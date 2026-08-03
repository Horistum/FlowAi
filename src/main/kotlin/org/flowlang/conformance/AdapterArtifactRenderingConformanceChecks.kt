package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.rendering.AdapterArtifactRenderingAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingEvidenceIntegrityAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingRoadmapLifecycleAuthority
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.honest.CliTargetEvidence
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner

class AdapterArtifactRenderingConformanceChecks(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    private val renderingAuthority by lazy {
        AdapterArtifactRenderingAuthority(rootDir, projections)
    }
    private val referenceEvidence: CliTargetEvidence by lazy(::renderReferenceEvidence)

    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AdapterArtifactRenderingRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val evidenceResult = runCatching {
            AdapterArtifactRenderingEvidenceIntegrityAuthority(rootDir, projections).analyze()
        }
        val evidence = evidenceResult.getOrNull()
        val runtimeResult = runCatching(::runtimeAuthorityErrors)
        val reviewResult = runCatching(::reviewArtifactSeparationErrors)
        val receiptResult = runCatching(::artifactReceiptErrors)
        val executableResult = runCatching(::executableRenderingErrors)

        val lifecycleErrors = buildList {
            lifecycleResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            lifecycle?.failedChecks?.forEach { id ->
                val failed = lifecycle.checks.first { it.id == id }
                add("$id:${failed.evidence.joinToString()}:${failed.message}")
            }
        }
        val evidenceErrors = buildList {
            evidenceResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            evidence?.findings?.forEach { add("${it.code}:${it.target}:${it.message}") }
        }

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EVIDENCE_CHECK,
                passed = evidence?.status == "PASS",
                message = evidenceErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            check(RUNTIME_AUTHORITY_CHECK, runtimeResult),
            check(REVIEW_SEPARATION_CHECK, reviewResult),
            check(RECEIPT_INTEGRITY_CHECK, receiptResult),
            check(EXECUTABLE_PROOF_CHECK, executableResult)
        )
    }

    private fun runtimeAuthorityErrors(): List<String> = buildList {
        val manifest = referenceEvidence.manifest
        val executable = renderingAuthority.render(manifest)
        if (
            executable.artifact.kind != AdapterRenderedArtifactKind.EXECUTABLE_TARGET ||
            executable.receipt.renderMode != TargetRenderMode.EXECUTABLE
        ) {
            add("Executable manifest did not produce one executable adapter artifact bundle.")
        }

        val review = manifest.copy(
            compatibility = manifest.compatibility.copy(
                status = SupportLevel.PARTIAL,
                executable = false
            )
        )
        if (runCatching { projections.requireProvider("jenkins").render(review) }.isSuccess) {
            add("Provider edge accepted a review-only manifest under the executable Jenkinsfile identity.")
        }
    }

    private fun reviewArtifactSeparationErrors(): List<String> = buildList {
        val manifest = referenceEvidence.manifest
        val review = manifest.copy(
            compatibility = manifest.compatibility.copy(
                status = SupportLevel.PARTIAL,
                executable = false
            )
        )
        val bundle = renderingAuthority.render(review)
        if (bundle.artifact.kind != AdapterRenderedArtifactKind.REVIEW_EVIDENCE) {
            add("Review-only manifest did not produce review evidence.")
        }
        if (bundle.artifact.fileName == "Jenkinsfile") {
            add("Review evidence reused the executable Jenkinsfile identity.")
        }
        if (bundle.artifact.fileName != "flow-jenkins-review.yaml") {
            add("Jenkins review evidence did not use its certified Flow-owned identity.")
        }
        if (
            "kind: TargetProjectionReview" !in bundle.artifact.content ||
            "executable: false" !in bundle.artifact.content
        ) {
            add("Review artifact does not identify itself as non-executable Flow evidence.")
        }
    }

    private fun artifactReceiptErrors(): List<String> = buildList {
        val bundle = renderingAuthority.render(referenceEvidence.manifest)
        val receipt = bundle.receipt
        if (bundle.evidenceFileName != "target-artifact-evidence.json") {
            add("Rendering evidence receipt has a non-canonical file identity.")
        }
        if (receipt.artifactSha256 != bundle.artifact.sha256 || receipt.artifactSha256.length != 64) {
            add("Rendering receipt does not bind the exact artifact digest.")
        }
        if (receipt.manifestSha256.length != 64) {
            add("Rendering receipt does not contain a SHA-256 manifest digest.")
        }
        if (receipt.evidence.isEmpty()) {
            add("Rendering receipt contains no semantic evidence inventory.")
        }
        if (receipt.evidence.map { it.id }.distinct().size != receipt.evidence.size) {
            add("Rendering receipt contains duplicate evidence identities.")
        }
        val categories = receipt.evidence.map { it.category }.toSet()
        val required = setOf(
            "manifest",
            "compatibility",
            "materialization",
            "renderer-payload",
            "renderer-binding",
            "renderer-certification"
        )
        val missing = required - categories
        if (missing.isNotEmpty()) {
            add("Rendering receipt is missing required evidence categories: ${missing.sorted().joinToString()}.")
        }
        val metadata = receipt.evidence.filter { it.category == "manifest-metadata" }
        if (metadata.none { it.reference.endsWith("metadata.adapterControlDecision") && it.detail == "MATCHED" }) {
            add("Rendering receipt does not preserve matched adapter control evidence.")
        }
        if (metadata.none { it.reference.endsWith("metadata.adapterContinuityDecision") && it.detail == "MATCHED" }) {
            add("Rendering receipt does not preserve matched adapter continuity evidence.")
        }
    }

    private fun executableRenderingErrors(): List<String> = buildList {
        val evidence = referenceEvidence
        val rendered = evidence.renderedArtifact
        if (evidence.outcome != CliTargetEvidenceOutcome.EXECUTABLE) {
            add("Executable reference did not retain an executable CLI outcome.")
        }
        if (rendered == null) {
            add("Executable reference produced no adapter artifact.")
            return@buildList
        }
        if (rendered.kind != AdapterRenderedArtifactKind.EXECUTABLE_TARGET) {
            add("Executable reference produced a review artifact instead of target syntax.")
        }
        if (rendered.fileName != "Jenkinsfile") {
            add("Executable reference did not retain the provider-owned Jenkinsfile identity.")
        }
        if ("pipeline {" !in rendered.content || "git branch:" !in rendered.content || "docker.build(" !in rendered.content) {
            add("Executable reference artifact does not preserve checkout and image-build behavior.")
        }
        if ("kind: TargetProjectionReview" in rendered.content || "executable: false" in rendered.content) {
            add("Executable reference artifact contains review-only markers.")
        }
        if (rendered.evidence.artifactSha256 != rendered.sha256) {
            add("CLI executable artifact and its rendering receipt disagree on content identity.")
        }
    }

    private fun renderReferenceEvidence(): CliTargetEvidence {
        val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/checkout-build-image.intent.yaml"))
        IntentCapabilityValidator(modules).validate(intent).assertValid()
        val plan = FlowPlanner(modules).plan(IntentToAstPlanner(modules).plan(intent))
        val selection = TargetSelectionAuthority.fromConformanceCheck(
            value = "jenkins",
            checkId = EXECUTABLE_PROOF_CHECK,
            targets = targets
        )
        return CliTargetEvidenceAuthority(targets, projections, rootDir).evaluate(
            plan = plan,
            explicitSelection = selection,
            strict = false,
            renderRequested = true
        )
    }

    private fun check(name: String, result: Result<List<String>>): ConformanceCheck {
        val errors = result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
        return ConformanceCheck(
            name = name,
            passed = errors.isEmpty(),
            message = errors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )
    }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a0.6.lifecycle-integrity"
        const val EVIDENCE_CHECK = "adapters.a0.6.rendering-evidence-integrity"
        const val RUNTIME_AUTHORITY_CHECK = "adapters.a0.6.runtime-rendering-authority"
        const val REVIEW_SEPARATION_CHECK = "adapters.a0.6.review-artifact-separation"
        const val RECEIPT_INTEGRITY_CHECK = "adapters.a0.6.artifact-receipt-integrity"
        const val EXECUTABLE_PROOF_CHECK = "adapters.a0.6.executable-rendering-proof"
    }
}
