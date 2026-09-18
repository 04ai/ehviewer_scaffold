use anyhow::Result;
use serde::{Deserialize, Serialize};
use scraper::{Html, Selector};
use lazy_static::lazy_static;

lazy_static! {
    static ref S_DIV_C1: Selector = Selector::parse("div.c1").unwrap();
    static ref S_DIV_C3: Selector = Selector::parse("div.c3").unwrap();
    static ref S_DIV_C6: Selector = Selector::parse("div.c6").unwrap();
    static ref S_A: Selector = Selector::parse("a").unwrap();
    static ref S_VOTE_LINK: Selector = Selector::parse("a[href*='act=vote'], a[href*='gallerycomments.php']").unwrap();
    static ref S_COMMENT_SCORE: Selector = Selector::parse("span[id*='comment_score'], span.score, span#score").unwrap();

    static ref S_VOTE_KEY: Selector = Selector::parse("input[name='vote_key']").unwrap();

    static ref S_NEXT: Selector = Selector::parse("a#pnext, a#dnext, a#next").unwrap();
    static ref S_ITEM: Selector = Selector::parse("table.itg tr, div.gl1t").unwrap();
    static ref S_LINK_G: Selector = Selector::parse("a[href*='/g/']").unwrap();
    static ref S_IMG_LIST: Selector = Selector::parse("a[href*='/g/'] img, div.glthumb img, .gl1t img").unwrap();
    static ref S_GLINK: Selector = Selector::parse(".glink").unwrap();
    static ref S_CAT: Selector = Selector::parse(".cn, .cs").unwrap();
    static ref S_UPLOADER: Selector = Selector::parse("a[href*='/uploader/']").unwrap();
    static ref S_DATE: Selector = Selector::parse("div[id^='posted_']").unwrap();

    static ref S_GN: Selector = Selector::parse("h1#gn").unwrap();
    static ref S_GJ: Selector = Selector::parse("h1#gj").unwrap();
    static ref S_GD1_DIV: Selector = Selector::parse("div#gd1 div").unwrap();
    static ref S_GDN_A: Selector = Selector::parse("div#gdn a").unwrap();
    static ref S_RATING: Selector = Selector::parse("td#rating_label").unwrap();
    static ref S_TAG_ROW: Selector = Selector::parse("div#taglist table tr").unwrap();
    static ref S_TAG_GROUP: Selector = Selector::parse("td.tc").unwrap();
    static ref S_DIV_A: Selector = Selector::parse("div a").unwrap();
    static ref S_GDT_ITEM: Selector = Selector::parse("div#gdt > div, div.gdtm, div.gdtl").unwrap();
    static ref S_LINK_S: Selector = Selector::parse("a[href*='/s/']").unwrap();
    static ref S_IMG: Selector = Selector::parse("img").unwrap();
    static ref S_DIV_STYLE: Selector = Selector::parse("div[style]").unwrap();
    static ref S_DIRECT_LINK: Selector = Selector::parse("div#gdt a[href*='/s/']").unwrap();
    static ref S_GD1_IMG: Selector = Selector::parse("div#gd1 img").unwrap();
    static ref S_GDD_ROW: Selector = Selector::parse("div#gdd tr").unwrap();
    static ref S_GDD_TD1: Selector = Selector::parse("td.gdt1").unwrap();
    static ref S_GDD_TD2: Selector = Selector::parse("td.gdt2").unwrap();
    static ref S_FAVCOUNT: Selector = Selector::parse("td#favcount, p#favcount").unwrap();
    static ref S_TORRENT: Selector = Selector::parse("a[href*='&fs=1']").unwrap();
    static ref S_FAVLINK: Selector = Selector::parse("a#favoritelink").unwrap();

    static ref S_IMG_IMG: Selector = Selector::parse("img#img").unwrap();

    static ref S_FORM_TORRENT: Selector = Selector::parse("#torrentinfo > div:nth-child(1) > form").unwrap();
    static ref S_TR: Selector = Selector::parse("table > tbody > tr").unwrap();
    static ref S_TD: Selector = Selector::parse("td").unwrap();

    static ref S_INPUT: Selector = Selector::parse("input").unwrap();
    static ref S_SELECT: Selector = Selector::parse("select").unwrap();
    static ref S_OPTION: Selector = Selector::parse("option").unwrap();
    static ref S_TEXTAREA: Selector = Selector::parse("textarea").unwrap();
}#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GalleryItem {
    pub gid: String,
    pub token: String,
    pub title: String,
    pub thumb_url: String,
    pub category: String,
    pub uploader: String,
    pub post_date: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TagGroup {
    pub group_name: String,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GalleryThumbnail {
    pub url: String, // sprite URL or direct image URL
    pub width: u32,
    pub height: u32,
    pub offset_x: u32,
    pub offset_y: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GalleryComment {
    pub author: String,
    pub time: String,
    pub content: String,
    /// E-Hentai comment id (from `<div class="c1" id="c_123456">`), 0 when absent.
    pub id: u64,
    /// Comment vote score (upvotes - downvotes), 0 when unavailable.
    pub score: i32,
    /// Vote link parsed from the detail page HTML (`...act=vote&comment_id=...&vote=1`).
    /// Relative URLs are completed against the site URL at vote time.
    pub vote_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EhWebConfig {
    pub load_hath: String,
    pub image_size: String,
    pub image_width: String,
    pub image_height: String,
    pub title_display: String,
    pub archiver_settings: String,
    pub display_mode: String,
    pub favorite_names: Vec<String>,
    pub raw_params: std::collections::HashMap<String, String>,
}

/// Parse gallery comments from a detail-page HTML document
/// (structure: #cdiv > div.c1 [id="c_<n>"] > div.c3 [meta] + div.c6 [content],
/// plus optional vote links / score spans).
pub fn parse_comments(html: &str) -> Vec<GalleryComment> {
    let document = Html::parse_document(html);
    let mut comments = Vec::new();

    for c1 in document.select(&S_DIV_C1) {
        // Comment id: <div class="c1" id="c_123456"> or <a name="c123456">
        let mut cid: u64 = 0;
        let c1_html = c1.html();
        if let Some(id_attr) = c1.value().attr("id") {
            if let Some(rest) = id_attr.strip_prefix("c_") {
                cid = rest.parse().unwrap_or(0);
            }
        }
        if cid == 0 {
            for key in &["comment_id=", "vote_comment(", "id=\"comment_", "id=\"cvotes_", "id=\"comment_score_", "id=\"c_", "name=\"c"] {
                if let Some(pos) = c1_html.find(key) {
                    let rest = &c1_html[pos + key.len()..];
                    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
                    if let Ok(val) = digits.parse::<u64>() {
                        if val > 0 {
                            cid = val;
                            break;
                        }
                    }
                }
            }
        }

        if let Some(c3) = c1.select(&S_DIV_C3).next() {
            let c3_text = c3.text().collect::<String>();
            let mut time = String::new();
            if let Some(start) = c3_text.find("Posted on ") {
                if let Some(end) = c3_text.find(" by:") {
                    time = c3_text[start + 10 .. end].trim().to_string();
                } else {
                    time = c3_text[start + 10 ..].trim().to_string();
                }
            }
            let author = c3.select(&S_A).next().map(|el| el.text().collect::<String>().trim().to_string()).unwrap_or_default();

            if let Some(c6) = c1.select(&S_DIV_C6).next() {
                let content = c6.text().collect::<String>().trim().to_string();
                if !author.is_empty() {
                    // Vote link (first one; it encodes vote=1 for like).
                    let mut vote_url = c1.select(&S_VOTE_LINK).next()
                        .and_then(|el| el.value().attr("href"))
                        .unwrap_or("")
                        .to_string();
                    if vote_url.is_empty() && cid > 0 {
                        vote_url = format!("gallerycomments.php?act=vote&comment_id={}&vote=1", cid);
                    }
                    // Score: prefer a dedicated span, fall back to a signed number
                    // in the vote area.
                    let mut score: i32 = 0;
                    if let Some(s) = c1.select(&S_COMMENT_SCORE).next() {
                        let t = s.text().collect::<String>().trim().to_string();
                        score = t.parse().unwrap_or(0);
                    }
                    if score == 0 {
                        // Fall back to a signed number token in the comment text
                        // (e.g. "±12" / "-3" next to the vote links).
                        let c1_text = c1.text().collect::<String>();
                        for tok in c1_text.split_whitespace() {
                            let cleaned = tok.trim_matches(|c: char| !c.is_ascii_digit() && c != '-' && c != '+');
                            if let Ok(v) = cleaned.parse::<i32>() {
                                if v.abs() <= 1_000_000 {
                                    score = v;
                                    break;
                                }
                            }
                        }
                    }
                    comments.push(GalleryComment { author, time, content, id: cid, score, vote_url });
                }
            }
        }
    }
    comments
}
/// Parse the CSRF `vote_key` hidden input used by the web rating form.
/// Returns `None` when the form is missing (not logged in or unsupported page).
pub fn parse_vote_key(html: &str) -> Option<String> {
    let document = Html::parse_document(html);
    document.select(&S_VOTE_KEY).next()?.value().attr("value").map(|v| v.to_string())
}

#[derive(Debug, Serialize, Deserialize)]
pub struct GalleryDetail {
    pub id: String,
    pub title: String,
    pub title_jpn: String,
    pub cover_url: String,
    pub uploader: String,
    pub rating: String,
    pub language: String,
    pub file_size: String,
    pub post_date: String,
    pub favorites_count: String,
    pub torrent_count: String,
    pub tag_groups: Vec<TagGroup>,
    pub total_pages: u32,
    pub image_urls: Vec<String>, // these are the viewer page URLs
    pub thumbnails: Vec<GalleryThumbnail>, // The thumbnail sprites
    pub comments: Vec<GalleryComment>,
    pub is_favorited: bool,
}

/// Extract GID and token from E-Hentai gallery URL
/// URL format: https://e-hentai.org/g/{gid}/{token}/
pub fn parse_gid_token(url: &str) -> Option<(String, String)> {
    let parts: Vec<&str> = url.split('/').collect();
    if let Some(g_idx) = parts.iter().position(|&p| p == "g") {
        if g_idx + 2 < parts.len() {
            let gid = parts[g_idx + 1].to_string();
            let token = parts[g_idx + 2].to_string();
            if !gid.is_empty() && !token.is_empty() && gid.chars().all(|c| c.is_ascii_digit()) {
                return Some((gid, token));
            }
        }
    }
    None
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GalleryPage {
    pub items: Vec<GalleryItem>,
    pub next_url: Option<String>,
}

pub fn parse_gallery_list(html: &str) -> Result<GalleryPage> {
    let document = Html::parse_document(html);
    let mut items = Vec::new();
    let mut next_url = None;

    // Parse the "Next" page URL.
    // E-Hentai uses `id="pnext"` for Thumbnail layouts or `id="dnext"` for Minimal/Compact/Extended layouts.
    if let Some(next_node) = document.select(&S_NEXT).next() {
        if let Some(href) = next_node.value().attr("href") {
            // E-Hentai sometimes disables the next button but leaves the link, or removes href.
            // If it exists and isn't #, use it.
            if href.contains("?next=") || href.contains("&next=") || href.contains("page=") {
                next_url = Some(href.to_string());
            }
        }
    }

    for item in document.select(&S_ITEM) {
        // 1. Link (URL gives us gid and token)
        let href = item.select(&S_LINK_G)
            .filter_map(|el| el.value().attr("href"))
            .find(|href| href.contains("/g/"));

        // 2. Title
        let title = item.select(&S_GLINK)
            .next()
            .map(|el| el.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        // 3. Thumb URL (Check data-src first for lazy load, then src)
        let thumb_url = item.select(&S_IMG_LIST)
            .next()
            .and_then(|img| {
                img.value().attr("data-src")
                    .or_else(|| img.value().attr("src"))
            })
            .unwrap_or("")
            .to_string();

        // 4. Category
        let category = item.select(&S_CAT)
            .next()
            .map(|el| el.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        // 5. Uploader
        let uploader = item.select(&S_UPLOADER)
            .next()
            .map(|el| el.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        // 6. Post Date
        let mut post_date = String::new();
        if let Some(el) = item.select(&S_DATE).next() {
            post_date = el.text().collect::<String>().trim().to_string();
        } else {
            // Fallback: search for text matching date format in the item
            for el in item.text() {
                if el.contains("-") && el.contains(":") && el.len() >= 10 {
                    let trimmed = el.trim();
                    if trimmed.starts_with("20") {
                        post_date = trimmed.to_string();
                        break;
                    }
                }
            }
        }

        if let Some(href) = href {
            if let Some((gid, token)) = parse_gid_token(href) {
                // Must have a title to be considered a valid row (ignores table header rows)
                if !title.is_empty() {
                    items.push(GalleryItem {
                        gid,
                        token,
                        title,
                        thumb_url,
                        category,
                        uploader,
                        post_date,
                    });
                }
            }
        }
    }

    Ok(GalleryPage {
        items,
        next_url,
    })
}

pub fn parse_gallery_detail(html: &str) -> Result<GalleryDetail> {
    let document = Html::parse_document(html);

    let mut image_urls = Vec::new();
    let mut thumbnails = Vec::new();

    for item in document.select(&S_GDT_ITEM) {
        // Must contain a viewer link /s/
        let href = match item.select(&S_LINK_S).next().and_then(|a| a.value().attr("href")) {
            Some(h) if h.contains("/s/") => h.to_string(),
            _ => continue,
        };
        image_urls.push(href);

        let mut width = 0;
        let mut height = 0;
        let mut offset_x = 0;
        let mut offset_y = 0;
        let mut url = String::new();

        // 1. Check for CSS sprite sheet (Mode A: gdtm)
        // Style can be on item itself or any child div (e.g. <div class="gdtm"><div style="...">)
        let mut style_opt = item.value().attr("style").filter(|s| s.contains("url(") || s.contains("URL("));
        if style_opt.is_none() {
            for div in item.select(&S_DIV_STYLE) {
                if let Some(s) = div.value().attr("style") {
                    if s.contains("url(") || s.contains("URL(") {
                        style_opt = Some(s);
                        break;
                    }
                }
            }
        }

        if let Some(style) = style_opt {
            if let Some(w_start) = style.find("width:") {
                let w_end = style[w_start..].find("px").unwrap_or(0) + w_start;
                if let Ok(w) = style[w_start + 6..w_end].trim().parse::<u32>() {
                    width = w;
                }
            }
            if let Some(h_start) = style.find("height:") {
                let h_end = style[h_start..].find("px").unwrap_or(0) + h_start;
                if let Ok(h) = style[h_start + 7..h_end].trim().parse::<u32>() {
                    height = h;
                }
            }
            if let Some(u_start) = style.find("url(") {
                if let Some(u_end) = style[u_start..].find(')') {
                    url = style[u_start + 4..u_start + u_end]
                        .trim_matches(|c| c == '\'' || c == '\"')
                        .trim()
                        .to_string();

                    // Parse offsets after url(...), e.g. -200px -140px
                    let after_url = &style[u_start + u_end + 1..];
                    let mut numeric_offsets = Vec::new();
                    for token in after_url.split_whitespace() {
                        let clean_token = token.trim_end_matches("px");
                        if let Ok(val) = clean_token.parse::<i32>() {
                            numeric_offsets.push(val.abs() as u32);
                        }
                    }
                    if !numeric_offsets.is_empty() {
                        offset_x = numeric_offsets[0];
                    }
                    if numeric_offsets.len() >= 2 {
                        offset_y = numeric_offsets[1];
                    }
                }
            }
        }

        // 2. If no sprite url found, check for normal img src (Mode B: gdtl)
        if url.is_empty() {
            if let Some(img) = item.select(&S_IMG).next() {
                let s = img.value().attr("data-src")
                    .or_else(|| img.value().attr("src"))
                    .unwrap_or("");
                if !s.is_empty() && !s.contains("blank.gif") {
                    url = s.to_string();
                }
            }
        }

        thumbnails.push(GalleryThumbnail {
            url,
            width,
            height,
            offset_x,
            offset_y,
        });
    }

    // Fallback: If div#gdt > div didn't find items, fallback to a[href*='/s/'] directly
    if image_urls.is_empty() {
        for el in document.select(&S_DIRECT_LINK) {
            if let Some(href) = el.value().attr("href") {
                image_urls.push(href.to_string());
                let img_src = el.select(&S_IMG).next()
                    .and_then(|img| img.value().attr("src"))
                    .filter(|s| !s.contains("blank.gif"))
                    .unwrap_or("")
                    .to_string();
                thumbnails.push(GalleryThumbnail {
                    url: img_src,
                    width: 0,
                    height: 0,
                    offset_x: 0,
                    offset_y: 0,
                });
            }
        }
    }

    let title = document.select(&S_GN).next()
        .map(|el| el.text().collect::<String>().trim().to_string())
        .unwrap_or_default();

    let title_jpn = document.select(&S_GJ).next()
        .map(|el| el.text().collect::<String>().trim().to_string())
        .unwrap_or_default();

    // E-Hentai cover is usually in a style attribute: background:transparent url(https://...)
    let mut cover_url = String::new();
    if let Some(cover_el) = document.select(&S_GD1_DIV).next() {
        if let Some(style) = cover_el.value().attr("style") {
            if let Some(start) = style.find("url(") {
                let rest = &style[start + 4..];
                if let Some(end) = rest.find(")") {
                    cover_url = rest[..end].trim_matches(|c| c == '\'' || c == '\"').to_string();
                }
            }
        }
    }
    // Fallback if it's an img tag
    if cover_url.is_empty() {
        if let Some(img) = document.select(&S_GD1_IMG).next() {
            cover_url = img.value().attr("src").unwrap_or("").to_string();
        }
    }

    let uploader = document.select(&S_GDN_A).next()
        .map(|el| el.text().collect::<String>().trim().to_string())
        .unwrap_or_default();

    let rating = document.select(&S_RATING).next()
        .map(|el| el.text().collect::<String>().trim().strip_prefix("Average: ").unwrap_or("").to_string())
        .unwrap_or_default();

    let mut tag_groups = Vec::new();
    for row in document.select(&S_TAG_ROW) {
        let group_name = row.select(&S_TAG_GROUP).next()
            .map(|el| el.text().collect::<String>().trim().trim_end_matches(':').to_string())
            .unwrap_or_default();
        
        let tags = row.select(&S_DIV_A)
            .map(|el| el.text().collect::<String>().trim().to_string())
            .collect::<Vec<_>>();
            
        if !group_name.is_empty() && !tags.is_empty() {
            tag_groups.push(TagGroup { group_name, tags });
        }
    }

    let mut language = String::new();
    let mut file_size = String::new();
    let mut post_date = String::new();
    let mut total_pages_str = String::new();

    for row in document.select(&S_GDD_ROW) {
        let key = row.select(&S_GDD_TD1).next().map(|el| el.text().collect::<String>().trim().to_string()).unwrap_or_default();
        let val = row.select(&S_GDD_TD2).next().map(|el| el.text().collect::<String>().trim().to_string()).unwrap_or_default();
        
        if key.contains("Language:") {
            language = val.replace("  ", "").trim().to_string(); // sometimes has spaces/nbsp
        } else if key.contains("File Size:") {
            file_size = val;
        } else if key.contains("Posted:") {
            post_date = val;
        } else if key.contains("Length:") {
            total_pages_str = val.replace(" pages", "").trim().to_string();
        }
    }

    let favorites_count = document.select(&S_FAVCOUNT).next()
        .map(|el| el.text().collect::<String>().replace(" times", "").trim().to_string())
        .unwrap_or_else(|| "0".to_string());

    let torrent_count = document.select(&S_TORRENT).next()
        .map(|el| el.text().collect::<String>().replace("Torrent Download ( ", "").replace(" )", "").trim().to_string())
        .unwrap_or_else(|| "0".to_string());

    let comments = parse_comments(html);

    let total_pages = total_pages_str.parse::<u32>().unwrap_or(image_urls.len() as u32);

    let is_favorited = document.select(&S_FAVLINK).next()
        .map(|el| el.text().collect::<String>())
        .unwrap_or_default()
        .contains("Favorited");

    Ok(GalleryDetail {
        id: "".to_string(), // Filled by API layer
        title,
        title_jpn,
        cover_url,
        uploader,
        rating,
        language,
        file_size,
        post_date,
        favorites_count,
        torrent_count,
        tag_groups,
        total_pages,
        image_urls,
        thumbnails,
        comments,
        is_favorited,
    })
}

/// Parse the actual image URL from an E-Hentai viewer page (e.g. /s/...)
pub fn parse_image_url(html: &str) -> Result<String> {
    let document = Html::parse_document(html);
    
    if let Some(img_el) = document.select(&S_IMG_IMG).next() {
        if let Some(src) = img_el.value().attr("src") {
            return Ok(src.to_string());
        }
    }
    
    Err(anyhow::anyhow!("Failed to find image src in viewer page"))
}

/// A single torrent entry from the gallery torrents popup (/gallerytorrents.php).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TorrentItem {
    pub name: String,
    pub posted: String,
    pub size_text: String,
    pub seeds: String,
    pub peers: String,
    pub downloads: String,
    pub uploader: String,
    pub hash: String,
    pub token: String,
}

/// Find the first 40-char hex string (the SHA-1 info hash) in `s`.
fn find_hex40(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut i = 0;
    while i + 40 <= bytes.len() {
        let run = &bytes[i..i + 40];
        if run.iter().all(|b| b.is_ascii_hexdigit()) {
            let prev_ok = i > 0 && bytes[i - 1].is_ascii_hexdigit();
            let next_ok = i + 40 < bytes.len() && bytes[i + 40].is_ascii_hexdigit();
            if !prev_ok && !next_ok {
                return Some(s[i..i + 40].to_ascii_lowercase());
            }
        }
        i += 1;
    }
    None
}

/// Find a torrent id embedded in a URL like /get/<id>/ or /torrent/<id>/.
fn find_torrent_token(s: &str) -> Option<String> {
    let lower = s.to_lowercase();
    for needle in ["/get/", "/torrent/"] {
        let mut search_from = 0;
        while let Some(pos) = lower[search_from..].find(needle) {
            let digits_start = search_from + pos + needle.len();
            let digits: String = s[digits_start..]
                .chars()
                .take_while(|c| c.is_ascii_digit())
                .collect();
            if !digits.is_empty() {
                return Some(digits);
            }
            search_from = digits_start;
        }
    }
    None
}

/// Parse the torrent list from /gallerytorrents.php (one <form> per torrent).
pub fn parse_torrents(html: &str) -> Vec<TorrentItem> {
    let document = Html::parse_document(html);
    let mut items = Vec::new();
    let mut global_token = String::new();

    for form in document.select(&S_FORM_TORRENT) {
        let mut fields: std::collections::HashMap<String, String> = std::collections::HashMap::new();
        let mut name = String::new();
        let mut hash = String::new();
        let mut token = String::new();

        for row in form.select(&S_TR) {
            for td in row.select(&S_TD) {
                let mut is_file_link = false;
                if let Some(a) = td.select(&S_A).next() {
                    if let Some(href) = a.value().attr("href") {
                        if find_hex40(href).is_some() || find_torrent_token(href).is_some() {
                            name = a
                                .text()
                                .collect::<String>()
                                .split_whitespace()
                                .collect::<Vec<_>>()
                                .join(" ");
                            if hash.is_empty() {
                                hash = find_hex40(href).unwrap_or_default();
                            }
                            if token.is_empty() {
                                token = find_torrent_token(href).unwrap_or_default();
                            }
                            is_file_link = true;
                        }
                    }
                }
                if is_file_link {
                    continue;
                }
                let text = td.text().collect::<String>();
                for label in ["Posted", "Size", "Seeds", "Peers", "Downloads", "Uploader"] {
                    let needle = format!("{}:", label);
                    if let Some(idx) = text.find(&needle) {
                        let val = text[idx + needle.len()..].trim().to_string();
                        fields.insert(label.to_string(), val);
                    }
                }
            }
        }

        if name.is_empty() || hash.is_empty() {
            continue;
        }
        if global_token.is_empty() && !token.is_empty() {
            global_token = token.clone();
        }
        items.push(TorrentItem {
            name,
            posted: fields.get("Posted").cloned().unwrap_or_default(),
            size_text: fields.get("Size").cloned().unwrap_or_default(),
            seeds: fields.get("Seeds").cloned().unwrap_or_default(),
            peers: fields.get("Peers").cloned().unwrap_or_default(),
            downloads: fields.get("Downloads").cloned().unwrap_or_default(),
            uploader: fields.get("Uploader").cloned().unwrap_or_default(),
            hash,
            token,
        });
    }

    // Fallback: any token found anywhere on the page (all rows share it).
    if global_token.is_empty() {
        if let Some(t) = find_torrent_token(html) {
            global_token = t;
        }
    }
    for item in &mut items {
        if item.token.is_empty() {
            item.token = global_token.clone();
        }
    }
    items
}

/// Parse user configuration settings from uconfig.php HTML
pub fn parse_eh_web_config(html: &str) -> Result<EhWebConfig> {
    let document = Html::parse_document(html);
    let mut raw_params = std::collections::HashMap::new();

    for input in document.select(&S_INPUT) {
        let val_attr = input.value();
        let name = val_attr.attr("name").unwrap_or_default();
        if name.is_empty() {
            continue;
        }
        let typ = val_attr.attr("type").unwrap_or("text");
        let value = val_attr.attr("value").unwrap_or_default();

        if matches!(typ, "radio" | "checkbox") {
            if val_attr.attr("checked").is_some() {
                raw_params.insert(name.to_string(), value.to_string());
            }
        } else if !matches!(typ, "submit" | "button" | "reset" | "image") {
            raw_params.insert(name.to_string(), value.to_string());
        }
    }

    for select in document.select(&S_SELECT) {
        let name = select.value().attr("name").unwrap_or_default();
        if name.is_empty() {
            continue;
        }
        let mut selected_val = String::new();
        for opt in select.select(&S_OPTION) {
            let val = opt.value().attr("value").unwrap_or_default();
            if opt.value().attr("selected").is_some() || selected_val.is_empty() {
                selected_val = val.to_string();
            }
        }
        raw_params.insert(name.to_string(), selected_val);
    }

    for textarea in document.select(&S_TEXTAREA) {
        let name = textarea.value().attr("name").unwrap_or_default();
        if !name.is_empty() {
            let text = textarea.text().collect::<String>();
            raw_params.insert(name.to_string(), text);
        }
    }

    let load_hath = raw_params.get("uh").cloned().unwrap_or_else(|| "0".to_string());
    let image_size = raw_params.get("xr").cloned().unwrap_or_else(|| "0".to_string());
    let image_width = raw_params.get("xr_w").cloned().unwrap_or_default();
    let image_height = raw_params.get("xr_h").cloned().unwrap_or_default();
    let title_display = raw_params.get("lt").cloned().unwrap_or_else(|| "0".to_string());
    let archiver_settings = raw_params.get("ar").cloned().unwrap_or_else(|| "0".to_string());
    let display_mode = raw_params.get("dm").cloned().unwrap_or_else(|| "0".to_string());

    let mut favorite_names = Vec::new();
    for i in 0..10 {
        let key = format!("fn{}", i);
        let name = raw_params.get(&key).cloned().unwrap_or_else(|| format!("Favorite {}", i));
        favorite_names.push(name);
    }

    Ok(EhWebConfig {
        load_hath,
        image_size,
        image_width,
        image_height,
        title_display,
        archiver_settings,
        display_mode,
        favorite_names,
        raw_params,
    })
}

#[cfg(test)]
mod tests {
    use super::{parse_gallery_detail, parse_gallery_list, parse_eh_web_config};

    /// Regression fixture: real front-page HTML captured from e-hentai.org.
    /// Guards against silent parser breakage when the site tweaks its markup.
    #[test]
    fn parses_captured_front_page() {
        let html = std::fs::read_to_string("../test_list.html").expect("fixture missing");
        let page = parse_gallery_list(&html).expect("list should parse");
        assert!(!page.items.is_empty(), "expected gallery items in captured page");
        for item in &page.items {
            assert!(!item.gid.is_empty());
            assert!(!item.token.is_empty());
            assert!(!item.title.is_empty());
        }
    }

    #[test]
    fn parses_captured_detail_page() {
        let html = std::fs::read_to_string("../test_detail.html").expect("fixture missing");
        let detail = parse_gallery_detail(&html).expect("detail should parse");
        assert!(!detail.title.is_empty());
        assert!(!detail.image_urls.is_empty());
        assert!(!detail.thumbnails.is_empty());
    }

    #[test]
    fn parses_uconfig_html() {
        let sample_html = r#"
            <form id="outer" action="https://e-hentai.org/uconfig.php" method="post">
                <input type="radio" name="uh" value="0" checked="checked" />
                <input type="radio" name="xr" value="1" checked="checked" />
                <input type="radio" name="lt" value="1" checked="checked" />
                <input type="text" name="fn0" value="Manga" />
                <input type="text" name="fn1" value="Doujinshi" />
                <select name="dm"><option value="2" selected="selected">Thumbnail</option></select>
            </form>
        "#;
        let config = parse_eh_web_config(sample_html).expect("uconfig should parse");
        assert_eq!(config.load_hath, "0");
        assert_eq!(config.image_size, "1");
        assert_eq!(config.title_display, "1");
        assert_eq!(config.display_mode, "2");
        assert_eq!(config.favorite_names[0], "Manga");
        assert_eq!(config.favorite_names[1], "Doujinshi");
    }

    #[test]
    fn parses_sprite_and_large_thumbnails() {
        let sample_html = r#"
            <div id="gd1"><div style="background:transparent url(https://ehgt.org/cover.jpg) no-repeat"></div></div>
            <h1 id="gn">Sample Gallery Title</h1>
            <h1 id="gj">日本語タイトル</h1>
            <div id="gdt">
                <div class="gdtm" style="height:170px">
                    <div style="margin:1px auto 0; width:100px; height:140px; background:transparent url(https://ehgt.org/m/002345/2345678-00.jpg) -200px -140px no-repeat">
                        <a href="https://e-hentai.org/s/111111/2345678-1"><img alt="01" src="https://ehgt.org/g/blank.gif" /></a>
                    </div>
                </div>
                <div class="gdtl" style="height:370px">
                    <a href="https://e-hentai.org/s/222222/2345678-2">
                        <img alt="02" src="https://ehgt.org/t/23/45/2345678-02.jpg" style="height:340px; width:240px" />
                    </a>
                </div>
            </div>
            <div id="gdd">
                <table>
                    <tr><td class="gdt1">Length:</td><td class="gdt2">2 pages</td></tr>
                </table>
            </div>
        "#;
        let detail = parse_gallery_detail(sample_html).expect("detail should parse");
        assert_eq!(detail.image_urls.len(), 2);
        assert_eq!(detail.image_urls[0], "https://e-hentai.org/s/111111/2345678-1");
        assert_eq!(detail.image_urls[1], "https://e-hentai.org/s/222222/2345678-2");

        assert_eq!(detail.thumbnails.len(), 2);
        // Sprite thumbnail
        assert_eq!(detail.thumbnails[0].url, "https://ehgt.org/m/002345/2345678-00.jpg");
        assert_eq!(detail.thumbnails[0].width, 100);
        assert_eq!(detail.thumbnails[0].height, 140);
        assert_eq!(detail.thumbnails[0].offset_x, 200);
        assert_eq!(detail.thumbnails[0].offset_y, 140);

        // Large thumbnail
        assert_eq!(detail.thumbnails[1].url, "https://ehgt.org/t/23/45/2345678-02.jpg");
        assert_eq!(detail.thumbnails[1].offset_x, 0);
        assert_eq!(detail.thumbnails[1].offset_y, 0);
    }
}
