package com.dengrb1.twfauth.cloudflare.ui.model

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Immutable
data class UiOperationError(
    val message: String,
    val retryAfterSeconds: Long? = null,
    val status: Int? = null,
    val serverMessage: Boolean = false,
    val clientCode: String? = null,
)

internal fun Throwable.toOperationError(): UiOperationError {
    val gateway = this as? UiGatewayException
    return UiOperationError(
        message = gateway?.message ?: message.orEmpty(), retryAfterSeconds = gateway?.retryAfterSeconds,
        status = gateway?.status, serverMessage = gateway?.serverMessage == true,
        clientCode = gateway?.clientCode,
    )
}

internal suspend inline fun <T> runUiCatching(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

abstract class CooldownViewModel : ViewModel() {
    private var cooldownJob: Job? = null
    private val screenJobs = Collections.synchronizedSet(mutableSetOf<Job>())

    /**
     * Starts work owned by the visible screen. Screen composables cancel these jobs from their
     * lifecycle STOP callback, while the independent rate-limit countdown may keep advancing.
     */
    protected fun launchScreenTask(block: suspend CoroutineScope.() -> Unit): Job {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY, block = block)
        screenJobs += job
        job.invokeOnCompletion { screenJobs -= job }
        job.start()
        return job
    }

    protected fun cancelScreenTasks() {
        val jobs = synchronized(screenJobs) {
            screenJobs.toList().also { screenJobs.clear() }
        }
        jobs.forEach(Job::cancel)
    }

    protected fun beginCooldown(seconds: Long?, onTick: (Long?) -> Unit) {
        cooldownJob?.cancel()
        if (seconds == null || seconds <= 0) { onTick(null); return }
        cooldownJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                onTick(remaining)
                delay(1_000)
                remaining--
            }
            onTick(null)
        }
    }
}
