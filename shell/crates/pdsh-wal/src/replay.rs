// SPDX-License-Identifier: MIT
//! D9 idempotent appended replay (`wal.md` §6.2).
//!
//! For an appended record with `append_from = A` and data length `L`, let `S` be
//! the current size of the materialized file:
//!
//! * `S == A` → append the data (the record was not applied yet);
//! * `S >= A + L` and the bytes at `[A, A + L)` equal the record data → skip
//!   (already applied);
//! * anything else, including a missing file or a directory → base mismatch
//!   (`VFS_RESYNC`, §5.1).
//!
//! The skip case makes consecutive appends replay-safe: base 6 bytes, append
//! `A=6` of 6, then `A=12` of 6 — a 6-byte file applies both, an 18-byte file
//! skips both, a 12-byte file skips the first and applies the second.
//!
//! This module decides only; it reads and writes nothing. The caller supplies
//! the file's size and the bytes at `append_from`.
//!
//! # Retained-sequence supersession
//!
//! D9's fourth verdict (`superseded`) is not a property of one record and the
//! current copy: it needs the **retained sequence after the record**. A later
//! retained complete write of the same path, a remove of the path, or a remove
//! of an ancestor covers the appended record (`wal.md` §6.2), and the earliest
//! such record is the one reported. [`appended_replay`] is deliberately still
//! the one-record three-way base check; callers decide supersession first with
//! [`retained_supersession`] and only then ask [`appended_replay`].
//!
//! The returned cover mseq is what a conservative watermark needs: a
//! `settled-superseded` record may be counted only once its cover's mseq is
//! inside the contiguous prefix (`wal.md` §6.2, §7.1). A bare verdict is not
//! enough to advance `materialized_upto_mseq`.

/// What replaying one appended record must do.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReplayDecision {
    /// `S == A`: append the data at `append_from`.
    Applied,
    /// The bytes are already present at `[A, A + L)`: do nothing.
    Skip,
    /// Base mismatch (`§5.1`): stop materializing the path and send
    /// `VFS_RESYNC`; `materialized_upto_mseq` stays below the record.
    Resync,
}

/// The pure D9 decision for one appended record.
///
/// `existing_at_offset` is the materialized file's bytes starting at
/// `append_from` (at least `data.len()` of them when the file is longer), or
/// `None` when the path is absent, is a directory, or is not a regular file —
/// all of which are a base mismatch.
pub fn appended_replay(
    current_size: u64,
    append_from: u64,
    data: &[u8],
    existing_at_offset: Option<&[u8]>,
) -> ReplayDecision {
    let Some(existing) = existing_at_offset else {
        return ReplayDecision::Resync;
    };
    if current_size == append_from {
        return ReplayDecision::Applied;
    }
    let end = match append_from.checked_add(data.len() as u64) {
        Some(end) => end,
        None => return ReplayDecision::Resync,
    };
    if current_size >= end && existing.len() >= data.len() && &existing[..data.len()] == data {
        return ReplayDecision::Skip;
    }
    ReplayDecision::Resync
}

/// The retained-record shape the supersession predicate reads.
///
/// This is the metadata of `wal.md` §6.2 only: a record's `mseq`, `op`,
/// `appended` flag and path. No body and no materialized state is needed to
/// decide whether a later retained record supersedes an appended one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RetainedRecord<'a> {
    /// The mutation's `mseq`.
    pub mseq: u64,
    /// `wal.md` §3 `op`: `1` write or `3` remove in the replay vectors.
    pub op: u8,
    /// The `mflags` bit 1 value: the write carries only a suffix.
    pub appended: bool,
    /// The absolute VFS path.
    pub path: &'a str,
}

impl<'a> RetainedRecord<'a> {
    /// One retained record.
    pub fn new(mseq: u64, op: u8, appended: bool, path: &'a str) -> RetainedRecord<'a> {
        RetainedRecord {
            mseq,
            op,
            appended,
            path,
        }
    }
}

/// The kind of later retained record that covers an appended record.
///
/// The vector's `complete-write`, `truncate` and `repair-write` labels are all
/// the one rule ("a complete write of the same path"; `wal.md` §6.2/§5.1), so
/// they project onto [`CoverKind::CompleteWrite`]. `remove` and
/// `ancestor-remove` are distinct path relations.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CoverKind {
    /// `op = 1`, `appended` clear, same path (a truncating or repair write is
    /// a complete write too).
    CompleteWrite,
    /// `op = 3`, same path.
    Remove,
    /// `op = 3` on a proper ancestor of the path.
    AncestorRemove,
}

/// The fourth D9 verdict: a later retained record covers an appended record.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Supersession {
    /// The earliest later retained covering record's `mseq`.
    pub cover_mseq: u64,
    /// What kind of record covers it.
    pub kind: CoverKind,
}

/// The D9 supersession predicate over one retained mutation sequence.
///
/// `records` is the retained sequence in ascending `mseq` order and `index`
/// addresses the appended record being decided. Returns the earliest later
/// record that covers it (`wal.md` §6.2):
///
/// * a complete write (`op = 1`, `appended` clear) of the same path;
/// * a remove (`op = 3`) of the same path;
/// * a remove of an ancestor of the path.
///
/// A complete write or remove covers every earlier record of the path, so an
/// intervening append of the same path does not stop the cover; an append that
/// comes **after** the cover is not covered by it and is a separate record.
/// Returns `None` when the record is not an appended write or no later retained
/// record covers it.
pub fn retained_supersession(records: &[RetainedRecord<'_>], index: usize) -> Option<Supersession> {
    let record = records.get(index)?;
    if record.op != 1 || !record.appended {
        return None;
    }
    for later in records.iter().skip(index + 1) {
        if later.mseq <= record.mseq {
            continue;
        }
        let kind = if later.op == 1 && !later.appended && later.path == record.path {
            Some(CoverKind::CompleteWrite)
        } else if later.op == 3 {
            if later.path == record.path {
                Some(CoverKind::Remove)
            } else if is_ancestor_path(later.path, record.path) {
                Some(CoverKind::AncestorRemove)
            } else {
                None
            }
        } else {
            None
        };
        if let Some(kind) = kind {
            return Some(Supersession {
                cover_mseq: later.mseq,
                kind,
            });
        }
    }
    None
}

/// `true` when `ancestor` is a proper path prefix of `path`.
fn is_ancestor_path(ancestor: &str, path: &str) -> bool {
    path.len() > ancestor.len()
        && path.starts_with(ancestor)
        && path.as_bytes().get(ancestor.len()) == Some(&b'/')
}
