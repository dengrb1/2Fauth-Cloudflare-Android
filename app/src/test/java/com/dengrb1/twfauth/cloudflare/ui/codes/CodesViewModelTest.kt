package com.dengrb1.twfauth.cloudflare.ui.codes

import androidx.lifecycle.SavedStateHandle
import com.dengrb1.twfauth.cloudflare.ui.FakeUiGateway
import com.dengrb1.twfauth.cloudflare.ui.MainDispatcherRule
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpGroupUiModel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun searchSortFilterAndSelectionSurviveSavedState() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply {
            groupValues += OtpGroupUiModel(4, "Work", 0xFF000000)
            entryValues += listOf(
                OtpEntryUiModel(1, "alice", issuer = "GitHub", groupId = 4),
                OtpEntryUiModel(2, "bob", issuer = "Google"),
            )
        }
        val handle = SavedStateHandle()
        val viewModel = CodesViewModel(gateway, handle)
        runCurrent()
        viewModel.setQuery("git")
        viewModel.setGroupFilter(GroupFilter.Group(4))
        viewModel.toggleSelection(1)
        assertEquals(listOf(1L), viewModel.state.value.visibleEntries.map { it.id })
        val restored = CodesViewModel(gateway, handle)
        runCurrent()
        assertEquals("git", restored.state.value.query)
        assertEquals(GroupFilter.Group(4), restored.state.value.groupFilter)
        assertEquals(setOf(1L), restored.state.value.selectedIds)
        viewModel.cancelScheduledRefresh(); restored.cancelScheduledRefresh()
    }

    @Test fun batchDeleteReportsPartialFailureInsteadOfPretendingAtomicity() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply {
            entryValues += listOf(OtpEntryUiModel(1, "one"), OtpEntryUiModel(2, "two"))
            deleteFailures = setOf(2)
        }
        val viewModel = CodesViewModel(gateway, SavedStateHandle())
        runCurrent()
        viewModel.toggleSelection(1); viewModel.toggleSelection(2); viewModel.deleteSelected()
        runCurrent()
        assertEquals(1, viewModel.state.value.operation?.failed)
        assertEquals(listOf(2L), viewModel.state.value.entries.map { it.id })
        viewModel.cancelScheduledRefresh()
    }

    @Test fun totpCodesRefreshAtTheServerDerivedExpiryBoundary() = runTest(main.dispatcher) {
        var calls = 0
        var now = 0L
        val gateway = object : FakeUiGateway() {
            override suspend fun refreshCodes(entryIds: List<Long>): Map<Long, Pair<String, Long?>> {
                calls++
                val expiry = if (calls == 1) 1L else 31L
                return entryIds.associateWith { calls.toString() to expiry }
            }
        }.apply { entryValues += OtpEntryUiModel(1, "totp") }
        val viewModel = CodesViewModel(gateway, SavedStateHandle(), nowMillis = { now })
        viewModel.setActive(true)
        runCurrent()
        assertEquals(1, calls)
        now = 1_000
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, calls)
        assertEquals("2", viewModel.state.value.entries.single().code)
        viewModel.setActive(false)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun partialBatchFailureBacksOffInsteadOfRetryingEverySecond() = runTest(main.dispatcher) {
        var calls = 0
        var now = 0L
        val gateway = object : FakeUiGateway() {
            override suspend fun refreshCodes(entryIds: List<Long>): Map<Long, Pair<String, Long?>> {
                calls++
                return if (calls == 1) entryIds.associateWith { "first" to 1L }
                else mapOf(1L to ("second" to 30L))
            }
        }.apply { entryValues += listOf(OtpEntryUiModel(1, "one"), OtpEntryUiModel(2, "two")) }
        val viewModel = CodesViewModel(gateway, SavedStateHandle(), nowMillis = { now })
        viewModel.setActive(true); runCurrent()
        now = 1_000; advanceTimeBy(1_000); runCurrent()
        assertEquals(2, calls)
        assertEquals(6L, viewModel.state.value.entries.first { it.id == 2L }.codeValidUntilEpochSeconds)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, calls)
        viewModel.cancelScheduledRefresh()
    }
}
