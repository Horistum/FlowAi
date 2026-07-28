package org.flowlang.conformance

import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.architecture.ArchitectureDeltaAnalyzer
import org.flowlang.architecture.StandardModelSnapshot
import org.flowlang.modules.ModuleRegistry
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.PurposeCoverageAnalyzer
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class DeltaPurposeChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV074ArchitectureDeltaAnalyzer(),
        checkV075PurposeCoverageRatio()
    )

    private fun checkV074ArchitectureDeltaAnalyzer(): ConformanceCheck = runCheck("v0.7.4.architecture-delta-analyzer") {
        val requiredGate = "v0.7.4.architecture-delta-analyzer"
        val baseline = StandardModelSnapshot.fromYaml(File(rootDir, "standard/architecture/standard-model-baseline-v0.7.3.yaml"))
        val delta = ArchitectureDeltaAnalyzer(baseline).analyze()
        val profile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }

        require(activeStandardAtLeast(0, 7, 4)) {
            "Architecture delta analyzer must carry standardVersion 0.7.4 or later."
        }
        require(requiredGate in profile.requiredConformanceChecks) {
            "Release profile must require the architecture delta analyzer gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest must publish the architecture delta analyzer gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate level must require the architecture delta analyzer gate."
        }
        require(delta.status == "PASS") {
            "Architecture delta must pass: ${delta.issues.joinToString { it.code + ":" + it.subject }}"
        }
        require(delta.previousVersion == "0.7.3") {
            "Architecture delta baseline must be v0.7.3."
        }
        require(delta.currentVersion == FlowStandardVersions.FLOW_STANDARD_VERSION) {
            "Architecture delta current version must match the active standard version."
        }
        require(requiredGate in delta.addedChecks) {
            "Architecture delta history must include the v0.7.4 gate. Added checks: ${delta.addedChecks}."
        }
        require(delta.registryConsistencyCheckGrowth == 0) {
            "Public-standard delta must not reintroduce registry-consistency gates."
        }
        require(delta.stablePublicArtifactGrowth == 0) {
            "v0.7.4 must not grow public stable artifacts; it only introduces delta measurement."
        }
    }

    private fun checkV075PurposeCoverageRatio(): ConformanceCheck = runCheck("v0.7.5.purpose-coverage-ratio") {
        val requiredGate = "v0.7.5.purpose-coverage-ratio"
        val baseline = StandardModelSnapshot.fromYaml(File(rootDir, "standard/architecture/standard-model-baseline-v0.7.4.yaml"))
        val delta = ArchitectureDeltaAnalyzer(baseline).analyze()
        val coverage = PurposeCoverageAnalyzer().analyze()
        val profile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }

        require(activeStandardAtLeast(0, 7, 5)) {
            "Purpose coverage gate must carry standardVersion 0.7.5 or later."
        }
        require(requiredGate in profile.requiredConformanceChecks) {
            "Release profile must require the purpose coverage gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest must publish the purpose coverage gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate level must require the purpose coverage gate."
        }
        require(coverage.status == "PASS") {
            "Purpose coverage must pass: ${coverage.issues.joinToString { it.code + ":" + it.subject }}"
        }
        require(coverage.referenceScenarioCount >= PurposeCoverageAnalyzer.minimumReferenceScenarios) {
            "Reference corpus must not shrink below the v0.7.5 minimum."
        }
        require(coverage.missingCapabilities.isEmpty()) {
            "Reference corpus misses required target-neutral purpose capabilities: ${coverage.missingCapabilities.joinToString()}."
        }
        require(coverage.missingBlockedRiskCapabilities.isEmpty()) {
            "Blocked corpus misses target-neutral risk capabilities: ${coverage.missingBlockedRiskCapabilities.joinToString()}."
        }
        require(coverage.missingPurposeKinds.isEmpty()) {
            "Release profile misses purpose categories: ${coverage.missingPurposeKinds.joinToString()}."
        }
        require(coverage.missingEvidenceBackedPurposeKinds.isEmpty()) {
            "Purpose categories lack evidence anchors: ${coverage.missingEvidenceBackedPurposeKinds.joinToString()}."
        }
        require(delta.status == "PASS") {
            "v0.7.5 architecture delta must pass: ${delta.issues.joinToString { it.code + ":" + it.subject }}"
        }
        require(delta.previousVersion == "0.7.4") {
            "v0.7.5 delta baseline must be v0.7.4."
        }
        require(delta.addedChecks == listOf(requiredGate)) {
            "v0.7.5 must add only the purpose coverage gate to the public standard: ${delta.addedChecks}."
        }
        require(delta.stablePublicArtifactGrowth == 0) {
            "v0.7.5 must not grow public stable artifacts; it adds purpose coverage measurement."
        }
        require(delta.registryConsistencyCheckGrowth == 0) {
            "Purpose coverage must not add registry-consistency checks to the public standard."
        }
    }
}
