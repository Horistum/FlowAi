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

The current repository still contains CI/CD-shaped vocabulary in several valid but architecturally important places:

- adapter and renderer boundaries for the currently implemented projection targets
- scenario packs and reference conformance fixtures focused on deployment, Kubernetes maintenance and build/test/deploy examples
- module notes for Docker, Kubernetes, Helm, Argo CD, Git and database behaviors
- documentation explaining historical target support and correction-track boundaries
- active semantic source that still contains scenario-pack and conformance helper wording tied to concrete CI/CD examples

These are not hidden as successful materialization. They are carried forward as correction-track evidence.

## Required follow-up

The inventory feeds the remaining v0.9.5.x correction track:

- v0.9.5.3 Notes Package Contract Model
- v0.9.5.4 Universal Semantic Action Graph
- v0.9.5.5 Materialization Negotiation
- v0.9.5.6 No-Shell Target Projection
- v0.9.5.7 Target Registry Honesty
- v0.9.5.8 Policy-Driven Safety and Environment Classification
- v0.9.5.9 Trigger and Schedule Notes
- v0.9.5.10 Conformance Honesty Gates

## Prohibition

Future Flow Core changes must not introduce:

- a concrete CI/CD target as semantic default truth
- Kubernetes, Docker, Maven, PostgreSQL, Jenkins, GitHub Actions, Tekton or Argo CD as universal Flow meaning
- deployment scenarios as proof that Flow is universal
- runner or workflow assumptions outside declared runtime and projection notes
- target support claims without notes, materialization status and conformance evidence

## Versioning boundary

- Current package version remains `0.9.4`.
- Active public standard version remains `0.7.6`.
- This is roadmap correction work inside the v0.9.5.x correction track.
- No artifact schema version is changed.
- No runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax change is introduced.
