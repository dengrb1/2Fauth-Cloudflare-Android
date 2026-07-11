package com.dengrb1.twfauth.cloudflare.ui.model

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StateSupportTest {
    @Test fun cancellationIsRethrownInsteadOfConvertedToUiFailure() = runTest {
        val task = async { runUiCatching { awaitCancellation() } }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
    }
}
