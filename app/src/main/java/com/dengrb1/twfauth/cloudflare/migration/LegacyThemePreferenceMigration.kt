package com.dengrb1.twfauth.cloudflare.migration

/**
 * Resolves the first persisted theme choice after upgrading from the pre-Compose app.
 *
 * The legacy UI was always dark and did not persist a theme value. An existing install
 * therefore migrates to [DARK], while a fresh install defaults to [SYSTEM]. Once the
 * migration marker has been written, a missing or invalid value safely falls back to
 * [SYSTEM] instead of repeatedly treating the install as legacy.
 */
object LegacyThemePreferenceMigration {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    data class Result(
        val theme: String,
        val shouldPersistTheme: Boolean,
        val shouldMarkMigrationComplete: Boolean,
    )

    fun resolve(
        savedTheme: String?,
        migrationComplete: Boolean,
        legacyInstallDetected: Boolean,
    ): Result {
        val normalizedTheme = savedTheme
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in setOf(SYSTEM, LIGHT, DARK) }

        val resolvedTheme = when {
            normalizedTheme != null -> normalizedTheme
            migrationComplete -> SYSTEM
            legacyInstallDetected -> DARK
            else -> SYSTEM
        }

        return Result(
            theme = resolvedTheme,
            shouldPersistTheme = savedTheme != resolvedTheme,
            shouldMarkMigrationComplete = !migrationComplete,
        )
    }
}
