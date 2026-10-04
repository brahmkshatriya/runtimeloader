package dev.brahmkshatriya.runtimeloader

import dalvik.system.DexClassLoader
import java.io.File

public class AndroidLoadedCode internal constructor(
    override val path: String,
    public val classLoader: DexClassLoader,
) : LoadedCode {
    private var closed: Boolean = false

    override val isClosed: Boolean
        get() = closed

    override fun close() {
        // ART has no supported explicit DexClassLoader unload operation.
        closed = true
    }
}

public object AndroidRuntimeLoader {
    public fun load(
        path: String,
        optimizedDirectory: String = File(File(path).canonicalFile.parentFile, ".runtime-loader-opt").absolutePath,
        parent: ClassLoader = AndroidRuntimeLoader::class.java.classLoader!!,
    ): AndroidLoadedCode {
        val file = File(path).canonicalFile
        if (!file.isFile) throw RuntimeLoaderException("Android code artifact not found: $file")
        File(optimizedDirectory).mkdirs()
        return AndroidLoadedCode(
            path = file.absolutePath,
            classLoader = DexClassLoader(file.absolutePath, optimizedDirectory, null, parent),
        )
    }
}

public actual object RuntimeCodeLoader {
    public actual fun load(
        path: String,
        onLoaded: (LoadedCode) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        try {
            onLoaded(AndroidRuntimeLoader.load(path))
        } catch (failure: Throwable) {
            onError(failure)
        }
    }
}

public actual fun LoadedCode.createEntryUntyped(): Any {
    val loaded = this as? AndroidLoadedCode
        ?: throw RuntimeLoaderException("Expected an Android loaded-code handle")
    check(!loaded.isClosed) { "Loaded code handle is closed" }
    return loaded.classLoader
        .loadClass(RuntimeLoaderEntryPoint.CLASS_NAME)
        .getDeclaredConstructor()
        .newInstance()
}
