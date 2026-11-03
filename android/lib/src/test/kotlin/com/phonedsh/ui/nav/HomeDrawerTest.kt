// The home drawer dialog follows the app theme's system-bar glyphs (issue 9).
// SPDX-License-Identifier: MIT
package com.phonedsh.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonedsh.ui.theme.PdshTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeDrawerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a dark app theme gives the drawer dialog light status-bar glyphs`() {
        show(darkTheme = true)
        val controller = dialogController()
        assertFalse("dark chrome needs light status-bar glyphs", controller.isAppearanceLightStatusBars)
        assertFalse("dark chrome needs light navigation-bar glyphs", controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun `a light app theme gives the drawer dialog dark status-bar glyphs`() {
        show(darkTheme = false)
        val controller = dialogController()
        assertTrue("light chrome needs dark status-bar glyphs", controller.isAppearanceLightStatusBars)
        assertTrue("light chrome needs dark navigation-bar glyphs", controller.isAppearanceLightNavigationBars)
    }

    private fun show(darkTheme: Boolean) {
        compose.setContent {
            PdshTheme(darkTheme = darkTheme) {
                Box(Modifier.fillMaxSize()) {
                    HomeDrawer(HomeDrawerFacts(), onClose = {}, onCreateSession = { true })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun dialogController(): WindowInsetsControllerCompat {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        val window = dialog.window
        assertNotNull(window)
        return WindowCompat.getInsetsController(window!!, window.decorView)
    }
}
