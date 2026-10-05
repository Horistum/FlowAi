package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.artifacts.ArtifactContent
import org.flowlang.artifacts.ArtifactPublicationVerifier
import org.flowlang.artifacts.AtomicArtifactWriter

internal class AtomicPublicationConformanceChecks {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.artifacts.actual-byte-publication") { root ->
            val destination = File(root, "published")
            val receipt = AtomicArtifactWriter().publish(destination, mapOf("result.txt" to ArtifactContent.encode("result.txt", "actual bytes")))
            val report = ArtifactPublicationVerifier().verify(destination, receipt.manifest)
            require(report.publication!!.coveredFiles.single().sizeBytes == File(destination, "result.txt").length())
            File(destination, "result.txt").appendText("changed")
            require(runCatching { ArtifactPublicationVerifier().verify(destination) }.isFailure)
        },
        check("architecture-recovery.artifacts.failed-preparation-never-publishes") { root ->
            val destination = File(root, "published")
            require(runCatching {
                AtomicArtifactWriter().publish(destination, mapOf("result.txt" to ArtifactContent.encode("result.txt", "actual bytes"))) {
                    require(File(it.directory, "result.txt").isFile)
                    error("Injected validation failure")
                }
            }.isFailure)
            require(!destination.exists() && root.listFiles()!!.isEmpty())
        }
    )

    private fun check(name: String, action: (File) -> Unit): ConformanceCheck {
        val root = createTempDirectory("atomic-publication-conformance-").toFile()
        val result = try { runCatching { action(root) } } finally { root.deleteRecursively() }
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }
}
