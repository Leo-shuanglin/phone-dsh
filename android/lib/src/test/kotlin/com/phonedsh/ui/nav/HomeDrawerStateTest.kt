// Compose interaction checks for the PR22 home drawer overlay.
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CompletableDeferred
import com.phonedsh.ui.theme.PdshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeDrawerStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun overlayShowsEntriesAndClosesFromScrim() {
        var closed = false
        compose.setContent {
            PdshTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    HomeDrawer(HomeDrawerFacts(), onClose = { closed = true }, onCreateSession = {})
                }
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.NEW_SESSION).assertIsDisplayed()
        compose.onNodeWithTag(HomeDrawerTags.SCHEDULES).assertExists()
        compose.onNodeWithTag(HomeDrawerTags.SETTINGS).assertExists()
        compose.onNodeWithTag(HomeDrawerTags.SCRIM).performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }

    @Test fun dialogBackDismissesDrawer() {
        var closed = false
        compose.setContent {
            PdshTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) { HomeDrawer(HomeDrawerFacts(), onClose = { closed = true }) }
            }
        }
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        (dialog as OnBackPressedDispatcherOwner).onBackPressedDispatcher.onBackPressed()
        compose.runOnIdle { assertEquals(true, closed) }
    }

    @Test fun rendersDefaultAndUnassignedGroupsAndPreservesFolderFold() {
        val facts = HomeDrawerFacts(
            folders = listOf(HomeDrawerFolder("daily", "日常", emptyList(), isDefault = true)),
            unassignedSessions = listOf(HomeDrawerSession("s1", "会话一")),
        )
        compose.setContent {
            PdshTheme(darkTheme = false) {
                HomeDrawer(facts, onClose = {}, onOpenSession = {})
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.FOLDER_PREFIX + "daily").assertExists()
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "s1").assertExists()
        compose.onNodeWithTag(HomeDrawerTags.FOLDER_PREFIX + "daily").performClick()
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "s1").assertDoesNotExist()
        compose.onNodeWithTag(HomeDrawerTags.FOLDER_PREFIX + "daily").performClick()
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "s1").assertExists()
    }

    @Test fun sessionLongPressOffersRealRenameAndArchiveCallbacks() {
        var archived = ""
        val facts = HomeDrawerFacts(
            folders = listOf(HomeDrawerFolder("daily", "日常", listOf(HomeDrawerSession("s1", "会话一")), true)),
            unassignedSessions = emptyList(),
        )
        compose.setContent {
            PdshTheme(darkTheme = false) {
                HomeDrawer(facts, onClose = {}, onRenameSession = { _, _ -> }, onArchiveSession = { request ->
                    archived = request.sessionId
                    HomeDrawerArchiveResult.Archived(request.requestId, request.sessionId)
                })
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "s1").performTouchInput { longClick() }
        compose.onNodeWithText("归档").performClick()
        compose.runOnIdle { assertEquals("s1", archived) }
        compose.onNodeWithText("已归档，可在 设置 › 已归档会话 里找回").assertExists()
    }

    @Test fun archiveWaitsForCorrelatedHostResultAndFailureCanRetry() {
        val firstResult = CompletableDeferred<HomeDrawerArchiveResult>()
        var firstRequest: HomeDrawerArchiveRequest? = null
        var calls = 0
        val facts = HomeDrawerFacts(
            folders = listOf(HomeDrawerFolder("daily", "日常", listOf(HomeDrawerSession("s1", "会话一")), true)),
            unassignedSessions = emptyList(),
        )
        compose.setContent {
            PdshTheme(darkTheme = false) {
                HomeDrawer(facts, onClose = {}, onRenameSession = { _, _ -> }, onArchiveSession = { request ->
                    calls++
                    if (calls == 1) {
                        firstRequest = request
                        firstResult.await()
                    } else {
                        HomeDrawerArchiveResult.Archived(request.requestId, request.sessionId)
                    }
                })
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "s1").performTouchInput { longClick() }
        compose.onNodeWithText("归档").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(HomeDrawerTags.ARCHIVE_PENDING_PREFIX + "s1").assertExists()
        compose.onNodeWithText("已归档，可在 设置 › 已归档会话 里找回").assertDoesNotExist()

        val request = requireNotNull(firstRequest)
        firstResult.complete(HomeDrawerArchiveResult.Failed(request.requestId, request.sessionId, "保存未完成"))
        compose.waitForIdle()
        compose.onNodeWithTag(HomeDrawerTags.ARCHIVE_FAILURE_PREFIX + "s1").assertExists()
        compose.onNodeWithText("已归档，可在 设置 › 已归档会话 里找回").assertDoesNotExist()
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "s1").assertExists()

        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "s1").performTouchInput { longClick() }
        compose.onNodeWithText("归档").performClick()
        compose.onNodeWithText("已归档，可在 设置 › 已归档会话 里找回").assertExists()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun closingWhileArchiveIsPendingSuppressesLateSuccessToast() {
        val response = CompletableDeferred<HomeDrawerArchiveResult>()
        var request: HomeDrawerArchiveRequest? = null
        compose.setContent {
            PdshTheme(darkTheme = false) {
                val open = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                Box(Modifier.fillMaxSize()) {
                    if (open.value) {
                        HomeDrawer(
                            facts = HomeDrawerFacts(
                                folders = listOf(HomeDrawerFolder("daily", "日常", listOf(HomeDrawerSession("s1", "会话一")), true)),
                                unassignedSessions = emptyList(),
                            ),
                            onClose = { open.value = false },
                            onRenameSession = { _, _ -> },
                            onArchiveSession = { archiveRequest -> request = archiveRequest; response.await() },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "s1").performTouchInput { longClick() }
        compose.onNodeWithText("归档").performClick()
        compose.onNodeWithTag(HomeDrawerTags.SCRIM).performClick()
        val archiveRequest = requireNotNull(request)
        response.complete(HomeDrawerArchiveResult.Archived(archiveRequest.requestId, archiveRequest.sessionId))
        compose.waitForIdle()
        compose.onNodeWithText("已归档，可在 设置 › 已归档会话 里找回").assertDoesNotExist()
    }
}
