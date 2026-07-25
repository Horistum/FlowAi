package org.flowlang.conformance

import java.io.File
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.modules.ModuleRegistry
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class StandardEvidenceChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    private val neutral = TargetNeutralConformanceFixture(rootDir, registry, targets)

    fun checks(): List<ConformanceCheck> = listOf(
        checkV0314DiagnosticCoverageReport(),
        checkV0315ArtifactIntegrityReport(),
        checkV0316StandardContractIndex(),
        checkV0317StandardReleaseProfile(),
        checkV0318ArtifactEvidenceReport(),
        checkV0319StandardComplianceReport(),
        checkV0320StandardFreezeReport(),
        checkV0321CompatibilityPolicy(),
        checkV0322ReferenceCorpus(),
        checkV0323NegativeConformanceCorpus(),
        checkV042TargetConformanceProfile(),
        checkV040PublicStandardDraft()
    )

    private fun checkV0314DiagnosticCoverageReport(): ConformanceCheck = runCheck("v0.3.14.diagnostic-coverage-report") {
        val core = neutral.build()
        val coverage = neutral.diagnosticCoverage(core)
        require(coverage.status == "PASS") { "Target-neutral reference evidence emitted diagnostic codes outside the standard catalog: ${coverage.unknownCodes.joinToString { it.code }}" }
        require(coverage.observedCodes.any { it.code == "ADAPTER_CONTRACT_READY" }) { "Coverage report must observe adapter diagnostics across the registered target inventory." }
        require(coverage.observedCodes.any { it.code == "ADAPTER_MUST_NOT_READ_INTENT" }) { "Coverage report must observe adapter contract invariants." }
        require(coverage.catalogCodes.contains("DIAGNOSTIC_CODE_UNKNOWN")) { "Coverage report must be anchored to the v0.3.14 diagnostic catalog." }
        require(coverage.unusedCatalogCodes.isNotEmpty()) { "Coverage report should expose catalog codes not observed by this reference intent." }
    }

    private fun checkV0315ArtifactIntegrityReport(): ConformanceCheck = runCheck("v0.3.15.artifact-integrity-report") {
        val core = neutral.build()
        val integrity = neutral.artifactIntegrity(core)
        require(integrity.status == "PASS") { "Target-neutral artifact set must pass integrity checks: ${integrity.issues.joinToString { it.code }}" }
        require(integrity.requiredArtifactsExpected.contains("diagnostic-coverage-report.json")) { "Integrity report must include diagnostic coverage as a required artifact." }
        require(integrity.requiredArtifactsExpected.contains("artifact-integrity-report.json")) { "Integrity report must include itself as a public artifact." }
        require(integrity.missingRequiredArtifacts.isEmpty()) { "Reference artifact set must not miss required artifacts." }
        require(integrity.standardVersionMismatches.isEmpty()) { "Reference artifact set must not contain standardVersion mismatches." }
        require(integrity.diagnosticCoverageStatus == "PASS") { "Artifact integrity must consume diagnostic coverage status." }
    }

    private fun checkV0316StandardContractIndex(): ConformanceCheck = runCheck("v0.3.16.standard-contract-index") {
        val index = neutral.contractIndex(neutral.build())
        require(index.target == TargetNeutralConformanceFixture.TARGET_NEUTRAL) {
            "Universal contract evidence must not inherit a concrete target identity."
        }
        require(index.contracts.any { it.artifact == "execution-plan.json" && it.schema == "schemas/execution-plan.schema.json" }) {
            "Contract index must include execution-plan.json and its schema."
        }
        require(index.contracts.any { it.artifact == "standard-compliance-report.json" && it.introducedIn == "0.3.19" }) {
            "Contract index must include standard-compliance-report.json."
        }
        require(index.requiredContracts.contains("standard-contract-index.json")) { "Contract index must list itself as required." }
    }

    private fun checkV0317StandardReleaseProfile(): ConformanceCheck = runCheck("v0.3.17.standard-release-profile") {
        val profile = referenceReleaseProfile()
        require(profile.minimumStandardVersion == "0.4.0") { "Release profile must target the public v0.4.0 draft." }
        require(profile.requiredArtifacts.contains("standard-compliance-report.json")) { "Release profile must require compliance report." }
        require(profile.requiredConformanceChecks.contains("v0.3.19.standard-compliance-report")) { "Release profile must require compliance conformance check." }
    }

    private fun checkV0318ArtifactEvidenceReport(): ConformanceCheck = runCheck("v0.3.18.artifact-evidence-report") {
        val evidence = neutral.evidence(neutral.build())
        require(evidence.target == TargetNeutralConformanceFixture.TARGET_NEUTRAL) {
            "Universal artifact evidence must not inherit a concrete target identity."
        }
        require(evidence.missingEvidence.isEmpty()) { "Required derived artifacts must have evidence: ${evidence.missingEvidence.joinToString()}" }
        require(evidence.evidence.any { it.artifact == "standard-compliance-report.json" && it.producer == "StandardComplianceAnalyzer" }) {
            "Evidence report must identify standard compliance producer."
        }
        require(evidence.evidence.any { it.artifact == "standard-version.txt" && it.evidenceType == "source" }) {
            "Evidence report must identify source artifacts."
        }
    }

    private fun checkV0319StandardComplianceReport(): ConformanceCheck = runCheck("v0.3.19.standard-compliance-report") {
        val core = neutral.build()
        val passing = neutral.compliance(core, neutral.passingManifest())
        require(passing.status == "PASS") { "Reference public artifact set must pass compliance gates: ${passing.failedGates.joinToString()}" }
        require(passing.gates.any { it.id == "artifact-integrity.pass" && it.status == "PASS" }) { "Compliance must include artifact integrity gate." }
        require(passing.gates.any { it.id == "contract-index.present" && it.status == "PASS" }) { "Compliance must include contract index gate." }
        require(passing.gates.any { it.id == "bundle.contains-compliance" && it.status == "PASS" }) { "Compliance must verify bundle membership." }
        require(passing.gates.any { it.id == "conformance.pass" && it.status == "PASS" }) { "Compliance must require explicit passing conformance evidence." }

        val failing = neutral.compliance(core, neutral.failingManifest())
        require(failing.status == "FAIL") {
            "A failing conformance manifest was certified as release compliant."
        }
        require(failing.failedGates.contains("conformance.pass")) {
            "Compliance failure must identify the conformance.pass gate."
        }
        require(failing.gates.single { it.id == "conformance.pass" }.status == "FAIL") {
            "The conformance.pass gate did not preserve negative evidence."
        }
    }

    private fun checkV0320StandardFreezeReport(): ConformanceCheck = runCheck("v0.3.20.standard-freeze-report") {
        val freeze = neutral.freeze(neutral.build())
        require(freeze.status == "PASS") { "Standard freeze must pass without missing schemas: ${freeze.issues.joinToString()}" }
        require(freeze.stableContracts.any { it.artifact == "execution-plan.json" }) { "Freeze report must mark execution plan as stable." }
        require(freeze.breakingChangeRules.contains("add-required-field")) { "Freeze report must expose breaking-change rules." }
    }

    private fun checkV0321CompatibilityPolicy(): ConformanceCheck = runCheck("v0.3.21.compatibility-policy") {
        val policy = PublicStandardDraft.compatibilityPolicy()
        require(policy.rules.any { it.id == "new-required-field" && it.severity == "breaking" }) { "Compatibility policy must classify new required fields as breaking." }
        require(policy.breakingChangeTriggers.contains("drop-required-artifact")) { "Compatibility policy must guard required artifacts." }
    }

    private fun checkV0322ReferenceCorpus(): ConformanceCheck = runCheck("v0.3.22.reference-corpus") {
        val corpus = PublicStandardDraft.referenceCorpus()
        require(corpus.examples.size >= 8) { "Reference corpus must contain core automation examples." }
        require(corpus.examples.any { it.id == "database-migration-with-backup" }) { "Reference corpus must include database migration." }
        require(corpus.examples.all { it.expectedArtifacts.contains("standard-compliance-report.json") }) { "Reference examples must expect compliance output." }
    }

    private fun checkV0323NegativeConformanceCorpus(): ConformanceCheck = runCheck("v0.3.23.negative-conformance-corpus") {
        val corpus = PublicStandardDraft.negativeCorpus()
        require(corpus.cases.size >= 8) { "Negative corpus must contain safety, target, adapter and artifact failures." }
        require(corpus.cases.any { it.expectedDiagnostic == "SAFETY_CLEANUP_REQUIRES_RETENTION" }) { "Negative corpus must include destructive cleanup without retention." }
        require(corpus.cases.any { it.expectedDiagnostic == "ADAPTER_MUST_NOT_READ_INTENT" }) { "Negative corpus must include adapter boundary violation." }
    }

    private fun checkV042TargetConformanceProfile(): ConformanceCheck = runCheck("v0.4.2.target-conformance-profile") {
        val profile = PublicStandardDraft.targetConformanceProfile()
        require(profile.levels.any { it.level == "compliance-ready" }) { "Target conformance profile must include compliance-ready level." }
        require(profile.levels.any { it.level == "plan-reader" }) { "Target conformance profile must include plan-reader level." }
        require(profile.forbiddenInputs.contains("normalized-intent.json")) { "Target conformance profile must forbid intent reinterpretation." }
        require(profile.requiredDiagnostics.contains("ADAPTER_CONTRACT_BLOCKED")) { "Target conformance profile must require blocked diagnostics." }
    }

    private fun checkV040PublicStandardDraft(): ConformanceCheck = runCheck("v0.4.0.public-standard-draft") {
        val core = neutral.build()
        val passingCompliance = neutral.compliance(core, neutral.passingManifest())
        val draft = neutral.draft(core, passingCompliance)
        val index = neutral.standardIndex(core)
        val suite = PublicStandardDraft.conformanceSuite(neutral.passingManifest())
        require(draft.status == "PASS") { "Public standard draft must pass valid compliance." }
        require(draft.purpose.contains("Human/AI intent")) { "Draft must preserve Flow's original intent standardization purpose." }
        require(index.artifacts.contains("flow-standard-draft.json")) { "Standard index must include draft artifact." }
        require(suite.referenceCorpus == "reference-corpus-index.json") { "Conformance suite must point to reference corpus." }
        require(suite.negativeCorpus == "negative-conformance-corpus.json") { "Conformance suite must point to negative corpus." }

        val failingCompliance = neutral.compliance(core, neutral.failingManifest())
        val rejectedDraft = neutral.draft(core, failingCompliance)
        require(rejectedDraft.status == "FAIL") {
            "Public standard draft ignored failing compliance evidence."
        }
    }
}
