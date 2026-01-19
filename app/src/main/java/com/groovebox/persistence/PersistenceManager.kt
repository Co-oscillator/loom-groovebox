package com.groovebox.persistence

import android.content.Context
import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.StripRouting
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

import java.io.*

object PersistenceManager {
    private const val FILENAME = "strip_assignments.json"
    private const val PROJECTS_DIR = "Projects"
    private const val LOOM_ROOT = "Loom"

    fun getLoomFolder(context: Context): File {
        try {
            // Priority 1: SD Card Root (visible to user)
            val root = File(android.os.Environment.getExternalStorageDirectory(), LOOM_ROOT)
            if (root.exists() || root.mkdirs()) return root
            
            // Priority 2: Public Documents (visible to user, more standard)
            val docs = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), LOOM_ROOT)
            if (docs.exists() || docs.mkdirs()) return docs
            
            // Priority 3: App External Files (visible to user via Android/data)
            val appExt = context.getExternalFilesDir(null)
            if (appExt != null) {
                val loom = File(appExt, LOOM_ROOT)
                if (loom.exists() || loom.mkdirs()) return loom
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Last resort: context.filesDir (Internal, not visible without root)
        return context.filesDir
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
            ObjectInputStream(FileInputStream(file)).use { return it.readObject() as? GrooveboxState }
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

    private fun copyAssetsToFilesDir(context: Context, dirName: String) {
        try {
            val destDir = File(getLoomFolder(context), dirName)
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            val assets = context.assets.list(dirName) ?: return
            for (assetName in assets) {
                val outFile = File(destDir, assetName)
                if (!outFile.exists()) {
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
}
