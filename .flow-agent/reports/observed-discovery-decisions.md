# Observed decisions constrained by the canonical compiler

PR 271 adds a functional authoring boundary: typed source facts select reviewed
lifecycle obligations, which the existing compiler checks on its canonical graph.
The earlier evaluation-only wrapper is removed from this PR. Independent live-model
evaluation remains open and is not replaced by a scripted success metric.

## Implemented behavior

- Opt-in investigation v4, host/6 and packet/assessment v2; v1–v3 behavior remains.
- Shared exact source resolution for parameter bindings and typed decision inputs.
- Exactly one matching alternative, with explicit coverage of every declared input.
  Missing, ambiguous, stale, unsupported or unmatched observations block selection.
- Selected checks are additive requirements v5. The original caller requirements,
  all case alternatives, source evidence and complete candidate are bound to review.
- Canonical receipts cover selected decision checks as well as observed parameters.
  No host traversal of the Intent or independent semantic graph is introduced.
- Existing clarification can continue to candidate review and compilation. Approval
  never follows a changed source, replacement candidate or interrupted compilation.

## Verification and limits

Local protocol tests cover typed equality, source conflicts, missing/invalidated
facts, case ambiguity, complete input coverage, old protocol rejection, unchanged
independent requirements, source mutation before/during compilation, explicit caller
continuation, compiler receipt omissions and public schema references.

The full local Python suite contains 503 tests: 491 passed; 12 existing deployed
observer tests cannot start Unix-socket services in this workspace. Those tests
remain enabled in CI. `tools/flow_agent_validate.py` passes. No JVM or installed-CLI
pass is asserted locally because the required Gradle distribution cannot be fetched
in this environment. Exact-head and merge-candidate CI remain required.

The installed compiler proof is integrated into `verify_authoring_host.py` and
contains 22 positive/adversarial cases over infrastructure and data. It checks stale
alternative plans, unrelated observed addresses, cleanup of a shared address,
allocation inside its own recovery region, flattened cleanup and an unrelated
recovery point. Two distinct source layouts exercise the same generic resolution.

These transcripts are manually authored and never supplied as caller hints to the
provider. They establish protocol and compiler behavior only. The model's discovery
quality, complete multi-resource infrastructure lifecycle, execution-time preconditions,
partial allocation reconciliation, native provider ownership and real recovery
outcomes remain unproven here. No executor, provider SDK or scenario-specific
production adapter is added. S3, AI-A3, AR-06 and AR-07 stay open; EF-09 stays paused.

The first installed run exposed a real receipt-handling defect: canonical scope
rejection omits `graphDigest`, but the host assumed it always existed and mislabeled
the rejected candidate as a compiler failure. The correction retains only explicit
`NOT_EVALUATED` obligations when both compiler reports identify rejection without a
graph. Accepted or evaluated claims still require graph evidence. The test peer now
matches that product shape, and dedicated regressions reject inconsistent claims.
Negative installed cases require the expected fidelity/scope rejection; a compiler
crash or unrelated failure cannot count as successful falsification.
