// SPDX-License-Identifier: MIT
#include <napi/native_api.h>
#include <hilog/log.h>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>
#include "artifacts_napi.h"
#include "pdsh_host.h"
#include "math_napi.h"

namespace {
struct Bridge {
    napi_threadsafe_function callback = nullptr;
    std::mutex mutex;
};
Bridge bridge;

bool StringArg(napi_env env, napi_value value, std::string &out) {
    napi_valuetype type;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_string) {
        napi_throw_type_error(env, nullptr, "Expected a string");
        return false;
    }
    size_t length = 0;
    napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    if (length > 4 * 1024 * 1024) {
        napi_throw_range_error(env, nullptr, "Native argument exceeds 4 MiB");
        return false;
    }
    std::vector<char> bytes(length + 1);
    napi_get_value_string_utf8(env, value, bytes.data(), bytes.size(), &length);
    out.assign(bytes.data(), length);
    if (out.find('\0') != std::string::npos) {
        napi_throw_type_error(env, nullptr, "Native string contains NUL");
        return false;
    }
    return true;
}

napi_value Undefined(napi_env env) {
    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

napi_value OwnedString(napi_env env, char *text) {
    if (text == nullptr) {
        napi_throw_error(env, nullptr, "Native host returned no result");
        return nullptr;
    }
    napi_value result;
    napi_create_string_utf8(env, text, NAPI_AUTO_LENGTH, &result);
    pdsh_host_free(text);
    return result;
}

struct AsyncOp {
    napi_async_work work = nullptr;
    napi_deferred deferred = nullptr;
    std::string first;
    std::string second;
    char *result = nullptr;
    bool install = false;
};

void RejectDeferred(napi_env env, napi_deferred deferred, const char *message) {
    napi_value text = nullptr;
    napi_value reason = nullptr;
    if (napi_create_string_utf8(env, message, NAPI_AUTO_LENGTH, &text) == napi_ok) {
        reason = text;
        napi_value error = nullptr;
        if (napi_create_error(env, nullptr, text, &error) == napi_ok) {
            reason = error;
        }
    } else {
        napi_get_undefined(env, &reason);
    }
    if (reason != nullptr) napi_reject_deferred(env, deferred, reason);
}

// Runs on the NAPI worker pool. NAPI calls are forbidden here; the Rust host
// does the 30 MiB archive extraction and WAL recovery on this thread.
void ExecuteAsync(napi_env, void *data) {
    AsyncOp *op = static_cast<AsyncOp *>(data);
    if (op->install) {
        op->result = pdsh_host_install_bundle(op->first.c_str(), op->second.c_str());
    } else {
        op->result = pdsh_host_start(op->first.c_str(), op->second.c_str());
    }
}

bool ParseInstallResult(napi_env env, const char *text, bool &ok, std::string &error) {
    napi_value global = nullptr, json = nullptr, parse = nullptr, argument = nullptr, object = nullptr;
    if (napi_get_global(env, &global) != napi_ok ||
        napi_get_named_property(env, global, "JSON", &json) != napi_ok ||
        napi_get_named_property(env, json, "parse", &parse) != napi_ok ||
        napi_create_string_utf8(env, text, NAPI_AUTO_LENGTH, &argument) != napi_ok) {
        return false;
    }
    if (napi_call_function(env, json, parse, 1, &argument, &object) != napi_ok) {
        napi_value ignored = nullptr;
        napi_get_and_clear_last_exception(env, &ignored);
        return false;
    }
    napi_value okValue = nullptr;
    if (napi_get_named_property(env, object, "ok", &okValue) != napi_ok ||
        napi_get_value_bool(env, okValue, &ok) != napi_ok) {
        return false;
    }
    if (ok) return true;
    napi_value errorValue = nullptr;
    if (napi_get_named_property(env, object, "error", &errorValue) != napi_ok) return true;
    napi_valuetype type;
    if (napi_typeof(env, errorValue, &type) != napi_ok || type != napi_string) return true;
    size_t length = 0;
    if (napi_get_value_string_utf8(env, errorValue, nullptr, 0, &length) != napi_ok || length == 0) {
        return true;
    }
    std::vector<char> bytes(length + 1);
    if (napi_get_value_string_utf8(env, errorValue, bytes.data(), bytes.size(), &length) != napi_ok) {
        return true;
    }
    error.assign(bytes.data(), length);
    return true;
}

// Runs on the ArkTS thread: parse the Rust JSON, resolve only after `ok` is
// checked, and always release the Rust-owned string.
void CompleteAsync(napi_env env, napi_status status, void *data) {
    std::unique_ptr<AsyncOp> op(static_cast<AsyncOp *>(data));
    if (op->work != nullptr) {
        napi_delete_async_work(env, op->work);
        op->work = nullptr;
    }
    std::unique_ptr<char, void (*)(char *)> result(op->result, pdsh_host_free);
    op->result = nullptr;
    if (status != napi_ok) {
        RejectDeferred(env, op->deferred, "Native host work was cancelled");
        return;
    }
    if (result.get() == nullptr) {
        RejectDeferred(env, op->deferred, "Native host returned no result");
        return;
    }
    if (op->install) {
        bool ok = false;
        std::string error;
        if (!ParseInstallResult(env, result.get(), ok, error)) {
            RejectDeferred(env, op->deferred, "Native runtime bundle installation result was invalid");
            return;
        }
        if (!ok) {
            RejectDeferred(env, op->deferred,
                error.empty() ? "Native runtime bundle installation failed" : error.c_str());
            return;
        }
        napi_value undefined = nullptr;
        napi_get_undefined(env, &undefined);
        napi_resolve_deferred(env, op->deferred, undefined);
        return;
    }
    napi_value value = nullptr;
    if (napi_create_string_utf8(env, result.get(), NAPI_AUTO_LENGTH, &value) != napi_ok) {
        RejectDeferred(env, op->deferred, "Cannot read native host start result");
        return;
    }
    napi_resolve_deferred(env, op->deferred, value);
}

napi_value RunAsync(napi_env env, napi_callback_info info, bool install) {
    size_t count = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    if (count != 2) {
        napi_throw_type_error(env, nullptr,
            install ? "installBundle needs archive and destination" : "start needs filesRoot and assetsRoot");
        return nullptr;
    }
    std::string first, second;
    if (!StringArg(env, args[0], first) || !StringArg(env, args[1], second)) return nullptr;
    napi_value promise = nullptr;
    napi_deferred deferred = nullptr;
    if (napi_create_promise(env, &deferred, &promise) != napi_ok) {
        napi_throw_error(env, nullptr, "Cannot create native host promise");
        return nullptr;
    }
    napi_value resourceName = nullptr;
    napi_create_string_utf8(env, install ? "pdshhost.installBundle" : "pdshhost.start",
        NAPI_AUTO_LENGTH, &resourceName);
    std::unique_ptr<AsyncOp> op = std::make_unique<AsyncOp>();
    op->deferred = deferred;
    op->first = std::move(first);
    op->second = std::move(second);
    op->install = install;
    if (napi_create_async_work(env, nullptr, resourceName, ExecuteAsync, CompleteAsync, op.get(), &op->work) != napi_ok) {
        RejectDeferred(env, deferred, "Cannot create native host work");
        return promise;
    }
    if (napi_queue_async_work(env, op->work) != napi_ok) {
        napi_delete_async_work(env, op->work);
        op->work = nullptr;
        RejectDeferred(env, deferred, "Cannot queue native host work");
        return promise;
    }
    op.release();
    return promise;
}

napi_value Start(napi_env env, napi_callback_info info) {
    return RunAsync(env, info, false);
}

napi_value Stop(napi_env env, napi_callback_info) {
    pdsh_host_stop();
    return Undefined(env);
}

napi_value Status(napi_env env, napi_callback_info) {
    return OwnedString(env, pdsh_host_status());
}

void Deliver(napi_env env, napi_value callback, void *, void *data) {
    std::unique_ptr<std::string> event(static_cast<std::string *>(data));
    if (env == nullptr || callback == nullptr) return;
    napi_value arg, receiver, ignored;
    napi_get_undefined(env, &receiver);
    napi_create_string_utf8(env, event->data(), event->size(), &arg);
    napi_call_function(env, receiver, callback, 1, &arg, &ignored);
}

void OnEvent(const char *text, void *) {
    if (text == nullptr || std::strlen(text) > 8 * 1024 * 1024) return;
    try {
        auto event = std::make_unique<std::string>(text);
        std::lock_guard<std::mutex> lock(bridge.mutex);
        if (bridge.callback != nullptr) {
            napi_status sent = napi_call_threadsafe_function(bridge.callback, event.get(), napi_tsfn_nonblocking);
            if (sent == napi_ok) event.release();
            else OH_LOG_Print(LOG_APP, LOG_ERROR, 0x1A21, "PhoneDSHHost", "native event queue unavailable");
        }
    } catch (...) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, 0x1A21, "PhoneDSHHost", "native event allocation failed");
    }
}

napi_value SetEventCallback(napi_env env, napi_callback_info info) {
    size_t count = 1;
    napi_value arg;
    napi_get_cb_info(env, info, &count, &arg, nullptr, nullptr);
    napi_valuetype type;
    if (count != 1 || napi_typeof(env, arg, &type) != napi_ok || type != napi_function) {
        napi_throw_type_error(env, nullptr, "setEventCallback needs a function");
        return nullptr;
    }
    napi_value name;
    napi_create_string_utf8(env, "PhoneDSHHostEvents", NAPI_AUTO_LENGTH, &name);
    {
        std::lock_guard<std::mutex> lock(bridge.mutex);
        if (bridge.callback != nullptr) napi_release_threadsafe_function(bridge.callback, napi_tsfn_abort);
        bridge.callback = nullptr;
        napi_status result = napi_create_threadsafe_function(env, arg, nullptr, name, 1024, 1, nullptr, nullptr,
            nullptr, Deliver, &bridge.callback);
        if (result != napi_ok) {
            napi_throw_error(env, nullptr, "Cannot register native host events");
            return nullptr;
        }
    }
    pdsh_host_set_callback(OnEvent, nullptr);
    return Undefined(env);
}

napi_value Respond(napi_env env, napi_callback_info info) {
    size_t count = 5;
    napi_value args[5];
    napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    if (count != 5) {
        napi_throw_type_error(env, nullptr, "respond needs id, kind, status, headers and body");
        return nullptr;
    }
    int64_t id = 0;
    int32_t kind = 0, status = 0;
    if (napi_get_value_int64(env, args[0], &id) != napi_ok || id < 0 ||
        napi_get_value_int32(env, args[1], &kind) != napi_ok || kind < 0 || kind > 3 ||
        napi_get_value_int32(env, args[2], &status) != napi_ok) {
        napi_throw_type_error(env, nullptr, "Invalid native response identity");
        return nullptr;
    }
    std::string headers;
    if (!StringArg(env, args[3], headers)) return nullptr;
    bool arrayBuffer = false;
    napi_is_arraybuffer(env, args[4], &arrayBuffer);
    void *bytes = nullptr;
    size_t length = 0;
    if (!arrayBuffer || napi_get_arraybuffer_info(env, args[4], &bytes, &length) != napi_ok || length > 4 * 1024 * 1024) {
        napi_throw_type_error(env, nullptr, "Response body must be an ArrayBuffer up to 4 MiB");
        return nullptr;
    }
    if (kind == 1 && length > 256 * 1024) {
        napi_throw_range_error(env, nullptr, "Native response chunk exceeds 256 KiB");
        return nullptr;
    }
    pdsh_host_respond(static_cast<uint64_t>(id), kind, status, headers.c_str(), static_cast<const uint8_t *>(bytes), length);
    return Undefined(env);
}

napi_value InstallBundle(napi_env env, napi_callback_info info) {
    return RunAsync(env, info, true);
}

void Cleanup(void *) {
    pdsh_host_set_callback(nullptr, nullptr);
    {
        std::lock_guard<std::mutex> lock(bridge.mutex);
        if (bridge.callback != nullptr) napi_release_threadsafe_function(bridge.callback, napi_tsfn_abort);
        bridge.callback = nullptr;
    }
    pdsh_host_stop();
}

napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor descriptors[] = {
        {"start", nullptr, Start, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"stop", nullptr, Stop, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"status", nullptr, Status, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setEventCallback", nullptr, SetEventCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"respond", nullptr, Respond, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"installBundle", nullptr, InstallBundle, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(descriptors) / sizeof(descriptors[0]), descriptors);
    if (!pdsh::RegisterArtifacts(env, exports)) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, 0x1A21, "PhoneDSHHost", "native artifact registration failed");
    }

    pdsh::RegisterMath(env, exports);
    napi_add_env_cleanup_hook(env, Cleanup, nullptr);
    return exports;
}
}

static napi_module module = {1, 0, nullptr, Init, "pdshhost", nullptr, {0}};
extern "C" __attribute__((constructor)) void RegisterPhoneDSHHost() {
    napi_module_register(&module);
}
