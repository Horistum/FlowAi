package org.flowlang.adapters.portfolio

/**
 * Adapter-local ordering contract.
 *
 * This authority validates A0 history and an explicitly declared adapter-local
 * A1.0 successor. It deliberately does not decide which unrelated roadmap stream
 * follows a terminal adapter state; that decision belongs to the global roadmap
 * transition authority.
 */
object AdapterRoadmapSequence {
    const val FIRST_ITEM: String = "A0.1"
    const val TERMINAL_ITEM: String = "A0.7"
    const val NEXT_SERIES_FIRST_ITEM: String = "A1.0"

    fun ordinal(item: String): Int? = A0_ITEM.matchEntire(item)?.groupValues?.get(1)?.toIntOrNull()

    fun isAdjacentProgress(
        completedItem: String,
        nextItem: String,
        minimumCompletedOrdinal: Int
    ): Boolean {
        val completed = ordinal(completedItem) ?: return false
        val next = ordinal(nextItem) ?: return false
        return completed in minimumCompletedOrdinal until TERMINAL_ORDINAL && next == completed + 1
    }

    fun isHistoricalProgress(
        completedItem: String,
        nextItem: String,
        minimumCompletedOrdinal: Int
    ): Boolean {
        val completed = ordinal(completedItem) ?: return false
        if (completed < minimumCompletedOrdinal) return false
        if (completedItem == TERMINAL_ITEM) return isPostTerminalA0Focus(nextItem)
        return isAdjacentProgress(completedItem, nextItem, minimumCompletedOrdinal)
    }

    fun isTrackStatusAligned(
        trackStatus: String,
        completedItem: String,
        nextItem: String
    ): Boolean = when {
        completedItem == TERMINAL_ITEM && nextItem == NEXT_SERIES_FIRST_ITEM -> trackStatus == "active"
        completedItem == TERMINAL_ITEM && nextItem.isBlank() -> trackStatus == "completed"
        completedItem.isBlank() -> trackStatus == "active" && nextItem == FIRST_ITEM
        else -> trackStatus == "active" && isAdjacentProgress(completedItem, nextItem, minimumCompletedOrdinal = 1)
    }

    fun isIndexFocusAligned(
        primaryStream: String,
        indexNextItem: String,
        indexNextStream: String,
        adapterNextItem: String
    ): Boolean = if (adapterNextItem.isBlank()) {
        true
    } else {
        primaryStream == "adapters" &&
            indexNextItem == adapterNextItem &&
            indexNextStream == "adapters"
    }

    fun isReleaseFocusAligned(
        primaryStream: String,
        releaseNextItem: String,
        adapterNextItem: String
    ): Boolean = adapterNextItem.isBlank() ||
        (primaryStream == "adapters" && releaseNextItem == adapterNextItem)

    fun isPostTerminalA0Focus(nextItem: String): Boolean =
        nextItem.isBlank() || nextItem == NEXT_SERIES_FIRST_ITEM

    private const val TERMINAL_ORDINAL = 7
    private val A0_ITEM = Regex("A0\\.([1-9][0-9]*)")
}
