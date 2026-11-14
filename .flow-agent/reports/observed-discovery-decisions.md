# Observed decisions constrained by the canonical compiler

PR 271 now adds a goal-first mode to the existing authoring host. Its initial input
contains the goal, permitted environment, actual observation grants, constraints and
frozen success criteria. An agent can investigate and propose an evidence-linked
complete Intent without a binding map or decision table. The earlier typed observed
decisions remain optional helpers. Independent live-model evaluation stays open;
scripted success cannot close it.

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
- Opt-in `investigation.task` records host/7, explicit per-root list/read grants and
  pre-generation public criteria. Grants are enforced before filesystem access.
- Failed authorized lookups become bounded observations in goal mode. They cannot
  become extracted facts or global absence, and changed results invalidate review.
- Map-free interpretation requires current evidence references. Inferences and open
  unknowns retain their existing attribution and blocking behavior.
- Every original and derived compiler check must occur exactly once in the receipt.
  Results are partitioned into caller-authored constraints and proposed consistency
  checks. Neither fills natural-language goal or runtime evidence.
- `prepare-goal` materializes eight input-only cases using the owner's transport and
  budget without calling a provider. Infrastructure and recovery variants contain no
  solution maps, proposed plans, expected query sequence or approval records.

## Verification and limits

Local protocol tests cover typed equality, source conflicts, missing/invalidated
facts, case ambiguity, complete input coverage, old protocol rejection, unchanged
independent requirements, source mutation before/during compilation, explicit caller
continuation, compiler receipt omissions and public schema references.

The full local Python suite contains 526 tests: 514 passed; 12 existing deployed
observer tests cannot start Unix-socket services in this workspace. Those tests
remain enabled in CI. `tools/flow_agent_validate.py` passes. No JVM or installed-CLI
pass is asserted locally because the required Gradle distribution cannot be fetched
in this environment. Exact-head and merge-candidate CI remain required.

The installed compiler proof is integrated into `verify_authoring_host.py` and
contains 30 positive/adversarial cases over infrastructure and data. It checks stale
alternative plans, unrelated observed addresses, cleanup of a shared address,
allocation inside its own recovery region, flattened cleanup and an unrelated
recovery point. Two distinct source layouts exercise the same generic resolution.
Eight added cases exercise four map-free plans and four violations of independent
constraints through the actual compiler. The input-only live portfolio is separate
from these deterministic transcripts. No live model adapter or provider credential
was configured in this workspace, so no live reasoning result is claimed.

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
