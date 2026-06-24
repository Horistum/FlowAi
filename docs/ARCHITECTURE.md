# Flow Architecture v0.1.7

Flow is organized around a platform-neutral automation standard.

The low-level `.flow` language is important, but it is not the only representation. The central architecture is the model pipeline.

```text
Human / AI Intent
  -> Standard Intent Model
  -> Flow Source / Flow AST
  -> Validator
  -> Planner
  -> Flow Execution Plan
  -> Target Compatibility Report
  -> Target Generator / Runtime
  -> Target Platform
```

## 1. Standard Intent Model

The Standard Intent Model captures high-level automation intent.

Examples:

- build application
- run tests
- deploy to environment
- require approval for production
- verify health
- rollback on failure
- notify team

Implementation package:

```text
org.flowlang.intent
```

Current status: data model draft only.

## 2. Flow Source / AST

The low-level source language is a precise representation of behavior.

Example:

```flow
shell.run local {
  command: "mvn test"
} -> tests {
  expect {
    ok == true
    code == 0
  }
}
```

Implementation packages:

```text
org.flowlang.parser
org.flowlang.ast
```

Current status: recursive-descent parser and AST model implemented for current examples.

## 3. Validator

The validator checks:

- module imports
- system declarations
- action contracts
- required parameters
- type compatibility
- secret handling
- destructive safety rules
- expression references

Implementation package:

```text
org.flowlang.validator
```

## 4. Module system

Modules define meaning. Core owns syntax.

Modules define:

- system types
- actions
- input schema
- output schema
- effects
- safety requirements
- retry support
- timeout support

Implementation package:

```text
org.flowlang.modules
```

## 5. Planner and Execution Plan

The planner lowers validated AST into a platform-neutral Execution Plan.

The Execution Plan preserves:

- task nodes
- conditions
- loops
- parallel groups
- match branches
- retry groups
- approvals
- data operations
- control nodes

Implementation package:

```text
org.flowlang.planner
```

## 6. Target Capability Model

A target capability profile declares what a platform supports.

Examples:

- Jenkins supports approvals and dynamic loops.
- GitHub Actions supports many simple workflows but has limited dynamic runtime behavior.
- Tekton has strong Kubernetes-native tasks but limited inline manual approvals.
- Argo Workflows supports DAGs and suspend-style gates.

Implementation package:

```text
org.flowlang.capabilities
```

## 7. Compatibility Report

Before generation, Flow must check whether a target can represent the plan.

Outputs:

- SUPPORTED
- PARTIAL
- UNSUPPORTED
- REQUIRES_RUNTIME

This prevents silent semantic loss.

## 8. Generators and plan preview

Generators produce target-specific output.

Current draft generators:

- Jenkins
- GitHub Actions

Current plan preview:

- Non-executing plan preview

Implementation packages:

```text
org.flowlang.generators
org.flowlang.preview
```

## 9. Design boundary

Flow must not become:

- a Jenkins-only DSL
- a general-purpose programming language
- a shell scripting clone
- a pile of target-specific syntax exceptions

Flow should remain:

- intent-first
- validated
- explainable
- platform-neutral
- target-aware


## v0.1.7 Intent pipeline

Added `IntentYamlLoader`, `IntentToAstPlanner`, and CLI command `intent <file> --target <target>` to produce normalized intent, generated AST, validation report, execution plan and target compatibility report. Rollback is represented as a standard rollback capability; target-specific rollback behavior remains extensible through modules and compatibility contracts.
