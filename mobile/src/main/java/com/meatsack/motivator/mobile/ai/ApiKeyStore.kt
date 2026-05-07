package com.meatsack.motivator.mobile.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Device-backed encrypted store for the user's Anthropic API key.
 * Key bytes live in the Android Keystore; ciphertext in prefs.
 */
class ApiKeyStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "meatsack_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(apiKey: String) {
        prefs.edit().putString(KEY, apiKey.trim()).apply()
    }

    fun read(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun hasKey(): Boolean = read() != null

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "anthropic_api_key"
    }
}
