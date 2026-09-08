# C1.0 Operational Domain Adequacy

## Status

C1.0 is `COMPLETED`. Its implementation boundary is independently proven by GitHub Flow CI #2812 / run `31238299719`, and its later distinct completion boundary is independently proven by Flow CI #2823 / run `31255449623`. Both boundaries passed the exact PR head and synthetic merge candidate workflow paths.

AR0.1 remains terminally completed. C1.0 reactivated the conformance stream as a separate post-architecture evidence track and now closes that stream again with no fabricated successor. The ordered post-C1.0 strategic direction remains candidate-only until a separate roadmap transition explicitly selects a bounded work package.

## Why this step exists

After C0.4 and AR0.1, the remaining material risk was no longer unresolved architecture ownership. The executable real-world corpus was still concentrated on software-delivery evidence, while Flow's target-neutral automation model already declared operational capabilities such as `BACKUP` and `RESTORE`.

C1.0 adds bounded falsification pressure outside CI/CD without modifying the closed C0.1 three-domain vocabulary, changing Core capability identity, or promoting any concrete tool to semantic authority.

## Implemented boundaries

### Separate operational corpus

A new `conformance/corpus/operational` corpus owns the C1.0 evidence vocabulary. The first bounded domain is `data-protection`, with exactly two required existing canonical capabilities:

- `BACKUP` through DP01;
- `RESTORE` through DP02.

C0.1 remains separate and retains its original `RealWorldDomain` vocabulary and check inventory.

### Independent immutable source evidence

DP01 and DP02 use the official `velero-io/velero` backup and restore reference documentation pinned to immutable revision `7346ec527c99c156bcbbb4a76d83c31b2370f504`. The upstream repository is Apache-2.0 licensed.

The source is evidence only. C1.0 does not claim a Velero adapter, Kubernetes execution support, or any target-specific public semantic contract.

### Production-path semantic evaluation

C1.0 reuses `RealWorldCorpusRunner` for canonical intent validation, Intent-to-AST lowering, Flow validation and production planning. The evaluator gained only a configurable evidence-check namespace; the existing C0.1 namespace remains the default.

No second C1.0 semantic interpreter or target-specific bypass was introduced.

### Positive and negative polarity

Each admitted baseline owns a negative mutation:

- DP01 removes the `backup_artifact` producer while its verification consumer remains;
- DP02 removes the `restored_state` producer while its verification consumer remains.

Both mutations must fail with the exact missing-value-producer diagnostic, and the observed polarity must change from `REPRESENTABLE` to `REJECTED`.

### Fail-closed lifecycle and roadmap transition

`OperationalDomainAdequacyRoadmapLifecycleAuthority` keeps C1.0 in one of three explicit states: `IMPLEMENTING`, `VALIDATING`, or `COMPLETED`.

Activation evidence exactly equals the recorded AR0.1 completion boundary. The implementation boundary is Flow CI #2812. The completion boundary is the later and distinct Flow CI #2823 boundary. The global roadmap transition authority therefore recognizes the terminal `C1_0_COMPLETE` state while preserving C0.4 and AR0.1 as completed predecessor evidence.

Completed C1.0 requires:

- C0.4 to remain completed immutable history;
- AR0.1 to remain terminally completed with no architecture successor;
- C1.0 to be the completed conformance item;
- the conformance roadmap, global roadmap and release state to carry no successor focus;
- activation, implementation and completion evidence to form a strictly later, distinct sequence;
- all retained predecessor evidence and the live AR0.1 responsibility catalog to remain valid.

## Architecture inventory maintenance

AR0.1's completion baseline recorded 74 production `*Authority` types, but its canonical responsibility catalog is a live maintenance contract rather than a permanent count ceiling. C1.0 adds one justified lifecycle authority and adds `OperationalDomainAdequacyConformanceChecks` as a caller of `RealWorldPolarityAuthority`.

The catalog was updated accordingly. Historical AR0.1 completion evidence and baseline counts were not rewritten.

## Version boundary

This step does not change the published package, public standard, or artifact contract:

- implementation package: `0.9.5`;
- public standard: `0.8.0`;
- artifact contract: `2.0`.

## Local validation evidence

The uploaded offline Gradle bundle was validated from clean `main` at merge commit `7499df871fac7ba780d02e3ff5c44d184105b4c1` before implementation.

Baseline before C1.0 changes:

- `./gradlew --offline --no-daemon clean test`: PASS, 3m 34s.

Final C1.0 implementation validation:

- production Kotlin compilation: PASS, 56s;
- focused roadmap/C1.0 suite: PASS, 31 tests;
- `./gradlew --offline --no-daemon clean test`: PASS, 3m 26s;
- Python Flow Agent tooling tests: PASS, 29 tests;
- `python3 tools/flow_agent_validate.py`: PASS;
- `python3 tools/flow_agent_runner.py`: PASS;
- `./gradlew --offline --no-daemon run --args="conformance"`: PASS, 190/190 checks, 0 failures.

## Independent implementation boundary

GitHub Flow CI #2812 / run `31238299719` independently validated PR #118 after implementation:

- exact head: `9230a30dd0783bd6932d58c404ef2076a41aee65`;
- synthetic merge candidate: `f28b45503c29b15070f6c93d7b194df1633eec5d`;
- exact-head `compile-test-conformance`: PASS;
- merge-candidate `merge-candidate-compile-test-conformance`: PASS;
- Gradle `clean test`: PASS;
- standalone conformance: PASS, 190/190 checks, 0 failures.

## Distinct completion boundary

GitHub Flow CI #2823 / run `31255449623` independently validated the later PR #119 boundary after C1.0 implementation evidence had already been recorded:

- exact head: `c22431b1abaf8814b2fe049935fee4c995332122`;
- synthetic merge candidate: `2b0f2e570b91b7c3c25b4b3c671b98d9733b9198`;
- exact-head `compile-test-conformance`: PASS;
- merge-candidate `merge-candidate-compile-test-conformance`: PASS;
- full Gradle compile/test: PASS;
- standalone conformance: PASS, 190/190 checks, 0 failures.

The completion boundary follows and is distinct from implementation validation #2812, which itself follows and is distinct from AR0.1 activation #2809. C1.0 can therefore be recorded as completed without using self-authored or same-run completion evidence.
