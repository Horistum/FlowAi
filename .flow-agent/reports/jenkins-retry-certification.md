# AR-06P candidate: bounded native Jenkins retry

The Jenkins adapter now renders an authorized retry as native `retry(max)`. A
plan-specific capability resolver admits only positive total attempt limits with
fixed zero delay and a composed native retry provider. The generic target registry
remains unchanged. Delay, variable backoff, failure filters, interruption and
transient recovery are outside this slice. The renderer rejects incomplete or
unsupported policy fields rather than discarding them. Core and public syntax
are unchanged. Native behavior follows the [Jenkins retry contract](https://www.jenkins.io/doc/pipeline/steps/workflow-basic-steps/#retry-retry-the-body-up-to-n-times).

Two complete compiler-generated pipelines execute on a disposable offline Jenkins
controller. An always-failing missing-revision checkout exhausts three attempts,
propagates the final native failure and skips its successor. A successful checkout
executes once, followed by the successor checkout. Mutants flatten the retry,
raise the attempt limit, omit the body or repeat an already successful body.
Independent oracles check result, marker, native checkout count, every checkout
error and position, and terminal error origin. Signing happens outside the
controller, and authenticated replay must reject every semantic mutant.

The portfolio retains all earlier scenarios and the shared canonical checkout
comparison. It now requires fourteen assessments and forty-four signed runs,
covering seven bounded construct/target pairs across thirty-six matrix rows.
It does not promote public support or general portability.

## Predecessor boundary

PR #212 merged as fda0e823af8e71cf94e416029c91bb6a865735d7 with tree
a3b88668d6e7db73d8c2a9ffec4803bb95e9f153, identical to validated PR head and
synthetic merge candidate. Flow CI 38061220444 passed 2,037 tests in 350 suites
and 274 ordered conformance checks per revision. Runtime CI 38061220446 supplied
twelve assessments and 38 independently verified signatures. Main runtime CI
38068371090 passed; main Flow CI 38068371091 was still running at selection.

Immutable transition receipt: `.flow-agent/evidence/jenkins-retry-baseline.json`,
SHA-256 `f1044b17c0196172a6f437c89ef521b9e79f94ccedd8cfaed282c2a0f9d6a2cb`. Earlier receipts and formal acceptance remain unchanged.

## Validation boundary

Projection regressions cover authorization scope, absent providers, unsupported
policies and malformed renderer inputs. Observation regressions cover false
attempt counts, lost or substituted failures, missing/duplicate runs and altered
artifact bytes. Lifecycle tests preserve historical acceptance and finding
ownership. Current Kotlin, isolation, conformance and real runtime results must
come from this candidate's GitHub checks and are reported in the PR after review.

AR-06 remains active; only A/B are formally accepted. AR-07 stays planned, EF-09
paused and F-20 AR-07-owned. Package and public standard versions are unchanged.

The scoped attempt-isolation evidence applies only to a retry body with one native
`git.checkout` task. Every attempt creates a distinct native execution node; it
does not create or clear a workspace. The authored generic attempt-isolation claim
stays unknown. Nested retry, other body shapes, and absent or ambiguous topology
declarations remain blocked. Other topology requirements are preserved. The
first CI run exposed the missing attempt-isolation evidence and was rejected by
the existing topology gate; this scoped resolver supplies that bounded evidence.

The adapter structural catalog distinguishes `PLAN_SCOPED` implementation evidence
from `TARGET_WIDE` support. Existing definitions default to target-wide; bounded
Jenkins retry is explicitly plan-scoped. The maturity publisher therefore keeps
its generic registry claim unsupported and rejects any attempted broad promotion
backed only by scoped evidence. Target-wide definitions still require matching
registry declarations. Installed-product and negative maturity regressions enforce
both directions without introducing a target-specific exception in the publisher.
