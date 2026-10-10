# Native checkout runtime certification

This adapter-owned fixture exercises a real Jenkins controller, Declarative
Pipeline and Git plugin. It makes one **native-leaf-only** claim: selecting the
requested Git branch produces its expected workspace bytes. The matrix defines
the independent expected observations and two deliberate artifact mutants.

Run from a checkout with JDK 25 and Docker:

```sh
./gradlew --no-daemon :flow-conformance-kit:verifyJenkinsCheckoutRuntime
```

The output directory `flow-conformance-kit/build/jenkins-runtime-certification`
must not already exist. Move prior evidence aside before running again. Ordinary
unit tests and product installations never start this external runtime. The
dedicated `Adapter Runtime Certification` workflow runs it for PR heads and main.

The runner compiles `checkout.intent.yaml`, captures compiler-authorized graph,
source and executable rendering through AR-06B, and writes that exact Jenkinsfile
without instrumentation. Mutants either replace the checkout with a harmless
echo or select another existing branch. Fresh jobs get distinct workspaces.
All three builds must finish successfully; the omitted operation leaves no marker
and the wrong branch produces the alternate marker. Timeouts, unavailable Git,
plugin failures and unsuccessful builds cannot kill a mutant.

Jenkins runs with `--network none`, no published ports and no production
credentials. A read-only disposable repository is served by a loopback Git
daemon inside the container. The controller records the configured script digest,
actual build result, workspace marker and plugin versions. The host verifies the
record, signs every observation with an assessment key generated before launch,
and invokes the existing authenticated compiler-bound admission. The private key
never enters the container or uploaded evidence.

The top-level Jenkins/plugin versions are pinned in `Dockerfile`; dependency
resolution uses `--latest=false`. The built image's immutable Docker ID is used
to launch the controller and is included in every signed runtime prerequisite.
The evidence also retains the complete active-plugin inventory, Java version,
raw results, build logs, original source/graph, artifacts, observation bytes,
public trust declaration and admission proof. Treat the ephemeral public key as
CI-assessment trust, not a production runner identity or external attestation.
The trusted test observer and Docker host remain part of the assessment boundary.

This proof covers neither GitHub Actions nor structural semantics, credentials,
shallow cloning, multi-agent continuity or general portability. It changes no
public support or maturity view. Source-shape/Groovy stub tests remain useful
unit tests, but cannot substitute for this runtime check.

Runtime setup follows the upstream [Jenkins Docker documentation](https://www.jenkins.io/doc/book/installing/docker/).
Pinned dependencies: [Jenkins 2.580.1](https://www.jenkins.io/changelog-stable/2.580.1/),
[Declarative Pipeline](https://plugins.jenkins.io/pipeline-model-definition/),
[Git](https://plugins.jenkins.io/git/) and [Timestamper](https://plugins.jenkins.io/timestamper/).

## Native failure propagation

AR-06E adds a separate bounded scenario, `failure.intent.yaml`, with two checkout
operations and an explicit dependency. The first requests a deliberately absent
branch. The canonical workflow has `PROPAGATE` failure disposition. The unchanged
generated Jenkinsfile must finish with `FAILURE` before the dependent checkout
starts. Fresh workspace bytes, native step count and actual step error identity
are observed from the controller, outside the pipeline.

```sh
./gradlew --no-daemon :flow-conformance-kit:verifyJenkinsFailureRuntime
```

Use a fresh `flow-conformance-kit/build/jenkins-failure-certification` directory.
The dedicated `jenkins-failure-runtime` job uploads its evidence separately from
the existing checkout scenario.

| Run | Build result | Workspace marker | Executed checkout steps | Missing-revision errors |
| --- | --- | --- | --- | --- |
| Original artifact | FAILURE | Absent | 1 | 1 |
| Omit the failing checkout | SUCCESS | `selected` | 1 | 0 |
| Suppress its error | SUCCESS | `selected` | 2 | 1 |

The observer uses the actual Jenkins execution graph and each native step's
`ErrorAction`; a caught error remains visible. The expected exception is exactly
`hudson.AbortException` with the pinned Git plugin's missing-revision diagnostic.
Repository readiness independently confirms the selected and alternate branches
exist and the missing branch does not. `COMPLETED` means a fully observed execution
of the scenario, which can deliberately end in `FAILURE`. Only this scenario's
specific native error qualifies; other errors, aborts, missing results and
timeouts fail the proof. All original checkout-scenario builds still require
`SUCCESS`. Expected observations remain in the independent `failure-matrix.json`.

The `catchError` wrapper exists only in the negative mutant. This scenario does
not certify general error-boundary or retry support, structural semantics,
cross-adapter portability or production runner trust. It promotes no public
support claim. The host key, exact input/artifact binding, runtime prerequisites
and authenticated admission are shared with AR-06D.

Observer API references: [FlowGraphWalker](https://javadoc.jenkins.io/plugin/workflow-api/org/jenkinsci/plugins/workflow/graph/FlowGraphWalker.html),
[StepAtomNode](https://javadoc.jenkins.io/plugin/workflow-cps/org/jenkinsci/plugins/workflow/cps/nodes/StepAtomNode.html)
and [ErrorAction](https://javadoc.jenkins.io/plugin/workflow-api/org/jenkinsci/plugins/workflow/actions/ErrorAction.html).


## Generated observation views

AR-06F adds `evidence-view.json` and `evidence-view.md` to each runtime evidence
directory. Both are derived from the same immutable inputs that pass authenticated
admission. The proof binds their hashes and the Actions summary displays the
Markdown matrix. The matrix retains every uncovered construct and shows exact
scenario/run identities, runtime prerequisites and limitations. A failed assessment
publishes no successful view. These are bounded observation diagnostics, not target
support declarations; `NOT_OBSERVED` does not mean `UNSUPPORTED`.
## Conditional execution (AR-06G)

`condition-true.flow` and `condition-false.flow` exercise complementary boolean
equality guards using the existing compiler and Jenkins renderer. Run
`./gradlew :flow-conformance-kit:verifyJenkinsConditionRuntime` with the same JDK
and Docker prerequisites as the earlier scenarios below.

The `condition-matrix.json` oracle requires exactly one checkout for each original
artifact. Removing both guards executes two checkouts; inverting both guards
selects the other branch. For the false default, flattening leaves the same final
marker, so the independently observed checkout count is essential. All six runs
must finish successfully with no checkout errors.

Evidence is written to `flow-conformance-kit/build/jenkins-condition-certification/true/`
and `false/`. Each subdirectory must be fresh before rerunning. Source bytes are
archived as `artifacts/source.flow`; each subdirectory otherwise follows the same
trust, signature, artifact and view format as the earlier proofs. The controller
observes Jenkins's executed flow graph without instrumenting the positive script.
The signing key stays outside the runtime container.

This is bounded structural-occurrence evidence, not a support/maturity promotion.
External parameter overrides, other expression forms, error boundaries and a
second adapter are outside these condition scenarios. Workflow handlers are
covered separately below.

## Workflow error boundaries

AR-06H adds `error-failure.flow` and `error-success.flow`. Both use the existing
workflow-level `on error` contract with `PROPAGATE` disposition. The failing body
requests a missing revision before a later checkout; its handler checks out the
alternate branch. The successful body checks out the selected branch and must
skip the handler. The original compiler/rendering output executes unchanged.

Run `:flow-conformance-kit:verifyJenkinsErrorBoundaryRuntime` with a fresh
`flow-conformance-kit/build/jenkins-error-boundary-certification` directory.
The `jenkins-error-boundary-runtime` job publishes separate `failure/` and
`success/` proofs, authenticated views and runtime records.

| Body | Run | Result | Marker | Checkouts | Native errors | Terminal origin |
| --- | --- | --- | --- | --- | --- | --- |
| Failure | Original | FAILURE | alternate | 2 | 1 | First checkout |
| Failure | Omit handler | FAILURE | absent | 1 | 1 | First checkout |
| Failure | Suppress propagation | SUCCESS | alternate | 2 | 1 | None |
| Success | Original | SUCCESS | selected | 1 | 0 | None |
| Success | Run handler unconditionally | SUCCESS | alternate | 2 | 0 | None |
| Success | Omit body | SUCCESS | absent | 0 | 0 | None |

The failure result alone cannot detect an omitted handler. The marker and native
step count alone cannot detect a swallowed error. The independent matrix checks
all these observations together. `FlowExecution.getCauseOfFailure()` and
`ErrorAction.findOrigin()` bind the terminal error to its originating checkout;
the signed observation retains its exact type, message and one-based checkout
index. An unrelated terminal error cannot satisfy intentional native failure.
Missing, malformed or unfinished records fail admission.

Certification derives the error-boundary occurrence from the compiler's typed
workflow failure policy. It does not invent a local try node or infer universal
meaning from target syntax. The JSON/Markdown view remains bounded: local
recovery, retries, cancellation, arbitrary exception semantics and cross-adapter
equivalence are not certified, and public support is not promoted.

## Local recovery and continuation (AR-06I)

`recovery-failure.flow` and `recovery-success.flow` exercise local `try` / `on error`
through the production compiler and unchanged renderer. The failing body contains
a later checkout that must remain unreachable. Both paths continue outside the
boundary; only the failing path executes its handler.

| Body | Run | Result | Final marker | Checkouts / errors |
| --- | --- | --- | --- | --- |
| Failure | Original | SUCCESS | selected | 3 / 1 |
| Failure | Omit handler | SUCCESS | selected | 2 / 1 |
| Failure | Rethrow caught error | FAILURE | alternate | 2 / 1 |
| Failure | Omit continuation | SUCCESS | alternate | 2 / 1 |
| Success | Original | SUCCESS | selected | 2 / 0 |
| Success | Unconditional handler | SUCCESS | selected | 3 / 0 |
| Success | Omit continuation | SUCCESS | selected | 1 / 0 |

Run `./gradlew :flow-conformance-kit:verifyJenkinsLocalRecoveryRuntime` with fresh
`flow-conformance-kit/build/jenkins-local-recovery-certification/{failure,success}`
outputs. The `jenkins-local-recovery-runtime` CI job retains both proofs and views.
The independent observer records the ordered one-based checkout positions carrying
native errors, as well as terminal error identity/origin. The expected error is
always the first checkout's missing revision; only the rethrow mutant terminates
with that error. Final result and workspace bytes alone cannot distinguish every
mutant, so checkout counts are part of the authenticated observation.

The evidence covers these local boundary occurrences only. It does not certify
nested handlers, retries, cancellation, error-value binding, general workspace or
state continuity, or portable execution. Previous scenario observation formats
and public support claims remain unchanged.
## Manual approval (AR-06J)

`approval.flow` is compiled once per decision through the production pipeline.
Both scenarios execute identical, uninstrumented compiler output. The external
controller observes a pending native `input` and workspace before submitting a
test decision through `InputStepExecution.doProceedEmpty()` or `doAbort()`.
No human account, permission policy or UI interaction is being certified.

| Decision / artifact | Pending checkout count | Final result | Final checkout count |
| --- | ---: | --- | ---: |
| Approve / original | 0 | SUCCESS | 1 |
| Approve / omitted approval | no input | SUCCESS | 1 |
| Approve / late approval | 1 | SUCCESS | 1 |
| Reject / original | 0 | ABORTED | 0 |
| Reject / omitted approval | no input | SUCCESS | 1 |
| Reject / late approval | 1 | ABORTED | 1 |
| Reject / suppressed rejection | 0 | SUCCESS | 1 |

`approval-matrix.json` records exact expected observations independently from the
runtime observer. Raw records include the pending message, native `PauseAction` and execution state,
decision, native input/checkout counts, error causes and terminal input origin.
Unrelated aborts, malformed observations and surviving mutants fail admission.

Run `./gradlew :flow-conformance-kit:verifyJenkinsApprovalRuntime` with JDK 25 and
Docker. Evidence is written under
`flow-conformance-kit/build/jenkins-approval-certification/{approve,reject}`;
each output directory must be fresh. The dedicated CI job archives raw logs,
source, graph, scripts, signed observations and derived bounded evidence views.

This covers one manual gate before one native checkout. Human authorization,
timeouts, restarts, concurrent/nested approvals, general protected-operation
binding and portable execution remain outside the claim.

### Construct behavior matrices (AR-06K)

All ten scenarios now archive `behavior-matrix.json` and `behavior-matrix.md`;
`proof.json` binds both by SHA-256. Actions summaries display the matrix with the
baseline and every mutant's full normalized observation. The existing evidence
views remain archived.

Each matrix lists the eleven AR-06 construct categories plus native checkout,
explicitly separating the one bounded scenario from categories without evidence
in that assessment. The JSON retains exact expected/observed UTF-8 bytes as
strings, compiler-bound source/graph references, runtime prerequisites, signed run
references and limitations. These are individual assessments, not a merged target
portfolio. Synthetic unit-test signatures validate the protocol only; real Jenkins
runs provide runtime evidence. No public support or portable execution is claimed.
