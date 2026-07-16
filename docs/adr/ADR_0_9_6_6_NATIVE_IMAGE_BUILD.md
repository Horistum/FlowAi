# Architecture Decision: Add reviewed native image-build projections

## Status

Accepted

## Change

The built-in distribution implements `docker.build` as a native projection for Jenkins, GitHub Actions and Tekton through the provider-owned contracts introduced in v0.9.6.4.

The Flow semantic inputs remain target-neutral:

- image reference `image`;
- workspace-relative build context `path`, defaulting to `.`;
- optional workspace-relative `dockerfile`;
- compile-time `push` policy, defaulting to `false`.

Concrete target mappings remain edge-owned:

- Jenkins uses the Docker Pipeline `build()` object API and optional returned-image `push()` method;
- GitHub Actions uses `docker/build-push-action@v7` with structured `context`, `file`, `tags` and `push` inputs;
- Tekton uses the reviewed Catalog `buildah` Task 0.9 contract with typed params and an explicit `source` workspace.

## Main Flow Axis

- Target registry evidence
- Native projection provider contracts
- Typed binding preservation
- Target manifest generation
- Concrete renderer behavior
- Behavioral conformance

## Semantic fidelity

An image-build projection is complete only when image identity, context, Dockerfile selection and push policy are preserved. The renderer may transform representation but must not replace these values with a universal command string.

Build context and Dockerfile values must be compile-time relative workspace paths. Absolute paths, option-like values, traversal outside the workspace, whitespace, control characters and unresolved runtime path expressions fail closed.

Image values may reference declared Flow inputs. Each target translates the interpolation through its own native input mechanism. Unknown or target-specific dollar interpolation fails closed.

Push policy is deliberately compile-time in this contract. A non-boolean value or unresolved dynamic expression is rejected instead of being approximated differently by each target.

## Target-specific constraints

### Jenkins

The Docker Pipeline plugin provides a structured `docker.build(image)` API and a directory overload. It exposes custom Dockerfile selection only through a free-form Docker CLI argument string. Flow therefore supports the default Dockerfile for the selected context and rejects a non-default Dockerfile for Jenkins rather than reintroducing command projection.

### GitHub Actions

The renderer uses the action's default Git context. A non-root Flow context becomes `{{defaultContext}}:<path>`. A custom Dockerfile must be inside that context and is rendered relative to the selected Git context. Authentication and registry login remain external target configuration.

### Tekton

The renderer references the reviewed `buildah` Task contract, emits `IMAGE`, `CONTEXT`, `DOCKERFILE` and `SKIP_PUSH` params, declares the required Pipeline workspace and binds it to the Task's `source` workspace. The cluster must install a Task matching the reviewed 0.9 contract; Flow does not manage Task installation or runtime credentials.

## Selected targets

- Jenkins: native default-Dockerfile build and optional push.
- GitHub Actions: native Buildx action with structured inputs and custom Dockerfile support inside context.
- Tekton: native Catalog Task reference with structured params and workspace.

Other built-in targets receive no image-build rule and remain adapter-required or review-only.

## Explicit non-goals

This decision does not add:

- runtime execution;
- SDK or plugin lifecycle;
- classpath discovery;
- target-specific public Flow syntax;
- shell or command projection;
- Docker CLI argument strings as universal semantics;
- registry authentication or credential inference;
- multi-platform, cache, secret or build-argument expansion;
- an executable multi-step reference scenario.

## Negative conformance

Tests reject:

- unsafe, absolute, option-like, traversing or dynamic build paths;
- unknown image interpolation;
- non-boolean push policy;
- a Jenkins custom Dockerfile that would require a Docker CLI argument string;
- a GitHub Dockerfile outside the selected Git context;
- registry/catalog mismatch;
- missing image-build evidence on an unimplemented target.

## Report budget

No new public report contract is added. The bounded work report records implementation scope while Target Manifest and existing readiness artifacts remain the public evidence surfaces.

## Drift score

The change adds target-native coverage only through the existing provider boundary. It introduces no runtime, SDK, plugin discovery, shell projection or target-specific semantic model. No Drift Score exception is required.

## Consequences

The first executable reference scenario may use image build only when its checkout, build and all remaining actions have concrete projection evidence for the selected target. Native image-build support alone does not promote a mixed scenario to executable.
