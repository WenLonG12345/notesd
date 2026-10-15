package notes.app.images

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picture ready to store: re-encoded bytes, their type and pixel size. */
class Prepared(val bytes: ByteArray, val type: String, val width: Int, val height: Int)

/** Couldn't make a note picture from what was picked; the message is for the user. */
class ImageException(message: String) : Exception(message)

const val MAX_SIDE = 2048
const val MAX_BYTES = 10 shl 20
private const val QUALITY = 85

/**
 * Downscaling before upload (architecture §7.6): the long side is capped at
 * [MAX_SIDE], orientation applied (ImageDecoder does), and the picture
 * re-encoded as JPEG, or PNG when it has transparency, which drops EXIF
 * metadata such as location. GIFs are kept as they are, for animation.
 */
suspend fun prepareImage(resolver: ContentResolver, uri: Uri): Prepared = withContext(Dispatchers.IO) {
    var mime: String? = null
    var size = 0 to 0
    val bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            mime = info.mimeType
            size = info.size.width to info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = size
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
    } catch (_: IOException) {
        // DecodeException included: not a picture this phone can read.
        throw ImageException("That picture couldn’t be read.")
    }
    if (mime == "image/gif") {
        bitmap.recycle()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImageException("That picture couldn’t be read.")
        if (bytes.size > MAX_BYTES) throw ImageException("GIFs can be at most 10 MB.")
        return@withContext Prepared(bytes, "image/gif", size.first, size.second)
    }
    val png = bitmap.hasAlpha()
    val out = ByteArrayOutputStream()
    bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, QUALITY, out)
    val prepared = Prepared(out.toByteArray(), if (png) "image/png" else "image/jpeg", bitmap.width, bitmap.height)
    bitmap.recycle()
    if (prepared.bytes.size > MAX_BYTES) throw ImageException("That picture is too large, even resized.")
    prepared
}
