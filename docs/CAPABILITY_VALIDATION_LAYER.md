# Flow Capability Validation Layer v1.0 Draft

## Purpose

The Capability Validation Layer validates the normalized Standard Intent Model
before it is lowered to Flow AST.

This keeps Flow aligned with its core goal: users describe intent and constraints,
while Flow detects platform/module requirements before runtime.

## Pipeline

```text
Human / AI Intent
  -> Intent YAML/JSON
  -> Standard YAML Loader
  -> Normalized Intent
  -> Capability Validation
  -> Flow AST Lowering
  -> AST Validation
  -> Execution Plan
  -> Target Compatibility Report
```

## Responsibilities

- Validate required system configuration.
- Validate sensitive config references.
- Validate known system types.
- Validate step dependencies and cycles.
- Validate explicit step target systems.
- Stop lowering when the intent cannot satisfy capability requirements.

## Example: ArgoCD

ArgoCD module descriptor requires:

```yaml
systemTypes:
  argocd:
    input:
      url:
        type: text
        required: true
      token:
        type: secret
        required: true
        sensitive: true
```

Valid intent:

```yaml
systems:
  - name: argo
    type: argocd
    config: { url: secret:ARGOCD_URL, token: secret:ARGOCD_TOKEN }
```

Invalid intent:

```yaml
systems:
  - name: argo
    type: argocd
```

The invalid intent must fail before AST lowering.

## Design Rule

If a problem can be detected in the intent layer, it must not be postponed to
runtime.
