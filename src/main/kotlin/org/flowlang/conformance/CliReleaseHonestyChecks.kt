package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.release.ReleaseMetadataHonestyAuthority

internal class CliReleaseHonestyChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkCliDiagnosticAndReleaseHonesty()
    )

    private fun checkCliDiagnosticAndReleaseHonesty(): ConformanceCheck =
        runCheck("cli.release.diagnostic-honesty") {
            val gradle = File(rootDir, "build.gradle.kts").readText()
            require(gradle.contains("org.flowlang.cli.honest.HonestFlowCliKt")) {
                "The application entrypoint bypasses the honest CLI authority."
            }

            val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
            IntentCapabilityValidator(registry).validate(intent).assertValid()
            val plan = FlowPlanner(registry).plan(IntentToAstPlanner(registry).plan(intent))
            val evidence = CliTargetEvidenceAuthority(targets, projections).evaluate(
                plan = plan,
                explicitSelection = explicitTarget("jenkins", "conformance:cli-release"),
                strict = false,
                renderRequested = false
            )
            require(evidence.readiness.readinessEvidenceAvailable) {
                "CLI exported preliminary readiness beside a concrete manifest."
            }
            require(evidence.negotiation.readinessEvidenceAvailable) {
                "CLI exported preliminary negotiation beside a concrete manifest."
            }
            require(evidence.selection.candidates.first { it.target == "jenkins" }.readinessEvidenceAvailable) {
                "CLI target selection was not reconciled against the emitted Jenkins manifest."
            }
            require(evidence.renderedArtifact == null) {
                "CLI emitted an adapter artifact even though rendering was not requested."
            }
            require(evidence.renderReadiness.mode == TargetRenderMode.REVIEW_ONLY) {
                "The reference Jenkins manifest should remain review-only, not executable."
            }

            val requestedRender = CliTargetEvidenceAuthority(targets, projections).evaluate(
                plan = plan,
                explicitSelection = explicitTarget("jenkins", "conformance:cli-release"),
                strict = false,
                renderRequested = true
            )
            require(requestedRender.outcome == CliTargetEvidenceOutcome.REVIEW_ONLY) {
                "Review-only target evidence was not preserved as a typed CLI outcome."
            }
            val rendered = requireNotNull(requestedRender.renderedArtifact) {
                "Requested review-only evidence did not emit its dedicated review artifact."
            }
            require(rendered.kind == AdapterRenderedArtifactKind.REVIEW_EVIDENCE) {
                "Review-only target evidence was mislabeled as executable target syntax."
            }
            require(rendered.fileName == "flow-jenkins-review.yaml") {
                "Review-only Jenkins evidence used executable or unexpected file identity '${rendered.fileName}'."
            }
            require(
                "kind: TargetProjectionReview" in rendered.content &&
                    "executable: false" in rendered.content
            ) {
                "Review-only artifact lacks explicit non-executable identity."
            }
            require(rendered.evidence.renderMode == TargetRenderMode.REVIEW_ONLY) {
                "Review-only artifact receipt contradicts its render mode."
            }
            require(requestedRender.diagnostics.any { it.code == "CLI_RENDER_NOT_AUTHORIZED" }) {
                "A denied executable render request lacks a stable diagnostic code."
            }

            val release = ReleaseMetadataHonestyAuthority(rootDir).requireValid()
            require(BOUNDED_CORRECTION.matches(release.completedCorrectionItem)) {
                "Release honesty selected correction '${release.completedCorrectionItem}' outside the bounded v0.9.7 correction vocabulary."
            }
            require(release.closureItem == "0.9.7.10") {
                "Release honesty lost the permanent bounded closure identity."
            }
            val expectedNextItem = if (release.closurePhase == "READY") release.closureItem else null
            require(release.nextCoreItem == expectedNextItem) {
                "Release honesty exposed nextCoreItem=${release.nextCoreItem} in phase ${release.closurePhase}; expected $expectedNextItem."
            }
            require(release.correctionStatus in setOf("active", "complete", "completed"))

            val releaseAssembly = File(rootDir, "src/main/kotlin/org/flowlang/release/StandardReleaseAssembly.kt").readText()
            require(!releaseAssembly.contains("ADAPTER_CONTRACT_READY")) {
                "Release assembly fabricates adapter-ready diagnostics without a target artifact."
            }
            val publishMethod = releaseAssembly
                .substringAfter("fun publishValidatedBundle")
                .substringBefore("private fun releaseBundle")
            val verifyIndex = publishMethod.indexOf("StandardBundleVerifier().verify(staging)")
            val publishIndex = publishMethod.indexOf("publishDirectory(staging, outputDir)")
            require(verifyIndex >= 0) {
                "Release publication does not verify the staged bundle."
            }
            require(publishIndex >= 0 && verifyIndex < publishIndex) {
                "Release bundle is published before staged verification."
            }

            listOf(
                "src/main/kotlin/org/flowlang/capabilities/CompatibilityAnalyzer.kt",
                "src/main/kotlin/org/flowlang/capabilities/ExecutionReadiness.kt",
                "src/main/kotlin/org/flowlang/capabilities/TargetNegotiationReportAnalyzer.kt",
                "src/main/kotlin/org/flowlang/standard/StandardDiagnosticCatalog.kt"
            ).forEach { path ->
                val text = File(rootDir, path).readText()
                require(!text.contains("requires Flow runtime support", ignoreCase = true)) {
                    "Legacy runtime-ownership wording remains in $path."
                }
                require(!text.contains("Generator/runtime may", ignoreCase = true)) {
                    "Ambiguous generator/runtime ownership wording remains in $path."
                }
            }
        }

    companion object {
        private val BOUNDED_CORRECTION = Regex("0\\.9\\.7\\.(?:9|10)\\.\\d+")
    }
}
