package org.flowlang.verification

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.architecture.KotlinSourceBoundaryScanner
import org.flowlang.serialization.FlowYaml

/** Mandatory integration checks reconcile migration metadata with actual Gradle compiler inputs. */
class IntegratedBoundaryInventoryTests {
    private val inventory get() = FlowYaml.readMap(File(".flow-agent/architecture/compiler-adapter-boundary-inventory.yaml"))
    private val ownership get() = FlowYaml.readMap(File("build/reports/module-ownership/source-ownership.json"))

    @Test fun actualCompiledOwnershipIsExhaustiveWithNoRootImplementation() {
        val report = ownership
        assertEquals(true, report["completePartition"])
        val modules = section(report["modules"])
        assertEquals(setOf(":") + File(".").listFiles().orEmpty().filter { File(it, "build.gradle.kts").isFile && it.name.startsWith("flow-") }
            .map { ":${it.name}" }, modules.keys)
        assertEquals(mapOf("role" to "aggregate", "sources" to emptyList<String>()), modules[":"])
        assertEquals("verification", section(modules[":flow-conformance-kit"])["role"])
        val declared = modules.values.flatMap { strings(section(it)["sources"]) }
        assertEquals(declared.size, declared.toSet().size)
        val actual = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(File("src/main/kotlin")).invariantSeparatorsPath }.toSet()
        assertEquals(actual, declared.toSet())
        modules.filterKeys { it !in setOf(":", ":flow-conformance-kit") }.values.forEach {
            assertEquals("product", section(it)["role"])
        }
    }

    @Test fun everyDeprecatedDeclarationHasAReviewedActualOwnerAndRemovalDecision() {
        validateInventory(inventory)
    }

    @Test fun anUnreviewedDeprecatedSourceCannotDisappearBehindAStableTotalCount() {
        val current = inventory
        val entries = sections(current["deprecatedSources"])
        assertFailsWith<IllegalArgumentException> { validateInventory(current + ("deprecatedSources" to entries.drop(1))) }
        assertFailsWith<IllegalArgumentException> { validateInventory(current + ("deprecatedSources" to (entries + entries.first()))) }
    }

    @Test fun changedDeclarationCountOrModuleOwnerRequiresARealInventoryReview() {
        val current = inventory
        val entries = sections(current["deprecatedSources"])
        for (patch in listOf(mapOf("declarationCount" to 100), mapOf("owner" to "flow-conformance-kit"),
            mapOf("removalTarget" to "AR-03D"), mapOf("exit" to ""))) {
            assertFailsWith<IllegalArgumentException> {
                validateInventory(current + ("deprecatedSources" to (listOf(entries.first() + patch) + entries.drop(1))))
            }
        }
    }

    @Test fun compatibilityEntriesCannotNameAnUnownedOrMissingProductionFile() {
        val current = inventory
        val entries = sections(current["compatibility"])
        assertFailsWith<IllegalArgumentException> {
            validateInventory(current + ("compatibility" to (listOf(entries.first() + ("path" to "src/main/kotlin/Absent.kt")) + entries.drop(1))))
        }
    }

    @Test fun sourceInspectionDoesNotCountDocumentationOrStringsAsDeprecatedDeclarations() {
        assertEquals(1, deprecatedCount("""
            // @Deprecated("documentation")
            val message = "@Deprecated(another example)"
            /* @Deprecated("nested /* comment */ example") */
            @Deprecated("actual") fun compatibility() = Unit
        """.trimIndent()))
    }

    @Test fun integratedDecisionDoesNotCloseCompatibilityOrActivateTheNextMilestone() {
        val decision = section(inventory["integratedDecision"])
        assertEquals(listOf("F-10"), decision["candidateClosesFindings"])
        assertEquals(listOf("F-20"), decision["containedFindings"])
        assertEquals("AR-07", decision["deferredClosureOwner"])
        assertEquals("not-activated", decision["nextItemActivation"])
        assertTrue(sections(inventory["reviewedRetention"]).all { it["reviewTarget"] == "AR-07" && it["decision"].toString().isNotBlank() })
    }

    private fun validateInventory(document: Map<String, Any?>) {
        require(document["version"] == "1.1" && document["workPackage"] == "AR-03D")
        val owners = section(ownership["modules"]).entries.flatMap { (module, value) ->
            strings(section(value)["sources"]).map { "src/main/kotlin/$it" to module.toString().removePrefix(":") }
        }.toMap()
        val actual = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.invariantSeparatorsPath to deprecatedCount(it.readText()) }.filter { it.second > 0 }.toMap()
        val entries = sections(document["deprecatedSources"])
        val recorded = entries.associate { it["path"].toString() to it["declarationCount"] }
        require(entries.size == recorded.size && recorded == actual) { "Deprecated declaration inventory differs from real Kotlin sources." }
        entries.forEach { item ->
            require(item["owner"] == owners[item["path"]]) { "Deprecated declaration owner differs from its actual Gradle module." }
            require(item["removalTarget"] == "AR-07" && item["symbols"].toString().isNotBlank() &&
                item["exit"] is String && item["exit"].toString().isNotBlank()) { "Missing reviewed removal decision." }
        }
        sections(document["compatibility"]).forEach { item ->
            val path = item["path"] as? String ?: error("Missing compatibility path")
            require(path in owners && File(path).isFile && item["owner"] == owners[path]) { "Unowned compatibility boundary." }
            require(item["removalTarget"] == "AR-07" && item["exit"] is String && item["exit"].toString().isNotBlank())
        }
    }

    private fun deprecatedCount(source: String) = Regex("@Deprecated\\b")
        .findAll(KotlinSourceBoundaryScanner.structuralSource(source)).count()
    private fun section(value: Any?): Map<*, *> = value as Map<*, *>
    private fun sections(value: Any?): List<Map<String, Any?>> = (value as List<*>).map { element ->
        (element as Map<*, *>).entries.associate { it.key.toString() to it.value }
    }
    private fun strings(value: Any?): List<String> = (value as List<*>).map { it as String }
}
