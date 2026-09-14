# Git opts planner reference

## Status

`git-opts-planner` is the normative Horistum reference implementation for a small, typed, deterministic planner.

The reference deliberately lives in `flow-reference-distribution`. It demonstrates how to build a planner against Horistum's architecture without adding Git-provider vocabulary to the target-neutral semantic Core. The first implementation slice is planning-only: it validates a typed intent and produces a side-effect-free operation DAG. Git execution belongs behind an executor/adapter boundary and is not performed by the planner.

Reference identity:

- planner: `git-opts-planner`
- planner version: `1.0.0`
- intent API: `horistum.dev/git-opts/v1`
- intent kind: `GitOptsIntent`
- descriptor: `reference/git-opts-planner/planner-descriptor.json`
- schema: `reference/git-opts-planner/schema/git-opts-intent.schema.json`
- runnable input: `reference/git-opts-planner/examples/change-readme.yaml`

## Why this reference exists

Horistum already has canonical intent, typed compilation, execution-plan, capability, target-selection and adapter boundaries. A developer still needs one small example that makes the responsibilities obvious in executable code.

This reference establishes four rules for future planners:

1. **Intent describes the requested result.** It does not contain shell commands or a serialized script.
2. **Planning is pure.** The planner validates and transforms input; it does not clone, commit, push, call GitHub or read credentials.
3. **The plan is typed and dependency-aware.** Operations carry stable semantic kinds, dependencies and capabilities rather than arbitrary command text.
4. **Execution is a separate responsibility.** A later executor/adapter consumes the plan and implements the capabilities for a concrete environment.

A planner that needs network access to discover what plan it should have emitted is not this reference model. Runtime discovery may be valid for an adapter, but it must not silently redefine canonical planning semantics.

## End-to-end model

```text
GitOptsIntent YAML
      |
      v
strict YAML decoding
      |
      v
GitOptsPlanner.validate
      |
      +---- invalid ----> stable diagnostics, no plan
      |
      v
GitOptsPlanner.plan
      |
      v
canonical operation DAG
      |
      +----> required capability set
      |
      v
GitOptsPlanIntegrity
      |
      v
GitOptsPlan JSON
      |
      v
future executor / target adapter
```

The planner therefore answers **what typed work is required and in what dependency order**. It does not answer **which Git binary, API client or hosted provider performs the work**.

## Intent model

### Repository

```yaml
repository:
  url: https://github.com/Horistum/example.git
```

`repository.url` identifies the requested source repository. The reference planner treats it as semantic input and does not contact it during planning.

### Base and change refs

```yaml
baseRef: main
branch: example/update-reference-docs
```

`baseRef` is the source ref from which the change branch is created. `branch` is the requested change ref. They must differ.

The planner rejects unsafe Git-ref shapes before producing a plan, including control characters, whitespace, `..`, `@{`, repeated `/`, invalid path components and `.lock` components.

### File changes

```yaml
changes:
  - path: README.md
    operation: upsert
    content: |
      # Horistum

      Git opts planner reference example.
```

Supported operations are closed:

| Operation | Content | Capability |
| --- | --- | --- |
| `create` | required | `workspace.file.create` |
| `update` | required | `workspace.file.update` |
| `upsert` | required | `workspace.file.upsert` |
| `delete` | forbidden | `workspace.file.delete` |

Workspace paths must be relative forward-slash paths. Empty components, `.`, `..`, backslashes, absolute paths and `.git` administrative paths are rejected. A path may occur only once in one intent.

This is intentionally stricter than handing a string to `git` or the shell. The planner should know what the requested operation means before execution begins.

### Commit

```yaml
commit:
  message: "docs: demonstrate git opts planner"
```

The commit message is semantic planner input. It must be non-empty and NUL-free.

### Delivery

```yaml
delivery:
  mode: pull-request
  title: "Demonstrate git opts planner"
  body: "Generated from the reference Git opts intent."
```

Delivery has three closed modes:

| Mode | Terminal plan operation | Meaning |
| --- | --- | --- |
| `local` | `scm.commit.create` | stop after creating the local commit |
| `push` | `scm.ref.publish` | publish the requested branch/ref |
| `pull-request` | `scm.change-request.open` | publish the branch and open a change request |

`pull-request` requires an explicit title. Provider credentials are deliberately absent from the intent model.

## Canonical operation model

Every operation implements the same contract:

```text
id
kind
dependsOn[]
capability
```

The concrete reference operations are:

| Kotlin operation | Semantic kind | Required capability |
| --- | --- | --- |
| `GitCheckoutOperation` | `scm.repository.checkout` | `scm.repository.read` |
| `GitCreateBranchOperation` | `scm.branch.create` | `scm.branch.create` |
| `GitFileMutationOperation` | `workspace.file.mutate` | operation-specific workspace capability |
| `GitCreateCommitOperation` | `scm.commit.create` | `scm.commit.create` |
| `GitPublishRefOperation` | `scm.ref.publish` | `scm.ref.publish` |
| `GitOpenChangeRequestOperation` | `scm.change-request.open` | `scm.change-request.open` |

Capabilities are not authored separately. `requiredCapabilities` is derived exactly from the operations in the plan and `GitOptsPlanIntegrity` rejects a forged or incomplete set.

## DAG semantics

For two independent file changes and pull-request delivery the planner emits this dependency graph:

```text
checkout
   |
create-branch
   |\
   | \
change-001  change-002
   \        /
      commit
        |
   publish-ref
        |
open-change-request
```

The commit depends on every file mutation. Independent file mutations do not depend on each other. That distinction is important: a canonical plan records real dependency semantics instead of flattening everything into accidental source order.

`GitOptsPlanIntegrity` rejects:

- duplicate operation IDs;
- missing dependency targets;
- self-dependencies;
- duplicate dependency edges on one node;
- dependency cycles;
- capability sets that do not exactly match operation semantics.

## Determinism contract

`git-opts-planner` is deterministic by design.

For semantically equivalent input, planning must not depend on:

- wall-clock time;
- random UUIDs;
- hostname or current user;
- temporary directories;
- filesystem enumeration order;
- network state;
- repository state discovered at runtime.

File changes are canonicalized by path and operation before stable `change-NNN` IDs are assigned. This means reordering independent file changes in authored YAML does not alter the resulting plan.

The plan contains no generated timestamp. If later evidence needs a timestamp, that belongs to execution/evidence metadata, not to canonical plan identity.

## Failure model

Failures are classified before planning whenever the input itself is invalid. `GitOptsPlanner.validate` emits stable diagnostic codes and source-model paths.

Examples include:

- `GIT_OPTS_UNSUPPORTED_API_VERSION`
- `GIT_OPTS_UNSUPPORTED_KIND`
- `GIT_OPTS_INVALID_REPOSITORY`
- `GIT_OPTS_INVALID_REF`
- `GIT_OPTS_BRANCH_EQUALS_BASE`
- `GIT_OPTS_EMPTY_CHANGESET`
- `GIT_OPTS_INVALID_PATH`
- `GIT_OPTS_DUPLICATE_PATH`
- `GIT_OPTS_MISSING_CONTENT`
- `GIT_OPTS_DELETE_HAS_CONTENT`
- `GIT_OPTS_INVALID_COMMIT_MESSAGE`
- `GIT_OPTS_MISSING_CHANGE_REQUEST_TITLE`
- `GIT_OPTS_INVALID_DELIVERY_TEXT`

An invalid input produces no partially valid `GitOptsPlan`.

This first reference slice does not fabricate runtime failures such as authentication failure, branch protection, repository not found or remote ref conflict. Those can only be known honestly by an executor/adapter and will belong to the execution failure model.

## Security boundary

The reference intentionally contains no command field and no generic shell operation.

It also does not accept credentials, tokens or private keys in the intent. Credentials are an execution-time binding concern. A future GitHub/GitLab/Bitbucket adapter can obtain them through the platform's credential mechanism without changing the planner model.

Workspace traversal and writes into `.git` are rejected at planning time. This is not meant to replace executor sandboxing. A conforming executor must still enforce its own workspace root and capability boundaries because validating once and trusting everything forever is how software eventually becomes an incident report.

## Running the reference

From the repository root:

```bash
./gradlew :flow-reference-distribution:gitOptsPlan \
  -PgitOptsFile=reference/git-opts-planner/examples/change-readme.yaml
```

The command loads the YAML with unknown-field rejection, validates it, builds the deterministic DAG, runs plan-integrity checks and writes the resulting JSON plan to standard output.

It performs **no Git or network side effects**. This is intentional. The command is the executable reference for the planner boundary, not a disguised Git script.

To use the example for another case, edit:

- `repository.url`;
- `baseRef`;
- `branch`;
- `changes`;
- `commit.message`;
- `delivery`.

Then run the same Gradle command. No Kotlin source edit is required.

## What is normative in this reference

A future Horistum planner should copy the architectural pattern, not blindly copy Git vocabulary.

Normative properties are:

1. closed input types for known semantic choices;
2. strict validation before plan construction;
3. side-effect-free planning;
4. deterministic normalization;
5. typed operations instead of command strings;
6. explicit DAG dependencies;
7. capabilities derived from operations;
8. a separate plan-integrity boundary;
9. machine-readable descriptor and schema;
10. positive and negative executable tests;
11. runnable example input requiring configuration changes only;
12. no credentials embedded in canonical intent.

## Extension pattern

For a second planner:

1. define a small intent model for the requested outcome;
2. define a closed operation vocabulary;
3. associate each operation with one semantic capability;
4. validate authored values before constructing operations;
5. canonicalize unordered authored input before assigning identities;
6. express true dependencies as a DAG;
7. derive required capabilities from that DAG;
8. add an integrity validator that rejects forged plan state;
9. add a descriptor, schema and runnable example;
10. keep provider/runtime code out of the planner.

If a capability is genuinely cross-domain and target-neutral, it may later justify promotion into the public Horistum standard. The reference module must not make that promotion implicitly merely because Git happens to be a convenient example.

## Planned continuation

The reference is intentionally split so each boundary remains reviewable:

- **REF-01A**: closed Git opts intent/operation/capability model;
- **REF-01B**: deterministic planner, DAG integrity, schema, descriptor and runnable planning example;
- **REF-01C**: isolated local Git executor fixture proving actual branch/file/commit effects without network access;
- **REF-01D**: hosted change-request adapter example with credentials supplied only at execution time;
- **REF-01E**: positive/negative conformance vectors and standard artifact integration;
- **REF-01F**: extension guide and final normative-reference acceptance.

REF-01A/B establish the planner contract. REF-01C must not move Git side effects back into `GitOptsPlanner`; it should consume the plan through a separate execution boundary.
