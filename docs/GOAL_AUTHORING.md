# Plan from plain text

## First conversation

Run the following from the FlowAi checkout on Linux (including WSL) or macOS, with Java 25 and Python 3.10 or later. Build the CLI and make it available in this terminal:

```sh
./gradlew :flow-cli:installDist && export PATH="$PWD/flow-cli/build/install/flow-core/bin:$PATH"
```

For a local model, install the Python inference backend once:

```sh
python3 -m venv .venv-author && \
  .venv-author/bin/python -m pip install --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu llama-cpp-python==0.3.38
```

**If the build and pip installation already succeeded, start here.** This command downloads the public Qwen2.5-1.5B-Instruct Q4_K_M model (1.12 GB), verifies its SHA-256, saves a provider configuration, then opens the chat. The model runs on your machine. The first prompt is `What would you like to automate?`.

```sh
.venv-author/bin/python examples/goal-authoring/local_gguf.py configure --download-example && \
  ./flow-cli/build/install/flow-core/bin/flow-author chat
```

The file stays in `${XDG_CACHE_HOME:-$HOME/.cache}/horistum/models`. Subsequent chats need only `flow-author chat`. The sample model is suitable for trying the conversation. Check the operations and their arguments in any proposed plan. [Measured local-model results](GOAL_AUTHORING_GROUNDING.md)

If you already have a GGUF model, you can configure it offline. Enter the actual filename when prompted. For a split model, keep all shards together and enter the path to the first one.

```sh
printf 'Path to your existing GGUF model: '
IFS= read -r model && .venv-author/bin/python examples/goal-authoring/local_gguf.py configure "$model"
```

The local setup writes `${XDG_CONFIG_HOME:-$HOME/.config}/horistum/authoring.json`. An existing configuration is preserved. To use a different provider configuration, pass its JSON file with `flow-author chat --config` or set `FLOW_AUTHOR_CONFIG`. [External provider configuration](BOUNDED_AUTHORING_HOST.md#configuration-and-one-run)

For goals that name resources and a set of operations, the optional `operations` profile restricts the local model to capabilities and parameter names published by the installed compiler. The existing `full` profile supports the complete proposal format, including conditions, schedules, failure handlers and explicit module bindings. This example reuses the verified model and creates a separate configuration:

```sh
.venv-author/bin/python examples/goal-authoring/local_gguf.py configure --download-example --profile operations --out local-operations.json && \
  flow-author chat --config local-operations.json
```

A missing argument can produce a question before a draft. Plain-text argument
values are copied from the goal or answers. Structured values, including
numbers, lists and objects, can be selected from a request's `context` using
a JSON pointer. A graph can still omit requested work or select the wrong
supplied value. Read the operations, arguments and prerequisites before using
the candidate. [Measured results and limits](GOAL_AUTHORING_GROUNDING.md)

Enter your goal at the prompt. When a decision is missing, answer it in plain
text. Type `:pause` or close the input to leave a question round pending. The
terminal prints the command to continue from the saved session. Answers from a
partially filled round are not sent. A completed session can be reopened without
calling the model again.

```sh
flow-author chat --out my-review 'Check the catalog during the maintenance window. Notify the owner otherwise.'
```

After the terminal prints a saved session path, you can reopen it:

```sh
flow-author chat --run my-review
```

If startup reports a missing provider configuration, finish the model setup and start a new chat. The failed start created no session to reopen.

Without `--out` each new conversation uses its own directory under
`${XDG_STATE_HOME:-$HOME/.local/state}/horistum/sessions`. The terminal shows the
compiler's decision and the reviewed operations. Conditional branches and failure
handlers are indented separately. Keep the printed plan and review paths for
machine-readable evidence. Provider limitations and compiler rejection are possible
outcomes. Conversation mode performs no deployment or resource changes.

## Machine-readable commands

Use `flow-author plan`, `answer` and `status` for automation. They retain their JSON
output and existing exit codes.

```sh
flow-author plan 'After approval, deploy the existing payments image pinned to its digest to staging. Verify payments after deployment.' \
  --config "$HOME/flow-provider.json" --out payments-review
```

The example leaves the image digest unspecified. A provider can request it before
drafting the deployment. Supply the ID printed under `questions` and the actual
value. IDs are scoped to the turn. Read pending questions again with
`flow-author status --run payments-review`. Reading a session consumes no provider
call. Multiple pending questions take one `--answer ID TEXT` each.

```sh
flow-author answer --run payments-review --reviewer operator \
  --answer 1:image 'Use registry.example/payments@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
```

An existing caller can pass `--request request.json` in place of the text argument.
The original `userText`, `mode` and `context` (including `knownSystems`) are carried
through subsequent decisions unchanged. Managed investigation configurations use their
existing observation and independent-criteria protocol. When a discovery packet needs
a reviewer, submit the decision with `flow-author review-discovery --run DIR --review FILE`.

`plan-ready-for-review` (exit 3) means the compiler produced a portable plan.
Compare its operations, parameters, dependencies and assumptions with the original
goal. `planFile` identifies the compiler's plan and `proposalFile` identifies the
exact proposal. Request fidelity is `unassessed` until checked independently.
A compilable candidate can still omit the approval or nominate the wrong resource.
`awaiting-user` (exit 3) lists pending decisions. Answers retain the original
request and consume the existing session budget. A provider may also return `provider-cannot-plan` (exit 4) with
its stated limitation. Only an actual proposal enters the compiler. A stopped run returns 4. A bad
command, answer or configuration returns 2.

After inspecting the candidate, select the printed `proposalFile` for a target
review. This reuses the exact candidate without calling the model again. The
compiler reports request fidelity as `unassessed` on this route. To check an
independent contract as well, add `--requirements "$REVIEWED_REQUIREMENTS"` with
confirmed criteria bound to the original goal.

```sh
flow-core proposal "$PROPOSAL_FILE" \
  --target jenkins --render --out target-review
```

Alternatively, pass `--requirements` to the initial `flow-author plan` command
with an optional `--target` and `--render`. Only the compiler assesses those
criteria, target support and rendering. Inspect `targetDisposition` and
`renderAuthorized` for the resulting decision. These commands perform no
deployment or resource removal.

The old `flow-core normalize` scenario classifier now requires `--scenario-pack`.
Add that flag to existing scripts that intentionally use its heuristic drafts.

## Provider replies

A goal session using a plain proposal provider accepts three response forms within
the existing `command-reasoning/v2` result. The host publishes the complete schema
and records `goal-dialogue/1` with the session.

| Result | Host behavior |
| --- | --- |
| `{"kind":"clarify","questions":[{"id":"owner","question":"Which owner?"}]}` | Present the decision immediately. The next provider call receives the question, its turn-specific ID and the caller's answer. |
| `{"kind":"cannot-plan","reason":"..."}` | Preserve the provider's explanation and stop. |
| `{"normalizedIntent":{...},"report":{...}}` | Compile the candidate using the installed proposal contract and the selected target policy. |

The installed-command checks exercise deployment, retirement and a conditional
maintenance workflow. The conditional case obtains a task from the supplied
context and the notification content from two separate user answers. CI uses
a deterministic provider peer and the actual compiler. The [local trial](GOAL_AUTHORING_LIVE_TRIAL.md) records independent live first turns on new goals.
