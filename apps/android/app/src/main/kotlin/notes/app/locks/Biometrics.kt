package notes.app.locks

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What asking for the fingerprint came to. */
sealed interface BiometricResult {
    data class Ok(val data: ByteArray) : BiometricResult

    /** Dismissed, or "Use password" chosen. */
    data object Cancelled : BiometricResult

    /** The key is gone: fingerprints were added or removed, or the screen lock was turned off. */
    data object Invalidated : BiometricResult

    data object Failed : BiometricResult
}

/** Seals data behind the device's strong biometric (fingerprint, or face where it's strong). Tests fake it. */
interface Biometrics {
    fun available(): Boolean

    suspend fun seal(activity: FragmentActivity, data: ByteArray): BiometricResult

    suspend fun open(activity: FragmentActivity, sealed: ByteArray): BiometricResult

    fun forget()
}

/**
 * AES-GCM with a Keystore key usable only right after a strong biometric
 * check, one check per use, and destroyed when the enrolled biometrics change.
 * Output is the 12-byte IV, then ciphertext and tag.
 */
class KeystoreBiometrics(private val context: Context) : Biometrics {
    override fun available() =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun seal(activity: FragmentActivity, data: ByteArray): BiometricResult {
        val cipher = try {
            forget()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        } catch (_: GeneralSecurityException) {
            return BiometricResult.Failed
        }
        val ready = prompt(
            activity,
            cipher,
            "Unlock with fingerprint?",
            subtitle = "Open locked notes with your fingerprint instead of the lock password.",
            negative = "Not now"
        ) ?: return BiometricResult.Cancelled
        return try {
            BiometricResult.Ok(ready.iv + ready.doFinal(data))
        } catch (_: GeneralSecurityException) {
            BiometricResult.Failed
        }
    }

    override suspend fun open(activity: FragmentActivity, sealed: ByteArray): BiometricResult {
        // Too short for an IV and a tag: damaged, and never openable.
        if (sealed.size < IV_BYTES + TAG_BITS / 8) return BiometricResult.Invalidated
        val key = existingKey() ?: return BiometricResult.Invalidated
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            return BiometricResult.Invalidated
        } catch (_: GeneralSecurityException) {
            return BiometricResult.Failed
        }
        val ready = prompt(activity, cipher, "Unlock locked notes") ?: return BiometricResult.Cancelled
        return try {
            BiometricResult.Ok(ready.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
        } catch (_: GeneralSecurityException) {
            BiometricResult.Failed
        }
    }

    override fun forget() {
        store().deleteEntry(ALIAS)
    }

    private fun store() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey() = store().getKey(ALIAS, null) as? SecretKey

    private fun newKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // Before Android 11 an authentication-bound key with no timeout already means a biometric per use.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    /** The cipher, unlocked by a biometric; null when dismissed or failed for good. */
    private suspend fun prompt(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String? = null,
        negative: String = "Use password"
    ): Cipher? = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "notes.locks.biometric"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
