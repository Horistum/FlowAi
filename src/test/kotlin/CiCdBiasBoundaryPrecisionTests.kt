import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.architecture.CiCdBiasInventoryAnalyzer
import org.flowlang.architecture.CiCdBiasLexicalContext

class CiCdBiasBoundaryPrecisionTests {
    @Test
    fun descriptiveCatalogExemptsOnlyExplicitModuleDeclarations() {
        withTemporaryRoot("flow-catalog-boundary") { root ->
            write(
                root,
                "src/main/kotlin/org/flowlang/standard/StandardIntentCatalog.kt",
                """
                package org.flowlang.standard

                private fun catalogModules(vararg ids: String) = ids.toList()
                private val modules = catalogModules("kubernetes")
                private val defaultTarget = "jenkins"
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()
            val kubernetes = report.evidence.single { it.term.equals("Kubernetes", ignoreCase = true) }
            val jenkins = report.evidence.single { it.term.equals("Jenkins", ignoreCase = true) }

            assertEquals(CiCdBiasLexicalContext.CATALOG_DECLARATION, kubernetes.lexicalContext)
            assertFalse(kubernetes.actionable)
            assertEquals(CiCdBiasLexicalContext.CONTROL_LITERAL, jenkins.lexicalContext)
            assertTrue(jenkins.actionable)
            assertEquals("REVIEW_REQUIRED", report.healthStatus)
        }
    }

    @Test
    fun compatibilityManifestExemptsOnlyRetiredSourceField() {
        withTemporaryRoot("flow-compatibility-boundary") { root ->
            write(
                root,
                "src/main/resources/standard/compatibility/capability-aliases.yaml",
                """
                schemaVersion: 1
                aliases:
                  - source: KUBERNETES_MAINTENANCE
                    canonical: CLUSTER_MAINTENANCE
                defaultTarget: Jenkins
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()
            val kubernetes = report.evidence.single { it.term.equals("Kubernetes", ignoreCase = true) }
            val jenkins = report.evidence.single { it.term.equals("Jenkins", ignoreCase = true) }

            assertEquals(CiCdBiasLexicalContext.COMPATIBILITY_SYMBOL, kubernetes.lexicalContext)
            assertFalse(kubernetes.actionable)
            assertEquals(CiCdBiasLexicalContext.CONTROL_LITERAL, jenkins.lexicalContext)
            assertTrue(jenkins.actionable)
        }
    }

    @Test
    fun deprecatedKotlinAliasExemptsOnlyCompatibilitySymbolDeclaration() {
        withTemporaryRoot("flow-kotlin-compatibility-boundary") { root ->
            write(
                root,
                "src/main/kotlin/org/flowlang/intent/StandardCapabilityCompatibility.kt",
                """
                package org.flowlang.intent

                val StandardCapability.Companion.KUBERNETES_MAINTENANCE: StandardCapability
                    get() = StandardCapability.CLUSTER_MAINTENANCE
                private val defaultTarget = "Jenkins"
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()
            val kubernetes = report.evidence.single { it.term.equals("Kubernetes", ignoreCase = true) }
            val jenkins = report.evidence.single { it.term.equals("Jenkins", ignoreCase = true) }

            assertEquals(CiCdBiasLexicalContext.COMPATIBILITY_SYMBOL, kubernetes.lexicalContext)
            assertFalse(kubernetes.actionable)
            assertEquals(CiCdBiasLexicalContext.CONTROL_LITERAL, jenkins.lexicalContext)
            assertTrue(jenkins.actionable)
            assertEquals("REVIEW_REQUIRED", report.healthStatus)
        }
    }

    @Test
    fun newPlatformKotlinAliasesAreNotCompatibilitySymbols() {
        withTemporaryRoot("flow-kotlin-rogue-compatibility-aliases") { root ->
            write(
                root,
                "src/main/kotlin/org/flowlang/intent/StandardCapabilityCompatibility.kt",
                """
                package org.flowlang.intent

                val StandardCapability.Companion.JENKINS_PIPELINE: StandardCapability
                    get() = StandardCapability.CLUSTER_MAINTENANCE
                val StandardCapability.Companion.DOCKER_BUILD: StandardCapability
                    get() = StandardCapability.CLUSTER_MAINTENANCE
                val StandardCapability.Companion.TEKTON_TASK: StandardCapability
                    get() = StandardCapability.CLUSTER_MAINTENANCE
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()
            val rogueTerms = setOf("Jenkins", "Docker", "Tekton")
            val rogueEvidence = report.evidence.filter { evidence ->
                rogueTerms.any { it.equals(evidence.term, ignoreCase = true) }
            }

            assertEquals(rogueTerms, rogueEvidence.map { it.term }.toSet())
            assertTrue(rogueEvidence.all { it.lexicalContext == CiCdBiasLexicalContext.CODE_IDENTIFIER })
            assertTrue(rogueEvidence.all { it.actionable })
            assertEquals("REVIEW_REQUIRED", report.healthStatus)
        }
    }

    @Test
    fun concretePredicateLiteralRequiresReview() {
        withTemporaryRoot("flow-predicate-literal") { root ->
            write(
                root,
                "src/main/kotlin/org/flowlang/intent/PlatformPredicate.kt",
                """
                package org.flowlang.intent

                fun isConcrete(value: String): Boolean = value.contains("kubernetes")
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()
            val evidence = report.actionableEvidence.single { it.term.equals("Kubernetes", ignoreCase = true) }

            assertEquals(CiCdBiasLexicalContext.CONTROL_LITERAL, evidence.lexicalContext)
            assertEquals("REVIEW_REQUIRED", report.healthStatus)
        }
    }

    @Test
    fun interpolationLexerIgnoresNestedStringBracesAndStopsSimpleNameAtDot() {
        withTemporaryRoot("flow-interpolation-lexer") { root ->
            write(
                root,
                "src/main/kotlin/org/flowlang/intent/InterpolationSample.kt",
                """
                package org.flowlang.intent

                fun render(runtime: String, foo: (String) -> String): String {
                    val nested = "${'$'}{foo("}")}"
                    val defaultTarget = "Jenkins"
                    return nested + "${'$'}runtime.Kubernetes"
                }
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()
            val jenkins = report.evidence.single { it.term.equals("Jenkins", ignoreCase = true) }
            val kubernetes = report.evidence.single { it.term.equals("Kubernetes", ignoreCase = true) }

            assertTrue(jenkins.actionable)
            assertEquals(CiCdBiasLexicalContext.CONTROL_LITERAL, jenkins.lexicalContext)
            assertFalse(kubernetes.actionable)
            assertEquals(CiCdBiasLexicalContext.STRING_LITERAL, kubernetes.lexicalContext)
        }
    }

    private fun withTemporaryRoot(prefix: String, block: (File) -> Unit) {
        val root = Files.createTempDirectory(prefix).toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content + "\n")
        }
    }
}
