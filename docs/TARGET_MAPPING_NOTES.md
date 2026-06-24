# Target Mapping Notes

Flow renders target-specific DevOps artifacts from a canonical TargetManifest.
A mapping note records where the target cannot represent Flow semantics directly
or where the renderer uses a convention.

Mapping notes are intentionally part of the generated artifact. They prevent a
silent downgrade from the platform-neutral Flow standard into a target-specific
half-truth. Users should not have to know every Jenkins, GitHub Actions or Tekton
edge case; Flow must surface that information before execution.

## Examples

- `approval.environmentGate`: GitHub Actions represents approval through protected environments, not an inline step.
- `approval.inline`: Jenkins can use an `input` step.
- `target.partial`: Tekton generation is partial where manual approval or complex dynamic expressions require external gates or Flow runtime support.
- `semantic.generic`: `standard.execute` preserves the standard intent as an auditable placeholder until a production lowering contract exists.

## Rule

A renderer must not silently replace unsupported semantics with an unconditional
echo. It must either:

1. Render a semantically valid mapping,
2. Emit an explicit mapping note, or
3. Fail compatibility validation before rendering.
