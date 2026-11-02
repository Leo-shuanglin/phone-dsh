// The Chat family's iOS-measured geometry, declared once.
// SPDX-License-Identifier: MIT
//
// Authority order used for every number below:
//
//   1. `examples/probe-app/PhoneDSHProbe/*.swift` — the implementation, read as
//      the highest authority: a literal there is what the phone draws.
//   2. `docs/receipts/ios-session-audit/screens/current/*.png` — 1206 x 2622 px
//      at DPR 3, so a pixel measurement divides by 3 to become points.
//   3. `docs/receipts/ios-session-audit/screens/official/manifest.json` — the
//      official web golden. It carries the viewport (402 x 874 @3) and the
//      interaction `rect`s only; it has **no** layout-metric table, so it
//      contributes no geometry below.
//
// The older `docs/receipts/ios-baseline-screens/*.png` set is deliberately NOT
// cited: its chat shots predate the parity rewrite (44 pt header, 22 pt
// composer, 17 pt body, no header hairline). The L6 receipt lists that set.
//
// Confidence vocabulary (same in every `*Metrics.kt` file):
//
//   * `exact`    — a numeric literal (or token) in the Swift source.
//   * `measured` — read off a PNG; the KDoc gives the pixel evidence so it can
//                  be reproduced (`y=...px / 3 = ...pt`).
//   * `inferred` — derived from other values, not stated anywhere; every such
//                  constant says what it was derived from and why.
//
// This file is a declaration site, not a drawing site. A page that draws one of
// these reads the constant; that is how the page layer keeps `N.dp` out of its
// own source (`PageTokenReferenceTest`).

package com.phonedsh.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Chat page geometry: header, transcript, bubble, composer and the cards that
 * hang off the composer.
 *
 * Overlap with [UiMetrics] is intentional and documented per constant: that
 * object is the **official web** yardstick, this one is the **iOS
 * implementation**. Where the two disagree (e.g. the header, 50 vs 44) the
 * difference is a product decision, not a copy error, and the page lane must
 * pick the surface it is implementing.
 */
public object ChatMetrics {
    /** No additional inset; iOS ChatPage.spacing uses zero for the first row. */
    public const val NO_GAP_PT: Int = 0
    // ------------------------------------------------------------------ header

    /** Page header height. Source: `NativeParityTokens.swift:99` (`headerHeight = 50`), drawn at `ChatPage.swift:195,303`. Confidence: exact. */
    public const val HEADER_HEIGHT_PT: Int = 50

    /**
     * Header title size. Source: `NativeParityTokens.swift:65` (`headerTitleSize = 14`).
     * This is a reference value only: Android text sizes come from `LocalUiType`.
     * Confidence: exact.
     */
    public const val HEADER_TITLE_REF_PT: Int = 14

    /** Header title weight (`.medium`). Source: `NativeParityTokens.swift:66`. Reference only. Confidence: exact. */
    public const val HEADER_TITLE_WEIGHT_REF: Int = 500

    /** Header horizontal inset. Source: `ChatPage.swift:302` (`.padding(.horizontal, 16)`). Confidence: exact. */
    public const val HEADER_HORIZONTAL_INSET_PT: Int = 16

    /** Sidebar glyph slot inside the 50 pt bar: 32 x 44. Source: `ChatPage.swift:224`. Confidence: exact. */
    public const val HEADER_LEADING_SLOT_WIDTH_PT: Int = 32

    /** Device entry slot: 36 x 44. Source: `ChatPage.swift:235`. Confidence: exact. */
    public const val HEADER_DEVICE_SLOT_WIDTH_PT: Int = 36

    /** Overflow ("more") slot: 44 x 44. Source: `ChatPage.swift:288`. Confidence: exact. */
    public const val HEADER_TRAILING_SLOT_WIDTH_PT: Int = 44

    /** Every header control's height (the bar is 50, controls keep the 44 hit box). Source: `ChatPage.swift:224,235,288`. Confidence: exact. */
    public const val HEADER_SLOT_HEIGHT_PT: Int = 44

    /** Side reserve the centred title must clear on both sides. Source: `ChatPage.swift:216` (`.padding(.horizontal, 104)`). Confidence: exact. */
    public const val HEADER_TITLE_SIDE_RESERVE_PT: Int = 104

    /** Header bottom hairline. Source: `ChatPage.swift:199` (`.frame(height: 0.5)`). Confidence: exact. */
    public const val HEADER_DIVIDER_PT: Float = 0.5f

    // -------------------------------------------------------------- transcript

    /** Transcript content column at 402 pt. Source: `NativeParityTokens.swift:94` (`contentWidth = 338`). Same value as [UiMetrics.CONTENT_COLUMN_WIDTH_PT]. Confidence: exact. */
    public const val CONTENT_COLUMN_WIDTH_PT: Int = 338

    /** Transcript side inset (both sides). Source: `NativeParityTokens.swift:123` (`transcriptSide = 32`), drawn at `ChatPage.swift:1162`. Confidence: exact. */
    public const val TRANSCRIPT_SIDE_INSET_PT: Int = 32

    /** First transcript row's top inset. Source: `ChatPage.swift:1163` (`.padding(.top, 16)`). Confidence: exact. */
    public const val TRANSCRIPT_TOP_INSET_PT: Int = 16

    /** Bottom clearance under the last row, so the composer never covers it. Source: `ChatPage.swift:1166` (`.padding(.bottom, 48)`). Confidence: exact. */
    public const val TRANSCRIPT_BOTTOM_INSET_PT: Int = 48

    /** Gap between adjacent rows of the same flow group. Source: `NativeParityTokens.swift:120` (`flowGap = 6`). Confidence: exact. */
    public const val FLOW_GAP_PT: Int = 6

    /** Gap after a response row. Source: `NativeParityTokens.swift:121` (`responseGap = 12`). Confidence: exact. */
    public const val RESPONSE_GAP_PT: Int = 12

    /** Gap after a turn-process control row. Source: `NativeParityTokens.swift:122` (`turnGap = 16`). Confidence: exact. */
    public const val TURN_GAP_PT: Int = 16

    /** Collapsed tool / thinking disclosure row. Source: `NativeParityTokens.swift:101` and `NativeMessageRows.swift:43` (`.frame(height: 24)`). Confidence: exact. */
    public const val DISCLOSURE_ROW_HEIGHT_PT: Int = 24

    /** Disclosure leading icon box. Source: `NativeMessageRows.swift:26` (`.frame(width: 16, height: 16)`). Confidence: exact. */
    public const val DISCLOSURE_ICON_PT: Int = 16

    /** Icon-to-title gap. Source: `NativeMessageRows.swift:27` (`.padding(.trailing, 6)`). Confidence: exact. */
    public const val DISCLOSURE_ICON_GAP_PT: Int = 6

    /** Expanded thinking body's leading indent. Source: `NativeMessageRows.swift:82` (`.padding(.leading, 22)`). Confidence: exact. */
    public const val THINKING_CONTENT_INDENT_PT: Int = 22

    /** Expanded thinking body's vertical inset. Source: `NativeMessageRows.swift:83` (`.padding(.vertical, 4)`). Confidence: exact. */
    public const val THINKING_CONTENT_VERTICAL_PT: Int = 4

    /** Closed-turn process control row height. Source: `NativeParityTokens.swift:100` (`turnProcessHeight = 33`), drawn at `NativeTurnProcessRow.swift:179`. Confidence: exact. */
    public const val TURN_PROCESS_HEIGHT_PT: Int = 33

    // --------------------------------------------------------------- user bubble

    /** User bubble corner radius (continuous). Source: `NativeParityTokens.swift:115` (`radiusXL = 20`), drawn at `ChatPage.swift:3741`; `tokens.json` declares `radius.bubble: 20` as of L9. Confidence: exact. */
    public const val USER_BUBBLE_RADIUS_PT: Int = 20

    /** User bubble maximum width. Source: `NativeParityTokens.swift:98` (`userBubbleMaxWidth = 277`), applied at `ChatPage.swift:3742`; `tokens.json` declares `geometry.bubbleMaxWidth: 277` as of L9. Confidence: exact. */
    public const val USER_BUBBLE_MAX_WIDTH_PT: Int = 277

    // The user bubble's 16 h / 10 v text insets (`ChatPage.swift:3738-3739`) are
    // **not** declared here any more: L22 landed the spec split, so the bubble
    // surface reads `geometry.bubblePaddingH/V` (16/10) from the theme and this
    // file no longer holds a second copy of those two numbers. Keeping a constant
    // next to a token with the same value is the drift this repo rejects.

    /** User bubble line box (the extra leading is `22 - font.lineHeight`). Source: `NativeParityTokens.swift:74-76`. Confidence: exact. */
    public const val USER_BUBBLE_LINE_HEIGHT_PT: Int = 22

    /** Assistant paragraph spacing. Source: `NativeChatTheme.swift:49`. Confidence: exact. */
    public const val MESSAGE_PARAGRAPH_SPACING_PT: Int = 20

    /** Assistant list spacing. Source: `NativeChatTheme.swift:50`. Confidence: exact. */
    public const val MESSAGE_LIST_SPACING_PT: Int = 10

    /** Assistant body content margin. Source: `NativeChatTheme.swift:48`. Confidence: exact. */
    public const val MESSAGE_CONTENT_MARGIN_PT: Int = 20

    // ----------------------------------------------------------------- composer

    /** Composer card width at 402 pt. Source: `NativeParityTokens.swift:96` (`composerWidth = 370`); the 16 pt side insets are at `ChatPage.swift:1892`. Confidence: exact. */
    public const val COMPOSER_WIDTH_PT: Int = 370

    /**
     * Collapsed composer card height, **as drawn**: 94 pt.
     *
     * Authority is commit `86a39aac` (`fix: tighten composer bottom spacing`),
     * not a guess: it changed `ChatPage.swift:1869` from `.padding(.bottom, 12)`
     * to `.padding(.bottom, 8)`, so the card is now the sum of its parts —
     * 8 (port top) + 36 (port min) + 10 (control top) + 32 (control row) +
     * 8 (control bottom) = **94**.
     *
     * `NativeParityTokens.swift:97` still declares `composerHeight = 98`. That
     * constant is **stale** with respect to the drawn layout: the composer's height
     * is its padding stack (the view never frames itself to that literal), so 98 is
     * not the number to implement. The official-web yardstick is a different value
     * on purpose and lives in [UiMetrics.COMPOSER_HEIGHT_PT].
     * Confidence: exact (commit diff, re-summed).
     */
    public const val COMPOSER_HEIGHT_PT: Int = 94

    /** Composer card corner radius (continuous). Source: `NativeParityTokens.swift:116` (`radiusPanel = 28`), drawn at `ChatPage.swift:1884`; `tokens.json` declares `radius.composer: 28` as of L9. Confidence: exact. */
    public const val COMPOSER_RADIUS_PT: Int = 28

    /** Composer outer side inset. Source: `ChatPage.swift:1892`. Confidence: exact. */
    public const val COMPOSER_OUTER_MARGIN_H_PT: Int = 16

    /** Composer outer top inset. Source: `ChatPage.swift:1893`. Confidence: exact. */
    public const val COMPOSER_OUTER_MARGIN_TOP_PT: Int = 12

    /**
     * Composer outer bottom inset, now **0**.
     *
     * Source: commit `86a39aac` changed `ChatPage.swift:1894` `.padding(.bottom, 8)`
     * to `.padding(.bottom, 0)` — the stats line sits against the home-indicator
     * safe area instead of stacking an 8 pt card inset on top of it. `tokens.json`
     * still declares `geometry.composerOuterMarginBottom: 8`; the implementation is
     * the baseline, so 0 is the value to draw.
     * Confidence: exact.
     */
    public const val COMPOSER_OUTER_MARGIN_BOTTOM_PT: Int = 0

    /** Input port minimum height (one line). Source: `ChatPage.swift:1851` (`.frame(minHeight: 36)`). Confidence: exact. */
    public const val COMPOSER_PORT_MIN_HEIGHT_PT: Int = 36

    /** Input port maximum height before it scrolls internally. Source: `ChatPage.swift:1841` (`maxHeight: 336`). Confidence: exact. */
    public const val COMPOSER_PORT_MAX_HEIGHT_PT: Int = 336

    /** Input port leading inset. Source: `ChatPage.swift:1849`. Confidence: exact. */
    public const val COMPOSER_PORT_LEADING_PT: Int = 14

    /** Input port trailing inset (the send column is outside it). Source: `ChatPage.swift:1850`. Confidence: exact. */
    public const val COMPOSER_PORT_TRAILING_PT: Int = 8

    /** Input port top inset. Source: `ChatPage.swift:1852`. Confidence: exact. */
    public const val COMPOSER_PORT_TOP_PT: Int = 8

    /**
     * Composer font size, forced to 16 so iOS focus zoom never triggers.
     * Source: `NativeParityTokens.swift:79`. Reference only: Android text sizes
     * come from `LocalUiType`. Confidence: exact.
     */
    public const val COMPOSER_FONT_REF_PT: Int = 16

    /** Control row height. Source: `ChatPage.swift:1867` (`.frame(height: 32)`). Confidence: exact. */
    public const val COMPOSER_CONTROL_ROW_HEIGHT_PT: Int = 32

    /** Control row leading inset. Source: `ChatPage.swift:1865`. Confidence: exact. */
    public const val COMPOSER_CONTROL_LEADING_PT: Int = 14

    /** Trailing width reserved for the send circle. Source: `ChatPage.swift:1866` (`.padding(.trailing, 40)`). Confidence: exact. */
    public const val COMPOSER_CONTROL_TRAILING_RESERVE_PT: Int = 40

    /** Control row top padding. Source: `ChatPage.swift:1868`. Confidence: exact. */
    public const val COMPOSER_CONTROL_TOP_PT: Int = 10

    /**
     * Control row bottom padding: **8** since commit `86a39aac`
     * (`ChatPage.swift:1869`, `.padding(.bottom, 12)` -> `8`). It is the 4 pt that
     * came out of the card, which is why [COMPOSER_HEIGHT_PT] is 94 and not 98.
     * Confidence: exact.
     */
    public const val COMPOSER_CONTROL_BOTTOM_PT: Int = 8

    /** Send circle diameter. Source: `ChatPage.swift:1967` (`.frame(width: 34, height: 34)`). Confidence: exact. */
    public const val SEND_DIAMETER_PT: Int = 34

    /** Send control hit frame. Source: `ChatPage.swift:1970` (`.frame(width: 44, height: 44)`). Confidence: exact. */
    public const val SEND_HIT_PT: Int = 44

    /** Send/stop overlay inset from the card's trailing/bottom edges. Source: `ChatPage.swift:1880-1881`. Confidence: exact. */
    public const val SEND_OVERLAY_INSET_PT: Int = 2

    /** Stop circle when it shares the row with a submit control. Source: `ChatPage.swift:1876` (`stopButton(diameter: 24, ...)`). Confidence: exact. */
    public const val STOP_INLINE_DIAMETER_PT: Int = 24

    /** Stop circle when it replaces the send control. Source: `ChatPage.swift:1963` (`stopButton(diameter: 34, ...)`). Confidence: exact. */
    public const val STOP_DIAMETER_PT: Int = 34

    /** "回到最新" pill horizontal padding. Source: `ChatPage.swift:1244`. Confidence: exact. */
    public const val BACK_TO_LATEST_PADDING_H_PT: Int = 12

    /** "回到最新" pill vertical padding; with the 12 pt caption line this is the ~28-30 pt pill. Source: `ChatPage.swift:1245`. Confidence: exact. */
    public const val BACK_TO_LATEST_PADDING_V_PT: Int = 8

    /** "回到最新" pill corner radius. Source: `ChatPage.swift:1248`. Confidence: exact. */
    public const val BACK_TO_LATEST_RADIUS_PT: Int = 12

    // -------------------------------------------------------------------- queue

    /** One queued-message row. Source: `ChatPage.swift:1690` (`.frame(height: 28)`), radius at `:1692`. Confidence: exact. */
    public const val QUEUE_ROW_HEIGHT_PT: Int = 28

    /** Queued-message row corner radius. Source: `ChatPage.swift:1692` (`RoundedRectangle(cornerRadius: 8)`). Confidence: exact. */
    public const val QUEUE_ROW_RADIUS_PT: Int = 8

    /** Queue strip's collapsed header row. Source: `ChatPage.swift:1557`. Confidence: exact. */
    public const val QUEUE_DOCK_HEADER_HEIGHT_PT: Int = 32

    /** Queue strip background corner radius. Source: `ChatPage.swift:1577`. Confidence: exact. */
    public const val QUEUE_DOCK_RADIUS_PT: Int = 12

    /** Queue row inner horizontal padding. Source: `ChatPage.swift:1641`. Confidence: exact. */
    public const val QUEUE_ROW_INSET_H_PT: Int = 10

    // ------------------------------------------------------------------- cards

    /** Permission card corner radius. Source: `ChatPage.swift:4467`. Confidence: exact. */
    public const val PERMISSION_CARD_RADIUS_PT: Int = 12

    /** Permission card inner padding. Source: `ChatPage.swift:4465` (`.padding(14)`). Confidence: exact. */
    public const val PERMISSION_CARD_PADDING_PT: Int = 14

    /** Permission card row spacing. Source: `ChatPage.swift:4425` (`VStack(spacing: 10)`). Confidence: exact. */
    public const val PERMISSION_CARD_SPACING_PT: Int = 10

    /** Permission card outer margin. Source: `ChatPage.swift:4468-4469`. Confidence: exact. */
    public const val PERMISSION_CARD_OUTER_MARGIN_H_PT: Int = 16

    /** Permission card outer vertical margin. Source: `ChatPage.swift:4469`. Confidence: exact. */
    public const val PERMISSION_CARD_OUTER_MARGIN_V_PT: Int = 8

    /** Allow-once button height. Source: `ChatPage.swift:4447` (`.frame(height: 48)`). Confidence: exact. */
    public const val PERMISSION_PRIMARY_HEIGHT_PT: Int = 48

    /** Allow-once button radius. Source: `ChatPage.swift:4448`. Confidence: exact. */
    public const val PERMISSION_PRIMARY_RADIUS_PT: Int = 12

    /** Deny button height (the 44 pt hit minimum). Source: `ChatPage.swift:4460`. Confidence: exact. */
    public const val PERMISSION_DENY_HEIGHT_PT: Int = 44

    /** Expanded tool card corner radius. Source: `NativeToolCardChrome.swift:33` (`radiusLG` = 16). Confidence: exact. */
    public const val TOOL_CARD_RADIUS_PT: Int = 16

    /** Expanded tool card hairline border. Source: `NativeToolCardChrome.swift:34`. Confidence: exact. */
    public const val TOOL_CARD_BORDER_PT: Float = 0.5f

    /** Expanded tool card outer margin (top = bottom = leading). Source: `NativeToolCardChrome.swift:34`. Confidence: exact. */
    public const val TOOL_CARD_OUTER_PT: Int = 4

    /** Code/tool banner padding: top. Source: `NativeToolCardChrome.swift:37`. Confidence: exact. */
    public const val TOOL_CARD_HEADER_TOP_PT: Int = 10

    /** Code/tool banner padding: bottom. Source: `NativeToolCardChrome.swift:37`. Confidence: exact. */
    public const val TOOL_CARD_HEADER_BOTTOM_PT: Int = 8

    /** Code/tool banner padding: leading. Source: `NativeToolCardChrome.swift:38`. Confidence: exact. */
    public const val TOOL_CARD_HEADER_LEADING_PT: Int = 22

    /** Code/tool banner padding: trailing. Source: `NativeToolCardChrome.swift:38`. Confidence: exact. */
    public const val TOOL_CARD_HEADER_TRAILING_PT: Int = 18

    /** Copy / wrap action target. Source: `NativeToolCardChrome.swift:43`. Confidence: exact. */
    public const val TOOL_CARD_ACTION_TARGET_PT: Int = 24

    /** Code body horizontal padding. Source: `NativeToolCardChrome.swift:48`. Confidence: exact. */
    public const val TOOL_CARD_BODY_HORIZONTAL_PT: Int = 22

    /** Code body top padding. Source: `NativeToolCardChrome.swift:49`. Confidence: exact. */
    public const val TOOL_CARD_BODY_TOP_PT: Int = 6

    /** Code body bottom padding. Source: `NativeToolCardChrome.swift:49`. Confidence: exact. */
    public const val TOOL_CARD_BODY_BOTTOM_PT: Int = 20

    /** IN/OUT section padding. Source: `NativeToolCardChrome.swift:56`. Confidence: exact. */
    public const val TOOL_CARD_IO_HORIZONTAL_PT: Int = 16

    /** IN/OUT section vertical padding. Source: `NativeToolCardChrome.swift:56`. Confidence: exact. */
    public const val TOOL_CARD_IO_VERTICAL_PT: Int = 12

    /** IN/OUT section max height. Source: `NativeToolCardChrome.swift:57`. Confidence: exact. */
    public const val TOOL_CARD_IO_MAX_HEIGHT_PT: Int = 150

    /** Details-card outer padding. Source: `NativeToolDetailsCard.swift:23`. Confidence: exact. */
    public const val TOOL_DETAILS_PADDING_PT: Int = 12

    /** Details-card per-item vertical padding. Source: `NativeToolDetailsCard.swift:24`. Confidence: exact. */
    public const val TOOL_DETAILS_ITEM_PADDING_V_PT: Int = 10

    /** Details-card per-item horizontal padding. Source: `NativeToolDetailsCard.swift:24`. Confidence: exact. */
    public const val TOOL_DETAILS_ITEM_PADDING_H_PT: Int = 14

    /** Details-card list max height. Source: `NativeToolDetailsCard.swift:25`. Confidence: exact. */
    public const val TOOL_DETAILS_LIST_MAX_HEIGHT_PT: Int = 320

    /** Standalone code block (non-tool-card) corner radius. Source: `Components.swift:221` (`.clipShape(RoundedRectangle(cornerRadius: 12 ...))`). Confidence: exact. */
    public const val CODE_BLOCK_RADIUS_PT: Int = 12

    /** Code block banner strip height. Source: `Components.swift:196` (`.frame(height: 34)`). Confidence: exact. */
    public const val CODE_BLOCK_BANNER_HEIGHT_PT: Int = 34

    /** Code block body padding. Source: `Components.swift:209` (`.padding(12)`). Confidence: exact. */
    public const val CODE_BLOCK_PADDING_PT: Int = 12

    /** Code block "expand all" footer height. Source: `Components.swift:216` (`.frame(height: 32)`). Confidence: exact. */
    public const val CODE_BLOCK_EXPAND_HEIGHT_PT: Int = 32

    /** Collapsed long-code max height. Source: `Components.swift:207` (`isLong ? 300 : nil`). Confidence: exact. */
    public const val CODE_BLOCK_COLLAPSED_MAX_HEIGHT_PT: Int = 300

    /** Todo dock height. Source: `NativeParityTokens.swift:102`. Confidence: exact. */
    public const val TODO_DOCK_HEIGHT_PT: Int = 36

    /** Todo list maximum height. Source: `NativeParityTokens.swift:103`. Confidence: exact. */
    public const val TODO_LIST_MAX_HEIGHT_PT: Int = 180

    /** Question composer maximum height. Source: `NativeParityTokens.swift:104`. Confidence: exact. */
    public const val QUESTION_MAX_HEIGHT_PT: Int = 520

    /** Question option minimum row height. Source: `NativeParityTokens.swift:105`. Confidence: exact. */
    public const val QUESTION_OPTION_MIN_HEIGHT_PT: Int = 40

    /** Submitted plan card height. Source: `NativeParityTokens.swift:106`. Confidence: exact. */
    public const val PLAN_CARD_HEIGHT_PT: Int = 60

    /** Subagent menu width. Source: `NativeParityTokens.swift:107`. Confidence: exact. */
    public const val SUBAGENT_MENU_WIDTH_PT: Int = 336

    // ---------------------------------------------------------------- native header controls

    /** ChatPage's drawer control width. Source: `ChatPage.swift:227`. Confidence: exact. */
    public const val CHAT_DRAWER_WIDTH_PT: Int = 32
    /** Device entry control width. Source: `ChatPage.swift:238`. Confidence: exact. */
    public const val CHAT_DEVICE_ENTRY_WIDTH_PT: Int = 36
    /** Overflow menu control width. Source: `ChatPage.swift:291`. Confidence: exact. */
    public const val CHAT_HEADER_MENU_WIDTH_PT: Int = 44
    /** Fixed side reserve around the centered session title. Source: `ChatPage.swift:219`. Confidence: exact. */
    public const val CHAT_HEADER_TITLE_RESERVE_PT: Int = 104
    /** Gap inside the device/menu group. Source: `ChatPage.swift:235`. Confidence: exact. */
    public const val CHAT_HEADER_GROUP_GAP_PT: Int = 4
    /** Drawer icon box. Source: `ChatPage.swift:225-227`. Confidence: exact. */
    public const val CHAT_DRAWER_ICON_PT: Int = 20
    /** Drawer icon stroke. Source: SF `sidebar.left` 20pt Android vector approximation. Confidence: approximated. */
    public const val CHAT_DRAWER_ICON_STROKE_TENTH_PT: Float = 1.5f
    /** Drawer icon inset and corner. Source: 20pt icon's interior stroke geometry. Confidence: approximated. */
    public const val CHAT_DRAWER_ICON_INSET_PT: Int = 2
    public const val CHAT_DRAWER_ICON_CORNER_TENTH_PT: Float = 1.5f
    /** Online marker diameter. Source: `NativeDeviceConnectionPage.swift:16-19`. Confidence: exact. */
    public const val CHAT_DEVICE_STATUS_DOT_PT: Int = 6
    /** Online marker border. Source: `NativeDeviceConnectionPage.swift:19`. Confidence: exact. */
    public const val CHAT_DEVICE_STATUS_BORDER_PT: Int = 1
    /** Online marker top/right offsets in the 36x44 entry. Source: `NativeDeviceConnectionPage.swift:17-19`. Confidence: exact. */
    public const val CHAT_DEVICE_STATUS_INSET_PT: Int = 9
    /** Device glyph's source font size. Source: `NativeDeviceConnectionPage.swift:11`. Confidence: exact. */
    public const val CHAT_DEVICE_ICON_SIZE_PT: Int = 18
    /** The Android line drawing's 10x20 phone outline box approximates the SF symbol ink. Source: `NativeDeviceConnectionPage.swift:11`. Confidence: approximated. */
    public const val CHAT_DEVICE_ICON_BOX_WIDTH_PT: Int = 12
    public const val CHAT_DEVICE_ICON_BOX_HEIGHT_PT: Int = 20
    public const val CHAT_DEVICE_BODY_WIDTH_PT: Int = 10
    public const val CHAT_DEVICE_BODY_STROKE_TENTH_PT: Float = 1.35f
    public const val CHAT_DEVICE_BODY_CORNER_PT: Int = 2
    public const val CHAT_DEVICE_SPEAKER_LEFT_PT: Int = 4
    public const val CHAT_DEVICE_SPEAKER_RIGHT_PT: Int = 6
    public const val CHAT_DEVICE_SPEAKER_BOTTOM_PT: Int = 2

    // ----------------------------------------------------------- question composer

    /** Question takeover's card radius. Source: `NativeParityTokens.swift:112` (`radiusXL`). Confidence: exact. */
    public const val QUESTION_CARD_RADIUS_PT: Int = 20
    /** Bottom breathing room below the question footer. Source: `NativeQuestionComposer.swift:269`. Confidence: exact. */
    public const val QUESTION_CARD_BOTTOM_PT: Int = 10
    /** Composer-seat bottom inset around the takeover. Source: `ChatPage.swift:1718,1732`. Confidence: exact. */
    public const val QUESTION_SEAT_BOTTOM_PT: Int = 8
    /** Question header top inset. Source: `NativeQuestionComposer.swift:308`. Confidence: exact. */
    public const val QUESTION_HEADER_TOP_PT: Int = 20
    /** Question header leading inset. Source: `NativeQuestionComposer.swift:308`. Confidence: exact. */
    public const val QUESTION_HEADER_LEADING_PT: Int = 24
    /** Question header trailing inset. Source: `NativeQuestionComposer.swift:308`. Confidence: exact. */
    public const val QUESTION_HEADER_TRAILING_PT: Int = 16
    /** Minimized question header bottom inset. Source: `NativeQuestionComposer.swift:308`. Confidence: exact. */
    public const val QUESTION_HEADER_MINIMIZED_BOTTOM_PT: Int = 14
    /** Gap between question copy and header actions. Source: `NativeQuestionComposer.swift:290`. Confidence: exact. */
    public const val QUESTION_HEADER_COPY_ACTION_GAP_PT: Int = 16
    /** Question eyebrow-to-prompt stack gap. Source: `NativeQuestionComposer.swift:290`. Confidence: exact. */
    public const val QUESTION_HEADER_STACK_GAP_PT: Int = 5
    /** Question eyebrow size. Source: `NativeQuestionComposer.swift:293`. Confidence: exact. */
    public const val QUESTION_EYEBROW_SIZE_PT: Int = 11
    /** Question prompt size. Source: `NativeQuestionComposer.swift:299`. Confidence: exact. */
    public const val QUESTION_PROMPT_SIZE_PT: Int = 16
    /** Question minimize and close hit-area size. Source: `NativeQuestionComposer.swift:323-328`. Confidence: exact. */
    public const val QUESTION_HEADER_ACTION_SIZE_PT: Int = 32
    /** Gap between minimize and close controls. Source: `NativeQuestionComposer.swift:312`. Confidence: exact. */
    public const val QUESTION_HEADER_ACTION_GAP_PT: Int = 4
    /** Collapsed question header bottom inset is zero. Source: `NativeQuestionComposer.swift:308`. Confidence: exact. */
    public const val QUESTION_HEADER_EXPANDED_BOTTOM_PT: Int = 0
    /** Question choice-list top inset. Source: `NativeQuestionComposer.swift:397`. Confidence: exact. */
    public const val QUESTION_OPTIONS_TOP_PT: Int = 12
    /** Question choice-list horizontal inset. Source: `NativeQuestionComposer.swift:397`. Confidence: exact. */
    public const val QUESTION_OPTIONS_INSET_H_PT: Int = 8
    /** Question choice-list bottom inset. Source: `NativeQuestionComposer.swift:397`. Confidence: exact. */
    public const val QUESTION_OPTIONS_BOTTOM_PT: Int = 4
    /** Question option row gap. Source: `NativeQuestionComposer.swift:389`. Confidence: exact. */
    public const val QUESTION_OPTION_GAP_PT: Int = 1
    /** Question option row vertical inset. Source: `NativeQuestionComposer.swift:433`. Confidence: exact. */
    public const val QUESTION_OPTION_PADDING_V_PT: Int = 8
    /** Question option row horizontal inset. Source: `NativeQuestionComposer.swift:433`. Confidence: exact. */
    public const val QUESTION_OPTION_PADDING_H_PT: Int = 6
    /** Question option corner radius. Source: `NativeParityTokens.swift:113` (`radiusMD`). Confidence: exact. */
    public const val QUESTION_OPTION_RADIUS_PT: Int = 12
    /** Question option number/check box diameter. Source: `NativeQuestionOptionRow.swift:17`. Confidence: exact. */
    public const val QUESTION_OPTION_MARK_SIZE_PT: Int = 24
    /** Question no-options answer field minimum height. Source: `NativeQuestionComposer.swift:361`. Confidence: exact. */
    public const val QUESTION_FREE_ANSWER_HEIGHT_PT: Int = 64
    /** Question footer top inset. Source: `NativeQuestionComposer.swift:512`. Confidence: exact. */
    public const val QUESTION_FOOTER_TOP_PT: Int = 12
    /** Question footer horizontal inset. Source: `NativeQuestionComposer.swift:512`. Confidence: exact. */
    public const val QUESTION_FOOTER_INSET_H_PT: Int = 10
    /** Question pager/action gap. Source: `NativeQuestionComposer.swift:489,512`. Confidence: exact. */
    public const val QUESTION_FOOTER_GAP_PT: Int = 12
    /** Question pager icon target. Source: `NativeQuestionComposer.swift:517-525`. Confidence: exact. */
    public const val QUESTION_PAGER_TARGET_PT: Int = 28
    /** Question action button height. Source: `NativeQuestionComposer.swift:674`. Confidence: exact. */
    public const val QUESTION_ACTION_HEIGHT_PT: Int = 36
    /** Question action button corner radius. Source: `NativeParityTokens.swift:114` (`radiusMD`). Confidence: exact. */
    public const val QUESTION_ACTION_RADIUS_PT: Int = 12
    /** Question action horizontal inset. Source: `NativeQuestionComposer.swift:687`. Confidence: exact. */
    public const val QUESTION_ACTION_PADDING_H_PT: Int = 14
    /** Question option text size. Source: `NativeQuestionComposer.swift:418`. Confidence: exact. */
    public const val QUESTION_OPTION_TEXT_SIZE_PT: Int = 14
    /** Question details horizontal inset. Source: `NativeQuestionComposer.swift:355`. Confidence: exact. */
    public const val QUESTION_DETAIL_INSET_H_PT: Int = 2
    /** Question detail vertical padding. Source: `NativeQuestionComposer.swift:356`. Confidence: exact. */
    public const val QUESTION_DETAIL_INSET_V_PT: Int = 8
    /** Question answer row vertical inset. Source: `NativeQuestionComposer.swift:462`. Confidence: exact. */
    public const val QUESTION_ANSWER_INSET_V_PT: Int = 8
    /** Question answer row horizontal inset. Source: `NativeQuestionComposer.swift:464`. Confidence: exact. */
    public const val QUESTION_ANSWER_INSET_H_PT: Int = 6
    /** Question action text button horizontal inset. Source: `NativeQuestionComposer.swift:682`. Confidence: exact. */
    public const val QUESTION_ACTION_TEXT_INSET_H_PT: Int = 10
    /** Question action text button vertical inset. Source: `NativeQuestionComposer.swift:682`. Confidence: exact. */
    public const val QUESTION_ACTION_TEXT_INSET_V_PT: Int = 8
    /** Question option list member minimum. Source: `NativeParityTokens.swift:105` (`questionOptionMinHeight`). Confidence: exact. */
    public const val QUESTION_OPTION_ROW_HEIGHT_PT: Int = 40
    /** Question footer error slot minimum height. Source: `NativeQuestionComposer.swift:493`. Confidence: exact. */
    public const val QUESTION_ERROR_SLOT_HEIGHT_PT: Int = 16
    /** Question card outer shadow approximation. Source: `NativeQuestionComposer.swift:282-283`; Android elevation differs. Confidence: approximated. */
    public const val QUESTION_CARD_SHADOW_PT: Int = 4
    /** Question answer body horizontal inset. Source: `NativeQuestionComposer.swift:397`. Confidence: exact. */
    public const val QUESTION_BODY_INSET_H_PT: Int = 8
    /** Question footer pager gap. Source: `NativeQuestionComposer.swift:516`. Confidence: exact. */
    public const val QUESTION_PAGER_GAP_PT: Int = 6
    /** Question panel interior bottom inset. Source: `NativeQuestionComposer.swift:269`. Confidence: exact. */
    public const val QUESTION_PANEL_BOTTOM_PT: Int = 10

    // -------------------------------------------------------------- plan review

    /** Plan-review card corner radius. Source: `NativeParityTokens.swift:110` (`radiusXL`). Confidence: exact. */
    public const val PLAN_REVIEW_CARD_RADIUS_PT: Int = 20
    /** Plan-review button height. Source: `NativePlanReviewCard.swift:200-210`. Confidence: exact. */
    public const val PLAN_REVIEW_BUTTON_HEIGHT_PT: Int = 36
    /** Plan-review button radius. Source: `NativeParityTokens.swift:111` (`radiusMD`). Confidence: exact. */
    public const val PLAN_REVIEW_BUTTON_RADIUS_PT: Int = 12
    /** Plan-review button horizontal inset. Source: `NativePlanReviewCard.swift:210`. Confidence: exact. */
    public const val PLAN_REVIEW_BUTTON_INSET_H_PT: Int = 14
    /** Gap between plan-review decision buttons. Source: `NativePlanReviewCard.swift:174`. Confidence: exact. */
    public const val PLAN_REVIEW_BUTTON_GAP_PT: Int = 8
    /** Plan-review section gap. Source: `NativePlanReviewCard.swift:141`. Confidence: exact. */
    public const val PLAN_REVIEW_SECTION_GAP_PT: Int = 8
    /** Plan-review card shadow approximation. Source: `NativePlanReviewCard.swift:97-98`; Android elevation differs. Confidence: approximated. */
    public const val PLAN_REVIEW_SHADOW_PT: Int = 3

    // ---------------------------------------------------------------- goal + todo dock

    /** GoalBar row height. Source: `ChatPage.swift:1452`. Confidence: exact. */
    public const val GOAL_BAR_HEIGHT_PT: Int = 40
    /** GoalBar radius. Source: `ChatPage.swift:1454`. Confidence: exact. */
    public const val GOAL_BAR_RADIUS_PT: Int = 10
    /** GoalBar horizontal inset. Source: `ChatPage.swift:1451`. Confidence: exact. */
    public const val GOAL_BAR_INSET_H_PT: Int = 10
    /** GoalBar icon size. Source: `ChatPage.swift:1421`. Confidence: exact. */
    public const val GOAL_BAR_ICON_SIZE_PT: Int = 14
    /** GoalBar element gap. Source: `ChatPage.swift:1420`. Confidence: exact. */
    public const val GOAL_BAR_GAP_PT: Int = 8
    /** GoalBar phase text size. Source: `ChatPage.swift:1425`. Confidence: exact. */
    public const val GOAL_BAR_PHASE_SIZE_PT: Int = 11
    /** GoalBar objective text size. Source: `ChatPage.swift:1428`. Confidence: exact. */
    public const val GOAL_BAR_OBJECTIVE_SIZE_PT: Int = 13
    /** GoalBar action text size. Source: `ChatPage.swift:1437,1440`. Confidence: exact. */
    public const val GOAL_BAR_ACTION_SIZE_PT: Int = 12
    /** Android target icon stroke approximation for the iOS target symbol. Confidence: approximated. */
    public const val GOAL_BAR_ICON_STROKE_TENTH_PT: Float = 1.4f
    /** Todo dock horizontal inset. Source: `NativeTodoDock.swift:93`. Confidence: exact. */
    public const val TODO_DOCK_INSET_H_PT: Int = 12
    /** Todo dock vertical inset. Source: `NativeTodoDock.swift:94`. Confidence: exact. */
    public const val TODO_DOCK_INSET_V_PT: Int = 6
    /** Todo dock/list row gap. Source: `NativeTodoDock.swift:89,166`. Confidence: exact. */
    public const val TODO_DOCK_GAP_PT: Int = 8
    /** Todo dock header line box. Source: `NativeTodoDock.swift:138`. Confidence: exact. */
    public const val TODO_DOCK_HEADER_LINE_PT: Int = 24
    /** Todo item line box. Source: `NativeTodoDock.swift:196`. Confidence: exact. */
    public const val TODO_ROW_HEIGHT_PT: Int = 20
    /** Todo status slot size. Source: `NativeTodoDock.swift:187`. Confidence: exact. */
    public const val TODO_STATUS_SLOT_PT: Int = 16
    /** Todo checklist artwork size. Source: `NativeTodoDock.swift:118`. Confidence: exact. */
    public const val TODO_CHECKLIST_ICON_PT: Int = 14
    /** Todo status dot size. Source: `NativeTodoDock.swift:187-188`. Confidence: exact. */
    public const val TODO_STATUS_DOT_PT: Int = 6
    /** Todo card radius. Source: `NativeParityTokens.swift:101` (`radiusLG`). Confidence: exact. */
    public const val TODO_DOCK_RADIUS_PT: Int = 16
    /** Todo line font size. Source: `NativeTodoDock.swift:121,125,190`. Confidence: exact. */
    public const val TODO_TEXT_SIZE_PT: Int = 13
    /** Todo glyph stroke width. Source: `NativeTodoDock.swift:206`. Confidence: approximated. */
    public const val TODO_GLYPH_STROKE_PT: Float = 0.875f
    /** Todo glyph dot radius. Source: `NativeTodoDock.swift:218-220`. Confidence: exact. */
    public const val TODO_GLYPH_DOT_RADIUS_PT: Float = 1.75f
    /** Todo glyph circle y centers. Source: `NativeTodoDock.swift:217`. Confidence: exact. */
    public const val TODO_GLYPH_ROW1_Y_PT: Float = 4.5f
    public const val TODO_GLYPH_ROW2_Y_PT: Float = 11.5f
    /** Todo glyph bullet center x. Source: `NativeTodoDock.swift:218`. Confidence: exact. */
    public const val TODO_GLYPH_BULLET_X_PT: Float = 2.0f
    /** Todo glyph text start and end. Source: `NativeTodoDock.swift:219-220`. Confidence: exact. */
    public const val TODO_GLYPH_LINE_START_X_PT: Int = 6
    public const val TODO_GLYPH_LINE_END_X_PT: Int = 13
    /** Todo panel shadow approximation. Source: `NativeTodoDock.swift:99-100`; Android elevation differs. Confidence: approximated. */
    public const val TODO_DOCK_SHADOW_PT: Int = 3
    /** Todo status dot row spacing. Source: `NativeTodoDock.swift:186-196`. Confidence: exact. */
    public const val TODO_STATUS_GAP_PT: Int = 10
    /** Todo status dot dimensions. Source: `NativeTodoDock.swift:187-188`. Confidence: exact. */
    public const val TODO_STATUS_DOT_CONTAINER_PT: Int = 16

    // ------------------------------------------------------------ dp accessors

    /** [HEADER_HEIGHT_PT] as a Compose length. */
    public val headerHeight: Dp get() = HEADER_HEIGHT_PT.dp

    /** [HEADER_HORIZONTAL_INSET_PT] as a Compose length. */
    public val headerHorizontalInset: Dp get() = HEADER_HORIZONTAL_INSET_PT.dp

    /** [TRANSCRIPT_SIDE_INSET_PT] as a Compose length. */
    public val transcriptSideInset: Dp get() = TRANSCRIPT_SIDE_INSET_PT.dp

    /** [FLOW_GAP_PT] as a Compose length. */
    public val flowGap: Dp get() = FLOW_GAP_PT.dp

    /** [RESPONSE_GAP_PT] as a Compose length. */
    public val responseGap: Dp get() = RESPONSE_GAP_PT.dp

    /** [TURN_GAP_PT] as a Compose length. */
    public val turnGap: Dp get() = TURN_GAP_PT.dp

    /** [DISCLOSURE_ROW_HEIGHT_PT] as a Compose length. */
    public val disclosureRowHeight: Dp get() = DISCLOSURE_ROW_HEIGHT_PT.dp

    /** [USER_BUBBLE_MAX_WIDTH_PT] as a Compose length. */
    public val userBubbleMaxWidth: Dp get() = USER_BUBBLE_MAX_WIDTH_PT.dp

    /** [COMPOSER_WIDTH_PT] as a Compose length. */
    public val composerWidth: Dp get() = COMPOSER_WIDTH_PT.dp

    /** [COMPOSER_HEIGHT_PT] as a Compose length. */
    public val composerHeight: Dp get() = COMPOSER_HEIGHT_PT.dp

    /** [COMPOSER_RADIUS_PT] as a Compose length. */
    public val composerRadius: Dp get() = COMPOSER_RADIUS_PT.dp

    /** [SEND_DIAMETER_PT] as a Compose length. */
    public val sendDiameter: Dp get() = SEND_DIAMETER_PT.dp

    /** [PERMISSION_CARD_RADIUS_PT] as a Compose length. */
    public val permissionCardRadius: Dp get() = PERMISSION_CARD_RADIUS_PT.dp
}
