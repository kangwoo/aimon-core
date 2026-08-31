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

Registered from ``mkdocs.yml`` under ``hooks:``. No plugin dependency.
"""

import posixpath
import re
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


def on_page_markdown(markdown, page, config, files, **kwargs):
    repo_url = config.get("repo_url")
    if not repo_url:
        return markdown

    docs_dir = Path(config["docs_dir"]).resolve()
    page_dir = posixpath.dirname(page.file.src_uri)

    out, fenced = [], False
    for line in markdown.splitlines():
        if FENCE.match(line):
            fenced = not fenced
            out.append(line)
            continue
        out.append(line if fenced else _rewrite(line, docs_dir, page_dir, repo_url))

    return "\n".join(out)


def _rewrite(line, docs_dir, page_dir, repo_url):
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
            return match.group(0)

        outside = (docs_dir / resolved).resolve()
        try:
            relative = outside.relative_to(_repo_root)
        except ValueError:
            # Climbs above the repository itself -- leave it for the link checker.
            return match.group(0)

        url = _github_url(repo_url, relative.as_posix())
        return f"{prefix}{url}{'#' + anchor if anchor else ''}{suffix}"

    return LINK.sub(replace, line)
