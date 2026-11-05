// SPDX-License-Identifier: MIT
//! The native host: loopback listener, routing, callback bridge and lifecycle.
//!
//! Bind authority is exactly `127.0.0.1:<ephemeral>`. Every route is token
//! gated. Model/provider HTTP routes emit one bounded callback event and stream
//! the caller's `respond` calls back as HTTP/1.1 chunked (SSE included). No
//! request headers, token or prompt text is ever logged.

use std::collections::HashMap;
use std::ffi::CString;
use std::fs;
use std::io::Write;
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{sync_channel, RecvTimeoutError, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::Engine as _;
use serde_json::{json, Value};

use crate::http::{self, query_param, token_matches, Request, StaticRoots, MAX_HEADER_BYTES};
use crate::wal::Store;

const MAX_CONNECTIONS: usize = 96;
const MAX_CHUNK_BYTES: usize = 256 * 1024;
const HEAD_TIMEOUT: Duration = Duration::from_secs(30);
const CHUNK_TIMEOUT: Duration = Duration::from_secs(60);
const REQUEST_DEADLINE: Duration = Duration::from_secs(15 * 60);
const STOP_GRACE: Duration = Duration::from_secs(2);

/// Absolute ceiling for a request id that also crosses the C ABI and is read as
/// a JavaScript number: `Number.MAX_SAFE_INTEGER`. Request ids are a
/// process-lifetime resource, never reset by a host start.
pub(crate) const MAX_HTTP_ID: u64 = 9_007_199_254_740_991;
/// How often the streaming driver may look for a confirmed peer abort while it
/// waits for the next `respond` event.
const ABORT_POLL_INTERVAL: Duration = Duration::from_millis(200);

static NEXT_HTTP_ID: AtomicU64 = AtomicU64::new(1);

/// Allocates the next request id, or `None` once the JS-safe ceiling is spent.
/// The counter stops at `MAX_HTTP_ID + 1`; it never wraps and never repeats.
pub(crate) fn next_http_id_from(counter: &AtomicU64) -> Option<u64> {
    counter
        .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |current| {
            if current > MAX_HTTP_ID {
                None
            } else {
                Some(current + 1)
            }
        })
        .ok()
}

/// The non-secret file `HostService` writes under the runtime root before
/// `native.start`. The Worker projection is derived only from this allowlist.
pub const MODEL_LAUNCH_FILE: &str = "model-launch.json";
pub const MODEL_LAUNCH_VERSION: u64 = 1;
const MAX_MODEL_LAUNCH_BYTES: u64 = 256 * 1024;
const MODEL_PROVIDER_ENV: &str = "PDSH_MODEL_PROVIDER_JSON";
const MODEL_NAME_ENV: &str = "PDSH_MODEL_NAME";

/// One public model definition. Optional fields mirror
/// `plugins/phone-model-config` version 1 exactly.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModelLaunchModel {
    pub id: String,
    pub name: Option<String>,
    pub context_window: Option<u64>,
    pub max_tokens: Option<u64>,
    pub text_input: bool,
    pub image_input: bool,
    pub reasoning_effort: Option<String>,
}

/// One public provider row. Encoder, credentialRef and key state are host-only
/// and are rejected by omission: this parser never reads them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModelLaunchProvider {
    pub id: String,
    pub display_name: String,
    pub base_url: String,
    pub model: String,
    pub models: Vec<ModelLaunchModel>,
    pub reasoning_effort: Option<String>,
}

/// The typed public document the host publishes to the Worker.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModelLaunch {
    pub selected_id: String,
    pub providers: Vec<ModelLaunchProvider>,
}

impl ModelLaunch {
    /// The selected provider's model id, if the document is internally valid.
    pub fn selected_model(&self) -> Option<&str> {
        self.providers
            .iter()
            .find(|provider| provider.id == self.selected_id)
            .map(|provider| provider.model.as_str())
    }

    /// Re-serialize only the allowlisted fields. Unknown input keys, a leaked
    /// key value or an arbitrary env entry can never survive this projection.
    pub fn document_json(&self) -> String {
        let providers: Vec<Value> = self
            .providers
            .iter()
            .map(|provider| {
                let mut row = serde_json::Map::new();
                row.insert("id".to_owned(), json!(provider.id));
                row.insert("displayName".to_owned(), json!(provider.display_name));
                row.insert("baseURL".to_owned(), json!(provider.base_url));
                row.insert("model".to_owned(), json!(provider.model));
                if !provider.models.is_empty() {
                    let models: Vec<Value> = provider.models.iter().map(model_json).collect();
                    row.insert("models".to_owned(), Value::Array(models));
                }
                if let Some(effort) = &provider.reasoning_effort {
                    row.insert("reasoningEffort".to_owned(), json!(effort));
                }
                Value::Object(row)
            })
            .collect();
        let mut document = serde_json::Map::new();
        document.insert("version".to_owned(), json!(MODEL_LAUNCH_VERSION));
        document.insert("selectedId".to_owned(), json!(self.selected_id));
        document.insert("providers".to_owned(), Value::Array(providers));
        Value::Object(document).to_string()
    }
}

fn model_json(model: &ModelLaunchModel) -> Value {
    let mut row = serde_json::Map::new();
    row.insert("id".to_owned(), json!(model.id));
    if let Some(name) = &model.name {
        row.insert("name".to_owned(), json!(name));
    }
    if let Some(context_window) = model.context_window {
        row.insert("contextWindow".to_owned(), json!(context_window));
    }
    if let Some(max_tokens) = model.max_tokens {
        row.insert("maxTokens".to_owned(), json!(max_tokens));
    }
    row.insert("textInput".to_owned(), json!(model.text_input));
    row.insert("imageInput".to_owned(), json!(model.image_input));
    if let Some(effort) = &model.reasoning_effort {
        row.insert("reasoningEffort".to_owned(), json!(effort));
    }
    Value::Object(row)
}

fn non_empty_string(value: Option<&Value>) -> Option<String> {
    let text = value?.as_str()?.trim();
    if text.is_empty() {
        None
    } else {
        Some(text.to_owned())
    }
}

fn optional_string(value: Option<&Value>, allow_empty: bool) -> Option<Option<String>> {
    match value {
        None | Some(Value::Null) => Some(None),
        Some(Value::String(text)) => {
            let trimmed = text.trim();
            if trimmed.is_empty() {
                // A model name may not be blank; an optional effort may be omitted.
                if allow_empty {
                    Some(None)
                } else {
                    None
                }
            } else {
                Some(Some(trimmed.to_owned()))
            }
        }
        _ => None,
    }
}

fn optional_positive_integer(value: Option<&Value>) -> Option<Option<u64>> {
    match value {
        None | Some(Value::Null) => Some(None),
        Some(Value::Number(number)) => {
            let parsed = number.as_u64()?;
            if parsed == 0 {
                None
            } else {
                Some(Some(parsed))
            }
        }
        _ => None,
    }
}

fn profile_id_valid(id: &str) -> bool {
    !id.is_empty()
        && id
            .chars()
            .all(|ch| !ch.is_whitespace() && !ch.is_control())
}

fn base_url_valid(raw: &str) -> bool {
    let text = raw.trim();
    if text.is_empty() || text.chars().any(char::is_whitespace) {
        return false;
    }
    let lower = text.to_ascii_lowercase();
    let rest = if let Some(rest) = lower.strip_prefix("https://") {
        rest
    } else if let Some(rest) = lower.strip_prefix("http://") {
        rest
    } else {
        return false;
    };
    if lower.contains('?') || lower.contains('#') {
        return false;
    }
    let authority = rest.split('/').next().unwrap_or("");
    if authority.is_empty()
        || authority.contains('@')
        || authority.contains('\\')
        || authority.contains('[')
        || authority.contains(']')
    {
        return false;
    }
    let host = authority.split(':').next().unwrap_or("");
    if host.is_empty() {
        return false;
    }
    if let Some(port_text) = authority.split_once(':').map(|(_, port)| port) {
        if port_text.is_empty() || !port_text.chars().all(|ch| ch.is_ascii_digit()) {
            return false;
        }
        if port_text.parse::<u32>().map(|port| port == 0 || port > 65535).unwrap_or(true) {
            return false;
        }
    }
    true
}

fn parse_model(value: &Value) -> Option<ModelLaunchModel> {
    let row = value.as_object()?;
    let id = non_empty_string(row.get("id"))?;
    let name = optional_string(row.get("name"), false)?;
    let context_window = optional_positive_integer(row.get("contextWindow"))?;
    let max_tokens = optional_positive_integer(row.get("maxTokens"))?;
    let text_input = match row.get("textInput") {
        None => true,
        Some(Value::Bool(value)) => *value,
        _ => return None,
    };
    let image_input = match row.get("imageInput") {
        None => false,
        Some(Value::Bool(value)) => *value,
        _ => return None,
    };
    if !text_input && !image_input {
        return None;
    }
    let reasoning_effort = optional_string(row.get("reasoningEffort"), true)?;
    Some(ModelLaunchModel {
        id,
        name,
        context_window,
        max_tokens,
        text_input,
        image_input,
        reasoning_effort,
    })
}

fn parse_provider(value: &Value) -> Option<ModelLaunchProvider> {
    let row = value.as_object()?;
    let id = non_empty_string(row.get("id"))?;
    if !profile_id_valid(&id) {
        return None;
    }
    let display_name = non_empty_string(row.get("displayName"))?;
    let base_url = non_empty_string(row.get("baseURL"))?;
    if !base_url_valid(&base_url) {
        return None;
    }
    let model = non_empty_string(row.get("model"))?;
    let reasoning_effort = optional_string(row.get("reasoningEffort"), true)?;
    let models = match row.get("models") {
        None | Some(Value::Null) => Vec::new(),
        Some(Value::Array(entries)) if !entries.is_empty() => entries
            .iter()
            .map(parse_model)
            .collect::<Option<Vec<ModelLaunchModel>>>()?,
        _ => return None,
    };
    Some(ModelLaunchProvider {
        id,
        display_name,
        base_url,
        model,
        models,
        reasoning_effort,
    })
}

/// Parse the explicit public provider document. Missing, oversized, malformed
/// or internally inconsistent input is an honest `None`, never a fabricated
/// provider or a fallback env map.
pub fn parse_model_launch(text: &str) -> Option<ModelLaunch> {
    if text.len() as u64 > MAX_MODEL_LAUNCH_BYTES {
        return None;
    }
    let root: Value = serde_json::from_str(text).ok()?;
    let row = root.as_object()?;
    if row.get("version")?.as_u64()? != MODEL_LAUNCH_VERSION {
        return None;
    }
    let selected_id = non_empty_string(row.get("selectedId"))?;
    let entries = row.get("providers")?.as_array()?;
    if entries.is_empty() {
        return None;
    }
    let mut providers = Vec::with_capacity(entries.len());
    for entry in entries {
        let provider = parse_provider(entry)?;
        if providers
            .iter()
            .any(|seen: &ModelLaunchProvider| seen.id == provider.id)
        {
            return None;
        }
        providers.push(provider);
    }
    if !providers.iter().any(|provider| provider.id == selected_id) {
        return None;
    }
    Some(ModelLaunch {
        selected_id,
        providers,
    })
}

/// Read and parse `<root>/model-launch.json`. A missing file is not an error.
pub fn read_model_launch(root: &Path) -> Option<ModelLaunch> {
    let path = root.join(MODEL_LAUNCH_FILE);
    let metadata = fs::metadata(&path).ok()?;
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_MODEL_LAUNCH_BYTES {
        return None;
    }
    let text = fs::read_to_string(path).ok()?;
    parse_model_launch(&text)
}

/// The event callback the NAPI layer installs.
pub type EventCallback = extern "C" fn(*const std::os::raw::c_char, *mut std::os::raw::c_void);

static CALLBACK: Mutex<Option<(EventCallback, usize)>> = Mutex::new(None);

/// Installs (or clears) the single event callback.
pub fn set_callback(callback: Option<EventCallback>, userdata: *mut std::os::raw::c_void) {
    let mut slot = lock(&CALLBACK);
    *slot = callback.map(|callback| (callback, userdata as usize));
}

fn invoke_callback(json_text: &str) -> bool {
    let (callback, userdata) = {
        let slot = lock(&CALLBACK);
        match *slot {
            Some((callback, userdata)) => (callback, userdata),
            None => return false,
        }
    };
    let Ok(text) = CString::new(json_text) else {
        return false;
    };
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        callback(text.as_ptr(), userdata as *mut std::os::raw::c_void);
    }));
    result.is_ok()
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// A confirmed TCP failure, not a normal write-half-close. POLLERR is always
/// fatal; POLLHUP is only treated as fatal when no readable FIN is also pending,
/// so a request peer that only did `shutdownWrite` keeps receiving the response.
#[cfg(unix)]
fn peer_aborted(socket: &TcpStream) -> bool {
    use std::os::fd::AsRawFd;
    let fd = socket.as_raw_fd();
    let mut descriptor = libc::pollfd {
        fd,
        events: libc::POLLIN,
        revents: 0,
    };
    let ready = unsafe { libc::poll(&mut descriptor, 1, 0) };
    if ready <= 0 {
        return false;
    }
    if descriptor.revents & libc::POLLERR != 0 {
        return true;
    }
    if descriptor.revents & libc::POLLHUP != 0 && descriptor.revents & libc::POLLIN == 0 {
        return true;
    }
    if descriptor.revents & libc::POLLIN == 0 {
        return false;
    }
    // A readable event can be either a normal FIN or a reset. Ask the socket
    // for its pending error and peek one byte without consuming it: ECONNRESET
    // is a confirmed abort, while a clean EOF stays a normal half-close.
    let mut pending_error: libc::c_int = 0;
    let mut length = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
    let read_error = unsafe {
        libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_ERROR,
            &mut pending_error as *mut libc::c_int as *mut libc::c_void,
            &mut length,
        )
    };
    if read_error == 0 && pending_error != 0 {
        return true;
    }
    let mut byte = 0u8;
    let peeked = unsafe {
        libc::recv(
            fd,
            &mut byte as *mut u8 as *mut libc::c_void,
            1,
            libc::MSG_PEEK | libc::MSG_DONTWAIT,
        )
    };
    if peeked < 0 {
        let error = std::io::Error::last_os_error()
            .raw_os_error()
            .unwrap_or_default();
        return error == libc::ECONNRESET || error == libc::ENOTCONN || error == libc::EPIPE;
    }
    false
}

#[cfg(not(unix))]
fn peer_aborted(_: &TcpStream) -> bool {
    false
}

/// One response event travelling from `pdsh_host_respond` to the HTTP writer.
enum RespondEvent {
    Head {
        status: i32,
        headers: Vec<(String, String)>,
    },
    Chunk(Vec<u8>),
    End,
    Error {
        status: i32,
        message: String,
    },
    Cancelled,
}

struct Pending {
    tx: SyncSender<RespondEvent>,
    socket: Option<TcpStream>,
    generation: u64,
    head_seen: bool,
    ended: bool,
}

impl Pending {
    fn deliver(&self, event: RespondEvent) -> Result<(), String> {
        match self.tx.try_send(event) {
            Ok(()) => Ok(()),
            Err(TrySendError::Full(_)) => {
                if let Some(socket) = &self.socket {
                    let _ = socket.shutdown(Shutdown::Both);
                }
                Err("response queue is full".to_owned())
            }
            Err(TrySendError::Disconnected(_)) => Err("response consumer is gone".to_owned()),
        }
    }

    fn abort_socket(&self) {
        if let Some(socket) = &self.socket {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }
}

/// Runtime information returned by `start` and `/health`.
#[derive(Clone, Debug)]
pub struct StartInfo {
    pub port: u16,
    pub token: String,
    pub boot_id: String,
    pub session_epoch: u64,
    pub acked_mseq: u64,
    pub recovered_bytes: u64,
}

impl StartInfo {
    pub fn json(&self, error: Option<&str>) -> Value {
        let mut map = serde_json::Map::new();
        map.insert("port".into(), json!(self.port));
        map.insert("token".into(), json!(self.token));
        map.insert("bootId".into(), json!(self.boot_id));
        map.insert("sessionEpoch".into(), json!(self.session_epoch));
        map.insert("ackedMseq".into(), json!(self.acked_mseq));
        map.insert("recoveredBytes".into(), json!(self.recovered_bytes));
        if let Some(error) = error {
            map.insert("error".into(), json!(bounded_text(error, 400)));
        }
        Value::Object(map)
    }
}

/// The live host. Fields are shared by the accept loop and the FFI callers.
pub struct Host {
    pub info: StartInfo,
    origin: String,
    root: PathBuf,
    static_roots: StaticRoots,
    store: Mutex<Store>,
    // The last ACK observed *after* a successful Store commit, readable without
    // the Store/filesystem lock. Initialized from the recovered Store ACK.
    acked_mseq: AtomicU64,
    stop: AtomicBool,
    next_connection_id: AtomicU64,
    pending: Mutex<HashMap<u64, Pending>>,
    sockets: Mutex<HashMap<u64, TcpStream>>,
    connections: AtomicUsize,
    channel_active: AtomicBool,
    started: Instant,
    last_error: Mutex<Option<String>>,
    console_bytes: AtomicU64,
    last_console_level: Mutex<Option<String>>,
    accept_thread: Mutex<Option<JoinHandle<()>>>,
    connection_threads: Mutex<Vec<JoinHandle<()>>>,
    generation: u64,
}

impl std::fmt::Debug for Host {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Host")
            .field("info", &self.info)
            .field("origin", &self.origin)
            .field(
                "channel_active",
                &self.channel_active.load(Ordering::Relaxed),
            )
            .finish()
    }
}

impl Host {
    /// Binds the loopback origin and starts the accept loop.
    pub fn start(root: &Path, assets: &Path) -> Result<Arc<Host>, String> {
        let boot_id = random_bytes(16)?;
        let token = hex(&random_bytes(16)?);
        let session_epoch = unix_millis().max(1);
        let store = Store::open(root, to_boot_id(&boot_id))?;
        let acked_mseq = store.acked_mseq();
        let recovered_bytes = store.recovered_bytes();
        let static_roots = StaticRoots::new(assets)?;
        let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
            .map_err(|error| format!("cannot bind 127.0.0.1:0: {error}"))?;
        listener
            .set_nonblocking(true)
            .map_err(|error| format!("cannot set listener nonblocking: {error}"))?;
        let port = listener
            .local_addr()
            .map_err(|error| format!("cannot read listener address: {error}"))?
            .port();
        let boot_hex = hex(&boot_id);
        let info = StartInfo {
            port,
            token,
            boot_id: boot_hex,
            session_epoch,
            acked_mseq,
            recovered_bytes,
        };
        let host = Arc::new(Host {
            origin: format!("http://127.0.0.1:{port}"),
            root: root.to_path_buf(),
            static_roots,
            store: Mutex::new(store),
            acked_mseq: AtomicU64::new(acked_mseq),
            stop: AtomicBool::new(false),
            next_connection_id: AtomicU64::new(1),
            pending: Mutex::new(HashMap::new()),
            sockets: Mutex::new(HashMap::new()),
            connections: AtomicUsize::new(0),
            channel_active: AtomicBool::new(false),
            started: Instant::now(),
            last_error: Mutex::new(None),
            console_bytes: AtomicU64::new(0),
            last_console_level: Mutex::new(None),
            accept_thread: Mutex::new(None),
            connection_threads: Mutex::new(Vec::new()),
            generation: session_epoch,
            info,
        });
        let host_clone = Arc::clone(&host);
        let handle = thread::Builder::new()
            .name("pdsh-host-accept".to_owned())
            .spawn(move || accept_loop(host_clone, listener))
            .map_err(|error| format!("cannot spawn accept thread: {error}"))?;
        *lock(&host.accept_thread) = Some(handle);
        Ok(host)
    }

    pub fn origin(&self) -> &str {
        &self.origin
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn token(&self) -> &str {
        &self.info.token
    }

    pub fn channel_active(&self) -> bool {
        self.channel_active.load(Ordering::Relaxed)
    }

    pub fn boot_config(&self) -> Value {
        let (acked, next) = {
            let store = lock(&self.store);
            (store.acked_mseq(), store.next_mseq())
        };
        let token = &self.info.token;
        // The Worker's model projection is the selected saved profile, read
        // from the host-written allowlisted document before boot. Only these two
        // names are derived; no arbitrary environment key ever crosses.
        let model_launch = read_model_launch(&self.root);
        let mut env = serde_json::Map::new();
        env.insert("PDSH_DSH_LAUNCH_TOKEN".to_owned(), json!(token));
        env.insert(
            "DEEPSEEK_API_KEY".to_owned(),
            json!("host-managed-placeholder"),
        );
        env.insert("PDSH_MODEL_ROUTE".to_owned(), json!("host-edge"));
        // Model traffic still goes to the native loopback edge; the public
        // document carries the upstream baseURL without being the traffic env.
        env.insert("DEEPSEEK_BASE_URL".to_owned(), json!(self.origin));
        env.insert("DSH_TELEMETRY_DISABLED".to_owned(), json!("1"));
        if let Some(launch) = model_launch {
            if let Some(model) = launch.selected_model() {
                env.insert(MODEL_NAME_ENV.to_owned(), json!(model));
            }
            env.insert(
                MODEL_PROVIDER_ENV.to_owned(),
                json!(launch.document_json()),
            );
        }
        json!({
            "imageURL": format!("/assets/image.tar.gz?token={token}"),
            "overlays": [format!("/overlay.tar.gz?token={token}")],
            "xl": {
                "channelURL": format!("ws://127.0.0.1:{}/channel?token={token}", self.info.port),
                "token": token,
                "bootId": self.info.boot_id,
                "sessionEpoch": self.info.session_epoch,
                "fence": {
                    "owner": "pdsh-host",
                    "generation": self.info.session_epoch,
                    "wal_seq": acked
                },
                "ackedMseq": acked,
                "nextMseq": next,
                "env": Value::Object(env)
            }
        })
    }

    pub fn health_json(&self) -> Value {
        let (acked, recovered, truncated, segments, journals, failed) = {
            let store = lock(&self.store);
            (
                store.acked_mseq(),
                store.recovered_bytes(),
                store.truncated_tail_bytes(),
                store.segment_count(),
                store.journal_count(),
                store.failed().map(|failure| failure.to_owned()),
            )
        };
        json!({
            "ok": failed.is_none(),
            "running": true,
            "port": self.info.port,
            "bootId": self.info.boot_id,
            "sessionEpoch": self.info.session_epoch,
            "ackedMseq": acked,
            "recoveredBytes": recovered,
            "truncatedTailBytes": truncated,
            "walSegments": segments,
            "journalSessions": journals,
            "channelActive": self.channel_active(),
            "pendingHttp": lock(&self.pending).len(),
            "consoleBytes": self.console_bytes.load(Ordering::Relaxed),
            "lastConsoleLevel": lock(&self.last_console_level).clone(),
            "lastError": lock(&self.last_error).clone(),
            "uptimeMs": self.started.elapsed().as_millis() as u64,
            "version": env!("CARGO_PKG_VERSION"),
            "error": failed
        })
    }

    /// Stops listening, fails pending HTTP responses and joins bounded.
    pub fn stop(&self) {
        if self.stop.swap(true, Ordering::SeqCst) {
            return;
        }
        {
            let mut pending = lock(&self.pending);
            let cancelled: Vec<u64> = pending
                .drain()
                .map(|(id, entry)| {
                    let _ = entry.tx.try_send(RespondEvent::Cancelled);
                    entry.abort_socket();
                    id
                })
                .collect();
            drop(pending);
            for id in cancelled {
                self.emit_http_cancel(id);
            }
        }
        {
            let sockets = lock(&self.sockets);
            for socket in sockets.values() {
                let _ = socket.shutdown(Shutdown::Both);
            }
        }
        if let Some(handle) = lock(&self.accept_thread).take() {
            join_bounded(vec![handle], Duration::from_millis(750));
        }
        {
            let mut pending = lock(&self.pending);
            let cancelled: Vec<u64> = pending
                .drain()
                .map(|(id, entry)| {
                    let _ = entry.tx.try_send(RespondEvent::Cancelled);
                    entry.abort_socket();
                    id
                })
                .collect();
            drop(pending);
            for id in cancelled {
                self.emit_http_cancel(id);
            }
        }
        {
            let mut sockets = lock(&self.sockets);
            for socket in sockets.values() {
                let _ = socket.shutdown(Shutdown::Both);
            }
            sockets.clear();
        }
        let handles = std::mem::take(&mut *lock(&self.connection_threads));
        join_bounded(handles, STOP_GRACE);
    }

    // ------------------------------------------------------------- routing

    fn handle_connection(self: Arc<Self>, stream: TcpStream) {
        let live = self.connections.fetch_add(1, Ordering::SeqCst) + 1;
        if live > MAX_CONNECTIONS {
            self.connections.fetch_sub(1, Ordering::SeqCst);
            return;
        }
        let id = self.next_connection_id.fetch_add(1, Ordering::SeqCst);
        if let Ok(clone) = stream.try_clone() {
            lock(&self.sockets).insert(id, clone);
        }
        let _guard = ConnectionGuard {
            host: Arc::clone(&self),
            id,
        };
        let mut stream = stream;
        if looks_like_websocket(&stream) {
            crate::ws::serve(Arc::clone(&self), stream);
        } else {
            http::set_timeouts(&stream);
            match http::read_request(&mut stream) {
                Ok(Some(request)) => self.route(&mut stream, request),
                Ok(None) => {}
                Err(error) => {
                    let body = json!({
                        "error": error.code,
                        "message": error.message,
                        "schema": "pdsh-host-error/1"
                    });
                    http::write_json(&mut stream, error.status, &body);
                }
            }
        }
    }

    fn route(&self, stream: &mut TcpStream, request: Request) {
        if !http::host_matches(&request, self.info.port) {
            http::write_json(
                stream,
                403,
                &json!({"error":"forbidden_authority","schema":"pdsh-host-error/1"}),
            );
            return;
        }
        if !self.authorized(&request) {
            http::write_json(
                stream,
                401,
                &json!({"error":"unauthorized","schema":"pdsh-host-error/1"}),
            );
            return;
        }

        let path = request.path.as_str();
        if request.method == "GET" && path == "/" {
            let extra = cookie_headers(&self.info.token, "/app/index.html");
            http::write_empty(stream, 303, &extra);
            return;
        }
        if request.method == "GET"
            && (path == "/app" || path == "/app/" || path == "/app/index.html")
            && self.serve_app_launch(stream, &request, "index.html")
        {
            return;
        }
        if request.method == "GET" && path.starts_with("/app/") {
            let relative = &path[4..];
            if self.serve_static(stream, true, relative, None) {
                return;
            }
            http::write_json(stream, 404, &json!({"error":"not_found"}));
            return;
        }
        if request.method == "GET" && path.starts_with("/assets/") {
            let relative = &path[7..];
            if self.serve_static(stream, false, relative, None) {
                return;
            }
            http::write_json(stream, 404, &json!({"error":"not_found"}));
            return;
        }
        if path == "/boot-config" {
            if request.method != "GET" {
                http::write_json(stream, 405, &json!({"error":"method_not_allowed"}));
                return;
            }
            http::write_json(stream, 200, &self.boot_config());
            return;
        }
        if path == "/health" {
            if request.method != "GET" {
                http::write_json(stream, 405, &json!({"error":"method_not_allowed"}));
                return;
            }
            http::write_json(stream, 200, &self.health_json());
            return;
        }
        if path == "/overlay.tar.gz" {
            if request.method != "GET" {
                http::write_json(stream, 405, &json!({"error":"method_not_allowed"}));
                return;
            }
            let packed = {
                let store = lock(&self.store);
                store.pack_overlay()
            };
            match packed {
                Ok(bytes) => {
                    http::write_response(stream, 200, "application/gzip", &bytes, &[]);
                }
                Err(error) => {
                    self.note_error("overlay_pack_failed");
                    http::write_json(
                        stream,
                        500,
                        &json!({
                            "error": "overlay_pack_failed",
                            "message": bounded_text(&error, 300)
                        }),
                    );
                }
            }
            return;
        }
        if path == "/channel" {
            http::write_json(stream, 426, &json!({"error":"websocket_upgrade_required"}));
            return;
        }
        if is_delegated_route(&request) {
            self.delegate_http(stream, &request);
            return;
        }
        http::write_json(stream, 404, &json!({"error":"not_found"}));
    }

    fn authorized(&self, request: &Request) -> bool {
        let query = query_param(&request.query, "token");
        let bearer = http::bearer(request);
        let cookie = http::launch_cookie(request);
        token_matches(query, &self.info.token)
            || token_matches(bearer, &self.info.token)
            || token_matches(cookie, &self.info.token)
    }

    fn serve_app_launch(&self, stream: &mut TcpStream, request: &Request, index: &str) -> bool {
        let has_query_token = query_param(&request.query, "token")
            .map(|value| token_matches(Some(value), &self.info.token))
            .unwrap_or(false);
        if has_query_token {
            let location = request.path.split('?').next().unwrap_or("/app/index.html");
            let extra = cookie_headers(&self.info.token, location);
            http::write_empty(stream, 303, &extra);
            return true;
        }
        self.serve_static(stream, true, &format!("/{index}"), Some(&self.info.token))
    }

    fn serve_static(
        &self,
        stream: &mut TcpStream,
        app: bool,
        relative: &str,
        seed_cookie: Option<&str>,
    ) -> bool {
        let root = if app {
            self.static_roots.app.as_deref()
        } else {
            self.static_roots.worker.as_deref()
        };
        let Some(root) = root else {
            return false;
        };
        let mut extra: Vec<(String, String)> = Vec::new();
        if let Some(token) = seed_cookie {
            if relative.ends_with(".html") || relative.is_empty() || relative == "/" {
                extra.push((
                    "Set-Cookie".to_owned(),
                    format!("xl_launch_token={token}; Path=/; HttpOnly; SameSite=Strict"),
                ));
            }
        }
        http::serve_static(stream, root, relative, &extra)
    }

    fn delegate_http(&self, stream: &mut TcpStream, request: &Request) {
        let Some(id) = next_http_id_from(&NEXT_HTTP_ID) else {
            self.note_error("http request id space exhausted");
            http::write_json(stream, 503, &json!({"error":"http_id_exhausted"}));
            return;
        };
        let (tx, rx) = sync_channel(32);
        {
            let mut pending = lock(&self.pending);
            pending.insert(
                id,
                Pending {
                    tx,
                    socket: stream.try_clone().ok(),
                    generation: self.generation,
                    head_seen: false,
                    ended: false,
                },
            );
        }
        if self.stop.load(Ordering::SeqCst) {
            lock(&self.pending).remove(&id);
            http::write_json(stream, 503, &json!({"error":"host_stopping"}));
            return;
        }
        let body_base64 = base64::engine::general_purpose::STANDARD.encode(&request.body);
        let event = json!({
            "type": "http",
            "id": id,
            "method": request.method,
            "path": strip_query_token(&request.target),
            "headers": request.headers_object(),
            "bodyBase64": body_base64,
            "generation": self.generation
        });
        let encoded = match serde_json::to_string(&event) {
            Ok(encoded) => encoded,
            Err(_) => {
                lock(&self.pending).remove(&id);
                http::write_json(stream, 500, &json!({"error":"callback_encode_failed"}));
                return;
            }
        };
        if !invoke_callback(&encoded) {
            lock(&self.pending).remove(&id);
            http::write_json(
                stream,
                503,
                &json!({
                    "error":"host_callback_unavailable",
                    "message":"the ArkTS proxy callback is not installed; refusing to fake a provider response"
                }),
            );
            return;
        }

        let monitor = stream.try_clone().ok();
        let started = Instant::now();
        let mut head_sent = false;
        let mut chunked = true;
        loop {
            if self.stop.load(Ordering::SeqCst) {
                if lock(&self.pending).remove(&id).is_some() {
                    self.emit_http_cancel(id);
                }
                if !head_sent {
                    http::write_json(stream, 503, &json!({"error":"host_stopping"}));
                }
                return;
            }
            let timeout = if head_sent {
                CHUNK_TIMEOUT
            } else {
                HEAD_TIMEOUT
            };
            let remaining = REQUEST_DEADLINE.saturating_sub(started.elapsed());
            let wait = timeout.min(remaining);
            let deadline = Instant::now() + wait;
            let mut received: Option<Result<RespondEvent, RecvTimeoutError>> = None;
            let mut client_aborted = false;
            let mut host_stopped = false;
            let mut timed_out = false;
            // Wait for the next respond event, but at a bounded interval check
            // for a confirmed peer abort. A normal write-half-close (FIN) is
            // not an abort: the client still reads the streamed response.
            loop {
                if self.stop.load(Ordering::SeqCst) {
                    host_stopped = true;
                    break;
                }
                if monitor.as_ref().map(peer_aborted).unwrap_or(false) {
                    client_aborted = true;
                    break;
                }
                let now = Instant::now();
                if now >= deadline {
                    timed_out = true;
                    break;
                }
                let tick = (deadline - now).min(ABORT_POLL_INTERVAL);
                match rx.recv_timeout(tick) {
                    Ok(event) => {
                        received = Some(Ok(event));
                        break;
                    }
                    Err(RecvTimeoutError::Timeout) => continue,
                    Err(error) => {
                        received = Some(Err(error));
                        break;
                    }
                }
            }
            if host_stopped {
                if lock(&self.pending).remove(&id).is_some() {
                    self.emit_http_cancel(id);
                }
                if !head_sent {
                    http::write_json(stream, 503, &json!({"error":"host_stopping"}));
                }
                return;
            }
            if client_aborted {
                lock(&self.pending).remove(&id);
                self.emit_http_cancel(id);
                return;
            }
            if timed_out {
                lock(&self.pending).remove(&id);
                self.emit_http_cancel(id);
                if !head_sent {
                    http::write_json(
                        stream,
                        504,
                        &json!({"error":"proxy_timeout","message":"the ArkTS proxy did not respond"}),
                    );
                } else {
                    self.note_error("response chunk timeout");
                    let _ = stream.shutdown(Shutdown::Both);
                }
                return;
            }
            let Some(received) = received else {
                lock(&self.pending).remove(&id);
                return;
            };
            match received {
                Ok(RespondEvent::Head {
                    status: reported,
                    headers,
                }) => {
                    if head_sent {
                        continue;
                    }
                    if !(200..=599).contains(&reported) {
                        lock(&self.pending).remove(&id);
                        http::write_json(stream, 502, &json!({"error":"invalid_upstream_status"}));
                        return;
                    }
                    let status = reported;
                    head_sent = true;
                    chunked = !matches!(status, 204 | 304);
                    if let Err(error) = write_stream_head(stream, status, &headers, chunked) {
                        lock(&self.pending).remove(&id);
                        self.emit_http_cancel(id);
                        self.note_error(&format!("response head write failed: {error}"));
                        return;
                    }
                }
                Ok(RespondEvent::Chunk(data)) => {
                    if !head_sent {
                        lock(&self.pending).remove(&id);
                        http::write_json(stream, 502, &json!({"error":"chunk_before_head"}));
                        return;
                    }
                    if chunked {
                        if let Err(error) = write_chunk(stream, &data) {
                            lock(&self.pending).remove(&id);
                            self.emit_http_cancel(id);
                            self.note_error(&format!("response chunk write failed: {error}"));
                            return;
                        }
                    }
                }
                Ok(RespondEvent::End) => {
                    if !head_sent {
                        lock(&self.pending).remove(&id);
                        http::write_json(stream, 502, &json!({"error":"end_before_head"}));
                        return;
                    }
                    if chunked {
                        let _ = stream.write_all(b"0\r\n\r\n");
                        let _ = stream.flush();
                    }
                    return;
                }
                Ok(RespondEvent::Error { status, message }) => {
                    lock(&self.pending).remove(&id);
                    if !head_sent {
                        let status = if (400..=599).contains(&status) {
                            status
                        } else {
                            502
                        };
                        http::write_json(
                            stream,
                            status as u16,
                            &json!({
                                "error": "upstream_proxy_error",
                                "message": bounded_text(&message, 400)
                            }),
                        );
                    } else {
                        self.note_error("response failed after head");
                        let _ = stream.shutdown(Shutdown::Both);
                    }
                    return;
                }
                Ok(RespondEvent::Cancelled) => {
                    lock(&self.pending).remove(&id);
                    if !head_sent {
                        http::write_json(stream, 503, &json!({"error":"host_stopping"}));
                    }
                    return;
                }
                Err(RecvTimeoutError::Timeout) => {
                    lock(&self.pending).remove(&id);
                    self.emit_http_cancel(id);
                    if !head_sent {
                        http::write_json(
                            stream,
                            504,
                            &json!({"error":"proxy_timeout","message":"the ArkTS proxy did not respond"}),
                        );
                    } else {
                        self.note_error("response chunk timeout");
                        let _ = stream.shutdown(Shutdown::Both);
                    }
                    return;
                }
                Err(RecvTimeoutError::Disconnected) => {
                    lock(&self.pending).remove(&id);
                    if !head_sent {
                        http::write_json(stream, 502, &json!({"error":"proxy_channel_closed"}));
                    }
                    return;
                }
            }
        }
    }

    // ----------------------------------------------------------- responses

    /// Called by the FFI `pdsh_host_respond`.
    pub fn respond(
        &self,
        id: u64,
        kind: i32,
        status: i32,
        headers: Option<&str>,
        data: &[u8],
    ) -> Result<(), String> {
        let mut entry = lock(&self.pending)
            .remove(&id)
            .ok_or_else(|| "unknown or completed request id".to_owned())?;
        if entry.generation != self.generation {
            return Err("request belongs to an earlier host generation".to_owned());
        }
        if entry.ended {
            return Err("response already ended".to_owned());
        }
        match kind {
            0 => {
                if entry.head_seen {
                    entry.abort_socket();
                    return Err("duplicate response head".to_owned());
                }
                let parsed = match parse_response_headers(headers.unwrap_or("{}")) {
                    Ok(parsed) => parsed,
                    Err(error) => {
                        let _ = entry.deliver(RespondEvent::Error {
                            status: 502,
                            message: error.clone(),
                        });
                        return Err(error);
                    }
                };
                if !(200..=599).contains(&status) {
                    let _ = entry.deliver(RespondEvent::Error {
                        status: 502,
                        message: "response status outside 200..599".to_owned(),
                    });
                    return Err("response status outside 200..599".to_owned());
                }
                entry.head_seen = true;
                entry
                    .deliver(RespondEvent::Head {
                        status,
                        headers: parsed,
                    })
                    .inspect_err(|_| {
                        entry.abort_socket();
                    })?;
                lock(&self.pending).insert(id, entry);
                Ok(())
            }
            1 => {
                if !entry.head_seen {
                    lock(&self.pending).insert(id, entry);
                    return Err("chunk before response head".to_owned());
                }
                if data.len() > MAX_CHUNK_BYTES {
                    entry.abort_socket();
                    return Err("response chunk exceeds 256 KiB".to_owned());
                }
                if let Err(error) = entry.deliver(RespondEvent::Chunk(data.to_vec())) {
                    entry.abort_socket();
                    return Err(error);
                }
                lock(&self.pending).insert(id, entry);
                Ok(())
            }
            2 => {
                if !entry.head_seen {
                    entry.abort_socket();
                    return Err("end before response head".to_owned());
                }
                entry.ended = true;
                if let Err(error) = entry.deliver(RespondEvent::End) {
                    entry.abort_socket();
                    return Err(error);
                }
                Ok(())
            }
            3 => {
                let message = String::from_utf8_lossy(data).into_owned();
                if let Err(error) = entry.deliver(RespondEvent::Error { status, message }) {
                    entry.abort_socket();
                    return Err(error);
                }
                Ok(())
            }
            other => {
                lock(&self.pending).insert(id, entry);
                Err(format!("unknown response kind {other}"))
            }
        }
    }

    /// Tells the paired ArkTS proxy that this native HTTP request is over
    /// before an upstream response. This is a local callback, not a P01 wire
    /// frame, and it carries no request headers, body, token or credentials.
    fn emit_http_cancel(&self, id: u64) {
        let event = json!({
            "type": "http-cancel",
            "id": id,
            "generation": self.generation,
        });
        if let Ok(encoded) = serde_json::to_string(&event) {
            let _ = invoke_callback(&encoded);
        }
    }

    fn note_error(&self, message: &str) {
        *lock(&self.last_error) = Some(bounded_text(message, 300));
    }

    // --------------------------------------------------------- channel hooks

    pub(crate) fn store_commit(
        &self,
        mutation: &pdsh_wire::VfsMutation,
    ) -> Result<crate::wal::CommitOutcome, String> {
        let mut store = lock(&self.store);
        let outcome = store.commit_mutation(mutation)?;
        // Publish the accepted level-1 tail only after the append/fsync was
        // accepted; a held materialization still counts as accepted (`wal.md`
        // §5 step 3) and is tracked separately by the materialized watermark.
        self.acked_mseq.store(store.acked_mseq(), Ordering::Release);
        Ok(outcome)
    }

    pub(crate) fn store_journal(
        &self,
        session_id: &str,
        jseq: u64,
        record: &[u8],
    ) -> Result<u64, String> {
        let mut store = lock(&self.store);
        store.append_journal(session_id, jseq, record)
    }

    pub(crate) fn store_snapshot(&self) -> (u64, u64) {
        let store = lock(&self.store);
        (store.acked_mseq(), store.next_mseq())
    }

    /// Outstanding `VFS_RESYNC` paths, one per path. A live reconnect re-requests
    /// these after HELLO (`wal.md` §10 step 4, `channel.md` §9 item 6).
    pub(crate) fn store_pending_resyncs(&self) -> Vec<crate::wal::PendingResync> {
        lock(&self.store).pending_resyncs()
    }

    /// The strictly contiguous materialized watermark, distinct from the
    /// accepted level-1 tail returned by [`Host::store_snapshot`].
    #[cfg(test)]
    pub(crate) fn store_materialized_upto(&self) -> u64 {
        lock(&self.store).materialized_upto_mseq()
    }

    /// Applies one worker `ERROR VFS_CONFLICT` to the held state: terminal only
    /// while the held path is still uncovered (`channel.md` §10).
    pub(crate) fn store_vfs_conflict(&self, message: &str) -> Result<(), String> {
        let mut store = lock(&self.store);
        store.resolve_vfs_conflict(message)
    }

    /// O(1) cached acknowledged mseq, without locking the Store or touching the
    /// filesystem. This is the only watermark a synchronous UI read may use.
    pub fn workspace_watermark(&self) -> Value {
        crate::artifacts::watermark_json(self.acked_mseq.load(Ordering::Acquire))
    }

    /// Read-only workspace-change JSON for `(from, to]`. The Store lock is taken
    /// only on the caller's NAPI worker-pool thread, never by a synchronous UI
    /// read.
    pub fn workspace_changes_json(&self, from: u64, to: u64) -> Value {
        let result = {
            let store = lock(&self.store);
            store.workspace_changes(from, to)
        };
        crate::artifacts::changes_json(from, to, result)
    }

    pub(crate) fn try_activate_channel(&self) -> bool {
        self.channel_active
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
    }

    pub(crate) fn deactivate_channel(&self) {
        self.channel_active.store(false, Ordering::SeqCst);
    }

    pub(crate) fn note_console(&self, level: Option<&str>, bytes: usize) {
        self.console_bytes
            .fetch_add(bytes as u64, Ordering::Relaxed);
        if let Some(level) = level {
            if matches!(level, "debug" | "info" | "warn" | "error") {
                *lock(&self.last_console_level) = Some(level.to_owned());
            }
        }
        // The text itself is deliberately dropped: it may contain prompts,
        // tokens or request headers, and this host never logs either.
    }

    pub(crate) fn note_error_code(&self, code: &str) {
        self.note_error(code);
    }
}

struct ConnectionGuard {
    host: Arc<Host>,
    id: u64,
}

impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        lock(&self.host.sockets).remove(&self.id);
        self.host.connections.fetch_sub(1, Ordering::SeqCst);
    }
}

fn accept_loop(host: Arc<Host>, listener: TcpListener) {
    loop {
        if host.stop.load(Ordering::SeqCst) {
            break;
        }
        match listener.accept() {
            Ok((stream, _)) => {
                if host.stop.load(Ordering::SeqCst) {
                    break;
                }
                // Some Unix accept implementations (notably macOS) inherit
                // O_NONBLOCK from the listener. Every accepted stream must
                // block with its own bounded timeout instead of returning
                // EAGAIN from the first frame read.
                let _ = stream.set_nonblocking(false);
                let child = Arc::clone(&host);
                match thread::Builder::new()
                    .name("pdsh-host-conn".to_owned())
                    .spawn(move || child.handle_connection(stream))
                {
                    Ok(handle) => {
                        let mut threads = lock(&host.connection_threads);
                        threads.retain(|thread| !thread.is_finished());
                        threads.push(handle);
                    }
                    Err(_) => {
                        host.note_error("cannot spawn connection thread");
                    }
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(10));
            }
            Err(_) => {
                if !host.stop.load(Ordering::SeqCst) {
                    host.note_error("loopback accept failed");
                }
                break;
            }
        }
    }
}

/// Reads the beginning of a request without consuming it and detects the one
/// WebSocket upgrade. The handshake headers are small; a larger request falls
/// through to the bounded HTTP parser.
fn looks_like_websocket(stream: &TcpStream) -> bool {
    let mut buffer = [0u8; 16 * 1024];
    let Ok(count) = stream.peek(&mut buffer) else {
        return false;
    };
    let bytes = &buffer[..count];
    let Some(end) = bytes.windows(4).position(|window| window == b"\r\n\r\n") else {
        return false;
    };
    let head = &bytes[..end];
    let text = String::from_utf8_lossy(head).to_ascii_lowercase();
    let first = text.lines().next().unwrap_or("");
    if !first.starts_with("get /channel") {
        return false;
    }
    text.contains("upgrade: websocket") && text.contains("sec-websocket-key:")
}

fn is_delegated_route(request: &Request) -> bool {
    if request.method != "POST" {
        return false;
    }
    matches!(
        request.path.as_str(),
        "/v1/http" | "/chat/completions" | "/v1/chat/completions" | "/responses" | "/v1/messages"
    )
}

fn parse_response_headers(text: &str) -> Result<Vec<(String, String)>, String> {
    if text.len() > 32 * 1024 {
        return Err("response headers exceed 32 KiB".to_owned());
    }
    let value: Value =
        serde_json::from_str(text).map_err(|_| "response headers are not JSON".to_owned())?;
    let object = value
        .as_object()
        .ok_or_else(|| "response headers must be a JSON object".to_owned())?;
    if object.len() > 100 {
        return Err("too many response headers".to_owned());
    }
    let mut headers = Vec::with_capacity(object.len());
    for (name, value) in object {
        let name = name.to_ascii_lowercase();
        if name.is_empty()
            || !name
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
        {
            return Err("invalid response header name".to_owned());
        }
        if matches!(
            name.as_str(),
            "connection" | "transfer-encoding" | "content-length" | "keep-alive" | "upgrade"
        ) {
            continue;
        }
        let Some(value) = value.as_str() else {
            return Err("response header values must be strings".to_owned());
        };
        if value.len() > 8 * 1024
            || value
                .bytes()
                .any(|byte| byte == b'\r' || byte == b'\n' || byte == 0)
        {
            return Err("invalid response header value".to_owned());
        }
        headers.push((name, value.to_owned()));
    }
    Ok(headers)
}

fn write_stream_head(
    stream: &mut TcpStream,
    status: i32,
    headers: &[(String, String)],
    chunked: bool,
) -> std::io::Result<()> {
    let mut head = format!(
        "HTTP/1.1 {status} {}\r\nCache-Control: no-store\r\nConnection: close\r\n",
        http::reason(status as u16)
    );
    for (name, value) in headers {
        head.push_str(name);
        head.push_str(": ");
        head.push_str(value);
        head.push_str("\r\n");
    }
    if chunked {
        head.push_str("Transfer-Encoding: chunked\r\n");
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes())?;
    stream.flush()
}

fn write_chunk(stream: &mut TcpStream, data: &[u8]) -> std::io::Result<()> {
    if data.is_empty() {
        return Ok(());
    }
    stream.write_all(format!("{:x}\r\n", data.len()).as_bytes())?;
    stream.write_all(data)?;
    stream.write_all(b"\r\n")?;
    stream.flush()
}

fn cookie_headers(token: &str, location: &str) -> Vec<(String, String)> {
    vec![
        ("Location".to_owned(), location.to_owned()),
        (
            "Set-Cookie".to_owned(),
            format!("xl_launch_token={token}; Path=/; HttpOnly; SameSite=Strict"),
        ),
    ]
}

/// Removes the loopback `token` query from the path handed to ArkTS. The
/// provider edge has no use for the local transport secret; other query
/// parameters stay in order.
fn strip_query_token(target: &str) -> String {
    let Some((path, query)) = target.split_once('?') else {
        return target.to_owned();
    };
    let kept: Vec<&str> = query
        .split('&')
        .filter(|pair| {
            let key = pair.split_once('=').map(|(key, _)| key).unwrap_or(pair);
            key != "token"
        })
        .filter(|pair| !pair.is_empty())
        .collect();
    if kept.is_empty() {
        path.to_owned()
    } else {
        format!("{path}?{}", kept.join("&"))
    }
}

fn bounded_text(text: &str, limit: usize) -> String {
    let mut cleaned: String = text
        .chars()
        .map(|character| {
            if character.is_control() && character != '\n' && character != '\t' {
                ' '
            } else {
                character
            }
        })
        .collect();
    if cleaned.len() > limit {
        cleaned.truncate(limit);
    }
    cleaned
}

fn join_bounded(handles: Vec<JoinHandle<()>>, grace: Duration) {
    let deadline = Instant::now() + grace;
    for handle in handles {
        while !handle.is_finished() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(5));
        }
        if handle.is_finished() {
            let _ = handle.join();
        }
        // A handle past the grace is detached; the socket was shut down and the
        // thread will observe it. `stop` never waits for an unbounded operation.
    }
}

/// Unix epoch milliseconds; 0 on a clock before 1970.
pub fn unix_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn random_bytes(count: usize) -> Result<Vec<u8>, String> {
    use std::io::Read;
    let mut file = std::fs::File::open("/dev/urandom")
        .map_err(|error| format!("cannot open /dev/urandom: {error}"))?;
    let mut bytes = vec![0u8; count];
    file.read_exact(&mut bytes)
        .map_err(|error| format!("cannot read /dev/urandom: {error}"))?;
    Ok(bytes)
}

fn to_boot_id(bytes: &[u8]) -> [u8; 16] {
    let mut id = [0u8; 16];
    let count = bytes.len().min(16);
    id[..count].copy_from_slice(&bytes[..count]);
    id
}

pub fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

// A compile-time guard: the accepted header ceiling is shared with the parser.
const _: () = assert!(MAX_HEADER_BYTES >= 16 * 1024);
