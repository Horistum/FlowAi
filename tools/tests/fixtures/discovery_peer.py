"""Deterministic discovery agent for integration tests, not a live-model evaluation.

It knows a small JSON inventory shape, but receives no filenames, expected address,
requirements, proposal template or filesystem access. Every choice uses the request
and observations returned by the production host.
"""
import json
import sys


def choose(payload):
    request=payload['input']['request']
    ledger=payload['input']['investigation']
    subject=request['context']['knownSystems']['discovery.subject']
    observations=ledger['observations']
    selected={(o['selector']['root'],o['selector']['path']):o for o in observations if o['selector']['kind']=='read'}
    listings={(o['selector']['root'],o['selector']['path']):o for o in observations if o['selector']['kind']=='list'}
    unknown=dict(id='target',question='Which resource and dependencies match the requested scope?',category='fact',blocking=True,status='open',evidenceRefs=[])

    def investigate(kind,root,path):
        return dict(kind='investigate',observe=[dict(kind=kind,root=root,path=path)],extractFacts=[],findings=[],unknowns=[unknown])

    def document(observation):
        try:return json.loads(observation['text'])
        except (ValueError,KeyError):return None

    matches=[]
    for (root,path),observation in selected.items():
        value=document(observation)
        if isinstance(value,dict):
            for resource in value.get('resources',[]):
                if isinstance(resource,dict) and resource.get('name')==subject:
                    matches.append((root,resource))
    if len(matches)==1:
        root,resource=matches[0]
        path=resource['details']
        if (root,path) not in selected:return investigate('read',root,path)
        detail=selected[root,path];value=document(detail)
        if isinstance(value,dict) and value.get('name')==subject:
            dependencies=value.get('dependencies',[])
            for dependency in dependencies:
                if (root,dependency) not in selected:return investigate('read',root,dependency)
            selections=[dict(id='address',observationId=detail['id'],path=['address'])]
            for i,dependency in enumerate(dependencies):
                selections.append(dict(id='dependency-'+str(i),observationId=selected[root,dependency]['id'],path=['state','ready']))
            facts={f['id']:f for f in ledger['facts']}
            missing=[f for f in selections if 'fact:'+f['id'] not in facts]
            if missing:return dict(kind='investigate',observe=[],extractFacts=missing,findings=[],unknowns=[unknown])
            refs=['fact:'+s['id'] for s in selections]
            ready=all(facts[r]['value'] is True for r in refs[1:])
            unknown.update(status='addressed' if ready else 'open',evidenceRefs=refs)
            address=facts['fact:address']['value']
            return proposal(address,unknown,[dict(id='selection',statement='The selected address and prerequisite state come from retained source values.',evidenceRefs=refs)])
    # Discover names from directory observations; never assume an answer file or path.
    for root in ledger['roots']:
        if (root['id'],'.') not in listings:return investigate('list',root['id'],'.')
    for (root,path),listing in listings.items():
        for entry in listing['entries']:
            child=entry['name'] if path=='.' else path+'/'+entry['name']
            if entry['kind']=='file' and (root,child) not in selected:return investigate('read',root,child)
    for (root,path),listing in listings.items():
        for entry in listing['entries']:
            child=entry['name'] if path=='.' else path+'/'+entry['name']
            if entry['kind']=='directory' and (root,child) not in listings:return investigate('list',root,child)
    return proposal('unresolved',unknown,[])


def proposal(address,unknown,findings):
    response=dict(normalizedIntent=dict(intentVersion='3.1',kind='FlowIntentDocument',name='discovered-system',
        workflows=[dict(name='main',kind='CUSTOM',body=[dict(kind='operation',id='verify',capability='VERIFY',
            params=dict(resource=address,criteria='Inspect the selected resource without changing it.'))])]),
        report=dict(mode='DRAFT',classification=dict(type='system-discovery',confidence=0),
            confidence=dict(overall=0,intentType=0,entities=0,dependencies=0,safety=0),
            explanation=['Deterministic discovery integration fixture; no live AI or runtime work claimed.'],guardrails=[]))
    return dict(kind='propose',response=response,extractFacts=[],findings=findings,unknowns=[unknown])


if __name__=='__main__':
    payload=json.load(sys.stdin)
    print(json.dumps(dict(schema='command-reasoning/v2',result=choose(payload),usage=dict(input_tokens=100,output_tokens=100),
        provider=dict(id='discovery-test',model=payload['model'],request_id=payload['effect_id']))))
