package org.flowlang.conformance

import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.CanonicalDependencyEdge
import org.flowlang.compiler.CanonicalDependencyEvidence
import org.flowlang.compiler.CanonicalDependencyKind
import org.flowlang.compiler.CanonicalDependencyResolution
import org.flowlang.compiler.CanonicalExecutionGraphBuild
import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
import org.flowlang.compiler.CanonicalExecutionGraphValidator
import org.flowlang.compiler.CanonicalGraphOutput
import org.flowlang.compiler.CanonicalTryNode
import org.flowlang.compiler.CanonicalValueTypeId
import org.flowlang.compiler.CanonicalWorkflowFailureDisposition
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TryPlanNode
import org.flowlang.planner.WorkflowFailureDisposition
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.builtin.BuiltInTargetProjections

/** Executable AR-02D evidence for first-class workflow failure semantics. */
class WorkflowFailureConformanceChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        resultCheck(FAILURE_POLICY_INTEGRITY, ::failurePolicyErrors),
        resultCheck(NO_SYNTHETIC_HANDLER_AUTHORITY, ::syntheticAuthorityErrors)
    )

    private fun failurePolicyErrors(): List<String> = buildList {
        val unit = runCatching { compile(GLOBAL_HANDLER_SOURCE, "ar02d-conformance") }
            .getOrElse {
                add("Workflow failure fixture did not compile: ${it.message}")
                return@buildList
            }
        val workflow = unit.graph.workflows.singleOrNull()
        if (workflow == null) {
            add("Workflow failure fixture did not produce exactly one canonical workflow.")
            return@buildList
        }
        val handler = workflow.failurePolicy.handler
        if (handler == null) {
            add("Canonical workflow does not own a typed failure-handler region.")
            return@buildList
        }

        if (workflow.failurePolicy.disposition != CanonicalWorkflowFailureDisposition.PROPAGATE) {
            add("Authored workflow error handling did not preserve PROPAGATE disposition.")
        }
        if (handler.entry.errorBinding != "error" || handler.entry.priorSuccessfulValuesAvailable) {
            add("Workflow failure-handler entry contract is not the closed error-only contract.")
        }
        if (handler.nodeIds.isEmpty() || handler.nodeIds.any { it in workflow.rootNodeIds }) {
            add("Failure-handler ownership is empty or overlaps normal workflow roots.")
        }
        if (unit.graph.nodes.filterIsInstance<CanonicalTryNode>().isNotEmpty()) {
            add("Workflow-level failure handling leaked into the canonical graph as a synthetic Try node.")
        }
        val publicPolicy = unit.workflowPlanSet.workflows.single().failurePolicy
        if (publicPolicy.disposition != WorkflowFailureDisposition.PROPAGATE) {
            add("Graph-derived workflow plan set changed the failure disposition.")
        }
        if (publicPolicy.handler?.nodeIds?.size != handler.nodeIds.size) {
            add("Public failure policy and canonical handler membership differ.")
        }
        val legacyTail = unit.executionPlan.nodes.lastOrNull() as? TryPlanNode
        if (
            legacyTail == null ||
            legacyTail.body.isNotEmpty() ||
            legacyTail.errorHandler.map { it.id } != publicPolicy.handler?.nodeIds
        ) {
            add("Legacy single-workflow tail is not an exact graph-derived compatibility mirror.")
        }
        if (unit.executionPlan.outputs.any { output ->
                output.sourceNodeId in legacyTail?.errorHandler.orEmpty().map { it.id }.toSet()
            }) {
            add("Workflow failure-handler output leaked into normal ExecutionPlan outputs.")
        }

        val recovered = unit.graph.copy(
            workflows = listOf(
                workflow.copy(
                    failurePolicy = workflow.failurePolicy.copy(
                        disposition = CanonicalWorkflowFailureDisposition.RECOVER
                    )
                )
            )
        )
        if (CanonicalExecutionGraphDigestComputer.digest(recovered) == unit.graphDigest) {
            add("Changing workflow failure disposition did not change the canonical digest.")
        }

        val overlap = unit.graph.copy(
            workflows = listOf(
                workflow.copy(rootNodeIds = workflow.rootNodeIds + handler.nodeIds.first())
            )
        )
        val overlapReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(overlap, unit.authorization.inspectionView().bindings)
        )
        if (overlapReport.issues.none { it.code == "graph.workflow.failure-handler.root-overlap" }) {
            add("Canonical validation accepted one node as both normal root and failure-handler member.")
        }

        val crossing = CanonicalDependencyEdge(
            sourceNodeId = workflow.rootNodeIds.first(),
            targetNodeId = handler.nodeIds.first(),
            kind = CanonicalDependencyKind.ORDERING,
            evidence = CanonicalDependencyEvidence.DECLARED_ORDERING,
            resolution = CanonicalDependencyResolution.RESOLVED
        )
        val crossingReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(
                unit.graph.copy(dependencyEdges = unit.graph.dependencyEdges + crossing),
                unit.authorization.inspectionView().bindings
            )
        )
        if (crossingReport.issues.none { it.code == "graph.workflow.failure-handler.edge-crossing" }) {
            add("Canonical validation accepted a dependency crossing the normal/failure boundary.")
        }

        val failureOutput = CanonicalGraphOutput(
            name = "failureOnly",
            type = CanonicalValueTypeId("any"),
            sourceNodeId = handler.nodeIds.first()
        )
        val outputReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(
                unit.graph.copy(outputs = unit.graph.outputs + failureOutput),
                unit.authorization.inspectionView().bindings
            )
        )
        if (outputReport.issues.none { it.code == "graph.workflow.failure-handler.output" }) {
            add("Canonical validation accepted a failure-only value as a normal workflow output.")
        }

        if (unit.workflowPlanSet.contractVersion != FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION) {
            add("Workflow execution-plan set does not publish the active versioned failure contract.")
        }
        val schema = File(rootDir, "schemas/workflow-execution-plan-set.schema.json")
        if (!schema.isFile) {
            add("Workflow execution-plan set schema is missing.")
        } else {
            val text = schema.readText()
            if ("\"const\": \"1.1\"" !in text || "\"failurePolicy\"" !in text) {
                add("Workflow execution-plan set schema does not publish the 1.1 failure-policy contract.")
            }
        }
        if (!File(rootDir, "docs/WORKFLOW_FAILURE_POLICY_MIGRATION.md").isFile) {
            add("AR-02D public contract migration guide is missing.")
        }
    }

    private fun syntheticAuthorityErrors(): List<String> = buildList {
        val unit = runCatching { compile(GLOBAL_HANDLER_SOURCE, "ar02d-authority") }
            .getOrElse {
                add("Workflow failure authority fixture did not compile: ${it.message}")
                return@buildList
            }
        val handler = unit.workflowPlanSet.workflows.single().failurePolicy.handler
        if (handler == null) {
            add("Typed workflow failure handler is absent from the authorized plan set.")
            return@buildList
        }

        val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
        val authority = ReferenceAdapterEvidence.control(
            rootDir = rootDir,
            targets = targets,
            projections = BuiltInTargetProjections.registry
        )
        val typedRequirements = authority.requirementsFor(unit.authorization)
        val typed = typedRequirements.singleOrNull { it.semantic == "compensation.error-handler" }
        if (typed == null) {
            add("Adapter requirements did not consume the typed workflow failure policy.")
        } else {
            if (typed.subject != handler.id) {
                add("Adapter failure requirement subject is not the typed handler-region identity.")
            }
            if (typed.subject.startsWith("onError_")) {
                add("Adapter failure requirement still depends on a generated compatibility-tail id.")
            }
            if (!typed.detail.contains("disposition=PROPAGATE")) {
                add("Adapter requirement lost the typed failure disposition.")
            }
        }

        val forged = ExecutionPlan(
            flowName = "forged-tail",
            requiredCapabilities = listOf("errorHandlers.finally"),
            nodes = listOf(
                ApprovalNode(id = "normal"),
                TryPlanNode(
                    id = "onError_999",
                    body = emptyList(),
                    errorHandler = listOf(ApprovalNode(id = "handler"))
                )
            )
        )
        val forgedRequirements = authority.requirementsFor(forged)
        if (forgedRequirements.any { it.semantic == "compensation.error-handler" }) {
            add("A terminal empty-body Try forged workflow-level failure authority.")
        }
        if (forgedRequirements.none { it.semantic == "compensation.detached-error-handler" }) {
            add("A detached compatibility-shaped Try was not quarantined as unsupported semantics.")
        }

        val requirementSource = read("src/main/kotlin/org/flowlang/adapters/control/AdapterControlRequirementAuthority.kt")
        listOf("FLOW_ERROR_HANDLER_ID", "canonicalFlowHandler", "\"errorHandlers.finally\"").forEach { forbidden ->
            if (forbidden in requirementSource) {
                add("Adapter control requirement authority still contains synthetic handler heuristic '$forbidden'.")
            }
        }
        val projectionSource = read("src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt")
        if ("lastOrNull() as? TryPlanNode" in projectionSource) {
            add("Built-in target projection still discovers workflow failure handling from terminal shape.")
        }
        val authorizationSource = read("src/main/kotlin/org/flowlang/compiler/WorkflowFailureAuthorization.kt")
        if (
            "requireSingleWorkflowFailureProjection" !in authorizationSource ||
            "workflowPlanSet.workflows.single()" !in authorizationSource
        ) {
            add("Target consumers lack the canonical authorization-owned workflow failure projection gate.")
        }
    }

    private fun compile(source: String, identity: String): org.flowlang.compiler.CompilationUnit {
        val file = File.createTempFile("$identity-", ".flow")
        return try {
            file.writeText(source)
            FlowSourceFrontend(
                FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(rootDir, "modules"))),
                FlowParser()
            ).compile(file).requireAccepted()
        } finally {
            file.delete()
        }
    }

    private fun read(path: String): String = File(rootDir, path).readText()

    private fun resultCheck(name: String, block: () -> List<String>): ConformanceCheck {
        val result = runCatching(block)
        val errors = result.getOrNull().orEmpty() + listOfNotNull(
            result.exceptionOrNull()?.let { exception ->
                exception.message ?: exception.javaClass.simpleName
            }
        )
        return ConformanceCheck(
            name = name,
            passed = errors.isEmpty(),
            message = errors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )
    }

    companion object {
        const val FAILURE_POLICY_INTEGRITY =
            "architecture-recovery.ar-02.failure-policy-integrity"
        const val NO_SYNTHETIC_HANDLER_AUTHORITY =
            "architecture-recovery.ar-02.no-synthetic-handler-authority"

        private val GLOBAL_HANDLER_SOURCE = """
            use module "shell" version "1.0"
            flow "failure-policy-conformance" {
              systems { system "local" { type: shell } }
              steps {
                shell.run local { command: "work" } -> workResult
              }
              on error {
                shell.run local { command: error.message } -> handlerResult
              }
            }
        """.trimIndent()
    }
}
