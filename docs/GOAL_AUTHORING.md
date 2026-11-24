# Plan from plain text

Install the product with `./gradlew :flow-cli:installDist` and put
`flow-cli/build/install/flow-core/bin` on PATH. On Linux or macOS, the authoring
command uses Python 3.10+ and the existing Java 25 compiler. Configure a
command-reasoning/v2 provider once with its executable, credentials, matching
compiler and spending limits. The same configuration serves different goals.
[Provider configuration example](BOUNDED_AUTHORING_HOST.md#configuration-and-one-run)

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
a deterministic provider peer and the actual compiler. Measuring live model quality
on new goals remains a separate roadmap step.
