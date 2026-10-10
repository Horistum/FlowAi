# Adapter certification bundle: AR-06A candidate

Current implementation: AR-06L aggregates the authenticated Jenkins portfolio;
AR-06M adds a separately scoped native GitHub Actions checkout experiment,
described at the end of this document.
Earlier candidate restrictions below record their original preparation boundaries;
PRs through #209 are now merged.

Horistum's AR-06 work starts with a typed, internal `AdapterCertificationBundle`
and `AdapterCertificationAdmission` in `flow-adapter-evidence`. This is a
candidate evidence contract. Existing target support, rendering authorization,
published schemas and target maturity remain unchanged.

This change is stacked on PR #197. AR-06 activation still requires that
predecessor's merge and actual-main validation. It closes none of the AR-06
findings and does not resume EF-09.

## Contract and admission

The bundle contains an adapter identity/version/implementation digest, explicit
semantic, structural and native-leaf coverage, runtime prerequisites, limitations,
positive scenarios and negative mutants. It reuses
`TargetStructuralProjectionKind` and the provider's actual native catalog.
It does not introduce another support-status registry.

| Input | Admission rule |
| --- | --- |
| Adapter identity | Exactly matches the independently supplied expected identity and provider target. |
| Semantic inventory | Every capability supplied by the canonical caller has one row; invented and missing rows fail. |
| Structural inventory | All existing structural kinds have exactly one row, including uncovered kinds. A covered kind requires a provider implementation. |
| Leaf inventory | Exactly matches the composed provider's kind/reference pairs. Native leaves cannot prove their parent structure. |
| Coverage | Each scenario is referenced; dangling, orphaned and duplicate identities fail. Empty scenario lists mean uncovered, with a required limitation. |
| Positive scenario | Pins canonical graph, fixture, target artifact and expected normalized observable bytes. |
| Negative mutant | Uses the baseline graph/fixture/runtime with changed artifact bytes and a specified different observable result. |
| Independent run | Pins adapter, scenario/mutant, input digests, artifact, observation and runtime versions. Each run has a distinct identity. |
| Polarity | Baseline and every mutant must complete with their exact expected observations. An unrelated difference, surviving mutant, timeout or infrastructure failure fails admission. |
| Evidence | Every opaque reference is resolved once, within shared I/O budgets, and checked against actual size and SHA-256. Missing, conflicting or altered evidence fails. |

Expected and observed records have different identities even when their bytes
match. The evidence resolver is supplied by the caller; this component does not
interpret IDs as paths, fetch URLs or execute target artifacts. Resolver errors
produce stable codes without publishing exception text. The caller must bound
I/O before allocation. The admission layer checks per-object and aggregate
budgets before resolution and validates returned bytes.

Admission is atomic across the candidate: any finding produces no admitted
scenario IDs. Reports have immutable lists and deterministic ordering. A complete
uncovered inventory can pass integrity with **zero admitted scenarios**.

## Trust and semantic boundaries

`valid` means the supplied contract, identities, bytes and mutation polarity
are consistent. It does **not** authenticate a runner, establish that artifacts
were executed, parse/authorize a canonical graph or prove that a fixture covers
the semantic subject it names. The caller owns those independent obligations.
Expected adapter identity and semantic inventory must not be derived from the
candidate being checked.

Conformance owns observation normalization and semantic adequacy, including the
existing C0.3 equivalence contracts. The admission component compares normalized
bytes without inventing another semantic model. Real adapter runners must derive
observations from actual execution and authenticate their provenance before
supplying records. A constructor or a self-authored successful run is not proof
of runtime behavior.

This first slice exposes no public support projection or portable-execution
claim. The current CLI and maturity publisher continue to use their existing
authorities. Later slices must populate real adapter-owned bundles, bind the
construct/target/behavior/mutant matrix to canonical requirements, integrate
authenticated runner evidence, then migrate public views through one authority.
At least two materially different adapters must execute equivalent canonical
scenarios before portable execution can be claimed.

## Verification

Module tests falsify adapter/input/artifact/runtime identity, complete coverage,
run uniqueness, missing/corrupt evidence, aggregate limits, baseline failure,
ineffective mutants and runner failure. The physical adapter isolation proof runs
these tests after deleting concrete adapters, distribution, CLI and conformance
sources. An integration test checks the actual Jenkins, GitHub Actions and Tekton
native inventories without certifying them. Existing standalone conformance
checks and historical evidence remain in place.

## AR-06B: compiler/rendering-bound scenario inputs

After PR #197 and PR #198 merged and actual-main CI passed, AR-06 is active.
The earlier activation restriction above describes the AR-06A preparation
boundary. Its accepted receipt is preserved in
`.flow-agent/evidence/adapter-certification-activation-baseline.json`.

`BoundCertificationScenario.capture` accepts an authorized compilation request,
original source bytes and explicit materialization/rendering authorities. It
checks the compiler source digest, validates the graph before and after adapter
processing and captures the generated executable artifact. A caller cannot
construct a bound scenario from a self-authored manifest or receipt. Evidence
resolution returns defensive copies. The graph evidence encoding is internal;
its byte SHA is distinct from `graphDigest`, the existing compiler semantic
identity. Neither is a new public graph schema.

`BoundAdapterCertificationAdmission` derives semantic inventory and exact
subject-to-scenario occurrence associations from these captures. Structures come
from canonical node kinds; native leaves come from generated manifest payloads
matched to the provider catalog. A leaf cannot stand in for a parent structure.
Missing, additional or borrowed associations fail before external evidence I/O.
It then delegates the existing independent observation and mutant checks to
AR-06A, resolving captured inputs itself. The generic AR-06A API remains an
integrity-only contract; it must not be relabeled as compiler-bound admission.

This path binds inputs and occurrence scope, not semantic adequacy or execution
provenance. Injected adapter composition and expected implementation identity
remain trusted caller inputs. Runtime fixtures, controls and observable behavior
still need independent conformance design and authenticated execution evidence.
No support view consumes this candidate path yet, and no target is promoted.

## AR-06C: authenticated runner observations

`AuthenticatedAdapterCertificationAdmission` adds runner-origin verification to
the compiler/rendering-bound path. The assessment owner supplies a
`CertificationObservationTrust` independently of the candidate: trusted Ed25519
public keys, a fresh unpredictable challenge (32..128 URL-safe characters), and
an exact `CertificationAuthorizedRun` inventory. Allocate at least 128 bits of
randomness for each challenge, persist the authorized run mapping, and never
reuse a challenge. This component has no durable replay store or key discovery.

An external runner derives an observation from execution, obtains signing bytes
from `CertificationObservationAuthentication.signingBytes`, signs them with its
own Ed25519 private key, and submits a `SignedCertificationObservation`. The
private key and signing operation stay outside Flow. Configure trusted public
keys through an independent channel; a candidate-supplied key proves nothing.

The internal version-1 signing input starts with UTF-8
`Flow adapter execution observation`, a NUL byte, `v1`, and another NUL. It then
encodes, in order: runner ID, assessment challenge, run ID, adapter target/ID/
version/implementation SHA, scenario ID, mutant presence byte (0 or 1) and optional
mutant ID, graph SHA, fixture SHA, artifact reference, observation reference,
runtime count and ordered runtime ID/version pairs, and outcome enum name.
Every string is strict UTF-8 preceded by its 32-bit big-endian byte length.
References contain ID, SHA and a 32-bit big-endian size. Runtime count uses the
same integer encoding. No separator concatenation or Unicode replacement occurs.
The helper is the in-process format owner; this is not a published wire schema.

Admission caps the run inventory at 256 and each statement at 65536 bytes, with
4096 characters per field. It rejects wrong challenge/runner/run assignments,
missing/duplicate/extra runs and invalid signatures before resolver I/O. Signature
bytes, runtime lists and trusted inputs are copied. Only fully authenticated
observations reach bound admission, whose byte, scope, runtime and mutant checks
still apply. Runtime-list order is signed even though integrity admission compares
runtime prerequisites as sets.

Use this entry point when runner authentication is required. The earlier APIs
retain their documented integrity-only meaning. A valid signature proves origin
under the configured trust policy; it does not prove runner honesty, semantic
adequacy or actual platform execution. Real behavioral runners, key provisioning,
revocation, observation normalization and the construct/target/behavior/mutant
matrix remain follow-up work. No public target support is promoted in this slice.

## AR-06D candidate: real Jenkins native checkout

The first runtime fixture lives in `flow-adapter-jenkins/src/runtimeTest` and has
an explicit behavior/mutant matrix. The dedicated
`:flow-conformance-kit:verifyJenkinsCheckoutRuntime` task runs a disposable real
Jenkins controller with the adapter's compiler-generated artifact. It observes
workspace bytes for the baseline, an omitted checkout and a substituted branch,
then admits signed observations through the AR-06C path. The ordinary unit suite
does not start Jenkins. The separate runtime CI job is mandatory for this claim.

This candidate is stacked on PR #200 until its merge and actual-main validation
are observed. It establishes only native checkout behavior on the recorded runtime,
not structural equivalence or portability to GitHub Actions. See the adapter's
runtime-test README for execution, evidence and trust boundaries.

## AR-06E: native failure propagation candidate

The adapter-owned `failure.intent.yaml` adds two explicitly dependent checkout
operations with propagating failure policy. Real Jenkins must report the exact
missing-revision error and never execute the dependent checkout. Omission and
error-suppression mutants must expose the changed marker, step count and error
observations. The external controller observes these facts without instrumenting
the positive Jenkinsfile.

Run `:flow-conformance-kit:verifyJenkinsFailureRuntime` with JDK 25, Docker and a
fresh output directory. CI publishes `jenkins-failure-runtime` evidence separately
from the existing checkout proof. Deliberate scenario failure can be a completed
execution; unexpected runtime errors, aborts and incomplete records remain
rejected. The original checkout scenario still requires all builds to succeed.

This is a stacked candidate after PR #201 merged into PR #200. Actual-main
acceptance remains pending. No public schema, support/maturity label or version
changes; general error-boundary, retry and portability claims remain unproven.


## AR-06F: evidence-derived diagnostic views

`CertificationEvidenceViews.assess` runs the existing authenticated, compiler-bound
admission and returns a view only when every signature, run, occurrence and evidence
byte passes. It accepts the complete candidate inputs and caller-owned trust, never
an authored PASS report. Inputs are frozen before resolver I/O so a callback cannot
change the scope that is displayed after admission.

Each runtime assessment now emits:

- `evidence-view.json`: deterministic internal diagnostic data, including exact
  source, graph, run, artifact and observation references, runner key fingerprints,
  runtime prerequisites and limitations.
- `evidence-view.md`: an escaped human-readable coverage table and scenario/run
  tables, also displayed in the GitHub Actions job summary.
- `proof.json`: the existing proof with SHA-256 values for both view files.

`OBSERVED_IN_SCENARIO` means only that the construct occurs in an admitted bounded
scenario. `NOT_OBSERVED` is missing evidence in that assessment, not an unsupported
feature. Scenario shapes distinguish native leaves, structural occurrences and
semantic-only occurrences; none implies general semantic adequacy or an executable
reference promotion. Public support and portable execution remain unpromoted.
All unobserved structural rows remain visible, including for the failure scenario.

No product CLI option, public wire schema, target registry or package version
changes. The same external Jenkins Gradle tasks regenerate both formats. Consumers
must retain the trust declaration and original proof/evidence; a copied diagnostic
view alone cannot authorize rendering or establish independent runtime trust.
Equivalent execution on a second adapter and wider behavioral coverage are still
required before replacing public support/maturity registries.

## AR-06G: real conditional execution

The adapter-owned `condition-true.flow` and `condition-false.flow` compile through
the production Flow Source frontend and canonical compiler. Each source declares
a boolean default and two complementary equality guards around native checkout
children. The positive Jenkinsfile is executed byte-for-byte as rendered.

These fixtures exposed a compiler defect: boolean literals previously survived
only in `PlanInput.defaultExpression`, so target input projection lost a true
default and Jenkins substituted false. `FlowPlanner` now also carries authored
boolean literals in the existing `defaultValue` field. Both Flow Source and
Intent inputs cross this same correction. Omitted defaults remain absent; no
symbolic expression is evaluated and no schema field or version changes.

Run `:flow-conformance-kit:verifyJenkinsConditionRuntime` to execute both defaults
and the flattened/inverted guard mutants. The `jenkins-condition-runtime` CI job
archives separate `true/` and `false/` assessments, each with its own trust
challenge, signed observations, original source/graph/artifacts and diagnostic
views. Ordinary tests do not start Jenkins.

| Scenario | Baseline | Flattened guards | Inverted guards |
| --- | --- | --- | --- |
| Default `true` | `selected`, one checkout | `alternate`, two checkouts | `alternate`, one checkout |
| Default `false` | `alternate`, one checkout | `alternate`, two checkouts | `selected`, one checkout |

Every build must finish successfully with no native checkout errors. Checkout
count is part of the signed observable bytes: equal workspace contents cannot
hide an extra child execution. Unexpected errors, missing/incomplete runs,
substituted scripts and surviving mutants fail admission.

These views identify `STRUCTURAL_OCCURRENCES`, with condition coverage confined
to the corresponding source. Other structures remain unobserved. This proves
the two boolean equality guards on the recorded runtime; it does not certify
external parameter overrides, other expressions, general condition semantics,
error boundaries or cross-target equivalence. Existing support/maturity labels,
public schemas and package versions remain unchanged.

## Workflow error-boundary evidence (AR-06H)

Workflow handlers live in canonical workflow failure policy rather than local
`TRY` nodes. Bound certification now retains their `ERROR_BOUNDARY` occurrence;
otherwise a captured workflow handler could be mislabeled as native-leaf-only.
The existing coverage admission requires that occurrence to remain associated
with its own scenario. This correction changes no graph or public schema.

The adapter-owned successful/failing Flow Source fixtures exercise the existing
`PROPAGATE` contract on real Jenkins. Six builds compare original artifacts with
omitted handler, suppressed propagation, unconditional handler and omitted body
mutants. Native checkout errors and the terminal error's originating checkout
are observed separately: a failure status cannot substitute for the required
handler action, and an unrelated error cannot substitute for propagation.

Run `:flow-conformance-kit:verifyJenkinsErrorBoundaryRuntime`. Its CI job archives
separate `failure/` and `success/` signed assessments and JSON/Markdown views in
`jenkins-error-boundary-runtime`. The new scenarios include `terminalError`
inside their opaque signed observation bytes; existing scenario observations and
the public certification contract are unchanged. Only the recorded native
missing-revision error, originating from the first checkout, is intentional.

These are bounded workflow-handler observations. Local recovery, retries,
cancellation, general exception semantics and equivalent execution on another
adapter remain separate obligations. Public support/maturity and version claims
remain unchanged.

### AR-06I: bounded local recovery

The conformance-only Jenkins runner now includes successful and failing local
`try` / `on error` scenarios with normal continuation. Five mutants falsify lost
handlers, accidental propagation, missing continuation and unconditional handler
execution. This uses the existing local canonical error boundary and renderer;
it changes no public syntax, schema, product runtime or support claim.

The new scenarios authenticate ordered native checkout error positions together
with completion, result, workspace marker, checkout counts/errors and terminal
error origin. Existing scenario observation formats remain unchanged. Evidence
views remain bounded to the recorded occurrence and runtime. Run the new
`verifyJenkinsLocalRecoveryRuntime` task with fresh evidence directories as
described in the adapter runtime README; ordinary tests never start Jenkins.
## AR-06J: observed manual approval decisions

Certification now includes provider-owned approval payloads in the native leaf
inventory. Existing checkout scenarios expose Jenkins `input` as unobserved;
approval scenarios may cover it only when that leaf occurs in their compiler-bound
artifact. A profile or an approval on another scenario cannot supply that coverage.

The new bounded Jenkins scenarios share one source, canonical graph and original
artifact. An independent controller observes a pending native input before issuing
an automated approval or rejection. It records the pre-decision workspace and
checkout count as well as final native steps and error origin. This detects gates
omitted or moved after the protected checkout even when final results look correct.
Suppressed rejection is independently falsified on the rejection path.

The evidence remains `native-leaf-only` / `NATIVE_LEAF_ONLY`: a manual input and one
checkout, without structural projection or general semantic adequacy claims.
Generated views remain bounded observations, with public support and portable
execution false. No human identity, submitter authorization, timeout, restart,
concurrent approval or general change-control policy is certified. This does not
resume EF-09 or complete AR-06.

## AR-06K: construct behavior matrix

Each admitted external Jenkins assessment also produces `behavior-matrix.json`
and `behavior-matrix.md`, with hashes recorded in `proof.json`. The internal
format lists all eleven roadmap construct categories and a bounded native checkout
case. `BOUNDED_SCENARIO_EVIDENCE` belongs only to the assessed scenario's category;
`NO_BEHAVIORAL_EVIDENCE_IN_ASSESSMENT` explicitly leaves all other categories open.
Neither value is public support status.

The conformance-owned scenario catalog assigns the category and requires its
compiler-bound subject. Graph occurrence does not establish behavioral adequacy.
Each populated row retains source/graph identity, adapter/runtime identity,
limitations, baseline and negative mutants, signed run references and the exact
expected/observed UTF-8 JSON strings. Admission and display-time size/hash checks
must pass. The full observations distinguish mutants even when their terminal
result and workspace marker equal the baseline. JSON is a diagnostic snapshot,
not reusable admission input or an execution authorization.

The scope is one assessment on one target. Missing rows do not claim target
inability, and combining files is not cross-target equivalence. Checkout evidence
does not establish artifact transfer, secrets or general value/state continuity.
No second-adapter runtime or portable-execution claim is introduced.

## Reauthenticated reference adapter portfolio (AR-06L, extended by AR-06N)

The dependent `adapter-certification-portfolio` CI job publishes one `portfolio.json`
and `portfolio.md` after all six Jenkins runtime jobs and the GitHub Actions runtime job succeed.
It covers eleven current scenarios and every construct/target pair in the reference adapter catalog.
Targets with no submitted behavioral evidence remain explicitly unobserved. The
catalog supplies target identities only; it cannot grant coverage.

Each producer exports exact proof and trust SHA-256 hashes through its job outputs.
The consumer downloads artifacts from the same workflow run and receives those
outputs through `needs`, separately from the downloaded candidates. Missing jobs,
failed jobs, duplicate scenarios and mixed revisions fail the complete portfolio.
Never derive the expected trust hashes from the candidate files being checked.
The CI service and producer host remain trusted; this is not external attestation
or durable production-runner trust.

The consumer recompiles the selected source, captures the current canonical graph,
rebuilds the original and mutant artifacts and compares the complete adapter bundle.
It reconstructs observations from raw native runtime results, rechecks Ed25519
signatures against the pinned trust and reruns authenticated admission. Existing
views and matrices must exactly match the regenerated bytes and their proof hashes.
An archived `passed` field or a concatenation of JSON matrices is insufficient.
Input files have byte limits, must be regular files and cannot traverse symlinks.
Output is published from a fresh staged directory only after every assessment passes.

For a local replay, arrange the seven artifact directories under
`build/certification-inputs/`, use JDK 25, set `FLOW_CERTIFICATION_REVISION` to the
checked-out commit and supply the original producer job outputs in
`FLOW_CERTIFICATION_JOB_RECEIPTS`. This variable has the same JSON shape as
GitHub Actions `toJSON(needs)`: seven job names, each with `result: "success"` and
`outputs.receipt` containing the producer's JSON receipt string. Then run:

```sh
./gradlew --no-daemon :flow-conformance-kit:verifyAdapterCertificationPortfolio
```

Use a fresh `flow-conformance-kit/build/adapter-certification-portfolio` destination.
This command does not start Docker or Jenkins. The installed product CLI does not
include this conformance-only entry point.

The JSON retains all eleven individual matrices, including source/graph references,
adapter identity/version/implementation digest, runtime image/plugin versions,
runner identity/key fingerprint, exact expected/observed bytes, mutants and limits.
The compact Markdown table points each covered pair to its scenario IDs. Different
scenario-specific adapter identities or runtime images are never flattened into a
target-wide certification. No scenario transfers evidence to another target or
construct. In particular, checkout does not prove artifact/workspace transfer,
secret handling or value/state continuity.

`BOUNDED_SCENARIO_EVIDENCE` and `NO_BEHAVIORAL_EVIDENCE_IN_PORTFOLIO` describe this
assessment inventory only. They do not replace public `SUPPORTED`/`UNSUPPORTED`
statuses or the declared/analyzable/renderable/executable maturity distinctions.
Public support and portable execution remain false. Equivalent execution on a
second materially different adapter and the remaining behavioral oracles are still
required before broader claims can be made.
## Native GitHub Actions checkout candidate (AR-06M)

The `github-actions-checkout-runtime` CI job provides bounded second-adapter
evidence, included in the portfolio from AR-06N. It compiles `flow-adapter-github-actions/src/runtimeTest/checkout.intent.yaml`,
binds its actual GitHub Actions rendering and checks the single native leaf against
literal workflow blocks before execution. The workflow selected by GitHub must
match the candidate's envelope bytes. The provider then runs baseline and
substituted-revision `actions/checkout@v4` steps; an empty native block represents
omitted checkout. Each observation starts from a cleared workspace and records
actual Git HEAD plus the fixture file's SHA-256.

The `github-actions-checkout-runtime` artifact contains three signed observations,
their source/canonical/artifact bindings, execution envelope, public trust,
admission proof and JSON/Markdown views and behavior matrices. Runtime prerequisites record the source
and executed workflow revisions, hosted image version, action reference and
envelope digest. Assessment trust is created before execution; the signing key
stays outside uploaded evidence. It is a same-host trusted-observer experiment,
not protection against a compromised provider action or runner.

The claim is `native-leaf-only`, with execution mode
`native-leaf-in-checked-envelope`. It does not certify the complete generated
workflow, mutable action binaries, scheduling, triggers, credentials or workspace
transfer. It does not establish cross-target equivalence or promote public support.
AR-06N extends the original ten-assessment Jenkins portfolio with this assessment.
The dependent job requires its successful producer receipt, pins proof and trust
outside the downloaded archive, recompiles source and mutants, checks the exact
execution envelope, reconstructs native observations and verifies every signature.
The public trust and proof must agree on source/workflow revision and workflow
run/attempt; producer-owned hashes bind the original metadata. Raw records,
admission, evidence views and behavior matrices must match the replayed bytes.
Missing or altered inputs prevent publication of the complete portfolio.

The resulting portfolio contains eleven assessments, thirty-five signed runs and
thirty-six construct/target rows. Its GitHub Actions `NATIVE_CHECKOUT` row is the
only newly observed pair. Every assessment carries an explicit claim and execution
mode; structural GitHub Actions rows remain unobserved. The Jenkins and GitHub
Actions checkout fixtures and canonical graphs differ. Aggregating them does not
prove observable cross-target equivalence. Executing a shared canonical fixture
on both providers remains the next bounded behavioral task.
