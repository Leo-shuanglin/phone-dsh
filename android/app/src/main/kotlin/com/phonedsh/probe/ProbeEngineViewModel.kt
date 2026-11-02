// The composition root: the one place :app names both the engine and the host.
// SPDX-License-Identifier: MIT
// It constructs DefaultPhoneDshHost, hands its channelHandler to createDemoEngine
// and forwards start/stop. The screen stays a pure function of the engine's flows.
package com.phonedsh.probe

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.agentcore.JsonArray
import com.phonedsh.agentcore.JsonBool
import com.phonedsh.agentcore.JsonNumber
import com.phonedsh.agentcore.JsonString
import com.phonedsh.agentcore.JsonValue
import com.phonedsh.agentcore.toJsonString
import com.phonedsh.controller.Admission
import com.phonedsh.controller.ControllerBudgets
import com.phonedsh.controller.ControllerStateMachine
import com.phonedsh.dsh.exec.FileRequestLedger
import com.phonedsh.dsh.exec.ExecEvent
import com.phonedsh.dsh.exec.ExecRequest
import com.phonedsh.dsh.exec.ExecTier
import com.phonedsh.dsh.exec.StdinSpec
import com.phonedsh.dsh.host.DefaultPhoneDshHost
import com.phonedsh.dsh.host.HostApiException
import com.phonedsh.dsh.host.HostBudgetTier
import com.phonedsh.dsh.host.HostExec
import com.phonedsh.dsh.host.ShellAcceleratorTool
import com.phonedsh.dsh.host.HostLog
import com.phonedsh.dsh.host.HostStateStore
import com.phonedsh.dsh.host.ModelRoute
import com.phonedsh.dsh.host.ModelRouteFact
import com.phonedsh.dsh.host.asHostWal
import com.phonedsh.domain.DomainStore
import com.phonedsh.domain.DomainStoreCore
import com.phonedsh.domain.QuotaPolicy
import com.phonedsh.domain.device.AndroidDeviceCapabilities
import com.phonedsh.domain.device.AndroidHostPermissions
import com.phonedsh.domain.session.ArchivedCommand
import com.phonedsh.domain.session.ArchivedTurn
import com.phonedsh.domain.session.ArchivedWrite
import com.phonedsh.domain.session.SessionArchive
import com.phonedsh.domain.sqlite.SqliteConnectionFactory
import com.phonedsh.dsh.journal.JournalException
import com.phonedsh.dsh.journal.JournalPaths
import com.phonedsh.dsh.journal.JournalStore
import com.phonedsh.dsh.turn.DshTurnClient
import com.phonedsh.dsh.turn.DshTurnException
import com.phonedsh.engine.AndroidEngineLogger
import com.phonedsh.engine.ApkWorkerAssetSource
import com.phonedsh.engine.BootOverlaySource
import com.phonedsh.engine.ChannelStatus
import com.phonedsh.engine.DemoEngine
import com.phonedsh.engine.EngineLogger
import com.phonedsh.engine.EngineState
import com.phonedsh.engine.HandshakeSummary
import com.phonedsh.engine.MaterializedBootOverlaySource
import com.phonedsh.engine.WorkerAssets
import com.phonedsh.engine.WebViewKernelReport
import com.phonedsh.engine.createDemoEngine
import com.phonedsh.engine.info
import com.phonedsh.gateway.AllowListEgressPolicy
import com.phonedsh.gateway.CredentialScope
import com.phonedsh.gateway.GatewayCredential
import com.phonedsh.gateway.GatewayCredentialProvider
import com.phonedsh.gateway.GatewayHttpEgress
import com.phonedsh.gateway.GatewaySession
import com.phonedsh.gateway.HttpUrlConnectionGatewayTransport
import com.phonedsh.hostevents.HostEvent
import com.phonedsh.hostevents.HostEventKind
import com.phonedsh.log.MutationBody
import com.phonedsh.log.VfsOp
import com.phonedsh.log.WalPaths
import com.phonedsh.log.WorkspaceLog
import com.phonedsh.ui.projection.UiStateNode
import com.phonedsh.ui.nav.confirms
import com.phonedsh.ui.state.ChatAttachment
import com.phonedsh.ui.state.ChatScreenState
import com.phonedsh.loopback.ChannelCloseCause
import com.phonedsh.loopback.LoopbackChannelHandler
import com.phonedsh.loopback.LoopbackConnection
import com.phonedsh.loopback.LoopbackHttpEgress
import com.phonedsh.loopback.LoopbackModelProxy
import com.phonedsh.platform.EngineHostState
import com.phonedsh.platform.NotificationAction
import com.phonedsh.platform.Notifier
import com.phonedsh.platform.NotifierAvailability
import com.phonedsh.platform.android.AndroidClock
import com.phonedsh.platform.android.AndroidDurability
import com.phonedsh.platform.android.AndroidLifecycleSignals
import com.phonedsh.shell.AcceleratorMount
import com.phonedsh.shell.AcceleratorLimits
import com.phonedsh.ui.page.ShellLimits
import com.phonedsh.ui.page.ShellRun
import com.phonedsh.ui.page.ShellSnapshot
import com.phonedsh.ui.nav.PdshNavFacts
import com.phonedsh.ui.page.BusyEnterMode
import com.phonedsh.ui.page.FactState
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Permission preset catalog and write capability measured from the DSH host. */
internal data class PermissionDefaultsHostState(
    val currentModeId: String? = null,
    val availableModeIds: Set<String>? = null,
    val writable: Boolean = false,
    val busy: Boolean = false,
    val failure: String? = null,
) {
    val currentTier: com.phonedsh.ui.page.PermissionTier? get() = tierForPermissionMode(currentModeId)
    val availableTiers: Set<com.phonedsh.ui.page.PermissionTier>? get() = availableModeIds?.mapNotNull(::tierForPermissionMode)?.toSet()
}

private fun tierForPermissionMode(id: String?): com.phonedsh.ui.page.PermissionTier? = when (id) {
    "read-only", "phone-read-only" -> com.phonedsh.ui.page.PermissionTier.VIEW_ONLY
    "workspace-write", "phone" -> com.phonedsh.ui.page.PermissionTier.WORKSPACE_WRITE
    "danger-full-access", "bypassPermissions" -> com.phonedsh.ui.page.PermissionTier.FULL_ACCESS
    else -> null
}

/**
 * Owns the one engine **and** the one host across configuration changes.
 *
 * Before K5 this view model constructed the engine with a do-nothing channel
 * handler, so the page's HELLO was never answered and the screen showed
 * `hostCaps=[]`. It now builds the real [DefaultPhoneDshHost] and passes
 * `host.channelHandler` to the engine: the host HELLO, its caps and its
 * watermark are produced by `:dsh` from the recovered workspace log and the real
 * `phone` accelerator, not by this file.
 *
 * [start] is the host's asynchronous `start()`: it returns immediately, runs
 * recovery on the host's own scope and starts the engine only after the HELLO
 * is final. Nothing here blocks the UI thread.
 *
 * [runTurn] is the App's own `/api` path: the real [DshTurnClient] sends
 * `session/create`, `session/follow` and `session/prompt` over the channel the
 * Worker opened. The model behind the Worker is scripted by the acceptance
 * runner; this class makes no claim about inference.
 */
class ProbeEngineViewModel(application: Application) : AndroidViewModel(application) {
    private val logger: EngineLogger = AndroidEngineLogger()

    /** The launch values read before this view model was constructed. */
    private val launchConfig: ProbeLaunchConfig = ProbeLaunchConfig.active()

    /**
     * budgets.md §4's device tier for this boot, selected from the physical
     * memory ceiling.
     *
     * This is the one place the App reads `ActivityManager.MemoryInfo.totalMem`
     * (`HostBudgetTier.forDevice`); §4 says the tier is selected **once per
     * boot**, so the value is a `val` and a later memory-pressure reading cannot
     * re-tier the host. The same object feeds the two surfaces the limit needs:
     * the Worker environment (so its write surfaces refuse with `EFBIG` before
     * publishing) and [DefaultPhoneDshHost] (so the host VFS sink answers
     * `VFS_REFUSED` as the backstop).
     *
     * Before this existed the App never measured anything: a 2 GiB device kept
     * the Worker's 8 GiB default of 128 MiB and accepted a file its §4 row caps
     * at 16 MiB.
     */
    private val deviceBudgets: ControllerBudgets = HostBudgetTier.forDevice(application)

    /**
     * The host credential provider for this launch, or `null` when there is no
     * model endpoint or no installed key store.
     *
     * One object serves both the `/v1/http` gateway and the model edge: a second
     * provider would mean a second `ModelEndpoint` and a second writer of the
     * same invariant (`gateway-security.md` §2 — the Worker never holds the key).
     */
    private val modelCredential: GatewayCredentialProvider? = launchConfig.modelCredentialProvider()

    /**
     * The model-protocol edge, or `null` when this launch cannot host one.
     *
     * It is built from the **real** upstream base (`launchConfig.deepseekBaseUrl`)
     * and the key-store provider, and handed to the engine; the engine binds it
     * to the loopback origin and rewrites the Worker's `DEEPSEEK_BASE_URL` to
     * that origin. When this is non-null the launch has declared **real model
     * traffic**: the Worker's model requests are answered only by the host edge,
     * and a stored key that is absent or unreadable is a fail-closed
     * `502 credential_unavailable` from that edge — never a silent fallback to an
     * anonymous or scripted upstream. "Credential broken" must look like a
     * broken credential, not like a model that answers strangely.
     *
     * `null` has three causes, and all three keep the pre-M2 path where the
     * Worker's env is passed through unchanged:
     *   * no base URL in the launch;
     *   * no installed key store ([ProbeLaunchConfig.modelCredentialProvider]);
     *   * the launch itself asked for no edge (`pdsh.model.proxy=false`), which
     *     is how a scripted upstream run keeps a real device key away from the
     *     test fixture.
     *
     * The base URL is not re-parsed here: [ProbeLaunchConfig.modelCredentialProvider]
     * already returned non-null only for a base URL that `ModelEndpoint.parse`
     * accepted, so this string is exactly the one the credential scope uses.
     */
    private val modelProxy: LoopbackModelProxy? = run {
        val base = launchConfig.deepseekBaseUrl
        val provider = modelCredential
        // Three ways to have no edge: no base, no credential, or a launch that asked for
        // none. The third one is what keeps a scripted upstream from ever receiving a real
        // key — see ProbeLaunchConfig.EXTRA_MODEL_PROXY.
        if (base == null || provider == null || !launchConfig.modelProxy) {
            if (!launchConfig.modelProxy) {
                // The one line a scripted run needs: no edge was mounted, so the
                // Worker env keeps its base URL and no host credential can reach
                // that upstream. No key and no real base appears here.
                logger.info("model edge disabled by launch (pdsh.model.proxy=false); the Worker env keeps its base URL")
            }
            null
        } else {
            LoopbackModelProxy(baseUrl = base, credentialProvider = provider)
        }
    }

    /**
     * The one upstream session, or `null` when the launch named no egress grant.
     *
     * The transport is the production `HttpURLConnection` hop. The credential comes
     * from the launch's model endpoint when it names one — the same object the model
     * proxy uses — and falls back to anonymous otherwise. Anonymous is not a silent
     * downgrade: [ProbeLaunchConfig.modelCredentialProvider] only returns non-null by
     * the time this runs, and its own resolve is fail-closed for the model origin.
     */
    private val gateway: GatewaySession? = launchConfig.egressGrant()?.let { grant ->
        GatewaySession(
            transport = HttpUrlConnectionGatewayTransport(),
            credentialProvider = modelCredential ?: AnonymousCredentialProvider,
            egress = AllowListEgressPolicy(listOf(grant)),
        )
    }

    /** The `/v1/http` policy the engine serves; `null` keeps the route at 503. */
    private val httpEgress: LoopbackHttpEgress? = gateway?.let { GatewayHttpEgress(it) }

    /**
     * What this launch can honestly say about model traffic (P27).
     *
     * The Worker env publishes the key's *state* and a non-secret placeholder
     * credential even when the launch named no endpoint (P25), which is correct:
     * those are facts about the device. It did **not** publish whether a model
     * route exists, and that absence was not neutral — with no
     * `DEEPSEEK_BASE_URL` the Worker's pinned `llm-deepseek` resolver falls back
     * to its public base (`packages/llm/llm-deepseek/src/config.ts:300`), so a
     * launch that mounted no edge sent the placeholder to
     * `https://api.deepseek.com/anthropic` and produced a real 401 (measured
     * 2026-11-01). This fact is published through
     * [ModelRouteFact.workerEnvironment] and the Worker refuses a model request
     * on `none` (`plugins/worker-entry/src/model-route.ts`), so the
     * misconfiguration produces no external traffic and names the missing input.
     *
     * It is computed once and read by the env, the start log and the sentence a
     * failed turn carries. One fact, one answer.
     */
    private val modelRoute: ModelRouteFact = ModelRouteFact.forLaunch(
        baseUrl = launchConfig.deepseekBaseUrl,
        proxyRequested = launchConfig.modelProxy,
        credentialAvailable = modelCredential != null,
    )

    /**
     * The `pageParams` this launch hands the engine (the engine's own parameter
     * is `pageParams`; this property is the value it is given).
     *
     * The route members are added **after** `ProbeLaunchConfig.workerEnvironment()`
     * (which P25 owns) so this lane does not reopen that method's contract: the
     * key members stay exactly where P25 put them, and the route is one more
     * fact of the same env object. The engine re-parses this JSON and adds
     * `PDSH_DSH_LAUNCH_TOKEN` (`WorkerEnvironment.pageParam`).
     *
     * No value here is a credential or an endpoint: `host-edge` / `direct` /
     * `none` plus a missing-input code. Nothing is logged.
     */
    private val workerPageParams: Map<String, String> = run {
        val hostEnv = launchConfig.workerEnvironment(
            modelProxyMounted = modelProxy != null,
            // The selected tier's `single_file_limit`, published where the Worker
            // reads it (channel.md §4.1 has no HELLO member for it). This is what
            // makes an over-limit write an `EFBIG` from the write surface instead
            // of a WAL record that only recovery can refuse.
            singleFileLimitBytes = deviceBudgets.singleFileBytes,
        )
        val env = if (hostEnv == null) JSONObject() else JSONObject(hostEnv)
        for ((name, value) in modelRoute.workerEnvironment()) env.put(name, value)
        mapOf("env" to env.toString())
    }

    /** The per-start token source; also authenticates the App's egress probe. */
    private val launchToken = SecureLaunchToken()

    // ---- construction order: engine first, then the host --------------------
    // The host needs the engine, and the engine needs the host's frame consumer.
    // [HostChannelSeam] breaks that cycle; it is attached in `init` before
    // `host.start()`, so no frame can ever reach an unattached seam.
    private val channelSeam = HostChannelSeam()

    private val engine: DemoEngine = createDemoEngine(
        context = application,
        channelHandler = channelSeam,
        logger = logger,
        // `workerAssets` is named on purpose: the factory's `logger` has a default
        // value before it, so a positional call would not compile.
        workerAssets = WorkerAssets(
            source = ApkWorkerAssetSource(application),
            filesDir = application.filesDir,
            logger = logger,
        ),
        // One fresh 32-byte secret per start: the loopback gate/cookie token and
        // the worker's PDSH_DSH_LAUNCH_TOKEN. Never logged or persisted; the
        // resident phone surface refuses to provide `connection` without it.
        launchToken = launchToken,
        // The base URL travels through the same `env` object as the launch
        // token; it is not a page query parameter of its own and is not logged.
        // With a mounted edge the env omits `DEEPSEEK_BASE_URL` entirely: the
        // engine fills in the loopback origin once the port is known, so the real
        // upstream base never enters the page parameter. The model route travels
        // in the same object (see [workerPageParams]).
        pageParams = workerPageParams,
        // The Worker's only outbound path; null when no exact grant was supplied.
        httpEgress = httpEgress,
        // M2: the model edge. It holds the real upstream base and the key-store
        // provider; the engine mounts it on the loopback origin and points the
        // Worker's DEEPSEEK_BASE_URL there. Null keeps the scripted-upstream path.
        modelProxy = modelProxy,
        // P22: the channel-reopen lane reloads the page only while D14's admission
        // is open. `controller` is initialized after this engine on purpose (the
        // host takes the engine), and the lambda is read at reload time, never
        // during construction.
        writesAllowed = { controller.admission == Admission.OPEN },
        // P28 / wal.md §8: the Worker is seeded from the recovered materialized
        // tree. The delegate reads the log when `pack()` runs — always after
        // `host.start()` finished recovery and tail replay — because the engine is
        // built before the WAL here, exactly like `writesAllowed` above reads
        // `controller` later. `null` (the diagnostic launch) reproduces the
        // pre-P28 shape: no `?overlay=`, an empty Worker view.
        bootOverlaySource = if (launchConfig.bootOverlay) {
            BootOverlaySource { bootOverlayProducer.pack() }
        } else {
            null
        },
    )

    private val clock = AndroidClock()
    private val durability = AndroidDurability()
    private val signals = AndroidLifecycleSignals(application)

    /**
     * The `wal.md` §8 boot overlay producer (P28).
     *
     * `wal.md` §8 says the overlay is packed from the materialized tree "after
     * recovery, reconcile and tail replay" and is rebuilt for each boot; the engine
     * therefore calls [MaterializedBootOverlaySource.pack] before **every** page
     * load. This property is `by lazy` on purpose: it reads [walPaths] and
     * [workspaceLog], which are constructed after the engine.
     *
     * The cap is the tier's `vfsLogicalBytes` (budgets.md §4). That is the memory
     * this tree becomes in the Worker's `MemoryVfs`, so a tree above it cannot be
     * booted by this device at all; the producer then refuses loudly instead of
     * truncating (see BootOverlay.kt).
     */
    private val bootOverlayProducer: MaterializedBootOverlaySource by lazy {
        MaterializedBootOverlaySource(
            materializedDirectory = walPaths.materializedDirectory,
            outputDirectory = walPaths.root.resolve(BOOT_OVERLAY_DIRECTORY),
            maxArchiveBytes = deviceBudgets.vfsLogicalBytes,
            logger = logger,
            // `materializedUptoMseq` is the watermark the snapshot must be taken
            // at: a pack that straddles a materialization is discarded and retried,
            // so the Worker always mounts one consistent mseq.
            watermark = { workspaceLog.materializedUptoMseq },
        )
    }

    // wal.md §1 places the workspace under `Library/xl/`; on Android the app's
    // private filesDir is the equivalent root. This one path is the root every
    // durable store is built over — the WAL's `wal/`, the journal's `journal/`
    // and the exec dedup table's `exec/` — so no store carries an absolute path
    // of its own and none of them can drift to a second directory.
    private val workspaceRoot: Path = File(application.filesDir, WORKSPACE_DIRECTORY).toPath()

    /**
     * The real seal behind the `ubuntu` lane: `BuildSeal` + `BuildSealTripwire`.
     *
     * Design §3 layers 3 and 4 are "the core countermeasure", and until this
     * property existed the production path ran `MockUbuntuSeal`, which opens and
     * closes nothing — so `chmod 000` never happened for any job the Agent ran.
     * The adapter lives in `:platform-android` (`BuildSealUbuntu`); `:dsh` only
     * sees the `UbuntuSeal` seam, which is why the injection point is here.
     *
     * The sealed roots are `materialized/home/{sessions,storages,tool-journal}`
     * under this same [workspaceRoot], i.e. the tree the WAL materializes and the
     * accelerators are mounted on — not a second copy.
     */
    private val ubuntuSeal: UbuntuWorkspaceSeal = UbuntuWorkspaceSeal.forWorkspace(
        xlRoot = workspaceRoot,
        log = { line -> LogcatHostLog.info(line) },
    )

    /**
     * The boot repair — **must run before `host.start()` opens the workspace**.
     *
     * A process killed inside a job's window leaves every root under
     * `materialized/home` at
     * `chmod 000` *and* leaves the seal marker, so a boot that skipped this would
     * fail its first reconcile of that tree and could not resume its own stores.
     * `BuildSeal.recoverIfNeeded()` (reached through `BuildSealUbuntu.repair()`)
     * is idempotent and reads one file that normally does not exist, so the cost
     * on a healthy boot is one failed `exists` check — the same synchronous,
     * small, composition-time read this class already makes for
     * `hostStateStore.load()` two properties below.
     *
     * The outcome is logged, never swallowed: a `false` means the marker is still
     * there (the next boot retries) and the workspace is not healthy.
     */
    private val ubuntuSealRepaired: Boolean = runBlocking {
        ubuntuSeal.repair()
    }.also { repaired ->
        if (repaired) {
            LogcatHostLog.info("ubuntu seal: boot repair found nothing to undo")
        } else {
            LogcatHostLog.warn(
                "ubuntu seal: boot repair FAILED; the seal marker is still on disk " +
                    "and the internal roots may still be 000",
            )
        }
    }

    /**
     * `host-state.json`, the file `PERSIST_HOST_STATE` writes (P15).
     *
     * Until this existed the boot below passed a literal `persistedState = null`
     * and every start was `COLD_START`: budgets.md §3's boot mapping (persisted
     * `Parked` -> `Parked`, persisted `Backoff` -> `Backoff`, persisted
     * `HandingOff` -> `Failed`) had no file to read, so a host killed while
     * `Parked` came back cold and `PARKED_RESUME` was unreachable.
     */
    private val hostStateStore = HostStateStore(
        file = workspaceRoot.resolve(HostStateStore.FILE_NAME),
        durability = durability,
        clock = clock,
        logger = LogcatHostLog,
    )

    /**
     * The state this boot restores, or `null` when there is none to trust.
     *
     * Read once, before the machine is built: the D14 boot mapping is a pure
     * function of the persisted state, and a second read later could see a
     * record this process itself wrote.
     */
    private val persistedHostState = hostStateStore.load()

    // The boot mapping of budgets.md §3: a persisted `Parked` host resumes as
    // `Parked` (its resume waits for an active scene), a persisted `Running`,
    // `Draining` or `Recovering` host boots back into `Recovering`, a persisted
    // `HandingOff` is `Failed`, and no record at all is the empty workspace.
    // `workspaceHasState` stays false: whether the WAL holds history is the
    // WAL's answer, not this file's.
    private val controller = ControllerStateMachine.boot(persistedState = persistedHostState?.state)

    // One path object for every consumer of the workspace layout: the WAL, the
    // journal and the exec accelerators' mounts all derive from it, so no store
    // can drift to a second directory.
    private val walPaths = WalPaths(workspaceRoot)

    // The directory is relative to filesDir (no absolute path is committed or
    // logged) and `recover()` is run by the host's asynchronous boot, never here.
    private val workspaceLog = WorkspaceLog(
        paths = walPaths,
        durability = durability,
        clock = clock,
        // wal.md section 10 step 2 requires a torn-tail truncation to be logged.
        // The library default drops every event, so the composition root supplies
        // the sink that both logs it and raises the banner.
        sink = ProbeRecovery.walSink,
    )

    /** The one WAL seam, shared by the HELLO and the exec bridge. */
    private val hostWal = workspaceLog.asHostWal()

    /**
     * The journal lane's directory layout, shared by the store and the session
     * read, so a row's file is named by the same object that wrote it.
     */
    private val journalPaths = JournalPaths(workspaceRoot)

    /**
     * The 本 session 产物 fact: which workspace files this session's turns wrote.
     *
     * One instance per process, over the same materialized tree the files page and
     * the preview read (`WalPaths.materializedWorkspace`). It measures the tree
     * around each chat turn rather than parsing tool arguments or scanning the
     * folder — see [HostSessionArtifacts] for why neither of those can answer the
     * page's question. Nothing is persisted: after a cold start the host has
     * measured no turn, so it claims nothing and the page lists every file as 未归属.
     */
    private val sessionArtifacts = HostSessionArtifacts(walPaths.materializedWorkspace)

    /** The artifact fact the shell collects; one value per turn measurement. */
    internal val artifactFacts: kotlinx.coroutines.flow.StateFlow<com.phonedsh.ui.page.SessionArtifactsFacts> =
        sessionArtifacts.facts

    /**
     * The journal lane behind `JOURNAL` (`0x30`).
     *
     * The store is recovered inside `host.start()`, before the HELLO is final:
     * only a store that recovered leaves the sink open, and only an open sink
     * acks a record. Constructing it here does no I/O. Without it the host logs
     * `journal: off` and the Worker's level-1 durability wait expires after
     * 30 s, which reopens the connection in the middle of a turn.
     */
    private val journalStore = JournalStore(
        paths = journalPaths,
        durability = durability,
        logger = LogcatHostLog,
    )

    /**
     * exec.md §2's durable dedup table (`exec/` under the workspace root).
     *
     * One file per `idem_key`, written through the same `Durability` SPI as the
     * WAL and the journal. A repeated key replays the recorded events after a
     * process restart instead of running the accelerator twice.
     *
     * Constructing it creates `exec/` and scans the retained entries: a
     * one-time, small IO at composition time, bounded by the ledger's
     * `DEFAULT_MAX_ENTRIES`. If a future workload makes that scan visible at
     * startup, the fix is a lazy-load seam in the ledger, not a larger default.
     */
    private val execLedger = FileRequestLedger(
        workspaceRoot = workspaceRoot,
        durability = durability,
        clock = clock,
    )

    /** The one registered read-only accelerator bridge shared by Worker and shell-page runs. */
    private val execBridge = HostExec.defaultTools(
        clock = clock,
        controller = controller,
        wal = hostWal,
        logger = LogcatHostLog,
        ledger = execLedger,
        shellMounts = listOf(
            AcceleratorMount(AcceleratorMount.WORKSPACE, walPaths.materializedWorkspace.toString()),
            AcceleratorMount(AcceleratorMount.HOME, walPaths.materializedHome.toString()),
        ),
        seal = ubuntuSeal,
    )

    /**
     * The local DomainStore (`PRD.md:169`), constructed here and nowhere else.
     *
     * `:domain` owns the contract and the two backends; this root is the one
     * place allowed to pick one, which is why `:dsh` has no edge to `:domain`.
     * Opening is lazy — [DomainStoreCore] touches the file on first use — so
     * constructing it at composition time costs nothing.
     *
     * It is deliberately **not** a third writer of the session record. The
     * authoritative event log stays the WAL and the journal (fields above); this
     * store holds only the projection cache described in [sessionArchive].
     */
    private val domainStore: DomainStore = DomainStoreCore(
        connections = SqliteConnectionFactory(application, SessionArchive.DATABASE_NAME),
        quotas = QuotaPolicy.perDomain(
            mapOf(SessionArchive.DOMAIN to SessionArchive.QUOTA_BYTES),
        ),
        clock = clock,
    )

    /**
     * The transcript cache: the Android half of the iOS baseline's
     * `transcript-snapshots/<sessionID>.json` plus its
     * `currentSessionDefaultsKey`.
     *
     * iOS keeps a JSON file per session so a cold start can paint the last
     * screen before the host is up (`ChatModel.swift:2512-2599`:
     * `persistTranscriptSnapshot` / `restoreCachedTranscriptIfAvailable`) and
     * remembers the session it last opened in `UserDefaults`. Android had
     * neither: [mutableTurn] started at [AppTurnState.Idle] on every launch and
     * [mutableSessions] learned the current session only from a turn this
     * process ran, so a kill erased both. This is the consumer the `:domain`
     * store was missing — see [sessionArchive]'s own file header for why the
     * payload lives in the domain store and not in a third JSON file.
     */
    private val sessionArchive = SessionArchive(domainStore)

    /**
     * The user preferences (`UserDefaults` parity), constructed here and nowhere
     * else.
     *
     * One `SharedPreferences` file behind one store — the settings index's five
     * pickers, the chat-appearance page's pickers and steppers, the 后台运行
     * switch and the 繁忙时的发送行为 menu all read and write this object, so two
     * pages cannot disagree about a value. It is constructed at composition time
     * and reads the file once; a write is published through [preferenceState] and
     * the shell rebuilds the facts on the next frame.
     */
    private val preferenceStore = PreferenceStore(SharedPreferenceFile(application))

    /** The resolved preferences; a write publishes a new value. */
    internal val preferenceState: StateFlow<Preferences> = preferenceStore.state
    private val mutablePermissionDefaults = MutableStateFlow(PermissionDefaultsHostState())
    internal val permissionDefaults: StateFlow<PermissionDefaultsHostState> = mutablePermissionDefaults.asStateFlow()

    private val host: DefaultPhoneDshHost = DefaultPhoneDshHost(
        engine = engine,
        controller = controller,
        clock = clock,
        signals = signals,
        // :dsh ships no production Notifier and :app has no notification channel
        // or POST_NOTIFICATIONS flow, so the honest value is DISABLED: on a
        // crash-recovery boot the host logs "not user-visible" instead of
        // pretending a notification reached the user.
        notifier = DisabledNotifier,
        // `wal/1` is advertised only when this log's recovery succeeds inside
        // `host.start()`; the HELLO's caps and watermark are built from that
        // outcome, never from a value written in this file.
        wal = hostWal,
        // The journal store is recovered in `host.start()` too, so `0x30` is
        // dispatched and acknowledged (durably) for the whole boot instead of
        // being logged and ignored.
        journal = journalStore,
        // `exec/1` and every `accelerator:*` entry come from the bridge's
        // capability set, never a capability string in this file. The durable
        // ledger and the mounts are injected here: this is the composition root
        // that owns the workspace root and the durability SPI.
        //
        // The mounts point at the WAL's *materialized* copies, exactly the tree
        // the accelerators are promised (exec.md §2.1): `rg`/`jaq` must never
        // read the journal or an unmaterialized mutation. `WalPaths` only joins
        // names; `WorkspaceLog.recover()` creates the directories inside
        // `host.start()`, before admission opens, so a dispatched EXEC_REQ always
        // finds them. With no workspace the list would be empty and :shell would
        // advertise no accelerator at all.
        exec = execBridge,
        logger = LogcatHostLog,
        // The one upstream session; no longer a null placeholder.
        gateway = gateway,
        // The Worker's `XL_DSH_READY` announcement arrives on CONSOLE.
        onWorkerConsole = ::onWorkerConsole,
        // P15: `PERSIST_HOST_STATE` and the `Backoff` retry schedule have an
        // owner. The store is the same file this boot read, so a `Parked` host
        // survives a kill and a `Backoff` host restores the rest of its window.
        stateStore = hostStateStore,
        // budgets.md §4: the one measured row for this boot. The host's VFS sink
        // answers a mutation that would grow a file past this row's
        // `single_file_limit` with `VFS_REFUSED` (channel.md §4.8/§8); the same
        // value went into the Worker env above, so the two surfaces cannot
        // disagree.
        deviceBudgets = deviceBudgets,
        // gateway/loopback/lease/sessionEpoch/scope keep their documented
        // defaults: :engine binds the origin, the lease is LocalOnly, and no
        // persisted session counter exists (0 = none).
    )

    val state: StateFlow<EngineState> = engine.state

    /**
     * The D14 host state, which is what actually decides whether this app is
     * usable right now: admission is open only in `Running`.
     *
     * The screen needs both facts. The engine can be `Running` while the host is
     * `Draining`/`Parked`/`Recovering`, and rendering that as 「运行中 / 已连接」
     * is the lie P10 measured (12:01:19, API 35: every write refused while the
     * panel said 运行中).
     */
    val hostState: StateFlow<com.phonedsh.controller.HostState> = host.state
    val channel: StateFlow<ChannelStatus> = engine.channel
    val handshake: StateFlow<HandshakeSummary?> = engine.handshake

    /**
     * The installed WebView provider, read once at construction.
     *
     * `null` when the framework could not answer; the runtime page then renders
     * 未知 instead of a package name the host never saw.
     */
    private val webViewPackage: android.content.pm.PackageInfo? =
        runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()

    /**
     * Whether the app is exempt from battery optimisation, read once.
     *
     * The settings page owns the wording; this file only reports the platform
     * answer, and 未知 when the service refused to answer.
     */
    private val batteryExemption: FactState = runCatching {
        val app = getApplication<Application>()
        val manager = app.getSystemService(PowerManager::class.java)
        when (manager?.isIgnoringBatteryOptimizations(app.packageName)) {
            true -> FactState.YES
            false -> FactState.NO
            null -> FactState.UNKNOWN
        }
    }.getOrDefault(FactState.UNKNOWN)

    /** Monotonic start of the current Running spell; `null` when not running. */
    @Volatile
    private var runningSinceMillis: Long? = null

    /**
     * What the merged APK declares for the background lane, read once.
     *
     * The declaration cannot change without a reinstall, so it is cached instead
     * of paying four `PackageManager` reads on every facts build. The notification
     * capability is *not* cached: the user can revoke it between two reads.
     */
    private val backgroundDeclaration: BackgroundDeclaration by lazy {
        HostBackground.declaration(getApplication())
    }

    /**
     * The engine's one view, or `null`.
     *
     * UI thread only: the Web surface attaches this exact instance and never
     * creates a second one (the single-WebView invariant).
     */
    fun currentWebView(): View? = engine.view()

    /**
     * The engine's last WebView kernel measurement (provider, real Chromium
     * version, runtime features), or `null` before the first page finished.
     *
     * Read synchronously like [currentWebView]: both are host facts of the one
     * engine, not UI state of their own. `null` means "not measured".
     */
    fun kernelReport(): WebViewKernelReport? = engine.kernelReport()

    /** True when the launch asked the Web surface to be the engine-ready face. */
    fun webFaceOnLaunch(): Boolean = launchConfig.webFace

    /**
     * True when the launch asked for the engine probe as the start face.
     *
     * L62 made the product home the session list; the device scripts that tap
     * 跑一轮 right after launch pass `--ez pdsh.face.probe true` and land on the
     * probe instead (see [ProbeLaunchConfig.EXTRA_PROBE_FACE]).
     */
    fun probeFaceOnLaunch(): Boolean = launchConfig.probeFace

    /**
     * One walk over every value the navigation facts are built from.
     *
     * Measured: the engine's view, the running duration (monotonic), the WebView
     * provider/version, the launch egress grant, the battery-exemption fact, the
     * background lane's declaration and notification capability, whether the
     * materialized workspace tree exists, and the app's own permission/hardware
     * state (`HostPermissionReadings`). Everything not measured stays `null`; no
     * field defaults to a fake value.
     */
    internal fun hostReadings(): HostReadings = HostReadings(
        webView = engine.view(),
        uptimeMs = runningSinceMillis?.let { since ->
            (clock.monotonicMillis() - since).coerceAtLeast(0L)
        },
        webViewProvider = webViewPackage?.packageName,
        webViewVersion = webViewPackage?.versionName,
        // The engine's own measurement, taken when the page finished: provider
        // identity plus the real Chromium version and the runtime features. It
        // is null until then, and the page renders 未知 — never "supported".
        webViewKernel = engine.kernelReport(),
        egressGrant = launchConfig.egressGrant(),
        batteryExemption = batteryExemption,
        background = HostBackground.readings(getApplication(), backgroundDeclaration),
        // The one workspace this app owns is `workspaceRoot`; its files page reads
        // the materialized mirror (`WalPaths.materializedWorkspace`). The directory's
        // existence is the measurement the folder row is built from: before the WAL
        // ever recovered there is no tree on disk, and the folder list must stay 未知
        // rather than show a folder that opens nothing.
        workspacePresent = Files.isDirectory(walPaths.materializedWorkspace),
        // The phone-permission matrix's device read: the platform snapshot comes from
        // `:domain`'s `AndroidDeviceCapabilities`, the permission state from
        // `AndroidHostPermissions` over it. Both are application-context reads; the
        // walk itself requests no permission and writes nothing — the request is the
        // Activity's (`MainActivity.phonePermission`), and what it leaves behind is
        // read back here as the ask log and the in-flight row.
        permissions = AndroidHostPermissions(
            getApplication<Application>(),
            AndroidDeviceCapabilities.platformFacts(getApplication<Application>()),
        ).read(),
        permissionAsks = PhonePermissionAsks.asked(),
        permissionInFlight = PhonePermissionAsks.inFlight,
    )

    /** The facts every routed page reads, rebuilt from one host walk. */
    internal fun navFacts(
        state: EngineState,
        handshake: HandshakeSummary?,
        turn: AppTurnState,
    ): PdshNavFacts {
        // The first snapshot after the transition may beat the state collector;
        // starting the spell here keeps the uptime from reading 未知 for it.
        if (state is EngineHostState.Running && runningSinceMillis == null) {
            runningSinceMillis = clock.monotonicMillis()
        }
        val measured = buildNavFacts(hostReadings(), state, handshake, turn, preferenceStore.snapshot())
        return measured.copy(settings = measured.settings.copy(engineLifecycle = nativeEngineLifecycle()))
    }

    /**
     * Performs one settings-page picker or stepper choice.
     *
     * The shell hands the row and option ids over unchanged; the store owns the
     * vocabulary and answers `false` for a row it does not write, so the caller
     * can say 「宿主未实现」 instead of showing a change that never landed.
     *
     * @param rowId `SettingsScreenState`'s row id (or a `preference.fontSize.*` id).
     * @param optionId the picker's option id, or a size as decimal text.
     */
    internal fun applyPreference(rowId: String, optionId: String): Boolean =
        preferenceStore.apply(rowId, optionId)

    internal suspend fun writePreferenceConfirmed(
        requestId: com.phonedsh.ui.state.StateRequestId,
        change: ConfirmedPreferenceChange,
    ): PreferenceWriteReceipt = preferenceStore.writeConfirmed(requestId, change)

    internal fun confirmedPreferenceChange(rowId: String, optionId: String): ConfirmedPreferenceChange? =
        preferenceStore.confirmedChange(rowId, optionId)

    /** Refreshes the source-backed permission namespace for SettingsRoute. */
    internal suspend fun refreshDefaultPermissionTier(): String? = withContext(Dispatchers.IO) {
        try {
            val client = DshTurnClient(host.channel, ::logTurn)
            val catalog = client.call("permissionPresets/catalog", timeoutMillis = 5_000) as? JsonObject
                ?: error("permission preset catalog was unreadable")
            val described = client.call("settings/describe", timeoutMillis = 5_000) as? JsonObject
                ?: error("settings description was unreadable")
            val namespace = permissionNamespace(described)
            val hasRevision = (namespace?.get("revision") as? JsonNumber)?.asLongOrNull() != null
            val options = (catalog["defaultOptions"] as? JsonArray)?.values?.mapNotNull { raw ->
                ((raw as? JsonObject)?.get("value") as? JsonString)?.value
            }?.toSet()
            mutablePermissionDefaults.value = PermissionDefaultsHostState(
                currentModeId = (catalog["defaultPreset"] as? JsonString)?.value,
                availableModeIds = options,
                writable = (described["writable"] as? JsonBool)?.value == true && namespace != null && hasRevision,
            )
            null
        } catch (failure: Exception) {
            val message = failure.message?.substringBefore('\n')?.take(180) ?: "权限默认值读取失败"
            mutablePermissionDefaults.value = mutablePermissionDefaults.value.copy(
                busy = false,
                writable = false,
                failure = message,
            )
            message
        }
    }

    /** Writes the same DSH permission namespace used by iOS and confirms by readback. */
    internal suspend fun writeDefaultPermissionTier(tier: com.phonedsh.ui.page.PermissionTier) {
        val client = DshTurnClient(host.channel, ::logTurn)
        mutablePermissionDefaults.value = mutablePermissionDefaults.value.copy(busy = true, failure = null)
        try {
        val catalog = client.call("permissionPresets/catalog", timeoutMillis = 5_000) as? JsonObject
            ?: error("permission preset catalog was unreadable")
        val candidates = when (tier) {
            com.phonedsh.ui.page.PermissionTier.VIEW_ONLY -> setOf("read-only", "phone-read-only")
            com.phonedsh.ui.page.PermissionTier.WORKSPACE_WRITE -> setOf("workspace-write", "phone")
            com.phonedsh.ui.page.PermissionTier.FULL_ACCESS -> setOf("danger-full-access", "bypassPermissions")
        }
        val options = (catalog["defaultOptions"] as? JsonArray)?.values.orEmpty()
        val mode = options.mapNotNull { (it as? JsonObject)?.get("value") as? JsonString }
            .firstOrNull { it.value in candidates }?.value
            ?: error("this host does not offer the selected default permission")
        val before = client.call("settings/describe", timeoutMillis = 5_000) as? JsonObject
            ?: error("settings description was unreadable")
        check((before["writable"] as? JsonBool)?.value == true) { "DSH settings are read-only" }
        val namespace = permissionNamespace(before) ?: error("the permission settings namespace is unavailable")
        val revision = ((namespace["revision"] as? JsonNumber)?.asLongOrNull())
            ?: error("the permission settings revision is unknown")
        client.call("settings/update", JsonObject(mapOf(
            "ns" to JsonString("permission"),
            "patch" to JsonObject(mapOf("defaultPreset" to JsonString(mode))),
            "expectedRevision" to JsonNumber(revision.toString()),
        )), timeoutMillis = 5_000)
        val after = client.call("permissionPresets/catalog", timeoutMillis = 5_000) as? JsonObject
            ?: error("permission preset readback was unreadable")
        check((after["defaultPreset"] as? JsonString)?.value == mode) {
            "permission default was not confirmed by permission catalog readback"
        }
        val available = (after["defaultOptions"] as? JsonArray)?.values.orEmpty().mapNotNull { raw ->
            ((raw as? JsonObject)?.get("value") as? JsonString)?.value
        }.toSet()
        mutablePermissionDefaults.value = PermissionDefaultsHostState(
            currentModeId = mode,
            availableModeIds = available,
            writable = (before["writable"] as? JsonBool)?.value == true,
        )
        } catch (failure: Exception) {
            mutablePermissionDefaults.value = mutablePermissionDefaults.value.copy(
                busy = false,
                writable = false,
                failure = failure.message?.substringBefore('\n')?.take(180) ?: "权限默认值保存失败",
            )
            throw failure
        }
    }

    private fun permissionNamespace(description: JsonObject): JsonObject? =
        (description["namespaces"] as? JsonArray)?.values.orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["ns"] as? JsonString)?.value == "permission" }


    /** 显示代码工作视图. */
    internal fun setDeveloperTools(value: Boolean) {
        preferenceStore.setDeveloperTools(value)
    }

    /** 繁忙时的发送行为. */
    internal fun setBusyEnterMode(mode: BusyEnterMode) {
        preferenceStore.setBusyEnterMode(mode)
    }

    /** 后台运行: whether the next turn should open a background window. */
    internal fun setBackgroundKeepAlive(value: Boolean) {
        preferenceStore.setBackgroundKeepAlive(value)
    }

    /** The host's wall reading for the sessions page's relative-time labels. */
    internal fun nowMillis(): Long = clock.nowMillis()

    /**
     * Re-reads the sessions the journal lane stored.
     *
     * The journal is this host's only durable per-session store, so it is the
     * only honest source for a session list.
     *
     * **Known cost, and the fix.** This is a second `recover()` scan of the
     * journal (the host already ran one during `start()`). It is correct today:
     * [JournalStore.recover] is documented as idempotent, and the store's mutex
     * serialises this scan with the host's own recovery and with any in-flight
     * append, so no torn read or lost record can result. It is **not the
     * designed end state**: the right fix is for `:dsh` to expose a read-only
     * journal-watermarks accessor, and this method should then read that API
     * instead of scanning the store a second time.
     *
     * The scan runs off the main thread, and the rows change only when it
     * returns, never on the gesture that asked for it.
     */
    internal fun refreshSessions() {
        if (!sessionScanInFlight.compareAndSet(false, true)) return
        mutableSessions.update { it.copy(scanInFlight = true) }
        turnScope.launch {
            try {
                val scan = withContext(Dispatchers.IO) { readJournalSessions() }
                // A journal row a chat already owns must not also list as its own
                // conversation: the chat's row (with its title) is the one row.
                val claimed = chatWorkerClaims
                mutableSessions.update { snapshot ->
                    snapshot.copy(
                        waiting = false,
                        failure = scan.failure,
                        sessions = mergeSessions(
                            scan.sessions.filterNot { it.id in claimed },
                            snapshot.sessions,
                        ),
                    )
                }
            } finally {
                sessionScanInFlight.set(false)
                mutableSessions.update { it.copy(scanInFlight = false) }
            }
        }
    }

    /** Hides the last read failure; the rows from the last good read stay. */
    internal fun clearSessionsFailure() {
        mutableSessions.update { it.copy(failure = null) }
    }

    private val turnRunning = AtomicBoolean(false)
    private val mutableTurn = MutableStateFlow<AppTurnState>(AppTurnState.Idle)

    /** The one turn's UI state; [runTurn] is the only writer. */
    internal val turn: StateFlow<AppTurnState> = mutableTurn.asStateFlow()

    private val mutableSessions = MutableStateFlow(HostSessionSnapshot())

    /** The host's session read; [refreshSessions] and a completed turn write it. */
    internal val sessions: StateFlow<HostSessionSnapshot> = mutableSessions.asStateFlow()

    /** One journal scan at a time; a second request while one runs is ignored. */
    private val sessionScanInFlight = AtomicBoolean(false)

    // ---------------------------------------------------------------- chat lane

    /**
     * The chat transcript cache: the App's own stable conversation records.
     *
     * `SessionArchive` (the L65 lane) keeps the settled tail's *kinds*, which is
     * enough for the probe's summary but not for painting a conversation after a
     * cold start. This store keeps the rendered node sequence (see
     * `ChatSessionStore`); neither is the authority for the events — the journal is.
     */
    internal val chatStore = ChatSessionStore(domainStore)

    private val mutableChat = MutableStateFlow(ChatUiState())

    /** The chat screen's host state; the App's one writer is this view model. */
    internal val chat: StateFlow<ChatUiState> = mutableChat.asStateFlow()
    internal val currentChatId: String get() = mutableChat.value.chatId

    /**
     * The Worker session ids a chat record has claimed.
     *
     * The journal lists Worker sessions; a chat owns one or more of them across
     * restarts (each restart creates a new Worker session in the same chat), so
     * the list must not also show them as separate conversations.
     */
    @Volatile
    private var chatWorkerClaims: Set<String> = emptySet()

    /** The running chat turn, or `null`; cancelling it is 停止. */
    private var chatTurnJob: kotlinx.coroutines.Job? = null

    /** The turn's runner, kept so 停止 can reach the Worker's cancel endpoint. */
    @Volatile
    private var chatRunner: ChatTurnRunner? = null
    private var activeRetryRequestId: String? = null

    /** A monotonic counter for node ordinals; never reset, so ids stay unique. */
    private var chatOrdinal: Int = 0

    private val turnScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // budgets.md §4 evidence: the raw ceiling and the row it selects. The
        // host prints the row it was given (its `budget tier:` line); this one
        // names the measurement the row came from, so a device that reports
        // 2 GiB cannot be mistaken for one that reported 8 GiB.
        HostBudgetTier.readTotalMemoryBytes(application)?.let { totalMem ->
            LogcatHostLog.info(
                "device memory: totalMem=$totalMem bytes; budget tier: " +
                    "${deviceBudgets.tier.displayName}; single_file_limit: " +
                    "${deviceBudgets.singleFileBytes} bytes " +
                    "(${deviceBudgets.singleFileBytes / ControllerBudgets.MIB} MiB); " +
                    "Worker env ${HostBudgetTier.SINGLE_FILE_LIMIT_ENV}=" +
                    "${deviceBudgets.singleFileBytes}",
            )
        } ?: LogcatHostLog.warn(
            "device memory: totalMem unavailable; budget tier " +
                "${deviceBudgets.tier.displayName} (the smallest row) applies",
        )
        // P27 evidence: the route this launch published to the Worker, printed
        // before the first turn so a reader can tell "the model was refused
        // because this launch had no route" from "the model refused the
        // credential". No endpoint and no key appears here.
        LogcatHostLog.info(modelRouteLine())
        channelSeam.attach(host.channelHandler)
        restoreSessionArchive()
        restoreChatSessions()
        // Asynchronous by contract: recovery, the final HELLO and engine.start()
        // run on the host's scope. Observe engine.state for the outcome.
        host.start()
        // The runtime page's uptime is the length of the current Running spell,
        // measured on the monotonic clock so a wall-clock change cannot move it.
        // A host that could not recover its WAL advertises no `wal/1`; the worker
        // then refuses to boot and the screen would otherwise keep saying
        // 「运行中」. Raising it here is the only surface that reaches the user.
        turnScope.launch {
            engine.handshake.collect { summary ->
                val caps = summary?.hostCapabilities ?: return@collect
                // The engine publishes a worker-only summary with empty host caps
                // before the host caps merge. Only a non-empty list can prove the
                // host omission; reading the empty one as "wal/1 missing" raises a
                // false banner on every healthy boot (measured 2026-11-01).
                if (caps.isEmpty()) return@collect
                if ("wal/1" in caps) {
                    ProbeRecovery.clearWalUnavailable()
                } else {
                    ProbeRecovery.raiseWalUnavailable(caps)
                }
            }
        }
        turnScope.launch {
            engine.state.collect { next ->
                when (next) {
                    is EngineHostState.Running -> if (runningSinceMillis == null) {
                        runningSinceMillis = clock.monotonicMillis()
                    }

                    EngineHostState.Stopped, is EngineHostState.Failed ->
                        runningSinceMillis = null

                    EngineHostState.Starting, EngineHostState.Recovering -> Unit
                }
            }
        }
        if (launchConfig.egressProbe) {
            turnScope.launch { runEgressProbe() }
        }
    }

    fun start() = host.start()

    fun stop() = host.stop()

    private val nativeLaunchStarted = AtomicBoolean(false)
    private val sendStartupInFlight = AtomicBoolean(false)

    internal fun startNativeSurface() {
        if (!nativeLaunchStarted.compareAndSet(false, true)) return
        if (host.state.value == com.phonedsh.controller.HostState.FAILED) restartChatEngine() else start()
    }

    internal fun nativeEngineLifecycle(): com.phonedsh.ui.state.EngineLifecycleFacts {
        val failed = host.state.value == com.phonedsh.controller.HostState.FAILED || state.value is EngineHostState.Failed
        val status = if (failed) com.phonedsh.ui.state.EngineLifecycleStatus.FAILED else when (state.value) {
            EngineHostState.Stopped -> com.phonedsh.ui.state.EngineLifecycleStatus.STOPPED
            EngineHostState.Starting -> com.phonedsh.ui.state.EngineLifecycleStatus.STARTING
            EngineHostState.Recovering -> com.phonedsh.ui.state.EngineLifecycleStatus.RECOVERING
            is EngineHostState.Running -> com.phonedsh.ui.state.EngineLifecycleStatus.RUNNING
            is EngineHostState.Failed -> com.phonedsh.ui.state.EngineLifecycleStatus.FAILED
        }
        return com.phonedsh.ui.state.EngineLifecycleFacts(status, needsReset = failed,
            canStartBySending = status == com.phonedsh.ui.state.EngineLifecycleStatus.STOPPED &&
                modelRoute.route != ModelRoute.NONE && !sendStartupInFlight.get())
    }

    internal fun restartChatEngine() {
        stopChat()
        chatPreparationJob?.cancel()
        turnScope.launch {
            try {
                host.stop()
                host.start()
                withTimeout(HOST_READY_TIMEOUT_MILLIS) { host.ready.first { it } }
                host.resetFailure()
                withTimeout(ENGINE_READY_TIMEOUT_MILLIS) {
                    host.state.first { it == com.phonedsh.controller.HostState.RUNNING }
                    engine.state.first { it is EngineHostState.Running }
                }
                mutableChat.update { it.copy(error = null, bannerDismissed = false) }
                prepareChatSession()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                mutableChat.update { it.copy(error = "引擎还没恢复，请查看运行状态后重试。", bannerDismissed = false) }
            }
        }
    }

    /**
     * The one line that records this launch's model route.
     *
     * Its counterpart is the Worker's refusal (`plugins/worker-entry/src/model-route.ts`):
     * the host publishes `PDSH_MODEL_ROUTE`, the Worker refuses on `none`, and
     * this line is what makes the two halves verifiable in one logcat capture.
     */
    private fun modelRouteLine(): String = buildString {
        append("model route: ").append(modelRoute.route.wire)
        append(" (Worker env ").append(ModelRouteFact.ENV).append('=')
        append(modelRoute.route.wire)
        modelRoute.missing?.let {
            append(", ").append(ModelRouteFact.MISSING_ENV).append('=').append(it.wire)
        }
        append("); ")
        when (modelRoute.route) {
            ModelRoute.HOST_EDGE -> append("model requests are answered by the host edge")
            ModelRoute.DIRECT -> append("the Worker talks to the launch's base URL directly")
            ModelRoute.NONE -> append(modelRoute.missing!!.sentence)
                .append(": the Worker refuses model requests locally instead of ")
                .append("falling back to the public DeepSeek API")
        }
    }

    /**
     * The turn-failure text, with this launch's route fact appended when it has none.
     *
     * The appended sentence states a fact about the **launch**, not a claim about
     * the cause: on a routeless launch the Worker refused the model request
     * locally, which is why the turn's end reason is `error`, and the reader must
     * not be sent to look for a wrong key.
     */
    private fun failureText(reason: String): String {
        val diagnosis = modelRoute.diagnosis() ?: return reason
        return "$reason; $diagnosis"
    }

    /**
     * Starts one turn through the App's own `/api` path.
     *
     * Idempotent while a turn is in flight. The prompt is the launch-configured
     * text, not a model call: the acceptance upstream is scripted.
     *
     * A launch with no model route still runs the turn: the refusal belongs to
     * the Worker (the only layer that can keep `llm-deepseek` from falling back
     * to its public base), and running it is what proves the refusal happens
     * before any request leaves the device. [failureText] then names the missing
     * input in the App's own line.
     */
    fun runTurn(prompt: String = launchConfig.prompt) {
        if (!turnRunning.compareAndSet(false, true)) {
            Log.i(TAG_TURN, "turn ignored: one is already running")
            return
        }
        Log.i(TAG_TURN, "APP_TURN_BUTTON prompt=${prompt.length} chars")
        // Durable tombstone before the first await: a process killed during the
        // turn leaves it behind, and the next launch reports "interrupted at step
        // N" instead of silently showing a half session.
        val epoch = ProbeRecovery.beginTurn(getApplication<Application>())
        // The background window opens *before* the first await, while this button
        // gesture still counts as visible: UIDT cannot be scheduled from a
        // backgrounded app (plan §7.10-B2) and a foreground service cannot be
        // started from the background on Android 12+. The result is the platform's
        // answer, not an assumption; a foreground-only answer is logged as such.
        //
        // 后台运行 is the one preference that changes a turn's behaviour: off
        // means no window is opened at all and the turn runs foreground-only
        // (`ChatModel.swift:43-49` is the baseline's switch; on Android the
        // equivalent of the silent-audio session is this foreground-service
        // window). The tombstone above is still written either way: a turn can
        // die without a window, and the next launch must still report it.
        if (preferenceStore.snapshot().backgroundKeepAlive) {
            HostBackground.startTurn(
                getApplication<Application>(),
                epoch,
                backgroundDeclaration,
            )
        } else {
            Log.i(TAG_TURN, "BG_TURN_SKIPPED reason=preference backgroundKeepAlive=false")
        }
        mutableTurn.value = AppTurnState.Running
        turnScope.launch {
            try {
                withTimeout(HOST_READY_TIMEOUT_MILLIS) { host.ready.first { it } }
                withTimeout(ENGINE_READY_TIMEOUT_MILLIS) {
                    engine.state.first { it is EngineHostState.Running }
                }
                val client = DshTurnClient(host.channel, ::logTurn)
                // The prompt is the caller's (the composer's in the chat lane); the
                // launch extra is only the default for the probe's own button.
                val result = client.runTurn(prompt)
                for (event in result.events) logTurn("host event: ${event.describe()}")
                // Completion is the Worker's own edge, not the shape of its tool
                // use: a turn that answered without calling a tool is still a
                // completed turn. Gating on the tool edges reported a plain Q&A
                // turn as `incomplete` while its `turn/end` said `completed`
                // (measured 2026-11-01), which is the one thing this screen may
                // not do. `sawTheFourEdges` is kept as a separate observation of
                // the tool lane and logged next to the OK line.
                val answered = result.events.any { it.kind == HostEventKind.ASSISTANT_MESSAGE }
                val complete = result.turnEndReason == "completed" && answered
                if (complete) {
                    logTurn(
                        "APP_TURN_OK session=${result.sessionId} " +
                            "kinds=${result.kinds.joinToString(",")} reason=completed " +
                            "tool_edges=${result.sawTheFourEdges}",
                    )
                    ProbeRecovery.endTurn(getApplication<Application>())
                    val completed = AppTurnState.Completed(
                        sessionId = result.sessionId,
                        kinds = result.kinds,
                        commands = toolCommands(result.events),
                        writes = toolWrites(result.events),
                    )
                    mutableTurn.value = completed
                    recordCompletedTurn(result.sessionId)
                    // After the screen already has the turn: a store refusal
                    // must not turn a completed turn into a failed one.
                    archiveCompletedTurn(completed, clock.nowMillis())
                } else {
                    failTurn(
                        failureText(
                            "incomplete: kinds=${result.kinds.joinToString(",")} " +
                                "reason=${result.turnEndReason ?: "-"}",
                        ),
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                failTurn(failureText("timeout"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failTurn(failureText(reasonOf(failure)))
            } finally {
                turnRunning.set(false)
                // A cancelled scope (ViewModel cleared, host stopped) is not an
                // explicit end above, so the window and the marker are closed here:
                // a background window left open holds a notification nothing owns.
                // Every other path already ended the turn, and ending it twice is a
                // no-op (the marker is gone and no window is running).
                if (mutableTurn.value is AppTurnState.Running) {
                    ProbeRecovery.endTurn(getApplication<Application>())
                }
            }
        }
    }

    /**
     * The commands the turn's `tool_call` events named.
     *
     * `tool_call.arguments` is the contract's one arbitrary-JSON member, and the
     * worker encodes it as a JSON *string* (the trajectory goldens carry
     * `"arguments":"{\"cmd\":\"ls\"}"`), so both a string and an object are
     * accepted. Non-JSON or argument-less calls yield no row: a tool name alone is
     * never rendered as a command.
     */
    private fun toolCommands(events: List<HostEvent>): List<TurnToolCommand> =
        events.mapNotNull { event ->
            if (event.kind != HostEventKind.TOOL_CALL) return@mapNotNull null
            val data = event.data ?: return@mapNotNull null
            val arguments = argumentsOf(data) ?: return@mapNotNull null
            val command = (arguments["command"] as? JsonString)?.value
                ?: (arguments["cmd"] as? JsonString)?.value
                ?: return@mapNotNull null
            val id = (data["callId"] as? JsonString)?.value
                ?: (data["call_id"] as? JsonString)?.value
                ?: command
            TurnToolCommand(id = id, command = command)
        }

    /**
     * The paths the turn's write tools named.
     *
     * Only the three tool names §2 folds into the changed-files card are
     * recognised; any other tool yields no row, because this host has not
     * measured whether it wrote a file.
     */
    private fun toolWrites(events: List<HostEvent>): List<TurnWrite> =
        events.mapNotNull { event ->
            if (event.kind != HostEventKind.TOOL_CALL) return@mapNotNull null
            val data = event.data ?: return@mapNotNull null
            val toolName = (data["name"] as? JsonString)?.value ?: return@mapNotNull null
            if (toolName !in WRITE_TOOLS) return@mapNotNull null
            val arguments = argumentsOf(data)
            val path = (arguments?.get("path") as? JsonString)?.value
                ?: (arguments?.get("file_path") as? JsonString)?.value
                ?: (arguments?.get("filePath") as? JsonString)?.value
                ?: (data["path"] as? JsonString)?.value
                ?: return@mapNotNull null
            TurnWrite(path = path, toolName = toolName)
        }

    /**
     * The `arguments` object of a `tool_call`, decoded from either encoding.
     *
     * Returns `null` when the member is absent, is not JSON, or does not decode to
     * an object: the caller then renders no row rather than a guessed one.
     */
    private fun argumentsOf(data: JsonObject): JsonObject? =
        when (val raw = data["arguments"] ?: data["input"]) {
            is JsonObject -> raw
            is JsonString -> runCatching { JsonValue.parse(raw.value) as? JsonObject }.getOrNull()
            else -> null
        }

    /**
     * Exercises the App's own `/v1/http` route with two requests: one to the
     * exact launch origin (must be 200) and one to an unlisted origin (must be
     * 403 without upstream contact). Diagnostic only; it is started when the
     * launch asked for it, and it logs status codes and stable codes only.
     */    private suspend fun runEgressProbe() {
        try {
            withTimeout(HOST_READY_TIMEOUT_MILLIS) { host.ready.first { it } }
            withTimeout(ENGINE_READY_TIMEOUT_MILLIS) {
                engine.state.first { it is EngineHostState.Running }
            }
            val running = engine.state.value as? EngineHostState.Running ?: return
            val base = launchConfig.deepseekBaseUrl ?: return
            val token = launchToken.current() ?: return
            val allowed = probeEgress(running.origin, token, base.trimEnd('/') + "/health")
            if (allowed == 200) {
                Log.i(TAG_TURN, "APP_EGRESS_OK status=$allowed")
            } else {
                Log.e(TAG_TURN, "APP_EGRESS_FAILED status=$allowed")
            }
            val denied = probeEgress(running.origin, token, "https://unlisted.example/")
            if (denied == 403) {
                Log.i(TAG_TURN, "APP_EGRESS_DENIED status=$denied")
            } else {
                Log.e(TAG_TURN, "APP_EGRESS_NOT_DENIED status=$denied")
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.e(TAG_TURN, "APP_EGRESS_FAILED reason=timeout")
        } catch (failure: Exception) {
            Log.e(TAG_TURN, "APP_EGRESS_FAILED reason=${failure.javaClass.simpleName}")
        }
    }

    /** One POST to the App's own `/v1/http`; returns the status, never a body. */
    private suspend fun probeEgress(origin: String, token: String, target: String): Int =
        withContext(Dispatchers.IO) {
            val connection = URI("$origin/v1/http").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-PDSH-Target-URL", target)
                connection.setRequestProperty("X-PDSH-Target-Method", "GET")
                connection.doOutput = true
                connection.outputStream.use { it.write(ByteArray(0)) }
                val status = connection.responseCode
                runCatching { connection.errorStream?.use { stream -> stream.readBytes() } }
                runCatching { connection.inputStream?.use { stream -> stream.readBytes() } }
                status
            } finally {
                runCatching { connection.disconnect() }
            }
        }

    // ---------------------------------------------------------------- chat lane
    //
    // The three calls of a chat turn are the same ones 跑一轮 uses (`session/create`,
    // `session/follow`, `session/prompt`); the difference is that this path takes the
    // prompt from the composer, keeps one stable conversation across turns and cold
    // starts, renders the live text, and can stop the turn.
    //
    // Persistence is serialised on one IO thread: every write of a chat record is
    // submitted in the order the screen produced it, so a later save can never be
    // overtaken by an earlier one.

    private val chatIo = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** The page's state, assembled from the host's own readings. */
    internal fun chatSurfaceState(): ChatScreenState = chatScreenState(
        ui = mutableChat.value,
        engineStarted = state.value is EngineHostState.Running && host.state.value != com.phonedsh.controller.HostState.FAILED,
        providerNeedsKey = modelRoute.route == ModelRoute.NONE,
        canSend = state.value is EngineHostState.Running && host.state.value != com.phonedsh.controller.HostState.FAILED,
        canSteer = state.value is EngineHostState.Running && mutableChat.value.workerSessionId != null,
        enterMode = when (preferenceStore.snapshot().busyEnterMode) {
            BusyEnterMode.QUEUE -> com.phonedsh.ui.state.EnterMode.QUEUE
            BusyEnterMode.STEER -> com.phonedsh.ui.state.EnterMode.STEER
        },
        lifecycle = nativeEngineLifecycle(),
    )

    /** What the person typed. Local state; the page never stores it. */
    internal fun chatDraftChanged(text: String) {
        mutableChat.update { current ->
            if (current.draft == text) current else current.copy(draft = text, retryBusyPromptRequestId = null)
        }
    }

    internal fun chatWorkspaceSnapshot(): Pair<Boolean, String?> =
        Files.isDirectory(walPaths.materializedWorkspace) to "/dsh/workspace"

    /** Shell capabilities and limits come from this boot's real ExecBridge and accelerators. */
    internal fun shellSnapshot(run: ShellRun = ShellRun()): ShellSnapshot {
        val commands = host.readOnlyAcceleratorNames()
        val mounts = listOf(walPaths.materializedWorkspace, walPaths.materializedHome).count(Files::isDirectory)
        return ShellSnapshot(
            commands = commands,
            environment = if (commands.isEmpty()) "pdsh-shell unavailable" else "pdsh-shell via DSH exec/1",
            mounts = mounts,
            limits = ShellLimits(
                timeoutMilliseconds = AcceleratorLimits.DEFAULT_TIMEOUT_MS,
                maxOutputBytes = ShellAcceleratorTool.MAX_OUTPUT_BYTES,
                maxFiles = AcceleratorLimits.DEFAULT_MAX_FILES.toInt(),
                maxFileBytes = AcceleratorLimits.DEFAULT_MAX_FILE_BYTES,
            ),
            run = run,
        )
    }

    /** Creates one source-shaped interactive EXEC_REQ through the registered read-only bridge. */
    internal fun shellExecRequest(
        command: String,
        workingDirectory: String,
        arguments: String,
        requestId: com.phonedsh.ui.state.StateRequestId,
    ): ExecRequest? = ShellExecRequestFactory.create(
        command = command,
        workingDirectory = workingDirectory,
        arguments = arguments,
        requestId = requestId,
        availableCommands = host.readOnlyAcceleratorNames(),
        mountedRoots = buildList {
            if (Files.isDirectory(walPaths.materializedWorkspace)) add("/dsh/workspace")
            if (Files.isDirectory(walPaths.materializedHome)) add("/dsh/home")
        },
        requiresMseq = host.appliedWorkspaceMseq(),
        deadline = clock.nowMillis() + AcceleratorLimits.DEFAULT_TIMEOUT_MS,
    )

    internal fun runShellRequest(request: ExecRequest): Flow<ExecEvent> = host.runReadOnlyAccelerator(request)

    internal fun cancelShellRequest(execId: String) = host.cancelReadOnlyAccelerator(execId)


    internal fun chatImeChanged(visible: Boolean) {
        mutableChat.update { it.copy(imeVisible = visible) }
    }

    internal fun expandChatPending() {
        mutableChat.update { it.copy(pendingExpanded = true) }
    }

    internal fun toggleChatRow(id: String) {
        mutableChat.update { it.copy(expandedRowIds = if (id in it.expandedRowIds) {
            it.expandedRowIds - id
        } else it.expandedRowIds + id) }
    }

    internal fun archiveChatSession(chatId: String): Boolean = updateChatMetadata(chatId) {
        chatStore.setArchived(chatId, true)
    }

    internal fun restoreChatSession(chatId: String): Boolean = updateChatMetadata(chatId) {
        chatStore.setArchived(chatId, false)
    }

    internal fun setChatSessionPinned(chatId: String, pinned: Boolean): Boolean = updateChatMetadata(chatId) {
        chatStore.setPinned(chatId, pinned)
    }

    private fun updateChatMetadata(chatId: String, write: () -> Boolean): Boolean {
        if (mutableChat.value.chatId == chatId && mutableChat.value.running) return false
        if ((mutableSessions.value.sessions + mutableSessions.value.archivedSessions).none { it.id == chatId }) return false
        chatIo.launch {
            runCatching {
                check(write()) { "会话不存在，修改没有保存" }
                applyChatRecords(chatStore.loadAll())
            }.onFailure {
                mutableSessions.update { snapshot -> snapshot.copy(failure = "会话修改没有保存，请重试。") }
            }
        }
        return true
    }

    private fun applyChatRecords(records: List<ChatRecord>) {
        fun ChatRecord.listing() = HostSessionRecord(id, updatedAtMs, title, workspaceID, pinned)
        val ids = records.map { it.id }.toSet()
        val claims = records.flatMap { it.workerSessionIds }.toSet()
        val active = records.filterNot { it.archived }
        mutableSessions.update { snapshot -> snapshot.copy(
            waiting = false,
            sessions = mergeSessions(snapshot.sessions.filterNot { it.id in ids || it.id in claims }, active.map { it.listing() }),
            archivedSessions = records.filter { it.archived }.map { it.listing() },
            pinnedSessionIDs = active.filter { it.pinned }.map { it.id }.toSet(),
            currentSessionID = snapshot.currentSessionID?.takeUnless { id -> records.any { it.id == id && it.archived } },
        ) }
    }

    private data class ChatResponder(
        val request: com.phonedsh.dsh.turn.PendingRemoteRequest,
        val answer: suspend (JsonValue) -> Unit,
    )
    private val chatResponders = java.util.concurrent.ConcurrentHashMap<String, ChatResponder>()
    private val answeringRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val snapshotRefreshInFlight = AtomicBoolean(false)
    private val snapshotFullRefreshQueued = AtomicBoolean(false)
    private var chatSnapshotJob: kotlinx.coroutines.Job? = null
    private var chatPreparationJob: kotlinx.coroutines.Job? = null
    private val hostAutomation by lazy {
        HostAutomation({ endpoint, args -> DshTurnClient(host.channel, ::logTurn).call(endpoint, args) },
            { mutableChat.value.workerSessionId }, turnScope)
    }
    internal val automation: StateFlow<com.phonedsh.ui.page.AutomationFacts> get() = hostAutomation.facts
    internal fun refreshAutomation() = hostAutomation.refresh()
    internal fun handleAutomationAction(action: com.phonedsh.ui.nav.PdshHostAction): Boolean {
        when (action) {
            com.phonedsh.ui.nav.PdshHostAction.RefreshAutomation -> hostAutomation.refresh()
            is com.phonedsh.ui.nav.PdshHostAction.SaveAutomationSchedule -> hostAutomation.save(action.draft, action.submissionID)
            is com.phonedsh.ui.nav.PdshHostAction.DeleteAutomationSchedule -> hostAutomation.delete(action.id)
            is com.phonedsh.ui.nav.PdshHostAction.LoadAutomationHistory -> hostAutomation.history(action.id, action.before)
            else -> return false
        }
        return true
    }

    internal fun prepareChatSession() {
        val current = mutableChat.value
        if (current.chatId.isEmpty() || state.value !is EngineHostState.Running || host.state.value == com.phonedsh.controller.HostState.FAILED) return
        if (current.workerSessionId != null) { refreshChatSession(); return }
        if (chatPreparationJob?.isActive == true) return
        chatPreparationJob = turnScope.launch(Dispatchers.IO) {
            try {
                withTimeout(HOST_READY_TIMEOUT_MILLIS) { host.ready.first { it } }
                val worker = DshTurnClient(host.channel, ::logTurn).createSessionWhenReady(cwd = "/dsh/workspace")
                if (mutableChat.value.chatId == current.chatId) claimWorkerSession(current.chatId, worker)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                mutableChat.update { if (it.chatId != current.chatId) it else it.copy(
                    notice = "会话还没准备好，发送时会重试。",
                ) }
            }
        }
    }

    private fun receiveChatRequest(
        chatId: String,
        request: com.phonedsh.dsh.turn.PendingRemoteRequest,
        responder: suspend (JsonValue) -> Unit,
    ) {
        if (mutableChat.value.chatId != chatId) return
        chatResponders[request.eventID] = ChatResponder(request, responder)
        val node = pendingRequestNode(chatId, request)
        mutableChat.update { current ->
            if (current.chatId != chatId) current else current.copy(
                pendingNodes = current.pendingNodes.filterNot { it.nodeID == node.nodeID } + node,
            )
        }
    }

    private fun removeChatRequest(requestId: String) {
        chatResponders.remove(requestId)
        answeringRequests.remove(requestId)
        mutableChat.update { current -> current.copy(pendingNodes = current.pendingNodes.filterNot {
            (it.props["requestId"] as? JsonString)?.value == requestId
        }) }
    }

    internal fun answerChatQuestion(pending: com.phonedsh.ui.state.PendingAnswer, answer: String) {
        val responder = chatResponders[pending.requestId] ?: return
        submitChatAnswer(pending.requestId, questionOutcome(responder.request, answer))
    }

    internal fun answerChatPermission(pending: com.phonedsh.ui.state.PendingAnswer, allowed: Boolean) {
        submitChatAnswer(pending.requestId, JsonObject(mapOf(
            "kind" to JsonString("result"),
            "value" to JsonString(if (allowed) "allowed-once" else "rejected"),
        )))
    }

    internal fun dismissChatQuestion(pending: com.phonedsh.ui.state.PendingAnswer) {
        // iOS dismissQuestion only hides this seat; it never answers the waterfall.
        // Keep the responder until the Worker resolves or re-delivers the request.
        mutableChat.update { current -> current.copy(pendingNodes = current.pendingNodes.filterNot {
            (it.props["requestId"] as? JsonString)?.value == pending.requestId
        }) }
    }

    private fun submitChatAnswer(requestId: String, outcome: JsonValue) {
        val responder = chatResponders[requestId] ?: return
        if (!answeringRequests.add(requestId)) return
        fun updateRequest(submitting: Boolean, error: String? = null) {
            mutableChat.update { current -> current.copy(pendingNodes = current.pendingNodes.map { node ->
                if ((node.props["requestId"] as? JsonString)?.value != requestId) node else node.copy(
                    props = JsonObject(node.props.members + mapOf(
                        "submitting" to com.phonedsh.agentcore.JsonBool(submitting),
                        "submissionError" to (error?.let(::JsonString) ?: com.phonedsh.agentcore.JsonNull),
                    )),
                )
            }) }
        }
        updateRequest(true)
        turnScope.launch {
            try {
                responder.answer(outcome)
                removeChatRequest(requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateRequest(false, "回答没有提交成功，请重试。")
            } finally {
                answeringRequests.remove(requestId)
            }
        }
    }

    /** Refresh the same live catalogs and projection used by the iOS dock. */
    internal fun refreshChatSession(full: Boolean = true) {
        val current = mutableChat.value
        val worker = current.workerSessionId ?: return
        if (!snapshotRefreshInFlight.compareAndSet(false, true)) {
            if (full) snapshotFullRefreshQueued.set(true)
            return
        }
        turnScope.launch(Dispatchers.IO) {
            val client = DshTurnClient(host.channel, ::logTurn)
            try {
                val modes = if (full) runCatching { client.call("permissionPresets/catalog", timeoutMillis = 5_000) }.getOrNull() else null
                val commands = if (full) runCatching { client.call("commands/list", JsonObject(mapOf(
                    "agentId" to JsonString(worker),
                )), timeoutMillis = 5_000) }.getOrNull() else null
                val models = if (full) runCatching { client.call("session/modelCatalog", timeoutMillis = 5_000) }.getOrNull() else null
                // Read the changing projection last. Slow catalog reads must not
                // let an older goal/model/permission overwrite a later refresh.
                val projections = client.call("session/projections", JsonObject(mapOf(
                    "request" to JsonObject(mapOf("sessionId" to JsonString(worker))),
                )), timeoutMillis = 5_000)
                val measured = sessionSnapshotOf(projections, modes, commands, models, sessionId = worker)
                mutableChat.update { live ->
                    if (live.chatId != current.chatId || live.workerSessionId != worker) live else live.copy(
                        sessionSnapshot = if (full) measured else live.sessionSnapshot.copy(
                            goal = measured.goal, todos = measured.todos,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableChat.update { live -> if (live.chatId != current.chatId) live else live.copy(
                    sessionSnapshot = live.sessionSnapshot.copy(
                        accessError = "没读到权限,再试一次。", commandsError = "没读到命令,再试一次。",
                    ),
                ) }
            } finally {
                snapshotRefreshInFlight.set(false)
                if (snapshotFullRefreshQueued.getAndSet(false)) refreshChatSession(full = true)
            }
        }
    }

    /** Mutate one durable DSH queue item, then read the projection back before publishing success. */
    internal fun updateChatQueueItem(
        item: com.phonedsh.ui.state.ManagedQueueItemState,
        action: com.phonedsh.ui.state.QueueAction,
        requestId: com.phonedsh.ui.state.StateRequestId,
        replacement: String? = null,
    ): Boolean {
        val current = mutableChat.value
        val worker = current.workerSessionId ?: return false
        val itemId = (item.id as? com.phonedsh.ui.state.QueueItemId.Durable)?.value ?: return false
        if (action == com.phonedsh.ui.state.QueueAction.QUEUE) return false
        if (current.chatId.isBlank() || item.actionRequestId != null && item.busy == true) return false
        if (item.canEdit != true && action == com.phonedsh.ui.state.QueueAction.EDIT) return false
        if (item.canRemove != true && action == com.phonedsh.ui.state.QueueAction.REMOVE) return false
        if (item.canSteer != true && action == com.phonedsh.ui.state.QueueAction.STEER) return false
        if (action == com.phonedsh.ui.state.QueueAction.EDIT && replacement.isNullOrBlank()) return false
        val appChatId = current.chatId
        val activeItems = current.sessionSnapshot.queueItems ?: return false
        if (activeItems.none { (it.id as? com.phonedsh.ui.state.QueueItemId.Durable)?.value == itemId }) return false

        mutableChat.update { live ->
            if (live.chatId != appChatId || live.workerSessionId != worker) live else live.copy(
                sessionSnapshot = live.sessionSnapshot.copy(
                    queueItems = activeItems.map { row ->
                        if ((row.id as? com.phonedsh.ui.state.QueueItemId.Durable)?.value == itemId) {
                            row.copy(busy = true, actionRequestId = requestId, receipt = null)
                        } else row
                    },
                ),
            )
        }
        turnScope.launch(Dispatchers.IO) {
            val outcome = try {
                Result.success(run {
                val client = DshTurnClient(host.channel, ::logTurn)
                val actionValue = when (action) {
                    com.phonedsh.ui.state.QueueAction.QUEUE -> error("new queue submissions use session/prompt")
                    com.phonedsh.ui.state.QueueAction.EDIT -> JsonObject(linkedMapOf(
                        "kind" to JsonString("edit"),
                        "content" to JsonArray(listOf(JsonObject(mapOf(
                            "type" to JsonString("text"),
                            "text" to JsonString(requireNotNull(replacement)),
                        )))),
                    ))
                    com.phonedsh.ui.state.QueueAction.REMOVE -> JsonObject(mapOf("kind" to JsonString("remove")))
                    com.phonedsh.ui.state.QueueAction.STEER -> JsonObject(mapOf("kind" to JsonString("steer")))
                }
                client.call("session/updateQueue", JsonObject(mapOf("request" to JsonObject(mapOf(
                    "sessionId" to JsonString(worker),
                    "itemId" to JsonString(itemId),
                    "action" to actionValue,
                )))))
                val projections = client.call("session/projections", JsonObject(mapOf(
                    "request" to JsonObject(mapOf("sessionId" to JsonString(worker))),
                )))
                val rows = sessionSnapshotOf(projections, null, null, null, sessionId = worker).queueItems
                    ?: error("inbox projection unavailable")
                val updated = rows.firstOrNull { (it.id as? com.phonedsh.ui.state.QueueItemId.Durable)?.value == itemId }
                val confirmed = when (action) {
                    com.phonedsh.ui.state.QueueAction.QUEUE -> false
                    com.phonedsh.ui.state.QueueAction.EDIT -> updated?.text == replacement
                    com.phonedsh.ui.state.QueueAction.REMOVE -> updated == null
                    com.phonedsh.ui.state.QueueAction.STEER -> updated == null || updated.lane != item.lane
                }
                check(confirmed) { "queue action was not reflected by the refreshed inbox" }
                rows
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            mutableChat.update { live ->
                if (live.chatId != appChatId || live.workerSessionId != worker) return@update live
                val rows = outcome.getOrNull()
                if (rows != null) {
                    val successReceipt = com.phonedsh.ui.state.QueueActionReceipt(
                        requestId = requestId,
                        action = action,
                        outcome = com.phonedsh.ui.state.CommandOutcome.SUCCEEDED,
                        sessionId = worker,
                        itemId = itemId,
                    )
                    live.copy(sessionSnapshot = live.sessionSnapshot.copy(
                        queueItems = when (action) {
                            com.phonedsh.ui.state.QueueAction.QUEUE -> rows
                            com.phonedsh.ui.state.QueueAction.REMOVE -> rows
                            else -> rows.map { row ->
                                if ((row.id as? com.phonedsh.ui.state.QueueItemId.Durable)?.value == itemId) {
                                    row.copy(busy = false, actionRequestId = requestId, receipt = successReceipt)
                                } else row
                            }
                        },
                    ))
                } else {
                    val failure = outcome.exceptionOrNull()
                    val failureReceipt = com.phonedsh.ui.state.QueueActionReceipt(
                        requestId = requestId,
                        action = action,
                        outcome = com.phonedsh.ui.state.CommandOutcome.FAILED,
                        message = failure?.message?.substringBefore('\n')?.take(180) ?: "队列操作未确认",
                        sessionId = worker,
                        itemId = itemId,
                    )
                    live.copy(sessionSnapshot = live.sessionSnapshot.copy(
                        queueItems = activeItems.map { row ->
                            if ((row.id as? com.phonedsh.ui.state.QueueItemId.Durable)?.value == itemId) {
                                row.copy(busy = false, actionRequestId = requestId, receipt = failureReceipt)
                            } else row
                        },
                    ))
                }
            }
        }
        return true
    }

    internal fun runChatCommand(line: String, showResult: Boolean = true) {
        val current = mutableChat.value
        val worker = current.workerSessionId ?: return
        turnScope.launch(Dispatchers.IO) {
            try {
                val response = DshTurnClient(host.channel, ::logTurn).call("commands/execute", JsonObject(mapOf(
                    "agentId" to JsonString(worker), "line" to JsonString(line),
                    "submittedAttachments" to com.phonedsh.agentcore.JsonArray(emptyList()),
                )))
                val result = (response as? JsonObject)?.get("result") as? JsonObject
                val kind = (result?.get("kind") as? JsonString)?.value
                if (kind == null || kind == "error") throw IllegalStateException("command not accepted")
                mutableChat.update { if (it.chatId != current.chatId) it else it.copy(
                    draft = if (it.draft.trim() == line) "" else it.draft,
                    notice = (result["text"] as? JsonString)?.value,
                    commandExecution = if (!showResult) it.commandExecution else com.phonedsh.ui.page.ChatCommandExecution(
                        id = (response["commandId"] as? JsonString)?.value ?: java.util.UUID.randomUUID().toString(),
                        name = line.removePrefix("/").substringBefore(' '), line = line, kind = kind,
                        text = (result["text"] as? JsonString)?.value,
                    ),
                ) }
                refreshChatSession()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                mutableChat.update { if (it.chatId != current.chatId) it else it.copy(
                    notice = "命令没有执行成功，请重试。",
                ) }
            }
        }
    }

    internal fun selectChatAccessMode(id: String) {
        if (mutableChat.value.sessionSnapshot.accessModes.none { it.id == id }) return
        runChatCommand("/permission $id", showResult = false)
    }

    internal fun selectChatModel(model: String, effort: String?) {
        val current = mutableChat.value
        val worker = current.workerSessionId ?: return
        val route = current.sessionSnapshot.modelRoutes.firstOrNull {
            it.model == model && it.provider == current.sessionSnapshot.currentProvider
        } ?: current.sessionSnapshot.modelRoutes.firstOrNull { it.model == model } ?: return
        val keptEffort = effort ?: current.sessionSnapshot.currentEffort?.takeIf { existing ->
            model != current.sessionSnapshot.currentModel && route.efforts.any { it.id == existing }
        }
        if (keptEffort != null && route.efforts.none { it.id == keptEffort }) return
        turnScope.launch(Dispatchers.IO) {
            try {
                val request = linkedMapOf<String, JsonValue>("sessionId" to JsonString(worker),
                    "provider" to JsonString(route.provider), "model" to JsonString(route.model))
                keptEffort?.let { request["reasoningEffort"] = JsonString(it) }
                DshTurnClient(host.channel, ::logTurn).call("session/selectModel", JsonObject(mapOf("request" to JsonObject(request))))
                refreshChatSession()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                mutableChat.update { if (it.chatId != current.chatId) it else it.copy(notice = "模型没有切换成功，请重试。") }
            }
        }
    }

    /** 新建会话: a real record the session list shows immediately. */
    internal fun newChatSession(): String {
        val at = clock.nowMillis()
        val id = "chat-$at-${++chatOrdinal}"
        // A new conversation is a new session: it must not inherit the previous
        // one's 本 session 产物 attribution.
        sessionArtifacts.clear()
        chatIo.launch { chatStore.save(ChatRecord(id, "", at, emptyList(), emptyList())) }
        mutableSessions.update { snapshot ->
            snapshot.copy(
                waiting = false,
                currentSessionID = id,
                sessions = mergeSessions(emptyList(), snapshot.sessions + HostSessionRecord(id, at, workspaceID = "/dsh/workspace")),
            )
        }
        mutableChat.value = ChatUiState(chatId = id, updatedAtMs = at)
        prepareChatSession()
        LogcatHostLog.info("chat: new session $id")
        return id
    }

    /** Create a conversation only after its durable record has been read back. */
    internal suspend fun newChatSessionConfirmed(
        requestId: com.phonedsh.ui.state.StateRequestId,
        workspaceGroupId: String? = ChatSessionStore.DEFAULT_WORKSPACE_GROUP_ID,
    ):
        com.phonedsh.ui.nav.PdshActionReceipt {
        val at = clock.nowMillis()
        val id = "chat-$at-${++chatOrdinal}"
        val receipt = withContext(Dispatchers.IO) {
            createHostChatSession(chatStore, id, at, workspaceGroupId, requestId)
        }
        if (!receipt.confirms(requestId, id, null)) return receipt
        sessionArtifacts.clear()
        mutableSessions.update { snapshot ->
            snapshot.copy(
                waiting = false,
                currentSessionID = id,
                sessions = mergeSessions(emptyList(), snapshot.sessions + HostSessionRecord(id, at, workspaceID = "/dsh/workspace")),
            )
        }
        mutableChat.value = ChatUiState(chatId = id, updatedAtMs = at)
        prepareChatSession()
        LogcatHostLog.info("chat: new session confirmed $id")
        return receipt
    }

    /**
     * Switches to [chatId], painting its stored transcript.
     *
     * A row that the journal knows but this App never opened (a session the Worker
     * ran before the chat lane existed) is adopted as an empty conversation with
     * that Worker session id, so selecting it still opens a chat rather than
     * nothing.
     */
    internal fun openChatSession(chatId: String) {
        if (mutableChat.value.running) return
        if (mutableChat.value.chatId == chatId) return
        turnScope.launch {
            val stored = withContext(Dispatchers.IO) { runCatching { chatStore.load(chatId) }.getOrNull() }
            val known = mutableSessions.value.sessions.any { it.id == chatId }
            val at = clock.nowMillis()
            val record = stored ?: ChatRecord(
                id = chatId,
                title = "",
                updatedAtMs = at,
                // A journal id IS a Worker session id, so adopting it keeps the
                // conversation continuable instead of starting a second one.
                workerSessionIds = if (stored == null && known) listOf(chatId) else emptyList(),
                nodes = emptyList(),
            )
            if (stored == null) chatIo.launch { chatStore.save(record) }
            chatWorkerClaims = chatWorkerClaims + record.workerSessionIds
            mutableChat.value = record.toUiState()
            refreshChatSession()
            mutableSessions.update { it.copy(currentSessionID = chatId) }
            LogcatHostLog.info(
                "chat: opened session=$chatId rows=${record.nodes.size} " +
                    "worker=${record.workerSessionId ?: "-"}",
            )
        }
    }

    /** 重命名: writes the title to the record; `true` when it really landed there. */
    internal fun renameChatSession(chatId: String, title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || chatId.isEmpty()) return false
        val at = clock.nowMillis()
        if (mutableChat.value.chatId == chatId) {
            mutableChat.update { it.copy(title = trimmed, updatedAtMs = at) }
        }
        chatIo.launch {
            val existing = chatStore.load(chatId)
            val nodes = if (mutableChat.value.chatId == chatId) {
                mutableChat.value.nodes
            } else {
                existing?.nodes.orEmpty()
            }
            val workers = (existing?.workerSessionIds.orEmpty() +
                listOfNotNull(mutableChat.value.takeIf { it.chatId == chatId }?.workerSessionId))
                .distinct()
            chatStore.save(ChatRecord(chatId, trimmed, at, workers, nodes,
                archived = existing?.archived ?: false, pinned = existing?.pinned ?: false,
                workspaceID = existing?.workspaceID ?: "/dsh/workspace"))
        }
        mutableSessions.update { snapshot ->
            snapshot.copy(
                sessions = snapshot.sessions.map {
                    if (it.id == chatId) it.copy(title = trimmed, lastActivityMs = at) else it
                }.sortedByDescending { it.lastActivityMs ?: Long.MIN_VALUE },
            )
        }
        LogcatHostLog.info("chat: renamed session=$chatId title_chars=${trimmed.length}")
        return true
    }

    /** The page-head 重命名 of the open conversation. */
    internal fun renameCurrentChat(title: String): Boolean {
        val chatId = mutableChat.value.chatId
        if (chatId.isEmpty()) return false
        return renameChatSession(chatId, title)
    }

    /** Removes one pending import. */
    internal fun removeChatAttachment(index: Int) {
        mutableChat.update { current ->
            if (index !in current.attachments.indices) {
                current
            } else {
                current.copy(attachments = current.attachments.filterIndexed { i, _ -> i != index })
            }
        }
    }

    /** Closes the import status line and the send-failure banner. */
    internal fun dismissChatNotice() {
        mutableChat.update { it.copy(notice = null, error = null, attachmentError = null, bannerDismissed = true) }
    }

    /**
     * 添加文件/图片: copies the picked document into the workspace.
     *
     * The bytes go through the WAL's mutation lane (`WorkspaceLog.appendMutation`),
     * not into the App's private directory: the workspace is what the files page
     * lists, what the model's host-exec tools mount at `/dsh/workspace`, and what the
     * next boot's overlay is packed from. A copy beside the stores would be a second
     * workspace nothing reads.
     */
    internal fun importIntoWorkspace(
        uri: Uri,
        image: Boolean,
        attachToChat: Boolean = true,
        expectedChatId: String? = null,
        onCompleted: ((Boolean) -> Unit)? = null,
    ) {
        val targetChatId = if (attachToChat) expectedChatId ?: mutableChat.value.chatId else null
        turnScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    importLocked(uri, image)
                }
            }
            outcome.fold(
                onSuccess = { imported ->
                    if (attachToChat) {
                        mutableChat.update { current ->
                            if (current.chatId != targetChatId) current else current.copy(
                                    attachments = current.attachments + imported.attachment,
                                    notice = "已写入工作区：${imported.attachment.name}（${imported.bytes} 字节）",
                                    attachmentError = null,
                                    // No restart happens here (see the note above `workspaceFileName`),
                                    // and a stored id would only be a guess; the next send opens a
                                    // fresh Worker session.
                                )
                        }
                    }
                    LogcatHostLog.info(
                        "CHAT_IMPORT path=${imported.attachment.path} bytes=${imported.bytes} " +
                            "image=$image",
                    )
                    onCompleted?.invoke(true)
                },
                onFailure = { failure ->
                    val reason = when (failure) {
                        is IllegalArgumentException -> failure.message ?: "无法读取所选文件"
                        is Exception -> reasonOf(failure)
                        else -> failure.javaClass.simpleName
                    }
                    if (attachToChat) mutableChat.update { current ->
                        if (current.chatId != targetChatId) current else current.copy(attachmentError = "导入失败：$reason")
                    }
                    LogcatHostLog.warn("CHAT_IMPORT_FAILED reason=$reason")
                    onCompleted?.invoke(false)
                },
            )
        }
    }

    private class Imported(val attachment: ChatAttachment, val bytes: Int)

    private fun importLocked(uri: Uri, image: Boolean): Imported {
        val resolver = getApplication<Application>().contentResolver
        val declared = resolver.getType(uri)
        var displayName: String? = null
        var declaredBytes: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredBytes = cursor.getLong(sizeIndex)
                    }
                }
        }
        val limit = deviceBudgets.singleFileBytes
        declaredBytes?.let {
            require(it <= limit) { "文件超过本机单文件上限（$limit 字节）" }
        }
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                require(total <= limit) { "文件超过本机单文件上限（$limit 字节）" }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: throw IllegalArgumentException("无法打开所选文件")
        require(bytes.isNotEmpty()) { "所选文件是空的" }
        val safeName = workspaceFileName(displayName ?: "attachment", image)
        val vfsPath = "/dsh/workspace/$safeName"
        kotlinx.coroutines.runBlocking {
            workspaceLog.appendMutation(
                MutationBody(
                    op = VfsOp.WRITE,
                    mode = FILE_MODE,
                    path = vfsPath,
                    data = bytes,
                ),
            )
        }
        return Imported(
            attachment = ChatAttachment(
                name = safeName,
                mimeType = declared,
                image = image || declared?.startsWith("image/") == true,
                path = vfsPath,
            ),
            bytes = bytes.size,
        )
    }

    /**
     * Nothing is restarted here, and that is a measured decision.
     *
     * `WorkspaceLog.appendMutation` is the VFS mutation lane: the record is
     * durable and materialized, which is what makes the file part of the
     * workspace rather than a copy beside the stores. It has one host-side
     * consequence this App cannot repair: the `VfsSink` numbers the Worker's
     * mutations from its own counter, seeded once from `WorkspaceLog.recover()`,
     * and `recover()` caches its outcome — so an App-side append makes the log one
     * mseq ahead of the sink, and the Worker's first later `VFS_MUT` closes the
     * channel with `WAL allocated mseq N+1 for the worker's N` (measured on this
     * lane, API 35). Restarting the host does not reseed that counter (the cached
     * recovery is reused), so this class deliberately does not try; the durable
     * fix belongs in `:dsh` (`VfsSink` should reseed from the log, or
     * `WorkspaceLog.recover()` should report the live watermark).
     *
     * This note is documentation of the constraint, not a plan: nothing in this
     * class restarts the host, because a restart cannot reseed the counter.
     */
    /** A workspace file name that cannot escape `/dsh/workspace` and does not collide. */
    private fun workspaceFileName(raw: String, image: Boolean): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(FILE_NAME_DISALLOWED, "_").ifBlank { "attachment" }
        val withExtension = if (cleaned.contains('.')) {
            cleaned
        } else {
            cleaned + if (image) ".png" else ".txt"
        }
        var candidate = withExtension
        var suffix = 1
        val directory = walPaths.materializedWorkspace
        while (Files.exists(directory.resolve(candidate)) ||
            mutableChat.value.attachments.any { it.name == candidate }
        ) {
            val dot = withExtension.lastIndexOf('.')
            candidate = if (dot > 0) {
                "${withExtension.substring(0, dot)}-$suffix${withExtension.substring(dot)}"
            } else {
                "$withExtension-$suffix"
            }
            suffix++
        }
        return candidate
    }

    /**
     * Sends the draft (and every pending import) as one turn.
     *
     * The conversation is created on demand: 新建会话 writes the record, and the
     * Worker's `session/create` happens here, on the first message that needs it.
     */
    internal fun sendChat() {
        val current = mutableChat.value
        if (current.running) {
            val mode = when (preferenceStore.snapshot().busyEnterMode) {
                BusyEnterMode.QUEUE -> com.phonedsh.ui.state.EnterMode.QUEUE
                BusyEnterMode.STEER -> com.phonedsh.ui.state.EnterMode.STEER
            }
            submitWhileRunning(
                current.workerSessionId.orEmpty(),
                com.phonedsh.ui.state.StateRequestId("ui.prompt." + java.util.UUID.randomUUID().toString()),
                current.draft,
                mode,
            )
            return
        }
        submitChat(retryOriginalSubmission = false, retryRequestId = null)
    }

    /** Queue or steer a new user submission to the exact live Worker session. */
    internal fun submitWhileRunning(
        workerSessionId: String,
        requestId: com.phonedsh.ui.state.StateRequestId,
        text: String,
        mode: com.phonedsh.ui.state.EnterMode,
    ): Boolean {
        val current = mutableChat.value
        val trimmed = text.trim()
        if (workerSessionId.isBlank() || current.workerSessionId != workerSessionId || !current.running ||
            trimmed.isEmpty() || (mode == com.phonedsh.ui.state.EnterMode.STEER && current.attachments.isNotEmpty())
        ) return false
        // A retry of an ambiguous busy submission reuses its own backend prompt
        // identity. Matching by text would conflate duplicate messages.
        val promptRequestId = current.retryBusyPromptRequestId
            ?: com.phonedsh.ui.state.StateRequestId("pdsh-prompt-" + java.util.UUID.randomUUID().toString())
        val existing = current.localQueueItems.firstOrNull { it.promptRequestId == promptRequestId }
        val localId = existing?.id ?: com.phonedsh.ui.state.QueueItemId.Local(promptRequestId.value)
        val localItemId = (localId as? com.phonedsh.ui.state.QueueItemId.Local)?.value ?: return false
        val queueAction = if (mode == com.phonedsh.ui.state.EnterMode.STEER)
            com.phonedsh.ui.state.QueueAction.STEER else com.phonedsh.ui.state.QueueAction.QUEUE
        val localItem = com.phonedsh.ui.state.ManagedQueueItemState(
            id = localId,
            text = trimmed,
            sessionId = workerSessionId,
            lane = if (mode == com.phonedsh.ui.state.EnterMode.STEER) "steered" else "next-turn",
            state = if (mode == com.phonedsh.ui.state.EnterMode.STEER)
                com.phonedsh.ui.state.QueueItemStatus.STEERED else com.phonedsh.ui.state.QueueItemStatus.SENDING,
            canEdit = false,
            canRemove = false,
            canSteer = false,
            busy = true,
            actionRequestId = requestId,
            promptRequestId = promptRequestId,
        )
        val attachments = current.attachments
        val prompt = composeChatPrompt(trimmed, attachments)
        mutableChat.update { live ->
            if (live.chatId != current.chatId || live.workerSessionId != workerSessionId || !live.running) live else {
                live.copy(
                    localQueueItems = live.localQueueItems.filterNot { it.id == localId } + localItem,
                    retryBusyPromptRequestId = null,
                )
            }
        }
        turnScope.launch(Dispatchers.IO) {
            try {
                val client = DshTurnClient(host.channel, ::logTurn)
                suspend fun readQueueProjection(): List<com.phonedsh.ui.state.ManagedQueueItemState> =
                    sessionSnapshotOf(
                        client.call("session/projections", JsonObject(mapOf(
                            "request" to JsonObject(mapOf("sessionId" to JsonString(workerSessionId))),
                        ))),
                        catalog = null,
                        commandCatalog = null,
                        sessionId = workerSessionId,
                    ).queueItems ?: error("inbox projection unavailable")

                client.prompt(
                    sessionId = workerSessionId,
                    text = prompt,
                    requestId = promptRequestId.value,
                    mode = if (mode == com.phonedsh.ui.state.EnterMode.STEER) "steer" else "queue",
                )
                val after = readQueueProjection()
                // The successful prompt RPC acknowledges this exact request ID.
                // A fresh projection may already be empty because the worker
                // consumed it; do not infer its identity from text or row counts.

                mutableChat.update { live ->
                    if (live.chatId != current.chatId || live.workerSessionId != workerSessionId) live else {
                        live.copy(
                            draft = if (live.draft.trim() == trimmed) "" else live.draft,
                            attachments = if (live.attachments == attachments) emptyList() else live.attachments,
                            lastSubmission = trimmed,
                            lastAttachments = attachments,
                            localQueueItems = com.phonedsh.ui.state.retireAcceptedQueuePrompt(
                                live.localQueueItems,
                                promptRequestId,
                            ),
                            retryBusyPromptRequestId = null,
                            sessionSnapshot = live.sessionSnapshot.copy(queueItems = after),
                        )
                    }
                }
                refreshChatSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableChat.update { live ->
                    if (live.chatId != current.chatId || live.workerSessionId != workerSessionId) live else live.copy(
                        localQueueItems = live.localQueueItems.map { item ->
                            if (item.id == localId && item.actionRequestId == requestId) item.copy(
                                busy = false,
                                receipt = com.phonedsh.ui.state.QueueActionReceipt(
                                    requestId = requestId,
                                    action = queueAction,
                                    outcome = com.phonedsh.ui.state.CommandOutcome.FAILED,
                                    message = failure.message?.substringBefore('\n')?.take(180) ?: "请求结果未确认",
                                    sessionId = workerSessionId,
                                    itemId = localItemId,
                                ),
                            ) else item
                        },
                        retryBusyPromptRequestId = promptRequestId.takeIf {
                            live.draft.trim() == trimmed && live.attachments == attachments
                        },
                    )
                }
            }
        }
        return true
    }

    /** Retry the original failed input in its existing session without adding a second user row. */
    internal fun retryFailedChat(sessionId: String, requestId: com.phonedsh.ui.state.StateRequestId): Boolean {
        val current = mutableChat.value
        if (sessionId.isBlank() || current.chatId != sessionId || current.running || current.error == null ||
            (current.lastSubmission.isBlank() && current.lastAttachments.isEmpty()) ||
            current.lastPromptRequestId.isNullOrBlank() ||
            state.value !is EngineHostState.Running || turnRunning.get() || activeRetryRequestId != null
        ) return false
        activeRetryRequestId = requestId.value
        mutableChat.update {
            if (it.chatId != sessionId) it else it.copy(
                draft = it.lastSubmission,
                attachments = it.lastAttachments,
            )
        }
        submitChat(retryOriginalSubmission = true, retryRequestId = requestId.value)
        return true
    }

    private fun submitChat(retryOriginalSubmission: Boolean, retryRequestId: String?) {
        val current = mutableChat.value
        if (current.running) return
        val typed = current.draft.trim()
        val attachments = current.attachments
        if (typed.isEmpty() && attachments.isEmpty()) return
        val promptRequestId = if (retryOriginalSubmission) {
            current.lastPromptRequestId ?: return
        } else {
            "pdsh-prompt-${java.util.UUID.randomUUID()}"
        }
        val commandName = typed.removePrefix("/").substringBefore(' ')
        if (typed.startsWith('/') && attachments.isEmpty() &&
            current.sessionSnapshot.commands.any { it.name == commandName }) {
            runChatCommand(typed)
            return
        }
        if (state.value !is EngineHostState.Running || host.state.value == com.phonedsh.controller.HostState.FAILED) {
            if (nativeEngineLifecycle().canStartBySending && sendStartupInFlight.compareAndSet(false, true)) {
                turnScope.launch {
                    try {
                        start()
                        withTimeout(ENGINE_READY_TIMEOUT_MILLIS) {
                            host.ready.first { it }
                            engine.state.first { it is EngineHostState.Running }
                        }
                        sendStartupInFlight.set(false)
                        if (mutableChat.value.draft == current.draft) submitChat(retryOriginalSubmission, retryRequestId)
                        else mutableChat.update { it.copy(notice = "引擎已就绪，点发送继续。") }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) {
                        mutableChat.update { it.copy(error = "引擎没有启动成功，输入已保留。", bannerDismissed = false) }
                    } finally { sendStartupInFlight.set(false) }
                }
                return
            }
            mutableChat.update {
                it.copy(error = "本地引擎还没就绪，消息没有发出", bannerDismissed = false)
            }
            return
        }
        if (!turnRunning.compareAndSet(false, true)) {
            mutableChat.update {
                it.copy(error = "上一个回合还在运行，这条消息没有发出", bannerDismissed = false)
            }
            return
        }
        // `newChatSession` replaces the state, so it runs before the send snapshot
        // is taken again.
        val chatId = current.chatId.ifEmpty { newChatSession() }
        val sending = mutableChat.value
        val prompt = composeChatPrompt(typed, attachments)
        val ordinal = ++chatOrdinal
        // Every send gets its own turn-id prefix: a replacement Worker session
        // numbers its turns from 0001 again.
        // Wall-clock prefix, not just the process-local ordinal: the node ids of a
        // previous process must never collide with this one's, or the transcript's
        // duplicate-id guard would drop a legitimate new row (measured: the answer
        // of the newest turn was not drawn while an older turn's row was).
        val turnToken = "t${clock.nowMillis()}-$ordinal"
        val userNode = userNodeOf(
            chatId,
            turnToken,
            typed.ifEmpty { attachments.joinToString(", ") { it.name } },
            ordinal,
        )
        val at = clock.nowMillis()
        mutableChat.update {
            it.copy(
                chatId = chatId,
                nodes = if (retryOriginalSubmission) it.nodes else it.nodes + userNode,
                draft = "",
                lastSubmission = if (retryOriginalSubmission) current.lastSubmission else current.draft,
                lastAttachments = if (retryOriginalSubmission) current.lastAttachments else attachments,
                lastPromptRequestId = promptRequestId,
                attachments = emptyList(),
                running = true,
                streaming = "",
                reasoningStreaming = "",
                error = null,
                bannerDismissed = false,
                notice = null,
                attachmentError = null,
                activeTurnNodeId = null,
                turnToken = turnToken,
                updatedAtMs = at,
            )
        }
        persistChat(chatId)
        LogcatHostLog.info(
            "APP_CHAT_SEND session=$chatId chars=${prompt.length} " +
                "attachments=${attachments.size} worker=${sending.workerSessionId ?: "-"}",
        )
        chatTurnJob = turnScope.launch(Dispatchers.IO) {
            var outcome: ChatTurnOutcome? = null
            // The 本 session 产物 fact's baseline: every workspace file the person
            // already had is *not* this turn's output. Taken after the awaits and
            // before the prompt, on this IO thread (see HostSessionArtifacts).
            sessionArtifacts.beginTurn()
            try {
                withTimeout(HOST_READY_TIMEOUT_MILLIS) { host.ready.first { it } }
                withTimeout(ENGINE_READY_TIMEOUT_MILLIS) {
                    engine.state.first { it is EngineHostState.Running }
                }
                val runner = ChatTurnRunner(host.channel, DshTurnClient(host.channel, ::logTurn), ::logTurn)
                chatRunner = runner
                chatPreparationJob?.join()
                outcome = runner.run(
                    // The conversation's own Worker session, persisted in the chat
                    // record. It survives a Worker page reload and a cold start:
                    // `session/prompt` resumes it from the Worker's JSONL log
                    // (`/dsh/home/sessions`, repacked into every boot overlay), so
                    // the model keeps the earlier turns instead of starting blind.
                    workerSessionId = mutableChat.value.takeIf { it.chatId == chatId }?.workerSessionId,
                    prompt = prompt,
                    promptRequestId = promptRequestId,
                    onSession = { workerSessionId -> claimWorkerSession(chatId, workerSessionId) },
                    onEvent = { event -> onChatEvent(chatId, event) },
                    onDelta = { text ->
                        mutableChat.update { live ->
                            if (live.chatId == chatId) live.copy(streaming = live.streaming + text) else live
                        }
                    },
                    onContextLost = { message -> recordContextLost(chatId, message) },
                    onInteractiveRequest = { request, responder -> receiveChatRequest(chatId, request, responder) },
                    onInteractiveCancel = ::removeChatRequest,
                    onReasoningDelta = { text -> mutableChat.update { live ->
                        if (live.chatId != chatId) live else live.copy(reasoningStreaming = live.reasoningStreaming + text)
                    } },
                )
                finishChatTurn(chatId, outcome)
            } catch (timeout: TimeoutCancellationException) {
                failChatTurn(chatId, "timeout")
            } catch (cancelled: CancellationException) {
                // 停止: the job was cancelled from the main thread. The three lines
                // below do not suspend, so they still run before the coroutine dies.
                markChatTurnStopped(chatId)
                throw cancelled
            } catch (failure: Exception) {
                failChatTurn(chatId, reasonOf(failure))
            } finally {
                chatRunner = null
                if (activeRetryRequestId == retryRequestId) activeRetryRequestId = null
                chatSnapshotJob?.cancel()
                chatResponders.clear()
                answeringRequests.clear()
                turnRunning.set(false)
                // Close the measurement even on a failed or stopped turn: a file a
                // tool really wrote is on disk whether or not the answer settled, and
                // the page must list it rather than losing it with the error path.
                sessionArtifacts.endTurn()
                mutableChat.update {
                    it.copy(running = false, streaming = "", reasoningStreaming = "", activeTurnNodeId = null, pendingNodes = emptyList())
                }
                refreshChatSession()
                persistChat(chatId)
            }
        }
    }

    /**
     * 停止: tells the Worker to cancel, then stops reading its stream here.
     *
     * The cancel receipt is what makes the Worker's own turn end `interrupted`
     * instead of leaving it generating for a phone that stopped listening. It is
     * sent from a scope that outlives the cancelled turn job.
     */
    internal fun stopChat() {
        val current = mutableChat.value
        if (!current.running) return
        val runner = chatRunner
        val workerSessionId = current.workerSessionId
        LogcatHostLog.info("APP_CHAT_STOP session=${current.chatId}")
        chatTurnJob?.cancel()
        chatTurnJob = null
        if (runner != null && workerSessionId != null) {
            turnScope.launch {
                val failure = runner.cancel(workerSessionId)
                if (failure != null) {
                    LogcatHostLog.warn("APP_CHAT_STOP cancel not accepted ($failure)")
                }
            }
        }
        // The cancelled job's own branch records the stop; this line is the state
        // the user sees even if the job had already finished.
        mutableChat.update { it.copy(running = false, streaming = "", activeTurnNodeId = null) }
    }

    /** Appends the node(s) one Worker event produces. */
    private fun onChatEvent(chatId: String, event: HostEvent) {
        // The 本 session 产物 fact's corroborating signal (a named write tool's path).
        // The bytes are the evidence; this only catches a same-size rewrite.
        sessionArtifacts.observe(event)
        when (event.kind) {
            HostEventKind.TURN_STARTED -> mutableChat.update { current ->
                if (current.chatId != chatId) {
                    current
                } else {
                    val id = current.turnIdOf(event.turnId)
                    val turn = turnNode(chatId, id).copy(props = JsonObject(mapOf(
                        "startedAtMs" to (event.raw["time"] ?: com.phonedsh.agentcore.JsonNull),
                    )))
                    current.copy(activeTurnNodeId = id,
                        nodes = current.nodes.filterNot { it.nodeID == turn.nodeID } + turn)
                }
            }

            else -> {
                val current = mutableChat.value
                if (current.chatId != chatId) return
                val node = chatNodeOf(chatId, current.turnToken, event, ++chatOrdinal) ?: return
                mutableChat.update { live ->
                    if (live.chatId != chatId || live.nodes.any { it.nodeID == node.nodeID }) {
                        live
                    } else {
                        live.copy(nodes = live.nodes + node,
                            reasoningStreaming = if (event.kind == HostEventKind.ASSISTANT_MESSAGE) "" else live.reasoningStreaming)
                    }
                }
            }
        }
    }

    /**
     * Records the Worker session a turn opened, so the journal row belongs to this
     * conversation and the list does not show it twice.
     */
    private fun claimWorkerSession(chatId: String, workerSessionId: String) {
        chatWorkerClaims = chatWorkerClaims + workerSessionId
        mutableChat.update {
            if (it.chatId == chatId) it.copy(workerSessionId = workerSessionId) else it
        }
        refreshChatSession()
        chatSnapshotJob?.cancel()
        chatSnapshotJob = turnScope.launch {
            while (mutableChat.value.chatId == chatId && mutableChat.value.running) {
                delay(2_000)
                refreshChatSession(full = false)
            }
        }
        persistChat(chatId)
        mutableSessions.update { snapshot ->
            snapshot.copy(sessions = mergeSessions(emptyList(), snapshot.sessions))
        }
        LogcatHostLog.info("chat: session=$chatId claimed worker=$workerSessionId")
    }

    private fun finishChatTurn(chatId: String, outcome: ChatTurnOutcome) {
        val at = clock.nowMillis()
        val kinds = outcome.events.joinToString(",") { it.kind.wire }
        if (outcome.completed) {
            LogcatHostLog.info(
                "APP_CHAT_TURN_OK session=$chatId worker=${mutableChat.value.workerSessionId ?: "-"} " +
                    "kinds=$kinds reason=completed streamed=${outcome.liveText.length} chars " +
                    "text=${outcome.assistantText.length} chars context_reset=${outcome.contextReset}",
            )
        } else if (outcome.endReason == null) {
            failChatTurn(chatId, "the turn ended without a turn_ended edge (kinds=$kinds)")
            return
        } else {
            // interrupted / aborted / blocked: the row says so, and the turn is not
            // reported as a failure the person has to dismiss.
            LogcatHostLog.info(
                "APP_CHAT_TURN_ENDED session=$chatId kinds=$kinds reason=${outcome.endReason}",
            )
        }
        mutableChat.update { it.copy(updatedAtMs = at, activeTurnNodeId = null) }
        mutableSessions.update { snapshot ->
            snapshot.copy(
                currentSessionID = chatId,
                sessions = mergeSessions(emptyList(), snapshot.sessions),
            )
        }
        persistChat(chatId)
        logJournalEvidence(chatId)
    }

    private fun markChatTurnStopped(chatId: String) {
        val at = clock.nowMillis()
        val ordinal = ++chatOrdinal
        val turnID = mutableChat.value.turnIdOf("stopped")
        val node = UiStateNode(
            kind = com.phonedsh.ui.projection.UiStateKind.STATUS,
            nodeID = "session/$chatId/turn/$turnID/event/0/turn-end",
            parentID = "session/$chatId",
            sessionID = chatId,
            turnID = turnID,
            seq = 0,
            text = "cancelled",
            props = com.phonedsh.agentcore.JsonObject(
                linkedMapOf(
                    "reason" to com.phonedsh.agentcore.JsonString("cancelled"),
                    "detail" to com.phonedsh.agentcore.JsonString(""),
                ),
            ),
        )
        mutableChat.update {
            it.copy(
                running = false,
                streaming = "",
                activeTurnNodeId = null,
                nodes = it.nodes + node,
                updatedAtMs = at,
            )
        }
        LogcatHostLog.info("APP_CHAT_TURN_STOPPED session=$chatId")
    }

    private fun failChatTurn(chatId: String, reason: String) {
        val safe = reason.substringBefore('\n').take(240)
        LogcatHostLog.error("APP_CHAT_TURN_FAILED session=$chatId reason=$safe")
        mutableChat.update {
            it.copy(
                running = false,
                streaming = "",
                activeTurnNodeId = null,
                error = "消息发送失败（$safe）",
                bannerDismissed = false,
                draft = it.draft.ifEmpty { it.lastSubmission },
                attachments = if (it.attachments.isEmpty()) it.lastAttachments else it.attachments,
            )
        }
        persistChat(chatId)
    }

    /**
     * Tells the person that this conversation lost the model's earlier context.
     *
     * The send itself can still succeed — a new Worker session is the only way to
     * send at all once the old one is gone — but the transcript now shows bubbles
     * the model can no longer see. This writes that fact **into the transcript**
     * (a durable `status` row, so reopening the chat cannot present the earlier
     * messages as shared context) and raises the transient banner so it is visible
     * the moment it happens. Never a log-only path: `budgets.md` §5 / the lane's
     * rule is that the UI record and the model context must not disagree silently.
     */
    private fun recordContextLost(chatId: String, message: String) {
        val at = clock.nowMillis()
        val scopeTurn = mutableChat.value.turnToken.ifEmpty { "t$at" }
        val node = contextLostNode(chatId, scopeTurn, message)
        mutableChat.update {
            if (it.chatId != chatId) {
                it
            } else {
                it.copy(
                    nodes = it.nodes + node,
                    error = message,
                    bannerDismissed = false,
                    updatedAtMs = at,
                )
            }
        }
        persistChat(chatId)
        LogcatHostLog.warn("APP_CHAT_CONTEXT_LOST session=$chatId")
    }

    /**
     * Prints whether the journal really holds the turn's Worker session.
     *
     * This is the evidence line for "a session that exists", read from the store
     * itself (`JournalStore.recover` is public and idempotent) rather than assumed
     * from a successful reply.
     */
    private fun logJournalEvidence(chatId: String) {
        val workerSessionId = mutableChat.value.workerSessionId ?: return
        try {
            val recovery = kotlinx.coroutines.runBlocking { journalStore.recover() }
            val highest = recovery.highestJseq[workerSessionId]
            LogcatHostLog.info(
                "CHAT_JOURNAL_SESSION present=${highest != null} jseq=${highest ?: "-"} " +
                    "chat=$chatId worker=$workerSessionId sessions=${recovery.highestJseq.size}",
            )
        } catch (failure: Exception) {
            LogcatHostLog.warn("CHAT_JOURNAL_SESSION present=unknown reason=${reasonOf(failure)}")
        }
    }

    /**
     * Copies the current conversation into the store.
     *
     * Only durable nodes are written: the streaming text is not a row, and the
     * running turn's node is transient. A later `openChatSession` paints exactly
     * what was on screen when the turn settled.
     */
    private fun persistChat(chatId: String) {
        val ui = mutableChat.value
        if (ui.chatId != chatId) return
        val nodes = ui.nodes
        val title = ui.title
        val workerSessionId = ui.workerSessionId
        val at = ui.updatedAtMs
        chatIo.launch {
            val existing = runCatching { chatStore.load(chatId) }.getOrNull()
            val workers = (existing?.workerSessionIds.orEmpty() + listOfNotNull(workerSessionId)).distinct()
            runCatching {
                chatStore.save(ChatRecord(chatId, title, at, workers, nodes,
                    archived = existing?.archived ?: false, pinned = existing?.pinned ?: false,
                    workspaceID = existing?.workspaceID ?: "/dsh/workspace"))
            }.onFailure { LogcatHostLog.warn("chat: save refused session=$chatId (${reasonOf(it as? Exception ?: Exception(it))})") }
        }
    }

    /**
     * Paints the conversations the previous process had.
     *
     * The newest record opens by default, which is the baseline's
     * `currentSessionDefaultsKey` behaviour: a cold start shows the conversation
     * the person left. Everything here is a read; a store that cannot be read
     * leaves the screen empty rather than showing a fabricated one.
     */
    private fun restoreChatSessions() {
        turnScope.launch {
            val records = withContext(Dispatchers.IO) {
                runCatching { chatStore.loadAll() }
                    .onFailure { LogcatHostLog.warn("chat: store not restored (${reasonOf(it as? Exception ?: Exception(it))})") }
                    .getOrDefault(emptyList())
            }
            if (records.isEmpty()) {
                LogcatHostLog.info("chat: no stored conversation")
                return@launch
            }
            chatWorkerClaims = records.flatMap { it.workerSessionIds }.toSet()
            applyChatRecords(records)
            mutableSessions.update { snapshot ->
                snapshot.copy(
                    waiting = false,
                    sessions = mergeSessions(
                        emptyList(),
                        snapshot.sessions + records.filterNot { it.archived }.map {
                            HostSessionRecord(it.id, it.updatedAtMs, it.title, it.workspaceID, it.pinned)
                        },
                    ),
                    currentSessionID = snapshot.currentSessionID ?: records.firstOrNull { !it.archived }?.id,
                )
            }
            val newest = records.firstOrNull { !it.archived } ?: return@launch
            if (!turnRunning.get() && mutableChat.value.chatId.isEmpty()) {
                mutableChat.value = newest.toUiState()
            }
            LogcatHostLog.info(
                "chat: restored ${records.size} conversation(s) open=${newest.id} " +
                    "rows=${newest.nodes.size} worker=${newest.workerSessionId ?: "-"}",
            )
        }
    }

    /** The prompt the Worker receives: the typed text plus the workspace paths. */
    private fun composeChatPrompt(typed: String, attachments: List<ChatAttachment>): String {
        val references = attachments.mapNotNull { it.path }
        if (references.isEmpty()) return typed
        val block = buildString {
            append("已附加的文件（工作区路径，可用工具读取）：")
            references.forEach { append('\n').append("- ").append(it) }
        }
        return listOf(typed, block).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    override fun onCleared() {
        turnScope.cancel()
        chatIo.cancel()
        host.stop()
        super.onCleared()
    }

    private fun failTurn(reason: String) {
        val safe = reason.substringBefore('\n').take(240)
        Log.e(TAG_TURN, "APP_TURN_FAILED reason=$safe")
        // A failure is an explicit end: the marker is removed so the next launch
        // does not report this turn as a process death too.
        ProbeRecovery.endTurn(getApplication<Application>())
        mutableTurn.value = AppTurnState.Failed(safe)
    }

    /**
     * One cold scan of the journal lane, with the failure kept as a page label.
     *
     * The session ids are the store's own recovery watermarks; the timestamp is
     * the file's last write, which is the last moment the host durably touched
     * that session. Nothing is inferred from a file name.
     */
    private suspend fun readJournalSessions(): SessionScan = try {
        val recovery = journalStore.recover()
        SessionScan(
            sessions = recovery.highestJseq.keys
                .map { sessionId -> HostSessionRecord(sessionId, journalLastWriteMs(sessionId)) }
                .sortedByDescending { it.lastActivityMs ?: Long.MIN_VALUE },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        SessionScan(failure = sessionFailureLabel(failure))
    }

    /**
     * The session file's last write, or `null` when it disappeared.
     *
     * A row whose file vanished between recovery and this read keeps its id and
     * shows no time, rather than a time the host did not read.
     */
    private fun journalLastWriteMs(sessionId: String): Long? = runCatching {
        Files.getLastModifiedTime(journalPaths.sessionFile(sessionId)).toMillis()
    }.getOrNull()

    /**
     * Records the session a completed turn opened.
     *
     * This is the one session the host knows without a store scan: the client
     * returned it with the completed turn. It becomes the current session (the
     * page's check mark) and a row, with the host's own completion reading as
     * its last activity.
     */
    private fun recordCompletedTurn(sessionId: String) {
        val at = clock.nowMillis()
        mutableSessions.update { snapshot ->
            snapshot.copy(
                currentSessionID = sessionId,
                sessions = mergeSessions(
                    emptyList(),
                    snapshot.sessions + HostSessionRecord(sessionId, at),
                ),
            )
        }
    }

    /**
     * Paints the last completed turn and the last session this app opened.
     *
     * The Android half of `ChatModel.restoreCachedTranscriptIfAvailable()`: a
     * cold start shows what the previous process rendered instead of 未开始, and
     * the sessions page marks the session it last opened instead of forgetting
     * it. Both come from one row, read on [Dispatchers.IO] because
     * `DomainStore`'s contract forbids a main-thread call.
     *
     * Three rules keep this honest:
     *
     *  * a store that cannot be read leaves the screen at [AppTurnState.Idle] —
     *    the cache is an optimisation, and a cache failure must never look like
     *    "the last turn is still running" or like a fabricated one;
     *  * the restore only applies while the process has not started a turn of its
     *    own ([turnRunning] false and still [AppTurnState.Idle]), so a slow restore
     *    can never overwrite a turn this process really ran;
     *  * nothing is deleted on failure. A damaged row stays for the next save to
     *    replace, because silently dropping data is what `budgets.md` §5 forbids.
     */
    private fun restoreSessionArchive() {
        turnScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    sessionArchive.currentSessionId() to sessionArchive.latest()
                }
            }
            val (sessionId, archived) = restored.getOrElse { failure ->
                // Recovery evidence, not an error path: a fresh install has no
                // row, and this line is how a reader tells "nothing was saved"
                // from "the store refused to open".
                LogcatHostLog.warn("session archive: not restored (${archiveReasonOf(failure)})")
                return@launch
            }
            if (sessionId == null) {
                LogcatHostLog.info("session archive: empty (no session recorded yet)")
                return@launch
            }
            // The current-session mark is a separate fact from the payload: a
            // session whose turn row is gone still names the session this app
            // opened, exactly as `currentSessionDefaultsKey` survives on iOS.
            //
            // Guarded by `currentSessionID == null` for the same reason the turn
            // restore is guarded by `Idle`: a turn this process completed before
            // the read landed has already marked its own session, and the stored
            // one is older by definition. Letting the restore win would move the
            // check mark backwards.
            mutableSessions.update { snapshot ->
                if (turnRunning.get() || snapshot.currentSessionID != null) {
                    snapshot
                } else {
                    snapshot.copy(
                        currentSessionID = sessionId,
                        sessions = mergeSessions(
                            emptyList(),
                            snapshot.sessions + HostSessionRecord(sessionId, archived?.completedAtMs),
                        ),
                    )
                }
            }
            if (archived == null) {
                LogcatHostLog.warn(
                    "session archive: session=$sessionId restored without a readable turn; " +
                        "the screen keeps 未开始",
                )
                return@launch
            }
            var applied = false
            mutableTurn.update { current ->
                if (turnRunning.get() || current !is AppTurnState.Idle) {
                    current
                } else {
                    applied = true
                    archived.toRestoredCompleted()
                }
            }
            if (!applied) return@launch
            LogcatHostLog.info(
                "session archive: restored session=${archived.sessionId} " +
                    "completed_at=${archived.completedAtMs} kinds=${archived.kinds.size} " +
                    "commands=${archived.commands.size} writes=${archived.writes.size} " +
                    "restored=true",
            )
        }
    }

    /**
     * Writes the turn [runTurn] just completed, so the next launch can paint it.
     *
     * Called after the UI already shows the completed turn: a store write must
     * never turn a completed turn into a failed one, so a refusal is logged and
     * swallowed. The one case worth naming is the quota
     * (`QuotaExceededException`): it means the cache is full, and the write is
     * refused rather than silently trimming older sessions — the same rule the
     * WAL and the journal follow.
     */
    private suspend fun archiveCompletedTurn(completed: AppTurnState.Completed, atMillis: Long) {
        val turn = ArchivedTurn(
            sessionId = completed.sessionId,
            completedAtMs = atMillis,
            // The iOS baseline keeps the settled tail (`suffix(400)`); the same
            // bound here means a restored screen holds the same tail on both
            // platforms. `kinds` is the projection the screen already drew.
            kinds = completed.kinds.takeLast(SessionArchive.CAPACITY),
            commands = completed.commands
                .takeLast(SessionArchive.CAPACITY)
                .map { ArchivedCommand(it.id, it.command) },
            writes = completed.writes
                .takeLast(SessionArchive.CAPACITY)
                .map { ArchivedWrite(it.path, it.toolName) },
        )
        val outcome = withContext(Dispatchers.IO) { runCatching { sessionArchive.save(turn) } }
        outcome.fold(
            onSuccess = {
                LogcatHostLog.info(
                    "session archive: saved session=${turn.sessionId} " +
                        "commands=${turn.commands.size} writes=${turn.writes.size}",
                )
            },
            onFailure = { failure ->
                LogcatHostLog.warn(
                    "session archive: save refused session=${turn.sessionId} " +
                        "(${archiveReasonOf(failure)})",
                )
            },
        )
    }

    /**
     * Merges two session reads without losing the newer timestamp of either.
     *
     * The store scan and the turn observation can name the same session; the
     * merged row carries the max of the two readings and the list stays ordered
     * by that reading (newest first). Host order is the sessions page's input
     * order, so this is the order the rows appear in.
     *
     * Ordering caveat: newest-first by the host's one measured time. The iOS
     * baseline orders by its management model, not by a per-session timestamp,
     * and the UI contract does not specify a session order, so the two platforms
     * can list the same sessions in different orders. This is a known difference,
     * not a hidden choice; a contract-level order would replace this rule.
     */
    private fun mergeSessions(
        fromStore: List<HostSessionRecord>,
        observed: List<HostSessionRecord>,
    ): List<HostSessionRecord> {
        val merged = LinkedHashMap<String, HostSessionRecord>()
        for (record in fromStore + observed) {
            val previous = merged[record.id]
            merged[record.id] = if (previous == null) {
                record
            } else {
                HostSessionRecord(
                    id = record.id,
                    lastActivityMs = newer(previous.lastActivityMs, record.lastActivityMs),
                    // An empty title is "this source has no name", never "the name is
                    // empty": the chat record's name must survive a journal merge.
                    title = previous.title.ifEmpty { record.title },
                )
            }
        }
        return merged.values.sortedByDescending { it.lastActivityMs ?: Long.MIN_VALUE }
    }

    private fun newer(previous: Long?, next: Long?): Long? = when {
        previous == null -> next
        next == null -> previous
        else -> maxOf(previous, next)
    }

    /**
     * The page's failure line, with a stable store code and no path.
     *
     * The journal's own codes name why the read failed; anything else is a class
     * name, which is still token-free.
     */
    private fun sessionFailureLabel(failure: Exception): String = when (failure) {
        is JournalException -> "会话记录读取失败（${failure.failure.code}）"
        else -> "会话记录读取失败（${failure.javaClass.simpleName}）"
    }

    private fun logTurn(message: String) {
        Log.i(TAG_TURN, message)
    }

    private fun archiveReasonOf(failure: Throwable): String = when (failure) {
        is com.phonedsh.domain.QuotaExceededException -> "quota_exceeded(${failure.domain})"
        is Exception -> reasonOf(failure)
        else -> failure.javaClass.simpleName
    }

    private fun reasonOf(failure: Exception): String = when (failure) {
        is DshTurnException -> failure.code
        is HostApiException -> failure.code
        else -> failure.javaClass.simpleName
    }

    /**
     * The Worker's CONSOLE text: only the announce marker is logged, never the
     * line itself (a Worker console line can quote user content).
     */
    private fun onWorkerConsole(text: String) {
        if (text.contains("XL_DSH_READY")) {
            Log.i(TAG_TURN, "APP_WORKER_READY")
        }
    }

    private companion object {
        /** Subdirectory of filesDir that owns the WAL (wal.md §1's `xl`). */
        const val WORKSPACE_DIRECTORY = "xl"

        /**
         * Per-boot overlay members beside the WAL (`files/xl/boot-overlay/`).
         *
         * Not part of the VFS, the WAL or §8's own entry set: it is the host's
         * scratch for the bytes it serves the Worker, exactly like iOS's
         * `overlay-<boot_id>.tar` under its workspace root. Recovery ignores it.
         */
        const val BOOT_OVERLAY_DIRECTORY = "boot-overlay"

        /** The App turn's own logcat tag; the acceptance script greps it. */
        const val TAG_TURN = "PDSHAppTurn"

        /** The tool names §2 folds into the changed-files card (write/edit/str_replace_editor). */
        val WRITE_TOOLS: Set<String> = setOf("write", "edit", "str_replace_editor")

        /** `0644` for a workspace file the person imported. */
        const val FILE_MODE: Int = 0b110100100

        /** Characters a workspace file name may not carry; everything else is kept. */
        val FILE_NAME_DISALLOWED: Regex = Regex("[^A-Za-z0-9._-]")

        const val HOST_READY_TIMEOUT_MILLIS = 180_000L
        const val ENGINE_READY_TIMEOUT_MILLIS = 180_000L

    }
}

/**
 * One journal scan's outcome.
 *
 * @param sessions the session rows the store recovered.
 * @param failure the token-free page label when the scan failed; non-null means
 *   [sessions] is empty because nothing was read, not because nothing exists.
 */
private data class SessionScan(
    val sessions: List<HostSessionRecord> = emptyList(),
    val failure: String? = null,
)

/**
 * The engine's frame consumer, forwarded to the host once it exists.
 *
 * The engine is constructed before the host because the host takes the engine;
 * this seam is the other half of that cycle. [attach] happens in the view
 * model's `init` before `host.start()`, and `@Volatile` publishes the target to
 * the origin's threads.
 */
private class HostChannelSeam : LoopbackChannelHandler {
    @Volatile
    private var delegate: LoopbackChannelHandler? = null

    fun attach(delegate: LoopbackChannelHandler) {
        this.delegate = delegate
    }

    private fun target(): LoopbackChannelHandler =
        delegate ?: error("the host channel handler was not attached before the engine started")

    override suspend fun onOpen(connection: LoopbackConnection) {
        target().onOpen(connection)
    }

    override suspend fun onBinary(connection: LoopbackConnection, payload: ByteArray) {
        target().onBinary(connection, payload)
    }

    override suspend fun onClosed(connection: LoopbackConnection, cause: ChannelCloseCause) {
        target().onClosed(connection, cause)
    }
}

/**
 * The `:net` anonymous credential for the demo's scripted upstream.
 *
 * No key is stored in this build; the gateway still strips the Worker's
 * authorization and applies "nothing", which is the honest value for an
 * anonymous exact-origin grant. A production host replaces this provider.
 */
private object AnonymousCredentialProvider : GatewayCredentialProvider {
    override fun resolve(scope: CredentialScope): GatewayCredential = GatewayCredential.None
}

/**
 * The facade's tokens-free log, routed to logcat under its own tag.
 *
 * `:dsh` defaults to `HostLog.Println`; a tagged adapter keeps the host's
 * `host boot`, `wal/1` and `host HELLO sent` provenance greppable next to the
 * engine's `PDSHEngine` lines.
 */
private object LogcatHostLog : HostLog {
    private const val TAG = "PDSHHost"

    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String) {
        Log.e(TAG, message)
    }
}

/**
 * The `:platform` empty Notifier for a build with no notification surface.
 *
 * `:dsh` has no production `Notifier` implementation (its background lane owns
 * the foreground-service notifications, not this facade's recovery notice), and
 * :app never creates a notification channel or asks for POST_NOTIFICATIONS.
 * Reporting [NotifierAvailability.DISABLED] is what makes that visible: the host
 * logs the degraded presentation path instead of silently dropping a notice.
 */
private object DisabledNotifier : Notifier {
    override val availability: NotifierAvailability = NotifierAvailability.DISABLED

    override fun notify(
        id: Int,
        title: String,
        body: String,
        ongoing: Boolean,
        action: NotificationAction?,
    ) = Unit

    override fun cancel(id: Int) = Unit
}
