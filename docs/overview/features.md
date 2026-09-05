# 기능 카탈로그 (Feature Catalog)

`aimon-core` 가 **무엇을 할 수 있는지**를 사용자 관점에서 한 곳에 모은 문서다.

- 아키텍처가 궁금하면 → [`architecture.md`](architecture.md)
- 용어의 뜻과 수명이 궁금하면 → [`glossary.md`](glossary.md) / [`scope-model.md`](scope-model.md)
- **"이런 걸 하고 싶은데 AIMON에 있나?"** 가 궁금하면 → 이 문서

## 읽는 법

각 기능은 아래 세 가지로 기술된다.

| 열 | 뜻 |
|-----|-----|
| **기능** | 사용자가 얻는 것 |
| **진입점** | 그 기능을 쓰기 위해 처음 잡아야 하는 타입 또는 도구 이름 |
| **위치** | `core` = `aimon-core` 에 내장 / 모듈명 = 별도 의존성 추가 필요 |

`core` 로 표시된 것은 `at.aimon.core:aimon-core` 하나만 넣으면 동작한다. 모듈명이 적힌 것은 해당
아티팩트를 추가로 의존해야 한다 — 코어는 인터페이스만 갖고 구현이 밖에 있는 구조다
([SOLID › DIP](../project/solid-principles.md)).

---

## 0. 한눈에 보기

| # | 영역 | 한 줄 요약 |
|---|------|-----------|
| [1](#1-에이전트-실행) | **에이전트 실행** | ReAct 루프를 돌려 사용자 입력 1건을 처리한다 |
| [2](#2-세션) | **세션** | 대화를 영속하고, 재시작·멀티 노드를 넘어 이어간다 |
| [3](#3-도구-tool) | **도구** | 에이전트가 외부와 상호작용하는 단위 |
| [4](#4-스킬-skill) | **스킬** | 프롬프트·도구·훅을 묶은 선언적 능력 패키지 |
| [5](#5-훅-hook) | **훅** | 실행 라이프사이클 13개 지점에 개입 |
| [6](#6-서브에이전트-subagent) | **서브에이전트** | 격리된 컨텍스트에서 하위 작업을 위임 |
| [7](#7-워크플로-workflow) | **워크플로** | 여러 서브에이전트를 결정론적으로 오케스트레이션 |
| [8](#8-명령-command) | **명령** | `/compact`, `/status` 같은 사용자 슬래시 명령 |
| [9](#9-llm-연동) | **LLM 연동** | 프로바이더 추상화, 스트리밍, 재시도, 비용 계측 |
| [10](#10-메모리-memory) | **메모리** | 관찰을 축적해 장기 기억으로 승격 |
| [11](#11-지식과-위키-knowledge--wiki) | **지식 / 위키** | RAG 검색과 LLM 위키 |
| [12](#12-mcp-연동) | **MCP** | 외부 MCP 서버의 도구를 에이전트에 붙임 |
| [13](#13-스케줄링) | **스케줄링** | cron/일회성 예약 실행, 루틴 |
| [14](#14-파일시스템과-셸) | **파일시스템 / 셸** | 가상 FS와 가상 셸 추상화 |
| [15](#15-샌드박스와-브라우저) | **샌드박스 / 브라우저** | 격리 실행 환경, 웹 자동화 |
| [16](#16-관측-observability) | **관측** | 트레이싱, 상태 리포트, 사용량 |
| [17](#17-권한과-자격증명) | **권한 / 자격증명** | 도구 허용 규칙, 승인 스코프, 시크릿 |

---

## 1. 에이전트 실행

에이전트가 "LLM 호출 → 도구 실행 → 관찰"을 반복해 사용자 입력 1건을 끝까지 처리하는 부분이다.
이 반복 1회가 **iteration**, 입력 1건 처리 전체가 **turn** 이다
([용어 규칙](glossary.md)).

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 에이전트 정의 (이름·시스템 프롬프트·모델·max iterations) | `Agent` (builder) | core |
| Markdown + YAML frontmatter 로 에이전트 정의 | `agent.definition` / `agent.parser` | core |
| ReAct 실행 루프 | `AgentExecutor` → `OrcaAgentExecutor` | core |
| 에이전트 실행 환경 (도구·훅·MCP 레지스트리 묶음) | `AgentRuntime`, `AgentRuntimeId` | core |
| 실행 예산 (iteration / 토큰 / 시간 상한) | `ExecutionBudget`, `BudgetTracker` | core |
| 컨텍스트 윈도우 자동 압축 | `CompactionEngine`, `TimeBasedMicrocompact` | core |
| 실행 중 컨텍스트 블록 갱신 (작업 디렉토리·시간 등) | `ContextAssembler`, `ContextProvider` | core |
| 턴 도중 사용자 추가 입력 큐잉 | `agent.queue`, `SubmitOptions` | core |
| 협조적 중단 (Ctrl+C, NOW priority) | `agent.interrupt`, `InterruptBehavior` | core |
| 실행 이벤트 스트리밍 | `AgentExecutionEvent` (sealed) | core |
| 실행 전후 인터셉터 체인 | `AgentExecutionInterceptor`, `InterceptingAgentExecutor` | core |
| 멀티모달 입력 (텍스트/이미지/오디오/파일) | `agent.input` | core |
| 실행 산출물 수집 | `ArtifactCollector`, `FileArtifact` | core |
| 시스템 프롬프트 조립 · `<system-reminder>` 합성 | `SystemPromptPart`, `SystemReminderFormatter` | core |

**언제 쓰나.** 애플리케이션에 에이전트를 임베딩할 때 반드시 지나는 길이다. 대부분은
`Agent` 를 만들고 `AgentRuntime` 을 부트스트랩에서 1회 등록한 뒤
[세션](#2-세션)을 통해 턴을 넣는다.

**주의.** `AgentRuntime` 은 **agent-scoped** 다 — 세션마다 만들지 않는다. 수명 규칙은
[`scope-model.md`](scope-model.md) 를 기준으로 삼는다.

**관련 문서**
- [임베딩 가이드](../getting-started/embedding-agent-in-application.md) — Spring Boot / SDK 통합
- [CLI 레퍼런스 통합 예시](../getting-started/aimon-core-integration-via-cli-reference.md)
- [명령 큐 가이드](../features/agent-execution/command-queue-guide.md)
- [중단 가능 도구 가이드](../features/agent-execution/interruptible-tools-guide.md)
- [system-reminder 규약](../features/agent-execution/system-reminder-convention.md)

---

## 2. 세션

대화를 영속하고, 프로세스 재시작·노드 이동을 넘어 이어가는 부분이다.

IMPORTANT: **세션(`SessionRecord`)과 라이브 세션(`LiveSession`)은 다르다.** 관계는
`1 SessionRecord : 0..N LiveSession` 으로 비대칭이며, 살아남아야 하는 값은 레코드 쪽에 둔다.
자세한 규칙은 [`scope-model.md` §3](scope-model.md) 참조.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 턴 제출 / 이벤트 구독 파사드 | `LiveSession` (`submit`, `submitAsync`, `offerAsync`, `events()`) | core |
| 세션 영속 레코드 (전사 + 누적치 + 예산 오버라이드) | `SessionRecord`, `SessionRecordStore` | core (in-memory 기본) |
| 메시지 이력 | `SessionTranscript`, `SessionSnapshot`, `TranscriptManager` | core |
| 누적 통계 (턴 수 / iteration / 토큰) | `SessionTotals` | core |
| 노드 간 세션 소유권 선출 + 펜싱 | `SessionLeaseStore`, `SessionStore.claim` | core |
| 멀티 노드 라우팅 / 핸들 캐시 | `SessionRouter`, `LiveSessionCache`, `LiveSessionOpener` | `aimon-session-routing` |
| MongoDB 백엔드 | — | `aimon-session-mongodb` |
| PostgreSQL 백엔드 | — | `aimon-session-postgres` |
| Redis 백엔드 | — | `aimon-session-redis` |
| 턴 주소 지정 (인터럽트 타겟팅 / 이벤트 태깅) | `TurnId` | core |
| 제출 결과 판별 (즉시 실행 vs 큐잉) | `SubmitOutcome` | core |

**언제 쓰나.** 대화형 UI(REPL, 웹 채팅)를 만들거나, 같은 대화를 여러 요청에 걸쳐 이어가야 할 때.
단발성 배치 실행이라면 세션 없이 [서브에이전트 포크](#6-서브에이전트-subagent)나
[스케줄 루틴](#13-스케줄링)으로도 충분하다.

**관련 문서**
- [세션 튜토리얼](../features/session/agent-session-tutorial.md) — 처음 붙일 때
- [`LiveSession` API 가이드](../features/session/agent-session-guide.md) — 이벤트 스트리밍 포함
- [멀티 노드 배포 가이드](../features/session/web-session-deployment-guide.md)

---

## 3. 도구 (Tool)

에이전트가 외부와 상호작용하는 단위. `execute()` 는 **예외를 던지지 않고** 항상 `ToolResult` 를
반환한다는 것이 계약이다.

### 3.1 프레임워크

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 도구 인터페이스 / 기본 구현 | `Tool`, `AbstractTool` | core |
| 타입 안전 입출력 | `ToolInput`, `ToolResult`, `ToolContext` (모두 불변) | core |
| 도구 등록·조회 | `ToolRegistry` | core |
| 선언적 입력 바인딩 (record + `@ToolParam` → 스키마) | `GenericTool`, `ToolSchemaGenerator`, `ToolInputBinder` | core |
| 입력 스키마 검증 (`execute()` 이전) | `agent.tool.schema`, `SchemaValidationMode` | core (기본 `WARN`) |
| 병렬 실행 (동일 배치 내 안전 도구) | `ConcurrencyBehavior`, `ParallelToolDispatcher`, `ToolConcurrencyConfig` | core (기본 off) |
| 지연 로딩 + 검색으로 스키마 노출 | `ToolSearchRegistry`, `ToolSearchStrategy`, `ToolLoadingMode` | core |
| 도구 권한 검사 | `agent.tool.permission`, `ToolPermissionSubjectAware` | core |
| 중단 동작 선언 | `InterruptBehavior`, `InterruptAccess` | core |
| 단발 도구 직접 호출 | `SingleToolInvoker` | core |

### 3.2 내장 도구

| 분류 | 도구 이름 | 위치 |
|------|----------|------|
| 파일 | `Read`, `Write`, `Edit`, `Grep` | core |
| 셸 | `Bash`, `BashOutput` (백그라운드 포함) | core |
| 웹 | `WebSearch`, `WebFetch` | core |
| 할 일 | `TodoWrite` | core |
| 작업 위임 | `Task`, `TaskList`, `TaskStop`, `AgentOutput` | core |
| 스킬 | `Skill` | core |
| 스케줄링 | `schedule_task`, `list_scheduled_tasks`, `cancel_scheduled_task` | core |
| 지식 | `KnowledgeSearch` | core |
| 위키 | `WikiIngest`, `WikiSearch`, `WikiLint`, `WikiStatus` | core |
| 메모리 | `Observe`, `MemoryRecall`, `MemorySearch`, `MemoryChat` | core |
| 도구 검색 | `ToolSearch` | core |
| 콘솔 | `ConsoleOutput` | core |
| 워크플로 | `Workflow` | core |
| 워크플로 (JS 스크립트) | `WorkflowJs` | `aimon-workflow-graaljs` |
| 브라우저 | `Browser` | `aimon-browser-playwright` |
| 샌드박스 | `RunSandbox`, `CopyToSandbox`, `RestartSandbox`, `DeleteSandbox` | `aimon-sandbox-*` |

**관련 문서**
- [도구 개발 가이드](../features/tool/tool-development-guide.md) — 새 도구를 만들 때의 정본
- [도구 병렬 실행 가이드](../features/tool/parallel-tool-execution-guide.md)
- [브라우저 도구 가이드](../features/tool/browser-tool-guide.md)

---

## 4. 스킬 (Skill)

프롬프트·도구·훅을 하나로 묶은 **선언적 능력 패키지**. [Agent Skills 표준](../references/agentskills-specification.md)
포맷을 따르고 AIMON 확장 frontmatter를 더한다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 스킬 정의 / 메타데이터 | `Skill`, `SkillMetadata`, `SkillContent` | core |
| 등록·조회 | `SkillRegistry` | core |
| 실행 모드 (인라인 / 포크) | `ExecutionMode`, `SubagentBackedSkillForkExecutor` | core |
| 호출 정책과 승인 | `InvokePolicy`, `skill.policy.*` | core |
| 승인 스코프 — 이번 턴 / 이 세션 / 이 에이전트 | `PendingApprovalStore`, `SessionApprovalStore`, `AgentApprovalStore` | core |
| 스킬 저장소 (클래스패스 / 경로 / VFS) | `ClasspathSkillRepository`, `PathSkillRepository`, `VfsSkillRepository` | core |
| 번들 스킬 물리화 | `BundledSkillMaterializer` | core |
| 스킬이 선언한 훅 | `skill.hook.declarative`, `skill.hook.predicate` | core |
| 템플릿 렌더링 (`${AIMON_*}` 변수) | `skill.render` | core |
| 검증 | `skill.validation` | core |

**언제 쓰나.** 재사용 가능한 절차(배포 순서, 리뷰 체크리스트, 리포지토리 고유 워크플로)를 코드가 아니라
Markdown으로 패키징하고 싶을 때. 승인 정책까지 선언으로 붙일 수 있다.

**승인 스코프의 도달 범위**는 좁은 것부터 넓은 것 순으로 pending → session → agent 이며,
정책 체인도 그 순서로 조회한다. 되돌리기는 `/revoke` (에이전트 전역까지 지우려면 `--agent`).
자세한 표는 [`glossary.md` §3](glossary.md).

**관련 문서**
- [빌트인 Agent/Skill 가이드](../features/skill/builtin-agent-skill-guide.md)
- [Agent Skills 표준 명세](../references/agentskills-specification.md)
- [AIMON 스킬 확장 필드](../references/aimon-skill-extensions.md)
- [CustomCommand → Skill 마이그레이션](../migration/custom-command-to-skill.md)

---

## 5. 훅 (Hook)

실행 라이프사이클에 개입하는 확장점. **13개 이벤트 타입**이 있다.

| 이벤트 | 발화 시점 |
|--------|----------|
| `onStart` / `onStop` | 실행 시작 / 종료 |
| `preTool` / `postTool` | 도구 실행 직전 / 직후 |
| `preCompact` / `postCompact` | 컨텍스트 압축 직전 / 직후 |
| `permissionRequest` / `permissionDenied` | 권한 요청 시 / 거부 시 |
| `subagentStart` / `subagentStop` | 서브에이전트 실행 시작 / 종료 |
| `onSessionStart` / `onSessionEnd` | **라이브 세션** 열기 / 닫기 (아래 주의) |
| `onConfigReload` | 훅 설정 핫리로드 시 |

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 훅 등록·실행 | `HookRegistry`, `HookExecutionManager`, `HookEventType` | core |
| 훅 피드백 (실행 차단·컨텍스트 주입) | `HookFeedback` | core |
| 설정 파일 기반 훅 + 핫리로드 | `HookConfigLoader`, `HookConfigWatcher`, `HookRegistryReloader` | core |
| 계층 설정 병합 (전역 / 프로젝트 / 로컬) | `LayeredHookConfig`, `HookConfigMerger` | core |
| 비동기 rewake (외부 이벤트로 에이전트 깨우기) | `RewakeSpec`, `RewakeEnvelope`, `hook.rewake` | core |
| Webhook 수신 rewake | — | `aimon-rewake-webhook` |

IMPORTANT (알려진 오칭): `OnSessionStartHook` / `OnSessionEndHook` 은 세션(레코드)이 아니라
**`LiveSession` 의 열기/닫기**에 발화한다. 같은 세션이 재개되면 다시 발화한다.

**관련 문서**
- [훅 개발 가이드](../features/hook/hook-development-guide.md)
- [훅 설정 가이드](../features/hook/hook-config-guide.md) — 파일 기반 설정과 핫리로드
- [훅 스펙 parity 경계](../references/hooks-specification.md) — Claude Code 포맷 호환의 한계

---

## 6. 서브에이전트 (Subagent)

부모 실행 안에서 **격리된 컨텍스트**로 도는 하위 에이전트. 부모의 전사를 오염시키지 않고
탐색·검증 같은 작업을 위임할 때 쓴다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 서브에이전트 정의 (코드) | `Subagent.builder()` | core |
| 서브에이전트 정의 (Markdown) | `subagent.parser`, `subagent.repository` | core |
| 등록·조회 | `SubagentRegistry` | core |
| 실행 관리 | `SubagentExecutionManager`, `DefaultSubagentExecutor` | core |
| 백그라운드 실행 / 제어 | `SubagentBackgroundConfig`, `SubagentTaskController`, `BackgroundTaskStore` | core |
| 백그라운드 결과 영속화 | `TaskResultStore` (`InMemory*` / `Vfs*`), `TaskResult`, `TaskResultCodec` | core |
| 호출 도구 | `Task`, `TaskList`, `TaskStop`, `AgentOutput` | core |

**주의.** 서브에이전트 포크는 **자기 `SessionId` 가 없다** — 세션의 턴이 아니기 때문이다.
정체성은 `ExecutionId` 이고, 자기를 띄운 세션의 id 는 `invokingSessionId` 로 따로 들고 다닌다
(승인 정책의 도달 범위를 잇기 위해). 예산도 별도 `BudgetTracker` 로 관리된다.

**관련 문서**
- [서브에이전트 개발 가이드](../features/subagent/subagent-development-guide.md)

---

## 7. 워크플로 (Workflow)

여러 서브에이전트를 **결정론적 제어 흐름**(루프·조건·팬아웃)으로 오케스트레이션한다.
"에이전트가 알아서 결정"이 아니라 "스크립트가 결정"하는 것이 서브에이전트 단독 호출과의 차이다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 워크플로 실행기 | `WorkflowRunner`, `WorkflowRunners` | core |
| 파이프라인 / 스테이지 조립 | `Pipeline`, `Stage`, `AgentTask` | core |
| 판정 패턴 (judge panel 등) | `JudgedResult`, `Verdict`, `WorkflowPatterns` | core |
| 실행 핸들 · 재개 | `RunHandle`, `RunId`, `RunStore`, `StepResultCache` | core |
| 예산 / 동시성 제한 | `WorkflowBudget`, `WorkflowConcurrencyConfig` | core |
| git worktree 격리 실행 | `WorktreeEnvironmentFactory` | core |
| JS 스크립트 프론트엔드 | `WorkflowJs` 도구 | `aimon-workflow-graaljs` |

**언제 쓰나.** 커버리지를 위해 병렬로 훑어야 하거나(감사·마이그레이션), 독립적 관점의 교차 검증이
필요하거나(리뷰·설계 비교), 한 컨텍스트에 담기지 않는 규모일 때.

**관련 문서**
- [워크플로 CLI 가이드](../features/workflow/workflow-cli-guide.md) — `Workflow`/`WorkflowJs` 도구와 `/runs`
- [워크플로 사용 가이드](../features/workflow/workflow-usage-guide.md) — 코드로 조립·실행·재개

---

## 8. 명령 (Command)

사용자가 입력하는 슬래시 명령. 코어가 제공하는 시스템 명령과, 스킬을 명령으로 노출하는 경로가 있다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 명령 정의·등록 | `Command`, `CommandRegistry`, `CommandType` | core |
| 시스템 명령 기반 클래스 | `SystemCommand` | core |
| 스킬을 명령으로 노출 | `SkillBackedCommand` | core |

**내장 시스템 명령**

| 명령 | 하는 일 |
|------|--------|
| `/help`, `/commands`, `/version` | 도움말 · 명령 목록 · 버전 |
| `/status` | 시스템 상태 리포트 |
| `/agents`, `/skills` | 등록된 에이전트 / 스킬 목록 |
| `/clear` | 전사 초기화 |
| `/compact` | 컨텍스트 수동 압축 |
| `/pending`, `/approve`, `/deny` | 승인 대기 턴 조회 · 승인 · 거부 |
| `/revoke` | 승인 철회 (`--agent` 로 에이전트 전역까지) |
| `/rewakes` | 등록된 rewake 목록 |

---

## 9. LLM 연동

프로바이더 교체 가능성이 목적이다. 코어는 `LlmClient` 인터페이스만 갖는다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 프로바이더 추상화 | `LlmClient`, `LlmModel`, `LlmResponse` | core |
| 메시지 / 콘텐츠 블록 | `Message`, `Role`, `TextContentBlock`, `ImageContentBlock`, `DocumentContentBlock` | core |
| 도구 호출 프로토콜 | `ToolDefinition`, `ToolUse`, `ToolUseResult`, `StopReason` | core |
| 스트리밍 | `llm.streaming` | core |
| 재시도 · 백오프 | `llm.retry` | core |
| 호출 게이트웨이 / 프롬프트 초과 처리 | `LlmCallGateway`, `PromptTooLongHandler` | core |
| 취소 | `LlmCancellation` | core |
| 토큰 계측 | `TokenUsage`, `llm.token`, `llm.usage` | core |
| 비용 계산 | `Money`, `ModelPriceTable`, `CostSummary` | core |
| 컨텍스트 윈도우 레지스트리 | `ModelContextWindowRegistry` | core |
| 호출 메타데이터 태깅 | `LlmCallMetadata`, `BoundMetadataLlmClient` | core |
| OpenAI 구현 | — | `aimon-llm-openai` |
| Anthropic 구현 | — | `aimon-llm-anthropic` |

**관련 문서**
- [LLM Provider 개발 가이드](../features/llm/llm-provider-development-guide.md)
- [LLM 사용량·비용 미터링](../features/llm/llm-usage-metering.md)

---

## 10. 메모리 (Memory)

관찰(observation)을 축적하고 파생·정합화해 장기 기억으로 승격시키는 구조. 세션 전사와 달리
**세션을 가로질러** 남는다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 관찰 기록 | `Observation`, `ObservationStore`, `Observe` 도구 | core |
| 표상(파생 기억) | `Representation`, `RepresentationStore`, `memory.deriver` | core |
| 작업 공간 / 피어 뷰 | `Workspace`, `WorkspaceStore`, `PeerView`, `WorkspaceAccessPolicy` | core |
| 프롬프트 주입 | `MemoryContextProvider`, `MemoryInjectionMode` | core |
| 정합화 · 변증 · 드리머 | `memory.reconciler`, `memory.dialectic`, `memory.dreamer` | core |
| 색인 / 레닥션 | `memory.index`, `memory.redaction` | core |
| 조회 도구 | `MemoryRecall`, `MemorySearch`, `MemoryChat` | core |
| 파일 백엔드 | `memory.file` | core |
| 원격 백엔드 (멀티 인스턴스) | `PeerMemory` 구현 | 별도 저장소 — [aimon-memory](https://github.com/kangwoo/aimon-memory) |
| 백엔드 계약 스위트 | `AbstractPeerMemoryContractTest` | `aimon-memory-testkit` |

**관련 문서**
- [메모리 사용 가이드](../features/memory/memory-usage-guide.md)

---

## 11. 지식과 위키 (Knowledge / Wiki)

메모리가 "에이전트가 겪은 것"이라면, 지식은 "외부에서 넣어 준 것"이다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 지식 저장소 추상화 | `KnowledgeStore`, `SearchQuery`, `SearchResult`, `KnowledgeScope` | core |
| 키워드 기반 기본 구현 | `KeywordKnowledgeStore` | core |
| 문서 청킹 / 색인 옵션 | `DocumentChunker`, `IndexOptions` | core |
| 임베딩 클라이언트 추상화 | `EmbeddingClient` | core |
| LLM 위키 (수집·합성·린트·병합) | `WikiKnowledgeBase`, `WikiKnowledgeStore` | core |
| 위키 도구 | `WikiIngest`, `WikiSearch`, `WikiLint`, `WikiStatus` | core |
| 검색 도구 | `KnowledgeSearch` | core |
| OpenSearch 백엔드 (RAG) | — | `aimon-knowledge-opensearch` |

**관련 문서**
- [OpenSearch Knowledge Store 가이드](../features/knowledge/opensearch-knowledge-store-guide.md)
- [LLM Wiki 패턴](../references/llm-wiki.md) — `WikiKnowledgeStore` 의 컨셉 출처

---

## 12. MCP 연동

외부 [MCP](https://modelcontextprotocol.io) 서버가 제공하는 도구를 에이전트의 도구 목록에 합류시킨다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 클라이언트 / 수명 관리 | `McpClient`, `McpClientManager`, `McpClientFactory` | core |
| 서버 설정 | `McpServerConfig` | core |
| MCP 도구 어댑터 | `McpTool`, `McpToolSchema` | core |
| 전송 계층 | `mcp.transport` | core |

**주의.** `McpClientManager` 는 `AgentRuntime` 이 소유하며 `OrcaAgentRuntime.close()` 가 닫는다 —
직접 닫지 않는다.

---

## 13. 스케줄링

cron 또는 일회성 예약으로 에이전트 작업을 발화시킨다. 스케줄링 컴포넌트는 **application-scoped** 이며
`AgentRuntime` 소멸과 무관하게 살아 있다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 스케줄링 엔진 | `SchedulingEngine`, `SchedulingEngineBuilder` | core |
| 예약 작업 | `ScheduledTask`, `ScheduledTaskManager` | core |
| 루틴 (다단계 예약 실행) | `RoutineExecutor`, `RoutineStep` | core |
| 실행 가드 / 쿼터 | `ScheduledExecutionGuard`, `scheduling.quota` | core |
| 저장소 추상화 | `scheduling.repository` | core (in-memory 기본) |
| 도구 | `schedule_task`, `list_scheduled_tasks`, `cancel_scheduled_task` | core |
| Quartz 클러스터 백엔드 | — | `aimon-scheduling-quartz` |

**주의.** `ScheduledTask.boundRuntimeId` 는 **agent-scoped** id 를 참조하므로 원래 세션이 끝난 뒤
cron 이 재발화해도 런타임이 resolve 된다. 이 때문에 `AgentRuntimeId` 를 실행마다 새로 만들면 안 된다.

**관련 문서**
- [Quartz 스케줄링 배포 가이드](../features/scheduling/quartz-scheduling-web-deployment-guide.md)

---

## 14. 파일시스템과 셸

에이전트가 보는 파일 세계와 셸을 추상화해, 로컬 디스크 대신 원격 스토리지나 격리 환경으로
바꿔 끼울 수 있게 한다.

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 가상 파일시스템 | `VirtualFileSystem`, `FileMetadata`, `PathValidator` | core |
| 로컬 구현 / 스코프 제한 래퍼 | `filesystem.impl.local`, `ScopedVirtualFileSystem` | core |
| 사용량·백엔드 상태 | `FileSystemUsage`, `BackendStatus`, `BackendType` | core |
| 가상 셸 | `VirtualShell`, `ShellCommand`, `ShellCommandResult`, `ExecutionOptions` | core |
| MongoDB GridFS 백엔드 | — | `aimon-filesystem-gridfs` |
| AWS S3 백엔드 | — | `aimon-filesystem-s3` |

---

## 15. 샌드박스와 브라우저

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 샌드박스 추상화 | 샌드박스 SPI | `aimon-sandbox` |
| Docker 구현 | — | `aimon-sandbox-docker` |
| Kubernetes 구현 | — | `aimon-sandbox-kubernetes` |
| 샌드박스 도구 | `RunSandbox`, `CopyToSandbox`, `RestartSandbox`, `DeleteSandbox` | `aimon-sandbox-*` |
| 브라우저 자동화 | `Browser` 도구, `BrowserSession` | `aimon-browser-playwright` |

**관련 문서**
- [브라우저 도구 가이드](../features/tool/browser-tool-guide.md)

---

## 16. 관측 (Observability)

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 실행 트레이싱 | `Tracer`, `TraceSpan`, `SpanContext`, `SpanType` | core |
| 스팬 내보내기 / 저장 | `SpanExporter`, `TraceSpanStore` | core |
| 민감정보 레닥션 | `SpanRedactor`, `KeyPatternSpanRedactor`, `TracePayloadPolicy` | core |
| LLM 호출 자동 계측 | `TracingLlmClient` | core |
| 시스템 상태 리포트 | `SystemStatus`, `SystemStatusProvider`, `StatusSection` | core |
| 실행 이벤트 스트림 | `AgentExecutionEvent` | core |
| 토큰·비용 집계 | `SessionTotals`, `CostSummary` | core |

**관련 문서**
- [실행 트레이싱 가이드](../features/observability/execution-tracing-guide.md)
- [LLM 사용량·비용 미터링](../features/llm/llm-usage-metering.md)

---

## 17. 권한과 자격증명

| 기능 | 진입점 | 위치 |
|------|--------|------|
| 도구 권한 패턴 (`Bash(git:*)` · `Read(/tmp/**)` 형식) | `agent.tool.permission`, `ToolPermissionSubjectAware` | core |
| 도구 권한 규칙 (값 하나로 표현되지 않는 도구) | `CustomToolPermissionAware`, `CustomToolPermissionRule` | core |
| 권한 훅 | `PermissionRequestHook`, `PermissionDeniedHook` | core |
| 스킬 승인 스코프 (턴 / 세션 / 에이전트) | `skill.policy.*` | core |
| 신원 표현 | `Principal` (user / group / system / service) | core |
| 자격증명 저장소 | `CredentialStore`, `InMemoryCredentialStore` | core |

**관련 문서**
- [도구 개발 가이드 › 권한 시스템](../features/tool/tool-development-guide.md)

---

## 이 문서에 없는 것

- **왜 그렇게 설계했는가** → [`../design/`](../design/)
- **의식적으로 보류한 것** → [`../design/backlog/`](../design/backlog/)
- **버전 업그레이드 절차** → [`../migration/`](../migration/)
- **기여·빌드·퍼블리싱** → [`../project/`](../project/)

## 관련 문서

- [`architecture.md`](architecture.md) — 핵심 추상화 레퍼런스
- [`glossary.md`](glossary.md) — 용어와 수명 사전
- [`scope-model.md`](scope-model.md) — 수명·소유권·소멸 책임 규칙
- [`../features/`](../features/) — 기능별 상세 가이드 색인
