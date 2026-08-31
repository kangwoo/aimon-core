# aimon-core 통합 가이드 — aimon-cli를 레퍼런스로 따라가기

> `aimon-cli`가 `aimon-core`를 어떻게 조립하는지 코드 단위로 따라가면서, 자신의 애플리케이션에 동일한 패턴을 이식하는 방법을 설명한다.

## 이 문서의 위치

이미 다음 문서들이 있다. 목적이 다르므로 함께 읽으면 좋다.

| 문서 | 목적 |
|------|------|
| [architecture.md](../overview/architecture.md) | `aimon-core`의 핵심 추상화(Tool, LlmClient, VirtualFileSystem 등) 레퍼런스 |
| [embedding-agent-in-application.md](embedding-agent-in-application.md) | Spring Boot/SDK 임베딩의 권장 패턴, 스코프 정책, 멀티세션 |
| [agent-session-guide.md](../features/session/agent-session-guide.md) | `LiveSession` API와 이벤트 스트리밍 사용법 |
| [scope-model.md](../overview/scope-model.md) | 수명·소유권·소멸 책임의 규범 문서 — 이 문서의 스코프 서술은 전부 그것을 따른다 |
| **이 문서** | **`aimon-cli`의 실제 부트스트랩 코드를 한 줄씩 따라가며 "왜 그렇게 조립했는지"를 설명** |

`aimon-cli`는 코어가 제공하는 모든 확장 포인트(LLM, 파일시스템, 도구, 스킬, 훅, 스케줄링, MCP)를 한 곳에서 꽂아 쓰는 가장 완전한 레퍼런스 구현이다. 이 문서는 그 코드를 그대로 읽으며 자신의 호스트 애플리케이션에 옮길 때의 결정 포인트를 짚어준다.

---

## 목차

1. [전체 그림](#1-전체-그림)
2. [모듈 의존성과 빌드 설정](#2-모듈-의존성과-빌드-설정)
3. [부트스트랩 흐름 — `AimonCli.call()`](#3-부트스트랩-흐름--aimonclicall)
4. [`AgentSetupFactory.create()`를 한 줄씩](#4-agentsetupfactorycreate를-한-줄씩)
5. [구성 요소별 적응 가이드](#5-구성-요소별-적응-가이드)
6. [라이프사이클과 스코프](#6-라이프사이클과-스코프)
7. [최소 임베딩 예제](#7-최소-임베딩-예제)
8. [웹 애플리케이션으로 옮기기](#8-웹-애플리케이션으로-옮기기)
9. [기타 적응 시나리오](#9-기타-적응-시나리오)
10. [체크리스트](#10-체크리스트)

---

## 1. 전체 그림

`aimon-cli`는 세 개의 큰 단계로 동작한다.

```
[Picocli 진입점]                  [팩토리 부트스트랩]                  [세션 실행]
AimonCli.call()         ──▶      AgentSetupFactory.create(config)   ──▶  ReplSession.start()
- 옵션 파싱                       - LLM 클라이언트 생성                  - LiveSession.submit(input)
- 설정 로드                       - VirtualFileSystem 초기화             - 이벤트 스트림 구독
- AgentSetup 오픈                 - Tool/Skill/Hook 등록                  - 사용자 입력 큐잉
- AgentSetup.close()              - SchedulingEngine 시작
                                  - LiveSession 생성
```

여러분의 애플리케이션도 같은 세 단계를 따른다. **Picocli 진입점만 자기 진입점(웹 핸들러, 배치 잡 등)으로 바꾸고, REPL을 자기 인터랙션 루프로 바꾼다.** 가운데의 부트스트랩 단계는 거의 그대로 가져갈 수 있다.

레이어 다이어그램:

```
┌─────────────────────────────────────────────────────────────┐
│  Application                                                 │
│  AimonCli (CLI)  /  HTTP handler  /  Batch job              │
└──────────────────────────────┬──────────────────────────────┘
                               │ create(CliConfig)
┌──────────────────────────────▼──────────────────────────────┐
│  Composition root (AgentSetupFactory)                       │
│  LlmClient + VirtualFileSystem + AgentBundle +              │
│  ToolRegistry + SkillRegistry + HookRegistry +              │
│  SchedulingEngine + AgentRuntime +                          │
│  AgentExecutor + SessionRecordStore + LiveSession           │
└──────┬────────────┬──────────────┬──────────────┬───────────┘
       │            │              │              │
┌──────▼──┐    ┌────▼────┐   ┌─────▼──────┐  ┌────▼─────────┐
│ aimon-  │    │ aimon-  │   │ aimon-     │  │ aimon-       │
│ llm-*   │    │ filesys │   │ scheduling │  │ knowledge-*  │
└─────────┘    │ -*      │   │ -quartz    │  └──────────────┘
               └─────────┘   └────────────┘
```

`aimon-core`는 인터페이스만 정의한다. 실제 구현체는 별도 모듈에서 가져와 조립한다 — 이게 `aimon-cli`가 보여주는 핵심 패턴이다.

---

## 2. 모듈 의존성과 빌드 설정

### `aimon-cli`의 `build.gradle.kts`

`modules/aimon-cli/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    application
}

application {
    mainClass.set("at.aimon.cli.AimonCli")
}

dependencies {
    // Core 모듈 (인터페이스 + Orca 실행기)
    implementation(project(":aimon-core"))

    // LLM 구현체 — 필요한 것만 골라 넣는다
    implementation(project(":aimon-llm-anthropic"))
    implementation(project(":aimon-llm-openai"))

    // CLI 전용 (자신의 앱에서는 불필요)
    implementation(libs.picocli)
    implementation(libs.jline)
    implementation(libs.jansi)

    // 설정 파싱 (선택)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.snakeyaml)

    // 로깅
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)
}
```

### 자기 애플리케이션의 의존성

**최소 구성:**

```kotlin
dependencies {
    implementation(project(":aimon-core"))      // 또는 published artifact
    implementation(project(":aimon-llm-openai")) // 적어도 LLM 하나
}
```

**필요에 따라 추가:**

| 모듈 | 언제 필요한가 |
|------|-------------|
| `aimon-llm-openai` / `aimon-llm-anthropic` | LLM 호출 (둘 중 하나는 필수) |
| `aimon-filesystem-gridfs` | MongoDB GridFS를 가상 파일 시스템 백엔드로 |
| `aimon-filesystem-s3` | S3/MinIO를 백엔드로 |
| `aimon-scheduling-quartz` | 작업 스케줄링이 필요하면 |
| `aimon-knowledge-opensearch` | 벡터 검색 기반 KnowledgeStore |
| `aimon-sandbox-docker` / `aimon-sandbox-kubernetes` | 격리된 셸 실행이 필요하면 |
| `aimon-browser-playwright` | 브라우저 자동화 도구가 필요하면 |

> **모듈 의존성 규칙** (.claude/rules/architecture.md): 구현 모듈은 `implementation(project(":aimon-core"))`로만 코어를 참조한다. `api()`로 노출하지 않는다 — 코어 타입이 트랜지티브 의존성으로 새는 것을 막기 위해.

---

## 3. 부트스트랩 흐름 — `AimonCli.call()`

`modules/aimon-cli/src/main/java/at/aimon/cli/AimonCli.java:67-136`

```java
public Integer call() {
    // (1) 설정 로드
    CliConfigLoader configLoader = new CliConfigLoader();
    CliConfig config = loadConfiguration(configLoader);

    // (2) CLI 옵션을 설정에 반영 (스트리밍 토글, 초기 예산)
    if (streaming != null) {
        config.getCliSettings().setStreaming(streaming);
    }
    ExecutionBudget initialBudget = buildInitialBudget();

    // (3) AgentSetup 오픈 (try-with-resources)
    AgentSetupFactory agentFactory = new AgentSetupFactory();
    try (AgentSetupFactory.AgentSetup agentSetup = agentFactory.create(config)) {

        // (4) 인터랙션 루프 시작 — CLI에서는 REPL
        ReplSession replSession = new ReplSession(agentSetup, cliSettings, initialBudget);
        replSession.start();
    }
    return 0;
}
```

자신의 앱에서 옮길 때의 매핑:

| `AimonCli`의 단계 | 여러분의 코드 |
|-------------------|----------------|
| `configLoader.load(path)` | Spring `@ConfigurationProperties`, env-vars, 커스텀 YAML 파서 등 |
| `factory.create(config)` | **그대로 유지** — `AgentSetupFactory`를 직접 호출하거나, 그 내부 로직을 자신의 컴포지션 루트로 옮긴다 |
| `try-with-resources` | Spring `@Bean(destroyMethod = "close")` / Quarkus `@PreDestroy` 등으로 라이프사이클 위임 |
| `replSession.start()` | HTTP 핸들러, 배치 잡, WebSocket 메시지 수신 루프 |

---

## 4. `AgentSetupFactory.create()`를 한 줄씩

`modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java:687-902`

이 메서드 하나가 `aimon-core`의 모든 조립 패턴을 담고 있다. 단계별로 본다.

> 아래 인용한 줄 번호는 그 순간의 좌표일 뿐이고 이 파일은 계속 자란다(트레이싱, peer memory, dreamer,
> rewake, 세션 체크포인트, GraalJS가 차례로 들어왔다). 번호가 어긋나면 **메서드 이름으로 찾는다** —
> 이 문서가 참조하는 헬퍼는 전부 `create*` / `build*` / `configure*` / `register*` 접두어를 가진다.

### 4.1 LLM 클라이언트 생성 (line 690)

```java
final LlmClient llmClient = createLlmClient(config);
```

내부적으로 `LlmClientFactory.create()`가 provider 문자열로 분기한다 (`LlmClientFactory.java:16-28`):

```java
return switch (provider) {
    case "anthropic" -> createAnthropicClient(config);
    case "openai"    -> createOpenAIClient(config);
    default          -> throw new ConfigurationException("Unsupported LLM provider: " + provider);
};
```

각 빌더는 SDK별 설정 객체(`AnthropicConfig`, `OpenAIConfig`)를 만들어 `apiKey`, `model`, `timeout`, `baseUrl`을 주입한다.

`cli.tracing`이 켜져 있으면 그 위에 한 겹이 더 붙는다 (line 697-712) — `TracingLlmClient`가 원본 클라이언트를
감싸고, 같은 `Tracer`가 실행기 팩토리에도 주입되어 턴/이터레이션/도구 span까지 한 트리에 모인다. 감싸는 대상은
**에이전트 턴 경로뿐**이다. 백그라운드 서브시스템(wiki 인덱싱, peer memory, dreamer)은 의도적으로 원본
`llmClient`를 그대로 받는다 — 그쪽 호출에는 턴 span 컨텍스트가 없어 감싸도 span이 생기지 않는다.

**여러분의 적응 포인트:**
- 자체 LLM 게이트웨이가 있다면 `LlmClient`를 직접 구현해서 주입한다. `LlmClientFactory`를 안 거쳐도 된다.
- `LlmClient` 인스턴스는 **애플리케이션 스코프**다. 한 번 만들고 모든 세션에서 공유한다.
- 데코레이터를 얹을 거라면 CLI처럼 **원본과 감싼 것을 둘 다 들고** 어느 쪽을 어디에 넘길지 정한다. 하나로
  합치면 백그라운드 작업까지 트레이스에 섞인다.

### 4.2 출력 포매터 + 셸 + 스킬 파서 (line 713-725)

```java
final OutputFormatter outputFormatter = createOutputFormatter(config);
final LocalShell skillHookShell = new LocalShell();
final SkillParser skillParser = createShellAwareSkillParser(skillHookShell);
final AgentBundleLoader effectiveBundleLoader = (this.agentBundleLoader != null)
        ? this.agentBundleLoader
        : new AdaptiveAgentBundleLoader(DEFAULT_AGENT_BUNDLE_BASE_PATH,
                new MarkdownAgentDefinitionParser(),
                Thread.currentThread().getContextClassLoader(), skillParser);
final AgentBundle agentBundle = effectiveBundleLoader.load(extractAgentName(config));
```

- **`OutputFormatter`** — 콘솔 색상/포매팅 담당. 자신의 앱에서는 SSE 스트리머, 로그 어펜더, WebSocket 송신기 등으로 대체한다.
- **`LocalShell`** — 스킬 frontmatter의 `shell` 액션을 실행할 셸. `AutoCloseable`로 `AgentSetup.close()`에서 정리된다.
- **`SkillParser`** — 마크다운 스킬 정의 파서. `LocalShell`을 주입해서 `shell` 훅이 실제 실행되게 한다.
- **`AgentBundleLoader`** — `agents/<name>/agent.md`와 그 하위의 서브에이전트, 스킬을 한 번에 로드한다. 클래스패스에서 읽으므로 jar로 패키징된다.

**여러분의 적응 포인트:**
- Agent 정의를 코드/DB에서 동적으로 만들고 싶으면 `AgentBundle`을 직접 빌드해서 `AgentSetupFactory`의 패키지-프라이빗 생성자로 주입한다.
- 셸 실행을 컨테이너에 격리하려면 `aimon-sandbox-docker`/`aimon-sandbox-kubernetes`의 `VirtualShell` 구현체로 교체.

### 4.3 세션 레코드 저장소, 트랜스크립트 매니저, 메시지 큐, 파일 시스템 (line 726-733)

```java
final SessionCheckpointMailbox sessionCheckpoints = createSessionCheckpointMailbox();
final InMemorySessionRecordStore sessionRecordStore = new InMemorySessionRecordStore();
final TranscriptManager transcriptManager = createTranscriptManager(sessionRecordStore, sessionCheckpoints);
final MessageQueueManager messageQueueManager = createMessageQueueManager();
final LocalFileSystem fileSystem = createFileSystem();
```

기본 구현 (`AgentSetupFactory.java:1033, 1044, 1076, 1083`):

```java
private TranscriptManager createTranscriptManager(InMemorySessionRecordStore repository,
        SessionCheckpointMailbox checkpoints) {
    return new DefaultTranscriptManager(repository, checkpoints);
}

private SessionCheckpointMailbox createSessionCheckpointMailbox() {
    return SessionCheckpointMailbox.background();
}

private MessageQueueManager createMessageQueueManager() {
    return new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
}

private LocalFileSystem createFileSystem() {
    final String workingDirectory = getJarDirectory();
    final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(workingDirectory));
    fileSystem.initialize();
    return fileSystem;
}
```

여기서 이름 세 개를 구분해 둬야 뒤가 편하다.

| 타입 | 무엇을 들고 있나 | 수명 |
|------|----------------|------|
| `SessionRecordStore` | `SessionId`로 식별되는 **영속 세션 레코드** — 메시지 이력, `SessionTotals`, `budgetOverride` | 저장소 자체는 **애플리케이션 스코프**, 항목은 세션별 |
| `TranscriptManager` | 그 레코드 위에서 LLM **메시지 교환**(conversation)을 읽고 쓰는 매니저 | 애플리케이션 스코프 |
| `SessionCheckpointMailbox` | end-of-turn 저장 사이에 진행 중 세션을 자기 스레드에서 비동기로 흘려보내는 우편함 | 애플리케이션 스코프 |

`sessionRecordStore`를 팩토리 지역 변수로 **끌어올려 둔 것이 의도적**이다 (line 727-730 주석). 같은 인스턴스가
트랜스크립트 매니저(메시지 이력)와 아래에서 만드는 `LiveSession`(세션의 영속 side field 두 개 —
`sessionTotals`, `budgetOverride`) 양쪽에 들어가야 한다. 두 개로 나누면 라이브 세션이 되쓴 누적치를
트랜스크립트 쪽이 못 보게 된다.

**핵심 원칙** (CLAUDE.md): 상태를 가진 컴포넌트는 저장소를 인터페이스로 분리한다. CLI는 in-memory 구현으로 충분하지만, 멀티 인스턴스 환경에서는 `SessionRecordStore`/`MessageQueueRepository`를 분산 백엔드 구현으로 갈아끼우면 된다.

**여러분의 적응 포인트:**
- 멀티 인스턴스: `InMemorySessionRecordStore` → `aimon-session-mongodb` / `aimon-session-postgres` / `aimon-session-redis`의 영속 구현.
- 다중 사용자: `LocalFileSystem` → `GridFSFileSystem` 또는 `S3FileSystem`. 사용자별로 작업 디렉터리를 분리한다.
- `MessageQueueManager`는 같은 세션 내에서 producer(REPL)와 consumer(executor의 ReAct 루프)가 **반드시 같은 인스턴스를 공유**해야 한다 — 이게 mid-turn 사용자 입력 주입을 가능하게 한다.

> **자바 이름은 `Session*`인데 저장된 이름은 `conversation_*`이다.** Mongo 컬렉션
> (`conversation_locks` / `conversation_inbox` / `conversation_signals`), Postgres 테이블·채널
> (`conversation_*`), 와이어 키(`"conversationId"`, `"invokingConversationId"`), Redis 키 prefix는
> **의도적으로 동결**되어 있다 — 개명은 자바 식별자에서만 일어났고 이미 배포된 데이터의 마이그레이션을
> 강요하지 않기 위해서다. 경계는 [`../migration/frozen-names.md`](../migration/frozen-names.md) 이다.
> 여러분의 스토어 구현이 그 이름들을 쓰고 있다면 **그대로 두는 것이 맞다.**

### 4.4 스킬 정책과 보류 턴 레지스트리 (line 738-767)

스킬 호출 승인 흐름을 담당한다. CLI에서는 사용자가 인터랙티브 프롬프트로 승인/거부하지만, 자신의 앱에서는 정책으로 자동 결정하거나 외부 승인 시스템에 위임할 수 있다.

```java
final PendingTurnRegistry pendingTurnRegistry = new InMemoryPendingTurnRegistry();
final PendingTurnReaper pendingTurnReaper = createPendingTurnReaper(pendingTurnRegistry, outputFormatter);
// 승인은 두 스코프로 나뉜다 — 기본은 세션 단위이고, 사용자가 "이 에이전트에서 항상"이라고
// 명시한 답만 agent 단위 저장소로 간다. 정책 체인은 좁은 쪽(세션)을 먼저 본다.
final AgentApprovalStore agentApprovalStore = new InMemoryAgentApprovalStore();
final SessionApprovalStore sessionApprovalStore = new InMemorySessionApprovalStore();
// 번들(클래스패스) 스킬을 작업 VFS로 실체화해서 부속 파일(스크립트·레퍼런스·템플릿)이
// 에이전트가 읽을 수 있는 진짜 파일이 되고 ${AIMON_SKILL_DIR}가 resolve 되게 한다.
final SkillRegistry skillRegistry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(
        agentBundle, fileSystem, ".aimon/skills", ".aimon/bundled-skills",
        DEFAULT_AGENT_BUNDLE_BASE_PATH + "/" + extractAgentName(config) + "/skills",
        Thread.currentThread().getContextClassLoader(), skillParser);
final SkillInvocationPolicy skillInvocationPolicy =
        createSkillInvocationPolicy(sessionApprovalStore, agentApprovalStore);
final InteractiveSkillApprovalChannel skillApprovalChannel = new InteractiveSkillApprovalChannel(
        sessionApprovalStore, agentApprovalStore, outputFormatter);
final SkillPreflightScanner skillPreflightScanner = SkillPreflightScanner.builder()
        .policy(skillInvocationPolicy)
        .registry(skillRegistry)
        .approvalChannel(skillApprovalChannel)
        .build();
```

정책 체인은 `createSkillInvocationPolicy` (`AgentSetupFactory.java:998-1003`)가 조립한다. **순서가 계약이다**:

```java
return new SessionScopedSkillInvocationPolicy(sessionApprovalStore,          // 1. 세션 단위 (좁음)
        new ApprovalCachingSkillInvocationPolicy(agentApprovalStore,         // 2. 에이전트 전역
                RuleBasedSkillInvocationPolicy.builder()                     // 3. 규칙
                        .defaultDecision(SkillInvocationDecision.ASK).build()));
```

**좁은 스코프가 먼저**인 것은 취향이 아니라 유일하게 동작하는 순서다. 뒤집으면 이전에 준 에이전트 전역 허용이
먼저 답해버려서 "이 세션에서는 거부"가 영영 도달하지 못한다.

> **`SessionApprovalStore`라는 이름은 한 번 폐기됐다가 다른 뜻으로 재사용됐다.** 옛 코드/옛 문서에서 이 이름은
> `AgentRuntimeId`로 키잉되는 **에이전트 전역** 저장소를 가리켰고(이름이 거짓말을 하고 있었다), 그것은 지금
> `AgentApprovalStore` (`…skill.policy.agent`)다. **지금의 `SessionApprovalStore` (`…skill.policy.session`)는
> `SessionId`로 키잉되는 세션 단위 저장소**이며 옛 `ConversationApprovalStore`의 후신이다. 이 파일에서 가장
> 뒤집기 쉬운 지점이므로 대응표는 [scope-model.md §6](../overview/scope-model.md)을 본다. 승인의 **의미는 하나도 바뀌지
> 않았다** — 에이전트 전역 결정은 여전히 TTL이 없고 `/clear`로 지워지지 않는다.

**적응 포인트:**
- 헤드리스 환경(배치, 웹 API): `RuleBasedSkillInvocationPolicy.builder().defaultDecision(SkillInvocationDecision.ALLOW)` 등으로 자동 허용/거부.
- 외부 승인 시스템: `SkillApprovalChannel`을 직접 구현해서 Slack/이메일/대시보드로 승인 요청을 보낸다.
- 스킬 부속 파일이 필요 없다면 실체화 없는 `buildSkillRegistry(...)` 오버로드로 충분하다.

### 4.5 AgentExecutor 생성 (line 806-808)

```java
final OrcaAgentExecutor agentExecutor = createAgentExecutor(
        effectiveLlmClient, transcriptManager, messageQueueManager,
        config.getCliSettings().isStreaming(),
        skillPreflightScanner, pendingTurnRegistry, memoryContextProvider);
```

`OrcaAgentExecutorFactory`가 ReAct 루프 실행기를 만들어준다 — 최종 호출은
`create(llmClient, transcriptManager)`이고, 나머지는 전부 그 앞의 `with*` 세터다. 이 인스턴스도
**애플리케이션 스코프**다 — 모든 세션이 공유한다.

팩토리에는 `create()` 이전에 이미 몇 가지가 얹혀 있다 (line 795-804): `withRewakeService`,
`withSubagentBehaviorRegistry`, `withCostEstimator`, 그리고 트레이싱이 켜져 있으면
`withTracer` / `withTracePayloadPolicy`. **`with*`는 팩토리를 변조하고 자기 자신을 돌려주므로**,
같은 팩토리 인스턴스로 실행기를 두 번 만들면 두 번째가 첫 번째의 설정을 물려받는다. 실행기를 여러 개
만들 계획이면 팩토리도 따로 만든다.

### 4.6 SchedulingEngine 생성 (line 809, 862)

```java
final SchedulingEngine schedulingEngine = createSchedulingEngine(agentRuntimeRegistry);
// ...
schedulingEngine.start();  // line 862 — runtime 등록 이후
```

```java
// AgentSetupFactory.java:1571
private static SchedulingEngine createSchedulingEngine(AgentRuntimeRegistry agentRuntimeRegistry) {
    return SchedulingEngineBuilder.create().agentRuntimeRegistry(agentRuntimeRegistry).build();
}
```

> **라이프사이클 규칙** ([scope-model.md §2](../overview/scope-model.md)): Scheduling 컴포넌트는 **애플리케이션 수준(long-lived)**이다. `AgentRuntime`가 소멸돼도 스케줄링 엔진은 유지되어야 한다. CLI는 프로세스 수명이 곧 세션 수명이라 `AgentSetup.close()`에서 함께 닫지만, **임베딩에서는 분리해야 한다.**

`SchedulingEngineBuilder.agentRuntimeRegistry(...)`의 파라미터에는 `@ExternallyManaged`가 붙어 있다 —
"빌려온 참조이고 엔진이 닫지 않는다"는 뜻이다. 이 애노테이션은 런타임 동작이 없는 문서용 마커지만,
규약상 **그 클래스가 닫으면 안 된다**는 표시다.

**적응 포인트:**
- 분산/클러스터 스케줄링이 필요하면 `aimon-scheduling-quartz`의 Quartz 기반 구현으로 교체.
- `AgentRuntimeRegistry`는 **반드시 외부에서 만들어 주입**한다 — 엔진이 소유하지 않는다.

### 4.7 AgentRuntime 조립 (line 833-860)

```java
final OrcaAgentRuntimeFactory agentRuntimeFactory =
    new OrcaAgentRuntimeFactory(
        "1.0.0",
        ".aimon/commands",
        ".aimon/agents",
        ".aimon/skills",
        createWikiKnowledgeStore(agentRuntimeRegistry, llmClient))
        .withSkillRegistry(skillRegistry)
        .withCodeSubagentRegistry(codeSubagentRegistry)
        .withPendingTurnRegistry(pendingTurnRegistry)
        .withAgentApprovalStore(agentApprovalStore)
        .withSessionApprovalStore(sessionApprovalStore)
        .withSkillInvocationPolicy(skillInvocationPolicy)
        .withToolContextEnrichers(toolContextEnrichers)
        .withRewakeService(rewakeService)
        .withWorkflowRunnerEnabled(enableWorkflow || enableWorkflowJs);

// AgentRuntimeId는 인자가 아니다 — createAgentRuntime 내부에서 agent로부터 유도된다.
final OrcaAgentRuntime agentRuntime = createAgentRuntime(
    agentRuntimeFactory, agentExecutor,
    schedulingEngine.getTaskManager(), agentBundle, fileSystem,
    config, graalJsEngines);

configureHooks(agentRuntime, outputFormatter);
final HookHotReloadBootstrap.Started hookHotReload = setupHookHotReload(...);
registerCliTools(agentRuntime, outputFormatter, ...);
configureSchedulingEventListener(schedulingEngine, config);
agentRuntimeRegistry.register(agentRuntime);
```

**`AgentRuntimeId`를 호출부에서 만들어 넘기지 않는다.** `createAgentRuntime`가 내부에서
`AgentRuntimeId.from(agentBundle.getAgent())`로 유도한다. 결정론적이라는 것이 핵심이다 —
`agent:<name>` 또는 `agent:<name>:<discriminator>` 형식으로 고정되므로, 원래 세션이 끝난 한참 뒤에
cron이 재발화해도 `ScheduledTask.boundRuntimeId`가 같은 runtime으로 resolve 된다. **`generate()` 같은
것은 존재하지 않는다** — 있었다면 바로 그 재발화가 깨졌을 것이다. 필요하면
`from(agent)` / `from(agent, discriminator)` / `fromName(name)` / `of(value)`를 쓴다.

`OrcaAgentRuntime`는 **agent 스코프** 객체다 — `(Agent, discriminator)` 당 하나이며, 그 agent에 대한 모든 세션이 공유한다. 세션마다 만들지 말 것. 안에 다음을 담는다:

- `Agent` 정의 + 시스템 프롬프트
- `ToolRegistry` (기본 도구 + CLI 도구)
- `SkillRegistry`
- `HookRegistry`
- `CommandRegistry`, `SubagentRegistry`
- `VirtualFileSystem`
- `McpClientManager` (MCP 서버가 설정돼 있으면)
- `KnowledgeStore` (Wiki 저장소)

기본 도구 제공자들은 `OrcaAgentRuntimeFactory.defaultToolProviders()`에서 가져온다 — `Read`, `Write`, `Edit`, `Bash`, `Grep`, `Glob`, `Todo`, `Subagent`, `Skill`, `Scheduling` 등.

**적응 포인트:**
- 자기 도구 추가: `agentRuntime.getToolRegistry().register(myTool)` (`registerCliTools` 패턴 참고).
- 자기 훅 추가: `agentRuntime.getHookRegistry().register(HookEventType.PRE_TOOL, hook)` (`configureHooks` 패턴 참고). 이벤트별 `register*` 메서드는 없고 타입 토큰 하나로 등록한다.
- 도구 일부 비활성화: 커스텀 `ToolProvider` 리스트를 `agentRuntimeFactory.create(...)`에 넘긴다.

### 4.8 LiveSession 생성 (line 865-866)

```java
final LiveSession liveSession = new DefaultLiveSession(
    sessionId,                       // SessionId.of("default") — line 773
    agentRuntime,
    agentExecutor,
    LiveSessionOptions.defaults(),
    messageQueueManager,
    null,                            // HookExecutionManager (OnSessionStart/End 훅) — CLI는 안 씀
    sessionRecordStore);             // 세션의 영속 side field가 사는 곳
```

`DefaultLiveSession`은 4·5·6·7-arg 생성자를 제공한다. 뒤쪽 세 개는 각각 `MessageQueueManager`(mid-turn 큐잉),
`HookExecutionManager`(세션 훅), `SessionRecordStore`(영속 side field 하이드레이션)를 켜는 스위치이고,
넘기지 않으면 그 기능만 꺼진다. 예를 들어 4-arg를 쓰면 `offerAsync`가 절대 큐잉하지 않고 항상
`SubmitOutcome.Kind.EXECUTED`를 돌려준다.

`LiveSession`이 외부에서 주로 마주칠 진입 API다. `submit(input)` 호출 한 번이 한 턴(여러 ReAct iteration 포함)을 실행한다. 자세한 사용법은 [agent-session-guide.md](../features/session/agent-session-guide.md) 참고.

> **`LiveSession`은 세션이 아니라 세션의 핸들이다.** 영속 애그리게이트는 `SessionId`로 식별되는
> `SessionRecord`이고, `LiveSession`은 그 세션에 대해 턴을 실행하는 **노드 로컬·일시적** 객체다.
> 관계는 1 : 0..N이다 — 한 세션은 살아 있는 핸들이 0개일 수도(아무도 대화 중이 아님), 시간에 걸쳐 여러 개가
> 순차적으로 서빙할 수도 있다(idle-TTL 축출, 프로세스 재시작, 노드 간 핸드오프). 이 구분이 §6의 전제다.

### 4.9 AgentSetup으로 묶어 반환 (line 880-889)

`AgentSetup`은 CLI 프로세스가 만든 모든 리소스의 핸들이다 — 라이브 세션만이 아니라 agent 스코프와 앱 스코프까지 함께 들고 있다(CLI는 프로세스 = 세션 1개라서 가능한 단순화다). `AutoCloseable`이므로 try-with-resources로 닫으면 다음 순서로 정리된다 (`AgentSetupFactory.java:320-428`):

```java
 1. memoryFinalDerivation.run()      // 트랜스크립트가 아직 살아 있을 때 마지막 파생 작업을 큐에 넣는다
 2. memoryQueue.stop()               // 진행 중인 파생 작업 드레인 (의존 저장소보다 먼저)
 3. dreamerSubsystem.close()
 4. memoryMaintenance.close()
 5. liveSession.close()              // 핸들 자원만 정리 — OrcaAgentRuntime은 닫지 않는다
 6. sessionCheckpoints.close()       // liveSession 이후 — 마지막 end-of-turn 저장이 이미 드레인된 뒤
 7. agentRuntime.close()             // 앱 종료이므로 여기서 agent 스코프 자원(MCP 등)을 푼다
 8. graalJsEngines.close()           // runtime 해체 뒤 — 스크립트가 반쯤 닫힌 엔진을 만나지 않도록
 9. agentRuntimeRegistry.unregister(agentRuntime.getId())
10. schedulingEngine.close()         // (CLI 한정 — 임베딩에서는 분리해야 함)
11. rewakeService.close()
12. pendingTurnReaper.close()
13. hookHotReload.close()            // skillHookShell보다 먼저 — reload 콜백이 셸을 쓴다
14. skillHookShell.close()
```

순서에 이유가 붙은 자리가 네 곳 있고, 전부 코드 주석에 근거가 남아 있다: **파생 작업 → 저장소**(2가 3·4보다
먼저), **라이브 세션 → 체크포인트 우편함**(5가 6보다 먼저), **runtime → GraalJS 엔진**(7이 8보다 먼저),
**훅 핫리로드 → 셸**(13이 14보다 먼저). 자기 셸에서 순서를 바꿀 때 이 네 쌍은 유지한다.

> **라이브 세션은 닫히지만 그 때문에 runtime이 닫히는 것은 아니다.** `liveSession.close()`가
> `OrcaAgentRuntime.close()`를 호출하면 안 된다 — 같은 agent의 다른 세션이 아직 그 runtime(MCP 서브프로세스,
> `KnowledgeStore`)을 쓰고 있을 수 있다. 위 목록에서 5와 7이 **따로 적혀 있는 것이 핵심**이다: runtime이
> 실제로 닫히는 것은 CLI가 프로세스 종료와 동시에 agent를 버리기 때문이지, 세션 종료의 결과가 아니다.
> 임베딩에서 runtime 해체는 앱 종료 또는 명시적 agent 제거 시 `OrcaAgentRuntimeManager.destroyRuntime`이 담당한다.

---

## 5. 구성 요소별 적응 가이드

| 컴포넌트 | aimon-cli의 선택 | 자신의 앱에서 흔한 대안 |
|---------|----------------|----------------------|
| `LlmClient` | OpenAI 또는 Anthropic SDK 래퍼 | 사내 LLM 게이트웨이를 감싼 자체 구현 |
| `VirtualFileSystem` | `LocalFileSystem` (jar 디렉터리 기준) | `GridFSFileSystem` / `S3FileSystem` / 사용자별 격리된 인스턴스 |
| `VirtualShell` | `LocalShell` | `aimon-sandbox-docker` 컨테이너 격리 셸 |
| `SessionRecordStore` | `InMemorySessionRecordStore` | `aimon-session-mongodb` / `-postgres` / `-redis` 영속 구현 |
| `TranscriptManager` | `DefaultTranscriptManager` (+ 백그라운드 체크포인트 우편함) | 대개 그대로 — 갈아끼울 것은 그 아래 `SessionRecordStore`다 |
| `MessageQueueManager` | in-memory | 분산 큐 백엔드 |
| `AgentBundleLoader` | `AdaptiveAgentBundleLoader` (클래스패스) | 코드/DB에서 만든 `AgentBundle` 직접 주입 |
| `SkillInvocationPolicy` | 인터랙티브 ASK 정책 | rule-based 자동 정책 또는 외부 승인 |
| `SchedulingEngine` | in-memory (디폴트) | `aimon-scheduling-quartz` (분산) |
| `KnowledgeStore` | `WikiKnowledgeStore` (파일 기반) | `aimon-knowledge-opensearch` (벡터 검색) |
| `HookRegistry` | `ToolCallDisplayHook`, `SubagentResultDisplayHook` | 메트릭/감사 로그/요청-응답 트레이싱 훅 |
| `Tool` 추가 | `ConsoleOutputTool` 등 | 자체 비즈니스 도구 (DB 조회, 사내 API 호출 등) |
| 인터랙션 루프 | `ReplSession` (JLine) | HTTP 핸들러 / WebSocket / 배치 잡 |

도구 작성은 [tool-development-guide.md](../features/tool/tool-development-guide.md), 훅 작성은 [hook-development-guide.md](../features/hook/hook-development-guide.md), LLM 어댑터 작성은 [llm-provider-development-guide.md](../features/llm/llm-provider-development-guide.md)를 따른다.

---

## 6. 라이프사이클과 스코프

`aimon-cli`는 단일 프로세스 = 단일 세션이라 모든 것을 한 번에 만들고 한 번에 닫는다. **임베딩에서는 네 스코프를 명확히 분리해야 한다** — CLI를 그대로 베끼면 agent 스코프가 라이브 세션 스코프로 접혀버린다.

전체 규범은 [scope-model.md](../overview/scope-model.md)에 있다. 아래는 CLI 코드에 대응시킨 요약이다.

### 애플리케이션 스코프 (프로세스 수명)

한 번 생성하고 모든 agent와 세션이 공유:

- `LlmClient`
- `OrcaAgentExecutor`
- `SchedulingEngine` + `ScheduledTaskManager`, `RoutineExecutor`
- `AgentRuntimeRegistry`
- `SessionRecordStore`, `SessionLeaseStore`, `TranscriptManager`
- `AgentBundleLoader`, `AgentBundle` (변하지 않는 정의라면)
- `MessageQueueManager` 인스턴스 풀

### Agent 스코프 (`(Agent, discriminator)` 수명)

**agent 당 한 번** 생성하고, 그 agent의 모든 세션이 공유한다. 세션이 끝나도 닫지 않는다:

- `OrcaAgentRuntime`
- 그 runtime의 `McpClientManager` 및 MCP 클라이언트 (서브프로세스는 세션보다 오래 산다)
- `KnowledgeStore` (agent 단위로 나눌 경우)
- runtime 별 `ToolRegistry` / `HookRegistry`
- `WorkflowRunner` (agent-scoped 변형 — `withWorkflowRunnerEnabled`로 켰을 때)

생성·조회는 `OrcaAgentRuntimeManager.getOrCreateRuntime(bundle, ...)`로 한다 — 이름이 말하듯 이미 있으면 재사용한다. 해체는 앱 종료 또는 명시적 agent 제거 시 `destroyRuntime`으로만.

> **`OrcaAgentRuntime.close()`는 `AgentScoped` 구현체를 스캔하지 않는다** — 하드코딩된 목록
> (`mcpClientManager`, `workflowRunner`, `ownedShell`)만 닫는다. 네이티브 자원(커넥션 풀, 워처 스레드)을 쥔
> agent 스코프 컴포넌트를 새로 얹는다면 그 목록에 직접 추가해야 한다. 마커 인터페이스는 문서일 뿐 자동
> 소멸이 아니다. `ownedShell`은 셋 중 유일하게 조건부다 — `withShell(...)`로 셸을 직접 준 어셈블리에서는
> null이고, 그때 셸을 닫는 것은 준 쪽의 몫이다.

### 세션 스코프 (`SessionId` 수명 — **영속**)

세션이 존재하는 동안 유지되고, 재시작·축출·노드 이동을 **넘어 살아남는다**:

- `SessionRecord` (메시지 이력)
- `SessionTotals`, `budgetOverride` — 레코드의 side field
- `SessionTranscript`

이 값들이 `LiveSession`이 아니라 레코드 쪽에 있는 것이 요점이다. 라이브 세션은 뒤의 두 개를
`SessionRecordStore.setTotalsAndBudgetOverride`로 한 쌍씩 되쓴다.

### 라이브 세션 스코프 (한 접속의 수명 — 노드 로컬)

핸들마다 생성, `close()`로 정리:

- `LiveSession`
- 메시지 큐 구독과 이벤트 publisher
- 그 핸들이 만든 턴 추적 상태

`LiveSession`은 **노드 로컬·일시적** 핸들이다. 한 세션(`SessionId`)은 살아 있는 핸들이 0개일 수도, 시간에 걸쳐 여러 개일 수도 있다(idle-TTL 축출, 재시작, 노드 이동). **재시작을 넘어 살아남아야 하는 값은 핸들이 아니라 `SessionRecord` 쪽에 둔다.**

> 새 타입 이름을 지을 때: 영속되어야 하면 `Session*` (`at.aimon.core.agent.session[.store|.transcript]`),
> 프로세스와 함께 사라져도 되면 `LiveSession*` (`at.aimon.core.agent.session`), 에이전트 단위로 한 번 모으면
> 되면 `Agent*` (`at.aimon.core.agent`). **맨 단어 `Session`과 `AgentSession`은 타입 이름으로 금지**되어 있고
> `SessionNamingArchitectureTest`가 빌드에서 막는다 — 그 두 이름이 정확히 위 두 수명을 서로 사칭하게 만들기
> 때문이다. 반면 "conversation"은 여전히 유효한 단어이며 **LLM과의 메시지 교환**을 뜻한다
> (`getConversationHistory()`, `/compact`의 "Conversation compacted"). 수명을 뜻하는 데 쓰지 않는다.

> **이름의 마지막 명사로 수명을 추론하지 말 것.** `*Store` / `*Registry` / `*Manager` / `*Factory`는 X를
> 관리하는 **컨테이너**이고, 컨테이너 자신의 수명은 X의 수명이 아니다 — `SessionRecordStore`는 항목이
> 세션 단위지만 인스턴스는 애플리케이션 스코프이고, `AgentRuntimeRegistry`도 마찬가지다. 판단은 이름이 아니라
> **무엇으로 키잉되는가**로 한다: `Map<AgentRuntimeId, _>`면 agent-scoped, `Map<SessionId, _>`면 session-scoped.

### 잘못된 패턴

```java
// 안 됨 (1): 라이브 세션 close()에서 SchedulingEngine까지 닫으면
//           다른 세션의 예약 작업이 모두 죽는다
try (AgentSetup setup = factory.create(config)) {
    // ...
}
// → setup.close() 안에서 schedulingEngine.close()가 호출됨 (CLI 가정)

// 안 됨 (2): 세션마다 runtime을 새로 만들고 세션 close()에서 닫기
OrcaAgentRuntime rt = factory.create(...);   // 세션마다 MCP 서브프로세스 재기동
liveSession.close();
rt.close();   // 같은 agent의 다른 세션이 쓰던 MCP/KnowledgeStore를 끊어버린다

// 안 됨 (3): 세션 누적치를 라이브 세션 안에 들고 있기
//           핸들이 축출되거나 노드가 바뀌면 조용히 사라진다. SessionRecord에 둔다.
```

### 임베딩 패턴

```java
// 애플리케이션 시작 시 한 번
SchedulingEngine engine = SchedulingEngineBuilder.create()
    .agentRuntimeRegistry(registry).build();
engine.start();
LlmClient llmClient = new OpenAILlmClient(openAiConfig);
OrcaAgentExecutor executor = ...;
SessionRecordStore sessionRecords = ...;   // 앱 스코프. 재시작을 넘어야 하면 영속 구현

// agent 당 한 번 (이미 있으면 재사용된다)
OrcaAgentRuntime runtime = runtimeManager.getOrCreateRuntime(agentBundle, ...);

// 접속마다 — runtime은 만들지도, 닫지도 않는다
LiveSession session = new DefaultLiveSession(
    SessionId.of(userId), runtime, executor, LiveSessionOptions.defaults(),
    queueManager, null, sessionRecords);
try {
    AgentExecutionResult result = session.submit(input);   // 동기 — 한 턴이 끝나면 돌아온다
} finally {
    session.close();              // 핸들 자원만 정리, runtime은 그대로 산다
}

// 애플리케이션 종료 시 한 번
runtimeManager.destroyRuntime(runtime.getId());   // 여기서 비로소 MCP/KnowledgeStore 해제
engine.close();
```

자세한 임베딩 패턴은 [embedding-agent-in-application.md](embedding-agent-in-application.md) 참고.

---

## 7. 최소 임베딩 예제

`aimon-cli`의 부트스트랩을 가장 단순한 형태로 압축한 예제. 실제로 동작하는 최소 코드.

```java
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliConfigLoader;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.cli.factory.AgentSetupFactory.AgentSetup;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.LiveSession;

public class MyEmbeddedAgent {
    public static void main(String[] args) throws Exception {
        // 1. 설정 로드 (자체 YAML 또는 코드 빌드)
        CliConfig config = new CliConfigLoader().loadDefault();

        // 2. AgentSetup 한 번만 생성 (애플리케이션 스코프)
        try (AgentSetup setup = new AgentSetupFactory().create(config)) {

            // 3. 라이브 세션을 통해 입력 제출.
            //    submit(...)은 동기다 — 한 턴이 끝날 때까지 블록하고 결과를 돌려준다.
            LiveSession session = setup.getLiveSession();
            AgentExecutionResult result =
                session.submit("Read the README.md and summarize it");

            // 4. 결과 사용
            if (result.isSuccess()) {
                System.out.println(result.getFinalAnswer());
            } else {
                System.err.println(result.getErrorMessage());
            }
        }
    }
}
```

이벤트를 받으면서 비동기로 돌리고 싶으면 `submitAsync(input, listener)`가
`CompletionStage<AgentExecutionResult>`를 돌려준다 — 위의 동기 `submit`과 짝을 이루는 쪽이 그것이다.

```java
CompletionStage<AgentExecutionResult> stage = session.submitAsync(
    "Read the README.md and summarize it",
    event -> System.out.println(event));   // 토큰 델타, 도구 호출, iteration 진행 ...
AgentExecutionResult result = stage.toCompletableFuture().get();
```

**더 적극적인 임베딩** (자기 컴포넌트로 갈아끼우기)이 필요하면 `AgentSetupFactory`의 `create()` 본문을 자기 컴포지션 루트로 옮겨 단계별로 자기 구현체를 주입한다. 위의 [4. AgentSetupFactory.create()를 한 줄씩](#4-agentsetupfactorycreate를-한-줄씩) 섹션을 그대로 참고하면 된다.

---

## 8. 웹 애플리케이션으로 옮기기

`aimon-cli`의 `AgentSetupFactory.create()`는 앱·agent·세션 스코프를 한 덩어리로 만든다 — 단일 사용자 / 단일 프로세스 가정이라 가능한 일이다. 웹에서는 이것들을 분리해서 **앱 스코프 빈은 한 번만, agent 스코프 runtime은 agent 당 한 번, 라이브 세션만 사용자 접속마다** 만들어야 한다. 이 섹션은 그 분리를 네 단계로 보여준다.

1. [컴포넌트 스코프 분리표](#81-컴포넌트-스코프-분리표)
2. [Spring Boot 컴포지션 루트](#82-spring-boot-컴포지션-루트)
3. [`LiveSession` ↔ HTTP/SSE 어댑터](#83-livesession--httpsse-어댑터)
4. [비대화형 스킬 승인 채널](#84-비대화형-스킬-승인-채널)

> 이 섹션은 **직접 조립**하는 경로를 보여준다. 멀티 노드 배포에서 세션 라우팅·리스·핸드오프까지 필요하면
> `aimon-session-routing`의 `SessionRouter`(`SessionRouter.builder()`)가 그 계층을 이미 구현해 두었다 —
> 운영 관점의 설정은 [web-session-deployment-guide.md](../features/session/web-session-deployment-guide.md)를 본다.

### 8.1 컴포넌트 스코프 분리표

| 컴포넌트 | 스코프 | 빈 종류 | 비고 |
|---------|-------|---------|------|
| `LlmClient` | 앱 | `@Bean(destroyMethod = "close")` | SDK 커넥션 풀이 안에 있음. 모든 사용자가 공유 |
| `OrcaAgentExecutor` | 앱 | `@Bean` 싱글톤 | stateless. 모든 세션이 공유 |
| `SchedulingEngine` | 앱 | `@Bean(initMethod = "start", destroyMethod = "close")` | **세션 close()와 절대 묶지 말 것** |
| `AgentRuntimeRegistry` | 앱 | `@Bean` 싱글톤 | `SchedulingEngine`이 lazy lookup용으로 사용 |
| `AgentBundleLoader`, `AgentBundle` | 앱 | `@Bean` 싱글톤 | 정의가 정적이면 한 번만 로드 |
| `PendingTurnReaper` | 앱 | `@Bean(initMethod = "start", destroyMethod = "close")` | 데몬 스레드 한 개로 충분 |
| `LocalShell` (skill 훅용) | 앱 | `@Bean(destroyMethod = "close")` | I/O 스레드풀 공유 |
| `SessionRecordStore` | 앱 | `@Bean` 싱글톤 | **항목은 세션 단위, 인스턴스는 앱 스코프.** 멀티 인스턴스에서는 Mongo/Postgres/Redis 영속 구현 필수 |
| `TranscriptManager` | 앱 | `@Bean` 싱글톤 | 위 저장소를 감싼 디폴트 구현 |
| `PendingTurnRegistry`, `AgentApprovalStore`, `SessionApprovalStore` | 앱 | `@Bean` 싱글톤 | 항목의 키는 각각 pending turn / `AgentRuntimeId` / `SessionId` 지만 **인스턴스는 앱 스코프**다. 클러스터에서 라우팅이 안 보장되면 분산 백엔드로 |
| **`OrcaAgentRuntime`** | **agent** | `OrcaAgentRuntimeManager.getOrCreateRuntime()` | `(Agent, discriminator)` 당 하나. MCP, KnowledgeStore 소유. **라이브 세션 close()로 닫지 말 것** |
| **`OrcaAgentRuntimeManager`** | 앱 | `@Bean` 싱글톤 | agent 스코프 runtime의 생성·캐시·해체를 소유 |
| **`MessageQueueManager`** | **라이브 세션** | 팩토리에서 매번 생성 | producer(HTTP)와 consumer(executor)가 같은 인스턴스를 공유해야 함 |
| **`LiveSession`** | **라이브 세션** | 팩토리에서 매번 생성 | `SessionId` 당 동시에 0..1개, 시간에 걸쳐 N개 |
| **`VirtualFileSystem`** | **사용자/agent** | 사용자별 분리 권장 | GridFS bucket / S3 prefix 분리 |

> 가장 흔한 실수 네 가지:
> - `OrcaAgentExecutor`/`LlmClient`를 접속마다 새로 만든다 — 비싸고 의미 없다.
> - `MessageQueueManager`를 앱 스코프 싱글톤으로 만든다 — 다른 사용자의 mid-turn 입력이 새어들어간다.
> - **`OrcaAgentRuntime`을 라이브 세션 스코프로 만든다** — 접속마다 MCP 서브프로세스가 재기동되고, 핸들 하나가 닫힐 때 같은 agent의 다른 세션이 쓰던 MCP·KnowledgeStore가 함께 끊긴다. `VirtualFileSystem`을 사용자별로 나눠야 한다면 사용자를 discriminator로 넘겨 `getOrCreateRuntime(bundle, userId, fs, store)`를 쓴다 — 접속마다 새로 만드는 것이 아니라 사용자마다 하나다.
> - **재시작을 넘어야 하는 값을 `LiveSession` 안에 들고 있는다** — 누적 토큰/비용, 예산 오버라이드 같은 값은
>   `SessionRecordStore`의 레코드에 있어야 한다. 핸들 안에 두면 idle-TTL 축출 한 번에 조용히 사라진다.

### 8.2 Spring Boot 컴포지션 루트

#### 앱 스코프 — `@Configuration`

```java
@Configuration
public class AimonAppConfig {

    @Bean(destroyMethod = "close")
    public LlmClient llmClient(@Value("${aimon.openai.key}") String apiKey,
                               @Value("${aimon.openai.model:gpt-5.1}") String model) {
        return new OpenAILlmClient(OpenAIConfig.builder()
            .apiKey(apiKey)
            .model(model)
            .timeout(Duration.ofSeconds(60))
            .build());
    }

    @Bean
    public AgentRuntimeRegistry agentRuntimeRegistry() {
        return new DefaultAgentRuntimeRegistry();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public SchedulingEngine schedulingEngine(AgentRuntimeRegistry registry) {
        return SchedulingEngineBuilder.create().agentRuntimeRegistry(registry).build();
    }

    @Bean
    public SessionRecordStore sessionRecordStore() {
        // 멀티 인스턴스 환경에서는 aimon-session-mongodb / -postgres / -redis 구현으로 교체.
        // 인스턴스는 앱 스코프이고 항목만 SessionId로 갈린다.
        return new InMemorySessionRecordStore();
    }

    @Bean(destroyMethod = "close")
    public SessionCheckpointMailbox sessionCheckpoints() {
        // 턴 중간 크래시에도 append된 메시지를 잃지 않도록 자기 스레드에서 비동기로 흘려보낸다.
        return SessionCheckpointMailbox.background();
    }

    @Bean
    public TranscriptManager transcriptManager(SessionRecordStore store,
                                               SessionCheckpointMailbox checkpoints) {
        return new DefaultTranscriptManager(store, checkpoints);
    }

    @Bean
    public OrcaAgentExecutor agentExecutor(LlmClient llmClient,
                                           TranscriptManager transcriptManager) {
        return new OrcaAgentExecutorFactory()
            .withUseStreaming(true)
            .create(llmClient, transcriptManager);
    }

    @Bean(destroyMethod = "close")
    public VirtualShell skillHookShell() {
        return new LocalShell();
    }

    @Bean
    public SkillParser skillParser(VirtualShell skillHookShell) {
        return new MarkdownSkillParser(
            new ShellArgumentTokenizer(),
            new SkillHookSetParser(new DefaultShellActionExecutor(skillHookShell)));
    }

    @Bean
    public AgentBundle defaultAgentBundle(SkillParser skillParser) {
        return new AdaptiveAgentBundleLoader(
            "agents", new MarkdownAgentDefinitionParser(),
            getClass().getClassLoader(), skillParser).load("default");
    }

    @Bean
    public PendingTurnRegistry pendingTurnRegistry() {
        return new InMemoryPendingTurnRegistry();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public PendingTurnReaper pendingTurnReaper(PendingTurnRegistry registry) {
        return PendingTurnReaper.builder()
            .registry(registry)
            .interval(Duration.ofSeconds(60))
            .expirationListener(turns -> { /* 메트릭/로그 */ })
            .build();
    }

    @Bean
    public SessionApprovalStore sessionApprovalStore() {
        // at.aimon.core.skill.policy.session — SessionId로 키잉되는 좁은 쪽
        return new InMemorySessionApprovalStore();
    }

    @Bean
    public AgentApprovalStore agentApprovalStore() {
        // at.aimon.core.skill.policy.agent — AgentRuntimeId로 키잉되는 넓은 쪽
        return new InMemoryAgentApprovalStore();
    }

    @Bean
    public SkillInvocationPolicy skillInvocationPolicy(
            SessionApprovalStore sessionApprovals, AgentApprovalStore agentApprovals) {
        // 8.4 참고 — 자동 정책 또는 ASK + suspend/resume.
        // 좁은 것부터: 세션 승인 → 에이전트 전역 승인 → 규칙. 이 순서를 뒤집으면
        // 세션 단위 거부가 영영 도달하지 못한다.
        return new SessionScopedSkillInvocationPolicy(sessionApprovals,
            new ApprovalCachingSkillInvocationPolicy(agentApprovals,
                RuleBasedSkillInvocationPolicy.builder()
                    .defaultDecision(SkillInvocationDecision.ASK).build()));
    }
}
```

#### Agent 스코프 runtime — 사용자마다 한 번 (요청마다가 아니다)

`OrcaAgentRuntimeManager`는 앱 스코프 싱글톤 빈이다. `getOrCreateRuntime()`은 이름 그대로 캐시 조회 후 없을 때만 생성하며, 레지스트리 등록까지 내부에서 처리한다 — 호출부가 `register()`를 따로 부를 필요가 없다. 사용자별 VFS 분리가 필요하면 **접속마다 새 runtime을 만드는 것이 아니라 userId를 discriminator로** 넘긴다.

```java
@Bean
public OrcaAgentRuntimeManager agentRuntimeManager(
        OrcaAgentExecutor executor, AgentRuntimeRegistry registry,
        SchedulingEngine schedulingEngine,
        SkillInvocationPolicy skillPolicy, SessionApprovalStore sessionApprovals,
        AgentApprovalStore agentApprovals, PendingTurnRegistry pendingTurnRegistry) {

    // withSkillRegistry()는 일부러 부르지 않는다 — 사용자마다 VFS가 다르므로 스킬 레지스트리도
    // runtime 별로 달라야 한다. 생략하면 팩토리가 (agentBundle, fileSystem)에서 runtime마다 새로 만든다.
    OrcaAgentRuntimeFactory runtimeFactory =
        new OrcaAgentRuntimeFactory("1.0.0",
            ".aimon/commands", ".aimon/agents", ".aimon/skills",
            /* knowledgeStore */ null)
            .withSessionApprovalStore(sessionApprovals)
            .withAgentApprovalStore(agentApprovals)
            .withPendingTurnRegistry(pendingTurnRegistry)
            .withSkillInvocationPolicy(skillPolicy);

    return OrcaAgentRuntimeManager.builder()
        .agentExecutor(executor)
        .agentRuntimeRegistry(registry)
        .agentRuntimeFactory(runtimeFactory)
        .scheduledTaskManager(schedulingEngine.getTaskManager())
        .toolProviders(OrcaAgentRuntimeFactory.defaultToolProviders())
        .commandProviders(OrcaAgentRuntimeFactory.defaultCommandProviders())
        .build();
}
```

#### 라이브 세션 팩토리 — 접속마다 새 핸들 (runtime은 재사용)

```java
@Component
public class WebLiveSessionOpener {

    private final OrcaAgentExecutor executor;
    private final OrcaAgentRuntimeManager runtimeManager;
    private final AgentBundle agentBundle;
    private final CredentialStore credentialStore;
    private final SessionRecordStore sessionRecords;   // 앱 스코프 — 주입만 받는다
    private final VirtualFileSystemProvider fsProvider;

    // 생성자 주입 생략

    public LiveSession openFor(String userId, SessionId sessionId) {
        VirtualFileSystem userFs = fsProvider.forUser(userId);  // 사용자별 GridFS bucket / S3 prefix

        // agent 스코프: userId 당 한 번만 실제로 생성된다. 두 번째 세션부터는 캐시된 인스턴스가
        // 그대로 반환되므로 MCP 서브프로세스도 재기동되지 않는다.
        OrcaAgentRuntime runtime =
            runtimeManager.getOrCreateRuntime(agentBundle, userId, userFs, credentialStore);

        // 라이브 세션 스코프: 핸들마다 새로. producer(HTTP)와 consumer(executor)가 같은 인스턴스를 봐야 한다.
        MessageQueueManager queueManager = new DefaultMessageQueueManager(
            new InMemoryMessageQueueRepository());

        // 마지막 인자가 SessionRecordStore 다. 넘기면 세션 누적치(SessionTotals)와 budgetOverride 를
        // 열 때 복원하고 턴이 끝날 때마다 되쓴다. 넘기지 않으면(null) 이 핸들이 닫히는 순간
        // 그 값들은 사라진다 — 재시작·축출을 넘겨야 하는 값이므로 웹에서는 반드시 넘긴다.
        return new DefaultLiveSession(sessionId, runtime, executor,
            LiveSessionOptions.defaults(), queueManager, /* hookExecutionManager */ null, sessionRecords);
    }

    public void close(LiveSession session) {
        session.close();   // 라이브 세션 스코프만 정리한다
        // runtime은 unregister/close 하지 않는다 — 같은 사용자의 다른 세션이 아직 쓰고 있다.
        // SchedulingEngine도 절대 닫지 않는다 — @PreDestroy가 책임.
    }
}
```

> 사용자가 로그아웃하거나 유휴 사용자를 회수할 때에만 `runtimeManager.destroyRuntime(AgentRuntimeId.from(agent, userId))`를 호출한다. 그 사용자의 **모든** 라이브 세션이 닫힌 뒤여야 한다. 여기서 id를 `AgentRuntimeId.from(agent, userId)`로 **다시 계산해서** 넘길 수 있는 이유는 그 발급이 결정론적이기 때문이다 — `generate()` 같은 것은 없다.

### 8.3 `LiveSession` ↔ HTTP/SSE 어댑터

웹 클라이언트는 메시지 한 번에 — 토큰 델타, Tool 호출, iteration 진행, 완료 — 여러 이벤트를 받아야 한다. `LiveSession.submitAsync(input, listener)`가 이걸 정확히 지원한다 (`LiveSession.java:162`).

#### 라이브 세션 보관소

```java
@Component
public class LiveSessionRegistry {

    private final Map<SessionId, LiveSession> live = new ConcurrentHashMap<>();
    private final WebLiveSessionOpener opener;

    public LiveSession getOrOpen(String userId, SessionId sessionId) {
        return live.computeIfAbsent(sessionId, id -> opener.openFor(userId, id));
    }

    public Optional<LiveSession> peek(SessionId sessionId) {
        return Optional.ofNullable(live.get(sessionId));
    }

    public void close(SessionId sessionId) {
        LiveSession session = live.remove(sessionId);
        if (session != null) {
            opener.close(session);   // 핸들만 닫힌다. runtime도 세션 레코드도 살아 있다
        }
    }
}
```

> 운영에서는 TTL eviction(예: 30분 idle), 사용자당 최대 핸들 수, 인스턴스 셧다운 시 일괄 close 같은 정책을 추가한다. Caffeine/Guava cache의 removalListener에 `opener.close()`를 묶는 패턴이 흔하다. **이 축출 정책은 라이브 세션에만 적용된다** — 핸들이 TTL로 사라져도 agent 스코프 runtime은 그대로 남아, 같은 사용자가 다시 접속하면 MCP 재기동 없이 즉시 이어간다. runtime 회수가 필요하면 별도의(훨씬 긴) 유휴 정책으로 `destroyRuntime`을 건다.

> 이 맵의 키가 `SessionId`인데 값의 수명은 그보다 짧다는 점이 이 절 전체의 요지다. **한 `SessionId`에 대해 살아 있는 핸들은 동시에 0..1개, 시간에 걸쳐서는 N개**다 — 축출·재접속·프로세스 재시작마다 새 핸들이 같은 세션을 이어받는다. 따라서 누적 토큰·비용이나 예산 오버라이드처럼 그 이어받기를 넘어야 하는 값은 이 맵이 아니라 `SessionRecordStore`의 레코드에 있어야 한다(8.2의 `WebLiveSessionOpener`가 마지막 인자로 넘기는 그 저장소다).

#### SSE 컨트롤러 (Spring WebMVC)

```java
@RestController
@RequestMapping("/agent/sessions/{sessionId}")
public class AgentChatController {

    private final LiveSessionRegistry sessions;

    @PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable String sessionId,
                                  @AuthenticationPrincipal Principal user,
                                  @RequestBody MessageRequest body) {

        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        LiveSession session = sessions.getOrOpen(user.getName(), SessionId.of(sessionId));

        // offerAsync는 세션이 이미 turn을 돌리고 있으면 mid-turn 큐에 쌓는다
        SubmitOutcome outcome = session.offerAsync(body.text(), event -> {
            try {
                emitter.send(SseEmitter.event()
                    .name(event.getClass().getSimpleName())
                    .data(EventDto.from(event)));   // 자기 DTO로 직렬화
            } catch (IOException ignored) {
                // 클라이언트가 끊으면 다음 이벤트에서 다시 실패함 — 그때 cleanup
            }
        });

        if (outcome.getKind() == SubmitOutcome.Kind.QUEUED) {
            try {
                emitter.send(SseEmitter.event().name("queued")
                    .data("Session busy, queued at position " + outcome.getQueuePosition()));
            } catch (IOException ignored) {}
        }

        // 지금 시작된 턴의 id를 클라이언트에 흘려보낸다 — 아래 /interrupt가 이걸 되돌려 받는다.
        // EXECUTED 일 때만이다: QUEUED 였다면 지금 돌고 있는 턴은 남의 턴이고, 그 id를 이 응답의
        // 턴이라고 알려주면 사용자가 "중지"를 눌렀을 때 무고한 턴을 끊게 된다.
        // 이것도 best-effort 값이므로(§동시성 주의) 비어 있으면 그냥 보내지 않는다.
        if (outcome.getKind() == SubmitOutcome.Kind.EXECUTED) {
            session.currentTurnId().ifPresent(turnId -> {
                try {
                    emitter.send(SseEmitter.event().name("turn").data(turnId.value()));
                } catch (IOException ignored) {}
            });
        }

        // getResultStage()는 Optional 이다 — QUEUED 결과에는 아직 붙일 스테이지가 없다.
        // 큐에 쌓인 입력의 결과는 세션이 그 턴을 실제로 시작할 때 이벤트 스트림으로 온다.
        outcome.getResultStage().ifPresent(stage -> stage.whenComplete((result, ex) -> {
            try {
                if (ex != null) {
                    emitter.completeWithError(ex);
                } else {
                    emitter.send(SseEmitter.event().name("done")
                        .data(ResultDto.from(result)));
                    emitter.complete();
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }));

        return emitter;
    }

    @PostMapping("/interrupt")
    public ResponseEntity<Void> interrupt(@PathVariable String sessionId,
                                          @RequestBody InterruptRequest body) {
        // 주소 지정 형태를 쓴다. 무인자 interrupt(reason)는 "그 순간 돌고 있는 턴"을 끊으므로
        // 축출/셧다운/리스 상실 같은 관리 목적에는 맞지만, "내가 보낸 턴을 멈춰줘"에는 틀리다 —
        // 사용자의 클릭이 도착하기 전에 그 턴이 끝나고 다음 턴이 시작됐을 수 있다.
        // turnId를 넘기면 불일치가 조용한 no-op이 되어 무고한 턴을 끊지 않는다.
        sessions.peek(SessionId.of(sessionId)).ifPresent(session ->
            session.interrupt(TurnId.of(body.turnId()), InterruptReason.USER_SIGINT));
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> close(@PathVariable String sessionId) {
        sessions.close(SessionId.of(sessionId));   // 핸들만 닫는다 — 세션 레코드는 남는다
        return ResponseEntity.noContent().build();
    }
}
```

> `InterruptReason`은 **enum**이다 — 자유 문자열 사유를 담는 팩토리는 없다. 웹의 "중지" 버튼은 `USER_SIGINT`("SIGINT on the CLI host, **or equivalent**")가 맞고, 관리 목적에는 `SESSION_RELEASED` / `SYSTEM_SHUTDOWN` / `LEASE_LOST` / `HOLDER_LOST`가 따로 있다. 인터럽트가 실제로 꽂혔는지는 이 호출로 알 수 없다(본질적으로 racy) — 턴의 종료 이벤트로 관찰한다.

#### 동시성 주의

- `LiveSession`은 **스레드 안전 보장이 없다** (`LiveSession.java:42-47`). 한 핸들은 한 번에 한 턴만 실행해야 한다.
- 같은 `SessionId`에 동시 요청이 들어오면 `submitAsync` 대신 **`offerAsync`를 써서 mid-turn 큐에 쌓이게** 한다. `SubmitOutcome.getKind()`로 즉시 실행(`EXECUTED`)/대기(`QUEUED`)를 확인한다.
- **`status()`와 `currentTurnId()`는 제어 게이트가 아니다.** 둘 다 동기화 없이 읽는 best-effort 관찰값이라 정착 중인 턴에 대해 잠깐 어긋날 수 있다. "턴을 시작해도 되는가"는 `offerAsync`가 돌려주는 `SubmitOutcome`으로만 판단한다.
- 인터럽트는 동기 호출이 아니다 — 한 턴에 대해 **첫 트립만 의미가 있고**(이후 호출은 멱등 no-op), 실제 중단은 다음 ReAct iteration 또는 도구 종료 시점에 반영된다. 활성 턴이 없으면 조용한 no-op이며 예외를 던지지 않는다.
- 멀티 인스턴스에서는 두 가지 길이 있다:
  - **세션 어피니티 라우팅**: `SessionId` → 인스턴스 매핑 (sticky session, gateway 라우팅 룰). 리스·핸드오프까지 필요하면 직접 짜지 말고 `SessionRouter`를 쓴다.
  - **핸들 stateless화**: `LiveSession`을 매 요청마다 재생성하고 모든 상태를 `SessionRecordStore`에 넘긴다. 단, mid-turn 인터럽트/큐잉 같은 인메모리 상태가 사라지므로 trade-off 분명히.

### 8.4 비대화형 스킬 승인 채널

`SkillApprovalChannel`은 **동기 인터페이스**다 (`SkillApprovalChannel.java` 의 "Stay synchronous" 계약):

> "Stay synchronous. The scanner blocks on this call. Implementations that genuinely need async resolution should not implement this interface; they should let the suspend/resume path run instead."

웹 환경에서는 이 동기 계약 때문에 두 가지 길로 갈라진다.

#### 옵션 A — 룰 기반 자동 결정 (자동화 워크플로)

자체 정책으로 즉시 결정 가능하면 동기 채널이 깔끔하다.

```java
public class PolicyBasedApprovalChannel implements SkillApprovalChannel {

    private final SessionApprovalStore sessionApprovals;   // 좁은 쪽 — SessionId 로 키잉
    private final AgentApprovalStore agentApprovals;       // 넓은 쪽 — AgentRuntimeId 로 키잉
    private final SkillPolicyEvaluator evaluator;

    // 2-arg 가 인터페이스의 추상 메서드다. 세션을 아는 구현은 3-arg 쪽을 override 하고,
    // 2-arg 는 "세션이 없는 호출"로 위임만 시킨다.
    @Override
    public void requestApproval(List<PendingSkillRequest> pendingRequests,
                                AgentRuntimeId agentRuntimeId) {
        requestApproval(pendingRequests, agentRuntimeId, null);
    }

    @Override
    public void requestApproval(List<PendingSkillRequest> pendingRequests,
                                AgentRuntimeId agentRuntimeId, SessionId sessionId) {
        for (PendingSkillRequest req : pendingRequests) {
            // 절대 throw 금지 — 실패 시 안전 기본값(DENY)으로 기록 (SkillApprovalChannel 의 "Never throw" 계약)
            SkillInvocationDecision decision;
            try {
                decision = evaluator.evaluate(req.getSkillName(), req.getArgs());
            } catch (Exception e) {
                decision = SkillInvocationDecision.DENY;
            }
            // 스캐너는 채널의 반환값을 읽지 않는다. 반드시 정책 체인이 읽는 저장소에 써야 하고,
            // 쓰지 않은 스킬은 다음 체크에서 그냥 다시 ASK 가 된다.
            if (sessionId != null) {
                sessionApprovals.put(sessionId, req.getSkillName(), decision);
            } else {
                // sessionId 가 null 인 호출이 실제로 있다 — 스케줄 태스크처럼 사용자가 시킨 턴이 아닌 실행.
                // 이때 조용히 버리면 안 된다. 넓은 쪽에라도 기록한다.
                agentApprovals.put(agentRuntimeId, req.getSkillName(), decision);
            }
        }
    }
}
```

> **IMPORTANT — `AgentApprovalStore` 에 넣은 승인은 만료되지 않는다**: 키가
> `AgentRuntimeId` (`agent:<name>[:<discriminator>]`) 이므로 여기에 기록한 결정은 **그 에이전트의 이후 모든
> 세션에 그대로 적용되고**, TTL 이 없으며 `/clear` 로도 지워지지 않는다. 이 저장소는
> 사용자가 "이 에이전트에서 항상 허용"이라고 **명시적으로 답한 경우**에만 쓴다 — 사용자는 자기 답이 닿을
> 다른 세션들을 볼 수 없으므로, 평범한 "예" 를 이 스코프로 승격시켜서는 안 된다. "이번 세션에서만 허용"에는
> 세션 단위인 `SessionApprovalStore` (`at.aimon.core.skill.policy.session`) 를 쓰고,
> `SessionScopedSkillInvocationPolicy` 로 감싸면 정책 체인이 그쪽을 먼저 본다 (8.2 의 체인 참고).
> 되돌리는 경로는 각 저장소의 `invalidate(...)` 이며, CLI 는 이를 `/revoke` (세션) 와
> `/revoke --agent` (에이전트 전역) 로 노출한다 — 웹 UI 를 만든다면 동등한 취소 버튼을 반드시 함께 제공해야 한다.
>
> 세션 단위 승인의 도달 범위는 **그 세션과 그 세션이 위임한 실행**(서브에이전트 포크·스킬 포크·포그라운드
> 워크플로)이다. 다만 그 도달 방식을 오해하기 쉽다 — **포크는 자기 `SessionId` 를 갖지 않는다.**
> `DefaultSubagentExecutor` 는 툴 컨텍스트에 `SESSION_ID` 를 아예 넣지 않고, 실행 정체성인 `ExecutionId` 를
> `EXECUTION_ID` 로, 그리고 자기를 띄운 **사용자 세션의** id 를 `INVOKING_SESSION_ID` 로 공개한다. 정책은
> 후자로 답을 찾는다. 포크가 다시 포크를 띄워도 중간 포크가 아니라 사용자의 세션 id 가 그대로 전달된다.
> 포크는 사람에게 물을 채널이 없으므로 — 채널이 포크에서 도달 가능해서도 안 된다, 사용자가 그 화면을 보고
> 있지 않다 — 이 경로가 없으면 포크의 스킬 호출이 전부 막힌다.
>
> 두 id 는 축이 다르다: `sessionId` 는 *수명*(내 세션이 무엇인가), `invokingSessionId` 는 *도달 범위*(누구의
> 결정이 나에게 적용되는가). 그리고 **와이어 키는 여전히 `"conversationId"` / `"invokingConversationId"` 다** —
> Java 식별자만 개명됐고 직렬화 이름은 호환성을 위해 의도적으로 동결되어 있다. 저장된 이름과 타입 이름이
> 어긋나 보이는 것이 정상이다.
>
> **이름이 재사용된 자리라 특히 주의할 것**: `SessionApprovalStore` 라는 이름은 예전에 **에이전트 전역**
> 저장소의 이름이었다(키가 `AgentRuntimeId` 인데 이름이 세션을 말하고 있었다). 그것은 지금
> `AgentApprovalStore` 이고, 비어 있던 그 이름은 **진짜 세션 단위 저장소**에 다시 붙었다. 옛 코드나 옛 문서에서
> `SessionApprovalStore` 를 봤다면 지금의 `AgentApprovalStore` 일 수 있다 — 패키지(`…policy.agent` vs
> `…policy.session`)와 키 타입으로 구분한다. 옛 `ConversationApprovalStore` 가 지금의 `SessionApprovalStore`,
> 옛 `ConversationAwareSkillInvocationPolicy` 가 지금의 `SessionScopedSkillInvocationPolicy` 다.

규칙이 단순하면 채널 자체를 두지 않고 `RuleBasedSkillInvocationPolicy`만으로 끝나는 경우가 많다 — 정책이 ASK 대신 ALLOW/DENY를 직접 반환하면 채널이 호출되지도 않는다. 룰은 임의 람다가 아니라 **스킬 이름 글로브 패턴**이며, 평가 순서는 deny → allow → safe-by-default → `defaultDecision` 이다.

```java
SkillInvocationPolicy autoPolicy = RuleBasedSkillInvocationPolicy.builder()
    .addDenyPattern("dangerous-*")          // 가장 높은 우선순위
    .addAllowPattern("report-*")
    .safeByDefault(false)                   // 디폴트는 true — 끄면 ALLOW 는 명시적 allow 패턴으로만
    .defaultDecision(SkillInvocationDecision.DENY)  // 아무 룰도 안 걸렸을 때. 디폴트도 DENY(fail-closed)
    .build();
```

> `defaultDecision` 의 기본값은 `DENY` 다 — CLI 가 `ASK` 를 쓰는 것은 대화형 셸에 물어볼 사람이 있기 때문이지 그것이 프레임워크 기본값이어서가 아니다. 무인 워크플로에서 `ASK` 로 두면 채널이 없는 한 옵션 B 의 suspend 경로로 떨어져 턴이 멈춘다.

#### 옵션 B — 외부 승인 (사람이 결정해야 하는 경우)

사람의 클릭이 필요한 흐름은 **동기 채널을 만들지 말고**, scanner의 fallback인 suspend/resume 경로를 쓴다. `aimon-cli`의 `/approve`, `/deny`, `/pending` 명령과 같은 메커니즘 — 단지 입력 채널이 터미널에서 HTTP로 바뀔 뿐이다.

흐름:

1. Scanner가 채널이 없으면 (또는 DENY로 폴백) → 턴이 `PendingTurnRegistry`에 등록되며 일시 중단
2. 클라이언트는 `events()` 스트림에서 보류 이벤트를 수신
3. 사용자가 승인/거절을 별도 UI에서 결정
4. 승인 결과를 백엔드 API로 전달
5. 컨트롤러는 결정을 **스코프에 맞는 저장소**(`SessionApprovalStore` 또는 `AgentApprovalStore`)에 기록한 뒤 `pendingTurnRegistry.remove(turnId)` 로 보류 항목 제거
6. 클라이언트가 같은 프롬프트를 다시 제출 → Scanner가 정책을 다시 묻고 이번엔 캐시된 ALLOW/DENY로 결정 → 턴 진행

> 5–6 단계 주의: `resume(turnId)` 같은 API 는 없다. `PendingTurnRegistry` 는 순수 저장소이고 실행을 재개시키지
> 않는다 — 승인 기록 + 항목 제거까지가 서버 몫이고, 실제 재실행은 클라이언트가 턴을 다시 제출해서 일어난다
> (CLI 의 `/approve` 도 "Resume the agent to continue" 라고 안내할 뿐 스스로 재개하지 않는다).

```java
@RestController
@RequestMapping("/agent/pending/{turnId}")
public class PendingApprovalController {

    private final PendingTurnRegistry pendingTurns;
    private final SessionApprovalStore sessionApprovals;
    private final AgentApprovalStore agentApprovals;

    @PostMapping("/decide")
    public ResponseEntity<Void> decide(@PathVariable String turnId,
                                       @RequestBody ApprovalRequest body) {
        PendingTurn pending = pendingTurns.get(PendingTurnId.of(turnId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // 보류 턴은 자기가 어느 세션의 턴이었는지 알고 있다 — 다만 Optional 이다.
        // 사용자가 시킨 턴이 아니면(스케줄 태스크 등) 비어 있다.
        Optional<SessionId> sessionId = pending.getSessionId();

        for (PendingSkillRequest req : pending.getPendingSkills()) {
            SkillInvocationDecision decision = body.allows(req.getSkillName())
                ? SkillInvocationDecision.ALLOW
                : SkillInvocationDecision.DENY;

            if (sessionId.isPresent() && !body.forWholeAgent()) {
                // 기본 경로. 이 세션과 이 세션이 위임한 실행에만 적용된다.
                sessionApprovals.put(sessionId.get(), req.getSkillName(), decision);
            } else {
                // 주의: 이 결정은 이 세션이 아니라 pending.getAgentRuntimeId() 전체에 남고 만료되지 않는다.
                // 사용자가 "이 에이전트에서 항상"이라고 명시적으로 답했을 때만 이 가지로 온다.
                agentApprovals.put(pending.getAgentRuntimeId(), req.getSkillName(), decision);
            }
        }
        pendingTurns.remove(PendingTurnId.of(turnId));
        return ResponseEntity.accepted().build();
    }
}
```

승인을 되돌리는 엔드포인트도 같이 열어 두는 것이 좋다 — CLI 의 `/revoke` 와 동등한 역할이다. **두 스코프 모두** 열어야 한다. 좁은 쪽만 열어 두면 사용자가 한 번 잘못 누른 에이전트 전역 승인을 되돌릴 방법이 UI 에 남지 않는다.

```java
@DeleteMapping("/agent/sessions/{sessionId}/approvals")
public ResponseEntity<Void> revokeSession(@PathVariable String sessionId) {
    sessionApprovals.invalidate(SessionId.of(sessionId));      // /revoke
    return ResponseEntity.noContent().build();
}

@DeleteMapping("/agent/{agentRuntimeId}/approvals")
public ResponseEntity<Void> revokeAgent(@PathVariable String agentRuntimeId) {
    agentApprovals.invalidate(AgentRuntimeId.of(agentRuntimeId));   // /revoke --agent
    return ResponseEntity.noContent().build();
}
```

> **TTL 주의**: `PendingTurnReaper`가 보류 턴을 일정 주기로 정리한다 (`AgentSetupFactory.createPendingTurnReaper`, 디폴트 60초 sweep). 클라이언트가 승인 UI를 너무 오래 띄워두면 턴이 expire되어 사용자 결정이 무시된다 — UX에서 카운트다운 또는 자동 거절을 알려야 한다. sweep 주기는 만료 여부가 아니라 **만료된 항목을 얼마나 빨리 걷어내는지**를 정한다 — 실제 만료 시각은 `PendingTurn.getExpiresAt()` 이다.

---

## 9. 기타 적응 시나리오

CLI/웹 가리지 않는 일반 패턴들.

### 9.1 자체 도구 등록

```java
public class CompanyDirectoryTool extends AbstractTool {
    public static final String TOOL_NAME = "CompanyDirectory";
    private final DirectoryService directory;

    public CompanyDirectoryTool(DirectoryService directory) {
        super(TOOL_NAME,
              "Look up an employee by email or employee ID.",
              createInputSchema());
        this.directory = Objects.requireNonNull(directory);
    }
    // ... execute 구현
}

// 등록
agentRuntime.getToolRegistry().register(new CompanyDirectoryTool(svc));
```

`AgentSetupFactory.registerCliTools()`와 동일한 패턴이다. `ToolRegistry`는 runtime 하나에 하나이므로 **등록도 runtime 생성 시 한 번**이다 — 접속마다 등록하면 같은 도구가 중복 등록된다. 웹에서는 `OrcaAgentRuntimeManager.builder().toolProviders(...)`에 `OrcaToolProvider`로 넘기는 것이 정석이다 — 그러면 매니저가 runtime을 만들 때마다 정확히 한 번 등록해준다. (훅은 같은 자리에서 `hookRegistrars(...)`로 넘긴다.)

### 9.2 감사 로그 훅

```java
// 이벤트 타입별 register* 메서드는 없다 — 타입 토큰 하나로 등록한다.
hookRegistry.register(HookEventType.PRE_TOOL, (PreToolHook) ctx -> {
    auditLog.info("invoker={} tool={} input={} attrs={}",
        ctx.getInvokerName(), ctx.getCurrentToolUse().getName(),
        ctx.getCurrentToolUse().getInput(), ctx.getExecutionAttributes());
    return HookResult.allow();
});
```

훅 종류와 차단 의미는 [hook-development-guide.md](../features/hook/hook-development-guide.md)를 따른다. **`PreToolHook`만 차단(`HookResult.block(reason)`) 의미가 있다** — 다른 훅은 비차단이다.

> 감사 로그에 사용자 신원을 남기려면 훅 컨텍스트에서 꺼낼 수 없다는 점에 주의한다 — `PreToolContext`에 `getUserId()` 같은 것은 없다. 제출 시점에 `SubmitOptions.builder().executionAttribute("userId", ...)` 로 실어 보내면 `getExecutionAttributes()` 로 그대로 도착한다. 훅 등록은 **agent 스코프 `HookRegistry`** 에 대해 일어나므로 이 역시 접속마다가 아니라 runtime 당 한 번이다 — 사용자 식별은 등록 시점이 아니라 제출 시점에 실려야 한다.

---

## 10. 체크리스트

새 호스트 애플리케이션에 `aimon-core`를 통합할 때 점검할 항목.

### 의존성
- [ ] `aimon-core`를 `implementation()`으로 추가했는가?
- [ ] 적어도 하나의 LLM 구현체 모듈을 추가했는가?
- [ ] 필요한 파일시스템/스케줄링/지식 모듈을 골라 추가했는가?

### 컴포지션
- [ ] `LlmClient`, `OrcaAgentExecutor`, `SchedulingEngine`을 **애플리케이션 스코프**로 만들었는가?
- [ ] `AgentRuntimeRegistry`를 외부에서 만들어 `SchedulingEngine`에 주입했는가?
- [ ] `OrcaAgentRuntime`을 **agent 스코프**로 두고 `OrcaAgentRuntimeManager.getOrCreateRuntime()`으로만 얻는가? (세션마다 새로 만들지 않는가?)
- [ ] `AgentRuntimeId`를 `from(agent)` / `from(agent, discriminator)`로 유도하는가? (`generate()`는 존재하지 않는다)
- [ ] 접속마다 새 `LiveSession`을 만들되, **같은 `SessionId`** 로 열어 이전 세션을 이어받는가?
- [ ] `LiveSession`에 `SessionRecordStore`를 넘겨, 누적치·예산 오버라이드가 핸들보다 오래 살아남는가?
- [ ] 같은 라이브 세션 안에서 `MessageQueueManager`가 **단일 인스턴스**인가?
- [ ] (웹 한정) `LlmClient`/`OrcaAgentExecutor`를 사용자 요청마다 새로 만들지 않는가?
- [ ] (웹 한정) `MessageQueueManager`를 앱 스코프 싱글톤으로 만들지 않는가?

### 라이프사이클
- [ ] 라이브 세션 종료 시 `liveSession.close()`만 부르고, **`AgentRuntime`은 닫지도 unregister 하지도 않는가**?
- [ ] **라이브 세션 종료에서 `SchedulingEngine`을 닫지 않는가**? (CLI와의 차이)
- [ ] `AgentRuntime` 해체를 앱 종료 또는 명시적 agent 제거 시 `destroyRuntime()`으로만 하는가?
- [ ] 애플리케이션 종료 훅에서 `SchedulingEngine`, `LlmClient`, 공유 `VirtualFileSystem`을 닫는가?
- [ ] agent-scoped 컴포넌트를 새로 추가했다면 `OrcaAgentRuntime.close()`의 **하드코딩된 목록에 직접 넣었는가**? (`AgentScoped` 마커는 문서일 뿐 fan-out 이 없다)
- [ ] (웹 한정) 라이브 세션 보관소가 idle TTL eviction을 가지고 있고, eviction 시 핸들만 닫는가? (runtime까지 닫지 않는가?)

### 도구/훅/스킬
- [ ] 자체 도구는 `AbstractTool` 규약(예외 안 던지고 `ToolResult.error()` 반환)을 지키는가?
- [ ] `PreToolHook` 외에는 차단을 시도하지 않는가?
- [ ] 헤드리스 환경이면 스킬 승인 정책이 자동 결정 또는 외부 승인 시스템에 위임되는가?
- [ ] 승인 결정을 기록할 때 **좁은 스코프(`SessionApprovalStore`)를 기본**으로 두고, 에이전트 전역은 사용자가 명시적으로 답했을 때만 쓰는가?
- [ ] 정책 체인이 **좁은 것부터**(세션 → 에이전트 → 규칙) 배치되어 있는가? 순서를 뒤집으면 세션 단위 거부가 도달하지 못한다
- [ ] 승인 취소 경로를 **두 스코프 모두** 열었는가? (`/revoke` 와 `/revoke --agent` 에 해당)
- [ ] (웹 한정) 사람의 승인이 필요한 스킬은 동기 채널 대신 suspend/resume + HTTP 결정 엔드포인트를 쓰는가?

### 동시성 / HTTP
- [ ] (웹 한정) 같은 `SessionId` 동시 요청을 `offerAsync`로 큐잉하거나 명시적으로 거절하는가?
- [ ] (웹 한정) `status()` / `currentTurnId()` 를 제어 게이트로 쓰지 않고 `SubmitOutcome` 으로 판단하는가?
- [ ] (웹 한정) SSE/WebSocket 스트림에서 클라이언트 끊김(`IOException`)을 감지해 cleanup 하는가?
- [ ] (웹 한정) 사용자발 인터럽트를 `interrupt(turnId, reason)` 로 **턴을 지정해서** 보내는가? (무인자 형태는 관리 목적용)
- [ ] (웹 한정) `interrupt()` 호출이 즉시 종료를 보장하지 않는다는 점을 클라이언트에 노출했는가?

### 멀티 인스턴스 (선택)
- [ ] in-memory 구현(`InMemorySessionRecordStore`, `InMemoryMessageQueueRepository`, `InMemoryPendingTurnRegistry`, `InMemoryAgentApprovalStore`, `InMemorySessionApprovalStore`)을 분산 백엔드로 교체했는가?
- [ ] `SchedulingEngine`이 클러스터링 가능한 구현(`aimon-scheduling-quartz`)을 쓰는가?
- [ ] (웹 한정) 세션 어피니티 라우팅을 쓰는가(`SessionRouter`), 아니면 `LiveSession`을 stateless로 매 요청마다 재구성하는가?

---

## 참고

- 코어 추상화 레퍼런스: [architecture.md](../overview/architecture.md)
- **수명·소유권·소멸 책임의 기준 문서**: [scope-model.md](../overview/scope-model.md) — 새 타입을 만들거나 `close()` 를 부르기 전에 본다
- 용어 사전: [glossary.md](../overview/glossary.md)
- SDK 임베딩 패턴 (스코프, 멀티세션, 스트리밍): [embedding-agent-in-application.md](embedding-agent-in-application.md)
- `LiveSession` API 와 이벤트 스트리밍: [agent-session-guide.md](../features/session/agent-session-guide.md)
- 멀티 노드 세션 라우팅·리스 운영: [web-session-deployment-guide.md](../features/session/web-session-deployment-guide.md)
- 도구 개발: [tool-development-guide.md](../features/tool/tool-development-guide.md)
- 훅 개발: [hook-development-guide.md](../features/hook/hook-development-guide.md)
- LLM 프로바이더 개발: [llm-provider-development-guide.md](../features/llm/llm-provider-development-guide.md)
- `aimon-cli` 진입점: `modules/aimon-cli/src/main/java/at/aimon/cli/AimonCli.java`
- `aimon-cli` 컴포지션 루트: `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java`
- `aimon-cli` LLM 팩토리: `modules/aimon-cli/src/main/java/at/aimon/cli/factory/LlmClientFactory.java`
- 기본 설정: `modules/aimon-cli/src/main/resources/default-config.yaml`
