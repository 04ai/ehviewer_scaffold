//! Blocked-tag registry and the gallery→tags index that backs list filtering.
//!
//! E-Hentai gives us two different affordances, so this feature has two halves:
//!
//! 1. **Query side — complete filtering.** E-Hentai's search syntax supports
//!    exclusion with a leading `-` ("Subtraction Sign (-) Exclusion. When placed
//!    before a term, prevents search results from including that term"), so when
//!    the user *has* typed a query the blocked tags are appended as `-ns:tag`
//!    and the server does the work. The site rejects a query made of exclusions
//!    only ("Searches with only exclusions are not permitted"), and caps a
//!    search at 8 terms, which is why that side lives in the Kotlin layer where
//!    the user's query is known, and why straying outside a query is impossible.
//!
//! 2. **Listing side — best effort.** Gallery *listing* HTML carries no tags at
//!    all (`GalleryItem` has no tag field because there is nothing to parse), so
//!    a list can only be filtered for galleries whose tags we have already seen.
//!    Tags are recorded whenever a detail page is parsed, so a blocked gallery
//!    stops appearing in listings from the second sighting onward: the first
//!    sighting is what teaches us it is blocked.

use lazy_static::lazy_static;
use std::collections::{HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::RwLock;

lazy_static! {
    /// Lowercased blocked entries that carry a namespace (`female:yaoi`).
    /// A blocked tag is matched against the *whole* `ns:tag` in that case.
    static ref BLOCKED_FULL: RwLock<HashSet<String>> = RwLock::new(HashSet::new());

    /// Lowercased blocked entries without a namespace (`yaoi`). These match the
    /// tag part of any namespace, which is what a user means when they type a
    /// bare tag by hand in the settings screen.
    static ref BLOCKED_BARE: RwLock<HashSet<String>> = RwLock::new(HashSet::new());

    /// gid -> lowercased `ns:tag` entries.
    static ref TAG_INDEX: RwLock<TagIndex> = RwLock::new(TagIndex::default());

    static ref INDEX_FILE: RwLock<Option<PathBuf>> = RwLock::new(None);
    static ref LAST_SAVE_SECS: AtomicU64 = AtomicU64::new(0);
    static ref SAVE_PENDING: AtomicBool = AtomicBool::new(false);
}

/// Bound on remembered galleries. An entry is a few dozen short strings, so this
/// is low single-digit MB at worst. Oldest-first eviction.
const TAG_INDEX_CAP: usize = 3000;

/// Minimum seconds between two index writes. Recording happens once per opened
/// gallery, so without this a browsing session would rewrite the file constantly.
const INDEX_SAVE_THROTTLE_SECS: u64 = 5;

#[derive(Default)]
struct TagIndex {
    map: HashMap<String, Vec<String>>,
    order: VecDeque<String>,
}

/// Extract the bare gid from whatever the detail path was addressed with.
///
/// `fetch_gallery_detail` receives `"<gid>/<token>"` — that is how the detail URL
/// and its cache key are built — but a *listing* entry carries only the bare gid.
/// Indexing under `gid/token` while looking up by `gid` meant every lookup missed,
/// so list-side filtering silently matched nothing at all. Normalising here is
/// what makes the two sides agree.
fn normalize_gid(gid: &str) -> &str {
    match gid.split_once('/') {
        Some((head, _)) => head,
        None => gid,
    }
}

/// Point the index at its on-disk file and load whatever is already there.
pub fn init_index(cache_dir: &str) {
    let file = PathBuf::from(cache_dir).join("blocked_tag_index.json");
    if let Ok(mut f) = INDEX_FILE.write() {
        *f = Some(file.clone());
    }
    load(&file);
}

fn load(file: &PathBuf) {
    let Ok(raw) = std::fs::read_to_string(file) else {
        return;
    };
    // Shape: [[gid, ["female:yaoi", ...]], ...] — a Vec rather than a map so the
    // insertion order (used for eviction) survives a round trip.
    let Ok(rows) = serde_json::from_str::<Vec<(String, Vec<String>)>>(&raw) else {
        log::warn!("blocked_tag_index.json is unreadable; starting with an empty index");
        return;
    };
    let Ok(mut idx) = TAG_INDEX.write() else {
        return;
    };
    for (gid, tags) in rows {
        // Entries written before the key was normalised carry `gid/token`; strip
        // it on the way in so that data is recovered rather than discarded.
        let gid = normalize_gid(gid.trim()).to_string();
        if gid.is_empty() {
            continue;
        }
        if idx.map.len() >= TAG_INDEX_CAP && !idx.map.contains_key(&gid) {
            break;
        }
        if idx.map.insert(gid.clone(), tags).is_none() {
            idx.order.push_back(gid);
        }
    }
    log::info!("Loaded tag index for {} galleries", idx.map.len());
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Persist the index, throttled. Cheap enough to call after every record: the
/// file is a few hundred KB at most and the throttle keeps writes to one per
/// [`INDEX_SAVE_THROTTLE_SECS`]. Synchronous on purpose — it is called from the
/// detail path (already blocking on `RUNTIME.block_on`) and from this small file
/// a blocking write is far cheaper than a thread hop.
fn save_throttled() {
    let now = now_secs();
    if now.saturating_sub(LAST_SAVE_SECS.load(Ordering::Relaxed)) < INDEX_SAVE_THROTTLE_SECS {
        SAVE_PENDING.store(true, Ordering::Relaxed);
        return;
    }
    LAST_SAVE_SECS.store(now, Ordering::Relaxed);
    SAVE_PENDING.store(false, Ordering::Relaxed);
    save_now();
}

/// Unconditional write. Called when the process is about to lose the index
/// (nothing to hook on Android, so `record_tags` also flushes a pending write
/// once the throttle window has passed).
fn save_now() {
    let Ok(file) = INDEX_FILE.read() else { return };
    let Some(file) = file.as_ref() else { return };
    let rows: Vec<(String, Vec<String>)> = {
        let Ok(idx) = TAG_INDEX.read() else { return };
        idx.order
            .iter()
            .filter_map(|gid| idx.map.get(gid).map(|t| (gid.clone(), t.clone())))
            .collect()
    };
    match serde_json::to_vec(&rows) {
        Ok(bytes) => {
            if let Err(e) = std::fs::write(file, bytes) {
                log::warn!("could not persist tag index: {}", e);
            }
        }
        Err(e) => log::warn!("could not serialise tag index: {}", e),
    }
}

/// Replace the whole block list. Called from Kotlin, where SharedPreferences
/// remains the authoritative copy — this is in-memory state replayed at cold
/// start, exactly like the site URL and the download directory.
pub fn set_blocked_tags(tags: Vec<String>) {
    let mut full = HashSet::new();
    let mut bare = HashSet::new();
    for raw in tags {
        let t = raw.trim().to_lowercase();
        if t.is_empty() {
            continue;
        }
        if t.contains(':') {
            full.insert(t);
        } else {
            bare.insert(t);
        }
    }
    let count = full.len() + bare.len();
    if let Ok(mut g) = BLOCKED_FULL.write() {
        *g = full;
    }
    if let Ok(mut g) = BLOCKED_BARE.write() {
        *g = bare;
    }
    log::info!("Blocked tags set: {} entries", count);
}

/// Cheapest possible early-out for the hot listing path.
pub fn has_blocked() -> bool {
    if let Ok(g) = BLOCKED_BARE.read() {
        if !g.is_empty() {
            return true;
        }
    }
    if let Ok(g) = BLOCKED_FULL.read() {
        return !g.is_empty();
    }
    false
}

/// Match one `namespace:tag` entry against the two block-list views.
///
/// Pure so it can be tested without touching the process-global registry.
fn entry_matches(entry: &str, full: &HashSet<String>, bare: &HashSet<String>) -> bool {
    if full.contains(entry) {
        return true;
    }
    if !bare.is_empty() {
        // `entry` is `namespace:tag`; compare against the tag part. E-Hentai
        // tags themselves never contain a colon.
        if let Some(tag_part) = entry.rsplit(':').next() {
            if bare.contains(tag_part) {
                return true;
            }
        }
    }
    false
}

fn entry_is_blocked(entry: &str) -> bool {
    let Ok(full) = BLOCKED_FULL.read() else {
        return false;
    };
    let Ok(bare) = BLOCKED_BARE.read() else {
        return false;
    };
    entry_matches(entry, &full, &bare)
}

/// The block list formatted as E-Hentai exclusion terms (`-ns:tag`), capped at
/// `budget` terms.
///
/// Used to try an exclusion-only listing query, which would let the *server* filter
/// every listed gallery instead of only the ones this device has already opened.
/// E-Hentai's documentation says "Searches with only exclusions are not permitted",
/// but that could never be verified from here (the dev machine cannot reach the
/// site), so the caller probes it once and remembers the answer.
///
/// Multi-word tags are quoted, which is what the site expects.
pub fn exclusion_terms(budget: usize) -> Vec<String> {
    fn term(t: &str) -> String {
        if t.contains(' ') {
            format!("-\"{}\"", t)
        } else {
            format!("-{}", t)
        }
    }
    let mut out: Vec<String> = Vec::with_capacity(budget);
    if let Ok(full) = BLOCKED_FULL.read() {
        for t in full.iter() {
            if out.len() >= budget {
                break;
            }
            out.push(term(t));
        }
    }
    if let Ok(bare) = BLOCKED_BARE.read() {
        for t in bare.iter() {
            if out.len() >= budget {
                break;
            }
            out.push(term(t));
        }
    }
    out
}

/// Whether any of this gallery's remembered tags is blocked.
pub fn is_gallery_blocked(gid: &str) -> bool {
    if !has_blocked() {
        return false;
    }
    let Ok(idx) = TAG_INDEX.read() else {
        return false;
    };
    match idx.map.get(gid) {
        Some(tags) => tags.iter().any(|t| entry_is_blocked(t)),
        None => false, // never opened this gallery: nothing to judge by
    }
}

/// Drop blocked galleries from a freshly parsed listing.
///
/// Deliberately applied *after* the listing cache: the cache must keep the raw
/// server response, otherwise un-blocking a tag would leave the gallery missing
/// until the TTL expired.
pub fn filter_blocked(items: Vec<crate::parser::GalleryItem>) -> Vec<crate::parser::GalleryItem> {
    if !has_blocked() {
        return items;
    }
    let before = items.len();
    let kept: Vec<_> = items
        .into_iter()
        .filter(|it| !is_gallery_blocked(&it.gid))
        .collect();
    let removed = before - kept.len();
    if removed > 0 {
        log::info!(
            "Blocked-tag filter hid {} of {} listed galleries",
            removed,
            before
        );
    }
    kept
}

/// Remember which tags a gallery carries. Same hook points as
/// `tag_translator::learn_tags`, including the cache-hit path: re-opening a
/// gallery is exactly when its tags are worth having on file.
pub fn record_tags(gid: &str, groups: &[crate::parser::TagGroup]) {
    let gid = normalize_gid(gid.trim());
    if gid.is_empty() {
        return;
    }
    let mut tags: Vec<String> = Vec::new();
    for group in groups {
        let ns = group.group_name.trim().to_lowercase();
        for tag in &group.tags {
            let t = tag.trim().to_lowercase();
            if t.is_empty() {
                continue;
            }
            tags.push(if ns.is_empty() {
                t
            } else {
                format!("{}:{}", ns, t)
            });
        }
    }
    if tags.is_empty() {
        return;
    }

    let tag_count = tags.len();

    let Ok(mut idx) = TAG_INDEX.write() else {
        return;
    };
    let is_new = idx.map.insert(gid.to_string(), tags).is_none();
    if is_new {
        idx.order.push_back(gid.to_string());
        while idx.order.len() > TAG_INDEX_CAP {
            if let Some(oldest) = idx.order.pop_front() {
                idx.map.remove(&oldest);
            }
        }
    }
    drop(idx);

    // Deliberately at info level and once per opened gallery (not per tag): this
    // is the only observable proof that a gallery has entered the index, which is
    // what blocked-tag filtering depends on.
    log::info!(
        "Tag index: {} tags for gallery {} ({})",
        tag_count,
        gid,
        if is_new { "new" } else { "updated" }
    );

    save_throttled();
}

/// Flush a throttled write. Exposed so a caller that knows the session is ending
/// (e.g. an explicit user action) can force the index to disk.
pub fn flush_index() {
    if SAVE_PENDING.swap(false, Ordering::Relaxed) {
        LAST_SAVE_SECS.store(now_secs(), Ordering::Relaxed);
        save_now();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gid_is_normalised_from_detail_path() {
        assert_eq!(normalize_gid("3923500/236da23bab"), "3923500");
        assert_eq!(normalize_gid("3923500"), "3923500");
        assert_eq!(normalize_gid(""), "");
    }

    #[test]
    fn bare_entry_matches_any_namespace() {
        let full = HashSet::new();
        let bare: HashSet<String> = ["furry".to_string()].into_iter().collect();
        assert!(entry_matches("male:furry", &full, &bare));
        assert!(entry_matches("female:furry", &full, &bare));
        assert!(entry_matches("furry", &full, &bare));
        assert!(!entry_matches("male:yaoi", &full, &bare));
    }

    #[test]
    fn namespaced_entry_does_not_leak_across_namespaces() {
        let full: HashSet<String> = ["female:yaoi".to_string()].into_iter().collect();
        let bare = HashSet::new();
        assert!(entry_matches("female:yaoi", &full, &bare));
        assert!(!entry_matches("male:yaoi", &full, &bare));
    }

    /// Regression guard for the bug found on device: `fetch_gallery_detail` is
    /// addressed as `gid/token`, while listing entries carry the bare `gid`.
    /// Indexing under one and looking up with the other made list-side filtering
    /// silently match nothing.
    ///
    /// This is the only test in this module that touches the process-global
    /// registry, so it restores it afterwards and cannot race its siblings.
    #[test]
    fn index_key_agrees_with_listing_gid() {
        let groups = vec![crate::parser::TagGroup {
            group_name: "male".to_string(),
            tags: vec!["furry".to_string(), "dog".to_string()],
        }];

        set_blocked_tags(vec!["male:furry".to_string()]);
        record_tags("3923500/236da23bab", &groups);

        assert!(
            is_gallery_blocked("3923500"),
            "a gallery recorded via gid/token must be found by its bare gid"
        );
        assert!(!is_gallery_blocked("9999999"), "unknown gid must pass");

        let kept = crate::api::filter_blocked_ids(vec!["3923500".into(), "9999999".into()]);
        assert_eq!(kept, vec!["9999999".to_string()]);

        set_blocked_tags(vec![]);
    }
}
