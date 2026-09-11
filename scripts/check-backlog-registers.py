#!/usr/bin/env python3
"""Check the backlog registers: one item per ID, and counts that agree with the items.

Every register in docs/backlog/ states its item count twice outside the items:
in its own title (`등록 항목 N건 (열림 X · 닫힘 Y)`) and in its row of the index
in docs/backlog/README.md (`| 항목 수 | 열림 | 닫힘 | 해소 |`). Both are copied
by hand from the item headings, and until this check nothing compared either copy
with the headings or noticed an ID written twice. On 2026-09-10 two branches each
added `## L-13` to the same register and git merged both item bodies without a
conflict; only the two count lines collided, which is the only reason anyone
noticed. README.md's rule seven (요약표는 항목이 아니다) and the dated notes under
its index record earlier drift, every case found by a person reading.

It fails on an ID repeated within a register, a title that disagrees with the
items, an index row that disagrees with them, a register with no row, and an ID
read in two registers.

Design decisions and the alternatives they rejected:
docs/design/documentation/backlog-register-check.md. This docstring is the
specification; `--self-test` pins it.

WHAT IS AN ITEM. Only ATX headings of level 2 or deeper, written at the start of
a line, outside fenced blocks and HTML comment blocks. Fences are
docs_tree.unfence's, the reading check-doc-links.py computes anchors from. A
comment block opens on a line that begins `<!--` after at most three spaces and
runs through the first line containing `-->`, the opening line included, or to
the end of the file. Neither puts a heading on the page -- a fence shows it as
code, a comment not at all -- so a commented-out item is removed, and the title
counts without it. No other kind of raw HTML block is recognised, so a heading
inside one is read -- decision 6 names them. Tables, blockquotes, list items and
prose never define an item. check-doc-links.py reads headings through the same
unfence but skips no comment block; where that makes the two checks read a
heading differently, and why it is left, is written down at
docs_tree.anchors_of.

    ID         [A-Z]{1,3}-<digits>, not followed by a letter, digit, `_` or `-`
               (so SBS-04b and P1-14 are not IDs)
    ID run     one ID, or IDs of the SAME prefix joined by `·`; the run stops at
               the first ID with another prefix
    item       heading text = an ID run, whitespace, then `—` or `·`
                 `B-19 · B-20 · B-24 — …`   three items
                 `B-7 · U-8 — …`            B-7 only
                 `B-25 · SBS-04b …`         B-25
    section    from an item heading to the next heading of the same or shallower
               level, or to the next item heading at any depth
    unread     a heading that begins with an ID once `*` `` ` `` `~` `_` and any
               leading non-alphanumerics are stripped (`### T-1 (원문)`,
               `## **L-16** — …`, `### ✅ B-35 — …`) but is not an item. A
               FINDING: a heading skipped without anyone being told is how an
               item goes missing, so it is not allowed to be quiet.
    displaced  an ATX heading behind indentation, `>` or a list marker, in any
               order (`   ## CE-3 — …`, `> ## CE-3 — …`, `- ## CE-3 — …`), whose
               text would be an item, an unread heading or a state record at the
               start of the line. A FINDING, reported as unread-heading: GitHub
               shows it as a heading, so skipping it would be quiet, and counting
               it would read an item out of a quote. It is never an item, a
               record or the end of a section.

WHAT IS A STATE. Read from the item heading after inline code is blanked and
`~~…~~` spans are deleted -- a word in backticks is an example, a struck word is
the state it used to have:

    explicit   each **bold** span whose text BEGINS with 열림, 닫힘, 완료 or 해소,
               followed by the end of the span, whitespace, or one of ( . , — · :
               The boundary is what keeps 해소된 and 닫힌 from reading as states.
    glyph      ✅ anywhere in the heading
    counts as  열림 -> 열림    닫힘, 완료, ✅ -> 닫힘    해소 -> 해소
               nothing at all -> 열림 (how llm-config-surface and
               reasoning-delta-stream write every open item)
    record     a non-item heading inside an item's section whose text -- inline
               code blanked, struck spans deleted, `**` removed -- begins with a
               state word under the same boundary (`### 닫힘 (2026-09-10, #73)`).
               The LAST record in the section wins, so a revival reads in order.

Qualifiers are never states. 결정 대기, 트리거 대기, 설계 대기, 소비자 대기, 막힘,
접힘 and 결정됨 are either text after a leading 열림 inside a bold span
(`**열림 · 결정 대기**`) or text the reader never looks at. B-10's
`**결정됨 (2026-08-05): 유지** … ✅` is 닫힘 by its glyph, which is how its title
and the README count it. 결정됨 in particular is not a kind of 열림: README rule
four's 결정됨이되 열림 is an item with work left and is written with 열림 or
nothing, and a decision that left nothing to do closes its item with ✅.

Three things are findings, not choices: two different explicit states in one
heading; ✅ with an explicit 열림 or 해소; an explicit heading state that differs
from the item's last record (one of the two copies was edited and not the other).
An item in any of those has no state, so that register's columns are not
compared -- the unfinished edit is reported once, as `state`, rather than three
times -- while its total still is.

TITLE. The register's first H1 must contain `등록 항목 N건 (…)`. The parenthetical
splits on `·`: `<열림|닫힘|해소> <n>` is a count and an omitted column is 0;
`<anything else> <n>` (완료 3, 접힘 1), a bare column name, or a column written
twice is a finding; any other text is a qualifier (`(열림 1 · 결정 대기)`).

INDEX. The single table in docs/backlog/README.md, outside fences, whose header
names 항목 수, 열림, 닫힘 and 해소. Columns are found by header name, so a column
added in front of them moves nothing. Rows are the lines starting with `|` right
after the separator row; the table ends at the first line that does not. The link
in a row's first cell names its register. Every register has exactly one row,
every row names a register, and every count cell is an integer.

THE DECISIONS, each with its reason:

1. Heading shape -- the grammar above, not a heuristic. Ten of eleven registers
   already wrote every item as an ID-first heading when this landed. "Any ID
   anywhere in a heading" reads `#### 1차 전제 정정 … — B-20 의 …` as a second
   B-20, and B-22's heading, which names the B-21 revived under it, as a second
   B-21; making open items state themselves would turn every branch that adds an
   item in llm-config-surface's stateless style red.
   One register is written in another shape throughout and is CITED by that
   shape, so it has a declared reading (READINGS): interrupt-open-items.md's
   items are its level-2 `## N.` headings with N >= 1 (`## 0.` is its correction
   log), because interrupt.md and routing.md cite its §2,
   inbox-collect-durability.md its §5, architecture-review-open-items.md its 1번,
   and README.md its 2번 and 3번. The declaration invalidates itself: a missing
   file, or one with ID item headings, is a finding. A level-2 `## N.` added there
   for another purpose -- `## 6. 관련`, the way other registers title a related-
   documents section -- is counted as an item and fails loudly as a title
   mismatch; number that section differently or not at all.
2. State vocabulary -- README.md's (rules four and seven, the index columns),
   read from bold and ✅ only. Every state written in an item heading when this
   landed was one of those, and the plain-text occurrences were traps: B-15's
   `*(절반 완료 …)*` is on an OPEN item. Two unstruck states in one heading is an
   unfinished edit -- T-1's `~~열림~~ **닫힘**` is how this repository changes a
   state -- and picking one would be the checker deciding what the author meant.
3. Cross-register IDs -- checked. Design documents and CHANGELOG cite items by a
   bare `L-13`; this guarantees a bare ID names at most one register. The next
   number for a prefix is the next one NO register with that prefix uses, and
   the finding names it -- a per-register "next free ID" is exactly how
   openai-model-capabilities-open-items.md's next item would become a second
   `L-2`. The `L-1` that predates this check is declared in SHARED_IDS instead
   of renamed: its citations are bare, so rewriting them means deciding which
   `L-1` each one meant, which is the ambiguity itself. That declaration
   invalidates itself once the collision is gone. Numbered items are not IDs for
   this rule; they are always cited together with their register.
4. The dated notes under the index quote old row values on purpose and are never
   rows: they are `>` blockquotes after the table has ended, with the values in
   inline code inside sentences. A `>` line never continues the table and a pipe
   inside a sentence never starts one; `--self-test` inserts both.
5. A register that cannot be read FAILS. There is no exemption. Every finding
   exits 1, and every run prints every register with its tally, so a register
   that was never read is visible as the line it did not produce. A finding
   that is reported without failing is a green run that means nothing --
   check-translation-staleness.py learned that with UNRESOLVABLE, 19 of 32
   translations unresolvable while the job stayed green -- and the fix for an
   unreadable register (reorder a heading, write the title) always belongs to
   whoever is editing it, which is the argument that makes UNRESOLVABLE exit 1.
6. What the page shows -- a register's only rendering is GitHub's (mkdocs.yml
   excludes docs/backlog/), and the reading follows it for comment blocks and
   displaced headings, not for the other raw HTML blocks (below). A comment
   block is not read: a heading no reader can see is not an item a reader can
   count, and counting it would make the title count it too. A heading written
   after indentation, `>` or a list marker is not read either, but when its text
   matters it FAILS, as `displaced` above. Counting it would read an item out of
   a quote, and would make indentation the one non-grammar shape that is counted
   where `## **L-16** — …` is not; leaving it unread and unreported inverts the
   verdict -- a title that counts what the page shows goes red, and one that
   forgot it goes green. Setext and raw-HTML headings are left to BLIND SPOT
   instead, because neither can be told from one line: a setext underline turns
   the whole paragraph above it into the heading, and an HTML heading can carry
   attributes or span lines, so a line pattern would report some of them and
   stay quiet on the rest.
   The raw HTML blocks it does not follow: a comment is one of the seven kinds
   of HTML block in CommonMark 0.31.2 §4.6, and the only kind recognised here.
   Inside the other six the page shows no heading, and this reads an ATX line
   as one and counts it -- kind 1, `<pre`, `<script`, `<style` or `<textarea`,
   through a line holding one of their closing tags; 3, `<?`, through `?>`; 4,
   `<!` and a letter (`<!DOCTYPE`), through `>`; 5, `<![CDATA[`, through `]]>`;
   6, a block-level tag such as `<details>`, `<div>` or `<table>`, to the next
   blank line; 7, any other complete tag alone on its line where it does not
   interrupt a paragraph, to the next blank line. There the verdict is the
   inverted one above: a title that counts what the page shows goes red, and
   one that counts the hidden heading goes green. They are not skipped because
   hiding one needs to know whether a fence or a container holds its start, and
   unfence pairs fence markers by position -- a start it exposes inside a real
   fence would hide headings the page shows, the edge SHARP EDGES names for
   comments, carried to six more kinds. A heading under `<details>` shows on the
   page once a blank line follows the opening tags, which ends the block.

WHERE IT RUNS: a second step of the `docs-links` job -- the check, then
`--self-test`. The branch ruleset requires jobs by name, so a job of its own
would run without gating a merge, and renaming `docs-links` would break a
required context. That job is the pure-text one on a shallow checkout, and this
reads no history; `translations` checks out full history and states its
severities in terms of freshness, so a backlog failure there would name the
wrong domain. The two lines run in the opposite order from
check-translation-structure.py's, on purpose: this self-test aims its mutations
at the real tree, and under the runner's `bash -e` the first failing line ends
the step. With the check first, a red step always names what is wrong with the
tree, and the self-test only ever runs on a tree the check has passed -- where a
failure can only mean the checker.

NOT CHECKED, on purpose:
  * section subtotals inside a register (`## 1. … — 7건 (…)`) -- a third copy of
    the count, kept by one register, whose §5 subtotal is non-standard on
    purpose: it leaves out a dissolved item and names 대기, which is not a
    column. Checking subtotals means rewriting that recorded subtotal or
    exempting a section, and decision 5 allows no exemption; whoever edits a
    section recounts its subtotal by hand
  * derived tables that list IDs with states -- rule seven's derived views;
    items are headings, never table cells
  * whether a heading's state is TRUE. Agreement is duplication, not
    verification (rule seven): green means both copies agree with what the
    headings say, and a count that disagrees can be wrong on either side
  * docs/design/backlog/ -- a different kind of backlog, and it has no counts
  * number reuse and order -- a single tree cannot show them

BLIND SPOT. An item whose heading does not begin with what this grammar reads as
an ID is not counted: no ID at all (`## 새 항목 — …`), a prefix the ID pattern does
not match (`## ABCD-1 — …`, `## l-16 — …`), or, in the numbered register, a number
that is not a level-2 `## N.` (`### 6. …`). Neither is an item written as
something other than an ATX `#` line: a setext heading (`CE-3 — …` underlined
with `---` or `===`) or raw HTML (`<h2>CE-3 — …</h2>`). If its author also left
the title alone, the two mistakes cancel. The checker counts headings, not
intentions.

SHARP EDGES, all loud. A sub-heading that happens to begin with a state word
(`### 해소 조건`) is read as a record -- a title or state finding names the line,
and the fix is to reword the heading. Because the unread test strips leading
punctuation along with glyphs, a heading that opens with a parenthesised ID
(`### (L-3 참고) 배경`) is an unread-heading; start it with a word instead.
Displacement is read from the line alone, so indentation of any width counts --
a heading in a list item's continuation sits as far in as the item's text (two
spaces under `- `, three under `1. `), one in an indented code block four, and
both are reported. Fences and comments are read from the line alone too. A fence
marker is recognised after any indentation but not after `>` or on a list
marker's own line, and the next marker closes it whatever its character, length
or info string. So a fence in a list item's continuation hides the heading in
it, as the page does; four-space markers in an indented code block hide a
heading the page shows; and the closer of a fence opened on a list marker's own
line (`- ```…`) opens a fence the page does not have. A comment is recognised
after up to three spaces, in a list item's continuation too, but not after `>`,
on a list marker's own line or after four spaces. The lines inside a fence or
comment that is not recognised are read as if it were not there, so a heading in
one is reported when it is itself behind `>` or indentation; an item-heading
example belongs in a fence opened neither after `>` nor on a list marker's line.
And fences are read before comments: a comment block holding an unmatched fence
marker hides everything after it, and so does a `<!--` inside a real fence that
unfence has closed early or not seen open -- the two fence cases above. A
heading hidden that way shows up as a title that counts more items than are
read.

Usage:
    python3 scripts/check-backlog-registers.py [--github]
    python3 scripts/check-backlog-registers.py --self-test
"""
import collections
import pathlib
import posixpath
import re
import sys

from docs_tree import ATX_HEADING, ROOT, translation_suffix, unfence

BACKLOG = "docs/backlog"
INDEX = BACKLOG + "/README.md"
SELF = "scripts/check-backlog-registers.py"

# --- the grammar ------------------------------------------------------------

ID = re.compile(r"[A-Z]{1,3}-\d+(?![A-Za-z0-9_-])")
RUN_JOIN = re.compile(r"\s*·\s*")
AFTER_RUN = re.compile(r"\s+[—·]")
NUMBER = re.compile(r"(\d+)\.(?=\s|$)")
UNWRAP = re.compile(r"[*`~_]")

STATE_CLASS = {"열림": "열림", "닫힘": "닫힘", "완료": "닫힘", "해소": "해소"}
STATE_WORD = re.compile(r"(열림|닫힘|완료|해소)(?=$|[\s(.,—·:])")
STATE_WORD_UNBOUNDED = re.compile(r"(열림|닫힘|완료|해소)")
GLYPH = "✅"
BOLD = re.compile(r"\*\*(.+?)\*\*")
STRIKE = re.compile(r"~~.*?~~")
# A code span is delimited by a run of backticks of matching length, as in
# check-doc-links.py.
INLINE_CODE = re.compile(r"(`+)(?:(?!\1).)*\1")

TITLE = re.compile(r"등록 항목\s*(\d+)\s*건(?:\s*\(([^)]*)\))?")
TITLE_COUNT = re.compile(r"(.*?)\s*(\d+)")
COUNT_COLUMNS = ("열림", "닫힘", "해소")
TOTAL_COLUMN = "항목 수"
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)")
SEPARATOR = re.compile(r"^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)*\|?\s*$")
QUOTE_MARKERS = re.compile(r"^(?:>\s*)+")
# A comment block as CommonMark defines one: a line that begins `<!--` after at
# most three spaces, through the first line containing `-->` -- the opening line
# included, which is how cmark reads `<!-- note -->` as a block of one line.
COMMENT_OPEN = re.compile(r"^ {0,3}<!--")
COMMENT_CLOSE = "-->"
# Indentation, `>` markers and list markers, in any order, in front of a line.
DISPLACED = re.compile(r"^(?:[ \t]*(?:>|[-*+](?=[ \t])|\d{1,9}[.)](?=[ \t])))*[ \t]*")

ID_READING = "id"
NUMBERED = "numbered"

# --- the declarations -------------------------------------------------------
#
# Both lists are checked against the tree on every run, and an entry that has
# stopped being true is a finding. A declaration nobody retires is one nobody
# reads.

# interrupt-open-items.md numbers its items (`## 1.` … `## 5.`, with `## 0.` for
# its correction log) and five documents cite them by that number -- `§2`, `§5`,
# `1번`. Giving the items IDs would give each a second name while the citations
# keep using the first, so the register is read the way it is written.
READINGS = {
    "interrupt-open-items.md": NUMBERED,
}

# L-1 was an item in both of these before this check existed. The citations are
# bare -- CHANGELOG and design documents write `L-1` and qualify it by hand -- so
# renaming either one means reading every citation to decide which L-1 it meant,
# which is the ambiguity itself. New items take the next L number neither uses.
SHARED_IDS = {
    "L-1": ("llm-config-surface-open-items.md", "openai-model-capabilities-open-items.md"),
}

# --- reading rules as switches ----------------------------------------------
#
# Production runs with every rule on. `--self-test` turns one off at a time and
# asserts that the reading changes: a rule that can be switched off without
# changing anything is a rule that has already been deleted from the code.

RULES = ("unfence", "uncomment", "code", "strike", "bold_only", "boundary", "records",
         "same_prefix", "unwrap", "displaced", "table_rows_only", "skip_zero")


class Rules:
    __slots__ = RULES

    def __init__(self, off=()):
        for name in RULES:
            setattr(self, name, name not in off)


SPEC = Rules()


class Finding:
    __slots__ = ("kind", "path", "line", "text")

    def __init__(self, kind, path, line, text):
        self.kind = kind            # duplicate-id | title | index | state |
        self.path = path            # unread-heading | shared-id | reading | layout
        self.line = line
        self.text = text


class Item:
    __slots__ = ("ids", "line", "level", "heading_state", "problem", "records", "state")

    def __init__(self, ids, line, level, heading_state, problem):
        self.ids = ids
        self.line = line
        self.level = level
        self.heading_state = heading_state  # 열림 | 닫힘 | 해소 | None (nothing explicit)
        self.problem = problem              # why the heading's state is unreadable, or None
        self.records = []                   # (line, state) of `### 닫힘 (…)` headings
        self.state = None                   # what the item counts as; None = unreadable


class Register:
    __slots__ = ("name", "path", "reading", "title_line", "title_n", "title_counts",
                 "items", "total", "tally", "unreadable")

    def __init__(self, name, path, reading):
        self.name = name
        self.path = path
        self.reading = reading
        self.title_line = None
        self.title_n = None
        self.title_counts = {}
        self.items = []
        self.total = 0
        self.tally = dict.fromkeys(COUNT_COLUMNS, 0)
        self.unreadable = 0


class IndexRow:
    __slots__ = ("line", "cells", "spans", "register", "values")

    def __init__(self, line, cells, spans):
        self.line = line
        self.cells = cells
        self.spans = spans
        self.register = None        # register name, once the row is known to name one
        self.values = {}


class IndexTable:
    __slots__ = ("header_line", "columns", "rows")

    def __init__(self, header_line, columns, rows):
        self.header_line = header_line
        self.columns = columns      # header name -> cell index
        self.rows = rows


class Result:
    __slots__ = ("findings", "registers", "table", "translations")

    def __init__(self, findings, registers, table, translations):
        self.findings = findings
        self.registers = registers
        self.table = table
        self.translations = translations

    @property
    def rows(self):
        return self.table.rows if self.table else []


# --- reading a register -----------------------------------------------------

def blank_code(text):
    return INLINE_CODE.sub(lambda m: " " * len(m.group(0)), text)


def uncomment(text):
    """Blank HTML comment blocks, preserving line numbering -- see WHAT IS AN ITEM."""
    out, inside = [], False
    for line in text.split("\n"):
        if inside or COMMENT_OPEN.match(line):
            inside = COMMENT_CLOSE not in line
            out.append("")
        else:
            out.append(line)
    return "\n".join(out)


def prefix_of(item_id):
    return item_id.rsplit("-", 1)[0]


def id_run(text, rules):
    """The IDs an item heading names, or None when `text` is not an item heading."""
    m = ID.match(text)
    if not m:
        return None
    ids, pos = [m.group(0)], m.end()
    while True:
        join = RUN_JOIN.match(text, pos)
        nxt = ID.match(text, join.end()) if join else None
        if not nxt:
            break
        if rules.same_prefix and prefix_of(nxt.group(0)) != prefix_of(ids[0]):
            break
        ids.append(nxt.group(0))
        pos = nxt.end()
    return ids if AFTER_RUN.match(text, pos) else None


def unwrapped(text, rules):
    """`text` with emphasis, code and strike markers and any leading glyph removed."""
    if not rules.unwrap:
        return text
    t = UNWRAP.sub("", text)
    i = 0
    while i < len(t) and not t[i].isalnum():
        i += 1
    return t[i:]


def numbered_item(level, text, rules):
    if level != 2:
        return None
    m = NUMBER.match(text)
    if not m or (int(m.group(1)) == 0 and rules.skip_zero):
        return None
    return str(int(m.group(1)))


def heading_state(text, rules):
    """(state, problem) as the item heading writes it -- see WHAT IS A STATE."""
    t = blank_code(text) if rules.code else text
    if rules.strike:
        t = STRIKE.sub(" ", t)
    word = STATE_WORD if rules.boundary else STATE_WORD_UNBOUNDED
    if rules.bold_only:
        spans = (word.match(b.group(1).strip()) for b in BOLD.finditer(t))
        found = [w.group(1) for w in spans if w]
    else:
        found = [w.group(1) for w in word.finditer(t.replace("**", ""))]
    classes = [c for c in COUNT_COLUMNS if c in {STATE_CLASS[w] for w in found}]
    glyph = GLYPH in t

    if len(classes) > 1:
        return None, (f"names two states, {' and '.join(classes)} -- strike the one it no "
                      "longer has (`~~열림~~ **닫힘**`)")
    if glyph and classes and classes[0] != "닫힘":
        return None, f"has ✅ and **{classes[0]}** -- ✅ means closed, so one of them is wrong"
    if classes:
        return classes[0], None
    if glyph:
        return "닫힘", None
    return None, None


def record_state(text, rules):
    t = blank_code(text) if rules.code else text
    if rules.strike:
        t = STRIKE.sub(" ", t)
    t = t.replace("**", "").strip()
    m = (STATE_WORD if rules.boundary else STATE_WORD_UNBOUNDED).match(t)
    return STATE_CLASS[m.group(1)] if m else None


def label(register, item_id):
    return item_id if register.reading == ID_READING else f"§{item_id}"


def report_displaced(reg, current, lineno, line, rules, add):
    """An unread-heading for a heading behind indentation, `>` or a list marker whose text matters.

    See `displaced` in the grammar. The heading is read for this and nothing else:
    it never becomes an item, a record or the end of a section, so the reading
    around it is the one the ATX lines at the start of a line give.
    """
    prefix = DISPLACED.match(line).group(0)
    m = ATX_HEADING.match(line[len(prefix):]) if prefix else None
    if not m:
        return
    level, htext = len(m.group(1)), m.group(2)
    bare = unwrapped(htext, rules)
    if ID.match(bare):
        what = "an ID"
        fix = ("start the heading at the beginning of the line; a quote or list item that "
               "only cites an item must not begin its heading with the ID (`### 원문 — T-1`), "
               "and an item-heading example belongs in a fence")
    elif reg.reading == NUMBERED and numbered_item(level, bare, rules) is not None:
        what = "a number"
        fix = ("start the heading at the beginning of the line; a quote or list item that "
               "only cites a numbered item must not begin its heading with the number")
    elif (current is not None and level > current.level and rules.records
          and record_state(htext, rules)):
        what = f"a state word inside {' · '.join(label(reg, i) for i in current.ids)}'s section"
        fix = ("start the record at the beginning of the line; a quoted or listed heading "
               "inside an item's section must not begin with a state word")
    else:
        return
    where = [w for w, present in (("inside a blockquote", ">" in prefix),
                                  ("inside a list item",
                                   any(c not in " \t>" for c in prefix))) if present]
    add("unread-heading", reg.path, lineno,
        f"`{line.strip()}` begins with {what} but is {' and '.join(where) or 'indented'}, "
        f"and no heading is read there -- {fix}")


def read_register(path, text, reading, rules, add):
    reg = Register(posixpath.basename(path), path, reading)
    body = unfence(text) if rules.unfence else "\n".join(text.splitlines())
    if rules.uncomment:
        body = uncomment(body)
    title_text = None
    id_headings = []
    current = None

    for lineno, line in enumerate(body.split("\n"), 1):
        m = ATX_HEADING.match(line)
        if not m:
            if rules.displaced:
                report_displaced(reg, current, lineno, line, rules, add)
            continue
        level, htext = len(m.group(1)), m.group(2)

        if level == 1 and reg.title_line is None:
            reg.title_line, title_text = lineno, htext
            current = None
            continue

        as_id = id_run(htext, rules) if level >= 2 else None
        if reading == NUMBERED:
            number = numbered_item(level, htext, rules)
            ids = [number] if number is not None else None
            if as_id is not None:
                id_headings.append(lineno)
                unread = False
            else:
                wrapped = (level == 2 and not NUMBER.match(htext)
                           and numbered_item(2, unwrapped(htext, rules), rules) is not None)
                unread = ids is None and (bool(ID.match(unwrapped(htext, rules))) or wrapped)
        else:
            ids = as_id
            unread = ids is None and bool(ID.match(unwrapped(htext, rules)))

        if ids is not None:
            state, problem = heading_state(htext, rules)
            current = Item(ids, lineno, level, state, problem)
            reg.items.append(current)
            continue

        if unread:
            if ID.match(unwrapped(htext, rules)):
                what = "an ID"
                fix = ("an item heading is the bare ID, whitespace, then `—` or `·` "
                       "(`### B-35 — <title>`); a heading that only mentions an item "
                       "must not start with its ID (`### 원문 — T-1`)")
            else:
                what = "a number"
                fix = "a numbered item is a level-2 `## N. <title>` with the number bare"
            add("unread-heading", path, lineno,
                f"`{'#' * level} {htext}` begins with {what} but is not an item heading -- {fix}")

        if current is not None and level <= current.level:
            current = None
        elif current is not None and rules.records:
            state = record_state(htext, rules)
            if state:
                current.records.append((lineno, state))

    if id_headings:
        add("reading", path, id_headings[0],
            f"declared a {reading} reading in {SELF}, but :{id_headings[0]} is an ID item "
            "heading -- finish converting the register to ID headings and delete its READINGS "
            "entry, or give that heading a number")

    for item in reg.items:
        last = item.records[-1] if item.records else None
        name = " · ".join(label(reg, i) for i in item.ids)
        if item.problem:
            add("state", path, item.line, f"{name}: the heading {item.problem}")
        elif item.heading_state and last and last[1] != item.heading_state:
            add("state", path, item.line,
                f"{name}: the heading says {item.heading_state}, the record at :{last[0]} says "
                f"{last[1]} -- one of the two was updated and the other was not")
        else:
            item.state = item.heading_state or (last[1] if last else "열림")
        for _ in item.ids:
            reg.total += 1
            if item.state is None:
                reg.unreadable += 1
            else:
                reg.tally[item.state] += 1

    read_title(reg, title_text, add)
    return reg


def read_title(reg, text, add):
    if text is None:
        add("title", reg.path, None,
            "no H1 -- a register's title states its count: `# <name> — 등록 항목 N건 (열림 X · 닫힘 Y)`")
        return
    m = TITLE.search(text)
    if not m:
        add("title", reg.path, reg.title_line,
            "the title does not state `등록 항목 N건 (열림 X · 닫힘 Y)` -- it is one of the two copies "
            "of the count this check compares")
        return
    reg.title_n = int(m.group(1))
    for part in (p.strip() for p in (m.group(2) or "").split("·")):
        if not part:
            continue
        count = TITLE_COUNT.fullmatch(part)
        if count and count.group(1):
            column, n = count.group(1), int(count.group(2))
            if column not in COUNT_COLUMNS:
                add("title", reg.path, reg.title_line,
                    f"`{part}` counts a column the index does not have -- the columns are "
                    f"{', '.join(COUNT_COLUMNS)}; a qualifier such as 결정 대기 is written "
                    "without a number")
            elif column in reg.title_counts:
                add("title", reg.path, reg.title_line, f"{column} is written twice in the title")
            else:
                reg.title_counts[column] = n
        elif part in COUNT_COLUMNS:
            add("title", reg.path, reg.title_line, f"`{part}` names a column without a number")


# --- reading the index ------------------------------------------------------

def table_line(line, rules):
    s = line.strip()
    if not rules.table_rows_only:
        s = QUOTE_MARKERS.sub("", s)
    return s if s.startswith("|") else None


def split_cells(line):
    """Cells of one table line and the span each occupies in it.

    Inline code is masked first, so a pipe inside backticks does not split a cell.
    Spans index the line as given, which is what lets `--self-test` rewrite one cell.
    """
    masked = INLINE_CODE.sub(lambda m: " " * len(m.group(0)), line).replace("\\|", "  ")
    bars = [i for i, c in enumerate(masked) if c == "|"]
    spans = [(a + 1, b) for a, b in zip(bars, bars[1:])]
    if bars and masked[bars[-1] + 1:].strip():
        spans.append((bars[-1] + 1, len(masked)))
    return [masked[a:b] for a, b in spans], spans


def read_index_tables(text, rules):
    lines = unfence(text).split("\n") if rules.unfence else text.splitlines()
    tables, i = [], 0
    while i < len(lines):
        head = table_line(lines[i], rules)
        sep = table_line(lines[i + 1], rules) if i + 1 < len(lines) else None
        if head is None or sep is None or not SEPARATOR.match(sep):
            i += 1
            continue
        names = [c.strip() for c in split_cells(lines[i])[0]]
        rows, j = [], i + 2
        while j < len(lines) and table_line(lines[j], rules) is not None:
            cells, spans = split_cells(lines[j])
            rows.append(IndexRow(j + 1, cells, spans))
            j += 1
        if all(n in names for n in (TOTAL_COLUMN,) + COUNT_COLUMNS):
            tables.append(IndexTable(i + 1, {n: names.index(n) for n in names}, rows))
        i = j
    return tables


# --- the check --------------------------------------------------------------

_DECLARATION_LINES = {}


def declaration_line(name):
    """Where a constant is declared in this file, so its finding can point at it."""
    if name not in _DECLARATION_LINES:
        _DECLARATION_LINES[name] = None
        try:
            source = pathlib.Path(__file__).read_text(encoding="utf-8")
            for n, line in enumerate(source.splitlines(), 1):
                if line.startswith(name + " = "):
                    _DECLARATION_LINES[name] = n
                    break
        except OSError:
            pass
    return _DECLARATION_LINES[name]


def tally_text(counts):
    return " · ".join(f"{c} {counts.get(c, 0)}" for c in COUNT_COLUMNS)


def check(docs, readings=READINGS, shared=SHARED_IDS, rules=SPEC):
    """Every finding for `docs`, a {repo-relative path: text} map of docs/backlog/*.md.

    A pure function: the main run and every self-test case call it.
    """
    findings = []

    def add(kind, path, line, text):
        findings.append(Finding(kind, path, line, text))

    names = sorted(p for p in docs if posixpath.dirname(p) == BACKLOG and p.endswith(".md"))
    if not names:
        add("layout", BACKLOG, None,
            f"no markdown in {BACKLOG}/ -- the registers this check reads are gone")
        return Result(findings, [], None, [])
    if INDEX not in docs:
        add("layout", INDEX, None,
            "missing -- it holds the index every register is counted against")

    translations = [p for p in names if translation_suffix(pathlib.PurePosixPath(p))]
    registers = [read_register(p, docs[p], readings.get(posixpath.basename(p), ID_READING),
                               rules, add)
                 for p in names if p != INDEX and p not in translations]
    by_name = {r.name: r for r in registers}

    for name, reading in sorted(readings.items()):
        if name not in by_name:
            add("reading", SELF, declaration_line("READINGS"),
                f"READINGS declares a {reading} reading for {name}, which is not a register in "
                f"{BACKLOG}/ -- delete the entry")

    # What number an ID should have taken is a question about every register
    # with that prefix, never about one: that is the collision #88 is about.
    holders = collections.defaultdict(list)
    highest = collections.defaultdict(int)
    for reg in registers:
        if reg.reading != ID_READING:
            continue
        for item in reg.items:
            for item_id in item.ids:
                holders[item_id].append((reg, item.line))
                prefix, n = item_id.rsplit("-", 1)
                highest[prefix] = max(highest[prefix], int(n))

    def next_free(item_id):
        prefix = prefix_of(item_id)
        return (f"{prefix}-{highest[prefix] + 1}, "
                f"the next number no register with prefix {prefix} uses")

    for reg in registers:
        seen = {}
        for item in reg.items:
            for item_id in item.ids:
                if item_id not in seen:
                    seen[item_id] = item.line
                    continue
                fix = (f"give the later item {next_free(item_id)}" if reg.reading == ID_READING
                       else "renumber the later item")
                add("duplicate-id", reg.path, item.line,
                    f"{label(reg, item_id)} is also the item heading at :{seen[item_id]} -- {fix}")

    for item_id, places in sorted(holders.items()):
        if len({r.name for r, _ in places}) < 2:
            continue
        declared = set(shared.get(item_id, ()))
        for reg, line in places:
            if reg.name in declared:
                continue
            others = ", ".join(f"{r.name}:{n}" for r, n in places if r is not reg)
            add("shared-id", reg.path, line,
                f"{item_id} is also an item in {others} -- a bare {item_id} must name one "
                f"register; renumber the newer item to {next_free(item_id)}")
    for item_id, declared in sorted(shared.items()):
        where = sorted({r.name for r, _ in holders.get(item_id, [])})
        if len(declared) < 2 or any(n not in where for n in declared):
            add("shared-id", SELF, declaration_line("SHARED_IDS"),
                f"SHARED_IDS declares {item_id} shared by {' and '.join(declared)}, but it is "
                f"an item in {' and '.join(where) or 'no register'} -- delete the entry so the "
                "declaration keeps saying something true")

    for reg in registers:
        if reg.title_n is None:
            continue
        said = f"등록 항목 {reg.title_n}건 ({tally_text(reg.title_counts)})"
        read = f"{reg.total} ({tally_text(reg.tally)})"
        if reg.unreadable:
            if reg.title_n != reg.total:
                add("title", reg.path, reg.title_line,
                    f"{said} but {reg.total} item(s) are read -- columns not compared while "
                    f"{reg.unreadable} item state(s) are unreadable")
        elif reg.title_n != reg.total or any(reg.title_counts.get(c, 0) != reg.tally[c]
                                             for c in COUNT_COLUMNS):
            add("title", reg.path, reg.title_line, f"{said} but the items read {read}")

    table = None
    if INDEX in docs:
        tables = read_index_tables(docs[INDEX], rules)
        if not tables:
            add("index", INDEX, None,
                f"no table whose header names {TOTAL_COLUMN}, {', '.join(COUNT_COLUMNS)} -- "
                "the index every register is counted against")
        else:
            table = tables[0]
            for extra in tables[1:]:
                add("index", INDEX, extra.header_line,
                    f"a second table whose header names {TOTAL_COLUMN} and the count columns (the "
                    f"first is at :{table.header_line}) -- the index is one table")
            compare_index(table, registers, by_name, add)

    return Result(findings, registers, table, translations)


def compare_index(table, registers, by_name, add):
    covered = {}
    for row in table.rows:
        link = LINK.search(row.cells[0]) if row.cells else None
        if not link:
            add("index", INDEX, row.line, "the row's first cell has no link to a register")
            continue
        target = link.group(1).split("#", 1)[0]
        resolved = posixpath.normpath(posixpath.join(BACKLOG, target))
        name = posixpath.basename(resolved)
        if posixpath.dirname(resolved) != BACKLOG or name not in by_name:
            add("index", INDEX, row.line,
                f"the row links {target}, which is not a register in {BACKLOG}/")
            continue
        if name in covered:
            add("index", INDEX, row.line,
                f"a second row for {name} (the first is at :{covered[name]})")
            continue
        covered[name] = row.line

        bad = []
        for column in (TOTAL_COLUMN,) + COUNT_COLUMNS:
            at = table.columns[column]
            cell = row.cells[at].strip() if at < len(row.cells) else ""
            if re.fullmatch(r"\d+", cell):
                row.values[column] = int(cell)
            else:
                bad.append(f"{column} is {cell!r}" if cell else f"{column} is empty")
        if bad:
            add("index", INDEX, row.line,
                f"{name}: {', '.join(bad)} -- every count cell is an integer")
            continue
        row.register = name

        reg = by_name[name]
        said = [row.values[TOTAL_COLUMN]] + [row.values[c] for c in COUNT_COLUMNS]
        read = [reg.total] + [reg.tally[c] for c in COUNT_COLUMNS]
        if reg.unreadable:
            if said[0] != read[0]:
                add("index", INDEX, row.line,
                    f"{name} row says {said[0]} item(s), {read[0]} are read -- columns not "
                    f"compared while {reg.unreadable} item state(s) are unreadable")
        elif said != read:
            add("index", INDEX, row.line,
                f"{name} row says {' | '.join(map(str, said))}, "
                f"items read {' | '.join(map(str, read))}")

    for reg in registers:
        if reg.name not in covered:
            add("index", INDEX, table.header_line,
                f"no row for {reg.name} -- a register the index does not list is one nothing "
                "compares")


# --- reporting --------------------------------------------------------------

def load_tree():
    base = ROOT / BACKLOG
    if not base.is_dir():
        return {}
    return {p.relative_to(ROOT).as_posix(): p.read_text(encoding="utf-8", errors="replace")
            for p in sorted(base.glob("*.md"))}


def report(result, github):
    regs = result.registers
    print(f"checked {len(regs)} register(s), {len(result.rows)} index row(s), "
          f"{sum(r.total for r in regs)} item(s)")
    width = max((len(r.name) for r in regs), default=0)
    for r in regs:
        note = f"   {r.reading} reading" if r.reading != ID_READING else ""
        if r.unreadable:
            note += f"   {r.unreadable} item state(s) unreadable"
        print(f"  {r.name:<{width}}  {r.total:>3}  ({tally_text(r.tally)}){note}")
    shared = ", ".join(sorted(SHARED_IDS))
    print(f"declared: {len(SHARED_IDS)} shared ID(s){f' ({shared})' if shared else ''}, "
          f"{len(READINGS)} numbered reading(s), "
          f"{len(result.translations)} translation file(s) skipped")

    by_path = collections.defaultdict(list)
    for f in result.findings:
        by_path[f.path].append(f)
    for path in sorted(by_path):
        print()
        print(path)
        for f in sorted(by_path[path], key=lambda f: f.line or 0):
            where = f":{f.line}" if f.line else ""
            print(f"  {where:<6} {f.kind:<15} {f.text}")
            if github:
                # Every finding fails the job, so every one is an error. `line=` is
                # added because every finding here has one -- a heading, a title or
                # a row -- where the other doc checkers annotate by file.
                at = f",line={f.line}" if f.line else ""
                print(f"::error file={f.path}{at}::{f.kind}: {f.text}")

    if result.findings:
        print()
        print(f"{len(result.findings)} finding(s) failed. Which copy is wrong -- the count or "
              "the heading's state -- is decided by reading the items, not by copying the "
              "numbers above: see docs/backlog/README.md, rule seven.")
        return 1
    print()
    print("every register's title and index row agree with its items, and every ID names one item")
    return 0


# --- the self-test ----------------------------------------------------------
#
# The main run on a tree with nothing to catch prints the same green line as a
# check that quietly stopped reading. This holds the corpus still and varies one
# thing at a time: it breaks the real tree one way per case, and turns each
# reading rule off against a synthetic register. Each case counts the findings a
# mutation ADDS against the unmutated run, compared by (kind, path) with line
# numbers left out because an inserted line moves every later one.
#
# Targets are chosen by rule, never by name, and never where the unmutated run
# already has a finding -- Tree says why. With that, drift in a register or a row
# leaves the self-test green and the main run, which owns the verdict on the real
# tree, reports it once; and the drift case breaks the tree exactly where the
# cases would otherwise aim, so the rule cannot be dropped without it going red.
# A case with nothing left to aim at fails as "could not construct"; it is never
# skipped.

class CannotConstruct(Exception):
    pass


def counted(findings, path=None):
    return collections.Counter((f.kind, f.path) for f in findings if path is None or f.path == path)


def added(before, after, path=None):
    return counted(after.findings, path) - counted(before.findings, path)


def describe(diff):
    return ", ".join(f"{kind} @ {posixpath.basename(path)}" + (f" x{n}" if n > 1 else "")
                     for (kind, path), n in sorted(diff.items())) or "nothing"


def verdict(diff, must, allowed=None):
    got = {kind for kind, _ in diff}
    allowed = must if allowed is None else allowed
    return must <= got and got <= allowed, "added " + describe(diff)


def lines_of(docs, path):
    return docs[path].split("\n")


def with_lines(docs, path, lines):
    out = dict(docs)
    out[path] = "\n".join(lines)
    return out


def replace_line(docs, path, lineno, new):
    lines = lines_of(docs, path)
    lines[lineno - 1] = new
    return with_lines(docs, path, lines)


def insert_after(docs, path, lineno, new):
    lines = lines_of(docs, path)
    lines[lineno:lineno] = new
    return with_lines(docs, path, lines)


def replace_cells(line, spans, replacements):
    for index in sorted(replacements, reverse=True):
        a, b = spans[index]
        line = line[:a] + f" {replacements[index]} " + line[b:]
    return line


def index_table(docs):
    tables = read_index_tables(docs[INDEX], SPEC) if INDEX in docs else []
    if not tables or not tables[0].rows:
        raise CannotConstruct("no index table with rows in " + INDEX)
    return tables[0]


def append_row(docs, first_cell, counts):
    table = index_table(docs)
    values = dict(zip((TOTAL_COLUMN,) + COUNT_COLUMNS, counts))
    names = sorted(table.columns, key=table.columns.get)
    cells = [first_cell if i == 0 else str(values.get(n, "셀프 테스트")) for i, n in enumerate(names)]
    return insert_after(docs, INDEX, table.rows[-1].line, ["| " + " | ".join(cells) + " |"])


def add_register(docs, name, text, counts):
    path = f"{BACKLOG}/{name}"
    if path in docs:
        raise CannotConstruct(f"{path} already exists")
    out = dict(docs)
    out[path] = text
    return append_row(out, f"[`{name}`]({name})", counts)


def synthetic_register(p):
    return "\n".join([
        "# 셀프 테스트 — 등록 항목 8건 (열림 3 · 닫힘 4 · 해소 1)",
        "",
        f"## {p}-1 — 상태를 적지 않은 항목",
        "### 해소된 까닭",
        f"## {p}-2 — 상태가 바뀐 항목 · ~~**열림**~~ **닫힘 (날짜)**",
        f"## {p}-3 — 곁말은 읽지 않는다 *(절반 완료 2026-08-05 · 착수 전)*",
        f"## {p}-4 — 하위 제목이 닫는다",
        "### 닫힘 (날짜)",
        f"## {p}-5 · {p}-6 — 한 제목에 둘 ✅",
        f"## {p}-7 · U-8 — 코드 안의 `**닫힘**` 은 예시다 · **열림 · 결정 대기**",
        f"## {p}-8 — 전제가 없던 항목 · **해소**",
        f"### 원문 — {p}-1",
        "",
        "<!--",
        f"## {p}-1 — 주석 안의 제목은 항목이 아니다",
        "-->",
        "",
        "```markdown",
        f"## {p}-1 — 펜스 안의 예시는 항목이 아니다",
        "```",
        "",
    ])


SYNTHETIC_NUMBERED = "\n".join([
    "# 셀프 테스트 번호 — 등록 항목 2건 (열림 1 · 닫힘 1)",
    "",
    "## 0. 착수하며 정정한 것",
    "### 0.1 첫 정정",
    "## 1. 닫힌 항목 — **닫힘** *(날짜)*",
    "## 2. 열린 항목 — **열림 (트리거 대기)**",
    "",
])


class Tree:
    """The real tree as the cases that aim mutations at it see it.

    Only registers with no finding at all, and index rows with no finding whose
    register has none either, are targets. Where the unmutated run already has a
    finding, a mutation aimed there can add nothing: that finding absorbs the new
    one (a title already wrong is still one title finding), or an unreadable item
    state has switched the register's column comparisons off, so moving an item
    between columns changes nothing the check compares. A case aimed there would
    go red on a tree whose only problem is the one the main run reports.
    """

    __slots__ = ("docs", "base", "registers", "rows")

    def __init__(self, docs, base):
        self.docs = docs
        self.base = base
        flagged = {f.path for f in base.findings}
        flagged_rows = {f.line for f in base.findings if f.path == INDEX}
        self.registers = [r for r in base.registers if r.path not in flagged]
        self.rows = [row for row in base.rows
                     if row.register and row.line not in flagged_rows
                     and f"{BACKLOG}/{row.register}" not in flagged]

    def duplicate_target(self):
        for reg in self.registers:
            singles = [item for item in reg.items if len(item.ids) == 1]
            if (reg.reading == ID_READING and len(singles) >= 2
                    and singles[0].ids != singles[1].ids
                    and singles[1].ids[0] not in SHARED_IDS):
                return reg, singles[0], singles[1]
        raise CannotConstruct("no register without a finding has two single-ID item headings")

    def title_target(self, nonzero):
        for reg in self.registers:
            if reg.title_n is None:
                continue
            if nonzero and not any(reg.title_counts.get(c) for c in COUNT_COLUMNS):
                continue
            return reg
        raise CannotConstruct("no register without a finding has a readable title"
                              + (" with a column above 0" if nonzero else ""))

    def row_target(self, nonzero):
        for row in self.rows:
            if not nonzero or any(row.values[c] for c in COUNT_COLUMNS):
                return row
        raise CannotConstruct("no index row without a finding names a register that has none"
                              + (" and counts above 0" if nonzero else ""))

    def id_target(self):
        for reg in self.registers:
            for item in reg.items:
                if reg.reading == ID_READING and item.ids[0] not in SHARED_IDS:
                    return reg, item.ids[0]
        raise CannotConstruct("no register without a finding has an ID item")

    def note(self, docs):
        """`docs` with a blockquoted row, and a sentence quoting a row, under the index."""
        target = self.row_target(nonzero=False).register
        return insert_after(docs, INDEX, index_table(docs).rows[-1].line, [
            f"> | [`{target}`]({target}) | 출처 | 99 | 99 | 0 | 0 |",
            "이 줄은 행이 아니다 — 옛 값 `34 | 4 | 26 | 4` 를 문장 안에서 인용할 뿐이다.",
        ])


def move_one(counts):
    source = next(c for c in COUNT_COLUMNS if counts.get(c))
    return source, next(c for c in COUNT_COLUMNS if c != source)


def real_tree_cases(tree):
    """The cases that aim at the real tree, as [(name, run)] with run() -> (ok, detail).

    A: the three required failures. B: a dated note stays unread, and the
    shared-ID rule and its declaration fire. They take the tree as an argument
    rather than closing over it so that the drift case can run every one of them
    again on a tree it has broken.
    """
    real, base = tree.docs, tree.base

    def duplicated_id():
        reg, first, second = tree.duplicate_target()
        old, new = second.ids[0], first.ids[0]
        line = lines_of(real, reg.path)[second.line - 1]
        changed = re.sub(r"^(\s*#{1,6}\s+)" + re.escape(old) + r"(?![A-Za-z0-9_-])",
                         lambda m: m.group(1) + new, line, count=1)
        after = check(replace_line(real, reg.path, second.line, changed))
        ok, detail = verdict(added(base, after), {"duplicate-id"})
        return ok, f"{reg.name}:{second.line} {old} -> {new}; {detail}"

    def title_total():
        reg = tree.title_target(nonzero=False)
        line = lines_of(real, reg.path)[reg.title_line - 1]
        m = TITLE.search(line)
        changed = line[:m.start(1)] + str(reg.title_n + 1) + line[m.end(1):]
        after = check(replace_line(real, reg.path, reg.title_line, changed))
        ok, detail = verdict(added(base, after), {"title"})
        return ok, f"{reg.name}:{reg.title_line} {reg.title_n} -> {reg.title_n + 1}; {detail}"

    def title_split():
        reg = tree.title_target(nonzero=True)
        line = lines_of(real, reg.path)[reg.title_line - 1]
        m = TITLE.search(line)
        source, dest = move_one(reg.title_counts)
        counts = dict(reg.title_counts)
        counts[source] -= 1
        counts[dest] = counts.get(dest, 0) + 1
        parts, seen = [], set()
        for part in (p.strip() for p in m.group(2).split("·")):
            column = TITLE_COUNT.fullmatch(part)
            if column and column.group(1) in counts:
                part = f"{column.group(1)} {counts[column.group(1)]}"
                seen.add(column.group(1))
            parts.append(part)
        parts += [f"{c} {counts[c]}" for c in COUNT_COLUMNS if c in counts and c not in seen]
        changed = line[:m.start(2)] + " · ".join(parts) + line[m.end(2):]
        after = check(replace_line(real, reg.path, reg.title_line, changed))
        ok, detail = verdict(added(base, after), {"title"})
        return ok, f"{reg.name}:{reg.title_line} {source} -1, {dest} +1; {detail}"

    def index_total():
        row = tree.row_target(nonzero=False)
        line = lines_of(real, INDEX)[row.line - 1]
        column = base.table.columns[TOTAL_COLUMN]
        changed = replace_cells(line, row.spans, {column: row.values[TOTAL_COLUMN] + 1})
        after = check(replace_line(real, INDEX, row.line, changed))
        ok, detail = verdict(added(base, after), {"index"})
        return ok, f"README.md:{row.line} {row.register} {TOTAL_COLUMN} +1; {detail}"

    def index_split():
        row = tree.row_target(nonzero=True)
        line = lines_of(real, INDEX)[row.line - 1]
        source, dest = move_one(row.values)
        columns = base.table.columns
        changed = replace_cells(line, row.spans, {columns[source]: row.values[source] - 1,
                                                  columns[dest]: row.values[dest] + 1})
        after = check(replace_line(real, INDEX, row.line, changed))
        ok, detail = verdict(added(base, after), {"index"})
        return ok, f"README.md:{row.line} {row.register} {source} -1, {dest} +1; {detail}"

    def missing_row():
        row = tree.row_target(nonzero=False)
        lines = lines_of(real, INDEX)
        del lines[row.line - 1]
        ok, detail = verdict(added(base, check(with_lines(real, INDEX, lines))), {"index"})
        return ok, f"README.md:{row.line} {row.register} row deleted; {detail}"

    def dated_note():
        return verdict(added(base, check(tree.note(real))), set())

    def shared_id():
        reg, item_id = tree.id_target()
        text = f"# 셀프 테스트 — 등록 항목 1건 (열림 1)\n\n## {item_id} — 다른 등록부의 번호\n"
        docs = add_register(real, "zz-self-test-shared.md", text, (1, 1, 0, 0))
        ok, detail = verdict(added(base, check(docs)), {"shared-id"})
        return ok, f"{item_id} (from {reg.name}) in a new register; {detail}"

    def stale_shared():
        reg, item_id = tree.id_target()
        other = next((r.name for r in base.registers if r.name != reg.name), None)
        if other is None:
            raise CannotConstruct("fewer than two registers")
        declared = dict(SHARED_IDS, **{item_id: (reg.name, other)})
        ok, detail = verdict(added(base, check(real, shared=declared)), {"shared-id"})
        return ok, f"{item_id} declared shared by {reg.name} and {other}; {detail}"

    return [
        ("duplicated ID -- a later item heading takes an earlier item's ID", duplicated_id),
        ("wrong title total -- 등록 항목 N건 becomes N+1", title_total),
        ("wrong title split -- one item moves between two columns, the total unchanged",
         title_split),
        ("wrong index total -- a row's 항목 수 + 1", index_total),
        ("wrong index split -- one item moves between two count cells, the total unchanged",
         index_split),
        ("missing row -- a register's index row is deleted", missing_row),
        ("a dated note is not the row -- a blockquoted row and a quoted row under the table",
         dated_note),
        ("an ID read in two registers", shared_id),
        ("a shared-ID declaration whose collision does not exist", stale_shared),
    ]


def drift_cases(tree):
    """The real tree broken exactly where real_tree_cases aims, as [(label, docs, touched)].

    Three kinds of drift, each put on every register and row those cases would
    pick: an item heading that names two states (so the register's columns are no
    longer compared), a title one higher, an index row one higher. `touched` is the
    (path, line) of each place the drift went, line None for a whole register.
    """
    real, base = tree.docs, tree.base
    rows = {row.line: row for row in (tree.row_target(False), tree.row_target(True))}
    paths = {tree.duplicate_target()[0].path, tree.title_target(False).path,
             tree.title_target(True).path, tree.id_target()[0].path}
    paths |= {f"{BACKLOG}/{row.register}" for row in rows.values()}

    two_states = title_up = row_up = real
    on_states, on_titles, on_rows = [], [], []
    for reg in (r for r in base.registers if r.path in paths):
        if reg.items:
            item = reg.items[0]
            line = lines_of(two_states, reg.path)[item.line - 1]
            two_states = replace_line(two_states, reg.path, item.line, line + " · **열림** **해소**")
            on_states.append((reg.path, None))
        line = lines_of(title_up, reg.path)[reg.title_line - 1]
        m = TITLE.search(line)
        title_up = replace_line(title_up, reg.path, reg.title_line,
                                line[:m.start(1)] + str(reg.title_n + 1) + line[m.end(1):])
        on_titles.append((reg.path, None))
    column = base.table.columns[TOTAL_COLUMN]
    for row in rows.values():
        line = lines_of(row_up, INDEX)[row.line - 1]
        higher = replace_cells(line, row.spans, {column: row.values[TOTAL_COLUMN] + 1})
        row_up = replace_line(row_up, INDEX, row.line, higher)
        on_rows.append((INDEX, row.line))
    return [("an item heading naming two states", two_states, on_states),
            ("a title one higher", title_up, on_titles),
            ("an index row one higher", row_up, on_rows)]


def self_test():
    real = load_tree()
    base = check(real)
    tree = Tree(real, base)
    cases = real_tree_cases(tree)

    def case(name):
        def register(run):
            cases.append((name, run))
            return run
        return register

    @case("drift where the cases above aim leaves them green -- that drift is the main run's")
    def _():
        report = []
        for label, docs, touched in drift_cases(tree):
            drifted = Tree(docs, check(docs))
            found = {(f.path, f.line) for f in drifted.base.findings}
            missed = [p for p, n in touched
                      if not any(fp == p and (n is None or fn == n) for fp, fn in found)]
            if missed:
                return False, f"{label}: the drift left {missed[0]} without a finding"
            again = real_tree_cases(drifted)
            red = []
            for name, run in again:
                try:
                    ok = run()[0]
                except CannotConstruct:
                    ok = False
                if not ok:
                    red.append(name.split(" -- ")[0])
            if red:
                return False, f"{label}: {', '.join(red)} went red"
            report.append(f"{label} at {len(touched)} place(s): {len(again)} ok")
        return True, "; ".join(report)

    # B, continued: numbered-reading declarations, on synthetic registers.

    readings_n = dict(READINGS, **{"zz-self-test-numbered.md": NUMBERED})
    try:
        docs_n = add_register(real, "zz-self-test-numbered.md", SYNTHETIC_NUMBERED, (2, 1, 1, 0))
        base_n = check(docs_n, readings=readings_n)
    except CannotConstruct as e:
        docs_n = base_n = e
    path_n = f"{BACKLOG}/zz-self-test-numbered.md"

    def numbered():
        if isinstance(docs_n, CannotConstruct):
            raise docs_n
        return docs_n, base_n

    @case("a numbered-reading declaration on a register that has ID item headings")
    def _():
        docs, before = numbered()
        docs = with_lines(docs, path_n, lines_of(docs, path_n) + ["## ZN-1 — 번호 대신 ID · **열림**"])
        return verdict(added(before, check(docs, readings=readings_n)), {"reading"})

    @case("a numbered-reading declaration for a file that does not exist")
    def _():
        declared = dict(READINGS, **{"zz-no-such-register.md": NUMBERED})
        return verdict(added(base, check(real, readings=declared)), {"reading"})

    # C. Every reading rule is load-bearing, against a synthetic register.

    used = {prefix_of(i) for reg in base.registers if reg.reading == ID_READING
            for item in reg.items for i in item.ids}
    prefix = next((p for p in ("Z", "Y", "X", "W", "V", "Q") if p not in used), None)
    path_s = f"{BACKLOG}/zz-self-test.md"
    try:
        if prefix is None:
            raise CannotConstruct("every candidate prefix for the synthetic register is taken")
        docs_s = add_register(real, "zz-self-test.md", synthetic_register(prefix), (8, 3, 4, 1))
        base_s = check(docs_s)
    except CannotConstruct as e:
        docs_s = base_s = e

    def synthetic():
        if isinstance(docs_s, CannotConstruct):
            raise docs_s
        return docs_s, base_s

    @case("the synthetic register reads clean under the specified grammar")
    def _():
        _, before = synthetic()
        return verdict(added(base, before), set())

    switches = [
        ("strike", "struck spans deleted", f"{prefix}-2 reads 열림 and 닫힘", {"state"}),
        ("bold_only", "states read from bold only", f"{prefix}-3 reads 절반 완료 as 닫힘", {"title"}),
        ("code", "inline code blanked", f"{prefix}-7 reads the `**닫힘**` example", {"state"}),
        ("records", "state records", f"{prefix}-4 reads 열림", {"title"}),
        ("boundary", "the word boundary", f"`### 해소된 까닭` records {prefix}-1 as 해소", {"title"}),
        ("same_prefix", "same-prefix ID runs", f"{prefix}-7 · U-8 reads two items", {"title"}),
        ("unfence", "fenced blocks ignored", f"the fenced `## {prefix}-1` is a second {prefix}-1",
         {"duplicate-id"}),
        ("uncomment", "HTML comment blocks ignored",
         f"the commented-out `## {prefix}-1` is a second {prefix}-1", {"duplicate-id"}),
    ]
    for switch, rule, wrong, must in switches:
        # Only the finding the wrong reading names is required. Switching a rule
        # off changes how the real registers read too (`B-7 · U-8` in the starter
        # yields a U-8), so whatever else lands on the synthetic register is not
        # this case's business.
        def run(switch=switch, wrong=wrong, must=must):
            docs, before = synthetic()
            diff = added(before, check(docs, rules=Rules(off=(switch,))), path_s)
            return must <= {kind for kind, _ in diff}, f"off: {wrong}; added {describe(diff)}"
        cases.append((f"rule: {rule}", run))

    @case("rule: markup stripped before the unread test")
    def _():
        docs, _ = synthetic()
        docs = with_lines(docs, path_s, lines_of(docs, path_s) + [f"## **{prefix}-9** — 굵게 감싼 번호"])
        lost = added(check(docs, rules=Rules(off=("unwrap",))), check(docs), path_s)
        ok, detail = verdict(lost, {"unread-heading"})
        return ok, (f"off: `## **{prefix}-9** — …` is skipped without a word; "
                    f"the specified reading {detail}")

    @case("rule: a heading behind indentation, `>` or a list marker is reported")
    def _():
        docs, _ = synthetic()
        docs = with_lines(docs, path_s, lines_of(docs, path_s) + [f"   ## {prefix}-9 — 들여 쓴 항목"])
        lost = added(check(docs, rules=Rules(off=("displaced",))), check(docs), path_s)
        ok, detail = verdict(lost, {"unread-heading"})
        return ok, (f"off: `   ## {prefix}-9 — …` is skipped without a word; "
                    f"the specified reading {detail}")

    @case("rule: only table lines are rows (`>` lines are not)")
    def _():
        docs = tree.note(real)
        off = check(docs, rules=Rules(off=("table_rows_only",)))
        ok, detail = verdict(added(check(docs), off, INDEX), {"index"})
        return ok, f"off: the blockquoted note is a second row for its register; {detail}"

    @case("rule: `## 0.` is not an item in a numbered reading")
    def _():
        docs, before = numbered()
        after = check(docs, readings=readings_n, rules=Rules(off=("skip_zero",)))
        ok, detail = verdict(added(before, after, path_n), {"title"})
        return ok, f"off: the correction log counts as a third item; {detail}"

    @case("an ID-first heading that is not an item -- `### Z-1 (원문)`")
    def _():
        docs, before = synthetic()
        lines = lines_of(docs, path_s)
        i = lines.index(f"### 원문 — {prefix}-1")
        after = check(replace_line(docs, path_s, i + 1, f"### {prefix}-1 (원문)"))
        return verdict(added(before, after), {"unread-heading"})

    # D. Every other finding kind fires, on the synthetic register or the index.

    def mutate_synthetic(old, new):
        docs, before = synthetic()
        lines = lines_of(docs, path_s)
        return before, replace_line(docs, path_s, lines.index(old) + 1, new)

    title_s = "# 셀프 테스트 — 등록 항목 8건 (열림 3 · 닫힘 4 · 해소 1)"
    kinds = [
        ("a heading state that disagrees with its record",
         f"## {prefix}-4 — 하위 제목이 닫는다", f"## {prefix}-4 — 하위 제목이 닫는다 · **열림**", {"state"}),
        ("✅ together with an explicit 해소",
         f"## {prefix}-8 — 전제가 없던 항목 · **해소**", f"## {prefix}-8 — 전제가 없던 항목 · **해소** ✅", {"state"}),
        ("a title column the index does not have (완료 0)",
         title_s, title_s.replace("해소 1)", "해소 1 · 완료 0)"), {"title"}),
        ("a bare column name in the title",
         title_s, title_s.replace("해소 1)", "해소 1 · 닫힘)"), {"title"}),
        ("a column written twice in the title",
         title_s, title_s.replace("해소 1)", "해소 1 · 열림 0)"), {"title"}),
        ("a title with no count", title_s, "# 셀프 테스트", {"title"}),
    ]
    for name, old, new, must in kinds:
        def run(old=old, new=new, must=must):
            before, docs = mutate_synthetic(old, new)
            return verdict(added(before, check(docs)), must)
        cases.append((name, run))

    def row_of_synthetic(docs):
        table = index_table(docs)
        row = next((r for r in table.rows if "zz-self-test.md" in r.cells[0]), None)
        if row is None:
            raise CannotConstruct("the synthetic register has no row")
        return table, row

    @case("a count cell that is not an integer")
    def _():
        docs, before = synthetic()
        table, row = row_of_synthetic(docs)
        line = lines_of(docs, INDEX)[row.line - 1]
        changed = replace_cells(line, row.spans, {table.columns["열림"]: "—"})
        after = check(replace_line(docs, INDEX, row.line, changed))
        return verdict(added(before, after), {"index"})

    rows = [
        ("a second row for one register", "[`zz-self-test.md`](zz-self-test.md)", (8, 3, 4, 1)),
        ("a row for a file that does not exist",
         "[`zz-no-such-register.md`](zz-no-such-register.md)", (0, 0, 0, 0)),
        ("a row for a file that is not a register", "[`README.md`](README.md)", (0, 0, 0, 0)),
        ("a row with no link", "링크 없음", (0, 0, 0, 0)),
    ]
    for name, first_cell, counts in rows:
        def run(first_cell=first_cell, counts=counts):
            docs, before = synthetic()
            return verdict(added(before, check(append_row(docs, first_cell, counts))), {"index"})
        cases.append((name, run))

    @case("two tables that both look like the index")
    def _():
        docs, before = synthetic()
        docs = with_lines(docs, INDEX, lines_of(docs, INDEX) + [
            "", f"| 문서 | {TOTAL_COLUMN} | 열림 | 닫힘 | 해소 |", "|---|---|---|---|---|", ""])
        return verdict(added(before, check(docs)), {"index"})

    @case("a numbered item written twice (`## 2.`)")
    def _():
        docs, before = numbered()
        docs = with_lines(docs, path_n, lines_of(docs, path_n) + ["## 2. 같은 번호 — **열림**"])
        return verdict(added(before, check(docs, readings=readings_n)), {"duplicate-id"},
                       {"duplicate-id", "title", "index"})

    @case("no README.md in docs/backlog/")
    def _():
        docs = {p: t for p, t in real.items() if p != INDEX}
        return verdict(added(base, check(docs)), {"layout"})

    @case("no docs/backlog/ at all")
    def _():
        return verdict(added(base, check({})), {"layout"})

    # E. What the page shows (#102). Each shape is appended to the synthetic
    # register twice: with its title left alone, and with the title counting the
    # appended item. The row stays at 8, so a shape that is not read adds no
    # `index` finding and one that is read adds it to both, and both verdicts are
    # exact. The shapes BLIND SPOT, SHARP EDGES and decision 6 name are pinned too,
    # so that reading one differently later means changing the docstring as well.
    # Where the link check reads a shape differently (#121), docs_tree.anchors_of
    # says why, and check-doc-links.py --self-test pins that side.

    title_9 = "# 셀프 테스트 — 등록 항목 9건 (열림 4 · 닫힘 4 · 해소 1)"
    fence3, fence4 = "`" * 3, "`" * 4
    shapes = [
        ("an item heading indented three spaces (docs_tree.anchors_of)",
         [f"   ## {prefix}-9 — 들여 쓴 항목"], {"unread-heading"}, False),
        ("an item heading inside a blockquote (docs_tree.anchors_of)",
         [f"> ## {prefix}-9 — 인용 블록 안의 항목"], {"unread-heading"}, False),
        ("an item heading inside an HTML comment block (docs_tree.anchors_of)",
         ["<!--", f"## {prefix}-9 — 주석으로 가린 항목", "-->"], set(), False),
        ("an item heading inside a list item (docs_tree.anchors_of)",
         [f"- ## {prefix}-9 — 리스트 항목 안의 항목"], {"unread-heading"}, False),
        ("an item heading indented four spaces (SHARP EDGES)",
         [f"    ## {prefix}-9 — 네 칸 들여 쓴 항목"], {"unread-heading"}, False),
        ("an item written as a setext heading (BLIND SPOT)",
         [f"{prefix}-9 — setext 로 쓴 항목", "---"], set(), False),
        ("an item written as a raw HTML heading (BLIND SPOT)",
         [f"<h2>{prefix}-9 — HTML 로 쓴 항목</h2>"], set(), False),
        ("an item heading after a `<!--` that unfence exposes inside a longer fence "
         "(SHARP EDGES; docs_tree.anchors_of)",
         [fence4 + "markdown", fence3, "<!--", fence3, fence4, "",
          f"## {prefix}-9 — 펜스 뒤의 항목"], set(), False),
        ("an item heading inside a `<details>` block with no blank line is read (decision 6)",
         ["<details>", "<summary>요약</summary>", f"## {prefix}-9 — details 안의 항목",
          "</details>"], set(), True),
        ("an item heading in a fence in a list item's continuation (SHARP EDGES)",
         ["- 목록 항목", "  " + fence3, f"  ## {prefix}-9 — 목록 안 펜스 속 항목", "  " + fence3],
         set(), False),
        ("an item heading in a comment opened two spaces in, in a list item's continuation "
         "(SHARP EDGES)",
         ["- 목록 항목", "  <!--", f"  ## {prefix}-9 — 목록 안 주석 속 항목", "  -->"], set(), False),
        ("an item heading in a comment opened after `>` (SHARP EDGES)",
         ["> <!--", f"> ## {prefix}-9 — 인용 안 주석 속 항목", "> -->"], {"unread-heading"}, False),
    ]
    for name, appended, flagged, read in shapes:
        def run(appended=appended, flagged=flagged, read=read):
            docs, before = synthetic()
            lines = lines_of(docs, path_s) + appended
            untouched = added(before, check(with_lines(docs, path_s, lines)))
            lines[lines.index(title_s)] = title_9
            counting = added(before, check(with_lines(docs, path_s, lines)))
            if read:
                # A ninth item: the untouched title and the row, still at 8, both disagree.
                want_untouched, want_counting = flagged | {"title", "index"}, flagged | {"index"}
            else:
                want_untouched, want_counting = flagged, flagged | {"title"}
            ok_untouched, untouched_detail = verdict(untouched, want_untouched)
            ok_counting, counting_detail = verdict(counting, want_counting)
            return (ok_untouched and ok_counting,
                    f"title untouched: {untouched_detail}; title counting it: {counting_detail}")
        cases.append((name, run))

    @case("a state record behind indentation is reported, not read")
    def _():
        docs, before = synthetic()
        lines = lines_of(docs, path_s) + [f"## {prefix}-9 — 새 항목", "   ### 닫힘 (날짜)"]
        lines[lines.index(title_s)] = title_9
        docs = with_lines(docs, path_s, lines)
        # The title and the row both count the new item as 열림, which agrees with
        # the items only while the displaced record is left unread.
        table, row = row_of_synthetic(docs)
        line = lines_of(docs, INDEX)[row.line - 1]
        changed = replace_cells(line, row.spans, {table.columns[TOTAL_COLUMN]: 9,
                                                  table.columns["열림"]: 4})
        after = check(replace_line(docs, INDEX, row.line, changed))
        return verdict(added(before, after), {"unread-heading"})

    print(f"self-test over {len(base.registers)} register(s), {len(base.rows)} index row(s), "
          f"{sum(r.total for r in base.registers)} item(s)")
    failed = unbuilt = 0
    for name, run in cases:
        try:
            ok, detail = run()
        except CannotConstruct as e:
            ok, detail = False, f"could not construct: {e}"
            unbuilt += 1
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
        print(f"         {detail}")

    print()
    print(f"for context, the real tree has {len(base.findings)} finding(s) right now -- "
          "the main run owns that verdict")
    if failed:
        print()
        if failed > unbuilt:
            print(f"{failed - unbuilt} case(s) failed: a mutation no longer adds the finding it "
                  "names, or a reading rule can be switched off without changing what is read -- "
                  "which means it is no longer in the code. The module docstring is the "
                  "specification.")
        if unbuilt:
            print(f"{unbuilt} case(s) could not be constructed: this tree has nothing left that "
                  "fits -- a register, row or ID with no finding of its own, or a readable index "
                  "table. Run the check without --self-test; what it reports is what to fix.")
        return 1
    print()
    print("every mutation adds the finding it names, and every reading rule still changes what "
          "is read")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()
    return report(check(load_tree()), "--github" in sys.argv)


if __name__ == "__main__":
    sys.exit(main())
