# 세션 로그 · 봉인 — 등록 항목 5건 (닫힘 5)

출처는 context engine 과 session log 작업(단계 1~5)과 그 뒤의 두 차례 개선이다. 설계는
[`../design/session/session-log.md`](../design/session/session-log.md) 와
[`../design/agent-execution/context-engine.md`](../design/agent-execution/context-engine.md) 이고, 두 문서의
"구현과의 차이" 절(§12 · §13)이 구현 시점에 남은 틈을 적었다. 그중 **이 작업 안에서 닫지 않은 것**이 여기로 올라왔다.

두 번째 개선에서 닫힌 것 — 조립된 스택의 삭제 펜스(분산 모드), 저장소 단위 고아 스윕, CLI 의 쓰기 형식 스위치 — 은
session-log §12.6 과 context-engine §13.7 에 있고 여기에는 싣지 않았다. 아래 다섯 건은 **세 번째 개선에서 모두 닫혔다**
(2026-09-24). 각 항목의 "닫힘" 절이 어떻게 닫혔는지와, 착수해 보니 적힌 것과 달랐던 점을 적는다. 설계 쪽 기록은
session-log §12.7 과 context-engine §13.8 이다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**다. 줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은
전부 2026-09-24 기준이다.

---

## SL-1 — 조립된 스택의 레코드 쓰기는 펜스 없이 간다 · **닫힘**

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

### 닫힘 (2026-09-24, 세 번째 개선)

transcript manager 의 레코드 저장소 자리에 `LateBoundFencedRecordStore`(bootstrap `assemble`)를 주고, 라우터가 생긴 뒤
`SessionRouter.fencedRecordStore(fence)` 에 묶는다 — 삭제 쪽 `LateBoundFencedSegmentStore` 와 같은 순서 풀이다. 분산
모드의 펜스는 `SessionFence.HOLDER_ONLY`(`SessionStore.records()` 그대로)다. `AimonStackSegmentFencingTest.staleHolderIsRejected`
는 이제 "저장도 삭제도 거절되고, 던지는 `save` 는 `SessionNotHeldException` 을 올린다" 를 고정한다.

**적힌 것보다 넓었다.** 항목은 transcript manager 만 말했지만 같은 raw 저장소를 라우터 opener 가 만드는 라이브 세션도 쓴다 —
누계 · 예산 덮어쓰기 · 영속 rewind 가 모두 레코드 쓰기다. 그것도 같은 뷰로 옮겼다. 그리고 **거절된 저장의 처리**가 새로
필요했다: 턴 끝의 `saveSilently` 는 거절을 WARN 한 줄로 남기고 그 저장 뒤의 GC 를 건너뛴다. 매 체크포인트마다 반복되는 거절과
펜스가 거절한 GC 삭제는 DEBUG 로 낮췄다 — 두 번째 개선의 리뷰가 "결함처럼 읽힌다" 고 짚은 WARN 이다. 분산 모드에서 라우터 밖에서
연 라이브 세션은 이제 모든 저장이 거절된다 — 이 모드의 계약(모든 세션은 라우터로 연다)을 문서에 적었다. "재증명과 쓰기 사이의
창" 은 항목이 적은 대로 남는다.

## SL-2 — 단일 노드 스택의 삭제는 raw 로 간다 · **닫힘**

**무엇을.** 단일 노드 모드에서도 라우터가 리스를 쥔 세션의 삭제는 펜스로 보낼지 정한다.

**왜.** 펜스는 `DeploymentMode.DISTRIBUTED` 에서만 걸린다. 단일 노드에서 펜스를 걸면 라우터 밖에서 만든 라이브 세션 —
CLI 의 것 — 은 리스를 쥐지 않으므로 모든 GC 삭제가 거절되어 고아가 영원히 남는다. 그런데 단일 노드 모드는 durable 리스
저장소를 받을 수 있고(클러스터의 첫 노드를 먼저 올리는 경우가 `AimonStackBuilder.buildSessionRouter` javadoc 의 예다),
그 구성에서 두 번째 노드가 섞이면 삭제가 막히지 않는다. 관측 가능한 결과는 SL-1 과 같은 gap 이다.

**어디.** `AimonStackBuilder.java:299` 의 `fencedDeletes` 조건.

**언제 다시 볼까.** 단일 노드 모드로 durable 리스 저장소를 쓰는 배포가 보고되거나, CLI 가 라우터를 거쳐 세션을 열게 될 때
(그러면 조건을 "리스 저장소가 있으면" 으로 넓힐 수 있다).

### 닫힘 (2026-09-24, 세 번째 개선)

**규칙을 하나 더 만들었다 — `SessionFence.UNLESS_HELD_ELSEWHERE`.** 리스 저장소가 **다른** 홀더를 가리키면 거절하고, 이
노드가 쥐었거나 아무도 쥐지 않았으면 통과시킨다. 단일 노드 스택은 리스 저장소를 받았을 때만 이 펜스를 레코드 쓰기와 삭제
양쪽에 건다. 기본 리스 저장소의 단일 노드는 펜스가 없다 — 동작이 바뀌지 않는다.

**"라우터가 리스를 쥔 세션만 펜스" 라는 항목의 문구로는 닫을 수 없었다.** 그 규칙은 리스를 **잃는 순간** 그 세션을 펜스 밖으로
내보낸다 — 로컬 리스 기록이 지워지면 "쥔 세션" 이 아니게 되고, 그러면 막으려던 늦은 쓰기가 raw 로 간다. 판단 기준은 로컬
기억이 아니라 **리스 권위가 지금 누구를 가리키는가**여야 했다. 그 결과 CLI 의 세션(아무도 쥐지 않음)은 저장 · GC 모두 전과
같고(`singleNodeWithLeaseStoreUnheldSessionPasses`), 다른 노드가 쥔 세션은 둘 다 막힌다
(`singleNodeWithLeaseStoreHeldElsewhereIsRejected`). 남는 틈은 "아무도 쥐지 않은 순간의 늦은 쓰기" 다 — `SessionFence`
javadoc 과 session-log §12.4 에 적었다.

## SL-3 — 스윕은 켠 모든 노드에서 같은 일을 반복한다 · **닫힘**

**무엇을.** 저장소 단위 스윕을 한 노드만 돌게 할지(리더 선출, 또는 스케줄러의 클러스터 잡) 정한다.

**왜.** `SessionLogSegmentSweeper.sweep()` 은 여러 노드가 동시에 돌려도 안전하지만(없는 세그먼트 삭제는 no-op 이다),
노드 수만큼 전체 저장소를 훑는다. 스윕 비용이 저장소 크기에 비례하므로 큰 클러스터에서 켜면 그만큼 읽기 부하가 곱해진다.
지금의 안내는 "한 노드에서만 켜라" 이고, 강제하는 장치는 없다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogSegmentSweeper.java:117`
(`sweep()`), 배선은 `AimonStackBuilder.java:753`(`buildSegmentSweep`).

**언제 다시 볼까.** 스윕 한 회차의 비용이 실측될 때, 또는 `aimon-scheduling-quartz` 의 클러스터 잡에 올릴 다른
애플리케이션 범위 작업이 생길 때.

### 닫힘 (2026-09-24, 세 번째 개선)

리더 선출도 Quartz 클러스터 잡도 아니고, **이미 있는 `SessionLeaseStore` 위의 스윕 리스**다. 예약 id
`aimon:segment-sweep`(`SessionLogSegmentSweeper.SWEEP_LEASE_ID`)에 리스를 잡은 노드만 그 회차를 돈다
(`sweepIfClaimed()`). 리스는 만료와 펜싱 토큰을 이미 갖고 있고 네 백엔드가 모두 구현하므로 새 SPI 가 필요 없었다.

**"놓지 않는다" 가 핵심이었다.** 회차가 끝나면 리스를 놓는 모양이 먼저 떠오르지만, 그러면 일정이 어긋난 다른 노드가 몇 초 뒤
같은 간격 안에서 다시 돈다 — 한 번에 하나만 돌 뿐 여전히 노드 수만큼 돈다. 그래서 리스를 **간격 하나만큼** 잡고, 긴 회차에는
페이지마다 연장하고, 끝나도 놓지 않는다. 쥔 노드는 다음 회차에 리스를 다시 따지 않고 자기 리스를 연장한다 — 다시 따면 자기
틱과 리스 만료 중 어느 것이 먼저냐에 따라 간격 하나를 건너뛸 수 있었다(네 번째 개선에서 고침). 쥔 노드가 죽으면 연장이 멈추고
만료 뒤 다음 노드가 잇는다 — 잃는 것은 간격 하나다.
스택은 리스 저장소를 받으면 자동으로 조정한다(holder id 는 `nodeId`). 테스트: `SessionLogSegmentSweeperTest` 의 coordinated
셋, `AimonStackSegmentFencingTest.sweepIsCoordinatedThroughTheLeaseStore`.

## SL-4 — Redis 스캔은 standalone 연결만 본다 · **닫힘**

**무엇을.** Redis Cluster 에서 `scanSessions` 가 모든 마스터 노드를 훑게 한다.

**왜.** `RedisSessionLogSegmentStore` 는 `StatefulRedisConnection` 하나를 받고, `scanSessions` 는 그 연결에 `SCAN` 을
보낸다. 키 배치는 해시 태그로 Cluster 슬롯을 지키지만, `SCAN` 은 연결된 노드의 키 공간만 돈다. Cluster 에 붙이는
방법(클러스터 연결을 받는 생성자)이 생기면 스윕은 **다른 노드의 세션을 빠뜨리고**, 그것은 SPI 계약이 금지하는 유일한
것이다.

**어디.** `modules/aimon-session-redis/src/main/java/at/aimon/session/redis/RedisSessionLogSegmentStore.java:187`
(`scanSessions`), 생성자는 `:102` · `:112`.

**언제 다시 볼까.** 세션 백엔드가 Redis Cluster 연결을 받게 될 때 — 그때 이 메서드가 노드마다 `SCAN` 하도록 같은 변경에서
고친다.

### 닫힘 (2026-09-24, 세 번째 개선)

`RedisSessionLogSegmentStore` 가 `StatefulRedisClusterConnection` 생성자를 얻었고, `scanSessions` 는 마스터를 노드 id 순으로
하나씩 `SCAN` 한다. 커서는 `<nodeId>:<nodeCursor>` 다. **Lettuce 의 클러스터 스캔을 그대로 쓸 수는 없었다** — 그것은 노드 위치를
커서 **객체**에 두므로, SPI 가 요구하는 불투명 **문자열** 커서로 `scanSessions` 두 호출 사이를 넘기면 위치를 잃는다.

통합 테스트는 가능했다: `redis:7-alpine` 두 개를 Docker 네트워크에 띄워 `CLUSTER MEET` 하고 슬롯을 반씩 준 뒤, 노드가 알리는
내부 주소를 게시된 포트로 매핑하는 `MappingSocketAddressResolver` 로 붙는다. 그 위에서 계약 테스트 전체와 "두 마스터의 세션을
모두 보고한다" 가 돈다(`RedisClusterSessionLogSegmentStoreIntegrationTest`). 노드별 순회 — 빈 마스터, 스캔 중에 빠진 노드,
잘못된 커서 — 는 가짜 노드로 단위 테스트한다(`KeyspaceScannerTest`). 스캔 중 토폴로지가 바뀌면 그 회차는 옮겨 간 슬롯의 키를
놓칠 수 있다 — `SCAN` 자체의 주의사항이고 다음 회차가 본다. 클래스 javadoc 에 적었다.

## SL-5 — `/clear` 뒤 늦게 도착한 체크포인트가 지워진 세그먼트를 가리킨다 · **닫힘**

**무엇을.** `/clear` 의 즉시 삭제(grace 없음)를 늦은 체크포인트보다 뒤로 미루거나, 체크포인트가 `/clear` 이전 스냅샷을
쓰지 못하게 한다.

**왜.** `/clear` 는 비운 레코드를 저장한 뒤 잘린 줄의 세그먼트를 grace 없이 지운다. `mailbox.flush` 가 timeout 으로 놓친
`/clear` 이전의 체크포인트가 그 저장 **뒤에** 쓰이면 레코드는 이미 지워진 세그먼트를 가리키는 옛 manifest 를 다시 든다.
결과는 실패가 아니라 gap(`[history unavailable: …]`)이다. 늦은 체크포인트가 지운 메시지를 되살리는 기존 문제와 뿌리가
같다 — session-log §12.4.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogGarbageCollector.java:106`
(`deleteCleared`).

**언제 다시 볼까.** 늦은 체크포인트가 삭제·rewind 를 되돌리는 문제를 고칠 때 — 같은 변경에서 사라진다.

### 닫힘 (2026-09-24, 세 번째 개선)

**두 선택지 중 "삭제를 미룬다" 쪽이다. 그리고 미루는 조건은 grace 가 아니라 drain 의 결과다.** "체크포인트가 `/clear` 이전
스냅샷을 쓰지 못하게" 하는 쪽은 성립하지 않았다 — 문제의 체크포인트는 스냅샷을 찍은 뒤 저장소 호출 **안에서** 걸려 있으므로,
쓰기 전에 두는 어떤 검사도 이미 지나간 뒤다. 레코드 백엔드의 compare-and-set 없이는 막을 자리가 없다.

`/clear` 삭제에 grace 를 주는 쪽은 성립하지만 값이 비쌌다 — `/clear` 의 물리 삭제가 최소 1시간, 그 세션에 다음 턴이 없으면
스윕이 켜져 있을 때까지 늦어진다. 그래서 더 좁게 잡았다: `SessionCheckpointMailbox.drain(sessionId)` 가 **이 세션의 옛 상태
쓰기가 아직 착지할 수 있는지**를 답하고, transcript manager 는 그 답이 "있다" 인 저장 뒤에만 삭제(`/clear` 와 GC 둘 다)를 미룬다.
drain 이 끝나는 평소 경로에서 `/clear` 는 전처럼 즉시 지운다. 미룬 `/clear` 삭제는 버퍼에 남아 다음 저장에서 다시 시도된다.

**뿌리 쪽 문제는 남는다.** 늦게 착지한 체크포인트는 여전히 지운 메시지를 레코드에 잠시 되살린다(다음 쓰기가 다시 비운다).
이 항목이 막은 것은 그 결과가 gap 이 되는 것이다. 테스트:
`DefaultTranscriptManagerSealingTest.aClearWhoseDrainGaveUpDefersItsDeletesUntilALateCheckpointCannotLand`(백그라운드 mailbox,
저장소 호출 안에 걸린 체크포인트, 착지 순간에 세그먼트가 모두 남아 있는지 확인) 와 `SessionCheckpointMailboxTest` 의 drain 넷.
