# Migrating CustomCommand → Skill

`CustomCommand` (`.aimon/commands/<name>.md`) was **deprecated in AIMON 0.0.37** and **removed in 0.1.0** (SK-08-F).
Migrate any remaining `.aimon/commands/*.md` files to the unified Skill format at `.aimon/skills/<name>/SKILL.md`
**before** upgrading to 0.1.0 — on 0.1.0 the registry refuses to start while a legacy file is present.

> Why? AIMON now has a single, richer Skill format that supports both **user invocation** (`/<name>`) and **model
> invocation** (Skill tool) from a single source of truth. Keeping two parallel formats forces every feature
> (renderer, frontmatter key, allowed-tools mapping) to be implemented twice. See
> `docs/design/skill/command-unification.md` for the full design.

## Timeline

| Version  | Behavior                                                                                                  |
|----------|-----------------------------------------------------------------------------------------------------------|
| 0.0.37   | `.aimon/commands/*.md` still loads. WARN on every load. `/commands` shows `[deprecated]`.                 |
| 0.1.0    | Loader, parser, and `LlmCommandExecutor` removed. Startup fails with `CommandException` if any `.aimon/commands/*.md` is present. |

The deprecation window was one minor version.

## What changes for users

- File location: `.aimon/commands/<name>.md` → `.aimon/skills/<name>/SKILL.md` (per-skill directory).
- Frontmatter gains a required `name` field and a new `invoke` block.
- Body content (`$ARGUMENTS`, `` !`cmd` ``, `@file` placeholders) is **unchanged** — the renderer is identical.

## Frontmatter mapping

| CustomCommand frontmatter | Skill frontmatter           | Notes                                                              |
|---------------------------|-----------------------------|--------------------------------------------------------------------|
| `description: <text>`     | `description: <text>`       | Identical.                                                         |
| `allowed-tools: a, b, c`  | `allowed-tools:` (YAML list) | List form is preferred. Comma-separated string still parses.       |
| _(implicit from filename)_ | `name: <name>`             | Skills require an explicit `name`. Use the original filename stem. |
| _(none)_                  | `invoke:` block             | `user: true` enables `/<name>` invocation; `model: false` hides it from the Skill tool. Set `model: true` if the agent should also be able to invoke it autonomously. |
| _(none)_                  | `max-iterations: <int>`     | Optional. Defaults to the agent-level cap.                         |

Body content under the closing `---` is copied verbatim.

## Before → After example

### Before — `.aimon/commands/commit.md`

```markdown
---
description: Create a git commit with proper message
allowed-tools: Bash(git add:*), Bash(git commit:*), Read
---

## Task
Create a commit with a proper message.

Arguments: $ARGUMENTS

Recent log:
!`git log -5 --oneline`
```

### After — `.aimon/skills/commit/SKILL.md`

```markdown
---
name: commit
description: Create a git commit with proper message
allowed-tools:
  - Bash(git add:*)
  - Bash(git commit:*)
  - Read
invoke:
  user: true
  model: false
---

## Task
Create a commit with a proper message.

Arguments: $ARGUMENTS

Recent log:
!`git log -5 --oneline`
```

After migration the user invokes it the same way: `/commit Fix authentication bug`.

## Automated conversion

Use the bundled script for batch migration:

```bash
# Migrate one file
./scripts/migrate-custom-command-to-skill.sh .aimon/commands/commit.md

# Migrate every legacy command in a project
./scripts/migrate-custom-command-to-skill.sh .aimon/commands/*.md
```

The script:

1. Creates `.aimon/skills/<name>/SKILL.md`.
2. Copies the original body unchanged.
3. Inserts `name: <name>` and an `invoke:` block (`user: true`, `model: false`) into the frontmatter.
4. Preserves existing `description` and `allowed-tools` keys.
5. Skips (with a warning) when the destination already exists — never overwrites.

The original `.aimon/commands/<name>.md` is **not** deleted automatically. The legacy file must be removed before
upgrading to 0.1.0 — on 0.1.0 startup fails fast if any `.aimon/commands/*.md` remains. On 0.0.37 you can verify
the migration by checking that the `[DEPRECATION] CustomCommand '<name>' loaded` WARN disappears for that name,
then delete the legacy file manually or rerun the script with `--delete-original`.

## Verification checklist

### On 0.0.37 (during migration)

- [ ] `.aimon/skills/<name>/SKILL.md` exists and parses (no startup ERROR for the skill).
- [ ] `/commands` lists `<name>` under **Skill commands:** (not under Custom commands).
- [ ] No `[DEPRECATION] CustomCommand '<name>' loaded` WARN for the migrated name.
- [ ] `/<name> <args>` still produces the expected output.
- [ ] Legacy `.aimon/commands/<name>.md` removed after verification.

### After upgrading to 0.1.0

- [ ] Application starts without a `CommandException` referencing `Legacy CustomCommand`.
- [ ] `/commands` shows the migrated name under **Skill commands:** (the **Custom commands:** section no longer
      exists in 0.1.0).
- [ ] `/<name> <args>` produces the same output as before the upgrade.

## When to keep using CustomCommand

There is no remaining use case. Every CustomCommand feature has a direct equivalent in the Skill format. New
commands should be authored as Skills from the start.

## Troubleshooting

| Symptom                                                                              | Version | Likely cause                                                               | Fix                                                                                                |
|--------------------------------------------------------------------------------------|---------|----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `/<name>` shows `Command not found` after migration                                  | any     | `invoke.user` is missing or `false`                                        | Set `invoke: { user: true }`.                                                                      |
| Startup fails with `Legacy CustomCommand files detected: …` on upgrade               | 0.1.0   | `.aimon/commands/*.md` still exists and was not migrated before upgrading | Migrate per this guide and remove the legacy directory; restart.                                   |
| Skill loads but `/commands` still shows `[deprecated]`                               | 0.0.37  | Legacy `.aimon/commands/<name>.md` still exists                            | Remove the legacy file.                                                                            |
| `allowed-tools` parsed as a single string                                            | any     | YAML quoting collapsed the list                                            | Use the explicit list form (one tool per line under `allowed-tools:`).                             |
| WARN log persists for an already-migrated skill                                      | 0.0.37  | The legacy file was not deleted, and the loader is still picking it up    | Remove `.aimon/commands/<name>.md`.                                                                |

## Related

- Design: `docs/design/skill/command-unification.md`
- Conversion script: `scripts/migrate-custom-command-to-skill.sh`
- Skill format reference: see `MarkdownSkillParser` Javadoc in
  `modules/aimon-core/src/main/java/at/aimon/core/ext/skill/parser/MarkdownSkillParser.java`.
