#!/usr/bin/env python3
"""Signed inventory collection and installed-compiler proof; no live model or cloud."""
import argparse
import base64
import copy
from datetime import timedelta
import json
from pathlib import Path
import secrets
import sqlite3
import subprocess
import sys
import tempfile
from unittest.mock import patch

from authoring_host import investigation, inventory_provenance
from authoring_host.contracts import HostError, canonical, digest, loads, read, require, verify_inventory
from authoring_host.inventory_collection import collect, prepare
from authoring_host.session import start, invoke
from verify_system_discovery import fixtures


def verify(root, cli):
    root,cli=Path(root).resolve(),Path(cli).resolve()
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    rows=[]
    with tempfile.TemporaryDirectory(prefix='flow-managed-inventory-') as temporary:
        work=Path(temporary)
        key=work/'collector.pem'
        subprocess.run(['openssl','genpkey','-algorithm','Ed25519','-out',str(key)],check=True,capture_output=True)
        key.chmod(0o600)
        public=subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout','-outform','DER'])
        trust=dict(issuerId='isolated-inventory-collector',keyId='collection-key',publicKey=base64.b64encode(public).decode(),
                   system='local-managed-source',scope='isolated-development')
        for domain in ('infrastructure','data','credential'):
            for variant in ('matching','wrong-scope','tampered-export','expired-during-compilation'):
                case=work/(domain+'-'+variant);case.mkdir()
                subject=domain+'-'+secrets.token_hex(4)
                addresses=['resource://'+secrets.token_hex(8),'resource://'+secrets.token_hex(8)]
                documents,bindings=fixtures(domain,subject,addresses)
                filenames=[secrets.token_hex(4)+'/'+secrets.token_hex(4)+'.json' for _ in documents]
                request=dict(userText='Discover '+subject+' from the authorized managed inventory and enforce declared observation bindings. Do not build.',mode='DRAFT')
                requirements=dict(formatVersion=2,request=request,origin='REVIEWER_AUTHORED',confirmation='CONFIRMED',reviewerId='independent-reviewer',
                    requirements=[dict(id='no-build',sourceQuote='Do not build',check=dict(kind='forbid-capability',capability='BUILD'))])
                database=case/'source.sqlite'
                with sqlite3.connect(database) as connection:
                    connection.execute('CREATE TABLE metadata(system, scope, revision)')
                    connection.execute('INSERT INTO metadata VALUES (?,?,?)',(trust['system'],trust['scope'],'source-revision-'+secrets.token_hex(4)))
                    connection.execute('CREATE TABLE documents(path, content)')
                    connection.executemany('INSERT INTO documents VALUES (?,?)',[(name,canonical(document).decode()) for name,document in zip(filenames,documents)])
                source_before=digest(database.read_bytes())
                collector=dict(formatVersion=1,argv=[sys.executable,str(root/'tools/tests/fixtures/inventory_collector.py'),'--database',str(database)],
                               credentialEnv=[],timeoutSeconds=15)
                capture=prepare(request,trust['system'],trust['scope'])
                collected=collect(capture,collector,trust,key,case/'collection')
                policy=copy.deepcopy(collected['inventory'])
                workspace=Path(collected['inventoryRoot'])
                require(digest(database.read_bytes())==source_before,'Read-only inventory collection changed its source')
                require(all(address.encode() not in canonical([request,requirements,bindings,policy]) for address in addresses),'An expected address leaked into caller policy')
                config=dict(formatVersion=1,provider=dict(id='discovery-test',model='fixture-only',version='discovery-peer/2',evidenceKind='deterministic-test',
                    argv=[sys.executable,str(root/'tools/tests/fixtures/discovery_peer.py')],credentialEnv=[]),
                    compiler=dict(argv=[str(cli)],commit=commit,contracts=str(root)),
                    budget=dict(maxCalls=16,maxRepairs=0,maxInputTokensPerCall=160000,maxOutputTokensPerCall=8192,maxTotalTokens=2691072,
                        maxCostMicrousd=4000000,inputMicrousdPerMillionTokens=1000000,outputMicrousdPerMillionTokens=4000000,timeoutSeconds=120),
                    investigation=dict(formatVersion=2,roots=[dict(id='workspace',path=str(workspace),scope='Authorized inventory export',inventory=policy)],
                        maxSteps=15,maxSourceBytes=8192,maxTotalSourceBytes=32768,maxRecords=96,maxEntries=32,bindingRequirements=bindings))
                if variant=='wrong-scope':
                    policy['trust']['scope']='another-environment';policy['captureRequest']['scope']='another-environment'
                    try:start(config,canonical(request),canonical(requirements),case/'run')
                    except HostError:pass
                    else:raise AssertionError('Cross-scope inventory reached authoring')
                    require(not (case/'run').exists(),'Rejected inventory created an authoring session')
                    rows.append(dict(domain=domain,variant=variant,status='admission-blocked',providerCalls=0));continue
                clock=[None]; real_now=inventory_provenance.now
                def intercepted(argv,data,timeout,environment):
                    result=invoke(argv,data,timeout,environment)
                    if variant=='expired-during-compilation' and 'proposal' in argv:
                        clock[0]=real_now()+timedelta(seconds=3601)
                    if variant=='tampered-export' and data:
                        payload=loads(data)
                        if payload.get('schema')=='command-reasoning/v2' and payload['input']['investigation']['facts']:
                            (workspace/filenames[0]).write_text('{}')
                    return result
                with patch('authoring_host.session.invoke',intercepted),patch('authoring_host.inventory_provenance.now',side_effect=lambda:clock[0] or real_now()):
                    state=start(config,canonical(request),canonical(requirements),case/'run')
                expected='accepted' if variant=='matching' else 'source-changed'
                require(state['status']==expected,'Unexpected managed inventory result: '+domain+'/'+variant+' '+state['status'])
                verify_inventory(case/'run',state)
                ledger=investigation.load(case/'run',state)
                require('inventoryAttestations' in ledger,'Signed inventory receipt was not retained')
                last=state['attempts'][-1]
                if variant=='matching':
                    receipt=loads(read(case/'run'/last['discoveryBindingResult']))
                    require(receipt['inventoryValidation']['roots'][0]['attestationSha256']==ledger['roots'][0]['provenance']['attestationSha256'],
                            'Final compiler result lost inventory provenance')
                    require(set(receipt['compilerAssessment'].values())=={'SATISFIED'},'Observed binding did not pass canonical compiler checks')
                    resolved=loads(read(case/'run'/last['compilerRequirementsFile']))
                    require([r['check']['expected'] for r in resolved['requirements'][-2:]]==addresses,'Compiler did not use the collected values')
                else:
                    require('discoveryBindingResult' not in last and all(f['validity']=='invalidated' for f in ledger['facts']),
                            'Invalid inventory retained an accepted binding')
                    if variant=='expired-during-compilation':
                        require(last['review']['exitCode']==0,'Expiry case never reached a successful actual compilation')
                for attempt in state['attempts']:
                    payload=loads(read(case/'run'/attempt['directory']/'provider-request.json'))
                    require('inventoryAttestations' not in payload['input']['investigation'] and str(workspace) not in json.dumps(payload),
                            'Raw inventory manifest or local path leaked into provider context')
                require(digest(database.read_bytes())==source_before,'Authoring changed the managed source')
                rows.append(dict(domain=domain,variant=variant,status=state['status'],providerCalls=len(state['attempts']),
                                 facts=len(ledger['facts']),compilerInvoked='review' in last))
    result=dict(status='PASS',evidenceKind='managed-inventory-process-integration-test',cases=rows,sourceTransport='read-only-sqlite-process',
                liveModelEvaluated=False,cloudWorkExecuted=False,workflowWorkExecuted=False,upstreamFreshnessEstablished=False)
    print(json.dumps(result));return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument('--cli',required=True);args=parser.parse_args();verify(args.root,args.cli)
