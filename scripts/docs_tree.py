"""What every docs script must agree on, defined once.

Four scripts walk the same markdown tree -- check-doc-links.py,
check-translation-staleness.py, check-translation-structure.py and
upgrade-translation-links.py -- and they need the same answers: which
directories are not ours to scan, which files are translations, how a heading
turns into an anchor, which canonical a translation declares, and whether that
pair is current. check-backlog-registers.py walks nothing -- it reads
docs/backlog/ -- but borrows three of this module's answers (what a heading is,
what a fence hides, which file is a translation), so that it and the link checker
start from the same heading lines. They do not end on the same ones: the backlog
check also skips HTML comment blocks and reports headings behind indentation, `>`
or a list marker, and where that makes the two read a heading differently, with
why each place is left, is written down at `anchors_of`. Each script carrying its
own copy is how the copies drift (`.venv` was in one skip set and missing from the
other two), so the answers live here and the scripts import them.

The last two answers are why this module runs git. That is a real cost -- it
used to be pure text -- and it is paid for one reason: two checks now ask "is
this pair current", and computing that twice independently is exactly the drift
above, except the divergence would be over which findings a shallow clone
excuses. check-translation-staleness.py spends four paragraphs of its header on
getting that right; a second, separately written copy of it would not.

A sixth answer sits beside the front-matter reading: whether a file's front matter
is written in the subset that reading and mkdocs' YAML parser read the same
(`front_matter_defects`). check-translation-structure.py asks it of every
translation and every other page the site builds.

One answer is asked from outside the tree walk: which directories the site
builds (`site_tree`, at the end). check-doc-links.py needs it to fail a link to a
directory the site builds, and scripts/mkdocs_github_links.py -- the MkDocs hook,
which holds MkDocs' own parse of the same setting -- compares the two on every
site build.
"""

import pathlib
import re
import subprocess
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Trees that contain markdown nobody here wrote: build outputs, vendored
# dependencies, and the virtualenv that installs docs-requirements.txt.
SKIP_DIRS = {".git", ".gradle", ".venv", "build", "node_modules", "site"}

# The locales the site builds (mkdocs.yml declares the same pair). A file is a
# translation exactly when its name ends `.<one of these>.md`; adding a locale
# here is what makes all four scripts see it.
TRANSLATION_SUFFIXES = ("en", "ko")

FENCE = re.compile(r"^\s*(?:```|~~~)")
ATX_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
HTML_ANCHOR = re.compile(r"<a\s[^>]*(?:name|id)\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE)


def translation_suffix(path):
    """`.en` for docs/foo.en.md, `.ko` for CONTRIBUTING.ko.md, None otherwise."""
    parts = path.name.split(".")
    if len(parts) >= 3 and parts[-1] == "md" and parts[-2] in TRANSLATION_SUFFIXES:
        return "." + parts[-2]
    return None


def unfence(text):
    """Blank out fenced blocks, preserving line numbering."""
    out, fenced = [], False
    for line in text.splitlines():
        if FENCE.match(line):
            fenced = not fenced
            out.append("")
        elif fenced:
            out.append("")
        else:
            out.append(line)
    return "\n".join(out)


def slug(heading):
    """GitHub's heading-to-anchor rule, as far as this repository needs it.

    Markdown markup is stripped first (`**bold**`, `` `code` ``, links keep their
    text), then everything that is not a word character, a space, or a hyphen is
    dropped, spaces become hyphens, and the result is lowercased. Non-ASCII letters
    survive -- most headings here are Korean.
    """
    text = re.sub(r"`([^`]*)`", r"\1", heading)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"[*_~]", "", text)
    text = unicodedata.normalize("NFC", text)
    text = "".join(c for c in text.lower() if c.isalnum() or c in " -_")
    return text.strip().replace(" ", "-")


def anchors_of(text):
    """Every fragment that resolves inside one markdown file.

    A heading is an ATX_HEADING line at the start of a line in unfence()'s
    output, read after the YAML front matter at the top of the file (FRONT_MATTER,
    the block check-translation-structure.py strips too) is blanked; a hand-written
    anchor is an `<a name|id>` anywhere in the raw text.

    WHERE THE LINK CHECK AND THE BACKLOG CHECK READ HEADINGS DIFFERENTLY.
    check-backlog-registers.py starts from the same lines and does two things
    this does not: it blanks HTML comment blocks (its `uncomment`), and it
    reports a heading behind indentation, `>` or a list marker whose text it
    would read at the start of a line. Three shapes come out differently. "The
    page" is what cmark-gfm 0.29.0.gfm.13 renders, from cmarkgfm 2025.10.22 run
    locally; github.com was not observed.

    * Inside an HTML comment block. The page shows no heading and the backlog
      check reads none, but this anchors it, so a link into one passes and
      lands at the top of the page.
    * Behind one to three spaces, `>` or a list marker, or in a list item's
      continuation (two spaces under `- `). The page shows a heading, but this
      gives no anchor, so a correct link to one fails; the backlog check
      reports it as unread-heading.
    * After a `<!--` on a line unfence() exposes inside a real fence. unfence()
      pairs fence markers by position: the next marker closes a fence whatever
      its character, length or info string, so a shorter marker inside a
      longer fence closes it and the marker after that opens one again, and a
      fence opened on a list marker's own line is not seen open at all, so its
      closer opens one. Either way a `<!--` the page shows as code is plain
      text to it. Where unfence() is back in step with the page by the heading
      after that `<!--` -- as it is after a longer fence whose shorter markers
      pair up inside it, when nothing before that fence was mis-paired, and is
      not right after a list marker's fence -- this anchors that heading, as
      the page shows it; the backlog check's comment block opens on the `<!--`
      and runs to the next `-->`, so it reads no heading there.

    Why each is left:

    * The comment reading is not taken in here because of the third shape: in
      this function it would fail a correct link to a heading the page shows,
      a new way for this check to skip one on top of unfence()'s own. Reading
      comment blocks without that edge needs fences paired the way CommonMark
      pairs them inside quotes and list items, which a reading of one line at a
      time does not have. What leaving it costs, measured when this was
      written: no heading in the tree sits inside a comment block, and taking
      the reading in would change no file's anchors.
    * A displaced heading gets no anchor because whether a fence, comment or
      HTML block opened in the same quote or list item holds it is not on its
      line (`> <!--` / `> ## x` / `> -->` shows no heading), and because the
      site does not render every displaced shape as a heading: its
      Python-Markdown does not for one to three spaces, a `1)` list, a list
      marker right after a paragraph line, or a list item's continuation. That
      failure is loud, and moving the heading to the start of the line fixes
      the link on both. The backlog check reports them for its own reason,
      decision 6 in that script.
    * The third shape is the backlog check's edge, named in its SHARP EDGES.

    `-N` numbering follows the first two: a heading this anchors inside a
    comment block takes a number the page does not give, and a displaced
    heading takes one on the page that it does not take here.

    Where both checks read the same lines and both differ from the page, they
    do not differ from each other: unfence()'s pairing, a heading inside a raw
    HTML block other than a comment (decision 6 in check-backlog-registers.py
    names those blocks). YAML front matter is no longer one of them: this blanks
    it (backlog T-6, closed 2026-10-05) and the backlog check does not, which
    differs on no file today because no register under docs/backlog/ has front
    matter. In the shapes the link self-test holds, two of unfence()'s pairings hide a
    heading the page shows: one after the closer of a fence opened on a `- `
    marker's line, and one between two backtick fence markers indented four
    spaces, which the page reads there as an indented code block.

    `check-doc-links.py --self-test` has a case for this function's side of
    each of the three shapes, of a raw HTML block, of each of those two
    pairings and of a `#` comment in front matter; the backlog check's
    `--self-test` has cases for its side.
    """
    found, seen = set(), {}
    # Front matter is not page text: the site drops it and github.com renders it as a
    # table, so a `# ` comment inside it is no heading (backlog T-6). Blanked rather than
    # cut, the way unfence() blanks a fence, so line numbering survives.
    body = unfence(FRONT_MATTER.sub(lambda m: "\n" * m.group(0).count("\n"), text, count=1))
    for line in body.splitlines():
        m = ATX_HEADING.match(line)
        if not m:
            continue
        base = slug(m.group(2))
        if not base:
            continue
        n = seen.get(base, 0)
        seen[base] = n + 1
        found.add(base if n == 0 else f"{base}-{n}")
    # Hand-written anchors live in raw HTML, so they are read off the raw text.
    found.update(HTML_ANCHOR.findall(text))
    return found


# --- translation pairs ------------------------------------------------------
#
# A translation declares its canonical and the commit it was translated from:
#
#     ---
#     translated_from: docs/features/tool/tool-development-guide.md
#     source_commit: eec9ccd
#     ---
#
# This is read with a line regex rather than a YAML parser on purpose. The two
# jobs that run these scripts call `python3 scripts/...` straight after
# actions/checkout -- no setup-python, no pip install -- so importing yaml would
# be a dependency on whatever the runner image happens to ship.

FRONT_MATTER = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n", re.DOTALL)
FRONT_MATTER_KEY = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):\s*(.*?)\s*$", re.MULTILINE)


def frontmatter(path):
    """The `---` block at the top of a file, as a dict. Empty when there is none."""
    m = FRONT_MATTER.match(path.read_text(encoding="utf-8", errors="replace"))
    if not m:
        return {}
    return {k: v for k, v in FRONT_MATTER_KEY.findall(m.group(1))}


# --- front matter both parsers read the same ---------------------------------
#
# Two parsers read that block and they are not the same parser. The scripts read
# it with FRONT_MATTER_KEY above; the site reads it with mkdocs'
# `mkdocs.utils.meta.get_data()`, which is `yaml.load` inside `except Exception:
# pass`. When YAML refuses the block -- or reads something that is not a mapping --
# get_data() returns no metadata AND LEAVES THE BLOCK IN THE PAGE, so the site
# publishes `translated_from: ...` as body text, and `mkdocs build --strict` exits
# 0 because nothing was logged to promote (backlog T-3; reproduced on mkdocs 1.6.1,
# 2026-10-05). The line regex reads the same block without complaint, so every
# check here stays green over a page that is broken on the site.
#
# Asking YAML is ruled out for the reason FRONT_MATTER is a regex. So instead of
# deciding whether a block is valid YAML, `front_matter_defects()` holds every block
# to a subset small enough to decide with line patterns, and inside which the two
# parsers were measured to read the same keys and the same values:
#
#     ---                              the fences are exactly `---`, nothing after
#     key: value                       one per line, from column 0, one `: ` after
#     other_key: "a quoted value"      a key matching FRONT_MATTER_KEY's own pattern
#     ---
#
#   plain value   starts with a letter, a digit, `_`, `.` or `/`; holds no `: `
#                 and no ` #`, does not end in `:`, and is not something YAML 1.1
#                 reads as a number, a boolean, null or a date (`1234567`, `yes`,
#                 `2026-10-05`)
#   quoted value  `"..."` with neither `"` nor `\` inside, and nothing after it
#
# Nothing else: no blank or comment line, no indentation, no tab, no list, no
# nested mapping, no repeated key, no empty value. Some of those the two parsers
# do agree on (a comment line, a blank line); they are left out because every
# shape let in is one more agreement that has to be shown and then kept true, and
# no front matter in the tree uses one. This is the same bargain `site_tree()`
# strikes with exclude_docs below: read one shape exactly and refuse the rest out
# loud, rather than half-understand it. A page that needs a list in its front
# matter (Material's `hide:`) is the occasion to widen the subset, together with
# the cases in `check-translation-structure.py --self-test`.
#
# The subset is narrower than "what does not break the site", on purpose. `key:
# 1234567` publishes fine; mkdocs holds the integer 1234567 and the scripts hold
# the text -- and for `0123456` mkdocs holds 42798. A seven-digit abbreviation
# with no letter in it is about one commit in 27, so that case is not exotic.
#
# Two keys must be plain, not quoted: the scripts use `translated_from` and
# `source_commit` by value and would read the quotes as part of it.

FRONT_MATTER_PLAIN_ONLY = ("translated_from", "source_commit")
FRONT_MATTER_LINE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):( *)(.*?) *$")
FRONT_MATTER_QUOTED = re.compile(r'^"[^"\\]+"$')
FRONT_MATTER_FENCE_LIKE = re.compile(r"\A﻿?---[ \t]*\r?(?:\n|\Z)")
# mkdocs' second reading: a page with no `---` fence that opens with `Key: value`
# lines has them taken as MultiMarkdown metadata and dropped from the page.
MULTIMARKDOWN_META = re.compile(r"^ {0,3}[A-Za-z0-9_-]+:\s*.*")

# What YAML 1.1's implicit resolver turns a plain scalar into when it is not a
# string -- PyYAML's own patterns (yaml/resolver.py), which is the resolver mkdocs
# loads with. `<<` and `=` are missing only because a plain value here cannot
# start with either.
YAML_NOT_A_STRING = (
    ("a boolean", re.compile(
        r"^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$")),
    ("null", re.compile(r"^(?:~|null|Null|NULL)$")),
    ("a number", re.compile(
        r"^(?:[-+]?0b[0-1_]+|[-+]?0[0-7_]+|[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x[0-9a-fA-F_]+"
        r"|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+)$")),
    ("a number", re.compile(
        r"^(?:[-+]?(?:[0-9][0-9_]*)\.[0-9_]*(?:[eE][-+][0-9]+)?"
        r"|\.[0-9][0-9_]*(?:[eE][-+][0-9]+)?"
        r"|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\.[0-9_]*"
        r"|[-+]?\.(?:inf|Inf|INF)|\.(?:nan|NaN|NAN))$")),
    ("a date", re.compile(
        r"^(?:[0-9]{4}-[0-9]{2}-[0-9]{2}"
        r"|[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}(?:[Tt]|[ \t]+)[0-9]{1,2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]*)?"
        r"(?:[ \t]*(?:Z|[-+][0-9]{1,2}(?::[0-9]{2})?))?)$")),
)

_SUBSET = "front matter here is `key: value` lines only"


def _plain_value_defect(key, value):
    """Why `value`, unquoted, is outside the subset -- or None when it is inside."""
    first = value[0]
    if not (first.isalnum() or first in "_./"):
        return (f"{key}: the value starts with {first!r}, which YAML does not read as the start "
                "of plain text -- mkdocs then drops the whole block and publishes it as page "
                "text. Start it with a letter, a digit, `_`, `.` or `/`"
                + ("" if key in FRONT_MATTER_PLAIN_ONLY else ", or wrap it in double quotes"))
    if ": " in value or value.endswith(":"):
        return (f"{key}: the value holds a colon followed by a space (or ends in one), which "
                "YAML reads as a second key -- mkdocs then drops the whole block and publishes "
                "it as page text. "
                + ("Take the colon out" if key in FRONT_MATTER_PLAIN_ONLY
                   else f'Wrap the value in double quotes: `{key}: "..."`'))
    if " #" in value:
        return (f"{key}: YAML reads everything from ` #` on as a comment and the scripts read it "
                "as part of the value. "
                + ("Take it out" if key in FRONT_MATTER_PLAIN_ONLY
                   else f'Wrap the value in double quotes: `{key}: "..."`'))
    for what, pattern in YAML_NOT_A_STRING:
        if pattern.match(value):
            if key == "source_commit":
                fix = ("Write a longer abbreviation, one with a letter in it "
                       f"(`git rev-parse --short=12 {value}`)")
            elif key in FRONT_MATTER_PLAIN_ONLY:
                fix = "Reword it so it reads as text"
            else:
                fix = f'Wrap it in double quotes: `{key}: "{value}"`'
            return (f"{key}: YAML reads `{value}` as {what} and the scripts read it as text, "
                    f"so mkdocs and the scripts hold different values. {fix}")
    return None


def front_matter_defects(text, site_page=True):
    """Where a file's front matter leaves the subset both parsers read the same.

    Returns `(line number, key or None, sentence)` per defect, empty for a file
    with no front matter and for one that stays inside the subset described in the
    comment above. Every sentence says what to write instead.

    `site_page` is whether mkdocs builds the file. One defect depends on it: a page
    with no fence that opens with `Key: value` is only misread by mkdocs, so a file
    the site does not build (CONTRIBUTING.ko.md) is free to open that way.
    """
    if not FRONT_MATTER_FENCE_LIKE.match(text):
        first = text.split("\n", 1)[0]
        if site_page and MULTIMARKDOWN_META.match(first):
            return [(1, None,
                     f"the page opens with `{first.strip()[:40]}` and no `---` fence. mkdocs reads "
                     "leading `Key: value` lines as MultiMarkdown metadata and drops them from "
                     "the page. Start the page with its `# ` heading, or with a line that is not "
                     "`Word: text`")]
        return []

    if text.startswith("﻿"):
        return [(1, None,
                 "a byte-order mark comes before the opening `---`. mkdocs reads front matter "
                 "here and the scripts read none. Save the file as UTF-8 without a BOM")]

    m = FRONT_MATTER.match(text)
    if not m:
        opening = text.split("\n", 1)[0].rstrip("\r")
        if opening != "---":
            return [(1, None,
                     "the opening fence has spaces or tabs after `---`. mkdocs reads front matter "
                     "here and the scripts read none. Write `---` and nothing else on the line")]
        return [(1, None,
                 "the page opens with `---` and no later line closes it as front matter for the "
                 "scripts (a closing line must be exactly `---`, with a newline after it; "
                 "`...` and `--- ` are read by mkdocs only). Close the block with `---`, or if "
                 "line 1 was meant as a horizontal rule, move it below the first heading")]

    defects, seen = [], {}
    for offset, raw_line in enumerate(m.group(1).split("\n")):
        number = offset + 2                     # line 1 is the opening fence
        line = raw_line.rstrip("\r")
        shown = line.strip()[:40]

        if not line.strip():
            defects.append((number, None,
                            f"{_SUBSET}: this line is blank. Delete it"))
            continue
        if "\t" in line or not line.isprintable():
            defects.append((number, None,
                            f"{_SUBSET}: `{shown.expandtabs(1)}` holds a tab or a control "
                            "character, which YAML and the scripts do not read the same way. "
                            "Use single spaces"))
            continue
        km = FRONT_MATTER_LINE.match(line)
        if not km:
            if line.lstrip().startswith("#"):
                why = "is a comment. Delete it, or say it in the page"
            elif line[0] == " ":
                why = ("is indented, so YAML reads it as part of the line above (a list item, a "
                       "nested key or a continued value) and the scripts skip it. Write each key "
                       "from column 0 with its whole value on the same line")
            elif line.lstrip().startswith("- "):
                why = ("is a YAML list item, which the scripts do not read. Write the items on "
                       "the key's own line as a comma-separated string")
            else:
                why = ("is not `key: value` with a key made of letters, digits and `_`")
            defects.append((number, None, f"{_SUBSET}: `{shown}` {why}"))
            continue

        key, gap, value = km.group(1), km.group(2), km.group(3)
        if key in seen:
            defects.append((number, key,
                            f"{key}: declared on line {seen[key]} too. YAML and the scripts both "
                            "keep the later one without saying so. Delete one"))
            continue
        seen[key] = number

        if not value:
            defects.append((number, key,
                            f"{key}: has no value. YAML reads null (or the start of a list or a "
                            "nested mapping on the lines below) and the scripts read an empty "
                            "string. Give it a value on this line, or delete the line"))
        elif not gap:
            defects.append((number, key,
                            f"{key}: no space after the colon. The scripts read `{key}` here, but "
                            "YAML does not read a key at all -- mkdocs then drops the whole block "
                            f"and publishes it as page text. Write `{key}: {value}`"))
        elif value[0] == '"':
            if not FRONT_MATTER_QUOTED.match(value):
                defects.append((number, key,
                                f"{key}: a quoted value is `\"...\"` with neither `\"` nor `\\` "
                                "inside and nothing after the closing quote. Anything else YAML "
                                "either refuses -- and mkdocs then publishes the block as page "
                                "text -- or unescapes into something the scripts do not read"))
            elif key in FRONT_MATTER_PLAIN_ONLY:
                defects.append((number, key,
                                f"{key}: the scripts use this value as written and would read "
                                f"the quotes as part of it. Write it unquoted: "
                                f"`{key}: {value[1:-1]}`"))
        else:
            why = _plain_value_defect(key, value)
            if why:
                defects.append((number, key, why))
    return defects


def markdown_files():
    """Every *.md in the repo, sorted, build outputs excluded."""
    return sorted(p for p in ROOT.rglob("*.md")
                  if not any(part in SKIP_DIRS for part in p.relative_to(ROOT).parts))


def translations():
    """Every *.<lang>.md in the repo, sorted, build outputs excluded."""
    # foo.en.md / foo.ko.md -- the suffixes declared above, so every walker
    # agrees on what a translation is
    return [p for p in markdown_files() if translation_suffix(p) is not None]


def canonical_of(meta):
    """The canonical a translation *declares*, or None when it declares none.

    Takes the dict `frontmatter()` returned rather than a path, and that is the
    point rather than a convenience: both callers already hold the dict (one
    reads `source_commit` from it, the other `structure_exempt`), so a path
    argument would read the file twice -- and, more usefully, requiring the meta
    means this function cannot be called without having read the declaration.
    Deriving the canonical from the translation's *name* would agree on all 32
    pairs today, but agreement is duplication rather than verification: the
    declared value is the one the staleness check already trusts.

    The direction is not assumed. `translated_from` names the canonical
    whichever language it is in, so this answers for docs/**/*.en.md (Korean
    canonical) and CONTRIBUTING.ko.md (English canonical) alike.
    """
    declared = meta.get("translated_from")
    if not declared:
        return None
    return ROOT / declared


# --- is this pair current ---------------------------------------------------

FRESH = "fresh"
STALE = "stale"
UNRESOLVABLE_DOCUMENT = "unresolvable-document"
UNRESOLVABLE_HISTORY = "unresolvable-history"

# Which findings a shallow clone could have produced. HISTORY findings answer a
# question about the commit graph and a truncated graph gets them wrong;
# DOCUMENT findings are about the file in front of you -- no clone depth deletes
# front matter or moves a canonical -- so they hold at any depth.
KIND_DOCUMENT = "document"
KIND_HISTORY = "history"
UNRESOLVABLE_KIND = {
    UNRESOLVABLE_DOCUMENT: KIND_DOCUMENT,
    UNRESOLVABLE_HISTORY: KIND_HISTORY,
}


class PairState:
    """What `pair_state()` answers, plus the detail its callers print.

    `state` is one of the four constants above and is the whole answer for a
    caller that only needs the verdict. `why` carries the sentence for an
    unresolvable finding and `commits` the canonical-only commits behind a stale
    one, so the staleness report does not have to recompute either.
    """

    __slots__ = ("state", "why", "commits", "canonical")

    def __init__(self, state, why=None, commits=(), canonical=None):
        self.state = state
        self.why = why
        self.commits = list(commits)
        self.canonical = canonical

    @property
    def unresolvable(self):
        return self.state in UNRESOLVABLE_KIND

    @property
    def kind(self):
        """"document" / "history" for an unresolvable state, else None."""
        return UNRESOLVABLE_KIND.get(self.state)


def git(*args):
    """Run a git command in the repo, returning stdout (stripped) or None."""
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), *args],
            capture_output=True, text=True, check=True,
        )
    except (subprocess.CalledProcessError, OSError):
        return None
    return out.stdout.strip()


def is_shallow_clone():
    """Whether this working tree has a truncated history.

    Asked once per run by the caller rather than carried per pair: it is a
    property of the clone, not of any pair, and threading it through
    `pair_state()` would copy one answer 32 times.
    """
    return git("rev-parse", "--is-shallow-repository") == "true"


def pair_state(translation, meta):
    """Whether `translation` is level with the canonical it declares.

    Returns a `PairState`. The four states are the ones both translation checks
    branch on, and they are computed here exactly once so the two cannot drift
    apart over which findings excuse themselves on a shallow clone.
    """
    rel = translation.relative_to(ROOT).as_posix()
    canonical = canonical_of(meta)
    commit = meta.get("source_commit")

    if canonical is None or not commit:
        return PairState(UNRESOLVABLE_DOCUMENT,
                         "no translated_from / source_commit front matter")
    if not canonical.exists():
        declared = meta.get("translated_from")
        return PairState(UNRESOLVABLE_DOCUMENT,
                         f"canonical does not exist: {declared}")

    declared = meta.get("translated_from")

    # An unknown commit means history was rewritten (squash, rebase, a shallow
    # clone). Say so rather than silently reporting "fresh".
    if git("cat-file", "-e", f"{commit}^{{commit}}") is None:
        return PairState(UNRESOLVABLE_HISTORY,
                         f"source_commit {commit} is not in this history",
                         canonical=canonical)

    # Reachable is not enough: a commit recorded on a squash-merged branch
    # exists in the repository but is not an ancestor of HEAD, and
    # `{commit}..HEAD` would then count every commit back to the merge base as
    # "behind" -- commits the translation was actually made from.
    if git("merge-base", "--is-ancestor", commit, "HEAD") is None:
        return PairState(UNRESOLVABLE_HISTORY,
                         f"source_commit {commit} is not an ancestor of HEAD "
                         "(recorded on an unmerged or squash-merged branch?)",
                         canonical=canonical)

    behind = git("log", "--format=%h %s", f"{commit}..HEAD", "--", declared)
    if behind is None:
        # DOCUMENT, not HISTORY, even though it is the catch-all: by here the
        # commit resolves and is an ancestor, so the range is computable at any
        # depth, and what is left to fail is the path. A translated_from of
        # "../elsewhere.md" that exists on disk gets past the existence check
        # above and makes git refuse the pathspec as outside the repository --
        # the file's problem, not the clone's.
        return PairState(UNRESOLVABLE_DOCUMENT,
                         f"could not diff {commit}..HEAD for {declared}",
                         canonical=canonical)

    # A commit that edited the canonical *and* this translation is not staleness
    # -- the translator saw the change. This happens on every normal update,
    # because source_commit can only name a commit that already exists, so it
    # always trails the commit making the edit by one. One `git log` over the
    # translation's own path answers it for the whole range -- no per-commit
    # subprocess, and no parsing of path lists that would misread a path
    # containing whitespace.
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
        return PairState(STALE, commits=commits, canonical=canonical)
    return PairState(FRESH, canonical=canonical)


# --- what the site builds ----------------------------------------------------
#
# The site is `docs_dir` minus `exclude_docs`, both declared in mkdocs.yml. Two
# readers need that answer and must not disagree on it:
#
#   * scripts/mkdocs_github_links.py, at render time, to turn a link into a tree
#     the site does not build into a GitHub URL;
#   * check-doc-links.py, to fail a link to a *directory* the site does build
#     (backlog T-5) -- which is exactly the link the hook leaves alone.
#
# The hook is handed mkdocs' own parsed `exclude_docs` (a pathspec). This side
# cannot have that: check-doc-links.py runs straight after actions/checkout, no
# pip install, so neither `yaml` nor `pathspec` is importable -- the same reason
# FRONT_MATTER above is a line regex. So mkdocs.yml is read with two regexes, and
# only the one pattern shape that can be matched without a gitignore engine is
# accepted: an anchored directory, `/backlog/` or `/a/b/`. Anything else -- a
# bare `backlog/` (any depth), a wildcard, a `!` negation, a file pattern --
# raises instead of being half-understood, and the link check goes red saying
# so. Widen EXCLUDE_PATTERN and SiteTree.excluded together when that happens.
#
# What keeps the two readers from drifting is not this comment: the hook's
# `on_config` asks both about every directory under `docs_dir` and stops the
# site build when one answer differs.

MKDOCS_DOCS_DIR = re.compile(r"^docs_dir:[ \t]*([^\s#]+)[ \t]*(?:#.*)?$", re.MULTILINE)
MKDOCS_EXCLUDE_DOCS = re.compile(
    r"^exclude_docs:[ \t]*\|[-+]?[ \t]*\n((?:[ \t]+.*(?:\n|\Z)|[ \t]*\n)*)", re.MULTILINE)
MKDOCS_EXCLUDE_DOCS_KEY = re.compile(r"^exclude_docs:", re.MULTILINE)
EXCLUDE_PATTERN = re.compile(r"^/(?:[^/*?\[\]!\\\s]+/)+$")


class SiteTree:
    """`docs_dir` and the directories `exclude_docs` keeps off the site."""

    def __init__(self, docs_dir, excluded_dirs):
        self.docs_dir = docs_dir
        self.excluded_dirs = tuple(excluded_dirs)

    def relative(self, path):
        """`path` as a posix path inside `docs_dir` ("" for `docs_dir` itself), else None."""
        try:
            relative = path.relative_to(self.docs_dir).as_posix()
        except ValueError:
            return None
        return "" if relative == "." else relative

    def excluded(self, relative):
        """Whether a path inside `docs_dir` is, or is under, an excluded directory."""
        relative = relative.strip("/")
        return any(relative == d or relative.startswith(d + "/") for d in self.excluded_dirs)

    def builds(self, path):
        """Whether the site builds `path` -- inside `docs_dir`, and not excluded."""
        relative = self.relative(path)
        return relative is not None and not self.excluded(relative)


def site_tree(root=ROOT):
    """What `root`/mkdocs.yml says the site builds, or None when there is no mkdocs.yml.

    Raises ValueError on an `exclude_docs` this cannot read exactly -- see the
    comment above for which shapes it reads and why the rest are refused.
    """
    config = pathlib.Path(root) / "mkdocs.yml"
    if not config.is_file():
        return None
    text = config.read_text(encoding="utf-8", errors="replace")

    m = MKDOCS_DOCS_DIR.search(text)
    docs_dir = (config.parent / (m.group(1).strip("\"'") if m else "docs")).resolve()

    excluded = []
    m = MKDOCS_EXCLUDE_DOCS.search(text)
    if m is None and MKDOCS_EXCLUDE_DOCS_KEY.search(text):
        raise ValueError(
            "mkdocs.yml: exclude_docs is not a `|` block of patterns, which is the only "
            "form scripts/docs_tree.py reads")
    for line in (m.group(1).splitlines() if m else ()):
        pattern = line.strip()
        if not pattern or pattern.startswith("#"):
            continue
        if not EXCLUDE_PATTERN.match(pattern):
            raise ValueError(
                f"mkdocs.yml: exclude_docs pattern {pattern!r} is not an anchored directory "
                "(`/name/`), the only shape scripts/docs_tree.py matches without a gitignore "
                "engine. Write it that way, or teach EXCLUDE_PATTERN and SiteTree.excluded "
                "the new shape")
        excluded.append(pattern.strip("/"))
    return SiteTree(docs_dir, excluded)
