package com.groovebox.persistence

import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.TrackState
import java.io.File

expect object PersistenceManager {
    fun getLoomFolder(): File
    fun saveProject(state: GrooveboxState, fileName: String)
    fun loadProject(fileName: String): GrooveboxState?
    fun listProjects(): List<String>
    fun deleteProject(fileName: String): Boolean
    fun copyProject(fileName: String): Boolean
    fun clearAssignments()
    fun saveTrackPreset(trackState: TrackState, name: String)
    fun loadTrackPreset(engineType: EngineType, name: String): TrackState?
    fun listTrackPresets(engineType: EngineType): List<String>
    fun saveSequence(trackState: TrackState, name: String)
    fun listSequences(): List<String>
    fun loadSequence(targetTrack: TrackState, name: String): TrackState?
    fun loadMidiSequence(targetTrack: TrackState, path: String): TrackState?
    fun copyWavetablesToFilesDir()
    fun copySoundFontsToFilesDir()
    fun copyDefaultsToFilesDir()
    fun migrateToExternalStorage()
}
