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

Which lines are headings is docs_tree.anchors_of's reading, and it is not the page's:
a heading inside an HTML comment block gets an anchor, and one behind indentation, `>`
or a list marker does not. That docstring names each shape, says how
check-backlog-registers.py reads the same shape, and why the two are left different.
`--self-test` pins this side of each of those differences, and of a raw HTML block,
so a change to the reading goes red here until the docstring says what is read now.

Usage:
    python3 scripts/check-doc-links.py [root]
    python3 scripts/check-doc-links.py --self-test
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


def resolves(fragment, anchors):
    """Whether `#fragment` names one of a page's `anchors` — what main() asks of every link."""
    return slug(fragment) in anchors


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
            if not resolves(fragment, anchors[dest]):
                problems.append(f"{where}  no such anchor    {target}")

    print(f"checked {len(files)} files, {checked} relative links")
    if problems:
        print(f"broken: {len(problems)}")
        for p in problems:
            print("  " + p)
        return 1
    print("broken: 0")
    return 0


# --- the self-test ----------------------------------------------------------
#
# One case for this side of each difference docs_tree.anchors_of's docstring
# names, and one for a raw HTML block. Each puts the shape on a page and asks
# what main() asks of a link to it: does `#target` resolve?
# The expected answer is the reading's, not the page's -- where the two differ
# is what the case is for. Nothing here reads the real tree, so a red case can
# only mean the reading changed.

FENCE3, FENCE4 = "`" * 3, "`" * 4
TARGET = "## Target"

SHAPES = [
    ("a heading inside an HTML comment block resolves, though the page shows no heading",
     ["<!--", TARGET, "-->"], True),
    ("a heading behind three spaces does not resolve, though the page shows one",
     ["   " + TARGET], False),
    ("a heading inside a blockquote does not resolve, though the page shows one",
     ["> " + TARGET], False),
    ("a heading on a list marker's line does not resolve, though the page shows one",
     ["- " + TARGET], False),
    ("a heading in a list item's continuation does not resolve, though the page shows one",
     ["- 목록 항목", "", "  " + TARGET], False),
    ("a heading after a `<!--` that unfence exposes inside a longer fence resolves, as the "
     "page shows it -- the backlog check's comment reading would hide it",
     [FENCE4 + "markdown", FENCE3, "<!--", FENCE3, FENCE4, "", TARGET], True),
    ("a heading inside a `<details>` block with no blank line resolves, though the page "
     "shows it as text",
     ["<details>", "<summary>요약</summary>", TARGET, "</details>"], True),
]


def self_test():
    print(f"self-test over {len(SHAPES)} heading shape(s) named in docs_tree.anchors_of")
    failed = 0
    for name, lines, expected in SHAPES:
        got = resolves("target", anchors_of("\n".join(["# Page", ""] + lines) + "\n"))
        failed += got != expected
        print(f"  {'ok  ' if got == expected else 'FAIL'} {name}")
        print(f"         `#target` {'resolves' if got else 'does not resolve'}")
    print()
    if failed:
        print(f"{failed} case(s) failed: docs_tree.anchors_of no longer reads a heading the way its "
              "docstring says. Say in that docstring what is read now and why, then change the "
              "expected answer here.")
        return 1
    print("every heading shape docs_tree.anchors_of names still reads the way its docstring says")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        sys.exit(self_test())
    sys.exit(main(*sys.argv[1:]))
