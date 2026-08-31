# AIMON Spring Boot Starter — 조립 지식을 프레임워크 중립 층으로 꺼낸다

> Status: **IMPLEMENTED** — `aimon-bootstrap`(main 40파일 / test 11) +
> `aimon-spring-boot-starter`(main 29파일 / test 14) + `aimon-bom`.
> 호스트가 주입받는 공개 표면은 `AimonSessions` · `AimonAgents` 둘뿐이고, 그 아래는 전부
> `AimonStack` 한 덩어리로 조립된다. 남은 것은 §9.

서버 애플리케이션이 `aimon-core` 를 **의존성 추가 + 프로퍼티 몇 줄**로 임베딩할 수 있게 하는 3계층
조립 설계다. 계층 경계는 원칙 하나에서 나온다 — **`aimon-core` 에는 Spring 의존성을 넣지 않는다.**
DIP 상 코어가 프레임워크를 알아서는 안 되므로, 조립 지식은 프레임워크 중립 모듈
(`at.aimon.bootstrap`)에 두고 Spring 바인딩(`at.aimon.spring.boot`)은 그 위에 얇게 얹는다.

---

## 1. 개요

### 1.1 목적

서버 애플리케이션이 `aimon-core` 를 **의존성 추가 + 프로퍼티 몇 줄**로 임베딩할 수 있게 한다.
현재는 동작하는 에이전트 하나를 띄우기 위해 약 45개 협력자를 순서대로 `new` 해야 하고, 그 순서 지식은
`aimon-cli` 의 `AgentSetupFactory.create(CliConfig)` 한 메서드(216줄, `@SuppressWarnings("checkstyle:MethodLength")`)
안에만 존재한다. 그리고 `aimon-cli` 는 Maven Central 에 퍼블리시되지 않는다.

목표 상태:

```yaml
# application.yml — 이것으로 동작하는 단일 노드 에이전트가 뜬다
aimon:
  workspace:
    root: /var/lib/myapp/agent
  agents:
    default: {}                  # 번들 이름 = 키. 더 필요하면 여기에 항목을 추가한다 (§4.11)
  llm:
    provider: anthropic
    api-key: ${ANTHROPIC_API_KEY}
  budget:
    max-iterations: 20
    max-wall-clock: 60s
```

```java
@RestController
class ChatController {
    private final AimonSessions sessions;   // 스타터가 제공하는 유일한 진입점

    @PostMapping("/chat")
    ChatResponse chat(@RequestBody ChatRequest req, Principal principal) {
        return ChatResponse.from(sessions.submit(SessionId.of(principal.getName()), req.getInput()));
    }
}
```

에이전트가 여럿이거나 테넌트별로 갈라야 하면 같은 파사드에 `agentRef` 와 discriminator 를 얹는다 —
새 API 가 아니라 같은 메서드의 오버로드다(§4.6, §4.11).

```java
sessions.submit(sessionId, "ops-agent", req.getInput(),
                LiveSessionOptions.builder().discriminator(tenantId).channel("slack").build());
```

### 1.2 배경 — "가능한가"에 대한 답

**가능하다.** 코어가 프레임워크 임베딩을 막는 구조적 결함은 없다.

| 임베딩 가능성 판정 근거 | 확인 내용 |
|------------------------|----------|
| 프로세스 제어 없음 | `aimon-core` main 소스에 `System.exit` 호출이 없다 |
| 가변 전역 상태 없음 | **모든 모듈의 main 트리에 non-final static 필드가 0건**이다. static 은 전부 final 이며, 가변 컨테이너는 `BackendType.CACHE`(값 인터닝) 하나뿐 — 두 Spring 컨텍스트가 서로를 오염시키지 않는다 |
| 모든 확장점이 인터페이스 | `LlmClient` / `VirtualFileSystem` / `*Store` / `Tool` / `Hook` 전부 SPI |
| in-memory 기본 구현 존재 | `InMemorySessionRecordStore` 등, 외부 인프라 없이 기동 가능 |
| Java 17 | Spring Boot 3.x / 4.x 베이스라인과 호환 (Boot 4.0 도 Java 17+) |
| 이미 서버 계층이 존재 | `aimon-session-routing` 의 `SessionRouter` 가 멀티 노드 라우팅을 담당 |
| **fat jar 리소스 열거가 동작** | `ClasspathResourceTreeWalker` 가 Boot 신·구 로더 양쪽에서 정상 동작함을 **실제 repackage 된 jar 로 실증**했다(§4.8) |
| TCCL 안전 | `getContextClassLoader()` 사용처 6곳 전부 명시 ClassLoader 오버로드를 가지며, Boot 로더 하에서 동일 로더로 resolve 된다 |
| 리플렉션 표면 최소 | `ServiceLoader` 0건, `Proxy.newProxyInstance` 0건이고 aimon 타입은 Spring 빈이 아니라 손으로 만들어진다 — **일반 JVM Boot 앱에는 AOT 힌트가 불필요**하다. native 이미지에만 힌트가 필요하며 그것을 `AimonRuntimeHints` 가 등록한다(§4.2) |
| 데몬 스레드 규율 | 코어가 만드는 장수명 스레드는 **전부** 명시적 데몬이다. 비-데몬은 Quartz 와 Playwright 두 곳뿐(§2.4) |

**그러나 "쉽다"는 아니다.** 스타터를 막는 실제 장애물이 여섯 부류로 나뉜다.

1. **소유권 함정** — Spring 의 `@Bean` 기본 `destroyMethod = "(inferred)"` 는 **public 무인자 `close()`
   또는 `shutdown()`** 을 찾아 자동으로 호출한다. 코어가 그런 메서드를 가진 타입을 대량으로 노출하므로,
   `OrcaAgentRuntime` 이나 `LiveSession` 을 무심코 빈으로 노출하면
   [`scope-model.md` §4](../../overview/scope-model.md) 가 금지한 소멸이 자동으로 일어난다(§2.1).
2. **침묵하는 기본값** — 빠뜨렸을 때 예외가 아니라 *기능이 조용히 사라지는* 지점이 많다(§2.2).
3. **호스트 환경 가정** — 작업 디렉토리를 jar 위치/`user.dir` 에서 파생하고, `user.home` 을 직접 읽고,
   부트스트랩 중 디스크에 쓴다. 컨테이너에서는 전부 틀린 위치다(§2.3).
4. **기동/종료 순서** — 코어의 순서 제약은 **양방향**이고, 어기면 한쪽은 예외를 던지고 다른 쪽은
   조용히 실패한다(§2.4).
5. **조립 지식이 CLI 에 갇혀 있음** — 서버가 필요로 하는 배선의 대부분이 published 되지 않는
   `aimon-cli` 안에 있다(§2.5).
6. **빌드 규칙 충돌** — 프로젝트 규칙이 요구하는 `implementation` 은 published POM 에서 스타터
   소비자의 컴파일을 깨뜨린다(§2.6).

이 문서의 핵심 주장은 **5번을 먼저 해결해야 나머지가 풀린다**는 것이다 — Spring 애노테이션은
쉬운 부분이고, 진짜 일은 조립 지식을 프레임워크 중립 모듈로 꺼내는 것이다(§3.2).

### 1.3 사용 시나리오

```
시나리오 A — 최소 REST 챗 (단일 노드, in-memory)
  1. build.gradle 에 aimon-spring-boot-starter + aimon-llm-anthropic 추가.
  2. application.yml 에 workspace.root / agents.<name> / llm.* / budget.* 5개 키.
  3. AimonSessions 를 주입받아 submit(sessionId, text) 호출.
  → 재시작하면 대화 이력이 사라진다. 개발/PoC 용도.

시나리오 B — 영속 세션 (단일 노드)
  1. A + aimon-session-postgres, spring.datasource.* 설정.
  2. aimon.session.store=postgres.
  → SessionRecordStore 가 교체되어 재시작 후에도 대화가 이어진다.

시나리오 C — 멀티 인스턴스 (스케일아웃)
  1. B + aimon.session.mode=distributed, aimon.session.node-id=${HOSTNAME}.
  2. 리스/시그널/인박스/멱등 SPI 가 백엔드 구현으로 교체되고 SessionRouter 가 홀더 노드로 포워딩.
  → 스티키 라우팅 없이 N개 파드. 스키마는 운영자가 선적용(§4.9).

시나리오 D — 기존 수동 배선 앱의 점진 이관
  1. 스타터를 추가하되 이미 정의된 @Bean 이 있으면 그것이 이긴다(@ConditionalOnMissingBean).
  2. 슬라이스 단위로 선택자 프로퍼티를 none 으로 두어 직접 배선을 유지할 수 있다.

시나리오 E — 다중 에이전트 · 다중 테넌트 (내부 운영 앱 형태)
  1. C + aimon.agents.<name> 를 여러 개 선언하거나 agent-defaults.discover-bundles 로 자동 발견.
  2. 에이전트별 도구/훅은 AimonAgentCustomizer 를 @Component 로 여러 개 등록 (List<> 수집).
  3. 테넌트는 submit 시 discriminator 로 분기 → AgentRuntimeId=agent:<name>:<tenant>.
  4. 에이전트 정의가 DB 에서 바뀌면 AimonAgents.invalidate(agentRef, tenant).
  → 런타임은 지연 생성되고 유휴 시 축출된다. 워크스페이스·자격증명은 두 축 모두로 격리(§4.11).
```

### 1.4 핵심 설계 원칙

- **코어에 Spring 을 넣지 않는다** — 의존 방향을 뒤집지 않는다. Spring 지식은 최상위 스타터에만.
- **조립은 프레임워크 중립 층에서** — Spring 자동설정은 *프로퍼티 → 스펙 → `AimonStack`* 을 잇는
  얇은 바인딩일 뿐, 조립 로직을 갖지 않는다(§3.2). Quarkus/Micronaut/plain SDK 가 같은 층을 재사용한다.
- **소멸 책임은 하나** — Spring 이 닫는 것은 `AimonStack` **하나뿐**이다. 나머지는 전부
  `destroyMethod = ""` 로 노출한다(§6 D4). 이것이 [`scope-model.md`](../../overview/scope-model.md) 의
  "만든 쪽이 닫는다"를 DI 컨테이너 위에서 지키는 유일한 방법이다.
- **순서는 코드로, 애노테이션으로 흩지 않는다** — 기동은 `SmartLifecycle` 2단, 종료는
  `AimonStack.close()` 한 메서드(§4.5, §5.3).
- **침묵하는 기본값 금지** — 코어가 조용히 degrade 하는 자리는 스타터가 **시끄럽게** 만든다.
  기동 실패 또는 최소한 WARN + Actuator health indicator.
- **보수적 기본값** — 예산 유한, `Bash` off, 스킬 승인 거부, 셸 훅 off. 서버 기본값은 CLI 기본값과 다르다.
- **선택자 프로퍼티, 불리언 아님** — `aimon.llm.provider=anthropic|openai|none` 식.
  N개 불리언은 "이 중 정확히 하나"를 표현하지 못한다(§6 D2).
- **자동설정은 대체 가능해야 한다** — 모든 빈에 `@ConditionalOnMissingBean`. 호스트가 정의하면 호스트가 이긴다.
- **에이전트는 하나라고 가정하지 않는다** — 에이전트(`agentRef`)와 테넌트(`discriminator`)는 **다른 축**이고,
  자원 격리는 전역 빈이 아니라 팩토리로 표현한다(§4.11, §6 D14·D15).

### 1.5 용어 정의

| 용어 | 정의 |
|------|------|
| **슬라이스(slice)** | 하나의 `@AutoConfiguration` 클래스가 담당하는 기능 단위(LLM, 세션, 스케줄링 …). |
| **`AimonStack`** | 애플리케이션 스코프 협력자 전체를 담고 **순서 있는 `close()`** 를 제공하는 프레임워크 중립 조립체(§4.1). |
| **`AimonStackSpec`** | `AimonStack` 을 무엇으로 조립할지 기술하는 불변 값. Spring 프로퍼티는 이것으로 번역된다. |
| **`AimonSessions`** | 호스트가 턴을 실행할 때 쓰는 유일한 파사드. 내부적으로 `SessionRouter` 를 감싼다(§4.6). |
| **`AimonAgents`** | 설정된 에이전트를 조회하고 런타임을 무효화하는 파사드(§4.11). |
| **`agentRef`** | 에이전트를 고르는 논리 이름. `aimon.agents.<이 이름>` 의 키이며 `AgentRuntimeId` 의 `<name>` 부분이 된다(§4.11). |
| **`discriminator`** | 같은 에이전트를 테넌트/사용자별로 쪼개는 축. `AgentRuntimeId` 의 세 번째 세그먼트다. `agentRef` 와 **다른 축**이다(§4.11). |
| **빌린 빈(borrowed bean)** | 호스트 앱이 소유하고 스타터가 주입만 받는 빈(`DataSource`, `org.quartz.Scheduler` …). 절대 닫지 않는다. |

용어의 수명 규칙(session / live session / turn / execution)은 이 문서에서 재정의하지 않는다 —
[`glossary.md`](../../overview/glossary.md) 를 따른다. 특히 **`Session` / `AgentSession` 이라는 타입 이름은
금지**되므로(ArchUnit `SessionNamingArchitectureTest`), 스타터가 새로 만드는 타입도 그 규칙을 지킨다.

---

## 2. 조립이 어려운 이유 — 스타터가 흡수한 함정들

수동 배선이 어려운 것은 협력자가 많아서가 아니다. **틀렸을 때 예외가 아니라 침묵이 나오기 때문**이다.
아래 여섯 부류가 §4 의 구조와 §6 의 결정 대부분을 직접 낳았다.

### 2.1 소유권 — Spring 이 닫으면 안 되는 것을 닫는다

`@Bean` 의 기본 `destroyMethod = "(inferred)"` 는 **public 무인자 `close()` 또는 `shutdown()`** 을 찾아
호출한다(상속 계층 어디에 있든, 반환 타입과 무관하게). 따라서:

> **판정 기준은 `implements AutoCloseable` 이 아니라 "public 무인자 `close()` 또는 `shutdown()` 이
> 있는가"다.**

`AutoCloseable` 이 아니면서 `shutdown()` 때문에 Spring 이 자동으로 닫아 버리는 것들이 있다 —
`RoutineExecutor`, `InMemoryTaskScheduler`, `QuartzTaskScheduler`. 그런데 `SchedulingEngine.close()` 가
이미 앞의 둘을 닫으므로, 그것들을 별도 빈으로 노출하면 **이중 소멸**이 되고 그 순서를 Spring 이
보장하지 않는다.

좁은 스코프에서 닫는 것이 명시적으로 금지된 것들:

| 타입 | 금지 근거 |
|------|----------|
| `OrcaAgentRuntime` | agent-scoped. 닫으면 같은 에이전트의 다른 세션의 MCP 서브프로세스가 끊긴다 |
| `LiveSession` | `AgentRuntime` 으로 cascade 금지. 게다가 **생성자가 `OnSessionStart` 훅을 발화한다**(§4.6) |
| `SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor` | application-scoped. `AgentRuntime` 소멸과 함께 닫으면 안 됨 |
| `AgentRuntimeRegistry` | `SchedulingEngine` 바깥에서 만들어 주입 — 엔진이 소유하지 않음 |

반대로 **닫히지 않아 새는 것**도 있다.

- `OrcaAgentRuntime.close()` 는 정확히 **세 개**(`mcpClientManager`, `workflowRunner`, `ownedShell`)만
  닫는다 — 그중 `ownedShell` 은 어셈블리가 `withShell(...)` 로 셸을 주지 않아 런타임이 직접 만든
  경우에만 non-null 이다. agent-scoped `VirtualFileSystem` 도, `ToolRegistry` 가 쥔 `AutoCloseable`
  도구도 닫지 않는다 — 예를 들어 `BashTool` 은 캐시 스레드풀을 소유하고 `close()` 와 `shutdown()` 을
  **둘 다** 노출한다. 같은 처지의 풀 소유자가 최소 다음과 같다: `SessionCheckpointMailbox`,
  `PendingTurnReaper`, `DefaultParallelToolDispatcher`, `BoundedFanoutDispatcher`,
  `DefaultSubagentExecutionManager`, `TaskHeartbeatPublisher`, `ZombieTaskReaper`, `HookConfigWatcher`,
  `GraalJsEngineHolder`. **전부 데몬 스레드이므로 JVM 종료는 막지 않지만**, 컨텍스트가 반복 생성되는
  환경(devtools restart, 여러 테스트 컨텍스트)에서 풀이 누적된다. 스타터가 이들을 자기 빈으로 따로
  소유해야 한다.
- **`AutoCloseable` 조차 아닌 것들**: `InMemoryDerivationQueueManager` 와
  `PostgresDerivationQueueManager` 는 `stop()` 만 있으므로 `destroyMethod = "stop"` 을 명시해야 한다.
- **닫을 방법 자체가 없는 것**: `DefaultHookExecutor` 는 무인자 생성자에서 캐시 스레드풀
  (`hook-executor`, 데몬)을 만들지만 `close()` 도 `shutdown()` 도 없고 `HookExecutor` 인터페이스에도
  없다. 인스턴스마다 풀이 영구히 샌다(§9).
- `DefaultSessionRouter.close()` 는 `closeGracefully(Duration.ZERO)` 로 위임한다. `ZERO` 는 드레인을
  **아예 하지 않고** 곧장 강제 종료 + `interruptAllActiveSessions(SYSTEM_SHUTDOWN)` 으로 가며, 중단된
  턴의 세션 리스를 릴리스하지 않고 만료되게 둔다. Spring 의 추론 destroy 는 `close()` 를 부르므로,
  **아무 조치 없이 두면 모든 배포가 진행 중 턴을 하드 인터럽트하고 피어에게 lockLease 만큼의 지연을
  물린다.**

마커 인터페이스도 신뢰할 수 없다. `AgentScoped extends AutoCloseable` 이지만 그 마커에 대한 **fan-out 이
없다** — `OrcaAgentRuntime.close()` 는 하드코딩 목록만 닫는다. 마커를 근거로 destroy 콜백을 자동 등록하는
`BeanPostProcessor` 를 만들면 안 된다. 스코프/소멸 판단은 **타입별로 하드코딩**되어야 한다.

> `@Bean(destroyMethod = "")` 은 커스텀 close/shutdown 과 `Closeable`/`AutoCloseable` 의 `close` 만
> 억제한다. **`DisposableBean.destroy()` 는 여전히 호출된다.**

### 2.2 침묵하는 기본값 — 빠뜨리면 예외가 아니라 기능이 사라진다

| 빠뜨린 것 | 결과 | 발현 |
|-----------|------|------|
| `ExecutionBudget` | `LiveSessionOptions.defaults()` → `unlimited()` → **iteration/토큰/시간/비용 상한 전무** | 무한 ReAct 루프 |
| ~~`OrcaAgentExecutorFactory.create(llmClient)` 1-인자 호출~~ | 사적 `new DefaultTranscriptManager(new InMemorySessionRecordStore())` 를 만들어 주입한 스토어를 무시했다 | **해소됨** — 그 오버로드는 제거되었다. `TranscriptManager` 는 이제 필수 인자이므로 빠뜨리면 컴파일되지 않는다 |
| `SchedulingEngineBuilder.agentRuntimeRegistry(...)` | 사적 `DefaultAgentRuntimeRegistry` 생성 | cron 발화 시 `RoutineExecutor` 가 `IllegalStateException` 을 던지지만 **한 프레임 위에서 삼켜져** `maxRetries` 만큼 재시도(스케줄러 스레드에서 `Thread.sleep`)된 뒤 `StepResult.failure` + WARN 로그 + 실패 `TaskFailedEvent` 로 끝난다. **호출자에게 전파되지 않는다** |
| `SkillInvocationPolicy` 빈 | `AlwaysAllowSkillInvocationPolicy.INSTANCE` 로 대체(debug 로그만) | 모든 스킬이 무검사 실행 |
| `MessageQueueManager` | `offerAsync` 가 QUEUED 를 반환하지 않고 **동시 턴**으로 폴백 | thread-unsafe 세션에서 턴 중첩 |
| `HookExecutionManager` | `OnSessionStart` / `OnSessionEnd` 미발화 | 훅이 조용히 없음. **CLI 가 바로 이 상태다**(§4.6) |
| `RewakeService` | `RewakeService.NOOP` — 모든 rewake spec 을 WARN 후 드롭 | 기능 실종 |
| agent frontmatter 의 `maxIterations` | `Integer.MAX_VALUE` | 무한 루프 |

**설계 결론**: 이 표의 모든 행이 스타터에서 (1) 안전한 기본값 또는 (2) 기동 실패 중 하나가 되어야 한다.
"기본값을 안 주면 안전하다"가 성립하지 않는다.

### 2.3 호스트 환경 가정

| 읽는 것 | 서버에서의 문제 |
|--------|---------------|
| jar 위치 또는 `user.dir` (CLI 의 `getJarDirectory()`) | Boot jar 에서 code source URI 가 **opaque** 라 `toURI().getPath()` 가 null → `jarPath.endsWith(".jar")` 가 **NPE** → `catch(Exception)` 이 삼키고 `user.dir` 로 조용히 폴백한다. 실제 repackage 된 jar 로 확인됨 |
| `Environment.createDefault()` 의 `user.dir` | 컨테이너의 CWD 는 의미 없는 값 |
| `HookConfigLoader.createDefault()` / hook hot reload 의 `user.home` | 컨테이너에서 `/` 또는 `/root` |
| `BundledSkillMaterializer` 의 `.aimon/bundled-skills` **기동 시 디스크 쓰기** | 읽기 전용 루트 파일시스템에서 실패 |
| `DefaultCommandRegistry.initialize()` 의 `.aimon/commands/*.md` | 무관한 파일 하나가 컨텍스트 기동 실패로 |
| 훅 셸 액션에 전달되는 `System.getenv()` 전체 | 서버 프로세스의 DB 비밀번호/클라우드 자격증명 노출 |
| `McpClientManager.createClients()` 의 `awaitTermination(Long.MAX_VALUE, NANOSECONDS)` + 기본(비-데몬) 스레드 팩토리 | **응답 없는 MCP 서버 하나가 컨텍스트 refresh 를 영구 블록한다.** 타임아웃도, 설정으로 중단할 방법도 없다. 서버가 하나뿐이면 풀을 아예 만들지 않고 호출 스레드에서 그대로 막힌다 |

### 2.4 기동/종료 순서 — 양방향이고, 어기면 조용히 실패한다

**기동 순서는 양방향 제약이다.**

```
AgentRuntimeRegistry.register(...)   ← 이게 먼저여야 cron 발화가 런타임을 resolve 한다
        ↓
SchedulingEngine.start()             ← 이게 먼저여야 register 가 예외를 안 던진다
        ↓
ScheduledTaskManager.register(...)
```

두 `TaskScheduler` 구현 모두 `start()` 이전에 `scheduleRecurrently` 를 부르면
`TaskSchedulerException("Scheduler is not running")` 을 던지고, `ScheduledTaskManager.register()` 는 그
경로를 무조건 탄다. 따라서 **선언된 작업을 `@PostConstruct` / `InitializingBean` 에서 등록하면 refresh
중에 던진다.** 반대로 런타임 등록이 늦으면 §2.2 처럼 조용히 재시도되다 드롭된다.

**DI 컨테이너가 공짜로 주지 않는 것이 세 가지 있다.** CLI 조립체(`AgentSetupFactory`)가 45개 협력자를
한 메서드 안에서 손으로 엮고 있는 이유가 전부 여기 있다.

- **인스턴스 공유 강제** — 스킬 정책 스토어를 pre-flight scanner 와 `SkillTool` 이 공유해야 하고,
  `SessionRecordStore` 를 `TranscriptManager` 와 라이브 세션이 공유해야 하고, `MessageQueueManager` 를
  executor 와 세션 **양쪽** 모두에 넘겨야 한다. 하나라도 갈리면 §2.2 의 침묵이 된다.
- **양방향 부트스트랩** — `DefaultRewakeFireListener` ↔ `DefaultRewakeService` 는 3단계 수동 결선
  (`new listener(registry)` → `new service(listener)` → `listener.bindRewakeService(service)`)이다.
  생성자 주입으로 표현할 수 없으므로 **한 `@Bean` 메서드 안에서** 처리해야 한다.
- **엄격한 종료 총순서** — 다음 14단계다.

  ```
  memoryFinalDerivation.run() → memoryQueue.stop() → dreamer → maintenance
    → liveSession → sessionCheckpoints → agentRuntime → graalJsEngines
    → registry.unregister → schedulingEngine → rewakeService
    → pendingTurnReaper → hookHotReload → skillHookShell
  ```

  인접 쌍마다 이유가 있다.
  - `memoryFinalDerivation.run()` 이 먼저 — 전사 관리자와 peer-memory 스토어가 아직 살아 있어야 deriver 가
    전체 메시지 이력을 읽는다. **`run()` 자체는 LLM 호출이 아니다** — 전사를 읽어 `DerivationTask` 를
    큐에 넣을 뿐이고 실제 LLM 파생은 워커 스레드에서 일어난다. 두 단계가 붙어 있는 이유가 그
    enqueue→드레인 관계다.
  - `memoryQueue.stop()` 은 큐가 빌 때까지 또는 드레인 타임아웃(in-memory 30초)까지 블록한다.
    **LLM 파생이 완료되는 곳이 여기다.**
  - `sessionCheckpoints` 는 라이브 세션 **뒤** — 마지막 end-of-turn 저장이 이미 mailbox 를 드레인했도록.
  - `graalJsEngines` 는 `agentRuntime` **뒤** — 반쯤 닫힌 엔진에 WorkflowJs 스크립트가 resolve 하지
    못하도록.
  - `hookHotReload` 는 `skillHookShell` **앞** — 그 사이에 debounce 된 reload 가 발화하면 닫힌 셸을 친다.

  **이 총순서의 대부분은 의존 간선이 없다.** Spring 의 역-의존 소멸 순서는 이것을 재현하지 못한다.
  그리고 첫 두 단계만으로도 Spring 의 기본 종료 phase 타임아웃(30초)을 넘길 수 있다. 이것이 §6 D4
  ("Spring 이 닫는 것은 `AimonStack` 하나")의 직접적인 근거다.

`AgentSetupFactory` 는 또한 **호출 간 상태를 갖는다** — 생성자에서 만든 가변 `OrcaAgentExecutorFactory`
하나를 `create()` 가 `withTracer` / `withRewakeService` / `withSubagentBehaviorRegistry` /
`withCostEstimator` 로 변조한 뒤 사용한다. 위험은 두 갈래다. **(1) 에이전트 간 설정 누수** — 팩토리
인스턴스를 재사용하기 때문. **(2) 설정이 동일해도 남던 위험** — 사적 전사 스토어를 만들던 1-인자
`create(llmClient)` 오버로드를 두 번 부르면 두 executor 가 `PendingTurnRegistry` / `MessageQueueManager` /
`SkillPreflightScanner` / `RewakeService` 는 **공유**하면서 각자 사적 `DefaultTranscriptManager` 를 만들어
**전사 상태만 서로 갈라졌다.** 팩토리를 프로토타입 스코프로 두면 (1)만 고쳐진다. (2)는 그 오버로드가
제거되면서 해소됐다 — `TranscriptManager` 가 필수 인자가 되었으므로 **공유 `SessionRecordStore` 위에 만든
것을 매 호출에 넘기는 것 말고는 선택지가 없다.**

**Quartz 관련 위험 3종.** 1·2 는 코어를 고쳐 해소했고, 3 은 남아 있다.

1. **JVM 전역 공유** — `StdSchedulerFactory.getScheduler()` 는 JVM 전역 `SchedulerRepository` 를 통해
   `instanceName` 으로 resolve 하는데, 한때 `QuartzTaskScheduler` 의 두 생성 경로가 모두 그 이름을 같은
   리터럴로 고정하고 있었다. 그러면 `@Bean` 팩토리 메서드가 두 번 불리거나 한 JVM 에 컨텍스트가 둘이면
   **같은 Scheduler 를 공유**하고, 한쪽의 `start()` 가 공유 scheduler-context 를 덮어써 다른 쪽 executor
   로 잡이 실행되며, 어느 한쪽의 `shutdown()` 이 양쪽을 내린다. 스타터에 맡기지 않고 **두 경로 모두**
   기본값을 파생 이름(`AimonScheduler-<n>`)으로 바꿨다 — 스타터만 고치면 빌더를 직접 쓰는 다른 호출자
   (테스트 포함)가 그대로 남기 때문이다. 클러스터링은 이 요구를 뒤집으므로(같은 이름 = 한 클러스터)
   `clustered(true)` 에는 명시적 이름을 **요구**한다.
2. **비-데몬 스레드** — Quartz 는 `makeThreadsDaemons` / `makeSchedulerThreadDaemon` 을 설정하지 않으면
   `QuartzSchedulerThread` + 모든 워커(+ JDBC job store 사용 시 `ClusterManager`)가 비-데몬이다.
   **코어가 만드는 장수명 스레드는 전부 명시적 데몬**이므로, 트리 전체에서 비-데몬 원천은 Quartz 와
   `PlaywrightLifecycleManager` **두 곳뿐**이다 — 즉 destroy 를 놓쳤을 때 JVM 이 안 죽는 경로가 이 둘로
   한정된다. Quartz 쪽은 기본 데몬으로 바꿔 해소했고(`daemonThreads(false)` 로 끌 수 있다) Playwright 는
   그대로다.
3. **`shutdown(true)` 는 잡 완료를 기다린다.** 루틴 스텝의 도구 실행은 `RoutineStep.getTimeout()` 으로만
   제한되므로 종료가 수 분 블록될 수 있다.

여기에 §4.5 의 드레인을 더하면 종료가 수십 초 단위로 늘어난다. **`spring.lifecycle.timeout-per-shutdown-phase`
는 이 경로에 도달하지 않는다** — 스택 소멸은 `destroyBeans()` 에서 일어나고 라이프사이클 타임아웃은 그
이전 단계에서만 산다(§4.5). 길어지는 종료를 짧게 만들려면 `aimon.session.shutdown-drain-timeout` 을
내려야 한다.

`OrcaAgentRuntimeManager.destroyRuntime()` 은 per-id 락 객체를 **락을 쥔 채로 제거**하고, javadoc 이
"같은 id 에 대해 `getOrCreateRuntime` 과 동시에 실행되면 안 된다"고 경고한다. 에이전트 핫리로드나
actuator refresh 같은 기능을 붙이려면 외부에서 직렬화해야 한다.

`AgentRuntimeRegistry`(인터페이스)는 `register` / `unregister` / `get` 과 default `getAs` 만 노출하고
**열거 메서드가 없다**. 그리고 구현 `at.aimon.core.agent.DefaultAgentRuntimeRegistry`(주의 — `.impl`
하위가 아니다)의 `unregister` 는 맵에서 빼기만 하고 닫지 않는다. 스타터는 자기가 만든 런타임 목록을
**직접 들고 있어야** 한다.

### 2.5 조립 지식이 published 되지 않은 모듈에 있다

`aimon-cli` 는 `aimon.publishable` 을 적용하지 않는다 — Maven Central 에 없다. 서버가 필요로 하는
배선의 대부분이 거기 있었으므로, 사용자는 그 파일을 **읽고 베껴야** 했다. 그것이 스타터 이전의 통합
경험이며, `aimon-bootstrap` 이 존재하는 이유다.

### 2.6 빌드 — `implementation` 은 스타터에 쓸 수 없다

[`.claude/rules/code-style.md`](../../../.claude/rules/code-style.md) 는 "구현 모듈은
`implementation(project(":aimon-core"))` 를 쓰고 `api()` 를 쓰지 말 것"을 규정한다. 그러나 Gradle
`java-library` 의 `implementation` 은 published POM 에서 `<scope>runtime</scope>` 이 되므로, 그렇게
선언된 스타터의 소비자는 **컴파일 타임에 `aimon-core` 타입을 전혀 볼 수 없다**. `@Bean LlmClient`,
`AgentRuntime`, `SessionId` 를 쓰는 사용자 코드가 전부 컴파일 실패한다.

스타터는 구현 모듈이 아니라 **파사드/애그리게이터**이므로 이 규칙의 예외가 된다(§6 D5). in-tree
선례가 있다 — `aimon-sandbox-docker` 는 `api(project(":aimon-sandbox"))` 를 쓴다.

또한 **Spring 은 어느 모듈의 main 컴파일 클래스패스에도 없다**. `aimon.java-conventions` 가
`spring-boot-starter-test` 를 `testImplementation` 으로만 건다. 스타터 모듈은 main 스코프
`spring-boot-autoconfigure` 를 직접 선언한다.

---

## 3. 아키텍처

### 3.1 3계층

```
┌───────────────────────────────────────────────────────────────────┐
│ 호스트 애플리케이션 (Spring Boot)                                   │
│   application.yml  +  AimonSessions / AimonAgents 주입              │
└───────────────────────────┬───────────────────────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────────────┐
│ aimon-spring-boot-starter          at.aimon.spring.boot.*          │
│   (자동설정을 포함하는 단일 아티팩트 — §6 D1b)                       │
│                                                                    │
│   AimonProperties (@ConfigurationProperties("aimon"))              │
│        │  번역(translate)                                           │
│        ▼                                                            │
│   AimonStackSpec                                                    │
│   AimonAutoConfiguration + 슬라이스 7종                             │
│   AimonRuntimeLifecycle / AimonSchedulingLifecycle (SmartLifecycle) │
│   AimonSessions / AimonAgents (파사드) / AimonHealthIndicator       │
└───────────────────────────┬───────────────────────────────────────┘
                            │ 의존
┌───────────────────────────▼───────────────────────────────────────┐
│ aimon-bootstrap                    at.aimon.bootstrap              │
│                                                                    │
│   AimonStackBuilder.from(AimonStackSpec) → AimonStack              │
│     ├ LlmAssembler          ├ SessionAssembler                     │
│     ├ SkillPolicyAssembler  ├ SchedulingAssembler                  │
│     ├ RuntimeAssembler      ├ MemoryAssembler …                    │
│   AimonStack.close()  ← 순서 있는 teardown (§5.3)                   │
│                                                                    │
│   ※ Spring 의존성 없음. 순수 Java.                                  │
└───────────────────────────┬───────────────────────────────────────┘
                            │ 의존
┌───────────────────────────▼───────────────────────────────────────┐
│ aimon-core  +  aimon-session-routing  +  위성 모듈(optional)           │
└───────────────────────────────────────────────────────────────────┘
```

`aimon-cli` 는 같은 `aimon-bootstrap` 위에 얹힌다 — `AgentSetupFactory` 는 CLI 전용 데코레이션
(터미널 출력, JLine 승인 채널, 표시 훅)만 남기고 축소된다.

### 3.2 왜 프레임워크 중립 층이 먼저인가

Spring 자동설정만 만들고 조립 로직을 그 안에 두는 **2계층 안**도 가능하다. 기각한다.

| 항목 | 2계층 (스타터가 직접 조립) | **3계층 (bootstrap 분리)** |
|------|--------------------------|---------------------------|
| 조립 지식의 소재 | Spring 자동설정 클래스 안 | 프레임워크 중립 모듈 |
| CLI 와의 중복 | `AgentSetupFactory` 와 완전 중복, 영구히 갈라짐 | CLI 가 같은 층을 소비 → 단일 진실 |
| 종료 순서(§2.4) | Spring 빈 그래프에 인코딩해야 함 — 표현 불가한 제약이 있음 | `AimonStack.close()` 에 그대로 코드로 존재 |
| 테스트 | Spring 컨텍스트를 띄워야 조립을 검증 | 평범한 JUnit 으로 조립 검증 |
| Quarkus/Micronaut/plain SDK | 처음부터 다시 | 바인딩 층만 추가 |
| `.claude/rules/architecture.md` "CLI 만이 여러 구현 모듈에 의존할 수 있다" | 스타터가 그 규칙을 위반 | bootstrap 이 새로운 assembly 층으로 규칙에 편입 |

결정적인 것은 **종료 순서**다. §2.4 의 14단계 총순서는 인접 쌍마다 이유가 있고, 그 대부분은 **의존 간선이
존재하지 않는다**. `@DependsOn` 으로 가짜 간선을 만들 수는 있지만 그러면 순서 지식이 애노테이션으로
흩어져 검증할 수 없게 된다. 순서를 한 메서드 안에 코드로 두고 그 메서드를 테스트하는 편이 낫다.

이 층 분리가 **아티팩트 분리를 뜻하지는 않는다** — 자동설정을 별도 아티팩트로 뺄지는 §6 D1b 의 별개
결정이며, 답은 "지금은 아니다"다.

### 3.3 모듈 배치

```
modules/
├── aimon-bootstrap                  # 신규 — 프레임워크 중립 조립 (publishable)
└── aimon-spring-boot-starter        # 신규 — 자동설정 포함 단일 스타터 (publishable)
```

`settings.gradle.kts` 의 `include(...)` 와 `project(":x").projectDir` 양쪽에 항목을 추가하고,
각 모듈에 `gradle.properties`(`POM_ARTIFACT_ID` / `POM_NAME` / `POM_DESCRIPTION`)를 둔다 —
Maven Central 은 `name` / `description` / `url` 을 요구한다.

> **in-tree 반례 주의**: `aimon-workflow-graaljs` 는 `aimon.publishable` 을 적용했지만 모듈
> `gradle.properties` 가 없어 POM 의 `<name>`/`<description>` 이 비어 있다. 다만 그 모듈이 Central 에
> 없는 이유는 검증 거부가 아니라 **마지막 릴리스 태그(v0.2.0, 2026-07-13) 이후인 2026-07-24 에
> 추가되어 아직 릴리스를 거치지 않았기 때문**이다. 실제로 거부되는지는 다음 릴리스 때 드러난다.

의존 선언:

```kotlin
// modules/aimon-spring-boot-starter/build.gradle.kts
dependencies {
    api(project(":aimon-core"))          // ← 규칙 예외. 소비자 컴파일 클래스패스에 필요 (§2.6)
    api(project(":aimon-bootstrap"))
    api(project(":aimon-session-routing"))

    implementation(libs.spring.boot.autoconfigure)                  // ← main 스코프. 카탈로그에 추가 필요
    annotationProcessor(libs.spring.boot.configuration.processor)   // 프로퍼티 메타데이터 JSON
    annotationProcessor(libs.spring.boot.autoconfigure.processor)   // 조건 사전 인덱스 (별도 아티팩트)

    // 프로바이더/백엔드는 optional — 사용자가 고른다
    compileOnly(project(":aimon-llm-anthropic"))
    compileOnly(project(":aimon-llm-openai"))
    compileOnly(project(":aimon-session-postgres"))
    // …
}
```

`spring-boot-autoconfigure` / `-configuration-processor` / `-autoconfigure-processor` 좌표는 카탈로그에
없으므로 추가한다. 이 블록은 `buildSrc` 의 새 규약 플러그인 `aimon.spring-starter` 로 빼는 편이
`aimon.java-conventions` / `aimon.publishable` 분리 관례와 일관된다.

**`checkstyle {}` 블록은 넣지 않는다** — `config/checkstyle/checkstyle.xml:8` 이 `severity=error` 를
전역 설정하므로 `maxWarnings`(severity=warning 만 셈)는 원리적으로 발동하지 않는다. 가장 최근에 추가된
두 모듈(`aimon-rewake-webhook`, `aimon-workflow-graaljs`)도 블록이 없다.

### 3.4 계층별 책임

| 계층 | 하는 일 | 하지 않는 일 |
|------|--------|-------------|
| `aimon-core` | 도메인, SPI, 기본 구현 | DI, 프레임워크 인지, 조립 |
| `aimon-bootstrap` | 스펙 → 인스턴스 그래프 조립, 순서 있는 소멸, 조립 시점 검증 | 설정 파일 파싱, HTTP, 프레임워크 애노테이션 |
| `aimon-spring-boot-starter` | 프로퍼티 바인딩, 조건부 등록, 라이프사이클 브리지, 파사드 | 조립 순서 지식 |
| `aimon-cli` | 터미널 UX (출력 포매터, JLine, 표시 훅, REPL) | 조립 (bootstrap 으로 이관) |

---

## 4. 핵심 설계

### 4.1 `AimonStack` — 프레임워크 중립 조립 API

```java
package at.aimon.bootstrap;

/**
 * 하나의 AIMON 스택 — application-scoped 협력자 전체와 그들의 소멸 순서를 소유한다.
 *
 * <p>수명: 애플리케이션. {@link #close()} 는 정확히 한 번, 앱 종료 시에만 호출된다.
 * 이 객체가 돌려주는 협력자들은 <b>빌려주는 것</b>이며, 받은 쪽이 닫아서는 안 된다.
 */
public final class AimonStack implements AutoCloseable {

    public static AimonStackBuilder builder(AimonStackSpec spec) { … }

    // 조회 — 전부 이 스택이 소유한다. 호출자는 닫지 않는다.
    public SessionRouter sessionRouter() { … }
    public AgentRegistry agentRegistry() { … }
    public AgentRuntimeRegistry agentRuntimeRegistry() { … }
    public SessionRecordStore sessionRecords() { … }
    public Optional<SchedulingEngine> schedulingEngine() { … }
    public Optional<KnowledgeStore> knowledgeStore() { … }
    public AimonAgents agents() { … }                      // §4.11 — 조회·무효화
    public AgentRuntimeResolver agentRuntimes() { … }      // (agentRef, discriminator) → AgentRuntime
    public HealthReport health() { … }                     // §4.10

    // 기동 단계 — SmartLifecycle 이 부른다 (§4.5)
    /** discriminator 없는 agent:<name> 런타임 N개만 만든다. 테넌트 런타임은 지연 생성 (§4.11). */
    public void registerAgentRuntimes() { … }
    public void startScheduling() { … }                    // engine.start() → task register
    public void stopScheduling(Duration timeout) { … }

    /** §5.3 의 순서대로 닫는다. 멱등. */
    @Override public void close() { … }
    public void close(Duration drainTimeout) { … }          // SessionRouter.closeGracefully 용
}
```

`AimonStackSpec` 은 불변 빌더 값이며, **프로퍼티도 YAML 도 모른다**. Spring 층은 `AimonProperties`
→ `AimonStackSpec` 번역만 담당한다.

```java
AimonStackSpec.builder()
    .workspaceRoot(Path.of("/var/lib/myapp/agent"))       // 필수 — 파생하지 않는다 (§6 D7)
    .llm(LlmSpec.anthropic(apiKey).model("claude-sonnet-4-20250514").build())
    .agents(AgentsSpec.builder()                           // N개 (§4.11)
            .add(AgentSpec.fromBundle("ops-agent").budget(b -> b.maxIterations(30)))
            .add(AgentSpec.fromBundle("inquiry"))
            .defaultAgent("ops-agent")
            .customizers(customizers)                      // List<AimonAgentCustomizer>
            .runtimeCache(idleTtl, maxEntries)
            .build())
    .fileSystems(vfsFactory)                               // 에이전트×테넌트별 (§6 D15)
    .credentials(credentialStoreFactory)                   // 테넌트별
    .capabilities(capabilityResolver)                      // __capability.* 승격 (§4.11)
    .defaultBudget(ExecutionBudget.builder().maxIterations(20)…build())
    .channelBudgets(Map.of("slack", slackBudget))          // §6 D17
    .session(SessionSpec.singleNode())                     // 또는 .distributed(nodeId, backends)
    .skillApproval(SkillApprovalSpec.denyAll())            // 기본 = 거부
    .tools(ToolSpec.serverDefaults())                      // Bash off
    .scheduling(SchedulingSpec.disabled())
    .build();
```

`AimonStackBuilder` 는 §2.4 의 순서 제약을 코드로 인코딩한다 — 인스턴스 공유 강제, rewake 양방향 결선,
소멸 순서 등록. 협력자를 하나 추가할 때 **소멸 목록에 등록하는 것을 잊을 수 없도록** 등록형 API 를 쓴다.

```java
// 내부 — 생성과 동시에 teardown 순서 슬롯에 등록된다.
SessionCheckpointMailbox mailbox = own(TeardownPhase.CHECKPOINTS, SessionCheckpointMailbox.background());
```

다중 에이전트에서 이 등록형 API 는 **런타임별로도** 필요하다. `AgentRuntime.close()` 는 자기가 소유한
것만 닫고 fan-out 하지 않으므로(§4.11), 런타임을 만들 때 딸려 만든 `VirtualFileSystem` 과 `AutoCloseable`
도구는 그 런타임에 매달린 소멸 목록에 등록해 두고 축출·무효화·종료 시 함께 닫는다. 스택 소멸 한 번으로
덮이던 단일 에이전트 때와 달리, 여기서 빠뜨리면 테넌트가 늘어날수록 핸들이 샌다.

### 4.2 자동설정 슬라이스

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 에만 등록한다
(컴포넌트 스캔 금지 — 그것이 `@AutoConfiguration` 을 등록하는 유일한 경로다). 클래스 개명에 대비해
`…AutoConfiguration.replacements`(`old=new` 매핑) 파일도 함께 둔다 — 이 저장소는 개명 이력이 있고
(`AgentExecutionContext`→`AgentRuntime`, `Conversation`→`SessionRecord`), 사용자의
`spring.autoconfigure.exclude` 항목은 개명 시 그대로 깨진다. 다만 **현재 내용은 비어 있다**: 지금까지의
유일한 개명(`AimonSessionStoreAutoConfiguration`→`AimonSessionAutoConfiguration`)이 이 모듈이 어떤
태그에도 실리기 전에 일어나서, 별칭을 남기는 비용이 그것이 막아 주는 것보다 크기 때문이다.

| # | 슬라이스 | 조건 | 기여 |
|---|---------|------|------|
| 1 | `AimonAutoConfiguration` | `@ConditionalOnClass(Agent.class)` + `@ImportRuntimeHints(AimonRuntimeHints)`. `after` = 나머지 일곱. 안에 `EnabledConfiguration` / `DisabledConfiguration` 두 갈래 | `AimonStackSpec`, `AimonStack`, 라이프사이클 2종, `AimonSessions`, `AimonAgents`, `PendingTurnRegistry`, 그리고 `LlmClient` 미해결 폴백 |
| 2 | `AimonLlmAutoConfiguration` | `aimon.llm.provider` 선택자 + 프로바이더 모듈에 대한 `@ConditionalOnClass` + `@ConditionalOnMissingBean` | `LlmClient` — 중첩 설정 `anthropic`(기본) / `openai` |
| 3 | `AimonFileSystemAutoConfiguration` | `@ConditionalOnMissingBean(FileSystemSpec.class)` | `FileSystemSpec` (기본: 로컬 서브트리 팩토리 — §6 D15) |
| 4 | `AimonSessionAutoConfiguration` | `@ConditionalOnMissingBean(SessionSpec.class)` | `SessionSpec` — 레코드 스토어 **+ 분산 SPI 4종**(`SessionLeaseStore` / `SessionSignalBus` / `SessionInbox` / `IdempotencyStore`). 분산 SPI 를 별도 슬라이스로 나눌 수 없는 이유는 **같은 `@Bean` 하나에 기여**하기 때문이다 |
| 5 | `AimonSchedulingAutoConfiguration` | `@ConditionalOnMissingBean(SchedulingSpec.class)`. 중첩 `QuartzConfiguration` 은 `backend=quartz` + `@ConditionalOnClass{QuartzTaskScheduler, Scheduler}` | `SchedulingSpec` — 엔진 자체가 아니라 **`TaskSchedulerFactory`** 를 싣는다 |
| 6 | `AimonMemoryAutoConfiguration` | `before` 슬라이스 1. `aimon.memory.backend`(`none`/`in-memory`/`supplied`) + `peer-mode` / `redaction` 선택자 | `MemoryContribution`(값 빈) + `in-memory` 일 때 `RepresentationStore` / `ObservationStore`. `@ConditionalOnClass` 를 쓰지 않는다 — 스토어 구현이 전부 코어에 있어 항상 존재한다 |
| 7 | `AimonKnowledgeAutoConfiguration` | `before` 슬라이스 1. `aimon.knowledge.backend`(`none`/`keyword`/`supplied`) | `KnowledgeContribution`(값 빈) + `keyword` 일 때 `KnowledgeStore`. OpenSearch 는 `supplied` 경로로만 닿는다 |
| 8 | `AimonObservabilityAutoConfiguration` | `before` 슬라이스 1. `aimon.tracing.*` · `@ConditionalOnClass(HealthIndicator)` · `@ConditionalOnClass(SanitizingFunction)` · `@ConditionalOnClass` **+ `@ConditionalOnBean`**`(MeterRegistry)` — 스위치 넷이 서로 독립이다 | `Tracer`, `TraceSpanStore`, `AimonHealthIndicator`, `AimonMetrics`, `/env` 새니타이저 |

여덟 슬라이스 전부가 `@ConditionalOnProperty(name = aimon.enabled, havingValue = "true", matchIfMissing = true)`
와 `@EnableConfigurationProperties(AimonProperties.class)` 를 함께 단다.

**스킬 승인 정책과 에이전트 커스터마이저에는 별도 슬라이스가 없다.** 둘 다 값 하나로 귀결되므로 루트
슬라이스의 `EnabledConfiguration` 이 `ObjectProvider<AimonAgentCustomizer>` 와 승인 프로퍼티를 직접 읽어
`AimonStackSpec` 에 싣는다 — 슬라이스를 하나 더 만들면 `@ConditionalOnMissingBean` 의 대상만 늘고 바뀌는
것이 없다.

**중요**: 슬라이스 2~8 이 만드는 것은 `AimonStackSpec` 에 들어갈 **재료**이지 `AimonStack` 의 부품을 직접
`new` 한 것이 아니다. 실제 조립은 항상 `AimonStackBuilder` 안에서 일어난다. 이 규율이 깨지면 3계층의
의미가 사라진다.

두 가지 주의:

- `@ConditionalOnBean` / `@ConditionalOnMissingBean` 은 **자동설정 클래스 위에서만** 안전하다.
  자동설정이 사용자 빈 정의 이후에 로드되는 것이 보장되기 때문이며, 일반 `@Configuration` 에서 쓰면
  순서에 따라 결과가 달라진다.
- `@AutoConfiguration(after = …)` 의 순서는 **빈이 정의되는 시점**만 좌우하고, 생성 시점은 의존 관계와
  `@DependsOn` 이 결정한다. 기동 순서를 여기에 기대면 안 된다(§4.5).

#### `aimon.enabled=false` — "빈이 전부 사라진다" 가 아니다

kill switch 의 실제 용도는 **코드를 고치지 않고 특정 프로파일에서만 끄는 것**이다(테스트 프로파일,
LLM 을 부르면 안 되는 스테이징). 그런데 슬라이스 전체가 그냥 백오프하면 `AimonSessions` 를 주입받는
호스트 빈이 `NoSuchBeanDefinitionException` 으로 **기동에 실패**한다 — 끄려고 켠 스위치가 앱을 죽인다.
그래서 게이트는 두 갈래다.

| `aimon.enabled` | 만들어지는 것 | 호출했을 때 |
|-----------------|--------------|------------|
| `true` (기본) | 슬라이스 1~8 전부 | 정상 동작 |
| `false` | **`AimonSessions` 하나만** — `DisabledAimonSessions` | 모든 메서드가 `AimonDisabledException`: "aimon.enabled=false 이므로 세션을 실행할 수 없다" |

`AimonAutoConfiguration` 안에 `@ConditionalOnProperty(havingValue="true", matchIfMissing=true)` 와
`havingValue="false"` 두 개의 중첩 `@Configuration` 을 두어 갈라낸다. disabled 갈래는 대체 구현 하나만
등록하므로 `AimonStack` 도, 스레드 풀도, LLM 커넥션도 만들지 않는다 — 끈다는 말의 뜻이 지켜진다.
그러면서 주입점은 살아 있고, **실제로 쓰려고 하면 프로퍼티 이름이 박힌 예외로 시끄럽게 실패**한다.
§1.4 의 "침묵하는 기본값 금지" 를 kill switch 에 적용한 것이다.

**호스트가 주입받도록 문서화된 타입은 `AimonSessions` 하나뿐이며**(§4.6), disabled 대체물을 갖는 것도
그것뿐이다. `AgentRuntimeRegistry` 나 `SchedulingEngine` 같은 내부 빈을 직접 주입한 앱이 `enabled=false`
에서 기동 실패하는 것은 **의도된 동작**이다 — 그 주입은 애초에 문서화된 계약이 아니었고, 조용히
no-op 대체물을 끼워 주면 "꺼져 있는데 동작하는 것처럼 보이는" 상태가 만들어진다.

### 4.3 프로퍼티 트리

접두어는 `aimon` — `spring` / `server` / `management` 처럼 예약된 네임스페이스를 침범하지 않는다.
각 `@ConfigurationProperties` 클래스에 `PREFIX` 상수를 두고, 필드 Javadoc 이 그대로 IDE 설명이 되므로
Boot 하우스 스타일을 따른다(불리언은 "Whether …" / "Enable …", `long` 대신 `java.time.Duration`,
기본값을 문장에 다시 적지 않기, 컬렉션은 "Comma-separated list …").

```yaml
aimon:
  enabled: true                       # kill switch. false 면 AimonSessions 만 남고 호출 시 예외 (§4.2)
  fail-fast: true                     # degradation 이 있으면 기동 중단 (§4.10)
  workspace:
    root:                             # 필수. 파생하지 않는다 (§6 D7)
    ensure-writable: true             # 기동 시 쓰기 가능 여부 검증 → 실패 시 기동 중단
  agents:                             # ← 목록이 아니라 map. 키가 agentRef 다 (§4.11)
    ops-agent:
      bundle: ops-agent               # 생략 시 키와 동일
      max-iterations: 20              # frontmatter 부재 시 Integer.MAX_VALUE 방지
      tools: { bash: { enabled: false } }    # agent-defaults 를 부분 오버라이드
      budget: { max-iterations: 30 }
    inquiry-agent:
      bundle: inquiry
      budget: { max-iterations: 10, max-wall-clock: 2m }
  agent-defaults:                     # 모든 에이전트의 기저값. agents.<name>.* 가 이긴다
    discover-bundles: true            # classpath*:agents/*/agent.md 자동 발견 (§4.11)
    default-agent: ops-agent          # agentRef 없이 들어온 submit 이 쓸 에이전트
    max-iterations: 20
  llm:
    provider: anthropic               # anthropic | openai | none   ← 선택자
    api-key:
    model:
    base-url:
    timeout: 120s
    anthropic: { … }                  # 프로바이더 전용 키는 여기 (§9)
    openai: { … }
  budget:                             # 전역 기저값 — 미설정 시 unlimited 가 되는 것을 막는다
    max-iterations: 20
    max-tokens: 100000
    max-wall-clock: 120s
    max-cost-usd:
    compaction-token-threshold:
    channels:                         # 채널별 상한. agents.<name>.budget 과 축이 다르다 (§4.11)
      web:   { max-iterations: 20, max-wall-clock: 5m }
      slack: { max-iterations: 30, max-wall-clock: 10m }
  session:
    store: in-memory                  # in-memory | postgres | mongodb | redis  ← 선택자
    mode: single-node                 # single-node | distributed               ← 선택자
    node-id:                          # distributed 필수. ':' 금지 — 바인딩 시점에 거부한다
    cache: { max-entries: 1000, idle-ttl: 30m }
    lease: { duration: 30s, extend-interval: 10s }   # extend < duration — 아직 배선되지 않았다
    shutdown-drain-timeout: 20s       # SessionRouter.closeGracefully (§2.1)
  queue:
    enabled: true                     # MessageQueueManager 배선 여부
    drain-on-turn-end: true           # §4.6
  tools:
    bash: { enabled: false }          # 서버 기본은 off (§6 D7)
    web:  { enabled: true }
  skill:
    approval:
      mode: deny                      # deny | allow-list | suspend | channel   ← 선택자
      allow: []                       # mode=allow-list 일 때만 읽는다. 빈 항목은 인덱스와 함께 거부
      pending-turn-ttl: 30m           # 미설정이면 코어의 기본값(30분). 모드와 무관하게 적용된다
    shell-actions:
      enabled: false                  # declarative shell 훅 (서버 기본 off)
      env-allow-list: []              # 전체 env 전달 금지 (§7)
  hooks:
    enabled: true
    config-path:                      # user.home 파생 금지
    ask-default: deny
    hot-reload: false                 # 서버 기본 off
  mcp:
    enabled: false
    connect-timeout: 10s              # 코어의 무한 대기 해소가 선행 필요 (§9)
    servers: []
  scheduling:
    backend: none                     # none | in-memory | quartz              ← 선택자
    auto-startup: true
    #  wait-for-jobs-on-shutdown 은 quartz.* 아래에 있다 — 백엔드 3개 중 2개에서는 아무 일도 하지 않는다
    #  shutdown-timeout 은 만들지 않았다 — 닿을 곳이 없다 (§4.5)
    quartz:
      use-application-scheduler: true # Spring 의 Scheduler 빈을 빌려 쓴다 (§6 D9)
      instance-name:                  # 미지정 시 컨텍스트별 고유값 파생 (§2.4)
      thread-count:                   # 자체 소유 스케줄러에만 유효. 빌려 쓰는 중이면 거부된다
      daemon-threads:                 # 〃
      wait-for-jobs-on-shutdown: false # 〃 — SchedulerFactoryBean 의 동명 플래그와 같은 의미
  memory:                             # 불리언 하나가 아니라 선택자 4개다 — 아래 주석 참조
    backend: none                     # none | in-memory | supplied            ← 선택자
    workspace-id:                     # 기본값 없음 — 없으면 서로 다른 배포가 한 워크스페이스를 공유한다
    peer-mode: fixed                  # fixed | caller  ← caller 는 도구를 등록하지 않는다
    peer-id:                          # peer-mode=fixed 의 필수값. caller 면 거부된다
    injection-mode: summary-only      # summary-only | full
    max-tokens: 0                     # 0 = 무제한
    redaction: default                # default | strict | none | supplied     ← 유일하게 중립이 아닌 기본값
  knowledge: { backend: none }      # none | keyword | supplied  ← 선택자
  tracing:   { enabled: false, payload-capture: none }   # none | truncated | full
```

`knowledge` 와 `memory` 는 둘 다 **불리언이 아니라 선택자**다. knowledge 의 값이 셋인데 결과가 넷인 것은
`supplied` 가 애플리케이션이 실제로 빈을 냈는지에 따라 채택과 기동 실패로 갈리기 때문이며, 그 반대
(`none` 인데 `KnowledgeStore` 빈이 있다)도 거부된다.

memory 쪽이 선택자 4개로 늘어난 이유는 메모리가 복잡해서가 아니라 **틀린 값이 조용하기 때문**이다 —
knowledge 는 잘못 꽂으면 빈 검색 결과를 주지만 memory 는 남의 이력을 준다. 그래서 `workspace-id` 와
(기본 모드에서) `peer-id` 에는 기본값이 아예 없고, `redaction` 만은 반대로 **중립이 아닌 기본값**
(`default`)을 갖는다. 이 슬라이스에서 유일하게 되돌릴 수 없는 실수가 그것 하나라서다 — 한 번 저장된
비밀은 프로퍼티를 고쳐도 사라지지 않는다.

**바인딩 검증**은 빈 생성보다 앞당긴다. 코어는 여러 곳에서 생성자/빌더에서 던지는데
(`SessionRouterConfig.Builder.build()` 의 lease 타이밍, `ToolConcurrencyConfig` 의 범위,
`ExecutionBudget` 의 `< 1` 검사), 그대로 두면 빈 생성 중 raw `IllegalStateException` 이 나와 어떤 프로퍼티가
잘못됐는지 알 수 없다.

다만 **JSR-380 은 쓰지 않는다** — 검증 공급자가 없으면 `@Validated` 는 조용한 no-op 이라, 스타터가 호스트
앱에 `hibernate-validator` 를 강요하지 않는 한 같은 오설정이 앱마다 다르게 동작한다. 검사는
`InitializingBean` 에서 직접 하고, 메시지에 프로퍼티 이름을 넣는다.

kebab-case 철자는 **퍼블리시 순간 동결**되므로 의도적으로 고른다(`open-ai` 가 아니라 `openai` 등).

#### 선택자 프로퍼티의 IDE 자동완성 — 애노테이션 프로세서가 해 주지 않는 부분

선택자는 **오타가 조용히 백오프로 이어지는** 자리이므로 IDE 자동완성이 실제로 떠야 한다. 그런데
`spring-boot-configuration-processor` 는 프로퍼티의 `type` 만 적고 **값 후보를 만들지 않는다** — enum
상수를 읽어 후보를 띄우는 것은 IDE 쪽이다. 위 트리에서 `← 선택자` 로 표시한 7개는
(`store: postgress` → 아무 슬라이스도 매치되지 않고 in-memory 로 떨어진다). 따라서 값 집합이 닫혀 있는지로
갈라 처리한다.

| 프로퍼티 | 바인딩 타입 | 힌트 출처 |
|---------|-----------|----------|
| `aimon.session.store` | `enum SessionStoreType` | IDE 가 enum 상수에서 |
| `aimon.session.mode` | `enum DeploymentMode`(코어 타입 재사용) | 〃 |
| `aimon.scheduling.backend` | `enum SchedulingBackend` | 〃 |
| `aimon.skill.approval.mode` | `enum ApprovalMode` | 〃 |
| `aimon.tracing.payload-capture` | `enum PayloadCapture`(`none` / `full` **2값**) | 〃 |
| `aimon.knowledge.backend` | `enum KnowledgeBackend` | 〃 |
| `aimon.llm.provider` | **`String`** | **손으로 작성한 힌트** — 서드파티 `LlmClient` 를 배제하지 않기 위해 |

enum 바인딩이 실제로 사는 값은 힌트보다 **relaxed binding**(`IN_MEMORY` / `in-memory` / `inMemory` 모두
허용)과 바인딩 시점의 오타 거부 쪽이다 — `@ConditionalOnProperty(havingValue=…)` 는 raw 문자열로
평가되므로 enum 으로 바꿔도 슬라이스 조건은 그대로 동작한다.

`aimon.llm.provider` 만 열어 두는 이유는 이 값이 **서드파티가 늘릴 수 있는 유일한 선택자**이기 때문이다 —
자기 슬라이스를 `havingValue="bedrock"` 으로 등록하는 외부 모듈(§6 D1b 의 분할 시나리오, §1.4 의 "호스트가
정의하면 호스트가 이긴다")을 enum 이 원천 봉쇄한다. 나머지 5종은 코어가 제공하는 구현으로 값 집합이
닫혀 있다. 대신
`src/main/resources/META-INF/additional-spring-configuration-metadata.json` 에 힌트를 손으로 쓴다 —
이 파일은 프로세서 생성 결과에 **병합**되며, `providers: [{ "name": "any" }]` 로 열어 두면 알려진 값을
제안하면서도 미지의 값에 경고를 띄우지 않는다.

```json
{ "hints": [ { "name": "aimon.llm.provider",
    "values": [ { "value": "anthropic" }, { "value": "openai" },
                { "value": "none", "description": "LlmClient 를 만들지 않는다. 호스트가 직접 빈으로 제공." } ],
    "providers": [ { "name": "any" } ] } ] }
```

`deprecation` 항목도 같은 파일에 쓴다 — kebab 철자가 동결되는 만큼 개명 시 유일한 이전 경로다.

### 4.4 소유권 매트릭스 — `destroyMethod` 결정표

이 표가 이 설계의 안전성의 핵심이다. **원칙: 한 자원에 소멸 간선이 둘이 아니다.**
판정 기준은 `implements AutoCloseable` 이 아니라 **public 무인자 `close()` 또는 `shutdown()` 의 존재**다.

원칙을 "Spring 이 닫는 빈은 `AimonStack` 하나뿐" 으로 좁히면 틀린다 — 스택이 **소유하지도 닫지도 않는**
자원(`LlmClient`, 애플리케이션이 낸 `KnowledgeStore`)에서 추론을 끄면 소멸 간선이 하나도 남지 않아
커넥션 풀이 누수한다. 세는 것은 간선의 개수이지 간선의 주인이 아니다.

| 빈 | 스코프 | 만드는 주체 | `@Bean` 선언 | 이유 |
|----|-------|-----------|-------------|------|
| `AimonStack` | Application | 스타터 | `destroyMethod = "close"` | **유일한 소멸 간선** |
| `SessionRouter` | Application | `AimonStack` | `destroyMethod = ""` | `close()` 는 드레인하지 않는다. 스택이 `closeGracefully(timeout)` 로 닫는다 |
| `AgentRegistry` / `AgentRuntimeRegistry` | Application | `AimonStack` | `destroyMethod = ""` | 빌려주는 것 |
| `SessionRecordStore` | Application | `AimonStack` 또는 호스트 | `destroyMethod = ""` | |
| `SchedulingEngine` | Application | `AimonStack` | `destroyMethod = ""` | 스택이 순서대로 닫는다 |
| `RoutineExecutor` / `*TaskScheduler` | Application | `AimonStack` | **빈으로 노출하지 않음** | `shutdown()` 추론 + `SchedulingEngine.close()` 와 이중 소멸 |
| `KnowledgeStore` | Application | 슬라이스 9 또는 호스트 | **추론 `close()` 를 그대로 둔다** | `AimonStackBuilder` 는 스토어를 teardown 에 등록하지 않으므로 스택 쪽 간선이 애초에 없다. `destroyMethod = ""` 로 막으면 소멸 간선이 **하나도** 남지 않는다. 애플리케이션이 낸 스토어를 스택이 닫는 것은 반대로 "만든 쪽이 닫는다" 위반이므로, 어느 쪽이든 Spring 이 유일한 간선인 것이 맞다 |
| `VirtualFileSystemFactory` | Application | `AimonStack` 또는 호스트 | `destroyMethod = ""` | 팩토리가 만든 VFS 는 `AgentRuntime` 이 닫아 주지 않으므로 **스택이 런타임 축출과 함께** 닫는다 (§4.11) |
| `CredentialStoreFactory` | Application | `AimonStack` 또는 호스트 | `destroyMethod = ""` | 테넌트별 인스턴스도 같은 규칙 |
| `AimonAgents` | Application | 스타터 | `destroyMethod = ""` | 조회·무효화 파사드. 레지스트리를 빌려 쓸 뿐 소유하지 않는다 (§4.11) |
| `AgentRuntimeResolver` / `AgentRuntimeCache` | Application | `AimonStack` | `destroyMethod = ""` | **담는 것은 agent-scoped 이지만 자신은 application-scoped** (scope-model §5.2). 축출·소멸은 스택이 refcount 를 보고 한다 (§4.11) |
| `CapabilityResolver` | Application | 슬라이스 11 또는 호스트 | `destroyMethod = ""` | 무상태. 닫을 자원이 없다 |
| `LlmClient` | Application | 슬라이스 2 | **추론 `close()` 를 그대로 둔다** | 스택은 클라이언트를 소유하지도 teardown 에 등록하지도 않는다. 추론을 끄면 커넥션 풀이 누수한다 |
| **`OrcaAgentRuntime`** | Agent | `AimonStack` (지연 생성) | **빈으로 노출하지 않음** | 노출하면 MCP 가 끊긴다. 게다가 `(agentRef × discriminator)` 로 개수가 정해지지 않아 빈이 될 수 없다 (§4.11) |
| **`LiveSession`** | Live session | 요청 시점 | **빈으로 노출하지 않음** | 생성자가 `OnSessionStart` 를 발화 |
| **`OrcaAgentExecutor(Factory)`** | Agent | `AimonStack` (에이전트당 1개) | **빈으로 노출하지 않음** | §2.4 의 두 위험 |
| `AutoCloseable 도구` (예: `BashTool`) | Agent | `AimonStack` | **빈으로 노출하지 않음** | 런타임이 닫지 않으므로 스택이 소유 |
| `RewakeService` | Application | `AimonStack` | `destroyMethod = ""` | 구현만 `close()` 를 가져 추론이 걸린다. 스택이 §5.3 순서대로 닫는다 |
| `PendingTurnReaper` | Application | `AimonStack` | **빈으로 노출하지 않음** | `start()`+`close()` 를 둘 다 가져 빈으로 만들면 추론 destroy 가 걸린다 (§4.7) |
| `PendingTurnRegistry` | Application | `AimonStack` | `destroyMethod = ""` | 승인 REST 를 붙일 수 있도록 **조회용으로만** 노출 (§4.7) |
| `SessionStore` | **Node** | `AimonStack` | **싱글턴으로 공유 금지** | 계약상 세션 매니저당 1개 (§4.9) |
| `DataSource` / `MongoDatabase` / `RedisClient` / `org.quartz.Scheduler` | Application | **호스트 앱** | 주입만 — 절대 닫지 않음 | 빌린 빈 |

**예외는 없다** — `RewakeService` 도 아니다. 인터페이스는 `AutoCloseable` 이 아니고
`at.aimon.core.hook.rewake.impl.DefaultRewakeService` 만 `close()` 를 가지므로,
인터페이스 타입으로 선언해도 Spring 은 **런타임 클래스**에서 `close()` 를 찾아낸다. 그렇다고 추론을 켜
두면 안 된다 — `AimonStack` 이 이미 §5.3 의 순서대로 `rewakeService` 를 닫으므로 **누수는 애초에 없고**,
추론을 켜 두면 같은 객체가 두 번 닫히면서 그 두 소멸의 상대 순서를 Spring 이 보장하지 않는다. 누수를 막는
예외가 아니라 D4 를 깨뜨리는 **두 번째 소멸 간선**이다.

같은 이유로 `PendingTurnReaper` 도 빈이 아니다 — `start()` 와 `close()` 를 **둘 다** 가지므로
빈으로 만드는 순간 추론 destroy 가 걸린다(§4.7).

주의사항 둘:

- `@Bean(destroyMethod = "")` 은 **`DisposableBean.destroy()` 를 억제하지 못한다.**
- 빌린 빈은 `AimonStackSpec` 에 `@ExternallyManaged` 로 표시해 `AimonStack` 이 소멸 목록에 넣지 않게 한다.

이 규칙은 리뷰 체크리스트 항목이자 **테스트로 강제**한다.

### 4.5 라이프사이클 — `SmartLifecycle` 2단

기동/종료 순서의 소유자는 두 개의 `SmartLifecycle` 빈이다. 선례를 그대로 따른다 — Spring 자신의
`SchedulerFactoryBean` 이 `SmartLifecycle` 이고(phase `Integer.MAX_VALUE` = 가장 늦게 start,
가장 먼저 stop), Spring Kafka 의 `KafkaListenerEndpointRegistry` 가 "컨테이너를 빈으로 만들지 않고
인프라 빈 하나가 N개의 수명을 관리"하는 패턴이다.

```java
/** 에이전트 런타임 등록. 웹 서버보다 먼저 start, 나중 stop. */
final class AimonRuntimeLifecycle implements SmartLifecycle {
    @Override public int getPhase() { return Integer.MAX_VALUE - 4096; }
    @Override public void start() {
        stack.startRuntimes();                // ① 런타임 먼저
    }
    @Override public void stop() { /* no-op — 소멸은 stack.close() */ }
}

/** 스케줄링. 가장 늦게 start, 가장 먼저 stop (SchedulerFactoryBean 의 phase 의미). */
final class AimonSchedulingLifecycle implements SmartLifecycle {
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public boolean isAutoStartup() { … }        // aimon.scheduling.auto-startup
    @Override public void start() {
        stack.startScheduling();              // ② engine.start() → ③ task register
    }
    @Override public void stop() {
        stack.stopScheduling();
    }
}
```

- **①→②→③ 이 §2.4 의 양방향 제약**이다. 선언된 작업을 `@PostConstruct` 에서 등록하면 refresh 중에
  `TaskSchedulerException` 이 나고, 런타임 등록이 늦으면 조용히 재시도되다 드롭된다.
- 웹 서버의 `WebServerStartStopLifecycle` 은 **`Integer.MAX_VALUE - 2048`** 이다. `- 1024` 는 그 위에
  있는 `WebServerGracefulShutdownLifecycle` 이고, 두 숫자를 맞바꿔 읽으면 `AimonRuntimeLifecycle` 이
  웹 서버와 **같은 phase 에 앉는다** — 런타임 phase 가 `- 4096` 인 이유가 이것이다. 세 숫자를 그 아래위로
  두면 **런타임 등록 → 소켓 오픈 → 스케줄링 시작**, 종료는 그 역순이 된다. 테스트는 이 숫자들을 상수로
  베끼지 않고 Boot 상수에서 **유도**해 검사한다.
- 스케줄링이 `Integer.MAX_VALUE` 인 것은 "가장 늦게 켜고 가장 먼저 끈다"는 뜻이다 — 루틴이 도는 동안
  스토어와 런타임이 살아 있음이 보장된다.
- **디스크 쓰기와 파일 스캔을 refresh 밖으로**: `BundledSkillMaterializer` 의 디스크 쓰기와
  `DefaultCommandRegistry.initialize()` 의 `.aimon/commands` 스캔은 빈 생성 중이 아니라 `start()` 에서
  일어나야 한다. 그래야 파일시스템 상태 때문에 컨텍스트 refresh 가 실패하지 않는다.
- **refresh 이후에 만들어지는 런타임**(런타임 중 새 테넌트 도착 등)은 `autoStartup` 의 적용을 받지
  않고 즉시 시작된다 — Spring Kafka 가 같은 문제를 문서화하고 있다. 지연 생성 경로가 필요하면 별도
  정책을 둔다.
- **종료 타임아웃**: `spring.lifecycle.timeout-per-shutdown-phase` 는 이 설계에서 아무것도 제한하지
  않는다 — 부족한 것이 아니라 애초에 도달하지 않는다. 스택 소멸은 `destroyBeans()` 에서 일어나고
  라이프사이클 타임아웃은 그 이전 단계에서만 살기 때문이다. 실제로 종료를 짧게 만드는 손잡이는
  `aimon.session.shutdown-drain-timeout` 이며, `stopScheduling(timeout)` 같은 오버로드는 만들지
  않았다 — 닿을 곳이 없다.

### 4.6 세션 접근 — `LiveSession` 을 빈으로 만들지 않는다

`LiveSession` 은 thread-safe 하지 않고, **생성자에서 `OnSessionStart` 훅을 발화하며**
(`DefaultLiveSession`), 수명이 요청/세션 어느 쪽과도 정확히 일치하지 않는다.
빈으로 만들면 컨텍스트 refresh 중에 임의의 사용자 훅 코드가 — 그 훅이 필요로 하는 다른 빈이 준비되기
전에 — 실행된다.

이 결론은 CLI 가 `agentExecutor.getHookExecutionManager()` 를 넘기게 되면서 오히려 강해졌다 — 훅이
배선됐다는 것은 곧 `LiveSession` 생성이 임의의 사용자 코드를 실행한다는 뜻이다.

호스트는 파사드만 본다.

```java
package at.aimon.spring.boot;

public interface AimonSessions {
    /** 동기 턴 1건. 내부적으로 SessionRouter 를 통해 홀더 노드에서 실행된다. */
    AgentExecutionResult submit(SessionId sessionId, String input);

    /**
     * 에이전트와 테넌트를 지정한 턴. agentRef 는 aimon.agents 의 키,
     * discriminator·channel 은 options 에 담긴다 (§4.11).
     */
    AgentExecutionResult submit(SessionId sessionId, String agentRef, String input, LiveSessionOptions options);

    /** 비동기 + 이벤트 스트림 (SSE 용). */
    SubmitDisposition submitAsync(SessionId sessionId, String input, AgentEventListener listener);

    /** 진행 중 턴 중단. TurnId 를 요구한다 — 무주소 interrupt 는 엉뚱한 턴을 끊을 수 있다. */
    void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason);

    void release(SessionId sessionId);   // 핸들 해제(레코드는 남는다)
}
```

**단일 노드에서도 `SessionRouter` 를 쓴다**(§6 D3). `SessionRouterBuilder` 는 `DeploymentMode.SINGLE_NODE`
에서 네 SPI 를 in-memory 로 자동 기본값 처리하므로, 단일 노드 → 분산이 프로퍼티 한 줄이 된다.
그리고 라우터가 세션별 핸들 캐시와 홀더 선출을 담당하므로, thread-unsafe 한 `LiveSession` 이
두 요청에 동시에 노출되는 사고를 구조적으로 막는다.

`LiveSessionOpener` 는 스타터가 제공하며 **7-인자 `DefaultLiveSession` 생성자**를 직접 호출한다 —
`LiveSessionFactory.open()` 은 큐와 훅에 `null` 을 넣으므로 쓰지 않는다. 그리고 턴 종료 후
`LATER` 티어 드레인(코어가 하지 않고 호스트에 위임한 부분)을 파사드가 재현한다.

**세션은 에이전트에 바인딩된다.** `SessionStore.claim` 이 리스 선출 다음, 레코드 프로비저닝 **이전**에
에이전트 바인딩을 검증하므로(`SessionRecord.agentRef`), 이미 `ops-agent` 로 열린 세션에 `inquiry-agent`
로 `submit` 하면 실패한다. 이것을 스타터가 완화해 주지 않는다 — 대화 이력의 시스템 프롬프트와 도구
집합이 도중에 바뀌는 것은 복구가 아니라 오염이다. 에이전트를 바꾸려면 **새 `SessionId`** 를 쓴다.
`agentRef` 를 생략한 2-인자 `submit` 은 기존 레코드의 바인딩을 따르고, 레코드가 없으면
`aimon.agent-defaults.default-agent` 를 쓴다.

### 4.7 스킬 승인 — 헤드리스

트리에 헤드리스 `SkillApprovalChannel` 구현이 **없다**. `aimon-cli` 의 `InteractiveSkillApprovalChannel`
(JLine 바인딩) 이 유일하다. 서버는 다음 네 모드 중 하나를 고른다.

| `aimon.skill.approval.mode` | 동작 | 용도 |
|---|---|---|
| `deny` (**기본**) | 모든 ASK 를 DENY 로 확정 | 무인 서버 |
| `allow-list` | 목록에 있으면 ALLOW, 나머지 DENY | 알려진 스킬만 |
| `suspend` | 턴을 SUSPENDED 로 중단하고 `PendingTurnRegistry` 에 적재 | 사람 승인 API 를 붙일 때 |
| `channel` | 호스트가 `SkillApprovalChannel` 빈을 제공 | 커스텀 |

`suspend` 는 **채널을 주지 않는 것**으로만 표현된다. 채널이 있으면 pre-flight scanner 가 승인을
인라인으로 해소해 버려 턴이 애초에 중단되지 않기 때문이다 — 즉 `suspend` 는 `null` 채널이고, 그 사실은
`skill-approval` degradation 으로 기동 시점에 보인다.

스타터가 함께 배선하는 것 — 다만 **모드와 무관하게 언제나** 배선한다:

- `PendingTurnReaper` — 없으면 `InMemoryPendingTurnRegistry` 가 무한히 증가한다. 다만 **빈으로
  노출하지는 않는다**: `start()` 와 `close()` 를 둘 다 가지므로 빈이 되는 순간 추론 destroy 가
  걸린다. 기동은 `AimonRuntimeLifecycle.start()`(§5.1), 소멸은 `AimonStack.close()`(§5.3)가 소유한다.
  이 타입은 §2.1 가 열거한 "스택이 직접 소유해야 하는 풀 소유자"의 실례이기도 하다.
- 승인 REST 엔드포인트를 붙일 수 있도록 `PendingTurnRegistry` 를 `destroyMethod = ""` 로 노출.

`channel` 모드 구현자를 위한 계약 경고는 문서화한다: **채널은 동기적으로 블록해야 한다.**
WebSocket 알림만 보내고 즉시 반환하면 결정이 기록되지 않은 채 scanner 가 진행하고, `SkillTool` 시점에
정책이 다시 ASK 로 평가되어 **승인이 영원히 반영되지 않는 무한 ask 루프**가 된다.
(이 경고는 `SkillApprovalSpec.channel(...)` 의 javadoc 에도 들어갔다 — 문서만 읽고 구현하는 사람보다
IDE 만 보고 구현하는 사람이 많다.)

### 4.8 워크스페이스와 리소스 로딩

- **`aimon.workspace.root` 는 필수**다. jar 위치/`user.dir` 파생을 하지 않는다. 기동 시 존재·쓰기 가능
  여부를 검증하고, `ensure-writable=true` 인데 불가능하면 **기동을 실패시킨다**.
- **root 는 에이전트 하나의 작업 디렉토리가 아니라 트리의 뿌리다.** 실제 작업 디렉토리는
  `<root>/<agentRef>/<discriminator>/` 이고 discriminator 가 없으면 `_default` 를 쓴다(§4.11).
  `VirtualFileSystemFactory` 가 이 서브트리로 샌드박싱하므로, 에이전트나 테넌트가 서로의 파일을
  경로 조작으로 넘볼 수 없다. `agentRef`/discriminator 는 경로 세그먼트가 되므로 `/`·`..`·`:` 을
  포함할 수 없고, 위반은 **바인딩 시점에** 거부한다(§7).
- `user.home` 을 읽는 자리(`HookConfigLoader.createDefault()`, hook hot reload)는 프로퍼티에서 받은
  경로를 주입한다. 두 호출부 모두 이미 `Path` 주입 오버로드를 받으므로 코어 변경 없이 가능하다.
- **클래스패스 리소스 열거는 fat jar 에서 동작한다 — 실증 확인됨.** `spring-boot-loader-tools` 의
  `Repackager` 로 실제 Boot jar 를 만들어 `java -jar` 로 돌린 결과 **신·구 로더 양쪽에서 정상 동작**한다.
  - `ClasspathResourceTreeWalker.listFiles` 의 프로토콜 분기는 Boot 가 신형
    (`jar:nested:/app.jar/!BOOT-INF/classes/!/…`) 과 구형(`jar:file:/app.jar!/BOOT-INF/classes!/…`)
    모두에 대해 프로토콜 `jar` 를 주므로 warn-and-empty 로 떨어지지 않는다.
  - 결정적으로, `jarFile.entries()` 가 **중첩 루트 기준 상대 경로**(`agents/…/SKILL.md`, `BOOT-INF/…` 가
    아님)를 돌려주므로 `name.startsWith(prefix)` 가 그대로 매치된다. prefix 불일치는 **없다.**
  - 앵커 폴백은 실제로 하중을 받는다 — 디렉토리 엔트리 없이 패키징된 jar 에서
    `getResource(dir)` 가 null 이어도 앵커를 주면 목록이 나온다. 유일한 프로덕션 호출자인
    `BundledSkillMaterializer` 가 **항상** 앵커를 넘기므로 영구히 안전 경로에 있다.

  따라서 **`ResourceEnumerator` SPI 는 walker 때문에 필요한 것이 아니다.**
  대신 실제 위험은 **index 파일 게이팅과 다중 jar 취합**으로 옮겨간다 — 그리고 이쪽이 Boot 앱에서
  훨씬 더 잘 발생한다.

  1. `ClasspathAgentBundleLoader` 는 서브에이전트/스킬 레지스트리를
     `getResource("<dir>/index") != null` 로 게이트하고, 없으면 `Optional.empty` 를 돌려준다.
     index 없이 스킬을 담아 배포한 모듈은 **스킬 0개인 에이전트로 로드되고 debug 로그만 남는다**
     (`BOOT-INF/lib` 케이스로 실증됨).
  2. `ClasspathSkillRepository` / `ClasspathSubagentRepository` 는 같은 index 가
     **여러 jar 에 있으면 WARN 후 첫 번째만 쓴다**. 여러 aimon 모듈을 합치는 Boot 앱이 정확히 그
     다중 jar 상황이므로 **스킬이 조용히 누락된다.** 이것이 이 항목의 진짜 위험이다.
     추측이 아니다 — 내부 운영 앱의 `AgentConfiguration` 은 번들 자동 발견에
     `PathMatchingResourcePatternResolver` 로 `classpath:agents/*/agent.md` 를 쓴다. `classpath*:` 가
     아니라 `classpath:` 이므로 **첫 번째 클래스패스 루트 하나만** 스캔된다 — Spring 은
     `determineRootDir` 로 `classpath:agents/` 를 뽑고, 거기엔 패턴이 없으므로 루트 리소스를 **1개만**
     얻은 뒤 그 아래 `*/agent.md` 는 **전부** 수집한다. 따라서 누락 단위는 '에이전트'가 아니라
     **'두 번째 이후의 클래스패스 루트(jar)'** 다. 그 앱의 번들들은 한 리소스 루트에 모여 있어
     **지금은 정상 발견된다** — 문제는 번들이 여러 jar 로 흩어지는 순간 두 번째 이후 jar 의
     `agents/` 가 통째로 사라지는 것이고,
     여러 aimon 모듈을 합치는 Boot 앱이 정확히 그 상황이다. 스타터의 발견 경로는 `classpath*:` 로
     고정한다(§4.11).
  3. 프로토콜 분기의 `default:` 는 `file`/`jar` 가 아닌 스킴에 빈 목록을 준다 — WAR 배포(`war:`),
     `jrt:`, GraalVM 이미지(`resource:`)에서 조용히 실패한다. Boot 실행 가능 jar 는 해당 없음.

  대응은 단일 리소스 게이트를 `getResources()` 병합으로 교체하는 쪽을 택했다 —
  `ClasspathIndexReader` 가 모든 루트를 열거한다. 빌드 시 index 생성은 택하지 않았다: 모듈마다 자기
  index 를 들고 오면 그만이다. 그와 별개로 **`bootJar` 산출물을 실제로 띄워 스킬 목록을 비교하는
  통합 테스트**를 둔다 — fat jar 의 클래스패스는 그것으로 띄운 JVM 안에만 존재하므로, 그 티어가
  없으면 "첫 번째 jar 만 읽는다" 를 어떤 테스트로도 관찰할 수 없다.

  개발/운영 갈림은 **실재했고, 이제 갈리지 않는다.** `AdaptiveAgentBundleLoader` 는
  `"file".equals(url.getProtocol())` 로 둘 중 하나를 고르는 대신 둘을 겹친다(클래스패스 아래, 작업
  디렉토리 위). 갈림의 경계도 처음 예상과 달랐다 — 스킬은 양쪽에서 살아남았고
  (`BundledSkillMaterializer` 가 로더와 무관하게 클래스패스 전체를 훑는다), 사라지던 것은 그런 보정이
  없는 **서브에이전트**였다. 실제 경계는 "jar 냐 디렉토리냐" 가 아니라 "materializer 를 거치는
  종류냐" 다. 스타터는 그와 별개로 기동 시 기대한 스킬/서브에이전트가 실제로 resolve 되었는지 확인하고
  다르면 실패하거나 WARN 한다(§4.10).

- **ClassLoader 는 명시적으로 넘긴다.** `getContextClassLoader()` 사용처는 코어 4곳
  (`ClasspathSkillRepository`, `ClasspathSubagentRepository`, `AdaptiveAgentBundleLoader`,
  `ClasspathAgentBundleLoader`) + CLI 2곳이다. **코어 4곳만 TCCL 기본값 편의 오버로드**이고
  (넷 다 명시 `ClassLoader` 오버로드를 나란히 가진다), CLI 2곳(`AgentSetupFactory`)은 ClassLoader 를
  **필수 인자로 받는** API — `AdaptiveAgentBundleLoader` 4-인자 생성자와
  `OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry`(단일 시그니처이며 `requireNonNull` 한다)
  — 에 TCCL 을 값으로 넘기는 **호출부**다. 후자에는 TCCL 기본 오버로드가 아예
  없다. 즉 **6곳 전부 명시 ClassLoader 경로가 이미 존재한다.** Boot 로더 하에서 TCCL 은 앱·코어와 동일한
  로더이므로 현재도 안전하지만, 스타터는 관례상 `ApplicationContext.getClassLoader()` 를 넘기는 명시
  경로를 쓴다 — 그래야 빈을 만드는 스레드가 무엇이든 동작이 달라지지 않는다.

### 4.9 멀티 인스턴스 슬라이스

`aimon.session.mode=distributed` 일 때 백엔드별로 필요한 것이 다르고, **Spring Boot 가 이미 만들어 둔
빈을 그대로 쓸 수 없는 지점**이 있다.

| 백엔드 | 코어가 요구하는 타입 | Spring Boot 가 주는 타입 | 간극 |
|--------|---------------------|------------------------|------|
| MongoDB | `com.mongodb.client.MongoDatabase` | `MongoClient` / `MongoDatabaseFactory` | `getDatabase(name)` 한 홉 + db 이름 프로퍼티 |
| PostgreSQL | `javax.sql.DataSource` **+ 두 번째 DataSource + raw jdbcUrl + Properties** | `DataSource` 하나 | 시그널 버스의 LISTEN 커넥션이 풀 밖에서 `DriverManager` 로 열린다 → 프로퍼티에서 별도 바인딩 필요 |
| Redis | Lettuce `StatefulRedisConnection` / `StatefulRedisPubSubConnection` | `RedisConnectionFactory` | Jedis 사용자는 Lettuce 커넥션이 아예 없음 → `RedisClient` 빈을 요구하거나 `spring.data.redis.*` 에서 직접 생성 |

**스키마는 런타임이 만들지 않는다.** Mongo 는 `db/mongodb/init.js` 를 `mongosh` 로, Postgres 는
`db/postgres/V1__init.sql` 을 `psql` 로 운영자가 선적용해야 한다. Mongo 는 추가로 **레플리카 셋**이
필요하다(change stream). 따라서 "의존성 추가하고 끝"이 성립하지 않으며, 스타터는
(a) 기동 시 스키마 존재를 확인해 없으면 명확한 메시지로 실패하거나 (b) 옵션으로 Flyway 를
`classpath:db/postgres` 에 대해 구동한다.

**소멸**: `SessionRouter.close()` 는 자기 캐시/퍼블리셔/내부 executor 만 닫고 **SPI 도 드라이버 커넥션도
닫지 않으며 드레인도 하지 않는다**(§2.1). 시그널 버스 중 **스레드를 소유하는 것은 둘**이다 — Mongo 는
change stream watcher 스레드를(`MongoSessionSignalBus`, close 에서 join), Postgres 는 생성자에서
`ListenDispatcher.start()` 로 LISTEN 스레드를 띄운다(`PostgresSessionSignalBus` →
`internal/ListenDispatcher`). Redis 는 스레드를 소유하지 않는다 — 호출자 소유의 Lettuce 커넥션에
리스너만 등록하고 `close()` 는 `removeListener` + 핸들러 정리뿐이며(`RedisPubSubSignalBus`),
디스패치 스레드는 호스트가 소유하는 `RedisClient` 쪽에 있다(§4.4 의 '빌린 빈').

셋 다 정리할 것이 있지만 **`AimonStack` 의 소멸 목록에 등록하지는 않는다** — SPI 는 애플리케이션이
만든 것이므로 만든 쪽이 닫고, 등록하면 한 자원에 소멸 경로가 둘 생긴다(§4.4). 남는 문제인 **순서**는
`SessionSpec` `@Bean` 메서드 안에서 SPI 를 해석해 의존 빈 간선을 만드는 것으로 해결한다 — 컨테이너가
의존자를 먼저 소멸시키므로 버스는 스택이 드레인을 마친 뒤에 닫힌다.

**`SessionStore` 는 node-scoped 다** — 계약상(`SessionStore` javadoc) 세션 매니저당 하나이며,
한 JVM 에 매니저가 둘이면 **같은 두 백엔드 위에 스토어도 둘** 만들어야 한다(리스 추적 상태를 공유하면
펜싱이 깨진다). 그 두 백엔드(`SessionRecordStore`, `SessionLeaseStore`)는 application-scoped 이므로
공유해도 된다. 이름이 `*Store` 로 끝나지만 application-scoped 가 아닌,
[`scope-model.md` §5.2](../../overview/scope-model.md) 가 경고하는 컨테이너-내용물 함정의 실례다.
싱글턴 `@Bean SessionStore` 하나는 멀티 매니저 배포와 멀티 노드 통합 하네스를 조용히 깨뜨린다.

### 4.10 관측 — 조용한 degrade 를 시끄럽게

코어는 실패를 WARN 으로 삼키는 자리가 많다. 스타터는 조립 결과를 **검증 가능한 사실**로 만든다.

```java
public final class HealthReport {
    boolean llmConfigured;              // 프로바이더 확인 (Anthropic 의 isConfigured() 는 하드코딩 true — 신뢰 불가)
    int agentsRegistered;               // 기대치와 비교
    Map<String, Integer> skillsPerAgent;// 기대치 미달이면 index 누락 또는 다중 jar 취합 실패 (§4.8)
    Map<String, Integer> subagentsPerAgent;
    boolean sessionStoreDurable;        // in-memory 면 false → 운영 배포에서 WARN
    boolean schedulingRehydrated;
    List<String> degradations;          // "rewake=NOOP", "hooks=disabled" …
}
```

`AimonHealthIndicator` 로 Actuator 에 노출하고, `aimon.fail-fast=true`(기본 `true`)면 `degradations` 가
비어 있지 않을 때 기동을 중단한다.

`HealthReport` 는 **조립 시점의 불변 스냅샷**이므로 기동 이후에 발견되는 degrade 는 담을 수 없다.
그래서 thread-safe 한 `RuntimeDegradations`(문자열 Set) 를 따로 두고, indicator 가 스냅샷과 이것을
**합쳐서** 보고한다. 여기에 들어간 항목은 `fail-fast` 의 대상이 아니다 — 컨텍스트는 이미 기동했으므로
health 를 내리는 것 외에 할 수 있는 일이 없다. 현재 이 채널을 쓰는 곳은 §6 D13 의 `stale-config`
하나이며, 향후 MCP 재접속 실패 같은 런타임 degrade 가 같은 자리를 쓴다.

---

### 4.11 다중 에이전트 — N개 에이전트 × M개 테넌트

이 절은 나머지 §4 가 암묵적으로 깔고 있던 "에이전트는 하나" 가정을 걷어낸다. 근거는 추측이 아니라
실측이다 — 이 문서가 여러 번 인용하는 **내부 운영 앱**은 이 스타터가 겨냥하는 바로 그 형태의
호스트이고, 거기서 에이전트는 하나가 아니다. `classpath:agents/*/agent.md` 로 번들을 자동 발견하고,
OU(조직 단위)마다 자격증명이 갈리고, 에이전트마다 다른 도구 집합이 붙고, 에이전트 정의 자체가 REST 로
생성·수정되는 **런타임 도메인 엔티티**다. 단일 에이전트만 지원하는 스타터는 그 앱을 하나도 단순하게
만들어 주지 못한다.

> 그 앱은 이 저장소 밖에 있어 링크할 수 없다. 아래에서 인용하는 사실은 전부 그 코드를 읽고 확인한
> 것이며, 이 문서가 "실측" 이라고 쓸 때 가리키는 것이 그것이다.

#### 축이 둘이다 — 혼동하면 격리가 깨진다

| 축 | 무엇이 갈리는가 | 식별자 | 개수 |
|----|----------------|--------|------|
| **에이전트** (`agentRef`) | 시스템 프롬프트, 도구·커맨드 집합, 스킬, 서브에이전트, 모델 | 번들 이름 | 유한 — 설정/번들로 열거 가능 |
| **테넌트** (`discriminator`) | 자격증명, 워크스페이스, 지식 스코프 | OU/테넌트 id | **무한** — 런타임에 늘어남 |

두 축의 곱이 `AgentRuntimeId` 다: `agent:<name>` 또는 `agent:<name>:<discriminator>`.
이것은 스타터가 발명하는 규약이 아니라 코어가 이미 강제하는 형식이며(`from(Agent)` /
`from(Agent, String)`, `generate()` 는 존재하지 않는다 — @docs/overview/scope-model.md §4),
그래서 cron 재발화나 다른 노드에서도 같은 값이 재현된다.

IMPORTANT: **내부 운영 앱의 현재 구현을 그대로 베끼면 안 된다.** 그 앱은 `aimon-core 0.1.18`,
즉 rescoping 이전 API 위에 있어서 세션 오프너가 **`ConversationId` 마다 컨텍스트를 새로 만든다**. 0.2.0 에서 그것은 명시적 금지 사항이다 — `AgentRuntime` 은 agent-scoped 이고 세션들을
가로질러 재사용된다. 그 앱이 컨텍스트 재생성으로 얻고 있는 테넌트 분리는 0.2.0 에서 **discriminator** 가
담당한다. 이 스타터는 후자로 배선하고, 그 앱의 마이그레이션은 §9 으로 남긴다.

#### 런타임 생성은 축마다 다르다 — 에이전트는 eager, 테넌트는 lazy

"전부 기동 시 생성" 도 "전부 지연 생성" 도 틀렸다. 두 축의 성질이 다르기 때문이다.

| 키 | 개수 | 생성 시점 | 근거 |
|----|------|----------|------|
| `agent:<name>` | 유한 — 설정·번들로 열거됨 | **기동 시(eager)** | 이 시점에 MCP 접속·도구 해석이 일어나므로, 오배선을 첫 요청이 아니라 기동에서 터뜨릴 수 있다(§4.10 fail-fast) |
| `agent:<name>:<tenant>` | 무한 — 런타임에 늘어남 | **첫 사용 시(lazy)** | 열거가 불가능하다. 미리 만들 수 없다 |

즉 discriminator 없는 런타임 N개는 §5.1 ①에서 만들어 등록하고, 테넌트별 런타임은 `AgentRuntimeResolver`
가 레지스트리 미스일 때 만든다. **생성 지점은 이 둘뿐**이고 `LiveSessionOpener` 는 아니다.

테넌트 런타임의 MCP 접속은 이 모델에서 기동 검증을 받지 못한다. 이는 감수하는 트레이드오프이며 —
테넌트를 미리 알 수 없으니 대안이 없다 — 대신 첫 생성 실패를 `RuntimeDegradations`(§4.10)에 올려
health 로 보이게 한다. MCP 서버 정의가 에이전트 공통이면 eager 경로에서 이미 검증되므로, 실무에서
남는 미검증 표면은 **테넌트별 MCP 자격증명**뿐이다.

무한 증가를 막으려면 축출이 필요한데, 여기에 함정이 있다. `AgentRuntime.close()` 는
**다른 세션이 아직 그 런타임을 쓰고 있으면 호출하면 안 된다**(scope-model §4 의 첫 번째 금지 항목).
그래서 축출은 시간 기준 단독으로는 안전하지 않고, **사용 카운트가 0인 상태로 유휴 TTL 을 넘긴** 항목만
닫는다.

```
aimon:
  agent-runtime:
    idle-ttl: 30m          # 마지막 사용 이후. 사용 중(refcount>0)이면 타이머가 돌지 않는다
    max-entries: 500       # 도달 시 TTL 을 넘긴 유휴 항목을 먼저 회수하고, 없으면 거부. 사용 중 항목은 절대 축출하지 않는다
    eviction: idle         # idle | never  ← 선택자. never 면 프로세스 수명 내내 유지
```

**축출 대상은 테넌트 런타임뿐이다.** discriminator 없는 `agent:<name>` N개는 기동 시 만들어진 유한
집합이고 fail-fast 의 근거이므로, 유휴해도 닫지 않는다 — 닫으면 다음 요청이 기동 검증을 받지 않은
경로로 재생성하게 되어 §5.1 의 보증이 무너진다.

`max-entries` 를 넘겼는데 축출 가능한(유휴) 항목이 하나도 없으면 **새 런타임 생성을 거부**하고
`AgentRuntimeExhaustedException` 을 던진다. 조용히 상한을 넘겨 힙을 밀어내는 것보다 낫다.
이 신호는 `RuntimeDegradations`(§4.10)에 **올리지 않는다** — 그것은 스택이 **무엇 없이 조립되었는가**의
기동 시점 스냅샷이라 런타임 중에 오르내리는 값이 들어갈 자리가 아니다. 고갈은 대신 health 검사 `agent-runtime-capacity`(`!isSaturated()`)와
`aimon.agent.runtimes.*` 미터로 나간다.

거부와 강제 축출 중 어느 쪽이 옳은지는 아직 열려 있다(§9). 다만 그 판단에 필요한 숫자는
`.active` 하나가 아니다 — **`.active` 와 `.leased` 의 차이**가 "500개가 전부 턴을 돌리는 중"(상한을
올린다)과 "3개만 쓰이고 나머지는 TTL 이 안 지났을 뿐"(TTL 을 줄인다)을 가른다. 두 처방은 반대 방향이다.

축출·무효화가 실제로 `close()` 를 부르는 시점은 **`AgentRuntime` 이 소유한 것만** 닫는다는 뜻이다
(`McpClientManager`, agent-scoped `WorkflowRunner`). 그 런타임에 딸린 `VirtualFileSystem` 과
`AutoCloseable` 도구는 `AgentRuntime.close()` 가 닫아 주지 않으므로(scope-model §2 의 "fan-out 은 없다"),
`AimonStack` 이 런타임별 소멸 목록을 따로 들고 함께 닫는다. 이것을 빠뜨리면 테넌트가 늘어날 때마다
파일 핸들이 샌다 — 단일 에이전트 설계에서는 스택 소멸 한 번으로 덮이던 자리다.

#### 도구·훅은 스타터가 한 번만 조립한다

내부 운영 앱의 구체적 통증 하나: **동일한 6개 도구 프로바이더 목록이 `AgentSessionConfiguration` 과
`AgentConfiguration` 두 곳에 복제되어 있다.** 세션 경로와 스케줄링 경로가 각각 컨텍스트를 만들기 때문인데,
둘이 어긋나면 "채팅으로는 되는데 cron 으로는 안 되는" 버그가 된다. 스타터의 존재 이유가 이런 것이므로
**기저 프로바이더 목록은 스타터 안에서 단 한 번 만들어지고 두 경로가 같은 것을 공유한다.**

에이전트별 차이는 복제가 아니라 **수집되는 확장점**으로 표현한다. 내부 운영 앱의
`AgentExecutionContextCustomizer` / `AgentExecutionHookRegistrar` 가 이미 이 모양이므로, 그것을
프레임워크 중립 인터페이스로 `aimon-bootstrap` 에 올리고 스타터가 `List<>` 로 수집한다.

```java
public interface AimonAgentCustomizer {
    /** 이 커스터마이저가 붙을 에이전트인지. agentRef·번들·해석된 프로퍼티로 판단한다. */
    boolean supports(AgentDescriptor agent);

    default List<OrcaToolProvider> toolProviders(AgentDescriptor a)    { return List.of(); }
    default List<OrcaCommandProvider> commandProviders(AgentDescriptor a) { return List.of(); }
    default void registerHooks(AgentDescriptor a, HookRegistry hooks)  { }

    default int getOrder() { return 0; }
}
```

내부 운영 앱은 `supports` 를 2개 오버로드(`(type, bundle)` / `(type, bundle, properties)`)로 늘려 왔고
default 메서드로 하위호환을 유지하고 있다. 같은 패턴이 `getAdditionalToolProviders` /
`getAdditionalCommandProviders` 에도 반복되어 인터페이스 전체가 오버로드 쌍 3개(메서드 6개)다.
신규 API 이므로 그 부채를 물려받지 않고 **인자 하나짜리
`AgentDescriptor`** 로 시작한다 — 판단에 필요한 것이 늘어나도 시그니처가 아니라 descriptor 가 늘어난다.

`getOrder()` 는 필수다. 훅은 등록 순서가 곧 실행 순서이고, `List<>` 주입 순서는 빈 정의 순서에 의존해
안정적이지 않다.

#### 격리 — 전역 VFS 는 다중 에이전트에서 성립하지 않는다

`VirtualFileSystem` 빈 **하나**를 workspace root 에 거는 배선은 에이전트가 하나일 때만 성립한다.
에이전트가 여럿이면 그것은 "에이전트 A 가 B 의 작업 파일을 읽을 수 있다" 는 뜻이 되고, 테넌트가 여럿이면
훨씬 나쁜 뜻이 된다. 따라서 팩토리로 바꾼다.

| 자원 | 단일 에이전트 배선 | 다중 에이전트 설계 | 키 |
|------|------------------------|-------------------|-----|
| `VirtualFileSystem` | 전역 빈 1개 | `VirtualFileSystemFactory#create(AgentRuntimeId)` | 에이전트 **×** 테넌트 |
| `CredentialStore` | 전역 빈 1개 | `CredentialStoreFactory#create(String discriminator)` | 테넌트만 |
| `KnowledgeStore` | 전역 빈 1개 | 전역 유지 + 조회 시 테넌트 필터 | — |

`VirtualFileSystem` 을 `AgentRuntimeId` 전체로 키잉하는 것이 중요하다. 내부 운영 앱은 `agentId` 로만
키잉하는데(`virtualFileSystemFactory.create(agentId)`), 그 앱은 테넌트 분리를 컨텍스트 재생성으로
얻고 있어서 성립한다. discriminator 모델에서는 같은 에이전트의 두 테넌트가 같은 런타임 트리를 공유하게
되므로, 파일 경로가 반드시 두 축을 모두 포함해야 한다. 기본 레이아웃은
`<workspace-root>/<agentRef>/<discriminator>/` 이고, discriminator 가 없으면 `_default` 를 쓴다.

디폴트 팩토리는 스타터가 제공하고(로컬 파일시스템 서브트리), `@ConditionalOnMissingBean` 이므로 호스트가
GridFS/S3 팩토리로 갈아끼울 수 있다.

#### 자격증명 승격 — `__capability.*`

내부 운영 앱은 에이전트 프로퍼티에 시스템이 부여한 능력 키를 섞어 넣되, **사용자 프로퍼티를 먼저 넣고
시스템 키를 나중에 덮어쓴다**. 사용자가 `__capability.runbook-execution=true` 를 스스로 써넣어 권한을
위조하지 못하게 하려는 것이다. 이 병합 순서는 보안 속성이므로 스타터가 규약으로 고정한다 —
`AgentDescriptor.properties()` 는 **병합 후** 맵이고, `__capability.` 로 시작하는 키는 호스트 설정이나
DB 가 아니라 스타터가 주입한 `CapabilityResolver` 만 쓸 수 있다. 다른 출처의 같은 접두어 키는 바인딩
시점에 **거부**한다(조용히 무시하지 않는다 — 무시는 위조 시도를 숨긴다). §7 에 항목으로 올린다.

#### 예산 해석 — 에이전트 × 채널

내부 운영 앱의 `AgentBudgetProperties` 는 예산을 **에이전트가 아니라 채널**로 키잉한다
(`web`/`ws`/`slack`/`inquiry`/`insight`). 같은 에이전트라도 Slack 스레드에서는 길게, 인라인 조회에서는
짧게 돌아야 하기 때문이다. 그런데 에이전트별 상한도 필요하다 — 무거운 운영 에이전트와 가벼운 조회
에이전트는 애초에 급이 다르다. 둘은 **직교**하므로 둘 다 둔다. 해석 순서는 좁은 것부터:

```
submit() 인자의 LiveSessionOptions.budget       ← 호출자가 명시한 것. 항상 이긴다
  ▸ aimon.agents.<name>.budget                  ← 에이전트별
    ▸ aimon.budget.channels.<channel>           ← 채널별
      ▸ aimon.budget.*                          ← 전역 기저값
```

필드 단위로 병합한다(에이전트가 `max-iterations` 만 지정했으면 나머지 세 필드는 채널/전역에서 채운다).
채널 이름은 열거형이 아니라 **자유 문자열**이다 — `AgentChannel` 을 코어 enum 으로 굳히면 호스트가
채널을 추가할 때마다 코어를 고쳐야 한다. 대신 알 수 없는 채널 이름은 전역 기저값으로 조용히 떨어지므로,
`aimon.budget.channels` 에 없는 채널로 들어온 호출은 DEBUG 로그를 남긴다.

#### 에이전트 정의의 런타임 변경 — D13 이 금지하는 것이 아니다

§6 D13 은 **Spring 프로퍼티**의 런타임 리프레시를 지원하지 않는다고 못박았다. 그것을 "에이전트 설정은
재기동해야 바뀐다" 로 읽으면 안 된다. 내부 운영 앱에서 에이전트·스킬·서브에이전트는 REST 로 생성되고
수정되는 도메인 엔티티이고, 변경은 도메인 이벤트 → `AgentContextInvalidator` 로 전파된다. 이것은
프로퍼티 리프레시와 **다른 축**이다.

| | 대상 | 트리거 | 지원 |
|---|------|--------|------|
| D13 | `aimon.*` Spring 프로퍼티 (스토어 종류, 풀 크기, LLM 키…) | `/actuator/refresh` | **미지원** — WARN + `stale-config` |
| 이 절 | 에이전트 정의 (프롬프트, 스킬, 도구, 프로퍼티) | 호스트의 도메인 이벤트 | **지원** — `AimonAgents.invalidate(...)` |

```java
public interface AimonAgents {
    /** 설정 + 발견된 번들에서 해석된 에이전트 목록. */
    List<AgentDescriptor> list();

    /** 해당 런타임을 폐기 대상으로 표시한다. 진행 중인 턴은 끝까지 돌고, 다음 사용부터 새로 만든다. */
    void invalidate(String agentRef, String discriminator);

    /** agentRef 의 모든 테넌트. */
    void invalidate(String agentRef);
}
```

IMPORTANT: `invalidate` 는 **즉시 `close()` 하지 않는다.** 레지스트리에서 등록만 해제해 새 요청이
그것을 잡지 못하게 하고, 사용 카운트가 0이 되는 시점에 닫는다(위 축출과 같은 메커니즘). 즉시 닫으면
진행 중인 턴이 도구 레지스트리를 잃는다 — scope-model 이 금지하는 바로 그 사고다.

#### 스케줄링과의 결선

`ScheduledTask.boundRuntimeId` 는 agent-scoped id 를 참조하므로, cron 이 재발화하는 시점에
`(agentRef, discriminator)` 런타임이 **축출되어 있을 수 있다**. 지연 생성 모델에서는 이것이 문제가 되지
않는다 — 레지스트리 조회가 미스이면 그 자리에서 다시 만든다. 이 성질 때문에 축출이 안전하고, 반대로
축출이 있어야 테넌트가 무한한 환경에서 스케줄링이 성립한다. 단, `invalidate` 로 폐기된 뒤 재생성된
런타임은 **새 정의**를 쓰므로, 예약 당시와 다른 프롬프트로 돌 수 있다. 이것은 버그가 아니라 의도이며
(정의를 고쳤으니 다음 실행부터 반영되는 것이 맞다) §9 에 사용자 가시성 문제로 남긴다.

내부 운영 앱은 `SchedulingEngine` → `AgentExecutionContextRegistry` → 컨텍스트 프로바이더 →
`SchedulingEngine` 의 빈 순환을 익명 lazy 어댑터로 끊고 있다. 스타터는 §6 D10 대로 조립을
`AimonStack`(POJO) 안에서 하므로 이 순환이 애초에 생기지 않는다 — 그 어댑터는 스타터 도입 시
삭제 대상이다.

---

## 5. 실행 흐름

### 5.1 컨텍스트 기동

```
Spring context refresh
  ├ 슬라이스 2~8: 재료 빈 생성 (LlmClient, VFS 팩토리, SessionRecordStore, 정책, 커스터마이저 수집 …)
  │    ※ 이 시점에 디스크 쓰기·파일 스캔·스레드 기동·MCP 접속 없음
  ├ AimonProperties 바인딩 + @Validated 검증        ← 프로퍼티 오류는 여기서 읽을 수 있는 메시지로
  ├ AimonStackSpec 조립 (번역만)
  ├ AimonStack 생성 (AimonStackBuilder)             ← 인스턴스 공유·양방향 결선·소멸 등록
  └ SmartLifecycle
       ├ [phase MAX-4096] AimonRuntimeLifecycle.start()
       │    ├ workspaceRoot 검증 (+ ensure-writable)
       │    ├ 번들 스킬 materialize (디스크 쓰기)
       │    ├ 에이전트 번들 로드(N개) → AgentRegistry 등록
       │    │    ├ aimon.agents.* 선언분 + discover-bundles 자동 발견분 병합
       │    │    └ 각 에이전트의 정의·번들·커스터마이저를 해석해 AgentDescriptor 확정
       │    ├ MCP 접속 (타임아웃 적용)     ← 아래 ①에 딸려 온다 (런타임이 McpClientManager 소유)
       │    ├ ① agent:<name> 런타임 N개 생성·등록  ← discriminator 없는 것만. eager (§4.11)
       │    └ PendingTurnReaper.start()
       ├ [phase MAX-2048] 웹 서버 소켓 오픈 (WebServerStartStopLifecycle)
       ├ [phase MAX-1024] WebServerGracefulShutdownLifecycle (기동 시엔 하는 일 없음)
       └ [phase MAX]      AimonSchedulingLifecycle.start()
            ├ ② SchedulingEngine.start()
            └ ③ ScheduledTaskManager.register(...)
  ↓
  HealthReport 산출 → fail-fast 판정
```

### 5.2 요청 1건 = 턴 1건

```
POST /chat
  └ AimonSessions.submit(sessionId, input)
       └ SessionRouter.submit(...)
            ├ 리스 확인 → 이 노드가 홀더면 로컬 실행, 아니면 홀더 노드로 포워딩
            ├ LiveSessionCache 조회 (miss 면 LiveSessionOpener 호출)
            │    └ 7-인자 DefaultLiveSession (queue + hooks + records 전부 주입)
            │         → 생성자에서 OnSessionStart 발화
            ├ offerAsync → SubmitOutcome (EXECUTED | QUEUED)
            ├ OrcaAgentExecutor.execute (ReAct 루프)
            └ 턴 종료 → LATER 티어 드레인 → SessionTotals/budgetOverride 플러시
```

`OrcaAgentRuntime` 은 **`LiveSessionOpener` 안에서 만들어지지 않는다.** discriminator 없는 런타임은
`AimonRuntimeLifecycle.start()` 에서 등록된 것을 재사용하고, 테넌트별 런타임은 라우터 앞단의
`AgentRuntimeResolver` 가 레지스트리 미스일 때 **단일 진입점에서** 만든다(§4.11). 오프너가 런타임을
만들면 세션마다 런타임이 생겨 0.1.x 의 실수를 재현하게 되므로 금지한다.

### 5.3 종료

```
SIGTERM
  ├ [phase MAX]      AimonSchedulingLifecycle.stop()   ← 루틴 발화 중단 (필요시 잡 완료 대기)
  ├ [phase MAX-1024] 웹 서버 graceful shutdown         ← 신규 요청 거절, 진행 중 요청 드레인
  ├ [phase MAX-2048] 웹 서버 stop                      ← 커넥터 닫기
  ├ [phase MAX-4096] AimonRuntimeLifecycle.stop()      ← no-op
  └ AimonStack.close(drainTimeout)   ← 유일한 소멸 간선. §2.4 의 14단계 + 치환 1 + 추가 3
       ※ 여기는 destroyBeans() 이므로 Spring 의 어떤 종료 타임아웃도 미치지 않는다
       memoryFinalDerivation.run()      (파생 태스크 enqueue)
       → memoryQueue.stop()             (최대 30초 블록 — LLM 파생이 여기서 완료된다)
       → dreamer → maintenance
       → sessionRouter.closeGracefully(drainTimeout)   ← close() 가 아니다 (§2.1)
       → sessionCheckpoints → agentRuntimes (등록된 전부 — eager N개 + 살아 있는 테넌트 런타임)
       → graalJsEngines
       → registry.unregister → schedulingEngine → rewakeService
       → pendingTurnReaper → hookHotReload → skillHookShell
       → signalBus / inbox (분산 모드)
       → 런타임별 VirtualFileSystem, AutoCloseable 도구  ← 런타임 수만큼 (§4.11)
  └ 빌린 빈(DataSource, Scheduler, MongoDatabase)은 건드리지 않는다
```

**이것은 §2.4 의 총순서와 같지 않다.** 정확히 한 곳이 치환되고 세 개가 덧붙는다.

- **치환**: `liveSession` 자리에 `sessionRouter.closeGracefully(drainTimeout)` 이 온다. 스타터는
  개별 핸들이 아니라 라우터를 소유하며(§6 D3), 라우터가 캐시된 핸들 전체를 닫는다.
- **추가**: `signalBus` / `inbox`(분산 모드), `VirtualFileSystem`, `AutoCloseable 도구` 셋은 CLI 조립체에
  없던 스타터 소유분이다 — `OrcaAgentRuntime.close()` 가 닫아 주지 않기 때문이다(§2.1).
  다중 에이전트에서는 이 셋이 **런타임 하나가 아니라 살아 있는 런타임 전부**에 대해 반복된다.
  런타임이 축출·무효화로 먼저 사라졌다면 그때 이미 닫혔으므로 여기서는 남은 것만 닫는다(§4.11).

**`spring.lifecycle.timeout-per-shutdown-phase` 는 위 그림의 어느 줄도 제한하지 않는다.** 마지막 줄
(`AimonStack.close`)은 `destroyBeans()` 단계라 애초에 라이프사이클 프로세서의 관할 밖이고, 그 위 세 줄은
모두 동기 `stop()` 이라 프로세서가 기다리는 래치가 이미 0이다. 실제로 이 종료를 짧게 만드는 손잡이는
`aimon.session.shutdown-drain-timeout` 과 memory queue 의 30초다.

### 5.4 실패 흐름

| 상황 | 동작 |
|------|------|
| `aimon.workspace.root` 미설정 | 바인딩 실패 — 기동 중단, 프로퍼티 이름이 메시지에 나온다 |
| LLM api-key 미설정 | 기동 중단 (`fail-fast=true`) |
| 기대한 에이전트 번들 없음 | `AgentDefinitionNotFoundException` 을 잡아 "번들 `X` 를 찾지 못했다. 클래스패스의 `agents/X/agent.md` 를 확인하라" 로 재작성 후 기동 중단 |
| 스킬 열거가 0건 | `degradations` 에 기록 → `fail-fast` 면 기동 중단, 아니면 WARN + health DOWN |
| MCP 서버 무응답 | `aimon.mcp.connect-timeout` 초과 시 해당 서버만 degraded 처리 (refresh 를 블록하지 않는다) |
| 분산 모드인데 스키마 미적용 | 명확한 메시지로 기동 중단 (raw SQL 예외 노출 금지) |
| 턴 중 예산 초과 | 코어 동작 그대로 — `AgentExecutionResult` 의 완료 사유로 반환 |
| 스킬 승인 ASK, 채널 없음 | 모드에 따라 DENY 확정 또는 SUSPENDED |
| `aimon.enabled=false` + `AimonSessions` 호출 | 기동은 정상. 호출 시 `AimonDisabledException` — 프로퍼티 이름이 메시지에 나온다 (§4.2) |
| `aimon.enabled=false` + 내부 빈 직접 주입 | 기동 실패. 문서화된 주입점은 `AimonSessions` 하나뿐이므로 **의도된 동작** (§4.2) |
| 선택자 프로퍼티 오타 (`store: postgress`) | enum 바인딩이 기동 시점에 거부 — 백오프로 흘러 in-memory 가 되지 않는다 (§4.3) |
| 런타임에 `aimon.*` 프로퍼티 변경 (`/actuator/refresh`) | 반영되지 않음. 변경 키를 열거한 WARN + health `stale-config` degradation (§6 D13) |
| 알 수 없는 `agentRef` 로 `submit` | `IllegalArgumentException` — 알려진 `agentRef` 목록을 메시지에 넣는다. 기본 에이전트로 조용히 폴백하지 않는다 (§4.11) |
| 세션의 바인딩과 다른 `agentRef` 로 `submit` | `SessionStore.claim` 이 거부 — 새 `SessionId` 를 쓰라는 메시지. 이력은 그대로 (§4.6) |
| `agentRef`/discriminator 에 `..` `/` `:` | 바인딩·`submit` 시점 거부. 워크스페이스 경로 세그먼트가 되기 때문 (§7) |
| 테넌트 런타임 생성 중 MCP 실패 | 그 런타임만 degraded 로 등록되고 턴은 해당 도구 없이 진행. 기동은 이미 끝났으므로 `fail-fast` 대상 아님 (§4.11) |
| `max-entries` 도달 + 전부 사용 중 | `AgentRuntimeExhaustedException` + `RuntimeDegradations`. 상한을 조용히 넘기지 않는다 (§4.11) |
| 사용자 프로퍼티에 `__capability.*` | 거부. 조용히 무시하면 위조 시도가 로그에도 남지 않는다 (§7) |

---

## 6. 설계 결정 사항

### D1. 3계층 — `aimon-bootstrap` 을 먼저 만든다

**결정**: 프레임워크 중립 조립 모듈을 스타터보다 먼저 만들고, `aimon-cli` 도 그 위로 이관한다.
**기각한 대안**: 자동설정 클래스가 직접 조립하는 2계층.
**근거**: §3.2. 결정적인 것은 종료 순서 — Spring 의 역-의존 순서로 표현할 수 없는 제약이 실재한다.
그리고 CLI 와 스타터가 같은 조립을 두 벌 갖는 순간 두 벌은 갈라진다.

### D1b. 스타터는 **하나의 아티팩트**로 시작한다 (autoconfigure 분리 없음)

**결정**: `aimon-spring-boot-starter` 안에 자동설정을 포함한다.
**기각한 대안**: `aimon-spring-boot`(자동설정) + `aimon-spring-boot-starter`(의존성 집합) 2분할.
**근거**: Boot 문서가 제시하는 분할 기준은 "여러 flavor / 선택 기능"이지만, 가장 가까운 동종 사례인
LangChain4j 는 ~18개 통합 **전부**를 자동설정 포함 단일 스타터로 낸다. Spring AI 가 실제로 분할한
이유는 아키텍처가 아니라 **전이 의존성 충돌 격리**였다("Protocol Buffers, gRPC 등의 버전 충돌 영향을
최소화하기 위해"). AIMON 은 아직 그 문제가 없다.
**분할 트리거(미래)**: 어느 프로바이더의 전이 의존성(AWS SDK, Mongo 드라이버, Playwright, k8s 클라이언트,
GraalJS)이 **모든 사용자의 클래스패스에 얹히게 되면** 그때 분할한다. 스타터 아티팩트 ID 는 바뀌지
않으므로 사용자 파괴 없이 나중에 할 수 있다.
**분할 시 명칭**: 자동설정 모듈은 `aimon-spring-boot` 다 — `aimon-spring-boot-autoconfigure` 는 Boot 2.7
철자이며 현행 문서는 `-autoconfigure` 접미사를 뗐다. 대부분의 블로그가 옛 철자를 가르치므로 리뷰어가
"틀렸다"고 지적할 수 있다. 근거를 함께 남긴다.

### D2. 명칭과 프로퍼티 스킴

**결정 (a)**: 아티팩트는 `aimon-spring-boot-starter`. Boot 는 groupId 가 달라도 `spring-boot` 로
**시작하는** 모듈명을 금지하므로 `spring-boot-starter-aimon` 은 불가다.
**결정 (b)**: 프로퍼티는 `aimon.<subsystem>.<component>.<setting>`, 클래스마다 `PREFIX` 상수.
**결정 (c)**: **선택자 프로퍼티를 쓰고 프로바이더별 불리언을 만들지 않는다.**
`aimon.llm.provider=anthropic|openai|none`, `aimon.session.store=…`, `aimon.scheduling.backend=…`.
**근거**: Spring AI 가 `spring.ai.openai.chat.enabled` 같은 불리언을 먼저 냈다가 "여러 모델을 설정할 수
있게 하려고" `spring.ai.model.chat` 선택자로 **교체하며 기존 플래그를 제거**했다. 불리언 N개는
"이 중 정확히 하나"를 표현하지 못하고 마이그레이션이 아프다. 그 종착지를 처음부터 채택한다.

### D3. 단일 노드에서도 `SessionRouter` 를 쓴다. `LiveSession` 은 빈이 아니다

**결정**: 세션 진입점은 항상 `SessionRouter`. 호스트에 노출하는 것은 `AimonSessions` 파사드뿐.
**기각한 대안 (a)**: `LiveSessionFactory` 를 빈으로 노출 → 큐/훅이 `null` 로 조용히 빠진다.
**기각한 대안 (b)**: `LiveSession` 을 request/session 스코프 빈으로 → 생성자가 `OnSessionStart` 훅을
발화하고, Spring 의 추론 destroy 가 `close()` 를 부른다.
**근거**: `LiveSession` 은 thread-safe 하지 않고, 큐 없이 busy 상태에서 `offerAsync` 하면 **거절이 아니라
동시 실행**으로 폴백한다. 두 HTTP 요청이 한 세션에 겹치면 턴이 중첩된다. 라우터가 이 구조적 위험을 막는다.

### D4. Spring 이 닫는 것은 `AimonStack` 하나

**결정**: `AimonStack` 만 `destroyMethod="close"`. 나머지 노출 빈은 **예외 없이** `destroyMethod=""`.
소멸 간선은 정확히 하나여야 하며, 스택이 이미 닫는 것을 Spring 도 닫게 두면 순서가 미보장인 이중 소멸이
된다. `RewakeService` 도 예외가 아니다(§4.4).
**기각한 대안**: 빈마다 올바른 destroyMethod 를 지정 + `@DependsOn` 으로 순서 인코딩.
**근거**: §2.1. 판정 기준이 `AutoCloseable` 이 아니라 "무인자 `close()`/`shutdown()`"이라 실수하기
쉽고(`RoutineExecutor`, `*TaskScheduler` 는 `AutoCloseable` 이 아니면서 닫힌다), `AgentScoped` 마커는
fan-out 이 없어 근거가 못 되며, 순서 제약 중 일부는 의존 간선이 없다. 소멸 간선을 하나로 줄이면
Spring 의 순서 결정이 아예 관여하지 않게 된다.

### D5. 스타터는 `api(project(":aimon-core"))` 를 쓴다

**결정**: `.claude/rules/code-style.md` 의 `implementation` 규칙에 **파사드 모듈 예외**를 명문화한다.
**근거**: §2.6. `implementation` 은 POM 에서 runtime scope 가 되어 소비자 컴파일이 불가능해진다.
규칙의 의도(구현 모듈이 코어 타입을 전이 노출하지 않게)는 유지하되, 스타터는 정의상 **노출이 목적**이다.
선례: `aimon-sandbox-docker` 의 `api(project(":aimon-sandbox"))`.

### D6. Spring Boot 베이스라인은 **3.5.x**

**결정**: `spring-boot-autoconfigure` **3.5.x**(작성 시점 최신 3.5.16)에 대해 컴파일한다. 지원 창은
"이 애노테이션 표면에 한해 Boot 3.5.x ~ 4.1.x". Boot 4 전용 아티팩트는 **필요해질 때 별도로** 낸다 —
하나의 아티팩트가 두 메이저를 지원하지 않는다.
**기각한 대안 (a)**: 저장소가 현재 카탈로그에 고정한 3.4.1 — OSS 지원이 끝났다.
**기각한 대안 (b)**: Boot 4.x 베이스라인 — 아직 채택률이 낮고, junit 6 로의 테스트 클래스패스 점프를
21개 모듈 전체에 강요한다.
**근거**:
- **3.5 는 Boot 3 계열의 마지막 라인이고 상용 지원이 2032-06-30 까지**로 유난히 길다(4.0 은 2027-12-31,
  4.1 은 2028-07-31). 라이브러리 저자에게 중요한 것은 OSS EOL 이 아니라 "소비자가 실제로 몇 년 그 위에
  앉아 있는가"다.
- 호환 창은 추론이 아니라 확인된 것이다 — `@AutoConfiguration` 의 멤버 집합이 3.4.1 과 4.1.0 사이에
  동일하고(javap 비교), `@ConditionalOn*` 은 여전히 `org.springframework.boot.autoconfigure.condition` 에,
  `@ConfigurationProperties` 는 여전히 `org.springframework.boot.context.properties` 에 있으며,
  `…AutoConfiguration.imports` 파일도 4.1.0 에 존재한다.
- Boot 팀이 "모듈화 리팩터링 때문에 한 아티팩트로 Boot 3 과 4 를 동시에 지원하는 것"을 강하게
  비권장한다. LangChain4j 의 우회책이 `-spring-boot-starter` / `-spring-boot4-starter` **쌍둥이 36개**다.

**부수 결정**: 스타터는 **자신의 `ObjectMapper` 를 직접 만든다.** 앱의 `ObjectMapper` 빈을 주입받지
않는다 — Boot 4 에서 그 빈은 Jackson 3(`tools.jackson`) 타입일 수 있고, 그때
`@ConditionalOnBean(ObjectMapper.class)` 는 **조건에 따라** 매칭되기도 안 되기도 한다
(`spring-boot-jackson2` 가 클래스패스에 남아 있으면 Jackson 2 `ObjectMapper` 빈이 여전히 존재한다).
결과가 확정적이지 않다는 점이 오히려 빌리지 않을 이유다.

### D7. 서버 기본값은 CLI 기본값과 다르다

| 항목 | CLI 기본 | **서버 기본** | 이유 |
|------|---------|--------------|------|
| `ExecutionBudget` | unlimited | **유한** (iterations 20 / wall-clock 120s) | 무한 루프 = 무한 비용 |
| agent `maxIterations` | `Integer.MAX_VALUE` | **20** | 위와 동일 |
| `Bash` 도구 | 기본 프로바이더에 포함 | **off** | 서버 프로세스 권한으로 임의 명령 실행 |
| 스킬 승인 | ASK (터미널에서 물음) | **DENY** | 무인 환경에서 ASK 는 물을 채널이 없다 |
| declarative shell 훅 | on | **off** | 프로세스 env 전체 노출 |
| 훅 hot reload | on | **off** | `user.home` 감시 |
| 작업 디렉토리 | jar 위치/`user.dir` 파생 | **필수 프로퍼티** | 컨테이너에서 파생값은 항상 틀리다 |
| 스케줄링 | on | **off** (`backend=none`) | Quartz 는 트리에 둘뿐인 비-데몬 스레드 원천 중 하나 (§2.4). 필요할 때만 켠다 |

### D8. in-memory 기본은 유지하되 **시끄럽게**

**결정**: 저장소 기본값은 in-memory 로 두어 "설정 없이 뜬다"를 지키되, `HealthReport.sessionStoreDurable=false`
를 노출하고 기동 로그에 명시적 WARN 을 남긴다.
**근거**: in-memory 구현들이 장수명 서버에서 무제한 증가한다 — `InMemorySessionRecordStore` 는 TTL/축출이
없고, `InMemoryMessageQueueRepository` 는 TTL 도 크기 제한도 없다고 자체 문서화되어 있으며,
`InMemoryPendingTurnRegistry` 는 무제한 `ConcurrentHashMap` 이다. CLI 에서는 드러나지 않는 누수다.

### D9. Quartz 는 Spring 의 `Scheduler` 를 빌려 쓰고, instanceName 은 컨텍스트별로 다르다

**결정**: `aimon.scheduling.quartz.use-application-scheduler=true`(기본)면 호스트의 `org.quartz.Scheduler`
빈을 공개 생성자 `QuartzTaskScheduler(Scheduler, ScheduledTaskExecutor)` 로 주입한다. 자체 스케줄러를
만들 때는 `instanceName` 을 **프로퍼티에서 받고, 미지정 시 컨텍스트별 고유값을 파생**한다.
**기각한 대안**: `QuartzTaskSchedulerBuilder` 를 기본값 그대로 사용 → 자체 `StdSchedulerFactory` 로
**두 번째** 스케줄러/스레드풀/커넥션풀이 생기고 `spring.quartz.*` 를 무시한다. 이것이 이 결정의 유일한
근거다. instanceName 은 근거가 아니다 — 빌더와 `createDefaultScheduler()` 양쪽 모두 기본값이 JVM 안에서
유일한 파생 이름이므로, 스타터가 이름을 넘기지 않아도 컨텍스트끼리 겹치지 않는다(§2.4).

**빌린 자원에서 조심할 것은 셋이고, 셋 다 어댑터가 막는다.** 소유 여부는
`QuartzTaskScheduler.owning(...)` / `borrowing(...)` 이 가른다.

| 위험 | 빌린 스케줄러에서 벌어지는 일 |
|------|------------------------------|
| `shutdown()` | `scheduler.shutdown(true)` 가 앱 전체 Quartz 를 멈추고, 잡 완료를 기다리느라 종료가 길어진다 |
| `clear()` | 호스트가 등록한 잡까지 지운다 |
| executor 슬롯 덮어쓰기 | 스택이 둘이면 첫 스택의 task 가 둘째 스택의 에이전트로 돈다 |

**프로퍼티**: `aimon.scheduling.quartz.wait-for-jobs-on-shutdown`(`SchedulerFactoryBean` 의 동명 플래그와
같은 의미)과 데몬 스레드 옵션을 노출한다. 최상위가 아니라 `quartz.*` 아래인 이유는, 최상위에 두면
백엔드 3개 중 2개에서 아무 일도 하지 않는 손잡이가 되기 때문이다. `daemonThreads` 는 어댑터 쪽에 이미
있어 스타터가 값만 넘긴다.

### D10. `AgentRuntimeRegistry` 를 인프라 빈으로 — Kafka 패턴

**결정**: `AgentRuntimeRegistry` 를 `SmartLifecycle` 인프라 빈 하나가 관리하게 하고, **개별
`AgentRuntime` 은 빈으로 등록하지 않는다.** Spring Kafka 의 `KafkaListenerEndpointRegistry` 가 리스너
컨테이너를 "컨텍스트의 빈이 아니라 인프라 빈이 관리하는 대상"으로 두는 것과 같은 형태다.
**근거**: 런타임을 빈으로 만들면 Spring 에게 그 `close()` 를 넘기게 되고 agent-scoped 소유권이 깨진다.
Kafka 는 여기에 더해 "레지스트리가 만든 것"과 "사용자가 빈으로 선언한 것"을 두 조회 메서드로 나눠 두는데,
AIMON 도 같은 구분이 필요하다 — 현재 `AgentRuntimeRegistry` 에는 **열거 메서드 자체가 없다**(§9).

### D11. 모노레포 유지 + BOM 하나

**결정**: 스타터를 별도 저장소로 빼지 않고 현 모노레포에 둔다. 대신 `java-platform` 기반 `aimon-bom`
모듈을 **하나만** 만들어 서브프로젝트 목록에서 constraints 를 생성하고, 기존 릴리스 절차에서 lockstep 으로
낸다(vanniktech 플러그인의 `configure(JavaPlatform())`).
**근거**: LangChain4j 는 Spring 통합을 별도 저장소로 두어 독립 릴리스를 얻었지만, 그 대가로
`langchain4j-spring-bom` 이 `1.0.0-beta5` 에 멈춘 채 스타터들만 앞서 나갔다. **낡은 BOM 은 없는 BOM 보다
나쁘다.** AIMON 은 단일 릴리스 프로세스가 이미 있으므로 모노레포 + 단일 BOM 이 맞다.
**부작용(수용)**: 버전이 루트 `VERSION_NAME` 하나에 묶여 있어 **스타터만 독립 릴리스할 수 없다.**
스타터 버그픽스가 전체 릴리스를 부르고, 코어 릴리스는 스타터를 항상 재퍼블리시한다.

### D12. 자동설정하지 않는 것

의도적으로 스타터가 만들지 않고 호스트에 맡기는 것.

- **HTTP 엔드포인트** — 컨트롤러/SSE/WebSocket 을 제공하지 않는다. 인증·라우팅·직렬화는 앱의 몫이다.
- **`SessionId` 결정** — (사용자, 스레드) → `SessionId` 매핑은 앱의 도메인이다.
- **`DataSource` / `MongoClient` / `RedisClient`** — Spring Boot 의 기존 자동설정을 쓴다.
- **스키마 마이그레이션** — 기본은 검증만. Flyway 구동은 명시적 opt-in.
- **`CredentialStore`** — main 소스에 구현이 없다. 스타터는 `CredentialStoreFactory` 인터페이스만 노출하고
  구현은 앱이 제공한다(§9). 테넌트별 분기가 필요한 것도 이 자리다(§4.11).
- **에이전트 정의의 영속화** — 에이전트를 DB 로 관리할지, 누가 만들 수 있는지, 변경 이벤트를 어떻게 나를지는
  앱의 도메인이다. 스타터는 `AimonAgents.invalidate` 라는 **수신구**만 제공한다(§4.11).

`CredentialStore` 는 넷 중 유일하게 **코드가 아니라 데이터**인 확장점이라 여기서 빠진다. 구현은 코어
main 소스에 있고(`at.aimon.core.credential.InMemoryCredentialStore`), 스타터는 그것을 세우는 프로퍼티 트리
`aimon.credentials.<프로필>.<필드>` 를 노출한다. 그래도 빈·팩토리·프로퍼티는 **셋 중 하나만** 이며 둘
이상이면 기동을 거부한다. 사용법은
[`docs/getting-started/embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md)
§4 · §10 에 있다.

### D13. 런타임 프로퍼티 리프레시를 지원하지 않는다 — 조용히가 아니라 시끄럽게

**결정**: `aimon.*` 프로퍼티는 **조립 시점에 한 번** 읽힌다. `spring-cloud-context` 의 `/actuator/refresh`
나 Config Server 갱신으로 값이 바뀌어도 반영되지 않으며, 그 사실을 **로그와 health 로 드러낸다.**

이 항목이 필요한 이유는 아무것도 하지 않았을 때의 동작이 최악이기 때문이다. `AimonStack` 은 불변이고
자동설정 빈은 싱글턴이므로, 리프레시는 예외 없이 **성공한 것처럼 끝나고** 에이전트는 옛 설정으로 계속
돈다. 운영자는 `max-tokens` 를 낮췄다고 믿고 청구서는 그대로다 — §1.4 의 "침묵하는 기본값 금지" 가
금지하는 실패 양상 그 자체다.

**기각한 대안 1 — `AimonStack` 에 `@RefreshScope`.** 프록시가 다음 호출에서 스택 전체를 재생성한다.
진행 중인 턴, 캐시된 `LiveSession`, 등록된 `AgentRuntime`, Quartz 스케줄러가 통째로 교체되고, 옛 스택의
소멸 시점은 프록시가 정한다 — D4 의 "소멸 간선은 하나" 가 무너진다.

**기각한 대안 2 — 일부 키만 리프레시(예산·타임아웃 등).** 어느 키가 반영되고 어느 키가 안 되는지가
프로퍼티 트리에 드러나지 않아, 결국 같은 침묵을 키 단위로 쪼갤 뿐이다.

**시끄럽게 만드는 방법**(아직 코드가 없다 — §9.1): `@ConditionalOnClass(EnvironmentChangeEvent.class)` 로 게이트된 리스너 하나를
둔다. 변경된 키에 `aimon.` 접두어가 있으면

1. 키 이름을 열거한 WARN 로그 — "재기동해야 반영된다" 를 문장에 포함,
2. `RuntimeDegradations` 에 `stale-config` 등록(§4.10) — 조립 시점 `HealthReport` 는 불변이므로 이 런타임
   채널을 거치며, indicator 가 둘을 합쳐 보고한다. `fail-fast` 는 걸리지 않는다(이미 기동한 뒤다).

클래스패스에 `spring-cloud-context` 가 없으면 리스너 자체가 존재하지 않으므로 비용은 0이다.

**적용 범위 밖**: 세션 단위 `budgetOverride`(`/budget` 에 해당하는 런타임 변경), `ScheduledTaskManager`
를 통한 스케줄 변경, 그리고 **에이전트 정의의 런타임 변경**(`AimonAgents.invalidate` — §4.11)은
**코어/스타터의 정상 기능**이며 이 결정과 무관하다. D13 이 말하는 것은 Spring `Environment` 의 프로퍼티
값이지 실행 중 API 호출이 아니다. 이 구분은 중요하다 — 내부 운영 앱처럼 에이전트를 DB 에서 관리하는
앱에게 D13 을 "에이전트를 바꾸려면 재기동" 으로 읽히게 두면 스타터를 못 쓴다고 결론내리게 된다.

### D14. 에이전트는 목록이 아니라 map 이고, 축은 둘이다

**결정**: `aimon.agents.<name>.*` map + `discriminator` 축. 에이전트 축은 eager, 테넌트 축은 lazy(§4.11).
**근거**: `aimon.agent.bundles: [default]` 라는 리스트 형태는 에이전트별로 값을 매달 자리가 없어서,
도구·예산·모델을 에이전트마다 다르게 주려면 결국 다른 트리를 새로 만들게 된다. map 은 키가 곧 `agentRef`
이므로 프로퍼티·바인딩·메타데이터 힌트가 전부 한 축을 공유한다.
**기각한 대안 — 에이전트마다 컨텍스트를 새로 만들기(내부 운영 앱의 현재 방식).** 0.1.18 에서는
유효했지만 0.2.0 에서 `AgentRuntime` 은 agent-scoped 이고, 세션마다 만드는 것은 scope-model 이 금지한다.
**부작용(수용)**: 테넌트 수가 많으면 런타임 캐시가 커진다. `idle-ttl`/`max-entries` 로 상한을 두되,
축출 불가 상태에서는 조용히 넘기지 않고 거부한다(§4.11).

### D15. 자원 격리는 팩토리로 — 전역 `VirtualFileSystem` 빈을 없앤다

**결정**: `VirtualFileSystem` 단일 빈 대신 `VirtualFileSystemFactory#create(AgentRuntimeId)`,
`CredentialStore` 대신 `CredentialStoreFactory#create(discriminator)`.
**근거**: 다중 에이전트에서 전역 VFS 는 "에이전트 A 가 B 의 작업 파일을 읽는다" 는 뜻이고, 다중 테넌트에서는
테넌트 간 유출이다. 이것은 편의 문제가 아니라 보안 속성이므로 기본값이 안전한 쪽이어야 한다(§7).
**내부 운영 앱과의 차이**: 그 앱은 `create(agentId)` 로 **에이전트로만** 키잉한다. 그 앱은 테넌트 분리를
컨텍스트 재생성으로 얻고 있어 성립하지만, discriminator 모델에서는 같은 에이전트의 두 테넌트가 한 런타임을
공유하므로 **키가 `AgentRuntimeId` 전체**여야 한다. 여기를 그대로 베끼면 테넌트 격리가 사라진다.
**부작용(수용)**: 호스트가 `VirtualFileSystem` 빈 하나를 주던 기존 배선은 팩토리로 바꿔야 한다.
단일 에이전트 앱을 위해 "빈이 있으면 모든 에이전트가 그것을 공유" 하는 폴백은 **두지 않는다** —
격리 실패가 설정 실수 하나로 되살아나는 폴백이기 때문이다.

### D16. 확장은 `List<>` 로 수집되는 커스터마이저, 상속이나 복제가 아니다

**결정**: `AimonAgentCustomizer`(도구·커맨드·훅)를 `aimon-bootstrap` 의 프레임워크 중립 인터페이스로 두고
스타터가 `List<>` 로 수집한다. `supports(AgentDescriptor)` 로 에이전트를 고르고 `getOrder()` 로 순서를 정한다.
**근거**: 내부 운영 앱이 이미 이 형태(`AgentExecutionContextCustomizer` + `AgentExecutionHookRegistrar`)로
수렴했고, Spring 에서 가장 관용적인 확장 형태다. 그리고 이것이 있어야 **기저 프로바이더 목록을 한 번만**
만들 수 있다 — 그 앱이 같은 6개 목록을 세션 경로와 스케줄링 경로에 복제해 둔 것이 이 확장점 부재의 증상이다.
**기각한 대안 — `supports` 오버로드 누적.** 그 앱은 판단 재료가 늘 때마다 default 메서드 오버로드를 추가해
왔다 — `supports` 가 `(type, bundle)` / `(type, bundle, properties)` 2개이고, 같은 방식으로 늘어난
`getAdditionalToolProviders` / `getAdditionalCommandProviders` 까지 합치면 오버로드 쌍 3개·메서드 6개다.
신규 API 이므로 인자 하나(`AgentDescriptor`)로 시작하고, 재료가 늘면 descriptor 를 늘린다.
**부작용(수용)**: `getOrder()` 를 안 붙이면 훅 실행 순서가 빈 정의 순서에 좌우된다. 기본값 0 이므로
순서에 민감한 훅을 쓰는 앱은 명시해야 하고, 이 사실을 훅 등록 로그에 남긴다.

### D17. 예산은 에이전트 × 채널 2축, 채널 이름은 enum 이 아니다

**결정**: `LiveSessionOptions.budget` ▸ `aimon.agents.<name>.budget` ▸ `aimon.budget.channels.<ch>` ▸
`aimon.budget.*` 순으로 **필드 단위 병합**. 채널 이름은 자유 문자열.
**근거**: 내부 운영 앱은 예산을 채널로만 키잉하고(`AgentBudgetProperties`), 에이전트 하나에 전역 예산
하나라는 배선은 채널을 구분하지 못한다. 실제로는 둘 다 필요하고 서로 직교한다 — 무거운 운영 에이전트/
가벼운 조회 에이전트라는 구분과, Slack 스레드/인라인 조회라는 구분은 같은 축이 아니다.
**기각한 대안 — `AgentChannel` enum 을 코어에 둔다.** 그 앱은 enum(`WEB`/`WS`/`SLACK`/`INQUIRY`/`INSIGHT`)
이지만 그것은 그 앱의 채널 목록이다. 코어/스타터가 굳히면 호스트가 채널을 하나 더 만들 때마다 업스트림을
고쳐야 한다. §4.3 의 선택자들과 달리 이 값은 **열린 집합**이므로 문자열이 맞다(§6 D2 의 예외).
**부작용(수용)**: 오타 난 채널 이름은 바인딩에서 걸리지 않고 전역 기저값으로 떨어진다. 그래서 알 수 없는
채널은 DEBUG 로그를 남기고, 메타데이터 힌트에 `providers: [{"name":"any"}]` 를 붙여 IDE 가 오탐 경고를
내지 않게 한다(§4.3).

**이 4단 병합은 아직 구현되어 있지 않고, 출발점이 위 결정문과 다르다.** 지금의 예산 소스는 전역 기본값 ·
호출 시점 `LiveSessionOptions` · 그리고 **세션 레코드에 영속되는 `budgetOverride`** 셋이다. 셋째는 위 4단이
그리지 않은 **세션 축**이고, 세션을 열 때 하이드레이션되어 opener 기본값을 이기며
(`DefaultLiveSession`), 병합 방식도 필드 단위가 아니라 **객체 통째 교체**
(`options.withBudget(budget)`)다. 그러므로 이 결정을 여는 사람이 상대할 것은 빈 자리가 아니라 **이미
영속되어 돌고 있는 한 축과 그 축이 이미 골라 둔 반대 방향의 병합**이다 — 필드 단위로 바꾸면 저장된
오버라이드의 의미가 함께 바뀐다("이 세션의 예산은 이것" → "이 세션은 이 필드만 덮는다"). 열린 항목의
정본은 [`docs/backlog/spring-boot-starter-open-items.md`](../../backlog/spring-boot-starter-open-items.md)
이다.

---

## 7. 보안 / 안전성 고려사항

| 항목 | 위험 | 대응 |
|------|------|------|
| `Bash` 도구 기본 활성 | 서버 프로세스 권한으로 임의 명령 | 서버 기본 off (§6 D7). 켜려면 명시적 프로퍼티 |
| declarative shell 훅에 `System.getenv()` 전체 전달 | DB 비밀번호·클라우드 자격증명이 훅 스크립트에 노출 | 기본 off + `env-allow-list` 화이트리스트 |
| 스킬 승인 ASK 의 무인 처리 | 채널이 없으면 승인이 반영되지 않거나 무한 대기 | 기본 DENY. `channel` 모드는 동기 블록 계약을 문서화 |
| 도구 권한 | 메인 에이전트 경로가 `allowedTools = List.of()`(= 무제한)로 하드코딩 | 선언적 allowlist 는 코어 변경 없이 불가 — `PermissionRequestHook` 체인 위에 구현 (§9) |
| 승인 유출 | agent-scoped 승인은 TTL 이 없고 `/clear` 로도 안 지워진다 | 세션 릴리스/삭제 시 `SessionApprovalStore.invalidate` 호출을 파사드가 보장 |
| 멀티테넌시 | `OpenAttributes` 는 캐시 미스에서만 소비되고 인박스/멱등 캐시에 직렬화되지 않는다 | **인가 정보를 `OpenAttributes` 에 싣지 않는다**. 테넌트 분리는 `SessionId`/`discriminator` 로 |
| **에이전트 간 파일 유출** | 전역 `VirtualFileSystem` 빈 하나면 에이전트 A 가 B 의 작업 파일을, 테넌트 X 가 Y 의 것을 읽는다 | `VirtualFileSystemFactory#create(AgentRuntimeId)` 로 `<root>/<agentRef>/<discriminator>/` 서브트리 샌드박싱 (§6 D15). "빈 하나면 공유" 폴백을 **두지 않는다** |
| **경로 주입** | `agentRef`/discriminator 가 경로 세그먼트가 되므로 `../` 이 들어오면 샌드박스를 벗어난다 | 두 값 모두 `[A-Za-z0-9_-]+` 로 제한하고 **바인딩/`submit` 시점에** 거부. discriminator 는 `AgentRuntimeId` 구분자 `:` 도 금지 (§4.11) |
| **능력 위조** | 사용자가 편집 가능한 에이전트 프로퍼티에 `__capability.*` 를 써넣어 권한을 자칭 | 시스템 키를 **사용자 프로퍼티 이후에** 병합해 항상 이기게 하고, 다른 출처의 `__capability.` 접두어 키는 조용히 무시하지 않고 **거부**한다 (§4.11) |
| **테넌트 자격증명 오염** | `CredentialStore` 가 전역이면 한 테넌트의 자격증명이 다른 테넌트 에이전트의 도구에 실린다 | `CredentialStoreFactory#create(discriminator)` — 캐시 키에 discriminator 포함 (§6 D15) |
| 시크릿 로깅 | api-key 가 프로퍼티에 있다 | `AimonProperties` 의 `toString` 마스킹 + Actuator `/env` 새니타이즈 키 등록 |
| 읽기 전용 루트 FS | 번들 스킬 materialize 가 기동 시 디스크에 쓴다 | `workspace.root` 를 쓰기 가능한 볼륨으로 요구하고 기동 시 검증 |
| 종료 시 데이터 손실 | `SessionRouter.close()` 가 진행 중 턴을 하드 인터럽트하고 리스를 만료로 남긴다 (§2.1) | `closeGracefully(drainTimeout)` 강제 + 종료 phase 타임아웃 상향 (§4.5) |
| **YAML 역직렬화** | 스킬(`SkillContentParser`)·에이전트(`MarkdownAgentDefinitionParser`) frontmatter 를 무제한 생성자 `new Yaml()` 로 파싱한다. 신뢰할 수 없는 스킬 번들을 로드하는 서버에서 문제. `SubagentContentParser` 만 `SafeConstructor` 를 쓴다 | `SafeConstructor` 로 통일하는 것이 해소이며(§9), 그때까지는 스킬 소스를 신뢰 경계 안으로 제한하도록 문서화 |
| `BackendType.of` 무제한 인터닝 | `BackendType.CACHE` 는 `computeIfAbsent` 로만 채워지는 static final 맵이라 임의 문자열이 들어오면 무한 증가 | 백엔드 이름은 프로퍼티 바인딩 시점에 enum 화이트리스트로 검증 |

---

## 8. 확장 포인트

호스트가 **1일차부터** 쓰는 확장점은 넷이고, 전부 빈으로 선언하면 스타터가 알아서 집는다.

| 빈 | 수집 방식 | 무엇을 바꾸나 |
|----|----------|--------------|
| `AimonAgentCustomizer` | `List<>` — N개 | 에이전트별 도구·커맨드·훅 (§4.11, §6 D16) |
| `VirtualFileSystemFactory` | `@ConditionalOnMissingBean` — 1개 | 에이전트/테넌트별 파일 백엔드 (기본: 로컬 서브트리) |
| `CredentialStoreFactory` | `@ConditionalOnMissingBean` — 1개 | 테넌트별 자격증명 (기본 구현 없음 — §9) |
| `CapabilityResolver` | `@ConditionalOnMissingBean` — 1개 | `__capability.*` 부여 주체 (기본: 아무것도 부여하지 않음) |

나머지(`LlmClient`, `SessionRecordStore`, `SkillApprovalChannel` …)도 전부 `@ConditionalOnMissingBean`
이므로 호스트가 정의하면 호스트가 이긴다(§1.4).

---

## 9. 남은 것 · 하지 말 것

### 9.1 아직 코드가 없는 것

| 무엇 | 지금 상태 | 해소 방향 |
|------|----------|----------|
| **D13 의 "시끄럽게" 절반** | 결정(§6 D13)은 유효하지만 `EnvironmentChangeEvent` 리스너도 `stale-config` degradation 도 코드에 없다. 프로퍼티를 리프레시하면 지금은 **조용히** 무시된다 | `@ConditionalOnClass(EnvironmentChangeEvent.class)` 리스너 하나 — `aimon.*` 키가 바뀌면 WARN + `RuntimeDegradations` 등재 |
| **선언적 도구 allowlist** | 메인 에이전트 경로가 `allowedTools = List.of()`(= 무제한)로 하드코딩되어 있어 `aimon.tools.allowed` 를 만들 수 없다 | `PermissionRequestHook` 체인 위에 구현. 코어 변경 필요 |
| **`CredentialStore` 참조 구현** | 팩토리 SPI(`CredentialStoreFactory`)만 있고 구현이 트리에 없다. rewake webhook 과 참조 기반 자격증명 해석이 동작하지 않는다 | 참조 구현을 코어에 넣을지 결정 |
| **스케줄 작업 재기동(rehydration)** | `InMemoryScheduledTaskRepository` 가 트리 안의 **유일한** `ScheduledTaskRepository` 구현이다. 내구성 있는 job store 를 붙이면 **더 나빠진다** — 트리거는 살아남고 그것이 가리키는 task 는 사라져 모든 발화가 "task not found" 로 끝나면서 내구성이 있는 것처럼 보인다 | 스타터는 Quartz 의 JDBC job store·클러스터링 프로퍼티를 **내지 않고**, 엔진을 세울 때마다 `RuntimeDegradations` 에 `scheduling-durability` 를 올려 기동 시점에 말한다. 실제 해소는 영속 구현이 생기는 시점이며 그때 이 프로퍼티들이 함께 열린다 |
| **`LlmClient` 데코레이터 자동 배선** | `MeteringLlmClient` / `LoggingLlmClient` / `TracingLlmClient` 를 호스트가 손으로 감싼다 | 프로퍼티로 옮기되 **중첩 방지**가 선행 — 메터링 데코레이터가 이중 적용되면 이중 계수된다 |
| **`aimon-rewake-webhook` 을 앱의 서버에 얹기** | 자체 Javalin 서버를 띄우므로 Boot 앱 안에서 두 번째 HTTP 서버가 된다 | 핸들러만 노출하도록 모듈을 바꾼다 |
| **GraalVM native** | Spring AOT 는 해소됐다 — `AimonRuntimeHints` 가 번들 리소스·Quartz·Jackson 힌트를 등록하고 루트 자동설정이 `@ImportRuntimeHints` 로 건다. 남은 것은 native 이미지 전체 검증이며, `ApplicationContextRunner` 가 native 테스트에서 동작하지 않는 제약이 그대로다 | 힌트 범위를 native 빌드로 실측한 뒤 확장 |
| **WAR / `jrt:` 배포** | 워크스페이스 walker 의 `default:` 분기(§4.8)가 미검증. Boot 실행 가능 jar 는 해당 없다 | 지원 범위 결정 후 |

### 9.2 코어 쪽 선행 과제

스타터가 감쌀 수는 있지만 **근본 해소는 코어에서만** 되는 것들이다.

- `AgentRuntimeRegistry` 에 **열거 메서드가 없고** `unregister` 가 닫지 않는다(§2.4). 스타터가 목록을
  중복으로 들고 있다.
- `DefaultHookExecutor` 가 자기 캐시 스레드풀을 **닫을 방법 자체가 없다**(§2.1).
- `McpClientManager.createClients()` 의 `awaitTermination(Long.MAX_VALUE)` — 응답 없는 서버 하나가
  기동을 영구 블록한다(§2.3).
- 스킬(`SkillContentParser`)·에이전트(`MarkdownAgentDefinitionParser`) frontmatter 를 무제한 생성자
  `new Yaml()` 로 파싱한다. `SubagentContentParser` 만 `SafeConstructor` 를 쓴다 — 셋을 통일해야 한다(§7).
- `BackendType.CACHE` 가 `computeIfAbsent` 로만 채워지는 static final 맵이라 임의 문자열이 들어오면
  무한 증가한다(§7).
- `OrcaAgentRuntimeManager.destroyRuntime()` 이 per-id 락을 쥔 채로 제거한다 — 에이전트 핫리로드를
  붙이려면 외부 직렬화가 필요하다(§2.4).

### 9.3 결정이 필요한 것

- **프로바이더별 파라미터 의미 차이** — temperature 유효범위, presence/frequency penalty 무시 등으로
  공통 `aimon.llm.*` 키의 의미가 프로바이더마다 다르다. 공통 키는 최소 교집합만 두고 프로바이더 전용
  키는 `aimon.llm.<provider>.*` 로 분리하는 방향.
- **예약 작업의 정의 버전** — cron 이 발화하는 시점에 에이전트 정의가 예약 당시와 달라져 있을 수 있다
  (`invalidate` 후 재생성). 의도된 동작이지만 사용자에게는 놀라움이다. 정의 버전을 `ScheduledTask` 에
  남겨 실행 로그에 표시할지 결정한다 — **스냅샷 고정은 하지 않는다**(옛 프롬프트로 도는 것이 더 나쁘다).
- **테넌트 축의 상한 정책** — `max-entries` 를 넘겼을 때 §4.11 은 "TTL 을 넘긴 유휴 항목 회수, 그래도
  없으면 거부" 를 택했다. 테넌트가 많은 SaaS 에서 거부는 가용성 사고로 보이므로 실측 후 재검토
  대상이며, 그 실측을 가능하게 하는 것이 `aimon.agent.runtimes.leased` 게이지다.
- **첫 실사용자 이관** — 내부 운영 앱은 세션마다 컨텍스트를 새로 만들고 있어 **스타터 도입 전에
  rescoping 이관이 선행**되어야 한다. 이관되면 그쪽의 세션 설정 클래스·lazy registry 어댑터·중복
  프로바이더 목록이 통째로 삭제되고, 그 과정이 §4.11 의 SPI 를 실사용으로 검증한다.

정본 목록은 [`docs/backlog/spring-boot-starter-open-items.md`](../../backlog/spring-boot-starter-open-items.md)
에 있다.

### 9.4 하지 말 것

- **`aimon-core` 에 Spring 의존성을 넣지 말 것.** DIP 위반이며 이 설계 전체의 전제다.
- **마커 인터페이스(`AgentScoped`)를 근거로 destroy 콜백을 자동 등록하는 `BeanPostProcessor` 를 만들지
  말 것.** 마커에 대한 fan-out 은 코어에도 없다(§2.1). 소멸은 타입별 하드코딩이다.
- **`LiveSession` 을 빈으로 만들지 말 것** — 생성자가 `OnSessionStart` 훅을 발화하고, 소멸이
  `AgentRuntime` 으로 cascade 하면 안 된다(§6 D3, §4.6).
- **전역 `VirtualFileSystem` 빈을 되살리지 말 것.** "빈 하나면 공유" 폴백은 다중 에이전트에서 곧바로
  파일 유출이다(§6 D15).
- **HTTP 엔드포인트를 제공하지 말 것.** 인증·라우팅은 앱의 도메인이다(§6 D12).
- **에이전트 정의를 프로퍼티로 작성하게 하지 말 것.** 에이전트는 마크다운 번들이고,
  `aimon.agents.<name>.*` 가 담는 것은 **배선 값**(번들 이름, 예산, 도구 on/off)이지 프롬프트가 아니다.
- **에이전트 CRUD 를 넣지 말 것.** 스타터는 `AimonAgents.invalidate` 수신구만 준다(§6 D12).
- **자동 스키마 마이그레이션을 기본 활성하지 말 것.** 기본은 검증이다.
- **`spring-boot-starter-quartz` 를 대체하지 말 것.** 앱의 Quartz 를 빌려 쓰되 대체하지 않는다(§6 D9).
- **Boot 3 / Boot 4 동시 지원 아티팩트를 만들지 말 것.** Boot 팀이 강하게 비권장한다(§6 D6).

---

## 부록. 참조 파일 지도

**`aimon-bootstrap`** — `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/` (40파일).

| 무엇 | 파일 |
|------|------|
| 조립체와 스펙 | [`AimonStack.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStack.java) · [`AimonStackBuilder.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java) · [`AimonStackSpec.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackSpec.java) |
| 종료 총순서 | [`TeardownPhase.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/TeardownPhase.java) · [`TeardownRegistry.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/TeardownRegistry.java) |
| 기동 시점 진단 | [`HealthReport.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/HealthReport.java) · [`RuntimeDegradations.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/RuntimeDegradations.java) |
| 런타임 축 (6) | [`runtime/`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/runtime/) — `AgentRuntimeResolver` · `AgentRuntimeProvisioner` · `AgentRuntimeLease` · `ProvisionedAgentRuntime` · `AgentRuntimeEviction` · `SchedulingLifecycle` |
| 조립 내부 (5) | [`assemble/`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/assemble/) — `StackLiveSessionOpener` · `StackAgentRuntimeProvisioner` · `LeasedLiveSession` · `MemoryAssembly` · `StackPaths` |
| 호스트가 넘기는 값 (18) | [`spec/`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/spec/) — 스펙 10종(`Agent`·`AgentRuntime`·`Executor`·`FileSystem`·`Llm`·`Memory`·`Scheduling`·`Session`·`SkillApproval`·`Tool`) · 팩토리 4종(`VirtualFileSystem`·`CredentialStore`·`KnowledgeStore`·`SkillApprovalChannel`) · 커스터마이저 2종 · 값 2종(`AgentDescriptor`, `AgentWorkspaceLayout`) |
| 예외 (4) | [`exception/`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/exception/) — `AimonBootstrapException` · `AimonTeardownException` · `AgentRuntimeExhaustedException` · `UnknownAgentRuntimeException` |

**`aimon-spring-boot-starter`** — `modules/aimon-spring-boot-starter/src/main/` (java 29 + 리소스 3).

| 무엇 | 파일 |
|------|------|
| 호스트 파사드 | [`AimonSessions`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/AimonSessions.java) · [`AimonAgents`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/AimonAgents.java) (+ `Default*` / `Disabled*` 구현, `AimonDisabledException`) |
| 자동설정 슬라이스 (8) | [`autoconfigure/`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/) — `AimonAutoConfiguration` 과 `Llm` · `FileSystem` · `Session` · `Scheduling` · `Memory` · `Knowledge` · `Observability` |
| 프로퍼티·선택자 | [`AimonProperties`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonProperties.java) + enum 8종 (`ApprovalMode` · `KnowledgeBackend` · `MemoryBackend` · `MemoryPeerMode` · `MemoryRedaction` · `PayloadCapture` · `SchedulingBackend` · `SessionStoreType`) |
| 라이프사이클 | [`AimonRuntimeLifecycle`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonRuntimeLifecycle.java) · [`AimonSchedulingLifecycle`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonSchedulingLifecycle.java) |
| AOT 힌트 | [`AimonRuntimeHints`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonRuntimeHints.java) — 번들 스킬 리소스 서브트리 · Quartz 이름 인스턴스화 · Jackson todo 바인딩 |
| Actuator | [`health/AimonHealthIndicator`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/health/AimonHealthIndicator.java) · [`metrics/AimonMetrics`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/metrics/AimonMetrics.java) |
| 등록 리소스 | `META-INF/spring/…AutoConfiguration.imports` · `…AutoConfiguration.replacements`(의도적으로 빈 파일) · `META-INF/additional-spring-configuration-metadata.json` |

**BOM** — [`modules/aimon-bom/build.gradle.kts`](../../../modules/aimon-bom/build.gradle.kts).

---

## 관련 문서

- [`docs/overview/scope-model.md`](../../overview/scope-model.md) — 수명·소유권·소멸 규칙. §4.4 의 근거
- [`docs/overview/glossary.md`](../../overview/glossary.md) — session / live session / turn / execution
- [`docs/getting-started/embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md) — **스타터 기준의 임베딩 가이드**. 수동 배선은 부록 A 로 내렸다
- [`docs/getting-started/aimon-core-integration-via-cli-reference.md`](../../getting-started/aimon-core-integration-via-cli-reference.md) — `AgentSetupFactory` 해설
- [`docs/features/session/agent-session-guide.md`](../../features/session/agent-session-guide.md) — `LiveSession` API
- [`docs/features/session/web-session-deployment-guide.md`](../../features/session/web-session-deployment-guide.md) — 멀티 노드 배포
- [`docs/features/scheduling/quartz-scheduling-web-deployment-guide.md`](../../features/scheduling/quartz-scheduling-web-deployment-guide.md) — Quartz 배포
- [`session/routing.md`](../session/routing.md) — `SessionRouter` 설계
- [`.claude/rules/architecture.md`](../../../.claude/rules/architecture.md) — 모듈 의존 방향. D5 가 예외를 요구한다
