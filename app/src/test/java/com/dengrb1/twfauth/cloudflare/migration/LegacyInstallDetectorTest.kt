package com.dengrb1.twfauth.cloudflare.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyInstallDetectorTest {
    @Test fun `logged out updated install without locale is still legacy`() {
        assertTrue(LegacyInstallDetector.isLegacyInstall(100, 200, hasSavedSession = false, hasLegacyLocale = false))
    }

    @Test fun `fresh install without state is not legacy`() {
        assertFalse(LegacyInstallDetector.isLegacyInstall(100, 100, hasSavedSession = false, hasLegacyLocale = false))
    }

    @Test fun `legacy language is reflected in new settings preference`() {
        assertEquals("zh", LegacyLanguagePreferenceMigration.resolve(null, "zh"))
        assertEquals("en", LegacyLanguagePreferenceMigration.resolve(null, "en"))
        assertEquals("system", LegacyLanguagePreferenceMigration.resolve(null, null))
        assertEquals("zh", LegacyLanguagePreferenceMigration.resolve("zh", "en"))
    }
}

