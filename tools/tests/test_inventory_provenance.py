import base64
import copy
from datetime import timedelta
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import discovery, investigation, inventory_collection as collection, inventory_provenance as provenance
from authoring_host.contracts import HostError, canonical, digest, loads, read, verify_inventory
from authoring_host.session import resume
import test_authoring_host as host_tests
from test_authoring_discovery import config as discovery_config, action

COLLECTOR = Path(__file__).parent / 'fixtures/inventory_collector.py'


def source(root, request, documents=None):
    key = root / 'signing.pem'
    subprocess.run(['openssl','genpkey','-algorithm','Ed25519','-out',str(key)], check=True, capture_output=True)
    key.chmod(0o600)
    public = subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout','-outform','DER'])
    trust = dict(issuerId='test-collector',keyId='test-key',publicKey=base64.b64encode(public).decode(),system='local-inventory',scope='development')
    db = root / 'source.sqlite'
    with sqlite3.connect(db) as connection:
        connection.execute('CREATE TABLE metadata(system, scope, revision)')
        connection.execute('INSERT INTO metadata VALUES (?,?,?)',('local-inventory','development','revision-1'))
        connection.execute('CREATE TABLE documents(path, content)')
        connection.executemany('INSERT INTO documents VALUES (?,?)',(documents or {'state.json':'{"owner":"team"}'}).items())
    config = dict(formatVersion=1,argv=[sys.executable,str(COLLECTOR), '--database',str(db)],credentialEnv=[],timeoutSeconds=15)
    capture = collection.prepare(request,'local-inventory','development')
    return key,trust,db,config,capture


class InventoryProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.request = dict(userText='Verify the inventory owner.')
        self.key,self.trust,self.db,self.collector,self.capture = source(self.root,self.request,
            {'nested/state.json':'{"owner":"team"}','other.json':'{"unread":true}'})
        self.result = collection.collect(self.capture,self.collector,self.trust,self.key,self.root/'collection')
        self.policy = self.result['inventory']
        self.workspace = Path(self.result['inventoryRoot'])
        self.config = discovery_config(self.workspace); self.config['roots'][0]['inventory'] = self.policy
        self.raw = Path(self.policy['attestationFile']).read_bytes()
        self.payload = loads(base64.b64decode(loads(self.raw)['payload']))

    def test_real_process_capture_preserves_exact_source_bytes_and_separate_trust(self):
        before = self.db.read_bytes()
        payload,receipt = provenance.verify_envelope(self.raw,self.policy)
        self.assertEqual('revision-1',payload['sourceRevision'])
        self.assertFalse(receipt['upstreamFreshnessEstablished'])
        self.assertEqual(digest(canonical(self.collector)),payload['collectorConfigSha256'])
        state = loads(read(self.root/'collection/state.json')); verify_inventory(self.root/'collection',state)
        self.assertEqual('collected',state['status'])
        ledger = discovery.initial(self.config)
        self.assertEqual(self.raw.decode(),ledger['inventoryAttestations']['workspace'])
        self.assertNotIn('inventoryAttestations',investigation.public(ledger))
        self.assertNotIn('nested/state.json',json.dumps(investigation.public(ledger)))
        self.assertEqual(before,self.db.read_bytes())

    def test_actual_source_update_needs_a_new_collection_not_a_relabelled_old_export(self):
        with sqlite3.connect(self.db) as connection:
            connection.execute('UPDATE documents SET content=? WHERE path=?',('{"owner":"replacement"}','nested/state.json'))
        later = collection.collect(collection.prepare(self.request,'local-inventory','development'),self.collector,self.trust,
                                   self.key,self.root/'later')
        self.assertEqual('{"owner":"replacement"}',(Path(later['inventoryRoot'])/'nested/state.json').read_text())
        self.assertEqual('{"owner":"team"}',(self.workspace/'nested/state.json').read_text())
        # An unchanged export remains a signed past collection, never proof of current remote state.
        self.assertFalse(provenance.verify_envelope(self.raw,self.policy)[1]['upstreamFreshnessEstablished'])
        with self.assertRaises(HostError):provenance.verify_envelope(self.raw,later['inventory'])

    def test_signer_scope_and_challenge_cannot_be_selected_by_the_export(self):
        for field in ('issuerId','keyId','system','scope'):
            bad = copy.deepcopy(self.policy); bad['trust'][field] = 'other'
            if field in ('system','scope'):bad['captureRequest'][field]='other'
            with self.subTest(field=field),self.assertRaises(HostError):provenance.verify_envelope(self.raw,bad)
        bad = copy.deepcopy(self.policy); bad['captureRequest']['challenge']='0'*64
        with self.assertRaises(HostError):provenance.verify_envelope(self.raw,bad)
        with self.assertRaises(HostError):provenance.validate_context(self.config,dict(userText='Another request'))

    def test_altered_signed_fields_and_signature_fail(self):
        for field,value in (('system','other'),('collectionStartedAt','2020-01-01T00:00:00Z'),('files',[])):
            changed = dict(self.payload,**{field:value}); envelope=loads(self.raw)
            envelope['payload']=base64.b64encode(canonical(changed)).decode()
            with self.subTest(field=field),self.assertRaises(HostError):provenance.verify_envelope(canonical(envelope),self.policy)
        envelope=loads(self.raw); envelope['signature']=base64.b64encode(b'\0'*64).decode()
        with self.assertRaises(HostError):provenance.verify_envelope(canonical(envelope),self.policy)

    def test_other_valid_key_and_signature_domain_do_not_authenticate_inventory(self):
        other=self.root/'other.pem'
        subprocess.run(['openssl','genpkey','-algorithm','Ed25519','-out',str(other)],check=True,capture_output=True)
        raw=collection.sign(self.payload,other.read_bytes())
        with self.assertRaises(HostError):provenance.verify_envelope(raw,self.policy)
        envelope=loads(self.raw)
        message=self.root/'wrong-domain';message.write_bytes(b'Horistum operation observation\0v1\0'+canonical(self.payload))
        signature=subprocess.check_output(['openssl','pkeyutl','-sign','-inkey',str(self.key),'-rawin','-in',str(message)])
        envelope['signature']=base64.b64encode(signature).decode()
        with self.assertRaises(HostError):provenance.verify_envelope(canonical(envelope),self.policy)

    def test_future_expired_and_reversed_collection_windows_fail(self):
        current=provenance.now()
        for start,end in ((current+timedelta(seconds=90),current+timedelta(seconds=91)),
                          (current-timedelta(hours=1),current), (current,current-timedelta(seconds=1))):
            payload=dict(self.payload,collectionStartedAt=start.isoformat(),collectionCompletedAt=end.isoformat())
            with self.subTest(start=start),self.assertRaises(HostError):
                provenance.verify_envelope(collection.sign(payload,self.key.read_bytes()),self.policy)
        with patch('authoring_host.inventory_provenance.now',return_value=current+timedelta(seconds=901)),self.assertRaises(HostError):
            provenance.verify_envelope(self.raw,self.policy)

    def test_age_counts_from_collection_start_not_only_completion(self):
        policy=copy.deepcopy(self.policy); policy['maxAgeSeconds']=1
        start=provenance.timestamp(self.payload['collectionStartedAt'])
        with patch('authoring_host.inventory_provenance.now',return_value=start+timedelta(seconds=2)),self.assertRaises(HostError):
            provenance.verify_envelope(self.raw,policy)

    def test_every_file_and_directory_must_match_even_when_not_read_by_the_model(self):
        original=(self.workspace/'other.json').read_bytes()
        for mutation in ('change','remove','additional','empty-directory','link'):
            with self.subTest(mutation=mutation):
                if mutation=='change':(self.workspace/'other.json').write_text('{}')
                elif mutation=='remove':(self.workspace/'other.json').unlink()
                elif mutation=='additional':(self.workspace/'extra.json').write_text('{}')
                elif mutation=='empty-directory':(self.workspace/'empty').mkdir()
                else:(self.workspace/'link').symlink_to(self.workspace/'nested/state.json')
                with self.assertRaises((HostError,OSError)):discovery.initial(self.config)
                if mutation in ('change','remove'):(self.workspace/'other.json').write_bytes(original)
                elif mutation=='empty-directory':(self.workspace/'empty').rmdir()
                else:(self.workspace/('link' if mutation=='link' else 'extra.json')).unlink()

    def test_expiry_invalidates_facts_findings_and_unknowns_even_with_unchanged_files(self):
        ledger=discovery.apply(discovery.initial(self.config),action(('read','nested/state.json')),self.config,{},'read')
        ref=ledger['observations'][0]['id']
        ledger=discovery.apply(ledger,action(facts=[dict(id='owner',observationId=ref,path=['owner'])],
            findings=[dict(id='inference',statement='Owner inference',evidenceRefs=['fact:owner'])],
            unknowns=[dict(id='owner-question',question='Who owns it?',category='fact',blocking=True,status='addressed',evidenceRefs=['inference'])]),self.config,{},'extract')
        with patch('authoring_host.inventory_provenance.now',return_value=provenance.now()+timedelta(seconds=901)):
            with self.assertRaises(investigation.SourceChanged) as caught:discovery.verify(self.config,{},ledger)
        changed=caught.exception.ledger
        self.assertEqual('invalidated',changed['facts'][0]['validity'])
        self.assertEqual('invalidated',changed['findings'][0]['validity'])
        self.assertEqual('open',changed['unknowns'][-1]['status'])
        self.assertIn('expired',changed['inventoryFailures'][0]['reason'])
        with self.assertRaises(investigation.SourceChanged):discovery.verify(self.config,{},changed)

    def test_replacing_a_valid_attestation_cannot_refresh_an_existing_session(self):
        ledger=discovery.initial(self.config)
        payload=dict(self.payload,sourceRevision='revision-2')
        Path(self.policy['attestationFile']).write_bytes(collection.sign(payload,self.key.read_bytes()))
        with self.assertRaises(investigation.SourceChanged):discovery.verify(self.config,{},ledger)

    def test_transient_capture_must_match_signed_bytes_even_if_surrounding_reads_match(self):
        ledger=discovery.initial(self.config);actual=discovery.capture;reads=0
        def racing(selector,*args):
            nonlocal reads
            captured=actual(selector,*args)
            if selector['kind']=='read' and selector['path']=='nested/state.json':
                reads+=1
                # Manifest scans see the signed version; observation scans see another version.
                if reads in (2,4):
                    data=b'{"owner":"forged"}'
                    captured=dict(text=data.decode(),sha256=digest(data),sizeBytes=len(data))
            return captured
        with patch('authoring_host.discovery.capture',racing),self.assertRaises(investigation.SourceChanged) as caught:
            discovery.apply(ledger,action(('read','nested/state.json')),self.config,{},'racing-read')
        self.assertEqual([],caught.exception.ledger['observations'])
        self.assertIn('authenticated inventory',caught.exception.ledger['inventoryFailures'][0]['reason'])

    def test_attestation_paths_and_configuration_are_strict(self):
        for value in ('relative','/tmp/../escape','//tmp/file'):
            bad=copy.deepcopy(self.policy);bad['attestationFile']=value
            with self.assertRaises(HostError):provenance.validate_policy(bad)
        for field,value in (('maxAgeSeconds',True),('maxAgeSeconds',3601),('maxClockSkewSeconds',61)):
            bad=copy.deepcopy(self.policy);bad[field]=value
            with self.assertRaises(HostError):provenance.validate_policy(bad)
        bad=copy.deepcopy(self.config);bad['roots'][0]['inventory']['attestationFile']=str(self.workspace/'attestation.json')
        with self.assertRaises(HostError):discovery.validate(bad)
        link=self.root/'attestation-link';link.symlink_to(self.policy['attestationFile'])
        bad=copy.deepcopy(self.config);bad['roots'][0]['inventory']['attestationFile']=str(link)
        with self.assertRaises(OSError):discovery.initial(bad)

    def test_malformed_or_unbounded_manifest_paths_are_rejected(self):
        entry=dict(path='data.json',sha256='a'*64,sizeBytes=1)
        for entries in ([entry,entry],[dict(entry,path='../outside')],[dict(entry,path='/absolute')],
                        [dict(entry,path='parent'),dict(entry,path='parent/child')], [entry]*129,
                        [dict(entry,sizeBytes=65537)],[dict(entry,sizeBytes=True)]):
            with self.subTest(entries=entries[:2]),self.assertRaises(HostError):provenance.validate_entries(entries)

    def test_wrong_source_scope_request_and_failed_process_publish_no_evidence(self):
        for mutation in ('scope','request','failure'):
            config=copy.deepcopy(self.collector);config['argv']+=['--mutation',mutation]
            out=self.root/mutation
            with self.assertRaises(HostError):collection.collect(self.capture,config,self.trust,self.key,out)
            self.assertFalse((out/'evidence').exists())
            self.assertEqual('failed',loads(read(out/'state.json'))['status'])
            with patch('authoring_host.inventory_collection.invoke') as invoke,self.assertRaises(FileExistsError):
                collection.collect(self.capture,config,self.trust,self.key,out)
            invoke.assert_not_called()

    def test_expiry_during_collection_never_publishes_a_fresh_receipt(self):
        actual=collection.invoke; future=provenance.now()+timedelta(seconds=901)
        clock=patch('authoring_host.inventory_provenance.now',return_value=future)
        def expires(*args):
            result=actual(*args);clock.start();return result
        try:
            with patch('authoring_host.inventory_collection.invoke',expires),self.assertRaises(HostError):
                collection.collect(self.capture,self.collector,self.trust,self.key,self.root/'expired')
        finally:clock.stop()
        self.assertFalse((self.root/'expired/evidence').exists())

    def test_returned_credentials_never_reach_published_or_retained_response(self):
        secret='inventory-test-token-sensitive'
        with sqlite3.connect(self.db) as connection:connection.execute('UPDATE documents SET content=?',(secret,))
        config=copy.deepcopy(self.collector);config['credentialEnv']=['INVENTORY_API_TOKEN']
        with patch.dict(os.environ,{'INVENTORY_API_TOKEN':secret}),self.assertRaises(HostError):
            collection.collect(self.capture,config,self.trust,self.key,self.root/'secret')
        for file in (self.root/'secret').rglob('*'):
            if file.is_file():self.assertNotIn(secret.encode(),file.read_bytes())

    def test_private_key_and_matching_independent_grant_are_required_before_collection(self):
        self.key.chmod(0o644)
        with patch('authoring_host.inventory_collection.invoke') as invoke,self.assertRaises(HostError):
            collection.collect(self.capture,self.collector,self.trust,self.key,self.root/'bad-key')
        invoke.assert_not_called();self.assertFalse((self.root/'bad-key').exists())

    def test_collector_cannot_publish_traversal_paths_or_duplicate_files(self):
        for name, rows in (('traversal',[('../escape','bad')]),('duplicate',[('same.json','one'),('same.json','two')])):
            with sqlite3.connect(self.db) as connection:
                connection.execute('DELETE FROM documents')
                connection.executemany('INSERT INTO documents VALUES (?,?)',rows)
            with self.assertRaises(HostError):collection.collect(self.capture,self.collector,self.trust,self.key,self.root/name)
            self.assertFalse((self.root/name/'evidence').exists())
        self.assertFalse((self.root/'escape').exists())

    def test_clock_rollback_cannot_extend_an_existing_discovery_session(self):
        ledger=discovery.initial(self.config)
        with patch('authoring_host.inventory_provenance.now',return_value=provenance.now()-timedelta(seconds=120)):
            with self.assertRaises(investigation.SourceChanged):discovery.verify(self.config,{},ledger)

    def test_public_cli_prepares_a_fresh_challenge_and_collects_a_verifiable_export(self):
        for name,value in (('request',self.request),('collector',self.collector),('trust',self.trust)):
            (self.root/(name+'.json')).write_bytes(canonical(value))
        cli=str(Path(__file__).resolve().parents[1]/'managed_inventory.py')
        subprocess.run([sys.executable,cli,'prepare','--request',str(self.root/'request.json'),
            '--system',self.trust['system'],'--scope',self.trust['scope'],'--out',str(self.root/'challenge.json')],check=True,capture_output=True)
        result=subprocess.run([sys.executable,cli,'collect','--capture-request',str(self.root/'challenge.json'),
            '--collector-config',str(self.root/'collector.json'),'--trust',str(self.root/'trust.json'),
            '--private-key',str(self.key),'--out',str(self.root/'cli-collection')],check=True,capture_output=True)
        value=loads(result.stdout)
        self.assertNotEqual(self.capture['challenge'],value['inventory']['captureRequest']['challenge'])
        provenance.verify_envelope(Path(value['inventory']['attestationFile']).read_bytes(),value['inventory'])


class InventorySessionTests(unittest.TestCase):
    run_host=host_tests.AuthoringHostTests.run_host

    def setUp(self):
        host_tests.AuthoringHostTests.setUp(self)
        key,trust,_,collector,capture=source(self.root,self.request)
        collected=collection.collect(capture,collector,trust,key,self.root/'collection')
        self.config['investigation']=discovery_config(collected['inventoryRoot'])
        self.config['investigation']['roots'][0]['inventory']=collected['inventory']
        self.requirements['formatVersion']=2
        self.config['budget'].update(maxCalls=8,maxTotalTokens=865536,maxCostMicrousd=2_000_000)
        selector=dict(kind='read',root='workspace',path='state.json')
        self.ref='observation:'+digest(canonical(selector))+':'+digest(b'{"owner":"team"}')

    def run_actions(self,values):return self.run_host([dict(response=v) for v in values])

    def proposal(self,**kwargs):
        return action(propose=True,facts=[dict(id='owner',observationId=self.ref,path=['owner'])],**kwargs)

    def test_acceptance_retains_final_provenance_receipt_without_exposing_manifest_to_provider(self):
        state=self.run_actions([action(('read','state.json')),self.proposal()])
        self.assertEqual('accepted',state['status']);self.assertEqual('bounded-authoring-host/4',state['hostVersion'])
        receipt=loads(read(self.out/state['attempts'][-1]['discoveryBindingResult']))
        provenance_receipt=receipt['inventoryValidation']['roots'][0]
        self.assertEqual(receipt['bindings'][0]['inventoryProvenance']['attestationSha256'],provenance_receipt['attestationSha256'])
        self.assertNotIn('inventoryAttestations',loads(read(self.out/'attempt-001/provider-request.json'))['input']['investigation'])
        self.assertIn('inventoryAttestations',investigation.load(self.out,state));verify_inventory(self.out,state)

    def test_another_original_request_is_rejected_before_any_paid_dispatch(self):
        self.config['investigation']['roots'][0]['inventory']['captureRequest']['requestSha256']='0'*64
        with self.assertRaises(HostError):self.run_actions([action(('read','state.json'))])
        self.assertFalse(self.counter.exists());self.assertFalse(self.out.exists())

    def test_expiry_during_compilation_prevents_acceptance_and_keeps_invalidated_evidence(self):
        from authoring_host.session import invoke as actual
        clock=patch('authoring_host.inventory_provenance.now',return_value=provenance.now()+timedelta(seconds=901))
        def expires(argv,*args):
            result=actual(argv,*args)
            if 'proposal' in argv:clock.start()
            return result
        try:
            with patch('authoring_host.session.invoke',expires):state=self.run_actions([action(('read','state.json')),self.proposal()])
        finally:clock.stop()
        self.assertEqual('source-changed',state['status']);self.assertEqual(0,state['attempts'][-1]['review']['exitCode'])
        self.assertNotIn('discoveryBindingResult',state['attempts'][-1])
        self.assertEqual('invalidated',investigation.load(self.out,state)['facts'][0]['validity'])

    def test_expired_clarification_cannot_resume_or_refresh_the_capture(self):
        question=dict(id='choice',question='Approve scope?',category='decision',blocking=True,status='open',evidenceRefs=[])
        state=self.run_actions([action(('read','state.json')),self.proposal(unknowns=[question]),self.proposal()])
        answers=canonical(dict(runId=state['runId'],reviewerId='test-reviewer',answers=[dict(id='investigation:choice',answer='Approved')]))
        with patch('authoring_host.inventory_provenance.now',return_value=provenance.now()+timedelta(seconds=901)):
            result=resume(self.out,answers,canonical(self.requirements))
        self.assertEqual('source-changed',result['status']);self.assertEqual('2',self.counter.read_text())

    def test_expired_capture_is_rejected_before_first_provider_dispatch(self):
        with patch('authoring_host.inventory_provenance.now',return_value=provenance.now()+timedelta(seconds=901)):
            with self.assertRaises(HostError):self.run_actions([action(('read','state.json'))])
        self.assertFalse(self.counter.exists());self.assertFalse(self.out.exists())


if __name__=='__main__':unittest.main()
