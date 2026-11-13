"""Protocol tests; the scripted peer is not evidence of model reasoning."""
import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import discovery, discovery_review, investigation
from authoring_host.contracts import HostError, canonical, digest, loads, read, verify_inventory
from authoring_host.session import resume, review_discovery
import test_authoring_host as host_tests
from test_authoring_discovery import action, config


class DiscoveryReviewTests(unittest.TestCase):
    setUp = host_tests.AuthoringHostTests.setUp
    run_host = host_tests.AuthoringHostTests.run_host

    def configure(self):
        self.workspace = self.root / 'workspace'; self.workspace.mkdir()
        self.source = self.workspace / 'inventory.json'
        self.source.write_text('{"services":[{"name":"orders","handle":"resource://orders"}]}')
        self.config['investigation'] = config(self.workspace)
        self.config['investigation'].update(formatVersion=3)
        del self.config['investigation']['bindingRequirements']
        self.config['budget'].update(maxCalls=8, maxTotalTokens=865536, maxCostMicrousd=2_000_000)
        self.requirements['formatVersion'] = 5
        selector = dict(kind='read', root='workspace', path='inventory.json')
        ref = 'observation:' + digest(canonical(selector)) + ':' + digest(self.source.read_bytes())
        self.binding = dict(id='service', sourceQuote='Verify',
            source=dict(root='workspace', recordPath=['services', '*'],
                        where=[dict(path=['name'], equals='orders')], valuePath=['handle']),
            target=dict(selector=dict(capability='VERIFY'), parameter='resource'))
        self.proposal = action(propose=True)
        del self.proposal['bindings']
        self.proposal['interpretation'] = dict(summary='Verify the observed orders service; preserve recovery data.', bindings=[
            dict(meaning='The orders service handle identifies the verification subject.',
                 rationale='The observed record names the requested service.', requirement=self.binding, factId='fact:service')])
        self.actions = [action(('read', 'inventory.json')),
                        action(facts=[dict(id='service', observationId=ref, path=['services', 0, 'handle'])]), self.proposal]

    def run_actions(self, actions=None):
        return self.run_host([dict(response=a) for a in (actions or self.actions)])

    def decision(self, state, decision='accept'):
        return dict(formatVersion=1, runId=state['runId'],
            packetSha256=state['attempts'][-1]['discoveryReviewPacketSha256'], reviewerId='separate-reviewer',
            decision=decision, rationale='Reviewed the source interpretation and complete plan against the original goal.')

    def test_no_caller_mapping_and_no_compilation_before_separate_review(self):
        self.configure(); state = self.run_actions()
        self.assertEqual('awaiting-discovery-review', state['status'])
        self.assertEqual('bounded-authoring-host/5', state['hostVersion'])
        self.assertFalse(any('review' in a for a in state['attempts']))
        first = loads(read(self.out / 'attempt-000/provider-request.json'))
        self.assertEqual(self.request, first['input']['request'])
        self.assertNotIn('bindingRequirements', first['input']['investigation'])
        self.assertNotIn('recordPath', json.dumps(first['input']))
        self.assertNotIn('resource://orders', json.dumps(first['input']))
        self.assertNotIn('test-reviewer', json.dumps(first))
        packet = loads(read(self.out / state['attempts'][-1]['discoveryReviewPacket']))
        self.assertEqual('model-proposed-unreviewed', packet['bindingAssessment']['policyAttribution'])
        reviewed = review_discovery(self.out, canonical(self.decision(state)))
        self.assertEqual('accepted', reviewed['status'])
        self.assertEqual('3', self.counter.read_text())  # Review spends no provider calls.
        self.assertEqual(state['reservedTokens'], reviewed['reservedTokens'])
        self.assertEqual(self.requirements, loads(read(self.out / 'requirements-0.json')))
        attempt = reviewed['attempts'][-1]
        derived = loads(read(self.out / attempt['compilerRequirementsFile']))
        self.assertEqual(5, derived['formatVersion'])
        self.assertEqual(self.requirements['requirements'], derived['requirements'][:-1])
        self.assertEqual('resource://orders', derived['requirements'][-1]['check']['expected'])
        self.assertEqual('SELECTED_OPERATIONS', derived['requirements'][-1]['check']['evaluationScope'])
        receipt = loads(read(self.out / attempt['discoveryBindingResult']))
        self.assertEqual('model-proposed-caller-reviewed-not-authenticated', receipt['policyAttribution'])
        self.assertEqual(packet['responseSha256'], attempt['proposalResponseSha256'])
        verify_inventory(self.out, reviewed)
        with self.assertRaisesRegex(HostError, 'awaits review'):
            review_discovery(self.out, canonical(self.decision(state)))

    def test_rejection_never_calls_the_compiler_or_provider(self):
        self.configure(); state = self.run_actions()
        with patch('authoring_host.session.invoke', side_effect=AssertionError('No process may run')):
            result = review_discovery(self.out, canonical(self.decision(state, 'reject')))
        self.assertEqual('discovery-review-rejected', result['status'])
        self.assertFalse(any('review' in a for a in result['attempts']))
        with self.assertRaisesRegex(HostError, 'awaits review'):
            review_discovery(self.out, canonical(self.decision(state)))

    def test_review_is_bound_to_run_packet_and_explicit_decision(self):
        self.configure(); state = self.run_actions()
        for field, value in [('runId', 'other'), ('packetSha256', '0'*64), ('decision', 'approved'), ('reviewerId', ''),
                             ('formatVersion', True), ('rationale', '')]:
            bad = self.decision(state); bad[field] = value
            with self.subTest(field=field), self.assertRaises(HostError):
                review_discovery(self.out, canonical(bad))
        with self.assertRaisesRegex(HostError, 'explicit clarification'):
            resume(self.out, canonical(dict(runId=state['runId'], reviewerId='x', answers=[])), canonical(self.requirements))
        self.assertEqual('awaiting-discovery-review', loads(read(self.out/'state.json'))['status'])

    def test_review_cannot_be_reused_after_compilation_or_interruption(self):
        self.configure(); state = self.run_actions(); decision = canonical(self.decision(state))
        with patch('authoring_host.session._compile', side_effect=KeyboardInterrupt()), self.assertRaises(KeyboardInterrupt):
            review_discovery(self.out, decision)
        self.assertEqual('compiling-reviewed-discovery', loads(read(self.out/'state.json'))['status'])
        with self.assertRaisesRegex(HostError, 'awaits review'): review_discovery(self.out, decision)

    def test_packet_tampering_is_rejected(self):
        self.configure(); state = self.run_actions()
        file = self.out / state['attempts'][-1]['discoveryReviewPacket']
        value = loads(read(file)); value['response']['normalizedIntent']['marker'] = 'reject'
        file.write_bytes(canonical(value))
        with self.assertRaisesRegex(HostError, 'evidence changed'):
            review_discovery(self.out, canonical(self.decision(state)))

    def test_source_change_while_waiting_invalidates_review_without_compilation(self):
        self.configure(); state = self.run_actions(); self.source.write_text('{}')
        with patch('authoring_host.session._compile', side_effect=AssertionError('No compilation')):
            result = review_discovery(self.out, canonical(self.decision(state)))
        self.assertEqual('source-changed', result['status'])
        self.assertEqual('invalidated', investigation.load(self.out, result)['facts'][0]['validity'])

    def test_source_change_during_compilation_cannot_be_accepted(self):
        self.configure(); state = self.run_actions()
        from authoring_host.session import _compile
        def compile_and_change(*args):
            result = _compile(*args); self.source.write_text('{}'); return result
        with patch('authoring_host.session._compile', side_effect=compile_and_change):
            result = review_discovery(self.out, canonical(self.decision(state)))
        self.assertEqual('source-changed', result['status'])

    def test_model_cannot_self_approve_or_use_the_legacy_binding_shortcut(self):
        self.configure()
        for field in ('approval', 'reviewerId', 'bindings'):
            self.out = self.root / ('run-'+field); self.counter.unlink(missing_ok=True)
            candidate = copy.deepcopy(self.proposal); candidate[field] = 'approved'
            state = self.run_actions(self.actions[:-1] + [candidate])
            self.assertEqual('provider-or-compiler-error', state['status'])
            self.assertNotIn('review', state['attempts'][-1])

    def test_missing_facts_and_empty_maps_cannot_reach_review(self):
        self.configure()
        state = self.run_actions([self.proposal])
        self.assertEqual('discovery-binding-blocked', state['status'])
        self.out = self.root/'empty'; self.counter.unlink()
        self.proposal['interpretation']['bindings'] = []
        state = self.run_actions()
        self.assertEqual('provider-or-compiler-error', state['status'])

    def test_conflicting_delivered_records_cannot_reach_review(self):
        self.configure()
        (self.workspace/'another.json').write_text('{"services":[{"name":"orders","handle":"resource://different"}]}')
        self.actions.insert(1, action(('read', 'another.json')))
        state = self.run_actions()
        self.assertEqual('discovery-binding-blocked', state['status'])
        packet = loads(read(self.out/state['attempts'][-1]['discoveryReviewPacket']))
        self.assertEqual('missing-or-ambiguous-source-record', packet['bindingAssessment']['bindings'][0]['reason'])

    def test_constraints_must_be_confirmed_before_provider_dispatch(self):
        self.configure(); self.requirements['confirmation'] = 'PROVISIONAL'
        with self.assertRaisesRegex(HostError, 'confirmed independent constraints'): self.run_actions()
        self.assertFalse(self.counter.exists())

    def test_older_requirements_cannot_be_laundered_by_implicit_version_upgrade(self):
        self.configure()
        for version in range(1, 5):
            self.requirements['formatVersion'] = version
            # This field is invalid in the claimed version; v3 must not turn it valid.
            self.requirements['requirements'][0]['check']['evaluationScope'] = 'SELECTED_OPERATIONS'
            with self.subTest(version=version), self.assertRaisesRegex(HostError, 'constraints version 5'):
                self.run_actions()
            self.assertFalse(self.counter.exists())
            with self.assertRaisesRegex(HostError, 'implicit migration'):
                discovery_review.prepare(self.proposal, {}, self.requirements, [], 'run')

    def test_changed_goal_requires_a_new_packet_even_for_identical_candidate(self):
        self.configure(); first = self.run_actions(); prior = self.decision(first)
        self.out = self.root/'second'; self.counter.unlink()
        self.request['userText'] = 'Verify recovery first, then restore. Do not delete.'
        self.requirements['request'] = self.request
        second = self.run_actions()
        self.assertNotEqual(prior['packetSha256'], self.decision(second)['packetSha256'])
        prior['runId'] = second['runId']
        with self.assertRaisesRegex(HostError, 'exact discovery packet'):
            review_discovery(self.out, canonical(prior))

    def test_blocking_unknowns_do_not_turn_into_reviewable_success(self):
        self.configure()
        for category in ('fact', 'conflict', 'capability', 'decision'):
            self.out = self.root/category; self.counter.unlink(missing_ok=True)
            candidate = copy.deepcopy(self.proposal)
            candidate['unknowns'] = [dict(id='missing', question='Which resource is authorized?',
                category=category, blocking=True, status='open', evidenceRefs=[])]
            state = self.run_actions(self.actions[:-1] + [candidate])
            self.assertEqual('awaiting-user' if category=='decision' else 'investigation-blocked', state['status'])
            self.assertNotIn('discoveryReviewPacket', state['attempts'][-1])

    def test_compiler_rejection_cannot_trigger_unreviewed_repair(self):
        self.configure(); self.proposal['response']['normalizedIntent']['marker'] = 'reject'
        state = self.run_actions(); result = review_discovery(self.out, canonical(self.decision(state)))
        self.assertEqual('discovery-candidate-rejected', result['status'])
        self.assertEqual(0, result['repairCount']); self.assertEqual('3', self.counter.read_text())

    def test_user_clarification_does_not_approve_the_subsequent_interpretation(self):
        self.configure()
        ask = copy.deepcopy(self.proposal)
        ask['unknowns'] = [dict(id='owner', question='May this resource be used?', category='decision',
                               blocking=True, status='open', evidenceRefs=[])]
        state = self.run_actions(self.actions[:-1] + [ask, self.proposal])
        self.assertEqual('awaiting-user', state['status'])
        answers = dict(runId=state['runId'], reviewerId='test-reviewer',
                       answers=[dict(id='investigation:owner', answer='Use it for verification only.')])
        state = resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual('awaiting-discovery-review', state['status'])
        self.assertFalse(any('review' in a for a in state['attempts']))
        state = review_discovery(self.out, canonical(self.decision(state)))
        self.assertEqual('accepted', state['status'])
        self.assertEqual('4', self.counter.read_text())

    def test_new_config_cannot_hide_a_caller_binding_map(self):
        self.configure(); self.config['investigation']['bindingRequirements'] = [self.binding]
        with self.assertRaises(HostError): self.run_actions()

    def test_schema_and_protocol_advertise_interpretation_without_approval(self):
        self.configure()
        from authoring_host.contracts import public_contracts
        schema = investigation.output_schema(public_contracts(host_tests.ROOT)['outputSchema'], self.config['investigation'])
        proposal = schema['oneOf'][1]
        self.assertIn('interpretation', proposal['required'])
        self.assertNotIn('bindings', proposal['properties'])
        self.assertNotIn('approval', proposal['properties'])
        self.assertEqual('authoring-investigation/3', investigation.protocol(self.config['investigation']))


if __name__ == '__main__': unittest.main()
