"""Planning tool boundaries; compiler peers here prove protocol, not semantics."""
import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host.contracts import HostError, canonical, loads, read
from authoring_host.planning_tools import PlanningTools
from authoring_host.discovery_bindings import verify_compiler_receipt

ROOT = Path(__file__).resolve().parents[2]


def configuration(compiler=None):
    return dict(formatVersion=1, compiler=dict(
        argv=compiler or [sys.executable, str(ROOT / 'tools/tests/fixtures/authoring_peer.py'), 'compiler'],
        contracts=str(ROOT)), task=dict(request=dict(userText='Recover the isolated orders database.'),
        allowedEnvironment=['Isolated recovery environment only'], availableTools=['Host-owned inventory reader'],
        constraints=['Never modify production', 'Keep the original recovery point']),
        limits=dict(maxChecks=3, timeoutSeconds=10))


def response(marker='accept'):
    return dict(normalizedIntent=dict(marker=marker), report={})


class PlanningToolTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = configuration()
        self.tools = PlanningTools(self.config, self.root / 'session')

    def test_rejected_draft_is_repairable_and_both_exact_candidates_survive(self):
        rejected = self.tools.check(response('reject'))
        accepted = self.tools.check(response())
        self.assertEqual(4, rejected['review']['exitCode'])
        self.assertEqual(0, accepted['review']['exitCode'])
        self.assertNotIn('graphDigest', rejected['review'])
        self.assertNotEqual(rejected['proposalSha256'], accepted['proposalSha256'])
        for result in (rejected, accepted):
            artifact = self.tools.artifact(result['candidateId'], 'proposal.json')
            self.assertEqual(self.config['task']['request'], artifact['content']['request'])
            self.assertEqual(result['proposalSha256'], artifact['sha256'])
            self.assertEqual('unassessed', result['review']['requestFidelity'])
            self.assertFalse(result['review']['renderAuthorized'])

    def test_agent_cannot_replace_task_or_submit_requirements_or_host_paths(self):
        for extra in ('request', 'requirements', 'compiler', 'render', 'out'):
            with self.subTest(extra=extra), self.assertRaises(HostError):
                self.tools.check(dict(response(), **{extra: 'replacement'}))
        self.config['task']['request']['userText'] = 'Modify production'
        self.assertEqual('Recover the isolated orders database.', self.tools.task()['request']['userText'])
        for name in ('../config.json', '/etc/passwd', 'config.json'):
            with self.assertRaises(HostError):
                self.tools.contract(name)
            with self.assertRaises(HostError):
                self.tools.artifact('../', name)
        self.assertEqual(0, self.tools.checks)

    def test_catalog_and_exact_contract_lookup(self):
        self.assertEqual('VERIFY', self.tools.capabilities('verify')['items'][0]['capability'])
        self.assertEqual([], self.tools.capabilities('not-present')['items'])
        self.assertIn('schema', self.tools.contract('proposal-response'))
        self.assertIn('yaml', self.tools.contract('database.yaml'))
        with self.assertRaises(HostError):
            self.tools.capabilities(limit=1000)

    def test_budget_is_spent_on_failed_process_and_not_replayed(self):
        with patch('authoring_host.planning_tools.invoke', return_value=dict(failure='process-timeout')):
            for _ in range(3):
                with self.assertRaisesRegex(HostError, 'did not complete'):
                    self.tools.check(response())
            with self.assertRaisesRegex(HostError, 'limit reached'):
                self.tools.check(response())
        self.assertEqual(3, len(list(self.tools.root.glob('draft-*/proposal.json'))))
        self.assertEqual({}, self.tools.candidates)

    def test_changed_compiler_and_changed_artifact_are_not_trusted(self):
        result = self.tools.check(response())
        path = self.tools.root / result['candidateId'] / 'proposal.json'
        path.write_bytes(canonical(dict(changed=True)))
        with self.assertRaisesRegex(HostError, 'changed'):
            self.tools.artifact(result['candidateId'], 'proposal.json')
        with patch('authoring_host.planning_tools.compiler_fingerprint', return_value='changed'):
            with self.assertRaisesRegex(HostError, 'distribution changed'):
                self.tools.check(response())
        self.assertEqual(1, self.tools.checks)

    def test_changed_contracts_require_new_session(self):
        with patch('authoring_host.planning_tools.public_contracts', return_value={}):
            with self.assertRaisesRegex(HostError, 'contracts changed'):
                self.tools.check(response())
        self.assertEqual(0, self.tools.checks)

    def test_false_compiler_receipt_is_never_a_draft_result(self):
        from authoring_host.planning_tools import invoke
        def corrupt(*args):
            result = invoke(*args)
            path = Path(args[0][-1]) / 'proposal-review.json'
            review = loads(read(path)); review['inputSource']['sha256'] = '0' * 64
            path.write_bytes(canonical(review))
            return result
        with patch('authoring_host.planning_tools.invoke', side_effect=corrupt):
            with self.assertRaisesRegex(HostError, 'receipt mismatch'):
                self.tools.check(response())
        self.assertEqual({}, self.tools.candidates)

    def test_unbounded_and_nonfinite_proposals_are_rejected_before_dispatch(self):
        for value in ('x' * 2_000_001, float('nan')):
            with self.subTest(value=type(value).__name__), self.assertRaises((HostError, ValueError)):
                self.tools.check(dict(normalizedIntent={}, report=dict(explanation=value)))
        self.assertEqual(0, self.tools.checks)

    def test_precanonical_receipt_does_not_need_a_graph_or_certify_success(self):
        assessment = dict(compilerRequirementsSha256='b'*64, bindings=[dict(compilerRequirementId='scope')])
        fidelity = dict(requirementsSource=dict(sha256='b'*64), state='NOT_EVALUATED',
                        requirements=[dict(id='scope', state='NOT_EVALUATED')])
        review = dict(exitCode=4, semanticAcceptance='rejected', requestFidelity='not-evaluated')
        self.assertIsNone(verify_compiler_receipt(assessment, fidelity, review)['compilerGraphDigest'])
        for field, value in (('exitCode', 0), ('semanticAcceptance', 'accepted'), ('requestFidelity', 'requirements-satisfied')):
            with self.subTest(field=field), self.assertRaises(HostError):
                verify_compiler_receipt(assessment, fidelity, dict(review, **{field: value}))
        changed = copy.deepcopy(fidelity); changed['requirements'][0]['state'] = 'SATISFIED'
        with self.assertRaises(HostError):
            verify_compiler_receipt(assessment, changed, review)


if __name__ == '__main__':
    unittest.main()
