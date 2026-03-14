package com.groovebox.ui

actual fun platformShowMessage(message: String) {
    println("TOAST: $message")
}

actual fun openUrl(url: String) {
    try {
        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    } catch (e: Exception) {
        println("Error opening URL: $url")
    }
}
