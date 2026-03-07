package com.groovebox.persistence

import android.content.Context
import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.StripRouting
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.groovebox.StepState
import com.groovebox.TrackState
import com.groovebox.ArpConfig
import com.groovebox.ArpMode

import java.io.*

object PersistenceManager {
    private const val FILENAME = "strip_assignments.json"
    private const val PROJECTS_DIR = "Projects"
    private const val LOOM_ROOT = "Loom"

    fun getLoomFolder(context: Context): File {
        // Force App-Specific External Storage for reliability & C++ access
        // Path: /Android/data/com.groovebox/files/Loom/
        // Accessible via USB, but sandboxed from other apps.
        val externalFiles = context.getExternalFilesDir(null)
        val loomDir = File(externalFiles ?: context.filesDir, "Loom")
        
        if (!loomDir.exists()) {
             try {
                 loomDir.mkdirs()
             } catch (e: Exception) {
                 e.printStackTrace()
             }
        }
        return loomDir
    }

    private fun getProjectsDir(context: Context): File {
        val dir = File(getLoomFolder(context), PROJECTS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveProject(context: Context, state: GrooveboxState, fileName: String) {
        try {
            val name = if (fileName.endsWith(".gbx")) fileName else "$fileName.gbx"
            val file = File(getProjectsDir(context), name)
            ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(state) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadProject(context: Context, fileName: String): GrooveboxState? {
        try {
            val file = File(getProjectsDir(context), fileName)
            if (!file.exists()) return null
            ObjectInputStream(FileInputStream(file)).use {
                val loaded = it.readObject() as? GrooveboxState ?: return null
                // Migration: pad fxSends/fxMix to 18 if loading older saves (was 17)
                return loaded.copy(tracks = loaded.tracks.map { t ->
                    val sends = if (t.fxSends.size < 18) t.fxSends + List(18 - t.fxSends.size) { 0.0f } else t.fxSends
                    val mix = if (t.fxMix.size < 18) t.fxMix + List(18 - t.fxMix.size) { 0.0f } else t.fxMix
                    t.copy(fxSends = sends, fxMix = mix)
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun listProjects(context: Context): List<String> {
        val dir = getProjectsDir(context)
        return dir.listFiles { _, name -> name.endsWith(".gbx") }?.map { it.name } ?: emptyList()
    }

    fun deleteProject(context: Context, fileName: String): Boolean {
        val file = File(getProjectsDir(context), fileName)
        return if (file.exists()) file.delete() else false
    }

    fun copyProject(context: Context, fileName: String): Boolean {
        try {
            val sourceFile = File(getProjectsDir(context), fileName)
            if (!sourceFile.exists()) return false
            
            val newName = "${fileName.removeSuffix(".gbx")}_copy.gbx"
            val destFile = File(getProjectsDir(context), newName)
            
            sourceFile.copyTo(destFile, overwrite = true)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    fun renameProject(context: Context, oldName: String, newName: String): Boolean {
        val sourceFile = File(getProjectsDir(context), oldName)
        val safeNewName = if (newName.endsWith(".gbx")) newName else "$newName.gbx"
        val destFile = File(getProjectsDir(context), safeNewName)
        
        return if (sourceFile.exists() && !destFile.exists()) {
            sourceFile.renameTo(destFile)
        } else false
    }

    fun saveAssignments(context: Context, stripAssignments: Map<EngineType, List<StripRouting>>?, knobAssignments: Map<EngineType, List<StripRouting>>?) {
        val root = JSONObject()
        
        // Save Strips
        val stripsObj = JSONObject()
        stripAssignments?.forEach { (engine, routings) ->
            val array = JSONArray()
            routings.forEach { r ->
                val obj = JSONObject()
                obj.put("stripIndex", r.stripIndex)
                obj.put("parameterName", r.parameterName)
                obj.put("targetType", r.targetType)
                obj.put("targetId", r.targetId)
                obj.put("min", r.min.toDouble())
                obj.put("max", r.max.toDouble())
                array.put(obj)
            }
            stripsObj.put(engine.name, array)
        }
        root.put("strips", stripsObj)

        // Save Knobs
        val knobsObj = JSONObject()
        knobAssignments?.forEach { (engine, routings) ->
            val array = JSONArray()
            routings.forEach { r ->
                val obj = JSONObject()
                obj.put("stripIndex", r.stripIndex)
                obj.put("parameterName", r.parameterName)
                obj.put("targetType", r.targetType)
                obj.put("targetId", r.targetId)
                obj.put("min", r.min.toDouble())
                obj.put("max", r.max.toDouble())
                array.put(obj)
            }
            knobsObj.put(engine.name, array)
        }
        root.put("knobs", knobsObj)
        
        try {
            val file = File(getLoomFolder(context), FILENAME)
            file.writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadAssignments(context: Context): Pair<Map<EngineType, List<StripRouting>>, Map<EngineType, List<StripRouting>>> {
        val file = File(getLoomFolder(context), FILENAME)
        if (!file.exists()) return Pair(emptyMap(), emptyMap())

        val jsonString = file.readText()
        if (jsonString.isEmpty()) return Pair(emptyMap(), emptyMap())

        val stripResult = mutableMapOf<EngineType, List<StripRouting>>()
        val knobResult = mutableMapOf<EngineType, List<StripRouting>>()

        try {
            val root = JSONObject(jsonString)
            
            // Backwards compatibility: Check if root has "strips" object, else assume root IS strips
            val stripsObj = if (root.has("strips")) root.getJSONObject("strips") else root
            
            // Load Strips
            val stripKeys = stripsObj.keys()
            while (stripKeys.hasNext()) {
                val engineName = stripKeys.next()
                try {
                    val engineType = EngineType.valueOf(engineName)
                    val array = stripsObj.getJSONArray(engineName)
                    val list = mutableListOf<StripRouting>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(StripRouting(
                            stripIndex = obj.getInt("stripIndex"),
                            parameterName = obj.optString("parameterName", "None"),
                            targetType = obj.optInt("targetType", 0),
                            targetId = obj.optInt("targetId", 0),
                            min = obj.optDouble("min", 0.0).toFloat(),
                            max = obj.optDouble("max", 1.0).toFloat()
                        ))
                    }
                    stripResult[engineType] = list
                } catch (e: Exception) { }
            }

            // Load Knobs
            if (root.has("knobs")) {
                val knobsObj = root.getJSONObject("knobs")
                val knobKeys = knobsObj.keys()
                while (knobKeys.hasNext()) {
                    val engineName = knobKeys.next()
                    try {
                        val engineType = EngineType.valueOf(engineName)
                        val array = knobsObj.getJSONArray(engineName)
                        val list = mutableListOf<StripRouting>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            list.add(StripRouting(
                                stripIndex = obj.getInt("stripIndex"),
                                parameterName = obj.optString("parameterName", "Knob ${i+1}"),
                                targetType = obj.optInt("targetType", 0),
                                targetId = obj.optInt("targetId", 0),
                                min = obj.optDouble("min", 0.0).toFloat(),
                                max = obj.optDouble("max", 1.0).toFloat()
                            ))
                        }
                        knobResult[engineType] = list
                    } catch (e: Exception) { }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(stripResult, knobResult)
    }

    fun clearAssignments(context: Context) {
        val file = File(getLoomFolder(context), FILENAME)
        if (file.exists()) {
            file.delete()
        }
    }

    fun copyWavetablesToFilesDir(context: Context) {
        copyAssetsToFilesDir(context, "wavetables")
    }

    fun copySoundFontsToFilesDir(context: Context) {
        copyAssetsToFilesDir(context, "soundfonts")
    }

    fun copyDefaultsToFilesDir(context: Context) {
        copyAssetsToFilesDir(context, "defaults")
    }

    private fun copyAssetsToFilesDir(context: Context, dirName: String) {
        try {
            val destDir = File(getLoomFolder(context), dirName)
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            val assets = context.assets.list(dirName) ?: return
            for (assetName in assets) {
                val outFile = File(destDir, assetName)
                if (!outFile.exists() || outFile.length() < 1024) {
                    context.assets.open("$dirName/$assetName").use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun migrateToExternalStorage(context: Context) {
        try {
            val internalDir = context.filesDir
            val externalDir = getLoomFolder(context)
            
            // List of directories to migrate
            val dirs = listOf(PROJECTS_DIR, "wavetables", "soundfonts", "samples", "granular")
            dirs.forEach { dirName ->
                val source = File(internalDir, dirName)
                val dest = File(externalDir, dirName)
                if (source.exists() && source.isDirectory) {
                    if (!dest.exists()) dest.mkdirs()
                    source.listFiles()?.forEach { file ->
                        val destFile = File(dest, file.name)
                        if (!destFile.exists()) {
                            file.copyTo(destFile)
                            file.delete()
                        }
                    }
                }
            }
            
            // Migrate assignments file
            val sourceFile = File(internalDir, FILENAME)
            val destFile = File(externalDir, FILENAME)
            if (sourceFile.exists() && !destFile.exists()) {
                sourceFile.copyTo(destFile)
                sourceFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    fun saveTrackPreset(context: Context, trackState: com.groovebox.TrackState, name: String) {
        try {
            val engineDir = File(File(getLoomFolder(context), "Presets"), trackState.engineType.name)
            if (!engineDir.exists()) engineDir.mkdirs()
            
            val safeName = if (name.endsWith(".gbp")) name else "$name.gbp"
            val file = File(engineDir, safeName)
            ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(trackState) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadTrackPreset(context: Context, engineType: EngineType, name: String): TrackState? {
        try {
            val engineDir = File(File(getLoomFolder(context), "Presets"), engineType.name)
            val file = File(engineDir, if (name.endsWith(".gbp")) name else "$name.gbp")
            
            if (!file.exists()) return null

            // Try Text Load first (New format)
            val textLoad = loadTrackPresetFromText(file)
            if (textLoad != null) return textLoad

            // Fallback to Binary Load (Legacy format)
            ObjectInputStream(FileInputStream(file)).use { return it.readObject() as? TrackState }
        } catch (e: Exception) {
            // e.printStackTrace() // Silent fail for probing
        }
        return null
    }

    fun loadTrackPresetFromText(file: File): TrackState? {
        try {
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0].trim() != "LOOM_PRESET_V1") return null
            
            val params = mutableMapOf<Int, Float>()
            var lineIdx = 1
            while (lineIdx < lines.size) {
                val line = lines[lineIdx].trim()
                if (line == "STEPS_V1" || line == "LOOM_PRESET_V1" || line.isEmpty()) break
                
                val value = line.toFloatOrNull() ?: 0f
                params[lineIdx - 1] = value
                lineIdx++
            }
            
            // Create a base track state with these parameters
            var state = TrackState(id = -1, parameters = params)
            
            // Check for steps
            if (lineIdx < lines.size && lines[lineIdx].trim() == "STEPS_V1") {
                lineIdx++
                if (lineIdx < lines.size) {
                    val numSteps = lines[lineIdx].trim().toIntOrNull() ?: 0
                    lineIdx++
                    val newSteps = MutableList(64) { StepState() }
                    for (i in 0 until numSteps) {
                        if (lineIdx >= lines.size) break
                        val parts = lines[lineIdx].trim().split(" ")
                        if (parts.size >= 4) {
                            val active = parts[0] == "1"
                            val velocity = parts[1].toFloatOrNull() ?: 0.8f
                            val gate = parts[2].toFloatOrNull() ?: 1.0f
                            val noteCount = parts[3].toIntOrNull() ?: 0
                            val notes = mutableListOf<Int>()
                            for (j in 0 until noteCount) {
                                if (4 + j < parts.size) {
                                    val note = parts[4 + j].toIntOrNull()
                                    if (note != null) notes.add(note)
                                }
                            }
                            newSteps[i % 64] = StepState(active = active, notes = notes, velocity = velocity, gate = gate)
                        }
                        lineIdx++
                    }
                    state = state.copy(steps = newSteps)
                }
            }
            return state
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun listTrackPresets(context: Context, engineType: EngineType): List<String> {
        val engineDir = File(File(getLoomFolder(context), "Presets"), engineType.name)
        if (!engineDir.exists()) return emptyList()
        return engineDir.listFiles { _, name -> name.endsWith(".gbp") }?.map { it.name.removeSuffix(".gbp") } ?: emptyList()
    }

    fun saveSequence(context: Context, trackState: com.groovebox.TrackState, name: String) {
        try {
            // Save as a partial TrackState or a specialized Sequence object? 
            // For simplicity, we save the whole TrackState but lazily only load what we need.
            val seqDir = File(getLoomFolder(context), "Sequences")
            if (!seqDir.exists()) seqDir.mkdirs()
            
            val safeName = if (name.endsWith(".gbs")) name else "$name.gbs"
            val file = File(seqDir, safeName)
            ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(trackState) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun listSequences(context: Context): List<String> {
        val seqDir = File(getLoomFolder(context), "Sequences")
        if (!seqDir.exists()) return emptyList()
        return seqDir.listFiles { _, name -> name.endsWith(".gbs") }?.map { it.name.removeSuffix(".gbs") } ?: emptyList()
    }

    fun loadSequence(context: Context, targetTrack: com.groovebox.TrackState, name: String): com.groovebox.TrackState? {
        try {
            val seqDir = File(getLoomFolder(context), "Sequences")
            val file = File(seqDir, if (name.endsWith(".gbs")) name else "$name.gbs")
            if (!file.exists()) return null
            
            // Try Text Load first
            val textLoad = loadTrackPresetFromText(file)
            val sourceTrack = textLoad ?: ObjectInputStream(FileInputStream(file)).use { it.readObject() as? TrackState }
            if (sourceTrack == null) return null
            
            // Compatibility Check
            // If engines match, we can keep everything (including P-Locks)
            // If incompatible, we keep Steps (Notes) but strip P-locks
            
            val isCompatible = targetTrack.engineType == sourceTrack.engineType
            
            // If completely different types (e.g. Drum vs Synth), we might need to map notes?
            // For now, raw copy of steps is usually "safe" enough, just might sound weird.
            // But P-Locks MUST be stripped if incompatible to avoid crashing/weirdness.
            
            // Deep copy / Transfer logic
            return if (isCompatible) {
                targetTrack.copy(
                    steps = sourceTrack.steps,
                    drumSteps = sourceTrack.drumSteps,
                    numPages = sourceTrack.numPages,
                    stepsPerPage = sourceTrack.stepsPerPage,
                    arpConfig = sourceTrack.arpConfig
                )
            } else {
                 // Check if loading Melodic -> Drum (FM_DRUM)
                 if (targetTrack.engineType == EngineType.FM_DRUM && sourceTrack.engineType != EngineType.FM_DRUM) {
                      // Map Melodic Steps to Drum Lanes
                      // Smart Mapping: Map notes 60-67 to Lanes 0-7. Else map to Lane 0 (Kick).
                      // Note: TrackState.drumSteps is List<List<StepState>> (8 lanes x 64 steps) usually
                      val newDrumSteps = MutableList(8) { MutableList(64) { StepState() } }
                      
                      sourceTrack.steps.forEachIndexed { i, step ->
                          if (step.active) {
                              if (step.notes.isNotEmpty()) {
                                  step.notes.forEach { note ->
                                      val lane = if (note in 60..67) note - 60 else 0
                                      // Copy step properties but strip locks/notes
                                      newDrumSteps[lane][i] = step.copy(notes = emptyList(), parameterLocks = emptyMap())
                                  }
                              } else {
                                  // Active but no notes? Map to Kick
                                  newDrumSteps[0][i] = step.copy(notes = emptyList(), parameterLocks = emptyMap())
                              }
                          }
                      }
                      
                      return targetTrack.copy(
                          drumSteps = newDrumSteps, // We must cast/convert to Immutable List?
                          steps = List(64) { StepState() },
                          numPages = sourceTrack.numPages,
                          stepsPerPage = sourceTrack.stepsPerPage,
                          arpConfig = sourceTrack.arpConfig
                      )
                 }
                 // Check if loading Drum -> Melodic
                 else if (sourceTrack.engineType == EngineType.FM_DRUM && targetTrack.engineType != EngineType.FM_DRUM) {
                      // Flatten Drum Lanes to Single Melodic Sequence
                      val newSteps = MutableList(64) { StepState() }
                      for (i in 0 until 64) {
                          val activeNotes = mutableListOf<Int>()
                          var maxVel = 0f
                          
                          // Check all lanes
                          // sourceTrack.drumSteps might be empty if not initialized? Safe check.
                          if (sourceTrack.drumSteps.isNotEmpty()) {
                              sourceTrack.drumSteps.forEachIndexed { lane, laneSteps ->
                                  val step = laneSteps.getOrNull(i)
                                  if (step?.active == true) {
                                      activeNotes.add(60 + lane)
                                      if (step.velocity > maxVel) maxVel = step.velocity
                                  }
                              }
                          }
                          
                          if (activeNotes.isNotEmpty()) {
                              newSteps[i] = StepState(
                                  active = true, 
                                  notes = activeNotes, 
                                  velocity = if (maxVel > 0f) maxVel else 0.8f,
                                  isSkipped = false // Simplify
                              )
                          }
                      }
                      
                      return targetTrack.copy(
                          steps = newSteps,
                          drumSteps = List(16) { List(64) { StepState() } }, // Clear drums
                          numPages = sourceTrack.numPages,
                          stepsPerPage = sourceTrack.stepsPerPage,
                          arpConfig = sourceTrack.arpConfig
                      )
                 }

                 // Default Fallback: Strip P-Locks from Steps
                 val cleanSteps = sourceTrack.steps.map { s -> s.copy(parameterLocks = emptyMap()) }
                 val cleanDrumSteps = sourceTrack.drumSteps.map { ds -> ds.map { s -> s.copy(parameterLocks = emptyMap()) } }
                 
                 return targetTrack.copy(
                    steps = cleanSteps,
                    drumSteps = cleanDrumSteps,
                    numPages = sourceTrack.numPages,
                    stepsPerPage = sourceTrack.stepsPerPage,
                    arpConfig = sourceTrack.arpConfig
                 )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
        }
}
