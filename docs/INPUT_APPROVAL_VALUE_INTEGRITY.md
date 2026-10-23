# Input and approval value integrity

A-06 closes admission gaps at the shared compiler boundary. Parsing a value does not authorize its use. Flow validation and direct planning apply the same rules before an execution plan can discard an unsupported field. Rejected compilations retain their AST and error diagnostics.

## Inputs

Input kinds are case-sensitive. The accepted vocabulary is:

| Family | Authored kinds | Accepted default |
| --- | --- | --- |
| Text | `text`, `string` | String literal |
| Number | `number`, `int`, `integer`, `float`, `double` | Finite numeric literal, including negative literals |
| Boolean | `boolean`, `bool` | Boolean literal |
| List | `list`, `array` | Literal list |
| Map | `map`, `object`, `json`, `yaml` | Literal map |
| Secret | `secret` | Secret reference |
| Unconstrained | `any` | Any supported static value, including explicit null |
| Option | `option` | String literal contained in the declared domain |

Aliases retain their authored spelling in the plan and use the existing availability type families. Numeric aliases do not add integer-only validation. This is the input contract, separate from module schema types: `duration` and `artifact`, for example, are not admitted input kinds.

Every option domain must contain at least one non-blank literal string. Authored order is preserved; empty or whitespace-only labels are rejected because the canonical graph forbids them. Non-string members, expression members and choices attached to another input kind are errors. An out-of-domain default is now an error (`INPUT_DEFAULT_NOT_IN_OPTION`), previously a warning. No default is invented when absent; `required` is retained independently of the default.

Lists and maps may contain nested static values, including null and secret references. Map keys and default secret-reference names must be plain Flow identifiers (letter/underscore followed by letters, digits or underscores); other spellings are rejected because the existing expression renderer cannot encode them unambiguously. References, calls, templates and other computed defaults are rejected with `INPUT_DEFAULT_UNSUPPORTED`, including inside collections. This boundary does not implement initialization order, dependent defaults or runtime type inference. Numeric values must be finite; integer-tagged AST numbers must be integral and renderable without saturation. Direct-AST static validation is bounded to 10,000 values and depth 128. Default values are not included in error messages.

Other diagnostics are `INPUT_TYPE_UNSUPPORTED`, `INPUT_OPTIONS_UNEXPECTED`, `INPUT_OPTIONS_EMPTY`, `INPUT_OPTION_VALUE_UNSUPPORTED` and `INPUT_DEFAULT_TYPE_MISMATCH`. Direct planning raises `InvalidInputApprovalValueException` with the same diagnostic code and declaration location when available.

## Approval

The existing approval contract admits only the optional `message` parameter. It must be a non-blank string literal or a template containing literal text and simple reference paths. Computed interpolation and literal text containing `${` are rejected with `APPROVAL_MESSAGE_UNSUPPORTED`: the existing message representation cannot distinguish all of these authored forms. Unknown fields, including invented `timeout`, `approvers` or `unexpected` fields, produce `APPROVAL_PARAM_UNSUPPORTED`; incompatible message values produce `APPROVAL_MESSAGE_TYPE_MISMATCH`. No values are coerced into strings. Author a reference message as `message: "${joined}"`; the bare `message: joined` form is rejected instead of rendering the reference name as literal text. Existing approval modes, results, dependencies and control evidence retain their existing contracts.

The message retains the existing expression-rendered string representation. Removing the enclosing quotes removes exactly one pair, preserving escaped trailing quotes. Literal interpolation markers are also rejected when assembled across adjacent typed-AST literal parts, including empty intervening parts. Templates remain templates for downstream admission; accepting a canonical template does not establish that every adapter can execute it.

These rules also apply inside conditions, loops, parallel branches, match branches, retries, unreachable statements and failure handlers. They do not implement approver identity, timeout policy, quorum or any other EF-09 feature.

## Frontends and evidence

Flow Source and typed AST callers share the same admission owner. Intent YAML and reviewed AI proposals lower into that boundary. Intent approval messages preserve their expression kind instead of passing through a text conversion. Omitted Intent messages retain the existing policy/generated message behavior; an authored null is not an omission.

Intent option notation is parsed as a list expression. Existing bare labels such as `option[dev,prod]` remain supported; quoted commas such as `option["a,b","other"]` remain intact. Empty labels remain present in the AST and are rejected, never silently removed. Numeric and Boolean list members retain their types and are rejected, rather than being converted to labels. Use quotes when a label would otherwise be a typed literal.

Accepted values are checked through execution plans, canonical graph digests, serialization and authorized projection. Four standalone conformance checks replay the audit counterexamples, direct planning and accepted value preservation. Compiler isolation and frontend integration tests cover all three frontends and independent workflows. The historical [system audit](audits/2026-10-20-system-roadmap/README.md) remains immutable.

Full expression rendering, arbitrary map keys/default secret names and richer approval message expressions require a separate canonical representation change. A-02 and A-03 still require separate typed data-operation and global-initialization designs. AR-06 remains active, AR-07 remains planned and EF-09 remains paused. This correction adds no adapter capability, signed scenario or support promotion.
