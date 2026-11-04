package dev.anct.runtime

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("anct-settings")

class SettingsStore(
    private val context: Context,
) {
    private val nativeExperimentalEnabled = booleanPreferencesKey("native_experimental_enabled")
    private val allowUnverifiedImportsKey = booleanPreferencesKey("allow_unverified_imports")

    val experimentalNativeEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[nativeExperimentalEnabled] ?: false }

    val allowUnverifiedImports: Flow<Boolean> =
        context.settingsDataStore.data.map { it[allowUnverifiedImportsKey] ?: false }

    suspend fun setExperimentalNativeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[nativeExperimentalEnabled] = enabled }
    }

    suspend fun setAllowUnverifiedImports(enabled: Boolean) {
        context.settingsDataStore.edit { it[allowUnverifiedImportsKey] = enabled }
    }
}

