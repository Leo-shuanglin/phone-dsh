// The Sessions family's iOS-measured geometry (drawer + session list).
// SPDX-License-Identifier: MIT
//
// Authority: `examples/probe-app/PhoneDSHProbe/SessionsPage.swift` and
// `NativeShell.swift` first; PNG measurements from
// `docs/receipts/ios-session-audit/screens/current/darkfinal-sessions*.png`
// (1206 x 2622 px @3, so `pt = px / 3`) second.
//
// The screenshot set was captured ~2.5 h before commit f39acd0a touched
// `SessionsPage.swift`; that commit only replaced colour literals with
// `NativePagePalette` (no geometry moved), so the row/separator/CTA numbers
// still describe the current file. Two things in those PNGs are **not** current:
// the "会话" large title (the page declares no `navigationTitle` today) and, in
// the real drawer, the search field (the drawer hides the navigation bar —
// `NativeShell.swift:291` `.toolbar(.hidden, for: .navigationBar)`), so the
// shipped drawer shows no search field. [SEARCH_HEIGHT_PT] is measured from the
// standalone presentation, which is the only presentation that renders it.
//
// Confidence: see the vocabulary in `ChatMetrics.kt`.

package com.phonedsh.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drawer + session-list geometry.
 *
 * This is the iOS drawer, not the official web drawer: the two are different
 * surfaces (`UiMetrics` owns the official-web yardstick). A page that draws the
 * sessions drawer reads these values instead of re-typing them.
 */
public object SessionsMetrics {
    // ------------------------------------------------------------------ drawer

    /** Drawer column width on a 402 pt phone. Source: `NativeShell.swift:97` (`drawerWidth: CGFloat = 320`), repeated in the preview shell at `:850`. Confidence: exact. */
    public const val DRAWER_WIDTH_PT: Int = 320

    /** Drawer content width: 320 - 16 leading - 16 trailing. Source: `SessionsPage.swift:594` ("面板内左 16 右 16:320 宽的面板,线正好 288"), and the separator's hard-coded width at `:157`/`:596`. Confidence: exact. */
    public const val DRAWER_CONTENT_WIDTH_PT: Int = 288

    /** Row inset inside the drawer, both sides. Source: `SessionsPage.swift:162,195,281,460,590`. Confidence: exact. */
    public const val ROW_INSET_PT: Int = 16

    /** Bottom furniture row (定时任务 / 设置) height. Source: `NativeShell.swift:432` (`.frame(height: 48)`). Confidence: exact. */
    public const val DRAWER_ROW_HEIGHT_PT: Int = 48

    /** Bottom furniture row icon slot. Source: `NativeShell.swift:422` (`.frame(width: 22)`). Confidence: exact. */
    public const val DRAWER_ROW_ICON_SLOT_PT: Int = 22

    /** Bottom furniture row icon/title gap. Source: `NativeShell.swift:421` (`HStack(spacing: 10)`). Confidence: exact. */
    public const val DRAWER_ROW_SPACING_PT: Int = 10

    /** Bottom furniture chevron size. Source: `NativeShell.swift:427` (`.font(.system(size: 12, weight: .semibold))`). Confidence: exact. */
    public const val DRAWER_ROW_CHEVRON_REF_PT: Int = 12

    // ------------------------------------------------------------------- rows

    /** Session row minimum height (two text lines plus the 17 pt glyph). Source: `SessionsPage.swift:580` (`.frame(minHeight: 56)`). PNG check: separators at y=1346, 1514, 1682 px in `darkfinal-sessions-many-light.png` → 168 px pitch = 56.0 pt. Confidence: exact (source), measured (render). */
    public const val SESSION_ROW_HEIGHT_PT: Int = 56

    /** Workspace ("group") row height. Source: `SessionsPage.swift:439` (`.frame(height: 44)`). Confidence: exact. */
    public const val WORKSPACE_ROW_HEIGHT_PT: Int = 44

    /** Action row height (新建会话 / 添加工作文件夹 / retry). Source: `SessionsPage.swift:164,194,277`. Confidence: exact. */
    public const val ACTION_ROW_HEIGHT_PT: Int = 44

    /** Session row inner horizontal padding (inside the 16 pt row inset). Source: `SessionsPage.swift:579`. Confidence: exact. */
    public const val SESSION_ROW_INNER_INSET_PT: Int = 8

    /** Session row glyph box. Source: `SessionsPage.swift:550` (`.frame(width: 17, height: 17)`). Confidence: exact. */
    public const val SESSION_GLYPH_BOX_PT: Int = 17

    /** Session row glyph-to-text gap. Source: `SessionsPage.swift:546` (`HStack(spacing: 10)`). Confidence: exact. */
    public const val SESSION_GLYPH_GAP_PT: Int = 10

    /** Session title-to-time gap. Source: `SessionsPage.swift:551` (`VStack(spacing: 3)`). Confidence: exact. */
    public const val SESSION_TEXT_GAP_PT: Int = 3

    /** Running dot diameter. Source: `SessionsPage.swift:562` (`.frame(width: 6, height: 6)`). Confidence: exact. */
    public const val SESSION_RUNNING_DOT_PT: Int = 6

    /** Workspace chevron slot. Source: `SessionsPage.swift:425` (`.frame(width: 12, alignment: .leading)`). Confidence: exact. */
    public const val WORKSPACE_CHEVRON_SLOT_PT: Int = 12

    /** Workspace title leading gap after the chevron. Source: `SessionsPage.swift:430`. Confidence: exact. */
    public const val WORKSPACE_TITLE_GAP_PT: Int = 7

    /** Workspace count label leading gap. Source: `SessionsPage.swift:435`. Confidence: exact. */
    public const val WORKSPACE_COUNT_GAP_PT: Int = 6

    /** Workspace overflow ("...") hit box. Source: `SessionsPage.swift:455` (`.frame(width: 32, height: 44)`). Confidence: exact. */
    public const val WORKSPACE_MENU_WIDTH_PT: Int = 32

    // -------------------------------------------------------------- separators

    /** Hand-drawn row separator length. Source: `SessionsPage.swift:157` and `:596` (`.frame(width: 288, height: 0.5)`). Confidence: exact. */
    public const val SEPARATOR_WIDTH_PT: Int = 288

    /** Hand-drawn row separator thickness. Source: `SessionsPage.swift:157`, `:596`. Confidence: exact. */
    public const val SEPARATOR_HEIGHT_PT: Float = 0.5f

    /** Separator leading origin: the row inset, so the line runs 16 -> 304 in a 320 pt drawer. Source: `SessionsPage.swift:590,593-597`. PNG check: `darkfinal-sessions-many-light.png` row 1346 has the hairline at x=48..911 px → 16..303.7 pt. Confidence: exact (source), measured (render). */
    public const val SEPARATOR_INSET_PT: Int = 16

    // ------------------------------------------------------------------- search

    /**
     * Search field height, measured.
     *
     * Source: `darkfinal-sessions-light.png`: the white capsule spans y=350..482 px at
     * x=600 (a vertical scan; the field is white-on-white, so the shadow start
     * at y=483 and the white run are the edge evidence) → 132 px / 3 = 44.0 pt.
     * The corner is a capsule: at y=352 the white run starts at x=102 px, at
     * y=400 at x=50 px, which fits radius 66 px = 22 pt.
     *
     * Caveat: the real drawer hides the navigation bar (`NativeShell.swift:291`),
     * so this field only exists in the standalone presentation. There is no
     * search field in the shipped drawer screenshot (`darkfinal-drawer-light.png`).
     * Confidence: measured.
     */
    public const val SEARCH_HEIGHT_PT: Int = 44

    /** Search capsule radius: half the 44 pt height. Source: the same fit as [SEARCH_HEIGHT_PT] (top-corner arc). Confidence: measured. */
    public const val SEARCH_RADIUS_PT: Int = 22

    /** Search field side inset, measured: white run x=48..1157 px on the 402 pt page → 16.0..385.7 pt, so 370 pt wide. Source: `darkfinal-sessions-light.png`. Confidence: measured. */
    public const val SEARCH_INSET_PT: Int = 16

    /** Search field width at 402 pt: 402 - 2 x 16. Derived from [SEARCH_INSET_PT]. Source: arithmetic on the two measured edges. Confidence: inferred. */
    public const val SEARCH_WIDTH_PT: Int = 370

    /** Search placeholder text size, measured: ink box 46 px tall (y=551..596) in `darkpass-files-light.png`, CJK ink ≈ 0.9 x size → 17 pt. Reference only; Android uses `LocalUiType`. Source: `darkpass-files-light.png`. Confidence: measured. */
    public const val SEARCH_TEXT_REF_PT: Int = 17

    // --------------------------------------------------------------------- CTA

    /** Empty-state CTA height. Source: `SessionsPage.swift:223` (`.frame(height: 50)`). PNG check: `darkfinal-sessions-light.png` blue box y=1046..1195 px → 150 px = 50.0 pt. Confidence: exact (source), measured (render). */
    public const val CTA_HEIGHT_PT: Int = 50

    /** Empty-state CTA corner radius. Source: `SessionsPage.swift:225` (`RoundedRectangle(cornerRadius: 12)`). PNG check: corner fit r≈36 px = 12 pt. Confidence: exact (source), measured (render). */
    public const val CTA_RADIUS_PT: Int = 12

    /** Empty-state CTA width at 402 pt: the list row inset leaves 402 - 32. Source: PNG check x=48..1157 px → 370.0 pt. Confidence: measured. */
    public const val CTA_WIDTH_PT: Int = 370

    /** Empty-state top gap between the title block and the CTA. Source: `SessionsPage.swift:228` (`.padding(.top, 24)`). Confidence: exact. */
    public const val EMPTY_STATE_CTA_TOP_PT: Int = 24

    /** Empty-state bottom padding. Source: `SessionsPage.swift:232` (`.padding(.bottom, 8)`). Confidence: exact. */
    public const val EMPTY_STATE_BOTTOM_PT: Int = 8

    // -------------------------------------------------------------------- toast

    /** Archived toast height. Source: `SessionsPage.swift:332` (`.frame(height: 28)`). Confidence: exact. */
    public const val TOAST_HEIGHT_PT: Int = 28

    /** Archived toast corner radius. Source: `SessionsPage.swift:334` (`RoundedRectangle(cornerRadius: 12)`). Confidence: exact. */
    public const val TOAST_RADIUS_PT: Int = 12

    /** Archived toast top offset. Source: `SessionsPage.swift:335` (`.padding(.top, 92)`). Confidence: exact. */
    public const val TOAST_TOP_OFFSET_PT: Int = 92

    // ------------------------------------------------------------ dp accessors

    /** [DRAWER_WIDTH_PT] as a Compose length. */
    public val drawerWidth: Dp get() = DRAWER_WIDTH_PT.dp

    /** [DRAWER_CONTENT_WIDTH_PT] as a Compose length. */
    public val drawerContentWidth: Dp get() = DRAWER_CONTENT_WIDTH_PT.dp

    /** [ROW_INSET_PT] as a Compose length. */
    public val rowInset: Dp get() = ROW_INSET_PT.dp

    /** [DRAWER_ROW_HEIGHT_PT] as a Compose length (the 定时任务 / 设置 furniture rows). */
    public val drawerRowHeight: Dp get() = DRAWER_ROW_HEIGHT_PT.dp

    /** [DRAWER_ROW_ICON_SLOT_PT] as a Compose length. */
    public val drawerRowIconSlot: Dp get() = DRAWER_ROW_ICON_SLOT_PT.dp

    /** [DRAWER_ROW_SPACING_PT] as a Compose length. */
    public val drawerRowSpacing: Dp get() = DRAWER_ROW_SPACING_PT.dp

    /** [DRAWER_ROW_CHEVRON_REF_PT] as a Compose length. */
    public val drawerRowChevron: Dp get() = DRAWER_ROW_CHEVRON_REF_PT.dp

    /** [SESSION_ROW_HEIGHT_PT] as a Compose length. */
    public val sessionRowHeight: Dp get() = SESSION_ROW_HEIGHT_PT.dp

    /** [WORKSPACE_ROW_HEIGHT_PT] as a Compose length. */
    public val workspaceRowHeight: Dp get() = WORKSPACE_ROW_HEIGHT_PT.dp

    /** [SEPARATOR_WIDTH_PT] as a Compose length. */
    public val separatorWidth: Dp get() = SEPARATOR_WIDTH_PT.dp

    /** [SEPARATOR_HEIGHT_PT] as a Compose length. */
    public val separatorHeight: Dp get() = SEPARATOR_HEIGHT_PT.dp

    /** [SEARCH_HEIGHT_PT] as a Compose length. */
    public val searchHeight: Dp get() = SEARCH_HEIGHT_PT.dp

    /** [SEARCH_RADIUS_PT] as a Compose length. */
    public val searchRadius: Dp get() = SEARCH_RADIUS_PT.dp

    /** [CTA_HEIGHT_PT] as a Compose length. */
    public val ctaHeight: Dp get() = CTA_HEIGHT_PT.dp

    /** [CTA_RADIUS_PT] as a Compose length. */
    public val ctaRadius: Dp get() = CTA_RADIUS_PT.dp
}
