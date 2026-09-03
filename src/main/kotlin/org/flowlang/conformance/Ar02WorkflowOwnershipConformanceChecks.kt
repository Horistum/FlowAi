package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.materialization.MultiWorkflowTargetMaterializationUnsupportedException
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.MultipleWorkflowCompatibilityViewException
import org.flowlang.standard.FlowStandardVersions

/** Executable AR-02C evidence for workflow ownership and target containment. */
class Ar02WorkflowOwnershipConformanceChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> {
        val result = runCatching(::compile)
        val unit = result.getOrNull()
        val membershipErrors = buildList {
            result.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            if (unit != null) {
                if (unit.workflowPlanSet.contractVersion != FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION) {
                    add("Workflow execution-plan set does not publish the live versioned contract.")
                }
                if (!File(rootDir, "schemas/workflow-execution-plan-set.schema.json").isFile) {
                    add("Workflow execution-plan set schema is missing.")
                }
                if (unit.graph.workflows.map { it.name } != listOf("build", "report")) {
                    add("Canonical graph does not preserve both workflow identities in deterministic order.")
                }
                val nodes = unit.graph.nodes.associateBy { it.id }
                unit.graph.workflows.forEach { workflow ->
                    workflow.rootNodeIds.forEach { root ->
                        if (nodes[root]?.workflow != workflow.id) {
                            add("Workflow '${workflow.name}' root '$root' is not owned by that workflow.")
                        }
                    }
                }
                val namesById = unit.graph.workflows.associate { it.id to it.name }
                val routes = unit.graph.triggers.associate { trigger ->
                    trigger.id to trigger.workflows.map { namesById.getValue(it) }
                }
                if (routes["build-manual"] != listOf("build") || routes["all-manual"] != listOf("build", "report")) {
                    add("Canonical trigger routing was flattened, reordered incorrectly or lost.")
                }
                if (runCatching { unit.executionPlan }.exceptionOrNull() !is MultipleWorkflowCompatibilityViewException) {
                    add("Legacy ExecutionPlan compatibility view selected or flattened a multi-workflow graph.")
                }
            }
        }
        val targetErrors = buildList {
            result.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            if (unit != null) {
                val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
                val selection = TargetSelectionAuthority.fromConformanceCheck(
                    value = "jenkins",
                    checkId = TARGET_GATE,
                    targets = targets
                )
                if (runCatching { TargetMaterializationRequest.fromCompilation(unit, selection) }
                        .exceptionOrNull() !is MultiWorkflowTargetMaterializationUnsupportedException) {
                    add("Executable target materialization did not fail closed for multiple workflows.")
                }
                if (runCatching { TargetDiagnosticMaterializationRequest.fromCompilation(unit, selection) }
                        .exceptionOrNull() !is MultiWorkflowTargetMaterializationUnsupportedException) {
                    add("Diagnostic target materialization selected or flattened a workflow.")
                }
            }
        }
        return listOf(
            ConformanceCheck(
                name = MEMBERSHIP,
                passed = membershipErrors.isEmpty(),
                message = membershipErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = TARGET_GATE,
                passed = targetErrors.isEmpty(),
                message = targetErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            )
        )
    }

    private fun compile(): org.flowlang.compiler.CompilationUnit {
        val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
        val result = IntentYamlFrontend(FlowCompilationService(modules)).compileText(FIXTURE, "ar02c-conformance.intent.yaml")
        return (result as? CompilationResult.Accepted)?.unit
            ?: error("AR-02C multi-workflow fixture was rejected: $result")
    }

    companion object {
        const val MEMBERSHIP = "architecture-recovery.ar-02.workflow-membership-integrity"
        const val TARGET_GATE = "architecture-recovery.ar-02.multi-workflow-materialization-gate"

        private val FIXTURE = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: ar02c-conformance
            triggers:
              - id: build-manual
                type: MANUAL
                workflows: [build]
              - id: all-manual
                type: MANUAL
                workflows: [build, report]
            workflows:
              - name: build
                kind: BUILD
                steps:
                  - id: build-step
                    capability: CUSTOM
              - name: report
                kind: CUSTOM
                steps:
                  - id: report-step
                    capability: CUSTOM
        """.trimIndent()
    }
}
