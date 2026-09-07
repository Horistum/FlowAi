package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.continuity.UnresolvedAdapterContinuitySatisfactionException
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.PlannerCapabilityConstraintViolation
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationUnit
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderBlockedException
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.UnresolvedExecutionTopologyException
import org.flowlang.generators.manifest.UnresolvedPlanningContinuityException
import org.flowlang.generators.manifest.UnresolvedPlanningControlException
import org.flowlang.generators.manifest.sanitizeId
import org.flowlang.materialization.MultiWorkflowTargetMaterializationUnsupportedException
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.MultipleWorkflowCompatibilityViewException
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.builtin.BuiltInTargetProjections

/** Executable workflow ownership evidence and its approved target-selection boundary. */
class WorkflowOwnershipConformanceChecks(private val rootDir: File) {
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
                targets.keys.sorted().forEach { target ->
                    val selection = TargetSelectionAuthority.fromConformanceCheck(target, TARGET_GATE, targets)
                    if (runCatching { TargetMaterializationRequest.fromCompilation(unit, selection) }
                            .exceptionOrNull() !is MultiWorkflowTargetMaterializationUnsupportedException) {
                        add("$target executable target materialization did not fail closed for multiple workflows.")
                    }
                    if (runCatching { TargetDiagnosticMaterializationRequest.fromCompilation(unit, selection) }
                            .exceptionOrNull() !is MultiWorkflowTargetMaterializationUnsupportedException) {
                        add("$target diagnostic target materialization selected or flattened a workflow.")
                    }
                }
            }
        }
        return listOf(
            ConformanceCheck(MEMBERSHIP, membershipErrors.isEmpty(), membershipErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")),
            ConformanceCheck(TARGET_GATE, targetErrors.isEmpty(), targetErrors.takeIf { it.isNotEmpty() }?.joinToString(" | "))
        )
    }

    /** Uses compiled integration fixtures through the production request/pipeline/provider path. */
    internal fun observeTargets(
        merge: CompilationUnit,
        multi: CompilationUnit,
        failure: CompilationUnit
    ): List<WorkflowTargetObservation> {
        val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
        val projections = BuiltInTargetProjections.registry
        val pipeline = BuiltInTargetProjections.pipeline(targets, rootDir)
        val mergeId = merge.authorization.bindings.nodeMetadata.single {
            it.nodeId == merge.graph.valueMerges.single().targetNodeId
        }.planNodeId.let(::sanitizeId)
        return targets.keys.sorted().flatMap { target ->
            val selection = TargetSelectionAuthority.fromConformanceCheck(
                target, WorkflowSemanticsIntegrationChecks.TARGET_MATRIX, targets
            )
            WorkflowTargetScenario.entries.map { scenario ->
                val compilation = when (scenario) {
                    WorkflowTargetScenario.EXPLICIT_MERGE -> merge
                    WorkflowTargetScenario.MULTI_WORKFLOW -> multi
                    WorkflowTargetScenario.WORKFLOW_FAILURE -> failure
                }
                val digest = compilation.graphDigest.value
                try {
                    compilation.authorization.requireIntegrity()
                    if (scenario == WorkflowTargetScenario.MULTI_WORKFLOW) {
                        val executableFailure = runCatching {
                            TargetMaterializationRequest.fromCompilation(compilation, selection)
                        }.exceptionOrNull()
                        val diagnosticFailure = runCatching {
                            TargetDiagnosticMaterializationRequest.fromCompilation(compilation, selection)
                        }.exceptionOrNull()
                        WorkflowTargetObservation(
                            target, scenario, digest, WorkflowTargetOutcome.BLOCKED,
                            executableBlocked = executableFailure is MultiWorkflowTargetMaterializationUnsupportedException,
                            diagnosticBlocked = diagnosticFailure is MultiWorkflowTargetMaterializationUnsupportedException
                        )
                    } else {
                        val provider = projections.providerFor(target)
                        if (provider == null) {
                            WorkflowTargetObservation(target, scenario, digest, WorkflowTargetOutcome.NO_PROVIDER)
                        } else if (scenario == WorkflowTargetScenario.WORKFLOW_FAILURE && target == "jenkins") {
                            val manifest = pipeline.generate(TargetMaterializationRequest.fromCompilation(compilation, selection))
                            val text = provider.render(manifest)
                            WorkflowTargetObservation(
                                target, scenario, digest,
                                if (manifest.compatibility.executable) WorkflowTargetOutcome.EXECUTABLE else WorkflowTargetOutcome.NON_EXECUTABLE,
                                renderedText = text,
                                failurePolicyPreserved = preservesFailurePolicy(manifest, compilation)
                            )
                        } else {
                            val candidate = runCatching {
                                pipeline.generate(TargetMaterializationRequest.fromCompilation(compilation, selection))
                            }
                            val candidateFailure = candidate.exceptionOrNull()
                            require(candidateFailure == null || isExpectedTargetBlocker(candidateFailure)) {
                                "Unexpected execution failure: ${candidateFailure?.javaClass?.simpleName}: ${candidateFailure?.message}"
                            }
                            var candidateText: String? = null
                            val candidateRenderBlocked = candidate.getOrNull()?.let { manifest ->
                                val result = runCatching { provider.render(manifest) }
                                require(result.exceptionOrNull() == null || result.exceptionOrNull() is TargetRenderBlockedException) {
                                    "Unexpected provider rendering failure: ${result.exceptionOrNull()}"
                                }
                                candidateText = result.getOrNull()
                                result.exceptionOrNull() is TargetRenderBlockedException
                            } ?: (candidateFailure != null)
                            val diagnostic = pipeline.generateDiagnosticEvidence(
                                TargetDiagnosticMaterializationRequest.fromCompilation(compilation, selection)
                            )
                            val diagnosticRendering = runCatching { provider.render(diagnostic) }
                            val renderFailure = diagnosticRendering.exceptionOrNull()
                            require(renderFailure == null || renderFailure is TargetRenderBlockedException) {
                                "Unexpected diagnostic rendering failure: $renderFailure"
                            }
                            val renderedText = candidateText ?: diagnosticRendering.getOrNull()
                            val mergeSteps = flatten(diagnostic).filter { it.id == mergeId }
                            WorkflowTargetObservation(
                                target, scenario, digest,
                                if (renderedText == null) WorkflowTargetOutcome.NON_EXECUTABLE else WorkflowTargetOutcome.EXECUTABLE,
                                executableBlocked = candidateRenderBlocked,
                                mergePreserved = mergeSteps.size == 1 &&
                                    mergeSteps.single().materialization.status == TargetMaterializationStatus.SEMANTIC_ONLY,
                                renderBlocked = renderFailure is TargetRenderBlockedException,
                                renderedText = renderedText,
                                failurePolicyPreserved = preservesFailurePolicy(diagnostic, compilation)
                            )
                        }
                    }
                } catch (exception: Exception) {
                    WorkflowTargetObservation(
                        target, scenario, digest, WorkflowTargetOutcome.BLOCKED,
                        error = "${exception.javaClass.simpleName}: ${exception.message}"
                    )
                }
            }
        }
    }

    private fun isExpectedTargetBlocker(failure: Throwable): Boolean =
        failure is PlannerCapabilityConstraintViolation ||
            failure is UnresolvedExecutionTopologyException ||
            failure is UnresolvedPlanningContinuityException ||
            failure is UnresolvedPlanningControlException ||
            failure is UnresolvedAdapterContinuitySatisfactionException

    private fun preservesFailurePolicy(manifest: TargetManifest, compilation: CompilationUnit): Boolean =
        WorkflowFailureProjectionEvidence.errors(
            manifest, compilation.workflowPlanSet.workflows.single().failurePolicy
        ).isEmpty()

    private fun flatten(manifest: TargetManifest): List<TargetStep> {
        fun visit(steps: List<TargetStep>): List<TargetStep> = steps.flatMap { listOf(it) + visit(it.children) }
        return manifest.jobs.flatMap { visit(it.steps) }
    }

    private fun compile(): CompilationUnit {
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
