#!/usr/bin/env bash
# Migrate one or more legacy CustomCommand files to the unified Skill format.
#
#   .aimon/commands/<name>.md  ->  .aimon/skills/<name>/SKILL.md
#
# The original frontmatter (description, allowed-tools) is preserved. We add:
#   - name: <name>
#   - invoke: { user: true, model: false }
#
# The body content (everything after the closing `---`) is copied verbatim,
# including $ARGUMENTS, !`cmd`, and @file placeholders — the renderer treats
# them identically.
#
# Usage:
#   scripts/migrate-custom-command-to-skill.sh [--delete-original] FILE...
#
# Behavior:
#   - Skips (warns) when the destination SKILL.md already exists.
#   - Never overwrites existing files unless they were created in this run.
#   - --delete-original removes the source file ONLY after a successful write.
#   - Idempotent: running again on already-migrated input is a no-op (skipped).
#
# Exits non-zero when any input fails. Successful skips are not failures.
#
# Reference: docs/migration/custom-command-to-skill.md
set -euo pipefail

DELETE_ORIGINAL=0
declare -a FILES=()

usage() {
    sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        --delete-original) DELETE_ORIGINAL=1 ;;
        -h|--help) usage; exit 0 ;;
        --) ;;
        -*) echo "Unknown option: $arg" >&2; usage >&2; exit 2 ;;
        *)  FILES+=("$arg") ;;
    esac
done

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "Error: no input files." >&2
    usage >&2
    exit 2
fi

warn() { echo "WARN: $*" >&2; }
err()  { echo "ERROR: $*" >&2; }

migrate_one() {
    local src="$1"

    if [[ ! -f "$src" ]]; then
        err "Not a file: $src"
        return 1
    fi

    local base
    base="$(basename -- "$src" .md)"
    if [[ -z "$base" || "$base" == "$(basename -- "$src")" ]]; then
        err "Source must end with .md: $src"
        return 1
    fi

    local commands_dir
    commands_dir="$(dirname -- "$src")"
    local project_root
    project_root="$(dirname -- "$commands_dir")"
    local skills_dir="${project_root}/skills/${base}"
    local dst="${skills_dir}/SKILL.md"

    if [[ -e "$dst" ]]; then
        warn "Destination already exists, skipping: $dst"
        return 0
    fi

    # Detect frontmatter delimiters. A valid CustomCommand starts with `---`
    # on line 1 and has a matching `---` line later. If absent, treat the
    # entire file as body and synthesize fresh frontmatter.
    # NOTE: We pipe via stdin because BSD awk/sed on macOS reject `-- file`
    # as a positional separator.
    local has_fm=0
    local fm_end=0
    if [[ "$(head -n 1 -- "$src")" == "---" ]]; then
        # Find the closing `---` (line number, starting after line 1).
        fm_end="$(awk 'NR>1 && $0=="---" {print NR; exit}' < "$src" || true)"
        if [[ -n "$fm_end" && "$fm_end" -ge 2 ]]; then
            has_fm=1
        else
            warn "Opening --- without closing ---; treating whole file as body: $src"
            fm_end=0
        fi
    fi

    mkdir -p -- "$skills_dir"

    # Build the new file in a temp location, then atomically move it into place.
    local tmp
    tmp="$(mktemp -t skill-migrate.XXXXXX)"
    trap 'rm -f -- "$tmp"' RETURN

    {
        echo "---"
        echo "name: ${base}"
        if [[ "$has_fm" -eq 1 ]]; then
            # Strip any pre-existing `name:` line from the original frontmatter
            # (rare, but be defensive) and emit the rest unchanged.
            sed -n "2,$((fm_end - 1))p" < "$src" | grep -Ev '^name:[[:space:]]' || true
        else
            : # nothing to copy
        fi
        echo "invoke:"
        echo "  user: true"
        echo "  model: false"
        echo "---"
        if [[ "$has_fm" -eq 1 ]]; then
            # Body starts after the closing `---`.
            sed -n "$((fm_end + 1)),\$p" < "$src"
        else
            # No frontmatter — entire file is body.
            cat -- "$src"
        fi
    } > "$tmp"

    mv -- "$tmp" "$dst"
    trap - RETURN
    echo "Migrated: $src -> $dst"

    if [[ "$DELETE_ORIGINAL" -eq 1 ]]; then
        rm -- "$src"
        echo "Removed:  $src"
    fi
}

rc=0
for f in "${FILES[@]}"; do
    if ! migrate_one "$f"; then
        rc=1
    fi
done

exit "$rc"
