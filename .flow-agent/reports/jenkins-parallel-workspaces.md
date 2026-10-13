# AR-06W: bounded Jenkins parallel branch workspaces

The preceding parallel proof covered native inputs without branch work. This
slice admits exactly two manual-approval then unbound literal checkout bodies
with failFast false. Each branch writes in its own adapter-owned relative
`.flow-parallel/<branch-id>` directory. The branch ID preserves identity even
when authored branch labels are equal. Gate-only shapes and their two failure
policies remain unchanged; Core contracts and generic support remain unchanged.

## Owned boundary

Admission rejects mixed bodies, result bindings, nonlocal dependencies, workspace
export/continuity edges, nonliteral checkout parameters, shallow checkout and
immutable-revision checkout. The renderer independently rejects malformed body
shapes, unsafe directory identities, injected work and unsupported failure policy.
The directory primitive is native Jenkins `dir`; no shell projection or pipeline
observation probe is emitted.

This is directory separation for the declared checkout effects, not a security
boundary or a promise to clean or transfer workspaces. Existing filesystem state,
malicious repository content and cross-build resource isolation are outside the
claim. Native behavior is described at
<https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#dir-change-current-directory>;
the pinned runtime must independently demonstrate it.

## Independent observations and falsification

The observer first sees both native inputs pending. After deciding the left gate,
it waits for the native parallel branch's BlockEndNode, then observes the still
pending right gate and completed branch work. Directory contents are read from
fixed fixture locations, independently of rendered directory arguments. Native
checkout nodes retain their branch identity and enclosing directory arguments.
After the second decision the observer verifies both directories, join,
continuation and the original rejection origin.

The approval scenario falsifies aliased directories, swapped directory ownership,
omitted left checkout, checkout before approval and premature continuation. The
rejection scenario proves that rejected branch work stays absent while the
approved sibling runs; swallowed rejection, premature branch work and premature
continuation fail certification. Equal final build status or root marker alone
cannot establish success. The generated Jenkinsfile remains the exact executed
artifact. Two new assessments are reauthenticated in the existing producer and
portfolio, with the ARTIFACTS_WORKSPACES category explicitly bounded to these
branch directories.

## Validation boundary

Before release-state selection, local Flow Agent validation, context generation,
162 Python tests and whitespace checks passed. The pinned Kotlin 2.4.10 plugin is
absent from the offline cache; local Kotlin compilation cannot provide a receipt.
Fresh exact-head and synthetic-merge compilation, full test inventories, ordered
conformance checks, all runtime producers and portfolio verification are required.
Final external receipts belong in the PR without creating an untested follow-up
commit.

The immutable predecessor baseline records PR #219's 2125 tests / 368 suites and
274 ordered checks per revision, 25 assessments, 78 signatures and 18 verified
archive digests. Actual main has the same tree. Its runtime workflow had passed
and Flow CI was still running at baseline observation; these observations remain
historical facts, not current-candidate receipts.

## Limits

No failFast-true checkout bodies, arbitrary or nested branch work, exported
outputs, artifact transfer, state continuity, security isolation, fresh workspace,
simultaneous CPU/Git execution, real human authorization or portable execution is
claimed. Public versions and generic registry support remain unchanged. Only
AR-06A/B are formally accepted; AR-06 remains active, AR-07 planned, EF-09 paused
and F-20 AR-07-owned.
