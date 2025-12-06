package com.blehotspot.trigger

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages hotspot credentials storage using encrypted SharedPreferences.
 * Credentials are stored securely and persist across app restarts.
 */
class HotspotCredentialsManager(context: Context) {
    
    companion object {
        private const val TAG = "HotspotCredentials"
        private const val PREFS_NAME = "hotspot_credentials"
        private const val FALLBACK_PREFS_NAME = "hotspot_credentials_fallback"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSWORD = "password"
    }
    
    private val sharedPreferences: SharedPreferences
    
    init {
        sharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fall back to regular SharedPreferences if encryption fails
            Log.w(TAG, "Failed to create encrypted preferences, using fallback", e)
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    
    /**
     * Save hotspot credentials securely.
     * @param ssid The hotspot SSID
     * @param password The hotspot password (can be empty for open networks)
     */
    fun saveCredentials(ssid: String, password: String) {
        sharedPreferences.edit()
            .putString(KEY_SSID, ssid)
            .putString(KEY_PASSWORD, password)
            .apply()
    }
    
    /**
     * Get the saved SSID.
     * @return The saved SSID or empty string if not set
     */
    fun getSsid(): String {
        return sharedPreferences.getString(KEY_SSID, "") ?: ""
    }
    
    /**
     * Get the saved password.
     * @return The saved password or empty string if not set
     */
    fun getPassword(): String {
        return sharedPreferences.getString(KEY_PASSWORD, "") ?: ""
    }
    
    /**
     * Check if credentials have been saved.
     * @return true if SSID is saved, false otherwise
     */
    fun hasCredentials(): Boolean {
        return getSsid().isNotEmpty()
    }
    
    /**
     * Get credentials as a Pair.
     * @return Pair of (SSID, Password)
     */
    fun getCredentials(): Pair<String, String> {
        return Pair(getSsid(), getPassword())
    }
    
    /**
     * Clear all saved credentials.
     */
    fun clearCredentials() {
        sharedPreferences.edit()
            .remove(KEY_SSID)
            .remove(KEY_PASSWORD)
            .apply()
    }
}
