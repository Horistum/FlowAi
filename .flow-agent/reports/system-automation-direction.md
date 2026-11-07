# System automation direction and resource-specific fidelity

Baseline: merged PR #258 (`d4f98d525a8ad4ae9a6fc051155e75dcb4752f1a`).
Work package: [AI-A3-S1](../work-packages/system-automation-direction.yaml).
The maintainer requested whole infrastructure/DevOps and complex operational scenarios.
Pinned-file investigation is retained as support, not accepted as that product outcome.

## Findings and resulting direction

The capability vocabulary already covers infrastructure, identity, data and operations.
The limiting boundaries are flat Intent authoring, undifferentiated same-capability
requirements, incomplete resource/outcome semantics, manually selected evidence and
implementation/readiness gaps. More scenario packs would not resolve those boundaries.
The selected [continuation](../../docs/SYSTEM_AUTOMATION_DIRECTION.md) orders structural
authoring, discovery, reusable implementation composition and live cross-domain proof.

The infrastructure example exposed an additional compiler defect: task `dependsOn`
listed data-reference predecessors before declared predecessors, while dependency
relations and canonical projection listed declared predecessors first. Distinct
producer/output names could therefore cause an exact-projection rejection for a
valid prerequisite set. `FlowPlanner` now constructs the list in relation order;
the authorization equality check remains unchanged. The new compiled examples and
step/output-renaming regression exercise the original failure.

## Implemented boundary

- Requirements v2 select canonical operations using capability, optional workflow
  and exact literal semantic parameters; v1 rejects the new fields and semantics.
- Zero-count checks can exclude a specific resource operation without excluding
  every operation of that capability.
- `value-from` requires a unique selected producer, a direct output reference and a
  matching resolved VALUE edge. Literal object paths support composed configurations.
  Ordering alone, placeholders and a value produced by another operation do not pass.
- Dynamic selectors, ambiguous producers and unrepresented value/control forms need
  review. Known mismatches exclude a node independently of filter iteration order.
- The external host captures requirements v2 independently of its provider prompt.
  New prompt version 2 covers environment lifecycles; existing captured runs keep
  their instructions. No alternate graph or scenario dispatcher is introduced.
- Three manually authored planning examples cover foundation provisioning and DevOps
  setup, isolated data recovery/migration and environment retirement. The installed
  CLI proof includes seven negative mutations and runs in existing host-verification CI.
- Public schema ownership, packaged contracts, migration guidance and a standalone
  conformance vector are updated. The original 12-case authoring corpus is unchanged.

## Validation

- Python: 330 tests passed.
- JDK 25 / Gradle 9.5.0 offline build: all 2,443 JVM tests passed, with no failures,
  errors or skipped tests. The installed contract package was rebuilt and inspected.
- Standalone conformance: all 299 checks passed. Its first run detected the omitted
  new check-inventory entry; registration was corrected before the successful rerun.
- Installed host proof passed: 36 frozen-corpus runs, 49 recorded provider attempts
  in the original host proof, and six investigation cases retain their classifications.
- Installed system proof passed all ten cases: three accepted lifecycle plans and
  seven rejected mutations (resource substitution, wrong producers, missing/literal
  values, shared-resource removal and an invented build).
- Flow agent validation, documentation links and whitespace checks passed.
- Final exact-head and merge-candidate CI are required on the published revision;
  their run links belong in the pull request rather than a self-referential commit.

The first GitHub run on `c28cb2947e6a365ab008f518b132785c35ebfa7e` passed
runtime certification, physical isolation and complete exact-head validation
(18m33s). The merge candidate passed compilation, tests and conformance, then
was canceled at the 20-minute job boundary during the installed-host proof.
Both full-validation jobs now have a 25-minute ceiling; isolation remains at
20 minutes. The CI cost-policy regression and documentation retain both revision
checks and all proof steps. A fresh run is required for the final revision.

## Limits and next owner

These are declared-plan checks, not typed cloud identity, runtime postconditions,
autonomous discovery or real-model quality measurements. The examples use unbound
standard operations and do not provision Azure. VERIFY retains its earlier
deployment-oriented effect category; changing that contract belongs to S2.

S2 is selected next and must preserve conditional reuse/create, bounded readiness,
partial-failure recovery and resource ownership through the existing canonical axis.
The direction document defines that concrete acceptance request and a second data
composition. General cross-workflow lifecycle execution remains unimplemented.
AI-A3's live corpus and held-out render gates, AR-06/AR-07 and their existing findings
remain open; EF-09 remains paused. No target support or runtime evidence is promoted.
