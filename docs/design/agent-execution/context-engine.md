# Context Engine — LLM 에 무엇을 보낼지 정하는 자리

> Status: **IMPLEMENTED** — 적용 대상 `aimon-core`, `aimon-bootstrap`, `aimon-spring-boot-starter`. 열린 질문은 §12.
> §1 ~ §12 와 부록은 리뷰를 통과한 설계 그대로다(`f09d891`, 승인 뒤 고치지 않았다). 구현이 갈라진 자리와 남은 틈은
> [§13](#13-구현과의-차이).
> 선행 설계: [`../session/session-log.md`](../session/session-log.md) (기록과 뷰의 분리 — 이 문서가 딛고 서는 저장 모델)
> 관련 문서: [`compaction.md`](compaction.md) (요약 생성 부품), [`orca-executor.md`](orca-executor.md) (ReAct 루프),
> [`../memory/peer-memory.md`](../memory/peer-memory.md), [`../../overview/scope-model.md`](../../overview/scope-model.md)

---

## 1. 무엇을 푸는가

### 1.1 한 타입이 두 가지 일을 한다

지금 `TranscriptBuffer` 는 **기록**(세션에서 실제로 오간 메시지)이면서 동시에 **LLM 뷰**(다음 호출에 보낼 메시지)다.
압축과 prompt-too-long 복구는 뷰를 줄이려고 `replaceWith` 로 기록 자체를 고쳐 쓴다.

이 겹침이 장기 대화에서 다섯 가지 누수를 만든다.

| # | 누수 | 원인 |
|---|------|------|
| L1 | **최근 맥락이 매번 뭉개진다** | 전체 compaction 은 방금 본 도구 결과까지 요약 안으로 밀어 넣는다. 뷰를 부분적으로 줄이는 정책이 없다 |
| L2 | **요약이 요약을 요약한다** | 재압축 시 이전 `[[COMPACT_SUMMARY]]` 가 기록 속의 일반 메시지로 다시 요약된다 |
| L3 | **원문이 영구히 사라진다** | 뷰를 줄이는 쓰기가 곧 기록을 지우는 쓰기다 |
| L4 | **압축된 실행은 메모리에 남지 않는다** | 실행 끝 ingest 는 기록 위의 mark 로 델타를 구하는데, 기록이 다시 쓰이면 mark 가 무효가 되어 그 실행 전체를 건너뛴다 |
| L5 | **요약문이 대화로 ingest 된다** | `SESSION_END` 는 세션 종료 시 기록 전체를 넘긴다. 압축된 기록에는 원문 대신 요약문(paraphrase)이 들어 있다 |

L1·L2 는 정책의 문제이고, L3~L5 는 저장 모델의 문제다. 뷰를 줄이는 방법이 기록을 지우는 것뿐인 한 L3~L5 는 정책으로
막을 수 없다 — 기록을 읽는 쪽(메모리, 재조회, rewind)은 언제나 무언가를 잃는다.

### 1.2 이 문서의 답

기록과 뷰를 나눈다.

- **기록**은 append-only 로그다 — [`session-log.md`](../session/session-log.md). 압축은 기록을 건드리지 않는다
- **뷰**는 기록과 **뷰 상태**(어느 구간을 어떤 요약으로 대체했는지)로부터 결정적으로 계산된다
- **`ContextEngine`** 이 뷰를 줄이는 유일한 주체다. 매 LLM 호출 직전에 뷰를 만들고, 압축·복구·`/compact` 는 전부 뷰 상태를
  바꾸는 engine 의 일이다

그러면 L4·L5 는 해결할 필요 없이 사라지고 — 메모리는 기록을 읽는다 — L3 은 "원문은 남는다" 까지 해소된다. 그 원문을
에이전트가 되찾는 길(§6)은 롤링 engine 이 연다. 남는 것은 "뷰를 어떻게 줄이는가" 라는 정책 문제(L1·L2)이고, 그것은 교체
가능한 engine 구현이 된다.

### 1.3 참고한 설계

Nous Research 의 Hermes Agent 가 장기 대화를 다루는 방식을 참고했다 — 머리·꼬리를 원문으로 보존하고 가운데만 요약,
요약의 누적 갱신, LLM 요약 전 무비용 정리, 원문을 버리지 않는 기록. 가져오지 않은 것은 §8 에 있다.

---

## 2. 경계

```
                         ┌──────────── ContextEngine (agent-scoped) ────────────┐
TranscriptBuffer         │                                                      │
  로그 ──────────────────┼─▶ prepare(request) ─▶ ContextView ──▶ LLM 호출        │
  SessionViewState ◀─────┼── (필요하면 뷰 상태를 바꾼다 = 압축)                   │
                         │   recover(request, promptTooLong) ─▶ 더 줄인 뷰       │
                         │   compactNow(request, instructions)  ◀── /compact     │
                         │        │                                             │
                         │        └─ CompactionEngine.summarize(...)  (요약 부품)│
                         └──────────────────────────────────────────────────────┘
```

| 무엇 | 누가 | 수명 |
|------|------|------|
| 로그 | `TranscriptBuffer` 가 들고 레코드에 영속 | session |
| 뷰 상태 (`SessionViewState`) | `TranscriptBuffer` 가 들고 레코드에 영속. 뷰를 **줄이는** 것은 engine 뿐이다. 로그를 자르는 rewind·`/clear` 는 잘린 seq 를 가리키던 뷰 상태를 정리한다([`session-log.md` §6](../session/session-log.md)) | session |
| 뷰 (`ContextView`) | engine 이 매 호출 계산. 영속하지 않는다 | 한 LLM 호출 |
| 판단 규칙 (임계값, 구간 선택, 요약 프롬프트) | engine 구현 | agent |

뷰 상태가 레코드에 있는 것은 **재시작·노드 이동을 넘어 살아남아야 하기** 때문이다. 다른 노드가 세션을 이어받으면 같은
기록과 같은 뷰 상태에서 **같은 뷰**가 나와야 대화가 이어진다(§3.3).

**engine 이 소유하지 않는 것** — 시스템 프롬프트 조립(`SystemPromptRenderer`, `ContextAssembler`, `MemoryContextProvider`).
이것들은 이미 교체 가능한 seam 이다. 둘을 합칠 이유는 하나 — 프롬프트 캐시 경계(CTX-05)를 시스템 프롬프트와 메시지에 걸쳐
놓으려면 둘을 함께 봐야 한다 — 이고, 그 결정은 CTX-05 의 몫이다(§9). 다만 engine 은 시스템 프롬프트의 **크기**를 입력으로
받는다 — 임계값 비교가 그것을 포함하기 때문이다(§5.6).

---

## 3. SPI

### 3.1 인터페이스

```java
public interface ContextEngine {

    /** iteration 경계, LLM 호출 직전. 필요하면 뷰 상태를 바꾸고(압축) 이번 호출에 보낼 뷰를 돌려준다. */
    ContextDecision prepare(ContextRequest request);

    /** 프로바이더가 prompt-too-long 을 돌려준 뒤. 더 줄인 뷰를 주거나, 줄일 수 없으면 empty. */
    Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error);

    /** /compact. 임계값 판정 없이 지금 줄인다. */
    CompactionResult compactNow(ContextRequest request, String instructions);

    /** 압축하지 않는 engine. */
    static ContextEngine passthrough() { ... }
}
```

| 값 | 내용 |
|----|------|
| `ContextRequest` | `TranscriptBuffer`, 시스템 프롬프트, 모델, `HookRegistry`, `Environment`, `ContextCaller(executionId, principal)`, `budgetForced` |
| `ContextDecision` | `ContextView` + 액션(`NONE`/`WARN`/`COMPACT`/`BLOCK`) + `CompactionMetadata`(있으면) |
| `ContextView` | 보낼 `List<Message>`, 추정 토큰(시스템 프롬프트 포함) |

- 액션은 `CompactionDecision.Action` 을 그대로 잇는다 — 이름도 의미도 같다. `BLOCK` 이면 실행기는 지금처럼
  `ContextWindowExceededException` 으로 턴을 끝낸다
- `compactNow` 는 기존 `CompactionResult` 를 돌려준다. 경합 실패(§5.6)를 표현하도록 실패 사유에 값 하나를 더할 뿐, 새
  결과 타입을 만들지 않는다
- 전부 불변 클래스 + 빌더다. 패키지는 `at.aimon.core.agent.context`(기존 `ContextAssembler` 옆)다

### 3.2 호출 지점

LLM 에 메시지를 보내는 곳은 세 루프의 다섯 자리다. 다섯 자리 모두 `transcriptBuffer.getMessages()` 대신
`engine.prepare(...)` 의 뷰를 보낸다.

| 루프 | engine | 이유 |
|------|--------|------|
| 메인 ReAct (`OrcaAgentExecutor`) | runtime 에 배선된 engine | |
| 서브에이전트 포크 (`DefaultSubagentExecutor`) | **주입 가능**, 기본 `passthrough()` | 지금도 실제 `CompactionGuard` 를 받는 공개 생성자가 있고 세션 없는 압축(`executionId` 식별)을 수행할 수 있다. 기본 배선이 `NoOpCompactionGuard` 일 뿐이다. 그 자리를 engine 주입으로 잇는다 |
| 스킬 루프 (`LlmSkillExecutor`) | `passthrough()` | 매 실행 새 스크래치 버퍼를 만든다. 압축할 만큼 길어지지 않는다 |

포크에 engine 을 주입하면 뷰 상태는 그 포크의 버퍼에만 있고 영속하지 않는다. 세션이 없으므로 봉인도 없다.

### 3.3 뷰의 결정성

같은 `(로그, 뷰 상태)` 에서는 언제나 같은 뷰가 나와야 한다.

- **노드 이동** — 다른 노드가 세션을 이어받으면 같은 레코드에서 뷰를 다시 계산한다. 결과가 다르면 모델이 보는 대화가
  노드마다 달라진다
- **프롬프트 캐시** — 뷰의 앞부분이 호출마다 바뀌면 prefix 캐시가 깨진다. 뷰는 뷰 상태가 바뀔 때만 바뀐다

그래서 뷰 계산에는 시계·난수·노드 로컬 상태·메모리 스냅샷이 들어가지 않는다. 요약문처럼 비결정적으로 **만들어지는** 값은
만들어진 순간 뷰 상태에 저장되고, 뷰 계산은 저장된 값을 읽기만 한다.

### 3.4 뷰 상태 연산

뷰 상태의 형태는 [`session-log.md` §4](../session/session-log.md) 가 정한다. engine 이 쓰는 연산은 셋이다.

| 연산 | 뜻 |
|------|----|
| `summarize(fromSeq, toSeq, summary, meta)` | 로그의 `[fromSeq, toSeq)` 를 경계·요약 마커 쌍으로 대체한다. 기존 span 을 덮으면 흡수한다 |
| `drop(fromSeq, toSeq)` | 그 구간을 뷰에서 뺀다 — 요약 없이. prompt-too-long 복구 전용 |
| `elide(seq, placeholder)` | 그 도구 결과 본문을 placeholder 로 대체한다 (L0 prune) |

**불변식** — `summarize` 와 `drop` 의 두 경계는 **합법 절단면**이어야 한다. 그 위치의 메시지가 `TOOL` 이 아니고, 앞의 모든
`tool_use` 가 해소된 곳이다 — `compaction.md` §4.3 의 엔진 검증과 같은 조건이다. 연산이 불변식을 검사하고, 위반이면 거부한다.

셋 다 **로그를 건드리지 않는다.** 뷰 상태가 바뀌면 레코드가 dirty 가 되고 로그 변경과 같은 체크포인트 경로로 저장된다.

뷰 상태가 바뀌어 원문으로 보이지 않게 된 구간은 봉인 대상이 된다. 봉인은 engine 이 아니라 저장 모델의 일이다 — 실행기가
`prepare` 에서 `COMPACT` 를 받으면 같은 스레드에서 버퍼의 봉인을 부른다([`session-log.md` §5.3](../session/session-log.md)).
engine 은 저장소를 모른다.

---

## 4. `DefaultContextEngine` — 지금의 동작

기본 engine 은 오늘의 동작을 뷰 모델로 옮긴 것이다. 모델이 보는 것은 바뀌지 않는다.

| 지금 | `DefaultContextEngine` |
|------|------------------------|
| `DefaultCompactionGuard` 의 판정(blocking → breaker → auto → warning), 세션 락, 사전조건 | 그대로 |
| 전체 compaction = `replaceWith([B, S])` | `summarize(뷰 시작, 끝, S)` — 뷰는 `[B, S]` 만 남고 로그는 그대로 |
| 복구 = `DefaultPromptSizeRecoveryStrategy` 가 **가장 오래된 USER 메시지 하나**(마지막 USER 와 압축 마커 제외)를 뺀 목록으로 `replaceWith` | 그 메시지 하나를 `drop(s, s + 1)` — USER 메시지 앞뒤는 합법 절단면이므로 불변식을 지킨다 |
| `/compact` = 엔진 직접 호출 | `compactNow` — 전체 compaction |
| budget-forced 패스 | `ContextRequest.budgetForced` — 유효 임계값을 warning 밴드로 |

그래서 기본 engine 에서도 L4·L5 가 사라지고, L3 은 원문이 저장되는 데까지 해소된다. **정책을 바꾸지 않고 얻는 것**이 이
분리의 첫 번째 이득이다.

circuit breaker 는 지금처럼 `CompactionFailureStore` 뒤에 있다 — in-memory 기본, 스케일아웃에서는
`SessionRecordCompactionFailureStore` ([`compaction.md` §8](compaction.md)).

v1 쓰기 모드([`session-log.md` §7.3](../session/session-log.md))에서는 뷰 상태를 저장할 수 없으므로 기본 engine 은 기록을
고쳐 쓰는 지금의 동작으로 물러난다.

---

## 5. `RollingContextEngine` — 장기 대화

### 5.1 모양

```
로그:  s0 s1 s2 ............................................ sN
뷰:   [ head ][ B  S ][ 원문 ................ ][ tail ...........]
        원문    요약      (다음 압축 때 흡수)       원문
```

- **head** — 세션의 목적. 세대가 지나도 원문으로 남는다. 봉인되지 않는다
- **span** — head 뒤. 요약 하나로 대체된다. 새 압축은 이전 span 을 흡수해 뒤로 넓어진다. span 구간은 봉인 대상이다
- **tail** — 최근. 원문

### 5.2 head

- **정의** — `floorSeq` 이후 첫 턴의 **첫 번째 `CONVERSATION` USER 메시지까지(포함)**. 그 앞의 `SYNTHETIC` 항목(CTX-06
  user-context, 조립된 `<system-reminder>`)은 head 에 포함되지만 head 의 끝을 정하지 않는다. 출처는 로그 항목의 `origin` 으로
  판별한다([`session-log.md` §3.2](../session/session-log.md)) — 텍스트 태그로 판별하면 mid-turn 사용자 메시지를 오분류한다.
  head 는 USER 메시지로 끝나므로 tool 짝이 끊기지 않는다. `floorSeq` 기준이므로 `/clear` 뒤에도 성립한다
- **상한** — `headTokenRatio`(기본 effective window 의 5%)는 `CONVERSATION` 항목에만 적용한다. 첫 메시지에 거대한 로그를
  붙였다면 그것이 영원히 창을 차지하므로 상한을 넘으면 head 는 비고 span 이 맡는다. `SYNTHETIC` 블록은 조립하는 쪽이 크기를
  제한하므로 상한 계산에서 뺀다
- **v1 에서 온 세션** — 로그의 첫 메시지가 경계 마커면 head 는 비어 있다. 첫 요청은 이미 요약 안에 있다

### 5.3 tail 과 절단면

- **예산** — 뒤에서부터 `tailTokenRatio`(기본 20%)를 채울 때까지. 도구 결과 하나가 수만 토큰일 수 있어 개수가 아니라
  토큰으로 잰다
- **절단면** — §3.4 의 합법 절단면. **assistant 메시지 시작점도 합법**이다 — 한 턴 안에서 iteration 이 수십 번 도는 실행은
  USER 메시지가 턴 시작의 하나뿐이라, USER 경계만 허용하면 tail 이 빈다
- **선호** — 예산 경계 ±10% 안에 USER 경계가 있으면 그쪽으로 스냅한다
- **span 뒤** — tail 은 언제나 span 뒤에서 시작한다. 뷰 상태에서 span 은 하나이고 경계·요약 마커는 뷰가 만드는 것이라 이
  불변식은 구조로 성립한다. **예외**는 v1 에서 옮겨 온 세션이다 — 그 로그에는 옛 마커가 평범한 메시지로 남아 있다. 그
  메시지들은 새 span 에 흡수될 때 일반 메시지로 요약된다

### 5.4 요약 갱신

span 을 넓힐 때 요약 입력은 **이전 요약 + 새로 흡수되는 원문**이다. 이전 요약은 뷰 상태에 저장된 값이므로 마커를 파싱하지
않는다. 새로 흡수되는 원문은 hot 로그에 있다 — 아직 span 밖이었으므로 봉인되지 않았다.

- 지시는 "새로 요약하라" 가 아니라 **"이전 요약을 갱신하라"** 다
- 섹션은 [`compaction.md` §5](compaction.md) 의 9개에 `Key decisions and constraints` 를 더한다. `Primary Request and
  Intent`, `Key decisions and constraints`, `Pending Tasks` 는 **누적 섹션**이다 — 항목은 명시적으로 완료·철회되지 않은 한
  지우지 않는다
- 목표 길이 `summaryTokenRatio`(기본 8%)를 지시한다. 넘었다고 재작성 호출을 하지 않는다 — 요약 호출은 압축당 한 번이다
- 사용자 지침 격리 규칙은 `compaction.md` §5 그대로다
- **요약 모델** — `summaryModel` 로 요약 전용 모델을 지정할 수 있다. runtime 의 `LlmClient` 는 하나이므로 같은 프로바이더의
  모델만 된다

### 5.5 L0 prune

LLM 요약 전에 무비용 정리를 먼저 시도한다.

- 구간 선택(§5.3) **다음**에 한다
- **대상** — head 밖, span 밖, 새 tail 밖의 원문 구간(이전 tail 과 그 뒤에 쌓인 메시지)에서 본문이 `pruneMinTokens`(기본
  500) 이상인 `TOOL` 메시지. **head 는 제외한다** — 세션의 목적이고 prefix 캐시의 핵심이다
- placeholder 는 `"[tool result elided: seq=<seq>]"` 로 결정적이다. 원문은 로그에 있고 §6 의 도구가 seq 로 되찾는다
- prune 만으로 유효 임계값 아래로 내려가면 요약하지 않는다. 액션은 `COMPACT`, metadata 의 `kind = PRUNE` 이다

로그를 건드리지 않으므로 prune 이 메모리 ingest 에 영향을 주지 않는다.

### 5.6 언제 — 임계값과 후퇴

판정 순서와 사전조건은 `DefaultContextEngine` 과 같다. 모든 크기는 **시스템 프롬프트를 포함해** 잰다 — 지금
`DefaultCompactionGuard` 가 `tokenEstimator.estimate(systemPrompt, messages)` 로 재는 것과 같다.

```
rollingAuto = min(autoCompactRatio × effective, limits.autoCompactThreshold)
warning     = rollingAuto − limits.warningBuffer
blocking    = limits.blockingLimit                        (engine 이 바꾸지 않는다)
threshold   = budgetForced ? warning : rollingAuto        (유효 임계값)
```

`autoCompactRatio` 기본 0.6 — 일찍·자주·작게 압축해야 tail 예산이 의미를 갖는다. warning 을 rollingAuto 에서 다시 파생하는
것은 순서를 지키기 위해서다 — 200K 모델에서 기본 auto 는 167K, warning 은 147K 인데, 롤링 auto 만 108K 로 내리면
budget-forced 패스가 일반 AUTO 보다 늦게 발동한다.

**후퇴** — 구간은 요약 **전에** 토큰 추정으로 정한다. 예상 크기 = `system + head + summaryTokenRatio × effective + 원문 나머지`.

| 단계 | span 끝 | 채택 조건 |
|------|---------|-----------|
| 0 | tail 예산 경계 | 예상 크기 < `threshold` |
| 1 | tail 예산 절반 경계 | 예상 크기 < `threshold` |
| 2 | 마지막 합법 절단면 (tail 0, head 유지) | 예상 크기 < `threshold` |
| — | 압축하지 않고 `WARN` | 2 의 예상 크기가 `threshold` 이상이고 현재 크기가 blocking 미만 |
| 3 | 마지막 합법 절단면, **head 까지** span 에 포함 | 현재 크기가 blocking 이상 |

- **채택 조건이 유효 임계값인 이유** — auto 로 비교하면 budget-forced 패스에서 예상 크기가 warning 과 auto 사이인 구간이
  채택되고, 다음 iteration 의 `forceCompact` 가 다시 압축한다. `BudgetTracker` 는 한번 `SHOULD_COMPACT` 가 되면 매
  iteration 그 값을 돌려주므로, 매 iteration 요약을 요약하게 된다
- **`WARN` 행이 필요한 이유** — 최선의 결과로도 임계값 아래로 내려가지 못하는 대화를 압축하면 다음 iteration 에 또
  압축한다. 성공이므로 breaker 도 멈추지 않는다. 원인은 대개 시스템 프롬프트, head, tail 의 거대한 메시지다.
  `kind = FALLBACK` 으로 관측에 남긴다
- **모델이 롤링을 감당하는지는 런타임에 본다** — 롤링이 의미를 가지려면 압축 직후의 최소 크기가 임계값 아래여야 한다.

  ```
  minAfter = system + head + summaryTokenRatio × effective + minTailRatio × effective   (minTailRatio 기본 0.05)
  롤링 가능 ⇔ minAfter < warning  (warning 은 0 이상으로 clamp)
  ```

  시스템 프롬프트 크기는 빌드 시점에 알 수 없으므로 판정 시점에 본다. 불가능하면 그 모델·그 시스템 프롬프트 조합에서는
  **`DefaultContextEngine` 의 동작으로 물러나고** 한 번 WARN 을 남긴다. 128K 창(effective 108K)에서 기본값이면 rollingAuto
  64.8K, warning 44.8K 라서 시스템 프롬프트 30K 인 에이전트는 이 검사에서 걸린다. 32K 창에서는 warning 이 음수가 된다. 작은
  창에서 롤링을 강행하면 매 iteration `WARN` 에 머물다 blocking 에서 head 까지 삼키는, 기본 engine 보다 나쁜 결과가 나온다

**`/compact`** — `compactNow` 는 임계값 판정만 건너뛰고 구간 선택은 따른다(`WARN` 행 없이 — 사용자가 요청했다).
`compaction.md` §7 의 근거("명령을 쳤다는 사실이 판정을 대체한다")는 "언제" 에 대한 것이다. 세션 락은 `tryLock` 이다 —
다른 압축이 진행 중이면 조용히 아무것도 안 하는 대신 경합 실패를 돌려주고, 명령은 그것을 사용자에게 보여 준다.

### 5.7 복원 훅

`RecentFilesRestoreHook`·`InvokedSkillsRestoreHook` 는 압축 뒤 최근 읽은 파일과 스킬 목록을 USER 메시지로 로그에 append 한다.
롤링에서는 **기본 등록하지 않는다.**

- 롤링은 tail 을 원문으로 보존하므로 "요약이 잃은 파일" 을 다시 붙일 이유가 약하다
- 롤링은 자주 압축하므로, 압축마다 파일 원문이 새 메시지로 쌓이고 그것이 tail 에 남아 다음 압축을 앞당긴다

등록한다면 훅은 `origin = SYNTHETIC` 으로 append 한다. 그래야 메모리 ingest 와 되찾기가 재첨부된 파일을 대화로 취급하지
않는다.

---

## 6. 되찾기 — `SessionHistoryTool`

뷰에서 빠진 원문을 에이전트가 되찾는 도구다. `at.aimon.core.tools.session`(신규)에 둔다. 롤링 engine 을 배선할 때 등록한다.

| 입력 | 뜻 |
|------|----|
| `seq` | 그 메시지 원문 (placeholder 가 가리키는 값) |
| `query` | 부분 문자열 검색 (대소문자 무시). `seq` 와 둘 중 하나는 필수 |
| `limit` (기본 5) | 결과 수 |

- **범위는 현재 세션의 `CONVERSATION` 항목뿐이다.** `SessionLogReader` 로 봉인된 세그먼트까지 읽는다. 세션을 넘는 회상은
  `PeerMemory` 의 일이다(§8)
- **`/clear` 이전은 보이지 않는다** — reader 는 manifest 만 따른다
- **스캔 상한** — 인덱스가 없으므로 `query` 는 선형 스캔이다. 최근부터 `maxScanTokens`(기본 1M)까지만 읽고, 넘으면 "더 오래된
  기록은 검색하지 않았다" 를 결과에 담는다
- 결과는 매치된 메시지 주변 ±2 개, 도구 결과는 `maxResultChars` 로 자른다
- 돌려주는 것은 모두 **예전에 이미 LLM 에 보낸** 메시지다. 새로운 노출 경로가 아니다
- 도구 규칙(예외 금지·무상태·불변 I/O)은 [`tool-development-guide.md`](../../features/tool/tool-development-guide.md) 를 따른다

기본 engine 에는 등록하지 않는다. 기본 engine 에는 placeholder 가 없고, 전체 compaction 뒤 원문을 되찾게 할지는 별도 결정이다.

---

## 7. 메모리

engine 은 메모리를 부르지 않는다. 메모리는 기록을 읽는다.

- **`EXECUTION_END`** — 실행 끝 ingest 는 로그 위의 mark(seq)로 델타를 구한다. 압축은 로그를 바꾸지 않으므로 mark 는 무효가
  되지 않고, 압축된 실행도 원문 그대로 ingest 된다(L4). 요약문은 로그에 없으므로 ingest 될 수 없다
- **`SESSION_END`** — 호출자는 `SessionLogReader` 로 로그를 넘긴다(L5)
- **`SYNTHETIC` 제외** — 두 모드 모두 `origin = SYNTHETIC` 항목을 넘기지 않는다
- **청크** — 지금은 압축이 ingest 페이로드를 창 크기 이하로 묶어 준다. 새 모델에서는 긴 실행의 델타나 세션 전체 로그가 창의
  몇 배일 수 있고, `LlmDeriver` 는 받은 메시지를 한 LLM 호출에 싣는다. 그래서 **ingest 는 `maxIngestTokens` 단위로 나누어
  보낸다.** 청크 경계는 §3.4 의 합법 절단면이다 — 짝 잃은 `tool_result` 를 받으면 deriver 의 LLM 호출을 프로바이더가 거부한다.
  `SYNTHETIC` 항목을 빼는 것은 짝을 가르지 않는다(런타임 주입은 USER 또는 짝 없는 assistant 메시지다). `IngestingExecutionMemorySink`
  와 `SESSION_END` 호출자의 계약으로 두고, `SessionLogReader` 의 페이지 읽기가 같은 경계로 자른다
- **압축 직전 flush** — 필요 없다. 지키려던 원문이 사라지지 않는다

---

## 8. 설계 결정

### 8.1 결정 표

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 뷰를 줄이는 방법 | 로그는 append-only, 뷰 상태로 대체 | **기록을 고쳐 쓰는 모델 위에 정책 추가** — 원문 보관소와 그 키·크래시 처리·GC, ingest mark 와 rewind point 의 재매핑, 압축 후 flush seam 이 모두 필요해진다. 전부 "기록을 고쳐 쓴다" 는 전제에서 나오는 비용이다 |
| 판정·복구·`/compact` 의 자리 | `ContextEngine` 하나 | **각자 버퍼를 고침** — 지금은 가드, 복구 전략, 명령, 게이트웨이의 `PromptTooLongHandler` 넷이 뷰를 바꿀 수 있다. 주체가 여럿이면 뷰 상태의 일관성을 누구도 보장하지 않는다 |
| 정책 선택 | engine 구현 교체 | **가드에 정책 값 객체 주입** — 판정·구간 선택·요약 방식이 함께 바뀌는데 가드는 "언제" 만 아는 자리다 |
| 시스템 프롬프트 조립 | engine 밖. 크기만 입력으로 | **engine 이 흡수** — 이미 교체 가능한 seam 이고, 합칠 유일한 이유(CTX-05 캐시 경계)는 아직 없다 |
| 포크 | 주입 가능, 기본 `passthrough()` | **`passthrough()` 고정** — 지금 포크가 가진 설정 가능한 압축을 없앤다 |
| 복구 연산 | `drop(from, to)` + 합법 절단면 불변식 | **`dropBefore(seq)` (prefix)** — 지금 복구는 USER 메시지 하나를 빼므로 모델이 보는 것이 바뀌고, prefix 는 tool 짝을 자를 수 있다 |
| span 개수 | 하나 | **여러 span** — 요약끼리의 순서·중첩을 관리해야 하고, 누적 갱신이 한 요약을 전제한다 |
| 롤링의 복원 훅 | 기본 등록 안 함, 등록 시 `SYNTHETIC` | **자동 등록** — 압축마다 파일 원문이 로그에 쌓여 다음 압축을 앞당기고 대화로 ingest 된다 |
| ingest 크기 | `maxIngestTokens` 청크 | **한 번에** — 압축이 더 이상 페이로드를 묶어 주지 않는다 |
| 압축 전 메모리 flush | 하지 않는다 | **에이전트 주도 flush iteration** (Hermes) — iteration 이 하나 더 들고, 그것이 다시 컨텍스트를 키울 수 있고, 무엇을 저장할지가 모델에 달린다. 로그가 원문을 들면 지킬 것이 없다 |
| `SessionId` | 압축으로 바꾸지 않는다 | **압축마다 새 세션 + parent** (Hermes) — lease·펜싱, `SessionRouter`, `IdempotencyStore`, `SessionInbox`, 1:N `LiveSession` 이 모두 `SessionId` 에 걸려 있다 |
| 되찾기 범위 | 현재 세션 | **세션 간 원문 검색** — `WorkspaceAccessPolicy` 가 지키는 격리를 도구 하나가 우회한다 |
| 메모리 주입 고정 | 하지 않는다 (§9) | **세션 시작 시점 값으로 고정** (Hermes) — 두 어댑터 모두 캐시 경계를 구현하지 않아 지금은 얻는 것이 없다 |
| 롤링의 전제 | 로그·뷰 분리 뒤에만 | **기록을 고쳐 쓰는 모델 위의 롤링** — 압축 빈도가 높을수록 L3~L5 도 잦다. v1 쓰기 모드에서 롤링을 배선하면 기동 시 실패한다 |

### 8.2 기존 공개 SPI

지금 뷰를 바꾸는 네 seam 은 공개 SPI 이고, `OrcaAgentRuntime.Builder` 와 `OrcaProviderDependencies.Builder` 로 외부에서
주입할 수 있다.

| SPI | 결정 | 이유 |
|-----|------|------|
| `CompactionGuard` | deprecated. v1 쓰기 모드에서만 주입을 받는다. v2 에서 주입하면 기동 시 실패 | 계약이 "버퍼를 받아 필요하면 고친다" 이다. 뷰 상태 연산으로 기계적으로 옮길 수 없다. 판정 규칙을 바꾸고 싶은 쪽은 `ContextEngine` 을 구현한다. 가드를 **소비**하던 쪽도 옮긴다 — `CompactCommand` 는 `recordExternalSuccess` 를 부르지 않고 `engine.compactNow` 를 부르며, breaker 리셋은 engine 안에서 한다 |
| `CompactionEngine` | 유지. `summarize(SummaryRequest)` 와 `supportsSummarize()` 추가, `compact(CompactionRequest)` 는 deprecated | 요약을 만드는 부분은 그대로 쓸모 있다. `supportsSummarize()` 는 default 로 false 이고 `DefaultCompactionEngine` 은 true 다. v2 에서 false 인 엔진을 주입하면 기동 시 실패한다 — 호출해 보지 않고 탐지하려면 능력 메서드가 필요하다 |
| `PromptSizeRecoveryStrategy` | 유지. 어댑터로 받는다. **줄이기만 하는 전략**만 동작한다 | 계약이 `List<Message>` 를 받아 `List<Message>` 를 돌려준다. 어댑터는 넘긴 뷰와 돌려받은 목록을 **위치 기반 diff**(부분 수열 매칭)로 비교해 빠진 위치를 찾는다 — 같은 인스턴스가 두 번 append 될 수 있어 인스턴스 동일성만으로는 모호하다. 빠진 위치를 **연속 구간으로 합친 뒤** seq 구간으로 바꿔 `drop` 한다 — assistant(tool_use)와 그 `TOOL` 을 함께 뺀 정당한 결과가 개별 검사에서 거절되지 않게. 다음 경우는 거절하고 WARN 을 남긴다 — 돌려받은 목록에 새 메시지나 바뀐 메시지가 있을 때(긴 도구 결과를 잘라 내는 전략 포함), 뷰가 만든 메시지(경계·요약 마커, elide placeholder)가 빠졌을 때, 합친 구간이 불변식을 어길 때. 이를 위해 뷰 계산은 원문 항목의 `Message` 인스턴스를 그대로 쓴다 |
| `PromptTooLongHandler<TranscriptBuffer>` (`LlmCallGateway`) | Orca 에서 배선하지 않는다 (지금도 기본 배선 없음). `ThrowingPromptTooLongHandler` 는 `engine.recover` 로 대체되었다고 적는다 | 게이트웨이 안에서 버퍼를 고치는 두 번째 복구 경로가 생기면 engine 의 결정성이 깨진다 |

### 8.3 [`compaction.md`](compaction.md) 와의 관계

`compaction.md` 가 정한 것 대부분은 그대로 유효하다 — 판정 순서, circuit breaker 와 그 저장 위치, 훅 계약, 요약 프롬프트의
인젝션 방어, 재귀 방지. 바뀌는 것은 두 가지다.

- **교체의 대상** — `replaceWith` 로 기록을 바꾸던 것이 뷰 상태 연산이 된다. `compaction.md` §10.2("`TranscriptBuffer` 확장
  vs 새 타입")의 결정은 뷰 모델에서 대체된다
- **"원본 무손상"** 원칙의 뜻이 강해진다 — 요약 실패 시만이 아니라 성공 시에도 원본은 손상되지 않는다

이 설계가 구현되면 `compaction.md` 에서 대체되는 절을 이 문서로 링크한다.

### 8.4 증류와 원문의 분업

`PeerMemory` 는 "무엇을 알게 되었나" 이고 로그는 "정확히 무엇이 오갔나" 다. 로그를 세션 밖으로 검색하게 하지 않고,
`PeerMemory` 로 원문을 대신하지 않는다.

---

## 9. 하지 않는 것 — 메모리 주입 고정

Hermes 는 시스템 프롬프트에 넣는 메모리를 세션 시작 시점 값으로 고정해 프롬프트 캐시 prefix 를 지킨다. AIMON 에서도 메모리
파트는 실행마다 다시 읽힌다. 그러나 지금은 비용 누수가 아니다.

- 두 LLM 어댑터 모두 캐시 경계를 구현하지 않았다. `LlmClient` 는 그 자리를 "MAY override" 로 열어 두었고,
  `SystemPromptRenderer` 는 그것을 CTX-05 로 따로 추적한다. Anthropic 은 명시적 `cache_control` 없이는 캐시하지 않는다
- OpenAI 의 자동 prefix 캐시는 동작하지만, 메모리 파트 뒤에는 실행마다 바뀔 수 있는 context SYSTEM 블록이 있다

CTX-05 가 선행 조건이다. 그때 되살린다면 고정 단위는 "메모리 스냅샷이 의미 있게 바뀌었을 때" 같은 명시적 갱신 정책이 낫다 —
롤링에서는 span 이 넓어져도 system + head prefix 는 그대로여서, 압축마다 메모리를 다시 읽으면 오히려 그 prefix 캐시를 깬다.

---

## 10. 선택과 관측

- **배선** — 배선하지 않으면 `DefaultContextEngine` 이다. `OrcaAgentRuntimeFactory` 가 지금 가드를 만드는 자리에서 만든다.
  비-Spring 조립은 `AimonStackBuilder` 의 spec 이 engine 을 받는다
- **설정 키** — Spring `aimon.context.engine`(`default` | `rolling`), AGENT.md frontmatter `context-engine`. 키 이름을 여기서
  정하는 것은 결정이기 때문이다. 사용법과 나머지 키는 `docs/features/` 의 몫이다. runtime 은 agent-scoped 이므로 engine 도
  agent 별로 갈린다
- **관측** — 롤링은 압축 빈도가 높아 운영 중 튜닝이 필요하다. `CompactionMetadata` 에 `kind`(`PRUNE`/`ROLLING`/`FULL`/
  `FALLBACK`), 뷰의 head·span·tail 토큰, 요약 토큰, 흡수한 seq 범위를 더한다. 실행기의 `CompactBoundary` 이벤트가 싣는
  전후 크기는 로그가 아니라 **뷰**의 크기다 — 로그는 압축해도 줄지 않는다

---

## 11. 하지 말 것

- **engine 밖에서 뷰를 줄이지 않는다.** 가드·명령·훅·게이트웨이 핸들러가 뷰 상태를 고치면 결정성(§3.3)을 누구도 지키지
  않는다. 로그를 자르는 rewind·`/clear` 가 잘린 seq 를 정리하는 것만 예외다
- **합법 절단면이 아닌 곳을 자르지 않는다.** `summarize` 와 `drop` 은 불변식을 검사한다
- **뷰 계산에 비결정적 입력을 넣지 않는다.** 요약처럼 비결정적으로 만들어지는 값은 만들어진 순간 뷰 상태에 저장한다
- **로그를 LLM 에 보내지 않는다.** 호출 자리에서 `transcriptBuffer.getMessages()` 를 다시 쓰면 압축이 없는 것과 같다
- **뷰를 영속하지 않는다.** 영속하는 것은 뷰 상태다
- **engine 이 메모리를 부르지 않는다.** 메모리는 로그를 읽는다(§7)
- **런타임이 넣는 메시지를 `CONVERSATION` 으로 append 하지 않는다.** 대화로 ingest 되고 되찾기에 섞인다
- **span 을 좁히지 않는다.** span 은 넓어지기만 한다. rewind 로 로그가 잘리는 경우만 예외이며, 그 처리는
  [`session-log.md` §6.1](../session/session-log.md) 이 정한다

---

## 12. 열린 질문

- **요약 풍화의 검증** — 누적 섹션 항목에 id 를 붙여 갱신 전후 diff 로 "삭제된 결정" 을 탐지할지. 관측값(§10)을 먼저 본다
- **토큰 추정 오차** — 휴리스틱 추정기는 과대 추정한다. 롤링은 압축 빈도가 높아 오차가 비용으로 쌓인다. 프로바이더 usage 로
  보정할지
- **기본 engine 의 되찾기** — 전체 compaction 뒤에도 `SessionHistoryTool` 을 열지
- **메모리 주입 고정** — CTX-05 이후(§9)

---

## 부록: 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| LLM 호출 자리 (메인) | [`OrcaAgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java) — `invokeGatewayOnce`, `invokeGateway`(복구), 압축 게이트 |
| LLM 호출 자리 (포크) | [`DefaultSubagentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/DefaultSubagentExecutor.java) — `applyCompactionGate` |
| LLM 호출 자리 (스킬) | [`LlmSkillExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/skill/execution/llm/LlmSkillExecutor.java) |
| 지금의 판정 | [`DefaultCompactionGuard.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultCompactionGuard.java) |
| 지금의 요약·교체 | [`DefaultCompactionEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultCompactionEngine.java) |
| 지금의 복구 | [`DefaultPromptSizeRecoveryStrategy.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultPromptSizeRecoveryStrategy.java), [`ThrowingPromptTooLongHandler.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/ThrowingPromptTooLongHandler.java) |
| 공개 주입 자리 | [`OrcaAgentRuntime.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntime.java), [`OrcaProviderDependencies.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/orca/OrcaProviderDependencies.java) |
| 수동 명령 | [`CompactCommand.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/command/system/CompactCommand.java) |
| 복원 훅 | [`RecentFilesRestoreHook.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/impl/RecentFilesRestoreHook.java), [`InvokedSkillsRestoreHook.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/impl/InvokedSkillsRestoreHook.java) |
| 한계값 | [`ModelContextLimits.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/ModelContextLimits.java) |
| 예산 | `BudgetTracker` — [`orca-executor.md` §4](orca-executor.md) |
| 메모리 ingest | [`IngestingExecutionMemorySink.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/memory/IngestingExecutionMemorySink.java), [`LlmDeriver.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/memory/deriver/LlmDeriver.java) |
| 시스템 프롬프트 (engine 밖) | [`SystemPromptRenderer.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/SystemPromptRenderer.java), [`ContextAssembler.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextAssembler.java) |

---

## 13. 구현과의 차이

§1 ~ §12 와 부록은 리뷰를 통과한 설계 그대로다(`f09d891`). 이 절은 구현이 그 글에서 갈라진 자리와, 구현을 마친 뒤에도
열려 있는 결과를 적는다. 저장 쪽의 차이는 [`session-log.md` §12](../session/session-log.md#12-구현과의-차이) 에 있다.

### 13.1 SPI 와 호출 지점 (§3, §8.2)

- **`ContextRequest` 에 `callMetadata` 가 있다.** §3.1 목록에는 없다. `/compact` 는 `LlmCallMetadata`
  (`component = compact-command`, traceId, principal)를 붙이는데 `ContextCaller(executionId, principal)` 로는 component
  이름을 나를 수 없다. 선택 필드이고 engine 은 그대로 `CompactionRequest` 로 넘긴다
- **`hookRegistry` · `environment` 는 선택이다.** 스킬 루프(`LlmSkillExecutor`)는 scratch 버퍼 위에서 돌고 둘 다 없으며
  `passthrough()` 를 쓴다. `DefaultContextEngine` 은 둘 중 하나가 빠지면 `IllegalArgumentException` 을 던진다
- **`summarize` 의 모양.** `CompactionEngine.summarize(SummaryRequest)` 는 새 타입이 아니라 `CompactionResult` 를 돌려준다.
  PreCompact 는 발화하지만 PostCompact 는 발화하지 않는다 — PostCompact 는 설치된 뒤의 상태를 보는데 그것은 호출자만
  만든다. 그래서 `summaryInstalled(SummaryRequest, CompactionResult, TranscriptBuffer)`(기본 no-op)를 더했고,
  `DefaultCompactionEngine` 은 `compact()` 와 같은 invoker 이름·같은 파일/스킬 스캔으로 PostCompact 를 거기서 발화한다.
  롤링을 위해 `SummaryRequest` 에 `rolling` · `previousSummary` · `targetSummaryTokens` 가 붙었다
- **ArchUnit 예외 하나.** `agent.context` 는 `ContextRequest` 가 `HookRegistry` 를 들어야 하므로 `agent..` 의 hook 금지
  규칙에서 떨어져, `HookRegistry` 하나만 허용하는 규칙(`agentContextMayDependOnHookRegistryOnly`)을 받았다.
  `agent.compact` · `agent.session` 과 같은 모양이다
- **메인 루프의 `ContextCaller` 는 `ExecutionId` 를 싣지 않는다.** 오늘처럼 세션 정체성으로 부른다. 포크만 싣는다
- **`/compact` 의 breaker 리셋이 OnStop 훅보다 먼저 일어난다.** §8.2 대로 리셋이 engine 안으로 들어갔기 때문이다.
  예전에는 OnStop 뒤였다. 성공 조건은 같다
- **복구 사유 로그**는 executor 가 아니라 `DefaultContextEngine.recover` 가 남긴다. 흐름(전략 1회, 재시도 1회)은 같다
- **배선 표면.** `OrcaAgentRuntime.getContextEngine()` 은 null 이 아니다 — 명시한 engine 이 없으면 `build()` 가 guard ·
  복구 전략 · compaction engine 으로 `DefaultContextEngine` 을 파생한다. `OrcaProviderDependencies.contextEngine`,
  `CompactCommand(ContextEngine, …)`, `DefaultSubagentExecutor(…, ContextEngine)` 가 생겼고 옛 생성자는 그것을 감싸서 남았다
- **`ContextView.estimatedTokens` 는 추정하지 않았으면 0** 이다. `passthrough()` 와 추정기 없는 파생 engine 이 그렇다
- **`ContextDecision.getViewSizeBefore()`** (`OptionalInt`)를 더했다. 부록 A 가 말한 대로 executor 의 `CompactBoundary`
  크기는 뷰 크기다. 이것을 보고하지 않는 engine(mock, passthrough)에서는 예전처럼 `buffer.size()` 를 쓴다
- **`ContextEngineKind` 는 `at.aimon.core.agent` 에 있다.** `AgentMetadata`(`agent` 패키지)가 들고 있고 `agent.context` 는
  이미 `agent` 에 의존하므로, `agent.context` 에 두면 순환이 생긴다
- 구현체(`DefaultContextEngine` · `RollingContextEngine` · `ViewProjection` · `RecoveryDiff`)는 §3.1 대로 `agent.context`
  에 있다. `.impl` 이 아니다 — `DefaultContextAssembler` 가 선례다

### 13.2 `DefaultContextEngine` 의 뷰 모드 (§4, §8.2)

- **판정 규칙의 재사용.** guard 의 진입점은 전부 자기 `compact` 로 버퍼를 고쳐 쓴다. 그래서
  `DefaultCompactionGuard.decide(sessionId, systemPrompt, view, model, budgetForced, Compactor)` 를 더했다 — 같은
  `tryLock` · ladder · precondition · breaker 를 호출자가 준 뷰에 돌리고, 압축 자체는 콜백으로 되돌려 준다.
  옛 진입점은 같은 코드로 위임한다
- **뷰 모드의 조건.** guard 가 정확히 `DefaultCompactionGuard`(하위 클래스 아님) 또는 `NoOpCompactionGuard` 이고
  compaction engine 이 `supportsSummarize()` 일 때다. 모드는 노드가 아니라 **버퍼의 형식**(`TranscriptBuffer.getFormat()`)
  으로 고른다 — sticky 로 올라간 레코드는 v1 쓰기 노드에서도 뷰 모드다. 그 밖의 조합이 v2 버퍼를 만나면 in-place 로
  물러나고 engine 당 WARN 을 한 번 남긴다. 그 결과는 §13.5 첫 항목이다
- **"v2 에서 기동 실패" 의 자리**는 `DefaultContextEngine.Builder.writeFormat(V2)` 다. `build()` 가 커스텀
  `CompactionGuard`, 요약하지 못하는 engine, engine 없는 Default guard 를 거절한다. `OrcaAgentRuntimeFactory` 는 노드의
  쓰기 형식으로 이것을 건다. `OrcaAgentRuntime.Builder` 가 파생하는 engine 은 형식을 받지 않으므로(v1) 그 경로로 들어온
  커스텀 guard 는 거절되지 않는다
- **전체 compaction** 의 span 은 `[floorSeq, nextSeq)` 이고 요약 입력은 현재 뷰(이전 `[B, S]` 포함)다 — in-place 재압축이
  보내던 것과 같으므로 L2 가 그대로다. `summarizeView` 가 span 을 거절하면 실패이고 로그는 그대로다
- **`compactNow`** 는 뷰 전체를 MANUAL 로 요약하고 성공하면 breaker 를 리셋한다. 빈 뷰는 LLM 을 부르지 않고 실패한다.
  기본 engine 에는 `tryLock` 과 경합 실패가 없다 — §5.6 이 그것을 롤링 engine 의 절에 두었고, 롤링만
  `CompactionContendedException` 을 돌려준다
- **복구 어댑터 `RecoveryDiff`.** 전략의 답을 뷰와 인스턴스로 대조한다(왼쪽부터 탐욕 — 같은 인스턴스가 둘이면 앞의 것이
  남는다). 빠진 위치를 run 으로 묶어 run 마다 `drop` 하나. 새 메시지나 고쳐 쓴 메시지, 뷰가 만든 메시지(마커·elide 된
  결과)를 빼는 답, 아무것도 빼지 않는 답, 합법 절단을 어기는 구간은 WARN 과 함께 거절한다. 모든 구간을 상태의 사본에서
  먼저 검증하므로 답은 통째로 적용되거나 전혀 적용되지 않는다
- **deprecation.** `CompactionGuard`, `CompactionEngine.compact`, `TimeBasedMicrocompact` 가 deprecated 다(버퍼 쪽은
  session-log). `DefaultCompactionGuard` · `NoOpCompactionGuard` 자체와 guard 타입을 받는 주입 자리는 뷰 모드가 재사용하므로
  deprecate 하지 않고 `@SuppressWarnings("deprecation")` 로 담았다
- **메모리 ingest(§7).** SYNTHETIC 을 빼고 합법 절단에서 나눈다. 두 절단 사이의 run 이 예산을 넘으면 쪼개지 않고 통째로
  보낸다. §7 에 값이 없던 `maxIngestTokens` 의 기본은 `IngestChunks.DEFAULT_MAX_INGEST_TOKENS = 32_000` 이고 설정 키로
  노출하지 않았다

### 13.3 `RollingContextEngine` (§5)

- **§5.6 의 WARN 행은 적힌 대로는 거의 닿지 않는다.** "모델이 롤링을 지탱하는가" 검사가 먼저 돌고, 그것을 통과했다면
  stage 2(tail 0, head 유지)의 예상 크기는 언제나 `warning` 아래라서 절단면이 있으면 채택된다. 그래서 FALLBACK 경고는
  **어떤 합법 절단도 흡수할 것이 없을 때** 낸다 — 현실적인 경우는 요약이 `summaryTokenRatio` 보다 훨씬 길게 돌아와
  head + 마커만으로 임계값을 넘는 것이다. blocking 에서는 같은 상황이 stage 3(head 흡수)로 간다
- **복구는 기본 engine 의 것이다.** §5 에 롤링의 복구가 없다. `recover` 는 같은 compaction engine · failure store · 복구
  전략으로 만든 내부 `DefaultContextEngine` 에 위임하고, v1 버퍼와 §5.6 의 "기본 동작으로 물러남" 도 그것이 맡는다.
  그 안의 guard lock 은 롤링 engine 의 lock 과 따로이며 한 호출은 둘 중 하나만 쓴다
- **precondition** 은 guard 의 것이 아니라 "뷰의 끝이 합법 절단" (`LegalCuts.isLegal`)이다. §3.4 의 불변식이고, 차이는
  다른 호출이 답을 기다리는데 `TOOL` 로 끝나는 뷰에서만 드러난다
- **본문이 열어 둔 선택들.**
  - user 메시지로 시작하지 않는 요약 입력에는 합성 user 메시지(`CONTINUATION_NOTE`)를 앞에 붙인다. span 이 새로 흡수하는
    구간은 assistant 메시지로 시작하기 쉽고 프로바이더는 그런 요청을 거절한다
  - 이전 요약은 메시지가 아니라 요약 호출의 시스템 프롬프트에 데이터로 격리해 넣는다(`<<<PREVIOUS_SUMMARY>>>`).
    user 뒤에 user 가 오는 것을 피하고 PostCompact 의 파일/스킬 스캔에서도 빠진다
  - `summaryModel` 은 요약 호출의 모델을 통째로 바꾼다
  - L0 prune 은 stage 0(tail 예산) 절단이 흡수할 구간만 본다. stage 0 절단이 없으면 prune 도 없다
  - span 의 `preTokenCount` 는 압축 전 뷰의 추정이고, span 의 `messagesSummarized` 는 누적, 메타데이터의 것은 이번에 흡수한
    양이다
  - "롤링을 지탱하지 못하는 모델" WARN 은 모델 이름당 한 번이다. 시스템 프롬프트는 실행마다 바뀔 수 있어 키로 쓰면
    끝없이 자란다
  - 비율은 `RollingContextEngine.Builder` 값뿐이고 Spring 속성이 아니다. §10 이 이름을 준 것은 `aimon.context.engine` 과
    `context-engine` 뿐이다
- **저장된 span 의 trigger 는 너그럽게 읽는다.** `ViewProjection` 은 모르는 `summarySpan.trigger` 를 `AUTO` 로 투영한다.
  trigger 는 경계 마커의 라벨일 뿐이고 대체값이 결정적이므로 §3.3 은 그대로 성립한다. 던지면 그 세션은 다시는 턴을
  돌지 못한다

### 13.4 `SessionHistoryTool` 과 선택 (§6, §10)

- **도구가 로그에 닿는 길.** 실행 중인 버퍼(레코드는 한 턴 늦다)와 봉인 구간을 읽을 reader 가 필요하다. executor 가 메인
  루프의 도구 컨텍스트마다 `SessionLogSource`(버퍼 + 선택적 `SessionLogReader`)를 `SessionHistoryTool.LOG_SOURCE_KEY` 로
  넣는다 — `TodoWriteTool.CONTEXT_ID_KEY` 선례다. reader 가 없으면 봉인 구간은 gap 항목으로 보고한다
- 검색은 뒤에서부터 64 seq 창으로 훑고 토큰은 `HeuristicTokenEstimator` 로 센다. 도구는 `CONCURRENT_SAFE` 다
- **등록.** 해석된 engine 이 rolling 이면 런타임 팩토리가 도구 provider 들 뒤에 직접 등록한다. allow-list 는 여전히 이름으로
  막는다. §5.7 의 복원 훅은 트리 어디서도 기본 등록되지 않으므로 코드 변경 없이 문서만 적었다
- **선택.** 기본 engine 은 `ExecutorSpec.contextEngine(...)`(Spring `aimon.context.engine`)이고 에이전트 frontmatter 가
  이긴다. rolling 은 v2 쓰기 형식을 요구한다 — 선언된 에이전트는 기동이 실패하고, 테넌트 런타임은 첫 resolve 에서 실패한다.
  **CLI 에는 쓰기 형식 스위치가 없어** 언제나 v1 이므로 `context-engine: rolling` 에이전트는 CLI 로 기동하지 않는다
- 사용 가이드는 [`../../features/agent-execution/context-engine-guide.md`](../../features/agent-execution/context-engine-guide.md)
  로 새로 썼다. §8.3 이 말한 [`compaction.md`](compaction.md) 쪽의 대체 표시는 달지 않았다

### 13.5 알려진 열린 결과

구현을 마친 시점에 남아 있는 행동상의 틈이다. 어느 것도 지원되는 조립(`OrcaAgentRuntimeFactory` · `AimonStackBuilder` ·
스타터)에서 상태를 깨뜨리지는 않지만 설계가 약속한 것과 다르다.

- **in-place 로 물러난 v2 로그는 뷰 상태를 무시한다.** §13.2 의 폴백은 `buffer.getMessages()` — carried 항목 그대로 —
  를 보내고 그것을 압축한다. span 의 요약은 모델에 가지 않고, span 이 가린 항목과 drop 된 항목이 되돌아오며, 봉인된 구간은
  말없이 빠진다. 이어지는 `replaceWith` 는 span 과 manifest 를 지우므로 요약과 봉인된 기록이 로그를 영영 떠나고, 세그먼트는
  grace 뒤에 GC 가 지운다. 닿는 조건은 뷰 모드를 못 하는 engine(커스텀 guard, 요약하지 못하는 compaction engine)이 v2
  레코드를 만나는 경우다 — v2 이행 중 노드마다 engine 구성이 다를 때, 또는 `OrcaAgentRuntime.Builder` 로 커스텀 guard 를
  넣은 경우(§13.2). `PassthroughContextEngine` 도 같은 모양이지만 뷰 상태를 가진 v2 버퍼를 넘기는 호출자가 없다.
  고치는 방향은 물러나기 전에 뷰를 투영하는 것(`ViewProjection.of`), 또는 뷰 상태나 manifest 가 비어 있지 않은 버퍼에
  `replaceWith` 를 거절하는 것이다
- **`headEnd()` 가 틀린 head 를 고를 수 있다.** head 를 첫 *carried* CONVERSATION user 항목에서 찾는다. prompt-too-long
  `drop` 이 진짜 첫 요청을 가리고 그 구간이 `minSealTokens` 에 닿아 봉인되면, 뒤의 user 메시지가 head 가 되어 영원히
  원문으로 남는다. 드물고 상태는 깨지지 않는다
- **두 engine 의 `/compact` 실패 계약이 다르다.** 롤링은 경합 시 `CompactionContendedException`, 기본 engine 은 lock 없이
  진행한다(§13.2)
- **비용이 긴 로그에서 제곱으로 자란다.** 롤링의 절단면 계산은 뷰 위치마다 O(n) 인 `isLegalCut` 을 불러 `prepare` 한 번이
  O(n²) 이다(`LegalCuts.legalPositions` 한 번이면 된다). `SessionHistoryTool` 의 검색과 `SessionLogReader` 의 페이징은 큰
  세그먼트를 창·페이지마다 다시 읽고 해시 검사하고 디코드하므로 봉인된 기록 크기의 제곱이다(`maxScanTokens` 까지)
