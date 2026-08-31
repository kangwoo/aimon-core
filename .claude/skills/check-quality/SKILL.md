---
name: check-quality
description: Run all code quality checks — formatting, checkstyle, tests, and coverage report. Use when you want to verify code quality before committing.
user-invocable: true
allowed-tools: "Bash"
---

# Code Quality Check

Run the full quality pipeline for the AIMON project.

## Steps

1. **Format check**
   ```bash
   ./gradlew checkFormat
   ```

2. **Checkstyle**
   ```bash
   ./gradlew checkStyle
   ```

3. **Tests** (excluding infra-dependent tests)
   ```bash
   ./gradlew test -x :aimon-filesystem-gridfs:test -x :aimon-filesystem-s3:test
   ```

4. **Coverage report**
   ```bash
   ./gradlew jacocoTestReport
   ```

## On Failure

- Format issues: run `./gradlew format` to auto-fix
- Checkstyle issues: review `build/reports/checkstyle/main.html` in the failing module
- Test failures: review test reports in `build/reports/tests/test/index.html`

Report a summary of results for each step: pass/fail and key issues found.
