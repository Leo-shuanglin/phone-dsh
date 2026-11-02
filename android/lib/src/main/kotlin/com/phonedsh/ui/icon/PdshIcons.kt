// PR22 native icon registry. Every mark is vector geometry, never text glyphs.
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stable app icon identifiers mapped to the iOS SF Symbol used by each owner. */
public enum class PdshIcon(public val sfSymbol: String, public val permissionOnly: Boolean = false) {
    NEW_CHAT("square.and.pencil"),
    CLOCK("clock"),
    GEAR("gearshape"),
    FOLDER("folder"),
    SESSIONS("list.dash"),
    ARCHIVE("archivebox"),
    FOLDER_PLUS("folder.badge.plus"),
    CHEVRON_DOWN("chevron.down"),
    CHEVRON_UP("chevron.up"),
    CHEVRON_RIGHT("chevron.right"),
    CHEVRON_LEFT("chevron.left"),
    CLOSE("xmark"),
    CHECK("checkmark"),
    PLUS("plus"),
    MORE("ellipsis"),
    REFRESH("arrow.clockwise"),
    CPU("cpu"),
    PEOPLE("person.2"),
    PUZZLE("puzzlepiece.extension"),
    LOCK_SHIELD("lock.shield"),
    HAND("hand.raised"),
    NETWORK("network"),
    GLOBE("globe"),
    TEXT_SIZE("textformat.size"),
    LIST("list.bullet.rectangle"),
    COMMAND("command"),
    BOLT("bolt.horizontal"),
    WAVEFORM("waveform.path.ecg"),
    INFO("info.circle"),
    DOCUMENT("doc.text"),
    NUMBER("number"),
    MENU("line.3.horizontal"),
    DEVICE("iphone"),
    DEVICE_RADIO("dot.radiowaves.left.and.right"),
    CHAT_BUBBLE("bubble.left"),
    TERMINAL("terminal"),
    SEND("arrow.up"),
    WARNING("exclamationmark.triangle"),
    TRASH("trash"),
    PENCIL("pencil"),
    SEARCH("magnifyingglass"),
    SPARKLE("sparkle"),
    /** Permission artwork only; HomeSpec currently has `showsVoiceInput = false`. */
    MICROPHONE_PERMISSION("mic", permissionOnly = true),
}

private data class IconPath(val data: String, val filled: Boolean = false, val width: Float = 1.8f)

/** One source of truth for rendered vectors, SF Symbol provenance and caller tint. */
public object PdshIcons {
    /** Resolves a real, distinct vector path for [icon], with the requested tint embedded. */
    public fun vector(icon: PdshIcon, tint: Color): ImageVector {
        val builder = ImageVector.Builder(
            name = icon.name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        definitions.getValue(icon).forEach { part ->
            val parsed = PathParser().parsePathString(part.data).toNodes()
            builder.addPath(
                pathData = parsed,
                fill = if (part.filled) SolidColor(tint) else null,
                stroke = if (part.filled) null else SolidColor(tint),
                strokeLineWidth = part.width,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private val definitions: Map<PdshIcon, List<IconPath>> = mapOf(
        PdshIcon.NEW_CHAT to listOf(
            IconPath("M13 4H5.5C4.67 4 4 4.67 4 5.5v13c0 .83.67 1.5 1.5 1.5H18c.83 0 1.5-.67 1.5-1.5V13"),
            IconPath("M11 13l1.1-4.1L19.2 1.8a1.98 1.98 0 0 1 2.8 2.8l-7.1 7.1L11 13Z"),
        ),
        PdshIcon.CLOCK to listOf(
            IconPath("M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17Z"),
            IconPath("M12 7v5l3.5 2"),
        ),
        PdshIcon.GEAR to listOf(
            IconPath("M12 3.3 13.2 3.5 14 5.5 15.5 6.1 17.5 5.1 18.9 6.5 17.9 8.5 18.5 10 20.5 10.8 20.7 12 20.5 13.2 18.5 14 17.9 15.5 18.9 17.5 17.5 18.9 15.5 17.9 14 18.5 13.2 20.5 12 20.7 10.8 20.5 10 18.5 8.5 17.9 6.5 18.9 5.1 17.5 6.1 15.5 5.5 14 3.5 13.2 3.3 12 3.5 10.8 5.5 10 6.1 8.5 5.1 6.5 6.5 5.1 8.5 6.1 10 5.5 10.8 3.5Z"),
            IconPath("M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"),
        ),
        PdshIcon.FOLDER to listOf(IconPath("M3 6.2c0-.9.7-1.6 1.6-1.6h5l2 2.3h7.8c.9 0 1.6.7 1.6 1.6v9.3c0 .9-.7 1.6-1.6 1.6H4.6c-.9 0-1.6-.7-1.6-1.6V6.2Z")),
        PdshIcon.SESSIONS to listOf(IconPath("M5 7h14M5 12h14M5 17h9", width = 1.5f)),
        PdshIcon.ARCHIVE to listOf(IconPath("M3 4.5h18v4H3z"), IconPath("M4 8.5h16v10.8H4z"), IconPath("M9 12.5h6")),
        PdshIcon.FOLDER_PLUS to listOf(IconPath("M2.8 6.1c0-.9.7-1.6 1.6-1.6h5l2 2.3h7.9c.9 0 1.6.7 1.6 1.6v3.3H7c-.8 0-1.5.5-1.8 1.3l-2.4 6.4V6.1Z"), IconPath("M16.5 13v7M13 16.5h7")),
        PdshIcon.CHEVRON_DOWN to listOf(IconPath("M5 9l7 7 7-7")),
        PdshIcon.CHEVRON_UP to listOf(IconPath("M5 15l7-7 7 7")),
        PdshIcon.CHEVRON_RIGHT to listOf(IconPath("M9 5l7 7-7 7")),
        PdshIcon.CHEVRON_LEFT to listOf(IconPath("M15 5l-7 7 7 7")),
        // DSHClose.svg and DSHCheck.svg (viewBox 16) scaled to the 24pt registry viewport.
        PdshIcon.CLOSE to listOf(IconPath("M3.75 3.75 20.25 20.25M20.25 3.75 3.75 20.25", width = 1.5f)),
        PdshIcon.CHECK to listOf(IconPath("M3.375 12.75 8.24598 17.62095C8.857785 18.2328 9.858945 18.2016 10.43139 17.5527L20.625 6", width = 1.5f)),
        PdshIcon.PLUS to listOf(IconPath("M12 5v14M5 12h14")),
        PdshIcon.MORE to listOf(IconPath("M5 12a1 1 0 1 0 0-.01M12 12a1 1 0 1 0 0-.01M19 12a1 1 0 1 0 0-.01", filled = true)),
        PdshIcon.REFRESH to listOf(IconPath("M20 11a8 8 0 0 0-14-5L4 8"), IconPath("M4 4v4h4"), IconPath("M4 13a8 8 0 0 0 14 5l2-2"), IconPath("M20 20v-4h-4")),
        PdshIcon.CPU to listOf(IconPath("M7 7h10v10H7z"), IconPath("M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3")),
        PdshIcon.PEOPLE to listOf(IconPath("M9 11a3.2 3.2 0 1 0 0-6.4A3.2 3.2 0 0 0 9 11ZM16.5 10a2.6 2.6 0 1 0 0-5.2"), IconPath("M2.8 19c.3-3.2 2.4-5.1 6.2-5.1s5.9 1.9 6.2 5.1M15 14.2c3.2-.1 5.2 1.5 5.8 4.2")),
        PdshIcon.PUZZLE to listOf(IconPath("M9 3.5h2a2.5 2.5 0 1 1 5 0h2.5v5a2.5 2.5 0 1 1 0 5v7H13v-2a2.5 2.5 0 1 0-5 0v2H4v-6.5h2a2.5 2.5 0 1 0 0-5H4v-5h5v-2Z")),
        PdshIcon.LOCK_SHIELD to listOf(IconPath("M12 2.8 20 6v5.5c0 4.8-3.2 8.1-8 10-4.8-1.9-8-5.2-8-10V6l8-3.2Z"), IconPath("M8.5 10V8.5a3.5 3.5 0 0 1 7 0V10M8 10h8v5H8z")),
        PdshIcon.HAND to listOf(IconPath("M7.5 11V6.2a1.5 1.5 0 0 1 3 0V10 5.2a1.5 1.5 0 0 1 3 0V10 6.3a1.5 1.5 0 0 1 3 0v5.1l.6-1a1.5 1.5 0 0 1 2.6 1.5l-3.1 5.3a4 4 0 0 1-3.4 1.9h-2.1a5 5 0 0 1-3.8-1.8L5 14.5a1.8 1.8 0 0 1 2.5-2.6l2 1.8")),
        PdshIcon.NETWORK to listOf(IconPath("M12 12 5 6M12 12l7-6M12 12l-1 8M5 6l2-2M5 6l-2 2M19 6l2-2M19 6l2 2"), IconPath("M14.5 12a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0ZM7 5a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM21 5a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM13 20a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z")),
        PdshIcon.GLOBE to listOf(IconPath("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM3.5 12h17M12 3c2.2 2.4 3.2 5.4 3.2 9S14.2 18.6 12 21M12 3c-2.2 2.4-3.2 5.4-3.2 9S9.8 18.6 12 21")),
        PdshIcon.TEXT_SIZE to listOf(IconPath("M3.5 7V4.5h12V7M9.5 4.5V19M5.5 19h8M16 11h5M18.5 11v8M16.5 19h4")),
        PdshIcon.LIST to listOf(IconPath("M8 5h12M8 9h12M8 13h12M8 17h12M3.5 5h.1M3.5 9h.1M3.5 13h.1M3.5 17h.1")),
        PdshIcon.COMMAND to listOf(IconPath("M8 8V5.5a2.5 2.5 0 1 0-2.5 2.5H8Zm0 0h8m0 0V5.5A2.5 2.5 0 1 1 18.5 8H16Zm0 0v8m0 0h2.5a2.5 2.5 0 1 1-2.5 2.5V16Zm0 0H8m0 0v2.5A2.5 2.5 0 1 1 5.5 16H8Zm0 0V8")),
        PdshIcon.BOLT to listOf(IconPath("M21.2 13.5 11 5.7v5.5l-8.2-.7L13 18.3v-5.5l8.2.7Z", filled = true)),
        PdshIcon.WAVEFORM to listOf(IconPath("M2.5 12H7l2-6 4 12 2-6h6.5")),
        PdshIcon.INFO to listOf(IconPath("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z"), IconPath("M12 11v5M12 7.5h.01")),
        PdshIcon.DOCUMENT to listOf(IconPath("M6 3.5h8l4 4V20H6zM14 3.5V8h4M9 12h6M9 15h6M9 18h4")),
        PdshIcon.NUMBER to listOf(IconPath("M8 4 6 20M18 4l-2 16M4 9h16M3 15h16")),
        PdshIcon.MENU to listOf(IconPath("M4 6h16M4 12h16M4 18h16")),
        PdshIcon.DEVICE to listOf(IconPath("M7 2.8h10c1 0 1.8.8 1.8 1.8v14.8c0 1-.8 1.8-1.8 1.8H7c-1 0-1.8-.8-1.8-1.8V4.6c0-1 .8-1.8 1.8-1.8ZM10 5.5h4M10.5 18h3")),
        PdshIcon.DEVICE_RADIO to listOf(IconPath("M12 13v.1M8.5 9.5a5 5 0 0 0 0 7M15.5 9.5a5 5 0 0 1 0 7M5.5 6.5a9.2 9.2 0 0 0 0 13M18.5 6.5a9.2 9.2 0 0 1 0 13")),
        PdshIcon.CHAT_BUBBLE to listOf(IconPath("M4 5.5c0-1.1.9-2 2-2h12c1.1 0 2 .9 2 2v9c0 1.1-.9 2-2 2h-8l-5 4v-4.2c-.6-.4-1-1-1-1.8v-9Z")),
        PdshIcon.TERMINAL to listOf(IconPath("M3.5 4.5h17v15h-17zM7 9l3 3-3 3M12.5 15h4")),
        PdshIcon.SEND to listOf(IconPath("M12 19V5M6.5 10.5 12 5l5.5 5.5")),
        PdshIcon.WARNING to listOf(IconPath("M10.3 4.2 2.8 17.5c-.6 1 .1 2.2 1.3 2.2h15.8c1.2 0 1.9-1.2 1.3-2.2L13.7 4.2a2 2 0 0 0-3.4 0Z"), IconPath("M12 9v4M12 16.5h.01")),
        PdshIcon.TRASH to listOf(IconPath("M4 6.5h16M9 6.5V4h6v2.5M6.5 6.5l1 14h9l1-14M10 10v7M14 10v7")),
        PdshIcon.PENCIL to listOf(IconPath("M4 16.5V20h3.5L19 8.5 15.5 5 4 16.5ZM13.8 6.7l3.5 3.5")),
        PdshIcon.SEARCH to listOf(IconPath("M10.8 3.5a7.3 7.3 0 1 0 0 14.6 7.3 7.3 0 0 0 0-14.6ZM16.2 16.2 21 21")),
        PdshIcon.SPARKLE to listOf(IconPath("M12 2.5 14 9l6.5 2-6.5 2-2 6.5-2-6.5-6.5-2 6.5-2 2-6.5ZM19 15.5l1 3 3 1-3 1-1 3-1-3-3-1 3-1 1-3Z")),
        PdshIcon.MICROPHONE_PERMISSION to listOf(IconPath("M12 3.2a3 3 0 0 0-3 3v6.2a3 3 0 0 0 6 0V6.2a3 3 0 0 0-3-3ZM6.5 11.5a5.5 5.5 0 0 0 11 0M12 17v4M8.5 21h7")),
    )
}

/** Caller-owned size, tint and accessibility description for a registered vector icon. */
@Composable
public fun PdshIconView(
    icon: PdshIcon,
    size: Dp,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val vector = remember(icon, tint) { PdshIcons.vector(icon, tint) }
    Image(
        imageVector = vector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}
