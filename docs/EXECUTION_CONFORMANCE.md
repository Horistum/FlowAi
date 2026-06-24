# Execution Conformance (real-platform last mile)

Flow proves behavioral fidelity in two layers:

1. **In-repo reference-semantics conformance** (`tests/FlowBehavioralConformanceTests.kt`).
   An independent reference interpreter derives *which tasks execute under a given guard
   assignment* from both the canonical `ExecutionPlan` and each generated manifest, and
   asserts they agree. This runs offline in CI and catches dropped/mistranslated guards
   and dropped tasks (a deliberately corrupted manifest is included as a negative case).

2. **Real-platform execution conformance** (this document).
   The reference interpreter models execution semantics; it does not replace running the
   generated artifacts on the real engines. This is the last mile: it confirms the
   rendered text is accepted by each platform and behaves as the plan says.

> Flow itself never executes workflows (see `docs/ARCHITECTURE_CONSTITUTION.md`). The
> steps below are an *external test harness* you run against real engines; they are not
> a Flow runtime, SDK, or adapter framework, and nothing here is shipped in the standard.

## Golden scenarios

Use the same plan as the reference test: `build -> test -> (deploy when env==prod, else notify)`.
Generate the three manifests for that intent, then run each scenario and check the
observable behavior:

| Scenario | env | Expected to run | Expected skipped |
|----------|-----|-----------------|------------------|
| prod     | `prod`    | build, test, deploy | notify |
| non-prod | `staging` | build, test, notify | deploy |
| failure  | `prod`, test fails | build, test | deploy, notify (and rollback/notify if modeled) |

The assertion is **observable**: inspect each engine's run log for which steps executed
and which were skipped, and compare to the table. Equivalence holds when every engine
that the readiness report marks `READY`/`DEGRADED` for the plan produces the expected
run/skip set. Where readiness is `BLOCKED` (a guard the target cannot express), do **not**
expect equivalence - the standard has already refused generation for that target.

## Jenkins (containerized)

```bash
docker run --rm -u root -p 8080:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home jenkins/jenkins:lts-jdk21
# Create a Pipeline job, paste the generated Jenkinsfile, build with parameter env=prod / env=staging.
# Check the stage view: the guarded stage runs only in the matching scenario.
```

## GitHub Actions (local, via act)

```bash
# https://github.com/nektos/act
act -W .github/workflows/<generated>.yml \
  --input env=prod        # then re-run with --input env=staging
# Confirm the conditional job runs only when the `if:` guard holds.
```

## Tekton (on kind / k3s)

```bash
kind create cluster
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
kubectl apply -f <generated>-tekton-pipeline.yaml
# Create a PipelineRun with param env=prod / env=staging; inspect TaskRun status.
# NOTE: a guard Flow could not map to a native `when` is reported as an error mapping
# note AND blocks readiness; such a pipeline must not be promoted to production for Tekton.
```

## Wiring into CI

Run layer 1 (`./gradlew test`) on every change. Run layer 2 on a schedule or release
gate, against the golden scenarios, and fail the gate if observed run/skip sets diverge
from the table for any target the readiness report did not mark `BLOCKED`.
