# Composition coverage admission

The `adapter-composition-coverage` job in Adapter Runtime Certification publishes
coverage for every declared target and construct. It keeps two evidence classes
separate: authenticated signed portfolio scenarios and CI-bound composition
observations. Neither class establishes target-wide support or portable execution.
The `targets` CLI remains a repository reference/declaration report.

## Admission and replay

All six producers must succeed in the same workflow run and attempt. Their job
outputs pin the source revision, workflow revision, run, attempt and SHA-256 of
the archive entrypoint. The consumer checks the exact producer inventory, every
file hash/size, runtime image identity and scope flags before freezing the input
bytes. It rejects missing/extra files, stale evidence, ambiguous JSON, symbolic
or special files and exceeded file/inventory budgets.

The current compiler then regenerates each fixture's AST, canonical graph,
manifest and native projection. These must match the archived inputs byte for
byte. Parsed source fixtures use the stable logical name `composition.flow`;
source contents and their changes remain part of the evidence. Native observation
envelopes and deliberate mutants are regenerated using the existing independent
oracles. Those oracles replay the archived runtime observations and compare their
derived cases with the published cases. An authored `passed` field cannot replace
this replay. Cross-runtime conditions also recheck native GitHub job results
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
| `match-composition-runtime` | Jenkins | Match | Inert literal string sources and exclusive literal Boolean cases |
| `parallel-composition-runtime` | Jenkins | Parallelism | Top-level groups, branch-local sequences, workspace routing and authored failure policy |

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
