import copy
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import discovery, discovery_bindings as bindings, investigation
from authoring_host.contracts import HostError, canonical, digest
from test_authoring_discovery import config, action


class DiscoveryBindingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = config(self.root)
        self.policy = [dict(id='selected',sourceQuote='Verify',source=dict(root='workspace',recordPath=['items','*'],
            where=[dict(path=['name'],equals='target'),dict(path=['ready'],equals=True)],valuePath=['id']),
            target=dict(selector=dict(capability='VERIFY'),parameter='resource'))]
        self.config['bindingRequirements'] = self.policy
        self.original = dict(formatVersion=2,request=dict(userText='Verify the observed target.'),origin='REVIEWER_AUTHORED',
            confirmation='CONFIRMED',reviewerId='caller',requirements=[dict(id='one-verify',sourceQuote='Verify',
                check=dict(kind='capability-count',selector=dict(capability='VERIFY'),minimum=1))])
        self.response = dict(normalizedIntent={})
        self.document = dict(items=[dict(name='decoy',ready=True,id='resource://decoy'),
                                   dict(name='target',ready=True,id='resource://actual')])
        self.observe()

    def observe(self):
        (self.root/'inventory.json').write_bytes(canonical(self.document))
        self.ledger = discovery.apply(discovery.initial(self.config),action(('read','inventory.json')),self.config,{},'read')
        self.ref = self.ledger['observations'][0]['id']
        self.extract('selected',['items',1,'id'])

    def extract(self, identity, path):
        self.ledger = discovery.apply(self.ledger,action(facts=[dict(id=identity,observationId=self.ref,path=path)]),self.config,{},'extract')

    def resolve(self, choices=None):
        return bindings.resolve(self.policy,self.ledger,
            [dict(requirementId='selected',factId='fact:selected')] if choices is None else choices,self.original,self.response)

    def test_unknown_value_becomes_additive_compiler_constraint_without_mutating_inputs(self):
        before = copy.deepcopy((self.original,self.ledger,self.policy))
        derived, receipt = self.resolve()
        self.assertEqual(before,(self.original,self.ledger,self.policy))
        self.assertEqual(self.original['requirements'],derived['requirements'][:-1])
        self.assertNotIn('resource://actual',canonical([self.original,self.policy]).decode())
        check = derived['requirements'][-1]['check']
        self.assertEqual(dict(kind='literal-parameter',selector=dict(capability='VERIFY'),parameter='resource',expected='resource://actual'),check)
        row = receipt['bindings'][0]
        self.assertEqual(['items',1,'id'],row['sourcePath']); self.assertEqual(self.ref,row['observationId'])
        self.assertEqual(digest(canonical(derived)),receipt['compilerRequirementsSha256'])

    def test_no_observations_or_missing_binding_cannot_resolve(self):
        for ledger, choices in ((discovery.initial(self.config),[dict(requirementId='selected',factId='fact:selected')]),(self.ledger,[])):
            self.ledger=ledger
            derived,receipt=self.resolve(choices)
            self.assertIsNone(derived);self.assertEqual('blocked',receipt['status'])

    def test_real_fact_from_wrong_record_cannot_satisfy_policy(self):
        self.extract('decoy',['items',0,'id'])
        derived,receipt=self.resolve([dict(requirementId='selected',factId='fact:decoy')])
        self.assertIsNone(derived);self.assertEqual('fact-does-not-match-required-source',receipt['bindings'][0]['reason'])

    def test_equal_value_from_wrong_path_is_not_a_binding(self):
        self.document['copy']='resource://actual';self.observe();self.extract('copy',['copy'])
        self.assertIsNone(self.resolve([dict(requirementId='selected',factId='fact:copy')])[0])

    def test_model_findings_and_fabricated_fact_ids_are_not_sources(self):
        for identity in ('missing','fact:invented','model-inference'):
            self.assertIsNone(self.resolve([dict(requirementId='selected',factId=identity)])[0])

    def test_ambiguous_delivered_records_block_even_equal_values(self):
        for value in ('resource://different','resource://actual'):
            self.document['items'].append(dict(name='target',ready=True,id=value));self.observe()
            self.assertIsNone(self.resolve()[0]);self.document['items'].pop()

    def test_candidate_from_another_root_is_not_promoted(self):
        self.ledger['observations'][0]['selector']['root']='another'
        self.assertIsNone(self.resolve()[0])

    def test_invalidated_fact_is_never_accepted(self):
        self.ledger['facts'][0]['validity']='invalidated'
        self.assertIsNone(self.resolve()[0])

    def test_forged_fact_value_is_compared_with_source_bytes(self):
        self.ledger['facts'][0]['value']='resource://forged'
        self.assertIsNone(self.resolve()[0])

    def test_predicates_do_not_coerce_booleans_and_numbers(self):
        self.document['items'][1]['ready']=1;self.observe()
        self.assertIsNone(self.resolve()[0])

    def test_non_string_parameters_remain_unsupported_without_coercion(self):
        for value in (True,1,None,{'id':'resource://actual'},['resource://actual']):
            with self.subTest(value=value):
                self.document['items'][1]['id']=value;self.observe()
                self.assertIsNone(self.resolve()[0])

    def test_duplicate_unknown_or_provider_redefined_obligations_are_rejected(self):
        choice=dict(requirementId='selected',factId='fact:selected')
        for choices in ([choice,choice],[dict(requirementId='unknown',factId='fact:selected')],[dict(choice,target={})]):
            with self.assertRaises(HostError):self.resolve(choices)

    def test_policy_cannot_filter_on_the_parameter_it_checks(self):
        self.policy[0]['target']['selector']['parameters']={'resource':'resource://actual'}
        with self.assertRaises(HostError):discovery.validate(self.config)

    def test_binding_configuration_and_context_are_bounded_before_dispatch(self):
        bindings.validate_context(self.policy,self.original,[dict(capability='VERIFY')])
        for policy in ([],self.policy*17):
            with self.assertRaises(HostError):bindings.validate(policy,{'workspace'})
        with self.assertRaises(HostError):bindings.validate_context(self.policy,self.original,[dict(capability='BACKUP')])
        legacy=copy.deepcopy(self.original);legacy['formatVersion']=1
        with self.assertRaises(HostError):bindings.validate_context(self.policy,legacy,[dict(capability='VERIFY')])
        unrelated=copy.deepcopy(self.policy);unrelated[0]['sourceQuote']='not in the original request'
        with self.assertRaises(HostError):bindings.validate_context(unrelated,self.original,[dict(capability='VERIFY')])
        self.original['requirements'][0]['id']=bindings.requirement_id(self.policy[0])
        with self.assertRaises(HostError):bindings.validate_context(self.policy,self.original,[dict(capability='VERIFY')])

    def test_wildcard_expansion_is_bounded(self):
        with self.assertRaises(HostError):bindings.records({'items':list(range(129))},['items','*'])

    def test_missing_fields_are_unresolved_not_null_evidence(self):
        self.policy[0]['source']['valuePath']=['missing']
        self.assertIsNone(self.resolve()[0])

    def test_receipt_requires_the_resolved_requirements_graph_and_obligations(self):
        _,assessment=self.resolve();identity=assessment['bindings'][0]['compilerRequirementId']
        fidelity=dict(requirementsSource=dict(sha256=assessment['compilerRequirementsSha256']),graphDigest='a'*64,
            requirements=[dict(id=identity,state='SATISFIED')])
        review=dict(exitCode=0,graphDigest='a'*64)
        self.assertEqual({identity:'SATISFIED'},bindings.verify_compiler_receipt(assessment,fidelity,review)['compilerAssessment'])
        for change in ('digest','graph','missing','violated'):
            bad=copy.deepcopy(fidelity)
            if change=='digest':bad['requirementsSource']['sha256']='b'*64
            if change=='graph':bad['graphDigest']='b'*64
            if change=='missing':bad['requirements']=[]
            if change=='violated':bad['requirements'][0]['state']='VIOLATED'
            with self.subTest(change=change),self.assertRaises(HostError):bindings.verify_compiler_receipt(assessment,bad,review)


if __name__=='__main__':unittest.main()
