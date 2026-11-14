#!/usr/bin/env python3
"""Cross-domain observed decisions through the installed compiler; manually authored transcripts.

These tests falsify candidate plans against selected obligations. They do not test a
model's ability to discover the supplied interpretation or execute infrastructure.
"""
import argparse
import copy
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

from authoring_host import discovery_bindings
from authoring_host.contracts import canonical, digest, loads, read, require, verify_inventory
from authoring_host.session import start, review_discovery


def replace(value, substitutions):
    """Randomize resource addresses without altering capability or control contracts."""
    if isinstance(value, dict): return {k:replace(v,substitutions) for k,v in value.items()}
    if isinstance(value, list): return [replace(v,substitutions) for v in value]
    return substitutions.get(value,value) if isinstance(value,str) else value


def operations(body):
    """Test-only traversal to inject a precise fault; production uses only the compiler."""
    for node in body:
        if node['kind']=='operation': yield node
        for key in ('body','onFailure'): yield from operations(node.get(key,[]))


def inputs(root, domain, selected, layout, variant):
    corpus=root/'conformance/observed-decisions'
    request=loads(read(corpus/f'{domain}.request.json'))
    requirements=loads(read(corpus/f'{domain}.requirements.json'))
    decision=loads(read(corpus/f'{domain}.decision.json'))
    document=loads(read(corpus/f'{domain}-{selected}.source.json'))
    alternatives=[c['id'] for c in decision['cases']]
    planned=next(c for c in alternatives if c!=selected) if variant=='stale-plan' else selected
    response=loads(read(corpus/f'{domain}-{planned}.response.json'))
    bindings=loads(read(corpus/f'{domain}.bindings.json'))[planned]
    resource,vault='resource://'+secrets.token_hex(8),'vault://'+secrets.token_hex(8)
    substitutions={s:resource for s in ('resource://requested-network','resource://shared-network','isolated-orders')}
    substitutions['recovery-vault']=vault
    response=replace(response,substitutions);document=replace(document,substitutions)
    if layout:
        document=dict(export=document)
        for row in decision['inputs']:row['source']['recordPath'].insert(0,'export')
        for row in bindings:row['requirement']['source']['recordPath'].insert(0,'export')
    body=response['normalizedIntent']['workflows'][0]['body']
    if variant=='wrong-resource':
        target=bindings[0]['requirement']['target']
        for node in operations(body):
            if node['capability']==target['selector']['capability']:node['params'][target['parameter']]='resource://unrelated'
    elif variant=='delete-shared':
        next(n for n in operations(body) if n['capability']=='DEPROVISION')['params']['target']='resource://shared'
    elif variant=='allocation-inside-recovery':
        allocation=body.pop(1);body[1]['body'].insert(0,allocation)
    elif variant=='flatten-handler':
        region=body.pop();body.extend(region['body']+region['onFailure'])
    elif variant=='unrelated-recovery-point':
        next(n for n in operations(body) if n['capability']=='RESTORE')['params']['recoveryPoint']='snapshot://unrelated'
    elif variant=='unsupported-state':
        item=decision['inputs'][0];record=discovery_bindings.records(document,item['source']['recordPath'])[0][0]
        record[item['source']['valuePath'][0]]='unresolved-partial-state'
    return request,requirements,document,response,bindings,decision,resource,vault


def verify(root, cli):
    root,cli=Path(root).resolve(),Path(cli).resolve()
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    cases=[]
    for domain,choices in [('infrastructure',('reuse','create')),('data',('inspect','migrate'))]:
        for selected in choices:
            cases += [(domain,selected,layout,'matching') for layout in (0,1)]
            cases += [(domain,selected,0,variant) for variant in ('stale-plan','wrong-resource','goal-mode','goal-violation')]
        cases.append((domain,choices[1],0,'unsupported-state'))
    cases += [('infrastructure','create',0,v) for v in ('delete-shared','allocation-inside-recovery','flatten-handler')]
    cases += [('data','migrate',0,'unrelated-recovery-point')]
    results=[]
    with tempfile.TemporaryDirectory(prefix='flow-observed-decisions-') as temporary:
        work=Path(temporary)
        for domain,selected,layout,variant in cases:
            label=f'{domain}-{selected}-{layout}-{variant}'
            case=work/label;case.mkdir();workspace=case/'workspace';workspace.mkdir()
            request,requirements,document,response,bindings,decision,resource,vault=inputs(root,domain,selected,layout,variant)
            goal_mode = variant in ('goal-mode', 'goal-violation')
            if variant == 'goal-violation':
                # A frozen caller prohibition, not an obligation extracted from the
                # candidate. The scripted plan deliberately contradicts this goal.
                request['userText'] += ' For this diagnostic run do not verify resources.'
                requirements['request'] = copy.deepcopy(request)
                requirements['requirements'].append(dict(id='independent-no-verification',
                    sourceQuote='do not verify resources', check=dict(kind='forbid-capability', capability='VERIFY')))
            filename=secrets.token_hex(8)+'.json';(workspace/filename).write_bytes(canonical(document))
            selector=dict(kind='read',root='workspace',path=filename)
            observation='observation:'+digest(canonical(selector))+':'+digest(canonical(document))
            extractions=[]
            for source,fact_id in [(i['source'],i['factId']) for i in decision['inputs']]+[(b['requirement']['source'],b['factId']) for b in bindings]:
                record,prefix=discovery_bindings.records(document,source['recordPath'])[0]
                extractions.append(dict(id=fact_id.removeprefix('fact:'),observationId=observation,path=prefix+source['valuePath']))
            actions=[dict(kind='investigate',observe=[dict(kind='list',root='workspace',path='.')],extractFacts=[],findings=[],unknowns=[]),
                     dict(kind='investigate',observe=[selector],extractFacts=[],findings=[],unknowns=[]),
                     dict(kind='investigate',observe=[],extractFacts=extractions,findings=[],unknowns=[]),
                     dict(kind='propose',response=response,extractFacts=[],findings=[],unknowns=[],interpretation=dict(summary='Observed lifecycle choice and complete candidate plan.',bindings=bindings,decisions=[decision]))]
            source,counter=case/'responses.json',case/'counter';source.write_bytes(canonical([dict(response=a) for a in actions]))
            config=dict(formatVersion=1,
                provider=dict(id='test-provider',model='manual-transcript',version='observed-decisions/1',evidenceKind='deterministic-test',argv=[sys.executable,str(root/'tools/tests/fixtures/authoring_peer.py'),'provider',str(source),str(counter)],credentialEnv=[]),
                compiler=dict(argv=[str(cli)],commit=commit,contracts=str(root)),
                budget=dict(maxCalls=8,maxRepairs=0,maxInputTokensPerCall=240000,maxOutputTokensPerCall=16384,maxTotalTokens=2051072,maxCostMicrousd=3_000_000,inputMicrousdPerMillionTokens=1000000,outputMicrousdPerMillionTokens=4000000,timeoutSeconds=120),
                investigation=dict(formatVersion=4,roots=[dict(id='workspace',path=str(workspace),scope='Development environment inventory')],maxSteps=8,maxSourceBytes=8192,maxTotalSourceBytes=32768,maxRecords=96,maxEntries=32))
            if goal_mode:
                config['investigation']['roots'][0]['observations'] = ['list', 'read']
                config['investigation']['task'] = dict(constraints=[request['userText']], successCriteria=[
                    dict(id='goal-outcome', description='The original goal is achieved without forbidden effects.')])
                actions[-1]['interpretation'].update(bindings=[], decisions=[], evidenceRefs=[observation])
                source.write_bytes(canonical([dict(response=a) for a in actions]))
            require(all(v.encode() not in canonical([request,requirements,config['investigation']]) for v in (filename,resource,vault)),'Fixture expectation leaked into caller inputs')
            out=case/'run';state=start(config,canonical(request),canonical(requirements),out)
            packet=loads(read(out/state['attempts'][-1]['discoveryReviewPacket']))
            require(not any('review' in a for a in state['attempts']),'Unreviewed candidate reached compiler')
            if variant=='unsupported-state':
                require(state['status']=='discovery-binding-blocked','Unsupported observation selected a plan')
            else:
                require(state['status']=='awaiting-discovery-review',f'{label}: {state["status"]}')
                if not goal_mode:
                    require(packet['bindingAssessment']['decisions'][0]['selectedCase']==selected,'Host followed candidate rather than observed state')
                approval=dict(formatVersion=1,runId=state['runId'],packetSha256=state['attempts'][-1]['discoveryReviewPacketSha256'],reviewerId='manual-test-reviewer',decision='accept',rationale='Explicit protocol-test review; canonical compiler must still reject invalid plans.')
                state=review_discovery(out,canonical(approval))
                # A crashed compiler or unrelated blocker is not a successful falsification.
                # The misplaced allocation may fail canonical scope admission before fidelity.
                expected = {'accepted'} if variant in ('matching', 'goal-mode') else (
                    {'fidelity-violation', 'discovery-candidate-rejected'} if variant == 'allocation-inside-recovery'
                    else {'fidelity-violation'})
                require(state['status'] in expected,f'{label}: unexpected {state["status"]}: {state["attempts"][-1]}')
                if variant == 'allocation-inside-recovery' and state['status'] == 'discovery-candidate-rejected':
                    require(any(d['code']=='STRUCTURED_DEPENDENCY_SCOPE' for d in state['attempts'][-1]['review']['diagnostics']),
                            'Misplaced allocation was rejected for an unrelated reason')
                if variant=='stale-plan':
                    receipt=loads(read(out/state['attempts'][-1]['discoveryBindingResult']))
                    ids=receipt['decisions'][0]['compilerRequirementIds']
                    require(any(receipt['compilerAssessment'][i]!='SATISFIED' for i in ids),'Stale alternative was not rejected by selected decision obligations')
                derived=loads(read(out/state['attempts'][-1]['compilerRequirementsFile']))
                require(derived['requirements'][:len(requirements['requirements'])]==requirements['requirements'],'Independent obligations changed')
                if goal_mode:
                    require(derived == requirements, 'Map-free goal mode added candidate-derived obligations')
                    evidence = state['goalEvidence']
                    require(evidence['goalSatisfaction'] == 'not-assessed' and evidence['runtimeOutcome'] == 'not-observed',
                            'Compiler acceptance was promoted to goal or runtime evidence')
                    require(not evidence['proposedConsistencyChecks']['checks'], 'Map-free mode invented proposed checks')
                    require(evidence['independentConstraints']['status'] == ('satisfied' if variant == 'goal-mode' else 'not-satisfied'),
                            'Independent caller constraints lost their assessment')
            require(counter.read_text()=='4' and state['repairCount']==0,'Review dispatched a replacement candidate')
            verify_inventory(out,state)
            results.append(dict(domain=domain,observedCase=selected,layout=layout,variant=variant,status=state['status']))
    print(json.dumps(dict(status='PASS',evidenceKind='manual-transcript-installed-compiler-test',cases=results,liveModelEvaluated=False,workflowWorkExecuted=False,remoteOwnershipProven=False)))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',default=str(Path(__file__).resolve().parents[1]));parser.add_argument('--cli',required=True)
    args=parser.parse_args();verify(args.root,args.cli)
