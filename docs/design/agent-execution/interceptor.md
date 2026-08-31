# Execution Interceptor — `execute()` 경계를 가로챈다

> Status: **IMPLEMENTED**. 프레임워크가 자동으로 감싸지 않는다 — 호출자가 명시적으로 적용하는 데코레이터다.
> 적용 대상: `aimon-core`
> 관련 문서: [`orca-executor.md`](orca-executor.md) (감싸이는 실행기),
> [`hook-development-guide.md`](../../features/hook/hook-development-guide.md) (도구 수준 계층)

---

## 1. 무엇을 푸는가

훅 시스템(`OnStart`, `PreTool`, `PostTool`, `OnStop`)은 에이전트 **내부** 라이프사이클에 걸린다. 그것으로는
`execute()` 호출 자체를 가로챌 수 없다 — 인증, rate limiting, 요청/응답 변환, 실행 단위 감사 로그처럼
"이 실행을 시작할 것인가"를 묻는 관심사는 훅이 발화하기 **전에** 결정되어야 하기 때문이다.

인터셉터 체인은 그 경계 하나만 담당한다.

- **기존 인터페이스를 바꾸지 않는다** — `AgentExecutor` 는 그대로이고, 데코레이터가 투명하게 감싼다
- **훅과 섞지 않는다** — 실행 수준과 도구 수준은 별개 계층이며 서로를 대체하지 않는다
- **제네릭을 보존한다** — `<CTX, REQ, RES>` 를 체인 전체에 통과시켜 컴파일 타임에 타입을 지킨다

| 용어 | 뜻 |
|------|-----|
| 패스스루 인터셉터 | `chain.proceed()` 를 **항상** 호출한다 (로깅, 메트릭) |
| 차단 인터셉터 | 조건에 따라 호출하지 않고 직접 결과를 만든다 (인증, rate limit) |

---

## 2. 구조

```
Caller (ReplSession, LiveSession …)
        │
        ▼
┌──────────────────────────────────────────────────┐
│ InterceptingAgentExecutor  (AgentExecutor 구현)   │
│   [order -1000] ─▶ [order -50] ─▶ [order 0] ─┐   │
└──────────────────────────────────────────────┼───┘
                                               ▼
                            delegate.execute(context, request)
                                   = OrcaAgentExecutor
                                   OnStart → ReAct 루프 → OnStop
```

| 컴포넌트 | 역할 |
|----------|------|
| `AgentExecutionInterceptor<CTX, REQ, RES>` | 체인의 한 단위. `intercept(ctx, req, chain)` |
| `AgentExecutionChain<CTX, REQ, RES>` | 다음 단계로 넘기는 `@FunctionalInterface`. `proceed(ctx, req)` |
| `InterceptingAgentExecutor<CTX, REQ, RES>` | 체인을 적용하는 데코레이터. 빌더로 만든다 |

셋 다 `at.aimon.core.agent.interceptor` 에 있다. 구체 인터셉터는 사용처가 정의하며, 코어가 제공하는
것은 `at.aimon.core.agent.queue.QueueMetricsInterceptor` 하나다(§6).

**계층 분리.** 인터셉터는 `execute()` 호출 **전체**를 감싸 요청/응답 변환과 실행 차단을 하고, 훅은
에이전트 내부에서 개별 도구 실행을 제어한다. 둘은 상호 보완이며, 인터셉터가 실행을 차단하면 훅은 애초에
발화하지 않는다.

---

## 3. 계약

**`AgentExecutionInterceptor`**

```
RES intercept(CTX context, REQ request, AgentExecutionChain<CTX, REQ, RES> chain)
    // chain.proceed() 는 이 단계에서 최대 1회
    // 호출하지 않으면 실행 차단 — 직접 RES 를 만들어 반환해야 한다
    // 2회 이상이면 IllegalStateException

default int getOrder()      // 0. 낮은 값이 바깥쪽(먼저 실행)
default String getName()    // 클래스 단순명. 진단·로깅용
```

**`AgentExecutionChain`** 은 `proceed(context, request) → RES` 하나뿐인 함수형 인터페이스다.

**제네릭 전략은 인터셉터 종류가 정한다.** 패스스루는 결과를 만들 필요가 없으므로 타입 파라미터를 열어
두고 아무 `AgentExecutor` 에나 재사용한다. 차단 인터셉터는 올바른 결과 타입을 **직접 생성**해야 하므로
구체 타입(`OrcaAgentExecutionResult` 등)으로 특정한다. Java 제네릭은 invariant 이라
`AgentExecutor<OrcaRuntime, …>` 가 `AgentExecutor<AgentRuntime, …>` 의 하위 타입이 아니고, 타입을
보존하지 않으면 이 구분이 런타임 `ClassCastException` 으로 미뤄진다.

---

## 4. 데코레이터

```java
InterceptingAgentExecutor<CTX, REQ, RES> intercepted = InterceptingAgentExecutor.builder(executor)
        .addInterceptor(new QueueMetricsInterceptor<>(queueManager))
        .addInterceptor(new AuthInterceptor(authService))
        .build();

RES result = intercepted.execute(context, request);   // 호출자에게는 원래 타입 그대로
```

`builder(delegate)` 가 delegate 로부터 세 타입 파라미터를 추론하므로 호출부에 타입 인자를 적을 일이 없다.
빌더가 하는 일은 넷이다.

1. `build()` 시점에 `getOrder()` 기준 **stable sort** — 같은 order 는 등록 순서를 유지한다
2. `execute()` 에서 정렬된 목록을 **역순으로** 감아 체인을 만든다 (`delegate::execute` 가 마지막 단계)
3. 각 단계를 `oneShot` 래퍼로 감싼다 — `AtomicBoolean.compareAndSet` 이므로 동시 호출에도 한 번만 통과한다
4. 인터셉터가 하나도 없으면 체인을 만들지 않고 delegate 를 직접 호출한다

`getDelegate()` 와 `getInterceptors()`(정렬된 불변 목록)로 구성을 들여다볼 수 있다.

---

## 5. 설계 결정

**미들웨어 체인.** 상속 기반 템플릿 메서드는 단일 상속에 묶여 조합이 안 되고, before/after 만 있는 단순
데코레이터는 요청 변환도 실행 차단도 못 한다. 훅을 확장하는 안은 도구 수준 계층에 실행 수준 관심사를
섞으므로 기각했다.

**`proceed()` 1회 제한.** 에이전트 실행은 LLM 호출과 토큰 소비를 동반하므로 이중 실행의 대가가 크다.

| 호출 횟수 | 동작 |
|-----------|------|
| 0회 | 실행 차단 — 인터셉터가 결과를 직접 만든다 |
| 1회 | 정상 |
| 2회 이상 | `IllegalStateException("chain.proceed() has already been called…")` |

재시도가 필요하면 인터셉터 계층이 아니라 별도의 재시도 메커니즘을 쓴다. 같은 요청을 체인 안에서 두 번
흘리는 것은 바깥 인터셉터가 이미 본 요청을 다시 보게 만들어 로그·메트릭·감사 기록을 모두 어긋나게 한다.

**예외는 전파한다.** 훅과 반대다. 훅 예외는 프레임워크가 격리하지만 인터셉터 예외는 호출자에게 그대로
올라간다 — 인터셉터를 등록한 것이 호출자이고, 인증 실패 같은 결정을 조용히 삼키면 그 계층을 쓰는 이유가
사라지기 때문이다. 대신 **후처리는 스스로 지켜야 한다**: 안쪽 인터셉터의 후처리에서 예외가 나면 바깥
인터셉터의 후처리는 실행되지 않으므로, 자원 정리와 메트릭 기록은 `try-finally` 에 둔다.

**순서 관례.** 값이 낮을수록 바깥이다.

| 범위 | 용도 |
|------|------|
| -1000 ~ -100 | 인프라 관측 (큐 메트릭, 실행 로깅) |
| -100 ~ 0 | 보안 (인증, 인가, rate limit) |
| 0 ~ 100 | 비즈니스 (요청 정규화, 결과 가공) |

---

## 6. 구현 패턴

**패스스루 — 타입을 열어 둔다.**

```
class MetricsInterceptor<CTX, REQ, RES> implements AgentExecutionInterceptor<CTX, REQ, RES>:
    getOrder() → -90

    intercept(ctx, req, chain):
        timer 시작
        try:
            return chain.proceed(ctx, req)      // 항상 호출
        finally:
            timer 종료 후 기록                   // 예외 여부와 무관
```

**차단 — 결과 타입을 특정한다.**

```
class AuthInterceptor implements AgentExecutionInterceptor<OrcaRuntime, OrcaRequest, OrcaAgentExecutionResult>:
    getOrder() → -50

    intercept(ctx, req, chain):
        if not authService.isAuthorized(req.getPrincipal(), ctx.getAgent()):
            return OrcaAgentExecutionResult.failure("Unauthorized", …)   // proceed 호출 안 함
        return chain.proceed(ctx, req)
```

**코어가 제공하는 유일한 구현 — `QueueMetricsInterceptor`** (`at.aimon.core.agent.queue`). 실행 경계를
알아야만 답할 수 있는 질문("턴 시작 시점의 큐 깊이는? 턴이 도는 동안 몇 건이 들어오고 빠졌는가?")을 위해
실행 동안만 살아 있는 `MessageQueueListener` 를 붙였다 뗀다. `getOrder()` 가 `-1000` 인 것은 다른
인터셉터가 만든 큐 활동까지 포함시키기 위해 가장 바깥에 서려는 것이다. 누적 카운터는 `LongAdder` 이고
턴마다 DEBUG 한 줄을 남긴다 — 메트릭 백엔드가 있는 프로젝트는 이 클래스를 감싸 자기 레지스트리로 옮긴다.

같은 `AgentRuntimeId` 의 실행이 **동시에** 둘 이상 돌면 두 리스너가 같은 트래픽을 각각 세어 중복
계상된다. 한 런타임의 턴은 순차로 도는 것이 정상이므로 실무에서는 문제가 되지 않지만, 계약상 그렇다는
것은 알아 두어야 한다.

---

## 7. 스트리밍 이벤트와의 관계

`at.aimon.core.agent.stream` 의 `StreamingAgentExecutor` / `AgentExecutionEvent` 는 인터셉터와 **목적이
다르다**. 어느 하나로 다른 하나를 대체하려 하면 설계 가정이 깨진다.

| 관심사 | 인터셉터 | 실행 이벤트 |
|--------|----------|-------------|
| 목적 | 실행 **제어** | 실행 **관찰** |
| 경계 | `execute()` 전/후 — 실행당 1회 | 내부 도달점마다 다수 |
| 실행 영향 | `proceed()` 미호출로 차단 가능 | 소비자는 실행을 바꿀 수 없다 |
| 오류 | 호출자로 전파 | 리스너 예외는 격리(WARN 로그) |
| 스레딩 | 호출 스레드에서 동기 | 발행 스레드 임의, 리스너가 thread-safe 여야 |

이벤트가 인터셉터를 대체할 수 없는 이유는 발행 결과가 실행에 반영되지 않고 리스너 예외가 격리되기
때문이다 — rate limit 초과 같은 결정을 이벤트 경로로 표현하면 조용히 삼켜진다. 반대로 인터셉터가
이벤트를 대체할 수 없는 이유는 `execute()` 경계에서만 호출되어 iteration 시작·도구 호출·압축 경계 같은
세밀한 시점을 볼 수 없고, 인터셉터 예외는 실행을 죽이므로 관측 코드가 실행을 방해하게 되기 때문이다.

둘은 직교하므로 **병렬로** 쓴다. `InterceptingAgentExecutor` 는 `AgentExecutor` 계약만 구현하고 스트리밍
API 를 노출하지 않으므로, 이벤트가 필요한 호출자는 **감싸기 전의 실행기 인스턴스**를 따로 들고 그쪽으로
구독한다.

```
executor = agentSetup.getAgentExecutor()          // OrcaAgentExecutor — 이벤트 구독용으로 보관
intercepted = InterceptingAgentExecutor.builder(executor).addInterceptor(…).build()

result = intercepted.execute(ctx, req)            // 제어 경로
executor.executeAsync(ctx, req, repl::onEvent)    // 관찰 경로
```

인터셉터가 실행을 차단하면 그 실행에서는 이벤트가 아예 발행되지 않는다 — 관찰할 실행이 없기 때문이다.

---

## 8. 관심사 배치표

새 관심사를 어느 계층에 둘지 고를 때의 기준이다.

| 관심사 | 인터셉터 | 훅 | 이벤트 |
|--------|:--------:|:--:|:------:|
| 실행 전 인증·인가 | O | | |
| rate limiting | O | | |
| 실행 단위 로깅·메트릭 | O | | |
| 요청/응답 변환 | O | | |
| 개별 도구 실행 차단·피드백 | | O | |
| 도구 실행 후 모니터링 | | O | |
| 에이전트 내부 시작/종료 알림 | | O | |
| iteration 진행 상황 렌더링 | | | O |
| 도구 호출·결과 tail | | | O |

---

## 부록 — 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 인터셉터 계약 | [`AgentExecutionInterceptor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/interceptor/AgentExecutionInterceptor.java) |
| 체인 | [`AgentExecutionChain.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/interceptor/AgentExecutionChain.java) |
| 데코레이터 | [`InterceptingAgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/interceptor/InterceptingAgentExecutor.java) |
| 구현 예 | [`QueueMetricsInterceptor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/queue/QueueMetricsInterceptor.java) |
| 감싸이는 실행기 | [`OrcaAgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java) |
| 실행기 계약 | [`AgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/AgentExecutor.java) |
| 이벤트 계층 | [`agent/stream/`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/stream/) |
| 훅 계층 | [`hook/`](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/) |

관련 문서: [`solid-principles.md`](../../project/solid-principles.md)
