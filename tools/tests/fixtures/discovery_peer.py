"""Deterministic protocol fixture interpreting caller-declared JSON selectors.

This is not unfamiliar-format AI reasoning. It knows no domain names, source file
names or addresses and does not import the host's binding resolver. The installed
proof mutates its replies to verify enforcement against a non-cooperative provider.
"""
import json
import sys


def select(value, path):
    selected = [(value, [])]
    for key in path:
        following = []
        for node, prefix in selected:
            if key == '*':
                children = node.items() if isinstance(node, dict) else enumerate(node) if isinstance(node, list) else []
                following.extend((v, prefix+[k]) for k,v in children)
            elif isinstance(node, dict) and key in node:
                following.append((node[key], prefix+[key]))
            elif isinstance(node, list) and type(key) is int and key < len(node):
                following.append((node[key], prefix+[key]))
        selected = following
    return selected


def make_proposal(ledger, values, bindings):
    workflows, operations = {}, {}
    for requirement in ledger['bindingRequirements']:
        target = requirement['target']; selector = target['selector']
        key = json.dumps(selector, sort_keys=True)
        workflow = selector.get('workflow', 'main')
        if key not in operations:
            operations[key] = dict(kind='operation', id='operation-'+str(len(operations)),
                capability=selector['capability'], params=dict(selector.get('parameters', {})))
            workflows.setdefault(workflow, []).append(operations[key])
        operations[key]['params'][target['parameter']] = values.get(requirement['id'], 'unobserved')
    response = dict(normalizedIntent=dict(intentVersion='3.1', kind='FlowIntentDocument', name='observed-parameters',
        workflows=[dict(name=name, kind='CUSTOM', body=body) for name,body in workflows.items()]),
        report=dict(mode='DRAFT', classification=dict(type='binding-protocol-fixture', confidence=0),
            confidence=dict(overall=0,intentType=0,entities=0,dependencies=0,safety=0),
            explanation=['Deterministic protocol fixture; no live model or runtime execution.'], guardrails=[]))
    return dict(kind='propose', response=response, extractFacts=[], findings=[], unknowns=[], bindings=bindings)


def choose(payload):
    ledger = payload['input']['investigation']
    observations = ledger['observations']
    reads = {(o['selector']['root'],o['selector']['path']):o for o in observations if o['selector']['kind']=='read'}
    listings = {(o['selector']['root'],o['selector']['path']):o for o in observations if o['selector']['kind']=='list'}

    def observe(kind, root, path):
        return dict(kind='investigate', observe=[dict(kind=kind,root=root,path=path)], extractFacts=[], findings=[], unknowns=[])

    # Complete this small fixture workspace using host-returned names; no answer index.
    for root in ledger['roots']:
        if (root['id'],'.') not in listings:
            return observe('list',root['id'],'.')
    for (root,path), listing in listings.items():
        for entry in listing['entries']:
            child = entry['name'] if path=='.' else path+'/'+entry['name']
            if entry['kind']=='directory' and (root,child) not in listings:
                return observe('list',root,child)
            if entry['kind']=='file' and (root,child) not in reads:
                return observe('read',root,child)

    extractions, bindings, values = [], [], {}
    facts = {f['id']:f for f in ledger['facts']}
    for requirement in ledger['bindingRequirements']:
        source = requirement['source']; found = []
        for (root,_), observation in reads.items():
            if root != source['root']:continue
            try: document = json.loads(observation['text'])
            except ValueError:continue
            for record, prefix in select(document, source['recordPath']):
                if all(any(json.dumps(v,sort_keys=True)==json.dumps(p['equals'],sort_keys=True)
                           for v,_ in select(record,p['path'])) for p in source['where']):
                    for value, suffix in select(record,source['valuePath']):
                        found.append((observation,prefix+suffix,value))
        if not found:continue
        # Deliberately choose the first of ambiguous records: the HOST must reject it.
        observation, path, value = found[0]
        identity = 'selected-'+requirement['id']
        if 'fact:'+identity not in facts:
            extractions.append(dict(id=identity,observationId=observation['id'],path=path))
        else:
            values[requirement['id']] = facts['fact:'+identity]['value']
            bindings.append(dict(requirementId=requirement['id'],factId='fact:'+identity))
    if extractions:
        return dict(kind='investigate',observe=[],extractFacts=extractions,findings=[],unknowns=[])
    return make_proposal(ledger,values,bindings)


if __name__=='__main__':
    payload=json.load(sys.stdin)
    print(json.dumps(dict(schema='command-reasoning/v2',result=choose(payload),usage=dict(input_tokens=100,output_tokens=100),
        provider=dict(id='discovery-test',model=payload['model'],request_id=payload['effect_id']))))
