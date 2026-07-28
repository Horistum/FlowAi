package org.flowlang.adapters.portfolio

/**
 * Shared adapter-roadmap ordering contract.
 *
 * Lifecycle authorities own the historical evidence for one item. They must not
 * freeze the global roadmap pointer forever after that item completes. Current
 * progress is valid only when completedItem and nextItem form one adjacent A0.x
 * step at or beyond the item guarded by the authority.
 */
object AdapterRoadmapSequence {
    fun ordinal(item: String): Int? = ITEM.matchEntire(item)?.groupValues?.get(1)?.toIntOrNull()

    fun isAdjacentProgress(
        completedItem: String,
        nextItem: String,
        minimumCompletedOrdinal: Int
    ): Boolean {
        val completed = ordinal(completedItem) ?: return false
        val next = ordinal(nextItem) ?: return false
        return completed >= minimumCompletedOrdinal && next == completed + 1
    }

    private val ITEM = Regex("A0\\.([1-9][0-9]*)")
}
