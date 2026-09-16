import java.io.File
import kotlin.test.*
import org.flowlang.compiler.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.modules.ModuleRegistry

class CanonicalDependencyDigestBoundaryTests {
    private fun graph(): CanonicalExecutionGraph = IntentYamlFrontend(
        FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")))
    ).compile(File("examples/intent/build-test-deploy.intent.yaml")).requireAccepted().graph

    @Test fun everyDependencyEvidenceClassificationHasADistinctIdentity() {
        val graph = graph()
        val edge = graph.dependencyEdges.first()
        val variants = CanonicalDependencyEvidence.entries.map { evidence ->
            graph.copy(dependencyEdges = listOf(edge.copy(evidence = evidence)) + graph.dependencyEdges.drop(1))
        }
        val digests = variants.map(CanonicalExecutionGraphDigestComputer::digest)
        assertEquals(variants.size, digests.toSet().size)
        val baseline = CanonicalExecutionGraphDigestComputer.digest(graph)
        variants.filter { it.dependencyEdges.first().evidence != edge.evidence }.forEach { changed ->
            assertFailsWith<IllegalArgumentException> {
                CanonicalExecutionGraphDigestComputer.requireMatches(changed, baseline)
            }
        }
    }

    @Test fun dependencyPathAndItsOrderAreBoundToAuthorization() {
        val graph = graph()
        val edge = graph.dependencyEdges.first()
        val nodes = graph.nodes.take(2).map { it.id }
        assertEquals(2, nodes.size)
        fun withPath(path: List<CanonicalNodeId>) = graph.copy(
            dependencyEdges = listOf(edge.copy(path = path)) + graph.dependencyEdges.drop(1)
        )
        val variants = listOf(emptyList(), listOf(nodes[0]), nodes, nodes.reversed())
        assertEquals(variants.size, variants.map { CanonicalExecutionGraphDigestComputer.digest(withPath(it)) }.toSet().size)
    }

    @Test fun provenancePointersAndStorageOrderDoNotBecomeSemanticIdentity() {
        val graph = graph()
        val baseline = CanonicalExecutionGraphDigestComputer.digest(graph)
        val changed = graph.copy(
            nodes = graph.nodes.reversed(),
            dependencyEdges = graph.dependencyEdges.reversed().map {
                it.copy(evidenceReference = "test:relocated-provenance")
            }
        )
        assertEquals(baseline, CanonicalExecutionGraphDigestComputer.digest(changed))
        CanonicalExecutionGraphDigestComputer.requireMatches(changed, baseline)
    }
}
