package org.flowlang.conformance

import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.validator.FlowValidator
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class CorePipelineSnapshotChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkValidIntentPipeline(),
        checkArgocdMissingConfigFails(),
        checkTektonStrictApprovalFails(),
        checkJenkinsManifestGeneration(),
        checkGitHubManifestGeneration(),
        checkTektonManifestGeneration(),
        checkFlowStyleYamlIntent(),
        checkEndToEndSnapshotsExist(),
        checkEndToEndSnapshotContent()
    )

    private fun checkValidIntentPipeline(): ConformanceCheck = runCheck("intent.valid.build-test-deploy") {
        val artifacts = buildPipeline("jenkins", strict = true)
        require(artifacts.plan.tasks.isNotEmpty()) { "Expected at least one execution task." }
        require(artifacts.manifest.standardVersion == FlowStandardVersions.FLOW_STANDARD_VERSION) { "Manifest standardVersion missing." }
    }

    private fun checkArgocdMissingConfigFails(): ConformanceCheck = runCheck("intent.invalid.argocd-missing-config") {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/bad-argocd-missing-config.intent.yaml"))
        val report = IntentCapabilityValidator(registry).validate(intent)
        require(!report.valid) { "Expected invalid ArgoCD config intent." }
        require(report.issues.any { it.code == "MISSING_SYSTEM_CONFIG" }) { "Expected MISSING_SYSTEM_CONFIG." }
    }

    private fun checkTektonStrictApprovalFails(): ConformanceCheck = runCheck("target.strict.tekton-approval-unsupported") {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val validation = FlowValidator(registry).validate(ast)
        require(validation.valid) { validation.issues.joinToString { it.code + ": " + it.message } }
        val plan = FlowPlanner(registry).plan(ast)
        val report = CompatibilityAnalyzer(targets).analyze(plan, "tekton", strict = true)
        require(report.hasErrors) { "Expected Tekton strict compatibility errors for approval." }
    }

    private fun checkJenkinsManifestGeneration(): ConformanceCheck = runCheck("generator.manifest.jenkins") {
        val artifacts = buildPipeline("jenkins", strict = false)
        require(artifacts.manifest.target == "jenkins") { "Unexpected manifest target." }
        require(artifacts.manifest.jobs.flatMap { it.steps }.isNotEmpty()) { "Expected Jenkins manifest steps." }
        require(TargetRenderPolicy.evaluate(artifacts.manifest).mode == TargetRenderMode.REVIEW_ONLY) {
            "Unresolved Jenkins projection must remain review-only."
        }
        require(artifacts.rendered.contains("kind: TargetProjectionReview")) { "Expected Flow review artifact." }
        require(!artifacts.rendered.contains("pipeline {")) { "Review-only output must not masquerade as a Jenkins pipeline." }
    }

    private fun checkGitHubManifestGeneration(): ConformanceCheck = runCheck("generator.manifest.github-actions") {
        val artifacts = buildPipeline("github-actions", strict = false)
        require(artifacts.manifest.target == "github-actions") { "Unexpected manifest target." }
        require(artifacts.manifest.jobs.isNotEmpty()) { "Expected GitHub Actions manifest jobs." }
        require(TargetRenderPolicy.evaluate(artifacts.manifest).mode == TargetRenderMode.REVIEW_ONLY) {
            "Unresolved GitHub Actions projection must remain review-only."
        }
        require(artifacts.rendered.contains("kind: TargetProjectionReview")) { "Expected Flow review artifact." }
        require(!artifacts.rendered.contains("jobs:")) { "Review-only output must not masquerade as a GitHub Actions workflow." }
        require(!artifacts.rendered.contains("steps: []")) { "Review-only output must not emit a green no-op job." }
    }

    private fun checkTektonManifestGeneration(): ConformanceCheck = runCheck("generator.manifest.tekton.partial") {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val plan = FlowPlanner(registry).plan(IntentToAstPlanner(registry).plan(intent))
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "tekton", strict = false)
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(plan, "tekton", strict = false)
        require(compatibility.hasErrors) { "Tekton reference pipeline must fail compatibility before manifest generation." }
        val state = ReferenceSnapshotHonesty.targetState(ReferenceBlockedProjectionEvidence(compatibility, readiness))
        require(state.renderMode == TargetRenderMode.FAIL_FAST)
        require(!state.manifestPresent && !state.renderedArtifactPresent)
        require(state.blockers.any { it.feature == "approvals" })
        require(state.blockers.any { it.feature == "standard.rollback" })
    }

    private fun checkFlowStyleYamlIntent(): ConformanceCheck = runCheck("intent.yaml.flow-style") {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/argocd-deploy.intent.yaml"))
        val report = IntentCapabilityValidator(registry).validate(intent)
        require(report.valid) { report.issues.joinToString { it.code + ": " + it.message } }
        require(intent.systems.firstOrNull { it.name == "argo" }?.config?.get("token") is org.flowlang.intent.IntentSecretRef) {
            "Flow-style config map was not normalized correctly."
        }
    }

    private fun checkEndToEndSnapshotsExist(): ConformanceCheck = runCheck("snapshots.e2e.files-exist") {
        val dir = File(rootDir, "conformance/snapshots/build-test-deploy")
        val required = listOf(
            "normalized-intent.json",
            "flow-ast.json",
            "execution-plan.json",
            "snapshot-index.json",
            "jenkins.review.yaml",
            "github-actions.review.yaml",
            "tekton.blocked.json",
            "README.md"
        )
        required.forEach { name -> require(File(dir, name).isFile) { "Missing snapshot $name" } }
        ReferenceSnapshotHonesty.legacyExecutableLookingFiles.forEach { name ->
            require(!File(dir, name).exists()) { "Legacy executable-looking or stale snapshot '$name' must be removed." }
        }
    }

    private fun checkEndToEndSnapshotContent(): ConformanceCheck = runCheck("snapshots.e2e.content") {
        val committed = File(rootDir, "conformance/snapshots/build-test-deploy")
        val generated = File(System.getProperty("java.io.tmpdir"), "flow-reference-snapshot-${System.nanoTime()}")
        try {
            val snapshot = ReferenceSnapshotBundleGenerator(rootDir, registry, targets, projections).generate(
                intentFile = File(rootDir, "examples/intent/build-test-deploy.intent.yaml"),
                outputDir = generated,
                scenarioId = "build-test-deploy"
            )
            require(ReferenceSnapshotHonesty.validate(snapshot).isEmpty()) {
                "Generated snapshot evidence is inconsistent: ${ReferenceSnapshotHonesty.validate(snapshot)}"
            }
            require(snapshot.overallState == ReferenceSnapshotSetState.MIXED && !snapshot.executable) {
                "Realistic build-test-deploy evidence must be mixed and non-executable."
            }
            require(snapshot.targets.single { it.target == "jenkins" }.renderMode == TargetRenderMode.REVIEW_ONLY)
            require(snapshot.targets.single { it.target == "github-actions" }.renderMode == TargetRenderMode.REVIEW_ONLY)
            val tekton = snapshot.targets.single { it.target == "tekton" }
            require(tekton.renderMode == TargetRenderMode.FAIL_FAST)
            require(!tekton.manifestPresent && !tekton.renderedArtifactPresent)

            val generatedFiles = generated.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
            val committedFiles = committed.listFiles().orEmpty().filter { it.isFile && it.name != "README.md" }.map { it.name }.sorted()
            require(committedFiles == generatedFiles) {
                "Committed reference snapshot files differ from canonical generation. committed=$committedFiles generated=$generatedFiles"
            }
            generatedFiles.forEach { name ->
                val generatedFile = File(generated, name)
                val committedFile = File(committed, name)
                if (name.endsWith(".json")) {
                    val expected = Json.mapper.readTree(committedFile.readText())
                    val actual = Json.mapper.readTree(generatedFile.readText())
                    require(expected == actual) { "JSON snapshot mismatch for $name." }
                } else {
                    assertSnapshotEquals(committedFile, generatedFile.readText())
                }
            }

            val canonicalPlan = File(committed, "execution-plan.json").readText()
            require(!canonicalPlan.contains("\"module\" : \"shell\""))
            require(!canonicalPlan.contains("\"action\" : \"run\""))
            require(canonicalPlan.contains("\"module\" : \"standard\""))
            val readme = File(committed, "README.md").readText()
            require(readme.contains("0.9.5"))
            require(readme.contains("0.8.0"))
            require(readme.contains("2.0"))
            require(readme.contains("Projection Rule Coverage"))
        } finally {
            generated.deleteRecursively()
        }
    }
}
