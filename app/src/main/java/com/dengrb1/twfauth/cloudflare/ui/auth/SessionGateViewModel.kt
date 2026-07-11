package com.dengrb1.twfauth.cloudflare.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Survives configuration changes but deliberately does not restore an unlocked process. */
class SessionGateViewModel : ViewModel() {
    var authenticated by mutableStateOf(false)
        private set

    fun unlock() { authenticated = true }
    fun lock() { authenticated = false }
}

