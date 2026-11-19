package org.flowlang.cli

import com.fasterxml.jackson.databind.MapperFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flowlang.continuity.StateLifetime
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanDependencyResolution

class PublishedDependencyOrderTests {
    @Test
    fun dependencyBytesRetainTheirOrderAcrossPropertyDiscoveryStrategies() {
        val relation = PlanDependencyRelation(
            sourceNodeId = "source", targetNodeId = "target", kind = PlanDependencyKind.STATE,
            channel = "state", stateLifetime = StateLifetime.DURABLE, evidence = PlanDependencyEvidence.MODULE_CONTRACT,
            resolution = PlanDependencyResolution.UNRESOLVED, path = listOf("source", "target"),
            candidates = listOf("store"), evidenceReference = "contract"
        )
        val expectedFields = listOf(
            "sourceNodeId", "targetNodeId", "kind", "channel", "stateLifetime", "evidence",
            "resolution", "path", "candidates", "evidenceReference", "continuity", "blocking"
        )
        val bytes = Json.bytes(relation)
        assertEquals(expectedFields, Json.mapper.readTree(bytes).fieldNames().asSequence().toList())
        // Explicit wire ordering must also override a different discovery default.
        val alternate = Json.mapper.copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        assertEquals(bytes.decodeToString(), alternate.writeValueAsString(relation))
    }
}
