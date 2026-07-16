package org.flowlang.conformance

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.artifacts.StandardSurface
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.TaskNode
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

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
        val generators = listOf("jenkins", "github-actions", "tekton").associateWith {
            projections.requireProvider(it).generator
        }
        generators.forEach { (target, generator) ->
            val manifest = generator.generate(plan, CompatibilityAnalyzer(targets).analyze(plan, target))
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
        require(surface.status == "PASS") { "Public standard surface must pass." }
        require(surface.internalArtifacts.isEmpty()) { "Internal artifacts must not be part of the public surface." }
        require(surface.entries.isNotEmpty()) { "Public surface must declare entries." }
        surface.entries.forEach { entry ->
            require(File(rootDir, entry.schema).isFile) {
                "Public surface entry '${entry.artifact}' declares schema '${entry.schema}' which does not exist on disk."
            }
            require(entry.stability in setOf("stable", "draft", "experimental", "internal")) {
                "Public surface entry '${entry.artifact}' has an unknown stability '${entry.stability}'."
            }
        }
        val stable = surface.stableArtifacts.toSet()
        require("intent.schema.json" in stable) { "Intent schema must be in the stable public surface." }
        require("execution-plan.schema.json" in stable) { "Execution plan schema must be in the stable public surface." }
        require("public-standard-surface.json" in stable) { "Public surface report must declare itself." }
        require(surface.requiredChangeGates.contains("negative-conformance-review")) {
            "Public surface changes must require negative conformance review."
        }
    }

    private fun checkV046CompatibilityMigrationPolicy(): ConformanceCheck = runCheck("v0.4.6.compatibility-migration-policy") {
        val policy = StandardSurface.compatibilityMigrationPolicy()
        require(policy.status == "PASS") { "Compatibility and migration policy must pass." }
        require(policy.deprecationWindowMinorReleases >= 2) { "Deprecation policy must provide a minimum two minor release window." }
        require(policy.compatibilityRules.any { it.id == "minor.add-optional-field" && it.allowedInMinor }) {
            "Adding optional public fields must be allowed in a minor release."
        }
        require(policy.compatibilityRules.any { it.id == "breaking.remove-public-field" && !it.allowedInMinor && it.requiresMigrationNote }) {
            "Removing a public field must be treated as a breaking change with migration notes."
        }
        require(policy.migrationArtifacts.contains("CHANGELOG.md")) { "Migration policy must require changelog coverage." }
    }

    private fun checkV047ReferenceIntentCorpus(): ConformanceCheck = runCheck("v0.4.7.reference-intent-corpus") {
        val corpus = StandardSurface.referenceIntentCorpus()
        require(corpus.status == "PASS") { "Reference intent corpus must pass." }
        require(corpus.scenarios.isNotEmpty()) { "Reference corpus must contain scenarios." }
        require(corpus.negativeScenarioIds.isNotEmpty()) { "Reference corpus must keep negative (BLOCKED) scenarios." }
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
            val rejectionCodes = (decision as? IntentProposalDecision.Rejected)?.violations?.map { it.code }?.toSet().orEmpty()
            val blocked = requiredClarifications.isNotEmpty() || decision is IntentProposalDecision.Rejected
            val actualStatus = if (blocked) "BLOCKED" else "ACCEPTED"
            require(actualStatus == scenario.expectedStatus) {
                "Reference scenario '${scenario.id}': pipeline produced $actualStatus, expected ${scenario.expectedStatus}."
            }
            require(capabilities.containsAll(scenario.expectedCapabilities)) {
                "Reference scenario '${scenario.id}': normalized intent is missing capabilities ${scenario.expectedCapabilities.toSet() - capabilities}."
            }
            require(requiredClarifications.containsAll(scenario.expectedRequiredClarifications)) {
                "Reference scenario '${scenario.id}': missing required clarifications ${scenario.expectedRequiredClarifications.toSet() - requiredClarifications}."
            }
            require(rejectionCodes.containsAll(scenario.expectedRejectionCodes)) {
                "Reference scenario '${scenario.id}': missing gate rejection codes ${scenario.expectedRejectionCodes.toSet() - rejectionCodes}."
            }
            scenario.expectedEntities.forEach { (key, value) ->
                require(response.report.entities[key] == value) {
                    "Reference scenario '${scenario.id}': expected entity '$key'='$value' but normalizer extracted '${response.report.entities[key]}'."
                }
            }
        }
    }
}
