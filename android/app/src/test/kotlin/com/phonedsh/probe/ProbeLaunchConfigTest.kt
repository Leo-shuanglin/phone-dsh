// The current process binds one endpoint and credential account at launch.
// SPDX-License-Identifier: MIT
package com.phonedsh.probe

import com.phonedsh.ui.page.ProviderKeyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeLaunchConfigTest {
    @Test
    fun `stored launch profile captures matching endpoint model and credential account`() {
        val profile = provider("profile-a", "https://a.example.test/v1", "model-a")
        val launch = ProbeLaunchConfig.fromValues(extraBaseUrl = null, storedProfile = profile)

        assertEquals(profile.endpoint, launch.deepseekBaseUrl)
        assertEquals(profile.model, launch.modelName)
        assertEquals(profile.credentialAccount(), launch.credentialAccount)
        assertEquals(profile.id, launch.providerProfileId)
    }

    @Test
    fun `explicit launch endpoint does not inherit another profile key or model`() {
        val unrelated = provider("profile-b", "https://b.example.test/v1", "model-b")
        val launch = ProbeLaunchConfig.fromValues(
            extraBaseUrl = "https://override.example.test/v1",
            storedProfile = unrelated,
        )

        assertEquals("https://override.example.test/v1", launch.deepseekBaseUrl)
        assertEquals(ProbeLaunchConfig.DEFAULT_MODEL, launch.modelName)
        assertNull(launch.credentialAccount)
        assertNull(launch.providerProfileId)
        assertNull(launch.modelCredentialProvider())
        assertEquals(ProviderKeyStatus.NOT_CONFIGURED, launch.keyFact().status)
    }

    @Test
    fun `launch profile identity remains process-local when persisted selection changes later`() {
        val atProcessStart = provider("profile-a", "https://a.example.test/v1", "model-a")
        val laterSelected = provider("profile-b", "https://b.example.test/v1", "model-b")
        val launch = ProbeLaunchConfig.fromValues(extraBaseUrl = null, storedProfile = atProcessStart)

        // Selection writes change ProviderConfigStore; they do not mutate this launch object.
        val afterSelection = laterSelected
        assertEquals("profile-a", launch.providerProfileId)
        assertEquals("provider.profile-a", launch.credentialAccount)
        assertEquals("https://a.example.test/v1", launch.deepseekBaseUrl)
        assertEquals("profile-b", afterSelection.id)
    }

    private fun provider(id: String, endpoint: String, model: String) =
        ProviderRecord(
            id = id,
            name = id,
            model = model,
            endpoint = endpoint,
            credentialRef = ProviderRecord.providerCredentialRef(id),
        )
}
