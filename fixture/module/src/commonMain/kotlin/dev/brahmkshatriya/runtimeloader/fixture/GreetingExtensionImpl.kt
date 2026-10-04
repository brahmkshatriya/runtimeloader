package dev.brahmkshatriya.runtimeloader.fixture

public class GreetingExtensionImpl : GreetingExtension {
    override fun greet(name: String): String = "runtime hello, $name"

    override fun incrementHostCounter(): Int {
        FixtureHostState.counter += 7
        return FixtureHostState.counter
    }
}
