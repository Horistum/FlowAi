import org.flowlang.adapters.testing.AdapterRuntimeTestFixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.honest.CliArtifact
import org.flowlang.cli.honest.CliArtifactRole
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.CliPresentation
import org.flowlang.cli.honest.CliProcessExit
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.materialization.TargetSelectionDecision
import org.flowlang.materialization.TargetSelectionOrigin
import org.flowlang.materialization.UnknownExplicitTargetSelectionException
import org.flowlang.materialization.UnsupportedExplicitConfigurationSourceException
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.BuiltInTargetProjections

class TargetSelectionAuthorityTests {
    private val targets = mapOf(
        "jenkins" to TargetCapability("jenkins", "Test target."),
        "github-actions" to TargetCapability("github-actions", "Test target.")
    )

    @Test
    fun absentCliOptionRemainsTargetNeutral() {
        assertEquals(
            TargetSelectionDecision.NotSelected,
            TargetSelectionAuthority.fromCliOption(null, targets)
        )
    }

    @Test
    fun explicitCliOptionProducesRegistryValidatedProvenance() {
        val selected = assertIs<TargetSelectionDecision.Selected>(
            TargetSelectionAuthority.fromCliOption("jenkins", targets)
        ).selection

        assertEquals("jenkins", selected.target)
        assertEquals(TargetSelectionOrigin.CLI_OPTION, selected.evidence.origin)
        assertEquals("cli:--target", selected.evidence.source)
    }

    @Test
    fun unknownExplicitTargetFailsAtTheSelectionAuthority() {
        assertFailsWith<UnknownExplicitTargetSelectionException> {
            TargetSelectionAuthority.fromCliOption("missing", targets)
        }
    }

    @Test
    fun targetSelectionOriginVocabularyIsClosed() {
        assertEquals(
            setOf(
                TargetSelectionOrigin.CLI_OPTION,
                TargetSelectionOrigin.INTENT_DECLARATION,
                TargetSelectionOrigin.EXPLICIT_CONFIGURATION
            ),
            TargetSelectionOrigin.entries.toSet()
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun arbitraryConfigurationSourceCannotForgeSelectionProvenance() {
        assertFailsWith<UnsupportedExplicitConfigurationSourceException> {
            AdapterRuntimeTestFixtures.fromExplicitConfiguration(
                "jenkins",
                "cli:compatibility-report",
                targets
            )
        }
    }

    @Test
    fun materializationAuthoritiesExposeNoRawTargetStringParameter() {
        listOf(
            TargetManifestGenerationPipeline::class.java,
            MandatoryMaterializationAuthority::class.java
        ).forEach { type ->
            val unsafe = type.declaredMethods.filter { method ->
                method.name in setOf("generate", "generateDiagnosticEvidence", "authorize", "authorizeDiagnosticEvidence") &&
                    method.parameterTypes.contains(String::class.java)
            }
            assertTrue(unsafe.isEmpty(), "${type.simpleName} still exposes raw target methods: $unsafe")
        }
    }

    @Test
    fun duplicateCliCompositionPipelineIsAbsentFromTheClasspath() {
        val loader = Thread.currentThread().contextClassLoader
        assertNull(loader.getResource("org/flowlang/cli/TargetManifestGenerationPipeline.class"))
        assertNotNull(loader.getResource("org/flowlang/generators/manifest/TargetManifestGenerationPipeline.class"))
    }

    @Test
    fun explicitSelectionImplementationCannotBeConstructedByCallers() {
        val implementation = AdapterRuntimeTestFixtures.fromTestFixture(
            "jenkins",
            "reflection",
            targets
        ).javaClass

        assertTrue(java.lang.reflect.Modifier.isPrivate(implementation.modifiers))
        assertEquals(TargetSelectionAuthority::class.java, implementation.enclosingClass)
        assertEquals(1, org.flowlang.materialization.ExplicitTargetSelection::class.sealedSubclasses.size)
    }

    @Test
    fun targetedCliResultRequiresSelectionEvidenceAndDerivesProcessStatus() {
        val modules = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
        val plan = FlowPlanner(modules).plan(FlowParser().parse(File("examples/api-sync.flow")))
        val selected = AdapterRuntimeTestFixtures.fromTestFixture(
            "jenkins",
            "targeted-result",
            targets
        )
        val evidence = CliTargetEvidenceAuthority(targets, BuiltInTargetProjections.registry)
            .evaluate(plan, selected, strict = false, renderRequested = false)
        val artifacts = listOf(
            CliArtifact("target-selection-evidence.json", CliArtifactRole.DIAGNOSTIC_EVIDENCE, false),
            CliArtifact("target-manifest.json", CliArtifactRole.TARGET_MANIFEST, false)
        )

        val review = CliExecutionResult.Targeted(
            selection = selected,
            evidence = evidence,
            strict = false,
            renderRequested = false,
            presentation = CliPresentation(),
            artifacts = artifacts
        )
        val reviewGate = CliExecutionResult.Targeted(
            selection = selected,
            evidence = evidence,
            strict = true,
            renderRequested = false,
            presentation = CliPresentation(),
            artifacts = artifacts
        )
        val blocked = CliExecutionResult.Targeted(
            selection = selected,
            evidence = evidence.copy(outcome = CliTargetEvidenceOutcome.BLOCKED),
            strict = false,
            renderRequested = false,
            presentation = CliPresentation(),
            artifacts = artifacts
        )

        assertEquals("jenkins", review.selection.target)
        assertEquals(CliTargetEvidenceOutcome.REVIEW_ONLY, review.evidence.outcome)
        assertFalse(review.artifacts.any { it.role == CliArtifactRole.RENDERED_TARGET })
        assertEquals(CliProcessExit.SUCCESS.code, review.exitCode)
        assertEquals(CliProcessExit.REVIEW_REQUIRED.code, reviewGate.exitCode)
        assertEquals(CliProcessExit.BLOCKED.code, blocked.exitCode)
        assertEquals(
            CliProcessExit.SUCCESS.code,
            CliExecutionResult.Completed(CliPresentation()).exitCode
        )
    }
}
