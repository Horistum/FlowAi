package org.flowlang.conformance

import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry

/** Final non-circular conformance gate for the v0.9.7 track. */
internal class BoundedSemanticClosureChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(priorChecks: List<ConformanceCheck>): List<ConformanceCheck> = listOf(
        runCheck(BoundedSemanticClosureCatalog.CLOSURE_CHECK_ID) {
            val authority = BoundedSemanticClosureAuthority(rootDir)
            val report = authority.analyze(priorChecks)
            require(report.status == "PASS") {
                "Bounded semantic closure failed: ${report.failedChecks.joinToString()}; " +
                    "missing=${report.missingConformanceChecks.joinToString()}; " +
                    "unexpected=${report.unexpectedConformanceChecks.joinToString()}; " +
                    "duplicate=${report.duplicateConformanceChecks.joinToString()}; " +
                    "failed=${report.failedConformanceChecks.joinToString()}"
            }

            val first = priorChecks.first()
            val missing = authority.analyze(priorChecks.filterNot { it === first })
            require(missing.status == "FAIL" && "closure.declared-check-set" in missing.failedChecks) {
                "Closure did not reject an omitted prior check."
            }

            val failed = authority.analyze(
                priorChecks.map { check ->
                    if (check === first) ConformanceCheck(check.name, false, "synthetic closure counterexample") else check
                }
            )
            require(failed.status == "FAIL" && "closure.all-prior-checks-pass" in failed.failedChecks) {
                "Closure did not reject a failing prior check."
            }

            val duplicate = authority.analyze(priorChecks + ConformanceCheck(first.name, true))
            require(duplicate.status == "FAIL" && "closure.declared-check-set" in duplicate.failedChecks) {
                "Closure did not reject duplicate check evidence."
            }

            val circular = authority.analyze(
                priorChecks + ConformanceCheck(BoundedSemanticClosureCatalog.CLOSURE_CHECK_ID, true)
            )
            require(circular.status == "FAIL" && "closure.declared-check-set" in circular.failedChecks) {
                "Closure accepted its own PASS result as prior evidence."
            }
        }
    )
}
