# Adapter certification bundle: AR-06A candidate

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
