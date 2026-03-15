package com.groovebox.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SoundFontCreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SoundFont Credits") },
        text = {
            Column {
                Text("Loom includes high-quality SoundFonts from various creators.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("• GeneralUser GS - S. Christian Collins")
                Text("• Personal Copy - various sources")
                Text("• Roland SC-55 - Roland Corp (sampled/converted)")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("OK") }
        }
    )
}
