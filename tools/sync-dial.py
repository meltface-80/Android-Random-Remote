#!/usr/bin/env python3
"""
Regenerates the ported Dial for Roon files from a pinned upstream commit.

The dial is not this project's code. It comes from meltface-80/dial-for-Roon,
which is where it is developed and where it will keep changing, and the
difference between that code and the copy here is a short list of string
substitutions — a package name and one import. Writing that list down and
running it means an upstream release is a version bump and a re-run, rather
than somebody reading two files side by side and hoping.

    tools/sync-dial.py --check           # have the ported files been touched?
    tools/sync-dial.py --write --source <dir>   # regenerate them from a checkout
    tools/sync-dial.py --latest          # what has upstream done since?
    tools/sync-dial.py --write --commit <sha>   # move to a new upstream commit

CI runs --check, so a hand-edit to a ported file fails the build. That is the
point: a local fix to a synced file would be silently undone by the next sync,
so the build refuses to let one exist.

--check DOES NOT USE THE NETWORK, and that is deliberate. It used to re-fetch
the pinned commit and regenerate, which meant the build could only answer "has
anyone edited these files?" while a SECOND repository was reachable. In
September 2026 that repository stopped being reachable — it is no longer on the
account at all — and the build went red on every PR, including ones that do not
go near the dial. A guard that fails when something unrelated disappears is not
guarding anything; it is just a second thing that can break.

So the sync records a sha256 of every file it writes, in the manifest, and
--check compares the tree against those. That answers the question the guard
actually exists to answer, locally and deterministically. It also covers a gap
the old check had: the digests are keyed to a hash of the manifest inputs (the
commit, the file list and the rewrites), so changing a rewrite without re-running
the sync fails too, where before the output was simply never regenerated.

Regenerating still needs the upstream tree. With the repository gone that means
--write wants --source pointing at a checkout you already have; there is nothing
left to fetch. See the note in CLAUDE.md about what that means for these files.
"""

import argparse
import hashlib
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


def digest(text):
    """The identity of one ported file, as --check compares it."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def inputs_digest(manifest):
    """
    A hash of everything that decides what the sync WOULD write.

    Recorded beside the file digests so the two cannot drift apart. Editing a
    rewrite, adding a file or moving the pinned commit changes this, and
    --check then says the recorded digests were produced by a different
    manifest than the one in the tree — which the old check could not notice,
    because it regenerated from whatever the manifest currently said and so
    agreed with itself by construction.
    """
    material = {
        "repo": manifest["repo"],
        "commit": manifest["commit"],
        "files": manifest["files"],
        "rewrites": manifest["rewrites"],
    }
    canonical = json.dumps(material, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


UPSTREAM_GONE = (
    "could not read {repo}.\n"
    "That repository is no longer reachable — it was removed from the account in\n"
    "September 2026 — so there is nothing left to fetch or compare against.\n"
    "If you have a checkout of it, pass --source <dir>. If you do not, these files\n"
    "are this repository's own now; see the dial section of CLAUDE.md."
)


def checkout(repo, commit, into):
    """A shallow fetch of exactly the one commit we pin."""
    subprocess.run(["git", "init", "-q", into], check=True)
    subprocess.run(["git", "-C", into, "remote", "add", "origin", repo], check=True)
    fetched = subprocess.run(
        ["git", "-C", into, "fetch", "-q", "--depth", "1", "origin", commit],
        capture_output=True, text=True,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
    )
    if fetched.returncode != 0:
        # A stack trace ending in CalledProcessError says "git exited 128" and
        # nothing about what to do next, which is how this last failed.
        raise SystemExit(
            UPSTREAM_GONE.format(repo=repo)
            + "\n\ngit said: " + (fetched.stderr.strip() or "nothing")
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


def verify_digests(manifest):
    """
    The offline check: does the tree still hold what the sync wrote?

    Returns a list of complaints, empty when all is well.
    """
    recorded = manifest.get("digests")
    if not recorded:
        return [
            "tools/dial-upstream.json records no digests.\n"
            "  Run: tools/sync-dial.py --write --source <checkout of the dial>"
        ]

    problems = []
    if manifest.get("inputs") != inputs_digest(manifest):
        problems.append(
            "the manifest has changed since the digests were recorded.\n"
            "  A file, a rewrite or the pinned commit moved without a re-sync, so\n"
            "  what is in the tree is not what this manifest would produce.\n"
            "  Re-run: tools/sync-dial.py --write --source <checkout of the dial>"
        )

    for entry in manifest["files"]:
        path = entry["to"]
        full = os.path.join(ROOT, path)
        if not os.path.exists(full):
            problems.append(f"{path} (missing)")
            continue
        with open(full, encoding="utf-8") as f:
            if digest(f.read()) != recorded.get(path):
                problems.append(path)

    unknown = sorted(set(recorded) - {e["to"] for e in manifest["files"]})
    for path in unknown:
        problems.append(f"{path} (has a digest but is not in the file list)")
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="regenerate the ported files")
    ap.add_argument("--check", action="store_true", help="fail if they have been edited")
    ap.add_argument("--latest", action="store_true", help="report upstream's HEAD")
    ap.add_argument("--commit", help="pin a new upstream commit and write")
    ap.add_argument("--source", help="a checkout of the dial to regenerate from")
    args = ap.parse_args()

    manifest = load()

    if args.latest:
        ls = subprocess.run(
            ["git", "ls-remote", manifest["repo"], "HEAD"],
            capture_output=True, text=True,
            env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
        )
        if ls.returncode != 0 or not ls.stdout.split():
            raise SystemExit(
                UPSTREAM_GONE.format(repo=manifest["repo"])
                + "\n\ngit said: " + (ls.stderr.strip() or "nothing")
            )
        head = ls.stdout.split()[0]
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

    # The check is offline and always available. Handed a --source it ALSO does
    # the full regeneration comparison, which is the stronger statement — but it
    # is the digests that CI depends on, because CI has no checkout to hand it.
    if args.check and not args.source:
        problems = verify_digests(manifest)
        if not problems:
            # The digests say the ported files are untouched. These two say the
            # things they DEPEND ON still exist — a string deleted from
            # res/values/strings.xml breaks the dial without touching one of
            # these files, so it has to be checked from the tree as well.
            in_tree = {}
            for entry in manifest["files"]:
                with open(os.path.join(ROOT, entry["to"]), encoding="utf-8") as f:
                    in_tree[entry["to"]] = f.read()
            check_leftovers(in_tree)
            check_needs(manifest, in_tree)
        if problems:
            print(
                "these are not what the sync wrote:\n  " + "\n  ".join(problems)
                + "\n\nThe dial was ported from " + manifest["repo"] + " and these\n"
                  "files are generated, not written here. Do not hand-edit them.\n"
                  "See the dial section of CLAUDE.md.",
                file=sys.stderr,
            )
            return 1
        print(
            f"{len(manifest['digests'])} file(s) match the sync of "
            f"{manifest['commit'][:8]}"
        )
        return 0

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
            # The recorded digests have to agree with the source too, or the
            # offline check CI runs would be answering from a stale baseline.
            differing += verify_digests(manifest)
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

        # Written in the same breath as the files, never separately: a digest
        # recorded from anything but what was just written is worse than none.
        with open(MANIFEST) as f:
            raw = json.load(f)
        if args.commit:
            raw["commit"] = args.commit
        raw["digests"] = {path: digest(text) for path, text in generated.items()}
        raw["inputs"] = inputs_digest(raw)
        with open(MANIFEST, "w") as f:
            json.dump(raw, f, indent=2)
            f.write("\n")
        if args.commit:
            print(f"pinned {args.commit}")
        print(f"recorded {len(raw['digests'])} digest(s)")
        return 0
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
