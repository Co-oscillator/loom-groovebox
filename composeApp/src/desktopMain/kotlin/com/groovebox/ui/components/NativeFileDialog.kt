package com.groovebox.ui.components

import androidx.compose.runtime.*
import com.groovebox.GrooveboxState
import java.io.File
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun NativeFileDialog(
    directory: File,
    onDismiss: () -> Unit,
    state: GrooveboxState,
    onFileSelected: (String) -> Unit,
    isSave: Boolean,
    trackIndex: Int,
    extensions: List<String>,
    title: String,
    onExport: ((Int, String, String) -> Unit)?
) {
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val parentFrame = Frame.getFrames().firstOrNull { it.isVisible }
            val dialog = FileDialog(parentFrame, title, if (isSave) FileDialog.SAVE else FileDialog.LOAD)
            
            dialog.directory = directory.absolutePath
            
            if (extensions.isNotEmpty() && !isSave) {
                dialog.file = extensions.joinToString(";") { "*.$it" }
            }
            
            dialog.isVisible = true
            
            val selectedFile = if (dialog.file != null) {
                File(dialog.directory, dialog.file).absolutePath
            } else null
            
            if (selectedFile != null) {
                onFileSelected(selectedFile)
            }
            onDismiss()
        }
    }
}
