# CLAUDE.md

AIMON is a ReAct (Reasoning and Acting) agent framework for IT operations automation.

## Build & Test Commands

```bash
./gradlew build                    # Build entire project
./gradlew :aimon-core:build        # Build specific module
./gradlew test                     # Run all unit tests (excludes @Tag("docker") integration tests)
./gradlew :aimon-core:test         # Run tests for specific module
./gradlew :aimon-core:test --tests "at.aimon.core.agent.tool.ToolInputTest"  # Single test class
./gradlew integrationTest          # Run Docker/Testcontainers integration tests (@Tag("docker")) — needs a Docker daemon
./gradlew format                   # Apply formatting (Spotless)
./gradlew checkFormat              # Check formatting only
./gradlew checkStyle               # Checkstyle (main source only, not tests)
./gradlew checkAll                 # Run all quality checks (format check + style + unit tests)
./gradlew :aimon-cli:run           # Run CLI application
```

## Build Setup

Cross-cutting build configuration (Java 17, Spotless, Checkstyle, JaCoCo, JUnit, common
dependencies, Maven Central publishing) is implemented as pre-compiled Gradle script
plugins under `buildSrc/src/main/kotlin/`:

- `aimon.java-conventions` — applied by every JVM module. Configures `java-library`,
  Spotless, Checkstyle, JaCoCo, JUnit Platform, common test dependencies, and shared
  compiler args.
- `aimon.publishable` — applied by modules published to Maven Central.

A new module opts in via:

```kotlin
plugins {
    id("aimon.java-conventions")
    id("aimon.publishable") // omit if the module is not published
}
```

The root `build.gradle.kts` only owns repository setup and the `format` / `checkAll`
aggregator tasks; module-side build files only declare module-specific dependencies.

## Code Conventions

- **Java 17** with pattern matching
- IMPORTANT: **Prefer `class` over `record`** — Use immutable classes with builder pattern (see `at.aimon.core.agent.AgentContent` for reference)
  - 예외는 하나뿐: **`GenericTool` 의 입력 DTO** (`at.aimon.core.agent.tool.generic`) 는 `record` 로 쓴다. 컴포넌트마다 붙는 `@ToolParam` 이 JSON Schema 생성과 `ToolInput` 바인딩의 **단일 출처**이기 때문이다. 도메인 타입·값 객체·설정 객체는 예외에 포함되지 않는다. 빌더 규칙과 충돌하지 않는 이유("역직렬화 대상은 빌더 면제", 선례 `Todo.java:44-46`)는 `.claude/rules/immutability-pattern.md` 에 있다
- **Import order**: java, javax, jakarta, org, com, (blank line), project imports
- **Formatting**: Eclipse Formatter (`config/eclipse/eclipse-formatter.xml`) via Spotless — run `./gradlew format` before committing
- Follow SOLID principles per @docs/project/solid-principles.md
- Use `at.aimon.core.base.Principal` for identity representation (user, group, system, service)

## Design Principles

- **Multi-instance ready**: 상태를 가진 컴포넌트는 저장소를 인터페이스로 분리하여 멀티 인스턴스(스케일아웃) 환경에서도 동작할 수 있도록 설계한다. 기본 구현은 in-memory로 제공하되, 저장소 교체가 리팩토링이 아니라 구현체 교체로 가능해야 한다.

## Module Structure

```
modules/
├── aimon-bom                        # java-platform BOM: every module's version, derived from the subproject list
├── aimon-core                       # Core framework: agent execution, tools, skills, hooks, scheduling
├── aimon-bootstrap                  # Framework-neutral assembly: AimonStack + ordered teardown (no Spring)
├── aimon-spring-boot-starter        # Spring Boot autoconfiguration over aimon-bootstrap
├── aimon-cli                        # Interactive REPL CLI application (assembly)
│
├── aimon-llm-openai                 # OpenAI LLM client
├── aimon-llm-anthropic              # Anthropic LLM client
│
├── aimon-filesystem-gridfs          # MongoDB GridFS virtual filesystem
├── aimon-filesystem-s3              # AWS S3 virtual filesystem
│
├── aimon-sandbox                    # Sandbox abstraction (interface)
├── aimon-sandbox-docker             # Docker sandbox implementation
├── aimon-sandbox-kubernetes         # Kubernetes sandbox implementation
│
├── aimon-session-mongodb            # MongoDB-backed session store
├── aimon-session-postgres           # PostgreSQL-backed session store
├── aimon-session-redis              # Redis-backed session store
├── aimon-session-routing            # Multi-node session routing (the SPIs it runs on live in aimon-core)
│
├── aimon-memory-testkit             # Shared five-tier PeerMemory contract suite (published)
│
├── aimon-knowledge-opensearch       # OpenSearch knowledge store
├── aimon-scheduling-quartz          # Quartz-based task scheduler (clustered/distributed)
├── aimon-workflow-graaljs           # GraalJS frontend: JS-scripted subagent workflow
├── aimon-rewake-webhook             # Javalin HTTP endpoint that fires rewake (HMAC-verified)
└── aimon-browser-playwright         # Playwright-based browser automation
```

NOTE: `aimon-session-routing` has been renamed twice — `aimon-session-web` → `aimon-session-base`
→ `aimon-session-routing`. The first rename only said what the module *was not* (not a web-only
adapter); the second says what it *is*. The session SPIs (`SessionInbox`, `SessionSignalBus`,
`IdempotencyStore`, `SessionRecordCodec`) that used to justify the "base" half now live in
`aimon-core` under `at.aimon.core.agent.session.*`, so the backend modules' **main sources** no
longer depend on this module — they keep it at `testImplementation` scope only, where their
multi-node tests drive a real `SessionRouter`. Only routing (`SessionRouter`, `LiveSessionCache`,
`LiveSessionOpener`) is left here. The Java package moved with it: `at.aimon.session.base` →
`at.aimon.session.routing`.

NOTE: 메모리는 이제 **코어 + 원격 서비스** 두 조각이다. `aimon-memory-mongodb` 와
`aimon-memory-postgres` 는 **제거되었고**(이전이 아니라 제거 — 데이터가 옮겨가지 않는다),
`aimon-memory-file` 은 `aimon-core` 의 `at.aimon.core.memory.file` 로 **병합**되었다. 남은 그림은
두 줄이다 — `aimon-core` 가 `PeerMemory` SPI 와 노드 로컬 기본 백엔드(in-memory + file)를 갖고,
분산 메모리는 별도 저장소의 [aimon-memory](https://github.com/kangwoo/aimon-memory) 서비스가
`RemotePeerMemory` 로 그 SPI 를 구현한다. `aimon-memory-testkit` 은 그 계약을 두 저장소가 공유하기
위해 **배포 대상이 되었다**. 근거는 @docs/design/memory/pluggable-memory-backend.md §4.2·§4.3.

## Package Conventions

Within `aimon-core`, top-level packages follow a domain + impl split:

- **`at.aimon.core.<domain>`** — interfaces, domain types, immutable value objects
  (e.g. `at.aimon.core.agent.Agent`, `at.aimon.core.filesystem.VirtualFileSystem`)
- **`at.aimon.core.<domain>.impl`** — concrete implementations (`Default*`, `Local*`, `Orca*` ...)
  Direct imports of `*.impl` from outside the `at.aimon.core.<domain>..` tree are blocked by
  ArchUnit. External modules and other core packages must depend on neutral SPI packages instead.
- **`at.aimon.core.agent.orca`** — public Orca tool-provider SPI surface
  (`OrcaToolProvider`, `OrcaToolProviderContext`, `OrcaProviderDependencies`). External modules
  (`aimon-sandbox`, `aimon-browser-playwright`, ...) and other core packages
  (`mcp.orca`, `tools.*`, ...) implement these interfaces — they must NOT import from
  `at.aimon.core.agent.impl..`.

Other rules:

- Each module places custom exceptions in an `exception/` sub-package.
- Tool implementations live under `at.aimon.core.tools.<category>` (formerly `ext.tools.*`).
- ArchUnit tests in `aimon-core/src/test/java/at/aimon/core/architecture/` enforce these
  rules at build time.

## Scope & Scheduling Lifecycle

IMPORTANT: 수명·소유권·소멸 책임의 전체 규칙은 @docs/overview/scope-model.md 에 있다. 새 타입을 만들거나
`close()` 를 호출하기 전에 그 문서를 기준으로 삼는다. 아래는 그중 자주 어기는 항목만 요약한 것이다.

컴포넌트 수명은 4단계다 — **Application** (`SchedulingEngine`, `ScheduledTaskManager`, `RoutineExecutor`,
`AgentRuntimeRegistry`, `SessionRecordStore`, `SessionLeaseStore`) / **Agent** (`AgentRuntime` 과 그것이 소유한
`ToolRegistry` / `HookRegistry` / `McpClientManager`, `AgentEnvironmentSnapshot`) / **Session**
(`SessionRecord`, `SessionTotals`, `budgetOverride`, `SessionTranscript`) / **Live session** (`LiveSession`,
메시지 큐, 이벤트 publisher). 여기에 실행 단위 Execution·Turn·Iteration 이 얹힌다.

IMPORTANT (turn ≠ iteration ≠ execution): **턴**은 세션에 들어온 사용자 입력 1건의 처리
(`executor.execute(runtime, request)` 1회), **iteration** 은 ReAct 루프 1회(LLM 호출 → 도구 실행 → 관찰),
**execution** 은 에이전트 작업 1회 일반으로 **세션이 없을 수도 있다**(서브에이전트 포크, 스킬 포크,
rewake 리플레이, 스케줄 루틴 — 정체성은 `SessionId` 가 아니라 `ExecutionId`). 턴은 실행의 한 종류이므로,
두 경로가 공유하는 것(취소 신호, `BudgetTracker`)을 설명할 때는 `turn` 이 아니라 `execution` 이라고 쓴다.
예외는 `assistant turn` / `user turn` 뿐이다 — 한정어가 붙어 LLM 메시지 role 어휘가 되기 때문이며,
맨 `turn` 은 언제나 첫 번째 뜻이다. 전체 규칙은 @docs/overview/glossary.md §4 › 실행 단위.

IMPORTANT (session ≠ live session): **1 SessionRecord : 0..N LiveSession** 의 비대칭 관계다. `SessionRecord` 는
`SessionId` 로 식별되는 영속 애그리게이트이고, `LiveSession` 은 그 세션에 대해 턴을 실행하는 노드 로컬
핸들이다. 따라서 **재시작·축출·노드 이동을 넘어 살아남아야 하는 값은 레코드 쪽에 둔다**. 새 타입 이름:
영속되어야 하면 `Session*`(`agent.session[.store|.transcript]`), 프로세스와 함께 사라져도 되면
`LiveSession*`(`agent.session`), 에이전트 단위로 한 번 모으면 되면 `Agent*`(`agent`).

IMPORTANT (맨 단어 `Session` / `AgentSession` 금지): 정확히 그 두 이름을 가진 타입은 만들 수 없다 — 두 수명이
서로를 사칭하게 만드는 이름이기 때문이다. `SessionNamingArchitectureTest` 가 빌드에서 막는다. 반면 "conversation"
은 여전히 유효한 단어이며, **LLM 과의 메시지 교환**을 뜻한다(`getConversationHistory()`, "Conversation compacted").
수명을 뜻하는 데 쓰지 말 것.

IMPORTANT (이름으로 수명을 추론하지 말 것): `*Manager`/`*Registry`/`*Factory`/`*Repository`/`*Store` 는 X 를
관리하는 컨테이너이고 자기 자신의 수명은 X 의 수명이 아니다 — `SessionRecordStore` 와 `AgentRuntimeRegistry`
는 application-scoped 다. `Session` 이라는 단어도 여러 수명을 가리킨다(`ReplSession`, `BrowserSession`,
`LiveSessionCache` 항목). 이름이 아니라 **키와 저장 위치**로 판단할 것 (`Map<AgentRuntimeId, _>` 면 agent-scoped).

IMPORTANT (Scheduling): `SchedulingEngine`/`ScheduledTaskManager`/`RoutineExecutor` 는 application-scoped 이며
`AgentRuntime` 소멸과 무관하게 유지된다. runtime 소멸 시 scheduling 컴포넌트를 close 하면 안 된다.
`AgentRuntimeRegistry` 는 `SchedulingEngine` 외부에서 생성되어 빌더로 주입된다 — `SchedulingEngine` 이 소유하지
않는다. `ScheduledTask.boundRuntimeId` 는 agent-scoped id 를 참조하므로 cron 재발화 시점에도 resolve 된다.

IMPORTANT (Agent Scope): `AgentRuntime` 은 **agent-scoped** 다. `AgentRuntimeId` 는 `agent:<name>` 또는
`agent:<name>:<discriminator>` 형식으로 결정론적이며 `from(Agent)` / `from(Agent, String)` 으로 발급한다
(`generate()` 는 존재하지 않음). `LiveSession.close()` 가 `AgentRuntime.close()` 를 호출하면 안 된다 — 동일
agent 의 다른 세션이 아직 그 runtime 을 사용 중일 수 있다. runtime 등록은 부트스트랩(CLI `AgentSetupFactory`,
web `LiveSessionOpener`)에서 1회 수행하고, 종료는 앱 shutdown 또는 명시적 agent 제거 시에만.

이름의 유래: 이 타입은 `AgentExecutionContext` 였고 `AgentRuntime` 으로 개명되었다 — "context" 가 실행마다 새로
생기는 값처럼 읽혔지만 실제로는 agent 당 하나 살아 있는 장수명 런타임이기 때문이다. 옛 이름으로 검색하면
@docs/migration/rename-maps.md 의 매핑 표를 볼 것.

## Tool Development

IMPORTANT: Tools must follow these rules. See @docs/features/tool/tool-development-guide.md for details.

1. **Never throw exceptions from `execute()`** — Always return `ToolResult.error()`
2. **Use type-safe accessors** — `input.getRequiredString()`, `input.getInteger("key", defaultValue)`
3. **Stateless design** — No state between executions
4. **Immutable I/O** — ToolInput, ToolResult, ToolContext are all immutable

## Documentation & Translation

문서는 두 언어로 제공되며, **어느 쪽이 정본인지는 파일 위치가 정한다.**

| 위치 | 정본 | 번역본 |
|------|------|--------|
| `docs/**` | 한국어 (`*.md`) | 영어 (`*.en.md`) |
| 저장소 루트 (`README`, `CONTRIBUTING`, `SECURITY`, `CODE_OF_CONDUCT`) | 영어 (`*.md`) | 한국어 (`*.ko.md`) |

방향은 반대지만 규칙은 하나다 — **언어 접미사가 붙은 파일이 번역본이다.** 번역본은 앞머리에
`translated_from` (정본 경로) 과 `source_commit` (옮겨 온 시점의 정본 커밋) 을 적는다. 정본에는
절대 붙이지 않는다.

IMPORTANT: **문서를 고칠 때는 번역본이 있는지 먼저 확인한다.** 정본만 고치고 번역본을 그대로 두면
두 문서가 조용히 어긋난다. 같이 고칠 수 있으면 같은 커밋에서 고치고, 못 고치면 그 사실을 말한다 —
번역 때문에 정본 수정을 미루지는 않는다(더 나쁜 실패 모드다). `python3 scripts/check-translation-staleness.py`
가 뒤처진 번역을 보고한다. `source_commit` 은 **이번 수정 직전**의 정본 커밋이며(자기 커밋 SHA 는
미리 알 수 없다), 체커는 정본과 번역본을 함께 건드린 커밋을 건너뛰므로 이 한 커밋의 지연은 낡음이
아니다.

번역 규칙 전문은 @docs/project/translation-glossary.md 와 @docs/README.md 에 있다. 자주 어기는 것만:

- **구조를 정확히 맞춘다** — 제목 개수, 표의 행 수, 코드 블록 개수가 같아야 한다
- **제목을 번역하면 앵커가 바뀐다** — 문서 안 `#링크` 를 다시 겨누고 `python3 scripts/check-doc-links.py` 로 검증
- **식별자는 번역하지 않는다** — 타입·패키지 이름, 파일 경로, 설정 키, CLI 플래그, 애노테이션,
  enum 상수, 동결된 와이어 이름(`conversationId`, `conversation_locks`). 코드 블록 **안의 주석**은 번역한다
- **ASCII 다이어그램은 고치지 말고 다시 그린다** — 한글은 두 칸 폭이라 상자 가운데에 끼워 넣으면 정렬이 깨진다
- **정본이 낡았어도 번역본에서 고치지 않는다** — 정본을 별도 커밋으로 고친다
