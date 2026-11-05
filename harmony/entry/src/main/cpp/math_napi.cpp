// SPDX-License-Identifier: MIT
// native.renderMath(latex, optionsJson) -> Promise<string>
//
// The generic QueueStringJob helper owns the N-API async work, the two bounded
// string copies, the promise and the Rust-owned result. This file only binds
// the C ABI and registers the export; it does not duplicate N-API work.
#include "math_napi.h"
#include "native_async.h"

namespace {
constexpr size_t kMaxMathArgument = 64 * 1024;

napi_value RenderMath(napi_env env, napi_callback_info info) {
    return pdsh::QueueStringJob(
        env, info, "pdshhost.renderMath", pdsh_math_render_svg, kMaxMathArgument);
}
}

namespace pdsh {
void RegisterMath(napi_env env, napi_value exports) {
    napi_property_descriptor descriptor = {
        "renderMath", nullptr, RenderMath, nullptr, nullptr, nullptr, napi_default, nullptr
    };
    napi_define_properties(env, exports, 1, &descriptor);
}
}
