# Core / Adapter Split

Flow is a standardization layer, not a YAML parser project and not a Jenkins DSL.
The core model must remain independent from loader and renderer dependencies.

## Core packages

These packages should not import Jackson, YAML parsers, Jenkins APIs, GitHub APIs or Tekton SDKs:

- `org.flowlang.ast`
- `org.flowlang.planner`
- `org.flowlang.intent` model classes
- `org.flowlang.capabilities`
- `org.flowlang.standard`
- `org.flowlang.core`

## Adapter packages

These packages may depend on serialization and target-specific libraries:

- `org.flowlang.adapters.yaml.IntentYamlLoader`
- `org.flowlang.adapters.yaml.TargetRegistryYamlLoader`
- `org.flowlang.cli`
- `org.flowlang.conformance`

Legacy compatibility loaders remain in:

- `org.flowlang.intent.IntentYamlLoader`
- `org.flowlang.targets.TargetRegistryYamlLoader`

New code should use the adapter namespace.

## v0.3.2 direction

The Execution Plan, standard intent catalog, capability contracts and target negotiation model are treated as core standard contracts. YAML loading, CLI commands, conformance file loading and rendered target outputs remain adapter concerns.

v0.3.2 adds a canonical lowercase Execution Plan export so adapters and runtimes can consume a stable public representation instead of depending on internal planner node class names.

## v0.3.4 correction

v0.3.4 corrects the module direction. Module descriptors remain adapter-facing YAML inputs, but the loaded module contract and report are standard contracts for capability semantics only. Modules describe action inputs, outputs, effects, safety requirements, secrets, required capabilities and target implications. They must not own runtime hooks, renderer templates or generator implementation details.

The practical split should evolve toward:

- `flow-core`: AST, intent model, planner, validator, execution-plan contract, capability negotiation contracts.
- `flow-adapters`: Jackson/YAML loaders, CLI, conformance runner, rendered target output helpers.
- `flow-modules`: reusable capability module descriptors and contracts.
- `flow-targets`: target registries and target manifest renderers.

## Why this matters

The user should describe intent once. Flow should normalize, validate, plan and render it without making the user learn Jenkins, GitHub Actions, Tekton, YAML edge cases or target lifecycle details.
