// The Settings family's iOS-measured list chrome.
// SPDX-License-Identifier: MIT
//
// Why this file exists: `PageScaffold.kt` (the generic page frame + `InfoRow`)
// is another lane's file and is shared with chat/sessions/files, so it cannot
// grow Settings-only geometry. The Settings pages therefore own their chrome
// here, in the surface package, once — instead of each page re-typing a row.
//
// **Which baseline.** The Settings page was rewritten in iOS PR #13
// (`SettingsPage.swift`, 255 lines) into a five-tab `Picker(.segmented)` over an
// inset-grouped `List`. The chrome in the first half of this file is that
// *current* page: 52.33 pt rows, 1.0 pt separators, 17 pt labels **and values**,
// 26 pt cards, a 32 pt segmented track. Every one of those numbers is read from
// `com.phonedsh.ui.SettingsMetrics`, which carries the measurement and its PNG
// coordinate; this file must not re-type one.
//
// What is **gone** from the old flat list (and therefore from this file): the
// 44 pt row, the 0.33 pt hairline, the 15 pt value, the 20 pt chevron slot
// pinned to a 350 pt value edge, and the `x 32.7 / 24 above / 8 below` group
// header. Those were measured on `ios-settings.png`, a pre-#13 screenshot of a
// layout the Swift source no longer renders. `SettingsMetrics`' header records
// the same removal; keeping them here would have made a stale baseline look
// authoritative.
//
// The six sub-pages (`AboutPage`, `ModelProviderSettingsPage`,
// `WebSearchSettingsPage`, `EgressAllowListPage`, `RuntimeDetailsPage`,
// `NativeConfigurationPage`, `MCPSettingsPage`) were **not** rewritten by
// PR #13: their Swift sources still render the plain `List` they always did, so
// their geometry comes from the six `docs/receipts/ios-baseline-screens/
// ios-settings-*.png` and not from `SettingsMetrics`. Those measurements live in
// the second half of this file, each with the PNG coordinate it came from.
//
// Token discipline: colours and the system type roles (17/600, 17/400, 13/400)
// come from `LocalUiTokens`/`LocalUiType`. Only values no token declares (the
// measured insets, the chevron glyph, the switch) are page-layer constants, each
// marked `unverified geometry` with the measurement it reproduces. The token
// table's own deltas against the baseline (#E4E4E8 vs #E8E8E8 separator,
// #3964FE vs #2F5BFF accent, #111114 vs #000000 primary ink) are *not* papered
// over here: they are spec-level and reported as a gap, not re-typed as
// literals.
package com.phonedsh.ui.surface

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonedsh.ui.SettingsMetrics
import com.phonedsh.ui.page.PageTone
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import com.phonedsh.ui.theme.generated.UiTokenData
import com.phonedsh.ui.theme.style

// ---------------------------------------------------------------------------
// Current grouped-list geometry: read from SettingsMetrics, never re-typed
// ---------------------------------------------------------------------------

/** One grouped row: 52.33 pt, measured four times in `settings-chat.png`. */
public val SettingsGroupedRowHeight: Dp get() = SettingsMetrics.rowHeight

/** The 1.0 pt separator (3 px @3x), measured at y=760/1179/… in `settings-chat.png`. */
public val SettingsSeparatorThickness: Dp get() = SettingsMetrics.SEPARATOR_HEIGHT_PT.dp

/** The separator's 16 pt inset inside a card (card x 48 px → line x 96 px). */
public val SettingsSeparatorInset: Dp get() = SettingsMetrics.separatorInset

/** The card's 16 pt side margin (card x 48..1157 px on a 402 pt screen). */
public val SettingsCardMargin: Dp get() = SettingsMetrics.cardMargin

/** The card's 26 pt continuous corner, measured by fitting the top-left arc. */
public val SettingsCardRadius: Dp get() = SettingsMetrics.cardRadius

/** The 38.67 pt gap between two cards (116 px at y=914→1030). */
public val SettingsCardGap: Dp get() = SettingsMetrics.SECTION_GAP_PT.dp

/** The 34.67 pt from the list's top edge to the first card (y=502→606 px). */
public val SettingsListTopInset: Dp get() = SettingsMetrics.FIRST_CARD_TOP_INSET_PT.dp

/** The 39.33 pt from a card's trailing edge to a row value's right edge. */
public val SettingsValueTrailingInset: Dp get() = SettingsMetrics.VALUE_TRAILING_INSET_PT.dp

/** The 14 pt from a card's trailing edge to the switch (switch right 1115 px). */
public val SettingsSwitchTrailingInset: Dp get() = SettingsMetrics.SWITCH_TRAILING_INSET_PT.dp

/** The 20.67 pt slot between a value's right edge and the chevron's right edge. */
public val SettingsChevronSlotWidth: Dp get() = SettingsMetrics.VALUE_CHEVRON_SLOT_PT.dp

/** The segmented track: 32 pt tall, 16 pt inset, capsule (r = height / 2). */
public val SettingsSegmentHeight: Dp get() = SettingsMetrics.segmentedHeight
public val SettingsSegmentInset: Dp get() = SettingsMetrics.segmentedInset
public val SettingsSegmentRadius: Dp get() = SettingsMetrics.SEGMENTED_RADIUS_PT.dp

/** The 20 pt from the 44 pt bar's bottom edge to the segmented track's top. */
public val SettingsSegmentTopGap: Dp get() = SettingsMetrics.SEGMENTED_TOP_GAP_PT.dp

/**
 * The 15 pt vertical padding iOS's segmented control puts around its 32 pt
 * track: the page declares 10 (`SettingsPage.swift:70`) and the bar-to-track gap
 * is measured at 20, so 10 of it is the page's own padding and the other 10 is
 * the navigation content inset `SettingsMetrics.SEGMENTED_TOP_GAP_PT` splits.
 */
public val SettingsSegmentPaddingV: Dp get() = SettingsMetrics.SEGMENTED_PADDING_V_PT.dp

// ---------------------------------------------------------------------------
// Sub-page geometry: measured on the six `ios-settings-*.png` sub-page shots
// ---------------------------------------------------------------------------

/**
 * unverified geometry: the large title's 17 pt leading inset.
 *
 * `ios-settings-model.png` / `-plugins` / `-runtime` / `-search`: the 34 pt CJK
 * title's ink starts at x=51 px, 51..55 px across the four shots → 17 pt, which
 * is iOS's large-title inset. `tokens.json` has no such length.
 */
public val SettingsLargeTitleInset: Dp = 17.dp

/**
 * unverified geometry: the large title's 14 pt gap below the 44 pt bar.
 *
 * The four large-title shots put the ink box at y=376..465 px = 125.67..155 pt.
 * The bar ends at 62 + 44 = 106 pt, so the ink starts 19.67 pt below it; a 34 pt
 * line box puts ~5.5 pt of ascent above the CJK ink, so the *block* starts 14 pt
 * below the bar. Derived, not measured: the ink box is the measurement, the
 * block offset is arithmetic on it.
 */
public val SettingsLargeTitleTopGap: Dp = 14.dp

/**
 * unverified geometry: the section label's 33 pt indent on the sub-pages.
 *
 * `ios-settings-model.png` `服务` ink x=99 px → 33 pt; `-search`'s
 * `用哪一份搜索` and `-plugins`' `内置插件设置` sit on the same 33 pt line.
 * `-network`'s `地址` sits at 32.7 pt, inside the measurement's rounding.
 */
public val SettingsSectionLabelIndent: Dp = 33.dp

/**
 * unverified geometry: the sub-page row content's 33 pt indent for an
 * icon-bearing row (model / plugins), and 17 pt for a plain row (network).
 *
 * `ios-settings-model.png`: the action rows' labels start at x=216 px = 72 pt
 * after a 33 pt icon; `-network`'s host labels start at x=51 px = 17 pt.
 */
public val SettingsIconRowLabelIndent: Dp = 72.dp

/**
 * unverified geometry: the sub-page separator's leading inset.
 *
 * `-network` hairlines run x=48..1109 px → 16..369.67 pt; `-model` / `-plugins`
 * run x=216..1109 px → 72..369.67 pt (they begin where the label does, i.e.
 * after the 33 pt icon column); `-runtime` runs x=48..1157 px → 16..385.67 pt.
 * The trailing inset is therefore 32.33 pt on four of the six and 16 pt on
 * `-runtime`.
 */
public val SettingsSubPageSeparatorTrailingInset: Dp = 32.33f.dp

/** unverified geometry: `-runtime` runs its hairlines to a 16 pt trailing inset. */
public val SettingsSubPageSeparatorTrailingInsetWide: Dp = 16.dp

/** unverified geometry: the 6.33 x 11.33 pt `chevron.right` glyph (x 1083..1101 px). */
private val ChevronWidth: Dp = SettingsMetrics.CHEVRON_WIDTH_PT.dp
private val ChevronHeight: Dp = SettingsMetrics.CHEVRON_HEIGHT_PT.dp
private val ChevronStroke: Dp = 2.dp

/** unverified geometry: the 16 pt `chevron.up.chevron.down` on a menu row. */
private val MenuChevronWidth: Dp = 10.dp
private val MenuChevronHeight: Dp = 16.dp
private val MenuChevronStroke: Dp = 2.dp

/** unverified geometry: the iOS 26 switch, 63 x 29.67 pt with a 26 pt thumb. */
public val SettingsSwitchWidth: Dp get() = SettingsMetrics.switchWidth
public val SettingsSwitchHeight: Dp get() = SettingsMetrics.switchHeight
private val SettingsSwitchThumb: Dp = 26.dp
private val SettingsSwitchTrackRadius: Dp = 14.5.dp

// ---------------------------------------------------------------------------
// Type roles
// ---------------------------------------------------------------------------

/**
 * The row label: 17 pt regular — `typography.rowLabel`, added to
 * `spec/ui/v1/tokens.json` by L9 while this lane was running.
 *
 * Before that token existed this role re-used `headline` (17/600) and forced the
 * weight back to 400; the size came from the token and the weight from the
 * baseline. Now both come from one token.
 *
 * The line height is stated rather than inherited: the row is 52.33 pt with a
 * single 17 pt line (52.33 - 2 × 16 measured padding = 20.33), and stating it
 * also keeps the row's height independent of a text engine whose default line
 * box is wider than the platform's (Robolectric measures the same string at
 * 35 pt, which is why the JVM row was 59 pt tall before this was stated).
 */
@Composable
public fun settingsLabelStyle(): TextStyle =
    UiTokenData.typeRowLabel.style().copy(lineHeight = SETTINGS_LABEL_LINE_HEIGHT)

/**
 * The row value: 17 pt regular, secondary ink.
 *
 * The pre-#13 flat list used a 15 pt value; the current grouped list measures
 * 17 (`SettingsMetrics.ROW_VALUE_REF_PT`: the `未设置` ink box is 45 px = 15 pt,
 * i.e. a 17 pt font). The two are not interchangeable, and this one is the
 * measured one.
 */
@Composable
public fun settingsValueStyle(): TextStyle =
    UiTokenData.typeRowLabel.style().copy(lineHeight = SETTINGS_LABEL_LINE_HEIGHT)

/**
 * The footnote: 13 pt regular, secondary ink (`.font(.footnote)` at
 * `SettingsPage.swift:105,127`; ink 35 px = 11.7 pt).
 */
@Composable
public fun settingsFootnoteStyle(): TextStyle =
    LocalUiType.current.secondary.copy(lineHeight = SETTINGS_FOOTNOTE_LINE_HEIGHT)

/**
 * The sub-pages' section label: 17 pt regular, secondary ink.
 *
 * Measured, not inferred from the old page: `ios-settings-model.png` `服务`'s
 * ink box is 46 px = 15.33 pt, the same box a 17 pt CJK row label produces. The
 * pre-#13 index's 13 pt group header is a different (removed) element; the
 * current general tab has **no** section headers at all, only footnotes.
 */
@Composable
public fun settingsSectionLabelStyle(): TextStyle =
    UiTokenData.typeRowLabel.style().copy(lineHeight = SETTINGS_LABEL_LINE_HEIGHT)

/**
 * The baseline's large title: 34 pt bold at x 17 pt with its ink top at 125.67 pt.
 *
 * The `typography.largeTitle` (34/700) token landed in `spec/ui/v1/tokens.json`
 * while this lane was running (L9), so this reads it — through the generated
 * `UiTokenData` rather than through `LocalUiType`, because `UiType`'s twelve
 * roles are another lane's file and do not expose it yet. When
 * `UiType.largeTitle` exists this becomes `LocalUiType.current.largeTitle` and
 * nothing else changes.
 *
 * Before the token existed this role re-used `headline` (17/600) and said so; the
 * measured block (17 pt inset, 14 pt below the 44 pt bar) was already right, it
 * was only the glyph size that was not.
 */
@Composable
public fun settingsLargeTitleStyle(): TextStyle =
    UiTokenData.typeLargeTitle.style().copy(lineHeight = SETTINGS_LARGE_TITLE_LINE_HEIGHT)

/** unverified geometry: 52.33 - 2 × 16 = 20.33 pt of line box for a 17 pt label. */
private val SETTINGS_LABEL_LINE_HEIGHT: TextUnit = 20.33.sp

/** unverified geometry: 13 pt footnote in a 16 pt line box (two-line footnotes). */
private val SETTINGS_FOOTNOTE_LINE_HEIGHT: TextUnit = 16.sp

/**
 * unverified geometry: the 34 pt large title's line box. 34 × 1.2 = 40.8 pt is
 * the platform's default line height; until `typography.largeTitle` exists the
 * text is 17 pt, so this box is deliberately the *measured* 34 pt one and the
 * title renders as a stand-in inside it.
 */
private val SETTINGS_LARGE_TITLE_LINE_HEIGHT: TextUnit = 40.8.sp

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

/**
 * The 44 pt nav bar: optional leading action, centred 17 pt semibold title,
 * optional trailing action (the baseline centres 通用设置 between nothing and
 * 完成).
 *
 * @param title the inline title; `null` on a page whose title is a large title
 *   drawn in the content instead.
 * @param onBack when set, draws the baseline's leading back chevron.
 * @param backTag an optional stable test tag for the back control.
 * @param leading an optional leading slot that replaces the back chevron.
 * @param trailing an optional trailing slot.
 */
@Composable
public fun SettingsNavBar(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backTag: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .defaultMinSize(minHeight = tokens.geometry.pageHeaderMinHeight)
            .padding(horizontal = tokens.geometry.pageOuterMarginH),
    ) {
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            when {
                leading != null -> leading()
                onBack != null -> {
                    val tag = if (backTag != null) Modifier.testTag(backTag) else Modifier
                    BasicText(
                        text = BACK_GLYPH,
                        style = settingsLabelStyle().copy(color = tokens.colors.accent),
                        modifier = tag
                            .clickable(role = Role.Button, onClick = onBack)
                            .semantics { contentDescription = UiStrings.BACK }
                            .padding(vertical = tokens.spacing.iconText, horizontal = tokens.spacing.iconText),
                    )
                }
            }
        }
        if (title != null) {
            BasicText(
                text = title,
                style = type.headline.copy(color = tokens.colors.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { heading() },
            )
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            trailing?.invoke()
        }
    }
}

/**
 * A bar affordance: the baseline's 完成, a ✕, or the `‹` back chevron.
 *
 * @param description the spoken label; defaults to the glyph. A ✕ on a pushed
 *   page is still a *back* action to a screen reader, so the sub-page frame
 *   passes `返回` here while the glyph stays the baseline's.
 */
@Composable
public fun SettingsNavAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    description: String = text,
) {
    val tokens = LocalUiTokens.current
    BasicText(
        text = text,
        style = settingsLabelStyle().copy(color = tokens.colors.accent),
        modifier = modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = tokens.spacing.iconText, horizontal = tokens.spacing.iconText),
    )
}

/**
 * The five-segment picker: a 32 pt capsule track, 16 pt side inset, the selected
 * segment drawn as a white pill (measured track y=378..473 px, x=48..1157 px).
 *
 * Labels are caller-supplied fixed copy, in the baseline's order.
 *
 * @param selected the index the host reported; an out-of-range index renders no
 *   selected pill instead of guessing one.
 * @param onSelect a segment was tapped.
 * @param tag an optional stable test tag for the track.
 */
@Composable
public fun SettingsSegmentedPicker(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val tokens = LocalUiTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsSegmentInset)
            .height(SettingsSegmentHeight)
            .clip(RoundedCornerShape(SettingsSegmentRadius))
            .background(tokens.colors.selector)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics(mergeDescendants = false) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(SETTINGS_SEGMENT_INSET_INNER)
                    .then(
                        if (isSelected) {
                            Modifier
                                .clip(RoundedCornerShape(SettingsSegmentRadius))
                                .background(tokens.colors.input)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(role = Role.Button) { onSelect(index) }
                    .semantics {
                        stateDescription = if (isSelected) UiStrings.EXPANDED else UiStrings.COLLAPSED
                    },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = label,
                    style = settingsLabelStyle().copy(
                        color = if (isSelected) tokens.colors.primary else tokens.colors.secondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** unverified geometry: the 2 pt white pill's inset inside a 32 pt track. */
private val SETTINGS_SEGMENT_INSET_INNER: Dp = 2.dp

/**
 * One inset-grouped card: white, 26 pt corner, 16 pt side margins, its last row
 * carrying no separator.
 *
 * @param content the card's rows; each row is a [SettingsGroupedRow] or a
 *   [SettingsFootnoteRow], in the baseline's order.
 */
@Composable
public fun SettingsGroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = LocalUiTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsCardMargin)
            .clip(RoundedCornerShape(SettingsCardRadius))
            .background(tokens.colors.input),
    ) {
        content()
    }
}

/**
 * One 52.33 pt row inside a card: 16 pt content inset, optional chevron slot,
 * optional 1 pt separator below.
 *
 * The separator is drawn as a sibling `Box` so it never adds height: the
 * baseline's row pitch is exactly 52.33 pt (157 px @3x) while a Column child
 * separator would make it 53.33.
 *
 * @param value the trailing value; `null` for a row with no value.
 * @param valueInk the value's ink; `null` uses the secondary token.
 * @param chevron draw the trailing chevron (and reserve its slot).
 * @param dividerBelow draw the 1 pt separator under this row.
 * @param onClick the row's tap action; `null` keeps the row read-only.
 */
@Composable
public fun SettingsGroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueInk: Color? = null,
    accentTitle: Boolean = false,
    chevron: Boolean = false,
    dividerBelow: Boolean = true,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tokens = LocalUiTokens.current
    val clickable = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .semantics(mergeDescendants = true) {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsGroupedRowHeight)
                .padding(
                    start = SettingsMetrics.ROW_CONTENT_INSET_PT.dp,
                    end = SettingsMetrics.ROW_CONTENT_INSET_PT.dp,
                    top = tokens.spacing.iconText,
                    bottom = tokens.spacing.iconText,
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = title,
                    style = settingsLabelStyle().copy(
                        color = when {
                            !enabled -> tokens.colors.tertiary
                            accentTitle -> tokens.colors.accent
                            else -> tokens.colors.primary
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (value != null) {
                    BasicText(
                        text = value,
                        style = settingsValueStyle().copy(color = valueInk ?: tokens.colors.secondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (trailing != null) {
                    trailing()
                } else if (chevron) {
                    SettingsChevronSlot(withChevron = true)
                }
            }
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = settingsFootnoteStyle().copy(color = tokens.colors.secondary),
                )
            }
        }
        if (dividerBelow) {
            SettingsHairline(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SettingsSeparatorInset),
            )
        }
    }
}

/** One footnote row inside a card: 13 pt secondary ink, same 52.33 pt pitch. */
@Composable
public fun SettingsFootnoteRow(
    text: String,
    modifier: Modifier = Modifier,
    dividerBelow: Boolean = false,
) {
    val tokens = LocalUiTokens.current
    Box(modifier = modifier.fillMaxWidth()) {
        BasicText(
            text = text,
            style = settingsFootnoteStyle().copy(color = tokens.colors.secondary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsGroupedRowHeight)
                .padding(
                    start = SettingsMetrics.ROW_CONTENT_INSET_PT.dp,
                    end = SettingsMetrics.ROW_CONTENT_INSET_PT.dp,
                    top = tokens.spacing.iconText,
                    bottom = tokens.spacing.iconText,
                ),
        )
        if (dividerBelow) {
            SettingsHairline(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SettingsSeparatorInset),
            )
        }
    }
}

/**
 * The sub-pages' section label: 17 pt secondary ink at x 33 pt, optional
 * right-aligned trailing value (the baseline's `15 个地址`).
 *
 * @param trailing an optional right-aligned value in 13 pt secondary ink
 *   (`-network`'s `15 个地址` measures 35 px = 11.7 pt of ink, i.e. 13 pt).
 * @param indent the leading inset; the network page's `地址` sits at 32.7 pt and
 *   the plain-row pages at 33 pt, so the default is the measured 33.
 * @param tag an optional stable test tag.
 */
@Composable
public fun SettingsSectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    indent: Dp = SettingsSectionLabelIndent,
    tag: String? = null,
) {
    val tokens = LocalUiTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(
                start = indent,
                end = tokens.geometry.pageOuterMarginH,
                top = tokens.spacing.cardInset,
                bottom = tokens.spacing.codeToolbar,
            )
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        BasicText(
            text = title,
            style = settingsSectionLabelStyle().copy(color = tokens.colors.secondary),
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            BasicText(
                text = trailing,
                style = settingsFootnoteStyle().copy(color = tokens.colors.secondary),
            )
        }
    }
}

/**
 * One row of a sub-page's plain list: 52.33 pt, 17 pt label, 17 pt value, an
 * optional chevron slot, and a 1 pt separator below at the measured inset.
 *
 * This is the sub-pages' replacement for `PageScaffold.InfoRow` (14 pt label,
 * 13 pt value, no separator). It takes the same shape so a page swaps the call
 * and nothing else.
 *
 * @param leadingInset the label's leading inset: 17 pt for a plain row
 *   (`-network`), 72 pt for a row with a 33 pt icon column (`-model`).
 * @param dividerLeadingInset the separator's leading inset: 16 pt (`-network`,
 *   `-runtime`), 72 pt (`-model`, `-plugins`), 32 pt (`-search`).
 * @param dividerTrailingInset the separator's trailing inset: 32.33 pt on four
 *   of the six sub-pages, 16 pt on `-runtime`.
 */
@Composable
public fun SettingsFormRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    tone: PageTone = PageTone.NORMAL,
    valueInk: Color? = null,
    accentTitle: Boolean = false,
    chevron: Boolean = false,
    dividerBelow: Boolean = true,
    subtitle: String? = null,
    enabled: Boolean = true,
    leadingInset: Dp = SettingsLargeTitleInset,
    dividerLeadingInset: Dp = SettingsSeparatorInset,
    dividerTrailingInset: Dp = SettingsSubPageSeparatorTrailingInset,
    trailing: (@Composable () -> Unit)? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tokens = LocalUiTokens.current
    val clickable = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .semantics(mergeDescendants = true) {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsGroupedRowHeight)
                .padding(
                    start = leadingInset,
                    end = SettingsMetrics.ROW_CONTENT_INSET_PT.dp,
                    top = tokens.spacing.iconText,
                    bottom = tokens.spacing.iconText,
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = title,
                    style = settingsLabelStyle().copy(
                        color = when {
                            !enabled -> tokens.colors.tertiary
                            accentTitle -> tokens.colors.accent
                            else -> tokens.colors.primary
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (value != null) {
                    BasicText(
                        text = value,
                        style = settingsValueStyle().copy(
                            color = valueInk ?: tone.settingsInk(tokens),
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (trailing != null) {
                    trailing()
                } else if (chevron) {
                    SettingsChevronSlot(withChevron = true)
                }
            }
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = settingsFootnoteStyle().copy(color = tokens.colors.secondary),
                )
            }
            subtitleContent?.invoke()
        }
        if (dividerBelow) {
            SettingsHairline(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = dividerLeadingInset, end = dividerTrailingInset),
            )
        }
    }
}

/** One paragraph of small print on a sub-page: 13 pt secondary ink. */
@Composable
public fun SettingsSubPageFootnote(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalUiTokens.current
    BasicText(
        text = text,
        style = settingsFootnoteStyle().copy(color = tokens.colors.secondary),
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = SettingsSectionLabelIndent,
                end = SettingsMetrics.ROW_CONTENT_INSET_PT.dp,
                top = tokens.spacing.codeToolbar,
                bottom = tokens.spacing.codeToolbar,
            ),
    )
}

/**
 * A sub-page's full frame: the 44 pt bar (✕ / ‹ leading, optional centred title,
 * optional trailing action), an optional 34 pt large title, then the scrolling
 * content.
 *
 * Two background modes, both measured:
 *  * [grouped] = `false` — a plain white list. `-model`, `-network`, `-plugins`,
 *    `-runtime`, `-search` all render rows straight on the page background.
 *  * [grouped] = `true` — iOS's inset-grouped list: a grey page with white
 *    cards. `-about` and the general tab are this mode.
 *
 * @param title the inline (centred) title; the six sub-pages set it on the two
 *   that live behind a modal sheet without a large title (`关于`, `网络访问`).
 * @param largeTitle the 34 pt heading drawn in the content instead of the bar.
 * @param onBack the leading affordance (the baseline's ✕ on a sheet, ‹ on a
 *   pushed page).
 * @param rootTag an optional stable test tag for the page root.
 */
@Composable
public fun SettingsSubPageFrame(
    modifier: Modifier = Modifier,
    title: String? = null,
    largeTitle: String? = null,
    onBack: (() -> Unit)? = null,
    backGlyph: String = SETTINGS_CLOSE_GLYPH,
    backTag: String? = null,
    grouped: Boolean = false,
    rootTag: String? = null,
    embedded: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val background = when {
        grouped -> tokens.colors.selector
        tokens.colors.background.isSpecified -> tokens.colors.background
        else -> tokens.colors.input
    }
    val tagged = if (rootTag != null) modifier.testTag(rootTag) else modifier
    Column(
        modifier = tagged
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        // `embedded` = this page is a tab body: the settings shell already drew
        // the 44 pt bar and the segmented picker, so drawing another bar here
        // would stack two. The large title stays — it belongs to the page.
        if (!embedded) {
            SettingsNavBar(
                title = title,
                leading = if (onBack != null) {
                    {
                        SettingsNavAction(
                            text = backGlyph,
                            onClick = onBack,
                            tag = backTag,
                            description = UiStrings.BACK,
                        )
                    }
                } else {
                    null
                },
                trailing = trailing,
            )
        }
        if (largeTitle != null) {
            BasicText(
                text = largeTitle,
                style = settingsLargeTitleStyle().copy(color = tokens.colors.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SettingsLargeTitleInset,
                        end = tokens.geometry.pageOuterMarginH,
                        top = SettingsLargeTitleTopGap,
                    )
                    .semantics { heading() },
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            content()
        }
    }
}

/**
 * The baseline's ✕ (a sheet's close affordance) drawn as a 22 pt accent ring
 * with a cross, matching `ios-settings-model.png`'s y=187 px control.
 */
@Composable
public fun SettingsCloseGlyph(modifier: Modifier = Modifier) {
    val tokens = LocalUiTokens.current
    Canvas(modifier = modifier.size(SettingsCloseGlyphSize)) {
        val ink = tokens.colors.accent
        drawCircle(
            color = ink.copy(alpha = SETTINGS_CLOSE_RING_ALPHA),
            radius = size.minDimension / 2f,
        )
        val arm = size.minDimension * SETTINGS_CLOSE_ARM_RATIO
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = SettingsCloseArmStroke.toPx()
        drawLine(ink, center - Offset(arm, arm), center + Offset(arm, arm), stroke, StrokeCap.Round)
        drawLine(ink, center - Offset(arm, -arm), center + Offset(-arm, arm), stroke, StrokeCap.Round)
    }
}

/** unverified geometry: the ✕ control's 22 pt ring (`-model` y=176..209 px). */
public val SettingsCloseGlyphSize: Dp = 22.dp
private val SettingsCloseArmStroke: Dp = 2.dp
private const val SETTINGS_CLOSE_RING_ALPHA: Float = 0.12f
private const val SETTINGS_CLOSE_ARM_RATIO: Float = 0.19f

/** The sheet's close glyph. */
public const val SETTINGS_CLOSE_GLYPH: String = "✕"

/**
 * unverified geometry: the 16 x 4 pt three-dot affordance a sub-page row can carry
 * (the network page's delete control). iOS's network page has no such control —
 * deletion lives in its own actions — so the size is this app's; it is a *shape*
 * rather than a text glyph because a text glyph contributes its font's line box
 * and pushed the measured 52.33 pt row to 58.3 pt.
 */
public val SettingsDotsWidth: Dp = 16.dp
public val SettingsDotsHeight: Dp = 4.dp

/**
 * A glass capsule action (`-plugins`'s 插件说明 / 刷新 / 添加插件): a 40 pt tall
 * pill on the selector ink with an optional leading glyph.
 *
 * unverified geometry: the shots put the pills at y=626..716 px = 30 pt tall and
 * 16 pt apart, so the pill is 30 pt with `spacing.iconText` between them.
 */
@Composable
public fun SettingsGlassPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    enabled: Boolean = true,
) {
    val tokens = LocalUiTokens.current
    Row(
        modifier = modifier
            .heightIn(min = SETTINGS_PILL_HEIGHT)
            .clip(RoundedCornerShape(SETTINGS_PILL_HEIGHT / 2))
            .background(tokens.colors.selector)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = tokens.spacing.cardInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        if (glyph != null) {
            BasicText(text = glyph, style = settingsLabelStyle().copy(color = tokens.colors.accent))
        }
        BasicText(
            text = label,
            style = settingsLabelStyle().copy(color = if (enabled) tokens.colors.accent else tokens.colors.tertiary),
        )
    }
}

/** unverified geometry: `-plugins` pill height y=626..716 px = 30 pt. */
private val SETTINGS_PILL_HEIGHT: Dp = 30.dp

/**
 * One group header of the *old* flat list is gone; what remains is the
 * sub-pages' [SettingsSectionLabel]. This alias exists so a caller that read the
 * pre-#13 name does not silently keep the removed geometry.
 */
@Deprecated(
    message = "The pre-#13 flat-list group header is not rendered by the current baseline; " +
        "use SettingsSectionLabel (sub-pages) or no header at all (the general tab).",
    replaceWith = ReplaceWith("SettingsSectionLabel(title, trailing = trailing, tag = tag)"),
)
@Composable
public fun SettingsGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    tag: String? = null,
) {
    SettingsSectionLabel(title = title, modifier = modifier, trailing = trailing, tag = tag)
}

/**
 * The baseline's 34 pt large title, drawn as a block.
 *
 * See [settingsLargeTitleStyle] for the missing `typography.largeTitle` token.
 * @param text the page's title, fixed copy owned by the page.
 */
@Composable
public fun SettingsLargeTitle(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalUiTokens.current
    BasicText(
        text = text,
        style = settingsLargeTitleStyle().copy(color = tokens.colors.primary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = SettingsLargeTitleInset,
                end = tokens.geometry.pageOuterMarginH,
                top = SettingsLargeTitleTopGap,
            )
            .semantics { heading() },
    )
}

/**
 * One 52.33 pt row of the card list, as a bare row (no card, no separator).
 *
 * Kept for callers that lay a row out outside [SettingsGroupCard]; the general
 * tab uses [SettingsGroupedRow].
 */
@Composable
public fun SettingsRowFrame(
    dividerBelow: Boolean = true,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: (() -> Unit)? = null,
    stateValue: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalUiTokens.current
    val clickable = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = role, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .semantics(mergeDescendants = true) {
                if (stateValue != null) stateDescription = stateValue
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsGroupedRowHeight)
                .padding(
                    start = SettingsMetrics.ROW_CONTENT_INSET_PT.dp,
                    end = SettingsValueTrailingInset,
                    top = tokens.spacing.iconText,
                    bottom = tokens.spacing.iconText,
                ),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (dividerBelow) {
            SettingsHairline(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SettingsSeparatorInset),
            )
        }
    }
}

/** The baseline's row label (17 pt regular); the caller supplies the colour. */
@Composable
public fun SettingsRowLabel(text: String, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val tokens = LocalUiTokens.current
    BasicText(
        text = text,
        style = settingsLabelStyle().copy(
            color = if (enabled) tokens.colors.primary else tokens.colors.tertiary,
        ),
        modifier = modifier,
    )
}

/** The baseline's row value (17 pt regular), end-aligned inside its slot. */
@Composable
public fun SettingsRowValue(
    text: String,
    modifier: Modifier = Modifier,
    ink: Color? = null,
    style: TextStyle? = null,
) {
    val tokens = LocalUiTokens.current
    BasicText(
        text = text,
        style = (style ?: settingsValueStyle()).copy(color = ink ?: tokens.colors.secondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The baseline's trailing 20.67 pt slot: the chevron when the row opens
 * something, empty otherwise. Reserving it on every row is what puts all values'
 * right edge on one line, matching the measured value/chevron edges.
 */
@Composable
public fun SettingsChevronSlot(withChevron: Boolean) {
    val tokens = LocalUiTokens.current
    Box(
        modifier = Modifier.width(SettingsChevronSlotWidth),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (withChevron) {
            val ink = chevronInk
            Canvas(
                modifier = Modifier
                    .padding(end = tokens.spacing.copyGap)
                    .size(width = ChevronWidth, height = ChevronHeight),
            ) {
                drawLine(
                    color = ink,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = ChevronStroke.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ink,
                    start = Offset(size.width, size.height / 2f),
                    end = Offset(0f, size.height),
                    strokeWidth = ChevronStroke.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val chevronInk: Color
    @Composable get() = LocalUiTokens.current.colors.caption

/**
 * The same slot, hosting a different glyph (a menu row's
 * `chevron.up.chevron.down`). Reserving the slot keeps a row with a custom
 * trailing glyph aligned with the rows above it.
 */
@Composable
public fun SettingsTrailingSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.width(SettingsChevronSlotWidth),
        contentAlignment = Alignment.CenterEnd,
    ) {
        content()
    }
}

/** The menu row's `chevron.up.chevron.down` (16 pt in the baseline). */
@Composable
public fun SettingsMenuChevron(ink: Color) {
    Canvas(modifier = Modifier.size(width = MenuChevronWidth, height = MenuChevronHeight)) {
        val stroke = MenuChevronStroke.toPx()
        val upperEnds = size.height * 0.375f
        val upperApex = size.height * 0.0625f
        val lowerEnds = size.height * 0.625f
        val lowerApex = size.height * 0.9375f
        drawLine(ink, Offset(0f, upperEnds), Offset(size.width / 2f, upperApex), stroke, StrokeCap.Round)
        drawLine(ink, Offset(size.width / 2f, upperApex), Offset(size.width, upperEnds), stroke, StrokeCap.Round)
        drawLine(ink, Offset(0f, lowerEnds), Offset(size.width / 2f, lowerApex), stroke, StrokeCap.Round)
        drawLine(ink, Offset(size.width / 2f, lowerApex), Offset(size.width, lowerEnds), stroke, StrokeCap.Round)
    }
}

/** The baseline's separator: `colors.border`, 1.0 pt thick (3 px @3x). */
@Composable
public fun SettingsHairline(modifier: Modifier = Modifier) {
    val tokens = LocalUiTokens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SettingsSeparatorThickness)
            .background(tokens.colors.border),
    )
}

/**
 * The baseline's 63 x 29.67 pt switch.
 *
 * Drawn rather than delegated to Material's `Switch`: that control is 52 x 32 dp
 * with a 20 dp thumb and a Material thumb halo, which is a different shape at
 * the same place. The row owns the tap target, so this draws only the control.
 *
 * @param checked the position the host reported.
 * @param enabled `false` dims the track.
 */
@Composable
public fun SettingsSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tokens = LocalUiTokens.current
    val track = when {
        !enabled -> tokens.colors.border
        checked -> tokens.colors.accent
        else -> tokens.colors.borderLight
    }
    Canvas(modifier = modifier.size(width = SettingsSwitchWidth, height = SettingsSwitchHeight)) {
        val radius = SettingsSwitchTrackRadius.toPx().coerceAtMost(size.height / 2f)
        drawRoundRect(
            color = track,
            cornerRadius = CornerRadius(radius, radius),
        )
        val thumbRadius = SettingsSwitchThumb.toPx() / 2f
        val inset = (size.height - SettingsSwitchThumb.toPx()) / 2f
        val x = if (checked) {
            size.width - inset - thumbRadius
        } else {
            inset + thumbRadius
        }
        drawCircle(color = Color.White, radius = thumbRadius, center = Offset(x, size.height / 2f))
    }
}

/** The 44 pt bar's leading glyph on a pushed page (the baseline's `‹`). */
public const val BACK_GLYPH: String = "‹"

/**
 * The sub-pages' foldable group header: a 17 pt secondary label at the measured
 * 33 pt indent, the disclosure chevron, the row count, and a 44 pt floor.
 *
 * The baseline's six sub-pages do not fold; Android's pages do, because the fold
 * is a ScreenState event those pages already own (§3.1). Keeping the fold and
 * restyling the header is the honest mirror: the label, its ink, its indent and
 * the row pitch match, the affordance stays.
 *
 * @param trailing an optional right-aligned count, read from the state.
 * @param tag an optional stable test tag.
 */
@Composable
public fun SettingsSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    tag: String? = null,
) {
    val tokens = LocalUiTokens.current
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            tokens.motion.disclosureMs,
            easing = tokens.motion.disclosureEasing,
        ),
        label = "settings-section-chevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = tokens.geometry.pageHeaderMinHeight)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                stateDescription = if (expanded) UiStrings.EXPANDED else UiStrings.COLLAPSED
                contentDescription = "$title · ${if (expanded) UiStrings.EXPANDED else UiStrings.COLLAPSED}"
            }
            .padding(
                start = SettingsSectionLabelIndent,
                end = tokens.geometry.pageOuterMarginH,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        BasicText(
            text = CHEVRON_FORWARD,
            style = settingsLabelStyle().copy(color = tokens.colors.tertiary),
            modifier = Modifier.rotate(rotation),
        )
        BasicText(
            text = title,
            style = settingsSectionLabelStyle().copy(color = tokens.colors.secondary),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (trailing != null) {
            BasicText(
                text = trailing,
                style = settingsFootnoteStyle().copy(color = tokens.colors.secondary),
            )
        }
    }
}

/** The disclosure glyph. */
private const val CHEVRON_FORWARD: String = "›"

/**
 * The ink for a row value: secondary for a plain value, a tone for a fact.
 *
 * Lives here (not in the page) because the row primitive needs it; the page only
 * passes the tone.
 */
@Composable
public fun PageTone.settingsInk(tokens: com.phonedsh.ui.theme.UiTokens): Color = when (this) {
    PageTone.NORMAL -> tokens.colors.secondary
    PageTone.ON -> tokens.colors.success
    PageTone.DEGRADED -> tokens.colors.warning
    PageTone.UNKNOWN -> tokens.colors.tertiary
}
