package org.flowlang.conformance

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.capabilities.TargetDecisionKind
import org.flowlang.capabilities.DecisionTraceStatus
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.standard.StandardDiagnosticCatalog
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class DecisionAndArtifactChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    private val neutral = TargetNeutralConformanceFixture(rootDir, registry, targets)

    fun checks(): List<ConformanceCheck> = listOf(
        checkV039TargetDecisionTraceReport(),
        checkV0310PublicArtifactBundle(),
        checkV0311ConformanceManifest(),
        checkV0312TargetAdapterContract(),
        checkV0313StandardDiagnosticCatalog()
    )

    private fun checkV039TargetDecisionTraceReport(): ConformanceCheck = runCheck("v0.3.9.target-decision-trace") {
        val core = neutral.build()
        val compatibility = CompatibilityAnalyzer(targets)
        val manifests = targets.keys.sorted().map { target ->
            manifestPipeline.generateDiagnosticEvidence(core.plan, target)
        }
        val negotiation = TargetCompatibilityReadinessAnalyzer.reconcile(
            compatibility.negotiate(core.plan, strict = false),
            manifests
        )
        val selection = TargetCompatibilityReadinessAnalyzer.reconcile(
            TargetSelectionAnalyzer(targets).analyze(core.plan, strict = false),
            manifests
        )
        val report = TargetDecisionTraceAnalyzer(targets).analyze(
            plan = core.plan,
            requestedTarget = "jenkins",
            strict = false,
            negotiation = negotiation,
            selection = selection
        )
        require(report.finalDecision == DecisionTraceStatus.BLOCKED) { "Reference trace must block because no target artifact is executable." }
        require(!report.generationAllowed) { "Blocked trace must not allow generation." }
        require(report.recommendedTarget.isEmpty()) { "Trace must not preserve a capability-only target recommendation." }
        require(report.trace.map { it.id }.containsAll(listOf("execution-plan", "capability-negotiation", "execution-readiness", "target-selection"))) {
            "Trace must cover the standard decision pipeline."
        }
        require(report.publicArtifacts.contains("target-selection-report.json")) { "Trace must cite target-selection-report.json." }
        require(report.publicArtifacts.contains("target-decision-trace-report.json")) { "Trace must cite itself as a public artifact." }
        require(report.targetExplanations.any { it.target == "jenkins" && it.decision == TargetDecisionKind.DEGRADED }) {
            "Trace must explain Jenkins as review-only degraded."
        }
        require(report.targetExplanations.any { it.target == "github-actions" && it.decision == TargetDecisionKind.BLOCKED }) {
            "Trace must explain GitHub Actions as blocked without workspace continuity evidence."
        }
        require(report.targetExplanations.any { it.target == "tekton" && it.decision == TargetDecisionKind.BLOCKED }) {
            "Trace must explain Tekton as blocked."
        }
    }

    private fun checkV0310PublicArtifactBundle(): ConformanceCheck = runCheck("v0.3.10.public-artifact-bundle") {
        val core = neutral.build()
        val neutralBundle = neutral.artifactBundle(core)
        require(neutralBundle.target == TargetNeutralConformanceFixture.TARGET_NEUTRAL) {
            "Universal bundle evidence must remain target-neutral."
        }
        require(neutralBundle.optionalArtifacts.none { it == "target-manifest.json" || it == "Jenkinsfile" }) {
            "Target-neutral bundle must not invent target artifacts."
        }

        val targetExtension = FlowArtifactBundleAnalyzer().intentBundle(
            flowName = core.plan.flowName,
            target = "jenkins",
            strict = false,
            hasManifest = true,
            renderedArtifact = "Jenkinsfile"
        )
        require(targetExtension.artifacts.isNotEmpty()) { "Artifact bundle must list public artifacts." }
        require(targetExtension.pipeline.first() == "standard-version.txt") { "Bundle pipeline must start with standard-version.txt." }
        require(targetExtension.pipeline.last() == "flow-artifact-bundle.json") { "Bundle pipeline must end with flow-artifact-bundle.json." }
        require(targetExtension.requiredArtifacts.contains("execution-plan.json")) { "Bundle must require execution-plan.json." }
        require(targetExtension.requiredArtifacts.contains("target-decision-trace-report.json")) { "Bundle must require target-decision-trace-report.json." }
        require(targetExtension.requiredArtifacts.contains("flow-artifact-bundle.json")) { "Bundle must require itself as the public bundle manifest." }
        require(targetExtension.optionalArtifacts.contains("target-manifest.json")) { "Target manifest must be listed as optional because not every target has a renderer." }
        require(targetExtension.optionalArtifacts.contains("Jenkinsfile")) { "Rendered vendor artifact must be listed as optional." }
        require(targetExtension.artifacts.map { it.pipelineIndex } == (1..targetExtension.artifacts.size).toList()) { "Artifact pipeline indexes must be contiguous." }
    }

    private fun checkV0311ConformanceManifest(): ConformanceCheck = runCheck("v0.3.11.conformance-manifest") {
        val seed = ConformanceSummary(listOf(
            ConformanceCheck("intent.valid.build-test-deploy", true),
            ConformanceCheck("target.strict.tekton-approval-unsupported", true),
            ConformanceCheck("schemas.public-outputs", true),
            ConformanceCheck("v0.3.10.public-artifact-bundle", true)
        ))
        val manifest = ConformanceManifestBuilder(rootDir).build(seed)
        require(manifest.status == "PASS") { "Conformance manifest must report PASS when all checks passed." }
        require(manifest.totalChecks == seed.checks.size) { "Conformance manifest must include total check count." }
        require(manifest.areas.any { it.area == "intent" && it.passed == 1 }) { "Manifest must summarize intent area." }
        require(manifest.areas.any { it.area == "target" && it.passed == 1 }) { "Manifest must summarize target area." }
        require(manifest.requiredChecks.contains("schemas.public-outputs")) { "Manifest must list required checks." }
        require(manifest.vectors.any { it.path == "conformance/artifacts/public-artifact-bundle.conformance.yaml" }) { "Manifest must list artifact bundle vector." }
        require(manifest.publicSchemas.any { it.artifact == "flow-artifact-bundle.json" && it.schema == "schemas/flow-artifact-bundle.schema.json" }) { "Manifest must list artifact bundle schema." }
        require(manifest.publicSchemas.any { it.artifact == "conformance-manifest.json" && it.schema == "schemas/conformance-manifest.schema.json" }) { "Manifest must list its own schema." }
        require(manifest.requiredArtifacts.contains("conformance-manifest.json")) { "Manifest must list conformance-manifest.json as required artifact." }
    }

    private fun checkV0312TargetAdapterContract(): ConformanceCheck = runCheck("v0.3.12.target-adapter-contract") {
        val core = neutral.build()
        val contract = TargetAdapterContractAnalyzer(targets).analyze(core.plan, "jenkins", strict = false)
        require(contract.generationAllowed) { "Jenkins adapter contract should allow generation for the reference plan." }
        require(contract.allowedInputArtifacts.any { it.name == "execution-plan.json" }) { "Adapter contract must allow execution-plan.json." }
        require(contract.allowedInputArtifacts.none { it.name == "normalized-intent.json" }) { "Adapter contract must not allow normalized-intent.json as adapter input." }
        require(contract.forbiddenInputArtifacts.contains("normalized-intent.json")) { "Adapter contract must explicitly forbid intent reinterpretation." }
        require(contract.expectedOutputArtifacts.any { it.name == "target-manifest.json" }) { "Adapter contract must describe target manifest output." }
        require(contract.expectedOutputArtifacts.any { it.name == "adapter-diagnostics.json" }) { "Adapter contract must describe diagnostics output." }
        require(contract.invariants.any { it.code == "ADAPTER_MUST_NOT_READ_INTENT" }) { "Adapter contract must include no-intent invariant." }
        require(contract.invariants.any { it.code == "ADAPTER_MUST_RESPECT_READINESS" }) { "Adapter contract must include readiness invariant." }

        val tekton = TargetAdapterContractAnalyzer(targets).analyze(core.plan, "tekton", strict = false)
        require(!tekton.generationAllowed) { "Tekton adapter contract should block generation for unsupported manual approval." }
        require(tekton.diagnostics.issues.any { it.code == "ADAPTER_CONTRACT_BLOCKED" }) { "Blocked adapter contract must emit blocking diagnostics." }
    }

    private fun checkV0313StandardDiagnosticCatalog(): ConformanceCheck = runCheck("v0.3.13.standard-diagnostic-catalog") {
        val report = StandardDiagnosticCatalog.report()
        val codes = report.codes.map { it.code }
        require(codes.size == codes.distinct().size) { "Diagnostic catalog codes must be unique." }
        require(codes.contains("MISSING_SYSTEM_CONFIG")) { "Catalog must include existing intent diagnostic MISSING_SYSTEM_CONFIG." }
        require(codes.contains("SAFETY_REQUIRES_DRY_RUN")) { "Catalog must include existing safety diagnostic SAFETY_REQUIRES_DRY_RUN." }
        require(codes.contains("TARGET_UNSUPPORTED_CAPABILITY")) { "Catalog must include target compatibility diagnostic TARGET_UNSUPPORTED_CAPABILITY." }
        require(codes.contains("ADAPTER_CONTRACT_BLOCKED")) { "Catalog must include adapter diagnostic ADAPTER_CONTRACT_BLOCKED." }
        require(codes.contains("CONFORMANCE_CHECK_FAILED")) { "Catalog must include conformance diagnostic CONFORMANCE_CHECK_FAILED." }
        require(report.codes.all { it.usedBy.isNotEmpty() }) { "Every diagnostic code must cite at least one public artifact." }
        require(report.codes.all { it.stability == "stable" }) { "v0.3.13 catalog should only publish stable codes." }
    }
}
