# AR-00 Target Honesty and Semantic Safety Freeze

## Purpose

AR-00 contains target and executable-readiness claims that were broader than the evidence implemented by the composed adapters. PR #159 completed the fail-closed capability and structural-projection containment. This closure slice adds a current, separately versioned maturity publication without rewriting the immutable C0.4 adapter portfolio.

## Maturity model

Maturity is cumulative:

1. `DECLARED` — a current target registry entry exists.
2. `ANALYZABLE` — the target has a complete analysis/topology contract.
3. `RENDERABLE` — the current distribution composes a projection provider.
4. `EXECUTABLE` — an exact bounded scenario has executable snapshot evidence.
5. `BEHAVIORALLY_CERTIFIED` — the same scope has implementation evidence, behavioral evidence and explicit limitations.

Target-wide maturity and scoped maturity are intentionally separate. No current target receives target-wide `EXECUTABLE` or `BEHAVIORALLY_CERTIFIED` status.

## Current publication

- `local`: target-wide through `ANALYZABLE`; no projection provider and no executable scope.
- `jenkins`: target-wide through `RENDERABLE`; `checkout-build-image` is scoped through `BEHAVIORALLY_CERTIFIED`.
- `github-actions`: target-wide through `RENDERABLE`; `checkout-build-image` is scoped through `BEHAVIORALLY_CERTIFIED` using the existing post-A0 promotion and artifact-backed workspace transfer.
- `tekton`: target-wide through `RENDERABLE`; no certified executable scope.
- `argo-workflows`: target-wide through `ANALYZABLE`; profile-only.
- `azure-devops`: target-wide through `ANALYZABLE`; profile-only.

The Jenkins and GitHub Actions scopes preserve their limitations. Neither scope promotes parallelism, loops, match, retry, generic workspace continuity or generic `standard.execute` behavior.

## Evidence boundaries

- `adapters/portfolio/builtin-adapter-portfolio.yaml` remains immutable C0.4 history.
- `adapters/portfolio/executable-reference-promotions.yaml` remains the post-A0 GitHub Actions promotion authority.
- `adapters/portfolio/target-maturity-evidence.yaml` is the new current-distribution evidence index.
- `AdapterTargetMaturityPublisher` derives cumulative stages and rejects unsupported scope borrowing, missing files, source mismatches and unowned supported structures.
- Architecture Recovery conformance has a separate inventory under `architecture-recovery/conformance`; it does not modify frozen Core or adapter inventories.

## Safety properties

- A provider is required before a target can become `RENDERABLE`.
- A target-scoped snapshot is required before a scope can become `EXECUTABLE`.
- Production and behavioral source references are required before a scope can become `BEHAVIORALLY_CERTIFIED`.
- Profile-only targets cannot borrow executable evidence from another target.
- Every target-adapter structural claim marked `SUPPORTED` must have a matching provider-owned structural projection definition.
- A bounded executable scope cannot promote target-wide executable maturity.

## Remaining ownership

AR-00 contains F-01, F-06, F-11 and F-18 but does not close them. Full behaviorally falsifiable adapter certification, including canonical-construct × target × negative-mutant coverage, remains owned by AR-06.
