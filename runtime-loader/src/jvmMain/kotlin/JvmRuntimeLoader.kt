package dev.brahmkshatriya.runtimeloader

import java.io.File
import java.net.URLClassLoader

public class JvmLoadedCode internal constructor(
    override val path: String,
    public val classLoader: URLClassLoader,
) : LoadedCode, AutoCloseable {
    private var closed: Boolean = false

    override val isClosed: Boolean
        get() = closed

    override fun close() {
        if (closed) return
        closed = true
        classLoader.close()
    }
}

public object JvmRuntimeLoader {
    public fun load(
        path: String,
        parent: ClassLoader = JvmRuntimeLoader::class.java.classLoader,
    ): JvmLoadedCode {
        val file = File(path).canonicalFile
        if (!file.isFile) throw RuntimeLoaderException("JVM code artifact not found: $file")
        return JvmLoadedCode(
            path = file.absolutePath,
            classLoader = URLClassLoader(arrayOf(file.toURI().toURL()), parent),
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
            onLoaded(JvmRuntimeLoader.load(path))
        } catch (failure: Throwable) {
            onError(failure)
        }
    }
}

public actual fun LoadedCode.createEntryUntyped(): Any {
    val loaded = this as? JvmLoadedCode
        ?: throw RuntimeLoaderException("Expected a JVM loaded-code handle")
    check(!loaded.isClosed) { "Loaded code handle is closed" }
    return loaded.classLoader
        .loadClass(RuntimeLoaderEntryPoint.CLASS_NAME)
        .getDeclaredConstructor()
        .newInstance()
}
