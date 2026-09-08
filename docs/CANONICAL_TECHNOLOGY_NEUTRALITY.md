# SI-03 Canonical Technology Neutrality

## Purpose

SI-03 removes concrete image-builder technology from the target-neutral Standard Intent contract without deleting implementation evidence that belongs in adapters.

`BUILD_IMAGE` and `PUSH_IMAGE` remain canonical capability identities because they describe implementation-independent operations. Docker remains a valid concrete implementation through `modules/docker.yaml`, explicit `docker.build` / `docker.push` bindings and target-owned projection evidence.

## Canonical boundary

The canonical image capability contract now has these properties:

- `BUILD_IMAGE` semantic parameters are `image`, `path` and `push` plus generic binding metadata such as `system` and `target`;
- `PUSH_IMAGE` keeps canonical image publication semantics;
- neither capability requires a Docker system;
- neither capability uses Docker as its lowering strategy;
- `dockerfile` is not canonical intent meaning;
- the public standard catalog does not nominate Docker as the defining module for either image capability.

Canonical effects remain `software.image` creation and registry publication. The serialized capability identities and public artifact shapes do not change, so Intent, AST and ExecutionPlan contract versions remain unchanged.

## Explicit Docker binding

An author may still explicitly select Docker:

```yaml
- id: image
  capability: BUILD_IMAGE
  uses: docker.build
  params:
    system: builder
    image: acme/service:1
    path: services/api
    push: false
    dockerfile: services/api/Dockerfile.release
```

Here `image`, `path` and `push` are semantic parameters. `dockerfile` is binding-only configuration. It is preserved into the resolved adapter action and target projection but is absent from `CanonicalIntentMeaning`.

Without explicit `uses: docker.build`, a `dockerfile` parameter is rejected as unknown rather than silently discarded or promoted into canonical meaning.

## Binding evidence migration

C0.4 certified `adapters/bindings/builtin-capability-bindings.yaml` version `1.0` with SHA-256:

`eda2fdec8e6a1b42a969ffa8e68a09567080d56a7e01a2786dccb06ae820f5f2`

That file remains byte-for-byte unchanged as historical evidence.

Current binding evidence lives in `adapters/bindings/builtin-capability-bindings-v1.1.yaml`. The v1.0 to v1.1 migration permits exactly one semantic change:

- `docker.build#BUILD_IMAGE`: `dockerfile` moves from `semanticParameters.mapped` to `bindingParameters`.

No record may be added, removed or reordered by this migration. No system type, effect policy, limitation, evidence reference, unsupported parameter or other semantic mapping may change. `AdapterCapabilityBindingMigrationAuthority` fails closed on any additional delta and also verifies the frozen C0.4 digest.

C0.4 continues reading the frozen v1.0 binding snapshot. Current adapter conformance evaluates the v1.1 live document and the migration proof independently.

## Falsification boundary

SI-03 regression tests prove that:

- Docker inventory presence cannot change canonical image meaning;
- an explicit Dockerfile changes binding evidence but not canonical meaning;
- Dockerfile without explicit Docker binding is rejected;
- the frozen v1.0 binding snapshot cannot be rewritten;
- any v1.1 migration delta beyond the reviewed Dockerfile reclassification fails;
- current live binding evidence still reconciles exactly with module implementation claims.
