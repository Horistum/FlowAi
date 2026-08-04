package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence

class AdapterRoadmapSequenceTransitionTests {
    @Test
    fun terminalA0HistoryDoesNotOwnUnrelatedGlobalFocus() {
        assertTrue(AdapterRoadmapSequence.isIndexFocusAligned("conformance", "C0.2", "conformance", ""))
        assertTrue(AdapterRoadmapSequence.isReleaseFocusAligned("conformance", "C0.2", ""))
    }

    @Test
    fun activeAdapterFocusMustMatchExactly() {
        assertTrue(AdapterRoadmapSequence.isTrackStatusAligned("active", "A0.7", "A1.0"))
        assertTrue(AdapterRoadmapSequence.isIndexFocusAligned("adapters", "A1.0", "adapters", "A1.0"))
        assertTrue(AdapterRoadmapSequence.isReleaseFocusAligned("adapters", "A1.0", "A1.0"))
        assertFalse(AdapterRoadmapSequence.isIndexFocusAligned("conformance", "A1.0", "conformance", "A1.0"))
    }

    @Test
    fun terminalA0RejectsFabricatedA08() {
        assertFalse(AdapterRoadmapSequence.isTrackStatusAligned("active", "A0.7", "A0.8"))
        assertFalse(AdapterRoadmapSequence.isHistoricalProgress("A0.7", "A0.8", 1))
        assertTrue(AdapterRoadmapSequence.isPostTerminalA0Focus(""))
        assertTrue(AdapterRoadmapSequence.isPostTerminalA0Focus("A1.0"))
        assertFalse(AdapterRoadmapSequence.isPostTerminalA0Focus("A0.8"))
    }
}
