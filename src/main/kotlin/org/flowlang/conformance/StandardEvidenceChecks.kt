package org.flowlang.conformance

import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.FlowArtifactEntry
import org.flowlang.artifacts.FlowArtifactRole
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import java.io.File

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
        require(coverage.observedCodes.any { it.code == "ADAPTER_CONTRACT_READY" })
        require(coverage.observedCodes.any { it.code == "ADAPTER_MUST_NOT_READ_INTENT" })
        require(coverage.catalogCodes.contains("DIAGNOSTIC_CODE_UNKNOWN"))
        require(coverage.unusedCatalogCodes.isNotEmpty())
    }

    private fun checkV0315ArtifactIntegrityReport(): ConformanceCheck = runCheck("v0.3.15.artifact-integrity-report") {
        val core = neutral.build()
        val integrity = neutral.artifactIntegrity(core)
        require(integrity.status == "PASS") { "Target-neutral artifact set must pass integrity checks: ${integrity.issues.joinToString { it.code }}" }
        require(integrity.requiredArtifactsExpected.contains("diagnostic-coverage-report.json"))
        require(integrity.requiredArtifactsExpected.contains("artifact-integrity-report.json"))
        require(integrity.missingRequiredArtifacts.isEmpty())
        require(integrity.standardVersionMismatches.isEmpty())
        require(integrity.diagnosticCoverageStatus == "PASS")
    }

    private fun checkV0316StandardContractIndex(): ConformanceCheck = runCheck("v0.3.16.standard-contract-index") {
        val core = neutral.build()
        val bundle = neutral.artifactBundle(core)
        val index = StandardContractIndexAnalyzer().analyze(bundle)
        require(index.target == TargetNeutralConformanceFixture.TARGET_NEUTRAL) {
            "Universal contract evidence must not inherit a concrete target identity."
        }
        require(index.contracts.any { it.artifact == "execution-plan.json" && it.schema == "schemas/execution-plan.schema.json" })
        require(index.contracts.any { it.artifact == "standard-compliance-report.json" && it.introducedIn == "0.3.19" })
        require(index.requiredContracts.contains("standard-contract-index.json"))

        val unknown = bundle.copy(
            artifacts = bundle.artifacts + FlowArtifactEntry(
                name = "invented-public-report.json",
                role = FlowArtifactRole.REPORT,
                schema = "schemas/invented-public-report.schema.json",
                required = true,
                derived = true,
                pipelineIndex = bundle.artifacts.size + 1,
                derivedFrom = listOf("normalized-intent.json")
            ),
            requiredArtifacts = bundle.requiredArtifacts + "invented-public-report.json",
            pipeline = bundle.pipeline + "invented-public-report.json"
        )
        require(runCatching { StandardContractIndexAnalyzer().analyze(unknown) }.isFailure) {
            "An unknown artifact must not receive fictional pre-0.3.10 introduction metadata."
        }
    }

    private fun checkV0317StandardReleaseProfile(): ConformanceCheck = runCheck("v0.3.17.standard-release-profile") {
        val profile = referenceReleaseProfile()
        require(profile.minimumStandardVersion == "0.4.0")
        require(profile.requiredArtifacts.contains("standard-compliance-report.json"))
        require(profile.requiredConformanceChecks.contains("v0.3.19.standard-compliance-report"))
    }

    private fun checkV0318ArtifactEvidenceReport(): ConformanceCheck = runCheck("v0.3.18.artifact-evidence-report") {
        val core = neutral.build()
        val bundle = neutral.artifactBundle(core)
        val evidence = ArtifactEvidenceAnalyzer().analyze(bundle)
        require(evidence.target == TargetNeutralConformanceFixture.TARGET_NEUTRAL) {
            "Universal artifact evidence must not inherit a concrete target identity."
        }
        require(evidence.missingEvidence.isEmpty()) {
            "Required derived artifacts must cite existing or registered evidence: ${evidence.missingEvidence.joinToString()}"
        }
        require(evidence.evidence.any {
            it.artifact == "standard-compliance-report.json" && it.producer == "flow.standard.compliance"
        }) { "Evidence report must use the stable standard compliance producer contract." }
        require(evidence.evidence.any { it.artifact == "standard-version.txt" && it.evidenceType == "source" })

        val dangling = bundle.copy(
            artifacts = bundle.artifacts.map { artifact ->
                if (artifact.name == "standard-compliance-report.json") {
                    artifact.copy(derivedFrom = listOf("does-not-exist.json"))
                } else {
                    artifact
                }
            }
        )
        val danglingEvidence = ArtifactEvidenceAnalyzer().analyze(dangling)
        require("standard-compliance-report.json" in danglingEvidence.missingEvidence) {
            "A non-empty but dangling derivedFrom reference must block evidence completeness."
        }

        val unknown = bundle.copy(
            artifacts = bundle.artifacts + FlowArtifactEntry(
                name = "invented-public-report.json",
                role = FlowArtifactRole.REPORT,
                schema = "schemas/invented-public-report.schema.json",
                required = true,
                derived = true,
                pipelineIndex = bundle.artifacts.size + 1,
                derivedFrom = listOf("normalized-intent.json")
            ),
            requiredArtifacts = bundle.requiredArtifacts + "invented-public-report.json",
            pipeline = bundle.pipeline + "invented-public-report.json"
        )
        require(runCatching { ArtifactEvidenceAnalyzer().analyze(unknown) }.isFailure) {
            "An unknown public artifact must not receive a generic producer provenance."
        }
    }

    private fun checkV0319StandardComplianceReport(): ConformanceCheck = runCheck("v0.3.19.standard-compliance-report") {
        val core = neutral.build()
        val passing = neutral.compliance(core, neutral.passingManifest())
        require(passing.status == "PASS") { "Reference public artifact set must pass compliance gates: ${passing.failedGates.joinToString()}" }
        require(passing.gates.any { it.id == "artifact-integrity.pass" && it.status == "PASS" })
        require(passing.gates.any { it.id == "contract-index.present" && it.status == "PASS" })
        require(passing.gates.any { it.id == "bundle.contains-compliance" && it.status == "PASS" })
        require(passing.gates.any { it.id == "conformance.pass" && it.status == "PASS" })

        val failing = neutral.compliance(core, neutral.failingManifest())
        require(failing.status == "FAIL") {
            "A failing conformance manifest was certified as release compliant."
        }
        require(failing.failedGates.contains("conformance.pass"))
        require(failing.gates.single { it.id == "conformance.pass" }.status == "FAIL")

        val bundle = neutral.artifactBundle(core)
        val dangling = bundle.copy(
            artifacts = bundle.artifacts.map { artifact ->
                if (artifact.name == "standard-compliance-report.json") {
                    artifact.copy(derivedFrom = listOf("missing-lineage.json"))
                } else {
                    artifact
                }
            }
        )
        val danglingEvidence = ArtifactEvidenceAnalyzer().analyze(dangling)
        val rejected = org.flowlang.artifacts.StandardComplianceAnalyzer().analyze(
            bundle = dangling,
            contractIndex = StandardContractIndexAnalyzer().analyze(dangling),
            releaseProfile = referenceReleaseProfile(),
            evidence = danglingEvidence,
            integrity = neutral.artifactIntegrity(core),
            conformanceManifest = neutral.passingManifest()
        )
        require(rejected.status == "FAIL" && "evidence.complete" in rejected.failedGates) {
            "Dangling artifact lineage must block public compliance."
        }
    }

    private fun checkV0320StandardFreezeReport(): ConformanceCheck = runCheck("v0.3.20.standard-freeze-report") {
        val freeze = neutral.freeze(neutral.build())
        require(freeze.status == "PASS") { "Standard freeze must pass without missing schemas: ${freeze.issues.joinToString()}" }
        require(freeze.stableContracts.any { it.artifact == "execution-plan.json" })
        require(freeze.breakingChangeRules.contains("add-required-field"))
    }

    private fun checkV0321CompatibilityPolicy(): ConformanceCheck = runCheck("v0.3.21.compatibility-policy") {
        val policy = PublicStandardDraft.compatibilityPolicy()
        require(policy.rules.any { it.id == "new-required-field" && it.severity == "breaking" })
        require(policy.breakingChangeTriggers.contains("drop-required-artifact"))
    }

    private fun checkV0322ReferenceCorpus(): ConformanceCheck = runCheck("v0.3.22.reference-corpus") {
        val corpus = PublicStandardDraft.referenceCorpus()
        require(corpus.examples.size >= 8)
        require(corpus.examples.any { it.id == "database-migration-with-backup" })
        require(corpus.examples.all { it.expectedArtifacts.contains("standard-compliance-report.json") })
    }

    private fun checkV0323NegativeConformanceCorpus(): ConformanceCheck = runCheck("v0.3.23.negative-conformance-corpus") {
        val corpus = PublicStandardDraft.negativeCorpus()
        require(corpus.cases.size >= 8)
        require(corpus.cases.any { it.expectedDiagnostic == "SAFETY_CLEANUP_REQUIRES_RETENTION" })
        require(corpus.cases.any { it.expectedDiagnostic == "ADAPTER_MUST_NOT_READ_INTENT" })
    }

    private fun checkV042TargetConformanceProfile(): ConformanceCheck = runCheck("v0.4.2.target-conformance-profile") {
        val profile = PublicStandardDraft.targetConformanceProfile()
        require(profile.levels.any { it.level == "compliance-ready" })
        require(profile.levels.any { it.level == "plan-reader" })
        require(profile.forbiddenInputs.contains("normalized-intent.json"))
        require(profile.requiredDiagnostics.contains("ADAPTER_CONTRACT_BLOCKED"))
    }

    private fun checkV040PublicStandardDraft(): ConformanceCheck = runCheck("v0.4.0.public-standard-draft") {
        val core = neutral.build()
        val passingCompliance = neutral.compliance(core, neutral.passingManifest())
        val draft = neutral.draft(core, passingCompliance)
        val index = neutral.standardIndex(core)
        val suite = PublicStandardDraft.conformanceSuite(neutral.passingManifest())
        require(draft.status == "PASS")
        require(draft.purpose.contains("Human/AI intent"))
        require(index.artifacts.contains("flow-standard-draft.json"))
        require(suite.referenceCorpus == "reference-corpus-index.json")
        require(suite.negativeCorpus == "negative-conformance-corpus.json")

        val failingCompliance = neutral.compliance(core, neutral.failingManifest())
        val rejectedDraft = neutral.draft(core, failingCompliance)
        require(rejectedDraft.status == "FAIL") {
            "Public standard draft ignored failing compliance evidence."
        }
    }
}
