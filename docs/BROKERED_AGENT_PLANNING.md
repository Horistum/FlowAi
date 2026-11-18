# Brokered agent planning and observations

Horistum connects its planning tools to the existing
[`agent_worker.AppServerWorker`](https://github.com/Horistum/gitops-agent-control-plane/tree/92a664b7ce894b48ce0fdcb35283c43a5fed661e/agent_worker)
through controller-owned dynamic tools. MCP remains disabled inside this worker;
the separate MCP server remains available to other hosts. No second model transport
or semantic evaluator is introduced. Installing this standalone adapter does not
activate the reference software-delivery controller or change its role policies.

The worker can inspect local exports, form drafts, inspect compiler diagnostics and
select an exact candidate for independent review. It cannot approve, render or
execute workflows. Flow's installed compiler remains the semantic authority.

## Installation

Use Linux, Python 3.11+, JDK 25 and an installed Flow CLI with public contracts:

```sh
python3 -m venv /absolute/planning-worker-venv
/absolute/planning-worker-venv/bin/pip install -r tools/requirements-planning-worker.txt
```

Follow the pinned worker's [installation and capability check](https://github.com/Horistum/gitops-agent-control-plane/blob/92a664b7ce894b48ce0fdcb35283c43a5fed661e/docs/BROKERED-WORKER.md).
Its reviewed experimental CLI/schema pin remains mandatory. A newer CLI is not
automatically compatible. The owner supplies a dedicated authenticated home without
instruction files, plugins or tool configuration; no credentials are copied.

The private adapter configuration has exactly four fields:

| Field | Contract |
| --- | --- |
| `formatVersion` | Integer `1`. |
| `planning` | The complete unchanged [planning-tools configuration](AGENT_PLANNING_TOOLS.md), including its own `formatVersion`, fixed task, compiler and limits. |
| `worker` | `argv` (absolute executable and arguments), `home` (absolute dedicated Codex home), `model` (explicit model), and `profile` (the complete existing brokered worker profile). |
| `discovery` | Existing selector-free [v3 discovery configuration](REVIEWED_DISCOVERY.md): roots, limits and optional signed inventory provenance. |

For example, add this discovery object to the outer configuration:

```json
{
  "formatVersion": 3,
  "roots": [{"id": "inventory", "path": "/absolute/exported-inventory", "scope": "Isolated recovery resources"}],
  "maxSteps": 8,
  "maxSourceBytes": 65536,
  "maxTotalSourceBytes": 262144,
  "maxRecords": 32,
  "maxEntries": 64
}
```

Set `planning.task.availableTools` to describe `flow_observe`. Root grants enforce
file access; task prose does not create permissions. Output must remain outside
all roots. Grant only appropriate exported data, not credentials or a whole home.
No caller source-to-operation map is needed. Optional root `inventory` policies
retain the existing [signed collection contract](MANAGED_INVENTORY_PROVENANCE.md).

Before activation the owner explicitly commissions the configured model:

```sh
PYTHONPATH=/absolute/FlowAi/tools /absolute/planning-worker-venv/bin/python \
  -m agent_worker.smoke --policy /absolute/private/planning.json \
  --adapter-module authoring_host.planning_worker
```

This makes a potentially billed turn. The existing synthetic source-broker check
binds model, profile and adapter/worker source identity; it does not exercise this
adapter's discovery authorizer or prove planning quality. Changed authority requires
reviewed recommissioning. Never use a fixture attestation for deployment.

Start one bounded planning turn:

```sh
JAVA_HOME=/absolute/jdk-25 /absolute/planning-worker-venv/bin/python \
  /absolute/FlowAi/tools/agent_planning_worker.py \
  --config /absolute/private/planning.json --out /absolute/private/run-001
```

## Tools, evidence and final selection

The five existing planning tools retain their names and compiler delegation.
`flow_task` also returns granted root IDs/scopes and remaining observation calls.
`flow_observe(kind, root, path)` performs `list` or `read`; `.` lists a root. It
reuses bounded text, no-symlink, regular-file and provenance checks. Filesystem root
paths are never model-selected. No shell, network, write or arbitrary MCP is exposed.

Each checked draft retains the exact delivered observation ledger. Observed files,
directory membership, root identity and provenance are revalidated before tool
calls, after compilation and at final handoff. A change stops the run rather than
silently refreshing its evidence. This establishes local exports at check time,
not current remote truth, completeness, ownership or later runtime preconditions.

Final selection must use the **last delivered observation snapshot**. Reading a new
file or listing after a draft was checked requires another complete draft check,
even if the agent intends to keep the same proposal. `flow_observe` returns
`draftsNeedRecheck`; `flow_task` exposes the current `observationsSha256`, and
`flow_get_artifact` can retrieve a draft's `observations.json`. Re-reading unchanged
already-delivered evidence does not invalidate a draft. This prevents selection of
a plan that predates newly discovered evidence; it does not prove the model used
the evidence correctly or discovered everything relevant.

Final JSON contains only `candidateId`: an exact completely checked draft, or an
empty string if no proposal is available. The agent cannot replace the request,
proposal or host verdict through final text. The host returns
`awaiting-independent-review` only for compiler exit code 0; other checked outcomes
or no candidate return `blocked`. Goal fidelity remains `unassessed` and workflow
execution remains false. Observation bytes are not automatically promoted to
operation-parameter authority or reviewed-discovery approval.

Malformed response envelopes and contract/artifact lookup arguments are correctable within
the same turn. Process failures, integrity changes, forbidden tools and exhausted
limits stop it. Worker call/byte/frame/output/time bounds and separate discovery
and compiler limits remain enforced. A turn may contain multiple internal model
inferences; it is not a fixed provider-request count or price.

## Durable recovery and separate review

`--out` identifies one durable turn. Reusing it with unchanged configuration/code
recovers an exact completed inner receipt, verifies all retained artifacts and
observations, and restores `result.json` without another model or compiler call.
Without a completed receipt it stops, including after a dispatch crash. There is no
automatic retry: inspect private state before explicitly starting a new directory.
Keep the whole directory. Missing outer results alone do not require another turn.

The configuration envelope, bounds and compiler/catalog preparation are checked before
`intent.json` reserves a model dispatch. A preparation failure without this intent
has not reserved a model turn. Partial preparation files may still need inspection
and a new directory. Once the intent exists, missing completed receipts remain
uncertain and are never replayed automatically. Recovery also verifies the retained
`selection.json`, its frozen complete artifact inventory, exact candidate result,
compiler review, observation snapshot and
derived status; matching file hashes alone cannot validate contradictory metadata.

The result includes the selected compiler result, observation snapshot digest and
artifact manifest. Private records contain the request, inventory text and drafts;
they are not publication artifacts. Historical results are not continuously valid
authorizations. Revalidate again before later review or execution.

Use the host's independent-review mode with caller-supplied independent requirements:

```sh
JAVA_HOME=/absolute/jdk-25 /absolute/planning-worker-venv/bin/python \
  /absolute/FlowAi/tools/agent_planning_worker.py \
  --config /absolute/private/planning.json --out /absolute/private/run-001 \
  --review-requirements /absolute/private/caller-requirements.json \
  --review-out /absolute/private/review-001
```

This mode never starts a worker or substitutes another candidate. A missing,
incomplete, blocked or stale handoff stops before review compilation. The host
locks the existing run, recovers its exact selected candidate, freezes the proposal,
task, observations and independent requirements, and invokes the same canonical
`proposal --requirements` command. It revalidates the original handoff and inputs
after compilation and checks the compiler's proposal/requirements receipts. A
direct raw-compiler command does not provide these handoff checks.
The independent graph identity and canonical-plan bytes must also match the
retained selected candidate; matching review metadata alone cannot substitute
another plan.

Requirements must be outside all model-readable discovery roots and the planning
output. Review output must be a new directory outside those locations. Requirements
and review feedback never enter the worker turn. The compiler owns request matching
and fidelity semantics; the host does not reimplement them. Reviewers must consider
the retained task boundaries as well as the request. Source interpretation,
requirement completeness and remote truth still need independent review.

`review.json` binds the source receipt, selected proposal, observation snapshot,
requirements and compiler artifacts. Status `requirements-satisfied` is limited to
the supplied confirmed requirements. Other compiler results remain `blocked` with
their diagnostics. The CLI returns the canonical review exit code (0, 2, 3 or 4),
or 2 for a host failure. `naturalLanguageCompleteness` remains `unassessed` and
`workflowExecution` remains false. There is no rendering, approval or execution.
Reusing a review output is rejected; a deliberate new review uses a new directory.
Any retained review is historical evidence, not continuing execution permission.

The [worker portfolio evaluator](PLANNING_WORKER_EVALUATION.md) runs repeated cases
through this exact planning/review boundary and can recover a completed review
without invoking the compiler again. New private reviews retain
`compiler-process.json` alongside captured inputs and compiler artifacts; recovery
reconstructs the complete result and compares its typed metadata and inventory.
The process receipt binds the actual exit code and every compiler output digest.
Normal evaluation resume may publish a missing final review from this complete,
unchanged evidence without another compiler call. Summary mode never finalizes a
missing review. Incomplete compiler evidence is not overwritten or recompiled.
Earlier reviews without the full process/inventory receipt remain historical
records of their original authority checkout; the evaluator does not silently
migrate or reinterpret them.

Code changes alter the adapter authority identity. Existing receipts remain tied
to their original checkout; they are not silently migrated or rerun. New planning
runs require explicit commissioning of the changed authority.

## Verification and limits

After an independent goal review succeeds, the same entrypoint supports a
[fresh resource preflight](OPERATION_PRECONDITIONS.md#independent-preflight-of-an-agent-selected-plan).
Supply `--preflight-config` and `--preflight-out` together with the existing
`--review-requirements` and completed `--review-out`. This mode recovers the
selection and review, checks canonical effect coverage, collects signed snapshots
from a separately configured observer and invokes the compiler. It never starts
a model turn. Reuse verifies a completed receipt and current expiry; partial
outputs never trigger an automatic retry. Fidelity satisfaction alone remains
distinct from resource readiness and external execution authorization.

```sh
/absolute/planning-worker-venv/bin/python -m unittest discover -s tools/tests_worker -v
JAVA_HOME=/absolute/jdk-25 /absolute/planning-worker-venv/bin/python \
  tools/verify_planning_worker.py --cli /absolute/flow-core/bin/flow-core
```

Tests run the actual pinned worker against a clearly scripted stdio peer. The
installed proof covers infrastructure/data recovery, invalid dependencies, unsafe
retries, repairs, unknown decisions, canonical retrieval, the actual independent
review entrypoint (including compiler-valid wrong-goal plans, mismatched requests
and stale evidence), and recovery without another turn. Production code
does not load the fixture corpus. This is not live-model understanding, unfamiliar
environment generalization or actual system recovery. Live evaluation, deployed
observer commissioning and external dispatch-time enforcement remain open. S3, AI-A3, AR-06 and
AR-07 remain open; EF-09 stays paused.
