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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.phonedsh.dsh.background.NotificationCapability
import com.phonedsh.ui.page.PhoneCapability

class MainActivity : ComponentActivity() {
    private val engine: ProbeEngineViewModel by viewModels()

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
        // The phone-permissions page's ask log, read before the first facts build so
        // the very first frame already knows which rows were already answered.
        PhonePermissionAsks.install(applicationContext)
        // Development only: a key dropped into the app's own files dir is imported and
        // the plaintext is deleted. Gated on the *install's* debuggable flag rather than
        // BuildConfig (this module compiles with `buildConfig = false`); a release APK
        // therefore has no such path at all.
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
        // targetSdk 35 enforces edge-to-edge; consume the insets in ProbeScreen.
        enableEdgeToEdge()
        setContent {
            val state by engine.state.collectAsState()
            val hostState by engine.hostState.collectAsState()
            val handshake by engine.handshake.collectAsState()
            val channel by engine.channel.collectAsState()
            val turn by engine.turn.collectAsState()
            val recoveryNotice by ProbeRecovery.notice.collectAsState()
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
                onStop = engine::stop,
                onRunTurn = ::runTurnOrAskForNotifications,
                onPhonePermissionRequest = ::requestPhonePermission,
                onPhonePermissionSettings = ::openPhonePermissionSettings,
            )
        }
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
