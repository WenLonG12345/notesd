package notes.app.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The insets controller of the window this view's activity owns. */
fun View.windowInsetsController(): WindowInsetsControllerCompat? {
    var c = context
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    return (c as? Activity)?.window?.let { WindowCompat.getInsetsController(it, this) }
}

fun View.hideKeyboard() {
    windowInsetsController()?.hide(WindowInsetsCompat.Type.ime())
}
