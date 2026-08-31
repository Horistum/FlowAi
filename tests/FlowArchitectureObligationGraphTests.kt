import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.notes.NotesPackageBoundary
import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind
import org.flowlang.notes.StandardNotesPackageContracts
import org.flowlang.obligations.ArchitectureObligationEdge
import org.flowlang.obligations.ArchitectureObligationEdgeKind
import org.flowlang.obligations.ArchitectureObligationGraph
import org.flowlang.obligations.ArchitectureObligationGraphStatus
import org.flowlang.obligations.ArchitectureObligationGraphValidator
import org.flowlang.obligations.ArchitectureObligationKind
import org.flowlang.obligations.ArchitectureObligationNode
import org.flowlang.obligations.StandardArchitectureObligationGraphs

class FlowArchitectureObligationGraphTests {
    private val notes = StandardNotesPackageContracts.baseline()
    private val validator = ArchitectureObligationGraphValidator(notes)

    @Test
    fun baselineArchitectureObligationGraphIsValidAndNotesBound() {
        val graph = StandardArchitectureObligationGraphs.baseline()
        val report = validator.validate(graph)

        assertEquals(ArchitectureObligationGraphStatus.PASS, report.status)
        assertTrue(report.valid)
        assertEquals(5, report.nodes)
        assertEquals(4, report.edges)
        assertTrue(graph.nodes.all { it.notesPackageId.startsWith("flow.") })
    }

    @Test
    fun architectureObligationNodesMustBindToDeclaredNotesPackageItems() {
        val graph = ArchitectureObligationGraph(
            graphId = "flow.semantic.bad-declaration",
            nodes = listOf(
                ArchitectureObligationNode(
                    id = "capability.unknown",
                    kind = ArchitectureObligationKind.CAPABILITY,
                    declaration = "not.declared",
                    notesPackageId = "flow.capability.core"
                )
            )
        )

        val report = validator.validate(graph)

        assertEquals(ArchitectureObligationGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.node.declaration.unbound" })
    }

    @Test
    fun architectureObligationNodeKindMustMatchNotesPackageKind() {
        val graph = ArchitectureObligationGraph(
            graphId = "flow.semantic.kind-mismatch",
            nodes = listOf(
                ArchitectureObligationNode(
                    id = "runtime.bad",
                    kind = ArchitectureObligationKind.RUNTIME_REQUIREMENT,
                    declaration = "approval.required",
                    notesPackageId = "flow.safety.core"
                )
            )
        )

        val report = validator.validate(graph)

        assertEquals(ArchitectureObligationGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.node.package.kind-mismatch" })
    }

    @Test
    fun universalArchitectureObligationGraphRejectsRawRuntimeMechanismDeclarations() {
        val rawNotes = NotesPackageContract(
            packageId = "flow.capability.raw-runtime",
            packageVersion = "0.9.5.7.2",
            kind = NotesPackageKind.CAPABILITY,
            description = "Test fixture for a forbidden raw runtime mechanism.",
            declaredCapabilities = setOf("shell.run"),
            boundaries = setOf(NotesPackageBoundary("test-only", "Used only to prove semantic mechanism rejection."))
        )
        val graph = ArchitectureObligationGraph(
            graphId = "flow.semantic.raw-runtime-leak",
            nodes = listOf(
                ArchitectureObligationNode(
                    id = "capability.bad",
                    kind = ArchitectureObligationKind.CAPABILITY,
                    declaration = "shell.run",
                    notesPackageId = rawNotes.packageId
                )
            )
        )

        val report = ArchitectureObligationGraphValidator(notes + rawNotes).validate(graph)

        assertEquals(ArchitectureObligationGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.node.forbidden-universal-mechanism" })
    }

    @Test
    fun diagnosticDescriptionMayNameRejectedRuntimeMechanisms() {
        val graph = ArchitectureObligationGraph(
            graphId = "flow.semantic.diagnostic-language",
            nodes = listOf(
                ArchitectureObligationNode(
                    id = "capability.approval",
                    kind = ArchitectureObligationKind.CAPABILITY,
                    declaration = "approval.require",
                    notesPackageId = "flow.capability.core",
                    description = "A shell command is not accepted as universal approval meaning."
                )
            )
        )

        val report = validator.validate(graph)

        assertEquals(ArchitectureObligationGraphStatus.PASS, report.status, report.issues.toString())
    }

    @Test
    fun architectureObligationGraphRejectsUnknownSelfReferentialAndCyclicEdges() {
        val graph = ArchitectureObligationGraph(
            graphId = "flow.semantic.edge-errors",
            nodes = listOf(
                ArchitectureObligationNode(
                    id = "domain.intent",
                    kind = ArchitectureObligationKind.DOMAIN,
                    declaration = "automation.intent",
                    notesPackageId = "flow.domain.core"
                ),
                ArchitectureObligationNode(
                    id = "capability.approval",
                    kind = ArchitectureObligationKind.CAPABILITY,
                    declaration = "approval.require",
                    notesPackageId = "flow.capability.core"
                )
            ),
            edges = listOf(
                ArchitectureObligationEdge("domain.intent", "missing.node", ArchitectureObligationEdgeKind.REQUIRES),
                ArchitectureObligationEdge("domain.intent", "domain.intent", ArchitectureObligationEdgeKind.REQUIRES),
                ArchitectureObligationEdge("domain.intent", "capability.approval", ArchitectureObligationEdgeKind.REQUIRES),
                ArchitectureObligationEdge("capability.approval", "domain.intent", ArchitectureObligationEdgeKind.REQUIRES)
            )
        )

        val report = validator.validate(graph)

        assertEquals(ArchitectureObligationGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.edge.to.unknown" })
        assertTrue(report.issues.any { it.code == "semantic.edge.self" })
        assertTrue(report.issues.any { it.code == "semantic.graph.cycle" })
    }

    @Test
    fun duplicateOrInvalidNodeIdsFailValidation() {
        val graph = ArchitectureObligationGraph(
            graphId = "flow.semantic.bad-node-id",
            nodes = listOf(
                ArchitectureObligationNode(
                    id = "Invalid Node",
                    kind = ArchitectureObligationKind.DOMAIN,
                    declaration = "automation.intent",
                    notesPackageId = "flow.domain.core"
                ),
                ArchitectureObligationNode(
                    id = "Invalid Node",
                    kind = ArchitectureObligationKind.DOMAIN,
                    declaration = "automation.intent",
                    notesPackageId = "flow.domain.core"
                )
            )
        )

        val report = validator.validate(graph)

        assertEquals(ArchitectureObligationGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.node.id.invalid" })
        assertTrue(report.issues.any { it.code == "semantic.node.id.duplicate" })
        assertFalse(report.valid)
    }
}
