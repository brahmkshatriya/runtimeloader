package dev.brahmkshatriya.runtimeloader.publishedfixture

public class GreetingExtensionImpl : GreetingExtension {
    override fun greet(name: String): String = "published hello, $name"
    override fun incrementHostCounter(): Int {
        HostState.counter += 11
        return HostState.counter
    }
}
