package com.groovebox.ui

actual fun platformShowMessage(message: String) {
    // Android implementation via Toast logic elsewhere or passed Context
}

actual fun openUrl(url: String) {
    // Android implementation via Intent
}
