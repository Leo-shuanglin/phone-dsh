// Session configuration: the built-in settings groups, permissions and plugins.
// SPDX-License-Identifier: MIT
//
// Spec: `spec/ui/v1/README.md` §1 row NativeConfigurationPage (会话级与插件配置;
// entry: 页头 `⋯` → 本会话设置; exit: sheet), §3.1 (the ScreenState layer) and
// §5 (`insets`).
//
// The page has no harness event anchor. Every group, field, permission mode and
// plugin entry is a host read, so §3.1 rule 3 makes the read-only snapshot the
// UiState-side input and `from(snapshot, local)` a pure function. The Android
// host does not expose a settings-write command yet; the page therefore shows
// the reported values, names the read/write fact and hands saving to the host.
// It never invents a second settings store.
//
// The states that look alike are kept apart:
//   * a snapshot with no reported content is the empty state;
//   * an unreported snapshot is 未知, never "no settings" and never `0 个条目`;
//   * a failed read is 失败 and drops every value claim;
//   * inside a group, a null field list is 设置项未知, while an empty list is
//     这个分组没有设置项. Inside the plugin inventory the same split holds for
//     the plugin list and the experimental-plugin list.
//
// The baseline's page header is not a toolbar row: it is one `Section` holding
// the intro sentence and an `HStack(spacing: 10)` of three glass pills —
// 插件说明 / 刷新 / 添加插件 (`NativeConfigurationPage.swift:61-103`). The toolbar
// itself carries only 完成 (`:30-34`). L74 had put 刷新 in the toolbar to un-hide
// the contract's control; the source disagrees, so the pill row is where it now
// lives (still unconditional, still disabled when the host hands no re-read).
// The pills, their glyphs, the orange warning line and the globe glyph live in
// `ui/surface/GlassPill.kt` and `ui/surface/WarningRow.kt`, once, with the
// measurements that justify their shape.
//
// Geometry: page-level tokens only (`geometry.pageOuterMarginH`,
// `spacing.badgeInsetH` for the pill gap the baseline spells as `spacing: 10`).
// The header, section header and InfoRow floors live in `PageScaffold.kt`, and
// the list gap comes from `pageListGap`. No signature-surface geometry is reused
// in this file, so no `unverified geometry` marker applies here (the pill's own
// borrowed insets are marked in `GlassPill.kt`).
package com.phonedsh.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import com.phonedsh.ui.surface.GlassPill
import com.phonedsh.ui.surface.GlobeGlyph
import com.phonedsh.ui.surface.InfoCircleGlyph
import com.phonedsh.ui.surface.PlusGlyph
import com.phonedsh.ui.surface.RefreshGlyph
import com.phonedsh.ui.surface.SettingsSubPageFrame
import com.phonedsh.ui.surface.SettingsFormRow
import com.phonedsh.ui.surface.SettingsSectionHeader
import com.phonedsh.ui.surface.SETTINGS_CLOSE_GLYPH
import com.phonedsh.ui.surface.WarningLine

// ---------------------------------------------------------------------------
// Facts
// ---------------------------------------------------------------------------

/**
 * One settings field as the host reported it.
 *
 * @param id the host's stable field id.
 * @param title the field label.
 * @param value the host-formatted value; `null` means the host did not report
 *   it, which renders 未知 rather than a default.
 * @param detail the host's own explanation line, if any.
 * @param overridden whether the host says this value is a user override.
 */
@Immutable
public data class ConfigurationField(
    public val id: String,
    public val title: String,
    public val value: String? = null,
    public val detail: String? = null,
    public val overridden: Boolean = false,
)

/**
 * One built-in settings group.
 *
 * @param namespace the host's namespace key (for example `agent-loop`).
 * @param title the group title.
 * @param detail the group's own description.
 * @param writable whether the host says this group can be edited.
 * @param fields the fields; `null` means unread, `emptyList()` means the group
 *   really has no field.
 */
@Immutable
public data class ConfigurationGroup(
    public val namespace: String,
    public val title: String,
    public val detail: String? = null,
    public val writable: FactState = FactState.UNKNOWN,
    public val fields: List<ConfigurationField>? = null,
)

/** One permission preset the host can assign to new sessions. */
@Immutable
public data class ConfigurationPermissionMode(
    public val id: String,
    public val name: String,
    public val detail: String? = null,
)

/** One DSH Loader plugin entry. */
@Immutable
public data class ConfigurationPlugin(
    public val id: String,
    public val module: String,
    public val enabled: FactState = FactState.UNKNOWN,
    public val phase: String? = null,
)

/** One experimental plugin row; `loaded` is a three-valued fact. */
@Immutable
public data class ConfigurationExperimentalPlugin(
    public val id: String,
    public val title: String,
    public val status: String? = null,
    public val loaded: FactState = FactState.UNKNOWN,
)

/**
 * The permission presets the host reported, and which one new sessions get.
 *
 * @param modes the presets; `null` means the catalog was not read.
 * @param selected the preset the host says is the current default, if any.
 * @param failure the catalog read's own failure message, if it failed.
 */
@Immutable
public data class ConfigurationPermissionView(
    public val modes: List<ConfigurationPermissionMode>? = null,
    public val selected: ConfigurationPermissionMode? = null,
    public val failure: String? = null,
) {
    /** True when the host did not report the catalog: the page says 未知. */
    public val isUnknown: Boolean get() = modes == null

    /** True when the host reported a catalog with no preset in it. */
    public val isEmpty: Boolean get() = modes != null && modes.isEmpty()
}

/**
 * The host's read-only configuration snapshot.
 *
 * @param sessionTitle the session this sheet configures, when resolved.
 * @param settingsWritable whether the host accepts a settings write.
 * @param groups the built-in groups; `null` means the read failed or did not run.
 * @param permissionModes the available presets; `null` means unread.
 * @param defaultPermissionModeId the current preset id, if any.
 * @param plugins the DSH Loader entries; `null` means unread.
 * @param experimentalPlugins the experimental-plugin rows; `null` means unread.
 * @param pluginManagementMessage the host's own explanation of what the phone
 *   can and cannot do with plugins.
 * @param settingsFailure a failure specific to the settings read.
 * @param inventoryFailure a failure specific to the plugin-inventory read.
 * @param permissionFailure a failure specific to the permission catalog read.
 */
@Immutable
public data class ConfigurationSnapshot(
    public val sessionTitle: String? = null,
    public val settingsWritable: FactState = FactState.UNKNOWN,
    public val groups: List<ConfigurationGroup>? = null,
    public val permissionModes: List<ConfigurationPermissionMode>? = null,
    public val defaultPermissionModeId: String? = null,
    public val plugins: List<ConfigurationPlugin>? = null,
    public val experimentalPlugins: List<ConfigurationExperimentalPlugin>? = null,
    public val pluginManagementMessage: String? = null,
    public val settingsFailure: String? = null,
    public val inventoryFailure: String? = null,
    public val permissionFailure: String? = null,
) {
    /** True when the snapshot carries at least one thing the page can show. */
    internal val hasContent: Boolean
        get() = sessionTitle != null ||
            settingsWritable != FactState.UNKNOWN ||
            !groups.isNullOrEmpty() ||
            !permissionModes.isNullOrEmpty() ||
            !plugins.isNullOrEmpty() ||
            !experimentalPlugins.isNullOrEmpty() ||
            pluginManagementMessage != null ||
            settingsFailure != null ||
            inventoryFailure != null ||
            permissionFailure != null
}

/**
 * The page's read-only input.
 *
 * @param snapshot the host's snapshot; `null` means the host did not report one.
 * @param failure the host's read failure; non-null when the last read failed.
 */
@Immutable
public data class ConfigurationFacts(
    public val snapshot: ConfigurationSnapshot? = null,
    public val failure: String? = null,
)

// ---------------------------------------------------------------------------
// ScreenState
// ---------------------------------------------------------------------------

/** The four outcomes the page distinguishes. */
public enum class ConfigurationStatus {
    /** At least one group, permission preset or plugin was reported. */
    READY,

    /** The host returned a snapshot but nothing in it was measured. */
    EMPTY,

    /** The last host read failed; no value claim survives it. */
    FAILED,

    /** The host did not report a snapshot: the page says 未知, never "none". */
    UNKNOWN,
}

/** One display-ready field or explanation row. */
@Immutable
public data class ConfigurationRowView(
    public val id: String,
    public val title: String,
    public val value: String? = null,
    public val subtitle: String? = null,
    public val tone: PageTone = PageTone.NORMAL,
    public val enabled: Boolean = true,
)

/** One collapsible group of rows, with an explicit empty-text case. */
@Immutable
public data class ConfigurationSectionView(
    public val id: String,
    public val title: String,
    public val trailing: String?,
    public val expanded: Boolean,
    public val rows: List<ConfigurationRowView>,
    public val emptyText: String? = null,
    /** True when the host reports this group writable: the save verbs arm. */
    public val writable: Boolean = false,
)

/**
 * The complete configuration screen state.
 *
 * @param status the four-state outcome; [ConfigurationStatus.FAILED] wins because
 *   a failed read proves nothing about the values.
 * @param sessionTitle the configured session's title, or `null` when unread.
 * @param writableValue the visible read/write word.
 * @param writableTone its ink.
 * @param sections the built-in groups (only while the 内置插件设置 fold is open)
 *   plus the about area (only while 插件说明 is open) and the fixed
 *   experimental-plugin and installed-plugin groups.
 * @param webSearchRow the navigation row into the web-search page; always present
 *   in [ConfigurationStatus.READY] because it does not depend on the snapshot.
 * @param permission the new-session permission presets and the current default.
 * @param builtInExpanded whether the 内置插件设置 master fold is open.
 * @param aboutExpanded whether the about area (本机管理范围 / 手机 Worker 限制) is open.
 * @param notes cross-cutting partial failures.
 * @param failure the host's failure message, re-shown by the page.
 * @param hasUnmeasuredValues true when a visible row is 未知, so the page can
 *   explain the word once.
 */
@Immutable
public data class ConfigurationScreenState(
    public val status: ConfigurationStatus,
    public val sessionTitle: String?,
    public val writableValue: String,
    public val writableTone: PageTone,
    public val sections: List<ConfigurationSectionView>,
    public val webSearchRow: ConfigurationRowView?,
    public val permission: ConfigurationPermissionView,
    public val builtInExpanded: Boolean,
    public val aboutExpanded: Boolean,
    public val notes: List<PageNote>,
    public val failure: String?,
    public val hasUnmeasuredValues: Boolean,
) {
    /** True when the host reported no snapshot. */
    public val isUnknown: Boolean get() = status == ConfigurationStatus.UNKNOWN

    /** True when the host reported an empty snapshot. */
    public val isEmpty: Boolean get() = status == ConfigurationStatus.EMPTY

    /** True when the last read failed. */
    public val isFailed: Boolean get() = status == ConfigurationStatus.FAILED

    /**
     * A stable, display-free signature of the sequence-relevant state.
     *
     * The sequence test compares these strings and then replays the fold script,
     * so a clock, an I/O read or a settings write would show up as a mismatch.
     */
    public fun signature(): String = buildString {
        append(status.name).append(':')
        append(writableTone.name).append(':')
        append(if (webSearchRow != null) "web" else "-").append(':')
        append(if (builtInExpanded) "in" else "-").append(':')
        append(if (aboutExpanded) "about" else "-").append(':')
        append(if (permission.isUnknown) "perm?" else "perm:${permission.modes?.size ?: 0}").append(':')
        append(sections.joinToString(",") { section ->
            "${section.id}=${if (section.expanded) "1" else "0"}:${section.rows.size}"
        }).append(':')
        append(notes.size).append(':')
        append(if (hasUnmeasuredValues) "partial" else "complete")
    }

    public companion object {
        /** The permission block's stable id (it is a block, not a titled section). */
        public const val PERMISSION_SECTION_ID: String = "config.permission"
        /** The 插件说明 about area's fold id: it owns 本机管理范围 and 手机 Worker 限制. */
        public const val INFO_SECTION_ID: String = "config.info"
        public const val BUILT_IN_SECTION_ID: String = "config.built-in"
        public const val SCOPE_SECTION_ID: String = "config.scope"
        public const val LIMITS_SECTION_ID: String = "config.limits"
        public const val EXPERIMENTAL_SECTION_ID: String = "config.experimental"
        public const val INSTALLED_SECTION_ID: String = "config.installed"
        public const val WEB_SEARCH_ROW_ID: String = "config.websearch"

        /** The prefix every host settings group's section id carries. */
        public const val GROUP_SECTION_PREFIX: String = "config.group."

        /** The host namespace a group row belongs to, as a stable section id. */
        public fun groupSectionId(namespace: String): String = GROUP_SECTION_PREFIX + namespace

        /** True when [id] is one of the host's built-in setting groups. */
        public fun isGroupSection(id: String): Boolean = id.startsWith(GROUP_SECTION_PREFIX)

        /**
         * Projects (facts, fold script) into the screen state.
         *
         * Pure: no clock, no I/O and no engine call. The host supplies every
         * value and every failure message, so the same facts and the same fold
         * script always yield the same state sequence (§3.1).
         */
        public fun from(
            facts: ConfigurationFacts,
            local: ConfigurationLocalState = ConfigurationLocalState(),
        ): ConfigurationScreenState {
            val snapshot = facts.snapshot
            val status = when {
                facts.failure != null -> ConfigurationStatus.FAILED
                snapshot == null -> ConfigurationStatus.UNKNOWN
                !snapshot.hasContent -> ConfigurationStatus.EMPTY
                else -> ConfigurationStatus.READY
            }
            if (status != ConfigurationStatus.READY) {
                return ConfigurationScreenState(
                    status = status,
                    sessionTitle = snapshot?.sessionTitle,
                    writableValue = UiStrings.UNKNOWN,
                    writableTone = PageTone.UNKNOWN,
                    sections = emptyList(),
                    webSearchRow = null,
                    permission = ConfigurationPermissionView(),
                    builtInExpanded = true,
                    aboutExpanded = false,
                    notes = emptyList(),
                    failure = facts.failure,
                    hasUnmeasuredValues = false,
                )
            }
            val measured = requireNotNull(snapshot)
            val settingsWritable = measured.settingsWritable
            val builtInExpanded = local.fold.isExpanded(BUILT_IN_SECTION_ID)
            val aboutExpanded = local.fold.isExpanded(INFO_SECTION_ID)
            val permission = ConfigurationPermissionView(
                modes = measured.permissionModes,
                selected = measured.defaultPermissionModeId?.let { id ->
                    measured.permissionModes?.firstOrNull { mode -> mode.id == id }
                },
                failure = measured.permissionFailure,
            )
            val sections = buildList {
                if (builtInExpanded) {
                    measured.groups.orEmpty().forEach { group ->
                        add(groupSection(group, local))
                    }
                }
                if (aboutExpanded) {
                    add(scopeSection(measured))
                    add(limitsSection(measured))
                }
                add(experimentalSection(measured, local))
                add(installedSection(measured, local))
            }
            val notes = buildList {
                if (measured.settingsFailure != null) {
                    add(
                        PageNote(
                            id = "config.note.settings",
                            text = UiStrings.CONFIGURATION_PARTIAL_FAILURE_FORMAT.format(
                                measured.settingsFailure,
                            ),
                            tone = PageTone.DEGRADED,
                        ),
                    )
                }
                if (measured.inventoryFailure != null) {
                    add(
                        PageNote(
                            id = "config.note.inventory",
                            text = UiStrings.CONFIGURATION_PARTIAL_FAILURE_FORMAT.format(
                                measured.inventoryFailure,
                            ),
                            tone = PageTone.DEGRADED,
                        ),
                    )
                }
                if (measured.permissionFailure != null) {
                    add(
                        PageNote(
                            id = "config.note.permission",
                            text = UiStrings.CONFIGURATION_PARTIAL_FAILURE_FORMAT.format(
                                measured.permissionFailure,
                            ),
                            tone = PageTone.DEGRADED,
                        ),
                    )
                }
            }
            val hasUnmeasuredValues = measured.sessionTitle == null ||
                settingsWritable == FactState.UNKNOWN ||
                permission.isUnknown ||
                sections.any { section -> section.rows.any { row -> row.tone == PageTone.UNKNOWN } }
            return ConfigurationScreenState(
                status = ConfigurationStatus.READY,
                sessionTitle = measured.sessionTitle,
                writableValue = when (settingsWritable) {
                    FactState.YES -> UiStrings.CONFIGURATION_WRITABLE_YES
                    FactState.NO -> UiStrings.CONFIGURATION_WRITABLE_NO
                    FactState.UNKNOWN -> UiStrings.CONFIGURATION_WRITABLE_UNKNOWN
                },
                writableTone = when (settingsWritable) {
                    FactState.YES -> PageTone.ON
                    FactState.NO -> PageTone.NORMAL
                    FactState.UNKNOWN -> PageTone.UNKNOWN
                },
                sections = sections,
                webSearchRow = ConfigurationRowView(
                    id = WEB_SEARCH_ROW_ID,
                    title = UiStrings.CONFIGURATION_WEB_SEARCH,
                ),
                permission = permission,
                builtInExpanded = builtInExpanded,
                aboutExpanded = aboutExpanded,
                notes = notes,
                failure = null,
                hasUnmeasuredValues = hasUnmeasuredValues,
            )
        }

        private fun groupSection(
            group: ConfigurationGroup,
            local: ConfigurationLocalState,
        ): ConfigurationSectionView {
            val id = groupSectionId(group.namespace)
            val fields = group.fields
            val writableRow = ConfigurationRowView(
                id = "$id.writable",
                title = UiStrings.CONFIGURATION_WRITABLE,
                value = when (group.writable) {
                    FactState.YES -> UiStrings.CONFIGURATION_WRITABLE_YES
                    FactState.NO -> UiStrings.CONFIGURATION_WRITABLE_NO
                    FactState.UNKNOWN -> UiStrings.CONFIGURATION_WRITABLE_UNKNOWN
                },
                subtitle = group.detail,
                tone = when (group.writable) {
                    FactState.YES -> PageTone.ON
                    FactState.NO -> PageTone.NORMAL
                    FactState.UNKNOWN -> PageTone.UNKNOWN
                },
            )
            val fieldRows = if (fields == null) {
                listOf(
                    ConfigurationRowView(
                        id = "$id.unknown",
                        title = UiStrings.CONFIGURATION_GROUP_UNKNOWN,
                        value = UiStrings.UNKNOWN,
                        subtitle = UiStrings.SETTINGS_UNKNOWN_HINT,
                        tone = PageTone.UNKNOWN,
                    ),
                )
            } else {
                fields.map { field ->
                    ConfigurationRowView(
                        id = "$id.${field.id}",
                        title = field.title,
                        value = field.value ?: UiStrings.UNKNOWN,
                        subtitle = when {
                            field.value == null -> UiStrings.SETTINGS_UNKNOWN_HINT
                            field.overridden -> UiStrings.CONFIGURATION_FIELD_OVERRIDDEN
                            else -> field.detail
                        },
                        tone = if (field.value == null) PageTone.UNKNOWN else PageTone.NORMAL,
                    )
                }
            }
            val rows = listOf(writableRow) + fieldRows
            return ConfigurationSectionView(
                id = id,
                title = group.title,
                trailing = if (fields == null) {
                    UiStrings.UNKNOWN
                } else {
                    UiStrings.CONFIGURATION_GROUP_ITEMS.format(fields.size)
                },
                expanded = local.fold.isExpanded(id),
                rows = rows,
                emptyText = if (fields != null && fields.isEmpty()) {
                    UiStrings.CONFIGURATION_GROUP_EMPTY
                } else {
                    null
                },
                writable = group.writable == FactState.YES,
            )
        }

        private fun scopeSection(snapshot: ConfigurationSnapshot): ConfigurationSectionView =
            ConfigurationSectionView(
                id = SCOPE_SECTION_ID,
                title = UiStrings.CONFIGURATION_SCOPE_SECTION,
                trailing = null,
                expanded = true,
                rows = listOf(
                    ConfigurationRowView(
                        id = "$SCOPE_SECTION_ID.scope",
                        title = UiStrings.CONFIGURATION_SCOPE_TEXT,
                    ),
                ),
            )

        private fun limitsSection(snapshot: ConfigurationSnapshot): ConfigurationSectionView =
            ConfigurationSectionView(
                id = LIMITS_SECTION_ID,
                title = UiStrings.CONFIGURATION_LIMITS_SECTION,
                trailing = null,
                expanded = true,
                rows = listOf(
                    ConfigurationRowView(
                        id = "$LIMITS_SECTION_ID.message",
                        // The host's measured sentence, or this phone's own wording of the
                        // same fact. The baseline names an *iPhone* Worker; Android does
                        // not print that about the device it runs on.
                        title = snapshot.pluginManagementMessage
                            ?: UiStrings.PLUGIN_INVENTORY_MANAGEMENT_NOTICE,
                    ),
                    ConfigurationRowView(
                        id = "$LIMITS_SECTION_ID.loaded",
                        title = UiStrings.CONFIGURATION_ONLY_LOADED_TEXT,
                    ),
                ),
            )

        private fun experimentalSection(
            snapshot: ConfigurationSnapshot,
            local: ConfigurationLocalState,
        ): ConfigurationSectionView {
            val plugins = snapshot.experimentalPlugins
            val rows = plugins.orEmpty().map { plugin ->
                ConfigurationRowView(
                    id = "$EXPERIMENTAL_SECTION_ID.${plugin.id}",
                    title = plugin.title,
                    value = when (plugin.loaded) {
                        FactState.YES -> UiStrings.CONFIGURATION_PLUGIN_LOADED
                        FactState.NO -> UiStrings.CONFIGURATION_PLUGIN_NOT_LOADED
                        FactState.UNKNOWN -> UiStrings.UNKNOWN
                    },
                    subtitle = plugin.status ?: if (plugin.loaded == FactState.UNKNOWN) {
                        UiStrings.SETTINGS_UNKNOWN_HINT
                    } else {
                        null
                    },
                    tone = when (plugin.loaded) {
                        FactState.YES -> PageTone.ON
                        FactState.NO -> PageTone.NORMAL
                        FactState.UNKNOWN -> PageTone.UNKNOWN
                    },
                )
            }
            return ConfigurationSectionView(
                id = EXPERIMENTAL_SECTION_ID,
                title = UiStrings.CONFIGURATION_EXPERIMENTAL,
                trailing = if (plugins == null) {
                    UiStrings.UNKNOWN
                } else {
                    UiStrings.CONFIGURATION_GROUP_ITEMS.format(plugins.size)
                },
                expanded = local.fold.isExpanded(EXPERIMENTAL_SECTION_ID),
                rows = rows,
                emptyText = when {
                    plugins == null -> UiStrings.CONFIGURATION_EXPERIMENTAL_UNKNOWN
                    plugins.isEmpty() -> UiStrings.CONFIGURATION_EXPERIMENTAL_EMPTY
                    else -> null
                },
            )
        }

        private fun installedSection(
            snapshot: ConfigurationSnapshot,
            local: ConfigurationLocalState,
        ): ConfigurationSectionView {
            val plugins = snapshot.plugins
            val rows = plugins.orEmpty().map { plugin ->
                ConfigurationRowView(
                    id = "$INSTALLED_SECTION_ID.${plugin.id}",
                    title = plugin.module,
                    value = when (plugin.enabled) {
                        FactState.YES -> UiStrings.CONFIGURATION_PLUGIN_ENABLED
                        FactState.NO -> UiStrings.CONFIGURATION_PLUGIN_DISABLED
                        FactState.UNKNOWN -> UiStrings.UNKNOWN
                    },
                    subtitle = plugin.phase?.let { phase ->
                        UiStrings.CONFIGURATION_PLUGIN_META_FORMAT.format(
                            UiStrings.CONFIGURATION_PLUGIN_ENTRY.format(plugin.id),
                            UiStrings.CONFIGURATION_PLUGIN_PHASE.format(phase),
                        )
                    } ?: UiStrings.CONFIGURATION_PLUGIN_ENTRY.format(plugin.id),
                    tone = when (plugin.enabled) {
                        FactState.YES -> PageTone.ON
                        FactState.NO -> PageTone.NORMAL
                        FactState.UNKNOWN -> PageTone.UNKNOWN
                    },
                )
            }
            return ConfigurationSectionView(
                id = INSTALLED_SECTION_ID,
                title = UiStrings.CONFIGURATION_INSTALLED,
                trailing = if (plugins == null) {
                    UiStrings.UNKNOWN
                } else {
                    UiStrings.CONFIGURATION_INSTALLED_COUNT.format(plugins.size)
                },
                expanded = local.fold.isExpanded(INSTALLED_SECTION_ID),
                rows = rows,
                emptyText = when {
                    plugins == null -> UiStrings.CONFIGURATION_INSTALLED_UNKNOWN
                    plugins.isEmpty() -> UiStrings.CONFIGURATION_INSTALLED_EMPTY
                    else -> null
                },
            )
        }
    }
}

/** The local UI events of the configuration page: only the group folds. */
public sealed interface ConfigurationUiEvent {
    /** Collapse or expand one group. */
    public data class ToggleSection(public val id: String) : ConfigurationUiEvent
}

/**
 * The fold script; [apply] is pure, so a test drives it like a reducer.
 *
 * The installed-plugin group starts collapsed because the baseline renders it as
 * a disclosure; the about area starts collapsed because the baseline keeps it
 * behind the 插件说明 sheet; every other group starts expanded.
 *
 * @param fold which sections the user collapsed.
 */
@Immutable
public data class ConfigurationLocalState(
    public val fold: FoldState = FoldState(
        collapsedIds = setOf(
            ConfigurationScreenState.INSTALLED_SECTION_ID,
            ConfigurationScreenState.INFO_SECTION_ID,
        ),
    ),
) {
    /** Applies one local event. */
    public fun apply(event: ConfigurationUiEvent): ConfigurationLocalState = when (event) {
        is ConfigurationUiEvent.ToggleSection -> copy(fold = fold.toggle(event.id))
    }
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

/**
 * The session-configuration sheet.
 *
 * The control set is the baseline's (`NativeConfigurationPage.swift`): the three
 * 插件说明/刷新/添加插件 glass pills (`:67-95`), the 内置插件设置 master fold over
 * the host's groups, the new-session permission block, and the two 插件说明
 * sections. The write verbs exist even when this host cannot perform a write; the
 * host's measured `settingsWritable` (and a `null` callback) disables them rather
 * than hiding a control the contract declares.
 *
 * @param state the pure screen state (`ConfigurationScreenState.from`).
 * @param onDone close the sheet (the baseline's `完成`).
 * @param onToggleSection a group header was tapped; the host applies
 *   `ConfigurationUiEvent.ToggleSection` and re-projects.
 * @param onRefresh re-read the snapshot; `null` renders 刷新 disabled (the
 *   baseline always draws the pill, so this page never hides it).
 * @param onOpenWebSearch open the web-search page; `null` makes the row read-only.
 * @param onRetry retry the failed read; `null` renders no retry row.
 * @param onDismissFailure dismiss the failure line; `null` renders no dismiss row.
 * @param onSaveGroup save one built-in group (the group's namespace); `null`
 *   disables 保存.
 * @param onDiscardGroup discard one built-in group's drafts; `null` disables 撤销.
 * @param onResetField reset one overridden field (section id, row id); `null`
 *   disables 恢复默认.
 * @param onPickDefaultPermission open the new-session permission picker; `null`
 *   (or a read-only host) disables the 默认权限 row.
 * @param onSaveDefaultPermission save the staged permission default; `null`
 *   disables 保存默认.
 * @param onDiscardDefaultPermission discard the staged permission default;
 *   `null` disables its 撤销.
 */
@Composable
public fun NativeConfigurationPage(
    state: ConfigurationScreenState,
    onDone: () -> Unit,
    onToggleSection: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
    onOpenWebSearch: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onDismissFailure: (() -> Unit)? = null,
    onSaveGroup: ((String) -> Unit)? = null,
    onDiscardGroup: ((String) -> Unit)? = null,
    onResetField: ((String, String) -> Unit)? = null,
    onPickDefaultPermission: (() -> Unit)? = null,
    onSaveDefaultPermission: (() -> Unit)? = null,
    onDiscardDefaultPermission: (() -> Unit)? = null,
    embedded: Boolean = false,
) {
    SettingsSubPageFrame(
        backGlyph = SETTINGS_CLOSE_GLYPH,
        embedded = embedded,
        largeTitle = UiStrings.CONFIGURATION_TITLE,
        modifier = modifier,
        rootTag = UiPageTags.CONFIGURATION_ROOT,
        // The baseline's toolbar carries ONE item, 完成 (`NativeConfigurationPage.swift:30-34`).
        // L74 had put 刷新 here to make the contract's control visible; the source
        // says otherwise: 刷新 is the middle glass pill in the page header
        // (`:76-82`), so it moved there — still unconditional
        // (`enabled = onRefresh != null`), still the same 刷新 the contract names.
        trailing = {
            SessionTextAction(
                label = UiStrings.CONFIGURATION_DONE,
                onClick = onDone,
                tone = SessionActionTone.ACCENT,
                modifier = Modifier.testTag(UiPageTags.CONFIGURATION_DONE),
            )
        },
    ) {
        when (state.status) {
            ConfigurationStatus.FAILED -> ConfigurationFailureBlock(
                failure = state.failure,
                onRetry = onRetry,
                onDismissFailure = onDismissFailure,
            )

            ConfigurationStatus.UNKNOWN -> ConfigurationUnknownList()

            ConfigurationStatus.EMPTY -> EmptyHint(
                title = UiStrings.CONFIGURATION_EMPTY,
                hint = UiStrings.CONFIGURATION_EMPTY_HINT,
                tag = UiPageTags.CONFIGURATION_EMPTY,
            )

            ConfigurationStatus.READY -> ConfigurationContent(
                state = state,
                onRefresh = onRefresh,
                onToggleSection = onToggleSection,
                onOpenWebSearch = onOpenWebSearch,
                onSaveGroup = onSaveGroup,
                onDiscardGroup = onDiscardGroup,
                onResetField = onResetField,
                onPickDefaultPermission = onPickDefaultPermission,
                onSaveDefaultPermission = onSaveDefaultPermission,
                onDiscardDefaultPermission = onDiscardDefaultPermission,
            )
        }
    }
}

/**
 * The unknown snapshot: a scrollable pair of lines so the negative assertion in
 * the test runs against a composed node, not against a window that never held it.
 */
@Composable
private fun ConfigurationUnknownList() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiPageTags.CONFIGURATION_LIST),
        verticalArrangement = Arrangement.Top,
    ) {
        item(key = "config.unknown") {
            SettingsFormRow(
                title = UiStrings.CONFIGURATION_UNKNOWN_STATE,
                value = null,
                subtitle = UiStrings.CONFIGURATION_UNKNOWN_HINT,
                modifier = Modifier.testTag(UiPageTags.CONFIGURATION_UNKNOWN),
            )
        }
        item(key = "config.unknown.detail") {
            SettingsFormRow(
                title = UiStrings.UNKNOWN,
                value = null,
                subtitle = UiStrings.CONFIGURATION_UNKNOWN_DETAIL,
                tone = PageTone.UNKNOWN,
                modifier = Modifier.testTag(UiPageTags.CONFIGURATION_UNKNOWN_DETAIL),
            )
        }
    }
}

/** The measured configuration: identity, groups, permissions and plugins. */
@Composable
private fun ConfigurationContent(
    state: ConfigurationScreenState,
    onRefresh: (() -> Unit)?,
    onToggleSection: (String) -> Unit,
    onOpenWebSearch: (() -> Unit)?,
    onSaveGroup: ((String) -> Unit)?,
    onDiscardGroup: ((String) -> Unit)?,
    onResetField: ((String, String) -> Unit)?,
    onPickDefaultPermission: (() -> Unit)?,
    onSaveDefaultPermission: (() -> Unit)?,
    onDiscardDefaultPermission: (() -> Unit)?,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    var addMenuOpen by remember { mutableStateOf(false) }
    var addNotice by remember { mutableStateOf<String?>(null) }
    val writable = state.writableTone == PageTone.ON
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiPageTags.CONFIGURATION_LIST),
        verticalArrangement = Arrangement.Top,
    ) {
        item(key = "config.intro") {
            ConfigurationIntro()
        }
        item(key = "config.actions") {
            Column(modifier = Modifier.fillMaxWidth()) {
                // The baseline's three glass pills, in one `HStack(spacing: 10)`
                // (`NativeConfigurationPage.swift:67-95`): 插件说明 (bordered),
                // 刷新 (bordered, disabled while loading or while drafts are dirty)
                // and 添加插件 (borderedProminent, always disabled on a phone).
                // Android cannot know `isLoading`/`hasDrafts` — its snapshot carries
                // no loading fact — so 刷新 is disabled when the host handed no
                // re-read command, and prints the host's own answer otherwise.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.geometry.pageOuterMarginH),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetH),
                ) {
                    GlassPill(
                        label = UiStrings.CONFIGURATION_PLUGIN_INFO,
                        onClick = {
                            onToggleSection(ConfigurationScreenState.INFO_SECTION_ID)
                        },
                        glyph = { tint -> InfoCircleGlyph(tint) },
                        tag = UiPageTags.CONFIGURATION_PLUGIN_INFO_ACTION,
                        accessibilityLabel = UiStrings.CONFIGURATION_PLUGIN_INFO_HINT,
                    )
                    GlassPill(
                        label = UiStrings.CONFIGURATION_REFRESH,
                        onClick = { onRefresh?.invoke() },
                        glyph = { tint -> RefreshGlyph(tint) },
                        enabled = onRefresh != null,
                        tag = UiPageTags.CONFIGURATION_REFRESH,
                    )
                    Box {
                        GlassPill(
                            label = UiStrings.CONFIGURATION_ADD_DISABLED,
                            onClick = { addMenuOpen = true },
                            glyph = { tint -> PlusGlyph(tint) },
                            tag = UiPageTags.CONFIGURATION_ADD_PLUGIN,
                            accessibilityLabel = UiStrings.CONFIGURATION_ADD_DISABLED_HINT,
                        )
                        DropdownMenu(
                            expanded = addMenuOpen,
                            onDismissRequest = { addMenuOpen = false },
                        ) {
                            // The baseline's two items are disabled because no phone can
                            // install a plugin. They stay tappable here so the tap can
                            // print the measured reason instead of dead control.
                            DropdownMenuItem(
                                text = { Text(UiStrings.CONFIGURATION_INSTALL_THIRD_PARTY) },
                                onClick = {
                                    addMenuOpen = false
                                    addNotice = UiStrings.CONFIGURATION_ADD_DISABLED_HINT
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(UiStrings.CONFIGURATION_CREATE_WITH_AGENT) },
                                onClick = {
                                    addMenuOpen = false
                                    addNotice = UiStrings.CONFIGURATION_ADD_DISABLED_HINT
                                },
                            )
                        }
                    }
                }
                if (addNotice != null) {
                    ConfigurationNoteRow(
                        text = addNotice.orEmpty(),
                        tag = UiPageTags.CONFIGURATION_ADD_NOTICE,
                    )
                }
            }
        }
        if (state.hasUnmeasuredValues) {
            item(key = "config.unmeasured") {
                SettingsFormRow(
                    title = UiStrings.UNKNOWN,
                    value = null,
                    subtitle = UiStrings.CONFIGURATION_UNMEASURED_HINT,
                    tone = PageTone.UNKNOWN,
                    modifier = Modifier.testTag(UiPageTags.CONFIGURATION_UNMEASURED),
                )
            }
        }
        item(key = "config.session") {
            SettingsFormRow(
                title = UiStrings.CONFIGURATION_SESSION,
                value = state.sessionTitle ?: UiStrings.UNKNOWN,
                tone = if (state.sessionTitle == null) PageTone.UNKNOWN else PageTone.NORMAL,
                subtitle = if (state.sessionTitle == null) UiStrings.SETTINGS_UNKNOWN_HINT else null,
                modifier = Modifier.testTag(UiPageTags.CONFIGURATION_SESSION),
            )
        }
        item(key = "config.writable") {
            SettingsFormRow(
                title = UiStrings.CONFIGURATION_WRITABLE,
                value = state.writableValue,
                tone = state.writableTone,
                subtitle = if (state.writableTone == PageTone.UNKNOWN) {
                    UiStrings.SETTINGS_UNKNOWN_HINT
                } else {
                    null
                },
                modifier = Modifier.testTag(UiPageTags.CONFIGURATION_WRITABLE),
            )
        }
        state.notes.forEach { note ->
            item(key = "config.note.${note.id}") {
                // The baseline's orange warning line (`NativeConfigurationPage.swift:15-20`):
                // `Label(…, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)`.
                // It is one sentence for `model.error`; Android's host reports up to
                // three partial-read failures, so each draws its own line with the
                // host's own wording under the format's prefix.
                WarningLine(
                    text = note.text,
                    tag = UiPageTags.CONFIGURATION_NOTE_PREFIX + note.id,
                )
            }
        }
        item(key = "config.builtin") {
            SettingsSectionHeader(
                title = UiStrings.CONFIGURATION_BUILT_IN_SECTION,
                expanded = state.builtInExpanded,
                onToggle = { onToggleSection(ConfigurationScreenState.BUILT_IN_SECTION_ID) },
                tag = UiPageTags.CONFIGURATION_BUILT_IN,
            )
        }
        if (state.builtInExpanded) {
            state.webSearchRow?.let { row ->
                item(key = "config.websearch") {
                    // `InfoRow` is the row idiom the structure gate reads as a
                    // labelled action. `SettingsFormRow` is 3 pt taller (17 pt title)
                    // and matches the baseline better, but it is the family's
                    // *display* row: the gate would read every display row as an
                    // action, so an action row has to use `InfoRow` here.
                    //
                    // The baseline's row is `Label(…, systemImage: "globe")`
                    // (`NativeConfigurationPage.swift:134`), and `InfoRow` has no
                    // leading slot, so the glyph is drawn beside the row instead of
                    // inside it. The glyph's tint is the system blue the shot
                    // measures; it borrows `colors.accent` (see `GlassPill.kt`).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = tokens.geometry.pageOuterMarginH),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
                    ) {
                        GlobeGlyph(tint = tokens.colors.accent)
                        InfoRow(
                            title = UiStrings.CONFIGURATION_WEB_SEARCH,
                            value = null,
                            subtitle = row.subtitle,
                            onClick = onOpenWebSearch,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(UiPageTags.CONFIGURATION_WEB_SEARCH),
                        )
                    }
                }
            }
            state.sections
                .filter { section -> ConfigurationScreenState.isGroupSection(section.id) }
                .forEach { group ->
                    item(key = "config.header.${group.id}") {
                        SettingsSectionHeader(
                            title = group.title,
                            expanded = group.expanded,
                            onToggle = { onToggleSection(group.id) },
                            trailing = group.trailing,
                            tag = UiPageTags.CONFIGURATION_SECTION_PREFIX + group.id,
                        )
                    }
                    if (group.expanded) {
                        items(count = group.rows.size, key = { index -> group.rows[index].id }) { index ->
                            val row = group.rows[index]
                            SettingsFormRow(
                                title = row.title,
                                value = row.value,
                                subtitle = row.subtitle,
                                tone = row.tone,
                                enabled = row.enabled,
                                trailing = if (row.subtitle == UiStrings.CONFIGURATION_FIELD_OVERRIDDEN) {
                                    {
                                        SessionTextAction(
                                            label = UiStrings.CONFIGURATION_RESET,
                                            onClick = {
                                                onResetField?.invoke(
                                                    group.id.removePrefix(
                                                        ConfigurationScreenState.GROUP_SECTION_PREFIX,
                                                    ),
                                                    row.id.removePrefix(group.id + "."),
                                                )
                                            },
                                            tone = SessionActionTone.SECONDARY,
                                            enabled = group.writable && onResetField != null,
                                            modifier = Modifier.testTag(
                                                UiPageTags.CONFIGURATION_ROW_RESET_PREFIX + row.id,
                                            ),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                        if (group.emptyText != null) {
                            item(key = "config.empty.${group.id}") {
                                SettingsFormRow(
                                    title = group.emptyText,
                                    value = null,
                                    tone = PageTone.UNKNOWN,
                                )
                            }
                        }
                        item(key = "config.group-actions.${group.id}") {
                            ConfigurationGroupActions(
                                groupId = group.id,
                                writable = group.writable && writable,
                                canSave = onSaveGroup != null,
                                onSave = {
                                    onSaveGroup?.invoke(
                                        group.id.removePrefix(
                                            ConfigurationScreenState.GROUP_SECTION_PREFIX,
                                        ),
                                    )
                                },
                                canDiscard = onDiscardGroup != null,
                                onDiscard = {
                                    onDiscardGroup?.invoke(
                                        group.id.removePrefix(
                                            ConfigurationScreenState.GROUP_SECTION_PREFIX,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            item(key = "config.permission") {
                ConfigurationPermissionBlock(
                    permission = state.permission,
                    writable = writable,
                    onPick = onPickDefaultPermission,
                    onSave = onSaveDefaultPermission,
                    onDiscard = onDiscardDefaultPermission,
                )
            }
        }
        state.sections
            .filterNot { section -> ConfigurationScreenState.isGroupSection(section.id) }
            .forEach { section ->
                item(key = "config.header.${section.id}") {
                    SettingsSectionHeader(
                        title = section.title,
                        expanded = section.expanded,
                        onToggle = {
                            // 本机管理范围 and 手机 Worker 限制 are the two halves of one
                            // 插件说明 area: either header closes/opens both, exactly as
                            // the baseline's one sheet does.
                            onToggleSection(
                                if (section.id == ConfigurationScreenState.SCOPE_SECTION_ID ||
                                    section.id == ConfigurationScreenState.LIMITS_SECTION_ID
                                ) {
                                    ConfigurationScreenState.INFO_SECTION_ID
                                } else {
                                    section.id
                                },
                            )
                        },
                        trailing = section.trailing,
                        tag = UiPageTags.CONFIGURATION_SECTION_PREFIX + section.id,
                    )
                }
                if (section.expanded) {
                    items(count = section.rows.size, key = { index -> section.rows[index].id }) { index ->
                        val row = section.rows[index]
                        SettingsFormRow(
                            title = row.title,
                            value = row.value,
                            subtitle = row.subtitle,
                            tone = row.tone,
                            enabled = row.enabled,
                            modifier = if (row.id.startsWith("config.installed.")) {
                                Modifier.testTag(
                                    UiPageTags.CONFIGURATION_PLUGIN_ROW_PREFIX + row.id,
                                )
                            } else {
                                Modifier
                            },
                        )
                    }
                    if (section.emptyText != null) {
                        item(key = "config.empty.${section.id}") {
                            SettingsFormRow(
                                title = section.emptyText,
                                value = null,
                                tone = PageTone.UNKNOWN,
                            )
                        }
                    }
                }
            }
        item(key = "config.read-only") {
            ConfigurationNoteRow(text = UiStrings.CONFIGURATION_READ_ONLY)
        }
    }
}

/**
 * The baseline's 保存 / 撤销 pair under one built-in settings group.
 *
 * The pair is rendered even when the host reports the group read-only: the
 * baseline declares the controls, and a disabled control states the measured
 * fact. A `null` callback (a host with no write command) also disables it.
 */
@Composable
private fun ConfigurationGroupActions(
    groupId: String,
    writable: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    canDiscard: Boolean,
    onDiscard: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.geometry.pageOuterMarginH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionTextAction(
            label = UiStrings.CONFIGURATION_SAVE,
            onClick = onSave,
            tone = SessionActionTone.ACCENT,
            enabled = writable && canSave,
            modifier = Modifier.testTag(UiPageTags.CONFIGURATION_GROUP_SAVE_PREFIX + groupId),
        )
        SessionTextAction(
            label = UiStrings.CONFIGURATION_DISCARD,
            onClick = onDiscard,
            tone = SessionActionTone.SECONDARY,
            enabled = writable && canDiscard,
            modifier = Modifier.testTag(UiPageTags.CONFIGURATION_GROUP_DISCARD_PREFIX + groupId),
        )
    }
}

/**
 * The 新会话默认权限 block.
 *
 * Three cases stay apart, as everywhere else on this page: an unread catalog is
 * 未知 (with its own failure line when the read failed), a measured-empty catalog
 * says 没有可选的权限预设, and a measured catalog shows the current 默认权限. The
 * picker row and the 保存默认 / 撤销 pair are the baseline's controls; the host's
 * measured writability (and a `null` callback) disables them.
 */
@Composable
private fun ConfigurationPermissionBlock(
    permission: ConfigurationPermissionView,
    writable: Boolean,
    onPick: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onDiscard: (() -> Unit)?,
) {
    val tokens = LocalUiTokens.current
    val modes = permission.modes
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.CONFIGURATION_PERMISSION),
    ) {
        InfoRow(
            title = UiStrings.CONFIGURATION_DEFAULT_PERMISSION,
            value = null,
            subtitle = UiStrings.CONFIGURATION_DEFAULT_PERMISSION_HINT,
        )
        when {
            modes == null -> InfoRow(
                title = UiStrings.CONFIGURATION_PERMISSION_UNKNOWN,
                value = UiStrings.UNKNOWN,
                subtitle = permission.failure ?: UiStrings.SETTINGS_UNKNOWN_HINT,
                tone = PageTone.UNKNOWN,
            )

            modes.isEmpty() -> InfoRow(
                title = UiStrings.CONFIGURATION_PERMISSION_EMPTY,
                value = null,
                subtitle = UiStrings.CONFIGURATION_DEFAULT_PERMISSION_HINT,
            )

            else -> {
                InfoRow(
                    title = UiStrings.CONFIGURATION_DEFAULT_PERMISSION_ROW,
                    value = permission.selected?.name ?: UiStrings.UNKNOWN,
                    subtitle = permission.selected?.detail
                        ?: UiStrings.CONFIGURATION_DEFAULT_PERMISSION_HINT,
                    tone = if (permission.selected == null) PageTone.UNKNOWN else PageTone.NORMAL,
                    onClick = if (writable) onPick else null,
                    modifier = Modifier.testTag(UiPageTags.CONFIGURATION_PERMISSION_ROW),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.geometry.pageOuterMarginH),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SessionTextAction(
                        label = UiStrings.CONFIGURATION_SAVE_DEFAULT,
                        onClick = { onSave?.invoke() },
                        tone = SessionActionTone.ACCENT,
                        enabled = writable && onSave != null,
                        modifier = Modifier.testTag(UiPageTags.CONFIGURATION_PERMISSION_SAVE),
                    )
                    SessionTextAction(
                        label = UiStrings.CONFIGURATION_DISCARD,
                        onClick = { onDiscard?.invoke() },
                        tone = SessionActionTone.SECONDARY,
                        enabled = writable && onDiscard != null,
                        modifier = Modifier.testTag(UiPageTags.CONFIGURATION_PERMISSION_DISCARD),
                    )
                }
            }
        }
    }
}

/** The sheet's one-sentence purpose, inked with the secondary token. */
@Composable
private fun ConfigurationIntro() {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    BasicText(
        text = UiStrings.CONFIGURATION_INTRO,
        style = type.secondary.copy(color = tokens.colors.secondary),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.CONFIGURATION_INTRO)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.spacing.badgeInsetV,
            ),
    )
}

/** One explanatory line; the ink is a token, never a second colour source. */
@Composable
private fun ConfigurationNoteRow(
    text: String,
    tag: String = UiPageTags.CONFIGURATION_READ_ONLY,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    BasicText(
        text = text,
        style = type.caption.copy(color = tokens.colors.tertiary),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.spacing.badgeInsetV,
            ),
    )
}

/** The failure line: the host's message, a retry row and a dismiss row. */
@Composable
private fun ConfigurationFailureBlock(
    failure: String?,
    onRetry: (() -> Unit)?,
    onDismissFailure: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsFormRow(
            title = UiStrings.CONFIGURATION_LOAD_FAILED,
            value = null,
            subtitle = failure,
            modifier = Modifier.testTag(UiPageTags.CONFIGURATION_FAILURE),
        )
        if (onRetry != null) {
            SettingsFormRow(
                title = UiStrings.RETRY,
                value = null,
                onClick = onRetry,
                modifier = Modifier.testTag(UiPageTags.CONFIGURATION_RETRY),
            )
        }
        if (onDismissFailure != null) {
            SettingsFormRow(
                title = UiStrings.DISMISS,
                value = null,
                onClick = onDismissFailure,
                modifier = Modifier.testTag(UiPageTags.CONFIGURATION_DISMISS),
            )
        }
    }
}

/** Every token this file reads; kept equal to the source by `PageTokenReferenceTest`. */
internal object NativeConfigurationPageTokens {
    val refs: List<String> = listOf(
        "colors.accent",
        "colors.secondary",
        "colors.tertiary",
        "geometry.pageOuterMarginH",
        "spacing.badgeInsetH",
        "spacing.badgeInsetV",
        "spacing.iconText",
        "typography.caption",
        "typography.secondary",
    )
}
