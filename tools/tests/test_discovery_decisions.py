"""Observed choices and review invariants; plan semantics are tested by the installed CLI proof."""
import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import discovery_bindings, discovery_review, investigation
from authoring_host.contracts import HostError, canonical, digest, loads, public_contracts, read
from authoring_host.session import resume, review_discovery
import test_authoring_host as host_tests
from test_authoring_discovery import action
import test_discovery_review as review_tests


class ObservedDecisionTests(unittest.TestCase):
    setUp = host_tests.AuthoringHostTests.setUp
    run_host = host_tests.AuthoringHostTests.run_host
    run_actions = review_tests.DiscoveryReviewTests.run_actions
    decision = review_tests.DiscoveryReviewTests.decision

    def configure(self, exists=True):
        """The same interpretation must select a different obligation after a fresh observation."""
        review_tests.DiscoveryReviewTests.configure(self)
        self.config['investigation']['formatVersion'] = 4
        self.config['budget'].update(maxInputTokensPerCall=200000,maxTotalTokens=1665536,maxCostMicrousd=3_000_000)
        self.source.write_bytes(canonical(dict(services=[dict(name='orders', handle='resource://orders', exists=exists)])))
        selector = dict(kind='read', root='workspace', path='inventory.json')
        observation = 'observation:'+digest(canonical(selector))+':'+digest(self.source.read_bytes())
        for fact in self.actions[1]['extractFacts']:
            fact['observationId'] = observation
        self.actions[1]['extractFacts'].append(dict(id='exists', observationId=observation, path=['services',0,'exists']))
        source = copy.deepcopy(self.binding['source']); source['valuePath'] = ['exists']
        self.choice = dict(id='allocation', sourceQuote='Verify', meaning='Reuse only an explicitly present resource.',
            rationale='A complete selected record reports its presence; missing records do not mean absent.',
            inputs=[dict(id='present', source=source, factId='fact:exists')], cases=[
                dict(id='reuse', when=dict(present=True), obligations=[dict(id='no-create',check=dict(kind='forbid-capability',capability='PROVISION'))]),
                dict(id='create', when=dict(present=False), obligations=[dict(id='create',check=dict(kind='capability-count',selector=dict(capability='PROVISION'),minimum=1,maximum=1))])])
        self.proposal['interpretation']['decisions'] = [self.choice]

    def packet(self, state):
        return loads(read(self.out/state['attempts'][-1]['discoveryReviewPacket']))

    def test_typed_observation_selects_additive_compiler_obligations(self):
        self.configure(); state = self.run_actions(); packet = self.packet(state)
        self.assertEqual('bounded-authoring-host/6',state['hostVersion'])
        self.assertEqual('authoring-investigation/4',state['investigationProtocol'])
        self.assertEqual(2,packet['formatVersion'])
        row = packet['bindingAssessment']['decisions'][0]
        self.assertEqual('reuse',row['selectedCase']); self.assertIs(True,row['inputs'][0]['value'])
        self.assertNotIn('review',state['attempts'][-1])
        final = review_discovery(self.out,canonical(self.decision(state)))
        self.assertEqual('accepted',final['status'])
        attempt = final['attempts'][-1]
        derived = loads(read(self.out/attempt['compilerRequirementsFile']))
        self.assertEqual(self.requirements['requirements'],derived['requirements'][:1])
        self.assertEqual('SELECTED_OPERATIONS',derived['requirements'][1]['check']['evaluationScope'])
        self.assertEqual(self.choice['cases'][0]['obligations'][0]['check'],derived['requirements'][2]['check'])
        receipt = loads(read(self.out/attempt['discoveryBindingResult']))
        self.assertEqual('SATISFIED',receipt['compilerAssessment'][row['compilerRequirementIds'][0]])
        self.assertEqual('3',self.counter.read_text())

    def test_false_is_an_observed_value_not_a_missing_record(self):
        self.configure(False); state=self.run_actions()
        self.assertEqual('create',self.packet(state)['bindingAssessment']['decisions'][0]['selectedCase'])

    def test_boolean_and_integer_are_not_interchangeable(self):
        self.configure(1); state=self.run_actions()
        self.assertEqual('discovery-binding-blocked',state['status'])
        self.assertEqual('missing-or-ambiguous-decision-case',self.packet(state)['bindingAssessment']['decisions'][0]['reason'])

    def test_string_integer_and_null_choices_are_preserved_without_coercion(self):
        self.configure()
        for i,value in enumerate(('retained',7,None)):
            self.out=self.root/f'typed-{i}'; self.counter.unlink(missing_ok=True)
            self.source.write_bytes(canonical(dict(services=[dict(name='orders',handle='resource://orders',exists=value)])))
            selector=dict(kind='read',root='workspace',path='inventory.json')
            observation='observation:'+digest(canonical(selector))+':'+digest(self.source.read_bytes())
            for fact in self.actions[1]['extractFacts']: fact['observationId']=observation
            self.choice['cases'][0]['when']['present']=value
            state=self.run_actions()
            self.assertEqual('awaiting-discovery-review',state['status'])
            self.assertEqual(canonical(value),canonical(self.packet(state)['bindingAssessment']['decisions'][0]['inputs'][0]['value']))

    def test_no_case_and_multiple_cases_block_instead_of_falling_through(self):
        self.configure()
        for i,value in enumerate((False,True)):
            self.out=self.root/f'case-{i}';self.counter.unlink(missing_ok=True)
            for case in self.choice['cases']: case['when']['present']=value
            state=self.run_actions()
            self.assertEqual('discovery-binding-blocked',state['status'])
            self.assertNotIn('review',state['attempts'][-1])

    def test_missing_unrelated_or_invalidated_facts_cannot_select_a_case(self):
        self.configure()
        for i,fact in enumerate(('fact:missing','fact:service')):
            self.out=self.root/f'fact-{i}'; self.counter.unlink(missing_ok=True)
            self.choice['inputs'][0]['factId']=fact
            state=self.run_actions()
            self.assertEqual('discovery-binding-blocked',state['status'])
        self.choice['inputs'][0]['factId']='fact:exists'
        ledger=investigation.load(self.out,state)
        ledger['facts'][-1]['validity']='invalidated'
        derived,assessment,_=discovery_review.prepare(self.proposal,ledger,self.requirements,loads(read(self.out/'contracts.json'))['capabilityCatalog'],'test')
        self.assertIsNone(derived);self.assertEqual('blocked',assessment['status'])

    def test_conflicting_delivered_records_cannot_be_ignored(self):
        self.configure()
        (self.workspace/'other.json').write_text('{"services":[{"name":"orders","exists":false,"handle":"resource://orders"}]}')
        self.actions.insert(1,action(('read','other.json')))
        state=self.run_actions()
        self.assertEqual('discovery-binding-blocked',state['status'])
        self.assertEqual('missing-or-ambiguous-source-record',self.packet(state)['bindingAssessment']['decisions'][0]['reason'])

    def test_each_alternative_must_constrain_all_inputs(self):
        self.configure()
        self.choice['inputs'].append(dict(id='owned',source=copy.deepcopy(self.choice['inputs'][0]['source']),factId='fact:exists'))
        state=self.run_actions()
        self.assertEqual('provider-or-compiler-error',state['status'])
        for case in self.choice['cases']: case['when']['owned']=case['when']['present']
        self.out=self.root/'complete';self.counter.unlink()
        state=self.run_actions();self.assertEqual('awaiting-discovery-review',state['status'])

    def test_decisions_without_parameter_bindings_are_supported_but_empty_work_is_not(self):
        self.configure();self.proposal['interpretation']['bindings']=[]
        state=self.run_actions();self.assertEqual('awaiting-discovery-review',state['status'])
        final=review_discovery(self.out,canonical(self.decision(state)));self.assertEqual('accepted',final['status'])
        self.out=self.root/'empty';self.counter.unlink();self.proposal['interpretation']['decisions']=[]
        self.assertEqual('provider-or-compiler-error',self.run_actions()['status'])

    def test_old_protocol_cannot_accept_new_decisions(self):
        self.configure();self.config['investigation']['formatVersion']=3
        self.assertEqual('provider-or-compiler-error',self.run_actions()['status'])

    def test_changed_observation_invalidates_review_and_new_run_selects_new_case(self):
        self.configure();first=self.run_actions();approval=self.decision(first)
        self.source.write_text('{"services":[{"name":"orders","exists":false,"handle":"resource://orders"}]}')
        with patch('authoring_host.session._compile',side_effect=AssertionError('No stale compilation')):
            final=review_discovery(self.out,canonical(approval))
        self.assertEqual('source-changed',final['status'])
        selector=dict(kind='read',root='workspace',path='inventory.json')
        for fact in self.actions[1]['extractFacts']:
            fact['observationId']='observation:'+digest(canonical(selector))+':'+digest(self.source.read_bytes())
        self.out=self.root/'fresh';self.counter.unlink();second=self.run_actions()
        self.assertEqual('create',self.packet(second)['bindingAssessment']['decisions'][0]['selectedCase'])
        with self.assertRaises(HostError): review_discovery(self.out,canonical(approval))

    def test_changed_source_during_compile_cannot_be_accepted(self):
        self.configure();state=self.run_actions()
        from authoring_host.session import _compile
        def change(*args):
            result=_compile(*args);self.source.write_text('{}');return result
        with patch('authoring_host.session._compile',side_effect=change):
            final=review_discovery(self.out,canonical(self.decision(state)))
        self.assertEqual('source-changed',final['status'])

    def test_user_decision_continues_to_separate_review_and_compilation(self):
        self.configure();ask=copy.deepcopy(self.proposal)
        ask['unknowns']=[dict(id='permission',question='May this selected resource be reused?',category='decision',blocking=True,status='open',evidenceRefs=[])]
        state=self.run_actions(self.actions[:-1]+[ask,self.proposal]);self.assertEqual('awaiting-user',state['status'])
        answer=dict(runId=state['runId'],reviewerId='test-reviewer',answers=[dict(id='investigation:permission',answer='Reuse it for verification.')])
        state=resume(self.out,canonical(answer),canonical(self.requirements));self.assertEqual('awaiting-discovery-review',state['status'])
        self.assertNotIn('review',state['attempts'][-1])
        final=review_discovery(self.out,canonical(self.decision(state)));self.assertEqual('accepted',final['status'])
        self.assertEqual(1,final['clarificationCount']);self.assertEqual('4',self.counter.read_text())

    def test_compiler_cannot_omit_selected_decision_checks(self):
        self.configure();state=self.run_actions();assessment=self.packet(state)['bindingAssessment']
        fidelity=dict(requirementsSource=dict(sha256=assessment['compilerRequirementsSha256']),graphDigest='a'*64,
            requirements=[dict(id=row['compilerRequirementId'],state='SATISFIED') for row in assessment['bindings']])
        with self.assertRaisesRegex(HostError,'omitted'):
            discovery_bindings.verify_compiler_receipt(assessment,fidelity,dict(graphDigest='a'*64,exitCode=0))

    def test_precanonical_rejection_retains_unevaluated_obligations_without_a_graph(self):
        self.configure();self.proposal['response']['normalizedIntent']['marker']='reject'
        state=self.run_actions();final=review_discovery(self.out,canonical(self.decision(state)))
        self.assertEqual('discovery-candidate-rejected',final['status'])
        receipt=loads(read(self.out/final['attempts'][-1]['discoveryBindingResult']))
        self.assertIsNone(receipt['compilerGraphDigest'])
        self.assertTrue(all(v=='NOT_EVALUATED' for v in receipt['compilerAssessment'].values()))
        self.assertEqual('3',self.counter.read_text())

    def test_missing_graph_cannot_certify_evaluated_or_accepted_plan(self):
        assessment=dict(compilerRequirementsSha256='b'*64,bindings=[],decisions=[dict(compilerRequirementIds=['selected'])])
        fidelity=dict(requirementsSource=dict(sha256='b'*64),state='NOT_EVALUATED',requirements=[dict(id='selected',state='NOT_EVALUATED')])
        review=dict(exitCode=4,semanticAcceptance='rejected',requestFidelity='not-evaluated')
        for field,value in [('exitCode',0),('semanticAcceptance','accepted'),('requestFidelity','requirements-satisfied')]:
            with self.subTest(field=field),self.assertRaises(HostError):
                discovery_bindings.verify_compiler_receipt(assessment,fidelity,dict(review,**{field:value}))
        for mutation in (dict(fidelity,state='REQUIREMENTS_SATISFIED'),
                         dict(fidelity,requirements=[dict(id='selected',state='SATISFIED')])):
            with self.assertRaises(HostError):discovery_bindings.verify_compiler_receipt(assessment,mutation,review)

    def test_schema_references_public_checks_and_never_admits_model_approval(self):
        self.configure();contracts=public_contracts(host_tests.ROOT,observed_decisions=True)
        schema=investigation.output_schema(contracts['outputSchema'],self.config['investigation'],contracts['decisionRequirementsSchema'])
        self.assertEqual(contracts['decisionRequirementsSchema'],schema['$defs']['decisionRequirements'])
        def resolve_refs(node):
            if isinstance(node,dict):
                if '$ref' in node:
                    self.assertTrue(node['$ref'].startswith('#/'))
                    target=schema
                    for part in node['$ref'][2:].split('/'):
                        target=target[part]
                for value in node.values(): resolve_refs(value)
            elif isinstance(node,list):
                for value in node: resolve_refs(value)
        resolve_refs(schema)
        interpretation=schema['oneOf'][1]['properties']['interpretation']
        self.assertNotIn('selectedCase',json.dumps(interpretation))
        self.assertNotIn('approval',json.dumps(interpretation))


if __name__=='__main__': unittest.main()
