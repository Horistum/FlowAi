package org.flowlang.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The distribution must consume the separately compiled kernel, never a duplicate. */
class SemanticKernelCompositionTests {
    @Test
    fun compilerCompositionLoadsGraphAndDigestFromTheSeparateKernelOutput() {
        val graph = CanonicalExecutionGraph::class.java.protectionDomain.codeSource.location
        val digest = CanonicalExecutionGraphDigestComputer::class.java.protectionDomain.codeSource.location
        val compiler = FlowCompilationService::class.java.protectionDomain.codeSource.location
        assertEquals(graph, digest)
        assertNotEquals(compiler, graph, "The root product must not compile the kernel sources again.")
    }
}
