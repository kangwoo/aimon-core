# aimon-core Test Coverage Priority

Generated from `./gradlew :aimon-core:jacocoTestReport`
(report: `modules/aimon-core/build/reports/jacoco/test/html/index.html`).

## Headline numbers

| Counter | Covered | Total | % |
|---|---:|---:|---:|
| INSTRUCTION | 80,835 | 104,484 | **77.4%** |
| LINE | 17,477 | 22,529 | **77.6%** |
| BRANCH | 5,515 | 8,406 | 65.6% |
| METHOD | 4,534 | 5,800 | 78.2% |
| CLASS | 713 | 822 | 86.7% |

The 50% → 70% line-coverage goal in the architecture/QA plan is **already met**
post-refactor (77.6%). Branch coverage at 65.6% is the next natural target
(→ 70% would need ~370 more covered branches).

## Priority list — packages dragging coverage down

Packages are sorted by missed lines × business criticality. Anything ≥ 80% is
omitted; "in tier" rules are heuristics so the test author can pick one
self-contained chunk per PR.

### Tier 1 — large, untested, in critical paths

| Package | Covered | Total | % | Notes |
|---|---:|---:|---:|---|
| `at.aimon.core.mcp` | 1 | 316 | 0.3% | Top-level MCP client API. High value. |
| `at.aimon.core.mcp.transport` | 0 | 106 | 0.0% | Stdio/HTTP transports — needs fakes. |
| `at.aimon.core.scheduling.scheduler` | 0 | 86 | 0.0% | Quartz adapter glue. |
| `at.aimon.core.tools.task` | 42 | 183 | 23.0% | TaskTool / subagent dispatch. |
| `at.aimon.core.llm.streaming` | 50 | 174 | 28.7% | SSE / parts assembly. |
| `at.aimon.core.agent.impl.orca.tool` | 41 | 127 | 32.3% | Orca tool-provider plumbing. |
| `at.aimon.core.scheduling` | 244 | 584 | 41.8% | Engine + manager. Largest package on the list. |

### Tier 2 — small surface, easy wins

| Package | Covered | Total | % | Notes |
|---|---:|---:|---:|---|
| `at.aimon.core.agent.impl.orca.environment` | 0 | 6 | 0.0% | Trivial — env wiring. |
| `at.aimon.core.agent.session.exception` | 0 | 12 | 0.0% | Exception ctors only. |
| `at.aimon.core.mcp.exception` | 0 | 12 | 0.0% | Exception ctors. |
| `at.aimon.core.subagent.permission` | 0 | 15 | 0.0% | Permission DTOs / enums. |
| `at.aimon.core.hook.exception` | 0 | 22 | 0.0% | Exception ctors. |
| `at.aimon.core.mcp.orca` | 0 | 25 | 0.0% | OrcaMcpToolProvider. |
| `at.aimon.core.scheduling.repository` | 0 | 28 | 0.0% | In-memory store. |
| `at.aimon.core.skill.permission` | 0 | 30 | 0.0% | Skill permission DTOs. |
| `at.aimon.core.subagent.context` | 0 | 36 | 0.0% | Context wiring. |
| `at.aimon.core.skill.validation` | 0 | 41 | 0.0% | Static validators. |
| `at.aimon.core.scheduling.quota` | 0 | 43 | 0.0% | Per-agent quota. |
| `at.aimon.core.agent.impl.orca.command` | 2 | 69 | 2.9% | Slash-command dispatch. |
| `at.aimon.core.filesystem.config` | 9 | 62 | 14.5% | VFS root config builders. |

### Tier 3 — mid-coverage, fill gaps

`at.aimon.core.subagent` (60.2%), `at.aimon.core.agent.stream` (60.6%),
`at.aimon.core.skill.execution` (63.6%), `at.aimon.core.subagent.execution`
(65.0%), `at.aimon.core.agent.impl.orca` (70.7%). Existing tests cover the
happy paths — gaps are mostly error branches and edge cases.

## Recommended next PRs (one per slot)

1. **mcp.exception / hook.exception / agent.session.exception / mcp.exception
   ctors** — single test file exercising each constructor with cause /
   message overloads. ~20 mins, lifts 4 packages from 0% to 100%.
2. **scheduling.repository** — `InMemoryScheduledTaskRepository` round-trip
   test (save/load/delete/list/findByAgent). ~30 mins.
3. **scheduling.quota** — quota-counter behaviour at boundaries (under,
   at, over) for the in-memory implementation.
4. **mcp.transport** — start with `StdioMcpTransport` using a piped
   `Process` stand-in; HTTP transport with a `MockWebServer`.
5. **llm.streaming** — feed canned SSE chunks into the parts-aware reducer
   and assert the resulting `LlmResponse` parts.
6. **scheduling** core — `SchedulingEngineImpl` + `ScheduledTaskManagerImpl`
   with a fake `Scheduler`. Largest absolute gain available.

## Out of scope for this cycle

Per the plan, Stage 8 is "JaCoCo report + priority list" only. Implementation
of the test PRs above is tracked separately.
