# GitHub Actions Artifact and Workspace Continuity

## Decision

AR-06AE-C12 replaces the A1.0 complete-plan exception with an adapter-owned producer/channel/consumer contract. Core still determines the resolved `WORKSPACE` relation; `needs` supplies ordering only. A producer uploads once per workspace channel and each admitted consumer restores the same artifact into its isolated job.

## Applicability

The bounded implementation admits a resolved `git.checkout` → `source` → `docker.build` relation when both endpoints are unconditional top-level tasks, the consumer explicitly depends on the producer, and the consumer has exactly one workspace requirement. Additional inputs, independent tasks, multiple independent producer paths and repeated consumers do not change applicability. Other leaf and control capabilities remain independently checked.

The scope rejects conditional or repeated endpoints, missing or ambiguous producers, other channels, missing ordering and multiple workspace sources. Whole-workspace downloads into `.` cannot silently merge competing producers. `VALUE` and `STATE` transport remain separate and unsupported here.

`AdapterContinuityScopedSupport.endpointScope` replaces the internal `exactPlanActions` property. The public artifact schemas and Core semantics do not change. The general registry retains unsupported workspace status; the resolver creates a plan-specific effective capability view only when every workspace relation is admitted.

## Native execution

`actions/upload-artifact@v7` follows the checkout, includes hidden files and rejects an empty workspace. `actions/download-artifact@v8` restores the identical producer/channel artifact before each build. Uploads are deduplicated across consumers; independent producers retain different artifact identities.

Image builds now explicitly select the restored local directory (`context: .` or the authored subdirectory). The earlier default Git context could fetch the workflow repository again and ignore transferred files. Custom Dockerfile paths remain relative to the workspace and must stay within the selected context. See the action's [Git and path context documentation](https://github.com/docker/build-push-action#usage).

A checkout that exports its workspace uses `persist-credentials: false`, because the archive includes hidden `.git` files. This prevents checkout authentication from being retained in the exported Git configuration.

The renderer rechecks transfer placement, inventories, identities, endpoints and ordering, including caller-supplied manifests. It cannot repair an omitted download or substitute a different artifact. Unmatched planning relations retain diagnostic blockers and cannot become executable.

## Fidelity and evidence

The bounded claim covers regular-file bytes, relative paths and hidden entries. Archived transfer does not preserve Unix mode bits; symbolic-link identity and metadata-dependent consumers remain outside this scope. It does not claim a bit-identical POSIX filesystem.

The local reconstruction tests remain useful contract tests. The C12 native workflow adds actual GitHub upload/download and Docker builds for one source, two consumers and an independent checkout. The generated source AST, canonical graph, manifest and workflow are retained alongside the exact executed envelope. An independent Python oracle checks source-derived dependencies, canonical workspace edges, manifest transfer placement and native SHA-256/size observations for text, binary and hidden files.

Two native mutants must fail: a build with its download removed, and a build whose archive omitted hidden files. Mutant jobs explicitly assert that the build failed; skipped or missing mutants do not count. A regression to default Git context defeats the missing-transfer falsification and fails the gate.

Evidence is bound to the source revision, workflow revision, run and attempt, with file receipts. This is CI-bound evidence from the checked execution envelope and GitHub job outputs. It is not yet admitted by the independent composition-coverage replay and is not a signed certification bundle. Multiple independent producer paths and alternate task counts are covered by JVM tests, not additional native certification claims.

The current GitHub executable reference is updated for local context and credential handling. Its active promotion overlay binds the new endpoint-scope identity while retaining the same checkout-build-image scenario and support class; stale task-sequence scope identities remain rejected. The historical Jenkins reference stays unchanged. No target-wide support, portability, signed portfolio promotion or AR-06 closure follows from this work.
