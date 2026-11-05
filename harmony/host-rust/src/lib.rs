// SPDX-License-Identifier: MIT
//! Native phone-dsh host for HarmonyOS.
//!
//! The exported ABI in `include/pdsh_host.h` is implemented here. Rust owns the
//! loopback HTTP/WS origin, the WAL, the materialized VFS, the journal and the
//! boot overlay. ArkTS owns provider credentials and performs the outbound model
//! request; this crate only forwards the bounded callback event and streams the
//! caller's response back to the Worker.

pub mod artifacts;
pub mod bundle;
pub mod host;
pub mod http;
pub mod math;
pub mod overlay;
pub mod wal;
pub mod ws;

pub use host::{Host, StartInfo};

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};
use std::path::Path;
use std::sync::{Arc, Mutex};

use serde_json::{json, Value};

static HOST: Mutex<Option<Arc<Host>>> = Mutex::new(None);
static LAST_STATUS: Mutex<Option<Value>> = Mutex::new(None);

fn empty_info(error: &str) -> Value {
    let info = StartInfo {
        port: 0,
        token: String::new(),
        boot_id: String::new(),
        session_epoch: 0,
        acked_mseq: 0,
        recovered_bytes: 0,
    };
    info.json(Some(error))
}

fn c_string(json_value: Value) -> *mut c_char {
    let text = serde_json::to_string(&json_value)
        .unwrap_or_else(|_| "{\"error\":\"json_encode_failed\"}".to_owned());
    match CString::new(text) {
        Ok(value) => value.into_raw(),
        Err(_) => CString::new("{\"error\":\"nul_in_json\"}")
            .expect("static JSON has no NUL")
            .into_raw(),
    }
}

/// Parses one decimal u64 argument exactly: no sign, no whitespace, no
/// leading-zero ambiguity beyond the canonical numeric value.
unsafe fn c_u64(pointer: *const c_char) -> Result<u64, String> {
    if pointer.is_null() {
        return Err("null mseq pointer".to_owned());
    }
    let text = CStr::from_ptr(pointer)
        .to_str()
        .map_err(|_| "mseq is not UTF-8".to_owned())?;
    if text.is_empty() || text.len() > 20 || !text.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err("mseq must be a decimal u64 string".to_owned());
    }
    text.parse::<u64>()
        .map_err(|_| "mseq is outside the u64 range".to_owned())
}

unsafe fn c_path(pointer: *const c_char) -> Result<String, String> {
    if pointer.is_null() {
        return Err("null path pointer".to_owned());
    }
    let text = CStr::from_ptr(pointer)
        .to_str()
        .map_err(|_| "path is not UTF-8".to_owned())?;
    if text.is_empty() {
        return Err("path is empty".to_owned());
    }
    Ok(text.to_owned())
}

/// Starts the host.
///
/// # Safety
/// `root` and `assets` must be valid NUL-terminated UTF-8 C strings. The return
/// value is owned by this library and must be released with `pdsh_host_free`.
#[no_mangle]
pub unsafe extern "C" fn pdsh_host_start(
    root: *const c_char,
    assets: *const c_char,
) -> *mut c_char {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root = c_path(root)?;
        let assets = c_path(assets)?;
        let mut slot = HOST.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if slot.is_some() {
            return Err("host is already running; call pdsh_host_stop first".to_owned());
        }
        let host = Host::start(Path::new(&root), Path::new(&assets))?;
        let info = host.info.clone();
        *slot = Some(host);
        *LAST_STATUS
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
        Ok(info.json(None))
    }));
    match result {
        Ok(Ok(value)) => c_string(value),
        Ok(Err(error)) => c_string(empty_info(&error)),
        Err(_) => c_string(empty_info("panic in pdsh_host_start")),
    }
}

/// Stops the host. Idempotent and bounded: sockets are shut down and threads
/// are detached after a short grace period rather than blocking shutdown.
#[no_mangle]
pub extern "C" fn pdsh_host_stop() {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let host = HOST
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take();
        let Some(host) = host else {
            return;
        };
        let mut status = host.health_json();
        host.stop();
        if let Value::Object(map) = &mut status {
            map.insert("running".to_owned(), Value::Bool(false));
            map.insert("stopped".to_owned(), Value::Bool(true));
        }
        *LAST_STATUS
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(status);
    }));
}

/// Returns status JSON. Never contains the per-start token.
///
/// # Safety
/// The return value is owned by this library and must be released with
/// `pdsh_host_free`.
#[no_mangle]
pub unsafe extern "C" fn pdsh_host_status() -> *mut c_char {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let host = HOST
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        if let Some(host) = host {
            return host.health_json();
        }
        LAST_STATUS
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
            .unwrap_or_else(|| json!({"running": false, "error": "not_started"}))
    }));
    c_string(result.unwrap_or_else(|_| json!({"running": false, "error": "panic_in_status"})))
}

/// Installs the ArkTS event callback. `None` clears it.
#[no_mangle]
pub extern "C" fn pdsh_host_set_callback(
    callback: Option<extern "C" fn(*const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        host::set_callback(callback, userdata);
    }));
}

/// Advances one pending HTTP response.
///
/// `kind`: 0=head, 1=chunk, 2=end, 3=error. `headers` is a NUL-terminated JSON
/// object for kind 0 and may be null. `data`/`len` carry the chunk or the error
/// text. Invalid input, unknown ids and old-generation ids are ignored: the
/// caller cannot crash or leak state through a stale NAPI callback.
///
/// # Safety
/// When `len > 0`, `data` must point to `len` readable bytes. `headers` must be
/// null or a NUL-terminated UTF-8 C string.
#[no_mangle]
pub unsafe extern "C" fn pdsh_host_respond(
    id: u64,
    kind: i32,
    status: i32,
    headers: *const c_char,
    data: *const u8,
    len: usize,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if len > 0 && data.is_null() {
            return;
        }
        let body: &[u8] = if len == 0 {
            &[]
        } else {
            std::slice::from_raw_parts(data, len)
        };
        let headers = if headers.is_null() {
            None
        } else {
            match CStr::from_ptr(headers).to_str() {
                Ok(value) => Some(value),
                Err(_) => return,
            }
        };
        let host = HOST
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        if let Some(host) = host {
            let _ = host.respond(id, kind, status, headers, body);
        }
    }));
}

/// Installs a worker bundle zip into `assets` and returns `{ok,error?}`.
///
/// # Safety
/// Both pointers must be valid NUL-terminated UTF-8 C strings. The return value
/// is owned by this library and must be released with `pdsh_host_free`.
#[no_mangle]
pub unsafe extern "C" fn pdsh_host_install_bundle(
    zip: *const c_char,
    assets: *const c_char,
) -> *mut c_char {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let zip = c_path(zip)?;
        let assets = c_path(assets)?;
        bundle::install_bundle(Path::new(&zip), Path::new(&assets))?;
        Ok::<(), String>(())
    }));
    let value = match result {
        Ok(Ok(())) => json!({"ok": true}),
        Ok(Err(error)) => json!({"ok": false, "error": error}),
        Err(_) => json!({"ok": false, "error": "panic in pdsh_host_install_bundle"}),
    };
    c_string(value)
}

/// Returns the read-only workspace changes in `(from, to]`.
///
/// Both arguments are decimal u64 strings. The result is
/// `{"fromMseq","toMseq","files":[{"path","size","mseq"}],"error"?}`; invalid or
/// out-of-watermark ranges are an error, never a successful empty list.
///
/// # Safety
/// Both pointers must be valid NUL-terminated UTF-8 C strings. The return value
/// is owned by this library and must be released with `pdsh_host_free`.
#[no_mangle]
pub unsafe extern "C" fn pdsh_host_workspace_changes(
    from_mseq: *const c_char,
    to_mseq: *const c_char,
) -> *mut c_char {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let from = c_u64(from_mseq)?;
        let to = c_u64(to_mseq)?;
        let host = HOST
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        let Some(host) = host else {
            return Err("host is not running".to_owned());
        };
        Ok::<Value, String>(host.workspace_changes_json(from, to))
    }));
    let value = match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"files": Vec::<Value>::new(), "error": error}),
        Err(_) => json!({"files": Vec::<Value>::new(), "error": "panic in pdsh_host_workspace_changes"}),
    };
    c_string(value)
}

/// Returns `{"ackedMseq":"<u64>","error"?}` from the cached atomic ACK.
///
/// It never takes the Store lock or touches the filesystem; callers must only
/// read it once Native and the Worker are ready.
///
/// # Safety
/// The return value is owned by this library and must be released with
/// `pdsh_host_free`.
#[no_mangle]
pub extern "C" fn pdsh_host_workspace_watermark() -> *mut c_char {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let host = HOST
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        match host {
            Some(host) => host.workspace_watermark(),
            None => crate::artifacts::watermark_error_json(0, "host is not running"),
        }
    }));
    c_string(result.unwrap_or_else(|_| {
        crate::artifacts::watermark_error_json(0, "panic in pdsh_host_workspace_watermark")
    }))
}

/// Frees a string returned by this library. Null is ignored.
///
/// # Safety
/// `pointer` must be a pointer previously returned by this library and not yet
/// freed.
#[no_mangle]
pub unsafe extern "C" fn pdsh_host_free(pointer: *mut c_char) {
    if !pointer.is_null() {
        drop(CString::from_raw(pointer));
    }
}

#[cfg(test)]
mod tests;
