package org.flowlang.adapters.portfolio

/**
 * Shared adapter-roadmap ordering contract.
 *
 * Historical lifecycle authorities accept the initial A0.1 focus, adjacent
 * active progress and the one declared terminal state. They never infer an
 * imaginary predecessor or successor merely to preserve one metadata shape.
 */
object AdapterRoadmapSequence {
    const val FIRST_ITEM: String = "A0.1"
    const val TERMINAL_ITEM: String = "A0.7"

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

    fun isHistoricalProgress(
        completedItem: String,
        nextItem: String,
        minimumCompletedOrdinal: Int
    ): Boolean {
        val completed = ordinal(completedItem) ?: return false
        if (completed < minimumCompletedOrdinal) return false
        return if (nextItem.isBlank()) {
            completedItem == TERMINAL_ITEM
        } else {
            ordinal(nextItem) == completed + 1
        }
    }

    fun isTrackStatusAligned(
        trackStatus: String,
        completedItem: String,
        nextItem: String
    ): Boolean = when {
        nextItem.isBlank() -> trackStatus == "completed" && completedItem == TERMINAL_ITEM
        completedItem.isBlank() -> trackStatus == "active" && nextItem == FIRST_ITEM
        else -> trackStatus == "active" && isAdjacentProgress(completedItem, nextItem, minimumCompletedOrdinal = 1)
    }

    fun isIndexFocusAligned(
        primaryStream: String,
        indexNextItem: String,
        indexNextStream: String,
        adapterNextItem: String
    ): Boolean = primaryStream == "adapters" &&
        indexNextItem == adapterNextItem &&
        if (adapterNextItem.isBlank()) indexNextStream.isBlank() else indexNextStream == "adapters"

    private val ITEM = Regex("A0\\.([1-9][0-9]*)")
}
