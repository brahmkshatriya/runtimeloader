package dev.brahmkshatriya.runtimeloader.fixture

public interface GreetingExtension {
    public fun greet(name: String): String
    public fun incrementHostCounter(): Int
}

public object FixtureHostState {
    public var counter: Int = 0
}
