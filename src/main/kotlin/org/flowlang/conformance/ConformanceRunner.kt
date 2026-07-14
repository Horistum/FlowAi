package org.flowlang.conformance

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.capabilities.TargetDecisionKind
import org.flowlang.capabilities.DecisionTraceStatus
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.StandardComplianceAnalyzer
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer
import org.flowlang.architecture.ArchitectureDeltaAnalyzer
import org.flowlang.architecture.StandardModelSnapshot
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetExpressionTranslator
import org.flowlang.generators.manifest.TargetExpressionTranslationException
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDecisionAnalyzer
import org.flowlang.intent.IntentDesignAnalyzer
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.CanonicalPlanNode
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardModel
import org.flowlang.standard.PurposeCoverageAnalyzer
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.ObservedDiagnosticCode
import org.flowlang.standard.StandardDiagnosticCatalog
import org.flowlang.standard.StandardIntentCatalog
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.validator.FlowValidator
import java.io.File

/**
 * CLI-facing conformance runner for Flow standard vectors.
 *
 * This checks the public path:
 * Intent/AI intent -> capability validation -> AST -> Flow validation -> execution
 * plan -> target compatibility -> target manifest -> rendered output.
 * A standard without reproducible vectors is folklore with version numbers, and
 * humanity has already produced enough folklore in YAML.
 */
class ConformanceRunner(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry = if (File(rootDir, "modules").isDirectory) ModuleRegistry.fromDirectory(File(rootDir, "modules"), includeDefaults = true) else ModuleRegistry(),
    private val targets: Map<String, org.flowlang.capabilities.TargetCapability> = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")).also {
        require(it.isNotEmpty()) { "No target registry found under ${File(rootDir, "targets").path}." }
    }
) {
    fun run(): ConformanceSummary {
        val checks = mutableListOf<ConformanceCheck>()
        checks += checkValidIntentPipeline()
        checks += checkArgocdMissingConfigFails()
        checks += checkTektonStrictApprovalFails()
        checks += checkJenkinsManifestGeneration()
        checks += checkGitHubManifestGeneration()
        checks += checkTektonManifestGeneration()
        checks += checkFlowStyleYamlIntent()
        checks += checkEndToEndSnapshotsExist()
        checks += checkEndToEndSnapshotContent()
        checks += checkRenderedSnapshotsContainVersion()
        checks += checkStandardIntentCatalogCoverage()
        checks += checkStandardCapabilityContracts()
        checks += checkIntentDesignReport()
        checks += checkCorePackagesDoNotImportJackson()
        checks += checkModulesDoNotOwnTargetRendering()
        checks += checkAiNormalizationDeployment()
        checks += checkAiNormalizationFullPipelineValidation()
        checks += checkAiNormalizationMissingApplicationQuestion()
        checks += checkPublicOutputsMatchSchemas()
        checks += checkScenarioPackCatalog()
        checks += checkScenarioPackNormalizationFullPipelines()
        checks += checkScenarioPackRegressionCoverage()
        checks += checkV031ScenarioPackVectors()
        checks += checkV031CapabilityNegotiationReport()
        checks += checkV032CanonicalExecutionPlan()
        checks += checkV032SafetyPolicies()
        checks += checkV034CapabilityModuleContracts()
        checks += checkV035IntentDecisionModel()
        checks += checkV036ExecutionPlanPortability()
        checks += checkV037ExecutionReadinessReport()
        checks += checkV038TargetSelectionReport()
        checks += checkV039TargetDecisionTraceReport()
        checks += checkV0310PublicArtifactBundle()
        checks += checkV0311ConformanceManifest()
        checks += checkV0312TargetAdapterContract()
        checks += checkV0313StandardDiagnosticCatalog()
        checks += checkV0314DiagnosticCoverageReport()
        checks += checkV0315ArtifactIntegrityReport()
        checks += checkV0316StandardContractIndex()
        checks += checkV0317StandardReleaseProfile()
        checks += checkV0318ArtifactEvidenceReport()
        checks += checkV0319StandardComplianceReport()
        checks += checkV0320StandardFreezeReport()
        checks += checkV0321CompatibilityPolicy()
        checks += checkV0322ReferenceCorpus()
        checks += checkV0323NegativeConformanceCorpus()
        checks += checkV042TargetConformanceProfile()
        checks += checkV040PublicStandardDraft()
        checks += checkV041SemanticCorrectnessHardening()
        checks += checkV042StandardBoundary()
        checks += checkV043ArchitectureGovernanceGuardrails()
        checks += checkV044AiProposalReview()
        checks += checkV044ConditionExpressionReadiness()
        checks += checkV044NoSilentConditionFallback()
        checks += checkV044BehavioralGeneratorEquivalence()
        checks += checkV045StandardSurfaceFreeze()
        checks += checkV046CompatibilityMigrationPolicy()
        checks += checkV047ReferenceIntentCorpus()
        checks += checkV048TargetSemanticsMatrix()
        checks += checkV049StandardExportBundle()
        checks += checkV050StandardExportManifest()
        checks += checkV053StandardBundleVerifier()
        checks += checkV054DataDrivenConformanceIndex(
            checks.map { it.name } + "v0.5.4.data-driven-conformance-index" + postVectorIndexChecks()
        )
        checks += checkV061IntentCorpusExpansion()
        checks += checkV062RequiredClarificationContract()
        checks += checkV063SafetyPolicyMatrix()
        checks += checkV064TargetSemanticsNegativeCorpus()
        checks += checkV065ExecutionPlanSemanticInvariants()
        checks += checkV066AiInputTrustBoundary()
        checks += checkV067StandardExampleBundle()
        checks += checkV068CompatibilityPromise()
        checks += checkV070ReferenceCorpusExecutionHarness()
        checks += checkV071ArchitectureDebtCleanupAndDriftEnforcement()
        checks += checkV073StandardModelProjectionCoherence()
        checks += checkV074ArchitectureDeltaAnalyzer()
        checks += checkV075PurposeCoverageRatio()
        checks += ConformanceQualityGates.run()
        return ConformanceSummary(checks)
    }

    private fun buildPipeline(target: String, strict: Boolean = false): PipelineArtifacts {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
        val intentReport = IntentCapabilityValidator(registry).validate(intent)
        intentReport.assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val validation = FlowValidator(registry).validate(ast)
        require(validation.valid) { validation.issues.joinToString { it.code + ": " + it.message } }
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target, strict = strict)
        if (strict) compatibility.assertAllowed(strict = true)
        val manifest = when (target) {
            "jenkins" -> JenkinsManifestGenerator().generate(plan, compatibility)
            "github-actions" -> GitHubActionsManifestGenerator().generate(plan, compatibility)
            "tekton" -> TektonManifestGenerator().generate(plan, compatibility)
            else -> error("No conformance manifest generator for target '$target'.")
        }
        val rendered = when (target) {
            "jenkins" -> JenkinsManifestRenderer().render(manifest)
            "github-actions" -> GitHubActionsManifestRenderer().render(manifest)
            "tekton" -> TektonManifestRenderer().render(manifest)
            else -> ""
        }
        return PipelineArtifacts(intent, ast, validation, plan, compatibility, manifest, rendered)
    }

    private fun referenceDiagnosticCoverage(artifacts: PipelineArtifacts, target: String) =
        DiagnosticCoverageAnalyzer().analyze(referenceObservedDiagnostics(artifacts, target))

    private fun referenceArtifactBundle(artifacts: PipelineArtifacts, target: String) =
        FlowArtifactBundleAnalyzer().intentBundle(
            flowName = artifacts.plan.flowName,
            target = target,
            strict = false,
            hasManifest = true,
            renderedArtifact = when (target) {
                "jenkins" -> "Jenkinsfile"
                "github-actions" -> "github-actions.yml"
                "tekton" -> "tekton-pipeline.yaml"
                else -> null
            }
        )

    private fun referenceArtifactIntegrity(artifacts: PipelineArtifacts, target: String) =
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

    private fun referenceContractIndex(artifacts: PipelineArtifacts, target: String) =
        StandardContractIndexAnalyzer().analyze(referenceArtifactBundle(artifacts, target))

    private fun referenceReleaseProfile() = StandardReleaseProfile.report()

    private fun referenceEvidence(artifacts: PipelineArtifacts, target: String) =
        ArtifactEvidenceAnalyzer().analyze(referenceArtifactBundle(artifacts, target))

    private fun referenceCompliance(artifacts: PipelineArtifacts, target: String) =
        StandardComplianceAnalyzer().analyze(
            bundle = referenceArtifactBundle(artifacts, target),
            contractIndex = referenceContractIndex(artifacts, target),
            releaseProfile = referenceReleaseProfile(),
            evidence = referenceEvidence(artifacts, target),
            integrity = referenceArtifactIntegrity(artifacts, target)
        )

    private fun referenceFreeze(artifacts: PipelineArtifacts, target: String) =
        PublicStandardDraft.freeze(referenceContractIndex(artifacts, target))

    private fun referenceStandardIndex(artifacts: PipelineArtifacts, target: String) =
        PublicStandardDraft.standardIndex(referenceArtifactBundle(artifacts, target), referenceContractIndex(artifacts, target))

    private fun referenceConformanceSuite() = PublicStandardDraft.conformanceSuite()

    private fun referenceDraft(artifacts: PipelineArtifacts, target: String) =
        PublicStandardDraft.draft(referenceArtifactBundle(artifacts, target), referenceCompliance(artifacts, target))

    private fun referenceObservedDiagnostics(artifacts: PipelineArtifacts, target: String): List<ObservedDiagnosticCode> {
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

    private fun observed(artifact: String, code: String, source: String, severity: String): ObservedDiagnosticCode =
        ObservedDiagnosticCode(artifact = artifact, code = code, source = source, severity = severity)

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
        val artifacts = buildPipeline("tekton", strict = false)
        require(artifacts.manifest.target == "tekton") { "Unexpected manifest target." }
        require(artifacts.manifest.metadata["supportLevel"] == "partial") { "Tekton manifest must declare partial support." }
        require(TargetRenderPolicy.evaluate(artifacts.manifest).mode == TargetRenderMode.REVIEW_ONLY) {
            "Unresolved Tekton projection must remain review-only."
        }
        require(artifacts.rendered.contains("kind: TargetProjectionReview")) { "Expected Flow review artifact." }
        require(!artifacts.rendered.contains("kind: Pipeline")) { "Review-only output must not masquerade as a Tekton Pipeline." }
        require(!artifacts.rendered.contains("flow-materialization-required")) { "Review-only output must not reference a phantom Tekton task." }
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
            "tekton.review.yaml",
            "README.md"
        )
        required.forEach { name -> require(File(dir, name).isFile) { "Missing snapshot $name" } }
        ReferenceSnapshotHonesty.legacyExecutableLookingFiles.forEach { name ->
            require(!File(dir, name).exists()) {
                "Legacy executable-looking or stale snapshot '$name' must be removed."
            }
        }
    }

    private fun checkEndToEndSnapshotContent(): ConformanceCheck = runCheck("snapshots.e2e.content") {
        val dir = File(rootDir, "conformance/snapshots/build-test-deploy")
        val jenkins = buildPipeline("jenkins", strict = false)
        val github = buildPipeline("github-actions", strict = false)
        val tekton = buildPipeline("tekton", strict = false)
        val version = FlowStandardVersions.FLOW_STANDARD_VERSION
        val pipelines = listOf(jenkins, github, tekton)
        val expectedIndex = ReferenceSnapshotHonesty.build(
            scenarioId = "build-test-deploy",
            standardVersion = version,
            manifests = pipelines.map { it.manifest }
        )
        val committedIndex = Json.mapper.readValue(
            File(dir, "snapshot-index.json"),
            ReferenceSnapshotSet::class.java
        )

        require(ReferenceSnapshotHonesty.validate(expectedIndex).isEmpty()) {
            "Generated reference snapshot evidence is internally inconsistent: ${ReferenceSnapshotHonesty.validate(expectedIndex)}"
        }
        require(ReferenceSnapshotHonesty.validate(committedIndex).isEmpty()) {
            "Committed reference snapshot evidence is internally inconsistent: ${ReferenceSnapshotHonesty.validate(committedIndex)}"
        }
        require(committedIndex == expectedIndex) {
            "Committed snapshot-index.json does not match current materialization and projection evidence."
        }
        require(!committedIndex.executable) { "Reference snapshot set must not claim executable output." }
        require(committedIndex.claim == ReferenceSnapshotClaim.REVIEW_ONLY_PROJECTION_SET) {
            "Current build-test-deploy snapshots must be explicitly classified as review-only."
        }

        assertJsonSnapshotEquals(File(dir, "normalized-intent.json"), jenkins.intent)
        assertJsonSnapshotEquals(File(dir, "flow-ast.json"), jenkins.ast)
        assertJsonSnapshotEquals(File(dir, "execution-plan.json"), ExecutionPlanCanonicalizer.canonicalize(jenkins.plan))

        val canonicalPlanText = File(dir, "execution-plan.json").readText()
        require(!canonicalPlanText.contains("\"module\" : \"shell\"")) {
            "Flagship execution-plan snapshot must not contain shell materialization."
        }
        require(!canonicalPlanText.contains("\"action\" : \"run\"")) {
            "Flagship execution-plan snapshot must not contain generic run-command projection."
        }
        require(canonicalPlanText.contains("\"module\" : \"standard\"")) {
            "Flagship execution-plan snapshot must preserve semantic standard test intent."
        }

        pipelines.forEach { artifacts ->
            val readiness = TargetRenderPolicy.evaluate(artifacts.manifest)
            require(readiness.mode == TargetRenderMode.REVIEW_ONLY) {
                "Reference projection for ${artifacts.manifest.target} must remain review-only until renderer payload evidence exists."
            }
            require(!readiness.executable) {
                "Reference projection for ${artifacts.manifest.target} must be explicitly non-executable."
            }
            require(artifacts.rendered.contains("standardVersion: \"$version\"")) {
                "Review artifact for ${artifacts.manifest.target} must carry the active standard version."
            }
            require(artifacts.rendered.contains("renderMode: REVIEW_ONLY")) {
                "Review artifact for ${artifacts.manifest.target} must declare REVIEW_ONLY mode."
            }
            require(artifacts.rendered.contains("executable: false")) {
                "Review artifact for ${artifacts.manifest.target} must be explicitly non-executable."
            }
            assertSnapshotEquals(
                File(dir, ReferenceSnapshotHonesty.projectionFile(artifacts.manifest.target, readiness.mode)),
                artifacts.rendered
            )
        }

        require(jenkins.manifest.allStepParams().any { it.contains("${'$'}{version}") }) {
            "Jenkins manifest must preserve the authored version input reference before target rendering."
        }
        require(github.manifest.allStepParams().any { it.contains("${'$'}{version}") }) {
            "GitHub Actions manifest must preserve the authored version input reference before target rendering."
        }
        require(tekton.manifest.allStepParams().any { it.contains("${'$'}{version}") }) {
            "Tekton manifest must preserve the authored version input reference before target rendering."
        }
        require(!jenkins.rendered.contains("pipeline {")) { "Review snapshot must not contain Jenkins vendor syntax." }
        require(!github.rendered.contains("jobs:")) { "Review snapshot must not contain GitHub Actions vendor syntax." }
        require(!tekton.rendered.contains("kind: Pipeline")) { "Review snapshot must not contain Tekton vendor syntax." }

        val readme = File(dir, "README.md").readText()
        require(readme.contains("review-only", ignoreCase = true)) {
            "Snapshot README must state that target outputs are review-only."
        }
        require(!readme.contains("first public end-to-end Flow conformance snapshot", ignoreCase = true)) {
            "Snapshot README must not claim end-to-end execution."
        }
    }

    private fun checkRenderedSnapshotsContainVersion(): ConformanceCheck = runCheck("snapshots.rendered.standard-version") {
        val dir = File(rootDir, "conformance/snapshots/build-test-deploy")
        val version = FlowStandardVersions.FLOW_STANDARD_VERSION
        listOf("jenkins.review.yaml", "github-actions.review.yaml", "tekton.review.yaml").forEach { name ->
            val text = File(dir, name).readText()
            require(text.contains(version)) { "Snapshot $name does not contain Flow standard version $version" }
            require(text.contains("renderMode: REVIEW_ONLY")) { "Snapshot $name must declare REVIEW_ONLY mode." }
            require(text.contains("executable: false")) { "Snapshot $name must be explicitly non-executable." }
        }
    }

    private fun checkStandardIntentCatalogCoverage(): ConformanceCheck = runCheck("standard.catalog.coverage") {
        val capabilities = org.flowlang.intent.StandardCapability.values().toSet()
        val catalog = StandardIntentCatalog.byCapability.keys
        require(catalog.containsAll(capabilities)) { "Standard catalog does not cover all StandardCapability enum values." }
        require(StandardIntentCatalog.definitions.any { it.capability.name == "BACKUP" }) { "Catalog must include non-CI/CD capability BACKUP." }
        require(StandardIntentCatalog.definitions.any { it.capability.name == "SECRET_ROTATE" }) { "Catalog must include security capability SECRET_ROTATE." }
    }

    private fun checkStandardCapabilityContracts(): ConformanceCheck = runCheck("standard.capability-contracts") {
        val capabilities = org.flowlang.intent.StandardCapability.values().toSet()
        val contracts = org.flowlang.standard.StandardCapabilityContracts.all.keys
        require(contracts.containsAll(capabilities)) { "Standard capability contracts do not cover all StandardCapability enum values." }
        val deploy = org.flowlang.standard.StandardCapabilityContracts.requireContract(org.flowlang.intent.StandardCapability.DEPLOY)
        require(deploy.loweringStrategy.isNotBlank()) { "DEPLOY contract must declare lowering strategy." }
    }

    private fun checkIntentDesignReport(): ConformanceCheck = runCheck("intent.design-report") {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
        val report = IntentDesignAnalyzer(registry).analyze(intent)
        require(report.capabilities.isNotEmpty()) { "Design report must list capabilities." }
        require(report.requiredSystems.isNotEmpty()) { "Design report must list required systems." }
        require(report.standardVersion == FlowStandardVersions.FLOW_STANDARD_VERSION) { "Design report must carry standard version." }
    }

    private fun checkCorePackagesDoNotImportJackson(): ConformanceCheck = runCheck("architecture.core-no-jackson-imports") {
        val src = File(rootDir, "src/main/kotlin/org/flowlang")
        val coreDirs = listOf("ast", "planner", "capabilities", "standard", "core")
        val offenders = coreDirs.flatMap { dir ->
            File(src, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
                val text = file.readText()
                text.contains("com.fasterxml.jackson") || text.contains("YAMLFactory") || text.contains("ObjectMapper")
            }.map { it.relativeTo(rootDir).path }.toList()
        }
        require(offenders.isEmpty()) { "Core packages must not import Jackson/YAML: ${offenders.joinToString()}" }
    }

    private fun checkAiNormalizationDeployment(): ConformanceCheck = runCheck("ai.normalization.deployment") {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure."))
        require(response.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability.name == "DEPLOY" }) { "Normalizer must synthesize DEPLOY capability." }
        require(response.normalizedIntent.policies.any { it.type.name == "APPROVAL" }) { "Normalizer must infer approval policy." }
        require(response.normalizedIntent.failure.rollback) { "Normalizer must infer rollback failure policy." }
        val validation = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
        require(validation.valid) { validation.issues.joinToString { it.code + ": " + it.message } }
    }

    private fun checkAiNormalizationFullPipelineValidation(): ConformanceCheck = runCheck("ai.normalization.full-pipeline-validation") {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure."))
        IntentCapabilityValidator(registry).validate(response.normalizedIntent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(response.normalizedIntent)
        val validation = FlowValidator(registry).validate(ast)
        require(validation.valid) { "Normalized deployment produced invalid Flow AST: " + validation.issues.joinToString { it.code + ": " + it.message } }
        val plan = FlowPlanner(registry).plan(ast)
        require(plan.nodes.isNotEmpty()) { "Normalized deployment produced an empty execution plan." }
    }

    private fun checkAiNormalizationMissingApplicationQuestion(): ConformanceCheck = runCheck("ai.normalization.required-question") {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy to Kubernetes with health verification."))
        require(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.application.name" }) {
            "Normalizer must ask a required clarification when deployment application is missing."
        }
    }

    private fun checkPublicOutputsMatchSchemas(): ConformanceCheck = runCheck("schemas.public-outputs") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val schemaDir = File(rootDir, "schemas")
        val intent = artifacts.intent as org.flowlang.intent.IntentDocument
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(intent), Json.mapper.readTree(File(schemaDir, "intent.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(IntentDecisionAnalyzer(registry).analyze(intent)), Json.mapper.readTree(File(schemaDir, "intent-decision-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(IntentCapabilityValidator(registry).validate(intent)), Json.mapper.readTree(File(schemaDir, "intent-capability-validation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.ast), Json.mapper.readTree(File(schemaDir, "ast.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.validation), Json.mapper.readTree(File(schemaDir, "validation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.plan), Json.mapper.readTree(File(schemaDir, "execution-plan.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ExecutionPlanCanonicalizer.canonicalize(artifacts.plan)), Json.mapper.readTree(File(schemaDir, "execution-plan.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.compatibility), Json.mapper.readTree(File(schemaDir, "compatibility-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(CompatibilityAnalyzer(targets).negotiate(artifacts.plan)), Json.mapper.readTree(File(schemaDir, "capability-negotiation-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ExecutionReadinessAnalyzer(targets).analyze(artifacts.plan, "jenkins")), Json.mapper.readTree(File(schemaDir, "execution-readiness-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(TargetSelectionAnalyzer(targets).analyze(artifacts.plan)), Json.mapper.readTree(File(schemaDir, "target-selection-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(TargetDecisionTraceAnalyzer(targets).analyze(artifacts.plan, requestedTarget = "jenkins")), Json.mapper.readTree(File(schemaDir, "target-decision-trace-report.schema.json")))
        val adapterContract = TargetAdapterContractAnalyzer(targets).analyze(artifacts.plan, "jenkins")
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(adapterContract), Json.mapper.readTree(File(schemaDir, "target-adapter-contract.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(adapterContract.diagnostics), Json.mapper.readTree(File(schemaDir, "adapter-diagnostics.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardDiagnosticCatalog.report()), Json.mapper.readTree(File(schemaDir, "standard-diagnostic-catalog.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceDiagnosticCoverage(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "diagnostic-coverage-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceArtifactIntegrity(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "artifact-integrity-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceContractIndex(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "standard-contract-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceReleaseProfile()), Json.mapper.readTree(File(schemaDir, "standard-release-profile.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceEvidence(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "artifact-evidence-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceCompliance(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "standard-compliance-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceFreeze(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "standard-freeze-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.compatibilityPolicy()), Json.mapper.readTree(File(schemaDir, "compatibility-policy.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.referenceCorpus()), Json.mapper.readTree(File(schemaDir, "reference-corpus-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.negativeCorpus()), Json.mapper.readTree(File(schemaDir, "negative-conformance-corpus.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(PublicStandardDraft.targetConformanceProfile()), Json.mapper.readTree(File(schemaDir, "target-conformance-profile.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.publicSurface()), Json.mapper.readTree(File(schemaDir, "public-standard-surface.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.compatibilityMigrationPolicy()), Json.mapper.readTree(File(schemaDir, "compatibility-migration-policy.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.referenceIntentCorpus()), Json.mapper.readTree(File(schemaDir, "reference-intent-corpus.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.targetSemanticsMatrix()), Json.mapper.readTree(File(schemaDir, "target-semantics-matrix.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.standardExportBundle()), Json.mapper.readTree(File(schemaDir, "standard-export-bundle.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.conformanceLevels()), Json.mapper.readTree(File(schemaDir, "conformance-levels.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardSurface.standardExportManifest()), Json.mapper.readTree(File(schemaDir, "standard-export-manifest.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ConformanceVectorIndexBuilder(rootDir).build()), Json.mapper.readTree(File(schemaDir, "conformance-vector-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(StandardBundleVerifier().verify(standardBundleFixture())), Json.mapper.readTree(File(schemaDir, "standard-bundle-verification.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceStandardIndex(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "standard-index.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceConformanceSuite()), Json.mapper.readTree(File(schemaDir, "conformance-suite.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceDraft(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "flow-standard-draft.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(referenceArtifactBundle(artifacts, "jenkins")), Json.mapper.readTree(File(schemaDir, "flow-artifact-bundle.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ConformanceManifestBuilder(rootDir).build(ConformanceSummary(listOf(ConformanceCheck("schemas.public-outputs", true))))), Json.mapper.readTree(File(schemaDir, "conformance-manifest.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(ModuleContractAnalyzer.analyze(registry)), Json.mapper.readTree(File(schemaDir, "capability-module-contract-report.schema.json")))
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(artifacts.manifest), Json.mapper.readTree(File(schemaDir, "target-manifest.schema.json")))
    }

    private fun checkModulesDoNotOwnTargetRendering(): ConformanceCheck = runCheck("architecture.modules-do-not-own-target-rendering") {
        val moduleDir = File(rootDir, "modules")
        val offenders = moduleDir.walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val forbidden = Regex("^\\s{4}(runtime|generators):\\s*$").containsMatchIn(line) ||
                        Regex("^\\s{8}template:\\s*").containsMatchIn(line) ||
                        Regex("^\\s{8}entrypoint:\\s*").containsMatchIn(line)
                    if (forbidden) "${file.relativeTo(rootDir).path}:${index + 1}:${line.trim()}" else null
                }
            }.toList()
        require(offenders.isEmpty()) {
            "Module descriptors must describe capabilities/effects/safety, not runtime hooks or renderer templates: ${offenders.joinToString()}"
        }
    }

    private fun checkScenarioPackCatalog(): ConformanceCheck = runCheck("scenario-packs.catalog") {
        val ids = ScenarioPackRegistry.packs.map { it.definition.id }.toSet()
        require(ids.containsAll(setOf("deployment", "build-test", "backup-restore", "data-sync", "secret-rotation", "provision", "cleanup", "incident-runbook"))) {
            "Scenario pack registry must cover deployment, build-test, backup/restore, data-sync, secret-rotation, provision, cleanup and incident-runbook."
        }
        require(ids.containsAll(setOf("database-migration", "certificate-renewal", "kubernetes-maintenance"))) {
            "Scenario pack registry must cover v0.3.1 database-migration, certificate-renewal and kubernetes-maintenance packs."
        }
        require(ScenarioPackRegistry.packs.all { it.definition.capabilities.isNotEmpty() }) { "Each scenario pack must declare standard capabilities." }
    }

    private fun checkScenarioPackNormalizationFullPipelines(): ConformanceCheck = runCheck("scenario-packs.normalization.full-pipelines") {
        val samples = listOf(
            "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure.",
            "Back up PostgreSQL database every night, keep backups for 14 days and notify the team on failure.",
            "Synchronize customers from CRM to warehouse, transform fields and notify on failure.",
            "Rotate secret payment-api-token, verify the service and notify the security team.",
            "Run database migration for orders database to version 2026.06, create backup first, require approval, rollback on failure and verify schema after migration.",
            "Renew certificate api-gateway in namespace edge and verify service gateway after renewal.",
            "Run Kubernetes maintenance in namespace payments, drain nodes with approval, dry-run first and verify pods are healthy.",
            "Run incident runbook for api outage, collect diagnostics, notify the team and verify recovery."
        )
        samples.forEach { sample ->
            val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest(sample))
            val intentReport = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
            if (response.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED }) {
                intentReport.assertValid()
                val ast = IntentToAstPlanner(registry).plan(response.normalizedIntent)
                val validation = FlowValidator(registry).validate(ast)
                require(validation.valid) { "Scenario pack produced invalid Flow AST for '$sample': " + validation.issues.joinToString { it.code + ": " + it.message } }
                val plan = FlowPlanner(registry).plan(ast)
                require(plan.nodes.isNotEmpty()) { "Scenario pack produced empty execution plan for '$sample'." }
            }
        }
    }


    private fun checkScenarioPackRegressionCoverage(): ConformanceCheck = runCheck("scenario-packs.regression-coverage") {
        val normalizer = ScenarioPackIntentNormalizer()
        val build = normalizer.normalize(AiIntentRequest("Build and test the repo."))
        require(build.report.classification.type == "build-test") { "Build/test request must not fall back to custom." }
        val provision = normalizer.normalize(AiIntentRequest("Provision infrastructure with terraform."))
        require(provision.report.classification.type == "provision") { "Provisioning request must not fall back to custom." }
        val cleanup = normalizer.normalize(AiIntentRequest("Cleanup old docker images."))
        require(cleanup.report.classification.type == "cleanup") { "Cleanup request must not fall back to custom." }
        require(cleanup.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "safety.cleanup.retention" }) { "Cleanup without retention must ask for required safety clarification." }
        require(!IntentCapabilityValidator(registry).validate(cleanup.normalizedIntent).valid) { "Cleanup without retention/safety must block lowering." }
        val cleanupWithRetention = normalizer.normalize(AiIntentRequest("Cleanup docker images older than 14 days."))
        require(cleanupWithRetention.report.classification.type == "cleanup") { "Cleanup with retention must still use cleanup pack." }
        IntentCapabilityValidator(registry).validate(cleanupWithRetention.normalizedIntent).assertValid()
        val rollback = normalizer.normalize(AiIntentRequest("Rollback the release."))
        require(rollback.report.classification.type != "deployment") { "Rollback-only request must not synthesize a full deployment." }
        val missing = normalizer.normalize(AiIntentRequest("Synchronize customer data and notify on failure."))
        require(missing.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED }) { "Missing data-sync source/destination must be a required clarification." }
        require(!IntentCapabilityValidator(registry).validate(missing.normalizedIntent).valid) { "Required clarifications must block lowering by default." }
    }

    private fun checkV031ScenarioPackVectors(): ConformanceCheck = runCheck("v0.3.1.scenario-packs") {
        val normalizer = ScenarioPackIntentNormalizer()
        val database = normalizer.normalize(AiIntentRequest("Run database migration for orders database to version 2026.06, create backup first, require approval, rollback on failure and verify schema after migration."))
        require(database.report.classification.type == "database-migration") { "Database migration request must select database-migration pack." }
        require(database.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability.name == "DATABASE_MIGRATE" }) { "Database migration pack must emit DATABASE_MIGRATE." }
        IntentCapabilityValidator(registry).validate(database.normalizedIntent).assertValid()

        val cert = normalizer.normalize(AiIntentRequest("Renew certificate api-gateway in namespace edge and verify service gateway after renewal."))
        require(cert.report.classification.type == "certificate-renewal") { "Certificate request must select certificate-renewal pack." }
        require(cert.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability.name == "CERTIFICATE_RENEW" }) { "Certificate pack must emit CERTIFICATE_RENEW." }
        IntentCapabilityValidator(registry).validate(cert.normalizedIntent).assertValid()

        val k8s = normalizer.normalize(AiIntentRequest("Run Kubernetes maintenance in namespace payments, drain nodes with approval, dry-run first and verify pods are healthy."))
        require(k8s.report.classification.type == "kubernetes-maintenance") { "Kubernetes request must select kubernetes-maintenance pack." }
        require(k8s.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability.name == "KUBERNETES_MAINTENANCE" }) { "Kubernetes maintenance pack must emit KUBERNETES_MAINTENANCE." }
        IntentCapabilityValidator(registry).validate(k8s.normalizedIntent).assertValid()

        val missingDb = normalizer.normalize(AiIntentRequest("Run database migration tomorrow."))
        require(missingDb.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.database" }) { "Missing database must be a required clarification." }
        require(!IntentCapabilityValidator(registry).validate(missingDb.normalizedIntent).valid) { "Missing database must block lowering." }
    }

    private fun checkV031CapabilityNegotiationReport(): ConformanceCheck = runCheck("v0.3.1.capability-negotiation") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val negotiation = CompatibilityAnalyzer(targets).negotiate(artifacts.plan, strict = false)
        require(negotiation.targets.isNotEmpty()) { "Negotiation report must include targets." }
        require(negotiation.targets.any { it.target == "jenkins" }) { "Negotiation report must include Jenkins." }
        require(negotiation.targets.any { it.target == "tekton" && it.unsupported.contains("approval.manual") }) {
            "Negotiation report must explain Tekton manual approval gap."
        }
        require(negotiation.requiredCapabilities.isNotEmpty()) { "Negotiation report must list required capabilities." }
    }

    private fun checkV036ExecutionPlanPortability(): ConformanceCheck = runCheck("v0.3.6.execution-plan-portability") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val negotiation = CompatibilityAnalyzer(targets).negotiate(artifacts.plan, strict = false)
        require(negotiation.portabilityScore in 0.0..1.0) { "Portability score must be normalized to 0.0..1.0." }
        require(negotiation.targets.all { it.portabilityScore in 0.0..1.0 }) { "Every target must expose a normalized portability score." }
        require(negotiation.portableCapabilities.isNotEmpty()) { "Report must identify capabilities portable across all registered targets." }
        require(negotiation.targetSpecificCapabilities.contains("approval.manual")) { "Manual approval must be marked target-specific when some targets only partially support or reject it." }
        require(negotiation.blockingPortabilityIssues.any { it.target == "tekton" && it.capability == "approval.manual" }) {
            "Report must expose Tekton manual approval as a blocking portability issue."
        }
        require(negotiation.requiredWorkarounds.any { it.target == "github-actions" && it.capability == "approval.manual" }) {
            "Report must expose GitHub Actions manual approval as a target-specific workaround."
        }
    }

    private fun checkV037ExecutionReadinessReport(): ConformanceCheck = runCheck("v0.3.7.execution-readiness") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val compatibility = CompatibilityAnalyzer(targets)
        val analyzer = ExecutionReadinessAnalyzer(targets)

        val preliminaryJenkins = analyzer.analyze(artifacts.plan, "jenkins", strict = false)
        require(preliminaryJenkins.readiness == ExecutionReadinessStatus.READY) { "Jenkins capability readiness should pass before manifest evaluation." }
        require(!preliminaryJenkins.productionReady) { "Capability readiness alone must not claim production readiness." }
        require(!preliminaryJenkins.readinessEvidenceAvailable) { "Preliminary readiness must expose missing artifact evidence." }
        val jenkins = TargetCompatibilityReadinessAnalyzer.reconcile(preliminaryJenkins, artifacts.manifest)
        require(jenkins.readiness == ExecutionReadinessStatus.DEGRADED) { "Reference Jenkins manifest must be review-only." }
        require(!jenkins.productionReady && !jenkins.executable) { "Review-only Jenkins manifest must not be production-ready." }
        require(jenkins.readinessEvidenceAvailable) { "Concrete Jenkins readiness must carry manifest evidence." }

        val githubManifest = GitHubActionsManifestGenerator().generate(
            artifacts.plan,
            compatibility.analyze(artifacts.plan, "github-actions", strict = false)
        )
        val github = TargetCompatibilityReadinessAnalyzer.reconcile(
            analyzer.analyze(artifacts.plan, "github-actions", strict = false),
            githubManifest
        )
        require(github.readiness == ExecutionReadinessStatus.DEGRADED) { "GitHub Actions reference manifest must remain degraded." }
        require(!github.productionReady && !github.executable) { "GitHub Actions review artifact must not be production-ready." }

        val tektonManifest = TektonManifestGenerator().generate(
            artifacts.plan,
            compatibility.analyze(artifacts.plan, "tekton", strict = false)
        )
        val tekton = TargetCompatibilityReadinessAnalyzer.reconcile(
            analyzer.analyze(artifacts.plan, "tekton", strict = false),
            tektonManifest
        )
        require(tekton.readiness == ExecutionReadinessStatus.BLOCKED) { "Tekton must remain blocked by effective compatibility." }
        require(!tekton.generationAllowed && !tekton.productionReady) { "Blocked Tekton target must not allow executable generation." }
    }

    private fun checkV038TargetSelectionReport(): ConformanceCheck = runCheck("v0.3.8.target-selection") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val compatibility = CompatibilityAnalyzer(targets)
        val preliminary = TargetSelectionAnalyzer(targets).analyze(artifacts.plan, strict = false)
        require(preliminary.recommendedTarget.isEmpty()) { "Capability-only selection must not recommend a target." }
        val manifests = listOf(
            artifacts.manifest,
            GitHubActionsManifestGenerator().generate(artifacts.plan, compatibility.analyze(artifacts.plan, "github-actions")),
            TektonManifestGenerator().generate(artifacts.plan, compatibility.analyze(artifacts.plan, "tekton"))
        )
        val manifestTargets = manifests.map { it.target }.toSet()
        val report = TargetCompatibilityReadinessAnalyzer.reconcile(preliminary, manifests)
        require(report.candidates.size == targets.size) { "Target selection must evaluate every registered target." }
        require(report.recommendedTarget.isEmpty()) { "Reference deployment has no executable target recommendation." }
        require(report.readyTargets.isEmpty()) { "Review-only reference manifests must not be classified as ready." }
        require(report.degradedTargets.containsAll(listOf("jenkins", "github-actions"))) { "Jenkins and GitHub Actions must be classified as review-only degraded targets." }
        require(report.blockedTargets.contains("tekton")) { "Tekton must be classified as blocked." }
        require(report.candidates.filter { it.target in manifestTargets }.all { it.readinessEvidenceAvailable }) {
            "Candidates with concrete manifests must carry readiness evidence."
        }
        require(report.candidates.filter { it.target !in manifestTargets }.none { it.readinessEvidenceAvailable }) {
            "Candidates without concrete manifests must remain explicitly unevaluated."
        }
        require(report.candidates.map { it.rank } == (1..report.candidates.size).toList()) { "Candidate ranks must be contiguous." }
    }

    private fun checkV039TargetDecisionTraceReport(): ConformanceCheck = runCheck("v0.3.9.target-decision-trace") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val compatibility = CompatibilityAnalyzer(targets)
        val manifests = listOf(
            artifacts.manifest,
            GitHubActionsManifestGenerator().generate(artifacts.plan, compatibility.analyze(artifacts.plan, "github-actions")),
            TektonManifestGenerator().generate(artifacts.plan, compatibility.analyze(artifacts.plan, "tekton"))
        )
        val negotiation = TargetCompatibilityReadinessAnalyzer.reconcile(
            compatibility.negotiate(artifacts.plan, strict = false),
            manifests
        )
        val selection = TargetCompatibilityReadinessAnalyzer.reconcile(
            TargetSelectionAnalyzer(targets).analyze(artifacts.plan, strict = false),
            manifests
        )
        val report = TargetDecisionTraceAnalyzer(targets).analyze(
            plan = artifacts.plan,
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
        require(report.targetExplanations.any { it.target == "github-actions" && it.decision == TargetDecisionKind.DEGRADED }) {
            "Trace must explain GitHub Actions as degraded."
        }
        require(report.targetExplanations.any { it.target == "tekton" && it.decision == TargetDecisionKind.BLOCKED }) {
            "Trace must explain Tekton as blocked."
        }
    }

    private fun checkV0310PublicArtifactBundle(): ConformanceCheck = runCheck("v0.3.10.public-artifact-bundle") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val bundle = FlowArtifactBundleAnalyzer().intentBundle(
            flowName = artifacts.plan.flowName,
            target = "jenkins",
            strict = false,
            hasManifest = true,
            renderedArtifact = "Jenkinsfile"
        )
        require(bundle.artifacts.isNotEmpty()) { "Artifact bundle must list public artifacts." }
        require(bundle.pipeline.first() == "standard-version.txt") { "Bundle pipeline must start with standard-version.txt." }
        require(bundle.pipeline.last() == "flow-artifact-bundle.json") { "Bundle pipeline must end with flow-artifact-bundle.json." }
        require(bundle.requiredArtifacts.contains("execution-plan.json")) { "Bundle must require execution-plan.json." }
        require(bundle.requiredArtifacts.contains("target-decision-trace-report.json")) { "Bundle must require target-decision-trace-report.json." }
        require(bundle.requiredArtifacts.contains("flow-artifact-bundle.json")) { "Bundle must require itself as the public bundle manifest." }
        require(bundle.optionalArtifacts.contains("target-manifest.json")) { "Target manifest must be listed as optional because not every target has a renderer." }
        require(bundle.optionalArtifacts.contains("Jenkinsfile")) { "Rendered vendor artifact must be listed as optional." }
        require(bundle.artifacts.map { it.pipelineIndex } == (1..bundle.artifacts.size).toList()) { "Artifact pipeline indexes must be contiguous." }
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
        val artifacts = buildPipeline("jenkins", strict = false)
        val contract = TargetAdapterContractAnalyzer(targets).analyze(artifacts.plan, "jenkins", strict = false)
        require(contract.generationAllowed) { "Jenkins adapter contract should allow generation for the reference plan." }
        require(contract.allowedInputArtifacts.any { it.name == "execution-plan.json" }) { "Adapter contract must allow execution-plan.json." }
        require(contract.allowedInputArtifacts.none { it.name == "normalized-intent.json" }) { "Adapter contract must not allow normalized-intent.json as adapter input." }
        require(contract.forbiddenInputArtifacts.contains("normalized-intent.json")) { "Adapter contract must explicitly forbid intent reinterpretation." }
        require(contract.expectedOutputArtifacts.any { it.name == "target-manifest.json" }) { "Adapter contract must describe target manifest output." }
        require(contract.expectedOutputArtifacts.any { it.name == "adapter-diagnostics.json" }) { "Adapter contract must describe diagnostics output." }
        require(contract.invariants.any { it.code == "ADAPTER_MUST_NOT_READ_INTENT" }) { "Adapter contract must include no-intent invariant." }
        require(contract.invariants.any { it.code == "ADAPTER_MUST_RESPECT_READINESS" }) { "Adapter contract must include readiness invariant." }

        val tekton = TargetAdapterContractAnalyzer(targets).analyze(artifacts.plan, "tekton", strict = false)
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

    private fun checkV0314DiagnosticCoverageReport(): ConformanceCheck = runCheck("v0.3.14.diagnostic-coverage-report") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val coverage = referenceDiagnosticCoverage(artifacts, "jenkins")
        require(coverage.status == "PASS") { "Reference pipeline must not emit diagnostic codes outside the standard catalog: ${coverage.unknownCodes.joinToString { it.code }}" }
        require(coverage.observedCodes.any { it.code == "ADAPTER_CONTRACT_READY" }) { "Coverage report must observe adapter diagnostics." }
        require(coverage.observedCodes.any { it.code == "ADAPTER_MUST_NOT_READ_INTENT" }) { "Coverage report must observe adapter contract invariants." }
        require(coverage.catalogCodes.contains("DIAGNOSTIC_CODE_UNKNOWN")) { "Coverage report must be anchored to the v0.3.14 diagnostic catalog." }
        require(coverage.unusedCatalogCodes.isNotEmpty()) { "Coverage report should expose catalog codes not observed by this specific pipeline." }
    }

    private fun checkV0315ArtifactIntegrityReport(): ConformanceCheck = runCheck("v0.3.15.artifact-integrity-report") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val integrity = referenceArtifactIntegrity(artifacts, "jenkins")
        require(integrity.status == "PASS") { "Reference artifact set must pass integrity checks: ${integrity.issues.joinToString { it.code }}" }
        require(integrity.requiredArtifactsExpected.contains("diagnostic-coverage-report.json")) { "Integrity report must include diagnostic coverage as a required artifact." }
        require(integrity.requiredArtifactsExpected.contains("artifact-integrity-report.json")) { "Integrity report must include itself as a public artifact." }
        require(integrity.missingRequiredArtifacts.isEmpty()) { "Reference artifact set must not miss required artifacts." }
        require(integrity.standardVersionMismatches.isEmpty()) { "Reference artifact set must not contain standardVersion mismatches." }
        require(integrity.diagnosticCoverageStatus == "PASS") { "Artifact integrity must consume diagnostic coverage status." }
    }

    private fun checkV0316StandardContractIndex(): ConformanceCheck = runCheck("v0.3.16.standard-contract-index") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val index = referenceContractIndex(artifacts, "jenkins")
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
        val artifacts = buildPipeline("jenkins", strict = false)
        val evidence = referenceEvidence(artifacts, "jenkins")
        require(evidence.missingEvidence.isEmpty()) { "Required derived artifacts must have evidence: ${evidence.missingEvidence.joinToString()}" }
        require(evidence.evidence.any { it.artifact == "standard-compliance-report.json" && it.producer == "StandardComplianceAnalyzer" }) {
            "Evidence report must identify standard compliance producer."
        }
        require(evidence.evidence.any { it.artifact == "standard-version.txt" && it.evidenceType == "source" }) {
            "Evidence report must identify source artifacts."
        }
    }

    private fun checkV0319StandardComplianceReport(): ConformanceCheck = runCheck("v0.3.19.standard-compliance-report") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val compliance = referenceCompliance(artifacts, "jenkins")
        require(compliance.status == "PASS") { "Reference public artifact set must pass compliance gates: ${compliance.failedGates.joinToString()}" }
        require(compliance.gates.any { it.id == "artifact-integrity.pass" && it.status == "PASS" }) { "Compliance must include artifact integrity gate." }
        require(compliance.gates.any { it.id == "contract-index.present" && it.status == "PASS" }) { "Compliance must include contract index gate." }
        require(compliance.gates.any { it.id == "bundle.contains-compliance" && it.status == "PASS" }) { "Compliance must verify bundle membership." }
    }

    private fun checkV0320StandardFreezeReport(): ConformanceCheck = runCheck("v0.3.20.standard-freeze-report") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val freeze = referenceFreeze(artifacts, "jenkins")
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
        val artifacts = buildPipeline("jenkins", strict = false)
        val draft = referenceDraft(artifacts, "jenkins")
        val index = referenceStandardIndex(artifacts, "jenkins")
        val suite = referenceConformanceSuite()
        require(draft.status == "PASS") { "Public standard draft must pass compliance." }
        require(draft.purpose.contains("Human/AI intent")) { "Draft must preserve Flow's original intent standardization purpose." }
        require(index.artifacts.contains("flow-standard-draft.json")) { "Standard index must include draft artifact." }
        require(suite.referenceCorpus == "reference-corpus-index.json") { "Conformance suite must point to reference corpus." }
        require(suite.negativeCorpus == "negative-conformance-corpus.json") { "Conformance suite must point to negative corpus." }
    }

    private fun checkV041SemanticCorrectnessHardening(): ConformanceCheck = runCheck("v0.4.1.semantic-correctness-hardening") {
        val normalizer = ScenarioPackIntentNormalizer()
        val requiredClarifications = listOf(
            normalizer.normalize(AiIntentRequest("Backup now.")) to "entities.backup.subject",
            normalizer.normalize(AiIntentRequest("Deploy now.")) to "entities.application.name",
            normalizer.normalize(AiIntentRequest("Run kubernetes maintenance daily.")) to "entities.kubernetes.scope",
            normalizer.normalize(AiIntentRequest("Sync from here to there.")) to "entities.source",
            normalizer.normalize(AiIntentRequest("Prune everything.")) to "entities.cleanup.resource"
        )
        requiredClarifications.forEach { (response, field) ->
            require(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == field }) {
                "Adversarial normalization must ask required clarification for $field."
            }
        }
        val secret = normalizer.normalize(AiIntentRequest("Rotate the api token now."))
        require(secret.report.classification.type == "secret-rotation") { "Token secret request must stay in secret-rotation pack." }
        require(secret.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.secret.name" }) {
            "Secret extractor should prefer the concrete token name before the noun over temporal adverbs after it."
        }
        val migration = normalizer.normalize(AiIntentRequest("Migrate the database now."))
        require(migration.report.classification.type == "database-migration") { "Tokenized matching must classify 'Migrate the database' as database-migration." }
        require(migration.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.database" }) {
            "Database migration without a concrete database must ask a required question."
        }
        val unsupportedGithub = runCatching {
            TargetExpressionTranslator.github(
                "environment matches 'prod.*'",
                listOf(TargetInput("environment")),
                targets.getValue("github-actions").expressionSupport
            )
        }
        require(unsupportedGithub.isFailure) { "Unsupported condition operators must not be translated to silent true/false." }
    }

    private fun checkV042StandardBoundary(): ConformanceCheck = runCheck("v0.4.2.standard-boundary-no-sdk-runtime") {
        require(!File(rootDir, "src/main/kotlin/org/flowlang/runtime").exists()) { "Active source must not expose an org.flowlang.runtime package." }
        require(!File(rootDir, "src/main/kotlin/org/flowlang/runtime/LocalRuntime.kt").exists()) { "LocalRuntime must not be part of the active standard path." }
        val bundle = FlowArtifactBundleAnalyzer().intentBundle("boundary", "jenkins", strict = false, hasManifest = true, renderedArtifact = "Jenkinsfile")
        require(bundle.requiredArtifacts.contains("target-conformance-profile.json")) { "Public bundle must contain target-conformance-profile.json." }
        require(!bundle.requiredArtifacts.contains("target-adapter-certification-profile.json")) { "Public bundle must not publish SDK-like certification artifact names." }
    }


    private fun checkV043ArchitectureGovernanceGuardrails(): ConformanceCheck = runCheck("v0.4.3.architecture-governance-guardrails") {
        val report = ArchitectureGovernanceAnalyzer(rootDir).analyze()
        require(report.status == "PASS") {
            "Architecture governance guardrails must pass: " + report.issues.joinToString { it.code + " at " + it.path + ": " + it.message }
        }
        val requiredPaths = report.files.map { it.path }.toSet()
        require(requiredPaths.contains("docs/ARCHITECTURE_CONSTITUTION.md")) { "Architecture constitution must be part of governance." }
        require(requiredPaths.contains("docs/adr/ADR_TEMPLATE.md")) { "ADR template must be part of governance." }
        require(requiredPaths.contains("standard/architecture/forbidden-directions.yaml")) { "Forbidden directions catalog must be part of governance." }
        require(requiredPaths.contains("standard/architecture/release-checklist.yaml")) { "Release checklist must be part of governance." }
        require(requiredPaths.contains("standard/architecture/drift-score.yaml")) { "Drift Score model must be part of governance." }
        require(report.forbiddenDirections.any { it.id == "runtime-executor" && it.documented }) { "Runtime executor drift must be forbidden." }
        require(report.forbiddenDirections.any { it.id == "sdk-framework" && it.documented }) { "SDK drift must be forbidden." }
        require(report.forbiddenDirections.any { it.id == "plugin-framework" && it.documented }) { "Plugin drift must be forbidden." }
        require(report.forbiddenDirections.any { it.id == "target-template-ownership" && it.documented }) { "Target template ownership drift must be forbidden." }
        require(report.driftScoreMinimum == 0) { "Governance must reject negative Drift Score proposals by default." }
    }

    private fun checkV032CanonicalExecutionPlan(): ConformanceCheck = runCheck("v0.3.2.execution-plan.canonical") {
        val artifacts = buildPipeline("jenkins", strict = false)
        val canonical = ExecutionPlanCanonicalizer.canonicalize(artifacts.plan)
        val nodes = canonical.nodes.flatMap { flattenCanonicalNode(it) }
        require(canonical.planVersion == FlowStandardVersions.EXECUTION_PLAN_VERSION) { "Canonical plan must carry current execution plan version." }
        require(nodes.isNotEmpty()) { "Canonical plan must contain nodes." }
        require(nodes.all { it.kind == it.kind.lowercase() }) { "Canonical node kinds must be lowercase: ${nodes.map { it.kind }.distinct().joinToString()}" }
        require(nodes.any { it.kind == "approval" }) { "Canonical plan must expose approval nodes as public lowercase kind." }
        require(canonical.requiredCapabilities.isNotEmpty()) { "Canonical plan must carry required capabilities." }
    }

    private fun checkV032SafetyPolicies(): ConformanceCheck = runCheck("v0.3.2.safety-policy-validation") {
        val normalizer = ScenarioPackIntentNormalizer()

        val cleanup = normalizer.normalize(AiIntentRequest("Cleanup old docker images."))
        val cleanupReport = IntentCapabilityValidator(registry).validate(cleanup.normalizedIntent)
        require(cleanup.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "safety.cleanup.retention" }) {
            "Cleanup without retention must ask for required safety clarification."
        }
        require(cleanupReport.issues.any { it.code == "SAFETY_CLEANUP_REQUIRES_RETENTION" }) {
            "Cleanup without retention must be blocked by safety validator."
        }

        val cleanupWithRetention = normalizer.normalize(AiIntentRequest("Cleanup docker images older than 14 days."))
        IntentCapabilityValidator(registry).validate(cleanupWithRetention.normalizedIntent).assertValid()

        val k8sWithoutDryRun = normalizer.normalize(AiIntentRequest("Run Kubernetes maintenance in namespace payments, drain nodes with approval and verify pods are healthy."))
        val k8sReport = IntentCapabilityValidator(registry).validate(k8sWithoutDryRun.normalizedIntent)
        require(k8sReport.issues.any { it.code == "SAFETY_REQUIRES_DRY_RUN" }) {
            "Kubernetes maintenance without dry-run must be blocked by safety validator."
        }
    }

    private fun checkV034CapabilityModuleContracts(): ConformanceCheck = runCheck("v0.3.4.capability-module-contracts") {
        val report = ModuleContractAnalyzer.analyze(registry)
        val errors = report.issues.filter { it.level == "error" }
        require(report.valid) { "Capability module contract report contains errors: ${errors.joinToString { it.code + " " + it.module + "." + (it.action ?: "") }}" }
        require(report.totals.modules >= 5) { "Expected loaded module descriptors." }
        require(report.totals.actions > 0) { "Capability module contract report must include actions." }
        require(report.modules.any { it.name == "docker" && it.actions.any { action -> action.name == "build" && action.outputs.contains("digest") } }) {
            "Docker build contract must expose image digest output."
        }
        require(report.modules.any { it.actions.any { action -> action.destructive && action.requiresSafety } }) {
            "At least one destructive action must require safety so adapters can enforce gates."
        }
        require(report.totals.targetImplicationActions > 0) {
            "At least one module action must expose target implications for capability negotiation."
        }
    }

    private fun checkV035IntentDecisionModel(): ConformanceCheck = runCheck("v0.3.5.intent-decision-model") {
        val normalizer = ScenarioPackIntentNormalizer()
        val analyzer = IntentDecisionAnalyzer(registry)

        val cleanup = analyzer.analyze(normalizer.normalize(AiIntentRequest("Cleanup old docker images.")).normalizedIntent)
        require(cleanup.missingDecisions.any { it.field == "safety.cleanup.retention" && it.blocksLowering }) {
            "Cleanup without retention must produce a blocking missing decision."
        }
        require(!cleanup.validForLowering) { "Cleanup without retention must not be valid for lowering in decision report." }

        val backup = analyzer.analyze(normalizer.normalize(AiIntentRequest("Back up PostgreSQL database every night, keep backups for 14 days and notify the team on failure.")).normalizedIntent)
        require(backup.missingDecisions.any { it.field == "schedule.timezone" && it.severity == "recommended" && !it.blocksLowering }) {
            "Backup schedule without timezone must produce a recommended missing decision."
        }

        val migration = analyzer.analyze(normalizer.normalize(AiIntentRequest("Run database migration for orders database to version 2026.06, require approval and verify schema after migration.")).normalizedIntent)
        require(migration.missingDecisions.any { it.field == "safety.backup" && it.blocksLowering }) {
            "Database migration without backup must produce a blocking backup decision."
        }

        val prodDeploy = IntentYamlLoader.loadText("""
            kind: FlowIntentDocument
            name: prod-deploy-without-approval
            workflows:
              - name: cd
                kind: DEPLOY
                steps:
                  - id: deploy
                    capability: DEPLOY
                    params:
                      namespace: prod
                      environment: prod
                      image: billing-api:1.0
        """.trimIndent())
        val prodReport = analyzer.analyze(prodDeploy)
        require(prodReport.safetyGates.any { it.policy == "forbidProductionWithoutApproval" && it.blocksLowering }) {
            "Production deploy without approval must produce a blocking safety gate."
        }
        require(prodReport.missingDecisions.any { it.field == "safety.approval" && it.blocksLowering }) {
            "Production deploy without approval must ask for approval decision."
        }
    }

    private fun flattenCanonicalNode(node: CanonicalPlanNode): List<CanonicalPlanNode> =
        listOf(node) +
            node.then.flatMap { flattenCanonicalNode(it) } +
            node.otherwise.flatMap { flattenCanonicalNode(it) } +
            node.body.flatMap { flattenCanonicalNode(it) } +
            node.errorHandler.flatMap { flattenCanonicalNode(it) } +
            node.errorCase.flatMap { flattenCanonicalNode(it) } +
            node.defaultSteps.flatMap { flattenCanonicalNode(it) } +
            node.branches.flatMap { branch -> branch.steps.flatMap { flattenCanonicalNode(it) } } +
            node.cases.flatMap { matchCase -> matchCase.steps.flatMap { flattenCanonicalNode(it) } }

    private fun pretty(value: Any): String = Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"

    private fun assertJsonSnapshotEquals(file: File, actual: Any) {
        require(file.isFile) { "Missing snapshot ${file.path}" }
        val expected = Json.mapper.readTree(file.readText())
        val got = Json.mapper.readTree(pretty(actual))
        require(expected == got) { "JSON snapshot mismatch for ${file.name}. Update snapshot only after reviewing generated semantics." }
    }

    private fun assertSnapshotEquals(file: File, actual: String) {
        require(file.isFile) { "Missing snapshot ${file.path}" }
        val expected = file.readText().trimEnd()
        val got = actual.trimEnd()
        require(expected == got) {
            "Snapshot mismatch for ${file.name}. Update snapshot only after reviewing generated semantics. " +
                "Actual output follows:\n--- ACTUAL ${file.name} ---\n$got\n--- END ACTUAL ${file.name} ---"
        }
    }

    private fun TargetManifest.allStepParams(): List<String> = jobs.flatMap { job ->
        job.steps.flatMap { it.allStepParams() }
    }

    private fun TargetStep.allStepParams(): List<String> = params.values.toList() + children.flatMap { it.allStepParams() }

    private fun checkV044AiProposalReview(): ConformanceCheck = runCheck("v0.4.4.ai-proposal-review") {
        val review = IntentProposalReview()
        val clean = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Build and test the orders service."))
        require(review.review(clean) is IntentProposalDecision.Accepted) { "A clean deterministic proposal must be accepted." }
        val migrate = IntentDocument(
            name = "schema-change",
            workflows = listOf(IntentWorkflow("migrate", IntentWorkflowKind.CUSTOM, listOf(
                IntentStep("migrate", StandardCapability.DATABASE_MIGRATE, params = mapOf("database" to IntentString("orders")))
            )))
        )
        val decision = review.review(migrate)
        require(decision is IntentProposalDecision.Rejected && decision.violations.any { it.code == "SAFETY_REQUIRES_BACKUP" }) {
            "A migration that omits a mandated backup must be rejected with SAFETY_REQUIRES_BACKUP, independently of the provider's report."
        }
    }

    private fun checkV044ConditionExpressionReadiness(): ConformanceCheck = runCheck("v0.4.4.condition-expression-readiness") {
        val plan = ExecutionPlan(
            flowName = "guarded",
            nodes = listOf(ConditionNode(
                id = "gate", condition = "count > 1",
                then = listOf(TaskNode(id = "guarded-task", module = "standard", action = "execute", target = "all"))
            ))
        )
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(plan, "tekton")
        require(readiness.readiness == ExecutionReadinessStatus.BLOCKED) { "An untranslatable Tekton guard must block readiness." }
        require(readiness.blockers.any { it.capability == "condition.expression" }) { "The blocker must identify condition.expression." }
        var refused = false
        try { CompatibilityAnalyzer(targets).analyze(plan, "tekton").assertAllowed() } catch (_: IllegalStateException) { refused = true }
        require(refused) { "The generation gate must refuse an untranslatable guard." }
    }

    private fun checkV044NoSilentConditionFallback(): ConformanceCheck = runCheck("v0.4.4.no-silent-condition-fallback") {
        val supported = "env == 'prod'"
        val unsupported = "count > 1"
        val github = targets.getValue("github-actions")
        val tekton = targets.getValue("tekton")
        fun gitHubTranslatorOk(condition: String): Boolean = try {
            TargetExpressionTranslator.github(condition, emptyList(), github.expressionSupport); true
        } catch (_: TargetExpressionTranslationException) { false }
        require((TargetExpressionSupport.unsupportedReason(github, supported) == null) == gitHubTranslatorOk(supported)) {
            "GitHub support model and translator disagree on a supported condition."
        }
        require((TargetExpressionSupport.unsupportedReason(github, unsupported) == null) == gitHubTranslatorOk(unsupported)) {
            "GitHub support model and translator disagree on an unsupported condition."
        }
        require(TargetExpressionSupport.unsupportedReason(tekton, unsupported) != null) {
            "The model must report that Tekton cannot express the condition."
        }
        require(TargetExpressionTranslator.tektonWhen(unsupported, emptyList(), tekton.expressionSupport) == null) {
            "Tekton must not silently translate an unsupported condition into a passing guard."
        }
    }

    private fun checkV044BehavioralGeneratorEquivalence(): ConformanceCheck = runCheck("v0.4.4.behavioral-generator-equivalence") {
        val plan = ExecutionPlan(
            flowName = "behavioral",
            nodes = listOf(
                TaskNode(id = "build", module = "ci", action = "build", target = "all"),
                TaskNode(id = "test", module = "ci", action = "test", target = "all", dependsOn = listOf("build")),
                ConditionNode(
                    id = "env-gate", condition = "env == 'prod'",
                    then = listOf(TaskNode(id = "deploy", module = "cd", action = "deploy", target = "all", dependsOn = listOf("test"))),
                    otherwise = listOf(TaskNode(id = "notify", module = "ops", action = "notify", target = "all", dependsOn = listOf("test")))
                )
            )
        )
        val expectedTasks = setOf("build", "test", "deploy", "notify")
        val generators = mapOf(
            "jenkins" to JenkinsManifestGenerator(),
            "github-actions" to GitHubActionsManifestGenerator(),
            "tekton" to TektonManifestGenerator()
        )
        generators.forEach { (target, generator) ->
            val manifest = generator.generate(plan, CompatibilityAnalyzer(targets).analyze(plan, target))
            val taskNames = mutableSetOf<String>()
            var deployGuard: String? = null
            fun walk(steps: List<TargetStep>, guard: String?) {
                steps.forEach { step ->
                    when {
                        step.type == "condition" -> walk(step.children, step.params["condition"])
                        step.children.isNotEmpty() -> walk(step.children, guard)
                        step.type == "action" -> {
                            taskNames += step.name
                            if (step.name == "deploy") deployGuard = guard
                        }
                    }
                }
            }
            manifest.jobs.forEach { job -> walk(job.steps, job.metadata["condition"]) }
            require(taskNames == expectedTasks) { "Target $target dropped or added tasks: $taskNames" }
            require(!deployGuard.isNullOrBlank()) { "Target $target dropped the guard on the conditional deploy task." }
        }
    }

    private fun checkV045StandardSurfaceFreeze(): ConformanceCheck = runCheck("v0.4.5.standard-surface-freeze") {
        val surface = StandardSurface.publicSurface()
        require(surface.status == "PASS") { "Public standard surface must pass." }
        require(surface.internalArtifacts.isEmpty()) { "Internal artifacts must not be part of the public surface." }
        require(surface.entries.isNotEmpty()) { "Public surface must declare entries." }
        // Reconcile the declared surface with reality: every declared schema file must actually exist,
        // so the freeze detects a removed, renamed or never-created schema instead of asserting a constant.
        surface.entries.forEach { entry ->
            require(File(rootDir, entry.schema).isFile) {
                "Public surface entry '${entry.artifact}' declares schema '${entry.schema}' which does not exist on disk."
            }
            require(entry.stability in setOf("stable", "draft", "experimental", "internal")) {
                "Public surface entry '${entry.artifact}' has an unknown stability '${entry.stability}'."
            }
        }
        val stable = surface.stableArtifacts.toSet()
        require("intent.schema.json" in stable) { "Intent schema must be in the stable public surface." }
        require("execution-plan.schema.json" in stable) { "Execution plan schema must be in the stable public surface." }
        require("public-standard-surface.json" in stable) { "Public surface report must declare itself." }
        require(surface.requiredChangeGates.contains("negative-conformance-review")) {
            "Public surface changes must require negative conformance review."
        }
    }

    private fun checkV046CompatibilityMigrationPolicy(): ConformanceCheck = runCheck("v0.4.6.compatibility-migration-policy") {
        val policy = StandardSurface.compatibilityMigrationPolicy()
        require(policy.status == "PASS") { "Compatibility and migration policy must pass." }
        require(policy.deprecationWindowMinorReleases >= 2) { "Deprecation policy must provide a minimum two minor release window." }
        require(policy.compatibilityRules.any { it.id == "minor.add-optional-field" && it.allowedInMinor }) {
            "Adding optional public fields must be allowed in a minor release."
        }
        require(policy.compatibilityRules.any { it.id == "breaking.remove-public-field" && !it.allowedInMinor && it.requiresMigrationNote }) {
            "Removing a public field must be treated as a breaking change with migration notes."
        }
        require(policy.migrationArtifacts.contains("CHANGELOG.md")) { "Migration policy must require changelog coverage." }
    }

    private fun checkV047ReferenceIntentCorpus(): ConformanceCheck = runCheck("v0.4.7.reference-intent-corpus") {
        val corpus = StandardSurface.referenceIntentCorpus()
        require(corpus.status == "PASS") { "Reference intent corpus must pass." }
        require(corpus.scenarios.isNotEmpty()) { "Reference corpus must contain scenarios." }
        require(corpus.negativeScenarioIds.isNotEmpty()) { "Reference corpus must keep negative (BLOCKED) scenarios." }
        val review = IntentProposalReview()
        corpus.scenarios.forEach { scenario ->
            // Replay each scenario through the real standard: normalize the input text, then run the
            // proposal-review gate. The corpus is verified against actual behavior, not asserted against itself.
            val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest(scenario.inputText))
            val intent = response.normalizedIntent
            val capabilities = intent.workflows.flatMap { it.steps }.map { it.capability.name }.toSet()
            val requiredClarifications = response.report.openQuestions
                .filter { it.severity == ClarificationSeverity.REQUIRED }
                .map { it.field }
                .toSet()
            val decision = review.review(intent)
            val rejectionCodes = (decision as? IntentProposalDecision.Rejected)?.violations?.map { it.code }?.toSet().orEmpty()
            val blocked = requiredClarifications.isNotEmpty() || decision is IntentProposalDecision.Rejected
            val actualStatus = if (blocked) "BLOCKED" else "ACCEPTED"
            require(actualStatus == scenario.expectedStatus) {
                "Reference scenario '${scenario.id}': pipeline produced $actualStatus, expected ${scenario.expectedStatus}."
            }
            require(capabilities.containsAll(scenario.expectedCapabilities)) {
                "Reference scenario '${scenario.id}': normalized intent is missing capabilities ${scenario.expectedCapabilities.toSet() - capabilities}."
            }
            require(requiredClarifications.containsAll(scenario.expectedRequiredClarifications)) {
                "Reference scenario '${scenario.id}': missing required clarifications ${scenario.expectedRequiredClarifications.toSet() - requiredClarifications}."
            }
            require(rejectionCodes.containsAll(scenario.expectedRejectionCodes)) {
                "Reference scenario '${scenario.id}': missing gate rejection codes ${scenario.expectedRejectionCodes.toSet() - rejectionCodes}."
            }
            scenario.expectedEntities.forEach { (key, value) ->
                require(response.report.entities[key] == value) {
                    "Reference scenario '${scenario.id}': expected entity '$key'='$value' but normalizer extracted '${response.report.entities[key]}'."
                }
            }
        }
    }

    private fun checkV048TargetSemanticsMatrix(): ConformanceCheck = runCheck("v0.4.8.target-semantics-matrix") {
        val matrix = StandardSurface.targetSemanticsMatrix()
        require(matrix.status == "PASS") { "Target semantics matrix must pass." }
        // Target ids must be real targets in the registry, not an independent hardcoded list.
        require(matrix.targetIds.all { it in targets.keys }) {
            "Target semantics matrix references targets absent from the registry: ${matrix.targetIds.filter { it !in targets.keys }}."
        }
        require(matrix.targetIds.containsAll(listOf("jenkins", "github-actions", "tekton"))) {
            "Target semantics matrix must cover Jenkins, GitHub Actions and Tekton."
        }
        val features = matrix.entries.map { it.feature }.toSet()
        require(features.containsAll(setOf("conditions", "approvals", "secrets", "artifacts", "parallelism", "rollback"))) {
            "Target semantics matrix is missing core portable features."
        }
        // The conditions row must agree with TargetExpressionSupport, the single source of truth for which
        // guard expressions a target can enforce natively, rather than a hand-maintained label.
        val referenceConditions = listOf("env == 'prod'", "stage != 'dev'", "count > 1", "name matches '^prod-'", "region in ['eu', 'us']")
        fun derivedConditionSupport(target: String): String {
            val capability = targets.getValue(target)
            val unsupported = referenceConditions.count { TargetExpressionSupport.unsupportedReason(capability, it) != null }
            return when {
                unsupported == 0 -> "native"
                unsupported < referenceConditions.size -> "partial"
                else -> "unsupported"
            }
        }
        val conditions = matrix.entries.first { it.feature == "conditions" }
        require(conditions.jenkins == derivedConditionSupport("jenkins")) {
            "Matrix Jenkins conditions '${conditions.jenkins}' disagrees with TargetExpressionSupport ('${derivedConditionSupport("jenkins")}')."
        }
        require(conditions.githubActions == derivedConditionSupport("github-actions")) {
            "Matrix GitHub Actions conditions '${conditions.githubActions}' disagrees with TargetExpressionSupport ('${derivedConditionSupport("github-actions")}')."
        }
        require(conditions.tekton == derivedConditionSupport("tekton")) {
            "Matrix Tekton conditions '${conditions.tekton}' disagrees with TargetExpressionSupport ('${derivedConditionSupport("tekton")}')."
        }
        require(conditions.requiredDiagnosticWhenUnsupported == "condition.expression") {
            "Unsupported condition expressions must surface the condition.expression diagnostic."
        }
        // Reference case (formerly the unsupported-target-condition corpus entry), verified in the layer
        // that owns target semantics: a guard expression Tekton cannot express natively must be reported
        // as unsupported, while a target that can express it natively must not be.
        val unsupportedReference = referenceConditions.firstOrNull {
            TargetExpressionSupport.unsupportedReason(targets.getValue("tekton"), it) != null
        }
        require(unsupportedReference != null) {
            "Tekton must report at least one unsupported reference guard expression."
        }
        require(TargetExpressionSupport.unsupportedReason(targets.getValue("jenkins"), unsupportedReference) == null) {
            "Jenkins must natively express the reference guard expression '$unsupportedReference'."
        }
    }

    private fun checkV049StandardExportBundle(): ConformanceCheck = runCheck("v0.4.9.standard-export-bundle") {
        val export = StandardSurface.standardExportBundle()
        val surface = StandardSurface.publicSurface()
        require(export.status == "PASS") { "Standard export bundle must pass." }
        require(export.command.startsWith("standard-export --out")) { "Standard export bundle must define a CLI export command." }
        require(export.command.contains(FlowStandardVersions.FLOW_STANDARD_VERSION)) {
            "Export command must target the current standard version."
        }
        // Every required directory must actually exist in the standard repository.
        export.requiredDirectories.forEach { dir ->
            require(File(rootDir, dir.trimEnd('/')).isDirectory) {
                "Standard export bundle requires directory '$dir' which does not exist."
            }
        }
        require(export.requiredDirectories.containsAll(listOf("docs/", "schemas/", "conformance/", "standard/", "targets/", "examples/"))) {
            "Standard export bundle must cover docs, schemas, conformance, standard data, targets and examples."
        }
        // The exported artifact set must equal the public surface's stable artifacts (single source of truth).
        require(export.requiredArtifacts.toSet() == surface.stableArtifacts.toSet()) {
            "Standard export bundle artifacts must match the public surface stable artifacts."
        }
        require(export.requiredFiles.contains("standard-index.json")) { "Standard export bundle must include standard-index.json." }
        require(export.requiredFiles.contains("public-standard-surface.json")) { "Standard export bundle must include public-standard-surface.json." }
        require(export.requiredFiles.contains("conformance-manifest.json")) { "Standard export bundle must include conformance-manifest.json for self-verification." }
        require(export.requiredArtifacts.contains("standard-export-bundle.json")) { "Standard export bundle must declare itself as a required artifact." }
    }

    private fun checkV050StandardExportManifest(): ConformanceCheck = runCheck("v0.5.0.standard-export-manifest") {
        val manifest = StandardSurface.standardExportManifest()
        val surface = StandardSurface.publicSurface()
        require(manifest.status == "PASS") { "Standard export manifest must pass." }
        require(manifest.candidate == "public-standard-candidate") { "Manifest must declare the public standard candidate." }
        manifest.requiredDocuments.forEach { path ->
            require(File(rootDir, path).isFile) { "Standard export manifest requires missing document '$path'." }
        }
        require(manifest.requiredDocuments.contains("docs/IMPLEMENTER_GUIDE.md")) {
            "Manifest must include the implementer guide."
        }
        require(manifest.requiredJsonArtifacts.containsAll(listOf("conformance-levels.json", "standard-export-manifest.json"))) {
            "Manifest must include v0.5.0 public JSON artifacts."
        }
        require(manifest.requiredSchemas.toSet().containsAll(surface.entries.filter { it.stability == "stable" }.map { it.schema })) {
            "Manifest schemas must cover the stable public surface."
        }
        require(manifest.nonGoals.any { it.contains("No runtime executor") }) {
            "Manifest must explicitly reject runtime-executor direction."
        }
    }

    private fun checkV053StandardBundleVerifier(): ConformanceCheck = runCheck("v0.5.3.standard-bundle-verifier") {
        val manifest = StandardSurface.standardExportManifest()
        val releaseProfile = StandardReleaseProfile.report()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val requiredGate = "v0.5.3.standard-bundle-verifier"

        require(activeStandardAtLeast(0, 5)) {
            "Standard bundle verifier must carry standardVersion 0.5.3 or later."
        }
        require(versionAtLeast(manifest.manifestVersion, 1, 3)) {
            "Standard export manifest must expose manifestVersion 1.3 for verifier metadata."
        }
        require(manifest.selfVerificationCommands.any { it.contains("standard-verify") }) {
            "Self-verification commands must include the standard-verify command."
        }
        require(candidate.requiredChecks.contains(requiredGate)) {
            "The standard-candidate conformance level must require the standard bundle verifier gate."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "The release profile must require the standard bundle verifier gate."
        }

        val validBundle = standardBundleFixture()
        val passReport = StandardBundleVerifier().verify(validBundle)
        require(passReport.status == "PASS") {
            "A complete standard bundle fixture must pass verification: ${passReport.checks.filter { it.status != "PASS" }.joinToString { it.id }}"
        }

        File(validBundle, "docs/IMPLEMENTER_GUIDE.md").delete()
        val failReport = StandardBundleVerifier().verify(validBundle)
        require(failReport.status == "FAIL") {
            "Verifier must fail when a required document is missing."
        }
        require(failReport.missingRequiredDocuments.contains("docs/IMPLEMENTER_GUIDE.md")) {
            "Verifier must report the missing required document."
        }
    }

    private fun checkV054DataDrivenConformanceIndex(runnerChecks: List<String>): ConformanceCheck = runCheck("v0.5.4.data-driven-conformance-index") {
        val releaseProfile = StandardReleaseProfile.report()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val requiredGate = "v0.5.4.data-driven-conformance-index"
        val index = ConformanceVectorIndexBuilder(rootDir).build(
            runnerChecks = runnerChecks,
            releaseProfileChecks = releaseProfile.requiredConformanceChecks
        )

        require(activeStandardAtLeast(0, 5, 4)) {
            "Data-driven conformance index must carry standardVersion 0.5.4 or later."
        }
        require(StandardSurface.publicSurface().stableArtifacts.contains("conformance-vector-index.json")) {
            "Conformance vector index must be part of the stable public surface."
        }
        require(StandardSurface.standardExportBundle().requiredFiles.contains("conformance-vector-index.json")) {
            "Standard export bundle must include conformance-vector-index.json."
        }
        require(candidate.requiredChecks.contains(requiredGate)) {
            "The standard-candidate conformance level must require the data-driven vector index gate."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "The release profile must require the data-driven vector index gate."
        }
        require(index.status == "PASS") {
            "Conformance vector index must pass: missingRequired=${index.vectorsMissingRequiredCheck}, missingRunner=${index.checksMissingFromRunner}, missingReleaseVectors=${index.releaseProfileChecksMissingVector}"
        }
        require(index.vectorCount > 0) { "Conformance vector index must include public vector files." }
        require(index.entries.all { it.hasRequiredCheck }) { "Every public vector must declare requiredCheck or legacy name." }
        require(index.requiredChecksFromVectors.contains(requiredGate)) {
            "The data-driven vector index gate must be represented by a public vector."
        }
    }

    private fun checkV061IntentCorpusExpansion(): ConformanceCheck = runCheck("v0.6.1.intent-corpus-expansion") {
        val corpus = StandardSurface.referenceIntentCorpus()
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.1.intent-corpus-expansion"
        val requiredScenarios = setOf(
            "deploy-with-approval-and-rollback",
            "database-migration-with-backup",
            "certificate-renewal-with-window",
            "secret-rotation-with-audit",
            "kubernetes-maintenance-dry-run",
            "cleanup-with-retention",
            "rollback-with-verification",
            "portable-build-test-deploy",
            "database-migration-without-backup",
            "kubernetes-maintenance-without-dry-run"
        )
        val scenarioIds = corpus.scenarios.map { it.id }.toSet()

        require(activeStandardAtLeast(0, 6, 1)) {
            "Intent corpus expansion must carry standardVersion 0.6.1 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the intent corpus expansion gate."
        }
        require(scenarioIds.containsAll(requiredScenarios)) {
            "Reference intent corpus is missing required scenarios: ${requiredScenarios - scenarioIds}"
        }
        require(corpus.scenarios.count { it.expectedStatus == "ACCEPTED" } >= 10) {
            "Reference intent corpus must include at least ten accepted scenarios."
        }
        require(corpus.negativeScenarioIds.containsAll(listOf("database-migration-without-backup", "kubernetes-maintenance-without-dry-run", "prod-deploy-without-approval"))) {
            "Reference intent corpus must include safety-blocked negative scenarios."
        }
    }

    private fun checkV062RequiredClarificationContract(): ConformanceCheck = runCheck("v0.6.2.required-clarification-contract") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.2.required-clarification-contract"
        val rules = StandardSurface.requiredClarificationContract()
        val corpus = StandardSurface.referenceIntentCorpus()
        val requiredFields = rules.filter { it.blocking }.map { it.field }.toSet()
        val corpusClarifications = corpus.scenarios.flatMap { it.expectedRequiredClarifications }.toSet()

        require(activeStandardAtLeast(0, 6, 2)) {
            "Required clarification contract must carry standardVersion 0.6.2 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the required-clarification contract gate."
        }
        require(rules.all { it.blocking }) {
            "Every required clarification rule in the contract must be blocking."
        }
        require(requiredFields.containsAll(listOf("entities.application.name", "entities.environment", "safety.backup", "safety.cleanup.retention", "approval.owner", "safety.maintenance.window", "entities.secret.name", "entities.certificate"))) {
            "Required clarification contract is missing one or more critical fields."
        }
        require(corpusClarifications.containsAll(requiredFields - "safety.backup")) {
            "Reference intent corpus must contain blocked examples for required clarification fields: ${(requiredFields - "safety.backup") - corpusClarifications}"
        }
        require(corpus.scenarios.any { it.expectedRejectionCodes.contains("SAFETY_REQUIRES_BACKUP") }) {
            "Backup requirement may be represented as a safety rejection when migration intent is explicit."
        }
    }

    private fun checkV063SafetyPolicyMatrix(): ConformanceCheck = runCheck("v0.6.3.safety-policy-matrix") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.3.safety-policy-matrix"
        val matrix = StandardSurface.safetyPolicyMatrix()
        val negativeCases = PublicStandardDraft.negativeCorpus().cases
        val diagnostics = negativeCases.map { it.expectedDiagnostic }.toSet()

        require(activeStandardAtLeast(0, 6, 3)) {
            "Safety policy matrix must carry standardVersion 0.6.3 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the safety-policy matrix gate."
        }
        require(matrix.map { it.capability }.toSet().containsAll(setOf("DATABASE_MIGRATE", "CLEANUP", "KUBERNETES_MAINTENANCE", "SECRET_ROTATE", "DEPLOY", "CERTIFICATE_RENEW"))) {
            "Safety policy matrix must cover destructive and high-risk standard capabilities."
        }
        require(matrix.all { it.requiredMitigations.isNotEmpty() && it.blockingDiagnostic.isNotBlank() }) {
            "Every safety policy matrix entry must specify mitigations and a blocking diagnostic."
        }
        require(diagnostics.containsAll(matrix.map { it.blockingDiagnostic }.toSet())) {
            "Negative conformance corpus must cover every safety matrix blocking diagnostic: ${matrix.map { it.blockingDiagnostic }.toSet() - diagnostics}"
        }
    }

    private fun checkV064TargetSemanticsNegativeCorpus(): ConformanceCheck = runCheck("v0.6.4.target-semantics-negative-corpus") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.4.target-semantics-negative-corpus"
        val matrix = StandardSurface.targetSemanticsMatrix()
        val features = matrix.entries.associateBy { it.feature }
        val negativeDiagnostics = PublicStandardDraft.negativeCorpus().cases.map { it.expectedDiagnostic }.toSet()

        require(activeStandardAtLeast(0, 6, 4)) {
            "Target semantics negative corpus must carry standardVersion 0.6.4 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the target-semantics negative corpus gate."
        }
        require(features["strict-manual-approval"]?.requiredDiagnosticWhenUnsupported == "approval.strict") {
            "Target semantics matrix must explicitly model strict manual approval portability."
        }
        require(features["unsupported-condition-fallback"]?.requiredDiagnosticWhenUnsupported == "condition.expression") {
            "Target semantics matrix must reject silent unsupported-condition fallback."
        }
        require(features["rollback-portability"]?.requiredDiagnosticWhenUnsupported == "rollback.capability") {
            "Target semantics matrix must model rollback portability."
        }
        require(negativeDiagnostics.containsAll(setOf("approval.strict", "condition.expression", "rollback.capability"))) {
            "Negative conformance corpus must cover target semantics degradation/blocking diagnostics."
        }
    }

    private fun checkV065ExecutionPlanSemanticInvariants(): ConformanceCheck = runCheck("v0.6.5.execution-plan-semantic-invariants") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.5.execution-plan-semantic-invariants"
        val invariants = StandardSurface.executionPlanSemanticInvariants()
        val artifacts = buildPipeline("jenkins", strict = false)
        val plan = artifacts.plan
        val canonical = ExecutionPlanCanonicalizer.canonicalize(plan)
        val canonicalNodes = flattenCanonical(canonical.nodes)
        val ids = canonicalNodes.map { it.id }
        val dependencies = canonicalNodes.flatMap { node -> node.dependencies.map { node.id to it } }
        val targetSyntax = listOf("Jenkinsfile", "github-actions.yml", "tekton-pipeline.yaml", "pipeline {", "kind: Pipeline")

        require(activeStandardAtLeast(0, 6, 5)) {
            "Execution plan semantic invariants must carry standardVersion 0.6.5 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the execution-plan semantic invariants gate."
        }
        require(invariants.map { it.id }.toSet().containsAll(setOf("plan.node-ids-unique", "plan.dependencies-known", "plan.no-cycles", "plan.no-target-specific-leakage"))) {
            "Execution plan semantic invariant catalog is incomplete."
        }
        require(ids.size == ids.toSet().size) {
            "Canonical execution-plan node ids must be unique."
        }
        require(dependencies.all { (_, dep) -> dep in ids.toSet() }) {
            "Canonical execution-plan dependencies must reference known nodes: ${dependencies.filterNot { (_, dep) -> dep in ids.toSet() }}"
        }
        require(!hasDependencyCycle(dependencies)) {
            "Canonical execution-plan dependencies must not contain cycles."
        }
        require(targetSyntax.none { canonical.toString().contains(it) }) {
            "Canonical execution plan must not contain rendered target syntax."
        }
    }

    private fun checkV066AiInputTrustBoundary(): ConformanceCheck = runCheck("v0.6.6.ai-input-trust-boundary") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.6.ai-input-trust-boundary"
        val rules = StandardSurface.aiInputTrustBoundary()
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy to Kubernetes with health verification."))
        var blockedByRequiredClarification = false
        try {
            response.assertUsableForLowering()
        } catch (ignored: IllegalStateException) {
            blockedByRequiredClarification = true
        }

        require(activeStandardAtLeast(0, 6, 6)) {
            "AI input trust boundary must carry standardVersion 0.6.6 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the AI input trust-boundary gate."
        }
        require(rules.all { it.blocking }) {
            "AI trust-boundary rules must be blocking when violated."
        }
        require(rules.map { it.id }.containsAll(listOf("ai.proposal-only", "ai.required-clarification-blocks", "ai.no-critical-defaults", "ai.validation-before-plan", "ai.target-syntax-after-plan", "ai.audit-trace"))) {
            "AI trust-boundary rule set is incomplete."
        }
        require(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED }) {
            "Incomplete AI/user intent must produce required clarification questions."
        }
        require(blockedByRequiredClarification) {
            "AI response with required clarification must be unusable for lowering."
        }
        require(response.report.guardrails.any { it.contains("normalized into IntentDocument") }) {
            "AI normalization report must expose the intent-normalization trust boundary."
        }
    }

    private fun checkV067StandardExampleBundle(): ConformanceCheck = runCheck("v0.6.7.standard-example-bundle") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.7.standard-example-bundle"
        val examples = StandardSurface.standardExampleBundle()
        val corpusById = StandardSurface.referenceIntentCorpus().scenarios.associateBy { it.id }

        require(activeStandardAtLeast(0, 6, 7)) {
            "Standard example bundle must carry standardVersion 0.6.7 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the standard-example bundle gate."
        }
        require(examples.size >= 5) {
            "Standard example bundle must contain the five golden examples."
        }
        require(examples.all { it.intentScenarioId in corpusById }) {
            "Every standard example must reference an existing reference intent scenario."
        }
        require(examples.any { it.expectedStatus == "ACCEPTED" } && examples.any { it.expectedStatus == "BLOCKED" }) {
            "Standard example bundle must include both accepted and blocked examples."
        }
        require(examples.all { "execution-plan.json" in it.expectedArtifacts && "intent-decision-report.json" in it.expectedArtifacts }) {
            "Every standard example must cite core public artifacts."
        }
    }

    private fun checkV068CompatibilityPromise(): ConformanceCheck = runCheck("v0.6.8.compatibility-promise") {
        val releaseProfile = StandardReleaseProfile.report()
        val requiredGate = "v0.6.8.compatibility-promise"
        val promises = StandardSurface.compatibilityPromiseRules()
        val policy = StandardSurface.compatibilityMigrationPolicy()

        require(activeStandardAtLeast(0, 6, 8)) {
            "Compatibility promise must carry standardVersion 0.6.8 or later."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "Release profile must require the compatibility-promise gate."
        }
        require(promises.map { it.category }.toSet().containsAll(setOf("patch", "minor", "major", "all"))) {
            "Compatibility promise must cover patch, minor, major and all-release rules."
        }
        require(promises.all { it.requiresConformanceVector }) {
            "Every compatibility promise rule must require conformance-vector coverage."
        }
        require(policy.compatibilityRules.any { it.id == "minor.add-optional-field" && it.allowedInMinor }) {
            "Compatibility migration policy must keep additive minor changes allowed."
        }
        require(policy.compatibilityRules.any { it.category == "breaking" && !it.allowedInMinor && it.requiresMigrationNote }) {
            "Compatibility migration policy must keep breaking changes out of minor releases."
        }
    }

    private fun checkV070ReferenceCorpusExecutionHarness(): ConformanceCheck = runCheck("v0.7.0.reference-corpus-execution-harness") {
        val releaseProfile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val harnessContract = StandardSurface.referenceCorpusExecutionHarness()
        val requiredGate = "v0.7.0.reference-corpus-execution-harness"
        val harnessReport = ReferenceCorpusExecutionHarness(registry).execute()
        val index = ConformanceVectorIndexBuilder(rootDir).build(
            runnerChecks = allRunnerChecksForVectorIndex(),
            releaseProfileChecks = releaseProfile.requiredConformanceChecks
        )

        require(activeStandardAtLeast(0, 7, 0)) {
            "Reference corpus execution harness must carry standardVersion 0.7.0 or later."
        }
        require(harnessContract.status == "PASS") {
            "Reference corpus execution harness contract must pass."
        }
        require(harnessContract.replayStages.contains("normalize scenario input with ScenarioPackIntentNormalizer")) {
            "Harness contract must state that scenarios are replayed through the real normalizer."
        }
        require(harnessContract.assertions.any { it.id == "corpus.accepted-lowers" && it.blocking }) {
            "Harness contract must require accepted scenarios to lower."
        }
        require(harnessContract.assertions.any { it.id == "corpus.blocked-stays-blocked" && it.blocking }) {
            "Harness contract must require blocked scenarios to remain blocked."
        }
        require(requiredGate in releaseProfile.requiredConformanceChecks) {
            "Release profile must require the reference corpus execution harness gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest must publish the reference corpus execution harness gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate level must require the reference corpus execution harness gate."
        }
        require(harnessReport.status == "PASS") {
            harnessReport.failures.joinToString(prefix = "Reference corpus execution failed: ", separator = "; ")
        }
        require(harnessReport.scenarioCount == StandardSurface.referenceIntentCorpus().scenarios.size) {
            "Harness must execute every reference corpus scenario."
        }
        require(harnessReport.lowerableAcceptedCount == harnessReport.acceptedCount) {
            "Every accepted reference scenario must lower successfully."
        }
        require(harnessReport.blockedLoweringCount == harnessReport.blockedCount) {
            "Every blocked reference scenario must be non-lowerable."
        }
        require(index.status == "PASS") {
            "Reference corpus harness requires vector index closure: missingRequired=${index.vectorsMissingRequiredCheck}, missingRunner=${index.checksMissingFromRunner}, missingReleaseVectors=${index.releaseProfileChecksMissingVector}"
        }
        require(requiredGate in index.requiredChecksFromVectors) {
            "The v0.7.0 reference corpus execution harness gate must be represented by a public vector."
        }
    }

    private fun checkV071ArchitectureDebtCleanupAndDriftEnforcement(): ConformanceCheck = runCheck("v0.7.1.architecture-debt-cleanup-and-drift-enforcement") {
        val requiredGate = "v0.7.1.architecture-debt-cleanup-and-drift-enforcement"
        val releaseProfile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val governance = ArchitectureGovernanceAnalyzer(rootDir).analyze()
        val aliasInvariants = StandardSurface.publicContractAliasInvariants()

        require(activeStandardAtLeast(0, 7, 1)) {
            "Architecture debt cleanup and drift enforcement must carry standardVersion 0.7.1 or later."
        }
        require(requiredGate in releaseProfile.requiredConformanceChecks) {
            "Release profile must require the architecture-debt cleanup gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest must publish the architecture-debt cleanup gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate level must require the architecture-debt cleanup gate."
        }
        require(governance.status == "PASS") {
            "Architecture governance must pass: ${governance.issues.joinToString { it.code + ":" + it.path }}"
        }
        require(governance.driftScore.status == "PASS" && governance.driftScore.finalScore >= governance.driftScore.minimumScore) {
            "Drift score must be enforced and non-negative: ${governance.driftScore.finalScore}."
        }
        require(governance.driftScore.scoringMode == "negative-signal-only") {
            "Drift score must not use existing baseline artifacts as a positive floor."
        }
        require(governance.driftScore.positiveSignals.any { it.id == "conformance-coverage" && it.present && it.score == 0 }) {
            "Drift score may report baseline conformance coverage but must not credit it into the score."
        }
        require(governance.driftScore.negativeSignals.none { it.id == "report-without-validation-purpose" && it.present }) {
            "Report budget rule must reject public reports without schema, role or validation gate."
        }
        require(governance.reportBudget.status == "PASS" && governance.reportBudget.publicArtifactsChecked >= 10) {
            "Report budget must actively check the public standard surface."
        }

        val classifiedIds = releaseProfile.gateClassifications.map { it.id }.toSet()
        require(classifiedIds == releaseProfile.requiredConformanceChecks.toSet()) {
            "Every release-profile check must have a gate classification."
        }
        require(releaseProfile.behaviorSafetyNormalizationChecks.contains("v0.7.0.reference-corpus-execution-harness")) {
            "Reference corpus execution harness must count as behavioral coverage."
        }
        require(requiredGate in releaseProfile.governanceChecks) {
            "The v0.7.1 gate must be classified as governance."
        }
        require(releaseProfile.registryConsistencyChecks.isEmpty()) {
            "Registry-consistency gates must be collapsed into StandardModel projection coherence, not kept as active release gates."
        }
        require(governance.reportBudget.registryConsistencyChecks == 0) {
            "Report budget must report zero registry-consistency gates after the collapse."
        }
        require(StandardModel.wellFormednessIssues(rootDir).isEmpty()) {
            "StandardModel must be well formed: ${StandardModel.wellFormednessIssues(rootDir).joinToString()}"
        }

        require(aliasInvariants.size >= 4 && aliasInvariants.all { it.blocking }) {
            "Public compatibility aliases must be documented as blocking invariants."
        }
        val task = TaskNode(id = "deploy", module = "kubernetes", action = "deploy", target = "cluster", dependsOn = listOf("build"))
        val approval = org.flowlang.planner.ApprovalNode(id = "approve", dependsOn = listOf("test"))
        require(task.dependencies == task.dependsOn) { "TaskNode.dependencies must stay synchronized with dependsOn." }
        require(approval.dependencies == approval.dependsOn) { "ApprovalNode.dependencies must stay synchronized with dependsOn." }
        val normalized = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Renew certificate api-tls during the Sunday maintenance window."))
        require(normalized.report.extractedEntities == normalized.report.entities) {
            "NormalizationReport.extractedEntities must stay synchronized with entities."
        }
        require(normalized.report.confidenceByArea["overall"] == normalized.report.confidence.overall) {
            "NormalizationReport.confidenceByArea must stay synchronized with confidence."
        }
        require(!File(rootDir, "src/main/kotlin/org/flowlang/validator/FlowValidator.kt").readText().contains("isPlainString(")) {
            "Dead private FlowValidator.isPlainString helper must not remain in active source."
        }
        val adr = File(rootDir, "docs/adr/ADR-0001-ast-data-orchestration-boundary.md")
        require(adr.isFile && adr.readText().contains("does not support arbitrary general-purpose computation")) {
            "AST data/orchestration boundary must be documented by an ADR before adding more language-like nodes."
        }
    }

    private fun checkV073StandardModelProjectionCoherence(): ConformanceCheck = runCheck("v0.7.3.standard-model-projection-coherence") {
        val requiredGate = "v0.7.3.standard-model-projection-coherence"
        val profile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val surface = StandardSurface.publicSurface()

        require(activeStandardAtLeast(0, 7, 3)) {
            "Standard-model projection coherence must carry standardVersion 0.7.3 or later."
        }
        require(requiredGate in profile.requiredConformanceChecks) {
            "Release profile must require the standard-model projection coherence gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest must publish the standard-model projection coherence gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate level must require the standard-model projection coherence gate."
        }
        require(StandardModel.wellFormednessIssues(rootDir).isEmpty()) {
            "StandardModel must be well formed: ${StandardModel.wellFormednessIssues(rootDir).joinToString()}"
        }
        require(StandardModel.releaseProfileCheckIds() == profile.requiredConformanceChecks) {
            "Release profile must be projected from StandardModel."
        }
        require(StandardModel.standardExportManifestCheckIds() == manifest.releaseGateChecks) {
            "Standard export manifest gates must be projected from StandardModel."
        }
        require(StandardModel.candidateCheckIds() == candidate.requiredChecks) {
            "Standard-candidate checks must be projected from StandardModel."
        }
        require(StandardModel.stableArtifacts().toSet() == surface.stableArtifacts.toSet()) {
            "Public surface stable artifacts must be projected from StandardModel."
        }
        require(StandardModel.registryConsistencyCheckIds().isEmpty()) {
            "Registry-consistency gates must not remain in the public release model after the collapse."
        }
    }

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
        // The exact v0.7.4-only delta invariant is covered by the frozen
        // standard-model-baseline-v0.7.4 tests. Keeping a runtime branch for an
        // inactive active standard version only made the current runner harder to audit.
        require(delta.registryConsistencyCheckGrowth == 0) {
            "Architecture delta must not reintroduce registry-consistency gates."
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
            "Purpose coverage ratio must carry standardVersion 0.7.5 or later."
        }
        require(requiredGate in profile.requiredConformanceChecks) {
            "Release profile must require the purpose coverage ratio gate."
        }
        require(requiredGate in manifest.releaseGateChecks) {
            "Standard export manifest must publish the purpose coverage ratio gate."
        }
        require(requiredGate in candidate.requiredChecks) {
            "Standard-candidate level must require the purpose coverage ratio gate."
        }
        require(coverage.status == "PASS") {
            "Purpose coverage must pass: ${coverage.issues.joinToString { it.code + ":" + it.subject }}"
        }
        require(coverage.referenceScenarioCount >= PurposeCoverageAnalyzer.minimumReferenceScenarios) {
            "Reference corpus must not shrink below the v0.7.5 minimum."
        }
        require(coverage.missingCapabilities.isEmpty()) {
            "Reference corpus misses required purpose capabilities: ${coverage.missingCapabilities.joinToString()}."
        }
        require(coverage.missingBlockedRiskCapabilities.isEmpty()) {
            "Blocked corpus misses risk-sensitive capabilities: ${coverage.missingBlockedRiskCapabilities.joinToString()}."
        }
        require(coverage.automationPurposeRatio >= PurposeCoverageAnalyzer.minimumAutomationPurposeRatio) {
            "Automation-purpose ratio is too low: ${coverage.automationPurposeRatio}."
        }
        require(coverage.governanceRatio <= PurposeCoverageAnalyzer.maximumGovernanceRatio) {
            "Governance ratio is too high: ${coverage.governanceRatio}."
        }
        require(delta.status == "PASS") {
            "v0.7.5 architecture delta must pass: ${delta.issues.joinToString { it.code + ":" + it.subject }}"
        }
        require(delta.previousVersion == "0.7.4") {
            "v0.7.5 delta baseline must be v0.7.4."
        }
        require(delta.addedChecks == listOf(requiredGate)) {
            "v0.7.5 must add only the purpose coverage ratio gate, not unrelated surface area: ${delta.addedChecks}."
        }
        require(delta.stablePublicArtifactGrowth == 0) {
            "v0.7.5 must not grow public stable artifacts; it adds purpose coverage measurement."
        }
        require(delta.registryConsistencyCheckGrowth == 0) {
            "Purpose coverage must not reintroduce registry-consistency gates."
        }
    }

    private fun standardBundleFixture(): File {
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

    private fun activeStandardAtLeast(requiredMajor: Int, requiredMinor: Int): Boolean {
        val parts = FlowStandardVersions.FLOW_STANDARD_VERSION.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor)
    }

    private fun activeStandardAtLeast(requiredMajor: Int, requiredMinor: Int, requiredPatch: Int): Boolean =
        versionAtLeast(FlowStandardVersions.FLOW_STANDARD_VERSION, requiredMajor, requiredMinor, requiredPatch)

    private fun versionAtLeast(value: String, requiredMajor: Int, requiredMinor: Int): Boolean {
        val parts = value.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor)
    }

    private fun versionAtLeast(value: String, requiredMajor: Int, requiredMinor: Int, requiredPatch: Int): Boolean {
        val parts = value.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major > requiredMajor ||
            (major == requiredMajor && minor > requiredMinor) ||
            (major == requiredMajor && minor == requiredMinor && patch >= requiredPatch)
    }

    private fun postVectorIndexChecks(): List<String> = listOf(
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
        ConformanceQualityGateNames.CORE_CONTRACT_CHECK,
        ConformanceQualityGateNames.SCENARIO_PACK_QUALITY
    )

    private fun allRunnerChecksForVectorIndex(): List<String> =
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

    private fun flattenCanonical(nodes: List<CanonicalPlanNode>): List<CanonicalPlanNode> =
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

    private fun hasDependencyCycle(edges: List<Pair<String, String>>): Boolean {
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

    private fun runCheck(name: String, body: () -> Unit): ConformanceCheck = try {
        body()
        ConformanceCheck(name, true)
    } catch (t: Throwable) {
        ConformanceCheck(name, false, t.message ?: t::class.simpleName.orEmpty())
    }


    private fun ConformanceCheck.assertPassed() {
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
    val rendered: String
)
