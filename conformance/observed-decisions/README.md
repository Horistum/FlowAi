# Observed lifecycle decisions

These are manually authored public development fixtures, not model evaluation.
Caller inputs are the request, independent requirements and allowed inventory root.
The decision, bindings and responses are scripted provider outputs, not caller hints.
`tools/verify_discovery_decisions.py` randomizes addresses and filenames and tests two
source layouts through the real installed compiler.

| Observed state | Selected obligations | Rejected counterexample |
| --- | --- | --- |
| Existing shared network | Reuse; bounded readiness; no creation or deletion | Old create plan, unrelated verification resource |
| Explicitly absent, unallocated network | Successful allocation before protected readiness; cleanup consumes that allocation | Delete shared address, move allocation inside protected region, flatten cleanup into happy path |
| Isolated database at revision 41 | Backup before migration; restore that backup on failure; bounded verification | Inspect-only plan, unrelated recovery point |
| Isolated database at revision 42 | Verify without backup, migration or restore | Old migration plan |
| Partial allocation, unknown revision or conflicting evidence | Block decision, preserve evidence | Treat incomplete state as absence or choose a default case |

The infrastructure fixture deliberately places creation outside the protected region.
Its output is available to readiness and cleanup only after successful creation. This
proves plan structure and value continuity, not create-only semantics in a provider,
remote ownership, successful cleanup or safe handling of a partially failed allocation.
Those require executor preconditions and runtime evidence. The full network/identity/
compute lifecycle remains beyond this bounded proof.

Selected alternatives add ordinary requirements-v5 checks; they do not replace the
caller's independent prohibitions. Unknown types and unsupported states do not become
an implicit else branch. No production code branches on these fixture domains.
