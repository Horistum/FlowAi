# Bounded external authoring host

The opt-in [evidence-driven investigation](AUTHORING_INVESTIGATION.md) extends this
host with bounded, host-mediated reads of explicitly selected pinned files. Its
versioned result envelope and ledger are separate from the default data-only
proposal format below. Provider executables still receive no tool execution authority.

`python3 tools/author_proposal.py` connects one operator-configured, data-only
provider to the installed `flow-core proposal` command. It is a standalone POSIX
Python host, not a compiler dependency, workflow executor or deployment service.
It uses only the standard library. AI-A3 remains open until live evaluation and
held-out rendering evidence meet the development proposal's acceptance criteria.

New runs use prompt version `bounded-authoring/2`, which covers infrastructure,
identity, DevOps setup, data, recovery, maintenance and retirement alongside delivery.
Application/repository defaults are optional. Requirements versions 1 and 2 are
accepted and retained outside the provider prompt; their full authority remains in
the product reader. Existing captured runs retain their captured instructions on
resume. See the [version 2 migration](migrations/request-requirements-v2.md) and
[selected development direction](SYSTEM_AUTOMATION_DIRECTION.md).

## Integration boundary

Reuse the existing [command-reasoning/v2 protocol](https://github.com/Horistum/gitops-agent-control-plane/blob/main/docs/ADAPTERS.md)
from `gitops-agent-control-plane`. That host's provider adapters already own
authentication and one-shot transport. Its software-delivery controller also owns
Git, tests and release effects; those authorities are not imported here. This host
does not import controller state or the worker lifecycle. In particular,
there is no PostgreSQL dependency or new provider SDK in Flow.

The adapter receives one JSON document on stdin and returns exactly one on stdout:

```json
{"schema":"command-reasoning/v2","result":{"normalizedIntent":{},"report":{}},"usage":{"input_tokens":100,"output_tokens":200},"provider":{"id":"configured-provider","model":"configured-model","request_id":"provider-request-id"}}
```

The result example illustrates the transport; empty Intent/report objects are not
valid proposals. The host supplies the complete response schema in `output_schema`,
the configured model, a stable dispatch `effect_id`, original request, public module
contracts and the installed compiler's capability catalog. The host assembles the
envelope's original `request`, `providerId` and `formatVersion` itself. The model
cannot replace these fields or submit its own requirement document.

An adapter is trusted operator infrastructure. It must make one bounded call, obey
the selected input/output token ceilings, avoid internal retries and never expose
model tools, file edits, shell execution or delivery actions. An empty cwd and
restricted environment reduce accidental access; they are **not an OS sandbox**
for a malicious adapter. Install and review the adapter separately. Provider/model
identity is configured and adapter-reported, not cryptographically attested.

## Configuration and one run

Build the product with `./gradlew :flow-cli:installDist`. Use a matching checkout
for public schema/module contracts and an explicit compiler commit. Copy the
following configuration outside the repository and replace the absolute paths,
provider identity, model and adapter version. The rates below are **illustrative**,
not current provider prices. Supply the actual chosen rates and a conscious budget.

```json
{
  "formatVersion": 1,
  "provider": {
    "id": "openai-chat-completions",
    "model": "OPERATOR_SELECTED_MODEL",
    "version": "OPERATOR_RECORDED_ADAPTER_AND_MODEL_VERSION",
    "argv": ["/absolute/bin/agent-reasoning-openai", "--max-output-tokens", "8192"],
    "credentialEnv": ["OPENAI_API_KEY"],
    "evidenceKind": "live"
  },
  "compiler": {
    "argv": ["/absolute/flow-core/bin/flow-core"],
    "commit": "81180222b0cf14867f468e8ff3dfffb0ca75aaa7",
    "contracts": "/absolute/matching/FlowAi"
  },
  "budget": {
    "maxCalls": 3,
    "maxRepairs": 2,
    "maxInputTokensPerCall": 150000,
    "maxOutputTokensPerCall": 8192,
    "maxTotalTokens": 474576,
    "maxCostMicrousd": 1000000,
    "inputMicrousdPerMillionTokens": 1000000,
    "outputMicrousdPerMillionTokens": 4000000,
    "timeoutSeconds": 120
  }
}
```

Only explicitly named credential environment variables reach the adapter. Keep
keys out of configuration, argv, requests and captures. The provider process gets
an empty temporary HOME/cwd, a minimal PATH and no ambient account configuration.
This command transport does not silently reuse a Codex subscription or login. A
separate audited command/v2 bridge would be needed for that transport. The compiler
receives `JAVA_HOME` when set, but no provider credentials or Java option variables.

```bash
python3 tools/author_proposal.py run \
  --config /private/authoring-config.json \
  --request /private/request.json \
  --requirements /private/reviewed-requirements.json \
  --out /private/new-run
```

`request.json` is the `request` object defined by the proposal schema.
`--requirements` is mandatory and bound to that exact original request; the full
requirements schema and semantic checks remain the installed compiler's authority.
Use `--target TARGET --render` only when target output is wanted. The output parent
must exist; the run directory must not exist. Exit codes are 0 for accepted, 3 for
required user review, 4 for a stopped/blocked run and 2 for a host/configuration error.

## Budgets, attempts and interruption

Defaults are one initial dispatch and up to two repairs, within three total calls.
Clarification continuations also consume this same call/token/cost budget. Before
starting each provider process the host durably reserves its entire declared
input/output allowance and the corresponding cost. Reservations are never refunded,
including unknown outcomes. Prices use integer micro-USD per million tokens;
cached-input discounts are intentionally not assumed. Reported usage and estimated
usage cost remain separate from reservations. Missing usage is unknown, not zero.

These are conservative **dispatch reservations**, not a provider billing guarantee.
The adapter must enforce output limits and the operator must select valid token/rate
ceilings, including provider framing and any billable reasoning tokens. The host
also bounds request bytes by the input reservation, caps combined process output at
2 MB and enforces a timeout. A reported token overrun terminates the run. A provider
could bill an uncertain or misbehaving call; the host never claims otherwise.

There are no automatic transport retries or model fallbacks. Transport failure,
timeout, malformed protocol, identity mismatch and interruption stop the run. Each
dispatched attempt retains its identity and full reservation. An interrupted
`dispatched` state cannot resume; reconcile provider evidence before authorizing a
new run with a new budget. This avoids ambiguous replay and duplicate billing.

Automatic repair receives compiler diagnostics and the previous complete response,
never oracle requirements or their expected values. Fidelity violations terminate
the run instead of teaching a held-out answer to the candidate. Representability
blockers, unchanged repeated proposals, exhausted budgets and absent user decisions
also stop. Target materialization failure is `target-blocked`, not a request to
guess a user decision. Compiler error remains distinct from successful acceptance.

Compiler input diagnostics under `$.request` stop with `caller-input-error`.
The original request and selected target are frozen by the caller, so replacing the
model response cannot resolve an unknown/conflicting target or invalid request field.
The first attempted call remains charged against the reservation; no repair call is
dispatched, and corpus evaluation stops. Correct the caller input and explicitly start
a new run. Candidate response input errors remain eligible for bounded repair.

## Explicit clarification

An `awaiting-user` state publishes pending decision IDs and questions in `state.json`.
No background call occurs. Supply an answer for every pending ID plus independently
reviewed requirements that retain the original request, remove resolved clarification
checks and declare `CONFIRMED`. Preserve existing hard constraints when filling
missing values. Reviewer identity is caller-declared, not authenticated approval.

```json
{"runId":"RUN_ID_FROM_STATE","reviewerId":"operator","answers":[{"id":"question-0","answer":"The exact approved value is ..."}]}
```

```bash
python3 tools/author_proposal.py resume \
  --run /private/new-run --answers /private/answers.json \
  --requirements /private/confirmed-requirements.json
```

Resume verifies retained input/attempt digests, original request binding, run ID,
complete answers and reviewer attribution. It records a new immutable requirements
revision and answer file. Only the answers reach the provider; expectations remain
outside its write/prompt authority. Existing configuration, provider and budget
cannot be replaced during continuation. A process lock prevents concurrent resume.

## Evidence and confidentiality

`state.json` indexes retained bytes by SHA-256 and records prompt/schema identities,
caller-declared compiler commit, local launcher/jar digest, configured provider/model
version, attempts, usage, reserved cost, repair/clarification counts and final status.
Each attempt retains its exact command-protocol request/response, complete assembled
proposal and compiler artifact directory. Compiler reports retain stage outcomes,
graph digest, requirements-source identity and materialization disposition.
Upstream HTTP envelopes are owned by the adapter and are not retained by this host.

These are local integrity receipts, not signatures, authenticated human decisions,
proof of provider authorship or a reproducible build attestation. Custom compiler
wrappers can have dependencies beyond the executable/argv files and adjacent jars
that are fingerprinted. Pin and review that infrastructure separately.

New directories/files are private. Raw stderr is discarded. If a configured
credential is detected in a provider response (including JSON escapes), the whole
response is replaced by an explicitly redacted record, hashed over those retained
bytes, and the run stops. Inputs containing a configured credential are rejected
before capture. This is not general secret detection; review original requests and
all other sensitive content before publishing captures. Never publish raw credentials.

## Frozen corpus evaluation

```bash
python3 tools/author_proposal.py evaluate \
  --config /private/authoring-config.json \
  --corpus conformance/authoring/corpus.json \
  --target jenkins --repetitions 3 --max-cost-microusd 36000000 \
  --out /private/new-evaluation
python3 tools/author_proposal.py summarize --evaluation /private/new-evaluation
```

The suite cap reserves every run's configured allowance before dispatch; the sample
36 USD cap follows 36 runs with the sample 1 USD per-run allowance, not an expected
actual charge. A broken provider/compiler integration stops the remaining suite.
The runner pins the frozen twelve-case inventory and expectation digests, never
opens expected proposal fixtures, and sends only original requests/public contracts
to the provider. It renders only fully specified held-out cases using the explicitly
selected target. The configuration, public contracts, prompt and compiler digest
are pinned across repetitions; a change stops evaluation before the next provider dispatch.

The report separates first-pass canonical/fidelity acceptance, acceptance after
repair, fidelity violations, unsupported detection, per-domain acceptance, rendered
evidence, clarification counts, latency, reservations and reported usage/cost.
Target failure does not erase a faithful canonical plan. Unsupported classification
and clarification quality require independent review; aggregate compiler labels
cannot establish that the model identified the actual missing capability or asked
the right questions. Evaluation returns 3 and `milestoneComplete: false` until a
separate reviewed acceptance record is admitted.

Review captured responses against the original request and the frozen expectations.
For each unsupported case identify the model's missing-capability statement and
confirm preservation of the original request. For each ambiguous case assess whether
questions cover the missing decisions without inventing answers. Any accepted hard
constraint violation blocks closure. Require at least three complete live repetitions,
one faithful accepted plan in every domain and one executable artifact from a fully
specified held-out case. These bounded measurements do not establish general
natural-language accuracy.

## Current evidence boundary

The implementation has no configured live provider credentials in its development
environment. Deterministic peer tests and `tools/verify_authoring_host.py` prove host
behavior and installed compiler integration only. They do not measure model quality.
The unchanged held-out certificate-renewal fixture is canonically accepted and
satisfies its confirmed requirements. Installed-product assessment covers all six
declared target profiles:

| Selected targets | Current result for this fixture |
| --- | --- |
| `jenkins`, `github-actions`, `tekton` | Review-only projection; concrete integration payload/task binding is absent; rendering is not authorized. |
| `local`, `argo-workflows`, `azure-devops` | No installed projection provider; `PROPOSAL_TARGET_PROJECTION_UNAVAILABLE`, exit 4, accepted canonical and fidelity evidence retained. |

The required executable held-out render therefore remains open. A different target
selection alone cannot resolve it. Concrete certificate integration needs an explicit
architecture and binding decision that preserves the original request; the corpus,
requirements and executable-readiness policy are unchanged.
