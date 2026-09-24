# 세션 로그 · 봉인 — 등록 항목 5건 (열림 5)

출처는 context engine 과 session log 작업(단계 1~5)과 그 뒤의 두 차례 개선이다. 설계는
[`../design/session/session-log.md`](../design/session/session-log.md) 와
[`../design/agent-execution/context-engine.md`](../design/agent-execution/context-engine.md) 이고, 두 문서의
"구현과의 차이" 절(§12 · §13)이 구현 시점에 남은 틈을 적었다. 그중 **이 작업 안에서 닫지 않은 것**이 여기로 올라왔다.

두 번째 개선에서 닫힌 것 — 조립된 스택의 삭제 펜스(분산 모드), 저장소 단위 고아 스윕, CLI 의 쓰기 형식 스위치 — 은
session-log §12.6 과 context-engine §13.7 에 있고 여기에는 싣지 않았다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**다. 줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은
전부 2026-09-24 기준이다.

---

## SL-1 — 조립된 스택의 레코드 쓰기는 펜스 없이 간다 · **열림**

**무엇을.** 스택의 transcript manager 가 쓰는 레코드 저장을 `SessionStore.records()`(펜스 뷰)로 옮긴다.

**왜.** 분산 모드에서 세그먼트 **삭제**는 이제 리스로 막히지만(session-log §12.3), 같은 노드의 **레코드 쓰기**는 raw
`SessionRecordStore` 로 간다. 리스를 잃은 줄 모르는 노드의 늦은 턴 종료 저장이나 체크포인트가 새 홀더의 레코드를
덮어쓸 수 있고, 새 홀더가 다시 저장할 때까지 레코드는 옛 전사와 옛 manifest 를 든다 — 그 사이에 이 세션을 여는 노드는
되돌아간 대화를 본다. 삭제 펜스가 막는 것보다 큰 위험이다. `AimonStackSegmentFencingTest.staleHolderIsRejected` 가 바로 그 모양 — 삭제는 거절되고 저장은
성공한다 — 을 고정하고 있다.

**어디.** `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java:303` 의
`new DefaultTranscriptManager(sessionRecordStore, …)`. 펜스 뷰를 가진 `SessionStore` 는 라우터 빌드 안에서 manager 보다
뒤에 생긴다 — 삭제 쪽이 `LateBoundFencedSegmentStore` 로 푼 것과 같은 순서 문제다. `SessionStore` javadoc 이 적은
"재증명과 쓰기 사이의 창" 은 이것을 옮겨도 남는다.

**언제 다시 볼까.** 분산 모드 배포에서 노드 이동 뒤 전사가 되돌아가거나 gap 이 보고될 때, 또는 레코드 백엔드에 펜싱
compare-and-set 이 생길 때.

## SL-2 — 단일 노드 스택의 삭제는 raw 로 간다 · **열림**

**무엇을.** 단일 노드 모드에서도 라우터가 리스를 쥔 세션의 삭제는 펜스로 보낼지 정한다.

**왜.** 펜스는 `DeploymentMode.DISTRIBUTED` 에서만 걸린다. 단일 노드에서 펜스를 걸면 라우터 밖에서 만든 라이브 세션 —
CLI 의 것 — 은 리스를 쥐지 않으므로 모든 GC 삭제가 거절되어 고아가 영원히 남는다. 그런데 단일 노드 모드는 durable 리스
저장소를 받을 수 있고(클러스터의 첫 노드를 먼저 올리는 경우가 `AimonStackBuilder.buildSessionRouter` javadoc 의 예다),
그 구성에서 두 번째 노드가 섞이면 삭제가 막히지 않는다. 관측 가능한 결과는 SL-1 과 같은 gap 이다.

**어디.** `AimonStackBuilder.java:299` 의 `fencedDeletes` 조건.

**언제 다시 볼까.** 단일 노드 모드로 durable 리스 저장소를 쓰는 배포가 보고되거나, CLI 가 라우터를 거쳐 세션을 열게 될 때
(그러면 조건을 "리스 저장소가 있으면" 으로 넓힐 수 있다).

## SL-3 — 스윕은 켠 모든 노드에서 같은 일을 반복한다 · **열림**

**무엇을.** 저장소 단위 스윕을 한 노드만 돌게 할지(리더 선출, 또는 스케줄러의 클러스터 잡) 정한다.

**왜.** `SessionLogSegmentSweeper.sweep()` 은 여러 노드가 동시에 돌려도 안전하지만(없는 세그먼트 삭제는 no-op 이다),
노드 수만큼 전체 저장소를 훑는다. 스윕 비용이 저장소 크기에 비례하므로 큰 클러스터에서 켜면 그만큼 읽기 부하가 곱해진다.
지금의 안내는 "한 노드에서만 켜라" 이고, 강제하는 장치는 없다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogSegmentSweeper.java:117`
(`sweep()`), 배선은 `AimonStackBuilder.java:753`(`buildSegmentSweep`).

**언제 다시 볼까.** 스윕 한 회차의 비용이 실측될 때, 또는 `aimon-scheduling-quartz` 의 클러스터 잡에 올릴 다른
애플리케이션 범위 작업이 생길 때.

## SL-4 — Redis 스캔은 standalone 연결만 본다 · **열림**

**무엇을.** Redis Cluster 에서 `scanSessions` 가 모든 마스터 노드를 훑게 한다.

**왜.** `RedisSessionLogSegmentStore` 는 `StatefulRedisConnection` 하나를 받고, `scanSessions` 는 그 연결에 `SCAN` 을
보낸다. 키 배치는 해시 태그로 Cluster 슬롯을 지키지만, `SCAN` 은 연결된 노드의 키 공간만 돈다. Cluster 에 붙이는
방법(클러스터 연결을 받는 생성자)이 생기면 스윕은 **다른 노드의 세션을 빠뜨리고**, 그것은 SPI 계약이 금지하는 유일한
것이다.

**어디.** `modules/aimon-session-redis/src/main/java/at/aimon/session/redis/RedisSessionLogSegmentStore.java:187`
(`scanSessions`), 생성자는 `:102` · `:112`.

**언제 다시 볼까.** 세션 백엔드가 Redis Cluster 연결을 받게 될 때 — 그때 이 메서드가 노드마다 `SCAN` 하도록 같은 변경에서
고친다.

## SL-5 — `/clear` 뒤 늦게 도착한 체크포인트가 지워진 세그먼트를 가리킨다 · **열림**

**무엇을.** `/clear` 의 즉시 삭제(grace 없음)를 늦은 체크포인트보다 뒤로 미루거나, 체크포인트가 `/clear` 이전 스냅샷을
쓰지 못하게 한다.

**왜.** `/clear` 는 비운 레코드를 저장한 뒤 잘린 줄의 세그먼트를 grace 없이 지운다. `mailbox.flush` 가 timeout 으로 놓친
`/clear` 이전의 체크포인트가 그 저장 **뒤에** 쓰이면 레코드는 이미 지워진 세그먼트를 가리키는 옛 manifest 를 다시 든다.
결과는 실패가 아니라 gap(`[history unavailable: …]`)이다. 늦은 체크포인트가 지운 메시지를 되살리는 기존 문제와 뿌리가
같다 — session-log §12.4.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogGarbageCollector.java:106`
(`deleteCleared`).

**언제 다시 볼까.** 늦은 체크포인트가 삭제·rewind 를 되돌리는 문제를 고칠 때 — 같은 변경에서 사라진다.
