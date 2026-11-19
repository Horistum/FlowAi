# Commission a reviewed Kubernetes resource update

The writer applies one approved, content-addressed sync to an existing namespaced object. A second identity correlates a native API audit record with its own GET. Both roles independently load an operator-provisioned declaration that names the API version, resource and writable field. Two built-in profiles also support existing configurations.

## Reproduce on a disposable API server

From the repository root on Linux x86-64, install the planning worker in a virtual environment, build the CLI with JDK 25 and run the same commissioning gate as CI.

```sh
python3 -m venv /tmp/flow-configmap-venv
/tmp/flow-configmap-venv/bin/pip install -r tools/requirements-planning-worker.txt
./gradlew :flow-cli:installDist
scripts/verify_kubernetes_dispatch.sh /tmp/flow-configmap-venv/bin/python flow-cli/build/install/flow-core/bin/flow-core
```

The script verifies the pinned controller-tools envtest archive, starts etcd and kube-apiserver on loopback, installs scoped RBAC and enables blocking JSON auditing for the test writer. It generates disposable credentials and uses an explicit private kubeconfig. For an offline run, pass an existing envtest binary directory to `tools/verify_kubernetes_dispatch.py --cli ... --binaries ...`. The planning peer in this gate is scripted.

## Commission the operation

The writer reads the exact canonical plan and proposal retained by independent review. The plan must contain one unconditional SYNC with a resolved ordering dependency on a manual approval. Extra operations, unsupported bindings or controls, declared outputs that the writer cannot produce, and failure compensation prevent dispatch. Source IDs locate typed operands even when the compiler assigns a different output name. Equivalent flat Intent 2.0 steps and structured Intent 3.0 operations use the same writer.

The SYNC destination contains `scope: "<commissioned-cluster>/<namespace>"`, the declared resource type, and the exact object name as its key. Its `source: "sha256:<digest>"` identifies the **exact bytes** of an operator-supplied UTF-8 JSON file up to 64 KiB. A trailing newline affects this digest. The common mode is `replace`. The original ConfigMap mode `replace-text-data` remains supported for that profile.

| Built-in profile | Destination type and field | Source example | Independent outcome facts |
| --- | --- | --- | --- |
| `configmap-text` | `configmap` → `data` | `{"data":{"mode":"reviewed"}}` | `committedDataSha256`, `dataSha256` |
| `deployment-replicas` | `deployment` → `spec.replicas` | `{"replicas":3}` | `committedReplicas`, `replicas` |

A ConfigMap sync replaces the entire text map, including removing omitted keys. An empty map clears text data. A Deployment sync changes the declared replica count and preserves the pod template and selector. A count of zero is valid. Replica facts describe the API object's desired count. Pod availability and rollout success require observations from workload controllers. Model input can select neither a free-form API path nor a writable field.

### Add a native or custom resource type

For a new kind, supply an absolute `profileFile` in each role's config. Provision a separate copy of the same JSON bytes for the writer and the observer. Here is the declaration for the custom ArchivePolicy used in the gate.

```json
{
  "formatVersion": 1,
  "apiVersion": "records.example.test/v1",
  "kind": "ArchivePolicy",
  "plural": "archivepolicies",
  "fieldPath": ["spec", "retention", "windows"],
  "valueType": "array"
}
```

1. Commission the named resource, its API origin, owner and RBAC. The destination type is the plural followed by the API group, here `archivepolicies.records.example.test`. Core-group resources use their plural alone. For example, an existing CronJob uses `batch/v1`, `CronJob`, `cronjobs`, `["spec","suspend"]` and `boolean`, with destination type `cronjobs.batch`.
2. Hash the exact profile bytes with SHA-256. Supply that lowercase hex digest at the top level of the external grant as `profileSha256`. Create the desired source, for example `{"profileSha256":"<same hex digest>","value":["90d","365d"]}`. Hash the source file's exact bytes for the reviewed SYNC `source`. Use mode `replace`. Changing the field, type or even profile whitespace requires fresh source and approval pins.
3. Predeclare outcome facts for the selected profile. `profileSha256` identifies the declaration. `committedValuePresent` and `valuePresent` distinguish absence from an empty array, empty object, zero or false. When present, `committedValueSha256` and `valueSha256` hash the respective API values. Compute a value digest using sorted, compact JSON encoded as UTF-8 with a trailing LF, as implemented by `authoring_host.contracts.canonical`. For the string-only array in this example, the exact bytes are `["90d","365d"]` followed by LF. A precondition can require `valuePresent: false` when setting an optional leaf for the first time.

A declaration is bounded to 16 KiB. Its path is one to 32 literal object keys. Each key starts with an ASCII letter or underscore and may also contain digits, periods and hyphens. The parent object must exist at dispatch. Values may be booleans, integers, strings, objects or arrays. Replacing an object or array replaces its entire value. Indexes, selectors, wildcards and the root fields `metadata`, `status`, `kind` and `apiVersion` are outside this contract. The API server still enforces the served schema, admission and field mutability. The observer hashes what the server actually committed and what it reads later, including a value rewritten by admission.

### Configure the writer

Configure the [planning dispatch host](PLANNING_DISPATCH_PRECONDITIONS.md) with the runtime argv below and its own trusted precondition public key. Provision both provider configs, the approval, source files, CA and credential files outside model and discovery grants. Separate the writer and observer's filesystem/key custody as well as their Kubernetes permissions. The test host provisions both roles locally for reproducibility.

```sh
python3 /path/to/FlowAi/tools/kubernetes_resource_dispatch.py runtime --config /private/writer/config.json
```

| Writer config | Value |
| --- | --- |
| `profile` or `profileFile` | Select exactly one built-in name or one operator-provisioned JSON file per role. |
| `server`, `caFile`, `tokenFile` | Fixed HTTPS API origin, trusted CA PEM and private bearer-token file. |
| `cluster`, `namespace`, `name` | Operator-defined cluster identity bound to that origin, namespace and named object. |
| `keyId`, `signingKey` | Precondition trust grant ID and private Ed25519 PEM. |
| `canonicalPlan` | Path to the independently reviewed canonical plan. Defaults to `compiler/canonical-execution-plan.json` beside the proposal in the normal review directory. |
| `proposal`, `requirements`, `source` | Exact reviewed proposal, independent precondition contract and content-addressed desired-data file. |
| `grant`, `reservationDirectory` | Operator-owned approval JSON and an existing durable private directory shared by every invocation of this runtime identity. |

The grant's `pins` object contains `dispatchId`, `runtimeId`, `authorizationRef`, `candidateId`, `proposalSha256`, `canonicalPlanSha256`, `fidelityReviewSha256` and `compilerDigest`. Verify these against the selected proposal, retained fidelity review and canonical-plan artifact. At the top level also supply `graphDigest`, the selected canonical `nodeId`, `contractSha256` of the exact precondition document, the current `uid`, `resourceVersion`, `owner` from the `horistum.com/owner` label, UTC `expiresAt` and `deadlineSemantics: "submission"`. Grant GET and UPDATE for this named object in its API group. Commission an existing mutable object. A single precondition must cover its destination. The precondition facts are the opaque `resourceVersion` and the profile's current-state facts. The ConfigMap data digest covers the sorted, compact UTF-8 JSON text map followed by a newline. Replica counts are JSON integers.

Existing ConfigMap commands can continue to use `tools/kubernetes_configmap_dispatch.py` without a profile setting. That entry point accepts only the original ConfigMap profile. If a writer was provisioned with a standalone proposal, also provide its reviewed canonical plan and set `canonicalPlan` to the absolute path. Its digest must equal the value already pinned by the grant.

The runtime durably reserves the dispatch ID, signs the actual GET projection and waits for the compiler's assessment. It rechecks the external grant and time window before submitting one PUT with the observed UID and resourceVersion. The server can reject a conflicting update even after the compiler returned SATISFIED. Grant revocation and expiry apply at submission. Kubernetes does not atomically check the external clock or grant at commit. A missing or ambiguous response leaves the dispatch reserved for manual reconciliation. Never remove the reservation to replay. [Kubernetes API resource versions](https://kubernetes.io/docs/reference/using-api/api-concepts/#resource-versions)

## Independently verify a completed dispatch

Predeclare the [outcome policy and independent trust](PLANNING_DISPATCH_OUTCOMES.md). Select the same SYNC destination and managed owner. Use both of the selected profile's outcome facts to require the intended value in the server's successful response and in the observer's subsequent GET. A reviewer can also ask about just one of those points in time.

On the independent audit host, configure the same fixed API address, cluster, namespace and name. Set a GET-only `tokenFile`, a separate Ed25519 `signingKey` and `keyId`, the granted `runtimeId`, the writer's authenticated Kubernetes `writerUsername` and an absolute `auditFile`. Provide a stable, complete NDJSON export from that API server's trusted audit sink (up to 16 MiB, individual events up to the 2 MB host bound). Protect it from the writer. The audit policy must retain RequestResponse-level records for updates of this named resource by the writer. Narrow the policy to avoid collecting unrelated object data. See the [disposable policy and RBAC](../tools/tests_worker/kubernetes_cluster.py) and [Kubernetes auditing](https://kubernetes.io/docs/tasks/debug/debug-cluster/audit/).

```sh
# Use the request and assess commands from the dispatch outcome review instructions.
python3 /path/to/FlowAi/tools/kubernetes_resource_dispatch.py observer \
  --config /private/audit/config.json < /private/audit/request.json > /private/audit/observations.json
```

Use the genuine, independently recovered reviewer request as input. The collector requires one successful ResponseComplete update for the receipt's audit ID, exact request URI, configured writer, object UID, changed revision and correlation annotation on both the submitted and returned objects. The annotation binds the dispatch, runtime, node, graph, resource, assessment time and request/decision digests. For a file-backed declaration it also binds the exact profile digest. An observer provisioned for another field of the same kind cannot reuse that receipt. The collector also reconstructs the completed-result challenge and checks the returned UID against its own GET. It signs only the selected facts and the audit and object identifiers. A valid empty export, absent event, ambiguous receipt or replacement object yields no observations. A malformed or truncated export or unavailable API fails collection; discard incomplete output and obtain a new capture. A later change to the same UID can produce a violated current-state postcondition even when the original commit matched.

The commissioning gate exercises the public CLIs for ConfigMap, Deployment, CronJob and a namespaced ArchivePolicy CRD, distinct writer and observer identities per resource, native revision conflicts and subsequent drift. Production key custody, audit provenance and completeness, storage durability, live model quality and cross-domain portfolio acceptance require separate evidence.
