package org.flowlang.conformance

import org.flowlang.frontend.FrontendCompilerComposition

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
        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(intent)
        val validation = FrontendCompilerComposition.flowValidator(registry).validate(ast)
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
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val plan = FlowPlanner(registry).plan(FrontendCompilerComposition.intentPlanner(registry).plan(intent))
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "github-actions", strict = false)
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(plan, "github-actions", strict = false)
        require(compatibility.hasErrors) {
            "GitHub Actions reference pipeline must fail without explicit workspace continuity evidence."
        }
        val state = ReferenceSnapshotHonesty.targetState(ReferenceBlockedProjectionEvidence(compatibility, readiness))
        require(state.renderMode == TargetRenderMode.FAIL_FAST)
        require(!state.manifestPresent && !state.renderedArtifactPresent)
        require(state.blockers.any { it.feature == "continuity.workspace" })
    }

    private fun checkTektonManifestGeneration(): ConformanceCheck = runCheck("generator.manifest.tekton.partial") {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val plan = FlowPlanner(registry).plan(FrontendCompilerComposition.intentPlanner(registry).plan(intent))
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
            "github-actions.blocked.json",
            "tekton.blocked.json",
            "README.md"
        )
        required.forEach { name -> require(File(dir, name).isFile) { "Missing snapshot $name" } }

        val executableDir = File(rootDir, "conformance/snapshots/checkout-build-image")
        val executableRequired = listOf(
            "normalized-intent.json",
            "flow-ast.json",
            "execution-plan.json",
            "snapshot-index.json",
            "jenkins.executable.yaml",
            "README.md"
        )
        executableRequired.forEach { name ->
            require(File(executableDir, name).isFile) { "Missing executable reference snapshot $name" }
        }
        require(executableDir.listFiles().orEmpty().none { it.name.endsWith(".review.yaml") || it.name.endsWith(".blocked.json") }) {
            "First executable reference must not contain review-only or blocked target evidence."
        }

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
            val github = snapshot.targets.single { it.target == "github-actions" }
            require(github.renderMode == TargetRenderMode.FAIL_FAST)
            require(!github.manifestPresent && !github.renderedArtifactPresent)
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

            val canonicalPlan = Json.mapper.readTree(File(committed, "execution-plan.json"))
            val canonicalModules = canonicalPlan.findValuesAsText("module")
            val canonicalActions = canonicalPlan.findValuesAsText("action")
            require("shell" !in canonicalModules) { "Canonical reference plan must not contain a shell module." }
            require("run" !in canonicalActions) { "Canonical reference plan must not contain a shell-style run action." }
            require("standard" in canonicalModules) { "Canonical reference plan must retain target-neutral standard tasks." }
            val readme = File(committed, "README.md").readText()
            require(readme.contains("0.9.5"))
            require(readme.contains("0.8.0"))
            require(readme.contains("2.0"))
            require(readme.contains("First Executable Reference Scenario")) {
                "Mixed reference README must identify the separate executable-scenario evidence boundary."
            }

            val executableCommitted = File(rootDir, "conformance/snapshots/checkout-build-image")
            val executableGenerated = File(
                System.getProperty("java.io.tmpdir"),
                "flow-executable-reference-snapshot-${System.nanoTime()}"
            )
            try {
                val executableSnapshot = ReferenceSnapshotBundleGenerator(rootDir, registry, targets, projections).generate(
                    intentFile = File(rootDir, "examples/intent/checkout-build-image.intent.yaml"),
                    outputDir = executableGenerated,
                    scenarioId = "checkout-build-image",
                    targetIds = setOf("jenkins")
                )
                require(ReferenceSnapshotHonesty.validate(executableSnapshot).isEmpty()) {
                    "Executable reference evidence is inconsistent: ${ReferenceSnapshotHonesty.validate(executableSnapshot)}"
                }
                require(executableSnapshot.overallState == ReferenceSnapshotSetState.EXECUTABLE && executableSnapshot.executable) {
                    "Checkout-build-image must be executable for its explicitly selected target scope."
                }
                val jenkins = executableSnapshot.targets.single()
                require(jenkins.target == "jenkins" && jenkins.renderMode == TargetRenderMode.EXECUTABLE) {
                    "First executable reference must contain only executable Jenkins evidence."
                }
                require(jenkins.manifestPresent && jenkins.renderedArtifactPresent)

                val executableGeneratedFiles = executableGenerated.listFiles().orEmpty()
                    .filter { it.isFile }.map { it.name }.sorted()
                val executableCommittedFiles = executableCommitted.listFiles().orEmpty()
                    .filter { it.isFile && it.name != "README.md" }.map { it.name }.sorted()
                require(executableCommittedFiles == executableGeneratedFiles) {
                    "Committed executable reference differs from canonical generation. " +
                        "committed=$executableCommittedFiles generated=$executableGeneratedFiles"
                }
                executableGeneratedFiles.forEach { name ->
                    val generatedFile = File(executableGenerated, name)
                    val committedFile = File(executableCommitted, name)
                    if (name.endsWith(".json")) {
                        require(Json.mapper.readTree(committedFile) == Json.mapper.readTree(generatedFile)) {
                            "Executable JSON snapshot mismatch for $name."
                        }
                    } else {
                        assertSnapshotEquals(committedFile, generatedFile.readText())
                    }
                }

                val artifact = File(executableCommitted, "jenkins.executable.yaml").readText()
                require(artifact.contains("pipeline {"))
                require(artifact.indexOf("git branch:") < artifact.indexOf("docker.build(")) {
                    "Executable reference must preserve checkout before image build."
                }
                require(!artifact.contains("kind: TargetProjectionReview"))
                require(artifact.lineSequence().none { line ->
                    val trimmed = line.trim()
                    trimmed == "sh" || trimmed.startsWith("sh ") || trimmed.startsWith("sh(")
                }) { "Executable reference must not introduce a Jenkins shell step." }

                val executableReadme = File(executableCommitted, "README.md").readText()
                require(executableReadme.contains("target-scoped to **Jenkins**"))
                require(executableReadme.contains("build-test-deploy"))
            } finally {
                executableGenerated.deleteRecursively()
            }
        } finally {
            generated.deleteRecursively()
        }
    }
}
