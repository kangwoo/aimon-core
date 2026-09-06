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

--strict covers staleness and nothing else. It used to fail on any finding,
which meant a clean shallow clone -- where the whole report is the clone's depth
talking -- failed under it; now the excused findings below are excused there
too, and --strict on that clone exits 0 like the plain run. Excusing a finding
under one flag and not the other would make the flag, rather than the finding,
decide whether depth counts as a defect.

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
HEAD, so those two findings -- and only those two -- are reported and not
counted; CI passes fetch-depth: 0 for this reason. Everything else fails at any
depth, the catch-all "could not diff" included: depth cannot delete front
matter, move a canonical, or point translated_from outside the repository. An
exemption wider than the reason for it is the same green-that-means-nothing this
file exists to remove, just in a narrower window.

The direction is not assumed. `translated_from` names the canonical whichever
language it is in, so this handles both docs/**/*.en.md (Korean canonical) and
CONTRIBUTING.ko.md (English canonical) with the same code.

Usage:
    python3 scripts/check-translation-staleness.py [--strict] [--github]
"""
import sys

from docs_tree import (KIND_HISTORY, ROOT, STALE, frontmatter, git,
                       is_shallow_clone, pair_state, translations)


def main():
    strict = "--strict" in sys.argv
    github = "--github" in sys.argv

    if git("rev-parse", "--is-inside-work-tree") is None:
        print("not a git repository -- nothing to compare against")
        return 0

    # A shallow clone has no history to compare against, so a source_commit that
    # is really there looks absent. That is the clone's fault, not the
    # documents', and it must not be indistinguishable from a real finding.
    #
    # Asked once, here, rather than inside the per-pair verdict: it is a
    # property of the clone and not of any pair.
    shallow = is_shallow_clone()

    # Which is why every finding says whether depth could have produced it.
    # HISTORY findings are answers to a question about the commit graph, and a
    # truncated graph can get them wrong. DOCUMENT findings are about the file
    # in front of you -- no clone depth deletes front matter or moves a
    # canonical -- so they hold at any depth and are never excused.
    # docs_tree.pair_state() makes that split, so this report and
    # check-translation-structure.py cannot disagree about which findings a
    # shallow clone excuses.
    stale, broken, fresh = [], [], 0

    for path in translations():
        rel = path.relative_to(ROOT).as_posix()
        meta = frontmatter(path)
        verdict = pair_state(path, meta)

        if verdict.unresolvable:
            broken.append((rel, verdict.why, verdict.kind))
        elif verdict.state == STALE:
            canonical = meta.get("translated_from")
            commit = meta.get("source_commit")
            stat = git("diff", "--shortstat", commit, "HEAD", "--", canonical) or ""
            stale.append((rel, canonical, commit, verdict.commits, stat))
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
    excused = [b for b in broken if shallow and b[2] == KIND_HISTORY]
    fatal = [b for b in broken if not (shallow and b[2] == KIND_HISTORY)]

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
            level = "warning" if (shallow and kind == KIND_HISTORY) else "error"
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
