# Rolling Context Engine — 장기 대화의 뷰

> Status: **IMPLEMENTED** — 적용 대상 `aimon-core`, `aimon-bootstrap`, `aimon-spring-boot-starter`, `aimon-cli`. 남은 일은
> [`agent-execution-open-items.md`](../../backlog/agent-execution-open-items.md) 가 정본이다.
> 관련 문서: [`context-engine.md`](context-engine.md) (SPI · 기록과 뷰 · 합법 절단면 · 미응답 부분 · 기본 engine),
> [`compaction.md`](compaction.md) (임계값 · 판정 사다리 · breaker · 요약기 · 요약 요청의 끝맺음 · 훅 · `/compact`),
> [`../session/session-log.md`](../session/session-log.md) (로그 · 뷰 상태 · 봉인),
> [`../testing/context-engine-pressure-rig.md`](../testing/context-engine-pressure-rig.md) (기본 engine 과의 비교 리그)
> 사용 가이드: [`../../features/agent-execution/context-engine-guide.md`](../../features/agent-execution/context-engine-guide.md)

`RollingContextEngine` 은 `ContextEngine` 의 구현 하나다. 기본 engine 이 한계 근처에서 뷰 전체를 요약 하나로 접는 것과 달리,
세션의 목적(head)과 최근(tail)을 원문으로 두고 가운데(span)만 더 일찍, 더 작은 걸음으로 요약한다. 누수 ①(최근 맥락이
뭉개짐)과 ②(요약이 요약을 요약함)를 푸는 정책이다([`context-engine.md` §1](context-engine.md#1-기록과-뷰--무엇을-푸는가)).
이 문서에서 맨 "engine" 은 `ContextEngine`, `CompactionEngine` 은 **요약기**다.

---

## 1. 모양 — head · span · tail

```
로그:  s0 s1 s2 ............................................ sN
뷰:   [ head ][ B  S ][ 원문 ................ ][ tail ...........]
        원문    요약      (다음 압축 때 흡수)       원문
```

- **head** — 세션의 목적. 세대가 지나도 원문으로 남는다. 봉인되지 않는다
- **span** — head 뒤. 경계 · 요약 마커 쌍(`B`, `S`) 하나로 대체된다. 새 압축은 이전 span 을 흡수해 뒤로 넓어진다. span 구간은
  봉인 대상이다
- **tail** — 최근. 원문

뷰 상태에서 span 은 언제나 하나다. 모든 절단면은 미응답 부분의 시작 이하에서 고른다
([`context-engine.md` §7](context-engine.md#7-미응답-부분)).

---

## 2. head

- **정의** — `floorSeq` 이후 첫 턴의 **첫 번째 `CONVERSATION` USER 메시지까지(포함)**. 그 앞의 `SYNTHETIC` 항목(user-context,
  조립된 `<system-reminder>`)은 head 에 포함되지만 head 의 끝을 정하지 않는다. head 는 USER 메시지로 끝나므로 tool 짝이
  끊기지 않는다. `floorSeq` 기준이므로 `/clear` 뒤에도 성립한다
- **출처 판별** — 로그 항목의 `origin` 으로 한다([`session-log.md` §3.2](../session/session-log.md#32-항목의-출처--origin)).
  텍스트 태그로 판별하면 mid-turn 사용자 메시지를 오분류한다
- **seq 로 고정** — head 는 로그의 첫 `CONVERSATION` user 항목에 seq 로 고정된다. 그 항목이 carried 가 아니라 봉인된 구간에
  있을 수 있으면 — 첫 carried user 항목보다 앞에서 시작하는 manifest 줄이 있으면 — head 는 비어 있다. 뒤의 user 메시지를
  head 로 삼으면 그것이 영원히 원문으로 남기 때문이다. drop 되었지만 carried 인 첫 요청은 head 를 정한다
- **상한** — `headTokenRatio`(기본 effective window 의 5%)는 `CONVERSATION` 항목에만 적용한다. 첫 메시지에 거대한 로그를
  붙였다면 그것이 영원히 창을 차지하므로, 상한을 넘으면 head 는 비고 span 이 맡는다. `SYNTHETIC` 블록은 조립하는 쪽이 크기를
  제한하므로 상한 계산에서 뺀다
- **v1 에서 온 세션** — 로그의 첫 메시지가 경계 마커면 head 는 비어 있다. 첫 요청은 이미 요약 안에 있다

---

## 3. tail 과 절단면

- **예산** — 뒤에서부터 `tailTokenRatio`(기본 20%)를 채울 때까지. 도구 결과 하나가 수만 토큰일 수 있어 개수가 아니라
  토큰으로 잰다
- **절단면** — 합법 절단면이다([`context-engine.md` §6](context-engine.md#6-뷰-상태-연산과-합법-절단면)). assistant 메시지의
  시작점도 쓴다 — 한 턴 안에서 iteration 이 수십 번 도는 실행은 USER 메시지가 턴 시작의 하나뿐이라, USER 경계만 쓰면 tail 이
  빈다
- **선호** — 예산 경계 ±10% 안에 USER 경계가 있으면 그쪽으로 스냅한다
- **span 뒤** — tail 은 언제나 span 뒤에서 시작한다. span 이 하나이고 경계 · 요약 마커는 뷰가 만드는 것이라 이 불변식은
  구조로 성립한다. **예외**는 v1 에서 옮겨 온 세션이다 — 그 로그에는 옛 마커가 평범한 메시지로 남아 있고, 그 메시지들은 새
  span 에 흡수될 때 일반 메시지로 요약된다

---

## 4. 요약 갱신

span 을 넓힐 때 요약 입력은 **이전 요약 + 새로 흡수되는 원문**이다. 이전 요약은 뷰 상태에 저장된 값이므로 마커를 파싱하지
않는다. 새로 흡수되는 원문은 hot 로그에 있다 — 아직 span 밖이었으므로 봉인되지 않았다.

- **지시** — "새로 요약하라" 가 아니라 **"이전 요약을 갱신하라"** 다
- **섹션** — 요약 프롬프트의 섹션([`compaction.md`](compaction.md))에 `Key decisions and constraints` 를 더한다.
  `Primary Request and Intent`, `Key decisions and constraints`, `Pending Tasks` 는 **누적 섹션**이다 — 항목은 명시적으로
  완료 · 철회되지 않은 한 지우지 않는다. 사용자 지침의 격리 규칙은 compaction.md 그대로다
- **목표 길이** — `summaryTokenRatio`(기본 8%)를 지시한다. 넘었다고 재작성 호출을 하지 않는다 — 요약 호출은 압축당 한 번이다
- **이전 요약의 자리** — 메시지가 아니라 요약 호출의 시스템 프롬프트에 데이터로 격리해 넣는다(`<<<PREVIOUS_SUMMARY>>>`).
  요약문이 대화의 한 메시지로 읽히지 않고, PostCompact 의 파일 · 스킬 스캔에서도 빠진다
- **입력의 머리** — user 메시지로 시작하지 않는 요약 입력에는 합성 user 메시지(`CONTINUATION_NOTE`)를 앞에 붙인다. span 이 새로
  흡수하는 구간은 assistant 메시지로 시작하기 쉽고 프로바이더는 그런 요청을 거절한다
- **입력의 끝** — 롤링은 흡수 구간이 무엇으로 끝나든 자기 입력을 `ROLLING_SUMMARIZE_NOTE` 로 스스로 닫는다. 롤링은
  `summarize()` 를 지원하는 어떤 요약기와도 돌기 때문이다. 요약기는 이미 붙은 지시를 알아보고 다시 붙이지 않는다. 끝맺음
  규칙과 지시 문구의 정본은 [`compaction.md`](compaction.md) 다
- **요약 모델** — `summaryModel` 은 요약 호출의 모델을 통째로 바꾼다. runtime 의 `LlmClient` 는 하나이므로 같은 프로바이더의
  모델만 된다
- **장부** — span 의 `preTokenCount` 는 압축 전 뷰의 추정이다. span 의 `messagesSummarized` 는 누적이고, 압축 기록
  (`CompactionMetadata`)의 것은 이번에 흡수한 양이다

---

## 5. L0 prune

LLM 요약 전에 무비용 정리를 먼저 시도한다.

- **구간** — 이번 압축의 절단(stage 0 의 tail 예산 절단, 그것이 없으면 미응답 부분 앞의 마지막 절단)이 흡수할 구간에서,
  head 를 빼고, 본문이 `pruneMinTokens`(기본 500) 이상인 `TOOL` 메시지를 가린다(`elide`). 이미 읽은 큰 결과만 가리고 미응답
  결과는 가리지 않는다. 미응답 부분이 tail 예산보다 크면 stage 0 절단이 없으므로, 그때도 그 앞의 **이미 읽은** 큰 결과는
  가릴 수 있다
- **head 는 제외한다** — 세션의 목적이고 prefix 캐시의 핵심이다
- **placeholder** — `"[tool result elided: seq=<seq>]"` 로 결정적이다. 원문은 로그에 있고 `SessionHistory` 가 seq 로
  되찾는다([§12](#12-sessionhistorytool))
- **채택** — prune 만으로 유효 임계값 아래로 내려가면 요약하지 않는다. 액션은 `COMPACT`, 기록의 `kind = PRUNE` 이다. prune
  계획은 임계값 아래로 내려갈 때만 생긴다
- 로그를 건드리지 않으므로 prune 은 메모리 ingest 에 영향을 주지 않는다

L0 의 다른 구현이던 `TimeBasedMicrocompact` 는 deprecated 다. 살아 있는 L0 는 이 prune 하나다.

---

## 6. 언제 — 후퇴와 FALLBACK

임계값(`auto` · `warning` · `blocking`, 유효 임계값 = `budgetForced ? warning : auto`)은 기본 engine 과 같은
`ModelContextLimits` 의 것을 쓴다. 시작 비율도 두 engine 에 같은 하나다. 식과 비율의 정본은 [`compaction.md`](compaction.md)
다. 모든 크기는 시스템 프롬프트를 포함해 잰다.

**판정 순서**는 기본 engine 의 사다리와 같다 — blocking → breaker(AUTO 만 막음) → auto. 다른 것은 둘이다.

- **사전조건** — 가드의 것이 아니라 "뷰의 끝이 합법 절단"(`LegalCuts.isLegal`)이다. 합법 절단면 불변식 그대로이고, 차이는 다른
  호출이 답을 기다리는데 `TOOL` 로 끝나는 뷰에서만 드러난다
- **세션 락** — 롤링 자신의 노드 로컬 `tryLock` 이다. `prepare` 가 락을 얻지 못하면 압축하지 않고 `NONE`(`concurrent
  compaction in progress`)으로 현재 뷰를 보낸다

auto 단계에서는 prune([§5](#5-l0-prune))을 먼저 보고, 그것으로 모자라면 span 을 넓힌다. 구간은 요약 **전에** 토큰 추정으로
정한다. 예상 크기 = `system + head + summaryTokenRatio × effective + 원문 나머지`.

| 단계 | span 끝 | 채택 조건 |
|------|---------|-----------|
| 0 | tail 예산 경계 | 예상 크기 < 유효 임계값 |
| 1 | tail 예산 절반 경계 | 예상 크기 < 유효 임계값 |
| 2 | 마지막 합법 절단면 (tail 0, head 유지) | 예상 크기 < 유효 임계값 |
| — | 압축하지 않고 `WARN`, `kind = FALLBACK` | 어떤 합법 절단도 흡수할 것이 없거나, 예상 크기가 유효 임계값 아래로 내려가지 않음. 현재 크기는 blocking 미만 |
| 3 | 마지막 합법 절단면, **head 까지** span 에 포함 | 현재 크기가 blocking 이상 |

- **채택 조건이 유효 임계값인 이유** — auto 로 비교하면 budget-forced 패스에서 예상 크기가 warning 과 auto 사이인 구간이
  채택되고, 다음 iteration 의 budget-forced 요청이 다시 압축한다. `BudgetTracker` 는 한번 `SHOULD_COMPACT` 가 되면 매
  iteration 그 값을 돌려주므로, 매 iteration 요약을 요약하게 된다
- **`WARN` 행이 필요한 이유** — 최선의 결과로도 임계값 아래로 내려가지 못하는 대화를 압축하면 다음 iteration 에 또 압축한다.
  성공이므로 breaker 도 멈추지 않는다. 원인은 대개 시스템 프롬프트, head, tail 의 거대한 메시지다
- **FALLBACK 이 실제로 나는 자리** — 롤링 가능성 검사([§7](#7-롤링-가능성-검사))를 통과한 모델에서는 stage 2 의 예상 크기가
  언제나 warning 아래다. 그래서 FALLBACK 은 어떤 합법 절단도 흡수할 것이 없을 때 낸다. 현실적인 경우는 요약이 목표 길이보다
  훨씬 길게 돌아와 head 와 마커만으로 임계값을 넘는 것이다. blocking 에서는 같은 상황이 stage 3 으로 간다
- FALLBACK 은 실행 결과의 `compactionEvents` 에 그것을 결정한 iteration 마다 남는다. 의도된 관측이다
  ([`context-engine.md` §11](context-engine.md#11-선택과-관측))
- AUTO 요약이 성공하면 breaker 를 리셋한다. 실패를 세는 규칙은 [`compaction.md`](compaction.md) 의 breaker 다

---

## 7. 롤링 가능성 검사

롤링이 의미를 가지려면 압축 직후의 최소 크기가 임계값 아래여야 한다.

```
minAfter = system + head + summaryTokenRatio × effective + minTailRatio × effective   (minTailRatio 기본 0.05)
롤링 가능 ⇔ minAfter < warning  (warning 은 0 이상으로 clamp)
```

- **판정 시점에 본다** — 시스템 프롬프트 크기는 빌드 시점에 알 수 없다
- **불가능하면 기본 engine 의 동작으로 물러난다** — 그 모델 · 그 시스템 프롬프트 조합에서는 내부의 `DefaultContextEngine` 이
  호출을 맡는다. 작은 창에서 롤링을 강행하면 매 iteration `WARN` 에 머물다 blocking 에서 head 까지 삼키는, 기본 engine 보다
  나쁜 결과가 나온다. 창이 작으면 warning 이 0 으로 clamp 되어 이 검사에 걸린다
- **WARN 은 모델 이름당 한 번이다** — 시스템 프롬프트는 실행마다 바뀔 수 있어 그것을 키로 쓰면 기록이 끝없이 자란다

---

## 8. 미응답 부분만으로 넘칠 때

미응답 부분은 가리지도 흡수하지도 않으므로([`context-engine.md` §7](context-engine.md#7-미응답-부분)), 미응답 부분만으로
임계값을 넘는 뷰에는 롤링이 할 수 있는 일이 정해져 있다.

- **blocking 미만** — 어떤 절단도 임계값 아래로 내려가지 못하므로 `WARN`(`kind = FALLBACK`) 행이다. 뷰는 그대로 간다
- **blocking 이상** — stage 3 이 head 까지 미응답 부분 앞의 모든 것을 요약한다. 그래도 blocking 을 넘으면 WARN 로그를 남기고
  **그대로 보낸다** — 추정은 휴리스틱이고 판정은 프로바이더와 prompt-too-long 복구의 몫이다. 결정은 `COMPACT` 지만 사유가
  `blocking-limit forced compaction; the view is still at or above the blocking limit (post=…, blocking=…, unread=…)` 로
  바뀐다(`RollingContextEngine.STILL_OVER_BLOCKING`, 기본 engine 의 것과 같은 상수)
- **사실은 기록에 싣는다** — 롤링이 내는 모든 압축 기록(`ROLLING` · `PRUNE` · `FALLBACK`)에 판정 기준이 된 blocking 한계
  (`getBlockingLimit()`)가 실리고, 위 경우의 기록은 `isOverBlockingLimit()` 가 참이다. 실행기는 결정의 사유를 결과에 싣지
  않으므로, 호출자가 로그를 긁지 않고 평소의 blocking 압축과 구별하려면 메타데이터여야 한다. 이 경우는 요약 경로에서만
  생긴다 — prune 계획은 임계값 아래로 내려갈 때만 있고 임계값은 blocking 보다 낮다
- **같은 뷰로 다시 오면 `BLOCK`** — 복구가 아무것도 줄이지 못해 같은 뷰가 다시 오면 새로 흡수할 것이 없으므로 요약을 다시
  요약하지 않는다. 실행은 `ContextWindowExceededException` 으로 끝나고 사유에 미응답 부분의 크기가 들어간다. 반복도, 미응답
  결과의 elide 도 없다. 기본 engine 이 같은 자리에서 그대로 보내는 이유는 [`context-engine.md` §8.3](context-engine.md#83-blocking-한계)
  에 있다

---

## 9. 복구와 `/compact`

- **복구는 기본 engine 의 것이다.** `recover` 는 같은 요약기 · `CompactionFailureStore` · 복구 전략으로 만든 내부
  `DefaultContextEngine` 에 위임한다. v1 버퍼와 롤링 가능성 검사의 "기본 동작으로 물러남" 도 그것이 맡는다. 그 안의 가드
  락은 롤링의 락과 따로이며 한 호출은 둘 중 하나만 쓴다
- **`compactNow`** — 임계값 판정만 건너뛰고 구간 선택(stage 0 → 1 → 2)은 따른다. `WARN` 행은 없다 — 사용자가 요청했다.
  미응답 부분 앞에서 멈추고, 뷰가 미응답 입력뿐이면 `nothing to compact` 실패다. 명령 흐름과 breaker 리셋은
  [`compaction.md`](compaction.md) 가 정한다
- **경합** — `compactNow` 의 세션 락은 `tryLock` 이다. 다른 압축이 진행 중이면 기다리거나 조용히 아무것도 하지 않는 대신
  `CompactionContendedException` 을 실은 실패 결과를 돌려주고, 명령은 그것을 사용자에게 보여 준다. 기본 engine 의
  `compactNow` 는 세션 락 없이 진행한다 — 경합 실패는 롤링만의 계약이다
- **요약기의 예외는 실행을 실패시키지 않는다** — `prepare` 와 `compactNow` 모두 요약기의 `summarize(...)` · `summaryInstalled(...)`
  가 던진 예외를 실패 결과로 바꾼다. breaker 에 세는 것은 AUTO 경로뿐이다. `summaryInstalled` 가 던지면 span 은 이미 기록되어
  있으므로 그대로 두고 실패를 보고한다

---

## 10. 복원 훅

`RecentFilesRestoreHook` · `InvokedSkillsRestoreHook` 는 압축 뒤 최근 읽은 파일과 스킬 목록을 USER 메시지로 로그에 붙인다.
훅의 계약과 두 구현은 [`compaction.md`](compaction.md) 에 있다. 롤링에서는 **등록하지 않는다.**

- 롤링은 tail 을 원문으로 보존하므로 "요약이 잃은 파일" 을 다시 붙일 이유가 약하다
- 롤링은 자주 압축하므로, 압축마다 파일 원문이 새 메시지로 쌓이고 그것이 tail 에 남아 다음 압축을 앞당긴다

등록한다면 훅은 `origin = SYNTHETIC` 으로 붙인다. 그래야 메모리 ingest 와 되찾기가 다시 붙인 파일을 대화로 취급하지 않는다.

---

## 11. v2 쓰기 형식과 조정 값

- **롤링은 v2 쓰기 형식을 요구한다.** span 을 뷰 상태에 두는데 v1 쓰기 모드는 그것을 저장할 수 없다. 노드의 쓰기 형식이 v1
  이면 런타임 팩토리가 롤링 engine 을 만들지 않는다 — 선언된 에이전트는 기동이 실패하고, 테넌트 런타임은 첫 resolve 에서
  실패한다. 형식 키는 스타터 `aimon.session.log-write-format`, CLI `cli.sessionLogWriteFormat` 이고 기본은 모두 v2 다
- **CLI** — `cli.sessionLogWriteFormat` 은 CLI 설정의 다른 키처럼 camelCase 이고 값은 대소문자를 가리지 않는다. v2 면 CLI 는
  in-memory 세그먼트 저장소를 함께 붙이므로 롤링이 가린 구간은 레코드 밖으로 봉인되고 `SessionHistory` 가 그것을 다시 읽는다.
  v1 으로 고정한 CLI 에서 `context-engine: rolling` 에이전트는 기동하지 않는다
- **조정 값** — `RollingContextEngine.Builder` 의 값이다. 스타터는 `aimon.context.rolling.*`(`head-token-ratio` ·
  `tail-token-ratio` · `summary-token-ratio` · `min-tail-ratio` · `prune-min-tokens`)을 `ExecutorSpec.rollingContextEngineCustomizer`
  로 넘긴다. 요약 모델은 속성이 아니고 그 customizer 로 정한다. customizer 는 engine 을 조정할 뿐 런타임의 요약기 · 추정기 ·
  failure store 배선을 바꾸지 못한다
- **시작 비율은 롤링의 값이 아니다.** 두 engine 이 같은 auto 비율 하나를 쓰고 에이전트별로 바꾼다([`compaction.md`](compaction.md)).
  `aimon.context.rolling.auto-compact-ratio` 를 설정하면 기동이 실패한다 — 무시되는 키는 롤링이 여전히 그 비율에서 압축을
  시작하는 것처럼 읽힌다

선택 키(`aimon.context.engine`, AGENT.md `context-engine`)는 [`context-engine.md` §11](context-engine.md#11-선택과-관측) 에 있다.

---

## 12. `SessionHistoryTool`

뷰에서 빠진 원문을 에이전트가 되찾는 도구다(이름 `SessionHistory`, `at.aimon.core.tools.session`).

| 입력 | 뜻 |
|------|----|
| `seq` | 그 메시지 원문(placeholder 가 가리키는 값) |
| `offset` | `seq` 와 함께 — 긴 메시지의 다음 부분 |
| `query` | 부분 문자열 검색(대소문자 무시). `seq` 와 둘 중 하나는 필수 |
| `limit` | 결과 수. 기본 5, 상한 20(`MAX_LIMIT`) |

- **범위** — 현재 세션의 `CONVERSATION` 항목뿐이다. 봉인된 세그먼트까지 읽는다. 세션을 넘는 회상은 `PeerMemory` 의 일이다.
  `/clear` 이전은 보이지 않는다 — reader 는 manifest 만 따른다
- **로그에 닿는 길** — 실행 중인 버퍼(레코드는 한 턴 늦다)와 봉인 구간을 읽을 reader 가 필요하다. 실행기가 메인 루프의 도구
  컨텍스트마다 `SessionLogSource`(버퍼 + 선택적 `SessionLogReader`)를 `SessionHistoryTool.LOG_SOURCE_KEY` 로 넣는다. reader 가
  없으면 봉인 구간은 gap 으로 보고한다. 세션 밖(포크, 스킬 루프)에는 없다
- **검색** — 인덱스가 없으므로 선형 스캔이다. 최근부터 뒤로 64 seq 창씩, `maxScanTokens`(기본 1M)까지만 읽고, 넘으면 "더 오래된
  기록은 검색하지 않았다" 를 결과에 담는다. 여러 창에 걸친 읽을 수 없는 구간은 한 줄로 합쳐 보고한다
- **결과** — 일치한 메시지 주변 ±2 개(대화 메시지). 각 메시지는 `maxResultChars`(기본 2,000자)로 자른다. 그보다 긴 메시지는 잘린
  자리에 남은 글자 수와 다음 부분의 `offset` 을 적는다 — elide 된 원문은 커서 elide 된 것이므로 끝까지 읽을 수 있어야 한다.
  검색 결과 전체는 `SEARCH_RESULT_PARTS`(10) × `maxResultChars` 에 닿으면 더 일치를 붙이지 않고 그 사실을 적는다. 첫 일치는
  언제나 보인다. 넓은 검색이 그것이 대신하는 읽기보다 큰 결과를 돌려주지 않기 위해서다. 방금 받은 도구 결과는 미응답 부분으로
  보호되므로, 큰 원문을 다시 읽는 모델이 같은 elide 에 갇히지 않는다
- **노출** — 돌려주는 것은 모두 예전에 이미 LLM 에 보낸 메시지다. 새로운 노출 경로가 아니다
- **등록** — 해석된 engine 이 rolling 이면 런타임 팩토리가 도구 provider 들 뒤에 직접 등록한다. allow-list 는 여전히 이름으로
  막는다. 기본 engine 에는 등록하지 않는다 — 기본 engine 에는 따라갈 placeholder 가 없다
- **도구 규칙** — `CONCURRENT_SAFE`, 무상태, 읽기 전용이다. 한 호출 안에서만 각 세그먼트를 한 번 읽는 캐시를 쓴다. 예외 금지 ·
  불변 I/O 는 [`tool-development-guide.md`](../../features/tool/tool-development-guide.md) 를 따른다

---

## 13. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 롤링의 전제 | 로그 · 뷰 분리(v2) 뒤에만. v1 쓰기 모드에서 롤링을 배선하면 기동이 실패한다 | **기록을 고쳐 쓰는 모델 위의 롤링** — 압축 빈도가 높을수록 누수 ③~⑤ 도 잦다 |
| span 개수 | 하나 | **여러 span** — 요약끼리의 순서 · 중첩을 관리해야 하고, 누적 갱신이 한 요약을 전제한다 |
| 시작 비율 | 기본 engine 과 같은 auto 비율 하나. 롤링 전용 비율 키는 기동 실패 | **롤링 전용 시작 비율** — 하나의 비율이어야 budget-forced 패스의 warning 이 언제나 같은 auto 에서 파생되어 일반 AUTO 보다 먼저 발동한다. 받아 두고 무시하는 키는 그 비율이 여전히 쓰이는 것처럼 읽힌다 |
| 후퇴의 채택 조건 | 유효 임계값 | **auto** — budget-forced 패스에서 매 iteration 요약을 요약한다([§6](#6-언제--후퇴와-fallback)) |
| 내려갈 수 없는 대화 | 압축하지 않고 `WARN`(`FALLBACK`) | **압축 강행** — 다음 iteration 에 또 압축하고, 성공이라 breaker 도 멈추지 않는다 |
| 롤링 가능성 | 판정 시점에 검사, 불가능하면 기본 engine 동작으로 | **빌드 시점 검사** — 시스템 프롬프트 크기를 모른다. **강행** — `WARN` 에 머물다 blocking 에서 head 까지 삼킨다 |
| "롤링 불가" WARN 의 키 | 모델 이름 | **모델 + 시스템 프롬프트** — 시스템 프롬프트는 실행마다 바뀔 수 있어 끝없이 자란다 |
| tail 의 크기와 절단면 | 토큰 예산, assistant 시작점 허용, USER 경계 스냅 | **메시지 개수** — 도구 결과 하나가 수만 토큰일 수 있다. **USER 경계만** — 한 턴 안에서 수십 iteration 도는 실행의 tail 이 빈다 |
| head 의 판별과 상한 | `origin` 으로 판별, 상한은 `CONVERSATION` 에만 | **텍스트 태그** — mid-turn 사용자 메시지를 오분류한다. **SYNTHETIC 포함 상한** — 그 크기는 조립하는 쪽이 이미 제한한다 |
| 봉인 구간일 수 있는 첫 요청 | head 를 비운다 | **뒤의 user 메시지를 head 로** — 목적이 아닌 메시지가 영원히 원문으로 남는다 |
| 이전 요약의 자리 | 요약 호출의 시스템 프롬프트에 데이터로 | **대화 메시지로** — 요약문이 대화의 한 메시지로 읽히고 PostCompact 의 스캔에 섞인다 |
| 요약 길이 초과 | 재작성하지 않는다 | **재작성 호출** — 요약 호출은 압축당 한 번이다 |
| prune 의 대상 | 이번 절단이 흡수할 구간의 이미 읽은 큰 결과, head 제외 | **미응답 결과 포함** — 모델이 읽기 전에 elide 되고 다시 읽어도 또 elide 된다. **head 포함** — 세션의 목적이고 prefix 캐시의 핵심이다 |
| 미응답 부분만으로 넘친 채 같은 뷰가 다시 옴 | `BLOCK` | **요약을 다시 요약** — 새로 흡수할 것이 없어 아무것도 줄지 않는다 |
| `/compact` 경합 | `tryLock` + `CompactionContendedException` | **기다림 · 조용히 아무것도 안 함** — 사용자가 친 명령이 무엇을 했는지 보여야 한다 |
| 복원 훅 | 등록하지 않음, 등록하면 `SYNTHETIC` | **자동 등록** — 압축마다 파일 원문이 로그에 쌓여 다음 압축을 앞당기고 대화로 ingest 된다 |
| 되찾기 범위 | 현재 세션 | **세션 간 원문 검색** — `WorkspaceAccessPolicy` 가 지키는 격리를 도구 하나가 우회한다. `PeerMemory` 는 "무엇을 알게 되었나", 로그는 "정확히 무엇이 오갔나" 다 — 로그를 세션 밖으로 검색하게 하지 않고, `PeerMemory` 로 원문을 대신하지 않는다 |
| 검색 결과의 크기 | `SEARCH_RESULT_PARTS` × `maxResultChars` 에서 멈춤, 긴 메시지는 `offset` 으로 이어 읽기 | **일치마다 무제한** — `limit` 20 이면 20만 자까지 간다. **잘린 채 끝** — elide 된 큰 원문을 끝까지 읽을 수 없다 |
| 기본 engine 의 되찾기 | 등록하지 않는다 | **기본 engine 에도 등록** — 따라갈 placeholder 가 없다. 전체 compaction 뒤에 원문을 되찾게 할지는 [backlog](../../backlog/agent-execution-open-items.md) 에 있다 |

---

## 14. 하지 말 것

- **span 을 좁히지 않는다.** span 은 넓어지기만 한다. rewind 로 로그가 잘리는 경우만 예외이며
  [`session-log.md` §8.1](../session/session-log.md#81-rewind--truncatefromseq) 이 정한다
- **미응답 부분을 가리거나 흡수하지 않는다.** prune 구간도, 모든 stage 의 절단도 미응답 부분의 시작 이하에서 고른다
- **head 를 prune 하지 않는다.** stage 3(blocking) 만 head 를 span 에 넣는다
- **이전 요약을 대화 메시지로 넣지 않는다.** 마커를 파싱하지도 않는다 — 이전 요약은 뷰 상태의 값이다
- **복원 훅을 `CONVERSATION` 으로 붙이지 않는다.** 대화로 ingest 되고 되찾기에 섞인다
- **v1 쓰기 모드에 롤링을 배선하지 않는다.** 기동이 실패하는 것이 설계다
- **`SessionHistoryTool` 에 상태를 두지 않는다.** 세그먼트 캐시는 한 호출 안에서만 산다

---

## 15. 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 롤링 engine (head · span · tail, prune, 후퇴, FALLBACK, 롤링 가능성, 경합, 복구 위임) | [`RollingContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/RollingContextEngine.java) |
| 뷰 계산 · 미응답 부분 | [`ViewProjection.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ViewProjection.java) |
| 합법 절단면 | [`LegalCuts.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/LegalCuts.java) |
| 임계값 | [`ModelContextLimits.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/ModelContextLimits.java) |
| 요약기 · 롤링 요약 요청 · 끝맺음 지시 | [`DefaultCompactionEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultCompactionEngine.java), [`SummaryRequest.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/SummaryRequest.java) |
| 압축 기록 · 종류 · 경합 실패 | [`CompactionMetadata.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionMetadata.java), [`CompactionKind.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionKind.java), [`CompactionContendedException.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionContendedException.java) |
| 되찾기 도구 · 로그 소스 | [`SessionHistoryTool.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/session/SessionHistoryTool.java), [`SessionLogSource.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogSource.java), [`SessionLogReader.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogReader.java) |
| 등록 · v2 요구 · engine 생성 | [`OrcaAgentRuntimeFactory.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeFactory.java) |
| 조정 값 · 선택 | [`ExecutorSpec.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/spec/ExecutorSpec.java), [`AimonProperties.java`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonProperties.java), [`CliSettings.java`](../../../modules/aimon-cli/src/main/java/at/aimon/cli/config/CliSettings.java) |
| 복원 훅 | [`RecentFilesRestoreHook.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/impl/RecentFilesRestoreHook.java), [`InvokedSkillsRestoreHook.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/impl/InvokedSkillsRestoreHook.java) |
| FALLBACK 을 실행 결과에 싣는 자리 | [`OrcaAgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java) |
