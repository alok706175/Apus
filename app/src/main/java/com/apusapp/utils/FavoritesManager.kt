package com.apusapp.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Starred / Favorite PDF files across the app.
 * Provides reactive StateFlow updates so Home Screen instantly updates when a file is favorited.
 */
object FavoritesManager {

    private const val PREFS_NAME = "apus_favorites_prefs"
    private const val KEY_FAVORITE_PATHS = "favorite_paths"

    private val _favoritesFlow = MutableStateFlow<Set<String>>(emptySet())
    val favoritesFlow: StateFlow<Set<String>> = _favoritesFlow.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        val prefs = getPrefs(context)
        val saved = prefs.getStringSet(KEY_FAVORITE_PATHS, emptySet()) ?: emptySet()
        _favoritesFlow.value = saved.toSet()
    }

    fun isFavorite(context: Context, path: String): Boolean {
        if (_favoritesFlow.value.isEmpty()) {
            init(context)
        }
        return _favoritesFlow.value.contains(path)
    }

    fun toggleFavorite(context: Context, path: String): Boolean {
        val prefs = getPrefs(context)
        val current = (_favoritesFlow.value).toMutableSet()
        val isNowFav = if (current.contains(path)) {
            current.remove(path)
            false
        } else {
            current.add(path)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITE_PATHS, current).apply()
        _favoritesFlow.value = current
        return isNowFav
    }

    fun getFavorites(context: Context): Set<String> {
        if (_favoritesFlow.value.isEmpty()) {
            init(context)
        }
        return _favoritesFlow.value
    }
}

