// The 402 pt parity yardstick every screenshot is measured against.
// SPDX-License-Identifier: MIT
//
// This object is the single owner of the numbers a `402 x 874 @3` capture is
// compared to. It is NOT a second token store: the UI draws from
// `LocalUiTokens`, whose geometry mirrors the iOS app (`ChatPage.swift`), and the
// two reference layers are deliberately different. The clearest example is the
// header: the iOS app draws a 44 pt bar (`geometry.pageHeaderMinHeight`), the
// official UI draws 50 pt, and this file names the **official** number because a
// parity review compares a screenshot to what the official UI shows.
//
// Sources, in order of authority:
//
//   * `docs/receipts/ios-session-audit/screens/official/manifest.json` — the
//     official golden's viewport contract: CSS `402 x 874`, `deviceScaleFactor 3`,
//     PNG `1206 x 2622`, light, mobile emulation. Captured by
//     `scripts/ios-parity/golden/capture-official.sh` (real `dsh web` + CDP).
//   * `docs/receipts/ios-baseline-screens/conditions.txt` — the iOS baseline's
//     identical screen contract (`screen_pt=402x874`, `screen_px=1206x2622`,
//     `scale=3`), captured by `android/tools/ios-baseline-capture.sh`.
//   * iOS PR #13 — the shared-metric list this file names:
//     `header 50 / composer 370x98 / content 338 / tool row 24 / flow 6/12`.
//     The per-number official derivations are receipted in
//     `docs/receipts/ios-session-audit/08-composer.md` (S6 composer card, C-18
//     header, C-25 content column) and `00-MASTER.md` (P1-04, P1-05).
//
// Consumers:
//
//   * `android/tools/screenshot-capture.sh` reads the viewport constants out of
//     this file to size the emulator display, so the capture viewport has exactly
//     one definition.
//   * the instrumented capture harness pins its frame against them
//     (`ScreenshotProfile.IOS_PARITY`).
//   * a page lane that implements one of the five metrics reads the constant
//     instead of re-typing the number, and records the measurement in its own
//     receipt.
package com.phonedsh.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shared 402 pt metrics (iOS PR #13) and the viewport they are measured in.
 *
 * Every value is in points (dp on Android: the capture sets density 480, so
 * `1 pt == 3 px` and a 402 pt frame is 1206 px wide). The pixel numbers are
 * derived from the point numbers, never typed twice.
 *
 * Nothing here is a drawing value: a composable that reads [headerHeight] or
 * [contentColumnWidth] is either implementing a parity gap or measuring one, and
 * must say which in its own comment.
 */
public object UiMetrics {
    // ----------------------------------------------------------------- viewport

    /** CSS viewport width of the official golden and the iOS baseline, in points. */
    public const val BASELINE_WIDTH_PT: Int = 402

    /** CSS viewport height of the official golden and the iOS baseline, in points. */
    public const val BASELINE_HEIGHT_PT: Int = 874

    /** Device pixel ratio of both baselines (`deviceScaleFactor: 3`). */
    public const val BASELINE_DPR: Int = 3

    /** Golden PNG width: `402 pt x 3`. */
    public const val BASELINE_WIDTH_PX: Int = BASELINE_WIDTH_PT * BASELINE_DPR

    /** Golden PNG height: `874 pt x 3`. */
    public const val BASELINE_HEIGHT_PX: Int = BASELINE_HEIGHT_PT * BASELINE_DPR

    /** Android `wm density` that makes 1 dp equal 1 pt: `160 x 3`. */
    public const val BASELINE_DENSITY_DPI: Int = 160 * BASELINE_DPR

    // ------------------------------------------------- shared metrics (PR #13)

    /**
     * Page header height: the official four-slot bar is `10 + 30 + 10`.
     *
     * iOS draws 44 pt here (`geometry.pageHeaderMinHeight`), so this is a
     * yardstick difference, not a token.
     */
    public const val HEADER_HEIGHT_PT: Int = 50

    /** The header's title row inside [HEADER_HEIGHT_PT] (`min-height: 30`). */
    public const val HEADER_TITLE_ROW_PT: Int = 30

    /** The header's vertical padding above and below the title row (`10`). */
    public const val HEADER_VERTICAL_PAD_PT: Int = 10

    /** Composer card width at 402 pt: `402 - 2 x 16`. */
    public const val COMPOSER_WIDTH_PT: Int = 370

    /** Composer card height at 402 pt, single line of draft (`InputBar.module.css:45-77`). */
    public const val COMPOSER_HEIGHT_PT: Int = 98

    /** Composer card corner radius (`--dsw-radius-panel`); iOS draws 22. */
    public const val COMPOSER_RADIUS_PT: Int = 28

    /**
     * Transcript content column at 402 pt: `402 - 2 x 32`.
     *
     * The official page insets the message column by `16` (composer clearance)
     * `+ 16` (card padding); the composer stays 370 wide, i.e. 338 is the narrower
     * of the two card families (`08-composer.md` C-25).
     */
    public const val CONTENT_COLUMN_WIDTH_PT: Int = 338

    /** Composer-to-screen inset, both sides: `(402 - 370) / 2`. */
    public const val COMPOSER_OUTER_MARGIN_PT: Int =
        (BASELINE_WIDTH_PT - COMPOSER_WIDTH_PT) / 2

    /** Content-column inset, both sides: `(402 - 338) / 2`. */
    public const val CONTENT_COLUMN_INSET_PT: Int =
        (BASELINE_WIDTH_PT - CONTENT_COLUMN_WIDTH_PT) / 2

    /** Collapsed tool row height (`geometry.disclosureRowHeight`, surface-owned). */
    public const val TOOL_ROW_HEIGHT_PT: Int = 24

    /** Flow gap between adjacent rows of the same group (official row spacing `6`). */
    public const val FLOW_GAP_PT: Int = 6

    /** Flow gap after a response row, before the next row (official `12`). */
    public const val FLOW_GAP_RESPONSE_PT: Int = 12

    /**
     * Flow gap after a `turn-process` control row (official `16`).
     *
     * PR #13 pinned `6/12`; the official matrix has this third value
     * (`05-timeline.md` T-05, `ChatView.module.css:68-84,116-121`) and the audit
     * flags the missing one, so it is named here rather than left in a comment.
     */
    public const val FLOW_GAP_TURN_PROCESS_PT: Int = 16

    // ------------------------------------------------------------ dp accessors

    /** [HEADER_HEIGHT_PT] as a Compose length. */
    public val headerHeight: Dp get() = HEADER_HEIGHT_PT.dp

    /** [COMPOSER_WIDTH_PT] as a Compose length. */
    public val composerWidth: Dp get() = COMPOSER_WIDTH_PT.dp

    /** [COMPOSER_HEIGHT_PT] as a Compose length. */
    public val composerHeight: Dp get() = COMPOSER_HEIGHT_PT.dp

    /** [CONTENT_COLUMN_WIDTH_PT] as a Compose length. */
    public val contentColumnWidth: Dp get() = CONTENT_COLUMN_WIDTH_PT.dp

    /** [TOOL_ROW_HEIGHT_PT] as a Compose length. */
    public val toolRowHeight: Dp get() = TOOL_ROW_HEIGHT_PT.dp

    /** [FLOW_GAP_PT] as a Compose length. */
    public val flowGap: Dp get() = FLOW_GAP_PT.dp

    /** [FLOW_GAP_RESPONSE_PT] as a Compose length. */
    public val flowGapResponse: Dp get() = FLOW_GAP_RESPONSE_PT.dp

    /**
     * True when a capture is the contract frame: 1206 x 2622 px at 480 dpi.
     *
     * A capture at any other size is not comparable to the golden and must not be
     * written into the parity baseline directory.
     */
    public fun matchesBaselineFrame(widthPx: Int, heightPx: Int, densityDpi: Int): Boolean =
        widthPx == BASELINE_WIDTH_PX &&
            heightPx == BASELINE_HEIGHT_PX &&
            densityDpi == BASELINE_DENSITY_DPI
}
