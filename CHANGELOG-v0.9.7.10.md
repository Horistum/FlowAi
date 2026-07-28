# v0.9.7.10 Correction Track

## Unreleased

### v0.9.7.10.1 Standard and Closure Integrity Correction

Reopened `v0.9.7.10 Bounded Semantic Closure Gate` after a post-merge audit proved that the completed closure claim did not establish complete conformance-suite presence, accepted unknown bounded-correction statuses and retained drift between the public standard model and the actual conformance producer.

Implemented correction scope:

- declare the exact 90-check pre-closure conformance sequence independently from the runner;
- reject missing, duplicate, reordered and unexpected checks;
- reject missing and unknown correction statuses through the shared `FlowYaml` boundary;
- separate the unchanged public 0.8.0 release profile from durable package-level conformance identities;
- remove non-runner pseudo-check identities and reconcile modeled checks against the complete inventory;
- replace count-ratio purpose gates with target-neutral structural coverage and evidence requirements;
- keep the package registry-consistency owner outside the public release profile;
- assign actual DIAGNOSTICS and REGISTRY_CONSISTENCY owners;
- replace string-prefix export membership with explicit model data;
- remove `KUBERNETES_MAINTENANCE` from universal mandatory purpose coverage;
- derive internal artifacts from explicit artifact visibility;
- retain only the compiled classpath check for the deleted CLI composition facade;
- separate permanent `closureItem` identity from nullable READY-only `nextCoreItem` projection;
- preserve package `0.9.5`, public standard `0.8.0` and artifact contract `2.0`.

Validation history:

- Flow CI #2185 established the first implementation boundary for complete-suite presence and fail-closed correction statuses.
- Flow CI #2204 validated the explicit CORRECTION_REQUIRED lifecycle on exact and merge-candidate revisions.
- Flow CI #2218 passed the reconciled StandardModel, structural purpose policy, artifact visibility and complete standalone conformance on exact head `c10bd83aa4c0bf48dcc4b0faef90c035525842c7` and synthetic merge candidate `651cca496a6b8a7e54cde2b992751c2630dbc30a`.
- Flow CI #2231 passed the complete corrected implementation, lifecycle projection and standalone conformance on exact head `ca5f0d921975bce724ff99a01f9e2b8d9793a0c0` and synthetic merge candidate `7bbabd0ccfdedd3862fa0b140e0c4c4fdc30f87b`.
- Bounded correction `0.9.7.10.1` is complete and `0.9.7.10` is now READY/next.
- The READY metadata head and later CLOSED metadata head each require independent exact-head and synthetic merge-candidate validation.
