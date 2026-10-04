package dev.brahmkshatriya.runtimeloader.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

public var sharedCounter: Int by mutableIntStateOf(0)

// Non-state diagnostics owned by the host. Plugin code mutates these exact globals when its
// composable is entered, which lets every runtime prove shared host identity without causing an
// endless recomposition loop.
public var pluginRenderCount: Int = 0
public var diagnosticMode: Boolean = false

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class ExtensionEntry

public interface Plugin {
    public fun increment()

    @Composable
    public fun BoxScope.Content()
}

@Composable
public fun PluginHost(plugin: Plugin) {
    Box(modifier = Modifier.fillMaxSize()) {
        with(plugin) {
            Content()
        }
    }
}
