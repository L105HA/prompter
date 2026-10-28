package com.prompter.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val HOST = stringPreferencesKey("host")
        val PORT = stringPreferencesKey("port")
        val TOKEN = stringPreferencesKey("token")
        val LANG = stringPreferencesKey("lang")
        val THEME = stringPreferencesKey("theme") // system, light, dark
        val PREVIEW_WRAP = booleanPreferencesKey("preview_wrap") // wrap pane text instead of panning
        val PREVIEW_FONT = intPreferencesKey("preview_font") // preview font size, sp
        val PREVIEW_COLOR = booleanPreferencesKey("preview_color") // render ANSI colors in preview
        val PREVIEW_COLS = intPreferencesKey("preview_cols") // crop pane width (0 = full, hides sidebar)
        val PREVIEW_TRIM = booleanPreferencesKey("preview_trim") // drop trailing input-box/status block
        val PREVIEW_WAKE = booleanPreferencesKey("preview_wake") // keep screen on while preview visible
        val MACROS = stringPreferencesKey("macros") // user macros, JSON (see Macros)
    }

    val host: Flow<String> = context.dataStore.data.map { it[HOST] ?: "" }
    val port: Flow<String> = context.dataStore.data.map { it[PORT] ?: "8788" }
    val token: Flow<String> = context.dataStore.data.map { it[TOKEN] ?: "" }
    val lang: Flow<String> = context.dataStore.data.map { it[LANG] ?: "system" }
    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "system" }
    val previewWrap: Flow<Boolean> = context.dataStore.data.map { it[PREVIEW_WRAP] ?: true }
    val previewFont: Flow<Int> = context.dataStore.data.map { it[PREVIEW_FONT] ?: 10 }
    val previewColor: Flow<Boolean> = context.dataStore.data.map { it[PREVIEW_COLOR] ?: true }
    val previewCols: Flow<Int> = context.dataStore.data.map { it[PREVIEW_COLS] ?: 150 }
    val previewTrim: Flow<Boolean> = context.dataStore.data.map { it[PREVIEW_TRIM] ?: true }
    val previewWake: Flow<Boolean> = context.dataStore.data.map { it[PREVIEW_WAKE] ?: true }
    val macros: Flow<List<Macro>> = context.dataStore.data.map { Macros.fromJson(it[MACROS]) }

    suspend fun setHost(v: String) { context.dataStore.edit { it[HOST] = v } }
    suspend fun setPort(v: String) { context.dataStore.edit { it[PORT] = v } }
    suspend fun setToken(v: String) { context.dataStore.edit { it[TOKEN] = v } }
    suspend fun setLang(v: String) { context.dataStore.edit { it[LANG] = v } }
    suspend fun setTheme(v: String) { context.dataStore.edit { it[THEME] = v } }
    suspend fun setPreviewWrap(v: Boolean) { context.dataStore.edit { it[PREVIEW_WRAP] = v } }
    suspend fun setPreviewFont(v: Int) { context.dataStore.edit { it[PREVIEW_FONT] = v.coerceIn(6, 24) } }
    suspend fun setPreviewColor(v: Boolean) { context.dataStore.edit { it[PREVIEW_COLOR] = v } }
    suspend fun setPreviewCols(v: Int) { context.dataStore.edit { it[PREVIEW_COLS] = v.coerceIn(0, 220) } }
    suspend fun setPreviewTrim(v: Boolean) { context.dataStore.edit { it[PREVIEW_TRIM] = v } }
    suspend fun setPreviewWake(v: Boolean) { context.dataStore.edit { it[PREVIEW_WAKE] = v } }
    suspend fun setMacros(v: List<Macro>) { context.dataStore.edit { it[MACROS] = Macros.toJson(v) } }
}
