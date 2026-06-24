# Public Standard Surface

Introduced in v0.4.5.

The public standard surface is the set of artifacts, schemas and conformance
rules that external implementations may rely on. It is intentionally smaller
than the Kotlin implementation.

Stable public contracts include:

- intent schema
- execution plan schema
- target manifest schema
- conformance manifest
- standard contract index
- standard release profile
- public standard surface report

Changing the public surface requires:

- a schema compatibility review,
- a migration note when consumers need to change,
- a negative conformance case,
- release-profile coverage.

Implementation helpers are not part of the public surface unless they are
listed in `public-standard-surface.json`.
