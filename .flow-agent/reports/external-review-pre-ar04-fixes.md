# External review corrections before AR-04

Baseline: `dc3cfab1ec2e16ff315f6af0b8f3a7c1ef62707c`.
Review: PR #182 at `18d01dae490d47b6fbedd9714ede0759ddc393d0`.
Implementation: PR #183, `fix/external-review-pre-ar04`.
Authorization: maintainer instruction on 2026-09-16 to fix confirmed defects before continuing AR-04; bounded work packages were committed before implementation.

## Acceptance status

Implementation and regression-test candidates exist for EXT-01 through EXT-08. They are NOT accepted closure evidence until full exact-head and synthetic-merge-candidate Flow CI pass. Existing recovery roadmap states, historical audit evidence and target support levels are unchanged.

The first Jenkins-only candidate `7579c6f3e4566fe171dee18ee0e243984009dd15` failed Flow CI run `35121254732` while compiling its new parser regression: the test omitted the parser scope argument. That test now supplies `scope = "auto"` explicitly. A failed CI run is not recorded as proof that runtime tests passed.

No full local Gradle execution is claimed: the editing environment does not have the repository's required toolchain/dependency access. Repository CI is the complete validation boundary. No complete external Jenkins runtime certification is claimed by the Groovy expression tests.

## Implemented boundaries and regressions

| Finding | Production correction | Regression evidence to execute |
| --- | --- | --- |
| EXT-01 | Regex operands are non-interpolating Groovy strings; regex meaning and empty patterns are retained. | JenkinsLiteralBoundaryTests: real Groovy evaluation, Flow parsing, regex semantics and an effective harmless negative control for the former interpolation. |
| EXT-02 | One Groovy literal encoder handles strings, credentials and parameter names; exact input property names are quoted rather than sanitized into different identities. | JenkinsLiteralBoundaryTests: quotes, backslashes, control characters, dollar markers, exact properties and credential argument round-trips. |
| EXT-03 | Core validates unary/postfix operators and operator arity categories; Jenkins and GitHub Actions reject unknown operator fallbacks. | ExpressionOperatorBoundaryTests; JenkinsLiteralBoundaryTests; GitHubActionsOperatorBoundaryTests. |
| EXT-04 | Closed requiresApproval tokens and unconditional requirements require coverage of all operations in their workflow. Separate valid approvals can cover different operations. Expression-based policies remain DYNAMIC/PENDING, not executable authorization. | IntentWideApprovalCoverageTests: partial coverage, spelling variants, distributed coverage, workflow isolation and pending conditional evidence. |
| EXT-05 | Decision reports consume EnvironmentSafetyPolicy; Kubernetes maintenance no longer guesses production with contains("prod"). Unknown or conflicting explicit environment evidence requires clarification. | IntentDecisionEvidenceBoundaryTests; MaintenanceEnvironmentBoundaryTests. |
| EXT-06 | Decision gates project every canonical control requirement, including the three formerly omitted safety tokens. Scoped backup and cleanup evidence are reused rather than re-inferred globally. | IntentDecisionEvidenceBoundaryTests: all closed safety tokens, confirmed ticket, pending schema, unrelated backup and partial approval. |
| EXT-07 | The domain-separated semantic digest v2 includes dependency evidence classification and ordered dependency paths. Diagnostic evidenceReference pointers remain non-semantic. | CanonicalDependencyDigestBoundaryTests: mutation sensitivity, authorization mismatch, path order and storage/provenance invariance. |
| EXT-08 | The public catalog admits the actually emitted SYMBOL diagnostic, retaining legacy TERM compatibility. The negative corpus expects the real code. | ArchitectureDiagnosticBoundaryTests drives the actual scanner, catalog and corpus, with a harmless string-literal negative case. |

## Contract boundaries

See `docs/security/EXTERNAL_REVIEW_CORRECTIONS.md`. Decision reports now declare model version 1.1 and expose a pending state. Canonical digests/receipts must be recomputed. The decision-report implementation is frontend-owned because its default policy composition belongs to frontends; Core must not acquire a reverse dependency on that composition.

The larger AR-04 identity/type migration, callable-function contract, other adapters' identifier encoding, market roadmap changes and governance retirement are not declared finished by these corrections. No new runtime executor, target feature certification or historical lifecycle authority is introduced.
