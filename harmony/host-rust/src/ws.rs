// SPDX-License-Identifier: MIT
//! The one ordered `/channel` WebSocket.
//!
//! Every frame is decoded by the shared `pdsh-wire` codec. The host advertises
//! exactly `channel/1`, `wal/1` and `exec/1` (no unimplemented accelerators),
//! validates the worker HELLO (proto/role/boot id/fence/caps), rejects wrong
//! `seq` or corrupt payloads before dispatch, persists VFS mutations and journal
//! records with CRC32C, and never sends a level-2 acknowledgement.

use std::collections::HashMap;
use std::net::TcpStream;
use std::sync::Arc;

use pdsh_wire::{
    decode_with_header, encode, AckLevel, ApiAbort, ApiEnd, ApiResponse, Frame, JournalAck,
    JsonObject, JsonValue, VfsAck, VfsResync,
};
use tungstenite::accept_hdr_with_config;
use tungstenite::handshake::server::{ErrorResponse, Request as WsRequest, Response as WsResponse};
use tungstenite::protocol::{Message, WebSocket, WebSocketConfig};

use crate::host::Host;

const MAX_WIRE_MESSAGE: usize = 16 * 1024 * 1024 + 64;
const REQUIRED_WORKER_CAPS: [&str; 4] = ["channel/1", "vfs-sink/1", "journal/1", "api/1"];

/// Serves one authenticated upgrade and then owns the frame loop.
pub fn serve(host: Arc<Host>, stream: TcpStream) {
    let token = host.token().to_owned();
    let port = host.info.port;
    let callback = move |request: &WsRequest, response: WsResponse| {
        if !ws_authorized(request, &token, port) {
            return Err(reject(401, "{\"error\":\"unauthorized\"}"));
        }
        if request.uri().path() != "/channel" {
            return Err(reject(404, "{\"error\":\"not_found\"}"));
        }
        Ok(response)
    };
    let config = WebSocketConfig::default()
        .max_message_size(Some(MAX_WIRE_MESSAGE))
        .max_frame_size(Some(MAX_WIRE_MESSAGE));
    let mut websocket = match accept_hdr_with_config(stream, callback, Some(config)) {
        Ok(websocket) => websocket,
        Err(_) => return,
    };
    if !host.try_activate_channel() {
        let _ = send_error(
            &mut websocket,
            &mut 2,
            "PROTO_MISMATCH",
            "a channel is already active",
            None,
        );
        let _ = websocket.close(None);
        return;
    }
    let _guard = ChannelGuard {
        host: Arc::clone(&host),
    };
    let result = run_channel(&host, &mut websocket);
    if let Err(error) = result {
        if !error.is_empty() {
            host.note_error_code(&error);
        }
    }
    let _ = websocket.close(None);
}

struct ChannelGuard {
    host: Arc<Host>,
}

impl Drop for ChannelGuard {
    fn drop(&mut self) {
        self.host.deactivate_channel();
    }
}

fn run_channel(host: &Host, websocket: &mut WebSocket<TcpStream>) -> Result<(), String> {
    let (acked, next) = host.store_snapshot();
    let mut out_seq = 1u64;
    send_frame(websocket, &mut out_seq, &host_hello(host, acked, next))?;

    let first = websocket
        .read()
        .map_err(|error| format!("read failed: {error}"))?;
    let first = match first {
        Message::Binary(bytes) => bytes,
        Message::Close(_) => return Ok(()),
        Message::Ping(payload) => {
            let _ = websocket.send(Message::Pong(payload));
            return Ok(());
        }
        _ => {
            send_error(
                websocket,
                &mut out_seq,
                "BAD_PAYLOAD",
                "the first frame must be the binary HELLO",
                None,
            )?;
            return Ok(());
        }
    };
    let (header, frame) = match decode_with_header(&first) {
        Ok(decoded) => decoded,
        Err(error) => {
            send_error(
                websocket,
                &mut out_seq,
                "BAD_PAYLOAD",
                &format!("cannot decode HELLO: {error}"),
                None,
            )?;
            return Ok(());
        }
    };
    if header.seq != 1 {
        send_error(
            websocket,
            &mut out_seq,
            "BAD_SEQ",
            "the first frame must carry seq 1",
            Some(header.seq),
        )?;
        return Ok(());
    }
    let Frame::Hello(hello) = frame else {
        send_error(
            websocket,
            &mut out_seq,
            "PROTO_MISMATCH",
            "the first frame must be HELLO",
            Some(header.seq),
        )?;
        return Ok(());
    };
    if let Err((code, message)) = validate_worker_hello(&hello, host, acked) {
        send_error(websocket, &mut out_seq, code, &message, Some(header.seq))?;
        return Ok(());
    }
    // A live reconnect is not recovery: paths still held in memory are
    // re-requested after HELLO, at most one outstanding per path
    // (`channel.md` §9 item 6).
    let mut announced_resyncs: HashMap<String, u64> = HashMap::new();
    send_pending_resyncs(host, websocket, &mut out_seq, &mut announced_resyncs)?;
    let mut in_seq = 2u64;

    loop {
        let message = match websocket.read() {
            Ok(message) => message,
            Err(_) => return Ok(()),
        };
        match message {
            Message::Binary(bytes) => {
                let (header, frame) = match decode_with_header(&bytes) {
                    Ok(decoded) => decoded,
                    Err(error) => {
                        send_error(
                            websocket,
                            &mut out_seq,
                            "BAD_PAYLOAD",
                            &format!("cannot decode frame: {error}"),
                            None,
                        )?;
                        return Ok(());
                    }
                };
                if header.seq != in_seq {
                    send_error(
                        websocket,
                        &mut out_seq,
                        "BAD_SEQ",
                        &format!("expected seq {in_seq}, found {}", header.seq),
                        Some(header.seq),
                    )?;
                    return Ok(());
                }
                in_seq = in_seq.saturating_add(1);
                dispatch(
                    host,
                    websocket,
                    &mut out_seq,
                    &mut announced_resyncs,
                    in_seq,
                    header.seq,
                    frame,
                )?;
            }
            Message::Text(_) => {
                send_error(
                    websocket,
                    &mut out_seq,
                    "BAD_PAYLOAD",
                    "text frames are a protocol error",
                    None,
                )?;
                return Ok(());
            }
            Message::Ping(payload) => {
                let _ = websocket.send(Message::Pong(payload));
            }
            Message::Pong(_) => {}
            Message::Close(_) => return Ok(()),
            Message::Frame(_) => {}
        }
    }
}

fn dispatch(
    host: &Host,
    websocket: &mut WebSocket<TcpStream>,
    out_seq: &mut u64,
    announced_resyncs: &mut HashMap<String, u64>,
    _next_in_seq: u64,
    ref_seq: u64,
    frame: Frame,
) -> Result<(), String> {
    let frame_type = frame.frame_type();
    if !frame_type.direction().allows_worker_to_host() {
        send_error(
            websocket,
            out_seq,
            "PROTO_MISMATCH",
            &format!("{} is not a worker-to-host frame", frame_type.name()),
            Some(ref_seq),
        )?;
        return Ok(());
    }
    match frame {
        Frame::Hello(_) => {
            send_error(
                websocket,
                out_seq,
                "PROTO_MISMATCH",
                "HELLO may only be the first frame",
                Some(ref_seq),
            )?;
        }
        Frame::VfsMut(mutation) => match host.store_commit(&mutation) {
            Ok(_) => {
                // Level 1 is the accepted WAL tail (the record's write/fsync
                // returned); it is not the materialized watermark, which can
                // stay below a held append (`wal.md` §5.1).
                let (acked, _) = host.store_snapshot();
                send_frame(
                    websocket,
                    out_seq,
                    &Frame::VfsAck(VfsAck {
                        level: AckLevel::Write,
                        upto_mseq: acked.max(mutation.mseq),
                    }),
                )?;
                // One `VFS_RESYNC` per path, sent after the level-1 ACK and at
                // most once per connection until the path is settled.
                send_pending_resyncs(host, websocket, out_seq, announced_resyncs)?;
            }
            Err(error) => {
                let code = classify(&error);
                send_error(websocket, out_seq, code, &error, Some(ref_seq))?;
            }
        },
        Frame::VfsBarrier(barrier) => {
            let (acked, _) = host.store_snapshot();
            // Level 2 is never claimed: no power-loss level has been measured.
            // Answer with the level this host can actually prove.
            let upto = acked.min(barrier.upto_mseq);
            send_frame(
                websocket,
                out_seq,
                &Frame::VfsAck(VfsAck {
                    level: AckLevel::Write,
                    upto_mseq: upto,
                }),
            )?;
        }
        Frame::Journal(journal) => {
            if let Err(error) = crate::wal::validate_identifier(&journal.session_id) {
                send_error(websocket, out_seq, "BAD_PAYLOAD", &error, Some(ref_seq))?;
                return Ok(());
            }
            match host.store_journal(&journal.session_id, journal.jseq, &journal.record) {
                Ok(upto) => {
                    send_frame(
                        websocket,
                        out_seq,
                        &Frame::JournalAck(JournalAck {
                            level: AckLevel::Write,
                            session_id: journal.session_id,
                            upto_jseq: upto,
                        }),
                    )?;
                }
                Err(error) => {
                    send_error(websocket, out_seq, "BAD_PAYLOAD", &error, Some(ref_seq))?;
                }
            }
        }
        Frame::ExecReq(request) => {
            let exec_id = request
                .get("exec_id")
                .and_then(|value| value.as_str())
                .filter(|value| crate::wal::validate_identifier(value).is_ok())
                .unwrap_or("")
                .to_owned();
            send_exec_error(websocket, out_seq, &exec_id, ref_seq)?;
        }
        Frame::ExecCtl(request) => {
            let exec_id = request
                .get("exec_id")
                .and_then(|value| value.as_str())
                .filter(|value| value.len() <= 64)
                .unwrap_or("")
                .to_owned();
            send_exec_error(websocket, out_seq, &exec_id, ref_seq)?;
        }
        Frame::Console(console) => {
            let level = console
                .get("level")
                .and_then(|value| value.as_str())
                .map(|value| value.to_owned());
            let bytes = console
                .get("text")
                .and_then(|value| value.as_str())
                .map(|value| value.len())
                .unwrap_or(0);
            host.note_console(level.as_deref(), bytes.min(64 * 1024));
        }
        Frame::ApiAbort(ApiAbort { .. }) => {
            // No API_REQ is pending in this build; aborting one is a no-op.
        }
        Frame::ApiRes(ApiResponse { .. }) | Frame::ApiEnd(ApiEnd { .. }) => {
            send_error(
                websocket,
                out_seq,
                "PROTO_MISMATCH",
                "no API request is pending on this host",
                Some(ref_seq),
            )?;
        }
        Frame::Error(error) => {
            let code = error
                .get("code")
                .and_then(|value| value.as_str())
                .unwrap_or("worker_error");
            if code == "VFS_CONFLICT" {
                let message = error
                    .get("message")
                    .and_then(|value| value.as_str())
                    .unwrap_or("");
                // A conflict for a still-held (uncovered) path is terminal; a
                // conflict for a path already settled by a later complete
                // write/remove is ignored and does not enter Failed
                // (`channel.md` §10, `wal.md` §6.2).
                if let Err(failure) = host.store_vfs_conflict(message) {
                    return Err(failure);
                }
            } else {
                host.note_error_code(&format!("worker:{code}"));
            }
        }
        Frame::VfsAck(_)
        | Frame::VfsResync(_)
        | Frame::ExecEvt(_)
        | Frame::JournalAck(_)
        | Frame::ApiReq(_)
        | Frame::Lifecycle(_)
        | Frame::Fence(_) => {
            send_error(
                websocket,
                out_seq,
                "PROTO_MISMATCH",
                &format!("{} is host-to-worker only", frame_type.name()),
                Some(ref_seq),
            )?;
        }
    }
    Ok(())
}

fn host_hello(host: &Host, acked: u64, next: u64) -> Frame {
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
    .unwrap_or_default();
    let hello = JsonObject::from_pairs([
        ("proto".to_owned(), JsonValue::Integer(1)),
        ("role".to_owned(), JsonValue::String("host".to_owned())),
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
            JsonValue::Array(vec![
                JsonValue::String("channel/1".to_owned()),
                JsonValue::String("wal/1".to_owned()),
                JsonValue::String("exec/1".to_owned()),
            ]),
        ),
        ("next_mseq".to_owned(), JsonValue::Integer(next as i128)),
        ("acked_mseq".to_owned(), JsonValue::Integer(acked as i128)),
        ("durable_mseq".to_owned(), JsonValue::Integer(0)),
    ])
    .unwrap_or_default();
    Frame::Hello(hello)
}

fn validate_worker_hello(
    hello: &JsonObject,
    host: &Host,
    acked: u64,
) -> Result<(), (&'static str, String)> {
    if integer(hello, "proto") != Some(1) {
        return Err(("PROTO_MISMATCH", "worker HELLO proto must be 1".to_owned()));
    }
    if text(hello, "role") != Some("worker") {
        return Err((
            "PROTO_MISMATCH",
            "worker HELLO role must be worker".to_owned(),
        ));
    }
    if text(hello, "boot_id") != Some(host.info.boot_id.as_str()) {
        return Err((
            "PROTO_MISMATCH",
            "worker HELLO boot_id does not match this boot".to_owned(),
        ));
    }
    if integer(hello, "session_epoch") != Some(host.info.session_epoch) {
        return Err((
            "PROTO_MISMATCH",
            "worker HELLO session_epoch does not match this host".to_owned(),
        ));
    }
    let fence = hello
        .get("fence")
        .and_then(|value| value.as_object())
        .ok_or((
            "PROTO_MISMATCH",
            "worker HELLO has no fence object".to_owned(),
        ))?;
    if text(fence, "owner") != Some("pdsh-host") {
        return Err((
            "FENCE_STALE",
            "worker HELLO fence owner is stale".to_owned(),
        ));
    }
    if integer(fence, "generation") != Some(host.info.session_epoch) {
        return Err((
            "FENCE_STALE",
            "worker HELLO fence generation is stale".to_owned(),
        ));
    }
    if fence.get("wal_seq").and_then(json_u64).is_none() {
        return Err((
            "PROTO_MISMATCH",
            "worker HELLO fence.wal_seq must be a non-negative integer".to_owned(),
        ));
    }
    let caps = hello
        .get("caps")
        .and_then(|value| value.as_array())
        .ok_or((
            "PROTO_MISMATCH",
            "worker HELLO caps must be an array".to_owned(),
        ))?;
    let caps: Vec<&str> = caps.iter().filter_map(|value| value.as_str()).collect();
    for required in REQUIRED_WORKER_CAPS {
        if !caps.contains(&required) {
            return Err((
                "PROTO_MISMATCH",
                format!("worker HELLO is missing required capability {required}"),
            ));
        }
    }
    if let Some(value) = hello.get("last_mseq") {
        let last = json_u64(value).ok_or((
            "PROTO_MISMATCH",
            "worker last_mseq must be a non-negative integer".to_owned(),
        ))?;
        if last > acked {
            return Err((
                "BAD_MSEQ",
                "worker last_mseq is above the host's acknowledged tail".to_owned(),
            ));
        }
    }
    Ok(())
}

fn send_exec_error(
    websocket: &mut WebSocket<TcpStream>,
    out_seq: &mut u64,
    exec_id: &str,
    ref_seq: u64,
) -> Result<(), String> {
    let event = JsonObject::from_pairs([
        ("type".to_owned(), JsonValue::String("error".to_owned())),
        ("exec_id".to_owned(), JsonValue::String(exec_id.to_owned())),
        ("seq".to_owned(), JsonValue::Integer(0)),
        (
            "code".to_owned(),
            JsonValue::String("UNSUPPORTED".to_owned()),
        ),
        (
            "message".to_owned(),
            JsonValue::String(
                "the native host does not implement the exec bridge in this build".to_owned(),
            ),
        ),
        ("ref_seq".to_owned(), JsonValue::Integer(ref_seq as i128)),
    ])
    .unwrap_or_default();
    send_frame(websocket, out_seq, &Frame::ExecEvt(event))
}

fn send_error(
    websocket: &mut WebSocket<TcpStream>,
    out_seq: &mut u64,
    code: &str,
    message: &str,
    ref_seq: Option<u64>,
) -> Result<(), String> {
    let mut pairs = vec![
        ("code".to_owned(), JsonValue::String(code.to_owned())),
        (
            "message".to_owned(),
            JsonValue::String(truncate(message, 300)),
        ),
    ];
    if let Some(ref_seq) = ref_seq {
        pairs.push(("ref_seq".to_owned(), JsonValue::Integer(ref_seq as i128)));
    }
    let object = JsonObject::from_pairs(pairs).unwrap_or_default();
    let frame = Frame::Error(object);
    send_frame(websocket, out_seq, &frame)?;
    let _ = websocket.close(None);
    Ok(())
}

/// Sends one `VFS_RESYNC` per outstanding path that this connection has not
/// announced yet. A settled path is dropped from the announcement map, so a
/// later new mismatch on the same path is requested again.
fn send_pending_resyncs(
    host: &Host,
    websocket: &mut WebSocket<TcpStream>,
    out_seq: &mut u64,
    announced: &mut HashMap<String, u64>,
) -> Result<(), String> {
    let pending = host.store_pending_resyncs();
    announced.retain(|path, _| pending.iter().any(|request| request.path == *path));
    for request in pending {
        if announced.get(&request.path) == Some(&request.mseq) {
            continue;
        }
        send_frame(
            websocket,
            out_seq,
            &Frame::VfsResync(VfsResync {
                path: request.path.clone(),
            }),
        )?;
        announced.insert(request.path, request.mseq);
    }
    Ok(())
}

fn send_frame(
    websocket: &mut WebSocket<TcpStream>,
    out_seq: &mut u64,
    frame: &Frame,
) -> Result<(), String> {
    let bytes = encode(frame, *out_seq).map_err(|error| format!("encode failed: {error}"))?;
    *out_seq = out_seq.saturating_add(1);
    websocket
        .send(Message::Binary(bytes.into()))
        .map_err(|_| "send failed".to_owned())
}

fn classify(error: &str) -> &'static str {
    if error.contains("mseq") || error.contains("duplicate") {
        "BAD_MSEQ"
    } else if error.contains("path") || error.contains("outside /dsh") || error.contains("symlink")
    {
        "BAD_PATH"
    } else if error.contains("expected") || error.contains("consecutive") {
        "BAD_PAYLOAD"
    } else {
        "WAL_CORRUPT"
    }
}

fn text<'a>(object: &'a JsonObject, key: &str) -> Option<&'a str> {
    object.get(key).and_then(|value| value.as_str())
}

fn integer(object: &JsonObject, key: &str) -> Option<u64> {
    object.get(key).and_then(json_u64)
}

fn json_u64(value: &JsonValue) -> Option<u64> {
    match value {
        JsonValue::Integer(value) if *value >= 0 && *value <= u64::MAX as i128 => {
            Some(*value as u64)
        }
        _ => None,
    }
}

fn truncate(text: &str, limit: usize) -> String {
    let mut out: String = text
        .chars()
        .map(|character| {
            if character.is_control() && character != '\n' && character != '\t' {
                ' '
            } else {
                character
            }
        })
        .collect();
    if out.len() > limit {
        out.truncate(limit);
    }
    out
}

fn ws_authorized(request: &WsRequest, token: &str, port: u16) -> bool {
    let authority_ok = request
        .headers()
        .get("host")
        .and_then(|value| value.to_str().ok())
        .map(|host| host.eq_ignore_ascii_case(&format!("127.0.0.1:{port}")))
        .unwrap_or(false);
    if !authority_ok {
        return false;
    }
    let query = request.uri().query().unwrap_or("");
    let query_token = query.split('&').find_map(|pair| {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        (key == "token").then_some(value)
    });
    let header_token = request
        .headers()
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| {
            let (scheme, token) = value.split_once(' ')?;
            scheme
                .eq_ignore_ascii_case("bearer")
                .then_some(token.trim())
        });
    let cookie_token = request
        .headers()
        .get("cookie")
        .and_then(|value| value.to_str().ok())
        .and_then(|header| {
            header.split([';', ',']).find_map(|segment| {
                let trimmed = segment.trim();
                let (name, value) = trimmed.split_once('=')?;
                (name.trim() == "xl_launch_token").then_some(value.trim())
            })
        });
    crate::http::token_matches(query_token, token)
        || crate::http::token_matches(header_token, token)
        || crate::http::token_matches(cookie_token, token)
}

fn reject(status: u16, body: &str) -> ErrorResponse {
    tungstenite::http::Response::builder()
        .status(status)
        .header("content-type", "application/json")
        .header("cache-control", "no-store")
        .body(Some(body.to_owned()))
        .unwrap_or_else(|_| {
            tungstenite::http::Response::builder()
                .status(500)
                .body(Some("{\"error\":\"internal\"}".to_owned()))
                .expect("static rejection response")
        })
}
