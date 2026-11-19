"""Focused regressions for commissioning boundaries common to both resource profiles."""
from copy import deepcopy
from datetime import timedelta
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host.contracts import HostError, canonical, digest
from kubernetes_provider.common import instant
from kubernetes_provider.projections import CONFIGMAP, DEPLOYMENT
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
