# 세션 모델 — 레코드, 전사, 라이브 핸들

**Status**: IMPLEMENTED — `aimon-core` `at.aimon.core.agent.session[.store|.transcript]`. 남은 것은 [마지막 절](#남은-것).

세션이라는 하나의 사용자 개념은 코드에서 영속 레코드와 노드 로컬 핸들로 갈라진다. 이 문서는 무엇이 영속되고 무엇이
프로세스와 함께 사라지는지, 그 경계가 왜 거기에 있는지를 정의한다. 다른 세션 문서는 여기서 출발한다.

- 수명·소유권의 일반 규칙: [`../../overview/scope-model.md`](../../overview/scope-model.md)
- 실행 단위 용어(turn · execution · iteration): [`../../overview/glossary.md`](../../overview/glossary.md) §4
- 레코드를 담는 SPI 와 쓰기 펜스: [`session-store.md`](session-store.md)
- 전사 안의 로그와 뷰 상태: [`session-log.md`](session-log.md)
- 멀티 노드 라우팅: [`routing.md`](routing.md)

---

## 1. 1 : 0..N 비대칭

```
one SessionRecord (영속, SessionId 로 식별)  :  0..N LiveSession (일시적, 노드 로컬)
```

한 세션에는 살아 있는 핸들이 **0개**일 수도 있고(아무도 대화 중이 아님), 시간에 걸쳐 **여러 개**가 순차로 서빙할 수도
있다(idle-TTL 축출, 프로세스 재시작, 노드 간 핸드오프). 멀티 노드에서는 같은 세션에 핸들이 동시에 둘 열려 있을 수도
있다([`routing.md`](routing.md) §3.1). 이 비대칭에서 나오는 규칙은 하나로 압축된다.

> **핸들보다 오래 살아야 하는 값은 레코드 쪽에 둔다.**

1:1 이 아니라 0..N 인 이유는 CLAUDE.md 의 "Multi-instance ready" 원칙이다. 핸들을 세션 수명 내내 고정하면 재수화 코드가
사라지지만, 대가는 노드당 무한정 메모리와 배포마다 하드 핸드오프다. 그리고 1:1 로 가면 가장 먼저 지워지는 것이 재수화
경로인데, 그 경로가 없으면 축출 한 번에 누적 totals 가 0 으로 돌아간다.

---

## 2. 타입

| 타입 | 정체 | 패키지 | 수명 |
|------|------|--------|------|
| `SessionRecord` | 영속 애그리게이트 — 전사 + side field | `agent.session.store` | 명시적 삭제까지 |
| `SessionRecordView` | 레코드의 읽기 전용 뷰. 저장소의 읽기가 돌려준다 | `agent.session.store` | — |
| `SessionTranscript` | 시스템 프롬프트 + `SessionLogState`. **불변 값** | `agent.session.transcript` | 레코드에 담김 |
| `SessionSnapshot` | `sessionId` + 시스템 프롬프트 + `SessionLogState`. 전사의 투영 | `agent.session.transcript` | 값 |
| `TranscriptBuffer` | 턴 진행 중의 가변 작업 버퍼 | `agent.session.transcript` | 턴 1회 |
| `LiveSession` | 그 세션에 대해 턴을 실행하는 노드 로컬 핸들 | `agent.session` | 열기 ~ `close()` |

`SessionId` 는 `agent.session` 최상위에 있다. 세 계층이 모두 참조하므로 어느 하위 패키지에도 속하지 않는다.

`transcript` 와 `store` 는 `session` 의 **자식** 패키지다. 형제로 두면 전사가 `SessionId` 를 참조하고 세션이
`SessionTranscript` 를 담으므로 패키지 순환이 생긴다. 자식으로 두면 부모 → 자식 방향 하나만 남는다. 같은 이유로
`store` 는 `transcript` 를 알지만 그 반대는 아니다.

`LiveSession.close()` 는 `AgentRuntime` 을 닫지 않는다. 런타임은 agent-scoped 이고 같은 agent 의 다른 세션이 아직 쓰고
있을 수 있다([`../agent-execution/agent-runtime-scope.md`](../agent-execution/agent-runtime-scope.md)).

---

## 3. 이름

**`Session` 과 `AgentSession` 은 타입 이름으로 금지된다.** 이 코드베이스는 이름 토큰으로 수명을 강제하는 ArchUnit
규칙을 여럿 갖고 있다. 두 수명이 같은 토큰을 공유하면 그 규칙은 통과하지만 아무것도 막지 못한다 — "이름에 `Session` 이
들어간 타입은 …" 이라는 규칙이 영속 레코드와 노드 로컬 핸들을 구분하지 못하기 때문이다. 초록불이 켜진 공허한 규칙은
규칙이 없는 것보다 나쁘다. 규칙이 없으면 사람이 보지만, 공허한 규칙은 아무도 보지 않는다.

그래서 두 수명 중 어느 쪽도 맨 이름을 갖지 않는다. 사용자에게 "세션" 은 재시작을 넘어 이어지는 것이므로 영속 쪽이
무표기(`Session*`)를 갖고, 노드 로컬 핸들만 `Live` 표기를 단다. `SessionNamingArchitectureTest` 가 빌드에서 막는다.

새 이름은 세 규칙을 따른다 — 재시작 후에도 복원되어야 하면 `Session*`, 프로세스와 함께 사라져도 되면 `LiveSession*`,
에이전트 단위로 한 번 모으면 되면 `Agent*`. 이름의 마지막 명사로 수명을 추론하지 않고 키와 저장 위치로 판단한다는
일반 규칙은 [`scope-model.md`](../../overview/scope-model.md) §5.3 이 정본이다.

| 기각한 이름 | 이유 |
|-------------|------|
| `ActiveSession` | `LiveSessionStatus` 의 `RUNNING`/`IDLE` 과 충돌한다. "active" 는 이미 상태 어휘다 |
| `SessionRuntime` | `AgentRuntime` 이 세운 관례("`*Runtime` 은 장수명")와 정반대다. 핸들은 가장 짧게 사는 것 중 하나다 |
| `SessionHandle` | 정확하지만 무엇의 핸들인지 말하지 않는다. `LiveSession` 은 수명까지 말한다 |

---

## 4. 레코드의 구조

### 4.1 전사 + side field 다섯

```java
class SessionRecord implements SessionRecordView {
    private final SessionId id;
    private SessionTranscript transcript;      // 시스템 프롬프트 + SessionLogState
    private int compactionFailureCount;        // side field
    private String agentRef;                   // side field — 바인딩 (routing.md §3.4)
    private SessionTotals sessionTotals;       // side field — §6.1
    private ExecutionBudget budgetOverride;    // side field — §6.2
    private Principal owner;                   // side field — §5
}
```

레코드가 **정체성 애그리게이트**이고 전사가 **불변 값 객체**다. 레코드는 메시지를 추가하는 메서드를 갖지 않는다. 모든
append 는 `TranscriptBuffer` 를 거치고, 레코드는 완성된 전사를 통째로 받는다. 그 부재는 리플렉션 테스트가 지킨다(§8).

side field 마다 쓰는 주체와 원자 primitive 가 따로 있다. 그 계약은 [`session-store.md`](session-store.md) §2 가 정본이다.

### 4.2 왜 전사만 불변인가

| | `SessionTranscript` | `TranscriptBuffer` |
|---|---|---|
| 성질 | 불변 값 — `withSystemPrompt` / `append` 가 새 인스턴스를 돌려준다 | 가변 |
| 수명 | 레코드와 함께 | 턴 1회 |
| 목적 | 저장·비교·공유가 안전 | iteration 마다 메시지를 쌓는다 |

턴 하나는 수십 번 append 한다. 매번 리스트를 복사하면 O(n²) 이므로 버퍼는 가변이어야 하고, 레코드에 앉는 순간에만 불변
스냅샷으로 굳는다.

**성능은 이 분해의 근거가 아니다.** load/save 왕복의 리스트 복사는 줄지만, 같은 분해가 전사의 `append` 를 O(n) 으로
만든다. 근거는 "공유해도 안전한 값" 이다. 성능을 근거로 적으면 다음 사람이 그 숫자를 근거로 되돌린다.

### 4.3 `compactionFailureCount` 는 전사 밖에 있다

압축 실패 카운터는 `incrementCompactionFailureCount` / `resetCompactionFailureCount` 라는 독립 원자 primitive 로 갱신된다.
전사 안에 두면 갱신이 "레코드 전체를 읽고 → 카운터를 고치고 → 전체를 쓴다" 가 되고, 그 사이 다른 노드가 전사를 append
했다면 lost update 가 된다. 압축은 정의상 전사가 클 때 돌므로 그 경합은 이론이 아니다.

### 4.4 스냅샷은 side field 를 담지 않는다

`SessionSnapshot` 은 `sessionId` · 시스템 프롬프트 · `SessionLogState` 뿐이다. 스냅샷은 전사의 투영이기 때문이다.

그래서 스냅샷을 레코드에 쓰는 경로가 보존 규칙을 진다 — `SessionRecordStore.mergeFromSnapshot` 은 기존 레코드의 side field
다섯을 되살린 뒤 전사만 덮어쓴다. 이것을 잊으면 턴이 끝날 때마다 누적 통계와 예산 오버라이드가 0 으로 돌아간다.

변환의 방향도 여기서 정해진다 — `SessionRecord.fromSnapshot(SessionSnapshot)` 이지 `SessionSnapshot.toSessionRecord()` 가
아니다. 보존해야 할 것을 아는 쪽은 레코드이므로 레코드가 생성자다. 이 방향 덕분에 mutable `SessionRecord` 에 의존하는
클래스가 `agent.session.store` 밖에 하나도 없다(§8 sole-writer 규칙).

### 4.5 읽기 뷰는 전사를 내주지 않는다

`SessionRecordView` 가 노출하는 것은 `getSystemPrompt()` / `getMessages()` / `getLogState()` 이지 `SessionTranscript` 자체가
아니다. 전사 객체를 내주면 호출자가 `withSystemPrompt` 로 새 전사를 만들어 레코드를 우회해 들고 다닐 수 있고, 그러면
§4.4 의 보존 규칙이 걸리지 않는 두 번째 쓰기 경로가 생긴다.

`getSessionTotals()` · `getBudgetOverride()` · `getOwner()` · `getLogState()` 는 인터페이스의 `default` 메서드다. 이
인터페이스에는 트리 밖 구현이 있으므로 `default` 를 abstract 로 올리면 그 구현이 컴파일되지 않는다. `default` 는 공표된
SPI 의 호환성이다.

---

## 5. 세션 소유자 — 기록이지 권한이 아니다

레코드는 선택적으로 소유 `Principal` 을 갖는다(`SessionRecordView.getOwner()`). 멀티 테넌트 확장(예: aimon-sandbox)이
"세션 → 소유자" 맵을 각자 들고 있지 않게 하려는 것이다.

- **명시한 값만 소유자가 된다.** 출처는 `SubmitRequest.owner(...)` 하나다. 필수인 `initiator` 에서 끌어내지 않는다 —
  Spring 스타터는 모든 요청에 system initiator 를 채우고, rewake · 예약 루틴 · 서비스 호출자는 자기가 소유하지 않은 세션에
  글을 넣는다. initiator 에서 끌어내면 단일 사용자 세션마다 `SYSTEM` 이 소유자가 되고, 멀티 테넌트 세션의 소유권은 먼저
  말한 자동 호출자에게 간다
- **첫 소유자가 이긴다.** 소유자는 레코드 프로비저닝이 "비어 있으면 채우고 있으면 덮어쓰지 않는다" 로 쓴다. 지우거나
  바꾸는 SPI 는 없다. 덮어쓸 수 있으면("마지막 제출이 이긴다") 확장이 막아야 하는 바로 그 가로채기가 가능해진다
- **거절된 요청은 소유자가 되지 못한다.** 바인딩이 맞는다는 것을 확인한 뒤에만 쓴다 — `AgentConflict` 로 거절된 claim 도,
  다른 agent 를 지목한 인박스 메시지도 소유자를 남기지 않는다
- **포워딩된 첫 제출도 소유자를 잃지 않는다.** 인박스 봉투가 소유자를 나르고 홀더의 드레인 경로가 기록한다
  ([`inbox.md`](inbox.md) §2)
- **언제 채워지는가.** 이 노드가 이미 리스를 들고 있는 세션의 턴은 프로비저닝하지 않으므로 소유자도 쓰지 않는다. 소유자
  없이 프로비저닝된 레코드는 다음 프로비저닝 — 리스를 쥐지 않은 노드의 claim 이나 드레인 — 이 소유자를 실어 오면 채워진다.
  엄격한 소유가 필요하면 **첫** 제출에 `owner` 를 단다
- **동일성은 `type` + `id` 다**(`Principal.equals`). `displayName` 은 표시용으로 저장되지만 동일성에 들지 않고, 첫 값이
  통째로 유지된다
- **코어는 강제하지 않는다.** 다른 소유자를 단 제출도 거절되지 않는다. 기록된 값을 읽어 규칙을 적용하는 것은 호스트나
  확장의 몫이다. 코어에는 세션의 권한 모델이 없다

소유자를 쓰고 읽는 SPI — 소유자를 받는 `provision` 오버로드, 소유자만 읽는 `loadOwner`, 감싸는 저장소의 전달 — 는
[`session-store.md`](session-store.md) §2.3 이 정본이다.

---

## 6. 무엇이 재시작을 넘는가

### 6.1 누적 통계와 예산 오버라이드

`sessionTotals` 와 `budgetOverride` 는 **라이브 세션이 쓰고 레코드가 보관하는** 쌍이다.

- **열 때** — `hydrateFromRecord()` 가 레코드에서 두 값을 읽어 핸들의 상태를 채운다. 같은 `SessionId` 로 새 핸들을 열면
  누적치는 0 부터가 아니라 복원된다
- **턴이 끝날 때** — 완료된 턴의 iteration · 토큰을 `sessionTotals` 에 더하고,
  `SessionRecordStore.setTotalsAndBudgetOverride(sessionId, totals, override)` 로 두 값을 한 쌍으로 되쓴다

이 primitive 는 델타가 아니라 **절대값**이므로 중복 호출이 턴을 두 번 세지 않는다. 레코드가 없으면 no-op 이고 쓰기 자체가
best-effort 다 — 통계 저장 실패가 턴 결과를 실패로 만들면 안 된다.

### 6.2 예산 오버라이드의 우선순위

```
레코드의 budgetOverride  >  LiveSessionOptions 의 기본 예산
```

핸들을 열 때 opener 가 기본 `ExecutionBudget` 을 준다. 레코드에 오버라이드가 있으면 그것이 이긴다. REPL 의 `/budget` 처럼
사용자가 런타임에 바꾼 값이 재개할 때 opener 기본값으로 돌아가면 안 되기 때문이다. 오버라이드는 옵션을 명시적으로 세팅할
때만 기록되고, 지우면 `null` 이 되어 다시 기본값을 따른다.

### 6.3 상태 조회는 영속과 일시를 섞지 않는다

상태 조회는 재시작을 넘는 값과 넘지 않는 값을 한 구조체에 담지 않는다. 섞으면 "이 필드는 재시작 후에도 있나?" 를 필드마다
따로 기억해야 한다. 누적치는 레코드, 진행 중인 것은 핸들이 답한다. 그래서 "마지막 턴의 결과" 같은 필드는 상태 조회에 없다.

### 6.4 재시작을 넘지 않는 것

| | 넘지 않는 것 | 결과 |
|---|---|---|
| L1 | `LiveSessionOptions` 의 locale, source agent id | 재개 시 opener 기본값으로 돌아간다 |
| L2 | 큐에 남은 미실행 메시지 (단일 노드) | 유실. 멀티 노드에서는 제출이 인박스에 먼저 적재되므로 살아남는다([`inbox.md`](inbox.md)) |
| L3 | 크래시 시점에 진행 중이던 턴 | 마지막 체크포인트 이후의 진행분은 저장되지 않는다(체크포인트는 [`session-log.md`](session-log.md) §5.3) |
| L4 | `messageTimestamps` | 스냅샷에 없어 재개 후 "메시지 나이" 표시가 리셋된다(외관상) |

---

## 7. 식별자

| 식별자 | 무엇을 가리키는가 | 영속 |
|--------|------------------|------|
| `SessionId` | 영속 세션. 전사 · 리스 · 인박스 · 승인 · 이벤트 프레임이 모두 이 값으로 조인된다 | O |
| `ExecutionId` | 세션이 없을 수도 있는 실행 — 서브에이전트 포크, 스킬 포크, rewake 리플레이, 예약 루틴 | X |
| `TurnId` | 턴 1건. 주소 지정 전용(인터럽트 겨냥, 이벤트 프레임 표시) | X |

**`SessionId` 와 `ExecutionId` 는 둘이다.** 하나로 두면 호출자가 두 방향으로 틀린다 — 포크가 자기 세션 id 를 새로
발급받아 툴에게는 사용자 세션과 똑같이 읽히지만 아무 권한도 뜻하지 않는 값을 만들거나, 세션이 없으니 아무것도 싣지 않아
추적 가능한 정체성을 잃는다. 그래서 포크는 `SessionId` 가 없고 `ExecutionId` 가 정체성이며, 툴 컨텍스트에는
`EXECUTION_ID` 가 실린다.

포크가 자기를 띄운 세션의 결정(스킬 승인 등)을 물려받아야 하므로 그 세션의 id 는 `invokingSessionId` 라는 다른 축으로
전달된다. `sessionId` 는 수명(자기 세션이 무엇인가), `invokingSessionId` 는 도달 범위(누구의 결정이 적용되는가)다. 포크가
다시 포크를 띄우면 중간 포크가 아니라 사용자의 세션 id 를 물려준다. 남아 있는 `SessionId` ↔ 포크 변환은
`forkTranscriptLabel` 하나이고, 그것은 전사 버퍼가 `SessionId` 로 타입되어 있어서 생기는 라벨이지 조회 키가 아니다.

**`TurnId` 는 스코프가 아니다.** 턴은 컴포넌트를 소유하지 않는다. 제출 시점에 발급되고 턴이 끝나면 의미가 없으므로 세션
상태로 영속하지 않는다. id 가 없는 것은 "알 수 없음 → 드롭" 이 아니라 옛 의미다 — 인터럽트는 라이브 세션 스코프, 이벤트는
세션 전체 전달. 승인 대기로 중단된 턴에만 발급되는 `PendingTurnId` 와는 다른 타입이다.

스킬 본문의 렌더 변수 `${AIMON_SESSION_ID}` 와 셸 훅 env 의 `AIMON_SESSION_ID` 는 진짜 세션 id 다. 에이전트 런타임 id 는
`AIMON_AGENT_RUNTIME_ID` 다.

---

## 8. 아키텍처 규칙

| 규칙 | 위치 | 막는 것 |
|------|------|---------|
| `sessionRecordHasNoAppendMethod` | `ArchitectureRulesTest` (core) | `addMessage` / `addUserMessage` / `addAssistantMessage` 의 부활 (리플렉션) |
| `systemPromptIsNotMutatedOutsideTheStorePackage` | `ArchitectureRulesTest` (core) | `agent.session.store` 밖에서의 `SessionRecord.setSystemPrompt` |
| `SessionNamingArchitectureTest` | `aimon-session-routing` 테스트 | 맨 `Session` / `AgentSession` 타입 이름 |
| `SessionRecordSoleWriterArchitectureTest` | `aimon-session-routing` 테스트 | mutable `SessionRecord` 에 대한 `agent.session.store` 밖의 의존 — 쓰기 경로가 `SessionStore` 뒤에 있게 한다([`session-store.md`](session-store.md) §5) |

sole-writer 규칙의 허용 목록은 **비어 있다.** 선출 · 프로비저닝 · 바인딩 · 스냅샷 변환이 모두 `agent.session.store` 안에서
끝나기 때문이다. 새 예외를 더하려면 먼저 [`session-store.md`](session-store.md) §5.3 의 남는 창을 읽는다.

**삭제한 메서드에는 부재를 단언하는 테스트를 짝짓는다.** `noClasses().should().callMethod(SessionRecord.class,
"addMessage", ...)` 같은 규칙은 메서드가 있을 때 호출을 막는다. 메서드를 지우면 규칙은 초록인 채 아무것도 막지 않고,
누군가 메서드를 되살린 커밋도 통과한다. 그래서 리플렉션으로 부재를 단언한다.

---

## 9. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 레코드와 핸들의 관계 | 1 : 0..N | **1:1** — 노드당 무한 메모리, 배포마다 하드 핸드오프, 재수화 경로가 사라진다 |
| 맨 이름 | 어느 쪽도 갖지 않는다. 영속이 무표기, 핸들이 `Live` | **영속이나 핸들 한쪽이 `Session`** — 이름 기반 규칙이 공허해진다. 기각한 이름은 §3 |
| 전사의 성질 | 불변 값. 버퍼만 가변 | **가변 전사** — 저장·공유가 안전하지 않다. **불변 버퍼** — append 가 O(n²) |
| 압축 실패 카운터 | 전사 밖 독립 원자 primitive | **전사 안** — 다른 노드의 append 와 lost update |
| 스냅샷 → 레코드 | `SessionRecord.fromSnapshot`, 저장이 side field 보존 | **`SessionSnapshot.toSessionRecord()`** — 보존해야 할 것을 모르는 쪽이 생성자가 되고, mutable 레코드에 대한 의존이 store 밖에 생긴다 |
| 소유자의 출처 | `SubmitRequest.owner` 만 | **`initiator` 에서 유도** — system·서비스 주체가 소유자가 된다 |
| 소유자의 갱신 | first-wins, 바꾸는 SPI 없음 | **마지막 제출이 이긴다** — 가로채기를 허용한다 |
| 소유자의 강제 | 기록만 | **코어가 거절** — 코어에 세션 권한 모델이 없다. 백로그 SO-1 |
| 식별자 | `SessionId` 와 `ExecutionId` 분리, `invokingSessionId` 는 별도 축 | **하나의 id** — 가짜 세션 id 를 발명하거나 정체성을 잃는다 |

---

## 하지 말 것

- **핸들보다 오래 살아야 하는 값을 핸들에 두지 않는다.** 레코드의 side field 로 둔다
- **`LiveSession.close()` 에서 `AgentRuntime` 을 닫지 않는다**
- **`SessionRecordView` 의 `default` 메서드를 abstract 로 올리지 않는다.** 트리 밖 구현이 깨진다
- **스냅샷 저장 경로에서 side field 를 버리지 않는다.** `mergeFromSnapshot` 의 보존 규칙을 우회하는 쓰기 경로를 만들지 않는다
- **전사 객체를 뷰로 내주지 않는다**
- **전사의 메시지 리스트를 `List.copyOf` 로 감싸지 않는다.** `List.copyOf` 는 null 원소를 거부하고, 만들어진 리스트는
  비어 있어도 `contains(null)` 에 NPE 를 던진다. 트리 안의 방어적 `history.contains(null)` 이 그 자리에서 터진다. 그래서
  `empty()` 조차 `Collections.unmodifiableList(new ArrayList<>())` 다
- **성능을 이 분해의 근거로 적지 않는다**
- **`TurnId` 를 세션 상태로 영속하지 않는다**
- **소유자를 권한 검사로 읽지 않는다** — 코어는 강제하지 않는다. 강제는 호스트가 기록된 값을 읽어 한다

---

## 남은 것

소유자의 열린 항목은 [`../../backlog/session-owner-open-items.md`](../../backlog/session-owner-open-items.md) 가 정본이다 —
SO-1(강제), SO-2(소유자 이전), SO-3(다른 노드의 삭제 통지), SO-4(`onCreated`), SO-9(계약 스위트 공개).

---

## 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 레코드 | [`SessionRecord.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecord.java), [`SessionRecordView.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecordView.java) |
| 전사·스냅샷·버퍼 | [`SessionTranscript.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionTranscript.java), [`SessionSnapshot.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionSnapshot.java), [`TranscriptBuffer.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/TranscriptBuffer.java) |
| 라이브 세션 | [`LiveSession.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSession.java), [`DefaultLiveSession.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/DefaultLiveSession.java) |
| 식별자 | [`SessionId.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/SessionId.java) |
| 아키텍처 규칙 | [`SessionNamingArchitectureTest.java`](../../../modules/aimon-session-routing/src/test/java/at/aimon/session/routing/architecture/SessionNamingArchitectureTest.java), [`SessionRecordSoleWriterArchitectureTest.java`](../../../modules/aimon-session-routing/src/test/java/at/aimon/session/routing/architecture/SessionRecordSoleWriterArchitectureTest.java) |

---

## 관련 문서

- [`session-store.md`](session-store.md) — 레코드를 담는 SPI, `SessionStore`, 쓰기 펜스
- [`session-log.md`](session-log.md) — 전사 안의 로그·뷰 상태·봉인
- [`routing.md`](routing.md) — 멀티 노드 라우팅
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명·소유권·소멸 책임
- [`../../overview/glossary.md`](../../overview/glossary.md) — 용어 사전
- [`../../features/session/agent-session-guide.md`](../../features/session/agent-session-guide.md) — `LiveSession` API
