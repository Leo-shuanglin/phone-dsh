// Signature surface 1/6 - the message bubble.
// SPDX-License-Identifier: MIT
//
// Spec: `spec/ui/v1/README.md` §4 "气泡" + `tokens.json`
// `signatureSurfaces.bubble`. Values come from the theme only; the token list
// below is the exact set this surface is allowed to read and is asserted
// against `tokens.json` by `TokenReferenceTest`.
//
// The user bubble's text insets are 16 h / 10 v (`ChatPage.swift:3738-3739`,
// PR13) and are read from `geometry.bubblePaddingH/V` — the pair this surface
// declares in `signatureSurfaces.bubble.tokens`. The queue label's 6 pt gap is
// its own token, `geometry.bubbleBadgeGap` (`ChatPage.swift:891`). Surface
// ownership is what keeps both off `PageGeometryTokenTest`'s "used by two pages"
// rule (no page file reads the bubble). The old single scalar
// `geometry.bubblePadding: 12` no longer draws anything in this surface: it stays
// declared only as the surface-owned home of the 12 pt inset
// `NativeDeviceSessionPage` reads, so that page does not become an orphan.
//
// The surface is two pieces: `ChatBubble` draws the bubble, `BubbleBadge` draws
// the queue label a user row puts under it (spec §3 "行下标签"). The row owns
// the placement, so the bubble itself never knows that a label exists.
package com.phonedsh.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.phonedsh.ui.UiTags
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import com.phonedsh.ui.theme.UiType
import com.phonedsh.ui.theme.generated.UiTokenNames

/** Who spoke. The assistant answer carries no bubble chrome (spec §4 note). */
public enum class BubbleRole {
    /** Own message: `colors.selector` fill + `radius.bubble`. */
    USER,

    /** Assistant answer: text on the platform background, no fill, no radius. */
    ASSISTANT,
}

/**
 * The token inventory of this surface; a subset of
 * `signatureSurfaces.bubble.tokens` in `spec/ui/v1/tokens.json`.
 */
public object BubbleTokens {
    public val refs: List<String> = listOf(
        UiTokenNames.COLORS_SELECTOR,
        UiTokenNames.COLORS_PRIMARY,
        UiTokenNames.COLORS_SECONDARY,
        UiTokenNames.TYPOGRAPHY_BODY,
        UiTokenNames.TYPOGRAPHY_SECONDARY,
        UiTokenNames.RADIUS_BUBBLE,
        // Kept in the inventory (surface-owned) even though this surface no longer
        // draws it: `NativeDeviceSessionPage` is the only reader left (12 pt inset),
        // and a page-level `bubblePadding` would be a one-page orphan.
        UiTokenNames.GEOMETRY_BUBBLE_PADDING,
        UiTokenNames.GEOMETRY_BUBBLE_PADDING_H,
        UiTokenNames.GEOMETRY_BUBBLE_PADDING_V,
        UiTokenNames.GEOMETRY_BUBBLE_BADGE_GAP,
        UiTokenNames.GEOMETRY_BUBBLE_LINE_SPACING,
        UiTokenNames.GEOMETRY_BUBBLE_MAX_WIDTH,
    )
}

/**
 * The bubble body style: `typography.body` plus `geometry.bubbleLineSpacing`.
 *
 * The extra spacing is a fixed page-layer length, so it is converted to `sp`
 * *through* the current font scale: the text grows with the system scale while
 * the gap between lines keeps its dp size (the token is `scalesWith: null`).
 */
@Composable
public fun bubbleBodyStyle(): TextStyle {
    val type: UiType = LocalUiType.current
    val tokens = LocalUiTokens.current
    val fontSize = type.body.fontSize
    if (!fontSize.isSp) return type.body
    val fontScale = LocalDensity.current.fontScale
    val extraSp = tokens.geometry.bubbleLineSpacing.value / fontScale
    return type.body.copy(lineHeight = fontSize.value.plus(extraSp).sp)
}

/**
 * The queue-label style: `typography.secondary` + `colors.secondary` (spec §3
 * "行下标签"; iOS `queueLine` is system 13 pt + `inkSecondary`). The values come
 * from the theme, never from this file.
 */
@Composable
internal fun bubbleBadgeStyle(): TextStyle {
    val type: UiType = LocalUiType.current
    val tokens = LocalUiTokens.current
    return type.secondary.copy(color = tokens.colors.secondary)
}

/**
 * The queue label of a user row: the row's badge text in `typography.secondary`
 * + `colors.secondary`, 6 pt under the bubble (spec §3 "行下标签").
 *
 * It is a separate piece on purpose: the chat row places the bubble at its
 * trailing edge and this label at its leading edge, matching the iOS
 * `queueLine` under a trailing `userBubble`. [ChatBubble] stays a pure bubble
 * and never learns that a label exists.
 *
 * @param text the row's `ChatRow.User.badge`; the row decides when it exists.
 *   It is display text, passed through verbatim.
 * @param modifier layout modifier from the caller.
 */
@Composable
public fun BubbleBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    BasicText(
        text = text,
        // iOS `queueLine` sits 6 pt under the bubble (`ChatPage.swift:891`), read
        // from `geometry.bubbleBadgeGap` — its own token, no longer `bubblePadding/2`.
        modifier = modifier.padding(top = tokens.geometry.bubbleBadgeGap),
        style = bubbleBadgeStyle(),
    )
}

/**
 * One message row.
 *
 * @param role user or assistant.
 * @param text the message body, already projected by the UiState projector
 *   (bounded to 20000 chars there; this component never truncates silently).
 * @param modifier layout modifier from the caller.
 */
@Composable
public fun ChatBubble(
    role: BubbleRole,
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val common = modifier
        .testTag(UiTags.BUBBLE)
        // One utterance per message: the role and the body are read together.
        .semantics(mergeDescendants = true) {}

    when (role) {
        BubbleRole.USER -> BasicText(
            text = text,
            // `bubbleMaxWidth` is the *bubble's* cap; the assistant answer is a text
            // column and takes the page's full width (ios-chat-default.png: it starts
            // at x 16.7 and runs to the trailing page margin).
            modifier = common
                .widthIn(max = tokens.geometry.bubbleMaxWidth)
                .background(
                    color = tokens.colors.selector,
                    shape = RoundedCornerShape(tokens.radius.bubble),
                )
                // iOS PR13's pair: 16 horizontal / 10 vertical
                // (`ChatPage.swift:3738-3739`). Both are read from this surface's
                // own tokens (`bubblePaddingH/V`), so no `ChatMetrics` bridge and no
                // documented exception to "theme only" remains.
                .padding(
                    horizontal = tokens.geometry.bubblePaddingH,
                    vertical = tokens.geometry.bubblePaddingV,
                ),
            style = bubbleBodyStyle().copy(color = tokens.colors.primary),
        )

        BubbleRole.ASSISTANT -> BasicText(
            text = text,
            // The assistant answer has no bubble chrome and, per the surface note
            // ("助手回答无气泡底"), no bubble padding and no bubble line spacing: the
            // measured baseline starts its first glyph at x 16.7 (the page margin the
            // row already applies) and its line pitch is 16.7 pt, i.e.
            // `typography.body`'s own line height.
            modifier = common,
            style = type.body.copy(color = tokens.colors.primary),
        )
    }
}
