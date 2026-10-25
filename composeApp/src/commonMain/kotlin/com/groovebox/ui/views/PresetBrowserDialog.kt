package com.groovebox.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.presets.FactoryPresets
import com.groovebox.presets.HelmPresetImporter
import com.groovebox.presets.LoomPreset
import com.groovebox.presets.PresetApplier
import com.groovebox.presets.PresetCodec
import com.groovebox.presets.PresetEngine
import com.groovebox.presets.SurgePresetImporter
import com.groovebox.persistence.PersistenceManager
import com.groovebox.ui.components.NativeFileDialog
import com.groovebox.ui.platformShowMessage
import java.io.File

/**
 * Factory preset browser + portable preset import/export.
 *
 * The import path accepts Loom preset JSON and Helm `.helm` patches (best-effort
 * mapping into the current engine).
 */
@Composable
fun PresetBrowserDialog(
    state: GrooveboxState,
    trackIndex: Int,
    nativeLib: NativeLib,
    onStateChange: (GrooveboxState) -> Unit,
    onDismiss: () -> Unit
) {
    val track = state.tracks.getOrNull(trackIndex) ?: return
    val engine = PresetEngine.forEngineTypeId(track.engineType.ordinal)
    if (engine == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Presets") },
            text = { Text("This engine does not have factory presets yet.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
        return
    }

    val allPresets = remember(engine) { FactoryPresets.forEngine(engine) }
    val categories = remember(engine) { listOf("All") + FactoryPresets.categories(engine) }
    var category by remember(engine) { mutableStateOf("All") }
    var selectedName by remember(engine) { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("My ${PresetEngine.displayName(engine)}") }

    val filtered = remember(allPresets, category) {
        if (category == "All") allPresets else allPresets.filter { it.category == category }
    }

    val presetDir = remember(engine) {
        File(File(PersistenceManager.getLoomFolder(), "Presets"), track.engineType.name)
            .apply { if (!exists()) mkdirs() }
    }

    fun applyPreset(preset: LoomPreset) {
        PresetApplier.apply(preset, trackIndex, state, onStateChange, nativeLib)
        selectedName = preset.name
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight(0.85f)
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(16.dp)),
            color = Color(0xFF161616),
            tonalElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "FACTORY PRESETS",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${PresetEngine.displayName(engine)} · ${allPresets.size} patches",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { cat ->
                        val selected = cat == category
                        OutlinedButton(
                            onClick = { category = cat },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.height(30.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color.Transparent,
                                contentColor = if (selected) Color(0xFF00E5FF) else Color.Gray
                            ),
                            border = BorderStroke(1.dp, if (selected) Color(0xFF00E5FF) else Color.DarkGray)
                        ) {
                            Text(cat, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.name }) { preset ->
                        val selected = preset.name == selectedName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (selected) Color(0xFF00E5FF).copy(alpha = 0.12f)
                                    else Color.DarkGray.copy(alpha = 0.25f)
                                )
                                .clickable { applyPreset(preset) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    preset.name,
                                    color = if (selected) Color(0xFF00E5FF) else Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${preset.category} · ${preset.description}",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selected) {
                                Text("LOADED", color = Color(0xFF00E5FF), fontSize = 9.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showImport = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("IMPORT", fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showExport = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("EXPORT", fontSize = 11.sp, maxLines = 1)
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("CLOSE", fontSize = 11.sp, maxLines = 1)
                    }
                }

                Text(
                    "Import accepts Loom .json presets, Helm .helm patches (Subtractive/Wavetable) and Surge .fxp patches (Wavetable). Best-effort mapping; original attribution is kept.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        if (showImport) {
            NativeFileDialog(
                directory = presetDir,
                onDismiss = { showImport = false },
                state = state,
                onFileSelected = { path ->
                    val file = File(path)
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    val text = bytes?.toString(Charsets.UTF_8)
                    if (bytes == null || text == null) {
                        platformShowMessage("Could not read ${file.name}")
                    } else if (HelmPresetImporter.looksLikeHelm(text)) {
                        if (engine != PresetEngine.SUBTRACTIVE && engine != PresetEngine.WAVETABLE) {
                            platformShowMessage("Helm patches map to Subtractive or Wavetable tracks")
                        } else {
                            val imported = HelmPresetImporter.import(text, engine)
                            if (imported != null) applyPreset(imported)
                            else platformShowMessage("Could not map this Helm patch")
                        }
                    } else if (SurgePresetImporter.looksLikeSurge(bytes)) {
                        if (engine != PresetEngine.WAVETABLE) {
                            platformShowMessage("Surge patches map to Wavetable tracks")
                        } else {
                            val imported = SurgePresetImporter.import(bytes, engine)
                            if (imported != null) applyPreset(imported)
                            else platformShowMessage("Could not map this Surge patch")
                        }
                    } else {
                        val imported = PresetCodec.decode(text)
                        if (imported == null) {
                            platformShowMessage("Not a Loom preset file")
                        } else if (imported.engine != engine) {
                            platformShowMessage(
                                "This preset is for ${PresetEngine.displayName(imported.engine)}, " +
                                    "not ${PresetEngine.displayName(engine)}"
                            )
                        } else {
                            applyPreset(imported)
                        }
                    }
                },
                isSave = false,
                trackIndex = trackIndex,
                extensions = listOf("helm", "fxp", "json"),
                title = "IMPORT PRESET"
            )
        }

        if (showExport) {
            AlertDialog(
                onDismissRequest = { showExport = false },
                title = { Text("Export Preset") },
                text = {
                    OutlinedTextField(
                        value = exportName,
                        onValueChange = { exportName = it },
                        label = { Text("Preset name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val exported = PresetApplier.exportTrack(trackIndex, state, exportName.trim())
                            if (exported == null) {
                                platformShowMessage("This engine cannot be exported")
                            } else {
                                val file = File(presetDir, exportName.trim().ifBlank { "preset" } + ".loom.json")
                                if (PresetCodec.save(file, exported)) {
                                    platformShowMessage("Saved ${file.name}")
                                } else {
                                    platformShowMessage("Could not save preset")
                                }
                            }
                            showExport = false
                        }
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showExport = false }) { Text("Cancel") }
                }
            )
        }
    }
}
