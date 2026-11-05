// SPDX-License-Identifier: MIT
//! Durable host state: the phone-dsh WAL, the materialized VFS and the journal.
//!
//! The on-disk WAL format is the shared `pdsh-wal` format (40-byte segment
//! header, `len ‖ crc32c ‖ mseq ‖ body` records and CRC32C). Segments are never
//! compacted here, so a boot replays the complete retained mutation sequence
//! from mseq 1 and opens a new epoch afterwards. Only the newest segment of an
//! epoch directory may have a torn tail; it is truncated to the last complete
//! record. Every other CRC/order failure fails loudly.

use std::collections::{BTreeMap, HashMap};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

use pdsh_wal::segment::{MAX_EPOCH, MAX_SEGMENT};
use pdsh_wal::{
    scan_segment, Checksum, MutationBody, Record, SegmentHeader, SegmentName, TailVerdict,
    HEADER_BYTES,
};
use pdsh_wire::{crc32c, VfsMutation, VfsOp};

/// A segment rolls before a mutation would push it past 8 MiB (`wal.md` §1).
const SEGMENT_ROLL_BYTES: u64 = 8 * 1024 * 1024;
/// Refuse to read an individual segment larger than this into memory.
const MAX_SEGMENT_READ_BYTES: u64 = 512 * 1024 * 1024;
/// Journal framing: one gzip-free length+CRC record, capped by the wire frame.
const JOURNAL_MAGIC: &[u8; 8] = b"PDSHJ1\0\0";
const MAX_JOURNAL_PAYLOAD: usize = 16 * 1024 * 1024;
/// Upper bound on one read-only workspace-change query, so a bad caller cannot
/// force an unbounded segment walk. This is a query bound, not a WAL limit.
const MAX_CHANGE_RANGE: u64 = 1_000_000;

/// CRC32C injected into the shared `pdsh-wal` codec.
pub struct Crc32c;

impl Checksum for Crc32c {
    fn crc32c(&self, bytes: &[u8]) -> u32 {
        crc32c(bytes)
    }
}

/// Where one mutation record lives in the retained WAL.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct RecordLoc {
    epoch: u32,
    segment: u32,
    offset: u64,
    len: usize,
}

/// One current workspace file written by a WAL mutation in a measured range.
///
/// `path` is the absolute VFS path from the retained record; `size` and `mseq`
/// are the current materialized file's size and the highest write in the range.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WorkspaceChange {
    pub path: String,
    pub size: u64,
    pub mseq: u64,
}

/// Outcome of accepting one VFS mutation.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CommitOutcome {
    /// A new record was appended and fsynced; the materialized copy applied it
    /// or is holding it for a `VFS_RESYNC` (`wal.md` §5.1).
    Appended,
    /// The same mseq was already retained with an identical payload.
    Duplicate,
}

/// One path whose appended record cannot be based on the materialized copy.
///
/// `mseq` is the first retained record held for the path. The WAL keeps that
/// record: a held append is never discarded or rewritten (`wal.md` §5.1).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PendingResync {
    pub path: String,
    pub mseq: u64,
}

/// What one record did to the materialized copy.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum MaterializeOutcome {
    /// Applied (including a D9 skip of already-present bytes).
    Applied,
    /// The base did not match: hold the record for `VFS_RESYNC` (§5.1/§6.2).
    Held,
}

/// The `materialized.staging/` / `materialized.replaced/` names from
/// `wal.md` §1 and §7.2. They are workspace-private and never part of the VFS.
const MATERIALIZED_STAGING: &str = "materialized.staging";
const MATERIALIZED_REPLACED: &str = "materialized.replaced";

struct SegmentWriter {
    name: SegmentName,
    file: File,
    bytes: u64,
}

struct JournalFile {
    path: PathBuf,
    file: File,
    last_jseq: u64,
}

/// The single serialized writer of VFS and journal state.
pub struct Store {
    root: PathBuf,
    wal_dir: PathBuf,
    materialized: PathBuf,
    journal_dir: PathBuf,
    current: SegmentWriter,
    boot_id: [u8; 16],
    /// Effective tail (`wal.md` §7.2, `channel.md` §4.1 `acked_mseq`): the
    /// highest mutation mseq accepted at level 1, including a record whose
    /// materialization is held for `VFS_RESYNC`.
    acked_mseq: u64,
    /// Strictly contiguous watermark over the materialized copy (`wal.md`
    /// §5.1/§7). It stays below the first record that is neither applied nor
    /// settled.
    materialized_upto_mseq: u64,
    /// Held mutation mseq -> path. A held record is retained in the WAL and
    /// settled by a later complete write/remove (D9) or by the repair write.
    held: BTreeMap<u64, String>,
    recovered_bytes: u64,
    truncated_tail_bytes: u64,
    index: HashMap<u64, RecordLoc>,
    journal: HashMap<String, JournalFile>,
    segment_count: usize,
    failed: Option<String>,
}

impl std::fmt::Debug for Store {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Store")
            .field("root", &self.root)
            .field("acked_mseq", &self.acked_mseq)
            .field("materialized_upto_mseq", &self.materialized_upto_mseq)
            .field("held", &self.held)
            .field("recovered_bytes", &self.recovered_bytes)
            .field("truncated_tail_bytes", &self.truncated_tail_bytes)
            .field("segments", &self.segment_count)
            .field("journals", &self.journal.len())
            .field("failed", &self.failed)
            .finish()
    }
}

impl Store {
    /// Opens (or creates) the workspace, recovers the WAL and starts a new
    /// epoch with a fresh segment.
    ///
    /// Recovery first parses and validates every retained record. When the
    /// retained mutations are the complete mseq-1 sequence (the normal case
    /// here, because this host never compacts), the materialized copy is
    /// rebuilt from the WAL into a fresh `materialized.staging/` tree and
    /// published by rename. Historical node kinds are therefore never applied
    /// over the final tree (`wal.md` §6.1/§7.2).
    pub fn open(root: &Path, boot_id: [u8; 16]) -> Result<Store, String> {
        let root = root.to_path_buf();
        fs::create_dir_all(&root)
            .map_err(|error| format!("cannot create {}: {error}", root.display()))?;
        recover_materialized_layout(&root)?;
        let wal_dir = canonical_plain_dir(&root.join("wal"))?;
        let materialized = canonical_plain_dir(&root.join("materialized"))?;
        let journal_dir = canonical_plain_dir(&root.join("journal"))?;

        let mut names = list_segments(&wal_dir)?;
        names.sort();
        let mut recovered_bytes = 0u64;
        let mut truncated_tail_bytes = 0u64;
        let mut index = HashMap::new();
        let mut mutations: Vec<MutationBody> = Vec::new();
        let mut last_epoch = 0u32;
        let mut segment_count = 0usize;

        // Phase 1: verify every record before touching the materialized copy so
        // a corrupt later segment leaves the previous tree intact.
        for (name_index, name) in names.iter().enumerate() {
            last_epoch = last_epoch.max(name.epoch);
            segment_count += 1;
            let path = wal_dir.join(name.base_name());
            let bytes = read_capped(&path, MAX_SEGMENT_READ_BYTES)?;
            let scan = scan_segment(&bytes, &Crc32c);
            if scan.header.is_none() {
                return Err(format!(
                    "WAL segment {} has an unreadable header: {}",
                    name.base_name(),
                    scan.header_error
                        .map(|error| error.to_string())
                        .unwrap_or_else(|| "unknown error".to_owned())
                ));
            }
            if scan.chain_error.is_some() || scan.is_corrupt() {
                return Err(format!(
                    "WAL segment {} is corrupt; refusing to recover",
                    name.base_name()
                ));
            }
            let newest = name_index + 1 == names.len();
            if let TailVerdict::Truncate { dropped, .. } = scan.verdict {
                if !newest {
                    return Err(format!(
                        "WAL segment {} has a torn tail but is not the newest segment",
                        name.base_name()
                    ));
                }
                let keep = scan.valid_len;
                if keep < HEADER_BYTES {
                    return Err(format!(
                        "WAL segment {} cannot keep a complete header",
                        name.base_name()
                    ));
                }
                let file = OpenOptions::new()
                    .write(true)
                    .open(&path)
                    .map_err(|error| {
                        format!("cannot open {} for truncation: {error}", path.display())
                    })?;
                file.set_len(keep as u64)
                    .and_then(|_| file.sync_all())
                    .map_err(|error| format!("cannot truncate {}: {error}", path.display()))?;
                sync_dir(&wal_dir)?;
                truncated_tail_bytes = truncated_tail_bytes.saturating_add(dropped as u64);
            }

            // The shared scan does not expose per-record offsets; walk the
            // verified prefix again to index exact records for duplicate checks.
            let mut offset = HEADER_BYTES;
            while offset < scan.valid_len {
                let parsed = Record::parse(&bytes[offset..], &Crc32c).map_err(|error| {
                    format!(
                        "WAL segment {} record at {offset} cannot be re-read: {error}",
                        name.base_name()
                    )
                })?;
                if let Record::Mutation(mutation) = parsed.record {
                    let expected = (mutations.len() as u64)
                        .checked_add(1)
                        .ok_or_else(|| "WAL mseq space exhausted".to_owned())?;
                    if mutation.mseq != expected {
                        return Err(format!(
                            "WAL mseq gap: expected {expected}, found {} in {}",
                            mutation.mseq,
                            name.base_name()
                        ));
                    }
                    recovered_bytes =
                        recovered_bytes.saturating_add(mutation.body.data.len() as u64);
                    index.insert(
                        mutation.mseq,
                        RecordLoc {
                            epoch: name.epoch,
                            segment: name.segment,
                            offset: offset as u64,
                            len: parsed.byte_len,
                        },
                    );
                    mutations.push(mutation.body);
                }
                offset += parsed.byte_len;
            }
            if offset != scan.valid_len {
                return Err(format!(
                    "WAL segment {} verified length does not align with its records",
                    name.base_name()
                ));
            }
        }

        let accepted_mseq = mutations.len() as u64;
        let next_epoch = last_epoch
            .checked_add(1)
            .filter(|epoch| *epoch <= MAX_EPOCH)
            .ok_or_else(|| "WAL epoch space exhausted".to_owned())?;

        // Phase 2: rebuild the copy from the WAL. A retained sequence is always
        // complete from mseq 1 in this host (no compaction), so the WAL alone is
        // the rebuild input; the previous final tree is never replayed into.
        let mut materialized = materialized;
        let mut materialized_upto_mseq = 0u64;
        let mut held = BTreeMap::new();
        if !mutations.is_empty() {
            let rebuilt = rebuild_materialized(&materialized, &mutations)?;
            materialized = rebuilt.0;
            materialized_upto_mseq = rebuilt.1;
            held = rebuilt.2;
        }

        let first_mseq = accepted_mseq
            .checked_add(1)
            .ok_or_else(|| "WAL mseq space exhausted".to_owned())?;
        let name = SegmentName::new(next_epoch, 0).map_err(|error| error.to_string())?;
        let current = create_segment(&wal_dir, name, first_mseq, boot_id)?;
        segment_count += 1;

        let journal = recover_journals(&journal_dir)?;
        Ok(Store {
            root,
            wal_dir,
            materialized,
            journal_dir,
            current,
            boot_id,
            acked_mseq: accepted_mseq,
            materialized_upto_mseq,
            held,
            recovered_bytes,
            truncated_tail_bytes,
            index,
            journal,
            segment_count,
            failed: None,
        })
    }

    /// Highest level-1 acknowledged mseq: the effective WAL tail, including an
    /// appended record whose materialization is held for `VFS_RESYNC`.
    pub fn acked_mseq(&self) -> u64 {
        self.acked_mseq
    }

    /// Highest strictly contiguous mseq applied to the materialized copy
    /// (`wal.md` §5.1/§7). This can lag [`Store::acked_mseq`] while a base
    /// mismatch is held.
    pub fn materialized_upto_mseq(&self) -> u64 {
        self.materialized_upto_mseq
    }

    /// One outstanding repair per path, in first-held mseq order.
    pub fn pending_resyncs(&self) -> Vec<PendingResync> {
        let mut seen: HashMap<&str, ()> = HashMap::new();
        let mut pending = Vec::new();
        for (mseq, path) in &self.held {
            if seen.insert(path.as_str(), ()).is_none() {
                pending.push(PendingResync {
                    path: path.clone(),
                    mseq: *mseq,
                });
            }
        }
        pending
    }

    /// Handles a worker `ERROR VFS_CONFLICT` (`channel.md` §10).
    ///
    /// A conflict for a path whose held record is still uncovered is terminal:
    /// the repair can never arrive, so the host enters `Failed`. A conflict for
    /// a path already settled by a later complete write/remove (D9 superseded)
    /// is ignored and does not enter `Failed`.
    pub fn resolve_vfs_conflict(&mut self, message: &str) -> Result<(), String> {
        let mut matched: Option<PendingResync> = None;
        for request in self.pending_resyncs() {
            if !message.contains(&request.path) {
                continue;
            }
            if matched
                .as_ref()
                .map(|current| request.path.len() > current.path.len())
                .unwrap_or(true)
            {
                matched = Some(request);
            }
        }
        match matched {
            Some(request) => {
                let failure = format!(
                    "VFS_CONFLICT for held appended path {} at mseq {}",
                    request.path, request.mseq
                );
                self.failed = Some(failure.clone());
                Err(failure)
            }
            None => Ok(()),
        }
    }

    pub fn recovered_bytes(&self) -> u64 {
        self.recovered_bytes
    }

    pub fn truncated_tail_bytes(&self) -> u64 {
        self.truncated_tail_bytes
    }

    pub fn segment_count(&self) -> usize {
        self.segment_count
    }

    pub fn journal_count(&self) -> usize {
        self.journal.len()
    }

    pub fn failed(&self) -> Option<&str> {
        self.failed.as_deref()
    }

    pub fn next_mseq(&self) -> u64 {
        self.acked_mseq.saturating_add(1)
    }

    pub fn materialized_root(&self) -> &Path {
        &self.materialized
    }

    /// Read-only view of the real `/dsh/workspace/` writes in `(from, to]`.
    ///
    /// Reads the retained WAL index and each exact record through the shared
    /// codec (`Record::parse`); it never reads file content, appends a record or
    /// mutates the WAL. A record whose current materialized target is missing,
    /// removed or not a plain regular file is omitted rather than reported as an
    /// openable artifact. The range must lie inside the strictly contiguous
    /// materialized watermark, not merely the accepted WAL tail: a held append
    /// is retained but has no materialized fact yet, so this query is an error
    /// rather than a falsely empty success.
    pub fn workspace_changes(&self, from: u64, to: u64) -> Result<Vec<WorkspaceChange>, String> {
        if from > to {
            return Err(format!("mseq range is inverted: {from} > {to}"));
        }
        if to > self.materialized_upto_mseq {
            return Err(format!(
                "mseq range ends past the materialized watermark: {to} > {}",
                self.materialized_upto_mseq
            ));
        }
        if from > self.materialized_upto_mseq {
            return Err(format!(
                "mseq range starts past the materialized watermark: {from} > {}",
                self.materialized_upto_mseq
            ));
        }
        if to.saturating_sub(from) > MAX_CHANGE_RANGE {
            return Err(format!(
                "mseq range spans more than {MAX_CHANGE_RANGE} records"
            ));
        }
        let mut changes: Vec<WorkspaceChange> = Vec::new();
        let mut by_path: HashMap<String, usize> = HashMap::new();
        for mseq in from.saturating_add(1)..=to {
            let loc = self
                .index
                .get(&mseq)
                .copied()
                .ok_or_else(|| format!("mseq {mseq} has no retained WAL record"))?;
            let record = self.read_record_at(loc)?;
            let Record::Mutation(mutation) = record else {
                continue;
            };
            if mutation.body.op != pdsh_wal::OP_WRITE {
                continue;
            }
            let Ok(("workspace", relative)) = resolve_path(&mutation.body.path) else {
                continue;
            };
            let Some(size) = current_workspace_file_size(&self.materialized, &relative)? else {
                continue;
            };
            let change = WorkspaceChange {
                path: mutation.body.path.clone(),
                size,
                mseq,
            };
            if let Some(&existing) = by_path.get(&mutation.body.path) {
                changes[existing] = change;
            } else {
                by_path.insert(mutation.body.path.clone(), changes.len());
                changes.push(change);
            }
        }
        changes.sort_by(|left, right| {
            left.mseq
                .cmp(&right.mseq)
                .then_with(|| left.path.cmp(&right.path))
        });
        Ok(changes)
    }

    /// Reads one retained record at the exact indexed location through the
    /// shared codec. The index and the file must agree on the byte length.
    fn read_record_at(&self, loc: RecordLoc) -> Result<Record, String> {
        let name = SegmentName {
            epoch: loc.epoch,
            segment: loc.segment,
        };
        let path = self.wal_dir.join(name.base_name());
        let mut file = File::open(&path)
            .map_err(|error| format!("cannot open {}: {error}", path.display()))?;
        let mut bytes = vec![0u8; loc.len];
        file.seek(SeekFrom::Start(loc.offset))
            .and_then(|_| file.read_exact(&mut bytes))
            .map_err(|error| format!("cannot read {} at {}: {error}", path.display(), loc.offset))?;
        let parsed = Record::parse(&bytes, &Crc32c)
            .map_err(|error| format!("cannot parse indexed record: {error:?}"))?;
        if parsed.byte_len != loc.len {
            return Err(format!(
                "indexed record length {} does not match the WAL record {}",
                loc.len, parsed.byte_len
            ));
        }
        Ok(parsed.record)
    }

    /// Appends, fsyncs and materializes one VFS mutation.
    ///
    /// A duplicate mseq is accepted only when the retained record has the same
    /// payload; then it is a replay and nothing is appended. A forward mseq is
    /// rejected. Level 1 is the strongest ack this host can honestly give: the
    /// segment is fsynced before the caller sends `VFS_ACK level 1`, but no
    /// measured level-2/power-loss guarantee is claimed.
    pub fn commit_mutation(&mut self, mutation: &VfsMutation) -> Result<CommitOutcome, String> {
        if let Some(failure) = &self.failed {
            return Err(format!("host state failed: {failure}"));
        }
        validate_mutation(mutation)?;

        if let Some(loc) = self.index.get(&mutation.mseq).copied() {
            if !self.retained_record_matches(mutation, loc)? {
                return Err(format!(
                    "duplicate mseq {} has a different payload",
                    mutation.mseq
                ));
            }
            // The record is already durable and already part of the applied (or
            // held) materialized state. Re-applying its bytes would undo every
            // later record of the path; an identical duplicate is a no-op.
            return Ok(CommitOutcome::Duplicate);
        }

        let expected = self
            .acked_mseq
            .checked_add(1)
            .ok_or_else(|| "WAL mseq space exhausted".to_owned())?;
        if mutation.mseq != expected {
            return Err(format!(
                "mseq {} is not the expected {}",
                mutation.mseq, expected
            ));
        }

        let body = body_of(mutation);
        let record = Record::mutation(mutation.mseq, body.clone())
            .map_err(|error| format!("cannot encode WAL record: {error}"))?;
        let bytes = record
            .emit(&Crc32c)
            .map_err(|error| format!("cannot emit WAL record: {error}"))?;
        if self.current.bytes > HEADER_BYTES as u64
            && self.current.bytes + bytes.len() as u64 > SEGMENT_ROLL_BYTES
        {
            self.roll(mutation.mseq)?;
        }
        let offset = self.current.bytes;
        self.current
            .file
            .write_all(&bytes)
            .and_then(|_| self.current.file.sync_all())
            .map_err(|error| {
                self.failed = Some("WAL append failed".to_owned());
                format!("cannot append WAL record: {error}")
            })?;
        self.current.bytes += bytes.len() as u64;
        let loc = RecordLoc {
            epoch: self.current.name.epoch,
            segment: self.current.name.segment,
            offset,
            len: bytes.len(),
        };
        self.index.insert(mutation.mseq, loc);
        // The record is durable now, so it is level-1 accepted even when the
        // materialized copy cannot apply it yet (`channel.md` §3.2, §4.1).
        self.acked_mseq = mutation.mseq;

        if let Err(error) = self.materialize_live(mutation.mseq, &body) {
            self.failed = Some("materialization failed".to_owned());
            return Err(error);
        }
        Ok(CommitOutcome::Appended)
    }

    /// Applies one live record to the materialized copy.
    ///
    /// A missing appended base is a `VFS_RESYNC` hold, not a materialization
    /// I/O failure: the record stays in the WAL and the contiguous watermark
    /// remains below it.
    fn materialize_live(&mut self, mseq: u64, body: &MutationBody) -> Result<(), String> {
        match apply_mutation(&self.materialized, body)? {
            MaterializeOutcome::Applied => {
                settle_held(&mut self.held, body);
                advance_contiguous(&mut self.materialized_upto_mseq, self.acked_mseq, &self.held);
            }
            MaterializeOutcome::Held => {
                self.held.entry(mseq).or_insert_with(|| body.path.clone());
            }
        }
        Ok(())
    }

    /// Appends one journal record and fsyncs it before the caller ACKs it.
    ///
    /// A jseq at or below the retained value is the documented duplicate and is
    /// ignored; a forward gap is `BAD_PAYLOAD` at the channel layer.
    pub fn append_journal(
        &mut self,
        session_id: &str,
        jseq: u64,
        record: &[u8],
    ) -> Result<u64, String> {
        validate_identifier(session_id)?;
        if jseq == 0 {
            return Err("JOURNAL jseq starts at 1".to_owned());
        }
        if record.is_empty() {
            return Err("JOURNAL record must not be empty".to_owned());
        }
        if let Some(existing) = self.journal.get_mut(session_id) {
            if jseq <= existing.last_jseq {
                return Ok(existing.last_jseq);
            }
            let expected = existing
                .last_jseq
                .checked_add(1)
                .ok_or_else(|| "journal jseq space exhausted".to_owned())?;
            if jseq != expected {
                return Err(format!(
                    "journal jseq {jseq} is not consecutive after {}",
                    existing.last_jseq
                ));
            }
            append_journal_record(existing, session_id, jseq, record)?;
            return Ok(jseq);
        }

        // First record for this session must be jseq 1.
        if jseq != 1 {
            return Err(format!("journal jseq {jseq} starts a session before 1"));
        }
        let path = self
            .journal_dir
            .join(format!("{}.jnl", hex(session_id.as_bytes())));
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&path)
            .map_err(|error| format!("cannot create journal file: {error}"))?;
        file.write_all(JOURNAL_MAGIC)
            .and_then(|_| file.sync_all())
            .map_err(|error| format!("cannot write journal header: {error}"))?;
        sync_dir(&self.journal_dir)?;
        let mut state = JournalFile {
            path,
            file,
            last_jseq: 0,
        };
        append_journal_record(&mut state, session_id, jseq, record)?;
        self.journal.insert(session_id.to_owned(), state);
        Ok(jseq)
    }

    fn retained_record_matches(
        &self,
        mutation: &VfsMutation,
        loc: RecordLoc,
    ) -> Result<bool, String> {
        let name = SegmentName::new(loc.epoch, loc.segment)
            .map_err(|error| format!("retained segment name is invalid: {error}"))?;
        let path = self.wal_dir.join(name.base_name());
        let mut file =
            File::open(&path).map_err(|error| format!("cannot open retained segment: {error}"))?;
        let mut frame = vec![0u8; loc.len];
        file.seek(SeekFrom::Start(loc.offset))
            .and_then(|_| file.read_exact(&mut frame))
            .map_err(|error| format!("cannot read retained record: {error}"))?;
        let parsed = Record::parse(&frame, &Crc32c)
            .map_err(|error| format!("retained record is corrupt: {error}"))?;
        let Record::Mutation(retained) = parsed.record else {
            return Ok(false);
        };
        Ok(retained.body == body_of(mutation))
    }

    fn roll(&mut self, next_mseq: u64) -> Result<(), String> {
        let next_segment = self
            .current
            .name
            .segment
            .checked_add(1)
            .filter(|segment| *segment <= MAX_SEGMENT)
            .ok_or_else(|| "WAL segment number exhausted".to_owned())?;
        let name = SegmentName::new(self.current.name.epoch, next_segment)
            .map_err(|error| error.to_string())?;
        let writer = create_segment(&self.wal_dir, name, next_mseq, self.boot_id)?;
        self.current = writer;
        self.segment_count += 1;
        Ok(())
    }

    /// Packs the materialized `home/` and `workspace/` trees as one gzip member.
    pub fn pack_overlay(&self) -> Result<Vec<u8>, String> {
        crate::overlay::pack(&self.materialized)
    }
}

fn body_of(mutation: &VfsMutation) -> MutationBody {
    MutationBody::new(
        mutation.op.byte(),
        mutation.mode,
        mutation.mflags,
        mutation.append_from,
        &mutation.path,
        mutation.data.clone(),
    )
}

fn validate_mutation(mutation: &VfsMutation) -> Result<(), String> {
    let body = body_of(mutation);
    body.validate().map_err(|error| error.to_string())?;
    let (scope, relative) = resolve_path(&mutation.path)?;
    let _ = (scope, relative);
    if mutation.op == VfsOp::Write {
        if mutation.data.len() > pdsh_wire::MAX_MUTATION_DATA_LEN {
            return Err("mutation data exceeds 15 MiB".to_owned());
        }
        if !mutation.appended() && mutation.append_from != 0 {
            return Err("complete write has a nonzero append_from".to_owned());
        }
    }
    Ok(())
}

/// D4 path grammar and the materialized scope split.
///
/// Returns (`home`/`workspace`, relative components joined by `/`).
fn resolve_path(path: &str) -> Result<(&'static str, String), String> {
    if !path.starts_with('/') {
        return Err("mutation path must be absolute".to_owned());
    }
    if path.len() > u16::MAX as usize {
        return Err("mutation path exceeds the u16 wire bound".to_owned());
    }
    let rest = path
        .strip_prefix("/dsh/")
        .ok_or_else(|| "mutation path is outside /dsh".to_owned())?;
    let (scope, relative) = if let Some(relative) = rest.strip_prefix("home/") {
        ("home", relative)
    } else if let Some(relative) = rest.strip_prefix("workspace/") {
        ("workspace", relative)
    } else {
        return Err("mutation path is outside /dsh/home or /dsh/workspace".to_owned());
    };
    if relative.is_empty() {
        return Err("mutation path names the VFS root".to_owned());
    }
    for component in relative.split('/') {
        if component.is_empty() {
            return Err("mutation path has an empty component".to_owned());
        }
        if component == "." || component == ".." {
            return Err("mutation path has a dot component".to_owned());
        }
        if component.len() > 255 {
            return Err("mutation path component exceeds 255 bytes".to_owned());
        }
        if component.contains('\\') || component.contains('\0') {
            return Err("mutation path has an unsafe byte".to_owned());
        }
        if component.chars().any(|ch| ch.is_control()) {
            return Err("mutation path has a control character".to_owned());
        }
    }
    Ok((scope, relative.to_owned()))
}

/// The current size of a materialized workspace file, or `None` when it is not
/// a plain regular file. Every ancestor is checked for symlinks so a file that
/// escaped the workspace scope after materialization is not reported.
fn current_workspace_file_size(
    materialized: &Path,
    relative: &str,
) -> Result<Option<u64>, String> {
    let mut current = materialized.join("workspace");
    match fs::symlink_metadata(&current) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(format!(
                    "materialized workspace scope {} is not a plain directory",
                    current.display()
                ));
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("cannot stat {}: {error}", current.display())),
    }
    let components: Vec<&str> = relative.split('/').collect();
    for (index, component) in components.iter().enumerate() {
        current.push(component);
        match fs::symlink_metadata(&current) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() {
                    return Err(format!(
                        "refusing to report symlinked artifact {}",
                        current.display()
                    ));
                }
                if index + 1 == components.len() {
                    return Ok(if metadata.is_file() {
                        Some(metadata.len())
                    } else {
                        None
                    });
                }
                if !metadata.is_dir() {
                    return Ok(None);
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(format!("cannot stat {}: {error}", current.display())),
        }
    }
    Ok(None)
}

/// Applies one materialized mutation. Complete writes are temp+rename; appended
/// writes use the shared D9 decision. A base mismatch is a hold for
/// `VFS_RESYNC`; a real I/O failure is an error.
fn apply_mutation(materialized: &Path, body: &MutationBody) -> Result<MaterializeOutcome, String> {
    let (scope, relative) = resolve_path(&body.path)?;
    let base = materialized.join(scope);
    ensure_plain_scope(&base)?;
    let components: Vec<&str> = relative.split('/').collect();
    ensure_ancestors(
        &base,
        &components,
        body.op == pdsh_wal::OP_WRITE && !body.is_appended(),
    )?;
    let target = base.join(&relative);

    match body.op {
        pdsh_wal::OP_WRITE => {
            if body.is_appended() {
                append_file(&target, body.append_from, &body.data)
            } else {
                write_complete(&target, &body.data, body.mode)?;
                Ok(MaterializeOutcome::Applied)
            }
        }
        pdsh_wal::OP_MKDIR => {
            create_dir_checked(&base, &components, body.mode)?;
            Ok(MaterializeOutcome::Applied)
        }
        pdsh_wal::OP_REMOVE => {
            remove_checked(&target)?;
            Ok(MaterializeOutcome::Applied)
        }
        pdsh_wal::OP_CHMOD => {
            chmod_checked(&target, body.mode)?;
            Ok(MaterializeOutcome::Applied)
        }
        other => Err(format!("unknown mutation op {other}")),
    }
}

fn canonical_plain_dir(path: &Path) -> Result<PathBuf, String> {
    fs::create_dir_all(path)
        .map_err(|error| format!("cannot create {}: {error}", path.display()))?;
    let metadata = fs::symlink_metadata(path)
        .map_err(|error| format!("cannot stat {}: {error}", path.display()))?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(format!(
            "{} is not a plain directory; refusing symlinked host state",
            path.display()
        ));
    }
    fs::canonicalize(path)
        .map_err(|error| format!("cannot canonicalize {}: {error}", path.display()))
}

fn ensure_plain_scope(path: &Path) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() {
                return Err(format!(
                    "refusing symlinked materialized scope {}",
                    path.display()
                ));
            }
            if !metadata.is_dir() {
                return Err(format!(
                    "materialized scope {} is not a directory",
                    path.display()
                ));
            }
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir(path)
                .map_err(|error| format!("cannot create {}: {error}", path.display()))?;
            set_mode(path, 0o755)
        }
        Err(error) => Err(format!("cannot stat {}: {error}", path.display())),
    }
}

fn ensure_ancestors(base: &Path, components: &[&str], create_missing: bool) -> Result<(), String> {
    if components.is_empty() {
        return Err("empty materialized path".to_owned());
    }
    let mut current = base.to_path_buf();
    for component in &components[..components.len() - 1] {
        current.push(component);
        match fs::symlink_metadata(&current) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() {
                    return Err(format!("refusing symlink ancestor {}", current.display()));
                }
                if !metadata.is_dir() {
                    return Err(format!(
                        "materialized ancestor {} is not a directory",
                        current.display()
                    ));
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                if !create_missing { return Ok(()); }
                fs::create_dir(&current)
                    .map_err(|error| format!("cannot create {}: {error}", current.display()))?;
                set_mode(&current, 0o755)?;
                sync_dir(current.parent().ok_or("materialized ancestor has no parent")?)?;
            }
            Err(error) => {
                return Err(format!("cannot stat {}: {error}", current.display()));
            }
        }
    }
    Ok(())
}

fn create_dir_checked(base: &Path, components: &[&str], mode: u32) -> Result<(), String> {
    let mut current = base.to_path_buf();
    for (index, component) in components.iter().enumerate() {
        current.push(component);
        match fs::symlink_metadata(&current) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() {
                    return Err(format!("refusing symlink {}", current.display()));
                }
                if !metadata.is_dir() {
                    return Err(format!("{} is not a directory", current.display()));
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(&current)
                    .map_err(|error| format!("cannot create {}: {error}", current.display()))?;
                let permissions = if mode == 0 { 0o755 } else { mode & 0o7777 };
                set_mode(&current, permissions)?;
            }
            Err(error) => {
                return Err(format!("cannot stat {}: {error}", current.display()));
            }
        }
        if index + 1 == components.len() {
            let permissions = if mode == 0 { 0o755 } else { mode & 0o7777 };
            set_mode(&current, permissions)?;
        }
    }
    Ok(())
}

fn write_complete(target: &Path, data: &[u8], mode: u32) -> Result<(), String> {
    let parent = target
        .parent()
        .ok_or_else(|| "materialized target has no parent".to_owned())?;
    if let Ok(metadata) = fs::symlink_metadata(target) {
        if metadata.file_type().is_symlink() {
            return Err(format!("refusing to replace symlink {}", target.display()));
        }
        if metadata.is_dir() {
            return Err(format!(
                "refusing to write over directory {}",
                target.display()
            ));
        }
    }
    let temporary = parent.join(format!(
        ".pdsh-tmp-{}-{}",
        std::process::id(),
        next_temp_id()
    ));
    let result = (|| -> Result<(), String> {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&temporary)
            .map_err(|error| format!("cannot create {}: {error}", temporary.display()))?;
        file.write_all(data)
            .and_then(|_| file.sync_all())
            .map_err(|error| format!("cannot write {}: {error}", temporary.display()))?;
        let permissions = if mode == 0 { 0o644 } else { mode & 0o7777 };
        set_mode(&temporary, permissions)?;
        fs::rename(&temporary, target)
            .map_err(|error| format!("cannot rename {}: {error}", target.display()))?;
        sync_dir(parent)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn append_file(
    target: &Path,
    append_from: u64,
    data: &[u8],
) -> Result<MaterializeOutcome, String> {
    let metadata = match fs::symlink_metadata(target) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(MaterializeOutcome::Held);
        }
        Err(error) => {
            return Err(format!("cannot stat appended write target: {error}"));
        }
    };
    if metadata.file_type().is_symlink() {
        return Err(format!(
            "refusing symlinked appended write target {}",
            target.display()
        ));
    }
    if !metadata.is_file() {
        // `wal.md` §6.2: anything else, including a directory, is a base
        // mismatch. The worker's repair or a later cover settles it.
        return Ok(MaterializeOutcome::Held);
    }
    let size = metadata.len();
    let existing = if size == append_from {
        Some(Vec::new())
    } else if size > append_from {
        let mut file = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(target)
            .map_err(|error| format!("cannot read {}: {error}", target.display()))?;
        file.seek(SeekFrom::Start(append_from))
            .map_err(|error| format!("cannot seek {}: {error}", target.display()))?;
        let mut buffer = vec![0u8; data.len()];
        match file.read_exact(&mut buffer) {
            Ok(()) => Some(buffer),
            Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => None,
            Err(error) => {
                return Err(format!("cannot read {}: {error}", target.display()));
            }
        }
    } else {
        None
    };
    match pdsh_wal::appended_replay(size, append_from, data, existing.as_deref()) {
        pdsh_wal::ReplayDecision::Applied => {
            let mut file = OpenOptions::new()
                .append(true)
                .custom_flags(libc::O_NOFOLLOW | libc::O_APPEND)
                .open(target)
                .map_err(|error| format!("cannot open {}: {error}", target.display()))?;
            file.write_all(data)
                .and_then(|_| file.flush())
                .map_err(|error| format!("cannot append {}: {error}", target.display()))?;
            Ok(MaterializeOutcome::Applied)
        }
        pdsh_wal::ReplayDecision::Skip => Ok(MaterializeOutcome::Applied),
        pdsh_wal::ReplayDecision::Resync => Ok(MaterializeOutcome::Held),
    }
}

/// The walking state for a cold rebuild: the same D9 hold/settlement rules the
/// live path uses, over a fresh empty base instead of the final tree.
#[derive(Default)]
struct ApplyState {
    accepted: u64,
    watermark: u64,
    held: BTreeMap<u64, String>,
}

impl ApplyState {
    fn process(&mut self, base: &Path, mseq: u64, body: &MutationBody) -> Result<(), String> {
        match apply_mutation(base, body)? {
            MaterializeOutcome::Applied => {
                settle_held(&mut self.held, body);
                advance_contiguous(&mut self.watermark, self.accepted, &self.held);
            }
            MaterializeOutcome::Held => {
                self.held.entry(mseq).or_insert_with(|| body.path.clone());
            }
        }
        Ok(())
    }
}

/// True when `body` is a later complete write/remove that covers `held_path`
/// (`wal.md` §6.2): a complete write of the same path, a remove of the path, or
/// a remove of a proper ancestor.
fn covered_by(held_path: &str, body: &MutationBody) -> bool {
    match body.op {
        pdsh_wal::OP_WRITE if !body.is_appended() => held_path == body.path,
        pdsh_wal::OP_REMOVE => {
            held_path == body.path || is_descendant_path(held_path, &body.path)
        }
        _ => false,
    }
}

fn is_descendant_path(path: &str, ancestor: &str) -> bool {
    path.len() > ancestor.len()
        && path.starts_with(ancestor)
        && path.as_bytes().get(ancestor.len()) == Some(&b'/')
}

fn settle_held(held: &mut BTreeMap<u64, String>, body: &MutationBody) {
    held.retain(|_, held_path| !covered_by(held_path, body));
}

fn advance_contiguous(
    watermark: &mut u64,
    accepted: u64,
    held: &BTreeMap<u64, String>,
) {
    while *watermark < accepted {
        let next = *watermark + 1;
        if held.contains_key(&next) {
            break;
        }
        *watermark = next;
    }
}

/// Rebuilds the whole materialized copy from the complete mseq-1 WAL sequence
/// into a fresh `materialized.staging/` tree, validates it, then publishes it.
///
/// The previous final tree is never the input, so a historical file/mkdir kind
/// can never be replayed over the final kind. A failure while building leaves
/// `materialized/` untouched.
fn rebuild_materialized(
    materialized: &Path,
    mutations: &[MutationBody],
) -> Result<(PathBuf, u64, BTreeMap<u64, String>), String> {
    let parent = materialized
        .parent()
        .ok_or_else(|| "materialized copy has no parent".to_owned())?;
    let staging = parent.join(MATERIALIZED_STAGING);
    remove_derived_dir(&staging)?;
    fs::create_dir(&staging)
        .map_err(|error| format!("cannot create {}: {error}", staging.display()))?;
    set_mode(&staging, 0o755)?;

    let mut state = ApplyState::default();
    let built = (|| -> Result<(), String> {
        for (index, body) in mutations.iter().enumerate() {
            let mseq = index as u64 + 1;
            state.accepted = mseq;
            state.process(&staging, mseq, body)?;
        }
        sync_tree(&staging)
    })();
    if let Err(error) = built {
        let _ = remove_derived_dir(&staging);
        return Err(error);
    }

    publish_staging(parent, &staging, materialized)?;
    let published = fs::canonicalize(materialized)
        .map_err(|error| format!("cannot canonicalize published copy: {error}"))?;
    Ok((published, state.watermark, state.held))
}

/// Atomic-ish directory publish: keep the previous tree as a crash backup until
/// the staged tree is renamed into place, then drop the backup. A failure at
/// any step restores the previous tree.
fn publish_staging(parent: &Path, staging: &Path, materialized: &Path) -> Result<(), String> {
    let backup = parent.join(MATERIALIZED_REPLACED);
    remove_derived_dir(&backup)?;
    fs::rename(materialized, &backup)
        .map_err(|error| format!("cannot back up {}: {error}", materialized.display()))?;
    sync_dir(parent)?;
    if let Err(error) = fs::rename(staging, materialized) {
        let _ = fs::rename(&backup, materialized);
        let _ = sync_dir(parent);
        return Err(format!(
            "cannot publish staged copy {}: {error}",
            staging.display()
        ));
    }
    sync_dir(parent)?;
    // The new tree is durable; the previous tree is only a crash backup now.
    let _ = remove_derived_dir(&backup);
    let _ = sync_dir(parent);
    Ok(())
}

/// Restores a crash-left backup (or drops a stale one) and removes a stale
/// staging tree before recovery rebuilds it.
fn recover_materialized_layout(root: &Path) -> Result<(), String> {
    let materialized = root.join("materialized");
    let backup = root.join(MATERIALIZED_REPLACED);
    let staging = root.join(MATERIALIZED_STAGING);
    if path_exists_plain(&backup)? {
        if path_exists_plain(&materialized)? {
            remove_derived_dir(&backup)?;
        } else {
            fs::rename(&backup, &materialized).map_err(|error| {
                format!(
                    "cannot restore {} from {}: {error}",
                    materialized.display(),
                    backup.display()
                )
            })?;
        }
        sync_dir(root)?;
    }
    if path_exists_plain(&staging)? {
        remove_derived_dir(&staging)?;
    }
    Ok(())
}

fn path_exists_plain(path: &Path) -> Result<bool, String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() {
                return Err(format!(
                    "refusing symlinked recovery path {}",
                    path.display()
                ));
            }
            Ok(true)
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(format!("cannot stat {}: {error}", path.display())),
    }
}

fn remove_derived_dir(path: &Path) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(format!(
                    "{} is not a plain directory; refusing to replace it",
                    path.display()
                ));
            }
            remove_tree(path)
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(format!("cannot stat {}: {error}", path.display())),
    }
}

/// fsync every file and every directory in a freshly built tree, deepest first.
fn sync_tree(dir: &Path) -> Result<(), String> {
    for entry in
        fs::read_dir(dir).map_err(|error| format!("cannot list {}: {error}", dir.display()))?
    {
        let entry =
            entry.map_err(|error| format!("cannot list {}: {error}", dir.display()))?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)
            .map_err(|error| format!("cannot stat {}: {error}", path.display()))?;
        if metadata.file_type().is_symlink() {
            return Err(format!(
                "refusing symlinked staged entry {}",
                path.display()
            ));
        }
        if metadata.is_dir() {
            sync_tree(&path)?;
        } else if metadata.is_file() {
            File::open(&path)
                .and_then(|file| file.sync_all())
                .map_err(|error| format!("cannot fsync {}: {error}", path.display()))?;
        }
    }
    sync_dir(dir)
}

fn chmod_checked(target: &Path, mode: u32) -> Result<(), String> {
    let metadata = fs::symlink_metadata(target)
        .map_err(|error| format!("chmod target is missing: {error}"))?;
    if metadata.file_type().is_symlink() {
        return Err(format!("refusing to chmod symlink {}", target.display()));
    }
    set_mode(target, mode & 0o7777)
}

fn remove_checked(target: &Path) -> Result<(), String> {
    let metadata = match fs::symlink_metadata(target) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(format!("cannot stat {}: {error}", target.display())),
    };
    if metadata.is_dir() && !metadata.file_type().is_symlink() {
        remove_tree(target)
    } else {
        fs::remove_file(target)
            .map_err(|error| format!("cannot remove {}: {error}", target.display()))
    }
}

fn remove_tree(target: &Path) -> Result<(), String> {
    let entries = fs::read_dir(target)
        .map_err(|error| format!("cannot list {}: {error}", target.display()))?;
    for entry in entries {
        let entry = entry.map_err(|error| format!("cannot list {}: {error}", target.display()))?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)
            .map_err(|error| format!("cannot stat {}: {error}", path.display()))?;
        if metadata.is_dir() && !metadata.file_type().is_symlink() {
            remove_tree(&path)?;
        } else {
            fs::remove_file(&path)
                .map_err(|error| format!("cannot remove {}: {error}", path.display()))?;
        }
    }
    fs::remove_dir(target)
        .map_err(|error| format!("cannot remove directory {}: {error}", target.display()))
}

fn set_mode(path: &Path, mode: u32) -> Result<(), String> {
    fs::set_permissions(path, fs::Permissions::from_mode(mode & 0o7777))
        .map_err(|error| format!("cannot chmod {}: {error}", path.display()))
}

fn create_segment(
    wal_dir: &Path,
    name: SegmentName,
    first_mseq: u64,
    boot_id: [u8; 16],
) -> Result<SegmentWriter, String> {
    let path = wal_dir.join(name.base_name());
    let header = SegmentHeader::new(first_mseq, boot_id, &Crc32c)
        .map_err(|error| format!("cannot build WAL segment header: {error}"))?;
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(&path)
        .map_err(|error| format!("cannot create {}: {error}", path.display()))?;
    file.write_all(&header.emit())
        .and_then(|_| file.sync_all())
        .map_err(|error| format!("cannot write {}: {error}", path.display()))?;
    sync_dir(wal_dir)?;
    Ok(SegmentWriter {
        name,
        file,
        bytes: HEADER_BYTES as u64,
    })
}

fn append_journal_record(
    state: &mut JournalFile,
    session_id: &str,
    jseq: u64,
    record: &[u8],
) -> Result<(), String> {
    let session = session_id.as_bytes();
    if session.len() > u16::MAX as usize {
        return Err("journal session id exceeds the u16 bound".to_owned());
    }
    let payload_len = 8 + 2 + session.len() + record.len();
    if payload_len > MAX_JOURNAL_PAYLOAD {
        return Err("journal payload exceeds 16 MiB".to_owned());
    }
    let mut payload = Vec::with_capacity(payload_len);
    payload.extend_from_slice(&jseq.to_be_bytes());
    payload.extend_from_slice(&(session.len() as u16).to_be_bytes());
    payload.extend_from_slice(session);
    payload.extend_from_slice(record);
    let mut frame = Vec::with_capacity(8 + payload.len());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    frame.extend_from_slice(&crc32c(&payload).to_be_bytes());
    frame.extend_from_slice(&payload);
    state
        .file
        .write_all(&frame)
        .and_then(|_| state.file.sync_all())
        .map_err(|error| format!("cannot append journal {}: {error}", state.path.display()))?;
    state.last_jseq = jseq;
    Ok(())
}

fn recover_journals(dir: &Path) -> Result<HashMap<String, JournalFile>, String> {
    let mut sessions = HashMap::new();
    for entry in
        fs::read_dir(dir).map_err(|error| format!("cannot list journal directory: {error}"))?
    {
        let entry = entry.map_err(|error| format!("cannot list journal directory: {error}"))?;
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        let Some(stem) = name.strip_suffix(".jnl") else {
            continue;
        };
        let session_bytes =
            unhex(stem).ok_or_else(|| format!("journal file {name} has a non-hex identifier"))?;
        let session_id = String::from_utf8(session_bytes)
            .map_err(|_| format!("journal file {name} names a non-UTF-8 session"))?;
        validate_identifier(&session_id)?;
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&path)
            .map_err(|error| format!("cannot open journal {}: {error}", path.display()))?;
        let length = file
            .metadata()
            .map_err(|error| format!("cannot stat journal {}: {error}", path.display()))?
            .len();
        let mut offset;
        let mut last_jseq = 0u64;
        let trailer: [u8; 8] = *JOURNAL_MAGIC;
        if length < 8 {
            file.set_len(0)
                .and_then(|_| file.sync_all())
                .map_err(|error| format!("cannot truncate journal: {error}"))?;
            offset = 0;
        } else {
            let mut header = [0u8; 8];
            file.read_exact(&mut header)
                .map_err(|error| format!("cannot read journal header: {error}"))?;
            if header != trailer {
                return Err(format!("journal {} has a bad header", path.display()));
            }
            offset = 8;
            loop {
                if offset == length {
                    break;
                }
                if length - offset < 8 {
                    break;
                }
                let mut prefix = [0u8; 8];
                file.read_exact(&mut prefix)
                    .map_err(|error| format!("cannot read journal frame: {error}"))?;
                let payload_len =
                    u32::from_be_bytes([prefix[0], prefix[1], prefix[2], prefix[3]]) as usize;
                if !(11..=MAX_JOURNAL_PAYLOAD).contains(&payload_len) {
                    return Err(format!(
                        "journal {} has an impossible record length {payload_len}",
                        path.display()
                    ));
                }
                if length - offset - 8 < payload_len as u64 {
                    // A record cut short at the end is a torn append and may be
                    // dropped. Any earlier failure is corruption.
                    break;
                }
                let stored = u32::from_be_bytes([prefix[4], prefix[5], prefix[6], prefix[7]]);
                let mut payload = vec![0u8; payload_len];
                file.read_exact(&mut payload)
                    .map_err(|error| format!("cannot read journal payload: {error}"))?;
                if crc32c(&payload) != stored {
                    return Err(format!(
                        "journal {} has a CRC mismatch at offset {}",
                        path.display(),
                        offset
                    ));
                }
                let jseq = u64::from_be_bytes(
                    payload[0..8]
                        .try_into()
                        .map_err(|_| "journal payload is short".to_owned())?,
                );
                let session_len = u16::from_be_bytes([payload[8], payload[9]]) as usize;
                if session_len + 10 > payload.len() {
                    return Err(format!(
                        "journal {} record names an overlong session id",
                        path.display()
                    ));
                }
                let record_session =
                    std::str::from_utf8(&payload[10..10 + session_len]).map_err(|_| {
                        format!("journal {} record session is not UTF-8", path.display())
                    })?;
                if record_session != session_id {
                    return Err(format!(
                        "journal {} record names a different session",
                        path.display()
                    ));
                }
                if payload.len() == 10 + session_len {
                    return Err(format!("journal {} record is empty", path.display()));
                }
                let expected = last_jseq
                    .checked_add(1)
                    .ok_or_else(|| "journal jseq space exhausted".to_owned())?;
                if jseq != expected {
                    return Err(format!(
                        "journal {} jseq {jseq} is not consecutive after {last_jseq}",
                        path.display()
                    ));
                }
                last_jseq = jseq;
                offset += 8 + payload_len as u64;
            }
        }
        let keep = offset;
        if length != keep {
            file.set_len(keep)
                .and_then(|_| file.sync_all())
                .map_err(|error| format!("cannot truncate torn journal: {error}"))?;
            sync_dir(dir)?;
        }
        if keep == 0 {
            file.seek(SeekFrom::Start(0))
                .and_then(|_| file.write_all(JOURNAL_MAGIC))
                .and_then(|_| file.sync_all())
                .map_err(|error| format!("cannot rewrite journal header: {error}"))?;
            sync_dir(dir)?;
        }
        file.seek(SeekFrom::End(0))
            .map_err(|error| format!("cannot seek journal: {error}"))?;
        sessions.insert(
            session_id,
            JournalFile {
                path,
                file,
                last_jseq,
            },
        );
    }
    Ok(sessions)
}

fn list_segments(wal_dir: &Path) -> Result<Vec<SegmentName>, String> {
    let mut names = Vec::new();
    for entry in
        fs::read_dir(wal_dir).map_err(|error| format!("cannot list WAL directory: {error}"))?
    {
        let entry = entry.map_err(|error| format!("cannot list WAL directory: {error}"))?;
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        if let Ok(segment) = SegmentName::parse(name) {
            names.push(segment);
        }
    }
    Ok(names)
}

fn read_capped(path: &Path, cap: u64) -> Result<Vec<u8>, String> {
    let metadata =
        fs::metadata(path).map_err(|error| format!("cannot stat {}: {error}", path.display()))?;
    if metadata.len() > cap {
        return Err(format!(
            "{} is {} bytes, above the {} byte recovery cap",
            path.display(),
            metadata.len(),
            cap
        ));
    }
    fs::read(path).map_err(|error| format!("cannot read {}: {error}", path.display()))
}

/// fsync a directory so a create/rename/unlink is durable.
pub fn sync_dir(dir: &Path) -> Result<(), String> {
    let file = File::open(dir)
        .map_err(|error| format!("cannot open directory {}: {error}", dir.display()))?;
    file.sync_all()
        .map_err(|error| format!("cannot fsync directory {}: {error}", dir.display()))
}

/// D6 identifiers: `^[A-Za-z0-9._-]{1,64}$`.
pub fn validate_identifier(value: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > 64 {
        return Err("identifier must be 1..64 characters".to_owned());
    }
    if !value
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    {
        return Err("identifier has a character outside [A-Za-z0-9._-]".to_owned());
    }
    Ok(())
}

fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

fn unhex(text: &str) -> Option<Vec<u8>> {
    if text.len() % 2 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(text.len() / 2);
    let bytes = text.as_bytes();
    for pair in bytes.chunks(2) {
        let high = hex_value(pair[0])?;
        let low = hex_value(pair[1])?;
        out.push((high << 4) | low);
    }
    Some(out)
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

fn next_temp_id() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static ID: AtomicU64 = AtomicU64::new(0);
    ID.fetch_add(1, Ordering::Relaxed)
}
