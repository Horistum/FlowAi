package org.flowlang.conformance

import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import org.flowlang.release.ClosureEvidenceBoundaryAuthority

/** Owns the distinct implementation/completion evidence boundary invariant. */
internal class ClosureEvidenceBoundaryChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        runCheck(ClosureEvidenceBoundaryAuthority.CHECK_ID) {
            val report = ClosureEvidenceBoundaryAuthority(rootDir).analyze()
            require(report.status == "PASS") {
                "Closure evidence boundaries are invalid: " +
                    report.checks.filter { it.status == "FAIL" }
                        .joinToString { check -> "${check.id}:${check.evidence.joinToString()}" }
            }
        }
    )
}
