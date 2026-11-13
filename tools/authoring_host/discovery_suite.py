"""Bounded evaluation inputs. Exposure is a complete directory, never an answer map."""
from __future__ import annotations

import os
from pathlib import Path
import stat

from .contracts import MAX_BYTES, canonical, digest, keys, loads, nonblank, require, requirements
from .discovery import _directory, relative, validate
from .investigation import identifier
from .inventory_provenance import read_regular

MAX_CASES = 32
MAX_RUNS = 96
MAX_FILES = 128
MAX_SUITE_BYTES = 8_000_000
MAX_SUITE_ENTRIES = 1024


def snapshot(directory):
    """Capture UTF-8 bytes and empty directories; reject links and special files.

    This is a bounded local snapshot, not an atomic remote-system observation.
    No filename or expected extraction path is sent to the model by this loader.
    """
    files, directories, total = {}, [], 0
    def visit(fd, prefix):
        nonlocal total
        before = os.fstat(fd)
        names = sorted(os.listdir(fd))
        require(len(names) <= 256 and len(directories) <= MAX_FILES, "Exposure directory limit exceeded")
        for name in names:
            path = "/".join([*prefix, name])
            relative(path)
            info = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if stat.S_ISDIR(info.st_mode):
                require(len(directories) < MAX_FILES, "Exposure directory limit exceeded")
                directories.append(path)
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                try:
                    require(os.fstat(child).st_ino == info.st_ino, "Exposure directory changed")
                    visit(child, [*prefix, name])
                finally:
                    os.close(child)
            else:
                require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, "Exposure requires regular single-link files")
                require(len(files) < MAX_FILES, "Exposure file limit exceeded")
                child = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
                with os.fdopen(child, "rb") as stream:
                    first = os.fstat(stream.fileno())
                    require(stat.S_ISREG(first.st_mode) and first.st_nlink == 1 and
                            (first.st_dev, first.st_ino) == (info.st_dev, info.st_ino), "Exposure file changed")
                    data = stream.read(65537)
                    last = os.fstat(stream.fileno())
                require(len(data) <= 65536, "Exposure file byte limit exceeded")
                require((first.st_size, first.st_mtime_ns, first.st_ctime_ns) ==
                        (last.st_size, last.st_mtime_ns, last.st_ctime_ns), "Exposure file changed during capture")
                try:
                    data.decode("utf-8", errors="strict")
                except UnicodeError:
                    require(False, "Exposure must contain UTF-8 text")
                total += len(data)
                require(total <= MAX_BYTES, "Exposure byte limit exceeded")
                files[path] = data
        after = os.fstat(fd)
        require((before.st_mtime_ns, before.st_ctime_ns) == (after.st_mtime_ns, after.st_ctime_ns),
                "Exposure directory changed during capture")
    fd = _directory(str(Path(directory).absolute()))
    try:
        visit(fd, [])
    finally:
        os.close(fd)
    return files, directories


def inventory(files, directories):
    return dict(files={name: digest(data) for name, data in sorted(files.items())}, directories=sorted(directories))


def load_suite(path):
    path = Path(path).absolute()
    raw = read_regular(path, MAX_BYTES)
    suite = loads(raw)
    keys(suite, ("formatVersion", "id", "description", "discoveryLimits", "cases"))
    require(type(suite["formatVersion"]) is int and suite["formatVersion"] == 1, "Unsupported discovery evaluation suite")
    identifier(suite["id"]); nonblank(suite["description"])
    limits = suite["discoveryLimits"]
    keys(limits, ("maxSteps", "maxSourceBytes", "maxTotalSourceBytes", "maxRecords", "maxEntries"))
    require(isinstance(suite["cases"], list) and 1 <= len(suite["cases"]) <= MAX_CASES, "Invalid discovery case count")
    protected, cases, names = {path}, [], set()
    def private_file(name):
        return path.parent.joinpath(*relative(name))
    for row in suite["cases"]:
        keys(row, ("id", "domain", "environment", "exposure", "request", "requirements", "roots"))
        for field in ("id", "domain", "environment"):
            identifier(row[field])
        require(row["id"] not in names, "Duplicate discovery case")
        names.add(row["id"])
        require(row["exposure"] in ("public-development", "caller-declared-held-out"), "Explicit source exposure attribution required")
        request_path, requirements_path = private_file(row["request"]), private_file(row["requirements"])
        protected.update((request_path, requirements_path))
        request_data, requirements_data = (read_regular(p, MAX_BYTES) for p in (request_path, requirements_path))
        request = loads(request_data)
        keys(request, ("userText",), ("context", "mode"))
        nonblank(request["userText"])
        original = requirements(requirements_data, request)
        require(original["formatVersion"] == 5 and original["confirmation"] == "CONFIRMED" and
                bool(original.get("reviewerId")), "Discovery evaluation requires independently confirmed requirements v5")
        require(isinstance(row["roots"], list) and 1 <= len(row["roots"]) <= 8, "Invalid case exposure roots")
        roots = []
        for source in row["roots"]:
            keys(source, ("id", "directory", "scope"))
            roots.append(dict(id=source["id"], path=str(private_file(source["directory"])), scope=source["scope"]))
        validate(dict(formatVersion=3, roots=roots, **limits))
        cases.append(dict(metadata=row, request=request_data, requirements=requirements_data, roots=roots))
    # Cross-case expectations are private too, even when multiple tasks share an environment.
    for case in cases:
        for root in case["roots"]:
            exposed = Path(root["path"])
            require(all(p != exposed and exposed not in p.parents for p in protected),
                    "Exposure contains suite metadata, a request or independent requirements")
    total, entries, environments = len(raw), 0, {}
    for case in cases:
        total += len(case["request"]) + len(case["requirements"])
        for source in case["roots"]:
            source["files"], source["directories"] = snapshot(source["path"])
            source["inventory"] = inventory(source["files"], source["directories"])
            entries += len(source['files']) + len(source['directories'])
            require(entries <= MAX_SUITE_ENTRIES, 'Discovery suite entry limit exceeded')
            total += sum(len(data) for data in source["files"].values())
            require(total <= MAX_SUITE_BYTES, "Discovery suite byte limit exceeded")
        exposure = digest(canonical([dict(id=r['id'], scope=r['scope'], inventory=r['inventory']) for r in case['roots']]))
        environment = case['metadata']['environment']
        require(environments.setdefault(environment, exposure) == exposure,
                "Tasks in one environment must receive the same complete exposure")
        case['exposureSha256'] = exposure
    return raw, suite, cases
