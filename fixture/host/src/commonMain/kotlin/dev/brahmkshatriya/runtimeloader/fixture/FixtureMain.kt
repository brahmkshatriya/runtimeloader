package dev.brahmkshatriya.runtimeloader.fixture

import dev.brahmkshatriya.runtimeloader.RuntimeCodeLoader
import dev.brahmkshatriya.runtimeloader.createEntry

public fun main(args: Array<String>) {
    val modulePath = args.firstOrNull() ?: error("Pass the runtime module artifact path")
    FixtureHostState.counter = 0
    RuntimeCodeLoader.load(
        path = modulePath,
        onLoaded = { code ->
            val entry = code.createEntry<GreetingExtension>()
            check(entry.greet("Echo") == "runtime hello, Echo")
            check(entry.incrementHostCounter() == 7)
            check(FixtureHostState.counter == 7) {
                "Runtime-loaded entry did not mutate the exact host-owned contract state"
            }
            code.close()
            code.close()
            check(code.isClosed)
            println("PASS: consumer-owned GreetingExtension loaded with typed createEntry and shared host state")
        },
        onError = { throw it },
    )
}
