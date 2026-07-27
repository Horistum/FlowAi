# v0.9.7.10 Bounded Semantic Closure Gate

## Purpose

The bounded semantic closure gate closes the v0.9.7 universal semantic foundation only from evidence declared before closure started. It is not a new semantic feature, a new public standard release, or a new implementation capability.

The gate answers one finite question:

> Do all previously declared Core, release, conformance, reference and negative-evidence obligations still exist, agree and pass?

## Two-phase lifecycle

### READY

While the closure work package is `active`:

- Core items `0.9.7.1` through `0.9.7.9` must be completed;
- the closure roadmap item remains `next`;
- the Core track remains `active`;
- the closure implementation may report `PASS` only for the frozen evidence checklist;
- no completion metadata is claimed.

The exact branch head and its synthetic merge candidate must independently pass Flow CI.

### CLOSED

After implementation validation passes, a separate metadata change may:

- mark the closure work package `complete`;
- mark item `0.9.7.10` `completed`;
- mark the Core track `completed`;
- record the exact implementation head and synthetic merge candidate in structured validation evidence.

The completion-metadata head and its synthetic merge candidate must then independently pass the same Flow CI boundary.

## Evidence authorities

Closure consumes these pre-existing authorities:

- `.flow-agent/roadmap-core-v0.9.7.9.yaml` for Core item lifecycle;
- `.flow-agent/roadmap.yaml` for current roadmap decision alignment;
- `ReleaseMetadataHonestyAuthority` for package, standard, correction and closure metadata reconciliation;
- `StandardModel` for release checks and external or negative evidence anchors;
- actual `ConformanceRunner` results for executed behavior;
- `.github/workflows/flow-agent-check.yml` for exact-head and merge-candidate CI separation.

## Frozen check catalog

`BoundedSemanticClosureCatalog.declaredPriorChecks` is the finite list of checks that existed when closure began. The authority requires exact set equality between that catalog and the checks executed before closure.

Closure fails on:

- missing checks;
- unexpected checks;
- duplicated identities;
- failed checks;
- the closure check itself appearing as prior evidence.

A new check introduced after closure planning therefore does not silently expand the checklist. It requires an explicit roadmap decision.

## Retained reference proof

The closure catalog explicitly retains:

- Jenkins manifest generation;
- end-to-end snapshot existence and content;
- reference intent corpus behavior;
- reference corpus execution harness behavior;
- target-selection provenance and CLI status integrity;
- closure-blocking safety and diagnostic integrity.

This keeps implementation feedback alive without allowing a concrete platform to define universal meaning.

## Structured workflow verification

The closure authority parses the GitHub Actions workflow as YAML and verifies that:

- `compile-test-conformance` exists;
- `merge-candidate-compile-test-conformance` exists;
- exact checkout selects the pull-request head SHA;
- both jobs verify `git rev-parse HEAD`;
- both jobs run complete tests;
- both jobs run standalone conformance.

Text occurrence outside the authoritative workflow fields is not accepted.

## Non-circularity

The closure check runs last and receives an immutable snapshot of all prior conformance results. Its own result is not part of that snapshot.

Negative tests prove that adding a passing closure result to prior evidence makes the report fail. Closure is therefore not allowed to certify itself.

## Version boundary

This item does not change:

- implementation package `0.9.5`;
- public standard `0.8.0`;
- artifact contract `2.0`.
