import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.groovebox.GrooveboxViewModel
import com.groovebox.NativeLib
import com.groovebox.GridMode
import com.groovebox.ui.views.MainScreen
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import java.io.File

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.*
import androidx.compose.ui.unit.dp

fun main() = application {
    val heldKeys = remember { mutableSetOf<Key>() }
    val nativeLib = remember { NativeLib() }
    val viewModel = remember { GrooveboxViewModel(nativeLib) }
    val audioCapture = remember { com.groovebox.DesktopAudioCapture() }

    val windowState = rememberWindowState(
        width = 1200.dp,
        height = 800.dp
    )

    Window(
        onCloseRequest = ::exitApplication, 
        state = windowState,
        title = "Loom Groovebox",
        icon = painterResource("icon.png"),
        onKeyEvent = { event ->
            val currentState = viewModel.state
            
            val keyToPadMap = when (currentState.gridMode) {
                GridMode.GRID_4X4 -> mapOf(
                    Key.One to 0, Key.Two to 1, Key.Three to 2, Key.Four to 3,
                    Key.Q to 4, Key.W to 5, Key.E to 6, Key.R to 7,
                    Key.A to 8, Key.S to 9, Key.D to 10, Key.F to 11,
                    Key.Z to 12, Key.X to 13, Key.C to 14, Key.V to 15
                )
                GridMode.GRID_6X6 -> mapOf(
                    // Mapping middle 4 rows of 6x6 (starting with index 0? No, let's map to screen layout)
                    // 6x6 indices: 0-5, 6-11, 12-17, 18-23, 24-29, 30-35
                    // Middle four rows: 6-11, 12-17, 18-23, 24-29
                    Key.One to 6, Key.Two to 7, Key.Three to 8, Key.Four to 9, Key.Five to 10, Key.Six to 11,
                    Key.Q to 12, Key.W to 13, Key.E to 14, Key.R to 15, Key.T to 16, Key.Y to 17,
                    Key.A to 18, Key.S to 19, Key.D to 20, Key.F to 21, Key.G to 22, Key.H to 23,
                    Key.Z to 24, Key.X to 25, Key.C to 26, Key.V to 27, Key.B to 28, Key.N to 29
                )
                GridMode.MAC_KEYS -> mapOf(
                    // Numbers
                    Key.One to 0, Key.Two to 1, Key.Three to 2, Key.Four to 3, Key.Five to 4, 
                    Key.Six to 5, Key.Seven to 6, Key.Eight to 7, Key.Nine to 8, Key.Zero to 9,
                    Key.Minus to 10, Key.Equals to 11,
                    // QWERTY
                    Key.Q to 12, Key.W to 13, Key.E to 14, Key.R to 15, Key.T to 16, 
                    Key.Y to 17, Key.U to 18, Key.I to 19, Key.O to 20, Key.P to 21,
                    Key.LeftBracket to 22, Key.RightBracket to 23, Key.Backslash to 24,
                    // ASDF
                    Key.A to 25, Key.S to 26, Key.D to 27, Key.F to 28, Key.G to 29, 
                    Key.H to 30, Key.J to 31, Key.K to 32, Key.L to 33, Key.Semicolon to 34,
                    Key.Apostrophe to 35,
                    // ZXCV
                    Key.Z to 36, Key.X to 37, Key.C to 38, Key.V to 39, Key.B to 40, 
                    Key.N to 41, Key.M to 42, Key.Comma to 43, Key.Period to 44, Key.Slash to 45
                )
                else -> emptyMap()
            }

            val padIndex = keyToPadMap[event.key]
            
            if (padIndex != null) {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (heldKeys.add(event.key)) {
                            viewModel.triggerPad(padIndex, 100)
                        }
                    }
                    KeyEventType.KeyUp -> {
                        if (heldKeys.remove(event.key)) {
                            viewModel.releasePad(padIndex)
                        }
                    }
                }
                true
            } else if (event.key == Key.Minus && event.type == KeyEventType.KeyDown) {
                val newRoot = (currentState.rootNote - 12).coerceIn(0, 72)
                viewModel.onStateChange(currentState.copy(rootNote = newRoot))
                nativeLib.setScaleConfig(newRoot, currentState.scaleIntervals.toIntArray())
                true
            } else if ((event.key == Key.Equals || event.key == Key.Plus) && event.type == KeyEventType.KeyDown) {
                val newRoot = (currentState.rootNote + 12).coerceIn(0, 72)
                viewModel.onStateChange(currentState.copy(rootNote = newRoot))
                nativeLib.setScaleConfig(newRoot, currentState.scaleIntervals.toIntArray())
                true
            } else if (event.key == Key.Spacebar && event.type == KeyEventType.KeyDown) {
                val isShift = event.isShiftPressed
                if (isShift) {
                    // Start + Record
                    val newState = currentState.copy(
                        isPlaying = true,
                        isRecording = true
                    )
                    viewModel.onStateChange(newState)
                } else {
                    // Toggle Start/Stop
                    viewModel.onStateChange(currentState.copy(isPlaying = !currentState.isPlaying))
                }
                true
            } else {
                false
            }
        }
    ) {

        LaunchedEffect(Unit) {
            val appDataDir = File(System.getProperty("user.home"), ".loom_groovebox")
            if (!appDataDir.exists()) appDataDir.mkdirs()

            nativeLib.init()
            nativeLib.setAppDataDir(appDataDir.absolutePath)
            nativeLib.loadAppState()
            
            for (i in 0 until 8) { nativeLib.clearSequencer(i) }
            
            viewModel.syncWithNative()
            nativeLib.start()
            audioCapture.startCapture()
        }

        DisposableEffect(Unit) {
            onDispose {
                audioCapture.stopCapture()
                nativeLib.stop()
            }
        }

        MainScreen(
            viewModel = viewModel,
            nativeLib = nativeLib
        )
    }
}
