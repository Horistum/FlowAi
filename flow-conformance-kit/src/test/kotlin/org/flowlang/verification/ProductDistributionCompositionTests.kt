package org.flowlang.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductDistributionCompositionTests {
    @Test fun productArtifactsCliAndVerificationHaveDistinctActualCompilerOutputs() {
        val ownership = mapOf(
            "org.flowlang.artifacts.StandardSurface" to "flow-standard-artifacts",
            "org.flowlang.cli.honest.CliCommandCatalog" to "flow-cli",
            "org.flowlang.distribution.reference.ReferenceStandardArtifacts" to "flow-reference-distribution",
            "org.flowlang.cli.Json" to "flow-frontends",
            "org.flowlang.conformance.ConformanceRunner" to "flow-conformance-kit",
            "org.flowlang.verification.VerificationCommands" to "flow-conformance-kit"
        )
        val outputs = ownership.map { (name, owner) ->
            val type = Class.forName(name)
            val origin = type.protectionDomain.codeSource.location.toExternalForm()
            assertTrue(Regex("${Regex.escape(owner)}(?:/|-[0-9])").containsMatchIn(origin), "$name: $origin is not $owner")
            val resources = type.classLoader.getResources(name.replace('.', '/') + ".class").toList()
            assertEquals(1, resources.size, "$name has duplicate runtime definitions: $resources")
            owner to origin
        }
        assertEquals(5, outputs.map { it.second }.toSet().size)
        assertEquals(1, outputs.filter { it.first == "flow-conformance-kit" }.map { it.second }.toSet().size)
    }
}
