import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.architecture.AuthorityResponsibilityCatalog

class AuthorityResponsibilityCatalogCritiqueRegressionTests {
    @Test
    fun selfFactoryInsideAuthorityBodyDoesNotSatisfyProductionLiveness() {
        val root = fixture(
            name = "SelfOnlyAuthority",
            source = """
                package org.flowlang.future
                class SelfOnlyAuthority private constructor() {
                    companion object {
                        fun create(): SelfOnlyAuthority = SelfOnlyAuthority()
                    }
                }
            """.trimIndent(),
            callers = listOf("src/main/kotlin/org/flowlang/future/SelfOnlyAuthority.kt")
        )

        val report = AuthorityResponsibilityCatalog(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "SelfOnlyAuthority has no production use outside its own Authority declaration" in it })
    }

    @Test
    fun siblingOwnerInSameFileIsARealColocatedProductionUse() {
        val root = fixture(
            name = "ColocatedIntegrityAuthority",
            source = """
                package org.flowlang.future
                object ColocatedIntegrityAuthority {
                    fun validate() = Unit
                }
                object ColocatedOwner {
                    fun analyze() = ColocatedIntegrityAuthority.validate()
                }
            """.trimIndent(),
            callers = listOf("src/main/kotlin/org/flowlang/future/ColocatedIntegrityAuthority.kt")
        )

        val report = AuthorityResponsibilityCatalog(root).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(1, report.authorityCount)
    }

    @Test
    fun injectedSiblingUseOutsideAuthorityBodyCountsAsProductionUse() {
        val root = fixture(
            name = "InjectedBoundaryAuthority",
            source = """
                package org.flowlang.future
                class InjectedBoundaryAuthority {
                    fun validate() = Unit
                }
                class ColocatedConsumer(private val authority: InjectedBoundaryAuthority) {
                    fun analyze() = authority.validate()
                }
            """.trimIndent(),
            callers = listOf("src/main/kotlin/org/flowlang/future/InjectedBoundaryAuthority.kt")
        )

        val report = AuthorityResponsibilityCatalog(root).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(1, report.authorityCount)
    }

    @Test
    fun duplicateDeclarationDoesNotInflateAuthorityIdentityCount() {
        val root = fixture(
            name = "DuplicateIdentityAuthority",
            source = """
                package org.flowlang.future
                object DuplicateIdentityAuthority
                class Sibling {
                    fun call() = DuplicateIdentityAuthority
                }
            """.trimIndent(),
            callers = listOf("src/main/kotlin/org/flowlang/future/DuplicateIdentityAuthority.kt")
        )
        File(root, "src/main/kotlin/org/flowlang/other/DuplicateIdentityAuthority.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package org.flowlang.other
                object DuplicateIdentityAuthority
                """.trimIndent() + "\n"
            )
        }

        val report = AuthorityResponsibilityCatalog(root).analyze()

        assertEquals("FAIL", report.status)
        assertEquals(1, report.authorityCount)
        assertTrue(report.errors.any { "DuplicateIdentityAuthority" in it && "defined more than once" in it })
    }

    private fun fixture(name: String, source: String, callers: List<String>): File {
        val root = createTempDirectory("flow-ar01-critique").toFile()
        val sourcePath = "src/main/kotlin/org/flowlang/future/$name.kt"
        File(root, sourcePath).apply {
            parentFile.mkdirs()
            writeText(source + "\n")
        }
        val callersYaml = callers.joinToString("\n") { "      - \"$it\"" }
        File(root, AuthorityResponsibilityCatalog.CATALOG_PATH).apply {
            parentFile.mkdirs()
            writeText(
                """
                version: "1.0"
                purpose: "test"
                authorities:
                  - name: "$name"
                    path: "$sourcePath"
                    role: "evidence-integrity-owner"
                    invariant: "test invariant"
                    inputs: "test inputs"
                    outputs: "test outputs"
                    callers:
                $callersYaml
                """.trimIndent() + "\n"
            )
        }
        return root
    }
}
