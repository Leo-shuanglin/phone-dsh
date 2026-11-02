// NativeConfigurationPage: four states, the plugin-inventory unknown rule and folds.
// SPDX-License-Identifier: MIT
//
// The unmeasured plugin list is located with `performScrollToNode` before the
// test asserts that no `0 个条目` was invented: the section is a lazy item and
// starts collapsed, so a bare absence assertion would be about the window, not
// about the page.
package com.phonedsh.ui.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.theme.PdshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h640dp")
class NativeConfigurationPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun fullFacts(): ConfigurationFacts = ConfigurationFacts(
        snapshot = ConfigurationSnapshot(
            sessionTitle = "修复登录",
            settingsWritable = FactState.YES,
            groups = listOf(
                ConfigurationGroup(
                    namespace = "agent-loop",
                    title = "自动执行轮次",
                    detail = "控制自动执行的轮次上限",
                    writable = FactState.YES,
                    fields = listOf(
                        ConfigurationField(
                            id = "maxTurns",
                            title = "最大轮次",
                            value = "12",
                            detail = "超过后停止自动执行",
                        ),
                        ConfigurationField(
                            id = "autoContinue",
                            title = "自动继续",
                            value = "关闭",
                        ),
                    ),
                ),
                ConfigurationGroup(
                    namespace = "bash-sandbox",
                    title = "终端",
                    detail = "命令在沙箱里执行",
                    writable = FactState.NO,
                    fields = listOf(
                        ConfigurationField(id = "sandbox", title = "沙箱", value = "开启"),
                        ConfigurationField(id = "network", title = "网络", value = "白名单"),
                    ),
                ),
                ConfigurationGroup(
                    namespace = "subagent",
                    title = "子 Agent",
                    detail = "子任务使用的默认设置",
                    writable = FactState.YES,
                    fields = listOf(
                        ConfigurationField(id = "enabled", title = "启用", value = "开启"),
                    ),
                ),
            ),
            permissionModes = listOf(
                ConfigurationPermissionMode(id = "standard", name = "标准", detail = "每次询问"),
                ConfigurationPermissionMode(id = "full", name = "完全", detail = "不询问"),
            ),
            defaultPermissionModeId = "standard",
            plugins = listOf(
                ConfigurationPlugin(
                    id = "loader",
                    module = "@deepseek-ai/dsh-loader",
                    enabled = FactState.YES,
                    phase = "active",
                ),
                ConfigurationPlugin(
                    id = "shell",
                    module = "@deepseek-ai/dsh-shell",
                    enabled = FactState.NO,
                    phase = "disabled",
                ),
            ),
            experimentalPlugins = listOf(
                ConfigurationExperimentalPlugin(
                    id = "agent-team",
                    title = "智能体团队",
                    status = "Worker 当前已加载 · active",
                    loaded = FactState.YES,
                ),
                ConfigurationExperimentalPlugin(
                    id = "voice-input",
                    title = "语音输入",
                    status = "Worker 未加载",
                    loaded = FactState.NO,
                ),
            ),
            pluginManagementMessage = "插件可查看；安装和启用需在桌面 DSH 管理。",
        ),
    )

    private fun show(
        state: ConfigurationScreenState,
        onDone: () -> Unit = {},
        onRefresh: (() -> Unit)? = null,
        onOpenWebSearch: (() -> Unit)? = null,
        onRetry: (() -> Unit)? = null,
        onDismissFailure: (() -> Unit)? = null,
        onSaveGroup: ((String) -> Unit)? = null,
        onDiscardGroup: ((String) -> Unit)? = null,
        onResetField: ((String, String) -> Unit)? = null,
        onPickDefaultPermission: (() -> Unit)? = null,
        onSaveDefaultPermission: (() -> Unit)? = null,
        onDiscardDefaultPermission: (() -> Unit)? = null,
    ) {
        compose.setContent {
            PdshTheme {
                NativeConfigurationPage(
                    state = state,
                    onDone = onDone,
                    onRefresh = onRefresh,
                    onToggleSection = {},
                    onOpenWebSearch = onOpenWebSearch,
                    onRetry = onRetry,
                    onDismissFailure = onDismissFailure,
                    onSaveGroup = onSaveGroup,
                    onDiscardGroup = onDiscardGroup,
                    onResetField = onResetField,
                    onPickDefaultPermission = onPickDefaultPermission,
                    onSaveDefaultPermission = onSaveDefaultPermission,
                    onDiscardDefaultPermission = onDiscardDefaultPermission,
                )
            }
        }
    }

    /** A host that applies the fold events, so the disclosure really moves. */
    private fun showLive(facts: ConfigurationFacts) {
        compose.setContent {
            var local by remember { mutableStateOf(ConfigurationLocalState()) }
            PdshTheme {
                NativeConfigurationPage(
                    state = ConfigurationScreenState.from(facts, local),
                    onDone = {},
                    onToggleSection = { id ->
                        local = local.apply(ConfigurationUiEvent.ToggleSection(id))
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ states

    @Test
    fun `an empty snapshot is the empty state, not the unknown state`() {
        val state = ConfigurationScreenState.from(
            ConfigurationFacts(snapshot = ConfigurationSnapshot()),
        )
        assertEquals(ConfigurationStatus.EMPTY, state.status)
        assertTrue(state.isEmpty)
        assertTrue(state.sections.isEmpty())
        assertNull(state.webSearchRow)
        show(state)

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_EMPTY).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_EMPTY).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_EMPTY_HINT).assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_UNKNOWN).assertDoesNotExist()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.CONFIGURATION_INSTALLED_COUNT.format(0))
            .assertDoesNotExist()
        compose.onNodeWithText(UiStrings.CONFIGURATION_SESSION).assertDoesNotExist()
    }

    @Test
    fun `a measured snapshot renders settings, permissions and the plugin inventory`() {
        val state = ConfigurationScreenState.from(fullFacts())
        assertEquals(ConfigurationStatus.READY, state.status)
        assertEquals("修复登录", state.sessionTitle)
        assertEquals(UiStrings.CONFIGURATION_WRITABLE_YES, state.writableValue)
        assertEquals(
            listOf(
                ConfigurationScreenState.groupSectionId("agent-loop"),
                ConfigurationScreenState.groupSectionId("bash-sandbox"),
                ConfigurationScreenState.groupSectionId("subagent"),
                ConfigurationScreenState.EXPERIMENTAL_SECTION_ID,
                ConfigurationScreenState.INSTALLED_SECTION_ID,
            ),
            state.sections.map { it.id },
        )
        assertEquals("标准", state.permission.selected?.name)
        assertTrue(state.permission.isUnknown.not())
        assertTrue(state.builtInExpanded)
        assertFalse(state.aboutExpanded)
        assertFalse(state.hasUnmeasuredValues)
        var done = 0
        var webSearch = 0
        show(
            state,
            onDone = { done += 1 },
            onOpenWebSearch = { webSearch += 1 },
        )

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_INTRO).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_INTRO).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_SESSION).assertIsDisplayed()
        compose.onNodeWithText("修复登录").assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_WRITABLE).assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_WEB_SEARCH).performClick()
        assertEquals(1, webSearch)
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_DONE).performClick()
        assertEquals(1, done)

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasText("最大轮次"))
        compose.onNodeWithText("最大轮次").assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(
                hasTestTag(
                    UiPageTags.CONFIGURATION_SECTION_PREFIX +
                        ConfigurationScreenState.INSTALLED_SECTION_ID,
                ),
            )
        compose.onNodeWithTag(
            UiPageTags.CONFIGURATION_SECTION_PREFIX +
                ConfigurationScreenState.INSTALLED_SECTION_ID,
        ).assertIsDisplayed()
    }

    @Test
    fun `a failed read wins over stale settings and offers retry and dismiss`() {
        val state = ConfigurationScreenState.from(fullFacts().copy(failure = "设置写入冲突"))
        assertEquals(ConfigurationStatus.FAILED, state.status)
        assertTrue(state.isFailed)
        assertTrue(state.sections.isEmpty())
        assertEquals("设置写入冲突", state.failure)
        var retried = 0
        var dismissed = 0
        show(
            state,
            onRetry = { retried += 1 },
            onDismissFailure = { dismissed += 1 },
        )

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_FAILURE).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_LOAD_FAILED).assertIsDisplayed()
        compose.onNodeWithText("设置写入冲突").assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST).assertDoesNotExist()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_EMPTY).assertDoesNotExist()
        compose.onNodeWithText("修复登录").assertDoesNotExist()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_RETRY).performClick()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_DISMISS).performClick()
        assertEquals(1, retried)
        assertEquals(1, dismissed)
    }

    @Test
    fun `an unreported snapshot is unknown after the region is composed`() {
        val state = ConfigurationScreenState.from(ConfigurationFacts())
        assertEquals(ConfigurationStatus.UNKNOWN, state.status)
        assertTrue(state.isUnknown)
        assertTrue(state.sections.isEmpty())
        show(state)

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(UiPageTags.CONFIGURATION_UNKNOWN_DETAIL))
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_UNKNOWN_DETAIL).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_UNKNOWN_STATE).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_UNKNOWN_HINT).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_UNKNOWN_DETAIL).assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_EMPTY).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.CONFIGURATION_EMPTY).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.CONFIGURATION_INSTALLED_COUNT.format(0))
            .assertDoesNotExist()
        compose.onNodeWithText(UiStrings.CONFIGURATION_WRITABLE_YES).assertDoesNotExist()
    }

    @Test
    fun `an unreadable plugin inventory says unknown, never zero entries`() {
        val facts = fullFacts()
        val snapshot = requireNotNull(facts.snapshot).copy(plugins = null)
        val state = ConfigurationScreenState.from(facts.copy(snapshot = snapshot))
        assertEquals(ConfigurationStatus.READY, state.status)
        val installed = state.sections.first {
            it.id == ConfigurationScreenState.INSTALLED_SECTION_ID
        }
        assertEquals(UiStrings.UNKNOWN, installed.trailing)
        assertEquals(UiStrings.CONFIGURATION_INSTALLED_UNKNOWN, installed.emptyText)
        showLive(facts.copy(snapshot = snapshot))

        val sectionTag = UiPageTags.CONFIGURATION_SECTION_PREFIX +
            ConfigurationScreenState.INSTALLED_SECTION_ID
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(sectionTag))
        compose.onNodeWithTag(sectionTag).performClick()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasText(UiStrings.CONFIGURATION_INSTALLED_UNKNOWN))
        compose.onNodeWithText(UiStrings.CONFIGURATION_INSTALLED_UNKNOWN).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_INSTALLED_COUNT.format(0))
            .assertDoesNotExist()
        compose.onNodeWithText(UiStrings.CONFIGURATION_INSTALLED_EMPTY).assertDoesNotExist()
    }

    @Test
    fun `the installed-plugin fold really moves the plugin rows`() {
        val state = ConfigurationScreenState.from(fullFacts())
        val installed = state.sections.first {
            it.id == ConfigurationScreenState.INSTALLED_SECTION_ID
        }
        assertFalse(installed.expanded)
        showLive(fullFacts())

        val sectionTag = UiPageTags.CONFIGURATION_SECTION_PREFIX +
            ConfigurationScreenState.INSTALLED_SECTION_ID
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(sectionTag))
        compose.onNodeWithTag(sectionTag).performClick()
        val rowTag = UiPageTags.CONFIGURATION_PLUGIN_ROW_PREFIX + "config.installed.loader"
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(rowTag))
        compose.onNodeWithTag(rowTag).assertIsDisplayed()
        compose.onNodeWithText("@deepseek-ai/dsh-loader").assertIsDisplayed()
    }

    @Test
    fun `插件说明 opens the two sections the baseline keeps in its sheet`() {
        showLive(fullFacts())

        // The about area is closed initially: the baseline hides it behind a sheet.
        compose.onNodeWithText(UiStrings.CONFIGURATION_SCOPE_SECTION).assertDoesNotExist()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_PLUGIN_INFO_ACTION).performClick()

        val scopeTag = UiPageTags.CONFIGURATION_SECTION_PREFIX +
            ConfigurationScreenState.SCOPE_SECTION_ID
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(scopeTag))
        compose.onNodeWithText(UiStrings.CONFIGURATION_SCOPE_SECTION).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_SCOPE_TEXT).assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(
                hasTestTag(
                    UiPageTags.CONFIGURATION_SECTION_PREFIX +
                        ConfigurationScreenState.LIMITS_SECTION_ID,
                ),
            )
        compose.onNodeWithText(UiStrings.CONFIGURATION_LIMITS_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasText(UiStrings.CONFIGURATION_ONLY_LOADED_TEXT))
        compose.onNodeWithText(UiStrings.CONFIGURATION_ONLY_LOADED_TEXT).assertIsDisplayed()
    }

    @Test
    fun `folding 内置插件设置 hides the host groups, not the plugin inventory`() {
        showLive(fullFacts())
        val agentHeader = UiPageTags.CONFIGURATION_SECTION_PREFIX +
            ConfigurationScreenState.groupSectionId("agent-loop")
        compose.onNodeWithTag(agentHeader).assertIsDisplayed()

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_BUILT_IN).performClick()
        compose.onNodeWithTag(agentHeader).assertDoesNotExist()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_PERMISSION).assertDoesNotExist()
        compose.onNodeWithTag(
            UiPageTags.CONFIGURATION_SECTION_PREFIX +
                ConfigurationScreenState.EXPERIMENTAL_SECTION_ID,
        ).assertIsDisplayed()
    }

    @Test
    fun `the add-plugin menu prints why installing cannot run here`() {
        showLive(fullFacts())

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_ADD_PLUGIN).performClick()
        compose.onNodeWithText(UiStrings.CONFIGURATION_INSTALL_THIRD_PARTY).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_CREATE_WITH_AGENT).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_INSTALL_THIRD_PARTY).performClick()
        compose.onNodeWithText(UiStrings.CONFIGURATION_ADD_DISABLED_HINT).assertIsDisplayed()
    }

    @Test
    fun `an unread permission catalog says unknown and shows no save pair`() {
        val facts = fullFacts()
        val snapshot = requireNotNull(facts.snapshot).copy(
            permissionModes = null,
            defaultPermissionModeId = null,
        )
        val state = ConfigurationScreenState.from(facts.copy(snapshot = snapshot))
        assertTrue(state.permission.isUnknown)
        assertTrue(state.hasUnmeasuredValues)
        show(state)

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(UiPageTags.CONFIGURATION_PERMISSION))
        compose.onNodeWithText(UiStrings.CONFIGURATION_PERMISSION_UNKNOWN).assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_PERMISSION_SAVE).assertDoesNotExist()
    }

    @Test
    fun `the permission row and its save pair arm only with a host write`() {
        var picked = 0
        var saved = 0
        var discarded = 0
        show(
            ConfigurationScreenState.from(fullFacts()),
            onPickDefaultPermission = { picked += 1 },
            onSaveDefaultPermission = { saved += 1 },
            onDiscardDefaultPermission = { discarded += 1 },
        )

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(UiPageTags.CONFIGURATION_PERMISSION_ROW))
        compose.onNodeWithText(UiStrings.CONFIGURATION_DEFAULT_PERMISSION_ROW)
            .assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_PERMISSION_ROW).performClick()
        assertEquals(1, picked)
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_PERMISSION_SAVE).performClick()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_PERMISSION_DISCARD).performClick()
        assertEquals(1, saved)
        assertEquals(1, discarded)
    }

    @Test
    fun `a writable group arms its save pair and an overridden field resets`() {
        val facts = fullFacts()
        val snapshot = requireNotNull(facts.snapshot)
        val groups = snapshot.groups.orEmpty().map { group ->
            if (group.namespace != "agent-loop") {
                group
            } else {
                group.copy(
                    fields = group.fields.orEmpty().mapIndexed { index, field ->
                        if (index == 0) field.copy(overridden = true) else field
                    },
                )
            }
        }
        val state = ConfigurationScreenState.from(
            facts.copy(snapshot = snapshot.copy(groups = groups)),
        )
        val groupId = ConfigurationScreenState.groupSectionId("agent-loop")
        var savedGroup: String? = null
        var discardedGroup: String? = null
        var reset: Pair<String, String>? = null
        show(
            state,
            onSaveGroup = { savedGroup = it },
            onDiscardGroup = { discardedGroup = it },
            onResetField = { namespace, field -> reset = namespace to field },
        )

        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(
                hasTestTag(UiPageTags.CONFIGURATION_GROUP_SAVE_PREFIX + groupId),
            )
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_GROUP_SAVE_PREFIX + groupId).performClick()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_GROUP_DISCARD_PREFIX + groupId).performClick()
        assertEquals("agent-loop", savedGroup)
        assertEquals("agent-loop", discardedGroup)

        val resetTag = UiPageTags.CONFIGURATION_ROW_RESET_PREFIX + "$groupId.maxTurns"
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(resetTag))
        compose.onNodeWithTag(resetTag).performClick()
        assertEquals("agent-loop" to "maxTurns", reset)
    }

    @Test
    fun `the fold sequence is a pure function of the script`() {
        val facts = fullFacts()
        val agentLoop = ConfigurationScreenState.groupSectionId("agent-loop")
        val script = listOf(
            ConfigurationUiEvent.ToggleSection(ConfigurationScreenState.INSTALLED_SECTION_ID),
            ConfigurationUiEvent.ToggleSection(ConfigurationScreenState.INSTALLED_SECTION_ID),
            ConfigurationUiEvent.ToggleSection(ConfigurationScreenState.BUILT_IN_SECTION_ID),
            ConfigurationUiEvent.ToggleSection(agentLoop),
            ConfigurationUiEvent.ToggleSection(ConfigurationScreenState.BUILT_IN_SECTION_ID),
            ConfigurationUiEvent.ToggleSection(ConfigurationScreenState.INFO_SECTION_ID),
            ConfigurationUiEvent.ToggleSection(ConfigurationScreenState.INFO_SECTION_ID),
        )

        fun run(): List<ConfigurationScreenState> {
            var local = ConfigurationLocalState()
            return buildList {
                add(ConfigurationScreenState.from(facts, local))
                script.forEach { event ->
                    local = local.apply(event)
                    add(ConfigurationScreenState.from(facts, local))
                }
            }
        }

        val states = run()
        fun expanded(id: String): List<Boolean?> =
            states.map { state -> state.sections.firstOrNull { it.id == id }?.expanded }

        assertEquals(
            listOf(true, true, true, false, false, true, true, true),
            states.map { it.builtInExpanded },
        )
        assertEquals(
            listOf(false, false, false, false, false, false, true, false),
            states.map { it.aboutExpanded },
        )
        assertEquals(
            listOf(false, true, false, false, false, false, false, false),
            expanded(ConfigurationScreenState.INSTALLED_SECTION_ID),
        )
        // A folded master section hides the groups; the group keeps its own fold.
        assertEquals(
            listOf(true, true, true, null, null, false, false, false),
            expanded(agentLoop),
        )
        assertEquals(
            listOf(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(
                    ConfigurationScreenState.SCOPE_SECTION_ID,
                    ConfigurationScreenState.LIMITS_SECTION_ID,
                ),
                emptyList(),
            ),
            states.map { state ->
                state.sections.map { it.id }.filter { id ->
                    id == ConfigurationScreenState.SCOPE_SECTION_ID ||
                        id == ConfigurationScreenState.LIMITS_SECTION_ID
                }
            },
        )
        // Replaying the same script yields exactly the same sequence (purity).
        assertEquals(states.map { it.signature() }, run().map { it.signature() })
    }
    // ------------------------------------------------------------------ pills

    @Test
    fun `the three glass pills are drawn, and 刷新 arms only with a host command`() {
        val state = ConfigurationScreenState.from(fullFacts())
        show(state)
        // 插件说明 / 刷新 / 添加插件: one HStack in the baseline
        // (`NativeConfigurationPage.swift:67-95`), three pills here.
        compose.onNodeWithText(UiStrings.CONFIGURATION_PLUGIN_INFO).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_REFRESH).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.CONFIGURATION_ADD_DISABLED).assertIsDisplayed()
        // A host with no re-read command still sees the control, disabled — it
        // says "this host cannot", it does not vanish.
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_REFRESH).assertIsNotEnabled()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_PLUGIN_INFO_ACTION).assertIsEnabled()
        // 添加插件 is disabled on every phone (`NativeConfigurationPage.swift:93`),
        // and its tap prints the measured reason instead of doing nothing.
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_ADD_PLUGIN).performClick()
        compose.onNodeWithText(UiStrings.CONFIGURATION_INSTALL_THIRD_PARTY).assertIsDisplayed()
    }

    @Test
    fun `the 刷新 pill re-reads the snapshot when the host handed a command`() {
        var refreshes = 0
        show(
            ConfigurationScreenState.from(fullFacts()),
            onRefresh = { refreshes += 1 },
        )
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_REFRESH).assertIsEnabled()
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_REFRESH).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun `a partial host failure draws the orange warning line`() {
        val facts = fullFacts()
        val partial = facts.copy(
            snapshot = facts.snapshot!!.copy(
                settingsFailure = "请先启动 DSH 主机，再读取插件和设置。",
            ),
        )
        val state = ConfigurationScreenState.from(partial)
        assertEquals(1, state.notes.size)
        val sentence = UiStrings.CONFIGURATION_PARTIAL_FAILURE_FORMAT
            .format("请先启动 DSH 主机，再读取插件和设置。")
        show(state)
        compose.onNodeWithTag(UiPageTags.CONFIGURATION_LIST)
            .performScrollToNode(hasTestTag(UiPageTags.CONFIGURATION_NOTE_PREFIX + state.notes.single().id))
        compose.onNodeWithText(sentence).assertIsDisplayed()
    }
}
