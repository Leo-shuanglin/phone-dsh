// The route edges this lane owns: the drawer's 会话 row and the chat head's
// SPDX-License-Identifier: MIT
// session control both land on [PdshRoute.Sessions], and that route renders the
// host's real session list when the host supplies one.
//
// Why these assertions exist: the pages were routing-reachable but no tap led to
// them (L1: "还缺一行入口"; L2: "抽屉里没有会话入口"; L5: "app 内无可点入口").
// A route that compiles is not a destination a user can reach, so the tests here
// mount the real [PdshNavHost] and tap the real controls.
package com.phonedsh.ui.nav

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonedsh.ui.page.UiPageTags
import com.phonedsh.ui.theme.PdshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PdshReachabilityTest {

    @get:Rule
    val compose = createComposeRule()

    /** Mounts the real router on the JVM host; only [sessions] is optional. */
    private fun mount(
        nav: PdshNavState,
        onNavigate: (PdshRoute) -> Unit = {},
        onHostAction: (PdshHostAction) -> Unit = {},
        sessions: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ) {
        compose.setContent {
            PdshTheme(darkTheme = false) {
                PdshNavHost(
                    nav = nav,
                    facts = PdshNavFacts(),
                    onNavigate = onNavigate,
                    onBack = {},
                    onHome = {},
                    onHostAction = onHostAction,
                    home = { BasicText(text = "home") },
                    webSurface = {},
                    sessions = sessions,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the sessions route renders the host's real list`() {
        mount(
            nav = PdshNavState(listOf(PdshRoute.Home, PdshRoute.Sessions)),
            sessions = { BasicText(text = "host session list") },
        )
        compose.onNodeWithText("host session list").assertIsDisplayed()
    }

    @Test
    fun `without a host list the router still renders a sessions page`() {
        mount(nav = PdshNavState(listOf(PdshRoute.Home, PdshRoute.Sessions)))
        // The facts-only page reports the folder list as unknown; it never claims
        // an empty workspace (`SessionsRoute`'s documented default state). The
        // line is the block's spoken name, not a text node: the page merges it.
        compose.onNodeWithTag(UiPageTags.SESSIONS_WORKSPACE_UNKNOWN).assertExists()
    }

    @Test
    fun `the chat head's session control pushes the sessions route`() {
        var pushed: PdshRoute? = null
        mount(
            nav = PdshNavState(listOf(PdshRoute.Home, PdshRoute.Chat)),
            onNavigate = { pushed = it },
        )
        compose.onNodeWithTag(UiPageTags.CHAT_HEADER_DRAWER).performClick()
        compose.runOnIdle { assertEquals(PdshRoute.Sessions, pushed) }
    }

    @Test
    fun `the automation bar controls reach the page's own editor and the host`() {
        // L11's page renders the ✕/刷新/新建 bar only when the callbacks are
        // non-null; leaving them out made the collected screenshots disagree
        // with the app. 刷新 is a host read, while 新建 now presents the page's own
        // `ScheduleEditorPage` (README §1: an embedded subpage ships with its
        // parent) — and that form's 保存 is the handoff, so a tap here must still
        // end at a host action rather than a dead control.
        val actions = mutableListOf<PdshHostAction>()
        mount(
            nav = PdshNavState(listOf(PdshRoute.Home, PdshRoute.Automation)),
            onHostAction = { actions += it },
        )
        compose.onNodeWithTag(UiPageTags.AUTOMATION_REFRESH).assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.AUTOMATION_NEW).assertIsDisplayed()
        compose.onNodeWithTag(UiPageTags.AUTOMATION_REFRESH).performClick()
        compose.onNodeWithTag(UiPageTags.AUTOMATION_NEW).performClick()
        compose.waitForIdle()
        // 新建 opened the form instead of handing a "please edit" note to a host
        // editor that does not exist.
        compose.onNodeWithTag(UiPageTags.AUTOMATION_EDITOR_ROOT).assertIsDisplayed()
        assertEquals(listOf<PdshHostAction>(PdshHostAction.RefreshAutomation), actions)

        // 保存 before the required fields carry text would be a no-op; fill them in
        // and the same control hands the draft to the host.
        compose.onNodeWithTag(UiPageTags.AUTOMATION_EDITOR_NAME).performTextInput("周报")
        compose.onNodeWithTag(UiPageTags.AUTOMATION_EDITOR_PROMPT).performTextInput("总结这一周")
        compose.onNodeWithTag(UiPageTags.AUTOMATION_EDITOR_SAVE).performClick()
        compose.waitForIdle()
        assertEquals(
            listOf<PdshHostAction>(
                PdshHostAction.RefreshAutomation,
                PdshHostAction.OpenAutomationEditor(null),
            ),
            actions,
        )
    }

    @Test
    fun `the drawer's session row and the route agree on the destination`() {
        // The drawer row and the chat head must not name two different faces for
        // 会话: one route, one address.
        val entries = listOf(
            PdshDrawerEntry("会话", PdshDrawerIcon.SESSIONS, PdshRoute.Sessions),
        )
        var pushed: PdshRoute? = null
        compose.setContent {
            PdshTheme(darkTheme = false) {
                PdshDrawer(
                    entries = entries,
                    footerEntries = emptyList(),
                    onSelect = { pushed = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag(PdshDrawerTags.ROW_PREFIX + "会话").performClick()
        compose.runOnIdle { assertEquals(PdshRoute.Sessions, pushed) }
    }
}
