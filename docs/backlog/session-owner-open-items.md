# 세션 소유자 · 삭제 리스너 — 등록 항목 6건 (열림 6 · 닫힘 0)

출처는 [`../design/session/session-owner-deletion-event.md`](../design/session/session-owner-deletion-event.md)
(#253) 의 구현이다. 그 문서 §8 의 열린 질문 가운데 **이 변경 밖으로 결과가 나가는 것**과, §9(구현 뒤 — 설계와 달라진
점)가 새로 연 것 하나가 여기로 올라왔다. §8 의 Q4(Postgres 마이그레이션 정책)와 Q7(drain 경로에서 소유자를 주는
메시지)은 설계대로 정해져 올리지 않는다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §8 · §9 는 설계·구현 시점의 기록이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 전부 2026-10-17 기준이다.

---

## SO-1 — 코어는 소유자를 기록할 뿐 강제하지 않는다 · **열림**

**무엇을.** 기록된 소유자와 다른 `owner` 를 단 제출을 라우터(또는 `claim`)가 거절할지 정한다 —
`ClaimResult.AgentConflict` 와 나란한 `OwnerConflict` / `SessionOwnerMismatchException` 같은 모양으로.

**왜.** 지금은 다른 소유자를 단 제출도 그대로 실행된다(설계 Q1). 관측 가능한 결과는 **"소유자만 리다이렉트할 수
있다" 는 aimon-sandbox 의 규칙이 코어가 아니라 확장 안에서만 지켜진다**는 것이다 — 그 확장을 쓰지 않는 호스트가
같은 규칙을 기대하면 아무것도 막아 주지 않는다. 코어에는 세션 권한 모델이 없어서 1단계는 기록만 했다.

**어디.** (2026-10-17) `DefaultSessionStore.claim` 의 소유자 단계, `DefaultSessionRouter.runDrainOnly` 의 소유자
provision. 둘 다 "비어 있을 때만 쓴다" 만 하고 비교하지 않는다.

**언제 다시 볼까.** 두 번째 확장이 같은 "소유자만" 규칙을 다시 구현할 때, 또는 호스트가 라우터에 세션 권한을
요구할 때.

---

## SO-2 — 소유자를 옮기는 프리미티브가 없다 · **열림**

**무엇을.** 관리자 재배정·소유권 이전을 위한 펜싱된 명시적 쓰기(`transferOwner(id, from, to)` 같은)를 둘지 정한다.

**왜.** 소유자를 한 번 쓰면 바꾸거나 지우는 SPI 가 없다(설계 Q2, 규칙 5.1). 사용자 계정이 합쳐지거나 퇴사자의
세션을 넘겨야 하는 호스트는 **레코드 저장소를 직접 고치는 것 말고 길이 없다.**

**어디.** (2026-10-17) `SessionRecordStore` — 소유자를 쓰는 메서드는 `provision(SessionId, String, Principal)`
하나다.

**언제 다시 볼까.** 소유권 이전을 요구하는 호스트가 나올 때. 그때 이 쓰기는 다섯 번째 작성자가 되므로
`SessionRecordStore` 클래스 javadoc 의 작성자 목록부터 다시 본다.

---

## SO-3 — 삭제 리스너는 삭제한 노드에서만 불린다 · **열림**

**무엇을.** 노드마다 메모리에 세션별 상태를 두는 확장을 위해 클러스터 fan-out(예: `EVICT` 신호에 `reason=DELETED`
를 싣고 다른 노드에서 `onDeletedElsewhere` 를 부른다)을 둘지 정한다.

**왜.** 지금은 삭제한 노드의 리스너만 불린다(설계 Q3). 공유 상태(샌드박스의 옵션 레코드)는 정리가 한 번이면 되므로
맞지만, **노드 로컬 캐시를 가진 확장은 다른 노드의 캐시를 비울 방법이 없다** — 같은 id 로 새 세션이 생기면 옛
상태가 보인다.

**어디.** (2026-10-17) `DefaultSessionRouter.deleteSession` 의 `EVICT` publish(`reason=SESSION_RELEASED` — 해제와
삭제를 구분하지 않는다).

**언제 다시 볼까.** 노드 로컬 세션 상태를 가진 확장이 리스너를 쓰려 할 때.

---

## SO-4 — `onCreated` 가 없다, 그리고 리스너는 람다로 만들 수 없다 · **열림**

**무엇을.** 세션 생성 이벤트를 더할지 정한다. 더한다면 `provision` 이 "새로 만들었다" 를 보고해야 한다.

**왜.** 이슈는 "최소한 `onDeleted`" 를 요구했고 생성은 미뤘다(설계 Q5). 프로비저닝은 여러 곳(claim, acquire, drain)
에서 일어나고 새 행인지는 백엔드만 알기 때문이다. 부수 결과 하나: 인터페이스의 유일한 메서드가 `default` 라서
**`SessionLifecycleListener` 는 함수형 인터페이스가 아니고 람다로 구현할 수 없다**(설계 §9 DV-7). 콜백이 둘 이상이
되면 이 불편은 자연히 정당해지지만, 하나뿐인 지금은 사용자 코드가 익명 클래스를 쓴다.

**어디.** (2026-10-17) `at.aimon.core.agent.session.SessionLifecycleListener`.

**언제 다시 볼까.** 생성 시점에 세션별 상태를 만들어야 하는 확장이 나올 때. 그때까지 람다가 꼭 필요하다는 요구가
먼저 오면 `static SessionLifecycleListener onDeleted(Consumer<SessionDeletedEvent>)` 팩토리를 더하는 것이 가장 싸다.

---

## SO-5 — 샌드박스의 `SessionOwnerLookup` 에 대응하는 이름 붙은 SPI 가 없다 · **열림**

**무엇을.** 코어가 `SessionOwnerResolver` 같은 이름 붙은 SPI 를 낼지, `SessionRecordView.getOwner()` 로 충분한지
정한다.

**왜.** 호스트는 한 줄로 이을 수 있다 — `id -> stack.sessionRecordStore().load(id).flatMap(SessionRecordView::getOwner)`
(설계 Q6). 그러나 그 한 줄은 레코드 **전체**(전사 포함)를 읽는다. 소유자를 자주 묻는 확장이라면 **소유자 하나를
위해 긴 전사를 매번 디코드한다** — 삭제 경로가 같은 비용을 받아들인 이유(삭제당 한 번)가 조회 경로에는 없다.

**어디.** (2026-10-17) `SessionRecordStore.load` — side field 만 읽는 좁은 읽기가 없다.

**언제 다시 볼까.** aimon-sandbox 가 이 seam 에 호스트 배선을 요구하거나, 소유자 조회가 프로파일에 보일 때.

---

## SO-6 — 빈 문자열 `agentRef` 를 Redis 만 거부한다 · **열림**

**무엇을.** 빈(또는 공백뿐인) `agentRef` 를 `SubmitRequest` 에서 거부해 네 저장소의 규칙을 맞춘다.

**왜.** Redis provision 스크립트가 빈 문자열을 "없음" 표지로 쓰게 되면서 `RedisSessionRecordStore.provision` 은
`""` 를 거부한다(설계 §9 DV-3). in-memory · Postgres · MongoDB 는 여전히 `""` 에 바인딩한다. 관측 가능한 결과는
**같은 잘못된 요청이 백엔드에 따라 바인딩되거나 `IllegalArgumentException` 으로 실패한다**는 것이다.
`AgentRuntimeId.fromName` 이 빈 이름을 거부하지만 claim **뒤**에 불리므로 그 사이에 바인딩이 먼저 써진다.

**어디.** (2026-10-17) `SubmitRequest` 생성자(`agentRef` 는 null 검사뿐), `RedisSessionRecordStore.provision`.

**언제 다시 볼까.** `SubmitRequest` 의 검증을 손볼 때. 바꾸면 Redis 의 거부는 방어선으로만 남는다.
