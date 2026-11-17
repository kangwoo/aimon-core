# Context engine 뷰 압력 리그

> Status: **IMPLEMENTED** — 테스트 소스에만 있다. 프로덕션 코드와 공개 API · SPI 는 바뀌지 않는다. 남은 일은
> [`context-engine-pressure-rig-open-items.md`](../../backlog/context-engine-pressure-rig-open-items.md) 가 정본이다.
>
> 적용 대상: `aimon-llm-openai` · `aimon-llm-anthropic` 의 테스트 소스(같은 이름의 사본 둘)
>
> 관련 문서: [`../agent-execution/context-engine.md`](../agent-execution/context-engine.md) (SPI · 기본 engine) ·
> [`../agent-execution/rolling-context-engine.md`](../agent-execution/rolling-context-engine.md) (롤링 정책) ·
> [`../agent-execution/compaction.md`](../agent-execution/compaction.md) (임계값 · 판정 사다리) ·
> [`release-gate-provider-keys.md`](release-gate-provider-keys.md) (키 게이트) · 실행 명령과 비용은
> [`CONTRIBUTING.md` › Live-API tests](../../../CONTRIBUTING.md#live-api-tests)

---

## 1. 무엇을 재는가

기존 `ContextEngineLiveRig` 시나리오는 프로바이더가 engine 의 요청을 받는지와 사실 하나가 롤링 사이클을 넘는지를
통과/실패로만 본다. 세 번째 engine 의 착수 조건([`self-managed-context-engine.md` §12](../backlog/self-managed-context-engine.md#12-언제-다시-볼까))이
"이 저장소의 과제에서 이득이 재어졌을 때" 이므로, 두 engine(`default` · `rolling`)을 **같은 입력 · 같은 창**에 놓고 정답률과
비용을 나란히 재는 리그를 테스트 소스에만 둔다. 프로덕션 코드와 SPI 는 바꾸지 않는다.

비교 프로파일은 기존 시나리오와 따로 둔다. 기존 시나리오(롤링은 8K 창에 비율 0.3, 기본 engine 은 프레임워크 창 표)는 몇 턴
안에 롤링 사이클에 닿도록 고른 값이라 두 engine 이 서로 다른 창 위에 있고, 비교가 성립하지 않는다. 기존 팩토리와 그 숫자,
그것이 만드는 배선은 그대로다 — 기존 라이브 테스트와 쌍둥이가 그 위에서 돈다.

```
ContextPressureTasks (순수, 시드 고정)          ContextEngineLiveRig.forComparison(kind, ...)
  needle / keyValue / logTriage                   같은 창 · 프로덕션 기본 비율 · 프로덕션과 같은 도구 배선
        |  ContextPressureTask (불변)                      |
        +--------------------+-----------------------------+
                             v
                  ContextPressureRun.run(task, rig)
        압력 단계(턴마다 fetch 1회) -> 질문 단계(턴마다 질문 1개) -> 채점
                             v
                  ContextPressureResult  ->  build/reports/context-engine-pressure/<provider>-<model>.md
```

기준선은 [CP-1](../../backlog/context-engine-pressure-rig-open-items.md) 에 있다. CP-1 은 옛 프로파일(롤링 8,400 / 기본
13,000)을 쟀다. 지금의 프로파일(§3)에서 다시 잰 값은 없다 — CP-7.

## 2. 압력과 대조 수준

**압력**은 과제가 첫 질문 **전에** 로그에 밀어 넣는 입력 — 압력 단계의 사용자 입력과 그것이 가져온 도구 결과 — 의 추정 토큰
합을 effective window 로 나눈 값이다. 질문 문장은 세지 않는다. 추정은 engine 이 임계값 판정에 쓰는 `HeuristicTokenEstimator`
다. 압축을 일으키는 것이 그 숫자이기 때문이다.

- 생성기는 합이 `p × effective` 이상이 될 때까지 **압력 단위**(도구 결과 하나)를 더한다. 넘침은 한 단위 미만이다
- 러너는 실행에서 실제로 닿은 압력을 다시 재어 결과에 싣는다. 요청의 90%(`MIN_PRESSURE_SHARE`) 아래면 그 칸은
  `INVALID_PRESSURE` 다(§8)
- 수준은 **0.3 이상 8 이하**다(`MIN_PRESSURE` · `MAX_PRESSURE`). 0.3 아래에서는 단위가 모자라 세 stratum 을 채울 수 없다.
  8 은 오타 하나가 수천 번의 과금 호출이 되지 않게 하는 상한이다

**0.3 이 대조**다. 어느 engine 도 압축하지 않아야 하고, 여기서 틀리는 모델은 압력과 무관하게 과제를 못 푸는 것이다 — 그
모델의 높은 수준 실패를 engine 탓으로 돌릴 수 없다. 뷰에는 계획 토큰이 세지 않는 것(시스템 프롬프트, fetch 마다의
`tool_use`, 모델의 답, 메시지 오버헤드)이 얹히고 생성기는 한 단위까지 넘치므로, 대조 칸의 뷰가 임계값 근처에 붙지 않을
만큼 낮아야 한다. 대조 칸에서 압축이 일어나도(모델이 군말을 길게 하거나 필요 없는 되찾기를 한다) 단언하지 않고
`INVALID_CONTROL` 로 기록한다.

대조 칸의 정의는 수준이 아니라 계획 토큰이다 — **계획 토큰 ≤ 롤링 임계값 × 0.6**(`ContextPressureRun.CONTROL_SHARE`)인 칸.
현재 프로파일에서 `0.6 × 11,200 = 6,720` 이다. 수준으로 정하면 롤링이 설정대로 압축하는 0.7 같은 수준의 칸이 무효가 된다.
대조 수준의 세 과제가 실제로 이 값 아래에 얼마나 여유를 두고 드는지는 `확인 필요`.

## 3. 비교 프로파일

`ContextEngineLiveRig.forComparison` 이 만든다. 창은 `ContextEngineLiveRig.COMPARISON_LIMITS` 하나를 두 engine 이 공유한다.

| 항목 | 값 |
|------|----|
| 창 | `contextWindow 16_000`, `reservedOutputTokens 2_000` → effective 14,000. auto 비율은 기본값 0.8(`ModelContextLimits.DEFAULT_AUTO_COMPACT_RATIO`), warning / blocking 버퍼 1,000 / 400 → AUTO 11,200, blocking 13,600 |
| 기본 engine | 그 창 위의 `DefaultCompactionGuard` + `DefaultContextEngine`(`writeFormat(V2)`, 뷰 모드) |
| 롤링 engine | `RollingContextEngine.builder()` 에 **비율을 주지 않는다** — 배포되는 `DEFAULT_*` 그대로(head 0.05 · tail 0.20 · 요약 0.08 · minTail 0.05 · prune 하한 500). 임계값은 기본 engine 과 같은 11,200, tail 예산 2,800, 요약 목표 1,120 |
| 도구 | 두 engine 모두 `fetch_report`(§6). `SessionHistoryTool` 은 `OrcaAgentRuntimeFactory` 가 하는 대로 **롤링에만** 등록한다 — 되찾기 유무는 engine 의 일부다 |
| 시스템 프롬프트 | 두 engine 에 같은 문장이고 도구를 이름으로 부르지 않는다. 질문 문장도 마찬가지다. 다른 것은 도구 목록뿐이다 |
| 에이전트 | `maxIterations(6)` — 질문 턴 하나에서 되찾기 5회까지. 넘으면 그 질문은 `FAILED` |
| 실행기의 클라이언트 | `MainCallRecorder` 로 감싼다 — 호출마다 보낸 뷰의 추정 토큰, 프로바이더가 센 입력 토큰, 응답이 요청한 도구 이름. **비교 리그에서만** 감싸고 기존 두 팩토리의 실행기는 감싸지 않은 클라이언트를 받는다 |

- **롤링의 비율을 프로덕션 기본값으로 두는 이유.** 재려는 것은 배포되는 설정이다. 기존 시나리오의 롤링 0.3 은 아무도
  배포하지 않는 설정이다
- **시스템 프롬프트가 도구를 이름으로 부르지 않는 이유.** 기존 시나리오의 프롬프트는 `SessionHistory` 를 이름으로 부르는데
  기본 engine 에는 그 도구가 없다. 두 engine 이 받는 글이 같아야 차이가 engine 에서 온다
- **`MainCallRecorder` 는 `LlmClient` 의 일곱 오버로드를 모두 같은 오버로드로 넘긴다.** `String` 시스템 프롬프트 오버로드만
  감싸면 default 구현이 system prompt parts 를 이어 붙여 그 오버로드로 내려보내므로 프로바이더가 받는 요청의 모양이 바뀐다.
  그러면 리그가 재는 것이 감싸지 않은 클라이언트가 보냈을 요청이 아니게 된다. stub 으로는 드러나지 않는 종류의 결함이라
  쌍둥이가 한 경우로 붙든다
- **롤링이 이 창을 감당하지 못하면** 롤링은 조용히 기본 engine 으로 물러나고([롤링 가능성 검사](../agent-execution/rolling-context-engine.md#7-롤링-가능성-검사)),
  리그는 기본 engine 을 두 번 재게 된다. 롤링 줄의 `FULL` 기록으로 보이며 쌍둥이가 0 임을 단언한다

### 왜 16K 인가

`SessionHistory` 검색 결과의 크기는 창에 비례하지 않는다. 보통은 일치 하나에 이웃 메시지를 더한 수천 자다. 최악은 약
30,000자다 — 상한(`SEARCH_RESULT_PARTS × DEFAULT_MAX_RESULT_CHARS` = 10 × 2,000 = 20,000자)은 일치를 붙이기 **전에** 검사하고,
일치 하나는 앞뒤 두 메시지씩을 각각 2,000자까지 달고 온다. 그 결과는 모델이 아직 답하지 않은 미응답 부분이라 원문으로
남는다([미응답 부분](../agent-execution/context-engine.md#7-미응답-부분)).

창은 그 최악의 결과도 받을 만큼 커야 한다. 모델이 고르는 검색어는 통제할 수 없으므로 최악의 결과가 들어갈 자리를 창
쪽에서 낸다. 창이 작으면 미응답 부분만으로 blocking 을 넘을 수 있고, 그러면 리그는 "롤링이 사실을 잃었다" 가 아니라 "창이
되찾기 도구보다 작다" 를 재게 된다. 8K 가 그 경우이고 32K 는 비용이 두 배다.

다음은 `확인 필요` 로 남긴다. 최악 결과의 추정 토큰(약 8.6K), 현재 임계값 11,200 에서 그 결과를 받쳐 주는 경로가 미응답
부분을 tail 로 삼는 span 계획인지 blocking 경로인지, 그리고 그 경로가 내보내는 뷰의 크기. 어느 경로든 롤링 칸의 질문 단계에
`FALLBACK` 이 보이면 예상한 동작이고 그 칸을 버릴 이유가 아니다.

## 4. prune 이 먼저 — 단위 크기가 재는 것

롤링은 임계값에서 prune 을 먼저 시도하고, 가리는 것만으로 임계값 아래가 되면 요약하지 않는다
([L0 prune](../agent-execution/rolling-context-engine.md#5-l0-prune)). prune 하한 이상의 도구 결과로 만든 압력은 전부 placeholder 로
접힌다. 그래서 **압력 단위의 크기가 롤링 칸이 무엇을 재는지를 정한다.** 과제마다 단위를 나눈다.

| 단위 | 크기 (`ContextPressureTasks`) | 과제 | 이유 |
|------|------------------------------|------|------|
| 작은 단위 | `SMALL_UNIT_CHARS = 1_450` | needle | 도구 메시지로 추정해 prune 하한(500 토큰) **아래**라 지울 수 없고, 롤링은 요약해야 한다. needle 의 사실은 사용자 메시지라 어차피 prune 대상이 아니므로, 이렇게 해야 사실이 실제로 span 에 흡수된다 |
| 큰 단위 | `LARGE_UNIT_CHARS = 3_850` | keyValue · logTriage | prune 하한 이상이라 prune 대상이다. `SessionHistory` 한 조각(2,000자)보다 길어 `offset` 으로 이어 읽는 경로가 쓰인다. 단위 넷이 대조 칸의 상한(§2) 안에 든다 |

- 키 없는 검사가 두 경계를 붙든다 — needle 단위는 prune 하한 미만, 큰 단위는 하한 이상이고 effective 의 15% 이하. 하한은
  `RollingContextEngine.DEFAULT_PRUNE_MIN_TOKENS` 를 읽어 쓴다. 리터럴로 적으면 기본값이 바뀔 때 조용히 어긋난다
- **keyValue · logTriage 의 롤링 기준선은 prune 기준선이다.** 큰 도구 결과에 대한 롤링의 실제 동작이 prune 이고, 리그는 그것을
  지우지 않는다
- 두 단위의 추정 토큰(작은 단위 약 420, 큰 단위 약 1,110 ~ 1,160)과 각 수준에서 롤링이 몇 번 요약하는지는 현재 프로파일에서
  `확인 필요`

## 5. 세 과제와 stratum

과제는 스크립트다. 사용자 입력의 순서와 도구가 돌려주는 본문이 전부 `(과제, 시드, 압력)` 에서 정해지고, 난수는 시드로 만든
난수원 하나에서만 나온다. 모델이 정하는 것은 답과 무엇을 다시 찾아 읽을지뿐이다. 같은 과제 인스턴스 하나를 두 engine 에
준다.

- **1턴은 사실이 없는 브리핑이다.** 롤링의 head 는 첫 사용자 메시지를 원문으로 고정하므로
  ([head](../agent-execution/rolling-context-engine.md#2-head)), 거기 사실을 두면 롤링이 과제와 무관하게 맞힌다
- 압력 단계는 턴마다 `fetch_report` 한 번이다. 질문 단계는 턴마다 질문 하나이고, 답을 값만 쓰고 못 찾으면 `UNKNOWN` 으로
  답하라는 문장이 붙는다

| 과제 | 사실이 놓이는 자리 | 질문 | 롤링에서 재는 것 |
|------|-------------------|------|------------------|
| needle (사실 보존) | 2 ~ 7턴의 **사용자 메시지** 여섯. 그 뒤로 사실과 무관한 작은 리포트 | 사실마다 하나(6), 전부 `EARLY` | `ROLLING` 요약이 사실을 지키는가, 잃었으면 검색으로 되찾는가 |
| keyValue (키-값 조회) | **도구 결과** — `svc.<name>.<attr> = <value>` 줄 약 100개의 페이지가 곧 단위 | 8. 물은 키의 다음 줄에 한 글자만 다른 이웃 키가 있다 | prune 이 가린 결과에서 한 줄을 되찾는가 — 검색, 그리고 페이지 뒤쪽이면 `offset` 읽기 |
| logTriage (로그 triage) | **도구 결과** — 서비스 로그 덤프가 곧 단위. INFO 잡음 속에 오류 코드 · 노드 · 요청 id 를 가진 WARN/ERROR 줄 | 6. stratum 마다 세 꼴 — 덤프를 지목한 것, 말뭉치에서 유일한 오류 코드만 준 것(내용으로 찾아야 한다), 덤프를 지목하되 같은 코드가 다른 덤프의 다른 노드에 미끼로 있는 것 | 위와 같다 |

logTriage 의 답은 로그 줄의 `node=` · `req=` 값이다. 답이 다른 과제와 같은 꼴의 토큰이어야 채점이 하나로 선다.

**stratum.** keyValue · logTriage 의 질문은 출처의 위치로 나뉜다 — 마지막 두 단위(`LATE_UNITS`, 롤링의 tail 에 드는 양)가
`LATE`, 나머지의 앞 절반이 `EARLY`, 뒤 절반이 `MIDDLE`.

- **늦은 것부터 묻는다**(`LATE` → `MIDDLE` → `EARLY`). 롤링에서 `LATE` 의 출처는 첫 되찾기 결과가 올 때까지만 원문이다 — 그
  결과는 미응답이고 tail 예산보다 커서 prune 구간이 그 앞 전부로 넓어진다. 다른 순서로 물으면 `LATE` 도 가려진 결과에 대고
  묻게 되어 stratum 열이 칸마다 다른 뜻이 된다
- **needle 에는 층이 없다.** 사실은 전부 첫 리포트 앞에 있고, 대조를 넘는 어느 수준에서도 첫 압축이 흡수한다. needle 에서
  압축이 치른 값을 가르는 비교는 stratum 이 아니라 **같은 질문의 대조 수준**이다. 사실을 압력 단계에 흩지 않는다 — 과제가
  "초반에 준 사실" 이 아니게 된다

**생성기의 불변식** (키 없는 검사가 붙든다): 모든 정답 값은 말뭉치 전체에서 정확히 한 번 나온다. 어떤 질문 문장에도 정답이나
미끼 값이 들어 있지 않다. 미끼는 정답과 같은 꼴이고 서로 다르며 어느 쪽도 다른 쪽의 부분 문자열이 아니다. 값은 사전 단어와
숫자로 만들어 모델이 맞혀 낼 수 없다.

## 6. 한 번만 내주는 출처

비교 리그의 `fetch_report` 는 id 마다 한 번만 내준다. 처음 받은 id 에는 본문을, 그 뒤로는 "이미 내주었다" 는 고정 문장을
돌려준다. 다시 가져올 수 있으면 기본 engine 의 모델이 다시 가져와 답하고, 그 정답은 engine 이 무엇을 지켰는지와 무관하기
때문이다. 회전한 로그나 소비된 큐처럼 한 번 흘러가면 다시 오지 않는 출처의 모양이다.

- 고정 문장은 **오류가 아닌 결과**다. 오류는 모델의 재시도를 부른다. 모르는 id 에는 `no entries` 를 돌려준다
- 내준 id 는 리그가 가진 `ReportDesk` 에 있고 도구는 그것을 읽는다. 도구 규칙의 "무상태" 는 프로덕션 도구의 것이고, 이것은
  리그 하나의 수명 안에서 출처를 흉내 내는 테스트 대역이다. 리그마다 새 `ReportDesk` 이므로 두 engine 의 실행은 서로 영향을
  주지 않는다
- 거절된 호출은 `refetch attempts` 로 질문마다 · 칸마다 센다. 압력 단계에서 같은 id 를 두 번 부른 것도 여기 든다
- 기존 시나리오의 리포트 도구는 지금처럼 몇 번이든 내준다

이 선택으로 이 리그의 기본 engine 은 실제보다 불리하다(§10, CP-3).

## 7. 채점과 지문

채점은 문자열 대조다. 답은 `QX7-MULE-4417` 꼴의 생성된 토큰이고 말뭉치에 정확히 한 번 나온다. LLM 심판은 없다 — 과금 호출이
늘고 결정적이지 않으며, 정답이 생성된 토큰이라 필요 없다.

| verdict | 조건 |
|---------|------|
| `FAILED` | 턴이 실패했다(`BLOCK`, 프로바이더 오류, 최대 iteration) |
| `CORRECT` | 정규화한 최종 답이 정답을 포함하고 어떤 미끼도 포함하지 않는다 |
| `ABSTAINED` | `UNKNOWN` 을 포함하거나 값 꼴의 토큰이 하나도 없다 |
| `WRONG` | 그 밖 — 다른 값을 답했다 |

정규화는 대문자로 바꾸고 공백 · 백틱 · 따옴표 · 끝의 마침표를 지운다. 정답 포함만 검사하면 후보를 전부 나열한 답이 맞게 되므로
미끼가 들어 있으면 `WRONG` 이다. `WRONG` 과 `ABSTAINED` 를 가르는 것은 요약이 사실을 잃을 때 침묵이 아니라 **틀린 값**으로
나타나는지를 보려는 것이다.

**지문.** 두 프로바이더 모듈은 테스트 소스를 공유하지 않아 과제 코드가 사본 둘이다. 어긋남은 말뭉치(시스템 프롬프트, 압력
단계의 입력, id 순의 리포트, 질문 · 정답 · 미끼)의 **SHA-256 지문**이 붙든다. 두 모듈의 키 없는 검사가 `(과제, 시드 1, 압력 2)`
세 지문을 같은 리터럴과 대조하므로, 한쪽 생성기만 고치면 그 모듈이 빨개진다. 지켜야 할 것은 소스의 글자가 아니라 두
프로바이더가 받는 입력이 같다는 것이다. 채점과 러너의 어긋남은 지문이 잡지 못한다 — 두 쌍둥이가 같은 단언을 하는 것이
전부다.

## 8. 결과 상태와 보고서 읽는 법

칸 하나는 과제 × engine × 수준(× 시드)이다. 러너(`ContextPressureRun`)는 모델이나 프로바이더가 한 일로 던지지 않는다. 수치는
전부 실행 결과의 압축 기록(`CompactionMetadata` 의 kind · trigger · blocking 초과 여부), iteration 수, 두 recorder, `ReportDesk`
에서 읽는다. 프로덕션 코드에 관측 지점을 더하지 않는다.

| `status` | 뜻 |
|----------|----|
| `OK` | 압력에 닿았고(요청의 90% 이상) 대조 칸이면 압축이 없었다 |
| `INVALID_CONTROL` | 대조 칸(계획 토큰 ≤ 롤링 임계값의 0.6 배)인데 압축 기록이 있다. 단언이 아니라 기록이다 |
| `INVALID_PRESSURE` | 모델이 fetch 를 건너뛰어 닿은 압력이 요청의 90% 아래다 |
| `ERROR` | 압력 단계의 턴이 실패했다. 그 칸을 닫고 다음 칸으로 간다 |

압축 수는 kind 별(`PRUNE` · `ROLLING` · `FULL` · `FALLBACK`)로 전체와 압력 단계만을 따로 센다. 질문 단계의 되찾기 결과가 일으킨
압축과 압력이 일으킨 압축을 가르기 위해서다.

보고서는 모듈의 `build/reports/context-engine-pressure/<provider>-<model>.md` 에 쓴다. 칸마다 한 줄인 요약 표와 질문마다 한 줄인
상세 표이고, 머리에 프로파일의 숫자 · 과제별 단위 크기 · 시드 · 수준 · 날짜를 적는다. 칸이 끝날 때마다 다시 쓴다 — 중간에
429 나 예외로 죽어도 이미 돈을 낸 칸의 결과가 남는다. 표준 출력으로 내지 않는 것은 컨벤션 플러그인이
`showStandardStreams = false` 이기 때문이다.

읽는 법:

- **먼저 걸러 낸다** — `status` 가 `OK` 가 아닌 줄, `failed turns > 0`, 롤링 줄의 `FULL > 0`(기본 engine 으로 물러났다)
- **대조 수준의 칸이 `OK` 이고 정답률 1.0 이어야** 그 과제 · 모델 · engine 의 높은 수준을 engine 비교로 읽는다.
  `INVALID_CONTROL` 이면 대조가 대조가 아니었다
- **needle 의 롤링 줄은 요약을 잰다.** 압력 단계가 `ROLLING ≥ 1`, `PRUNE = 0` 이어야 한다
- **keyValue · logTriage 의 롤링 줄은 prune 을 잰다.** 압력 단계가 `PRUNE ≥ 1`, `ROLLING = 0` 이고, 여기서 롤링의 오답은
  "요약이 잃었다" 가 아니라 "가려진 결과를 되찾지 못했다" 다. 압력 단계에 `ROLLING` 이 보이면 그 칸은 prune 과 요약이 섞인
  것이다. 현재 프로파일에서 어느 수준부터 그렇게 되는지는 `확인 필요`
- `sent view sum` 이 비용의 대리값이다 — "정답률은 같은데 합이 크다" 가 읽고 지나간 결과를 임계값까지 싣고 가는 값이다.
  `reported input` 은 프로바이더가 센 입력 토큰이고, 두 클라이언트 모두 프롬프트 캐시를 요청하지 않으므로 두 프로바이더에서
  같은 양이다
- `SessionHistory calls` 는 롤링이 정답률을 사는 데 든 값이다. 기본 engine 에는 그 도구가 없어 언제나 0 이다
- `refetch attempts` 는 어느 engine 에서든 0 이 아닐 수 있다 — 모델이 다시 가져오려다 거절당한 횟수다. 답을 바꾸지는 못하지만
  그 engine 의 뷰에서 사실이 사라졌다는 것을 모델이 행동으로 보인 것이다. 기본 engine 의 `ABSTAINED` 옆에 이 수가 높으면
  "되찾을 길이 있었다면 썼을 것" 으로 읽는다(CP-3)

## 9. 실행 중 실패 정책

| 무엇이 | 어떻게 드러나고 어떻게 다루나 |
|--------|------------------------------|
| 모델이 지시받은 `fetch_report` 를 부르지 않는다 | 그 단위가 로그에 없다. 다시 시키지 않는다 — 두 engine 의 입력이 달라진다. `skippedFetches` 를 세고, 닿은 압력이 요청의 90% 아래면 `INVALID_PRESSURE` |
| 모델이 이미 받은 리포트를 다시 가져오려 한다 | 본문 대신 고정 문장을 받는다(§6). `refetch attempts` 로 보이고, 그 답은 평소대로 채점한다 |
| 대조 칸에서 압축이 일어난다 | `INVALID_CONTROL`. 테스트는 빨개지지 않는다. 읽는 법이 그 칸과 그 위 수준을 거른다 |
| 압력 단계의 턴이 실패한다(429, 프로바이더 오류, `BLOCK`) | 그 칸을 `ERROR(message)` 로 닫고 **다음 칸으로 간다.** 보고서는 칸마다 다시 쓰므로 앞 칸의 결과가 남는다. 재시도는 프로바이더 클라이언트의 것 외에 더하지 않는다 |
| 질문 턴이 실패한다(최대 iteration, 되찾기 결과로 `BLOCK`, 첫 호출의 프로바이더 오류) | 그 질문만 `FAILED` 이고 다음 질문을 계속한다. 로그가 도구 결과나 사용자 메시지로 끝난 채 다음 질문이 그 뒤에 붙는다. 그 뷰로 다음 턴이 돌고 채점되는 것을 쌍둥이가 붙든다 |
| 롤링이 기본 engine 으로 물러난다 | 롤링 칸에 `FULL` 기록이 생긴다. 쌍둥이가 0 임을 단언하고, 라이브 보고서에는 그 수가 그대로 나온다 |
| needle 의 단위가 prune 하한을 넘어 prune 된다 | 롤링이 요약하지 않고 needle 이 아무것도 재지 않는다. 키 없는 검사가 단위 크기를, 쌍둥이가 needle · 롤링의 `PRUNE = 0` 을 붙든다 |
| 요약 호출이 거절되거나 실패한다 | `summaryFailures`. 연속 실패로 breaker 가 열리면 뷰가 자라 `BLOCK` 에 닿고 위의 턴 실패로 이어진다 |
| 답에 군말이 붙는다 | 정규화 + 포함 검사. 정답은 다른 문장에 우연히 나올 수 없는 토큰이다 |
| 후보를 전부 나열한 답 | 미끼가 들어 있으므로 `WRONG` |
| 사본이 어긋난다 | 지문(§7) |
| recorder 가 요청의 모양을 바꾼다 | 일곱 오버로드를 같은 오버로드로 넘기고(§3) 쌍둥이의 한 경우가 붙든다 |
| 보고서를 못 쓴다 | 테스트를 실패시킨다. 돈을 낸 결과를 조용히 잃는 것보다 낫다 |
| 키는 있는데 opt-in 이 없다 | `assumeTrue` 로 건너뛰고, 메시지가 변수 이름과 `CONTRIBUTING.md` 의 절을 말한다 |
| `AIMON_CONTEXT_PRESSURE` 의 값이 숫자 목록이 아니거나 [0.3, 8] 밖이다 | 첫 호출 전에 실패한다 — 오타 하나가 수천 번의 호출이 되지 않게 |

## 10. 재지 않는 것

- **한 번의 실행은 한 표본이다.** 과제당 질문은 6 ~ 8 개이고 모델은 결정적이지 않다. 한두 문제의 차이는 잡음이다. 반복은
  시드를 늘려서 한다
- **큰 도구 결과에 대한 롤링의 요약.** keyValue · logTriage 는 prune 기준선이다. 롤링의 요약이 사실을 지키는지는 needle 만
  본다. 수준 목록이 매개변수이므로 더 높은 수준으로 prune 에서 span 으로 넘어가는 자리를 볼 수는 있지만 기본 권장에는 넣지
  않는다
- **다시 가져올 수 있는 출처.** 실제 도구 다수는 다시 부를 수 있고, 그때 뷰에서 잃은 것의 값은 오답이 아니라 다시 부르는
  비용이다. 이 리그의 기본 engine 은 그만큼 실제보다 불리하다(CP-3)
- **창이 16K 다.** 이 리그는 "압력이 같을 때 두 engine 이 어떻게 다른가" 를 재고, "실제 창에서 그 압력에 얼마나 자주
  닿는가" 는 재지 않는다
- **압력은 추정 토큰이다.** 프로바이더가 센 값과 다르다. 보고서는 둘을 나란히 싣는다
- **턴 사이의 압력만 만든다.** 한 턴 안에서 iteration 이 수십 번 도는 실행은 이 과제에 없다
- **head 에 놓인 사실의 이득.** 사실을 head 밖에 두어 롤링의 head 고정이 주는 이득을 과제에서 뺐다(§5)
- 재는 모델은 같은 모듈의 `*ContextEngineLiveTest` 와 같다 — `gpt-4o-mini`(maxTokens 1000), `claude-haiku-4-5-20251001`
  (maxTokens 2000). 그것이 착수 조건이 말하는 "쓰려는 모델" 인지는 CP-4
- [`self-managed-context-engine.md` §12](../backlog/self-managed-context-engine.md#12-언제-다시-볼까) 의 둘째 물음(모델이 크기
  알림을 보고 실제로 편집하는가)은 범위 밖이다

## 11. 과금 opt-in

라이브 실행 한 번은 기존 라이브 계층 전체의 수십 ~ 백 배를 쓴다. 키가 export 된 셸의 평범한 빌드가 그것을 치르지 않도록
`OpenAIContextPressureLiveTest` · `AnthropicContextPressureLiveTest` 는 프로바이더 키 게이트(`@EnabledIfEnvironmentVariable`)를
**그대로** 따르고, 그 안에서 `AIMON_CONTEXT_PRESSURE` 가 없으면 `assumeTrue` 로 건너뛴다. 값이 곧 수준 목록(`0.3,2`)이라 싼
수준부터 재고 비싼 수준을 따로 낼 수 있다. 게이트를 넓히지 않고 좁히므로, 키가 있으면 시작하지 않는 릴리스 스크립트가 이
클래스를 돌 길은 없다([`release-gate-provider-keys.md`](release-gate-provider-keys.md)).

| 기각한 대안 | 이유 |
|-------------|------|
| 키 게이트만 | 키를 export 한 셸의 모든 `./gradlew test` · `checkAll` 이 이 클래스를 돈다 |
| 새 `@EnabledIfEnvironmentVariable` 변수 | `ReleaseGateMatchesCiGateTest` 의 키 게이트 인구조사가 실패하고 `scripts/release.sh` 를 고쳐야 한다 |
| JUnit 태그나 시스템 속성 | `test` 태스크의 제외나 `-D` 전달은 빌드 스크립트 변경이다 |

인구조사는 애노테이션만 읽으므로 이 변수는 거기 잡히지 않는다(CP-2). 명령, 비용, 보고서의 위치는
[`CONTRIBUTING.md` › Live-API tests](../../../CONTRIBUTING.md#live-api-tests) 가 정본이다.

## 12. 설계 결정

리그의 타입은 전부 테스트 소스의 package-private 타입이다. 공개 API 와 SPI 는 바뀌지 않는다.

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 과제 코드가 사는 자리 | **두 프로바이더 모듈에 사본**(패키지 줄과 클래스 이름 접두만 다르다). 어긋남은 지문으로 붙든다(§7) | **새 testkit 모듈** — 코드가 `src/main` 에 놓여 `.impl` 경계 검사의 대상이 되는데 리그는 `at.aimon.core.agent.impl.orca` 를 직접 쓴다. 빌드 설정과 두 모듈의 의존도 바뀐다. **`aimon-llm-capability-testkit`** — 주제가 설정 표면 → `ModelCapabilityDeclaration` 바인딩 계약이고 두 프로바이더 모듈은 거기 의존하지 않는다. **`aimon-core` 의 테스트 소스** — 다른 모듈이 볼 수 없다. **`java-test-fixtures`** — publishing 플러그인 아래에서 구성이 실패한다 |
| 사본의 어긋남 | 쌍둥이가 말뭉치 SHA-256 지문을 리터럴과 대조. 두 모듈의 리터럴이 같다 | **`aimon-core` 에서 두 소스를 읽어 비교** — 패키지 이름 길이 때문에 포매터가 주석을 다르게 접어 정규화 규칙이 필요해진다. 지켜야 할 것은 소스의 글자가 아니라 두 프로바이더가 받는 입력이 같다는 것이다 |
| engine 설정 | 같은 창, 롤링은 프로덕션 기본 비율(§3) | **기존 시나리오의 숫자 재사용** — 롤링 0.3 은 아무도 배포하지 않는 설정이고 두 engine 의 창이 다르다. **`OrcaAgentRuntimeFactory` 와 실제 창** — 사이클 하나에 수십만 토큰이다 |
| 창 크기 | 16K | **8K** — 미응답 되찾기 결과만으로 blocking 을 넘을 수 있다(§3). **32K** — 비용이 두 배다 |
| 압력을 만드는 방법 | 턴마다 도구 호출 하나, 지시문 고정 | **한 턴에 여러 개를 가져오게 한다** — 모델이 병렬로 부르면 결과 셋이 `TOOL` 메시지 하나로 붙어 미응답 부분이 tail 보다 커지고, 순차로 부르면 그렇지 않다. 같은 과제의 모양이 모델에 따라 달라진다 |
| 롤링이 요약하게 만드는 방법 (needle) | needle 의 도구 결과를 prune 하한 아래로 둔다(§4) | **큰 단위 그대로** — prune 이 압력을 전부 접어 롤링이 한 번도 요약하지 않고, 사용자 메시지인 사실은 원문으로 남아 롤링이 과제와 무관하게 전부 맞힌다. **산문 사용자 메시지로 채운다** — 과제 정의가 "무관한 도구 결과로 뷰를 채운다" 다. **수준을 크게 올린다** — 큰 단위로 span 에 닿을 때까지 턴이 많이 들고, 그 뒤에도 사이클이 드물어 비용 대비 요약 표본이 적다. **큰 것과 작은 것을 섞는다** — 한 칸에서 prune 과 요약이 함께 일어나 어느 쪽이 사실을 잃었는지 읽기 어렵다 |
| keyValue · logTriage 의 롤링이 요약하지 않는 것 | 받아들이고 **prune 기준선**이라고 적는다(§4, §8, §10) | **이 둘도 작은 단위로** — "큰 로그 덤프" 가 아니게 되고, 큰 결과에 대한 롤링의 실제 동작(prune)을 리그에서 지운다 |
| 다시 가져오기 | `fetch_report` 는 id 마다 한 번만 내주고, 시도는 센다(§6) | **세기만 한다** — 기본 engine 의 모델이 다시 가져와 답하면 engine 과 무관한 정답이 되고, 그런 답을 빼면 표본이 준다. **그대로 둔다** — 두 engine 이 다시 가져오기로 동률이 되고 "기본 engine 은 아무것도 잃지 않는다" 로 읽힌다 |
| 대조 수준 | 0.3, 압축이 일어나면 `INVALID_CONTROL` 로 기록(§2) | **0.5** — 대조 칸의 뷰가 임계값 가까이 붙는다(현재 프로파일에서의 여유는 `확인 필요`). **대조의 "압축 0" 을 라이브에서 단언** — 모델의 말수에 달린 단언이다 |
| 사실을 head 에 두는가 | 두지 않는다. 1턴은 사실 없는 브리핑 | **첫 사용자 메시지에 사실** — 롤링의 head 고정으로 롤링이 과제와 무관하게 전부 맞힌다. 실제 차이이기는 하나 한 메시지의 특권이고, 재려는 것(요약 · prune · 되찾기)을 가린다 |
| 질문하는 방법 | 질문 하나에 턴 하나 | **마지막 턴 하나에 전부** — 호출은 줄지만 답을 줄 단위로 파싱해야 하고, 질문별 되찾기 횟수와 실패한 턴이 구별되지 않는다. 질문 턴은 압력 단계에 비해 싸다 |
| 표본 수 | 실행당 질문을 늘린다(과제당 6 ~ 8). 시드는 매개변수 | **같은 과제를 여러 번** — 압력 단계를 통째로 다시 낸다. 질문을 늘리는 쪽이 같은 돈으로 표본이 많다 |
| 채점 | 정규화한 최종 답에 정답이 있고 미끼가 없으면 `CORRECT`(§7) | **LLM 심판** — 과금 호출이 늘고 결정적이지 않다. **정답 포함만 검사** — 후보를 전부 나열한 답이 맞게 된다 |
| 라이브 클래스의 단언 | 정답률 · `status` · 되찾기 수를 단언하지 않는다. 수치는 보고서로 | **정답률 하한을 단언** — 정답률은 재는 대상이지 통과 조건이 아니다. 이 계층이 썩은 사례 대부분이 상태만 요구할 자리에서 모델의 문장을 대조한 단언이었다([`live-api-test-tier.md`](../../backlog/live-api-test-tier.md) LA-1) |
| 키 없는 검사가 붙드는 것 | 생성의 결정성, 대조의 여유(계획 ≤ 임계값 × 0.6, 쌍둥이의 보낸 뷰 최대 ≤ 임계값 × 0.75), 단위가 prune 하한의 어느 쪽에 있는지, 답의 유일성, 채점, 하네스 불변식(롤링이 물러나지 않음, 기본 engine 이 잃은 것을 잃었다고 보고함, 한 번만 내주는 리포트, 실패한 질문 뒤에 다음 질문이 돎, 네 `status`, recorder 가 오버로드를 바꾸지 않음) | **절단면 위치에 기대는 단언**("3번 질문은 `WRONG`") — 요약 길이가 한 토큰 달라지면 깨진다. stratum 단위와 부등식으로 적는다 |
| 결과가 나가는 자리 | 모듈 `build/` 아래 Markdown 파일, 칸마다 다시 쓴다(§8) | **표준 출력** — `showStandardStreams = false` 다. **끝에 한 번 쓰기** — 중간에 죽으면 이미 돈을 낸 칸의 결과를 잃는다 |
| 과금 실행의 opt-in | 키 게이트 안의 `AIMON_CONTEXT_PRESSURE`(§11) | §11 의 표 |

## 13. 하지 말 것

- **기존 팩토리 `rolling(...)` · `defaultViewMode(...)` 의 숫자와 배선을 비교에 맞춰 고치지 말 것.** 기존 라이브 테스트와
  쌍둥이가 그 위에서 돈다. 비교는 `forComparison` 에서만 한다
- **`MainCallRecorder` 에서 오버로드 하나만 감싸지 말 것.** default 구현이 parts 를 이어 붙여 프로바이더가 받는 요청의 모양이
  바뀐다(§3)
- **needle 단위를 prune 하한 이상으로 키우지 말 것.** 롤링이 요약하지 않고 needle 이 아무것도 재지 않는다(§4)
- **단위 크기 검사에 prune 하한을 리터럴로 적지 말 것.** `RollingContextEngine.DEFAULT_PRUNE_MIN_TOKENS` 를 읽는다
- **사실을 첫 사용자 메시지에 두지 말 것.** 롤링의 head 고정이 과제와 무관한 정답을 준다(§5)
- **건너뛴 fetch 를 다시 시키지 말 것.** 두 engine 의 입력이 달라진다. `INVALID_PRESSURE` 로 기록한다
- **한쪽 모듈의 생성기만 고치지 말 것.** 두 사본과 두 지문 리터럴을 함께 고친다(§7)
- **라이브 클래스에 정답률 · 상태 단언을 더하지 말 것.** 모델의 말수로 빨개지는 단언이다(§12)
- **opt-in 을 새 `@EnabledIfEnvironmentVariable` 변수로 바꾸지 말 것.** 키 게이트 인구조사와 릴리스 스크립트가 함께 바뀌어야
  한다(§11)

## 14. 참조 파일 지도

| 관심사 | 파일 (두 프로바이더 모듈의 테스트 소스에 같은 이름으로) |
|--------|------|
| 비교 프로파일(`COMPARISON_LIMITS` · `forComparison` · `comparisonRollingThreshold`) · `ReportDesk` · `MainCallRecorder` | [`ContextEngineLiveRig.java`](../../../modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/ContextEngineLiveRig.java) |
| 과제의 생성 · 채점 · 지문, 단위 크기와 수준 범위 상수 | [`ContextPressureTasks.java`](../../../modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/ContextPressureTasks.java) |
| 러너 · 결과 · 보고서, `CONTROL_SHARE` · `MIN_PRESSURE_SHARE` · `OPT_IN_VARIABLE` | [`ContextPressureRun.java`](../../../modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/ContextPressureRun.java) |
| 키 없는 검사 | [`ContextPressureTasksTest.java`](../../../modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/ContextPressureTasksTest.java), [`ContextPressureRunTest.java`](../../../modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/ContextPressureRunTest.java) |
| 라이브 | [`OpenAIContextPressureLiveTest.java`](../../../modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/OpenAIContextPressureLiveTest.java), [`AnthropicContextPressureLiveTest.java`](../../../modules/aimon-llm-anthropic/src/test/java/at/aimon/core/llms/anthropic/AnthropicContextPressureLiveTest.java) |
| 리그가 읽는 프로덕션 상수 | `aimon-core` 의 `ModelContextLimits`(`DEFAULT_AUTO_COMPACT_RATIO`, 임계값 식) · `RollingContextEngine`(`DEFAULT_*`) · `SessionHistoryTool`(`SEARCH_RESULT_PARTS` · `DEFAULT_MAX_RESULT_CHARS`) |
