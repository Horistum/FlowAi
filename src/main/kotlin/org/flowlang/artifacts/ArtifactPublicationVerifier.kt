package org.flowlang.artifacts

import java.io.File
import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits
import org.flowlang.io.IoBudget
import org.flowlang.serialization.FlowJson
import org.flowlang.standard.FlowStandardVersions

/** Byte integrity, not authentication: a trusted external manifest hash can also be supplied. */
class ArtifactPublicationVerifier(private val schemaLoader: (String) -> ByteArray = ArtifactSchemas::packaged) {
    fun verify(directory: File, expectedManifest: ArtifactByteReceipt? = null): ArtifactIntegrityReport {
        val names = StagedArtifacts.artifactFiles(directory, InputLimits.MAX_RELEASE_FILES)
        val manifestBytes = BoundedIo.readBytes(File(directory, AtomicArtifactWriter.MANIFEST))
        val manifestReceipt = StagedArtifacts.inspect(AtomicArtifactWriter.MANIFEST, manifestBytes,
            AtomicArtifactWriter.MANIFEST_SCHEMA, false, schemaLoader)
        require(expectedManifest == null || expectedManifest == manifestReceipt) { "Publication manifest differs from trusted receipt." }
        val report = FlowJson.read(BoundedIo.decodeUtf8(manifestBytes), ArtifactIntegrityReport::class.java)
        val evidence = requireNotNull(report.publication) { "Actual-byte publication evidence is missing." }
        require(report.status == "PASS" && report.artifactIntegrityVersion == "1.1" &&
            report.standardVersion == FlowStandardVersions.FLOW_STANDARD_VERSION &&
            report.missingRequiredArtifacts.isEmpty() && report.standardVersionMismatches.isEmpty() &&
            report.diagnosticCoverageStatus == "PASS" && report.issues.none { it.severity == "error" }) { "Invalid publication integrity status." }
        require(evidence.protocol == "staged-atomic-directory-v1" && evidence.fileDataForced &&
            evidence.excludedPaths == listOf(AtomicArtifactWriter.MANIFEST)) { "Unsupported publication protocol." }
        val covered = evidence.coveredFiles.map { it.path }
        require(covered == covered.distinct().sorted() && names == (covered + AtomicArtifactWriter.MANIFEST).sorted()) {
            "Publication file inventory differs from its receipt."
        }
        require(report.requiredArtifactsExpected == report.requiredArtifactsPresent &&
            report.requiredArtifactsPresent.all { it in covered }) { "Publication required artifact coverage is incomplete." }
        if ("flow-artifact-bundle.json" in covered) {
            val bundle = FlowJson.read(File(directory, "flow-artifact-bundle.json"), FlowArtifactBundleReport::class.java)
            require(bundle.requiredArtifacts.filter { it != AtomicArtifactWriter.MANIFEST }.sorted() == report.requiredArtifactsExpected) {
                "Publication coverage contradicts the actual bundle declaration."
            }
            val records = evidence.coveredFiles.associateBy { it.path }
            bundle.artifacts.filter { it.name != AtomicArtifactWriter.MANIFEST }.forEach { entry -> records[entry.name]?.let {
                require(it.schema == entry.schema && (!entry.name.endsWith(".json") || it.validation in setOf("JSON", "JSON_SCHEMA"))) {
                    "Publication schema validation contradicts the actual bundle declaration."
                }
            } }
        }
        val budget = IoBudget(InputLimits.MAX_TOTAL_OUTPUT_BYTES, InputLimits.MAX_RELEASE_FILES, "OUTPUT")
        budget.add(manifestBytes.size)
        val records = evidence.coveredFiles.associateBy { it.path }
        for (receipt in evidence.coveredFiles + manifestReceipt) records[receipt.schema]?.let { includedSchema ->
            require(includedSchema.sha256 == receipt.schemaSha256) { "Included schema differs from validated schema bytes." }
        }
        for (expected in evidence.coveredFiles) {
            AtomicArtifactWriter.requireArtifactPath(expected.path)
            val bytes = BoundedIo.readBytes(File(directory, expected.path), minOf(InputLimits.MAX_ARTIFACT_BYTES, budget.remainingBytes()))
            budget.add(bytes.size)
            val actual = StagedArtifacts.inspect(expected.path, bytes, expected.schema, expected.validation == "BYTES", schemaLoader)
            require(actual == expected) { "Publication bytes or validation differ: ${expected.path}" }
        }
        return report
    }
}
