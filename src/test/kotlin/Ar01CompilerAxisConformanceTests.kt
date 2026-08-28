package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.Ar01CompilerAxisConformanceChecks

class Ar01CompilerAxisConformanceTests {
    @Test
    fun repositoryCompilerAxisPassesEveryBoundedAr01Check() {
        val checks = Ar01CompilerAxisConformanceChecks(File(".")).checks()
        assertTrue(
            checks.all { it.passed },
            checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" }
        )
    }

    @Test
    fun directIntentPlannerBypassFailsConvergence() {
        val root = copiedFixture()
        val cli = File(root, HONEST_CLI)
        cli.writeText(
            cli.readText().replace(
                "IntentYamlFrontend(FlowCompilationService(registry))",
                "IntentToAstPlanner(registry)"
            )
        )

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.INTENT_CONVERGENCE_CHECK }
        assertEquals(false, check.passed)
        assertTrue(check.message.orEmpty().contains("does not route through") || check.message.orEmpty().contains("direct compiler-stage"))
    }

    @Test
    fun missingProductEntrypointFailsInventory() {
        val root = copiedFixture()
        val inventory = File(root, INVENTORY)
        inventory.writeText(
            inventory.readText().replace(
                "  - \"src/main/kotlin/org/flowlang/conformance/ReferenceSnapshotBundleGenerator.kt#generate\"\n",
                ""
            )
        )

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.INVENTORY_CHECK }
        assertEquals(false, check.passed)
    }

    @Test
    fun unlistedDirectCompilationPathFailsInventory() {
        val root = copiedFixture()
        File(root, "src/main/kotlin/org/flowlang/product/Bypass.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package org.flowlang.product

                import org.flowlang.intent.IntentToAstPlanner
                import org.flowlang.planner.FlowPlanner

                fun bypass() {
                    IntentToAstPlanner(registry)
                    FlowPlanner(registry)
                }
                """.trimIndent()
            )
        }

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.INVENTORY_CHECK }
        assertEquals(false, check.passed)
        assertTrue(check.message.orEmpty().contains("unlisted="))
    }

    @Test
    fun directReviewedProposalReviewBypassFailsConvergence() {
        val root = copiedFixture()
        val cli = File(root, HONEST_CLI)
        cli.writeText(
            cli.readText().replace(
                "ReviewedAiProposalFrontend(FlowCompilationService(registry))",
                "IntentProposalReview(registry)"
            )
        )

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.REVIEWED_AI_CONVERGENCE_CHECK }
        assertEquals(false, check.passed)
        assertTrue(
            check.message.orEmpty().contains("does not route through") ||
                check.message.orEmpty().contains("outside FlowCompilationService") ||
                check.message.orEmpty().contains("direct compiler-stage"),
            check.message
        )
    }

    @Test
    fun compilerImportingConcreteTargetLayerFailsDirectionCheck() {
        val root = copiedFixture()
        val service = File(root, FLOW_COMPILATION_SERVICE)
        service.writeText(
            service.readText().replace(
                "package org.flowlang.compiler\n",
                "package org.flowlang.compiler\n\nimport org.flowlang.targets.builtin.BuiltInTargetProjections\n"
            )
        )

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.DEPENDENCY_DIRECTION_CHECK }
        assertEquals(false, check.passed)
        assertTrue(check.message.orEmpty().contains("forbidden production layer"), check.message)
    }

    @Test
    fun sourceCaptureWithoutStrictUtf8FailsDirectionCheck() {
        val root = copiedFixture()
        val contracts = File(root, COMPILATION_CONTRACTS)
        contracts.writeText(contracts.readText().replace("CodingErrorAction.REPORT", "CodingErrorAction.REPLACE"))

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.DEPENDENCY_DIRECTION_CHECK }
        assertEquals(false, check.passed)
        assertTrue(check.message.orEmpty().contains("CodingErrorAction.REPORT"), check.message)
    }

    @Test
    fun compilationInputConstructedOutsideItsFrontendFailsDirectionCheck() {
        val root = copiedFixture()
        File(root, "src/main/kotlin/org/flowlang/product/IntentBypass.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package org.flowlang.product

                fun bypass() = IntentCompilationInput(source, intent)
                """.trimIndent()
            )
        }

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.DEPENDENCY_DIRECTION_CHECK }
        assertEquals(false, check.passed)
        assertTrue(check.message.orEmpty().contains("IntentCompilationInput("), check.message)
        assertTrue(check.message.orEmpty().contains("IntentBypass.kt"), check.message)
    }

    @Test
    fun reviewedProposalInputConstructedOutsideItsFrontendFailsDirectionCheck() {
        val root = copiedFixture()
        File(root, "src/main/kotlin/org/flowlang/product/AiProposalBypass.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package org.flowlang.product

                fun bypass() = ReviewedAiProposalCompilationInput(source, providerId, request, response)
                """.trimIndent()
            )
        }

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.DEPENDENCY_DIRECTION_CHECK }
        assertEquals(false, check.passed)
        assertTrue(check.message.orEmpty().contains("ReviewedAiProposalCompilationInput("), check.message)
        assertTrue(check.message.orEmpty().contains("AiProposalBypass.kt"), check.message)
    }

    @Test
    fun notesSemanticGraphImportFailsDirectionCheck() {
        val root = copiedFixture()
        val service = File(root, FLOW_COMPILATION_SERVICE)
        service.writeText(
            service.readText().replace(
                "package org.flowlang.compiler\n",
                "package org.flowlang.compiler\n\nimport org.flowlang.semantic.SemanticActionGraph\n"
            )
        )

        val check = Ar01CompilerAxisConformanceChecks(root).checks()
            .single { it.name == Ar01CompilerAxisConformanceChecks.DEPENDENCY_DIRECTION_CHECK }
        assertEquals(false, check.passed)
        assertTrue(check.message.orEmpty().contains("notes-backed SemanticActionGraph"), check.message)
    }

    private fun copiedFixture(): File {
        val root = createTempDirectory("flow-ar01-compiler-axis").toFile()
        REQUIRED_PATHS.forEach { path ->
            val source = File(path)
            require(source.isFile) { "Test fixture source is missing: $path" }
            File(root, path).apply {
                parentFile.mkdirs()
                writeBytes(source.readBytes())
            }
        }
        return root
    }

    companion object {
        private const val INVENTORY = "architecture-recovery/ar-01/compiler-entrypoint-inventory.yaml"
        private const val COMPILATION_CONTRACTS = "src/main/kotlin/org/flowlang/compiler/CompilationContracts.kt"
        private const val FLOW_COMPILATION_SERVICE = "src/main/kotlin/org/flowlang/compiler/FlowCompilationService.kt"
        private const val STANDARD_CLI = "src/main/kotlin/org/flowlang/cli/honest/StandardCliCommands.kt"
        private const val HONEST_CLI = "src/main/kotlin/org/flowlang/cli/honest/HonestFlowCli.kt"
        private const val REFERENCE_SNAPSHOT = "src/main/kotlin/org/flowlang/conformance/ReferenceSnapshotBundleGenerator.kt"
        private val REQUIRED_PATHS = listOf(
            INVENTORY,
            COMPILATION_CONTRACTS,
            FLOW_COMPILATION_SERVICE,
            "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraph.kt",
            "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraphBindings.kt",
            "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraphBuilder.kt",
            "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraphDigest.kt",
            "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraphPlanMappings.kt",
            "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraphProjection.kt",
            "src/main/kotlin/org/flowlang/compiler/CanonicalExecutionGraphValidator.kt",
            "src/main/kotlin/org/flowlang/compiler/CompilationAuthorization.kt",
            "src/main/kotlin/org/flowlang/frontend/source/FlowSourceFrontend.kt",
            "src/main/kotlin/org/flowlang/frontend/intent/IntentYamlFrontend.kt",
            "src/main/kotlin/org/flowlang/frontend/ai/ReviewedAiProposalFrontend.kt",
            "src/main/kotlin/org/flowlang/ai/normalization/IntentProposalReview.kt",
            "src/main/kotlin/org/flowlang/materialization/TargetSelection.kt",
            STANDARD_CLI,
            HONEST_CLI,
            REFERENCE_SNAPSHOT,
            "src/main/kotlin/org/flowlang/conformance/ConformanceCheckSupport.kt",
            "src/main/kotlin/org/flowlang/conformance/TargetNeutralConformanceFixture.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusRunner.kt",
            "src/main/kotlin/org/flowlang/conformance/CorePipelineSnapshotChecks.kt",
            "src/main/kotlin/org/flowlang/conformance/CliReleaseHonestyChecks.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterBindingConformanceChecks.kt",
            "src/main/kotlin/org/flowlang/conformance/ScenarioAndPlanChecks.kt",
            "src/main/kotlin/org/flowlang/conformance/ClosureBlockingIntegrityChecks.kt",
            "src/main/kotlin/org/flowlang/conformance/ReferenceCorpusExecutionHarness.kt",
            "src/main/kotlin/org/flowlang/conformance/AbstractTopologyMatrixAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/StandardArchitectureNormalizationChecks.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterArtifactRenderingConformanceChecks.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterTriggerConformanceChecks.kt"
        )
    }
}
