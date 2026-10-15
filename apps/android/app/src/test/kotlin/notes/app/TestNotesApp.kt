package notes.app

import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import notes.app.account.Sealer
import notes.app.locks.BiometricResult
import notes.app.locks.Biometrics

/** The app with in-memory keys instead of the Android Keystore and biometric prompt, which Robolectric doesn't provide. */
class TestNotesApp : NotesApp() {
    override fun createGraph() = AppGraph(this, TestSealer(), FakeBiometrics)

    override fun startSync() = Unit
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

/** A fingerprint that always matches, unless a test says otherwise. Shared, so tests can reach it. */
object FakeBiometrics : Biometrics {
    var sealer = TestSealer()
    var cancelNext = false
    var invalidated = false

    override fun available() = true

    override suspend fun seal(activity: FragmentActivity, data: ByteArray): BiometricResult = when {
        cancelNext -> BiometricResult.Cancelled.also { cancelNext = false }

        else -> {
            invalidated = false
            BiometricResult.Ok(sealer.seal(data))
        }
    }

    override suspend fun open(activity: FragmentActivity, sealed: ByteArray): BiometricResult = when {
        cancelNext -> BiometricResult.Cancelled.also { cancelNext = false }
        invalidated -> BiometricResult.Invalidated
        else -> BiometricResult.Ok(sealer.open(sealed))
    }

    // As the Keystore deletes the key: what it sealed is invalidated until the next seal.
    override fun forget() {
        sealer = TestSealer()
        invalidated = true
    }
}
