# AI-A3-S3.4: existing worker integration with scoped observations

Baseline: merged PR #271, main `5b1599da18f35da49c5493e5cec19e2ef83737d1`.

## Change

The portable control-plane worker can now call Flow's existing planning operations
through its dynamic-tool broker. The optional dependency is pinned to control-plane
commit `92a664b7ce894b48ce0fdcb35283c43a5fed661e`. MCP, shell, edits, execution and
approval remain disabled in this worker. The reference delivery controller is not
automatically activated or modified.

The adapter reuses `PlanningTools`, the installed canonical compiler and existing
selector-free discovery v3. It captures source observations with drafts, revalidates
them around compilation and final handoff, and accepts only an exact checked
candidate ID from the worker. Compiler-valid drafts await independent review;
fidelity remains unassessed. No model-authored requirements or scenario mapping is
added. This is a host integration; public Intent/standard versions do not change.

One private output directory identifies one reserved turn. Completed inner worker
receipts can restore an interrupted outer result without model/compiler calls.
Recovery rechecks retained bytes, current local sources, compiler and contracts.
Missing completed receipts or changed authority/configuration stop; there is no
automatic repeated model turn. The existing profile and commissioning gate remain.

## Validation

- 20 new tests pass against the actual packaged worker and a scripted stdio peer.
  They cover repairs, selection, duplicate delivery, uncertainty, outer-write
  recovery, changed request/source/listing/compiler/contracts/artifacts, forbidden
  tools, missing commissioning, scope boundaries and process failures.
- 16 installed-compiler outcomes pass through the user-facing worker CLI in
  infrastructure and isolated data recovery. These include canonical retrieval,
  invalid dependencies, unsafe retry, repaired and unresolved candidates, separate
  rejection of compiler-valid wrong-goal plans, and one-turn completed recovery.
- The existing Python suite ran 495 tests locally: 483 passed; 12 deployed-observer
  process tests cannot start because this environment rejects Unix socket creation
  with `EPERM`. No test or gate is disabled; the complete suite runs in GitHub CI.
- Flow Agent structure/context and diff checks pass. The installed CLI build passes.
- Exact-head and merge-candidate CI are required, including JVM/conformance,
  isolation, the existing MCP/authoring proofs and the new worker proof. The two
  full jobs now install the pinned optional worker and run its tests explicitly.

The first CI attempt at `55922089` passed the complete merge candidate, including
all 495 Python, 20 worker, 2,573 JVM tests, 319 conformance checks and 16 installed
worker cases. The exact-head job passed those same groups but exceeded the 35-minute
job limit during the final existing allocation proof. GitHub explicitly reported
`The job has exceeded the maximum execution time of 35m0s`; no test assertion failed.
Both full-job budgets are now 45 minutes to retain the entire expanded suite on
slower runners. Individual compiler/worker bounds and every test remain unchanged.
Final-revision CI must still pass; the timed-out attempt is not accepted evidence
of full exact-head completion.
The CI cost policy document and its existing finite-budget regression now pin the
same 45-minute ceiling. Revision selection, mandatory gates, cache restrictions and
failure propagation remain checked by the complete local CI-policy test suite.

These are scripted protocol/compiler checks, not live model reasoning or executed
infrastructure recovery. Private fixture attestations are ephemeral and are not
deployment commissioning evidence.

## Boundaries and next work

Observed export bytes are not provider truth or completeness; the adapter does not
automatically bind model interpretations to operation parameters or approve the
separate reviewed-discovery protocol. Independent original-goal review remains
external. Source checks at handoff cannot certify later execution preconditions.
Live model evaluation, remote ownership/state revalidation and the reference
controller's product-specific activation remain open. S3/AI-A3, AR-06, AR-07 and
F-16/F-20 remain open; EF-09 stays paused. No support promotion is claimed.
