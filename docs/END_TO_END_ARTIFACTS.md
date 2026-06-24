# End-to-End Artifacts

Flow v0.3.0-rc1.8.3 introduces an exported artifact bundle for the public standard pipeline.

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target jenkins --strict --render --out generated/jenkins"
```

The output directory contains:

- `standard-version.txt`
- `normalized-intent.json`
- `intent-capability-validation-report.json`
- `flow-ast.json`
- `validation-report.json`
- `execution-plan.json`
- `compatibility-report.json`
- `target-manifest.json`
- rendered target output such as `Jenkinsfile`, `github-actions.yml`, or `tekton-pipeline.yaml`

These artifacts are the concrete expression of the Flow mission: users describe intent once, and Flow produces validated, inspectable, target-aware outputs.
