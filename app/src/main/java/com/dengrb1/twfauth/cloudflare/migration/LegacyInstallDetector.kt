package com.dengrb1.twfauth.cloudflare.migration

object LegacyInstallDetector {
    fun isLegacyInstall(
        firstInstallTime: Long,
        lastUpdateTime: Long,
        hasSavedSession: Boolean,
        hasLegacyLocale: Boolean,
    ): Boolean = hasSavedSession || hasLegacyLocale || lastUpdateTime > firstInstallTime
}

object LegacyLanguagePreferenceMigration {
    fun resolve(current: String?, legacy: String?): String = when {
        current in setOf("system", "en", "zh") -> current!!
        legacy == "en" || legacy == "zh" -> legacy
        else -> "system"
    }
}

