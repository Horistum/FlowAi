# Flow Core v0.3.0-rc1.8.3 Notes

This release candidate focuses on proving the public standard pipeline through exportable artifacts.

## Main Additions

- `--out` artifact export.
- TargetManifest JSON as a first-class standard artifact.
- End-to-end snapshots.
- GitHub Actions renderer improvements.
- Tekton partial target renderer.
- Standard version metadata across generated artifacts.

## Why This Matters

Flow must not become another DSL that only prints console output. A standard needs reproducible artifacts and conformance vectors.
