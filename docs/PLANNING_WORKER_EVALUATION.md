# Repeated evaluation of worker planning

The evaluator runs a fixed portfolio through the [existing planning worker](BROKERED_AGENT_PLANNING.md)
and its independent canonical review. It measures selected plans against supplied
requirements. It does not use the older data-only provider evaluator or load expected
proposals into the worker. The original twelve-case corpus remains unchanged.

The full lifecycle is available through `tools/evaluate_planning_worker.py`: prepare,
reserve, investigate/compile/select, independently review, recover and summarize.
No orchestration target, workflow execution, model-generated oracle or additional
semantic evaluator is introduced.

## Portfolio and preparation

Install `tools/requirements-planning-worker.txt` and commission the selected worker
using the existing documented owner-run procedure. A matching CLI/schema pin,
dedicated authenticated home and adapter smoke record are still required. The
evaluator neither chooses credentials/models nor commissions a provider silently.

The private JSON portfolio has exactly these fields:

| Field | Meaning |
| --- | --- |
| `formatVersion` | Integer `1`. |
| `worker` | The existing complete worker object: explicit `argv`, `home`, `model` and `profile`. Shared by every trial. |
| `compiler` | Existing planning compiler object: absolute `argv` and public `contracts` directory. |
| `limits` | Existing planning limits: `maxChecks` and `timeoutSeconds`. |
| `cases` | One to sixteen independently prepared cases, defined below. |

Each case has exactly `id`, `domain`, `task`, `discovery` and `requirements`.
IDs and domain labels match `[a-z][a-z0-9-]{0,63}`; case IDs are unique. `task`
is the existing planning task with its original request and declared boundaries.
`discovery` is the existing selector-free v3 configuration. The independent
requirements reference is:

```json
{
  "path": "/absolute/private/foundation-requirements.json",
  "sha256": "<SHA-256 of the exact file bytes>"
}
```

Requirements must be nonempty, independently confirmed and bound to the exact case
request. The compiler still owns their semantic validity. An invalid requirements
document that reaches canonical review stops further dispatch; it is not a reason
to pay for the rest of the portfolio.

All requirements are checked against **every** discovery grant in the portfolio.
Neither another case's requirements, evaluation output nor worker history can be
exposed as discovery input. Symlinks, hard links and nonregular files are rejected.
These checks establish path isolation; the owner must still review the actual task
and exports for answer leakage and select a trustworthy executable. A domain label
or successful commissioning does not prove an unfamiliar held-out task.

Use small exported UTF-8 trees, not a home or unrestricted repository. Before any
turn, the evaluator traverses each entire granted tree through the existing bounded
discovery reader and retains its snapshot privately. Discovery byte, entry, record
and depth limits apply to this full snapshot; oversized or unsafe exports stop
preparation without partial acceptance. The worker receives only the roots/scopes
and the observations it asks for, not the private snapshot or requirement files.

All exports, requirements, configuration, compiler identity and authority code must
remain unchanged across repetitions and recovery. Even changing a file that the
agent has not read invalidates this experiment. Collect a new export and start a
separate evaluation when intentionally testing changed state. Signed inventory
freshness remains enforced; do not extend its lifetime to make a long evaluation
pass. These are snapshots of exports, not assertions of current remote truth.

## Run, recover and summarize

For a three-case portfolio and three repetitions:

```sh
JAVA_HOME=/absolute/jdk-25 /absolute/planning-worker-venv/bin/python \
  /absolute/FlowAi/tools/evaluate_planning_worker.py \
  --portfolio /absolute/private/portfolio.json \
  --out /absolute/private/evaluation-001 \
  --repetitions 3 --max-turns 9
```

`--repetitions` defaults to 3 and permits 3 through 10. `--max-turns` is mandatory
and caps the entire case/repetition matrix, not each case separately. Cases run
in stable portfolio order within each repetition. Every trial starts a separate
worker session; completed trial histories are not inherited by another case.

One worker turn can contain several internal model inferences. This allowance is
**not a provider-request, token or monetary cap**. Existing per-turn tool, byte,
frame, output and elapsed-time bounds remain in force. Deployment billing limits
must be established separately before a live evaluation. Reported token usage is
retained per completed trial; absent usage is not replaced with zero or an invented
price. Commissioning is a separate owner-run turn outside this matrix.

Reusing the same command and output resumes the same experiment:

- Worker commissioning/capability preflight and compiler catalog preparation run
  before the first new reservation. A known preparation failure consumes no turn;
  correcting that prerequisite can resume the same frozen experiment.
- Every trial has a durable outer reservation before entering the worker.
- An exact completed worker receipt is recovered without another model call.
- An exact completed independent review is recovered without compiler or model I/O.
- Missing outer reports can be reconstructed from those retained receipts.
- An uncertain worker turn, a missing reserved worker intent, partial review output,
  changed inputs or contradictory evidence stops further dispatch. A crash after
  reservation but before worker intent conservatively consumes the slot; it never
  authorizes a replacement turn automatically.
- Ordinary blocked selections and independently rejected plans remain completed
  measured outcomes. They do not prevent the remaining planned trials.

Keep the whole output and its original input files. Do not remove a reservation or
partial directory to force a retry. After reconciling a failure, a deliberately new
experiment uses a new directory and explicit allowance; this does not erase the
incomplete experiment.

To validate and recompute the summary without starting any worker or compiler:

```sh
JAVA_HOME=/absolute/jdk-25 /absolute/planning-worker-venv/bin/python \
  /absolute/FlowAi/tools/evaluate_planning_worker.py \
  --portfolio /absolute/private/portfolio.json \
  --out /absolute/private/evaluation-001 \
  --repetitions 3 --max-turns 9 --summary-only
```

Exit `0` means every planned trial has valid completed evidence, including blocked
plans. Exit `3` means the retained experiment is incomplete or contains invalid
trial evidence. Exit `2` means preparation, frozen inputs or campaign identity are
invalid. A complete experiment is not the same as successful plans in every trial.

## Result and interpretation

`evaluation-report.json` is recomputed from the immutable plan, exact reserved
trial set, verified worker receipts and independently verified compiler reviews.
Previously published summary fields are never input authority. The report includes:

- Planned, reserved and completed counts, every case/repetition slot and per-domain counts.
- Bounded preparation and incomplete-evidence diagnostics without raw process output.
- Independent requirement satisfaction and its rate over **all planned trials**,
  so stopping early cannot inflate the reported rate by dropping unfinished cases.
- Compiler acceptance separately from requirement satisfaction. A compiler-valid
  wrong-goal plan remains blocked by independent review.
- Selected candidate, checked drafts, first-draft compiler acceptance, rejected and
  additional draft counts, delivered observation count, tool calls and reported usage.
- Exact planning/review receipt digests, model identity and the captured plan digest.

The evidence kind is always `configured-worker-evaluation`. Naming an executable or
model does not attest that a live provider produced the result. Independent live
provenance, source interpretation, requirement completeness and actual runtime
outcomes remain separate assessments; the report does not self-certify them.
It cannot close S3/AI-A3 or authorize execution. The original live-corpus and held-out
render gates continue to apply.

Private snapshots, requirements and worker records may contain environment data;
they are evaluation state, not automatic publication artifacts. Results describe
their captured experiment. A later successful summary additionally revalidates the
current retained inputs; an old report is not a continuing state assertion.

## Verification

```sh
/absolute/planning-worker-venv/bin/python -m unittest discover -s tools/tests_worker -v
JAVA_HOME=/absolute/jdk-25 /absolute/planning-worker-venv/bin/python \
  tools/verify_planning_evaluation.py --cli /absolute/flow-core/bin/flow-core
```

The installed proof runs the public CLI over three repetitions of infrastructure,
data recovery and a compiler-valid wrong-goal mutation. It checks rejected-draft
repair, private-expectation isolation, independent rejection, completed recovery,
contradictory review metadata and changed unread exports. Its peer is explicitly
scripted: it verifies the evaluator and compiler integration, not model reasoning.
