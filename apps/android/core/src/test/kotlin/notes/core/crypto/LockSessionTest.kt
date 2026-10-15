package notes.core.crypto

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import notes.core.db.Database
import notes.core.db.LockKeyStore

class LockSessionTest {
    private val dir: File = Files.createTempDirectory("notes-locks").toFile()
    private val db = Database(File(dir, "notes.db").path)
    private val keys = LockKeyStore(db)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val session = LockSession(keys, { null }, scope, iterations = 10_000)

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `a device-kept key unlocks only while the password entry it was kept against stands`() = runBlocking {
        val created = Keyring.create("lock pass 123", iterations = 10_000)
        keys.replace(listOf(created.entry))
        session.refresh()
        val bound = session.passwordEntry()!!

        assertTrue(session.unlockWithKey(created.key, bound))
        assertEquals(LockSession.State.Unlocked, session.state.value)
        session.lock()
        assertNull(session.key())

        // Reset elsewhere and a new password made: the kept key is stale.
        keys.replace(listOf(Keyring.create("another one", iterations = 10_000).entry))
        assertFalse(session.unlockWithKey(created.key, bound))
        assertEquals(LockSession.State.Locked, session.state.value)
        assertFalse(session.unlockWithKey(ByteArray(5), session.passwordEntry()!!))
    }
}
