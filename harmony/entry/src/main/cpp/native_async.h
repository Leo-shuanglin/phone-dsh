// SPDX-License-Identifier: MIT
#ifndef PDSH_NATIVE_ASYNC_H
#define PDSH_NATIVE_ASYNC_H
#include <napi/native_api.h>
#include <memory>
#include <new>
#include <string>
#include <vector>
#include "pdsh_host.h"

namespace pdsh {
using StringJob = char *(*)(const char *, const char *);

inline bool ReadString(napi_env env, napi_value value, std::string &text, size_t limit) {
    napi_valuetype type;
    size_t length = 0;
    if (napi_typeof(env, value, &type) != napi_ok || type != napi_string ||
        napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok) {
        napi_throw_type_error(env, nullptr, "Expected a string");
        return false;
    }
    if (length > limit) {
        napi_throw_range_error(env, nullptr, "Native argument is too large");
        return false;
    }
    std::vector<char> bytes(length + 1);
    if (napi_get_value_string_utf8(env, value, bytes.data(), bytes.size(), &length) != napi_ok) return false;
    text.assign(bytes.data(), length);
    if (text.find('\0') != std::string::npos) {
        napi_throw_type_error(env, nullptr, "Native argument contains NUL");
        return false;
    }
    return true;
}

struct StringWork {
    napi_async_work work = nullptr;
    napi_deferred deferred = nullptr;
    StringJob job = nullptr;
    std::string first;
    std::string second;
    char *result = nullptr;
    ~StringWork() { if (result != nullptr) pdsh_host_free(result); }
};

inline void RejectStringWork(napi_env env, napi_deferred deferred, const char *message) {
    napi_value text, error;
    napi_create_string_utf8(env, message, NAPI_AUTO_LENGTH, &text);
    napi_create_error(env, nullptr, text, &error);
    napi_reject_deferred(env, deferred, error);
}

inline void ExecuteStringWork(napi_env, void *data) {
    auto *work = static_cast<StringWork *>(data);
    work->result = work->job(work->first.c_str(), work->second.c_str());
}

inline void CompleteStringWork(napi_env env, napi_status status, void *data) {
    std::unique_ptr<StringWork> work(static_cast<StringWork *>(data));
    napi_delete_async_work(env, work->work);
    if (status != napi_ok || work->result == nullptr) {
        RejectStringWork(env, work->deferred, "Native work did not complete");
        return;
    }
    napi_value value;
    if (napi_create_string_utf8(env, work->result, NAPI_AUTO_LENGTH, &value) != napi_ok) {
        RejectStringWork(env, work->deferred, "Cannot read native result");
        return;
    }
    napi_resolve_deferred(env, work->deferred, value);
}

inline napi_value QueueStringJob(napi_env env, napi_callback_info info, const char *name,
    StringJob job, size_t argumentLimit = 65536) {
    size_t count = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    if (count != 2) {
        napi_throw_type_error(env, nullptr, "Native operation requires two strings");
        return nullptr;
    }
    std::unique_ptr<StringWork> work(new (std::nothrow) StringWork());
    if (!work) { napi_throw_error(env, nullptr, "Cannot allocate native work"); return nullptr; }
    if (!ReadString(env, args[0], work->first, argumentLimit) ||
        !ReadString(env, args[1], work->second, argumentLimit)) return nullptr;
    napi_value promise, resource;
    if (napi_create_promise(env, &work->deferred, &promise) != napi_ok) return nullptr;
    napi_create_string_utf8(env, name, NAPI_AUTO_LENGTH, &resource);
    work->job = job;
    if (napi_create_async_work(env, nullptr, resource, ExecuteStringWork, CompleteStringWork,
        work.get(), &work->work) != napi_ok) {
        RejectStringWork(env, work->deferred, "Cannot create native work");
        return promise;
    }
    if (napi_queue_async_work(env, work->work) != napi_ok) {
        napi_delete_async_work(env, work->work);
        RejectStringWork(env, work->deferred, "Cannot queue native work");
        return promise;
    }
    work.release();
    return promise;
}
}
#endif
