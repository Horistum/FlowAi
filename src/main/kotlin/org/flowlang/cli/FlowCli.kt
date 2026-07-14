package org.flowlang.cli

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.StandardComplianceAnalyzer
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.adapters.contract.TargetAdapterContractAnalyzer
import org.flowlang.ai.normalization.AiIntentContext
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.NormalizationMode
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.generators.manifest.*
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceManifestBuilder
import org.flowlang.conformance.ConformanceVectorIndexBuilder
import org.flowlang.conformance.ReferenceSnapshotBundleGenerator
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDecisionAnalyzer
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentDesignAnalyzer
import org.flowlang.standard.StandardIntentCatalog
import org.flowlang.standard.StandardDiagnosticCatalog
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.DiagnosticCoverageReport
import org.flowlang.standard.ObservedDiagnosticCode
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.preview.PlanPreview
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.validator.FlowValidator
import java.io.File

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "intent" -> { runIntentCommand(args.drop(1)); return }
        "normalize" -> { runNormalizeCommand(args.drop(1)); return }
        "conformance" -> { runConformanceCommand(args.drop(1)); return }
        "reference-snapshot" -> { runReferenceSnapshotCommand(args.drop(1)); return }
        "catalog" -> { runCatalogCommand(args.drop(1)); return }
        "diagnostics" -> { runDiagnosticsCommand(args.drop(1)); return }
        "release-profile" -> { runReleaseProfileCommand(args.drop(1)); return }
        "standard-draft" -> { runStandardDraftCommand(args.drop(1)); return }
        "standard-export" -> { runStandardExportCommand(args.drop(1)); return }
        "standard-verify" -> { runStandardVerifyCommand(args.drop(1)); return }
        "targets" -> { runTargetsCommand(); return }
        "modules" -> { runModulesCommand(); return }
        "scenarios" -> { runScenariosCommand(args.drop(1)); return }
        "scenario" -> { runScenarioCommand(args.drop(1)); return }
    }
    runFlowCommand(args.toList())
}

private fun registry(): ModuleRegistry {
    val modulesDir = File("modules")
    return if (modulesDir.isDirectory) ModuleRegistry.fromDirectory(modulesDir, includeDefaults = true) else ModuleRegistry()
}

private fun targetRegistry(): Map<String, org.flowlang.capabilities.TargetCapability> {
    val targetsDir = File("targets")
    val fromYaml = TargetRegistryYamlLoader.loadDirectory(targetsDir)
    require(fromYaml.isNotEmpty()) { "No target registry found under ${targetsDir.path}. Flow uses the versioned target registry as the single source of truth." }
    return fromYaml
}

private fun runFlowCommand(args: List<String>) {
    val source = args.firstOrNull() ?: "examples/hello.flow"
    val file = File(source)
    require(file.exists()) { "Flow file does not exist: $source" }

    val registry = registry()
    val parser = FlowParser()
    val ast = parser.parse(file)
    val validator = FlowValidator(registry)
    val report = validator.validate(ast)
    val plan = FlowPlanner(registry).plan(ast)
    val previewReport = PlanPreview().render(plan)
    val targets = targetRegistry()
    val compatibility = CompatibilityAnalyzer(targets)

    println("===== AST JSON =====")
    println(Json.mapper.writeValueAsString(ast))
    println("===== VALIDATION REPORT =====")
    println(Json.mapper.writeValueAsString(report))
    println("===== EXECUTION PLAN JSON =====")
    println(Json.mapper.writeValueAsString(plan))
    println("===== CANONICAL EXECUTION PLAN JSON =====")
    println(Json.mapper.writeValueAsString(ExecutionPlanCanonicalizer.canonicalize(plan)))
    println("===== PLAN PREVIEW (NO COMMAND EXECUTION) =====")
    println(Json.mapper.writeValueAsString(previewReport))
    println("===== TARGET COMPATIBILITY REPORTS =====")
    targets.keys.sorted().forEach { target ->
        println("--- $target ---")
        println(Json.mapper.writeValueAsString(compatibility.analyze(plan, target)))
    }
    println("===== TARGET CAPABILITY NEGOTIATION REPORT =====")
    println(Json.mapper.writeValueAsString(compatibility.negotiate(plan)))
    println("===== CANONICAL TARGET MANIFESTS =====")
    listOf("jenkins", "github-actions", "tekton").forEach { target ->
        val targetCompatibility = compatibility.analyze(plan, target)
        val manifest = TargetManifestGenerationPipeline.generate(plan, targetCompatibility)
        println("--- $target ---")
        println(Json.mapper.writeValueAsString(manifest))
    }
}

private fun runIntentCommand(args: List<String>) {
    val source = args.firstOrNull() ?: "examples/intent/build-test-deploy.intent.yaml"
    val target = parseTarget(args) ?: "jenkins"
    val strict = args.contains("--strict") || args.contains("--fail-on-unsupported")
    val render = args.contains("--render")
    val outDir = parseOutDir(args)
    val file = File(source)
    require(file.exists()) { "Intent file does not exist: $source" }

    val registry = registry()
    val intent = IntentYamlLoader.load(file)
    val designReport = IntentDesignAnalyzer(registry).analyze(intent)
    val decisionReport = IntentDecisionAnalyzer(registry).analyze(intent)
    val intentValidation = IntentCapabilityValidator(registry).validate(intent)
    println("===== INTENT DESIGN REPORT =====")
    val designReportJson: String = Json.mapper.writeValueAsString(designReport)
    println(designReportJson)
    println("===== INTENT DECISION REPORT =====")
    val decisionReportJson: String = Json.mapper.writeValueAsString(decisionReport)
    println(decisionReportJson)
    println("===== NORMALIZED INTENT JSON =====")
    val normalizedIntentJson: String = Json.mapper.writeValueAsString(intent)
    println(normalizedIntentJson)
    println("===== INTENT CAPABILITY VALIDATION REPORT =====")
    val intentValidationJson: String = Json.mapper.writeValueAsString(intentValidation)
    println(intentValidationJson)
    intentValidation.assertValid()

    val ast = IntentToAstPlanner(registry).plan(intent)
    val validation = FlowValidator(registry).validate(ast)
    val plan = FlowPlanner(registry).plan(ast)
    val targetCapabilities = targetRegistry()
    val compatibility = CompatibilityAnalyzer(targetCapabilities).analyze(plan, target, strict = strict)
    val negotiation = CompatibilityAnalyzer(targetCapabilities).negotiate(plan, strict = strict)
    val readiness = ExecutionReadinessAnalyzer(targetCapabilities).analyze(plan, target, strict = strict)
    val targetSelection = TargetSelectionAnalyzer(targetCapabilities).analyze(plan, strict = strict)
    val targetDecisionTrace = TargetDecisionTraceAnalyzer(targetCapabilities).analyze(plan, requestedTarget = target, strict = strict)
    val targetAdapterContract = TargetAdapterContractAnalyzer(targetCapabilities).analyze(plan, target, strict = strict)

    println("===== GENERATED FLOW AST JSON =====")
    val astJson: String = Json.mapper.writeValueAsString(ast)
    println(astJson)
    println("===== VALIDATION REPORT =====")
    val validationJson: String = Json.mapper.writeValueAsString(validation)
    println(validationJson)
    println("===== EXECUTION PLAN JSON =====")
    val planJson: String = Json.mapper.writeValueAsString(plan)
    println(planJson)
    println("===== CANONICAL EXECUTION PLAN JSON =====")
    val canonicalPlanJson: String = Json.mapper.writeValueAsString(ExecutionPlanCanonicalizer.canonicalize(plan))
    println(canonicalPlanJson)
    println("===== TARGET COMPATIBILITY REPORT: $target =====")
    val compatibilityJson: String = Json.mapper.writeValueAsString(compatibility)
    println(compatibilityJson)
    println("===== TARGET CAPABILITY NEGOTIATION REPORT =====")
    val negotiationJson: String = Json.mapper.writeValueAsString(negotiation)
    println(negotiationJson)
    println("===== EXECUTION READINESS REPORT =====")
    val readinessJson: String = Json.mapper.writeValueAsString(readiness)
    println(readinessJson)
    println("===== TARGET SELECTION REPORT =====")
    val targetSelectionJson: String = Json.mapper.writeValueAsString(targetSelection)
    println(targetSelectionJson)
    println("===== TARGET DECISION TRACE REPORT =====")
    val targetDecisionTraceJson: String = Json.mapper.writeValueAsString(targetDecisionTrace)
    println(targetDecisionTraceJson)
    println("===== TARGET ADAPTER CONTRACT =====")
    val targetAdapterContractJson: String = Json.mapper.writeValueAsString(targetAdapterContract)
    println(targetAdapterContractJson)
    println("===== ADAPTER DIAGNOSTICS =====")
    val adapterDiagnosticsJson: String = Json.mapper.writeValueAsString(targetAdapterContract.diagnostics)
    println(adapterDiagnosticsJson)
    val diagnosticCoverage = buildDiagnosticCoverageReport(
        intentValidation = intentValidation.issues.map { observedDiagnostic("intent-capability-validation-report.json", it.code, "intentValidation.issues", it.level) },
        validation = validation.issues.map { observedDiagnostic("validation-report.json", it.code, "validation.issues", it.level) },
        readiness = (readiness.blockers + readiness.warnings).map { observedDiagnostic("execution-readiness-report.json", it.code, "readiness.findings", it.severity.name.lowercase()) },
        adapterContract = targetAdapterContract.invariants.map { observedDiagnostic("target-adapter-contract.json", it.code, "adapterContract.invariants", it.severity.name.lowercase()) },
        adapterDiagnostics = targetAdapterContract.diagnostics.issues.map { observedDiagnostic("adapter-diagnostics.json", it.code, "adapterDiagnostics.issues", it.severity.name.lowercase()) }
    )
    val diagnosticCoverageJson: String = Json.mapper.writeValueAsString(diagnosticCoverage)
    println("===== DIAGNOSTIC COVERAGE REPORT =====")
    println(diagnosticCoverageJson)
    compatibility.assertAllowed(strict = strict)

    var manifest: TargetManifest? = null
    var rendered: Pair<String, String>? = null
    when (target) {
        "jenkins" -> {
            manifest = TargetManifestGenerationPipeline.generate(plan, compatibility)
            println("===== JENKINS TARGET MANIFEST =====")
            println(Json.mapper.writeValueAsString(manifest))
            rendered = "Jenkinsfile" to JenkinsManifestRenderer().render(manifest)
            if (render) {
                println("===== RENDERED JENKINSFILE =====")
                println(rendered.second)
            } else {
                println("===== JENKINS TARGET MANIFEST READY =====")
                println("Use --render to produce Jenkinsfile from the canonical target manifest.")
            }
        }
        "github-actions" -> {
            manifest = TargetManifestGenerationPipeline.generate(plan, compatibility)
            println("===== GITHUB ACTIONS TARGET MANIFEST =====")
            println(Json.mapper.writeValueAsString(manifest))
            rendered = "github-actions.yml" to GitHubActionsManifestRenderer().render(manifest)
            if (render) {
                println("===== RENDERED GITHUB ACTIONS WORKFLOW =====")
                println(rendered.second)
            } else {
                println("===== GITHUB ACTIONS TARGET MANIFEST READY =====")
                println("Use --render to produce GitHub Actions YAML from the canonical target manifest.")
            }
        }
        "tekton" -> {
            manifest = TargetManifestGenerationPipeline.generate(plan, compatibility)
            println("===== TEKTON TARGET MANIFEST =====")
            println(Json.mapper.writeValueAsString(manifest))
            rendered = "tekton-pipeline.yaml" to TektonManifestRenderer().render(manifest)
            if (render) {
                println("===== RENDERED TEKTON PIPELINE =====")
                println(rendered.second)
            } else {
                println("===== TEKTON MANIFEST READY =====")
                println("Use --render to produce a Tekton Pipeline YAML draft. Target support is partial and must be checked through the compatibility report.")
            }
        }
        else -> println("===== GENERATOR DRAFT =====\nNo generator is implemented yet for target '$target'. Compatibility report is still produced.")
    }

    if (outDir != null) {
        exportIntentArtifacts(
            outDir = File(outDir),
            normalizedIntentJson = normalizedIntentJson,
            intentDesignReportJson = designReportJson,
            intentDecisionReportJson = decisionReportJson,
            intentValidationJson = intentValidationJson,
            astJson = astJson,
            validationJson = validationJson,
            planJson = planJson,
            canonicalPlanJson = canonicalPlanJson,
            compatibilityJson = compatibilityJson,
            negotiationJson = negotiationJson,
            readinessJson = readinessJson,
            targetSelectionJson = targetSelectionJson,
            targetDecisionTraceJson = targetDecisionTraceJson,
            targetAdapterContractJson = targetAdapterContractJson,
            adapterDiagnosticsJson = adapterDiagnosticsJson,
            diagnosticCoverage = diagnosticCoverage,
            flowName = intent.name,
            target = target,
            strict = strict,
            manifest = manifest,
            rendered = rendered
        )
    }
}



private fun runReferenceSnapshotCommand(args: List<String>) {
    val source = parseOption(args, "--intent") ?: args.firstOrNull() ?: "examples/intent/build-test-deploy.intent.yaml"
    val out = parseOutDir(args) ?: "conformance/snapshots/build-test-deploy"
    val scenarioId = parseOption(args, "--scenario-id") ?: File(source).nameWithoutExtension.removeSuffix(".intent")
    val snapshot = ReferenceSnapshotBundleGenerator().generate(
        intentFile = File(source),
        outputDir = File(out),
        scenarioId = scenarioId
    )
    println("===== REFERENCE SNAPSHOT INDEX =====")
    println(Json.mapper.writeValueAsString(snapshot))
    println("===== EXPORTED REFERENCE SNAPSHOT =====")
    println(File(out).absolutePath)
}

private fun runNormalizeCommand(args: List<String>) {
    val text = collectNormalizeText(args)
    val target = parseTarget(args)
    val strict = args.contains("--strict") || args.contains("--fail-on-unsupported")
    val render = args.contains("--render")
    val lower = args.contains("--lower") || args.contains("--pipeline") || args.contains("--render")
    val outDir = parseOutDir(args)
    val mode = when {
        args.contains("--strict") -> NormalizationMode.STRICT
        args.contains("--explain") -> NormalizationMode.EXPLAIN
        args.contains("--repair") -> NormalizationMode.REPAIR
        else -> NormalizationMode.DRAFT
    }
    val context = AiIntentContext(
        target = target,
        defaultApplication = parseOption(args, "--app"),
        defaultEnvironment = parseOption(args, "--environment"),
        repositoryUrl = parseOption(args, "--repo"),
        notificationChannel = parseOption(args, "--channel")
    )
    val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest(text, context, mode))
    val normalizedIntentJson: String = Json.mapper.writeValueAsString(response.normalizedIntent)
    val normalizationReportJson: String = Json.mapper.writeValueAsString(response.report)
    val registry = registry()
    val decisionReportJson: String = Json.mapper.writeValueAsString(IntentDecisionAnalyzer(registry).analyze(response.normalizedIntent))
    println("===== AI INTENT NORMALIZATION REPORT =====")
    println(normalizationReportJson)
    println("===== NORMALIZED INTENT JSON =====")
    println(normalizedIntentJson)
    println("===== INTENT DECISION REPORT =====")
    println(decisionReportJson)

    if (strict) response.assertUsableForLowering()

    var intentDesignReportJson: String? = null
    var intentValidationJson: String? = null
    var astJson: String? = null
    var validationJson: String? = null
    var planJson: String? = null
    var canonicalPlanJson: String? = null
    var compatibilityJson: String? = null
    var negotiationJson: String? = null
    var readinessJson: String? = null
    var targetSelectionJson: String? = null
    var targetDecisionTraceJson: String? = null
    var targetAdapterContractJson: String? = null
    var adapterDiagnosticsJson: String? = null
    var diagnosticCoverage: DiagnosticCoverageReport? = null
    var manifest: TargetManifest? = null
    var rendered: Pair<String, String>? = null

    if (lower) {
        val intentValidation = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
        println("===== INTENT CAPABILITY VALIDATION REPORT =====")
        intentValidationJson = Json.mapper.writeValueAsString(intentValidation)
        println(intentValidationJson)

        // The standard, not the (possibly model-backed) provider, decides whether this proposal may be lowered.
        when (val proposalDecision = IntentProposalReview(registry).review(response)) {
            is IntentProposalDecision.Rejected -> {
                println("===== AI PROPOSAL REJECTED BY STANDARD =====")
                println(Json.mapper.writeValueAsString(proposalDecision.violations))
                error("AI proposal rejected by the standard before lowering: " + proposalDecision.violations.joinToString { it.code })
            }
            is IntentProposalDecision.Accepted -> Unit
        }
        intentValidation.assertValid()

        val designReport = IntentDesignAnalyzer(registry).analyze(response.normalizedIntent)
        intentDesignReportJson = Json.mapper.writeValueAsString(designReport)
        println("===== INTENT DESIGN REPORT =====")
        println(intentDesignReportJson)

        val ast = IntentToAstPlanner(registry).plan(response.normalizedIntent)
        val validation = FlowValidator(registry).validate(ast)
        val plan = FlowPlanner(registry).plan(ast)
        val actualTarget = target ?: "jenkins"
        val targetCapabilities = targetRegistry()
        val compatibility = CompatibilityAnalyzer(targetCapabilities).analyze(plan, actualTarget, strict = strict)
        val negotiation = CompatibilityAnalyzer(targetCapabilities).negotiate(plan, strict = strict)
        val readiness = ExecutionReadinessAnalyzer(targetCapabilities).analyze(plan, actualTarget, strict = strict)
        val targetSelection = TargetSelectionAnalyzer(targetCapabilities).analyze(plan, strict = strict)
        val targetDecisionTrace = TargetDecisionTraceAnalyzer(targetCapabilities).analyze(plan, requestedTarget = actualTarget, strict = strict)
        val targetAdapterContract = TargetAdapterContractAnalyzer(targetCapabilities).analyze(plan, actualTarget, strict = strict)
        astJson = Json.mapper.writeValueAsString(ast)
        validationJson = Json.mapper.writeValueAsString(validation)
        planJson = Json.mapper.writeValueAsString(plan)
        canonicalPlanJson = Json.mapper.writeValueAsString(ExecutionPlanCanonicalizer.canonicalize(plan))
        compatibilityJson = Json.mapper.writeValueAsString(compatibility)
        negotiationJson = Json.mapper.writeValueAsString(negotiation)
        readinessJson = Json.mapper.writeValueAsString(readiness)
        targetSelectionJson = Json.mapper.writeValueAsString(targetSelection)
        targetDecisionTraceJson = Json.mapper.writeValueAsString(targetDecisionTrace)
        targetAdapterContractJson = Json.mapper.writeValueAsString(targetAdapterContract)
        adapterDiagnosticsJson = Json.mapper.writeValueAsString(targetAdapterContract.diagnostics)
        diagnosticCoverage = buildDiagnosticCoverageReport(
            intentValidation = intentValidation.issues.map { observedDiagnostic("intent-capability-validation-report.json", it.code, "intentValidation.issues", it.level) },
            validation = validation.issues.map { observedDiagnostic("validation-report.json", it.code, "validation.issues", it.level) },
            readiness = (readiness.blockers + readiness.warnings).map { observedDiagnostic("execution-readiness-report.json", it.code, "readiness.findings", it.severity.name.lowercase()) },
            adapterContract = targetAdapterContract.invariants.map { observedDiagnostic("target-adapter-contract.json", it.code, "adapterContract.invariants", it.severity.name.lowercase()) },
            adapterDiagnostics = targetAdapterContract.diagnostics.issues.map { observedDiagnostic("adapter-diagnostics.json", it.code, "adapterDiagnostics.issues", it.severity.name.lowercase()) }
        )
        val diagnosticCoverageJson = Json.mapper.writeValueAsString(diagnosticCoverage)
        println("===== GENERATED FLOW AST JSON =====")
        println(astJson)
        println("===== VALIDATION REPORT =====")
        println(validationJson)
        println("===== EXECUTION PLAN JSON =====")
        println(planJson)
        println("===== CANONICAL EXECUTION PLAN JSON =====")
        println(canonicalPlanJson)
        println("===== TARGET COMPATIBILITY REPORT: $actualTarget =====")
        println(compatibilityJson)
        println("===== TARGET CAPABILITY NEGOTIATION REPORT =====")
        println(negotiationJson)
        println("===== EXECUTION READINESS REPORT =====")
        println(readinessJson)
        println("===== TARGET SELECTION REPORT =====")
        println(targetSelectionJson)
        println("===== TARGET DECISION TRACE REPORT =====")
        println(targetDecisionTraceJson)
        println("===== TARGET ADAPTER CONTRACT =====")
        println(targetAdapterContractJson)
        println("===== ADAPTER DIAGNOSTICS =====")
        println(adapterDiagnosticsJson)
        println("===== DIAGNOSTIC COVERAGE REPORT =====")
        println(diagnosticCoverageJson)
        compatibility.assertAllowed(strict = strict)

        when (actualTarget) {
            "jenkins" -> {
                manifest = TargetManifestGenerationPipeline.generate(plan, compatibility)
                rendered = "Jenkinsfile" to JenkinsManifestRenderer().render(manifest)
            }
            "github-actions" -> {
                manifest = TargetManifestGenerationPipeline.generate(plan, compatibility)
                rendered = "github-actions.yml" to GitHubActionsManifestRenderer().render(manifest)
            }
            "tekton" -> {
                manifest = TargetManifestGenerationPipeline.generate(plan, compatibility)
                rendered = "tekton-pipeline.yaml" to TektonManifestRenderer().render(manifest)
            }
        }
        if (manifest != null) {
            println("===== TARGET MANIFEST =====")
            println(Json.mapper.writeValueAsString(manifest))
        }
        if (render && rendered != null) {
            println("===== RENDERED TARGET OUTPUT =====")
            println(rendered.second)
        }
    }

    if (outDir != null) {
        val dir = File(outDir)
        dir.mkdirs()
        File(dir, "standard-version.txt").writeText(FlowStandardVersions.FLOW_STANDARD_VERSION + "\n")
        File(dir, "standard-diagnostic-catalog.json").writeText(Json.mapper.writeValueAsString(StandardDiagnosticCatalog.report()) + "\n")
        File(dir, "ai-normalization-report.json").writeText(normalizationReportJson + "\n")
        File(dir, "normalized-intent.json").writeText(normalizedIntentJson + "\n")
        File(dir, "intent-decision-report.json").writeText(decisionReportJson + "\n")
        intentDesignReportJson?.let { File(dir, "intent-design-report.json").writeText(it + "\n") }
        intentValidationJson?.let { File(dir, "intent-capability-validation-report.json").writeText(it + "\n") }
        astJson?.let { File(dir, "flow-ast.json").writeText(it + "\n") }
        validationJson?.let { File(dir, "validation-report.json").writeText(it + "\n") }
        planJson?.let { File(dir, "execution-plan.json").writeText(it + "\n") }
        canonicalPlanJson?.let { File(dir, "canonical-execution-plan.json").writeText(it + "\n") }
        compatibilityJson?.let { File(dir, "compatibility-report.json").writeText(it + "\n") }
        negotiationJson?.let { File(dir, "capability-negotiation-report.json").writeText(it + "\n") }
        readinessJson?.let { File(dir, "execution-readiness-report.json").writeText(it + "\n") }
        targetSelectionJson?.let { File(dir, "target-selection-report.json").writeText(it + "\n") }
        targetDecisionTraceJson?.let { File(dir, "target-decision-trace-report.json").writeText(it + "\n") }
        targetAdapterContractJson?.let { File(dir, "target-adapter-contract.json").writeText(it + "\n") }
        adapterDiagnosticsJson?.let { File(dir, "adapter-diagnostics.json").writeText(it + "\n") }
        diagnosticCoverage?.let { File(dir, "diagnostic-coverage-report.json").writeText(Json.mapper.writeValueAsString(it) + "\n") }
        manifest?.let { File(dir, "target-manifest.json").writeText(Json.mapper.writeValueAsString(it) + "\n") }
        rendered?.let { File(dir, it.first).writeText(it.second) }
        val bundle = FlowArtifactBundleAnalyzer().normalizationBundle(
            flowName = response.normalizedIntent.name,
            target = if (lower) (target ?: "jenkins") else (target ?: ""),
            strict = strict,
            lowered = lower,
            hasManifest = manifest != null,
            renderedArtifact = rendered?.first
        )
        diagnosticCoverage?.let {
            writeStandardClosureReports(dir, bundle, it)
        }
        File(dir, "flow-artifact-bundle.json").writeText(Json.mapper.writeValueAsString(bundle) + "\n")
        println("===== EXPORTED NORMALIZATION ARTIFACTS =====")
        println(dir.absolutePath)
    }
}

private fun collectNormalizeText(args: List<String>): String {
    val file = parseOption(args, "--file")
    if (file != null) return File(file).readText()
    val untilOption = args.takeWhile { !it.startsWith("--") }
    val joined = untilOption.joinToString(" ").trim()
    require(joined.isNotBlank()) { "normalize requires text arguments or --file <path>." }
    return joined
}

private fun parseOption(args: List<String>, name: String): String? {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.size) return args[idx + 1]
    return args.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

private fun parseOutDir(args: List<String>): String? {
    val idx = args.indexOf("--out")
    if (idx >= 0 && idx + 1 < args.size) return args[idx + 1]
    return args.firstOrNull { it.startsWith("--out=") }?.substringAfter('=')
}

private fun parseTarget(args: List<String>): String? {
    val idx = args.indexOf("--target")
    if (idx >= 0 && idx + 1 < args.size) return args[idx + 1]
    return args.firstOrNull { it.startsWith("--target=") }?.substringAfter('=')
}

private fun observedDiagnostic(
    artifact: String,
    code: String,
    source: String,
    severity: String
): ObservedDiagnosticCode = ObservedDiagnosticCode(
    artifact = artifact,
    code = code,
    source = source,
    severity = severity
)

private fun buildDiagnosticCoverageReport(
    intentValidation: List<ObservedDiagnosticCode> = emptyList(),
    validation: List<ObservedDiagnosticCode> = emptyList(),
    readiness: List<ObservedDiagnosticCode> = emptyList(),
    adapterContract: List<ObservedDiagnosticCode> = emptyList(),
    adapterDiagnostics: List<ObservedDiagnosticCode> = emptyList()
): DiagnosticCoverageReport =
    DiagnosticCoverageAnalyzer().analyze(intentValidation + validation + readiness + adapterContract + adapterDiagnostics)

private fun buildArtifactIntegrityReport(
    bundle: org.flowlang.artifacts.FlowArtifactBundleReport,
    diagnosticCoverage: DiagnosticCoverageReport
) = ArtifactIntegrityAnalyzer().analyze(
    bundle = bundle,
    presentArtifacts = bundle.pipeline.toSet(),
    standardVersionObservations = standardVersionObservations(bundle),
    diagnosticCoverage = diagnosticCoverage
)

private fun writeStandardClosureReports(
    dir: File,
    bundle: org.flowlang.artifacts.FlowArtifactBundleReport,
    diagnosticCoverage: DiagnosticCoverageReport
) {
    val integrity = buildArtifactIntegrityReport(bundle, diagnosticCoverage)
    val contractIndex = StandardContractIndexAnalyzer().analyze(bundle)
    val releaseProfile = StandardReleaseProfile.report()
    val evidence = ArtifactEvidenceAnalyzer().analyze(bundle)
    val compliance = StandardComplianceAnalyzer().analyze(
        bundle = bundle,
        contractIndex = contractIndex,
        releaseProfile = releaseProfile,
        evidence = evidence,
        integrity = integrity
    )
    val freeze = PublicStandardDraft.freeze(contractIndex)
    val compatibilityPolicy = PublicStandardDraft.compatibilityPolicy()
    val referenceCorpus = PublicStandardDraft.referenceCorpus()
    val negativeCorpus = PublicStandardDraft.negativeCorpus()
    val targetConformanceProfile = PublicStandardDraft.targetConformanceProfile()
    val publicSurface = StandardSurface.publicSurface()
    val compatibilityMigrationPolicy = StandardSurface.compatibilityMigrationPolicy()
    val referenceIntentCorpus = StandardSurface.referenceIntentCorpus()
    val targetSemanticsMatrix = StandardSurface.targetSemanticsMatrix()
    val standardExportBundle = StandardSurface.standardExportBundle()
    val conformanceLevels = StandardSurface.conformanceLevels()
    val standardExportManifest = StandardSurface.standardExportManifest()
    val conformanceVectorIndex = ConformanceVectorIndexBuilder().build(
        releaseProfileChecks = releaseProfile.requiredConformanceChecks
    )
    val standardIndex = PublicStandardDraft.standardIndex(bundle, contractIndex)
    val conformanceSuite = PublicStandardDraft.conformanceSuite()
    val draft = PublicStandardDraft.draft(bundle, compliance)
    File(dir, "artifact-integrity-report.json").writeText(Json.mapper.writeValueAsString(integrity) + "\n")
    File(dir, "standard-contract-index.json").writeText(Json.mapper.writeValueAsString(contractIndex) + "\n")
    File(dir, "standard-release-profile.json").writeText(Json.mapper.writeValueAsString(releaseProfile) + "\n")
    File(dir, "artifact-evidence-report.json").writeText(Json.mapper.writeValueAsString(evidence) + "\n")
    File(dir, "standard-compliance-report.json").writeText(Json.mapper.writeValueAsString(compliance) + "\n")
    File(dir, "standard-freeze-report.json").writeText(Json.mapper.writeValueAsString(freeze) + "\n")
    File(dir, "compatibility-policy.json").writeText(Json.mapper.writeValueAsString(compatibilityPolicy) + "\n")
    File(dir, "reference-corpus-index.json").writeText(Json.mapper.writeValueAsString(referenceCorpus) + "\n")
    File(dir, "negative-conformance-corpus.json").writeText(Json.mapper.writeValueAsString(negativeCorpus) + "\n")
    File(dir, "target-conformance-profile.json").writeText(Json.mapper.writeValueAsString(targetConformanceProfile) + "\n")
    File(dir, "public-standard-surface.json").writeText(Json.mapper.writeValueAsString(publicSurface) + "\n")
    File(dir, "compatibility-migration-policy.json").writeText(Json.mapper.writeValueAsString(compatibilityMigrationPolicy) + "\n")
    File(dir, "reference-intent-corpus.json").writeText(Json.mapper.writeValueAsString(referenceIntentCorpus) + "\n")
    File(dir, "target-semantics-matrix.json").writeText(Json.mapper.writeValueAsString(targetSemanticsMatrix) + "\n")
    File(dir, "standard-export-bundle.json").writeText(Json.mapper.writeValueAsString(standardExportBundle) + "\n")
    File(dir, "conformance-levels.json").writeText(Json.mapper.writeValueAsString(conformanceLevels) + "\n")
    File(dir, "standard-export-manifest.json").writeText(Json.mapper.writeValueAsString(standardExportManifest) + "\n")
    File(dir, "conformance-vector-index.json").writeText(Json.mapper.writeValueAsString(conformanceVectorIndex) + "\n")
    File(dir, "standard-index.json").writeText(Json.mapper.writeValueAsString(standardIndex) + "\n")
    File(dir, "conformance-suite.json").writeText(Json.mapper.writeValueAsString(conformanceSuite) + "\n")
    File(dir, "flow-standard-draft.json").writeText(Json.mapper.writeValueAsString(draft) + "\n")
}

private fun standardVersionObservations(bundle: org.flowlang.artifacts.FlowArtifactBundleReport): List<ArtifactIntegrityVersionObservation> =
    bundle.pipeline
        .filter { it.endsWith(".json") || it == "standard-version.txt" }
        .map { ArtifactIntegrityVersionObservation(it, FlowStandardVersions.FLOW_STANDARD_VERSION) }

private fun exportIntentArtifacts(
    outDir: File,
    normalizedIntentJson: String,
    intentDesignReportJson: String,
    intentDecisionReportJson: String,
    intentValidationJson: String,
    astJson: String,
    validationJson: String,
    planJson: String,
    canonicalPlanJson: String,
    compatibilityJson: String,
    negotiationJson: String,
    readinessJson: String,
    targetSelectionJson: String,
    targetDecisionTraceJson: String,
    targetAdapterContractJson: String,
    adapterDiagnosticsJson: String,
    diagnosticCoverage: DiagnosticCoverageReport,
    flowName: String,
    target: String,
    strict: Boolean,
    manifest: TargetManifest?,
    rendered: Pair<String, String>?
) {
    outDir.mkdirs()
    File(outDir, "standard-version.txt").writeText(FlowStandardVersions.FLOW_STANDARD_VERSION + "\n")
    File(outDir, "standard-diagnostic-catalog.json").writeText(Json.mapper.writeValueAsString(StandardDiagnosticCatalog.report()) + "\n")
    File(outDir, "normalized-intent.json").writeText(normalizedIntentJson + "\n")
    File(outDir, "intent-design-report.json").writeText(intentDesignReportJson + "\n")
    File(outDir, "intent-decision-report.json").writeText(intentDecisionReportJson + "\n")
    File(outDir, "intent-capability-validation-report.json").writeText(intentValidationJson + "\n")
    File(outDir, "flow-ast.json").writeText(astJson + "\n")
    File(outDir, "validation-report.json").writeText(validationJson + "\n")
    File(outDir, "execution-plan.json").writeText(planJson + "\n")
    File(outDir, "canonical-execution-plan.json").writeText(canonicalPlanJson + "\n")
    File(outDir, "compatibility-report.json").writeText(compatibilityJson + "\n")
    File(outDir, "capability-negotiation-report.json").writeText(negotiationJson + "\n")
    File(outDir, "execution-readiness-report.json").writeText(readinessJson + "\n")
    File(outDir, "target-selection-report.json").writeText(targetSelectionJson + "\n")
    File(outDir, "target-decision-trace-report.json").writeText(targetDecisionTraceJson + "\n")
    File(outDir, "target-adapter-contract.json").writeText(targetAdapterContractJson + "\n")
    File(outDir, "adapter-diagnostics.json").writeText(adapterDiagnosticsJson + "\n")
    File(outDir, "diagnostic-coverage-report.json").writeText(Json.mapper.writeValueAsString(diagnosticCoverage) + "\n")
    if (manifest != null) File(outDir, "target-manifest.json").writeText(Json.mapper.writeValueAsString(manifest) + "\n")
    if (rendered != null) File(outDir, rendered.first).writeText(rendered.second)
    val bundle = FlowArtifactBundleAnalyzer().intentBundle(
        flowName = flowName,
        target = target,
        strict = strict,
        hasManifest = manifest != null,
        renderedArtifact = rendered?.first
    )
    writeStandardClosureReports(outDir, bundle, diagnosticCoverage)
    File(outDir, "flow-artifact-bundle.json").writeText(Json.mapper.writeValueAsString(bundle) + "\n")
    println("===== EXPORTED ARTIFACTS =====")
    println(outDir.absolutePath)
}

private fun runCatalogCommand(args: List<String>) {
    if (args.contains("--markdown")) {
        println(StandardIntentCatalog.markdown())
        return
    }
    println("===== FLOW STANDARD INTENT CATALOG =====")
    println(Json.mapper.writeValueAsString(StandardIntentCatalog.definitions))
}

private fun runDiagnosticsCommand(args: List<String>) {
    if (args.contains("--markdown")) {
        println(StandardDiagnosticCatalog.markdown())
        return
    }
    println("===== FLOW STANDARD DIAGNOSTIC CATALOG =====")
    val catalogJson = Json.mapper.writeValueAsString(StandardDiagnosticCatalog.report())
    println(catalogJson)
    parseOutDir(args)?.let { out ->
        val dir = File(out)
        dir.mkdirs()
        File(dir, "standard-diagnostic-catalog.json").writeText(catalogJson + "\n")
        println("===== EXPORTED STANDARD DIAGNOSTIC CATALOG =====")
        println(dir.absolutePath)
    }
}

private fun runReleaseProfileCommand(args: List<String>) {
    val profileJson = Json.mapper.writeValueAsString(StandardReleaseProfile.report())
    println("===== FLOW STANDARD RELEASE PROFILE =====")
    println(profileJson)
    parseOutDir(args)?.let { out ->
        val dir = File(out)
        dir.mkdirs()
        File(dir, "standard-release-profile.json").writeText(profileJson + "\n")
        println("===== EXPORTED STANDARD RELEASE PROFILE =====")
        println(dir.absolutePath)
    }
}

private fun runStandardDraftCommand(args: List<String>) {
    val bundle = FlowArtifactBundleAnalyzer().intentBundle(
        flowName = "reference-public-draft",
        target = "jenkins",
        strict = false,
        hasManifest = true,
        renderedArtifact = "Jenkinsfile"
    )
    val coverage = DiagnosticCoverageAnalyzer().analyze(
        listOf(observedDiagnostic("adapter-diagnostics.json", "ADAPTER_CONTRACT_READY", "adapterDiagnostics.issues", "info"))
    )
    val integrity = buildArtifactIntegrityReport(bundle, coverage)
    val contractIndex = StandardContractIndexAnalyzer().analyze(bundle)
    val releaseProfile = StandardReleaseProfile.report()
    val evidence = ArtifactEvidenceAnalyzer().analyze(bundle)
    val compliance = StandardComplianceAnalyzer().analyze(bundle, contractIndex, releaseProfile, evidence, integrity)
    val artifacts = mapOf(
        "standard-freeze-report.json" to PublicStandardDraft.freeze(contractIndex),
        "compatibility-policy.json" to PublicStandardDraft.compatibilityPolicy(),
        "reference-corpus-index.json" to PublicStandardDraft.referenceCorpus(),
        "negative-conformance-corpus.json" to PublicStandardDraft.negativeCorpus(),
        "target-conformance-profile.json" to PublicStandardDraft.targetConformanceProfile(),
        "public-standard-surface.json" to StandardSurface.publicSurface(),
        "compatibility-migration-policy.json" to StandardSurface.compatibilityMigrationPolicy(),
        "reference-intent-corpus.json" to StandardSurface.referenceIntentCorpus(),
        "target-semantics-matrix.json" to StandardSurface.targetSemanticsMatrix(),
        "standard-export-bundle.json" to StandardSurface.standardExportBundle(),
        "conformance-levels.json" to StandardSurface.conformanceLevels(),
        "standard-export-manifest.json" to StandardSurface.standardExportManifest(),
        "conformance-vector-index.json" to ConformanceVectorIndexBuilder().build(
            releaseProfileChecks = releaseProfile.requiredConformanceChecks
        ),
        "standard-index.json" to PublicStandardDraft.standardIndex(bundle, contractIndex),
        "conformance-suite.json" to PublicStandardDraft.conformanceSuite(),
        "flow-standard-draft.json" to PublicStandardDraft.draft(bundle, compliance)
    )
    println("===== FLOW STANDARD DRAFT =====")
    println(Json.mapper.writeValueAsString(artifacts["flow-standard-draft.json"]))
    parseOutDir(args)?.let { out ->
        val dir = File(out)
        dir.mkdirs()
        artifacts.forEach { (name, report) -> File(dir, name).writeText(Json.mapper.writeValueAsString(report) + "\n") }
        println("===== EXPORTED FLOW STANDARD DRAFT =====")
        println(dir.absolutePath)
    }
}

private fun runStandardExportCommand(args: List<String>) {
    val out = parseOutDir(args) ?: "dist/flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}"
    val dir = File(out)
    dir.mkdirs()
    runStandardDraftCommand(listOf("--out", dir.path))
    val conformanceSummary = ConformanceRunner().run()
    val conformanceManifest = ConformanceManifestBuilder().build(conformanceSummary)
    val conformanceVectorIndex = ConformanceVectorIndexBuilder().build(
        runnerChecks = conformanceSummary.checks.map { it.name },
        releaseProfileChecks = StandardReleaseProfile.report().requiredConformanceChecks
    )
    File(dir, "conformance-manifest.json").writeText(Json.mapper.writeValueAsString(conformanceManifest) + "\n")
    File(dir, "conformance-vector-index.json").writeText(Json.mapper.writeValueAsString(conformanceVectorIndex) + "\n")
    File(dir, "standard-diagnostic-catalog.json").writeText(Json.mapper.writeValueAsString(StandardDiagnosticCatalog.report()) + "\n")
    File(dir, "standard-version.txt").writeText(FlowStandardVersions.FLOW_STANDARD_VERSION + "\n")
    File(dir, "standard-release-profile.json").writeText(Json.mapper.writeValueAsString(StandardReleaseProfile.report()) + "\n")
    File(dir, "public-standard-surface.json").writeText(Json.mapper.writeValueAsString(StandardSurface.publicSurface()) + "\n")
    File(dir, "compatibility-migration-policy.json").writeText(Json.mapper.writeValueAsString(StandardSurface.compatibilityMigrationPolicy()) + "\n")
    File(dir, "reference-intent-corpus.json").writeText(Json.mapper.writeValueAsString(StandardSurface.referenceIntentCorpus()) + "\n")
    File(dir, "target-semantics-matrix.json").writeText(Json.mapper.writeValueAsString(StandardSurface.targetSemanticsMatrix()) + "\n")
    File(dir, "standard-export-bundle.json").writeText(Json.mapper.writeValueAsString(StandardSurface.standardExportBundle()) + "\n")
    File(dir, "conformance-levels.json").writeText(Json.mapper.writeValueAsString(StandardSurface.conformanceLevels()) + "\n")
    File(dir, "standard-export-manifest.json").writeText(Json.mapper.writeValueAsString(StandardSurface.standardExportManifest()) + "\n")
    listOf("docs", "schemas", "conformance", "standard", "targets", "examples").forEach { copyStandardDirectory(it, dir) }
    println("===== EXPORTED FLOW STANDARD BUNDLE =====")
    println(dir.absolutePath)
    if (!conformanceSummary.ok) error("Flow conformance failed; standard export bundle is not publishable.")
}

private fun runStandardVerifyCommand(args: List<String>) {
    val bundle = parseBundleDir(args) ?: error("standard-verify requires --bundle <dir>")
    val report = StandardBundleVerifier().verify(File(bundle))
    val reportJson = Json.mapper.writeValueAsString(report)
    println("===== FLOW STANDARD BUNDLE VERIFICATION =====")
    println(reportJson)
    parseOutDir(args)?.let { out ->
        val dir = File(out)
        dir.mkdirs()
        File(dir, "standard-bundle-verification.json").writeText(reportJson + "\n")
        println("===== EXPORTED STANDARD BUNDLE VERIFICATION =====")
        println(dir.absolutePath)
    }
    if (report.status != "PASS") error("Flow standard bundle verification failed.")
}

private fun copyStandardDirectory(name: String, outDir: File) {
    val source = File(name)
    if (!source.isDirectory) return
    val target = File(outDir, name)
    source.copyRecursively(target, overwrite = true)
}

private fun parseBundleDir(args: List<String>): String? {
    val index = args.indexOf("--bundle")
    if (index >= 0 && index + 1 < args.size) return args[index + 1]
    return args.firstOrNull { !it.startsWith("--") }
}

private fun runScenariosCommand(args: List<String>) {
    if (args.contains("--markdown")) {
        println(ScenarioPackRegistry.markdown())
        return
    }
    println("===== FLOW SCENARIO PACKS =====")
    println(Json.mapper.writeValueAsString(ScenarioPackRegistry.jsonReady()))
}

private fun runScenarioCommand(args: List<String>) {
    val id = args.firstOrNull() ?: error("scenario requires a scenario pack id, for example: scenario deployment --examples")
    val pack = ScenarioPackRegistry.packs.firstOrNull { it.definition.id == id }
        ?: error("Unknown scenario pack '$id'. Run 'scenarios' to list available packs.")
    if (args.contains("--examples")) {
        println("===== SCENARIO EXAMPLES: ${pack.definition.id} =====")
        pack.definition.exampleRequests.forEach { println("- $it") }
        return
    }
    println("===== SCENARIO PACK: ${pack.definition.id} =====")
    println(Json.mapper.writeValueAsString(pack.definition))
}

private fun runTargetsCommand() {
    val targets = targetRegistry().values.sortedBy { it.target }
    println("===== FLOW TARGET CAPABILITY MATRIX =====")
    targets.forEach { t ->
        println("${t.target}: ${t.description}")
        println("  sequentialTasks=${t.sequentialTasks}, parallel=${t.parallel}, conditions=${t.conditions}, dynamicLoops=${t.dynamicLoops}, approvals=${t.approvals}, errorHandlers=${t.errorHandlers}, artifacts=${t.artifacts}, secrets=${t.secrets}, nativeRuntime=${t.nativeRuntime}")
        if (t.notes.isNotEmpty()) println("  notes=${t.notes.joinToString("; ")}")
    }
}

private fun runModulesCommand() {
    println("===== FLOW CAPABILITY MODULE CONTRACT REPORT =====")
    println(Json.mapper.writeValueAsString(ModuleContractAnalyzer.analyze(registry())))
}

private fun runConformanceCommand(args: List<String> = emptyList()) {
    val summary = ConformanceRunner().run()
    val manifest = ConformanceManifestBuilder().build(summary)
    val vectorIndex = ConformanceVectorIndexBuilder().build(
        runnerChecks = summary.checks.map { it.name },
        releaseProfileChecks = StandardReleaseProfile.report().requiredConformanceChecks
    )
    println("===== FLOW CONFORMANCE REPORT =====")
    summary.checks.forEach { check ->
        val status = if (check.passed) "PASS" else "FAIL"
        val msg = check.message?.let { " - $it" } ?: ""
        println("$status ${check.name}$msg")
    }
    println("-----")
    println("Passed: ${summary.passed}")
    println("Failed: ${summary.failed}")
    println("===== FLOW CONFORMANCE MANIFEST JSON =====")
    val manifestJson = Json.mapper.writeValueAsString(manifest)
    val vectorIndexJson = Json.mapper.writeValueAsString(vectorIndex)
    val diagnosticCatalogJson = Json.mapper.writeValueAsString(StandardDiagnosticCatalog.report())
    println(manifestJson)
    parseOutDir(args)?.let { out ->
        val dir = File(out)
        dir.mkdirs()
        File(dir, "conformance-manifest.json").writeText(manifestJson + "\n")
        File(dir, "conformance-vector-index.json").writeText(vectorIndexJson + "\n")
        File(dir, "standard-diagnostic-catalog.json").writeText(diagnosticCatalogJson + "\n")
        println("===== EXPORTED CONFORMANCE ARTIFACTS =====")
        println(dir.absolutePath)
    }
    if (!summary.ok) error("Flow conformance failed.")
}
