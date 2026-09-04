---
name: release
description: Cut a release — finalize the changelog, bump the version, run the quality gate, publish to Maven Central, then tag and push. Use when you want to publish a new version of AIMON to Maven Central.
user-invocable: true
allowed-tools: "Bash, Read, Edit"
---

# Release

Publishes a new AIMON version to Maven Central via `scripts/release.sh`. **The script is the source
of truth** and enforces every safety gate (clean tree, on `main`, synced with origin, Docker daemon
reachable, credentials present, quality gate). This skill only invokes it — never reproduce the
release steps by hand and never bypass the script's gates.

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

2. **Finalize the changelog if the script warned about it.** A missing `## [X.Y.Z]` section is a
   warning, not an abort — the release still succeeds, but the GitHub Release gets a bare pointer
   instead of notes. `docs/project/publishing-guide.md` treats promoting `[Unreleased]` to `[X.Y.Z]`
   as part of the procedure, so **offer it rather than skipping past the warning**; shipping the
   pointer is a legitimate choice, but it should be the user's.

   If they want notes:
   - Retitle `## [Unreleased]` as `## [X.Y.Z] - YYYY-MM-DD` and leave a fresh empty `[Unreleased]`.
     `release.yml` matches the heading on `[X.Y.Z]` **including the brackets**, so `[0.1.1]` does not
     match `[0.1.10]`.
   - Repoint any `` `[Unreleased]` `` cross-references that now name a heading which no longer
     exists. Leave prose that narrates the file's own past state in past tense.
   - Check the extracted body against the 125,000-character cap (see Notes).

   IMPORTANT: **this cannot ride along in the release commit.** `release.sh` requires a clean, in-sync
   `main` and stages only `gradle.properties`, so the changelog edit must be committed **and pushed**
   to `main` before step 4 — otherwise pre-flight fails on a dirty tree, or the tagged tree lacks the
   section the Release workflow reads.

3. **Confirm with the user.** Publishing to Maven Central is **permanent and public**. State the
   exact version about to be released and get explicit confirmation.

4. **Release** — only after confirmation:
   ```bash
   scripts/release.sh <bump> --yes
   ```
   The script bumps `gradle.properties`, runs the quality gate, publishes to Maven Central, commits
   `chore(release): bump version to X`, tags `vX`, and pushes the commit + tag to origin.

5. **Verify the GitHub Release.** The pushed tag triggers `.github/workflows/release.yml`, which cuts
   the `## [X.Y.Z]` section into a Release body. It runs *after* the Central publish, so a failure
   here never affects the published artifacts and is re-runnable via `workflow_dispatch` with the tag
   — but it fails silently from the user's side, so check it:
   ```bash
   gh run watch "$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
   gh release view "v<version>" --json name,tagName --jq '{name, tagName}'
   ```

6. **Report** the released version and remind the user that the Central Portal may take a few minutes
   to validate/release the deployment.

## Notes

- Requires Maven Central credentials + GPG signing in `~/.gradle/gradle.properties` (the script
  verifies these before any mutation).
- **Docker must be running.** The gate includes `integrationTest` (Testcontainers), so the script
  fails fast on a missing daemon rather than discovering it minutes in.
- Quality gate = `checkAll integrationTest packagingTest jacocoTestCoverageVerification` — the same
  set `.github/workflows/build.yml` runs, so a release never passes a narrower gate than a PR.
  `ReleaseGateMatchesCiGateTest` enforces the match, because the two lists drifted once already.
  `playwrightTest` is the only opt-in tier outside both.
- **Release bodies are capped at 125,000 characters.** GitHub rejects a longer body with a 422 that
  fails the whole `gh release create` call — publishing *nothing*, not even the missing-section
  pointer. `scripts/cap-release-notes.py` truncates on a line boundary and links to the full section,
  so this is handled; it matters when judging whether a consolidated section is worth splitting.
- On failure the only side effect is an uncommitted `gradle.properties` bump; the script prints the
  revert command. Nothing is committed/tagged/pushed until the publish succeeds.
