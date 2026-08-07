# C0.4 Adapter Profile Evidence

## Purpose

C0.4 evaluates the current built-in implementation against adapter-owned profile evidence that was already established through A0.1-A0.6. It does not author a second support matrix and it does not reinterpret target syntax as semantic truth.

The key honesty rule is asymmetric: an implementation may legitimately be unsupported or unknown. C0.4 passes when those facts are represented exactly. It fails when a negative claim disappears, is promoted without adapter evidence, contradicts the frozen profile, or no longer matches the implementation that the adapter authorities observe.

## Frozen source boundary

`conformance/profiles/adapter-profile-sources.yaml` pins exactly six adapter-owned documents by path, document version and SHA-256:

1. A0.1 portfolio evidence;
2. A0.2 topology evidence;
3. A0.3 capability binding evidence;
4. A0.4 control materialization evidence;
5. A0.5 continuity evidence;
6. A0.6 artifact rendering evidence.

The digest is a freeze marker, not a duplicated support declaration. A legitimate future adapter-profile change must therefore be reviewed as a new evidence boundary instead of silently changing the meaning of an existing C0.4 report.

A0.7 trigger evidence is intentionally outside this boundary because C0.4 depends on A0.6. A1.0 is also outside the generic profile: its GitHub Actions executable continuity promotion is bounded to one reviewed scenario and cannot upgrade the generic A0.5 target profile.

## Independent implementation reassessment

`AdapterProfileEvidenceAuthority` loads the six frozen documents and invokes the existing adapter authorities again against the current repository implementation:

- portfolio evidence is reconciled with the target registry and composed providers;
- topology evidence is reconciled with complete topology claims and provider composition;
- capability binding evidence is reconciled with current module implementation claims;
- control and continuity evidence is revalidated against their closed semantic partitions and production evidence requirements;
- rendering evidence is reconciled with the current provider composition and executable/review identities.

C0.4 therefore consumes adapter evidence without replacing adapter ownership. A profile document that still hashes correctly but no longer describes the current implementation causes implementation reassessment to fail.

## Normalized report

The C0.4 report exposes four target dimensions for every portfolio target:

- topology;
- control;
- continuity;
- executable artifact rendering.

Capability binding semantic parameters are reported separately because they belong to module implementation bindings rather than one target.

Each claim has one closed status: `SUPPORTED`, `PARTIAL`, `UNSUPPORTED` or `UNKNOWN`. Rendering `REVIEW_ONLY` is reported as unsupported executable rendering, while its review artifact remains adapter evidence rather than executable support.

The report contains explicit `unsupportedClaims` and `unknownClaims` indexes in addition to the full target and binding claim sets. `AdapterProfileReportIntegrityAuthority` requires both indexes to be exact projections of the underlying claims. Removing even one negative claim is therefore a conformance failure rather than a cosmetically cleaner report.

## Bounded promotion separation

The frozen generic GitHub Actions continuity profile declares `artifact.shared-workspace` unsupported because independent jobs do not share a workspace without an explicit transfer mechanism. A1.0 later proves a scoped upload/download bridge for the bounded `checkout-build-image` scenario. C0.4 keeps both statements true:

- generic A0.5 profile: unsupported;
- bounded A1.0 scenario: executable under its separate promotion authority.

A successful bounded scenario is evidence about that scenario, not a wildcard capability grant.

## Evidence isolation

C0.4 owns its own ordered inventory under `conformance/profiles/c0.4-check-inventory.yaml` and runs after C0.3. It rejects C0.4 check identifiers from the frozen inventories owned by Core, A0, A1.0, C0.1, C0.2 and C0.3.

No earlier inventory is modified to make C0.4 appear complete.

## Lifecycle

C0.4 starts in `IMPLEMENTING` after the exact C0.3 completion boundary is recorded. One later exact-head plus synthetic merge-candidate Flow CI boundary may move the work package to `VALIDATING`. Completion requires a second distinct passing boundary.

This implementation PR deliberately leaves C0.4 in `IMPLEMENTING`. A local green build is necessary evidence for publication, but it is not allowed to impersonate GitHub Flow CI evidence that does not exist yet.

## Explicit exclusions

C0.4 does not:

- change Core semantic meaning;
- mutate any A0 or A1 support claim;
- infer support from a renderer, target registry flag or provider name;
- include A0.7 trigger evidence in the A0.6 profile boundary;
- promote generic GitHub Actions continuity from the bounded A1.0 proof;
- hide unsupported or unknown capabilities to improve a headline pass rate;
- perform the separate AR0.1 authority-responsibility refactor.
