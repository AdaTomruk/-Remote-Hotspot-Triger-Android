package com.blehotspot.trigger

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Utility class for managing encrypted SharedPreferences.
 * Provides a singleton instance to ensure consistent encryption configuration
 * across the application.
 */
object SecurePreferences {
    private const val TAG = "SecurePreferences"
    private const val PREFS_NAME = "HotspotPreferences"
    const val KEY_HOTSPOT_PASSWORD = "hotspot_password"
    
    @Volatile
    private var encryptedPrefs: SharedPreferences? = null
    
    /**
     * Gets or creates an EncryptedSharedPreferences instance.
     * Uses double-checked locking for thread-safe lazy initialization.
     * Uses AES256_GCM master key with AES256_SIV key encryption and AES256_GCM value encryption.
     * 
     * @param context Application context
     * @return Encrypted SharedPreferences instance
     * @throws Exception if encryption setup fails
     */
    fun getEncryptedPreferences(context: Context): SharedPreferences {
        return encryptedPrefs ?: synchronized(this) {
            encryptedPrefs ?: createEncryptedPreferences(context).also { encryptedPrefs = it }
        }
    }
    
    private fun createEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * Gets the saved hotspot password.
     * 
     * @param context Application context
     * @return Password string or empty if not set or error occurs
     */
    fun getPassword(context: Context): String {
        return try {
            val prefs = getEncryptedPreferences(context)
            prefs.getString(KEY_HOTSPOT_PASSWORD, "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read password from encrypted preferences", e)
            ""
        }
    }
    
    /**
     * Saves the hotspot password securely.
     * 
     * @param context Application context
     * @param password Password to save
     * @return true if saved successfully, false otherwise
     */
    fun savePassword(context: Context, password: String): Boolean {
        return try {
            val prefs = getEncryptedPreferences(context)
            // Use commit() instead of apply() to ensure synchronous write for critical data
            prefs.edit().putString(KEY_HOTSPOT_PASSWORD, password).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save password to encrypted preferences", e)
            false
        }
    }
}
