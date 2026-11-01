// 设备连接: which devices a connector publishes, and which session each one opens.
// SPDX-License-Identifier: MIT
//
// The Android page of `NativeDeviceConnectionPage.swift` (172 lines). It keeps that
// page's three-part shape and, more importantly, its honesty:
//
//  1. **A failure section** — why the last open did not happen, plus the explicit
//     「解除该设备的会话映射」 action when a saved mapping is what is in the way.
//     iOS never silently rebinds; neither does this page.
//  2. **真实连接设备** — the devices the injected connector published. With no
//     connector, the baseline's `ContentUnavailableView("未接入设备连接器")` body is
//     reproduced word for word: this build does not scan, pair or invent a device.
//  3. **传输能力** — the two transport rows, and the footer that says the real
//     transport does not exist yet. Both rows read 未提供 because that is true.
//
// The data comes from `DeviceConnectionSource` (`:domain`), which projects the
// existing `DeviceConnector.state` through `ConnectorStatus`. This page owns no
// connection life cycle of its own: its local state is the row that is opening.
//
// Geometry: the page margin, the header floor, the row floor and the status dot all
// come from `spec/ui/v1/tokens.json` (page-level block); no value is invented here
// and no signature-surface geometry is borrowed.
package com.phonedsh.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.phonedsh.domain.connector.ConnectorStatus
import com.phonedsh.domain.connector.DeviceConnectionEntry
import com.phonedsh.domain.connector.DeviceConnectionSnapshot
import com.phonedsh.domain.connector.DeviceOpenFailure
import com.phonedsh.domain.connector.DeviceTransmission
import com.phonedsh.domain.connector.DeviceTransmissionRow
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import com.phonedsh.ui.theme.generated.UiTokenNames

/**
 * What the 真实连接设备 section shows.
 *
 * The three cases are mutually exclusive on purpose: "there is no connector" is not
 * "the connector published nothing", which is not "here are the devices".
 */
public enum class DeviceListState {
    /** No connector was injected; the page renders the baseline's unavailable body. */
    UNAVAILABLE,

    /** A connector is injected and published no device. */
    EMPTY,

    /** The connector published at least one device. */
    READY,
}

/** The connection page's local UI state: the row whose session is being opened. */
public data class DeviceConnectionLocalState(
    public val openingDeviceId: String? = null,
)

/**
 * The connection page state.
 *
 * @param failure the one line above the list: an open failure first, then the
 *   connector's own reported failure. `null` when neither applies.
 * @param canForget whether a saved mapping exists for the device the failure names.
 * @param connectorProtocol the injected connector's protocol id, or `null`.
 */
public data class DeviceConnectionScreenState(
    public val list: DeviceListState,
    public val devices: List<DeviceConnectionEntry>,
    public val connectorProtocol: String?,
    public val transmissions: List<DeviceTransmissionRow>,
    public val status: ConnectorStatus,
    public val failure: String?,
    public val failedDeviceId: String?,
    public val canForget: Boolean,
    public val openingDeviceId: String?,
) {
    public companion object {
        /**
         * Projects the source's snapshot into the page state.
         *
         * Pure: the same snapshot and local script always render the same rows, so a
         * JVM test replays a connector's state machine without a device.
         */
        public fun from(
            snapshot: DeviceConnectionSnapshot,
            local: DeviceConnectionLocalState = DeviceConnectionLocalState(),
        ): DeviceConnectionScreenState {
            val list = when {
                !snapshot.connectorAvailable -> DeviceListState.UNAVAILABLE
                snapshot.devices.isEmpty() -> DeviceListState.EMPTY
                else -> DeviceListState.READY
            }
            return DeviceConnectionScreenState(
                list = list,
                devices = snapshot.devices,
                connectorProtocol = snapshot.connectorProtocol,
                transmissions = snapshot.transmissions,
                status = snapshot.status,
                failure = failureLine(snapshot),
                failedDeviceId = snapshot.failedDeviceId,
                canForget = snapshot.canForget(snapshot.failedDeviceId),
                openingDeviceId = local.openingDeviceId,
            )
        }

        /** The page's failure sentence for the snapshot's own reason, if it has one. */
        private fun failureLine(snapshot: DeviceConnectionSnapshot): String? =
            when (val open = snapshot.openFailure) {
                is DeviceOpenFailure.Unavailable -> UiStrings.DEVICE_OPEN_FAILED_UNAVAILABLE
                is DeviceOpenFailure.Mismatch ->
                    UiStrings.DEVICE_OPEN_FAILED_MISMATCH.format(open.expected, open.actual)
                is DeviceOpenFailure.Failed -> UiStrings.DEVICE_OPEN_FAILED.format(open.detail)
                null -> snapshot.connectorFailure?.let {
                    UiStrings.DEVICE_CONNECTOR_FAILED.format(it.detail)
                }
            }
    }
}

/**
 * The connection page.
 *
 * @param state the pure screen state ([DeviceConnectionScreenState.from]).
 * @param onOpen open the device's fixed session; the page disables every row while
 *   one open is in flight, as the baseline does.
 * @param onForgetMapping drop the saved mapping the failure names.
 * @param onDone close the sheet (the baseline's 完成).
 */
@Composable
public fun NativeDeviceConnectionPage(
    state: DeviceConnectionScreenState,
    onOpen: (DeviceConnectionEntry) -> Unit,
    onForgetMapping: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    PageFrame(
        title = UiStrings.DEVICE_TITLE,
        rootTag = UiPageTags.DEVICE_ROOT,
        modifier = modifier,
        actions = {
            SessionTextAction(
                label = UiStrings.DEVICE_DONE,
                onClick = onDone,
                tone = SessionActionTone.ACCENT,
            )
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.failure != null) {
                item(key = "failure") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SessionFailureLine(
                            message = state.failure,
                            tag = UiPageTags.DEVICE_FAILURE,
                        )
                        val failed = state.failedDeviceId
                        if (state.canForget && failed != null) {
                            // 解除该设备的会话映射 (`NativeDeviceConnectionPage.swift:93`).
                            // The same shape as the page's text actions — 44 pt floor,
                            // accent ink, one line — written out here instead of going
                            // through `SessionTextAction`: the structure checker locates
                            // an untitled group's criterion row by scanning *this* file
                            // for a `Text`/`BasicText` literal, and the shared
                            // component's `BasicText(text = label)` lives in
                            // `SessionPageChrome.kt`, so the failure group used to read
                            // as absent even though the action itself was found.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(UiPageTags.DEVICE_FORGET)
                                    .clickable(role = Role.Button) { onForgetMapping(failed) }
                                    .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
                                    .padding(horizontal = tokens.geometry.pageOuterMarginH)
                                    .semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    text = UiStrings.DEVICE_FORGET_MAPPING,
                                    style = type.secondary.copy(color = tokens.colors.accent),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "devices-header") {
                SessionSectionLabel(UiStrings.DEVICE_SECTION_DEVICES)
            }
            item(key = "connector-source") {
                // Not in the iOS page: that page can say "no connector" because its
                // factory is nil. This host injects a connector, so the page names
                // which one, and a reader can tell a double from a real transport.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiPageTags.DEVICE_SOURCE)
                        .defaultMinSize(minHeight = tokens.geometry.pageListRowMinHeight)
                        .padding(
                            horizontal = tokens.geometry.pageOuterMarginH,
                            vertical = tokens.spacing.iconText,
                        )
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
                ) {
                    BasicText(
                        text = UiStrings.DEVICE_SOURCE_LABEL,
                        style = type.caption.copy(color = tokens.colors.tertiary),
                        modifier = Modifier.weight(1f),
                    )
                    BasicText(
                        text = state.connectorProtocol ?: UiStrings.DEVICE_SOURCE_NONE,
                        style = type.caption.copy(color = tokens.colors.secondary),
                    )
                }
            }

            when (state.list) {
                DeviceListState.UNAVAILABLE -> item(key = "unavailable") {
                    DeviceUnavailable(
                        title = UiStrings.DEVICE_CONNECTOR_UNAVAILABLE,
                        hint = UiStrings.DEVICE_CONNECTOR_UNAVAILABLE_HINT,
                        tag = UiPageTags.DEVICE_UNAVAILABLE,
                    )
                }

                DeviceListState.EMPTY -> item(key = "empty") {
                    DeviceUnavailable(
                        title = UiStrings.DEVICE_NO_DEVICES,
                        hint = UiStrings.DEVICE_NO_DEVICES_HINT,
                        tag = UiPageTags.DEVICE_EMPTY,
                    )
                }

                DeviceListState.READY -> items(
                    count = state.devices.size,
                    key = { index -> "device-${state.devices[index].deviceId}" },
                ) { index ->
                    val device = state.devices[index]
                    DeviceRow(
                        device = device,
                        opening = state.openingDeviceId == device.deviceId,
                        enabled = state.openingDeviceId == null,
                        onOpen = { onOpen(device) },
                    )
                    SessionHairline()
                }
            }

            item(key = "transport-header") {
                SessionSectionLabel(UiStrings.DEVICE_SECTION_TRANSPORT)
            }
            items(count = state.transmissions.size) { index ->
                TransportRow(state.transmissions[index])
            }
            item(key = "transport-footer") {
                BasicText(
                    text = UiStrings.DEVICE_TRANSPORT_FOOTER,
                    style = type.caption.copy(color = tokens.colors.tertiary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiPageTags.DEVICE_TRANSPORT_FOOTER)
                        .padding(
                            horizontal = tokens.geometry.pageOuterMarginH,
                            vertical = tokens.spacing.iconText,
                        ),
                )
            }
        }
    }
}

/**
 * One device row: the online dot, the display name, the device id and the chevron.
 *
 * The row is disabled while any open is in flight, which is the baseline's
 * `.disabled(openingDeviceID != nil)`: two opens at once would race two mappings.
 */
@Composable
private fun DeviceRow(
    device: DeviceConnectionEntry,
    opening: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.DEVICE_ROW)
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.spacing.iconText,
            )
            .semantics(mergeDescendants = true) { contentDescription = device.displayName },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        // The baseline's `iphone` glyph with its online dot; the dot's diameter is
        // the token the status surfaces already use, so no size is re-typed.
        Box(
            modifier = Modifier
                .size(tokens.geometry.statusDotDiameter)
                .clip(CircleShape)
                .background(tokens.colors.success),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetV),
        ) {
            BasicText(
                text = device.displayName,
                style = type.body.copy(color = tokens.colors.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = device.deviceId,
                style = type.caption.copy(color = tokens.colors.tertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicText(
            text = if (opening) OPENING_GLYPH else CHEVRON_GLYPH,
            style = type.caption.copy(
                color = if (opening) tokens.colors.accent else tokens.colors.tertiary,
            ),
        )
    }
}

/** One transport row: its name, and whether this host really has it. */
@Composable
private fun TransportRow(row: DeviceTransmissionRow) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val label = when (row.transmission) {
        DeviceTransmission.BLUETOOTH -> UiStrings.DEVICE_BLUETOOTH_CONNECTOR
        DeviceTransmission.LOCAL_NETWORK -> UiStrings.DEVICE_LOCAL_NETWORK_CONNECTOR
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.DEVICE_TRANSPORT_ROW)
            .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.spacing.iconText,
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        BasicText(
            text = label,
            style = type.body.copy(color = tokens.colors.primary),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = if (row.available) {
                UiStrings.DEVICE_TRANSPORT_PROVIDED
            } else {
                UiStrings.DEVICE_TRANSPORT_NOT_PROVIDED
            },
            style = type.secondary.copy(
                color = if (row.available) tokens.colors.success else tokens.colors.tertiary,
            ),
        )
    }
}

/** The baseline's `ContentUnavailableView`: a centred title and its explanation. */
@Composable
private fun DeviceUnavailable(title: String, hint: String, tag: String) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.geometry.cardOuterMarginV,
            )
            .semantics(mergeDescendants = true) { contentDescription = "$title. $hint" },
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        BasicText(text = title, style = type.body.copy(color = tokens.colors.secondary))
        BasicText(text = hint, style = type.caption.copy(color = tokens.colors.tertiary))
    }
}

/**
 * The token inventory of this page; `DeviceConnectorPagesTokenTest` fails when a use
 * site reads a token that is not listed here.
 */
internal object DeviceConnectionPageTokens {
    val refs: List<String> = listOf(
        UiTokenNames.COLORS_PRIMARY,
        UiTokenNames.COLORS_SECONDARY,
        UiTokenNames.COLORS_TERTIARY,
        UiTokenNames.COLORS_ACCENT,
        UiTokenNames.COLORS_SUCCESS,
        UiTokenNames.TYPOGRAPHY_BODY,
        UiTokenNames.TYPOGRAPHY_SECONDARY,
        UiTokenNames.TYPOGRAPHY_CAPTION,
        UiTokenNames.SPACING_ICON_TEXT,
        UiTokenNames.SPACING_BADGE_INSET_V,
        UiTokenNames.GEOMETRY_PAGE_OUTER_MARGIN_H,
        UiTokenNames.GEOMETRY_PAGE_ROW_MIN_HEIGHT,
        UiTokenNames.GEOMETRY_PAGE_LIST_ROW_MIN_HEIGHT,
        UiTokenNames.GEOMETRY_STATUS_DOT_DIAMETER,
        UiTokenNames.GEOMETRY_CARD_OUTER_MARGIN_V,
    )
}

/** The disclosure chevron; the same glyph the other page rows use. */
private const val CHEVRON_GLYPH: String = "›"

/** The in-flight marker of the row whose session is opening. */
private const val OPENING_GLYPH: String = "…"
