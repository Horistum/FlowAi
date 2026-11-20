"""Built-in compatibility projections and operator-owned, content-addressed field declarations."""
from dataclasses import dataclass
import re
from typing import Callable

from authoring_host.contracts import canonical, digest, keys, loads, require
from authoring_host.inventory_provenance import read_regular


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
    profile_sha: str | None = None

    @property
    def resource_type(self):
        return self.kind.lower()

    def claim_id(self, suffix):
        return f"{self.resource_type}-{suffix}"

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

    def before_facts(self, obj):
        return {self.fact: self.observed(obj)}

    def outcome_facts(self, committed, current):
        return {self.fact: self.observed(current), self.committed_fact: self.observed(committed)}


class DeclaredProjection(Projection):
    @property
    def resource_type(self):
        group, _, _ = self.api_version.rpartition("/")
        return f"{self.plural}.{group}" if group else self.plural

    def claim_id(self, suffix):
        # The compiler bounds observation IDs independently of Kubernetes group names.
        return f"resource-{suffix}"

    def desired(self, source):
        keys(source, ("profileSha256", "value"))
        require(source["profileSha256"] == self.profile_sha, "Source names another field declaration")
        return self.validate(source["value"])

    def before_facts(self, obj):
        container = self.parent(obj)
        leaf = self.path[-1]
        facts = dict(profileSha256=self.profile_sha, valuePresent=leaf in container)
        if leaf in container:
            facts[self.fact] = self.summarize(self.validate(container[leaf]))
        return facts

    def outcome_facts(self, committed, current):
        facts = dict(profileSha256=self.profile_sha)
        for obj, presence, value_fact in ((committed, "committedValuePresent", self.committed_fact),
                                          (current, "valuePresent", self.fact)):
            for key in self.path:
                if not isinstance(obj, dict) or key not in obj:
                    facts[presence] = False
                    break
                obj = obj[key]
            else:
                facts[presence] = True
                facts[value_fact] = self.summarize(obj)
        return facts


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


_LABEL = re.compile(r"[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?")
_DNS_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
_FIELD_TYPES = {"boolean": bool, "integer": int, "string": str, "object": dict, "array": list}


def declared_projection(raw):
    doc = loads(raw)
    keys(doc, ("formatVersion", "apiVersion", "kind", "plural", "fieldPath", "valueType"))
    require(type(doc["formatVersion"]) is int and doc["formatVersion"] == 1, "Unsupported field declaration")
    api_version = doc["apiVersion"]
    require(isinstance(api_version, str), "Invalid API version")
    group, sep, version = api_version.rpartition("/")
    require(_LABEL.fullmatch(version) and
            (not sep or len(group) <= 253 and all(_DNS_LABEL.fullmatch(part) for part in group.split("."))),
            "Invalid API group or version")
    kind, plural, path = doc["kind"], doc["plural"], doc["fieldPath"]
    require(isinstance(kind, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9]{0,62}", kind) and
            isinstance(plural, str) and _LABEL.fullmatch(plural), "Invalid resource kind or plural")
    require(isinstance(path, list) and 1 <= len(path) <= 32 and all(
            isinstance(key, str) and re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_.-]{0,252}", key) for key in path)
            and path[0] not in {"apiVersion", "kind", "metadata", "status"}, "Invalid writable field path")
    name = doc["valueType"]
    require(isinstance(name, str) and name in _FIELD_TYPES, "Unsupported field type")

    def validate(value):
        require(type(value) is _FIELD_TYPES[name], "Field value does not match its declared JSON type")
        return value

    return DeclaredProjection(
        api_version=api_version, kind=kind, plural=plural, path=tuple(path),
        fact="valueSha256", committed_fact="committedValueSha256", validate=validate,
        summarize=lambda value: digest(canonical(value)), modes=("replace",), profile_sha=digest(raw),
    )


def load_projection(config):
    require(("profile" in config) != ("profileFile" in config), "Select exactly one resource profile")
    if "profileFile" in config:
        return declared_projection(read_regular(config["profileFile"], 16384))
    name = config["profile"]
    require(isinstance(name, str) and name in PROFILES, "Unknown built-in profile")
    return PROFILES[name]
