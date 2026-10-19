"""Independent native fork/join composition evidence; no product execution path."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
from condition_composition_oracle import receipts, require, sha
from parallel_composition_oracle import bind, expectation, verify
from retry_composition_runtime import replace_once


def envelope(generated, leaves):
    ordered = iter(leaves.items()); calls = {}; output = []
    for line in generated.splitlines(keepends=True):
        if line.lstrip().startswith(('input message:', 'git branch:')):
            identity, leaf = next(ordered)
            require(line.lstrip().startswith('input message:' if leaf['kind'] == 'approval' else 'git branch:'), 'Changed leaf order')
            indent = line[:len(line) - len(line.lstrip())]
            block = indent + f"stage('flow-observe:{identity}') {{\n" + line + indent + '}\n'
            calls[identity] = (line, block); output.append(block)
        else:
            output.append(line)
    require(next(ordered, None) is None and len(calls) == len(leaves), 'Incomplete observation envelope')
    text = ''.join(output); restored = text
    for line, block in calls.values():
        restored = replace_once(restored, block, line)
    require(restored == generated, 'Envelope changes production calls')
    return text, calls


def prepare(inputs, output):
    contracts, variants, inventory = {}, {}, []
    artifacts = output / 'artifacts'; artifacts.mkdir()
    for fast in (False, True):
        for outcome in ('success', 'failure', 'reject'):
            case = f'{outcome}-{str(fast).lower()}'
            directory = inputs / f'{"failure" if outcome == "failure" else "success"}-{str(fast).lower()}'
            contract = bind(expectation(json.loads((directory / 'source.ast.json').read_text())),
                            json.loads((directory / 'canonical-graph.json').read_text()), json.loads((directory / 'manifest.json').read_text()))
            contracts[case] = contract
            baseline, calls = envelope((directory / 'Jenkinsfile').read_text(), contract['leaves'])
            first = contract['groups'][0]
            primary = first['branches'][0]['leaves']
            reject = primary[0] if outcome == 'reject' else None
            release = [i for i, leaf in contract['leaves'].items() if leaf['kind'] == 'approval']
            candidates = {'baseline': baseline}
            start = '          parallel([\n'; end = '          ])\n'
            offset = baseline.index(start); closing = baseline.index(end, offset)
            block = baseline[offset:closing + len(end)]
            if case == 'success-false':
                serialized = block.replace(start, '          ([\n', 1).replace(end,
                    "          ]).findAll { key, value -> key != 'failFast' }.each { key, body -> body() }\n")
                candidates['serialized'] = replace_once(baseline, block, serialized)
                a, b = [calls[i][1] for i in primary[1:3]]
                candidates['omitted-work'] = replace_once(baseline, a, '')
                candidates['reordered-work'] = replace_once(replace_once(baseline, a, 'FLOW_SWAP'), b, a).replace('FLOW_SWAP', b)
                candidates['aliased-workspaces'] = replace_once(baseline, '.flow-parallel/' + first['branches'][1]['id'], '.flow-parallel/' + first['branches'][0]['id'])
                successor = calls[contract['roots'][1]][1]
                candidates['early-successor'] = replace_once(replace_once(baseline, successor, ''), block, successor + block)
            if case == 'failure-false' or case == 'reject-true':
                candidates['swallowed-failure'] = replace_once(baseline, block, '          try {\n' + block +
                    '          } catch (flowMutationFailure) { }\n')
            if case == 'failure-true':
                candidates['disabled-cancellation'] = replace_once(baseline, block, block.replace('failFast: true', 'failFast: false'))
            for name, text in candidates.items():
                identity = case + '-' + name; filename = identity + '.Jenkinsfile'
                variants[identity] = {'case': case, 'variant': name, 'text': text, 'reject': outcome == 'reject'}
                (artifacts / filename).write_text(text)
                inventory.append({'id': identity, 'artifact': filename, 'release': release, 'reject': reject,
                                  'initialCount': sum(bool(b['leaves']) for b in first['branches']),
                                  'directories': ['.'] + ['.flow-parallel/' + b['id'] for g in contract['groups'] for b in g['branches']]})
    (artifacts / 'runs.json').write_text(json.dumps(inventory, indent=2) + '\n')
    return contracts, variants, inventory


def assess(contracts, variants, inventory, runtime, revisions):
    require(runtime['status'] == 'completed' and runtime['jenkins'] == '2.580.1' and runtime['java'].startswith('25') and
            runtime['plugins']['git'] == '5.10.1', 'Unexpected native runtime')
    require([r['id'] for r in runtime['runs']] == [r['id'] for r in inventory], 'Changed run inventory')
    require(set(revisions) == {'selected', 'alternate'} and len(set(revisions.values())) == 2, 'Changed fixture revision inventory')
    cases = []
    for run, invocation in zip(runtime['runs'], inventory):
        variant = variants[run['id']]
        require(run['finished'] is True and run['artifactSha256'] == sha(variant['text'].encode()), 'Incomplete run or changed artifact')
        require(set(run['markers']) == set(invocation['directories']) and
                len({r['node'] for r in run['leaves']}) == len(run['leaves']), 'Changed probe or invocation inventory')
        rejection = None
        try:
            verify(contracts[variant['case']], run, variant['reject'])
            require(all(effect['job'] == run['id'] and effect['build'] == 1 and
                        effect['revision'] == revisions.get(effect['marker'].strip()) for effect in run['checkoutEffects']),
                    'Physical checkout revision differs from fixture reference')
        except ValueError as error:
            rejection = str(error)
        require((rejection is None) == (variant['variant'] == 'baseline'), f"Baseline failed or mutant survived: {run['id']}: {rejection}")
        cases.append({'id': run['id'], 'result': run['result'], 'rejection': rejection})
    return cases


def main():
    require(len(sys.argv) == 4, 'Expected repository root, prepared inputs and fresh output directory')
    root, inputs, output = map(lambda p: Path(p).resolve(), sys.argv[1:])
    revision = os.environ['EXPECTED_SHA']
    require(subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip() == revision, 'Changed source revision')
    require(not output.exists(), 'Output must be fresh'); output.mkdir()
    shutil.copytree(inputs, output / 'inputs')
    contracts, variants, inventory = prepare(inputs, output)
    def process(label, command, timeout=60, env=None):
        path = output / (label + '.log')
        with path.open('wb') as stream:
            result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout, env=env)
        require(path.stat().st_size < 8 * 1024 * 1024, 'Oversized runtime log')
        require(result.returncode == 0, f'{label} failed: {path.read_text()[-4000:]}')
        return path.read_text().strip()
    fixture = output / 'fixture'; working = fixture / 'working'; working.mkdir(parents=True)
    fixed = dict(os.environ, GIT_AUTHOR_DATE='2000-01-01T00:00:00Z', GIT_COMMITTER_DATE='2000-01-01T00:00:00Z')
    def git(label, *args): return process(label, ['git', '-C', str(working), *args], env=fixed)
    git('fixture-init', 'init', '--initial-branch=selected')
    git('fixture-name', 'config', 'user.name', 'Flow Conformance')
    git('fixture-email', 'config', 'user.email', 'fixture@example.invalid')
    for branch in ('selected', 'alternate'):
        if branch == 'alternate': git('fixture-branch', 'checkout', '-b', branch)
        (working / 'marker.txt').write_text(branch + '\n')
        git('fixture-add-' + branch, 'add', 'marker.txt'); git('fixture-commit-' + branch, 'commit', '-m', branch)
    revisions = {branch: git('fixture-revision-' + branch, 'rev-parse', branch) for branch in ('selected', 'alternate')}
    (output / 'fixture-revisions.json').write_text(json.dumps(revisions, indent=2) + '\n')
    process('fixture-bare', ['git', 'clone', '--bare', str(working), str(fixture / 'repository.git')])
    shutil.rmtree(working)
    # Only the bare fixture enters the container; receipts cover its actual bytes.
    runtime = output / 'runtime'; runtime.mkdir(); runtime.chmod(0o777)
    native = root / 'flow-adapter-jenkins/src/runtimeTest'
    container = 'flow-parallel-composition-' + uuid.uuid4().hex; tag = container + ':local'
    try:
        process('image-build', ['docker', 'build', '--tag', tag, str(native)], 600)
        image = process('image-identity', ['docker', 'image', 'inspect', '--format', '{{.Id}}', tag])
        process('controller', ['docker', 'run', '--rm', '--name', container, '--network', 'none', '--memory', '3g', '--cpus', '2',
            '--mount', f'type=bind,src={output / "artifacts"},dst=/artifacts,readonly',
            '--mount', f'type=bind,src={fixture / "repository.git"},dst=/fixture/repository.git,readonly',
            '--mount', f'type=bind,src={runtime},dst=/evidence',
            '--mount', f'type=bind,src={native / "parallel-composition-observer.groovy"},dst=/usr/share/jenkins/ref/init.groovy.d/parallel.groovy,readonly', image], 1200)
        data = runtime / 'runtime.json'; require(0 < data.stat().st_size < 2 * 1024 * 1024, 'Oversized native evidence')
        cases = assess(contracts, variants, inventory, json.loads(data.read_text()), revisions)
        proof = {'status': 'passed', 'sourceRevision': revision, 'workflowRevision': os.environ['WORKFLOW_REVISION'],
                 'runId': os.environ['GITHUB_RUN_ID'], 'attempt': os.environ['GITHUB_RUN_ATTEMPT'], 'imageId': image,
                 'cases': cases, 'files': receipts(output), 'publicSupportPromoted': False, 'portableExecution': False,
                 'claim': 'Native fork/join composition, sequential branch order, workspace separation and authored failure policy',
                 'limits': ['Top-level groups and sequential control-local/workspace-local leaves only',
                            'No branch output export, other control nesting, shared-service/security isolation or fresh-workspace claim',
                            'Native evidence covers manual inputs and full-history literal-branch git mappings',
                            'Observation stages restore original calls byte-for-byte; compiler, CI, SCM callbacks and native graph are trusted',
                            'Physical checkout effects are captured on completion; later root work may remove completed branch directories',
                            'Separate composition regression, not a signed portfolio expansion']}
        (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
        print(f'PASS: parallel composition: {len(cases)} native runs; all baselines pass and all mutants rejected')
    finally:
        for command in (['docker', 'rm', '--force', container], ['docker', 'image', 'rm', tag]):
            subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, check=False)


if __name__ == '__main__':
    main()
