package org.flowlang.conformance

import java.io.File
import org.flowlang.roadmap.ToolchainModernizationLifecycle

/** Independent conformance projection of the orthogonal toolchain lifecycle. */
class ToolchainModernizationConformanceRunner(private val rootDir: File = File(".")) {
    fun checks(): List<ConformanceCheck> {
        val result = runCatching { ToolchainModernizationLifecycle(rootDir).analyze() }
        val report = result.getOrNull()
        return listOf(
            ConformanceCheck(
                name = ToolchainModernizationLifecycle.CHECK_ID,
                passed = report?.status == "PASS",
                message = result.exceptionOrNull()?.message
                    ?: report?.errors?.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        )
    }
}
