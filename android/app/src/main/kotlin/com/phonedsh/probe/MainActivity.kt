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

class MainActivity : ComponentActivity() {
    private val engine: ProbeEngineViewModel by viewModels()

    /**
     * Bumped when the notification answer changes what the background facts may
     * say, so `HostShell` rebuilds them instead of keeping the snapshot it took
     * before the dialog was answered.
     */
    private var backgroundRevision by mutableIntStateOf(0)

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
        backgroundRevision++
        if (turnAfterPermission) {
            turnAfterPermission = false
            // Denied still runs the turn: it becomes a foreground-only turn, which
            // is the honest degradation rather than a refusal to work.
            engine.runTurn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The key store must exist before the launch config, because the config
        // asks it for the key's *state* while building the Worker env, and before
        // the view model constructs the gateway that asks it for the credential.
        ApiKeyStore.install(applicationContext)
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
                onStart = engine::start,
                onStop = engine::stop,
                onRunTurn = ::runTurnOrAskForNotifications,
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

    private companion object {
        const val TAG = "PDSHMain"
    }
}
