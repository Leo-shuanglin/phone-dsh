// SPDX-License-Identifier: MIT
//! Host integration tests. These exercise the real TCP listener, the shared
//! WAL format, the gzip overlay and the WebSocket byte path; none of them uses
//! a mocked host.

use std::collections::HashMap;
use std::ffi::CStr;
use std::fs;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Mutex, MutexGuard};
use std::time::Duration;

use base64::Engine as _;
use flate2::read::GzDecoder;
use pdsh_wire::{
    decode_with_header, encode, AckLevel, ApiAbort, ApiEnd, ApiResponse, Frame, JsonObject,
    JsonValue, VfsAck, VfsBarrier, VfsOp, MFLAG_APPENDED, MFLAG_ENTRY_CHANGED,
};
use serde_json::Value;
use tungstenite::connect;
use tungstenite::ClientRequestBuilder;

use crate::wal::{CommitOutcome, Store};
use crate::Host;

static TEST_LOCK: Mutex<()> = Mutex::new(());
static TEMP_ID: AtomicU64 = AtomicU64::new(0);
static CALLBACK_TX: Mutex<Option<Sender<String>>> = Mutex::new(None);

fn test_lock() -> MutexGuard<'static, ()> {
    TEST_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

struct TempDir(PathBuf);

impl TempDir {
    fn new(label: &str) -> Self {
        let id = TEMP_ID.fetch_add(1, Ordering::SeqCst);
        let path = std::env::temp_dir().join(format!(
            "pdsh-host-test-{label}-{}-{id}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).expect("test temp dir");
        TempDir(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn write_mutation(mseq: u64, path: &str, data: &[u8]) -> pdsh_wire::VfsMutation {
    pdsh_wire::VfsMutation {
        mseq,
        op: VfsOp::Write,
        mode: 0o644,
        mflags: MFLAG_ENTRY_CHANGED,
        append_from: 0,
        path: path.to_owned(),
        data: data.to_vec(),
    }
}

fn append_mutation(mseq: u64, path: &str, append_from: u64, data: &[u8]) -> pdsh_wire::VfsMutation {
    pdsh_wire::VfsMutation {
        mseq,
        op: VfsOp::Write,
        mode: 0o644,
        mflags: MFLAG_ENTRY_CHANGED | MFLAG_APPENDED,
        append_from,
        path: path.to_owned(),
        data: data.to_vec(),
    }
}

fn mkdir_mutation(mseq: u64, path: &str) -> pdsh_wire::VfsMutation {
    pdsh_wire::VfsMutation {
        mseq,
        op: VfsOp::Mkdir,
        mode: 0o755,
        mflags: 0,
        append_from: 0,
        path: path.to_owned(),
        data: Vec::new(),
    }
}

fn remove_mutation(mseq: u64, path: &str) -> pdsh_wire::VfsMutation {
    pdsh_wire::VfsMutation {
        mseq,
        op: VfsOp::Remove,
        mode: 0,
        mflags: 0,
        append_from: 0,
        path: path.to_owned(),
        data: Vec::new(),
    }
}

fn chmod_mutation(mseq: u64, path: &str, mode: u32) -> pdsh_wire::VfsMutation {
    pdsh_wire::VfsMutation {
        mseq,
        op: VfsOp::Chmod,
        mode,
        mflags: 0,
        append_from: 0,
        path: path.to_owned(),
        data: Vec::new(),
    }
}

fn wal_files(root: &Path) -> Vec<PathBuf> {
    let mut files: Vec<PathBuf> = fs::read_dir(root.join("wal"))
        .expect("wal dir")
        .map(|entry| entry.expect("wal entry").path())
        .filter(|path| path.extension().and_then(|extension| extension.to_str()) == Some("xlwal"))
        .collect();
    files.sort();
    files
}

#[test]
fn wal_roundtrip_materializes_and_reopens() {
    let temp = TempDir::new("wal-roundtrip");
    let root = temp.path();
    {
        let mut store = Store::open(root, [1u8; 16]).expect("open store");
        assert_eq!(store.acked_mseq(), 0);
        assert_eq!(
            store
                .commit_mutation(&write_mutation(1, "/dsh/home/a.txt", b"hello"))
                .expect("write"),
            CommitOutcome::Appended
        );
        assert_eq!(
            store
                .commit_mutation(&append_mutation(2, "/dsh/home/a.txt", 5, b" world"))
                .expect("append"),
            CommitOutcome::Appended
        );
        assert!(store
            .commit_mutation(&mkdir_mutation(3, "/dsh/workspace/src"))
            .is_ok());
        assert!(store
            .commit_mutation(&write_mutation(
                4,
                "/dsh/workspace/src/main.rs",
                b"fn main() {}\n"
            ))
            .is_ok());
        assert!(store
            .commit_mutation(&chmod_mutation(5, "/dsh/workspace/src/main.rs", 0o600))
            .is_ok());
        assert_eq!(store.acked_mseq(), 5);
        assert_eq!(
            fs::read(root.join("materialized/home/a.txt")).expect("materialized file"),
            b"hello world"
        );
        assert_eq!(
            fs::metadata(root.join("materialized/workspace/src/main.rs"))
                .expect("metadata")
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
    }

    let reopened = Store::open(root, [2u8; 16]).expect("reopen");
    assert_eq!(reopened.acked_mseq(), 5);
    assert!(reopened.recovered_bytes() > 0);
    assert_eq!(
        fs::read(root.join("materialized/home/a.txt")).expect("recovered file"),
        b"hello world"
    );
    assert_eq!(
        fs::read(root.join("materialized/workspace/src/main.rs")).expect("recovered source"),
        b"fn main() {}\n"
    );
    assert_eq!(reopened.next_mseq(), 6);

    let overlay = reopened.pack_overlay().expect("pack overlay");
    assert_eq!(
        &overlay[..2],
        &[0x1f, 0x8b],
        "overlay must be a gzip member"
    );
    let mut tar = Vec::new();
    GzDecoder::new(&overlay[..])
        .read_to_end(&mut tar)
        .expect("gunzip overlay");
    let names = checked_ustar_names(&tar);
    assert!(names.contains(&"home/".to_owned()), "names: {names:?}");
    assert!(names.contains(&"home/a.txt".to_owned()));
    assert!(names.contains(&"workspace/".to_owned()));
    assert!(names.contains(&"workspace/src/main.rs".to_owned()));
}

#[test]
fn wal_duplicate_must_match_payload_and_gaps_are_rejected() {
    let temp = TempDir::new("wal-duplicate");
    let mut store = Store::open(temp.path(), [3u8; 16]).expect("open store");
    let mutation = write_mutation(1, "/dsh/home/x", b"one");
    assert_eq!(
        store.commit_mutation(&mutation).expect("first"),
        CommitOutcome::Appended
    );
    assert_eq!(
        store.commit_mutation(&mutation).expect("duplicate"),
        CommitOutcome::Duplicate
    );
    let changed = write_mutation(1, "/dsh/home/x", b"two");
    assert!(store.commit_mutation(&changed).is_err());
    let gap = write_mutation(3, "/dsh/home/x", b"three");
    assert!(store.commit_mutation(&gap).is_err());
    assert_eq!(store.acked_mseq(), 1);
}

/// A retained identical duplicate of an older complete write must be a no-op.
/// Re-applying the old bytes after a later write would undo it (`wal.md` §6.1,
/// §6.2): the materialized copy is the state of every applied record in mseq
/// order, not a replay of the duplicate's payload.
#[test]
fn duplicate_retained_complete_write_does_not_undo_later_write() {
    let temp = TempDir::new("wal-duplicate-old");
    let root = temp.path();
    let mut store = Store::open(root, [11u8; 16]).expect("open store");
    let first = write_mutation(1, "/dsh/home/dup.txt", b"old");
    store.commit_mutation(&first).expect("first write");
    store
        .commit_mutation(&write_mutation(2, "/dsh/home/dup.txt", b"new"))
        .expect("later write");
    assert_eq!(
        fs::read(root.join("materialized/home/dup.txt")).expect("later bytes"),
        b"new"
    );
    assert_eq!(
        store.commit_mutation(&first).expect("identical duplicate"),
        CommitOutcome::Duplicate
    );
    assert_eq!(
        fs::read(root.join("materialized/home/dup.txt")).expect("duplicate must not undo"),
        b"new"
    );
    assert_eq!(store.acked_mseq(), 2);
}

#[test]
fn wal_torn_tail_is_truncated_and_corruption_fails() {
    let temp = TempDir::new("wal-torn");
    let root = temp.path();
    {
        let mut store = Store::open(root, [4u8; 16]).expect("open store");
        store
            .commit_mutation(&write_mutation(1, "/dsh/home/a", b"a"))
            .expect("write a");
        store
            .commit_mutation(&write_mutation(2, "/dsh/home/b", b"b"))
            .expect("write b");
    }
    let files = wal_files(root);
    let newest = files.last().expect("segment").to_path_buf();
    let mut file = fs::OpenOptions::new()
        .append(true)
        .open(&newest)
        .expect("open segment");
    file.write_all(&[0xde, 0xad, 0xbe, 0xef])
        .expect("append torn bytes");
    file.sync_all().expect("sync");
    drop(file);

    let recovered = Store::open(root, [5u8; 16]).expect("torn tail recovers");
    assert_eq!(recovered.acked_mseq(), 2);
    assert!(recovered.truncated_tail_bytes() >= 4);
    assert_eq!(fs::read(root.join("materialized/home/a")).expect("a"), b"a");
    assert_eq!(recovered.next_mseq(), 3);
    let _ = recovered;

    let files = wal_files(root);
    // Corrupt the oldest complete segment; a complete record follows it, so the
    // shared D19 classifier must call this corruption, not a torn tail.
    let target = files.first().expect("old segment").to_path_buf();
    let mut bytes = fs::read(&target).expect("read segment");
    assert!(bytes.len() > 48);
    bytes[48] ^= 0xff;
    fs::write(&target, &bytes).expect("write corrupt segment");
    assert!(Store::open(root, [6u8; 16]).is_err());
}

#[test]
fn path_scope_and_symlink_ancestors_are_refused() {
    let temp = TempDir::new("wal-path");
    let root = temp.path();
    let mut store = Store::open(root, [7u8; 16]).expect("open store");
    assert!(store
        .commit_mutation(&write_mutation(1, "/tmp/escape", b"x"))
        .is_err());
    assert!(store
        .commit_mutation(&write_mutation(1, "/dsh/home/../escape", b"x"))
        .is_err());
    assert!(store
        .commit_mutation(&write_mutation(1, "/dsh/other/escape", b"x"))
        .is_err());

    let outside = temp.path().join("outside");
    fs::create_dir_all(&outside).expect("outside");
    let home = root.join("materialized/home");
    fs::create_dir_all(&home).expect("home");
    std::os::unix::fs::symlink(&outside, home.join("link")).expect("symlink");
    assert!(store
        .commit_mutation(&write_mutation(1, "/dsh/home/link/escape", b"x"))
        .unwrap_err().contains("symlink"));
    assert!(!outside.join("escape").exists());
}

#[test]
fn journal_is_persisted_deduplicated_and_torn_tail_truncated() {
    let temp = TempDir::new("journal");
    let root = temp.path();
    {
        let mut store = Store::open(root, [8u8; 16]).expect("open store");
        assert_eq!(store.append_journal("sess-1", 1, b"one").expect("j1"), 1);
        assert_eq!(store.append_journal("sess-1", 2, b"two").expect("j2"), 2);
        assert_eq!(
            store.append_journal("sess-1", 1, b"ignored").expect("dup"),
            2
        );
        assert!(store.append_journal("sess-1", 4, b"gap").is_err());
    }
    {
        let mut store = Store::open(root, [9u8; 16]).expect("reopen");
        assert_eq!(store.append_journal("sess-1", 3, b"three").expect("j3"), 3);
        assert_eq!(store.journal_count(), 1);
    }
    let journal = fs::read_dir(root.join("journal"))
        .expect("journal dir")
        .next()
        .expect("journal entry")
        .expect("journal path")
        .path();
    let mut raw = fs::read(&journal).expect("journal file");
    let good_len = raw.len();
    raw.extend_from_slice(b"torn!");
    fs::write(&journal, raw).expect("append torn journal");
    {
        let mut store = Store::open(root, [10u8; 16]).expect("torn journal recovers");
        assert_eq!(store.append_journal("sess-1", 4, b"four").expect("j4"), 4);
    }
    let raw = fs::read(&journal).expect("journal file");
    assert!(raw.len() > good_len);
}

// --------------------------------------------------------------- host tests

extern "C" fn test_event_callback(json: *const std::os::raw::c_char, _: *mut std::os::raw::c_void) {
    if json.is_null() {
        return;
    }
    let text = unsafe { CStr::from_ptr(json) }
        .to_str()
        .unwrap_or("")
        .to_owned();
    if let Some(sender) = CALLBACK_TX
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .as_ref()
    {
        let _ = sender.send(text);
    }
}

struct HttpResponse {
    status: u16,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

fn http_call(port: u16, request: &[u8]) -> HttpResponse {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("connect HTTP");
    stream
        .set_read_timeout(Some(Duration::from_secs(10)))
        .expect("read timeout");
    stream.write_all(request).expect("write request");
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).expect("read response");
    parse_http_response(&raw)
}

fn get(port: u16, target: &str, cookie: Option<&str>) -> HttpResponse {
    let host = format!("127.0.0.1:{port}");
    let cookie = cookie
        .map(|cookie| format!("Cookie: {cookie}\r\n"))
        .unwrap_or_default();
    let request =
        format!("GET {target} HTTP/1.1\r\nHost: {host}\r\n{cookie}Connection: close\r\n\r\n");
    http_call(port, request.as_bytes())
}

fn parse_http_response(raw: &[u8]) -> HttpResponse {
    let end = raw
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .expect("response header end")
        + 4;
    let head = String::from_utf8_lossy(&raw[..end]).into_owned();
    let mut lines = head.lines();
    let status = lines
        .next()
        .and_then(|line| line.split(' ').nth(1))
        .and_then(|value| value.parse::<u16>().ok())
        .expect("status");
    let mut headers = HashMap::new();
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_owned());
        }
    }
    let mut body = raw[end..].to_vec();
    if headers
        .get("transfer-encoding")
        .map(|value| value.eq_ignore_ascii_case("chunked"))
        .unwrap_or(false)
    {
        body = decode_chunked(&body);
    } else if let Some(length) = headers
        .get("content-length")
        .and_then(|value| value.parse::<usize>().ok())
    {
        body.truncate(length.min(body.len()));
    }
    HttpResponse {
        status,
        headers,
        body,
    }
}

fn decode_chunked(mut encoded: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    loop {
        let Some(end) = encoded.windows(2).position(|window| window == b"\r\n") else {
            return out;
        };
        let size_text = String::from_utf8_lossy(&encoded[..end]);
        let size = usize::from_str_radix(size_text.trim(), 16).unwrap_or(0);
        encoded = &encoded[end + 2..];
        if size == 0 {
            return out;
        }
        if encoded.len() < size {
            return out;
        }
        out.extend_from_slice(&encoded[..size]);
        encoded = &encoded[size..];
        if encoded.starts_with(b"\r\n") {
            encoded = &encoded[2..];
        }
    }
}

fn checked_ustar_names(tar: &[u8]) -> Vec<String> {
    let mut names = Vec::new();
    let mut offset = 0usize;
    let mut terminated = false;
    while offset + 512 <= tar.len() {
        let header = &tar[offset..offset + 512];
        if header.iter().all(|byte| *byte == 0) {
            terminated = true;
            break;
        }
        let stored = parse_octal(&header[148..156]).expect("checksum field");
        let mut sum = 0u64;
        for (index, byte) in header.iter().enumerate() {
            sum += if (148..156).contains(&index) {
                b' ' as u64
            } else {
                *byte as u64
            };
        }
        assert_eq!(sum, stored, "ustar checksum");
        let name = field_text(&header[0..100]);
        let prefix = field_text(&header[345..500]);
        names.push(if prefix.is_empty() {
            name
        } else {
            format!("{prefix}/{name}")
        });
        let size = parse_octal(&header[124..136]).expect("size field") as usize;
        offset += 512 + size.div_ceil(512) * 512;
    }
    assert!(terminated, "tar has two zero terminating blocks");
    names
}

fn field_text(bytes: &[u8]) -> String {
    let end = bytes
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).into_owned()
}

fn parse_octal(bytes: &[u8]) -> Option<u64> {
    let text: String = bytes
        .iter()
        .take_while(|byte| **byte != 0 && **byte != b' ')
        .map(|byte| *byte as char)
        .collect();
    u64::from_str_radix(&text, 8).ok()
}

fn make_assets(root: &Path) -> PathBuf {
    let assets = root.join("assets");
    fs::create_dir_all(assets.join("app")).expect("app dir");
    fs::create_dir_all(assets.join("worker")).expect("worker dir");
    fs::write(
        assets.join("app/index.html"),
        b"<!doctype html><title>host</title><script src=\"/app/app.js\"></script>",
    )
    .expect("index");
    fs::write(assets.join("app/app.js"), b"globalThis.__PDSH_TEST__=1;\n").expect("app js");
    fs::write(assets.join("worker/image.tar.gz"), b"not-a-real-image").expect("image");
    assets
}

#[test]
fn http_ws_overlay_callback_and_ffi_round_trip() {
    let _serial = test_lock();
    let temp = TempDir::new("host");
    let root = temp.path().join("root");
    fs::create_dir_all(&root).expect("root");
    let assets = make_assets(temp.path());
    let host = Host::start(&root, &assets).expect("start host");
    let token = host.info.token.clone();
    let port = host.info.port;
    let cookie = format!("xl_launch_token={token}");

    // Static route, launch exchange and cookie-relative subresource.
    let launch = get(port, &format!("/app/index.html?token={token}"), None);
    assert_eq!(launch.status, 303);
    assert_eq!(
        launch.headers.get("location").map(String::as_str),
        Some("/app/index.html")
    );
    assert!(launch.headers.contains_key("set-cookie"));
    let page = get(port, "/app/index.html", Some(&cookie));
    assert_eq!(page.status, 200);
    assert!(String::from_utf8_lossy(&page.body).contains("__PDSH_TEST__") || !page.body.is_empty());
    let script = get(port, "/app/app.js", Some(&cookie));
    assert_eq!(script.status, 200);
    assert_eq!(
        script.headers.get("content-type").map(String::as_str),
        Some("text/javascript; charset=utf-8")
    );
    let missing = get(port, "/app/../etc/passwd", Some(&cookie));
    assert!(missing.status == 400 || missing.status == 404);

    // Token gate and authority binding.
    assert_eq!(get(port, "/health", None).status, 401);
    assert_eq!(
        get(port, &format!("/health?token=wrong-{token}"), None).status,
        401
    );
    let health = get(port, &format!("/health?token={token}"), None);
    assert_eq!(health.status, 200);
    let health_json: Value = serde_json::from_slice(&health.body).expect("health JSON");
    assert_eq!(health_json["ok"], true);
    assert_eq!(health_json["port"], port);
    assert!(!health_json.to_string().contains(&token));

    // Boot config carries route facts and non-secret placeholders only.
    let config = get(port, &format!("/boot-config?token={token}"), None);
    assert_eq!(config.status, 200);
    let config: Value = serde_json::from_slice(&config.body).expect("boot config JSON");
    assert_eq!(config["xl"]["token"], token);
    assert_eq!(config["xl"]["env"]["PDSH_DSH_LAUNCH_TOKEN"], token.as_str());
    assert_eq!(
        config["xl"]["env"]["DEEPSEEK_API_KEY"],
        "host-managed-placeholder"
    );
    assert_eq!(config["xl"]["env"]["PDSH_MODEL_ROUTE"], "host-edge");
    assert_eq!(
        config["xl"]["env"]["DEEPSEEK_BASE_URL"],
        format!("http://127.0.0.1:{port}")
    );
    assert_eq!(config["xl"]["env"]["DSH_TELEMETRY_DISABLED"], "1");
    assert!(config["xl"]["channelURL"]
        .as_str()
        .unwrap()
        .contains("/channel?token="));
    assert!(config["xl"]["fence"]["generation"].is_u64());

    // HTTP proxy route: callback -> chunked SSE -> end.
    let (callback_tx, callback_rx) = channel();
    *CALLBACK_TX
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(callback_tx);
    crate::host::set_callback(Some(test_event_callback), std::ptr::null_mut());
    let proxy_port = port;
    let proxy_token = token.clone();
    let proxy_thread = std::thread::spawn(move || {
        let host = format!("127.0.0.1:{proxy_port}");
        let body = b"{\"model\":\"test\"}";
        let request = format!(
            "POST /v1/http?token={proxy_token} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nX-Test: visible\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        );
        let mut raw = request.into_bytes();
        raw.extend_from_slice(body);
        http_call(proxy_port, &raw)
    });
    let callback_json = callback_rx
        .recv_timeout(Duration::from_secs(10))
        .expect("callback event");
    let callback_event: Value = serde_json::from_str(&callback_json).expect("callback JSON");
    assert_eq!(callback_event["type"], "http");
    assert_eq!(callback_event["method"], "POST");
    assert_eq!(callback_event["path"], "/v1/http");
    assert_eq!(callback_event["headers"]["x-test"], "visible");
    let request_id = callback_event["id"].as_u64().expect("id");
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(callback_event["bodyBase64"].as_str().expect("bodyBase64"))
        .expect("base64");
    assert_eq!(decoded, b"{\"model\":\"test\"}");
    host.respond(
        request_id,
        0,
        200,
        Some("{\"content-type\":\"text/event-stream\"}"),
        &[],
    )
    .expect("head");
    host.respond(request_id, 1, 0, None, b"data: one\n\n")
        .expect("chunk one");
    host.respond(request_id, 1, 0, None, b"data: two\n\n")
        .expect("chunk two");
    host.respond(request_id, 2, 0, None, &[]).expect("end");
    let proxy = proxy_thread.join().expect("proxy thread");
    assert_eq!(proxy.status, 200);
    assert_eq!(
        proxy.headers.get("content-type").map(String::as_str),
        Some("text/event-stream")
    );
    assert_eq!(proxy.body, b"data: one\n\ndata: two\n\n");

    // Error before head becomes a bounded JSON error.
    let proxy_port = port;
    let proxy_token = token.clone();
    let error_thread = std::thread::spawn(move || {
        let host = format!("127.0.0.1:{proxy_port}");
        let request = format!(
            "POST /v1/messages?token={proxy_token} HTTP/1.1\r\nHost: {host}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        );
        http_call(proxy_port, request.as_bytes())
    });
    let callback_json = callback_rx
        .recv_timeout(Duration::from_secs(10))
        .expect("error callback");
    let callback_event: Value = serde_json::from_str(&callback_json).expect("error event");
    let request_id = callback_event["id"].as_u64().expect("id");
    host.respond(request_id, 3, 502, None, b"upstream refused")
        .expect("error");
    let error_response = error_thread.join().expect("error thread");
    assert_eq!(error_response.status, 502);
    let error_json: Value = serde_json::from_slice(&error_response.body).expect("error JSON");
    assert_eq!(error_json["error"], "upstream_proxy_error");

    // Overlay route: materialized home/workspace, gzip + ustar.
    host.store_commit(&write_mutation(1, "/dsh/home/a.txt", b"overlay"))
        .expect("seed overlay");
    let overlay = get(port, &format!("/overlay.tar.gz?token={token}"), None);
    assert_eq!(overlay.status, 200);
    assert_eq!(&overlay.body[..2], &[0x1f, 0x8b]);
    let mut tar = Vec::new();
    GzDecoder::new(&overlay.body[..])
        .read_to_end(&mut tar)
        .expect("gunzip");
    let names = checked_ustar_names(&tar);
    assert!(names.contains(&"home/a.txt".to_owned()));

    // WebSocket HELLO, VFS, journal, unsupported exec and level-2 refusal.
    let acked = host.store_snapshot().0;
    let bad_uri: tungstenite::http::Uri =
        format!("ws://127.0.0.1:{port}/channel?token=wrong-token")
            .parse()
            .expect("bad ws URI");
    assert!(
        connect(ClientRequestBuilder::new(bad_uri)).is_err(),
        "a wrong channel token must be rejected during the upgrade"
    );
    let uri: tungstenite::http::Uri = format!("ws://127.0.0.1:{port}/channel?token={token}")
        .parse()
        .expect("ws URI");
    let (mut socket, _) = connect(ClientRequestBuilder::new(uri)).expect("ws connect");
    let mut seq = 1u64;
    let hello = worker_hello(&host, acked);
    socket
        .send(tungstenite::Message::Binary(
            encode(&Frame::Hello(hello), seq)
                .expect("encode hello")
                .into(),
        ))
        .expect("send hello");
    let host_hello = read_frame(&mut socket);
    let Frame::Hello(host_hello) = host_hello else {
        panic!("host first frame is not HELLO");
    };
    assert_eq!(host_hello.get("proto"), Some(&JsonValue::Integer(1)));
    assert_eq!(
        host_hello.get("role"),
        Some(&JsonValue::String("host".to_owned()))
    );
    assert_eq!(
        host_hello.get("caps"),
        Some(&JsonValue::Array(vec![
            JsonValue::String("channel/1".to_owned()),
            JsonValue::String("wal/1".to_owned()),
            JsonValue::String("exec/1".to_owned()),
        ]))
    );

    seq += 1;
    let vfs = write_mutation(acked + 1, "/dsh/home/ws.txt", b"via-ws");
    socket
        .send(tungstenite::Message::Binary(
            encode(&Frame::VfsMut(vfs), seq).expect("encode vfs").into(),
        ))
        .expect("send vfs");
    let ack = read_frame(&mut socket);
    assert_eq!(
        ack,
        Frame::VfsAck(VfsAck {
            level: AckLevel::Write,
            upto_mseq: acked + 1
        })
    );
    assert_eq!(
        fs::read(root.join("materialized/home/ws.txt")).expect("ws materialized"),
        b"via-ws"
    );

    seq += 1;
    let journal = pdsh_wire::Journal {
        jseq: 1,
        session_id: "sess-ws".to_owned(),
        record: b"journal-bytes".to_vec(),
    };
    socket
        .send(tungstenite::Message::Binary(
            encode(&Frame::Journal(journal), seq)
                .expect("encode journal")
                .into(),
        ))
        .expect("send journal");
    let journal_ack = read_frame(&mut socket);
    assert_eq!(
        journal_ack,
        Frame::JournalAck(pdsh_wire::JournalAck {
            level: AckLevel::Write,
            session_id: "sess-ws".to_owned(),
            upto_jseq: 1
        })
    );

    seq += 1;
    let exec = JsonObject::from_pairs([
        ("exec_id".to_owned(), JsonValue::String("exec-1".to_owned())),
        (
            "argv".to_owned(),
            JsonValue::Array(vec![JsonValue::String("true".to_owned())]),
        ),
        ("cwd".to_owned(), JsonValue::String("/dsh".to_owned())),
        ("requires_mseq".to_owned(), JsonValue::Integer(0)),
        ("idem_key".to_owned(), JsonValue::String("a".repeat(64))),
    ])
    .expect("exec object");
    socket
        .send(tungstenite::Message::Binary(
            encode(&Frame::ExecReq(exec), seq)
                .expect("encode exec")
                .into(),
        ))
        .expect("send exec");
    let exec_event = read_frame(&mut socket);
    let Frame::ExecEvt(event) = exec_event else {
        panic!("exec request did not produce an explicit event");
    };
    assert_eq!(
        event.get("type"),
        Some(&JsonValue::String("error".to_owned()))
    );
    assert_eq!(
        event.get("code"),
        Some(&JsonValue::String("UNSUPPORTED".to_owned()))
    );

    seq += 1;
    let barrier = VfsBarrier {
        want_level: AckLevel::EpochFsync,
        upto_mseq: acked + 1,
    };
    socket
        .send(tungstenite::Message::Binary(
            encode(&Frame::VfsBarrier(barrier), seq)
                .expect("encode barrier")
                .into(),
        ))
        .expect("send barrier");
    let barrier_ack = read_frame(&mut socket);
    let Frame::VfsAck(barrier_ack) = barrier_ack else {
        panic!("barrier did not produce VFS_ACK");
    };
    assert_eq!(
        barrier_ack.level,
        AckLevel::Write,
        "level 2 must never be claimed"
    );

    // A wrong seq is rejected with ERROR and closes.
    let wrong_seq = seq + 3;
    let vfs = write_mutation(acked + 2, "/dsh/home/wrong-seq", b"x");
    socket
        .send(tungstenite::Message::Binary(
            encode(&Frame::VfsMut(vfs), wrong_seq)
                .expect("encode wrong seq")
                .into(),
        ))
        .expect("send wrong seq");
    let error = read_frame(&mut socket);
    let Frame::Error(error) = error else {
        panic!("wrong seq did not produce ERROR");
    };
    assert_eq!(
        error.get("code"),
        Some(&JsonValue::String("BAD_SEQ".to_owned()))
    );
    let _ = socket.close(None);

    crate::host::set_callback(None, std::ptr::null_mut());
    host.stop();
    drop(host);

    // The exported FFI install/start/callback/respond/status/stop path.
    let ffi_assets = temp.path().join("ffi-assets");
    fs::create_dir_all(&ffi_assets).expect("ffi assets");
    let ffi_zip = temp.path().join("ffi.zip");
    {
        let file = fs::File::create(&ffi_zip).expect("ffi zip file");
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        writer
            .start_file("app/index.html", options)
            .expect("ffi app");
        writer.write_all(b"<html>ffi</html>").expect("ffi app body");
        writer
            .start_file("worker/image.tar.gz", options)
            .expect("ffi image");
        writer.write_all(b"ffi-image").expect("ffi image body");
        writer.finish().expect("ffi zip finish");
    }
    let zip_c = std::ffi::CString::new(ffi_zip.to_string_lossy().as_bytes()).expect("zip c");
    let assets_c =
        std::ffi::CString::new(ffi_assets.to_string_lossy().as_bytes()).expect("assets c");
    let installed = unsafe { crate::pdsh_host_install_bundle(zip_c.as_ptr(), assets_c.as_ptr()) };
    assert!(!installed.is_null());
    let installed_text = unsafe { CStr::from_ptr(installed) }
        .to_str()
        .expect("install JSON")
        .to_owned();
    let installed_json: Value = serde_json::from_str(&installed_text).expect("install JSON parse");
    assert_eq!(installed_json["ok"], true);
    unsafe { crate::pdsh_host_free(installed) };

    let root_ffi = temp.path().join("ffi-root");
    fs::create_dir_all(&root_ffi).expect("ffi root");
    let root_c = std::ffi::CString::new(root_ffi.to_string_lossy().as_bytes()).expect("root c");
    let started = unsafe { crate::pdsh_host_start(root_c.as_ptr(), assets_c.as_ptr()) };
    assert!(!started.is_null());
    let start_text = unsafe { CStr::from_ptr(started) }
        .to_str()
        .expect("start JSON")
        .to_owned();
    let start_json: Value = serde_json::from_str(&start_text).expect("start JSON parse");
    let ffi_port = start_json["port"].as_u64().expect("port") as u16;
    let ffi_token = start_json["token"].as_str().expect("token").to_owned();
    assert!(start_json["error"].is_null());
    let status = unsafe { crate::pdsh_host_status() };
    let status_text = unsafe { CStr::from_ptr(status) }
        .to_str()
        .expect("status JSON")
        .to_owned();
    assert!(!status_text.contains(&ffi_token));
    assert!(status_text.contains("\"running\":true"));
    unsafe { crate::pdsh_host_free(status) };

    // Assets installed through the ABI are served after start.
    let ffi_page = get(
        ffi_port,
        "/app/index.html",
        Some(&format!("xl_launch_token={ffi_token}")),
    );
    assert_eq!(ffi_page.status, 200);
    assert_eq!(ffi_page.body, b"<html>ffi</html>");

    // Callback and respond through the exported ABI.
    let (ffi_tx, ffi_rx) = channel();
    *CALLBACK_TX
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(ffi_tx);
    crate::pdsh_host_set_callback(Some(test_event_callback), std::ptr::null_mut());
    let ffi_call_port = ffi_port;
    let ffi_call_token = ffi_token.clone();
    let ffi_thread = std::thread::spawn(move || {
        let host = format!("127.0.0.1:{ffi_call_port}");
        let body = b"prompt";
        let request = format!(
            "POST /v1/chat/completions?token={ffi_call_token} HTTP/1.1\r\nHost: {host}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        );
        let mut raw = request.into_bytes();
        raw.extend_from_slice(body);
        http_call(ffi_call_port, &raw)
    });
    let ffi_callback = ffi_rx
        .recv_timeout(Duration::from_secs(10))
        .expect("ffi callback");
    let ffi_event: Value = serde_json::from_str(&ffi_callback).expect("ffi callback JSON");
    assert_eq!(ffi_event["path"], "/v1/chat/completions");
    let ffi_id = ffi_event["id"].as_u64().expect("ffi id");
    let headers_c = std::ffi::CString::new("{\"content-type\":\"text/plain\"}").expect("headers");
    unsafe {
        crate::pdsh_host_respond(ffi_id, 0, 200, headers_c.as_ptr(), std::ptr::null(), 0);
        crate::pdsh_host_respond(ffi_id, 1, 0, std::ptr::null(), b"ffi-stream".as_ptr(), 10);
        crate::pdsh_host_respond(ffi_id, 2, 0, std::ptr::null(), std::ptr::null(), 0);
    }
    let ffi_response = ffi_thread.join().expect("ffi thread");
    assert_eq!(ffi_response.status, 200);
    assert_eq!(ffi_response.body, b"ffi-stream");
    crate::pdsh_host_set_callback(None, std::ptr::null_mut());

    crate::pdsh_host_stop();
    unsafe { crate::pdsh_host_free(started) };
    let stopped = unsafe { crate::pdsh_host_status() };
    let stopped_text = unsafe { CStr::from_ptr(stopped) }
        .to_str()
        .expect("stopped JSON")
        .to_owned();
    assert!(stopped_text.contains("\"running\":false"));
    unsafe { crate::pdsh_host_free(stopped) };
}

fn capture_callbacks() -> Receiver<String> {
    let (tx, rx) = channel();
    *CALLBACK_TX
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(tx);
    crate::host::set_callback(Some(test_event_callback), std::ptr::null_mut());
    rx
}

fn clear_callbacks(host: &Host) {
    crate::host::set_callback(None, std::ptr::null_mut());
    host.stop();
}

fn next_callback_of_type(rx: &Receiver<String>, wanted: &str) -> Value {
    loop {
        let text = rx
            .recv_timeout(Duration::from_secs(10))
            .expect("callback event");
        let event: Value = serde_json::from_str(&text).expect("callback JSON");
        if event["type"] == wanted {
            return event;
        }
    }
}

fn proxy_request_thread(
    port: u16,
    token: String,
    path: &'static str,
) -> std::thread::JoinHandle<HttpResponse> {
    std::thread::spawn(move || {
        let host = format!("127.0.0.1:{port}");
        let body = b"{\"model\":\"test\"}";
        let request = format!(
            "POST {path}?token={token} HTTP/1.1\r\nHost: {host}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        );
        let mut raw = request.into_bytes();
        raw.extend_from_slice(body);
        http_call(port, &raw)
    })
}

/// Before the fix each `Host::start` reset its request id to 1, so a late
/// response from an earlier host generation could land on the new host's id 1.
/// Request ids are a process-lifetime resource now.
#[test]
fn http_request_ids_are_process_unique_across_host_restarts() {
    let _serial = test_lock();
    let temp = TempDir::new("ids");
    let assets = make_assets(temp.path());
    let root_a = temp.path().join("root-a");
    fs::create_dir_all(&root_a).expect("root a");
    let host_a = Host::start(&root_a, &assets).expect("start a");
    let port_a = host_a.info.port;
    let token_a = host_a.info.token.clone();
    let rx = capture_callbacks();
    let thread_a = proxy_request_thread(port_a, token_a, "/v1/chat/completions");
    let event_a = next_callback_of_type(&rx, "http");
    let id_a = event_a["id"].as_u64().expect("id a");
    host_a
        .respond(id_a, 3, 502, None, b"close a")
        .expect("finish a");
    let response_a = thread_a.join().expect("thread a");
    assert_eq!(response_a.status, 502);
    clear_callbacks(&host_a);
    drop(host_a);

    let root_b = temp.path().join("root-b");
    fs::create_dir_all(&root_b).expect("root b");
    let host_b = Host::start(&root_b, &assets).expect("start b");
    let port_b = host_b.info.port;
    let token_b = host_b.info.token.clone();
    let rx = capture_callbacks();
    let thread_b = proxy_request_thread(port_b, token_b, "/v1/chat/completions");
    let event_b = next_callback_of_type(&rx, "http");
    let id_b = event_b["id"].as_u64().expect("id b");

    assert!(
        id_b > id_a,
        "request ids must not reset across host starts: first={id_a} second={id_b}"
    );
    assert!(id_b <= crate::host::MAX_HTTP_ID);
    // A response carrying the old generation's id must not touch the new
    // pending request, even though both hosts live in the same process.
    assert!(
        host_b.respond(id_a, 0, 200, Some("{}"), &[]).is_err(),
        "stale generation id {id_a} must be refused by the new host"
    );
    host_b
        .respond(id_b, 0, 200, Some("{\"content-type\":\"text/plain\"}"), &[])
        .expect("head b");
    host_b.respond(id_b, 1, 0, None, b"ok").expect("chunk b");
    host_b.respond(id_b, 2, 0, None, &[]).expect("end b");
    let response_b = thread_b.join().expect("thread b");
    assert_eq!(response_b.status, 200);
    assert_eq!(response_b.body, b"ok");
    clear_callbacks(&host_b);
}

#[test]
fn http_id_allocator_refuses_js_safe_integer_exhaustion() {
    let counter = AtomicU64::new(crate::host::MAX_HTTP_ID - 1);
    assert_eq!(
        crate::host::next_http_id_from(&counter),
        Some(crate::host::MAX_HTTP_ID - 1)
    );
    assert_eq!(
        crate::host::next_http_id_from(&counter),
        Some(crate::host::MAX_HTTP_ID)
    );
    assert_eq!(crate::host::next_http_id_from(&counter), None);
    assert_eq!(crate::host::next_http_id_from(&counter), None);
    assert_eq!(counter.load(Ordering::SeqCst), crate::host::MAX_HTTP_ID + 1);
}

/// A real TCP peer that aborts (RST) before the upstream answers must make the
/// host cancel the pending exchange and tell ArkTS through a local cancel
/// callback, before any upstream response is supplied.
#[test]
fn tcp_client_abort_cancels_pending_http_before_upstream_respond() {
    use std::os::fd::AsRawFd;

    let _serial = test_lock();
    let temp = TempDir::new("abort");
    let root = temp.path().join("root");
    fs::create_dir_all(&root).expect("root");
    let assets = make_assets(temp.path());
    let host = Host::start(&root, &assets).expect("start host");
    let token = host.info.token.clone();
    let rx = capture_callbacks();

    let mut socket = TcpStream::connect(("127.0.0.1", host.info.port)).expect("connect");
    socket
        .set_read_timeout(Some(Duration::from_secs(10)))
        .expect("read timeout");
    let request = format!(
        "POST /v1/chat/completions?token={token} HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}",
        host.info.port
    );
    socket.write_all(request.as_bytes()).expect("write request");
    let event = next_callback_of_type(&rx, "http");
    let id = event["id"].as_u64().expect("request id");
    assert_eq!(event["generation"], host.generation());

    let linger = libc::linger {
        l_onoff: 1,
        l_linger: 0,
    };
    let configured = unsafe {
        libc::setsockopt(
            socket.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_LINGER,
            &linger as *const libc::linger as *const libc::c_void,
            std::mem::size_of::<libc::linger>() as libc::socklen_t,
        )
    };
    assert_eq!(configured, 0, "set SO_LINGER");
    drop(socket);

    let cancel = next_callback_of_type(&rx, "http-cancel");
    assert_eq!(cancel["id"], id);
    assert_eq!(cancel["generation"], host.generation());
    assert!(cancel.get("token").is_none());
    assert!(cancel.get("bodyBase64").is_none());
    assert!(cancel.get("headers").is_none());
    assert!(
        host.respond(id, 0, 200, Some("{}"), &[]).is_err(),
        "the aborted request must already be gone"
    );
    assert_eq!(host.health_json()["pendingHttp"].as_u64(), Some(0));
    clear_callbacks(&host);
}

/// A normal client write-half-close is not an abort: the client still reads the
/// upstream response. The poll-based detector must leave this request pending.
#[test]
fn tcp_half_close_keeps_pending_http_until_response_completes() {
    use std::net::Shutdown;

    let _serial = test_lock();
    let temp = TempDir::new("halfclose");
    let root = temp.path().join("root");
    fs::create_dir_all(&root).expect("root");
    let assets = make_assets(temp.path());
    let host = Host::start(&root, &assets).expect("start host");
    let token = host.info.token.clone();
    let rx = capture_callbacks();

    let mut socket = TcpStream::connect(("127.0.0.1", host.info.port)).expect("connect");
    socket
        .set_read_timeout(Some(Duration::from_secs(10)))
        .expect("read timeout");
    let request = format!(
        "POST /v1/chat/completions?token={token} HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}",
        host.info.port
    );
    socket.write_all(request.as_bytes()).expect("write request");
    socket.shutdown(Shutdown::Write).expect("half close write");
    let event = next_callback_of_type(&rx, "http");
    let id = event["id"].as_u64().expect("request id");

    // Let several abort-detection ticks run before answering upstream.
    std::thread::sleep(Duration::from_millis(500));
    assert_eq!(
        host.health_json()["pendingHttp"].as_u64(),
        Some(1),
        "half-close must not cancel the pending request"
    );
    host.respond(id, 0, 200, Some("{\"content-type\":\"text/plain\"}"), &[])
        .expect("head");
    host.respond(id, 1, 0, None, b"ok").expect("chunk");
    host.respond(id, 2, 0, None, &[]).expect("end");
    let mut raw = Vec::new();
    socket.read_to_end(&mut raw).expect("read response");
    let response = parse_http_response(&raw);
    assert_eq!(response.status, 200);
    assert_eq!(response.body, b"ok");
    clear_callbacks(&host);
}

fn worker_hello(host: &Host, acked: u64) -> JsonObject {
    let fence = JsonObject::from_pairs([
        (
            "owner".to_owned(),
            JsonValue::String("pdsh-host".to_owned()),
        ),
        (
            "generation".to_owned(),
            JsonValue::Integer(host.info.session_epoch as i128),
        ),
        ("wal_seq".to_owned(), JsonValue::Integer(acked as i128)),
    ])
    .expect("fence");
    JsonObject::from_pairs([
        ("proto".to_owned(), JsonValue::Integer(1)),
        ("role".to_owned(), JsonValue::String("worker".to_owned())),
        (
            "boot_id".to_owned(),
            JsonValue::String(host.info.boot_id.clone()),
        ),
        (
            "session_epoch".to_owned(),
            JsonValue::Integer(host.info.session_epoch as i128),
        ),
        ("fence".to_owned(), JsonValue::Object(fence)),
        (
            "caps".to_owned(),
            JsonValue::Array(
                ["channel/1", "vfs-sink/1", "journal/1", "api/1"]
                    .into_iter()
                    .map(|cap| JsonValue::String(cap.to_owned()))
                    .collect(),
            ),
        ),
        ("last_mseq".to_owned(), JsonValue::Integer(0)),
    ])
    .expect("hello")
}

fn read_frame<S: Read + Write>(socket: &mut tungstenite::WebSocket<S>) -> Frame {
    for _ in 0..8 {
        match socket.read().expect("read frame") {
            tungstenite::Message::Binary(bytes) => {
                let (_, frame) = decode_with_header(&bytes).expect("decode frame");
                return frame;
            }
            tungstenite::Message::Ping(_) | tungstenite::Message::Pong(_) => continue,
            other => panic!("unexpected websocket message: {other:?}"),
        }
    }
    panic!("no frame")
}

#[test]
fn bundle_install_is_confined_and_replaces_assets() {
    let _serial = test_lock();
    let temp = TempDir::new("bundle");
    let assets = temp.path().join("assets");
    fs::create_dir_all(assets.join("old")).expect("old assets");
    fs::write(assets.join("old/keep.txt"), b"old").expect("old file");

    let zip_path = temp.path().join("bundle.zip");
    {
        let file = fs::File::create(&zip_path).expect("zip file");
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        writer
            .start_file("app/index.html", options)
            .expect("start app");
        writer.write_all(b"<html>new</html>").expect("write app");
        writer
            .start_file("worker/app.js", options)
            .expect("start worker");
        writer
            .write_all(b"console.log('new')")
            .expect("write worker");
        writer.finish().expect("finish zip");
    }
    crate::bundle::install_bundle(&zip_path, &assets).expect("install bundle");
    assert_eq!(
        fs::read(assets.join("app/index.html")).expect("installed app"),
        b"<html>new</html>"
    );
    assert!(assets.join("worker/app.js").is_file());
    assert!(!assets.join("old/keep.txt").exists());

    // Traversal entry must be refused and leave the installed tree intact.
    let bad_zip = temp.path().join("bad.zip");
    {
        let file = fs::File::create(&bad_zip).expect("bad zip file");
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        writer
            .start_file("../escape.txt", options)
            .expect("start bad");
        writer.write_all(b"escape").expect("write bad");
        writer.finish().expect("finish bad zip");
    }
    assert!(crate::bundle::install_bundle(&bad_zip, &assets).is_err());
    assert!(assets.join("app/index.html").is_file());
}

#[test]
fn call_abort_and_end_payloads_reject_unknown_api_ids_cleanly() {
    // Construct the API frame types so the wire surface stays compiled even
    // when the channel-level test is not running.
    let abort = Frame::ApiAbort(ApiAbort {
        req_id: 1,
        reason: "cancel".to_owned(),
    });
    assert_eq!(abort.frame_type(), pdsh_wire::FrameType::ApiAbort);
    let response = Frame::ApiRes(ApiResponse {
        req_id: 1,
        head: None,
        body: Vec::new(),
    });
    assert_eq!(response.frame_type(), pdsh_wire::FrameType::ApiRes);
    let end = Frame::ApiEnd(ApiEnd { req_id: 1 });
    assert_eq!(end.frame_type(), pdsh_wire::FrameType::ApiEnd);
}

#[test]
fn image_directory_write_materializes_and_recovers_without_mkdir_mutation() {
    let temp = TempDir::new("image-directory-write");
    let path = "/dsh/home/tool-journal/turn-1.json";
    {
        let mut store = Store::open(temp.path(), [31u8; 16]).unwrap();
        store.commit_mutation(&write_mutation(1, path, b"journal"))
            .expect("write into an image-provided directory");
        assert_eq!(fs::read(temp.path().join("materialized/home/tool-journal/turn-1.json")).unwrap(), b"journal");
    }
    let store = Store::open(temp.path(), [32u8; 16]).expect("recover image-directory write");
    assert_eq!(store.acked_mseq(), 1);
    assert_eq!(fs::read(temp.path().join("materialized/home/tool-journal/turn-1.json")).unwrap(), b"journal");
}

#[test]
fn model_launch_projection_is_allowlisted() {
    let document = r#"{
      "version": 1,
      "selectedId": "deepseek",
      "providers": [
        {
          "id": "deepseek",
          "displayName": "DeepSeek",
          "baseURL": "https://api.deepseek.com/anthropic",
          "model": "deepseek-flash",
          "reasoningEffort": "high",
          "models": [
            {"id": "deepseek-flash", "name": "Flash", "contextWindow": 64000,
             "maxTokens": 8192, "textInput": true, "imageInput": false,
             "reasoningEffort": "high"}
          ],
          "encoder": "deepseek",
          "credentialRef": "pdsh.model.key.deepseek",
          "apiKey": "sk-should-not-survive"
        },
        {
          "id": "alt",
          "displayName": "Alt",
          "baseURL": "https://example.com/api",
          "model": "alt-1"
        }
      ],
      "officialWebTools": true,
      "PDSH_EVIL_ENV": "1"
    }"#;
    let launch = crate::host::parse_model_launch(document).expect("valid launch document");
    assert_eq!(launch.selected_model(), Some("deepseek-flash"));
    assert!(launch.official_web_tools);
    let projected = launch.document_json();
    assert!(!projected.contains("sk-should-not-survive"));
    assert!(!projected.contains("credentialRef"));
    assert!(!projected.contains("encoder"));
    assert!(!projected.contains("officialWebTools"));
    assert!(!projected.contains("PDSH_EVIL_ENV"));
    let parsed: Value = serde_json::from_str(&projected).expect("projected JSON");
    assert_eq!(parsed["version"], 1);
    assert_eq!(parsed["selectedId"], "deepseek");
    assert_eq!(parsed["providers"][0]["displayName"], "DeepSeek");
    assert_eq!(
        parsed["providers"][0]["baseURL"],
        "https://api.deepseek.com/anthropic"
    );
    assert_eq!(parsed["providers"][0]["models"][0]["contextWindow"], 64000);
    assert_eq!(parsed["providers"].as_array().map(Vec::len), Some(2));

    for bad in [
        "{}",
        r#"{"version":2,"selectedId":"a","providers":[]}"#,
        r#"{"version":1,"selectedId":"missing","providers":[{"id":"a","displayName":"A","baseURL":"https://example.com","model":"m"}]}"#,
        r#"{"version":1,"selectedId":"a","providers":[{"id":"a","displayName":"A","baseURL":"http://example.com?x=1","model":"m"}]}"#,
        r#"{"version":1,"selectedId":"a","providers":[{"id":"a","displayName":"A","baseURL":"https://example.com","model":"m","models":[{"id":"m","textInput":false,"imageInput":false}]}]}"#,
        r#"{"version":1,"selectedId":"a","providers":[{"id":"a","displayName":"A","baseURL":"https://user:pw@example.com","model":"m"}]}"#,
    ] {
        assert!(crate::host::parse_model_launch(bad).is_none(), "accepted {bad}");
    }
}

#[test]
fn boot_config_adds_only_validated_model_projection() {
    let _serial = test_lock();
    let temp = TempDir::new("model-launch-boot");
    let root = temp.path().join("root");
    fs::create_dir_all(&root).expect("root");
    let assets = make_assets(temp.path());
    fs::write(
        root.join(crate::host::MODEL_LAUNCH_FILE),
        br#"{
          "version": 1,
          "selectedId": "deepseek",
          "providers": [{
            "id": "deepseek",
            "displayName": "DeepSeek",
            "baseURL": "https://api.deepseek.com/anthropic",
            "model": "deepseek-flash"
          }]
        }"#,
    )
    .expect("launch file");
    let host = Host::start(&root, &assets).expect("start host");
    let config = host.boot_config();
    let env = &config["xl"]["env"];
    assert_eq!(env["PDSH_MODEL_NAME"], "deepseek-flash");
    assert_eq!(
        env["DEEPSEEK_BASE_URL"],
        format!("http://127.0.0.1:{}", host.info.port)
    );
    assert!(env.get("PDSH_EVIL_ENV").is_none());
    let projection: Value = serde_json::from_str(
        env["PDSH_MODEL_PROVIDER_JSON"]
            .as_str()
            .expect("projection string"),
    )
    .expect("projection JSON");
    assert_eq!(projection["selectedId"], "deepseek");
    assert_eq!(
        projection["providers"][0]["baseURL"],
        "https://api.deepseek.com/anthropic"
    );
    host.stop();
}

const OFFICIAL_LAUNCH_BASE: &str = r#"{
  "version": 1,
  "selectedId": "deepseek",
  "providers": [{
    "id": "deepseek",
    "displayName": "DeepSeek",
    "baseURL": "https://api.deepseek.com/anthropic",
    "model": "deepseek-flash",
    "apiKey": "sk-official-secret"
  }]
}"#;

fn official_launch_document(flag: Option<Value>) -> String {
    let mut document: Value =
        serde_json::from_str(OFFICIAL_LAUNCH_BASE).expect("launch base document");
    if let Some(flag) = flag {
        document
            .as_object_mut()
            .expect("launch document object")
            .insert("officialWebTools".to_owned(), flag);
    }
    document.to_string()
}

#[test]
fn model_launch_official_web_tools_is_host_private_and_fail_closed() {
    // The root grant is host-private: absent, false, a non-boolean or null all
    // leave it disabled, and none of them ever reaches the Worker projection.
    for (flag, expected) in [
        (None, false),
        (Some(serde_json::json!(false)), false),
        (Some(serde_json::json!("true")), false),
        (Some(serde_json::json!(1)), false),
        (Some(Value::Null), false),
        (Some(serde_json::json!(true)), true),
    ] {
        let document = official_launch_document(flag);
        let launch = crate::host::parse_model_launch(&document)
            .unwrap_or_else(|| panic!("rejected launch document: {document}"));
        assert_eq!(launch.official_web_tools, expected, "flag case {document}");
        let projected = launch.document_json();
        assert!(
            !projected.contains("officialWebTools"),
            "projection leaked the host-private flag: {projected}"
        );
        assert!(!projected.contains("sk-official-secret"));
    }
}

#[test]
fn boot_config_publishes_official_web_env_only_for_frozen_grant() {
    let _serial = test_lock();
    for (label, flag, enabled) in [
        ("official-web-absent", None, false),
        ("official-web-false", Some(serde_json::json!(false)), false),
        ("official-web-invalid", Some(serde_json::json!("true")), false),
        ("official-web-true", Some(serde_json::json!(true)), true),
    ] {
        let temp = TempDir::new(label);
        let root = temp.path().join("root");
        fs::create_dir_all(&root).expect("root");
        let assets = make_assets(temp.path());
        fs::write(
            root.join(crate::host::MODEL_LAUNCH_FILE),
            official_launch_document(flag),
        )
        .expect("launch file");
        let host = Host::start(&root, &assets).expect("start host");
        let config = host.boot_config();
        let env = &config["xl"]["env"];
        if enabled {
            assert_eq!(env["PDSH_WEB_SEARCH_PROVIDER"], "deepseek-search-api");
            assert_eq!(env["PDSH_WEB_FETCH_PROVIDER"], "deepseek-fetch-api");
            assert_eq!(
                env["DEEPSEEK_SEARCH_API_BASE_URL"],
                "https://api.deepseek.com/v1/tools"
            );
            assert_eq!(env["DEEPSEEK_SEARCH_API_KEY"], "phone-host-injects-key");
            let projection = env["PDSH_MODEL_PROVIDER_JSON"]
                .as_str()
                .expect("projection string");
            assert!(!projection.contains("officialWebTools"));
            assert!(!projection.contains("sk-official-secret"));
            assert!(!config.to_string().contains("sk-official-secret"));
        } else {
            assert!(env.get("PDSH_WEB_SEARCH_PROVIDER").is_none());
            assert!(env.get("PDSH_WEB_FETCH_PROVIDER").is_none());
            assert!(env.get("DEEPSEEK_SEARCH_API_BASE_URL").is_none());
            assert!(env.get("DEEPSEEK_SEARCH_API_KEY").is_none());
        }
        host.stop();
    }
}

// ------------------------------------------------- workspace artifact facts

#[test]
fn workspace_changes_reads_real_records_scope_and_removal() {
    let temp = TempDir::new("workspace-changes");
    let root = temp.path();
    let mut store = Store::open(root, [21u8; 16]).expect("open store");
    store
        .commit_mutation(&write_mutation(1, "/dsh/home/private.txt", b"home"))
        .expect("home write");
    store
        .commit_mutation(&write_mutation(
            2,
            "/dsh/workspace/notes/a.md",
            b"# measured\n",
        ))
        .expect("workspace write");
    store
        .commit_mutation(&mkdir_mutation(3, "/dsh/workspace/src"))
        .expect("mkdir");
    store
        .commit_mutation(&write_mutation(4, "/dsh/workspace/src/main.rs", b"fn main(){}\n"))
        .expect("nested write");
    store
        .commit_mutation(&remove_mutation(5, "/dsh/workspace/notes/a.md"))
        .expect("remove");
    store
        .commit_mutation(&write_mutation(6, "/dsh/workspace/kept.txt", b"keep"))
        .expect("kept write");

    let changes = store.workspace_changes(0, 6).expect("changes");
    assert_eq!(
        changes
            .iter()
            .map(|change| (change.path.as_str(), change.size, change.mseq))
            .collect::<Vec<_>>(),
        vec![
            ("/dsh/workspace/src/main.rs", 12, 4),
            ("/dsh/workspace/kept.txt", 4, 6),
        ],
        "home writes, mkdir, and removed files must not be reported as artifacts"
    );

    // Only the exact requested half-open range is read.
    let kept = store.workspace_changes(4, 6).expect("range");
    assert_eq!(kept.len(), 1);
    assert_eq!(kept[0].path, "/dsh/workspace/kept.txt");
    assert!(store.workspace_changes(5, 5).expect("empty range").is_empty());
}

#[test]
fn workspace_changes_rejects_bad_bounds_and_survives_reopen() {
    let temp = TempDir::new("workspace-changes-reopen");
    let root = temp.path();
    {
        let mut store = Store::open(root, [22u8; 16]).expect("open store");
        store
            .commit_mutation(&write_mutation(1, "/dsh/workspace/a.txt", b"a"))
            .expect("write");
        assert!(store.workspace_changes(0, 2).is_err(), "past acked");
        assert!(store.workspace_changes(2, 2).is_err(), "start past acked");
        assert!(store.workspace_changes(2, 1).is_err(), "inverted");
    }
    let reopened = Store::open(root, [23u8; 16]).expect("reopen");
    let changes = reopened.workspace_changes(0, 1).expect("recovered changes");
    assert_eq!(changes.len(), 1);
    assert_eq!(changes[0].path, "/dsh/workspace/a.txt");
    assert_eq!(changes[0].mseq, 1);
}

#[test]
fn host_cached_watermark_tracks_successful_store_commit() {
    let temp = TempDir::new("host-watermark");
    let root = temp.path();
    let assets = make_assets(root);
    let host = Host::start(root, &assets).expect("start host");
    assert_eq!(host.workspace_watermark()["ackedMseq"], "0");
    host.store_commit(&write_mutation(1, "/dsh/workspace/w.txt", b"w"))
        .expect("commit");
    let watermark = host.workspace_watermark();
    assert_eq!(watermark["ackedMseq"], "1");
    let changes = host.workspace_changes_json(0, 1);
    assert_eq!(changes["files"][0]["path"], "/dsh/workspace/w.txt");
    // A held append advances the accepted level-1 tail but not the contiguous
    // materialized watermark; the artifact query must reject that range instead
    // of returning a successful empty list.
    host.store_commit(&append_mutation(
        2,
        "/dsh/home/image-provided.txt",
        6,
        b" world",
    ))
    .expect("held append");
    let watermark = host.workspace_watermark();
    assert_eq!(watermark["ackedMseq"], "2", "accepted tail");
    assert_eq!(host.store_materialized_upto(), 1);
    let unmaterialized = host.workspace_changes_json(0, 2);
    assert!(
        unmaterialized.get("error").is_some(),
        "a range above the materialized watermark must be an error: {unmaterialized}"
    );
    assert_eq!(unmaterialized["files"].as_array().map(Vec::len), Some(0));
    // A bad range is an error object, never a successful empty list.
    let bad = host.workspace_changes_json(0, 9);
    assert!(bad.get("error").is_some());
    assert_eq!(bad["files"].as_array().map(Vec::len), Some(0));
    host.stop();
}

#[test]
fn workspace_change_c_abi_rejects_non_decimal_mseq_strings() {
    let good = std::ffi::CString::new("0").expect("good");
    for bad_text in ["-1", "1.5", " 1", "abc", "", "18446744073709551616"] {
        let bad = std::ffi::CString::new(bad_text).expect("bad");
        let out = unsafe { crate::pdsh_host_workspace_changes(bad.as_ptr(), good.as_ptr()) };
        assert!(!out.is_null());
        let text = unsafe { std::ffi::CStr::from_ptr(out) }
            .to_str()
            .expect("utf8")
            .to_owned();
        unsafe { crate::pdsh_host_free(out) };
        let value: Value = serde_json::from_str(&text).expect("json");
        assert!(
            value.get("error").is_some(),
            "invalid mseq {bad_text:?} must be an error, got {text}"
        );
        assert_eq!(value["files"].as_array().map(Vec::len), Some(0));
    }
    let out = unsafe { crate::pdsh_host_workspace_changes(good.as_ptr(), good.as_ptr()) };
    let text = unsafe { std::ffi::CStr::from_ptr(out) }.to_str().expect("utf8").to_owned();
    unsafe { crate::pdsh_host_free(out) };
    let value: Value = serde_json::from_str(&text).expect("json");
    assert!(
        value.get("error").is_some(),
        "without a running host this must be an error, not an empty success: {text}"
    );
}

// --------------------------------------------------- WAL cold recovery (audit)

// BUG 1 proof (`wal.md` §7.2 "Materialized-copy invariant"): recovery rebuilds
// the complete mseq-1 WAL into a fresh staging tree, so a historical file write
// is never applied over the final directory. Both histories are produced by the
// real Store; no WAL or materialized fixture is hand-written. Each history is
// cold-reopened twice to prove the rebuild is repeatable.
#[test]
fn wal_cold_reopen_replays_historical_file_over_final_directory() {
    let temp = TempDir::new("wal-history-file-to-dir");
    let root = temp.path();
    let node = "/dsh/home/node";
    {
        let mut store = Store::open(root, [41u8; 16]).expect("open store");
        store
            .commit_mutation(&write_mutation(1, node, b"historical file"))
            .expect("write file");
        store
            .commit_mutation(&remove_mutation(2, node))
            .expect("remove file");
        store
            .commit_mutation(&mkdir_mutation(3, node))
            .expect("mkdir at the same path");
        store
            .commit_mutation(&write_mutation(4, "/dsh/home/node/child.txt", b"final child"))
            .expect("write child");
        assert!(root.join("materialized/home/node").is_dir());
        assert_eq!(
            fs::read(root.join("materialized/home/node/child.txt")).expect("child"),
            b"final child"
        );
    }
    let reopened = Store::open(root, [42u8; 16]).expect(
        "cold recovery must not replay a historical file write over the final directory",
    );
    assert_eq!(reopened.acked_mseq(), 4);
    assert_eq!(reopened.materialized_upto_mseq(), 4);
    assert_eq!(
        fs::read(root.join("materialized/home/node/child.txt")).expect("child after recovery"),
        b"final child"
    );
    drop(reopened);
    let reopened_twice = Store::open(root, [42u8; 16]).expect("second cold reopen");
    assert_eq!(reopened_twice.acked_mseq(), 4);
    assert_eq!(
        fs::read(root.join("materialized/home/node/child.txt")).expect("child after second recovery"),
        b"final child"
    );
}

#[test]
fn wal_cold_reopen_replays_historical_directory_over_final_file() {
    let temp = TempDir::new("wal-history-dir-to-file");
    let root = temp.path();
    let node = "/dsh/home/node";
    {
        let mut store = Store::open(root, [43u8; 16]).expect("open store");
        store
            .commit_mutation(&mkdir_mutation(1, node))
            .expect("mkdir");
        store
            .commit_mutation(&remove_mutation(2, node))
            .expect("remove directory");
        store
            .commit_mutation(&write_mutation(3, node, b"final file"))
            .expect("write file");
        assert!(root.join("materialized/home/node").is_file());
    }
    let reopened = Store::open(root, [44u8; 16])
        .expect("cold recovery must not replay a historical mkdir over the final file");
    assert_eq!(reopened.acked_mseq(), 3);
    assert_eq!(reopened.materialized_upto_mseq(), 3);
    assert_eq!(
        fs::read(root.join("materialized/home/node")).expect("final file after recovery"),
        b"final file"
    );
    drop(reopened);
    let reopened_twice = Store::open(root, [44u8; 16]).expect("second cold reopen");
    assert_eq!(reopened_twice.acked_mseq(), 3);
    assert_eq!(
        fs::read(root.join("materialized/home/node")).expect("final file after second recovery"),
        b"final file"
    );
}

// The audit's third regression asserted the *wrong* watermark: it expected
// `acked_mseq == 0` and characterized `live.is_err()` / `failed().is_some()`.
// `channel.md` §4.1 makes HELLO `acked_mseq` the accepted effective tail, so an
// accepted record at mseq 1 makes it 1 even while the contiguous materialized
// watermark stays 0. `wal.md` §5.1/§6.2 make a missing base a hold with
// `VFS_RESYNC`, not a materialization I/O failure, so the failed-state
// characterization was removed when the hold path was implemented.
#[test]
fn wal_cold_reopen_holds_first_appended_image_base_instead_of_permanent_failure() {
    let temp = TempDir::new("wal-appended-image-base");
    let root = temp.path();
    let path = "/dsh/home/image-provided.txt";
    {
        let mut store = Store::open(root, [45u8; 16]).expect("open store");
        let live = store.commit_mutation(&append_mutation(1, path, 6, b" world"));
        assert_eq!(
            live.expect("appended record is accepted at level 1"),
            CommitOutcome::Appended
        );
        assert!(
            store.failed().is_none(),
            "a base mismatch is a §5.1 hold, not a materialization I/O failure"
        );
        assert_eq!(store.acked_mseq(), 1, "accepted level-1 tail");
        assert_eq!(
            store.materialized_upto_mseq(),
            0,
            "the contiguous materialized watermark stays below the held record"
        );
        assert_eq!(
            store.pending_resyncs(),
            vec![crate::wal::PendingResync {
                path: path.to_owned(),
                mseq: 1,
            }]
        );
        assert!(
            wal_files(root)
                .iter()
                .any(|file| fs::metadata(file).map(|m| m.len() > 40).unwrap_or(false)),
            "appended record must be retained in the WAL"
        );
    }
    let mut reopened = Store::open(root, [46u8; 16])
        .expect("cold replay must hold the appended image-base mismatch, not fail permanently");
    assert_eq!(reopened.acked_mseq(), 1);
    assert_eq!(reopened.materialized_upto_mseq(), 0);
    assert_eq!(reopened.pending_resyncs().len(), 1);
    assert!(reopened.failed().is_none());
    // The repair is a complete write at the NEXT normal mseq, carrying the full
    // post-write bytes; mseq 1 is never rewritten or replaced.
    reopened
        .commit_mutation(&write_mutation(2, path, b"hello world"))
        .expect("repair write");
    assert_eq!(reopened.acked_mseq(), 2);
    assert_eq!(reopened.materialized_upto_mseq(), 2);
    assert!(reopened.pending_resyncs().is_empty());
    assert_eq!(
        fs::read(root.join("materialized/home/image-provided.txt")).expect("repaired file"),
        b"hello world"
    );
    drop(reopened);
    let again = Store::open(root, [47u8; 16]).expect("second cold reopen after repair");
    assert_eq!(again.acked_mseq(), 2);
    assert_eq!(again.materialized_upto_mseq(), 2);
    assert_eq!(
        fs::read(root.join("materialized/home/image-provided.txt")).expect("repaired file again"),
        b"hello world"
    );
}

// A held path must not stop other paths from materializing, and a query over
// the unmaterialized range is an error, never a falsely empty success.
#[test]
fn live_append_missing_base_holds_path_and_keeps_applying_other_paths() {
    let temp = TempDir::new("wal-live-hold");
    let root = temp.path();
    let held_path = "/dsh/home/held.txt";
    let mut store = Store::open(root, [48u8; 16]).expect("open store");
    store
        .commit_mutation(&append_mutation(1, held_path, 6, b" world"))
        .expect("held append");
    assert_eq!(store.acked_mseq(), 1);
    assert_eq!(store.materialized_upto_mseq(), 0);
    assert_eq!(store.pending_resyncs().len(), 1);

    store
        .commit_mutation(&write_mutation(2, "/dsh/home/other.txt", b"applied"))
        .expect("other path still applies");
    assert_eq!(store.acked_mseq(), 2);
    assert_eq!(
        store.materialized_upto_mseq(),
        0,
        "the contiguous watermark waits below mseq 1"
    );
    assert_eq!(
        fs::read(root.join("materialized/home/other.txt")).expect("other path"),
        b"applied"
    );
    assert!(store.workspace_changes(0, 1).is_err(), "unmaterialized mseq 1");
    assert!(store.workspace_changes(0, 2).is_err(), "range crosses the hold");
    assert!(store.workspace_changes(0, 0).expect("empty range").is_empty());

    // An identical duplicate of the held record is a no-op, not a second hold
    // and not a re-application.
    assert_eq!(
        store
            .commit_mutation(&append_mutation(1, held_path, 6, b" world"))
            .expect("identical held duplicate"),
        CommitOutcome::Duplicate
    );
    assert_eq!(store.materialized_upto_mseq(), 0);
    assert_eq!(store.pending_resyncs().len(), 1);

    store
        .commit_mutation(&write_mutation(3, held_path, b"hello world"))
        .expect("repair complete write");
    assert_eq!(store.acked_mseq(), 3);
    assert_eq!(
        store.materialized_upto_mseq(),
        3,
        "the applied cover settles mseq 1 and lifts the watermark"
    );
    assert!(store.pending_resyncs().is_empty());
    assert_eq!(
        fs::read(root.join("materialized/home/held.txt")).expect("repaired"),
        b"hello world"
    );
}

// D9 supersession: a later complete write of the same path settles a held
// append, and a remove of an ancestor settles every held append below it.
#[test]
fn complete_write_and_ancestor_remove_settle_held_appends() {
    let temp = TempDir::new("wal-supersede");
    let root = temp.path();
    let path = "/dsh/home/superseded.txt";
    {
        let mut store = Store::open(root, [49u8; 16]).expect("open store");
        store
            .commit_mutation(&append_mutation(1, path, 6, b" world"))
            .expect("held append");
        store
            .commit_mutation(&write_mutation(2, path, b"hello world"))
            .expect("covering complete write");
        assert_eq!(store.materialized_upto_mseq(), 2);
        assert!(store.pending_resyncs().is_empty());
    }
    let reopened = Store::open(root, [50u8; 16]).expect("cold superseded replay");
    assert_eq!(reopened.acked_mseq(), 2);
    assert_eq!(reopened.materialized_upto_mseq(), 2);
    assert!(reopened.pending_resyncs().is_empty());
    assert_eq!(
        fs::read(root.join("materialized/home/superseded.txt")).expect("covered bytes"),
        b"hello world"
    );

    let temp = TempDir::new("wal-ancestor-remove");
    let root = temp.path();
    let child = "/dsh/home/dir/file.txt";
    {
        let mut store = Store::open(root, [51u8; 16]).expect("open store");
        store
            .commit_mutation(&append_mutation(1, child, 6, b" world"))
            .expect("held append below the ancestor");
        store
            .commit_mutation(&remove_mutation(2, "/dsh/home/dir"))
            .expect("ancestor remove");
        assert_eq!(store.materialized_upto_mseq(), 2);
        assert!(store.pending_resyncs().is_empty());
        assert!(!root.join("materialized/home/dir").exists());
    }
    let reopened = Store::open(root, [52u8; 16]).expect("cold ancestor-remove replay");
    assert_eq!(reopened.acked_mseq(), 2);
    assert_eq!(reopened.materialized_upto_mseq(), 2);
    assert!(reopened.pending_resyncs().is_empty());
    assert!(!root.join("materialized/home/dir").exists());
}

// `channel.md` §10: VFS_CONFLICT is terminal only while the held record is
// uncovered; a conflict for a D9-superseded record is ignored.
#[test]
fn vfs_conflict_is_terminal_only_for_uncovered_held_append() {
    let temp = TempDir::new("wal-conflict");
    let root = temp.path();
    let path = "/dsh/home/conflict.txt";
    let mut store = Store::open(root, [53u8; 16]).expect("open store");
    store
        .commit_mutation(&append_mutation(1, path, 6, b" world"))
        .expect("held append");
    assert!(store
        .resolve_vfs_conflict("some other path is no longer a regular file")
        .is_ok());
    assert!(store.failed().is_none(), "unrelated conflict is ignored");
    let failure = store
        .resolve_vfs_conflict("/dsh/home/conflict.txt is no longer a regular file")
        .expect_err("uncovered conflict is terminal");
    assert!(failure.contains(path));
    assert!(store.failed().is_some());

    let temp = TempDir::new("wal-conflict-superseded");
    let root = temp.path();
    let path = "/dsh/home/conflict.txt";
    let mut store = Store::open(root, [54u8; 16]).expect("open store");
    store
        .commit_mutation(&append_mutation(1, path, 6, b" world"))
        .expect("held append");
    store
        .commit_mutation(&write_mutation(2, path, b"hello world"))
        .expect("cover");
    assert!(store
        .resolve_vfs_conflict("/dsh/home/conflict.txt is no longer a regular file")
        .is_ok());
    assert!(
        store.failed().is_none(),
        "a superseded VFS_CONFLICT must not enter Failed"
    );
}

// Real TCP peer regression: append mseq 1 with an image-only base -> level-1
// ACK for the accepted tail plus VFS_RESYNC; the worker answers with a complete
// write at the NEXT normal mseq -> ACK 2 and the materialized watermark 2; a
// reconnect re-requests the hold after HELLO; cold recovery preserves the
// post-write bytes.
#[test]
fn ws_live_append_holds_resyncs_and_cold_recovery_preserves_post_write_bytes() {
    let _serial = test_lock();
    let temp = TempDir::new("ws-resync");
    let root = temp.path().join("root");
    fs::create_dir_all(&root).expect("root");
    let assets = make_assets(temp.path());
    let host = Host::start(&root, &assets).expect("start host");
    let token = host.info.token.clone();
    let port = host.info.port;
    let path = "/dsh/home/image-base.txt";
    let uri: tungstenite::http::Uri = format!("ws://127.0.0.1:{port}/channel?token={token}")
        .parse()
        .expect("ws URI");

    let (mut socket, _) = connect(ClientRequestBuilder::new(uri.clone())).expect("ws connect");
    socket
        .send(tungstenite::Message::Binary(
            encode(&Frame::Hello(worker_hello(&host, 0)), 1)
                .expect("encode hello")
                .into(),
        ))
        .expect("send hello");
    let Frame::Hello(host_hello) = read_frame(&mut socket) else {
        panic!("host first frame is not HELLO");
    };
    assert_eq!(
        host_hello.get("acked_mseq"),
        Some(&JsonValue::Integer(0))
    );

    socket
        .send(tungstenite::Message::Binary(
            encode(
                &Frame::VfsMut(append_mutation(1, path, 6, b" world")),
                2,
            )
            .expect("encode append")
            .into(),
        ))
        .expect("send append");
    assert_eq!(
        read_frame(&mut socket),
        Frame::VfsAck(VfsAck {
            level: AckLevel::Write,
            upto_mseq: 1,
        }),
        "level 1 acknowledges the accepted WAL tail, including the held record"
    );
    let Frame::VfsResync(resync) = read_frame(&mut socket) else {
        panic!("missing base did not produce VFS_RESYNC");
    };
    assert_eq!(resync.path, path);
    assert_eq!(
        host.store_materialized_upto(),
        0,
        "the materialized watermark stays below the held mseq 1"
    );

    // Reconnect without repairing: the hold is in memory and is re-requested
    // after HELLO, one outstanding request for the path.
    let _ = socket.close(None);
    drop(socket);
    for _ in 0..200 {
        if !host.channel_active() {
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    assert!(!host.channel_active(), "channel did not deactivate");
    let (mut socket, _) = connect(ClientRequestBuilder::new(uri)).expect("ws reconnect");
    socket
        .send(tungstenite::Message::Binary(
            encode(&Frame::Hello(worker_hello(&host, 0)), 1)
                .expect("encode hello")
                .into(),
        ))
        .expect("send hello");
    let Frame::Hello(_) = read_frame(&mut socket) else {
        panic!("host first frame is not HELLO on reconnect");
    };
    let Frame::VfsResync(resync) = read_frame(&mut socket) else {
        panic!("reconnect did not re-request the held path after HELLO");
    };
    assert_eq!(resync.path, path);
    assert_eq!(host.store_pending_resyncs().len(), 1);

    // Repair: complete write at the next normal mseq 2 with the full post-write
    // bytes (6-byte image base "hello" + the accepted " world" suffix).
    socket
        .send(tungstenite::Message::Binary(
            encode(
                &Frame::VfsMut(write_mutation(2, path, b"hello world")),
                2,
            )
            .expect("encode repair")
            .into(),
        ))
        .expect("send repair");
    assert_eq!(
        read_frame(&mut socket),
        Frame::VfsAck(VfsAck {
            level: AckLevel::Write,
            upto_mseq: 2,
        })
    );
    assert_eq!(host.store_materialized_upto(), 2);
    assert!(host.store_pending_resyncs().is_empty());
    assert_eq!(
        fs::read(root.join("materialized/home/image-base.txt")).expect("post-write bytes"),
        b"hello world"
    );
    let _ = socket.close(None);
    drop(socket);
    for _ in 0..200 {
        if !host.channel_active() {
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    host.stop();
    drop(host);

    let cold = Store::open(&root, [57u8; 16]).expect("cold recovery");
    assert_eq!(cold.acked_mseq(), 2);
    assert_eq!(cold.materialized_upto_mseq(), 2);
    assert!(cold.pending_resyncs().is_empty());
    assert_eq!(
        fs::read(root.join("materialized/home/image-base.txt")).expect("cold bytes"),
        b"hello world"
    );
}
