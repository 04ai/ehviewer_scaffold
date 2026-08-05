use anyhow::Result;
use serde::{Deserialize, Serialize};
use scraper::{Html, Selector};

#[derive(Debug, Clone, Serialize, Deserialize)]
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

/// Parse gallery comments from a detail-page HTML document
/// (structure: #cdiv > div.c1 [id="c_<n>"] > div.c3 [meta] + div.c6 [content],
/// plus optional vote links / score spans).
pub fn parse_comments(html: &str) -> Vec<GalleryComment> {
    let document = Html::parse_document(html);
    let mut comments = Vec::new();
    let c1_sel = Selector::parse("div.c1").unwrap();
    let c3_sel = Selector::parse("div.c3").unwrap();
    let c6_sel = Selector::parse("div.c6").unwrap();
    let author_sel = Selector::parse("a").unwrap();
    let vote_sel = Selector::parse("a[href*='act=vote']").unwrap();
    let score_sel = Selector::parse("span[id*='comment_score'], span.score, span#score").unwrap();

    for c1 in document.select(&c1_sel) {
        // Comment id: <div class="c1" id="c_123456">
        let mut cid: u64 = 0;
        if let Some(id_attr) = c1.value().attr("id") {
            if let Some(rest) = id_attr.strip_prefix("c_") {
                cid = rest.parse().unwrap_or(0);
            }
        }

        if let Some(c3) = c1.select(&c3_sel).next() {
            let c3_text = c3.text().collect::<String>();
            let mut time = String::new();
            if let Some(start) = c3_text.find("Posted on ") {
                if let Some(end) = c3_text.find(" by:") {
                    time = c3_text[start + 10 .. end].trim().to_string();
                } else {
                    time = c3_text[start + 10 ..].trim().to_string();
                }
            }
            let author = c3.select(&author_sel).next().map(|el| el.text().collect::<String>().trim().to_string()).unwrap_or_default();

            if let Some(c6) = c1.select(&c6_sel).next() {
                let content = c6.text().collect::<String>().trim().to_string();
                if !author.is_empty() {
                    // Vote link (first one; it encodes vote=1 for like).
                    let vote_url = c1.select(&vote_sel).next()
                        .and_then(|el| el.value().attr("href"))
                        .unwrap_or("")
                        .to_string();
                    // Score: prefer a dedicated span, fall back to a signed number
                    // in the vote area.
                    let mut score: i32 = 0;
                    if let Some(s) = c1.select(&score_sel).next() {
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
    let sel = Selector::parse("input[name='vote_key']").unwrap();
    document.select(&sel).next()?.value().attr("value").map(|v| v.to_string())
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
    let next_sel = Selector::parse("a#pnext, a#dnext, a#next").unwrap();
    if let Some(next_node) = document.select(&next_sel).next() {
        if let Some(href) = next_node.value().attr("href") {
            // E-Hentai sometimes disables the next button but leaves the link, or removes href.
            // If it exists and isn't #, use it.
            if href.contains("?next=") || href.contains("&next=") || href.contains("page=") {
                next_url = Some(href.to_string());
            }
        }
    }

    // E-Hentai uses `table.itg tr` for Minimal/Compact/Extended layouts,
    // and `div.gl1t` for Thumbnail layouts. This covers all of them!
    let item_sel = Selector::parse("table.itg tr, div.gl1t").unwrap();
    let link_sel = Selector::parse("a[href*='/g/']").unwrap();
    // The thumbnail is always wrapped inside a link to the gallery. This prevents picking up category/rating images.
    let img_sel  = Selector::parse("a[href*='/g/'] img, div.glthumb img, .gl1t img").unwrap();
    let title_sel = Selector::parse(".glink").unwrap();
    let cat_sel   = Selector::parse(".cn, .cs").unwrap();
    // Uploader is usually a link to /uploader/, or text in glhide
    let upload_sel = Selector::parse("a[href*='/uploader/']").unwrap();

    for item in document.select(&item_sel) {
        // 1. Link (URL gives us gid and token)
        let href = item.select(&link_sel)
            .filter_map(|el| el.value().attr("href"))
            .find(|href| href.contains("/g/"));

        // 2. Title
        let title = item.select(&title_sel)
            .next()
            .map(|el| el.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        // 3. Thumb URL (Check data-src first for lazy load, then src)
        let thumb_url = item.select(&img_sel)
            .next()
            .and_then(|img| {
                img.value().attr("data-src")
                    .or_else(|| img.value().attr("src"))
            })
            .unwrap_or("")
            .to_string();

        // 4. Category
        let category = item.select(&cat_sel)
            .next()
            .map(|el| el.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        // 5. Uploader
        let uploader = item.select(&upload_sel)
            .next()
            .map(|el| el.text().collect::<String>().trim().to_string())
            .unwrap_or_default();

        // 6. Post Date
        let mut post_date = String::new();
        let date_sel = Selector::parse("div[id^='posted_']").unwrap();
        if let Some(el) = item.select(&date_sel).next() {
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

    let title_sel = Selector::parse("h1#gn").unwrap();
    let title_jpn_sel = Selector::parse("h1#gj").unwrap();
    let cover_sel = Selector::parse("div#gd1 div").unwrap();
    let uploader_sel = Selector::parse("div#gdn a").unwrap();
    let rating_sel = Selector::parse("td#rating_label").unwrap();
    let tag_row_sel = Selector::parse("div#taglist table tr").unwrap();
    let tag_group_sel = Selector::parse("td.tc").unwrap();
    let tag_name_sel = Selector::parse("div a").unwrap();
    let thumb_link_sel = Selector::parse("div#gdt a[href*='/s/']").unwrap();
    let inner_div_sel = Selector::parse("div").unwrap();

    let mut image_urls = Vec::new();
    let mut thumbnails = Vec::new();

    for el in document.select(&thumb_link_sel) {
        if let Some(href) = el.value().attr("href") {
            image_urls.push(href.to_string());
        }

        // Try to parse sprite info from inner div
        let mut width = 200;
        let mut height = 280;
        let mut offset_x = 0;
        let mut offset_y = 0;
        let mut url = String::new();

        if let Some(inner) = el.select(&inner_div_sel).next() {
            if let Some(style) = inner.value().attr("style") {
                // e.g. "width:200px;height:282px;background:transparent url(https://...) -200px -150px no-repeat"
                if let Some(w_start) = style.find("width:") {
                    let w_end = style[w_start..].find("px").unwrap_or(0) + w_start;
                    if let Ok(w) = style[w_start + 6..w_end].parse::<u32>() {
                        width = w;
                    }
                }
                if let Some(h_start) = style.find("height:") {
                    let h_end = style[h_start..].find("px").unwrap_or(0) + h_start;
                    if let Ok(h) = style[h_start + 7..h_end].parse::<u32>() {
                        height = h;
                    }
                }
                if let Some(u_start) = style.find("url(") {
                    if let Some(u_end) = style[u_start..].find(')') {
                        url = style[u_start + 4 .. u_start + u_end].trim_matches(|c| c == '\'' || c == '\"').to_string();
                        
                        // Parse X and Y offsets after url(...)
                        let after_url = &style[u_start + u_end + 1 ..];
                        let tokens: Vec<&str> = after_url.split_whitespace().collect();
                        let mut numeric_offsets = Vec::new();
                        for token in tokens {
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
        }
        thumbnails.push(GalleryThumbnail { url, width, height, offset_x, offset_y });
    }

    let title = document.select(&title_sel).next()
        .map(|el| el.text().collect::<String>().trim().to_string())
        .unwrap_or_default();

    let title_jpn = document.select(&title_jpn_sel).next()
        .map(|el| el.text().collect::<String>().trim().to_string())
        .unwrap_or_default();

    // E-Hentai cover is usually in a style attribute: background:transparent url(https://...)
    let mut cover_url = String::new();
    if let Some(cover_el) = document.select(&cover_sel).next() {
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
        if let Ok(img_sel) = Selector::parse("div#gd1 img") {
            if let Some(img) = document.select(&img_sel).next() {
                cover_url = img.value().attr("src").unwrap_or("").to_string();
            }
        }
    }

    let uploader = document.select(&uploader_sel).next()
        .map(|el| el.text().collect::<String>().trim().to_string())
        .unwrap_or_default();

    let rating = document.select(&rating_sel).next()
        .map(|el| el.text().collect::<String>().trim().strip_prefix("Average: ").unwrap_or("").to_string())
        .unwrap_or_default();

    let mut tag_groups = Vec::new();
    for row in document.select(&tag_row_sel) {
        let group_name = row.select(&tag_group_sel).next()
            .map(|el| el.text().collect::<String>().trim().trim_end_matches(':').to_string())
            .unwrap_or_default();
        
        let tags = row.select(&tag_name_sel)
            .map(|el| el.text().collect::<String>().trim().to_string())
            .collect::<Vec<_>>();
            
        if !group_name.is_empty() && !tags.is_empty() {
            tag_groups.push(TagGroup { group_name, tags });
        }
    }

    let gdd_row_sel = Selector::parse("div#gdd tr").unwrap();
    let gdd_td1_sel = Selector::parse("td.gdt1").unwrap();
    let gdd_td2_sel = Selector::parse("td.gdt2").unwrap();

    let mut language = String::new();
    let mut file_size = String::new();
    let mut post_date = String::new();
    let mut total_pages_str = String::new();

    for row in document.select(&gdd_row_sel) {
        let key = row.select(&gdd_td1_sel).next().map(|el| el.text().collect::<String>().trim().to_string()).unwrap_or_default();
        let val = row.select(&gdd_td2_sel).next().map(|el| el.text().collect::<String>().trim().to_string()).unwrap_or_default();
        
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

    let favcount_sel = Selector::parse("td#favcount, p#favcount").unwrap();
    let favorites_count = document.select(&favcount_sel).next()
        .map(|el| el.text().collect::<String>().replace(" times", "").trim().to_string())
        .unwrap_or_else(|| "0".to_string());

    let torrent_sel = Selector::parse("a[href*='&fs=1']").unwrap();
    let torrent_count = document.select(&torrent_sel).next()
        .map(|el| el.text().collect::<String>().replace("Torrent Download ( ", "").replace(" )", "").trim().to_string())
        .unwrap_or_else(|| "0".to_string());

    let comments = parse_comments(html);

    let total_pages = total_pages_str.parse::<u32>().unwrap_or(image_urls.len() as u32);

    let favlink_sel = Selector::parse("a#favoritelink").unwrap();
    let is_favorited = document.select(&favlink_sel).next()
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
    let img_sel = Selector::parse("img#img").unwrap();
    
    if let Some(img_el) = document.select(&img_sel).next() {
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
    let Ok(form_sel) = Selector::parse("#torrentinfo > div:nth-child(1) > form") else {
        return Vec::new();
    };
    let Ok(tr_sel) = Selector::parse("table > tbody > tr") else {
        return Vec::new();
    };
    let Ok(td_sel) = Selector::parse("td") else {
        return Vec::new();
    };
    let Ok(a_sel) = Selector::parse("a") else {
        return Vec::new();
    };

    let document = Html::parse_document(html);
    let mut items = Vec::new();
    let mut global_token = String::new();

    for form in document.select(&form_sel) {
        let mut fields: std::collections::HashMap<String, String> = std::collections::HashMap::new();
        let mut name = String::new();
        let mut hash = String::new();
        let mut token = String::new();

        for row in form.select(&tr_sel) {
            for td in row.select(&td_sel) {
                let mut is_file_link = false;
                if let Some(a) = td.select(&a_sel).next() {
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

#[cfg(test)]
mod tests {
    use super::{parse_gallery_detail, parse_gallery_list};

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
}
