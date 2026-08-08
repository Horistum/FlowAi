package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.conformance.OperationalDomainCorpusLoader
import org.flowlang.conformance.OperationalEvidenceDomain
import org.flowlang.conformance.RealWorldCorpusRunner
import org.flowlang.conformance.RealWorldPolarity
import org.flowlang.conformance.RealWorldPolarityAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.targets.builtin.BuiltInTargetProjections

class OperationalDomainCorpusLoaderTests {
    private val rootDir = File(".")
    private val registry = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
    private val runner = RealWorldCorpusRunner(rootDir, registry, targets, BuiltInTargetProjections.registry)

    @Test
    fun repositoryCorpusIsBoundedAndIndependentFromC01() {
        val corpus = OperationalDomainCorpusLoader(rootDir).load()

        assertEquals(listOf(OperationalEvidenceDomain.DATA_PROTECTION.documentValue), corpus.manifest.scope.domains)
        assertEquals(listOf("BACKUP", "RESTORE"), corpus.manifest.scope.requiredCapabilities)
        assertEquals(2, corpus.manifest.sources.size)
        assertEquals(listOf("DP01", "DP02"), corpus.cases.map { it.definition.id })
        assertEquals(2, corpus.cases.sumOf { it.mutations.size })
        assertTrue(corpus.manifest.casePackages.none { it.contains("real-world") })
    }

    @Test
    fun operationalBaselinesAndMutationsUseTheProductionEvaluator() {
        val corpus = OperationalDomainCorpusLoader(rootDir).load()
        corpus.cases.forEach { case ->
            val baseline = runner.evaluate(case, OperationalDomainCorpusLoader.CASE_CHECK_PREFIX)
            assertTrue(baseline.accepted, "${case.definition.id}: ${baseline.mismatches.joinToString(" | ")}")
            assertEquals(RealWorldPolarity.REPRESENTABLE, RealWorldPolarityAuthority.classify(baseline))

            val mutations = runner.evaluateMutations(case)
            assertTrue(mutations.isNotEmpty(), "${case.definition.id} must own a negative mutation")
            mutations.forEach { mutation ->
                assertTrue(mutation.accepted, "${mutation.caseId}: ${mutation.mismatches.joinToString(" | ")}")
                val comparison = RealWorldPolarityAuthority.compare(baseline, mutation)
                assertTrue(comparison.flipped, "${mutation.caseId}: ${comparison.reason}")
            }
        }
    }
}
