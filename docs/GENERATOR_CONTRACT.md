# Generator Contract v1.0

Flow generators should not jump directly from Execution Plan to vendor syntax.

The preferred chain is:

```text
Execution Plan
  -> Compatibility Report
  -> Target Manifest
  -> Vendor Renderer
```

## Why

The Target Manifest is an auditable intermediate artifact. It helps separate Flow semantics from Jenkinsfile YAML/Groovy, GitHub Actions YAML, Tekton CRDs, Argo Workflow manifests, and whatever new format humanity invents next week.

## Current implementation

`JenkinsManifestGenerator` creates a first `TargetManifest` from an Execution Plan and a Compatibility Report.

This is intentionally not the final Jenkinsfile renderer. It is the first generator contract object that future renderers can consume.

## Work in progress

- GitHub Actions Target Manifest generator.
- Tekton Target Manifest generator.
- Argo Workflows Target Manifest generator.
- Formal JSON Schema for Target Manifest.

## v0.3.0-rc1.8.3 Mapping Note Contract

Renderers consume `TargetManifest` only. They must not inspect raw Flow AST or
re-plan intent. If target syntax cannot represent a Flow node faithfully, the
manifest or renderer must carry a mapping note. This keeps the project aligned
with the main goal: users describe intent once and Flow explains target-specific
limitations instead of hiding them in vendor YAML.
