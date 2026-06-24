# Flow Vision & Scope v1.0

Flow is an AI-first automation standard. It is not a Jenkins DSL, not another YAML dialect, and not a replacement for general-purpose programming languages.

Flow exists because modern automation platforms solve similar problems with different syntax, lifecycle models, variable handling, artifact behavior, secrets, approvals, error handling, and execution semantics. Teams waste time learning and migrating between these systems instead of designing robust automation.

Flow separates the human intent from the target implementation.

```text
Human / AI Intent
  -> Standard Intent Model
  -> Flow Source / Flow AST
  -> Validator
  -> Planner
  -> Flow Execution Plan
  -> Target Compatibility Report
  -> Target Generator / Runtime
```

## Core goal

Create a clear standard that covers roughly 80% of common DevOps automation scenarios with a simple base model while still allowing advanced scenarios through AST, modules, execution plans, target capabilities, and validators.

## Human role

The human becomes the architect of the solution:

- defines the goal
- defines rules
- defines risks
- defines safety boundaries
- approves critical behavior

The human should not have to memorize every lifecycle detail of Jenkins, Tekton, Argo Workflows, GitHub Actions, Azure DevOps, GitLab CI, Airflow, or Kubernetes Jobs.

## AI role

AI helps translate human intent into Flow models, explains the plan, suggests improvements, and generates target-specific outputs. AI must never bypass parser, validator, policy checks, or target compatibility checks.

## What Flow is

- a standard intent model
- a source language
- an AST model
- a module system
- an execution plan model
- a target capability model
- a compatibility reporting system
- a runtime/generator architecture

## What Flow is not

- a Jenkins-only DSL
- a shell scripting replacement
- a new general-purpose programming language
- a platform-specific pipeline framework

## 80% automation scope

Flow should first cover:

- build
- test
- package
- build image
- deploy
- verify
- approve
- rollback
- notify
- sync data
- validate data/configuration
- backup
- restore
- cleanup
- report

## Advanced scope

Advanced scenarios are supported through lower-level Flow statements, modules, execution-plan nodes, and target-specific runtimes/generators. Advanced support must remain explicit and validated.

## Architectural rule

Low-level Flow statements exist to represent exact behavior. They are not the main product vision.

The main product vision is the standardization layer:

```text
intent -> validated model -> execution plan -> compatible target output
```
