# 도구 병렬 실행 가이드 (Parallel Tool Execution)

> 한 LLM 응답에 담긴 여러 `tool_use`를, 서로 독립적이고 동시 실행이 안전한 경우에만 병렬로 실행하여
> iteration 당 도구 실행 지연(wall-clock)을 줄이는 기능에 대한 개발자·운영자 가이드.

관련 문서: [설계 문서](../../design/tool/parallel-execution.md) ·
[Tool 개발 가이드 §동시 실행 안전성](tool-development-guide.md#동시-실행-안전성-concurrencybehavior)

---

## 1. 개요

LLM이 한 응답에 여러 `tool_use`를 반환하는 것은 "이들은 서로의 결과를 기다릴 필요가 없다"는 **병렬
의도 신호**다. 하지만 이 신호만으로 곧장 병렬 실행하면 부수효과 충돌·공유 상태 레이스가 생길 수 있다.
그래서 AIMON은 **모델의 의도 + 프레임워크의 안전성 검증**이라는 2단 판단을 거친 뒤에만 병렬화한다.

- **기본값은 OFF.** 도입만으로는 동작이 바뀌지 않는다(회귀 0). 명시적으로 켜야 병렬 실행된다.
- **보수적이다.** 도구가 스스로 `CONCURRENT_SAFE`를 선언하고, 배치의 *모든* 도구가 안전할 때만 병렬.
  하나라도 불확실하면 배치 전체를 순차 실행한다.
- **결과 순서는 항상 보존된다.** 병렬로 실행해도 `tool_use` 입력 순서대로 결과를 재조립한다.
- **두 단계 동시성 한도(2-tier).** `maxConcurrency`는 executor가 공유하는 워커 풀의 **전역 상한**(호스트
  보호)이고, `perBatchMax`는 한 배치가 그 풀을 **동시에 얼마나 점유**할 수 있는지를 제한한다(한 배치의
  풀 독점 방지). 기본값은 `perBatchMax = maxConcurrency`라 단일 단계와 동일하게 동작한다(§2.3).

적용 범위: 메인 에이전트(`OrcaAgentExecutor`)와 서브에이전트(`DefaultSubagentExecutor`) 양쪽.

---

## 2. 빠른 시작

### 2.1 메인 에이전트에서 켜기

```java
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;

TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore);
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withToolConcurrencyConfig(ToolConcurrencyConfig.enabled(4)) // maxConcurrency = 4
        .create(llmClient, transcriptManager);
```

`OrcaAgentExecutor.builder()`를 직접 쓴다면:

```java
OrcaAgentExecutor executor = OrcaAgentExecutor.builder()
        .llmClient(llmClient)
        .conversationManager(conversationManager)
        // ... 필수 매니저들 ...
        .parallelToolDispatcher(new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(4)))
        .build();
```

### 2.2 서브에이전트에서 켜기

`DefaultSubagentExecutor`에는 빌더가 없으므로 fluent 세터로 주입한다:

```java
SubagentExecutor executor = new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager)
        .withParallelToolDispatcher(new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(4)));
```

설정하지 않으면 양쪽 모두 **순차 실행**(기존 동작)을 유지한다.

### 2.3 per-batch 공정성 캡 (perBatchMax) — 선택

`maxConcurrency`는 **executor가 공유하는 풀**의 전역 상한이다. 여러 대화/턴이 한 executor를 동시에 거치면
(예: 웹 멀티 대화) 이 풀을 함께 나눠 쓰므로, 한 턴이 안전한 도구 N개를 한꺼번에 요청하면 풀 슬롯을 독점해
다른 동시 턴을 굶길 수 있다. 이를 막으려면 **한 배치가 풀에서 동시에 점유할 수 있는 슬롯 수**를
`perBatchMax`로 제한한다:

```java
// 전역 풀은 8, 단일 배치는 그중 최대 2슬롯까지만 동시 점유
ToolConcurrencyConfig.enabled(8, 2);

// builder 로도 동일
ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(8).perBatchMax(2).build();
```

- `perBatchMax`를 지정하지 않으면 **`maxConcurrency`와 같게** 설정된다 → 단일 단계와 동일(비트 단위 호환).
- 유효 범위는 `[1, maxConcurrency]`. 벗어나면(명시적 `0` 포함) `build()`에서 `IllegalArgumentException`.
- permit은 **호출 스레드에서 submit 직전에** 획득하므로, 슬롯을 기다리는 도구가 공유 워커 스레드를 점유하지
  않는다(다른 턴을 굶기지 않는다). 배치가 `perBatchMax`보다 크면 그 지점에서 backpressure로 제출이
  직렬화될 뿐, 전역 풀과 결과 순서 보존은 그대로 유지된다.

> **주의:** `perBatchMax`는 *배치(턴 내)* 단위 캡이다. 수다스러운 대화가 배치를 연달아 쏘는 것까지는 막지
> 못한다 — 대화/테넌트 단위 공정성이나 클러스터 전역 상한은 도구 디스패처가 아니라 세션/요청 수용(admission)
> 레이어에서 다뤄야 한다.

---

## 3. 동작 원리

### 3.1 2단 판단 게이트

```
한 LLM 응답의 tool_use 목록(batch)
        │
        ▼
Layer 1 — 의도 (LLM)
   · 병렬 기능이 켜져 있는가? (ToolConcurrencyConfig.enabled)
   · batch 크기가 2 이상인가?            ── 아니오 → 순차
        │ 예
        ▼
Layer 2 — 안전성 (Framework)
   batch의 "모든" 도구가
   · ConcurrencyBehavior == CONCURRENT_SAFE 이고
   · InterruptBehavior ∈ { NON_INTERRUPTIBLE, COOPERATIVE } 인가?
   · 모두 registry 에서 resolve 되는가? (미등록/오타 이름 → 순차)
        │ 전부 통과
        ▼
   병렬 실행 (bounded daemon pool)
```

하나라도 미충족이면 **배치 전체를 순차** 실행한다(혼합 배치 부분 병렬화는 의도적으로 제외 — §6).

### 3.2 결과 순서 보존 · 이벤트 순서

- **결과**: 병렬로 실행해도 `ToolUseResult` 리스트는 입력 `tool_use` 순서(index)대로 재조립된다.
- **`ToolUseStarted` 이벤트**: 호출 스레드에서 **입력 순서대로** emit (결정론적).
- **`ToolResultReady` 이벤트**: 실제 **완료 순서**대로 emit (병렬 시 비결정적). 렌더러는 `toolUseId`로
  매칭하므로 정합성에 문제 없다. **UI/렌더러는 Ready 이벤트의 시간 순서에 의존하면 안 된다.**
- conversationMemory append는 join 이후 단일 스레드에서 단일 batch로 수행된다.

### 3.3 예외 격리

dispatcher는 `runner`(단일 도구 실행), `onStarted`, `onCompleted`에서 새어 나오는 예외와 null 결과를
모두 잡아 error `ToolUseResult`로 변환하거나 log-and-swallow 한다. 한 도구나 한 리스너의 실패가 배치
순서·join·다른 도구를 깨뜨리지 않는다.

---

## 4. 도구를 CONCURRENT_SAFE로 만들기

기본값은 `ConcurrencyBehavior.SEQUENTIAL`이다. 안전한 도구만 명시적으로 override 한다:

```java
@Override
public ConcurrencyBehavior getConcurrencyBehavior() {
    return ConcurrencyBehavior.CONCURRENT_SAFE;
}
```

### 선언 전 체크리스트 — 하나라도 불확실하면 `SEQUENTIAL`로 둔다

- [ ] **부수효과가 없거나 멱등인가?** 파일/샌드박스/외부 상태를 변조하지 않는다.
      `Edit`/`Write`/`Bash`/`TodoWrite`처럼 변조하는 도구는 반드시 `SEQUENTIAL`.
- [ ] **공유 가변 상태를 thread-safe 하게만 만지는가?** `ToolContext`로 전달되는 값 중 도구가 변조하는
      가변 객체가 있다면 thread-safe 여야 한다(§5).
- [ ] **InterruptBehavior가 `NON_INTERRUPTIBLE` 또는 `COOPERATIVE`인가?** `THREAD_INTERRUPT`/
      `EXTERNALLY_TERMINATED` 도구는 실행 스레드 기준으로 terminator를 등록하므로 공유 worker 스레드에서
      의미가 모호하다 → 게이트에서 **자동 제외**(설사 `CONCURRENT_SAFE`로 선언해도 병렬되지 않는다).
- [ ] **이 도구의 Pre/PostTool 훅이 thread-safe 한가?** 병렬 가능 도구의 훅은 worker 스레드에서 동시
      호출될 수 있다.
- [ ] **동일 외부 리소스를 동시에 다투지 않는가?** 같은 파일/같은 rate-limited 엔드포인트를 다투는 도구는
      `SEQUENTIAL`이 안전하다.

### 현재 CONCURRENT_SAFE로 선언된 도구

| 도구 | InterruptBehavior | 근거 |
|------|-------------------|------|
| `ReadTool` | NON_INTERRUPTIBLE | 읽기 전용. 유일한 공유 상태(`READ_FILES_KEY` Set)는 executor가 thread-safe set으로 주입(§5) |
| `GrepTool` | COOPERATIVE | 읽기 전용. 모든 가변 상태를 호출별 로컬에 할당 |
| `WebFetchTool` | COOPERATIVE | 멱등 외부 GET. 캐시는 synchronized. 동일 URL 동시 페치는 각자 미스할 수 있으나(중복 요청) 멱등이라 무해 |

> 새 도구를 추가할 때는 위 체크리스트로 개별 검증한 뒤 확정한다.

---

## 5. Thread-safety 계약

### 5.1 `ToolContext`의 공유 가변 상태

`ToolContext`는 구조적으로 불변(맵 자체는 unmodifiable)이지만, **저장된 값**은 deep-copy되지 않는다.
병렬 실행 시 도구가 `ToolContext`의 가변 값을 변조하면 레이스가 발생한다.

프레임워크가 식별한 유일한 가변 값은 `ReadTool.READ_FILES_KEY` Set이다. 두 executor는 `createToolContext`
시점에 이를 **thread-safe set으로 주입**한다:

```java
builder.put(ReadTool.READ_FILES_KEY, ConcurrentHashMap.newKeySet());
```

- 이 set은 turn 당 1회 생성되어 iteration 사이에 유지된다(read-before-edit가 여러 iteration에 걸쳐 동작).
- (부수 효과) 이 주입 이전에는 프로덕션에서 `READ_FILES_KEY`가 전혀 주입되지 않아 `EditTool`의
  read-before-edit 가드가 사실상 no-op이었다 — 이 변경으로 비로소 동작한다.

> **신규 도구 주의:** mutable 상태를 `ToolContext`에 넣고 변조하는 도구는 반드시 `SEQUENTIAL`로 선언하거나
> thread-safe 자료구조를 사용해야 한다.

### 5.2 이미 병렬 안전한 프레임워크 인프라

`ToolInput`/`ToolResult`/`ToolContext`(불변), `ArtifactCollector`/`EventEmitter`(CopyOnWriteArrayList),
`DefaultHookExecutionManager`(stateless)는 모두 병렬 실행에 안전하다.

### 5.3 사용자 정의 훅

병렬 경로에서 `executeSingleTool`(= Permission/Pre/PostTool 훅 호출 지점) 전체가 배치의 tool마다
`tool-dispatch-worker-N` 스레드에서 **동시에** 실행된다. 즉 **한 turn 안에서 같은 훅 인스턴스가 동시에 여러 번
호출**된다. 순차 경로에서는 turn 내 훅이 항상 직렬이었으므로, 병렬을 켜면 아래의 새 노출 지점이 생긴다.

프레임워크 plumbing(`DefaultHookRegistry`=`CopyOnWriteArrayList`, `DefaultHookExecutionManager`/
`DefaultHookExecutor`=호출별 로컬 상태 + 불변 context/result, `executionAttributes`=`Map.copyOf` 불변 공유)은
모두 동시 호출에 안전하다. **남은 책임은 훅 작성자에게 있다.**

#### (1) 훅 내부 가변 상태 → 레이스

사용자가 등록한 Pre/PostTool 훅이 내부 가변 상태(turn 카운터, "직전 tool" 추적, 동기화 없는 컬렉션 등)를
가지면 worker 스레드에서 동시 호출되어 레이스가 난다. **병렬 가능 도구에 붙는 훅은 thread-safe해야 한다**
(`AtomicInteger`, `ConcurrentHashMap`, 동기화 등).

#### (2) 부수효과 순서 역전 (thread-safe해도 발생)

dispatcher는 **결과 리스트**를 입력 순서로 재조립하지만, **훅의 부수효과**(감사 로그·메트릭·콘솔 출력·알림)는
**완료 순서**로 interleave된다. thread-safe한 훅이라도 "tool 순서대로 기록된다"는 가정은 깨진다. 순서가 의미를
가지는 훅이라면 (a) 정렬 키(tool index/`iterationCount`)를 같이 기록하거나, (b) 해당 훅이 붙는 tool을
`SEQUENTIAL`로 유지해 직렬 실행을 강제한다.

> **예 (실배포 훅):** CLI `ToolCallDisplayHook`(PreTool)은 공유 `OutputFormatter` 콘솔에 직접 출력한다.
> 이 훅이 등록된 채 병렬을 켜면 tool-call 배너가 뒤섞이거나 순서가 어긋난다(데이터 손상은 아니나 가독성 저하).

#### (3) 인터랙티브 ASK 동시 호출

PreTool/PermissionRequest 훅이 `Decision.ASK`를 반환하면 `AskPromptHandler.resolve()`로 승격되며, 인터랙티브
핸들러는 사용자 입력으로 블로킹될 수 있다. 두 tool이 동시에 ASK를 띄우면 같은 stdin에서 프롬프트가 경합한다
(`CONCURRENT_SAFE`=주로 읽기전용이라 드물지만 가능). 인터랙티브 ASK와 병렬을 함께 쓴다면 `AskPromptHandler`가
프롬프트를 직렬화하도록 보장한다.

#### (4) `RewakeService.schedule()` 동시 호출

PostTool 훅의 async rewake는 worker 스레드에서 `RewakeService.schedule(...)`을 동시에 부른다. 기본
`RewakeService.NOOP`은 안전하나, 실제 구현체를 끼우면 thread-safe해야 하고 rewake 스케줄 순서는 비결정적이 된다.

#### (5) bounded `hookExecutor` 주입 주의

기본 `DefaultHookExecutor`는 무제한 cached 풀이라 안전하다. 다만 `DefaultHookExecutor(ExecutorService)`로
**유한 풀**을 주입하면, `executeParallel`이 같은 풀에 `supplyAsync→get→supplyAsync`로 중첩되어 tool 병렬 ×
훅 병렬이 곱해지며 starvation 위험이 커진다. 주입 풀은 넉넉히 잡거나 cached 풀을 유지한다.

---

## 6. 핵심 API

| 타입 | 패키지 | 역할 |
|------|--------|------|
| `ConcurrencyBehavior` | `at.aimon.core.agent.tool` | `SEQUENTIAL`(기본) / `CONCURRENT_SAFE` enum |
| `Tool#getConcurrencyBehavior()` | `at.aimon.core.agent.tool` | 도구별 정책 선언 (default `SEQUENTIAL`) |
| `ToolConcurrencyConfig` | `at.aimon.core.agent.tool` | `enabled`(기본 false) + `maxConcurrency`(전역 풀, 기본 4) + `perBatchMax`(배치당 캡, 기본 = maxConcurrency) 불변 설정 |
| `ParallelToolDispatcher` | `at.aimon.core.agent.tool` | 게이팅·분배·순서 재조립 인터페이스 |
| `DefaultParallelToolDispatcher` | `at.aimon.core.agent.tool` | bounded daemon pool 구현, `AutoCloseable` |

```java
// 설정 생성
ToolConcurrencyConfig.disabled();        // 기본: 병렬 OFF (풀 미생성)
ToolConcurrencyConfig.enabled(8);        // ON, maxConcurrency=8, perBatchMax=8 (= max)
ToolConcurrencyConfig.enabled(8, 2);     // ON, maxConcurrency=8, perBatchMax=2 (배치당 캡)
ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(6).perBatchMax(3).build();

// dispatcher
DefaultParallelToolDispatcher.sequential();                       // 항상 순차
new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(4));
```

> `DefaultParallelToolDispatcher`는 `at.aimon.core.agent.tool.impl`이 아니라 `at.aimon.core.agent.tool`에
> 둔다 — `DefaultToolRegistry`와 동일하게 이 도메인은 `.impl` 하위 패키지를 쓰지 않는다.

---

## 7. 운영 가이드

- **`maxConcurrency` 튜닝**: 기본 4. I/O 바운드(파일·HTTP) 도구가 많으면 4~8 권장. 너무 크게 잡으면
  외부 리소스(파일시스템, 원격 rate limit)에 부담을 줄 수 있다. COOPERATIVE 도구는 단일 파일/HTTP 호출
  중간에는 선점되지 않으므로, 느린 도구 몇 개가 풀 스레드를 점유할 수 있다.
- **`perBatchMax`로 배치 독점 방지**: 여러 대화/턴이 한 executor의 풀을 공유하는 환경(웹 멀티 대화 등)에서
  한 턴의 큰 배치가 풀을 독점하지 않게 하려면 `perBatchMax`를 `maxConcurrency`보다 작게 잡는다(예:
  `enabled(8, 2)`). 단일 에이전트 CLI처럼 동시 턴이 없으면 기본값(= `maxConcurrency`)이면 충분하다(§2.3).
- **풀 생명주기**: 풀은 executor-scoped이며 **lazy + daemon 스레드**다. 비활성(기본)이면 풀을 아예
  만들지 않는다(자원 0). 각 `dispatch()`는 turn 종료 전 모든 task를 join하므로 worker 활동이 turn 밖으로
  새지 않으며, per-turn `InterruptCoordinator`와 풀 생명주기를 결합하지 않는다.
- **명시적 종료**: `DefaultParallelToolDispatcher`는 `AutoCloseable`이다. `close()`는 in-flight task를
  최대 30초 드레인 후 종료하며 멱등이다. daemon 스레드라 JVM 종료를 막지 않으므로 보통 명시 호출은
  불필요하지만, 런타임에 agent를 반복 생성/제거하는 환경이라면 dispatcher `close()`를 agent 제거 경로에
  연결하는 것을 고려한다(현재 자동 배선 없음 — §8).
- **멀티 인스턴스**: 병렬 실행은 단일 turn 내 in-memory 동시성이라 저장소 교체 대상이 아니다. 인스턴스별
  풀 크기는 독립 설정한다.

---

## 8. 트러블슈팅 — "왜 병렬로 안 돌지?"

배치가 순차로 실행된다면 아래 중 하나다(게이트가 미통과한 것):

1. `ToolConcurrencyConfig`가 비활성(기본). → `withToolConcurrencyConfig(enabled(N))` 확인.
2. 배치 `tool_use`가 1개뿐. → 병렬 의미 없음.
3. 배치에 `SEQUENTIAL` 도구가 하나라도 있음(`Edit`/`Write`/`Bash`/`TodoWrite`/MCP 등).
4. 도구가 `THREAD_INTERRUPT`/`EXTERNALLY_TERMINATED`로 선언됨 → 게이트 자동 제외.
5. 도구 이름이 registry에서 resolve되지 않음(LLM 환각/오타) → 보수적으로 순차.
6. dispatcher가 `close()`된 상태 → 순차 폴백.

---

## 9. 제한사항 / 알려진 이슈

- **혼합 배치는 전체 순차**. "안전한 것만 병렬 + 나머지 순차"로 쪼개는 부분 병렬화는 순서 의존 위험 때문에
  의도적으로 제외했다(향후 확장 후보).
- **`THREAD_INTERRUPT`/`EXTERNALLY_TERMINATED` 도구는 병렬 제외**. cross-thread interrupt 의미가 모호해
  초기 범위에서 뺐다.
- **MCP 도구(`McpTool`)는 `SEQUENTIAL` 유지**. MCP 서버 도구는 임의의 부수효과(쓰기 포함)를 가질 수 있어
  프레임워크가 일괄로 안전성을 보장할 수 없다. 특정 MCP 도구가 읽기 전용·멱등임이 확실할 때만 개별적으로
  검토한다.
- **WebFetch 동일 URL 동시 페치**: 캐시가 check-then-act이라 동시 미스 시 중복 요청이 나갈 수 있다. 멱등
  GET이라 정합성 문제는 없고 요청 1회 낭비뿐.
- **풀 close 자동 배선 없음**: daemon 스레드라 행(hang)은 없지만, 런타임 agent 핫리로드가 잦으면 풀 스레드가
  누적될 수 있다. 필요 시 dispatcher `close()`를 agent 제거 경로에 연결한다.
- **(선행 이슈, 본 기능과 무관) SSRF**: `WebFetchTool`의 SSRF 보호는 이제 `SsrfGuardConfig`로 설정 가능하다
  — `SsrfGuard()`(기본, secure) / `SsrfGuard(SsrfGuardConfig.disabled())`(완전 비활성) /
  `SsrfGuardConfig.builder().allowHost("internal.host").build()`(신뢰 호스트 allow-list). 단, DNS-rebinding
  TOCTOU 완화(`SsrfRedirectInterceptor`)를 프로덕션 OkHttpClient에 등록하는 작업은 여전히 별도 과제다.

---

## 10. 관련 문서

- [설계 문서: ConcurrencyBehavior — 도구 병렬 실행](../../design/tool/parallel-execution.md)
- [Tool 개발 가이드](tool-development-guide.md)
- [InterruptBehavior 설계 문서](../../design/agent-execution/interrupt.md) — capability + coordinator 분리 선례
- [SOLID 원칙](../../project/solid-principles.md)
