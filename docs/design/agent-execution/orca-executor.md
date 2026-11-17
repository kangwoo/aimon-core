# OrcaAgentExecutor — 턴 루프

> Status: **IMPLEMENTED**. 적용 대상: `aimon-core`(게이트웨이의 provider 예외 매핑은 `aimon-llm-anthropic` · `aimon-llm-openai`).
> 남은 일은 [`agent-execution-open-items.md`](../../backlog/agent-execution-open-items.md) 가 정본이다.
> 관련 문서: [`truncation-and-stall.md`](truncation-and-stall.md) (잘린 응답 · 정체 가드),
> [`context-engine.md`](context-engine.md) (뷰와 복구), [`compaction.md`](compaction.md) (압축 판정),
> [`interrupt.md`](interrupt.md) (중단), [`usage-quota.md`](usage-quota.md) (누적 사용량 한도),
> [`../llm/streaming.md`](../llm/streaming.md), [`../tool/parallel-execution.md`](../tool/parallel-execution.md),
> [`../subagent/execution.md`](../subagent/execution.md) (서브에이전트 포크의 루프)
> 관련 규칙: [`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md),
> [`.claude/rules/error-handling.md`](../../../.claude/rules/error-handling.md),
> [`.claude/rules/llm-provider.md`](../../../.claude/rules/llm-provider.md)

이 문서는 턴 루프(`OrcaAgentExecutor.executeReActLoop`)가 iteration 을 어떻게 돌고 언제 끝나는지를 다룬다. 서브에이전트
포크와 백그라운드 태스크의 루프(`DefaultSubagentExecutor`)는 [`subagent/execution.md`](../subagent/execution.md) 소관이다.

참조 구현(Claude Code CLI 의 `queryLoop`)에서는 메커니즘이 아니라 개념만 가져온다. 참조는 단일 프로세스 인터랙티브 CLI 와
제너레이터 기반 스트림이라 실행 모델이 다르기 때문이다.

---

## 1. 루프 뼈대

```text
execute(runtime, request)
  └─ executeReActLoop(scope)
       while (iterationCount < maxIterations):
         ├─ 취소 검사                        → handleInterrupted
         ├─ budgetTracker.check()           → STOP → handleBudgetStop
         ├─ quotaGuard.check()              → 위반 → handleQuotaStop
         │    (SHOULD_COMPACT 였으면 이번 ContextRequest 를 budget-forced 로 표시)
         ├─ contextEngine.prepare(request)  → BLOCK / COMPACT(→ CompactBoundary) / WARN / NONE + 보낼 뷰
         ├─ IterationStarted, ITERATION 스팬(+ LoopTransition)
         ├─ invokeGateway(view)             → LlmResponse (재시도 · fallback, prompt-too-long → contextEngine.recover)
         ├─ 토큰 · 비용 누적, 쿼터 charge
         ├─ TruncatedResponses.isTruncated  → 잘림 판정 (응답마다 한 번)
         ├─ skillPreflightScanner.scan      → handleSuspended
         ├─ 도구 호출 없음                    → 잘렸으면 TRUNCATED, 아니면 COMPLETED
         └─ 도구 호출 있음 → 실행(잘렸으면 전부 거절) → assistant 메시지 + 도구 결과 기록
                          → 정체 가드 → 큐 주입 → 취소 검사
       handleMaxIterations()                → MAX_ITERATIONS 정상 반환 (§3.3)
```

각 단계의 규칙은 그 단계의 정본 문서가 갖는다. 이 문서는 순서와 위치를 정한다.

- **취소 검사**는 iteration 머리와 꼬리 두 곳에 있다. 신호가 선 뒤에는 새 LLM 호출도 도구 실행도 시작하지 않는다 —
  [`interrupt.md`](interrupt.md).
- **예산 → 쿼터 → 압축** 순서다. 예산과 쿼터는 둘 다 LLM 호출 전에 실행을 멈출 수 있고, 멈출 실행에 압축 비용을 쓰지 않는다.
  쿼터의 정의와 판정은 [`usage-quota.md`](usage-quota.md) 에 있다.
- **`ContextEngine.prepare`** 가 이번 iteration 에 보낼 뷰를 정한다. 뷰를 줄이는 주체는 engine 하나다 —
  [`context-engine.md`](context-engine.md). `BLOCK` 은 `ContextWindowExceededException` 으로 실행을 끝낸다. 단 취소 신호가 서
  있으면 그 `BLOCK` 은 인터럽트로 끊긴 PreCompact 가드가 낳은 것이므로 중단으로 끝낸다 —
  [`../hook/hook-cancellation.md`](../hook/hook-cancellation.md).
- **게이트웨이**가 prompt-too-long 을 던지면 `ContextEngine.recover` 로 뷰를 줄여 한 번 다시 보낸다. 복구 규칙은
  [`context-engine.md`](context-engine.md) 에 있다.
- **잘림 판정**은 응답마다 `TruncatedResponses` 로 한 번 하고, 도구 호출 유무에 따라 두 모양으로 갈린다. 잘린 응답의 도구
  호출은 실행하지 않고 전부 거절한다. 규칙은 [`truncation-and-stall.md`](truncation-and-stall.md) 가 정본이다.
- **스킬 preflight** 는 승인이 필요한 스킬 호출을 찾으면 그 응답을 기록하지 않고 실행을 원자적으로 보류(`SUSPENDED`)한다.
  재개가 같은 메모리 상태에서 LLM 호출을 다시 낼 수 있어야 하기 때문이다. 잘린 응답에는 돌리지 않는다 — 잘렸을 수 있는 인자에
  승인을 묻지 않는다.
- **정체 가드**(`StalledIterationGuard`)는 도구 결과를 기록한 뒤, 큐 주입 전에 검사한다. 모든 도구 결과가 오류인 iteration 이
  이어지면 다음 LLM 호출 전에 끝낸다. 실패 응답을 계속 돌려 토큰만 쓰는 반복(참조 구현의 death-spiral)을 끊는 것이 목표다.
  목표는 같지만 **트리거가 다르다.** 참조 구현의 가드는 "마지막 메시지가 API 에러" 일 때 continuation 을 건너뛴다. 이
  프레임워크에서 도구 실패는 예외가 아니라 `ToolResult.error()` 결과로 돌아오므로, 같은 반복은 "iteration 의 결과가 **전부**
  오류" 라는 모양으로 나타나고 가드는 그것을 센다. 정의는 [`truncation-and-stall.md` §4](truncation-and-stall.md#4-정체-가드) 에 있다.
- **큐 주입**은 iteration 끝, 도구 결과를 기록한 뒤 다음 LLM 호출 전에 한다. 이 런타임에 들어온 사용자 입력을 꺼내
  `<system-reminder>` 로 감싼 user 메시지로 붙인다. 큐 관리자가 없으면 아무것도 하지 않는다. 도구 직후 경계에서 drain 하지 않는
  이유는 [`interrupt.md`](interrupt.md), 사용법은
  [`command-queue-guide.md`](../../features/agent-execution/command-queue-guide.md) 에 있다.

## 2. 정상 종료와 `StopReason`

정상 종료 판정은 `!response.hasToolUses()` 다. 이번 응답에 도구 호출이 없으면 최종 답이다. 참조 구현도 같은 원리이며, 둘 다
provider 의 `stop_reason` 을 종료 판정에 쓰지 않는다. 그 값은 종료 판정에 쓸 만큼 신뢰할 수 없다.

`stop_reason` 은 잘림을 알아보는 데만 쓴다. provider 의 값은 모듈 경계를 넘을 수 없으므로(llm-provider 규칙) core 에 중립
enum 을 두고 각 provider 가 자기 코드를 여기로 매핑한다.

```java
package at.aimon.core.llm;

public enum StopReason {
    END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE, REFUSAL, UNKNOWN;

    public boolean isTruncated() { return this == MAX_TOKENS; }
}
```

- `LlmResponse` 와 `LlmStreamChunk` 가 이 값을 나르고, 스트리밍 경로에서는 `ChunkAggregator` 가 전달한다.
- 원시 finish 문자열은 텔레메트리용으로 함께 남긴다.
- 매핑 실패나 부재는 예외가 아니라 `UNKNOWN` 이다. `UNKNOWN` 은 잘림 판정을 하지 않는 동작으로 돌아가므로, 매핑을 아직 넣지
  않은 provider 도 회귀 없이 동작한다.

`isTruncated()` 가 참인 응답의 처리는 [`truncation-and-stall.md`](truncation-and-stall.md) 에 있다.

## 3. 예산 — 하드 STOP · `SHOULD_COMPACT` · `MAX_ITERATIONS`

### 3.1 하드 STOP

`BudgetTracker.check()` 는 iteration 머리에서 `ExecutionBudget` 을 판정한다. 하드 차원(iteration · 토큰 · 벽시계 · 비용) 중
하나라도 소진되면 `STOP` 을 돌려주고, 실행기는 LLM 호출 없이 `handleBudgetStop` 으로 끝낸다. 결과는 차원에 맞는 예산 종료
사유(`TOKEN_BUDGET_EXCEEDED`, `COST_BUDGET_EXCEEDED`, `WALL_CLOCK_EXCEEDED` 등)를 싣는다.

### 3.2 `SHOULD_COMPACT` — 예산이 압축을 요청한다

예산은 하드 STOP 에 닿기 전에 압축을 요청할 수 있다. `ExecutionBudget` 의 soft 차원 `compactionTokenThreshold` 가 그 자리다.
soft 이므로 실행을 멈추지 않고 `isUnlimited()` 에도 들어가지 않는다.

`BudgetTracker.check()` 는 모든 하드 STOP 을 통과한 뒤에만 `SHOULD_COMPACT` 를 돌려준다. STOP 이 우선이고, 멈춰야 할 실행을
압축으로 연장하지 않는다. 실행기는 그 iteration 의 `ContextRequest` 를 budget-forced 로 표시하고, 유효 임계값을 warning 밴드로
내리는 판정은 [`compaction.md`](compaction.md) 가 한다. 그 iteration 의 재진입 사유는 `BUDGET_COMPACT` 다(§6).

### 3.3 iteration 상한은 예외가 아니다

에이전트 메타데이터의 `maxIterations` 에 닿으면 `handleMaxIterations()` 가 `handleBudgetStop` 과 같은 모양으로 메타데이터를
만들고 OnStop 훅을 돌린 뒤 `CompletionReason.MAX_ITERATIONS` 로 정상 반환한다. 상한은 정책 한도이지 오류가 아니고, 예외는
진짜 오류에만 쓴다.

이 규칙은 턴 루프 한정이다. 포크 루프의 상한 처리(grace round 와, 그것이 없을 때의 `MAX_ITERATIONS`)는
[`subagent/execution-policy.md` §8.1](../subagent/execution-policy.md#81-iteration-한도의-grace-round) 에 있다.

## 4. 비용 추적 — `CostEstimator`

`TokenUsage` 는 토큰 수만 담는다. 가격을 거기에 얹지 않고 비용을 계산하는 책임을 분리한다(SRP).

```java
public interface CostEstimator  { Money estimate(String model, TokenUsage usage); }
public interface ModelPriceTable { Optional<ModelPrice> priceOf(String model); }
```

- 기본 구현은 `TablePricedCostEstimator` + `InMemoryModelPriceTable`(설정 주입)이다. 가격표에 없는 모델은 `Money.zeroUsd()` 와
  경고 로그다(모델 이름당 한 번). 가격표에 없다는 이유로 실행을 실패시키지 않는다.
- 루프는 iteration 마다 토큰을 누적하는 자리에서 비용도 누적하고, `OrcaAgentExecutionResult.getCostSummary()` 가 모델별 토큰과
  비용을 노출한다.
- `ExecutionBudget.maxCostUsd` 는 opt-in USD 축이다. `BudgetTracker` 가 토큰 · 벽시계와 같은 방식으로 STOP 을 판정하고 결과는
  `COST_BUDGET_EXCEEDED` 다. 예산 축은 USD 계약이라, USD 가 아닌 견적은 요약에는 쌓이지만 STOP 판정에는 들어가지 않는다.
- 기본값 `CostEstimator.NOOP` 이면 비용 블록 전체를 건너뛰고 `CostSummary` 는 비어 있다.
- 같은 호출의 토큰과 비용을 실행의 쿼터 키에도 charge 한다 — [`usage-quota.md`](usage-quota.md).

멀티 인스턴스에서 새로 공유할 상태는 없다. 가격표는 노드마다 같은 설정을 주입하는 읽기 전용 구성이고, 비용 누적은 실행 결과에
귀속된다.

## 5. 컨텍스트 조립 — `ContextAssembler`

`ContextAssembler` 는 턴 시작마다 git 상태 같은 동적 컨텍스트를 블록으로 조립한다. 대화 첫머리에 한 번 넣은 컨텍스트는 이후
갱신되지 않기 때문이다.

```java
public interface ContextAssembler {
    List<ContextBlock> assemble(ContextAssemblyRequest req);   // 예외를 던지지 않는다
}
// ContextBlock: {kind: SYSTEM | USER_PREPEND | ATTACHMENT, key, body, cacheable}
```

provider 는 셋이고 모두 무상태다.

| provider | 내용 |
|----------|------|
| `EnvironmentContextProvider` | cwd · platform · OS. 실행기 내장 세그먼트와 겹치므로 기본 미배선 |
| `GitStatusContextProvider` | VFS 의 `.git/HEAD` 로 읽은 브랜치 또는 detached 단축 SHA. 읽을 수 없으면 블록을 생략 |
| `DirectorySummaryContextProvider` | 작업 디렉터리 요약(상한에서 절단) |

- `DefaultContextAssembler` 는 provider 를 순서대로 조립하고, 실패한 provider 와 null 블록은 건너뛴다. 실행기도 조립기 예외를
  잡아 빈 목록으로 진행한다. 컨텍스트 조립이 턴을 죽이면 안 된다.
- **kind 별로 라우팅한다.** SYSTEM 블록은 시스템 프롬프트 파트로 접히고(`cacheable` 이면 STATIC) 프롬프트 캐시에 유리한 자리에
  놓인다. 갱신형 블록(USER_PREPEND · ATTACHMENT)은 `SystemReminderFormatter.wrapMany` 로 감싼 단일 합성 user
  `<system-reminder>` 메시지로 분리한다. 정적 프롬프트와 동적 꼬리를 가르는 참조 구현의 dynamic boundary 개념에 대응한다.
- 기본값은 `ContextAssembler.NOOP` 다. 배선하지 않으면 블록이 항상 비어 프롬프트 모양과 `concatenated()` 의 bit-equal 불변식이
  그대로다.
- git 조회는 프로세스 실행이 아니라 VFS 로 한정한다. 그래서 얻는 것은 브랜치와 detached SHA 뿐이고, 미커밋 변경 같은 풍부한
  상태는 실행 환경 기반 provider 의 몫이다. 실행기가 임의 프로세스를 띄우면 멀티 인스턴스에서 "작업공간" 의 정의가 노드마다
  달라진다.

`ContextAssembler` 는 [`context-engine.md`](context-engine.md) 의 engine 밖에 있다. engine 은 뷰를 정하고, 조립기는 뷰에 들어갈
동적 블록을 만든다.

## 6. 루프 재진입 사유 — `LoopTransition`

`LoopTransition` 은 루프가 왜 다시 도는지를 ITERATION 스팬에 남긴다. 종료 사유는 `CompletionReason` 이 말하지만 재진입 사유는
그것만으로 드러나지 않는다.

```java
package at.aimon.core.agent.loop;   // impl 밖 중립 패키지

enum LoopTransitionReason { NEXT_ITERATION, QUEUED_INPUT, BUDGET_COMPACT }
final class LoopTransition { /* reason, iteration, Optional<note> */ }
```

- 실제 재진입 자리가 있는 사유만 enum 에 둔다. 없는 경로를 가리키는 값은 관측을 거짓말로 만든다.
- iteration 2회차부터 사유를 도출해 ITERATION 스팬에 `loop.transition*` 속성을 붙인다. 첫 iteration 은 진입이지 재진입이 아니다.
- 두 사유가 겹치면 `BUDGET_COMPACT` 가 이기고, 큐 drain 은 note 로 남긴다.
- 관측 전용이다. 제어 흐름에 관여하지 않고, 스팬 부착 실패는 no-op 스팬으로 흡수된다.

패키지를 `impl` 밖에 두는 이유는 둘이다. ArchUnit 이 `impl` 밖에서 `impl` 을 직접 import 하는 것을 막으므로 다른 실행기나 두
루프의 공용 코어가 사유를 붙이려면 중립 패키지여야 한다. 그리고 값 타입은 도메인 어휘이지 한 구현의 내부가 아니다.

## 7. 압축 경계 이벤트 — `CompactBoundary`

이름이 같은 타입이 둘이다.

| 타입 | 무엇 | 정본 |
|------|------|------|
| `at.aimon.core.agent.stream.CompactBoundary` | 스트림 이벤트(`AgentExecutionEvent`) — 압축이 일어났음을 리스너에게 알린다 | 이 문서 |
| `at.aimon.core.agent.compact.CompactBoundary` | 대화에 들어가는 경계 마커 문자열과 마커 메시지 팩토리 | [`compaction.md`](compaction.md) |

이 절은 이벤트를 다룬다.

- **emit 위치.** `ContextEngine.prepare` 가 `COMPACT` 를 돌려준 iteration 에서, 그 iteration 의 `IterationStarted` 직전에 낸다.
  이벤트 순서가 "압축이 이 iteration 의 LLM 호출 직전에 일어났다" 는 시간 관계를 그대로 반영하게 하기 위해서다.
- **필드.** `iteration`, `strategyName`, `messagesBefore`, `messagesAfter`.
  - `messagesBefore` / `messagesAfter` 는 engine 이 보고한 뷰 크기(`ContextDecision.getViewSizeBefore()` 와 반환된 뷰의 메시지
    수)다. engine 이 뷰 크기를 보고하지 않으면 engine 을 부르기 직전과 직후의 전사 버퍼 크기를 쓴다. 크기가 뷰 크기라는 의미는
    [`context-engine.md`](context-engine.md) 에 있다.
  - `strategyName` 은 상수 `"summarization"`(`OrcaAgentExecutor.COMPACTION_STRATEGY_NAME`)이다. 이 값은 "압축 경계" 라는 라벨이지
    압축 종류가 아니다. 롤링 engine 의 prune 만으로 끝난 압축에도 같은 값이 실린다. 종류(PRUNE · ROLLING · FULL · FALLBACK)는
    실행 결과에 쌓이는 `CompactionMetadata.kind` 가 싣는다. trigger(자동 · 예산) 차원은 `BUDGET_COMPACT` 재진입 사유로 이미
    관측되므로 여기서 반복하지 않는다.
- **방어.** 빌더 계약(`messagesAfter ≤ messagesBefore`)은 emit 헬퍼에서 clamp 로 지킨다. 관측 코드가 계약 위반 예외를 던져
  실행을 깨뜨리게 두지 않는다. 리스너가 없으면 단락되어 비용이 없다.
- 이벤트를 낸 뒤 뷰가 더는 원문으로 보여 주지 않는 부분을 기록에서 봉인한다 —
  [`../session/session-log.md`](../session/session-log.md).

## 8. 게이트웨이 재시도와 모델 fallback

`LlmCallGateway` 는 세 재시도 루프(plain · parts-aware `sendMessage` · `sendMessageStreaming`)를 갖는다.

- **지연.** 세 루프는 `resolveRetryDelay` 한 경로로 지연을 정한다. 서버가 준 `Retry-After` 를 우선하고 `maxRetryAfter`(기본
  60초)로 자르며, 없으면 백오프 공식을 쓴다. 거대한 `Retry-After` 가 worker 스레드를 무한히 점유하지 못하게 하기 위해서다.
- **중립 예외.** provider 예외는 모듈 안의 매퍼가 중립 예외로 바꾼다. 재시도 · fallback 정책은 중립 예외만 알아보기 때문이다.
  매퍼는 SDK 타입을 모듈 밖으로 내보내지 않는다. Anthropic 매퍼(`AnthropicExceptionMapper`)의 대응은 다음과 같다.

  | provider 신호 | 중립 예외 |
  |---------------|-----------|
  | 429 (`Retry-After` 는 delta-seconds 와 RFC1123 둘 다 파싱) | `LlmRateLimitedException` |
  | 401 / 403 | `LlmAuthException` |
  | 500 · 502 · 503 · 504 · 529(Anthropic 의 overloaded), 또는 본문의 overloaded 신호 | `LlmOverloadedException` |
  | 400 / 422 + 본문의 context-length 신호 | `LlmPromptTooLongException` |
  | 그 밖의 400 / 422 | `LlmInvalidRequestException` |
  | 그 밖 | 모듈 예외(`AnthropicException`) |
  | 스트림 도중의 오류 이벤트(HTTP 200 위) | 본문이 rate limit 이면 `LlmRateLimitedException`, 아니면 `LlmOverloadedException` |

- **연속 실패 카운터.** 세 루프는 각자 로컬 카운터를 든다. activating 예외면 늘리고 아니면 0 으로 되돌린다.
  `LlmFallbackPolicy.consecutiveFailureThreshold` 에 닿았거나 같은 모델의 재시도 예산이 바닥났을 때(최후 수단) 다음 모델로 바꾸고
  카운터를 되돌린다. 기본값 1 은 첫 activating 예외에서 모델을 바꾼다.
- **배선.** fallback 은 `OrcaAgentExecutorFactory.withFallbackPolicy(...)` 로 켜는 opt-in 이고, 켜지 않으면
  `LlmFallbackPolicy.none()` 이다. `withGateway(...)` override 가 있으면 그것이 우선한다.

같은 재시도의 다른 면은 다른 문서가 다룬다. 재시도가 스트림에 남긴 폐기된 시도의 정리는
[`llm/streaming.md` §6](../llm/streaming.md#6-게이트웨이--재시도와-스트리밍의-충돌), 재시도 대기 중 취소는
[`llm/cancellation.md`](../llm/cancellation.md) 에 있다.

## 9. 스트리밍 도구 중첩

응답을 다 모은 뒤에 도구를 실행하면 도구 지연이 그대로 사용자 지연이 된다. 그래서 tool_use 블록이 스트림에서 완성되는 즉시 안전한
도구를 디스패치해 그 지연을 나머지 토큰 스트림 뒤에 숨긴다.

- **켜는 조건.** 스트리밍 · `concurrency.enabled` · `ToolConcurrencyConfig.streamingOverlap`(기본 false) 셋이 모두 켜졌을 때만
  iteration 마다 `StreamingToolScheduler` 를 설치한다. 하나라도 꺼지면 스케줄러가 없고 provider 의 신호는 무시된다. 이때 모든 경로가
  중첩하지 않는 경로와 바이트 단위로 같다.
- **신호.** 스케줄러는 `TOOL_USE_READY` chunk(advisory)를 소비한다. chunk 계약과 매퍼가 그것을 내는 규칙(sink 로만 흘리고
  aggregator 로는 흘리지 않는다, 프로바이더별로 블록이 완성되는 자리)은
  [`llm/streaming.md` §3](../llm/streaming.md#3-llmstreamchunk--네-kind-와-생성-시점-불변식) 에 있다. 스케줄러는 provider 가 내는
  오름차순 index 가 최종 응답의 도구 순서와 같다는 전제에 선다. 그래서 고아 future 나 수확되지 않는 도구가 생기지 않는다.
- **안전성 판정.** 새로 만들지 않는다. 병렬 디스패치의 2단 게이트를 도구별로 재사용한다 —
  [`../tool/parallel-execution.md`](../tool/parallel-execution.md).
- **prefix-safety.** index < N 이 모두 eligible 일 때만 N 을 일찍 디스패치한다. 처음 나온 ineligible 도구가 자기와 그 뒤 전부를
  poison 해 harvest 로 미룬다. 그래서 "unsafe 이후는 순차" 라는 순서 계약이 지켜진다. 배치당 세마포어가 스트림 스레드에
  백프레셔를 준다.
- **결정성.** 조기 실행은 실행만 스트림에 겹친다. `onStarted` / `onCompleted` 이벤트와 결과 재조립은 harvest 단계에서 입력
  순서로만 한다. 그래서 결과가 중첩하지 않는 경로와 바이트 단위로 같고, `onCompleted` 순서는 순차 경로와 같아 병렬 디스패치
  경로보다 더 결정적이다. 얻는 것은 벽시계 시간뿐이다.
- **재시도 · 보류 · 오류.** 시도마다 스케줄러를 되돌리고, 게이트웨이의 `onRetry` 에서 끄며, 보류나 오류면 조기 작업을 전부
  취소한다. 폐기된 시도의 조기 실행은 수확되지 않고, 조기 실행 대상은 부작용이 없는 도구이므로 남는 것이 없다.

## 10. 하위 호환 원칙

실행기에 들어오는 새 SPI 는 다음 규칙을 따른다. 새 개념을 들이기 전에 이미 만든 부품을 연결할 수 있는지 먼저 본다.

- 기존 `LlmResponse.of(...)` 시그니처를 보존한다. `stopReason` 이 없으면 `UNKNOWN` 이고 판정은 예전 그대로다.
- 새 SPI(`ContextAssembler` · `CostEstimator` · fallback 정책 · `streamingOverlap`)는 전부 빌더 opt-in 이고 기본값이 안전한
  no-op 이다.
- `CompletionReason` 은 값을 더하기만 한다.
- provider 매핑은 provider 마다 따로 넣을 수 있다. 매핑하지 않은 provider 는 `UNKNOWN` 으로 남는다.

## 11. 설계 결정

| 쟁점 | 결정 | 기각한 대안 | 이유 |
|------|------|-------------|------|
| 참조 구현에서 가져오는 것 | 개념만 가져온다 | 메커니즘 이식 | 참조는 단일 프로세스 인터랙티브 CLI 와 제너레이터 스트림이라 실행 모델이 다르다 |
| 정상 종료 판정 | `!hasToolUses()` | `stop_reason` 으로 판정 | `stop_reason` 은 종료 판정에 쓸 만큼 신뢰할 수 없다. 잘림을 알아보는 데만 쓴다 |
| 잘린 응답의 처리 형태 | 판정 하나(`TruncatedResponses`)로 잘렸음을 정직하게 알린다 | `TruncationRecoveryStrategy` 같은 전략 인터페이스, 이어쓰기(continuation) | continuation 은 결과 병합과 서명 블록 처리가 복잡하고, 잘못 병합하면 구조화 출력이 깨진다. 잘렸음을 알리는 것만으로 조용한 오답이 사라진다. 끼울 자리(seam)도 두지 않는다 — 만든다면 잘린 호출의 거절과 최종 답의 마커를 대신하게 되며, 결과 병합 · 서명 블록 정책이 먼저 정해져야 한다 |
| 뷰를 줄이는 주체 | `ContextEngine.prepare` / `recover` 하나 | 실행기가 압축 guard 와 복구 전략을 직접 부름 | 뷰를 바꾸는 자리가 하나여야 한다 — [`context-engine.md`](context-engine.md) |
| 예산과 압축 | 하드 STOP 을 모두 통과한 뒤에만 `SHOULD_COMPACT` | 압축 요청이 STOP 보다 앞섬 | 멈춰야 할 실행을 압축으로 연장하지 않는다 |
| iteration 상한 | `MAX_ITERATIONS` 정상 반환 | `MaxIterationsExceededException` 을 던짐 | 상한은 정책 한도이고, 예외는 진짜 오류에만 쓴다 |
| 재진입 사유의 표현 | 기존 `CompletionReason` 옆에 얇은 값 타입 `LoopTransition` | 참조 구현식 `Continue`/`Terminal` sealed 계층 | 종료 사유는 이미 `CompletionReason` 이 모델링한다. 빠진 것은 재진입 사유뿐이다 |
| 재진입 사유의 어휘 | 실제 재진입 자리가 있는 사유만 | `TRUNCATION_CONTINUE` · `PROMPT_OVERFLOW_RETRY` 를 미리 넣음 | 없는 경로를 가리키는 값은 관측을 거짓말로 만든다 |
| 턴 루프의 iteration 전처리 | 두 루프(턴 · 포크)의 공통부를 먼저 정하고 양쪽을 그 모양에 맞춘다 | 전처리(취소 → 예산 → 압축 → 스팬 → `LoopTransition`)를 `IterationGate` 로 뽑음 | `IterationGate` 는 턴 루프 안에 새 이음매를 만들어 두 루프 사이의 접합점(`LoopTransition`)을 가로지른다. 한쪽만 그 모양이 되면 공용 코어가 맞춰야 할 형태가 하나 는다 |
| 비용 계산 | `CostEstimator` 로 분리 | `TokenUsage` 에 가격을 얹음 | SRP. 토큰 수와 가격 정책은 바뀌는 이유가 다르다 |
| 가격표에 없는 모델 | `Money.zeroUsd()` + 경고 | 실행 실패 | 가격표 누락이 실행을 죽이면 안 된다 |
| 동적 컨텍스트의 자리 | SYSTEM 은 시스템 프롬프트, 갱신형은 단일 `<system-reminder>` 메시지 | 모든 블록을 한곳에 | 정적 프롬프트를 캐시에 유리한 자리에 두고 동적 꼬리를 분리한다 |
| git 상태 조회 | VFS 의 `.git/HEAD` | 실행기가 git 프로세스를 띄움 | 프로세스를 띄우면 멀티 인스턴스에서 작업공간의 정의가 노드마다 달라진다 |
| `CompactBoundary.strategyName` | 상수 — "압축 경계" 라벨. 종류는 `CompactionMetadata.kind` | `CompactionMetadata.kind` 에서 파생 | 파생은 이벤트 계약을 바꾼다. 종류는 이미 `CompactionMetadata` 가 싣는다. 남은 일은 backlog 가 정본이다 |
| `Retry-After` | 우선하되 `maxRetryAfter` 로 자른다 | 서버 값을 그대로 따름 | 거대한 `Retry-After` 가 worker 스레드를 무한히 점유한다 |
| 재시도 · fallback 이 알아보는 예외 | 모듈 안 매퍼가 만든 중립 예외 | provider 예외를 그대로 판정 | 정책이 provider 를 모르게 하고, SDK 타입이 모듈 밖으로 나가지 않게 한다 |
| 스트리밍 중첩의 안전성 판정 | 기존 2단 게이트를 도구별로 재사용 | 중첩 전용 판정 | 병렬 디스패치의 게이트가 이미 같은 질문(이 도구를 다른 일과 겹쳐 돌려도 되는가)에 답한다 |
| 스트리밍 중첩의 이벤트 순서 | harvest 에서 입력 순서로 | 완료 순서대로 즉시 | 결과와 이벤트가 중첩하지 않는 경로와 같아야 한다. 중첩이 바꾸는 것은 벽시계 시간뿐이다 |

## 12. 하지 말 것

- `stop_reason` 으로 정상 종료를 판정하지 않는다. 종료는 `!hasToolUses()` 다.
- `SHOULD_COMPACT` 를 하드 STOP 보다 먼저 돌려주지 않는다. 멈춰야 할 실행이 압축으로 연장된다.
- 실행기에서 압축 guard 나 prompt-too-long 복구 전략을 직접 부르지 않는다. 뷰를 줄이는 일은 `ContextEngine` 을 거친다.
- 잘린 응답의 도구 호출을 실행하지 않는다. 받은 인자가 잘린 부분일 수 있다 — [`truncation-and-stall.md`](truncation-and-stall.md).
- 정체 가드를 큐 주입 뒤로 옮기지 않는다. 오류뿐인 결과가 다음 LLM 호출로 흘러간다.
- 정체 가드 자리에서 스레드 인터럽트 플래그를 소비하는 검사를 쓰지 않는다. 플래그 소비는 iteration 꼬리의 취소 검사 한 곳이 맡는다 —
  [`interrupt.md`](interrupt.md).
- 턴 루프에서 iteration 상한을 예외로 던지지 않는다. 그렇다고 `MaxIterationsExceededException` 을 지우지는 않는다 — 포크 루프
  (`DefaultSubagentExecutor`)가 grace round 없이 상한에 닿을 때 여전히 던지고 받아 `MAX_ITERATIONS` 결과로 바꾼다.
- `LoopTransitionReason` 에 배선되지 않은 사유를 넣지 않는다. `LoopTransition` 을 제어 흐름에 쓰지 않는다.
- `CompactBoundary` emit 헬퍼의 clamp 를 지우지 않는다. 관측 코드가 실행을 깨뜨린다.
- `ContextAssembler` 의 실패가 턴을 죽이게 두지 않는다. 실행기에서 git 프로세스를 띄우지 않는다.
- 조기 실행한 도구의 이벤트와 결과를 도착 순서로 내보내지 않는다. 앞선 index 에 ineligible 도구가 있으면 뒤 도구를 일찍
  디스패치하지 않는다.
- provider SDK 타입을 모듈 밖으로 내보내지 않는다.
- 새 SPI 의 기본값을 no-op 이 아닌 것으로 두지 않는다. `CompletionReason` 의 값을 지우거나 뜻을 바꾸지 않는다.

## 부록 — 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다(따로 적은 것 제외).

| 관심사 | 파일 |
|--------|------|
| 턴 루프 · 압축 경계 상수 | [`agent/impl/orca/OrcaAgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java) |
| 이벤트 emit · clamp | [`agent/impl/orca/AgentEventDispatcher.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/AgentEventDispatcher.java) |
| 실행 결과 · 비용 요약 | [`agent/impl/orca/OrcaAgentExecutionResult.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutionResult.java), [`llm/cost/CostSummary.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/cost/CostSummary.java) |
| fallback 배선 | [`agent/impl/orca/OrcaAgentExecutorFactory.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutorFactory.java) |
| 중립 `StopReason` | [`llm/StopReason.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/StopReason.java), [`llm/streaming/ChunkAggregator.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/streaming/ChunkAggregator.java) |
| 잘림 판정 · 정체 가드 | [`agent/budget/TruncatedResponses.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/budget/TruncatedResponses.java), [`agent/budget/StalledIterationGuard.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/budget/StalledIterationGuard.java) |
| 예산 · 종료 사유 | [`agent/budget/ExecutionBudget.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/budget/ExecutionBudget.java), [`agent/budget/BudgetTracker.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/budget/BudgetTracker.java), [`agent/budget/CompletionReason.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/budget/CompletionReason.java) |
| 쿼터 판정 | [`agent/quota/QuotaGuard.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/quota/QuotaGuard.java) |
| context engine 호출 | [`agent/context/ContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextEngine.java), [`agent/context/ContextRequest.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextRequest.java) |
| 비용 | [`llm/cost/CostEstimator.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/cost/CostEstimator.java), [`llm/cost/ModelPriceTable.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/cost/ModelPriceTable.java), [`llm/cost/TablePricedCostEstimator.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/cost/TablePricedCostEstimator.java), [`llm/cost/InMemoryModelPriceTable.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/cost/InMemoryModelPriceTable.java) |
| 컨텍스트 조립 | [`agent/context/ContextAssembler.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextAssembler.java), [`agent/context/ContextBlock.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextBlock.java), [`agent/context/DefaultContextAssembler.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/DefaultContextAssembler.java), [`agent/context/GitStatusContextProvider.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/GitStatusContextProvider.java), [`agent/prompt/SystemReminderFormatter.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/prompt/SystemReminderFormatter.java) |
| 재진입 사유 | [`agent/loop/LoopTransition.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/loop/LoopTransition.java), [`agent/loop/LoopTransitionReason.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/loop/LoopTransitionReason.java) |
| 압축 경계 이벤트 / 마커 | [`agent/stream/CompactBoundary.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/stream/CompactBoundary.java), [`agent/compact/CompactBoundary.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactBoundary.java), [`agent/compact/CompactionMetadata.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionMetadata.java) |
| 게이트웨이 재시도 · fallback | [`llm/invoke/LlmCallGateway.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/invoke/LlmCallGateway.java), [`llm/retry/LlmFallbackPolicy.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/retry/LlmFallbackPolicy.java), [`llm/retry/LlmRetryPolicy.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/retry/LlmRetryPolicy.java) |
| provider 예외 매핑 | [`AnthropicExceptionMapper.java`](../../../modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/AnthropicExceptionMapper.java) (`aimon-llm-anthropic`), [`OpenAIExceptionMapper.java`](../../../modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAIExceptionMapper.java) (`aimon-llm-openai`) |
| 스트리밍 도구 중첩 | [`agent/tool/StreamingToolScheduler.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/StreamingToolScheduler.java), [`agent/tool/ToolConcurrencyConfig.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ToolConcurrencyConfig.java) |
