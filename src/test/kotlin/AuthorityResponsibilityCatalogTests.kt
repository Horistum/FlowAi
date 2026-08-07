import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.architecture.AuthorityResponsibilityCatalog

class AuthorityResponsibilityCatalogTests {
    @Test
    fun repositoryCatalogExactlyMatchesProductionAuthorityGraph() {
        val report = AuthorityResponsibilityCatalog(File(".")).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertTrue(report.authorityCount > 0)
    }

    @Test
    fun newAuthorityWithoutOwnershipEntryFailsClosed() {
        val root = catalogFixture()
        File(root, "src/main/kotlin/org/flowlang/architecture/UnownedFutureAuthority.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package org.flowlang.architecture
                object UnownedFutureAuthority { fun inspect() = Unit }
                object FutureCaller { fun call() = UnownedFutureAuthority.inspect() }
                """.trimIndent() + "\n"
            )
        }

        val report = AuthorityResponsibilityCatalog(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "UnownedFutureAuthority" in it && "missing" in it })
    }


    @Test
    fun duplicateAuthoritySimpleNameIsReportedAsArchitectureError() {
        val root = catalogFixture()
        File(root, "src/main/kotlin/org/flowlang/future/DuplicateTargetSelectionAuthority.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package org.flowlang.future
                class TargetSelectionAuthority
                class FutureCaller(private val authority: TargetSelectionAuthority)
                """.trimIndent() + "\n"
            )
        }

        val report = AuthorityResponsibilityCatalog(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any {
            "TargetSelectionAuthority" in it && "defined more than once" in it && "repository-unique" in it
        })
    }

    @Test
    fun changedProductionCallerRequiresInventoryReview() {
        val root = catalogFixture()
        File(root, "src/main/kotlin/org/flowlang/architecture/FutureSelectionCaller.kt").writeText(
            """
            package org.flowlang.architecture
            import org.flowlang.materialization.TargetSelectionAuthority
            class FutureSelectionCaller(private val authority: TargetSelectionAuthority)
            """.trimIndent() + "\n"
        )

        val report = AuthorityResponsibilityCatalog(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "TargetSelectionAuthority callers drifted" in it })
    }

    private fun catalogFixture(): File {
        val root = createTempDirectory("flow-ar01-catalog").toFile()
        File("src/main/kotlin").copyRecursively(File(root, "src/main/kotlin"), overwrite = true)
        File("standard/architecture/authority-responsibilities.yaml").copyTo(
            File(root, AuthorityResponsibilityCatalog.CATALOG_PATH).apply { parentFile.mkdirs() },
            overwrite = true
        )
        return root
    }
}
