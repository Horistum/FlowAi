# Target maturity report v2

The `targets` command now reports repository reference maturity separately from
native runtime certification. Version 1 could publish `BEHAVIORALLY_CERTIFIED`
after finding an executable reference snapshot and existing implementation/test
paths. Those checks neither executed the tests nor authenticated native results.
Version 2 removes that unsupported claim.

## Consumer migration

The `FLOW TARGET MATURITY REPORT` section retains its title. Its `reportVersion`
changes from `1.0` to `2.0`; consumers must handle the new version explicitly.

| Field or value | Version 2 meaning |
| --- | --- |
| `evidenceBasis: REPOSITORY_REFERENCE_INVENTORY` | The publisher checks repository reference records, snapshots and evidence paths. |
| `runtimeCertificationAssessed: false` | No native runtime result is authenticated or assessed by this report. This is not a negative runtime verdict. |
| `executionAuthorized: false` | The report never authorizes execution of a current plan. |
| Scope stage `REFERENCE_EXECUTABLE` | A valid inventory points to an executable reference snapshot with complete source, implementation/test path and limitation records. |
| `assessments[].structuralProjections` | Structural definitions from the composed provider, sorted by structure, including their declared `supportScope` and evidence references. An absent provider yields an empty list. |
| `status: PASS` | Reference validation and declaration reconciliation succeeded. It does not mean tests ran, a current plan is executable, or a target is certified. |

The cumulative stage order is `DECLARED`, `ANALYZABLE`, `RENDERABLE`,
`REFERENCE_EXECUTABLE`. Target-wide maturity stops at `RENDERABLE`; only a
validated reference scope can reach `REFERENCE_EXECUTABLE`. The old `EXECUTABLE`
and `BEHAVIORALLY_CERTIFIED` values are removed. Do not translate either old value
into native runtime certification. Recompute reference maturity from the current
inventory: missing or invalid scope evidence now prevents the final reference
stage, even when the referenced snapshot itself was executable. An invalid input
document kind/version invalidates every reference scope. Duplicate identities
invalidate each assessment of that scope, while unrelated valid scopes retain
their own reference maturity.

The raw registry section is renamed from `FLOW TARGET CAPABILITY MATRIX` to
`FLOW TARGET CAPABILITY DECLARATIONS`. Registry statuses remain declarations.
A `PLAN_SCOPED` structural mapping is available only when the provider's plan
resolver, native leaf mappings, lexical bindings and execution gates all accept
the concrete plan. Its presence does not promote target-wide support. Even a
`TARGET_WIDE` mapping declaration is not authenticated behavioral evidence.

## Choosing evidence

Use the ordinary target validation/rendering path for a current plan's outcome.
For example, the historical GitHub Actions checkout/build reference can remain
in this inventory while the same source is currently rendered as `REVIEW_ONLY`.
The installed CLI regression exercises this distinction.

Use the certification workflow's `target-coverage.json` and
`target-coverage.md` for authenticated native evidence. Those reports derive
from an opaque, reauthenticated certification portfolio and preserve its source
revision, proof/trust digests, bounded scenarios, explicit gaps and limitations.
They do not promote general support or portable execution. Composition job
success outside that portfolio is not silently included as certified coverage.

The reference evidence YAML remains version `1.0`: its input shape is unchanged.
Historical acceptance receipts and archived snapshots are unchanged. The public
Core language and canonical artifact schemas are unchanged.
