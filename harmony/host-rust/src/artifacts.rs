// SPDX-License-Identifier: MIT
//! Native workspace artifact facts.
//!
//! A pure projection over the read-only WAL range query ([`crate::wal::Store::workspace_changes`])
//! and the Host's cached ACK. It performs no I/O, takes no lock and adds no
//! wire/WAL field: the Store owns the records and the Host owns the atomic
//! acknowledgement, so this module only shapes their results at the C boundary.

use serde_json::{json, Value};

use crate::wal::WorkspaceChange;

/// Error text is bounded like the other host JSON surfaces; a path can quote a
/// person's input, so the Store already keeps these messages path-free.
const MAX_ERROR_CHARS: usize = 400;

fn bounded(error: &str) -> String {
    error.chars().take(MAX_ERROR_CHARS).collect()
}

/// `{"fromMseq","toMseq","files":[{"path","size","mseq"}],"error"?}`.
///
/// A failed range is an error object, never a successful empty file list.
pub fn changes_json(from: u64, to: u64, result: Result<Vec<WorkspaceChange>, String>) -> Value {
    match result {
        Ok(files) => {
            let rows: Vec<Value> = files
                .iter()
                .map(|change| {
                    json!({
                        "path": change.path,
                        "size": change.size,
                        "mseq": change.mseq.to_string(),
                    })
                })
                .collect();
            json!({
                "fromMseq": from.to_string(),
                "toMseq": to.to_string(),
                "files": rows,
            })
        }
        Err(error) => json!({
            "fromMseq": from.to_string(),
            "toMseq": to.to_string(),
            "files": Vec::<Value>::new(),
            "error": bounded(&error),
        }),
    }
}

/// `{"ackedMseq":"<u64>"}` from the cached atomic ACK.
pub fn watermark_json(acked_mseq: u64) -> Value {
    json!({ "ackedMseq": acked_mseq.to_string() })
}

/// `{"ackedMseq":"<u64>","error":"..."}` when no honest watermark exists.
pub fn watermark_error_json(acked_mseq: u64, error: &str) -> Value {
    json!({
        "ackedMseq": acked_mseq.to_string(),
        "error": bounded(error),
    })
}
