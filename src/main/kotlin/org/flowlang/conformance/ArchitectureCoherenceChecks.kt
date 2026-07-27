package org.flowlang.conformance

import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.TaskNode
import org.flowlang.standard.StandardCheckScope
import org.flowlang.standard.StandardModel
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class ArchitectureCoherenceChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV071ArchitectureDebtCleanupAndDriftEnforcement(),
        checkV073StandardModelProjectionCoherence()
    )

    private fun checkV071ArchitectureDebtCleanupAndDriftEnforcement(): ConformanceCheck = runCheck("v0.7.1.architecture-debt-cleanup-and-drift-enforcement") {
        val requiredGate = "v0.7.1.architecture-debt-cleanup-and-drift-enforcement"
        val releaseProfile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val governance = ArchitectureGovernanceAnalyzer(rootDir).analyze()
        val aliasInvariants = StandardSurface.publicContractAliasInvariants()

        require(activeStandardAtLeast(0, 7, 1)) {
            "Architecture debt cleanup and drift enforcement must carry standardVersion 0.7.1 or later."
        }
        require(requiredGate in releaseProfile.requiredConformanceChecks) {
            "Release profile must require the architecture-debt cleanup gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest must publish the architecture-debt cleanup gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate level must require the architecture-debt cleanup gate."
        }
        require(governance.status == "PASS") {
            "Architecture governance must pass: ${governance.issues.joinToString { it.code + ":" + it.path }}"
        }
        require(governance.driftScore.status == "PASS" && governance.driftScore.finalScore >= governance.driftScore.minimumScore) {
            "Drift score must be enforced and non-negative: ${governance.driftScore.finalScore}."
        }
        require(governance.driftScore.scoringMode == "negative-signal-only") {
            "Drift score must not use existing baseline artifacts as a positive floor."
        }
        require(governance.driftScore.positiveSignals.any { it.id == "conformance-coverage" && it.present && it.score == 0 }) {
            "Drift score may report baseline conformance coverage but must not credit it into the score."
        }
        require(governance.driftScore.negativeSignals.none { it.id == "report-without-validation-purpose" && it.present }) {
            "Report budget rule must reject public reports without schema, role or validation gate."
        }
        require(governance.reportBudget.status == "PASS" && governance.reportBudget.publicArtifactsChecked >= 10) {
            "Report budget must actively check the public standard surface."
        }

        val classifiedIds = releaseProfile.gateClassifications.map { it.id }.toSet()
        require(classifiedIds == releaseProfile.requiredConformanceChecks.toSet()) {
            "Every release-profile check must have a gate classification."
        }
        require(releaseProfile.behaviorSafetyNormalizationChecks.contains("v0.7.0.reference-corpus-execution-harness")) {
            "Reference corpus execution harness must count as behavioral coverage."
        }
        require(requiredGate in releaseProfile.governanceChecks) {
            "The v0.7.1 gate must be classified as governance."
        }
        require(releaseProfile.registryConsistencyChecks.isEmpty()) {
            "Registry-consistency evidence must not be promoted into the public release profile implicitly."
        }
        require(governance.reportBudget.registryConsistencyChecks == 0) {
            "The public report budget must count only public release-profile registry-consistency gates."
        }
        require(StandardModel.releaseRegistryConsistencyCheckIds().isEmpty()) {
            "The public release profile must remain free of registry-consistency bookkeeping."
        }
        require(StandardModel.modeledRegistryConsistencyGateCount == 1) {
            "Exactly one package-level registry-consistency owner must remain modeled."
        }
        require(StandardModel.registryConsistencyCheckIds() == listOf("governance.derived-model-integrity")) {
            "The package-level derived-model integrity check must be the sole registry-consistency owner."
        }
        require(StandardModel.checks.single { it.id == "governance.derived-model-integrity" }.scope == StandardCheckScope.ROADMAP_GOVERNANCE) {
            "Registry-consistency evidence must remain outside public release projections."
        }
        require(StandardModel.wellFormednessIssues(rootDir).isEmpty()) {
            "StandardModel must be well formed: ${StandardModel.wellFormednessIssues(rootDir).joinToString()}"
        }

        require(aliasInvariants.size >= 4 && aliasInvariants.all { it.blocking }) {
            "Public compatibility aliases must be documented as blocking invariants."
        }
        val task = TaskNode(id = "deploy", module = "kubernetes", action = "deploy", target = "cluster", dependsOn = listOf("build"))
        val approval = org.flowlang.planner.ApprovalNode(id = "approve", dependsOn = listOf("test"))
        require(task.dependencies == task.dependsOn) { "TaskNode.dependencies must stay synchronized with dependsOn." }
        require(approval.dependencies == approval.dependsOn) { "ApprovalNode.dependencies must stay synchronized with dependsOn." }
        val normalized = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Renew certificate api-tls during the Sunday maintenance window."))
        require(normalized.report.extractedEntities == normalized.report.entities) {
            "NormalizationReport.extractedEntities must stay synchronized with entities."
        }
        require(normalized.report.confidenceByArea["overall"] == normalized.report.confidence.overall) {
            "NormalizationReport.confidenceByArea must stay synchronized with confidence."
        }
        require(!File(rootDir, "src/main/kotlin/org/flowlang/validator/FlowValidator.kt").readText().contains("isPlainString(")) {
            "Dead private FlowValidator.isPlainString helper must not remain in active source."
        }
        val adr = File(rootDir, "docs/adr/ADR-0001-ast-data-orchestration-boundary.md")
        require(adr.isFile && adr.readText().contains("does not support arbitrary general-purpose computation")) {
            "AST data/orchestration boundary must be documented by an ADR before adding more language-like nodes."
        }
    }

    private fun checkV073StandardModelProjectionCoherence(): ConformanceCheck = runCheck("v0.7.3.standard-model-projection-coherence") {
        val requiredGate = "v0.7.3.standard-model-projection-coherence"
        val profile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val surface = StandardSurface.publicSurface()

        require(activeStandardAtLeast(0, 7, 3)) {
            "Standard-model projection coherence must carry standardVersion 0.7.3 or later."
        }
        require(requiredGate in profile.requiredConformanceChecks) {
            "Release profile must require the standard-model projection coherence gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest gates must publish the standard-model projection coherence gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate checks must require the standard-model projection coherence gate."
        }
        require(StandardModel.wellFormednessIssues(rootDir).isEmpty()) {
            "StandardModel must be well formed: ${StandardModel.wellFormednessIssues(rootDir).joinToString()}"
        }
        require(StandardModel.releaseProfileCheckIds() == profile.requiredConformanceChecks) {
            "Release profile must be projected from StandardModel."
        }
        require(StandardModel.standardExportManifestCheckIds() == manifest.releaseGateChecks) {
            "Standard export manifest gates must use explicit StandardModel membership."
        }
        require(StandardModel.candidateCheckIds() == candidate.requiredChecks) {
            "Standard-candidate checks must be projected from StandardModel."
        }
        require(StandardModel.stableArtifacts().toSet() == surface.stableArtifacts.toSet()) {
            "Public surface stable artifacts must be projected from StandardModel."
        }
        require(StandardModel.internalArtifacts() == StandardModel.artifacts
            .filter { it.visibility.name == "INTERNAL" }.map { it.artifact }) {
            "Internal artifact projection must be derived from modeled visibility."
        }
        require(StandardModel.modeledPostClosureCheckIds() == listOf("v0.9.7.10.bounded-semantic-closure")) {
            "The final closure check must be the only modeled check outside the pre-closure suite."
        }
    }
}
