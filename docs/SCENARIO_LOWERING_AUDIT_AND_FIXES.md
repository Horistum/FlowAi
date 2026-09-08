# Flow v0.3.0-rc1.8.3 Audit and Fixes

This corrective release validates and fixes the scenario-pack regressions reported after rc1.7.

## Valid findings and fixes

| Finding | Status | Fix |
|---|---:|---|
| Cleanup semantic target was interpreted as an intent system name | Valid | Cleanup now uses `resource` for the resource being cleaned. `system` is the only intent parameter treated as a system selector. |
| Simple backup phrases such as `orders database` were not extracted | Valid | Backup subject extraction now supports both `database orders` and `orders database` forms. |
| Custom and rollback-only requests crashed lowering through blocking required clarification | Valid | Custom review is now a recommended clarification and can lower to a standard human-review action. Rollback-only no longer synthesizes deployment and no longer crashes. |
| Non-English aliases were reintroduced | Valid | Non-English keyword triggers and extraction aliases were removed from source, tests, examples and project docs. |
| Cleanup regression coverage did not execute full lowering | Valid | Scenario pack tests now run cleanup through intent validation, AST lowering, Flow validation and execution planning. |
| Simple backup and custom review gaps were not covered | Valid | Added full-pipeline regression tests for simple backup, custom review and rollback-only review. |

## Remaining design debt

- Full offline Gradle build still depends on a local Gradle distribution and project dependencies.
- Runtime-grade adapters for every semantic `standard.execute` operation are still future work.
- Legacy draft generators remain for backward-compatibility tests but are not the canonical generation path.
