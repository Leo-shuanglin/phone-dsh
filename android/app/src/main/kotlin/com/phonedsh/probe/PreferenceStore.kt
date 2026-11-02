// phone-dsh — :app: the one user-preference store (iOS `UserDefaults` parity).
// SPDX-License-Identifier: MIT
//
// iOS keeps its non-model preferences in `UserDefaults.standard`, read and
// written by `NativeChatPreferences` (`examples/probe-app/PhoneDSHProbe/
// NativeChatPreferences.swift:112-321`) and `ChatSettings`
// (`ChatModel.swift:27-49`). Android had no preference layer at all: every
// `SettingsPreferences` field stayed `null`, the settings index's pickers were
// dead controls, and the chat-appearance page reported `settingsWritable = NO`.
// This file is the missing layer: **one** `SharedPreferences` file behind one
// class, with the same key names, types and defaults as iOS so a reader can map
// a value across the two codebases without a translation table.
//
// What is deliberately **not** here:
//
//   * `chat.dsh.currentSessionID` — L65 stores the current session in the
//     `:domain` `SessionArchive` (SQLite) next to the transcript it belongs to;
//     a second copy in a preferences file would be a second authority for the
//     same fact. The key is listed in the parity report, answered by that store.
//   * `textFontFamily` / `codeFontFamily` / `terminalFontFamily` and
//     `shortcutOverrides` — Android has no font-family surface (no custom font
//     loading) and no shortcut editor (the page is a reference list; nothing
//     produces an override), so storing them would be a value no screen can
//     read or write. See the lane report for the full key-by-key table.
//   * `chat.egress.allow`, `phoneDSH.egressListBeforeAllowAll`,
//     `phone-dsh.session.title-hints`, `phone-dsh.sidebar.collapsed-workspaces`
//     and the folder bookmarks — each needs a surface Android does not have
//     (an editable egress allow-all, a prompt composer, a workspace sidebar, a
//     SAF folder picker). They are open questions, not silent omissions.
//
// The storage seam ([PreferenceFile]) exists so the store's whole behaviour —
// read, clamp, migrate, write, fail-closed on an unknown enum id — is testable
// on a plain JVM. The Android binding ([SharedPreferenceFile]) is one small
// class; no other file in `:app` calls `getSharedPreferences` for preferences.

package com.phonedsh.probe

import android.content.Context
import android.content.SharedPreferences
import com.phonedsh.ui.page.BusyEnterMode
import com.phonedsh.ui.page.CollapseTiming
import com.phonedsh.ui.page.FactState
import com.phonedsh.ui.page.PerformanceUsage
import com.phonedsh.ui.page.SettingsAppearance
import com.phonedsh.ui.page.SettingsLanguage
import com.phonedsh.ui.page.SettingsPreferences
import com.phonedsh.ui.page.SettingsScreenState
import com.phonedsh.ui.page.TranscriptViewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One font-size role, with the iOS range and default
 * (`NativeChatPreferences.swift:36-54`).
 *
 * @param id the role's stable id, used only in a log line.
 * @param key the `UserDefaults` key the baseline stores this role under.
 * @param defaultSize the baseline's default (`text 17`, `code 14`, `terminal 13`).
 * @param sizes the baseline's clamp range (`setFontSize` clamps, never refuses).
 */
internal enum class FontRole(
    val id: String,
    val key: String,
    val defaultSize: Int,
    val sizes: IntRange,
) {
    TEXT("text", "textFontSize", 17, 10..22),
    CODE("code", "codeFontSize", 14, 10..16),
    TERMINAL("terminal", "terminalFontSize", 13, 10..20),
}

/**
 * Every preference this app stores, in its resolved form.
 *
 * The defaults are the baseline's, not "the first menu option":
 * `appearance = system`, `language = system`, `transcriptView = detailed`,
 * `collapseTiming = completion`, `developerTools = true`,
 * `performanceUsage = detailed`, `busyEnterMode = queue` and
 * `chat.background.keepalive = true` (`NativeChatPreferences.swift:118-131`,
 * `ChatModel.swift:43-49`).
 *
 * @param textFontSize 正文 size, 10...22, default 17.
 * @param codeFontSize 代码 size, 10...16, default 14.
 * @param terminalFontSize 终端 size, 10...20, default 13.
 * @param appearance 外观 (`appearance`): light / dark / system.
 * @param language 语言 (`language`): system / zh / en.
 * @param transcriptView 工作步骤展示 (`transcriptView`).
 * @param collapseTiming 工作步骤收起时机 (`collapseTiming`).
 * @param developerTools 显示代码工作视图 (`developerTools`), on by default.
 * @param performanceUsage 性能与用量 (`performanceUsage`).
 * @param busyEnterMode 繁忙时的发送行为 (`busyEnterMode`, legacy
 *   `phone-dsh.composer.busyEnter`), only `queue` / `steer`.
 * @param backgroundKeepAlive 后台运行 (`chat.background.keepalive`), on by default.
 */
internal data class Preferences(
    val textFontSize: Int = FontRole.TEXT.defaultSize,
    val codeFontSize: Int = FontRole.CODE.defaultSize,
    val terminalFontSize: Int = FontRole.TERMINAL.defaultSize,
    val appearance: SettingsAppearance = SettingsAppearance.SYSTEM,
    val language: SettingsLanguage = SettingsLanguage.SYSTEM,
    val transcriptView: TranscriptViewMode = TranscriptViewMode.DETAILED,
    val collapseTiming: CollapseTiming = CollapseTiming.COMPLETION,
    val developerTools: Boolean = true,
    val performanceUsage: PerformanceUsage = PerformanceUsage.DETAILED,
    val busyEnterMode: BusyEnterMode = BusyEnterMode.QUEUE,
    val backgroundKeepAlive: Boolean = true,
) {
    /** The size of [role], in px. */
    fun fontSize(role: FontRole): Int = when (role) {
        FontRole.TEXT -> textFontSize
        FontRole.CODE -> codeFontSize
        FontRole.TERMINAL -> terminalFontSize
    }

    /** This preference set with [value] clamped into the role's range. */
    fun withFontSize(role: FontRole, value: Int): Preferences {
        val size = value.coerceIn(role.sizes.first, role.sizes.last)
        return when (role) {
            FontRole.TEXT -> copy(textFontSize = size)
            FontRole.CODE -> copy(codeFontSize = size)
            FontRole.TERMINAL -> copy(terminalFontSize = size)
        }
    }

    /**
     * Whether the app should render its dark token table.
     *
     * `system` delegates to the platform's own answer; the other two ids are the
     * user's explicit choice, exactly as `NativeAppearance.colorScheme` returns
     * `.light` / `.dark` / `nil` on iOS.
     */
    fun darkTheme(systemDark: Boolean): Boolean = when (appearance) {
        SettingsAppearance.LIGHT -> false
        SettingsAppearance.DARK -> true
        SettingsAppearance.SYSTEM -> systemDark
    }

    /**
     * The settings index's 通用-tab values, from this preference set.
     *
     * Every field is a value the host really read, so the page renders the
     * stored choice instead of 未设置. `shortcutCount` stays `null`: the shortcut
     * page is a reference list and this app stores no overrides.
     */
    fun toSettingsPreferences(): SettingsPreferences = SettingsPreferences(
        language = language,
        appearance = appearance,
        fontSizeConversation = textFontSize,
        fontSizeCode = codeFontSize,
        fontSizeTerminal = terminalFontSize,
        transcriptView = transcriptView,
        collapseTiming = collapseTiming,
        developerTools = if (developerTools) FactState.YES else FactState.NO,
        performanceUsage = performanceUsage,
    )
}

/**
 * The storage seam under [PreferenceStore].
 *
 * One typed getter per `UserDefaults` value kind, and an explicit
 * `boolean`/`integer` that answers `null` when the key is absent — the
 * difference between "the user chose the default" and "nothing was stored" is
 * what keeps a later migration honest.
 */
internal interface PreferenceFile {
    /** The string at [key], or `null` when absent. */
    fun string(key: String): String?

    /** The int at [key], or `null` when absent. */
    fun integer(key: String): Int?

    /** The bool at [key], or `null` when absent. */
    fun boolean(key: String): Boolean?

    /** Stores [value] at [key], durably enough to survive a kill. */
    fun putString(key: String, value: String)

    /** Stores [value] at [key]. */
    fun putInt(key: String, value: Int)

    /** Stores [value] at [key]. */
    fun putBoolean(key: String, value: Boolean)

    /** Drops [key] when present. */
    fun remove(key: String)
}

/**
 * The `SharedPreferences` binding: one file, committed synchronously.
 *
 * `commit()` and not `apply()` on purpose. This store's whole acceptance is
 * "change a preference, kill the process, restart, the value is still there";
 * `apply()` hands the write to a background queue that a `force-stop` does not
 * wait for, so the one test that matters could pass in memory and lose the file.
 * The writes are single small values made on a gesture, not a hot path.
 *
 * @param context any context; only the application context is retained.
 * @param name the one preferences file, defaulting to [PreferenceStore.FILE_NAME].
 */
internal class SharedPreferenceFile(
    context: Context,
    name: String = PreferenceStore.FILE_NAME,
) : PreferenceFile {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    // The getters are fail-soft on a type mismatch: a value stored by another
    // build under the same key (a string where this one expects an int) must
    // resolve to the baseline default, not crash the launch. `commit()`'s boolean
    // is not threaded back through the store: a failed flush (a full disk) is not
    // distinguishable from a landed write here, and that gap is listed as an open
    // question in the lane report rather than hidden.
    override fun string(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    override fun integer(key: String): Int? =
        runCatching { if (prefs.contains(key)) prefs.getInt(key, 0) else null }.getOrNull()

    override fun boolean(key: String): Boolean? =
        runCatching { if (prefs.contains(key)) prefs.getBoolean(key, false) else null }.getOrNull()

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).commit()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}

/**
 * The one preference store: reads the file once, writes through, publishes the
 * resolved value set.
 *
 * A value read back that this build cannot parse (an enum id written by a newer
 * install, a size outside the baseline's range) resolves to the baseline's
 * default instead of failing the launch: a preference is never a reason the app
 * cannot start. Nothing is deleted on that path — the stored bytes stay for the
 * build that understands them, which is the same rule the WAL follows.
 *
 * @param file the storage seam; a test passes an in-memory map.
 */
internal class PreferenceStore(private val file: PreferenceFile) {

    private val mutableState = MutableStateFlow(read())

    /** The resolved preferences; a write publishes a new value. */
    val state: StateFlow<Preferences> = mutableState.asStateFlow()

    init {
        // The baseline migrated `phone-dsh.composer.busyEnter` to `busyEnterMode`
        // in place (`NativeChatPreferences.swift:147-152`). Android never wrote the
        // legacy key, but an install that imported iOS data would have it, and the
        // copy-forward is the same four lines.
        adoptLegacyBusyEnter()
    }

    /** The current resolved values. */
    fun snapshot(): Preferences = mutableState.value

    /** 外观. */
    fun setAppearance(value: SettingsAppearance) {
        file.putString(APPEARANCE_KEY, value.id)
        mutableState.value = mutableState.value.copy(appearance = value)
    }

    /** 语言. */
    fun setLanguage(value: SettingsLanguage) {
        file.putString(LANGUAGE_KEY, value.id)
        mutableState.value = mutableState.value.copy(language = value)
    }

    /** 工作步骤展示. */
    fun setTranscriptView(value: TranscriptViewMode) {
        file.putString(TRANSCRIPT_VIEW_KEY, value.id)
        mutableState.value = mutableState.value.copy(transcriptView = value)
    }

    /** 工作步骤收起时机. */
    fun setCollapseTiming(value: CollapseTiming) {
        file.putString(COLLAPSE_TIMING_KEY, value.id)
        mutableState.value = mutableState.value.copy(collapseTiming = value)
    }

    /** 性能与用量. */
    fun setPerformanceUsage(value: PerformanceUsage) {
        file.putString(PERFORMANCE_USAGE_KEY, value.id)
        mutableState.value = mutableState.value.copy(performanceUsage = value)
    }

    /** 显示代码工作视图. */
    fun setDeveloperTools(value: Boolean) {
        file.putBoolean(DEVELOPER_TOOLS_KEY, value)
        mutableState.value = mutableState.value.copy(developerTools = value)
    }

    /**
     * 繁忙时的发送行为.
     *
     * The only two ids the baseline accepts (`NativeChatPreferences.swift:228`);
     * an id outside them is not a mode this app can render, so the value is left
     * unchanged rather than stored as a string no menu can show.
     */
    fun setBusyEnterMode(value: BusyEnterMode) {
        file.putString(BUSY_ENTER_KEY, value.id)
        mutableState.value = mutableState.value.copy(busyEnterMode = value)
    }

    /** 后台运行: whether the app should open a background window for a turn. */
    fun setBackgroundKeepAlive(value: Boolean) {
        file.putBoolean(BACKGROUND_KEEP_ALIVE_KEY, value)
        mutableState.value = mutableState.value.copy(backgroundKeepAlive = value)
    }

    /** One font-size role, clamped to the baseline's range. */
    fun setFontSize(role: FontRole, value: Int) {
        val resolved = value.coerceIn(role.sizes.first, role.sizes.last)
        file.putInt(role.key, resolved)
        mutableState.value = mutableState.value.withFontSize(role, resolved)
    }

    /**
     * Performs one settings-page picker choice.
     *
     * The row ids are the settings index's own constants, so a gesture on the
     * index and the same gesture on the chat-appearance page reach one store
     * through one vocabulary. Returns `false` for a row this build does not
     * write, and the caller says so instead of leaving a dead control silent.
     *
     * @param rowId `SettingsScreenState`'s row id.
     * @param optionId the picker's option id, or a font size as decimal text for
     *   the three `preference.fontSize.*` rows.
     */
    fun apply(rowId: String, optionId: String): Boolean = when (rowId) {
        SettingsScreenState.LANGUAGE_ROW_ID ->
            SettingsLanguage.entries.firstOrNull { it.id == optionId }?.also(::setLanguage) != null

        SettingsScreenState.APPEARANCE_ROW_ID ->
            SettingsAppearance.entries.firstOrNull { it.id == optionId }?.also(::setAppearance) != null

        SettingsScreenState.TRANSCRIPT_VIEW_ROW_ID ->
            TranscriptViewMode.entries.firstOrNull { it.id == optionId }?.also(::setTranscriptView) != null

        SettingsScreenState.COLLAPSE_TIMING_ROW_ID ->
            CollapseTiming.entries.firstOrNull { it.id == optionId }?.also(::setCollapseTiming) != null

        SettingsScreenState.PERFORMANCE_ROW_ID ->
            PerformanceUsage.entries.firstOrNull { it.id == optionId }?.also(::setPerformanceUsage) != null

        SettingsScreenState.TEXT_FONT_SIZE_ROW_ID -> applyFontSize(FontRole.TEXT, optionId)
        SettingsScreenState.CODE_FONT_SIZE_ROW_ID -> applyFontSize(FontRole.CODE, optionId)
        SettingsScreenState.TERMINAL_FONT_SIZE_ROW_ID -> applyFontSize(FontRole.TERMINAL, optionId)
        else -> false
    }

    private fun applyFontSize(role: FontRole, optionId: String): Boolean {
        val size = optionId.trim().toIntOrNull() ?: return false
        setFontSize(role, size)
        return true
    }

    /** Resolves every key from [file], one defaulted read per key. */
    private fun read(): Preferences {
        val defaults = Preferences()
        return Preferences(
            textFontSize = readFontSize(FontRole.TEXT),
            codeFontSize = readFontSize(FontRole.CODE),
            terminalFontSize = readFontSize(FontRole.TERMINAL),
            appearance = readEnum(APPEARANCE_KEY, SettingsAppearance.entries, { it.id }, defaults.appearance),
            language = readEnum(LANGUAGE_KEY, SettingsLanguage.entries, { it.id }, defaults.language),
            transcriptView = readEnum(
                TRANSCRIPT_VIEW_KEY,
                TranscriptViewMode.entries,
                { it.id },
                defaults.transcriptView,
            ),
            collapseTiming = readEnum(
                COLLAPSE_TIMING_KEY,
                CollapseTiming.entries,
                { it.id },
                defaults.collapseTiming,
            ),
            developerTools = file.boolean(DEVELOPER_TOOLS_KEY) ?: defaults.developerTools,
            performanceUsage = readEnum(
                PERFORMANCE_USAGE_KEY,
                PerformanceUsage.entries,
                { it.id },
                defaults.performanceUsage,
            ),
            // The legacy key is deliberately *not* consulted here: the store
            // adopts it once, in `adoptLegacyBusyEnter`, so a legacy value can
            // never keep shadowing a choice written to the current key.
            busyEnterMode = readEnum(BUSY_ENTER_KEY, BusyEnterMode.entries, { it.id }, defaults.busyEnterMode),
            backgroundKeepAlive = file.boolean(BACKGROUND_KEEP_ALIVE_KEY) ?: defaults.backgroundKeepAlive,
        )
    }

    private fun readFontSize(role: FontRole): Int {
        val stored = file.integer(role.key) ?: return role.defaultSize
        // A stored size outside the range was written by a build with a different
        // range. Clamp rather than fail: the value is still "the user's size",
        // and the default would silently discard it.
        return stored.coerceIn(role.sizes.first, role.sizes.last)
    }

    private fun <T> readEnum(key: String, values: List<T>, id: (T) -> String, fallback: T): T =
        values.firstOrNull { id(it) == file.string(key) } ?: fallback

    private fun adoptLegacyBusyEnter() {
        if (file.string(BUSY_ENTER_KEY) != null) return
        val legacy = file.string(LEGACY_BUSY_ENTER_KEY) ?: return
        val mode = BusyEnterMode.entries.firstOrNull { it.id == legacy } ?: return
        file.putString(BUSY_ENTER_KEY, mode.id)
        mutableState.value = mutableState.value.copy(busyEnterMode = mode)
    }

    companion object {
        /**
         * The one preferences file.
         *
         * Two other `SharedPreferences` files exist in this app and are **not**
         * preferences: `pdsh.phone.permission.asks` (the permission ask log, a
         * device fact) and `pdsh_background_state` (the interrupted-turn
         * tombstone, `:dsh`'s). A new feature must extend this file, not add a
         * fourth.
         */
        const val FILE_NAME: String = "phone-dsh.preferences"

        /** 正文 size key (`NativeChatPreferences.swift:135`). */
        const val TEXT_FONT_SIZE_KEY: String = "textFontSize"

        /** 代码 size key (`:136`). */
        const val CODE_FONT_SIZE_KEY: String = "codeFontSize"

        /** 终端 size key (`:137`). */
        const val TERMINAL_FONT_SIZE_KEY: String = "terminalFontSize"

        /** 外观 (`:141`). */
        const val APPEARANCE_KEY: String = "appearance"

        /** 语言 (`:142`). */
        const val LANGUAGE_KEY: String = "language"

        /** 工作步骤展示 (`:143`). */
        const val TRANSCRIPT_VIEW_KEY: String = "transcriptView"

        /** 工作步骤收起时机 (`:144`). */
        const val COLLAPSE_TIMING_KEY: String = "collapseTiming"

        /** 显示代码工作视图 (`:145`). */
        const val DEVELOPER_TOOLS_KEY: String = "developerTools"

        /** 性能与用量 (`:146`). */
        const val PERFORMANCE_USAGE_KEY: String = "performanceUsage"

        /** 繁忙时的发送行为 (`:147`). */
        const val BUSY_ENTER_KEY: String = "busyEnterMode"

        /** The baseline's pre-rename key, read once by [adoptLegacyBusyEnter]. */
        const val LEGACY_BUSY_ENTER_KEY: String = "phone-dsh.composer.busyEnter"

        /** 后台运行 (`ChatModel.swift:43`). */
        const val BACKGROUND_KEEP_ALIVE_KEY: String = "chat.background.keepalive"

        /**
         * Every key this store persists, in the baseline's spelling.
         *
         * A guard, not documentation: a new key must be added here, and the JVM
         * suite fails until it is — the lane's rule is "one preference, one
         * persisted-key assertion".
         */
        val ALL_KEYS: Set<String> = setOf(
            TEXT_FONT_SIZE_KEY,
            CODE_FONT_SIZE_KEY,
            TERMINAL_FONT_SIZE_KEY,
            APPEARANCE_KEY,
            LANGUAGE_KEY,
            TRANSCRIPT_VIEW_KEY,
            COLLAPSE_TIMING_KEY,
            DEVELOPER_TOOLS_KEY,
            PERFORMANCE_USAGE_KEY,
            BUSY_ENTER_KEY,
            BACKGROUND_KEEP_ALIVE_KEY,
        )
    }
}
