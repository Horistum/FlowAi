package org.flowlang.verification

import java.io.File
import kotlin.system.exitProcess
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.cli.Json
import org.flowlang.cli.honest.CliArtifact
import org.flowlang.cli.honest.CliArtifactRole
import org.flowlang.cli.honest.CliCommandCatalog
import org.flowlang.cli.honest.CliCommandHandler
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.CliOutput
import org.flowlang.cli.honest.CliPresenter
import org.flowlang.cli.honest.executeCli
import org.flowlang.conformance.ConformanceManifestBuilder
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceVectorIndexBuilder
import org.flowlang.conformance.ReferenceSnapshotBundleGenerator
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.release.StandardReleaseAssemblyAuthority
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardDiagnosticCatalog

/** Verification host: product commands are reused through the same typed CLI axis. */
fun main(args: Array<String>) {
    val result = executeVerificationCli(args)
    CliPresenter.present(result)
    if (result.exitCode != 0) exitProcess(result.exitCode)
}

fun executeVerificationCli(args: Array<String>): CliExecutionResult = executeCli(args, VerificationCommands.catalog)

object VerificationCommands {
    val catalog: CliCommandCatalog = CliCommandCatalog.of(
        "conformance" to command { args, output ->
            runConformance(args, output)
            CliExecutionResult.Completed(output.snapshot())
        },
        "reference-snapshot" to command { args, output ->
            runReferenceSnapshot(args, output)
            CliExecutionResult.Completed(output.snapshot())
        },
        "release-profile" to command(::runReleaseProfileCommand),
        "standard-draft" to command(::runStandardDraftCommand),
        "standard-export" to command(::runStandardExportCommand)
    )

    private fun command(action: (List<String>, CliOutput) -> CliExecutionResult): CliCommandHandler =
        CliCommandHandler { args, output -> action(args, output) }
}

private fun runReleaseProfileCommand(
    args: List<String>,
    output: CliOutput
): CliExecutionResult {
    val profile = StandardReleaseProfile.report()
    val honesty = ReleaseMetadataHonestyAuthority().requireValid()
    output.section("FLOW STANDARD RELEASE PROFILE", profile)
    output.section("RELEASE METADATA HONESTY REPORT", honesty)
    val persisted = parseOption(args, "--out")?.let { out ->
        val directory = File(out)
        require(directory.mkdirs() || directory.isDirectory)
        File(directory, "standard-release-profile.json").writeText(Json.mapper.writeValueAsString(profile) + "\n")
        File(directory, "release-metadata-honesty-report.json").writeText(Json.mapper.writeValueAsString(honesty) + "\n")
        true
    } ?: false
    return CliExecutionResult.Completed(
        presentation = output.snapshot(),
        artifacts = listOf(
            CliArtifact("standard-release-profile.json", CliArtifactRole.REVIEW_DOCUMENT, persisted),
            CliArtifact("release-metadata-honesty-report.json", CliArtifactRole.DIAGNOSTIC_EVIDENCE, persisted)
        )
    )
}

private fun runStandardDraftCommand(
    args: List<String>,
    output: CliOutput
): CliExecutionResult {
    val authority = StandardReleaseAssemblyAuthority()
    val destination = parseOption(args, "--out")
    val assembly = if (destination == null) authority.assemble() else authority.writeValidatedDraft(File(destination))
    output.section("FLOW STANDARD DRAFT", assembly.artifacts.getValue("flow-standard-draft.json"))
    return CliExecutionResult.Completed(
        presentation = output.snapshot(),
        artifacts = listOf(CliArtifact("flow-standard-draft.json", CliArtifactRole.REVIEW_DOCUMENT, destination != null))
    )
}

private fun runStandardExportCommand(
    args: List<String>,
    output: CliOutput
): CliExecutionResult {
    val destination = File(
        parseOption(args, "--out")
            ?: "dist/flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}"
    )
    val verification = StandardReleaseAssemblyAuthority().publishValidatedBundle(destination)
    output.section("FLOW STANDARD BUNDLE VERIFICATION", verification)
    output.text("===== PUBLISHED VERIFIED FLOW STANDARD BUNDLE =====")
    output.text(destination.absolutePath)
    return CliExecutionResult.Completed(
        presentation = output.snapshot(),
        artifacts = listOf(CliArtifact("standard-bundle-verification.json", CliArtifactRole.DIAGNOSTIC_EVIDENCE, true))
    )
}

private fun runConformance(args: List<String>, output: CliOutput) {
    val summary = ConformanceRunner().run()
    val manifest = ConformanceManifestBuilder().build(summary)
    val vectorIndex = ConformanceVectorIndexBuilder().build(
        runnerChecks = summary.checks.map { it.name },
        releaseProfileChecks = StandardReleaseProfile.report().requiredConformanceChecks
    )
    output.section("FLOW CONFORMANCE REPORT", summary)
    output.section("FLOW CONFORMANCE MANIFEST", manifest)
    parseOption(args, "--out")?.let { out ->
        val directory = File(out)
        require(directory.mkdirs() || directory.isDirectory)
        writeJson(directory, "conformance-manifest.json", manifest)
        writeJson(directory, "conformance-vector-index.json", vectorIndex)
        writeJson(directory, "standard-diagnostic-catalog.json", StandardDiagnosticCatalog.report())
    }
    require(summary.ok) { "Flow conformance failed." }
}

private fun runReferenceSnapshot(args: List<String>, output: CliOutput) {
    val source = parseOption(args, "--intent")
        ?: args.firstOrNull { !it.startsWith("--") }
        ?: "examples/intent/build-test-deploy.intent.yaml"
    val outputPath = parseOption(args, "--out") ?: "conformance/snapshots/build-test-deploy"
    val scenarioId = parseOption(args, "--scenario-id")
        ?: File(source).nameWithoutExtension.removeSuffix(".intent")
    val targets = parseOption(args, "--targets")
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        ?: ReferenceTargetProjections.registry.targetIds
    val snapshot = ReferenceSnapshotBundleGenerator().generate(
        intentFile = File(source),
        outputDir = File(outputPath),
        scenarioId = scenarioId,
        targetIds = targets
    )
    output.section("REFERENCE SNAPSHOT INDEX", snapshot)
    output.text("===== EXPORTED REFERENCE SNAPSHOT =====")
    output.text(File(outputPath).absolutePath)
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

private fun writeJson(directory: File, name: String, value: Any) {
    File(directory, name).writeText(Json.mapper.writeValueAsString(value) + "\n")
}
