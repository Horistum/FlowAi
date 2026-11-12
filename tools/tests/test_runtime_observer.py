"""Versioned allocated-instance capture keeps the existing durable event semantics."""
import base64
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from authoring_host.contracts import HostError, canonical, digest
from observation_host import journal, session
import test_observation_host as fixtures
START, END, CLOSED = fixtures.START, fixtures.END, fixtures.CLOSED


class RuntimeObserverTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures.ObservationHostTests.setUpClass()

    @classmethod
    def tearDownClass(cls):
        fixtures.ObservationHostTests.tearDownClass()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "journal"
        self.resource = dict(scope="isolated", type="database", key="allocated-17")
        self.req = dict(formatVersion=3, graphDigest="a" * 64, runId="run", challenge="b" * 32, startedAt=START,
            execution=dict(workflow="main"), resources=[dict(id="database", disposition="CREATED_IN_RUN", owner="tenant",
                allocation=dict(scope="isolated", type="database", binding="created", path=["handle"], keyPrefix="allocated-"))])
        self.review = dict(formatVersion=3, graphDigest="a" * 64, runId="run", state="UNRESOLVED", executionAuthorized=False,
            requirementsSource=dict(sha256=digest(canonical(self.req))), results=[dict(id="make", nodeId="node", resource=None)],
            execution=dict(invocationTargets=[dict(nodeId="node", capability="PROVISION", outputs=["created"])]))
        self.tip = journal._create(self.directory, "intent", b"{}", canonical(self.req), canonical(self.review), "f" * 64)
        self.trust = dict(formatVersion=3, grants=[dict(keyId="observer", publicKey=base64.b64encode(fixtures.ObservationHostTests.public).decode(),
            scopes=["isolated"], resourceTypes=["database"], kinds=["ALLOCATION", "RESULT", "CREATION", "OWNERSHIP"], workflows=["main"])])

    def append(self, kind, data, capture=b"capture", version=2):
        event = journal.event_for(self.tip, "event-" + str(self.tip["sequence"] + 1), kind, data, capture, version)
        receipt = journal.append(self.directory, canonical(event), capture)
        self.tip = receipt
        return event

    def completed(self, status="SUCCEEDED"):
        self.append("START", dict(invocationId="create-1", nodeId="node", attempt=1, startedAt=START), b"")
        self.append("FINISH", dict(invocationId="create-1", completedAt=END, status=status,
            **({"errorId": "partial"} if status == "FAILED" else {})))

    def claim(self, **overrides):
        return dict(dict(id="allocation", kind="ALLOCATION", status="SUCCEEDED", nodeId="node", invocationId="create-1",
            resourceId="database", resource=self.resource, instanceId="instance-17", observedAt=END, binding="created", path=["handle"]), **overrides)

    def test_capture_is_versioned_and_exact_replay_preserves_receipt(self):
        self.completed()
        event = self.append("OBSERVATION", self.claim())
        receipt = self.tip.copy()
        self.append("CLOSE", dict(completedAt=CLOSED, status="SUCCEEDED"))
        self.assertEqual(receipt, journal.append(self.directory, canonical(event), b"capture"))
        self.assertEqual(1, journal.snapshot(self.directory)["observationCount"])

    def test_v1_event_cannot_enter_allocated_session(self):
        with self.assertRaises(HostError):
            self.append("START", dict(invocationId="first", nodeId="node", attempt=1, startedAt=START), b"", version=1)

    def test_conflicting_allocation_is_not_appended(self):
        self.completed()
        self.append("OBSERVATION", self.claim())
        for fields in (dict(instanceId="replacement"), dict(resource=dict(self.resource, key="allocated-other"))):
            with self.assertRaises(HostError):
                self.append("OBSERVATION", self.claim(id="conflict", **fields))
        self.assertEqual(1, journal.snapshot(self.directory)["observationCount"])

    def test_unavailable_instance_can_be_captured_without_inventing_one(self):
        self.completed()
        claim = self.claim(); del claim["instanceId"]
        self.append("OBSERVATION", claim)
        self.assertNotIn("instanceId", journal.snapshot(self.directory)["_history"].claims[0][0])

    def test_unknown_resource_and_scope_are_rejected(self):
        self.completed()
        for fields in (dict(resourceId="other"), dict(resource=dict(self.resource, scope="other")), dict(resource=dict(self.resource, type="other"))):
            with self.assertRaises(HostError):
                self.append("OBSERVATION", self.claim(**fields))

    def test_allocation_requires_successful_completed_declared_output(self):
        self.completed("FAILED")
        with self.assertRaises(HostError):
            self.append("OBSERVATION", self.claim())
        # Actual partial creation remains capturable without manufacturing a successful output.
        claim = self.claim(kind="CREATION", created=True)
        del claim["binding"]; del claim["path"]
        self.append("OBSERVATION", claim)

    def test_newly_allocated_ownership_can_be_observed_at_creation(self):
        self.completed()
        claim = self.claim(kind="OWNERSHIP", owner="tenant")
        del claim["binding"]; del claim["path"]
        self.append("OBSERVATION", claim)

    def test_signer_uses_distinct_version3_domain_and_allocation_grant(self):
        self.completed()
        self.append("OBSERVATION", self.claim())
        claims = journal.snapshot(self.directory)["_history"].claims
        signer = session.Signer(fixtures.ObservationHostTests.key, "observer", canonical(self.trust), self.req, claims)
        original = session.invoke
        messages = []
        def invoke(argv, *args):
            if "pkeyutl" in argv:
                messages.append(Path(argv[argv.index("-in") + 1]).read_bytes())
            return original(argv, *args)
        with patch.object(session, "invoke", side_effect=invoke):
            signer.sign(dict(formatVersion=3), b"capture")
            signer.sign(dict(formatVersion=3), b"capture", run=True)
        self.assertTrue(messages[0].startswith(b"Horistum operation observation\0v3\0"))
        self.assertTrue(messages[1].startswith(b"Horistum operation run observation\0v3\0"))
        self.trust["grants"][0]["kinds"].remove("ALLOCATION")
        with self.assertRaises(HostError):
            session.Signer(fixtures.ObservationHostTests.key, "observer", canonical(self.trust), self.req, claims)

    def test_unknown_fields_and_null_instance_are_rejected(self):
        self.completed()
        for extra in (dict(instanceId=None), dict(allocated=True)):
            with self.assertRaises(HostError):
                self.append("OBSERVATION", self.claim(**extra))
