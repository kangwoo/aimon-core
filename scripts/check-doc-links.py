#!/usr/bin/env python3
"""Checks every relative markdown link in the repository, target and anchor.

Two failures this catches, both of which have actually happened here:

  * a path that moved — a rename fixes the file and leaves every link to it dangling
  * an anchor that was never there — a plausible-looking `#section-name` invented
    from memory resolves to the top of the page, so the reader lands somewhere and
    never learns they were sent to the wrong place

External URLs are not checked. They fail for reasons that have nothing to do with
this commit (rate limits, a host that is down, a login wall), and a docs gate that
goes red on someone else's outage stops being read.

Code is not scanned: a link inside a fence or `backticks` is an example, not a link.

Usage: python3 scripts/check-doc-links.py [root]
"""

import pathlib
import re
import sys

from docs_tree import SKIP_DIRS, anchors_of, slug, unfence

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
EXTERNAL = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//|<)", re.IGNORECASE)
# A code span is delimited by a *run* of backticks, and the run length must match:
# ``[`ReadTool`](x)`` is one span, not an empty span followed by a link.
INLINE_CODE = re.compile(r"(`+)(?:(?!\1).)*\1")


def uncode(text):
    """unfence(), and also blank inline code — a link in backticks is an example."""
    return "\n".join(INLINE_CODE.sub("", line) for line in unfence(text).splitlines())


def main(root_arg="."):
    root = pathlib.Path(root_arg).resolve()
    files = [
        p
        for p in sorted(root.rglob("*.md"))
        if not (SKIP_DIRS & set(p.relative_to(root).parts))
    ]

    raw = {f: f.read_text(encoding="utf-8", errors="replace") for f in files}
    anchors = {f: anchors_of(t) for f, t in raw.items()}

    problems, checked = [], 0
    for f in files:
        body = uncode(raw[f])
        for m in LINK.finditer(body):
            target = m.group(1)
            if EXTERNAL.match(target):
                continue
            checked += 1
            path_part, _, fragment = target.partition("#")
            where = f"{f.relative_to(root)}:{body[: m.start()].count(chr(10)) + 1}"

            if path_part:
                dest = (f.parent / path_part).resolve()
                if not dest.exists():
                    problems.append(f"{where}  no such path      {target}")
                    continue
            else:
                dest = f

            if not fragment or dest.suffix != ".md" or dest not in anchors:
                continue
            if slug(fragment) not in anchors[dest]:
                problems.append(f"{where}  no such anchor    {target}")

    print(f"checked {len(files)} files, {checked} relative links")
    if problems:
        print(f"broken: {len(problems)}")
        for p in problems:
            print("  " + p)
        return 1
    print("broken: 0")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
