// D3 entry point: one activity, one Compose screen, zero view construction here.
// SPDX-License-Identifier: MIT
package com.phonedsh.probe

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonedsh.dsh.background.NotificationCapability
import com.phonedsh.dsh.localexec.UbuntuApproval
import com.phonedsh.dsh.localexec.UbuntuApprovalDecision
import com.phonedsh.dsh.localexec.UbuntuApprovalGate
import com.phonedsh.dsh.localexec.UbuntuApprovalPrompt
import com.phonedsh.dsh.localexec.UbuntuApprovalRequest
import com.phonedsh.ui.page.PhoneCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private val engine: ProbeEngineViewModel by viewModels()

    /**
     * The one confirmation the `ubuntu` approval gate is waiting on, if any.
     *
     * The gate lives in `:dsh` and knows nothing about Compose; the prompt this
     * Activity installs publishes the request here, the dialog below renders it,
     * and the user's answer completes [PendingUbuntuApproval.answer]. It is a
     * `StateFlow` so the write can come from the host-tool coroutine.
     */
    private val pendingUbuntuApproval = MutableStateFlow<PendingUbuntuApproval?>(null)

    /**
     * Bumped when the notification answer changes what the background facts may
     * say, so `HostShell` rebuilds them instead of keeping the snapshot it took
     * before the dialog was answered.
     */
    private var backgroundRevision by mutableIntStateOf(0)

    /**
     * The phone-permissions page's counterpart: bumped when a permission answer
     * changes the matrix, and on every resume **after** the user may have changed a
     * grant somewhere else (the settings page this app sends them to, or the
     * system's own app info page). The facts are rebuilt from a fresh device read —
     * this counter never carries a status, so a page that was not re-read cannot
     * show an answer it did not observe.
     */
    private var permissionRevision by mutableIntStateOf(0)

    /** True while a 跑一轮 tap waits for the POST_NOTIFICATIONS answer. */
    private var turnAfterPermission = false

    /**
     * The one runtime permission this app asks for.
     *
     * `:dsh` declares POST_NOTIFICATIONS because both background channels stand
     * on a visible progress notification: UIDT's contract is "progress visible",
     * and a dataSync service without one is an invisible window. Asking is the
     * only way that fact can ever be YES on Android 13+, and asking *at the turn*
     * is the moment the promise is made — not at launch, where the user has asked
     * for nothing yet.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS_ANSWER granted=$granted")
        // The 跑一轮 prompt is the same permission the phone-permissions page shows,
        // so its answer is recorded in the one ask log the page reads: a denial here
        // must not leave that page claiming 未请求 for a permission the user refused.
        PhonePermissionAsks.record(listOf(NotificationCapability.POST_NOTIFICATIONS))
        backgroundRevision++
        permissionRevision++
        if (turnAfterPermission) {
            turnAfterPermission = false
            // Denied still runs the turn: it becomes a foreground-only turn, which
            // is the honest degradation rather than a refusal to work.
            engine.runTurn()
        }
    }

    /**
     * The phone-permissions page's one-permission prompt.
     *
     * Same contract as [notificationPermission] — the answer arrives after the
     * snapshot that drew the row was taken — but the answered permission is not
     * known at registration time, so the ask is read back from the log
     * ([PhonePermissionAsks.inFlightPermissions]) and recorded before the facts are
     * rebuilt. Nothing here reports success: the page's next read is the only thing
     * that can turn the row 已允许, and `granted` is logged, not stored, so a denied
     * answer can never be mistaken for a grant.
     */
    private val phonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permissions = PhonePermissionAsks.inFlightPermissions()
        Log.i(
            TAG,
            "PHONE_PERMISSION_ANSWER permissions=$permissions granted=$granted " +
                "rationale=${permissions.map(::shouldShowRequestPermissionRationale)}",
        )
        PhonePermissionAsks.answered(permissions)
        permissionRevision++
    }

    /**
     * 添加文件: the platform document picker (`ACTION_OPEN_DOCUMENT`).
     *
     * The Activity owns the picker because a system UI is Activity-scoped; the file
     * itself is imported by the view model into the workspace through the WAL. The
     * picker's answer is the URI the person chose — a cancelled pick is logged as a
     * cancellation, never as an import.
     */
    private val openChatFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            Log.i(TAG, "CHAT_PICK_CANCELLED kind=file")
        } else {
            Log.i(TAG, "CHAT_PICK kind=file uri_type=${contentResolver.getType(uri) ?: "-"}")
            engine.importIntoWorkspace(uri, image = false)
        }
    }

    /** 添加图片: the same picker restricted to images. */
    private val openChatImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            Log.i(TAG, "CHAT_PICK_CANCELLED kind=image")
        } else {
            Log.i(TAG, "CHAT_PICK kind=image uri_type=${contentResolver.getType(uri) ?: "-"}")
            engine.importIntoWorkspace(uri, image = true)
        }
    }

    /**
     * The multi-permission form, used where Android requires one prompt for a pair —
     * precise and approximate location must be requested together from API 31, or the
     * platform drops the request instead of showing a dialog.
     */
    private val phonePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        Log.i(TAG, "PHONE_PERMISSIONS_ANSWER result=$result")
        PhonePermissionAsks.answered(result.keys)
        permissionRevision++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The key store must exist before the launch config, because the config
        // asks it for the key's *state* while building the Worker env, and before
        // the view model constructs the gateway that asks it for the credential.
        ApiKeyStore.install(applicationContext)
        // The model service's device record, installed before the launch config:
        // `ProbeLaunchConfig.from` overlays the saved endpoint/model on the launch
        // extras, which is what makes a 保存 apply at the next host start.
        ProviderConfigStore.install(applicationContext)
        // The phone-permissions page's ask log, read before the first facts build so
        // the very first frame already knows which rows were already answered.
        PhonePermissionAsks.install(applicationContext)
        // Development only: a key dropped into the app's own files dir is imported and
        // the plaintext is deleted. Gated on the *install's* debuggable flag rather than
        // BuildConfig: `buildConfig` is on now (L61 needs SIGNING_NOTICE), but reading
        // FLAG_DEBUGGABLE stays the right check — it asks what this install is, not what
        // the module was compiled as, so a debug-signed release build still has no such
        // path (the release manifest sets isDebuggable = false).
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) ApiKeyStore.importDevelopmentKey(applicationContext)
        // The fail-loud collector: one developmental vault operation named by the
        // launch (reclaim-token matrix), then the previous-death audit. Both run on
        // their own threads and only ever log lookup state, never token bytes.
        ProbeRecovery.handleDiagnosticIntent(applicationContext, intent, debuggable)
        ProbeRecovery.auditAsync(applicationContext)
        // The launch values must be installed before the view model's first
        // access constructs the engine and its `env` page parameter.
        ProbeLaunchConfig.install(intent)
        // The `ubuntu` approval gate's dialog. Installed before the engine can
        // start so the first `ubuntu run` already has a surface; the gate reads
        // the prompt at decision time, so order is not a correctness property —
        // it is just the honest place for the install.
        UbuntuApproval.install(object : UbuntuApprovalPrompt {
            override suspend fun ask(request: UbuntuApprovalRequest): UbuntuApprovalDecision {
                val answer = CompletableDeferred<UbuntuApprovalDecision>()
                pendingUbuntuApproval.value = PendingUbuntuApproval(request, answer)
                return try {
                    answer.await()
                } finally {
                    // Clear only if this request is still the one on screen: a
                    // later request (or a destroy) may already have replaced it.
                    if (pendingUbuntuApproval.value?.answer === answer) pendingUbuntuApproval.value = null
                }
            }
        })
        // targetSdk 35 enforces edge-to-edge; consume the insets in ProbeScreen.
        enableEdgeToEdge()
        setContent {
            val state by engine.state.collectAsState()
            val hostState by engine.hostState.collectAsState()
            val handshake by engine.handshake.collectAsState()
            val channel by engine.channel.collectAsState()
            val turn by engine.turn.collectAsState()
            val recoveryNotice by ProbeRecovery.notice.collectAsState()
            val ubuntuApproval by pendingUbuntuApproval.asStateFlow().collectAsState()
            HostShell(
                engine = engine,
                state = state,
                hostState = hostState,
                handshake = handshake,
                channel = channel,
                turn = turn,
                recoveryNotice = recoveryNotice,
                backgroundRevision = backgroundRevision,
                permissionRevision = permissionRevision,
                onStart = engine::start,
                // Stopping the local engine ends every DSH session it hosts, so
                // the session-scoped `ubuntu` approval must go with it. Starting
                // again requires a fresh confirmation, which is the design's
                // "session end invalidates the grant" made observable.
                onStop = {
                    engine.stop()
                    UbuntuApproval.endSession()
                },
                onRunTurn = ::runTurnOrAskForNotifications,
                onPhonePermissionRequest = ::requestPhonePermission,
                onPhonePermissionSettings = ::openPhonePermissionSettings,
                // L69: the chat composer's two pickers. Their bytes go into the
                // workspace (WAL mutation), not into a private copy beside the stores.
                onPickFiles = { openChatFile.launch(arrayOf("*/*")) },
                onPickImages = { openChatImage.launch(arrayOf("image/*")) },
            )
            UbuntuApprovalDialog(
                pending = ubuntuApproval,
                onAnswer = { approved ->
                    val pending = ubuntuApproval ?: return@UbuntuApprovalDialog
                    pendingUbuntuApproval.value = null
                    pending.answer.complete(
                        if (approved) UbuntuApprovalDecision.Approved else UbuntuApprovalDecision.Denied,
                    )
                },
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A configuration change is the same session on a new Activity; anything
        // else destroys the surface that could answer a pending confirmation.
        if (isChangingConfigurations) return
        UbuntuApproval.install(null)
        UbuntuApproval.endSession()
        pendingUbuntuApproval.value?.answer?.complete(
            UbuntuApprovalDecision.Unavailable("activity_destroyed"),
        )
        pendingUbuntuApproval.value = null
    }

    /**
     * The 跑一轮 intent: ask for the notification permission first when Android
     * 13+ has not answered it yet, then run the turn.
     *
     * The turn is deferred until the answer because the window is opened at the
     * start of the turn: asking afterwards would make the *first* turn of every
     * install run without a background window, which is the gap this lane closes.
     * A denial does not block the turn.
     */
    private fun runTurnOrAskForNotifications() {
        if (!notificationsGranted() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            turnAfterPermission = true
            notificationPermission.launch(NotificationCapability.POST_NOTIFICATIONS)
        } else {
            engine.runTurn()
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(NotificationCapability.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        // A grant can change while this activity is stopped: the user may have just
        // been sent to the settings page by this very page, or revoked something from
        // the system's own app-info screen. Re-walking on resume is the only way the
        // matrix reports what the platform says now instead of what it said before.
        permissionRevision++
    }

    /**
     * 继续 in the phone-permissions confirmation: raise the real system request.
     *
     * The decision is the host's, taken here from the same readings the page was
     * drawn from: one declared runtime permission is asked with the single-permission
     * launcher, a pair (location) with the multiple form, and a capability with no
     * requestable permission at all goes to the system page that can change it —
     * which is the same thing its 去系统设置 control does. Nothing is faked: an empty
     * request set is not "granted", it is a row the page should not have armed.
     *
     * @param capability the row whose 继续 was pressed.
     */
    private fun requestPhonePermission(capability: PhoneCapability) {
        val readings = engine.hostReadings().permissions
        val permissions = requestablePermissions(capability, readings)
        when {
            permissions.size == 1 -> {
                PhonePermissionAsks.started(capability, permissions)
                // Rebuild the facts first so the row is already disarmed/requesting
                // when the dialog appears over it.
                permissionRevision++
                phonePermission.launch(permissions.single())
            }

            permissions.size > 1 -> {
                PhonePermissionAsks.started(capability, permissions)
                permissionRevision++
                phonePermissions.launch(permissions.toTypedArray())
            }

            else -> {
                Log.i(TAG, "PHONE_PERMISSION_NONE capability=${capability.id} -> settings")
                openPhonePermissionSettings(capability)
            }
        }
    }

    /**
     * 去系统设置: open the page that holds this capability's switch.
     *
     * The capability decides which page, because they are not the same one: a denied
     * runtime permission lives on this app's own details page, while 专注模式's DND
     * access is a special-access page that app details does not contain.
     */
    private fun openPhonePermissionSettings(capability: PhoneCapability) {
        val intent = phonePermissionSettingsIntent(this, capability)
        if (intent == null) {
            Log.w(TAG, "PHONE_PERMISSION_NO_SETTINGS capability=${capability.id}")
            return
        }
        Log.i(TAG, "PHONE_PERMISSION_SETTINGS capability=${capability.id} action=${intent.action}")
        startActivity(intent)
    }

    private companion object {
        const val TAG = "PDSHMain"
    }
}

/**
 * One confirmation the `ubuntu` gate is waiting on.
 *
 * [answer] is completed exactly once — by the dialog, by [MainActivity.onDestroy]
 * with `Unavailable`, or never (then the gate's own timeout answers). The
 * request carries no `argv`: the dialog describes the environment and the use,
 * and the command is named by digest in the audit.
 */
private class PendingUbuntuApproval(
    val request: UbuntuApprovalRequest,
    val answer: CompletableDeferred<UbuntuApprovalDecision>,
)

/**
 * The `ubuntu` confirmation.
 *
 * Shape follows `PhonePermissionsPage`'s `ConfirmationDialog`: a title, the
 * purpose, and 取消 / 继续. Here 继续 is 允许 because the decision grants the
 * whole session, not one system prompt. It says what this build can and cannot
 * promise: the seal is a real `chmod 000` but not a hard boundary, the session
 * scope is named *and printed as a digest*, and a refusal is stated to start
 * nothing.
 *
 * The session digest is not decoration. This dialog is the user's only view of
 * who is asking, so "the same 8 hex as last time" and "a different 8 hex" have
 * to be tellable apart at a glance: if any future path lets a model spend
 * another session's approval, the user can still see that the question changed.
 *
 * @param pending the request on screen, or `null` when none is waiting.
 * @param onAnswer `true` for 允许, `false` for 拒绝 / dismissal.
 */
@Composable
private fun UbuntuApprovalDialog(
    pending: PendingUbuntuApproval?,
    onAnswer: (Boolean) -> Unit,
) {
    if (pending == null) return
    val request = pending.request
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("允许使用本地 Ubuntu 环境？") },
        text = {
            Column {
                Text("环境：${request.env}（backend=${request.backend}）")
                request.label?.let { Text("用途：$it") }
                Text("会话：${UbuntuApprovalGate.summary(request.sessionId)}")
                Text("")
                Text(
                    "它能读到的只有工作区；job 期间三个内部根真的被 chmod 000 密封，" +
                        "同 UID 的代码仍可 chmod 回来——但那会触发绊线（internalAccessObserved），" +
                        "并在 job 结束时告诉你。",
                )
                Text("")
                Text("一次批准覆盖上面这个 session 内后续的 ubuntu run；停止本地引擎或 session 结束后失效。")
                Text("摘要变了，就说明是另一个 session 在请求，不是刚才那个。")
                Text("")
                Text("拒绝则本次 run 失败（cause=approval_denied），不会启动任何命令，也不会截断任何文件。")
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text("允许", textAlign = TextAlign.End, modifier = Modifier.padding(horizontal = 4.dp))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) {
                Text("拒绝", textAlign = TextAlign.End, modifier = Modifier.padding(horizontal = 4.dp))
            }
        },
    )
}
