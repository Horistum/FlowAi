# git-opts-planner

Normative Horistum planner reference.

Run the committed example from the repository root:

```bash
./gradlew :flow-reference-distribution:gitOptsPlan \
  -PgitOptsFile=reference/git-opts-planner/examples/change-readme.yaml
```

The command validates the typed intent and prints a deterministic JSON operation DAG. It does not execute Git commands, contact a remote repository or consume credentials.

Files in this reference:

- `planner-descriptor.json` declares planner properties and the capability vocabulary.
- `schema/git-opts-intent.schema.json` documents the machine-readable input shape.
- `examples/change-readme.yaml` is the runnable input to copy and edit.
- `../../docs/GIT_OPTS_PLANNER_REFERENCE.md` contains the complete architecture, model, failure, security and extension guide.

The implementation lives in `org.flowlang.distribution.reference.gitopts` and is compiled as part of `flow-reference-distribution`.
