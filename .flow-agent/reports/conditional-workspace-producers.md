# AR-06AE-C15: Conditional workspace producers

## Baseline and purpose

PR #250 merged at `5b78609dd11c7538d296511c5d0f973b84368399`, with the same tree `02f8bfc52fea315b4600b45a6d2ef4158f7237fe` as validated head `5ad6dd2e36882e8721102884786cd91cb506b116`. Flow CI `40148866140` passed 2,362 JVM tests, 291 installed conformance checks and 270 Python tests on each required revision. Runtime CI `40148866139` independently replayed six composition archives and retained 39 signed assessments. These are predecessor receipts; final C15 evidence belongs in its PR.

C14 admitted the existing workspace proof, but production applicability still required every producer to be top-level and unconditional. C15 selects the next reusable control/continuity gap from AR-06. Its work package was committed before implementation.

## Implementation and ownership

The adapter now maps task identities to structural condition-branch paths. An admitted producer path must be a prefix of its consumer path. This permits the same branch and more deeply nested consumers, including otherwise branches, without equating independent conditions merely because their predicates look alike. Core availability, explicit ordering, one resolved workspace source and the existing Boolean input contract remain independent requirements. Loops, retries, parallel groups and recovery are not flattened into this contract.

Native checkout and upload share the producer job's guard; download and build share the consumer job's guard. The direct renderer validates both job/action guards, context availability and dependency ordering, then requires the consumer to retain every producer guard conjunct. Its comparison preserves Boolean AST structure and ignores source locations; it does not use lossy diagnostic rendering or infer arbitrary logical equivalence. The scope identity and current reference overlay move together; the existing unconditional reference scenario and snapshot bytes remain unchanged.

The existing native workspace workflow adds two conditional producers and four consumers. A selected producer serves two selected consumers and one skipped nested consumer; another producer and its consumer are both skipped. One new mutant removes only that skipped producer's guard and must actually checkout, upload and observe bytes. The independent oracle rejects its extra execution. Every earlier baseline job and transfer/consumer-guard mutant remains intact.

The source-derived oracle binds branch ancestry, graph relations, native guards, three separate artifact identities and file observations. It authenticates thirteen baseline jobs, four mutants and preparation against the same-attempt API, including required upload/observation steps and actual dependency timing. Common coverage regeneration and replay remain mandatory, and rehashing false observations cannot admit them. There are still six composition producers, seven total coverage producers and one bounded GitHub ARTIFACTS_WORKSPACES row; signed portfolio bytes and general VALUE/STATE gaps are unchanged.

## Validation

- Baseline: 270 Python tests and Flow Agent structure/context checks passed.
- Candidate: all 277 Python tests passed, including seven new oracle/replay tests. The focused composition suite passed 111 tests. Existing inventory, API, timing, byte and selection regressions now also cover the added jobs.
- Seven new JVM tests cover lexical ancestry, same/nested/otherwise branches, alternate consumer counts, unavailable inputs, scope escape, unsupported controls, guard tampering and Boolean expression grouping. The native envelope regression now requires all four mutants.
- YAML parsing, work-package scope, unchanged native baseline bodies and roadmap milestone-state checks passed. Core, build configuration, schemas, historical evidence and unconditional reference snapshots are unchanged.
- Local JDK is 17; this repository requires JDK 25. No local JVM execution is claimed. Final exact-head and merge-candidate JVM/conformance, all isolation proofs and current-revision runtime/composition workflows remain required. Their immutable-head results will be recorded in the PR without a later source commit.

The first current-revision native experiment passed all baseline and mutant jobs and 38 focused JVM tests, but collection rejected two skipped jobs whose GitHub scheduler completion timestamps precede their start by one second. Skipped jobs have no executed steps or observations and do not establish an execution interval. The collector now retains those raw API timestamps without using them for execution-order proof; real jobs still require valid intervals and dependency ordering. A regression covers reversed skipped timestamps, preservation of those facts and rejection of executed jobs or fake skipped steps using the same exception. Independent replay of the downloaded first-run archive against its eighteen actual API records passed after this correction: eight selected jobs, five skipped jobs and four mutants. That validates the correction against real input; the complete final-head workflow remains required.

## Limits and next work

This is an adapter endpoint-composition change, not a new universal data or control model. Native predicates are literal Booleans; input-driven predicates and alternate cardinalities have JVM coverage. CI runners, the compiler, native observers and job-output/API identity remain trusted. Regular-file bytes do not certify filesystem metadata or symbolic-link identity.

Producer-failure experiments, repeated-control transport, general VALUE/STATE continuity and broader provider coverage remain open. No signed portfolio expansion, target-wide support or portability promotion is made. AR-06 remains active, AR-07 planned and EF-09 paused; deferred A-02/A-03 semantic designs and AR-07 migration/AI work are not activated.
