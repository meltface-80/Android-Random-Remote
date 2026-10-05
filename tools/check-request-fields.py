#!/usr/bin/env python3
"""
Compare what the bundled page SENDS in each POST body against what the server
READS out of it.

check-api-contract.py looks at the other direction — fields the page reads off
a response. That left the request side unchecked, and it is where the next two
bugs were: /api/play-track read `track_index` while every caller sent `track`
(every tap on a track answered 400), and /api/transfer-zone read `from`/`to`
while the page sent `from_zone`/`to_zone` (moving playback to another room
answered 400, every time). Both handlers had tests; both tests were written
from the handler's own reading, so they passed.

The page is the authority on the wire format, so this reads the page:

  - every `fetch("/api/…", { … body: JSON.stringify({ … }) })` and the
    top-level keys of the object literal it sends;
  - every route's handler(s) in RemoteApi.kt, and the names they read with
    body.str / body.optX / body.has / request.str / request.int / … — one
    level of helper calls deep.

It reports a route where the SERVER reads a name the page never sends, which is
the shape of both bugs. Names the page sends and the server ignores are listed
too, more quietly: often deliberate (a field this build has no use for), but
occasionally the other half of a rename.

It is a heuristic, not a parser, like its sibling. A body built dynamically
(Object.assign, a variable) cannot be read and is listed as such. A list to
check, not a verdict.

    python3 tools/check-request-fields.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_JS = ROOT / "app/src/main/assets/web/app.js"
API_KT = ROOT / "core/src/main/kotlin/com/musicd/lite/api/RemoteApi.kt"

# Names a handler reads that are not request fields (pagination defaults and
# the like are still fields; these are reads of other objects that the regex
# below would otherwise mistake for the body).
SERVER_NOISE = set()


def matching_close(src, i):
    """Index just past the bracket that closes the one at src[i]."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    stack = [pairs[src[i]]]
    j = i + 1
    in_str = None
    while j < len(src) and stack:
        c = src[j]
        if in_str:
            if c == "\\":
                j += 2
                continue
            if c == in_str:
                in_str = None
        elif c in "\"'`":
            in_str = c
        elif c in pairs:
            stack.append(pairs[c])
        elif c == stack[-1]:
            stack.pop()
        j += 1
    return j


def strip_line_comments(text):
    """Drop `// …` comments, which can hold an apostrophe that reads as a string."""
    return re.sub(r"(^|[\s,{(])//[^\n]*", r"\1", text)


def top_level_keys(obj):
    """Keys of a JS object literal's top level, `{ a: 1, b, "c": x }` -> a, b, c."""
    inner = strip_line_comments(obj[1:-1])
    keys, depth, token, i = [], 0, "", 0
    parts = []
    in_str = None
    while i < len(inner):
        c = inner[i]
        if in_str:
            token += c
            if c == "\\" and i + 1 < len(inner):
                token += inner[i + 1]
                i += 1
            elif c == in_str:
                in_str = None
        elif c in "\"'`":
            in_str = c
            token += c
        elif c in "([{":
            depth += 1
            token += c
        elif c in ")]}":
            depth -= 1
            token += c
        elif c == "," and depth == 0:
            parts.append(token)
            token = ""
        else:
            token += c
        i += 1
    parts.append(token)
    for p in parts:
        p = re.sub(r"//[^\n]*", "", p).strip()
        if not p or p.startswith("..."):
            continue
        m = re.match(r'["\']?([A-Za-z_$][\w$]*)["\']?\s*(:|$)', p)
        if m:
            keys.append(m.group(1))
    return keys


def client_bodies():
    """route -> list of (line, keys or None-for-dynamic) for POSTed bodies."""
    src = APP_JS.read_text()
    out = {}
    for m in re.finditer(r'fetch\(\s*([`"\'])(/api/[^`"\'?$]+)', src):
        path = m.group(2)
        open_paren = src.rfind("(", 0, m.start() + 6)
        call_end = matching_close(src, open_paren)
        call = src[open_paren:call_end]
        if "method" not in call or not re.search(r'method:\s*["\']POST', call):
            continue
        line = src.count("\n", 0, m.start()) + 1
        b = re.search(r"body:\s*JSON\.stringify\(", call)
        if not b:
            out.setdefault(path, []).append((line, []))
            continue
        arg_start = b.end()
        while call[arg_start] in " \n\t":
            arg_start += 1
        if call[arg_start] == "{":
            obj = call[arg_start:matching_close(call, arg_start)]
            out.setdefault(path, []).append((line, top_level_keys(obj)))
        else:
            out.setdefault(path, []).append((line, None))
    return out


def server_reads():
    """route -> set of request field names its handler(s) read."""
    src = API_KT.read_text()
    routes = {}
    for line in src.split("\n"):
        m = re.search(r'"(/api/[^"]+)"\s*->\s*(.+)$', line)
        if not m:
            continue
        for handler in re.findall(r"\b([a-z]\w*)\s*\(", m.group(2)):
            routes.setdefault(m.group(1), set()).add(handler)

    bodies = {}
    for m in re.finditer(r"private fun (\w+)\(", src):
        start = m.start()
        nxt = src.find("\n    private fun ", start + 1)
        bodies[m.group(1)] = src[start: nxt if nxt > 0 else len(src)]

    read_re = re.compile(
        r'\b(?:body|request|it|b|o)\.(?:str|strOrNull|int|bool|has|opt\w*|get\w*)\(\s*"([^"]+)"'
    )

    def reads_of(name, seen):
        if name in seen or name not in bodies:
            return set()
        seen.add(name)
        text = bodies[name]
        keys = set(read_re.findall(text))
        for callee in set(re.findall(r"\b(\w+)\(", text)):
            if callee in bodies and callee not in seen:
                keys |= reads_of(callee, seen)
        return keys

    out = {}
    for path, handlers in routes.items():
        keys = set()
        for h in handlers:
            keys |= reads_of(h, set())
        out[path] = keys - SERVER_NOISE
    return out


def main():
    client = client_bodies()
    server = server_reads()
    flagged = 0
    for path in sorted(client):
        if path not in server:
            continue
        reads = server[path]
        for line, keys in client[path]:
            if keys is None:
                print(f"\n{path}  (app.js:{line})  body is built dynamically — check by hand")
                continue
            sent = set(keys)
            unsent = sorted(reads - sent)
            ignored = sorted(sent - reads)
            if unsent and sent:
                flagged += 1
                print(f"\n{path}  (app.js:{line})")
                print(f"  page sends : {', '.join(sorted(sent))}")
                print(f"  server reads: {', '.join(sorted(reads))}")
                print(f"  NEVER SENT : {', '.join(unsent)}")
                if ignored:
                    print(f"  ignored    : {', '.join(ignored)}")
    print(f"\n{flagged} POST call(s) where the server reads a field this call never sends.")
    print("Heuristic — optional fields are expected here; a REQUIRED one is a bug.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
