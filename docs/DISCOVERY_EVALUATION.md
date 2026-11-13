# Task-driven discovery evaluation

`evaluate-discovery` runs the existing external authoring agent against a caller-selected
suite of tasks and complete bounded environment exports. It supports investigation v3
and the separate plan review introduced by PR #270. The older `evaluate` command retains
its frozen twelve-case corpus and does not accept discovery configurations.

This is AI-A3-S3.3 evaluation infrastructure. The included portfolio is public development
material, not held-out evidence. Scripted integration tests do not establish live model
quality, infrastructure execution, S3 completion or target support.

## Inputs and exposure

The suite format is version 1. See
[`conformance/discovery-evaluation/suite.json`](../conformance/discovery-evaluation/suite.json)
for a complete six-task portfolio with three different source structures:

| Environment | Change task | Inspection task |
| --- | --- | --- |
| Infrastructure | Provision the allocated test worker; bounded readiness retry, notification and failure propagation | Inspect readiness without provisioning or deletion |
| Data | Capture a baseline, migrate the isolated database, restore that baseline on failure and verify recovery | Inspect the isolated database without backup, migration or restore |
| Credentials | Rotate the laboratory service credential once, retry consumer verification, diagnose and notify on failure | Inspect consumers without rotating credentials |

Each pair receives the same complete exposure, including out-of-scope production
records. The loader rejects different snapshots under one environment identifier.
The manifest selects directories, not source filenames, expected extraction paths,
resource-to-operation mappings or proposed plans. The agent chooses its own reads,
facts, interpretation and plan through the existing bounded observation protocol.

Each case specifies:

- `id`, `domain`, `environment`: lowercase investigation identifiers, unique case IDs.
- `exposure`: `public-development` or `caller-declared-held-out`.
- `request`: a relative JSON file containing the original `userText`, optional `context`
  and `mode`.
- `requirements`: a relative, independently authored and confirmed requirements-v5
  file with the same original request and nonempty reviewer attribution.
- `roots`: one to eight `{id, directory, scope}` entries, relative to the suite file.

The suite also requires `id`, `description` and `discoveryLimits` with `maxSteps`,
`maxSourceBytes`, `maxTotalSourceBytes`, `maxRecords` and `maxEntries` from the existing
discovery contract. Unknown manifest fields are rejected. Request text is bounded to
1,000 characters. There are at most 32 cases, 96 scheduled runs, ten repetitions,
128 files and 128 directories per root, 1,024 entries and 8 MB across the suite's
case snapshots. Files must be UTF-8, at most 64 KiB each and 2 MB per root. Links and
special files are rejected. Empty files and directories are preserved.

The loader copies whole roots, verifies membership and bytes, and keeps requests,
requirements, configuration and reviews outside those roots. No root may contain
the manifest or any case's private request/requirements file. Every case gets its
own immutable exposure copy; later changes to the original export do not change
the captured trial. This is a local snapshot, not an atomic or authenticated
observation of a cloud account. Signed collector provenance remains a separate
single-session capability; this suite format does not claim or preserve collector
authentication across its copied roots.

Independent requirements are retained privately and passed only to the compiler.
They are partial, explicitly authored checks, not a complete semantic translation
of the user's intent. A reviewer must still detect missing steps, unrelated changes,
wrong scope, inappropriate questions and inadequate discovery. This runner neither
authors those expectations from the candidate nor implements another semantic judge.

## Provider and budget

Use the existing [host configuration](BOUNDED_AUTHORING_HOST.md), with no
`investigation` field: the suite owns exposure and supplies version 3. Explicitly
choose the provider executable, model, version, credential environment names and
per-run limits. A compatible command/v2 bridge is already available as
`agent-reasoning-openai` in
[`gitops-agent-control-plane`](https://github.com/Horistum/gitops-agent-control-plane).
After installing a reviewed checkout of that project, its provider block can use:

```json
{
  "id": "openai-chat-completions",
  "model": "OPERATOR_SELECTED_MODEL",
  "version": "OPERATOR_PINNED_BRIDGE_REVISION",
  "argv": ["/absolute/venv/bin/agent-reasoning-openai", "--timeout", "110", "--max-output-tokens", "8192"],
  "credentialEnv": ["OPENAI_API_KEY"],
  "evidenceKind": "live"
}
```

This is an explicit API integration, not implicit reuse of a ChatGPT/Codex login.
Supply the key through the process environment, never a suite or committed file.
Set the host timeout above the bridge timeout, and align its output-token cap with
the bridge. Choose verified model prices in `inputMicrousdPerMillionTokens` and
`outputMicrousdPerMillionTokens`; recorded costs are estimates from those configured
rates and adapter-reported usage, not billing receipts. Other compatible providers
use the same command boundary; no provider SDK is added to Flow.

Before the first call, the runner checks required credential presence and reserves
`case count × repetitions × per-run maxCostMicrousd` against the explicit suite cap.
It freezes the compiler fingerprint, catalog, public contracts, instructions and
per-case configuration. Every dispatch checks those settings. A provider integration
failure stops subsequent runs. No interrupted capture is automatically retried,
resumed or refunded. The provider executable is trusted local infrastructure, not
an OS-sandboxed adversary; it must not expose private evaluator files to the model.

## Capture, review and report

For six cases and three repetitions, the suite cap must cover eighteen per-run
allowances. For example, if the operator chooses a 2,000,000-microUSD per-run ceiling:

```sh
python3 tools/author_proposal.py evaluate-discovery \
  --config /absolute/live-provider.json \
  --suite conformance/discovery-evaluation/suite.json \
  --out /absolute/new-evaluation \
  --repetitions 3 --max-cost-microusd 36000000
```

That 36 USD reservation is an upper bound chosen by the operator, not an expected
charge or model price. A one-repetition smoke run is allowed, but cannot satisfy
the minimum three-repetition acceptance gate. Optional `--target NAME --render`
requests target assessment after explicit candidate approval; it never executes
the workflow or promotes a review-only target.

Capture stops each candidate at `awaiting-discovery-review`. The report supplies
its private packet path, packet hash, run identity and captured state hash. Review
the packet, original request, private requirements and observations, then prepare a
separate JSON file. A one-run review looks like this (replace every placeholder):

```json
{
  "formatVersion": 1,
  "evaluationId": "CAPTURED_EVALUATION_ID",
  "reviewerId": "SEPARATE_REVIEWER",
  "runs": [{
    "run": "repeat-1-infrastructure-change",
    "stateSha256": "CAPTURED_RUN_STATE_SHA256",
    "assessment": {
      "goal": "fulfilled",
      "constraints": "respected",
      "discovery": "sufficient",
      "rationale": "Explain why this candidate and its evidence satisfy this task, including omissions checked."
    },
    "decision": {
      "formatVersion": 1,
      "runId": "CAPTURED_HOST_RUN_ID",
      "packetSha256": "CAPTURED_PACKET_SHA256",
      "reviewerId": "SEPARATE_REVIEWER",
      "decision": "accept",
      "rationale": "Approve this exact interpretation and complete plan for compiler checking."
    }
  }]
}
```

```sh
python3 tools/author_proposal.py review-discovery-evaluation \
  --evaluation /absolute/new-evaluation --reviews /absolute/separate-review.json
python3 tools/author_proposal.py summarize-discovery-evaluation \
  --evaluation /absolute/new-evaluation
```

Review invokes the existing packet-bound discovery review and compiler; it makes
no model calls and cannot repair a rejected plan. Reviewer identity is declared by
the caller, not authenticated. The assessment concerns the captured candidate; it
cannot claim a workflow has run. `goal` is `fulfilled`, `appropriate-stop`, `failed`
or `unassessed`; `constraints` is `respected`, `violated` or `unassessed`; `discovery`
is `sufficient`, `insufficient` or `unassessed`. Every assessment requires rationale.

For a clarification, missing fact, conflict or capability stop, omit `decision`
and assess the stop itself. Do not supply invented answers or change requirements
inside a repeated trial. A changed task or explicit clarification starts a separately
identified evaluation. Review files can cover subsets of captured runs; reusing a
reviewed state or substituting a packet is rejected. Batch reviews commit each run
separately. If a process is interrupted during a review, prior reviews remain
recorded and the uncertain run cannot be replayed. Summarization remains available;
re-running a trial requires a new directory and a newly reserved budget.

All three commands return exit 3 for a valid descriptive report, including reports
with failed or incomplete trials; inspect the report's counts and statuses. Exit 2
indicates rejected input or an unusable capture. A report is written to
`discovery-evaluation-report.json` and can be reconstructed from retained evidence.

## Reading the results

The denominator includes every scheduled run, including unstarted and interrupted
runs. Compiler acceptance is separate from caller-assessed success. The latter
requires respected constraints, sufficient discovery and either an accepted plan
assessed as fulfilled or a blocked outcome assessed as an appropriate stop.
`acceptedConstraintViolations` exposes disagreements where compiler checks passed
but the reviewer found a hard constraint violation. Such a disagreement blocks
acceptance of the overall model evaluation.

Per-run evidence includes read selectors and hashes, open unknowns, facts,
investigation steps, canonical graph digest, target authorization, provider and
compiler latency, call reservations, adapter-reported calls and reported usage/cost.
Unreported dispatches are separate from reported calls; interrupted runs retain the
whole suite reservation and never acquire a success label. No usage is inferred
from a missing response. `reportedLiveCalls` requires configured `live` evidence and
an adapter identity in the captured response; this remains attribution, not an
independent attestation of a model call.

Per-environment comparisons show distinct requests, read sets and accepted graphs.
These are descriptive: reading more files or producing more graph hashes does not
prove task relevance. For the same environment, a reviewer must establish that
changed goals caused appropriate exploration and a materially correct plan change.

The next acceptance exercise needs an explicitly configured live provider, at
least three complete repetitions, independent quality reviews in every domain,
separately supplied unfamiliar environments, and the existing executable held-out
render gate. `caller-declared-held-out` records the operator's claim; this tool
cannot attest that a model never saw those sources. Public examples must not be
relabelled as unseen evidence. `generalizationEstablished`, `workflowWorkExecuted`
and `milestoneComplete` remain false. S3, AI-A3, AR-06 and AR-07 remain open.
