#!/usr/bin/env bash
#
# AIMON release: bump version, run the quality gate, publish to Maven Central, then tag & push.
#
# Usage:
#   scripts/release.sh [patch|minor|major] [--yes] [--dry-run]
#
#   patch|minor|major   semantic bump of VERSION_NAME in gradle.properties (default: patch)
#   --yes, -y           skip the interactive "type the version" confirmation (for automation)
#   --dry-run           run all checks + the quality gate, then stop before any mutation/publish
#
# Order of operations (publish is irreversible, so git history is only pushed AFTER a successful
# publish; on failure the only side effect is an uncommitted gradle.properties bump, easily reverted):
#   pre-flight → quality gate → confirm → bump (uncommitted) → publish → commit + tag → push
#
set -euo pipefail

# ── args ────────────────────────────────────────────────────────────────────
BUMP="patch"
ASSUME_YES=0
DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        patch | minor | major) BUMP="$arg" ;;
        --yes | -y) ASSUME_YES=1 ;;
        --dry-run) DRY_RUN=1 ;;
        *)
            echo "Unknown argument: $arg" >&2
            echo "Usage: scripts/release.sh [patch|minor|major] [--yes] [--dry-run]" >&2
            exit 2
            ;;
    esac
done

cd "$(git rev-parse --show-toplevel)"

# JAVA_TOOL_OPTIONS often carries -Xms (e.g. -Xms1g) from the shell, which clashes with the Gradle
# worker daemon's smaller default -Xmx ("Initial heap size set to a larger value than the maximum").
# Pin a max-only override for every Gradle invocation here.
export JAVA_TOOL_OPTIONS="-Xmx3g"
GRADLE="./gradlew --console=plain"

log() { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }
ok() { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
fail() {
    printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2
    exit 1
}

cleanup() {
    local rc=$?
    if [ $rc -ne 0 ] && ! git diff --quiet -- gradle.properties 2>/dev/null; then
        echo "" >&2
        echo "Note: gradle.properties has an uncommitted version bump. To revert: git checkout -- gradle.properties" >&2
    fi
}
trap cleanup EXIT

# ── 1. pre-flight ───────────────────────────────────────────────────────────
log "Pre-flight checks"
[ -f gradle.properties ] || fail "gradle.properties not found — run from the aimon-core repo"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "main" ] || fail "Releases must be cut from 'main' (currently on '$BRANCH')"

git diff --quiet && git diff --cached --quiet || fail "Working tree is not clean — commit or stash changes first"

git fetch --quiet origin main
LOCAL_REV="$(git rev-parse @)"
REMOTE_REV="$(git rev-parse '@{u}')"
[ "$LOCAL_REV" = "$REMOTE_REV" ] || fail "Local 'main' is not in sync with origin/main — pull/push first"
ok "Clean working tree on main, in sync with origin"

# The quality gate in §4 runs `integrationTest`, which is Testcontainers and therefore needs a daemon.
# Checked here rather than being discovered by Gradle several minutes in: everything between this line
# and the gate — credential checks, the version bump write — is work thrown away when it turns out the
# release could never have passed. Fail, do not warn: a gate that skips itself when the daemon is absent
# would make the strictest-looking setup the weakest one, which is the whole reason the tier was gated.
docker info >/dev/null 2>&1 || fail "Docker daemon is not running — the release gate runs integrationTest (@Tag(\"docker\")). Start Docker and re-run."
ok "Docker daemon reachable"

# ── 2. credentials (names only; never print values) ─────────────────────────
log "Verifying Maven Central + signing credentials"
GP="$HOME/.gradle/gradle.properties"
require_cred() {
    local key="$1"
    grep -q "^${key}=" "$GP" 2>/dev/null && return 0
    [ -n "$(printenv "ORG_GRADLE_PROJECT_${key}" 2>/dev/null)" ] && return 0
    fail "Missing publish credential '${key}' — set it in ~/.gradle/gradle.properties or env ORG_GRADLE_PROJECT_${key}"
}
require_cred mavenCentralUsername
require_cred mavenCentralPassword
if ! grep -qE '^signing\.(keyId|secretKeyRingFile)=' "$GP" 2>/dev/null \
    && [ -z "$(printenv ORG_GRADLE_PROJECT_signingInMemoryKey 2>/dev/null)" ]; then
    fail "Missing GPG signing config (signing.keyId / signing.secretKeyRingFile or signingInMemoryKey)"
fi
ok "Credentials present"

# ── 3. compute next version ─────────────────────────────────────────────────
CURRENT="$(grep '^VERSION_NAME=' gradle.properties | head -1 | cut -d= -f2 | tr -d '[:space:]')"
[[ "$CURRENT" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || fail "VERSION_NAME='$CURRENT' is not in X.Y.Z form"
MAJ="${BASH_REMATCH[1]}"
MIN="${BASH_REMATCH[2]}"
PAT="${BASH_REMATCH[3]}"
case "$BUMP" in
    major)
        MAJ=$((MAJ + 1))
        MIN=0
        PAT=0
        ;;
    minor)
        MIN=$((MIN + 1))
        PAT=0
        ;;
    patch) PAT=$((PAT + 1)) ;;
esac
NEXT="${MAJ}.${MIN}.${PAT}"
TAG="v${NEXT}"
log "Version bump (${BUMP}): ${CURRENT} → ${NEXT}   (tag ${TAG})"
git rev-parse "$TAG" >/dev/null 2>&1 && fail "Tag ${TAG} already exists"

# The GitHub Release body is cut from CHANGELOG.md by .github/workflows/release.yml, which triggers on
# the tag this script pushes. No section means a Release that just says "see CHANGELOG" — which is how
# v0.1.17 through v0.2.2 ended up with an empty Releases page. Warn rather than fail: an out-of-band
# release is still a legitimate thing to want, it just should not be an accident.
if ! grep -q "^## \[${NEXT}\]" CHANGELOG.md; then
    printf '\033[1;33m! CHANGELOG.md has no "## [%s]" section.\033[0m\n' "$NEXT" >&2
    printf '  The GitHub Release will fall back to a pointer instead of real notes.\n' >&2
    printf '  Finalize the [Unreleased] section as [%s] first if you want notes.\n' "$NEXT" >&2
fi

# ── 4. quality gate ─────────────────────────────────────────────────────────
# This is deliberately the SAME task CI runs (.github/workflows/build.yml) — a release must not pass a
# gate narrower than the one every PR already clears. `checkAll` = checkFormat + checkStyle + every
# module's `test` + the BOM's `verifyBom`.
#
# It once read `test spotlessCheck` with a note that checkstyle had "pre-existing warnings"; that was
# never true of this build — checkstyle here is severity=error with maxErrors=0 and an empty
# suppressions file, so it has no warning tier to accumulate. The gridfs/s3 `-x` exclusions predate the
# @Tag("docker") convention, which already keeps Testcontainers tests out of `test`.
#
# `integrationTest` (@Tag("docker")) joined the gate because "opt-in" had a cost nobody had priced. For
# aimon-filesystem-{gridfs,s3}, aimon-session-{redis,postgres,mongodb} and aimon-memory-{postgres,mongodb}
# those ARE the tests — leaving them opt-in meant this script published seven artifacts whose only
# verification had never run, and a Maven Central publish cannot be taken back. THIS MEANS A RELEASE NOW
# NEEDS A RUNNING DOCKER DAEMON. That is the price, and it is the right way round: the machine that
# publishes should be the machine that can prove what it publishes.
#
# Both tasks stay on ONE `$GRADLE` line on purpose. ReleaseGateMatchesCiGateTest reads the first
# `$GRADLE` invocation after this section marker and compares its task list against CI's; a second line
# would be invisible to it, and the gate would silently stop matching CI.
#
# `packagingTest` (@Tag("packaging")) joined on a narrower argument than integrationTest's. It is not the
# only verification any module has; it is the only one that can see a fat jar at all. Packaging turns
# resource lookup into jar-entry enumeration, and when that breaks the skill list comes back silently short
# instead of failing — a regression this framework has actually shipped. Every other test here runs off a
# directory class path, where that code path does not exist. The task builds both fat jars itself and costs
# under a minute, which is why it is gated on the same line rather than argued about.
#
# `jacocoTestCoverageVerification` is here rather than exempted because it can fail a build, and the rule
# this script is held to is that a release passes no narrower a gate than a pull request. It costs nothing
# extra: unlike CI, where the tiers run in separate jobs and a third job reassembles their execution data,
# everything above already ran in THIS workspace, so the floor is checked against the complete picture.
#
# Still opt-in and therefore NOT gated here, same as in CI: `playwrightTest` (@Tag("playwright")), which
# needs browser binaries installed and guards a surface no consumer has yet.
log "Quality gate: checkAll + integrationTest + packagingTest + coverage floor"
$GRADLE checkAll integrationTest packagingTest jacocoTestCoverageVerification
ok "Quality gate passed"

if [ "$DRY_RUN" = 1 ]; then
    echo ""
    ok "Dry run complete. Would: bump to ${NEXT}, publish to Maven Central, commit, tag ${TAG}, push."
    exit 0
fi

# ── 5. confirm (publish is permanent) ───────────────────────────────────────
if [ "$ASSUME_YES" != 1 ]; then
    echo ""
    printf '\033[1;33mPublishing %s to Maven Central is PERMANENT and PUBLIC.\033[0m\n' "$NEXT"
    printf 'Type the version (%s) to confirm: ' "$NEXT"
    read -r reply
    [ "$reply" = "$NEXT" ] || fail "Confirmation did not match — aborted (no changes made)"
fi

# ── 6. bump (uncommitted) → publish → commit + tag → push ───────────────────
log "Writing VERSION_NAME=${NEXT}"
perl -i -pe "s{^VERSION_NAME=.*}{VERSION_NAME=${NEXT}}" gradle.properties

log "Publishing to Maven Central (Central Portal)…"
$GRADLE publishAllPublicationsToMavenCentralRepository

log "Committing + tagging"
git add gradle.properties
git commit -q -m "chore(release): bump version to ${NEXT}"
git tag -a "$TAG" -m "Release ${NEXT}"

log "Pushing commit + tag to origin"
git push origin main
git push origin "$TAG"

echo ""
ok "Released ${NEXT}. Central Portal may take a few minutes to validate and release the deployment."
echo "  The pushed tag triggers .github/workflows/release.yml, which creates the GitHub Release."
