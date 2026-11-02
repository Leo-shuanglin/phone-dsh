// Tests exercise real Compose ImageVector rendering semantics, not source grep or PixelCopy.
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.icon

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdshIconsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `icon composes with caller size tint and semantic description`() {
        val tint = Color(0xFF7D5CFF)
        compose.setContent {
            PdshIconView(
                icon = PdshIcon.NEW_CHAT,
                size = 31.dp,
                tint = tint,
                contentDescription = "New chat",
                modifier = Modifier.testTag("pdsh.icon.new-chat"),
            )
        }

        compose.onNodeWithTag("pdsh.icon.new-chat")
            .assertExists()
            .assertContentDescriptionEquals("New chat")
            .assertWidthIsEqualTo(31.dp)
            .assertHeightIsEqualTo(31.dp)

        val paths = PdshIcons.vector(PdshIcon.NEW_CHAT, tint).root.toList().filterIsInstance<VectorPath>()
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.any { ((it.stroke ?: it.fill) as? SolidColor)?.value == tint })
    }

    @Test
    fun `every registered symbol has distinct nonempty vector geometry`() {
        val icons = PdshIcon.values()
        assertTrue("registry should cover the HomeSpec and settings symbol set", icons.size >= 35)
        assertEquals("each entry maps to one distinct SF Symbol owner", icons.size, icons.map { it.sfSymbol }.toSet().size)
        val signatures = icons.map { icon ->
            val vector = PdshIcons.vector(icon, Color.Black)
            val paths = vector.root.toList().filterIsInstance<VectorPath>()
            assertTrue("${icon.sfSymbol} has no vector paths", paths.isNotEmpty())
            paths.joinToString("|") { path -> path.pathData.joinToString(";") }
        }
        assertEquals("each symbol has its own path set", signatures.size, signatures.toSet().size)
    }

    @Test
    fun `microphone symbol is marked permission only`() {
        assertTrue(PdshIcon.MICROPHONE_PERMISSION.permissionOnly)
        assertFalse(PdshIcon.NEW_CHAT.permissionOnly)
    }

    @Test
    fun `settings permission engine and runtime use their actual PR22 symbols`() {
        assertEquals("hand.raised", PdshIcon.HAND.sfSymbol)
        assertEquals("bolt.horizontal", PdshIcon.BOLT.sfSymbol)
        assertEquals("waveform.path.ecg", PdshIcon.WAVEFORM.sfSymbol)
    }
}
