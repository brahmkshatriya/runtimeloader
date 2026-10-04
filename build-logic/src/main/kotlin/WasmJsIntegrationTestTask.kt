package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@DisableCachingByDefault(because = "Executes Node and mutates a generated Kotlin/Wasm package for integration verification")
abstract class WasmJsIntegrationTestTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hostPackageDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleSyncDirectory: DirectoryProperty

    @get:Input
    abstract val hostModuleName: Property<String>

    @get:Input
    abstract val moduleName: Property<String>

    @get:Input
    abstract val runnerExport: Property<String>

    @TaskAction
    fun verify() {
        val hostDir = hostPackageDirectory.get().asFile
        val moduleDir = moduleSyncDirectory.get().asFile
        val moduleNameValue = moduleName.get()
        val required = listOf(
            "$moduleNameValue.mjs",
            "$moduleNameValue.import-object.mjs",
            "$moduleNameValue.wasm",
        )
        required.forEach { name ->
            val source = File(moduleDir, name)
            if (!source.isFile) throw GradleException("Wasm module file is missing: $source")
            source.copyTo(File(hostDir, name), overwrite = true)
        }
        File(moduleDir, "$moduleNameValue.wasm.map").takeIf(File::isFile)
            ?.copyTo(File(hostDir, "$moduleNameValue.wasm.map"), overwrite = true)

        val smoke = File(hostDir, ".runtime-loader-$moduleNameValue-smoke.mjs")
        smoke.writeText(
            """
            import { readFile } from 'node:fs/promises';
            import { readFileSync } from 'node:fs';
            import { fileURLToPath } from 'node:url';

            // Compose/Skiko's Wasm runtime is emitted for a web environment even when the Kotlin
            // host module is exercised under Node. Give its Emscripten bootstrap the tiny subset of
            // browser file I/O it needs so merely importing Compose-linked modules does not abort.
            globalThis.window = {};
            globalThis.XMLHttpRequest = class {
              open(method, url, async = true) {
                this.url = url;
                this.async = async;
                this.status = 0;
                this.response = null;
              }
              send() {
                const complete = bytes => {
                  this.status = 200;
                  this.response = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
                  this.onload?.();
                };
                const fail = error => this.onerror?.(error);
                if (this.async) {
                  readFile(fileURLToPath(this.url)).then(complete, fail);
                } else {
                  try { complete(readFileSync(fileURLToPath(this.url))); } catch (error) { fail(error); }
                }
              }
            };

            const nativeFetch = globalThis.fetch;
            globalThis.fetch = async (input, init) => {
              const url = input instanceof Request ? input.url : String(input);
              if (url.startsWith('file:')) {
                const bytes = await readFile(fileURLToPath(url));
                return new Response(bytes, { status: 200 });
              }
              return nativeFetch(input, init);
            };

            globalThis.runtimeLoaderResult = null;
            const host = await import('./${hostModuleName.get()}.mjs');
            const runModule = host[${jsString(runnerExport.get())}];
            if (typeof runModule !== 'function') {
              throw new Error('Host does not export ${runnerExport.get()}');
            }
            runModule('./$moduleNameValue.mjs');
            for (let i = 0; i < 200 && globalThis.runtimeLoaderResult == null; i++) {
              await new Promise(resolve => setTimeout(resolve, 25));
            }
            if (globalThis.runtimeLoaderResult !== 'PASS') {
              throw new Error(`Wasm runtime loader failed: ${'$'}{globalThis.runtimeLoaderResult}`);
            }
            console.log('PASS: Wasm module dynamically imported against the existing host modules');
            """.trimIndent() + "\n"
        )
        try {
            runCommand(listOf(requireTool("node"), smoke.absolutePath), cwd = hostDir)
        } finally {
            smoke.delete()
        }
    }
}

private fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }
    append('"')
}
