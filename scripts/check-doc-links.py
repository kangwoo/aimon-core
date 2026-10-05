#!/usr/bin/env python3
"""Checks every relative markdown link in the repository, target and anchor.

Three failures this catches, all of which have actually happened here:

  * a path that moved — a rename fixes the file and leaves every link to it dangling
  * an anchor that was never there — a plausible-looking `#section-name` invented
    from memory resolves to the top of the page, so the reader lands somewhere and
    never learns they were sent to the wrong place
  * a link to a directory the site builds (backlog T-5) — github.com opens the tree
    view, but the site has no page for a directory: mkdocs logs `unrecognized
    relative link … left as is` at INFO, `mkdocs build --strict` stays green, and
    the built page links to a path with no `index.html`. 41 of them were in the
    tree when this rule was written

WHICH DIRECTORY LINKS FAIL. One whose *source* and *target* are both built by the
site — `docs_tree.site_tree()`'s reading of mkdocs.yml: inside `docs_dir`, and not
under a directory `exclude_docs` names. Every other directory link is still accepted,
because something opens it on every surface it is read on:

  * the target is outside `docs_dir` (`../../modules/aimon-core/`) or under an
    excluded directory (`../backlog/`): scripts/mkdocs_github_links.py rewrites it to
    a GitHub tree URL at render time
  * the source is not a site page (the repository-root README, `.claude/**`,
    `docs/backlog/**`): it is only ever read on github.com, where the tree view opens

The message says what to write instead: the directory's `README.md` — `README.en.md`
from a `*.en.md` page when that translation exists, the way every other link in a
translation is written — or, when the directory has no README, a page inside it. A
README is not suggested into existence: whether a directory gets an index page is the
author's call, and the failure is loud either way.

The hook and this check must agree on what "built" means, and they read it through
different parsers (the hook is handed mkdocs' pathspec; this cannot import one). The
hook compares the two readings over every directory under `docs_dir` on each site
build — see docs_tree.py › "what the site builds".

External URLs are not checked. They fail for reasons that have nothing to do with
this commit (rate limits, a host that is down, a login wall), and a docs gate that
goes red on someone else's outage stops being read.

Code is not scanned: a link inside a fence or `backticks` is an example, not a link.

Which lines are headings is docs_tree.anchors_of's reading, and it is not the page's:
a heading inside an HTML comment block gets an anchor, and one behind indentation, `>`
or a list marker does not. That docstring names each shape, says how
check-backlog-registers.py reads the same shape, and why the two are left different.
`--self-test` has cases for this side of those differences, for a raw HTML block, for
ways unfence() pairs fence markers and for a `#` comment in YAML front matter,
which is not read, one page per case, so a change that
alters one of those cases' answers goes red there until that case's expected answer
changes too. It then pins the directory rule the same way: one link per case, in a
small tree written to a temporary directory, never the real one.

Usage:
    python3 scripts/check-doc-links.py [root]
    python3 scripts/check-doc-links.py --self-test
"""

import pathlib
import re
import sys
import tempfile

from docs_tree import SKIP_DIRS, anchors_of, site_tree, slug, translation_suffix, unfence

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


def write_instead(source, dest, path_part):
    """What a link from `source` to the built directory `dest` should say instead.

    The page a directory has is its README (mkdocs builds `README.md` as the
    directory's index). A translation links the translated README when there is
    one and the canonical when there is not, as its other links do.
    """
    suffix = translation_suffix(source)
    names = ([f"README{suffix}.md"] if suffix else []) + ["README.md"]
    for name in names:
        if (dest / name).is_file():
            return f"write {path_part.rstrip('/')}/{name}"
    return "it has no README.md, so link a page inside it or drop the link"


def check(root):
    """Every problem under `root`, as `(files, links checked, problem lines)`."""
    site = site_tree(root)
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
                if dest.is_dir() and site and site.builds(f) and site.builds(dest):
                    problems.append(
                        f"{where}  built directory   {target}  (the site has no page for a "
                        f"directory: {write_instead(f, dest, path_part)})")
                    continue
            else:
                dest = f

            if not fragment or dest.suffix != ".md" or dest not in anchors:
                continue
            if not resolves(fragment, anchors[dest]):
                problems.append(f"{where}  no such anchor    {target}")

    return files, checked, problems


def main(root_arg="."):
    root = pathlib.Path(root_arg).resolve()
    try:
        files, checked, problems = check(root)
    except ValueError as unreadable:
        # docs_tree.site_tree() refusing an exclude_docs it cannot match exactly.
        print(f"cannot tell which directories the site builds: {unreadable}")
        return 2

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
# Cases for these heading shapes, one page each: this side of each difference
# from the backlog check that docs_tree.anchors_of's docstring names, a raw HTML
# block, two ways unfence() pairs fence markers that hide a heading the page
# shows, and two ways it closes a fence -- on a marker of the other character,
# or one with an info string. Each asks what main() asks of a link to the
# heading: does `#target` resolve? The expected answer is the reading's, which is not always the page's.
# Nothing here reads the real tree, so a red case can only mean the reading
# changed.

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
    ("a heading after the closer of a fence opened on a `- ` marker's line does not resolve, "
     "though the page shows one",
     ["- " + FENCE3 + "bash", "  한 줄", "  " + FENCE3, "", TARGET], False),
    ("a heading between two backtick fence markers indented four spaces does not resolve, "
     "though the page shows one",
     ["문단", "", "    " + FENCE3, "", TARGET, "", "    " + FENCE3], False),
    # Backlog T-9: the shapes its one-line changes to docs_tree.FENCE move here, and one
    # for a reading it named without trying (an info string on the closing marker).
    ("a heading after a `~~~` marker that closes a backtick fence resolves -- the next "
     "marker closes a fence whatever its character",
     [FENCE3, "~~~", "", TARGET], True),
    ("a heading after the closer of a fence opened on a `1. ` marker's line does not resolve, "
     "though the page shows one",
     ["1. " + FENCE3 + "bash", "   한 줄", "   " + FENCE3, "", TARGET], False),
    ("a heading after the closer of a fence opened on a `* ` marker's line does not resolve, "
     "though the page shows one",
     ["* " + FENCE3 + "bash", "  한 줄", "  " + FENCE3, "", TARGET], False),
    ("a heading after the closer of a fence opened on a `+ ` marker's line does not resolve, "
     "though the page shows one",
     ["+ " + FENCE3 + "bash", "  한 줄", "  " + FENCE3, "", TARGET], False),
    ("a heading between two `~~~` fence markers indented four spaces does not resolve, "
     "though the page shows one",
     ["문단", "", "    ~~~", "", TARGET, "", "    ~~~"], False),
    ("a heading after a marker with an info string that closes a fence resolves -- the next "
     "marker closes a fence whatever its info string",
     [FENCE3, FENCE3 + "bash", "", TARGET], True),
]

# Shapes that exist only at the top of a file, so the page is these lines with `# Page`
# after them rather than before. YAML front matter is the one: the site drops it, and
# github.com renders it as a table, so a `#` comment inside it is no heading on either
# (backlog T-6).
TOP_SHAPES = [
    ("a `#` comment inside YAML front matter does not resolve, as the page shows no heading",
     ["---", "name: explore", "# Target", "tools: Read", "---"], False),
]


def heading_self_test():
    cases = [(name, ["# Page", ""] + lines, expected) for name, lines, expected in SHAPES]
    cases += [(name, lines + ["", "# Page"], expected) for name, lines, expected in TOP_SHAPES]
    print(f"self-test over {len(cases)} heading shape(s) named in docs_tree.anchors_of")
    failed = 0
    for name, page, expected in cases:
        got = resolves("target", anchors_of("\n".join(page) + "\n"))
        failed += got != expected
        print(f"  {'ok  ' if got == expected else 'FAIL'} {name}")
        print(f"         `#target` {'resolves' if got else 'does not resolve'}")
    print()
    if failed:
        print(f"{failed} case(s) failed: docs_tree.anchors_of no longer reads a heading the way its "
              "docstring says. Say in that docstring what is read now and why, then change the "
              "expected answer here.")
        return 1
    print("every heading shape above still reads the way docs_tree.anchors_of's docstring says")
    print()
    return 0


# --- the directory rule's self-test ------------------------------------------
#
# One link per case, in a tree written to a temporary directory. Each case names
# the page the link is on, the link, and the advice the failure must carry -- or
# None when the link must be accepted. The accepted cases are the boundary: they
# are the directory links something else opens (the hook, or github.com), and a
# rule that grew to fail them would send people to "fix" links that work.

SITE_MKDOCS = "docs_dir: docs\nexclude_docs: |\n  /backlog/\n  /plan/\n"
SITE_FILES = [
    "README.md",
    "modules/core/Tool.java",
    "docs/README.md",
    "docs/README.en.md",
    "docs/features/README.md",
    "docs/features/README.en.md",
    "docs/features/tool/guide.md",
    "docs/design/README.md",
    "docs/design/backlog/deferred.md",
    "docs/backlog/open-items.md",
    "docs/plan/work.md",
]
README_EN = "write {}/README.en.md"
README = "write {}/README.md"
NO_README = "it has no README.md, so link a page inside it or drop the link"

SITE_CASES = [
    ("a site page linking a built directory that has a README fails",
     "docs/overview.md", "features/", README.format("features")),
    ("the same link with no trailing slash fails -- it is the directory either way",
     "docs/overview.md", "features", README.format("features")),
    ("the same link with a fragment fails, and the advice drops the fragment",
     "docs/overview.md", "features/#tool", README.format("features")),
    ("a `*.en.md` page is told to write the translated README when there is one",
     "docs/overview.en.md", "features/", README_EN.format("features")),
    ("a `*.en.md` page is told to write the canonical README when there is no translation",
     "docs/overview.en.md", "design/", README.format("design")),
    ("a built directory with no README fails, and no README is suggested",
     "docs/features/README.md", "tool/", NO_README),
    ("`design/backlog/` fails: `/backlog/` is anchored and excludes only the top-level one",
     "docs/design/README.md", "backlog/", NO_README),
    ("a link to `docs_dir` itself fails",
     "docs/features/README.md", "../", README.format("..")),
    ("a directory `exclude_docs` names is accepted -- the hook rewrites it",
     "docs/design/README.md", "../backlog/", None),
    ("a directory outside `docs_dir` is accepted -- the hook rewrites it",
     "docs/features/tool/guide.md", "../../../modules/core/", None),
    ("a page `exclude_docs` keeps off the site may link a built directory -- github.com only",
     "docs/backlog/open-items.md", "../features/", None),
    ("a page outside `docs_dir` may link a built directory -- github.com only",
     "README.md", "docs/features/", None),
    ("a link to a README is accepted",
     "docs/overview.md", "features/README.md", None),
]

# exclude_docs shapes site_tree() must refuse rather than half-read: each one
# excludes something an anchored-directory match would get wrong.
UNREADABLE_EXCLUDES = [
    ("a bare `backlog/`, which matches at any depth", "exclude_docs: |\n  backlog/\n"),
    ("a wildcard", "exclude_docs: |\n  /dra*/\n"),
    ("a negation", "exclude_docs: |\n  /backlog/\n  !/backlog/keep/\n"),
    ("a file pattern", "exclude_docs: |\n  /notes.md\n"),
    ("a form other than a `|` block", "exclude_docs: /backlog/\n"),
]


def site_fixture(root, mkdocs, page=None, link=None):
    if mkdocs is not None:
        (root / "mkdocs.yml").write_text(mkdocs, encoding="utf-8")
    for name in SITE_FILES + ([page] if page else []):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# Page\n", encoding="utf-8")
    if page:
        (root / page).write_text(f"# Page\n\n[link]({link})\n", encoding="utf-8")


def site_self_test():
    print(f"self-test over {len(SITE_CASES)} directory link(s) and "
          f"{len(UNREADABLE_EXCLUDES) + 1} mkdocs.yml shape(s)")
    failed = 0

    def report(name, ok, got):
        nonlocal failed
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
        print(f"         {got}")

    for name, page, link, advice in SITE_CASES:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp).resolve()
            site_fixture(root, SITE_MKDOCS, page, link)
            _, _, problems = check(root)
        if advice is None:
            ok = not problems
        else:
            ok = (len(problems) == 1 and "  built directory   " in problems[0]
                  and problems[0].endswith(f"directory: {advice})"))
        report(name, ok, problems[0] if problems else "accepted")

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp).resolve()
        site_fixture(root, None, "docs/overview.md", "features/")
        _, _, problems = check(root)
    report("with no mkdocs.yml there is no site, and no directory link fails",
           not problems, problems[0] if problems else "accepted")

    for name, exclude_docs in UNREADABLE_EXCLUDES:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp).resolve()
            site_fixture(root, "docs_dir: docs\n" + exclude_docs)
            try:
                check(root)
                got = None
            except ValueError as refused:
                got = str(refused)
        report(f"exclude_docs with {name} is refused, not half-read",
               got is not None, got or "read without complaint")

    print()
    if failed:
        print(f"{failed} case(s) failed: the directory rule no longer draws the boundary this "
              "script's docstring says (WHICH DIRECTORY LINKS FAIL). Say there what fails now "
              "and why, then change the expected answer here.")
        return 1
    print("every directory link above is still failed or accepted as this script's docstring says")
    return 0


def self_test():
    return max(heading_self_test(), site_self_test())


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        sys.exit(self_test())
    sys.exit(main(*sys.argv[1:]))
