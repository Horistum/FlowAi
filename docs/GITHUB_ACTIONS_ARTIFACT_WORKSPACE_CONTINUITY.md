# GitHub Actions Artifact and Workspace Continuity

## Decision

AR-06AE-C12 replaces the A1.0 complete-plan exception with an adapter-owned producer/channel/consumer contract. Core still determines the resolved `WORKSPACE` relation; `needs` supplies ordering only. A producer uploads once per workspace channel and each admitted consumer restores the same artifact into its isolated job.

## Applicability

The bounded implementation admits a resolved `git.checkout` → `source` → `docker.build` relation when both endpoints are top-level or nested only under supported Boolean conditions, the consumer retains every enclosing producer branch and explicitly depends on the producer, and the consumer has exactly one workspace requirement. The producer's structural branch path must be a prefix of the consumer's path. Additional inputs, independent tasks, multiple independent producer paths and repeated consumers do not change applicability. Other leaf and control capabilities remain independently checked.

The scope rejects producers or consumers inside loops, retries, parallel groups or recovery, branch escape, sibling branches, missing or ambiguous producers, other channels, missing ordering and multiple workspace sources. Separately authored branches do not establish availability merely because their predicates have the same text. Whole-workspace downloads into `.` cannot silently merge competing producers. `VALUE` and `STATE` transport remain separate and unsupported here.

`AdapterContinuityScopedSupport.endpointScope` replaces the internal `exactPlanActions` property. The public artifact schemas and Core semantics do not change. The general registry retains unsupported workspace status; the resolver creates a plan-specific effective capability view only when every workspace relation is admitted.

AR-06AE-C13 composed unconditional producers with the existing Boolean condition contract. AR-06AE-C15 extends that contract to condition-scoped producers. Checkout and upload share the producer job's guard; download and build share the consumer job's guard. A skipped producer cannot supply an executing consumer. The internal scope identity is `CONDITION_SCOPED_SINGLE_SOURCE_WORKSPACE`; the active reference overlay uses that identity without changing its original unconditional reference scenario or snapshot bytes.

## Native execution

`actions/upload-artifact@v7` follows the checkout, includes hidden files and rejects an empty workspace. `actions/download-artifact@v8` restores the identical producer/channel artifact before each build. Uploads are deduplicated across consumers; independent producers retain different artifact identities.

Image builds now explicitly select the restored local directory (`context: .` or the authored subdirectory). The earlier default Git context could fetch the workflow repository again and ignore transferred files. Custom Dockerfile paths remain relative to the workspace and must stay within the selected context. See the action's [Git and path context documentation](https://github.com/docker/build-push-action#usage).

A checkout that exports its workspace uses `persist-credentials: false`, because the archive includes hidden `.git` files. This prevents checkout authentication from being retained in the exported Git configuration.

The renderer rechecks transfer placement, inventories, identities, endpoints, ordering, both endpoints' job/action guard consistency and Boolean input availability, including caller-supplied manifests. Every producer guard conjunct must occur structurally in the consumer's guard. Comparison preserves expression grouping and ignores source locations; it does not infer arbitrary logical implication. It cannot repair an omitted download or substitute a different artifact. Unmatched planning relations retain diagnostic blockers and cannot become executable.

## Fidelity and evidence

The bounded claim covers regular-file bytes, relative paths and hidden entries. Archived transfer does not preserve Unix mode bits; symbolic-link identity and metadata-dependent consumers remain outside this scope. It does not claim a bit-identical POSIX filesystem.

The local reconstruction tests remain useful contract tests. The C12 native workflow adds actual GitHub upload/download and Docker builds for one source, two consumers and an independent checkout. The generated source AST, canonical graph, manifest and workflow are retained alongside the exact executed envelope. An independent Python oracle checks source-derived dependencies, canonical workspace edges, manifest transfer placement and native SHA-256/size observations for text, binary and hidden files.

Two native transfer mutants must fail: a build with its download removed, and a build whose archive omitted hidden files. Mutant jobs explicitly assert that the build failed; skipped or missing mutants do not count. A regression to default Git context defeats the missing-transfer falsification and fails the gate.

C13 retains those observations and adds nested then/otherwise consumers: one selected build and two skipped builds. The independent oracle checks canonical branch ownership and literal predicates against the parsed AST, preserves manifest guard provenance, and derives native selection from the source. A third native mutant removes an unselected job's guard: it must actually restore and build, and the source-derived verifier must reject that extra execution. JVM tests additionally cover Boolean input predicates and reject event contexts that cannot supply those inputs. Native evidence here covers literal predicates, not arbitrary input combinations or producer-failure experiments.

AR-06AE-C14 admits these observations into the independent [composition-coverage replay](validation/composition-coverage.md). The reusable workflow pins its archive through a successful same-attempt collector receipt. The consumer regenerates all five compiler inputs, derives the expected file inventory from the current checkout and independently replays branch selection, bytes and the three mutants. It rechecks preparation, baseline and mutant identities, outcomes, observation steps and dependency timing against the same-attempt GitHub API. A rewritten proof or file manifest cannot substitute for this replay.

Evidence remains CI-bound and separate from signed certification. The bounded observation appears only on GitHub's `ARTIFACTS_WORKSPACES` row; signed portfolio bytes and general `VALUE_STATE_CONTINUITY` gaps remain unchanged. Native job identities describe the hosted runtime without inventing a container-image digest. Alternate task counts retain JVM coverage without additional native claims.

C15 extends the same experiment with a selected conditional producer, two selected consumers, one unselected nested consumer and a completely skipped producer/consumer branch. The original seven jobs and three mutants remain intact. A fourth mutant removes only the skipped producer's job guard: checkout, upload and byte observation must actually run, and source-derived replay must reject the extra production. The proof now authenticates thirteen baseline jobs, four mutants and preparation. Three producer artifacts remain distinct, including the skipped producer's artifact exercised only by its mutant. Same-run receipts, compiler regeneration and independent coverage replay remain mandatory.

Native producer/consumer conditions are literal Boolean predicates. Input-driven predicates and alternate task counts have JVM coverage. Producer-failure experiments, repeated-control transport, general VALUE/STATE continuity and filesystem metadata remain separate gaps.

C12 updated the current GitHub executable reference for local context and credential handling; C13 preserves those bytes. Its active promotion overlay binds the new endpoint-scope identity while retaining the same checkout-build-image scenario and support class; stale task-sequence scope identities remain rejected. The historical Jenkins reference stays unchanged. No target-wide support, portability, signed portfolio promotion or AR-06 closure follows from this work.
