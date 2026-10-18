# 사용량 쿼터 — 등록 항목 3건 (열림 3 · 닫힘 0)

출처는 [`../design/agent-execution/usage-quota.md`](../design/agent-execution/usage-quota.md) 의 1단계 구현이다.
그 문서 §11(구현 뒤 — 설계와 달라진 점)이 적은 것 가운데 **1단계 밖으로 결과가 나가는 것**만 여기로 올라왔다.
설계 §9 의 2·3단계(CLI 표시, Spring Boot 프로퍼티, Redis · Postgres 장부, 세션 삭제 시 키 정리)는 이미 설계가
단계로 잡아 둔 일이라 여기 옮겨 적지 않는다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §10 · §11 은 설계·구현 시점의 기록이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 전부 2026-10-15 기준이다.

---

## UQ-1 — 두 기록 지점을 지나지 않는 LLM 호출은 쿼터에 잡히지 않는다 · **열림**

**무엇을.** 턴 루프(`OrcaAgentExecutor`)와 포크 루프(`DefaultSubagentExecutor`) 밖에서 나가는 LLM 호출을 한 기록
경로로 모으고, 그 경로가 실행의 `QuotaGuard` 에 기록하게 한다.

**왜.** 쿼터는 두 루프의 `recordTokens` 바로 뒤에서만 기록된다(설계 §4.1). 그 밖의 호출은 쿼터를 쓰면서도 장부에
나타나지 않는다 — 관측 가능한 결과는 **한도에 닿은 세션이 컴팩션 요약·메모리 파생·위키 합성 호출을 계속 낸다**는
것이고, 장부의 사용량은 프로바이더 청구서보다 늘 적다. 설계 §10 이 첫 항목으로 범위 밖에 두면서 "별도 항목으로
둔다" 고 적은 것이 이것이다. `BudgetTracker` 도 같은 호출을 보지 못하므로 새 구멍은 아니지만, 쿼터는 실행 1회가 아니라
**누적**을 막는 장치라 같은 누락이 더 크게 보인다.

**어디.** (2026-10-15)

- `DefaultCompactionEngine.java:460` · `:471` — 컴팩션 요약 호출
- ~~`LlmSkillExecutor` — 포크하지 않는 슬래시 명령 스킬(`/skill`)의 자체 루프.~~ 판정과 토큰 기록은 먼저 들어갔고
  (명령 도구 컨텍스트의 `QUOTA_GUARD` 를 읽는다), 남았던 **비용**도 들어갔다 — 2026-10-18 아래 "좁힘" 참고
- `SubagentBehaviorSupport` 를 쓰는 코드 동작(code behavior) 서브에이전트 — 요청에는 guard 가 실려 오지만
  (`SubagentExecutionRequest#getQuotaGuard`), 동작이 직접 부르는 게이트웨이 호출은 기록되지 않는다
- `at.aimon.core.memory.{deriver,dreamer,dialectic,reconciler}` · `at.aimon.core.knowledge.wiki` 의 LLM 전략들 —
  실행 바깥에서 도는 것이 많아 걸 guard 부터 없다

**언제 다시 볼까.** 셋 중 하나다.

1. 쿼터 사용량이 청구서와 크게 어긋난다는 보고가 들어올 때 — 그때 어느 호출이 몫이 큰지부터 잰다.
2. LLM 호출 계측을 한 자리로 모으는 작업(예: 게이트웨이 수준의 사용량 훅)이 시작될 때. 쿼터는 그 자리에 붙는다.
3. 설계 §9 3단계(공유 장부)가 들어가 테넌트 단위 청구 근사에 쓰이기 시작할 때.

**좁힘 (2026-10-18, #265) — `/skill` 의 비용.** `LlmSkillExecutor` 가 `CostEstimator` 를 받는다
(`withCostEstimator`, `LlmSkillExecutor.java:200`). 포크 실행기가 받는 길과 같다 — 팩토리의
`withCostEstimator` 가 메인 실행기와 포크 실행기에 주는 그 estimator 를 기본 명령 실행 매니저의 스킬 실행기에도 준다
(`createDefaultCommandExecutionManager`, `OrcaAgentExecutorFactory.java:899`). 그래서 `/skill` 루프의 호출은 이제 가격을
달고 기록되어(`sendAndCharge`, `:432`) 비용 한도에 잡힌다. estimator 가 없으면(`CostEstimator.NOOP`) 예전처럼 토큰만
기록한다. 팩토리를 거치지 않고 명령 실행 매니저를 직접 넘기는 호스트는 `LlmSkillExecutor#withCostEstimator` 를 스스로
불러야 한다 — 포크 실행기와 같은 조건이다.

이 항목에 **남은 것**은 위 목록의 나머지 셋이다 — 컴팩션 요약 호출(쿼터에 기록되지 않는다; ST-9 가 실은 principal 은
usage attribution 이지 쿼터 기록이 아니다), 코드 동작 서브에이전트의 게이트웨이 호출, 메모리 · 위키의 LLM 전략들.

---

## UQ-2 — 호출한 실행이 없는 포크는 쿼터 없이 돈다 · **열림**

**무엇을.** 턴을 거치지 않고 포크를 시작하는 두 경로에 쿼터를 거는 방법을 정한다.

**왜.** 포크는 부모의 `QuotaGuard` 를 물려받을 뿐 스스로 해석하지 않는다(설계 §4.3). 그러니 **부모가 턴이 아니면
물려받을 guard 가 없다.** 1단계가 그 자리를 비활성 guard 로 둔 경로가 둘이다.

- **예약 루틴의 단계.** `RoutineExecutor` 는 턴을 돌리지 않고 도구를 직접 부른다. `Task` · `Workflow` · 포크 스킬을
  부르는 단계의 포크는 소유자(`task.getOwner()`)가 있는데도 **주체 쿼터에 잡히지 않는다** — 세션 한도를 다 쓴
  사용자가 같은 일을 cron 으로 걸면 계량되지 않는다.
- **에이전트 단위 `WorkflowRunner` 를 앱이 직접 부를 때.** `OrcaAgentRuntimeFactory` 가 만드는 runner 의 기본 launch
  context 에는 호출한 실행이 없다. 도구 경유의 백그라운드 실행은 1단계에서 guard 를 실어 보내도록 고쳤지만
  (`WorkflowRunner#runInBackground(..., QuotaGuard)`, 설계 §11), 앱 코드가 runner 를 직접 부르면 비활성 guard 다.

**어디.** (2026-10-15) `RoutineExecutor.java:447` (`buildToolContext` — `QUOTA_GUARD` 를 싣지 않는다),
`OrcaAgentRuntimeFactory.java:1157` (기본 launch context).

**막힌 것.** 둘 다 **애플리케이션 범위의 `QuotaGuardFactory` 를 그 자리까지 가져가는 배선**이 필요하다 —
`RoutineExecutor` 는 실행기를 모르고, 기본 launch context 는 런타임 팩토리가 만든다. 어느 쪽이든
`QuotaResolutionContext` 를 무엇으로 채울지(세션 없음, 주체는 소유자)는 설계 §4.4 가 이미 답한다.

**언제 다시 볼까.** 예약 루틴이 LLM 을 쓰는 단계를 사용자 대신 돌리는 배포가 나올 때, 또는 앱이 `WorkflowRunner` 를
사용자 요청으로 직접 띄우기 시작할 때.

---

## UQ-3 — `QuotaViolation` 은 노드를 건너지 못한다 · **열림**

**무엇을.** 라우팅 와이어와 저장된 결과에 `QuotaViolation`(키 · 한도 · 사용량)을 싣는다.

**왜.** 다른 노드에서 실행된 턴의 결과는 `TurnResultPayload` 를 거쳐 오는데, 그 페이로드는 완료 사유의 **이름**과
메시지만 싣는다. 받는 쪽의 `getQuotaViolation()` 은 비어 있다. 관측 가능한 결과는 **멀리서 멈춘 턴은
`QUOTA_EXCEEDED` 라는 사실과 한 줄 설명만 남고, 어느 범위의 어느 기간이 다 찼는지는 문자열을 파싱해야 안다**는
것이다. 설계 §6 이 "결과에는 어느 key 가 막았는지를 싣는다" 고 한 약속이 노드 하나 안에서만 지켜진다.
`StoredAgentExecutionResult`(멱등성 저장소에 남는 결과)도 같다.

**어디.** (2026-10-15) `TurnResultPayload.java:110` · `:203`, `StoredAgentExecutionResult.java:71`.

**언제 다시 볼까.** 설계 §9 2단계(CLI 표시)가 라우팅을 거친 결과를 보여 주게 될 때, 또는 `SessionRouter` 위의 웹
프런트엔드가 쿼터 멈춤을 사용자에게 구조적으로 보여 줘야 할 때. 와이어 필드를 더하는 일이므로 롤링 업그레이드
규칙(`CompletionReason#fromWireName` 과 같은 "모르는 것은 버리고 읽는다")을 함께 정한다.
