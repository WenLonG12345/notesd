package notes.app.editor

import android.graphics.Rect
import androidx.core.widget.NestedScrollView

/**
 * Keeps [editor]'s cursor in view when the scroller gets shorter (the keyboard
 * or the toolbars coming up). NestedScrollView alone only keeps the focused
 * view on screen, and here that's the whole note.
 */
fun NestedScrollView.keepCursorInView(editor: NoteEditText) {
    addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top < oldBottom - oldTop && editor.hasFocus()) editor.revealCursor()
    }
}

private fun NoteEditText.revealCursor() {
    val layout = layout ?: return
    val line = layout.getLineForOffset(selectionEnd)
    // A line of room below, so the cursor doesn't sit against the keyboard's toolbar.
    val below = layout.getLineBottom(line) - layout.getLineTop(line)
    val rect = Rect(0, layout.getLineTop(line), width, layout.getLineBottom(line) + below)
    rect.offset(0, totalPaddingTop)
    // Immediate: the keyboard animates in a frame at a time, and a smooth scroll would trail it.
    requestRectangleOnScreen(rect, true)
}
