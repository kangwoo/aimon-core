# Context engine 뷰 압력 리그가 남긴 열린 항목 — 등록 항목 6건 (열림 5 · 닫힘 1)

출처는 2026-10-07 의 작업이다 — `ContextEngineLiveRig` 에 `default` · `rolling` engine 을 같은 과제로 재는 비교 과제를
붙였다. 설계 기록은 [`design/agent-execution/context-engine-pressure-rig.md`](../design/agent-execution/context-engine-pressure-rig.md),
지금의 리그를 서술하는 글은 [`design/agent-execution/context-engine.md` §13.11](../design/agent-execution/context-engine.md#1311-engine-을-비교하는-과제) 이다.

**열림/닫힘의 정본은 이 문서다.** 설계 기록 §7 의 열린 질문 아홉은 설계 시점의 기록이고, 그 가운데 이 변경 밖에 결과가
있는 것만 여기로 옮겼다(옮기지 않은 넷과 그 이유는 그 기록 §9.4). `CP-1` 과 `CP-5` 는 질문이 아니라 작업이 끝난 자리에
남은 것이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 전부 2026-10-07 기준이다.

---

## CP-1 — 리그는 생겼고 기준선은 재지 않았다 · **닫힘** *(2026-10-07)*

**무엇을.** 두 프로바이더에서 `*ContextPressureLiveTest` 를 수준 `0.3,2` 로(여유가 되면 `4` 도) 돌리고, 나온 보고서의
수치를 [`self-managed-context-engine.md` §12](../design/backlog/self-managed-context-engine.md#12-언제-다시-볼까) 의 착수 조건에
대어 본다.

**왜.** 그 문서의 착수 조건은 "이 저장소의 과제에서 이득이 재어졌을 때" 하나다. 이 작업은 과제를 만들었을 뿐이고 과금
호출은 한 번도 하지 않았다. 리그가 있다는 사실이 "쟀다" 로 읽히면 세 번째 engine 의 보류는 근거 없이 굳거나 풀린다.
스크립트 모델로 확인한 것은 배선과 채점뿐이다 — 실제 모델이 지시대로 가져오는지, 대조 수준에서 압축 없이 전부 맞히는지는
아직 아무도 보지 않았다.

**어디.** `OpenAIContextPressureLiveTest` · `AnthropicContextPressureLiveTest`. 명령과 보고서 읽는 법은
[`CONTRIBUTING.md` › Live-API tests](../../CONTRIBUTING.md#live-api-tests).

**언제 다시 볼까.** 누군가 그 명령을 돌린 날. 보고서는 모듈의 `build/` 아래에 쓰이고 커밋되지 않으므로, 잰 값을 남길
자리(§12, 또는 이 항목)를 그때 정한다. 대조 수준의 칸이 `OK` · 정답률 1.0 이 아니면 CP-3 · CP-4 를 먼저 본다.

### 닫힘 (2026-10-07)

두 프로바이더에서 수준 `0.3,2` 로 한 번씩 돌렸다. 시드 하나, 한 번의 실행이다. 수준 `4` 는 돌리지 않았다.

**재기 전에 고칠 것이 있었다.** 처음 돌린 Anthropic 보고서에서 기본 engine 의 요약 호출 절반쯤이 content block 없는
응답으로 실패했고, 그중 하나는 keyValue 의 턴을 `ContextWindowExceededException` 으로 끝냈다. 리그의 결함이 아니라 요약
요청을 닫는 방식의 결함이었고 [#237](https://github.com/kangwoo/aimon-core/pull/237) 이 고쳤다. **아래 Anthropic 의 수치는
#237 의 변경을 얹고 잰 것이다** — 그 변경이 없는 트리에서 같은 명령을 돌리면 기본 engine 의 행에 `summary failures` 가
나오고 수치가 더 낮다. OpenAI 에서는 요약 실패가 없었고, 그 수치는 #237 없이 잰 것이다.

수준 2 의 정답 수다(괄호는 대조 수준 0.3).

| 과제 | `claude-haiku-4-5` default | `claude-haiku-4-5` rolling | `gpt-4o-mini` default | `gpt-4o-mini` rolling |
|------|------|------|------|------|
| needle | 0/6 (6/6) | 6/6 (6/6) | 0/6 (6/6) | 6/6 (6/6) |
| keyValue | 2/8 (8/8) | 3/8 (8/8) | 3/8 (6/8) | 4/8 (6/8) |
| logTriage | 4/6 (6/6) | 5/6 (6/6), 실패한 턴 1 | 3/6 (5/6) | 3/6 (5/6) |

읽는 규칙을 적용하면 남는 것은 이렇다.

- **`gpt-4o-mini` 의 keyValue · logTriage 는 비교할 수 없다.** 대조 수준에서 전부 맞히지 못했다(CP-4 가 물은 것의 한 답이다)
- **`claude-haiku-4-5` 의 logTriage 롤링 행은 뺀다.** 실패한 턴이 있다(CP-6)
- **needle 은 두 모델 모두 0/6 대 6/6 이다.** 기본 engine 은 요약이 성공해도 여섯 질문을 전부 포기했고, 롤링은 head 를
  원문으로 남겨 전부 맞혔다
- **keyValue 는 `claude-haiku-4-5` 에서 2/8 대 3/8 이다.** 롤링은 결과를 다섯 번 prune 했고 모델은 `SessionHistory` 를 한
  번도 부르지 않은 채 다섯 질문을 포기했다. 같은 모델이 logTriage 에서는 되찾기를 썼다. 이유는 이 한 번의 실행으로는 알
  수 없다

**§12 의 착수 조건에 대어 보면 충족되지 않았다.** 롤링이 약한 칸은 keyValue 하나이고, 거기서 잃은 것은 요약이 지운 사실이
아니라 모델이 되찾지 않은 사실이다. 그 과제는 질문이 나중에 오므로 모델이 스스로 뷰를 줄였더라도 무엇을 남길지 미리 알
수 없다. 다만 이것은 16K 창, 작은 모델 둘, 시드 하나의 결과다.

**착수해 보니 달랐던 것.** 이 항목은 재기만 하면 된다고 적었다. 실제로는 재는 일이 프로덕션 경로의 결함 하나를 먼저
드러냈고, 그것을 고치기 전의 기본 engine 수치는 engine 의 것이 아니라 그 결함의 것이었다(logTriage 2/6 이 4/6 이 되었고
keyValue 는 오류 대신 결과가 나왔다). 보고서의 `summary failures` 열이 0 이 아닌 행은 engine 을 재고 있지 않다 — 읽는
규칙 1 은 `failed turns` 만 걸러 내고 이 열은 걸러 내지 않는다.

보고서 자체는 모듈의 `build/` 아래에 있고 커밋되지 않는다. 남기는 자리는 이 절이다.

## CP-2 — 과금 실행의 opt-in 변수는 키 게이트 인구조사가 보지 못한다 · **열림** *(결정 대기)*

**무엇을.** `AIMON_CONTEXT_PRESSURE` 로 라이브 클래스를 한 번 더 좁힌 것을 그대로 둘지, 인구조사가 읽는 모양으로 바꿀지
정한다.

**왜.** 두 클래스는 프로바이더 키의 `@EnabledIfEnvironmentVariable` 을 그대로 달고, 그 안에서 `assumeTrue` 로 이 변수를
본다. `ReleaseGateMatchesCiGateTest` 의 인구조사는 애노테이션의 `named = "…"` 만 읽으므로 이 변수는 거기 없다. 지금은
해롭지 않다 — 변수는 게이트를 좁히기만 하고, 릴리스 스크립트는 키가 있으면 시작하지 않는다. 다만 "라이브 클래스는 키
하나로만 켜진다" 는 이 계층의 문장(`CONTRIBUTING.md`, `release-gate-provider-keys.md` §1.1)이 두 클래스에 대해서는 참이
아니게 되었고, 과제의 기준 5("기존 키 게이트를 그대로")를 "키만으로 돈다" 로 읽는다면 opt-in 은 빠져야 한다 — 그러면 키를
export 한 셸의 모든 `checkAll` 이 프로바이더당 수백만 토큰을 쓴다.

**어디.** 두 `*ContextPressureLiveTest` 의 `assumeTrue`, `ContextPressureRun.OPT_IN_VARIABLE`.

**언제 다시 볼까.** `assumeTrue` 로 읽는 변수로 과금 실행을 여는 클래스가 하나 더 생길 때 — 그때는 관례가 되므로
인구조사가 읽게 한다. 그 클래스를 쓰는 사람은 이 두 클래스를 본뜰 것이고, 두 클래스의 javadoc 이
`CONTRIBUTING.md` 의 절을, 그 절이 이 계층의 백로그를 가리킨다.

## CP-3 — 이 리그의 기본 engine 은 실제보다 불리하다 · **열림** *(결정 대기)*

**무엇을.** 기준선에 칸을 더할지 정한다 — (a) `fetch_report` 를 다시 부를 수 있게 두고 횟수만 세는 칸, (b) 기본 engine 에도
`SessionHistory` 를 등록한 칸.

**왜.** 두 선택이 모델이 돌기 전에 keyValue · logTriage 의 두 engine 차이 대부분을 정한다. 리포트는 id 마다 한 번만
내주고(다시 부르면 본문 대신 고정 문장), 되찾기 도구는 프로덕션 배선대로 롤링에만 있다. 그래서 기본 engine 의 모델은 요약이
잃은 줄을 되찾을 길이 **없고**, 롤링의 모델은 있다. 실제 에이전트의 도구 다수는 다시 부를 수 있고, 그때 뷰에서 잃은 것의
값은 오답이 아니라 "다시 부르는 비용" 이다. 세 번째 engine 이 겨룰 상대가 "다시 부르면 되는 기본 engine" 이라면 지금의
기준선은 그 상대를 재지 않는다.

**어디.** `ContextEngineLiveRig.forComparison`(`ReportDesk` 를 넘기는 자리, `SessionHistoryTool` 을 롤링에만 등록하는 자리).
(b) 는 어떤 조립도 만들지 않는 구성이라 설계에서 뺐다.

**언제 다시 볼까.** CP-1 의 보고서에서 기본 engine 의 `ABSTAINED` 옆에 `refetch attempts` 가 높게 나올 때 — 모델이 되찾을
길이 있었다면 썼을 것임을 행동으로 보인 것이다. 그 열은 보고서의 칸마다 있다.

## CP-4 — 재는 모델이 §12 가 말하는 "쓰려는 모델" 인지 정해지지 않았다 · **열림** *(결정 대기)*

**무엇을.** 기준선을 어느 모델로 잴지 정한다.

**왜.** 두 라이브 클래스는 기존 라이브 클래스의 모델(`gpt-4o-mini`, `claude-haiku-4-5`)을 따랐다 — 싸기 때문이다.
`self-managed-context-engine.md` §12 의 조건은 "쓰려는 모델" 이고, 작은 모델에서 나온 M1 ~ M3 의 크기가 큰 모델에 그대로
옮겨 간다는 근거는 없다. 더 큰 모델이면 비용이 한 자릿수 달라진다.

**어디.** 두 `*ContextPressureLiveTest` 의 `MODEL` 상수.

**언제 다시 볼까.** CP-1 을 돌리기 직전. 명령을 치는 사람이 그 클래스의 javadoc 에서 비용을 읽고, 그 옆에 모델 상수가 있다.

## CP-5 — `release-gate-provider-keys.md` 는 라이브 클래스가 넷이라고 적는다 · **열림**

**무엇을.** 그 문서 §1.1 의 클래스 수와 목록을 지금의 트리에 맞추거나, 그 문장이 설계 시점의 수임을 밝힌다.

**왜.** 프로바이더 키로 게이트가 걸린 클래스는 이 작업 뒤 여덟이다. `CONTRIBUTING.md` 의 표는 여덟을 싣고, 그 문서는
`AnthropicThinkingLiveTest` 등 넷만 든다 — `*ContextEngineLiveTest` 둘이 들어온 때부터 이미 어긋나 있었다. 그 문서를 읽고
릴리스 게이트가 막는 범위를 넷으로 아는 사람은 나머지 넷이 같은 키로 함께 돈다는 것을 모른다(거부 자체는 키를 보므로
동작은 맞다).

**어디.** `docs/design/testing/release-gate-provider-keys.md:18-20`.

**언제 다시 볼까.** 그 문서를 다음에 고치는 사람이 §1.1 을 지나간다. `Status` 가 `IMPLEMENTED` 이고 표지가 없는 기록이라
제자리에서 고칠 수 있다.

## CP-6 — 실제 모델이 `SessionHistory` 조회를 되풀이하다 턴을 실패시킨다 · **열림**

**무엇을.** 롤링의 logTriage 에서 한 질문이 iteration 한도까지 `SessionHistory` 만 부르다 실패하는 원인을 찾는다.

**왜.** `claude-haiku-4-5`, 수준 2 의 logTriage 롤링 칸에서 첫 질문(`LATE`)이 여섯 iteration 동안 `SessionHistory` 를 여섯
번 부르고 `FAILED` 로 끝났다. 두 번의 실행에서 같은 질문이 같은 모양으로 실패했다(둘 다 #237 의 변경을 얹은 실행이다. 그
전의 실행에서는 같은 질문이 세 iteration 에 맞혔다). 그 행은 읽는 규칙 1 로 비교에서 빠지므로 **이 모델에서는 logTriage
의 engine 비교가 남지 않는다.** 원인은 보지 않았다 — 조회가 찾는 줄을 돌려주지 못하는 것인지(검색어, 스캔 상한, 결과
상한), 모델이 받은 결과를 쓰지 못하는 것인지 모른다. 설계 기록 §9.2 가 적은 "같은 조회를 되풀이하는 스크립트 모델이
iteration 한도에서 턴을 끝내지 않았다" 와 같은 자리일 수 있다. 그쪽은 실패가 `FAILED` 로 세어지지 않을 수 있다는
걱정이었고, 이번의 실제 모델은 `FAILED` 로 세어졌다.

**어디.** `ContextPressureRun`(질문 턴의 iteration 한도와 채점), `SessionHistoryTool`(검색과 상한),
`AnthropicContextPressureLiveTest`. 보고서의 질문 표에서 `iterations` 와 `SessionHistory calls` 가 같은 행이 그 모양이다.

**언제 다시 볼까.** 이 리그로 롤링을 다시 잴 때. 그 행이 빠진 채로는 CP-1 의 표에서 logTriage 를 읽을 수 없다.
