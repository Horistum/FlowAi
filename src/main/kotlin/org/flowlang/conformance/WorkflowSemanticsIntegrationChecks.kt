package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.AiIntentResponse
import org.flowlang.ai.normalization.ConfidenceScore
import org.flowlang.ai.normalization.IntentClassification
import org.flowlang.ai.normalization.NormalizationMode
import org.flowlang.ai.normalization.NormalizationReport
import org.flowlang.cli.Json
import org.flowlang.compiler.CanonicalDependencyKind
import org.flowlang.compiler.CanonicalExecutionGraph
import org.flowlang.compiler.CanonicalExecutionGraphBuild
import org.flowlang.compiler.CanonicalExecutionGraphValidator
import org.flowlang.compiler.CanonicalTaskNode
import org.flowlang.compiler.CanonicalWorkflowFailureDisposition
import org.flowlang.compiler.CompilationUnit
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.ai.ReviewedAiProposal
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.targets.builtin.BuiltInTargetProjections

/** Execute each slice once; the integrated closure consumes these exact observations. */
internal fun workflowSemanticsPrerequisiteChecks(rootDir: File): List<ConformanceCheck> =
    Ar02FlowSensitiveConformanceChecks(rootDir).checks() +
        Ar02ExplicitMergeConformanceChecks(rootDir).checks() +
        WorkflowOwnershipConformanceChecks(rootDir).checks() +
        Ar02WorkflowFailureConformanceChecks(rootDir).checks()

/** Cross-layer evidence using public frontends and the existing target-boundary owner. */
class WorkflowSemanticsIntegrationChecks(private val rootDir: File) {
    private val modules by lazy { ModuleRegistry.fromDirectory(File(rootDir, "modules")) }
    private val compiler by lazy { FlowCompilationService(modules) }
    private val commonIntent by lazy { compileIntent(COMMON_INTENT, "ar02e-common.intent.yaml") }
    private val commonAi by lazy { compileAi(COMMON_INTENT, "ar02e-common-ai") }
    private val commonFlow by lazy { compileFlowSource(COMMON_SOURCE, "ar02e-common-source") }
    internal val mergeUnit by lazy { compileFlowSource(MERGE_SOURCE, "ar02e-merge") }
    internal val failureUnit by lazy { compileFlowSource(FAILURE_SOURCE, "ar02e-failure") }
    internal val multiUnit by lazy { compileIntent(MULTI_INTENT, "ar02e-multi.intent.yaml") }
    private val multiAi by lazy { compileAi(MULTI_INTENT, "ar02e-multi-ai") }
    internal val targetObservations by lazy {
        WorkflowOwnershipConformanceChecks(rootDir).observeTargets(mergeUnit, multiUnit, failureUnit)
    }
    internal val mutationObservations by lazy { observeMutations() }

    fun checks(predecessorChecks: List<ConformanceCheck> = workflowSemanticsPrerequisiteChecks(rootDir)): List<ConformanceCheck> {
        val matrices = listOf(
            resultCheck(FRONTEND_MATRIX, ::frontendMatrixErrors),
            resultCheck(MUTATION_MATRIX, ::mutationMatrixErrors),
            resultCheck(TARGET_MATRIX, ::targetMatrixErrors),
            resultCheck(PUBLIC_COMPATIBILITY_MATRIX, ::publicCompatibilityErrors)
        )
        return matrices + resultCheck(FINDING_CLOSURE) {
            findingClosureErrors(predecessorChecks, matrices)
        }
    }

    internal fun commonUnits(): List<CompilationUnit> = listOf(commonFlow, commonIntent, commonAi)

    private fun frontendMatrixErrors(): List<String> = buildList {
        addAll(WorkflowSemanticEvidence.frontendErrors(commonUnits()))
        if (multiUnit.graph != multiAi.graph || multiUnit.graphDigest != multiAi.graphDigest ||
            WorkflowSemanticEvidence.semanticPlanSet(multiUnit) != WorkflowSemanticEvidence.semanticPlanSet(multiAi)
        ) add("Multi-workflow Intent and reviewed AI disagree on canonical ownership or public views.")
        if (multiUnit.graph.workflows.map { it.name } != listOf("build", "report") ||
            multiUnit.graph.workflows.any { it.failurePolicy.handler == null }
        ) add("Multi-workflow frontend lost deterministic ownership or a typed failure region.")
        if (mergeUnit.graph.valueMerges.size != 1 || mergeUnit.graph.workflows.single().failurePolicy.handler == null) {
            add("Parsed Flow Source lost explicit merge or first-class failure semantics.")
        }
        (commonUnits() + listOf(mergeUnit, failureUnit, multiUnit, multiAi)).forEach { unit ->
            unit.authorization.requireIntegrity()
            val validation = CanonicalExecutionGraphValidator.validate(CanonicalExecutionGraphBuild(unit.graph, unit.authorization.bindings))
            if (!validation.valid) add("${unit.source.identity} produced invalid graph evidence: ${validation.issues}")
        }
    }

    private fun mutationMatrixErrors(): List<String> = buildList {
        addAll(WorkflowSemanticEvidence.mutationErrors(mutationObservations))
        val reordered = compileFlowSource(MERGE_SOURCE.replace("merge(left, right)", "merge(right, left)"), "ar02e-permutation")
        if (mergeUnit.graph != reordered.graph || mergeUnit.graphDigest != reordered.graphDigest) {
            add("Merge input storage order changed canonical meaning.")
        }
        mergeUnit.authorization.requireIntegrity()
        reordered.authorization.requireIntegrity()
        val merge = mergeUnit.graph.valueMerges.single()
        val missingArm = runCatching { merge.copy(inputs = merge.inputs.dropLast(1)) }.exceptionOrNull()
        if (missingArm !is IllegalArgumentException) {
            add("The typed merge constructor accepted a non-exhaustive single-arm contract.")
        }
    }

    private fun observeMutations(): List<WorkflowSemanticMutationObservation> {
        val baseline = mergeUnit
        val graph = baseline.graph
        val workflow = graph.workflows.single()
        val handler = requireNotNull(workflow.failurePolicy.handler)
        val merge = graph.valueMerges.single()
        fun policy(change: org.flowlang.compiler.CanonicalWorkflowFailurePolicy): CanonicalExecutionGraph =
            graph.copy(workflows = listOf(workflow.copy(failurePolicy = change)))
        val changedValue = compileFlowSource(MERGE_SOURCE.replace("set right = \"right\"", "set right = \"changed\""), "ar02e-changed-value")
        val wrongProducer = graph.copy(valueMerges = listOf(merge.copy(inputs = merge.inputs.mapIndexed { index, input ->
            if (index == 0) input.copy(producerNodeId = merge.inputs[1].producerNodeId) else input
        })))
        val missingEdge = graph.copy(dependencyEdges = graph.dependencyEdges.filterNot {
            it.kind == CanonicalDependencyKind.VALUE && it.targetNodeId == merge.targetNodeId
        })
        val firstWorkflow = multiUnit.graph.workflows.first()
        val otherWorkflow = multiUnit.graph.workflows.last()
        val movedNode = multiUnit.graph.nodes.filterIsInstance<CanonicalTaskNode>().first { it.workflow == firstWorkflow.id }
        val changedMembership = multiUnit.graph.copy(nodes = multiUnit.graph.nodes.map {
            if (it.id == movedNode.id) movedNode.copy(workflow = otherWorkflow.id) else it
        })
        val changedRouting = multiUnit.graph.copy(triggers = multiUnit.graph.triggers.map {
            if (it.id == "build-manual") it.copy(workflows = listOf(otherWorkflow.id)) else it
        })
        return listOf(
            WorkflowSemanticEvidence.observeMutation("path-value", baseline, changedValue.graph),
            WorkflowSemanticEvidence.observeMutation("merge-producer", baseline, wrongProducer, true),
            WorkflowSemanticEvidence.observeMutation("merge-edge", baseline, missingEdge, true),
            WorkflowSemanticEvidence.observeMutation("workflow-membership", multiUnit, changedMembership, true),
            WorkflowSemanticEvidence.observeMutation("trigger-routing", multiUnit, changedRouting),
            WorkflowSemanticEvidence.observeMutation("failure-disposition", baseline,
                policy(workflow.failurePolicy.copy(disposition = CanonicalWorkflowFailureDisposition.RECOVER))),
            WorkflowSemanticEvidence.observeMutation("handler-identity", baseline,
                policy(workflow.failurePolicy.copy(handler = handler.copy(id = "${handler.id}:changed")))),
            WorkflowSemanticEvidence.observeMutation("handler-membership", baseline,
                policy(workflow.failurePolicy.copy(handler = handler.copy(nodeIds = handler.nodeIds + merge.targetNodeId))), true),
            WorkflowSemanticEvidence.observeMutation("error-binding", baseline,
                policy(workflow.failurePolicy.copy(handler = handler.copy(entry = handler.entry.copy(errorBinding = "changedError"))))),
            WorkflowSemanticEvidence.observeMutation("entry-availability", baseline,
                policy(workflow.failurePolicy.copy(handler = handler.copy(entry = handler.entry.copy(priorSuccessfulValuesAvailable = true)))))
        )
    }

    internal fun targetMatrixErrors(): List<String> = WorkflowTargetGatingMatrix.errors(
        targetObservations,
        TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")).keys,
        BuiltInTargetProjections.registry.targetIds
    )

    private fun publicCompatibilityErrors(): List<String> = WorkflowPlanSetCompatibilityMatrix.errors(
        failureUnit, multiUnit, Json.mapper.readTree(File(rootDir, "schemas/workflow-execution-plan-set.schema.json"))
    )

    private fun findingClosureErrors(predecessors: List<ConformanceCheck>, matrices: List<ConformanceCheck>): List<String> = buildList {
        val inventory = ArchitectureRecoveryConformanceInventory.load(rootDir)
        addAll(WorkflowSemanticsFindingEvidenceValidator.errors(
            WorkflowSemanticsFindingCatalog.load(rootDir), inventory.checks.toSet(), predecessors, rootDir
        ))
        (predecessors + matrices).filterNot { it.passed }.forEach {
            add("AR-02 cannot close while '${it.name}' fails: ${it.message.orEmpty()}")
        }
        addAll(WorkflowSemanticsRecoveryLifecycle.errors(WorkflowSemanticsRecoveryLifecycle.load(rootDir)))
        val report = File(rootDir, REPORT_PATH)
        if (!report.isFile) add("AR-02 closure report is missing.")
        val forbidden = listOf("FLOW_ERROR_HANDLER_ID", "canonicalFlowHandler", "lastOrNull() as? TryPlanNode")
        listOf("adapters", "targets", "generators", "cli").forEach { packageName ->
            File(rootDir, "src/main/kotlin/org/flowlang/$packageName").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }.forEach { file ->
                    val source = file.readText()
                    forbidden.filter(source::contains).forEach { term ->
                        add("${file.relativeTo(rootDir)} retains retired synthetic-handler inference '$term'.")
                    }
                }
        }
    }

    private fun compileIntent(text: String, identity: String): CompilationUnit =
        IntentYamlFrontend(compiler).compileText(text, identity).requireAccepted()

    private fun compileAi(text: String, identity: String): CompilationUnit {
        val intent = IntentYamlLoader.loadText(text, "$identity.intent.yaml")
        val request = AiIntentRequest(userText = "Compile the AR-02E semantic closure fixture.", mode = NormalizationMode.DRAFT)
        val response = AiIntentResponse(
            normalizedIntent = intent,
            report = NormalizationReport(
                mode = NormalizationMode.DRAFT,
                classification = IntentClassification("ar-02e", 1.0),
                confidence = ConfidenceScore(1.0, 1.0, 1.0, 1.0, 1.0)
            )
        )
        return ReviewedAiProposalFrontend(compiler).compile(ReviewedAiProposal(
            providerId = "ar-02e-conformance", request = request, response = response,
            sourceIdentity = identity, sourceName = "$identity.json"
        )).requireAccepted()
    }

    internal fun compileFlowSource(source: String, identity: String): CompilationUnit {
        val file = File.createTempFile("$identity-", ".flow")
        return try {
            file.writeText(source, Charsets.UTF_8)
            FlowSourceFrontend(compiler).compile(file).requireAccepted()
        } finally {
            check(file.delete()) { "Cannot remove temporary conformance source '${file.name}'." }
        }
    }

    private fun resultCheck(name: String, block: () -> List<String>): ConformanceCheck {
        val errors = try { block() } catch (failure: Exception) {
            listOf("${failure.javaClass.simpleName}: ${failure.message}")
        }
        return ConformanceCheck(name, errors.isEmpty(), errors.takeIf { it.isNotEmpty() }?.joinToString(" | "))
    }

    companion object {
        const val FRONTEND_MATRIX = "architecture-recovery.ar-02.integrated-frontend-matrix"
        const val MUTATION_MATRIX = "architecture-recovery.ar-02.integrated-mutation-matrix"
        const val TARGET_MATRIX = "architecture-recovery.ar-02.integrated-target-gating-matrix"
        const val PUBLIC_COMPATIBILITY_MATRIX = "architecture-recovery.ar-02.integrated-public-compatibility-matrix"
        const val FINDING_CLOSURE = "architecture-recovery.ar-02.finding-closure-evidence"
        const val REPORT_PATH = ".flow-agent/reports/ar-02-flow-sensitive-workflow-data-failure-semantics.md"

        internal val COMMON_INTENT = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: ar02e-common
            inputs:
              - name: environment
                type: text
                required: true
            workflows:
              - name: main
                kind: CUSTOM
                steps:
                  - id: approved
                    capability: APPROVE
            failure:
              notify: false
              rollback: false
              stopOnError: true
        """.trimIndent()

        internal val COMMON_SOURCE = """
            flow "ar02e-common" {
              input { environment: text required }
              steps {
                approve manual { message: "Approval required for ar02e-common" } -> approved
              }
            }
        """.trimIndent()

        internal val FAILURE_SOURCE = """
            flow "ar02e-failure" {
              steps {
                approve manual { message: "Proceed with work" } -> approved
              }
              on error {
                approve manual { message: "Handle workflow failure" } -> handled
              }
            }
        """.trimIndent()

        internal val MERGE_SOURCE = """
            use module "shell" version "1.0"
            flow "ar02e-merge" {
              input { condition: boolean required }
              systems { system "local" { type: shell } }
              steps {
                if condition { set left = "left" } else { set right = "right" }
                set joined = merge(left, right)
                shell.run local { command: joined } -> consumed
              }
              on error {
                approve manual { message: "Handle workflow failure" } -> handled
              }
            }
        """.trimIndent()

        internal val MULTI_INTENT = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: ar02e-multi
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
            failure:
              notify: true
              rollback: true
              stopOnError: true
        """.trimIndent()
    }
}
