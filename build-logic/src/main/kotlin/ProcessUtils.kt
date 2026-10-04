package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class CommandResult(
    val exitCode: Int,
    val output: String,
)

internal fun runCommand(
    command: List<String>,
    cwd: File? = null,
    capture: Boolean = false,
    check: Boolean = true,
): CommandResult {
    val prepared = prepareProcessCommand(command)
    try {
        val builder = ProcessBuilder(prepared.command)
        if (cwd != null) builder.directory(cwd)
        builder.redirectErrorStream(true)
        val environment = builder.environment()
        val javaHome = System.getProperty("java.home")
        environment["JAVA_HOME"] = javaHome
        environment["PATH"] = "$javaHome/bin${File.pathSeparator}${environment["PATH"].orEmpty()}"

        val process = builder.start()
        val output = if (capture) {
            process.inputStream.readBytes().toString(Charsets.UTF_8)
        } else {
            process.inputStream.copyTo(System.out)
            ""
        }
        val exitCode = process.waitFor()
        if (check && exitCode != 0) {
            throw GradleException(
                "Command failed ($exitCode): ${command.joinToString(" ")}" +
                    if (output.isNotBlank()) "\n$output" else ""
            )
        }
        return CommandResult(exitCode, output)
    } finally {
        prepared.responseFile?.delete()
    }
}

internal data class TimedCommandResult(
    val finished: Boolean,
    val exitCode: Int?,
    val output: String,
)

internal fun runCommandWithTimeout(
    command: List<String>,
    timeoutSeconds: Double,
): TimedCommandResult {
    val processCommand = platformProcessCommand(command)
    val builder = ProcessBuilder(processCommand).redirectErrorStream(true)
    val environment = builder.environment()
    val javaHome = System.getProperty("java.home")
    environment["JAVA_HOME"] = javaHome
    environment["PATH"] = "$javaHome/bin${File.pathSeparator}${environment["PATH"].orEmpty()}"
    val process = builder.start()
    val buffer = ByteArrayOutputStream()
    val reader = thread(start = true, isDaemon = true, name = "runtime-loader-process-output") {
        process.inputStream.use { it.copyTo(buffer) }
    }
    val finished = process.waitFor((timeoutSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    val exitCode = if (finished) process.exitValue() else null
    if (!finished) {
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
    }
    reader.join(1500)
    return TimedCommandResult(finished, exitCode, buffer.toString(Charsets.UTF_8))
}

internal fun requireTool(name: String): String {
    val names = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true) && !name.endsWith(".exe")) {
        listOf(name, "$name.exe")
    } else {
        listOf(name)
    }
    val path = System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .asSequence()
        .flatMap { directory -> names.asSequence().map { executable -> File(directory, executable) } }
        .firstOrNull { it.isFile && (it.canExecute() || System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) }
    return path?.absolutePath ?: throw GradleException("Required tool is not on PATH: $name")
}


internal fun resolveKonanTool(konanHome: File, name: String): File {
    val bin = File(konanHome, "bin")
    val windows = isWindowsHost()
    val candidates = if (windows) {
        listOf("$name.bat", "$name.cmd", "$name.exe", name)
    } else {
        listOf(name)
    }
    return candidates.asSequence()
        .map { File(bin, it) }
        .firstOrNull(File::isFile)
        ?: throw GradleException(
            "Kotlin/Native tool '$name' is missing under ${bin.absolutePath}; tried ${candidates.joinToString()}"
        )
}

private fun platformProcessCommand(command: List<String>): List<String> {
    require(command.isNotEmpty()) { "Command must not be empty" }
    if (!isWindowsHost()) return command
    val extension = File(command.first()).extension.lowercase()
    if (extension !in setOf("bat", "cmd")) return command

    // Java ProcessBuilder ultimately calls CreateProcess on Windows, which cannot execute .bat/.cmd
    // launchers directly. Kotlin/Native ships tools such as klib and kotlinc-native as batch
    // wrappers, so route those commands through the system command processor while retaining each
    // original argument as a quoted token. The outer quotes are required by `cmd /s /c` when the
    // executable path itself is quoted.
    val commandLine = command.joinToString(" ") { windowsCmdQuote(it) }
    return listOf("cmd.exe", "/d", "/s", "/c", "\"$commandLine\"")
}

private data class PreparedProcessCommand(
    val command: List<String>,
    val responseFile: File? = null,
)

private fun prepareProcessCommand(command: List<String>): PreparedProcessCommand {
    require(command.isNotEmpty()) { "Command must not be empty" }
    if (!isWindowsHost() || !isKotlinNativeBatchCompiler(command.first()) || estimatedCommandLength(command) < 7000) {
        return PreparedProcessCommand(platformProcessCommand(command))
    }

    // cmd.exe has an ~8 KiB command-line ceiling. Runtime Loader's cache-enabled MinGW builds can
    // exceed that once the compiler is passed dozens of -library/-Xcached-library pairs.
    // Kotlin/Native supports @argfile expansion itself, so keep the batch invocation short.
    val responseFile = File.createTempFile("runtime-loader-konanc-", ".args")
    responseFile.writeText(
        command.drop(1).joinToString("\r\n", postfix = "\r\n", transform = ::kotlinCompilerResponseArgument)
    )
    return PreparedProcessCommand(
        command = platformProcessCommand(listOf(command.first(), "@${responseFile.absolutePath}")),
        responseFile = responseFile,
    )
}

private fun isKotlinNativeBatchCompiler(path: String): Boolean {
    val file = File(path)
    if (file.extension.lowercase() !in setOf("bat", "cmd")) return false
    return file.nameWithoutExtension.startsWith("kotlinc-native", ignoreCase = true)
}

private fun estimatedCommandLength(command: List<String>): Int =
    command.sumOf { it.length + 3 }

private fun kotlinCompilerResponseArgument(value: String): String =
    if (value.none(Char::isWhitespace) && '"' !in value) value
    else "\"${value.replace("\"", "\\\"")}\""

private fun windowsCmdQuote(value: String): String =
    "\"" + value.replace("%", "%%").replace("\"", "\"\"") + "\""

private fun isWindowsHost(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
