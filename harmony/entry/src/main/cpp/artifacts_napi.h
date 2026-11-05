// SPDX-License-Identifier: MIT
#ifndef PDSH_ARTIFACTS_NAPI_H
#define PDSH_ARTIFACTS_NAPI_H

#include <napi/native_api.h>

namespace pdsh {

// Registers the workspace artifact helpers on the existing pdshhost module.
// workspaceChanges is asynchronous on the NAPI worker pool; workspaceWatermark
// is a synchronous O(1) read of the cached native ACK.
bool RegisterArtifacts(napi_env env, napi_value exports);

}

#endif
