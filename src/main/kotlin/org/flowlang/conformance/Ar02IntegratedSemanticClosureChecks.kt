package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.AiIntentResponse
import org.flowlang.ai.normalization.ConfidenceScore
import org.flowlang.ai.normalization.IntentClassification
import org.flowlang.ai.normalization.NormalizationMode
import org.flowlang.ai.normalization.NormalizationReport
import org.flowlang.cli.Json
import org.flowlang.compiler.CanonicalExecutionGraphBuild
import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
import org.flowlang.compiler.CanonicalExecutionGraphValidator
import org.flowlang.compiler.CanonicalTryNode
import org.flowlang.compiler.CanonicalWorkflowFailureDisposition
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationUnit
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.ai.ReviewedAiProposal
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.MultipleWorkflowCompatibilityViewException
import org.flowlang.planner.TryPlanNode
import org.flowlang.planner.WorkflowExecutionPlanSet
import org.flowlang.standard.FlowStandardVersions

internal data class Ar02FindingClosureEvidence(
    val findingId: String,
    val productionPaths: List<String>,
    val positiveChecks: List<String>,
    val negativeChecks: List<String>
)

internal object Ar02FindingClosureCatalog {
    val entries: List<Ar02FindingClosureEvidence> = listOf(
        Ar02FindingClosureEvidence(
            findingId = "F-02",
            productionPaths = listOf(
                "src/main/kotlin/org/flowlang/core/FlowAvailabilityModel.kt",
                "src/main/kotlin/org/flowlang/core/FlowAvailabilityAnalyzer.kt",
                "src/main/kotlin/org/flowlang/validator/FlowValidator.kt",
                "src/main/kotlin/org/flowlang/planner/FlowPlanner.kt"
            ),
            positiveChecks = listOf(
                Ar02FlowSensitiveConformanceChecks.LATTICE_CHECK,
                Ar02ExplicitMergeConformanceChecks.MERGE_CHECK
            ),
            negativeChecks = listOf(
                Ar02FlowSensitiveConformanceChecks.REJECTION_CHECK,
                Ar02ExplicitMergeConformanceChecks.PERMUTATION_CHECK
            )
        ),
        Ar02FindingClosureEvidence(
            findingId = "F-08",
            productionPaths = listOf(
                "src/main/kotlin/org/flowlang/intent/IntentToAstPlanner.kt",
                "src/main/kotlin/org/flowlang/compiler/CompilationContracts.kt",
                "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraphBuilder.kt",
                "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraphProjection.kt",
                "src/main/kotlin/org/flowlang/materialization/TargetSelection.kt"
            ),
            positiveChecks = listOf(Ar02WorkflowOwnershipConformanceChecks.MEMBERSHIP),
            negativeChecks = listOf(Ar02WorkflowOwnershipConformanceChecks.TARGET_GATE)
        ),
        Ar02FindingClosureEvidence(
            findingId = "F-15",
            productionPaths = listOf(
                "src/main/kotlin/org/flowlang/planner/WorkflowFailurePolicy.kt",
                "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraph.kt",
                "src/main/kotlin/org/flowlang/compiler/WorkflowFailureAuthorization.kt",
                "src/main/kotlin/org/flowlang/adapters/control/AdapterControlRequirementAuthority.kt",
                "src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt"
            ),
            positiveChecks = listOf(
                Ar02WorkflowFailureConformanceChecks.FAILURE_POLICY_INTEGRITY
            ),
            negativeChecks = listOf(
                Ar02WorkflowFailureConformanceChecks.NO_SYNTHETIC_HANDLER_AUTHORITY
            )
        )
    )
}

internal object Ar02FindingClosureEvidenceValidator {
    private val expectedFindings = setOf("F-02", "F-08", "F-15")

    fun errors(
        entries: List<Ar02FindingClosureEvidence>,
        declaredChecks: Set<String>,
        rootDir: File
    ): List<String> = buildList {
        val ids = entries.map(Ar02FindingClosureEvidence::findingId)
        if (ids.toSet() != expectedFindings || ids.size != expectedFindings.size) {
            add("AR-02 closure catalog must map exactly F-02, F-08 and F-15; found $ids.")
        }
        entries.forEach { evidence ->
            if (evidence.productionPaths.size < 3) {
                add("${evidence.findingId} has fewer than three independent production evidence paths.")
            }
            evidence.productionPaths.forEach { path ->
                if (!File(rootDir, path).isFile) {
                    add("${evidence.findingId} production evidence path is missing: $path")
                }
            }
            if (evidence.positiveChecks.isEmpty()) {
                add("${evidence.findingId} has no positive conformance evidence.")
            }
            if (evidence.negativeChecks.isEmpty()) {
                add("${evidence.findingId} has no negative or mutation evidence.")
            }
            (evidence.positiveChecks + evidence.negativeChecks).forEach { check ->
                if (check !in declaredChecks) {
                    add("${evidence.findingId} references undeclared conformance check '$check'.")
                }
            }
        }
    }
}

/**
 * AR-02E closure gate across the independently delivered AR-02A through AR-02D contracts.
 *
 * This class composes existing authorized conformance gates instead of becoming a new direct caller
 * of frozen product authorities. Frontend convergence is checked through the three public frontends;
 * target behavior is checked through the target-owning AR-02B, AR-02C and AR-02D conformance gates.
 */
class Ar02IntegratedSemanticClosureChecks(private val rootDir: File) {
    private val modules by lazy { ModuleRegistry.fromDirectory(File(rootDir, "modules")) }
    private val compiler by lazy { FlowCompilationService(modules) }

    fun checks(): List<ConformanceCheck> = listOf(
        resultCheck(FRONTEND_MATRIX, ::frontendMatrixErrors),
        resultCheck(MUTATION_MATRIX, ::mutationMatrixErrors),
        resultCheck(TARGET_MATRIX, ::targetMatrixErrors),
        resultCheck(PUBLIC_COMPATIBILITY_MATRIX, ::publicCompatibilityErrors),
        resultCheck(FINDING_CLOSURE, ::findingClosureErrors)
    )

    private fun frontendMatrixErrors(): List<String> = buildList {
        val intent = IntentYamlLoader.loadText(SINGLE_WORKFLOW_INTENT, "ar02e-single.intent.yaml")
        val intentUnit = compileIntent(SINGLE_WORKFLOW_INTENT, "ar02e-single.intent.yaml")
        val aiUnit = compileAi(intent, "ar02e-single-ai")

        if (intentUnit.graph != aiUnit.graph || intentUnit.graphDigest != aiUnit.graphDigest) {
            add("Equivalent Intent YAML and reviewed-AI inputs produced different canonical meaning.")
        }
        if (semanticPlanSet(intentUnit) != semanticPlanSet(aiUnit)) {
            add("Equivalent Intent YAML and reviewed-AI inputs produced different graph-derived public meaning.")
        }
        if (intentUnit.graph.workflows.single().failurePolicy.handler == null) {
            add("Single-workflow frontend convergence fixture lost its first-class failure policy.")
        }

        val multiIntent = IntentYamlLoader.loadText(MULTI_WORKFLOW_INTENT, "ar02e-multi.intent.yaml")
        val multiIntentUnit = compileIntent(MULTI_WORKFLOW_INTENT, "ar02e-multi.intent.yaml")
        val multiAiUnit = compileAi(multiIntent, "ar02e-multi-ai")
        if (
            multiIntentUnit.graph != multiAiUnit.graph ||
            multiIntentUnit.graphDigest != multiAiUnit.graphDigest
        ) {
            add("Equivalent multi-workflow Intent and reviewed-AI inputs produced different canonical meaning.")
        }
        if (semanticPlanSet(multiIntentUnit) != semanticPlanSet(multiAiUnit)) {
            add("Equivalent multi-workflow frontends produced different workflow-owned public meaning.")
        }
        if (multiIntentUnit.graph.workflows.map { it.name } != listOf("build", "report")) {
            add("Multi-workflow frontend convergence did not preserve deterministic workflow ownership.")
        }
        if (multiIntentUnit.graph.workflows.any { it.failurePolicy.handler == null }) {
            add("Multi-workflow frontend convergence lost workflow-owned failure handling.")
        }

        val flowUnit = compileFlowSource(INTEGRATED_FLOW_SOURCE, "ar02e-integrated-source")
        if (flowUnit.graph.valueMerges.size != 1) {
            add("Flow Source frontend did not preserve the explicit merge contract.")
        }
        if (flowUnit.graph.workflows.single().failurePolicy.handler == null) {
            add("Flow Source frontend did not preserve the workflow failure region.")
        }

        val units = listOf(intentUnit, aiUnit, flowUnit)
        if (units.map { it.source.frontend }.toSet() != CompilationFrontend.entries.toSet()) {
            add("Integrated frontend matrix did not exercise every registered compiler frontend.")
        }
        if (units.map { it.source.sha256 }.toSet().size != units.size) {
            add("Distinct frontend source evidence unexpectedly collapsed to one source digest.")
        }
        units.forEach { unit ->
            val report = CanonicalExecutionGraphValidator.validate(
                CanonicalExecutionGraphBuild(unit.graph, unit.authorization.bindings)
            )
            if (!report.valid) {
                add("${unit.source.frontend} produced an invalid canonical graph: ${report.issues}.")
            }
            unit.authorization.requireIntegrity()
        }
    }

    private fun mutationMatrixErrors(): List<String> = buildList {
        val baseline = compileFlowSource(INTEGRATED_FLOW_SOURCE, "ar02e-mutation-base")
        val reordered = compileFlowSource(
            INTEGRATED_FLOW_SOURCE.replace("merge(left, right)", "merge(right, left)"),
            "ar02e-mutation-reordered"
        )
        if (baseline.graph != reordered.graph || baseline.graphDigest != reordered.graphDigest) {
            add("Semantically irrelevant explicit-merge input order changed canonical meaning.")
        }

        val changedValue = compileFlowSource(
            INTEGRATED_FLOW_SOURCE.replace("set right = \"right\"", "set right = \"changed\""),
            "ar02e-mutation-value"
        )
        if (baseline.graphDigest == changedValue.graphDigest) {
            add("Changing one path-local value did not change the canonical graph digest.")
        }

        val workflow = baseline.graph.workflows.single()
        val changedFailure = baseline.graph.copy(
            workflows = listOf(
                workflow.copy(
                    failurePolicy = workflow.failurePolicy.copy(
                        disposition = CanonicalWorkflowFailureDisposition.RECOVER
                    )
                )
            )
        )
        if (CanonicalExecutionGraphDigestComputer.digest(changedFailure) == baseline.graphDigest) {
            add("Changing workflow failure disposition did not change the canonical digest.")
        }

        val handler = workflow.failurePolicy.handler
        val mergeTarget = baseline.graph.valueMerges.single().targetNodeId
        if (handler == null) {
            add("Integrated mutation fixture has no workflow failure handler.")
        } else {
            val crossed = baseline.graph.copy(
                workflows = listOf(
                    workflow.copy(
                        failurePolicy = workflow.failurePolicy.copy(
                            handler = handler.copy(nodeIds = handler.nodeIds + mergeTarget)
                        )
                    )
                )
            )
            val report = CanonicalExecutionGraphValidator.validate(
                CanonicalExecutionGraphBuild(crossed, baseline.authorization.bindings)
            )
            if (report.valid) {
                add("Canonical validation accepted a normal merge node inside the failure-handler region.")
            }
        }

        val multi = compileIntent(MULTI_WORKFLOW_INTENT, "ar02e-mutation-multi.intent.yaml")
        val reportWorkflow = multi.graph.workflows.single { it.name == "report" }
        val routeMutation = multi.graph.copy(
            triggers = multi.graph.triggers.map { trigger ->
                if (trigger.id == "build-manual") {
                    trigger.copy(workflows = listOf(reportWorkflow.id))
                } else {
                    trigger
                }
            }
        )
        if (CanonicalExecutionGraphDigestComputer.digest(routeMutation) == multi.graphDigest) {
            add("Changing trigger workflow routing did not change the canonical digest.")
        }
    }

    private fun targetMatrixErrors(): List<String> = buildList {
        val selectedChecks = listOf(
            requireCheck(
                Ar02ExplicitMergeConformanceChecks(rootDir).checks(),
                Ar02ExplicitMergeConformanceChecks.CONTRACT_CHECK
            ),
            requireCheck(
                Ar02WorkflowOwnershipConformanceChecks(rootDir).checks(),
                Ar02WorkflowOwnershipConformanceChecks.TARGET_GATE
            ),
            requireCheck(
                Ar02WorkflowFailureConformanceChecks(rootDir).checks(),
                Ar02WorkflowFailureConformanceChecks.FAILURE_POLICY_INTEGRITY
            )
        )
        selectedChecks.filterNot { it.passed }.forEach { check ->
            add("Integrated target boundary '${check.name}' failed: ${check.message.orEmpty()}")
        }
    }

    private fun publicCompatibilityErrors(): List<String> = buildList {
        val single = compileIntent(SINGLE_WORKFLOW_INTENT, "ar02e-public-single.intent.yaml")
        val view = single.workflowPlanSet.workflows.single()
        if (single.executionPlan != view.executionPlan || single.canonicalPlan != view.canonicalPlan) {
            add("Single-workflow legacy views are not exact projections of WorkflowExecutionPlanSet.")
        }
        if (single.workflowPlanSet.contractVersion != FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION) {
            add("WorkflowExecutionPlanSet does not publish the active contract version.")
        }
        if (view.failurePolicy.handler == null) {
            add("Public workflow view lost the first-class failure policy.")
        }

        val publicTree = Json.mapper.readTree(Json.mapper.writeValueAsBytes(single.workflowPlanSet))
        if (publicTree.path("contractVersion").asText() != "1.1") {
            add("Serialized WorkflowExecutionPlanSet does not identify contract 1.1.")
        }
        if (publicTree.path("workflows").path(0).path("failurePolicy").isMissingNode) {
            add("Serialized workflow view omits failurePolicy.")
        }

        val schemaFile = File(rootDir, "schemas/workflow-execution-plan-set.schema.json")
        if (!schemaFile.isFile) {
            add("WorkflowExecutionPlanSet schema is missing.")
        } else {
            val schema = Json.mapper.readTree(schemaFile)
            if (
                schema.path("properties").path("contractVersion").path("const").asText() != "1.1" ||
                schema.path("\$defs").path("failurePolicy").isMissingNode
            ) {
                add("WorkflowExecutionPlanSet schema and live typed failure contract differ.")
            }
        }

        val multi = compileIntent(MULTI_WORKFLOW_INTENT, "ar02e-public-multi.intent.yaml")
        val legacyFailures = listOf(
            runCatching { multi.ast }.exceptionOrNull(),
            runCatching { multi.validation }.exceptionOrNull(),
            runCatching { multi.executionPlan }.exceptionOrNull(),
            runCatching { multi.canonicalPlan }.exceptionOrNull()
        )
        if (legacyFailures.any { it !is MultipleWorkflowCompatibilityViewException }) {
            add("A legacy single-workflow accessor selected or flattened a multi-workflow compilation.")
        }

        val flow = compileFlowSource(INTEGRATED_FLOW_SOURCE, "ar02e-public-flow")
        val policy = flow.workflowPlanSet.workflows.single().failurePolicy
        val legacyTail = flow.executionPlan.nodes.lastOrNull() as? TryPlanNode
        if (
            policy.handler == null ||
            legacyTail == null ||
            legacyTail.body.isNotEmpty() ||
            legacyTail.errorHandler.map { it.id } != policy.handler.nodeIds ||
            flow.graph.nodes.any { it is CanonicalTryNode }
        ) {
            add("Legacy failure tail is not an exact compatibility mirror of typed graph meaning.")
        }
    }

    private fun findingClosureErrors(): List<String> = buildList {
        val inventory = ArchitectureRecoveryConformanceInventory.load(rootDir)
        addAll(
            Ar02FindingClosureEvidenceValidator.errors(
                entries = Ar02FindingClosureCatalog.entries,
                declaredChecks = inventory.checks.toSet(),
                rootDir = rootDir
            )
        )

        val report = File(rootDir, REPORT_PATH)
        if (!report.isFile) {
            add("AR-02 completion report is missing: $REPORT_PATH")
        } else {
            val text = report.readText()
            listOf("F-02", "F-08", "F-15", "AR-03", "not activated").forEach { evidence ->
                if (evidence !in text) add("AR-02 completion report omits '$evidence'.")
            }
        }

        val workPackage = read(WORK_PACKAGE_PATH)
        if (
            !workPackage.contains("\nstatus: complete\n") ||
            !workPackage.contains(
                "  - id: \"AR-02E\"\n    name: \"Integrated Semantic Closure\"\n    status: \"complete\""
            )
        ) {
            add("AR-02 work package is not closed through AR-02E.")
        }

        val roadmap = read(RECOVERY_ROADMAP_PATH)
        if (
            !roadmap.contains(
                "  - id: \"AR-02\"\n    name: \"Flow-Sensitive Workflow, Data and Failure Semantics\"\n    order: 3\n    status: \"completed\""
            ) ||
            !roadmap.contains("nextItem: \"AR-03\"") ||
            !roadmap.contains("activationState: \"not-activated\"")
        ) {
            add("Architecture Recovery roadmap does not close AR-02 and select unactivated AR-03.")
        }

        val globalRoadmap = read(GLOBAL_ROADMAP_PATH)
val yamlScalars: (String) -> Map<String, String> = { content ->
    content.lineSequence()
        .map(String::trim)
        .filter { line -> ':' in line }
        .associate { line ->
            line.substringBefore(':').trim() to
                line.substringAfter(':').trim().trim('"')
        }
}
val globalHeaderFields = yamlScalars(globalRoadmap.substringBefore("currentDecision:"))
val globalDecisionFields = yamlScalars(globalRoadmap.substringAfterLast("currentDecision:"))
if (
    globalHeaderFields["currentTrack"] != "" ||
    globalDecisionFields["completedItem"] != "0.9.7.10" ||
    globalDecisionFields["completedItemName"] != "Bounded Semantic Closure Gate" ||
    globalDecisionFields["nextItem"] != "" ||
    globalDecisionFields["nextItemName"] != "" ||
    globalDecisionFields["nextItemStream"] != "" ||
    "completedRecoveryItem" in globalDecisionFields ||
    "nextRecoveryItem" in globalDecisionFields ||
    "AR-02" in globalDecisionFields.values ||
    "AR-03" in globalDecisionFields.values
) {
    add("AR-02 closure altered the terminal global roadmap focus.")
}

        val postToolchain = read(POST_TOOLCHAIN_ROADMAP_PATH)
        if (
            !postToolchain.contains("completedItem: \"AR-02\"") ||
            !postToolchain.contains("nextItem: \"AR-03\"") ||
            !postToolchain.contains("activationState: \"not-activated\"")
        ) {
            add("Post-toolchain recovery state does not retain AR-03 as an unactivated successor.")
        }

        val releaseState = read(RELEASE_STATE_PATH)
        if (
            !releaseState.contains("completedRecoveryItem: \"AR-02\"") ||
            !releaseState.contains("nextRecoveryItem: \"AR-03\"") ||
            !releaseState.contains("nextRecoveryActivationState: \"not-activated\"") ||
            !releaseState.contains("nextItem: \"\"")
        ) {
            add("Release state does not separate recovery succession from terminal global focus.")
        }

        val staleClaims = mapOf(
            "REPORT.md" to
                "canonical flow-level error handling is recognized only from planner provenance",
            "CHANGELOG-ADAPTERS.md" to
                "recognize the canonical flow-level handler only from planner provenance",
            "docs/A0_4_CONTROL_REQUIREMENT_MATERIALIZATION.md" to
                "the planner-generated identifier matches `onError_<n>`"
        )
        staleClaims.forEach { (path, claim) ->
            if (claim in read(path)) {
                add("$path still presents retired synthetic-handler authority as current behavior.")
            }
        }

        val productRoots = listOf(
            "src/main/kotlin/org/flowlang/adapters",
            "src/main/kotlin/org/flowlang/targets",
            "src/main/kotlin/org/flowlang/generators",
            "src/main/kotlin/org/flowlang/cli"
        )
        val forbiddenShapeAuthority = listOf(
            "FLOW_ERROR_HANDLER_ID",
            "canonicalFlowHandler",
            "lastOrNull() as? TryPlanNode"
        )
        productRoots.flatMap { path ->
            File(rootDir, path).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }.forEach { file ->
            val source = file.readText()
            forbiddenShapeAuthority.filter(source::contains).forEach { term ->
                add(
                    "${file.relativeTo(rootDir).invariantSeparatorsPath} retains " +
                        "synthetic handler authority '$term'."
                )
            }
        }

        val closureSource = read(CLOSURE_SOURCE_PATH)
        val frozenBoundaryCalls = listOf(
            "CliTargetEvidence" + "Authority(",
            "TargetSelection" + "Authority.",
            "FlowSourceCompilation" + "Input("
        )
        frozenBoundaryCalls.filter(closureSource::contains).forEach { call ->
            add("AR-02E introduced a direct frozen-authority call instead of composing existing gates: $call")
        }
    }

    private fun compileIntent(text: String, identity: String): CompilationUnit =
        IntentYamlFrontend(compiler).compileText(text, identity).requireAccepted()

    private fun compileAi(intent: org.flowlang.intent.IntentDocument, identity: String): CompilationUnit {
        val request = AiIntentRequest(
            userText = "Compile the AR-02E semantic closure fixture.",
            mode = NormalizationMode.DRAFT
        )
        val response = AiIntentResponse(
            normalizedIntent = intent,
            report = NormalizationReport(
                mode = NormalizationMode.DRAFT,
                classification = IntentClassification("ar-02e", 1.0),
                confidence = ConfidenceScore(1.0, 1.0, 1.0, 1.0, 1.0)
            )
        )
        return ReviewedAiProposalFrontend(compiler).compile(
            ReviewedAiProposal(
                providerId = "ar-02e-conformance",
                request = request,
                response = response,
                sourceIdentity = identity,
                sourceName = "$identity.json"
            )
        ).requireAccepted()
    }

    private fun compileFlowSource(source: String, identity: String): CompilationUnit {
        val file = File.createTempFile("$identity-", ".flow")
        return try {
            file.writeText(source)
            FlowSourceFrontend(compiler, FlowParser()).compile(file).requireAccepted()
        } finally {
            file.delete()
        }
    }

    private fun semanticPlanSet(unit: CompilationUnit): WorkflowExecutionPlanSet {
        val planSet = unit.workflowPlanSet
        return planSet.copy(
            sourceIntent = null,
            loweringReport = null,
            workflows = planSet.workflows.map { view ->
                view.copy(
                    executionPlan = view.executionPlan.copy(sourceIntent = null, loweringReport = null),
                    canonicalPlan = view.canonicalPlan.copy(sourceIntent = null, loweringReport = null)
                )
            }
        )
    }

    private fun requireCheck(checks: List<ConformanceCheck>, name: String): ConformanceCheck =
        checks.singleOrNull { it.name == name }
            ?: ConformanceCheck(name, false, "Required predecessor conformance check is missing or duplicated.")

    private fun read(path: String): String {
        val file = File(rootDir, path)
        require(file.isFile) { "AR-02E evidence source is missing: $path" }
        return file.readText()
    }

    private fun resultCheck(name: String, block: () -> List<String>): ConformanceCheck {
        val result = runCatching(block)
        val errors = result.getOrNull().orEmpty() + listOfNotNull(
            result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        )
        return ConformanceCheck(
            name = name,
            passed = errors.isEmpty(),
            message = errors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )
    }

    companion object {
        const val FRONTEND_MATRIX = "architecture-recovery.ar-02.integrated-frontend-matrix"
        const val MUTATION_MATRIX = "architecture-recovery.ar-02.integrated-mutation-matrix"
        const val TARGET_MATRIX = "architecture-recovery.ar-02.integrated-target-gating-matrix"
        const val PUBLIC_COMPATIBILITY_MATRIX =
            "architecture-recovery.ar-02.integrated-public-compatibility-matrix"
        const val FINDING_CLOSURE = "architecture-recovery.ar-02.finding-closure-evidence"

        private const val REPORT_PATH =
            ".flow-agent/reports/ar-02-flow-sensitive-workflow-data-failure-semantics.md"
        private const val WORK_PACKAGE_PATH =
            ".flow-agent/work-packages/AR-02-flow-sensitive-workflow-data-failure-semantics.yaml"
        private const val RECOVERY_ROADMAP_PATH = ".flow-agent/roadmap-architecture-recovery.yaml"
        private const val GLOBAL_ROADMAP_PATH = ".flow-agent/roadmap.yaml"
        private const val POST_TOOLCHAIN_ROADMAP_PATH = ".flow-agent/roadmap-post-toolchain.yaml"
        private const val RELEASE_STATE_PATH = ".flow-agent/release-state.yaml"
        private const val CLOSURE_SOURCE_PATH =
            "src/main/kotlin/org/flowlang/conformance/Ar02IntegratedSemanticClosureChecks.kt"

        private val SINGLE_WORKFLOW_INTENT = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: ar02e-single
            inputs:
              - name: environment
                type: option[dev,prod]
                required: true
            workflows:
              - name: main
                kind: CUSTOM
                steps:
                  - id: work
                    capability: CUSTOM
                  - id: approve-prod
                    capability: APPROVE
                    requires: [work]
            failure:
              notify: true
              rollback: true
              stopOnError: true
        """.trimIndent()

        private val MULTI_WORKFLOW_INTENT = """
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

        private val INTEGRATED_FLOW_SOURCE = """
            flow "ar02e-integrated" {
              input {
                condition: boolean required
              }
              steps {
                if condition {
                  set left = "left"
                } else {
                  set right = "right"
                }
                set joined = merge(left, right)
                approve manual {
                  message: joined
                } -> approved
              }
              on error {
                approve manual {
                  message: error.message
                } -> handlerApproval
              }
            }
        """.trimIndent()
    }
}
