import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.standard.StandardDiagnosticCatalog

class ArchitectureDiagnosticBoundaryTests {
    @Test fun realScannerEmissionMatchesTheCatalogAndNegativeCorpus() {
        val root = Files.createTempDirectory("architecture-diagnostic-boundary").toFile()
        try {
            File(root, "standard/architecture/forbidden-directions.yaml").apply {
                parentFile.mkdirs()
                writeText("forbiddenDirections:\n  - id: sdk-framework\n    forbiddenTerms: [ForbiddenBoundaryFixture]\n")
            }
            val source = File(root, "src/main/kotlin/example/Fixture.kt").apply {
                parentFile.mkdirs()
                writeText("package example\nclass Fixture { val instance = ForbiddenBoundaryFixture() }\n")
            }
            val expected = PublicStandardDraft.negativeCorpus().cases.single { it.id == "architecture-sdk-drift" }.expectedDiagnostic
            val reports = ArchitectureGovernanceAnalyzer(root).analyze().issues.filter { it.path.endsWith("Fixture.kt") }
            assertTrue(reports.isNotEmpty(), "The negative fixture must trigger the actual scanner.")
            assertTrue(reports.any { it.code == expected })
            val catalog = StandardDiagnosticCatalog.codes.associateBy { it.code }
            reports.forEach { assertTrue(it.code in catalog, "Uncataloged scanner diagnostic: ${it.code}") }
            source.writeText("package example\nclass Fixture { val message = \"ForbiddenBoundaryFixture\" }\n")
            assertFalse(ArchitectureGovernanceAnalyzer(root).analyze().issues.any { it.code == expected })
        } finally {
            root.deleteRecursively()
        }
    }
}
