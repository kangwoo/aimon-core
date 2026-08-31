"""What every docs script must agree on, defined once.

Three scripts walk the same markdown tree -- check-doc-links.py,
check-translation-staleness.py and upgrade-translation-links.py -- and each
needs the same three answers: which directories are not ours to scan, which
files are translations, and how a heading turns into an anchor. Each script
carrying its own copy is how the copies drift (`.venv` was in one skip set and
missing from the other two), so the answers live here and the scripts import
them.
"""

import re
import unicodedata

# Trees that contain markdown nobody here wrote: build outputs, vendored
# dependencies, and the virtualenv that installs docs-requirements.txt.
SKIP_DIRS = {".git", ".gradle", ".venv", "build", "node_modules", "site"}

# The locales the site builds (mkdocs.yml declares the same pair). A file is a
# translation exactly when its name ends `.<one of these>.md`; adding a locale
# here is what makes all three scripts see it.
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
    """Every fragment that resolves inside one markdown file."""
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
