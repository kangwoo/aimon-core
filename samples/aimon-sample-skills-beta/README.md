# aimon-sample-skills-beta

The second resources-only module. **Its whole reason for existing is to be the second jar.**

A framework regression once meant that only the first class-path root carrying
`agents/<name>/skills/index` was read, so everything shipped by a second module vanished with no
diagnostic. One sample module cannot show that bug or its absence; two can — and only if both declare the
**same** resource path, which [`aimon-sample-skills-alpha`](../aimon-sample-skills-alpha) and this module
deliberately do.

## What it ships

```
src/main/resources/agents/
├── sample/
│   ├── skills/
│   │   ├── index                  # declares: beta-notes
│   │   └── beta-notes/SKILL.md
│   └── agents/
│       ├── index                  # declares: beta-explorer
│       └── beta-explorer.md       # a bundled subagent
└── noindex/
    ├── agent.md                   # a second agent bundle
    └── skills/orphan/SKILL.md     # a skill with no index beside it
```

Two of these pieces are here rather than in alpha, on purpose:

- **A bundled subagent.** Subagents are the half of a bundle that has **no materializer**, and therefore
  the half where exploded and packaged layouts can still disagree. Alpha ships no subagent, so this is the
  only place that comparison can be made.
- **A second agent bundle with no index.** `noindex` ships a skill directory and no `index` file beside it.
  A framework that loaded zero skills in silence here would look identical to one that worked. The
  packaging tier asserts that it says so out loud instead — and the sample app keeps `logging.level.at.aimon`
  at `INFO` so that warning is one an operator would actually see, not one raised specially for a test.

## Building

Nothing to run here. The module is consumed by [`aimon-sample-app`](../aimon-sample-app) as a `runtimeOnly`
dependency and exercised by its packaging tests:

```bash
./gradlew :aimon-sample-app:packagingTest
```

It applies `aimon.java-conventions` even though it compiles nothing, because the root aggregators
(`format`, `checkFormat`, `checkStyle`, `checkAll`) call those task names on every subproject. It is
deliberately **not** `aimon.publishable` — samples are proof, not product.
