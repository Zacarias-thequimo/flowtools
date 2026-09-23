package com.flowtools.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.*

private val Context.prefs by preferencesDataStore("flowtools")

private val SERVER_URL = stringPreferencesKey("server_url")
private val PC_ID = stringPreferencesKey("pc_id")
private val PC_NAME = stringPreferencesKey("pc_name")
private val DEVICE_NAME = stringPreferencesKey("device_name")
private val HAPTICS = booleanPreferencesKey("haptics")
private val RETAIN_TEXT = booleanPreferencesKey("retain_text")

/**
 * SessionStore: non-sensitive prefs in DataStore, session token in
 * EncryptedSharedPreferences (Android Keystore). Sensitive text history
 * is never persisted unless RETAIN_TEXT is explicitly enabled.
 */
class SessionStore(private val ctx: Context) {
    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "flowtools_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    val serverUrl: Flow<String?> = ctx.prefs.data.map { it[SERVER_URL] }
    val pcId: Flow<String?> = ctx.prefs.data.map { it[PC_ID] }
    val pcName: Flow<String?> = ctx.prefs.data.map { it[PC_NAME] ?: "PC de trabalho" }
    val haptics: Flow<Boolean> = ctx.prefs.data.map { it[HAPTICS] ?: true }
    val retainText: Flow<Boolean> = ctx.prefs.data.map { it[RETAIN_TEXT] ?: false }

    fun loadToken() {
        _token.value = secure.getString("session_token", null)
    }

    suspend fun saveSession(serverUrl: String, pcId: String, pcName: String, token: String) {
        secure.edit().putString("session_token", token).apply()
        _token.value = token
        ctx.prefs.edit { prefs ->
            prefs[SERVER_URL] = serverUrl
            prefs[PC_ID] = pcId
            prefs[PC_NAME] = pcName
        }
    }

    suspend fun setHaptics(enabled: Boolean) {
        ctx.prefs.edit { it[HAPTICS] = enabled }
    }

    suspend fun setRetainText(enabled: Boolean) {
        ctx.prefs.edit { it[RETAIN_TEXT] = enabled }
    }

    suspend fun clearSession() {
        secure.edit().remove("session_token").apply()
        _token.value = null
        ctx.prefs.edit { prefs ->
            prefs.remove(PC_ID)
            prefs.remove(PC_NAME)
        }
    }
}
