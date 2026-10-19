import copy
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from parallel_composition_oracle import expectation, bind, verify
from parallel_composition_runtime import envelope


def sample():
    # Hand-authored independent graph: two pending gates, local work, empty branch, then join.
    gate = {'kind': 'approval', 'message': 'same'}
    task = {'kind': 'task', 'name': 'work', 'params': {'url': 'git://fixture/repo', 'branch': 'selected'}}
    leaves = {'a': dict(gate, owner='left'), 'b': dict(task, owner='left'), 'c': dict(gate, owner='right'), 'd': dict(task, owner=None, name='tail')}
    contract = {'roots': ['p', 'd'], 'groups': [{'id': 'p', 'failFast': False, 'branches': [
        {'id': 'left', 'leaves': ['a', 'b']}, {'id': 'right', 'leaves': ['c']}, {'id': 'empty', 'leaves': []}]}], 'leaves': leaves}
    def row(id, start, end):
        leaf = leaves[id]
        return {'id': id, 'node': str(start+1), 'kind': leaf['kind'], 'start': start, 'end': end,
                'branch': leaf['owner'], 'directory': '.flow-parallel/left' if id == 'b' else None,
                'gitBranch': 'selected' if leaf['kind'] == 'task' else None, 'message': 'same' if leaf['kind'] == 'approval' else None,
                'result': 'success', 'errorType': None, 'causes': [], 'inputId': 'Flow_' + id if leaf['kind'] == 'approval' else None}
    observed = {'result': 'SUCCESS', 'initialPending': ['a', 'c'], 'initialInputIds': {'a': 'Flow_a', 'c': 'Flow_c'},
                'decisions': [{'requested': i, 'id': i, 'nativeId': 'Flow_' + i, 'action': 'approve'} for i in ('a','c')], 'leaves': [row('a', 3, 10), row('c', 5, 15), row('b', 11, 13), row('d', 20, 22)],
                'groups': [{'start': 1, 'end': 19, 'branches': [{'id': 'left', 'start': 2, 'end': 14},
                    {'id': 'right', 'start': 4, 'end': 16}, {'id': 'empty', 'start': 6, 'end': 7}]}],
                'checkoutEffects': [{'directory': '.flow-parallel/left', 'marker': 'selected\n'}, {'directory': None, 'marker': 'selected\n'}],
                'markers': {'.': 'selected\n', '.flow-parallel/left': 'selected\n', '.flow-parallel/right': None, '.flow-parallel/empty': None}}
    return contract, observed


class ParallelCompositionOracleTests(unittest.TestCase):
    def test_partial_order_allows_independent_branch_schedules(self):
        contract, observed = sample()
        verify(contract, observed)
        # Sibling gate may complete before or after local work; no total order is invented.
        observed['leaves'][1]['end'] = 9
        verify(contract, observed)
        # Root work may clear old directories after join; callback effects remain authoritative.
        observed['markers']['.flow-parallel/left'] = None
        verify(contract, observed)

    def test_serialization_omission_order_join_and_directory_mutations_are_rejected(self):
        contract, observed = sample()
        changes = []
        bad = copy.deepcopy(observed); bad['initialPending'] = ['a']; changes.append(bad)
        bad = copy.deepcopy(observed); bad['leaves'].pop(2); changes.append(bad)
        bad = copy.deepcopy(observed); bad['leaves'][2]['start'] = 8; changes.append(bad)
        bad = copy.deepcopy(observed); bad['leaves'][-1]['start'] = 18; changes.append(bad)
        bad = copy.deepcopy(observed); bad['leaves'][2]['directory'] = '.flow-parallel/right'; changes.append(bad)
        bad = copy.deepcopy(observed); bad['groups'][0]['branches'].pop(); changes.append(bad)
        bad = copy.deepcopy(observed); bad['checkoutEffects'][0]['marker'] = 'alternate\n'; changes.append(bad)
        bad = copy.deepcopy(observed); bad['leaves'].append(bad['leaves'][0]); changes.append(bad)
        bad = copy.deepcopy(observed); bad['initialInputIds']['c'] = 'Flow_a'; changes.append(bad)
        bad = copy.deepcopy(observed); bad['decisions'][0]['requested'] = 'c'; changes.append(bad)
        for bad in changes:
            with self.assertRaises(ValueError): verify(contract, bad)

    def test_failure_waits_for_siblings_without_exporting_successors(self):
        contract, observed = sample()
        contract['leaves']['b']['params']['branch'] = 'missing-revision'
        observed['result'] = 'FAILURE'; observed['terminalOrigin'] = 'b'
        observed['leaves'].pop()
        observed['leaves'][2].update(result='failure', errorType='hudson.AbortException', errorMessage="Couldn't find any revision to build", gitBranch='missing-revision')
        observed['markers'] = {key: None for key in observed['markers']}
        observed['checkoutEffects'] = []
        verify(contract, observed)
        observed['terminalOrigin'] = 'c'
        with self.assertRaises(ValueError): verify(contract, observed)

    def test_rejection_cancels_siblings_only_when_authored(self):
        contract, observed = sample()
        observed['result'] = 'ABORTED'; observed['terminalOrigin'] = 'a'; observed['leaves'] = observed['leaves'][:2]
        observed['leaves'][0].update(result='failure', errorType='org.jenkinsci.plugins.workflow.steps.FlowInterruptedException')
        observed['markers'] = {key: None for key in observed['markers']}
        observed['checkoutEffects'] = []
        observed['decisions'][0]['action'] = 'reject'
        verify(contract, observed, reject=True)
        contract['groups'][0]['failFast'] = True
        with self.assertRaises(ValueError): verify(contract, observed, reject=True)
        observed['decisions'] = observed['decisions'][:1]
        observed['leaves'][1].update(result='failure', causes=['org.jenkinsci.plugins.workflow.cps.steps.ParallelStep$FailFastCause'])
        verify(contract, observed, reject=True)

    def test_source_model_rejects_unmodeled_controls_and_values(self):
        gate = {'type': 'Approve', 'mode': 'manual', 'params': {'message': {'type': 'StringLiteral', 'value': 'same'}}}
        document = {'flow': {'steps': [{'type': 'Parallel', 'failFast': False, 'branches': [{'name': 'same', 'steps': [gate]}, {'name': 'same', 'steps': []}]}]}}
        expected = expectation(document)
        self.assertEqual([1, 0], [len(b['body']) for b in expected[0]['branches']])
        for bad in ({'type': 'Unknown'}, {'type': 'Parallel', 'branches': [], 'failFast': True}, dict(gate, result={'name': 'decision'})):
            changed = copy.deepcopy(document); changed['flow']['steps'][0]['branches'][0]['steps'] = [bad]
            with self.assertRaises(ValueError): expectation(changed)

    def test_binding_rejects_branch_policy_orphan_reuse_and_parameter_changes(self):
        expected = [{'kind': 'parallel', 'failFast': False, 'branches': [{'name': 'same', 'body': [{'kind': 'approval', 'message': 'same'}]}, {'name': 'same', 'body': []}]}]
        graph = {'nodes': [{'id': 'p', 'kind': 'PARALLEL', 'failFast': False, 'branches': [{'name': 'same', 'nodeIds': ['a']}, {'name': 'same', 'nodeIds': []}]},
            {'id': 'a', 'kind': 'APPROVAL', 'mode': 'manual', 'message': 'same'}], 'workflows': [{'rootNodeIds': ['p']}], 'dependencyEdges': []}
        native = {'status': 'NATIVE'}
        manifest = {'jobs': [{'steps': [{'id': 'p', 'type': 'parallel', 'materialization': native, 'metadata': {'failFast': 'false'}, 'children': [
            {'id': 'left', 'type': 'parallel-branch', 'name': 'same', 'children': [{'id': 'a', 'type': 'approval', 'materialization': native,
                'params': {'mode': 'manual', 'message': 'same'}, 'children': []}]}, {'id': 'empty', 'type': 'parallel-branch', 'name': 'same', 'children': []}]}]}]}
        bind(expected, graph, manifest)
        for mutation in ('policy', 'orphan', 'reuse', 'message', 'branch'):
            g, m = copy.deepcopy(graph), copy.deepcopy(manifest)
            if mutation == 'policy': g['nodes'][0]['failFast'] = True
            if mutation == 'orphan': g['nodes'].append(dict(g['nodes'][1], id='orphan'))
            if mutation == 'reuse': g['nodes'][0]['branches'][1]['nodeIds'] = ['a']
            if mutation == 'message': g['nodes'][1]['message'] = 'different'
            if mutation == 'branch': m['jobs'][0]['steps'][0]['children'].reverse()
            with self.assertRaises(ValueError): bind(expected, g, m)
        graph['nodes'].reverse()
        bind(expected, graph, manifest)
        # Canonical structural IDs and adapter-safe IDs are distinct projections.
        manifest['jobs'][0]['steps'][0]['id'] = 'native_parallel'
        manifest['jobs'][0]['steps'][0]['children'][0]['children'][0]['id'] = 'native_approval'
        bound = bind(expected, graph, manifest)
        self.assertEqual(['native_parallel'], bound['roots'])
        self.assertEqual(['native_approval'], bound['groups'][0]['branches'][0]['leaves'])

    def test_envelopes_restore_identical_calls_and_reject_missing_inventory(self):
        text = "  input message: 'same'\n  git branch: 'selected', url: 'git://fixture/repo'\n"
        leaves = {'a': {'kind': 'approval'}, 'b': {'kind': 'task'}}
        wrapped, calls = envelope(text, leaves)
        for original, block in calls.values(): wrapped = wrapped.replace(block, original)
        self.assertEqual(text, wrapped)
        with self.assertRaises(ValueError): envelope('', leaves)


if __name__ == '__main__': unittest.main()
