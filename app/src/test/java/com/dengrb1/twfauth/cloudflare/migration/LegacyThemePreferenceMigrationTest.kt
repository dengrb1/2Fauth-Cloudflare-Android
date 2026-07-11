package com.dengrb1.twfauth.cloudflare.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyThemePreferenceMigrationTest {
    @Test
    fun `existing install without theme keeps the legacy dark appearance`() {
        val result = LegacyThemePreferenceMigration.resolve(
            savedTheme = null,
            migrationComplete = false,
            legacyInstallDetected = true,
        )

        assertEquals(LegacyThemePreferenceMigration.DARK, result.theme)
        assertTrue(result.shouldPersistTheme)
        assertTrue(result.shouldMarkMigrationComplete)
    }

    @Test
    fun `fresh install defaults to system theme`() {
        val result = LegacyThemePreferenceMigration.resolve(
            savedTheme = null,
            migrationComplete = false,
            legacyInstallDetected = false,
        )

        assertEquals(LegacyThemePreferenceMigration.SYSTEM, result.theme)
        assertTrue(result.shouldPersistTheme)
        assertTrue(result.shouldMarkMigrationComplete)
    }

    @Test
    fun `an explicit user choice is preserved`() {
        val result = LegacyThemePreferenceMigration.resolve(
            savedTheme = LegacyThemePreferenceMigration.LIGHT,
            migrationComplete = false,
            legacyInstallDetected = true,
        )

        assertEquals(LegacyThemePreferenceMigration.LIGHT, result.theme)
        assertFalse(result.shouldPersistTheme)
        assertTrue(result.shouldMarkMigrationComplete)
    }

    @Test
    fun `saved values are normalized before persistence`() {
        val result = LegacyThemePreferenceMigration.resolve(
            savedTheme = " DARK ",
            migrationComplete = true,
            legacyInstallDetected = false,
        )

        assertEquals(LegacyThemePreferenceMigration.DARK, result.theme)
        assertTrue(result.shouldPersistTheme)
        assertFalse(result.shouldMarkMigrationComplete)
    }

    @Test
    fun `completed migration does not restore dark for a missing value`() {
        val result = LegacyThemePreferenceMigration.resolve(
            savedTheme = "unsupported",
            migrationComplete = true,
            legacyInstallDetected = true,
        )

        assertEquals(LegacyThemePreferenceMigration.SYSTEM, result.theme)
        assertTrue(result.shouldPersistTheme)
        assertFalse(result.shouldMarkMigrationComplete)
    }
}
