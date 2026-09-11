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
#   provider-key check → pre-flight → quality gate → confirm → bump (uncommitted) → publish → commit + tag → push
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

log() { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }
ok() { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
fail() {
    printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2
    exit 1
}

# ── 0. provider API keys ────────────────────────────────────────────────────
# The live-API test classes in aimon-llm-anthropic and aimon-llm-openai carry no tag: what keeps them out of
# `checkAll` is @EnabledIfEnvironmentVariable on their provider's key, and nothing else. With ANTHROPIC_KEY or
# OPENAI_KEY in this environment the gate in §4 would run them — calls billed to that key's account, and a gate
# that can go red for a reason on the provider's side (a key that is no longer valid fails on HTTP 401). CI has
# no key, so the gate would also stop being the one CI runs, which is the promise §4 is built on.
#
# Refuse rather than unset. The key stays exported in the shell this script was started from, and every later
# `./gradlew test` or `checkAll` there bills the same way (CONTRIBUTING.md › Live-API tests); unsetting it in
# here would fix one command of that shell and tell the operator nothing. A key set to the empty string is
# refused too: the check asks whether the variable exists, and it never expands the value. AIMON_DOCKER_IT and
# AIMON_KUBERNETES_IT gate two more classes the same way but bill nothing, and are not refused; whether the
# release gate should inherit them is backlog LA-2 (docs/backlog/live-api-test-tier.md).
#
# This runs first. It needs nothing from the repository; a shell about to be refused should not trigger
# `git fetch` or `docker info` first; and a refusal is not a failed release, so the EXIT trap's
# gradle.properties note must not print beside it. It runs after the argument loop, so a bad argument still
# gets exit 2 and the usage line. ReleaseGateMatchesCiGateTest runs this file from an empty directory with a
# PATH holding only a stub `git` that records its calls, and fails if this check moves above the argument
# loop, below the `cd` that calls `git`, below `trap cleanup EXIT` (the trap calls `git` when a refusal exits
# non-zero), or below `log "Pre-flight checks"`.
provider_keys_set=""
for name in ANTHROPIC_KEY OPENAI_KEY; do
    # `+set` asks whether the variable exists without expanding its value.
    if [ -n "${!name+set}" ]; then
        provider_keys_set="${provider_keys_set:+$provider_keys_set }$name"
    fi
done
if [ -n "$provider_keys_set" ]; then
    unset_flags=""
    for name in $provider_keys_set; do
        unset_flags="$unset_flags -u $name"
    done
    case "$provider_keys_set" in
        *" "*)
            keys_are="${provider_keys_set/ / and } are"
            pronoun="them"
            ;;
        *)
            keys_are="$provider_keys_set is"
            pronoun="it"
            ;;
    esac
    printf '%s\n' \
        "${keys_are} set in this environment. This script never prints a key's value." \
        "The live-API test classes are gated on nothing but a provider key, so the quality gate would run them:" \
        "calls billed to that key's account, and a gate that can fail for a reason on the provider's side. CI runs" \
        "this gate without a key, and a release must too. Unset ${pronoun} and re-run, which also keeps later builds" \
        "in this shell from billing (CONTRIBUTING.md › Live-API tests):" \
        "    unset ${provider_keys_set}" \
        "or keep ${pronoun} out of this one run:" \
        "    env${unset_flags} scripts/release.sh${*:+ $*}" >&2
    fail "Refusing to start while a provider API key is in the environment: ${provider_keys_set}"
fi

cd "$(git rev-parse --show-toplevel)"

# JAVA_TOOL_OPTIONS often carries -Xms (e.g. -Xms1g) from the shell, which clashes with the Gradle
# worker daemon's smaller default -Xmx ("Initial heap size set to a larger value than the maximum").
# Pin a max-only override for every Gradle invocation here.
export JAVA_TOOL_OPTIONS="-Xmx3g"
GRADLE="./gradlew --console=plain"

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
# These are deliberately the SAME verification tasks CI runs (.github/workflows/build.yml), which splits
# them across three jobs and adds the report-only `jacocoTestReport` — a release must not pass a gate
# narrower than the one every PR already clears. `checkAll` = checkFormat + checkStyle + every module's
# `test` + the BOM's `verifyBom`.
#
# It once read `test spotlessCheck` with a note that checkstyle had "pre-existing warnings"; that was
# never true of this build — checkstyle here is severity=error with maxErrors=0 and an empty
# suppressions file, so it has no warning tier to accumulate. The gridfs/s3 `-x` exclusions predate the
# @Tag("docker") convention, which already keeps Testcontainers tests out of `test`.
#
# `integrationTest` (@Tag("docker")) joined the gate because "opt-in" had a cost nobody had priced. For
# aimon-filesystem-{gridfs,s3} and aimon-session-{redis,postgres,mongodb} those are the only tests that
# reach the backend — the one to six classes each keeps in `test` are codec round-trips and frozen-name
# assertions that never open a connection. Leaving the tier opt-in meant this script published five
# artifacts whose behaviour against a real server had never run, and a Maven Central publish cannot be
# taken back. (It was seven until aimon-memory-{postgres,mongodb}
# were removed.) THIS MEANS A RELEASE NOW NEEDS A RUNNING DOCKER DAEMON. That is the price, and it is
# the right way round: the machine that publishes should be the machine that can prove what it
# publishes.
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
# `playwrightTest` (@Tag("playwright")) joined last, on integrationTest's argument rather than packagingTest's:
# aimon-browser-playwright is published, and these four tests are the only ones in it that start a real browser.
# PlaywrightLifecycleManager -- which owns the browser process, the daemon worker thread and the shutdown ordering
# -- measures 9% line without them and 57% with them. The tier had never actually run anywhere: its Gradle task was
# missing `testClassesDirs` and `classpath`, so it reported NO-SOURCE and went green in 650ms.
#
# THIS MEANS A RELEASE NOW DOWNLOADS CHROMIUM ONCE, if the machine has no browser cache: 280 MB over the wire, 94s,
# 520 MB unpacked (measured 2026-09-05). Afterwards the tier costs about twenty seconds. That is a smaller demand
# than the Docker daemon integrationTest already made of this script, and it is made safely -- the task installs
# the browser as a build step, so a cold machine is slow rather than red. Before that, Playwright.create() did the
# download inline inside PlaywrightLifecycleManager's 30-second init timeout and every test failed.
#
# No tier is opt-in any more. Every @Tag in this build is a CI step and a gate task.
log "Quality gate: checkAll + integrationTest + packagingTest + playwrightTest + coverage floor"
$GRADLE checkAll integrationTest packagingTest playwrightTest jacocoTestCoverageVerification
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
