package com.dengrb1.twfauth.cloudflare

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANG = "app_language"
    const val LANG_EN = "en"
    const val LANG_ZH = "zh"
    const val LANG_SYSTEM = "system"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, LANG_SYSTEM) ?: LANG_SYSTEM

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANG, language).apply()
        val tags = when (language) {
            LANG_ZH -> "zh-CN"
            LANG_EN -> "en"
            else -> ""
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
    }

    fun wrapContext(context: Context, language: String): Context {
        if (language == LANG_SYSTEM) return context
        val locale = when (language) {
            LANG_ZH -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.ENGLISH
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun applySaved(context: Context): Context {
        return wrapContext(context, getSavedLanguage(context))
    }

    fun toggle(context: Context): String {
        val current = getSavedLanguage(context)
        val next = if (current == LANG_ZH) LANG_EN else LANG_ZH
        setLanguage(context, next)
        return next
    }

    fun displayNameFor(language: String): String {
        return when (language) {
            LANG_ZH -> "中文"
            LANG_SYSTEM -> "System"
            else -> "English"
        }
    }
}
