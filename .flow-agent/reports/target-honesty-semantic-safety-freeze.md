# AR-00 Target Honesty and Semantic Safety Freeze

## Purpose

AR-00 contains target and executable-readiness claims that were broader than the evidence implemented by the composed adapters. PR #159 completed the fail-closed capability and structural-projection containment. This closure slice adds a current, separately versioned maturity publication without rewriting the immutable C0.4 adapter portfolio.

## Maturity model

Maturity is cumulative:

1. `DECLARED` — a current target registry entry exists.
2. `ANALYZABLE` — the target has a complete analysis/topology contract.
3. `RENDERABLE` — the current distribution composes a projection provider.
4. `EXECUTABLE` — an exact bounded scenario has executable snapshot evidence.
5. `BEHAVIORALLY_CERTIFIED` — the same bounded scope has implementation evidence, behavioral evidence and explicit limitations.

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
- The `targets` CLI command publishes both the target capability matrix and current maturity report from the production composition root.

## Safety properties

- A provider is required before a target can become target-wide `RENDERABLE`.
- A target-scoped executable snapshot is required before a bounded scope can become `EXECUTABLE`.
- Production and behavioral source references are required before that bounded scope can become `BEHAVIORALLY_CERTIFIED`.
- Profile-only targets cannot borrow executable evidence from another target.
- Every target-adapter structural claim marked `SUPPORTED` must have a matching provider-owned structural projection definition.
- A bounded executable scope cannot promote target-wide executable maturity.
- Missing, forged or disconnected structural evidence prevents executable rendering.

## Validation boundaries

| Boundary | Exact head | Synthetic merge candidate | Flow CI | Result |
|---|---|---|---:|---|
| Activation | `e8f636788c773c639357a2fc7aaabf560442fef4` | `d469c0e9111de76e9ccb990ffe5cbad4fe989e57` | #3060 | PASS |
| Clean implementation | `5ec3eeeb03d8171456c58d5af4d76259cbdc2e55` | `41013377e1d5008e78b3a543d9757e1bddd265a0` | #3086 | PASS |
| Validation | `69f290ff924e37a80d0e8589eb057835ed1f4dd3` | `75e80199436fc0b5176cf09890e49ab659f55a4b` | #3087 | PASS |
| Completion | `9e5959f1fd0abd4e4d134aa50b99810b0e478f35` | `af744d593dc965974a0cdf7392d34dbac2806362` | #3088 | PASS |

The failed pre-cleanup candidate Flow CI #3084 is not accepted as evidence. It failed compilation because of an invalid import and also contained one-off workflows that rewrote production files. The branch was reset to a clean main-based implementation before the accepted implementation boundary.

## Completion decision

AR-00 is complete as containment. It established fail-closed capability defaults, provider-owned structural evidence, current target maturity and negative regression coverage without implementing missing target behavior or redefining target-neutral meaning.

AR-01 is now the next Architecture Recovery item, but it remains unactivated and has no work package in this change.

## Remaining ownership

AR-00 contains F-01, F-06, F-11 and F-18 but does not close them. Full behaviorally falsifiable adapter certification, including canonical-construct × target × negative-mutant coverage, remains owned by AR-06.
