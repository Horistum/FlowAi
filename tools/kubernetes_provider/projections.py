"""Closed set of reviewed resource projections. No caller-supplied API paths or field expressions."""
from dataclasses import dataclass
import re
from typing import Callable

from authoring_host.contracts import canonical, digest, keys, require


def text_map(value):
    require(isinstance(value, dict) and all(re.fullmatch(r"[-a-zA-Z0-9_.]{1,253}", key) and isinstance(v, str)
            for key, v in value.items()), "Expected ConfigMap text data")
    return value


def replicas(value):
    require(type(value) is int and 0 <= value <= 2**31 - 1, "Expected a nonnegative int32 replica count")
    return value


@dataclass(frozen=True)
class Projection:
    api_version: str
    kind: str
    plural: str
    path: tuple[str, ...]
    fact: str
    committed_fact: str
    validate: Callable
    summarize: Callable
    modes: tuple[str, ...]
    absent_value: Callable | None = None

    @property
    def resource_type(self):
        return self.kind.lower()

    def desired(self, source):
        key = self.path[-1]
        keys(source, (key,))
        return self.validate(source[key])

    def parent(self, obj):
        for key in self.path[:-1]:
            obj = obj.get(key)
            require(isinstance(obj, dict), "Object lacks the commissioned field parent")
        return obj

    def observed(self, obj):
        container = self.parent(obj)
        value = container.get(self.path[-1])
        if self.path[-1] not in container and self.absent_value is not None:
            value = self.absent_value()
        return self.summarize(self.validate(value))

    def replace(self, obj, value):
        self.parent(obj)[self.path[-1]] = value

    def outcome_facts(self, committed, current):
        return {self.fact: self.observed(current), self.committed_fact: self.observed(committed)}


CONFIGMAP = Projection(
    api_version="v1", kind="ConfigMap", plural="configmaps", path=("data",),
    fact="dataSha256", committed_fact="committedDataSha256", validate=text_map,
    summarize=lambda data: digest(canonical(data)), modes=("replace", "replace-text-data"), absent_value=dict,
)
DEPLOYMENT = Projection(
    api_version="apps/v1", kind="Deployment", plural="deployments", path=("spec", "replicas"),
    fact="replicas", committed_fact="committedReplicas", validate=replicas, summarize=int, modes=("replace",),
)
PROFILES = {"configmap-text": CONFIGMAP, "deployment-replicas": DEPLOYMENT}
