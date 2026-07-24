# v0.9.7.9.6 Derived Model and Governance Integrity

## Purpose

Flow exposes several reports that summarize lower-level planning and projection evidence. These summaries are useful, but they are derived views. They must never become an independent source of truth that can contradict the detailed evidence carried by the same artifact.

Architecture governance has the same requirement. The drift catalog is executable policy. Its signal inventory, weights, threshold and exception rules cannot be treated as loosely parsed documentation.

This correction introduces explicit integrity authorities for both boundaries.

## Derived model authority

`DerivedModelIntegrityAuthority` validates negotiation, selection and decision-trace inputs.

### Capability negotiation

A valid negotiation report must preserve these invariants:

- required capabilities are unique
- target entries are unique
- support partitions are disjoint
- each target support partition exactly covers the required capabilities
- portability scores remain normalized to `0.0..1.0`
- portable and target-specific classifications are disjoint and complete
- blocked target summaries are derived from effective target status
- capability-only reports cannot recommend executable targets
- readiness-aware recommendations identify only supported, unblocked targets
- portability issues and workarounds reference known targets and required capabilities

`REQUIRES_RUNTIME` remains a support-partition fact. The existing compatibility authority represents it as warning-driven `PARTIAL` preliminary compatibility. The integrity layer validates that meaning instead of inventing a competing status interpretation.

### Execution readiness

`ExecutionReadinessIntegrityAuthority` validates that:

- blocker and warning lists use the declared severity
- findings reference the report target
- blocked readiness never allows generation
- preliminary readiness cannot claim concrete materialization, executable or production-ready evidence
- executable readiness requires supported compatibility, complete materialization, executable projection, no blockers and concrete evidence
- production-ready and executable flags cannot diverge
- the public readiness status is reproducible from the evidence in the report

### Target selection

Selection summary fields are validated against the candidate list:

- candidate targets are unique
- ranks are contiguous and match report order
- ready, degraded and blocked buckets are derived from candidate evidence
- preliminary candidates remain degraded for recommendation purposes even when capability checks are ready
- a recommended target must be the first complete executable candidate in ranked order
- blocked or unevaluated candidates cannot be recommended

### Decision trace

A decision trace may only combine artifacts that agree on:

- plan version
- flow identity
- strictness
- configured target set
- blocked targets
- recommendation presence and identity

The requested target must exist in the configured target registry. A trace cannot be assembled from a negotiation for one plan and a selection for another merely because both data classes happen to deserialize successfully.

## One-way reconciliation

Concrete manifest evidence may be applied only to preliminary reports.

Reconciliation now rejects:

- a readiness-aware negotiation used as preliminary input
- a readiness-aware selection used as preliminary input
- an already reconciled execution-readiness report
- duplicate manifests for one target
- manifests for targets outside the source report

This removes silent `associateBy` overwrite and prevents repeated reconciliation from turning derived evidence into a new source authority.

## Governance policy integrity

`ArchitectureGovernanceIntegrityAuthority` validates the drift catalog before accepting a governance result.

The catalog must declare exactly the supported baseline and negative signal inventories. It rejects:

- missing or duplicate signal ids
- unknown signal ids or fields
- non-integer weights
- zero or positive weights for negative signals
- a weakened minimum score
- unsupported scoring modes
- missing purpose, rule or version metadata

The negative-signal-only policy requires `minimumScore: 0`. Existing baseline evidence remains descriptive and always contributes zero points.

## Governance report integrity

The authority independently verifies that:

- report signal sets match the catalog
- signal presence is derived from evidence
- negative scores match configured weights only when evidence is present
- the final score equals the sum of negative signals
- drift status follows the configured formula
- report-budget status follows its detailed findings
- top-level governance status follows error severity
- file and forbidden-direction identities are unique

A caller cannot change `status: FAIL` to `status: PASS` while leaving the underlying issues untouched.

## Scoped ADR exceptions

A drift exception is valid only when an ADR contains explicit structured lines:

```text
Drift Score exception: runtime-direction, target-specific-standard
Conformance guardrail: conformance/standard/example.conformance.yaml
```

Every named signal must be a known negative signal. The guardrail must be an existing file below `conformance/`. Every active negative signal must be covered.

Free-text mentions of “Drift Score exception” and “conformance guardrail” are not waiver authority.

## Production boundaries

The integrity authorities are invoked where reports are produced or reconciled:

- capability negotiation
- execution readiness
- target selection
- readiness reconciliation
- target negotiation explanation
- target decision trace
- architecture governance conformance

The change adds no runtime executor, plugin lifecycle, target command transport, shell projection or target-specific public semantics.

## Conformance

The conformance gate `governance.derived-model-integrity` verifies:

- preliminary and reconciled models pass the same authority
- duplicate manifest evidence is rejected
- decision traces preserve the validated recommendation
- repository governance passes strict catalog and report validation
- a corrupted top-level governance status is rejected

Package, public standard and artifact contract versions remain unchanged.
