// SPDX-License-Identifier: MIT
package com.phonedsh.probe

import com.phonedsh.platform.SecureLookup
import com.phonedsh.platform.SecureStore
import com.phonedsh.platform.SecurityLevel
import com.phonedsh.ui.page.ProviderDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.net.URL

class HostProviderConnectionTest {
    private val key = "sk-test-NOT-A-REAL-KEY-0000"

    @Test
    fun `openai probe uses the ios path header and one token ping`() = runBlocking {
        val transport = RecordingTransport(listOf(200))
        val result = testProviderConnection(
            draft = draft(endpoint = "https://api.example.test/v1", key = key),
            storedEndpointProvider = { null },
            launchEndpointProvider = { null },
            transport = transport,
        )
        val request = transport.requests.single()
        assertTrue(result is ProviderConnectionTestResult.Success)
        assertEquals("https://api.example.test/v1/chat/completions", request.url.toString())
        assertEquals("Bearer $key", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        val body = request.body.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"max_tokens\":1"))
        assertTrue(body.contains("\"content\":\"ping\""))
        assertFalse((result as ProviderConnectionTestResult.Success).message.contains(key))
    }

    @Test
    fun `anthropic probe avoids a duplicated v1 and uses the messages headers`() = runBlocking {
        val transport = RecordingTransport(listOf(200))
        val result = testProviderConnection(
            draft = draft(endpoint = "https://api.example.test/anthropic/v1", key = key),
            storedEndpointProvider = { null },
            launchEndpointProvider = { null },
            transport = transport,
        )
        val request = transport.requests.single()
        assertTrue(result is ProviderConnectionTestResult.Success)
        assertEquals("https://api.example.test/anthropic/v1/messages", request.url.toString())
        assertEquals(key, request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("Bearer $key", request.headers["Authorization"])
    }

    @Test
    fun `a not found falls through to the alternate protocol without exposing response data`() = runBlocking {
        val transport = RecordingTransport(listOf(404, 200))
        val result = testProviderConnection(
            draft = draft(endpoint = "https://api.example.test/service", key = key),
            storedEndpointProvider = { null },
            launchEndpointProvider = { null },
            transport = transport,
        )
        assertTrue(result is ProviderConnectionTestResult.Success)
        assertEquals(2, transport.requests.size)
        assertEquals("https://api.example.test/service/chat/completions", transport.requests[0].url.toString())
        assertEquals("https://api.example.test/service/v1/messages", transport.requests[1].url.toString())
    }

    @Test
    fun `an existing provider reads its stored key only when the editor has no replacement`() = runBlocking {
        val secure = ApiKeyStore(FakeSecureStore(SecureLookup.Found(key.toByteArray())))
        val transport = RecordingTransport(listOf(200))
        val result = testProviderConnection(
            draft = draft(endpoint = "https://api.example.test", key = "", isNew = false),
            keyProvider = { secure.readKey() },
            storedEndpointProvider = { null },
            launchEndpointProvider = { null },
            transport = transport,
        )
        assertTrue(result is ProviderConnectionTestResult.Success)
        assertEquals("Bearer $key", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun `new providers never borrow an unrelated stored key`() = runBlocking {
        val secure = ApiKeyStore(FakeSecureStore(SecureLookup.Found(key.toByteArray())))
        var reads = 0
        val transport = RecordingTransport(listOf(200))
        val result = testProviderConnection(
            draft = draft(endpoint = "https://api.example.test", key = "", isNew = true),
            keyProvider = { reads += 1; secure.readKey() },
            storedEndpointProvider = { null },
            launchEndpointProvider = { null },
            transport = transport,
        )
        assertTrue(result is ProviderConnectionTestResult.Failure)
        assertEquals("还没有可用于测试的密钥。", result.message)
        assertEquals(0, reads)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `embedded credentials query strings and public cleartext are rejected before sending`() = runBlocking {
        val invalidEndpoints = listOf(
            "https://user:pass@api.example.test/v1",
            "https://api.example.test/v1?token=secret",
            "http://api.example.test/v1",
        )
        invalidEndpoints.forEach { endpoint ->
            val transport = RecordingTransport(listOf(200))
            val result = testProviderConnection(
                draft = draft(endpoint = endpoint, key = key),
                storedEndpointProvider = { null },
                launchEndpointProvider = { null },
                transport = transport,
            )
            assertTrue(result is ProviderConnectionTestResult.Failure)
            assertFalse(result.message.contains("user"))
            assertFalse(result.message.contains("pass"))
            assertFalse(result.message.contains("secret"))
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun `the only cleartext probe accepted by app policy is loopback`() = runBlocking {
        val transport = RecordingTransport(listOf(200))
        val result = testProviderConnection(
            draft = draft(endpoint = "http://127.0.0.1:8000/v1", key = key),
            storedEndpointProvider = { null },
            launchEndpointProvider = { null },
            transport = transport,
        )
        assertTrue(result is ProviderConnectionTestResult.Success)
        assertEquals("http://127.0.0.1:8000/v1/chat/completions", transport.requests.single().url.toString())
    }

    @Test
    fun `get models uses native get and binds result to its request and profile`() = runBlocking {
        val transport = RecordingTransport(
            statuses = listOf(200),
            bodies = listOf("""{"data":[{"id":"model-a"},{"id":"model-b"},{"id":"model-a"}]}"""),
        )
        val result = getProviderModels(
            draft = draft(endpoint = "https://api.example.test/anthropic", key = key, isNew = false),
            requestId = "models-request-7",
            transport = transport,
        )
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://api.example.test/anthropic/models", request.url.toString())
        assertEquals("Bearer $key", request.headers["Authorization"])
        assertEquals(key, request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertArrayEquals(byteArrayOf(), request.body)
        assertEquals("models-request-7", result.requestId)
        assertEquals("saved-profile", result.profileId)
        assertEquals(listOf("model-a", "model-b"), result.models)
        assertTrue(result.succeeded)
        assertFalse(result.message.contains(key))
    }

    @Test
    fun `provider test result keeps request and profile identity`() = runBlocking {
        val result = testProviderConnectionBound(
            draft(endpoint = "https://api.example.test/v1", key = key, isNew = false),
            requestId = "test-request-2",
            transport = RecordingTransport(listOf(200)),
        )
        assertEquals("test-request-2", result.requestId)
        assertEquals("saved-profile", result.profileId)
        assertTrue(result.result is ProviderConnectionTestResult.Success)
    }

    @Test
    fun `provider save receipt follows a real store write and readback`() = runBlocking {
        val file = File(Files.createTempDirectory("provider-bound-save").toFile(), ProviderConfigStore.FILE_NAME)
        val store = ProviderConfigStore(file) { }
        val result = saveProviderDraft(
            draft = ProviderDraft(
                id = "", name = "Fixture service", endpoint = "https://api.example.test/v1",
                model = "fixture-model", models = listOf(com.phonedsh.ui.page.ProviderModelDraft("fixture-model")),
                apiKey = "", isNew = true,
            ),
            requestId = "save-request-9",
            store = store,
            keyStore = null,
            launch = ProbeLaunchConfig(modelName = "launch-model"),
        )
        assertTrue(result.succeeded)
        assertTrue(result.recordWritten)
        assertEquals("save-request-9", result.requestId)
        assertEquals("local", result.profileId)
        assertEquals("fixture-model", store.current()?.model)
    }

    @Test
    fun `provider profile write is reported as partial when key write fails`() = runBlocking {
        val file = File(Files.createTempDirectory("provider-partial-save").toFile(), ProviderConfigStore.FILE_NAME)
        val store = ProviderConfigStore(file) { }
        val keyStore = ApiKeyStore(MutableFailingSecureStore())
        val result = saveProviderDraft(
            draft = ProviderDraft(
                id = "", name = "Fixture service", endpoint = "https://api.example.test/v1",
                model = "fixture-model", models = listOf(com.phonedsh.ui.page.ProviderModelDraft("fixture-model")),
                apiKey = key, isNew = true,
            ),
            requestId = "save-request-partial",
            store = store,
            keyStore = keyStore,
        )
        assertFalse(result.succeeded)
        assertTrue("profile write is called out separately", result.recordWritten)
        assertEquals("save-request-partial", result.requestId)
        assertEquals("local", result.profileId)
        assertEquals("Fixture service", store.current()?.name)
        assertTrue(result.message.contains("密钥写入失败"))
    }

    @Test
    fun `provider save rejection retains request identity and writes no record`() = runBlocking {
        val file = File(Files.createTempDirectory("provider-bound-reject").toFile(), ProviderConfigStore.FILE_NAME)
        val store = ProviderConfigStore(file) { }
        val result = saveProviderDraft(
            draft = ProviderDraft(
                id = "new-profile", name = "Fixture service", endpoint = "https://user:pass@api.example.test",
                model = "fixture-model", models = listOf(com.phonedsh.ui.page.ProviderModelDraft("fixture-model")),
                apiKey = "", isNew = true,
            ),
            requestId = "save-request-bad",
            store = store,
            keyStore = null,
        )
        assertFalse(result.succeeded)
        assertFalse(result.recordWritten)
        assertEquals("save-request-bad", result.requestId)
        assertEquals("new-profile", result.profileId)
        assertNull(store.current())
    }

    @Test
    fun `get models does not read stored key for a new profile and rejects an empty catalog`() = runBlocking {
        var keyReads = 0
        val transport = RecordingTransport(listOf(200), listOf("""{"data":[]}"""))
        val result = getProviderModels(
            draft = draft(endpoint = "https://api.example.test/v1", key = "", isNew = true),
            requestId = "models-request-new",
            keyProvider = { keyReads++; key },
            transport = transport,
        )
        assertEquals(0, keyReads)
        assertTrue(transport.requests.isEmpty())
        assertNull(result.models)
        assertEquals("models-request-new", result.requestId)
        assertNull(result.profileId)
        assertFalse(result.succeeded)
    }

    @Test
    fun `anthropic model lookup retries the origin catalog only after profile path 404`() = runBlocking {
        val transport = RecordingTransport(
            statuses = listOf(404, 200),
            bodies = listOf("", """{"data":[{"id":"deepseek-chat"}]}"""),
        )
        val result = getProviderModels(
            draft(endpoint = "https://api.example.test/anthropic", key = key, isNew = false),
            requestId = "models-anthropic-fallback",
            transport = transport,
        )
        assertEquals(
            listOf("https://api.example.test/anthropic/models", "https://api.example.test/models"),
            transport.requests.map { it.url.toString() },
        )
        assertTrue(transport.requests.all { it.headers["x-api-key"] == key })
        assertEquals(listOf("deepseek-chat"), result.models)
    }

    @Test
    fun `get models rejects malformed catalogs and never returns upstream error text`() = runBlocking {
        val transport = RecordingTransport(listOf(502), listOf("provider secret payload"))
        val result = getProviderModels(
            draft(endpoint = "https://api.example.test/v1", key = key),
            requestId = "models-failed",
            transport = transport,
        )
        assertNull(result.models)
        assertFalse(result.message.contains("provider secret payload"))

        val malformed = getProviderModels(
            draft(endpoint = "https://api.example.test/v1", key = key),
            requestId = "models-malformed",
            transport = RecordingTransport(listOf(200), listOf("""{"models":[{"name":"fake"}]}""")),
        )
        assertNull(malformed.models)
        assertEquals("models-malformed", malformed.requestId)
    }

    @Test
    fun `transport exceptions are reported without echoing a key or raw exception`() = runBlocking {
        val transport = RecordingTransport(emptyList(), failure = IllegalStateException("failed for $key"))
        val result = testProviderConnection(
            draft = draft(endpoint = "https://api.example.test", key = key),
            storedEndpointProvider = { null },
            launchEndpointProvider = { null },
            transport = transport,
        )
        assertTrue(result is ProviderConnectionTestResult.Failure)
        assertFalse(result.message.contains(key))
        assertFalse(result.message.contains("failed for"))
    }

    private fun draft(endpoint: String, key: String, isNew: Boolean = true) = ProviderDraft(
        id = if (isNew) "" else "saved-profile",
        name = "Fake provider",
        endpoint = endpoint,
        model = "fake-model",
        apiKey = key,
        isNew = isNew,
    )

    private class RecordingTransport(
        private val statuses: List<Int>,
        private val bodies: List<String> = emptyList(),
        private val failure: Exception? = null,
    ) : ProviderProbeTransport {
        val requests = mutableListOf<ProviderProbeRequest>()

        override suspend fun execute(request: ProviderProbeRequest): ProviderProbeResponse {
            requests += request
            failure?.let { throw it }
            val index = requests.lastIndex
            return ProviderProbeResponse(statuses[index], bodies.getOrNull(index).orEmpty())
        }
    }

    private class FakeSecureStore(private val lookup: SecureLookup) : SecureStore {
        override val level: SecurityLevel = SecurityLevel.SOFTWARE
        override suspend fun put(key: String, value: ByteArray) = Unit
        override suspend fun get(key: String): SecureLookup = lookup
        override suspend fun delete(key: String) = Unit
    }

    private class MutableFailingSecureStore : SecureStore {
        override val level: SecurityLevel = SecurityLevel.SOFTWARE
        override suspend fun put(key: String, value: ByteArray) { throw IllegalStateException("fixture write failure") }
        override suspend fun get(key: String): SecureLookup = SecureLookup.Absent
        override suspend fun delete(key: String) = Unit
    }
}
