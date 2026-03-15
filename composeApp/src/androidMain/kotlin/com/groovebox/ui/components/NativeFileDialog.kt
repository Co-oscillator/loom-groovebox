package com.groovebox.ui.components

import androidx.compose.runtime.Composable
import com.groovebox.GrooveboxState
import java.io.File
import androidx.compose.material3.*

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
    // Simplified Android File Dialog using a basic Alert holding a list of files for now
    // or just a placeholder to get it building.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text("File picker for Android is not fully implemented in commonMain yet.")
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        }
    )
}
