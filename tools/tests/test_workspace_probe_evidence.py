import unittest

import workspace_probe_evidence as probe

CONTEXT = dict(sourceRevision='a' * 40, workflowRevision='b' * 40, runId='123', attempt='1')
REPOSITORY = 'Horistum/FlowAi'


def run():
    return dict(id=123, run_attempt=1, repository={'full_name': REPOSITORY}, path=probe.WORKFLOW,
                head_sha=CONTEXT['sourceRevision'], event='pull_request', status='completed', conclusion='failure')


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

    def test_authentication_requires_exact_workflow_head_attempt_and_expected_failure(self):
        probe.authenticate(run(), [collector()], CONTEXT, REPOSITORY)
        for key, value in [('id', 124), ('run_attempt', 2), ('run_attempt', True), ('path', 'other.yml'),
                           ('head_sha', 'c' * 40), ('repository', {'full_name': 'other/repo'}),
                           ('status', 'in_progress'), ('conclusion', 'success'), ('conclusion', 'cancelled'),
                           ('event', 'pull_request_target')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.authenticate(run() | {key: value}, [collector()], CONTEXT, REPOSITORY)

    def test_authentication_requires_successful_same_attempt_collector(self):
        for jobs in ([], [collector(), collector()], [collector() | {'conclusion': 'failure'}],
                     [collector() | {'run_attempt': 2}], [collector() | {'run_id': 124}],
                     [collector() | {'head_sha': 'c' * 40}], [collector() | {'status': 'in_progress'}]):
            with self.subTest(jobs=jobs), self.assertRaises(ValueError):
                probe.authenticate(run(), jobs, CONTEXT, REPOSITORY)

    def test_locator_waits_for_newest_run_and_never_retries_final_verdict(self):
        calls = iter([{'workflow_runs': []}, {'workflow_runs': [run() | {'status': 'in_progress'}]},
                      {'workflow_runs': [run()]}])
        self.assertEqual(run(), probe.locate(lambda: next(calls), CONTEXT['sourceRevision'], lambda _: None, 3))
        for conclusion in ('success', 'cancelled'):
            with self.assertRaisesRegex(ValueError, 'final verdict'):
                probe.locate(lambda: {'workflow_runs': [run(), run() | {'id': 124, 'conclusion': conclusion}]},
                             CONTEXT['sourceRevision'], lambda _: self.fail('Must not retry'), 3)
        with self.assertRaisesRegex(ValueError, 'budget'):
            probe.locate(lambda: {'workflow_runs': [run() | {'head_sha': 'c' * 40}]},
                         CONTEXT['sourceRevision'], lambda _: None, 1)
