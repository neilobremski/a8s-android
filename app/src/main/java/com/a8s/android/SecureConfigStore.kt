package com.a8s.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest store for the entire loaded config JSON. Backed by
 * `EncryptedSharedPreferences` keyed by an Android Keystore master key
 * (AES-256-GCM). The plaintext only lives in memory inside
 * `A8sAndroid.Config`; the on-disk file at
 * `/data/data/<pkg>/shared_prefs/secure_config.xml` contains only
 * ciphertext. Defends against an attacker exploiting a slash-command
 * surface (e.g. `/cat` of our own data dir) to recover broker
 * credentials — the Android sandbox already isolates other apps.
 *
 * Stores the raw JSON exactly as it was read from the SAF source so
 * the next launch can recover the full config (device, phonebook,
 * remotes, services) even when the source file has been deleted.
 */
class SecureConfigStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveConfigJson(json: String) {
        prefs.edit().putString(KEY_CONFIG_JSON, json).apply()
    }

    fun loadConfigJson(): String? = prefs.getString(KEY_CONFIG_JSON, null)

    fun clear() {
        prefs.edit().remove(KEY_CONFIG_JSON).apply()
    }

    companion object {
        private const val FILE_NAME = "secure_config"
        private const val KEY_CONFIG_JSON = "config_json"
    }
}
