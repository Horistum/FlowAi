# Target Registry v3.2

Flow target capabilities and projection evidence are represented as versioned YAML under `targets/`.

This keeps target support data reviewable and extensible. Adding a target must not require adding another vendor field to a public Kotlin data class, and declaring a capability must not be mistaken for proof that an executable renderer payload exists.

## Registry document

```yaml
kind: FlowTargetRegistry
version: "3.2"

targets:
  - name: jenkins
    expressionProfile: equality-membership-condition
    capabilities:
      parallel: supported
      approvals: partial
    projectionRules:
      - module: git
        action: checkout
        mode: native
        reason: Jenkins has a concrete Git checkout step.
        evidenceReference: targets/builtin-targets.yaml#jenkins.git.checkout
        payload:
          kind: jenkins-step
          reference: git
          bindings:
            url:
              kind: TASK_PARAMETER
              name: url
            branch:
              kind: TASK_PARAMETER
              name: branch
              defaultValue: main
            depth:
              kind: TASK_PARAMETER
              name: depth
              defaultValue: "0"
```

## Execution topology profiles

Target Registry `3.1` introduced complete execution-topology profiles for target-owned inline evidence. The profile is separate from action capabilities and projection rules. It records evidence for isolation, lifetime, persistence and propagation dimensions:

- workflow, branch and retry-attempt isolation;
- workflow lifetime and suspend/resume support;
- ephemeral workspace and durable state persistence;
- value, workspace, state and failure propagation.

Each dimension declares `supported`, `partial`, `unsupported` or `unknown` with one explicit evidence reference. Missing, duplicate or contradictory declarations fail closed. `partial` topology remains non-executable because a degraded execution environment cannot prove safe end-to-end materialization.

A target may support every requested action and still be blocked when its topology cannot preserve the required workspace, state or control-flow lifetime. Renderer layout and action ordering are not topology evidence.

## Target Registry 3.2 acceptance alignment

Target Registry `3.2` makes the public interchange schema and the production loader agree on the authored wire vocabulary instead of letting each boundary invent a different contract.

- `expressionProfiles` now has a concrete schema shape and rejects duplicate authored feature identifiers.
- Target capability names use a closed vocabulary and support levels accept only the documented values `supported`, `full`, `partial`, `unsupported`, `none` and `requires_runtime`. Undocumented aliases such as `yes`, `true`, `limited` or `runtime` are rejected rather than normalized.
- Projection `mode` and payload `bindings` remain optional because the production model owns explicit defaults (`adapter_required` and an empty binding map).
- Inline `topology` remains optional at the document shape boundary because a distribution may supply adapter-owned topology evidence beside the registry. `loadDirectory` still fails closed when neither source provides topology evidence.
- Schema validation remains a syntactic interchange check. Production validity is still owned by `TargetRegistryYamlLoader` and the typed authorities it invokes.

This is a public contract migration from 3.1 to 3.2, documented in `SI_07_PUBLIC_SCHEMA_ACCEPTANCE_ALIGNMENT_MIGRATION.md`.

## Projection rules

A rule identifies one semantic action and states how that target can represent it:

- `NATIVE`: concrete target-native payload evidence exists.
- `NOTES_PROJECTED`: a declarative notes package owns the projection.
- `ADAPTER_REQUIRED`: no complete projection is available.
- `UNSUPPORTED`: the target cannot represent the action safely.
- `BLOCKED`: architecture or safety policy forbids projection.

`NATIVE` and `NOTES_PROJECTED` are not promises made by enum value alone. They require evidence. A `NATIVE` rule must also match an immutable provider-owned native projection definition for the same opaque payload kind, reference and typed binding schema. Missing rules fail closed as `ADAPTER_REQUIRED`; missing or inconsistent native implementation evidence rejects generation before executable readiness can be inferred.

## Renderer payloads

The `kind` field is an opaque projection-consumer identifier, not a Flow Core enum. The registry loader validates and canonicalizes the identifier. The selected target provider must then own a matching native projection definition, while only the concrete edge renderer interprets target syntax and behavior.

Consequences:

- Flow Core does not enumerate Jenkins, GitHub Actions, Tekton, or future target payload kinds.
- Adding a new target payload kind does not change Intent, AST, ExecutionPlan or materialization semantics.
- Provider composition validates registry rules against implemented opaque payload identities and typed binding schemas.
- Generic readiness validates payload presence, target binding, reference, evidence and typed bindings.
- A concrete renderer still fails closed when it receives syntax or behavior it does not own.

## Typed bindings

Target Registry 3.0 replaces prefix-encoded strings such as `param:url`, `input:name` and `literal:value` with discriminated binding objects.

Supported binding kinds are:

| Kind | Registry meaning | Manifest behavior |
|---|---|---|
| `LITERAL` | Stable literal value | Preserved with its literal provenance |
| `TASK_PARAMETER` | Read a named task parameter | Resolved during manifest generation |
| `TASK_INPUT` | Read a named task input | Resolved during manifest generation |
| `TASK_METADATA` | Read task `ID` or `TARGET` | Resolved during manifest generation |
| `FLOW_INPUT` | Reference a top-level Flow input | Remains symbolic for the edge renderer |
| `SECRET` | Reference an opaque secret | Remains symbolic and requires renderer evidence |
| `ARTIFACT` | Reference a named artifact | Remains symbolic and requires renderer evidence |
| `TASK_OUTPUT` | Reference a named output of another task | Remains symbolic for dependency-aware rendering |
| `TARGET_EXPRESSION` | Explicit target-owned expression | Allowed only for the declared target |

Compile-time bindings preserve their kind after resolution. A resolved `TASK_PARAMETER` therefore carries its source name and resolved value instead of collapsing into an anonymous string.

Missing compile-time task parameters or inputs remain explicit `UNRESOLVED` manifest bindings. They force review-only readiness and are never silently converted to empty strings. Optional compile-time values may use an explicit `defaultValue`.

## Native implementation catalogs

A target distribution explicitly composes a `TargetNativeProjectionCatalog`. Each definition contains only target-neutral contract data:

- opaque payload kind and reference;
- named required or optional binding slots;
- accepted `ProjectionBindingKind` values.

The catalog does not discover plugins, select runtime handlers or define target syntax. It proves only that the selected edge distribution owns a concrete implementation contract corresponding to registry evidence. Registry declaration, provider implementation contract and generated manifest bindings must all agree.

The built-in distribution currently declares reviewed `git.checkout` contracts for:

- Jenkins `JENKINS_STEP/git`, using `url`, `branch` and optional `depth` bindings;
- GitHub Actions `GITHUB_ACTION/actions/checkout@v4`, mapping Flow repository URL, branch and depth to `repository`, `ref` and `fetch-depth`;
- Tekton `TEKTON_TASK/git-clone`, mapping repository URL, revision and depth plus an explicit Pipeline workspace derived from task target metadata.

These contracts share Flow semantic inputs but do not share target syntax. GitHub Actions accepts only repository URLs that can be proven to identify a `github.com` owner/repository pair. Tekton emits a required Pipeline workspace and binds it to the catalog task's `output` workspace. Jenkins preserves full-history behavior when depth is `0` and uses the structured Git SCM checkout contract for shallow clones. Invalid or incomplete values fail closed in the concrete edge renderer.

Targets without an explicit checkout projection rule and matching provider definition remain `ADAPTER_REQUIRED` or review-only.

The built-in distribution also declares reviewed `docker.build` contracts for:

- Jenkins `JENKINS_STEP/docker-build`, mapping image, context and push to the Docker Pipeline object API while rejecting custom Dockerfiles that require free-form CLI arguments;
- GitHub Actions `GITHUB_ACTION/docker/build-push-action@v7`, mapping image, context, Dockerfile and push to structured action inputs;
- Tekton `TEKTON_TASK/buildah`, mapping image, context, Dockerfile and inverted skip-push policy plus an explicit `source` workspace to the reviewed Catalog Task 0.9 contract.

Image-build context and Dockerfile bindings must resolve to safe compile-time relative workspace paths. Dynamic image references may use declared Flow inputs and are translated by the concrete renderer. Unknown interpolation, invalid push values, unsafe paths and unsupported target-specific combinations fail closed. Authentication remains external target configuration and is never inferred from image names or system URLs.

Targets without an explicit image-build projection rule and matching provider definition remain `ADAPTER_REQUIRED` or review-only.

## Executable reference scope

The first committed executable multi-step reference is `checkout-build-image`, scoped to Jenkins. The target scope is part of `snapshot-index.json`; it is not inferred from the existence of similar rules on other platforms. Jenkins keeps checkout and image build ordered in one workspace. GitHub Actions remains excluded until cross-job workspace transfer is represented, and Tekton remains excluded until complete PipelineRun workspace binding is committed as reference evidence.

Native leaf coverage and end-to-end reference readiness are separate claims. A registry may support both actions while a particular generated scenario remains review-only or outside the selected executable evidence scope because continuity between those actions is not proven.

## Expression profiles

A target that declares condition support selects an explicit expression profile. The profile describes Flow AST features, not a hardcoded target switch. Missing or empty evidence fails closed.

## Trigger capabilities

Trigger requirements are ordinary compatibility features such as:

- `trigger.manual`
- `trigger.schedule.cron`
- `trigger.schedule.interval`
- `trigger.schedule.calendar`
- `trigger.event`
- `trigger.webhook`

A renderer emits trigger syntax only when the target registry and manifest evidence support the required form.

## Enforcement

The CLI and conformance path both load the same registry, run `CompatibilityAnalyzer`, generate through `TargetManifestGenerationPipeline`, reconcile compatibility with actual materialization readiness and then apply render policy. There is one evidence path, not one truth for tests and another for users.
