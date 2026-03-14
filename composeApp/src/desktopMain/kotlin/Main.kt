import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.groovebox.GrooveboxViewModel
import com.groovebox.NativeLib
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
            // Map specific keys to the 4x4 pad grid indices (0-15)
            val keyToPadMap = mapOf(
                Key.One to 0, Key.Two to 1, Key.Three to 2, Key.Four to 3,
                Key.Q to 4, Key.W to 5, Key.E to 6, Key.R to 7,
                Key.A to 8, Key.S to 9, Key.D to 10, Key.F to 11,
                Key.Z to 12, Key.X to 13, Key.C to 14, Key.V to 15
            )

            val padIndex = keyToPadMap[event.key]
            
            if (padIndex != null) {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        // Debounce and held-state check
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
                true // Consume the matched keys
            } else {
                false // Ignore unmatched keys
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
