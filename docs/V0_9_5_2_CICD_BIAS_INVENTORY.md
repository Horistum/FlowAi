# v0.9.5.2 CI/CD Bias Inventory

## Purpose

v0.9.5.2 records the second correction inventory after the notes-driven architecture recenter.

The goal is not to erase every mention of Jenkins, GitHub Actions, Tekton, Kubernetes, Docker, Maven, PostgreSQL or Argo CD. Those can exist as adapter targets, scenario examples, module notes or conformance fixtures. The goal is to prevent those concrete tools from being treated as universal Flow semantics.

Flow Core must remain a universal automation standardization model. CI/CD is one important domain, not the project identity.

## Inventory categories

The inventory classifies remaining vocabulary into these categories:

- `target`: concrete target names and identifiers such as Jenkins, GitHub Actions and Tekton
- `infrastructure`: concrete infrastructure platforms such as Kubernetes
- `tool`: concrete build, container and registry tooling such as Docker, Maven and registry terminology
- `data-system`: concrete database implementations such as PostgreSQL
- `workflow-vocabulary`: CI/CD-shaped words such as pipeline, workflow, build, deploy and deployment
- `runtime`: runner terminology
- `domain`: explicit CI/CD domain labels

## Classification boundaries

The inventory separates evidence by architectural location:

- `adapter-boundary`: target and renderer code where concrete target names are expected
- `module-or-target-note`: declarative module and target notes
- `scenario-or-conformance`: examples and tests that intentionally exercise concrete domains
- `documentation`: architecture and roadmap text
- `active-semantic-source`: core source that still contains concrete CI/CD wording and must be treated as visible debt, not as proof of universal semantics

This distinction matters. A Jenkins renderer may mention Jenkins. The Flow semantic model must not use Jenkins as its default truth.

## Findings

The repository still contains CI/CD-shaped vocabulary in several valid but architecturally important places:

- adapter and renderer boundaries for the currently implemented projection targets
- scenario packs and reference conformance fixtures focused on deployment, Kubernetes maintenance and build/test/deploy examples
- module notes for Docker, Kubernetes, Helm, Argo CD, Git and database behaviors
- documentation explaining historical target support and correction-track boundaries
- active semantic source that still contains scenario-pack and conformance helper wording tied to concrete CI/CD examples

These are not hidden as successful materialization. They remain visible architecture evidence.

## Evidence-driven follow-up

`CiCdBiasInventoryAnalyzer` reports stable architectural follow-up areas derived from current repository evidence:

- `SEMANTIC_MODEL`
- `ADAPTER_BOUNDARY`
- `SCENARIO_AND_CONFORMANCE`
- `DOCUMENTATION`
- `NOTES_AND_TARGET_DECLARATIONS`

The analyzer does not schedule roadmap work and does not embed future release numbers. Roadmap ordering belongs in `.flow-agent/roadmap.yaml` and the active repair-track metadata, where scheduling can change without changing production analysis behavior.

This separation keeps the analyzer factual: it reports where evidence exists now. It does not pretend to know which future package or correction item will resolve it.

## Prohibition

Future Flow Core changes must not introduce:

- a concrete CI/CD target as semantic default truth
- Kubernetes, Docker, Maven, PostgreSQL, Jenkins, GitHub Actions, Tekton or Argo CD as universal Flow meaning
- deployment scenarios as proof that Flow is universal
- runner or workflow assumptions outside declared runtime and projection notes
- target support claims without notes, materialization status and conformance evidence
- future roadmap version lists inside production analyzers

## Versioning boundary

- The published package version remains `0.9.4`.
- The active correction track is the unreleased `v0.9.5.x` roadmap scope.
- The active public standard version remains `0.7.6`.
- No artifact contract version is changed.
- No runtime executor, SDK API, framework or plugin lifecycle, shell projection, target-specific public DSL, renderer expansion or Flow syntax change is introduced.
