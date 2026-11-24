# First conversation with a local model

The first-run trial measures what a person gets after entering an original goal. The three goals and their pass criteria were fixed before the first inference. Each goal started in a new session with no target, user answers, independent requirements, example proposals or repair attempts. The configured provider received the public contracts and the original request. [Goals and pass criteria](../examples/goal-authoring/first-turn-goals.json)

## Outcomes

A success requires either a compiler-admitted plan with the exact resources, operations and ordering in the stated criteria, or a question that asks for both missing decisions in the restore case. A model refusal, malformed reply and request for an already supplied detail each score zero.

| First-turn goal | Full schema in prompt | Capability guide with generic JSON | Capability guide with bounded grammar |
| --- | --- | --- | --- |
| Check the catalog, then tell the audit desk | Refused without identifying a missing capability | Returned a malformed proposal. The host stopped before compilation. | Refused because no deployment environment was supplied, although the user explicitly excluded deployment. |
| Ask which collection and recovery point to restore | Identified both missing values in a refusal | Attempted a restore draft in the wrong reply format, without asking the user | Named both missing values in a refusal instead of asking for them. |
| Verify amber and leave blue alone | Refused as unrepresentable | Refused and requested directory paths and health-check specifics in the refusal | Refused and requested paths and health-check specifics in the refusal. |

Each recipe scored **0/3** against the original criteria. No first turn produced either a compiler-checked plan or the required clarification. The capability guide reduced the initial prompts from about 10,520 to 7,155 tokens; it did not improve task success. The shipped local example retains the complete public contract. The separate installed-command tests cover dialogue, persistence and compilation using a deterministic provider peer.

A development variant also tried to load the entire recursive response schema as a llama.cpp decoding grammar. The native parser rejected it at its recursion-depth limit. All three attempts produced provider errors before returning usable replies. These failures are recorded separately from model decisions.

## What the controls establish

A human-authored proposal for the first goal compiled to a verification of `north-archive` followed by a notification to `audit-desk` depending on that verification. The scope control compiled to one verification of `amber`. Both had semantic acceptance `accepted` and request fidelity `unassessed`. These positive controls establish that the compiler can represent the two requested plans. The controls were never included in a model prompt. The evidence file contains both exact proposals for independent recompilation. [Model replies and compiler controls](../.flow-agent/reports/goal-authoring-first-turn.json)

## Reproducing a run

The trial used the CPU wheel of `llama-cpp-python==0.3.38` and Qwen2.5-1.5B-Instruct Q4_K_M, with a 16,384-token context, up to four threads, temperature zero, seed 42 and a maximum of 2,048 generated tokens. The model file is 1,117,320,736 bytes and its SHA-256 is `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`. Timings and text can vary with the CPU and runtime build. [Pinned GGUF download](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf)

To fetch the same weights without an account, run the following in the checkout.
The digest check uses the same Python installation as the setup guide.

```sh
curl --fail --location --output trial-model.gguf \
  https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf
python3 - <<'PYTHON'
import hashlib
with open("trial-model.gguf", "rb") as model:
    digest = hashlib.sha256()
    for block in iter(lambda: model.read(1024 * 1024), b""):
        digest.update(block)
if digest.hexdigest() != "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e":
    raise SystemExit("Unexpected model digest")
PYTHON
```

Build the distribution and install the optional Python backend using the [first-run guide](GOAL_AUTHORING.md#first-conversation). Run the following from the checkout with a new trial directory. EOF leaves pending questions available for inspection. Each session records its first dispatch, provider result, usage and any compiler review.

```sh
mkdir trial
.venv-author/bin/python examples/goal-authoring/local_gguf.py configure trial-model.gguf --out trial/provider.json
python3 - <<'PY'
import json
from pathlib import Path
import subprocess

trial = Path("trial")
config = json.loads((trial / "provider.json").read_text())
config["budget"].update(maxCalls=1, maxRepairs=0)
config_file = trial / "first-turn.json"
config_file.write_text(json.dumps(config))
cases = json.loads(Path("examples/goal-authoring/first-turn-goals.json").read_text())
for case in cases:
    result = subprocess.run(["flow-author", "chat", case["goal"], "--config", str(config_file),
                             "--out", str(trial / case["id"])], stdin=subprocess.DEVNULL)
    print(case["id"], "exit", result.returncode)
PY
```

The shipped adapter uses the baseline prompt and generic JSON decoding. It supplies the complete public contracts unchanged. The baseline source is at `2530e0c9e84b02284e54bc65c9b982a14b02cce7`. Baseline measurements used `flow-author plan`; the subsequent measurements used `flow-author chat`. Both entry points call the same authoring host.

The second pass used the capability guide at `c4a62b70dc5dff5e41a2ac068ba6ceb34d8a95d1` with generic JSON decoding. The final experiment passed a bounded projection of the public response schema as llama.cpp's grammar, using the same guide, weights and decoding settings. To reproduce the latter, use the recorded `boundedDecoderSchema` with `LlamaGrammar.from_json_schema(json.dumps(schema), verbose=False)` and pass the resulting `grammar` instead of `response_format` to `create_chat_completion`. The full-recursion experiment between the baseline and guide removed unanchored regex constraints for the decoder and attempted to load the whole response schema.

A separate first-conversation check uses a previously unused archive-retirement goal with two deliberately withheld facts: the collection and its decision owner. Its criterion is an explicit question covering both facts before any proposed work. This check is independent of the three-goal score.

Each repeat reused the three known goals. The experiment measures these recipes on this model only. No target was selected and no workflow ran. The next roadmap slice tests capability grounding and the distinction between clarification and refusal on fresh goals.
