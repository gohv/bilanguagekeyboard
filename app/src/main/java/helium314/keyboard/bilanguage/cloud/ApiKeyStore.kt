// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import helium314.keyboard.bilanguage.BiLog
import helium314.keyboard.latin.utils.prefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The cloud provider API key, encrypted with an AES-GCM key held in the Android Keystore.
 *
 * Only the ciphertext reaches SharedPreferences. The Keystore key never leaves secure hardware and is
 * not included in backups, so a restored backup carries an undecryptable blob: [get] then returns null
 * and the user re-enters the key, which is the right outcome for a credential.
 *
 * The key itself must never be logged, not even redacted.
 */
object ApiKeyStore {
    private const val PREF_CIPHERTEXT = "blk_cloud_api_key"
    private const val KEY_ALIAS = "blk_cloud_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun get(context: Context): String? {
        val stored = context.prefs().getString(PREF_CIPHERTEXT, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.onFailure {
            BiLog.w("stored cloud API key could not be decrypted (${it.javaClass.simpleName}); treating as absent")
        }.getOrNull()
    }

    fun has(context: Context): Boolean = get(context) != null

    fun set(context: Context, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        context.prefs().edit { putString(PREF_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
        BiLog.i("cloud API key saved")
    }

    fun clear(context: Context) {
        context.prefs().edit { remove(PREF_CIPHERTEXT) }
        BiLog.i("cloud API key removed")
    }

    /** Last four characters, for showing which key is saved without showing the key. */
    fun hint(context: Context): String? = get(context)?.takeLast(4)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
