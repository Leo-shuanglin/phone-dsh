// The answer body's block and inline model, and the parser that builds it.
// SPDX-License-Identifier: MIT
//
// Mirror: `examples/probe-app/PhoneDSHProbe/NativeMarkdownModel.swift`. The iOS
// page parses an answer with swift-markdown (CommonMark + GFM) and pre-processes
// three DSH extensions before the parse:
//
//   * `$$…$$` / `\[…\]` become a fenced ```math block (`:101-106`);
//   * `\(…\)` / `$…$` become a code span the AST parser turns into `.math`
//     (`:108-118`, `:205-208`);
//   * `[^id]: …` definitions are lifted out of the body and `[^id]` references
//     become an inline `.footnote` (`:85-99`, `:221-235`).
//
// This file is deliberately **not** a CommonMark engine. It recognises the block
// kinds the iOS renderer draws (heading, paragraph, list, quote, rule, fenced
// code, table, display math) and the inline kinds it styles (strong, emphasis,
// delete, code, link, inline math, footnote). Raw HTML is *text*: iOS renders
// `.html` with `Text(value)` (`NativeMarkdownBody.swift:293`) and never parses
// it, so leaving `<b>` in the paragraph is the faithful behaviour, not a gap.
package com.phonedsh.ui.surface

/** A GFM table column alignment (`NativeMarkdownTableAlignment`). */
public enum class MarkdownTableAlign {
    /** `:--` — leading. */
    LEFT,

    /** `:-:` — centred. */
    CENTER,

    /** `--:` — trailing. */
    RIGHT,
}

/** One inline run of a paragraph, a heading or a table cell. */
public sealed interface MarkdownInline {
    /** Literal text, including raw HTML tags: iOS draws them verbatim. */
    public data class Text(public val text: String) : MarkdownInline

    /** `**strong**` / `__strong__`. */
    public data class Strong(public val children: List<MarkdownInline>) : MarkdownInline

    /** `*emphasis*` / `_emphasis_`. */
    public data class Emphasis(public val children: List<MarkdownInline>) : MarkdownInline

    /** `~~deleted~~`. */
    public data class Delete(public val children: List<MarkdownInline>) : MarkdownInline

    /** `` `code` `` — a monospaced span, not a block. */
    public data class Code(public val code: String) : MarkdownInline

    /** `[label](destination)`. */
    public data class Link(
        public val destination: String,
        public val children: List<MarkdownInline>,
    ) : MarkdownInline

    /** `$…$` / `\(…\)` — the TeX source, typeset by the renderer. */
    public data class Math(public val latex: String) : MarkdownInline

    /** `[^id]` — a superscript reference (iOS `.footnote`). */
    public data class Footnote(public val id: String) : MarkdownInline
}

/**
 * One list item: the GFM task marker (when present) and its inline content.
 *
 * @param checked `true` for `[x]`, `false` for `[ ]`, `null` for a plain item.
 */
public data class MarkdownListItem(
    public val checked: Boolean?,
    public val inlines: List<MarkdownInline>,
)

/** One block of an answer (`NativeMarkdownBlock`). */
public sealed interface MarkdownBlock {
    /** A paragraph; soft line breaks inside it become spaces, like CommonMark. */
    public data class Paragraph(public val inlines: List<MarkdownInline>) : MarkdownBlock

    /** `#` … `######`. */
    public data class Heading(
        public val level: Int,
        public val inlines: List<MarkdownInline>,
    ) : MarkdownBlock

    /** A fenced code block; `language` may be empty. */
    public data class Code(public val language: String, public val body: String) : MarkdownBlock

    /** A GFM table (iOS `.table`). */
    public data class Table(
        public val header: List<List<MarkdownInline>>,
        public val rows: List<List<List<MarkdownInline>>>,
        public val alignments: List<MarkdownTableAlign?>,
    ) : MarkdownBlock

    /** Display math: the TeX source between `$$…$$` or `\[…\]`. */
    public data class Math(public val latex: String) : MarkdownBlock

    /** A bullet or ordered list. */
    public data class ListBlock(
        public val ordered: Boolean,
        public val start: Int,
        public val items: List<MarkdownListItem>,
    ) : MarkdownBlock

    /** A `>` quote: one level, recursively parsed. */
    public data class Quote(public val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** `---` / `***` / `___` (iOS `.thematicBreak`). */
    public data object Rule : MarkdownBlock
}

/**
 * Parses an answer body into [MarkdownBlock]s.
 *
 * Pure: the same string always yields the same blocks, so a replay and a unit
 * test see one answer.
 */
public object MarkdownParser {
    /**
     * @param source the projected answer body.
     * @return the blocks in source order, plus the footnote section iOS appends.
     */
    public fun parse(source: String): List<MarkdownBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val footnotes = LinkedHashMap<String, String>()
        val body = ArrayList<String>(lines.size)
        lines.forEach { line ->
            val match = FOOTNOTE_DEFINITION.matchEntire(line)
            if (match == null) {
                body.add(line)
            } else {
                footnotes[match.groupValues[1]] = match.groupValues[2]
            }
        }
        val blocks = parseBlocks(body).toMutableList()
        if (footnotes.isNotEmpty()) {
            // iOS appends the section after the body (`NativeMarkdownModel.swift:51-59`):
            // a bold 脚注 line, then one paragraph per definition. The definition
            // *text* never reaches the screen there: `:56` hands the definition's
            // block children to the inline parser, which drops them, and the baseline
            // shows only `[note]`. This copies that output, not the bug's cause.
            blocks.add(MarkdownBlock.Paragraph(listOf(MarkdownInline.Strong(listOf(
                MarkdownInline.Text(FOOTNOTE_HEADING),
            )))))
            footnotes.keys.forEach { id ->
                blocks.add(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("[$id] "))))
            }
        }
        return blocks
    }

    /** Parses a line range; used for the body and recursively for a quote. */
    private fun parseBlocks(lines: List<String>): List<MarkdownBlock> {
        val out = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            out.add(MarkdownBlock.Paragraph(inlines(paragraph.joinToString(" "))))
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> {
                    flushParagraph()
                    i++
                }

                trimmed.startsWith(FENCE) -> {
                    val language = trimmed.removePrefix(FENCE).trim()
                    val body = StringBuilder()
                    var j = i + 1
                    while (j < lines.size && lines[j].trim() != FENCE) {
                        body.append(lines[j]).append('\n')
                        j++
                    }
                    if (j >= lines.size) {
                        // Unterminated fence: the stream has not finished arriving.
                        // The old splitter kept it prose so a half answer never
                        // flashed an empty card; the same rule holds here, and the
                        // opening line stays in the surrounding paragraph.
                        paragraph.add(trimmed)
                        i++
                    } else {
                        flushParagraph()
                        out.add(MarkdownBlock.Code(language, body.toString().trimEnd('\n')))
                        i = j + 1
                    }
                }

                trimmed.startsWith("$$") -> {
                    flushParagraph()
                    val close = trimmed.indexOf("$$", 2)
                    if (close > 0) {
                        out.add(MarkdownBlock.Math(trimmed.substring(2, close).trim()))
                        i++
                    } else {
                        val latex = StringBuilder(trimmed.removePrefix("$$"))
                        var j = i + 1
                        while (j < lines.size && lines[j].trim() != "$$") {
                            latex.append('\n').append(lines[j])
                            j++
                        }
                        out.add(MarkdownBlock.Math(latex.toString().trim()))
                        i = if (j >= lines.size) lines.size else j + 1
                    }
                }

                trimmed.startsWith("\\[") -> {
                    flushParagraph()
                    val latex = StringBuilder(trimmed.removePrefix("\\["))
                    var j = i
                    if (trimmed.length > 4 && trimmed.endsWith("\\]")) {
                        out.add(MarkdownBlock.Math(trimmed.substring(2, trimmed.length - 2)))
                        i++
                    } else {
                        j += 1
                        while (j < lines.size && !lines[j].trim().endsWith("\\]")) {
                            latex.append('\n').append(lines[j])
                            j++
                        }
                        if (j < lines.size) latex.append('\n').append(lines[j].trim().removeSuffix("\\]"))
                        out.add(MarkdownBlock.Math(latex.toString().trim()))
                        i = if (j >= lines.size) lines.size else j + 1
                    }
                }

                isTableHeader(lines, i) -> {
                    flushParagraph()
                    val header = cells(lines[i]).map { inlines(it) }
                    val alignments = cells(lines[i + 1]).map(::alignment)
                    val rows = mutableListOf<List<List<MarkdownInline>>>()
                    var j = i + 2
                    while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                        rows.add(cells(lines[j]).map { inlines(it) })
                        j++
                    }
                    out.add(MarkdownBlock.Table(header, rows, alignments))
                    i = j
                }

                HEADING.matches(trimmed) -> {
                    flushParagraph()
                    val match = HEADING.matchEntire(trimmed)!!
                    out.add(
                        MarkdownBlock.Heading(
                            level = match.groupValues[1].length,
                            inlines = inlines(match.groupValues[2].trim()),
                        ),
                    )
                    i++
                }

                RULE.matches(trimmed) -> {
                    flushParagraph()
                    out.add(MarkdownBlock.Rule)
                    i++
                }

                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quoted = mutableListOf<String>()
                    var j = i
                    while (j < lines.size && lines[j].trim().startsWith(">")) {
                        quoted.add(lines[j].trim().removePrefix(">").removePrefix(" "))
                        j++
                    }
                    out.add(MarkdownBlock.Quote(parseBlocks(quoted)))
                    i = j
                }

                BULLET.matches(line) || ORDERED.matches(line) -> {
                    flushParagraph()
                    val ordered = ORDERED.matches(line)
                    val start = if (ordered) {
                        ORDERED.matchEntire(line)!!.groupValues[1].toIntOrNull() ?: 1
                    } else {
                        1
                    }
                    val items = mutableListOf<MarkdownListItem>()
                    var j = i
                    while (j < lines.size && (BULLET.matches(lines[j]) || ORDERED.matches(lines[j]))) {
                        val item = if (ordered) {
                            ORDERED.matchEntire(lines[j])!!.groupValues[2]
                        } else {
                            BULLET.matchEntire(lines[j])!!.groupValues[1]
                        }
                        var text = item
                        var checked: Boolean? = null
                        val task = TASK.matchEntire(item)
                        if (task != null) {
                            checked = task.groupValues[1].equals("x", ignoreCase = true)
                            text = task.groupValues[2]
                        }
                        items.add(MarkdownListItem(checked, inlines(text)))
                        j++
                    }
                    out.add(MarkdownBlock.ListBlock(ordered, start, items))
                    i = j
                }

                else -> {
                    paragraph.add(trimmed)
                    i++
                }
            }
        }
        flushParagraph()
        return out
    }

    /** Splits a table line into trimmed cells. */
    private fun cells(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|")) text = text.dropLast(1)
        return text.split("|").map { it.trim() }
    }

    /** True when line `index` opens a table: a pipe row followed by a delimiter row. */
    private fun isTableHeader(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index]
        if (!header.contains('|')) return false
        val delimiter = lines[index + 1].trim()
        if (delimiter.isEmpty() || delimiter.any { it !in "-:| \t" }) return false
        if (!delimiter.contains('-')) return false
        val columns = cells(header).size
        val markers = cells(delimiter)
        return markers.size == columns && markers.all { DELIMITER_CELL.matches(it) }
    }

    private fun alignment(cell: String): MarkdownTableAlign? = when {
        cell.startsWith(":") && cell.endsWith(":") -> MarkdownTableAlign.CENTER
        cell.endsWith(":") -> MarkdownTableAlign.RIGHT
        cell.startsWith(":") -> MarkdownTableAlign.LEFT
        else -> null
    }

    // ------------------------------------------------------------------ inline

    /** Parses the inline runs of one text run. */
    internal fun inlines(text: String): List<MarkdownInline> {
        val out = mutableListOf<MarkdownInline>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isEmpty()) return
            out.add(MarkdownInline.Text(buffer.toString()))
            buffer.clear()
        }

        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush()
                        out.add(MarkdownInline.Strong(inlines(text.substring(i + 2, end))))
                        i = end + 2
                    } else {
                        buffer.append(text[i++])
                    }
                }

                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        flush()
                        out.add(MarkdownInline.Delete(inlines(text.substring(i + 2, end))))
                        i = end + 2
                    } else {
                        buffer.append(text[i++])
                    }
                }

                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        out.add(MarkdownInline.Code(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        buffer.append(text[i++])
                    }
                }

                text.startsWith("[^", i) -> {
                    val end = text.indexOf(']', i + 2)
                    if (end > i + 2) {
                        flush()
                        out.add(MarkdownInline.Footnote(text.substring(i + 2, end)))
                        i = end + 1
                    } else {
                        buffer.append(text[i++])
                    }
                }

                text[i] == '[' -> {
                    val label = text.indexOf(']', i + 1)
                    if (label > i && label + 1 < text.length && text[label + 1] == '(') {
                        val close = text.indexOf(')', label + 2)
                        if (close > label) {
                            flush()
                            out.add(
                                MarkdownInline.Link(
                                    destination = text.substring(label + 2, close).trim(),
                                    children = inlines(text.substring(i + 1, label)),
                                ),
                            )
                            i = close + 1
                        } else {
                            buffer.append(text[i++])
                        }
                    } else {
                        buffer.append(text[i++])
                    }
                }

                text.startsWith("\\(", i) -> {
                    val end = text.indexOf("\\)", i + 2)
                    if (end > i + 2) {
                        flush()
                        out.add(MarkdownInline.Math(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        buffer.append(text[i++])
                    }
                }

                // Inline `$…$`; a `$$` pair is display math and is handled by the
                // block parser, so it is left alone here.
                text[i] == '$' && !text.startsWith("$$", i) -> {
                    val end = text.indexOf('$', i + 1)
                    val content = if (end > i + 1) text.substring(i + 1, end) else ""
                    if (end > i + 1 && !content.contains('\n') && !content.startsWith("$")) {
                        flush()
                        out.add(MarkdownInline.Math(content))
                        i = end + 1
                    } else {
                        buffer.append(text[i++])
                    }
                }

                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        flush()
                        out.add(MarkdownInline.Emphasis(inlines(text.substring(i + 1, end))))
                        i = end + 1
                    } else {
                        buffer.append(text[i++])
                    }
                }

                else -> buffer.append(text[i++])
            }
        }
        flush()
        return out
    }

    private const val FENCE: String = "```"
    private const val FOOTNOTE_HEADING: String = "脚注"

    private val FOOTNOTE_DEFINITION = Regex("""^\[\^([^\]]+)\]:\s*(.*)$""")
    private val DELIMITER_CELL = Regex("""^:?-{1,}:?$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val RULE = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
    private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ORDERED = Regex("""^\s*(\d{1,9})[.)]\s+(.*)$""")
    private val TASK = Regex("""^\[([ xX])]\s+(.*)$""")
}
