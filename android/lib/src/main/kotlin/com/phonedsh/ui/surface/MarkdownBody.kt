// The answer body's renderer: paragraphs, headings, lists, quotes, fenced code,
// GFM tables and DSH's TeX extensions.
// SPDX-License-Identifier: MIT
//
// Mirror: `examples/probe-app/PhoneDSHProbe/NativeMarkdownBody.swift`.
//
// What iOS actually draws, and what this file therefore draws:
//
//   * blocks are a `VStack(spacing: 20)` (`NativeMarkdownBody.swift:27` +
//     `NativeChatTheme.messageParagraphSpacing`, `NativeChatTheme.swift:55`), so the
//     gap between two blocks is 20 pt, not the 5 pt a prose/code split used to leave;
//   * the body line pitch is 24 pt: `.lineSpacing(messageLineSpacing)` at `:33`
//     plus the 17 pt body font is `NativeParityTokens.bodyLineHeight = 24`;
//   * a table is a real table (`NativeMarkdownTable`, `:132-199`): header row in the
//     selector fill, one hairline under every cell, GFM column alignment;
//   * math is typeset, not echoed (`:108-111` display, `:285-290` inline). The
//     renderer here is a bounded TeX subset — variables italic, `^`/`_` scripts,
//     common symbols — because the sample and the baseline only need that; an
//     unknown command stays visible verbatim instead of being dropped;
//   * raw HTML is literal text (`:293` `Text(value)`), never parsed and never a
//     WebView: `<b>` on screen is exactly the iOS output;
//   * a footnote reference is a small superscript (`:291-292`, 9 pt raised 6 pt).
package com.phonedsh.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import com.phonedsh.ui.theme.UiTokens

/** Test tag of the transcript's markdown body (one node per answer). */
internal const val MARKDOWN_BODY_TAG: String = "pdsh.surface.markdown.body"

/** Test tag of a GFM table inside an answer. */
internal const val MARKDOWN_TABLE_TAG: String = "pdsh.surface.markdown.table"

/** Test tag of a display-math block. */
internal const val MARKDOWN_MATH_TAG: String = "pdsh.surface.markdown.math"

/**
 * Renders one answer body.
 *
 * @param markdown the projected answer text, parsed once per value.
 * @param modifier the row's placement.
 */
@Composable
public fun MarkdownBody(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    MarkdownBlocks(blocks = blocks, modifier = modifier)
}

/**
 * Renders already-parsed [blocks]; the caller may reuse the parse across a
 * recomposition (the row remembers it).
 */
@Composable
internal fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    Column(
        modifier = modifier.fillMaxWidth().testTag(MARKDOWN_BODY_TAG),
        // iOS `NativeChatTheme.messageParagraphSpacing` = 20 (`NativeMarkdownBody.swift:27`).
        // unverified geometry: the 20 pt stat-pill floor is the only 20 pt token;
        // tokens.json has no markdown block-spacing token.
        verticalArrangement = Arrangement.spacedBy(tokens.geometry.statPillMinHeight),
    ) {
        blocks.forEach { block -> MarkdownBlockView(block) }
    }
}

/** One block, dispatched exactly as `NativeMarkdownBlockView` dispatches (`:51-118`). */
@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    when (block) {
        is MarkdownBlock.Paragraph -> MarkdownInlineText(
            inlines = block.inlines,
            style = markdownBodyStyle(),
        )

        is MarkdownBlock.Heading -> MarkdownInlineText(
            inlines = block.inlines,
            style = headingStyle(block.level),
        )

        is MarkdownBlock.Code -> CodeBlock(language = block.language, body = block.body)

        is MarkdownBlock.Math -> DisplayMath(latex = block.latex)

        is MarkdownBlock.Table -> MarkdownTable(
            header = block.header,
            rows = block.rows,
            alignments = block.alignments,
        )

        is MarkdownBlock.ListBlock -> Column(
            modifier = Modifier.fillMaxWidth().padding(start = tokens.spacing.codeToolbar),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetV),
        ) {
            block.items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
                ) {
                    val marker = when {
                        item.checked == true -> MARKER_CHECKED
                        item.checked == false -> MARKER_UNCHECKED
                        block.ordered -> "${block.start + index}."
                        else -> MARKER_BULLET
                    }
                    BasicText(
                        text = marker,
                        style = type.body.copy(color = tokens.colors.secondary),
                    )
                    MarkdownInlineText(inlines = item.inlines, style = markdownBodyStyle())
                }
            }
        }

        // iOS draws a 2 pt rule at the leading edge and insets the children by 14
        // (`NativeMarkdownBody.swift:96-99`). unverified geometry: the 2 pt summary
        // dot is the only 2 pt token; the 14 pt inset has no token either.
        is MarkdownBlock.Quote -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val width = tokens.geometry.summaryDotDiameter.toPx()
                    drawRect(
                        color = tokens.colors.caption,
                        size = Size(width, size.height),
                        topLeft = Offset.Zero,
                    )
                }
                .padding(start = tokens.spacing.cardInset),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.codeToolbar),
        ) {
            block.blocks.forEach { child -> MarkdownBlockView(child) }
        }

        MarkdownBlock.Rule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = tokens.spacing.codeToolbar)
                .height(tokens.stroke.hairline)
                .background(tokens.colors.border),
        )
    }
}

/**
 * The body style: 17 pt with the 24 pt line box the iOS page sets through
 * `.lineSpacing` (`NativeMarkdownBody.swift:33`). [bubbleBodyStyle] is the same
 * `typography.body` + `geometry.bubbleLineSpacing` pair; reusing it keeps one
 * definition of the 24 pt pitch instead of a second one here.
 */
@Composable
private fun markdownBodyStyle(): TextStyle {
    val tokens = LocalUiTokens.current
    return bubbleBodyStyle().copy(color = tokens.colors.primary)
}

/**
 * Heading roles. iOS uses 24/22/20/17/body-bold (`NativeMarkdownBody.swift:120-129`);
 * tokens.json has no 24 or 22 role, so the two largest headings share the 20 pt
 * `emptyStateTitle` role rather than typing a size here.
 */
@Composable
private fun headingStyle(level: Int): TextStyle {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    return when (level) {
        1, 2, 3 -> type.emptyStateTitle.copy(color = tokens.colors.primary)
        else -> type.headline.copy(color = tokens.colors.primary)
    }
}

/** One table cell's text, with the GFM column alignment (`:227-241`). */
@Composable
private fun cellStyle(align: MarkdownTableAlign?, header: Boolean): TextStyle {
    return markdownBodyStyle().copy(
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = when (align) {
            MarkdownTableAlign.CENTER -> TextAlign.Center
            MarkdownTableAlign.RIGHT -> TextAlign.End
            else -> TextAlign.Start
        },
    )
}

/**
 * A GFM table (`NativeMarkdownTable`): header row on the selector fill, one
 * hairline under every cell, every column the same weight.
 *
 * iOS picks between a grid and a per-row card stack with `ViewThatFits` (`:145-149`).
 * A Compose `Row` cannot ask "did it fit"; the phone-width baseline always takes
 * the grid branch, so only the grid is drawn.
 */
@Composable
internal fun MarkdownTable(
    header: List<List<MarkdownInline>>,
    rows: List<List<List<MarkdownInline>>>,
    alignments: List<MarkdownTableAlign?>,
) {
    val tokens = LocalUiTokens.current
    val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    @Composable
    fun row(cells: List<List<MarkdownInline>>, isHeader: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth().background(
                // iOS header fill is `NativeChatTheme.selector` = #F5F6F7
                // (`NativeMarkdownBody.swift:224`); `colors.approvalCard` is the
                // Android token that already carries that value.
                if (isHeader) tokens.colors.approvalCard else Color.Transparent,
            ),
        ) {
            for (index in 0 until columns) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // iOS cell inset: 16 h / 10 v (`NativeMarkdownBody.swift:219-220`).
                        .padding(
                            horizontal = tokens.geometry.bubblePaddingH,
                            vertical = tokens.geometry.bubblePaddingV,
                        )
                        .drawBehind {
                            val stroke = tokens.stroke.hairline.toPx()
                            drawLine(
                                color = tokens.colors.border,
                                start = Offset(0f, size.height - stroke / 2),
                                end = Offset(size.width, size.height - stroke / 2),
                                strokeWidth = stroke,
                            )
                        },
                ) {
                    MarkdownInlineText(
                        inlines = cells.getOrNull(index).orEmpty(),
                        style = cellStyle(alignments.getOrNull(index), isHeader),
                    )
                }
            }
        }
    }
    Column(modifier = Modifier.fillMaxWidth().testTag(MARKDOWN_TABLE_TAG)) {
        row(header, isHeader = true)
        rows.forEach { row(it, isHeader = false) }
    }
}

/**
 * Display math: the iOS block is a horizontal `ScrollView` around the typeset
 * label (`NativeMarkdownBody.swift:109-111`), so a long formula scrolls instead of
 * wrapping.
 */
@Composable
private fun DisplayMath(latex: String) {
    val scroll = rememberScrollState()
    val style = markdownBodyStyle()
    val text = remember(latex, style) { mathAnnotatedString(latex, style.fontSize) }
    BasicText(
        text = text,
        style = style,
        modifier = Modifier
            .horizontalScroll(scroll)
            .testTag(MARKDOWN_MATH_TAG),
    )
}

/** One text run with inline spans; shared by paragraphs, headings, lists and cells. */
@Composable
internal fun MarkdownInlineText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val text = remember(inlines, style, tokens) { annotatedInlines(inlines, style, tokens) }
    BasicText(text = text, style = style, modifier = modifier)
}

/** Builds the annotated string for a run of inline parts. */
private fun annotatedInlines(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    tokens: UiTokens,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    // The spans must start from the block's own size: the math script and the
    // footnote reference are sized relative to it (an unspecified size would leave
    // both at 100 %).
    val base = SpanStyle(
        color = style.color,
        fontSize = style.fontSize,
        fontWeight = style.fontWeight,
        fontStyle = style.fontStyle,
    )
    builder.appendInlines(inlines, base, tokens)
    return builder.toAnnotatedString()
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    span: SpanStyle,
    tokens: UiTokens,
) {
    inlines.forEach { part ->
        when (part) {
            is MarkdownInline.Text -> withStyle(span) { append(part.text) }
            is MarkdownInline.Strong -> appendInlines(
                part.children,
                span.merge(SpanStyle(fontWeight = FontWeight.SemiBold)),
                tokens,
            )
            is MarkdownInline.Emphasis -> appendInlines(
                part.children,
                span.merge(SpanStyle(fontStyle = FontStyle.Italic)),
                tokens,
            )
            is MarkdownInline.Delete -> appendInlines(
                part.children,
                span.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)),
                tokens,
            )
            is MarkdownInline.Code -> withStyle(
                span.merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = tokens.colors.inlineCode,
                    ),
                ),
            ) { append(part.code) }
            is MarkdownInline.Link -> appendInlines(
                part.children,
                span.merge(
                    SpanStyle(
                        color = tokens.colors.accent,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
                tokens,
            )
            is MarkdownInline.Footnote -> withStyle(
                span.merge(
                    SpanStyle(
                        fontSize = scaledSize(span.fontSize, FOOTNOTE_SCALE),
                        baselineShift = BaselineShift(FOOTNOTE_RAISE),
                    ),
                ),
            ) { append("[${part.id}]") }
            is MarkdownInline.Math -> appendMath(part.latex, span)
        }
    }
}

/** Typesets one TeX run into the annotated string. */
private fun AnnotatedString.Builder.appendMath(latex: String, span: SpanStyle) {
    mathRuns(latex).forEach { run ->
        withStyle(
            span.merge(
                SpanStyle(
                    fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
                    fontSize = if (run.script == 0) {
                        span.fontSize
                    } else {
                        scaledSize(span.fontSize, SCRIPT_SCALE)
                    },
                    baselineShift = when (run.script) {
                        1 -> BaselineShift(SCRIPT_RAISE)
                        -1 -> BaselineShift(-SCRIPT_RAISE)
                        else -> null
                    },
                ),
            ),
        ) { append(run.text) }
    }
}

/** Display math as a standalone annotated string (the block renderer's entry). */
private fun mathAnnotatedString(latex: String, size: TextUnit): AnnotatedString {
    val builder = AnnotatedString.Builder()
    mathRuns(latex).forEach { run ->
        builder.withStyle(
            SpanStyle(
                fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
                fontSize = if (run.script == 0) size else scaledSize(size, SCRIPT_SCALE),
                baselineShift = when (run.script) {
                    1 -> BaselineShift(SCRIPT_RAISE)
                    -1 -> BaselineShift(-SCRIPT_RAISE)
                    else -> null
                },
            ),
        ) { append(run.text) }
    }
    return builder.toAnnotatedString()
}

/** `size * factor` for a sp size; a unit that is not sp keeps its size. */
private fun scaledSize(size: TextUnit, factor: Float): TextUnit =
    if (size.isSp) size * factor else size

/**
 * Splits a TeX run into visible runs.
 *
 * The subset is bounded on purpose: letters are variables (italic), digits and
 * operators upright, `^`/`_` take the next group or character as a script, and a
 * small command table covers the symbols an answer actually uses. An unknown
 * command is emitted verbatim (`\foo` stays `\foo`) so no content disappears.
 *
 * @param latex the TeX source between the delimiters.
 */
internal fun mathRuns(latex: String): List<MathRun> {
    val runs = mutableListOf<MathRun>()
    fun push(text: String, italic: Boolean, script: Int) {
        if (text.isEmpty()) return
        val last = runs.lastOrNull()
        if (last != null && last.italic == italic && last.script == script) {
            runs[runs.lastIndex] = last.copy(text = last.text + text)
        } else {
            runs.add(MathRun(text, italic, script))
        }
    }

    var script = 0
    var singleScript = false
    var grouped = false
    var i = 0
    while (i < latex.length) {
        val char = latex[i]
        when {
            char == '\\' && i + 1 < latex.length -> {
                val next = latex[i + 1]
                if (next.isLetter()) {
                    var end = i + 1
                    while (end < latex.length && latex[end].isLetter()) end++
                    val command = latex.substring(i + 1, end)
                    push(MATH_SYMBOLS[command] ?: latex.substring(i, end), false, script)
                    i = end
                } else {
                    // `\,` `\;` `\!` `\%` `\{` and friends: the escaped character.
                    push(if (next == ',') " " else next.toString(), false, script)
                    i += 2
                }
                if (singleScript) {
                    script = 0
                    singleScript = false
                }
            }

            char == '^' || char == '_' -> {
                script = if (char == '^') 1 else -1
                val hasGroup = i + 1 < latex.length && latex[i + 1] == '{'
                singleScript = !hasGroup
                grouped = hasGroup
                i++
            }

            char == '{' -> {
                grouped = true
                i++
            }

            char == '}' -> {
                if (grouped) {
                    script = 0
                    grouped = false
                }
                i++
            }

            else -> {
                push(char.toString(), char.isLetter(), script)
                i++
                if (singleScript) {
                    script = 0
                    singleScript = false
                }
            }
        }
    }
    return runs
}

/**
 * One visible run of a TeX source.
 *
 * @param text the characters to draw.
 * @param italic true for a variable (a letter), false for an operator/digit/symbol.
 * @param script `1` superscript, `-1` subscript, `0` baseline.
 */
internal data class MathRun(
    public val text: String,
    public val italic: Boolean,
    public val script: Int,
)

/** The footnote reference's size relative to the body (9 pt / 17 pt, `:292`). */
private const val FOOTNOTE_SCALE: Float = 9f / 17f

/** The footnote reference's 6 pt raise relative to the 17 pt body (`:292`). */
private const val FOOTNOTE_RAISE: Float = 6f / 17f

/** A script's size relative to the base run (a typeset script is ~0.68 em). */
private const val SCRIPT_SCALE: Float = 0.68f

/** A script's raise/lower relative to the base run. */
private const val SCRIPT_RAISE: Float = 0.32f

private const val MARKER_BULLET: String = "•"
private const val MARKER_CHECKED: String = "[x]"
private const val MARKER_UNCHECKED: String = "[ ]"

/** The TeX commands the renderer maps to a glyph; everything else stays verbatim. */
private val MATH_SYMBOLS: Map<String, String> = mapOf(
    "times" to "×", "cdot" to "·", "pm" to "±", "mp" to "∓",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
    "approx" to "≈", "equiv" to "≡", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
    "infty" to "∞", "sum" to "∑", "prod" to "∏", "int" to "∫", "sqrt" to "√",
    "pi" to "π", "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "theta" to "θ", "lambda" to "λ", "mu" to "μ", "sigma" to "σ", "phi" to "φ",
    "omega" to "ω", "Delta" to "Δ", "Omega" to "Ω",
    "left" to "", "right" to "", "quad" to " ", "qquad" to "  ",
)
