package dev.brahmkshatriya.runtimeloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RuntimeCodeVerifierTest {
    @Test
    fun verifierFailureIsDeliveredOnceAndPreventsPlatformLoading() {
        val rejection = RuntimeLoaderException("untrusted fixture")
        var verifierCalls = 0
        var errorCalls = 0
        var observed: Throwable? = null

        RuntimeCodeLoader.loadVerified(
            path = "this-path-must-never-reach-a-platform-loader",
            verifier = RuntimeCodeVerifier {
                verifierCalls++
                throw rejection
            },
            onLoaded = { error("Verifier rejection must prevent platform loading") },
            onError = {
                errorCalls++
                observed = it
            },
        )

        assertEquals(1, verifierCalls)
        assertEquals(1, errorCalls)
        assertSame(rejection, observed)
    }
}
