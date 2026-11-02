// About page: version/platform facts, the fold sequence and the honest unknowns.
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.page

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AboutPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val facts = AboutFacts(version = "0.1.0")

    private fun show(
        facts: AboutFacts,
        onBack: () -> Unit = {},
        onOpenLicenses: (() -> Unit)? = null,
        onOpenDeveloper: (() -> Unit)? = null,
    ) {
        val state = AboutScreenState.from(facts)
        compose.setContent {
            PdshTheme {
                AboutPage(
                    state = state,
                    onToggleSection = {},
                    onBack = onBack,
                    onOpenLicenses = onOpenLicenses,
                    onOpenDeveloper = onOpenDeveloper,
                )
            }
        }
    }

    /**
     * No facts at all: the page is the baseline's one card, and no version is
     * invented.
     *
     * `NativeShell.swift:897-908` is `List { LabeledContent("版本", …); Text("会话记录…") }`
     * — one card, one row, one footnote, no section titles. The card of five
     * 未知 platform rows this page used to draw is not in that baseline, and five
     * unknowns are not information, so the projection emits no 系统 card when the
     * host read no `PlatformInfo`.
     */
    @Test
    fun `about with no facts is the baseline's one card and never a fallback version`() {
        val state = AboutScreenState.from(AboutFacts())
        assertEquals(listOf(AboutScreenState.VERSION_SECTION_ID), state.sections.map { it.id })
        val version = state.sections.single().rows
        assertEquals(listOf(UiStrings.UNKNOWN), version.map { it.value })
        assertEquals(UiStrings.ABOUT_SECTION_VERSION, version.single().title)
        show(AboutFacts())
        compose.onNodeWithTag(UiTags.ABOUT_LIST).assertIsDisplayed()
        compose.onAllNodesWithText(UiStrings.UNKNOWN).assertCountEquals(1)
        compose.onNodeWithText(UiStrings.ABOUT_LOCAL_NOTE).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.ABOUT_DEVELOPER).assertDoesNotExist()
    }

    @Test
    fun `measured facts render their values and the licenses row navigates when wired`() {
        var licenses = 0
        show(facts, onOpenLicenses = { licenses += 1 })
        compose.onNodeWithText(UiStrings.ABOUT_LICENSES).assertIsDisplayed()
        compose.onNodeWithText("0.1.0").assertIsDisplayed()
        compose.onNodeWithText(UiStrings.ABOUT_LOCAL_NOTE).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.ABOUT_LICENSES).performClick()
        assertEquals(1, licenses)
        compose.onNodeWithText(UiStrings.ABOUT_DEVELOPER).assertDoesNotExist()
    }

    @Test
    fun `the debug developer entry appears only when the build has one`() {
        var developers = 0
        show(
            facts.copy(developerEntry = true),
            onOpenLicenses = {},
            onOpenDeveloper = { developers += 1 },
        )
        compose.onNodeWithText(UiStrings.ABOUT_DEVELOPER).performClick()
        assertEquals(1, developers)
    }

    /**
     * A host that cannot read the platform draws no 系统 card, and a sub-page with
     * no callback is **not drawn at all** rather than drawn as 不可用: the
     * baseline's about page has no licenses row to disable.
     */
    @Test
    fun `an unread version still draws the one card and an unwired sub-page draws no row`() {
        show(facts.copy(version = null), onOpenLicenses = null)
        compose.onAllNodesWithText(UiStrings.UNKNOWN).assertCountEquals(1)
        compose.onNodeWithText(UiStrings.ABOUT_API_LEVEL).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.ABOUT_LICENSES).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.UNAVAILABLE).assertDoesNotExist()
    }

    /**
     * L74: there is no section header to fold.
     *
     * The baseline draws no header and no disclosure (`NativeShell.swift:897-908`),
     * so the section titles are no longer on screen and a tap on the card does not
     * hide the rows it holds.
     */
    @Test
    fun `the about card has no fold header and no android-only rows`() {
        show(facts)
        compose.onNodeWithText(UiStrings.ABOUT_SECTION_SYSTEM).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.ABOUT_API_LEVEL).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.ABOUT_BUILD).assertDoesNotExist()
    }

    /** §3.1: same facts + same fold script → same state sequence, sections in order. */
    @Test
    fun `the about fold sequence is a pure function of the script`() {
        val script = listOf(
            AboutUiEvent.ToggleSection("about.version"),
            AboutUiEvent.ToggleSection("about.system"),
        )
        var local = AboutLocalState()
        val states = buildList {
            add(AboutScreenState.from(facts, local))
            script.forEach { event ->
                local = local.apply(event)
                add(AboutScreenState.from(facts, local))
            }
        }
        states.forEach { state ->
            // One card only: the 系统 appendix is not part of the page (L79), and
            // `facts` carries no developer entry so the 更多 card is absent too.
            assertEquals(listOf(AboutScreenState.VERSION_SECTION_ID), state.sections.map { it.id })
        }
        // The fold script still folds the model (it is the host's vocabulary); the
        // page simply has no header to drive it with any more.
        assertEquals(listOf(true, false, false), states.map { it.sections[0].expanded })
        var replay = AboutLocalState()
        val replayed = buildList {
            add(AboutScreenState.from(facts, replay))
            script.forEach { event ->
                replay = replay.apply(event)
                add(AboutScreenState.from(facts, replay))
            }
        }
        assertEquals(states, replayed)
    }
}
