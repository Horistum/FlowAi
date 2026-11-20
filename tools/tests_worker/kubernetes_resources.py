"""Operator inputs, independent expected values and server fixtures for the installed commissioning gate."""
from dataclasses import dataclass

from authoring_host.contracts import canonical, digest


@dataclass(frozen=True)
class Scenario:
    kind: str
    name: str
    group: str
    plural: str
    api_version: str
    version: str
    profile: str | dict
    field: tuple[str, ...]
    initial: dict
    desired: object
    competing: object
    actor_prefix: str
    legacy: bool = False

    @property
    def profile_sha(self):
        return digest(canonical(self.profile)) if isinstance(self.profile, dict) else None

    @property
    def address(self):
        resource = self.plural + ("." + self.group if self.group else "")
        return resource + "/" + self.name

    @property
    def identity(self):
        resource = self.address.split("/")[0] if self.profile_sha else self.kind.lower()
        return dict(scope="loopback/commissioning", type=resource, key=self.name)

    @property
    def mode(self):
        return "replace-text-data" if self.legacy else "replace"

    @property
    def source(self):
        if self.profile_sha:
            return dict(profileSha256=self.profile_sha, value=self.desired)
        return {self.field[-1]: self.desired}

    @property
    def expected(self):
        value_sha = digest(canonical(self.desired))
        if self.profile_sha:
            return dict(profileSha256=self.profile_sha, valuePresent=True, committedValuePresent=True,
                        valueSha256=value_sha, committedValueSha256=value_sha)
        if self.legacy:
            return dict(dataSha256=value_sha, committedDataSha256=value_sha)
        return dict(replicas=self.desired, committedReplicas=self.desired)

    @property
    def competitor(self):
        patch = self.competing
        for key in reversed(self.field):
            patch = {key: patch}
        return patch


CONTAINERS = [dict(name="worker", image="example.invalid/worker:1")]
CONFIG = Scenario(
    kind="ConfigMap", name="settings", group="", plural="configmaps", api_version="v1", version="3.0",
    profile="configmap-text", field=("data",), actor_prefix="", legacy=True,
    initial=dict(data=dict(mode="before", obsolete="remove"), binaryData=dict(retained="YQ==")),
    desired=dict(mode="reviewed"), competing=dict(mode="concurrent"),
)
SCALE = Scenario(
    kind="Deployment", name="workers", group="apps", plural="deployments", api_version="apps/v1", version="2.0",
    profile="deployment-replicas", field=("spec", "replicas"), actor_prefix="replica-",
    initial=dict(spec=dict(replicas=1, selector=dict(matchLabels=dict(app="workers")),
        template=dict(metadata=dict(labels=dict(app="workers")), spec=dict(containers=CONTAINERS)))),
    desired=3, competing=2,
)
PAUSE = Scenario(
    kind="CronJob", name="nightly", group="batch", plural="cronjobs", api_version="batch/v1", version="3.0",
    profile=dict(formatVersion=1, apiVersion="batch/v1", kind="CronJob", plural="cronjobs",
                 fieldPath=["spec", "suspend"], valueType="boolean"),
    field=("spec", "suspend"), actor_prefix="cron-",
    initial=dict(spec=dict(schedule="0 1 * * *", suspend=False, jobTemplate=dict(spec=dict(
        template=dict(spec=dict(restartPolicy="Never", containers=CONTAINERS)))))),
    desired=True, competing=False,
)
RETENTION = Scenario(
    kind="ArchivePolicy", name="records", group="records.example.test", plural="archivepolicies",
    api_version="records.example.test/v1", version="3.0",
    profile=dict(formatVersion=1, apiVersion="records.example.test/v1", kind="ArchivePolicy",
                 plural="archivepolicies", fieldPath=["spec", "retention", "windows"], valueType="array"),
    field=("spec", "retention", "windows"), actor_prefix="archive-",
    initial=dict(immutable=True, spec=dict(retention=dict(fallback=["90d", "365d"]), storage="audit-store")),
    desired=["90d", "365d"], competing=["180d"],
)
PUBLISH = Scenario(
    kind="Service", name="collectors", group="", plural="services", api_version="v1", version="3.0",
    profile=dict(formatVersion=1, apiVersion="v1", kind="Service", plural="services",
                 fieldPath=["spec", "publishNotReadyAddresses"], valueType="boolean"),
    field=("spec", "publishNotReadyAddresses"), actor_prefix="service-",
    initial=dict(spec=dict(selector=dict(app="collector"), ports=[dict(name="syslog", port=601, protocol="TCP")])),
    desired=True, competing=False,
)
CASES = (CONFIG, SCALE, PAUSE, RETENTION, PUBLISH)


def install_archive_crd(cluster):
    case = RETENTION
    strings = dict(type="array", items=dict(type="string"))
    retention = dict(type="object", properties=dict(windows=strings, fallback=strings))
    spec = dict(type="object", properties=dict(storage=dict(type="string"), retention=retention))
    schema = dict(openAPIV3Schema=dict(type="object", properties=dict(spec=spec, immutable=dict(type="boolean"))))
    name = case.plural + "." + case.group
    cluster.create(dict(apiVersion="apiextensions.k8s.io/v1", kind="CustomResourceDefinition",
        metadata=dict(name=name), spec=dict(group=case.group, scope="Namespaced", names=dict(
            plural=case.plural, singular="archivepolicy", kind=case.kind),
            versions=[dict(name="v1", served=True, storage=True, schema=schema)])))
    cluster.kubectl("wait", "--for=condition=Established", "--timeout=5s", "crd/" + name)
