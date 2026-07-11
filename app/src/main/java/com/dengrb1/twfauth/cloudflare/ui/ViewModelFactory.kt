package com.dengrb1.twfauth.cloudflare.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

internal inline fun <reified T : ViewModel> savedStateFactory(
    crossinline create: (SavedStateHandle) -> T,
) = viewModelFactory { initializer { create(createSavedStateHandle()) } }

