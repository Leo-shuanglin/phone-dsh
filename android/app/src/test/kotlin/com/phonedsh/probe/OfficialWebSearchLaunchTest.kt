// phone-dsh — :app tests: the automatic official Search/Fetch launch decision.
// SPDX-License-Identifier: MIT
//
// The frozen contract makes the selected official model key the only user
// input. These cases pin the refusals as hard as the enablement: a direct
// diagnostic launch, an absent or unreadable key, and a non-official model base
// must not publish either provider.
package com.phonedsh.probe

import com.phonedsh.gateway.OfficialDeepSeekWebTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialWebSearchLaunchTest {
    private val officialBase: String = "https://api.deepseek.com/anthropic"
    private val account: String = "provider.official"

    @Test
    fun anOfficialModelWithAReadableSavedKeyEnablesBothTools() {
        val launch = OfficialWebSearchLaunch.resolve(
            baseUrl = officialBase,
            proxyRequested = true,
            credentialAccount = account,
            keyState = ApiKeyState.Valid("sk-not-a-real-key"),
        )

        requireNotNull(launch)
        assertEquals(account, launch.credentialAccount)
        assertEquals(OfficialDeepSeekWebTools.SEARCH_PROVIDER, launch.environment["PDSH_WEB_SEARCH_PROVIDER"])
        assertEquals(OfficialDeepSeekWebTools.FETCH_PROVIDER, launch.environment["PDSH_WEB_FETCH_PROVIDER"])
        assertEquals(
            "https://api.deepseek.com/v1/tools",
            launch.environment["DEEPSEEK_SEARCH_API_BASE_URL"],
        )
        assertEquals(
            "the Worker gets the placeholder, never the model key",
            OfficialDeepSeekWebTools.PLACEHOLDER,
            launch.environment["DEEPSEEK_SEARCH_API_KEY"],
        )
        assertFalse(launch.environment.values.joinToString(" ").contains("sk-not-a-real-key"))
    }

    @Test
    fun aDebugDirectLaunchNeverEnablesTheGrant() {
        assertNull(
            "pdsh.model.proxy=false has no host edge to inject the key",
            OfficialWebSearchLaunch.resolve(
                baseUrl = officialBase,
                proxyRequested = false,
                credentialAccount = account,
                keyState = ApiKeyState.Valid("sk-not-a-real-key"),
            ),
        )
    }

    @Test
    fun aMissingKeyDoesNotEnableTheGrant() {
        assertNull(
            OfficialWebSearchLaunch.resolve(
                baseUrl = officialBase,
                proxyRequested = true,
                credentialAccount = account,
                keyState = ApiKeyState.Absent,
            ),
        )
    }

    @Test
    fun anUnreadableOrInvalidKeyDoesNotEnableTheGrant() {
        for (state in listOf(
            ApiKeyState.Unreadable("keystore-entry-gone"),
            ApiKeyState.Invalid("blank-key"),
        )) {
            assertNull(
                "$state must not enable the grant",
                OfficialWebSearchLaunch.resolve(
                    baseUrl = officialBase,
                    proxyRequested = true,
                    credentialAccount = account,
                    keyState = state,
                ),
            )
        }
    }

    @Test
    fun aLaunchWithoutASelectedAccountDoesNotEnableTheGrant() {
        assertNull(
            "a debug extra has no saved key account",
            OfficialWebSearchLaunch.resolve(
                baseUrl = officialBase,
                proxyRequested = true,
                credentialAccount = null,
                keyState = ApiKeyState.Valid("sk-not-a-real-key"),
            ),
        )
    }

    @Test
    fun everyNonOfficialModelBaseDoesNotEnableTheGrant() {
        for (base in listOf(
            "https://api.deepseek.com/v2",
            "https://api.deepseek.com:8443/v1",
            "http://api.deepseek.com/v1",
            "https://api.deepseek.com.example.invalid/v1",
            "https://user@api.deepseek.com/v1",
            "https://api.deepseek.com/v1?model=x",
            "https://api.deepseek.com/v1#fragment",
            "https://api.deepseek.com/v1/../v1",
            null,
        )) {
            assertNull(
                "$base must not enable the official Search/Fetch grant",
                OfficialWebSearchLaunch.resolve(
                    baseUrl = base,
                    proxyRequested = true,
                    credentialAccount = account,
                    keyState = ApiKeyState.Valid("sk-not-a-real-key"),
                ),
            )
        }
    }

    @Test
    fun everyFrozenSupportedModelBaseEnablesTheGrant() {
        for (base in listOf(
            "https://api.deepseek.com",
            "https://api.deepseek.com/v1",
            "https://API.DEEPSEEK.COM:443/anthropic/",
            "https://api.deepseek.com/anthropic/v1",
        )) {
            assertTrue(
                "$base must enable the grant",
                OfficialWebSearchLaunch.resolve(
                    baseUrl = base,
                    proxyRequested = true,
                    credentialAccount = account,
                    keyState = ApiKeyState.Valid("sk-not-a-real-key"),
                ) != null,
            )
        }
    }
}
