package org.flowlang.conformance

import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.ArtifactEvidenceReport
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityReport
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.ConformanceManifestReport
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.FlowArtifactBundleReport
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.artifacts.StandardComplianceAnalyzer
import org.flowlang.artifacts.StandardComplianceReport
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardContractIndexReport
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.ast.FlowDocument
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.DiagnosticCoverageReport
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.ObservedDiagnosticCode
import org.flowlang.validator.ValidationReport

/**
 * Builds public Core evidence without selecting a projection as the semantic substrate.
 * Target diagnostics are aggregated across the registered inventory only where the
 * public report explicitly describes target compatibility.
 */
internal class TargetNeutralConformanceFixture(
    private val rootDir: File,
    private val registry: ModuleRegistry,
    private val targets: Map<String, org.flowlang.capabilities.TargetCapability>
) {
    private val intentFrontend = IntentYamlFrontend(FrontendCompilerComposition.compiler(registry))

    fun build(): CorePipelineArtifacts {
        val compilation = intentFrontend
            .compile(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
            .requireAccepted()
        return CorePipelineArtifacts(
            intent = compilation.requireIntentEvidence().intent,
            ast = compilation.ast,
            validation = compilation.validation,
            plan = compilation.executionPlan
        )
    }

    fun diagnosticCoverage(core: CorePipelineArtifacts): DiagnosticCoverageReport =
        DiagnosticCoverageAnalyzer().analyze(
            targets.keys.sorted().flatMap { target -> observedDiagnostics(core, target) }
        )

    fun artifactBundle(core: CorePipelineArtifacts): FlowArtifactBundleReport =
        FlowArtifactBundleAnalyzer().intentBundle(
            flowName = core.plan.flowName,
            target = TARGET_NEUTRAL,
            strict = false,
            hasManifest = false,
            renderedArtifact = null
        )

    fun artifactIntegrity(core: CorePipelineArtifacts): ArtifactIntegrityReport =
        artifactBundle(core).let { bundle ->
            ArtifactIntegrityAnalyzer().analyze(
                bundle = bundle,
                presentArtifacts = bundle.pipeline.toSet(),
                standardVersionObservations = bundle.pipeline
                    .filter { it.endsWith(".json") || it == "standard-version.txt" }
                    .map { ArtifactIntegrityVersionObservation(it, FlowStandardVersions.FLOW_STANDARD_VERSION) },
                diagnosticCoverage = diagnosticCoverage(core)
            )
        }

    fun contractIndex(core: CorePipelineArtifacts): StandardContractIndexReport =
        StandardContractIndexAnalyzer().analyze(artifactBundle(core))

    fun evidence(core: CorePipelineArtifacts): ArtifactEvidenceReport =
        ArtifactEvidenceAnalyzer().analyze(artifactBundle(core))

    fun compliance(
        core: CorePipelineArtifacts,
        conformanceManifest: ConformanceManifestReport
    ): StandardComplianceReport = StandardComplianceAnalyzer().analyze(
        bundle = artifactBundle(core),
        contractIndex = contractIndex(core),
        releaseProfile = StandardReleaseProfile.report(),
        evidence = evidence(core),
        integrity = artifactIntegrity(core),
        conformanceManifest = conformanceManifest
    )

    fun passingManifest(): ConformanceManifestReport = manifest(passed = true)

    fun failingManifest(): ConformanceManifestReport = manifest(passed = false)

    fun freeze(core: CorePipelineArtifacts) = PublicStandardDraft.freeze(contractIndex(core))

    fun standardIndex(core: CorePipelineArtifacts) =
        PublicStandardDraft.standardIndex(artifactBundle(core), contractIndex(core))

    fun draft(core: CorePipelineArtifacts, compliance: StandardComplianceReport) =
        PublicStandardDraft.draft(artifactBundle(core), compliance)

    private fun manifest(passed: Boolean): ConformanceManifestReport =
        ConformanceManifestBuilder(rootDir).build(
            summary = ConformanceSummary(
                listOf(ConformanceCheck("reference.target-neutral-compliance-evidence", passed))
            ),
            implementation = "flow-target-neutral-conformance-fixture"
        )

    private fun observedDiagnostics(
        core: CorePipelineArtifacts,
        target: String
    ): List<ObservedDiagnosticCode> {
        val intentValidation = IntentCapabilityValidator(registry).validate(core.intent)
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(core.plan, target)
        val adapterContract = TargetAdapterContractAnalyzer(targets).analyze(core.plan, target)
        return intentValidation.issues.map {
            observed("intent-capability-validation-report.json", it.code, "intentValidation.issues", it.level)
        } + core.validation.issues.map {
            observed("validation-report.json", it.code, "validation.issues", it.level)
        } + (readiness.blockers + readiness.warnings).map {
            observed("execution-readiness-report.json", it.code, "readiness.findings", it.severity.name.lowercase())
        } + adapterContract.invariants.map {
            observed("target-adapter-contract.json", it.code, "adapterContract.invariants", it.severity.name.lowercase())
        } + adapterContract.diagnostics.issues.map {
            observed("adapter-diagnostics.json", it.code, "adapterDiagnostics.issues", it.severity.name.lowercase())
        }
    }

    private fun observed(artifact: String, code: String, source: String, severity: String) =
        ObservedDiagnosticCode(artifact = artifact, code = code, source = source, severity = severity)

    companion object {
        const val TARGET_NEUTRAL = "target-neutral"
    }
}

internal data class CorePipelineArtifacts(
    val intent: IntentDocument,
    val ast: FlowDocument,
    val validation: ValidationReport,
    val plan: ExecutionPlan
)
