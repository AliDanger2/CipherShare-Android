package com.ciphershare.android.ui

import androidx.compose.runtime.compositionLocalOf
import com.ciphershare.android.data.AppState

val LocalAppState = compositionLocalOf<AppState> { error("AppState not provided") }
