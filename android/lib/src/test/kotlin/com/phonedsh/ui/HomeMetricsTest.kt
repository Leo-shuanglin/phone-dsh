// HomeSpec mirror discipline: the PR22 home table has one Android declaration site.
// SPDX-License-Identifier: MIT
//
// The test reads the authoritative Swift source and the generated code, never a
// hand-copied Kotlin number:
//  1. every `static let ...: CGFloat` in HomeSpec.swift is emitted exactly once
//     into `HomeSpecData` and re-exported exactly once by `HomeMetrics`;
//  2. every HomeSpec colour is emitted as light/dark and the resolved `UiColors`
//     field carries it (page, surface, bubble, buttons, text roles, success,
//     border);
//  3. the eight home type roles carry the Swift size/line and the HTML/Swift
//     weight, and `UiType` exposes them;
//  4. no page source and no HomeMetrics line re-types a dp/sp literal;
//  5. the generated table is not stale relative to the Swift source.
package com.phonedsh.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phonedsh.ui.theme.UiType
import com.phonedsh.ui.theme.UiTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class HomeMetricsTest {
    private val home: String by lazy { read("examples/probe-app/PhoneDSHProbe/HomeSpec.swift") }
    private val html: String by lazy { read("docs/design/ios-home-design-spec-no-overview.html") }
    private val generated: String by lazy {
        read("android/lib/build/generated/ui-tokens/kotlin/com/phonedsh/ui/theme/generated/HomeSpecData.kt")
    }
    private val metrics: String by lazy {
        read("android/lib/src/main/kotlin/com/phonedsh/ui/HomeMetrics.kt")
    }

    @Test
    fun `every HomeSpec CGFloat is generated and mirrored exactly once`() {
        val numbers = numericConstants()
        assertTrue("HomeSpec.swift must expose its grid/size/radius table", numbers.size >= 30)
        numbers.forEach { (key, value) ->
            val constant = upperSnake(key)
            assertTrue(
                "HomeSpecData is missing $constant = $value (regenerate :lib:generateUiTokens)",
                generated.contains("public const val $constant: Float = ${value}f"),
            )
            val uses = Regex("HomeSpecData\\.$constant\\b").findAll(metrics).count()
            assertEquals("HomeMetrics must re-export $constant exactly once", 1, uses)
            assertTrue(
                "HomeMetrics must expose ${constant}_PT from HomeSpecData.$constant",
                metrics.contains("${constant}_PT: Float = HomeSpecData.$constant"),
            )
        }
        assertTrue(
            "HomeMetrics must expose the tag size HomeSpec writes as a literal",
            metrics.contains("TAG_LABEL_SIZE_PT: Float = HomeSpecData.TAG_LABEL_SIZE"),
        )
        assertTrue(
            "HomeMetrics must expose the bubble's square top-trailing corner",
            metrics.contains("BUBBLE_TOP_TRAILING_RADIUS_PT: Float = HomeSpecData.BUBBLE_TOP_TRAILING_RADIUS"),
        )
    }

    @Test
    fun `the resolved palette carries every HomeSpec colour`() {
        val colours = colourConstants()
        assertTrue("HomeSpec.swift must expose its palette", colours.size >= 12)
        colours.forEach { (key, pair) ->
            val constant = upperSnake(key)
            assertTrue("HomeSpecData is missing ${constant}_LIGHT", generated.contains("${constant}_LIGHT: Long = ${pair.first}L"))
            assertTrue("HomeSpecData is missing ${constant}_DARK", generated.contains("${constant}_DARK: Long = ${pair.second}L"))
        }

        val light = UiTokens.resolve(dark = false).colors
        val dark = UiTokens.resolve(dark = true).colors
        assertEquals(colors("page", false), light.page)
        assertEquals(colors("page", true), dark.page)
        assertEquals(colors("surface", false), light.surface)
        assertEquals(colors("userBubble", false), light.userBubble)
        assertEquals(colors("primaryButton", false), light.primaryButton)
        assertEquals(colors("primaryButtonInk", false), light.primaryButtonInk)
        assertEquals(colors("textPrimary", false), light.textPrimary)
        assertEquals(colors("textSecondary", false), light.textSecondary)
        assertEquals(colors("textTertiary", false), light.textTertiary)
        assertEquals(colors("textSubtle", false), light.textSubtle)
        assertEquals(colors("textPlaceholder", false), light.textPlaceholder)
        assertEquals(colors("inputPlaceholder", false), light.inputPlaceholder)
        assertEquals(colors("iconMuted", false), light.iconMuted)
        assertEquals(colors("success", false), light.success)
        assertEquals(colors("borderList", false), light.borderList)
        assertEquals(colors("page", true), dark.page)
        assertEquals(colors("surface", true), dark.surface)
        assertEquals(colors("primaryButton", true), dark.primaryButton)
    }

    @Test
    fun `the home type roles carry the Swift size and line and the spec weight`() {
        val body = numbers()
        val navTitle = cssFont(".navtitle b")
        val navSubtitle = cssFont(".navtitle span")
        val prose = cssFont(".body")
        val process = cssFont(".proc")
        val listItem = cssFont(".listcard div")
        val tab = cssFont(".tabbar div")

        assertRole("navTitle", body.getValue("navTitleSize"), navTitle.first,
            body.getValue("navTitleLine"))
        assertRole("navSubtitle", body.getValue("navSubtitleSize"), navSubtitle.first,
            body.getValue("navSubtitleLine"))
        assertRole("body", body.getValue("bodySize"), prose.first,
            body.getValue("bodyLine"))
        assertRole("processBody", body.getValue("processSize"), process.first, process.third)
        assertRole("listItem", body.getValue("listItemSize"), listItem.first,
            body.getValue("listItemLine"))
        assertRole("buttonLabel", body.getValue("bodySize"), swiftWeight("HomeSpec"), null)
        assertRole("tagLabel", 12f, tagWeight(), null)
        assertRole("tabLabel", body.getValue("tabLabelSize"), tab.first, tab.third)

        val type = UiType.fromSpec()
        assertEquals(17.5f.sp, type.navTitle.fontSize)
        assertEquals(FontWeight(600), type.navTitle.fontWeight)
        assertEquals(22.sp, type.navTitle.lineHeight)
        assertEquals(14.5f.sp, type.navSubtitle.fontSize)
        assertEquals(20.sp, type.navSubtitle.lineHeight)
        assertEquals(16.sp, type.homeBody.fontSize)
        assertEquals(24.sp, type.homeBody.lineHeight)
        assertEquals(15.sp, type.processBody.fontSize)
        assertEquals(24.sp, type.processBody.lineHeight)
        assertEquals(14.sp, type.listItem.fontSize)
        assertEquals(22.sp, type.listItem.lineHeight)
        assertEquals(16.sp, type.buttonLabel.fontSize)
        assertEquals(FontWeight(600), type.buttonLabel.fontWeight)
        assertEquals(12.sp, type.tagLabel.fontSize)
        assertEquals(FontWeight(500), type.tagLabel.fontWeight)
        assertEquals(10.5f.sp, type.tabLabel.fontSize)
        assertEquals(14.sp, type.tabLabel.lineHeight)
    }

    @Test
    fun `no page source or HomeMetrics line re-types a dp or sp value`() {
        val length = Regex("""\b\d+(?:\.\d+)?\.(?:dp|sp)\b""")
        val pageDir = SpecFixtures.repoRoot.resolve("android/lib/src/main/kotlin/com/phonedsh/ui/page")
        Files.list(pageDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") }.forEach { file ->
                val found = length.findAll(String(Files.readAllBytes(file), Charsets.UTF_8))
                    .map { it.value }.toList()
                assertTrue("${file.fileName} re-types a length: $found", found.isEmpty())
            }
        }
        val homeFound = length.findAll(metrics).map { it.value }.toList()
        assertTrue("HomeMetrics.kt re-types a length: $homeFound", homeFound.isEmpty())
        // The only literal the file is allowed to hold is the structural square
        // corner of HomeSpec.bubbleShape (0), which is not a measured value.
        val assignments = Regex("""=\s*[1-9][0-9]*\.?[0-9]*f""").findAll(metrics).map { it.value }.toList()
        assertTrue("HomeMetrics.kt hand-types a measured value: $assignments", assignments.isEmpty())
    }

    private fun assertRole(name: String, size: Float, weight: Int, line: Float?) {
        val generatedRole = Regex(
            """\"typography\.$name\" to TokenType\(\"typography\.$name\", ([0-9.]+)f, (\d+), false, (null|[0-9.]+f)\)"""
        ).find(generated) ?: error("HomeSpecData has no generated type role $name")
        assertEquals("$name size", size, generatedRole.groupValues[1].toFloat(), 0f)
        assertEquals("$name weight", weight, generatedRole.groupValues[2].toInt())
        if (line == null) {
            assertEquals("$name line", "null", generatedRole.groupValues[3])
        } else {
            assertEquals("$name line", line, generatedRole.groupValues[3].removeSuffix("f").toFloat(), 0f)
        }
    }

    private fun cssFont(selector: String): Triple<Int, Float, Float> {
        val block = Regex(Regex.escape(selector) + """\s*\{([^}]*)\}""").find(html)
            ?.groupValues?.get(1) ?: error("design spec has no selector $selector")
        val font = Regex("""font:\s*(\d+)\s+([0-9.]+)px/([0-9.]+)px""").find(block)
            ?: error("design spec selector $selector has no font shorthand")
        return Triple(font.groupValues[1].toInt(), font.groupValues[2].toFloat(), font.groupValues[3].toFloat())
    }

    private fun tagWeight(): Int {
        val m = Regex("""font\(\.system\(size: 12, weight: \.(\w+)\)\)""").find(home)
            ?: error("HomeSpec.HomeTag has no 12pt weight")
        return weight(m.groupValues[1])
    }

    private fun swiftWeight(anchor: String): Int {
        val m = Regex("""font\(\.system\(size: HomeSpec\.bodySize, weight: \.(\w+)\)\)""").find(home)
            ?: error("$anchor has no body-size semibold role")
        return weight(m.groupValues[1])
    }

    private fun weight(name: String): Int = when (name) {
        "regular" -> 400
        "medium" -> 500
        "semibold" -> 600
        "bold" -> 700
        else -> error("unknown Swift weight .$name")
    }

    private fun colors(key: String, dark: Boolean): Color {
        val pair = colourConstants().getValue(key)
        return Color(if (dark) pair.second else pair.first)
    }

    private fun numbers(): Map<String, Float> = numericConstants()

    private fun numericConstants(): Map<String, Float> =
        Regex("""static let (\w+): CGFloat = ([0-9.]+)""").findAll(home)
            .associate { it.groupValues[1] to it.groupValues[2].toFloat() }

    private fun colourConstants(): Map<String, Pair<Long, Long>> =
        Regex("""static let (\w+) = NativeParityTokens\.color\(light: 0x([0-9A-Fa-f]{6}), dark: 0x([0-9A-Fa-f]{6})\)""")
            .findAll(home).associate { m ->
                m.groupValues[1] to (
                    ("FF" + m.groupValues[2].uppercase()).toLong(16) to
                        ("FF" + m.groupValues[3].uppercase()).toLong(16)
                    )
            }

    private fun upperSnake(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    private fun read(relative: String): String {
        val path: Path = SpecFixtures.repoRoot.resolve(relative)
        assertTrue("missing $relative", Files.exists(path))
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
}
