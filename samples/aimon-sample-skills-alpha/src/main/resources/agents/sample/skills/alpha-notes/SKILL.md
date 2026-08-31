---
name: alpha-notes
description: "Records an operations note. Shipped by the alpha sample module."
---

# Alpha Notes

This skill exists to be found. It is shipped inside `aimon-sample-skills-alpha.jar`, which reaches the
application only as a dependency — nothing in the application's own resources mentions it.

The supplementary file at `reference/checklist.md` is the second half of the proof: a skill body can be read
straight off the class path, but a supplementary file can only be reached through the workspace filesystem,
so its presence on disk means the whole tree was copied out of the jar rather than just the `SKILL.md`.

Read `${AIMON_SKILL_DIR}/reference/checklist.md` before recording anything.
