package dev.brahmkshatriya.runtimeloader.publishedfixture

public interface GreetingExtension {
    public fun greet(name: String): String
    public fun incrementHostCounter(): Int
}

public object HostState {
    public var counter: Int = 0
}
