// Compose interaction checks for the PR22 home drawer overlay.
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CompletableDeferred
import com.phonedsh.ui.theme.PdshTheme
import com.phonedsh.ui.state.StateRequestId
import com.phonedsh.ui.state.StateRevision
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
                    HomeDrawer(HomeDrawerFacts(), onClose = { closed = true }, onCreateSession = { true })
                }
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.NEW_SESSION).assertIsDisplayed()
        compose.onNodeWithTag(HomeDrawerTags.SCHEDULES).assertExists()
        compose.onNodeWithTag(HomeDrawerTags.SETTINGS).assertExists()
        compose.onNodeWithTag(HomeDrawerTags.SCRIM).performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }

    @Test fun newSessionKeepsDrawerOpenUntilHostConfirmsCreation() {
        var confirmed = false
        var calls = 0
        compose.setContent {
            PdshTheme(darkTheme = false) {
                HomeDrawer(
                    facts = HomeDrawerFacts(),
                    onClose = { confirmed = true },
                    onCreateSession = { calls++; false },
                )
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.NEW_SESSION).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(HomeDrawerTags.PANEL).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, calls)
            assertEquals(false, confirmed)
        }
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

    @Test fun title日常DoesNotTurnACustomFolderIntoTheDefaultOrAbsorbLooseSessions() {
        val facts = HomeDrawerFacts(
            folders = listOf(
                HomeDrawerFolder("custom-daily", "日常", emptyList(), isDefault = false),
            ),
            unassignedSessions = listOf(HomeDrawerSession("loose", "未分配会话")),
        )
        compose.setContent {
            PdshTheme(darkTheme = false) {
                HomeDrawer(facts, onClose = {}, onOpenSession = {})
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.FOLDER_PREFIX + "custom-daily")
            .assertContentDescriptionEquals("日常，0 个会话")
        compose.onNodeWithTag(HomeDrawerTags.FOLDER_PREFIX + "__unassigned__")
            .assertContentDescriptionEquals("未分配，1 个会话")
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "loose").assertIsDisplayed()
    }

    @Test fun sessionLongPressOffersRealRenameAndArchiveCallbacks() {
        var archived = ""
        val facts = HomeDrawerFacts(
            folders = listOf(HomeDrawerFolder("daily", "日常", listOf(HomeDrawerSession("s1", "会话一")), true)),
            unassignedSessions = emptyList(),
        )
        compose.setContent {
            PdshTheme(darkTheme = false) {
                HomeDrawer(facts, onClose = {}, onArchiveSession = { request ->
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
                HomeDrawer(facts, onClose = {}, onArchiveSession = { request ->
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

    @Test fun folderCreationUsesConfirmedHostMutationAndShowsFailedReceipt() {
        var command: PdshUiHostCommand? = null
        compose.setContent {
            PdshTheme {
                HomeDrawer(
                    facts = HomeDrawerFacts(folders = emptyList(), unassignedSessions = emptyList()),
                    onClose = {},
                    onMutation = { request ->
                        command = request
                        val id = (request as PdshUiHostCommand.WorkspaceFolderCreate).requestId
                        PdshActionReceipt(id, null, null, PdshActionOutcome.FAILED)
                    },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.CREATE_FOLDER).performClick()
        compose.onNodeWithText("新建文件夹").performClick()
        compose.onNodeWithTag(HomeDrawerTags.EDITOR).performTextInput("Research")
        compose.onNodeWithText("创建").performClick()
        compose.waitForIdle()
        val create = command as? PdshUiHostCommand.WorkspaceFolderCreate
        assertEquals("Research", create?.name)
        assertNotNull(create?.requestId?.value)
        compose.onNodeWithTag(HomeDrawerTags.FAILURE).assertIsDisplayed()
        compose.onNodeWithTag(HomeDrawerTags.RETRY).assertIsDisplayed()
    }

    @Test fun renameSessionIsRequestBoundAndDoesNotHideRowAfterFailedReceipt() {
        var command: PdshUiHostCommand? = null
        val facts = HomeDrawerFacts(
            folders = listOf(HomeDrawerFolder("folder-1", "Research", listOf(HomeDrawerSession("session-1", "Before")))),
            unassignedSessions = emptyList(),
        )
        compose.setContent {
            PdshTheme {
                HomeDrawer(
                    facts = facts,
                    onClose = {},
                    onMutation = { request ->
                        command = request
                        val rename = request as PdshUiHostCommand.SessionRename
                        PdshActionReceipt(rename.requestId, rename.sessionId, null, PdshActionOutcome.FAILED)
                    },
                )
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "session-1").performTouchInput { longClick() }
        compose.onNodeWithText("编辑名称").performClick()
        compose.onNodeWithTag(HomeDrawerTags.EDITOR).performTextClearance()
        compose.onNodeWithTag(HomeDrawerTags.EDITOR).performTextInput("After")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        val rename = command as? PdshUiHostCommand.SessionRename
        assertEquals("session-1", rename?.sessionId)
        assertEquals("After", rename?.title)
        compose.onNodeWithTag(HomeDrawerTags.FAILURE).assertIsDisplayed()
        compose.onNodeWithTag(HomeDrawerTags.SESSION_PREFIX + "session-1").assertIsDisplayed()
    }

    @Test fun folderContextMenuCreatesSessionWithExactFolderId() {
        var requestedFolder: String? = null
        var closed = false
        compose.setContent {
            PdshTheme {
                HomeDrawer(
                    facts = HomeDrawerFacts(
                        folders = listOf(HomeDrawerFolder("stable-folder-9", "Research", isDefault = false)),
                        unassignedSessions = emptyList(),
                    ),
                    onClose = { closed = true },
                    onCreateSessionInFolder = { folderId -> requestedFolder = folderId; true },
                )
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.FOLDER_PREFIX + "stable-folder-9").performTouchInput { longClick() }
        compose.onNodeWithText("在这里新建会话").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("stable-folder-9", requestedFolder)
            assertEquals(true, closed)
        }
    }

    @Test fun drawerRetryCallsHostSnapshotRefreshAndDisplaysReadError() {
        var retries = 0
        compose.setContent {
            PdshTheme {
                HomeDrawer(
                    facts = HomeDrawerFacts(failure = "实际读取失败"),
                    onClose = {},
                    onRetry = { retries++ },
                )
            }
        }
        compose.onNodeWithTag(HomeDrawerTags.FAILURE).assertIsDisplayed()
        compose.onNodeWithTag(HomeDrawerTags.RETRY).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
