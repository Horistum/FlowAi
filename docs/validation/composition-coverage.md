# Composition coverage admission

The `adapter-composition-coverage` job in Adapter Runtime Certification publishes
coverage for every declared target and construct. It keeps two evidence classes
separate: authenticated signed portfolio scenarios and CI-bound composition
observations. Neither class establishes target-wide support or portable execution.
The `targets` CLI remains a repository reference/declaration report.

## Admission and replay

All seven producers (six composition archives and the signed portfolio) must succeed in the same workflow run and attempt. Their job
outputs pin the source revision, workflow revision, run, attempt and SHA-256 of
the archive entrypoint. The consumer checks the exact producer inventory, every
file hash/size, runtime identity and scope flags before freezing the input
bytes. It rejects missing/extra files, stale evidence, ambiguous JSON, symbolic
or special files and exceeded file/inventory budgets.

The current compiler then regenerates each fixture's AST, canonical graph,
manifest and native projection. These must match the archived inputs byte for
byte. Parsed source fixtures use the stable logical name `composition.flow`;
source contents and their changes remain part of the evidence. Native observation
envelopes and deliberate mutants are regenerated using the existing independent
oracles. Those oracles replay the archived runtime observations and compare their
derived cases with the published cases. An authored `passed` field cannot replace
this replay. Cross-runtime conditions and workspace transfer also recheck native GitHub job results
against the same-attempt GitHub API.

This consumer starts no runtime. The existing producer jobs still execute every
native baseline and mutant. CI runners, the compiler, native observers and GitHub
job outputs/API remain trusted. These receipts are CI bindings, not cryptographic
signatures or additions to the signed portfolio.

## Bounded observations

| Composition producer | Target rows | Construct | Remaining boundary |
| --- | --- | --- | --- |
| `cross-runtime-condition-composition` | Jenkins, GitHub Actions | Conditions | Bounded Boolean inputs, branches and ordering; no general control/value transfer |
| `retry-composition-runtime` | Jenkins | Retry | Fixed zero-delay retry with supported sequential/condition/nested retry bodies |
| `loop-composition-runtime` | Jenkins | Loops | Finite literal string iteration, lexical item bindings and fixed zero-delay retry in both nesting directions |
| `match-composition-runtime` | Jenkins | Match | Inert literal string sources and exclusive literal Boolean cases, composed with fixed zero-delay retry and finite string iteration |
| `parallel-composition-runtime` | Jenkins | Parallelism | Top-level groups, branch-local sequences, workspace routing and authored failure policy |
| `github-workspace-composition` | GitHub Actions | Artifacts/workspaces | Unconditional and condition-scoped producers, single-source fan-out, literal nested guards and regular-file bytes; no branch escape or general VALUE/STATE transfer |

AR-06AE-C10 extends the existing loop producer to distinguish retry around a loop
from retry inside each iteration. The first replays completed iterations on a new
attempt; the second retains the current loop item and does not repeat completed
iterations. Both directions retain success, false-condition, empty-collection and
permanent native failure baselines. Mutants flatten retry, repeat successful work,
change collection order/multiplicity, invent an iteration or swallow a failure.
The independent consumer regenerates and replays these additional cases through
the same producer. The primary matrix construct stays `LOOPS`; the attached claim
and cases explicitly describe its interaction with retry. It does not manufacture
another signed assessment or imply general retry/state isolation. Transient-fault
budget reset also has oracle regression coverage, but is not claimed as a native
observation by these permanent-fault/success fixtures.

AR-06AE-C11 extends the existing match producer with retry around selection and
retry inside a selected branch, including finite loops in both directions.
Selection stays exclusive on every attempt and iteration. Default branches use
the same recursive contract; an empty selected branch never falls through.
Source-derived traces distinguish whole-body replay from retry of the selected
body, preserve duplicate elements and order, and suppress successors on failure.
Mutants substitute the default, flatten retry, repeat successful bodies, alter
iteration order/multiplicity or swallow failure. All previous match observations
remain required. The same admission/replay consumer publishes the additional
cases on the primary `MATCH` row; no new producer or signed assessment is added.

AR-06AE-C14 admits the existing GitHub workspace experiment. Its reusable workflow
exposes the collector's receipt; the consumer requires that receipt alongside all
previous producers. The current compiler regenerates the parsed AST, graph,
manifest, projection and compiler-checked execution envelope with the exact source
revision. The expected text, binary and hidden-file inventory comes from that
checkout, not from an assertion in the archive.

The independent replay verifies selected and skipped consumers, file hashes and
sizes, the missing-transfer and hidden-file failures, and execution of the
guard-omission mutant. It queries the same-attempt GitHub API again and checks the
exact native job inventory, source/run/attempt identities, final outcomes,
required observer/falsification steps and preparation/producer-before-consumer
timing. Rehashed archives, claimed `passed` values and changed case lists cannot
replace these checks. GitHub runners and job-output observations remain trusted;
the successful falsification step asserts the original failed build even though
GitHub reports its `continue-on-error` step as successful.

Workspace proof format v1 uses flat source/workflow/run/attempt provenance and
`runtimeKind: github-actions-jobs`, with five compiler inputs, `expected.json`,
`observations.json`, `github-jobs.json` and `proof.json`. Its native job identities
replace a container-image identity for this producer only. Existing Jenkins
composition archives retain their mandatory image digests. Older workspace
archives with nested provenance and uppercase `PASS` are not admitted; regenerate
the complete workflow on the current revision. This is a CI evidence format, not
a product artifact or signed portfolio schema change.

AR-06AE-C15 extends that same producer with selected and skipped conditional
producers. Source-derived branch paths must establish producer availability for
each consumer; equal predicate text in separate branches is insufficient. The
oracle checks three distinct producer artifacts, thirteen baseline jobs and four
mutants. The new mutant removes a skipped producer's guard and must actually
checkout, upload and observe bytes before replay rejects the extra execution.
Same-attempt API authentication now covers eighteen jobs including preparation.
Skipped jobs must have no steps or observations. Their API timestamps are retained
as scheduler metadata, not execution intervals: GitHub may report completion
before start for a skipped job. Executed jobs still require valid intervals and
preparation/producer-before-consumer ordering; skipped timestamps cannot prove it.
All earlier baseline jobs and mutants remain required. The primary matrix row,
archive format, seven-producer receipt inventory and signed portfolio stay unchanged.

The complete per-producer claims and limitations accompany the published report.
Every other target/construct cell remains `NO_COMPOSITION_EVIDENCE`, including
general value/state continuity. Absence of evidence is not a declaration that a
feature is unsupported. A bounded observation never upgrades the signed portfolio
status or erases its explicit gaps. Adding a producer requires a separately
reviewed native contract and evidence, not a new declaration in this table.

## Artifact contract

The `adapter-composition-coverage` artifact contains:

- `portfolio.json/md` and `target-coverage.json/md`: unchanged signed publication bytes.
- `signed-publication.json`: the unchanged original portfolio publication manifest.
- `composition-coverage.json/md`: format v1, source/workflow/run/attempt provenance,
  producer receipts, regenerated input digests, replayed cases and the complete
  target/construct matrix with distinct `signedPortfolioStatus` and
  `compositionStatus` fields. `publicSupportPromoted`, `portableExecution` and
  every row's `targetWideCertificationEstablished` are false.
- `publication.json`: format v1, provenance and SHA-256/byte-size receipts derived
  from the actual seven companion files. It does not hash itself.

Publication requires a fresh output directory and uses a sibling staging
directory followed by rename. Failed validation produces no accepted output.
Consumers must validate the publication manifest and preserve the evidence-class
distinction; the companion report is not a replacement signed portfolio.

CI retains native archives and focused JVM test reports as separate artifacts.
The native composition archives now have their evidence files at the archive
root, allowing exact inventory checks without path guessing. Artifacts retain
the existing seven-day retention. All existing certification jobs and signed
assessments remain required.

### Workspace producer-failure observations

C16 extends the same GitHub workspace archive with two selected producer failures (native checkout and native upload), their two blocked consumers and two forced-consumer mutants. The source-bound oracle requires the exact failed/skipped native steps, absence of baseline consumer execution, and actual download/build failures in each mutant. Producer errors are never suppressed. A standalone fault workflow must fail with the two expected producer failures and a successful collector. A positive observer pins its archive under the parent certification run, retaining the native run/attempt in the receipt. Independent replay authenticates the native repository, workflow path, source/workflow revisions, latest attempt and collector before accepting observations. The proof carries the failed producer and mutant API step records; independent replay checks them against the authenticated native run-attempt API and regenerated compiler inputs. All previous baselines and mutants remain required. This does not promote the signed portfolio or establish arbitrary failure/cancellation/recovery support.
