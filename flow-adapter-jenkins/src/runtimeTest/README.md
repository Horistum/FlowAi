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
