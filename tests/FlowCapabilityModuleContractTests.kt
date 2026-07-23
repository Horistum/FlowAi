package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.modules.CanonicalModuleLoader
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import java.io.File

class FlowCapabilityModuleContractTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun capabilityModuleContractReportHasNoBlockingErrors() {
        val report = ModuleContractAnalyzer.analyze(registry)
        assertTrue(report.valid, report.issues.filter { it.level == "error" }.joinToString { it.code + ": " + it.message })
        assertTrue(report.totals.modules >= 5)
        assertTrue(report.totals.actions > 0)
    }

    @Test
    fun moduleDescriptorsCannotOwnTargetImplications() {
        val error = assertFailsWith<CanonicalModuleLoader.ContractException> {
            CanonicalModuleLoader.loadText(
                """
                kind: FlowModule
                name: invalid-target-owner
                version: "1.0"
                description: Invalid module target ownership probe
                systemTypes:
                  service:
                    input: {}
                actions:
                  deploy:
                    kind: action
                    targetTypes: [service]
                    input: {}
                    output: {}
                    effects:
                      reads: []
                      writes: []
                      creates: []
                      updates: []
                      deletes: []
                      executes: []
                      network: []
                      filesystem: []
                    safety:
                      destructive: false
                      requires: []
                    targetImplications:
                      jenkins:
                        support: supported
                """.trimIndent(),
                "invalid-target-owner.yaml"
            )
        }

        assertTrue(error.message.orEmpty().contains("cannot declare targetImplications"))
    }

    @Test
    fun destructiveActionsRequireSafety() {
        val report = ModuleContractAnalyzer.analyze(registry)
        val destructive = report.modules.flatMap { it.actions }.filter { it.destructive }
        assertTrue(destructive.isNotEmpty())
        assertTrue(destructive.all { it.requiresSafety })
    }
}
