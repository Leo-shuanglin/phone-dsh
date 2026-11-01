// Parity captures: the iOS state names, captured at 402 x 874 @3 on Android.
// SPDX-License-Identifier: MIT
//
// What this suite is for: putting an Android frame next to the iOS baseline
// *without rescaling either side*. The state name is the join key (`ios-<state>`
// -> `page-<state>`), the viewport is the official golden's contract, and the
// comparison of the two PNGs is a human review by the page lane — this suite does
// not and cannot claim the two platforms render the same pixels.
//
// What this suite is NOT: a coverage claim. `ParityState` registers all 27 iOS
// states; a `PENDING` entry has no `@Test` here on purpose, because its content
// belongs to a page lane that is still changing the page. Capturing a half-written
// page would freeze the wrong pixels. Each captured state is one `@Test`, so a
// failure names the state instead of the whole suite.
package com.phonedsh.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonedsh.ui.page.AboutPage
import com.phonedsh.ui.page.ChatPage
import com.phonedsh.ui.page.EgressAllowListPage
import com.phonedsh.ui.page.ModelProviderSettingsPage
import com.phonedsh.ui.page.NativeConfigurationPage
import com.phonedsh.ui.page.RuntimeDetailsPage
import com.phonedsh.ui.page.SettingsPage
import com.phonedsh.ui.page.SettingsTab
import com.phonedsh.ui.page.WebSearchSettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParityScreenshotTest : ScreenshotTestBase() {
    /** The parity frame is the whole point of this suite. */
    override val allowedProfiles: Set<ScreenshotProfile> = setOf(ScreenshotProfile.IOS_PARITY)

    /**
     * Publishes the registry next to the PNGs on every test.
     *
     * Written here rather than in one test so that `-e class ...#<method>` (a
     * single-state run) still leaves the host a complete mapping to verify.
     */
    @Before
    fun publishRegistry() {
        ScreenshotStore.writeRegistry(
            activity = compose.activity,
            profile = profile,
            lines = captureRegistryLines(),
        )
    }

    // ------------------------------------------------------------- structure

    /**
     * The registry has to describe the iOS directory exactly: 27 states, 27
     * Android names, no duplicate iOS file, and a note the host can parse.
     */
    @Test
    fun registryIsComplete() {
        val states = ParityState.entries
        assertEquals(
            "the iOS baseline directory publishes a fixed number of states",
            ParityState.IOS_BASELINE_STATE_COUNT,
            states.size,
        )
        assertEquals(
            "every parity state belongs to the parity category",
            states.size,
            states.count { it.category == ParityCategory.PARITY },
        )
        assertEquals(
            "Android capture names must be unique",
            states.size,
            states.map { it.androidName }.toSet().size,
        )
        assertEquals(
            "every iOS file must map to exactly one Android state",
            states.size,
            states.map { it.iosFile }.toSet().size,
        )
        states.forEach { state ->
            assertFalse(
                "${state.androidName}: the registry line is pipe-separated, so a note cannot " +
                    "contain '|' or a newline",
                state.note.contains('|') || state.note.contains('\n'),
            )
            assertFalse("${state.androidName}: owner is empty", state.owner.isBlank())
            assertFalse("${state.screen}: screen is empty", state.screen.isBlank())
        }
        assertEquals(
            "a captured state keeps its iOS name with the kind prefix swapped in",
            states.filter { it.status == ParityStatus.CAPTURED }
                .map { it.androidName to it.iosFile }
                .filterNot { (android, ios) -> matchesNamingRule(android, ios) },
            emptyList<Pair<String, String>>(),
        )

        // The second category: free count, but each entry must be self-explanatory
        // and must not collide with a parity name (that would make the pulled
        // registry ambiguous for the host).
        val androidOnly = AndroidOnlyState.entries
        androidOnly.forEach { state ->
            assertEquals(
                "${state.androidName}: androidOnly states carry no iOS file",
                AndroidOnlyState.NO_IOS_BASELINE,
                state.iosFile,
            )
            assertTrue(
                "${state.androidName}: reason must start with " +
                    "'${AndroidOnlyState.REASON_PREFIX}' so a reader learns why it is here",
                state.reason.startsWith(AndroidOnlyState.REASON_PREFIX),
            )
            assertFalse("${state.androidName}: owner is empty", state.owner.isBlank())
            assertFalse(
                "${state.androidName}: an androidOnly name collides with a parity state",
                states.any { it.androidName == state.androidName },
            )
        }
        assertEquals(
            "androidOnly names must be unique",
            androidOnly.size,
            androidOnly.map { it.androidName }.toSet().size,
        )
    }

    /** `page-chat-default` <- `ios-chat-default`; the two `-default` suffixes are the exception. */
    private fun matchesNamingRule(androidName: String, iosFile: String): Boolean {
        val stem = iosFile.removePrefix("ios-")
        return androidName == "page-$stem" ||
            androidName == "page-$stem-default" ||
            androidName == "surface-$stem" ||
            androidName == "env-$stem"
    }

    // --------------------------------------------------------------- capture

    @Test
    fun pageChatDefault() {
        captureParity(ParityState.PAGE_CHAT_DEFAULT)
    }

    @Test
    fun pageChatFailed() {
        captureParity(ParityState.PAGE_CHAT_FAILED)
    }

    @Test
    fun pageChatLong() {
        captureParity(ParityState.PAGE_CHAT_LONG)
    }

    @Test
    fun pageChatDraft() {
        captureParity(ParityState.PAGE_CHAT_DRAFT)
    }

    // `page-sessions-default` is NOT captured here: the Sessions lane owns that
    // state (0 sessions, 0 folders — the registry's note), and
    // `SessionsParityScreenshotTest#pageSessionsDefault` writes it with the
    // baseline's own samples. Two suites writing one PNG would make the baseline
    // depend on class order, so the older capture was retired.

    @Test
    fun pageSettingsDefault() {
        captureParity(ParityState.PAGE_SETTINGS_DEFAULT)
    }

    // The Settings-family states this lane froze (samples: `SettingsParitySamples`).
    @Test
    fun pageSettingsAbout() {
        captureParity(ParityState.PAGE_SETTINGS_ABOUT)
    }

    @Test
    fun pageSettingsModel() {
        captureParity(ParityState.PAGE_SETTINGS_MODEL)
    }

    @Test
    fun pageSettingsNetwork() {
        captureParity(ParityState.PAGE_SETTINGS_NETWORK)
    }

    @Test
    fun pageSettingsPlugins() {
        captureParity(ParityState.PAGE_SETTINGS_PLUGINS)
    }

    @Test
    fun pageSettingsRuntime() {
        captureParity(ParityState.PAGE_SETTINGS_RUNTIME)
    }

    @Test
    fun pageSettingsSearch() {
        captureParity(ParityState.PAGE_SETTINGS_SEARCH)
    }

    private fun captureParity(state: ParityState): Unit {
        check(state.status == ParityStatus.CAPTURED) {
            "${state.androidName} is registered but not captured yet (owner: ${state.owner})"
        }
        setCapturedContent { parityContent(state) }
        capture(state.androidName)
    }

    /**
     * The page for one state.
     *
     * `else` is a real error, not a fallback: a state without content here would
     * otherwise capture an empty white frame and quietly become a baseline.
     */
    @Composable
    private fun parityContent(state: ParityState) {
        when (state) {
            ParityState.PAGE_CHAT_DEFAULT -> ChatPage(
                state = ScreenshotSamples.chatState(),
                onDraftChange = {},
                onSend = {},
                onStop = {},
                onToggleRow = {},
                onApprove = {},
                onDeny = {},
                onDismissBanner = {},
                onRetry = {},
                onOpenSessions = {},
                title = "像素基线复盘",
            )

            ParityState.PAGE_CHAT_FAILED -> ChatPage(
                // The parity sample sets `userFacingError`, which is what the page's
                // top-banner clause keys on. The gate sample (`chatStateWithBanner`)
                // predates that clause and renders no banner at HEAD, so it is not
                // reused here.
                state = ScreenshotSamples.chatStateWithTopError(),
                onDraftChange = {},
                onSend = {},
                onStop = {},
                onToggleRow = {},
                onApprove = {},
                onDeny = {},
                onDismissBanner = {},
                onRetry = {},
                onOpenSessions = {},
                title = "像素基线复盘",
            )

            ParityState.PAGE_CHAT_LONG -> ChatPage(
                state = ScreenshotSamples.chatStateLongThread(),
                onDraftChange = {},
                onSend = {},
                onStop = {},
                onToggleRow = {},
                onApprove = {},
                onDeny = {},
                onDismissBanner = {},
                onRetry = {},
                onOpenSessions = {},
                title = "像素基线复盘",
            )

            ParityState.PAGE_CHAT_DRAFT -> ChatPage(
                state = ScreenshotSamples.chatStateWithLongDraft(),
                onDraftChange = {},
                onSend = {},
                onStop = {},
                onToggleRow = {},
                onApprove = {},
                onDeny = {},
                onDismissBanner = {},
                onRetry = {},
                onOpenSessions = {},
                title = "像素基线复盘",
            )

            // `PAGE_SESSIONS_DEFAULT` is captured by
            // `SessionsParityScreenshotTest#pageSessionsDefault` (the owning lane).

            // The 通用 tab: the shell (bar + five-segment picker) over its six cards.
            ParityState.PAGE_SETTINGS_DEFAULT -> SettingsPage(
                state = SettingsParitySamples.indexState(),
                onNavigate = {},
                onToggleBackgroundKeepAlive = {},
                onSelectBusyEnterMode = {},
                onBack = {},
                tab = SettingsTab.GENERAL,
            )

            // The 模型 tab: iOS's `ModelProviderSettingsPage` *is* this tab's body, so
            // the capture is the shell with that page embedded (no second bar).
            ParityState.PAGE_SETTINGS_MODEL -> SettingsPage(
                state = SettingsParitySamples.indexState(),
                onNavigate = {},
                onToggleBackgroundKeepAlive = {},
                onSelectBusyEnterMode = {},
                onBack = {},
                tab = SettingsTab.MODELS,
                tabContent = {
                    ModelProviderSettingsPage(
                        state = SettingsParitySamples.modelProviderState(),
                        onAdd = {},
                        onEdit = {},
                        onToggleSection = {},
                        onBack = {},
                        embedded = true,
                    )
                },
            )

            // `settings.about` / `settings.network` / `settings.runtime` are the iOS
            // DEBUG router's preview-only screens (`NativeShell.swift:778-783`): after
            // PR #13 no settings tab has a row that opens them, on either platform.
            // The Android capture is therefore the page itself, which is the same
            // thing the iOS baseline photographed.
            ParityState.PAGE_SETTINGS_ABOUT -> AboutPage(
                state = SettingsParitySamples.aboutState(),
                onToggleSection = {},
                onBack = {},
            )

            ParityState.PAGE_SETTINGS_MODEL -> ModelProviderSettingsPage(
                state = SettingsParitySamples.modelProviderState(),
                onAdd = {},
                onEdit = {},
                onToggleSection = {},
                onBack = {},
            )

            ParityState.PAGE_SETTINGS_NETWORK -> EgressAllowListPage(
                state = SettingsParitySamples.egressState(),
                onBack = {},
                onToggleSection = {},
                onToggleAllowAll = {},
                onRestoreDefaults = {},
                onAddRule = {},
                onRemoveRule = {},
            )

            // The 插件 tab: `NativeConfigurationPage` embedded, exactly as the iOS
            // 插件 tab embeds `NativePluginInventoryPage` → `NativeConfigurationPage`.
            ParityState.PAGE_SETTINGS_PLUGINS -> SettingsPage(
                state = SettingsParitySamples.indexState(),
                onNavigate = {},
                onToggleBackgroundKeepAlive = {},
                onSelectBusyEnterMode = {},
                onBack = {},
                tab = SettingsTab.PLUGINS,
                tabContent = {
                    NativeConfigurationPage(
                        state = SettingsParitySamples.configurationState(),
                        onDone = {},
                        onToggleSection = {},
                        onRefresh = {},
                        onOpenWebSearch = {},
                        embedded = true,
                    )
                },
            )

            ParityState.PAGE_SETTINGS_RUNTIME -> RuntimeDetailsPage(
                state = SettingsParitySamples.runtimeState(),
                onBack = {},
                onToggleSection = {},
                onRequestStop = {},
                onCancelStop = {},
                onConfirmStop = {},
                onRestart = {},
            )

            ParityState.PAGE_SETTINGS_SEARCH -> WebSearchSettingsPage(
                state = SettingsParitySamples.webSearchState(),
                onToggle = {},
                onSelectBackend = {},
                onToggleBackends = {},
                onBack = {},
            )

            else -> error(
                "no content registered for ${state.androidName}; a PENDING state must not be " +
                    "added to this suite before its page lane freezes the page",
            )
        }
    }
}
