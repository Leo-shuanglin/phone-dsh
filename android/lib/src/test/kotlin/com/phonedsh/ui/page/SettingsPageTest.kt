// Settings: the five-tab sheet, its 通用 tab and the 新会话默认权限 sub-page.
// SPDX-License-Identifier: MIT
//
// Authority: `examples/probe-app/PhoneDSHProbe/SettingsPage.swift` at iOS HEAD
// (post-PR-#13). These tests pin the five tabs and their order, the 通用 tab's six
// cards and their row order, the baseline's wording for an unset value, the two
// write gestures plus the five pickers, every navigation target, the Android-only
// appendix, and the 新会话默认权限 sub-page.
//
// **Robolectric's font metrics are the reason this file scrolls.** The JVM text
// engine measures a 17 pt CJK line at ~35 pt instead of the platform's ~20.3 pt,
// so a 52.33 pt row renders ~67 pt tall and a full card list does not fit the
// 891 dp window. That is a measurement artifact, not a layout bug — the device
// capture (`page-settings-default`) pins the real pitch. The tests therefore
// scroll to a row before asserting on it or tapping it, which keeps the assertion
// (the row exists, is displayed, reports this destination) exactly as strong as
// before. Raising the window height would have hidden the fact that a row below
// the fold is reached by scrolling in the real app.
package com.phonedsh.ui.page

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.UiTags
import com.phonedsh.ui.theme.PdshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        facts: SettingsFacts = SettingsFacts(),
        tab: SettingsTab = SettingsTab.GENERAL,
        onSelectTab: (SettingsTab) -> Unit = {},
        onNavigate: (SettingsDestination) -> Unit = {},
        onToggleBackground: (Boolean) -> Unit = {},
        onToggleDeveloperTools: (Boolean) -> Unit = {},
        onSelectMode: (BusyEnterMode) -> Unit = {},
        onSelectPreference: (SettingsPreferenceChoice) -> Unit = {},
        onBack: () -> Unit = {},
        tabContent: (@Composable (SettingsTab) -> Unit)? = null,
    ) {
        val state = SettingsScreenState.from(facts)
        compose.setContent {
            PdshTheme {
                SettingsPage(
                    state = state,
                    onNavigate = onNavigate,
                    onToggleBackgroundKeepAlive = onToggleBackground,
                    onSelectBusyEnterMode = onSelectMode,
                    onBack = onBack,
                    tab = tab,
                    onSelectTab = onSelectTab,
                    onSelectPreference = onSelectPreference,
                    onToggleDeveloperTools = onToggleDeveloperTools,
                    tabContent = tabContent,
                )
            }
        }
    }

    /** The 通用 tab scrolls: a row below the fold is only composed once it is near. */
    private fun scrollTo(text: String) {
        compose.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasText(text))
    }

    /** A row's tap target by its state id. */
    private fun row(id: String) = compose.onNodeWithTag(UiPageTags.SETTINGS_ROW_PREFIX + id)

    // ---------------------------------------------------------------- structure

    /**
     * `SettingsPage.swift:99-167` renders five cards on the 通用 tab; this page
     * appends the Android-only appendix as a sixth. No card has a section header:
     * the baseline's only small print on this page is two footnotes *inside*
     * cards, and both are pinned here by their row ids.
     */
    @Test
    fun `the general tab keeps the baseline's five cards, its row order and the appendix`() {
        val state = SettingsScreenState.from(SettingsFacts())
        assertEquals(
            listOf(
                SettingsScreenState.PERMISSION_SECTION_ID,
                SettingsScreenState.PREFERENCES_SECTION_ID,
                SettingsScreenState.WORK_SECTION_ID,
                SettingsScreenState.COMPOSER_SECTION_ID,
                SettingsScreenState.ABOUT_SECTION_ID,
                SettingsScreenState.PLATFORM_SECTION_ID,
            ),
            state.sections.map { it.id },
        )
        assertEquals(
            listOf(
                SettingsScreenState.PERMISSION_ROW_ID,
                SettingsScreenState.PERMISSION_NOTE_ROW_ID,
            ),
            state.sections[0].rows.map { it.id },
        )
        assertEquals(
            listOf(
                SettingsScreenState.LANGUAGE_ROW_ID,
                SettingsScreenState.APPEARANCE_ROW_ID,
                SettingsScreenState.FONT_SIZE_ROW_ID,
                SettingsScreenState.FONT_NOTE_ROW_ID,
            ),
            state.sections[1].rows.map { it.id },
        )
        assertEquals(
            listOf(
                SettingsScreenState.TRANSCRIPT_VIEW_ROW_ID,
                SettingsScreenState.COLLAPSE_TIMING_ROW_ID,
                SettingsScreenState.DEVELOPER_TOOLS_ROW_ID,
                SettingsScreenState.SHORTCUTS_ROW_ID,
            ),
            state.sections[2].rows.map { it.id },
        )
        assertEquals(
            listOf(
                SettingsScreenState.BUSY_ENTER_ROW_ID,
                SettingsScreenState.PERFORMANCE_ROW_ID,
            ),
            state.sections[3].rows.map { it.id },
        )
        assertEquals(
            listOf(
                SettingsScreenState.VERSION_ROW_ID,
                SettingsScreenState.LICENSES_ROW_ID,
            ),
            state.sections[4].rows.map { it.id },
        )
        // The eleven rendered labels, in the baseline's order (a footnote row
        // carries no label, only its text).
        assertEquals(
            listOf(
                "权限", "语言", "外观", "字号大小", "工作步骤展示", "工作步骤收起时机",
                "显示代码工作视图", "快捷键", "繁忙时的发送行为", "性能与用量", "当前版本",
                UiStrings.ABOUT_LICENSES,
            ),
            state.sections
                .filter { it.id != SettingsScreenState.PLATFORM_SECTION_ID }
                .flatMap { it.rows }
                .filter { it.kind != SettingsRowKind.FOOTNOTE }
                .map { it.title },
        )
        show()
        // The bar draws the tab's inline title and 完成; the cards carry no headers.
        compose.onNodeWithText(SettingsTab.GENERAL.title).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.SETTINGS_DONE).assertIsDisplayed()
        listOf(
            "权限", "语言", "外观", "字号大小", "工作步骤展示", "工作步骤收起时机",
            "显示代码工作视图", "快捷键", "繁忙时的发送行为", "性能与用量", "当前版本",
        ).forEach { label ->
            scrollTo(label)
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    /** The segmented picker draws the baseline's five tabs, in its order. */
    @Test
    fun `the segmented picker draws five tabs and reports the tap`() {
        val picked = mutableListOf<SettingsTab>()
        show(onSelectTab = { picked += it })
        assertEquals(5, SettingsTab.order.size)
        assertEquals(
            listOf("通用", "模型", "插件", "手机权限", "预设"),
            SettingsTab.order.map { it.shortTitle },
        )
        SettingsTab.order.forEach { tab ->
            compose.onNodeWithText(tab.shortTitle).assertIsDisplayed()
        }
        compose.onNodeWithText("预设").performClick()
        compose.onNodeWithText("手机权限").performClick()
        assertEquals(listOf(SettingsTab.PRESETS, SettingsTab.PERMISSIONS), picked)
    }

    /**
     * A host that reported nothing still sees every row: the baseline's own
     * wording for "there is none" (未设置) and 未知 where the baseline has no
     * "none" branch. Nothing may become a plausible default.
     */
    @Test
    fun `an empty snapshot renders the baseline's unset wording, never a default`() {
        val state = SettingsScreenState.from(SettingsFacts())
        val rows = state.sections.flatMap { it.rows }.associateBy { it.id }
        listOf(
            SettingsScreenState.PERMISSION_ROW_ID,
            SettingsScreenState.LANGUAGE_ROW_ID,
            SettingsScreenState.APPEARANCE_ROW_ID,
            SettingsScreenState.TRANSCRIPT_VIEW_ROW_ID,
            SettingsScreenState.COLLAPSE_TIMING_ROW_ID,
            SettingsScreenState.PERFORMANCE_ROW_ID,
        ).forEach { id ->
            assertEquals(id, UiStrings.SETTINGS_PERMISSION_UNSET, rows.getValue(id).value)
        }
        assertEquals(UiStrings.UNKNOWN, rows.getValue(SettingsScreenState.VERSION_ROW_ID).value)
        assertNull(rows.getValue(SettingsScreenState.FONT_SIZE_ROW_ID).value)
        // A switch the host did not report is a value row, not a switch in a
        // position we invented.
        assertEquals(
            SettingsRowKind.VALUE,
            rows.getValue(SettingsScreenState.DEVELOPER_TOOLS_ROW_ID).kind,
        )
        assertEquals(
            SettingsRowKind.VALUE,
            rows.getValue(SettingsScreenState.BACKGROUND_ROW_ID).kind,
        )
        assertNull(state.row(SettingsScreenState.STATUS_ROW_ID))
        show()
        // Seven unset rows share one wording — 权限 and the six pickers — so the
        // count is the assertion: no menu may render one of its options as if the
        // user had chosen it.
        compose.onAllNodesWithText(UiStrings.SETTINGS_PERMISSION_UNSET).assertCountEquals(7)
    }

    /** 权限 reports the host's preset, in the row and on screen. */
    @Test
    fun `the permission row reports the host's preset`() {
        val facts = SettingsFacts(defaultPermissionTier = PermissionTier.FULL_ACCESS)
        val state = SettingsScreenState.from(facts)
        assertEquals(
            UiStrings.PERMISSION_TIER_FULL_ACCESS,
            state.row(SettingsScreenState.PERMISSION_ROW_ID)?.value,
        )
        show(facts)
        compose.onNodeWithText("权限").assertIsDisplayed()
        compose.onNodeWithText(UiStrings.PERMISSION_TIER_FULL_ACCESS).assertIsDisplayed()
    }

    /** Every navigation row opens its destination; the plain rows open nothing. */
    @Test
    fun `every navigation row reports its destination`() {
        val opened = mutableListOf<SettingsDestination>()
        show(onNavigate = { opened += it })
        // 权限 is the one navigation row the baseline and this page share; 开源许可
        // is iOS-only and 导出诊断 Android-only, so both are pinned here too.
        compose.onNodeWithText("权限").performClick()
        assertEquals(SettingsDestination.PERMISSION_DEFAULTS, opened.last())
        scrollTo(UiStrings.ABOUT_LICENSES)
        compose.onNodeWithText(UiStrings.ABOUT_LICENSES).performClick()
        assertEquals(SettingsDestination.LICENSES, opened.last())
        scrollTo(UiStrings.SETTINGS_ROW_EXPORT)
        compose.onNodeWithText(UiStrings.SETTINGS_ROW_EXPORT).performClick()
        assertEquals(SettingsDestination.EXPORT_DIAGNOSTICS, opened.last())
        // L55: the two entries to the pages the iOS baseline left without one.
        // 开发者工具 opens the Android-only index (记忆 / 设备探针 / 后台保活 / 任务屏 /
        // 每日简报 / 会话活动), and 当前版本 now opens the about page.
        scrollTo("开发者工具")
        compose.onNodeWithText("开发者工具").performClick()
        assertEquals(SettingsDestination.DEV_TOOLS, opened.last())
        scrollTo("当前版本")
        compose.onNodeWithText("当前版本").performClick()
        assertEquals(SettingsDestination.ABOUT, opened.last())
        assertEquals(
            listOf(
                SettingsDestination.PERMISSION_DEFAULTS,
                SettingsDestination.LICENSES,
                SettingsDestination.EXPORT_DIAGNOSTICS,
                SettingsDestination.DEV_TOOLS,
                SettingsDestination.ABOUT,
            ),
            opened,
        )
    }

    /**
     * 开源许可 has no Android page to open: the iOS list names SwiftTerm and
     * swift-nio, neither of which this app ships. The row explains that instead of
     * navigating nowhere — it still reports the destination so a future licenses
     * page needs no page change.
     */
    @Test
    fun `the licenses row explains itself and still reports its destination`() {
        val opened = mutableListOf<SettingsDestination>()
        show(onNavigate = { opened += it })
        scrollTo(UiStrings.ABOUT_LICENSES)
        compose.onNodeWithText(UiStrings.SETTINGS_LICENSES_ANDROID_ONLY).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.ABOUT_LICENSES).performClick()
        assertEquals(listOf(SettingsDestination.LICENSES), opened)
    }

    /** 后台运行 is the appendix's switch: it reports the flip and nothing else. */
    @Test
    fun `the background row reports the flip`() {
        val flips = mutableListOf<Boolean>()
        show(
            facts = SettingsFacts(backgroundKeepAlive = FactState.YES),
            onToggleBackground = { flips += it },
        )
        scrollTo(UiStrings.SETTINGS_ROW_BACKGROUND)
        row(SettingsScreenState.BACKGROUND_ROW_ID).performClick()
        assertEquals(listOf(false), flips)
    }

    /** 显示代码工作视图 is the second switch, with its own callback. */
    @Test
    fun `the developer-tools row reports the flip`() {
        val flips = mutableListOf<Boolean>()
        show(
            facts = SettingsFacts(
                preferences = SettingsPreferences(developerTools = FactState.NO),
            ),
            onToggleDeveloperTools = { flips += it },
        )
        scrollTo("显示代码工作视图")
        row(SettingsScreenState.DEVELOPER_TOOLS_ROW_ID).performClick()
        assertEquals(listOf(true), flips)
    }

    /** 繁忙时的发送行为 keeps its typed callback. */
    @Test
    fun `the send-while-running menu reports the picked mode`() {
        val modes = mutableListOf<BusyEnterMode>()
        show(
            facts = SettingsFacts(busyEnterMode = BusyEnterMode.QUEUE),
            onSelectMode = { modes += it },
        )
        scrollTo("繁忙时的发送行为")
        row(SettingsScreenState.BUSY_ENTER_ROW_ID).performClick()
        // The expanded menu grows the card; scroll the option itself into view so
        // the tap lands on a fully visible node (Robolectric's tall rows again).
        scrollTo(UiStrings.BUSY_ENTER_STEER)
        compose.onNodeWithTag(UiPageTags.SETTINGS_ROW_OPTION_PREFIX + BusyEnterMode.STEER.id)
            .performClick()
        assertEquals(listOf(BusyEnterMode.STEER), modes)
    }

    /** The five generic pickers report (row id, option id). */
    @Test
    fun `every picker reports the row and the option it picked`() {
        val picks = mutableListOf<SettingsPreferenceChoice>()
        show(
            facts = SettingsFacts(
                preferences = SettingsPreferences(
                    language = SettingsLanguage.ZH,
                    appearance = SettingsAppearance.LIGHT,
                    transcriptView = TranscriptViewMode.STANDARD,
                    collapseTiming = CollapseTiming.COMPLETION,
                    performanceUsage = PerformanceUsage.COMPACT,
                ),
            ),
            onSelectPreference = { picks += it },
        )
        data class Case(
            val rowId: String,
            val label: String,
            val optionId: String,
            val optionLabel: String,
        )

        val cases = listOf(
            Case(SettingsScreenState.LANGUAGE_ROW_ID, "语言", SettingsLanguage.EN.id, "English"),
            Case(SettingsScreenState.APPEARANCE_ROW_ID, "外观", SettingsAppearance.DARK.id, "深色"),
            Case(
                SettingsScreenState.TRANSCRIPT_VIEW_ROW_ID,
                "工作步骤展示",
                TranscriptViewMode.VERBOSE.id,
                "完全展开",
            ),
            Case(
                SettingsScreenState.COLLAPSE_TIMING_ROW_ID,
                "工作步骤收起时机",
                CollapseTiming.NEXT_INPUT.id,
                "下次有新消息时",
            ),
            Case(
                SettingsScreenState.PERFORMANCE_ROW_ID,
                "性能与用量",
                PerformanceUsage.DETAILED.id,
                "详细",
            ),
        )
        cases.forEach { case ->
            scrollTo(case.label)
            row(case.rowId).performClick()
            // The expanded menu grows the card; scroll the option into view so the
            // tap lands on a fully visible node.
            scrollTo(case.optionLabel)
            compose.onNodeWithTag(UiPageTags.SETTINGS_ROW_OPTION_PREFIX + case.optionId)
                .performClick()
        }
        assertEquals(
            cases.map { SettingsPreferenceChoice(rowId = it.rowId, optionId = it.optionId) },
            picks,
        )
        // The menus carry the baseline's option sets, in its order.
        assertEquals(3, SettingsLanguage.entries.size)
        assertEquals(3, SettingsAppearance.entries.size)
        assertEquals(2, CollapseTiming.entries.size)
        assertEquals(2, PerformanceUsage.entries.size)
        assertEquals(
            listOf("简洁", "标准", "详细", "完全展开"),
            TranscriptViewMode.entries.map { it.label },
        )
    }

    /** 字号大小 reads the three px sizes the host reported, or says nothing read. */
    @Test
    fun `the font-size row joins the three measured sizes`() {
        val state = SettingsScreenState.from(
            SettingsFacts(
                preferences = SettingsPreferences(
                    fontSizeConversation = 15,
                    fontSizeCode = 13,
                    fontSizeTerminal = 12,
                ),
            ),
        )
        assertEquals(
            "15 px / 13 px / 12 px",
            state.row(SettingsScreenState.FONT_SIZE_ROW_ID)?.value,
        )
        assertNull(
            SettingsScreenState.from(SettingsFacts())
                .row(SettingsScreenState.FONT_SIZE_ROW_ID)?.value,
        )
    }

    /** The appendix keeps the three whitelisted platform facts, in its order. */
    @Test
    fun `the platform appendix keeps its three facts`() {
        val facts = SettingsFacts(
            platform = SettingsPlatformFacts(
                notifications = FactState.YES,
                batteryExemption = FactState.NO,
                predictiveBack = FactState.YES,
            ),
        )
        val state = SettingsScreenState.from(facts)
        val appendix = state.section(SettingsScreenState.PLATFORM_SECTION_ID)!!
        assertEquals(
            listOf(
                SettingsScreenState.BACKGROUND_ROW_ID,
                SettingsScreenState.EXPORT_ROW_ID,
                // L55: the Android-only entry to the pages whose iOS baseline has
                // no entry (关于 is reached from the 版本 row above instead).
                SettingsScreenState.DEV_TOOLS_ROW_ID,
                SettingsScreenState.NOTIFICATIONS_ROW_ID,
                SettingsScreenState.BATTERY_ROW_ID,
                SettingsScreenState.BACK_ROW_ID,
            ),
            appendix.rows.map { it.id },
        )
        assertEquals(
            UiStrings.SETTINGS_NOTIFICATIONS_ON,
            appendix.rows.first { it.id == SettingsScreenState.NOTIFICATIONS_ROW_ID }.value,
        )
        assertEquals(
            UiStrings.SETTINGS_BATTERY_NOT_EXEMPT,
            appendix.rows.first { it.id == SettingsScreenState.BATTERY_ROW_ID }.value,
        )
        // The back form is a platform difference, not a degradation: never warning ink.
        assertEquals(
            PageTone.ON,
            appendix.rows.first { it.id == SettingsScreenState.BACK_ROW_ID }.tone,
        )
        show(facts)
        scrollTo(UiStrings.SETTINGS_NOTIFICATIONS)
        compose.onNodeWithText(UiStrings.SETTINGS_NOTIFICATIONS).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.SETTINGS_NOTIFICATIONS_ON).assertIsDisplayed()
    }

    /** The status line's precedence: warning, then restart, then saved. */
    @Test
    fun `the appendix status line honours the baseline's precedence`() {
        fun status(facts: SettingsFacts): String? =
            SettingsScreenState.from(facts).row(SettingsScreenState.STATUS_ROW_ID)?.text

        val warning = "网页搜索已降级"
        assertEquals(
            warning,
            status(SettingsFacts(searchLaunchWarning = warning, didSave = true)),
        )
        assertEquals(
            UiStrings.SETTINGS_FOOTER_RESTART,
            status(
                SettingsFacts(
                    engineStarted = FactState.YES,
                    networkConfigurationChanged = true,
                    didSave = true,
                ),
            ),
        )
        assertEquals(
            UiStrings.SETTINGS_FOOTER_SAVED,
            status(SettingsFacts(engineStarted = FactState.YES, didSave = true)),
        )
        assertNull(status(SettingsFacts(engineStarted = FactState.YES)))
    }

    /** 完成 leaves the sheet. */
    @Test
    fun `the done action leaves the page`() {
        var back = 0
        show(onBack = { back += 1 })
        compose.onNodeWithTag(UiPageTags.SETTINGS_DONE).performClick()
        assertEquals(1, back)
    }

    /** A tab whose body the host has not wired says so instead of drawing a page. */
    @Test
    fun `an unwired tab says so`() {
        show(tab = SettingsTab.PRESETS)
        compose.onNodeWithText(SettingsTab.PRESETS.title).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.UNAVAILABLE).assertIsDisplayed()
        // The general tab's cards are not drawn under another tab.
        compose.onNodeWithText("语言").assertDoesNotExist()
    }

    /** A wired tab draws the host's page; the shell supplies bar + picker. */
    @Test
    fun `a wired tab draws the host's content`() {
        show(
            tab = SettingsTab.MODELS,
            tabContent = { tab -> BasicText(text = "宿主页:" + tab.shortTitle) },
        )
        compose.onNodeWithText("宿主页:模型").assertIsDisplayed()
        compose.onNodeWithText(UiStrings.UNAVAILABLE).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ sub-page

    @Test
    fun `the permission sub-page keeps the baseline's note and three presets`() {
        val state = PermissionDefaultsState.from(PermissionTier.WORKSPACE_WRITE)
        assertEquals(UiStrings.PERMISSION_DEFAULTS_NOTE, state.note)
        assertEquals(
            listOf(
                UiStrings.PERMISSION_TIER_VIEW_ONLY,
                UiStrings.PERMISSION_TIER_WORKSPACE_WRITE,
                UiStrings.PERMISSION_TIER_FULL_ACCESS,
            ),
            state.options.map { it.title },
        )
        compose.setContent {
            PdshTheme {
                PermissionDefaultsPage(state = state, onSelect = {}, onBack = {})
            }
        }
        compose.onNodeWithTag(UiPageTags.PERMISSION_DEFAULTS_NOTE).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.PERMISSION_TIER_FULL_ACCESS).assertIsDisplayed()
    }

    @Test
    fun `the current preset is marked and a tap reports the new one`() {
        val picked = mutableListOf<PermissionTier>()
        val state = PermissionDefaultsState.from(PermissionTier.WORKSPACE_WRITE)
        assertTrue(state.options.single { it.selected }.tier == PermissionTier.WORKSPACE_WRITE)
        compose.setContent {
            PdshTheme {
                PermissionDefaultsPage(state = state, onSelect = { picked += it }, onBack = {})
            }
        }
        compose.onNodeWithTag(
            UiPageTags.PERMISSION_DEFAULTS_OPTION_PREFIX + PermissionTier.FULL_ACCESS.name,
        ).performClick()
        assertEquals(listOf(PermissionTier.FULL_ACCESS), picked)
    }

    @Test
    fun `the permission sub-page's back affordance leaves it`() {
        var back = 0
        compose.setContent {
            PdshTheme {
                PermissionDefaultsPage(
                    state = PermissionDefaultsState.from(null),
                    onSelect = {},
                    onBack = { back += 1 },
                )
            }
        }
        compose.onNodeWithContentDescription(UiStrings.BACK).performClick()
        assertEquals(1, back)
    }
}
