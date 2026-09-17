# External review corrections before AR-04

Baseline: `dc3cfab1ec2e16ff315f6af0b8f3a7c1ef62707c`.
Review: PR #182 at `18d01dae490d47b6fbedd9714ede0759ddc393d0`.
Implementation: PR #183, `fix/external-review-pre-ar04`.
Authorization: maintainer instruction on 2026-09-16 to fix confirmed defects before continuing AR-04; bounded work packages were committed before implementation.

## Acceptance status

Implementation and regression-test candidates exist for EXT-01 through EXT-08. They are NOT accepted closure evidence until full exact-head and synthetic-merge-candidate Flow CI pass. Existing recovery roadmap states, historical audit evidence and target support levels are unchanged.

The first Jenkins-only candidate `7579c6f3e4566fe171dee18ee0e243984009dd15` failed Flow CI run `35121254732` while compiling its new parser regression: the test omitted the parser scope argument. That test now supplies `scope = "auto"` explicitly. A failed CI run is not recorded as proof that runtime tests passed.

The initial complete Flow CI candidate `97d33a948cdf10ba316462675f9a05dc7f3da3f6` failed run `35125548570`: 1,560 JVM tests were reported with 71 failures. Source-isolation checks passed, but that does not turn a failed test boundary into accepted evidence.

For the follow-up, exact source and isolated build inputs were retrieved through temporary validation run `35167184071`, pinned to that candidate. The local source tree matched `7afd30f6f4ad934511c99575d7c5dbe25ed4d2ce` before editing. Local Gradle validation now runs offline with JDK 25 and Gradle 9.5.0; it is no longer described as unavailable. Final exact-head and synthetic-merge CI are still required. No complete external Jenkins runtime certification is claimed by the Groovy expression tests.

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


## Follow-up integration corrections

- The authored reference YAML had an unconditional intent-wide approval policy but put approval after checkout/test/build. It now explicitly places approval before every operation. Production-generated snapshots were regenerated with `reference-snapshot`, not hand-edited. An independent negative mutation removes the checkout dependency and must be rejected.
- The authority-responsibility catalog records the actual new canonical-control consumer, `IntentDecisionAnalyzer`.
- EF-09 retains its historical `MODEL_GAP` observation. A separate reviewed correction records `REPRESENTABLE` for whole-changeset approval coverage. The existing verifier checks the correction against live positive/negative evaluation and rejects regression, missing provenance and a declaration that claims unsupported behavior. Six other model gaps remain; EF-09 is not declared complete.
- The obsolete slashy-regex source assertion now checks non-interpolating regex encoding. Actual Groovy evaluation additionally verifies built-in URL pattern semantics.
- The frontend rejection regression now verifies that its fixture removes exactly one approval declaration; it cannot silently pass an unchanged reference to the compiler after a sample edit.
- The correction report uses a responsibility-based filename to satisfy the existing naming rule. No test, source-isolation gate or workflow requirement is disabled.

## Local validation

- Flow Agent structure validation: PASS.
- Flow Agent tooling: 151 tests passed.
- Targeted JVM integration regressions: 50 tests passed, 0 failures, 0 skipped.
- Standalone installed verification CLI: 246 conformance checks passed, 0 failed.
- The first complete local integration run reached 1,496 conformance-kit tests with one failure: the frontend negative fixture still searched for the old approval declaration and therefore submitted unchanged, valid input. The fixture mutation and typed rejection assertion are corrected in this follow-up.
- Final full-suite and exact-head/merge-candidate CI results must be read from PR #183 for the final commit. No passing final boundary is inferred from targeted checks or recorded before that execution.
