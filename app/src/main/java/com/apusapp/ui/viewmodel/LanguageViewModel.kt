package com.apusapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.apusapp.utils.AppLanguage
import com.apusapp.utils.AppStrings
import com.apusapp.utils.LanguageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LanguageViewModel(application: Application) : AndroidViewModel(application) {

    private val _currentLanguage = MutableStateFlow(LanguageManager.getSavedLanguage(application))
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    fun setLanguage(language: AppLanguage) {
        if (_currentLanguage.value != language) {
            _currentLanguage.value = language
            LanguageManager.saveLanguage(getApplication(), language)
        }
    }

    fun getString(key: String): String {
        return AppStrings.get(key, _currentLanguage.value)
    }
}
