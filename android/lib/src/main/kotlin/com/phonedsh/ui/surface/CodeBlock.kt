// The markdown code card of an answer: language strip, wrap/copy actions, monospaced body.
// SPDX-License-Identifier: MIT
//
// Mirror: `ios-code-block.png` (the component swatch) and the code card inside
// `ios-chat-assistant.png`. The iOS page renders an answer as markdown; the one
// block kind both baseline screenshots show is the triple-backtick fence, so this
// is the only structure `ChatRows.splitFences` recognises and the only one drawn
// here.
//
// Measured baseline (`Components.swift:151-225`, `ios-code-block.png`):
//
//   chrome        one card, radius 12, 0.5 pt border, 2 pt vertical outer inset
//   header strip  34 pt tall, fill `#F5F6F7`, language + wrap toggle + copy
//   copy          min 44 x 28, 13 pt glyph, 11 pt text, `已复制` for 1 s
//   body          fill `#F9FAFB`, mono 14 pt, 5 pt line spacing (20.7 pt pitch)
//   long block    > 24 lines or > 2400 chars: max 300 pt, `展开全部 N 行` tail
//
// Every value above comes from `spec/ui/v1/tokens.json`; the two colour token
// values were corrected to `NativeParityTokens.swift:35-36` in L80 (the old
// `#F6F6F8` / `#E9E9ED` matched neither the source nor any iOS baseline PNG).
package com.phonedsh.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import kotlinx.coroutines.delay

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
    // The header sits flush on the card: only its top corners are rounded.
    val headerShape = RoundedCornerShape(
        topStart = tokens.radius.codeBlock,
        topEnd = tokens.radius.codeBlock,
    )
    var wrapped by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val lineCount = remember(body) { body.split("\n").size }
    val isLong = lineCount > LONG_LINE_COUNT || body.length > LONG_CHAR_COUNT

    // `Components.swift:180-183`: 已复制 holds for one second, then reverts.
    LaunchedEffect(copied) {
        if (copied) {
            delay(tokens.motion.copyConfirmHoldMs.toLong())
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // `Components.swift:224`: the card is inset 2 pt above/below its row.
            .padding(vertical = tokens.spacing.codeBlockOuterInsetV)
            .testTag(CODE_BLOCK_TAG)
            .background(tokens.colors.code, shape)
            .border(width = tokens.stroke.hairline, color = tokens.colors.border, shape = shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.spacing.codeHeaderHeight)
                .background(tokens.colors.codeBanner, headerShape)
                .padding(horizontal = tokens.geometry.toolCardInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.copyGap),
        ) {
            BasicText(
                text = language,
                style = type.codeLabel.copy(color = tokens.colors.secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(modifier = Modifier.weight(1f))
            // `Components.swift:171-176`: text.alignleft / arrow.left.and.right,
            // 28 x 28, plain style.
            Box(
                modifier = Modifier
                    .size(tokens.spacing.codeWrapButton)
                    .clickable(role = Role.Button) { wrapped = !wrapped }
                    .semantics {
                        contentDescription = if (wrapped) {
                            UiStrings.CODE_WRAP_OFF
                        } else {
                            UiStrings.CODE_WRAP_ON
                        }
                    }
                    .testTag(CODE_WRAP_TAG),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = if (wrapped) WRAP_GLYPH else UNWRAP_GLYPH,
                    style = type.codeAction.copy(color = tokens.colors.secondary),
                )
            }
            // `Components.swift:177-192`: icon 13 + 11 pt label, min 44 x 28.
            Row(
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = tokens.spacing.codeCopyMinWidth,
                        minHeight = tokens.spacing.codeWrapButton,
                    )
                    .clickable(role = Role.Button) {
                        clipboard.setText(AnnotatedString(body))
                        copied = true
                    }
                    .semantics {
                        contentDescription = if (copied) {
                            UiStrings.MESSAGE_COPIED
                        } else {
                            UiStrings.CODE_COPY_ACTION
                        }
                    }
                    .testTag(CODE_COPY_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    tokens.spacing.copyGap,
                    Alignment.CenterHorizontally,
                ),
            ) {
                BasicText(
                    text = if (copied) CHECK_GLYPH else COPY_GLYPH,
                    style = type.codeAction.copy(color = tokens.colors.secondary),
                )
                BasicText(
                    text = if (copied) UiStrings.MESSAGE_COPIED else UiStrings.MESSAGE_COPY,
                    style = type.codeAction.copy(color = tokens.colors.secondary),
                )
            }
        }
        // iOS paints the block with `NativeCodeHighlighter` and a
        // `.secondary` base ink (NativeCodeHighlighter.swift:19-27); the four
        // token classes are the theme's success / caption / warning / accent.
        // A block with no recognised token therefore changes ink from primary
        // to secondary, which is what the baseline shows.
        val highlighted = remember(body, tokens.dark) {
            highlightCode(
                body,
                CodeInks(
                    string = tokens.colors.success,
                    comment = tokens.colors.caption,
                    number = tokens.colors.warning,
                    keyword = tokens.colors.accent,
                ),
            )
        }
        // `NativeChatPreferences`: code text defaults to 14 pt; `Components.swift:201`
        // adds 5 pt line spacing, and ios-code-block.png measures a 20.3-21.3 pt line
        // pitch. Compose's line height is the whole line box (not an extra spacing),
        // so it is the 15 pt subheadline box + the 5 pt spacing = 20; using the code
        // font size itself would produce a 19 pt pitch and drift 6 pt by line 5.
        val codeSize = type.controlBody.fontSize
        val codeStyle = type.controlBody.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = codeSize,
            lineHeight = (type.subheadline.fontSize.value + tokens.spacing.codeLineSpacing.value).sp,
            color = tokens.colors.secondary,
        )
        val verticalScroll = rememberScrollState()
        val horizontalScroll = rememberScrollState()
        val collapsible = isLong && !expanded
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // `Components.swift:207`: a long block is capped at 300 pt until
                // expanded. No 300 pt geometry token exists, and a one-surface
                // geometry token would be an orphan under `PageGeometryTokenTest`;
                // the declared terminal max (224) is the nearest owned length.
                // `unverified geometry` for that borrow.
                .then(
                    if (collapsible) {
                        Modifier.heightIn(max = tokens.geometry.terminalCardMaxHeight)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (collapsible) Modifier.verticalScroll(verticalScroll) else Modifier,
                )
                .then(
                    if (!wrapped) Modifier.horizontalScroll(horizontalScroll) else Modifier,
                ),
        ) {
            BasicText(
                text = highlighted,
                style = codeStyle,
                softWrap = wrapped,
                modifier = Modifier.padding(tokens.geometry.toolCardInset),
            )
        }
        if (isLong) {
            // `Components.swift:209-218`: full-width 32 pt tail, 12 pt label.
            val expandLabel = if (expanded) {
                UiStrings.CODE_COLLAPSE
            } else {
                UiStrings.CODE_EXPAND_ALL.format(lineCount)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tokens.spacing.codeExpandHeight)
                    .background(tokens.colors.code)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { contentDescription = expandLabel }
                    .testTag(CODE_EXPAND_TAG),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = expandLabel,
                    style = type.codeExpand.copy(color = tokens.colors.secondary),
                )
            }
        }
    }
}

/** The card's stable tag (a component swatch capture addresses it). */
public const val CODE_BLOCK_TAG: String = "pdsh.surface.code-block"

/** The 复制 key's stable tag. */
public const val CODE_COPY_TAG: String = "pdsh.surface.code-block.copy"

/** The wrap toggle's stable tag. */
public const val CODE_WRAP_TAG: String = "pdsh.surface.code-block.wrap"

/** The expand/collapse tail's stable tag. */
public const val CODE_EXPAND_TAG: String = "pdsh.surface.code-block.expand"

/** `Components.swift:156-158`: long is > 24 lines or > 2400 characters. */
private const val LONG_LINE_COUNT: Int = 24
private const val LONG_CHAR_COUNT: Int = 2400

/** The copy mark; the iOS icon set is an asset catalog, so Android draws a glyph. */
private const val COPY_GLYPH: String = "⧉"

/** The copied-state check mark (iOS `check`). */
private const val CHECK_GLYPH: String = "✓"

/** The wrapped-state glyph (iOS `text.alignleft`). */
private const val WRAP_GLYPH: String = "≡"

/** The unwrapped-state glyph (iOS `arrow.left.and.right`). */
private const val UNWRAP_GLYPH: String = "↔"
