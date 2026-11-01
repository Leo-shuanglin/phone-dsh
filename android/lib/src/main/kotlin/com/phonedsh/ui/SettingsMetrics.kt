// The Settings family's iOS-measured list chrome (current 5-tab page).
// SPDX-License-Identifier: MIT
//
// **Read this before using the old Settings numbers.** The Settings page was
// rewritten in commit f39acd0a (2026-11-01 15:29): `SettingsPage.swift` is now
// 255 lines with a 5-tab `Picker(.segmented)` and `List` (default inset-grouped
// style), not the 615-line flat-list page that `spec/ui/v1/pages/settings.json`
// and `docs/receipts/ios-baseline-screens/ios-settings*.png` describe. The old
// flat-list chrome — 44 pt rows, a 0.33 pt hairline at x=16, a 15 pt value, a
// 20 pt chevron slot starting at a 350 pt value edge, one group header at
// x=32.7 with a 24 pt top inset — is **not** in this file. It belongs to a
// layout the current source no longer renders; encoding it here would make a
// stale baseline look authoritative.
//
// Current authority:
//   * `SettingsPage.swift:64-72` — the segmented picker (`.padding(.horizontal,
//     16)`, `.padding(.vertical, 10)`), inside the navigation stack.
//   * `SettingsPage.swift:99-167` — `List { Section { ... } }` with default
//     inset-grouped chrome; row heights, separators, cards and the switch are
//     drawn by SwiftUI, so they are measured, not source literals.
//   * `docs/receipts/ios-session-audit/screens/current/settings-chat.png`
//     (1206 x 2622 px @3) — the only current pixel evidence for this page.
//
// Calibration used by every measurement below: the screen's safe-area top is
// 62 pt. Proof inside the same PNG: the navigation title's ink box is
// y=231..275 px (centre 253 px), and a 44 pt bar starting at 62 pt has its
// centre at 62 + 22 = 84 pt = 252 px. The 62 pt top is itself pinned by the chat
// header: `ChatMetrics.HEADER_HEIGHT_PT` is 50 and `sample-chat.png` draws its
// header hairline at y=335 px = 111.7 pt, i.e. 62 + 50.
//
// Confidence: see the vocabulary in `ChatMetrics.kt`.

package com.phonedsh.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Current Settings chrome: navigation bar, segmented tab picker, inset-grouped
 * cards, rows, switch and chevron.
 *
 * None of these values is a page-level token in `spec/ui/v1/tokens.json`; the
 * closest tokens are `geometry.pageHeaderMinHeight` (44) and
 * `geometry.pageRowMinHeight` (44), both of which the rendered page exceeds.
 */
public object SettingsMetrics {
    // --------------------------------------------------------------- nav bar

    /** Inline navigation bar height. Source: system `.navigationBarTitleDisplayMode(.inline)` at `SettingsPage.swift:169` + `tokens.json` `geometry.pageHeaderMinHeight: 44`; PNG check: title ink centre at y=253 px with a 62 pt safe top → 44 pt bar. Confidence: measured (render), exact (token). */
    public const val NAV_BAR_HEIGHT_PT: Int = 44

    /** Inline title size. Reference only (Android text comes from `LocalUiType`). Source: iOS inline title role; PNG check: CJK ink box 44 px = 14.7 pt → 17 pt font. Confidence: measured. */
    public const val NAV_TITLE_REF_PT: Int = 17

    /** Inline title weight (semibold). Source: UINavigationBar's inline-title role, not written in the page. Confidence: inferred. */
    public const val NAV_TITLE_WEIGHT_REF: Int = 600

    // ------------------------------------------------------- segmented picker

    /** Segmented control height. Source: PNG check `settings-chat.png` track y=378..473 px → 96 px = 32.0 pt. Confidence: measured. */
    public const val SEGMENTED_HEIGHT_PT: Int = 32

    /** Segmented control side inset. Source: `SettingsPage.swift:69` (`.padding(.horizontal, 16)`); PNG check: track x=48..1157 px → 16.0..385.7 pt. Confidence: exact. */
    public const val SEGMENTED_INSET_H_PT: Int = 16

    /** Segmented control vertical padding. Source: `SettingsPage.swift:70` (`.padding(.vertical, 10)`). Confidence: exact. */
    public const val SEGMENTED_PADDING_V_PT: Int = 10

    /** Segmented control track corner radius. The track is a capsule; PNG corner fit at the top edge is consistent with half the 32 pt height. Source: `settings-chat.png` (top-edge corner fit). Confidence: inferred (capsule = height / 2). */
    public const val SEGMENTED_RADIUS_PT: Int = 16

    /** Gap from the 44 pt bar to the track's top edge, measured. Source: `settings-chat.png`: bar bottom 62 + 44 = 106 pt = 318 px, track top 378 px → 60 px = 20 pt. The page declares 10 pt of vertical padding, so the other 10 pt is the navigation content inset the system adds; a page that lays out its own bar must add both. Confidence: measured (the 20), inferred (the split). */
    public const val SEGMENTED_TOP_GAP_PT: Int = 20

    // ------------------------------------------------------------ grouped list

    /** Group card side margin. Source: PNG check card white starts at x=48 px and ends at 1157 px → 16.0..385.7 pt on the 402 pt screen. Confidence: measured. */
    public const val CARD_MARGIN_H_PT: Int = 16

    /**
     * Group card corner radius, measured.
     *
     * Source: `settings-chat.png` first card: top edge y=606 px, left edge x=48 px. The
     * left boundary follows x=81 at y=620, x=56 at y=650 and reaches 48 at
     * y≈676; a circular fit through those points gives r≈77-79 px = 26 pt. The
     * top edge begins ~95 px in from the left, which is the signature of
     * SwiftUI's **continuous** corner style, so treat 26 as the nominal radius,
     * not a circular arc. Confidence: measured.
     */
    public const val CARD_RADIUS_PT: Int = 26

    /** Vertical gap between two cards. Source: PNG check card 1 bottom y=914 px, card 2 top y=1030 px (and 1647→1763, 2380→2496) → 116 px = 38.7 pt. Confidence: measured. */
    public const val SECTION_GAP_PT: Float = 38.67f

    /** Top inset from the list's top edge to the first card. Source: PNG check grouped list background starts y=502 px, card 1 top y=606 px → 104 px = 34.7 pt. Confidence: measured. */
    public const val FIRST_CARD_TOP_INSET_PT: Float = 34.67f

    /**
     * One grouped row's rendered height.
     *
     * Source: PNG check separators in `settings-chat.png` at y=1179, 1336, 1493, 1912,
     * 2069, 2226 px → a 157 px pitch = 52.33 pt, four times. The source declares
     * no row height (default `List`), and the value is the 17 pt line box plus
     * the system's vertical padding. The token `geometry.pageRowMinHeight` (44)
     * is the *minimum*, not this render. Confidence: measured.
     */
    public const val ROW_HEIGHT_PT: Float = 52.33f

    /** Nominal row minimum from the token table. Source: `spec/ui/v1/tokens.json` `geometry.pageRowMinHeight` (44). Confidence: exact. */
    public const val ROW_MIN_HEIGHT_PT: Int = 44

    /** Row vertical padding implied by [ROW_HEIGHT_PT]: (52.33 - 20.33 line box) / 2. Not written in source. Source: arithmetic on [ROW_HEIGHT_PT] and the 17 pt line box. Confidence: inferred. */
    public const val ROW_VERTICAL_PADDING_PT: Float = 16f

    /** Row content leading inset inside the card. Source: PNG check label glyph starts x=98 px; card starts x=48 px → 16.7 pt (16 pt inset plus ~0.7 pt glyph bearing). Confidence: measured (glyph), inferred (the 16 pt). */
    public const val ROW_CONTENT_INSET_PT: Int = 16

    /** Row separator thickness. Source: PNG check the hairline at y=760 occupies y=760..762 px → 3 px = 1.0 pt (iOS 26's grouped-list separator is thicker than the old flat list's 1 px). Confidence: measured. */
    public const val SEPARATOR_HEIGHT_PT: Float = 1f

    /** Row separator horizontal inset inside the card. Source: PNG check hairline x=96..1109 px, card x=48..1157 px → 16.0 pt on both sides. Confidence: measured. */
    public const val SEPARATOR_INSET_PT: Int = 16

    // ------------------------------------------------------- row trailing items

    /** Distance from the card's trailing edge to the chevron's trailing edge. Source: PNG check card right 1157 px, chevron right 1101 px → 56 px = 18.7 pt. Confidence: measured. */
    public const val CHEVRON_TRAILING_INSET_PT: Float = 18.67f

    /** Chevron glyph width. Source: PNG check x=1083..1101 px → 19 px = 6.3 pt. Confidence: measured. */
    public const val CHEVRON_WIDTH_PT: Float = 6.33f

    /** Chevron glyph height. Source: PNG check y=668..701 px → 34 px = 11.3 pt. Confidence: measured. */
    public const val CHEVRON_HEIGHT_PT: Float = 11.33f

    /** Slot from the value's right edge to the chevron's right edge ("值右缘 20.7"). Source: PNG check value right 1039 px, chevron right 1101 px → 62 px = 20.7 pt. Confidence: measured. */
    public const val VALUE_CHEVRON_SLOT_PT: Float = 20.67f

    /** Distance from the card's trailing edge to the value's right edge: 18.67 + 20.67. Source: arithmetic on the two measured edges; PNG check 1157-1039 = 118 px = 39.3 pt. Confidence: measured. */
    public const val VALUE_TRAILING_INSET_PT: Float = 39.33f

    /** Switch width. Source: PNG check green track x=927..1115 px → 189 px = 63.0 pt. Confidence: measured. */
    public const val SWITCH_WIDTH_PT: Int = 63

    /** Switch height. Source: PNG check green track y=2104..2192 px → 89 px = 29.7 pt. Confidence: measured. */
    public const val SWITCH_HEIGHT_PT: Float = 29.67f

    /** Switch trailing inset inside the card. Source: PNG check card right 1157 px, switch right 1115 px → 42 px = 14.0 pt. Confidence: measured. */
    public const val SWITCH_TRAILING_INSET_PT: Int = 14

    // -------------------------------------------------------------------- type

    /** Row label size, measured: CJK ink box 45 px = 15 pt → 17 pt font. Reference only. Source: `settings-chat.png` label ink box. Confidence: measured. */
    public const val ROW_LABEL_REF_PT: Int = 17

    /** Row value size, measured: "未设置" ink box 45 px → 17 pt (the old flat list used 15). Reference only. Source: `settings-chat.png` value ink box. Confidence: measured. */
    public const val ROW_VALUE_REF_PT: Int = 17

    /** Footnote size: source `.font(.footnote)` at `SettingsPage.swift:105,127`; PNG check ink 35 px = 11.7 pt → 13 pt. Reference only. Source: `SettingsPage.swift:105,127` + `settings-chat.png`. Confidence: exact (role), measured (size). */
    public const val FOOTNOTE_REF_PT: Int = 13

    /** Caption size on the permissions subpage (`.font(.caption)`). Source: `PhonePermissionsPage.swift:116`; reference only. Confidence: exact. */
    public const val CAPTION_REF_PT: Int = 12

    // ------------------------------------------------------------ dp accessors

    /** [NAV_BAR_HEIGHT_PT] as a Compose length. */
    public val navBarHeight: Dp get() = NAV_BAR_HEIGHT_PT.dp

    /** [SEGMENTED_HEIGHT_PT] as a Compose length. */
    public val segmentedHeight: Dp get() = SEGMENTED_HEIGHT_PT.dp

    /** [SEGMENTED_INSET_H_PT] as a Compose length. */
    public val segmentedInset: Dp get() = SEGMENTED_INSET_H_PT.dp

    /** [CARD_MARGIN_H_PT] as a Compose length. */
    public val cardMargin: Dp get() = CARD_MARGIN_H_PT.dp

    /** [CARD_RADIUS_PT] as a Compose length. */
    public val cardRadius: Dp get() = CARD_RADIUS_PT.dp

    /** [ROW_HEIGHT_PT] as a Compose length. */
    public val rowHeight: Dp get() = ROW_HEIGHT_PT.dp

    /** [SEPARATOR_INSET_PT] as a Compose length. */
    public val separatorInset: Dp get() = SEPARATOR_INSET_PT.dp

    /** [SWITCH_WIDTH_PT] as a Compose length. */
    public val switchWidth: Dp get() = SWITCH_WIDTH_PT.dp

    /** [SWITCH_HEIGHT_PT] as a Compose length. */
    public val switchHeight: Dp get() = SWITCH_HEIGHT_PT.dp
}
