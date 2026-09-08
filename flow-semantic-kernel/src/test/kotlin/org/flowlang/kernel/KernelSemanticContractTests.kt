package org.flowlang.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.flowlang.compiler.CanonicalExecutionGraph
import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
import org.flowlang.compiler.CanonicalWorkflow
import org.flowlang.compiler.CanonicalWorkflowId
import org.flowlang.controls.ControlDecisionAuthority
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlRequirement
import org.flowlang.controls.ControlRequirementKind
import org.flowlang.controls.ControlRequirementScope
import org.flowlang.controls.ControlRequirementSource
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologyProfileAuthority

/** Kernel behavior runs without module catalogs, adapters, files or conformance. */
class KernelSemanticContractTests {
    @Test
    fun semanticIdentityIgnoresWorkflowStorageOrderButObservesMeaning() {
        val graph = CanonicalExecutionGraph(
            flowName = "neutral", workflows = listOf(
                CanonicalWorkflow(CanonicalWorkflowId("first"), "First", emptyList()),
                CanonicalWorkflow(CanonicalWorkflowId("second"), "Second", emptyList())
            )
        )
        val digest = CanonicalExecutionGraphDigestComputer.digest(graph)
        assertEquals(digest, CanonicalExecutionGraphDigestComputer.digest(graph.copy(workflows = graph.workflows.reversed())))
        assertNotEquals(digest, CanonicalExecutionGraphDigestComputer.digest(graph.copy(flowName = "different")))
        assertTrue(digest.value.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun graphConstructorRejectsBlankIdentityAndUnknownVersion() {
        assertFailsWith<IllegalArgumentException> { CanonicalExecutionGraph(flowName = " ", workflows = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            CanonicalExecutionGraph(graphVersion = "unknown", flowName = "neutral", workflows = emptyList())
        }
    }

    @Test
    fun absentControlEvidenceBlocksInsteadOfBecomingSatisfied() {
        val requirement = ControlRequirement(
            "approval", ControlRequirementKind.APPROVAL, "operation",
            ControlRequirementSource.FLOW_SOURCE, scope = ControlRequirementScope.INTENT
        )
        val decision = ControlDecisionAuthority.evaluate(listOf(requirement), emptyList())
        assertEquals(ControlDecisionStatus.BLOCKED, decision.status)
        assertEquals(listOf("approval"), decision.blockingRequirementIds)
        assertFailsWith<IllegalArgumentException> {
            ControlDecisionAuthority.evaluate(listOf(requirement, requirement), emptyList())
        }
    }

    @Test
    fun incompleteTopologyEvidenceRemainsInvalid() {
        val profile = ExecutionTopologyProfile("neutral-target")
        assertTrue(ExecutionTopologyProfileAuthority.validate(profile).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { ExecutionTopologyProfileAuthority.requireValid(profile) }
    }
}
