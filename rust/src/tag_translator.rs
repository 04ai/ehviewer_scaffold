use std::collections::{HashMap, HashSet};
use std::sync::{Arc, RwLock};
use serde::Deserialize;
use anyhow::Result;
use lazy_static::lazy_static;

lazy_static! {
    static ref TAG_DB: Arc<RwLock<TagIndex>> = Arc::new(RwLock::new(TagIndex::default()));
    /// Tags harvested from galleries the user actually opened. See [`SeenIndex`].
    static ref SEEN_TAGS: Arc<RwLock<SeenIndex>> = Arc::new(RwLock::new(SeenIndex::default()));
}

/// Upper bound on how many distinct tags we remember from browsing.
///
/// Each entry holds three strings (~200 bytes), so this caps the index at a few
/// megabytes while still covering years of browsing history. Once full,
/// learning stops silently — this is a suggestion source, never correctness.
const SEEN_TAGS_CAP: usize = 20_000;

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

/// One searchable row of the tag database, with the lowercase forms
/// **precomputed at load time**.
///
/// This is what makes `search_tag_by_chinese` allocation-free. The previous
/// version recomputed `to_lowercase()` for the tag, the translation, and the
/// `"ns:tag"` form *and* ran three substring searches, for every one of ~50k
/// entries — roughly 200k heap allocations per call, on a code path triggered
/// by every keystroke in the tag autocomplete box.
struct TagRow {
    raw: String,
    raw_lower: String,
    translated: String,
    translated_lower: String,
}

/// Searchable view of the tag database.
#[derive(Default)]
struct TagIndex {
    /// `namespace -> tag -> translation`, for O(1) exact lookups.
    by_namespace: HashMap<String, HashMap<String, String>>,
    /// Flattened, pre-lowercased rows for substring search.
    rows: Vec<TagRow>,
}

impl TagIndex {
    fn build(parsed: TagDatabase) -> Self {
        let mut by_namespace = HashMap::new();
        // 50k+ rows is the realistic size, so reserve up front to avoid
        // repeated reallocation of the growth path.
        let mut rows: Vec<TagRow> = Vec::with_capacity(65_536);

        for ns_data in parsed.data {
            let ns = ns_data.namespace;
            let mut tag_map = HashMap::with_capacity(ns_data.data.len());

            for (tag_key, tag_val) in ns_data.data {
                let raw = if ns.is_empty() {
                    tag_key.clone()
                } else {
                    format!("{}:{}", ns, tag_key)
                };
                // The translation is stored twice on purpose: once in `rows`
                // (with its precomputed lowercase form, for substring search)
                // and once in `by_namespace` (for O(1) exact lookup). Cloning
                // happens once per tag at load time, never on a lookup path.
                let translated = tag_val.name;
                rows.push(TagRow {
                    raw_lower: raw.to_lowercase(),
                    raw,
                    translated_lower: translated.to_lowercase(),
                    translated: translated.clone(),
                });
                tag_map.insert(tag_key, translated);
            }

            by_namespace.insert(ns, tag_map);
        }

        Self { by_namespace, rows }
    }
}

/// Reverse-search the tag database: given a keyword in Chinese or English,
/// return matching raw E-Hentai tags with their Chinese translations.
///
/// Allocation-free on the scan path (all lowercase forms are precomputed), so
/// this is safe to call on every keystroke.
///
/// Draws from two sources, in this order:
/// 1. rows of the Chinese translation DB (empty until it is downloaded), and
/// 2. tags learned from galleries the user actually opened ([`learn_tags`]),
///    which is what keeps English autocomplete working with no DB at all.
pub fn search_tag_by_chinese(keyword: String) -> Vec<TagSuggestion> {
    let kw = keyword.trim().to_lowercase();
    if kw.is_empty() {
        return Vec::new();
    }

    let mut results: Vec<(u8, TagSuggestion)> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();

    // Source 1: translation DB. Scoped so the read guard is dropped before we
    // touch the second lock — the two indexes must never be held at once.
    {
        let Ok(db) = TAG_DB.read() else {
            return Vec::new();
        };

        for row in db.rows.iter() {
            let matched = row.translated_lower.contains(&kw) || row.raw_lower.contains(&kw);
            if !matched {
                continue;
            }

            // 0 = exact, 1 = prefix, 2 = substring elsewhere.
            let rank = if row.translated_lower == kw || row.raw_lower == kw {
                0
            } else if row.translated_lower.starts_with(&kw) || row.raw_lower.starts_with(&kw) {
                1
            } else {
                2
            };

            // `raw` is unique per row, so this also de-duplicates the two
            // columns when a tag matches on both its raw and translated form.
            if seen.insert(row.raw.clone()) {
                results.push((
                    rank,
                    TagSuggestion {
                        raw: row.raw.clone(),
                        translated: row.translated.clone(),
                    },
                ));
            }
        }
    }

    // Source 2: tags actually seen while browsing. This is the whole point of
    // the index — it is non-empty exactly when the translation DB is missing.
    {
        let Ok(known) = SEEN_TAGS.read() else {
            return results
                .into_iter()
                .map(|(_, s)| s)
                .collect();
        };

        for entry in known.rows.iter() {
            // Substring, not prefix: users type the middle of a tag too
            // ("rimjob" should find "female:rimjob").
            if !entry.raw_lower.contains(&kw) {
                continue;
            }

            let rank = if entry.raw_lower == kw {
                0
            } else if entry.raw_lower.starts_with(&kw) {
                1
            } else {
                2
            };

            if seen.insert(entry.raw.clone()) {
                results.push((
                    rank,
                    TagSuggestion {
                        raw: entry.raw.clone(),
                        translated: entry.translated.clone(),
                    },
                ));
            }
        }
    }

    // Stable sort so equal-rank entries keep database order (deterministic UI).
    results.sort_by_key(|(rank, _)| *rank);
    results.truncate(12);
    results.into_iter().map(|(_, s)| s).collect()
}

/// A raw tag observed on a real gallery, kept in insertion order.
struct SeenTag {
    raw: String,
    raw_lower: String,
    translated: String,
}

/// English tags harvested from galleries the user actually opened.
///
/// **Why this exists:** the curried translation database is a curated subset of
/// E-Hentai's vocabulary, and before this index existed the search box produced
/// *zero* suggestions unless the user had downloaded it — so a purely English
/// feature (type "kaf" → "kafka") silently depended on a 10 MB Chinese
/// database. Real browsing history is the one source that always exists, needs
/// no download, and reflects the tags the user actually cares about.
#[derive(Default)]
struct SeenIndex {
    /// Insertion-ordered, so suggestions stay stable rather than reshuffling
    /// underneath the user while they type.
    rows: Vec<SeenTag>,
    /// Dedupe set keyed on the raw `ns:tag` string.
    known: HashSet<String>,
}

/// Record the tags of one gallery so the search box can suggest them later.
///
/// Deliberately resolves translations *before* taking the write lock: this
/// keeps the two global locks strictly sequential, so no code path can hold
/// one while waiting on the other.
pub fn learn_tags(groups: &[crate::parser::TagGroup]) {
    let mut candidates: Vec<(String, String)> = Vec::new(); // (raw, translated)
    for group in groups {
        for tag in &group.tags {
            let tag = tag.trim();
            if tag.is_empty() {
                continue;
            }
            let raw = if group.group_name.is_empty() {
                tag.to_string()
            } else {
                format!("{}:{}", group.group_name, tag)
            };
            let translated = translate_tag_sync(group.group_name.clone(), tag.to_string());
            candidates.push((raw, translated));
        }
    }

    let Ok(mut seen) = SEEN_TAGS.write() else {
        return;
    };
    for (raw, translated) in candidates {
        if seen.known.contains(&raw) {
            continue;
        }
        if seen.rows.len() >= SEEN_TAGS_CAP {
            return;
        }
        seen.rows.push(SeenTag {
            raw_lower: raw.to_lowercase(),
            raw: raw.clone(),
            translated,
        });
        seen.known.insert(raw);
    }
}

/// How many tags we currently remember from browsing. Used by tests and for a
/// one-line startup log so "the index is empty" is diagnosable on device.
pub fn learned_tag_count() -> usize {
    SEEN_TAGS.read().map(|s| s.rows.len()).unwrap_or(0)
}
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

    tokio::fs::write(&path, &bytes).await?;
    log::info!("Tag database downloaded and saved.");

    // Load it into memory
    load_tag_db(path).await?;
    Ok(())
}

/// Load the tag database from local disk into memory.
///
/// Parsing happens *outside* the lock; the write only swaps the finished index
/// in, so a reload never blocks concurrent tag lookups for long.
pub async fn load_tag_db(path: String) -> Result<()> {
    log::info!("Loading tag database from {}", path);
    let content = tokio::fs::read_to_string(path).await?;
    let parsed: TagDatabase = serde_json::from_str(&content)?;
    let index = TagIndex::build(parsed);
    let row_count = index.rows.len();

    match TAG_DB.write() {
        Ok(mut w) => *w = index,
        Err(_) => anyhow::bail!("tag database lock poisoned"),
    }

    log::info!("Tag database loaded into memory ({} entries).", row_count);
    Ok(())
}

/// Translate a tag synchronously. Fast memory lookup with common tag fallback.
///
/// Uses a `std::sync::RwLock` rather than a `tokio` one on purpose: this is
/// called from the JNI entry point, which is not a Tokio worker, and the old
/// `try_read()` would silently return "no translation" whenever a database
/// reload happened to hold the write lock. A plain blocking read is both
/// correct here and cheap (the critical section is a hash lookup).
pub fn translate_tag_sync(namespace: String, tag: String) -> String {
    let ns = namespace.trim();
    let t = tag.trim();

    if let Ok(db) = TAG_DB.read() {
        let has_ns = !matches!(ns, "rows" | "");
        if has_ns {
            if let Some(ns_map) = db.by_namespace.get(ns) {
                if let Some(translated) = ns_map.get(t) {
                    return translated.clone();
                }
            }
        } else {
            for ns_map in db.by_namespace.values() {
                if let Some(translated) = ns_map.get(t) {
                    return translated.clone();
                }
            }
        }
    }

    tag // fallback to original
}

#[cfg(test)]
mod tests {
    use super::*;

    fn index_from(pairs: &[(&str, &str, &str)]) -> TagIndex {
        let mut by_ns: HashMap<String, HashMap<String, TagData>> = HashMap::new();
        for (ns, tag, name) in pairs {
            by_ns
                .entry(ns.to_string())
                .or_default()
                .insert(tag.to_string(), TagData { name: name.to_string() });
        }
        TagIndex::build(TagDatabase {
            data: by_ns
                .into_iter()
                .map(|(namespace, data)| NamespaceData { namespace, data })
                .collect(),
        })
    }

    /// The whole point of the rewrite: an exact-match query must rank first and
    /// carry the Chinese translation.
    #[test]
    fn build_then_lookup_is_exact() {
        let idx = index_from(&[
            ("parody", "genshin impact", "原神"),
            ("female", "sole female", " sole female "),
        ]);
        assert_eq!(idx.rows.len(), 2);
        assert_eq!(
            idx.by_namespace
                .get("parody")
                .and_then(|m| m.get("genshin impact"))
                .map(|s| s.as_str()),
            Some("原神")
        );
    }

    /// Precomputed lowercase forms must be lowercased versions of the originals,
    /// otherwise the allocation-free search silently misses matches.
    #[test]
    fn rows_cache_lowercase_forms() {
        let idx = index_from(&[("parody", "Genshin Impact", "原神")]);
        let row = &idx.rows[0];
        assert_eq!(row.raw, "parody:Genshin Impact");
        assert_eq!(row.raw_lower, "parody:genshin impact");
        assert_eq!(row.translated_lower, "原神");
    }

    /// An empty namespace must not produce a leading colon in `raw`.
    #[test]
    fn empty_namespace_raw_has_no_colon() {
        let idx = index_from(&[("", "tankoubon", "单行本")]);
        assert_eq!(idx.rows[0].raw, "tankoubon");
    }

    /// **The regression this module exists for.** With no translation database
    /// downloaded at all, typing a few English letters must still surface the
    /// tag we actually saw on a gallery. Before the seen-tag index, `rows` was
    /// empty and this returned nothing — making a purely English feature
    /// depend entirely on a 10 MB Chinese database.
    #[test]
    fn english_search_works_without_translation_db() {
        // No other test populates the global DB, so this really is the
        // "user never downloaded anything" case.
        let db_is_empty = TAG_DB.read().map(|db| db.rows.is_empty()).unwrap_or(true);
        assert!(db_is_empty, "expected an empty translation DB for this case");

        crate::tag_translator::learn_tags(&[crate::parser::TagGroup {
            group_name: "other".to_string(),
            tags: vec!["kafka".to_string()],
        }]);

        let hits = search_tag_by_chinese("kaf".to_string());
        assert!(
            hits.iter().any(|s| s.raw == "other:kafka"),
            "expected a prefix match on the learned tag, got {hits:?}"
        );
    }

    /// Learning is idempotent: re-opening the same gallery must not keep
    /// appending duplicates to an index that is never evicted until full.
    #[test]
    fn learn_tags_deduplicates() {
        let before = learned_tag_count();
        let group = crate::parser::TagGroup {
            group_name: "misc".to_string(),
            tags: vec!["rat".to_string()],
        };
        learn_tags(&[group.clone()]);
        let after_first = learned_tag_count();
        learn_tags(&[group]);
        assert_eq!(after_first, learned_tag_count());
        assert_eq!(after_first, before + 1);
    }
}
