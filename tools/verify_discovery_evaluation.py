#!/usr/bin/env python3
"""Public suite through the installed compiler with explicitly scripted transcripts.

The transcripts below are test-only answers. They never enter the suite loader or
candidate configuration and do not demonstrate model reasoning or generalization.
"""
import argparse
import copy
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from authoring_host import discovery_evaluation as evaluation
from authoring_host.contracts import canonical, digest, loads, read, require


def transcript(root, suite, case, *, stale_plan=False):
    domain = case['domain']
    inspect = case['id'].endswith('-inspect') and not stale_plan
    source = {'infrastructure': 'infrastructure-readiness', 'data': 'isolated-data-recovery',
              'credentials': 'credential-maintenance'}[domain]
    response = loads(read(root/'conformance/failure-regions'/f'{source}.proposal.json'))['response']
    request = loads(read(suite/case['request']))
    if domain == 'infrastructure':
        filename = 'allocation.json'
        record, where, prefix = ['environments', 'laboratory', 'reservations', '*'], [dict(path=['role'], equals='test-worker')], ['environments', 'laboratory', 'reservations', 0]
        path = ['identity', 'resource']
        targets = [('verify', path, 'VERIFY', 'resource')] if inspect else [('create', path, 'PROVISION', 'resource'), ('verify', path, 'VERIFY', 'resource')]
        replacements = {'test-worker': 'resource://lab/test-worker-17'}
    elif domain == 'data':
        filename = 'catalog.json'
        record, where, prefix = ['datasets', '*'], [dict(path=['labels', 'role'], equals='isolated')], ['datasets', 0]
        path, vault = ['connection', 'uri'], ['recovery', 'store']
        targets = [('verify', path, 'VERIFY', 'resource')] if inspect else [
            ('backup', path, 'BACKUP', 'subject'), ('destination', vault, 'BACKUP', 'destination'),
            ('migrate', path, 'DATABASE_MIGRATE', 'database'), ('restore', path, 'RESTORE', 'subject'), ('verify', path, 'VERIFY', 'resource')]
        replacements = {'isolated-orders': 'database://lab/orders-isolated', 'recovery-vault': 'vault://lab/orders-baseline'}
    else:
        filename = 'registry.json'
        record, where, prefix = ['*'], [dict(path=['role'], equals='service-token')], [0]
        path, consumers = ['metadata', 'reference'], ['metadata', 'consumerReference']
        targets = [('verify', consumers, 'VERIFY', 'resource')] if inspect else [
            ('rotate', path, 'SECRET_ROTATE', 'subject'), ('verify', consumers, 'VERIFY', 'resource')]
        replacements = {'service-token': 'credential://lab/orders-service', 'service-consumers': 'consumer-set://lab/orders-api'}
        if inspect: path = consumers
    def replace(node):
        if isinstance(node, dict): return {k: replace(v) for k, v in node.items()}
        if isinstance(node, list): return [replace(v) for v in node]
        return replacements.get(node, node) if isinstance(node, str) else node
    response = replace(response)
    document = loads(read(suite/case['roots'][0]['directory']/filename))
    if inspect:
        value = document
        for key in prefix + path: value = value[key]
        response['normalizedIntent'] = dict(intentVersion='3.1', kind='FlowIntentDocument', name='inspect-only',
            workflows=[dict(name='main', kind='CUSTOM', body=[dict(kind='operation', id='inspect', capability='VERIFY',
                                                                  params=dict(resource=value, criteria='ready'))])])
    selector = dict(kind='read', root='workspace', path=filename)
    observation = 'observation:'+digest(canonical(selector))+':'+digest((suite/case['roots'][0]['directory']/filename).read_bytes())
    facts, bindings = [], []
    for identifier, value_path, capability, parameter in targets:
        facts.append(dict(id=identifier, observationId=observation, path=prefix+value_path))
        bindings.append(dict(meaning='Observed identity for '+capability+'.'+parameter,
            rationale='The delivered record identifies the selected laboratory subject.', factId='fact:'+identifier,
            requirement=dict(id=identifier, sourceQuote=request['userText'],
                source=dict(root='workspace', recordPath=record, where=where, valuePath=value_path),
                target=dict(selector=dict(capability=capability), parameter=parameter))))
    reads = [selector]
    if not inspect:
        # Different read sets are a protocol assertion, never a model-quality score.
        reads += [dict(kind='read',root='workspace',path=p.name) for p in sorted((suite/case['roots'][0]['directory']).glob('*.json')) if p.name != filename]
    return [dict(response=a) for a in [
        dict(kind='investigate', observe=[dict(kind='list',root='workspace',path='.')], extractFacts=[], findings=[], unknowns=[]),
        dict(kind='investigate', observe=reads, extractFacts=[], findings=[], unknowns=[]),
        dict(kind='investigate', observe=[], extractFacts=facts, findings=[], unknowns=[]),
        dict(kind='propose', response=response, interpretation=dict(summary='Scripted test interpretation.',bindings=bindings),
             extractFacts=[], findings=[], unknowns=[])]]


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    commit = subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip()
    with tempfile.TemporaryDirectory(prefix='flow-discovery-evaluation-') as temporary:
        work = Path(temporary); suite = work/'suite'
        shutil.copytree(root/'conformance/discovery-evaluation', suite)
        manifest = loads(read(suite/'suite.json'))
        cases = manifest['cases']; actions = []
        for case in cases: actions.extend(transcript(root, suite, case))
        for case in list(cases):
            if case['id'].endswith('-inspect'):
                actions.extend(transcript(root, suite, case, stale_plan=True))
                stale = copy.deepcopy(case); stale['id'] += '-stale-plan'; cases.append(stale)
        (suite/'suite.json').write_bytes(canonical(manifest))
        source, counter = work/'transcripts.json', work/'counter'
        source.write_bytes(canonical(actions))
        config = dict(formatVersion=1,
            provider=dict(id='test-provider', model='transcript-only', version='evaluation-transcript/1', evidenceKind='deterministic-test',
                          argv=[sys.executable,str(root/'tools/tests/fixtures/authoring_peer.py'),'provider',str(source),str(counter)],credentialEnv=[]),
            compiler=dict(argv=[str(cli)],commit=commit,contracts=str(root)),
            budget=dict(maxCalls=8,maxRepairs=0,maxInputTokensPerCall=160000,maxOutputTokensPerCall=8192,
                        maxTotalTokens=1345536,maxCostMicrousd=2_000_000,inputMicrousdPerMillionTokens=1000000,
                        outputMicrousdPerMillionTokens=4000000,timeoutSeconds=120))
        out = work/'evaluation'
        captured = evaluation.capture(config, suite/'suite.json', out, repetitions=1, max_cost_microusd=18_000_000)
        require(captured['completedRuns']==9 and captured['compilerAccepted']==0, 'Capture must stop before separate review')
        reviews = dict(formatVersion=1,evaluationId=captured['evaluationId'],reviewerId='scripted-test-reviewer',runs=[])
        for row in captured['runs']:
            require(row['status']=='awaiting-discovery-review', 'Transcript did not reach review: '+str(row))
            first = loads(read(out/'runs'/row['id']/'attempt-000/provider-request.json'))
            require('public-development-suite-author' not in canonical(first).decode() and
                    'recordPath' not in canonical(first['input']).decode(), 'Private expectations leaked')
            stale = row['case'].endswith('-stale-plan')
            reviews['runs'].append(dict(run=row['id'],stateSha256=row['stateSha256'],
                assessment=dict(goal='failed' if stale else 'fulfilled',constraints='violated' if stale else 'respected',
                                discovery='sufficient',rationale='Scripted compiler-boundary test, not an independent model evaluation.'),
                decision=dict(formatVersion=1,runId=row['runId'],packetSha256=row['reviewPacketSha256'],reviewerId=reviews['reviewerId'],
                              decision='accept',rationale='Explicit test approval exercises compiler rejection of the stale plan.')))
        final = evaluation.review(out, canonical(reviews))
        for row in final['runs']:
            expected = 'fidelity-violation' if row['case'].endswith('-stale-plan') else 'accepted'
            require(row['status']==expected, 'Unexpected installed evaluation outcome: '+str(row))
        require(final['compilerAccepted']==6 and final['callerAssessedSuccesses']==6 and final['expectedRuns']==9,
                'Evaluation lost independent outcomes or denominator')
        require(all(c['distinctReadSets']==2 and c['distinctAcceptedGraphs']==2 for c in final['taskComparisons']),
                'Changed-goal observations or canonical graphs were lost')
        require(counter.read_text()=='36' and final['reportedLiveCalls']==0 and not final['milestoneComplete'],
                'Review dispatched provider calls or overstated evidence')
        require(evaluation.summarize(out)==final, 'Report is not reproducible from captured evidence')
        print(json.dumps(dict(status='PASS',evidenceKind='manual-transcript-installed-compiler-test',suiteRuns=9,
                              domains=3,accepted=6,stalePlansRejected=3,liveModelEvaluated=False,milestoneComplete=False)))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument('--cli',required=True)
    args=parser.parse_args(); verify(args.root,args.cli)
