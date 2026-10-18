# 세션 소유자 · 삭제 리스너 — 등록 항목 8건 (열림 5 · 닫힘 3)

출처는 [`../design/session/session-owner-deletion-event.md`](../design/session/session-owner-deletion-event.md)
(#253) 의 구현이다. 그 문서 §8 의 열린 질문 가운데 **이 변경 밖으로 결과가 나가는 것**과, §9(구현 뒤 — 설계와 달라진
점)가 새로 연 것 하나가 여기로 올라왔다. §8 의 Q4(Postgres 마이그레이션 정책)와 Q7(drain 경로에서 소유자를 주는
메시지)은 설계대로 정해져 올리지 않는다.

SO-7 · SO-8 의 출처는 SO-5 를 닫은 소유자 좁은 읽기(#262)
[`../design/session/session-owner-narrow-read.md`](../design/session/session-owner-narrow-read.md) 의 §8 Q1 · Q2 다
(그 문서 §9.3).

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §8 · §9 는 설계·구현 시점의 기록이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. SO-1~SO-6 의 인용은 2026-10-17, SO-7 · SO-8 의 인용은 2026-10-18 기준이다.

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

## SO-4 — `onCreated` 가 없다 · **열림**

**무엇을.** 세션 생성 이벤트를 더할지 정한다. 더한다면 `provision` 이 "새로 만들었다" 를 보고해야 한다.

**왜.** 이슈는 "최소한 `onDeleted`" 를 요구했고 생성은 미뤘다(설계 Q5). 프로비저닝은 여러 곳(claim, acquire, drain)
에서 일어나고 새 행인지는 백엔드만 알기 때문이다. 부수 결과 하나: 인터페이스의 유일한 메서드가 `default` 라서
**`SessionLifecycleListener` 는 함수형 인터페이스가 아니고 람다로 구현할 수 없다**(설계 §9 DV-7).

*(2026-10-17, PR #259 리뷰 1)* 람다 쪽은 해결했다 — `static SessionLifecycleListener whenDeleted(Consumer<SessionDeletedEvent>)`
팩토리를 더했다. 아래 "언제 다시 볼까" 가 적어 둔 바로 그 처방인데, 이름 하나가 달랐다: 처방대로 `onDeleted` 로
이름 붙이면 인스턴스 메서드 `onDeleted(SessionDeletedEvent)` 와 겹쳐 `onDeleted(null)` 호출이 모호해져 컴파일되지
않는다(테스트를 쓰다 걸렸다). 그래서 `whenDeleted` 다. 이 항목에 남은 것은 `onCreated` 하나다.

**어디.** (2026-10-17) `at.aimon.core.agent.session.SessionLifecycleListener`.

**언제 다시 볼까.** 생성 시점에 세션별 상태를 만들어야 하는 확장이 나올 때. 그때 `onCreated` 를 더하면 람다 형태도
`whenCreated` 로 나란히 둔다.

---

## SO-5 — 샌드박스의 `SessionOwnerLookup` 에 대응하는 이름 붙은 SPI 가 없다 · **닫힘** *(2026-10-18, #262)*

**무엇을.** 코어가 `SessionOwnerResolver` 같은 이름 붙은 SPI 를 낼지, `SessionRecordView.getOwner()` 로 충분한지
정한다.

**왜.** 호스트는 한 줄로 이을 수 있다 — `id -> stack.sessionRecordStore().load(id).flatMap(SessionRecordView::getOwner)`
(설계 Q6). 그러나 그 한 줄은 레코드 **전체**(전사 포함)를 읽는다. 소유자를 자주 묻는 확장이라면 **소유자 하나를
위해 긴 전사를 매번 디코드한다** — 삭제 경로가 같은 비용을 받아들인 이유(삭제당 한 번)가 조회 경로에는 없다.

**어디.** (2026-10-17) `SessionRecordStore.load` — side field 만 읽는 좁은 읽기가 없다.

**언제 다시 볼까.** aimon-sandbox 가 이 seam 에 호스트 배선을 요구하거나, 소유자 조회가 프로파일에 보일 때.

**닫음 (2026-10-18, #262).** `SessionRecordStore.loadOwner(id)` 가 소유자 하나만 읽는다. `load(id).flatMap(
SessionRecordView::getOwner)` 로 떨어지는 `default` 라서 서드파티 저장소는 고치지 않아도 같은 답을 낸다(비용만 예전
그대로). 출하되는 저장소는 모두 재정의한다 — Postgres 는 `owner` 열 하나, MongoDB 는 프로젝션, Redis 는 `HGET`,
in-memory 는 필드를 복사 없이. 감싸는 두 저장소(`DefaultSessionStore` 의 펜스 뷰, `LateBoundFencedRecordStore`)는
`load` 처럼 펜스 없이 넘긴다. 빈 답은 "레코드 없음" 과 "소유자 없음" 을 함께 뜻한다 — 샌드박스의
`require-principal` 경로는 둘을 가르지 않는다. 설계와 이탈:
[`../design/session/session-owner-narrow-read.md`](../design/session/session-owner-narrow-read.md).

- **"이름 붙은 SPI" 쪽은 하지 않기로 했다.** 이 항목의 제목은 `SessionOwnerResolver` 같은 별도 SPI 를 물었다. 저장소가
  이미 소유자를 갖고 있으므로 SPI 를 하나 더 두면 호스트가 이을 것만 늘어난다. 실제 비용 — 소유자 하나를 위해 전사
  전체를 디코드하는 것 — 은 저장소의 좁은 읽기로 닫힌다.
- **샌드박스는 아직 이 이득을 받지 않는다.** `SessionOwnerLookup.recorded` 가 `loadOwner` 를 부르게 바뀌어야 한다.
  그 일은 SO-8 이다 — 같은 날 aimon-sandbox#30 으로 닫혔다.

---

## SO-6 — 빈 문자열 `agentRef` 를 Redis 만 거부한다 · **닫힘** *(2026-10-17, #260)*

**무엇을.** 빈(또는 공백뿐인) `agentRef` 를 `SubmitRequest` 에서 거부해 네 저장소의 규칙을 맞춘다.

**왜.** Redis provision 스크립트가 빈 문자열을 "없음" 표지로 쓰게 되면서 `RedisSessionRecordStore.provision` 은
`""` 를 거부한다(설계 §9 DV-3). in-memory · Postgres · MongoDB 는 여전히 `""` 에 바인딩한다. 관측 가능한 결과는
**같은 잘못된 요청이 백엔드에 따라 바인딩되거나 `IllegalArgumentException` 으로 실패한다**는 것이다.
`AgentRuntimeId.fromName` 이 빈 이름을 거부하지만 claim **뒤**에 불리므로 그 사이에 바인딩이 먼저 써진다.

**어디.** (2026-10-17) `SubmitRequest` 생성자(`agentRef` 는 null 검사뿐), `RedisSessionRecordStore.provision`.

**언제 다시 볼까.** `SubmitRequest` 의 검증을 손볼 때. 바꾸면 Redis 의 거부는 방어선으로만 남는다.

**닫음 (2026-10-17, #260).** `SubmitRequest` 가 `build()` 에서 `AgentRuntimeId.fromName` 과 같은 규칙으로 `agentRef`
를 검사한다 — 공백뿐이거나 `:` 를 담으면 `IllegalArgumentException`. 그래서 잘못된 이름은 claim 에 닿지 않는다.
Redis 의 `""` 거부는 라우터를 거치지 않고 저장소를 직접 부르는 호출자를 위한 두 번째 방어선으로 남는다.
`contextDiscriminator` 는 원래부터 빌더에서 같은 규칙으로 검사하고 있었으므로 손대지 않았다. 테스트:
`SubmitRequestTest`, `SessionRouterOwnerTest.invalidAgentRefCannotBindTheSession`.

- **근거가 달랐다 (규칙 둘).** 이 항목은 문제를 "백엔드마다 다르게 동작한다" 는 **불일치**로 적었다. PR #259 리뷰 1
  이 짚은 실제 결과는 그보다 크다. 라우터는 claim(바인딩)을 먼저 하고 `fromName` 을 나중에 부르며, 바인딩은
  first-wins 다. 그래서 `fromName` 이 거부하는 이름은 **백엔드와 상관없이** 세션을 영구히 그 이름에 묶었고,
  이후 유효한 agent 로 오는 제출은 모두 `ConflictingAgentException` 으로 거절됐다. 세션을 지우기 전까지 풀리지
  않았다. 이 상태를 만든 것은 빈 문자열만이 아니었다. 공백뿐인 이름과 `:` 를 담은 이름(`"ops:prod"`)도 같았고,
  `:` 쪽은 어디에도 등록돼 있지 않았다. 이 결함은 #253 이전부터 있었다.
- **심각도 (규칙 셋).** 적힌 것보다 무겁다. "같은 잘못된 요청이 백엔드에 따라 다르게 실패한다" 가 아니라 "잘못된
  요청 한 번이 그 세션 id 를 쓸 수 없게 만든다" 였다. 다만 호출자가 잘못된 이름을 보낼 때만 일어난다.

---

## SO-7 — `SessionRecordStore` 하나만을 위한 계약 스위트가 없다 · **열림**

**무엇을.** 세 백엔드의 `*SessionRecordStoreIntegrationTest` 가 각자 반복하는 검사를 `aimon-session-testkit` 의
`AbstractSessionRecordStoreContractTest` 로 뽑아, 모든 백엔드(그리고 서드파티 저장소)가 같은 계약을 통과하게 한다.

**왜.** 레코드 저장소 계약 — provision 의 first-wins, 쓰기마다 side field 를 지키는 것, `loadOwner` 가 `load` 와 같은
답을 내는 것 — 은 백엔드마다 따로 쓴 테스트로만 지켜진다. 멀티 노드 계약 스위트는 이것을 대신하지 못한다.
`TwoNodeSessionHarness` 가 두 노드에 `InMemorySessionRecordStore` 하나를 나눠 주므로, 백엔드 실행에서도 레코드
저장소는 in-memory 다. #253 이 그것을 적었고(DV-9), #262 의 설계는 그것을 놓쳤다(설계 §9.1 DV-1). 관측 가능한 결과는
**새 백엔드나 새 계약 메서드가 셋 중 한 테스트에서만 검사되고 나머지에서 빠져도 빌드가 모른다**는 것이다.

**어디.** (2026-10-18) `TwoNodeSessionHarness` 의 `sharedRepository`, 세 백엔드의
`*SessionRecordStoreIntegrationTest`.

**언제 다시 볼까.** `SessionRecordStore` 에 메서드가 하나 더 붙을 때, 또는 네 번째 백엔드가 생길 때.

---

## SO-8 — aimon-sandbox 는 아직 소유자를 위해 레코드 전체를 읽는다 · **닫힘** *(2026-10-18, kangwoo/aimon-sandbox#30)*

**무엇을.** aimon-sandbox 의 `SessionOwnerLookup.recorded(...)` 가 `load(id).flatMap(SessionRecordView::getOwner)`
대신 `loadOwner(id)` 를 부르게 하고, 그것이 실릴 코어 릴리스를 정한다.

**왜.** SO-5 의 비용은 코어가 아니라 샌드박스의 hot path(메인 턴 루트 바인드마다, 세션 옵션 호출마다)에서 난다.
코어가 좁은 읽기를 내도 샌드박스가 옛 호출을 쓰는 동안은 **소유자 확인 한 번의 비용이 여전히 전사 길이에 비례한다.**
샌드박스는 `loadOwner` 를 담은 코어 릴리스가 나와야 바꿀 수 있다.

**어디.** (2026-10-18) aimon-sandbox `SessionOwnerLookup.recorded` 와 그 javadoc 의 "core has no narrower read yet"
문장(aimon-sandbox `847dad2`). 이 저장소는 고치지 않는다.

**언제 다시 볼까.** `loadOwner` 를 담은 코어 릴리스가 나갈 때. 그때 aimon-sandbox 에 이슈를 연다(#27 에 이어서).

**닫음 (2026-10-18, kangwoo/aimon-sandbox#30).** 코어 릴리스를 기다리지 않았다 — aimon-sandbox `main` 은 이미
`0.3.3-SNAPSHOT` 에 고정돼 있어서, #262 머지 뒤 다시 올린 스냅샷으로 바로 옮길 수 있었다. `SessionOwnerLookup.recorded`
가 `loadOwner(id)` 를 부르고, `load` 를 부르면 실패하는 저장소로 그것을 고정하는 테스트가 붙었다. 실릴 코어 릴리스는
**0.3.3** 이다 — 샌드박스의 `[Unreleased]` 가 이미 0.3.3 을 요구하고, 그 SNAPSHOT 고정은 릴리스 전에 지워야 하므로
`loadOwner` 없는 0.3.3 으로 풀릴 길이 없다. 같은 PR 이 SNAPSHOT 고정이 있는 동안 매 빌드 스냅샷을 다시 확인하게 했다
(CI 의 Gradle 캐시가 하루 지난 스냅샷으로 `loadOwner` 를 못 찾았다).

