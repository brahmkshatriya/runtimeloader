package dev.brahmkshatriya.runtimeloader.extensions.counter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.runtimeloader.demo.Plugin
import dev.brahmkshatriya.runtimeloader.demo.ExtensionEntry
import dev.brahmkshatriya.runtimeloader.demo.diagnosticMode
import dev.brahmkshatriya.runtimeloader.demo.pluginRenderCount
import dev.brahmkshatriya.runtimeloader.demo.sharedCounter

@ExtensionEntry
public class CounterExtension : Plugin {
    override fun increment() {
        sharedCounter++
        println("counter extension: sharedCounter is now $sharedCounter")
    }

    @Composable
    override fun BoxScope.Content() {
        pluginRenderCount++
        if (diagnosticMode) {
            println("runtime-loaded composable: rendering with sharedCounter=$sharedCounter")
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Counter Extension")
            Text("Extension sees counter: $sharedCounter")
            Button(onClick = {
                sharedCounter++
                println("counter extension composable: sharedCounter is now $sharedCounter")
            }) {
                Text("Increment from extension")
            }
        }
    }
}
