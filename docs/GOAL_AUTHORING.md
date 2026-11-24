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
value. Multiple pending questions take one `--answer ID TEXT` each.

```sh
flow-author answer --run payments-review --reviewer operator \
  --answer question-0 'Use registry.example/payments@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
```

For a retirement request, use the same command with a different goal and session
directory. Describe the resources subject to removal, which ones to preserve,
the backup destination and any approval or ownership conditions.

`plan-ready-for-review` (exit 3) means the compiler produced a portable plan.
Compare its operations, parameters, dependencies and assumptions with the original
goal. `planFile` identifies the compiler's plan and `proposalFile` identifies the
exact proposal. Request fidelity is `unassessed` until checked independently.
A compilable candidate can still omit the approval or nominate the wrong resource.
`awaiting-user` (exit 3) lists pending decisions. Answers retain the original
request and consume the existing session budget. A stopped run returns 4. A bad
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

## Regression coverage

The installed-command tests exercise an existing-image deployment and a retirement
with shared resources using one configured deterministic provider peer and the
actual compiler. They compare each retained graph against its own independent
criteria, reject a candidate that removes the shared network, and continue an
ambiguous goal from an explicit answer. Live model evaluation on fresh goals is
the next roadmap step.
