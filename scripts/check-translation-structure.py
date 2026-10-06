#!/usr/bin/env python3
"""Compare a translation's structure against the canonical it declares.

CLAUDE.md and CONTRIBUTING.md both tell translators to "match the structure
exactly -- same heading count, same table rows, same code blocks", and until now
the only thing checking that was a person. The two checks that already run see
something else: check-doc-links.py sees paths and anchors, and
check-translation-staleness.py sees *time*. A translation that sits at the same
commit as its canonical while missing a whole section passes both of them green.

Design, with every number below measured rather than assumed:
docs/design/documentation/translation-structure-check.md.

SIX AXES, and they are chosen rather than obvious. Prose folds -- Korean carries
more per column, so the same paragraph wraps to more lines in English -- which
rules out counting lines anywhere, inside a fence or outside it (all 32 pairs
disagree on raw line count; one by 16%). What survives folding is what a
translator has no licence to change:

    headings          ^(#{1,6})\\s+\\S      count + the sequence of levels
    fences            ^\\s*(```|~~~)        count + the sequence of languages
    table-rows        ^\\s*\\|               per-table vector
    list-items        ^\\s*([-*+]|\\d+[.)])\\s   count + the indent sequence
    quote-blocks      ^\\s*>                RUNS, not lines
    fence-hash-lines  ^\\s*#  (inside)      advisory only -- see below

Three of those patterns are load-bearing in a way that is invisible until it is
not, and each cost a wrong measurement first:

  * `list-items` requires whitespace AFTER the marker. Without it, every line
    that opens with `**bold**` is a list item (a `*` marker followed by `*`),
    and because the two languages open paragraphs in bold at different rates
    that single missing `\\s` turns 17 of 32 pairs red. There are 328 such lines
    in the corpus. `--self-test`, below, pins this and the other two.
  * quotes are counted as RUNS. Counting `>` lines instead reproduces the fold
    problem and fails 10 of 32 pairs.
  * front matter is stripped before anything is counted. It is not, and the
    closing `---` of a translation's own front matter is a setext h2 sitting on
    top of `source_commit:` -- a false heading in all 32 translations and none
    of the canonicals.

FENCE CONTENTS ARE NOT COMPARED, and `fence-hash-lines` is advisory for the same
reason. CLAUDE.md orders translators to translate the comments inside code
blocks and to REDRAW ascii diagrams rather than edit them, so the inside of a
fence is exactly where legitimate divergence lives. Widening the advisory axis
from `#` to the `//` that 438 of this corpus's 588 fence comments actually use
breaks three pairs today, all of them a Korean comment wrapping to one more line
in English. So the axis stays narrow, stays advisory, and never fails a run.

WHICH FINDINGS FAIL is decided by the pair's state, not by the axis. A structure
mismatch has two causes and they are owed different answers:

  FRESH  -- the two files are level and disagree anyway. Someone is writing that
            translation right now and is being asked to finish, not to start.
            EXIT 1.
  STALE  -- the canonical moved and the translation has not caught up. Failing
            here would hand a red build to whoever edited the canonical, which
            is precisely the pressure check-translation-staleness.py exits 0 to
            avoid; doing it from a second script would overturn that decision
            through a side door. Reported, EXIT 0.
  UNRESOLVABLE / shallow clone -- freshness cannot be established, so there is no
            basis for the hard verdict. Reported, EXIT 0. (The staleness check
            already fails the job on the resolvable-in-principle ones.)

EXEMPTIONS live in the translation's own front matter, per axis:

    structure_exempt: list-items, fences
    structure_exempt_reason: "the canonical's 3-item list is idiomatically 2 in English"

The comma string and the quotes are both required, and that is not fussiness.
Two parsers read this block: this script's line regex and mkdocs' yaml.load. A
YAML list is read by the regex as the string '- list-items' -- first axis
mangled, second silently dropped -- and an unquoted reason containing a colon, a
backtick or a bracket makes mkdocs' get_data() swallow the error, return no
metadata, and publish the raw front matter as page text while `mkdocs build
--strict` still exits 0. The comma string and the quoted scalar are the only
forms both parsers agree on, so the script enforces them.

Checks run shape -> vocabulary -> self-invalidation, in that order, so a
malformed or misspelled entry never reaches the rule that asks whether an
exemption is still needed. All of them are declaration defects: a canonical edit
cannot create or clear one, so unlike a structure mismatch they fail at EXIT 1
whatever the pair's state. The one that does look at state is the
self-invalidation rule -- "this axis matches again, drop the exemption" -- whose
trigger depends on both files, so it fails only on a FRESH pair.

FRONT MATTER NOTATION is checked for every key, not only the two above (backlog
T-3). The failure those two guard against is not theirs alone: any line YAML
refuses makes mkdocs publish the whole block as page text with `--strict` green,
and `translated_from: docs/a: b.md` does it as well as an unquoted reason. Since
YAML cannot be asked (nothing is pip-installed where this runs), every front
matter the site or these scripts read is held to a subset decidable with line
patterns, inside which the two parsers were measured to agree:

    key: value                  plain: starts with a letter, a digit, `_`, `.` or
    other_key: "quoted value"   `/`; no `: `, no ` #`, no trailing `:`; not a YAML
                                number, boolean, null or date. quoted: `"..."`
                                with no `"` or `\\` inside and nothing after

and nothing else -- no blank or comment line, no indentation, no tab, no list or
nested mapping, no repeated key, no empty value; fences exactly `---`.
`translated_from` and `source_commit` must be plain, since the scripts use them
by value. The rule, the reasons and each sentence it prints are
docs_tree.front_matter_defects'; this script points it at two populations --
every translation (with the pair's other declaration defects, EXIT 1 whatever the
pair's state) and every other page the site builds (not pairs, so counted on
their own summary line). Files neither the site nor these scripts read front
matter from -- agent and skill definitions, .claude/rules -- are not in scope:
their own parsers read real YAML, and 46 of those 66 files are outside this subset.

`--drift` IS AN AUDIT, NOT PART OF THE CHECK (backlog T-4). The axes count
shapes; an identifier, a path or a config key translated inside a shape that
still matches passes all of them. `--drift` compares the two layers that must
not be translated -- inline code spans outside fences, as a multiset, and the
non-comment lines inside fences -- and it is not a gate because it cannot be: 30
of 32 pairs differ there and every difference read so far was legitimate. So it
folds away what it can show is not a translated identifier (a link retargeted to
`.en.md`, a span cut differently, the same words without backticks, Hangul and
its rendering, a translated trailing comment, a redrawn diagram), prints the
residue per pair with file:line on each side, and exits 0 whatever it found.
Non-zero only for a usage error or a file it was pointed at and cannot audit
(EXIT 2). It is not in CI. The folds and what each one licenses are specified in
the comment above CODE_SPAN.

`--self-test` therefore has three parts. The first swaps one axis pattern at a
time and holds the corpus still. The second holds the notation rule to one front
matter per case, each labelled with what mkdocs was measured to do with it. The
third holds each `--drift` fold to one small pair per case -- what it must fold,
and the planted identifier it must leave in the residue. Neither of the last two
reads the tree: with nothing to reject there, the cases are the only place those
rules are seen rejecting.

Usage:
    python3 scripts/check-translation-structure.py [--github]
    python3 scripts/check-translation-structure.py --self-test
    python3 scripts/check-translation-structure.py --drift [--all] [translation.md ...]

    --drift   audit what must not be translated; print the residue; exit 0
    --all     with --drift: print the folded items too, each under its fold
    paths     with --drift: audit only these translations (repository-relative)
"""
import re
import sys

from docs_tree import (FRESH, FRONT_MATTER, FRONT_MATTER_KEY, ROOT, canonical_of,
                       front_matter_defects, frontmatter, git, is_shallow_clone, markdown_files,
                       pair_state, site_tree, translation_suffix, translations)

# --- the axes ---------------------------------------------------------------

HEADING = re.compile(r"^(#{1,6})\s+\S")
FENCE = re.compile(r"^\s*(```|~~~)(.*)$")
TABLE_ROW = re.compile(r"^\s*\|")
LIST_ITEM = re.compile(r"^(\s*)(?:[-*+]|\d+[.)])\s")
QUOTE = re.compile(r"^\s*>")
FENCE_HASH = re.compile(r"^\s*#")

HARD_AXES = ("headings", "fences", "table-rows", "list-items", "quote-blocks")
ADVISORY_AXES = ("fence-hash-lines",)

EXEMPT_SHAPE = re.compile(r"^[a-z][a-z-]*(?:,\s*[a-z][a-z-]*)*$")
REASON_SHAPE = re.compile(r'^"[^"\\]+"$')


class Structure:
    """Everything the six axes see in one file."""

    __slots__ = ("headings", "fence_langs", "table_rows", "tables", "list_items",
                 "list_indents", "quote_blocks", "fence_hash_lines", "sections")

    def __init__(self):
        self.headings = []          # one entry per heading: its level
        self.fence_langs = []       # one entry per opening fence
        self.table_rows = 0
        self.tables = []            # rows per table, in document order
        self.list_items = 0
        self.list_indents = []      # leading-space width per item
        self.quote_blocks = 0
        self.fence_hash_lines = 0
        self.sections = []          # per heading bucket: (rows, items, quotes, fences)


def scan(path):
    """Measure one file. Front matter off, fenced and unfenced kept apart.

    The positional vectors are not separate counters: a section bucket holds the
    same four numbers the global axes hold, counted by the same patterns. Break
    that and an axis's own trap comes back inside the vector -- counting quote
    LINES per section reproduces the 10-of-32 failure exactly.
    """
    text = FRONT_MATTER.sub("", path.read_text(encoding="utf-8", errors="replace"), count=1)
    s = Structure()

    inside, marker = False, None
    open_table = 0
    in_quote = False
    bucket = [0, 0, 0, 0]           # rows, items, quotes, fences

    def close_table():
        nonlocal open_table
        if open_table:
            s.tables.append(open_table)
            open_table = 0

    for line in text.split("\n"):
        m = FENCE.match(line)
        if m:
            if not inside:
                inside, marker = True, m.group(1)
                info = m.group(2).strip()
                s.fence_langs.append(info.split()[0] if info else "-")
                bucket[3] += 1
                close_table()
                in_quote = False
            elif m.group(1) == marker:
                inside, marker = False, None
            continue

        if inside:
            if FENCE_HASH.match(line):
                s.fence_hash_lines += 1
            continue

        h = HEADING.match(line)
        if h:
            s.headings.append(len(h.group(1)))
            close_table()
            s.sections.append(tuple(bucket))
            bucket = [0, 0, 0, 0]
            in_quote = False
            continue

        if TABLE_ROW.match(line):
            s.table_rows += 1
            bucket[0] += 1
            open_table += 1
        else:
            close_table()

        li = LIST_ITEM.match(line)
        if li:
            s.list_items += 1
            s.list_indents.append(len(li.group(1)))
            bucket[1] += 1

        if QUOTE.match(line):
            if not in_quote:
                s.quote_blocks += 1
                bucket[2] += 1
            in_quote = True
        else:
            in_quote = False

    close_table()
    s.sections.append(tuple(bucket))
    return s


def section_column(structure, index):
    return tuple(b[index] for b in structure.sections)


def comparisons(a, b, positional):
    """Every (axis, what, canonical value, translation value) this pair offers.

    `positional` is the second pass and runs only when the heading axis lined up
    -- one missing section shifts every later bucket, and forty bogus findings
    bury the one real one.
    """
    out = [
        ("headings", "heading count", len(a.headings), len(b.headings)),
        ("headings", "heading levels", tuple(a.headings), tuple(b.headings)),
        ("fences", "code fence count", len(a.fence_langs), len(b.fence_langs)),
        ("fences", "fence languages", tuple(a.fence_langs), tuple(b.fence_langs)),
        ("table-rows", "table rows", a.table_rows, b.table_rows),
        ("list-items", "list items", a.list_items, b.list_items),
        ("quote-blocks", "quote blocks", a.quote_blocks, b.quote_blocks),
        ("fence-hash-lines", "'#' lines inside fences",
         a.fence_hash_lines, b.fence_hash_lines),
    ]
    if positional:
        out += [
            ("table-rows", "rows per table", tuple(a.tables), tuple(b.tables)),
            ("list-items", "list indent depths",
             tuple(a.list_indents), tuple(b.list_indents)),
            ("table-rows", "table rows per section",
             section_column(a, 0), section_column(b, 0)),
            ("list-items", "list items per section",
             section_column(a, 1), section_column(b, 1)),
            ("quote-blocks", "quote blocks per section",
             section_column(a, 2), section_column(b, 2)),
            ("fences", "code fences per section",
             section_column(a, 3), section_column(b, 3)),
        ]
    return out


# --- exemptions -------------------------------------------------------------

def read_exemptions(meta):
    """(axes, defects) declared by one translation's front matter.

    Shape, then vocabulary. Anything rejected here yields no axes at all, so the
    self-invalidation rule downstream never sees a line that does not name a
    real axis.
    """
    raw = meta.get("structure_exempt")
    reason = meta.get("structure_exempt_reason")
    defects = []

    if raw is None:
        if reason is not None:
            # An orphan reason is the same shape the coverage baseline refuses:
            # a line that reads as "an exemption, and here is why" while
            # exempting nothing. It is most often a half-deleted exemption or a
            # misspelled key, and both leave the next reader believing an axis
            # is off. Nobody but the author of this front matter can create it,
            # so it fails whatever the pair's state.
            defects.append("structure_exempt_reason without structure_exempt "
                           "-- nothing is exempt; delete the reason or name the axis")
        return (), defects

    if not EXEMPT_SHAPE.match(raw):
        defects.append(
            f"structure_exempt: {raw!r} is not a comma-separated list of axis ids. "
            "A YAML list reads as '- <first>' through this script's parser while "
            "mkdocs reads a real list, so the two disagree; write "
            "`structure_exempt: list-items, fences`")
        return (), defects

    if reason is None:
        defects.append("structure_exempt without structure_exempt_reason "
                       "-- an exemption nobody explained is one nobody can retire")
        return (), defects
    if not REASON_SHAPE.match(reason):
        defects.append(
            f"structure_exempt_reason: {reason!r} must be wrapped in double quotes "
            "and contain neither `\"` nor `\\`. Unquoted, a colon or a backtick or a "
            "bracket makes mkdocs drop the whole front matter and publish it as page "
            "text, with `mkdocs build --strict` still green")
        return (), defects

    axes, seen = [], set()
    for name in (part.strip() for part in raw.split(",")):
        if name in seen:
            defects.append(f"structure_exempt names {name!r} twice")
            continue
        seen.add(name)
        if name in ADVISORY_AXES:
            defects.append(
                f"structure_exempt: {name!r} is advisory and never fails a run, so "
                "exempting it switches nothing off while reading as though it did")
            continue
        if name not in HARD_AXES:
            hint = ", ".join(HARD_AXES)
            defects.append(f"structure_exempt: {name!r} is not an axis. One of: {hint}")
            continue
        axes.append(name)

    if defects:
        return (), defects
    return tuple(axes), []


# --- front matter notation --------------------------------------------------

EXEMPTION_KEYS = ("structure_exempt", "structure_exempt_reason")


def notation_defects(path, site, exemption_defects=()):
    """Sentences for every line of `path`'s front matter outside the readable subset.

    The subset and the reason for it are docs_tree.front_matter_defects'. This
    only decides two things that function cannot: whether mkdocs builds the file,
    and whether a line is already reported. `read_exemptions` owns the two
    exemption keys and holds them to a narrower shape with its own sentence, so
    when it has spoken, the general sentence about the same line is dropped --
    two findings for one unquoted reason reads as two problems.
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    built = site is not None and site.builds(path)
    out = []
    for number, key, why in front_matter_defects(text, site_page=built):
        if exemption_defects and key in EXEMPTION_KEYS:
            continue
        out.append(f"front matter, line {number}: {why}")
    return out


def has_front_matter(path):
    return FRONT_MATTER.match(path.read_text(encoding="utf-8", errors="replace")) is not None


# --- reporting --------------------------------------------------------------

class Finding:
    __slots__ = ("rel", "severity", "text")

    def __init__(self, rel, severity, text):
        self.rel = rel
        self.severity = severity        # "hard" | "soft" | "advisory"
        self.text = text


# --- the pattern regression -------------------------------------------------
#
# The axis probes in the design all vary the DOCUMENT and hold the patterns
# still. This varies the patterns and holds the corpus still, which is the other
# half and the half that a green run cannot show you: with nothing to catch
# today, a check that quietly stopped measuring would look exactly like a check
# that measured and found nothing.
#
# Each case asserts only that the wrong reading still breaks SOMETHING, and
# prints the count beside the figure the design recorded. Pinning the exact
# number would be sharper documentation and a worse gate: adding one translation
# pair moves it, and the failure would name a regression that had not happened.
# gradle/coverage-baselines.properties spells out the cost of getting that wrong
# -- "a rule that flakes gets excluded, and an excluded rule is worse than none".
# The recorded figures are printed so drift is visible without being fatal.

LOOSE_LIST_ITEM = re.compile(r"^(\s*)(?:[-*+]|\d+[.)])")
SETEXT = re.compile(r"^(=+|-{2,})\s*$")
WIDE_FENCE_COMMENT = re.compile(r"^\s*(#|//|/\*|\*(?!\*)|<!--)")


def _pairs():
    for translation in translations():
        meta = frontmatter(translation)
        canonical = canonical_of(meta)
        if canonical is not None and canonical.exists():
            yield canonical, translation


def _body(path):
    return FRONT_MATTER.sub("", path.read_text(encoding="utf-8", errors="replace"), count=1)


def _outside(path, strip_front=True):
    """Lines outside fences, and lines inside them, as two lists."""
    text = _body(path) if strip_front else path.read_text(encoding="utf-8", errors="replace")
    out, inner, inside, marker = [], [], False, None
    for line in text.split("\n"):
        m = FENCE.match(line)
        if m:
            if not inside:
                inside, marker = True, m.group(1)
            elif m.group(1) == marker:
                inside, marker = False, None
            continue
        (inner if inside else out).append(line)
    return out, inner


def self_test():
    """Swap one reading at a time and hold the corpus still.

    Each case asks a narrow question: does this wrong reading part company with
    the specified one on at least one pair the specified reading is happy with?

    Counting that DELTA rather than an absolute is what keeps the case honest
    when the corpus is not. A pair whose canonical has been edited ahead of its
    translation differs under both readings, and so does a pair carrying a
    legitimate `structure_exempt`; both cancel. An earlier version asserted
    instead that the specified reading finds zero differences corpus-wide, which
    sounds like the same claim and is not: that number moves for reasons that
    have nothing to do with the patterns, and it turned the two states this
    check exists to FORGIVE -- a canonical edited first, the first exemption --
    into a red build on the step before the check that forgives them. The main
    check owns that assertion, with the pair state and the exemptions in hand.

    The delta framing also makes each case guard its own pattern directly. Widen
    the production pattern into the wrong one and the two readings agree, the
    delta collapses to zero, and the case fails.
    """
    pairs = list(_pairs())

    def delta(specified, wrong):
        """Pairs the wrong reading splits that the specified reading does not."""
        return sum(1 for c, t in pairs
                   if wrong(c) != wrong(t) and specified(c) == specified(t))

    # Front matter is invisible to the ATX pattern -- `---` is not a heading to
    # it -- so this case has to use a reading that DOES see it to show why the
    # strip is load-bearing. That makes it a statement about the corpus (only
    # translations carry front matter, 32 against 0) rather than a guard on the
    # strip itself; the production patterns are indifferent to it today, which
    # is exactly why nothing else here would notice its absence.
    def setext_aware(path):
        lines = _outside(path, strip_front=False)[0]
        n = 0
        for i, line in enumerate(lines):
            if HEADING.match(line):
                n += 1
            elif i and SETEXT.match(line) and lines[i - 1].strip():
                n += 1
        return n

    cases = [
        ("list-items without the whitespace after the marker",
         "17 of 32 pairs", 17,
         delta(lambda p: scan(p).list_items,
               lambda p: sum(1 for l in _outside(p)[0] if LOOSE_LIST_ITEM.match(l)))),
        ("quote blocks counted as '>' lines instead of runs",
         "10 of 32 pairs", 10,
         delta(lambda p: scan(p).quote_blocks,
               lambda p: sum(1 for l in _outside(p)[0] if QUOTE.match(l)))),
        ("fence comments widened from '#' to '//' and '/*'",
         "3 of 32 pairs", 3,
         delta(lambda p: scan(p).fence_hash_lines,
               lambda p: sum(1 for l in _outside(p)[1] if WIDE_FENCE_COMMENT.match(l)))),
        ("front matter left in place, read by anything that sees setext headings",
         "all 32 pairs -- only translations have front matter", 32,
         delta(lambda p: tuple(scan(p).headings), setext_aware)),
    ]

    print(f"pattern regression over {len(pairs)} pair(s)")
    failed = 0
    for name, recorded, expected, measured in cases:
        good = measured > 0
        failed += not good
        drift = "" if measured == expected else f"  (design recorded {expected})"
        print(f"  {'ok  ' if good else 'FAIL'} {name}")
        print(f"         breaks {measured} pair(s) the specified reading does not; "
              f"design recorded {recorded}{drift}")

    # Reported, never failed. How many pairs the specified reading itself splits
    # is a property of the corpus on the day it runs -- it is 1 the moment a
    # canonical is edited ahead of its translation, and 1 again for the first
    # legitimate exemption. Gating on it here would fail the run for exactly the
    # two states the design spends section 3.4 and section 4 forgiving.
    live = sum(1 for c, t in pairs
               if any(x != y for _, _, x, y in comparisons(scan(c), scan(t), True)))
    print()
    print(f"for context, the specified reading splits {live} pair(s) right now "
          "(exemptions and pair state not applied) -- whether that matters is the "
          "check's own verdict, not this one's")

    if failed:
        print()
        print(f"{failed} case(s) failed: a wrong reading no longer parts company with the "
              "specified one, which means the specified one has become it. The patterns in "
              "the module docstring are the specification -- see docs/design/documentation/"
              "translation-structure-check.md \u00a71.1.")
        return 1
    print()
    print("every wrong reading still breaks something the specified reading does not")
    return 0


# --- the notation regression ------------------------------------------------
#
# The tree holds nothing the notation rule rejects, so this is the only place the
# rule is seen to reject anything. One front matter per case, never the tree's.
#
# The middle column is what mkdocs 1.6.1 (PyYAML, through mkdocs.utils.meta.
# get_data) was MEASURED to do with that block on 2026-10-05, and is why the case
# is there:
#
#   LEAK   YAML refuses the block or reads a non-mapping; get_data() returns no
#          metadata and leaves the block in the page, which the site publishes
#   DIFF   mkdocs reads the block, and holds a different key set or a different
#          value from the one FRONT_MATTER_KEY reads
#   same   the two read the same thing
#
# That column is a record, not something this run re-measures: nothing here
# imports yaml, for the reason docs_tree.FRONT_MATTER is a regex. What the run
# asserts is the third column -- that the rule still rejects every LEAK and DIFF
# with a sentence naming the cause, still accepts the `same` forms it means to,
# and still rejects the `same` forms the subset leaves out on purpose.

_TWO = "translated_from: docs/a.md\nsource_commit: eec9ccd"
_NOTE = _TWO + "\nnote: "

NOTATION_CASES = [
    # (what, mkdocs measured, front matter block, expected substring -- None = accepted)
    ("the two keys every translation carries", "same", _TWO, None),
    ("a colon with no space after it in a value", "same",
     "translated_from: docs/a:b.md\nsource_commit: eec9ccd", None),
    ("a backtick in the middle of a value", "same",
     "translated_from: docs/`a`.md\nsource_commit: eec9ccd", None),
    ("a `#` with no space before it", "same",
     "translated_from: docs/a.md#x\nsource_commit: eec9ccd", None),
    ("an abbreviation that only looks numeric (`12e4567`, `0189456`)", "same",
     "translated_from: docs/a.md\nsource_commit: 12e4567\nother: 0189456", None),
    ("a version string", "same", _NOTE + "0.2.4", None),
    ("a comma string, the exemption list's form", "same",
     _TWO + "\nstructure_exempt: list-items, fences", None),
    ("Korean plain text", "same", _NOTE + "\ud55c\uae00 \uac12 \ud558\ub098", None),
    ("brackets and quotes in the middle of a plain value", "same",
     _NOTE + "see [a](b.md), it's \"fine\"", None),
    ("a quoted value holding a colon, a backtick and a bracket", "same (minus the quotes)",
     _NOTE + '"\ud45c \uc81c\ubaa9: `a` [b]"', None),

    ("a colon followed by a space in a value", "LEAK",
     "translated_from: docs/a: b.md\nsource_commit: eec9ccd", "colon followed by a space"),
    ("a value ending in a colon", "LEAK",
     "translated_from: docs/a.md:\nsource_commit: eec9ccd", "colon followed by a space"),
    ("a value opening with a backtick", "LEAK",
     "translated_from: `docs/a.md`\nsource_commit: eec9ccd", "starts with '`'"),
    ("a value opening with `*`", "LEAK", _NOTE + "*emphasis* first", "starts with '*'"),
    ("a value opening with `[` and never closing", "LEAK", _NOTE + "[a", "starts with '['"),
    ("no space after the key's colon", "LEAK",
     "translated_from:docs/a.md\nsource_commit: eec9ccd", "no space after the colon"),
    ("an indented key", "LEAK",
     "translated_from: docs/a.md\n  source_commit: eec9ccd", "is indented"),
    ("a quoted value with text after the closing quote", "LEAK",
     _NOTE + '"a" b', "nothing after the closing quote"),
    ("a quoted value never closed", "LEAK", _NOTE + '"a', "nothing after the closing quote"),
    ("a block holding only a comment", "LEAK", "# nothing", "is a comment"),
    ("an empty block", "LEAK", "", "is blank"),

    ("a flow list", "DIFF", _NOTE + "[a, b]", "starts with '['"),
    ("a block list", "DIFF", _TWO + "\ntags:\n  - a\n  - b", "tags: has no value"),
    ("a list item at column 0", "LEAK", _TWO + "\n- a", "is a YAML list item"),
    ("a nested mapping", "DIFF", _TWO + "\nsearch:\n  exclude: true", "search: has no value"),
    ("a plain value continued on the next line", "DIFF", _NOTE + "one\n  two", "is indented"),
    ("an empty value", "DIFF", "translated_from: docs/a.md\nsource_commit:",
     "source_commit: has no value"),
    ("a space and `#` in a value", "DIFF",
     "translated_from: docs/a.md #x\nsource_commit: eec9ccd", "as a comment"),
    ("an all-digit abbreviation", "DIFF", "translated_from: docs/a.md\nsource_commit: 1234567",
     "git rev-parse --short=12 1234567"),
    ("an all-digit abbreviation with a leading zero (mkdocs holds 42798)", "DIFF",
     "translated_from: docs/a.md\nsource_commit: 0123456", "as a number"),
    ("`yes`", "DIFF", _NOTE + "yes", 'as a boolean and the scripts read it as text'),
    ("`null`", "DIFF", _NOTE + "null", "as null"),
    ("a date", "DIFF", _NOTE + "2026-09-06", "as a date"),
    ("a float", "DIFF", _NOTE + "1.5", "as a number"),
    ("a sexagesimal (`1:30` is 90)", "DIFF", _NOTE + "1:30", "as a number"),
    ("a single-quoted value", "DIFF", _NOTE + "'a'", "starts with \"'\""),
    ("a quoted value with a backslash (YAML unescapes it)", "DIFF", _NOTE + '"a\\b"',
     "neither `\"` nor `\\`"),
    ("translated_from in quotes (the scripts keep the quotes)", "DIFF",
     'translated_from: "docs/a.md"\nsource_commit: eec9ccd', "Write it unquoted"),
    ("a key with a hyphen (FRONT_MATTER_KEY does not read it)", "DIFF", _TWO + "\nmy-key: v",
     "letters, digits and `_`"),

    ("a blank line", "same -- left out", "translated_from: docs/a.md\n\nsource_commit: eec9ccd",
     "is blank"),
    ("a comment line", "same -- left out",
     "translated_from: docs/a.md\n# note\nsource_commit: eec9ccd", "is a comment"),
    ("a tab after the key's colon", "same -- left out",
     "translated_from:\tdocs/a.md\nsource_commit: eec9ccd", "holds a tab"),
    ("a key declared twice", "same -- left out", _TWO + "\ntranslated_from: docs/b.md",
     "declared on line 2 too"),
]

# Whole files: the fences, and the reading mkdocs has when there is no fence.
NOTATION_FILE_CASES = [
    # (what, mkdocs measured, file text, site page, expected substring -- None = accepted)
    ("no front matter at all", "same", "# Title\n\nBody.\n", True, None),
    ("a `---` rule further down the page", "same", "# Title\n\n---\n\nBody.\n", True, None),
    ("spaces after the opening `---`", "DIFF", "--- \n" + _TWO + "\n---\n\n# T\n", True,
     "spaces or tabs after `---`"),
    ("closed by `...`", "DIFF", "---\n" + _TWO + "\n...\n\n# T\n", True, "no later line closes it"),
    ("a tab after the closing `---`", "DIFF", "---\n" + _TWO + "\n---\t\n\n# T\n", True,
     "no later line closes it"),
    ("the closing `---` is the file's last bytes", "LEAK", "---\n" + _TWO + "\n---", True,
     "no later line closes it"),
    ("a byte-order mark before the opening `---`", "DIFF", "\ufeff---\n" + _TWO + "\n---\n\n# T\n",
     True, "byte-order mark"),
    ("a site page opening with `Key: value` and no fence (mkdocs drops the lines)", "DIFF",
     "Status: draft\nOwner: me\n\n# T\n", True, "MultiMarkdown"),
    ("the same opening in a file the site does not build", "not read by mkdocs",
     "Status: draft\nOwner: me\n\n# T\n", False, None),
]


def notation_self_test():
    """Hold the notation rule to one front matter per case.

    A case fails when the rule's verdict changes, or when it rejects for another
    reason than the one named: a rule that still says "no" to a list but now says
    it about the wrong line would otherwise pass as unchanged.
    """
    cases = [(what, measured, f"---\n{block}\n---\n\n# T\n", True, expected)
             for what, measured, block, expected in NOTATION_CASES] + NOTATION_FILE_CASES

    print()
    print(f"front matter notation over {len(cases)} case(s)")
    failed = 0
    for what, measured, text, site_page, expected in cases:
        sentences = [why for _, _, why in front_matter_defects(text, site_page=site_page)]
        if expected is None:
            good = not sentences
            got = "accepted" if good else f"REJECTED: {sentences[0]}"
        else:
            good = any(expected in s for s in sentences)
            got = ("rejected" if good else
                   f"rejected for another reason: {sentences[0]}" if sentences else "ACCEPTED")
        failed += not good
        print(f"  {'ok  ' if good else 'FAIL'} {what}  [mkdocs: {measured}]  -> {got}")

    # The two exemption keys have an owner with a narrower rule and its own
    # sentence; the general rule must stand down on a line that owner reported.
    doubled = FRONT_MATTER.match("---\n" + _TWO + "\nstructure_exempt: list-items\n"
                                 "structure_exempt_reason: \ud45c \uc81c\ubaa9: \ub458\ub85c \uac08\ub9b0\ub2e4\n---\n")
    meta = dict(FRONT_MATTER_KEY.findall(doubled.group(1)))
    owner = read_exemptions(meta)[1]
    general = [key for _, key, _ in front_matter_defects(doubled.group(0))]
    good = len(owner) == 1 and general == ["structure_exempt_reason"]
    failed += not good
    print(f"  {'ok  ' if good else 'FAIL'} an unquoted reason holding `: ` is seen by both rules "
          "(the owner's sentence is the one printed)")

    if failed:
        print()
        print(f"{failed} notation case(s) failed. The subset is specified in scripts/docs_tree.py "
              "above front_matter_defects(); a case whose verdict is MEANT to change needs that "
              "comment, this table and docs/design/documentation/translation-structure-check.md "
              "\u00a74.6 changed with it -- and the new form measured against mkdocs first.")
        return 1
    print()
    print("the notation rule rejects every form mkdocs and the scripts read differently")
    return 0


# --- the drift audit --------------------------------------------------------
#
# `--drift`: what must NOT be translated, compared. The six axes count shapes, so
# a translation whose shape is right and whose identifiers were quietly
# translated -- `maxIterations` into "max iterations", a config key, a path --
# passes all of them. Two layers hold that text:
#
#   inline code spans   outside fences, as a multiset
#   fence lines         inside fences, the lines that are not comments
#
# This is an audit a person runs, never a gate, and the reason is measured rather
# than assumed: on the tree it was built on, 30 of 32 pairs differ in one layer or
# the other and every difference read was legitimate (design section 1.3 (e) and
# section 9). A machine cannot say "legitimate"; what it can do is recognise the
# kinds of difference a translator is ORDERED to make, or that leave the
# untranslatable text itself intact, fold those away, and print what is left.
# Whatever is left, the exit code is 0.
#
# The folds, in the order they are tried. Each one is a claim about why the item
# cannot be a translated identifier, which is the only thing that licenses hiding
# it:
#
#   retarget    `foo.md` on one side, `foo.en.md` on the other. documentation-
#               guide.md section 5.4 orders it.
#   re-cut      the span on one side is a substring of a span on the other, in
#               the same section: `merged.id == winner` against `merged.id` and
#               `winner`. The characters survived; where the backticks sit moved.
#   present     the span's text stands verbatim in the same section of the other
#               file, outside any unmatched span: the words are there without the
#               backticks, or one of several identical spans went away with a
#               restructured sentence. A translated identifier is not verbatim.
#   translated  the item holds Hangul, which is prose in backticks (`<해시>`), in
#               a string literal or in a diagram label, and prose is what a
#               translator translates -- PROVIDED every ASCII word of three
#               letters or more in it still stands in the same section (or
#               fence) of the other file. It takes one otherwise unmatched item
#               from the other side of the same place with it: its rendering.
#   comment     (fence lines) the two lines are equal once a trailing `// ...` or
#               `# ...` is cut off: CLAUDE.md orders comments inside fences
#               translated. Lines that are comments from their first character
#               are not compared at all.
#   redrawn     (fence lines) the fence is an ASCII diagram, which CLAUDE.md
#               orders redrawn rather than edited, and the line is pure line-art
#               or every word in it stands in the same fence on the other side.
#
# What is left is `residue`: text that exists on one side only and that none of
# those explains. A translated identifier lands there. Alone in its span or on
# its line, its canonical spelling holds no Hangul and is neither a substring of
# nor verbatim in the other side; next to Hangul, it is the ASCII word that no
# longer stands across. The partner a `translated` item takes is chosen by
# position, not by meaning, and can be the wrong one -- which is why a partner is
# only ever taken from the side that holds no Hangul: the Korean side's own item
# is what a drift has to get past, and no fold lets it.
#
# What this cannot see: an identifier translated outside backticks and outside
# fences (it was prose to begin with); a span whose text was changed into
# something that happens to stand elsewhere in the same section; a word of one
# or two letters; and a fence line that is a comment from its first character,
# `* item` in a fenced markdown example included.

CODE_SPAN = re.compile(r"(?<!`)(`+)(?!`)((?:(?!\n[ \t>]*\n).)+?)(?<!`)\1(?!`)", re.DOTALL)
HANGUL = re.compile(r"[ᄀ-ᇿ㄰-㆏가-힣]")
HANGUL_RUN = re.compile(r"[ᄀ-ᇿ㄰-㆏가-힣]+(?:[ \t]+[ᄀ-ᇿ㄰-㆏가-힣]+)*")
FENCE_COMMENT_LINE = WIDE_FENCE_COMMENT
TRAILING_COMMENT = re.compile(r"\s+(?://|#)\s.*$")
DIAGRAM = re.compile(r"[─-╿←-⇿■-◿]|^\s*[+|].*-{3,}.*[+|]\s*$|-{2,}>|<-{2,}",
                     re.MULTILINE)
DIAGRAM_WORD = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_./:@-]*")
# An ASCII word long enough to be an identifier, a path or a key rather than `a` or `1`.
ASCII_WORD = re.compile(r"[A-Za-z][A-Za-z0-9_./:@-]{2,}")

DRIFT_FOLDS = ("retarget", "re-cut", "present", "translated", "comment", "redrawn")
DRIFT_WHY = {
    "retarget": "a link retargeted to the translation (.md -> .<lang>.md)",
    "re-cut": "the same characters, with the backticks cut differently",
    "present": "the same text stands in the other file's section without these backticks",
    "translated": "Hangul in backticks or in a fence, and what it was rendered as",
    "comment": "equal once the trailing comment is cut off",
    "redrawn": "inside an ASCII diagram, which is redrawn rather than edited",
}


class Item:
    """One inline span or one fence line, on one side of a pair."""

    __slots__ = ("side", "line", "text", "where", "fold")

    def __init__(self, side, line, text, where):
        self.side = side            # "canonical" | "translation"
        self.line = line            # 1-based, in the file as it is on disk
        self.text = text
        self.where = where          # section index for a span, fence index for a fence line
        self.fold = None            # None = residue


class Layers:
    """What `--drift` reads out of one file."""

    __slots__ = ("spans", "fences", "sections")

    def __init__(self):
        self.spans = []             # (line, text, section index)
        self.fences = []            # per fence: (language, [(line, text)])
        self.sections = []          # per section: its text outside fences, spans included


def drift_layers(text):
    """Spans outside fences and lines inside them, with on-disk line numbers.

    Front matter is blanked rather than cut and fences are blanked in the copy
    the spans are read from, so a line number here is the line in the file.
    The span pattern crosses line breaks on purpose: one span in the corpus runs
    over two lines, and a line-by-line reading reports it as code that exists in
    one language only (design section 1.2). It does not cross a blank line, or one
    stray backtick would pair with another a page later.
    """
    text = FRONT_MATTER.sub(lambda m: "\n" * m.group(0).count("\n"), text, count=1)
    layers = Layers()
    outside, inside, marker = [], False, None
    section = []
    for number, line in enumerate(text.split("\n"), start=1):
        m = FENCE.match(line)
        if m:
            if not inside:
                inside, marker = True, m.group(1)
                info = m.group(2).strip()
                layers.fences.append((info.split()[0] if info else "-", []))
            elif m.group(1) == marker:
                inside, marker = False, None
            outside.append("")
            continue
        if inside:
            layers.fences[-1][1].append((number, line))
            outside.append("")
            continue
        if HEADING.match(line):
            layers.sections.append("\n".join(section))
            section = []
        section.append(line)
        outside.append(line)
    layers.sections.append("\n".join(section))

    prose = "\n".join(outside)
    starts = [i for i, line in enumerate(outside) if HEADING.match(line)]
    for m in CODE_SPAN.finditer(prose):
        line_index = prose.count("\n", 0, m.start())
        index = sum(1 for s in starts if s <= line_index)
        body = re.sub(r"\n[ \t]*>[ \t]?", " ", m.group(2))      # a span continued inside a quote
        layers.spans.append((line_index + 1, " ".join(body.split()), index))
    return layers


def _unmatched(canonical, translation):
    """The items of two (line, text, where) lists that have no equal on the other side.

    Equal texts are paired off in order of appearance, within the same `where`
    first, so that what is left over is the occurrence a reader would call missing.
    """
    left = [Item("canonical", line, text, where) for line, text, where in canonical]
    right = [Item("translation", line, text, where) for line, text, where in translation]
    for same_place in (True, False):
        pool = {}
        for item in right:
            if item.fold != "=":
                pool.setdefault((item.text, item.where if same_place else None), []).append(item)
        for item in left:
            if item.fold == "=":
                continue
            waiting = pool.get((item.text, item.where if same_place else None))
            if waiting:
                waiting.pop(0).fold = "="
                item.fold = "="
    out = [i for i in left + right if i.fold != "="]
    return out


def _retargeted(a, b, suffix):
    """Whether span texts `a` and `b` are one markdown path, differing only by the language suffix.

    Either way round: a translation points its links at translations
    (`foo.md` -> `foo.en.md`), and a pair that links to each other as "the other
    language" does the reverse (`CONTRIBUTING.ko.md` in the canonical,
    `CONTRIBUTING.md` in the translation).
    """
    if a == b or ".md" not in a or ".md" not in b:
        return False
    path_a, _, fragment_a = a.partition("#")
    path_b, _, fragment_b = b.partition("#")
    if bool(fragment_a) != bool(fragment_b) or path_a == path_b:
        return False
    # an anchor is a translated heading, so it may differ; the path may differ only by the suffix
    plain = f"{suffix}.md"
    return path_a.replace(plain, ".md") == path_b.replace(plain, ".md")


def _skeleton(text):
    """A pattern for what `text` may have been rendered as: its Hangul runs free, the rest fixed."""
    parts, last = [], 0
    for m in HANGUL_RUN.finditer(text):
        parts.append(re.escape(text[last:m.start()]))
        parts.append(r".+?")
        last = m.end()
    parts.append(re.escape(text[last:]))
    return re.compile("^" + "".join(parts).replace(r"\ ", r"\s*") + "$")


def _pair_translated(items, across):
    """Fold the Hangul items whose ASCII words made it across, and one partner each.

    `across(item)` is the other file's text in the same place (section or fence).
    A Hangul item is prose, but prose stands next to identifiers
    (`Consumer (OrcaAgentExecutor ReAct 루프)`), so it is folded only when every
    ASCII word in it still stands in that text; otherwise it stays in the residue,
    which is where a renamed `OrcaAgentExecutor` has to surface.

    Each folded item then takes one unmatched item from the other side of the same
    place as its rendering: one its non-Hangul skeleton fits (`<해시>` fits
    `<hash>`) if there is one, else the next in order of appearance, since word
    order moves in translation. An item with no partner is folded alone -- its
    rendering lost its backticks, or was wrapped into a neighbouring line.
    """
    hangul = []
    for item in items:
        if item.fold is None and HANGUL.search(item.text):
            text = across(item)
            if all(word in text for word in ASCII_WORD.findall(item.text)):
                item.fold = "translated"
                hangul.append(item)
    taken = set()
    for strict in (True, False):
        for item in hangul:
            if id(item) in taken:
                continue
            shape = _skeleton(item.text)
            for other in items:
                if (other.fold is None and other.side != item.side and other.where == item.where
                        and not HANGUL.search(other.text)
                        and (not strict or shape.match(other.text))):
                    other.fold = "translated"
                    taken.add(id(item))
                    break


def drift_spans(a, b, suffix):
    """Every inline-span item one file has and the other does not, each folded or left as residue."""
    items = _unmatched(a.spans, b.spans)

    for item in items:                                      # retarget
        if item.fold is None and item.side == "canonical":
            for other in items:
                if (other.fold is None and other.side == "translation"
                        and _retargeted(item.text, other.text, suffix)):
                    item.fold = other.fold = "retarget"
                    break

    for item in items:                                      # re-cut
        if item.fold is not None:
            continue
        parts = [o for o in items
                 if o is not item and o.side != item.side and o.where == item.where
                 and o.fold in (None, "re-cut") and len(o.text) > 1 and o.text in item.text
                 and o.text != item.text]
        if parts:
            item.fold = "re-cut"
            for part in parts:
                part.fold = "re-cut"

    # present: verbatim in the other file's section, once the other side's own
    # unexplained spans are taken out of it -- text inside one of those is that
    # span's business, not evidence that this one's words survived.
    for item in items:
        if item.fold is not None or HANGUL.search(item.text):
            continue
        other = b if item.side == "canonical" else a
        if item.where >= len(other.sections):
            continue
        section = " ".join(other.sections[item.where].split())
        for o in items:
            if o.side != item.side and o.where == item.where and o.fold is None:
                section = section.replace("`" + o.text + "`", " ")
        # ASCII boundaries only: a Korean particle is written straight onto an
        # identifier (`create()를`), and Hangul counts as \w.
        if re.search(r"(?<![A-Za-z0-9_.-])" + re.escape(item.text) + r"(?![A-Za-z0-9_-])",
                     section):
            item.fold = "present"

    def across(item):
        other = b if item.side == "canonical" else a
        return other.sections[item.where] if item.where < len(other.sections) else ""

    _pair_translated(items, across)
    return items


def drift_fences(a, b):
    """Every non-comment fence line one file has and the other does not, folded or residue.

    Fences are compared one against one, in order -- the structure check holds
    their count and languages level on a current pair. When the counts differ
    they are compared as one bag instead and the caller says so.
    """
    aligned = len(a.fences) == len(b.fences)

    def lines_of(fences, cut):
        out = []
        for index, (_, lines) in enumerate(fences):
            for number, raw in lines:
                if not raw.strip() or FENCE_COMMENT_LINE.match(raw):
                    continue
                text = TRAILING_COMMENT.sub("", raw) if cut else raw
                out.append((number, " ".join(text.split()), index if aligned else 0))
        return out

    whole = _unmatched(lines_of(a.fences, False), lines_of(b.fences, False))
    items = _unmatched(lines_of(a.fences, True), lines_of(b.fences, True))
    left = {(i.side, i.line) for i in items}
    folded = [i for i in whole if (i.side, i.line) not in left]
    for item in folded:
        item.fold = "comment"

    # A diagram is redrawn, so its lines differ wholesale and line against line
    # says nothing. What can still be asked of one line is whether its WORDS made
    # it across: a line of pure line-art has none, and a line whose every word
    # stands in the same fence on the other side was moved, not translated.
    diagrams, words, hangul_in, texts = set(), {}, set(), {}
    for side, fences in (("canonical", a.fences), ("translation", b.fences)):
        for index, (_, lines) in enumerate(fences):
            where = index if aligned else 0
            text = "\n".join(raw for _, raw in lines)
            if DIAGRAM.search(text):
                diagrams.add(where)
            words.setdefault((side, where), set()).update(DIAGRAM_WORD.findall(text))
            texts[(side, where)] = texts.get((side, where), "") + "\n" + text
            if HANGUL.search(text):
                hangul_in.add((side, where))

    def other_side(item):
        return "translation" if item.side == "canonical" else "canonical"

    for item in items:
        if item.where not in diagrams or HANGUL.search(item.text):
            continue
        if set(DIAGRAM_WORD.findall(item.text)) <= words.get((other_side(item), item.where), set()):
            item.fold = "redrawn"

    _pair_translated(items, lambda item: texts.get((other_side(item), item.where), ""))

    # What is left on the side that holds no Hangul, in a diagram whose other side
    # does: the redrawn labels, wrapped onto more lines than the labels they
    # render. The Hangul side's own plain lines are NOT folded this way -- that is
    # where a translated identifier would be standing.
    for item in items:
        other = "translation" if item.side == "canonical" else "canonical"
        if (item.fold is None and item.where in diagrams
                and (other, item.where) in hangul_in and (item.side, item.where) not in hangul_in):
            item.fold = "redrawn"
    return items + folded, aligned


class PairDrift:
    __slots__ = ("canonical", "translation", "spans", "fence_lines", "aligned",
                 "fence_counts", "languages")

    def residue(self):
        return [i for i in self.spans + self.fence_lines if i.fold is None]


def drift_of(canonical_text, translation_text, suffix):
    """The audit of one pair, from the two texts."""
    a, b = drift_layers(canonical_text), drift_layers(translation_text)
    d = PairDrift()
    d.spans = drift_spans(a, b, suffix)
    d.fence_lines, d.aligned = drift_fences(a, b)
    d.fence_counts = (len(a.fences), len(b.fences))
    d.languages = ([lang for lang, _ in a.fences], [lang for lang, _ in b.fences])
    return d


def _counts(items):
    out = {}
    for item in items:
        out[item.fold] = out.get(item.fold, 0) + 1
    return out


def _tally(counts):
    """`12 retarget, 2 translated` -- folds in their fixed order, the empty ones left out."""
    return ", ".join(f"{counts[f]} {f}" for f in DRIFT_FOLDS if counts.get(f)) or "nothing"


def drift_report(show_all, only):
    """Print the audit. Returns 0 whatever it finds, 2 when it was asked for a file it cannot read."""
    wanted = None
    if only:
        wanted = set()
        for arg in only:
            path = (ROOT / arg).resolve()
            if not path.is_file() or translation_suffix(path) is None:
                print(f"--drift: {arg} is not a translation (a *.<lang>.md file) in this repository")
                return 2
            wanted.add(path)

    audited, unreadable = [], []
    for translation in translations():
        if wanted is not None and translation not in wanted:
            continue
        canonical = canonical_of(frontmatter(translation))
        rel = translation.relative_to(ROOT).as_posix()
        if canonical is None or not canonical.exists():
            unreadable.append(rel)
            continue
        d = drift_of(canonical.read_text(encoding="utf-8", errors="replace"),
                     translation.read_text(encoding="utf-8", errors="replace"),
                     translation_suffix(translation))
        d.canonical, d.translation = canonical.relative_to(ROOT).as_posix(), rel
        audited.append(d)
    return drift_print(audited, unreadable, show_all)


def drift_print(audited, unreadable, show_all):
    """Print audited pairs. Returns 0: what an audit finds is for a person, not for an exit code."""
    spans = [i for d in audited for i in d.spans]
    lines = [i for d in audited for i in d.fence_lines]
    residue = [i for i in spans + lines if i.fold is None]
    with_residue = [d for d in audited if d.residue()]

    print(f"drift audit over {len(audited)} pair(s) -- a manual audit, never a gate: "
          "the exit code is 0 whatever is below")
    for label, items in (("inline code spans", spans), ("fence lines", lines)):
        pairs = sum(1 for d in audited
                    if (d.spans if items is spans else d.fence_lines))
        rest = sum(1 for i in items if i.fold is None)
        print(f"  {label:18} {len(items):4} item(s) on one side only, in {pairs} pair(s): "
              f"{_tally(_counts(items))} folded; {rest} residue")
    print(f"  residue to read: {len(residue)} item(s) in {len(with_residue)} pair(s)"
          + ("" if show_all else "   (--all prints the folded items too)"))

    for d in audited:
        items = d.spans + d.fence_lines
        left = d.residue()
        if not items or (not left and not show_all):
            continue
        folded = [i for i in items if i.fold is not None]
        print()
        print(f"{d.translation}  <-  {d.canonical}")
        print(f"  residue {len(left)} "
              f"({sum(1 for i in left if i in d.spans)} span(s), "
              f"{sum(1 for i in left if i in d.fence_lines)} fence line(s)); "
              f"folded {len(folded)}: {_tally(_counts(folded))}")
        if not d.aligned:
            print(f"  note: {d.fence_counts[0]} fence(s) in the canonical, {d.fence_counts[1]} "
                  "here -- fence lines were compared as one bag, not fence against fence. Run "
                  "the structure check first")
        for layer, kind in ((d.spans, "span "), (d.fence_lines, "fence")):
            for item in sorted(layer, key=lambda i: (i.fold is not None, i.fold or "",
                                                     i.where, i.side, i.line)):
                if item.fold is not None and not show_all:
                    continue
                path = d.canonical if item.side == "canonical" else d.translation
                tag = "RESIDUE   " if item.fold is None else f"{item.fold:10}"
                shown = f"`{item.text}`" if kind == "span " else f"| {item.text}"
                print(f"  {tag} {kind} only in {item.side:11} {path}:{item.line}  {shown}")

    for rel in unreadable:
        print()
        print(rel)
        print("  skipped   no canonical to compare against "
              "-- check-translation-staleness.py reports this one")

    print()
    if not residue:
        print("no residue: every difference in what must not be translated is one of the folds")
    else:
        print(f"{len(residue)} residue item(s). Each is text on one side only that no fold "
              "explains: read it against the other file at the line given. Most are legitimate "
              "-- a sentence restructured, an example reworded -- and the one this audit is for "
              "is an identifier, a path or a key that was translated.")
    if show_all:
        print()
        print("the folds:")
        for fold in DRIFT_FOLDS:
            print(f"  {fold:10} {DRIFT_WHY[fold]}")
    return 0


# --- the drift regression ---------------------------------------------------
#
# One small pair per case, never the tree's. Each case names the items that must
# be left as residue (by their text) and how many items each fold must take, so a
# fold that starts swallowing a translated identifier fails the case that plants
# one, and a fold that stops recognising what it is for fails its own.

def _doc(*lines):
    return "\n".join(lines) + "\n"


_F = "```"

DRIFT_CASES = [
    # (what, canonical, translation, residue texts, {fold: items})
    ("an identical pair",
     _doc("# T", "", "`a.b` 를 쓴다."), _doc("# T", "", "Use `a.b`."), [], {}),
    ("a span that runs over two lines is one span",
     _doc("# T", "", "값은 `[one < two", "< three]` 이다."),
     _doc("# T", "", "The value is `[one < two < three]`."), [], {}),
    ("an identifier translated inside a span",
     _doc("# T", "", "`maxIterations` 를 올린다."), _doc("# T", "", "Raise `max iterations`."),
     ["maxIterations", "max iterations"], {}),
    ("a config key translated while a retarget sits beside it",
     _doc("# T", "", "[`a.md`](a.md) 의 `aimon.llm.provider` 키."),
     _doc("# T", "", "The `aimon.llm.supplier` key of [`a.en.md`](a.en.md)."),
     ["aimon.llm.provider", "aimon.llm.supplier"], {"retarget": 2}),
    ("a link retargeted to the translation, anchor translated with it",
     _doc("# T", "", "[`a.md#절`](a.md#절) 과 [`b/c.md`](b/c.md)."),
     _doc("# T", "", "[`a.en.md#section`](a.en.md#section) and [`b/c.en.md`](b/c.en.md)."),
     [], {"retarget": 4}),
    ("a retarget to a different file is not a retarget",
     _doc("# T", "", "[`a.md`](a.md)"), _doc("# T", "", "[`b.en.md`](b.en.md)"),
     ["a.md", "b.en.md"], {}),
    ("Hangul in backticks and what it was rendered as",
     _doc("# T", "", "`<해시>` 를 적는다."), _doc("# T", "", "Write the `<hash>`."),
     [], {"translated": 2}),
    ("a Hangul span does not carry off a translated identifier in the same section",
     _doc("# T", "", "`<해시>` 와 `sessionId` 를 적는다."),
     _doc("# T", "", "Write the `<hash>` and the `session id`."),
     ["sessionId", "session id"], {"translated": 2}),
    ("one span cut into two",
     _doc("# T", "", "`merged.id == winner` 이면."), _doc("# T", "", "When `merged.id` is `winner`."),
     [], {"re-cut": 3}),
    ("the words are there without the backticks, a particle written onto them",
     _doc("# T", "", "AgentSetupFactory.create()를 따라간다."),
     _doc("# T", "", "Follow `AgentSetupFactory.create()`."), [], {"present": 1}),
    ("the same word in another section is not `present`",
     _doc("# A", "", "`offerAsync` 를 부른다.", "", "# B", "", "다른 절."),
     _doc("# A", "", "Call it.", "", "# B", "", "offerAsync is elsewhere."),
     ["offerAsync"], {}),

    ("a fence line whose identifier was translated",
     _doc("# T", "", _F + "yaml", "maxIterations: 3", _F),
     _doc("# T", "", _F + "yaml", "max_iterations: 3", _F),
     ["maxIterations: 3", "max_iterations: 3"], {}),
    ("a number changed inside a fence",
     _doc("# T", "", _F + "yaml", "timeout: 30", _F), _doc("# T", "", _F + "yaml", "timeout: 60", _F),
     ["timeout: 30", "timeout: 60"], {}),
    ("a trailing comment translated",
     _doc("# T", "", _F + "java", "run();   // 한 번만", _F),
     _doc("# T", "", _F + "java", "run(); // only once", _F), [], {"comment": 2}),
    ("a trailing comment translated AND the code beside it changed",
     _doc("# T", "", _F + "java", "run();   // 한 번만", _F),
     _doc("# T", "", _F + "java", "start(); // only once", _F), ["run();", "start();"], {}),
    ("a whole-line comment translated, and wrapped onto one more line",
     _doc("# T", "", _F + "java", "// 한 번만 실행한다", "run();", _F),
     _doc("# T", "", _F + "java", "// runs", "// only once", "run();", _F), [], {}),
    ("a Korean string literal translated",
     _doc("# T", "", _F + "java", 'log("시작");', _F), _doc("# T", "", _F + "java", 'log("start");', _F),
     [], {"translated": 2}),
    ("an identifier translated on a line that also holds Hangul",
     _doc("# T", "", _F, "Consumer (OrcaAgentExecutor ReAct 루프)", _F),
     _doc("# T", "", _F, "Consumer (the Orca agent executor ReAct loop)", _F),
     ["Consumer (OrcaAgentExecutor ReAct 루프)", "Consumer (the Orca agent executor ReAct loop)"], {}),
    ("the same line with the identifier kept and the words reordered",
     _doc("# T", "", _F, "Consumer (OrcaAgentExecutor ReAct 루프)", _F),
     _doc("# T", "", _F, "Consumer (the ReAct loop of OrcaAgentExecutor)", _F),
     [], {"translated": 2}),
    ("a diagram redrawn, its labels translated and its identifier kept",
     _doc("# T", "", _F, "┌────────────┐", "│ 세션 열기  │──▶ LiveSession", "└────────────┘", _F),
     _doc("# T", "", _F, "┌──────────────────┐", "│ open the session │──▶ LiveSession",
          "└──────────────────┘", _F),
     [], {"translated": 2, "redrawn": 4}),
    ("a diagram redrawn with an identifier translated in a plain line",
     _doc("# T", "", _F, "┌──────┐", "│ 열기 │", "└──────┘", "   ▼", "LiveSession", _F),
     _doc("# T", "", _F, "┌──────┐", "│ open │", "└──────┘", "   ▼", "live session", _F),
     ["LiveSession"], {"translated": 2, "redrawn": 1}),
    ("a number changed inside a diagram",
     _doc("# T", "", _F, "+-----+", "| 30s |", "+-----+", _F),
     _doc("# T", "", _F, "+-----+", "| 60s |", "+-----+", _F), ["| 30s |", "| 60s |"], {}),
]


def drift_self_test():
    """Hold each fold to what it is for, and the residue to what it must keep."""
    import contextlib
    import io

    print()
    print(f"drift audit over {len(DRIFT_CASES)} case(s)")
    failed = 0
    for what, canonical, translation, residue, folds in DRIFT_CASES:
        d = drift_of(canonical, translation, ".en")
        items = d.spans + d.fence_lines
        left = sorted(i.text for i in items if i.fold is None)
        counts = {k: v for k, v in _counts(items).items() if k is not None}
        good = left == sorted(residue) and counts == folds
        failed += not good
        print(f"  {'ok  ' if good else 'FAIL'} {what}")
        if not good:
            print(f"         residue {left}, folded {counts}; expected {sorted(residue)}, {folds}")

    # The reverse direction: an English canonical and its Korean translation.
    d = drift_of(_doc("# T", "", "See [`README.ko.md`](README.ko.md) and write the `<name>`."),
                 _doc("# T", "", "[`README.md`](README.md) 를 보고 `<이름>` 을 적는다."), ".ko")
    good = not d.residue() and _counts(d.spans) == {"retarget": 2, "translated": 2}
    failed += not good
    print(f"  {'ok  ' if good else 'FAIL'} a *.ko.md pair: the two files link each other, "
          "and the Hangul is on the translation's side")

    # A different number of fences: still audited, as one bag, and the report says so.
    d = drift_of(_doc("# T", "", _F, "a()", _F), _doc("# T", "", _F, "a()", _F, "", _F, "b()", _F), ".en")
    d.canonical, d.translation = "a.md", "a.en.md"
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = drift_print([d], [], False)
    good = (not d.aligned and [i.text for i in d.residue()] == ["b()"]
            and "compared as one bag" in out.getvalue())
    failed += not good
    print(f"  {'ok  ' if good else 'FAIL'} a pair whose fence counts differ is compared as one bag "
          "and says so")

    # The contract of the mode: residue is printed with file:line on its own side, and
    # the exit code is 0 anyway.
    d = drift_of(_doc("# T", "", "`maxIterations` 를 올린다."), _doc("# T", "", "Raise `max iterations`."),
                 ".en")
    d.canonical, d.translation = "a.md", "a.en.md"
    quiet, loud = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(quiet):
        code = drift_print([d], [], False)
    with contextlib.redirect_stdout(loud):
        drift_print([drift_named(_doc("# T", "", "`<해시>`"), _doc("# T", "", "`<hash>`"))], [], True)
    good = (code == 0 and "RESIDUE    span  only in canonical   a.md:3  `maxIterations`" in quiet.getvalue()
            and "only in translation a.en.md:3  `max iterations`" in quiet.getvalue())
    failed += not good
    print(f"  {'ok  ' if good else 'FAIL'} residue is printed with file:line on each side, and the "
          "exit code is 0")
    good = ("translated" in loud.getvalue() and "`<hash>`" in loud.getvalue()
            and "`<hash>`" not in _quiet_print(_doc("# T", "", "`<해시>`"), _doc("# T", "", "`<hash>`")))
    failed += not good
    print(f"  {'ok  ' if good else 'FAIL'} a folded item is printed under --all and not without it")

    if failed:
        print()
        print(f"{failed} drift case(s) failed. The folds are specified in the comment above "
              "CODE_SPAN; a fold may only hide an item it can show is not a translated "
              "identifier. See docs/design/documentation/translation-structure-check.md §9.")
        return 1
    print()
    print("every fold takes what it is for, and a translated identifier stays in the residue")
    return 0


def drift_named(canonical, translation):
    d = drift_of(canonical, translation, ".en")
    d.canonical, d.translation = "a.md", "a.en.md"
    return d


def _quiet_print(canonical, translation):
    import contextlib
    import io
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        drift_print([drift_named(canonical, translation)], [], False)
    return out.getvalue()


def main():
    if "--self-test" in sys.argv:
        # Every half always runs: stopping at the first would hide the others' verdicts.
        return max(self_test(), notation_self_test(), drift_self_test())

    known = {"--github", "--drift", "--all"}
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    paths = [a for a in sys.argv[1:] if not a.startswith("--")]
    unknown = [f for f in flags if f not in known]
    if unknown or (paths and "--drift" not in flags) or ("--all" in flags and "--drift" not in flags):
        print(__doc__.split("Usage:")[1].rstrip() if not unknown
              else f"unknown option {unknown[0]}\n" + __doc__.split("Usage:")[1].rstrip())
        return 2
    if "--drift" in flags:
        if "--github" in flags:
            print("--drift is an audit, not a gate: it annotates nothing, so --github does not apply")
            return 2
        return drift_report("--all" in flags, paths)
    github = "--github" in sys.argv

    in_repo = git("rev-parse", "--is-inside-work-tree") is not None
    shallow = is_shallow_clone() if in_repo else False

    try:
        site = site_tree()
    except ValueError as unreadable:
        # docs_tree.site_tree() refusing an exclude_docs it cannot match exactly.
        # check-doc-links.py exits 2 on the same refusal, with the same sentence.
        print(f"cannot tell which pages the site builds: {unreadable}")
        return 2

    findings = []
    matching = 0
    mismatched_pairs = set()
    exempt_axes_total = 0
    exempt_files = 0
    unknown_state = 0
    notation_defective = 0
    skipped = []

    for translation in translations():
        rel = translation.relative_to(ROOT).as_posix()
        meta = frontmatter(translation)
        canonical = canonical_of(meta)

        # A declaration defect is a finding about this pair, so it is collected
        # with the rest of them. Counting it separately printed "0 with findings"
        # one line above a MISMATCH and an exit 1.
        exempt, defects = read_exemptions(meta)
        pair_findings = [Finding(rel, "hard", d) for d in defects]
        # Notation is a declaration defect like the ones above: only whoever wrote
        # this front matter can create or clear it, so it fails whatever the pair's
        # state (design section 4.5).
        unreadable = notation_defects(translation, site, defects)
        pair_findings += [Finding(rel, "hard", d) for d in unreadable]
        notation_defective += bool(unreadable)
        if exempt:
            exempt_axes_total += len(exempt)
            exempt_files += 1

        if canonical is None or not canonical.exists():
            # Nothing to measure against: the pair names no canonical, or names
            # one that is not there. That is the staleness check's finding, and
            # it already fails the job on it.
            skipped.append(rel)
            findings.extend(pair_findings)
            continue

        verdict = pair_state(translation, meta) if in_repo else None
        state = verdict.state if verdict else None
        if state != FRESH:
            unknown_state += 1

        a, b = scan(canonical), scan(translation)

        # Pass 1 first: the heading axis is the alignment key, and the
        # positional vectors mean nothing until it lines up. Exempting headings
        # switches the second pass off for the same reason.
        heads_ok = all(x == y for axis, _, x, y in comparisons(a, b, positional=False)
                       if axis == "headings")
        positional = heads_ok and "headings" not in exempt

        for axis, what, x, y in comparisons(a, b, positional):
            if axis in exempt:
                continue
            if x == y:
                continue
            severity = ("advisory" if axis in ADVISORY_AXES
                        else "hard" if state == FRESH else "soft")
            pair_findings.append(Finding(
                rel, severity,
                f"{axis}: {describe(what, x, y, canonical.relative_to(ROOT).as_posix())}"))

        if "headings" in exempt:
            pair_findings.append(Finding(
                rel, "advisory",
                "headings is exempt, so the positional vectors (rows per table, "
                "list indents, per-section counts) are switched off too"))

        # Self-invalidation, last: a baseline nobody shrinks is a baseline
        # nobody reads. Only claimed when every comparison the axis owns was
        # actually run -- with the second pass skipped we have half a picture.
        for axis in exempt:
            owns_positional = axis != "headings"
            if owns_positional and not positional:
                continue
            if axis_matches(a, b, axis, positional):
                severity = "hard" if state == FRESH else "soft"
                pair_findings.append(Finding(
                    rel, severity,
                    f"{axis}: exempt, but the axis matches again -- delete the "
                    "exemption so it keeps saying something true"))

        if pair_findings:
            mismatched_pairs.add(rel)
            findings.extend(pair_findings)
        else:
            matching += 1

    # The other files mkdocs reads front matter from: site pages that are not
    # translations. No script reads their values, but the site still publishes the
    # block as page text when YAML refuses it, and a block this cannot decide about
    # is refused rather than waved through. They are not pairs, so they are counted
    # on their own line and never among the pairs above.
    canonical_pages = [p for p in markdown_files()
                       if translation_suffix(p) is None and site is not None and site.builds(p)]
    notation = {
        "translations": len(translations()),
        "pages": len(canonical_pages),
        "with_block": sum(1 for p in canonical_pages if has_front_matter(p)),
        "defective": notation_defective,
    }
    for page in canonical_pages:
        sentences = notation_defects(page, site)
        if sentences:
            notation["defective"] += 1
            rel = page.relative_to(ROOT).as_posix()
            findings.extend(Finding(rel, "hard", s) for s in sentences)

    return report(findings, matching, mismatched_pairs, skipped, exempt_axes_total,
                  exempt_files, unknown_state, shallow, in_repo, github, notation)


def axis_matches(a, b, axis, positional):
    return all(x == y for name, _, x, y in comparisons(a, b, positional) if name == axis)


def describe(what, x, y, canonical_rel):
    """Say where two readings part company, not just that they do.

    Naming the first differing entry is the whole reason the positional vectors
    exist: a bare pair of long lists tells you a section is wrong and leaves you
    to find which, and truncating them for display can print two lines that look
    identical.
    """
    if not isinstance(x, tuple):
        return f"{what} {x} in {canonical_rel}, {y} here"
    where = _first_difference(x, y)
    if len(x) != len(y):
        tail = f"; {where}" if where else ""
        return f"{what} -- {len(x)} entries in {canonical_rel}, {len(y)} here{tail}"
    return f"{what} -- same length, {where or 'but not equal'}"


def _first_difference(x, y):
    for i, (a, b) in enumerate(zip(x, y)):
        if a != b:
            return f"first difference at entry {i + 1}: {a} in the canonical, {b} here"
    return ""


def report(findings, matching, mismatched_pairs, skipped, exempt_axes_total,
           exempt_files, unknown_state, shallow, in_repo, github, notation):
    hard = [f for f in findings if f.severity == "hard"]
    soft = [f for f in findings if f.severity == "soft"]
    advisory = [f for f in findings if f.severity == "advisory"]
    total = matching + len(mismatched_pairs) + len(skipped)

    # The exemption total is printed on every run, zero included. Exemptions live
    # in the file they excuse rather than in one list, which buys locality and
    # costs the ability to see the whole set on one page; this line is half of
    # what is left of that, and `grep -rn structure_exempt docs/` is the other.
    print(f"checked {total} pair(s): {matching} structurally identical, "
          f"{len(mismatched_pairs)} with findings, {len(skipped)} not comparable, "
          f"{exempt_axes_total} axis exemption(s) in {exempt_files} file(s)")
    # Printed on every run for the reason the exemption total is: with nothing to
    # catch, a rule that stopped reading looks like a rule that read and found
    # nothing. The counts say what it was pointed at.
    print(f"front matter notation: {notation['translations']} translation(s) and "
          f"{notation['pages']} other site page(s) read; {notation['with_block']} of those "
          f"pages carry front matter; {notation['defective']} file(s) outside the subset")

    by_file = {}
    for f in findings:
        by_file.setdefault(f.rel, []).append(f)
    for rel in sorted(by_file):
        print()
        print(rel)
        for f in by_file[rel]:
            label = {"hard": "MISMATCH ", "soft": "behind   ", "advisory": "note     "}[f.severity]
            print(f"  {label} {f.text}")
            if github and f.severity == "hard":
                # Only the failing findings are annotated. GitHub truncates the
                # list, and a warning that never fails anything would crowd out
                # an error that does.
                print(f"::error file={rel}::{f.text}")

    for rel in skipped:
        if rel not in by_file:
            print()
            print(rel)
        print("  skipped   no canonical to compare against "
              "-- check-translation-staleness.py reports this one")

    if not findings and not skipped:
        print("every translation matches its canonical's structure")

    if soft or advisory:
        print()
        if soft:
            print(f"{len(soft)} finding(s) reported and not failed: the translation is "
                  "behind its canonical, or freshness could not be established. Failing "
                  "here would block whoever edited the canonical over a translation "
                  "backlog -- see check-translation-staleness.py's header.")
        if advisory:
            print(f"{len(advisory)} advisory note(s): never fail a run.")

    if not in_repo:
        print()
        print("not a git repository -- no pair could be shown to be current, so every "
              "structure finding above is reported rather than failed")
    elif shallow:
        print()
        excused = (" and every structure finding above is reported rather than failed"
                   if soft else "")
        print(f"this is a shallow clone -- source_commit cannot be resolved, so no pair "
              f"reads as current{excused}. Fetch the full history (git fetch --unshallow, "
              "or fetch-depth: 0 in Actions) to gate them.")
    elif unknown_state:
        print()
        print(f"{unknown_state} pair(s) are not level with their canonical, so their "
              "structure findings are reported rather than failed.")

    if hard:
        print()
        print(f"{len(hard)} finding(s) failed: a translation that claims to be current "
              "does not match its canonical's structure, or a front matter is written in a "
              "form mkdocs and these scripts do not read the same way. See CONTRIBUTING.md, "
              "\"When writing a translation\".")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
