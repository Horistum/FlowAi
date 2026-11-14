"""Portable input-only cases use the same host, with no bundled provider responses."""
import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import goal_case
from authoring_host.contracts import HostError, canonical, loads, read
import test_authoring_host as host_tests
from test_authoring_discovery import action
import author_proposal


class GoalCaseTests(unittest.TestCase):
    setUp = host_tests.AuthoringHostTests.setUp
    run_host = host_tests.AuthoringHostTests.run_host

    def cases(self):
        return sorted((host_tests.ROOT/'conformance/goal-discovery').glob('*.json'))

    def test_all_cases_prepare_without_processes_answers_or_solution_maps(self):
        self.assertEqual(8, len(self.cases()))
        for case_file in self.cases():
            with self.subTest(case=case_file.name), patch('subprocess.Popen', side_effect=AssertionError('No process during preparation')):
                prepared = goal_case.prepare(self.config, read(case_file), self.root/case_file.stem)
            self.assertFalse(prepared['providerInvoked'])
            config = loads(read(prepared['inputs']['config']))
            self.assertEqual(self.config['provider'], config['provider'])
            self.assertEqual(self.config['budget'], {k:config['budget'][k] for k in self.config['budget']})
            inv = config['investigation']
            self.assertEqual(4, inv['formatVersion'])
            for key in ('bindingRequirements', 'response', 'decisions', 'toolSequence'):
                self.assertNotIn(key, inv)
            environment = Path(inv['roots'][0]['path'])
            self.assertTrue(list(environment.rglob('*.json')))
            req = loads(read(prepared['inputs']['requirements']))
            self.assertEqual(req['request'], loads(read(prepared['inputs']['request'])))
            self.assertEqual('REVIEWER_AUTHORED', req['origin'])

    def test_both_domains_dispatch_only_goal_grants_constraints_and_empty_ledger(self):
        for case_file in self.cases():
            base = copy.deepcopy(self.config)
            base['budget'].update(maxCalls=1, maxInputTokensPerCall=240000, maxTotalTokens=400000)
            prepared = goal_case.prepare(base, read(case_file), self.root/case_file.stem)
            self.config = loads(read(prepared['inputs']['config']))
            self.request = loads(read(prepared['inputs']['request']))
            self.requirements = loads(read(prepared['inputs']['requirements']))
            self.out = Path(prepared['directory'])/'run'; self.counter.unlink(missing_ok=True)
            probe = action(); probe['observe'] = [dict(kind='list', root='environment', path='.')]
            state = self.run_host([dict(response=probe)])
            self.assertEqual('budget-exhausted', state['status'])
            prompt = loads(read(self.out/'attempt-000/provider-request.json'))
            self.assertEqual(self.request, prompt['input']['task']['goal'])
            self.assertEqual([], prompt['input']['investigation']['observations'])
            self.assertNotIn('review', state['attempts'][-1])
            self.assertEqual('not-assessed', state['goalEvidence']['goalSatisfaction'])
            self.config = base

    def test_invalid_inputs_do_not_create_partial_case_or_override_transport(self):
        baseline = loads(read(self.cases()[0]))
        for mutation in ('path', 'duplicate-root', 'prefix-conflict', 'response', 'provider'):
            case = copy.deepcopy(baseline)
            if mutation == 'path': case['roots'][0]['files']['../escape.json'] = {}
            elif mutation == 'duplicate-root': case['roots'] *= 2
            elif mutation == 'prefix-conflict': case['roots'][0]['files'].update({'same':{}, 'same/child':{}})
            else: case[mutation] = {}
            output = self.root/mutation
            with self.subTest(mutation=mutation), self.assertRaises(HostError):
                goal_case.prepare(self.config, canonical(case), output)
            self.assertFalse(output.exists())

    def test_preparation_is_available_through_cli_without_dispatch(self):
        config = self.root/'base-config.json'; config.write_bytes(canonical(self.config))
        with patch('builtins.print'), patch('subprocess.Popen', side_effect=AssertionError('No dispatch')):
            self.assertEqual(0, author_proposal.main(['prepare-goal', '--config', str(config),
                '--case', str(self.cases()[0]), '--out', str(self.root/'prepared')]))
        self.assertTrue((self.root/'prepared/config.json').is_file())


if __name__ == '__main__':
    unittest.main()
