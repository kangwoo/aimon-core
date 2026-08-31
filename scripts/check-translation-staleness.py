#!/usr/bin/env python3
"""Report translations that have fallen behind their canonical document.

Every translated file carries the commit of the canonical it was translated
from:

    ---
    translated_from: docs/features/tool/tool-development-guide.md
    source_commit: c976edc7
    ---

This script compares that commit against the canonical's current history and
lists the ones that have moved on. It is deliberately a *report*, not a gate:
it exits 0 even when translations are stale, because a translation backlog that
blocks edits to the canonical makes the canonical go stale instead -- the worse
of the two failure modes. Pass --strict to make staleness an error anyway
(useful before cutting a release).

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

    stale, broken, fresh = [], [], 0

    for path in translations():
        rel = path.relative_to(ROOT).as_posix()
        meta = frontmatter(path)
        canonical = meta.get("translated_from")
        commit = meta.get("source_commit")

        if not canonical or not commit:
            broken.append((rel, "no translated_from / source_commit front matter"))
            continue

        canonical_path = ROOT / canonical
        if not canonical_path.exists():
            broken.append((rel, f"canonical does not exist: {canonical}"))
            continue

        # An unknown commit means history was rewritten (squash, rebase, a
        # shallow clone). Say so rather than silently reporting "fresh".
        if git("cat-file", "-e", f"{commit}^{{commit}}") is None:
            broken.append((rel, f"source_commit {commit} is not in this history"))
            continue

        # Reachable is not enough: a commit recorded on a squash-merged branch
        # exists in the repository but is not an ancestor of HEAD, and
        # `{commit}..HEAD` would then count every commit back to the merge
        # base as "behind" -- commits the translation was actually made from.
        # Route that to unresolvable instead of reporting false staleness.
        if git("merge-base", "--is-ancestor", commit, "HEAD") is None:
            broken.append((rel, f"source_commit {commit} is not an ancestor of HEAD "
                                "(recorded on an unmerged or squash-merged branch?)"))
            continue

        behind = git("log", "--format=%h %s", f"{commit}..HEAD", "--", canonical)
        if behind is None:
            broken.append((rel, f"could not diff {commit}..HEAD for {canonical}"))
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

    for rel, why in broken:
        print()
        print(f"UNRESOLVABLE  {rel}")
        print(f"              {why}")
        if github:
            print(f"::warning file={rel}::{why}")

    if not stale and not broken:
        print("every translation is level with its canonical")

    if strict and (stale or broken):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
