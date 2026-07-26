package org.flowlang.conformance

import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.StandardSurfaceStatusAuthority
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import java.io.File

internal class ProjectionSurfaceChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV044BehavioralGeneratorEquivalence(),
        checkV045StandardSurfaceFreeze(),
        checkV046CompatibilityMigrationPolicy(),
        checkV047ReferenceIntentCorpus()
    )

    private fun checkV044BehavioralGeneratorEquivalence(): ConformanceCheck = runCheck("v0.4.4.behavioral-generator-equivalence") {
        val plan = ExecutionPlan(
            flowName = "behavioral",
            nodes = listOf(
                TaskNode(id = "build", module = "ci", action = "build", target = "all"),
                TaskNode(id = "test", module = "ci", action = "test", target = "all", dependsOn = listOf("build")),
                ConditionNode(
                    id = "env-gate", condition = "env == 'prod'",
                    then = listOf(TaskNode(id = "deploy", module = "cd", action = "deploy", target = "all", dependsOn = listOf("test"))),
                    otherwise = listOf(TaskNode(id = "notify", module = "ops", action = "notify", target = "all", dependsOn = listOf("test")))
                )
            )
        )
        val expectedTasks = setOf("build", "test", "deploy", "notify")
        listOf("jenkins", "github-actions", "tekton").forEach { target ->
            val manifest = manifestPipeline.generateDiagnosticEvidence(diagnosticMaterializationRequest(plan, target, "conformance:v0.4.4"))
            val taskNames = mutableSetOf<String>()
            var deployGuard: String? = null
            fun walk(steps: List<TargetStep>, guard: String?) {
                steps.forEach { step ->
                    when {
                        step.type == "condition" -> walk(step.children, step.params["condition"])
                        step.children.isNotEmpty() -> walk(step.children, guard)
                        step.type == "action" -> {
                            taskNames += step.name
                            if (step.name == "deploy") deployGuard = guard
                        }
                    }
                }
            }
            manifest.jobs.forEach { job -> walk(job.steps, job.metadata["condition"]) }
            require(taskNames == expectedTasks) { "Target $target dropped or added tasks: $taskNames" }
            require(!deployGuard.isNullOrBlank()) { "Target $target dropped the guard on the conditional deploy task." }
        }
    }

    private fun checkV045StandardSurfaceFreeze(): ConformanceCheck = runCheck("v0.4.5.standard-surface-freeze") {
        val surface = StandardSurface.publicSurface()
        require(surface.status == "PASS") { "Public standard surface must pass computed validation." }
        require(surface.internalArtifacts.isEmpty()) { "Internal artifacts must not be part of the public surface." }
        require(surface.entries.isNotEmpty()) { "Public surface must declare entries." }
        surface.entries.forEach { entry ->
            require(File(rootDir, entry.schema).isFile) {
                "Public surface entry '${entry.artifact}' declares schema '${entry.schema}' which does not exist on disk."
            }
            require(entry.stability in setOf("stable", "draft", "experimental", "internal"))
        }
        val stable = surface.stableArtifacts.toSet()
        require("intent.schema.json" in stable)
        require("execution-plan.schema.json" in stable)
        require("public-standard-surface.json" in stable)
        require(surface.requiredChangeGates.contains("negative-conformance-review"))
        require(StandardSurfaceStatusAuthority.publicSurface(emptyList(), surface.requiredChangeGates) == "FAIL") {
            "An empty public surface must not self-certify PASS."
        }
    }

    private fun checkV046CompatibilityMigrationPolicy(): ConformanceCheck = runCheck("v0.4.6.compatibility-migration-policy") {
        val policy = StandardSurface.compatibilityMigrationPolicy()
        require(policy.status == "PASS") { "Compatibility and migration policy must pass computed validation." }
        require(policy.deprecationWindowMinorReleases >= 2)
        require(policy.compatibilityRules.any { it.id == "minor.add-optional-field" && it.allowedInMinor })
        require(policy.compatibilityRules.any {
            it.id == "breaking.remove-public-field" && !it.allowedInMinor && it.requiresMigrationNote
        })
        require(policy.migrationArtifacts.contains("CHANGELOG.md"))
        val dishonest = policy.compatibilityRules.map { rule ->
            if (rule.category == "breaking") rule.copy(allowedInMinor = true) else rule
        }
        require(
            StandardSurfaceStatusAuthority.compatibilityMigrationPolicy(
                dishonest,
                policy.deprecationWindowMinorReleases,
                policy.migrationArtifacts,
                policy.breakingChangeGate
            ) == "FAIL"
        ) { "A breaking rule allowed in a minor release must fail policy status." }
    }

    private fun checkV047ReferenceIntentCorpus(): ConformanceCheck = runCheck("v0.4.7.reference-intent-corpus") {
        val corpus = StandardSurface.referenceIntentCorpus()
        require(corpus.status == "PASS") { "Reference intent corpus must pass computed validation." }
        require(corpus.scenarios.isNotEmpty())
        require(corpus.negativeScenarioIds.isNotEmpty())
        require(
            StandardSurfaceStatusAuthority.referenceIntentCorpus(
                corpus.scenarios,
                emptyList(),
                corpus.negativeScenarioIds
            ) == "FAIL"
        ) { "A corpus whose required id set is discarded must fail status validation." }

        val review = IntentProposalReview()
        corpus.scenarios.forEach { scenario ->
            val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest(scenario.inputText))
            val intent = response.normalizedIntent
            val capabilities = intent.workflows.flatMap { it.steps }.map { it.capability.name }.toSet()
            val requiredClarifications = response.report.openQuestions
                .filter { it.severity == ClarificationSeverity.REQUIRED }
                .map { it.field }
                .toSet()
            val decision = review.review(intent)
            val rejectionCodes = (decision as? IntentProposalDecision.Rejected)
                ?.violations
                ?.map { it.code }
                ?.toSet()
                .orEmpty()
            val blocked = requiredClarifications.isNotEmpty() || decision is IntentProposalDecision.Rejected
            val actualStatus = if (blocked) "BLOCKED" else "ACCEPTED"
            require(actualStatus == scenario.expectedStatus) {
                "Reference scenario '${scenario.id}': pipeline produced $actualStatus, expected ${scenario.expectedStatus}."
            }
            require(capabilities.containsAll(scenario.expectedCapabilities))
            require(requiredClarifications.containsAll(scenario.expectedRequiredClarifications))
            require(rejectionCodes.containsAll(scenario.expectedRejectionCodes))
            scenario.expectedEntities.forEach { (key, value) ->
                require(response.report.entities[key] == value)
            }
        }
    }
}
