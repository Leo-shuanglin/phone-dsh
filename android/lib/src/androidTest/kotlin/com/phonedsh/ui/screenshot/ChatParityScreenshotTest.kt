// The Chat family's parity captures, in L0's shared 402 x 874 @3 harness.
// SPDX-License-Identifier: MIT
//
// Scope: this class captures only the Chat states that `ParityState` still marks
// `PENDING` and that no other suite owns — `page-chat-assistant`,
// `page-chat-official`, `page-chat-queued`, `page-chat-permission` and
// `surface-code-block`. The four `CAPTURED` chat states (`-default`, `-failed`,
// `-long`, `-draft`) are captured by `ParityScreenshotTest`; re-capturing them
// here would write the same file twice.
//
// The capture names are read off `ParityState` ([androidName]), never typed here,
// so a rename in the registry renames the PNG and the host's registry check keeps
// working. The frame, the metrics receipt and the output directory come from
// [ScreenshotTestBase] / [ScreenshotProfile.IOS_PARITY] / [ScreenshotStore].
//
// What a parity capture proves: the page composition in the iOS frame. It never
// proves cross-platform glyph raster equality (Minikin/HarfBuzz vs CoreText).
package com.phonedsh.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonedsh.ui.UiMetrics
import com.phonedsh.ui.nav.PdshNavFacts
import com.phonedsh.ui.nav.PdshNavHost
import com.phonedsh.ui.nav.PdshNavState
import com.phonedsh.ui.nav.PdshRoute
import com.phonedsh.ui.page.ChatPage
import com.phonedsh.ui.surface.CodeBlock
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatParityScreenshotTest : ScreenshotTestBase() {

    /** These captures only mean anything in the iOS frame. */
    override val allowedProfiles = setOf(ScreenshotProfile.IOS_PARITY)

    // ------------------------------------------------------------------ states

    @Test
    fun pageChatAssistant() {
        val state = ParityState.PAGE_CHAT_ASSISTANT
        setCapturedContent { chatPage(ChatParitySamples.chatAssistant()) }
        capture(state.androidName)
    }

    @Test
    fun pageChatOfficial() {
        val state = ParityState.PAGE_CHAT_OFFICIAL
        setCapturedContent { chatPage(ChatParitySamples.chatOfficial()) }
        capture(state.androidName)
    }

    @Test
    fun pageChatQueued() {
        val state = ParityState.PAGE_CHAT_QUEUED
        setCapturedContent { chatPage(ChatParitySamples.chatQueued()) }
        capture(state.androidName)
    }

    @Test
    fun pageChatPermission() {
        val state = ParityState.PAGE_CHAT_PERMISSION
        setCapturedContent { chatPage(ChatParitySamples.chatPermission()) }
        capture(state.androidName)
    }

    /**
     * `ios-code-block.png` is a component swatch, not a page: the iOS shot has no
     * header and no composer, only the card at the top of the 402 pt frame. The
     * capture therefore draws the surface alone in the page-width column.
     */
    @Test
    fun surfaceCodeBlock() {
        val state = ParityState.SURFACE_CODE_BLOCK
        setCapturedContent(
            widthDp = UiMetrics.BASELINE_WIDTH_PT,
            heightDp = UiMetrics.BASELINE_HEIGHT_PT,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_MARGIN_PT.dp)
                    .testTag(CODE_SWATCH_TAG),
            ) {
                CodeBlock(
                    language = ChatParitySamples.CODE_BLOCK_LANGUAGE,
                    body = ChatParitySamples.CODE_BLOCK_BODY,
                )
            }
        }
        capture(state.androidName)
    }

    // ----------------------------------------------------------------- routing

    /**
     * The routing receipt: the page is rendered by [PdshNavHost] from
     * [PdshRoute.Chat], i.e. the same path the app takes, not a direct `ChatPage`
     * call. If the route fell back to `Unit` in the router's `when`, this frame
     * would be blank and the capture would be the only evidence needed.
     *
     * The name is deliberately outside `ParityState`: there is no iOS counterpart
     * for "the router resolved the route", so it is not a parity baseline.
     */
    @Test
    fun chatRouteThroughNavHost() {
        setCapturedContent {
            PdshNavHost(
                nav = PdshNavState(stack = listOf(PdshRoute.Home, PdshRoute.Chat)),
                facts = PdshNavFacts(),
                onNavigate = {},
                onBack = {},
                onHome = {},
                onHostAction = {},
                home = {},
                webSurface = {},
            )
        }
        capture(ROUTE_CAPTURE_NAME)
    }

    @Composable
    private fun chatPage(state: com.phonedsh.ui.state.ChatScreenState) {
        ChatPage(
            state = state,
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onToggleRow = {},
            onApprove = {},
            onDeny = {},
            onDismissBanner = {},
            onRetry = {},
            onOpenSessions = {},
            title = ChatParitySamples.TITLE,
        )
    }

    private companion object {
        /** The page margin both baselines use for the transcript column (16 pt). */
        const val PAGE_MARGIN_PT: Int = UiMetrics.COMPOSER_OUTER_MARGIN_PT

        /** The swatch frame's tag (the capture reads it back). */
        const val CODE_SWATCH_TAG: String = "pdsh.chat-parity.code-swatch"

        /** The routing receipt's PNG name; not a parity baseline. */
        const val ROUTE_CAPTURE_NAME: String = "page-chat-route"
    }
}
