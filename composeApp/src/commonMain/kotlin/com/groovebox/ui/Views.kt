package com.groovebox.ui

import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class PlatformInfo(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val isTablet: Boolean,
    val platform: String // "android", "macos", "windows", "linux"
)

val LocalPlatformInfo = compositionLocalOf<PlatformInfo> {
    error("No PlatformInfo provided")
}

val LocalFocusedValue = compositionLocalOf<String?> { null }
val LocalFocusedSetter = compositionLocalOf<(String?) -> Unit> { { } }

expect fun platformShowMessage(message: String)
expect fun openUrl(url: String)

expect val BlankPointerIcon: PointerIcon
