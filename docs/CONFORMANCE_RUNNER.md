# Flow Conformance Runner

Status: draft for v0.3.0-rc1.8.3.

Flow is intended to become a standard, not a pile of demos that happen to run on a good day. The conformance runner provides a CLI-facing verification path for the public standard pipeline:

```text
Intent YAML
  -> Normalized Intent
  -> Intent Capability Validation
  -> Flow AST
  -> AST Validation
  -> Execution Plan
  -> Target Compatibility Report
  -> Target Manifest
  -> Optional Vendor Renderer
```

Run:

```bash
./gradlew run --args="conformance"
```

Current checks cover:

- valid high-level build-test-deploy intent
- invalid ArgoCD system config detection
- Tekton strict compatibility rejection for manual approval
- Jenkins target manifest generation
- GitHub Actions target manifest generation
- YAML flow-style map normalization

The files under `conformance/` are the beginning of the formal vector suite. The Kotlin runner currently implements a subset directly. Future versions should load these vector documents and compare actual outputs against expected normalized JSON/YAML.

## Why this matters

A Flow implementation should not be considered compatible because it parses a happy-path file. It should prove that it preserves intent, validates capability requirements, refuses unsupported target semantics when strict mode is enabled, and creates auditable target manifests before rendering vendor syntax.
