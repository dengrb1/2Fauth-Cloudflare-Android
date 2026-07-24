package com.dengrb1.twfauth.cloudflare.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHasClickAction
import com.dengrb1.twfauth.cloudflare.ui.model.*
import com.dengrb1.twfauth.cloudflare.LocaleHelper
import com.dengrb1.twfauth.cloudflare.R
import com.dengrb1.twfauth.cloudflare.ui.theme.TwoFactorTheme
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class TwoFactorAppTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginAndFourPrimaryDestinationsAreReachable() {
        compose.setContent { TwoFactorApp(TestGateway()) }
        compose.onNodeWithTag(UiTestTags.USERNAME).performTextInput("alice")
        compose.onNodeWithTag(UiTestTags.PASSWORD).performTextInput("Password-123!")
        compose.onNodeWithTag(UiTestTags.LOGIN).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTestTags.BOTTOM_NAV).fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithTag(UiTestTags.destination("codes")).assertIsDisplayed()
        compose.onNodeWithTag(UiTestTags.destinationTab("groups")).performClick()
        compose.onNodeWithTag(UiTestTags.destination("groups")).assertIsDisplayed()
        compose.onNodeWithTag(UiTestTags.destinationTab("backups")).performClick()
        compose.onNodeWithTag(UiTestTags.destination("backups")).assertIsDisplayed()
        compose.onNodeWithTag(UiTestTags.destinationTab("settings")).performClick()
        compose.onNodeWithTag(UiTestTags.destination("settings")).assertIsDisplayed()
    }

    @Test fun addEntryOpensAccessibleEditor() {
        val gateway = TestGateway(saved = true)
        compose.setContent { TwoFactorApp(gateway, PlatformActions(requestDeviceUnlock = { true })) }
        compose.onNodeWithTag(UiTestTags.UNLOCK).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTestTags.ADD_ENTRY).fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithTag(UiTestTags.ADD_ENTRY).performClick()
        compose.onNodeWithTag(UiTestTags.ENTRY_FORM).assertIsDisplayed()
        compose.onNodeWithTag(UiTestTags.ENTRY_LABEL).performTextInput("Mail")
        compose.onNodeWithTag(UiTestTags.ENTRY_SECRET).performTextInput("JBSWY3DPEHPK3PXP")
        compose.onNodeWithTag(UiTestTags.ENTRY_SAVE).performClick()
        compose.waitUntil(5_000) { gateway.entryValues.any { it.label == "Mail" } }
    }

    @Test fun existingEntryOpensTheEditorWithItsCurrentValues() {
        val gateway = TestGateway(
            saved = true,
            initialEntries = listOf(OtpEntryUiModel(7, label = "Work Mail", issuer = "Example")),
        )
        compose.setContent { TwoFactorApp(gateway, PlatformActions(requestDeviceUnlock = { true })) }
        compose.onNodeWithTag(UiTestTags.UNLOCK).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTestTags.entry(7)).fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithTag(UiTestTags.ENTRY_LABEL).assertTextContains("Work Mail")
    }

    @Test fun scannerAndBackupFileSelectionPopulateComposeForms() {
        val gateway = TestGateway(saved = true)
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP"
        compose.setContent {
            TwoFactorApp(
                gateway,
                PlatformActions(
                    requestDeviceUnlock = { true }, scanOtpAuth = { uri },
                    readTextFile = { uri },
                ),
            )
        }
        compose.onNodeWithTag(UiTestTags.UNLOCK).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTestTags.ADD_ENTRY).fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithTag(UiTestTags.SCAN_FAB).performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(UiTestTags.ENTRY_FORM).fetchSemanticsNode() }.isSuccess }
        assertTrue(
            compose.onNodeWithTag(UiTestTags.OTPAUTH_URI).fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text.contains("otpauth://"),
        )
        compose.onNodeWithTag(UiTestTags.ENTRY_CANCEL).performClick()
        compose.onNodeWithTag(UiTestTags.destinationTab("backups")).performClick()
        compose.onNodeWithTag(UiTestTags.PICK_IMPORT_FILE).performClick()
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag(UiTestTags.IMPORT_CONTENT).fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText].text.contains("otpauth://")
            }.getOrDefault(false)
        }
    }

    @Test fun settingsAndGroupColorControlsExposeAccessibleTouchTargets() {
        val gateway = TestGateway(
            saved = true,
            initialGroups = listOf(OtpGroupUiModel(1, "Work", 0xFF0F766E)),
        )
        compose.setContent { TwoFactorApp(gateway, PlatformActions(requestDeviceUnlock = { true })) }
        compose.onNodeWithTag(UiTestTags.UNLOCK).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTestTags.BOTTOM_NAV).fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithTag(UiTestTags.destinationTab("settings")).performClick()
        listOf("System", "Light", "Dark").forEach { name ->
            val node = compose.onNodeWithTag(UiTestTags.themeOption(name)).assertHasClickAction().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height >= 48f)
        }
        compose.onNodeWithTag(UiTestTags.APP_LOCK).assertHasClickAction()
        compose.onNodeWithTag(UiTestTags.themeOption("Dark")).performClick()
        compose.onNodeWithTag(UiTestTags.languageOption("Chinese")).performClick()
        compose.waitUntil(5_000) {
            gateway.preferencesValue.theme == ThemePreference.Dark &&
                gateway.preferencesValue.language == LanguagePreference.Chinese
        }
        compose.onNodeWithTag(UiTestTags.destinationTab("groups")).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTestTags.group(1)).fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithContentDescription("Color #0F766E").assertHasClickAction()
        compose.onNodeWithTag(UiTestTags.GROUP_NAME).performTextReplacement("Office")
        compose.onNodeWithTag(UiTestTags.GROUP_SAVE).performClick()
        compose.waitUntil(5_000) { gateway.groupValues.single().name == "Office" }
    }

    @Test fun lightAndDarkPreferencesApplyDistinctComposeColorSchemes() {
        var preference by mutableStateOf(ThemePreference.Light)
        var rendered = Color.Unspecified
        compose.setContent {
            TwoFactorTheme(preference, dynamicColor = false) {
                rendered = MaterialTheme.colorScheme.background
                Box(Modifier.size(1.dp).testTag("theme_probe"))
            }
        }
        compose.onNodeWithTag("theme_probe").assertIsDisplayed()
        var light = Color.Unspecified
        compose.runOnIdle { light = rendered; preference = ThemePreference.Dark }
        compose.waitUntil(5_000) { rendered != light }
        compose.runOnIdle { assertTrue(light.luminance() > rendered.luminance()) }
    }

    @Test fun encryptedExportAndPasswordChangeCompleteTheirNavigationFlows() {
        val gateway = TestGateway(saved = true)
        var exported: String? = null
        compose.setContent {
            TwoFactorApp(
                gateway,
                PlatformActions(
                    requestDeviceUnlock = { true },
                    writeTextFile = { _, _, content -> exported = content; true },
                ),
            )
        }
        compose.onNodeWithTag(UiTestTags.UNLOCK).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTestTags.BOTTOM_NAV).fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithTag(UiTestTags.destinationTab("backups")).performClick()
        compose.onNodeWithTag(UiTestTags.BACKUP_PASSPHRASE).performTextInput("Long passphrase")
        compose.onNodeWithTag(UiTestTags.EXPORT_ENCRYPTED).performClick()
        compose.waitUntil(5_000) { exported == "{}" }

        compose.onNodeWithTag(UiTestTags.destinationTab("settings")).performClick()
        compose.onNodeWithTag(UiTestTags.CHANGE_PASSWORD).performClick()
        compose.onNodeWithTag(UiTestTags.CURRENT_PASSWORD).performTextInput("Old-password-1!")
        compose.onNodeWithTag(UiTestTags.NEW_PASSWORD).performTextInput("New-password-2!")
        compose.onNodeWithTag(UiTestTags.PASSWORD_SAVE).performClick()
        compose.waitUntil(5_000) { gateway.passwordChanged }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTestTags.LOGIN).fetchSemanticsNode() }.isSuccess }
    }

    @Test fun englishAndChineseResourcesRenderInsideCompose() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = Locale.getDefault()
        var language by mutableStateOf(LocaleHelper.LANG_EN)
        try {
            compose.setContent {
                val localized = remember(language) { LocaleHelper.wrapContext(base, language) }
                CompositionLocalProvider(
                    LocalContext provides localized,
                    LocalConfiguration provides localized.resources.configuration,
                ) {
                    Text(stringResource(R.string.nav_settings), Modifier.testTag("localized_label"))
                }
            }
            compose.onNodeWithTag("localized_label").assertTextEquals("Settings")
            compose.runOnIdle { language = LocaleHelper.LANG_ZH }
            compose.onNodeWithTag("localized_label").assertTextEquals("设置")
        } finally {
            Locale.setDefault(previous)
        }
    }
}

private class TestGateway(
    private var saved: Boolean = false,
    initialEntries: List<OtpEntryUiModel> = emptyList(),
    initialGroups: List<OtpGroupUiModel> = emptyList(),
) : UiGateway {
    val entryValues = initialEntries.toMutableList()
    val groupValues = initialGroups.toMutableList()
    var preferencesValue = AppPreferencesUiModel()
    var passwordChanged = false
    override val serverUrl = "https://example.workers.dev"; override val appVersion = "test"
    override suspend fun capabilities() = CapabilityUiModel("v1", setOf("android"))
    override suspend fun hasSavedSession() = saved
    override suspend fun login(username: String, password: String, turnstileToken: String?) = UserUiModel("1", username, "user")
    override suspend fun currentUser() = UserUiModel("1", "alice", "user")
    override suspend fun logout() { saved = false }
    override suspend fun entries() = entryValues.toList()
    override suspend fun groups() = groupValues.toList()
    override suspend fun refreshCodes(entryIds: List<Long>) = emptyMap<Long, Pair<String, Long?>>()
    override suspend fun generateHotp(entryId: Long) = error("unused")
    override suspend fun createEntry(draft: OtpEntryDraft) = OtpEntryUiModel(
        id = (entryValues.maxOfOrNull { it.id } ?: 0) + 1,
        label = draft.label,
        issuer = draft.issuer,
    ).also { entryValues += it }
    override suspend fun updateEntry(draft: OtpEntryDraft) = OtpEntryUiModel(requireNotNull(draft.id), draft.label)
    override suspend fun deleteEntry(entryId: Long) = Unit
    override suspend fun setEntryEnabled(entryId: Long, enabled: Boolean) = error("unused")
    override suspend fun moveEntry(entryId: Long, groupId: Long?) = error("unused")
    override suspend fun createGroup(name: String, color: Long) = OtpGroupUiModel(1, name, color)
    override suspend fun updateGroup(id: Long, name: String, color: Long) = OtpGroupUiModel(id, name, color).also { updated ->
        groupValues.replaceAll { if (it.id == id) updated else it }
    }
    override suspend fun deleteGroup(id: Long) = Unit
    override suspend fun importOtpAuth(content: String, groupId: Long?) = ImportSummaryUiModel(0, 0, failed = 0)
    override suspend fun importEncrypted(content: String, passphrase: CharArray) = EncryptedImportSummaryUiModel(0, 0)
    override suspend fun exportEncrypted(passphrase: CharArray) = "{}"
    override suspend fun preferences() = preferencesValue
    override suspend fun setTheme(theme: ThemePreference) { preferencesValue = preferencesValue.copy(theme = theme) }
    override suspend fun setLanguage(language: LanguagePreference) { preferencesValue = preferencesValue.copy(language = language) }
    override suspend fun setAppLock(enabled: Boolean) { preferencesValue = preferencesValue.copy(appLockEnabled = enabled) }
    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        passwordChanged = true
        saved = false
    }
    override suspend fun loginCredentials() = SavedLoginCredentials()
    override suspend fun rememberLogin(username: String, password: String?, rememberPassword: Boolean) = Unit
    override suspend fun clearRememberedPassword() = Unit
}
