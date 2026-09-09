import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.architecture.CiCdBiasFollowUpArea
import org.flowlang.architecture.CiCdBiasInventoryAnalyzer
import org.flowlang.architecture.CiCdBiasLexicalContext

class FlowCiCdBiasInventoryTests {
    @Test
    fun repositoryInventoryIsPresentAndSemanticHealthIsActuallyClean() {
        val report = CiCdBiasInventoryAnalyzer(File(".")).analyze()

        assertTrue(report.scannedFiles > 50)
        assertEquals("PRESENT", report.inventoryStatus)
        assertTrue(report.evidence.isNotEmpty())
        assertEquals("PASS", report.healthStatus, report.actionableEvidence.joinToString(" | ") { evidence ->
            "${evidence.path}:${evidence.line}:${evidence.term}:${evidence.lexicalContext}:${evidence.snippet}"
        })
        assertEquals("PASS", report.status)
        assertEquals(emptyList(), report.actionableEvidence)
        assertTrue(report.evidence.none {
            it.lexicalContext in setOf(
                CiCdBiasLexicalContext.CATALOG_DECLARATION,
                CiCdBiasLexicalContext.COMPATIBILITY_SYMBOL,
                CiCdBiasLexicalContext.DIAGNOSTIC_LITERAL
            ) && it.actionable
        })
    }

    @Test
    fun onlyTheReviewedAdapterCompositionRootMayNameConcreteProviders() {
        val root = Files.createTempDirectory("flow-cicd-reference-composition").toFile()
        try {
            val directory = File(root, "src/main/kotlin/org/flowlang/distribution/reference")
            directory.mkdirs()
            File(directory, "ReferenceTargetProjections.kt").writeText("val provider = JenkinsManifestGenerator()")
            File(directory, "UnreviewedDefault.kt").writeText("val provider = JenkinsManifestGenerator()")
            val report = CiCdBiasInventoryAnalyzer(root).analyze()
            assertTrue(report.adapterBoundaryEvidence.any { it.path.endsWith("ReferenceTargetProjections.kt") })
            assertTrue(report.actionableEvidence.isNotEmpty())
            assertTrue(report.actionableEvidence.all { it.path.endsWith("UnreviewedDefault.kt") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adapterVocabularyIsInventoryButNotSemanticHealthFailure() {
        val root = Files.createTempDirectory("flow-cicd-adapter-inventory").toFile()
        try {
            val adapter = File(root, "src/main/kotlin/org/flowlang/targets/builtin/TargetAdapter.kt")
            adapter.parentFile.mkdirs()
            adapter.writeText(
                """
                package org.flowlang.targets.builtin
                class TargetAdapter { val target = "Jenkins" }
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("PRESENT", report.inventoryStatus)
            assertEquals("PASS", report.healthStatus)
            assertTrue(report.actionableEvidence.isEmpty())
            assertEquals(1, report.adapterBoundaryEvidence.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun topLevelAdapterEvidenceIsScannedWithoutBecomingCoreMeaning() {
        val root = Files.createTempDirectory("flow-cicd-top-level-adapter").toFile()
        try {
            val adapter = File(root, "adapters/example.yaml")
            adapter.parentFile.mkdirs()
            adapter.writeText("target: azure-devops\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertTrue(report.evidence.any { it.path == "adapters/example.yaml" })
            assertEquals("PASS", report.healthStatus)
            assertTrue(report.actionableEvidence.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun applicationCompositionConcreteTargetLiteralRequiresReview() {
        val root = Files.createTempDirectory("flow-cicd-application-composition").toFile()
        try {
            val cli = File(root, "src/main/kotlin/org/flowlang/cli/App.kt")
            cli.parentFile.mkdirs()
            cli.writeText("package org.flowlang.cli\nval provider = \"Jenkins\"\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertEquals(1, report.applicationCompositionEvidence.size)
            assertEquals(report.applicationCompositionEvidence, report.actionableEvidence)
            assertTrue(report.requiredFollowUpAreas.contains(CiCdBiasFollowUpArea.APPLICATION_COMPOSITION))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concreteImplementationDefaultInSemanticSourceRequiresReview() {
        val root = Files.createTempDirectory("flow-cicd-semantic-health").toFile()
        try {
            val semantic = File(root, "src/main/kotlin/org/flowlang/intent/SemanticDefault.kt")
            semantic.parentFile.mkdirs()
            semantic.writeText(
                """
                package org.flowlang.intent
                class SemanticDefault { val defaultTarget = "Jenkins" }
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("PRESENT", report.inventoryStatus)
            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertEquals(1, report.actionableEvidence.size)
            assertEquals(CiCdBiasInventoryAnalyzer.ACTIVE_SEMANTIC_SOURCE, report.actionableEvidence.single().classification)
            assertEquals(CiCdBiasLexicalContext.CONTROL_LITERAL, report.actionableEvidence.single().lexicalContext)
            assertTrue(report.requiredFollowUpAreas.contains(CiCdBiasFollowUpArea.SEMANTIC_MODEL))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun semanticPlatformLiteralRequiresReviewWithoutCallingItADefault() {
        val root = Files.createTempDirectory("flow-cicd-semantic-literal").toFile()
        try {
            val semantic = File(root, "src/main/kotlin/org/flowlang/intent/SemanticCapability.kt")
            semantic.parentFile.mkdirs()
            semantic.writeText("package org.flowlang.intent\nval capability = \"Kubernetes\"\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertEquals(CiCdBiasLexicalContext.SEMANTIC_LITERAL, report.actionableEvidence.single().lexicalContext)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetNameInExecutableIdentifierRequiresReview() {
        val root = Files.createTempDirectory("flow-cicd-semantic-identifier").toFile()
        try {
            val semantic = File(root, "src/main/kotlin/org/flowlang/intent/JenkinsMeaning.kt")
            semantic.parentFile.mkdirs()
            semantic.writeText("package org.flowlang.intent\nclass JenkinsMeaning\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertEquals(CiCdBiasLexicalContext.CODE_IDENTIFIER, report.actionableEvidence.single().lexicalContext)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interpolationBodyIsScannedAsExecutableCode() {
        val root = Files.createTempDirectory("flow-cicd-interpolation").toFile()
        try {
            val semantic = File(root, "src/main/kotlin/org/flowlang/intent/Interpolation.kt")
            semantic.parentFile.mkdirs()
            semantic.writeText(
                """
                package org.flowlang.intent
                val message = "${'$'}{KubernetesResolver.resolve()}"
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertTrue(report.actionableEvidence.any {
                it.term.equals("Kubernetes", ignoreCase = true) &&
                    it.lexicalContext == CiCdBiasLexicalContext.CODE_IDENTIFIER
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun commentsDiagnosticProseAndCatalogDeclarationsAreNotActionable() {
        val root = Files.createTempDirectory("flow-cicd-lexical-context").toFile()
        try {
            val semantic = File(root, "src/main/kotlin/org/flowlang/intent/Diagnostics.kt")
            semantic.parentFile.mkdirs()
            semantic.writeText(
                """
                package org.flowlang.intent
                // Jenkins must not become semantic authority.
                /* Docker is mentioned only in an explanatory comment. */
                val message = "Jenkins is not available for this target-neutral operation."
                val term = CiCdBiasTerm("Jenkins", "target", "catalog data")
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("PASS", report.healthStatus)
            assertTrue(report.actionableEvidence.isEmpty())
            assertTrue(report.evidence.any { it.lexicalContext == CiCdBiasLexicalContext.DIAGNOSTIC_LITERAL })
            assertTrue(report.evidence.any { it.lexicalContext == CiCdBiasLexicalContext.CATALOG_DECLARATION })
            assertTrue(report.evidence.none { it.snippet.startsWith("//") || it.snippet.startsWith("/*") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun compatibilityKotlinFileDoesNotCreateAFileWideExemption() {
        val root = Files.createTempDirectory("flow-cicd-compatibility").toFile()
        try {
            val compatibility = File(
                root,
                "src/main/kotlin/org/flowlang/intent/StandardCapabilityCompatibility.kt"
            )
            compatibility.parentFile.mkdirs()
            compatibility.writeText(
                "package org.flowlang.intent\nval KUBERNETES_MAINTENANCE = CLUSTER_MAINTENANCE\n"
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertTrue(report.actionableEvidence.any {
                it.classification == CiCdBiasInventoryAnalyzer.COMPATIBILITY_BOUNDARY &&
                    it.lexicalContext == CiCdBiasLexicalContext.CODE_IDENTIFIER
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun normativeSchemaPlatformEnumRequiresReview() {
        val root = Files.createTempDirectory("flow-cicd-schema").toFile()
        try {
            val schema = File(root, "schemas/intent.schema.json")
            schema.parentFile.mkdirs()
            schema.writeText("{\"enum\": [\"Kubernetes\"]}\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertEquals(CiCdBiasLexicalContext.STRUCTURED_CONTROL, report.actionableEvidence.single().lexicalContext)
            assertEquals("schemas/intent.schema.json", report.actionableEvidence.single().path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetNeutralMaterializationAuthorityIsNotHiddenByGeneratorsDirectory() {
        val root = Files.createTempDirectory("flow-cicd-neutral-generator-authority").toFile()
        try {
            val authority = File(
                root,
                "src/main/kotlin/org/flowlang/generators/manifest/MandatoryMaterializationAuthority.kt"
            )
            authority.parentFile.mkdirs()
            authority.writeText("package org.flowlang.generators.manifest\nclass KubernetesMaterialization\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertEquals(CiCdBiasInventoryAnalyzer.ACTIVE_SEMANTIC_SOURCE, report.actionableEvidence.single().classification)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetSpecificGeneratorRemainsAdapterBoundary() {
        val root = Files.createTempDirectory("flow-cicd-target-generator").toFile()
        try {
            val generator = File(
                root,
                "src/main/kotlin/org/flowlang/generators/manifest/JenkinsGenerator.kt"
            )
            generator.parentFile.mkdirs()
            generator.writeText("package org.flowlang.generators.manifest\nclass JenkinsGenerator\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("PASS", report.healthStatus)
            assertEquals(1, report.adapterBoundaryEvidence.size)
            assertTrue(report.actionableEvidence.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ordinaryAutomationVocabularyIsNotMechanismLevelBiasEvidence() {
        val catalogTerms = CiCdBiasInventoryAnalyzer.catalog().map { it.term.lowercase() }.toSet()

        assertFalse("build" in catalogTerms)
        assertFalse("deploy" in catalogTerms)
        assertFalse("deployment" in catalogTerms)
        assertFalse("pipeline" in catalogTerms)
        assertFalse("workflow" in catalogTerms)
        assertFalse("registry" in catalogTerms)
        assertFalse("runner" in catalogTerms)
        assertTrue("jenkins" in catalogTerms)
        assertTrue("docker" in catalogTerms)
        assertTrue("azure devops" in catalogTerms)
        assertTrue("gitlab" in catalogTerms)
        assertTrue("circleci" in catalogTerms)
        assertTrue("helm" in catalogTerms)
        assertTrue("kubectl" in catalogTerms)
        assertTrue("terraform" in catalogTerms)
        assertTrue("k8s" in catalogTerms)
    }

    @Test
    fun classifierKeepsAdapterProjectionSeparateFromSemanticCore() {
        val root = Files.createTempDirectory("flow-cicd-bias-inventory").toFile()
        try {
            val adapter = File(root, "src/main/kotlin/org/flowlang/targets/builtin/TargetAdapter.kt")
            val semantic = File(root, "src/main/kotlin/org/flowlang/intent/SemanticDefault.kt")
            adapter.parentFile.mkdirs()
            semantic.parentFile.mkdirs()
            adapter.writeText("package org.flowlang.targets.builtin\nclass TargetAdapter { val target = \"Jenkins\" }\n")
            semantic.writeText("package org.flowlang.intent\nclass SemanticDefault { val defaultTarget = \"Jenkins\" }\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals(1, report.adapterBoundaryEvidence.size)
            assertEquals("src/main/kotlin/org/flowlang/targets/builtin/TargetAdapter.kt", report.adapterBoundaryEvidence.single().path)
            assertEquals(1, report.activeSemanticEvidence.size)
            assertEquals("src/main/kotlin/org/flowlang/intent/SemanticDefault.kt", report.activeSemanticEvidence.single().path)
            assertEquals(report.activeSemanticEvidence, report.actionableEvidence)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyRepositoryHasEmptyInventoryAndPassingHealth() {
        val root = Files.createTempDirectory("flow-cicd-empty-inventory").toFile()
        try {
            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("EMPTY", report.inventoryStatus)
            assertEquals("PASS", report.healthStatus)
            assertTrue(report.evidence.isEmpty())
            assertTrue(report.requiredFollowUpAreas.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}