package notes.app

import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Runs [block] (store or sync calls) while an activity is open. Under Robolectric the test thread is
 * the main thread, and the app's coroutines hold the store's locks across a hop to IO and back to
 * Main, so runBlocking here can wait on a lock whose holder needs this thread to finish: a deadlock.
 * This runs [block] on IO and keeps the main looper going until it's done.
 */
fun <T> ComposeTestRule.runOffMain(block: suspend () -> T): T {
    val result = CoroutineScope(Dispatchers.IO).async { block() }
    waitUntil(timeoutMillis = 5_000) { result.isCompleted }
    return runBlocking { result.await() }
}
