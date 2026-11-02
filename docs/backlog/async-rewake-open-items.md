# 비동기 재개 — 등록 항목 4건 (열림 4 · 닫힘 0)

출처는 [비동기 재개](../design/hook/async-rewake.md) §8 이다. 설계 문서는 현재 설계만 적고, 남은 일은 여기에 둔다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**다.

각 항목의 **어디** 줄은 줄 번호 없이 파일만 가리키며, 모두 2026-11-02 에 확인했다. 경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

---

## AR-1 — MCP 알림 브리지가 트랜스포트에 붙어 있지 않다 · **열림**

**무엇을.** `McpNotificationToRewakeBridge` 를 실제 트랜스포트의 읽기 루프에 등록한다.

**왜.** 브리지는 있지만 등록하는 코드가 트리에 없어 MCP 알림 경로는 손으로 배선해야 한다.

**어디.** `hook/rewake/mcp/McpNotificationToRewakeBridge.java`, `mcp/McpNotificationListener.java`. 설계는 [비동기 재개](../design/hook/async-rewake.md) §6.

**언제 다시 볼까.** MCP 알림으로 rewake 를 깨우려는 조립이 생길 때.

## AR-2 — 웹훅 서버를 부팅하는 엔트리포인트가 없다 · **열림**

**무엇을.** `RewakeWebhookServer` 를 시작하는 조립을 둔다.

**왜.** 트리 안에 그 서버를 시작하는 main 소스가 없어 호스트가 직접 만들어야 한다.

**어디.** `modules/aimon-rewake-webhook/src/main/java/at/aimon/rewake/webhook/RewakeWebhookServer.java`. 설계는 [비동기 재개](../design/hook/async-rewake.md) §6.

**언제 다시 볼까.** 트리 안의 어셈블리가 외부 이벤트 트리거를 쓸 때.

## AR-3 — envelope 가 프로세스 메모리에만 있다 · **열림**

**무엇을.** 저장소 SPI 를 먼저 뽑고 그 뒤에 영속 구현을 더한다.

**왜.** `DefaultRewakeService` 의 pending 은 메모리 맵이고 SQL · Mongo 어댑터가 없다. 멀티 인스턴스 운영은 Quartz 잡 저장소가 덮는 범위(시간 트리거)까지이고, 이벤트 트리거 envelope 는 재시작을 넘지 못한다.

**어디.** `hook/rewake/impl/DefaultRewakeService.java`, `modules/aimon-scheduling-quartz/src/main/java/at/aimon/scheduling/quartz/rewake/QuartzRewakeService.java`.

**언제 다시 볼까.** 멀티 인스턴스 배포가 이벤트 트리거를 쓸 때.

## AR-4 — 의미 없는 `asyncRewake` spec 이 파스 에러가 아니라 드롭된다 · **열림**

**무엇을.** 엄격 모드를 둘지 정한다.

**왜.** `{"asyncRewake": true}` 같은 spec 은 드롭으로 처리되어, 쓴 사람이 예약되지 않았음을 로그로만 안다.

**어디.** `config/hook/rewake/RewakeSpecParser.java`.

**언제 다시 볼까.** 이 드롭 때문에 예약이 빠진 보고가 있을 때.
