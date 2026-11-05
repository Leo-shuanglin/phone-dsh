// SPDX-License-Identifier: MIT

package com.phonedsh.probe

import com.phonedsh.gateway.OfficialDeepSeekWebTools

internal data class OfficialWebSearchLaunch(
    val credentialAccount: String,
    val environment: Map<String, String>,
) {
    companion object {
        fun resolve(
            baseUrl: String?,
            proxyRequested: Boolean,
            credentialAccount: String?,
            keyState: ApiKeyState?,
        ): OfficialWebSearchLaunch? {
            if (!proxyRequested) return null
            if (!OfficialDeepSeekWebTools.isOfficialModelBase(baseUrl)) return null
            val account = credentialAccount?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (keyState !is ApiKeyState.Valid) return null
            return OfficialWebSearchLaunch(
                credentialAccount = account,
                environment = OfficialDeepSeekWebTools.workerEnvironment(),
            )
        }
    }
}
