package notes.core.images

import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.core.api.ApiResult
import notes.core.api.LOCKED_IMAGE
import notes.core.crypto.LockedNotes
import notes.core.sync.SyncOutcome
import notes.core.sync.TestClient
import notes.core.sync.TestServer

/** Images (§7.6) against a real notesd: uploaded before their notes, fetched on first view, moved on lock changes. */
class ImagesTest {
    private val server = TestServer()
    private val clients = ArrayList<TestClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
    }

    private suspend fun client() = TestClient(server).also { clients += it }.signIn()

    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
    )

    @Test
    fun `references are parsed and written the same way as on the web`() {
        val id = "ab".repeat(32)
        val ref = ImageRef(id, 1600, 1200)
        assertEquals("![](notesd:image/$id?w=1600&h=1200)", ref.markdown())
        assertEquals("![a \\*cat\\* \\[1\\]](notesd:image/$id?w=1600&h=1200)", ref.markdown("a *cat* [1]"))
        assertEquals(ref, ImageRef.parse(ref.markdown("a *cat*")))
        assertEquals("a *cat*", ImageRef.alt(ref.markdown("a *cat*")))
        for (other in listOf(
            "![a](cat.png)",
            "![a](notesd:image/$id)",
            "![a](notesd:image/${id.uppercase()}?w=1&h=1)",
            "![a](notesd:image/$id?w=0&h=1)",
            "`![a](notesd:image/$id?w=1&h=1)`"
        )) {
            assertNull(ImageRef.parse(other), other)
        }
    }

    @Test
    fun `an image added offline uploads before its note and opens on another device`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.offline = true
        val id = a.images.add(png, "image/png")
        a.save("n", "Trip", ImageRef(id, 1, 1).markdown() + "\n")
        assertEquals(SyncOutcome.Offline, a.sync())
        assertEquals(1, a.images.pending().size)
        // Still on this device while it waits.
        assertContentEquals(png, a.images.load(id)?.bytes)

        a.offline = false
        var uploadedFirst = false
        a.beforeNext({ it.url.encodedPath.startsWith("/api/v1/notes") }) {
            uploadedFirst = runBlocking { b.api.getImage(id) is ApiResult.Ok }
        }
        assertEquals(SyncOutcome.Done, a.sync())
        assertTrue(uploadedFirst, "the image reached the server before the note")
        assertEquals(emptyList(), a.images.pending())
        assertTrue(a.images.has(id), "kept in the cache after upload")

        assertEquals(SyncOutcome.Done, b.sync())
        assertFalse(b.images.has(id))
        val fetched = assertNotNull(b.images.load(id))
        assertEquals("image/png", fetched.type)
        assertContentEquals(png, fetched.bytes)
        b.offline = true
        assertContentEquals(png, b.images.load(id)?.bytes, "cached for offline")
    }

    @Test
    fun `a lost upload response is retried harmlessly`() = runBlocking<Unit> {
        val a = client()
        val id = a.images.add(png, "image/png")
        a.loseNextResponse { it.url.encodedPath.startsWith("/api/v1/images") }
        assertEquals(SyncOutcome.Offline, a.sync())
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(emptyList(), a.images.pending())
        assertNotNull(client().images.load(id))
    }

    @Test
    fun `pending images survive a restart`() = runBlocking<Unit> {
        val a = client()
        a.offline = true
        val id = a.images.add(png, "image/png")
        a.restart()
        assertEquals(listOf(PendingImage(id, "image/png")), a.images.pending())
        a.offline = false
        assertEquals(SyncOutcome.Done, a.sync())
        assertNotNull(client().images.load(id))
    }

    @Test
    fun `resealing encrypts every image for a lock and decrypts them for its removal`() = runBlocking<Unit> {
        val a = client()
        val key = LockedNotes.newKey()
        val plain = a.images.add(png, "image/png")
        val body = "Before\n\n${ImageRef(plain, 1, 1).markdown("dot")}\n\n${ImageRef(plain, 1, 1).markdown()}\n"

        val locked = assertNotNull(a.images.reseal(body, locked = true, key))
        val sealedId = assertNotNull(Regex("notesd:image/([0-9a-f]{64})").find(locked)).groupValues[1]
        assertNotEquals(plain, sealedId)
        assertEquals(body.replace(plain, sealedId), locked)
        val sealed = assertNotNull(a.images.load(sealedId))
        assertEquals(LOCKED_IMAGE, sealed.type)
        assertContentEquals(png, LockedNotes.decryptImage(key, sealed.bytes))
        // Already encrypted: nothing to do.
        assertEquals(locked, a.images.reseal(locked, locked = true, key))

        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(LOCKED_IMAGE, client().images.load(sealedId)?.type)

        // Removing the lock brings back the same plain bytes, so the same id.
        assertEquals(body, a.images.reseal(locked, locked = false, key))
        // With another key the image won't open, and nothing changes.
        assertNull(a.images.reseal(locked, locked = false, LockedNotes.newKey()))
    }

    @Test
    fun `a lock change waits for images this device doesn't have`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        val id = a.images.add(png, "image/png")
        assertEquals(SyncOutcome.Done, a.sync())
        b.offline = true
        val body = ImageRef(id, 1, 1).markdown() + "\n"
        assertNull(b.images.reseal(body, locked = true, LockedNotes.newKey()))
        assertEquals(emptyList(), b.images.pending())
        b.offline = false
        assertNotNull(b.images.reseal(body, locked = true, LockedNotes.newKey()))
    }

    @Test
    fun `sniffing knows the image types the server accepts`() {
        assertEquals("image/png", ImageStore.sniff(png))
        assertEquals("image/jpeg", ImageStore.sniff(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)))
        assertEquals("image/gif", ImageStore.sniff("GIF89a".toByteArray()))
        assertEquals("image/webp", ImageStore.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertNull(ImageStore.sniff("<svg".toByteArray()))
    }
}
