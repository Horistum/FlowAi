"""Goal-first host boundaries. Scripted responses test protocol, not model ability."""
import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import discovery, goal_context, investigation
from authoring_host.contracts import HostError, canonical, digest, loads, public_contracts, read, validate_config
from authoring_host.session import resume, review_discovery
import test_authoring_host as host_tests
from test_authoring_discovery import action
import test_discovery_decisions as decision_tests
import test_discovery_review as review_tests


class GoalDiscoveryTests(unittest.TestCase):
    setUp = host_tests.AuthoringHostTests.setUp
    run_host = host_tests.AuthoringHostTests.run_host
    run_actions = review_tests.DiscoveryReviewTests.run_actions
    decision = review_tests.DiscoveryReviewTests.decision
    packet = decision_tests.ObservedDecisionTests.packet

    def configure(self, *, mapped=False):
        decision_tests.ObservedDecisionTests.configure(self)
        self.config['investigation']['roots'][0]['observations'] = ['list', 'read']
        self.task = dict(constraints=['Preserve retained data; do not restore.'], successCriteria=[
            dict(id='usable-backup', description='The retained backup is usable for the requested recovery scope.'),
            dict(id='no-restoration', description='The assessment causes no restoration or data mutation.')])
        self.config['investigation']['task'] = self.task
        self.proposal['interpretation']['evidenceRefs'] = ['fact:service']
        if not mapped:
            self.proposal['interpretation'].update(bindings=[], decisions=[])

    def test_goal_and_actual_grants_precede_any_observation_or_solution(self):
        self.configure(); state = self.run_actions()
        prompt = loads(read(self.out/'attempt-000/provider-request.json'))
        task = prompt['input']['task']
        self.assertEqual(self.request, task['goal'])
        self.assertEqual(self.task['constraints'], task['constraints'])
        self.assertEqual(self.task['successCriteria'], task['successCriteria'])
        self.assertEqual(['list', 'read'], [t['kind'] for t in task['tools']])
        self.assertEqual([], prompt['input']['investigation']['observations'])
        for private in (str(self.workspace), 'resource://orders', 'recordPath', 'forbidden', 'test-reviewer'):
            self.assertNotIn(private, json.dumps(prompt['input']))
        self.assertEqual('awaiting-discovery-review', state['status'])
        self.assertEqual('bounded-authoring-host/7', state['hostVersion'])
        self.assertNotIn('review', state['attempts'][-1])
        packet = self.packet(state)
        self.assertEqual(self.task, packet['task'])
        self.assertEqual([], packet['interpretation']['decisions'])
        self.assertEqual([], packet['interpretation']['bindings'])

    def test_map_free_compilation_does_not_certify_natural_language_goal_or_runtime(self):
        self.configure(); state = self.run_actions()
        final = review_discovery(self.out, canonical(self.decision(state)))
        self.assertEqual('accepted', final['status'])
        evidence = final['goalEvidence']
        self.assertEqual('satisfied', evidence['independentConstraints']['status'])
        self.assertEqual({'forbidden': 'SATISFIED'}, evidence['independentConstraints']['checks'])
        self.assertEqual({}, evidence['proposedConsistencyChecks']['checks'])
        self.assertEqual('not-assessed', evidence['goalSatisfaction'])
        self.assertEqual('not-observed', evidence['runtimeOutcome'])
        self.assertTrue(all(c['status'] == 'not-assessed' for c in evidence['successCriteria']))
        self.assertEqual(self.requirements, loads(read(self.out/final['attempts'][-1]['compilerRequirementsFile'])))
        self.assertEqual('3', self.counter.read_text())

    def test_model_checks_remain_distinct_from_frozen_independent_constraints(self):
        self.configure(mapped=True); state = self.run_actions()
        final = review_discovery(self.out, canonical(self.decision(state)))
        evidence = final['goalEvidence']
        self.assertEqual({'forbidden': 'SATISFIED'}, evidence['independentConstraints']['checks'])
        self.assertEqual(2, len(evidence['proposedConsistencyChecks']['checks']))
        self.assertEqual('model-proposed-caller-reviewed-not-independent', evidence['proposedConsistencyChecks']['authority'])
        self.assertEqual('not-assessed', evidence['goalSatisfaction'])

    def test_model_extracted_constraints_are_not_independent_even_when_confirmed(self):
        self.configure(); self.requirements['origin'] = 'MODEL_EXTRACTED'
        with self.assertRaisesRegex(HostError, 'caller-authored'): self.run_actions()
        self.assertFalse(self.counter.exists())

    def test_goals_require_explicit_grants_and_cannot_hide_solution_maps(self):
        self.configure()
        original = copy.deepcopy(self.config)
        for change in ('missing-grants', 'shell-grant', 'empty-grants', 'map', 'duplicate-criteria', 'empty-constraints', 'old-version'):
            self.config = copy.deepcopy(original); inv = self.config['investigation']
            if change == 'missing-grants': del inv['roots'][0]['observations']
            elif change == 'shell-grant': inv['roots'][0]['observations'] = ['shell']
            elif change == 'empty-grants': inv['roots'][0]['observations'] = []
            elif change == 'map': inv['task']['bindings'] = []
            elif change == 'duplicate-criteria': inv['task']['successCriteria'] *= 2
            elif change == 'empty-constraints': inv['task']['constraints'] = []
            else: inv['formatVersion'] = 3
            with self.subTest(change=change), self.assertRaises(HostError): validate_config(self.config)

    def test_ungranted_read_is_denied_before_filesystem_access(self):
        self.configure(); self.config['investigation']['roots'][0]['observations'] = ['list']
        with patch('authoring_host.discovery._capture', side_effect=AssertionError('No filesystem access')):
            with self.assertRaisesRegex(HostError, 'not granted'):
                discovery.capture(dict(kind='read', root='workspace', path='inventory.json'), self.config['investigation'], {})
        state = self.run_actions()
        self.assertEqual('provider-or-compiler-error', state['status'])
        prompt = loads(read(self.out/'attempt-000/provider-request.json'))
        self.assertEqual(['list'], [t['kind'] for t in prompt['input']['task']['tools']])

    def test_grants_are_scoped_per_root(self):
        self.configure(); second = self.root/'second'; second.mkdir()
        self.config['investigation']['roots'].append(dict(id='second', path=str(second), scope='Metadata only', observations=['list']))
        with self.assertRaisesRegex(HostError, 'not granted'):
            discovery.capture(dict(kind='read', root='second', path='inventory.json'), self.config['investigation'], {})
        self.assertIn('text', discovery.capture(dict(kind='read', root='workspace', path='inventory.json'), self.config['investigation'], {}))

    def test_failed_read_can_be_followed_by_successful_investigation(self):
        self.configure()
        state = self.run_actions([action(('read', 'absent.json'))] + self.actions)
        self.assertEqual('awaiting-discovery-review', state['status'])
        observation = self.packet(state)['evidence']['observations'][0]
        self.assertEqual('not-found', observation['failure'])
        self.assertEqual('host-observed-lookup-failure', observation['attribution'])
        self.assertNotIn('text', observation)
        self.assertNotIn('exists', observation)
        following = loads(read(self.out/'attempt-001/provider-request.json'))
        self.assertEqual('not-found', following['input']['investigation']['observations'][0]['failure'])
        self.assertEqual(self.config['budget']['maxCalls']-1, following['input']['task']['limits']['remainingProviderCalls'])
        self.assertEqual('accepted', review_discovery(self.out, canonical(self.decision(state)))['status'])

    def test_missing_file_appearing_invalidates_the_review_even_with_identical_hash_bytes(self):
        self.configure(); state = self.run_actions([action(('read', 'absent.json'))] + self.actions)
        # Payload bytes deliberately equal the failed-read digest input.
        (self.workspace/'absent.json').write_bytes(canonical(dict(failure='not-found')))
        with patch('authoring_host.session._compile', side_effect=AssertionError('No stale compilation')):
            final = review_discovery(self.out, canonical(self.decision(state)))
        self.assertEqual('source-changed', final['status'])

    def test_failed_read_is_not_extractable_json_and_never_a_binding_candidate(self):
        self.configure(mapped=True)
        inv = self.config['investigation']; ledger = investigation.initial(inv)
        ledger = investigation.apply(ledger, action(('read', 'absent.json')), inv, {}, 'one')
        ref = ledger['observations'][0]['id']
        with self.assertRaisesRegex(HostError, 'successful'):
            investigation.apply(ledger, action(facts=[dict(id='false-fact', observationId=ref, path=[])]), inv, {}, 'two')
        state = self.run_actions([action(('read', 'absent.json'))] + self.actions)
        self.assertEqual('awaiting-discovery-review', state['status'])

    def test_permission_error_is_bounded_but_invalid_paths_remain_rejected(self):
        self.configure(); selector = dict(kind='read', root='workspace', path='inventory.json')
        with patch('authoring_host.discovery._capture', side_effect=PermissionError('private host detail')):
            captured = discovery.capture(selector, self.config['investigation'], {})
        self.assertEqual('not-accessible', captured['failure'])
        self.assertNotIn('private', str(captured))
        for path in ('../escape', '/etc/passwd'):
            with self.assertRaises(HostError):
                discovery.capture(dict(selector, path=path), self.config['investigation'], {})

    def test_map_free_interpretation_still_needs_current_evidence(self):
        self.configure()
        for index, refs in enumerate(([], ['fact:invented'], ['proposal.json'])):
            self.out = self.root/f'basis-{index}'; self.counter.unlink(missing_ok=True)
            self.proposal['interpretation']['evidenceRefs'] = refs
            self.assertEqual('provider-or-compiler-error', self.run_actions()['status'])

    def test_conflicts_and_missing_capabilities_remain_open_without_a_case_table(self):
        self.configure()
        for category in ('fact', 'conflict', 'capability', 'decision'):
            self.out = self.root/category; self.counter.unlink(missing_ok=True)
            self.proposal['unknowns'] = [dict(id='unresolved', question='Can the environment meet the recovery goal?',
                category=category, blocking=True, status='open', evidenceRefs=['fact:service'])]
            state = self.run_actions()
            self.assertEqual('awaiting-user' if category == 'decision' else 'investigation-blocked', state['status'])
            self.assertNotIn('review', state['attempts'][-1])
            self.assertEqual('not-assessed', state['goalEvidence']['goalSatisfaction'])

    def test_clarification_cannot_promote_model_constraints_or_approve_the_plan(self):
        self.configure(); asked = copy.deepcopy(self.proposal)
        asked['unknowns'] = [dict(id='scope', question='Which recovery scope?', category='decision', blocking=True,
                                 status='open', evidenceRefs=[])]
        state = self.run_actions(self.actions[:-1]+[asked, self.proposal])
        answers = canonical(dict(runId=state['runId'], reviewerId='test-reviewer', answers=[dict(id='investigation:scope', answer='The isolated copy only.')]))
        with self.assertRaisesRegex(HostError, 'caller-authored'):
            resume(self.out, answers, canonical(dict(self.requirements, origin='MODEL_EXTRACTED')))
        state = resume(self.out, answers, canonical(self.requirements))
        self.assertEqual('awaiting-discovery-review', state['status'])
        self.assertEqual('not-assessed', state['goalEvidence']['goalSatisfaction'])

    def test_provider_cannot_supply_goal_acceptance_or_change_frozen_criteria(self):
        self.configure()
        for field in ('goalEvidence', 'task', 'approval'):
            self.out = self.root/field; self.counter.unlink(missing_ok=True)
            candidate = copy.deepcopy(self.proposal); candidate[field] = {'status':'accepted'}
            self.assertEqual('provider-or-compiler-error', self.run_actions(self.actions[:-1]+[candidate])['status'])

    def test_independent_constraint_receipt_cannot_omit_duplicate_or_falsely_satisfy_checks(self):
        self.configure()
        review = dict(exitCode=0, graphDigest='a'*64)
        fidelity = dict(requirementsSource=dict(sha256=digest(canonical(self.requirements))), graphDigest='a'*64,
                        requirements=[dict(id='forbidden', state='SATISFIED')])
        for rows in ([], fidelity['requirements']*2, [dict(id='invented', state='SATISFIED')], [dict(id='forbidden', state='NOT_EVALUATED')]):
            with self.subTest(rows=rows), self.assertRaises(HostError):
                goal_context.compiler_evidence(self.requirements, self.requirements, dict(fidelity, requirements=rows), review, self.task)
        for graph in (None, 'not-a-canonical-graph'):
            with self.subTest(graph=graph), self.assertRaisesRegex(HostError, 'canonical graph'):
                goal_context.compiler_evidence(self.requirements, self.requirements, dict(fidelity, graphDigest=graph),
                    dict(review, exitCode=4, graphDigest=graph), self.task)

    def test_schema_allows_empty_maps_and_requires_evidence_without_acceptance_fields(self):
        self.configure(); contracts = public_contracts(host_tests.ROOT, observed_decisions=True)
        schema = investigation.output_schema(contracts['outputSchema'], self.config['investigation'], contracts['decisionRequirementsSchema'])
        interpretation = schema['oneOf'][1]['properties']['interpretation']
        for field in ('bindings', 'decisions'):
            self.assertEqual(0, interpretation['properties'][field]['minItems'])
        self.assertIn('evidenceRefs', interpretation['required'])
        self.assertNotIn('goalEvidence', schema['oneOf'][1]['properties'])


if __name__ == '__main__':
    unittest.main()
