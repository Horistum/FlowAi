"""Real packaged-worker transport with scripted peers; no live provider credentials."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_worker import AppServerWorker, WorkerError, WorkerIndeterminate
from agent_worker.capabilities import inspect_cli
from agent_worker.protocol import ToolInputError, atomic
from authoring_host.contracts import HostError, canonical, loads, read
from authoring_host.planning_worker import PlanningBroker, authority_identity, run, verify_handoff

ROOT = Path(__file__).resolve().parents[2]
PEER = Path(__file__).with_name("planning_peer.py")


def configuration(root, compiler=None):
    inventory = root / "inventory"; inventory.mkdir()
    (inventory / "resources.json").write_text('{"environment":"isolated","endpoint":"test.example"}')
    script = root / "transcript.json"; script.write_text('{}')
    command = [sys.executable, str(PEER), "--transcript", str(script)]
    report = inspect_cli(command)
    return dict(formatVersion=1, planning=dict(formatVersion=1, compiler=dict(
        argv=compiler or [sys.executable, str(ROOT / "tools/tests/fixtures/authoring_peer.py"), "compiler"],
        contracts=str(ROOT)), task=dict(request=dict(userText="Plan isolated recovery without changing production."),
        allowedEnvironment=["Isolated recovery"], availableTools=["flow_observe"], constraints=["Keep production unchanged"]),
        limits=dict(maxChecks=5, timeoutSeconds=120)), worker=dict(argv=command, home=str(root / "home"), model="fixture",
        profile=dict(profile="codex-app-server-broker/v1", experimental=True, codex_version=report["codex_version"],
        schema_sha256=report["schema_sha256"], max_tool_calls=24, max_tool_bytes=4_000_000, max_frame_bytes=2_000_000,
        max_output_bytes=8_000_000, timeout_seconds=120, smoke_attestation=str(root / "smoke.json"))),
        discovery=dict(formatVersion=3, roots=[dict(id="inventory", path=str(inventory), scope="Isolated resources")],
                       maxSteps=8, maxSourceBytes=65536, maxTotalSourceBytes=262144, maxRecords=32, maxEntries=32))


def commission_fixture(config, directory):
    """Test-only activation gate record; never a live smoke or a production artifact."""
    worker = config["worker"]
    transport = AppServerWorker(worker["profile"], worker["home"], directory, argv=worker["argv"],
                                adapter_identity=authority_identity())
    atomic(Path(worker["profile"]["smoke_attestation"]), dict(identity=transport.smoke_identity(worker["model"]),
                                                            passed=True, model_called=True))


def response(marker="accept"):
    return dict(normalizedIntent=dict(marker=marker), report={})


def step(tool, **arguments):
    return dict(tool=tool, arguments=arguments)


def script(root, steps, candidate="checked.candidateId", final=None):
    (root / "transcript.json").write_bytes(canonical(dict(steps=steps,
        final=final if final is not None else dict(candidateId=dict(ref=candidate)))))


class PlanningWorkerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name); self.config = configuration(self.root)
        self.out = self.root / "run"
        commission_fixture(self.config, self.root / "fixture-commissioning")

    def check(self, marker="accept", identifier="checked", **extra):
        return dict(step("flow_check_plan", response=response(marker)), id=identifier, **extra)

    def observe(self):
        return step("flow_observe", kind="read", root="inventory", path="resources.json")

    def test_real_worker_repair_selection_and_process_free_recovery(self):
        script(self.root, [step("flow_task"), self.observe(), self.check("reject", "rejected", exitCode=4),
            self.check(exitCode=0), step("flow_get_artifact", candidate_id={"ref": "checked.candidateId"}, name="proposal.json")])
        first = run(self.config, self.out)
        result = first["result"]
        self.assertEqual("awaiting-independent-review", result["status"])
        self.assertEqual("draft-002", result["candidate"]["candidateId"])
        self.assertEqual("unassessed", result["requestFidelity"])
        self.assertFalse(result["workflowExecution"])
        self.assertIn("draft-001/proposal.json", result["artifacts"])
        self.assertIn("draft-002/observations.json", result["artifacts"])
        (self.out / "result.json").unlink()
        Path(self.config["worker"]["profile"]["smoke_attestation"]).unlink()
        with patch.object(AppServerWorker, "preflight", side_effect=AssertionError("No process on recovery")), \
             patch("authoring_host.planning_tools.invoke", side_effect=AssertionError("No compiler on recovery")):
            self.assertEqual(first, run(self.config, self.out))
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_duplicate_call_is_not_a_second_compilation(self):
        script(self.root, [self.check(callId="same"), self.check(callId="same")])
        value = run(self.config, self.out)
        self.assertEqual(1, value["worker"]["tool_calls"])
        self.assertEqual(1, len(list((self.out / "planning").glob("draft-*"))))

    def test_final_selection_requires_the_latest_observation_snapshot(self):
        broker = PlanningBroker(self.config, self.root / "broker")
        first = broker.call("flow_check_plan", dict(response=response()))
        observed = broker.call("flow_observe", self.observe()["arguments"])
        with self.assertRaisesRegex(HostError, "observations changed since"):
            broker.finalize(dict(candidateId=first["candidateId"]))
        self.assertEqual([first["candidateId"]], observed["draftsNeedRecheck"])
        second = broker.call("flow_check_plan", dict(response=response()))
        # Re-reading already delivered unchanged bytes does not invalidate a draft.
        broker.call("flow_observe", self.observe()["arguments"])
        result = broker.finalize(dict(candidateId=second["candidateId"]))
        self.assertEqual("awaiting-independent-review", result["status"])

    def test_recovery_metadata_must_match_the_retained_candidate(self):
        script(self.root, [self.observe(), self.check("reject", "rejected"), self.check()])
        result = run(self.config, self.out)["result"]
        mutations = [lambda r: r.update(status="blocked"),
                     lambda r: r.update(requestFidelity="requirements-satisfied"),
                     lambda r: r.update(workflowExecution=True),
                     lambda r: r.update(candidate=None, status="blocked"),
                     lambda r: r["candidate"]["review"].update(requestFidelity="requirements-satisfied"),
                     lambda r: r["candidate"]["review"].update(exitCode=False),
                     lambda r: r["candidate"].update(observationsSha256="0" * 64),
                     lambda r: r["artifacts"].pop("draft-001/proposal.json"),
                     lambda r: r["artifacts"].pop("task.json"),
                     lambda r: r["artifacts"].pop("observations.json")]
        for index, mutation in enumerate(mutations):
            altered = copy.deepcopy(result); mutation(altered)
            with self.subTest(mutation=index), self.assertRaises(HostError):
                verify_handoff(self.config, self.out / "planning", altered)

    def test_changed_worker_receipt_is_rejected_on_real_recovery_without_replay(self):
        script(self.root, [self.check()]); run(self.config, self.out)
        for path in (self.out / "worker").glob("*/*.json"):
            value = loads(read(path))
            if "receipt" in value:
                value["receipt"]["result"]["candidate"]["review"]["exitCode"] = False
                atomic(path, value)
                break
        else:
            self.fail("Completed worker receipt missing")
        with self.assertRaisesRegex(HostError, "differs from retained result"):
            run(self.config, self.out)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_stale_final_selection_fails_the_real_turn_without_automatic_replay(self):
        script(self.root, [self.check(), self.observe()])
        for _ in range(2):
            with self.assertRaises(WorkerIndeterminate): run(self.config, self.out)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())
        self.assertFalse((self.out / "result.json").exists())

    def test_invalid_planning_configuration_stops_before_preflight_and_reservation(self):
        self.config["planning"]["limits"]["maxChecks"] = 0
        with patch.object(AppServerWorker, "preflight", side_effect=AssertionError("No worker process")):
            with self.assertRaises(HostError): run(self.config, self.out)
        self.assertFalse((self.out / "intent.json").exists())

    def test_compiler_preparation_failure_does_not_reserve_a_model_turn(self):
        with patch("authoring_host.planning_tools.capability_catalog", side_effect=HostError("Catalog unavailable")):
            with self.assertRaises(HostError): run(self.config, self.out)
        self.assertFalse((self.out / "intent.json").exists())
        script(self.root, [self.check()])
        self.assertEqual("awaiting-independent-review", run(self.config, self.out)["result"]["status"])
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_artifact_lookup_is_repairable_but_changed_bytes_are_fatal(self):
        broker = PlanningBroker(self.config, self.root / "broker")
        checked = broker.call("flow_check_plan", dict(response=response()))
        for candidate, name in (([], "proposal.json"), ("draft-999", "proposal.json"),
                                (checked["candidateId"], "missing.json")):
            with self.subTest(candidate=candidate, name=name), self.assertRaises(ToolInputError):
                broker.call("flow_get_artifact", dict(candidate_id=candidate, name=name))
        snapshot = broker.call("flow_get_artifact", dict(candidate_id=checked["candidateId"], name="observations.json"))
        self.assertEqual(checked["observationsSha256"], snapshot["sha256"])
        (broker.planning.root / checked["candidateId"] / "observations.json").write_text('{}')
        with self.assertRaises(HostError):
            broker.call("flow_get_artifact", dict(candidate_id=checked["candidateId"], name="observations.json"))

    def test_uncertain_turn_is_never_repeated(self):
        script(self.root, [self.check(), {"crash": True}])
        for _ in range(2):
            with self.assertRaises(WorkerIndeterminate): run(self.config, self.out)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_completed_inner_receipt_survives_outer_write_failure(self):
        script(self.root, [self.check()])
        original = atomic
        def fail_result(path, value):
            if path == self.out / "result.json": raise OSError("simulated outer crash")
            return original(path, value)
        with patch("authoring_host.planning_worker.atomic", side_effect=fail_result), self.assertRaises(WorkerError):
            run(self.config, self.out)
        self.assertEqual("awaiting-independent-review", run(self.config, self.out)["result"]["status"])
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_changed_original_request_cannot_reuse_run_or_call_model(self):
        script(self.root, [self.check()]); run(self.config, self.out)
        self.config["planning"]["task"]["request"]["userText"] = "Modify production"
        with self.assertRaisesRegex(HostError, "configuration or authority changed"): run(self.config, self.out)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_changed_source_blocks_finalization_and_recovery(self):
        script(self.root, [self.observe(), self.check(), dict(write=str(self.root / "inventory/resources.json"), text="changed")])
        with self.assertRaises(WorkerIndeterminate): run(self.config, self.out)
        self.assertFalse((self.out / "result.json").exists())
        # A separate completed run must also recheck sources on later recovery.
        script(self.root, [self.observe(), self.check()]); other = self.root / "other"
        run(self.config, other)
        (self.root / "inventory/resources.json").write_text("changed again")
        with self.assertRaisesRegex(HostError, "sources or provenance changed"): run(self.config, other)
        self.assertEqual("2", (self.root / "home/turn-count").read_text())

    def test_changed_source_during_compile_is_not_a_selectable_candidate(self):
        broker = PlanningBroker(self.config, self.root / "broker")
        broker.call("flow_observe", self.observe()["arguments"])
        original = broker.planning.check
        def changed(value):
            result = original(value)
            (self.root / "inventory/resources.json").write_text("modified")
            return result
        with patch.object(broker.planning, "check", side_effect=changed), self.assertRaises(HostError):
            broker.call("flow_check_plan", dict(response=response()))
        self.assertEqual({}, broker.results)

    def test_changed_candidate_bytes_block_recovery(self):
        script(self.root, [self.check()]); run(self.config, self.out)
        (self.out / "planning/draft-001/proposal.json").write_text('{}')
        with self.assertRaisesRegex(HostError, "artifact changed"): run(self.config, self.out)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_unchecked_selection_and_injected_authority_are_rejected(self):
        for index, final in enumerate((dict(candidateId="draft-999"), dict(candidateId="", approved=True),
                                       dict(candidateId="", response=response()), dict(candidateId=[]))):
            script(self.root, [], final=final)
            with self.subTest(final=final), self.assertRaises(WorkerIndeterminate):
                run(self.config, self.root / str(index))

    def test_no_candidate_is_a_blocked_result(self):
        script(self.root, [step("flow_task")], final=dict(candidateId=""))
        result = run(self.config, self.out)["result"]
        self.assertEqual("blocked", result["status"])
        self.assertIsNone(result["candidate"])

    def test_missing_smoke_stops_before_any_turn(self):
        Path(self.config["worker"]["profile"]["smoke_attestation"]).unlink()
        with self.assertRaisesRegex(WorkerError, "smoke is required"): run(self.config, self.out)
        self.assertFalse((self.root / "home/turn-count").exists())
        self.assertFalse((self.out / "intent.json").exists())

    def test_forbidden_builtins_and_dynamic_tools_stop_the_turn(self):
        for index, action in enumerate((dict(item="mcpToolCall"), dict(item="commandExecution"),
                                       step("stage_edits", edits=[]), step("flow_approve"))):
            script(self.root, [action], final=dict(candidateId=""))
            with self.subTest(action=action), self.assertRaises(WorkerIndeterminate):
                run(self.config, self.root / str(index))

    def test_scoped_reads_reject_escape_symlinks_and_exhausted_bounds(self):
        broker = PlanningBroker(self.config, self.root / "broker")
        (self.root / "inventory/link").symlink_to(self.root / "transcript.json")
        for arguments in (dict(kind="read", root="inventory", path="../transcript.json"),
                          dict(kind="read", root="not-granted", path="resources.json"),
                          dict(kind="read", root="inventory", path="link")):
            with self.subTest(arguments=arguments), self.assertRaises((HostError, OSError)):
                broker.call("flow_observe", arguments)
        broker.observation_calls = self.config["discovery"]["maxSteps"]
        with self.assertRaisesRegex(HostError, "limit reached"): broker.call("flow_observe", self.observe()["arguments"])

    def test_task_is_fixed_and_lookup_errors_are_correctable(self):
        broker = PlanningBroker(self.config, self.root / "broker")
        with self.assertRaises(ToolInputError): broker.call("flow_task", dict(request="new"))
        with self.assertRaises(ToolInputError): broker.call("flow_get_contract", dict(contract_id="/etc/passwd"))
        task = broker.call("flow_task", {})
        task["request"]["userText"] = "new"
        self.assertEqual(self.config["planning"]["task"]["request"], broker.call("flow_task", {})["request"])
        self.assertNotIn("path", task["observationRoots"][0])

    def test_corrected_arguments_continue_in_the_same_worker_turn(self):
        script(self.root, [dict(step("flow_check_plan", response={"request": "replacement"}), success=False),
                          dict(step("flow_get_contract", contract_id="missing"), success=False),
                          dict(step("flow_get_artifact", candidate_id="draft-999", name="proposal.json"), success=False), self.check()])
        receipt = run(self.config, self.out)
        self.assertEqual("draft-001", receipt["result"]["candidate"]["candidateId"])
        self.assertEqual(4, receipt["worker"]["tool_calls"])

    def test_changed_listing_blocks_a_draft_before_compilation(self):
        broker = PlanningBroker(self.config, self.root / "broker")
        broker.call("flow_observe", dict(kind="list", root="inventory", path="."))
        (self.root / "inventory/new.json").write_text('{}')
        with self.assertRaisesRegex(HostError, "sources or provenance changed"):
            broker.call("flow_check_plan", dict(response=response()))
        self.assertEqual(0, broker.planning.checks)

    def test_compiler_failure_stops_instead_of_becoming_a_repair_diagnostic(self):
        broker = PlanningBroker(self.config, self.root / "broker")
        with patch("authoring_host.planning_tools.invoke", return_value=dict(failure="process-timeout")):
            with self.assertRaisesRegex(HostError, "did not complete"):
                broker.call("flow_check_plan", dict(response=response()))
        self.assertEqual(1, broker.planning.checks)
        self.assertEqual({}, broker.results)

    def test_changed_compiler_or_contracts_block_completed_recovery(self):
        script(self.root, [self.check()]); run(self.config, self.out)
        for target in ("compiler_fingerprint", "public_contracts"):
            with self.subTest(target=target), patch("authoring_host.planning_worker." + target, return_value="changed"):
                with self.assertRaisesRegex(HostError, "changed"): run(self.config, self.out)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def signed_inventory(self):
        sys.path.insert(0, str(ROOT / "tools/tests"))
        from test_inventory_provenance import source
        from authoring_host import inventory_collection
        directory = self.root / "signed-source"; directory.mkdir()
        request = self.config["planning"]["task"]["request"]
        key, trust, _, collector, capture = source(directory, request, {"resources.json": '{"environment":"isolated"}'})
        result = inventory_collection.collect(capture, collector, trust, key, self.root / "collection")
        self.config["discovery"]["roots"][0].update(path=result["inventoryRoot"], inventory=result["inventory"])

    def test_signed_inventory_for_another_request_stops_before_model_or_compiler(self):
        self.signed_inventory()
        broker = PlanningBroker(self.config, self.root / "broker")
        self.assertIn("inventoryAttestationSha256", broker.call("flow_observe", self.observe()["arguments"]))
        self.config["planning"]["task"]["request"]["userText"] = "A different request"
        with patch("authoring_host.planning_tools.invoke", side_effect=AssertionError("No compiler")):
            with self.assertRaisesRegex(HostError, "another authoring request"): run(self.config, self.out)
        self.assertFalse((self.root / "home/turn-count").exists())

    def test_expired_signed_inventory_blocks_completed_recovery(self):
        from datetime import timedelta
        from authoring_host import inventory_provenance
        self.signed_inventory()
        script(self.root, [self.observe(), self.check()]); run(self.config, self.out)
        with patch("authoring_host.inventory_provenance.now", return_value=inventory_provenance.now() + timedelta(hours=2)):
            with self.assertRaisesRegex(HostError, "sources or provenance changed"): run(self.config, self.out)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())


if __name__ == "__main__":
    unittest.main()
