/* SPDX-License-Identifier: MIT
 * Native phone-dsh host for HarmonyOS: C ABI.
 *
 * All JSON strings returned by this library are UTF-8 and must be released with
 * pdsh_host_free. The per-start token is present only in pdsh_host_start's
 * success object; status and health never repeat it.
 */
#ifndef PDSH_HOST_H
#define PDSH_HOST_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*pdsh_host_callback)(const char *event_json, void *userdata);

/* JSON {port,token,bootId,sessionEpoch,ackedMseq,recoveredBytes,error?}. */
char *pdsh_host_start(const char *root, const char *assets);
void pdsh_host_stop(void);
char *pdsh_host_status(void);

/* The callback receives one of two local JSON objects (never a P01 wire frame):
 *
 * 1. {"type":"http","id":...,"method":...,"path":...,"headers":{...},
 *     "bodyBase64":"...","generation":...}
 *    One outbound model request to perform; answer with pdsh_host_respond.
 *
 * 2. {"type":"http-cancel","id":...,"generation":...}
 *    The native request is already terminal (host stop, proxy timeout, or a
 *    confirmed TCP peer abort). The paired ArkTS proxy must cancel the matching
 *    outbound system-network request and send no further pdsh_host_respond
 *    calls for that id. This event carries no headers, body, token or other
 *    credentials. */
void pdsh_host_set_callback(pdsh_host_callback callback, void *userdata);

/* kind: 0=head, 1=chunk, 2=end, 3=error.
 * headers: JSON object string for head (may be NULL).
 * data/len: chunk bytes or a bounded error message.
 * Request ids are unique for the process lifetime, fit in a JS safe integer,
 * and are never reused across host starts. Responses for an unknown, finished,
 * refused or old-generation id are ignored; callers must not crash or leak. */
void pdsh_host_respond(uint64_t id, int32_t kind, int32_t status,
                       const char *headers, const uint8_t *data, size_t len);

/* JSON {"ok":true} or {"ok":false,"error":"..."}. */
char *pdsh_host_install_bundle(const char *zip, const char *assets);

/* Read-only workspace changes in the retained mseq range (from, to].
 *
 * Both arguments are decimal u64 strings. The result is
 * {"fromMseq":"...","toMseq":"...","files":[{"path":"/dsh/workspace/...",
 * "size":N,"mseq":"..."}],"error"?}. It reads existing WAL records and the
 * current materialized files only; it never reads file content, writes or
 * modifies the WAL. Invalid, unknown or out-of-watermark ranges are an error,
 * never a successful empty list. The returned string is released with
 * pdsh_host_free and the call may block: run it on a worker thread. */
char *pdsh_host_workspace_changes(const char *from_mseq, const char *to_mseq);

/* JSON {"ackedMseq":"<u64>","error"?}: the cached ACK after the last successful
 * Store commit (or the recovered Store ACK at boot). O(1) and lock-free; never
 * waits for the Store or filesystem. Read only once Native and the Worker are
 * ready. The returned string is released with pdsh_host_free. */
char *pdsh_host_workspace_watermark(void);

/* Renders LaTeX to a self-contained SVG JSON object.
 *
 * `latex` is UTF-8, at most 8192 bytes. `options_json` is an object:
 * {"fontSize":8..96,"color":"#rrggbb","displayMode":true|false}.
 * Returns {"svg":string,"width":number,"height":number,"baseline":number} or
 * {"error":string}. The SVG contains outlined glyph paths and no external font,
 * network, script or WebView dependency. Panics are caught; the return value is
 * owned by this library and must be released with pdsh_host_free. */
char *pdsh_math_render_svg(const char *latex, const char *options_json);

void pdsh_host_free(char *pointer);

#ifdef __cplusplus
}
#endif

#endif /* PDSH_HOST_H */
