import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.conformance.AdapterTargetMaturityConformanceChecks
import org.flowlang.conformance.ArchitectureRecoveryConformanceRunner
import org.flowlang.conformance.ConformanceRunner

class TargetMaturityCompositionTests {
    @Test
    fun targetsCommandPublishesCurrentMaturityReport() {
        val source = File(
            "src/main/kotlin/org/flowlang/cli/honest/StandardCliCommands.kt"
        ).readText()

        assertTrue(source.contains("AdapterTargetMaturityPublisher"))
        assertTrue(source.contains("FLOW TARGET MATURITY REPORT"))
    }

    @Test
    fun standaloneConformanceIncludesArchitectureRecoveryPhase() {
        val summary = ConformanceRunner().run()
        val names = summary.checks.map { it.name }
        val inventoryIndex = names.indexOf(ArchitectureRecoveryConformanceRunner.INVENTORY_CHECK)
        val publicationIndex = names.indexOf(
            AdapterTargetMaturityConformanceChecks.PUBLICATION_CHECK
        )

        assertTrue(inventoryIndex >= 0, names.joinToString())
        assertTrue(publicationIndex > inventoryIndex, names.joinToString())
        assertTrue(
            summary.checks
                .filter { it.name.startsWith("architecture-recovery.") }
                .all { it.passed },
            summary.checks.filterNot { it.passed }.joinToString { "${it.name}:${it.message}" }
        )
    }
}
