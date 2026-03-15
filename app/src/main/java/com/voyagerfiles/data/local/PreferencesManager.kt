package com.voyagerfiles.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val SORT_BY = stringPreferencesKey("sort_by")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val DEFAULT_PATH = stringPreferencesKey("default_path")
        val CUSTOM_PRIMARY = longPreferencesKey("custom_primary")
        val CUSTOM_BACKGROUND = longPreferencesKey("custom_background")
        val CUSTOM_SURFACE = longPreferencesKey("custom_surface")
    }

    val theme: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        AppTheme.fromName(prefs[Keys.THEME] ?: AppTheme.SYSTEM.name)
    }

    val showHidden: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHOW_HIDDEN] ?: false
    }

    val sortBy: Flow<SortBy> = context.dataStore.data.map { prefs ->
        try { SortBy.valueOf(prefs[Keys.SORT_BY] ?: SortBy.NAME.name) }
        catch (_: Exception) { SortBy.NAME }
    }

    val sortOrder: Flow<SortOrder> = context.dataStore.data.map { prefs ->
        try { SortOrder.valueOf(prefs[Keys.SORT_ORDER] ?: SortOrder.ASCENDING.name) }
        catch (_: Exception) { SortOrder.ASCENDING }
    }

    val viewMode: Flow<ViewMode> = context.dataStore.data.map { prefs ->
        try { ViewMode.valueOf(prefs[Keys.VIEW_MODE] ?: ViewMode.LIST.name) }
        catch (_: Exception) { ViewMode.LIST }
    }

    val defaultPath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_PATH] ?: "/storage/emulated/0"
    }

    val customPrimary: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_PRIMARY] ?: 0xFF6750A4
    }

    val customBackground: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_BACKGROUND] ?: 0xFF1C1B1F
    }

    val customSurface: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_SURFACE] ?: 0xFF2B2930
    }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setShowHidden(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_HIDDEN] = show }
    }

    suspend fun setSortBy(sortBy: SortBy) {
        context.dataStore.edit { it[Keys.SORT_BY] = sortBy.name }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.dataStore.edit { it[Keys.SORT_ORDER] = order.name }
    }

    suspend fun setViewMode(mode: ViewMode) {
        context.dataStore.edit { it[Keys.VIEW_MODE] = mode.name }
    }

    suspend fun setDefaultPath(path: String) {
        context.dataStore.edit { it[Keys.DEFAULT_PATH] = path }
    }

    suspend fun setCustomColors(primary: Long, background: Long, surface: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CUSTOM_PRIMARY] = primary
            prefs[Keys.CUSTOM_BACKGROUND] = background
            prefs[Keys.CUSTOM_SURFACE] = surface
        }
    }
}
