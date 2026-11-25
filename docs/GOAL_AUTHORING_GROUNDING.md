# Grounding a draft in the capability catalog

## Choosing a local model

We ran six identical requests through the installed chat on three GGUF models, using the same operations profile. The reviewer checked the requested outcome, the relevant resource values and the required order. A second parameter naming the same resource did not invalidate an otherwise correct backup. These six selected inputs exercise common user-facing failures and do not estimate a general success rate.

| Request | Qwen2.5-1.5B | Qwen3.5-2B | Qwen3.5-4B |
| --- | --- | --- | --- |
| AKS pipeline with Terraform | Image build | Provision operation | Provision, build, test, deploy and verify operations |
| Backup followed by a health check | Omitted health check | Omitted destination and prerequisite | Met all named requirements |
| Rotate a named secret | Asked back for the supplied name | Correct rotation | Correct rotation |
| Ask for backup storage and use the answer | Produced checkout | Produced provisioning | Correct backup after the answer |
| Copy data and validate the copy against schema v4 | Produced checkout | Missing prerequisite | Wrong schema value |
| Return a live memory reading without access | Proposed a plan | Asked again about access | Asked for a monitoring tool |

The 4B model met three outcomes. The 2B model met one and the 1.5B model met none. The AKS requests produced portable drafts without a pipeline file. Median first-turn wall times were **62.0 s**, **28.8 s** and **25.4 s**, respectively, on the test CPU. Each call included startup and inference. Local tests shared the machine. The quickstart uses 4B for its better results on the two backup tasks. Select 2B for a smaller download and a shorter wait on this setup.

[Exact prompts, outputs, weight hashes and timings](../.flow-agent/reports/local-operations-qwen35.json) · [Run a chat and check its operations](GOAL_AUTHORING.md)

## Earlier Qwen2.5 trials

The measurements below describe the operations profile at commit `10e8ff19`. Use that revision to reproduce its prompts.

The optional `operations` profile of `flow-author chat` lets a local GGUF model ask for a missing decision or propose a graph of unbound operations. It constrains which operations, fields and literal values the model can submit. On three fresh goals, both tested models asked for the missing inventory name, but neither produced a fully correct plan on any of the goals. Keep the result in review before selecting a target. [Getting started](GOAL_AUTHORING.md#first-conversation)

## What changed for authors

On the previous release, the authoring host received an empty parameter list even for `DATA_SYNC`, where the compiler requires both `source` and `destination`. The public catalog now supplies the parameter lists used by the compiler and marks fields that require an explicit module action. The executable contract snapshots for all 31 capabilities matched before and after the change. JSON and Markdown catalog output and the authoring host read the same definitions.

The profile constructs a decoding grammar from the installed catalog for each dispatch. String arguments must be copied from the original goal, submitted answers or caller context. JSON pointers preserve the types of structured context values. The model chooses the operation, argument roles and explicit prerequisite edges. A missing argument can become a question without sending a partial plan to the compiler. A copied word can still be the wrong value for a field. The existing full profile remains the default for requests involving conditions, middle-step values, failure handling, scheduling or explicit module bindings.

## Fresh acceptance goals

The implementation was fixed at `738eb8718d0ada59f4d376f1e8fd53f3df94a92f` before these three goals were run. The answer and expected operations were written before inference. The provider saw only the answer after asking a relevant question. It never received the expected graph. Each goal started in a new session. We used one model dispatch for complete inputs and allowed one further dispatch after the missing-name question. No repairs, target rendering or execution were requested. [Original inputs, answers and expected graphs](../examples/goal-authoring/grounding-acceptance.json)

| Request | Qwen2.5 1.5B | Qwen2.5 3B |
| --- | --- | --- |
| Renew only certificate `cedar-web` for `ticket-hub` | Selected the correct certificate and service, then added `target: service`. | Selected the certificate and service, added `provider: service` and proposed an extra notification. |
| Back up to `archive-5`, first asking which inventory | Asked for the name. After receiving `stock-north`, asked for it again. | Asked for the name. After the answer, proposed two backups and put `archive-5` in `target` on both. |
| Validate `shift-table` against `shift-v2`, then back it up to `night-vault` | Asked for the schema already supplied. | In an isolated replay, selected both operations, put the schema in `operation`, and left the backup independent of validation. The original provider process ended with signal 9 before replying. |
| Complete goals meeting all criteria | **0/3** | **0/3** |

For an exact pass, we compared the authored parameter maps, compiler-projected operations, expected literal values, step count and direct dependency edges against the expectation. We also ran the installed `flow-core proposal` with independently supplied requirements. The scopes differ. A certificate review can report `requirements-satisfied` for the specified name and service while an extra field or notification still fails the exact check. In the inventory case, success also required asking the right first question and using the answer. The original dialogue ran without these requirements or expected graphs. [Per-dispatch evidence and independent compiler decisions](../.flow-agent/reports/goal-authoring-capability-grounding.json)

### Earlier trials

The previous full-profile 1.5B adapter at `741729d693686bc8f11226cb8208b9e46ffe9745` refused all three development goals. Those goals were subsequently used to develop the grammar. They cover manifest validation, a backup with a missing destination and a dataset copy followed by validation. [Development goals](../examples/goal-authoring/grounding-goals.json)

An earlier version at `3b4901f9de3ad30f48973895d6db5d1e7f4ef8d7` was tested on a separate set. The 1.5B model succeeded on a policy check and on rotation after asking for the secret name. It omitted the health check after a backup (**2/3**). The 3B model requested supplied policy details, failed to finish rotation after receiving the name and chose `TEST` for the health check (**0/3**). On a separate development conversation that version also introduced two image pushes unrelated to a backup. The shipped version keeps format illustrations in the system instructions and uses placeholders outside the executable vocabulary. Only genuine questions and answers appear as dialogue turns. Replaying that same backup on the final 3B profile removed the image pushes but added an unrelated checkout. Changing the illustration did not solve unrequested work. [First evaluation goals](../examples/goal-authoring/grounding-evaluation.json)

## Reproduce the acceptance run

Build the CLI and install the optional Python backend as described in the first-run guide. The trials used llama-cpp-python 0.3.38, Qwen2.5-Instruct GGUF Q4_K_M, CPU inference with up to four threads, a 16,384-token context, 2,048 output tokens, temperature zero and seed 42. The evidence file includes the exact weight and input file hashes. CPU and runtime differences can affect decoding. [1.5B weights](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/tree/91cad51170dc346986eccefdc2dd33a9da36ead9) · [3B weights](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/tree/7dabda4d13d513e3e842b20f0d435c732f172cbe)

Run from the checkout and use a fresh directory per model. The command below selects the measured 1.5B model. To repeat with 3B, pass its downloaded GGUF filename in place of `--download-example`. For a complete goal, the following example starts the installed chat, preserves the first response and extracts the independent requirements for separate review.

```sh
mkdir trial
.venv-author/bin/python examples/goal-authoring/local_gguf.py configure --download-example \
  --profile operations --out trial/provider.json
jq '.budget.maxCalls = 1 | .budget.maxRepairs = 0' trial/provider.json > trial/one-turn.json
cases=examples/goal-authoring/grounding-acceptance.json
goal=$(jq -r '.[] | select(.id == "certificate") | .goal' "$cases")
flow-author chat "$goal" --config trial/one-turn.json --out trial/certificate </dev/null
jq '.requirements.certificate' .flow-agent/reports/goal-authoring-capability-grounding.json > trial/criteria.json
```

A reviewable draft and a pending question both exit with status 3. If there is a proposal, review the actual file from that attempt:

```sh
flow-core proposal trial/certificate/attempt-000/proposal.json \
  --requirements trial/criteria.json --out trial/review
```

Repeat with `validate-backup` in a fresh session. For `inventory-name`, set `maxCalls` to 2 and leave repairs at zero. Inspect the pending question first, then reopen the session with `flow-author chat --run trial/inventory-name` and enter the predefined answer only if the question requests the omitted name. Compare the exact proposed steps and canonical graph with the case file, including extra parameters and steps. No model call is needed for compiler review.

The next roadmap slice makes missing operations, unrequested work, incorrect argument roles and ordering visible to the person reviewing the goal before target selection. These measurements establish a failure mode for two local models. General-purpose authoring and held-out target rendering remain open.
