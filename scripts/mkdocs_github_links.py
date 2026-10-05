"""MkDocs hook: point out-of-docs relative links at GitHub.

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

Registered from ``mkdocs.yml`` under ``hooks:``. No plugin dependency.
"""

import posixpath
import re
import sys
from pathlib import Path

# Same shape as scripts/check-doc-links.py -- a link inside a fence or backticks
# is an example, not a link, and must not be rewritten.
FENCE = re.compile(r"^\s*(?:```|~~~)")
# A code span is delimited by a *run* of backticks, and the run length must match:
# ``[`ReadTool`](x)`` is one span, not an empty span followed by a link.
INLINE_CODE = re.compile(r"(`+)(?:(?!\1).)*\1")
LINK = re.compile(r"(!?\[[^\]]*\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")
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
    if not repo_url:
        return markdown

    docs_dir = Path(config["docs_dir"]).resolve()
    page_dir = posixpath.dirname(page.file.src_uri)
    excluded = _excluder(config, docs_dir)

    out, fenced = [], False
    for line in markdown.splitlines():
        if FENCE.match(line):
            fenced = not fenced
            out.append(line)
            continue
        out.append(line if fenced else _rewrite(line, docs_dir, page_dir, repo_url, excluded))

    return "\n".join(out)


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


def _rewrite(line, docs_dir, page_dir, repo_url, excluded):
    # Inline code is skipped by *position*, not by slicing the line up: link text
    # is very often backticked here (``[`ReadTool`](...)``), and cutting the line
    # at the code span would tear that link in half and leave it unrewritten.
    protected = [(m.start(), m.end()) for m in INLINE_CODE.finditer(line)]

    def replace(match):
        if any(start <= match.start(2) < end for start, end in protected):
            return match.group(0)
        prefix, target, suffix = match.groups()
        if EXTERNAL.match(target):
            return match.group(0)

        path_part, _, anchor = target.partition("#")
        if not path_part:
            return match.group(0)

        resolved = Path(posixpath.normpath(posixpath.join(page_dir, path_part)))
        # normpath keeps leading '..' when the path climbs out of docs_dir.
        if not str(resolved).startswith(".."):
            # Inside docs/: the only ones that need help are the ones that are
            # not built. Everything else stays relative and MkDocs resolves it.
            inside = excluded(resolved.as_posix())
            if inside is None:
                return match.group(0)
            url = _github_url(repo_url, inside)
            return f"{prefix}{url}{'#' + anchor if anchor else ''}{suffix}"

        outside = (docs_dir / resolved).resolve()
        try:
            relative = outside.relative_to(_repo_root)
        except ValueError:
            # Climbs above the repository itself -- leave it for the link checker.
            return match.group(0)

        url = _github_url(repo_url, relative.as_posix())
        return f"{prefix}{url}{'#' + anchor if anchor else ''}{suffix}"

    return LINK.sub(replace, line)
