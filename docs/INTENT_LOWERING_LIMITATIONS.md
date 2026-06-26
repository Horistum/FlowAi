# Intent Lowering Limitations

Intent lowering currently preserves authored order as a safe default. `requires` constraints are honored, and each ordered step is lowered with an explicit dependency on the previous lowered step unless the source model grows an explicit parallel intent structure.

This is intentional for the current package line. It prevents hidden parallel execution from changing operational semantics. Future planner work may introduce safe DAG expansion, but only after dependency analysis and target capability constraints are explicit.
