# Flow v0.3.4 Notes

v0.3.4 is a correction release.

The project should not drift into a plugin SDK, runtime framework or renderer-template catalog. The core Flow path remains:

```text
Human / AI intent
-> Standard Intent Model
-> validation of rules, risks and capabilities
-> Execution Plan
-> target capability negotiation
-> target manifest / renderer
```

## Capability Module Contract

Modules are capability/effects dictionaries. They describe what an action means, not how a target should render or execute it.

Allowed in the public module contract:

- actions,
- inputs,
- outputs,
- effects,
- secrets,
- safety requirements,
- required capabilities,
- target implications.

Not allowed in the public module contract:

- runtime hooks,
- entrypoints,
- renderer templates,
- generator ownership.

## Target implications

Target implications are capability-level hints:

```yaml
targetImplications:
  githubActions:
    support: partial
    requiredCapabilities:
      - secrets.runtime
```

They are not renderer templates and must not contain implementation entrypoints.

## Guardrail

Conformance now includes:

```text
architecture.modules-do-not-own-target-rendering
v0.3.4.capability-module-contracts
```

These checks keep module descriptors aligned with the original Flow idea.
