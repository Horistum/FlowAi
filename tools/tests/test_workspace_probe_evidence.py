import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import workspace_probe_evidence as probe

CONTEXT = dict(sourceRevision='a' * 40, workflowRevision='b' * 40, runId='123', attempt='1')
REPOSITORY = 'Horistum/FlowAi'


def run():
    return dict(id=123, run_attempt=1, repository={'full_name': REPOSITORY}, path=probe.WORKFLOW,
                head_sha=CONTEXT['sourceRevision'], event='pull_request', status='completed', conclusion='success')


def collector():
    return dict(id=100, name='collect', status='completed', conclusion='success', head_sha=CONTEXT['sourceRevision'],
                run_id=123, run_attempt=1)


class ProbeTests(unittest.TestCase):
    def test_cross_run_binding_preserves_native_identity_and_rejects_revision_drift(self):
        parent = CONTEXT | {'runId': '456', 'attempt': '2'}
        self.assertEqual(CONTEXT, probe.native_context(CONTEXT, parent))
        for key, value in [('sourceRevision', 'c' * 40), ('workflowRevision', 'c' * 40),
                           ('runId', '0'), ('attempt', True), ('runId', '../123')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.native_context(CONTEXT | {key: value}, parent)

    def test_authentication_requires_exact_workflow_head_attempt_and_successful_test_run(self):
        probe.authenticate(run(), [collector()], CONTEXT, REPOSITORY)
        for key, value in [('id', 124), ('run_attempt', 2), ('run_attempt', True), ('path', 'other.yml'),
                           ('head_sha', 'c' * 40), ('repository', {'full_name': 'other/repo'}),
                           ('status', 'in_progress'), ('conclusion', 'failure'), ('conclusion', 'cancelled'),
                           ('event', 'pull_request_target')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.authenticate(run() | {key: value}, [collector()], CONTEXT, REPOSITORY)

    def test_authentication_requires_successful_same_attempt_collector(self):
        for jobs in ([], [collector(), collector()], [collector() | {'conclusion': 'failure'}],
                     [collector() | {'run_attempt': 2}], [collector() | {'run_id': 124}],
                     [collector() | {'head_sha': 'c' * 40}], [collector() | {'status': 'in_progress'}]):
            with self.subTest(jobs=jobs), self.assertRaises(ValueError):
                probe.authenticate(run(), jobs, CONTEXT, REPOSITORY)

    def locate(self, runs=None, artifacts=None, attempts=None, explicit=None, timeout=30):
        now = [0]
        self.waits = []
        def pause(delay):
            self.waits.append(delay)
            now[0] += delay
        return probe.locate(lambda: runs if runs is not None else [run()],
                            artifacts or (lambda _: [self.artifact()]),
                            attempts or (lambda rid, attempt: run() | {'id': int(rid), 'run_attempt': int(attempt)}),
                            CONTEXT['sourceRevision'], 'd' * 64, 'pull_request', explicit=explicit,
                            pause=pause, clock=lambda: now[0], timeout=timeout)

    def artifact(self, digest='d' * 64, attempt=1, identity=10):
        return dict(id=identity, name=probe.artifact_name(digest, attempt), expired=False)

    def test_concurrent_dispatch_and_other_pr_events_cannot_displace_matching_probe(self):
        runs = [run(), run() | {'id': 124, 'event': 'workflow_dispatch'}, run() | {'id': 125}]
        result = self.locate(runs=runs, artifacts=lambda rid: [self.artifact('d' * 64 if rid == 123 else 'e' * 64)])
        self.assertEqual('123', result['run_id'])
        self.assertEqual('10', result['artifact_id'])

    def test_same_event_multiple_runs_fail_closed_instead_of_selecting_latest(self):
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            self.locate(runs=[run(), run() | {'id': 124}])

    def test_pinned_attempt_does_not_follow_concurrent_rerun(self):
        calls = []
        def attempts(rid, attempt):
            calls.append((rid, attempt))
            return run() | {'status': 'in_progress' if len(calls) == 1 else 'completed'}
        artifacts = lambda _: [self.artifact()] if not calls else [self.artifact(attempt=2, identity=11)]
        result = self.locate(artifacts=artifacts, attempts=attempts)
        self.assertEqual('1', result['attempt'])
        self.assertEqual([(123, 1), (123, 1)], calls)

    def test_latest_complete_artifact_is_pinned_without_replacing_older_artifacts(self):
        result = self.locate(artifacts=lambda _: [self.artifact(), self.artifact(attempt=2, identity=11)])
        self.assertEqual('2', result['attempt'])
        self.assertEqual('11', result['artifact_id'])

    def test_manual_certification_uses_explicit_run_and_attempt(self):
        result = self.locate(explicit=('123', '1'), artifacts=lambda _: [self.artifact('e' * 64)])
        self.assertEqual('123', result['run_id'])
        self.assertEqual('1', result['attempt'])
        with self.assertRaisesRegex(ValueError, 'provenance'):
            self.locate(explicit=('123', '1'), attempts=lambda *_: run() | {'head_sha': 'c' * 40})

    def test_successful_final_verdict_is_required_without_retrying_it(self):
        for conclusion in ('failure', 'cancelled'):
            with self.subTest(conclusion=conclusion), self.assertRaisesRegex(ValueError, 'final verdict'):
                self.locate(attempts=lambda *_: run() | {'conclusion': conclusion})
            self.assertEqual([], self.waits)

    def test_deadline_uses_elapsed_time_and_allows_native_critical_path(self):
        self.assertGreater(probe.WAIT_SECONDS, 50 * 60)
        with self.assertRaisesRegex(ValueError, 'budget'):
            self.locate(runs=[], timeout=31)
        self.assertEqual([15, 15, 1], self.waits)

    def test_expired_duplicate_and_wrong_trigger_artifacts_are_not_admitted(self):
        for artifacts in ([self.artifact() | {'expired': True}], [self.artifact('e' * 64)]):
            with self.assertRaisesRegex(ValueError, 'budget'):
                self.locate(artifacts=lambda _: artifacts, timeout=1)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.locate(artifacts=lambda _: [self.artifact(), self.artifact(identity=11)])

    def test_trigger_digest_is_order_independent_and_binds_event_and_workflow_revision(self):
        environment = dict(GITHUB_REPOSITORY=REPOSITORY, GITHUB_EVENT_NAME='pull_request',
                           EXPECTED_SHA=CONTEXT['sourceRevision'], WORKFLOW_REVISION=CONTEXT['workflowRevision'])
        event = {'number': 252, 'action': 'synchronize'}
        digest = probe.trigger_digest(environment, event)
        self.assertEqual(digest, probe.trigger_digest(environment, dict(reversed(list(event.items())))))
        for changed in (event | {'number': 253}, event | {'action': 'reopened'}):
            self.assertNotEqual(digest, probe.trigger_digest(environment, changed))
        self.assertNotEqual(digest, probe.trigger_digest(environment | {'WORKFLOW_REVISION': 'c' * 40}, event))

    def test_attempt_endpoint_is_immutable_and_rejects_path_injection(self):
        self.assertEqual('runs/123/attempts/1', probe.attempt_path('123', '1'))
        for invalid in ('0', '../1', True):
            with self.assertRaises(ValueError):
                probe.attempt_path('123', invalid)

    def test_paginated_artifacts_include_later_pages_and_reject_duplicate_ids(self):
        with patch.object(probe, 'get', side_effect=[{'total_count': 2, 'artifacts': [{'id': 1}]},
                                                    {'total_count': 2, 'artifacts': [{'id': 2}]}]) as fetch:
            self.assertEqual([{'id': 1}, {'id': 2}], probe.listing(REPOSITORY, 'runs/123/artifacts', 'artifacts', 'test'))
            self.assertIn('page=2', fetch.call_args.args[1])
        with patch.object(probe, 'get', return_value={'total_count': 2, 'artifacts': [{'id': 1}, {'id': 1}]}):
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                probe.listing(REPOSITORY, 'runs/123/artifacts', 'artifacts', 'test')

    def test_manual_wrong_or_unfinished_attempt_fails_before_artifact_polling(self):
        for change in ({'head_sha': 'c' * 40}, {'path': 'other.yml'}, {'id': 124},
                       {'run_attempt': True}, {'status': 'in_progress'}, {'conclusion': 'failure'}):
            artifacts = Mock(return_value=[])
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.locate(explicit=('123', '1'), artifacts=artifacts,
                            attempts=lambda *_: run() | change)
            artifacts.assert_not_called()
            self.assertEqual([], self.waits)

    def test_manual_missing_or_expired_archive_fails_without_waiting_or_falling_back(self):
        for archives in ([], [self.artifact() | {'expired': True}], [self.artifact(attempt=2)]):
            with self.subTest(archives=archives), self.assertRaisesRegex(ValueError, 'no available evidence'):
                self.locate(explicit=('123', '1'), artifacts=lambda _: archives)
            self.assertEqual([], self.waits)

    def test_slow_api_response_cannot_admit_evidence_after_deadline(self):
        for delayed in ('runs', 'artifacts', 'attempt'):
            now = [0]
            def response(name, value):
                if delayed == name:
                    now[0] = 31
                return value
            with self.subTest(delayed=delayed), self.assertRaisesRegex(ValueError, 'budget'):
                probe.locate(lambda: response('runs', [run()]),
                             lambda _: response('artifacts', [self.artifact()]),
                             lambda *_: response('attempt', run()),
                             CONTEXT['sourceRevision'], 'd' * 64, 'pull_request',
                             clock=lambda: now[0], pause=lambda _: self.fail('Must not wait'), timeout=30)

    def test_expired_deadline_does_not_start_another_api_observation(self):
        now = [0]
        fetch = Mock(return_value=[])
        with self.assertRaisesRegex(ValueError, 'budget'):
            probe.locate(fetch, lambda _: [], lambda *_: run(), CONTEXT['sourceRevision'], 'd' * 64,
                         'pull_request', clock=lambda: now[0], pause=lambda delay: now.__setitem__(0, delay), timeout=1)
        fetch.assert_called_once_with()
