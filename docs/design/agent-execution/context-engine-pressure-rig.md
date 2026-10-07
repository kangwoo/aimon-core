# 설계 — ContextEngineLiveRig 의 engine 비교 과제 (뷰 압력 리그)

> Status: **IMPLEMENTED** — 적용 대상은 `aimon-llm-openai` · `aimon-llm-anthropic` 의 테스트 소스와 문서뿐이다. 프로덕션 코드는
> 바뀌지 않았다. 기준선 수치는 아직 재지 않았다([`CP-1`](../../backlog/context-engine-pressure-rig-open-items.md)).
> §1 ~ §8 은 리뷰를 통과한 설계 그대로다(기준 커밋 `a65392d0`, 승인 뒤 고치지 않았다). 구현이 그 글에서 갈라진 자리는
> [§9](#9-구현이-이-설계와-갈라진-자리)에 있다. 지금의 리그를 서술하는 글은 이 기록이 아니라
> [`context-engine.md` §13.11](context-engine.md#1311-engine-을-비교하는-과제) 이다.

대상 브랜치 `herdr/context-engine-pressure-rig` (기준 `a65392d0`). 이 문서는 설계만 담는다. 코드 조각은 모양을 보이기 위한 것이다.
2차 판이다 — `review-1.md` 의 blocking 셋을 모두 받아들여 고쳤고, 무엇이 바뀌었는지는 §8 에 있다.

읽고 근거로 삼은 것: `TASK.md`, 두 모듈의 `ContextEngineLiveRig` · `ContextEngineLiveRigTest` · `*ContextEngineLiveTest`,
`docs/design/backlog/self-managed-context-engine.md`(§1.1, §12), `docs/design/agent-execution/context-engine.md`(§4, §5.5, §5.6, §6, §13),
`docs/backlog/live-api-test-tier.md`, `CONTRIBUTING.md` 의 Live-API tests 절, `ReleaseGateMatchesCiGateTest` 의 키 게이트 인구조사,
`aimon.java-conventions.gradle.kts`, 네 testkit 모듈의 빌드 스크립트, `RollingContextEngine`(판정 순서 `:401-405`, `spanPlan` ·
`prunePlan` `:880-930`) · `ModelContextLimits` · `CompactionMetadata` · `OrcaAgentExecutionResult` · `SessionHistoryTool` ·
`LlmClient` 의 공개 표면. `docs/design/testing/release-gate-provider-keys.md` 는 **읽지 않았다**(§3.2 의 HANDOFF 항목).

---

## 1. 문제

`self-managed-context-engine.md` §12 는 세 번째 engine 의 착수 조건을 "이 저장소의 과제에서 이득이 재어졌을 때" 하나로 두었는데,
지금 `ContextEngineLiveRig` 가 하는 일은 **프로바이더가 engine 의 요청을 받아 주는가**와 **한 개의 사실이 롤링 사이클을 넘어
돌아오는가**의 통과/실패 확인뿐이다. 두 engine 을 같은 입력으로 돌려 정답률과 비용을 나란히 놓는 과제가 없고, 기존 리그의 두
engine 은 서로 다른 창 위에 있어(롤링은 8K 창에 비율 0.3, 기본 engine 은 프레임워크 창 표라서 AUTO 가 절대 안 걸린다) 지금
배선으로는 비교 자체가 성립하지 않는다. 이 작업은 뷰 압력을 매개변수로 받는 세 과제(사실 보존 · 키-값 조회 · 로그 triage)와,
그것을 `default` · `rolling` 에 똑같이 주고 정답률 + 관측 수치를 내는 러너, 키 없이 배선과 채점을 고정하는 쌍둥이, 키 게이트를
따르는 라이브 클래스를 두 프로바이더 모듈의 **테스트 소스에만** 더한다. 새 engine 도, 프로덕션 코드 변경도, 이 실행에서의
과금 호출도 없다. 기준선 수치는 사용자가 잰다.

---

## 2. 접근

### 2.1 한눈에

```
ContextPressureTasks (순수, 시드 고정)          ContextEngineLiveRig.forComparison(kind, ...)
  needle / keyValue / logTriage                   같은 창 · 프로덕션 기본 비율 · 프로덕션과 같은 도구 배선
        |  ContextPressureTask (불변)                      |
        +--------------------+-----------------------------+
                             v
                  ContextPressureRun.run(task, rig)
        압력 단계(턴마다 fetch 1회) -> 질문 단계(턴마다 질문 1개) -> 채점
                             v
                  ContextPressureResult  ->  build/reports/context-engine-pressure/<provider>.md
```

- **과제는 스크립트다.** 사용자 입력의 순서와 도구가 돌려주는 본문이 전부 `(과제 종류, 시드, 압력)` 에서 결정된다. 모델이 정하는
  것은 답과, 무엇을 다시 찾아 읽을지뿐이다. 같은 `ContextPressureTask` 인스턴스 하나를 두 engine 의 리그에 차례로 준다(기준 3)
- **압력 `p`** = 과제가 로그에 밀어 넣는 입력(사용자 입력 + 도구 결과)의 추정 토큰 합 ÷ effective window. 추정은 engine 이 임계값
  판정에 쓰는 것과 같은 `HeuristicTokenEstimator` 다 — 압축을 일으키는 것이 그 숫자이기 때문이다. 생성기는 합이 `p × effective`
  이상이 될 때까지 **압력 단위**(도구 결과 하나)를 더한다. 실제로 도달한 압력은 실행에서 다시 재어 결과에 싣는다
- **수준** — `0.3`(대조), `2`, `4`. 대조 수준에서는 어느 engine 도 압축하지 않아야 한다. 여기서 틀리면 그 모델은 압력과 무관하게
  과제를 못 푸는 것이고, 높은 수준의 실패를 engine 탓으로 돌릴 수 없다. **0.5 가 아니라 0.3 인 이유**: 뷰에는 `plannedTokens` 가
  세지 않는 것(시스템 프롬프트, fetch 마다의 `tool_use`, 모델의 답, 메시지 overhead)이 얹히고 생성기는 한 단위까지 넘친다. 0.5 면
  keyValue 의 뷰가 약 8,100 으로 롤링 임계값 8,400 에 수백 토큰 차로 붙는다. 0.3 은 계획 4,200, 뷰 약 4,700 으로 3,700 이 남는다.
  그래도 대조에서 압축이 일어나면(모델이 군말을 길게 하거나 필요 없는 되찾기를 한다) **단언하지 않고** 그 칸을
  `INVALID_CONTROL` 로 기록한다(§4.5)
- **채점은 문자열 대조다.** 정답은 생성된 토큰(`QX7-MULE-4417` 꼴)이고 질문 하나에 턴 하나를 쓴다. LLM 심판은 없다
- **라이브 클래스는 정답률을 단언하지 않는다.** 기준선이 아직 없고, 정답률은 재는 대상이지 통과 조건이 아니다. 단언하는 것은
  하네스 불변식뿐이고(§6.3), 결과는 보고서 파일로 나간다

### 2.2 비교 프로파일 — 두 engine 을 같은 창에

기존 시나리오의 숫자(8K 창, 롤링 0.3 / 0.1 / 0.08 / 0.04 / prune 300, 기본 engine 은 프레임워크 창 표)는 "몇 턴 안에 롤링 사이클에
닿는다" 를 위해 고른 것이라 비교에는 쓰지 않는다. **기존 팩토리 `rolling(...)` · `defaultViewMode(...)` 와 그 숫자, 그것이 만드는
배선은 한 줄도 바꾸지 않는다** — 기존 라이브 테스트와 쌍둥이가 그 위에서 돈다. 비교용 팩토리를 하나 더한다.

| 항목 | 값 | 근거 |
|------|----|------|
| 창 | `contextWindow 16_000`, `reservedOutputTokens 2_000` → effective 14,000 | 아래 "왜 16K 인가" |
| 버퍼 | `autoCompactBuffer 1_000`, `warningBuffer 1_000`, `blockingBuffer 400` | 200K 기본값의 비례(약 6.5% / 10% / 1.5%)를 따른다 |
| 기본 engine | `DefaultCompactionGuard(compactionEngine, 위 창, estimator)` + `DefaultContextEngine`(`writeFormat(V2)`) | AUTO 13,000, blocking 13,600. 뷰 모드 조건(§13.2)은 기존 `viewModeEngine` 과 같다 |
| 롤링 engine | `RollingContextEngine.builder()` 에 **비율을 주지 않는다** — `DEFAULT_*` 그대로(0.6 / head 0.05 / tail 0.20 / summary 0.08 / minTail 0.05 / prune 500) | 재려는 것은 배포되는 설정이다. rollingAuto 8,400, warning 7,400, tail 2,800, 요약 목표 1,120 |
| 도구 | 두 engine 모두 `fetch_report`(**id 마다 한 번만 내준다**, §2.5). `SessionHistoryTool` 은 **롤링에만** | `OrcaAgentRuntimeFactory` 가 그렇게 등록한다(`context-engine.md` §6 · §13.4). 되찾기 유무는 engine 의 일부다 |
| 시스템 프롬프트 | 두 engine 에 **같은 문장**, 도구 이름을 부르지 않는다 (§4.3) | 기존 `SYSTEM_PROMPT` 는 `SessionHistory` 를 이름으로 부르는데 기본 engine 에는 그 도구가 없다 |
| 에이전트 | `maxIterations(6)` 그대로 | 질문 턴 하나에서 되찾기 5회까지. 넘으면 그 질문은 `FAILED` 로 기록된다 |

- **롤링이 이 창을 감당한다.** `minAfter ≈ system(~150) + head(<200) + 1,120 + 700 ≈ 2.2K < warning 7.4K` (`context-engine.md` §5.6).
  감당하지 못하면 롤링은 조용히 기본 engine 으로 물러나고 리그는 기본 engine 을 두 번 재게 된다 — 쌍둥이가 "롤링 실행에
  `FULL` 기록이 없다" 로 붙든다(§6.2)
- **왜 16K 인가 (8K 가 아니라).** `SessionHistory` 검색 결과의 상한은 창에 비례하지 않는 절대값이다 —
  `SEARCH_RESULT_PARTS(10) × DEFAULT_MAX_RESULT_CHARS(2,000자)` = 20,000자 ≈ 5.7K 토큰. 질문은 뷰가 임계값(8,400) 바로 아래일 때도
  도착하므로 "바닥 + 결과 < blocking" 은 조건이 아니다(8,400 + 5,700 > 13,600). 실제로 받쳐 주는 것은 **미응답 부분을 tail 로 삼는
  span 계획**이다 — 그 결과가 미응답인 `prepare` 에서 롤링은 그 앞을 전부 흡수할 수 있고, 채택 조건은
  `system + head + 요약 예산 1,120 + 미응답 ~5,750 ≈ 7,100 < 8,400` 이다(`RollingContextEngine.spanPlan`, `:890`). 8K 창에서는 같은
  합(요약 예산 560 으로 약 6,500)이 임계값 4,200 을 넘어 계획이 없고, blocking(6,700) 에 닿으면 미응답 부분만으로 넘칠 때의 `BLOCK`
  이다(§13.10). 그러면 "롤링이 사실을 잃었다" 가 아니라 "리그의 창이 되찾기 도구보다 작다" 를 재게 된다. 모델이 고르는 검색어는
  통제할 수 없으므로 최악의 결과가 들어갈 자리를 창 쪽에서 낸다
- **롤링은 prune 을 먼저 한다 — 그래서 압력 단위의 크기가 무엇을 재는지를 정한다.** `RollingContextEngine` 은 임계값에서
  `prunePlan` 을 먼저 시도하고, 가리는 것만으로 임계값 아래로 내려가면 **요약하지 않는다**(`:401-405`, `context-engine.md` §5.5).
  500 토큰 이상의 도구 결과로 만든 압력은 전부 placeholder 로 접힌다 — 가려진 턴 하나는 약 83 토큰(지시 ~33, `tool_use` ~23,
  placeholder ~20, `received` ~7)이라, 1,200 토큰 단위로는 수준 4(46 단위)에서도 뷰가 약 7,300 으로 8,400 아래에 머문다. 요약은 약
  66 턴(수준 6 근처)부터다. 기존 리그의 `fillerNote` 가 산문인 이유가 이것이다(`ContextEngineLiveRig.java:293-294`). 이 설계는 두
  크기를 과제별로 나눠 쓴다
  - **큰 단위 — 약 1,200 토큰(약 4,200자), keyValue · logTriage.** prune 대상(≥ 500), 미응답 결과 하나가 tail 예산(2,800)을 넘지
    않게 effective 의 15%(2,100) 이하, `SessionHistory` 의 2,000자 절단보다 길어 `offset` 으로 이어 읽는 경로가 쓰인다. 이 두
    과제의 롤링 기준선은 **prune 기준선**이다 — 제공하는 수준에서 압력 단계의 롤링은 요약하지 않는다. 그것이 큰 도구 결과에 대한
    롤링의 실제 동작이고 M3("placeholder 로 바꾸거나 그대로 둔다")가 말하는 바로 그 자리다
  - **작은 단위 — 약 420 토큰(약 1,450자), needle.** `TOOL` 메시지의 추정 토큰이 **`pruneMinTokens`(500) 미만**이어서 prune 이
    지우지 못한다. 롤링은 요약해야 한다 — 계획 입력 약 4,100 토큰마다 한 사이클(임계값 8,400 − 압축 뒤 바닥 약 4,300)이라 수준
    2 에서 약 6 회다. needle 의 사실은 사용자 메시지라 어차피 prune 대상이 아니고, 이렇게 해야 사실이 실제로 span 에 흡수된다
  - 정확한 값은 빌드에서 쌍둥이로 맞추고 상수 둘로 둔다. 범위(작은 것 < 500, 큰 것 500 ~ 2,100)는 테스트가 붙든다(§6.1)

### 2.3 세 과제

공통: 1턴은 사실이 없는 브리핑이다(§2.4 의 head 결정). 압력 단계의 턴은 모두
`Call the fetch_report tool with report_id "<id>". After reading it, reply with only the word: received.` 한 문장이고, 질문 단계의 턴은
질문 하나에 `Reply with the value only. If you cannot find it, reply UNKNOWN.` 이 붙는다. 질문은 **출처의 위치로 층화**한다 —
이른 것(높은 압력에서 요약/prune 되는 구간), 가운데, 늦은 것(tail 안).

| 과제 | 사실이 놓이는 자리 | 압력을 만드는 것 | 질문 (기본 개수) | 기본 engine 에서 재는 것 | 롤링에서 재는 것 |
|------|-------------------|------------------|------------------|--------------------------|------------------|
| **needle** (사실 보존) | 2 ~ 7턴의 **사용자 메시지** — `Note N: the <role> for <thing> is <value>. Reply with only: noted.` | 사실과 무관한 **작은** 운영 리포트(기존 `plantedReport` 의 INFO 줄 모양, 단위 약 420 토큰) | 사실마다 하나 (6) | `FULL` 요약이 사실을 지키는가. 되찾을 길이 없다 | **`ROLLING` 요약**이 사실을 지키는가(M2), 잃었으면 `SessionHistory` 검색으로 되찾는가. prune 은 일어나지 않는다 |
| **keyValue** (키-값 조회) | **도구 결과** — 페이지마다 `svc.<name>.<attr> = <value>` 줄 약 100개. 페이지가 곧 압력 단위다(큰 단위) | 페이지 자체 | 층마다 고른 키 (8). 키마다 한 글자만 다른 이웃 키가 같은 페이지에 있다 | 요약이 수백 줄 가운데 물을 줄을 지키는가 | **prune 이 가린 결과**에서 한 줄을 되찾는가(M3) — 검색, 또는 placeholder 의 seq 로 읽기. 페이지 안 위치에 따라 `offset` 읽기가 한두 번 더 든다. 요약은 압력 단계에 없다 |
| **logTriage** (로그 triage) | **도구 결과** — 서비스 · 시간대별 로그 덤프(큰 단위). INFO 잡음 속에 WARN/ERROR 줄 몇 개(호스트, 오류 코드, 요청 id) | 덤프 자체 | (6) 세 꼴을 층마다: 덤프를 지목한 조회(`In dump L-3, which host logged E-4417?`), 지목하지 않은 조회(`Which request id hit the disk-full error?`), 같은 오류 코드가 다른 덤프의 다른 호스트에 미끼로 있는 조회 | 위와 같다. 읽고 지나간 덤프가 임계값 13,000 까지 실려 가는 비용(M1) | 위와 같다. 덤프를 지목하지 않은 질문은 내용으로 검색해야 한다 |

- **M2 를 읽는 자리는 needle 이다.** `EARLY` 에서만 정답률이 떨어지면 요약이 위치 때문에 사실을 잃은 것이다. keyValue · logTriage
  의 롤링 줄에서 `EARLY` 가 떨어지면 그것은 요약이 아니라 **가려진 결과를 되찾지 못한** 것이다(M3 쪽)
- 생성기가 지키고 테스트가 붙드는 불변식: 모든 정답 값은 말뭉치 전체에서 **정확히 한 번** 나온다, 어떤 질문 문장에도 정답이나
  미끼 값이 들어 있지 않다, 미끼 값은 정답과 같은 꼴이고 서로 다르며 어느 쪽도 다른 쪽의 부분 문자열이 아니다, 값은 사전 단어 +
  숫자로 만들어 모델이 맞혀 낼 수 없다

### 2.4 결정과 기각한 대안

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 과제 코드가 사는 자리 | **두 모듈에 사본** (패키지 줄과 클래스 이름 접두만 다르다). 어긋남은 말뭉치 지문으로 붙든다(§4.4) | **새 testkit 모듈** — 코드가 `src/main` 에 놓이고 `settings.gradle.kts` · 빌드 스크립트 · 두 모듈의 의존이 바뀐다. 기준 7("테스트 소스와 문서에 한한다")과 기준 6("이미 있으면 거기")을 둘 다 어긴다 — 있는 자리가 아니라 만드는 자리다. 리그는 `at.aimon.core.agent.impl.orca` 를 직접 쓰는데 testkit 의 main 소스는 `.impl` 경계 검사의 대상이 된다. **`aimon-llm-capability-testkit`** — 이미 있지만 주제가 "설정 표면 → `ModelCapabilityDeclaration` 바인딩 계약" 이고 두 프로바이더 모듈은 거기 의존하지 않는다. **`aimon-core` 의 테스트 소스** — 다른 모듈이 볼 수 없다. `java-test-fixtures` 는 네 testkit 의 빌드 스크립트가 적어 둔 대로 publishing 플러그인 아래에서 구성이 실패한다 |
| 사본의 어긋남 | 쌍둥이가 `(과제, 시드, 압력)` 의 **말뭉치 SHA-256 지문**을 리터럴과 대조. 두 모듈의 리터럴이 같다 | **`aimon-core` 에서 두 소스를 읽어 비교** — 지금도 두 사본은 패키지 이름 길이 때문에 포매터가 주석을 다르게 접는다(`ContextEngineLiveRig.java:93-94`). 정규화 규칙이 필요해지고, 지켜야 할 것은 소스의 글자가 아니라 **두 프로바이더가 받는 입력이 같다**는 것이다 |
| engine 설정 | 같은 창, 롤링은 프로덕션 기본 비율 (§2.2) | **기존 리그의 숫자 재사용** — 롤링 0.3 은 아무도 배포하지 않는 설정이고 기본 engine 은 AUTO 가 걸리지 않는다. **`OrcaAgentRuntimeFactory` 와 실제 창** — 사이클 하나에 수십만 토큰이다(기존 리그 javadoc 의 이유 그대로) |
| 창 크기 | 16K | **8K** — 되찾기 결과 하나를 미응답으로 둔 span 계획이 서지 않는다(§2.2). 비용은 절반이다. **32K**(논문의 상한) — 비용이 두 배이고 16K 로 위 조건이 이미 선다. 열린 질문 Q1 |
| 압력을 만드는 방법 | 턴마다 도구 호출 하나, 지시문 고정 | **한 턴에 여러 개를 가져오게 한다** — 모델이 병렬로 부르면 결과 셋이 `TOOL` 메시지 **하나**로 붙어(실행기의 동작) 미응답 부분이 tail 보다 커지고, 순차로 부르면 그렇지 않다. 같은 과제의 모양이 모델에 따라 달라진다 |
| 롤링이 요약하게 만드는 방법 (needle) | 도구 결과를 **500 토큰 아래**로 | **1,200 토큰 단위 그대로** — prune 이 전부 접어 롤링이 한 번도 요약하지 않고, 사용자 메시지인 사실은 원문으로 남아 롤링이 과제와 무관하게 100% 를 받는다(1차 판의 결함). **산문 사용자 메시지**(기존 `fillerNote`, 호출 수 절반) — 과제 정의가 "무관한 **도구 결과**로 뷰를 채운다" 다. **수준을 6 이상으로** — 큰 단위로 span 에 닿으려면 약 66 턴이고, 그 뒤에도 사이클이 드물어 비용 대비 요약 표본이 적다. **큰 것과 작은 것을 섞는다** — 호출은 줄지만 한 칸에서 prune 과 요약이 함께 일어나 어느 쪽이 사실을 잃었는지 읽기 어렵다 |
| keyValue · logTriage 의 롤링이 요약하지 않는 것 | 받아들이고 **prune 기준선**이라고 적는다(§2.3, §4.5, §5.3) | **이 둘도 작은 단위로** — "큰 로그 덤프" 가 아니게 되고, 큰 결과에 대한 롤링의 실제 동작(prune)을 리그에서 지운다. 수준 6 ~ 8 을 주면 prune 에서 span 으로 넘어가는 자리를 볼 수 있다 — 수준 목록이 매개변수이므로 막지 않되 기본 권장에는 넣지 않는다 |
| 다시 가져오기 | `fetch_report` 는 id 마다 **한 번만** 내준다. 두 번째부터는 짧은 고정 문장. 시도는 센다 (§2.5) | **세기만 한다** — 기본 engine 의 모델이 `fetch_report("L-3")` 를 다시 불러 답하면 engine 이 무엇을 지켰는지와 무관한 정답이 되고, 그런 답을 빼면 표본이 준다. **그대로 둔다** — 두 engine 이 다시 가져오기로 동률이 되고 "기본 engine 은 아무것도 잃지 않는다" 로 읽힌다 |
| 대조 수준 | 0.3, 압축이 일어나면 `INVALID_CONTROL` 로 기록 | **0.5** — 롤링 임계값까지 수백 토큰이다(§2.1). **대조의 "압축 0" 을 라이브에서 단언** — 모델의 말수에 달린 단언이다 |
| 사실을 head 에 두는가 | 두지 않는다. 1턴은 사실 없는 브리핑 | **첫 사용자 메시지에 사실** — 롤링의 head 는 첫 `CONVERSATION` USER 항목을 원문으로 고정하므로(§5.2 · §13.6) 롤링이 과제와 무관하게 100% 를 받는다. 실제 차이이기는 하나 한 메시지의 특권이고 재려는 것(요약 · prune · 되찾기)을 가린다 |
| 질문하는 방법 | 질문 하나에 턴 하나 | **마지막 턴 하나에 전부** — 호출은 줄지만 답을 줄 단위로 파싱해야 하고, 질문별 되찾기 횟수와 실패한 턴이 구별되지 않는다. 질문 턴은 압력 단계에 비해 싸다(턴당 1 ~ 3 호출) |
| 표본 수 | 실행당 질문을 늘린다(과제당 6 ~ 8). 시드는 매개변수, 기본 하나 | **같은 과제를 여러 번** — 압력 단계를 통째로 다시 낸다. 질문을 늘리는 쪽이 같은 돈으로 표본이 많다. 반복은 시드를 늘려서 한다 |
| 채점 | 정규화한 최종 답에 정답이 있고 미끼가 없으면 `CORRECT` | **LLM 심판** — 과금 호출이 늘고 결정적이지 않다. 정답이 생성된 토큰이므로 필요 없다. **정답 포함만 검사** — 후보를 전부 나열한 답이 맞게 된다 |
| 라이브 클래스의 단언 | 하네스 불변식만. 수치는 보고서로 | **정답률 하한을 단언** — 기준선이 없다. 그리고 `live-api-test-tier.md` LA-1 이 적은 이 계층의 썩음 세 건 중 둘이 "상태만 요구할 자리에서 문장을 대조" 한 것이다. 모델의 기분으로 빨개지는 단언을 더하지 않는다 |
| 결과가 나가는 자리 | 모듈의 `build/reports/context-engine-pressure/` 아래 Markdown 파일. 칸(과제 × engine × 수준)이 끝날 때마다 다시 쓴다 | **표준 출력** — conventions 플러그인이 `showStandardStreams = false` 다. **끝에 한 번 쓰기** — 중간에 429 나 예외로 죽으면 이미 돈을 낸 칸의 결과를 잃는다 |
| 과금 실행의 opt-in | 프로바이더 키 게이트(`@EnabledIfEnvironmentVariable`) **그대로**, 그 안에서 `AIMON_CONTEXT_PRESSURE` 가 없으면 `assumeTrue` 로 건너뛴다. 값이 곧 수준 목록(`0.3,2`) | **키 게이트만** — `CONTRIBUTING.md` 가 적어 둔 대로 키를 export 한 셸의 모든 `./gradlew test` · `checkAll` 이 라이브 클래스를 돈다. 기존 계층은 한 번에 5만 토큰 아래인데 이 클래스는 수백만 토큰이다(§5.1). **새 `@EnabledIfEnvironmentVariable` 변수** — `ReleaseGateMatchesCiGateTest` 의 인구조사가 실패하고 `scripts/release.sh` 를 고쳐야 한다(기준 7 밖). **JUnit 태그나 시스템 속성** — `test` 태스크의 제외나 `-D` 전달은 빌드 스크립트 변경이다. 열린 질문 Q2 |

### 2.5 `fetch_report` 는 한 번만 내준다

1차 판의 `fetch_report` 는 말뭉치 전체를 몇 번이든 내주는 사본이었다. 시스템 프롬프트가 "가진 도구로 찾아보라" 고 하고 logTriage 의
질문은 덤프 id 를 부르므로, 기본 engine 의 모델은 engine 이 무엇을 지켰는지와 무관하게 다시 가져와서 답할 수 있었다.

- **동작.** 비교 리그의 `fetch_report` 는 id 를 처음 받으면 본문을, 그 뒤로는
  `Report <id> was already delivered earlier in this conversation. The source does not serve a report twice.` 를 돌려준다(오류가 아닌
  결과 — 오류면 모델이 재시도하기 쉽다). 모르는 id 는 지금처럼 `no entries` 다. 한 번 흘러가면 다시 오지 않는 출처(회전한 로그,
  소비된 큐)의 모양이다
- **상태가 사는 자리.** "이미 내준 id" 는 리그가 가진 `ReportDesk`(스레드 안전한 집합)에 있고 도구는 그것을 읽는다. 도구 개발
  규칙의 "무상태" 는 프로덕션 도구의 것이고, 이것은 리그 인스턴스 하나의 수명 안에서 출처를 흉내 내는 테스트 대역이다. 리그마다
  새 `ReportDesk` 이므로 두 engine 의 실행은 서로 영향을 주지 않는다. `reloadThroughCodec` 는 비교 리그에서 쓰지 않는다
- **기존 시나리오는 그대로다.** `rolling(...)` · `defaultViewMode(...)` 가 만드는 `ReportTool` 은 지금처럼 `R-1` 을 몇 번이든 내준다
- **센다.** 거절된 호출은 `refetchAttempts` 로 질문마다 · 칸마다 기록한다(§4.5). 압력 단계에서 같은 id 를 두 번 부른 것도 여기 든다
- **남는 틈.** 모델이 아직 내주지 않은 id 를 추측해 부를 수는 없다 — 과제의 모든 id 는 압력 단계에서 한 번씩 소비된다(건너뛴
  fetch 는 예외이고, 그 칸은 `skippedFetches` 로 보인다)

---

## 3. 파일별 변경

두 모듈에 같은 변경이다. 아래 경로의 `<P>` 는 `modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai` 와
`modules/aimon-llm-anthropic/src/test/java/at/aimon/core/llms/anthropic` 이다. **`src/main`, 빌드 스크립트, `scripts/` 는 건드리지 않는다.**

### 3.1 테스트 소스

| 파일 | 새로/수정 | 내용 |
|------|-----------|------|
| `<P>/ContextEngineLiveRig.java` | 수정 | ① `forComparison(ContextEngineKind, LlmClient, LlmModel, Path, Map<String,String> reports)` 팩토리와 비교 프로파일 상수(§2.2). ② `ReportTool` 이 말뭉치 `Map<id, 본문>` 과 선택적 `ReportDesk` 를 받는다 — 기존 팩토리는 `Map.of("R-1", plantedReport())` 와 desk 없음을 넘기므로 동작이 같다. ③ **`forComparison` 에서만** 실행기에 넘기는 클라이언트를 `MainCallRecorder` 로 감싼다(§4.2). 기존 두 팩토리의 실행기는 지금처럼 감싸지 않은 클라이언트를 받는다. ④ `tryTurn(String)` — 실패를 단언하지 않고 결과를 돌려준다. 기존 `turn` 은 그대로. ⑤ 접근자 `effectiveWindow()`, `mainCalls()`, `reportDesk()`(비교 리그가 아니면 비어 있다). 클래스 javadoc 의 "What the numbers do" 에 비교 프로파일을 한 문단 더한다 |
| `<P>/ContextPressureTasks.java` | 새로 | 순수 코드(리그 · 프로바이더 의존 없음). 과제 값 타입, 세 생성기, 채점, 지문 (§4.1, §4.3, §4.4) |
| `<P>/ContextPressureRun.java` | 새로 | 러너와 결과 타입, 보고서 렌더러 (§4.2, §4.5) |
| `<P>/ContextPressureTasksTest.java` | 새로 | 키 없음. 생성의 결정성 · 불변식 · 지문 · 채점 (§6.1) |
| `<P>/ContextPressureRunTest.java` | 새로 | 키 없음. **쌍둥이** — stub 모델로 세 과제 × 두 engine (§6.2) |
| `<P>/OpenAIContextPressureLiveTest.java`, `<P>/AnthropicContextPressureLiveTest.java` | 새로 | 키 게이트 + opt-in. 수준 × 과제 × engine 을 돌려 보고서를 쓴다 (§6.3). 모델과 `maxTokens` 는 같은 모듈의 `*ContextEngineLiveTest` 와 같다(`gpt-4o-mini` / 1000, `claude-haiku-4-5-20251001` / 2000) |

기존 `ContextEngineLiveRigTest` 와 두 `*ContextEngineLiveTest` 는 고치지 않는다.

규칙: 값 타입은 `record` 가 아니라 불변 `final class` 다(`CLAUDE.md` — 예외는 `GenericTool` 입력 DTO 뿐이고 테스트 소스에도 예외를
두지 않는다). 필드가 많은 결과 타입은 빌더를 쓴다. 타입 이름에 맨 `Session` 을 쓰지 않는다. 도구 클래스 이름은 `Tool` 로 끝난다.
`turn` 은 사용자 입력 1건의 뜻으로만 쓴다(용어집 §4) — 러너의 "압력 단계의 턴" · "질문 턴" 이 그 뜻이다.

### 3.2 문서

| 파일 | 정본/번역 | 변경 |
|------|-----------|------|
| `docs/design/agent-execution/context-engine.md` | 한국어 정본, 번역본 없음 | 새 절 **§13.11 engine 을 비교하는 과제** — 압력의 정의, 비교 프로파일과 그 근거(16K 의 조건은 §2.2 에 적은 span 계획의 식으로), prune 이 먼저라서 단위 크기가 재는 것을 정한다는 것과 과제별 단위, 세 과제의 정의, 한 번만 내주는 `fetch_report`, 채점, 관측 수치의 뜻, 리그가 재지 **않는** 것(§5.3). 부록의 참조 파일 지도에 새 파일 |
| `docs/design/backlog/self-managed-context-engine.md` §12 | 한국어 정본, 번역본 없음 | "`ContextEngineLiveRig` 에는 engine 을 비교하는 과제가 없다 … 먼저 있어야 한다" 는 이 작업 뒤에는 거짓이다. "과제는 생겼고(`context-engine.md` §13.11) **기준선은 아직 재지 않았다**" 로 고친다. 착수 조건과 Status 줄은 그대로다 — 재어진 값이 없다 |
| `CONTRIBUTING.md` → `CONTRIBUTING.ko.md` | **영어 정본**, 한국어 번역 | Live-API tests 절: 표에 두 클래스(여섯 → 여덟), 이 둘은 키만으로는 돌지 않고 `AIMON_CONTEXT_PRESSURE` 가 있어야 돈다는 것, 실행 명령, 비용의 규모, 보고서 위치와 읽는 법. 기존 명령 블록에는 넣지 않고 별도 블록으로 둔다 — 기존 계층을 돌리는 사람이 이 비용을 함께 내지 않게. 번역본을 같은 커밋에서 고치고 구조 여섯 축을 맞춘다 |
| `CHANGELOG.md` | 영어, 번역본 없음 | `[Unreleased]` 에 한 항목 — 기존 "Live tests for the context engines" 항목의 모양을 따른다 |
| `docs/backlog/live-api-test-tier.md` LA-1 "언제 다시 볼까" 2 | 한국어 정본, 번역본 없음 | 날짜 붙인 한 줄 — 클래스가 둘 늘었고, opt-in 없이는 건너뛰므로 이 계층의 **기본 한 번 실행 비용은 그대로**라는 것. 그 트리거가 "표를 고치는 사람이 이 자리를 지나간다" 고 적고 있다 (Q7) |

`docs/**` 의 세 파일에는 `*.en.md` 가 없다(확인함: `docs/design/agent-execution/`, `docs/design/backlog/`, `docs/backlog/`).
`docs/features/agent-execution/context-engine-guide.md` 는 번역본이 있지만 **고치지 않는다** — 사용 가이드이고 리그는 사용자 기능이 아니다.
문서를 고친 뒤 `check-doc-links.py` · `check-translation-structure.py` · `check-translation-staleness.py` 를 돌린다.

HANDOFF(빌드 단계의 산출물)에 적을 것:

- 실행 명령(§5.1)과 보고서의 칸 읽는 법(§4.5), §5.3 의 한계
- 쌍둥이가 센 수준별 호출 수 — 읽는 자리는 §6.2 의 `keyless-call-counts.md` 다
- `docs/design/testing/release-gate-provider-keys.md:18-19` 가 라이브 클래스를 "넷" 이라고 적고 있다는 것(리뷰 1 이 본 것. 이미
  `CONTRIBUTING.md` 의 여섯과 어긋나 있고 이 작업으로 여덟이 된다). 이 작업이 고치지는 않는다 — 세 번째 숫자를 만들지 않으려고
  적어만 둔다

---

## 4. 데이터와 인터페이스의 모양

전부 테스트 소스의 package-private 타입이다. 공개 API 와 SPI 는 바뀌지 않는다.

### 4.1 과제

```
ContextPressureTask                      (불변)
  kind            : NEEDLE | KEY_VALUE | LOG_TRIAGE
  seed            : long
  pressure        : double               요청한 수준
  systemPrompt    : String               두 engine 에 같은 것 (4.3)
  reports         : Map<String,String>   fetch_report 가 돌려줄 id -> 본문
  steps           : List<Step>           압력 단계의 사용자 입력. Step { input, fetchedReportId? }
  questions       : List<Question>
  plannedTokens   : int                  생성기가 센 입력 토큰 합
  unitTokens      : int                  압력 단위 하나의 추정 토큰 (needle 은 < 500, 나머지는 500 ~ 2,100)

Question
  id, prompt      : String
  expected        : String
  distractors     : Set<String>          같은 꼴의 다른 값
  stratum         : EARLY | MIDDLE | LATE
  source          : IN_USER_MESSAGE | IN_TOOL_RESULT, sourceReportId?, sourceCharOffset
```

```java
// 생성: 순수 함수. 같은 인자에 같은 과제.
static ContextPressureTask needle(long seed, double pressure, int effectiveWindow, TokenEstimator estimator);
static ContextPressureTask keyValue(...);   // 같은 서명
static ContextPressureTask logTriage(...);
```

난수는 `new SplittableRandom(seed)` 하나에서만 나온다. `Math.random`, 시각, `Map` 의 순회 순서에 기대지 않는다(`LinkedHashMap`).

### 4.2 리그와 러너

```java
// ContextEngineLiveRig
static ContextEngineLiveRig forComparison(ContextEngineKind kind, LlmClient client, LlmModel model,
        Path baseDir, Map<String, String> reports);
OrcaAgentExecutionResult tryTurn(String input);   // isSuccess() 를 단언하지 않는다
MainCallRecorder mainCalls();
ReportDesk reportDesk();                           // served(id), refusedCount()
int effectiveWindow();

// forComparison 의 실행기에 넘기는 클라이언트를 감싼다. 요약 호출은 SummaryCallRecorder, 나머지는 여기.
static final class MainCallRecorder implements LlmClient {
    int count();
    List<Integer> sentViewTokens();        // 호출마다 estimator.estimate(systemPrompt, messages)
    long reportedPromptTokens();           // LlmResponse.getTokenUsage().getPromptTokens() 의 합. stub 에서는 0
    int toolUsesNamed(String toolName);    // 응답이 요청한 tool_use 를 이름으로 센다
}

// ContextPressureRun
static ContextPressureResult run(ContextPressureTask task, ContextEngineKind kind, ContextEngineLiveRig rig);
```

**`MainCallRecorder` 는 `SummaryCallRecorder` 를 본뜨지 않는다.** 실행기는 `LlmCallGateway` 를 거쳐 클라이언트를 부르고, 그것이
쓰는 오버로드는 셋이다(리뷰 1 이 읽어 확인: `LlmCallGateway.java:257`, `:401`, `:568`) — `String` 시스템 프롬프트 오버로드,
`sendMessage(SystemPromptParts, messages, tools, model, metadata, cancellation)`, `sendMessageStreaming(...)`. 뒤의 둘은 `LlmClient`
의 default 메서드이고 프로바이더 클라이언트가 재정의한다(캐시 경계, 취소, 스트리밍). `SummaryCallRecorder` 처럼 `String` 오버로드만
감싸면 default 구현이 parts 를 이어 붙여 `String` 오버로드로 내려보내므로 **프로바이더가 받는 요청의 모양이 바뀐다** — 리그가 재는
것이 감싸지 않은 클라이언트가 보냈을 요청이 아니게 된다. recorder 는 세 오버로드를 각각 **같은 오버로드로** 넘기고, 세 자리에서
같은 기록 함수를 부른다. stub 으로는 드러나지 않는 종류의 결함이므로, 쌍둥이에 "세 오버로드 각각으로 부른 호출이 대역의 같은
오버로드에 닿고 한 번씩 세어진다" 는 경우를 둔다(§6.2).

### 4.3 고정 문장

```
SYSTEM  You are a terse assistant in a test. Follow the user's formatting instructions exactly. Earlier parts of this
        conversation may no longer be shown to you. When you need something that is not shown, use the tools you have
        to look it up. If you cannot find it, reply UNKNOWN - do not guess.
```

질문 문장도 도구를 이름으로 부르지 않는다. 두 engine 이 받는 **글**은 같고, 다른 것은 도구 목록뿐이다(§2.2).

### 4.4 채점과 지문

```
verdict(question, finalAnswer, turnSucceeded):
  FAILED     턴이 실패했다 (BLOCK, 프로바이더 오류, 최대 iteration)
  CORRECT    norm(answer) 가 norm(expected) 를 포함하고, 어떤 distractor 도 포함하지 않는다
  ABSTAINED  UNKNOWN 을 포함하거나, 값 꼴의 토큰이 하나도 없다
  WRONG      그 밖 - 다른 값을 답했다
norm: 대문자로, 공백 · 백틱 · 따옴표 · 끝의 마침표 제거
```

`WRONG` 과 `ABSTAINED` 를 나누는 것은 M2 가 말하는 실패("요약자의 말로 바뀐다")가 침묵이 아니라 **틀린 값**으로 나타나는지를
보기 위해서다.

```
fingerprint(task) = SHA-256( systemPrompt, steps[].input, reports 를 id 순으로, questions[].(prompt, expected, distractors) )
```

두 모듈의 `ContextPressureTasksTest` 가 `(과제, 시드 1, 압력 2)` 세 개의 지문을 **같은 리터럴**과 대조한다. 한쪽 사본의 생성기만
고치면 그 모듈의 테스트가 빨개진다. 고친 사람은 리터럴을 고쳐야 하고, 실패 메시지가 다른 모듈의 리터럴도 고치라고 말한다.

### 4.5 결과와 보고서

```
ContextPressureResult                    (칸 하나: 과제 x engine x 수준 x 시드)
  provider, model, kind(task), engine, seed
  pressureRequested, pressureAchieved    achieved = 러너가 실제로 준 입력의 추정 토큰 합 / effective
  unitTokens
  status                                 OK | INVALID_CONTROL | INVALID_PRESSURE | ERROR(message)
  answers[]                              질문별 verdict, stratum, 그 턴의 iteration 수,
                                         SessionHistory 호출 수, refetchAttempts
  correct / wrong / abstained / failed, accuracy = correct / questions
  compactions                            kind 별 수 (PRUNE, ROLLING, FULL, FALLBACK), trigger 별 수, isOverBlockingLimit 수
  compactionsInPressurePhase             위와 같은 kind 별 수를 압력 단계의 것만
  summaryCalls, summaryFailures          SummaryCallRecorder
  mainCalls                              MainCallRecorder.count()
  sentViewTokens                         합 / 최대 / 호출당 평균 (추정)
  reportedPromptTokens                   프로바이더가 센 입력 토큰 합
  sessionHistoryCalls, refetchAttempts
  skippedFetches, failedTurns
```

- `INVALID_CONTROL` — 대조 수준(1 미만)의 칸에서 압축 기록이 하나라도 있다. 단언이 아니라 기록이다
- `compactionsInPressurePhase` 를 따로 두는 것은 질문 단계의 되찾기 결과가 일으킨 압축과 압력이 일으킨 압축을 가르기 위해서다

수치의 출처는 전부 리그가 이미 볼 수 있는 것이다 — `OrcaAgentExecutionResult.getCompactionEvents()`(`CompactionMetadata` 의 kind ·
trigger · `isOverBlockingLimit()`), `getIterationCount()`, 두 recorder, `ReportDesk`. 프로덕션 코드에 관측 지점을 더하지 않는다.

보고서 `build/reports/context-engine-pressure/<provider>-<model>.md` 는 표 둘이다 — 칸마다 한 줄인 요약(위 필드)과, 질문마다 한
줄인 상세(과제, engine, 수준, 질문 id, stratum, verdict, 되찾기 수, 다시 가져오기 시도 수). 머리에 프로파일의 숫자, 과제별 단위
크기, 시드, 수준, 날짜를 적는다. 읽는 법:

- **먼저 걸러 낸다.** `status` 가 `OK` 가 아닌 줄, `failedTurns > 0`, 롤링 줄의 `FULL > 0`(기본 engine 으로 물러남)
- **대조 수준(0.3)의 칸이 `OK` 이고 정답률 1.0 이어야** 그 과제 · 모델 · engine 의 높은 수준 결과를 engine 비교로 읽는다.
  `INVALID_CONTROL` 이면 대조가 대조가 아니었다
- **needle 의 롤링 줄은 요약을 잰다.** 압력 단계의 `ROLLING ≥ 1`, `PRUNE = 0` 이어야 한다. 정답률을 stratum 별로 보고, `EARLY`
  에서만 벌어지면 M2 다
- **keyValue · logTriage 의 롤링 줄은 prune 을 잰다.** 제공하는 수준에서는 압력 단계가 `PRUNE ≥ 1`, `ROLLING = 0` 이다 — 여기서
  롤링의 오답은 "요약이 잃었다" 가 아니라 "가려진 결과를 되찾지 못했다" 다. 질문 단계에서 `ROLLING` 이 생길 수 있고(되찾기 결과가
  뷰를 민다) 그것은 `compactions` 와 `compactionsInPressurePhase` 의 차로 보인다
- `sentViewTokens` 합이 비용의 대리값이다. M1 은 "정답률은 같은데 합이 크다" 로 나타난다
- 롤링의 `sessionHistoryCalls` 는 정답률을 사는 데 든 값이다. 기본 engine 에는 그 도구가 없으므로 언제나 0 이다
- `refetchAttempts` 는 **어느 engine 에서든 0 이 아닐 수 있다** — 모델이 다시 가져오려 했고 거절당한 횟수다. 답을 바꾸지는 못하지만
  (본문이 오지 않는다) 그 engine 의 뷰에서 사실이 사라졌다는 것을 모델이 행동으로 보인 것이고, 그 시도의 iteration 과 토큰은
  `mainCalls` · `sentViewTokens` 에 이미 들어 있다. 기본 engine 의 `ABSTAINED` 옆에 이 수가 높으면 "되찾을 길이 있었다면 썼을 것"
  으로 읽는다

---

## 5. 실패 모드

### 5.1 비용

쌍둥이가 stub 으로 센 호출 수가 정확한 하한이다(§6.2, HANDOFF 에 옮긴다). 아래는 설계 시점의 **어림**이고 가정은 턴당 2 호출,
압축이 도는 수준의 평균 뷰 약 7K 다. needle 은 단위가 작아 같은 압력에 턴이 약 2.7 배 든다 — 롤링이 요약하게 만드는 값이다(§2.2).

| 수준 | needle 한 칸 (주 호출 / 입력) | keyValue · logTriage 한 칸 | 세 과제 × 두 engine |
|------|-------------------------------|----------------------------|---------------------|
| 0.3 | 약 35 / 약 0.1M | 약 25 / 약 0.08M | 약 0.5M |
| 2 | 약 140 / 약 1M | 약 70 / 약 0.35M | 약 3.4M |
| 4 | 약 265 / 약 1.9M | 약 115 / 약 0.8M | 약 7M |

프로바이더 하나에 세 수준을 다 돌리면 입력 약 11M 토큰이다. 기존 라이브 계층 전체("well under 50,000 tokens")의 이백 배쯤이다.
그래서 opt-in 이 있고(§2.4), 값이 수준 목록이라 `0.3,2`(약 4M)로 먼저 재고 `4` 는 따로 낼 수 있다.

```bash
ANTHROPIC_KEY=... AIMON_CONTEXT_PRESSURE=0.3,2 \
./gradlew :aimon-llm-anthropic:test --rerun \
    --tests 'at.aimon.core.llms.anthropic.AnthropicContextPressureLiveTest'
```

`--rerun` 은 필수다 — 환경 변수는 `test` 의 입력이 아니다(`live-api-test-tier.md` §0.3). **이 실행(설계 · 빌드 · 리뷰)에서는 이
명령을 치지 않는다.** 빌드 에이전트는 키가 환경에 있더라도 `AIMON_CONTEXT_PRESSURE` 를 설정하지 않는다. 다만 키가 export 된 셸에서
`checkAll` 을 돌리면 **기존** 라이브 클래스 여섯이 도는 것은 이 작업과 무관하게 그대로이므로, 빌드 에이전트는 `checkAll` 을
`env -u ANTHROPIC_KEY -u OPENAI_KEY` 로 돌린다.

### 5.2 실행 중

| 무엇이 | 어떻게 드러나고 어떻게 다루나 |
|--------|------------------------------|
| 모델이 지시받은 `fetch_report` 를 부르지 않는다 | 그 단위가 로그에 없다. 다시 시키지 않는다(입력이 두 engine 에 달라진다). `skippedFetches` 를 세고, 도달 압력이 요청의 90% 아래면 `INVALID_PRESSURE` |
| 모델이 이미 받은 리포트를 다시 가져오려 한다 | 본문 대신 고정 문장을 받는다(§2.5). `refetchAttempts` 로 질문마다 · 칸마다 보인다. 그 답의 verdict 는 평소대로 채점한다 |
| 대조 수준에서 압축이 일어난다 | `INVALID_CONTROL`. 테스트는 빨개지지 않는다. 읽는 법이 그 칸과 그 위 수준을 거른다(§4.5) |
| 압력 단계의 턴이 실패한다 (429, 프로바이더 오류, `BLOCK`) | 그 칸을 `ERROR(message)` 로 닫고 **다음 칸으로 간다.** 보고서는 칸마다 다시 쓰므로 앞 칸의 결과가 남는다. 재시도는 프로바이더 클라이언트의 것 외에 더하지 않는다 |
| 질문 턴이 실패한다 (최대 iteration, 되찾기 결과로 `BLOCK`) | 그 질문만 `FAILED`. 나머지 질문은 계속한다 — 최대 iteration 으로 끝난 턴은 로그를 도구 결과로 끝내 두고 다음 질문의 사용자 메시지가 그 뒤에 붙는데, 그 뷰로 다음 턴이 도는지는 쌍둥이가 한 경우로 붙든다(§6.2). 돌지 못하면 러너는 남은 질문을 `FAILED` 로 채우고 칸을 닫는다 |
| 롤링이 기본 engine 으로 물러난다 | 롤링 칸에 `FULL` 기록이 생긴다. 쌍둥이가 0 임을 단언하고, 라이브 보고서에는 그 수가 그대로 나온다 |
| needle 의 단위가 500 토큰을 넘어 prune 된다 | 롤링이 요약하지 않고 needle 이 아무것도 재지 않는다(1차 판의 결함). `ContextPressureTasksTest` 가 단위 크기를, 쌍둥이가 needle · 롤링 · 수준 2 의 `PRUNE = 0`, `ROLLING ≥ 1` 을 붙든다. 라이브 보고서에서는 §4.5 의 읽는 법이 본다 |
| 요약 호출이 거절되거나 실패한다 | `summaryFailures`. 연속 실패로 breaker 가 열리면 뷰가 자라 `BLOCK` 에 닿고 위의 턴 실패로 이어진다 |
| 답에 군말이 붙는다 | 정규화 + 포함 검사. 정답은 다른 문장에 우연히 나올 수 없는 토큰이다 |
| 후보를 전부 나열한 답 | 미끼가 들어 있으므로 `WRONG` |
| 사본이 어긋난다 | 지문(§4.4). 채점 · 러너의 어긋남은 지문이 잡지 못한다 — 두 쌍둥이가 같은 단언을 하는 것이 전부다 |
| recorder 가 요청의 모양을 바꾼다 | §4.2. 세 오버로드를 같은 오버로드로 넘기고 쌍둥이의 한 경우가 그것을 붙든다 |
| 보고서를 못 쓴다 | 테스트를 실패시킨다. 돈을 낸 결과를 조용히 잃는 것보다 낫다. 경로는 Gradle 이 테스트의 작업 디렉터리로 두는 모듈 디렉터리 기준이다 |
| 키는 있는데 opt-in 이 없다 | `assumeTrue` 로 건너뛰고, 메시지가 변수 이름과 `CONTRIBUTING.md` 의 절을 말한다 |
| `AIMON_CONTEXT_PRESSURE` 의 값이 숫자 목록이 아니다 | 호출하기 전에 실패한다. 0 이하와 8 초과도 거절한다 — 오타 하나가 수천 호출이 되지 않게 |

### 5.3 리그가 재지 않는 것 (문서에 적는다)

- **한 번의 실행은 한 표본이다.** 모델은 결정적이지 않고 과제당 질문은 6 ~ 8 개다. 한 칸의 차이 한두 문제는 잡음이다
- **큰 도구 결과에 대한 롤링의 요약은 재지 않는다.** keyValue · logTriage 의 롤링 줄은 제공하는 수준에서 prune 기준선이다. 롤링의
  요약이 사실을 지키는지는 needle 만 본다 — 사용자 메시지에 놓인 사실, 500 토큰 아래의 도구 결과로 만든 압력
- **다시 가져올 수 있는 출처는 재지 않는다.** `fetch_report` 는 한 번만 내준다. 실제 에이전트의 도구 다수는 다시 부를 수 있고, 그
  경우 뷰에서 잃은 것의 값은 "다시 부르는 비용" 이지 오답이 아니다
- **창이 16K 다.** `self-managed-context-engine.md` §12 가 적은 대로 M1 ~ M3 은 128K ~ 200K 에서 훨씬 늦게 나타난다. 이 리그는
  "압력이 같을 때 두 engine 이 어떻게 다른가" 를 재고, "실제 창에서 그 압력에 얼마나 자주 닿는가" 는 재지 않는다
- **압력은 추정 토큰이다.** 프로바이더가 센 값과 다르다. 둘을 나란히 싣는다
- **턴 사이의 압력만 만든다.** 한 턴 안에서 iteration 이 수십 번 도는 실행(도구 결과가 연달아 쌓이는 모양)은 이 과제에 없다
- **§12 의 둘째 물음**("모델이 크기 알림을 보고 실제로 편집하는가")은 범위 밖이다

---

## 6. 테스트 전략

### 6.1 `ContextPressureTasksTest` — 순수, 키 없음

- **결정성** — 같은 `(종류, 시드, 압력)` 으로 두 번 생성한 과제가 같다. 시드가 다르면 지문이 다르다
- **지문** — 세 과제의 `(시드 1, 압력 2)` 지문이 리터럴과 같다(두 모듈에 같은 리터럴)
- **압력** — `plannedTokens ≥ pressure × effective` 이고 한 단위 이상 넘지 않는다. 수준 0.3 / 2 / 4 에 대해
- **대조의 여유** — 수준 0.3 에서 세 과제 모두 `plannedTokens ≤ 0.6 × rollingAuto(8,400)`. 단위 크기나 대조 수준을 누가 바꿔 여유가
  사라지면 여기서 빨개진다
- **단위 크기** — needle 의 모든 `fetch_report` 본문은 `TOOL` 메시지로 추정해 **500 토큰 미만**, keyValue · logTriage 는 500 이상
  2,100 이하. 경계는 `RollingContextEngine.DEFAULT_PRUNE_MIN_TOKENS` 를 읽어 쓴다 — 리터럴로 적으면 기본값이 바뀔 때 조용히 어긋난다
- **불변식** — 정답이 말뭉치에 정확히 한 번, 질문 문장에 정답 · 미끼 없음, 미끼가 정답과 다르고 서로 부분 문자열이 아님, 세 stratum
  이 모두 있음
- **채점** — `CORRECT`(군말 · 백틱 · 소문자 포함), `WRONG`(미끼만, 정답 + 미끼), `ABSTAINED`(`UNKNOWN`, 빈 답, 값 꼴 없는 문장),
  `FAILED`

### 6.2 `ContextPressureRunTest` — 쌍둥이, 키 없음, `./gradlew test` 에 든다

`forComparison` 리그를 **스크립트 모델**로 돈다. 모델은 과제의 질문 → (찾을 키, 정답이 있는 줄) 표를 받고 협조적인 모델처럼
행동한다 — fetch 지시에는 `tool_use`, 그 결과에는 `received`, 질문에는 보이는 뷰에서 줄을 찾아 답하고, 없으면 도구 목록에
`SessionHistory` 가 있을 때만 검색하고, 결과가 잘렸으면 **줄을 찾을 때까지 `offset` 을 따라 읽고**(4,200자 페이지는 두 번까지 더.
`maxIterations(6)` 안에 든다), 그래도 없으면 `UNKNOWN`. 도구 없는 호출은 요약 호출이고 두 방식 중 하나로 답한다 —
`LOSSY`(사실을 전부 뺀 한 문장. 기존 쌍둥이와 같다), `FAITHFUL`(입력 메시지와, 롤링이 요약 호출의 시스템 프롬프트에 넣는
`<<<PREVIOUS_SUMMARY>>>` 에서 사실 줄을 뽑아 싣는다 — 이전 요약의 사실을 다음 요약으로 옮기지 않으면 두 번째 사이클에서 잃는다).
행동을 바꾼 변종이 셋 있다 — 다시 가져오는 모델, fetch 를 건너뛰는 모델, 던지는 모델.

| 경우 | 단언 |
|------|------|
| 세 과제 × 두 engine, 수준 0.3 | 전부 `CORRECT`, 압축 0, `sessionHistoryCalls` 0, `refetchAttempts` 0, `status = OK`. 보낸 뷰의 최대가 `0.75 × rollingAuto` 이하(대조의 여유를 실행에서도 붙든다) |
| **needle, 롤링, 수준 2, `LOSSY`** | 압력 단계에서 **`ROLLING ≥ 2`, `PRUNE = 0`**, `FULL = 0`(물러나지 않았다). `EARLY` 질문은 뷰에 없어 `SessionHistory` 로 되찾는다 — `EARLY` 답마다 `sessionHistoryCalls ≥ 1`, 전부 `CORRECT`. `summaryFailures = 0`, 요약 요청이 모두 user/tool 로 끝남 |
| **needle, 롤링, 수준 2, `FAITHFUL`** | 압력 단계 `ROLLING ≥ 2`. 전부 `CORRECT` 이고 **`sessionHistoryCalls = 0`** — 사실이 요약을 거쳐 뷰에 남았다. 요약 대역의 두 방식이 롤링에서도 갈린다 |
| needle, 기본, 수준 2, `LOSSY` | `FULL ≥ 1`, `sessionHistoryCalls = 0`, `EARLY` 질문은 전부 `ABSTAINED`, 정답률 < 1 — 채점과 러너가 "잃었다" 를 실제로 보고한다 |
| needle, 기본, 수준 2, `FAITHFUL` | 전부 `CORRECT` — 기본 engine 이 0 점만 받게 배선된 것이 아님을 보인다 |
| **keyValue · logTriage, 롤링, 수준 2, `LOSSY`** | 압력 단계에서 **`PRUNE ≥ 1`, `ROLLING = 0`**, `FULL = 0` — prune 기준선임을 붙든다. `EARLY` 질문은 가려진 결과에서 되찾아 `CORRECT`(`sessionHistoryCalls ≥ 1`, 페이지 뒤쪽 키는 한 답에 2 회 이상). 질문 단계의 `ROLLING` 은 단언하지 않는다(경계에 걸린다) |
| keyValue · logTriage, 기본, 수준 2, `LOSSY` | `FULL ≥ 1`, `sessionHistoryCalls = 0`, `EARLY` 는 전부 `ABSTAINED`, 정답률 < 1. `MIDDLE` · `LATE` 는 단언하지 않는다 — 마지막 `FULL` 이 질문 단계에 얼마나 가까운지에 달렸다 |
| **다시 가져오는 모델**, logTriage, 기본, 수준 2 | 뷰에 없으면 `fetch_report(<그 덤프>)` 를 다시 부른다. 본문이 오지 않는다 — `EARLY` 는 `ABSTAINED`, 그 답들의 `refetchAttempts ≥ 1`, 칸의 합이 `ReportDesk.refusedCount()` 와 같다. 같은 모델을 롤링에 주면 거절 뒤 `SessionHistory` 로 가서 `CORRECT` 이고 `refetchAttempts ≥ 1` 이 함께 남는다 |
| 수치의 일관성 (위 실행들에서) | `pressureAchieved ≥ pressureRequested`, `mainCalls` 가 `sentViewTokens` 의 길이와 같음, 보낸 뷰의 최대가 blocking 한계 이하, 두 engine 이 받은 사용자 입력 열이 같음, `compactionsInPressurePhase ≤ compactions` |
| 한 질문 턴이 최대 iteration 으로 끝난다 (그 질문에서만 끝없이 검색하는 모델, 롤링) | 그 질문은 `FAILED`, **다음 질문이 돌고 채점된다**, `failedTurns = 1` |
| `MainCallRecorder` 의 세 오버로드 | 각 오버로드로 한 번씩 부르면 대역의 **같은** 오버로드가 한 번씩 불리고(parts 가 이어 붙여지지 않는다) `count() = 3` |
| fetch 를 건너뛰는 모델 | `skippedFetches > 0`, `status = INVALID_PRESSURE` |
| 대조 수준에서 압축을 일으키는 모델 (답마다 긴 군말) | `status = INVALID_CONTROL`, 러너와 테스트가 실패하지 않음 |
| 압력 단계에서 던지는 모델 | `status = ERROR`, 러너가 던지지 않음 |
| 보고서 | `@TempDir` 에 렌더한 Markdown 이 칸마다 한 줄, 질문마다 한 줄을 가진다 |

절단면의 정확한 위치에 기대는 단언(예: "3번 질문은 `WRONG`")은 쓰지 않는다 — 요약 길이가 한 토큰 달라지면 깨진다. stratum 단위와
부등식으로 적고, 빌드에서 더 조이지 않는다.

**호출 수를 읽는 자리.** 이 클래스는 세 수준 × 세 과제 × 두 engine 의 stub 실행에서 센 `mainCalls` · `summaryCalls` 를
`build/reports/context-engine-pressure/keyless-call-counts.md` 에 쓴다(수준 4 도 stub 으로는 싸다). `TestReporter` 는 쓰지 않는다 —
그 항목은 XML 보고서에만 남고 콘솔에는 나오지 않는다(`showStandardStreams = false`). 빌드 에이전트는 이 파일을 읽어 HANDOFF 의
비용 줄과 §5.1 의 표(문서에 옮길 때)를 채운다.

### 6.3 `*ContextPressureLiveTest` — 키 게이트 + opt-in. **이 실행에서 돌리지 않는다**

테스트 메서드는 하나다(칸을 나누면 Gradle 이 일부만 다시 돌릴 때 보고서가 반쪽이 된다). 단언:

- 수준 2 이상의 `OK` 인 칸에서 **압력 단계의** 압축이 1 회 이상이다. 모델의 기분에 달리지 않는다 — `OK` 는 도달 압력이 요청의 90%
  이상이라는 뜻이고, 창의 1.8 배가 넘는 입력은 어느 engine 에서든 임계값을 넘는다
- 보고서 파일이 쓰였고 돌린 칸 수만큼 줄이 있다

대조 수준의 "압축 0" 은 **단언하지 않는다** — `INVALID_CONTROL` 로 기록한다. 정답률, `status`, 되찾기 수, 다시 가져오기 시도 수도
단언하지 않는다 — 보고서의 내용이다.

### 6.4 게이트

`./gradlew format` → `env -u ANTHROPIC_KEY -u OPENAI_KEY ./gradlew checkAll`. 새 `@EnabledIfEnvironmentVariable` 변수가 없으므로
`ReleaseGateMatchesCiGateTest` 의 인구조사는 그대로 통과한다(기존 두 변수만 본다). 그 테스트의 입력에 프로바이더 모듈의 테스트
소스가 들어 있으므로 이 변경으로 다시 돈다. 커버리지 하한은 main 소스의 것이고 테스트만 더하므로 내려가지 않는다. 문서 검사 셋(§3.2).

---

## 7. 열린 질문

과제 설명만으로는 정할 수 없어서, 아래 기본값으로 설계했다. 답이 다르면 표시한 자리만 바뀐다.

- **Q1. 창 크기와 비용.** 16K 로 잡았다(§2.2 의 근거: 되찾기 결과 하나를 미응답으로 둔 span 계획이 서야 한다). 8K 면 비용이
  절반이지만 롤링이 되찾기 한 번에 `BLOCK` 될 수 있고, 32K 면 논문의 조건과 같지만 두 배다. 세 수준을 다 돌리면 프로바이더당 입력
  약 11M 토큰이라는 어림이 받아들일 만한가 — 그 가운데 절반 남짓이 needle 이고, 그것은 롤링이 요약하게 만드는 값이다.
  *바뀌는 자리: 프로파일 상수 하나와 단위 크기 둘.*
- **Q2. opt-in 변수.** 기준 5 는 "기존 키 게이트를 **그대로**" 다. 설계는 키 게이트를 그대로 두고 그 안에 `AIMON_CONTEXT_PRESSURE`
  를 더했다 — 게이트를 넓히지 않고 좁히며, 릴리스 스크립트는 키가 있으면 이미 거부하므로 릴리스 게이트가 이 클래스를 돌 길은
  없다. 다만 인구조사는 애노테이션만 읽으므로 이 변수는 거기 잡히지 않는다. "그대로" 가 "키만으로 돈다" 를 뜻한다면 opt-in 을 빼고
  기본 수준을 `0.3,2` 로 고정해야 하고, 그러면 키를 export 한 셸의 모든 `checkAll` 이 프로바이더당 약 4M 토큰을 쓴다.
  *바뀌는 자리: 라이브 클래스의 `assumeTrue` 한 줄과 `CONTRIBUTING` 의 문단.*
- **Q3. 재는 모델.** 기존 라이브 클래스의 모델(`gpt-4o-mini`, `claude-haiku-4-5`)을 따랐다. §12 의 조건은 "쓰려는 모델" 인데 그것이
  이 둘인지는 과제 설명에 없다. 더 큰 모델이면 비용 어림이 한 자릿수 달라진다. *바뀌는 자리: 라이브 클래스의 상수.*
- **Q4. `SessionHistory` 의 비대칭.** 프로덕션 배선대로 롤링에만 등록했다. "같은 과제를 똑같이 준다" 를 도구 목록까지로 읽는다면
  기본 engine 에도 등록한 칸이 하나 더 필요하다 — 그것은 어떤 조립도 만들지 않는 구성이라 넣지 않았다.
- **Q5. 사실을 head 밖에 둔 것.** 롤링의 head 고정이 주는 이득을 과제에서 뺐다(§2.4). 그 이득도 기준선에 넣고 싶다면 needle 에
  "head 에 놓인 사실" 질문 하나를 stratum `HEAD` 로 더하면 된다.
- **Q6. 롤링의 비율.** 프로덕션 기본값을 썼다. 16K 창에서 tail 은 2,800 토큰(큰 단위 둘)이다. 이 프로젝트의 실제 배포가
  `withRollingContextEngineCustomizer` 로 다른 비율을 쓴다면 그 값이어야 한다 — 저장소에서는 그런 설정을 찾지 못했다.
- **Q7. `live-api-test-tier.md` 에 한 줄.** LA-1 은 닫힌 항목이다. 재검토 트리거 2 가 "표를 고치는 사람이 이 자리를 지나간다" 고
  적고 있어 날짜 붙인 한 줄을 더하기로 했는데, 닫힌 항목에 덧붙이는 것이 백로그 등록 규칙에 맞는지는 `docs/backlog/README.md` 를
  끝까지 대조하지 않았다. 빌드에서 그 규칙을 읽고 맞지 않으면 뺀다.
- **Q8. 한 번만 내주는 출처.** 다시 가져오기를 막았다(§2.5). 그 결과 이 리그의 기본 engine 은 실제보다 불리하다 — 실제 도구
  다수는 다시 부를 수 있다. 세 번째 engine 이 겨룰 상대가 "다시 부르면 되는 기본 engine" 이라고 본다면 막지 않고 세기만 한 줄이
  필요하다. *바뀌는 자리: `forComparison` 이 `ReportDesk` 를 넘기는가 하나.*
- **Q9. keyValue · logTriage 에서 롤링의 요약을 보지 않는 것.** prune 기준선으로 받아들였다(§2.4). 큰 결과가 span 에 흡수된 뒤의
  정답률까지 보려면 수준 6 ~ 8 을 돌려야 하고(변수 값으로 가능하다) 한 칸이 입력 1.5M 토큰을 넘는다.

빌드에서 맞출 것(추정으로 남긴 것이 아니다): 두 단위 크기와 질문 수의 정확한 값(§6.1 의 범위 안에서 쌍둥이로 맞춘다), 비용
어림의 실제 호출 수(쌍둥이가 세어 파일로 낸다).

---

## 8. 리뷰 1 에서 바뀐 것

blocking 셋은 모두 옳았고 반박할 것이 없어 `rebuttal-1.md` 는 쓰지 않았다. 리뷰가 인용한 `RollingContextEngine` 의 판정 순서
(`:401-405`)와 `prunePlan` · `spanPlan`(`:880-930`)은 다시 읽어 확인했다.

| 리뷰의 지적 | 고친 것 | 자리 |
|-------------|---------|------|
| **B1** 이 프로파일에서 롤링은 압력 단계에 한 번도 요약하지 않는다 — prune 이 전부 흡수한다 | 둘 다 했다. needle 은 prune 이 지우지 못하는 **500 토큰 아래의 도구 결과**로 압력을 만들어 요약 사이클이 실제로 돈다. keyValue · logTriage 는 **prune 기준선**이라고 명시하고 과제의 표적 · 읽는 법 · 쌍둥이의 단언을 그에 맞게 다시 썼다. `FAITHFUL` 요약 대역이 롤링에서도 쓰인다. 비용 표를 다시 계산했다(약 7 ~ 8M → 약 11M) | §2.2, §2.3, §2.4, §4.5, §5.1, §5.3, §6.1, §6.2, Q9 |
| **B2** `fetch_report` 는 다시 부를 수 있는 말뭉치 사본이고 설계가 그것을 다루지 않는다 | 비교 리그의 `fetch_report` 는 id 마다 한 번만 내준다. 시도는 질문마다 · 칸마다 `refetchAttempts` 로 센다. 읽는 법을 적었고("기본 engine 은 언제나 0" 은 `sessionHistoryCalls` 에만 남는다), 쌍둥이에 다시 가져오는 스크립트 모델을 두 engine 에 대해 넣었다. 그 선택이 기본 engine 을 불리하게 만든다는 것을 한계와 열린 질문에 적었다 | §2.5, §3.1, §4.2, §4.5, §5.2, §5.3, §6.2, Q8 |
| **B3** 대조 수준의 "압축 0" 여유가 수백 토큰이고 라이브 클래스가 그것을 단언한다 | 대조를 0.5 → **0.3** 으로 내렸다(여유 약 3,700). 라이브의 단언을 빼고 `INVALID_CONTROL` 상태로 기록한다. 읽는 법이 대조의 정답률과 함께 상태를 본다. 여유는 생성 테스트와 쌍둥이가 숫자로 붙든다 | §2.1, §2.4, §4.5, §5.2, §6.1, §6.2, §6.3 |

non-blocking 여덟도 받아들였다.

- `MainCallRecorder` 가 넘겨야 할 오버로드 셋을 리뷰가 읽은 대로 적고 "빌드에서 확인" 항목을 지웠다. 쌍둥이의 한 경우로 붙든다 (§4.2, §6.2)
- recorder 는 `forComparison` 에서만 감싼다 — 기존 두 팩토리의 배선은 글자 그대로 그대로다 (§2.2, §3.1)
- "왜 16K" 의 조건을 "바닥 + 결과 < blocking" 에서 미응답을 tail 로 둔 span 계획의 식으로 고쳤다 (§2.2, §3.2)
- stub 은 줄을 찾을 때까지 `offset` 을 따라 읽는다 (§6.2)
- 최대 iteration 으로 끝난 질문 뒤에 다음 질문이 도는 경우를 쌍둥이에 더했다 (§5.2, §6.2)
- 기본 engine 의 단언은 `EARLY` 와 부등식에 묶어 두고 빌드에서 조이지 않는다고 적었다 (§6.2)
- `release-gate-provider-keys.md` 의 "넷" 은 HANDOFF 에 적는다. 그 문서를 읽지 않았다는 것도 머리에 밝혔다 (§3.2)
- 호출 수는 `TestReporter` 가 아니라 파일로 낸다 (§6.2)

---

## 9. 구현이 이 설계와 갈라진 자리

§1 ~ §8 은 고치지 않았다. 아래는 빌드(2026-10-07)에서 코드와 맞지 않았거나 승인 리뷰가 빌드에 넘긴 것이다. 번호는 위
절을 가리킨다.

### 9.1 본문이 틀린 자리

| 본문 | 무엇이 틀렸나 | 구현 |
|------|---------------|------|
| §2.2 "왜 16K" — `SessionHistory` 검색 결과의 상한 20,000자 ≈ 5.7K 토큰, 채택 조건 "약 7,100 < 8,400" | 상한은 일치를 **붙이기 전에** 검사하고, 일치 하나는 앞뒤 두 메시지씩을 각각 2,000자까지 달고 온다. 최악은 20,000자 바로 아래에 일치 하나(약 10,000자)가 더 붙은 약 30,000자 ≈ 8.6K 토큰이다. 그러면 span 계획의 합(약 9.9K)은 롤링 임계값 8,400 을 넘는다 | 결론(16K 는 되찾기 한 번에 `BLOCK` 되지 않고 8K 는 될 수 있다)은 그대로 서지만 받쳐 주는 것은 span 계획이 아니라 **blocking 경로**다 — 임계값과 blocking 사이에서는 `FALLBACK` 경고로 그대로 보내고, blocking 에 닿으면 미응답 앞을 전부 흡수해 약 9.9K 로 나간다(13,600 아래). 문서에는 보통의 경우와 최악을 따로 적었고, 질문 단계의 `FALLBACK` 은 칸을 버릴 이유가 아니라고 적었다 |
| §2.3 · §4.5 · §6.1 — needle 의 질문도 `EARLY` · `MIDDLE` · `LATE` 로 층화하고 "`EARLY` 에서만 벌어지면 M2" 로 읽는다 | 사실 여섯은 전부 2 ~ 7턴에 있고 그 뒤로 수준 2 에서 리포트 60여 개가 온다. 어느 사실도 tail 에 있지 않으므로 층이 하나뿐이고, 비교할 다른 층이 없다 | needle 의 질문은 전부 `EARLY` 다. "세 stratum 이 모두 있음" 불변식은 keyValue · logTriage 에만 건다. needle 에서 압축의 값을 가르는 비교는 **같은 질문의 대조 수준**이다. 사실을 압력 단계에 흩는 쪽으로는 풀지 않았다 — 과제가 "초반에 준 사실" 이 아니게 된다 |
| §2.2 — 큰 단위 약 1,200 토큰(약 4,200자) | 그 크기로는 대조 수준에서 단위 넷이 계획 5,040(§6.1 의 여유)을 넘는다 | 3,850자(도구 메시지로 약 1,110 ~ 1,160 토큰). 여유를 늘리지 않고 단위를 맞췄다. 2,000자보다 길어 `offset` 읽기는 여전히 쓰인다(한 번) |
| §4.5 · §5.2 — `INVALID_CONTROL` 은 "수준 1 미만" 의 칸 | 롤링은 0.6 부터 정당하게 압축한다. 0.7 같은 수준의 칸이 설정대로 동작했다는 이유로 무효가 된다 | 대조 칸은 **계획 토큰이 롤링 임계값의 0.6 배(5,040) 이하**인 칸이다 — §6.1 의 생성 테스트가 수준 0.3 에 대해 붙드는 바로 그 값. 수준의 범위는 (0, 8] 이 아니라 **[0.3, 8]** 이다: 0.3 아래에서는 단위가 넷이 안 되어 세 층을 채울 수 없다 |
| §4.5 — keyValue · logTriage 는 제공하는 수준에서 압력 단계 `ROLLING = 0` | 수준 2 는 쌍둥이가 붙든다. 수준 4 는 스크립트 모델로는 0 이지만 뷰의 최대가 8,342 로 임계값 8,400 에 붙어 있다 — 답마다 몇 토큰을 더 쓰는 실제 모델은 넘는다 | 읽는 법에 "수준 4 의 압력 단계에 `ROLLING` 이 보일 수 있고 그 칸은 prune 과 요약이 섞인 것" 이라고 적었다 |
| §6.2 — 롤링에서 `EARLY` 답마다 `sessionHistoryCalls ≥ 1` | 검색은 일치 앞뒤의 메시지를 함께 보여 준다. needle 의 1번 사실을 찾으면 2번 사실이 이웃으로 딸려 와서 다음 질문은 뷰에서 답한다(되찾기 0). keyValue 도 두 질문이 한 페이지에 있으면 같다 | 전부 `CORRECT`, 그리고 **처음 묻는** `EARLY` 답의 되찾기가 1 이상. keyValue 의 "한 답에 2 회 이상" 은 그대로 선다 |
| §6.2 — `FAITHFUL` 대역은 `<<<PREVIOUS_SUMMARY>>>` 에서 사실 줄을 옮긴다 | 그것은 롤링의 경로다. 기본 engine 은 이전 요약을 뷰의 메시지로 둔다 | 대역은 시스템 프롬프트와 입력 메시지 양쪽에서 사실 줄을 찾는다. "needle · 기본 · `FAITHFUL`" 은 두 번째 `FULL` 을 지나서도 전부 `CORRECT` 다 |
| §3.2 — `context-engine.md` 부록의 참조 파일 지도에 새 파일 | 그 부록은 그 문서의 경계(§13) 앞이고 승인 뒤 고치지 않는 글이다([`../README.md`](../README.md) §3.4) | 파일은 §13.11 안에 적었다 |

### 9.2 본문이 비워 둔 자리

- **`plannedTokens` 에 질문이 드는가** — 들지 않는다. 압력은 첫 질문 **전에** 로그에 들어간 것(압력 단계의 사용자 입력 +
  그것이 가져온 도구 결과)이다. 대조 수준의 계획은 needle 4,286 · keyValue 4,646 · logTriage 4,773 토큰이다
- **질문의 순서** — 늦은 것 먼저(`LATE` → `MIDDLE` → `EARLY`). 롤링에서 `LATE` 의 출처는 첫 되찾기 결과가 올 때까지만
  원문이다: 그 결과는 미응답이고 tail 예산보다 커서 prune 구간이 그 앞 전부로 넓어진다. 다른 순서로 물으면 `LATE` 도
  가려진 결과에 대고 묻게 되어 stratum 열이 칸마다 다른 뜻이 된다
- **`reportedPromptTokens` 가 두 프로바이더에서 같은 양인가** — 같다. 두 클라이언트 모두 프로바이더가 센 입력 토큰 전체를
  그 칸에 넣고, Anthropic 클라이언트는 프롬프트 캐시를 요청하지 않아 캐시 읽기가 따로 빠지지 않는다. 보고서의 열 이름은
  `reported input` 이다
- **질문 턴이 첫 호출에서 프로바이더 오류로 죽는 모양** — 로그가 사용자 메시지로 끝나고 다음 질문이 그 뒤에 붙는다.
  쌍둥이에 경우 하나를 더해(두 engine) 다음 질문이 돌고 채점되는 것을 붙든다
- **`MainCallRecorder` 가 넘기는 오버로드** — 실행기가 쓰는 셋이 아니라 `LlmClient` 의 일곱 전부를 같은 오버로드로 넘긴다.
  나머지 넷을 default 로 두면 그쪽으로 부른 호출은 parts 가 이어 붙은 `String` 오버로드로 내려가 모양이 바뀐다
- **값 타입에 더한 것** — `Question` 에 `sourceLine`(답이 실린 글 그대로)과 `lookupKey`(부분 문자열 검색으로 출처를 찾는
  글). 스크립트 모델과 불변식 테스트가 쓴다. 결과의 `model` 은 클라이언트의 `getDefaultModelName()` 이다
- **logTriage 의 질문** — 본문의 예는 `disk-full` 같은 오류 이름으로 묻지만, 구현은 말뭉치에서 유일한 **오류 코드**로
  묻는다(`Which request id is on the log line with error code E-9300?`). 답은 다른 과제와 같은 꼴의 토큰이어야 채점이
  하나로 서므로 로그 줄의 `node=` 와 `req=` 가 그 꼴이다
- **끝없이 검색하는 스크립트 모델** — 아무것도 맞지 않는 질의로 검색한다. 질문의 키로 검색을 되풀이하게 했을 때는 턴이
  최대 iteration 으로 **끝나지 않았다**(`failedTurns` 0). 검색 결과가 이전 검색 결과와 맞으며 불어난 탓으로 보이지만
  원인은 확인하지 않았다

### 9.3 쌍둥이가 센 호출 수

§5.1 의 표는 어림이었다. 스크립트 모델(한 단어로 답하고, 한 번만 가져오고, 보이지 않는 것만 되찾는다)의 실측은 아래와
같고 실제 모델의 하한이다. 세 과제 × 두 engine 의 합이며 토큰은 engine 의 추정이다.

| 수준 | 주 호출 | 요약 호출 | 보낸 뷰의 합 |
|------|---------|-----------|--------------|
| 0.3 | 126 | 0 | 약 0.38M |
| 2 | 514 | 11 | 약 2.8M |
| 4 | 961 | 23 | 약 5.9M |

세 수준을 다 돌리면 프로바이더당 약 9.1M 이다(본문의 어림은 11M). 칸별 수는 `ContextPressureRunTest` 가 모듈의
`build/reports/context-engine-pressure/keyless-call-counts.md` 에 쓴다 — 그 모듈의 `test` 태스크가 실제로 돈 뒤에만 있다.

### 9.4 열린 질문의 행방

§7 의 아홉 가운데 이 변경 밖에 결과가 있는 것은
[`../../backlog/context-engine-pressure-rig-open-items.md`](../../backlog/context-engine-pressure-rig-open-items.md) 로
옮겼다. 열림/닫힘의 정본은 그쪽이다.

| 질문 | 어디로 |
|------|--------|
| Q2 opt-in 변수 | `CP-2` |
| Q3 재는 모델 | `CP-4` |
| Q4 `SessionHistory` 의 비대칭 · Q8 한 번만 내주는 출처 | `CP-3` |
| Q1 창 크기 · Q5 head 밖의 사실 · Q6 롤링의 비율 · Q9 큰 결과에 대한 롤링의 요약 | 옮기지 않았다. 리그의 상수와 수준 목록으로 닫히는 선택이고, 기준선을 읽는 사람이 알아야 할 한계는 `context-engine.md` §13.11 의 "재지 않는 것" 에 있다 |
| Q7 닫힌 항목 LA-1 에 한 줄을 덧붙여도 되는가 | 된다. 그 항목의 같은 자리에 이미 날짜 붙인 덧붙임이 있다(2026-09-11, #98). 같은 모양으로 한 줄을 더했다 |

본문 §3.2 가 "HANDOFF 에 적는다" 고 한 `release-gate-provider-keys.md` 의 낡은 클래스 수는 `CP-5` 로 등록했다.
