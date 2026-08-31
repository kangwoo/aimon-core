# aimon-sample-skills-alpha

A resources-only module. It carries skills for the `sample` agent bundle and **no code at all**.

That absence is the point. The claim under test is that a skill reaches the agent because it was *on the
class path* — not because some class in the same artifact pulled it in. A module with a `src/main/java`
would leave that ambiguous; this one cannot.

## What it ships

```
src/main/resources/agents/sample/skills/
├── index                          # declares: alpha-notes
└── alpha-notes/
    ├── SKILL.md
    └── reference/checklist.md     # a supplementary file
```

The supplementary file is the second half of the proof. A skill **body** can be read straight off the class
path, but a supplementary file can only be reached through the workspace filesystem — so its presence on
disk means the whole tree was copied out of the jar, not just the `SKILL.md`. `alpha-notes` reads it via
`${AIMON_SKILL_DIR}/reference/checklist.md`, which is how the assertion becomes observable in a turn.

## Why the index path collides with beta's

[`aimon-sample-skills-beta`](../aimon-sample-skills-beta) ships a file at the **exact same** resource path,
`agents/sample/skills/index`. Neither shadows the other: `ClasspathIndexReader` enumerates every root
through `getResources()` and merges the declarations.

If beta's declarations go missing from the running agent, the class path was read first-root-only — the
regression [`aimon-sample-app`](../aimon-sample-app) exists to catch.

## Building

Nothing to run here. The module is consumed by [`aimon-sample-app`](../aimon-sample-app) as a `runtimeOnly`
dependency and exercised by its packaging tests:

```bash
./gradlew :aimon-sample-app:packagingTest
```

It applies `aimon.java-conventions` even though it compiles nothing, because the root aggregators
(`format`, `checkFormat`, `checkStyle`, `checkAll`) call those task names on every subproject. It is
deliberately **not** `aimon.publishable` — samples are proof, not product.
