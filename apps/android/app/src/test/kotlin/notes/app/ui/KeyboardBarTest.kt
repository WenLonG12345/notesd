package notes.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w840dp-h900dp")
class KeyboardBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a pane's bar spans the window, and its owner keeps room for it`() {
        val bar = KeyboardBar()
        compose.setContent {
            CompositionLocalProvider(LocalKeyboardBar provides bar) {
                Box(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(340.dp))
                        Box(Modifier.weight(1f)) {
                            KeyboardBarContent(bar) { Box(Modifier.fillMaxWidth().height(56.dp).testTag("toolbar")) }
                        }
                    }
                    KeyboardBarSlot(bar, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.onNodeWithTag("toolbar").assertWidthIsEqualTo(840.dp)
        compose.runOnIdle { assertEquals(56.dp, bar.height) }
    }

    @Test
    fun `a note swapped in keeps the slot when the old one leaves`() {
        val bar = KeyboardBar()
        var showOld by mutableStateOf(true)
        var showNew by mutableStateOf(false)
        compose.setContent {
            if (showOld) KeyboardBarContent(bar) { Box(Modifier.testTag("old")) }
            if (showNew) KeyboardBarContent(bar) { Box(Modifier.testTag("new")) }
            KeyboardBarSlot(bar)
        }
        compose.runOnIdle { showNew = true }
        compose.runOnIdle { showOld = false }
        compose.onNodeWithTag("new").assertExists()
        compose.onNodeWithTag("old").assertDoesNotExist()

        compose.runOnIdle { showNew = false }
        compose.onNodeWithTag("new").assertDoesNotExist()
    }
}
