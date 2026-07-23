import base64
import zlib
from pathlib import Path

parts = [
    "scripts/v09794-payload-1.txt",
    "scripts/v09794-payload-2.txt",
    *[f"scripts/v09794-payload-3-{index}.txt" for index in range(1, 7)],
    "scripts/v09794-payload-4-1.txt",
    "scripts/v09794-payload-4-2.txt",
    "scripts/v09794-payload-4-3a.txt",
    "scripts/v09794-payload-4-3b.txt",
    "scripts/v09794-payload-4-4.txt",
    "scripts/v09794-payload-4-5.txt",
    "scripts/v09794-payload-4-6.txt",
]
payload = "".join(Path(path).read_text(encoding="utf-8").strip() for path in parts)
script = zlib.decompress(base64.b64decode(payload)).decode()
old = """  ('src/main/kotlin/org/flowlang/scenarios/ScenarioPacks.kt',
   'text.contains(\"notify\", ignoreCase = true)',
   'notificationRequested(text)',
   6),"""
new = old.replace("\n   6),", "\n   2),")
if script.count(old) != 1:
    raise SystemExit(f"Expected one notification cardinality override, found {script.count(old)}")
exec(script.replace(old, new), {"__name__": "__main__"})
