import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.notes.NotesPackageBoundary
import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind
import org.flowlang.notes.StandardNotesPackageContracts
import org.flowlang.semantic.SemanticActionEdge
import org.flowlang.semantic.SemanticActionEdgeKind
import org.flowlang.semantic.SemanticActionGraph
import org.flowlang.semantic.SemanticActionGraphStatus
import org.flowlang.semantic.SemanticActionGraphValidator
import org.flowlang.semantic.SemanticActionKind
import org.flowlang.semantic.SemanticActionNode
import org.flowlang.semantic.StandardSemanticActionGraphs

class FlowSemanticActionGraphTests {
    private val notes = StandardNotesPackageContracts.baseline()
    private val validator = SemanticActionGraphValidator(notes)

    @Test
    fun baselineSemanticActionGraphIsValidAndNotesBound() {
        val graph = StandardSemanticActionGraphs.baseline()
        val report = validator.validate(graph)

        assertEquals(SemanticActionGraphStatus.PASS, report.status)
        assertTrue(report.valid)
        assertEquals(5, report.nodes)
        assertEquals(4, report.edges)
        assertTrue(graph.nodes.all { it.notesPackageId.startsWith("flow.") })
    }

    @Test
    fun semanticActionNodesMustBindToDeclaredNotesPackageItems() {
        val graph = SemanticActionGraph(
            graphId = "flow.semantic.bad-declaration",
            nodes = listOf(
                SemanticActionNode(
                    id = "capability.unknown",
                    kind = SemanticActionKind.CAPABILITY,
                    declaration = "not.declared",
                    notesPackageId = "flow.capability.core"
                )
            )
        )

        val report = validator.validate(graph)

        assertEquals(SemanticActionGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.node.declaration.unbound" })
    }

    @Test
    fun semanticActionNodeKindMustMatchNotesPackageKind() {
        val graph = SemanticActionGraph(
            graphId = "flow.semantic.kind-mismatch",
            nodes = listOf(
                SemanticActionNode(
                    id = "runtime.bad",
                    kind = SemanticActionKind.RUNTIME_REQUIREMENT,
                    declaration = "approval.required",
                    notesPackageId = "flow.safety.core"
                )
            )
        )

        val report = validator.validate(graph)

        assertEquals(SemanticActionGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.node.package.kind-mismatch" })
    }

    @Test
    fun universalSemanticActionGraphRejectsRawRuntimeMechanismDeclarations() {
        val rawNotes = NotesPackageContract(
            packageId = "flow.capability.raw-runtime",
            packageVersion = "0.9.5.7.2",
            kind = NotesPackageKind.CAPABILITY,
            description = "Test fixture for a forbidden raw runtime mechanism.",
            declaredCapabilities = setOf("shell.run"),
            boundaries = setOf(NotesPackageBoundary("test-only", "Used only to prove semantic mechanism rejection."))
        )
        val graph = SemanticActionGraph(
            graphId = "flow.semantic.raw-runtime-leak",
            nodes = listOf(
                SemanticActionNode(
                    id = "capability.bad",
                    kind = SemanticActionKind.CAPABILITY,
                    declaration = "shell.run",
                    notesPackageId = rawNotes.packageId
                )
            )
        )

        val report = SemanticActionGraphValidator(notes + rawNotes).validate(graph)

        assertEquals(SemanticActionGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.node.forbidden-universal-mechanism" })
    }

    @Test
    fun diagnosticDescriptionMayNameRejectedRuntimeMechanisms() {
        val graph = SemanticActionGraph(
            graphId = "flow.semantic.diagnostic-language",
            nodes = listOf(
                SemanticActionNode(
                    id = "capability.approval",
                    kind = SemanticActionKind.CAPABILITY,
                    declaration = "approval.require",
                    notesPackageId = "flow.capability.core",
                    description = "A shell command is not accepted as universal approval meaning."
                )
            )
        )

        val report = validator.validate(graph)

        assertEquals(SemanticActionGraphStatus.PASS, report.status, report.issues.toString())
    }

    @Test
    fun semanticActionGraphRejectsUnknownSelfReferentialAndCyclicEdges() {
        val graph = SemanticActionGraph(
            graphId = "flow.semantic.edge-errors",
            nodes = listOf(
                SemanticActionNode(
                    id = "domain.intent",
                    kind = SemanticActionKind.DOMAIN,
                    declaration = "automation.intent",
                    notesPackageId = "flow.domain.core"
                ),
                SemanticActionNode(
                    id = "capability.approval",
                    kind = SemanticActionKind.CAPABILITY,
                    declaration = "approval.require",
                    notesPackageId = "flow.capability.core"
                )
            ),
            edges = listOf(
                SemanticActionEdge("domain.intent", "missing.node", SemanticActionEdgeKind.REQUIRES),
                SemanticActionEdge("domain.intent", "domain.intent", SemanticActionEdgeKind.REQUIRES),
                SemanticActionEdge("domain.intent", "capability.approval", SemanticActionEdgeKind.REQUIRES),
                SemanticActionEdge("capability.approval", "domain.intent", SemanticActionEdgeKind.REQUIRES)
            )
        )

        val report = validator.validate(graph)

        assertEquals(SemanticActionGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.edge.to.unknown" })
        assertTrue(report.issues.any { it.code == "semantic.edge.self" })
        assertTrue(report.issues.any { it.code == "semantic.graph.cycle" })
    }

    @Test
    fun duplicateOrInvalidNodeIdsFailValidation() {
        val graph = SemanticActionGraph(
            graphId = "flow.semantic.bad-node-id",
            nodes = listOf(
                SemanticActionNode(
                    id = "Invalid Node",
                    kind = SemanticActionKind.DOMAIN,
                    declaration = "automation.intent",
                    notesPackageId = "flow.domain.core"
                ),
                SemanticActionNode(
                    id = "Invalid Node",
                    kind = SemanticActionKind.DOMAIN,
                    declaration = "automation.intent",
                    notesPackageId = "flow.domain.core"
                )
            )
        )

        val report = validator.validate(graph)

        assertEquals(SemanticActionGraphStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "semantic.node.id.invalid" })
        assertTrue(report.issues.any { it.code == "semantic.node.id.duplicate" })
        assertFalse(report.valid)
    }
}
