package com.groovebox.ui

import androidx.compose.ui.input.pointer.PointerIcon

actual fun platformShowMessage(message: String) {
    // Android implementation via Toast logic elsewhere or passed Context
}

actual fun openUrl(url: String) {
    // Android implementation via Intent
}

actual val BlankPointerIcon: PointerIcon = PointerIcon.Default
