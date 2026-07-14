# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed correction track: `v0.9.5.x`

## Release purpose

Flow 0.9.5 completes the universal semantic and projection model that the v0.9.5.x repair track prepared. The release has one reconciled manifest generation boundary, first-class trigger semantics, target-neutral lowering, extensible target semantics and evidence-driven native projection.

## Corrected integrity gaps

1. Manifest generators expose only a final reconciled `generate` path. `TargetManifestGenerationPipeline` is the CLI boundary, so CLI and conformance cannot serialize different compatibility truths.
2. Jenkins, GitHub Actions and Tekton renderers contain no no-op job or phantom task fallback. Executable rendering requires a structured native payload.
3. Generic DEPLOY and VERIFY intent lower to semantic standard actions. Kubernetes systems, namespaces and selectors appear only through explicit `uses: kubernetes.*` intent.
4. Intent, AST, ExecutionPlan and TargetManifest carry manual, schedule, event and webhook triggers. Interval schedules use ISO-8601 durations such as `P30D`.
5. `TargetSemanticsEntry` uses `semanticsByTarget`, keyed by target registry ids. Adding a target does not change the public Kotlin data model.
6. Materialization is selected by declarative target projection rules. Missing rules fail closed as `ADAPTER_REQUIRED`. Jenkins `git.checkout` is backed by concrete native payload evidence and can produce executable target syntax.
7. The package, public standard and serialized artifact contracts are deliberately promoted and documented instead of leaving materially changed formats at historical version numbers.

## Versioning boundary

- Published package version: `0.9.5`
- Next package version: `0.9.6`
- Active public standard version: `0.8.0`
- Intent artifact version: `2.0`
- AST artifact version: `2.0`
- ExecutionPlan artifact version: `2.0`
- TargetManifest artifact version: `2.0`
- TargetRegistry artifact version: `2.0`
- Target semantics matrix version: `2.0`

Historical correction identifiers such as `0.9.5.7.9` remain traceability labels for completed work. They are not package or artifact versions.

## Validation source

Authoritative release validation is the standard Flow CI run on the final pull-request head:

- Flow Agent tooling tests
- Flow Agent structure validation
- Flow Agent context generation
- offline tests when cache is available
- offline conformance when cache is available
- clean compile and full test suite
- full conformance
- CI logs and test report artifacts

Validation remains pending until the final clean branch completes these gates. No temporary workflow, patch transport or snapshot writer is part of the release diff.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. It does not execute workflows, expose an SDK lifecycle, own a plugin framework, project raw shell commands or add target-specific public language syntax. Native target output is allowed only when declarative evidence and structured payloads prove it.
