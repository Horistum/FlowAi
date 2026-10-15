# AR-06AA — authenticated target coverage publication

PR #223 merged into `38b2253183df5c40cac3ff39c24be281f981821d`, preserving the independently verified tree. The immutable baseline records its exact PR receipts and separately observed main workflow status.

## Candidate behavior

The existing runtime portfolio covers three composed providers. The new publication includes every declared target and all twelve certification constructs, exposing profiles without providers alongside adapters with bounded observations. It consumes only the opaque result of complete authenticated replay; serialized PASS flags or declaration text are never behavioral inputs.

`target-coverage.json` and `target-coverage.md` separate declared capabilities/features, provider projection definitions and evidence-derived bounded maturity. Every observed row retains scenario identities and exact authenticated baseline/mutant behavior, source and canonical graph, proof/trust digests, runtime prerequisites, execution scope and limitations. Shared comparisons are inherited only from verified comparisons. Secrets and general value/state continuity remain explicit evidence gaps.

The CLI stages both historical portfolio files and both new coverage views, re-reads their actual bytes, writes `publication.json` last and atomically publishes the complete directory. The coverage view is bound to the exact portfolio SHA-256. Existing destinations are rejected. GitHub publishes the new coverage summary and archives all five files together. The manifest is an integrity receipt, not a new signature or trust root. Runtime hosts and transport remain trusted.

## Validation gate

Fresh exact-head and synthetic-merge Flow CI, all native producers and full authenticated portfolio replay remain mandatory. Current-revision results will be recorded in the PR after execution. Protocol tests use synthetic signed fixtures and never substitute for provider execution.

No target behavior, public syntax, package version, generic support, historical acceptance or portable-execution claim changes. AR-06 remains active; AR-07 planned, EF-09 paused and F-20 AR-07-owned.
