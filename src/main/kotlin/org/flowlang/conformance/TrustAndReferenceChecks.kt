package org.flowlang.conformance

import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.intent.IntentDocument
import org.flowlang.modules.ModuleRegistry
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class TrustAndReferenceChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV066AiInputTrustBoundary(),
        checkV067StandardExampleBundle(),
        checkV068CompatibilityPromise(),
        checkV070ReferenceCorpusExecutionHarness()
    )

    private fun checkV066AiInputTrustBoundary(): ConformanceCheck = runCheck("v0.6.6.ai-input-trust-boundary") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.6.ai-input-trust-boundary"
        val rules = StandardSurface.aiInputTrustBoundary()
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy to Kubernetes with health verification."))
        var blockedByRequiredClarification = false
        try {
            response.assertUsableForLowering()
        } catch (ignored: IllegalStateException) {
            blockedByRequiredClarification = true
        }

        require(activeStandardAtLeast(0, 6, 6)) {
            "AI input trust boundary must carry standardVersion 0.6.6 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the AI input trust-boundary gate."
        }
        require(rules.all { it.blocking }) {
            "AI trust-boundary rules must be blocking when violated."
        }
        require(rules.map { it.id }.containsAll(listOf("ai.proposal-only", "ai.required-clarification-blocks", "ai.no-critical-defaults", "ai.validation-before-plan", "ai.target-syntax-after-plan", "ai.audit-trace"))) {
            "AI trust-boundary rule set is incomplete."
        }
        require(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED }) {
            "Incomplete AI/user intent must produce required clarification questions."
        }
        require(blockedByRequiredClarification) {
            "AI response with required clarification must be unusable for lowering."
        }
        require(response.report.guardrails.any { it.contains("normalized into IntentDocument") }) {
            "AI normalization report must expose the intent-normalization trust boundary."
        }
    }

    private fun checkV067StandardExampleBundle(): ConformanceCheck = runCheck("v0.6.7.standard-example-bundle") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.7.standard-example-bundle"
        val examples = StandardSurface.standardExampleBundle()
        val corpusById = StandardSurface.referenceIntentCorpus().scenarios.associateBy { it.id }

        require(activeStandardAtLeast(0, 6, 7)) {
            "Standard example bundle must carry standardVersion 0.6.7 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the standard-example bundle gate."
        }
        require(examples.size >= 5) {
            "Standard example bundle must contain the five golden examples."
        }
        require(examples.all { it.intentScenarioId in corpusById }) {
            "Every standard example must reference an existing reference intent scenario."
        }
        require(examples.any { it.expectedStatus == "ACCEPTED" } && examples.any { it.expectedStatus == "BLOCKED" }) {
            "Standard example bundle must include both accepted and blocked examples."
        }
        require(examples.all { "execution-plan.json" in it.expectedArtifacts && "intent-decision-report.json" in it.expectedArtifacts }) {
            "Every standard example must cite core public artifacts."
        }
    }

    private fun checkV068CompatibilityPromise(): ConformanceCheck = runCheck("v0.6.8.compatibility-promise") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.8.compatibility-promise"
        val promises = StandardSurface.compatibilityPromiseRules()
        val policy = StandardSurface.compatibilityMigrationPolicy()

        require(activeStandardAtLeast(0, 6, 8)) {
            "Compatibility promise must carry standardVersion 0.6.8 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the compatibility-promise gate."
        }
        require(promises.map { it.category }.toSet().containsAll(setOf("patch", "minor", "major", "all"))) {
            "Compatibility promise must cover patch, minor, major and all-release rules."
        }
        require(promises.all { it.requiresConformanceVector }) {
            "Every compatibility promise rule must require conformance-vector coverage."
        }
        require(policy.compatibilityRules.any { it.id == "minor.add-optional-field" && it.allowedInMinor }) {
            "Compatibility migration policy must keep additive minor changes allowed."
        }
        require(policy.compatibilityRules.any { it.category == "breaking" && !it.allowedInMinor && it.requiresMigrationNote }) {
            "Compatibility migration policy must keep breaking changes out of minor releases."
        }
    }

    private fun checkV070ReferenceCorpusExecutionHarness(): ConformanceCheck = runCheck("v0.7.0.reference-corpus-execution-harness") {
        val releaseProfile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val harnessContract = StandardSurface.referenceCorpusExecutionHarness()
        val requiredGate = "v0.7.0.reference-corpus-execution-harness"
        val harnessReport = ReferenceCorpusExecutionHarness(registry).execute()
        val index = ConformanceVectorIndexBuilder(rootDir).build(
            runnerChecks = allRunnerChecksForVectorIndex(),
            releaseProfileChecks = releaseProfile.requiredConformanceChecks
        )

        require(activeStandardAtLeast(0, 7, 0)) {
            "Reference corpus execution harness must carry standardVersion 0.7.0 or later."
        }
        require(harnessContract.status == "PASS") {
            "Reference corpus execution harness contract must pass."
        }
        require(harnessContract.replayStages.contains("normalize scenario input with ScenarioPackIntentNormalizer")) {
            "Harness contract must state that scenarios are replayed through the real normalizer."
        }
        require(harnessContract.assertions.any { it.id == "corpus.accepted-lowers" && it.blocking }) {
            "Harness contract must require accepted scenarios to lower."
        }
        require(harnessContract.assertions.any { it.id == "corpus.blocked-stays-blocked" && it.blocking }) {
            "Harness contract must require blocked scenarios to remain blocked."
        }
        require(requiredGate in releaseProfile.requiredConformanceChecks) {
            "Release profile must require the reference corpus execution harness gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest must publish the reference corpus execution harness gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate level must require the reference corpus execution harness gate."
        }
        require(harnessReport.status == "PASS") {
            harnessReport.failures.joinToString(prefix = "Reference corpus execution failed: ", separator = "; ")
        }
        require(harnessReport.scenarioCount == StandardSurface.referenceIntentCorpus().scenarios.size) {
            "Harness must execute every reference corpus scenario."
        }
        require(harnessReport.lowerableAcceptedCount == harnessReport.acceptedCount) {
            "Every accepted reference scenario must lower successfully."
        }
        require(harnessReport.blockedLoweringCount == harnessReport.blockedCount) {
            "Every blocked reference scenario must be non-lowerable."
        }
        require(index.status == "PASS") {
            "Reference corpus harness requires vector index closure: missingRequired=${index.vectorsMissingRequiredCheck}, missingRunner=${index.checksMissingFromRunner}, missingReleaseVectors=${index.releaseProfileChecksMissingVector}"
        }
        require(requiredGate in index.requiredChecksFromVectors) {
            "The v0.7.0 reference corpus execution harness gate must be represented by a public vector."
        }
    }
}
