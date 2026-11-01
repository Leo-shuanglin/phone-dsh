// The shell's drawer: the one map of the app a user can actually tap.
// SPDX-License-Identifier: MIT
//
// Spec: `spec/ui/v1/README.md` §1's 入口 column. That column names three routes
// (工作文件夹, 定时任务, 设置) and leaves the rest to page-local entries. On the
// iOS baseline the drawer is the session list with two furniture rows at the
// bottom (`NativeShell.swift:276`–`:317`: SessionsDrawer = SessionsPage +
// 定时任务 + 设置), so a page that has no other entry inside the app has no
// touch path at all. This host has no tab bar and no session-list drawer body,
// so the drawer is a plain navigation list: every row a user needs is here.
//
// Geometry is the iOS drawer's, not an Android invention:
//
//   * panel 320 pt (`NativeShell.swift:97` `private let width: CGFloat = 320`);
//   * row content 288 pt = 320 - 16 - 16: the drawer row insets 16 on both sides
//     (`NativeShell.swift:41` `horizontalInset` / `:432`; the 288 is written out
//     at `SessionsPage.swift:593`);
//   * furniture row 48 pt tall (`NativeShell.swift:433`) and a 22 pt icon slot
//     (`:422`);
//   * icon/title gap 10 pt (`:419`), chevron reference 12 pt (`:428`–`:429`).
//
// All five numbers are read from [SessionsMetrics]; this file carries no second
// copy of them. The only unmeasured value is the 22 pt corner on the panel's
// trailing edge (`NativeShell.swift:254`): the token table publishes no
// drawer-radius token, so the literal is marked here.
//
// The drawer is shell wiring, not a page: it takes labelled [PdshDrawerEntry]
// values and hands the selected route back. It never reads the engine, a clock
// or the filesystem, so a JVM test can tap every row and assert the route.

package com.phonedsh.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonedsh.ui.SessionsMetrics
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import kotlin.math.cos
import kotlin.math.sin

/**
 * One row's glyph.
 *
 * The baseline uses SF Symbols (`clock.arrow.circlepath`, `gearshape`); this app
 * has no icon pack dependency, so each glyph is drawn from the same 22 x 22 box
 * the baseline reserves for it. The shape names the destination, never states.
 */
public enum class PdshDrawerIcon {
    /** 对话: a chat bubble. */
    CHAT,

    /** 会话: a list of sessions. */
    SESSIONS,

    /** 工作文件夹: a folder. */
    FOLDER,

    /** 命令: a terminal window. */
    TERMINAL,

    /** 设备连接: a device. */
    DEVICE,

    /** DSH 页面: a globe. */
    WEB,

    /** 定时任务: a clock. */
    CLOCK,

    /** 设置: a gear. */
    SETTINGS,
}

/**
 * One tappable drawer row.
 *
 * @param label the visible text; it is also the row's accessibility name.
 * @param icon the glyph in the 22 pt slot.
 * @param route the route the row pushes.
 */
public data class PdshDrawerEntry(
    public val label: String,
    public val icon: PdshDrawerIcon,
    public val route: PdshRoute,
)

/** Stable test tags, so a reachability test names the row it taps. */
public object PdshDrawerTags {
    /** The whole drawer overlay (panel + scrim strip). */
    public const val ROOT: String = "pdsh.drawer"

    /** The 320 pt panel itself. */
    public const val PANEL: String = "pdsh.drawer.panel"

    /** The dimmed page strip on the right; tapping it closes the drawer. */
    public const val SCRIM: String = "pdsh.drawer.scrim"

    /** One row, suffixed with the row's label. */
    public const val ROW_PREFIX: String = "pdsh.drawer.row."

    /** One glyph slot, suffixed with the row's label. */
    public const val ICON_PREFIX: String = "pdsh.drawer.icon."
}

/**
 * The drawer overlay.
 *
 * @param entries the app-map rows, top to bottom.
 * @param footerEntries the furniture rows pinned to the bottom (定时任务, 设置
 *   in the baseline); an empty list drops the divider and the bottom group.
 * @param onSelect the route whose row was tapped.
 * @param onDismiss close without navigating (the scrim strip).
 */
@Composable
public fun PdshDrawer(
    entries: List<PdshDrawerEntry>,
    footerEntries: List<PdshDrawerEntry>,
    onSelect: (PdshRoute) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(PdshDrawerTags.ROOT)) {
        // iOS is 320 on every phone it ships on; a narrower window (a split
        // screen, a small device) keeps a strip of the page visible instead of
        // letting the drawer cover the whole frame.
        val panelWidth: Dp = minOf(SessionsMetrics.drawerWidth, maxWidth - MIN_PAGE_STRIP)
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    // The baseline rounds the two trailing corners 22 pt. The
                    // background shape draws that edge; the panel is not
                    // `clip`ped, because a rounded clip layer swallows injected
                    // touches in the JVM renderer and the rows must stay tappable
                    // in the tests that prove this drawer's reachability.
                    .background(tokens.colors.input, DRAWER_PANEL_SHAPE)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag(PdshDrawerTags.PANEL),
            ) {
                Spacer(Modifier.height(PANEL_TOP_INSET))
                entries.forEach { entry ->
                    PdshDrawerRow(entry = entry, onClick = { onSelect(entry.route) })
                }
                Spacer(Modifier.weight(1f))
                if (footerEntries.isNotEmpty()) {
                    DrawerDivider()
                    footerEntries.forEach { entry ->
                        PdshDrawerRow(entry = entry, onClick = { onSelect(entry.route) })
                    }
                    Spacer(Modifier.height(PANEL_BOTTOM_INSET))
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // unverified token: no scrim-opacity token exists; the page
                    // ink at a fixed opacity is the only non-invented dimming
                    // the token table has (the same choice the sheet frame makes).
                    .background(tokens.colors.primary.copy(alpha = SCRIM_ALPHA))
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = DISMISS_LABEL }
                    .testTag(PdshDrawerTags.SCRIM),
            )
        }
    }
}

/** One 48 pt row: 22 pt glyph, 10 pt gap, title, 12 pt chevron. */
@Composable
private fun PdshDrawerRow(entry: PdshDrawerEntry, onClick: () -> Unit) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SessionsMetrics.drawerRowHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = entry.label }
            .testTag(PdshDrawerTags.ROW_PREFIX + entry.label)
            // 320 - 16 - 16 = 288: the measured drawer content width.
            .padding(horizontal = SessionsMetrics.rowInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SessionsMetrics.drawerRowSpacing),
    ) {
        DrawerGlyph(
            icon = entry.icon,
            color = tokens.colors.primary,
            modifier = Modifier.testTag(PdshDrawerTags.ICON_PREFIX + entry.label),
        )
        BasicText(
            text = entry.label,
            // iOS draws this row at 17 pt regular; the token table's closest
            // regular style is subheadline (15 pt). The row height, not the text
            // size, is the measured number this lane aligns.
            style = type.subheadline.copy(color = tokens.colors.primary),
            modifier = Modifier.weight(1f),
        )
        DrawerChevron(color = tokens.colors.tertiary)
    }
}

/** The 22 pt glyph slot the baseline reserves (`NativeShell.swift:422`). */
@Composable
private fun DrawerGlyph(icon: PdshDrawerIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.size(SessionsMetrics.drawerRowIconSlot),
    ) {
        // Every glyph is authored in a 22 x 22 unit box and scaled to the slot,
        // so the drawn mark keeps the baseline's slot even at another density.
        val unit = size.minDimension / ICON_UNITS
        val stroke = Stroke(
            width = ICON_STROKE_UNITS * unit,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (icon) {
            PdshDrawerIcon.CHAT -> {
                val bubble = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = 3.5f * unit,
                            top = 4f * unit,
                            right = 18.5f * unit,
                            bottom = 15.5f * unit,
                            radiusX = 3f * unit,
                            radiusY = 3f * unit,
                        ),
                    )
                }
                drawPath(bubble, color, style = stroke)
                val tail = Path().apply {
                    moveTo(7.5f * unit, 15.5f * unit)
                    lineTo(7.5f * unit, 19f * unit)
                    lineTo(12f * unit, 15.5f * unit)
                }
                drawPath(tail, color, style = stroke)
            }

            PdshDrawerIcon.SESSIONS -> {
                drawLine(color, p(5f, 7f, unit), p(17f, 7f, unit), stroke.width, StrokeCap.Round)
                drawLine(color, p(5f, 11f, unit), p(17f, 11f, unit), stroke.width, StrokeCap.Round)
                drawLine(color, p(5f, 15f, unit), p(13f, 15f, unit), stroke.width, StrokeCap.Round)
            }

            PdshDrawerIcon.FOLDER -> {
                val folder = Path().apply {
                    moveTo(3.5f * unit, 7f * unit)
                    lineTo(8.5f * unit, 7f * unit)
                    lineTo(10.5f * unit, 9.5f * unit)
                    lineTo(18.5f * unit, 9.5f * unit)
                    lineTo(18.5f * unit, 17.5f * unit)
                    lineTo(3.5f * unit, 17.5f * unit)
                    close()
                }
                drawPath(folder, color, style = stroke)
            }

            PdshDrawerIcon.TERMINAL -> {
                drawRoundRect(
                    color = color,
                    topLeft = p(3.5f, 4.5f, unit),
                    size = androidx.compose.ui.geometry.Size(15f * unit, 13f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
                    style = stroke,
                )
                val chevron = Path().apply {
                    moveTo(7f * unit, 8.5f * unit)
                    lineTo(10f * unit, 11f * unit)
                    lineTo(7f * unit, 13.5f * unit)
                }
                drawPath(chevron, color, style = stroke)
                drawLine(color, p(11.5f, 13.5f, unit), p(15f, 13.5f, unit), stroke.width, StrokeCap.Round)
            }

            PdshDrawerIcon.DEVICE -> {
                drawRoundRect(
                    color = color,
                    topLeft = p(7f, 3.5f, unit),
                    size = androidx.compose.ui.geometry.Size(8f * unit, 15f * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
                    style = stroke,
                )
                drawLine(color, p(10f, 6f, unit), p(12f, 6f, unit), stroke.width, StrokeCap.Round)
                drawCircle(color, radius = 0.9f * unit, center = p(11f, 15.5f, unit))
            }

            PdshDrawerIcon.WEB -> {
                drawCircle(color, radius = 7.5f * unit, center = p(11f, 11f, unit), style = stroke)
                drawLine(color, p(3.5f, 11f, unit), p(18.5f, 11f, unit), stroke.width)
                drawOval(
                    color = color,
                    topLeft = p(6.5f, 3.5f, unit),
                    size = androidx.compose.ui.geometry.Size(9f * unit, 15f * unit),
                    style = stroke,
                )
            }

            PdshDrawerIcon.CLOCK -> {
                drawCircle(color, radius = 7.5f * unit, center = p(11f, 11f, unit), style = stroke)
                drawLine(color, p(11f, 11f, unit), p(11f, 6.5f, unit), stroke.width, StrokeCap.Round)
                drawLine(color, p(11f, 11f, unit), p(14.5f, 13f, unit), stroke.width, StrokeCap.Round)
            }

            PdshDrawerIcon.SETTINGS -> {
                drawCircle(color, radius = 7.5f * unit, center = p(11f, 11f, unit), style = stroke)
                drawCircle(color, radius = 2.5f * unit, center = p(11f, 11f, unit), style = stroke)
                repeat(GEAR_TEETH) { index ->
                    val angle = (index.toFloat() / GEAR_TEETH) * TWO_PI
                    val inner = 5.5f * unit
                    val outer = 7.5f * unit
                    val center = p(11f, 11f, unit)
                    drawLine(
                        color = color,
                        start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                        end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/** A 22 x 22 unit point scaled to the glyph slot. */
private fun p(x: Float, y: Float, unit: Float): Offset = Offset(x * unit, y * unit)

/** The 12 pt chevron at the row's trailing edge (`NativeShell.swift:428`). */
@Composable
private fun DrawerChevron(color: Color) {
    Canvas(Modifier.size(SessionsMetrics.drawerRowChevron)) {
        val unit = size.minDimension / 12f
        val path = Path().apply {
            moveTo(4.5f * unit, 2.5f * unit)
            lineTo(8f * unit, 6f * unit)
            lineTo(4.5f * unit, 9.5f * unit)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** The hairline above the furniture group (`NativeShell.swift:293` Divider). */
@Composable
private fun DrawerDivider() {
    val tokens = LocalUiTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(SessionsMetrics.separatorHeight)
            .background(tokens.colors.borderLight),
    )
}

/** How far left of a narrow window the drawer's page strip starts. */
private val MIN_PAGE_STRIP: Dp = 40.dp

/** unverified geometry: no drawer-radius token; the baseline rounds 22 pt on the right (`NativeShell.swift:254`). */
private val DRAWER_PANEL_SHAPE = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)

/** iOS's panel starts just under the safe-area top; 8 pt keeps the first row off the bar. */
private val PANEL_TOP_INSET: Dp = 8.dp

/** iOS pads the bottom furniture group by `bottomInset + 16` (`NativeShell.swift:306`). */
private val PANEL_BOTTOM_INSET: Dp = 16.dp

/** unverified token: no scrim-opacity token exists; the sheet frame uses the same value. */
private const val SCRIM_ALPHA: Float = 0.32f

/** The glyph authoring box; the drawn mark is 22 x 22 units. */
private const val ICON_UNITS: Float = 22f

/** 1.5 pt stroke at the 22 unit authoring size. */
private const val ICON_STROKE_UNITS: Float = 1.5f

/** The gear's spoke count. */
private const val GEAR_TEETH: Int = 8

/** One turn in radians. */
private const val TWO_PI: Float = (Math.PI * 2).toFloat()

/** The scrim strip's spoken name. */
private const val DISMISS_LABEL: String = "关闭抽屉"
