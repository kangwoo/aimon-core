# 아키텍처 (Architecture)

`aimon-core` 의 **핵심 추상화 레퍼런스**. 각 인터페이스가 무엇을 계약하고 어디에 있으며 무엇으로
바꿔 끼울 수 있는지를 다룬다.

- 무슨 기능이 있는지부터 알고 싶다면 → [`features.md`](features.md)
- 용어의 뜻과 수명 → [`glossary.md`](glossary.md)
- 값을 어디 두고 언제 닫는가 → [`scope-model.md`](scope-model.md)

---

## 1. 성격

| 특성 | 내용 |
|------|------|
| **Java 17** | 불변 값 객체 + 빌더 패턴 (`record` 보다 `class` 를 선호한다) |
| **Stateless tools** | 도구는 실행 간 상태를 갖지 않는다 — 설계상 thread-safe |
| **Fail-safe** | `Tool.execute()` 는 예외를 던지지 않고 항상 `ToolResult` 를 반환한다 |
| **Multi-instance ready** | 상태를 가진 컴포넌트는 저장소를 인터페이스로 분리해 스케일아웃 가능 |
| **Pluggable backends** | LLM, 파일시스템, 셸, 세션 저장소, 스케줄러가 전부 추상화되어 있다 |

## 2. 패키지 구조

```
at.aimon.core/
├── agent/          에이전트 실행 — ReAct 루프의 본체
│   ├── tool/         도구 추상화 (+ search, permission)
│   ├── session/      세션 파사드 (+ store, transcript)
│   ├── budget/       실행 예산과 계측
│   ├── compact/      컨텍스트 압축
│   ├── context/      실행 중 갱신되는 컨텍스트 블록
│   ├── stream/       실행 이벤트 (sealed)
│   ├── interrupt/    협조적 중단
│   ├── interceptor/  실행 인터셉터 체인
│   ├── queue/        턴 도중 사용자 입력 큐
│   ├── input/        멀티모달 입력
│   ├── artifact/     실행 산출물 수집
│   ├── prompt/       시스템 프롬프트 조립
│   ├── definition/   Markdown+YAML 에이전트 정의
│   ├── template/     Mustache 렌더링
│   ├── orca/         Orca 도구 프로바이더 공개 SPI
│   └── impl/orca/    Orca 실행기 구현
├── llm/            LLM 클라이언트 추상화 (content, cost, retry, streaming, token, usage)
├── tools/          내장 도구 구현 (file, bash, web, todo, task, skill, …)
├── skill/          스킬 시스템 (execution, fork, hook, policy, render, repository)
├── hook/           라이프사이클 훅 (event, execution, rewake)
├── subagent/       서브에이전트
├── workflow/       워크플로 오케스트레이션
├── command/        사용자 슬래시 명령
├── memory/         장기 기억
├── knowledge/      지식 저장소 + LLM 위키
├── mcp/            MCP 클라이언트
├── scheduling/     예약 실행과 루틴
├── tracing/        실행 트레이싱
├── filesystem/     가상 파일시스템
├── shell/          가상 셸
├── credential/     자격증명 저장소
├── status/         시스템 상태 리포트
├── config/         설정 (훅 핫리로드)
├── toolinvocation/ 단발 도구 호출
└── base/           기반 타입 (Principal, 스코프 마커, …)
```

IMPORTANT (패키지 규약): `at.aimon.core.<domain>` 은 인터페이스와 값 객체, `at.aimon.core.<domain>.impl`
은 구현이다. **`*.impl` 을 도메인 트리 바깥에서 직접 import 하는 것은 ArchUnit이 막는다.** 외부 모듈과
다른 코어 패키지는 중립 SPI 패키지(예: `at.aimon.core.agent.orca`)에 의존한다.

## 3. 계층

각 계층은 아래 계층에만 의존한다.

```
┌───────────────────────────────────────────────────────────────┐
│ Application            aimon-cli, 사용자 애플리케이션            │
└──────────────────────────────┬────────────────────────────────┘
┌──────────────────────────────▼────────────────────────────────┐
│ Session                LiveSession / SessionRouter             │
│                        (턴 제출, 이벤트 구독, 멀티 노드 라우팅)   │
└──────────────────────────────┬────────────────────────────────┘
┌──────────────────────────────▼────────────────────────────────┐
│ Agent execution        OrcaAgentExecutor (ReAct 루프)          │
│   Skill · Subagent · Workflow · Hook · Command · Scheduling    │
└──────────────────────────────┬────────────────────────────────┘
┌──────────────────────────────▼────────────────────────────────┐
│ Core abstraction                                               │
│   Tool · LlmClient · VirtualFileSystem · VirtualShell          │
│   SessionRecordStore · KnowledgeStore · ObservationStore       │
└──────────────────────────────┬────────────────────────────────┘
┌──────────────────────────────▼────────────────────────────────┐
│ Implementation                                                 │
│   내장: ReadTool, BashTool, LocalFileSystem, LocalShell, …      │
│   외부 모듈: aimon-llm-*, aimon-filesystem-*, aimon-session-*,   │
│             aimon-memory-*, aimon-sandbox-*, …                  │
└───────────────────────────────────────────────────────────────┘
```

### ReAct 루프

```
사용자 입력 1건 (= 턴)
  └─ OrcaAgentExecutor.execute(runtime, request)
       ├─ 시스템 프롬프트 조립 (Agent 정의 + 스킬 + ContextProvider)
       ├─ OnStartHook
       ├─ ┌─ iteration (예산이 허용하는 동안 반복) ─────────────┐
       │  │ 1. LlmClient.sendMessage(prompt, messages, tools) │
       │  │ 2. 응답이 텍스트뿐이면 → 최종 답변, 루프 종료        │
       │  │ 3. tool_use 가 있으면                              │
       │  │    ├─ PreToolHook  (BLOCK 가능)                    │
       │  │    ├─ Tool.execute()  (배치 병렬 가능)              │
       │  │    ├─ PostToolHook                                │
       │  │    └─ 결과를 메시지에 append → 1 로 복귀            │
       │  └───────────────────────────────────────────────────┘
       ├─ OnStopHook
       └─ AgentExecutionResult
```

IMPORTANT: 위 반복 1회가 **iteration**, 전체가 **turn** 이다. **execution** 은 턴의 상위 개념으로
세션 없이도 일어난다(서브에이전트 포크, 스킬 포크, rewake 리플레이, 스케줄 루틴). 세 단어는 서로
바꿔 쓸 수 없다 — [`glossary.md` §4](glossary.md).

---

## 4. 핵심 추상화

### 4.1 Agent

에이전트의 **설정**. 실행 상태를 갖지 않는 불변 정의다.

**패키지**: `at.aimon.core.agent`

```java
public interface Agent {
    default String getName() { return getMetadata().getName(); }
    AgentMetadata getMetadata();   // 이름, max iterations, 변수
    AgentContent getContent();     // 시스템 프롬프트, 모델 설정
}
```

Markdown + YAML frontmatter 로도 정의한다.

```yaml
---
name: my-agent
maxIterations: 10
model:
  name: claude-sonnet-4-5
  temperature: 0.5
  maxTokens: 40000
variables:
  language: Java
  tools: ["Read", "Write", "Bash"]
---

# System Prompt
You are a helpful assistant...
```

관련: `DefaultAgent`(빌더), `AgentMetadata`, `AgentContent`,
파서는 `agent.definition` / `agent.parser`.

### 4.2 AgentExecutor / AgentRuntime

**패키지**: `at.aimon.core.agent`

```java
public interface AgentExecutor<
        CTX extends AgentRuntime,
        REQ extends AgentExecutionRequest,
        RES extends AgentExecutionResult> {

    RES execute(CTX agentRuntime, REQ executionRequest);
}
```

이 `execute` 1회가 **턴 1건**이다.

| 타입 | 역할 | 수명 |
|------|------|------|
| `AgentRuntime` | 도구·훅·MCP 레지스트리를 묶은 실행 환경 | **agent-scoped** |
| `AgentRuntimeId` | `agent:<name>[:<discriminator>]` — 결정론적 | agent |
| `AgentRuntimeRegistry` | 런타임 조회 | **application-scoped** |
| `AgentExecutionRequest` / `AgentExecutionResult` | 턴 입력 / 출력 | 턴 |
| `InterceptingAgentExecutor` | 횡단 관심사 데코레이터 | — |
| `AgentEnvironmentSnapshot` | 작업 디렉토리·환경 스냅샷 (`AgentRuntimeId` 로 memoize) | agent |

IMPORTANT: `AgentRuntime` 은 **세션마다 만들지 않는다.** 부트스트랩에서 1회 등록하고, 앱 종료 또는
명시적 agent 제거 시에만 닫는다. `LiveSession.close()` 가 `AgentRuntime.close()` 를 부르면 같은
에이전트의 다른 세션이 깨진다. 전체 규칙은 [`scope-model.md` §2](scope-model.md).

`AgentRuntimeId` 를 실행마다 새로 만들어서도 안 된다 — cron 재발화 시
`ScheduledTask.boundRuntimeId` 가 resolve 되지 않는다. 그래서 `generate()` 는 아예 없고
`from(Agent)` / `from(Agent, String)` 만 있다.

### 4.3 Tool

LLM이 호출할 수 있는 연산 단위. 스키마 정의와 실행 로직을 한 타입에 담는다.

**패키지**: `at.aimon.core.agent.tool`

```java
public interface Tool {
    ToolDefinition getDefinition();  // 이름, 설명, JSON schema
    ToolResult execute(ToolInput input, ToolContext context);
}
```

**계약 4가지**

1. `execute()` 는 **절대 예외를 던지지 않는다** — 항상 `ToolResult.error()` 로 반환한다.
2. `ToolInput` 의 타입 안전 접근자를 쓴다.
3. 실행 간 **상태를 갖지 않는다**.
4. `ToolInput` / `ToolResult` / `ToolContext` 는 전부 **불변**이다.

```java
// ToolInput — 필수 / 기본값 / nullable
String path      = input.getRequiredString("file_path");
int    limit     = input.getInteger("limit", 2000);
String filter    = input.getStringOrNull("filter");
if (input.has("optional_param")) { /* … */ }

// ToolResult
return ToolResult.success("Operation completed");
return ToolResult.error("File not found: " + path);
return ToolResult.error("I/O error: " + e.getMessage(), e);

// ToolContext — 불변, 빌더로 조립
Optional<VirtualFileSystem> vfs = context.get("fileSystem", VirtualFileSystem.class);
```

`AbstractTool` 이 권장 기반 클래스다. 작성법의 정본은
[도구 개발 가이드](../features/tool/tool-development-guide.md).

**부가 메커니즘**

| 기능 | 타입 | 기본값 |
|------|------|--------|
| 배치 내 병렬 실행 | `ConcurrencyBehavior`, `ParallelToolDispatcher`, `ToolConcurrencyConfig` | `SEQUENTIAL` (병렬 off) |
| 중단 동작 선언 | `InterruptBehavior` | — |
| 지연 로딩 + 검색 노출 | `ToolLoadingMode`, `ToolSearchRegistry`, `ToolSearchStrategy` | — |
| 입력 스키마 검증 | `agent.tool.schema`, `SchemaValidationMode` | `WARN` (기록만, 실행은 그대로) |
| 권한 대상 선언 | `ToolPermissionSubjectAware`, `PermissionSubject` | — |
| 권한 규칙 (값 하나로 표현되지 않을 때) | `CustomToolPermissionAware`, `CustomToolPermissionRule` | — |

권한 패턴: `"Read"`(무조건 허용) · `"Bash(git:*)"`(COMMAND prefix) · `"Read(/tmp/**)"`(PATH 글롭) ·
`"Bash(npm install)"`(정확히 일치). 매처는 `PermissionSubject.Kind` 가 고른다 — 도구가 명령을 내놓으면
`ToolPattern`, 경로를 내놓으면 `PathPattern`.

스키마 검증은 `execute()` **앞에** 선다 — `required` 누락 · 타입 불일치 · `enum` 이탈 · 선언되지 않은
파라미터 이름 네 가지만 본다. 범위(`minimum` 등)는 보지 않으므로 그 검사는 도구 안에 남는다.

### 4.4 LlmClient

**패키지**: `at.aimon.core.llm`

```java
public interface LlmClient {
    LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                            List<ToolDefinition> tools, LlmModel modelConfig,
                            LlmCallMetadata metadata);

    String getProviderName();
}
```

편의 오버로드(`systemPrompt` 만, `SystemPromptParts` 로, 취소 토큰 포함)와 스트리밍
(`sendMessageStreaming`)은 `default` 메서드로 제공되므로, 새 프로바이더는 위 하나만 구현하면 된다.

| 타입 | 역할 |
|------|------|
| `Message`, `Role` | 대화 메시지 (USER / ASSISTANT / TOOL) |
| `TextContentBlock`, `ImageContentBlock`, `DocumentContentBlock` | 멀티모달 콘텐츠 블록 |
| `ToolUse`, `ToolUseResult` | 도구 호출 요청 / 결과 |
| `LlmResponse`, `StopReason` | 응답과 종료 사유 |
| `LlmModel` | 모델 파라미터 |
| `TokenUsage`, `CostSummary`, `ModelPriceTable` | 토큰·비용 계측 |
| `LlmCallMetadata`, `BoundMetadataLlmClient` | 호출 태깅 |
| `LlmCancellation` | 취소 |
| `ModelContextWindowRegistry` | 모델별 컨텍스트 윈도우 |

구현: `aimon-llm-openai`, `aimon-llm-anthropic`.
새 프로바이더는 [LLM Provider 개발 가이드](../features/llm/llm-provider-development-guide.md).

### 4.5 VirtualFileSystem

**패키지**: `at.aimon.core.filesystem`

```java
public interface VirtualFileSystem extends AutoCloseable {
    // 콘텐츠
    void write(String path, InputStream content, long contentLength);
    void write(String path, byte[] content);
    void write(String path, String content);
    InputStream read(String path);
    void delete(String path);

    // 메타데이터
    boolean exists(String path);
    boolean isDirectory(String path);
    FileMetadata getMetadata(String path);

    // 디렉토리
    List<String> list(String directory);
    List<String> listRecursive(String directory);
    void createDirectory(String path);
    void deleteRecursive(String path);

    // 복사 / 이동
    void copy(String sourcePath, String destinationPath, boolean overwrite);
    void move(String sourcePath, String destinationPath, boolean overwrite);

    // 스트리밍
    OutputStream openOutputStream(String path);
    InputStream openInputStream(String path);

    // 검색 / 백엔드
    List<String> search(String directory, String pattern, int maxResults);
    String getWorkingDirectory();
    void initialize();
    BackendStatus getStatus();
    void close();
}
```

**모든 구현이 지켜야 할 보안 요구사항**: 경로 탈출(`../`) 차단, null 바이트·제어문자 거부,
심볼릭 링크 차단(로컬 파일시스템). `PathValidator` 가 공통 검증을 제공한다.

구현: `filesystem.impl.local`(코어 내장), `ScopedVirtualFileSystem`(루트 제한 래퍼),
`aimon-filesystem-gridfs`(MongoDB GridFS), `aimon-filesystem-s3`(AWS S3).

### 4.6 VirtualShell

**패키지**: `at.aimon.core.shell`

```java
public interface VirtualShell extends AutoCloseable {
    ShellCommandResult execute(ShellCommand command);
    ShellCommandResult execute(ShellCommand command, ExecutionOptions options);
    String getWorkingDirectory();
    boolean supports(ShellFeature feature);
    void close();
}
```

`ShellCommandResult` 는 exit code, stdout, stderr, 소요 시간을 담는다.
샌드박스 모듈(`aimon-sandbox-docker`, `aimon-sandbox-kubernetes`)이 격리 실행 구현을 제공한다.

### 4.7 Session

**패키지**: `at.aimon.core.agent.session` (+ `.store`, `.transcript`)

턴을 제출하고 이벤트를 구독하는 파사드는 `LiveSession` 이고, 영속되는 것은 `SessionRecord` 다.

```java
LiveSession session = /* opener 또는 factory */;
AgentExecutionResult result = session.submit("배포 로그 좀 봐줘");
session.events().subscribe(event -> { /* AgentExecutionEvent */ });
```

| 타입 | 역할 | 수명 |
|------|------|------|
| `LiveSession` | 노드 로컬 핸들 — `submit` / `submitAsync` / `offerAsync` / `events()` / `status()` | **일시적** |
| `SessionRecord` | 전사 + side field(`sessionTotals`, `budgetOverride`, …) | **영속** |
| `SessionTranscript`, `SessionSnapshot` | 메시지 이력 (불변) | 세션 |
| `SessionRecordStore` | 레코드 저장소 | **application-scoped** |
| `SessionLeaseStore` | 노드 간 소유권 선출 + 펜싱 | **application-scoped** |
| `SessionStore` | 위 둘을 묶은 노드 스코프 합성물 (`claim`) | 노드 |
| `TurnId` | 턴 주소 지정 (인터럽트 타겟팅, 이벤트 태깅) | **비영속** |

IMPORTANT: `1 SessionRecord : 0..N LiveSession` 의 **비대칭** 관계다. 재시작·축출·노드 이동을 넘어
살아남아야 하는 값은 반드시 레코드 쪽에 둔다. 그리고 **어느 쪽도 맨 단어 `Session` 을 타입 이름으로
갖지 않는다** — `Session` 과 `AgentSession` 은 `SessionNamingArchitectureTest` 가 빌드에서 막는다.
근거는 [`scope-model.md` §3](scope-model.md).

멀티 노드 라우팅(`SessionRouter`, `LiveSessionCache`, `LiveSessionOpener`)은 `aimon-session-routing`,
백엔드는 `aimon-session-{mongodb,postgres,redis}` 에 있다.

---

## 5. 확장 시스템

### 5.1 Skill

프롬프트·도구·훅을 묶은 선언적 패키지. [Agent Skills](https://agentskills.io/) 표준을 따른다.

**패키지**: `at.aimon.core.skill`

```java
Skill skill = Skill.builder()
    .name("alert-analysis")
    .metadata(SkillMetadata.builder()
        .name("alert-analysis")
        .description("Analyzes monitoring alerts")
        .build())
    .content(SkillContent.of("# Alert Analysis\n..."))
    .putScript("query.py", "scripts/query.py")
    .putReference("patterns.md", "references/patterns.md")
    .build();
```

디스크 레이아웃:

```
skill-name/
├── SKILL.md      메타데이터(YAML frontmatter) + 시스템 프롬프트
├── scripts/      실행 스크립트
├── references/   문서
└── assets/       템플릿·정적 자원
```

| 타입 | 역할 |
|------|------|
| `Skill`, `SkillMetadata`, `SkillContent` | 불변 스킬 표현 |
| `SkillRegistry` (+ `Default*`, `Composite*`) | 발견·조회 |
| `ClasspathSkillRepository` / `PathSkillRepository` / `VfsSkillRepository` | 저장소 |
| `MarkdownSkillParser` | SKILL.md 파싱 |
| `ExecutionMode`, `SubagentBackedSkillForkExecutor` | 인라인 실행 vs 포크 실행 |
| `InvokePolicy`, `skill.policy.*` | 호출 정책과 승인 |
| `SkillTool` | LLM에게 스킬을 도구로 노출 |

**승인 스코프**는 좁은 것부터 넓은 것 순으로 pending(이번 턴) → session(이 세션) → agent(이 에이전트)
이며 정책 체인도 그 순서로 조회한다. 되돌리기는 `/revoke`, 에이전트 전역까지는 `/revoke --agent`.

### 5.2 Hook

**패키지**: `at.aimon.core.hook` (이벤트 타입은 `hook.event`, 실행 계약은 `hook.execution`)

훅은 `HookEventType` 상수로 등록한다.

```java
HookRegistry registry = new DefaultHookRegistry();
registry.register(HookEventType.PRE_TOOL, context -> {
    log.info("Executing: {}", context.getCurrentToolUse().getName());
    return HookResult.allow();
});
```

**13개 이벤트 타입**

| 이벤트 타입 | 발화 시점 | 흐름 차단 |
|------------|----------|----------|
| `PRE_TOOL` / `POST_TOOL` | 도구 실행 직전 / 직후 | 직전만 가능 |
| `ON_START` / `ON_STOP` | 실행 시작 / 종료 | — |
| `PRE_COMPACT` / `POST_COMPACT` | 컨텍스트 압축 직전 / 직후 | — |
| `PERMISSION_REQUEST` / `PERMISSION_DENIED` | 권한 요청 시 / 거부 시 | 요청만 가능 |
| `SUBAGENT_START` / `SUBAGENT_STOP` | 서브에이전트 시작 / 종료 | — |
| `ON_SESSION_START` / `ON_SESSION_END` | **라이브 세션** 열기 / 닫기 | — |
| `ON_CONFIG_RELOAD` | 훅 설정 핫리로드 시 | — |

| 타입 | 역할 |
|------|------|
| `HookRegistry`, `HookExecutionManager` | 등록·실행 |
| `HookResult`, `Decision`(`ALLOW`/`ASK`/`DENY`), `FlowControl`(`CONTINUE`/`BLOCK`) | 훅의 판정 |
| `HookFeedback` | 훅이 남긴 조언을 `<system-reminder>` 블록으로 합성 |
| `HookExecutionPolicy` | 타임아웃·실행 모드 |
| `config.hook.*` | 파일 기반 설정, 계층 병합, 핫리로드 |
| `hook.rewake` | 외부 이벤트로 에이전트를 깨우는 비동기 rewake |

IMPORTANT (알려진 오칭): `ON_SESSION_START` / `ON_SESSION_END` 는 세션(레코드)이 아니라
**`LiveSession` 의 열기/닫기**에 발화한다. 같은 세션이 재개되면 다시 발화한다.

### 5.3 Subagent

**패키지**: `at.aimon.core.subagent`

```java
Subagent subagent = Subagent.builder()
    .name("log-analyzer")
    .description("Analyzes application logs for error patterns")
    .whenToUse("When a deployment fails and logs need triage")
    .systemPrompt("Analyze application logs...")
    .tools(List.of("Read", "Grep"))
    .maxIterations(8)
    .build();
```

`SubagentRegistry` 로 등록하고 `SubagentExecutionManager` / `DefaultSubagentExecutor` 가 실행한다.
Markdown 정의는 `subagent.parser` / `subagent.repository`, 백그라운드 실행은
`SubagentBackgroundConfig` / `SubagentTaskController`.

IMPORTANT: 포크는 **자기 `SessionId` 가 없다.** 세션의 턴이 아니므로 툴 컨텍스트에 `SESSION_ID` 가
실리지 않고 `EXECUTION_ID` 만 실린다. 승인 정책이 부모 세션의 결정을 따라오게 하려고 자기를 띄운 세션
id 를 `invokingSessionId` 로 별도 전달한다.

### 5.4 Workflow

**패키지**: `at.aimon.core.workflow`

여러 서브에이전트를 **결정론적 제어 흐름**으로 엮는다. `WorkflowRunners` 로 러너를 만들고
`Pipeline` / `Stage` / `AgentTask` 로 조립하며, `RunHandle` 과 `RunStore` 로 재개할 수 있다.
예산은 `WorkflowBudget`, 동시성은 `WorkflowConcurrencyConfig`, git 격리는
`WorktreeEnvironmentFactory` 가 담당한다. JS 스크립트 프론트엔드는 `aimon-workflow-graaljs`.

IMPORTANT (소멸 책임): `WorkflowRunner` 에는 두 변형이 있다 — agent-scoped 변형은
`OrcaAgentRuntimeFactory` 가 만들고 `OrcaAgentRuntime.close()` 가 닫으며, call-scoped 변형은
`WorkflowTool` 이 호출마다 만들고 try-with-resources 로 닫는다. **만든 쪽이 닫는다.**

### 5.5 Command

**패키지**: `at.aimon.core.command`

```java
CommandType.SYSTEM   // 코어 내장 (/help, /status, /compact …)
CommandType.CUSTOM   // 사용자 정의
```

내장 명령은 `command.system` 에 있다 — `/help`, `/commands`, `/version`, `/status`, `/agents`,
`/skills`, `/clear`, `/compact`, `/pending`, `/approve`, `/deny`, `/revoke`, `/rewakes`.
스킬을 명령으로 노출하려면 `SkillBackedCommand` 를 쓴다.

> 0.1.0 에서 `CustomCommand` 는 제거되고 스킬로 통합되었다 —
> [마이그레이션 가이드](../migration/custom-command-to-skill.md).

### 5.6 Scheduling

**패키지**: `at.aimon.core.scheduling`

`SchedulingEngine`(빌더로 조립) / `ScheduledTaskManager` / `RoutineExecutor` 가 중심이다.
쿼터(`scheduling.quota`)와 실행 가드(`ScheduledExecutionGuard`)로 폭주를 막는다.
클러스터 구현은 `aimon-scheduling-quartz`.

IMPORTANT: 이 셋은 **application-scoped** 다. `AgentRuntime` 이 소멸해도 닫으면 안 된다.
`AgentRuntimeRegistry` 도 `SchedulingEngine` **바깥**에서 만들어 빌더로 주입하며,
`SchedulingEngine` 이 소유하지 않는다.

---

## 6. Orca 에이전트

`OrcaAgentExecutor` 가 프로덕션 ReAct 구현체다.

**패키지**: `at.aimon.core.agent.impl.orca` (공개 SPI는 `at.aimon.core.agent.orca`)

```java
public class OrcaAgentExecutor implements AgentExecutor<
    OrcaAgentRuntime,
    OrcaAgentExecutionRequest,
    OrcaAgentExecutionResult> { … }
```

### 도구 프로바이더

Orca는 도구를 도메인별 프로바이더로 조립한다. 외부 모듈은
`at.aimon.core.agent.orca` 의 `OrcaToolProvider` / `OrcaToolProviderContext` /
`OrcaProviderDependencies` 를 구현해 합류한다 (`impl` 패키지를 import 하지 않는다).

| 프로바이더 | 제공 도구 |
|-----------|----------|
| `OrcaFileToolProvider` | `Read`, `Write`, `Edit`, `Grep` |
| `OrcaBashToolProvider` | `Bash`, `BashOutput` |
| `OrcaSkillToolProvider` | `Skill` |
| `OrcaSubagentToolProvider` | `Task`, `TaskList`, `TaskStop`, `AgentOutput` |
| `OrcaTodoToolProvider` | `TodoWrite` |
| `OrcaSchedulingToolProvider` | `schedule_task`, `list_scheduled_tasks`, `cancel_scheduled_task` |
| `OrcaKnowledgeToolProvider` | `KnowledgeSearch`, 위키 도구 |

내장 도구 전체 목록은 [기능 카탈로그 §3.2](features.md).

### 실행 흐름

1. Agent 정의 + 스킬 + `ContextProvider` 로 시스템 프롬프트 조립
2. `OnStartHook`
3. ReAct 루프 — 예산(`BudgetTracker`)이 허용하는 동안 iteration 반복
   - `LlmClient` 호출 (스트리밍 가능)
   - 텍스트만 있으면 최종 답변
   - `tool_use` 가 있으면 `PreToolHook`(BLOCK 가능) → 실행 → `PostToolHook` → 결과 append
   - 컨텍스트가 임계에 닿으면 `CompactionEngine` 이 압축 (`PRE_COMPACT` / `POST_COMPACT` 발화)
4. `OnStopHook`
5. `OrcaAgentExecutionResult` 반환

`BudgetTracker` 를 새로 만드는 곳은 main 소스에 **정확히 2곳**이다 — `OrcaAgentExecutor`(턴)와
`DefaultSubagentExecutor`(포크). 예산이 세션 단위가 아니라 **실행 단위**라는 사실이 여기서 드러난다.

---

## 7. 확장점 요약

| 확장하려는 것 | 인터페이스 | 방법 |
|--------------|----------|------|
| 도구 | `Tool` / `AbstractTool` | 상속 후 `ToolRegistry` 에 등록 |
| Orca 도구 묶음 | `OrcaToolProvider` | `at.aimon.core.agent.orca` SPI 구현 |
| LLM 프로바이더 | `LlmClient` | 별도 모듈에서 구현 |
| 파일 백엔드 | `VirtualFileSystem` | 구현 (GridFS, S3 참조) |
| 셸 백엔드 | `VirtualShell` | 구현 (샌드박스 모듈 참조) |
| 라이프사이클 훅 | `hook.event` 의 13개 인터페이스 | `HookRegistry.register(HookEventType, hook)` |
| 스킬 | `Skill` | Agent Skills 표준의 SKILL.md 작성 |
| 서브에이전트 | `Subagent` | 코드 빌더 또는 Markdown 정의 |
| 명령 | `Command` | 구현 후 `CommandRegistry` 등록 |
| 세션 저장소 | `SessionRecordStore` / `SessionLeaseStore` | 구현 (Mongo/Postgres/Redis 참조) |
| 지식 저장소 | `KnowledgeStore` | 구현 (OpenSearch 참조) |
| 메모리 저장소 | `ObservationStore` / `RepresentationStore` | 구현 (file/Postgres/Mongo 참조) |
| 스케줄러 | `scheduling.scheduler` SPI | 구현 (Quartz 참조) |
| 실행 인터셉터 | `AgentExecutionInterceptor` | 체인에 추가 |
| 트레이스 내보내기 | `SpanExporter` / `TraceSpanStore` | 구현 |

## 8. 적용된 설계 패턴

| 패턴 | 사용처 | 목적 |
|------|--------|------|
| **Builder** | `Agent`, `AgentMetadata`, `AgentContent`, `Skill`, `ToolContext` | 불변 객체 조립 |
| **Strategy** | `VirtualFileSystem`, `VirtualShell`, `LlmClient`, `ToolSearchStrategy` | 백엔드 교체 |
| **Template Method** | `AbstractTool` | 일관된 실행 구조 |
| **Chain of Responsibility** | `AgentExecutionChain`, 훅 실행, 승인 정책 체인 | 순차 처리 파이프라인 |
| **Composite** | `CompositeSkillRegistry`, `CompositeCommandExecutor` | 여러 소스 합성 |
| **Factory** | 도구 프로바이더, `LiveSessionFactory`, `OrcaAgentRuntimeFactory` | 생성 지점 집중 |
| **Decorator** | `ArtifactAware*` 도구, `InterceptingAgentExecutor`, `TracingLlmClient`, `BoundMetadataLlmClient` | 수정 없이 행동 추가 |
| **Registry** | `ToolRegistry`, `SkillRegistry`, `HookRegistry`, `AgentRuntimeRegistry` | 컴포넌트 컬렉션 관리 |
| **Observer** | 훅, `AgentExecutionEvent` 스트림 | 이벤트 통지 |
| **Repository** | `SessionRecordStore`, `SkillRepository`, `SubagentRepository`, `RunStore` | 데이터 접근 추상화 |

설계 원칙 자체는 [SOLID 원칙 문서](../project/solid-principles.md) 참조.

---

## 관련 문서

- [`features.md`](features.md) — 기능 카탈로그 (무엇을 할 수 있는가)
- [`glossary.md`](glossary.md) — 용어와 수명 사전
- [`scope-model.md`](scope-model.md) — 수명·소유권·소멸 책임 규칙
- [`../features/`](../features/) — 기능별 상세 가이드
- [도구 개발 가이드](../features/tool/tool-development-guide.md)
- [훅 개발 가이드](../features/hook/hook-development-guide.md)
- [LLM Provider 개발 가이드](../features/llm/llm-provider-development-guide.md)
- [Agent Skills 표준 명세](../references/agentskills-specification.md)
