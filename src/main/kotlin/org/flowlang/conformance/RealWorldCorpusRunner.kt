package org.flowlang.conformance

import org.flowlang.materialization.CompatibilityMaterializationBoundary
import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import java.util.ArrayDeque
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentList
import org.flowlang.intent.IntentObject
import org.flowlang.intent.IntentRef
import org.flowlang.intent.IntentValue
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.PlanNode
import org.flowlang.planner.TaskNode
import org.flowlang.targets.builtin.BuiltInTargetProjections

class RealWorldCorpusRunner(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry = ModuleRegistry.fromDirectory(File(rootDir, "modules")),
    private val targets: Map<String, TargetCapability> =
        TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")),
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    private val loader = RealWorldCorpusLoader(rootDir)
    private val manifestPipeline = TargetManifestGenerationPipeline(targets, projections, modules = org.flowlang.modules.ModuleRegistry())
    private val intentFrontend = IntentYamlFrontend(FrontendCompilerComposition.compiler(registry))

    fun load(): LoadedRealWorldCorpus = loader.load()

    fun evaluateAll(corpus: LoadedRealWorldCorpus = load()): List<RealWorldEvaluationResult> =
        corpus.cases.map { evaluate(it) }

    fun evaluate(
        case: LoadedRealWorldCase,
        evidenceCheckPrefix: String = CASE_CHECK_PREFIX
    ): RealWorldEvaluationResult {
        val intentFile = requiredFile(case.directory, case.definition.intent, "canonical intent")
        val evaluation = evaluateIntent(
            caseId = case.definition.id,
            intentFile = intentFile,
            expected = case.expectedPlan,
            targetExpectations = case.targetExpectations,
            sourceSemantics = case.source.semanticLoad.toSet()
        )
        val evidenceMismatches = buildList {
            if (case.evidence.outcome != evaluation.outcome) {
                add("Evidence outcome ${case.evidence.outcome} != actual ${evaluation.outcome}.")
            }
            if (case.evidence.expectedDiagnostics.sorted() != evaluation.diagnostics.sorted()) {
                add("Evidence diagnostics ${case.evidence.expectedDiagnostics.sorted()} != actual ${evaluation.diagnostics.sorted()}.")
            }
            val requiredCheck = "$evidenceCheckPrefix.${case.definition.id.lowercase()}"
            if (requiredCheck !in case.evidence.validatedBy) {
                add("Evidence validatedBy must include '$requiredCheck'.")
            }
        }
        return evaluation.copy(mismatches = evaluation.mismatches + evidenceMismatches)
    }

    fun evaluateMutations(case: LoadedRealWorldCase): List<RealWorldEvaluationResult> =
        case.mutations.map { (mutation, mutationDir) ->
            evaluateIntent(
                caseId = "${case.definition.id}:${mutation.id}",
                intentFile = requiredFile(mutationDir, mutation.intent, "mutation intent"),
                expected = mutation.expectedPlan(),
                targetExpectations = RealWorldTargetAssessmentDocument(),
                sourceSemantics = emptySet()
            )
        }

    private fun evaluateIntent(
        caseId: String,
        intentFile: File,
        expected: RealWorldExpectedPlan,
        targetExpectations: RealWorldTargetAssessmentDocument,
        sourceSemantics: Set<String>
    ): RealWorldEvaluationResult {
        val semanticDiagnostics = linkedSetOf<String>()
        val mismatches = mutableListOf<String>()
        var authoredIntent: IntentDocument? = null
        val build = runCatching {
            val loaded = intentFrontend.load(intentFile)
            authoredIntent = loaded.value
            semanticDiagnostics += missingReferenceDiagnostics(loaded.value)
            if (semanticDiagnostics.isNotEmpty()) return@runCatching PlanBuild(null)

            when (val compilation = intentFrontend.compile(loaded)) {
                is CompilationResult.Accepted -> PlanBuild(compilation.unit.executionPlan)
                is CompilationResult.Rejected -> {
                    semanticDiagnostics += compilation.rejection.diagnostics.map { diagnostic -> diagnostic.code }
                    PlanBuild(null)
                }
            }
        }.getOrElse { error ->
            mismatches += "Production pipeline failed for '$caseId': ${error.message ?: error.javaClass.simpleName}."
            null
        }

        authoredIntent?.let { semanticDiagnostics += compareAuthoredIntent(it, expected) }
        val plan = build?.plan
        if ((plan != null) != expected.generated) {
            mismatches += "Expected generated=${expected.generated}, actual=${plan != null}."
        }
        requiredSourceFeatures(sourceSemantics).forEach { feature ->
            if (feature !in expected.requiredFeatures) {
                mismatches += "Expected plan omitted source-observed feature '$feature'."
            }
        }
        if (plan != null) semanticDiagnostics += comparePlan(plan, expected, sourceSemantics)

        val outcome = determineOutcome(semanticDiagnostics)
        if (outcome != expected.expectedOutcome) {
            mismatches += "Expected outcome ${expected.expectedOutcome}, actual $outcome."
        }
        if (semanticDiagnostics.sorted() != expected.expectedDiagnostics.sorted()) {
            mismatches += "Expected diagnostics ${expected.expectedDiagnostics.sorted()}, actual ${semanticDiagnostics.sorted()}."
        }

        val actualTargets = if (plan == null) emptyList() else targetExpectations.assessments.map { expectation ->
            val actual = assessTarget(caseId, plan, expectation.target, semanticDiagnostics)
            if (expectation.allowedResults.isNotEmpty() && actual.result !in expectation.allowedResults) {
                mismatches += "Target '${expectation.target}' result ${actual.result} is not in ${expectation.allowedResults}."
            }
            if (expectation.mustNotBeExecutable && actual.executable) {
                mismatches += "Target '${expectation.target}' was falsely classified executable."
            }
            actual
        }

        return RealWorldEvaluationResult(
            caseId = caseId,
            outcome = outcome,
            planGenerated = plan != null,
            diagnostics = semanticDiagnostics.sorted(),
            targetAssessments = actualTargets,
            mismatches = mismatches
        )
    }

    private fun requiredSourceFeatures(sourceSemantics: Set<String>): Set<String> = buildSet {
        if (DYNAMIC_MATRIX_SOURCE_SEMANTIC in sourceSemantics) add(DYNAMIC_MATRIX_FEATURE)
        if (RUNTIME_PLAN_SOURCE_SEMANTIC in sourceSemantics) add(RUNTIME_PLAN_FEATURE)
    }

    private fun compareAuthoredIntent(
        intent: IntentDocument,
        expected: RealWorldExpectedPlan
    ): List<String> {
        val diagnostics = linkedSetOf<String>()
        val steps = intent.workflows.flatMap { it.steps }
        val ordering = steps
            .flatMap { target -> target.requires.map { source -> source to target.id } }
            .groupBy({ it.first }, { it.second })

        expected.requiredRelations
            .filter { it.kind == PlanDependencyKind.ORDERING }
            .forEach { relation ->
                if (!reachable(relation.source, relation.target, ordering)) {
                    diagnostics += REQUIRED_RELATION_MISSING
                }
            }
        return diagnostics.toList()
    }

    private fun comparePlan(
        plan: ExecutionPlan,
        expected: RealWorldExpectedPlan,
        sourceSemantics: Set<String>
    ): List<String> {
        val diagnostics = linkedSetOf<String>()
        val nodes = PlanDependencyRelations.flatten(plan.nodes)
        val sourceNodes = nodes.mapNotNull { node -> sourceId(node)?.let { it to node } }.toMap()

        val actualSources = sourceNodes.keys.sorted()
        val expectedSources = expected.tasks.map { it.sourceId }.sorted()
        if (expected.exactTaskSources && actualSources != expectedSources) diagnostics += EXPECTED_TASK_MISMATCH
        expected.tasks.forEach { task ->
            val node = sourceNodes[task.sourceId]
            if (node == null) {
                diagnostics += EXPECTED_TASK_MISSING
            } else {
                val capability = when (node) {
                    is TaskNode -> node.semanticCapability
                    is ApprovalNode -> "APPROVE"
                    else -> null
                }
                if (task.semanticCapability != null && capability != task.semanticCapability) diagnostics += EXPECTED_TASK_MISMATCH
                if (task.nodeKind != null && node.kind != task.nodeKind) diagnostics += EXPECTED_TASK_MISMATCH
            }
        }

        expected.requiredRelations.forEach { relation ->
            val sourceNodeId = sourceNodes[relation.source]?.id
            val targetNodeId = sourceNodes[relation.target]?.id
            val present = sourceNodeId != null && targetNodeId != null && plan.dependencyRelations.any { actual ->
                actual.sourceNodeId == sourceNodeId && actual.targetNodeId == targetNodeId &&
                    actual.kind == relation.kind && (relation.channel == null || actual.channel == relation.channel)
            }
            if (!present) diagnostics += REQUIRED_RELATION_MISSING
        }

        val ordering = plan.dependencyRelations
            .filter { it.kind == PlanDependencyKind.ORDERING && it.sourceNodeId != null }
            .groupBy({ requireNotNull(it.sourceNodeId) }, PlanDependencyRelation::targetNodeId)
        expected.forbiddenOrdering.forEach { forbidden ->
            val sourceNodeId = sourceNodes[forbidden.source]?.id
            val targetNodeId = sourceNodes[forbidden.target]?.id
            if (sourceNodeId != null && targetNodeId != null && reachable(sourceNodeId, targetNodeId, ordering)) {
                diagnostics += REQUIRED_PARALLELISM_SERIALIZED
            }
        }

        if (DYNAMIC_MATRIX_SOURCE_SEMANTIC in sourceSemantics && !hasExplicitDynamicMatrixRepresentation(plan)) {
            diagnostics += DYNAMIC_MATRIX_NOT_REPRESENTED
        }
        if (RUNTIME_PLAN_SOURCE_SEMANTIC in sourceSemantics && !hasExplicitRuntimePlanRepresentation(plan)) {
            diagnostics += RUNTIME_PLAN_NOT_REPRESENTED
        }

        expected.requiredFeatures
            .filterNot { it == DYNAMIC_MATRIX_FEATURE || it == RUNTIME_PLAN_FEATURE }
            .forEach { feature ->
                when (feature) {
                    "typed-human-input" -> {
                        val hasTypedChoice = plan.inputs.any { it.choices.isNotEmpty() }
                        val hasApprovalOutput = nodes.filterIsInstance<ApprovalNode>().any { it.outputs.isNotEmpty() }
                        if (!hasTypedChoice || !hasApprovalOutput) diagnostics += TYPED_HUMAN_INPUT_NOT_REPRESENTED
                    }
                    "artifact-identity" -> {
                        val hasNamedContinuity = plan.dependencyRelations.any {
                            it.kind == PlanDependencyKind.VALUE && !it.channel.isNullOrBlank()
                        }
                        if (!hasNamedContinuity) diagnostics += ARTIFACT_IDENTITY_NOT_REPRESENTED
                    }
                    "fan-in" -> {
                        val incoming = plan.dependencyRelations
                            .filter { it.kind == PlanDependencyKind.ORDERING && it.sourceNodeId != null }
                            .groupingBy { it.targetNodeId }
                            .eachCount()
                        if (incoming.values.none { it >= 2 }) diagnostics += REQUIRED_RELATION_MISSING
                    }
                    "data-transform" -> {
                        val transformTasks = nodes.filterIsInstance<TaskNode>().filter {
                            it.semanticCapability == "DATA_TRANSFORM" || it.semanticCapability == "TRANSFORM"
                        }
                        if (transformTasks.isEmpty()) diagnostics += DATA_TRANSFORM_NOT_REPRESENTED
                    }
                    "infrastructure-provision" -> {
                        val provisionTasks = nodes.filterIsInstance<TaskNode>().filter {
                            it.semanticCapability == "PROVISION"
                        }
                        if (provisionTasks.isEmpty()) diagnostics += INFRASTRUCTURE_PROVISION_NOT_REPRESENTED
                    }
                }
            }
        return diagnostics.toList()
    }

    private fun hasExplicitDynamicMatrixRepresentation(plan: ExecutionPlan): Boolean =
        PlanDependencyRelations.flatten(plan.nodes).any { node -> node.kind == DYNAMIC_MATRIX_NODE_KIND }

    private fun hasExplicitRuntimePlanRepresentation(plan: ExecutionPlan): Boolean =
        PlanDependencyRelations.flatten(plan.nodes).any { node -> node.kind == RUNTIME_PLAN_NODE_KIND }

    private fun assessTarget(
        caseId: String,
        plan: ExecutionPlan,
        target: String,
        semanticDiagnostics: Set<String>
    ): RealWorldTargetAssessmentActual {
        if (semanticDiagnostics.isNotEmpty()) {
            return RealWorldTargetAssessmentActual(
                target, RealWorldResult.BLOCKED_BY_TARGET_CAPABILITY, false,
                "Semantic preservation is incomplete: ${semanticDiagnostics.sorted().joinToString()}."
            )
        }
        if (target !in targets) {
            return RealWorldTargetAssessmentActual(
                target,
                RealWorldResult.BLOCKED_BY_TARGET_CAPABILITY,
                false,
                "Target is absent from the runtime registry."
            )
        }
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target, strict = false)
        if (compatibility.hasErrors) {
            return RealWorldTargetAssessmentActual(
                target, RealWorldResult.BLOCKED_BY_TARGET_CAPABILITY, false,
                compatibility.issues.joinToString { it.feature }
            )
        }
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(plan, target, strict = false)
        if (!readiness.generationAllowed) {
            return RealWorldTargetAssessmentActual(
                target, RealWorldResult.BLOCKED_BY_TARGET_CAPABILITY, false,
                readiness.blockers.joinToString { it.code }
            )
        }
        return runCatching {
            val selection = TargetSelectionAuthority.fromReferenceSnapshot(
                value = target,
                scenarioId = "real-world-${caseId.replace(':', '-')} ".trim(),
                targets = targets
            )
            val manifest = manifestPipeline.generate(
                CompatibilityMaterializationBoundary.executionRequest(
                    plan = plan,
                    selection = selection,
                    evidenceId = "conformance:real-world:$caseId:$target"
                )
            )
            val render = TargetRenderPolicy.evaluate(manifest)
            when (render.mode) {
                TargetRenderMode.EXECUTABLE -> RealWorldTargetAssessmentActual(target, RealWorldResult.SUPPORTED, true)
                TargetRenderMode.REVIEW_ONLY -> RealWorldTargetAssessmentActual(
                    target, RealWorldResult.SEMANTIC_ONLY, false, render.findings.joinToString { it.status }
                )
                TargetRenderMode.FAIL_FAST -> RealWorldTargetAssessmentActual(
                    target, RealWorldResult.BLOCKED_BY_TARGET_CAPABILITY, false, render.findings.joinToString { it.status }
                )
            }
        }.getOrElse { error ->
            RealWorldTargetAssessmentActual(
                target, RealWorldResult.BLOCKED_BY_TARGET_CAPABILITY, false,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun missingReferenceDiagnostics(intent: IntentDocument): List<String> {
        val known = linkedSetOf<String>()
        known += intent.inputs.map { it.name }
        val missing = mutableListOf<String>()
        intent.workflows.flatMap { it.steps }.forEach { step ->
            missing += step.params.values
                .flatMap(::referenceRoots)
                .filter { it !in known }
            known += step.id
            known += step.produces
        }
        return if (missing.isEmpty()) emptyList() else listOf(MISSING_VALUE_PRODUCER)
    }

    private fun referenceRoots(value: IntentValue): List<String> = when (value) {
        is IntentRef -> listOfNotNull(value.path.firstOrNull())
        is IntentList -> value.items.flatMap(::referenceRoots)
        is IntentObject -> value.fields.values.flatMap(::referenceRoots)
        else -> emptyList()
    }

    private fun sourceId(node: PlanNode): String? = when (node) {
        is TaskNode -> node.sourceId
        is ApprovalNode -> node.sourceId
        else -> null
    }

    private fun reachable(source: String, target: String, graph: Map<String, List<String>>): Boolean {
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.addLast(source)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            for (next in graph[current].orEmpty()) {
                if (next == target) return true
                queue.addLast(next)
            }
        }
        return false
    }

    private fun determineOutcome(diagnostics: Set<String>): RealWorldResult = when {
        MISSING_VALUE_PRODUCER in diagnostics -> RealWorldResult.INVALID_SOURCE_PIPELINE
        DYNAMIC_MATRIX_NOT_REPRESENTED in diagnostics || RUNTIME_PLAN_NOT_REPRESENTED in diagnostics ->
            RealWorldResult.UNSUPPORTED_DYNAMIC_CONSTRUCTION
        diagnostics.isNotEmpty() -> RealWorldResult.SEMANTIC_ONLY
        else -> RealWorldResult.SUPPORTED_WITH_BINDING
    }

    private fun requiredFile(base: File, path: String, label: String): File {
        require(path.isNotBlank()) { "Missing $label path under ${base.path}." }
        val file = File(base, path).canonicalFile
        require(file.path.startsWith(base.canonicalFile.path)) { "$label path escapes case package: $path" }
        require(file.isFile) { "Missing $label: ${file.path}" }
        return file
    }

    private data class PlanBuild(val plan: ExecutionPlan?)

    companion object {
        const val CORPUS_ROOT = "conformance/corpus/real-world"
        const val INTEGRITY_CHECK = "real-world-corpus.integrity"
        const val DOMAIN_COVERAGE_CHECK = "real-world-corpus.domain-coverage"
        const val MUTATION_CHECK = "real-world-corpus.mutations"
        const val DOMAIN_MUTATION_CHECK = "real-world-corpus.domain-mutation-polarity"
        const val CASE_CHECK_PREFIX = "real-world-corpus.case"
        const val MISSING_VALUE_PRODUCER = "REAL_WORLD_MISSING_VALUE_PRODUCER"
        const val EXPECTED_TASK_MISSING = "REAL_WORLD_EXPECTED_TASK_MISSING"
        const val EXPECTED_TASK_MISMATCH = "REAL_WORLD_EXPECTED_TASK_MISMATCH"
        const val REQUIRED_RELATION_MISSING = "REAL_WORLD_REQUIRED_RELATION_MISSING"
        const val REQUIRED_PARALLELISM_SERIALIZED = "REAL_WORLD_REQUIRED_PARALLELISM_SERIALIZED"
        const val DYNAMIC_MATRIX_NOT_REPRESENTED = "REAL_WORLD_DYNAMIC_MATRIX_NOT_REPRESENTED"
        const val RUNTIME_PLAN_NOT_REPRESENTED = "REAL_WORLD_RUNTIME_PLAN_NOT_REPRESENTED"
        const val DATA_TRANSFORM_NOT_REPRESENTED = "REAL_WORLD_DATA_TRANSFORM_NOT_REPRESENTED"
        const val INFRASTRUCTURE_PROVISION_NOT_REPRESENTED = "REAL_WORLD_INFRASTRUCTURE_PROVISION_NOT_REPRESENTED"
        const val TYPED_HUMAN_INPUT_NOT_REPRESENTED = "REAL_WORLD_TYPED_HUMAN_INPUT_NOT_REPRESENTED"
        const val ARTIFACT_IDENTITY_NOT_REPRESENTED = "REAL_WORLD_ARTIFACT_IDENTITY_NOT_REPRESENTED"
        private const val DYNAMIC_MATRIX_SOURCE_SEMANTIC = "dynamic-matrix-from-output"
        private const val DYNAMIC_MATRIX_FEATURE = "dynamic-matrix"
        private const val DYNAMIC_MATRIX_NODE_KIND = "DynamicMatrix"
        private const val RUNTIME_PLAN_SOURCE_SEMANTIC = "runtime-plan-generation"
        private const val RUNTIME_PLAN_FEATURE = "runtime-generated-plan"
        private const val RUNTIME_PLAN_NODE_KIND = "DynamicPlan"
    }
}
