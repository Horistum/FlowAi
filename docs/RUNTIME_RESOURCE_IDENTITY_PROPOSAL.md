# Runtime-allocated resource identities: bounded contract proposal

Status: historical design prepared with AI-A3-S2.7 and implemented as the bounded
[version 3 contract](RUNTIME_RESOURCE_IDENTITIES.md) in AI-A3-S2.8. Version 2 still requires
exact independently declared identities before capture. The original design below records
the representability gap and acceptance boundary; the implemented contract owns wire fields,
signing domains, migration and diagnostic behavior.

## Concrete representability gap

An external provision operation creates an isolated database and returns a provider-assigned
identifier. A later verifier must consume that exact database instance. Today S2.6 can prove
the producer/consumer relationship only when the full scoped identity was frozen beforehand.
Replacing the frozen requirements with the returned address would change the acceptance
contract after work and invalidate its digest. Matching an address without an instance
generation can also mistake a deleted-and-recreated resource for the original instance.

## Proposed separation

Keep one canonical graph, its VALUE edges and the existing invocation trace. Introduce a
future outcome contract version for allocation obligations; do not reinterpret version 2.

| Concern | Frozen before execution | Supplied by independently authorized observation |
| --- | --- | --- |
| Logical resource | Stable requirement ID, exact managed scope/type, new-owned disposition | No change to the logical obligation |
| Allocation source | Unique creator selector and declared output/path | Exact successful creator invocation and returned identity |
| Identity constraints | Bounded typed predicates allowed by the future schema | Concrete key and instance/generation evidence satisfying every predicate |
| Consumer | Existing parameter/path and canonical VALUE dependency | Producer available at that invocation and the same resource instance |
| Completion | Independent postconditions and allowed effects | Fresh state of the resolved instance after run closure |

Initial implementation should admit only newly created resources in the existing sequential
trace subset, with exact frozen scope/type and one declared allocation slot. Exclude import,
workflow inputs, aliases, branch merges, parallelism, nested recovery and automatic replacement
until separately modeled. No arbitrary regular-expression or executable predicate language is
needed. A provider's returned ID is data requiring an authorized observation, not proof by itself.

## Required identity and trust rules

1. Bind every allocation assertion to the original requirements bytes, canonical graph,
   run ID, challenge, exact invocation, output and structured path. Give allocation an
   explicit signing grant distinct from ordinary successful RESULT observations.
2. Freeze the obligation, not the future address. Resolution adds evidence to that obligation;
   it never rewrites requirements, expands scope or changes the requested outcome.
3. Permit one immutable resolution per allocation slot. Identical late redelivery is
   idempotent. A conflicting address or generation is contradictory evidence, even if one
   candidate satisfies postconditions. Never choose the convenient candidate.
4. Keep allocation, creation, ownership and final state separate. A returned handle does
   not establish creation or cleanup permission. A failed creator may leave partial resources;
   record them as unresolved side effects, without publishing a successful output binding.
5. Identify the resource instance as well as its address. Require an independently captured
   stable provider instance/generation identifier, or explicitly remain unresolved where it
   cannot be established. Never synthesize instance identity from a path or observation time.
6. Preserve each consumer's actual producer lineage, including failed attempts. A later
   successful resolution cannot repair an earlier consumer's missing or incorrect binding.
7. Resource replacement requires an explicit future lifecycle relation and new instance
   identity. It must not silently mutate the original allocation. Cleanup continues to require
   independently established ownership and separately authorized execution.
8. Extend the existing durable observer event/history boundaries and independent retention;
   do not introduce a second planner, allocator or operation dispatcher in the Python host.

## Migration and acceptance before implementation is complete

Keep versions 1 and 2 unchanged, including their signed payload domains. Give the new contract
an explicit version and domain and publish incompatible-field rejection plus migration examples.
Older evaluators must reject the new version; no coercion back to exact-address requirements is
valid. An absent allocation, unsupported generation evidence or incomplete invocation remains
unresolved; an authenticated out-of-scope or conflicting instance is a violation.

Prove at least two independently composed domains with addresses unknown at planning time:
an allocated local service endpoint with a real instance identity and a newly created database
with its own immutable instance record. Tests must obtain those values from actual operations,
not insert expected addresses after execution. Include:

- valid allocation, consumer lineage, creation, ownership and fresh final state;
- wrong scope/type, wrong producer/output/path, foreign run/challenge and missing grant;
- a changed requirement digest, missing earlier-attempt binding and partial failed creation;
- conflicting late allocation, same address with a different generation, and stale final state;
- interrupted collector/witness delivery, original receipt replay and journal rollback;
- a provider without trustworthy instance evidence, explicitly unresolved.

Open design decisions for that work package: the minimal generation-evidence representation,
which typed key constraints are necessary for concrete requests, and the precise violated versus
unresolved diagnostic mapping. Resolve these against real authored requests before freezing a
new schema. Provider implementation evidence and human execution authorization remain separate.
