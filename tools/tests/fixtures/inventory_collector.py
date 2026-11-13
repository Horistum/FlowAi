"""Independent read-only SQLite inventory source for process integration tests."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import sys


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", required=True)
    parser.add_argument("--mutation", default="none")
    args = parser.parse_args()
    request = json.load(sys.stdin)
    encoded = (json.dumps(request, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode()
    with sqlite3.connect(Path(args.database).resolve().as_uri() + "?mode=ro", uri=True) as connection:
        system, scope, revision = connection.execute("SELECT system, scope, revision FROM metadata").fetchone()
        files = [dict(path=path, text=text) for path, text in connection.execute("SELECT path, content FROM documents ORDER BY path")]
    response = dict(formatVersion=1, captureRequestSha256=hashlib.sha256(encoded).hexdigest(),
                    system=system, scope=scope, files=files, sourceRevision=revision)
    if args.mutation == "scope":
        response["scope"] = "another-environment"
    elif args.mutation == "request":
        response["captureRequestSha256"] = "0" * 64
    elif args.mutation == "failure":
        sys.exit(1)
    print(json.dumps(response))
