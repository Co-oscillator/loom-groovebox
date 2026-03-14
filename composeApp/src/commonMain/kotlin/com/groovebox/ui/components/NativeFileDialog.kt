package com.groovebox.ui.components

import androidx.compose.runtime.Composable
import com.groovebox.GrooveboxState
import java.io.File

@Composable
expect fun NativeFileDialog(
    directory: File,
    onDismiss: () -> Unit,
    state: GrooveboxState,
    onFileSelected: (String) -> Unit,
    isSave: Boolean = false,
    trackIndex: Int = -1,
    extensions: List<String> = emptyList(),
    title: String = "Select File"
)
