package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@DisableCachingByDefault(because = "Assembles files from the Kotlin/Wasm npm workspace outside the declared package directories")
abstract class WasmJsBrowserDistributionTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hostPackageDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleSyncDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val hostModuleName: Property<String>

    @get:Input
    abstract val moduleName: Property<String>

    @TaskAction
    fun buildDistribution() {
        val hostDir = hostPackageDirectory.get().asFile
        val moduleDir = moduleSyncDirectory.get().asFile
        val output = outputDirectory.get().asFile
        val hostModule = hostModuleName.get()
        val moduleNameValue = moduleName.get()

        output.deleteRecursively()
        output.mkdirs()
        hostDir.copyRecursively(output, overwrite = true)

        val wasmRoot = hostDir.parentFile.parentFile.parentFile
        val jsJoda = File(wasmRoot, "node_modules/@js-joda/core/dist/js-joda.esm.js")
        if (!jsJoda.isFile) {
            throw GradleException("Wasm npm dependency is missing: $jsJoda")
        }
        val npmDirectory = File(output, "npm").apply { mkdirs() }
        jsJoda.copyTo(File(npmDirectory, "js-joda-core.mjs"), overwrite = true)

        listOf(
            "$moduleNameValue.mjs",
            "$moduleNameValue.import-object.mjs",
            "$moduleNameValue.wasm",
            "$moduleNameValue.wasm.map",
        ).forEach { name ->
            val source = File(moduleDir, name)
            if (name.endsWith(".wasm.map") && !source.isFile) return@forEach
            if (!source.isFile) throw GradleException("Wasm module file is missing: $source")
            source.copyTo(File(output, name), overwrite = true)
        }

        File(output, "index.html").writeText(
            """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Kotlin Runtime Loader Wasm demo</title>
              <script type="importmap">
                {
                  "imports": {
                    "@js-joda/core": "./npm/js-joda-core.mjs"
                  }
                }
              </script>
              <style>
                html, body { margin: 0; width: 100%; height: 100%; overflow: hidden; }
              </style>
            </head>
            <body>
              <script type="module">
                globalThis.runtimeLoaderResult = 'LOADING';
                globalThis.runtimeLoaderModulePath = './$moduleNameValue.mjs';
                document.documentElement.dataset.runtimeLoaderResult = 'LOADING';
                const timer = setInterval(() => {
                  document.documentElement.dataset.runtimeLoaderResult = String(globalThis.runtimeLoaderResult);
                  if (globalThis.runtimeLoaderResult === 'COMPOSE_PASS') clearInterval(timer);
                }, 25);
                import('./$hostModule.mjs').catch(error => {
                  console.error(error);
                  globalThis.runtimeLoaderResult = 'FAIL: ' + error;
                });
              </script>
            </body>
            </html>
            """.trimIndent() + "\n"
        )

        logger.lifecycle("[runtime-loader] Wasm browser distribution: $output")
    }
}
