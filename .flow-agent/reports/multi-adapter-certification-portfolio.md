# AR-06N candidate: authenticated multi-adapter portfolio

The runtime portfolio now replays all eleven existing assessments from Jenkins
and GitHub Actions before publishing its JSON and Markdown snapshot. The second
producer's successful same-run job output pins its proof and trust separately
from the downloaded candidate files. Missing or failed producers reject the
complete inventory.

Replay reconstructs the GitHub Actions scenario with the selected compiler,
source, canonical graph and adapter. It checks baseline and mutant artifact bytes,
the executed workflow envelope, owner metadata, native observation records and
Ed25519 signatures. Published admission, views and matrices must match freshly
derived bytes. File budgets and symlink confinement apply to both providers.

The existing matrix implementation is shared behind provider-specific oracle
entry points. No new authority or public support model is introduced. Jenkins
matrix behavior is preserved. GitHub Actions contributes only NATIVE_CHECKOUT;
all structural rows remain unobserved. Eleven assessments contain thirty-five
signed runs across thirty-six construct/target rows. Explicit per-assessment
claims and execution modes preserve the native-leaf-in-checked-envelope scope.
The two checkout fixtures and canonical graphs differ, so no cross-target
equivalence or portable execution is established.

## Predecessor boundary

PR #210 merged as c85a62a39d412b3e9f323c0cb58bcaadb3b864da with tree
f4f77c2bb630f019e1073947d6609aa10f88170b, identical to validated PR head
ba23ccfa2b4ba8c266272a63defacaf6a77bca2d and merge candidate
d2df0d70517289cdbd699dccdb0f46db060242d3. Flow CI 38046478110 passed
2,013 tests in 347 suites and 274 ordered conformance checks on each revision.
Runtime CI 38046478081 passed all eight jobs. The GitHub Actions archive and
three signatures were independently verified during that implementation.
Main Flow CI 38050155031 and runtime CI 38050155029 also passed; no independent
main test recount is claimed here.

The immutable candidate transition receipt is
`.flow-agent/evidence/multi-adapter-portfolio-baseline.json`, SHA-256
`cf913d74094d247578a05122a5c23c93cebfaa65a3148f40833d3c7f7d6020a3`.
It preserves the frozen AR-06M receipt and all earlier acceptance boundaries.

## Validation and remaining work

Tests exercise both complete providers, distinct fixtures, receipt ordering,
missing/failed/substituted producers, forged keys/signatures, changed native
records, stale metadata, broadened claims, changed envelopes/artifacts/views,
run inventory tampering and file limits. Synthetic protocol tests do not claim
provider execution. The candidate requires both full revision checks, physical
module isolation, all seven producer jobs and portfolio replay on GitHub.
Current candidate results are recorded in its PR after validation.

AR-06 remains active, only A/B formally accepted. A shared canonical scenario on
both providers and remaining behavioral coverage are follow-up work. AR-07 stays
planned, EF-09 paused and F-20 AR-07-owned. Core, production renderers, package and
public standard versions are unchanged; no support promotion or finding closure.
