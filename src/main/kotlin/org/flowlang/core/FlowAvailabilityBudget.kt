package org.flowlang.core

import org.flowlang.ast.SourceLocation

/** Compiler-owned limits, independent of frontend input limits and JVM heap size. */
object FlowAvailabilityLimits {
    const val MAX_PATHS = 4_096
    const val MAX_WORK_UNITS = 1_000_000L
    const val MAX_PATH_CHARACTERS = 8_000_000L
    const val MAX_DEPTH = 128
}

/** Exhaustion yields no analysis product. Callers must not resume with a partial state. */
class FlowAvailabilityLimitException internal constructor(
    val dimension: String,
    val limit: Long,
    val consumed: Long,
    val requested: Long,
    val path: FlowStatementPath,
    val sourceLocation: SourceLocation?
) : IllegalStateException(
    "FLOW_AVAILABILITY_LIMIT_EXCEEDED: $dimension budget ($limit) exceeded at " +
        "${path.toString().take(512)}; consumed=$consumed, requested=$requested. " +
        "Simplify workflow branching or split the workflow. No execution plan was authorized."
) {
    val code: String = "FLOW_AVAILABILITY_LIMIT_EXCEEDED"
}

/**
 * A fresh ledger per analysis/compilation. Charges precede allocations and scans;
 * repeated state visits count even when immutable objects happen to be shared.
 * Work units count AST visits, state/path/binding/provenance entries and merge
 * comparisons, not wall time or implementation-specific heap bytes.
 */
internal class FlowAvailabilityBudget {
    private var work = 0L
    private var pathCharacters = 0L
    private var depth = 0
    private var path = FlowStatementPath("flow")
    private var location: SourceLocation? = null

    fun <T> visit(at: FlowStatementPath, sourceLocation: SourceLocation?, action: () -> T): T {
        val previousPath = path
        val previousLocation = location
        path = at
        location = sourceLocation ?: previousLocation
        try {
            check("depth", depth.toLong(), 1, FlowAvailabilityLimits.MAX_DEPTH.toLong())
            work(1)
            depth++
            try { return action() } finally { depth-- }
        } finally {
            path = previousPath
            location = previousLocation
        }
    }

    fun work(amount: Long = 1) {
        check("work-units", work, amount, FlowAvailabilityLimits.MAX_WORK_UNITS)
        work += amount
    }

    fun paths(count: Int) {
        check("paths", 0, count.toLong(), FlowAvailabilityLimits.MAX_PATHS.toLong())
    }

    fun pathCharacters(amount: Long) {
        check("path-characters", pathCharacters, amount, FlowAvailabilityLimits.MAX_PATH_CHARACTERS)
        pathCharacters += amount
    }

    fun binding(binding: FlowBindingState) {
        work(1L + binding.externalPaths.size + binding.producerPaths.size)
        binding.producerPaths.values.forEach { work(it.size.toLong()) }
    }

    fun state(state: FlowAvailabilityState) {
        paths(state.paths.size)
        work(1L + state.paths.size + state.bindings.size)
        state.bindings.values.forEach(::binding)
    }

    private fun check(dimension: String, used: Long, amount: Long, maximum: Long) {
        require(amount >= 0) { "Availability resource charges must be non-negative." }
        // Subtraction prevents overflow even for a caller's very large charge.
        if (amount > maximum - used) {
            throw FlowAvailabilityLimitException(dimension, maximum, used, amount, path, location)
        }
    }
}
