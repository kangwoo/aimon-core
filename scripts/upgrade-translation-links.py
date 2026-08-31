#!/usr/bin/env python3
"""Point a translation's relative links at sibling translations, once they exist.

A translated file links to `foo.md` while `foo` is untranslated and to
`foo.en.md` once it is not. Both render identically on the site -- the i18n
plugin resolves them to the same page -- so this only matters on GitHub, which
opens files directly and has no plugin to fall back with.

That makes the rule mechanical: after a translation batch lands, some links in
*earlier* batches now have a translated target and should be upgraded. This
finds them. It never downgrades -- a `.en.md` link whose target vanished is a
broken link, and reporting it is `check-doc-links.py`'s job, not silently
papering over it here.

Two links are deliberately left alone. A translation's link back to its own
canonical (the "English:" pointer at the top of CONTRIBUTING.ko.md) upgrades
to a link to itself, which is worse than useless. And a link with an #anchor
only upgrades when the translated target actually has that anchor -- headings
are translated, so carrying the source-language fragment over would write a
dead anchor that `check-doc-links.py` then rejects.

    python3 scripts/upgrade-translation-links.py          # report only
    python3 scripts/upgrade-translation-links.py --write  # apply

Root docs (`CONTRIBUTING.md` and friends) are canonically English, so their
translations carry `.ko.md`; the same rule applies with the suffix flipped.
"""

import re
import sys
from pathlib import Path

from docs_tree import SKIP_DIRS, anchors_of, slug, translation_suffix

FENCE = re.compile(r"^\s*(?:```|~~~)")
INLINE_CODE = re.compile(r"(`+)(?:(?!\1).)*\1")
LINK = re.compile(r"(!?\[[^\]]*\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")
EXTERNAL = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//|#|<)", re.IGNORECASE)

ROOT = Path(__file__).resolve().parent.parent

_anchor_cache = {}


def has_anchor(path, fragment):
    if path not in _anchor_cache:
        _anchor_cache[path] = anchors_of(path.read_text(encoding="utf-8", errors="replace"))
    return slug(fragment) in _anchor_cache[path]


def upgrade(path, suffix):
    text = path.read_text(encoding="utf-8")
    hits = []
    out, fenced = [], False

    for line in text.splitlines(keepends=False):
        if FENCE.match(line):
            fenced = not fenced
            out.append(line)
            continue
        if fenced:
            out.append(line)
            continue

        # Skip by position, not by slicing: link text here is very often
        # backticked, and cutting the line at a code span tears the link apart.
        protected = [(m.start(), m.end()) for m in INLINE_CODE.finditer(line)]

        def replace(match):
            if any(a <= match.start(2) < b for a, b in protected):
                return match.group(0)
            prefix, target, tail = match.groups()
            if EXTERNAL.match(target):
                return match.group(0)

            file_part, _, anchor = target.partition("#")
            if not file_part.endswith(".md") or file_part.endswith(suffix + ".md"):
                return match.group(0)

            translated = file_part[: -len(".md")] + suffix + ".md"
            resolved = (path.parent / translated).resolve()
            if not resolved.is_file():
                return match.group(0)
            # A translation linking its own canonical is a language switch,
            # not a stale link -- upgrading it would make it point at itself.
            if resolved == path.resolve():
                return match.group(0)
            # Headings are translated, so the source-language anchor usually
            # does not exist in the translated file. Upgrade only when it does;
            # otherwise the canonical-with-anchor link stays correct as it is.
            if anchor and not has_anchor(resolved, anchor):
                return match.group(0)

            hits.append((target, translated + ("#" + anchor if anchor else "")))
            return f"{prefix}{translated}{'#' + anchor if anchor else ''}{tail}"

        out.append(LINK.sub(replace, line))

    return "\n".join(out) + ("\n" if text.endswith("\n") else ""), hits


def main():
    write = "--write" in sys.argv
    total = 0

    for path in sorted(ROOT.rglob("*.md")):
        if any(part in SKIP_DIRS for part in path.relative_to(ROOT).parts):
            continue
        suffix = translation_suffix(path)
        if suffix is None:
            continue

        updated, hits = upgrade(path, suffix)
        if not hits:
            continue
        total += len(hits)
        print(f"{path.relative_to(ROOT)}")
        for old, new in hits:
            print(f"    {old}  ->  {new}")
        if write:
            path.write_text(updated, encoding="utf-8")

    if total == 0:
        print("no links to upgrade")
    elif not write:
        print(f"\n{total} link(s) would be upgraded; re-run with --write to apply")
    else:
        print(f"\nupgraded {total} link(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
