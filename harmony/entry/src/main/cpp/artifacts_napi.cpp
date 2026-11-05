// SPDX-License-Identifier: MIT
// Workspace artifact bridge: the two string C ABI calls that the artifacts page
// uses. The blocking WAL read goes through the shared QueueStringJob helper so
// this file never maintains a second async-work implementation.
#include "artifacts_napi.h"
#include "native_async.h"

namespace {

napi_value OwnedString(napi_env env, char *text) {
    if (text == nullptr) {
        napi_throw_error(env, nullptr, "Native host returned no result");
        return nullptr;
    }
    napi_value result = nullptr;
    if (napi_create_string_utf8(env, text, NAPI_AUTO_LENGTH, &result) != napi_ok) {
        pdsh_host_free(text);
        napi_throw_error(env, nullptr, "Cannot read native result");
        return nullptr;
    }
    pdsh_host_free(text);
    return result;
}

// Synchronous by contract: the Rust side only loads one atomic u64. It must
// never take the Store lock, so the UI never blocks on startup/recovery.
napi_value WorkspaceWatermark(napi_env env, napi_callback_info) {
    return OwnedString(env, pdsh_host_workspace_watermark());
}

napi_value WorkspaceChanges(napi_env env, napi_callback_info info) {
    // Two decimal mseq strings, each far below this bound.
    return pdsh::QueueStringJob(env, info, "pdshhost.workspaceChanges",
        pdsh_host_workspace_changes, 32);
}

}

namespace pdsh {

bool RegisterArtifacts(napi_env env, napi_value exports) {
    napi_property_descriptor descriptors[] = {
        {"workspaceChanges", nullptr, WorkspaceChanges, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"workspaceWatermark", nullptr, WorkspaceWatermark, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    return napi_define_properties(env, exports,
        sizeof(descriptors) / sizeof(descriptors[0]), descriptors) == napi_ok;
}

}
