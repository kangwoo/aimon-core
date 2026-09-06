#!/usr/bin/env python3
"""Report translations that have fallen behind their canonical document.

Every translated file carries the commit of the canonical it was translated
from:

    ---
    translated_from: docs/features/tool/tool-development-guide.md
    source_commit: eec9ccd
    ---

This script compares that commit against the canonical's current history and
sorts each translation into one of two findings, which are NOT the same thing
and do not exit the same way.

STALE -- the guard worked and the answer is bad: the canonical moved on. This
exits 0. A translation backlog that blocks edits to the canonical makes the
canonical go stale instead, which is the worse of the two failure modes. Pass
--strict to make staleness an error anyway.

--strict has no caller in this repository and that is deliberate, so before
wiring it into scripts/release.sh: release.sh already refuses to run unless the
tree is clean, on main, and level with origin/main, and every commit that
reaches main is run through this job on that exact tree -- release.sh does not
check that the run passed, but a red main is visible, so a release gate would
add nothing on the unresolvable axis, which now fails everywhere anyway.
What it would add is a gate on staleness at the one moment the argument above
bites hardest. A translation a week behind would block a release, and the
release is not the thing that is wrong; the pressure at that moment does not
produce a translation, it produces a deleted check. Run it by hand before a
release if you want to know. Do not make it the thing standing between a fix
and the people waiting for it.

UNRESOLVABLE -- the guard has no answer at all: the front matter is missing, the
canonical it names is gone, or the source_commit is not a commit in this
history. This exits 1. The reasoning that keeps STALE at 0 does not reach here:
failing on unresolvable does not pressure anyone to skip a translation, it asks
for a resolvable SHA, which is one line and belongs to whoever wrote the file.
Left at 0 it is worse than either -- a stale translation says the guard is
unhappy, an unresolvable one says nothing while looking like nothing is wrong.

That distinction was not free. The open-source history squash retired every
pre-squash SHA at once and 19 of 32 translations went unresolvable. Every run
after it stayed green, because both findings shared an exit code -- and of the
19 annotations emitted, the check-runs API returns 10, so the job's console
output was the only complete account of it and a green job gives nobody a
reason to open that.

The one exception is a shallow clone, and it reaches exactly as far as its own
reason. Truncated history is why a source_commit can look absent or unrelated to
HEAD, so those two findings are reported and not counted; CI passes
fetch-depth: 0 for this reason. Depth cannot delete front matter or move a
canonical, so those findings fail at any depth. An exemption wider than the
reason for it is the same green-that-means-nothing this file exists to remove,
just in a narrower window.

The direction is not assumed. `translated_from` names the canonical whichever
language it is in, so this handles both docs/**/*.en.md (Korean canonical) and
CONTRIBUTING.ko.md (English canonical) with the same code.

Usage:
    python3 scripts/check-translation-staleness.py [--strict] [--github]
"""
import re
import subprocess
import sys
import pathlib

from docs_tree import SKIP_DIRS, translation_suffix

ROOT = pathlib.Path(__file__).resolve().parent.parent
FRONT = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n", re.DOTALL)
KEY = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):\s*(.*?)\s*$", re.MULTILINE)


def git(*args):
    """Run a git command in the repo, returning stdout (stripped) or None."""
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), *args],
            capture_output=True, text=True, check=True,
        )
    except subprocess.CalledProcessError:
        return None
    return out.stdout.strip()


def frontmatter(path):
    text = path.read_text(encoding="utf-8", errors="replace")
    m = FRONT.match(text)
    if not m:
        return {}
    return {k: v for k, v in KEY.findall(m.group(1))}


def translations():
    """Every *.<lang>.md in the repo, sorted, build outputs excluded."""
    found = []
    for p in ROOT.rglob("*.md"):
        if any(part in SKIP_DIRS for part in p.relative_to(ROOT).parts):
            continue
        # foo.en.md / foo.ko.md -- the suffixes docs_tree declares, so this
        # walker and upgrade-translation-links.py agree on what a translation is
        if translation_suffix(p) is not None:
            found.append(p)
    return sorted(found)


def main():
    strict = "--strict" in sys.argv
    github = "--github" in sys.argv

    if git("rev-parse", "--is-inside-work-tree") is None:
        print("not a git repository -- nothing to compare against")
        return 0

    # A shallow clone has no history to compare against, so a source_commit that
    # is really there looks absent. That is the clone's fault, not the
    # documents', and it must not be indistinguishable from a real finding.
    shallow = git("rev-parse", "--is-shallow-repository") == "true"

    # Which is why every finding says whether depth could have produced it.
    # HISTORY findings are answers to a question about the commit graph, and a
    # truncated graph can get them wrong. DOCUMENT findings are about the file
    # in front of you -- no clone depth deletes front matter or moves a
    # canonical -- so they hold at any depth and are never excused.
    HISTORY, DOCUMENT = "history", "document"

    stale, broken, fresh = [], [], 0

    for path in translations():
        rel = path.relative_to(ROOT).as_posix()
        meta = frontmatter(path)
        canonical = meta.get("translated_from")
        commit = meta.get("source_commit")

        if not canonical or not commit:
            broken.append((rel, "no translated_from / source_commit front matter", DOCUMENT))
            continue

        canonical_path = ROOT / canonical
        if not canonical_path.exists():
            broken.append((rel, f"canonical does not exist: {canonical}", DOCUMENT))
            continue

        # An unknown commit means history was rewritten (squash, rebase, a
        # shallow clone). Say so rather than silently reporting "fresh".
        if git("cat-file", "-e", f"{commit}^{{commit}}") is None:
            broken.append((rel, f"source_commit {commit} is not in this history", HISTORY))
            continue

        # Reachable is not enough: a commit recorded on a squash-merged branch
        # exists in the repository but is not an ancestor of HEAD, and
        # `{commit}..HEAD` would then count every commit back to the merge
        # base as "behind" -- commits the translation was actually made from.
        # Route that to unresolvable instead of reporting false staleness.
        if git("merge-base", "--is-ancestor", commit, "HEAD") is None:
            broken.append((rel, f"source_commit {commit} is not an ancestor of HEAD "
                          "(recorded on an unmerged or squash-merged branch?)", HISTORY))
            continue

        behind = git("log", "--format=%h %s", f"{commit}..HEAD", "--", canonical)
        if behind is None:
            broken.append((rel, f"could not diff {commit}..HEAD for {canonical}", HISTORY))
            continue

        # A commit that edited the canonical *and* this translation is not
        # staleness -- the translator saw the change. This happens on every
        # normal update, because source_commit can only name a commit that
        # already exists, so it always trails the commit making the edit by
        # one. Filtering these keeps the report worth reading. One `git log`
        # over the translation's own path answers it for the whole range --
        # no per-commit subprocess, and no parsing of path lists that would
        # misread a path containing whitespace.
        translated_in = set(
            (git("log", "--format=%h", f"{commit}..HEAD", "--", rel) or "").splitlines())
        commits = []
        for line in behind.splitlines():
            if not line.strip():
                continue
            sha = line.split(None, 1)[0]
            if sha in translated_in:
                continue
            commits.append(line)

        if commits:
            stat = git("diff", "--shortstat", commit, "HEAD", "--", canonical) or ""
            stale.append((rel, canonical, commit, commits, stat))
        else:
            fresh += 1

    total = fresh + len(stale) + len(broken)
    print(f"checked {total} translation(s): {fresh} up to date, "
          f"{len(stale)} stale, {len(broken)} unresolvable")

    for rel, canonical, commit, commits, stat in stale:
        print()
        print(f"STALE  {rel}")
        print(f"       canonical {canonical} moved {len(commits)} commit(s) "
              f"since {commit}{(' -- ' + stat.strip()) if stat.strip() else ''}")
        for line in commits[:5]:
            print(f"         {line}")
        if len(commits) > 5:
            print(f"         ... and {len(commits) - 5} more")
        if github:
            print(f"::warning file={rel}::translation is behind {canonical} "
                  f"by {len(commits)} commit(s) since {commit}")

    # Split before printing: whether a finding is excused decides both what it
    # is annotated as and whether it fails the run, and those two must not be
    # able to disagree.
    excused = [b for b in broken if shallow and b[2] == HISTORY]
    fatal = [b for b in broken if not (shallow and b[2] == HISTORY)]

    for rel, why, kind in broken:
        print()
        print(f"UNRESOLVABLE  {rel}")
        print(f"              {why}")
        if github:
            # An error annotation, not a warning, because this fails the job --
            # unless the clone's depth is what produced it, which fails nothing.
            # It also puts the two findings in different display buckets, which
            # matters because GitHub truncates the list: 19 unresolvable would
            # otherwise have a stale one's warning to crowd out. None existed
            # when that happened here, so this is a mechanism, not a post-mortem.
            level = "warning" if (shallow and kind == HISTORY) else "error"
            print(f"::{level} file={rel}::{why}")

    if not stale and not broken:
        print("every translation is level with its canonical")

    if excused:
        print()
        print(f"{len(excused)} translation(s) unresolvable only because this is a "
              "shallow clone -- not counted as an error. Fetch the full history "
              "(git fetch --unshallow, or fetch-depth: 0 in Actions) to check them.")

    if fatal:
        print()
        print(f"{len(fatal)} translation(s) unresolvable: the check cannot say "
              "whether they are current. Fix the front matter -- see "
              "CONTRIBUTING.md, \"Translations\".")
        return 1

    if strict and stale:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
