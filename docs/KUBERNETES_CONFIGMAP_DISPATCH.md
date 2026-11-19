# Commission a conditional ConfigMap update

This external provider applies the reviewed text data to one existing ConfigMap and produces an API-server audit ID. A second identity reads the native audit record and the current object. The installed compiler can then check both what the server committed and what is present at observation time.

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

The supported Intent 3.0 workflow contains one literal `APPROVE` followed by one `SYNC` requiring that approval. The sync's three parameters are its `destination`, `mode: "replace-text-data"`, and `source: "sha256:<digest>"`. The destination has `scope: "<commissioned-cluster>/<namespace>"`, `type: "configmap"` and the exact object name as its key. The source digest covers the **exact bytes** of an operator-supplied UTF-8 JSON file, for example `{"data":{"mode":"reviewed"}}`. Extra or omitted bytes, including a trailing newline, affect the digest. The maximum source size is 64 KiB. The entire text data map is replaced, so keys absent from the source are removed. An empty map clears all text keys. Binary data and unrelated metadata are carried through from the observed revision.

Configure the [planning dispatch host](PLANNING_DISPATCH_PRECONDITIONS.md) with the runtime argv below and its own trusted precondition public key. Provision both provider configs, the approval, source files, CA and credential files outside model and discovery grants. Separate the writer and observer's filesystem/key custody as well as their Kubernetes permissions. The test host provisions both roles locally for reproducibility.

```sh
python3 /path/to/FlowAi/tools/kubernetes_configmap_dispatch.py runtime --config /private/writer/config.json
```

| Writer config | Value |
| --- | --- |
| `server`, `caFile`, `tokenFile` | Fixed HTTPS API origin, trusted CA PEM and private bearer-token file. |
| `cluster`, `namespace`, `name` | Operator-defined cluster identity bound to that origin, namespace and named ConfigMap. |
| `keyId`, `signingKey` | Precondition trust grant ID and private Ed25519 PEM. |
| `proposal`, `requirements`, `source` | Exact reviewed proposal, independent precondition contract and content-addressed desired-data file. |
| `grant`, `reservationDirectory` | Operator-owned approval JSON and an existing durable private directory shared by every invocation of this runtime identity. |

The grant's `pins` object contains `dispatchId`, `runtimeId`, `authorizationRef`, `candidateId`, `proposalSha256`, `canonicalPlanSha256`, `fidelityReviewSha256` and `compilerDigest`. Verify these against the selected proposal, retained fidelity review and canonical-plan artifact. At the top level also supply `graphDigest`, the selected canonical `nodeId`, `contractSha256` of the exact precondition document, the current `uid`, `resourceVersion`, `owner` from the `horistum.com/owner` label, UTC `expiresAt` and `deadlineSemantics: "submission"`. Grant GET and UPDATE for only this named ConfigMap. Commission an existing mutable object. A single precondition must cover its destination. Available facts are the opaque `resourceVersion` and `dataSha256`, computed over sorted, compact UTF-8 JSON of the text map followed by a newline.

The runtime durably reserves the dispatch ID, signs the actual GET projection and waits for the compiler's assessment. It rechecks the external grant and time window before submitting one PUT with the observed UID and resourceVersion. The server can reject a conflicting update even after the compiler returned SATISFIED. Grant revocation and expiry apply at submission. Kubernetes does not atomically check the external clock or grant at commit. A missing or ambiguous response leaves the dispatch reserved for manual reconciliation. Never remove the reservation to replay. [Kubernetes API resource versions](https://kubernetes.io/docs/reference/using-api/api-concepts/#resource-versions)

## Independently verify a completed dispatch

Predeclare the [outcome policy and independent trust](PLANNING_DISPATCH_OUTCOMES.md). Select the same SYNC destination and managed owner. Use both `committedDataSha256` and `dataSha256` as postconditions to require the intended data in the server's successful response and in the observer's subsequent GET. A reviewer can also ask about just one of those points in time.

On the independent audit host, configure the same fixed API address, cluster, namespace and name. Set a GET-only `tokenFile`, a separate Ed25519 `signingKey` and `keyId`, the granted `runtimeId`, the writer's authenticated Kubernetes `writerUsername` and an absolute `auditFile`. Provide a stable, complete NDJSON export from that API server's trusted audit sink (up to 16 MiB, individual events up to the 2 MB host bound). Protect it from the writer. The audit policy must retain RequestResponse-level records for updates of this named ConfigMap by the writer. Narrow the policy to avoid collecting unrelated object data. See the [disposable policy and RBAC](../tools/tests_worker/kubernetes_cluster.py) and [Kubernetes auditing](https://kubernetes.io/docs/tasks/debug/debug-cluster/audit/).

```sh
# Use the request and assess commands from the dispatch outcome review instructions.
python3 /path/to/FlowAi/tools/kubernetes_configmap_dispatch.py observer \
  --config /private/audit/config.json < /private/audit/request.json > /private/audit/observations.json
```

Use the genuine, independently recovered reviewer request as input. The collector requires one successful ResponseComplete update for the receipt's audit ID, exact request URI, configured writer, object UID, changed revision and correlation annotation on both the submitted and returned objects. The annotation binds the dispatch, runtime, node, graph, resource, assessment time and request/decision digests. The collector also reconstructs the completed-result challenge and checks the returned UID against its own GET. It signs bounded projections with hashes instead of copying the ConfigMap contents into evidence. A valid empty export, absent event, ambiguous receipt or replacement object yields no observations. A malformed or truncated export or unavailable API fails collection; discard incomplete output and obtain a new capture. A later change to the same UID can produce a violated current-state postcondition even when the original commit matched.

The test covers a real API server and native revision conflict. Production key custody, audit provenance and completeness, storage durability, live model quality and cross-domain portfolio acceptance require separate evidence.
