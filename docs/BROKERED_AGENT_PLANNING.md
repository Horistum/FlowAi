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

Final JSON contains only `candidateId`: an exact completely checked draft, or an
empty string if no proposal is available. The agent cannot replace the request,
proposal or host verdict through final text. The host returns
`awaiting-independent-review` only for compiler exit code 0; other checked outcomes
or no candidate return `blocked`. Goal fidelity remains `unassessed` and workflow
execution remains false. Observation bytes are not automatically promoted to
operation-parameter authority or reviewed-discovery approval.

Malformed response envelopes and public lookup arguments are correctable within
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

The result includes the selected compiler result, observation snapshot digest and
artifact manifest. Private records contain the request, inventory text and drafts;
they are not publication artifacts. Historical results are not continuously valid
authorizations. Revalidate again before later review or execution.

Use the selected candidate ID from `result.json` in an independent original-goal review:

```sh
/absolute/flow-core/bin/flow-core proposal \
  /absolute/private/run-001/planning/draft-003/proposal.json \
  --contracts /absolute/FlowAi --requirements /absolute/caller-requirements.json \
  --out /absolute/private/independent-review
```

`draft-003` is illustrative. Source interpretation and completeness still need
review. The proposing model never supplies the independent requirements.

## Verification and limits

```sh
/absolute/planning-worker-venv/bin/python -m unittest discover -s tools/tests_worker -v
JAVA_HOME=/absolute/jdk-25 /absolute/planning-worker-venv/bin/python \
  tools/verify_planning_worker.py --cli /absolute/flow-core/bin/flow-core
```

Tests run the actual pinned worker against a clearly scripted stdio peer. The
installed proof covers infrastructure/data recovery, invalid dependencies, unsafe
retries, repairs, unknown decisions, canonical retrieval, independent rejection of
compiler-valid wrong-goal plans and recovery without another turn. Production code
does not load the fixture corpus. This is not live-model understanding, unfamiliar
environment generalization or actual system recovery. Live evaluation and remote
state/ownership revalidation before execution remain open. S3, AI-A3, AR-06 and
AR-07 remain open; EF-09 stays paused.
