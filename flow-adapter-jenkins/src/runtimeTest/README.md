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
