"""Focused regressions for commissioning boundaries common to both resource profiles."""
from copy import deepcopy
from datetime import timedelta
from pathlib import Path
from tempfile import TemporaryDirectory
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host.contracts import HostError, canonical, digest
from kubernetes_provider.common import instant
from kubernetes_provider.projections import CONFIGMAP, DEPLOYMENT, declared_projection, load_projection
from kubernetes_provider.selection import sync_node


def plan():
    approval = dict(id="gate-1", sourceId="ticket", resultName="ticket", kind="approval", dependencies=[], outputs=["ticket"],
                    inputs=dict(mode="manual", message="Approve rehearsal"), targetHints={}, assumptions=[])
    sync = dict(id="task-9", sourceId="worker-capacity", resultName="capacity", kind="task", semanticCapability="SYNC", dependencies=["gate-1"],
                module="standard", action="execute", target="standard", targetHints={}, assumptions=[], outputs=["capacity"],
                destructive=False, effectModel=[dict(domain="INFRASTRUCTURE_STATE", operation="UPSERT",
                sourceCapability="SYNC", resource="desired.state")])
    return dict(planVersion="2.4", controlDecision=dict(status="ALLOWED"), inputs=[], triggers=[], assumptions=[],
                targetHints={}, controlRequirements=[], sourceIntent=dict(policies=[], systems=[],
                failure=dict(notify=False, rollback=False, stopOnError=True)), nodes=[approval, sync],
                dependencyRelations=[dict(sourceNodeId="gate-1", targetNodeId="task-9", kind="ORDERING", resolution="RESOLVED")])


class ReviewedSyncTests(unittest.TestCase):
    def test_selects_the_compiled_task_by_meaning_and_resolved_edge(self):
        artifact = plan()
        artifact["nodes"].reverse()
        self.assertEqual("worker-capacity", sync_node(artifact)["sourceId"])

    def test_rejects_unimplemented_semantics(self):
        mutations = (
            lambda p: p["nodes"].append(deepcopy(p["nodes"][1])),
            lambda p: p["nodes"][0]["inputs"].update(mode="automatic"),
            lambda p: p["nodes"][1].update(kind="conditional"),
            lambda p: p["nodes"][1].update(module="external"),
            lambda p: p["nodes"][1].update(dependencies=[]),
            lambda p: p["nodes"][1]["outputs"].append("external-result"),
            lambda p: p["dependencyRelations"][0].update(resolution="UNRESOLVED"),
            lambda p: p["dependencyRelations"][0].update(kind="VALUE"),
            lambda p: p["dependencyRelations"][0].update(sourceNodeId="another"),
            lambda p: p["nodes"][1]["effectModel"][0].update(operation="DELETE"),
            lambda p: p["sourceIntent"]["failure"].update(rollback=True),
            lambda p: p["controlRequirements"].append(dict(id="another-approval")),
            lambda p: p["controlDecision"].update(status="BLOCKED"),
        )
        for change in mutations:
            artifact = plan()
            change(artifact)
            with self.subTest(artifact=artifact), self.assertRaises(HostError):
                sync_node(artifact)

    def test_replica_contract_rejects_coercion_and_extra_fields(self):
        for value in (False, True, "3", 1.0, -1, 2**31):
            with self.subTest(value=value), self.assertRaises(HostError):
                DEPLOYMENT.desired(dict(replicas=value))
        with self.assertRaises(HostError):
            DEPLOYMENT.desired(dict(replicas=2, template={}))
        with self.assertRaises(HostError):
            DEPLOYMENT.observed(dict(spec={}))
        with self.assertRaises(HostError):
            CONFIGMAP.desired(dict(data={"key": 42}))
        self.assertEqual(0, DEPLOYMENT.desired(dict(replicas=0)))
        self.assertEqual(digest(canonical({})), CONFIGMAP.observed({}))

    def test_committed_and_current_replica_facts_are_independent(self):
        committed = dict(spec=dict(replicas=2, template=dict(metadata=dict(name="retain"))))
        live = deepcopy(committed)
        DEPLOYMENT.replace(live, 0)
        self.assertEqual(committed["spec"]["template"], live["spec"]["template"])
        self.assertEqual(dict(replicas=0, committedReplicas=2), DEPLOYMENT.outcome_facts(committed, live))

    def test_jvm_nanosecond_deadlines_round_toward_the_inside_of_the_window(self):
        stamp = "2026-11-19T12:00:00.000000001Z"
        self.assertEqual(instant("2026-11-19T12:00:00Z"), instant(stamp))
        self.assertEqual(instant(stamp) + timedelta(microseconds=1), instant(stamp, lower_bound=True))
        self.assertEqual(instant("2026-11-19T12:00:00.100000+00:00"),
                         instant("2026-11-19T12:00:00.100000000Z", lower_bound=True))

class DeclaredFieldTests(unittest.TestCase):
    definition = dict(formatVersion=1, apiVersion="jobs.example.test/v1beta1", plural="queues",
                      kind="Queue", fieldPath=["spec", "settings", "enabled"], valueType="boolean")

    def test_profile_bytes_bind_the_reviewed_source_and_group_qualified_destination(self):
        raw = canonical(self.definition)
        profile = declared_projection(raw)
        self.assertEqual("queues.jobs.example.test", profile.resource_type)
        self.assertEqual("queues", declared_projection(canonical(dict(self.definition, apiVersion="v1"))).resource_type)
        self.assertTrue(profile.desired(dict(profileSha256=digest(raw), value=True)))
        for source in (dict(value=True), dict(profileSha256=digest(raw + b" "), value=True),
                       dict(profileSha256=digest(raw), value=1), dict(profileSha256=digest(raw), value=True, path=[])):
            with self.subTest(source=source), self.assertRaises(HostError):
                profile.desired(source)

    def test_file_selection_is_exclusive_and_builtin_and_legacy_facts_stay_stable(self):
        self.assertIs(CONFIGMAP, load_projection(dict(profile="configmap-text")))
        for config in ({}, dict(profile="unknown"), dict(profile="deployment-replicas", profileFile="/unused")):
            with self.subTest(config=config), self.assertRaises(HostError):
                load_projection(config)
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "profile.json"
            path.write_bytes(canonical(self.definition))
            self.assertEqual(digest(path.read_bytes()), load_projection(dict(profileFile=str(path))).profile_sha)

    def test_untrusted_address_components_and_control_fields_fail_closed(self):
        invalid = (dict(apiVersion="/v1"), dict(apiVersion="example.test/v1?watch"), dict(apiVersion="a/b/v1"),
                   dict(plural="queues/status"), dict(kind="*"), dict(formatVersion=True),
                   dict(valueType="expression"), dict(unknownField=0), dict(fieldPath=[]), dict(fieldPath=["metadata", "uid"]),
                   dict(fieldPath=["apiVersion"]), dict(fieldPath=["kind"]), dict(fieldPath=["status", "ready"]),
                   dict(fieldPath=["spec", "*"]), dict(fieldPath=["spec", 0]))
        for mutation in invalid:
            with self.subTest(mutation=mutation), self.assertRaises(HostError):
                declared_projection(canonical(dict(self.definition, **mutation)))

    def test_fields_are_typed_and_replaced_as_whole_values(self):
        for name, value, wrong in (("string", "", False), ("integer", 0, True), ("object", {}, []),
                                   ("array", [], {}), ("boolean", False, "false")):
            profile = declared_projection(canonical(dict(self.definition, valueType=name)))
            with self.subTest(name=name):
                self.assertEqual(value, profile.desired(dict(profileSha256=profile.profile_sha, value=value)))
                with self.assertRaises(HostError):
                    profile.desired(dict(profileSha256=profile.profile_sha, value=wrong))
                obj = dict(spec=dict(settings=dict(retain=7)))
                self.assertFalse(profile.before_facts(obj)["valuePresent"])
                profile.replace(obj, value)
                facts = profile.outcome_facts(obj, dict(spec={}))
                self.assertTrue(facts["committedValuePresent"])
                self.assertEqual(digest(canonical(value)), facts["committedValueSha256"])
                self.assertFalse(facts["valuePresent"])
                self.assertNotIn("valueSha256", facts)
                self.assertEqual(dict(enabled=value, retain=7), obj["spec"]["settings"])
                with self.assertRaises(HostError):
                    profile.before_facts(dict(spec=dict(settings=[])))
