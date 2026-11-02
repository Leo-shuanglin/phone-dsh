// Signature surface 2/6: the tool row (JVM + Robolectric).
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.surface

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.UiTags
import com.phonedsh.ui.theme.PdshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ToolRowSurfaceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `collapsed row shows title and summary and hides the detail`() {
        compose.setContent {
            PdshTheme {
                ToolRow(
                    title = "exec",
                    summary = "uid=1000",
                    state = ToolRowState.OK,
                    expanded = false,
                    onToggle = {},
                    detail = { ToolDetailCard(banner = "exec", body = "uid=1000\n") },
                )
            }
        }
        compose.onNodeWithTag(UiTags.TOOL_ROW).assertIsDisplayed()
        compose.onNodeWithText("exec").assertIsDisplayed()
        compose.onNodeWithText("uid=1000").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TOOL_ROW_DETAIL).assertDoesNotExist()
    }

    @Test
    fun `expanding reveals the terminal card and reports its state`() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            PdshTheme {
                ToolRow(
                    title = "exec",
                    summary = "uid=1000",
                    state = ToolRowState.OK,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    detail = { ToolDetailCard(banner = "exec", body = "uid=1000") },
                )
            }
        }
        compose.onNodeWithTag(UiTags.TOOL_ROW)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, UiStrings.TOOL_OK))
        compose.onNodeWithTag(UiTags.TOOL_ROW).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.TOOL_ROW_DETAIL).assertIsDisplayed()
        compose.onNodeWithTag(TOOL_ROW_CHEVRON_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(TOOL_ROW_CATEGORY_ICON_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the expanded row hides its summary unless the source enables it`() {
        compose.setContent {
            PdshTheme {
                ToolRow(
                    title = "exec",
                    summary = "short summary",
                    state = ToolRowState.OK,
                    expanded = true,
                    onToggle = {},
                    detail = { androidx.compose.foundation.text.BasicText("detail body") },
                )
            }
        }
        compose.onNodeWithTag(UiTags.TOOL_ROW).assertIsDisplayed()
        compose.onNodeWithText("detail body").assertIsDisplayed()
        compose.onNodeWithText("short summary").assertDoesNotExist()
        compose.onNodeWithTag(TOOL_ROW_CHEVRON_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TOOL_ROW)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("exec · ${UiStrings.TOOL_OK}")))
    }

    @Test
    fun `a row without detail cannot be expanded`() {
        compose.setContent {
            PdshTheme {
                ToolRow(
                    title = "read",
                    summary = "",
                    state = ToolRowState.RUNNING,
                    expanded = false,
                    onToggle = {},
                    detail = null,
                )
            }
        }
        compose.onNodeWithTag(UiTags.TOOL_ROW).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.TOOL_ROW)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, UiStrings.TOOL_RUNNING))
    }

    @Test
    fun `the toggle callback is invoked once per tap`() {
        var taps = 0
        compose.setContent {
            PdshTheme {
                ToolRow(
                    title = "exec",
                    summary = "ok",
                    state = ToolRowState.OK,
                    expanded = false,
                    onToggle = { taps += 1 },
                    detail = { ToolDetailCard(banner = "exec", body = "ok") },
                )
            }
        }
        compose.onNodeWithTag(UiTags.TOOL_ROW).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `an error state remains announced and its detail stays readable`() {
        compose.setContent {
            PdshTheme {
                ToolRow(
                    title = "运行命令",
                    summary = "permission denied",
                    state = ToolRowState.ERROR,
                    expanded = true,
                    onToggle = {},
                    detail = {
                        ToolDetailCard(
                            banner = "运行命令",
                            body = "permission denied: /workspace/output",
                            isError = true,
                        )
                    },
                    showsSummaryWhenExpanded = true,
                )
            }
        }
        compose.onNodeWithTag(UiTags.TOOL_ROW)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, UiStrings.TOOL_ERROR))
        compose.onNodeWithText("permission denied: /workspace/output").assertIsDisplayed()
    }
}
