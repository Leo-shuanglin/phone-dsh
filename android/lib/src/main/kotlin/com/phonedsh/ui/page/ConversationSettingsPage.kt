// 工作过程设置：只向现有偏好写入已知选项。
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.phonedsh.ui.SettingsMetrics
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.surface.SettingsCardGap
import com.phonedsh.ui.surface.SettingsFootnoteRow
import com.phonedsh.ui.surface.SettingsGroupCard
import com.phonedsh.ui.surface.SettingsGroupedRow
import com.phonedsh.ui.surface.SettingsRowFrame
import com.phonedsh.ui.surface.SettingsRowLabel
import com.phonedsh.ui.surface.SettingsSubPageFrame
import com.phonedsh.ui.surface.SettingsSwitch
import com.phonedsh.ui.theme.LocalUiTokens

/** Controls backed by the host's existing work-process preference store. */
@Composable
public fun ConversationSettingsPage(
    preferences: SettingsPreferences,
    busyEnterMode: BusyEnterMode?,
    onSelectPreference: ((SettingsPreferenceChoice) -> Unit)?,
    onSelectBusyEnterMode: ((BusyEnterMode) -> Unit)?,
    onToggleDeveloperTools: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    embedded: Boolean = true,
    onBack: () -> Unit = {},
) {
    val screen: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(CONVERSATION_SETTINGS_LIST_TAG),
            contentPadding = PaddingValues(top = com.phonedsh.ui.surface.SettingsSubPageGroupedTopInset),
            verticalArrangement = Arrangement.spacedBy(SettingsCardGap),
        ) {
            item(key = "work-steps") {
                Column {
                    com.phonedsh.ui.surface.SettingsSectionLabel("工作步骤")
                    SettingsGroupCard {
                        ChoiceRow(
                            title = "显示方式",
                            tag = SettingsScreenState.TRANSCRIPT_VIEW_ROW_ID,
                            current = preferences.transcriptView?.label,
                            options = TranscriptViewMode.entries.map { SettingsMenuOption(it.id, it.label) },
                            enabled = onSelectPreference != null,
                            onSelect = { id -> onSelectPreference?.invoke(SettingsPreferenceChoice(SettingsScreenState.TRANSCRIPT_VIEW_ROW_ID, id)) },
                        )
                        ChoiceRow(
                            title = "收起时机",
                            tag = SettingsScreenState.COLLAPSE_TIMING_ROW_ID,
                            current = preferences.collapseTiming?.label,
                            options = CollapseTiming.entries.map { SettingsMenuOption(it.id, it.label) },
                            enabled = onSelectPreference != null,
                            onSelect = { id -> onSelectPreference?.invoke(SettingsPreferenceChoice(SettingsScreenState.COLLAPSE_TIMING_ROW_ID, id)) },
                        )
                        DeveloperToolsRow(preferences.developerTools, onToggleDeveloperTools)
                    }
                }
            }
            item(key = "sending-usage") {
                Column {
                    com.phonedsh.ui.surface.SettingsSectionLabel("发送与用量")
                    SettingsGroupCard {
                        ChoiceRow(
                            title = "任务进行中发送",
                            tag = SettingsScreenState.BUSY_ENTER_ROW_ID,
                            current = busyEnterMode?.let(::busyEnterLabel),
                            options = listOf(SettingsMenuOption("queue", "排队发送"), SettingsMenuOption("steer", "引导当前任务")),
                            enabled = onSelectBusyEnterMode != null,
                            onSelect = { id -> BusyEnterMode.entries.firstOrNull { it.id == id }?.let { onSelectBusyEnterMode?.invoke(it) } },
                        )
                        ChoiceRow(
                            title = "性能与用量",
                            tag = SettingsScreenState.PERFORMANCE_ROW_ID,
                            current = preferences.performanceUsage?.label,
                            options = PerformanceUsage.entries.map { SettingsMenuOption(it.id, it.label) },
                            enabled = onSelectPreference != null,
                            onSelect = { id -> onSelectPreference?.invoke(SettingsPreferenceChoice(SettingsScreenState.PERFORMANCE_ROW_ID, id)) },
                            dividerBelow = false,
                        )
                    }
                    SettingsFootnoteRow(
                        text = "排队发送会等当前任务结束再处理；引导会把新消息交给正在进行的任务。",
                        modifier = Modifier.testTag(CONVERSATION_SETTINGS_NOTE_TAG),
                    )
                }
            }
        }
    }
    if (embedded) screen() else {
        SettingsSubPageFrame(title = UiStrings.SETTINGS_ROW_WORK_DETAILS, onBack = onBack, modifier = modifier, grouped = true) { screen() }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    tag: String,
    current: String?,
    options: List<SettingsMenuOption>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    dividerBelow: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsGroupedRow(
        title = title,
        value = current ?: UiStrings.UNKNOWN,
        chevron = true,
        enabled = enabled,
        dividerBelow = dividerBelow && !expanded,
        modifier = Modifier.testTag(tag),
        onClick = if (enabled) ({ expanded = !expanded }) else null,
    )
    if (expanded) {
        options.forEachIndexed { index, option ->
            SettingsGroupedRow(
                title = option.title,
                modifier = Modifier.testTag("$tag.option.${option.id}"),
                dividerBelow = index != options.lastIndex,
                onClick = {
                    expanded = false
                    onSelect(option.id)
                },
            )
        }
    }
}

@Composable
private fun DeveloperToolsRow(value: FactState, onToggle: ((Boolean) -> Unit)?) {
    val known = value != FactState.UNKNOWN && onToggle != null
    val checked = value == FactState.YES
    if (!known) {
        SettingsGroupedRow(
            title = "显示代码工作视图",
            value = when (value) {
                FactState.YES -> UiStrings.SETTINGS_BACKGROUND_ON
                FactState.NO -> UiStrings.SETTINGS_BACKGROUND_OFF
                FactState.UNKNOWN -> UiStrings.UNKNOWN
            },
            enabled = false,
            dividerBelow = false,
            modifier = Modifier.testTag(SettingsScreenState.DEVELOPER_TOOLS_ROW_ID),
        )
        return
    }
    SettingsRowFrame(
        dividerBelow = false,
        role = Role.Switch,
        enabled = true,
        onClick = { onToggle?.invoke(!checked) },
        stateValue = if (checked) UiStrings.SETTINGS_BACKGROUND_ON else UiStrings.SETTINGS_BACKGROUND_OFF,
        modifier = Modifier.testTag(SettingsScreenState.DEVELOPER_TOOLS_ROW_ID),
    ) {
        SettingsRowLabel(text = "显示代码工作视图", modifier = Modifier.weight(1f))
        SettingsSwitch(checked = checked, enabled = true)
    }
}

private fun busyEnterLabel(mode: BusyEnterMode): String = when (mode) {
    BusyEnterMode.QUEUE -> UiStrings.BUSY_ENTER_QUEUE
    BusyEnterMode.STEER -> UiStrings.BUSY_ENTER_STEER
}

internal const val CONVERSATION_SETTINGS_LIST_TAG: String = "pdsh.settings.conversation.list"
internal const val CONVERSATION_SETTINGS_NOTE_TAG: String = "pdsh.settings.conversation.note"
