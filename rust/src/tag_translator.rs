use std::collections::HashMap;
use std::fs;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::Deserialize;
use anyhow::Result;
use lazy_static::lazy_static;

lazy_static! {
    static ref TAG_DB: Arc<RwLock<HashMap<String, HashMap<String, String>>>> = Arc::new(RwLock::new(HashMap::new()));
}

#[derive(Deserialize)]
struct TagDatabase {
    data: Vec<NamespaceData>,
}

#[derive(Deserialize)]
struct NamespaceData {
    namespace: String,
    data: HashMap<String, TagData>,
}

#[derive(Deserialize)]
struct TagData {
    name: String,
}

/// A raw E-Hentai tag matched by its Chinese translation, e.g.
/// raw "parody:genshin impact" translated "原神".
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct TagSuggestion {
    pub raw: String,
    pub translated: String,
}



/// Reverse-search the tag database and common tags: given a keyword in Chinese or
/// English, return matching raw E-Hentai tags with Chinese translations.
pub fn search_tag_by_chinese(keyword: String) -> Vec<TagSuggestion> {
    let kw = keyword.trim().to_lowercase();
    if kw.is_empty() {
        return Vec::new();
    }

    let mut results: Vec<(u8, TagSuggestion)> = Vec::new();
    let mut seen = std::collections::HashSet::new();

    // 1. Search loaded database
    if let Ok(db) = TAG_DB.try_read() {
        for (ns, tags) in db.iter() {
            for (tag, translated) in tags.iter() {
                let t = translated.to_lowercase();
                let tag_lower = tag.to_lowercase();
                let raw = if ns.is_empty() {
                    tag.clone()
                } else {
                    format!("{}:{}", ns, tag)
                };
                let raw_lower = raw.to_lowercase();

                let matched_t = t.find(&kw);
                let matched_tag = tag_lower.find(&kw);
                let matched_raw = raw_lower.find(&kw);

                if matched_t.is_some() || matched_tag.is_some() || matched_raw.is_some() {
                    let rank = if t == kw || tag_lower == kw || raw_lower == kw {
                        0
                    } else if t.starts_with(&kw) || tag_lower.starts_with(&kw) || raw_lower.starts_with(&kw) {
                        1
                    } else if matched_t == Some(0) || matched_tag == Some(0) || matched_raw == Some(0) {
                        1
                    } else {
                        2
                    };
                    if seen.insert(raw.clone()) {
                        results.push((rank, TagSuggestion { raw, translated: translated.clone() }));
                    }
                }
            }
        }
    }



    results.sort_by(|a, b| a.0.cmp(&b.0));
    results.truncate(12);
    results.into_iter().map(|(_, s)| s).collect()
}

/// Download the tag database from GitHub and save it to the given path
pub async fn download_tag_db(path: String) -> Result<()> {
    log::info!("Downloading tag database to {}", path);
    let url = "https://github.com/EhTagTranslation/Database/releases/latest/download/db.text.json";
    
    // We create a fresh client because the global NETWORK_CLIENT might have hosts overridden
    // and github might fail with that.
    let client = reqwest::Client::builder()
        .user_agent("Mozilla/5.0")
        .build()?;
        
    let res = client.get(url).send().await?;
    let bytes = res.bytes().await?;
    
    fs::write(&path, &bytes)?;
    log::info!("Tag database downloaded and saved.");
    
    // Load it into memory
    load_tag_db(path).await?;
    Ok(())
}

/// Load the tag database from local disk into memory
pub async fn load_tag_db(path: String) -> Result<()> {
    log::info!("Loading tag database from {}", path);
    let content = fs::read_to_string(path)?;
    let parsed: TagDatabase = serde_json::from_str(&content)?;
    
    let mut db_map = HashMap::new();
    for ns_data in parsed.data {
        let mut tag_map = HashMap::new();
        for (tag_key, tag_val) in ns_data.data {
            tag_map.insert(tag_key, tag_val.name);
        }
        db_map.insert(ns_data.namespace, tag_map);
    }
    
    let mut w = TAG_DB.write().await;
    *w = db_map;
    log::info!("Tag database loaded into memory.");
    Ok(())
}

/// Translate a tag synchronously. Fast memory lookup with common tag fallback.
pub fn translate_tag_sync(namespace: String, tag: String) -> String {
    let ns = namespace.trim();
    let t = tag.trim();

    // 1. Precise memory lookup from loaded DB
    if let Ok(db) = TAG_DB.try_read() {
        let has_ns = !matches!(ns, "rows" | "");
        if has_ns {
            if let Some(ns_map) = db.get(ns) {
                if let Some(translated) = ns_map.get(t) {
                    return translated.clone();
                }
            }
        } else {
            for ns_map in db.values() {
                if let Some(translated) = ns_map.get(t) {
                    return translated.clone();
                }
            }
        }
    }



    tag // fallback to original
}
