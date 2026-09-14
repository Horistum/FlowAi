# git-opts-planner reference example

This directory is a **standalone consumer example** for Horistum.

It is intentionally not implemented under `src/main/kotlin`, is not a new Horistum product module, does not add Git-specific capabilities to the standard, and does not modify any target adapter. The entire point of the example is to demonstrate how a team can model its own operational domain **outside Horistum Core** while still using Horistum for normalization, validation, planning, canonicalization and compatibility evidence.

The example can be copied out of this repository. Inside the repository, `run.sh` can bootstrap the product CLI automatically. Outside the repository, point `HORISTUM_BIN` at an installed Horistum `flow-core` CLI.

## Goal

The sample represents a common Git change workflow:

```text
checkout repository
      |
create change branch
      |
update a file
      |
create commit
      |
publish branch
      |
open pull request
```

The important part is not Git itself. Git is deliberately used because the model is easy to understand. The reference shows the pattern for an organization-specific planner/domain such as:

- change-management operations;
- release operations;
- artifact promotion;
- internal platform requests;
- infrastructure lifecycle operations;
- security operations;
- database maintenance.

## Architectural boundary

The reference consists only of external declarative inputs and a black-box verification script:

```text
examples/reference/git-opts-planner/
├── README.md
├── intent/
│   └── git-change.intent.yaml
├── modules/
│   └── gitops.yaml
├── targets/
│   └── reference-analysis.yaml
├── run.sh
└── verify.py
```

There are deliberately **no** files under:

```text
src/main/kotlin/
flow-*/src/main/kotlin/
```

for this reference.

That is the key design rule.

## Model

### 1. Domain model remains outside Core

Git-specific operations such as:

```text
create branch
write file
create commit
push ref
open pull request
```

are not silently promoted to Horistum standard capabilities.

Instead the example declares them in its own module descriptor:

```text
modules/gitops.yaml
```

The module is an extension contract owned by the example consumer.

Horistum remains unaware of Git branch creation as a new universal semantic primitive.

### 2. Standard semantics are reused when they actually exist

Horistum already has the standard `CHECKOUT` capability, so the external module declares:

```yaml
checkout:
  implements: [CHECKOUT]
```

The remaining operations use `CUSTOM` because they are not currently part of the standard capability vocabulary.

This is deliberate. A reference example must not pretend an organization-specific operation is a standard merely because it is useful.

If Horistum later standardizes one of these semantics, the external descriptor can migrate from `CUSTOM` to the new standard capability without rewriting Core for every early experiment.

### 3. Binding-only parameters stay with the external module

For example, `createBranch` owns:

```yaml
branch:
from:
```

and `openPullRequest` owns:

```yaml
base:
head:
title:
body:
```

These values are resolved through the explicit `uses: gitops.<action>` binding. They are not added as fields to Horistum's canonical public model.

### 4. Intent expresses ordering, not shell commands

The example intent is:

```text
intent/git-change.intent.yaml
```

It contains no:

```text
git checkout -b ...
git add ...
git commit ...
git push ...
curl GitHub API ...
```

Instead it declares typed steps and dependencies.

A shortened form looks like:

```yaml
- id: create-branch
  capability: CUSTOM
  uses: gitops.createBranch
  requires: [checkout]
  params:
    system: repository
    branch: example/update-readme
    from: main
```

Horistum then owns the generic pipeline:

```text
Intent YAML
   |
   v
strict intent parsing
   |
   v
module binding validation
   |
   v
canonical intent meaning
   |
   v
Flow lowering
   |
   v
Execution Plan
   |
   v
Canonical Execution Plan
   |
   v
target-neutral compatibility evidence
```

The Git-specific module never becomes a second planner implementation hidden inside Horistum.

## The external module descriptor

`modules/gitops.yaml` defines six actions:

| Action | Standard capability | Module capability |
| --- | --- | --- |
| `checkout` | `CHECKOUT` | `git.checkout` |
| `createBranch` | `CUSTOM` | `git.branch.create` |
| `writeFile` | `CUSTOM` | `workspace.file.write` |
| `commit` | `CUSTOM` | `git.commit.create` |
| `push` | `CUSTOM` | `git.ref.publish` |
| `openPullRequest` | `CUSTOM` | `scm.change-request.open` |

Each action declares:

- typed input fields;
- output fields;
- effects;
- workspace continuity where required;
- required implementation capability;
- safety metadata.

This is the actual reusable extension model demonstrated by the example.

## Workspace continuity

Checkout provides the logical workspace channel:

```text
source
```

Branch creation, file modification, commit creation and push consume that workspace as needed.

The module descriptor therefore carries continuity semantics instead of relying on the fact that a shell happens to run several commands in the same directory.

That distinction matters on targets where steps may execute on different agents, pods or runners.

## Target profile

`targets/reference-analysis.yaml` is intentionally an **analysis-only** target.

It does not claim that this directory contains a real Git executor. Git-specific implementation features are marked unsupported.

That means the example can prove two things independently:

1. the intent and external module can be validated and planned correctly;
2. Horistum still refuses to invent executable support that has not been supplied.

A later real executor/adapter can provide the missing capabilities without changing the intent model or Horistum Core.

This is preferable to a cheerful green compatibility report backed by absolutely nothing, a surprisingly popular distributed-systems design pattern.

## Running the example

From this directory:

```bash
./run.sh
```

When running inside the Horistum repository, the script uses:

```text
flow-cli/build/install/flow-core/bin/flow-core
```

and builds the product CLI first if necessary.

When the example is copied elsewhere, point it to an installed CLI:

```bash
HORISTUM_BIN=/opt/horistum/bin/flow-core ./run.sh
```

The script runs Horistum from this directory so the local `modules/` and `targets/` directories are the extension surface seen by the product.

No Horistum source tree mutation is required.

## Generated artifacts

A successful run writes a `generated/` directory containing the normal Horistum planning artifacts, including:

```text
normalized-intent.json
intent-capability-validation-report.json
execution-plan.json
canonical-execution-plan.json
target-neutral-planning-report.json
```

`verify.py` then checks the output as a black-box consumer. It verifies that:

- the normalized intent preserved all six authored steps;
- intent capability validation succeeded;
- each step became a `gitops` task with the expected action;
- module-required capabilities are present in the execution plan;
- source identities survive into the canonical plan;
- no execution target was implicitly selected.

The verification script does not import Horistum internals.

## Changing the example

For ordinary use, change only:

```text
intent/git-change.intent.yaml
```

Typical fields are:

```yaml
systems:
  - name: repository
    config:
      url: https://github.com/your-org/your-repo.git
```

and:

```yaml
branch: feature/your-change
path: path/to/file
content: ...
message: ...
base: main
head: feature/your-change
title: ...
```

Then run:

```bash
./run.sh
```

If the workflow needs a new organization-specific Git operation, extend:

```text
modules/gitops.yaml
```

rather than editing Horistum production code.

## When should Horistum Core change?

Only when an operation is demonstrably cross-domain, technology-neutral and stable enough to belong to the standard.

The sequence should be:

```text
external module experiment
       |
       v
multiple real consumers
       |
       v
stable common semantics
       |
       v
standardization proposal
       |
       v
Horistum Core change
```

not:

```text
one useful Git operation
       |
       v
add another enum and planner class to Core
```

The latter is how supposedly universal platforms gradually become a museum of whichever integrations were implemented first.

## Execution is a separate example boundary

This reference is a **planner/model example**. It deliberately does not run Git commands or call GitHub/GitLab/Bitbucket APIs.

A real implementation should add an external executor/adapter that consumes the planned operation contract and provides capabilities such as:

```text
git.branch.create
workspace.file.write
git.commit.create
git.ref.publish
scm.change-request.open
```

Credentials belong to that execution boundary, not to canonical planning intent.

The executor should be separately testable against a temporary repository and should not require a change to Horistum Core.

## What this example is intended to teach

The reusable pattern is:

```text
Domain-specific request
        |
        v
external module descriptor
        |
        v
Standard Intent with explicit bindings
        |
        v
Horistum generic validation/planning
        |
        v
Canonical plan + capability requirements
        |
        v
external executor/adapter
```

That is the model other Horistum examples should follow unless the semantics genuinely belong in the standard itself.
