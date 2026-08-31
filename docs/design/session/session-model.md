# 세션 모델 — 레코드, 전사, 라이브 핸들

**Status**: IMPLEMENTED

세션이라는 하나의 사용자 개념이 코드에서는 **세 개의 타입**으로 갈라진다. 무엇이 영속되고 무엇이
프로세스와 함께 사라지는지, 그 경계가 왜 거기에 있는지, 그리고 그 경계를 흐리면 무엇이 조용히
망가지는지를 정의한다.

- 용어의 뜻: [`../../overview/glossary.md`](../../overview/glossary.md)
- 수명·소유권 규칙: [`../../overview/scope-model.md`](../../overview/scope-model.md)
- 멀티 노드 라우팅: [`routing.md`](routing.md)
- 분산 백엔드: [`backends.md`](backends.md)

---

## 1. 개요 — 1 : 0..N 비대칭

```
one SessionRecord (영속, SessionId 로 식별)  :  0..N LiveSession (일시적, 노드 로컬)
```

한 세션은 살아 있는 핸들이 **0개**일 수도 있고(아무도 대화 중이 아님), 시간에 걸쳐 **여러 개**가
순차적으로 서빙할 수도 있다(idle-TTL 축출, 프로세스 재시작, 노드 간 핸드오프). 이 비대칭이 이 문서
전체의 출발점이다 — 여기서 나오는 규칙은 결국 하나로 압축된다.

> **핸들보다 오래 살아야 하는 값은 레코드 쪽에 둔다.**

`CLAUDE.md` 의 "Multi-instance ready" 원칙이 이 비대칭을 요구한다. 진짜 1:1(핸들을 세션 수명 내내
고정)이면 재수화 코드가 통째로 사라지고 §2 의 이름 문제도 거의 소멸하지만, 대가는 **노드당 무한정
메모리와 배포마다 하드 핸드오프**다. 그리고 1:1 로 가는 첫 삭제 대상이 정확히 재수화 경로인데, 그
삭제는 과거에 실제로 발생했던 "축출 한 번에 누적 totals 가 0" 회귀를 되살린다. 그래서 0..N 이다.

---

## 2. 타입 모델

### 2.1 세 이름

| 타입 | 정체 | 패키지 | 수명 |
|------|------|--------|------|
| `SessionRecord` | 영속 애그리게이트 — 전사 + side field | `agent.session.store` | 명시적 삭제까지 |
| `SessionTranscript` | 시스템 프롬프트 + 메시지 이력, **불변 값 객체** | `agent.session.transcript` | 레코드에 담김 |
| `LiveSession` | 그 세션에 대해 턴을 실행하는 노드 로컬 핸들 | `agent.session` | 열기 ~ `close()` |

`SessionId` 는 `agent.session` 최상위에 있다 — 세 계층이 전부 참조하므로 어느 하위 패키지에도
속하지 않는다.

### 2.2 왜 맨 `Session` 이 비어 있는가

**`Session` 과 `AgentSession` 은 타입 이름으로 금지된다.** 취향의 문제가 아니라 규칙이 작동하느냐의
문제다.

이 코드베이스는 이름 토큰으로 수명을 강제하는 ArchUnit 규칙을 여럿 갖고 있다. 그런데 **두 수명이 같은
토큰을 공유하면 그 규칙들은 통과는 하되 아무것도 막지 못하는 상태(vacuous)** 가 된다 — "이름에
`Session` 이 들어간 타입은 …" 이라는 규칙이 영속 레코드와 노드 로컬 핸들을 구분하지 못하기 때문이다.
이 프로젝트는 공허한 규칙을 **규칙이 없는 것보다 나쁘다**고 판정해 왔다. 규칙이 없으면 사람이 보지만,
초록불이 켜진 공허한 규칙은 아무도 보지 않는다.

그래서 두 수명 중 **어느 쪽도** 맨 이름을 갖지 않는다. 영속 쪽이 무표기(`Session*`)를 갖고 — 사용자에게
"세션"은 재시작을 넘어 이어지는 그것이므로 — 노드 로컬 핸들만 `Live` 라는 표기를 단다. 실수하기 쉬운
쪽이 짧은 이름을 갖는 배치다.

`SessionNamingArchitectureTest` (`aimon-session-routing`, `at.aimon` 전체를 임포트하고 테스트를 제외)가
빌드에서 막는다.

### 2.3 기각한 이름

| 후보 | 기각 사유 |
|------|----------|
| `ActiveSession` | `LiveSessionStatus` 의 `RUNNING`/`IDLE` 과 충돌한다 — "active" 가 이미 상태 어휘다 |
| `SessionRuntime` | `AgentRuntime` 이 세운 관례("`*Runtime` 은 장수명")와 정반대다. 핸들은 가장 짧게 사는 것 중 하나다 |
| `SessionHandle` | 정확하지만 무엇의 핸들인지 말하지 않는다 — `LiveSession` 은 수명까지 말한다 |

### 2.4 새 이름을 지을 때의 세 규칙

1. **재시작 후에도 복원되어야 하면** `Session*`, `agent.session.store` (전사 자체는
   `agent.session.transcript`)
2. **프로세스와 함께 사라져도 무방하면** `LiveSession*`, `agent.session`
3. **에이전트 단위로 한 번 모으면 되면** `Agent*`, `agent`

이름의 마지막 명사로 수명을 추론하면 안 된다 — `SessionRecordStore` 는 세션을 담지만 자기 자신은
application-scoped 다. 판단은 **키와 저장 위치**로 한다
([`../../overview/scope-model.md`](../../overview/scope-model.md) §5.3).

### 2.5 `transcript` 는 `session` 의 자식이다

`agent.session.transcript` 이지 `agent.transcript` 가 아니다. 형제로 두면 전사가 `SessionId` 를
참조하고 세션이 `SessionTranscript` 를 담으므로 **패키지 순환**이 생긴다. 자식으로 두면 부모→자식
방향 하나만 남는다.

같은 이유로 `store` 도 자식이다. `store` 는 `transcript` 를 알지만 그 반대는 아니다.

---

## 3. 레코드의 구조

### 3.1 전사 + 네 개의 side field

```java
class SessionRecord implements SessionRecordView {
    private final SessionId id;
    private SessionTranscript transcript;      // 시스템 프롬프트 + 메시지 이력
    private int compactionFailureCount;        // side
    private String agentRef;                   // side
    private SessionTotals sessionTotals;       // side
    private ExecutionBudget budgetOverride;    // side
}
```

레코드가 **정체성 애그리게이트**이고 전사가 **불변 값 객체**다. 이 분해 이전에는 레코드가 히스토리
변경 메서드(`addMessage` / `addUserMessage` / `addAssistantMessage`)를 직접 노출했다.

그 메서드들을 지울 수 있었던 근거는 단순하다 — **프로덕션 호출자가 0 이었다.** 모든 append 는
`TranscriptBuffer` 를 거쳐 들어오고, 레코드는 완성된 전사를 통째로 받는다. 즉 이 분해는 "쓰기 경로를
바꾸는 변경" 이 아니라 **이미 아무도 쓰지 않던 문을 닫는 변경**이었다.

### 3.2 왜 전사만 불변인가 — `TranscriptBuffer` 는 가변으로 남는다

두 타입은 역할이 다르다.

| | `SessionTranscript` | `TranscriptBuffer` |
|---|---|---|
| 성질 | 불변 값 — `withSystemPrompt` / `append` 가 새 인스턴스를 돌려준다 | 가변 — 턴 진행 중의 작업 버퍼 |
| 수명 | 레코드와 함께 | 턴 1회 |
| 목적 | 저장·비교·공유가 안전 | 이터레이션마다 메시지를 쌓는다 |

턴 하나는 수십 번 append 한다. 매번 리스트를 복사하는 것은 O(n²) 이므로 버퍼는 가변이어야 한다.
레코드에 앉는 순간에만 불변 스냅샷으로 굳는다.

> 성능은 이 분해의 **근거가 아니다.** load/save 왕복의 리스트 복사가 2회에서 0회로 줄어드는 것은
> 사실이지만, 같은 변경이 `append` 를 O(1) 에서 O(n) 으로 만든다. 근거는 "공유해도 안전한 값" 이다.

### 3.3 `compactionFailureCount` 는 전사에 들어가지 않는다

side field 중 이것 하나는 **위치가 강제**되어 있다.

컴팩션 실패 카운터는 `incrementCompactionFailureCount` / `resetCompactionFailureCount` 라는 **독립
원자 primitive** 로 갱신된다. 전사 안에 넣으면 갱신 경로가 "레코드 전체를 읽고 → 카운터를 고치고 →
레코드 전체를 쓴다" 가 되고, 그 사이에 다른 노드가 전사를 append 했다면 **lost update** 가 된다.
컴팩션은 정의상 전사가 클 때 도는 것이므로 그 경합은 이론이 아니다.

같은 이유로 `SessionSnapshot` 은 이 값을 담지 않는다. 스냅샷은 전사의 투영일 뿐이다.

### 3.4 스냅샷 저장의 보존 규칙

`SessionSnapshot` 은 `sessionId` + 시스템 프롬프트 + `getConversationHistory()` **셋뿐**이다.
side field 는 하나도 담지 않는다. 그래서 저장 경로가 규칙을 하나 진다.

> `SessionRecordStore.mergeFromSnapshot` 은 기존 레코드에서 네 side field
> (`compactionFailureCount` / `agentRef` / `sessionTotals` / `budgetOverride`)를 **되살린 뒤** 전사만
> 덮어쓴다.

이것을 잊으면 턴이 끝날 때마다 누적 통계와 예산 오버라이드가 0 으로 돌아간다. 변환의 방향도 여기서
정해졌다 — `SessionRecord.fromSnapshot(SessionSnapshot)` 이지 `SessionSnapshot.toSessionRecord()` 가
아니다. 보존해야 할 것을 아는 쪽은 레코드이므로 레코드가 생성자다.

### 3.5 `getTranscript()` 는 읽기 전용 뷰에 없다

`SessionRecordView` 가 노출하는 것은 `getSystemPrompt()` / `getMessages()` 이지 전사 객체 자체가
아니다. 전사를 통째로 내주면 호출자가 `withSystemPrompt` 로 새 전사를 만들어 **레코드를 우회해**
들고 다닐 수 있고, 그러면 §3.4 의 보존 규칙이 걸리지 않는 두 번째 쓰기 경로가 생긴다.

`getSessionTotals()` 와 `getBudgetOverride()` 가 인터페이스의 `default` 메서드로 남아 있는 것도
같은 계열의 제약이다 — 이 인터페이스에는 트리 밖 익명 구현이 존재하므로 `default` 를 abstract 로
승격하면 컴파일이 깨진다.

---

## 4. 영속 — 무엇이 재시작을 넘는가

### 4.1 두 개의 side field 는 라이브 세션이 소유한다

`sessionTotals` 와 `budgetOverride` 는 **라이브 세션이 쓰고 레코드가 보관하는** 쌍이다.

- **열 때** — `hydrateFromRecord()` 가 레코드에서 두 값을 읽어 핸들의 상태를 seed 한다. 같은
  `SessionId` 로 새 핸들을 열면 누적치가 0 부터가 아니라 **복원된다.**
- **턴이 끝날 때** — `sessionTotals` 에 완료된 턴의 iteration·토큰을 더하고,
  `SessionRecordStore.setTotalsAndBudgetOverride(sessionId, totals, override)` 로 **두 값을 한 쌍씩**
  되쓴다.

이 primitive 는 **델타가 아니라 절대값**이다. 중복 호출이 턴을 두 번 세지 않는다. 레코드가 없으면
no-op 이고, 쓰기 자체가 best-effort 다 — 통계 저장 실패가 턴 결과를 실패로 만들면 안 된다.

> 한때 이 자리에 `ConversationStatePersistence` 라는 좁은 SPI 가 있었다. 라이브 세션이
> `SessionRecordStore` 를 직접 소유하게 되면서 인터페이스를 하나 더 두고 ISP 를 좁힐 이유가
> 사라졌고, SPI 와 그 구현·값 타입은 삭제되었다. 지금 남은 것은 위 primitive 하나다.

### 4.2 예산 오버라이드의 우선순위

```
레코드의 budgetOverride  >  LiveSessionOptions 의 기본 예산
```

핸들을 열 때 opener 가 기본 `ExecutionBudget` 을 준다. 레코드에 오버라이드가 있으면 **그것이 이긴다** —
REPL 의 `/budget` 처럼 사용자가 런타임에 바꾼 값이 재개 시 opener 기본값으로 되돌아가면 안 되기
때문이다.

오버라이드는 **명시적으로 옵션을 세팅할 때만** 기록된다. 옵션을 건드리지 않은 세션의 레코드에는
오버라이드가 없고, 그러면 매번 opener 기본값이 적용된다 — 이것이 의도한 동작이다. 오버라이드를
지우는 것도 같은 경로로, 값이 `null` 이 되어 다시 기본값을 따른다.

### 4.3 상태 조회는 영속/일시로 갈린다

`lastCompletedTurn` 같은 "마지막 턴의 결과" 필드는 상태 조회에서 빠졌다. 그 값이 있으면
`status()` 가 재시작을 넘는 값과 넘지 않는 값을 한 구조체에 섞게 되고, 그때부터 "이 필드는 재시작
후에도 있나?" 를 필드마다 따로 기억해야 한다. 지금은 갈린다 — 누적치는 레코드, 진행 중인 것은 핸들.

### 4.4 재시작을 넘지 않는 것 — 알려진 한계

| | 넘지 않는 것 | 결과 |
|---|---|---|
| L1 | `LiveSessionOptions` 의 locale, source agent id | 재개 시 opener 기본값으로 돌아간다 |
| L2 | 큐에 남은 미실행 메시지 (단일 노드) | 유실. 멀티 노드에서는 `SessionInbox` 가 받아 두므로 살아남는다 |
| L3 | 크래시 시점에 진행 중이던 턴 | 저장되지 않는다 — 완료된 턴만 전사에 들어간다 |
| L4 | `messageTimestamps` | 스냅샷에 없어 재개 후 "메시지 나이" 표시가 리셋된다(외관상) |

L2 가 단일 노드에서만 유실인 것은 우연이 아니다. 멀티 노드 경로는 제출을 인박스에 먼저 적재하므로
큐가 아니라 저장소에 있다 — 자세한 것은 [`routing.md`](routing.md).

---

## 5. 단일 저장소와 리스

### 5.1 레코드와 리스는 한 문으로 들어간다

`SessionStore` 는 `SessionRecordStore`(레코드)와 `SessionLeaseStore`(리스)를 감싼 합성물이다. 둘을
따로 열게 두지 않는 이유는 `claim()` 의 **순서** 때문이다.

```
claim(sessionId, agentRef, holderId, lease)
  1. 리스 선출         — 이 노드가 홀더가 될 수 있는가?
  2. 에이전트 바인딩 검증 — 이 세션이 다른 에이전트에 묶여 있지 않은가?
  3. 레코드 프로비저닝   — 없으면 만든다
```

선출에서 **진 노드는 레코드를 아예 건드리지 않는다.** 그래서 이 연산은 분산 트랜잭션 없이 순서만으로
원자적이다 — 두 백엔드에 걸친 2PC 가 필요 없다. 순서를 뒤집으면(레코드 먼저) 경쟁에서 진 노드가 이미
레코드를 만든 뒤가 되고, 그때부터 "누가 만들었나" 를 추적해야 한다.

`claim()` 은 **매니저 전용 진입점**이다. CLI 는 매니저를 거치지 않고 핸들을 직접 조립하므로 리스를
잡지도, 갱신하지도, 축출 상호작용을 상속하지도 않는다. 그렇다고 in-memory 백엔드의 `claim()` 을
no-op 으로 만들면 안 된다 — 단일 JVM 두 가상 노드 하네스가 in-memory 백엔드에서 **진짜 만료·펜싱
의미론**을 요구한다.

### 5.2 `SessionStore` 는 노드 스코프다

담는 두 저장소는 application-scoped 지만 `SessionStore` 자신은 **노드(매니저)당 하나**다. 이 노드가
쥔 리스의 로컬 레지스트리를 소유하기 때문이다 — 그래야 `records()` 가 호출자마다 펜싱 토큰을 ReAct
호출 사슬 전체에 실어 나르지 않고도 "이 노드가 들고 있는 리스" 를 찾을 수 있다.

> 한 JVM 에 매니저를 둘 두면 **같은 두 백엔드 위에 스토어도 둘** 만들어야 한다. 공유하면 두 매니저의
> 리스 레지스트리가 섞인다.

### 5.3 펜싱은 `findHolder` 로 재증명한다 — `extend` 로는 안 된다

`records()` 는 각 변경 위임 직전에 **리스 권한을 리스 권위에게 다시 묻는다**. 이때 쓰는 것은
`findHolder` 이고, `extend` 를 쓰면 안 된다.

`extend` 는 토큰만 비교하고 **리스 생존을 확인하지 않는다** — in-memory(토큰 동등성만), postgres
(`SQL_EXTEND` 에 `lease_expires_at` 술어 없음), mongo(필터에 시간 술어 없음) 셋 다 이미 만료된 리스를
토큰만 맞으면 **되살린다.** redis 만 거부하고, 그것도 키가 이미 사라졌기 때문이지 의도한 검사가 아니다.
`findHolder` 는 만료와 명시적 release 를 **둘 다 unheld 로** 보는, 백엔드 전체에서 의미가 균일한
유일한 술어다.

효과는 "축출된 홀더의 **다음** 쓰기가 실패한다" 이다.

**남는 창을 정직하게 적는다.** 재증명과 실제 위임 사이의 sub-millisecond 인터리빙은 닫히지 않는다.
따라서 이 재증명을 **"리스 만료·split-brain 의 완전한 해법" 으로 인용하면 안 된다** — 정상 상태
보장이다. 그 창을 닫는 것은 쓰기 경로를 하나로 만드는 것이고, 그것이 §8 의 sole-writer 규칙이다.

**기각한 대안:** 레코드에 `fencingToken` side field 를 추가하고 저장소에 `putIfFenced` primitive 를
얹는 안. `SessionRecord.copyOf(SessionRecordView)` 가 상태를 뷰의 getter 만으로 재구성하므로 그 토큰은
매 load/save 왕복마다 **조용히 사라진다** — in-memory 저장소의 복사 경로가 전부 여기를 통과한다.
살리려면 뷰(그리고 모든 뷰 소비자)를 넓혀야 하는데 그것은 공표된 SPI 변경이다.

### 5.4 리스 숫자는 그대로 둔다

30s 리스 / 10s 갱신 / 15s 스윕. 리스를 줄이는 것이 틀린 레버인 이유는 **갱신 부하의 모집단**이
"실행 중인 턴" 에서 "보유 중인 세션" 으로 바뀌었기 때문이다. 1000 세션 × 30s/10s ≈ **100 extends/sec**
이고, 그 부하는 유휴 스윕·홀더 손실 스윕·상태 하트비트·모든 forward 폴을 함께 돌리는 공유 스케줄러에
얹힌다. 리스를 절반으로 줄이면 그 수가 두 배가 된다.

블랙아웃은 리스 길이가 아니라 **기다림의 상한**으로 줄인다 — 턴 스코프 실패 신호로 origin 의 future 가
5분짜리 forward TTL 을 소진하는 대신 즉시 실패하게 하고, origin 이 이미 돌리고 있는 폴이 선제적 재claim
을 촉발한다.

10s/30s 는 **두 번의 tick 실패분** 여유라는 뜻이다. 공유 풀의 작업이 늘어날 때 그것이 갱신 스레드에
얹히면 안 된다. `extend` 를 만료 인식(fail-closed)으로 바꾸는 것이 옳은 방향이지만, 갱신 tick 을 전용
스레드로 분리하기 **전에** 넣으면 긴 GC 나 포화된 스케줄러 때문에 *큐에 밀린* tick 이 아무도 훔치지
않은 리스에 손실 신호를 발화시킨다. §5.3 의 재증명이 `findHolder` 를 쓰므로 그 수정에 의존하지 않는
것이 다행이다.

---

## 6. 식별자

| 식별자 | 무엇을 가리키는가 | 영속 |
|--------|------------------|------|
| `SessionId` | 영속 세션. 전사·리스·인박스·승인·이벤트 프레임이 모두 이 값으로 조인된다 | O |
| `ExecutionId` | **세션 없는 실행** — 서브에이전트 포크, 스킬 포크, rewake 리플레이, 스케줄 루틴 | X |
| `TurnId` | 턴 1건 — 주소 지정 전용(인터럽트 겨냥, 이벤트 프레임 표시) | X |

### 6.1 왜 하나가 아니라 둘인가 (`SessionId` / `ExecutionId`)

하나로 뭉쳐 두었을 때 호출자들이 두 방향으로 갈라졌다 — **없는 id 를 발명하는 쪽**(포크가 자기
세션 id 를 새로 발급받아, 툴에게는 사용자 세션과 똑같이 읽히지만 실제로는 아무 권한도 뜻하지 않는
값이 되던 자리)과 **id 를 거부하는 쪽**(세션이 없으니 컨텍스트에 아무것도 싣지 않아, 추적 가능한
정체성이 사라지던 자리).

지금은 갈린다. 포크는 `SessionId` 가 **아예 없고** `ExecutionId` 가 정체성이다. 툴 컨텍스트에는
`SESSION_ID` 가 아니라 `EXECUTION_ID` 가 실린다.

포크가 자기를 띄운 세션의 결정(스킬 승인 등)을 물려받아야 하므로, 그 세션의 id 는 `invokingSessionId`
라는 **다른 축**으로 따로 전달된다. `sessionId` 는 *수명*(자기 세션이 무엇인가), `invokingSessionId` 는
*도달 범위*(누구의 결정이 적용되는가)다. 포크가 다시 포크를 띄우면 중간 포크가 아니라 **사용자의**
세션 id 를 그대로 물려준다.

지금 남아 있는 `SessionId` ↔ 포크 변환은 `forkTranscriptLabel` 하나뿐이고, 그것은 전사 버퍼가
`SessionId` 로 타입되어 있어서 생기는 **라벨**이지 조회 키가 아니다.

### 6.2 `TurnId` 는 스코프가 아니다

턴은 컴포넌트를 소유하지 않으므로 이 id 는 주소 지정용이다. 제출 시점에 발급되고 턴이 끝나면 의미가
없다 — **세션 상태로 영속하지 말 것.** id 가 **없는 것은 "알 수 없음 → 드롭" 이 아니라 옛 의미**다
(인터럽트는 라이브 세션 스코프, 이벤트는 세션 전체 전달).

승인 대기로 중단된 턴에만 발급되는 `PendingTurnId` 와는 **다른 타입**이다. 같은 턴을 가리키는 무관한
두 식별자다.

### 6.3 렌더 변수 `${AIMON_SESSION_ID}`

이 리터럴은 한때 `AgentRuntimeId` 로 해소되는 **deprecated 별칭**이었다. 지금은 진짜 세션 id 를
가리킨다.

되찾을 수 있었던 근거는 그 별칭이 **처음부터 deprecated 로 태어났다**는 것이다 —
`AIMON_AGENT_RUNTIME_ID` 라는 정식 철자가 같은 릴리스에 함께 추가되었고, 별칭 사용은 매 렌더마다
WARN 을 찍었으며, javadoc 은 그 이름이 약속하는 값이 아니라고 명시하고 있었다. 즉 이주 대상이 존재한
채로 "쓰지 말라" 고 계속 말해온 심볼이다.

남는 위험을 숨기지 않는다 — WARN 을 무시하고 별칭을 계속 쓴 스킬 본문은 이제 *다른 값*을 받고, 텍스트만
보고는 탐지할 수 없다. 다만 방향이 유리하다. 별칭이 주던 값은 **원하면 안 되는 값이라고 문서가 이미
판정한** 것이므로, per-session 동작을 원했던 본문은 *고쳐지고*, 진짜로 런타임 id 를 원했던 본문은 이미
정식 철자를 쓰라고 안내받아 왔다.

셸 훅 env 쪽은 충돌이 아예 없었다 — `SkillHookEnv` 에 `AIMON_SESSION_ID` 라는 이름 자체가 없었으므로
단순 개명이다.

---

## 7. 함정

이 넷은 전부 실제로 밟았거나, 밟기 직전에 잡힌 것이다.

### 7.1 `List.copyOf` 는 동작 변경이다

전사가 메시지 리스트를 감쌀 때 `List.copyOf` 를 쓰면 안 된다.

```java
// 이전: new ArrayList<>(messages) — null 원소를 허용
// List.copyOf: null 원소를 거부하고, 만들어진 리스트는 contains(null) 에 NPE 를 던진다
//              — 리스트가 비어 있어도 던진다
Collections.unmodifiableList(new ArrayList<>(messages))
```

`List.of()` 는 **비어 있어도** `contains(null)` 에서 NPE 다. 방어적으로 `history.contains(null)` 을
호출하는 코드가 트리 안에 있으면, 리스트를 "더 안전하게" 바꾼 커밋이 정확히 그 방어 코드에서 터진다.
그래서 `empty()` 조차 `Collections.unmodifiableList(new ArrayList<>())` 다.

### 7.2 인터페이스의 `default` 메서드를 abstract 로 올리지 말 것

`SessionRecordView.getSessionTotals()` / `getBudgetOverride()` 는 `default` 다. 트리 안에 익명 구현이
있어 abstract 로 승격하면 컴파일이 깨진다. `default` 는 게으름이 아니라 **공표된 SPI 의 호환성**이다.

### 7.3 성능은 근거가 아니다

§3.2 에서 적었듯 이 분해는 load/save 왕복의 리스트 복사를 2회에서 0회로 줄이지만 `append` 를 O(n) 으로
만든다. 근거로 성능을 내세우면 다음 사람이 그 숫자를 근거로 **되돌린다.**

### 7.4 삭제된 메서드에 대한 ArchUnit 규칙은 공허해진다

```java
noClasses().should().callMethod(SessionRecord.class, "addMessage", ...)
```

이 규칙은 메서드가 **존재할 때** 호출을 막는다. 메서드를 지우면 규칙은 여전히 초록불이지만 아무것도
막지 않는다 — 누군가 메서드를 되살리면 규칙은 그때부터 다시 일하지만, 되살린 그 커밋 자체는 통과한다.

따라서 삭제를 지키려면 **리플렉션으로 부재를 단언하는 테스트를 짝지어야 한다.**
`ArchitectureRulesTest.sessionRecordHasNoAppendMethod` 가 그것이다.

---

## 8. 아키텍처 규칙

| 규칙 | 위치 | 막는 것 |
|------|------|---------|
| `sessionRecordHasNoAppendMethod` | `ArchitectureRulesTest` (core) | `addMessage` / `addUserMessage` / `addAssistantMessage` 의 **부활** (리플렉션) |
| `systemPromptIsNotMutatedOutsideTheStorePackage` | `ArchitectureRulesTest` (core) | `agent.session.store` 밖에서의 `SessionRecord.setSystemPrompt` |
| `SessionNamingArchitectureTest` | `aimon-session-routing` | 맨 `Session` / `AgentSession` 타입 이름 |
| `SessionRecordSoleWriterArchitectureTest` | `aimon-session-routing` | 레코드 쓰기 경로가 둘로 갈라지는 것 |

sole-writer 규칙의 **허용 목록은 지금 비어 있다.** 세 항목(컴팩션 패키지, 바인딩 리졸버, 전사 패키지)이
있었는데, 스냅샷→레코드 변환의 방향을 뒤집어 `SessionRecord.fromSnapshot` 으로 만들면서 전부 사라졌다
(§3.4). 예외가 하나도 없는 규칙이므로 새 예외를 추가하려 할 때는 §5.3 의 "남는 창" 을 먼저 읽는다 —
그 창을 닫는 것이 이 규칙이다.

---

## 관련 문서

- [`routing.md`](routing.md) — 멀티 노드 라우팅, 인박스, 신호, 멱등 제출
- [`backends.md`](backends.md) — MongoDB / PostgreSQL / Redis 저장소 구현
- [`spi-extraction.md`](spi-extraction.md) — 세션 SPI 를 `aimon-core` 로 내린 경위
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명·소유권·소멸 책임
- [`../../overview/glossary.md`](../../overview/glossary.md) — 용어 사전
- [`../../features/session/agent-session-guide.md`](../../features/session/agent-session-guide.md) — `LiveSession` API
