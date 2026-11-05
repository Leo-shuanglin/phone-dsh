// SPDX-License-Identifier: MIT
//
// Regression for android-parity-4: the user row must measure its real container
// before the reading inset, because the shared bubble rule subtracts that inset.
package com.phonedsh.ui.page

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonedsh.ui.ChatMetrics
import com.phonedsh.ui.HomeMetrics
import com.phonedsh.ui.UiTags
import com.phonedsh.ui.state.ChatRow
import com.phonedsh.ui.theme.PdshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A phone window wide enough for both measured frames; each test forces the row
// itself to the frame under test.
@Config(sdk = [35], qualifiers = "w430dp-h932dp")
class ChatUserBubbleWidthTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `user row measures the 360dp frame before its reading inset`() {
        assertMeasuredBubbleWidth(frame = 360.dp)
    }

    @Test
    fun `user row keeps the shared rule in a narrow 280dp frame`() {
        assertMeasuredBubbleWidth(frame = 280.dp)
    }

    @Test
    fun `CJK text may use its intrinsic width but never exceeds the shared cap`() {
        assertMeasuredBubbleWidth(360.dp, text = "宽".repeat(200), saturatesCap = false)
    }

    private fun assertMeasuredBubbleWidth(
        frame: Dp,
        text: String = "W".repeat(400),
        saturatesCap: Boolean = true,
    ) {
        compose.setContent {
            PdshTheme {
                ChatRowItem(
                    row = ChatRow.User(id = "user-${frame.value}", text = text),
                    expandedRowIds = emptySet(),
                    onToggleRow = {},
                    modifier = Modifier.requiredWidth(frame),
                )
            }
        }
        val bubbleWidth = compose
            .onNodeWithTag(UiTags.BUBBLE)
            .fetchSemanticsNode()
            .boundsInRoot
            .width
        val expected = ChatMetrics.userBubbleMaxWidth(frame, HomeMetrics.contentInset)
        val expectedPx = with(compose.density) { expected.toPx() }
        if (saturatesCap) {
            assertEquals(
                "user bubble at a ${frame.value}dp row must use the shared formula",
                expectedPx,
                bubbleWidth,
                1f,
            )
        } else {
            assertTrue("CJK text must remain visible", bubbleWidth > 0f)
            assertTrue("CJK intrinsic width must not exceed the cap", bubbleWidth <= expectedPx + 1f)
        }
    }
}
