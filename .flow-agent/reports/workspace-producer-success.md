# AR-06AE-C16: Explicit workspace producer success

## Baseline and purpose

PR #251 merged at `e7a9824ce7add45f5d58b25737a6581a3c3747d5`. Its tree `c5520bc38fb295552f22ee4425b57984c421866a` matches validated head `ca16f0399422dccdbe9074d8a382ab36e653e1f9`. GitHub reports success for Flow CI `40249779687` and Adapter Runtime Certification `40249779695`. The new work package was committed before implementation.

C15 retained conditional workspace availability through producer/consumer branch ancestry and guard implication. Its native scheduler nevertheless included the ordering-only alternative that permits an intentionally skipped predecessor. For valid compiled workspace endpoints, the consumer guard made that alternative unreachable. This change makes the availability rule explicit and independent of that conjunction; it does not claim an observed bypass of C15's complete renderer.

## Implementation

The GitHub scheduling adapter derives workspace producers from existing download endpoints and requires each source to remain an explicit dependency. When conditional/approval dependencies need explicit scheduling, a workspace producer can satisfy it only with success. Unrelated conditional and approval predecessors retain their skip rules; ordinary dependencies retain success checks. Unconditional jobs keep GitHub's default success and cancellation behavior, so historical reference artifacts remain unchanged.

No new standard model, canonical relation or public contract is introduced. The renderer still validates all transfer endpoints and branch guards before rendering. The four conditional workspace consumers in the existing native envelope now use success-only producer checks. All thirteen baseline jobs and four native mutants remain present, and the existing independent archive regeneration/replay consumes the changed compiler output.

## Validation

- All 277 Python tests passed, including independent workspace archive admission and native identity/outcome/ordering regressions.
- Flow Agent structure validation and generated context passed.
- Six new JVM regressions cover both producer predicate values, mixed ordering/approval/data dependencies, absent endpoint metadata/dependencies, native default behavior and a 16-case outcome/cancellation matrix. The matrix evaluates the generated Boolean guard after binding native status facts; it is not a runtime emulator or native failed-job experiment.
- The existing envelope mutation test additionally rejects reintroducing a skipped workspace producer alternative. Existing conditional producer rendering assertions require success-only checks.
- Local Java is 17, while the repository requires JDK 25. JVM and current-revision native checks must run in GitHub; final immutable-head results belong in the PR without a later source commit.

## Limits and next work

This is a bounded adapter scheduling correction, not an expansion of workspace applicability. Producer-failure native experiments, repeated-control transport, general VALUE/STATE continuity and filesystem metadata fidelity remain open. The guard matrix must not be counted as evidence of those native failure experiments. Signed portfolio scope and support claims do not change. AR-06 remains active, AR-07 planned and EF-09 paused.
