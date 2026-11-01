#!/usr/bin/env python3
"""Authenticate a successful workspace test run before positive admission."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import time
import urllib.request

from condition_composition_oracle import require

WORKFLOW = '.github/workflows/github-workspace-composition.yml'
ARTIFACT = 'github-workspace-composition-'
WAIT_SECONDS = 70 * 60  # 50-minute native critical path plus runner queue allowance.


def trigger_digest(environment, event):
    binding = {key: environment[key] for key in
               ('GITHUB_REPOSITORY', 'GITHUB_EVENT_NAME', 'EXPECTED_SHA', 'WORKFLOW_REVISION')}
    binding['event'] = event
    return hashlib.sha256(json.dumps(binding, sort_keys=True, separators=(',', ':'),
                                     allow_nan=False).encode()).hexdigest()


def artifact_name(digest, attempt):
    require(re.fullmatch('[0-9a-f]{64}', digest) and re.fullmatch('[1-9][0-9]*', str(attempt)),
            'Invalid probe artifact identity')
    return f'{ARTIFACT}{digest}-{attempt}'


def attempt_path(run_id, attempt):
    require(all(re.fullmatch('[1-9][0-9]*', str(value)) for value in (run_id, attempt)),
            'Invalid probe attempt identity')
    return f'runs/{run_id}/attempts/{attempt}'


def listing(repository, path, key, token):
    rows = []
    separator = '&' if '?' in path else '?'
    for page in range(1, 11):
        response = get(repository, f'{path}{separator}per_page=100&page={page}', token)
        rows.extend(response[key])
        if len(rows) == response['total_count']:
            require(len({row['id'] for row in rows}) == len(rows), 'Duplicate GitHub listing identity')
            return rows
        require(len(rows) < response['total_count'], 'Inconsistent GitHub listing')
    raise ValueError('GitHub listing exceeds probe observation budget')


def native_context(proof, parent):
    require(all(proof[key] == parent[key] for key in ('sourceRevision', 'workflowRevision')),
            'Workspace source or workflow revision differs')
    result = {key: proof[key] for key in ('sourceRevision', 'workflowRevision', 'runId', 'attempt')}
    require(all(isinstance(result[key], str) and re.fullmatch('[1-9][0-9]*', result[key])
                for key in ('runId', 'attempt')), 'Invalid workspace run identity')
    return result


def get(repository, path, token):
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository')
    request = urllib.request.Request(f'https://api.github.com/repos/{repository}/actions/{path}',
        headers={'Authorization': f'Bearer {token}', 'Accept': 'application/vnd.github+json',
                 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def authenticate(run, jobs, native, repository):
    require(type(run['id']) is int and str(run['id']) == native['runId'] and
            type(run['run_attempt']) is int and str(run['run_attempt']) == native['attempt'],
            'Workspace pinned run attempt differs')
    require(run['repository']['full_name'] == repository and run['path'] == WORKFLOW and
            run['head_sha'] == native['sourceRevision'] and
            run['event'] in ('pull_request', 'push', 'workflow_dispatch'), 'Workspace workflow provenance differs')
    require(run['status'] == 'completed' and run['conclusion'] == 'success',
            'Workspace test run must complete successfully')
    collectors = [job for job in jobs if job['name'] == 'collect']
    require(len(collectors) == 1, 'Missing or duplicate workspace collector')
    collector = collectors[0]
    require(collector['status'] == 'completed' and collector['conclusion'] == 'success' and
            collector['head_sha'] == native['sourceRevision'] and
            type(collector['run_id']) is int and str(collector['run_id']) == native['runId'] and
            type(collector['run_attempt']) is int and str(collector['run_attempt']) == native['attempt'],
            'Workspace collector did not succeed in the authenticated attempt')
    # The workspace oracle independently checks every remaining job and failed step.


def locate(fetch_runs, fetch_artifacts, fetch_attempt, revision, digest, event_name,
           explicit=None, pause=time.sleep, clock=time.monotonic, timeout=WAIT_SECONDS):
    """Pin one event-bound artifact/attempt; never follow a moving latest attempt."""
    require(timeout > 0, 'Invalid probe observation deadline')
    if explicit:
        attempt_path(*explicit)
    deadline = clock() + timeout

    def remaining():
        seconds = deadline - clock()
        require(seconds > 0, 'Workspace probe did not complete within the observation budget')
        return seconds

    def query(fetch, *arguments):
        remaining()
        value = fetch(*arguments)
        remaining()
        return value

    def observe(run_id, attempt):
        run = query(fetch_attempt, run_id, attempt)
        require(type(run['id']) is int and str(run['id']) == str(run_id) and
                type(run['run_attempt']) is int and str(run['run_attempt']) == str(attempt) and
                run['head_sha'] == revision and run['path'] == WORKFLOW,
                'Pinned probe provenance differs')
        return run

    # Manual selection is a reference to completed evidence, not a request to
    # wait for an unrelated or unfinished run to produce an archive.
    explicit_run = observe(*explicit) if explicit else None
    if explicit_run is not None:
        require(explicit_run['status'] == 'completed', 'Selected manual probe attempt is not completed')
        require(explicit_run['conclusion'] == 'success', 'Workspace probe has an unexpected final verdict')
    pinned = None
    observations = 0
    while True:
        remaining()
        if pinned is None:
            candidates = []
            runs = ([{'id': explicit[0]}] if explicit else query(fetch_runs))
            for run in runs:
                if not explicit and (run['head_sha'] != revision or run['path'] != WORKFLOW or
                                     run['event'] != event_name):
                    continue
                for artifact in query(fetch_artifacts, run['id']):
                    match = re.fullmatch(re.escape(ARTIFACT) + r'([0-9a-f]{64})-([1-9][0-9]*)', artifact['name'])
                    if not match or artifact['expired']:
                        continue
                    if explicit:
                        if match[2] != str(explicit[1]):
                            continue
                    elif match[1] != digest:
                        continue
                    candidates.append((run['id'], int(match[2]), artifact['id'], artifact['name']))
            require(not explicit or candidates, 'Selected manual probe attempt has no available evidence artifact')
            require(len({row[0] for row in candidates}) <= 1, 'Ambiguous event-bound probe runs')
            if candidates:
                # Earlier attempts remain immutable and usable after a later rerun.
                candidates.sort(key=lambda row: row[1], reverse=True)
                require(len([row for row in candidates if row[:2] == candidates[0][:2]]) == 1,
                        'Duplicate probe attempt artifacts')
                pinned = candidates[0]
        if pinned is not None:
            run_id, attempt, artifact_id, name = pinned
            run = explicit_run if explicit_run is not None else observe(run_id, attempt)
            if run['status'] == 'completed':
                require(run['conclusion'] == 'success', 'Workspace probe has an unexpected final verdict')
                remaining()
                return {'run_id': str(run_id), 'attempt': str(attempt),
                        'artifact_id': str(artifact_id), 'artifact_name': name}
        seconds = remaining()
        observations += 1
        if observations % 4 == 1:
            print(f'Waiting for event-bound probe: pinned={pinned[:2] if pinned else None}; '
                  f'{int(seconds)} seconds remaining', flush=True)
        pause(min(15, seconds))


def main():
    import composition_coverage as coverage
    from cross_runtime_condition_composition import api_jobs
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('identity', 'locate', 'admit'))
    parser.add_argument('directory', nargs='?', type=Path)
    args = parser.parse_args()
    parent = coverage.context(os.environ)
    repository, token = os.environ['GITHUB_REPOSITORY'], os.environ['GH_TOKEN']
    if args.command in ('identity', 'locate'):
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        digest = trigger_digest(os.environ, event)
        print(f'Workspace trigger digest: {digest}', flush=True)
        if args.command == 'identity':
            result = {'artifact_name': artifact_name(digest, parent['attempt'])}
        else:
            explicit = None
            if os.environ['GITHUB_EVENT_NAME'] == 'workflow_dispatch':
                explicit = (event['inputs']['workspace_probe_run_id'], event['inputs']['workspace_probe_attempt'])
                attempt_path(*explicit)
            result = locate(
                lambda: listing(repository, f'workflows/github-workspace-composition.yml/runs?head_sha={parent["sourceRevision"]}', 'workflow_runs', token),
                lambda run_id: listing(repository, f'runs/{run_id}/artifacts', 'artifacts', token),
                lambda run_id, attempt: get(repository, attempt_path(run_id, attempt), token),
                parent['sourceRevision'], digest, os.environ['GITHUB_EVENT_NAME'], explicit=explicit)
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            for key, value in result.items():
                stream.write(f'{key}={value}\n')
        return
    files = coverage.snapshot(args.directory.absolute())
    native = native_context(coverage.decode(files['proof.json']), parent)
    require(native['runId'] == os.environ['PROBE_RUN_ID'] and native['attempt'] == os.environ['PROBE_ATTEMPT'],
            'Downloaded proof belongs to another probe attempt')
    jobs = api_jobs(repository, native['runId'], native['attempt'], token)
    authenticate(get(repository, attempt_path(native['runId'], native['attempt']), token), jobs, native, repository)
    import verify_github_workspace_composition as workspace
    assessment, authenticated = workspace.assess(files, workspace.inventory(Path.cwd()), native, jobs)
    proof = coverage.decode(files['proof.json'])
    require(coverage.decode(files['github-jobs.json']) == authenticated and
            all(proof[key] == value for key, value in assessment.items()), 'Workspace replay differs')
    result = coverage.receipt('github-workspace-composition', files, parent)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write('receipt=' + json.dumps(result, separators=(',', ':')) + '\n')
    print('PASS: authenticated asserted action failures, output-bound consumer guards and successful collector')


if __name__ == '__main__':
    main()
