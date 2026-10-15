package notes.app.images

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.LruCache
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import notes.app.editor.ImageSource
import notes.core.api.LOCKED_IMAGE
import notes.core.crypto.LockSession
import notes.core.crypto.LockedException
import notes.core.crypto.LockedNotes
import notes.core.images.ImageStore

/**
 * Pictures for the editor (architecture §7.6): from [ImageStore] (this
 * device, else the server, cached), decoded off the main thread into a
 * memory cache sized to an eighth of the heap. A locked note's image is
 * decrypted in memory with the session key; [forgetLocked] drops those when
 * locked notes lock. A picture that couldn't be had is tried again after a
 * while, not on every redraw.
 */
class ImageLoader(private val store: ImageStore, private val locks: LockSession, private val scope: CoroutineScope) :
    ImageSource {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Ids decrypted with the key, so [forgetLocked] knows which to drop. */
    private val decrypted = HashSet<String>()
    private val loading = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    /** Counts [forgetLocked] calls: a decryption started before the latest is thrown away. */
    private var generation = 0

    override fun bitmap(id: String, width: Int, onReady: () -> Unit): Bitmap? {
        val key = "$id@$width"
        cache.get(key)?.let { return it }
        if (key in loading) return null
        failedAt[key]?.let { if (SystemClock.elapsedRealtime() - it < RETRY_MS) return null }
        loading += key
        val started = generation
        scope.launch {
            val result = withContext(Dispatchers.Default) { decode(id, width) }
            loading -= key
            if (result == null) {
                failedAt[key] = SystemClock.elapsedRealtime()
                return@launch
            }
            if (result.second && started != generation) {
                // Locked notes locked while this decrypted: it must not stay in memory.
                result.first.recycle()
                return@launch
            }
            failedAt -= key
            if (result.second) decrypted += key
            cache.put(key, result.first)
            onReady()
        }
        return null
    }

    /** Locked notes locked: their pictures leave memory with their text. */
    fun forgetLocked() {
        generation++
        for (key in decrypted) cache.remove(key)
        decrypted.clear()
        failedAt.clear()
    }

    /** The bitmap, and whether it was encrypted; null if it can't be had or opened. */
    private suspend fun decode(id: String, width: Int): Pair<Bitmap, Boolean>? {
        val file = store.load(id) ?: return null
        val locked = file.type == LOCKED_IMAGE
        val bytes = if (locked) {
            // A copy: locking zeroes the session's own key, maybe mid-decryption.
            val k = locks.key()?.copyOf() ?: return null
            try {
                LockedNotes.decryptImage(k, file.bytes)
            } catch (_: LockedException) {
                return null
            }
        } else {
            file.bytes
        }
        return try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                if (w > width && width > 0) {
                    decoder.setTargetSize(width, (info.size.height.toLong() * width / w).toInt().coerceAtLeast(1))
                }
            }
            bitmap to locked
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val RETRY_MS = 30_000L
    }
}
