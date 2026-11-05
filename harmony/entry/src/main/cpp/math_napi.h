// SPDX-License-Identifier: MIT
#ifndef PDSH_MATH_NAPI_H
#define PDSH_MATH_NAPI_H
#include <napi/native_api.h>

namespace pdsh {
// Registers native.renderMath(latex, optionsJson): Promise<string>.
void RegisterMath(napi_env env, napi_value exports);
}
#endif
