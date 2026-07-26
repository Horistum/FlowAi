package org.flowlang.conformance

import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.cli.Json
import org.flowlang.standard.FlowStandardVersions
import java.io.File

/** Builds a structurally valid exported standard bundle for verifier conformance. */
internal object StrictStandardBundleFixture {
    fun create(rootDir: File): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "flow-standard-bundle-${System.nanoTime()}")
        dir.mkdirs()

        val manifest = StandardSurface.standardExportManifest()
        val export = StandardSurface.standardExportBundle()
        val releaseProfile = StandardReleaseProfile.report()
        val conformance = ConformanceManifestBuilder(rootDir).build(
            summary = ConformanceSummary(
                releaseProfile.requiredConformanceChecks.map { check -> ConformanceCheck(check, true) }
            ),
            implementation = "flow-standard-bundle-fixture"
        )

        fun write(path: String, text: String = "{}\n") {
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }

        fun writeJson(path: String, value: Any) {
            write(path, Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
        }

        manifest.requiredDirectories.forEach { File(dir, it.trimEnd('/')).mkdirs() }
        export.requiredDirectories.forEach { File(dir, it.trimEnd('/')).mkdirs() }
        write("standard-version.txt", FlowStandardVersions.FLOW_STANDARD_VERSION + "\n")
        manifest.requiredDocuments.forEach { write(it, "Reference document for $it\n") }
        manifest.requiredSchemas.forEach { write(it, "{}\n") }
        manifest.requiredJsonArtifacts.forEach { write(it) }
        manifest.evidenceArtifacts.forEach { write(it) }
        export.requiredFiles.filterNot { it == "standard-version.txt" }.forEach { write(it) }

        writeJson("conformance-manifest.json", conformance)
        writeJson("standard-export-bundle.json", export)
        writeJson("standard-release-profile.json", releaseProfile)
        return dir
    }
}
