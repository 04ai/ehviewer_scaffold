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
#[derive(Debug, Clone)]
#[flutter_rust_bridge::frb]
pub struct TagSuggestion {
    pub raw: String,
    pub translated: String,
}

/// Reverse-search the loaded tag database: given a keyword (usually a Chinese
/// translation like "原神"), return raw E-Hentai tags whose translated name
/// contains the keyword, e.g. "parody:genshin impact".
#[flutter_rust_bridge::frb(sync)]
pub fn search_tag_by_chinese(keyword: String) -> Vec<TagSuggestion> {
    let kw = keyword.trim().to_lowercase();
    if kw.is_empty() {
        return Vec::new();
    }

    let mut results: Vec<(u8, TagSuggestion)> = Vec::new();
    if let Ok(db) = TAG_DB.try_read() {
        for (ns, tags) in db.iter() {
            for (tag, translated) in tags.iter() {
                let t = translated.to_lowercase();
                if let Some(pos) = t.find(&kw) {
                    let rank = if t == kw {
                        0
                    } else if t.starts_with(&kw) {
                        1
                    } else if pos == 0 {
                        1
                    } else {
                        2
                    };
                    let raw = if ns.is_empty() {
                        tag.clone()
                    } else {
                        format!("{}:{}", ns, tag)
                    };
                    results.push((rank, TagSuggestion { raw, translated: translated.clone() }));
                }
            }
        }
    }

    results.sort_by(|a, b| a.0.cmp(&b.0));
    results.truncate(10);
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

/// Translate a tag synchronously. Fast memory lookup.
#[flutter_rust_bridge::frb(sync)]
pub fn translate_tag_sync(namespace: String, tag: String) -> String {
    // We use a try_read because it's a sync function and we don't want to block the thread
    // if it's currently writing. If it's locked, just return original.
    if let Ok(db) = TAG_DB.try_read() {
        // e-hentai sometimes sends empty namespaces or short names
        let ns = namespace.trim();
        let t = tag.trim();
        
        // Map common namespaces if needed, usually they match
        // EhTagTranslation uses: rows, reclass, language, parody, character, group, artist, cosplay, male, female, mixed, other
        let mapped_ns = match ns {
            "rows" | "" => None,
            _ => Some(ns),
        };
        
        // Try precise match
        if let Some(n) = mapped_ns {
            if let Some(ns_map) = db.get(n) {
                if let Some(translated) = ns_map.get(t) {
                    return translated.clone();
                }
            }
        }
        
        // Try fuzzy match in "other" or "mixed" or without namespace
        for ns_map in db.values() {
            if let Some(translated) = ns_map.get(t) {
                return translated.clone();
            }
        }
    }
    
    tag // fallback to original
}
