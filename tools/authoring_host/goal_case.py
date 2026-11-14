"""Materialize input-only discovery cases. Never load candidates or invoke a provider."""
from pathlib import Path

from .contracts import canonical, keys, loads, require, requirements, validate_config
from .discovery import relative
from .investigation import identifier


def prepare(config, case_data, directory):
    """Use an owner-selected transport and budget with a portable test environment.

The case owns goal, permitted observations and frozen criteria, not provider setup,
answers, source selectors, tool order or review decisions. Outputs are ordinary
inputs to run/resume/review-discovery; preparation spends no model quota.
"""
    config = loads(canonical(config))
    require('investigation' not in config, 'Use a base provider/compiler configuration without investigation')
    case = loads(case_data)
    keys(case, ('formatVersion', 'request', 'requirements', 'task', 'roots'))
    require(type(case['formatVersion']) is int and case['formatVersion'] == 1, 'Unsupported goal case')
    original = requirements(canonical(case['requirements']), case['request'])
    require(original['formatVersion'] == 5 and original['origin'] == 'REVIEWER_AUTHORED' and
            original['confirmation'] == 'CONFIRMED', 'Goal cases require frozen caller-authored constraints v5')
    root = Path(directory).absolute()
    require(not root.exists(), 'Goal case output must be a new directory')
    require(isinstance(case['roots'], list) and 1 <= len(case['roots']) <= 8, 'Invalid goal case roots')
    roots, files = [], {}
    for environment in case['roots']:
        keys(environment, ('id', 'scope', 'observations', 'files'))
        identifier(environment['id'])
        target = root/'environment'/environment['id']
        roots.append(dict(id=environment['id'], scope=environment['scope'],
                          observations=environment['observations'], path=str(target)))
        documents = environment['files']
        require(isinstance(documents, dict) and len(documents) <= 64, 'Invalid goal environment documents')
        paths = []
        for name, document in documents.items():
            path = target.joinpath(*relative(name))
            require(all(path != p and path not in p.parents and p not in path.parents for p in paths),
                    'Conflicting goal environment paths')
            paths.append(path)
            data = canonical(document)
            require(len(data) <= 65536, 'Goal environment document exceeds observation limit')
            files[path] = data
    require(sum(map(len, files.values())) <= 262144, 'Goal environment exceeds observation budget')
    config['investigation'] = dict(formatVersion=4, roots=roots, task=case['task'], maxSteps=16,
        maxSourceBytes=65536, maxTotalSourceBytes=262144, maxRecords=128, maxEntries=256)
    validate_config(config)  # Validate all portable inputs before creating anything.
    root.mkdir(mode=0o700, parents=False)
    for environment in roots:
        Path(environment['path']).mkdir(mode=0o700, parents=True)
    files.update({root/'config.json': canonical(config), root/'request.json': canonical(case['request']),
                  root/'requirements.json': canonical(original)})
    for path, data in files.items():
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with path.open('xb') as stream:
            path.chmod(0o600)
            stream.write(data)
    return dict(status='prepared', directory=str(root), providerInvoked=False,
                inputs={name: str(root/f'{name}.json') for name in ('config', 'request', 'requirements')})
