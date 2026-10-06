#!/usr/bin/env python3
"""Checks every relative markdown link in the repository, target and anchor.

Four failures this catches, all of which have actually happened here:

  * a path that moved — a rename fixes the file and leaves every link to it dangling
  * an anchor that was never there — a plausible-looking `#section-name` invented
    from memory resolves to the top of the page, so the reader lands somewhere and
    never learns they were sent to the wrong place
  * a link to a directory the site builds (backlog T-5) — github.com opens the tree
    view, but the site has no page for a directory: mkdocs logs `unrecognized
    relative link … left as is` at INFO, `mkdocs build --strict` stays green, and
    the built page links to a path with no `index.html`. 41 of them were in the
    tree when this rule was written
  * a fragment that only the canonical has (backlog T-10) — a page with no translation
    links `guide.md#한국어-제목`; on `/en/` the site shows that page from its Korean
    source and sends the link to `guide.en.md`, which has no such anchor. The link was
    right against the file it names, so the check above passed it. 6 of them were in
    the tree when this rule was written

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

WHICH FRAGMENTS MUST SURVIVE A TRANSLATION. A link `x.md#fragment` on a page the site
builds, when the site serves a reader of that page `x.<locale>.md` instead of `x.md`.
That reader is on one of two pages: a `*.<locale>.md` page (0 such links when this was
written — a translation links the translation), or a canonical with no `*.<locale>.md`
of its own, which the site shows on `/<locale>/` from its canonical source. A canonical
that has a translation is not asked: on `/<locale>/` its translation is shown instead,
and that file's links are its own.

Nobody is asked to write such a link differently — it is the right link on github.com
and on the canonical site. scripts/mkdocs_github_links.py carries the fragment over at
render time: it finds which heading of `x.md` the fragment names and writes the id of
the heading *at the same position* in `x.<locale>.md`. That works because
check-translation-structure.py holds a pair to the same headings at the same levels in
the same order. What this check asks is whether the hook has what it needs:

  * the fragment names a heading, and the two files' headings line up (same count,
    same levels in order): accepted. The hook carries it over
  * their headings do not line up, and the translation has no anchor of that very name:
    REPORTED, NOT FAILED. The hook does not guess, the link opens the top of the
    translated page, and the cause is one of two things somebody else already answers
    for — a translation that is behind its canonical, which fails nothing by decision
    (check-translation-staleness.py), or a current one with different headings, which
    check-translation-structure.py fails. This check cannot tell the two apart (the
    `docs-links` job runs on a shallow clone), so it fails neither
  * the fragment names a hand-written `<a id>` rather than a heading, and the translation
    has none of that name: FAILS. There is no position to carry over, no other check
    looks at `<a id>`, and the fix is to copy the `<a id>` — an id is an identifier and
    is not translated
  * the fragment names the second or later of several headings with the same text
    (`#설정-1`): FAILS. github.com numbers a repeat `-1` and the site `_1`, so the link
    is already dead on the canonical site and the hook, which matches the site's ids
    exactly, has nothing to match
  * the heading at that position in the translation has no anchor at all (its text is
    all punctuation or emoji): FAILS

The hook and this check read headings differently, and that is left so. This check reads
docs_tree.headings_of, with every difference from the page that anchors_of's docstring
names; the hook renders both files with the site's own Markdown configuration and
matches the site's real ids, because the id it writes has to be the one the browser
looks for, and docs_tree.slug is not that (measured on the built site when this was
written: of 144 pages, 40 have a heading whose site id is not docs_tree's anchor — `_`
kept inside code spans, `_1` for repeats, `<…>` dropped as a tag, a leading emoji
leaving `-` — and 7 more have a different number of headings). So a fragment this check accepts can still be one the
hook cannot carry: one that is not, character for character, the site's id for that
heading. Such a fragment is dead on the canonical site as well, before any translation
is involved, and this check has always accepted it (slug() normalises both sides).
MkDocs logs each one at INFO (`does not contain an anchor`); the hook's own
`--self-test` builds a small two-locale site and reads the built hrefs.

Which text is a link is read alike on both sides where it matters: the inline form
`[text](path.md#fragment)`, whose text may wrap onto the next line (this check always
read it so, and accepted as "the hook carries it" a link the hook, reading line by
line, did not carry; the hook reads the page whole now). Neither reads a
reference-style link, a `(<target>)` or a single-quoted title, so a fragment written
in one of those is not checked here and not carried there -- the hook's docstring
lists them. They differ on what a fence hides: this check toggles on every marker
(docs_tree.unfence) and the hook pairs markers as the site does. So this check reads
as a link a line in a fence shown inside a longer fence, which the site shows as text
and the hook leaves alone -- a sample that has to name a real target; and it does not
read anything after a marker nothing closes, which the site shows as a paragraph with
live links -- those the hook carries when it can, and nothing reports when it cannot.

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
small tree written to a temporary directory, never the real one. The translation
rule is pinned the same way again. Last, the run itself: main() on such trees, and
once the command line -- which exit code comes back, and which annotations `--github`
adds. The cases before these ask check() what it found, and none of them would notice
a run that found a broken link and exited 0.

`--github` also prints each reported-not-failed link as a workflow warning annotation,
since a line in a green job's log is a line nobody opens.

Usage:
    python3 scripts/check-doc-links.py [--github] [root]
    python3 scripts/check-doc-links.py --self-test
"""

import contextlib
import io
import pathlib
import re
import subprocess
import sys
import tempfile

from docs_tree import (
    SKIP_DIRS,
    TRANSLATION_SUFFIXES,
    anchors_of,
    headings_of,
    site_tree,
    slug,
    translation_suffix,
    unfence,
)

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


def served_instead(source, dest, site):
    """The translations of `dest` the site shows a reader of `source` in place of `dest`.

    Pairs are found the way the site finds them, by file name (`x.md` / `x.en.md`), not
    by `translated_from`. Empty when the link is only ever followed to `dest` itself.
    """
    if not site or not site.builds(source) or translation_suffix(dest):
        return []
    own = translation_suffix(source)
    served = []
    for locale in TRANSLATION_SUFFIXES:
        twin = dest.with_name(f"{dest.stem}.{locale}.md")
        if not twin.is_file() or not site.builds(twin):
            continue
        # `source` is read on /<locale>/ either as that locale's translation, or as a
        # canonical the site falls back to because it has no translation there.
        fallback = own is None and not source.with_name(f"{source.stem}.{locale}.md").is_file()
        if own == f".{locale}" or fallback:
            served.append(twin)
    return served


FAILS, REPORTED = "fails", "reported"


def carried_over(fragment, written, served):
    """Whether the site can carry `#fragment` from the page `written` to the page `served`.

    None when it can (or the fragment needs no carrying), else `(FAILS | REPORTED, why)`.
    The cases are this script's docstring › WHICH FRAGMENTS MUST SURVIVE A TRANSLATION.
    """
    here, there = headings_of(written), headings_of(served)
    want = slug(fragment)
    at = next((i for i, (_, anchor, _) in enumerate(here) if anchor == want), None)
    survives = resolves(fragment, anchors_of(served))

    if at is None:
        # Not a heading, so a hand-written <a id>: there is no position to carry over.
        return None if survives else (
            FAILS, "it is a hand-written `<a id>` that file does not have: copy the `<a id>`")
    if here[at][2]:
        return (FAILS, "it is a repeated heading, numbered `-N` on github.com and `_N` on the "
                       "site, so no fragment works on both: make the heading unique")
    if [level for level, _, _ in here] != [level for level, _, _ in there]:
        differ = (f"{len(there)} against {len(here)}" if len(there) != len(here)
                  else "same count, a level differs")
        return None if survives else (
            REPORTED, f"that file's headings do not line up with the canonical's ({differ}), so "
                      "the link opens the top of the page there until the translation catches up")
    if not there[at][1]:
        return (FAILS, f"heading {at + 1} of that file has no anchor")
    return None


def check(root):
    """Every finding under `root`, as `(files, links checked, problem lines, reported lines)`.

    A problem fails the run; a reported line does not (WHICH FRAGMENTS MUST SURVIVE A
    TRANSLATION says which is which, and why).
    """
    site = site_tree(root)
    files = [
        p
        for p in sorted(root.rglob("*.md"))
        if not (SKIP_DIRS & set(p.relative_to(root).parts))
    ]

    raw = {f: f.read_text(encoding="utf-8", errors="replace") for f in files}
    anchors = {f: anchors_of(t) for f, t in raw.items()}

    problems, reported, checked = [], [], 0
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
                continue
            for twin in served_instead(f, dest, site):
                verdict = carried_over(fragment, raw[dest], raw[twin])
                if verdict:
                    severity, why = verdict
                    (problems if severity == FAILS else reported).append(
                        f"{where}  not carried over  {target}  (the site serves "
                        f"{twin.relative_to(root)} for this link: {why})")

    return files, checked, problems, reported


def main(root_arg=".", github=False):
    root = pathlib.Path(root_arg).resolve()
    try:
        files, checked, problems, reported = check(root)
    except ValueError as unreadable:
        # docs_tree.site_tree() refusing an exclude_docs it cannot match exactly.
        print(f"cannot tell which directories the site builds: {unreadable}")
        return 2

    print(f"checked {len(files)} files, {checked} relative links")
    if reported:
        print(f"reported, not failed: {len(reported)}")
        for r in reported:
            print("  " + r)
            if github:
                where, _, rest = r.partition("  ")
                path, _, line = where.rpartition(":")
                print(f"::warning file={path},line={line}::{' '.join(rest.split())}")
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
            _, _, problems, _ = check(root)
        if advice is None:
            ok = not problems
        else:
            ok = (len(problems) == 1 and "  built directory   " in problems[0]
                  and problems[0].endswith(f"directory: {advice})"))
        report(name, ok, problems[0] if problems else "accepted")

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp).resolve()
        site_fixture(root, None, "docs/overview.md", "features/")
        _, _, problems, _ = check(root)
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


# --- the translation rule's self-test ----------------------------------------
#
# One link per case again. Each case names the page the link is on, the canonical
# it points into, that canonical's translation (None for "there is none"), the
# fragment, and what must come of it: None (accepted, nothing reported), or FAILS /
# REPORTED with a phrase the line must carry. The canonical is `docs/guide.md`.

KO = ["# 가이드", "", "## 설정", "", "## 실행", ""]
EN = ["# Guide", "", "## Settings", "", "## Running", ""]
A_ID = ['<a id="옛-이름"></a>', ""]
UNTRANSLATED = "docs/design.md"

PAIR_CASES = [
    ("a page with no translation linking a Korean heading of a translated page is accepted "
     "when the pair's headings line up -- the hook carries it over",
     UNTRANSLATED, KO, EN, "guide.md#실행", None),
    ("the same link is reported, not failed, when the translation has fewer headings",
     UNTRANSLATED, KO, EN[:4], "guide.md#실행", (REPORTED, "do not line up")),
    ("the same link is reported, not failed, when the count matches and a level does not",
     UNTRANSLATED, KO, EN[:4] + ["### Running", ""], "guide.md#실행", (REPORTED, "do not line up")),
    ("a fragment the translation has under the same name is accepted though the headings do "
     "not line up -- the hook leaves it and it resolves",
     UNTRANSLATED, KO + ["## API", ""], EN[:2] + ["## API", ""], "guide.md#api", None),
    ("a hand-written `<a id>` only the canonical has fails",
     UNTRANSLATED, KO + A_ID, EN, "guide.md#옛-이름", (FAILS, "copy the `<a id>`")),
    ("a hand-written `<a id>` both files have is accepted",
     UNTRANSLATED, KO + A_ID, EN + A_ID, "guide.md#옛-이름", None),
    ("the second of two headings with the same text fails -- github.com and the site number "
     "it differently",
     UNTRANSLATED, KO + ["## 설정", ""], EN + ["## Settings", ""], "guide.md#설정-1",
     (FAILS, "repeated heading")),
    ("the first of two headings with the same text is accepted",
     UNTRANSLATED, KO + ["## 설정", ""], EN + ["## Settings", ""], "guide.md#설정", None),
    ("a heading whose twin has no anchor fails",
     UNTRANSLATED, KO, EN[:4] + ["## →", ""], "guide.md#실행", (FAILS, "has no anchor")),
    ("a `*.en.md` page linking the canonical's Korean heading is accepted when the headings "
     "line up -- the reverse direction is the same rule",
     "docs/other.en.md", KO, EN, "guide.md#실행", None),
    ("a `*.en.md` page linking it is reported when they do not",
     "docs/other.en.md", KO, EN[:4], "guide.md#실행", (REPORTED, "do not line up")),
    ("a canonical that has its own translation is not asked -- /en/ shows that translation",
     "docs/README.md", KO, EN[:4], "guide.md#실행", None),
    ("a page `exclude_docs` keeps off the site is not asked -- github.com only",
     "docs/backlog/open-items.md", KO, EN[:4], "../guide.md#실행", None),
    ("a link into a page with no translation is not asked",
     UNTRANSLATED, KO, None, "guide.md#실행", None),
    ("a fragment the canonical does not have is still `no such anchor`, whatever the "
     "translation has",
     UNTRANSLATED, KO, EN + ["## 없는-제목", ""], "guide.md#없는-제목", (FAILS, "no such anchor")),
]


def pair_self_test():
    print(f"self-test over {len(PAIR_CASES)} link(s) into a page that has, or lacks, a translation")
    failed = 0
    for name, page, canonical, translation, link, expected in PAIR_CASES:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp).resolve()
            site_fixture(root, SITE_MKDOCS, page, link)
            (root / "docs/guide.md").write_text("\n".join(canonical), encoding="utf-8")
            if translation is not None:
                (root / "docs/guide.en.md").write_text("\n".join(translation), encoding="utf-8")
            _, _, problems, reported = check(root)
        if expected is None:
            ok = not problems and not reported
        else:
            severity, phrase = expected
            hit, miss = (problems, reported) if severity == FAILS else (reported, problems)
            kind = "  no such anchor    " if phrase == "no such anchor" else "  not carried over  "
            ok = len(hit) == 1 and not miss and kind in hit[0] and phrase in hit[0]
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
        for label, lines in (("fails", problems), ("reported", reported)):
            for line in lines:
                print(f"         {label}: {line}")
        if not problems and not reported:
            print("         accepted")
    print()
    if failed:
        print(f"{failed} case(s) failed: the translation rule no longer draws the boundary this "
              "script's docstring says (WHICH FRAGMENTS MUST SURVIVE A TRANSLATION). Say there "
              "what fails or is reported now and why, then change the expected answer here.")
        return 1
    print("every link above is still accepted, reported or failed as this script's docstring says")
    return 0


# --- the run's self-test -----------------------------------------------------
#
# What a caller of this script gets: an exit code, and under `--github` one workflow
# annotation per reported-not-failed link. Each case is a tree, whether `--github` was
# asked for, and the two answers. The trees are made of the two findings above that
# differ in severity -- a link to a file that is not there (fails), and a fragment into
# a translation whose headings are behind (reported).

BROKEN_PAGE, BROKEN_LINE = "docs/broken.md", 5
REPORTED_PAGE, REPORTED_LINE = "docs/design.md", 4
ANNOTATION = (f"::warning file={REPORTED_PAGE},line={REPORTED_LINE}::"
              "not carried over guide.md#실행 (the site serves docs/guide.en.md for this link: ")
UNREADABLE_MKDOCS = "docs_dir: docs\n" + UNREADABLE_EXCLUDES[0][1]

# (case, the tree's findings, --github, exit code, annotations)
MAIN_CASES = [
    ("a broken link exits 1",
     {"broken"}, False, 1, 0),
    ("a reported link alone exits 0 -- it is reported, not failed",
     {"reported"}, False, 0, 0),
    ("`--github` adds one annotation for the reported link, naming its file and line",
     {"reported"}, True, 0, 1),
    ("with a broken link beside it the run exits 1, and the annotation is still only "
     "the reported link's",
     {"broken", "reported"}, True, 1, 1),
    ("without `--github` the same tree exits 1 and prints no annotation",
     {"broken", "reported"}, False, 1, 0),
    ("a tree with neither exits 0 and prints no annotation",
     set(), True, 0, 0),
    ("an `exclude_docs` this cannot read exits 2, not 0",
     {"unreadable"}, True, 2, 0),
]


def main_fixture(root, findings):
    site_fixture(root, UNREADABLE_MKDOCS if "unreadable" in findings else SITE_MKDOCS)
    (root / "docs/guide.md").write_text("\n".join(KO), encoding="utf-8")
    (root / "docs/guide.en.md").write_text(
        "\n".join(EN[:4] if "reported" in findings else EN), encoding="utf-8")
    # The link sits on a line of its own number in each page, so a run that named the
    # wrong line -- or the other page's -- does not pass by coincidence.
    lead = "\n" * (REPORTED_LINE - 2)
    (root / REPORTED_PAGE).write_text(f"# Page\n{lead}[link](guide.md#실행)\n", encoding="utf-8")
    if "broken" in findings:
        lead = "\n" * (BROKEN_LINE - 2)
        (root / BROKEN_PAGE).write_text(f"# Page\n{lead}[link](missing.md)\n", encoding="utf-8")


def main_self_test():
    print(f"self-test over {len(MAIN_CASES) + 1} run(s): the exit code, and what `--github` adds")
    failed = 0

    def report(name, findings, expected, code, printed):
        nonlocal failed
        annotations = [line for line in printed.splitlines() if line.startswith("::")]
        wanted_code, wanted_annotations = expected
        ok = (code == wanted_code and len(annotations) == wanted_annotations
              and all(line.startswith(ANNOTATION) for line in annotations)
              and ("broken: 1" in printed) == ("broken" in findings)
              and ("reported, not failed: 1" in printed) == ("reported" in findings))
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
        print(f"         exit {code}, {len(annotations)} annotation(s)")
        for line in annotations:
            # Not at the start of the line: this output is a workflow log too, and the
            # runner would take the fixture's annotation for one of the job's own.
            print(f"         printed: {line}")

    for name, findings, github, code, annotations in MAIN_CASES:
        printed = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp).resolve()
            main_fixture(root, findings)
            with contextlib.redirect_stdout(printed):
                got = main(str(root), github=github)
        report(name, findings, (code, annotations), got, printed.getvalue())

    # main() was handed `github` above. Whether `--github` on the command line reaches
    # it, and whether its return value becomes the exit status, is the last thing.
    findings = {"broken", "reported"}
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp).resolve()
        main_fixture(root, findings)
        ran = subprocess.run([sys.executable, __file__, "--github", str(root)],
                             capture_output=True, text=True, encoding="utf-8")
    report("run as a command with `--github`, that tree exits 1 with its one annotation",
           findings, (1, 1), ran.returncode, ran.stdout)

    print()
    if failed:
        print(f"{failed} case(s) failed: a run of this script no longer exits or annotates as "
              "its docstring says (the usage, and `--github`). The `docs-links` job reads "
              "nothing else: say there what a run does now, then change the expected answer here.")
        return 1
    print("every run above still exits and annotates as this script's docstring says")
    return 0


def self_test():
    return max(heading_self_test(), site_self_test(), pair_self_test(), main_self_test())


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--self-test" in args:
        sys.exit(self_test())
    sys.exit(main(*[a for a in args if a != "--github"], github="--github" in args))
