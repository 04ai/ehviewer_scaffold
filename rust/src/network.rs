use anyhow::Result;
use futures::StreamExt;
use reqwest::{Client, header};
use tokio::sync::RwLock;

const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

/// Extra attempts for idempotent GET requests beyond the first try
/// (3 attempts total: 1 initial + 2 retries).
const GET_MAX_RETRIES: u32 = 2;
/// Exponential backoff base for GET retries: 500ms, then 1s.
const RETRY_BASE_DELAY_MS: u64 = 500;
/// If no data arrives for this long while streaming an image body, the
/// download is considered stalled (the server may have dropped the socket).
const STREAM_IDLE_TIMEOUT_SECS: u64 = 60;

pub struct NetworkClient {
    client: RwLock<Client>,
    /// Raw Cookie header string — set directly in each request (more reliable than Jar)
    cookie_string: RwLock<String>,
    /// Base site URL: "https://e-hentai.org" or "https://exhentai.org"
    pub site_url: RwLock<String>,
}

impl NetworkClient {
    pub fn new() -> Self {
        Self {
            client: RwLock::new(Self::build_client()),
            cookie_string: RwLock::new(String::new()),
            site_url: RwLock::new("https://e-hentai.org".to_string()),
        }
    }

    /// Build a client using the system DNS resolver as-is.
    /// (The old built-in hosts / IP-override feature was removed: hardcoded
    /// IPs went stale and overrode reachable DNS results, breaking images.)
    fn build_client() -> Client {
        let mut headers = header::HeaderMap::new();
        headers.insert(
            header::ACCEPT,
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                .parse().unwrap(),
        );
        headers.insert(
            header::ACCEPT_LANGUAGE,
            "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7".parse().unwrap(),
        );

        Client::builder()
            .default_headers(headers)
            .user_agent(USER_AGENT)
            .pool_idle_timeout(Some(std::time::Duration::from_secs(90)))
            .pool_max_idle_per_host(10)
            // Generous limits: EH image CDNs (hath.network) are slow and
            // flaky — 4s connect / 30s total was causing spurious "operation
            // timed out" failures on large webp files.
            .connect_timeout(std::time::Duration::from_secs(10))
            .timeout(std::time::Duration::from_secs(120))
            .build()
            .expect("Failed to build reqwest client")
    }

    /// Rebuild the client (kept for API compatibility; the enable_*_host
    /// flags from the removed built-in hosts feature are no longer used).
    pub async fn rebuild_client(&self) {
        let new_client = Self::build_client();
        let mut c = self.client.write().await;
        *c = new_client;
        log::info!("Reqwest client rebuilt (system DNS used as-is).");
    }

    /// Store raw Cookie string to be sent with every request
    pub async fn update_cookies(&self, cookie_str: &str) -> Result<()> {
        let mut c = self.cookie_string.write().await;
        *c = cookie_str.trim().to_string();
        // Log the length only: the cookie string contains session secrets
        // (ipb_pass_hash / igneous) and must not end up in logcat.
        log::info!("Cookies updated ({} chars)", c.len());
        Ok(())
    }

    /// Switch between E-Hentai and ExHentai
    pub async fn update_site_url(&self, url: &str) {
        let mut s = self.site_url.write().await;
        *s = url.trim_end_matches('/').to_string();
        log::info!("Site URL set to: {}", *s);
    }

    pub async fn get_site_url(&self) -> String {
        self.site_url.read().await.clone()
    }

    /// Send an idempotent GET with automatic retries on transient failures
    /// (connection errors, timeouts, 5xx). 4xx responses (auth failures,
    /// Cloudflare challenges, missing pages) are returned immediately since
    /// retrying cannot fix them. Backoff: 500ms → 1s.
    async fn get_with_retry(&self, url: &str) -> Result<reqwest::Response> {
        let cookie = self.cookie_string.read().await.clone();
        let site   = self.site_url.read().await.clone();
        let client = self.client.read().await.clone();

        let mut last_err: Option<anyhow::Error> = None;
        for attempt in 0..=GET_MAX_RETRIES {
            let mut req = client.get(url).header(header::REFERER, &site);
            if !cookie.is_empty() {
                req = req.header(header::COOKIE, &cookie);
            }

            match req.send().await {
                Ok(res) => {
                    if res.status().is_success() {
                        return Ok(res);
                    }
                    let err = res.error_for_status().unwrap_err();
                    let status = err.status();
                    if status.is_none() || !status.unwrap().is_server_error() {
                        return Err(err.into()); // 4xx / unknown: give up now
                    }
                    last_err = Some(err.into());
                }
                Err(e) => last_err = Some(e.into()),
            }

            if attempt < GET_MAX_RETRIES {
                let delay = RETRY_BASE_DELAY_MS * (1u64 << attempt);
                log::warn!(
                    "GET {} failed (attempt {}/{}), retrying in {}ms: {}",
                    url,
                    attempt + 1,
                    GET_MAX_RETRIES + 1,
                    delay,
                    last_err.as_ref().unwrap()
                );
                tokio::time::sleep(std::time::Duration::from_millis(delay)).await;
            }
        }
        Err(last_err.unwrap_or_else(|| anyhow::anyhow!("GET failed: {}", url)))
    }

    /// Fetch HTML — manually sets Cookie header for reliable auth
    pub async fn get_html(&self, url: &str) -> Result<String> {
        let res = self.get_with_retry(url).await?;
        log::info!("GET {} → {}", url, res.status());
        let html = res.text().await?;
        log::info!("HTML length: {} bytes", html.len());
        Ok(html)
    }

    /// Fetch raw bytes for image caching
    pub async fn get_bytes(&self, url: &str) -> Result<Vec<u8>> {
        let res = self.get_with_retry(url).await?;
        let bytes = res.bytes().await?;
        Ok(bytes.to_vec())
    }

    /// Fetch raw bytes while reporting download progress.
    /// `on_progress` is invoked after every received chunk with
    /// `(downloaded_bytes, total_bytes)`; `total` is `None` when the server
    /// omits Content-Length. A final call with the complete size is always
    /// made so callers can rely on seeing 100% at the end.
    pub async fn get_bytes_with_progress<F, Fut>(
        &self,
        url: &str,
        mut on_progress: F,
    ) -> Result<Vec<u8>>
    where
        F: FnMut(u64, Option<u64>) -> Fut + Send + 'static,
        Fut: futures::Future<Output = ()> + Send + 'static,
    {
        let res = self.get_with_retry(url).await?;
        let total = res.content_length();
        let mut stream = res.bytes_stream();
        let mut buf = Vec::with_capacity(total.unwrap_or(0) as usize);
        while let Some(chunk) = tokio::time::timeout(
            std::time::Duration::from_secs(STREAM_IDLE_TIMEOUT_SECS),
            stream.next(),
        )
        .await
        .map_err(|_| anyhow::anyhow!(
            "image download stalled (no data for {}s)", STREAM_IDLE_TIMEOUT_SECS
        ))?
        {
            let chunk = chunk?;
            buf.extend_from_slice(&chunk);
            if let Some(total) = total {
                on_progress(buf.len() as u64, Some(total)).await;
            }
        }
        on_progress(buf.len() as u64, total).await;
        Ok(buf)
    }

    /// POST Form Data
    pub async fn post_form(&self, url: &str, form_data: &[(&str, &str)]) -> Result<String> {
        let cookie = self.cookie_string.read().await.clone();
        let site   = self.site_url.read().await.clone();
        let client = self.client.read().await.clone();

        let mut req = client.post(url)
            .header(header::REFERER, url)
            .header(header::ORIGIN, &site)
            .form(form_data);

        if !cookie.is_empty() {
            req = req.header(header::COOKIE, &cookie);
        }

        let res = req.send().await?.error_for_status()?;
        log::info!("POST Form {} → {}", url, res.status());
        let body = res.text().await?;
        Ok(body)
    }

    /// POST JSON (Used for api.php endpoints like rating and autocomplete)
    pub async fn post_json(&self, url: &str, json_data: &serde_json::Value) -> Result<String> {
        let cookie = self.cookie_string.read().await.clone();
        let site   = self.site_url.read().await.clone();
        let client = self.client.read().await.clone();

        let mut req = client.post(url)
            .header(header::REFERER, &site)
            .json(json_data);

        if !cookie.is_empty() {
            req = req.header(header::COOKIE, &cookie);
        }

        let res = req.send().await?.error_for_status()?;
        log::info!("POST JSON {} → {}", url, res.status());
        let body = res.text().await?;
        Ok(body)
    }
}
