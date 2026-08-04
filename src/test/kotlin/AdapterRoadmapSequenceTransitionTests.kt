package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence

class AdapterRoadmapSequenceTransitionTests {
    @Test
    fun terminalAdapterTrackAcceptsExplicitConformanceSuccessor() {
        assertTrue(
            AdapterRoadmapSequence.isIndexFocusAligned(
                primaryStream = "conformance",
                indexNextItem = "C0.1",
                indexNextStream = "conformance",
                adapterNextItem = ""
            )
        )
        assertTrue(
            AdapterRoadmapSequence.isReleaseFocusAligned(
                primaryStream = "conformance",
                releaseNextItem = "C0.1",
                adapterNextItem = ""
            )
        )
    }

    @Test
    fun activeAdapterTrackStillRequiresExactAdapterFocus() {
        assertTrue(
            AdapterRoadmapSequence.isIndexFocusAligned(
                primaryStream = "adapters",
                indexNextItem = "A0.7",
                indexNextStream = "adapters",
                adapterNextItem = "A0.7"
            )
        )
        assertFalse(
            AdapterRoadmapSequence.isIndexFocusAligned(
                primaryStream = "conformance",
                indexNextItem = "C0.1",
                indexNextStream = "conformance",
                adapterNextItem = "A0.7"
            )
        )
    }

    @Test
    fun terminalAdapterTrackRejectsFabricatedOrUnboundSuccessors() {
        assertFalse(AdapterRoadmapSequence.isIndexFocusAligned("adapters", "A0.8", "adapters", ""))
        assertFalse(AdapterRoadmapSequence.isIndexFocusAligned("conformance", "A0.8", "conformance", ""))
        assertFalse(AdapterRoadmapSequence.isIndexFocusAligned("conformance", "C0.1", "adapters", ""))
        assertFalse(AdapterRoadmapSequence.isIndexFocusAligned("other", "C0.1", "other", ""))
        assertFalse(AdapterRoadmapSequence.isReleaseFocusAligned("conformance", "", ""))
        assertFalse(AdapterRoadmapSequence.isReleaseFocusAligned("conformance", "A0.8", ""))
    }
}
