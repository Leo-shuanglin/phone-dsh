// phone-dsh — :dsh / com.phonedsh.dsh.host: is there an OS window for the turn?
// SPDX-License-Identifier: MIT
//
// ---- why this seam exists ---------------------------------------------------
// budgets.md §2 drives `Running -> Draining` from `scenePhase(background)`. Read
// on its own, that row parks the host on every HOME switch — and until this file
// existed the facade fed it unconditionally: the drain closed the epoch, stopped
// the worker, and the in-flight turn died with `channel_lost` *while the platform
// was still holding the process for it*. L39 measured exactly that on API 35:
// UIDT job 8401 in its execution slot, progress notification 7301 attached,
// `oom adj 201`, and still `APP_TURN_FAILED reason=channel_lost`.
//
// ---- the ruling this seam implements ---------------------------------------
// Plan §7.9 promises "尽量完成" while a lock screen / HOME window is in place.
// The D14 row's premise is "no background window": `Draining` is the graceful
// close of a host that has *lost* execution authority, and a host that still has
// it must not close. So the guard is applied where the signal is produced: the
// facade asks this seam first and only feeds `scenePhase(background)` when the
// answer is [TurnWindow.Absent] ([TurnWindow.Unreadable] included), or when the
// bounded hold expires (see `WindowHold` / `DefaultPhoneDshHost`).
//
// ---- the reading is a reading, and it fails safe ---------------------------
// [TurnWindowRead.Platform] reads the `:dsh` background lane's process-wide
// state (`TurnRuntime`), which only becomes `RUNNING` after the platform really
// started the component: `TurnJobService.onStartJob` for UIDT,
// `TurnForegroundService.onStartCommand` for the dataSync fallback.
// `TurnBackground.start()` merely *scheduling* a job does not set it, so a
// scheduled-but-never-started window reads as absent. There is no hard-coded
// `true` anywhere in this composition, and anything the read cannot establish —
// a provider that throws, a "running" snapshot with no backend — is
// [TurnWindow.Unreadable], which the facade treats exactly like absent. The
// direction is deliberate: an unreadable window may cost a turn (it drains),
// it may never grant an exemption.

package com.phonedsh.dsh.host

import com.phonedsh.dsh.background.TurnRuntime

/** Which official channel owns a live turn window. */
enum class TurnWindowBackend(val wireName: String) {
    /** `setUserInitiated(true)` data-transfer job, API 34+ (plan §7.2). */
    USER_INITIATED_JOB("uidt"),

    /** `dataSync` foreground service: below API 34, and the UIDT fallback. */
    FOREGROUND_SERVICE("foreground_service"),
}

/**
 * One reading of "does the OS hold a window for the running turn".
 *
 * Three answers, because "I could not read it" is not "no": the caller is told
 * which one it got so the log can name it. Both [Absent] and [Unreadable] make
 * the facade feed the `Running -> Draining` edge; [InPlace] is the only value
 * that defers it.
 */
sealed interface TurnWindow {
    /** A window is in place: the platform granted the turn execution authority. */
    data class InPlace(val backend: TurnWindowBackend) : TurnWindow

    /** The reading succeeded and says no window exists (or it already ended). */
    data object Absent : TurnWindow

    /**
     * The reading itself failed: the provider threw, or the lane's snapshot is
     * inconsistent. A caller must treat this exactly like [Absent].
     */
    data class Unreadable(val reason: String) : TurnWindow
}

/**
 * The turn-window fact, as a seam.
 *
 * Synchronous and cheap like [LeaseProbe]: it is read on the signal thread, in
 * the same serialised section that feeds the D14 machine. Implementations may
 * throw — [DefaultPhoneDshHost] converts a throw into [TurnWindow.Unreadable]
 * and drains, so a broken reading can never keep a host running on a promise it
 * did not verify.
 */
fun interface TurnWindowRead {
    /** One reading; never called concurrently with itself by this facade. */
    fun read(): TurnWindow

    companion object {
        /**
         * The production reading: the background lane's own record of the window.
         *
         * `TurnRuntime` is process-wide and is written by the platform's
         * callbacks, not by this facade: [TurnRuntime.snapshot] is `RUNNING` only
         * after `onStartJob` (UIDT) or `onStartCommand` (foreground service)
         * actually ran, and it carries the backend that ran. That is the strongest
         * reading available without a `Context` (`TurnBackground.capability()` and
         * `HostBackground` live app-side and need one), and it is a reading: a
         * host that never opened a window — every JVM test, a foreground-only
         * turn, a refused UIDT — reads [TurnWindow.Absent].
         *
         * A `RUNNING` snapshot with no backend is not a window this seam can
         * describe: it is reported as [TurnWindow.Unreadable] so the facade drains
         * instead of inventing one.
         */
        val Platform: TurnWindowRead = TurnWindowRead {
            val snapshot = TurnRuntime.snapshot()
            if (!snapshot.isRunning) return@TurnWindowRead TurnWindow.Absent
            when (snapshot.backend) {
                TurnRuntime.Backend.USER_INITIATED_JOB ->
                    TurnWindow.InPlace(TurnWindowBackend.USER_INITIATED_JOB)

                TurnRuntime.Backend.FOREGROUND_SERVICE ->
                    TurnWindow.InPlace(TurnWindowBackend.FOREGROUND_SERVICE)

                null -> TurnWindow.Unreadable(
                    "the background lane reports a running turn with no backend",
                )
            }
        }

        /**
         * The empty composition: no background lane is wired, so no window can be
         * in place. Explicit rather than a default, so a caller that has no
         * background lane says so.
         */
        val NoBackgroundLane: TurnWindowRead = TurnWindowRead { TurnWindow.Absent }
    }
}
