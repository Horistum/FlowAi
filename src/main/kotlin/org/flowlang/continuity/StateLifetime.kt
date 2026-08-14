package org.flowlang.continuity

/**
 * Target-neutral lifetime required for mutable state continuity.
 *
 * WORKFLOW requires state to remain available only while the current workflow
 * execution carries it between dependent nodes. DURABLE requires persistence
 * beyond that local transfer boundary, including execution suspension or a
 * process/executor boundary where in-memory workflow state is insufficient.
 */
enum class StateLifetime(val wireName: String) {
    WORKFLOW("workflow"),
    DURABLE("durable");

    companion object {
        private val byWireName = entries.associateBy(StateLifetime::wireName)
        fun fromWireName(value: String): StateLifetime? = byWireName[value.trim().lowercase()]
    }
}
