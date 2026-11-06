package io.github.northster.sekaitracker.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Gemini API key, encrypted with a key that never leaves the Android keystore. Not part of backups. */
object SecretStore {
    private const val ALIAS = "sekai_tracker_secrets"
    private const val PREF = "gemini_key"
    private lateinit var context: Context

    fun init(context: Context) { this.context = context.applicationContext }

    private fun prefs() = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    var geminiKey: String?
        get() = runCatching {
            val stored = prefs().getString(PREF, null) ?: return null
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
        set(value) {
            if (value.isNullOrBlank()) { prefs().edit { remove(PREF) }; return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            prefs().edit { putString(PREF, Base64.encodeToString(out, Base64.NO_WRAP)) }
        }

    val hasGeminiKey get() = !geminiKey.isNullOrBlank()
}
