// Resolved token values for one theme mode.
// SPDX-License-Identifier: MIT
//
// Every value here comes from `spec/ui/v1/tokens.json` through the generated
// `UiTokenData`; nothing is re-typed. A `null` channel in the spec (only
// `colors.background`) stays unmeasured and resolves to [Color.Unspecified] so
// the caller renders on the platform surface instead of an invented colour.
package com.phonedsh.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonedsh.ui.theme.generated.TokenColor
import com.phonedsh.ui.theme.generated.TokenDp
import com.phonedsh.ui.theme.generated.TokenGeometry
import com.phonedsh.ui.theme.generated.UiTokenData

/** The twenty-two colour tokens, resolved for one mode. */
@Immutable
public data class UiColors(
    public val primary: Color,
    public val secondary: Color,
    public val tertiary: Color,
    public val caption: Color,
    /** `colors.background` is `tier: unmeasured`; [Color.Unspecified] means "platform surface". */
    public val background: Color,
    public val input: Color,
    public val selector: Color,
    public val code: Color,
    public val codeBanner: Color,
    public val inlineCode: Color,
    public val border: Color,
    public val borderLight: Color,
    public val accent: Color,
    public val success: Color,
    public val danger: Color,
    public val warning: Color,
    public val onAccent: Color,
    /** Page-layer override for the approval CTA (baseline inconsistency, mirrored on purpose). */
    public val ctaOverride: Color,
    /**
     * The page-layer fixed blue (`NativePagePalette.accentFill`). iOS has two
     * blues: the theme `accent` #3964FE on the approval card, and this one on
     * the sessions / schedules / draft / workspace CTAs. Appended, not inserted:
     * a positional construction that predates it cannot silently shift.
     */
    public val pageAccent: Color,
    /** The switch's on-track (`UIColor.systemGreen`). */
    public val switchOn: Color,
    /** System `.primary` (label): the ink iOS gives page rows. */
    public val systemLabel: Color,
    /** System `.secondary` (secondaryLabel): the ink iOS gives page captions. */
    public val systemSecondary: Color,
    /**
     * The page fill (`NativePagePalette.page`): white in light, black in dark.
     *
     * L75: the pages iOS paints with `.background(NativePagePalette.page)` — files,
     * workspace-folders and the preview — are white, while `colors.background` is
     * unmeasured (a dynamic system colour). Reading that unresolved token leaves
     * the Material surface (#F2F3F5) showing through, one grey step off the
     * baseline. Appended last so no positional construction shifts.
     */
    public val page: Color,
    /**
     * The task screen's pure black (`TaskScreenPage.swift:66`).
     *
     * L75: the screen is defined as "pure black, low brightness" and the engine's
     * `PhoneDSH.Background.TaskScreen` does not read the theme, so this is black in
     * both appearances. No other token is black in light mode, which is why the
     * page cannot borrow one.
     */
    public val taskScreen: Color,
    /**
     * L80: the grouped-list page fill (`UIColor.systemGroupedBackground`), #F2F2F7
     * in light. `page-shell` used `selector` (#F2F3F5) for the whole frame; a
     * three-channel delta of (0,1,2) turned 54% of the frame into "different".
     * Appended, never inserted.
     */
    public val groupedBackground: Color,
    /**
     * L79: the settings segmented picker's capsule track, #EEEEEF in light
     * (`ios-settings.png` 71,563 px). `selector` (#F2F3F5) is the app's own
     * segmented fill and is 4-6/255 away, which covers the whole 370x32 pt track.
     * Appended, never inserted.
     */
    public val segmentTrack: Color,
    /**
     * L79: the ink `.font(.footnote).foregroundStyle(.secondary)` actually
     * renders, #7F7F7F — measured on the same two PNGs as [systemSecondary], which
     * the `LabeledContent` values use (#8A8A8E). Appended, never inserted.
     */
    public val settingsFootnoteInk: Color,
    /**
     * L78: the user bubble's fill (`NativeChatTheme.bubble` →
     * `NativeParityTokens.userBubble`), #EDF3FE in light — measured on
     * `ios-chat-assistant.png`. `selector` (#F2F3F5) is the app's control fill and
     * is grey, not blue. Appended, never inserted.
     */
    public val userBubble: Color,
    /**
     * L78: the approval card's fill (`NativeChatTheme.selector` =
     * `NativeParityTokens.selector`), #F5F6F7 in light — `ios-chat-permission.png`
     * samples it. `selector` (#F2F3F5) is the app's segmented control fill and is
     * 3/255 off in the green channel, which covers the card's whole area. Appended,
     * never inserted.
     */
    public val approvalCard: Color,
    /**
     * L79: a grouped card's row separator, #E8E8E8 in light — every hairline in
     * `ios-settings.png` is that value, while `border` (#E4E4E8) is 4/255 away and
     * turned every separator row into "different". Appended, never inserted.
     */
    public val separator: Color,
) {
    /** True when the spec could not measure `colors.background` (it never could: a dynamic system colour). */
    public val backgroundUnmeasured: Boolean get() = background == Color.Unspecified
}

/** Spacing tokens, in dp. */
@Immutable
public data class UiSpacing(
    public val iconText: Dp,
    public val badgeInsetH: Dp,
    public val badgeInsetV: Dp,
    public val codeToolbar: Dp,
    public val cardInset: Dp,
    public val copyGap: Dp,
    public val codeExpandInset: Dp,
    public val jsonStack: Dp,
    public val jsonChevronGap: Dp,
    // L80: the DSSCodeBlock chrome lengths, each pointed at a Components.swift line.
    public val codeHeaderHeight: Dp,
    public val codeLineSpacing: Dp,
    public val codeWrapButton: Dp,
    public val codeCopyMinWidth: Dp,
    public val codeExpandHeight: Dp,
    public val codeBlockOuterInsetV: Dp,
)

/** Radius tokens, in dp. */
@Immutable
public data class UiRadius(
    public val card: Dp,
    public val codeBlock: Dp,
    public val bubble: Dp,
    public val composer: Dp,
)

/** Stroke tokens, in dp. */
@Immutable
public data class UiStroke(
    public val hairline: Dp,
)

/**
 * Motion tokens: duration, the spec's curve name and its repeat mode.
 *
 * The curve is a *name* (`linear`, `easeOut`); [easing] is the platform reading
 * of that name, never a second numeric source.
 */
@Immutable
public data class UiMotion(
    public val shimmerMs: Int,
    public val shimmerCurve: String,
    public val shimmerRepeat: Boolean,
    public val copyConfirmHoldMs: Int,
    public val copyConfirmHoldCurve: String,
    public val disclosureMs: Int,
    public val disclosureCurve: String,
    public val composerSendMs: Int,
    public val composerSendCurve: String,
) {
    /** Maps a spec curve name to a Compose easing. */
    public fun easing(curve: String): androidx.compose.animation.core.Easing =
        when (curve) {
            LINEAR -> androidx.compose.animation.core.LinearEasing
            // CSS-style easeOut; the spec names the curve, not the control points.
            EASE_OUT -> androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)
            else -> androidx.compose.animation.core.LinearEasing
        }

    public val shimmerEasing: androidx.compose.animation.core.Easing get() = easing(shimmerCurve)
    public val disclosureEasing: androidx.compose.animation.core.Easing get() = easing(disclosureCurve)
    public val composerSendEasing: androidx.compose.animation.core.Easing get() = easing(composerSendCurve)

    public companion object {
        public const val LINEAR: String = "linear"
        public const val EASE_OUT: String = "easeOut"
        public const val FOREVER: String = "forever"
    }
}

/**
 * Page-layer geometry tokens. `unit` is `pt` (dp on Android) or `screenRatio`.
 *
 * The first block is the geometry the six signature surfaces declare; the
 * page-level block at the end is declared for pages and is not part of any
 * signature surface (`spec/ui/v1/README.md` §4 page-level subsection).
 */
@Immutable
public data class UiGeometry(
    public val disclosureRowHeight: Dp,
    public val disclosureIconGap: Dp,
    public val summaryDotDiameter: Dp,
    public val summaryDotGap: Dp,
    public val thinkingContentIndent: Dp,
    public val thinkingContentVertical: Dp,
    public val toolDetailIndent: Dp,
    public val toolDetailVertical: Dp,
    public val toolCardInset: Dp,
    public val terminalCardMaxHeight: Dp,
    public val bubblePadding: Dp,
    /** User-bubble horizontal text inset (PR13's 16 h). */
    public val bubblePaddingH: Dp,
    /** User-bubble vertical text inset (PR13's 10 v). */
    public val bubblePaddingV: Dp,
    /** Gap between the bubble and its queue label (`ChatPage.swift:891`, 6). */
    public val bubbleBadgeGap: Dp,
    public val bubbleLineSpacing: Dp,
    public val bubbleMaxWidth: Dp,
    public val cardPadding: Dp,
    public val cardOuterMarginH: Dp,
    public val cardOuterMarginV: Dp,
    public val primaryButtonHeight: Dp,
    public val secondaryButtonHeight: Dp,
    /** `geometry.cardAreaMaxHeightRatio` is a `screenRatio`, not a dp: keep it unit-free. */
    public val cardAreaMaxHeightRatio: Float,
    public val composerMinHeight: Dp,
    public val composerMaxHeight: Dp,
    /** Input port top inset (`ChatPage.swift:1852`), its own token — same 8 as the row bottom, different meaning. */
    public val composerPortTop: Dp,
    public val composerControlSize: Dp,
    /** Fixed tool-row height (`ChatPage.swift:1867`, `.frame(height: 32)`), not the control edge. */
    public val composerControlRowHeight: Dp,
    /** Tool-row top inset (`ChatPage.swift:1868`). */
    public val composerControlTop: Dp,
    /** Tool-row bottom inset (`ChatPage.swift:1869`, tightened to 8 by `86a39aac`). */
    public val composerControlBottom: Dp,
    public val sendButtonDiameter: Dp,
    public val inlineStopDiameter: Dp,
    public val composerTrailingReserve: Dp,
    public val composerOuterMarginH: Dp,
    public val composerOuterMarginTop: Dp,
    public val composerOuterMarginBottom: Dp,
    public val statPillMinHeight: Dp,
    public val bannerGap: Dp,
    public val bannerInsetH: Dp,
    public val bannerInsetV: Dp,
    public val toolFailureBannerInset: Dp,
    // Page-level geometry: declared for the page layer, not for a signature surface.
    // `PageGeometryTokenTest` requires each of these to have at least two page users.
    public val pageOuterMarginH: Dp,
    public val pageHeaderMinHeight: Dp,
    public val pageRowMinHeight: Dp,
    public val pageListRowMinHeight: Dp,
    public val statusDotDiameter: Dp,
    // L75: the iOS 26 toolbar capsule (完成 / 添加 / the files cube). Two page
    // users each — `FilesPage` and `WorkspaceFoldersPage` — which is what
    // `PageGeometryTokenTest` asks of a page-layer length.
    public val pageBarButtonInsetH: Dp,
    public val pageBarButtonElevation: Dp,
)

/** The complete resolved token set for one theme mode. */
@Immutable
public data class UiTokens(
    public val dark: Boolean,
    public val colors: UiColors,
    public val spacing: UiSpacing,
    public val radius: UiRadius,
    public val stroke: UiStroke,
    public val motion: UiMotion,
    public val geometry: UiGeometry,
) {
    public companion object {
        /** Resolves every token for [dark] straight from the generated spec model. */
        public fun resolve(dark: Boolean): UiTokens = UiTokens(
            dark = dark,
            colors = UiColors(
                primary = UiTokenData.colorPrimary.color(dark),
                secondary = UiTokenData.colorSecondary.color(dark),
                tertiary = UiTokenData.colorTertiary.color(dark),
                caption = UiTokenData.colorCaption.color(dark),
                background = UiTokenData.colorBackground.color(dark),
                input = UiTokenData.colorInput.color(dark),
                selector = UiTokenData.colorSelector.color(dark),
                code = UiTokenData.colorCode.color(dark),
                codeBanner = UiTokenData.colorCodeBanner.color(dark),
                inlineCode = UiTokenData.colorInlineCode.color(dark),
                border = UiTokenData.colorBorder.color(dark),
                borderLight = UiTokenData.colorBorderLight.color(dark),
                accent = UiTokenData.colorAccent.color(dark),
                success = UiTokenData.colorSuccess.color(dark),
                danger = UiTokenData.colorDanger.color(dark),
                warning = UiTokenData.colorWarning.color(dark),
                onAccent = UiTokenData.colorOnAccent.color(dark),
                ctaOverride = UiTokenData.colorCtaOverride.color(dark),
                pageAccent = UiTokenData.colorPageAccent.color(dark),
                switchOn = UiTokenData.colorSwitchOn.color(dark),
                systemLabel = UiTokenData.colorSystemLabel.color(dark),
                systemSecondary = UiTokenData.colorSystemSecondary.color(dark),
                page = UiTokenData.colorPage.color(dark),
                taskScreen = UiTokenData.colorTaskScreen.color(dark),
                groupedBackground = UiTokenData.colorGroupedBackground.color(dark),
                segmentTrack = UiTokenData.colorSegmentTrack.color(dark),
                settingsFootnoteInk = UiTokenData.colorSettingsFootnoteInk.color(dark),
                separator = UiTokenData.colorSeparator.color(dark),
                userBubble = UiTokenData.colorUserBubble.color(dark),
                approvalCard = UiTokenData.colorApprovalCard.color(dark),
            ),
            spacing = UiSpacing(
                iconText = UiTokenData.spacingIconText.dp(),
                badgeInsetH = UiTokenData.spacingBadgeInsetH.dp(),
                badgeInsetV = UiTokenData.spacingBadgeInsetV.dp(),
                codeToolbar = UiTokenData.spacingCodeToolbar.dp(),
                cardInset = UiTokenData.spacingCardInset.dp(),
                copyGap = UiTokenData.spacingCopyGap.dp(),
                codeExpandInset = UiTokenData.spacingCodeExpandInset.dp(),
                jsonStack = UiTokenData.spacingJsonStack.dp(),
                jsonChevronGap = UiTokenData.spacingJsonChevronGap.dp(),
                codeHeaderHeight = UiTokenData.spacingCodeHeaderHeight.dp(),
                codeLineSpacing = UiTokenData.spacingCodeLineSpacing.dp(),
                codeWrapButton = UiTokenData.spacingCodeWrapButton.dp(),
                codeCopyMinWidth = UiTokenData.spacingCodeCopyMinWidth.dp(),
                codeExpandHeight = UiTokenData.spacingCodeExpandHeight.dp(),
                codeBlockOuterInsetV = UiTokenData.spacingCodeBlockOuterInsetV.dp(),
            ),
            radius = UiRadius(
                card = UiTokenData.radiusCard.dp(),
                codeBlock = UiTokenData.radiusCodeBlock.dp(),
                bubble = UiTokenData.radiusBubble.dp(),
                composer = UiTokenData.radiusComposer.dp(),
            ),
            stroke = UiStroke(hairline = UiTokenData.strokeHairline.dp()),
            motion = UiMotion(
                shimmerMs = UiTokenData.motionShimmer.durationMs,
                shimmerCurve = UiTokenData.motionShimmer.curve,
                shimmerRepeat = UiTokenData.motionShimmer.repeat == UiMotion.FOREVER,
                copyConfirmHoldMs = UiTokenData.motionCopyConfirmHold.durationMs,
                copyConfirmHoldCurve = UiTokenData.motionCopyConfirmHold.curve,
                disclosureMs = UiTokenData.motionDisclosure.durationMs,
                disclosureCurve = UiTokenData.motionDisclosure.curve,
                composerSendMs = UiTokenData.motionComposerSend.durationMs,
                composerSendCurve = UiTokenData.motionComposerSend.curve,
            ),
            geometry = UiGeometry(
                disclosureRowHeight = UiTokenData.geometryDisclosureRowHeight.dp(),
                disclosureIconGap = UiTokenData.geometryDisclosureIconGap.dp(),
                summaryDotDiameter = UiTokenData.geometrySummaryDotDiameter.dp(),
                summaryDotGap = UiTokenData.geometrySummaryDotGap.dp(),
                thinkingContentIndent = UiTokenData.geometryThinkingContentIndent.dp(),
                thinkingContentVertical = UiTokenData.geometryThinkingContentVertical.dp(),
                toolDetailIndent = UiTokenData.geometryToolDetailIndent.dp(),
                toolDetailVertical = UiTokenData.geometryToolDetailVertical.dp(),
                toolCardInset = UiTokenData.geometryToolCardInset.dp(),
                terminalCardMaxHeight = UiTokenData.geometryTerminalCardMaxHeight.dp(),
                bubblePadding = UiTokenData.geometryBubblePadding.dp(),
                bubblePaddingH = UiTokenData.geometryBubblePaddingH.dp(),
                bubblePaddingV = UiTokenData.geometryBubblePaddingV.dp(),
                bubbleBadgeGap = UiTokenData.geometryBubbleBadgeGap.dp(),
                bubbleLineSpacing = UiTokenData.geometryBubbleLineSpacing.dp(),
                bubbleMaxWidth = UiTokenData.geometryBubbleMaxWidth.dp(),
                cardPadding = UiTokenData.geometryCardPadding.dp(),
                cardOuterMarginH = UiTokenData.geometryCardOuterMarginH.dp(),
                cardOuterMarginV = UiTokenData.geometryCardOuterMarginV.dp(),
                primaryButtonHeight = UiTokenData.geometryPrimaryButtonHeight.dp(),
                secondaryButtonHeight = UiTokenData.geometrySecondaryButtonHeight.dp(),
                cardAreaMaxHeightRatio = UiTokenData.geometryCardAreaMaxHeightRatio.ratio(),
                composerMinHeight = UiTokenData.geometryComposerMinHeight.dp(),
                composerMaxHeight = UiTokenData.geometryComposerMaxHeight.dp(),
                composerPortTop = UiTokenData.geometryComposerPortTop.dp(),
                composerControlSize = UiTokenData.geometryComposerControlSize.dp(),
                composerControlRowHeight = UiTokenData.geometryComposerControlRowHeight.dp(),
                composerControlTop = UiTokenData.geometryComposerControlTop.dp(),
                composerControlBottom = UiTokenData.geometryComposerControlBottom.dp(),
                sendButtonDiameter = UiTokenData.geometrySendButtonDiameter.dp(),
                inlineStopDiameter = UiTokenData.geometryInlineStopDiameter.dp(),
                composerTrailingReserve = UiTokenData.geometryComposerTrailingReserve.dp(),
                composerOuterMarginH = UiTokenData.geometryComposerOuterMarginH.dp(),
                composerOuterMarginTop = UiTokenData.geometryComposerOuterMarginTop.dp(),
                composerOuterMarginBottom = UiTokenData.geometryComposerOuterMarginBottom.dp(),
                statPillMinHeight = UiTokenData.geometryStatPillMinHeight.dp(),
                bannerGap = UiTokenData.geometryBannerGap.dp(),
                bannerInsetH = UiTokenData.geometryBannerInsetH.dp(),
                bannerInsetV = UiTokenData.geometryBannerInsetV.dp(),
                toolFailureBannerInset = UiTokenData.geometryToolFailureBannerInset.dp(),
                pageOuterMarginH = UiTokenData.geometryPageOuterMarginH.dp(),
                pageHeaderMinHeight = UiTokenData.geometryPageHeaderMinHeight.dp(),
                pageRowMinHeight = UiTokenData.geometryPageRowMinHeight.dp(),
                pageListRowMinHeight = UiTokenData.geometryPageListRowMinHeight.dp(),
                statusDotDiameter = UiTokenData.geometryStatusDotDiameter.dp(),
                pageBarButtonInsetH = UiTokenData.geometryPageBarButtonInsetH.dp(),
                pageBarButtonElevation = UiTokenData.geometryPageBarButtonElevation.dp(),
            ),
        )
    }
}

/** Resolves one colour token; an unmeasured channel is never replaced by a guess. */
internal fun TokenColor.color(dark: Boolean): Color =
    (if (dark) this.dark else this.light)?.let { Color(it) } ?: Color.Unspecified

/** A `pt` token is a dp on Android (the unit only changes at render time, through the font scale). */
internal fun TokenDp.dp(): Dp = value.dp

/** Geometry with `unit: pt` renders as dp; a ratio token has no dp form. */
internal fun TokenGeometry.dp(): Dp {
    require(unit == PT) { "${name}: unit '$unit' is not a length; read it through ratio() instead" }
    return value.dp
}

/** Reads a `screenRatio` geometry token; a length token is a caller bug. */
internal fun TokenGeometry.ratio(): Float {
    require(unit == RATIO) { "${name}: unit '$unit' is not a screen ratio" }
    return value
}

/** The one length unit the page layer uses. */
public const val PT: String = "pt"

/** The one unit-free geometry unit the page layer uses. */
public const val RATIO: String = "screenRatio"
