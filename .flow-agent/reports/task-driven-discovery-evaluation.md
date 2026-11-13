# AI-A3-S3.3 — Task-driven discovery evaluation

Baseline: merged PR #270 at `611e688bcd9ac6e63d6a52de33d33bdae8e46dbf`.
Work package: [`task-driven-discovery-evaluation.yaml`](../work-packages/task-driven-discovery-evaluation.yaml).

## Problem and result

Discovery v3 could propose an interpretation and complete plan without caller-written
bindings, but the existing evaluation command explicitly rejected investigation.
It also had no route for independent candidate review. Repeated discovery trials
therefore could not be collected and assessed through the product's actual boundary.

The new `evaluate-discovery`, `review-discovery-evaluation` and
`summarize-discovery-evaluation` commands capture caller-selected task suites,
freeze complete exposure directories separately from independent expectations,
reserve all scheduled costs, and route exact candidates through the existing review
and compiler path. Compiler acceptance and caller-assessed task success remain
separate. Incomplete, failed and unstarted runs remain in the denominator. No model
calls occur during review; uncertain captures and reviews cannot be replayed.

The public development portfolio contains six tasks across infrastructure, data
and credential management, with change and inspection goals over the same sources.
The installed integration proof supplies nine clearly labeled manual transcripts:
six matching candidates and three old mutation plans submitted for inspection-only
goals. The latter must fail independent compiler requirements even after explicit
test approval. Test responses never enter the production suite or its configuration.

## Validation

Validation includes evaluator isolation, fixed-setting capture, budget reservation,
malformed provider output, interruption and replay, source membership changes,
packet/state substitution, partial review, appropriate clarification and independent
quality failures despite compiler acceptance. Public requirements were checked
against the existing v5 JSON schema. The installed proof is part of both full CI
validation jobs, preserving all prior authoring and runtime evidence proofs.

Local and exact-head/merge-candidate CI results are recorded in the pull request.
The implementation remains a candidate until final CI completes; no full-validation
claim is inferred from protocol-only tests.

Local validation: 506 Python tests ran; 494 passed, including all 20 new evaluator
tests. The twelve failures are the existing deployed-observer service tests whose
Unix-socket service cannot start in this workspace; none were disabled or changed.
Architecture metadata validation and the six public requirements-v5 schema checks
passed. Installed compiler and full JVM validation require GitHub CI in this environment.

## Limits and next work

No live provider credentials were configured during development and no live model
trial was performed. The provider-neutral command boundary reuses existing optional
bridges from `gitops-agent-control-plane`; it does not silently select an account,
reuse a subscription session, add a provider SDK or fabricate live evidence.

The next step is independently reviewed live trials with at least three repetitions,
private unfamiliar environments, blocked/conflicting cases and remediation driven
by actual discovery failures. Public fixtures are not held-out data. Provider and
reviewer attribution remain declared, not authenticated. Local exposure copies do
not establish remote inventory freshness or collector attestation. Natural-language
requirement completeness and executable held-out rendering remain open gates.

No new semantic graph, scenario adapter, execution engine, automatic judge or core
contract version is introduced. S3, AI-A3, AR-06 and AR-07 remain open; EF-09 remains
paused. Planning and compiler evidence do not demonstrate an infrastructure change,
successful runtime recovery or production readiness.
