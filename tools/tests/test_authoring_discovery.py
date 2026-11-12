import copy
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import discovery, investigation
from authoring_host.contracts import HostError, canonical, digest, loads, read, verify_inventory
from authoring_host.session import resume
import test_authoring_host as host_tests


def config(root):
    return dict(formatVersion=2, roots=[dict(id='workspace', path=str(root), scope='Selected environment inventory')],
                maxSteps=12, maxSourceBytes=4096, maxTotalSourceBytes=16384, maxRecords=64, maxEntries=32,
                bindingRequirements=[dict(id='owner',sourceQuote='Verify',source=dict(root='workspace',recordPath=[],
                    where=[dict(path=['owner'],equals='team')],valuePath=['owner']),
                    target=dict(selector=dict(capability='VERIFY'),parameter='resource'))])


def action(*observations, facts=(), findings=(), unknowns=(), propose=False, marker='accept'):
    result = dict(kind='propose' if propose else 'investigate', extractFacts=list(facts), findings=list(findings), unknowns=list(unknowns))
    if propose:
        result['response'] = dict(normalizedIntent=dict(marker=marker), report=dict(openQuestions=[]))
        result['bindings'] = [dict(requirementId='owner',factId='fact:owner')]
    else:
        result['observe'] = [dict(kind=kind, root='workspace', path=path) for kind, path in observations]
    return result


class DiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'workspace'; self.root.mkdir()
        (self.root / 'nested').mkdir()
        self.file = self.root / 'nested/state.json'
        self.file.write_bytes(b'{"resource":{"owner":"team","active":true,"count":1},"dependencies":["other.json"]}\n')
        (self.root / 'other.json').write_bytes(b'{"independent":42}')
        self.config = config(self.root); discovery.validate(self.config)
        self.ledger = discovery.initial(self.config)

    def apply(self, value):
        self.ledger = investigation.apply(self.ledger, value, self.config, {}, 'attempt')
        return self.ledger

    def observe(self):
        self.apply(action(('read', 'nested/state.json')))
        return self.ledger['observations'][0]['id']

    def fact(self, ref, name='owner', path=None):
        return dict(id=name, observationId=ref, path=path or ['resource','owner'])

    def test_listing_and_dependency_reads_do_not_require_a_file_inventory(self):
        self.apply(action(('list', '.')))
        self.assertEqual(['nested','other.json'], [e['name'] for e in self.ledger['observations'][0]['entries']])
        self.apply(action(('list','nested')))
        self.apply(action(('read','nested/state.json')))
        doc = json.loads(self.ledger['observations'][-1]['text'])
        self.apply(action(('read',doc['dependencies'][0])))
        self.assertEqual(4,len(self.ledger['observations']))
        self.assertNotIn(str(self.root),json.dumps(self.ledger))
        self.assertEqual(self.file.read_bytes(),self.ledger['observations'][2]['text'].encode())
        self.assertEqual(digest(self.file.read_bytes()),self.ledger['observations'][2]['sha256'])
        investigation.verify_sources(self.config,{},self.ledger)

    def test_exact_typed_facts_and_model_findings_remain_distinct(self):
        ref=self.observe()
        self.apply(action(facts=[self.fact(ref),self.fact(ref,'active',['resource','active']),self.fact(ref,'count',['resource','count']),
                                self.fact(ref,'dependency',['dependencies',0])],
                          findings=[dict(id='inference',statement='This resource may belong to the requested team.',evidenceRefs=['fact:owner'])]))
        facts={f['id']:f for f in self.ledger['facts']}
        self.assertIs(type(facts['fact:active']['value']),bool)
        self.assertIs(type(facts['fact:count']['value']),int)
        self.assertEqual('other.json',facts['fact:dependency']['value'])
        self.assertEqual('host-extracted-json-value',facts['fact:owner']['attribution'])
        self.assertEqual('model-inferred',self.ledger['findings'][0]['attribution'])
        old=copy.deepcopy(self.ledger)
        self.apply(action(facts=[self.fact(ref)]))
        self.assertEqual(old,self.ledger)

    def test_fact_values_sources_paths_and_names_cannot_be_forged(self):
        ref=self.observe()
        bad=[{**self.fact(ref),'value':'invented'},self.fact('unread'),self.fact(ref,path=['absent']),
             self.fact(ref,path=['dependencies',True]),self.fact(ref,path=['dependencies',1]),
             self.fact(ref,name='fact:owner'),self.fact(ref,path=['resource',0])]
        old=copy.deepcopy(self.ledger)
        for row in bad:
            with self.subTest(row=row),self.assertRaises(HostError):self.apply(action(facts=[row]))
            self.assertEqual(old,self.ledger)
        self.apply(action(facts=[self.fact(ref)]))
        with self.assertRaisesRegex(HostError,'immutable'):
            self.apply(action(facts=[self.fact(ref,path=['resource','active'])]))

    def test_observations_from_the_current_request_cannot_supply_preexisting_facts(self):
        expected='observation:'+digest(canonical(dict(kind='read',root='workspace',path='nested/state.json')))+':'+digest(self.file.read_bytes())
        with self.assertRaisesRegex(HostError,'delivered'):
            self.apply(action(('read','nested/state.json'),facts=[self.fact(expected)]))
        self.assertEqual([],self.ledger['observations'])

    def test_changed_source_invalidates_transitive_dependents_and_reopens_technical_unknowns(self):
        ref=self.observe()
        self.apply(action(('read','other.json'),facts=[self.fact(ref)],findings=[
            dict(id='first',statement='First inference',evidenceRefs=['fact:owner']),
            dict(id='second',statement='Dependent inference',evidenceRefs=['first'])],unknowns=[
            dict(id='ownership',question='Who owns this resource?',category='fact',blocking=True,status='addressed',evidenceRefs=['second'])]))
        other=self.ledger['observations'][-1]['id']
        self.apply(action(facts=[self.fact(other,'independent',['independent'])]))
        original=copy.deepcopy(self.ledger)
        self.file.write_text('{"resource":{"owner":"another-team"}}')
        with self.assertRaises(investigation.SourceChanged) as caught:discovery.verify(self.config,{},self.ledger)
        changed=caught.exception.ledger
        self.assertEqual(original,self.ledger)
        self.assertEqual(['invalidated','current'],[o['validity'] for o in changed['observations']])
        self.assertEqual(['invalidated','current'],[o['validity'] for o in changed['facts']])
        self.assertTrue(all(f['validity']=='invalidated' for f in changed['findings']))
        self.assertEqual('open',changed['unknowns'][-1]['status'])
        self.assertTrue(investigation.blocked(changed))

    def test_added_directory_entry_invalidates_listing_based_conclusions(self):
        self.apply(action(('list','.')))
        ref=self.ledger['observations'][0]['id']
        self.apply(action(findings=[dict(id='absence',statement='No further entries in this directory.',evidenceRefs=[ref])]))
        (self.root/'new.json').write_text('{}')
        with self.assertRaises(investigation.SourceChanged) as caught:discovery.verify(self.config,{},self.ledger)
        self.assertEqual('invalidated',caught.exception.ledger['findings'][0]['validity'])

    def test_restoring_old_bytes_cannot_revive_an_invalidated_ledger(self):
        ref=self.observe();original=self.file.read_bytes()
        self.apply(action(facts=[self.fact(ref)]))
        self.file.write_text('{}')
        with self.assertRaises(investigation.SourceChanged) as caught:discovery.verify(self.config,{},self.ledger)
        self.file.write_bytes(original)
        self.ledger=caught.exception.ledger
        with self.assertRaisesRegex(investigation.SourceChanged,'new session'):self.apply(action(propose=True))

    def test_replaced_root_invalidates_even_identical_bytes(self):
        self.observe()
        saved=self.root.with_name('previous');self.root.rename(saved);shutil.copytree(saved,self.root)
        with self.assertRaises(investigation.SourceChanged) as caught:discovery.verify(self.config,{},self.ledger)
        self.assertIn('root:workspace',caught.exception.ledger['invalidatedRefs'])
        self.assertEqual('invalidated',caught.exception.ledger['observations'][0]['validity'])

    def test_unread_content_is_not_loaded_or_assumed_to_be_evidence(self):
        (self.root/'private.txt').write_text('unread-secret')
        self.observe()
        discovery.verify(self.config,{'TOKEN':'unread-secret'},self.ledger)
        self.assertNotIn('unread-secret',json.dumps(self.ledger))
        with self.assertRaisesRegex(HostError,'credential'):
            discovery.capture(dict(kind='read',root='workspace',path='private.txt'),self.config,{'TOKEN':'unread-secret'})

    def test_changed_or_disappeared_files_fail_closed_without_disclosing_replacements(self):
        self.observe();self.file.unlink();self.file.symlink_to('/etc/passwd')
        with self.assertRaises(investigation.SourceChanged) as caught:discovery.verify(self.config,{},self.ledger)
        self.assertEqual('invalidated',caught.exception.ledger['validity'])
        self.assertNotIn('root:',json.dumps(caught.exception.ledger['observations']))

    def test_paths_roots_links_and_special_files_cannot_escape_the_grant(self):
        (self.root/'link').symlink_to(self.file)
        (self.root/'directory-link').symlink_to(self.root/'nested',target_is_directory=True)
        os.link(self.file,self.root/'hardlink')
        os.mkfifo(self.root/'fifo')
        (self.root/'binary').write_bytes(b'\xff')
        for path in ('/etc/passwd','../secret','nested/../other.json','nested//state.json','nested/./state.json',
                     'link','directory-link/state.json','hardlink','nested/state.json','fifo','binary','nested','a\\b','a\0b'):
            with self.subTest(path=path),self.assertRaises((HostError,OSError)):
                self.apply(action(('read',path)))
        with self.assertRaises(HostError):self.apply({**action(('list','.')),'observe':[dict(kind='list',root='not-granted',path='.') ]})
        with self.assertRaises(HostError):self.apply({**action(),'observe':[dict(kind='shell',root='workspace',path='.') ]})
        alias=self.root.with_name('alias');alias.symlink_to(self.root,target_is_directory=True)
        with self.assertRaises(OSError):discovery.initial(config(alias))
        self.assertEqual([],self.ledger['observations'])

    def test_entry_byte_and_record_budgets_reject_complete_updates(self):
        self.config['maxEntries']=1
        with self.assertRaisesRegex(HostError,'partial listing'):self.apply(action(('list','.')))
        self.config['maxEntries']=32;self.config['maxSourceBytes']=10
        with self.assertRaisesRegex(HostError,'byte limit'):self.observe()
        self.config['maxSourceBytes']=4096;self.config['maxRecords']=1
        with self.assertRaisesRegex(HostError,'record limit'):self.apply(action(('list','.'),('read','other.json')))
        self.config['maxRecords']=64;self.config['maxTotalSourceBytes']=len(self.file.read_bytes())
        with self.assertRaisesRegex(HostError,'total byte'):self.apply(action(('read','nested/state.json'),('read','other.json')))
        self.assertEqual([],self.ledger['observations'])

    def test_duplicate_json_fields_and_oversized_facts_are_not_extracted(self):
        for content in (b'{"a":true,"a":false}',canonical({'a':'x'*4097})):
            self.file.write_bytes(content);self.config['maxSourceBytes']=8192;self.ledger=discovery.initial(self.config)
            ref=self.observe()
            with self.assertRaises(HostError):self.apply(action(facts=[self.fact(ref,path=['a'])]))

    def test_configuration_and_output_do_not_expand_scope(self):
        invalid=[]
        for field,value in [('formatVersion',True),('maxSteps',17),('maxEntries',0),('roots',[])]:
            c=copy.deepcopy(self.config);c[field]=value;invalid.append(c)
        c=copy.deepcopy(self.config);c['roots'].append(dict(id='nested',path=str(self.root/'nested'),scope='other'));invalid.append(c)
        for path in ('/', '//', '/' + str(self.root), '/' + str(self.root/'nested')):
            c=copy.deepcopy(self.config);c['roots'][0]['path']=path;invalid.append(c)
        c=copy.deepcopy(self.config);c['shell']=True;invalid.append(c)
        for c in invalid:
            with self.subTest(config=c),self.assertRaises(HostError):discovery.validate(c)
        with self.assertRaises(HostError):discovery.check_output(self.config,self.root/'output')
        discovery.check_output(self.config,self.root.with_name('output'))

    def test_equal_bytes_in_distinct_roots_retain_distinct_provenance(self):
        other=self.root.with_name('another-workspace');other.mkdir()
        (other/'state.json').write_bytes(self.file.read_bytes())
        self.config['roots'].append(dict(id='another',path=str(other),scope='Independent scope'))
        self.ledger=discovery.initial(self.config)
        self.observe()
        a=action();a['observe']=[dict(kind='read',root='another',path='state.json')]
        self.apply(a)
        first,second=self.ledger['observations']
        self.assertEqual(first['sha256'],second['sha256']);self.assertNotEqual(first['id'],second['id'])
        self.assertNotEqual(first['scope'],second['scope'])

    def test_discovery_envelope_preserves_resolvable_public_schema_references(self):
        from authoring_host.contracts import public_contracts
        schema=investigation.output_schema(public_contracts(host_tests.ROOT)['outputSchema'],self.config)
        def check(node):
            if isinstance(node,dict):
                if '$ref' in node:
                    target=schema
                    for part in node['$ref'].removeprefix('#/').split('/'):target=target[part]
                    self.assertIsInstance(target,dict)
                for value in node.values():check(value)
            elif isinstance(node,list):
                for value in node:check(value)
        check(schema)
        self.assertIn('observe',schema['oneOf'][0]['required'])
        self.assertNotIn('readSources',schema['oneOf'][0]['properties'])
        self.assertTrue(all('extractFacts' in o['required'] for o in schema['oneOf']))


class DiscoverySessionTests(unittest.TestCase):
    setUp=host_tests.AuthoringHostTests.setUp
    run_host=host_tests.AuthoringHostTests.run_host

    def configure(self):
        self.workspace=self.root/'workspace';self.workspace.mkdir()
        self.source=self.workspace/'state.json';self.source.write_text('{"owner":"team"}')
        self.config['investigation']=config(self.workspace)
        self.requirements['formatVersion']=2
        self.config['budget'].update(maxCalls=8,maxTotalTokens=865536,maxCostMicrousd=2_000_000)
        selector=dict(kind='read',root='workspace',path='state.json')
        return 'observation:'+digest(canonical(selector))+':'+digest(self.source.read_bytes())

    def run_actions(self,values):return self.run_host([dict(response=v) for v in values])

    def test_no_observation_cannot_skip_the_caller_binding_contract(self):
        self.configure()
        state=self.run_actions([{**action(propose=True),'bindings':[]}])
        self.assertEqual('discovery-binding-blocked',state['status'])
        self.assertNotIn('review',state['attempts'][-1])
        assessment=loads(read(self.out/state['attempts'][-1]['discoveryBindingAssessment']))
        self.assertEqual('missing-binding',assessment['bindings'][0]['reason'])
        verify_inventory(self.out,state)

    def test_compiler_cannot_accept_using_a_different_requirements_receipt(self):
        ref=self.configure()
        from authoring_host.session import invoke as real_invoke
        def wrong_receipt(argv,data,timeout,env):
            result=real_invoke(argv,data,timeout,env)
            if 'proposal' in argv:
                file=Path(argv[argv.index('--out')+1])/'request-fidelity-review.json'
                receipt=loads(read(file));receipt['requirementsSource']['sha256']='b'*64
                file.write_bytes(canonical(receipt))
            return result
        with patch('authoring_host.session.invoke',wrong_receipt):
            state=self.run_actions([action(('read','state.json')),
                action(propose=True,facts=[dict(id='owner',observationId=ref,path=['owner'])])])
        self.assertEqual('provider-or-compiler-error',state['status'])
        self.assertEqual('compiler',state['attempts'][-1]['failurePhase'])
        self.assertNotIn('discoveryBindingResult',state['attempts'][-1])

    def test_discovery_resolves_additive_requirements_and_retains_compiler_receipt(self):
        ref=self.configure()
        state=self.run_actions([action(('list','.')),action(('read','state.json')),
            action(facts=[dict(id='owner',observationId=ref,path=['owner'])]),action(propose=True)])
        self.assertEqual('accepted',state['status']);self.assertEqual('bounded-authoring-host/3',state['hostVersion'])
        self.assertEqual('authoring-investigation/2',state['investigationProtocol'])
        payload=loads(read(self.out/'attempt-003/provider-request.json'))
        self.assertEqual('team',payload['input']['investigation']['facts'][0]['value'])
        self.assertNotIn(str(self.workspace),json.dumps(payload));self.assertNotIn('test-reviewer',json.dumps(payload))
        self.assertEqual(self.requirements,loads(read(self.out/'requirements-0.json')))
        attempt=state['attempts'][-1]
        resolved=loads(read(self.out/attempt['compilerRequirementsFile']))
        self.assertEqual(self.requirements['requirements'],resolved['requirements'][:-1])
        self.assertEqual('team',resolved['requirements'][-1]['check']['expected'])
        receipt=loads(read(self.out/attempt['discoveryBindingResult']))
        self.assertEqual('fact:owner',receipt['bindings'][0]['factId'])
        self.assertEqual({'SATISFIED'},set(receipt['compilerAssessment'].values()))
        verify_inventory(self.out,state)

    def test_repeated_observation_stops_and_limits_consume_the_existing_budget(self):
        self.configure()
        state=self.run_actions([action(('list','.'))])
        self.assertEqual('investigation-no-progress',state['status']);self.assertEqual(2,len(state['attempts']))
        self.out=self.root/'second';self.counter.unlink();self.config['investigation']['maxSteps']=1
        state=self.run_actions([action(('list','.')),action(('read','state.json'))])
        self.assertEqual('investigation-limit',state['status'])

    def test_changed_source_after_model_call_retains_invalidated_lineage_before_compiling(self):
        ref=self.configure()
        from authoring_host.session import invoke as real_invoke
        calls=0
        def changed(argv,data,timeout,env):
            nonlocal calls
            result=real_invoke(argv,data,timeout,env)
            if data and loads(data).get('schema')=='command-reasoning/v2':
                calls+=1
                if calls==3:self.source.write_text('{"owner":"replacement"}')
            return result
        with patch('authoring_host.session.invoke',changed):
            state=self.run_actions([action(('read','state.json')),
                action(facts=[dict(id='owner',observationId=ref,path=['owner'])],findings=[dict(id='inferred',statement='Owner inferred',evidenceRefs=['fact:owner'])]),
                action(propose=True)])
        self.assertEqual('source-changed',state['status']);self.assertFalse(any('review' in a for a in state['attempts']))
        ledger=investigation.load(self.out,state)
        self.assertEqual('invalidated',ledger['facts'][0]['validity']);self.assertEqual('invalidated',ledger['findings'][0]['validity'])
        self.assertEqual('current',loads(read(self.out/'attempt-001/investigation.json'))['facts'][0]['validity'])
        verify_inventory(self.out,state)

    def test_change_during_compilation_prevents_acceptance(self):
        ref=self.configure()
        from authoring_host.session import invoke as real_invoke
        def changed(argv,data,timeout,env):
            result=real_invoke(argv,data,timeout,env)
            if 'proposal' in argv:self.source.write_text('{"owner":"replacement"}')
            return result
        with patch('authoring_host.session.invoke',changed):state=self.run_actions([action(('read','state.json')),
            action(propose=True,facts=[dict(id='owner',observationId=ref,path=['owner'])])])
        self.assertEqual('source-changed',state['status']);self.assertEqual(0,state['attempts'][-1]['review']['exitCode'])

    def test_decisions_resume_only_when_evidence_stays_current(self):
        self.configure()
        question=dict(id='choice',question='Approve the selected scope?',category='decision',blocking=True,status='open',evidenceRefs=[])
        state=self.run_actions([action(('read','state.json')),action(propose=True,unknowns=[question]),action(propose=True)])
        self.assertEqual('awaiting-user',state['status'])
        answers=canonical(dict(runId=state['runId'],reviewerId='test-reviewer',answers=[dict(id='investigation:choice',answer='Approved scope')]))
        self.source.write_text('{"owner":"another-team"}')
        result=resume(self.out,answers,canonical(self.requirements))
        self.assertEqual('source-changed',result['status']);self.assertEqual('2',self.counter.read_text())
        self.assertEqual('open',investigation.load(self.out,result)['unknowns'][0]['status'])

    def test_open_conflicts_still_block_and_model_cannot_approve_decisions(self):
        self.configure()
        for category in ('fact','conflict','capability','decision'):
            self.out=self.root/category;self.counter.unlink(missing_ok=True)
            question=dict(id='missing',question='Required evidence missing?',category=category,blocking=True,status='open',evidenceRefs=[])
            state=self.run_actions([action(propose=True,unknowns=[question])])
            self.assertEqual('awaiting-user' if category=='decision' else 'investigation-blocked',state['status'])
            self.assertFalse(any('review' in a for a in state['attempts']))

    def test_current_evidence_survives_explicit_caller_clarification(self):
        ref=self.configure()
        question=dict(id='choice',question='Approve the selected scope?',category='decision',blocking=True,status='open',evidenceRefs=[])
        state=self.run_actions([action(('read','state.json')),
            action(propose=True,unknowns=[question],facts=[dict(id='owner',observationId=ref,path=['owner'])]),action(propose=True)])
        answers=canonical(dict(runId=state['runId'],reviewerId='test-reviewer',answers=[dict(id='investigation:choice',answer='Approved scope')]))
        result=resume(self.out,answers,canonical(self.requirements))
        self.assertEqual('accepted',result['status'])
        ledger=investigation.load(self.out,result)
        self.assertEqual('caller-answer-not-authenticated',ledger['unknowns'][-1]['attribution'])
        self.assertEqual('current',ledger['observations'][0]['validity'])
        verify_inventory(self.out,result)


if __name__=='__main__':unittest.main()
