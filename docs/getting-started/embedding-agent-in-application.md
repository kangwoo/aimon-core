# AIMON Agent 애플리케이션 임베딩 가이드

> 여러분의 애플리케이션(웹 서버 / 배치 / 백오피스 도구) 안에서 AIMON 에이전트를 직접 실행하는 방법.
> **기본 경로는 `aimon-spring-boot-starter` 입니다** — 프로퍼티 3개면 턴이 돕니다. 스타터를 쓸 수 없는
> 호스트를 위한 두 갈래는 [§14 Spring 이 아닌 호스트](#14-spring-이-아닌-호스트--aimonstack) 와
> [부록 A 수동 배선](#부록-a-수동-배선--스타터를-쓰지-않을-때) 에 있습니다.

> 본 가이드의 scope 모델은 [`docs/overview/scope-model.md`](../overview/scope-model.md) 를 따릅니다 —
> `AgentRuntime` 은 agent-scoped 이며 **여러 세션에 걸쳐 공유됩니다**. 설계 배경은
> [`agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md),
> 스타터 자체의 설계 배경은 [`spring-boot-starter.md`](../design/integration/spring-boot-starter.md) 입니다.

CLI 대화만 필요하다면 이 문서 대신 [agent-session-guide.md](../features/session/agent-session-guide.md)
(`LiveSession` API)를 읽으세요.

## 목차

1. [어느 길을 갈 것인가](#1-어느-길을-갈-것인가)
2. [5분 시작 — 스타터](#2-5분-시작--스타터)
3. [스타터가 조립해 주는 것](#3-스타터가-조립해-주는-것)
4. [프로퍼티](#4-프로퍼티)
5. [네 개의 스코프](#5-네-개의-스코프)
6. [턴 실행 — `AimonSessions`](#6-턴-실행--aimonsessions)
7. [스트리밍 이벤트 전달](#7-스트리밍-이벤트-전달)
8. [ExecutionBudget 정책](#8-executionbudget-정책)
9. [다중 에이전트와 테넌트](#9-다중-에이전트와-테넌트)
10. [갈아끼우기 — 확장점](#10-갈아끼우기--확장점)
11. [멀티 인스턴스](#11-멀티-인스턴스)
12. [Scheduling 라이프사이클 — 절대 건드리지 말 것](#12-scheduling-라이프사이클--절대-건드리지-말-것)
13. [관측과 로깅](#13-관측과-로깅)
14. [Spring 이 아닌 호스트 — `AimonStack`](#14-spring-이-아닌-호스트--aimonstack)
15. [임베딩 체크리스트](#15-임베딩-체크리스트)
- [부록 A. 수동 배선 — 스타터를 쓰지 않을 때](#부록-a-수동-배선--스타터를-쓰지-않을-때)
- [부록 B. 옛 이름 매핑](#부록-b-옛-이름-매핑)

---

## 1. 어느 길을 갈 것인가

임베딩에는 세 갈래가 있고, **위에서부터 시도하는 것이 맞습니다**. 아래로 갈수록 여러분이 직접 조립해야
하는 양이 늘어납니다.

| 길 | 언제 | 여러분이 직접 하는 일 |
|----|------|----------------------|
| **스타터** — `aimon-spring-boot-starter` | Spring Boot 3 애플리케이션 | 프로퍼티 3개 + `LlmClient` 자격증명. 나머지는 auto-configuration |
| **부트스트랩** — `aimon-bootstrap` | Spring 이 아닌 JVM 호스트 (Quarkus / Micronaut / plain `main` / 배치) | `AimonStackSpec` 을 손으로 만들고 `AimonStack` 을 닫는다 (§14) |
| **손 배선** — `aimon-core` 직접 | 조립의 형태 자체를 바꿔야 할 때 | 전부 — 실행자·레지스트리·팩토리·teardown 순서 (부록 A) |

두 번째와 세 번째의 차이는 **teardown 순서를 누가 아느냐**입니다. `AimonStack` 은 자기가 만든 것을
만든 역순으로 닫고, 빌려온 것은 닫지 않습니다. 손 배선은 그 순서를 여러분이 지켜야 합니다.

> **아직 Maven Central 에 없습니다.** `aimon-spring-boot-starter` 와 `aimon-bom` 은 v0.2.0 태그
> **이후에** 만들어졌으므로 현재 게시된 아티팩트에는 들어 있지 않고, 다음 릴리스부터 게시됩니다.
> 그때까지는 이 저장소를 체크아웃해 `./gradlew publishToMavenLocal` 로 설치한 뒤 `mavenLocal()` 에서
> 해석하세요.

---

## 2. 5분 시작 — 스타터

### 2.1 의존성

```kotlin
dependencies {
    // BOM 을 쓰면 아래 좌표들의 버전을 적지 않아도 된다.
    implementation(platform("at.aimon.core:aimon-bom:<version>"))

    implementation("at.aimon.core:aimon-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")

    // LLM 벤더 모듈은 **여러분이 고릅니다**. 스타터는 둘 다 compileOnly 로만 알고 있고,
    // 슬라이스가 @ConditionalOnClass 로 클래스패스에 있는 쪽만 배선합니다.
    implementation("at.aimon.core:aimon-llm-anthropic")   // 또는 aimon-llm-openai
}
```

`aimon-spring-boot-starter` 는 `aimon-core` / `aimon-bootstrap` / `aimon-session-routing` 를 `api` 로
재수출하므로, 여러분의 코드는 `AgentExecutionResult` · `SessionId` · `LiveSessionOptions` 를 별도 의존성
선언 없이 컴파일할 수 있습니다.

### 2.2 프로퍼티 3개

```yaml
aimon:
  workspace:
    root: /var/lib/aimon          # 에이전트가 읽고 쓸 작업 트리 (쓰기 가능해야 함)
  llm:
    api-key: ${ANTHROPIC_API_KEY}
  agent-defaults:
    default-agent: ops            # 클래스패스의 agents/ops/agent.md 를 읽는다
```

**이 세 개가 필수의 전부입니다.** `aimon.agents` 맵은 필요 없습니다 — 비어 있으면 스타터는
`AgentSpec.named(<default-agent>)` 하나를 만듭니다. "그 이름의 번들을, 더 할 말 없이 그대로" 라는 뜻입니다.
에이전트 정의는 프로퍼티가 아니라 클래스패스의 **마크다운 번들**(`agents/<bundle>/agent.md`)에서 옵니다.

### 2.3 빈 하나를 주입한다

```java
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AimonSessions sessions;

    public AgentController(AimonSessions sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        // SessionId 는 여러분의 도메인에서 옵니다 — (사용자, 스레드) → SessionId 매핑은
        // 프레임워크가 제공하지 않습니다. 같은 id 로 다시 부르면 대화가 이어집니다.
        AgentExecutionResult result = sessions.submit(SessionId.of(req.getThreadId()), req.getInput());

        // getFinalAnswer()/getErrorMessage() 는 Optional 이 아니라 nullable String 입니다.
        return result.isSuccess()
                ? ChatResponse.ok(result.getFinalAnswer())
                : ChatResponse.error(result.getErrorMessage());
    }
}
```

동작하는 전체 앱은 [`samples/aimon-sample-app`](../../samples/aimon-sample-app) 에 있습니다 — 자격증명
없이(`aimon.llm.provider: none` + 앱이 제공하는 `LlmClient` 빈) 실제 턴 1건을 돌리는 것이 그 샘플의 존재
이유이고, fat-jar 로 패키징한 뒤에도 같은 동작을 하는지를 `@Tag("packaging")` 테스트가 지킵니다.

### 2.4 지원하는 배포 형태 — 실행 가능 jar

**1차 릴리스가 지원하는 배포 형태는 실행 가능 jar 하나입니다.** 개발 시의 디렉토리(exploded)
클래스패스도 같이 지원합니다 — 그것이 없으면 IDE 에서 앱을 띄울 수 없기 때문입니다.

| 형태 | 지원 | 검증 |
|------|------|------|
| Spring Boot 실행 가능 jar — nested 로더 (`jar:nested:`, Boot 3.2+) | ✅ | `FatJarPackagingTest` 가 실제 JVM 을 띄워 확인 |
| Spring Boot 실행 가능 jar — classic 로더 (`jar:file:`) | ✅ | 같은 테스트가 같은 단언을 두 번째 jar 에 반복 |
| 디렉토리(exploded) 클래스패스 — 개발·IDE | ✅ | 같은 테스트가 세 번째 프로세스로 확인 |
| WAR 를 서블릿 컨테이너에 배치 | ❌ | — |
| `jlink` 런타임 이미지 (`jrt:`) | ❌ | — |
| GraalVM native 이미지 | ❌ | — |

지원 여부가 갈리는 지점은 **하나**입니다. 스킬·에이전트 번들은 클래스패스의 리소스 **디렉토리**이고,
JDK 에는 그것을 나열하는 이식 가능한 방법이 없습니다. `ClasspathResourceTreeWalker` 가 `file:` 은
디렉토리 순회로, `jar:` 은 `JarURLConnection` 을 통한 엔트리 열거로 나눠 처리하는 이유가 이것입니다.
컨테이너가 자기 프로토콜(`vfs:` · `wsjar:` 등)을 쓰거나 `jrt:` 로 들어오면 열거할 방법이 없고, native
이미지에는 열거할 클래스패스 자체가 없습니다.

**지원하지 않는 형태에서 어떻게 보이는지**를 알아 두는 편이 낫습니다 — 예외가 아니라 WARN 로그가
나가고 **스킬이 하나도 없는 에이전트**가 기동합니다. 기동은 성공하고 턴도 도는데 스킬만 없으므로,
프롬프트 문제로 오해하기 쉽습니다. 이 형태들 중 하나가 필요하다면 지금은 방법이 없다는 뜻이며, 로그의
그 줄이 유일한 단서입니다.

찍히는 줄은 둘이고, 둘 다 **파일이 없다고 말하지 않습니다**.

```text
WARN  ClasspathResourceTreeWalker - Cannot enumerate classpath resource 'agents/default/skills/commit':
      unsupported URL protocol 'vfs'
WARN  BundledSkillMaterializer   - Bundled skill 'commit' cannot be materialized from
      'agents/default/skills/commit': class path layout 'vfs' cannot be enumerated (its files may well be
      present). See the supported deployment shapes in docs/getting-started/embedding-agent-in-application.md §2.4
```

한동안 두 번째 줄은 `has no files under ...; skipping` 이었습니다 — walker 가 "걸을 수 없다" 와 "비어
있다" 를 같은 빈 리스트로 답했기 때문이고, 파일은 아카이브 안에 그대로 있는데 로그만 없다고 말했습니다.
지금은 `ResourceTreeListing` 이 두 사건을 나눠 나르므로 메시지가 사실과 어긋나지 않습니다.

> 이것은 "아직 안 해 봤다" 가 아니라 **범위 결정**입니다(2026-08-05). 요구가 실제로 생기면 다시 엽니다 —
> [`docs/backlog/spring-boot-starter-open-items.md`](../backlog/spring-boot-starter-open-items.md) 의
> B-19(범위 결정, 닫힘) · B-29(메시지 분리, 닫힘).

---

## 3. 스타터가 조립해 주는 것

### 3.1 여러분이 주입하는 빈

| 빈 | 무엇 |
|----|------|
| **`AimonSessions`** | 턴을 실행하는 파사드. **대부분의 앱은 이것 하나만 씁니다** (§6) |
| `AimonAgents` | 이 배포에 어떤 에이전트가 있는지, 테넌트 런타임을 어떻게 무효화하는지 (§9) |
| `AimonStack` | 그 아래 전부 — `sessionRouter()`, `agentExecutor()`, `sessionRecordStore()`, `agentRuntimes()`, `health()`, `degradations()` … |

`AimonStack` 은 `@Bean(destroyMethod = "close")` 로 등록되어 있습니다. **컨텍스트가 닫힐 때 스택이 닫히고,
그때 스택이 만든 것만 만든 역순으로 닫힙니다.** 접근자로 꺼내 쓴 것은 전부 **빌려온 것**이므로 여러분이
닫으면 안 됩니다.

### 3.2 auto-configuration 슬라이스

`AimonAutoConfiguration` 이 코어를 세우고, 나머지 7개가 각각 한 축을 담당합니다. 전부
`META-INF/spring/…AutoConfiguration.imports` 에 등재되어 있습니다.

| 슬라이스 | 켜지는 조건 | 배선하는 것 |
|---------|-----------|------------|
| `AimonAutoConfiguration` | 항상 (`aimon.enabled=false` 면 비활성 대체 빈) | `AimonStack`, `AimonSessions`, `AimonAgents`, 2단 `SmartLifecycle`, `PendingTurnRegistry` |
| `AimonLlmAutoConfiguration` | `@ConditionalOnClass` (벤더 모듈) + `aimon.llm.provider` | `LlmClient` |
| `AimonFileSystemAutoConfiguration` | 항상 | `FileSystemSpec` — 기본은 워크스페이스 루트의 로컬 트리 |
| `AimonSessionAutoConfiguration` | 항상 | `SessionSpec` — `aimon.session.store` / `mode` |
| `AimonSchedulingAutoConfiguration` | `aimon.scheduling.backend` | `SchedulingSpec`, Quartz 중첩 구성 |
| `AimonObservabilityAutoConfiguration` | 스위치 3개가 **독립** | 추적(프로퍼티) · Actuator `HealthIndicator`(클래스) · Micrometer 게이지(`MeterRegistry` **빈**) |
| `AimonKnowledgeAutoConfiguration` | `aimon.knowledge.backend` | `KnowledgeStore` + `KnowledgeContribution` |
| `AimonMemoryAutoConfiguration` | `aimon.memory.backend` | `RepresentationStore` / `ObservationStore` + `MemoryContribution` |

IMPORTANT: 뒤의 두 슬라이스는 **빈과 선택자가 어긋나면 기동을 거부**합니다. `KnowledgeStore` 빈을 선언해
놓고 `knowledge.backend: none` 이면 — 어떤 에이전트에게도 그 저장소에 닿을 도구가 주어지지 않으므로 —
"`supplied` 로 바꾸든지 빈을 지우든지" 라는 메시지로 실패합니다. 메모리 쪽도 같습니다. 조용히 무시되는
설정이 없다는 뜻입니다.

### 3.3 기동과 종료 순서

`SmartLifecycle` 2단이고, 두 phase 는 Boot 의 웹 서버 phase 를 **양쪽에서 끼우도록** 잡혀 있습니다.

```
AimonRuntimeLifecycle       MAX_VALUE - 4096
WebServerStartStopLifecycle MAX_VALUE - 2048   (Boot)
AimonSchedulingLifecycle    MAX_VALUE
```

즉 **런타임이 먼저 서고 → 웹 서버가 열리고 → 스케줄링이 마지막에 시작**되며, 종료는 정확히 그 역순입니다.
크론이 도는 동안 런타임이 사라지거나, 서버가 요청을 받는데 런타임이 아직 없는 상태가 생기지 않습니다.
(같은 phase 를 쓰면 한 `LifecycleGroup` 에 묶여 빈 팩토리 순회 순서로 정해지므로, "런타임이 소켓보다 먼저"
가 보장이 아니라 우연이 됩니다. 간격이 2048 이 아닌 이유가 그것입니다.)

실제 자원 해제는 `stop()` 이 아니라 **`AimonStack.close()`** 가 합니다 — Spring 은 lifecycle 을 멈춘 뒤에
빈을 파괴하므로, `stop()` 에서 런타임을 걷어내면 스택이 세션을 드레인하는 도중에 그 밑을 빼는 셈이 됩니다.
그래서 종료 시간을 줄이는 노브는 `spring.lifecycle.timeout-per-shutdown-phase` 가 **아니라**
`aimon.session.shutdown-drain-timeout` 입니다.

### 3.4 스타터가 이미 닫아 놓은 함정

수동 배선 시절 이 문서가 크게 경고하던 두 가지는 **스타터 경로에서는 발생하지 않습니다**.

- **`MessageQueueManager` / `HookExecutionManager` 가 `null` 로 들어가는 문제** — 스타터의
  `StackLiveSessionOpener` 는 7-인자 `DefaultLiveSession` 생성자를 씁니다. 큐도 훅도 실제로 주입됩니다.
  (`LiveSessionFactory.open` 을 직접 부르는 경로에만 해당하는 문제였습니다 — 부록 A 참조.)
- **`AgentRuntimeRegistry` 를 `SchedulingEngine` 과 `OrcaAgentRuntimeManager` 가 나눠 갖는 문제** —
  스택이 레지스트리를 소유하고 양쪽에 같은 인스턴스를 넘깁니다.

### 3.5 fail-fast 와 degradation

`aimon.fail-fast` 의 기본값은 **`false`** 입니다. 실수가 아니라 의도입니다 — 문서화된 서버 기본값 중 셋이
**일부러** 축소된 기능을 등록하기 때문입니다: in-memory 세션 저장소, deny-all 스킬 승인 채널, 비활성
스케줄링. `true` 로 켜면 그 셋 때문에 기동이 실패합니다.

축소된 기능은 사라지지 않고 **degradation 으로 보고**됩니다. 기동 시 스타터가 먼저 한 줄 남깁니다 —
`AIMON started with reduced capability: …`. 프로그램적으로 읽으려면:

```java
stack.degradations().asList()
        .forEach(d -> log.warn("degraded: {} — {}", d.getCapability(), d.getConsequence()));
```

Actuator 를 쓰면 같은 목록이 `/actuator/health` 의 `degradations` 디테일로 나옵니다 (§13).

---

## 4. 프로퍼티

전체 트리는 `AimonProperties` 가 정의하고, IDE 자동완성용 메타데이터가 함께 생성됩니다. 자주 쓰는 것만
추립니다.

```yaml
aimon:
  enabled: true                     # false → 비활성 대체 빈(호출 시 AimonDisabledException)
  fail-fast: false                  # true → degradation 이 하나라도 있으면 기동 실패

  workspace:
    root: /var/lib/aimon            # 필수
    ensure-writable: true

  agent-defaults:
    default-agent: ops              # agents 를 비우거나 2개 이상 선언하면 필수
  agents:                           # 선택 — 비우면 default-agent 하나만
    ops:
      bundle: ops                   # 생략하면 맵 키와 같다
    support:
      bundle: ops                   # 같은 번들을 다른 ref 로 굴릴 수 있다

  agent-runtime:                    # 테넌트 런타임 캐시
    eviction: idle                  # idle(기본) | never
    idle-ttl: 30m
    sweep-interval: 5m
    max-entries: 100

  llm:
    provider: anthropic             # anthropic(기본) | openai | none
    api-key: ${ANTHROPIC_API_KEY}
    timeout: 60s

  credentials:                      # 선택 — 도구가 'profile.field' 로 부르는 값 (§10)
    jira:
      username: svc-aimon
      password: ${JIRA_PASSWORD}

  budget:                           # 기본값은 이미 유한하다 (20 / 100000 / 120s) — 여기서 좁힌다 (§8)
    max-iterations: 20
    max-tokens: 50000
    max-wall-clock: 60s

  session:
    store: in-memory                # in-memory(기본) | postgres | mongodb | redis
    mode: single-node               # single-node(기본) | distributed
    node-id: ${HOSTNAME}
    shutdown-drain-timeout: 30s
    cache:
      max-entries: 1000
      idle-ttl: 30m

  skill:
    approval:
      mode: deny                    # deny(기본) | allow-list | suspend | channel
      allow: []
      pending-turn-ttl: 10m

  scheduling:
    backend: none                   # none(기본) | in-memory | quartz
    auto-startup: true
    quartz:
      use-application-scheduler: true   # 기본값 — 앱의 Quartz 스케줄러를 빌려 쓴다
      instance-name: aimon              # 아래 넷은 use-application-scheduler=false 일 때만 의미가 있다
      thread-count: 4
      daemon-threads: true
      wait-for-jobs-on-shutdown: false

  tracing:
    enabled: false
    payload-capture: none           # none(기본) | full
    max-chars: 2000
    max-spans: 500

  tools:
    bash:
      enabled: false                # 기본값 — 서버에서는 셸을 명시적으로 켜야 한다

  knowledge:
    backend: none                   # none(기본) | keyword | supplied
    chunk-size: 1000
    chunk-overlap: 100
  memory:
    backend: none                   # none(기본) | in-memory | supplied
    redaction: default              # default(기본) | strict | none | supplied
```

- 선택자 값(`store`, `mode`, `backend`, `provider`, `eviction` …)은 전부 enum 으로 바인딩되므로 **오타는
  기동 시점에 읽을 수 있는 메시지로 실패**합니다.
- `default-agent` 는 `aimon.agents` 에 **정확히 하나**만 선언했을 때만 생략할 수 있습니다. 둘 이상이면
  기동이 실패합니다 — 맵의 첫 항목을 고르면 YAML 순서가 바뀌거나 프로파일이 에이전트를 하나 더
  기여하는 날 **프로퍼티 변경 없이 트래픽이 다른 에이전트로 옮겨가기** 때문입니다.
- `aimon.llm.provider: none` 은 "스타터가 `LlmClient` 를 만들지 않는다"는 뜻입니다. 여러분이
  `LlmClient` 빈을 직접 등록하면 그것이 쓰입니다(샘플 앱이 이 형태입니다). 아무도 등록하지 않으면
  스타터의 폴백이 남는데, 그것은 호출 시 **어떤 프로퍼티를 채워야 하는지 이름을 대며** 실패합니다.
- **`Bash` 도구는 기본이 off 입니다** — CLI 와 다른 지점입니다. CLI 가 셸을 그냥 쥐여 주는 것은 사람이 명령
  하나하나를 승인하기 때문이고, 서버 프로세스에는 물어볼 사람이 없으므로 임의 명령 실행은 명시적으로 켜야
  합니다.
- `aimon.credentials.<프로필>.<필드>` 는 도구가 `credential_ref: 'jira.password'` 같은 이름으로 부르는 값을
  세웁니다. 프로필 이름과 필드 이름에 **점을 쓸 수 없습니다** — 참조 문법이 점을 정확히 하나만 허용하므로
  점이 들어간 이름은 바인딩된 뒤 아무도 부를 수 없게 되고, 그래서 기동 시점에 거부합니다. 필드가 하나도 없는
  프로필도 같은 이유로 거부합니다. 값은 `/env` 와 `/configprops` 에서 `show-values` 설정과 무관하게
  마스킹됩니다(§13.5). 복수형 `credentials` 인 것은 문법이 아니라 그 마스킹 때문입니다 — Boot 의 단어 목록이
  잡는 것이 `credentials` 이고, 규칙이 키 전체를 보므로 프리픽스 하나로 그 아래 **임의의 리프 이름**까지
  가려집니다.
- `knowledge` / `memory` 의 `supplied` 는 "**여러분이 그 빈을 선언하고 스타터는 도구만 거기에 연결한다**"는
  뜻입니다. Spring 이 만들었으니 Spring 이 닫고, 스택은 빌려 쓸 뿐입니다. `knowledge.backend` 에
  **OpenSearch 값이 일부러 없는** 것도 같은 이유입니다 — `aimon-knowledge-opensearch` 는 존재하고 동작하지만
  TLS 검증을 끄면 trust-all `X509TrustManager` 를 설치하므로, 스타터 상수로 만들면 그 결정이 프로퍼티 한 줄
  뒤로 숨습니다. 원하는 애플리케이션이 `@Bean` 하나로 선언하고, 보안 결정은 사람이 읽는 자리에 남습니다.

---

## 5. 네 개의 스코프

스타터를 쓰든 손으로 배선하든, **수명 규칙은 같습니다**. 전체 규칙은
[`docs/overview/scope-model.md`](../overview/scope-model.md) §1 이 기준입니다.

```
┌──────────────────────────────────────────────────────────────┐
│                    Your Application                          │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Live session scope (노드 로컬, 일시적)              │    │
│  │  LiveSession — 턴을 실행하는 핸들                   │    │
│  │  스타터에서는 SessionRouter 가 열고 캐시하고 닫는다 │    │
│  └─────────────────────────────────────────────────────┘    │
│                         │ 참조 (빌려온 것 — 닫지 않음)      │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Session scope (영속, SessionId 로 식별)             │    │
│  │  SessionRecord — 메시지 히스토리, SessionTotals,     │    │
│  │                  budgetOverride                     │    │
│  │  1 SessionRecord : 0..N LiveSession (비대칭)         │    │
│  └─────────────────────────────────────────────────────┘    │
│                         │                                    │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Agent scope (per (Agent, discriminator))            │    │
│  │  AgentRuntimeId = "agent:<name>[:<disc>]"           │    │
│  │  OrcaAgentRuntime — ToolRegistry, HookRegistry,     │    │
│  │  SkillRegistry, SubagentRegistry, CommandRegistry,  │    │
│  │  CompactionEngine/Guard, McpClientManager           │    │
│  └─────────────────────────────────────────────────────┘    │
│                         │                                    │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Application scope (싱글턴)                          │    │
│  │  OrcaAgentExecutor, LlmClient, SchedulingEngine,    │    │
│  │  AgentRuntimeRegistry, MessageQueueManager,         │    │
│  │  VirtualFileSystem, SessionRecordStore,             │    │
│  │  SessionLeaseStore, KnowledgeStore, CredentialStore │    │
│  │  ← 스타터에서는 전부 AimonStack 이 소유             │    │
│  └─────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

| 스코프 | 수명 | 예시 |
|--------|------|------|
| **Application** | 프로세스 시작 ~ 종료 | `OrcaAgentExecutor`, `LlmClient`, `SchedulingEngine`, `AgentRuntimeRegistry`, `MessageQueueManager`, `VirtualFileSystem`, `SessionRecordStore`, `SessionLeaseStore` |
| **Agent** | agent 등록 ~ agent 제거 또는 앱 종료 | `OrcaAgentRuntime` (ToolRegistry, HookRegistry, SkillRegistry, MCP 클라이언트) |
| **Session** | 세션이 존재하는 동안 — **영속** | `SessionId`, 메시지 히스토리, `SessionTotals`, `budgetOverride` (모두 `SessionRecordStore` 가 보관) |
| **Live session** | 핸들 open ~ `close()` — 노드 로컬 | `LiveSession`, 메시지 큐 리스너, 이벤트 publisher |

IMPORTANT: **Session 과 Live session 은 다른 수명입니다.** 한 `SessionId` 에 살아 있는 핸들이 0개일 수도
있고(아무도 대화 중이 아님), 시간에 걸쳐 여러 핸들이 순차로 서빙할 수도 있습니다(idle 만료, 프로세스
재시작, 노드 이동). **재시작을 넘어 살아남아야 하는 값은 `SessionRecordStore` 에 두고, 핸들이 죽으면 같이
사라져도 되는 값만 `LiveSession` 에 둡니다.** 이 구분을 놓치면 재시작 후 누적 토큰/턴 수가 조용히 0으로
돌아갑니다.

> 스코프를 잘못 잡으면 세 가지 버그 중 하나가 납니다:
> - 세션 `close()` 에서 `AgentRuntime` 이나 스케줄링 엔진까지 닫으면 → **동일 agent 의 다른 세션 또는
>   예약 작업이 파괴**
> - 애플리케이션 종료 시 세션만 닫고 실행자를 내버려 두면 → **LLM 연결이 프로세스 종료까지 남아있는**
>   리소스 누수
> - `SessionRecordStore` 없이 세션을 열면 → 히스토리·누적치가 **핸들과 함께 사라짐** (재시작 후 복원 불가)

스타터를 쓰면 이 셋은 전부 스택이 처리합니다. 그래도 규칙을 알아야 하는 이유는 **여러분이 스택에서 꺼낸
객체를 닫으면 같은 버그가 그대로 재현되기 때문**입니다.

---

## 6. 턴 실행 — `AimonSessions`

`AimonSessions` 는 **애플리케이션이 주입할 것으로 기대되는 유일한 빈**입니다. 그 아래(`AimonStack`,
`SessionRouter`, `LiveSession`)도 전부 공개되어 있고 그대로 쓸 수 있지만, "메시지 보내고 답 받기" 만
원하는 호스트가 그 어휘를 배울 필요는 없습니다.

```java
// 동기 — 한 턴을 돌고 끝날 때까지 블록
AgentExecutionResult submit(SessionId sessionId, String input);
AgentExecutionResult submit(SessionId sessionId, String agentRef, String input, LiveSessionOptions options);

// 비동기 — 어디로 갔는지와 기다리는 방법을 함께 돌려준다
SubmitDisposition submitAsync(SessionId sessionId, String input);
SubmitDisposition submitAsync(SubmitRequest request);

// 한 필드만 바꾸고 싶을 때
SubmitRequest.Builder newRequest(SessionId sessionId, String input);

Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId);
void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason);
void release(SessionId sessionId);
```

### 6.1 이 파사드가 채워 주는 것

`SessionRouter.submit(SubmitRequest)` 는 `agentRef` 와 `initiator` 를 요구하고,
`LiveSessionOptions.defaults()` 를 **조용히 받아들입니다** — 그 기본 예산은 **unlimited** 입니다. 라우터를
직접 부르면서 요청을 손으로 만들면, 예산을 직접 붙이는 것을 잊는 순간 무제한 턴이 돕니다.
`aimon.budget.*` 이 막으려던 바로 그 실패입니다.

`AimonSessions` 는 자기가 다루는 모든 요청에 **설정된 에이전트 · 설정된 기본 예산 · 시스템 initiator** 를
채워 넣습니다.

### 6.2 탈출구는 구멍이 아니다

`newRequest(...)` 는 **그 기본값들이 이미 채워진** 빌더를 돌려줍니다. 한 필드(멱등 키, 우선순위, 실제
최종 사용자 `Principal`)만 덮어쓰고 나머지는 유지하세요 — 빈 `SubmitRequest.builder()` 에서 시작하면
예산이 unlimited 로 돌아가고 `agentRef` 와 `initiator` 를 다시 적어야 합니다.

```java
SubmitDisposition disposition = sessions.submitAsync(
        sessions.newRequest(sessionId, input)
                .initiator(Principal.user(currentUser.getId()))   // 이 한 필드만 교체
                .idempotencyKey(req.getRequestId())
                .build());
```

`submitAsync(SubmitRequest)` 는 완성된 요청을 그대로 받으며, 다른 모든 submit 메서드가 위임하는
**단 하나의 primitive** 입니다.

### 6.3 `SubmitDisposition` 읽기

```java
SubmitDisposition d = sessions.submitAsync(sessionId, input);
switch (d.getKind()) {
    case EXECUTED_LOCALLY -> log.debug("이 노드가 세션 락을 잡았다");
    case FORWARDED -> log.debug("다른 노드가 홀더다 — 인박스에 적재됨: {}", d.getInboxId().orElseThrow());
}
d.getFuture().whenComplete((result, err) -> render(result, err));
```

두 값은 **busy/queued 가 아니라 this-node/other-node** 입니다. 단일 노드 배포에서는 언제나
`EXECUTED_LOCALLY` 이고, 어느 쪽이든 `getFuture()` 로 결과를 기다립니다.

### 6.4 인터럽트는 세션이 아니라 **턴**에 겨눈다

```java
// InterruptReason 은 enum 이다. 사용자가 누른 취소 버튼은 USER_SIGINT
// ("Ctrl+C 또는 그에 준하는 것" — CLI 전용이 아니다).
sessions.interrupt(sessionId, disposition.getTurnId(), InterruptReason.USER_SIGINT);
```

사용자의 취소가 에이전트에 도달할 무렵이면 그 턴은 이미 끝나고 **다음 턴이 시작되었을 수 있습니다**.
주소 없는 중단은 그 다음 턴을 죽입니다. `TurnId` 는 `SubmitDisposition.getTurnId()` 에서 오고, 맞지 않으면
조용한 no-op 입니다.

이유는 관측용으로만 쓰이는 게 아니라 결과의 `CompletionReason` 으로 이어지므로, 중단을 사용자에게 어떻게
설명할지는 `result.getCompletionReason()` 으로 갈라 쓸 수 있습니다.

### 6.5 `release` 는 삭제가 아니다

`release(sessionId)` 는 **노드 로컬 핸들만** 버리고 저장된 히스토리는 남깁니다 — 같은 id 로 다시 submit
하면 대화가 이어집니다. 진짜 삭제는 `SessionRouter.deleteSession(sessionId)` 이고, 그쪽은 세션 락을 먼저
잡습니다.

---

## 7. 스트리밍 이벤트 전달

`submit` 은 최종 결과만 돌려줍니다. ReAct 루프의 중간 이벤트(iteration 시작/종료, tool 호출, 어시스턴트
텍스트 델타, 최종 메시지)를 SSE 나 WebSocket 으로 흘리려면 `events(sessionId)` 를 구독하세요.

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam String threadId, @RequestParam String input) {
    SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(10));
    SessionId sessionId = SessionId.of(threadId);

    sessions.events(sessionId).subscribe(new Flow.Subscriber<>() {
        private Flow.Subscription subscription;

        @Override public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(AgentExecutionEvent event) {
            try {
                // AgentExecutionEvent 는 sealed 이므로 종류는 단순 클래스명으로 식별한다
                // (IterationStarted, ToolUseStarted, ToolResultReady, AssistantMessageReceived,
                //  ExecutionCompleted, ...).
                emitter.send(SseEmitter.event().name(event.getClass().getSimpleName()).data(event));
            } catch (IOException e) {
                subscription.cancel();
            }
        }

        @Override public void onError(Throwable t) { emitter.completeWithError(t); }
        @Override public void onComplete() { emitter.complete(); }
    });

    sessions.submitAsync(sessionId, input).getFuture()
            .whenComplete((r, err) -> { if (err != null) emitter.completeWithError(err); else emitter.complete(); });

    return emitter;
}
```

- `events(...)` 의 publisher 는 **여러 관찰자가 동시에 구독**할 수 있습니다.
- 구독자는 **측면 출력**(side-channel)입니다 — 거기서 던진 예외로 턴이 취소되지 않습니다. 관측 실패가
  사용자 응답을 망가뜨리지 않도록 설계하세요.
- 이벤트 타입은 `at.aimon.core.agent.stream` 에 **15개**입니다 (sealed 이므로 이것이 전부입니다) —
  `IterationStarted` / `IterationCompleted` / `AssistantMessageReceived` / `AssistantTextDelta` /
  `AssistantTextStreamReset` / `AssistantTextStreamCompleted` / `ToolUseStarted` / `ToolResultReady` /
  `SubagentTaskCompleted` / `SkillTurnSuspendedEvent` / `CompactBoundary` / `InterruptedAt` / `RejectedAt` /
  `ExecutionCompleted` / `ExecutionError`. `getIteration()` 은 sealed 기반 클래스의 `final` 메서드이므로
  모든 서브타입에서 읽을 수 있습니다.

### 7.1 큐에 대한 정직한 이야기

라우터 경로(`AimonSessions.submitAsync`)는 **언제나 `submitAsync` 를 쓰고 `offerAsync` 를 쓰지 않습니다.**
`offerAsync` 는 입력이 단순 큐잉되면 결과 stage 가 없는 `SubmitOutcome` 을 돌려주는데, 그러면 제출한
노드가 호출자에게 건넨 future 를 영원히 닫을 수 없기 때문입니다. 그래서 라우터에서 **우선순위는 큐
*위치*만 정합니다.**

Mid-turn 주입(진행 중인 턴 안으로 메시지를 끼워 넣는 것)은 **로컬 `offerAsync` 경로 전용**입니다. 그
경로의 호출자는 결과 stage 가 없다는 것을 알고 부릅니다. 실행자 쪽 드레인
(`OrcaAgentExecutor.injectQueuedMessages`)은 `AgentRuntimeId` 로 범위를 잡아 `NEXT` 이상 tier 를 걷어오고
`LATER` tier 는 남깁니다 — **턴 종료 시점의 드레인은 여전히 호스트 책임(CQ-05)** 입니다. 자세한 내용은
[command-queue-guide.md](../features/agent-execution/command-queue-guide.md) 를 보세요.

---

## 8. ExecutionBudget 정책

```yaml
aimon:
  budget:
    max-iterations: 20
    max-tokens: 50000
    max-wall-clock: 60s
```

이 값은 `AimonSessions` 가 **모든 요청에 붙이는 기본 예산**이 됩니다. 요청별로 바꾸려면 명시적 오버로드를
쓰세요.

```java
LiveSessionOptions tight = LiveSessionOptions.builder()
        .budget(ExecutionBudget.builder()
                .maxIterations(clamp(req.getMaxIterations(), 1, 50))
                .maxTokens(pricingPlanCap(principal))
                .maxWallClockDuration(Duration.ofSeconds(req.getTimeoutSec()))
                .build())
        .locale(Locale.forLanguageTag(req.getLocale()))
        .sourceAgentId("api:/agent/chat")
        .build();

sessions.submit(sessionId, "ops", req.getInput(), tight);
```

IMPORTANT: 예산은 **실행 단위(턴 또는 서브에이전트 포크)당** 적용됩니다 — 세션 단위가 아닙니다. 세션
전체의 누적치는 `SessionTotals` 가 따로 들고 있으므로(`SessionRecord` 의 side field), 누적 토큰 상한이
필요하면 그 값을 읽어 **애플리케이션 레이어에서** 판단하세요
([llm-usage-metering.md](../features/llm/llm-usage-metering.md)).

IMPORTANT: **스타터의 기본 예산은 유한합니다** — `max-iterations: 20`, `max-tokens: 100000`,
`max-wall-clock: 120s`. 프레임워크 자체의 기본값인 `ExecutionBudget.unlimited()` 와 **일부러 다릅니다.**
무한 ReAct 루프는 사람이 지켜보다 Ctrl-C 를 누를 수 있는 CLI 에서는 방어 가능한 기본값이지만, 같은 루프가
아무도 안 보는 요청 핸들러인 서버 프로세스에서는 아닙니다. 위 YAML 은 그 기본값을 **좁히는** 예이지
없던 상한을 켜는 예가 아닙니다.

- 각 필드는 **설정된 것만** 적용됩니다. 프로파일에서 빈 값을 주는 것은 상한을 **없애는** 방법이 아니라
  기본값으로 되돌리는 방법입니다 — 상한을 넓히려면 큰 값을 명시하십시오.
- 런타임에 바꾼 예산은 `SessionRecord` 의 `budgetOverride` 로 **영속**되므로 핸들을 다시 열어도 유지됩니다
  (되돌리려면 명시적으로 지워야 합니다 — 부록 A 의 `clearBudgetOverride()`).

---

## 9. 다중 에이전트와 테넌트

### 9.1 에이전트 여러 개

```yaml
aimon:
  agent-defaults:
    default-agent: ops
  agents:
    ops:
      bundle: ops
    support:
      bundle: ops        # 같은 번들, 다른 ref — ref 와 bundle 은 분리되어 있다
      properties:        # 스타터는 이 값을 읽지 않는다 — 커스터마이저에게 그대로 전달될 뿐이다
        escalation-channel: "#support-oncall"
```

맵의 **키가 ref** 입니다 — submit 이 라우팅하는 이름이자 나머지 전부가 조인하는 정체성. `bundle` 은 클래스패스
에서 읽을 디렉토리(`agents/<bundle>/agent.md`)이고 생략하면 키와 같아집니다. 둘이 분리된 이유는 **한 정의를 두
ref 로 띄우고 싶을 때**가 있기 때문입니다 — `ops` 와 `ops-readonly` 를 하나의 번들에서 만들고 커스터마이저와
`properties` 로 갈라놓는 식입니다.

`properties` 는 **스타터가 한 글자도 읽지 않습니다.** `AgentDescriptor.getProperties()` 로 커스터마이저에게
그대로 넘어갈 뿐입니다 — 리전, 팀, 에스컬레이션 채널 같은 배포 사실로 분기하고 싶을 때, 에이전트 ref 로 키잉된
두 번째 설정 트리를 만들어 손으로 동기화하지 않게 하려고 있는 자리입니다.

IMPORTANT: 이 트리에는 **에이전트별 예산도, 도구 on/off 도 없습니다.** `AgentEntry` 가 갖는 것은 `bundle` 과
`properties` 둘뿐입니다. 예산은 `aimon.budget.*` 전역 기본값과 호출 시점의
`LiveSessionOptions`(§8)로, 도구 차등은 `AimonAgentCustomizer.supports(...)`(§10)로 갈립니다.

```java
sessions.submit(sessionId, "support", input, null);   // agentRef 로 라우팅, options 는 기본값
```

`AimonAgents.list()` 는 이 배포에 **설정된** 에이전트를 돌려줍니다 — 유한하고, 설정에서 옵니다.

```java
for (AgentDescriptor agent : agents.list()) {
    log.info("{} → bundle={} runtimeId={}", agent.getAgentRef(), agent.getBundleName(), agent.getRuntimeId());
}
```

### 9.2 테넌트

같은 에이전트 정의를 테넌트별로 쪼개려면 `contextDiscriminator` 를 붙입니다 — `AgentRuntimeId` 가
`agent:<name>:<discriminator>` 가 되어 런타임이 갈립니다.

```java
sessions.submitAsync(
        sessions.newRequest(sessionId, input)
                .agentRef("ops")
                .contextDiscriminator(tenantId)
                .build());
```

테넌트 런타임의 개수는 트래픽에 따라 자라므로 `AimonAgents.list()` 가 보고하지 **않습니다** — 그것은 설정
사실이 아니라 용량 사실입니다. 현재 보유량은 `AimonStack.agentRuntimes()` 또는 §13 의 게이지로 봅니다.
상한과 축출은 `aimon.agent-runtime.*` 이 정합니다.

### 9.3 무효화

```java
agents.invalidate("ops", tenantId);   // 이 테넌트 런타임만
agents.invalidate("ops");             // 이 ref 의 테넌트 런타임 전부
```

무효화는 **런타임이 만들어진 근거가 바깥에서 바뀌었을 때** 쓰는 것입니다(테넌트가 키를 교체, 운영자가
연동을 회수). 메모리 회수 용도가 아닙니다 — 유휴 런타임은 이미 `aimon.agent-runtime.*` 이 걷어가고,
여기에 부르면 다음 요청이 재조립 비용을 냅니다.

**진행 중인 턴을 끊지 않습니다.** 런타임은 즉시 등록 해제되어 다음 submit 이 새것을 만들고, 마지막 보유자가
놓을 때 닫힙니다. 선언된 에이전트의 기동 런타임은 무효화 대상이 아니며, 그 이름을 부르면 에러가 아니라
no-op 입니다.

---

## 10. 갈아끼우기 — 확장점

**1일차부터 쓰는 확장점은 넷**이고, 전부 빈으로 선언하면 스타터가 알아서 집습니다.

| 빈 | 수집 방식 | 무엇을 바꾸나 |
|----|----------|--------------|
| `AimonAgentCustomizer` | `ObjectProvider<>` 로 **N개** 수집 | 에이전트별 도구·커맨드·훅 |
| `VirtualFileSystem` **또는** `VirtualFileSystemFactory` | 둘 중 **하나만** — 1개 | 전역 파일 백엔드 / 에이전트·테넌트별 파일 백엔드 (기본: 워크스페이스 로컬 서브트리) |
| `CredentialStore` **또는** `CredentialStoreFactory` | 둘 중 **하나만** — 1개 | 자격증명 (기본: 구현 없음). **프로퍼티로도 세울 수 있는 유일한 줄** — 아래 참조 |
| `SkillApprovalChannel` **또는** `SkillApprovalChannelFactory` | 둘 중 **하나만** — 1개 | 스킬 승인을 사람에게 묻는 채널 (`approval.mode: channel` 일 때) |

IMPORTANT: 뒤의 세 줄은 **단수 빈과 팩토리가 서로 대안**입니다. 둘 다 정의하면 스타터가 어느 쪽을 쓸지 고를 수
없으므로 **기동 시 `IllegalStateException`** 으로 거부합니다(조용히 하나를 고르지 않습니다). 테넌트별로
갈라야 하면 팩토리를, 전역 하나면 단수 빈을 정의하고 다른 쪽은 두지 마십시오.

자격증명만 세 번째 길이 있습니다 — `aimon.credentials.*` (§4). 넷 중 이것만 **코드가 아니라 데이터**이기
때문입니다: 나머지 셋은 여러분이 무엇을 구현했는지가 답이지만, 어떤 프로필이 존재하는지는 배포마다 다른
값이고 `@Bean` 으로 적으면 그 목록이 컴파일 타임 결정이 됩니다. 프로퍼티로 두면 `${JIRA_PASSWORD}` 로
환경변수·Vault·config server 를 그대로 끌어올 수 있고, 그것이 Boot 가 이미 하는 일입니다.

IMPORTANT: 이 세 갈래도 **서로 대안**이라 둘 이상을 채우면 거부합니다 — 여기서는 빈이 프로퍼티를 조용히
이기게 두지 않았습니다. 사라지는 쪽이 다시 타이핑하면 되는 기본값이 아니라 **비밀**이고, 떨어뜨린 증상이
기동이 아니라 몇 시간 뒤 어떤 도구의 "credential not found" 로만 나타나기 때문입니다. 그때 설정 파일은
여전히 값이 있는 것처럼 읽힙니다.

`LlmClient` · `SessionSpec` · `FileSystemSpec` · `SchedulingSpec` · `KnowledgeStore` ·
`RepresentationStore` · `ObservationStore` · `TaskSchedulerFactory` 도 전부 `@ConditionalOnMissingBean`
이므로 **여러분이 정의하면 여러분이 이깁니다.** `*Spec` 을 직접 정의하는 것은 프로퍼티 트리 전체를
건너뛰겠다는 뜻이라 마지막 수단입니다 — 프로퍼티로 표현되는 것은 프로퍼티로 두는 편이 낫습니다.

```java
@Component
public class TicketToolsCustomizer implements AimonAgentCustomizer {

    private final TicketApi api;   // 여러분의 도메인 서비스

    public TicketToolsCustomizer(TicketApi api) {
        this.api = Objects.requireNonNull(api);
    }

    @Override
    public boolean supports(AgentDescriptor agent) {
        return "ops".equals(agent.getAgentRef());
    }

    @Override
    public List<OrcaToolProvider> toolProviders(AgentDescriptor agent) {
        // discriminator 가 있으면 테넌트별 런타임이다 — 그 테넌트의 자격증명으로 도구를 만든다.
        return List.of(new TicketToolProvider(api, agent.getDiscriminator().orElse(null)));
    }

    @Override
    public void registerHooks(AgentDescriptor agent, HookRegistry hooks) {
        // 레지스트리는 이벤트 타입 토큰으로 주소를 지정한다 — register(hook) 오버로드는 없다.
        hooks.register(HookEventType.PRE_TOOL, new AuditHook());
    }

    @Override
    public int getOrder() {
        return 0;   // List<> 주입 순서는 신뢰할 수 없으므로 순서가 중요하면 명시한다
    }
}
```

IMPORTANT: 커스터마이저는 **테넌트 런타임을 만들 때도 호출**됩니다. `agent:ops` 에 대해 기동 시 한 번,
`agent:ops:acme` 에 대해 그 테넌트가 처음 등장할 때 다시 — 두 런타임이 서로 다른 도구를 갖지 않게 하는 것이
이 확장점의 존재 이유입니다. 따라서 `supports(...)` 는 **요청 스레드에서, 여러 테넌트에 대해 동시에** 불릴 수
있습니다. 구현은 **thread-safe** 해야 하고, 각 에이전트를 한 번만 본다고 가정하면 안 됩니다.

두 가지 보장이 더 있습니다.

- **훅은 런타임이 도달 가능해지기 전에 등록됩니다.** `registerHooks` 는 런타임을 다 만든 뒤, 아직 아무도
  그 런타임을 집어갈 수 없는 시점에 불립니다 — 그 사이에 시작된 턴을 훅이 놓치는 일은 없습니다.
- **기여는 더해지기만 합니다.** 반환한 프로바이더는 스타터의 기본 목록 **뒤에 덧붙습니다**. 커스터마이저가
  다른 커스터마이저가 넣은 도구를 빼앗을 수는 없습니다. 어떤 에이전트에게 도구를 주지 *않으려면*
  `supports(...)` 에서 그 에이전트를 걸러 내는 것이 유일한 방법입니다.

### 10.1 노드가 둘이 될 때 추가로 정의하는 빈 넷

위 넷과 달리 이것들은 **1일차에 쓰지 않습니다.** 단일 노드에서는 기본 인메모리 구현이 맞고, 두 번째
노드가 붙는 순간에만 답이 달라집니다. 프로퍼티로 고르는 백엔드가 없으므로 **빈의 존재 자체가 설정
전부**이고, 없으면 노드 로컬 기본값입니다.

| 빈 | 없을 때 | 공유하면 |
|----|---------|---------|
| `SessionApprovalStore` | 세션이 노드를 옮기면 스킬을 다시 묻는다 | 옮겨도 묻지 않는다 |
| `AgentApprovalStore` | "이 에이전트에서 항상 허용" 이 다른 노드에 없다 | 클러스터 전체에 적용된다 |
| `PendingTurnRegistry` | 다른 노드에서 중단된 턴에 대한 `/approve` 가 풀 것을 못 찾는다 | 어느 노드에서든 푼다 |
| `MessageQueueRepository` | 턴 뒤에 쌓인 입력이 받은 노드에 남는다 | **아래를 먼저 읽을 것** |

앞의 셋은 `SkillApprovalSpec.with*` 로도 직접 얹을 수 있고(`AimonStack` 을 손으로 조립하는 경우),
셋 중 일부만 공유하면 기동 시 `distributed-approvals` degradation 이 **남은 것만** 이름을 부릅니다.
단 `AimonStackSpec` 빈을 직접 정의했다면 이 빈들은 스택에 닿지 않습니다 — 스펙을 만드는 것이 스타터가
아니게 되므로 그 스펙에 `with*` 를 손으로 얹어야 합니다. 레지스트리는 어긋나면 기동이 실패하며 그
사실을 말해 주지만, 나머지 셋은 그렇지 않습니다.

IMPORTANT: 마지막 줄은 앞의 셋과 **같은 결정이 아닙니다.** 큐의 배출은 `AgentRuntimeId` 하나로만
거르고 적재된 항목에는 `SessionId` 가 아예 없으므로, 공유 저장소에서는 그 에이전트 런타임의 턴을 다음에
도는 노드가 — 다른 세션의 턴이더라도 — 그 입력을 가져갑니다. 그래서 노드 로컬로 남겨 두는 것이
degradation 으로 보고되지 않습니다. 공유는 에이전트 런타임을 노드별로 쪼개 두었거나, 구현체가 받은
노드로 배달을 다시 좁힐 때만 맞습니다.

넷 다 **빌려온 것**입니다 — 스택은 어느 것도 닫지 않고, 여러분의 빈이므로 Spring 이 스택보다 나중에
닫습니다.

### 10.2 의도적으로 제외된 것

- **HTTP 엔드포인트** — 인증·라우팅은 앱의 도메인입니다. 스타터는 컨트롤러를 만들지 않습니다.
- **에이전트 정의를 프로퍼티로 작성** — 에이전트는 마크다운 번들입니다. `aimon.agents.<name>.*` 이 담는
  것은 **배선 값**(번들 이름, 예산, 도구 on/off)이지 프롬프트가 아닙니다.
- **에이전트 CRUD** — 에이전트를 DB 로 관리하는 것은 앱의 도메인입니다. 스타터는 `AimonAgents.invalidate`
  라는 수신구만 줍니다.

---

## 11. 멀티 인스턴스

`AimonSessions` 를 쓰는 코드는 **단일 노드와 분산에서 똑같습니다.** 달라지는 것은 프로퍼티뿐입니다.

```yaml
aimon:
  session:
    mode: distributed
    store: postgres           # postgres | mongodb | redis
    node-id: ${HOSTNAME}
```

이때 무슨 일이 일어나는지:

- **`SessionLeaseStore`** 가 한 `SessionId` 를 **한 노드만** 서빙하도록 리스로 선출합니다.
- **`SessionRecordStore`** 는 분산 백엔드로 갑니다. 세 구현이 in-tree 에 있습니다 —
  `PostgresSessionRecordStore`(`aimon-session-postgres`), `MongoSessionRecordStore`(`aimon-session-mongodb`),
  `RedisSessionRecordStore`(`aimon-session-redis`). (해당 모듈을 의존성에 넣어야 합니다.)
- **`SessionStore`** 가 둘을 묶어 **fencing** 합니다 — 리스를 이긴 노드만 레코드를 씁니다. `claim` 이
  리스 선출 → 에이전트 바인딩 검증 → 레코드 프로비저닝을 **그 순서로** 하므로, 선출에서 진 노드는 레코드를
  아예 건드리지 않습니다(분산 트랜잭션이 필요 없는 이유).
- **`SessionRouter`** 가 요청을 현재 홀더로 넘깁니다. 다른 노드가 홀더면 `submitAsync` 의 disposition 이
  `FORWARDED` 로 옵니다(§6.3). **sticky routing 이 필요 없습니다.**

IMPORTANT: `mode: distributed` 를 `store: in-memory` 와 함께 두면 **기동이 실패합니다**. 리스·시그널·인박스는
공유되는데 전사(transcript)는 노드마다 자기 힙에 있게 되어, 세션을 홀더로 라우팅하면 **그 대화를 본 적 없는
노드로 보내는** 꼴이 됩니다. 단일 노드보다 나쁘지, 멀티 노드로 가는 한 걸음이 아닙니다.

여전히 단일 JVM 전용인 것들:

- `InMemorySessionRecordStore` / `InMemorySessionLeaseStore` / `InMemoryMessageQueueRepository`
- `DefaultAgentRuntimeRegistry` — 보통 이대로 충분합니다. 런타임은 노드마다 있어도 되고, 세션은 리스가
  한 노드에 고정하기 때문입니다.

> 한 JVM 에 세션 매니저가 둘이면(멀티 노드 테스트 하네스) **같은 두 백엔드 위에 `SessionStore` 도 두 개**
> 만들어야 합니다 — 하나를 공유하면 서로의 리스를 자기 것으로 오인합니다.

설계 배경은 [`routing.md`](../design/session/routing.md).

---

## 12. Scheduling 라이프사이클 — 절대 건드리지 말 것

[`docs/overview/scope-model.md`](../overview/scope-model.md) §4("하지 말 것")의 첫 두 항목을 한 줄로 줄이면:

> **`LiveSession.close()` 는 핸들 자원만 정리한다. `AgentRuntime`, `SchedulingEngine`,
> `ScheduledTaskManager`, `OrcaAgentExecutor` 는 하나도 닫지 않는다.**

`AgentRuntime` 이 포함되는 이유는 그것이 **agent-scoped** 이기 때문입니다 — 같은 agent 의 다른 세션이 아직
그 runtime(과 그 안의 `ToolRegistry` / `McpClientManager`)을 쓰고 있을 수 있습니다.

`ScheduledTask.boundRuntimeId` 는 agent-scoped id 를 참조하므로, 원래 세션이 끝난 뒤 cron 이 재발화해도
런타임이 그대로 resolve 됩니다. `AgentRuntimeId` 가 `agent:<name>[:<disc>]` 로 **결정론적**이어야 하는
이유가 이것입니다.

스타터에서 이것을 어기는 방법은 하나뿐입니다 — **스택에서 꺼낸 것을 닫는 것**.

```java
// ❌ 빌려온 것을 닫는다
@PreDestroy
public void shutdown() {
    stack.schedulingEngine().ifPresent(SchedulingEngine::close);   // 스택이 닫을 것을 미리 닫아 버림
}

// ❌ 더 큰 실수 — 세션 close 에 런타임 파괴를 얹는다
public void endChat(SessionId id) {
    sessions.release(id);
    stack.runtime(runtimeId).ifPresent(OrcaAgentRuntime::close);   // 같은 agent 의 다른 세션이 쓰던 runtime 파괴
}

// ✅ 스택이 자기가 만든 것을 만든 역순으로 닫는다. 여러분은 아무것도 닫지 않는다.
```

`AimonStack` 은 `@Bean(destroyMethod = "close")` 이고, 스케줄링 종료는 §3.3 의 2단 `SmartLifecycle` 이
**웹 서버가 닫힌 뒤**에 처리합니다.

---

## 13. 관측과 로깅

### 13.1 Actuator health

`spring-boot-starter-actuator` 가 클래스패스에 있으면 `AimonHealthIndicator` 가 자동 등록됩니다.

```json
{
  "status": "UP",
  "details": {
    "aimonStatus": "SERVING",
    "not-closed": "UP",
    "agent-runtime-registered": "UP",
    "scheduling-running": "UP",
    "agent-runtime-capacity": "UP — 3 of 100 tenant runtime slot(s) in use",
    "degradations": [
      "session-store: ...",
      "skill-approval: ..."
    ]
  }
}
```

- 체크는 네 개입니다 — `not-closed`(스택이 닫혔는가), `agent-runtime-registered`(선언된 에이전트의 런타임이
  전부 서 있는가), `scheduling-running`(스케줄링이 꺼진 게 아니라 **죽은** 것은 아닌가),
  `agent-runtime-capacity`(테넌트 슬롯이 포화인가).
- 체크마다 **판정(UP/DOWN)과 설명을 둘 다** 싣습니다 — 알람이 매칭하는 것은 boolean 이고, 그 알람에 깨어난
  사람에게 필요한 것은 설명입니다. 포화 시 capacity 체크의 설명은 무엇을 올려야 하는지까지 말합니다
  ("… and none is idle, so the next new tenant is refused; raise maxEntries or shorten the idle TTL").
- **degradation 이 있어도 status 는 `UP` 입니다.** degradation 은 기동 시점에 확정되어 스스로 해소되지
  않으므로 계속 `DOWN` 을 띄우면 알람이 영구히 울립니다 — 대신 이유를 디테일로 싣습니다.
- `degradations` 는 §3.5 의 축소 기능 목록입니다. 통과한 체크에 붙은 설명도 노이즈가 아닙니다 — 용량
  체크가 그 방식으로 카운터를 나르고, 그것이 한도를 올릴지 판단하는 숫자입니다.

### 13.2 Micrometer

`MeterRegistry` **빈**이 있으면 테넌트 런타임 게이지가 등록됩니다 (prefix `aimon.agent.runtimes`).

| 미터 | 뜻 |
|------|-----|
| `aimon.agent.runtimes.active` (gauge) | 이 노드가 지금 들고 있는 테넌트 런타임 수 |
| `aimon.agent.runtimes.leased` (gauge) | 그중 **누군가 리스를 쥐고 있는** 수 — 라이브 세션 핸들 하나면 쥔 것이다 |
| `aimon.agent.runtimes.max` (gauge) | 이 노드가 들 수 있는 상한 |
| `aimon.agent.runtimes.saturated` (gauge) | 지금 새 테넌트 요청이 거부되면 1 — 슬롯이 회수되면 스스로 내려간다 |
| `aimon.agent.runtimes.exhausted` (counter) | 회수할 것이 없어 거부된 요청 수 |
| `aimon.agent.runtimes.provision.failed` (counter) | 조립에 실패한 테넌트 런타임 수 → **프로비저너나 설정 문제** |

마지막 두 개가 분리되어 있는 이유는 **처방이 전혀 다르기 때문**입니다 — 하나는 용량 문제이고, 다른 하나는
던지는 프로비저너 혹은 만족시킬 수 없는 테넌트 설정입니다.

**`.exhausted` 가 올랐을 때 무엇을 고칠지는 `.active` 와 `.leased` 의 차이가 정합니다.** 거부는 세 가지
전혀 다른 상황에서 똑같이 일어나기 때문입니다.

| `.active` = `.max` 일 때 | 읽는 법 | 처방 |
|---|---|---|
| `.leased` 가 한참 낮다 | 대부분이 **런타임** 유휴 TTL 때문에만 살아 있다 | `aimon.agent-runtime.idle-ttl` 을 줄인다 — 한도를 올려도 같은 벽을 다시 만난다 |
| `.leased` 도 거의 `.max` 이고 턴도 그만큼 돌고 있다 | 슬롯이 전부 실제로 쓰이고 있다 — 거부가 정직하다 | `aimon.agent-runtime.max-entries` 를 올린다 (또는 노드를 늘린다) |
| `.leased` 도 거의 `.max` 인데 턴은 거의 없다 | 라이브 세션 핸들이 캐시에 떠 있느라 리스를 쥐고 있다 | `aimon.session.cache.idle-ttl`(또는 `.max-entries`)을 줄인다 |

`.active` 하나만 보면 이 셋이 구별되지 않고, 처방은 서로 반대 방향입니다.

**리스를 쥐는 것은 턴이 아니라 라이브 세션입니다.** `LiveSession` 핸들은 존재하는 내내 그 테넌트 런타임의
리스를 들고 있으므로(`LeasedLiveSession`), 세션 캐시가 핸들을 축출하기 전까지 런타임 유휴 TTL 은 **시작조차
하지 않습니다**. 두 타이머는 병렬이 아니라 **직렬**이고 기본값이 둘 다 30분이므로, 아무것도 건드리지 않은
배포에서 마지막 턴부터 런타임 회수까지는 60분 이상(스윕 주기만큼 더)입니다. `.leased` 가 높게 붙어 있다면
먼저 볼 것은 런타임 쪽 TTL 이 아니라 `aimon.session.cache.idle-ttl` 입니다.

**거부 자체도 같은 숫자를 나릅니다.** `AgentRuntimeExhaustedException` 메시지가 "N개가 살아 있고 그중
K개가 지금 붙들려 있다" 를 적습니다. 위 게이지들은 스크레이프 간격마다만 찍히는데, 짧게 지나간 포화는 다음
스크레이프가 오기 전에 유휴 항목이 스스로 만료되면서 두 번째 행의 증거가 사라집니다 — 그 순간에 남는 유일한
기록이 이 예외 메시지입니다.

### 13.3 추적과 LLM 사용량

```yaml
aimon:
  tracing:
    enabled: true
    payload-capture: none      # full 은 프롬프트/응답 본문을 남긴다 — PII 주의
    max-chars: 2000
    max-spans: 500
```

토큰/비용 계측은 [llm-usage-metering.md](../features/llm/llm-usage-metering.md) 를 보세요.
`AgentExecutionResult` 자체는 집계 필드를 노출하지 않으므로, 계측은 **`events(...)` 구독** 또는
`LlmClient` 데코레이터에서 `TokenUsage` 를 모읍니다. 완료 사유는 `result.getCompletionReason()` 으로
확인합니다(예산 초과/토큰 상한 도달 판단용).

세션 **누적** 사용량은 `SessionTotals`(턴 수, iteration 수, `TokenUsage`)이며 `SessionRecord` 에
영속되므로 핸들을 다시 열어도 이어집니다.

### 13.4 호출 경로 태깅

`LiveSessionOptions.sourceAgentId` 를 의미 있는 값으로 두면 로그/트레이스에서 경로를 추적하기 좋습니다 —
`"api:/agent/chat"`, `"batch:nightly-summary"`.

### 13.5 `/env` · `/configprops` 의 비밀 마스킹

스타터는 `SanitizingFunction` 빈 하나(`aimonSanitizingFunction`)를 등록해 자기 프리픽스 아래의 비밀을
가립니다. **Boot 가 알아서 해 주지 않기 때문입니다** — `show-values` 가 기본값 `NEVER` 인 동안에는 모든 값이
가려지지만, 운영자가 `ALWAYS` 나 `WHEN_AUTHORIZED` 로 옮기는 순간 Boot 는 마스킹을 멈추고 애플리케이션이
등록한 `SanitizingFunction` 만 적용합니다. 그리고 Boot 3.x 는 **하나도 등록하지 않습니다**(이름으로 가려
준다고 기억하는 `SanitizingFunction.ifLikelyCredential()` 은 그런 함수를 **작성할 때 쓰는 도우미**이지
기본값이 아닙니다). 그 빈이 없으면 `aimon.llm.api-key` 는 운영자가 Boot 의 평소 재량을 기대한 바로 그
순간에 전문이 찍힙니다.

- 규칙은 Boot 의 단어 목록 그대로입니다 — `aimon` 프리픽스 아래에서 `password` · `secret` · `key` ·
  `token` 으로 **끝나거나**, `credentials` 를 **포함**하면 가립니다.
- 그래서 `aimon.credentials.*` 아래는 **리프 이름이 무엇이든** 가려집니다(`username`, `pat`, 무엇이든).
  프로퍼티 트리가 단수 `credential` 이 아니라 복수형인 이유가 이것입니다(§4).
- `aimon.memory.max-tokens` 처럼 비밀처럼 읽히기만 하는 값은 **가리지 않습니다** — 접미사 규칙이라
  `tokens` 로 끝나는 이름은 걸리지 않고, 가려진 반복 한도는 아무에게도 도움이 되지 않습니다.
- 마스킹은 `aimon` 프리픽스에만 겁니다. 값을 보겠다고 말한 운영자에게 **여러분의** 프로퍼티까지 가리는 것은
  묻지 않은 질문에 답하는 일입니다.
- 여러분이 `SanitizingFunction` 을 등록하면 스타터의 것과 **함께** 돕니다(Actuator 가 전부 수집해 순서대로
  적용). 스타터 것을 대체하려면 **빈 이름을 `aimonSanitizingFunction` 으로** 선언해야 합니다 — 타입으로
  물러나게 했다면 여러분이 자기 프로퍼티용 함수를 하나 만든 날 API 키가 조용히 드러났을 것입니다.

---

## 14. Spring 이 아닌 호스트 — `AimonStack`

Quarkus / Micronaut / plain `main` / 배치 워커라면 `aimon-bootstrap` 을 직접 씁니다. 스타터가 하는 일은
결국 **프로퍼티를 `AimonStackSpec` 으로 번역하는 것**이므로, 그 스펙을 손으로 만들면 같은 스택을 얻습니다.

```java
AimonStackSpec spec = AimonStackSpec.builder()
        .workspaceRoot("/var/lib/aimon")
        .llm(LlmSpec.of(myLlmClient))
        .agent(AgentSpec.named("ops"))
        .build();

try (AimonStack stack = AimonStack.from(spec)) {
    stack.start();

    SubmitDisposition d = stack.sessionRouter().submit(/* SubmitRequest */);
    // ...
}
// close() 가 스택이 만든 것을 만든 역순으로 닫는다.
```

이 최소 스펙의 기본값:

| 축 | 기본값 |
|----|--------|
| 파일시스템 | 워크스페이스 루트의 로컬 트리 |
| 세션 저장소 | in-memory `SessionRecordStore` |
| 스킬 승인 | fail-closed (`SkillApprovalSpec::denyAll`) |
| 도구 | 코어 도구 전체 |
| 스케줄링 | 꺼짐 |
| 예산 | `ExecutionBudget::unlimited` ← **프로덕션에서 반드시 바꾸세요** (`.defaultBudget(...)`) |

IMPORTANT: 마지막 줄이 §8 과 **다릅니다**. 스타터의 기본 예산은 유한(20 / 100000 / 120s)하지만, 부트스트랩을
직접 쓰면 프레임워크 기본값인 `unlimited` 를 받습니다 — 유한 기본값은 스타터가 서버라는 것을 알기 때문에
얹는 것이고, `aimon-bootstrap` 은 CLI 도 배치도 서버도 될 수 있으므로 얹지 않습니다. 이 경로에서는
`.defaultBudget(...)` 이 **여러분 몫**입니다.

스펙은 조립 전에 **build() 시점에 거부하는 것들**이 있습니다 — 에이전트 0개, 같은 런타임 정체성으로
해석되는 에이전트 2개(`agent:<name>[:<discriminator>]` 충돌 — 두 번째 스펙의 번들·파일시스템·도구가 조용히
버려질 자리입니다), 그리고 서로 대안인 쌍을 둘 다 준 경우(`knowledgeStore`/`knowledgeStoreFactory`,
`credentialStore`/`credentialStoreFactory`, `MemorySpec` 의 저장소/`ExecutorSpec.memoryContextProvider`).
전부 "요청 시점의 미스터리"를 "빌드 시점의 메시지"로 바꾸려는 것입니다.

IMPORTANT: **스택은 자기 자신만 닫습니다.** 접근자(`sessionRouter()`, `agentExecutor()`,
`sessionRecordStore()`, `runtime(id)` …)로 도달하는 모든 것은 **빌려온 것**이며, 여러분이 닫으면 §12 의
버그가 그대로 재현됩니다.

라우터를 직접 부르는 경우 **예산을 직접 붙여야 한다**는 점에 주의하세요 —
`LiveSessionOptions.defaults()` 의 예산은 unlimited 입니다(§6.1). 스타터의 `AimonSessions` 가 채워 주던
것을 여기서는 여러분이 채웁니다.

---

## 15. 임베딩 체크리스트

### 스타터 경로

- [ ] `aimon.workspace.root` / `aimon.llm.api-key` / `aimon.agent-defaults.default-agent` 세 개를 설정했다.
- [ ] LLM 벤더 모듈(`aimon-llm-anthropic` 또는 `aimon-llm-openai`)을 **직접** 의존성에 넣었다.
- [ ] `AimonSessions` 를 주입해서 쓴다 — `SessionRouter` 에 직접 요청을 손으로 만들지 않는다.
      (만든다면 `newRequest(...)` 에서 시작한다.)
- [ ] `aimon.budget.*` 기본값(20 / 100000 / 120s)이 이 워크로드에 맞는지 확인했다 — 미설정은 unlimited 가
      아니라 이 유한 기본값이다.
- [ ] 기동 로그의 degradation 목록을 확인했고, 그중 의도한 것이 무엇인지 안다.
- [ ] `AimonStack` 에서 꺼낸 객체를 **닫지 않는다**.

### 세션 사용

- [ ] `SessionId` 를 여러분의 도메인(사용자 + 스레드)에서 결정론적으로 만든다.
- [ ] 대화 종료는 `release(sessionId)` — 히스토리까지 지우려면 `SessionRouter.deleteSession`.
- [ ] 취소는 `interrupt(sessionId, turnId, reason)` 로 **턴에 겨눈다** (`turnId` 는 disposition 에서).
- [ ] `!result.isSuccess()` 를 확인하고 `getErrorMessage()` 로 사용자 응답을 만든다
      (`AgentExecutionResult` 에는 `isError()` 가 없고, 두 게터는 `Optional` 이 아니라 **nullable String**).

### 다중 에이전트 / 테넌트

- [ ] `AimonAgentCustomizer` 구현이 **thread-safe** 하다 (테넌트 런타임 조립 시 동시 호출된다).
- [ ] `aimon.agent-runtime.max-entries` 를 이 노드가 감당할 값으로 설정했다.
- [ ] `aimon.agent.runtimes.exhausted` / `.provision.failed` 에 알람을 걸었다.
- [ ] 그 알람이 깨웠을 때 볼 수 있도록 `aimon.agent.runtimes.active` 와 `.leased` 를 **같은 그래프에**
      올렸다 — 둘의 차이가 한도를 올릴지 TTL 을 줄일지를 정한다.
- [ ] `.leased` 가 높을 때 줄여야 하는 것이 `aimon.session.cache.idle-ttl` 이라는 것을 안다 — 라이브 세션
      핸들이 리스를 쥐고 있는 동안에는 런타임 유휴 TTL 이 시작하지 않는다(§13.2).

### 멀티 인스턴스

- [ ] `aimon.session.mode: distributed` + 분산 `store` + 그 백엔드 모듈을 의존성에 넣었다.
- [ ] `aimon.session.node-id` 가 인스턴스마다 다르다.
- [ ] sticky routing 에 의존하지 않는다 (`FORWARDED` disposition 이 정상 경로다).

### 관측

- [ ] Actuator 를 쓴다면 `/actuator/health` 의 `degradations` 를 대시보드에 노출했다.
- [ ] LLM 사용량을 `events(...)` 구독 또는 `LlmClient` 데코레이터에서 수집한다.
- [ ] `LiveSessionOptions.sourceAgentId` 를 의미 있는 값으로 설정했다.

---

## 부록 A. 수동 배선 — 스타터를 쓰지 않을 때

이 부록은 **스타터도 `AimonStack` 도 쓸 수 없을 때**만 필요합니다. 조립의 형태 자체를 바꿔야 하거나,
이미 손으로 배선된 기존 코드를 읽어야 할 때 참고하세요. 여기 나오는 함정들은 **스타터 경로에서는
발생하지 않습니다**(§3.4).

### A.1 최소 예제

```java
public final class MinimalEmbeddingExample {

    public static void main(String[] args) {
        // 1) Application-scoped 싱글턴들 (실무에서는 DI 컨테이너가 관리)
        LlmClient llmClient = /* e.g., new OpenAILlmClient(...) */;
        SessionRecordStore sessionRecords = new InMemorySessionRecordStore();
        TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecords);
        OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
                .create(llmClient, transcriptManager);

        Agent agent = /* 여러분의 agent 정의 로드 */;
        AgentRegistry agentRegistry = new DefaultAgentRegistry();
        agentRegistry.register(agent);

        VirtualFileSystem fileSystem = /* LocalFileSystem or GridFs... */;
        CredentialStore credentialStore = /* InMemoryCredentialStore */;
        ScheduledTaskManager scheduledTaskManager = /* ... */;

        // 부트스트랩 1회: agent별 runtime 등록 (AgentRuntimeId = "agent:<name>")
        OrcaAgentRuntimeManager manager = OrcaAgentRuntimeManager.builder()
                .agentExecutor(executor)
                .scheduledTaskManager(scheduledTaskManager)
                .agentRuntimeFactory(new OrcaAgentRuntimeFactory())
                .build();
        AgentBundle bundle = AgentBundle.builder().agent(agent).build();
        manager.getOrCreateRuntime(bundle, fileSystem, credentialStore);

        // contextBuilder 는 멱등해야 한다 — 세션마다 새 runtime 을 만들면 안 되고,
        // 이미 등록된 agent-scoped runtime 을 되돌려줘야 한다.
        LiveSessionFactory factory = new LiveSessionFactory(agentRegistry,
                a -> manager.getOrCreateRuntime(AgentBundle.builder().agent(a).build(), fileSystem, credentialStore),
                executor,
                sessionRecords);

        try (LiveSession session = factory.open(
                SessionId.generate(), agent.getName(), LiveSessionOptions.defaults())) {

            AgentExecutionResult result = session.submit("Hello, what tools do you have?");
            System.out.println(result.isSuccess() ? result.getFinalAnswer() : "(error) " + result.getErrorMessage());
        }
        // session.close() → 핸들 자원만 정리.
        // SessionRecord(영속), OrcaAgentRuntime(agent-scoped), executor, scheduling 컴포넌트는 모두 살아있다.
    }
}
```

> `LiveSessionFactory` 의 3-인자 생성자는 `SessionRecordStore` 없이 세션을 엽니다 — 그러면
> `SessionTotals` 와 budget override 가 **핸들과 함께 사라져** 재개 후 복원되지 않습니다. 위처럼
> **4-인자 생성자**를 쓰세요.

### A.2 Spring Boot 에서 손으로 배선하기

<details>
<summary>@Configuration 전체 (펼치기)</summary>

```java
@Configuration
public class AgentConfiguration {

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        return new OpenAILlmClient(props.toOpenAIConfig());
    }

    @Bean
    public SessionRecordStore sessionRecordStore() {
        return new InMemorySessionRecordStore();
    }

    @Bean
    public TranscriptManager transcriptManager(SessionRecordStore sessionRecordStore) {
        return new DefaultTranscriptManager(sessionRecordStore);
    }

    @Bean
    public MessageQueueManager messageQueueManager() {
        return new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
    }

    @Bean
    public OrcaAgentExecutor orcaAgentExecutor(LlmClient llmClient,
            TranscriptManager transcriptManager,
            MessageQueueManager messageQueueManager) {
        // OrcaAgentExecutorFactory 의 withXxx 는 자기 자신을 변조하고 this 를 되돌려준다.
        // 따라서 싱글턴 빈으로 공유하지 말고 여기서 지역 생성한다.
        return new OrcaAgentExecutorFactory()
                .withMessageQueueManager(messageQueueManager)
                .create(llmClient, transcriptManager);
    }

    @Bean(destroyMethod = "close")
    public SchedulingEngine schedulingEngine(AgentRuntimeRegistry registry) {
        // AgentRuntimeRegistry 는 엔진 바깥에서 만들어 주입한다 — 엔진이 소유하지 않으므로
        // engine.close() 가 registry 를 닫지 않는다.
        SchedulingEngine engine = SchedulingEngineBuilder.create()
                .agentRuntimeRegistry(registry)
                .build();
        engine.start();
        return engine;
    }

    @Bean
    public ScheduledTaskManager scheduledTaskManager(SchedulingEngine engine) {
        // 엔진이 소유한 task manager 를 그대로 노출한다 — 별도로 만들면 두 개가 갈린다.
        return engine.getTaskManager();
    }

    @Bean
    public AgentRegistry agentRegistry(AgentBundleLoader loader, AgentProperties props) {
        DefaultAgentRegistry registry = new DefaultAgentRegistry();
        for (String name : props.getAgents()) {
            registry.register(loader.load(name).getAgent());
        }
        return registry;
    }

    @Bean
    public AgentRuntimeRegistry agentRuntimeRegistry() {
        return new DefaultAgentRuntimeRegistry();
    }

    @Bean
    public OrcaAgentRuntimeManager agentRuntimeManager(OrcaAgentExecutor executor,
            ScheduledTaskManager scheduledTaskManager,
            AgentRuntimeRegistry agentRuntimeRegistry) {
        // registry 는 스케줄링 엔진과 공유해야 하므로 반드시 명시해서 같은 인스턴스를 넘긴다.
        return OrcaAgentRuntimeManager.builder()
                .agentExecutor(executor)
                .scheduledTaskManager(scheduledTaskManager)
                .agentRuntimeFactory(new OrcaAgentRuntimeFactory())
                .agentRuntimeRegistry(agentRuntimeRegistry)
                .build();
    }

    @Bean
    public ApplicationRunner registerAgentRuntimes(OrcaAgentRuntimeManager manager,
            AgentBundleLoader loader,
            AgentProperties props,
            VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        return args -> {
            for (String name : props.getAgents()) {
                manager.getOrCreateRuntime(loader.load(name), fileSystem, credentialStore);
                // → AgentRuntimeId = "agent:<name>"
            }
        };
    }

    @Bean
    public LiveSessionFactory liveSessionFactory(AgentRegistry agentRegistry,
            OrcaAgentRuntimeManager manager,
            OrcaAgentExecutor executor,
            SessionRecordStore sessionRecordStore,
            VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        return new LiveSessionFactory(agentRegistry,
                agent -> manager.getOrCreateRuntime(
                        AgentBundle.builder().agent(agent).build(), fileSystem, credentialStore),
                executor,
                sessionRecordStore);
    }
}
```

</details>

> **팩토리가 주입하지 않는 것**: `LiveSessionFactory.open(...)` 은 내부에서
> `new DefaultLiveSession(sessionId, runtime, executor, options, null, null, sessionRecords)` 를
> 호출합니다 — `SessionRecordStore` 는 주입되지만 **`MessageQueueManager` 와 `HookExecutionManager` 는
> `null`** 입니다. 큐 auto-enqueue 나 `OnSessionStart`/`OnSessionEnd` 훅이 필요하면 **7-인자
> `DefaultLiveSession` 생성자를 직접 호출**해야 합니다(A.4). 스타터는 이미 7-인자 생성자를 씁니다.

### A.3 세션 모델 선택하기

세 패턴 모두 **`SessionId` 는 영속**이고, 달라지는 것은 `LiveSession` 핸들을 얼마나 오래 들고 있느냐입니다.

| 모델 | 형태 | 적합 | 주의 |
|------|------|------|------|
| **A — 요청당 핸들** | 한 HTTP 요청 = 한 핸들 | REST, GraphQL resolver, 배치, serverless | 핸들 open/close 비용이 매 요청. (agent-scoped runtime 은 재사용되므로 MCP/Knowledge 초기화는 반복되지 않음) |
| **B — 스레드당 핸들** | 대화가 열려 있는 동안 유지 (WebSocket/SSE) | 채팅 UI | 유휴 만료 정책 필요. 한 핸들은 **한 번에 한 턴** |
| **C — 싱글턴 핸들** | 앱 시작 시 1회 open | 크론 / worker / dev sandbox | `SessionId` 하나뿐 — 멀티유저에 부적합 |

```java
// Model B
@Component
public class ChatSessionRegistry {

    private final LiveSessionFactory factory;
    private final Map<SessionId, LiveSession> handles = new ConcurrentHashMap<>();

    public LiveSession getOrOpen(SessionId id, String agentRef, LiveSessionOptions opts) {
        return handles.computeIfAbsent(id, sid -> factory.open(sid, agentRef, opts));
    }

    public void close(SessionId id) {
        LiveSession s = handles.remove(id);
        if (s != null) s.close();
    }

    @PreDestroy
    public void closeAll() {
        handles.values().forEach(LiveSession::close);
        handles.clear();
    }
}
```

**같은 `SessionId` 로 동시에 두 핸들을 열지 마세요** — 두 핸들이 같은 `SessionRecord` 를 경쟁 수정합니다.
시간에 걸쳐 여러 핸들이 **순차로** 같은 `SessionId` 를 서빙하는 것은 정상이며(재시작, 노드 이동), 그때
히스토리와 누적치는 `SessionRecordStore` 에서 복원됩니다. 서로 다른 `SessionId` 의 동시 실행은 허용됩니다.

### A.4 큐 활성 핸들과 드레인

busy/idle 판정은 **`offerAsync` 의 `SubmitOutcome`** 으로 합니다 — `status()` 가 돌려주는
`LiveSessionStatus` 는 best-effort 관찰 스냅샷이며 제어 게이트가 아닙니다.

```java
SubmitOutcome outcome = session.offerAsync(input, listener);
switch (outcome.getKind()) {
    case EXECUTED -> outcome.getResultStage().orElseThrow().whenComplete(this::renderOrError);
    case QUEUED -> notifyClient("큐에 추가됨. 현재 큐 깊이: " + outcome.getQueuePosition());
}
```

- `QUEUED` → 입력이 `QueuedInputPriority.NEXT` 로 큐에 들어갑니다. **세션이 자동으로 드레인하지
  않습니다** — 진행 중인 턴이 끝난 뒤 호스트가 드레인해야 listener 로 이벤트가 흘러옵니다.
- `getQueuePosition()` 은 enqueue 직후 관측한 **큐 깊이**입니다. 프로듀서가 여럿이면 상한 추정치로만
  취급하세요.
- `offerAsync` 로 enqueue 되려면 세션이 `MessageQueueManager` 와 함께 생성되어야 합니다. 큐가 없으면
  `offerAsync` 는 항상 `EXECUTED` 를 돌려주고 턴이 겹칩니다.

큐를 붙이려면 7-인자 생성자를 직접 부릅니다 — 5-인자 생성자를 쓰면 큐는 붙지만 `SessionRecordStore` 가
빠져 영속 상태를 잃습니다.

```java
public LiveSession open(SessionId sessionId, String agentRef, LiveSessionOptions opts) {
    Agent agent = agentRegistry.findByName(agentRef)
            .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentRef));
    // contextBuilder 는 agent-scoped runtime 을 되돌려준다 — 세션마다 새로 만들지 않는다.
    OrcaAgentRuntime runtime = Objects.requireNonNull(contextBuilder.build(agent));
    return new DefaultLiveSession(sessionId, runtime, executor, opts,
            messageQueueManager, null, sessionRecords);
}
```

`OrcaAgentExecutorFactory.withMessageQueueManager(...)` 로 **같은 큐 인스턴스**를 실행자에도 주입해야
합니다(CQ-04). 턴 종료 후 드레인(CQ-05)은 호스트 책임입니다.

```java
// LiveSession 은 runtime 을 노출하지 않으므로, 필터에 쓸 id 는 호스트가 들고 있어야 한다.
AgentRuntimeId agentRuntimeId = AgentRuntimeId.from(agent);

List<QueuedInput> drained = messageQueueManager.drainForInjection(
        q -> agentRuntimeId.equals(q.getAgentRuntimeId()), QueuedInputPriority.LATER);
for (QueuedInput entry : drained) {
    session.submit(entry.getInputText());
}
```

- 두 번째 인자는 **포함할 가장 낮은 우선순위 tier** 입니다(`NOW` → `NEXT` → `LATER`). `LATER` 를 주면
  전부 걷어옵니다.
- 재입력 루프가 자기 자신을 다시 호출하지 않도록 재진입 가드를 두세요 (`ReplSession.draining` 참조).

### A.5 런타임 예산 교체

`LiveSession` 인터페이스에는 없고 `DefaultLiveSession` 에만 있는 확장 API 입니다.

```java
DefaultLiveSession live = (DefaultLiveSession) session;
live.setOptions(live.getOptions().withBudget(tighterBudget));  // SessionRecord 에 override 로 영속
live.clearBudgetOverride();                                     // opener 기본값으로 복귀
```

`setOptions` 로 넣은 override 는 `SessionRecordStore` 에 함께 쓰이므로 **핸들을 다시 열어도 유지**됩니다.
되돌리려면 `clearBudgetOverride()` 를 명시적으로 호출해야 합니다.

### A.6 손 배선 체크리스트

- [ ] `OrcaAgentExecutor` 를 애플리케이션 싱글턴으로 선언했다.
- [ ] `SchedulingEngine` 을 `@Bean(destroyMethod = "close")` 로 **한 번만 닫히도록** 관리한다.
- [ ] `AgentRuntimeRegistry` 를 `SchedulingEngine` 과 `OrcaAgentRuntimeManager` 가 **같은 인스턴스**로
      공유한다.
- [ ] `LiveSessionFactory` 를 **`SessionRecordStore` 를 받는 4-인자 생성자**로 만들었다.
- [ ] `ContextBuilder` 가 세션마다 새 runtime 을 만들지 않고 **agent-scoped runtime 을 되돌려준다**.
- [ ] `try-with-resources` 로 핸들을 반드시 닫고, `close()` 이후 submit 하지 않는다
      (`IllegalStateException: LiveSession has already been closed`).
- [ ] 한 핸들을 여러 스레드가 동시에 `submit` 하지 않는다.
- [ ] 큐가 필요하면 7-인자 `DefaultLiveSession` + `withMessageQueueManager` 로 **양쪽에 같은 큐**를
      주입하고, 턴 종료 후 드레인 로직을 두었다.

---

## 부록 B. 옛 이름 매핑

이 문서에 나오던 옛 타입 이름은 모두 개명되었습니다.

> **0.1.x 앱을 통째로 옮기는 중이라면 이 표만으로는 모자랍니다.** 이름을 바꾸는 것과 *세션마다
> `AgentRuntime` 을 새로 만들던 배선을 걷어내는 것*은 다른 일이고, 후자의 절차 —
> Before / After 코드와 "Spring 호스트라면 그 배선은 옮기는 게 아니라 지운다" 까지 — 는
> [`agent-runtime-scope.md` §4 마이그레이션](../design/agent-execution/agent-runtime-scope.md#4-마이그레이션)
> 가 단일 기준입니다. 아래 표는 그 절을 읽을 때 쓰는 사전입니다.

| 옛 이름 | 현재 이름 |
|---------|----------|
| `Conversation` | `SessionRecord` |
| `ConversationId` | `SessionId` |
| `ConversationRepository` | `SessionRecordStore` |
| `ConversationManager` | `TranscriptManager` |
| `AgentSession` | `LiveSession` |
| `AgentSessionFactory` | `LiveSessionFactory` |
| `AgentSessionOptions` | `LiveSessionOptions` |
| `DefaultAgentSession` | `DefaultLiveSession` |
| `AgentExecutionContext` | `AgentRuntime` |

IMPORTANT: **`Session` 과 `AgentSession` 은 타입 이름으로 쓸 수 없습니다** — 두 수명이 서로를 사칭하게
만드는 이름이라 `SessionNamingArchitectureTest` (`aimon-session-routing`) 가 빌드에서 막습니다.

반면 **영속된 이름은 의도적으로 동결**되어 그대로입니다 — Mongo 컬렉션 `conversation_*`, Postgres
테이블·채널 `conversation_*`, 와이어 키 `"conversationId"` / `"invokingConversationId"`. Java 식별자만
개명되었으므로 어긋나 보이는 것이 정상입니다.

"conversation" 이라는 단어 자체는 폐기어가 아닙니다 — **LLM 과의 메시지 교환**을 뜻하는 자리에는 그대로
남아 있습니다(`getConversationHistory()`, "Conversation compacted"). 수명을 뜻하는 데만 쓰지 마세요.

---

## 관련 문서

- [`docs/design/integration/spring-boot-starter.md`](../design/integration/spring-boot-starter.md) — 스타터 설계 전문
- [`docs/overview/scope-model.md`](../overview/scope-model.md) — 수명·소유권·소멸 책임의 전체 규칙
- [`docs/overview/glossary.md`](../overview/glossary.md) — 용어별 정의와 수명 사전
- [LiveSession 개발 가이드](../features/session/agent-session-guide.md) — 핸들 API / 라이프사이클 / close 규칙
- [LiveSession 튜토리얼](../features/session/agent-session-tutorial.md) — 입문자용 단계별 실습
- [Command Queue 가이드](../features/agent-execution/command-queue-guide.md) — CQ-01 ~ CQ-06, `MessageQueueManager` 동작
- [Hook 개발 가이드](../features/hook/hook-development-guide.md) — Pre/Post tool hook, 이벤트 interceptor
- [LLM Provider 개발 가이드](../features/llm/llm-provider-development-guide.md) — 자체 LLM 클라이언트 추가
- [LLM 사용량 미터링](../features/llm/llm-usage-metering.md) — 토큰/비용 관측
- [Tool 개발 가이드](../features/tool/tool-development-guide.md) — 커스텀 Tool 추가
- [`samples/aimon-sample-app`](../../samples/aimon-sample-app) — 동작하는 스타터 앱
