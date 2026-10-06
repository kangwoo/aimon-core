"""MkDocs hook: make the links written for GitHub work on the built site.

Two rewrites, both at render time and both leaving the sources alone: a link that
leaves what the site builds becomes a GitHub URL, and a fragment written against a
canonical page is carried over to the translation the site serves in its place.

OUT-OF-DOCS LINKS.

The repository is read on two surfaces. On GitHub, a link like
``../../modules/aimon-core/src/main/java/.../ReadTool.java`` resolves and is the
right thing to write. On the built site, ``modules/`` does not exist -- only
``docs/`` is copied -- so the same link is dead.

Rewriting those links in the sources would fix the site by breaking GitHub, and
there are roughly two hundred of them. So they stay relative in the sources, and this hook
rewrites *only the ones that escape ``docs_dir``* into absolute GitHub URLs at
render time. Links that stay inside ``docs/`` are left completely alone, which
keeps MkDocs' own link resolution -- and the ``.en.md`` translation mapping --
in charge of everything it should be in charge of.

There is a second, smaller class with the same shape: a link that stays inside
``docs/`` but points at a directory ``exclude_docs`` keeps off the site
(``backlog/``, ``plan/``). Those files exist on GitHub and not in the build, so
they get the same treatment. The list is read from ``exclude_docs`` rather than
repeated here -- excluding one more directory is then a one-line change in
``mkdocs.yml``, and this hook cannot fall out of step with it.

What this hook leaves alone, something else has to answer for. A link that stays
inside ``docs/`` and points at a *directory the site builds* is not rewritten --
there is no GitHub-only tree to send it to -- and MkDocs cannot resolve it either
(it logs ``unrecognized relative link`` at INFO and ``--strict`` stays green).
``scripts/check-doc-links.py`` fails those (backlog T-5), using
``docs_tree.site_tree()``'s reading of ``mkdocs.yml`` to decide what "built"
means. That reading is a regex one, because the check runs with no pip install;
this hook holds MkDocs' own parsed ``exclude_docs``. ``on_config`` below asks
both about every directory under ``docs_dir`` and stops the build when they
differ, so the link the check accepts as "the hook rewrites it" is one this hook
does rewrite.

FRAGMENTS ACROSS A TRANSLATION (backlog T-10). A page with no ``.en.md`` is shown on
``/en/`` from its Korean source, and its link ``guide.md#matcher-문법`` is sent to
``guide.en.md`` -- mkdocs-static-i18n's ``Files.get_file_from_path`` prefers the
build locale's file -- where no heading has that id. The link is right on
github.com and right on the Korean site, so it is not the source that is wrong.
``_carry`` rewrites the fragment to the id of the heading *at the same position*
in the file the build serves; ``check-translation-structure.py`` holds a
translation to its canonical's headings, same levels, same order, which is what
makes position mean something. Six links needed this when it was written.

  * *Which links.* One whose path, as written, names a ``.md`` file under
    ``docs_dir`` and for which ``files.get_file_from_path`` returns a *different*
    source file. Nothing here asks which locale is being built or names the i18n
    plugin: on the Korean build the lookup returns the file as written and nothing
    happens, and a ``.en.md`` page linking a canonical's Korean heading (none today)
    is carried over by the same test.
  * *Whose ids.* The site's. Both files are rendered with the build's own
    ``markdown_extensions`` and the ids are read off ``toc_tokens`` -- the id written
    into the link has to be the one the browser will look for, and
    ``docs_tree.slug`` is not that (it drops ``_``, and numbers a repeated heading
    ``-1`` where the site writes ``_1``). The fragment is matched against the
    canonical's ids *exactly*: one that does not match is dead on the Korean site
    too, and making it work only in English would hide that.
  * *What is not guessed.* When the two files' headings do not line up -- a
    different count, or a different level anywhere -- the link is left as written
    and one INFO line says so. That pair is either behind its canonical (which fails
    no build, by decision: ``check-translation-staleness.py``) or current and wrong
    (which ``check-translation-structure.py`` fails), and position means nothing in
    either. A fragment that is not one of the canonical's heading ids -- a
    hand-written ``<a id>`` -- is left as written as well: it resolves if the
    translation carries the same ``<a id>``.
  * *Which links are read at all.* The inline form, ``[text](path.md#fragment)`` with
    an optional ``"title"`` in double quotes -- the one form this tree writes (0 of
    the others below when this was written). The text may wrap onto the next line;
    it may not cross a blank one, since that is two paragraphs and no link. The
    site makes a link of each of these too, and this hook does not see them, so
    their fragment is not carried and is dead on ``/en/``:

      - a reference-style link, ``[text][ref]`` with ``[ref]: path.md#fragment``
      - a target in angle brackets, ``[text](<path.md#fragment>)``
      - a title in single quotes, ``[text](path.md#fragment 'title')``
      - a percent-encoded fragment, ``#%EC%84%A4%EC%A0%95``: it is read, and matched
        against the site's ids character for character, which it is not

    ``scripts/check-doc-links.py`` does not read the first three either (it reads
    the same inline form), so nothing stops one being written; it fails the fourth
    as ``no such anchor``. Write the inline form.
  * *What is rewritten that is not a link.* Sample text is left alone inside a fence
    and inside a code span -- ``_fenced`` pairs fence markers the way the site does,
    so a fence shown inside a longer fence stays hidden. Three places show
    link-looking text as text and are *not* recognised, so a sample there is
    displayed with the fragment (or the GitHub URL) this hook wrote:

      - a code block made by indenting four spaces. Not told apart on purpose: the
        same indentation is a list item's continuation, an admonition's body, a
        tab's, a definition's, and the links there are real (the three indented
        links in this tree when this was written were all of that kind, one of them
        out of ``docs/``). Reading one of those as code would leave a real link
        dead; reading code as a link only changes what a sample displays
      - a fence behind a blockquote's ``>``
      - a code span that wraps, around a link written on one line. A wrapped code
        span hides a link that wraps with it, and nothing else: a link on one line is
        judged by its own line, as it was before text could wrap, because a stray
        backtick in a heading or a table row would otherwise pair with one on the
        next line and hide a real link

    Show such a sample in a fence.
  * *What checks it.* MkDocs validates the link this hook returns, so a wrong id
    shows as its usual ``does not contain an anchor`` INFO line.
    ``scripts/check-doc-links.py`` asks, with no pip install, whether each such link
    has what this needs (WHICH FRAGMENTS MUST SURVIVE A TRANSLATION, which also says
    where its reading of headings differs from this one). ``--self-test`` here builds
    a small two-locale site in a temporary directory and reads the hrefs off the
    built pages -- and, for sample text, that the page shows it as it was written;
    ``.github/workflows/docs.yml`` runs it before the real build.

Registered from ``mkdocs.yml`` under ``hooks:``. The first rewrite depends on no
plugin; the second does nothing without a ``Files`` that maps a path to another
source, which today is mkdocs-static-i18n's.

Usage:
    python3 scripts/mkdocs_github_links.py --self-test    # needs docs-requirements.txt
"""

import logging
import os
import posixpath
import re
import sys
from pathlib import Path

log = logging.getLogger("mkdocs.hooks.github_links")

# A link inside a fence or backticks is an example, not a link, and must not be
# rewritten. Which lines a fence hides is the *site's* answer (pymdownx.superfences),
# not scripts/check-doc-links.py's, which toggles on every marker: see ``_fenced``.
FENCE_OPEN = re.compile(r"^[ \t]*(`{3,}|~{3,})(.*)$")
# A code span is delimited by a *run* of backticks, and the run length must match:
# ``[`ReadTool`](x)`` is one span, not an empty span followed by a link.
INLINE_CODE = re.compile(r"(`+)(?:(?!\1).)*\1")
# A newline that does not end the paragraph: link text and a code span may both wrap
# onto the next line, and neither may cross a blank one.
_WRAP = r"\n(?![ \t\r]*\n)"
WRAPPED_CODE = re.compile(rf"(`+)(?:(?!\1)(?:[^\n]|{_WRAP}))*\1")
LINK = re.compile(rf"(!?\[(?:[^\]\n]|{_WRAP})*\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")
EXTERNAL = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//|#|<)", re.IGNORECASE)

BRANCH = "main"

_repo_root = Path(__file__).resolve().parent.parent


def _github_url(repo_url, relative_path):
    """blob/ for a file, tree/ for a directory -- GitHub 404s on the wrong one."""
    kind = "tree" if (_repo_root / relative_path).is_dir() else "blob"
    return f"{repo_url.rstrip('/')}/{kind}/{BRANCH}/{relative_path}"


def on_config(config, **kwargs):
    """Stop the build when docs_tree.site_tree() and MkDocs disagree on what is built."""
    from mkdocs.exceptions import PluginError

    config_file = config.get("config_file_path")
    if not config_file or Path(config_file).resolve().parent != _repo_root:
        # Some other mkdocs.yml is being built with this hook; docs_tree reads ours.
        return config

    # MkDocs loads a hook by file path, so its directory is not importable yet.
    scripts = str(Path(__file__).resolve().parent)
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    import docs_tree

    try:
        site = docs_tree.site_tree(_repo_root)
    except ValueError as unreadable:
        raise PluginError(f"scripts/docs_tree.py cannot read exclude_docs: {unreadable}")

    docs_dir = Path(config["docs_dir"]).resolve()
    if site.docs_dir != docs_dir:
        raise PluginError(
            f"scripts/docs_tree.py reads docs_dir as {site.docs_dir}, MkDocs as {docs_dir}")

    spec = config.get("exclude_docs")
    differ = []
    for directory in sorted(p for p in docs_dir.rglob("*") if p.is_dir()):
        relative = directory.relative_to(docs_dir).as_posix()
        if site.excluded(relative) != bool(spec and spec.match_file(relative + "/")):
            differ.append(relative + "/")
    if differ:
        raise PluginError(
            "scripts/docs_tree.py and MkDocs disagree on whether exclude_docs keeps these "
            f"directories off the site: {', '.join(differ)}. scripts/check-doc-links.py "
            "decides which directory links to fail from the first reading and this hook "
            "rewrites links from the second -- make SiteTree.excluded match.")
    return config


def on_page_markdown(markdown, page, config, files, **kwargs):
    repo_url = config.get("repo_url")
    docs_dir = Path(config["docs_dir"]).resolve()
    page_dir = posixpath.dirname(page.file.src_uri)
    excluded = _excluder(config, docs_dir)

    def carry(written, fragment):
        return _carry(page.file.src_uri, written, fragment, docs_dir, files, config)

    return _rewrite(markdown, docs_dir, page_dir, repo_url, excluded, carry)


# --- fragments across a translation -----------------------------------------

_heading_cache = {}
_said = set()


def _headings(path, config):
    """``[(level, id)]`` for every heading of ``path``, in page order, as the site renders it."""
    key = (path, path.stat().st_mtime_ns)
    if key not in _heading_cache:
        import markdown
        from mkdocs.utils import meta

        body, _ = meta.get_data(path.read_text(encoding="utf-8-sig"))
        md = markdown.Markdown(
            extensions=config["markdown_extensions"],
            extension_configs=config["mdx_configs"] or {},
        )
        md.convert(body)
        found = []

        def walk(tokens):
            for token in tokens:
                found.append((token["level"], token["id"]))
                walk(token["children"])

        walk(getattr(md, "toc_tokens", []))
        _heading_cache[key] = found
    return _heading_cache[key]


def twin(fragment, written, served):
    """The id in ``served`` of the heading standing where ``#fragment`` stands in ``written``.

    Both are ``[(level, id)]`` in page order. Returns ``(id, None)``, or ``(None, why)``
    when the link must be left as written; ``why`` is None when there is nothing to say
    (the fragment is not a heading id, so there is no position to carry).
    """
    at = next((i for i, (_, anchor) in enumerate(written) if anchor == fragment), None)
    if at is None:
        return None, None
    if [level for level, _ in written] != [level for level, _ in served]:
        if len(written) != len(served):
            return None, f"their headings do not line up ({len(written)} against {len(served)})"
        return None, "their headings do not line up (same count, a level differs)"
    return served[at][1], None


def _carry(page_uri, written_uri, fragment, docs_dir, files, config):
    """``#fragment`` as it must be written for the file this build serves, or None to leave it."""
    if not fragment or not written_uri.endswith(".md"):
        return None
    served = files.get_file_from_path(written_uri)
    if served is None or served.src_uri == written_uri or not served.src_uri.endswith(".md"):
        return None
    written_path, served_path = docs_dir / written_uri, Path(served.abs_src_path)
    if not written_path.is_file() or not served_path.is_file():
        return None

    there = _headings(served_path, config)
    anchor, why = twin(fragment, _headings(written_path, config), there)
    said = (page_uri, written_uri, fragment)
    # Left as written and still alive when the served file has an id of that very name.
    if why and said not in _said and fragment not in {i for _, i in there}:
        _said.add(said)
        link = (f"Doc file '{page_uri}' links '{written_uri}#{fragment}' and this build serves "
                f"'{served.src_uri}' for it: the fragment is left as written, because {why}")
        if _lines_up_for_the_link_check(written_path, served_path):
            # Not a translation that is behind: the check that reads the sources sees one
            # outline in both. So one file has a line the site renders as a heading and
            # that reading does not, and scripts/check-doc-links.py has accepted a link the
            # site cannot carry. A warning, so `mkdocs build --strict` stops on it.
            log.warning(
                f"{link} on the site -- but scripts/check-doc-links.py reads the same headings "
                "in both files and accepts this link. One of the two has a line the site "
                "renders as a heading and github.com does not (Python-Markdown takes a line "
                "starting `#text`, with no space after the `#`, as one): compare the two "
                "pages' tables of contents and reword that line.")
        else:
            log.info(f"{link}. scripts/check-doc-links.py reports the same link.")
    return anchor


def _lines_up_for_the_link_check(written_path, served_path):
    """Whether docs_tree's reading -- check-doc-links.py's -- sees one outline in both files."""
    scripts = str(Path(__file__).resolve().parent)
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    import docs_tree

    def levels(path):
        text = path.read_text(encoding="utf-8", errors="replace")
        return [level for level, _, _ in docs_tree.headings_of(text)]

    return levels(written_path) == levels(served_path)


def _excluder(config, docs_dir):
    """Return ``path_inside_docs -> repo-relative path``, or None when it stays."""
    spec = config.get("exclude_docs")
    try:
        docs_prefix = docs_dir.relative_to(_repo_root).as_posix()
    except ValueError:
        docs_prefix = None
    if spec is None or docs_prefix is None:
        return lambda _: None

    def excluded(relative):
        # A gitignore pattern written as ``backlog/`` only matches a path the
        # matcher can see is a directory, which it decides from the trailing
        # slash -- so a bare ``backlog`` has to be offered both ways.
        candidates = [relative] if relative.endswith("/") else [relative, relative + "/"]
        if not any(spec.match_file(candidate) for candidate in candidates):
            return None
        return f"{docs_prefix}/{relative.rstrip('/')}"

    return excluded


def _fenced(lines):
    """The indexes of the lines a fenced block hides, its two markers included.

    As pymdownx.superfences pairs them, since it is what renders the page: a fence
    opens on three or more backticks or tildes (a backtick fence's info string has no
    backtick -- that line is a code span), and closes on the next line that is *that
    very marker* and nothing else. A longer or shorter run does not close it, nor does
    the other character, nor a marker with an info string -- so a fence shown inside a
    longer fence stays hidden. An opener with no closer is not a fence at all: the
    site renders those lines as a paragraph, and their links are live.
    """
    hidden, at = set(), 0
    while at < len(lines):
        opener = FENCE_OPEN.match(lines[at])
        if opener and not (opener.group(1)[0] == "`" and "`" in opener.group(2)):
            marker = opener.group(1)
            closer = next(
                (i for i in range(at + 1, len(lines)) if lines[i].strip() == marker), None)
            if closer is not None:
                hidden.update(range(at, closer + 1))
                at = closer + 1
                continue
        at += 1
    return hidden


def _rewrite(markdown, docs_dir, page_dir, repo_url, excluded, carry=None):
    """``markdown`` -- a whole page -- with each link's target rewritten, or left alone.

    The page is read whole, not line by line, because a link's text may wrap:
    ``[text that\nwraps](guide.md#frag)`` is one link to the site and to github.com.
    Only the target is ever replaced; every other character comes back as it was given.
    """
    lines = markdown.split("\n")
    fenced = _fenced(lines)
    # Fenced lines are blanked to their own length, so offsets hold and nothing -- a
    # link's text, a code span -- can be read across a fence.
    shown = [" " * len(line) if i in fenced else line for i, line in enumerate(lines)]
    masked = "\n".join(shown)

    # Inline code is skipped by *position*, not by slicing the text up: link text
    # is very often backticked here (``[`ReadTool`](...)``), and cutting at the code
    # span would tear that link in half and leave it unrewritten.
    protected, offset = [], 0
    for line in shown:
        protected += [(offset + m.start(), offset + m.end()) for m in INLINE_CODE.finditer(line)]
        offset += len(line) + 1
    # A code span that wraps hides only a link that wraps. A link written on one line
    # is judged by its own line alone, as it always was: a stray backtick in a heading
    # or a table row would otherwise pair with one a line below and hide a real link.
    wrapped_code = [(m.start(), m.end()) for m in WRAPPED_CODE.finditer(masked)
                    if "\n" in m.group(0)]

    def target_for(match):
        """What to write in place of the link's target, or None to leave it."""
        hiding = protected + (wrapped_code if "\n" in match.group(1) else [])
        if any(start <= match.start(2) < end for start, end in hiding):
            return None
        target = match.group(2)
        if EXTERNAL.match(target):
            return None

        path_part, _, anchor = target.partition("#")
        if not path_part:
            return None

        resolved = Path(posixpath.normpath(posixpath.join(page_dir, path_part)))
        # normpath keeps leading '..' when the path climbs out of docs_dir.
        if not str(resolved).startswith(".."):
            # Inside docs/: the only ones that need help are the ones that are
            # not built. Everything else stays relative and MkDocs resolves it.
            inside = excluded(resolved.as_posix())
            if inside is None:
                carried = carry(resolved.as_posix(), anchor) if carry else None
                if carried is None or carried == anchor:
                    return None
                return f"{path_part}#{carried}"
            if not repo_url:
                return None
            url = _github_url(repo_url, inside)
            return f"{url}{'#' + anchor if anchor else ''}"

        outside = (docs_dir / resolved).resolve()
        try:
            relative = outside.relative_to(_repo_root)
        except ValueError:
            # Climbs above the repository itself -- leave it for the link checker.
            return None
        if not repo_url:
            return None

        url = _github_url(repo_url, relative.as_posix())
        return f"{url}{'#' + anchor if anchor else ''}"

    out, done = [], 0
    for match in LINK.finditer(masked):
        target = target_for(match)
        if target is not None:
            out += [markdown[done:match.start(2)], target]
            done = match.end(2)
    out.append(markdown[done:])
    return "".join(out)


# --- the self-test -----------------------------------------------------------
#
# A small site, built by MkDocs itself into a temporary directory with this file as
# its hook and everything else inherited from the real mkdocs.yml -- the i18n plugin
# and the `toc` slugify are the two things a carried id depends on, and a copy of
# either here would be free to drift. Each case is one link, on a page that has no
# translation (`design`, `sub/page`, `loose`) or on one half of a pair (`other`), and
# the href it must have in the built Korean page and in the built English one.
# `twin()` is not asked directly: what has to hold is the page the reader gets.
#
# Several pages are here for one wrong reading each, and say so. A change to this
# file that every case survives was not pinned by any of them -- that is how each of
# those pages got here (a review mutated the hook and nothing went red).

FENCE3, FENCE4 = "`" * 3, "`" * 4

SELF_TEST_PAGES = {
    "index.md": "# 홈\n",
    # An h3 under the first h2: every later heading's position counts it.
    "guide.md": "# 가이드\n\n## 설정 방법\n\n### 세부 설정\n\n## `call_id` 다루기\n\n"
                '## 설정 방법\n\n<a id="옛-이름"></a>\n\n## API\n',
    # Front matter, as every real translation has. Read as body it is a setext heading
    # (`key: value` over `---`) and an ATX one (the comment), and nothing lines up.
    "guide.en.md": "---\ntranslated_from: docs/guide.md\n# a comment, and not a heading\n"
                   "source_commit: 0000000\n---\n\n"
                   "# Guide\n\n## How to configure\n\n### Details\n\n## Handling `call_id`\n\n"
                   '## How to configure\n\n<a id="옛-이름"></a>\n\n## API\n',
    "behind.md": "# 뒤처진 쌍\n\n## 새 절\n\n## 둘째\n\n## API\n",
    "behind.en.md": "# A pair that is behind\n\n## Second\n\n## API\n",
    # As many headings on both sides, and the last one a level apart.
    "levels.md": "# 레벨이 다른 쌍\n\n## 둘째\n\n## 셋째\n",
    "levels.en.md": "# A pair a level apart\n\n## Second\n\n### Third\n",
    # The same levels on both sides in a different order: [1, 2, 3, 2] against
    # [1, 2, 2, 3]. Read siblings-before-children both come out [1, 2, 2, 3].
    "nested.md": "# 순서가 다른 쌍\n\n## 첫째\n\n### 첫째의 세부\n\n## 둘째\n",
    "nested.en.md": "# A pair in another order\n\n## First\n\n## Second\n\n### Detail of the second\n",
    # The paragraph's second line is a heading to Python-Markdown and to nothing else.
    "stray.md": "# 어긋난 쌍\n\n## 둘째\n\n문단의 첫 줄\n#42 로 시작하는 둘째 줄\n",
    "stray.en.md": "# A pair the site reads differently\n\n## Second\n\nfirst line\nthen #42\n",
    "plain.md": "# 번역 없는 쪽\n\n## 제목\n",
    # A second `guide.md` / `guide.en.md`, with another outline: a file is its path.
    "sub/guide.md": "# 하위 가이드\n\n## 다른 제목\n",
    "sub/guide.en.md": "# The guide one level down\n\n## A different heading\n",
    # `../guide.md` is `guide.md` to the site; what is looked up is the resolved path.
    "sub/page.md": "# 하위 문서\n\n[L16](../guide.md#설정-방법)\n\n[L17](guide.md#다른-제목)\n",
    "design.md": "# 설계\n\n" + "\n\n".join([
        "[L01](guide.md#설정-방법)",
        "[L02](guide.md#call_id-다루기)",
        "[L03](guide.md#설정-방법_1)",
        "[L04](guide.md#설정-방법-1)",
        "[L05](guide.md#옛-이름)",
        "[L06](guide.md#api)",
        "[L07](behind.md#둘째)",
        "[L08](behind.md#api)",
        "[L09](stray.md#둘째)",
        "[L10](plain.md#제목)",
        "[L11](guide.md)",
        "`[L12](guide.md#설정-방법)`",
        "[L18](guide.md#세부-설정)",
        "[L19](levels.md#셋째)",
        "[L20](nested.md#둘째)",
        "[L21](sub/guide.md#다른-제목)",
        "[L22 의 글이\n다음 줄로 넘어간다](guide.md#설정-방법)",
        "`[L23 의 글이\n다음 줄로 넘어간다](guide.md#설정-방법)`",
        f"{FENCE3}text\n[L24](guide.md#설정-방법)\n{FENCE3}",
        # The inner markers are text to the site. Toggling on every marker reads them
        # as closing the outer fence, and the line between them as a link.
        f"{FENCE4}text\n{FENCE3}text\n[L25](guide.md#설정-방법)\n{FENCE3}\n{FENCE4}",
        # A `[` and a `](...)` in two paragraphs are no link, on the site or anywhere.
        "[L27 은 닫히지 않는다\n\n다음 문단의 글](guide.md#설정-방법)",
    ]) + "\n",
    # An opener nothing closes is no fence to the site: a paragraph, and a live link.
    "loose.md": f"# 닫히지 않은 펜스\n\n{FENCE3}text\n[L26](guide.md#설정-방법)\n",
    "other.md": "# 다른 문서\n\n[L13](guide.md#설정-방법)\n",
    "other.en.md": "# Another\n\n[L14](guide.md#설정-방법)\n\n[L15](guide.en.md#how-to-configure)\n",
}

WRAPPED = "의 글이\n다음 줄로 넘어간다"

# (case, built page, link text, href in the Korean build, href in the English build).
# None where that build's page has no such link.
SELF_TEST_CASES = [
    ("a Korean heading is carried to the heading at the same position",
     "design", "L01", "../guide/#설정-방법", "../guide/#how-to-configure"),
    ("the id written is the site's, `_` and all -- not docs_tree.slug's",
     "design", "L02", "../guide/#call_id-다루기", "../guide/#handling-call_id"),
    ("a repeated heading is carried under the site's `_1` numbering",
     "design", "L03", "../guide/#설정-방법_1", "../guide/#how-to-configure_1"),
    ("github.com's `-1` numbering is no id on the site, and is left as written",
     "design", "L04", "../guide/#설정-방법-1", "../guide/#설정-방법-1"),
    ("a hand-written `<a id>` is left as written",
     "design", "L05", "../guide/#옛-이름", "../guide/#옛-이름"),
    ("a heading with the same id in both files comes out the same",
     "design", "L06", "../guide/#api", "../guide/#api"),
    ("when the translation has fewer headings the fragment is left as written, not guessed",
     "design", "L07", "../behind/#둘째", "../behind/#둘째"),
    ("so is one the translation has under the same name, which still resolves",
     "design", "L08", "../behind/#api", "../behind/#api"),
    ("when only the site sees an extra heading the fragment is left as written",
     "design", "L09", "../stray/#둘째", "../stray/#둘째"),
    ("a link into a page with no translation is untouched",
     "design", "L10", "../plain/#제목", "../plain/#제목"),
    ("a link with no fragment is untouched",
     "design", "L11", "../guide/", "../guide/"),
    ("a link inside a code span is not a link",
     "design", "L12", None, None),
    ("a canonical that has a translation keeps its fragment on the Korean build",
     "other", "L13", "../guide/#설정-방법", None),
    ("a `*.en.md` page linking the canonical's Korean heading is carried too",
     "other", "L14", None, "../guide/#how-to-configure"),
    ("a `*.en.md` page linking the translation is untouched",
     "other", "L15", None, "../guide/#how-to-configure"),
    ("a link written `../guide.md` from a subdirectory is carried -- the path is resolved",
     "sub/page", "L16", "../../guide/#설정-방법", "../../guide/#how-to-configure"),
    ("a second `guide.md` in another directory is carried by its own headings",
     "sub/page", "L17", "../guide/#다른-제목", "../guide/#a-different-heading"),
    ("an h3 is carried to the h3 at the same position",
     "design", "L18", "../guide/#세부-설정", "../guide/#details"),
    ("when the count matches and a level does not the fragment is left as written",
     "design", "L19", "../levels/#셋째", "../levels/#셋째"),
    ("so is it when the levels match only out of page order",
     "design", "L20", "../nested/#둘째", "../nested/#둘째"),
    ("the second `guide.md` is carried from the first one's directory too",
     "design", "L21", "../sub/guide/#다른-제목", "../sub/guide/#a-different-heading"),
    ("a link whose text wraps onto the next line is carried",
     "design", "L22 " + WRAPPED, "../guide/#설정-방법", "../guide/#how-to-configure"),
    ("a wrapped link inside a code span is not a link",
     "design", "L23 " + WRAPPED, None, None),
    ("a link inside a fence is not a link",
     "design", "L24", None, None),
    ("nor is one inside a fence shown in a longer fence",
     "design", "L25", None, None),
    ("a link after a fence marker nothing closes is a link, and is carried",
     "loose", "L26", "../guide/#설정-방법", "../guide/#how-to-configure"),
]

# "Not a link" would also be true of sample text that had been rewritten. So: what the
# English page must show, character for character as it was written -- (case, text).
SELF_TEST_AS_WRITTEN = [
    ("the code span", "<code>[L12](guide.md#설정-방법)</code>"),
    ("the wrapped code span", f"<code>[L23 {WRAPPED}](guide.md#설정-방법)</code>"),
    ("the fenced line", "[L24](guide.md#설정-방법)"),
    ("the line in the inner fence", "[L25](guide.md#설정-방법)"),
    ("text that only looks like a link across two paragraphs",
     "다음 문단의 글](guide.md#설정-방법)"),
]

# What that build must say and must not: (case, log level, the link named, lines expected).
SELF_TEST_LOG = [
    ("a pair both readings see as out of step is said at INFO", "INFO", "behind.md#둘째'", 1),
    ("and not as a warning -- a translation that is behind fails no build",
     "WARNING", "behind.md#둘째'", 0),
    ("a fragment left as written that still resolves is not mentioned", "", "behind.md#api'", 0),
    ("a pair only the site sees as out of step is a warning -- the link check accepted it",
     "WARNING", "stray.md#둘째'", 1),
    ("a pair a level apart is said at INFO", "INFO", "levels.md#셋째'", 1),
    ("and so is a pair whose levels come in another order", "INFO", "nested.md#둘째'", 1),
    ("a carried fragment is not mentioned", "", "guide.md#설정-방법'", 0),
]

# Every fixture file gets this one modification time, so that nothing but its path
# tells one from another.
SELF_TEST_MTIME_NS = 1_700_000_000 * 10**9


def self_test():
    import html
    import subprocess
    import tempfile
    import urllib.parse

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp).resolve()
        (root / "docs").mkdir()
        for name, text in SELF_TEST_PAGES.items():
            path = root / "docs" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            os.utime(path, ns=(SELF_TEST_MTIME_NS, SELF_TEST_MTIME_NS))
        (root / "mkdocs.yml").write_text(
            f"INHERIT: {_repo_root / 'mkdocs.yml'}\n"
            "docs_dir: docs\n"
            f"hooks:\n  - {Path(__file__).resolve()}\n",
            encoding="utf-8")
        # Not --strict: one case is a warning, and the built pages are read either way.
        built = subprocess.run(
            [sys.executable, "-m", "mkdocs", "build", "-f", str(root / "mkdocs.yml"),
             "-d", str(root / "site")],
            capture_output=True, text=True, encoding="utf-8",
            env={**os.environ, "NO_COLOR": "1"})
        said = (built.stdout + built.stderr).splitlines()
        if built.returncode != 0:
            print("\n".join(said))
            print("the self-test site did not build (is docs-requirements.txt installed?)")
            return 1

        def href(page, text, locale):
            path = root / "site" / locale / page / "index.html"
            found = re.findall(
                rf'<a href="([^"]*)">{re.escape(text)}</a>', path.read_text(encoding="utf-8"))
            return html.unescape(urllib.parse.unquote(found[0])) if len(found) == 1 else None

        hrefs = {(page, text): (href(page, text, ""), href(page, text, "en"))
                 for _, page, text, _, _ in SELF_TEST_CASES}
        english_design = (root / "site/en/design/index.html").read_text(encoding="utf-8")

    failed = 0
    print(f"self-test over {len(SELF_TEST_CASES)} link(s) in a two-locale site MkDocs built")
    for name, page, text, korean, english in SELF_TEST_CASES:
        got = hrefs[page, text]
        ok = got == (korean, english)
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
        print(f"         /: {got[0]!r}    /en/: {got[1]!r}")

    for name, text in SELF_TEST_AS_WRITTEN:
        ok = text in english_design
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} and {name} reads on /en/ as it was written")
        print(f"         {text!r} {'is' if ok else 'is not'} on the page")

    print()
    print(f"self-test over {len(SELF_TEST_LOG)} thing(s) that build must say, or must not")
    for name, level, link, expected in SELF_TEST_LOG:
        got = sum(1 for line in said
                  if line.startswith(level) and "and this build serves" in line and link in line)
        ok = got == expected
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
        print(f"         {got} {level or 'log'} line(s) naming {link[:-1]}")

    print()
    if failed:
        print(f"{failed} case(s) failed: this hook no longer carries a fragment across a "
              "translation the way its docstring says (FRAGMENTS ACROSS A TRANSLATION). Say "
              "there what it does now and why, then change the expected answer here.")
        return 1
    print("every fragment above is still carried, or left as written, as this hook's docstring says")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        sys.exit(self_test())
    sys.exit("usage: python3 scripts/mkdocs_github_links.py --self-test")
