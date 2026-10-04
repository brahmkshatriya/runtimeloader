package dev.brahmkshatriya.runtimeloader.publishedfixture

import dev.brahmkshatriya.runtimeloader.RuntimeCodeLoader
import dev.brahmkshatriya.runtimeloader.createEntry

public fun main(args: Array<String>) {
    val modulePath = args.firstOrNull() ?: error("Pass the runtime module artifact path")
    HostState.counter = 0
    RuntimeCodeLoader.load(
        path = modulePath,
        onLoaded = { code ->
            val entry = code.createEntry<GreetingExtension>()
            check(entry.greet("Echo") == "published hello, Echo")
            check(entry.incrementHostCounter() == 11)
            check(HostState.counter == 11)
            code.close()
            check(code.isClosed)
            println("PASS: isolated repository consumer resolved published Runtime Loader artifacts")
        },
        onError = { throw it },
    )
}
