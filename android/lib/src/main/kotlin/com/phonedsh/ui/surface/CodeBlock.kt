// The markdown code card of an answer: language strip, 复制 action, monospaced body.
// SPDX-License-Identifier: MIT
//
// Mirror: `ios-code-block.png` (the component swatch) and the code card inside
// `ios-chat-assistant.png`. The iOS page renders an answer as markdown; the one
// block kind both baseline screenshots show is the triple-backtick fence, so this
// is the only structure `ChatRows.splitFences` recognises and the only one drawn
// here.
//
// Measured baseline (see `docs/receipts/ios-baseline-screens/VISUAL.md` §1.4):
//
//   chrome        one card, 16 pt radius, hairline border
//   header strip  26 pt tall, fill `#E9E9ED`, language on the left, 复制 on the right
//   body          fill `#F6F6F8`, mono 15 pt, 20.7 pt line pitch
//
// Two of those lengths are not in `spec/ui/v1/tokens.json`, and this component may
// not invent a second value source: the card radius is `radius.codeBlock` (12 pt,
// not 16) and the mono is `typography.code` (12 pt, not 15). Both deviations are
// listed in the chat lane's receipt instead of being typed here.
package com.phonedsh.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType

/**
 * One fenced code block of an assistant answer.
 *
 * @param language the fence's info string (`swift`); empty hides the label.
 * @param body the block's text, drawn verbatim (never re-wrapped by this card).
 * @param modifier the row's placement.
 */
@Composable
public fun CodeBlock(
    language: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(tokens.radius.codeBlock)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CODE_BLOCK_TAG)
            .background(tokens.colors.code, shape)
            .border(width = tokens.stroke.hairline, color = tokens.colors.border, shape = shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.colors.codeBanner, shape)
                .padding(
                    horizontal = tokens.geometry.toolCardInset,
                    vertical = tokens.spacing.badgeInsetV,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
        ) {
            BasicText(
                text = language,
                style = type.caption.copy(color = tokens.colors.secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(modifier = Modifier.weight(1f))
            // The whole cluster copies the block: the glyph is decorative, the word
            // carries the semantics and the click.
            BasicText(
                text = COPY_GLYPH,
                style = type.caption.copy(color = tokens.colors.secondary),
            )
            BasicText(
                text = UiStrings.MESSAGE_COPY,
                style = type.caption.copy(color = tokens.colors.secondary),
                modifier = Modifier
                    .clickable(role = Role.Button) {
                        clipboard.setText(AnnotatedString(body))
                    }
                    .semantics { contentDescription = UiStrings.MESSAGE_COPY }
                    .testTag(CODE_COPY_TAG)
                    .padding(tokens.spacing.iconText),
            )
        }
        BasicText(
            text = body,
            style = type.code.copy(color = tokens.colors.primary),
            modifier = Modifier.padding(tokens.geometry.toolCardInset),
        )
    }
}

/** The card's stable tag (a component swatch capture addresses it). */
public const val CODE_BLOCK_TAG: String = "pdsh.surface.code-block"

/** The 复制 key's stable tag. */
public const val CODE_COPY_TAG: String = "pdsh.surface.code-block.copy"

/** The copy mark; the iOS icon set is an asset catalog, so Android draws a glyph. */
private const val COPY_GLYPH: String = "⧉"
