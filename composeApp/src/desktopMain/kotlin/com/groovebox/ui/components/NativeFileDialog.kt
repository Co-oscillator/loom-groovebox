package com.groovebox.ui.components

import androidx.compose.runtime.*
import com.groovebox.GrooveboxState
import java.io.File
import java.awt.FileDialog
import java.awt.Frame

@Composable
actual fun NativeFileDialog(
    directory: File,
    onDismiss: () -> Unit,
    state: GrooveboxState,
    onFileSelected: (String) -> Unit,
    isSave: Boolean,
    trackIndex: Int,
    extensions: List<String>,
    title: String
) {
    LaunchedEffect(Unit) {
        // Run on IO thread to avoid blocking Compose UI thread
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, title, if (isSave) FileDialog.SAVE else FileDialog.LOAD)
            dialog.directory = directory.absolutePath
            
            // Simple extension filter
            if (extensions.isNotEmpty() && !isSave) {
                dialog.file = extensions.joinToString(";") { "*.$it" }
            }
            
            dialog.isVisible = true
            
            val selectedFile = dialog.files.firstOrNull()?.absolutePath
            if (selectedFile != null) {
                onFileSelected(selectedFile)
            }
            onDismiss()
        }
    }
}
