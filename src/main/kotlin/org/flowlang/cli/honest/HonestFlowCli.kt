package org.flowlang.cli.honest

import java.io.File
import kotlin.system.exitProcess
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ai.normalization.AiIntentContext
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.NormalizationMode
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.FlowArtifactBundleReport
import org.flowlang.artifacts.FlowArtifactEntry
import org.flowlang.artifacts.FlowArtifactRole
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDecisionAnalyzer
import org.flowlang.intent.IntentDesignAnalyzer
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.release.StandardReleaseAssemblyAuthority
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.DiagnosticCoverageReport
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.ObservedDiagnosticCode
import org.flowlang.standard.StandardDiagnosticCatalog
import org.flowlang.validator.FlowValidator

data class CliCommandFailureReport(
    val status: String = "FAIL",
    val code: String,
    val command: String,
    val message: String,
    val causeType: String
)

data class CliTargetNeutralPlanningEvidence(
    val flowName: String,
    val planVersion: String,
    val targetSelected: Boolean = false,
    val negotiation: Any,
    val selection: Any,
    val message: String = "No target was selected. Planning evidence is target-neutral and no target manifest or rendered syntax was produced."
)

data class CliTargetOutcomeReport(
    val target: String,
    val outcome: CliTargetEvidenceOutcome,
    val diagnosticFallbackUsed: Boolean,
    val renderRequested: Boolean,
    val renderAuthorized: Boolean,
    val diagnostics: List<CliTargetDiagnostic>
)

fun main(args: Array<String>) {
    val status = runCli(args)
    if (status != 0) exitProcess(status)
}

/** Public testable entrypoint. Expected command failures are emitted as JSON without stack traces. */
fun runCli(args: Array<String>): Int {
    val command = args.firstOrNull()
    if (command == null) {
        printHelp()
        return 0
    }
    return try {
        when (command) {
            "intent" -> runIntentCommand(args.drop(1))
            "normalize" -> runNormalizeCommand(args.drop(1))
            "diagnostics" -> runDiagnosticsCommand(args.drop(1))
            "release-profile" -> runReleaseProfileCommand(args.drop(1))
            "standard-draft" -> runStandardDraftCommand(args.drop(1))
            "standard-export" -> runStandardExportCommand(args.drop(1))
            "standard-verify" -> runStandardVerifyCommand(args.drop(1))
            in StandardCliCommands.names -> {
                StandardCliCommands.run(command, args.drop(1))
                0
            }
            else -> throw IllegalArgumentException(
                "Unknown command '$command'. Supported commands: ${supportedCommands.joinToString()}."
            )
        }
    } catch (failure: Exception) {
        val report = CliCommandFailureReport(
            code = when (failure) {
                is IllegalArgumentException -> "CLI_INVALID_INPUT"
                is IllegalStateException -> "CLI_INTEGRITY_BLOCKED"
                else -> "CLI_INTERNAL_ERROR"
            },
            command = command,
            message = failure.message ?: "CLI command failed without a diagnostic message.",
            causeType = failure::class.qualifiedName ?: failure::class.simpleName.orEmpty()
        )
        printSection("CLI DIAGNOSTIC FAILURE", report)
        2
    }
}

private fun runIntentCommand(args: List<String>): Int {
    val source = args.firstOrNull { !it.startsWith("--") }
        ?: "examples/intent/build-test-deploy.intent.yaml"
    val target = parseOption(args, "--target")
    val strict = "--strict" in args || "--fail-on-unsupported" in args
    val renderRequested = "--render" in args
    val outDir = parseOption(args, "--out")?.let(::File)
    require(!renderRequested || target != null) { "--render requires an explicit --target; Flow does not select Jenkins or any other target implicitly." }
    val file = File(source)
    require(file.isFile) { "Intent file does not exist: $source" }

    val registry = moduleRegistry()
    val targets = targetRegistry()
    val intent = IntentYamlLoader.load(file)
    val design = IntentDesignAnalyzer(registry).analyze(intent)
    val decision = IntentDecisionAnalyzer(registry).analyze(intent)
    val intentValidation = IntentCapabilityValidator(registry).validate(intent)
    intentValidation.assertValid()
    val ast = IntentToAstPlanner(registry).plan(intent)
    val validation = FlowValidator(registry).validate(ast)
    require(validation.valid) {
        "Flow validation failed before planning: " + validation.issues.joinToString { it.code + ": " + it.message }
    }
    val plan = FlowPlanner(registry).plan(ast)

    printSection("INTENT DESIGN REPORT", design)
    printSection("INTENT DECISION REPORT", decision)
    printSection("NORMALIZED INTENT JSON", intent)
    printSection("INTENT CAPABILITY VALIDATION REPORT", intentValidation)
    printSection("GENERATED FLOW AST JSON", ast)
    printSection("VALIDATION REPORT", validation)
    printSection("EXECUTION PLAN JSON", plan)
    printSection("CANONICAL EXECUTION PLAN JSON", ExecutionPlanCanonicalizer.canonicalize(plan))

    if (target == null) {
        val planning = targetNeutralPlanningEvidence(plan, targets, strict)
        printSection("TARGET-NEUTRAL PLANNING EVIDENCE", planning)
        outDir?.let {
            writePlanningArtifacts(
                directory = it,
                normalizedIntent = intent,
                intentDesign = design,
                intentDecision = decision,
                intentValidation = intentValidation,
                ast = ast,
                validation = validation,
                plan = plan,
                planning = planning,
                strict = strict
            )
        }
        return 0
    }

    val evidence = CliTargetEvidenceAuthority(targets).evaluate(plan, target, strict, renderRequested)
    printTargetEvidence(evidence, renderRequested)
    outDir?.let {
        writeIntentArtifacts(
            directory = it,
            normalizedIntent = intent,
            intentDesign = design,
            intentDecision = decision,
            intentValidation = intentValidation,
            ast = ast,
            validation = validation,
            plan = plan,
            evidence = evidence,
            strict = strict,
            renderRequested = renderRequested
        )
    }
    return targetCommandStatus(evidence, strict, renderRequested)
}

private fun runNormalizeCommand(args: List<String>): Int {
    val text = parseOption(args, "--file")?.let { File(it).readText() }
        ?: args.takeWhile { !it.startsWith("--") }.joinToString(" ").trim()
    require(text.isNotBlank()) { "normalize requires text arguments or --file <path>." }
    val target = parseOption(args, "--target")
    val strict = "--strict" in args || "--fail-on-unsupported" in args
    val renderRequested = "--render" in args
    val lower = "--lower" in args || "--pipeline" in args || renderRequested
    require(!renderRequested || target != null) { "--render requires an explicit --target; Flow does not select Jenkins or any other target implicitly." }
    val mode = when {
        "--strict" in args -> NormalizationMode.STRICT
        "--explain" in args -> NormalizationMode.EXPLAIN
        "--repair" in args -> NormalizationMode.REPAIR
        else -> NormalizationMode.DRAFT
    }
    val response = ScenarioPackIntentNormalizer().normalize(
        AiIntentRequest(
            text,
            AiIntentContext(
                target = target,
                defaultApplication = parseOption(args, "--app"),
                defaultEnvironment = parseOption(args, "--environment"),
                repositoryUrl = parseOption(args, "--repo"),
                notificationChannel = parseOption(args, "--channel")
            ),
            mode
        )
    )
    printSection("AI INTENT NORMALIZATION REPORT", response.report)
    printSection("NORMALIZED INTENT JSON", response.normalizedIntent)
    val registry = moduleRegistry()
    val decision = IntentDecisionAnalyzer(registry).analyze(response.normalizedIntent)
    printSection("INTENT DECISION REPORT", decision)
    if (strict) response.assertUsableForLowering()

    if (!lower) {
        parseOption(args, "--out")?.let { out ->
            val values = linkedMapOf<String, Any>(
                "standard-diagnostic-catalog.json" to StandardDiagnosticCatalog.report(),
                "ai-normalization-report.json" to response.report,
                "normalized-intent.json" to response.normalizedIntent,
                "intent-decision-report.json" to decision
            )
            writeMinimalBundle(
                directory = File(out),
                flowName = response.normalizedIntent.name,
                target = target.orEmpty(),
                strict = strict,
                values = values,
                includeAiNormalization = true
            )
        }
        return 0
    }

    when (val proposal = IntentProposalReview(registry).review(response)) {
        is IntentProposalDecision.Rejected -> throw IllegalArgumentException(
            "AI proposal rejected by the standard before lowering: " +
                proposal.violations.joinToString { it.code }
        )
        is IntentProposalDecision.Accepted -> Unit
    }
    val intentValidation = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
    intentValidation.assertValid()
    val design = IntentDesignAnalyzer(registry).analyze(response.normalizedIntent)
    val ast = IntentToAstPlanner(registry).plan(response.normalizedIntent)
    val validation = FlowValidator(registry).validate(ast)
    require(validation.valid) {
        "Flow validation failed before planning: " + validation.issues.joinToString { it.code + ": " + it.message }
    }
    val plan = FlowPlanner(registry).plan(ast)
    val targets = targetRegistry()

    printSection("INTENT CAPABILITY VALIDATION REPORT", intentValidation)
    printSection("INTENT DESIGN REPORT", design)
    printSection("GENERATED FLOW AST JSON", ast)
    printSection("VALIDATION REPORT", validation)
    printSection("EXECUTION PLAN JSON", plan)
    printSection("CANONICAL EXECUTION PLAN JSON", ExecutionPlanCanonicalizer.canonicalize(plan))

    val output = parseOption(args, "--out")?.let(::File)
    if (target == null) {
        val planning = targetNeutralPlanningEvidence(plan, targets, strict)
        printSection("TARGET-NEUTRAL PLANNING EVIDENCE", planning)
        output?.let {
            writePlanningArtifacts(
                directory = it,
                normalizedIntent = response.normalizedIntent,
                intentDesign = design,
                intentDecision = decision,
                intentValidation = intentValidation,
                ast = ast,
                validation = validation,
                plan = plan,
                planning = planning,
                strict = strict,
                normalizationReport = response.report
            )
        }
        return 0
    }

    val evidence = CliTargetEvidenceAuthority(targets).evaluate(plan, target, strict, renderRequested)
    printTargetEvidence(evidence, renderRequested)
    output?.let {
        writeIntentArtifacts(
            directory = it,
            normalizedIntent = response.normalizedIntent,
            intentDesign = design,
            intentDecision = decision,
            intentValidation = intentValidation,
            ast = ast,
            validation = validation,
            plan = plan,
            evidence = evidence,
            strict = strict,
            renderRequested = renderRequested,
            normalizationReport = response.report
        )
    }
    return targetCommandStatus(evidence, strict, renderRequested)
}

private fun targetNeutralPlanningEvidence(
    plan: ExecutionPlan,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    strict: Boolean
): CliTargetNeutralPlanningEvidence = CliTargetNeutralPlanningEvidence(
    flowName = plan.flowName,
    planVersion = plan.planVersion,
    negotiation = CompatibilityAnalyzer(targets).negotiate(plan, strict),
    selection = TargetSelectionAnalyzer(targets).analyze(plan, strict)
)

private fun targetCommandStatus(
    evidence: CliTargetEvidence,
    strict: Boolean,
    renderRequested: Boolean
): Int = if ((strict || renderRequested) && evidence.outcome != CliTargetEvidenceOutcome.EXECUTABLE) 3 else 0

private fun printTargetEvidence(evidence: CliTargetEvidence, renderRequested: Boolean) {
    printSection(
        "CLI TARGET OUTCOME",
        CliTargetOutcomeReport(
            target = evidence.manifest.target,
            outcome = evidence.outcome,
            diagnosticFallbackUsed = evidence.diagnosticFallbackUsed,
            renderRequested = renderRequested,
            renderAuthorized = evidence.renderedArtifact != null,
            diagnostics = evidence.diagnostics
        )
    )
    printSection("RECONCILED TARGET COMPATIBILITY REPORT", evidence.compatibility)
    printSection("RECONCILED TARGET CAPABILITY NEGOTIATION REPORT", evidence.negotiation)
    printSection("RECONCILED EXECUTION READINESS REPORT", evidence.readiness)
    printSection("RECONCILED TARGET SELECTION REPORT", evidence.selection)
    printSection("RECONCILED TARGET DECISION TRACE REPORT", evidence.decisionTrace)
    printSection("RECONCILED TARGET ADAPTER CONTRACT", evidence.adapterContract)
    printSection("ADAPTER DIAGNOSTICS", evidence.adapterContract.diagnostics)
    printSection("TARGET MANIFEST EVIDENCE", evidence.manifest)
    printSection("TARGET RENDER READINESS", evidence.renderReadiness)
    if (evidence.renderedArtifact != null) {
        println("===== RENDERED EXECUTABLE TARGET OUTPUT: ${evidence.renderedArtifact.fileName} =====")
        println(evidence.renderedArtifact.content)
    } else {
        println("===== TARGET OUTPUT NOT RENDERED =====")
        println(
            when (evidence.renderReadiness.mode) {
                TargetRenderMode.EXECUTABLE -> "Executable evidence is available, but rendering was not requested. Use --render explicitly."
                TargetRenderMode.REVIEW_ONLY -> "Manifest evidence is review-only; target syntax was not emitted."
                TargetRenderMode.FAIL_FAST -> "Manifest evidence is blocked; target syntax was not emitted."
            }
        )
    }
}

private fun writePlanningArtifacts(
    directory: File,
    normalizedIntent: Any,
    intentDesign: Any,
    intentDecision: Any,
    intentValidation: Any,
    ast: Any,
    validation: Any,
    plan: ExecutionPlan,
    planning: CliTargetNeutralPlanningEvidence,
    strict: Boolean,
    normalizationReport: Any? = null
) {
    val values = linkedMapOf<String, Any>(
        "standard-diagnostic-catalog.json" to StandardDiagnosticCatalog.report()
    )
    normalizationReport?.let { values["ai-normalization-report.json"] = it }
    values += linkedMapOf(
        "normalized-intent.json" to normalizedIntent,
        "intent-design-report.json" to intentDesign,
        "intent-decision-report.json" to intentDecision,
        "intent-capability-validation-report.json" to intentValidation,
        "flow-ast.json" to ast,
        "validation-report.json" to validation,
        "execution-plan.json" to plan,
        "canonical-execution-plan.json" to ExecutionPlanCanonicalizer.canonicalize(plan),
        "target-neutral-planning-report.json" to planning
    )
    writeMinimalBundle(directory, plan.flowName, "", strict, values, normalizationReport != null)
}

private fun writeIntentArtifacts(
    directory: File,
    normalizedIntent: Any,
    intentDesign: Any,
    intentDecision: Any,
    intentValidation: Any,
    ast: Any,
    validation: Any,
    plan: ExecutionPlan,
    evidence: CliTargetEvidence,
    strict: Boolean,
    renderRequested: Boolean,
    normalizationReport: Any? = null
) {
    val observed = buildList {
        addAll(extractIssueDiagnostics("intent-capability-validation-report.json", intentValidation))
        addAll(extractIssueDiagnostics("validation-report.json", validation))
        addAll(evidence.readiness.blockers.map {
            ObservedDiagnosticCode("execution-readiness-report.json", it.code, "readiness.blockers", it.severity.name.lowercase())
        })
        addAll(evidence.readiness.warnings.map {
            ObservedDiagnosticCode("execution-readiness-report.json", it.code, "readiness.warnings", it.severity.name.lowercase())
        })
        addAll(evidence.adapterContract.invariants.map {
            ObservedDiagnosticCode("target-adapter-contract.json", it.code, "adapterContract.invariants", it.severity.name.lowercase())
        })
        addAll(evidence.adapterContract.diagnostics.issues.map {
            ObservedDiagnosticCode("adapter-diagnostics.json", it.code, "adapterDiagnostics.issues", it.severity.name.lowercase())
        })
        addAll(evidence.diagnostics.map {
            ObservedDiagnosticCode("cli-target-outcome.json", it.code, "cliTargetOutcome.diagnostics", it.severity)
        })
    }
    val diagnosticCoverage = DiagnosticCoverageAnalyzer().analyze(observed)
    require(diagnosticCoverage.status == "PASS") {
        "CLI diagnostic coverage failed: ${diagnosticCoverage.unknownCodes.joinToString { it.code }}"
    }
    val outcome = CliTargetOutcomeReport(
        target = evidence.manifest.target,
        outcome = evidence.outcome,
        diagnosticFallbackUsed = evidence.diagnosticFallbackUsed,
        renderRequested = renderRequested,
        renderAuthorized = evidence.renderedArtifact != null,
        diagnostics = evidence.diagnostics
    )
    val values = linkedMapOf<String, Any>(
        "standard-diagnostic-catalog.json" to StandardDiagnosticCatalog.report()
    )
    normalizationReport?.let { values["ai-normalization-report.json"] = it }
    values += linkedMapOf(
        "normalized-intent.json" to normalizedIntent,
        "intent-design-report.json" to intentDesign,
        "intent-decision-report.json" to intentDecision,
        "intent-capability-validation-report.json" to intentValidation,
        "flow-ast.json" to ast,
        "validation-report.json" to validation,
        "execution-plan.json" to plan,
        "canonical-execution-plan.json" to ExecutionPlanCanonicalizer.canonicalize(plan),
        "cli-target-outcome.json" to outcome,
        "compatibility-report.json" to evidence.compatibility,
        "capability-negotiation-report.json" to evidence.negotiation,
        "execution-readiness-report.json" to evidence.readiness,
        "target-selection-report.json" to evidence.selection,
        "target-decision-trace-report.json" to evidence.decisionTrace,
        "target-adapter-contract.json" to evidence.adapterContract,
        "adapter-diagnostics.json" to evidence.adapterContract.diagnostics,
        "diagnostic-coverage-report.json" to diagnosticCoverage,
        "target-manifest.json" to evidence.manifest,
        "target-render-readiness.json" to evidence.renderReadiness
    )
    evidence.renderedArtifact?.let { values[it.fileName] = it.content }
    writeMinimalBundle(
        directory = directory,
        flowName = evidence.manifest.flowName,
        target = evidence.manifest.target,
        strict = strict,
        values = values,
        includeAiNormalization = normalizationReport != null
    )
}

private fun writeMinimalBundle(
    directory: File,
    flowName: String,
    target: String,
    strict: Boolean,
    values: LinkedHashMap<String, Any>,
    includeAiNormalization: Boolean
) {
    require(directory.mkdirs() || directory.isDirectory) { "Cannot create output directory: ${directory.path}" }
    val catalog = FlowArtifactBundleAnalyzer().normalizationBundle(
        flowName = flowName,
        target = target,
        strict = strict,
        lowered = values.containsKey("execution-plan.json"),
        hasManifest = values.containsKey("target-manifest.json"),
        renderedArtifact = values.keys.firstOrNull { it !in knownJsonArtifacts && !it.endsWith(".json") }
    ).artifacts.associateBy { it.name }
    val names = listOf("standard-version.txt") + values.keys + listOf("artifact-integrity-report.json", "flow-artifact-bundle.json")
    val entries = names.distinct().mapIndexed { index, name ->
        val known = catalog[name]
        FlowArtifactEntry(
            name = name,
            role = known?.role ?: if (name.endsWith(".json")) FlowArtifactRole.REPORT else FlowArtifactRole.RENDERED,
            schema = known?.schema.orEmpty(),
            required = true,
            derived = name != "standard-version.txt" && name != "standard-diagnostic-catalog.json",
            pipelineIndex = index + 1,
            derivedFrom = known?.derivedFrom.orEmpty()
        )
    }
    val bundle = FlowArtifactBundleReport(
        flowName = flowName,
        target = target,
        strict = strict,
        artifacts = entries,
        requiredArtifacts = entries.map { it.name },
        optionalArtifacts = emptyList(),
        pipeline = entries.map { it.name }
    )
    val coverage = values["diagnostic-coverage-report.json"] as? DiagnosticCoverageReport
        ?: DiagnosticCoverageAnalyzer().analyze(emptyList())
    val integrity = ArtifactIntegrityAnalyzer().analyze(
        bundle = bundle,
        presentArtifacts = bundle.pipeline.toSet(),
        standardVersionObservations = bundle.pipeline
            .filter { it.endsWith(".json") || it == "standard-version.txt" }
            .map { ArtifactIntegrityVersionObservation(it, FlowStandardVersions.FLOW_STANDARD_VERSION) },
        diagnosticCoverage = coverage
    )
    require(integrity.status == "PASS") {
        "CLI artifact integrity failed: ${integrity.issues.joinToString { it.code + ":" + it.artifact }}"
    }
    File(directory, "standard-version.txt").writeText(FlowStandardVersions.FLOW_STANDARD_VERSION + "\n")
    values.forEach { (name, value) ->
        val text = if (value is String && !name.endsWith(".json")) value else Json.mapper.writeValueAsString(value)
        File(directory, name).writeText(text + if (text.endsWith("\n")) "" else "\n")
    }
    File(directory, "artifact-integrity-report.json").writeText(Json.mapper.writeValueAsString(integrity) + "\n")
    File(directory, "flow-artifact-bundle.json").writeText(Json.mapper.writeValueAsString(bundle) + "\n")
}

private fun extractIssueDiagnostics(artifact: String, report: Any): List<ObservedDiagnosticCode> {
    val tree = Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(report)
    val issues = tree.path("issues")
    if (!issues.isArray) return emptyList()
    return issues.mapNotNull { issue ->
        val code = issue.path("code").asText()
        code.takeIf { it.isNotBlank() }?.let {
            ObservedDiagnosticCode(
                artifact = artifact,
                code = code,
                source = "issues",
                severity = issue.path("severity").asText(issue.path("level").asText())
            )
        }
    }
}

private fun runDiagnosticsCommand(args: List<String>): Int {
    val catalog = StandardDiagnosticCatalog.report()
    printSection("FLOW STANDARD DIAGNOSTIC CATALOG", catalog)
    parseOption(args, "--out")?.let { out ->
        val directory = File(out)
        require(directory.mkdirs() || directory.isDirectory)
        File(directory, "standard-diagnostic-catalog.json").writeText(Json.mapper.writeValueAsString(catalog) + "\n")
    }
    return 0
}

private fun runReleaseProfileCommand(args: List<String>): Int {
    val profile = StandardReleaseProfile.report()
    val honesty = ReleaseMetadataHonestyAuthority().requireValid()
    printSection("FLOW STANDARD RELEASE PROFILE", profile)
    printSection("RELEASE METADATA HONESTY REPORT", honesty)
    parseOption(args, "--out")?.let { out ->
        val directory = File(out)
        require(directory.mkdirs() || directory.isDirectory)
        File(directory, "standard-release-profile.json").writeText(Json.mapper.writeValueAsString(profile) + "\n")
        File(directory, "release-metadata-honesty-report.json").writeText(Json.mapper.writeValueAsString(honesty) + "\n")
    }
    return 0
}

private fun runStandardDraftCommand(args: List<String>): Int {
    val authority = StandardReleaseAssemblyAuthority()
    val output = parseOption(args, "--out")
    val assembly = if (output == null) authority.assemble() else authority.writeValidatedDraft(File(output))
    printSection("FLOW STANDARD DRAFT", assembly.artifacts.getValue("flow-standard-draft.json"))
    return 0
}

private fun runStandardExportCommand(args: List<String>): Int {
    val output = File(
        parseOption(args, "--out")
            ?: "dist/flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}"
    )
    val verification = StandardReleaseAssemblyAuthority().publishValidatedBundle(output)
    printSection("FLOW STANDARD BUNDLE VERIFICATION", verification)
    println("===== PUBLISHED VERIFIED FLOW STANDARD BUNDLE =====")
    println(output.absolutePath)
    return 0
}

private fun runStandardVerifyCommand(args: List<String>): Int {
    val bundle = parseOption(args, "--bundle")
        ?: args.firstOrNull { !it.startsWith("--") }
        ?: throw IllegalArgumentException("standard-verify requires --bundle <dir>")
    val report = StandardBundleVerifier().verify(File(bundle))
    printSection("FLOW STANDARD BUNDLE VERIFICATION", report)
    parseOption(args, "--out")?.let { out ->
        val directory = File(out)
        require(directory.mkdirs() || directory.isDirectory)
        File(directory, "standard-bundle-verification.json").writeText(Json.mapper.writeValueAsString(report) + "\n")
    }
    require(report.status == "PASS") { "Flow standard bundle verification failed." }
    return 0
}

private fun moduleRegistry(): ModuleRegistry {
    val directory = File("modules")
    return if (directory.isDirectory) ModuleRegistry.fromDirectory(directory, includeDefaults = true) else ModuleRegistry()
}

private fun targetRegistry() = TargetRegistryYamlLoader.loadDirectory(File("targets")).also {
    require(it.isNotEmpty()) { "No target registry found under targets." }
}

private fun parseOption(args: List<String>, name: String): String? {
    val index = args.indexOf(name)
    if (index >= 0) {
        require(index + 1 < args.size && !args[index + 1].startsWith("--")) { "$name requires a value." }
        return args[index + 1]
    }
    return args.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.also {
        require(it.isNotBlank()) { "$name requires a value." }
    }
}

private fun printSection(title: String, value: Any) {
    println("===== $title =====")
    println(Json.mapper.writeValueAsString(value))
}

private fun printHelp() {
    println("Flow CLI commands: ${supportedCommands.joinToString()}")
    println("Target materialization requires --target. Rendering additionally requires --render.")
}

private val supportedCommands = (
    setOf("intent", "normalize", "diagnostics", "release-profile", "standard-draft", "standard-export", "standard-verify") +
        StandardCliCommands.names
).sorted()

private val knownJsonArtifacts = setOf(
    "standard-version.txt",
    "standard-diagnostic-catalog.json",
    "ai-normalization-report.json",
    "normalized-intent.json",
    "intent-design-report.json",
    "intent-decision-report.json",
    "intent-capability-validation-report.json",
    "flow-ast.json",
    "validation-report.json",
    "execution-plan.json",
    "canonical-execution-plan.json",
    "target-neutral-planning-report.json",
    "cli-target-outcome.json",
    "compatibility-report.json",
    "capability-negotiation-report.json",
    "execution-readiness-report.json",
    "target-selection-report.json",
    "target-decision-trace-report.json",
    "target-adapter-contract.json",
    "adapter-diagnostics.json",
    "diagnostic-coverage-report.json",
    "target-manifest.json",
    "target-render-readiness.json"
)