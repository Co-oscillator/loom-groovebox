package com.groovebox.ui

import androidx.compose.runtime.staticCompositionLocalOf

val LocalFocusedValue = staticCompositionLocalOf<(String?) -> Unit> { {} }
