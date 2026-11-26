package io.nekohasekai.sagernet.utils

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.nekohasekai.sagernet.database.DataStore

object AppLocale {

    // Empty tag means "follow system".
    fun localeList(tag: String?): LocaleListCompat {
        return if (tag.isNullOrEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
    }

    // Applies the in-app language. AppCompat handles persistence (API 33+ via the
    // framework, below via SharedPreferences) and recreates active activities.
    fun apply(tag: String? = DataStore.appLanguage) {
        AppCompatDelegate.setApplicationLocales(localeList(tag))
    }

    // Japanese, Korean and Traditional Chinese were dropped; a stored choice of one follows the system again.
    private val RETIRED = setOf("ja", "ko", "zh-TW")

    fun applyStored() {
        if (DataStore.appLanguage in RETIRED) DataStore.appLanguage = ""
        apply()
    }

}
