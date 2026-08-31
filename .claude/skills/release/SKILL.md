---
name: release
description: Cut a release — bump the version, run the quality gate, publish to Maven Central, then tag and push. Use when you want to publish a new version of AIMON to Maven Central.
user-invocable: true
allowed-tools: "Bash"
---

# Release

Publishes a new AIMON version to Maven Central via `scripts/release.sh`. **The script is the source
of truth** and enforces every safety gate (clean tree, on `main`, synced with origin, credentials
present, quality gate). This skill only invokes it — never reproduce the release steps by hand and
never bypass the script's gates.

## Usage

- `/release` — patch bump (default)
- `/release minor` — minor bump
- `/release major` — major bump

Map the argument to `<bump>` (`patch` | `minor` | `major`, default `patch`).

## Steps

1. **Preview (dry run)** — runs all pre-flight checks + the quality gate, then stops before any
   mutation or publish:
   ```bash
   scripts/release.sh <bump> --dry-run
   ```
   Report the `current → next` version and the gate result. If the script aborts, relay its message
   verbatim and stop — do not work around it.

2. **Confirm with the user.** Publishing to Maven Central is **permanent and public**. State the
   exact version about to be released and get explicit confirmation.

3. **Release** — only after confirmation:
   ```bash
   scripts/release.sh <bump> --yes
   ```
   The script bumps `gradle.properties`, runs the quality gate, publishes to Maven Central, commits
   `chore(release): bump version to X`, tags `vX`, and pushes the commit + tag to origin.

4. **Report** the released version and remind the user that the Central Portal may take a few minutes
   to validate/release the deployment.

## Notes

- Requires Maven Central credentials + GPG signing in `~/.gradle/gradle.properties` (the script
  verifies these before any mutation).
- Quality gate = `checkAll`, the same task CI runs, so a release never passes a narrower gate than a
  PR. Opt-in tiers stay out of it in both places: `integrationTest`, `packagingTest`, `playwrightTest`.
- On failure the only side effect is an uncommitted `gradle.properties` bump; the script prints the
  revert command. Nothing is committed/tagged/pushed until the publish succeeds.
