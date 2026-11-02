// SPDX-License-Identifier: MIT
package com.phonedsh.probe

import com.phonedsh.agentcore.JsonArray
import com.phonedsh.agentcore.JsonBool
import com.phonedsh.agentcore.JsonNumber
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.agentcore.JsonString
import com.phonedsh.agentcore.JsonValue
import com.phonedsh.agentcore.toJsonString
import com.phonedsh.ui.page.ProviderDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A user-safe result for the provider editor; it never contains a key or response body. */
internal sealed interface ProviderConnectionTestResult {
    val message: String

    data class Success(override val message: String) : ProviderConnectionTestResult
    data class Failure(override val message: String) : ProviderConnectionTestResult
}

/** One real endpoint probe. Secret-bearing headers are intentionally absent from toString(). */
internal class ProviderProbeRequest(
    val url: URL,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    override fun toString(): String = "ProviderProbeRequest(POST ${url.path})"
}

internal data class ProviderProbeResponse(val statusCode: Int)

/** Typed save outcome, separating service-record persistence from a key-write failure. */
internal data class HostProviderSaveResult(
    val recordWritten: Boolean,
    val succeeded: Boolean,
    val message: String,
)

internal fun interface ProviderProbeTransport {
    suspend fun execute(request: ProviderProbeRequest): ProviderProbeResponse
}

/**
 * Test the same endpoint formats the iOS editor tests, with the draft key taking
 * precedence and stored credentials read only for an existing profile. The test
 * is a one-token ping; it never writes the key and never logs the request.
 */
internal suspend fun testProviderConnection(
    draft: ProviderDraft,
    keyProvider: () -> String? = { ApiKeyStore.active()?.readKey() },
    storedEndpointProvider: () -> String? = { ProviderConfigStore.active()?.current()?.endpoint },
    launchEndpointProvider: () -> String? = { ProbeLaunchConfig.active().deepseekBaseUrl },
    transport: ProviderProbeTransport = UrlConnectionProviderProbeTransport,
): ProviderConnectionTestResult = withContext(Dispatchers.IO) {
    val endpointText = draft.endpoint.trim().ifEmpty {
        if (draft.isNew) "" else storedEndpointProvider()?.trim().orEmpty().ifEmpty {
            launchEndpointProvider()?.trim().orEmpty()
        }
    }
    val endpoint = validateProbeEndpoint(endpointText)
        ?: return@withContext ProviderConnectionTestResult.Failure(endpointFailure(endpointText))

    val model = draft.model.trim()
    if (model.isEmpty()) {
        return@withContext ProviderConnectionTestResult.Failure("模型名是空的。")
    }

    val key = when {
        draft.apiKey.isNotBlank() -> draft.apiKey.trim()
        draft.isNew || draft.removeStoredKey -> ""
        else -> try {
            keyProvider().orEmpty()
        } catch (_: Exception) {
            return@withContext ProviderConnectionTestResult.Failure("读不到已保存的密钥；输入新密钥后可重新测试。")
        }
    }
    if (key.isEmpty()) {
        return@withContext ProviderConnectionTestResult.Failure("还没有可用于测试的密钥。")
    }
    if (key.length < ApiKeyStore.MIN_KEY_LENGTH || key.any { it !in '!'..'~' }) {
        return@withContext ProviderConnectionTestResult.Failure("密钥格式不符合要求；请检查后重试。")
    }

    val anthropicFirst = endpoint.toString().lowercase().contains("/anthropic")
    val attempts = if (anthropicFirst) listOf(ProviderProtocol.ANTHROPIC, ProviderProtocol.OPENAI)
    else listOf(ProviderProtocol.OPENAI, ProviderProtocol.ANTHROPIC)
    var lastStatus: Int? = null
    for (protocol in attempts) {
        val request = buildProviderProbeRequest(endpoint, protocol, model, key)
        val response = try {
            transport.execute(request)
        } catch (_: Exception) {
            return@withContext ProviderConnectionTestResult.Failure("连不上模型服务；请检查网络和接口地址。")
        }
        val status = response.statusCode
        lastStatus = status
        when (status) {
            in 200..299 -> return@withContext ProviderConnectionTestResult.Success(
                "连接正常（HTTP $status，${protocol.title}，${protocol.pathLabel}）。密钥可用。",
            )
            401, 403 -> return@withContext ProviderConnectionTestResult.Failure(
                "密钥被服务拒绝（HTTP $status）。请确认密钥与服务地址属于同一平台。",
            )
            429 -> return@withContext ProviderConnectionTestResult.Failure(
                "请求太频繁（HTTP 429）；服务已收到测试请求。",
            )
            in 300..399 -> return@withContext ProviderConnectionTestResult.Failure(
                "服务要求重定向（HTTP $status）；为保护密钥，测试未跟随跳转。",
            )
            else -> Unit // Try the other protocol, matching the iOS editor.
        }
    }
    ProviderConnectionTestResult.Failure(
        "测试失败（HTTP ${lastStatus ?: 0}）。请检查接口地址、协议和模型名。",
    )
}

private enum class ProviderProtocol(
    val title: String,
    val pathLabel: String,
) {
    ANTHROPIC("Anthropic Messages", "/v1/messages"),
    OPENAI("OpenAI 兼容", "/chat/completions"),
}

private fun validateProbeEndpoint(raw: String): URI? {
    if (raw.isBlank() || raw.any(Char::isWhitespace)) return null
    val uri = try {
        URI(raw)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    // The app's Network Security Config permits cleartext only to this exact
    // loopback host. Do not present a public HTTP endpoint as testable.
    if (scheme == "http" && uri.host != "127.0.0.1") return null
    return uri
}

private fun endpointFailure(raw: String): String {
    if (raw.isBlank()) return "接口地址是空的。"
    val uri = try { URI(raw) } catch (_: Exception) { null }
    if (uri?.userInfo != null) return "接口地址不能内嵌用户名或密码。"
    if (uri?.rawQuery != null || uri?.rawFragment != null) return "接口地址不能带 query 或 fragment。"
    if (uri?.scheme?.lowercase() == "http" && uri.host != "127.0.0.1") {
        return "公网模型服务必须使用 HTTPS；HTTP 仅允许 127.0.0.1。"
    }
    return "接口地址必须是可用的 HTTP(S) 绝对地址。"
}

private fun buildProviderProbeRequest(
    base: URI,
    protocol: ProviderProtocol,
    model: String,
    key: String,
): ProviderProbeRequest {
    val basePath = base.rawPath.orEmpty().trimEnd('/')
    val suffix = when (protocol) {
        ProviderProtocol.ANTHROPIC -> {
            val root = if (basePath.endsWith("/v1")) basePath else "$basePath/v1"
            "$root/messages"
        }
        ProviderProtocol.OPENAI -> "$basePath/chat/completions"
    }.ifEmpty { "/" }
    val url = URL("${base.scheme}://${base.rawAuthority}$suffix")
    val members = linkedMapOf<String, JsonValue>(
        "model" to JsonString(model),
        "messages" to JsonArray(
            listOf(JsonObject(mapOf("role" to JsonString("user"), "content" to JsonString("ping")))),
        ),
        "max_tokens" to JsonNumber("1"),
    )
    if (protocol == ProviderProtocol.OPENAI) members["stream"] = JsonBool(false)
    val body = JsonObject(members).toJsonString()
    val headers = linkedMapOf(
        "Content-Type" to "application/json",
    )
    if (protocol == ProviderProtocol.ANTHROPIC) {
        headers["x-api-key"] = key
        headers["anthropic-version"] = "2023-06-01"
    }
    headers["Authorization"] = "Bearer $key"
    return ProviderProbeRequest(url, headers, body.toByteArray(Charsets.UTF_8))
}

private object UrlConnectionProviderProbeTransport : ProviderProbeTransport {
    override suspend fun execute(request: ProviderProbeRequest): ProviderProbeResponse = withContext(Dispatchers.IO) {
        val connection = request.url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Connection", "close")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { output -> output.write(request.body) }
            ProviderProbeResponse(connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }
}
