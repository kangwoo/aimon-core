# AgentRuntime 과 Agent Scope

> Status: **IMPLEMENTED**
> 적용 대상: `aimon-core`
> 관련 규칙: [`.claude/rules/scheduling.md`](../../../.claude/rules/scheduling.md),
> [`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md)
> 관련 문서: [`scope-model.md`](../../overview/scope-model.md) (현행 규칙),
> [`session/session-model.md`](../session/session-model.md)

이 문서는 **왜 agent scope 이 별도의 수명 계층인가**를 기록한다. "지금 무엇을 언제 닫아야 하는가" 는
[`scope-model.md`](../../overview/scope-model.md) 가 답한다 — 여기는 그 규칙이 나오게 된 결함과
그때 버려진 대안을 남긴다.

이 문서가 설계한 타입은 당시 `AgentExecutionContext` 라는 이름이었고 지금은 **`AgentRuntime`** 이다.
개명의 이유가 곧 이 문서의 결론이다 — "context" 는 실행마다 새로 생기는 값처럼 읽히지만, 실제로는
agent 당 하나 살아 있는 장수명 런타임이다. 옛 이름의 매핑표는
[`rename-maps.md`](../../migration/rename-maps.md) 에 있다.

---

## 1. 문제 — 스케줄이 자기 런타임을 잃어버렸다

개편 전 `AgentRuntime` 은 **세션마다 새로 생성되어 registry 에 등록되고, 세션이 끝나면
unregister** 되었다. `AgentRuntimeId` 도 `generate()` 로 매번 새 UUID 를 발급했다.

여기에 스케줄링이 얹히면 곧바로 깨진다. `ScheduledTask` 는 application-scoped 이므로 세션보다 오래
살고, cron 이 다음 회차에 발화할 때 `RoutineExecutor` 는 태스크가 기록해 둔 런타임 id 로
registry 를 조회한다. 그 id 를 발급한 세션은 이미 끝나서 registry 에서 사라진 뒤다.

이것은 lifecycle 버그가 아니라 **scope 정의의 문제**였다. 런타임이 담고 있는 것을 하나씩 보면
세션에 종속된 값이 **하나도 없기 때문이다**.

| 필드 | 실제 종속 대상 |
|------|---------------|
| `agent`, `toolRegistry`, `hookRegistry`, `commandRegistry`, `subagentRegistry`, `skillRegistry`, `environment`, `compactionEngine`, `compactionGuard`, `promptSizeRecoveryStrategy`, `toolContextEnrichers` | agent 정의 |
| `fileSystem` | 앱 전역 또는 agent 별 1개 |
| `mcpClientManager` | agent 정의 (당시 세션 종료에서 닫고 있었다) |
| `knowledgeStore` | 앱 전역 — 이미 `ApplicationScoped` 인데 세션 종료에서 닫고 있었다 |

메시지 이력·턴 카운터 같은 **세션 상태는 런타임에 없다**. 그것은 `SessionRecord` 와 `LiveSession`
쪽에 있다. 즉 런타임은 이미 의미상 "agent + 실행 능력" 이었고, 수명 관행만 세션에 묶여 있었다.

---

## 2. Agent scope

Application 과 Session 사이에 **Agent** 를 넣는다. 한 `Agent` 정의(이름·시스템 프롬프트·모델)와
선택적 discriminator 의 조합이 하나의 수명 단위다.

```
Application  ─ SchedulingEngine, ScheduledTaskManager, RoutineExecutor
             ─ AgentRegistry, AgentRuntimeRegistry, KnowledgeStore, CredentialStore
      ▲
      │
   Agent     ─ AgentRuntime  (id = "agent:<name>[:<discriminator>]")
             ─ ToolRegistry, HookRegistry, CommandRegistry, SubagentRegistry,
               SkillRegistry, Environment, CompactionEngine/Guard, Enrichers,
               McpClientManager
      ▲
      │ 참조 (read-only)
  Session    ─ SessionRecord, SessionTranscript, SessionTotals
      ▲
      │
 Live session ─ LiveSession, 메시지 큐, 이벤트 publisher
```

세션과 라이브 세션이 별도 칸으로 갈라진 것은 **이 문서 이후의 개편** 결과다. 이 문서가 도입한 것은
가운데 Agent 칸이며, 당시 아래 두 칸은 "Conversation" 하나였다. 그 분할의 근거는
[`session/session-model.md`](../session/session-model.md) 에 있다.

### 2.1 AgentRuntimeId — 결정론적 발급

`generate()` 를 **제거**한다. 이것이 이 설계의 핵심 한 줄이다 — id 가 무작위인 한 어떤 lifecycle
규칙을 붙여도 cron 재발화는 런타임을 찾지 못한다.

| 팩토리 | 결과 |
|--------|------|
| `from(Agent)` | `agent:<name>` |
| `from(Agent, String discriminator)` | `agent:<name>:<discriminator>` |
| `fromName(String)` / `fromName(String, String)` | 위 둘의 문자열 입력판 (세션 매니저가 `agentRef` 로부터 도출) |
| `of(String)` | 역직렬화 — 진입점에서 `agent:<name>[:<disc>]` 레이아웃을 완전 검증 |

id 는 `agentName` 과 `discriminator` 를 **구조적으로 저장**하고 `value()` 를 두 필드에서 파생한다.
접근자가 와이어 문자열을 매번 재파싱하던 초기 구현은 파싱 실패 시 `IllegalStateException` 을 던질
수 있었다 — 도달 가능한 모든 인스턴스가 레이아웃 불변식을 만족하도록 검증을 `of()` 한 곳으로 모았다.

discriminator 는 non-blank 이며 콜론을 포함할 수 없다(`IllegalArgumentException`). 콜론이 구분자이므로
그것을 허용하면 `value()` 의 파싱이 모호해진다.

### 2.2 전제

1. **런타임 : (Agent, discriminator) = 1 : 1.** discriminator 를 쓰지 않는 사용처에서는 agent 당 1개다.
2. **agent 이름의 유일성은 호출자 책임.** `AgentRegistry.register(Agent)` 는 같은 이름이 들어오면
   기존 항목을 교체한다.
3. **discriminator 의 의미는 호출자가 정한다.** 프레임워크는 단순 식별자로만 취급한다 —
   테넌트·환경·사용자 중 무엇으로 쓸지, 요청에서 어떻게 고를지는 부트스트랩 코드의 몫이다.
4. **hot-reload 는 범위 밖.** agent 정의가 런타임에 바뀌어도 (name, discriminator) 가 같으면 같은 id 다.

---

## 3. 자원 재배치

### 3.1 McpClientManager — agent-scoped

`AgentScoped` 마커의 의미를 agent-scoped 로 확정하고 MCP 클라이언트 매니저를 거기 둔다.

- AIMON 의 주 사용처(IT 운영 자동화)에서 MCP 는 agent 정의에 종속된 인프라 도구다 —
  Kubernetes·클라우드 API 같은 것으로, 사용자별 격리가 일반적인 요구가 아니다.
- 사용자별 자격증명이 필요하면 `CredentialStore`(application-scoped) 를 도구 호출 시점에 조회하는
  쪽이 자연스럽다.
- stdio MCP 는 프로세스 spawn 이다. 세션마다 spawn 하면 사용자가 체감한다.

세션 단위 MCP 가 실제로 필요해지면 라이브 세션 쪽에 별도 슬롯을 추가한다 — agent-scoped MCP 와
병존하므로 breaking change 없이 얹을 수 있다. 현재는 미구현이다.

### 3.2 KnowledgeStore — 결정이 아니라 버그였다

`KnowledgeStore` 는 이미 `extends ApplicationScoped` 로 선언되어 있었는데, 런타임의 `close()` 가
그것을 닫고 있었다. 마커 계약 위반이므로 정책 선택지가 아니라 **호출 제거**다.

### 3.3 소멸 — 마커는 문서일 뿐

`AgentRuntime.close()` 는 `AgentScoped` 구현체를 **스캔하지 않는다**. 하드코딩된 목록만 닫는다.

```java
// OrcaAgentRuntime.close()
mcpClientManager  → close
workflowRunner    → close
ownedShell        → close   // 어셈블리가 withShell(...) 로 주지 않아 런타임이 직접 만든 경우에만
```

세 개 모두 예외를 삼키고 다음으로 넘어간다 — 하나가 실패해도 나머지 정리를 막지 않는다.
네이티브 자원을 쥔 agent-scoped 컴포넌트를 새로 추가한다면 이 목록에 **직접 한 줄을 넣어야** 한다.
마커를 붙이는 것만으로는 아무 일도 일어나지 않는다.

목록이 하나에서 셋으로 늘어난 과정이 그 규칙의 증거다. 자동 fan-out 을 쓰지 않은 이유는 순서다 —
닫는 순서가 자원마다 다르고(`ownedShell` 은 마지막), 스캔은 순서를 표현하지 못한다. 그 순서가
아직 완결되지 않은 지점(`BackgroundBashManager`)은 `OrcaAgentRuntime.close()` 의 주석과
[`tool/contract-hardening.md`](../tool/contract-hardening.md) 에 기록되어 있다.

### 3.4 누가 닫으면 안 되는가

- **`LiveSession.close()` 는 `AgentRuntime.close()` 를 호출하지 않는다.** 같은 agent 의 다른 세션이
  아직 그 런타임을 쓰고 있을 수 있다. 핸들 자원만 정리한다.
- **런타임 소멸이 스케줄링 컴포넌트를 닫지 않는다.** `SchedulingEngine` / `ScheduledTaskManager` /
  `RoutineExecutor` 는 application-scoped 다.

런타임 등록은 부트스트랩에서 1회 수행한다 — CLI 는 `AgentSetupFactory`, web 은 `LiveSessionOpener`.
해제는 앱 shutdown 또는 명시적 agent 제거(`OrcaAgentRuntimeManager.destroyRuntime`) 뿐이다.
등록을 `AgentRegistry.register` 콜백이나 hot-reload 트리거에 결합하지 않는 것은 의도다 — 그러면
"언제 만들어지는가" 가 등록 순서에 따라 달라진다.

---

## 4. 마이그레이션

### 4.1 Before

```java
AgentRuntimeId contextId = AgentRuntimeId.generate();
OrcaAgentRuntime ctx = manager.getOrCreateRuntime(contextId, bundle, fs, store);
// ... 대화 진행
ctx.close();
manager.destroyRuntime(contextId);
```

### 4.2 After — 직접 배선하는 호스트

```java
// 부트스트랩 1회 (agent 등록 시점)
OrcaAgentRuntime runtime = manager.getOrCreateRuntime(bundle, fs, store);
// → AgentRuntimeId.from(bundle.getAgent())   //  "agent:<name>"

// 같은 agent 를 테넌트별로 분리하려면 discriminator
OrcaAgentRuntime runtimeAcme = manager.getOrCreateRuntime(bundle, "acme", fs, storeAcme);
OrcaAgentRuntime runtimeBeta = manager.getOrCreateRuntime(bundle, "beta", fs, storeBeta);
// → "agent:<name>:acme", "agent:<name>:beta" — 별개 인스턴스

// 세션 시작 — 런타임을 새로 만들지 않고 기존 인스턴스를 재사용한다
LiveSession session = sessionFactory.open(sessionId, agentRef, LiveSessionOptions.defaults());

// 세션 종료 — 런타임은 닫지 않는다
session.close();

// 앱 종료 시점에만
manager.destroyRuntime(runtime.getId());
```

`getOrCreateRuntime` 은 double-checked locking 으로 (agent, discriminator) 당 1회만 생성하며,
`hookRegistrars` 등록도 그 안에서 한 번만 일어난다 — N 회 호출해도 훅이 N 번 등록되지 않는다.

### 4.3 After — Spring 호스트

4.2 는 Spring 을 쓸 수 없는 호스트의 목적지다. Spring Boot 앱이라면 위 배선은 **옮겨 적을 대상이
아니라 삭제 대상**이다 — `aimon-spring-boot-starter` 가 런타임 등록·리졸버·라우터를 대신 조립하므로,
세션마다 컨텍스트를 만들던 `@Configuration` 과 그것을 감싸던 lazy registry 어댑터가 통째로 없어진다.

```java
// 배선 코드가 없다. application.yml 의 aimon.* 프로퍼티가 4.2 전체를 대신한다.

@Service
class ChatService {

    private final AimonSessions sessions; // 스타터가 등록해 주는 빈

    ChatService(AimonSessions sessions) {
        this.sessions = sessions;
    }

    AgentExecutionResult ask(SessionId sessionId, String input) {
        return sessions.submit(sessionId, input); // 세션을 열고 닫는 코드도 여기 없다
    }
}
```

프로퍼티 목록과 갈아끼우는 자리는
[`embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md) 에 있다.
옛 이름 ↔ 새 이름 매핑은 그 문서의
[부록 B](../../getting-started/embedding-agent-in-application.md#부록-b-옛-이름-매핑) 에 있다.

### 4.4 영속 데이터

`ScheduledTask` 를 영속 백엔드(Quartz/Postgres 등)에 저장해 운용 중이라면 기존 `bound_context_id`
컬럼의 UUID 를 `agent:<name>` 형식으로 변환해야 한다. 이 트리에는 그 시점에 출시된 영속 백엔드가
없어 마이그레이션 스크립트를 제공하지 않았다 — 도입 환경에서 deployment-specific 하게 수립한다.

---

## 5. Opener 는 id 형식을 알지 않는다

Phase 1 직후 web 쪽 opener 는 여전히 agent 참조 문자열을 받아 **내부에서** id 를 도출했다. 그러면
opener 구현체마다 prefix·discriminator 결합 규칙을 알아야 하고, discriminator 는
`OpenAttributes` 의 키 컨벤션(`exec.contextDiscriminator`)에 묶여 1급 시민이 되지 못한다.

```java
// Before — opener 가 id 도출 책임을 진다
LiveSession open(SessionId sessionId, AgentRef agentRef, LiveSessionOptions options, OpenAttributes attrs) {
    String discriminator = attrs.getString("exec.contextDiscriminator").orElse(null);
    AgentRuntimeId ctxId = (discriminator == null)
            ? AgentRuntimeId.fromName(agentRef.name())
            : AgentRuntimeId.fromName(agentRef.name(), discriminator);
    AgentRuntime ctx = registry.get(ctxId).orElseThrow();
    return sessionFactory.open(sessionId, ctx, options);
}
```

```java
// After — opener 는 lookup 만 한다. id 형식은 매니저가 유일하게 안다.
LiveSession open(SessionId sessionId, AgentRuntimeId ctxId, LiveSessionOptions options,
        OpenAttributes attrs) {
    AgentRuntime ctx = registry.get(ctxId)
            .orElseThrow(() -> new IllegalStateException("runtime not bootstrapped for " + ctxId));
    return sessionFactory.open(sessionId, ctx, options);
}
```

이에 맞춰 `SubmitRequest.contextDiscriminator` 가 1급 필드가 되었다 — `null` 은 허용하되 non-null
이면 그 자리에서 non-blank + 콜론 미포함을 검증한다. 세션 매니저가 `{agentRef, contextDiscriminator}`
에서 id 를 도출해 opener 에 넘긴다.

`RejectedAt` / `InterruptedAt` 이벤트는 런타임이 없는 상황에서도 emit 되므로 sentinel id 가 필요하다.
매직 스트링 대신 상수로 분리했다 — `agent:web-rejected`, `agent:web-evicted`.

---

## 6. 열어 둔 것

1. **Hot-reload** — agent 정의가 런타임에 바뀔 때 런타임 재발급 정책과 이전 인스턴스의 자원 회수.
   `AgentRegistry` 의 replace 정책과 맞물린다. 지금은 교체된 이전 인스턴스를 누가 닫는지 미정의다.
2. **세션 단위 MCP** — 요구가 생기면 라이브 세션 쪽 슬롯으로 추가한다 (§3.1).
3. **이름 형식 가이드라인** — `agent:<name>[:<discriminator>]` 의 가독성을 위한 권장 규칙
   (영문 소문자/숫자/하이픈)을 강제할지 여부. 지금은 콜론·blank 만 막는다.
4. **discriminator 라우팅** — "현재 요청 → 어느 discriminator" 를 고르는 패턴(테넌트 컨텍스트,
   request scope)을 프레임워크가 일부라도 제공할지. 지금은 호출자 자유다.
