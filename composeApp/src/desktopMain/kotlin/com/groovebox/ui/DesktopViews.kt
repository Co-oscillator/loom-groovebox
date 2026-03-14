package com.groovebox.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

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

actual val BlankPointerIcon: PointerIcon = PointerIcon(
    Toolkit.getDefaultToolkit().createCustomCursor(
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
        Point(0, 0),
        "blank"
    )
)
