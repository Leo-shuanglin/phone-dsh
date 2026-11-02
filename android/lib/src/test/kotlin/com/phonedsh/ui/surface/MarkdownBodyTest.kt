// The markdown block/inline parser, on the JVM.
// SPDX-License-Identifier: MIT
//
// Each case is one thing the iOS renderer draws differently from plain prose:
// a GFM table, display and inline TeX, a literal HTML tag, a footnote reference
// plus the section it appends, and a fenced code block.
package com.phonedsh.ui.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBodyTest {
    @Test
    fun `a GFM table keeps its header, rows and column alignment`() {
        val blocks = MarkdownParser.parse(
            """
            | 模块 | 状态 |
            |:--|--:|
            | Markdown | 原生 |
            | TeX | ${'$'}x^2${'$'} |
            """.trimIndent(),
        )
        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(2, table.header.size)
        assertEquals(2, table.rows.size)
        assertEquals(MarkdownTableAlign.LEFT, table.alignments[0])
        assertEquals(MarkdownTableAlign.RIGHT, table.alignments[1])
        assertEquals(
            listOf(MarkdownInline.Text("Markdown")),
            table.rows[0][0],
        )
        assertEquals(listOf(MarkdownInline.Text("原生")), table.rows[0][1])
    }

    @Test
    fun `a display math block is one math block, multi-line included`() {
        val single = MarkdownParser.parse("\$\$E = mc^2\$\$").single() as MarkdownBlock.Math
        assertEquals("E = mc^2", single.latex)
        val multi = MarkdownParser.parse("\$\$\nE = mc^2\n\$\$").single() as MarkdownBlock.Math
        assertEquals("E = mc^2", multi.latex)
    }

    @Test
    fun `inline math inside a table cell becomes a math run`() {
        val table = MarkdownParser.parse(
            "| a | b |\n|--|--|\n| TeX | ${'$'}x^2 + y^2 = z^2${'$'} |",
        ).single() as MarkdownBlock.Table
        assertEquals(
            listOf(MarkdownInline.Math("x^2 + y^2 = z^2")),
            table.rows[0][1],
        )
    }

    @Test
    fun `raw HTML stays literal text, it is never parsed`() {
        val paragraph = MarkdownParser.parse(
            "原始 HTML 保持字面：<b>不会执行</b>。",
        ).single() as MarkdownBlock.Paragraph
        assertEquals(
            "原始 HTML 保持字面：<b>不会执行</b>。",
            plainText(paragraph.inlines),
        )
    }

    @Test
    fun `a footnote reference is an inline node and the section is appended`() {
        val blocks = MarkdownParser.parse(
            "正文。[^note]\n\n[^note]: 流式与历史恢复使用同一事件投影。",
        )
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals(listOf(MarkdownInline.Footnote("note")), paragraph.inlines.drop(1))
        // The definition line is lifted out of the body, not drawn in place.
        assertTrue(plainText(paragraph.inlines).contains("正文。"))
        assertFalse(plainText(paragraph.inlines).contains("流式"))
        val heading = blocks[1] as MarkdownBlock.Paragraph
        assertEquals("脚注", plainText(heading.inlines))
        val definition = blocks[2] as MarkdownBlock.Paragraph
        // iOS' footnote section prints the id only: `:56` hands the definition's
        // block children to the inline parser, which drops them, and the baseline
        // shows `[note]` alone. The port matches that output.
        assertEquals("[note] ", plainText(definition.inlines))
    }

    @Test
    fun `a fenced block keeps its language and body`() {
        val code = MarkdownParser.parse(
            "```swift\nstruct NativeChat: View {\n    let official = true\n}\n```",
        ).single() as MarkdownBlock.Code
        assertEquals("swift", code.language)
        assertEquals("struct NativeChat: View {\n    let official = true\n}", code.body)
    }

    @Test
    fun `an unterminated fence stays prose so a stream never flashes a card`() {
        val blocks = MarkdownParser.parse("正文\n```swift\nstruct S {")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `strong, delete and code spans keep their kind`() {
        val paragraph = MarkdownParser.parse("**粗体**、~~删除线~~、`Package.swift`").single()
            as MarkdownBlock.Paragraph
        assertEquals(MarkdownInline.Strong(listOf(MarkdownInline.Text("粗体"))), paragraph.inlines[0])
        assertEquals(MarkdownInline.Delete(listOf(MarkdownInline.Text("删除线"))), paragraph.inlines[2])
        assertEquals(MarkdownInline.Code("Package.swift"), paragraph.inlines[4])
    }

    @Test
    fun `a task list keeps its checked state`() {
        val list = MarkdownParser.parse("- [x] 完成\n- [ ] 没完成").single() as MarkdownBlock.ListBlock
        assertEquals(false, list.ordered)
        assertEquals(listOf(true, false), list.items.map { it.checked })
    }

    @Test
    fun `TeX typesetting italicises variables and raises a superscript`() {
        val runs = mathRuns("E = mc^2")
        assertEquals(
            listOf(
                MathRun("E", italic = true, script = 0),
                MathRun(" = ", italic = false, script = 0),
                MathRun("mc", italic = true, script = 0),
                MathRun("2", italic = false, script = 1),
            ),
            runs,
        )
    }

    @Test
    fun `an unknown TeX command stays visible instead of disappearing`() {
        assertEquals("\\foo", mathRuns("\\foo").single().text)
        assertEquals("×", mathRuns("\\times").single().text)
    }

    private fun plainText(inlines: List<MarkdownInline>): String = inlines.joinToString("") { part ->
        when (part) {
            is MarkdownInline.Text -> part.text
            is MarkdownInline.Strong -> plainText(part.children)
            is MarkdownInline.Emphasis -> plainText(part.children)
            is MarkdownInline.Delete -> plainText(part.children)
            is MarkdownInline.Code -> part.code
            is MarkdownInline.Link -> plainText(part.children)
            is MarkdownInline.Math -> part.latex
            is MarkdownInline.Footnote -> "[^${part.id}]"
        }
    }
}
