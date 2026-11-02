// Navigation evidence for the PR22 grouped settings index.
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.page

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonedsh.ui.theme.PdshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        onNavigate: (SettingsDestination) -> Unit = {},
        onOpenAgentPresets: (() -> Unit)? = null,
        onOpenPhonePermissions: (() -> Unit)? = null,
        onOpenPluginsTools: (() -> Unit)? = null,
        onOpenFolders: (() -> Unit)? = null,
        onOpenArchivedSessions: (() -> Unit)? = null,
        onSelectDefaultPermission: ((PermissionTier) -> Unit)? = null,
    ) {
        compose.setContent {
            PdshTheme {
                SettingsPage(
                    state = SettingsScreenState.from(SettingsFacts()),
                    onNavigate = onNavigate,
                    onToggleBackgroundKeepAlive = {},
                    onSelectBusyEnterMode = {},
                    onBack = {},
                    onOpenAgentPresets = onOpenAgentPresets,
                    onOpenPhonePermissions = onOpenPhonePermissions,
                    onOpenPluginsTools = onOpenPluginsTools,
                    onOpenFolders = onOpenFolders,
                    onOpenArchivedSessions = onOpenArchivedSessions,
                    onSelectDefaultPermission = onSelectDefaultPermission,
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(com.phonedsh.ui.UiTags.SETTINGS_LIST)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
    }

    @Test
    fun `route destinations from all groups dispatch to host`() {
        val opened = mutableListOf<SettingsDestination>()
        show(onNavigate = { opened += it })
        val rows = listOf(
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "modelProvider" to SettingsDestination.MODEL_PROVIDER,
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "network" to SettingsDestination.EGRESS,
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "shortcuts" to SettingsDestination.SHORTCUTS,
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "runtimeDetails" to SettingsDestination.RUNTIME_DETAILS,
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "about" to SettingsDestination.ABOUT,
        )
        rows.forEach { (tag, destination) ->
            scrollTo(tag)
            compose.onNodeWithTag(tag).performClick()
            assertEquals(destination, opened.lastOrNull())
        }
        assertEquals(
            listOf(SettingsDestination.MODEL_PROVIDER, SettingsDestination.EGRESS,
                SettingsDestination.SHORTCUTS, SettingsDestination.RUNTIME_DETAILS, SettingsDestination.ABOUT),
            opened,
        )
    }

    @Test
    fun `optional host pages are live only when callbacks are supplied`() {
        val opened = mutableListOf<String>()
        show(
            onOpenAgentPresets = { opened += com.phonedsh.ui.nav.PdshSettingsRows.targetOf(com.phonedsh.ui.nav.PdshSettingsRows.HostRow.AGENT_PRESETS).toString() },
            onOpenPhonePermissions = { opened += com.phonedsh.ui.nav.PdshSettingsRows.targetOf(com.phonedsh.ui.nav.PdshSettingsRows.HostRow.PHONE_PERMISSIONS).toString() },
            onOpenPluginsTools = { opened += com.phonedsh.ui.nav.PdshSettingsRows.targetOf(com.phonedsh.ui.nav.PdshSettingsRows.HostRow.PLUGINS_TOOLS).toString() },
            onOpenFolders = { opened += com.phonedsh.ui.nav.PdshSettingsRows.targetOf(com.phonedsh.ui.nav.PdshSettingsRows.HostRow.FOLDERS).toString() },
            onOpenArchivedSessions = { opened += "archived" },
        )
        listOf(
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "agentPresets" to com.phonedsh.ui.nav.PdshRoute.AgentPresets.toString(),
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "phonePermissions" to com.phonedsh.ui.nav.PdshRoute.PhonePermissions.toString(),
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "pluginsTools" to com.phonedsh.ui.nav.PdshRoute.PluginInventory.toString(),
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "folders" to com.phonedsh.ui.nav.PdshRoute.WorkspaceFolders.toString(),
            com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "archivedSessions" to "archived",
        ).forEach { (tag, expected) ->
            scrollTo(tag)
            compose.onNodeWithTag(tag).performClick()
            assertEquals(expected, opened.last())
        }
        assertEquals(
            listOf(
                com.phonedsh.ui.nav.PdshRoute.AgentPresets.toString(),
                com.phonedsh.ui.nav.PdshRoute.PhonePermissions.toString(),
                com.phonedsh.ui.nav.PdshRoute.PluginInventory.toString(),
                com.phonedsh.ui.nav.PdshRoute.WorkspaceFolders.toString(),
                "archived",
            ),
            opened,
        )
    }

    @Test
    fun `default permission page updates through the explicit host writer`() {
        val selected = mutableListOf<PermissionTier>()
        show(onSelectDefaultPermission = { selected += it })
        scrollTo(com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "permissionDefaults")
        compose.onNodeWithTag(com.phonedsh.ui.page.UiPageTags.SETTINGS_INDEX_ROW_PREFIX + "permissionDefaults").performClick()
        compose.onNodeWithTag(PERMISSION_DEFAULTS_OPTION_PREFIX + PermissionTier.FULL_ACCESS.name).performClick()
        assertEquals(listOf(PermissionTier.FULL_ACCESS), selected)
    }

    @Test
    fun `deep link renders host provided page without restoring the tab picker`() {
        compose.setContent {
            PdshTheme {
                SettingsPage(
                    state = SettingsScreenState.from(SettingsFacts()),
                    onNavigate = {}, onToggleBackgroundKeepAlive = {}, onSelectBusyEnterMode = {}, onBack = {},
                    tab = SettingsTab.PERMISSIONS,
                    tabContent = { _: SettingsTab -> BasicText("deep-permission-page") },
                )
            }
        }
        compose.onNodeWithText("deep-permission-page").assertIsDisplayed()
        compose.onNodeWithText("模型与智能体").assertDoesNotExist()
    }
}
