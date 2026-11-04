// SPDX-License-Identifier: MIT
//! D9 idempotent appended replay (`wal.md` §6.2): the three decisions.

use pdsh_wal::{appended_replay, ReplayDecision};

/// The §6.2 example: base 6 bytes, append `A=6` of `world\n`, then `A=12` of
/// `again\n`.
const FIRST: &[u8] = b"world\n";
const SECOND: &[u8] = b"again\n";

#[test]
fn a_file_at_the_offset_applies_the_suffix() {
    assert_eq!(
        appended_replay(6, 6, FIRST, Some(b"")),
        ReplayDecision::Applied
    );
    // An empty suffix at the offset is a no-op append.
    assert_eq!(
        appended_replay(6, 6, b"", Some(b"")),
        ReplayDecision::Applied
    );
}

#[test]
fn a_file_that_already_holds_the_bytes_skips() {
    assert_eq!(
        appended_replay(12, 6, FIRST, Some(FIRST)),
        ReplayDecision::Skip
    );
    // The file may be longer than A + L; only [A, A + L) matters.
    assert_eq!(
        appended_replay(40, 6, FIRST, Some(b"world\nand more bytes")),
        ReplayDecision::Skip
    );
}

#[test]
fn a_missing_or_non_regular_path_is_a_base_mismatch() {
    assert_eq!(appended_replay(6, 6, FIRST, None), ReplayDecision::Resync);
}

#[test]
fn a_file_shorter_than_the_offset_is_a_base_mismatch() {
    assert_eq!(
        appended_replay(5, 6, FIRST, Some(b"")),
        ReplayDecision::Resync
    );
}

#[test]
fn a_partially_appended_suffix_is_a_base_mismatch() {
    // S == 10 lies strictly between A (6) and A + L (12): neither applied nor
    // provably skipped.
    assert_eq!(
        appended_replay(10, 6, FIRST, Some(b"worl")),
        ReplayDecision::Resync
    );
}

#[test]
fn different_bytes_at_the_offset_are_a_base_mismatch() {
    assert_eq!(
        appended_replay(12, 6, FIRST, Some(b"XXXXXX")),
        ReplayDecision::Resync
    );
}

#[test]
fn a_window_shorter_than_the_suffix_is_a_base_mismatch() {
    assert_eq!(
        appended_replay(12, 6, FIRST, Some(b"wor")),
        ReplayDecision::Resync
    );
}

#[test]
fn consecutive_appends_replay_through_a_6_12_and_18_byte_file() {
    // A 6-byte file: the base, so both appends apply in order.
    assert_eq!(
        appended_replay(6, 6, FIRST, Some(b"")),
        ReplayDecision::Applied
    );
    assert_eq!(
        appended_replay(12, 12, SECOND, Some(b"")),
        ReplayDecision::Applied
    );

    // An 18-byte file: both appends are already there.
    assert_eq!(
        appended_replay(18, 6, FIRST, Some(b"world\nagain\n")),
        ReplayDecision::Skip
    );
    assert_eq!(
        appended_replay(18, 12, SECOND, Some(b"again\n")),
        ReplayDecision::Skip
    );

    // A 12-byte file: the first append is present, the second still applies.
    assert_eq!(
        appended_replay(12, 6, FIRST, Some(FIRST)),
        ReplayDecision::Skip
    );
    assert_eq!(
        appended_replay(12, 12, SECOND, Some(b"")),
        ReplayDecision::Applied
    );
}

// ---------------------------------------------------------------------------
// The shared retained-sequence vectors (`spec/v1/golden/wal/replay/*.json`)
// ---------------------------------------------------------------------------
//
// The predicate is the Kotlin path's authority for D9 (the JNI entry point
// projects it), so it is driven by the same 14 vectors the Swift/Kotlin
// recovery code must consume. The test reads every file in the directory; a
// vector that disappears or a new one that is not handled fails loudly. The
// expected cover *label* is only a category (`complete-write`, `truncate` and
// `repair-write` are all the one complete-write rule), while the cover mseq is
// the conservative watermark input and must match exactly.

use std::collections::BTreeSet;
use std::fs;
use std::path::PathBuf;

use pdsh_wal::json::{self, Json};
use pdsh_wal::{retained_supersession, CoverKind, RetainedRecord};

fn golden_replay_dir() -> PathBuf {
    PathBuf::from(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../spec/v1/golden/wal/replay"
    ))
}

fn replay_vector_names() -> Vec<String> {
    let dir = golden_replay_dir();
    let mut names: Vec<String> = fs::read_dir(&dir)
        .expect("the replay vector directory exists")
        .map(|entry| {
            entry
                .expect("directory entry")
                .file_name()
                .to_string_lossy()
                .into_owned()
        })
        .filter(|name| name.ends_with(".json"))
        .collect();
    names.sort();
    names
}

fn uint(doc: &Json, name: &str) -> u64 {
    doc.get(name)
        .and_then(Json::as_u64)
        .unwrap_or_else(|| panic!("missing integer member {name}"))
}

fn boolean(doc: &Json, name: &str) -> bool {
    match doc.get(name) {
        Some(Json::Bool(value)) => *value,
        _ => panic!("missing boolean member {name}"),
    }
}

fn text<'a>(doc: &'a Json, name: &str) -> &'a str {
    doc.get(name)
        .and_then(Json::as_str)
        .unwrap_or_else(|| panic!("missing string member {name}"))
}

fn text_array<'a>(doc: &'a Json, name: &str) -> &'a [Json] {
    doc.get(name)
        .and_then(Json::as_array)
        .unwrap_or_else(|| panic!("missing array member {name}"))
}

fn label_matches(kind: CoverKind, label: &str) -> bool {
    match kind {
        CoverKind::CompleteWrite => {
            matches!(label, "complete-write" | "truncate" | "repair-write")
        }
        CoverKind::Remove => label == "remove",
        CoverKind::AncestorRemove => label == "ancestor-remove",
    }
}

#[test]
fn golden_replay_directory_holds_exactly_fourteen_vectors() {
    let names = replay_vector_names();
    assert_eq!(
        names,
        vec![
            "append-already-applied-skips.json",
            "append-ancestor-remove-settles.json",
            "append-full-write-settles.json",
            "append-only-stays-held.json",
            "append-remove-settles.json",
            "append-truncate-settles.json",
            "cover-before-append-stays-held.json",
            "cover-other-path-stays-held.json",
            "dependent-append-not-covered-stays-held.json",
            "intervening-append-covered-settles.json",
            "live-repair-write-settles.json",
            "recovery-rebuilds-from-wal.json",
            "retained-not-from-one-stays-failed.json",
            "settled-watermark-waits-for-cover.json",
        ],
        "expected exactly the 14 shared WAL replay vectors in {}",
        golden_replay_dir().display()
    );
}

#[test]
fn every_shared_replay_vector_drives_the_supersession_predicate() {
    let names = replay_vector_names();
    assert_eq!(names.len(), 14, "all 14 shared vectors must run");
    let mut seen_labels = BTreeSet::new();
    for name in &names {
        let path = golden_replay_dir().join(name);
        let contents = fs::read_to_string(&path).expect("vector is readable");
        let doc = json::parse(&contents).expect("vector is JSON");
        let input_watermark = uint(&doc, "materialized_upto_mseq");
        let records = text_array(&doc, "records");

        let parsed: Vec<(u64, u8, bool, String)> = records
            .iter()
            .map(|record| {
                (
                    uint(record, "mseq"),
                    uint(record, "op") as u8,
                    boolean(record, "appended"),
                    text(record, "path").to_string(),
                )
            })
            .collect();
        for pair in parsed.windows(2) {
            assert_eq!(
                pair[1].0,
                pair[0].0 + 1,
                "{name}: retained mseq values must be contiguous"
            );
        }
        let paths: Vec<String> = parsed.iter().map(|record| record.3.clone()).collect();
        let retained: Vec<RetainedRecord<'_>> = parsed
            .iter()
            .zip(paths.iter())
            .map(|(record, path)| RetainedRecord::new(record.0, record.1, record.2, path))
            .collect();

        // For a rebuild vector the top-level verdicts describe the rebuild after
        // the copy replay; the predicate is judging the given retained sequence
        // against the given copy, which is exactly `copy_replay`.
        let expected = doc.get("expected").expect("expected member");
        let verdict_source = expected
            .get("copy_replay")
            .and_then(|copy| copy.get("records"))
            .unwrap_or_else(|| expected.get("records").expect("expected.records"));
        let verdicts = verdict_source
            .as_array()
            .expect("expected verdicts are an array");
        assert_eq!(
            verdicts.len(),
            retained.len(),
            "{name}: one verdict per retained record"
        );

        for (index, record) in retained.iter().enumerate() {
            let verdict = verdicts[index].get("verdict").and_then(Json::as_str);
            assert_eq!(
                uint(&verdicts[index], "mseq"),
                record.mseq,
                "{name}: verdict order follows the records"
            );
            let superseded = retained_supersession(&retained, index);
            if let Some(supersession) = superseded {
                assert_eq!(
                    verdict,
                    Some("settled-superseded"),
                    "{name}: mseq {} is covered by mseq {}",
                    record.mseq,
                    supersession.cover_mseq
                );
                assert_eq!(
                    uint(&verdicts[index], "superseded_by_mseq"),
                    supersession.cover_mseq,
                    "{name}: mseq {} earliest cover",
                    record.mseq
                );
                let label = text(&verdicts[index], "cover");
                assert!(
                    label_matches(supersession.kind, label),
                    "{name}: mseq {} cover label {label} disagrees with {:?}",
                    record.mseq,
                    supersession.kind
                );
                seen_labels.insert(label.to_string());
            } else {
                assert_ne!(
                    verdict,
                    Some("settled-superseded"),
                    "{name}: mseq {} must not be settled without a retained cover",
                    record.mseq
                );
            }
            if record.mseq <= input_watermark {
                assert_eq!(
                    verdict,
                    Some("already-materialized"),
                    "{name}: mseq {} is at or below the input watermark",
                    record.mseq
                );
            }
            assert!(
                record.op == 1 || record.op == 3,
                "{name}: replay vectors admit write and remove only"
            );
        }
    }
    assert_eq!(
        seen_labels,
        [
            "ancestor-remove",
            "complete-write",
            "remove",
            "repair-write",
            "truncate"
        ]
        .iter()
        .map(|label| (*label).to_string())
        .collect::<BTreeSet<_>>(),
        "all five cover labels must be exercised by the shared vectors"
    );
}
