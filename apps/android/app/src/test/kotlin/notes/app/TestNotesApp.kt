package notes.app

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import notes.app.account.Sealer

/** The app with an in-memory key instead of the Android Keystore, which Robolectric doesn't provide. */
class TestNotesApp : NotesApp() {
    override fun createGraph() = AppGraph(this, TestSealer())
}

class TestSealer : Sealer {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}
