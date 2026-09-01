package org.flowlang.conformance

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.StandardComplianceAnalyzer
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.adapters.rendering.AdapterArtifactRenderingAuthority
import org.flowlang.cli.Json
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.materialization.ExplicitTargetSelection
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.CanonicalPlanNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.ObservedDiagnosticCode
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

/**
 * Shared, target-neutral context for mechanically separated conformance groups.
 *
 * Concrete target projections are supplied by the composition root. The support
 * layer never enumerates a platform id or constructs a built-in renderer.
 */
internal abstract class ConformanceCheckSupport(
    protected val rootDir: File,
    protected val registry: ModuleRegistry,
    protected val targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    protected val projections: TargetProjectionRegistry
) {
    protected val manifestPipeline = TargetManifestGenerationPipeline(targets, projections)
    protected val artifactRendering = AdapterArtifactRenderingAuthority(rootDir, projections)
    protected val intentFrontend = IntentYamlFrontend(FlowCompilationService(registry))

    protected fun buildPipeline(target: String, strict: Boolean = false): PipelineArtifacts {
        val compilation = intentFrontend
            .compile(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
            .requireAccepted()
        val intent = compilation.requireIntentEvidence().intent
        val plan = compilation.executionPlan
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target, strict = strict)
        val manifest = manifestPipeline.generate(materializationRequest(plan, target, strict, "conformance:build-pipeline"))
        val rendering = artifactRendering.render(manifest)
        return PipelineArtifacts(
            intent = intent,
            ast = compilation.ast,
            validation = compilation.validation,
            plan = plan,
            compatibility = compatibility,
            manifest = manifest,
            renderedArtifactName = rendering.artifact.fileName,
            rendered = rendering.artifact.content
        )
    }

    protected fun explicitTarget(
        target: String,
        source: String
    ): ExplicitTargetSelection = TargetSelectionAuthority.fromConformanceCheck(target, source, targets)

    protected fun materializationRequest(
        plan: ExecutionPlan,
        target: String,
        strict: Boolean = false,
        source: String
    ): TargetMaterializationRequest = TargetMaterializationRequest.fromCompatibilityPlan(
        plan = plan,
        selection = explicitTarget(target, source),
        strict = strict,
        evidenceId = source
    )

    protected fun diagnosticMaterializationRequest(
        plan: ExecutionPlan,
        target: String,
        source: String
    ): TargetDiagnosticMaterializationRequest = TargetDiagnosticMaterializationRequest.fromCompatibilityPlan(
        plan = plan,
        selection = explicitTarget(target, source),
        evidenceId = source
    )

    protected fun referenceDiagnosticCoverage(artifacts: PipelineArtifacts, target: String) =
        DiagnosticCoverageAnalyzer().analyze(referenceObservedDiagnostics(artifacts, target))

    protected fun referenceArtifactBundle(artifacts: PipelineArtifacts, target: String) =
        FlowArtifactBundleAnalyzer().intentBundle(
            flowName = artifacts.plan.flowName,
            target = target,
            strict = false,
            hasManifest = true,
            renderedArtifact = artifacts.renderedArtifactName
        )

    protected fun referenceArtifactIntegrity(artifacts: PipelineArtifacts, target: String) =
        referenceArtifactBundle(artifacts, target).let { bundle ->
            ArtifactIntegrityAnalyzer().analyze(
                bundle = bundle,
                presentArtifacts = bundle.pipeline.toSet(),
                standardVersionObservations = bundle.pipeline
                    .filter { it.endsWith(".json") || it == "standard-version.txt" }
                    .map { ArtifactIntegrityVersionObservation(it, FlowStandardVersions.FLOW_STANDARD_VERSION) },
                diagnosticCoverage = referenceDiagnosticCoverage(artifacts, target)
            )
        }

    protected fun referenceContractIndex(artifacts: PipelineArtifacts, target: String) =
        StandardContractIndexAnalyzer().analyze(referenceArtifactBundle(artifacts, target))

    protected fun referenceReleaseProfile() = StandardReleaseProfile.report()

    protected fun referenceEvidence(artifacts: PipelineArtifacts, target: String) =
        ArtifactEvidenceAnalyzer().analyze(referenceArtifactBundle(artifacts, target))

    protected fun referenceCompliance(artifacts: PipelineArtifacts, target: String) =
        StandardComplianceAnalyzer().analyze(
            bundle = referenceArtifactBundle(artifacts, target),
            contractIndex = referenceContractIndex(artifacts, target),
            releaseProfile = referenceReleaseProfile(),
            evidence = referenceEvidence(artifacts, target),
            integrity = referenceArtifactIntegrity(artifacts, target),
            conformanceManifest = referenceComplianceManifestFixture()
        )

    protected fun referenceComplianceManifestFixture() = ConformanceManifestBuilder(rootDir).build(
        summary = ConformanceSummary(listOf(ConformanceCheck("reference.artifact-compliance-fixture", true))),
        implementation = "flow-reference-compliance-fixture"
    )

    protected fun referenceFreeze(artifacts: PipelineArtifacts, target: String) =
        PublicStandardDraft.freeze(referenceContractIndex(artifacts, target))

    protected fun referenceStandardIndex(artifacts: PipelineArtifacts, target: String) =
        PublicStandardDraft.standardIndex(referenceArtifactBundle(artifacts, target), referenceContractIndex(artifacts, target))

    protected fun referenceConformanceSuite() = PublicStandardDraft.conformanceSuite()

    protected fun referenceDraft(artifacts: PipelineArtifacts, target: String) =
        PublicStandardDraft.draft(referenceArtifactBundle(artifacts, target), referenceCompliance(artifacts, target))

    protected fun referenceObservedDiagnostics(artifacts: PipelineArtifacts, target: String): List<ObservedDiagnosticCode> {
        val intent = artifacts.intent as org.flowlang.intent.IntentDocument
        val validation = artifacts.validation as org.flowlang.validator.ValidationReport
        val intentValidation = IntentCapabilityValidator(registry).validate(intent)
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(artifacts.plan, target)
        val adapterContract = TargetAdapterContractAnalyzer(targets).analyze(artifacts.plan, target)
        return intentValidation.issues.map { observed("intent-capability-validation-report.json", it.code, "intentValidation.issues", it.level) } +
            validation.issues.map { observed("validation-report.json", it.code, "validation.issues", it.level) } +
            (readiness.blockers + readiness.warnings).map { observed("execution-readiness-report.json", it.code, "readiness.findings", it.severity.name.lowercase()) } +
            adapterContract.invariants.map { observed("target-adapter-contract.json", it.code, "adapterContract.invariants", it.severity.name.lowercase()) } +
            adapterContract.diagnostics.issues.map { observed("adapter-diagnostics.json", it.code, "adapterDiagnostics.issues", it.severity.name.lowercase()) }
    }

    protected fun observed(artifact: String, code: String, source: String, severity: String): ObservedDiagnosticCode =
        ObservedDiagnosticCode(artifact = artifact, code = code, source = source, severity = severity)

    protected fun flattenCanonicalNode(node: CanonicalPlanNode): List<CanonicalPlanNode> =
        listOf(node) +
            node.then.flatMap { flattenCanonicalNode(it) } +
            node.otherwise.flatMap { flattenCanonicalNode(it) } +
            node.body.flatMap { flattenCanonicalNode(it) } +
            node.errorHandler.flatMap { flattenCanonicalNode(it) } +
            node.errorCase.flatMap { flattenCanonicalNode(it) } +
            node.defaultSteps.flatMap { flattenCanonicalNode(it) } +
            node.branches.flatMap { branch -> branch.steps.flatMap { flattenCanonicalNode(it) } } +
            node.cases.flatMap { matchCase -> matchCase.steps.flatMap { flattenCanonicalNode(it) } }

    protected fun pretty(value: Any): String = Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"

    protected fun assertJsonSnapshotEquals(file: File, actual: Any) {
        require(file.isFile) { "Missing snapshot ${file.path}" }
        val expected = Json.mapper.readTree(file.readText())
        val got = Json.mapper.readTree(pretty(actual))
        require(expected == got) { "JSON snapshot mismatch for ${file.name}. Update snapshot only after reviewing generated semantics." }
    }

    protected fun assertSnapshotEquals(file: File, actual: String) {
        require(file.isFile) { "Missing snapshot ${file.path}" }
        val expected = file.readText().trimEnd()
        val got = actual.trimEnd()
        require(expected == got) {
            "Snapshot mismatch for ${file.name}. Update snapshot only after reviewing generated semantics. " +
                "Actual output follows:\n--- ACTUAL ${file.name} ---\n$got\n--- END ACTUAL ${file.name} ---"
        }
    }

    protected fun TargetManifest.allStepParams(): List<String> = jobs.flatMap { job ->
        job.steps.flatMap { it.allStepParams() }
    }

    protected fun TargetStep.allStepParams(): List<String> = params.values.toList() + children.flatMap { it.allStepParams() }

    protected fun standardBundleFixture(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "flow-standard-bundle-${System.nanoTime()}")
        dir.mkdirs()

        val manifest = StandardSurface.standardExportManifest()
        val export = StandardSurface.standardExportBundle()
        val surface = StandardSurface.publicSurface()
        val releaseChecks = StandardReleaseProfile.report().requiredConformanceChecks

        fun write(path: String, text: String = "{}\n") {
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }

        manifest.requiredDirectories.forEach { File(dir, it.trimEnd('/')).mkdirs() }
        write("standard-version.txt", FlowStandardVersions.FLOW_STANDARD_VERSION + "\n")
        manifest.requiredDocuments.forEach { write(it, "Reference document for $it\n") }
        manifest.requiredSchemas.forEach { write(it, "{}\n") }
        manifest.requiredJsonArtifacts.forEach { write(it, "{}\n") }
        manifest.evidenceArtifacts.forEach { write(it, "{}\n") }
        export.requiredFiles
            .filterNot { it == "standard-version.txt" }
            .forEach { write(it, "{}\n") }
        export.requiredDirectories.forEach { File(dir, it.trimEnd('/')).mkdirs() }
        write("standard-export-bundle.json", surface.stableArtifacts.joinToString(prefix = "{ \"requiredArtifacts\": [\"", separator = "\", \"", postfix = "\"] }\n"))
        write("conformance-manifest.json", releaseChecks.joinToString(prefix = "{ \"requiredChecks\": [\"", separator = "\", \"", postfix = "\"] }\n"))
        write("standard-release-profile.json", releaseChecks.joinToString(prefix = "{ \"requiredConformanceChecks\": [\"", separator = "\", \"", postfix = "\"] }\n"))
        return dir
    }

    protected fun activeStandardAtLeast(requiredMajor: Int, requiredMinor: Int): Boolean {
        val parts = FlowStandardVersions.FLOW_STANDARD_VERSION.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor)
    }

    protected fun activeStandardAtLeast(requiredMajor: Int, requiredMinor: Int, requiredPatch: Int): Boolean =
        versionAtLeast(FlowStandardVersions.FLOW_STANDARD_VERSION, requiredMajor, requiredMinor, requiredPatch)

    protected fun versionAtLeast(value: String, requiredMajor: Int, requiredMinor: Int): Boolean {
        val parts = value.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor)
    }

    protected fun versionAtLeast(value: String, requiredMajor: Int, requiredMinor: Int, requiredPatch: Int): Boolean {
        val parts = value.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major > requiredMajor ||
            (major == requiredMajor && minor > requiredMinor) ||
            (major == requiredMajor && minor == requiredMinor && patch >= requiredPatch)
    }

    protected fun postVectorIndexChecks(): List<String> = listOf(
        "v0.6.1.intent-corpus-expansion",
        "v0.6.2.required-clarification-contract",
        "v0.6.3.safety-policy-matrix",
        "v0.6.4.target-semantics-negative-corpus",
        "v0.6.5.execution-plan-semantic-invariants",
        "v0.6.6.ai-input-trust-boundary",
        "v0.6.7.standard-example-bundle",
        "v0.6.8.compatibility-promise",
        "v0.7.0.reference-corpus-execution-harness",
        "v0.7.1.architecture-debt-cleanup-and-drift-enforcement",
        "v0.7.3.standard-model-projection-coherence",
        "v0.7.4.architecture-delta-analyzer",
        "v0.7.5.purpose-coverage-ratio",
        "cli.release.diagnostic-honesty",
        ConformanceQualityGateNames.CORE_CONTRACT_CHECK,
        ConformanceQualityGateNames.SCENARIO_PACK_QUALITY
    )

    protected fun allRunnerChecksForVectorIndex(): List<String> =
        listOf(
            "intent.valid.build-test-deploy",
            "intent.invalid.argocd-missing-config",
            "target.strict.tekton-approval-unsupported",
            "generator.manifest.jenkins",
            "generator.manifest.github-actions",
            "generator.manifest.tekton.partial",
            "intent.yaml.flow-style",
            "snapshots.e2e.files-exist",
            "snapshots.e2e.content",
            "snapshots.rendered.standard-version",
            "standard.catalog",
            "standard.capability-contracts",
            "intent.design-report",
            "core.boundary.no-jackson",
            "modules.boundary.no-target-rendering",
            "ai.normalization.deployment",
            "ai.normalization.full-pipeline",
            "ai.normalization.missing-application-question",
            "schemas.public-outputs",
            "scenario-packs.catalog",
            "scenario-packs.normalization.full-pipelines",
            "scenario-packs.regression-coverage",
            "v0.3.1.scenario-packs",
            "v0.3.1.capability-negotiation",
            "v0.3.2.execution-plan.canonical",
            "v0.3.2.safety-policy-validation",
            "v0.3.4.capability-module-contracts",
            "v0.3.5.intent-decision-model",
            "v0.3.6.execution-plan-portability",
            "v0.3.7.execution-readiness",
            "v0.3.8.target-selection",
            "v0.3.9.target-decision-trace",
            "v0.3.10.public-artifact-bundle",
            "v0.3.11.conformance-manifest",
            "v0.3.12.target-adapter-contract",
            "v0.3.13.standard-diagnostic-catalog",
            "v0.3.14.diagnostic-coverage-report",
            "v0.3.15.artifact-integrity-report",
            "v0.3.16.standard-contract-index",
            "v0.3.17.standard-release-profile",
            "v0.3.18.artifact-evidence-report",
            "v0.3.19.standard-compliance-report",
            "v0.3.20.standard-freeze-report",
            "v0.3.21.compatibility-policy",
            "v0.3.22.reference-corpus",
            "v0.3.23.negative-conformance-corpus",
            "v0.4.2.target-conformance-profile",
            "v0.4.0.public-standard-draft",
            "v0.4.1.semantic-correctness-hardening",
            "v0.4.2.standard-boundary-no-sdk-runtime",
            "v0.4.3.architecture-governance-guardrails",
            "v0.4.4.ai-proposal-review",
            "v0.4.4.condition-expression-readiness",
            "v0.4.4.no-silent-condition-fallback",
            "v0.4.4.behavioral-generator-equivalence",
            "v0.4.5.standard-surface-freeze",
            "v0.4.6.compatibility-migration-policy",
            "v0.4.7.reference-intent-corpus",
            "v0.4.8.target-semantics-matrix",
            "v0.4.9.standard-export-bundle",
            "v0.5.0.standard-export-manifest",
            "v0.5.3.standard-bundle-verifier",
            "v0.5.4.data-driven-conformance-index"
        ) + postVectorIndexChecks()

    protected fun flattenCanonical(nodes: List<CanonicalPlanNode>): List<CanonicalPlanNode> =
        nodes.flatMap { node ->
            listOf(node) +
                flattenCanonical(node.then) +
                flattenCanonical(node.otherwise) +
                node.branches.flatMap { flattenCanonical(it.steps) } +
                node.cases.flatMap { flattenCanonical(it.steps) } +
                flattenCanonical(node.errorCase) +
                flattenCanonical(node.defaultSteps) +
                flattenCanonical(node.body) +
                flattenCanonical(node.errorHandler)
        }

    protected fun hasDependencyCycle(edges: List<Pair<String, String>>): Boolean {
        val graph = edges.groupBy({ it.first }, { it.second })
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            for (dep in graph[id].orEmpty()) {
                if (visit(dep)) return true
            }
            visiting -= id
            visited += id
            return false
        }

        return graph.keys.any { visit(it) }
    }

    protected fun runCheck(name: String, body: () -> Unit): ConformanceCheck = try {
        body()
        ConformanceCheck(name, true)
    } catch (t: Throwable) {
        ConformanceCheck(name, false, t.message ?: t::class.simpleName.orEmpty())
    }

    protected fun ConformanceCheck.assertPassed() {
        require(passed) { message ?: name }
    }
}

data class PipelineArtifacts(
    val intent: Any,
    val ast: Any,
    val validation: Any,
    val plan: ExecutionPlan,
    val compatibility: Any,
    val manifest: TargetManifest,
    val renderedArtifactName: String,
    val rendered: String
)
