# Node-Preserving Generators

Status: v0.3.0-rc1.8.3 draft

Target manifest generators consume `ExecutionPlan.nodes`, not only the flattened `ExecutionPlan.tasks` view.

The goal is to preserve user intent structures:

- conditions
- approvals
- parallel groups
- loops
- retries
- match blocks
- error handlers
- control/data operation nodes

A target renderer may degrade or partially map a feature, but it must not silently erase the structure. Unsupported and partial features belong in the Compatibility Report.
