#!/usr/bin/env python3
"""Reviewed discovery transcripts through the installed compiler, not AI evaluation.

The replay provider receives manually authored actions. This proves the product
boundary without claiming those actions were inferred by a model. No expected
mapping, source filename or resource address is supplied in host caller inputs.
"""
import argparse
import copy
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, digest, loads, read, require, verify_inventory
from authoring_host.session import review_discovery, start


def scenario(root, domain, layout, address, vault):
    case = 'infrastructure-readiness' if domain == 'infrastructure' else 'isolated-data-recovery'
    corpus = root / 'conformance/failure-regions'
    proposal = loads(read(corpus / (case + '.proposal.json')))
    requirements = loads(read(corpus / (case + '.requirements.json')))
    replacements = {'test-worker': address} if domain == 'infrastructure' else {'isolated-orders': address, 'recovery-vault': vault}
    def replace(node):
        if isinstance(node, dict): return {k: replace(v) for k, v in node.items()}
        if isinstance(node, list): return [replace(v) for v in node]
        return replacements.get(node, node) if isinstance(node, str) else node
    response = replace(proposal['response'])
    # Independent control/effect constraints contain no expected discovered values.
    def unbind(node):
        if isinstance(node, dict):
            return {k: ({p: v for p, v in value.items() if v not in replacements} if k == 'parameters' else unbind(value))
                    for k, value in node.items()}
        if isinstance(node, list): return [unbind(v) for v in node]
        return node
    requirements = unbind(requirements)
    if domain == 'infrastructure':
        if layout == 0:
            document = dict(resources=[dict(role='test-worker', state=dict(address=address))])
            record, where, value_path = ['resources', '*'], [dict(path=['role'], equals='test-worker')], ['state', 'address']
        else:
            document = dict(environments=dict(dev=dict(services=dict(worker=dict(purpose='test-worker', endpoint=address)))))
            record, where, value_path = ['environments', 'dev', 'services', '*'], [dict(path=['purpose'], equals='test-worker')], ['endpoint']
        targets = [('worker-create', value_path, 'PROVISION', 'resource'), ('worker-verify', value_path, 'VERIFY', 'resource')]
    else:
        if layout == 0:
            document = dict(datasets=dict(primary=dict(role='isolated', connection=dict(uri=address), store=vault)))
            record, where, value_path, vault_path = ['datasets', '*'], [dict(path=['role'], equals='isolated')], ['connection', 'uri'], ['store']
        else:
            document = [dict(labels=dict(role='isolated'), databaseRef=address, recovery=dict(uri=vault))]
            record, where, value_path, vault_path = ['*'], [dict(path=['labels', 'role'], equals='isolated')], ['databaseRef'], ['recovery', 'uri']
        targets = [('backup-database', value_path, 'BACKUP', 'subject'), ('backup-vault', vault_path, 'BACKUP', 'destination'),
                   ('migrate-database', value_path, 'DATABASE_MIGRATE', 'database'), ('restore-database', value_path, 'RESTORE', 'subject'),
                   ('verify-database', value_path, 'VERIFY', 'resource')]
        requirements['formatVersion'] = 5
        requirements['requirements'].append(dict(id='restore-captured-baseline', sourceQuote='restore its captured baseline',
            check=dict(kind='value-from', consumer=dict(capability='RESTORE'), parameter='recoveryPoint', producer=dict(capability='BACKUP'), evaluationScope='SELECTED_OPERATIONS')))
    # Concrete extraction paths are authored into the replay, never caller policy.
    prefix = ['resources', 0] if domain == 'infrastructure' and layout == 0 else (
        ['environments', 'dev', 'services', 'worker'] if domain == 'infrastructure' else ['datasets', 'primary'] if layout == 0 else [0])
    rows, extractions = [], []
    for identifier, path, capability, parameter in targets:
        rows.append(dict(meaning='Observed identity used by '+capability+'.'+parameter,
            rationale='The delivered record identifies the requested isolated resource and its recovery relationship.',
            requirement=dict(id=identifier, sourceQuote=proposal['request']['userText'],
                source=dict(root='workspace', recordPath=record, where=where, valuePath=path),
                target=dict(selector=dict(capability=capability), parameter=parameter)), factId='fact:'+identifier))
        extractions.append(dict(id=identifier, path=prefix+path))
    return proposal['request'], requirements, document, response, rows, extractions


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    results = []
    with tempfile.TemporaryDirectory(prefix='flow-reviewed-discovery-') as temporary:
        work = Path(temporary)
        for domain in ('infrastructure', 'data'):
            for layout in (0, 1):
                for variant in ('matching', 'wrong-value', 'flattened-handler', 'changed-goal'):
                    case = work / f'{domain}-{layout}-{variant}'; case.mkdir()
                    workspace = case/'workspace'; workspace.mkdir()
                    address, vault = 'resource://'+secrets.token_hex(6), 'vault://'+secrets.token_hex(6)
                    request, requirements, document, response, bindings, facts = scenario(root, domain, layout, address, vault)
                    if variant == 'wrong-value':
                        body = response['normalizedIntent']['workflows'][0]['body']
                        if domain == 'infrastructure': body[1]['body'][0]['params']['resource'] = 'resource://unrelated'
                        else: body[0]['params']['subject'] = 'resource://unrelated'
                    elif variant == 'flattened-handler':
                        body = response['normalizedIntent']['workflows'][0]['body']
                        index = 1 if domain == 'infrastructure' else 2
                        region = body[index]; body[index:index+1] = region['body']+region['onFailure']
                    elif variant == 'changed-goal':
                        capability = 'PROVISION' if domain == 'infrastructure' else 'DATABASE_MIGRATE'
                        request['userText'] = 'Inspect the selected environment only. Do not provision or migrate resources.'
                        requirements['request'] = copy.deepcopy(request)
                        requirements['requirements'] = [dict(id='no-mutation', sourceQuote=request['userText'],
                            check=dict(kind='forbid-capability', capability=capability))]
                        for row in bindings: row['requirement']['sourceQuote'] = request['userText']
                        # Deliberately replay the old plan: independent constraints must reject it.
                    file = secrets.token_hex(6)+'.json'; (workspace/file).write_bytes(canonical(document))
                    selector = dict(kind='read', root='workspace', path=file)
                    observation = 'observation:'+digest(canonical(selector))+':'+digest(canonical(document))
                    actions = [dict(kind='investigate', observe=[dict(kind='list',root='workspace',path='.')], extractFacts=[], findings=[], unknowns=[]),
                               dict(kind='investigate', observe=[selector], extractFacts=[], findings=[], unknowns=[]),
                               dict(kind='investigate', observe=[], extractFacts=[dict(f, observationId=observation) for f in facts], findings=[], unknowns=[]),
                               dict(kind='propose', response=response, extractFacts=[], findings=[], unknowns=[],
                                    interpretation=dict(summary='Proposed resource interpretation and structured lifecycle plan.', bindings=bindings))]
                    source, counter = case/'responses.json', case/'counter'
                    source.write_bytes(canonical([dict(response=a) for a in actions]))
                    config = dict(formatVersion=1,
                        provider=dict(id='test-provider', model='transcript-only', version='review-transcript/1', evidenceKind='deterministic-test',
                                      argv=[sys.executable,str(root/'tools/tests/fixtures/authoring_peer.py'),'provider',str(source),str(counter)],credentialEnv=[]),
                        compiler=dict(argv=[str(cli)],commit=commit,contracts=str(root)),
                        budget=dict(maxCalls=8,maxRepairs=0,maxInputTokensPerCall=160000,maxOutputTokensPerCall=8192,
                                    maxTotalTokens=1345536,maxCostMicrousd=2_000_000,inputMicrousdPerMillionTokens=1000000,
                                    outputMicrousdPerMillionTokens=4000000,timeoutSeconds=120),
                        investigation=dict(formatVersion=3,roots=[dict(id='workspace',path=str(workspace),scope='Isolated environment inventory')],
                                           maxSteps=8,maxSourceBytes=8192,maxTotalSourceBytes=32768,maxRecords=96,maxEntries=32))
                    require(all(v.encode() not in canonical([request,requirements,config['investigation']]) for v in (file,address,vault)),
                            'Expected source or value leaked into caller inputs')
                    out = case/'run'
                    state = start(config,canonical(request),canonical(requirements),out)
                    require(state['status']=='awaiting-discovery-review', 'Interpretation did not reach review: '+state['status'])
                    require(not any('review' in a for a in state['attempts']), 'Unreviewed interpretation reached compiler')
                    decision = dict(formatVersion=1,runId=state['runId'],packetSha256=state['attempts'][-1]['discoveryReviewPacketSha256'],
                                    reviewerId='independent-transcript-reviewer',decision='accept',rationale='Protocol test approval; compiler still owns constraints.')
                    final = review_discovery(out,canonical(decision))
                    expected = 'accepted' if variant == 'matching' else 'fidelity-violation'
                    require(final['status']==expected, f'Unexpected reviewed outcome {domain}/{layout}/{variant}: {final["status"]}: {final["attempts"][-1]}')
                    require(counter.read_text()=='4' and final['repairCount']==0, 'Review caused an unapproved replacement')
                    require(loads(read(out/'requirements-0.json'))==requirements, 'Independent requirements changed')
                    derived = loads(read(out/final['attempts'][-1]['compilerRequirementsFile']))
                    require(derived['formatVersion']==5 and derived['requirements'][:len(requirements['requirements'])]==requirements['requirements'],
                            'V5 translation changed an independent obligation')
                    require(all(r['check']['evaluationScope']=='SELECTED_OPERATIONS' for r in derived['requirements'][len(requirements['requirements']):]),
                            'Observed-value checks claimed unconditional execution')
                    verify_inventory(out,final)
                    results.append(dict(domain=domain,layout=layout,variant=variant,status=final['status']))
    print(json.dumps(dict(status='PASS',evidenceKind='manual-transcript-installed-compiler-test',cases=results,
                          callerBindingMapRequired=False,liveModelEvaluated=False,unfamiliarSystemGeneralizationEstablished=False,
                          workflowWorkExecuted=False,targetSupportPromoted=False)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument('--cli',required=True)
    args = parser.parse_args(); verify(args.root,args.cli)
