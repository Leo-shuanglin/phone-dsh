// SPDX-License-Identifier: MIT
package com.phonedsh.probe

import com.phonedsh.platform.SecureLookup
import com.phonedsh.platform.SecureStore
import com.phonedsh.platform.SecurityLevel
import com.phonedsh.ui.page.ProviderDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
        private val failure: Exception? = null,
    ) : ProviderProbeTransport {
        val requests = mutableListOf<ProviderProbeRequest>()

        override suspend fun execute(request: ProviderProbeRequest): ProviderProbeResponse {
            requests += request
            failure?.let { throw it }
            return ProviderProbeResponse(statuses[requests.lastIndex])
        }
    }

    private class FakeSecureStore(private val lookup: SecureLookup) : SecureStore {
        override val level: SecurityLevel = SecurityLevel.SOFTWARE
        override suspend fun put(key: String, value: ByteArray) = Unit
        override suspend fun get(key: String): SecureLookup = lookup
        override suspend fun delete(key: String) = Unit
    }
}
