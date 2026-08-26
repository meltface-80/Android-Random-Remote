#!/usr/bin/env python3
"""
Compare what the bundled page READS off each API response against what the
server SENDS.

Every user-visible fault in this port so far has been the same shape: the
server answered correctly under a field name nothing reads. `results` vs
`albums` emptied library search. `text` vs `description` hid every artist bio.
`items` vs `albums` broke multi-select. `ok` missing broke credential saving
and streaming logins. `is_docker` missing put a Docker banner on a phone.
Scores and Best New Music were computed and then dropped.

None of those could fail a unit test, because the tests were written from the
same misreading as the code. The page is the authority on the wire format, so
this reads the page.

It is a heuristic, not a parser: it pairs each `fetch("/api/...")` with the
property reads on the parsed body nearby, and each route with the keys its
handler puts. False positives are expected — a field read from a DIFFERENT
object in the window, or one the server sends from a helper this cannot
follow. It is a list to check, not a verdict.

    python3 tools/check-api-contract.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_JS = ROOT / "app/src/main/assets/web/app.js"
API_KT = ROOT / "core/src/main/kotlin/com/musicd/lite/api/RemoteApi.kt"

# Reads that are never response fields.
NOISE = {
    "then", "catch", "json", "text", "ok", "status", "statusText", "body",
    "length", "map", "filter", "forEach", "find", "push", "slice", "sort",
    "toFixed", "toLowerCase", "querySelector", "classList", "style", "value",
    "textContent", "appendChild", "getTime", "toString",
}


def server_routes():
    """route path -> set of keys the handler puts."""
    src = API_KT.read_text()
    routes = {}
    # A route line can name more than one handler — `if (post) save(...) else
    # read(...)` is the common shape, and taking only the first one made the
    # read handler's keys look absent.
    for line in src.split("\n"):
        m = re.search(r'"(/api/[^"]+)"\s*->\s*(.+)$', line)
        if not m:
            continue
        path, rest = m.group(1), m.group(2)
        for handler in re.findall(r"\b([a-z]\w*)\s*\(", rest):
            routes.setdefault(path, set()).add(handler)

    bodies = {}
    for m in re.finditer(r"private fun (\w+)\(", src):
        name = m.group(1)
        start = m.start()
        nxt = src.find("\n    private fun ", start + 1)
        bodies[name] = src[start: nxt if nxt > 0 else len(src)]

    out = {}
    for path, handlers in routes.items():
        keys = set()
        for h in handlers:
            body = bodies.get(h, "")
            keys |= set(re.findall(r'\.put\(\s*"([^"]+)"', body))
            # One level of helper: a handler that delegates to another private fun.
            for callee in set(re.findall(r"\b(\w+)\(", body)):
                if callee in bodies and callee != h:
                    keys |= set(re.findall(r'\.put\(\s*"([^"]+)"', bodies[callee]))
        out[path] = keys
    return out


def client_reads():
    """route path -> set of properties read off the parsed response."""
    src = APP_JS.read_text()
    lines = src.split("\n")
    found = {}
    for i, line in enumerate(lines):
        for m in re.finditer(r'fetch\(\s*[`"\']([^`"\'?]+)', line):
            path = m.group(1)
            if not path.startswith("/api/"):
                continue
            window = "\n".join(lines[i: i + 45])
            reads = set()
            for var in ("j", "s", "data", "body", "resp"):
                reads |= set(re.findall(rf"\b{var}\.(\w+)", window))
            found.setdefault(path, set()).update(reads - NOISE)
    return found


def main():
    server = server_routes()
    client = client_reads()
    problems = 0

    for path in sorted(client):
        reads = client[path]
        if not reads:
            continue
        # Exact route, else the closest prefix the server declares.
        sends = server.get(path)
        if sends is None:
            candidates = [p for p in server if path.startswith(p) or p.startswith(path)]
            if not candidates:
                continue
            sends = set().union(*(server[c] for c in candidates))
        missing = sorted(reads - sends)
        if missing:
            problems += 1
            print(f"\n{path}")
            print(f"  page reads : {', '.join(sorted(reads))}")
            print(f"  server puts: {', '.join(sorted(sends)) or '(none found)'}")
            print(f"  NOT SENT   : {', '.join(missing)}")

    print(f"\n{problems} endpoint(s) with fields the page reads and the server may not send.")
    print("Heuristic — check each one against the page before changing anything.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
