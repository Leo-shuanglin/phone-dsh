// SPDX-License-Identifier: MIT
//! A deliberately small HTTP/1.1 server surface.
//!
//! The loopback origin serves the WebView and the Worker only. It is not a
//! general-purpose server: one request per connection, bounded headers/body,
//! no request body chunking, and no method other than GET/POST where documented.

use std::fs;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::Value;

pub const MAX_HEADER_BYTES: usize = 32 * 1024;
pub const MAX_HEADER_LINE: usize = 8 * 1024;
pub const MAX_HEADERS: usize = 100;
pub const MAX_BODY_BYTES: usize = 4 * 1024 * 1024;
const IO_TIMEOUT: Duration = Duration::from_secs(20);

/// One bounded HTTP request.
#[derive(Debug)]
pub struct Request {
    pub method: String,
    pub target: String,
    pub path: String,
    pub query: String,
    pub version: String,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Request {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find_map(|(key, value)| (key == name).then_some(value.as_str()))
    }

    pub fn headers_object(&self) -> serde_json::Map<String, Value> {
        let mut map = serde_json::Map::new();
        for (name, value) in &self.headers {
            map.entry(name.clone())
                .and_modify(|existing| {
                    if let Value::String(text) = existing {
                        text.push_str(", ");
                        text.push_str(value);
                    }
                })
                .or_insert_with(|| Value::String(value.clone()));
        }
        map
    }
}

/// A request that cannot be served as HTTP; the status and stable code are
/// written as a bounded JSON body.
#[derive(Debug)]
pub struct ParseError {
    pub status: u16,
    pub code: &'static str,
    pub message: &'static str,
}

impl ParseError {
    fn new(status: u16, code: &'static str, message: &'static str) -> Self {
        ParseError {
            status,
            code,
            message,
        }
    }
}

pub fn set_timeouts(stream: &TcpStream) {
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
    let _ = stream.set_nodelay(true);
}

/// Reads one request. `Ok(None)` means the peer closed before a request line.
pub fn read_request(stream: &mut TcpStream) -> Result<Option<Request>, ParseError> {
    let mut buffer = Vec::with_capacity(4096);
    let mut scratch = [0u8; 4096];
    let header_end = loop {
        if let Some(position) = find_bytes(&buffer, b"\r\n\r\n") {
            break position + 4;
        }
        if buffer.len() > MAX_HEADER_BYTES {
            return Err(ParseError::new(
                431,
                "headers_too_large",
                "request headers exceed the cap",
            ));
        }
        let read = stream
            .read(&mut scratch)
            .map_err(|_| ParseError::new(400, "bad_request", "cannot read request"))?;
        if read == 0 {
            if buffer.is_empty() {
                return Ok(None);
            }
            return Err(ParseError::new(
                400,
                "bad_request",
                "connection closed inside request headers",
            ));
        }
        buffer.extend_from_slice(&scratch[..read]);
    };
    if header_end > MAX_HEADER_BYTES {
        return Err(ParseError::new(
            431,
            "headers_too_large",
            "request headers exceed the cap",
        ));
    }

    let head = &buffer[..header_end - 4];
    let mut lines = head.split(|byte| *byte == b'\n');
    let request_line = lines
        .next()
        .ok_or_else(|| ParseError::new(400, "bad_request", "request line is missing"))?;
    let request_line = trim_cr(request_line);
    let request_line = std::str::from_utf8(request_line)
        .map_err(|_| ParseError::new(400, "bad_request", "request line is not UTF-8"))?;
    let mut parts = request_line.split(' ');
    let method = parts
        .next()
        .filter(|part| is_http_token(part.as_bytes()) && !part.is_empty())
        .ok_or_else(|| ParseError::new(400, "bad_request", "request method is invalid"))?
        .to_owned();
    let target = parts
        .next()
        .filter(|part| part.starts_with('/'))
        .ok_or_else(|| ParseError::new(400, "bad_request", "request target is invalid"))?
        .to_owned();
    let version = parts
        .next()
        .filter(|part| *part == "HTTP/1.1" || *part == "HTTP/1.0")
        .ok_or_else(|| ParseError::new(505, "http_version", "HTTP/1.1 is required"))?
        .to_owned();
    if target.bytes().any(|byte| byte.is_ascii_control()) {
        return Err(ParseError::new(
            400,
            "bad_request",
            "request target has a control character",
        ));
    }
    if parts.next().is_some() {
        return Err(ParseError::new(
            400,
            "bad_request",
            "request line has trailing bytes",
        ));
    }
    if target.len() > MAX_HEADER_LINE {
        return Err(ParseError::new(
            414,
            "uri_too_long",
            "request target exceeds the cap",
        ));
    }
    let (path, query) = target.split_once('?').unwrap_or((&target, ""));
    let path = path.to_owned();
    let query = query.to_owned();
    if path.contains('%') {
        return Err(ParseError::new(
            400,
            "bad_path",
            "percent escapes are not accepted",
        ));
    }

    let mut headers = Vec::new();
    for line in lines {
        let line = trim_cr(line);
        if line.is_empty() {
            continue;
        }
        if line.len() > MAX_HEADER_LINE {
            return Err(ParseError::new(
                431,
                "headers_too_large",
                "request header line exceeds the cap",
            ));
        }
        let colon = line
            .iter()
            .position(|byte| *byte == b':')
            .ok_or_else(|| ParseError::new(400, "bad_request", "malformed request header"))?;
        let name = std::str::from_utf8(&line[..colon])
            .map_err(|_| ParseError::new(400, "bad_request", "header name is not UTF-8"))?;
        if name.is_empty() || !is_http_token(name.as_bytes()) {
            return Err(ParseError::new(400, "bad_request", "invalid header name"));
        }
        let value = std::str::from_utf8(&line[colon + 1..])
            .map_err(|_| ParseError::new(400, "bad_request", "header value is not UTF-8"))?;
        let value = value.trim();
        if value
            .bytes()
            .any(|byte| byte == b'\r' || byte == b'\n' || byte == 0)
        {
            return Err(ParseError::new(
                400,
                "bad_request",
                "header value has a control byte",
            ));
        }
        headers.push((name.to_ascii_lowercase(), value.to_owned()));
        if headers.len() > MAX_HEADERS {
            return Err(ParseError::new(
                431,
                "headers_too_large",
                "too many request headers",
            ));
        }
    }

    let content_lengths: Vec<&str> = headers
        .iter()
        .filter(|(name, _)| name == "content-length")
        .map(|(_, value)| value.as_str())
        .collect();
    if content_lengths.len() > 1 {
        return Err(ParseError::new(
            400,
            "bad_request",
            "duplicate Content-Length",
        ));
    }
    let content_length = match content_lengths.first() {
        Some(value) => value
            .parse::<usize>()
            .map_err(|_| ParseError::new(400, "bad_request", "invalid Content-Length"))?,
        None => 0,
    };
    if content_length > MAX_BODY_BYTES {
        return Err(ParseError::new(
            413,
            "body_too_large",
            "request body exceeds 4 MiB",
        ));
    }
    if headers.iter().any(|(name, _)| name == "transfer-encoding") {
        return Err(ParseError::new(
            400,
            "bad_request",
            "Transfer-Encoding request bodies are not accepted",
        ));
    }

    let mut body = if buffer.len() > header_end {
        buffer[header_end..].to_vec()
    } else {
        Vec::new()
    };
    if body.len() > content_length {
        body.truncate(content_length);
    }
    while body.len() < content_length {
        let remaining = content_length - body.len();
        let mut chunk = vec![0u8; remaining.min(64 * 1024)];
        let read = stream
            .read(&mut chunk)
            .map_err(|_| ParseError::new(400, "bad_request", "cannot read request body"))?;
        if read == 0 {
            return Err(ParseError::new(
                400,
                "bad_request",
                "connection closed inside request body",
            ));
        }
        body.extend_from_slice(&chunk[..read]);
    }

    Ok(Some(Request {
        method,
        target,
        path,
        query,
        version,
        headers,
        body,
    }))
}

fn find_bytes(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

fn trim_cr(line: &[u8]) -> &[u8] {
    line.strip_suffix(b"\r").unwrap_or(line)
}

fn is_http_token(bytes: &[u8]) -> bool {
    !bytes.is_empty()
        && bytes.iter().all(|byte| {
            byte.is_ascii_alphanumeric()
                || matches!(
                    byte,
                    b'!' | b'#'
                        | b'$'
                        | b'%'
                        | b'&'
                        | b'\''
                        | b'*'
                        | b'+'
                        | b'-'
                        | b'.'
                        | b'^'
                        | b'_'
                        | b'`'
                        | b'|'
                        | b'~'
                )
        })
}

/// Basic `name=value` query parsing. Values are returned raw (the token is hex
/// and the only expected query parameter), never URL-decoded.
pub fn query_param<'a>(query: &'a str, name: &str) -> Option<&'a str> {
    query.split('&').find_map(|pair| {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        (key == name).then_some(value)
    })
}

/// Constant-time token comparison over UTF-8 bytes.
pub fn token_matches(candidate: Option<&str>, expected: &str) -> bool {
    let Some(candidate) = candidate else {
        return false;
    };
    let left = candidate.as_bytes();
    let right = expected.as_bytes();
    let mut difference = left.len() ^ right.len();
    let length = left.len().max(right.len());
    for index in 0..length {
        let a = left.get(index).copied().unwrap_or(0);
        let b = right.get(index).copied().unwrap_or(0);
        difference |= (a ^ b) as usize;
    }
    difference == 0
}

pub fn bearer(req: &Request) -> Option<&str> {
    let value = req.header("authorization")?;
    let (scheme, token) = value.split_once(' ')?;
    if !scheme.eq_ignore_ascii_case("bearer") {
        return None;
    }
    let token = token.trim();
    (!token.is_empty()).then_some(token)
}

pub fn launch_cookie(req: &Request) -> Option<&str> {
    let header = req.header("cookie")?;
    for segment in header.split([';', ',']) {
        let trimmed = segment.trim();
        let Some((name, value)) = trimmed.split_once('=') else {
            continue;
        };
        if name.trim() == "xl_launch_token" {
            return Some(value.trim());
        }
    }
    None
}

/// The Android D22 authority binding: only the bound loopback authority answers.
pub fn host_matches(req: &Request, port: u16) -> bool {
    req.header("host")
        .map(|host| host.eq_ignore_ascii_case(&format!("127.0.0.1:{port}")))
        .unwrap_or(false)
}

pub fn json_response(status: u16) -> Vec<u8> {
    format!("HTTP/1.1 {status} {}\r\n", reason(status)).into_bytes()
}

pub fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        204 => "No Content",
        303 => "See Other",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        408 => "Request Timeout",
        413 => "Payload Too Large",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        504 => "Gateway Timeout",
        _ => "Error",
    }
}

pub fn write_json(stream: &mut TcpStream, status: u16, value: &Value) {
    write_json_extra(stream, status, value, &[]);
}

pub fn write_json_extra(
    stream: &mut TcpStream,
    status: u16,
    value: &Value,
    extra_headers: &[(String, String)],
) {
    let body = serde_json::to_vec(value).unwrap_or_else(|_| b"{\"error\":\"internal\"}".to_vec());
    write_response(
        stream,
        status,
        "application/json; charset=utf-8",
        &body,
        extra_headers,
    );
}

pub fn write_response(
    stream: &mut TcpStream,
    status: u16,
    content_type: &str,
    body: &[u8],
    extra_headers: &[(String, String)],
) {
    let mut head = format!(
        "HTTP/1.1 {status} {}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n",
        reason(status),
        body.len()
    );
    for (name, value) in extra_headers {
        if value.bytes().any(|byte| byte == b'\r' || byte == b'\n') {
            continue;
        }
        head.push_str(name);
        head.push_str(": ");
        head.push_str(value);
        head.push_str("\r\n");
    }
    head.push_str("\r\n");
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}

pub fn write_empty(stream: &mut TcpStream, status: u16, extra_headers: &[(String, String)]) {
    let mut head = format!(
        "HTTP/1.1 {status} {}\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n",
        reason(status)
    );
    for (name, value) in extra_headers {
        if value.bytes().any(|byte| byte == b'\r' || byte == b'\n') {
            continue;
        }
        head.push_str(name);
        head.push_str(": ");
        head.push_str(value);
        head.push_str("\r\n");
    }
    head.push_str("\r\n");
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.flush();
}

/// Canonicalized static roots; `None` means the directory did not exist at start.
#[derive(Clone, Debug)]
pub struct StaticRoots {
    pub app: Option<PathBuf>,
    pub worker: Option<PathBuf>,
}

impl StaticRoots {
    pub fn new(assets: &Path) -> Result<StaticRoots, String> {
        let app = canonical_optional(&assets.join("app"))?;
        let worker = canonical_optional(&assets.join("worker"))?;
        Ok(StaticRoots { app, worker })
    }
}

fn canonical_optional(path: &Path) -> Result<Option<PathBuf>, String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(format!(
                    "static root {} is not a plain directory",
                    path.display()
                ));
            }
            fs::canonicalize(path)
                .map(Some)
                .map_err(|error| format!("cannot canonicalize {}: {error}", path.display()))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(format!("cannot stat {}: {error}", path.display())),
    }
}

/// Resolves a relative request path under a canonical root and serves it.
///
/// Returns `false` when the path is refused or missing; callers write the 404.
pub fn serve_static(
    stream: &mut TcpStream,
    root: &Path,
    request_path: &str,
    extra_headers: &[(String, String)],
) -> bool {
    let Some(file) = resolve_static(root, request_path) else {
        return false;
    };
    let metadata = match fs::metadata(&file) {
        Ok(metadata) if metadata.is_file() => metadata,
        _ => return false,
    };
    let mut input = match fs::File::open(&file) {
        Ok(input) => input,
        Err(_) => return false,
    };
    let mut head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n",
        content_type_for(&file),
        metadata.len()
    );
    for (name, value) in extra_headers {
        if value.bytes().any(|byte| byte == b'\r' || byte == b'\n') {
            continue;
        }
        head.push_str(name);
        head.push_str(": ");
        head.push_str(value);
        head.push_str("\r\n");
    }
    head.push_str("\r\n");
    if stream.write_all(head.as_bytes()).is_err() {
        return true;
    }
    let _ = std::io::copy(&mut input, stream);
    let _ = stream.flush();
    true
}

/// Maps `/app/...` (or `/assets/...`) to a regular file under `root`.
pub fn resolve_static(root: &Path, request_path: &str) -> Option<PathBuf> {
    let relative = request_path.strip_prefix('/')?;
    let mut segments: Vec<&str> = if relative.is_empty() {
        vec!["index.html"]
    } else {
        relative.trim_end_matches('/').split('/').collect()
    };
    if segments.is_empty() {
        segments.push("index.html");
    }
    for segment in &segments {
        if !is_safe_segment(segment) {
            return None;
        }
    }
    let mut candidate = root.to_path_buf();
    for segment in &segments {
        candidate.push(segment);
    }
    let mut real = fs::canonicalize(&candidate).ok()?;
    if !real.starts_with(root) {
        return None;
    }
    if real.is_dir() {
        real = fs::canonicalize(real.join("index.html")).ok()?;
        if !real.starts_with(root) || !real.is_file() {
            return None;
        }
    }
    real.is_file().then_some(real)
}

fn is_safe_segment(segment: &str) -> bool {
    !segment.is_empty()
        && segment != "."
        && segment != ".."
        && segment.len() <= 255
        && !segment
            .bytes()
            .any(|byte| matches!(byte, b'\\' | b'\0' | b'%' | b':'))
}

pub fn content_type_for(path: &Path) -> &'static str {
    let extension = path
        .extension()
        .and_then(|extension| extension.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    match extension.as_str() {
        "html" | "htm" => "text/html; charset=utf-8",
        "js" | "mjs" | "cjs" => "text/javascript; charset=utf-8",
        "json" => "application/json; charset=utf-8",
        "map" => "application/json; charset=utf-8",
        "webmanifest" => "application/manifest+json; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "wasm" => "application/wasm",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "txt" | "md" => "text/plain; charset=utf-8",
        "xml" => "application/xml; charset=utf-8",
        "gz" | "tgz" => "application/gzip",
        "tar" => "application/x-tar",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        "ttf" => "font/ttf",
        "otf" => "font/otf",
        "pdf" => "application/pdf",
        _ => "application/octet-stream",
    }
}

pub fn is_html_path(path: &Path) -> bool {
    content_type_for(path).starts_with("text/html")
}
