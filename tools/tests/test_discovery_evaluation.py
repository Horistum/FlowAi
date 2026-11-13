"""Evaluation accounting and isolation tests; scripted peers are not model evidence."""
import copy
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import discovery_evaluation as evaluation, discovery_suite
from authoring_host.contracts import HostError, canonical, loads, read
import test_discovery_review as discovery_tests


class DiscoveryEvaluationTests(unittest.TestCase):
    setUp = discovery_tests.DiscoveryReviewTests.setUp
    configure = discovery_tests.DiscoveryReviewTests.configure

    def suite(self, cases=2):
        self.configure()
        limits = {k: v for k, v in self.config.pop('investigation').items() if k not in ('formatVersion', 'roots')}
        self.suite_path = self.root / 'suite.json'
        rows = []
        for index in range(cases):
            request = dict(self.request, userText=f'Verify orders with policy {index}. Do not restore.')
            requirements = dict(self.requirements, request=request)
            (self.root/f'request-{index}.json').write_bytes(canonical(request))
            (self.root/f'requirements-{index}.json').write_bytes(canonical(requirements))
            rows.append(dict(id=f'case-{index}', domain='infrastructure' if index == 0 else 'data', environment='shared',
                             exposure='public-development', request=f'request-{index}.json', requirements=f'requirements-{index}.json',
                             roots=[dict(id='workspace', directory='workspace', scope='Selected environment inventory')]))
        self.manifest = dict(formatVersion=1, id='test-suite', description='Public protocol fixtures only.', discoveryLimits=limits, cases=rows)
        self.write_suite()

    def write_suite(self):
        self.suite_path.write_bytes(canonical(self.manifest))

    def capture(self, repetitions=1, actions=None, **options):
        self.responses.write_bytes(canonical([dict(response=a) for a in (actions or self.actions)*len(self.manifest['cases'])*repetitions]))
        return evaluation.capture(self.config, self.suite_path, self.out, repetitions=repetitions, max_cost_microusd=100_000_000, **options)

    def reviews(self, report, **assessment):
        rows = []
        for run in report['runs']:
            row = dict(run=run['id'], stateSha256=run['stateSha256'], assessment=dict(
                goal='fulfilled', constraints='respected', discovery='sufficient', rationale='Independent test assessment.'))
            row['assessment'].update(assessment)
            if run['status'] == 'awaiting-discovery-review':
                row['decision'] = dict(formatVersion=1, runId=run['runId'], packetSha256=run['reviewPacketSha256'],
                    reviewerId='quality-reviewer', decision='accept', rationale='Explicit protocol-test approval.')
            rows.append(row)
        return dict(formatVersion=1, evaluationId=report['evaluationId'], reviewerId='quality-reviewer', runs=rows)

    def test_repeated_capture_preserves_private_inputs_and_requires_explicit_review(self):
        self.suite(); report = self.capture(repetitions=3)
        self.assertEqual(6, report['completedRuns']); self.assertEqual(6, report['expectedRuns'])
        self.assertEqual(0, report['compilerAccepted']); self.assertEqual(0, report['reportedLiveCalls'])
        self.assertEqual(12_000_000, report['reservedSuiteCostMicrousd'])
        self.assertEqual(2, report['taskComparisons'][0]['distinctRequests'])
        self.assertEqual(1, report['taskComparisons'][0]['distinctReadSets'])
        self.assertEqual(3, report['domains']['infrastructure']['expectedRuns'])
        self.assertTrue(report['minimumThreeRepetitions']); self.assertFalse(report['milestoneComplete'])
        for row in report['runs']:
            run = self.out/'runs'/row['id']
            first = loads(read(run/'attempt-000/provider-request.json'))
            prompt = json.dumps(first)
            for private in ('test-reviewer', 'resource://orders', 'requirements-0.json'):
                self.assertNotIn(private, prompt)
            for private in ('recordPath', 'bindingRequirements'):
                self.assertNotIn(private, json.dumps(first['input']))
            self.assertIn('policy', first['input']['request']['userText'])
            self.assertEqual(3, row['reportedProviderCalls']); self.assertEqual(0, row['unreportedCalls'])
        result = evaluation.review(self.out, canonical(self.reviews(report)))
        self.assertEqual(6, result['compilerAccepted']); self.assertEqual(6, result['callerAssessedSuccesses'])
        self.assertEqual('18', self.counter.read_text())
        self.assertEqual(result, evaluation.summarize(self.out))

    def test_compiler_acceptance_does_not_override_caller_quality_failure(self):
        self.suite(); report = self.capture()
        reviews = self.reviews(report)
        reviews['runs'][0]['assessment'].update(goal='failed', constraints='violated')
        reviews['runs'][1]['assessment'].update(discovery='unassessed')
        result = evaluation.review(self.out, canonical(reviews))
        self.assertEqual(2, result['compilerAccepted']); self.assertEqual(0, result['callerAssessedSuccesses'])
        self.assertEqual(1, result['acceptedConstraintViolations'])

    def test_review_rejection_does_not_authorize_compilation(self):
        self.suite(cases=1); report = self.capture()
        reviews = self.reviews(report, goal='failed', discovery='insufficient')
        reviews['runs'][0]['decision']['decision'] = 'reject'
        with patch('authoring_host.session.invoke', side_effect=AssertionError('No compiler or provider')):
            result = evaluation.review(self.out, canonical(reviews))
        self.assertEqual('discovery-review-rejected', result['runs'][0]['status'])
        self.assertEqual(0, result['compilerAccepted'])

    def test_target_block_does_not_erase_a_faithful_plan_or_claim_an_artifact(self):
        self.suite(cases=1); report = self.capture(target='github-actions', render=True)
        result = evaluation.review(self.out, canonical(self.reviews(report)))
        self.assertEqual('target-blocked', result['runs'][0]['status'])
        self.assertEqual(1, result['compilerAccepted']); self.assertEqual(1, result['callerAssessedSuccesses'])
        self.assertEqual(0, result['runAccepted']); self.assertEqual(0, result['renderAuthorizedRuns'])
        self.assertFalse(result['milestoneComplete'])

    def test_review_is_bound_to_evaluation_run_state_packet_and_reviewer(self):
        self.suite(cases=1); report = self.capture(); valid = self.reviews(report)
        variants = []
        wrong = copy.deepcopy(valid); wrong['evaluationId'] = 'other'; variants.append(wrong)
        wrong = copy.deepcopy(valid); wrong['runs'][0]['stateSha256'] = '0'*64; variants.append(wrong)
        wrong = copy.deepcopy(valid); wrong['runs'][0]['decision']['packetSha256'] = '0'*64; variants.append(wrong)
        wrong = copy.deepcopy(valid); wrong['runs'][0]['decision']['reviewerId'] = 'other'; variants.append(wrong)
        wrong = copy.deepcopy(valid); wrong['runs'][0]['decision'] = 'accept'; variants.append(wrong)
        wrong = copy.deepcopy(valid); wrong['runs'] *= 2; variants.append(wrong)
        for wrong in variants:
            with self.assertRaises(HostError): evaluation.review(self.out, canonical(wrong))
        self.assertEqual(0, evaluation.summarize(self.out)['reviewedRuns'])
        evaluation.review(self.out, canonical(valid))
        with self.assertRaises(HostError): evaluation.review(self.out, canonical(valid))

    def test_partial_reviews_do_not_shrink_the_denominator(self):
        self.suite(); report = self.capture(); reviews = self.reviews(report)
        reviews['runs'] = reviews['runs'][:1]
        result = evaluation.review(self.out, canonical(reviews))
        self.assertEqual(1, result['reviewedRuns']); self.assertEqual(2, result['expectedRuns'])
        remaining = self.reviews(report); remaining['runs'] = remaining['runs'][1:]
        self.assertEqual('reviewed', evaluation.review(self.out, canonical(remaining))['status'])

    def test_blocked_discovery_can_be_assessed_without_fabricating_an_approval(self):
        self.suite(cases=1)
        self.proposal['unknowns'] = [dict(id='owner', question='Which owner authorized this?', category='decision',
                                        blocking=True, status='open', evidenceRefs=[])]
        report = self.capture()
        self.assertEqual('awaiting-user', report['runs'][0]['status'])
        self.assertEqual(1, report['runs'][0]['openUnknownsByCategory']['decision'])
        self.assertEqual(1, report['runs'][0]['blockingOpenUnknowns'])
        reviews = self.reviews(report, goal='appropriate-stop')
        self.assertNotIn('decision', reviews['runs'][0])
        result = evaluation.review(self.out, canonical(reviews))
        self.assertEqual(1, result['callerAssessedSuccesses']); self.assertEqual(0, result['compilerAccepted'])
        self.assertEqual('3', self.counter.read_text())

    def test_invalid_provider_output_stops_spending_but_retains_all_scheduled_runs(self):
        self.suite()
        self.responses.write_bytes(canonical(['invalid']))
        report = evaluation.capture(self.config, self.suite_path, self.out, max_cost_microusd=12_000_000)
        self.assertEqual('capture-stopped', report['status']); self.assertEqual('1', self.counter.read_text())
        self.assertEqual(6, report['expectedRuns']); self.assertEqual(1, report['completedRuns'])
        self.assertEqual(1, report['runs'][0]['unreportedCalls'])
        self.assertEqual(0, report['runs'][0]['reportedProviderCalls'])

    def test_total_suite_budget_is_reserved_before_any_dispatch(self):
        self.suite()
        with self.assertRaisesRegex(HostError, 'reserve'):
            evaluation.capture(self.config, self.suite_path, self.out, max_cost_microusd=11_999_999)
        self.assertFalse(self.out.exists()); self.assertFalse(self.counter.exists())

    def test_missing_explicit_credentials_do_not_create_a_run(self):
        self.suite(); self.config['provider']['credentialEnv'] = ['TEST_DISCOVERY_EVAL_KEY']
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(HostError): self.capture()
        self.assertFalse(self.out.exists()); self.assertFalse(self.counter.exists())

    def test_interrupted_capture_is_reported_and_never_replayed(self):
        self.suite()
        with patch('authoring_host.discovery_evaluation.start', side_effect=KeyboardInterrupt()), self.assertRaises(KeyboardInterrupt):
            self.capture()
        report = evaluation.summarize(self.out)
        self.assertEqual(1, report['uncertainRuns']); self.assertEqual(2, report['expectedRuns'])
        self.assertEqual(4_000_000, report['reservedSuiteCostMicrousd'])
        with self.assertRaisesRegex(HostError, 'must not exist'): self.capture()
        with self.assertRaisesRegex(HostError, 'Interrupted'): evaluation.review(self.out, b'{}')

    def test_interrupted_review_cannot_reuse_the_decision(self):
        self.suite(cases=1); report = self.capture(); reviews = canonical(self.reviews(report))
        with patch('authoring_host.session._compile', side_effect=KeyboardInterrupt()), self.assertRaises(KeyboardInterrupt):
            evaluation.review(self.out, reviews)
        self.assertEqual(1, evaluation.summarize(self.out)['uncertainRuns'])
        with self.assertRaisesRegex(HostError, 'Interrupted'): evaluation.review(self.out, reviews)
        self.assertEqual('3', self.counter.read_text())

    def test_sources_are_frozen_and_original_changes_do_not_change_captured_exposure(self):
        self.suite(cases=1); (self.workspace/'empty').mkdir(); (self.workspace/'empty.txt').write_bytes(b'')
        report = self.capture(); self.source.write_bytes(b'{}')
        self.assertEqual(report, evaluation.summarize(self.out))
        self.assertTrue((self.out/'exposure/case-0/workspace/empty').is_dir())
        (self.out/'exposure/case-0/workspace/extra.txt').write_text('Unexpected newly exposed information')
        with self.assertRaisesRegex(HostError, 'exposure changed'): evaluation.summarize(self.out)
        with self.assertRaises(HostError): evaluation.review(self.out, canonical(self.reviews(report)))

    def test_captured_run_and_private_inputs_cannot_be_replaced(self):
        self.suite(cases=1); report = self.capture()
        state_path = self.out/'runs'/report['runs'][0]['id']/'state.json'
        saved = state_path.read_bytes(); state = loads(saved); state['status'] = 'accepted'; state_path.write_bytes(canonical(state))
        with self.assertRaisesRegex(HostError, 'outside its review channel'): evaluation.summarize(self.out)
        state_path.write_bytes(saved)
        (self.out/'inputs/case-0/requirements.json').write_text('{}')
        with self.assertRaises(HostError): evaluation.summarize(self.out)

    def test_exposure_cannot_include_another_tasks_private_expectations(self):
        self.suite()
        self.manifest['cases'][1]['requirements'] = 'workspace/requirements.json'
        (self.workspace/'requirements.json').write_bytes((self.root/'requirements-1.json').read_bytes())
        self.write_suite()
        with self.assertRaisesRegex(HostError, 'independent requirements'): self.capture()
        self.assertFalse(self.counter.exists())

    def test_same_environment_comparison_requires_identical_exposure(self):
        self.suite(); (self.root/'other').mkdir(); (self.root/'other/source.json').write_text('{}')
        self.manifest['cases'][1]['roots'][0]['directory'] = 'other'; self.write_suite()
        with self.assertRaisesRegex(HostError, 'same complete exposure'): self.capture()

    def test_suite_rejects_path_escape_links_special_files_and_invalid_utf8(self):
        self.suite(cases=1)
        self.manifest['cases'][0]['request'] = '../request.json'; self.write_suite()
        with self.assertRaises(HostError): discovery_suite.load_suite(self.suite_path)
        self.manifest['cases'][0]['request'] = 'request-0.json'; self.write_suite()
        bad = self.workspace/'bad'
        for kind in ('symlink', 'hardlink', 'fifo', 'binary', 'oversized'):
            if kind == 'symlink': bad.symlink_to(self.root/'requirements-0.json')
            elif kind == 'hardlink': os.link(self.source, bad)
            elif kind == 'fifo': os.mkfifo(bad)
            elif kind == 'binary': bad.write_bytes(b'\xff')
            else: bad.write_bytes(b'a'*65537)
            with self.subTest(kind=kind), self.assertRaises((HostError, OSError)):
                discovery_suite.load_suite(self.suite_path)
            bad.unlink()

    def test_external_host_configuration_cannot_smuggle_an_answer_map(self):
        self.suite(); self.config['investigation'] = dict(formatVersion=3, roots=[], **self.manifest['discoveryLimits'])
        with self.assertRaises(HostError): self.capture()
        self.assertFalse(self.counter.exists())

    def test_changed_public_catalog_is_rejected_before_dispatch(self):
        self.suite(cases=1)
        with patch('authoring_host.discovery_evaluation.capability_catalog', return_value=[dict(capability='RESTORE')]), self.assertRaisesRegex(HostError, 'inputs changed'):
            self.capture()
        self.assertFalse(self.counter.exists())
        report = evaluation.summarize(self.out)
        self.assertEqual(1, report['uncertainRuns']); self.assertEqual(0, report['compilerAccepted'])

    def test_public_portfolio_has_three_environments_and_two_original_goals_each(self):
        root = Path(__file__).resolve().parents[2]
        _, manifest, cases = discovery_suite.load_suite(root/'conformance/discovery-evaluation/suite.json')
        self.assertEqual(6, len(cases)); self.assertEqual(3, len({c['domain'] for c in manifest['cases']}))
        for environment in {c['environment'] for c in manifest['cases']}:
            group = [c for c in cases if c['metadata']['environment']==environment]
            self.assertEqual(2, len({c['request'] for c in group}))
            self.assertEqual(1, len({c['exposureSha256'] for c in group}))
        self.assertTrue(all(c['exposure']=='public-development' for c in manifest['cases']))

    def test_report_keeps_large_observation_sets_in_linked_evidence(self):
        self.suite(cases=1); self.capture()
        state = loads(read(self.out/'state.json')); run = state['runs'][0]
        directory, captured = evaluation._run_state(self.out, state, run)
        # Exercise the report's largest scheduled count with long selectors. Session
        # integrity is verified separately; do not spawn 96 processes for formatting.
        ledger = loads(read(directory/captured['investigationFile']))
        ledger['facts'] = []
        ledger['observations'] = [dict(selector=dict(kind='read',root='workspace',path='a'*240+'/'+'b'*230+str(i)),sha256='b'*64) for i in range(64)]
        ledger['unknowns'] = [dict(id='unknown-'+str(i),category='fact',blocking=True,status='open') for i in range(64)]
        (directory/captured['investigationFile']).write_bytes(canonical(ledger))
        state['cases'] = [dict(state['cases'][0], id='case-'+str(i)) for i in range(12)]
        state['runs'] = [dict(run,id=f"repeat-{r}-{c['id']}",case=c['id'],repetition=r)
                         for r in range(1,9) for c in state['cases']]
        state.update(expectedRuns=96,repetitions=8,reservedSuiteCostMicrousd=192_000_000,maxSuiteCostMicrousd=192_000_000)
        with patch('authoring_host.discovery_evaluation._run_state', return_value=(directory,captured)):
            result = evaluation._summarize(self.out,state)
        self.assertEqual(96,result['completedRuns'])
        self.assertEqual(64,result['runs'][0]['readObservationCount'])
        self.assertEqual(64,result['runs'][0]['blockingOpenUnknowns'])
        self.assertLess(len(canonical(result)),300_000)
        self.assertEqual(64,len(loads(read(self.out/result['runs'][0]['investigationFile']))['observations']))

    def test_directory_enumeration_stops_at_the_entry_limit(self):
        self.suite(cases=1)
        class Entries:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def __iter__(self):
                from types import SimpleNamespace
                for i in range(257): yield SimpleNamespace(name='file-'+str(i))
                raise AssertionError('Enumeration must stop before reading an unbounded directory')
        with patch('authoring_host.discovery_suite.os.scandir', return_value=Entries()), self.assertRaisesRegex(HostError,'directory limit'):
            discovery_suite.snapshot(self.workspace)

    def test_cli_exposes_discovery_commands_and_review_returns_descriptive_exit_code(self):
        from author_proposal import main
        self.suite(cases=1); self.capture()
        with patch('builtins.print'):
            self.assertEqual(3, main(['summarize-discovery-evaluation', '--evaluation', str(self.out)]))


if __name__ == '__main__': unittest.main()
