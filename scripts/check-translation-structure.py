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
    in the corpus. tests/test_translation_structure.py pins this.
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

Usage:
    python3 scripts/check-translation-structure.py [--github]
    python3 scripts/check-translation-structure.py --self-test
"""
import re
import sys

from docs_tree import (FRESH, ROOT, canonical_of, frontmatter, git,
                       is_shallow_clone, pair_state, translations)

# --- the axes ---------------------------------------------------------------

HEADING = re.compile(r"^(#{1,6})\s+\S")
FENCE = re.compile(r"^\s*(```|~~~)(.*)$")
TABLE_ROW = re.compile(r"^\s*\|")
LIST_ITEM = re.compile(r"^(\s*)(?:[-*+]|\d+[.)])\s")
QUOTE = re.compile(r"^\s*>")
FENCE_HASH = re.compile(r"^\s*#")
FRONT_MATTER = re.compile(r"\A---\r?\n.*?\r?\n---\r?\n", re.DOTALL)

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
    """Vary the reading, hold the corpus still. Every case must still break."""
    pairs = list(_pairs())
    cases = []

    def count(fn):
        return sum(1 for c, t in pairs if fn(c) != fn(t))

    cases.append((
        "the six axes as specified", "0 pairs differ", 0,
        sum(1 for c, t in pairs
            if any(x != y for _, _, x, y in comparisons(scan(c), scan(t), True))),
        lambda n: n == 0))

    cases.append((
        "list-items without the whitespace after the marker",
        "17 of 32 pairs differ", 17,
        count(lambda p: sum(1 for l in _outside(p)[0] if LOOSE_LIST_ITEM.match(l))),
        lambda n: n > 0))

    cases.append((
        "quote blocks counted as '>' lines instead of runs",
        "10 of 32 pairs differ", 10,
        count(lambda p: sum(1 for l in _outside(p)[0] if QUOTE.match(l))),
        lambda n: n > 0))

    cases.append((
        "fence comments widened from '#' to '//' and '/*'",
        "3 of 32 pairs differ", 3,
        count(lambda p: sum(1 for l in _outside(p)[1] if WIDE_FENCE_COMMENT.match(l))),
        lambda n: n > 0))

    # Front matter is invisible to the ATX pattern -- `---` is not a heading to
    # it -- so this case has to use the reading that DOES see it to show why the
    # strip is load-bearing. Only translations carry front matter (32 of 32,
    # against 0 canonicals), so the moment anything reads its closing `---` as a
    # setext h2 the two sides part company everywhere at once.
    def setext_aware(path):
        lines = _outside(path, strip_front=False)[0]
        n = 0
        for i, line in enumerate(lines):
            if HEADING.match(line):
                n += 1
            elif i and SETEXT.match(line) and lines[i - 1].strip():
                n += 1
        return n

    cases.append((
        "front matter left in place, read by anything that sees setext headings",
        "all 32 pairs differ -- only translations have front matter", 32,
        count(setext_aware),
        lambda n: n > 0))

    print(f"pattern regression over {len(pairs)} pair(s)")
    failed = 0
    for name, recorded, expected, measured, ok in cases:
        good = ok(measured)
        failed += not good
        drift = "" if measured == expected else f"  (design recorded {expected})"
        print(f"  {'ok  ' if good else 'FAIL'} {name}")
        print(f"         measured {measured}; design recorded: {recorded}{drift}")
    if failed:
        print()
        print(f"{failed} case(s) failed: a reading this check is supposed to reject has "
              "stopped being rejected. The patterns in the module docstring are the "
              "specification -- see docs/design/documentation/"
              "translation-structure-check.md \u00a71.1.")
        return 1
    print()
    print("every wrong reading still breaks something -- the axes are not vacuous")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()
    github = "--github" in sys.argv

    in_repo = git("rev-parse", "--is-inside-work-tree") is not None
    shallow = is_shallow_clone() if in_repo else False

    findings = []
    matching = 0
    mismatched_pairs = set()
    exempt_axes_total = 0
    exempt_files = 0
    unknown_state = 0
    skipped = []

    for translation in translations():
        rel = translation.relative_to(ROOT).as_posix()
        meta = frontmatter(translation)
        canonical = canonical_of(meta)

        exempt, defects = read_exemptions(meta)
        for defect in defects:
            findings.append(Finding(rel, "hard", defect))
        if exempt:
            exempt_axes_total += len(exempt)
            exempt_files += 1

        if canonical is None or not canonical.exists():
            # Nothing to measure against: the pair names no canonical, or names
            # one that is not there. That is the staleness check's finding, and
            # it already fails the job on it.
            skipped.append(rel)
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

        pair_findings = []
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

    return report(findings, matching, mismatched_pairs, skipped, exempt_axes_total,
                  exempt_files, unknown_state, shallow, in_repo, github)


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
           exempt_files, unknown_state, shallow, in_repo, github):
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
        print("this is a shallow clone -- source_commit cannot be resolved, so no pair "
              "reads as current and every structure finding above is reported rather "
              "than failed. Fetch the full history (git fetch --unshallow, or "
              "fetch-depth: 0 in Actions) to gate them.")
    elif unknown_state:
        print()
        print(f"{unknown_state} pair(s) are not level with their canonical, so their "
              "structure findings are reported rather than failed.")

    if hard:
        print()
        print(f"{len(hard)} finding(s) failed: a translation that claims to be current "
              "does not match its canonical's structure, or its exemption front matter "
              "is malformed. See CONTRIBUTING.md, \"When writing a translation\".")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
