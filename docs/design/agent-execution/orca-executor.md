# OrcaAgentExecutor — 메인 ReAct 루프

> Status: **IMPLEMENTED**
> 적용 대상: `aimon-core`
> 관련 규칙: [`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md),
> [`.claude/rules/error-handling.md`](../../../.claude/rules/error-handling.md),
> [`.claude/rules/llm-provider.md`](../../../.claude/rules/llm-provider.md)
> 자매 문서: [`subagent/execution.md`](../subagent/execution.md) (서브에이전트 실행)

이 문서는 **메인 루프**(`OrcaAgentExecutor.executeReActLoop`) 만 다룬다. 서브에이전트와 백그라운드
태스크(`TaskTool`, `DefaultSubagentExecutor`, `BackgroundTaskStore`)는 자매 문서 소관이고,
두 루프의 **공용 ReAct 코어 추출**은 양쪽이 공유하는 미해결 과제다.

설계의 출발점은 성숙한 참조 구현(Claude Code CLI 의 `queryLoop`)이었지만, 이식한 것은 메커니즘이
아니라 **개념**이다 — 참조는 단일 프로세스 인터랙티브 CLI + 제너레이터 기반 async 스트림이라
실행 모델 자체가 다르다.

---

## 1. 출발점 — 루프는 이미 성숙했다

이 개선의 전제는 "`OrcaAgentExecutor` 에 없는 것을 채운다" 가 아니었다. 스트리밍, 인터럽트, 예산,
자동 압축, 병렬 툴, 트레이싱, 훅, 턴 중 주입, 스킬 suspend/resume 이 **이미 프로덕션에 연결되어
동작**하고 있었다. 그래서 참조 구현에서 "명백해 보이는" 개선 다수는 재제안 대상이 아니다.

| 이미 있던 능력 | 위치 |
|----------------|------|
| 토큰 단위 스트리밍 | `agent.stream.*`, `StreamingEventSink`, `ChunkAggregator` |
| 인터럽트/취소 + 원자적 suspend 롤백 | `CancellationSignal`, `InterruptCoordinator`, `InterruptBehavior` |
| 실행 예산(벽시계/토큰/iteration) | `BudgetTracker`, `ExecutionBudget`, `BudgetDecision` |
| 자동 압축 + 마이크로압축 + 실패 서킷브레이커 | `DefaultCompactionGuard`, `TimeBasedMicrocompact`, `ModelContextWindowRegistry` |
| 병렬 툴 실행 | `ParallelToolDispatcher`, `ConcurrencyBehavior` 게이트 |
| 트레이싱 스팬 (turn → iteration → llm/tool) | `TracePayloadPolicy` |
| 훅 체인 | OnStart/OnStop/PreTool/PostTool/Permission* |
| 턴 중 입력 주입 | `MessageQueueManager`, `injectQueuedMessages` |
| 스킬 preflight + suspend/resume | `SkillPreflightScanner`, `CompletionReason.SUSPENDED` |
| 지수 백오프 + 지터 재시도 | `LlmRetryPolicy`, `LlmCallGateway` |

실제 결함은 미묘했고 네 갈래에 몰려 있었다 — **정확성**(잘림), **연결 누락**(만들어 놓고 배선하지
않은 컴포넌트), **관측성**(비용·압축 경계), **컨텍스트 조립 품질**.

### 1.1 루프의 뼈대

```
execute(runtime, request)
  └─ executeReActLoop(ExecutionScope)
       while (iterationCount < maxIterations):
         ├─ cancellationSignal 체크        → handleInterrupted (원자적 롤백)
         ├─ budgetTracker.check()          → STOP → handleBudgetStop
         │                                 → SHOULD_COMPACT → 선제 압축 (§4)
         ├─ compactionGuard.maybeCompact() → BLOCK/COMPACT/WARN/NONE
         ├─ invokeGateway()                → LlmResponse (retry/fallback/PTL 경유)
         ├─ accumulate tokens + cost
         ├─ skillPreflightScanner.scan     → handleSuspended
         ├─ if (!response.hasToolUses())   → 잘림 판정 (§2) 또는 최종 답변
         └─ else executeToolUses → addMessage(assistant + toolResults)
                                → stalled 가드 (§5) → injectQueuedMessages
       handleMaxIterations()               // 예외가 아니라 정상 반환 (§8)
```

**정상 종료 판정은 `!response.hasToolUses()`** 다 — 이번 응답에 tool_use 가 없으면 최종 답변.
참조 구현도 같은 원리이며, 둘 다 `stop_reason` 을 종료 판정에 쓰지 않는다. 그 값은 신뢰할 수 없다.

---

## 2. 잘린 응답을 최종 답변으로 오인하지 않는다

가장 심각한 결함이었다. 토큰 상한으로 **잘린 응답**은 tool_use 를 담고 있지 않으므로
`!hasToolUses()` 분기를 타고 **완성된 최종 답변으로 조용히 처리**되었다. 사용자는 잘렸다는 사실을
알 수 없었다.

정보가 없어서가 아니었다 — 스트리밍 경로의 `ChunkAggregator` 는 `finishReason` 을 **이미 캡처**하고
`LlmResponse` 를 조립할 때 버렸고, 비스트리밍 Anthropic 경로는 `MAX_TOKENS` 를 감지해 **경고 로그만**
남겼다. 즉 폐기하던 값을 연결하는 것이 절반이다.

### 2.1 중립 `StopReason`

provider 의 stop_reason 은 모듈 경계를 넘을 수 없다(llm-provider 규칙). core 중립 enum 을 두고
각 provider 가 자기 코드를 여기로 매핑한다.

```java
package at.aimon.core.llm;

public enum StopReason {
    END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE, REFUSAL, UNKNOWN;

    public boolean isTruncated() { return this == MAX_TOKENS; }
}
```

원시 finish 문자열은 텔레메트리용으로 유지한다. 매핑 실패나 부재는 예외가 아니라 `UNKNOWN` 이며,
`UNKNOWN` 은 **기존 동작**(잘림 판정 없음)으로 회귀한다 — provider 가 매핑을 추가하기 전까지
회귀가 없다는 뜻이고, 그래서 provider 별 점진 도입이 가능했다.

`LlmResponse` 와 `LlmStreamChunk` 가 이 값을 나르고 `ChunkAggregator` 가 전달한다.
기존 `of(text, tools, usage)` 시그니처는 보존한다.

### 2.2 복구 정책은 인터페이스, 기본은 보수적

```java
public interface TruncationRecoveryStrategy {
    TruncationDecision onTruncated(TruncationContext ctx);   // 예외를 던지지 않는다
}
// CONTINUE(이어쓰기 후 재루프) | FINALIZE_AS_TRUNCATED(잘림 표기 후 종료)
```

기본은 **`FlaggingTruncationRecoveryStrategy`** 다 — 이어쓰기를 시도하지 않고 부분 답변을
`[System: response truncated at max_tokens]` 마커와 함께 노출한 뒤 `CompletionReason.TRUNCATED`
로 종료한다. 부분 텍스트는 사용자에게 보이되(`isSuccess() == true`) 실행은 성공으로 세지 않는다
(`CompletionReason.isSuccessful() == false`).

**이어쓰기를 기본으로 하지 않은 이유**는 병합이다. continuation 은 결과 병합과 서명 블록 처리에
복잡도가 있고, 잘못 병합하면 구조화 출력이 깨진다. 1단계 목표는 "잘렸음을 정직하게 알리는 것"
이며 그것만으로 조용한 오답이 사라진다 — 회귀 위험이 없는 최소 변경이다. 참조처럼 합성 user
메시지로 이어쓰는 `ContinuingTruncationRecoveryStrategy` 는 opt-in 으로 남는다.

---

## 3. 프롬프트 초과는 하드 실패가 아니다

`DefaultPromptSizeRecoveryStrategy`(가장 오래된 droppable user 메시지 제거 후 1회 재시도)는
**만들어져 있었지만 아무도 주입하지 않았다**. 그 결과 전략은 NoOp 으로 기본화되고 게이트웨이는
`ThrowingPromptTooLongHandler` 라, 프롬프트 초과가 곧 하드 실패였다.

루프는 이미 `invokeGateway` 에서 `LlmPromptTooLongException` 을 잡아 전략에 위임하고 있었으므로
실행부는 손대지 않았다. `OrcaAgentRuntimeFactory` 가 생성하는 모든 런타임에 전략을 배선하는 것이
전부였다 — **"이미 만들어진 것을 연결한다" 가 새 개념 도입보다 먼저**라는 이 문서의 원칙이 가장
선명하게 드러난 자리다.

더 나은 복구(먼저 `CompactionGuard` 로 요약 압축을 시도하고, 실패했을 때만 crude drop-oldest 로
폴백하는 2계층)는 설계로 남겨 두었다. 손실이 적은 쪽을 먼저 쓰는 것이 옳지만, 연결만으로 하드
실패가 사라졌으므로 급하지 않다.

---

## 4. 예산이 압축을 유도한다

`BudgetDecision.SHOULD_COMPACT` 는 로그만 남기는 死분기였다("compaction integration pending").
바로 뒤에서 `CompactionGuard` 가 자체 임계값으로 압축하므로 실질 무해했지만, **예산이 하드 STOP
이전에 선제 압축을 강제할 길이 없었다**.

`ExecutionBudget` 에 soft 차원 `compactionTokenThreshold` 를 추가한다. soft 이므로
`isUnlimited()` 의 하드 3차원에는 들어가지 않는다. `BudgetTracker.check()` 는 **모든 하드 STOP 을
통과한 뒤에만** `SHOULD_COMPACT` 를 반환한다 — STOP 이 우선이고, 멈춰야 할 실행을 압축으로
연장하지 않는다. 루프는 힌트를 받으면 `CompactionGuard.forceCompact()` 로 선제 압축한 뒤 계속한다.
guard 의 서킷브레이커·BLOCK 로직은 그대로 재사용되므로 멱등하다.

---

## 5. 실패가 무한 루프를 만들지 않는다

참조 구현에는 "마지막 메시지가 API 에러면 continuation 을 건너뛰고 즉시 반환" 하는 death-spiral
가드가 있다. 실패 응답에 continuation 을 돌리면 토큰만 쓰면서 영원히 돈다.

aimon-core 는 툴 실패를 예외가 아니라 `ToolResult.error()` 로 반환하므로 **트리거가 다르다**.
그래서 같은 목표를 stalled-iteration 으로 표현한다 — 한 iteration 이 툴 호출을 1개 이상 냈고
**모든** 결과가 error 면 stalled 로 센다. `MAX_CONSECUTIVE_STALLED_ITERATIONS = 3` 연속이면 큐
드레인과 continuation 이전에 `CompletionReason.ERROR` 로 중단한다. 성공한 툴이 하나라도 있거나
최종 답변이 나오면 카운터를 리셋한다.

이것이 포착하는 것은 "모델이 계속 실패하는 툴을 반복 호출" 하는 실제 death-spiral 이다.

---

## 6. 컨텍스트 조립 — `ContextAssembler`

환경 블록은 작업 디렉터리·플랫폼·OS 3줄이 전부였고, 사용자 컨텍스트는 fresh 대화에 **1회만**
주입되고 이후 갱신되지 않았다. 참조는 매 API 호출마다 git 상태와 날짜를 재주입하고, 턴 사이
attachment 로 런타임 컨텍스트를 흘린다.

```java
public interface ContextAssembler {
    List<ContextBlock> assemble(ContextAssemblyRequest req);   // 예외를 던지지 않는다
}
// ContextBlock: {kind: SYSTEM | USER_PREPEND | ATTACHMENT, key, body, cacheable}
```

provider 는 셋 다 무상태 no-op 기본이다.

| provider | 내용 |
|----------|------|
| `EnvironmentContextProvider` | cwd/platform/OS — 실행기 내장 세그먼트와 중복되므로 **기본 미배선** |
| `GitStatusContextProvider` | **VFS 의 `.git/HEAD` 경유** 브랜치 / detached 단축 SHA. 미가용 시 블록 생략 |
| `DirectorySummaryContextProvider` | 작업 디렉터리 요약 (상한 절단) |

`DefaultContextAssembler` 는 provider 를 순서대로 조립하되 **실패한 provider 와 null 블록은
skip** 한다. 컨텍스트 조립이 턴을 죽이면 안 된다.

**배선의 핵심 결정은 kind 별 라우팅**이다. SYSTEM 블록은 시스템 프롬프트 파트로 folding 되어
(`cacheable` → STATIC) 프롬프트 캐시 친화적 위치에 놓이고, 갱신형 블록(USER_PREPEND / ATTACHMENT)
은 `SystemReminderFormatter.wrapMany` 로 감싼 **단일 합성 user `<system-reminder>` 메시지**로
분리된다. 정적 프롬프트와 동적 꼬리를 가르는 참조의 dynamic boundary 개념에 대응한다.

기본값은 `ContextAssembler.NOOP` 다. 배선 전에는 블록이 항상 비어 프롬프트 shape 과
`concatenated()` 의 bit-equal 불변식이 그대로 유지된다.

git 조회를 **프로세스 실행이 아니라 VFS 경유**로 한정한 것은 의도다. 그래서 얻을 수 있는 것은
`.git/HEAD` 가 주는 브랜치와 detached SHA 뿐이고, 미커밋 변경 같은 풍부한 상태는 샌드박스 기반
provider 의 몫으로 남겼다 — 실행기가 임의 프로세스를 띄우기 시작하면 멀티 인스턴스에서
"작업공간" 의 정의가 노드마다 달라진다.

---

## 7. 비용 추적 — `CostEstimator`

`TokenUsage` 는 토큰 수만 담는다. 여기에 가격을 얹지 않고 **비용을 계산하는 책임을 분리**한다(SRP).

```java
public interface CostEstimator  { Money estimate(String model, TokenUsage usage); }
public interface ModelPriceTable { Optional<ModelPrice> priceOf(String model); }
```

기본 구현은 `TablePricedCostEstimator` + `InMemoryModelPriceTable`(설정 주입) 이며, 미등록 모델은
`Money.ZERO` + 경고 로그다 — 가격표에 없다는 이유로 실행을 실패시키지 않는다.

루프가 iteration 마다 토큰을 누적하는 자리에서 비용도 함께 누적하고, `OrcaAgentExecutionResult`
가 `getCostSummary()`(모델별 토큰·비용)로 노출한다. `ExecutionBudget` 에는 opt-in USD 축
`maxCostUsd` 를 추가해 `BudgetTracker` 가 토큰·벽시계와 동일하게 STOP 을 판정한다
(`CompletionReason.COST_BUDGET_EXCEEDED`). 기본값 `CostEstimator.NOOP` 은 zero-cost 이므로
미배선 시 회귀가 없다.

멀티 인스턴스 관점에서 새로 공유해야 할 상태는 없다 — 가격표는 노드 간 동일 설정을 주입하는
read-only 구성이고, 비용 누적은 실행 결과에 귀속되어 세션 스토어가 이미 분산 처리한다.

---

## 8. 루프 사유와 예외 제거

종료 사유는 `CompletionReason` 으로 잘 모델링되어 있었지만 **왜 다시 루프를 도는지**는 어디에도
남지 않았다. 참조는 `Continue`/`Terminal` 태그드 유니온으로 모든 진입·종료를 자기설명적으로 만든다.
여기서는 sealed 계층을 새로 세우는 대신 기존 `CompletionReason` 에 얇은 값 타입 하나를 더한다.

```java
package at.aimon.core.agent.loop;   // impl 밖 중립 패키지

enum LoopTransitionReason { NEXT_ITERATION, QUEUED_INPUT, BUDGET_COMPACT }
final class LoopTransition { /* reason, iteration, Optional<note> */ }
```

**실제 재진입 사이트가 존재하는 사유만** enum 에 담는다. `TRUNCATION_CONTINUE` 와
`PROMPT_OVERFLOW_RETRY` 는 해당 기능이 배선되는 시점에 추가할 예약 어휘로 문서에만 남긴다 —
없는 경로를 가리키는 값은 관측을 거짓말로 만든다.

iteration 2회차부터 사유를 도출해 ITERATION 스팬에 `loop.transition*` 속성을 붙인다. 두 사유가
겹치면 `BUDGET_COMPACT` 가 우선하고 큐 drain 은 note 로 보존한다. **관측 전용**이며 제어흐름에
관여하지 않는다 — 스팬 부착 실패는 no-op 스팬으로 흡수된다.

패키지가 `impl` 밖인 이유는 둘이다. ArchUnit 이 `impl` 외부 → `impl` 직접 import 를 막으므로 향후
공용 ReAct 코어나 다른 실행기가 사유를 부착하려면 중립 패키지여야 하고, 값 타입은 도메인 어휘이지
특정 구현의 내부가 아니다.

### 8.1 iteration 상한은 예외가 아니다

`MaxIterationsExceededException` 을 **던지는** 대신 `handleMaxIterations()` 가
`handleBudgetStop` 과 동형으로 metadata 를 빌드하고 OnStop 훅을 돌린 뒤
`CompletionReason.MAX_ITERATIONS` 로 정상 반환한다. 예외는 진짜 오류에만 쓴다.

이 변경은 정합성 버그도 함께 닫았다 — 이전에는 이벤트만 `MAX_ITERATIONS` 를 싣고 결과의
`completionReason` 은 `ERROR` 였다.

범위는 **메인 루프 한정**이다. `MaxIterationsExceededException` 클래스 자체는 서브에이전트 루프
(`DefaultSubagentExecutor`)가 아직 쓰므로 존치한다.

---

## 9. 압축 경계 이벤트

`CompactBoundary` 는 압축이 실제로 일어나는데도 placeholder 로 남아 있었다. 루프의 `COMPACT`
분기에서 진짜로 emit 하도록 배선한다.

guard 가 메모리를 **제자리 재작성**하므로 게이트 진입 직전에 크기를 스냅샷해 두고 재작성 후 크기와
함께 emit 한다. **emit 위치는 `IterationStarted` 직전**이다 — 그래야 이벤트 순서가 "압축이 이
iteration 의 LLM 호출 직전에 일어났다" 는 시간 관계를 그대로 반영한다.

`strategyName` 은 상수 `"summarization"` 이다. 압축 서브시스템이 항상 L3 요약을 수행하고,
trigger(AUTO/MANUAL) 차원은 이미 `BUDGET_COMPACT` 전이 태그로 관측되므로 여기서 중복하지 않는다.
빌더 계약(`messagesAfter ≤ messagesBefore`)은 emit 헬퍼에서 clamp 로 방어한다 — guard 가 크기를
늘리는 일은 없지만 관측 코드가 계약 위반 예외를 던져 실행을 깨뜨리게 두지 않는다. 리스너가 없으면
단락되어 zero-cost 다.

---

## 10. 재시도와 모델 fallback

네 조각이 순서대로 닫혔다.

**(a) 게이트웨이가 `Retry-After` 를 존중한다.** `LlmCallGateway` 의 세 retry 루프(plain /
parts-aware `sendMessage` / `sendMessageStreaming`)가 백오프 공식만 쓰던 것을
`resolveRetryDelay(ex, attempt)` 한 경로로 통일했다. 서버가 실은 힌트를 우선하고, 없으면 기존
`computeDelay(attempt, random)` 로 폴백한다. 힌트는 `maxRetryAfter`(기본 60초)로 **clamp** 한다 —
hostile 하거나 잘못 설정된 provider 가 거대한 `Retry-After` 로 worker 스레드를 무한 점유하는 것을
막는다.

**(b) Anthropic 예외 매퍼.** SDK 오류를 flat `AnthropicException` 으로 감싸던 탓에
`LlmRetryPolicy.isRetryable` 이 알아보지 못해 **retry 와 fallback 이 사실상 no-op** 이었다.
OpenAI 매퍼를 미러링한 `AnthropicExceptionMapper` 를 두고 중립 taxonomy 로 매핑한다 — 429 →
`LlmRateLimitedException`(헤더 `Retry-After` 를 delta-seconds 와 RFC1123 양쪽으로 파싱),
5xx 와 **529**(Anthropic 의 overloaded 는 statusCode 529 로 도착한다) → `LlmOverloadedException`,
context-length 시그널 → `LlmPromptTooLongException`, 401/403 → `LlmAuthException`, 나머지는
모듈 예외로 fall-through. 매퍼는 모듈 내부에 머물고 SDK 타입을 밖으로 노출하지 않는다.

**(c) 연속 과부하 카운터.** 종전 게이트웨이는 activating 예외가 **처음** 오면 즉시 모델을 교체했다 —
참조의 "연속 N회 후 교체" 와 어긋난다. `LlmFallbackPolicy` 에 `consecutiveFailureThreshold`
(기본 **1**)를 두고, 게이트웨이의 세 루프가 로컬 카운터를 든다. activating 이면 증가, 아니면 0으로
리셋. threshold 에 도달했거나 **최후수단**(동일 모델 재시도 예산 소진)일 때 다음 모델로 교체하고
카운터를 리셋한다. 기본값 1은 종전 동작과 bit-compatible 이라 기존 fallback 테스트가 회귀하지 않는다.

**(d) 프로덕션 배선.** 프로덕션 경로가 항상 `LlmFallbackPolicy.none()` 이라 모델 교체는 죽어 있었다.
`OrcaAgentExecutorFactory.withFallbackPolicy(...)` 로 opt-in 한다. 미설정이면 여전히 `none()` 이고,
`withGateway(...)` override 가 있으면 그 override 가 우선한다.

참조 대비 남은 격차는 **부분 스트림 tombstone 정리**(교체 시 부분 assistant 메시지 정돈)와
**런타임 모델 해상도**(plan-mode / >200k 승격) 둘이다. 후자는 자매 문서 소관이다.

---

## 11. 스트리밍 툴 중첩

응답을 전량 집계한 **뒤에** 툴을 실행하면 툴 지연이 그대로 사용자 지연이 된다. 참조는 tool_use
블록이 스트리밍되는 즉시 안전한 툴을 디스패치해 그 지연을 다음 토큰 스트림 뒤에 숨긴다.

### 11.1 스트림 신호

`LlmStreamChunk` 에 세 번째 kind `TOOL_USE_READY` 를 추가하고, 생성자 불변식으로 상호배제를
강제한다 — TOOL_USE_READY 는 `toolUse` 를 요구하고 `textDelta` 를 금지, TEXT_DELTA/STREAM_END 는
`toolUse` 를 금지한다.

`ChunkAggregator` 에는 **비-변조 읽기** `finalizeToolCall(int)` 를 추가한다. 슬롯이 비었거나
id·name 이 결측이면 empty 를 돌려주는데, 이 조건은 `toLlmResponse()` 가 그 슬롯을 skip 하는 조건과
**동일**하다. 부분 프래그먼트만 본 상태에서도 절대 throw 하지 않는다.

provider 매퍼는 블록이 **완성되는 즉시** 신호를 **sink 로만** 흘린다(내부 aggregator 로는 흘리지
않아 이중 반영이 없다) — Anthropic 은 `content_block_stop`, OpenAI 는 더 높은 index 의 tool_call 이
시작될 때와 `STREAM_END` 에서 마지막 슬롯을 flush 한다. 두 provider 모두 오름차순 index 가 최종
응답 순서와 일치하므로 orphan future 나 미수확 툴이 생기지 않는다.

### 11.2 스케줄러

`ToolConcurrencyConfig.streamingOverlap`(기본 **false**) 을 켰을 때만 `StreamingToolScheduler` 가
설치된다. 안전성 판정은 새로 만들지 않고 **기존 2단 게이트(`ConcurrencyBehavior` + InterruptBehavior)
를 per-tool 로 재사용**한다.

순서 보존의 핵심은 **prefix-safety poison** 이다 — index < N 이 **모두** eligible 일 때만 N 을 조기
디스패치하고, 첫 ineligible 툴이 자신과 이후 전부를 poison 해 harvest 로 이연시킨다. 결과적으로
"unsafe 이후는 순차" 라는 순서 계약이 지켜진다. 배치당 세마포어가 스트림 스레드에 백프레셔를 준다.

**결정성이 이 설계의 결론이다.** 조기 실행은 **실행만** 스트림에 중첩시키고,
`onStarted`/`onCompleted` 이벤트와 결과 재조립은 harvest 단계에서 **입력 순서**로만 수행한다.
그래서 결과가 non-overlap 경로와 바이트 동일하고, `onCompleted` 순서는 순차 경로와 같아
병렬 dispatch 경로보다 **더** 결정적이다. 얻는 것은 순수히 벽시계뿐이다.

재시도 안전성도 같은 원리로 확보된다 — attempt 마다 리셋하고, 게이트웨이의 `onRetry` 에서
비활성화하며, suspend/에러 시 전부 취소한다. 폐기된 attempt 의 조기 실행은 미수확이므로 부작용이 없다.

3개 knob(streaming · `concurrency.enabled` · `streamingOverlap`) 중 하나라도 꺼지면 스케줄러가
설치되지 않고 provider 의 신호는 무시된다 — 완전 무회귀다. `DefaultSubagentExecutor` 로의 확장은
같은 dispatcher 를 주입하면 SPI 상 가능하지만 별도 과제로 남는다.

---

## 12. 하위 호환 원칙

이 문서의 모든 항목이 지킨 규칙이다.

- 기존 `LlmResponse.of(...)` 시그니처 보존. `stopReason` 부재는 `UNKNOWN` → 기존 판정 그대로.
- 새 SPI(`TruncationRecoveryStrategy` / `ContextAssembler` / `CostEstimator` / fallback policy /
  `streamingOverlap`)는 전부 빌더 opt-in 이고 기본값이 안전 no-op 이다.
- `CompletionReason` 은 값 추가(`TRUNCATED`, `COST_BUDGET_EXCEEDED`)만 한다.
- provider 매핑은 점진 도입이 가능하다 — 매핑하지 않은 provider 는 `UNKNOWN` 으로 남는다.

---

## 13. 남은 것

1. **공용 ReAct 코어 추출** — 메인 루프와 `DefaultSubagentExecutor` 의 중복. 자매 문서와 공유하는
   과제이며, `LoopTransition` 을 `impl` 밖에 둔 것이 그 접합점이다.

   2026-08-31 아키텍처 리뷰가 이 항목에 두 가지를 보탰다. **측정**과 **기각한 대안**이다.

   | | 줄 수 |
   |---|---|
   | `OrcaAgentExecutor.executeReActLoop` | **335** (main 소스 최장 메서드, 2위의 1.3배) |
   | `DefaultSubagentExecutor.runReActLoop` | 131 |
   | `OrcaAgentExecutor` 전체 | 3,238 (main 소스 최대 클래스) |

   리뷰는 처음에 다른 처방을 냈다 — 메인 루프의 **이터레이션 전처리**(인터럽트 검사 → 예산 판정 →
   압축 게이트 → 스팬 오픈 → `LoopTransition` 태깅)를 `IterationGate` 로 뽑는 것. 그 다섯은 실제로
   고정 시퀀스이고 서로 독립적이며 각자 협력자를 이미 갖고 있어서, 뽑으면 메서드가 눈에 띄게 짧아진다.

   **기각한다.** 그 처방은 이 항목이 준비해 둔 접합점을 **가로지른다**. 이 항목의 이음매는
   `LoopTransition`(`at.aimon.core.agent.loop` — 확인함, `impl` 밖)이고 방향은 *두 루프 사이*인데,
   `IterationGate` 는 *메인 루프 안*에 새 이음매를 하나 더 만든다. 한쪽 루프만 그 모양이 되면 공용
   코어를 뽑을 때 맞춰야 할 형태가 하나 늘어난다 — 즉 짧아 보이는 대가로 이 항목이 비싸진다.

   그러므로 335줄은 **여기서 해소될 증상**이지 별도 항목이 아니다. 착수 시 순서는 "메인 루프를
   정리하고 나중에 합친다" 가 아니라 **두 루프의 공통부를 먼저 정하고 그 모양으로 양쪽을 맞춘다** 다.
2. **투기적 side-work** — 툴 배치 후 요약·prefetch 를 비블로킹 시작해 다음 iteration 에서 소비하는
   지연 은닉. 확정 소비자가 없어 구현하지 않고
   [백로그](../backlog/orca-executor-speculative-side-work.md) 로 분리했다.
3. **잘림 이어쓰기(continuation) 승격** — 결과 병합·서명 블록 정책이 정해지면 기본값 후보가 된다(§2.2).
4. **프롬프트 초과 2계층 복구** — `compact → drop-oldest` (§3).
5. **모델 fallback 잔여** — 부분 스트림 tombstone 정리, 런타임 모델 해상도 (§10).
6. **컨텍스트 refresh 트리거** — 지금은 턴 시작 1회다. N iteration 주기와 파일 변경 이벤트 중 무엇을
   쓸지는 프롬프트 캐시 무효화 비용과의 균형 문제로 남아 있다.

---

## 부록 — 참조 구현 파일 맵

개념의 출처를 추적할 때만 쓴다.

- 루프/상태 머신: `query.ts`(`queryLoop`, `State`, `transition`), `query/transitions.js`
- 쿼리 호스트: `QueryEngine.ts`(usage 누적, `total_cost_usd`, abort)
- 모델 스트리밍: `claude.ts`, `withRetry.ts`(백오프/헤더/`FallbackTriggeredError`)
- 컨텍스트: `context.ts`(systemContext/userContext memoize), `utils/api.ts`(dynamic boundary)
- 압축: `autoCompact.ts`(reserve buffer, 서킷브레이커), `microCompact.ts`
- 툴 스케줄: `toolOrchestration.ts`, `StreamingToolExecutor.ts`(도착순 버퍼)
- 예산·비용: `query/tokenBudget.ts`, `cost-tracker.ts`
