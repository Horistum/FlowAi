# Evidence-driven authoring investigation

This document describes pinned-file investigation version 1. For root-scoped exploration,
exact JSON fact extraction and dependent invalidation, see [System discovery](SYSTEM_DISCOVERY.md).

The external Python authoring host can now investigate operator-selected local text
sources before proposing an Intent. Enable this explicitly with `investigation` in
the existing host configuration. The provider remains a data-only command process;
the host implements the bounded reads. Neither the compiler nor target adapters
gain model access or workflow execution.

This is a generic read/record/propose loop. It contains no deployment, backup,
certificate, Jenkins or Kubernetes scenario dispatch. The existing compiler still
owns semantic acceptance, independent request fidelity and target materialization.
Evidence never establishes a missing implementation binding or certifies a target.

## Configuration and compatibility

Configurations without `investigation` retain the existing response format and
`bounded-authoring-host/1` receipts. Opted-in runs retain host version 2 and protocol
`authoring-investigation/1`. Both remain resumable through `author_proposal.py resume`.
The optional configuration below uses schema version 1; replace the example path and
digest with a deliberately selected, nonsecret source. `sha256sum` can compute the
digest locally. Selecting a file authorizes sending its complete text to the provider.

```json
{
  "investigation": {
    "formatVersion": 1,
    "sources": [
      {
        "id": "deployment-config",
        "path": "/absolute/checkout/config/deployment.json",
        "sha256": "REPLACE_WITH_64_LOWERCASE_HEX_CHARACTERS",
        "scope": "Selected checkout configuration for the staging application"
      }
    ],
    "maxSteps": 4,
    "maxSourceBytes": 16384,
    "maxTotalSourceBytes": 65536,
    "maxRecords": 64
  }
}
```

Merge this member into the [existing host configuration](BOUNDED_AUTHORING_HOST.md).
Run the same command; no new CLI flags are required:

```bash
python3 tools/author_proposal.py run \
  --config /private/authoring-config.json \
  --request /private/request.json \
  --requirements /private/reviewed-requirements.json \
  --out /private/new-investigation
```

Every investigation turn consumes `budget.maxCalls`, tokens and monetary reservations
just like a proposal. The default three calls may suffice for read/record/propose;
choose larger explicit limits when more exploration or clarification is required.
There are no hidden retries or unbudgeted model calls. `maxSteps` limits investigation
actions independently of proposal repairs. Limits are at most 16 investigation
steps, 32 sources, 64 KiB per source, 256 KiB aggregate source bytes and 128 ledger
records. Each retained ledger is capped at 1 MB. Existing prompt-byte, process-output,
time, token and cost bounds still apply; large ledgers can exhaust a prompt allowance.

All selected files must be regular UTF-8 files with absolute paths and exact SHA-256
digests. Symlinks in any path component, special files and oversized inputs are
rejected. No recursive traversal, shell, network access, arbitrary path selection or
target mutation is exposed to the model. Paths stay in private operator configuration;
the model sees source IDs, hashes and operator-declared scope descriptions.

## Provider result contract

The outer `command-reasoning/v2` envelope and usage accounting are unchanged. With
investigation enabled, `result` is one of the following exact objects. The host sends
its full schema as `output_schema`, and the complete envelope as `response_schema`.

To read sources or add findings/unknown revisions:

```json
{
  "kind": "investigate",
  "readSources": ["deployment-config"],
  "findings": [],
  "unknowns": []
}
```

The next request's `input.investigation` contains sources, observations, findings and
the append-only unknown history. An observation has a host-derived ID of
`source:<source-id>@<sha256>`, complete text, byte digest, source ID, scope, observation
time and creating attempt ID. `host-observed-bytes` describes exactly the bytes read. The scope label
is operator-declared: it does not attest a Git commit, repository identity, cluster
state, publication date or remote authenticity.

Findings contain exactly `id`, `statement`, and `evidenceRefs`. References must select
observations already delivered in an earlier turn or earlier immutable findings.
Forward references and cycles are rejected. The host appends `model-inferred` and
the attempt ID. Reusing a finding ID cannot change its statement or dependencies.
Citations are structural provenance, not proof that the claim follows from its source.

Unknowns contain exactly `id`, `question`, `category`, `blocking`, `status`, and
`evidenceRefs`. IDs use lowercase letters, digits and hyphens, beginning with a letter,
and are at most 64 characters. Questions/statements/scopes are at most 1000 characters.

| Category | Meaning | How it can be addressed |
| --- | --- | --- |
| `fact` | A technical fact is missing | Model supplies supporting evidence references; the conclusion remains inferred |
| `decision` | The user must choose | An explicit answer through the existing resume operation |
| `capability` | Required implementation support is not established | Additional evidence can support a model conclusion; compiler/target checks still decide acceptance |
| `conflict` | Available sources disagree | Further evidence and a retained model conclusion; prior records remain visible |

`status` is `open` or `addressed`. Addressed technical unknowns require nonempty
references and remain `model-inferred`. They are never marked verified. Revisions
cannot change the original question/category/blocking flag or remove evidence.
Omitting an unknown from a subsequent result does not remove it. Reopening a question
appends a revision. Nonblocking open questions remain visible even on accepted runs.

To submit a proposal, return `kind: "propose"`, `response` containing the complete
existing `normalizedIntent` and `report`, plus `findings` and `unknowns` arrays.
Only `response` crosses the existing proposal compiler boundary. Ledger fields never
become canonical graph authority or replace compiler evidence. Unsupported fields
such as model-authored `verified` or execution commands are rejected.

## Decisions, stops and source validity

Blocking decision unknowns publish `awaiting-user` with `investigation:<id>` question
IDs. Resume requires answers to every pending question and independently confirmed
requirements. Existing nonclarification requirements must remain unchanged. Answers
are retained and linked from the next ledger revision; reviewer identity remains
caller-declared, not authenticated operational approval. The provider must then
produce a new complete proposal, which passes all compiler checks.

Other blocking unknowns stop proposals as `investigation-blocked` before compilation
or rendering. Collect more evidence within investigation turns before proposing.
Repeated reads reuse their observation; an action adding nothing stops as
`investigation-no-progress`. Hitting the independent step limit stops as
`investigation-limit`. The existing call/cost budgets remain authoritative even if
more investigation steps would otherwise be available.

Sources are checked before a run, before every dispatch, after each provider call,
on resume and after compilation. A changed digest stops as `source-changed` and a
new run is required. This conservatively invalidates the investigation as a whole;
all earlier observations remain historical evidence. A source removed or replaced
with an unsafe file fails closed. An interruption never causes automatic replay.

These checks describe selected local snapshots. They do not prove current remote
state or prevent a file changing after the final check. A source drift detected
after compilation invalidates the run even if diagnostic/compiler artifacts exist.
External execution must establish its own current preconditions. No automatic live
system freshness, selective recomputation or arbitrary MCP discovery is claimed.

Sources and model text are untrusted data, never instructions or authorization.
The host blocks configured credentials found in selected source content, but this
is not general secret detection. Select only files appropriate to disclose. The
provider executable remains trusted infrastructure, not an OS-sandboxed adversary.

## Evidence and verification

Each successful update writes a new `attempt-NNN/investigation.json`. Initial state
and answered-decision revisions have separate files. `state.json` identifies the
latest revision and tracks every file digest. The exact provider request/response
and compiler records remain retained. Raw byte observations, model inferences and
caller answers have distinct attribution. Hashes detect changes; they do not prove
truth or provider authorship.

`tools/verify_authoring_investigation.py --cli PATH` exercises deployment and backup
inspection through the same source-selection and observation-driven proposal path.
For each, it checks accepted matching data, rejected conflicting data and a rejected
prohibited operation. It uses the real installed compiler and a deterministic test
peer, not a live model. The existing installed-host verifier runs this proof in both
exact-head and merge-candidate CI jobs.

The frozen AI-A3 evaluation rejects investigation-enabled configurations because its
source-exposure policy has not been independently designed. Its oracle and held-out
inputs remain unchanged. Live investigation quality, comprehensive requirement
discovery and AI-A3's executable held-out render remain open acceptance work.
