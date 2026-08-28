package org.flowlang.compiler

import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyResolution

internal fun PlanDependencyKind.toCanonical(): CanonicalDependencyKind = when (this) {
    PlanDependencyKind.ORDERING -> CanonicalDependencyKind.ORDERING
    PlanDependencyKind.VALUE -> CanonicalDependencyKind.VALUE
    PlanDependencyKind.WORKSPACE -> CanonicalDependencyKind.WORKSPACE
    PlanDependencyKind.STATE -> CanonicalDependencyKind.STATE
}

internal fun CanonicalDependencyKind.toPlan(): PlanDependencyKind = when (this) {
    CanonicalDependencyKind.ORDERING -> PlanDependencyKind.ORDERING
    CanonicalDependencyKind.VALUE -> PlanDependencyKind.VALUE
    CanonicalDependencyKind.WORKSPACE -> PlanDependencyKind.WORKSPACE
    CanonicalDependencyKind.STATE -> PlanDependencyKind.STATE
}

internal fun PlanDependencyEvidence.toCanonical(): CanonicalDependencyEvidence = when (this) {
    PlanDependencyEvidence.DECLARED_ORDERING -> CanonicalDependencyEvidence.DECLARED_ORDERING
    PlanDependencyEvidence.DATA_REFERENCE -> CanonicalDependencyEvidence.DATA_REFERENCE
    PlanDependencyEvidence.MODULE_CONTRACT -> CanonicalDependencyEvidence.MODULE_CONTRACT
}

internal fun CanonicalDependencyEvidence.toPlan(): PlanDependencyEvidence = when (this) {
    CanonicalDependencyEvidence.DECLARED_ORDERING -> PlanDependencyEvidence.DECLARED_ORDERING
    CanonicalDependencyEvidence.DATA_REFERENCE -> PlanDependencyEvidence.DATA_REFERENCE
    CanonicalDependencyEvidence.MODULE_CONTRACT -> PlanDependencyEvidence.MODULE_CONTRACT
}

internal fun PlanDependencyResolution.toCanonical(): CanonicalDependencyResolution = when (this) {
    PlanDependencyResolution.RESOLVED -> CanonicalDependencyResolution.RESOLVED
    PlanDependencyResolution.UNRESOLVED -> CanonicalDependencyResolution.UNRESOLVED
    PlanDependencyResolution.AMBIGUOUS -> CanonicalDependencyResolution.AMBIGUOUS
}

internal fun CanonicalDependencyResolution.toPlan(): PlanDependencyResolution = when (this) {
    CanonicalDependencyResolution.RESOLVED -> PlanDependencyResolution.RESOLVED
    CanonicalDependencyResolution.UNRESOLVED -> PlanDependencyResolution.UNRESOLVED
    CanonicalDependencyResolution.AMBIGUOUS -> PlanDependencyResolution.AMBIGUOUS
}
