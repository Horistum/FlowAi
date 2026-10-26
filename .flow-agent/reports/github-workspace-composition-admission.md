# AR-06AE-C14: GitHub workspace composition admission

## Baseline and purpose

PR #249 merged at `c3f44dceae48c7cbc423e15c461e9c72548fa4bd`. Its tree `065abbcd880f7820b21db9844cb2c201c2445fe6` matches validated head `017d901ce0a02ab96498bbcfa4cd2e153deca8a8`. Flow CI `40045474454` passed 2,362 JVM tests, 291 installed conformance checks and 257 Python tests on each required revision; runtime CI `40045474482` passed. The completed evidence is recorded in PR #249. Those receipts validate the predecessor, not C14.

The existing C12/C13 workspace experiment executed native fan-out and three mutants, but the common coverage consumer admitted only five other composition archives. Its workspace proof used nested provenance and unauthenticated job-output assertions, so merely adding its name to the coverage table would not establish independent replay. The work package was committed before implementation.

## Implemented boundary

The reusable workspace workflow now exposes a successful collector's receipt to its caller. Common admission requires this seventh producer alongside six existing producers (five compositions and the signed portfolio). It pins the exact source/workflow revisions, run, attempt and proof digest, checks the complete nine-file inventory, and freezes every file before replay.

Workspace proof format v1 uses flat provenance and `runtimeKind: github-actions-jobs`. Native job identities describe its execution boundary; no fictitious image digest is introduced. Existing container-based compositions retain mandatory image identity checks. Old workspace proof archives are deliberately rejected and must be regenerated on the current revision.

The consumer invokes the existing Kotlin workspace compiler entrypoint with the current revision and execution envelope. All five generated inputs must match archived bytes. The existing source/graph/manifest oracle then derives branch selection and workspace dependencies; the regular, hidden and binary file inventory comes from the current checkout. Missing transfer, hidden-file loss and guard omission must still be detected. Published cases, scope, claim and limits are compared with the independently derived assessment.

Both collection and replay authenticate eleven native jobs through the same-attempt GitHub API: preparation, seven baselines and three mutants. They reject missing/extra/duplicate jobs, stale identities, unsuccessful or unfinished outcomes, skipped jobs with executed steps, absent observation/falsification steps and reversed preparation/producer/consumer timing. The archived API records must match a fresh query. For `continue-on-error` mutant builds, the pinned envelope and successful falsification step assert the original failure; API step conclusion alone cannot distinguish it.

Coverage adds only a bounded GitHub `ARTIFACTS_WORKSPACES` observation. All signed portfolio files remain byte-preserved. General `VALUE_STATE_CONTINUITY` and other unobserved target/construct rows remain explicit gaps. Current documentation and recovery state describe this boundary without changing historical reports.

## Validation

- Baseline: 257 Python tests, Flow Agent validation and context generation passed.
- Candidate: 270 Python tests passed, including 13 new tests across workspace authentication and common admission/replay. Negative cases cover rewritten hashes, compiler/envelope drift, wrong source/run/attempt, native failure, missing mutants, byte loss, branch changes, missing assertions, ordering and forged proof claims.
- The collector-to-receipt integration test exercises actual file publication and the common admission contract with fixture API responses; replay tests repin altered archives to ensure checks do not stop at hashes.
- A compatibility check against eleven real predecessor GitHub job records passed identity, outcome, step and timing validation. It is an API-shape check, not current-revision native evidence.
- Flow Agent validation/context generation, YAML parsing and diff checks passed. Local JDK is 17 and this repository requires JDK 25; no local JVM execution is claimed. Exact-head and merge-candidate JVM/conformance, all isolation proofs and current native workflows remain required, with final immutable-head evidence recorded in the PR.

## Limits and next work

This change owns verification and CI admission. Core, adapter implementations, source syntax, product schemas, native scenario families and signed portfolio scope are unchanged. CI runners, native observers and job outputs remain trusted even after API identity checks. Native evidence covers literal consumer guards and regular-file contents, not filesystem metadata, alternate native producer counts, input-driven branch combinations or producer-failure experiments.

AR-06 stays active; further work must select a reusable control/continuity gap from current coverage. Conditional producers, repeated-control workspace transport and general VALUE/STATE continuity remain open. A-05 documentation is merged, while its migration/AI cleanup remains under planned AR-07; EF-09 remains paused. Neither this bounded admission nor green CI closes AR-06 or promotes target-wide support or portability.
