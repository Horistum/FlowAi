"""Disposable loopback-only API server and etcd. No ambient kubeconfig or cloud credentials."""
from contextlib import ExitStack, contextmanager
import json
from pathlib import Path
import secrets
import socket
import subprocess
import time


@contextmanager
def service(command, log):
    with log.open("wb") as stream, subprocess.Popen(command, stdout=stream, stderr=stream) as process:
        try:
            yield process
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()


class Cluster:
    def __init__(self, work, bins):
        self.work, self.bins = work, bins
        self.tokens = {actor: secrets.token_hex(32) for actor in ("admin", "writer", "observer", "replica-writer", "replica-observer")}

    def kubectl(self, *args, document=None, actor="admin", check=True):
        result = subprocess.run([str(self.bins / "kubectl"), "--kubeconfig", str(self.work / (actor + ".json")),
            "--request-timeout=3s", *args], input=json.dumps(document) if document is not None else None,
            capture_output=True, text=True, timeout=7)
        if check and result.returncode:
            raise RuntimeError("kubectl failed: " + result.stderr[-800:])
        return result

    def create(self, document):
        self.kubectl("create", "-f", "-", document=document)

    def setup(self):
        self.create(dict(apiVersion="v1", kind="Namespace", metadata=dict(name="commissioning")))
        self.create(dict(apiVersion="v1", kind="ConfigMap", metadata=dict(namespace="commissioning", name="settings",
                         labels={"horistum.com/owner": "platform"}, annotations={"example.com/retain": "yes"}),
                         data=dict(mode="before", obsolete="remove"),
                         binaryData=dict(retained="YQ==")))
        self.create(dict(apiVersion="apps/v1", kind="Deployment", metadata=dict(namespace="commissioning", name="workers",
            labels={"horistum.com/owner": "platform"}), spec=dict(replicas=1, selector=dict(matchLabels=dict(app="workers")),
            template=dict(metadata=dict(labels=dict(app="workers")), spec=dict(containers=[dict(name="worker", image="example.invalid/worker:1")])))))
        rbac = "rbac.authorization.k8s.io"
        for actor, group, resource, name in (("writer", "", "configmaps", "settings"), ("observer", "", "configmaps", "settings"),
                ("replica-writer", "apps", "deployments", "workers"), ("replica-observer", "apps", "deployments", "workers")):
            verbs = ["get", "update"] if actor.endswith("writer") else ["get"]
            meta = dict(name=actor, namespace="commissioning")
            self.create(dict(apiVersion=rbac + "/v1", kind="Role", metadata=meta,
                rules=[dict(apiGroups=[group], resources=[resource], resourceNames=[name], verbs=verbs)]))
            self.create(dict(apiVersion=rbac + "/v1", kind="RoleBinding", metadata=meta,
                roleRef=dict(apiGroup=rbac, kind="Role", name=actor),
                subjects=[dict(apiGroup=rbac, kind="User", name=actor)]))


@contextmanager
def start(work, bins):
    cluster = Cluster(work, bins)
    with ExitStack() as sockets:
        listeners = [sockets.enter_context(socket.socket()) for _ in range(3)]
        for sock in listeners:
            sock.bind(("127.0.0.1", 0))
        api_port, client_port, peer_port = [sock.getsockname()[1] for sock in listeners]
    client, peer = [f"http://127.0.0.1:{p}" for p in (client_port, peer_port)]
    cluster.server = f"https://127.0.0.1:{api_port}"
    cert, key = work / "server.crt", work / "server.key"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1",
                    "-keyout", str(key), "-out", str(cert)], capture_output=True, check=True)
    account_key = work / "service-account.key"
    subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-out", str(account_key)], capture_output=True, check=True)
    tokens = work / "tokens.csv"
    tokens.write_text("".join(f'{token},{actor},{actor},"{group}"\n' for actor, token in cluster.tokens.items()
                              for group in ["system:masters" if actor == "admin" else "commissioned"]))
    tokens.chmod(0o600)
    for actor, token in cluster.tokens.items():
        token_file = work / (actor + ".token")
        token_file.write_text(token)
        token_file.chmod(0o600)
        config = dict(apiVersion="v1", kind="Config", clusters=[dict(name="local",
            cluster={"server": cluster.server, "certificate-authority": str(cert)})],
            users=[dict(name=actor, user=dict(tokenFile=str(token_file)))],
            contexts=[dict(name="local", context=dict(cluster="local", user=actor))], **{"current-context": "local"})
        (work / (actor + ".json")).write_text(json.dumps(config))
    policy = work / "audit-policy.json"
    policy.write_text(json.dumps(dict(apiVersion="audit.k8s.io/v1", kind="Policy", omitStages=["RequestReceived"],
        rules=[dict(level="RequestResponse", users=["writer"], verbs=["update"], namespaces=["commissioning"],
                    resources=[dict(group="", resources=["configmaps"], resourceNames=["settings"])]),
               dict(level="RequestResponse", users=["replica-writer"], verbs=["update"], namespaces=["commissioning"],
                    resources=[dict(group="apps", resources=["deployments"], resourceNames=["workers"])])])))
    with ExitStack() as stack:
        stack.enter_context(service([str(bins / "etcd"), "--data-dir", str(work / "etcd"), "--listen-client-urls", client,
            "--advertise-client-urls", client, "--listen-peer-urls", peer, "--initial-advertise-peer-urls", peer,
            "--initial-cluster", "default=" + peer], work / "etcd.log"))
        api = stack.enter_context(service([str(bins / "kube-apiserver"), "--etcd-servers", client,
            "--bind-address=127.0.0.1", "--advertise-address=127.0.0.1", f"--secure-port={api_port}",
            "--service-cluster-ip-range=10.10.0.0/24", "--service-account-issuer=https://localhost",
            "--service-account-key-file", str(account_key), "--service-account-signing-key-file", str(account_key),
            "--authorization-mode=RBAC", "--anonymous-auth=false", "--token-auth-file", str(tokens),
            "--tls-cert-file", str(cert), "--tls-private-key-file", str(key), "--audit-log-mode=blocking",
            "--audit-policy-file", str(policy), "--audit-log-path", str(work / "audit.log")], work / "api.log"))
        deadline = time.monotonic() + 45
        while api.poll() is None and time.monotonic() < deadline:
            if cluster.kubectl("get", "--raw=/readyz", check=False).returncode == 0:
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("API server failed readiness: " + (work / "api.log").read_text()[-1800:])
        cluster.setup()
        yield cluster
