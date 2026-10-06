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
