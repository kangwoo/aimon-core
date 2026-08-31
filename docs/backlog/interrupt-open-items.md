# 중단·재시도가 남긴 열린 항목

[`design/agent-execution/interrupt.md`](../design/agent-execution/interrupt.md) 가 `IMPLEMENTED` 로
닫힌 뒤에도 남은 항목들이다. **열림/닫힘의 정본은 이 문서다** — 설계 문서 §14 는 왜 미뤘는지의 근거를
갖고, 무엇이 아직 열려 있는지는 여기가 말한다 ([`README.md`](README.md) 규칙 하나).

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 드리프트하므로 그 날짜 이후의 인용은 다시 세어야 한다.

---

## 0. 착수하며 정정한 것 — 1번의 처방은 "순수 삭제"가 아니었다

[`README.md`](README.md) 규칙 다섯의 사례가 하나 더 나왔다. 1번은 근거도 자리도 정확했고
(Redis·Postgres 의 사본은 정말 자구까지 같았다), 무엇이 합쳐지고 무엇이 안 합쳐지는지도 표로
정확히 갈라 두었다. 틀린 것은 **"Redis·Postgres 는 순수 삭제이고"** 한 줄이다.

두 코덱은 `ObjectMapper` 를 **호출자에게서 받는다**. `RedisSessionInbox` 와 `PostgresSessionInbox`
가 각각 그것을 받는 생성자를 공개하고 있고, 기본값은 `JavaTimeModule` 이 등록된 mapper 다.
`SubmitOptionsCodec` 은 자기 `private static` mapper 를 쓴다. 사본을 지우고 그냥 그 클래스를
부르면 — 컴파일되고, 라운드트립하고, 기존 테스트가 전부 통과한다 — `systemPromptVariables` 와
`executionAttributes` 안의 시간 값만 봉투의 나머지와 **다른 규칙을 따르기 시작한다.** 두 필드가
`Map<String, Object>` 라서 mapper 설정이 곧 와이어이기 때문이다.

| 항목 | 문서가 적은 처방 | 실측 |
|------|-----------------|------|
| **1** | Redis·Postgres 는 **순수 삭제** | 삭제만 하면 **문서 한 장 안에서 규칙이 갈린다**. 사본을 지우는 것은 맞지만 `SubmitOptionsCodec` 에 mapper 를 받는 오버로드가 먼저 필요했다. 라운드트립 테스트로는 보이지 않는다 — 같은 mapper 로 넣고 빼면 무엇을 쓰든 같은 값이 나온다 |

`aimon-core` 는 `jackson-databind` 만 의존하므로(`jsr310` 없음) 코어 쪽 테스트는 같은 성질을
`java.util.Date` + `WRITE_DATES_AS_TIMESTAMPS` 로 증명하고, 실제 배선인 `JavaTimeModule` 경로는
그 모듈이 클래스패스에 있는 Redis 테스트가 확인한다.

**Mongo 는 세 선택지 중 "상수 공유 + 명시" 를 골랐다.** `Document ↔ ObjectNode` 변환을 끼우는 안은
기각했다 — 그 변환이 지나가는 스칼라 매핑이 바로 이 코덱이 일부러 쓰지 않는 것이고
(`normalizeBsonMap` 이 존재하는 이유가 그것이다), BSON `Date` 를 JSON 문자열 규칙으로 끌고 가게 된다.
대신 필드 **이름**을 `SubmitOptionsCodec` 이 공개하고 Mongo 가 그것을 쓴다.

다만 이름 공유가 막아 주는 것은 **개명뿐이고 추가는 아니다.** 그래서 규칙 여섯이 요구하는 자리에
테스트를 한 쌍 놓았다 — 코어 쪽은 세 타입의 선언된 필드와 공개된 이름 집합을 대조하고(속성이 늘면
여기서 먼저 깨진다), Mongo 쪽은 자기 BSON 키 집합을 **자기 리터럴이 아니라 그 같은 상수**와 대조한다
(공유 코덱에만 반영하면 여기서 깨진다). 둘이 맞물려야 닫힌다.

---

## 1. `SubmitOptions` 매핑이 네 곳에 흩어져 있다 — **닫힘** *(2026-08-28)*

**무엇이었나** — Redis·Postgres 인박스 코덱의 `encodeSubmitOptions`/`decodeSubmitOptions` 를 지우고
`SubmitOptionsCodec` 에 합친다.

**왜** — 같은 매핑이 넷이면 **넷이 일치하는 것은 우연**이다. 필드 하나를 `SubmitOptions` 에 추가하면
네 곳을 고쳐야 하고, 셋만 고친 배포는 노드마다 다른 것을 저장한다 — 인박스로 넘어온 턴이 그 노드에서만
principal 을 잃는 식으로, 조용히.

**한 것**

| 사본 | 결과 |
|------|------|
| 공용 `subagent/task/codec/SubmitOptionsCodec.java` | `encode(SubmitOptions, ObjectMapper)` · `decode(JsonNode, ObjectMapper)` 오버로드 추가. 무인자 형태는 기존 `private static` mapper 에 위임하므로 되감기 지점의 동작은 그대로다. 필드 이름 상수와 세 개의 이름 집합(`TOP_LEVEL_FIELDS` · `PRINCIPAL_FIELDS` · `LLM_CALL_METADATA_FIELDS`)을 공개 |
| Redis `internal/InboundMessageCodec.java` | 사본 삭제, 자기 mapper 를 넘겨 공용 코덱 호출 |
| Postgres `internal/InboundMessageRowCodec.java` | 같음 |
| Mongo `internal/InboundMessageCodec.java` | **합치지 않음.** BSON `Document` 라 통화가 다르다. 대신 리터럴을 공용 상수로 바꾸고, 클래스 javadoc 에 "두 번째 표현이며 그대로 둘 것" 과 그 이유를 명시 |

**와이어는 바뀌지 않았다.** 셋의 필드 이름·모양이 이미 같았고 오버로드가 호출자의 mapper 를 그대로
쓰므로, 스트림·테이블에 이미 들어 있는 항목의 해석이 달라지는 곳은 없다. 동작 차이는 하나뿐이다 —
`submitOptions` 안의 principal 이 깨져 있을 때 raw `NullPointerException` 대신
`SessionSnapshotCodecException` 이 나온다. 둘 다 unchecked 이고 둘 다 디코드를 실패시키며, 어느
테스트도 옛 타입을 고정하고 있지 않았다.

**남긴 가드, 그리고 각각이 실제로 빨개지는 것을 본 뮤테이션** — 넷 다 통과하는 것만으로는 아무것도
증명되지 않으므로(규칙 다섯), 하나씩 겨눈 결함을 넣어 보고 **그 하나만** 빨개지는 것을 확인했다.

| 가드 | 겨눈 것 | 넣어 본 결함 | 결과 |
|------|---------|-------------|------|
| `SubmitOptionsCodecTest` — 선언된 필드 ↔ 공개 이름 집합 | `SubmitOptions` 에 속성이 늘었는데 코덱이 모른다 | `TOP_LEVEL_FIELDS` 에서 `FIELD_EXECUTION_ATTRIBUTES` 제거 | FAILED |
| `SubmitOptionsCodecTest` — 완전 채운 인스턴스가 쓰는 키 집합 | 이름은 등록됐는데 인코더가 안 쓴다 | 같음 | FAILED |
| Mongo `InboundMessageCodecTest` — BSON 키 집합 ↔ 같은 상수 | 공유 코덱에만 반영되고 Mongo 가 빠진다 | `TOP_LEVEL_FIELDS` 에 이름 하나 추가 | FAILED — **같은 클래스의 라운드트립 테스트는 PASSED** |
| Redis `InboundMessageCodecTest` — 주입 mapper 가 subtree 까지 도달하는지 | §0 의 그 함정, 즉 무인자 오버로드로 수렴 | `encode(options, mapper)` → `encode(options)` | FAILED — 나머지 9건 전부 PASSED |

셋째 줄이 이 쌍을 만든 이유 그 자체다. 공유 코덱의 모양이 한 칸 늘었을 때 Mongo 의 라운드트립은
**초록인 채로 남는다** — 자기가 모르는 필드는 넣지도 빼지도 않으므로 픽스처와 여전히 같기 때문이다.
빨개지는 것은 자기 리터럴이 아니라 남의 상수와 대조하는 그 한 건뿐이다.

**정정** — 처방의 "순수 삭제" 부분이 틀렸다. §0 참조.

---

## 2. 크로스 노드 제출은 아직 텍스트다 — **열림 (소비자 대기)**

**무엇** — `SubmitRequest` 가 `UserInput` 을 나르게 하고, 인박스 와이어 포맷에 입력 인코딩을 얹는다.

**왜** — `LiveSession` 은 이제 이미지·문서·멀티모달 턴을 받지만, 라우터를 거쳐 다른 노드로 가는 제출은
`String` 이다. 즉 **멀티모달은 핸들을 직접 쥔 호스트만의 것**이고, 같은 애플리케이션이 스케일아웃하는
순간 그 기능이 조용히 사라진다. 라우터는 재시도를 노출하지도 않는다.

**어디** *(2026-08-28 확인)* — `aimon-session-routing/.../SubmitRequest.java:81` (`getUserInput()` 이
`String`). 인코딩 자체는 이미 있다 — `JsonSessionSnapshotCodec` 의 `userInput` (5개 타입 + 중첩
multimodal, 32단계 상한).

**언제 다시 볼까** — 멀티 노드 배포에서 멀티모달 제출을 요구하는 소비자가 나타날 때. 그 전까지는
§12.7 분산 버스와 같은 근거로 만들지 않는다.

---

## 3. 크로스 노드 스케줄 취소의 분산 구현 — **열림 (소비자 대기)**

**무엇** — `ScheduledTaskInterruptBus` 의 브로커 기반 구현.

**왜** — 스케일아웃에서 사용자가 취소를 넣는 노드는 대개 cron 이 발화한 노드가 아니다. 코어가 싣는
것은 노드 하나(`LOCAL_ONLY`)와 JVM 하나(`InMemoryScheduledTaskInterruptBus`)뿐이라, 클러스터에서는
저쪽 실행이 **방금 삭제된 태스크를 위해 남은 스텝을 끝까지 돈다** — 파일을 쓰고 외부 시스템을
호출하면서. 애플리케이션이 직접 구현을 주입하지 않는 한 그렇다.

**어디** *(2026-08-28 확인)* — SPI 는
`aimon-core/.../scheduling/ScheduledTaskInterruptBus.java`. 배선 지점은 `SchedulingSpec` 과
`AimonSchedulingAutoConfiguration` 둘. `aimon-session-{redis,postgres,mongodb}` 중 이 타입을 참조하는
파일은 **하나도 없다**.

**언제 다시 볼까** — 클러스터 배포에서 스케줄 루틴 취소가 실제로 요구될 때. 모양은 정해져 있다 —
`SessionSignalBus` 에 대해 그 세 모듈이 하는 것과 같은 백엔드.

---

## 4. 병렬화 대상이 넓어지면 인터럽트 승격 규칙을 다시 봐야 한다 — **열림 (트리거 대기)**

작업이 아니라 **조건부 재검토**다. 지금 할 일은 없고, 아래 전제가 깨지는 변경을 하는 사람이 §8.2 를
함께 열어야 한다는 사실만 남긴다.

**왜** — 배치 게이트 콜백은 병렬 디스패치 시 워커 스레드에서 돈다. 거기서 관측되는 인터럽트 플래그가
**그 워커에 대한 실제 인터럽트뿐**이라는 것이 §8.2 승격 규칙의 전제이고, 그 전제는
`DefaultParallelToolDispatcher` 가 `NON_INTERRUPTIBLE`·`COOPERATIVE` 도구만 병렬화하기 때문에
성립한다. `THREAD_INTERRUPT`/`EXTERNALLY_TERMINATED` 도구가 워커 풀에 들어가면 남의 플래그를 자기
것으로 읽고 턴 신호로 **승격**시킨다 — 즉 인터럽트되지 않은 턴이 인터럽트된 것으로 끝난다.

**어디** *(2026-08-28 확인)* —
`aimon-core/.../agent/tool/DefaultParallelToolDispatcher.java:316-321`
(`isParallelizableInterrupt`), 승격 쪽은 `CancellationSignals.isInterrupted(coordinator)`.

**언제 다시 볼까** — `isParallelizableInterrupt` 가 허용하는 `InterruptBehavior` 집합이 넓어질 때.
그 메서드 javadoc 이 전제를 적어 두고 있으므로, 고치는 사람이 읽게 되어 있다.
