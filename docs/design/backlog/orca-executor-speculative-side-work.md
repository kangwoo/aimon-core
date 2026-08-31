# OrcaAgentExecutor — 투기적 side-work (speculative side-work) 백로그

> Status: **Backlog (의식적 보류)** — 설계는 명확하나 **확정 소비자(consumer)가 없어** 구현하지 않음.
> 출처: `docs/design/agent-execution/orca-executor.md` 의 **G11**. 나머지 gap(G1~G10)은 모두 구현 완료되어 implemented 문서로 이관되었고, G11만 이 백로그로 분리한다.
> 재검토 트리거: 아래 §7 "언제 다시 볼까" 조건이 충족될 때.

---

## 1. 한 줄 정의

툴 배치 실행이 끝난 **직후(iteration 꼬리)**, 다음 iteration 에 쓰일 보조 작업(요약·prefetch)을 **논블로킹으로 미리 시작**해두고 **다음 iteration에서 그 결과를 소비**함으로써, 보조 작업의 지연을 **다음 모델 스트림 뒤에 숨기는** 최적화. 정확성과 무관한 **순수 지연 은닉(latency hiding)** 기법이다.

## 2. 참조 구현(Claude Code `queryLoop`)에서의 모습

> 툴 배치 후 **Haiku 요약 / 메모리·스킬 prefetch**를 **비블로킹 시작**해 다음 iteration에서 소비 → **~1s 지연을 다음 모델 스트림(5~30s) 뒤에 숨김**.

핵심은 **비용 비대칭**이다. 메인 모델 스트림은 느리고(5~30s) 어차피 기다려야 하는 반면, 보조 작업은 값싼 모델/조회로 ~1s면 끝난다. 보조 작업을 메인 스트림에 **겹쳐서** 미리 돌리면 그 1초가 스트림 시간 안에 흡수된다.

```
순차 (현재):
  iter N:   [툴배치]──[요약 1s]────────────[모델스트림 30s]──▶
  iter N+1:                                                  [모델스트림 30s]──▶

투기적 (G11):
  iter N:   [툴배치]──┬─[모델스트림 30s]────────────────▶
                      └─[요약 1s]  ← 백그라운드, 스트림 뒤에 숨음
  iter N+1:            (요약 이미 완료 → 즉시 소비) [모델스트림 30s]──▶
            → 1s가 사라짐 (벽시계만 감소, 결과는 동일)
```

## 3. G7(스트리밍 툴 중첩)과의 차이 — 혼동 주의

이미 구현된 **G7**과 개념이 비슷하나 **중첩 축이 다르다**:

| | **G7 (구현 완료)** | **G11 (본 백로그)** |
|---|---|---|
| 무엇을 겹치나 | **툴 실행** ↔ 토큰 스트림 | **보조 작업**(요약/prefetch) ↔ 토큰 스트림 |
| 시간 범위 | **같은 iteration 내**(intra-iteration) | **다음 iteration에 걸침**(inter-iteration) |
| 소비 시점 | 같은 iteration 의 `STREAM_END` 후 harvest | **다음** iteration 상단 |
| 투기성 | 없음 (모델이 실제 요청한 툴만 실행) | **있음** (미리 계산하나 안 쓸 수도) |
| 코드 | `agent/tool/StreamingToolScheduler.java` | 없음 |

G7은 "모델이 이번 iteration 에 요청한 안전한 툴을 스트림 도중 미리 실행"이고, G11은 "**아무도 아직 요청하지 않은** 보조 산출물을 다음 iteration 을 위해 미리 계산"이다. 그래서 G11은 "안 쓰면 낭비"라는 투기 리스크가 있다.

## 4. hook 지점 (코드 기준)

`OrcaAgentExecutor.executeReActLoop` (`agent/impl/orca/OrcaAgentExecutor.java`) 기준. 아래 라인 번호는 분리 시점의 코드 스냅샷이며 리팩터링에 따라 이동할 수 있다.

**Producer seam (백그라운드 작업 시작 지점)** — iteration 꼬리, 툴 결과가 메모리에 커밋된 직후 (약 `1719`~`1757`):

```
1698  executeToolUses(...)                        → toolUseResults
1714  addMessage(assistant + toolResults)
1718  addMessage(toolUseResults)
      ── 여기부터 iteration 꼬리 ──               ★ G11 producer: 백그라운드 작업 시작
1726  isStalledIteration(...)
1745  injectQueuedMessages(scope)
1757  emitIterationCompleted(willContinue=true)
      ↺ loop back
```

**Consumer seam (미리 계산된 결과 소비 지점)** — 다음 iteration 상단:

```
1547  compactionGuard.maybeCompact(...)   ← pre-computed 요약을 읽을 후보 지점
1616  invokeGateway(...)
```

## 5. 왜 보류인가 — "확정 소비자 부재"

**소비자(consumer)** = 미리 계산해둔 산출물을 **실제로 읽어서 쓰는 코드 지점**. 코드 조사 결과, aimon-core에는 그런 소비자가 오늘 **존재하지 않는다**. 참조 구현의 소비자 후보들이 aimon에서는 전부 다른 형태다:

| 참조의 소비자 후보 | aimon-core 현황 | 소비자가 될 수 있나 |
|---|---|---|
| **Haiku 요약**(툴 출력 사전 요약) | 압축 요약(`DefaultCompactionEngine.compact`, 약 `:216`)이 **메인 모델**로 **동기** 실행. 임계값 gate에서 라이브 메모리로부터 매번 재계산. **pre-computed 요약을 담을 캐시/슬롯이 없음** | 구조적으론 **가장 유력**하나, 소비 슬롯이 없음 |
| **스킬 prefetch** | `SkillPreflightScanner.scan`(약 `:105`)은 **동기 정책 검사**(승인 필요 여부). LLM/prefetch 작업 자체가 없음 | ❌ prefetch 대상 없음 |
| **메모리 prefetch** | `MemoryContextProvider.provide()`가 **턴당 1회**, 루프 **진입 전**(약 `:2761`) 동기 조회. iteration 루프 안에서 반복 조회하지 않음 | ❌ 루프 내 소비자 아님 |
| **값싼 보조 모델**(Haiku) | **없음.** `summaryModel`/`secondaryModel`/`cheap` 필드 전무. 압축도 메인 모델 사용 | ❌ 값싼 모델부터 배선 필요 |
| **백그라운드 executor** | 루프가 소유한 범용 백그라운드 풀 없음. `DefaultParallelToolDispatcher` 풀은 tool-concurrency가 켜졌을 때만 생성되고 eager 툴 디스패치와 공유 | ⚠️ 새 풀 필요 or 조건부 재사용 |

정리: **producer(작업을 시작할 자리)는 명확한데, 그 결과를 쓸 consumer가 코드 어디에도 없다.** consumer 없이 producer만 만들면 계산해놓고 버리는 **죽은 인프라(dead infra)**가 된다.

## 6. 구현한다면 필요한 것 (producer + consumer 한 세트)

1. **소비자를 하나 발명** (문서가 없다고 한 그것). 가장 현실적인 후보: **투기적 압축 요약** — 다음 iteration에서 임계값을 넘길 것으로 예상되면 iteration 꼬리에서 L3 요약을 **미리 계산**해 슬롯에 저장 → 다음 compaction gate가 라이브 재계산 대신 그 슬롯을 소비. (`DefaultCompactionEngine`에 pre-computed 요약 캐시 슬롯 추가 필요)
2. **값싼 보조 모델 배선** (참조의 Haiku 자리). 현재 없음.
3. **백그라운드 executor** — 새로 만들거나 `DefaultParallelToolDispatcher` 풀을 조건부 재사용.
4. **무효화/취소 정책** — 투기 결과는 그 사이 **큐 입력 주입·인터럽트·메모리 변경**으로 무효화될 수 있다. staleness 판정 + 취소 로직 필수. (틀린 요약을 소비하면 정확성이 깨진다)
5. **SPI 분리** (multi-instance/SOLID 관용구): `SpeculativeSideWork` 인터페이스 + no-op 기본값 + opt-in 배선. 기본 미배선 시 완전 무회귀.

## 7. 가치 / 비용 / 리스크 & 언제 다시 볼까

| 축 | 평가 |
|---|---|
| **가치** | **벽시계 지연만** 절감(요약/prefetch 소비 기준 ~1s급). 정확성·기능 이득 없음. 실측 이득은 "소비자가 실제로 매 iteration 무거운 보조 작업을 하는가"에 전적으로 의존 — 현재 그런 소비자 부재 → **이득 0** |
| **비용** | **높음.** 새 SPI + 소비자 발명 + 값싼 모델 배선 + 백그라운드 풀 + 무효화/취소 정책 |
| **리스크** | 투기 실패 시 **토큰 낭비**, stale 결과 소비 시 **정확성 훼손**, inter-iteration 상태 공유로 **복잡도·경쟁조건** 증가 |

**언제 다시 볼까 (재검토 트리거)** — 아래 중 하나라도 실제로 생기면 소비자가 확정되어 G11이 유의미해진다:

- 매 iteration마다 **값싼 모델 기반 툴 출력 요약**을 실제로 수행하는 경로가 생겼을 때(투기적 압축 요약의 소비 슬롯).
- 루프 내에서 **iteration마다 메모리/스킬을 반복 조회**하는 소비자가 생겼을 때.
- 값싼 **보조 모델**이 다른 목적으로 이미 배선되어 재사용 비용이 낮아졌을 때.

## 부록 — 참조 구현 파일 맵

- 투기 side-work 개념: `query.ts`(툴 배치 후 비블로킹 side-work 시작), `cost-tracker.ts`(값싼 모델 usage)
- aimon 대응 지점: `agent/impl/orca/OrcaAgentExecutor.java`(producer 꼬리·consumer 상단), `agent/compact/DefaultCompactionEngine.java`(요약 소비 후보), `agent/tool/StreamingToolScheduler.java`(G7 — 축이 다른 기존 지연 은닉)
