---
name: resource-demo
description: "Verifies bundled-skill resource materialization. Loads this skill's own template, reference notes, and helper script from its directory via ${AIMON_SKILL_DIR}, then renders a small report. Use to confirm that skill-relative files under templates/, references/, and scripts/ are readable and executable. Triggers on: resource demo, skill files, materialization test, AIMON_SKILL_DIR."
arguments: [topic]
allowed-tools: "Read, Bash"
---

# Resource Demo Skill

This skill proves that a **bundled skill can load its own files at runtime**. Every file
referenced below lives next to this `SKILL.md` and is addressed through `${AIMON_SKILL_DIR}`,
which resolves to this skill's directory in the workspace (it is materialized there on startup).

The report topic is `$1` (default to `sample` if empty).

Perform these steps **in order** and show your tool calls:

1. **Read the report template** at `${AIMON_SKILL_DIR}/templates/report-template.md`.
2. **Read the reference notes** at `${AIMON_SKILL_DIR}/references/notes.md`.
3. **Run the helper script** with Bash and capture its single output line:
   `python3 ${AIMON_SKILL_DIR}/scripts/hello.py $1`
4. **Render the report** by filling the template from step 1:
   - Replace `{{TOPIC}}` with `$1`.
   - Replace `{{SCRIPT_OUTPUT}}` with the exact line printed by the script in step 3.
   - Complete the `Summary` and `Details` sections following the guidance in the reference notes.

Finally, print a one-line verdict:

- If all three files were readable/executable, say: `✅ materialization OK — read templates/, references/, scripts/ via ${AIMON_SKILL_DIR}`.
- Otherwise, list exactly which path failed and the error.
