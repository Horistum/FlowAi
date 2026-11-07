# System automation planning examples

These examples define infrastructure/DevOps foundations, data recovery rehearsal and
environment retirement without requiring an application or source repository. Each
proposal has an independently specified requirements document. They use the existing
Intent model and version 2 request-fidelity checks; no scenario-specific production
normalizer or adapter is added.

```bash
flow-core proposal examples/system-automation/infrastructure-foundation.proposal.json \
  --requirements examples/system-automation/infrastructure-foundation.requirements.json \
  --out /tmp/new-foundation-review
```

Use `data-recovery-rehearsal` or `environment-retirement` in the filenames to inspect
the other lifecycles. Use a new output directory each time. The installed product CLI
is `flow-cli/build/install/flow-core/bin/flow-core` in a development checkout.

| Example | Operations and relations |
| --- | --- |
| Infrastructure foundation | Policy and approval; distinct network, identity, compute, monitoring, project, connection and agent resources; structured value dependencies; final verification |
| Data recovery rehearsal | Production backup; approved restore into a distinct rehearsal destination; verification before migration; migration retains the actual original recovery point |
| Environment retirement | Backup and verification; ownership check and approval; dependent removals; explicit exclusions for shared network and retained vault |

The examples are manually authored and the reviewer identity is a declaration. No model
was called, no cloud account was used and no runtime result is claimed. Azure in the
foundation request is managed-system context, not an implemented Azure binding. Accepted
canonical plans remain subject to actual target capability and materialization gates.

The source quotes and checks specify a bounded interpretation of each request. They do
not establish completeness of natural-language understanding. Verification steps are
planned operations, not completed observations; deletion safety inputs are represented
dependencies, not evidence that ownership was actually established.

`python3 tools/verify_system_automation.py --cli PATH` checks these examples and mutations
through the installed product. It verifies resource substitution, wrong value producers,
missing data relations, protected-resource removal and prohibited image builds.

See the [development direction](../../docs/SYSTEM_AUTOMATION_DIRECTION.md) for the remaining
structured control, state, discovery, implementation and live-evaluation work.
