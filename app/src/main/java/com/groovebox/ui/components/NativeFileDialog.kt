package com.groovebox.ui.components

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import com.groovebox.ui.theme.getEngineColor
import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.persistence.PersistenceManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import com.groovebox.ui.components.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.ExperimentalComposeUiApi

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun NativeFileDialog(
    directory: File,
    onDismiss: () -> Unit,
    onFileSelected: (String) -> Unit,
    isSave: Boolean,
    extraOptions: List<Pair<String, String>> = emptyList(),
    onExport: ((Int, String, String) -> Unit)? = null,
    trackIndex: Int = -1,
    extensions: List<String>? = listOf("wav"),
    title: String? = null,
    state: GrooveboxState? = null
) {
    val context = LocalContext.current
    var currentDir by remember { mutableStateOf(directory) }
    var refreshKey by remember { mutableStateOf(0) }
    var currentExtensions by remember { mutableStateOf(extensions) }
    var fileName by remember { mutableStateOf("") }
    
    val files: List<String> = remember(currentDir, refreshKey, currentExtensions) { 
        if (!currentDir.exists()) {
             emptyList()
        } else if (!currentDir.isDirectory) {
             emptyList()
        } else {
            val list = currentDir.listFiles { file -> 
                currentExtensions?.any { ext -> file.extension.equals(ext, ignoreCase = true) } ?: true
            }?.sortedBy { it.name }?.map { it.name } ?: emptyList()
            list
        }
    }

    // System Picker
    val systemLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                // Copy to currentDir
                val contentResolver = context.contentResolver
                val time = System.currentTimeMillis()
                // Try to get name or use timestamp
                var name = "import_$time.wav" // default
                // Attempt to query name
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                         val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                         if(index >= 0) name = cursor.getString(index)
                    }
                }
                
                // Copy
                val destFile = File(currentDir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                refreshKey++
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Import failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }

    
    // Export Launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let {
             try {
                 val sourceFile = File(currentDir, fileName)
                 if (sourceFile.exists()) {
                     context.contentResolver.openOutputStream(it)?.use { output ->
                         sourceFile.inputStream().use { input ->
                             input.copyTo(output)
                         }
                     }
                     android.widget.Toast.makeText(context, "Export Successful!", android.widget.Toast.LENGTH_SHORT).show()
                 }
             } catch (e: Exception) {
                 android.widget.Toast.makeText(context, "Export Failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
             }
        }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val loomFolders = listOf("samples", "granular", "wavetables", "recordings", "sessions", "soundfonts")
            Card(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(enabled = false) {}, // Prevent clicks passing through
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
            // Apply IME padding to the content column so it shrinks when keyboard opens
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
            ) {
                val headerText = (title ?: (if (isSave) "SAVE" else "LOAD")) + " (${files.size} files)"
                
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        headerText,
                        style = MaterialTheme.typography.titleMedium,
                        color = getEngineColor(if (trackIndex != -1 && state != null) state.tracks[trackIndex].engineType else EngineType.SAMPLER)
                    )
                    Text(currentDir.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                // Folder Navigation Bar
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    loomFolders.forEach { folderName ->
                        val rootDir = PersistenceManager.getLoomFolder(context)
                        val folderFile = File(rootDir, folderName).apply { if (!exists()) mkdirs() }
                        val isSelected = currentDir.absolutePath == folderFile.absolutePath
                        
                        Surface(
                            onClick = { 
                                currentDir = folderFile
                                currentExtensions = when(folderName) {
                                    "wavetables" -> listOf("wav", "wt")
                                    "samples", "granular", "recordings" -> listOf("wav")
                                    "sessions" -> listOf("gbx")
                                "soundfonts" -> listOf("sf2", "sf3", "SF2", "SF3")
                                else -> listOf("wav")
                            }
                            refreshKey++ 
                        },
                            color = if (isSelected) Color.White.copy(alpha = 0.1f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (isSelected) Color.White else Color.Gray.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                folderName.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) Color.White else Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                // System Import Button
                if (!isSave) {
                    Button(
                        onClick = { 
                           // Launch for all audio or wildcard if unknown. 
                           // Wavetables might be .wav, Soundfonts .sf2
                           val mime = if (extensions?.contains("sf2") == true) "*/*" else "audio/*"
                           systemLauncher.launch(mime)
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Text("OPEN SYSTEM BROWSER", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                if (isSave) {
                    val focusRequester = remember { FocusRequester() }
                    val keyboardController = LocalSoftwareKeyboardController.current
                    
                    LaunchedEffect(Unit) {
                        delay(300)
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }

                    OutlinedTextField(
                         value = fileName,
                         onValueChange = { fileName = it },
                         label = { Text("Filename") },
                         modifier = Modifier
                             .fillMaxWidth()
                             .focusRequester(focusRequester),
                         colors = OutlinedTextFieldDefaults.colors(
                             focusedTextColor = Color.White,
                             unfocusedTextColor = Color.White,
                             focusedBorderColor = Color.Cyan,
                             unfocusedBorderColor = Color.Gray
                         ),
                         keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                         keyboardActions = KeyboardActions(
                             onDone = { keyboardController?.hide() }
                         ),
                         singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                     // Export is now in the context menu
                }


                var renamingFile by remember { mutableStateOf<File?>(null) }
                var newName by remember { mutableStateOf("") }
                
                if (renamingFile != null) {
                    AlertDialog(
                        onDismissRequest = { renamingFile = null },
                        title = { Text("RENAME FILE") },
                        text = {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text("New Name") }
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                renamingFile?.let { file ->
                                    val ext = file.extension
                                    val dest = File(file.parentFile, if (newName.endsWith(".$ext")) newName else "$newName.$ext")
                                    file.renameTo(dest)
                                }
                                renamingFile = null
                                refreshKey++
                            }) { Text("RENAME") }
                        },
                        dismissButton = { TextButton(onClick = { renamingFile = null }) { Text("CANCEL") } }
                    )
                }

                val listState = rememberLazyListState()
                Box(modifier = Modifier.weight(1f)) { // Use weight to fill available space
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        // Extra Options (Direct Actions)
                        if (extraOptions.isNotEmpty()) {
                            items(extraOptions) { (label, value) ->
                                Text(
                                    label,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onFileSelected(value)
                                            onDismiss()
                                        }
                                        .padding(vertical = 8.dp),
                                    color = Color.Yellow,
                                    fontWeight = FontWeight.Bold
                                )
                                Divider(color = Color.White.copy(alpha = 0.1f))
                            }
                        }

                        items(files) { name ->
                            var showFileMenu by remember { mutableStateOf(false) }
                            val file = File(currentDir, name)
                            
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (isSave) fileName = name.removeSuffix(".wav")
                                                else {
                                                    onFileSelected(file.absolutePath)
                                                    onDismiss()
                                                }
                                            },
                                            onLongClick = { showFileMenu = true }
                                        )
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = "Handle",
                                        tint = Color.Gray.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(name, color = Color.White, modifier = Modifier.weight(1f))
                                }

                                DropdownMenu(
                                    expanded = showFileMenu,
                                    onDismissRequest = { showFileMenu = false },
                                    offset = DpOffset(x = 40.dp, y = 0.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Load") },
                                        onClick = {
                                            showFileMenu = false
                                            onFileSelected(file.absolutePath)
                                            onDismiss()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            showFileMenu = false
                                            newName = name.removeSuffix(".wav")
                                            renamingFile = file
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy") },
                                        onClick = {
                                            try {
                                                val newFile = File(directory, name.removeSuffix(".wav") + "_copy.wav")
                                                file.copyTo(newFile, overwrite = true)
                                                refreshKey++
                                                android.util.Log.d("NativeFileDialog", "Copied file: ${newFile.absolutePath}")
                                            } catch (e: Exception) {
                                                android.util.Log.e("NativeFileDialog", "Copy failed", e)
                                                val msg = "Copy failed: ${e.message?.take(50)}"
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            showFileMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = Color.Red) },
                                        onClick = {
                                            try {
                                                if (file.delete()) {
                                                    refreshKey++
                                                    android.util.Log.d("NativeFileDialog", "Deleted file: ${file.absolutePath}")
                                                } else {
                                                    throw Exception("Delete returned false")
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.e("NativeFileDialog", "Delete failed", e)
                                                android.widget.Toast.makeText(context, "Delete failed", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            showFileMenu = false
                                        }
                                    )
                                    if (!isSave) {
                                        Divider(color = Color.DarkGray)
                                        DropdownMenuItem(
                                            text = { Text("Export to AAC") },
                                            onClick = {
                                                showFileMenu = false
                                                onExport?.invoke(trackIndex, file.absolutePath, "AAC")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Export to FLAC") },
                                            onClick = {
                                                showFileMenu = false
                                                onExport?.invoke(trackIndex, file.absolutePath, "FLAC")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Export to Public Storage", color = Color.Yellow) },
                                            onClick = {
                                                showFileMenu = false
                                                fileName = name // Set filename for export
                                                exportLauncher.launch(name)
                                            }
                                        )
                                    }
                                }
                            }
                            Divider(color = Color.White.copy(alpha = 0.05f))
                        }
                    }
                    
                    // Simple custom scrollbar indicator
                    if (files.size > 5) {
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            state = listState
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.Gray) }
                    if (isSave) {
                        Button(
                            onClick = {
                                val finalName = if (fileName.endsWith(".wav")) fileName else "$fileName.wav"
                                onFileSelected(File(directory, finalName).absolutePath)
                                onDismiss()
                            },
                            enabled = fileName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                        ) {
                            Text("SAVE", color = Color.Black)
                        }
                    }
                }
            }
            }
        }
    }
}
