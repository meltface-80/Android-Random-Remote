#!/usr/bin/env python3
"""
Regenerates the ported Dial for Roon files from a pinned upstream commit.

The dial is not this project's code. It comes from meltface-80/dial-for-Roon,
which is where it is developed and where it will keep changing, and the
difference between that code and the copy here is a short list of string
substitutions — a package name and one import. Writing that list down and
running it means an upstream release is a version bump and a re-run, rather
than somebody reading two files side by side and hoping.

    tools/sync-dial.py --check           # do the ported files match upstream?
    tools/sync-dial.py --write           # regenerate them
    tools/sync-dial.py --latest          # what has upstream done since?
    tools/sync-dial.py --write --commit <sha>   # move to a new upstream commit

CI runs --check, so a hand-edit to a ported file fails the build. That is the
point: a local fix to a synced file would be silently undone by the next sync,
so the build refuses to let one exist. Fix it upstream, or move the file out of
the manifest and adapt it by hand.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, "tools", "dial-upstream.json")


def load():
    with open(MANIFEST) as f:
        return json.load(f)


def checkout(repo, commit, into):
    """A shallow fetch of exactly the one commit we pin."""
    subprocess.run(["git", "init", "-q", into], check=True)
    subprocess.run(["git", "-C", into, "remote", "add", "origin", repo], check=True)
    subprocess.run(
        ["git", "-C", into, "fetch", "-q", "--depth", "1", "origin", commit],
        check=True,
    )
    subprocess.run(["git", "-C", into, "checkout", "-q", "FETCH_HEAD"], check=True)
    return into


def port(text, rewrites):
    for old, new in rewrites:
        text = text.replace(old, new)
    return text


def generate(manifest, source):
    """The ported content of every file, as {path: text}."""
    out = {}
    for entry in manifest["files"]:
        src = os.path.join(source, entry["from"])
        if not os.path.exists(src):
            raise SystemExit(
                f"upstream no longer has {entry['from']}.\n"
                "It was moved, renamed or deleted. Update tools/dial-upstream.json."
            )
        with open(src, encoding="utf-8") as f:
            out[entry["to"]] = port(f.read(), manifest["rewrites"])
    return out


def check_leftovers(generated):
    """Nothing may still refer to the upstream package after rewriting."""
    bad = [p for p, text in generated.items() if "roondial" in text]
    if bad:
        raise SystemExit(
            "these still mention the upstream package after rewriting:\n  "
            + "\n  ".join(bad)
            + "\nAdd a rewrite to tools/dial-upstream.json."
        )


def check_needs(manifest, generated):
    """Every resource the synced files expect must exist in this app."""
    missing = []
    strings_file = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
    with open(strings_file, encoding="utf-8") as f:
        strings = f.read()
    for name in manifest["needs"]["strings"]:
        if f'name="{name}"' not in strings:
            missing.append(f"@string/{name} (add it to res/values/strings.xml)")

    layout = generated.get("app/src/main/res/layout/widget_dial.xml", "")
    for name in manifest["needs"]["ids"]:
        if f"@+id/{name}" not in layout:
            missing.append(f"@+id/{name} is no longer declared by the upstream layout")

    if missing:
        raise SystemExit("the sync is missing things it needs:\n  " + "\n  ".join(missing))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="regenerate the ported files")
    ap.add_argument("--check", action="store_true", help="fail if they differ")
    ap.add_argument("--latest", action="store_true", help="report upstream's HEAD")
    ap.add_argument("--commit", help="pin a new upstream commit and write")
    ap.add_argument("--source", help="use an existing checkout instead of fetching")
    args = ap.parse_args()

    manifest = load()

    if args.latest:
        head = subprocess.run(
            ["git", "ls-remote", manifest["repo"], "HEAD"],
            capture_output=True, text=True, check=True,
        ).stdout.split()[0]
        pinned = manifest["commit"]
        print(f"pinned   {pinned}")
        print(f"upstream {head}")
        print("up to date" if head == pinned else
              "upstream has moved — re-run with --write --commit " + head)
        return 0 if head == pinned else 1

    if args.commit:
        manifest["commit"] = args.commit
        args.write = True

    if not (args.write or args.check):
        ap.error("choose --write, --check or --latest")

    tmp = None
    try:
        if args.source:
            source = args.source
        else:
            tmp = tempfile.mkdtemp(prefix="dial-sync-")
            source = checkout(manifest["repo"], manifest["commit"], tmp)

        generated = generate(manifest, source)
        check_leftovers(generated)
        check_needs(manifest, generated)

        if args.check:
            differing = []
            for path, text in generated.items():
                full = os.path.join(ROOT, path)
                if not os.path.exists(full):
                    differing.append(f"{path} (missing)")
                    continue
                with open(full, encoding="utf-8") as f:
                    if f.read() != text:
                        differing.append(path)
            if differing:
                print(
                    "these differ from upstream " + manifest["commit"][:8] + ":\n  "
                    + "\n  ".join(differing)
                    + "\n\nThe dial is developed in " + manifest["repo"] + ".\n"
                      "Do not edit these here — the next sync would undo it.\n"
                      "Fix it upstream, then: tools/sync-dial.py --write --commit <sha>",
                    file=sys.stderr,
                )
                return 1
            print(f"{len(generated)} file(s) match upstream {manifest['commit'][:8]}")
            return 0

        for path, text in generated.items():
            full = os.path.join(ROOT, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w", encoding="utf-8") as f:
                f.write(text)
            print(f"wrote {path}")

        if args.commit:
            with open(MANIFEST) as f:
                raw = json.load(f)
            raw["commit"] = args.commit
            with open(MANIFEST, "w") as f:
                json.dump(raw, f, indent=2)
                f.write("\n")
            print(f"pinned {args.commit}")
        return 0
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
