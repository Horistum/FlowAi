# Structured Intent Values

Status: v0.3.0-rc1.8.3 draft

Flow intent input is a user/AI boundary. The intent loader must preserve structure instead of flattening YAML maps/lists into strings.

Supported normalized value kinds:

- `string`
- `number`
- `boolean`
- `null`
- `list`
- `object`
- `secret`
- `ref`
- `expression`

Examples:

```yaml
params:
  app: demo
  wait: true
  filters: { status: active, region: eu }
  token: secret:ARGOCD_TOKEN
  image: ref:build_image.tag
```

This normalizes to structured `IntentValue` objects and is lowered deliberately to Flow AST expressions. This prevents the intent layer from becoming another fragile YAML string parser.

## Design rule

If a value is structurally meaningful to the user, it must remain structured until the lowering or validation layer explicitly chooses how to map it.
