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
    output; a hand-written anchor is an `<a name|id>` anywhere in the raw text.

    WHERE THE LINK CHECK AND THE BACKLOG CHECK READ HEADINGS DIFFERENTLY.
    check-backlog-registers.py starts from the same lines and does two things
    this does not: it blanks HTML comment blocks (its `uncomment`), and it
    reports a heading behind indentation, `>` or a list marker whose text it
    would read at the start of a line. Three shapes come out differently. "The
    page" is CommonMark 0.31.2 as cmark-gfm renders it locally; github.com was
    not observed.

    * Inside an HTML comment block. The page shows no heading and the backlog
      check reads none, but this anchors it, so a link into one passes and
      lands at the top of the page.
    * Behind one to three spaces, `>` or a list marker, or in a list item's
      continuation (two spaces under `- `). The page shows a heading, but this
      gives no anchor, so a correct link to one fails; the backlog check
      reports it as unread-heading.
    * After a `<!--` on a line unfence() exposes inside a real fence. unfence()
      pairs fence markers by position -- a shorter marker inside a longer fence
      closes it, and the closer of a fence opened on a list marker's own line,
      which it does not see open, opens one -- so a `<!--` the page shows as
      code is plain text to it. This anchors the heading after the fence, as
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
    names those blocks), and YAML front matter read as text (backlog T-6).

    `check-doc-links.py --self-test` pins this function's side of the three
    shapes and of a raw HTML block; the backlog check's `--self-test` pins its
    side.
    """
    found, seen = set(), {}
    body = unfence(text)
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


def translations():
    """Every *.<lang>.md in the repo, sorted, build outputs excluded."""
    found = []
    for p in ROOT.rglob("*.md"):
        if any(part in SKIP_DIRS for part in p.relative_to(ROOT).parts):
            continue
        # foo.en.md / foo.ko.md -- the suffixes declared above, so every walker
        # agrees on what a translation is
        if translation_suffix(p) is not None:
            found.append(p)
    return sorted(found)


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
