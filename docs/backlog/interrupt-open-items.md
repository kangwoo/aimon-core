# 중단·재시도가 남긴 열린 항목 — 등록 항목 5건 (열림 3 · 닫힘 2)

[`design/agent-execution/interrupt.md`](../design/agent-execution/interrupt.md) 가 `IMPLEMENTED` 로
닫힌 뒤에도 남은 항목들이다. **열림/닫힘의 정본은 이 문서다** — 설계 문서 §14 는 왜 미뤘는지의 근거를
갖고, 무엇이 아직 열려 있는지는 여기가 말한다 ([`README.md`](README.md) 규칙 하나).

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 드리프트하므로 그 날짜 이후의 인용은 다시 세어야 한다.

---

## 0. 착수하며 정정한 것

### 0.1 1번의 처방은 "순수 삭제"가 아니었다

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

### 0.2 2번의 *"인코딩 자체는 이미 있다"* 는 참이지만 재사용 가능하다는 뜻은 아니었다

규칙 둘의 사례이되 조금 다른 종류다. 2번의 **인용**은 전부 참이었다 — 인용한 줄도, 그 줄이 말하는
동작도. `SubmitRequest:81` 은 정말 `String` 을 돌려주고 있었고, `LiveSession` 은 정말 `UserInput` 을
받고 있었고, `JsonSessionSnapshotCodec` 은 정말 5개 타입과 32단계 상한을 갖고 있었다. 규칙 여섯도
통과한다 — `SubmitRequest` 는 스타터의 `DefaultAimonSessions.newRequest` 가 main 소스에서 만들고,
`InboundMessage` 는 `DefaultSessionRouter` 가 두 곳에서 만든다. 테스트 전용 타입이 아니다.

(**심각도**는 참이 아니었다. 이 절의 초판은 여기에 "심각도도" 를 함께 적었는데, 그것이 규칙 셋이 겨누는
바로 그 문장이었다 — §0.3 이 그 정정이다.)

틀린 것은 **"얹기만 하면 되고"** 라는 크기 추정이다. 그 인코딩은 `JsonSessionSnapshotCodec` 의
**private 메서드 넷**이었다. 인박스 코덱 셋에서 부를 수 있는 것이 아니므로, 실제 순서는 "얹는다" 가
아니라 **꺼내고 → 얹는다** 였다. 그것이 별도 커밋 하나(`UserInputCodec` 추출)가 된 이유이고, 안 꺼냈다면
같은 매핑의 사본이 넷이 되어 **1번이 방금 지운 상황**이 그대로 재현됐을 것이다.

| 항목 | 문서가 적은 것 | 실측 |
|------|---------------|------|
| **2** | 인코딩은 이미 있으니 *"인박스 와이어 포맷에 얹기만 하면"* 된다 | 인코딩은 **`private`** 이었다. 얹기 전에 꺼내야 했고, 안 꺼내면 인박스 코덱 셋이 각자 사본을 갖게 되어 1번이 방금 없앤 상태로 돌아간다 |

그리고 항목이 **적지 않은 것**이 하나 있었는데, 그것이 이 작업에서 제일 조심스러운 부분이었다 —
`IdempotencyEntry.inputHash`. 항목은 "와이어 포맷" 만 말하지만 `DefaultSessionRouter` 는 제출 입력의
sha256 을 **공유 멱등 저장소에 쓴다**. 그것도 노드 간 계약이고, 텍스트 턴의 해시를 바꾸면 롤링 업그레이드
중의 정상 재시도가 전부 `IdempotencyConflictException` 이 된다. 항목의 "어디" 칸이 짚은 두 자리
(`SubmitRequest` · 인박스 코덱) 밖에 있었고, `getUserInput()` 호출자를 세어야 나온다.

### 0.3 2번의 심각도는 "조용한 손실" 이 아니라 **컴파일 벽**이었다 — 그리고 그것이 닫는 근거를 무너뜨렸다

[`README.md`](README.md) 규칙 셋의 사례다. 근거는 읽어서 확인되지만 심각도는 돌려 봐야 나오는데, 이
항목에서는 **돌려 볼 필요조차 없었다** — 세어 보기만 하면 됐다. §0.2 는 크기 추정을 정정하면서 이 축을
그냥 지나쳤고, 착수 리뷰에서 지적을 받고서야 확인했다.

| 항목·닫는 글이 적은 것 | 실측 (`main` 기준) |
|---|---|
| 스케일아웃하는 순간 멀티모달이 **조용히 사라진다**. 요청은 성공하고 턴은 돌고 모델은 `[Image: …]` 를 받는다 | 그 상태를 **만들 수 없다.** `main` 의 `SubmitRequest` 는 `userInput(String)` 하나뿐이고 필드도 initial commit 이래 계속 `String` 이다. `aimon-session-routing`·`aimon-spring-boot-starter` 의 main 소스에 `asText()` 호출이 **0건**이라 `UserInput` 을 받아 납작하게 만드는 프레임워크 경로가 없고, 두 봉투를 만드는 main 소스 전부가 이미 `String` 인 값을 그대로 나른다. `QueuedInput` 도 `String inputText` 단일 필드다 |

즉 `main` 의 상태는 **컴파일 벽**이다. `[Image: …]` 를 만들 수 있는 유일한 주체는 `image.asText()` 를
직접 적은 호출자이며, 그것은 프레임워크의 침묵이 아니라 호출자의 명시적 행위다.

**이것이 문구 문제로 끝나지 않는 이유**는 그 심각도가 §2 를 **소비자 트리거 없이 닫은 유일한 근거**였기
때문이다. 손실이 컴파일 에러라면 트리거는 이 자리를 지나가고, 그러면 3번을 열어 둔 기준과 같은 기준을
두 항목에 다르게 적용한 것이 된다. 정정 후의 닫는 근거는 §2 에 다시 썼다 — 요약하면 **트리거는 멀쩡했고,
이 항목은 그 앞에서 처리되었다.**

그리고 이 문장은 **사용자 문서 여섯 자리로 복제되어 있었다**(배포 모듈의 공개 javadoc · CHANGELOG ·
임베딩 가이드 정본과 번역본 · 라우팅 설계 문서 · 테스트 javadoc 둘). 규칙 일곱이 파생 뷰에 대해 말하는
것이 여기서도 그대로다 — **일치는 검증이 아니라 복제다.** 여섯이 서로 맞았던 이유는 여섯이 같은 한
문장에서 나왔기 때문이지 그 문장이 맞아서가 아니었다.

### 0.4 "인박스 항목은 아무도 못 돌린다" 는 원칙을 **새로 만든 사이드카에는 적용하지 않았다**

[`README.md`](README.md) 규칙 다섯의 변형이다. 처방이 틀린 것이 아니라, **처방이 세운 원칙을 처방 자신이
만든 새 경로에 적용하지 않았다.**

와이어 설계는 옛 노드가 새 항목을 읽는 방향을 공들여 막았고, 그 근거로 코덱 javadoc 에 *"an inbox entry
nobody can decode is a turn nobody runs"* 를 적었다. 그런데 **거울상** — 이 빌드가 새 노드의 항목을 읽는
방향 — 은 `UserInputCodec.decode` 를 무조건 부르고 있었다. 여섯 번째 `InputType` 이 생기는 다음 릴리스에
정확히 그 경로가 열린다.

대가가 큰 이유는 세 백엔드가 전부 **디코드 전에 저장소에서 지우기** 때문이다.

| 백엔드 | 지우는 시점 | 디코드 실패의 결과 |
|---|---|---|
| Redis | collect 스크립트가 Lua 안에서 배치 전체를 `XDEL` 한 뒤 반환 | 못 읽은 것 **앞뒤 가릴 것 없이 그 배치 전부** 소실 |
| Postgres | `DELETE … RETURNING` 을 `commit()` 한 뒤 디코드 루프 | 같음 |
| MongoDB | `findOneAndDelete` 를 배치 크기만큼 **반복**하며 `out` 에 모은다 | 못 읽은 것 **과 그 앞에 이미 지워진 정상 항목 전부** 소실. 아직 안 닿은 것은 저장소에 남는다 |

**이 표의 Mongo 행은 처음에 "그 항목 소실" 로 적혀 있었고 그것은 과소평가였다.** `MongoSessionInbox.collect`
는 `findOneAndDelete` 를 루프로 돌면서 결과를 `out` 에 쌓으므로, 가운데에서 던지면 앞서 지워진 정상 항목이
`out` 과 함께 버려진다 — 2차 리뷰가 실컨테이너에서 3건 중 가운데를 깨뜨려 재현했다(`docs-left=1`,
생존자는 뒤쪽 하나, 앞쪽 정상 항목은 **저장소에서도 사라지고 아무에게도 전달되지 않음**). 같은 브랜치의
`decodeOrText` javadoc 과 `frozen-names.md` 는 처음부터 *"every message that call collected"* 로 정확히
적고 있었으므로, 틀린 것은 이 표 하나였다 — 같은 사실에 대한 세 서술 중 하나만 어긋난, 규칙 일곱의 모양.

인박스 코덱의 `catch` 는 `IOException` 뿐이고 `SessionSnapshotCodecException` 은 `RuntimeException` 이라
그대로 빠져나간다. 게다가 **바로 옆 옛 키에 멀쩡한 문자열이 있다** — 옛 노드가 돌렸을 바로 그 렌더다.

고친 방법과 그 비대칭(스냅샷은 거절, 인박스는 저하)의 근거는 `UserInputCodec.decodeOrText` 의 javadoc 에
있다. 저하는 **WARN 으로 관측 가능**하다 — 조용히 텍스트로 떨어지면 이 항목이 없애려던 실패 모드가 그대로
재현되기 때문이다.

| 가드 | 넣어 본 결함 | 결과 |
|------|-------------|------|
| 백엔드 3종의 `unreadableSidecarDegradesToText` | 저하를 없애고 다시 거절 | 셋 다 FAILED |
| `UserInputCodecTest` — 저하가 WARN 을 남기는가 | `degrade` 에서 로그만 제거 | FAILED (저하 자체는 여전히 통과) |
| 같은 클래스 — `decode` 는 여전히 거절하는가 | — | 비대칭이 의도임을 고정 |

**선재 여부**: 배치 소실 자체는 이 브랜치가 만든 것이 아니다. `main` 에서도 깨진 `initiator` 나 알 수 없는
`priority` 가 같은 자리에서 던진다. 이 브랜치가 더한 것은 **정상 업그레이드에서 예상되는** 트리거다 —
앞의 것들은 손상된 문서를 뜻하지만, 알 수 없는 입력 타입은 **더 새로운 빌드가 쓴 멀쩡한 문서**를 뜻한다.
그래서 `catch` 를 넓히지 않고 **입력 필드 한 자리만** 저하시킨다: 손상 신호는 그대로 던져야 한다.
남아 있는 선재 동작은 5번으로 등록했다.

### 0.5 §0.4 의 저하 게이트를 **예외 타입**으로 그었더니 절반이 새어 나갔다

[`README.md`](README.md) 의 *"방금 고친 것의 옆자리"* 항목(B-33)과 같은 모양이다 — 새 결함이 아니라
**방금 쓴 처방의 경계가 잘못 그어진 것**이고, 2차 리뷰가 실컨테이너로 잡았다.

§0.4 의 `decodeOrText` 는 `SessionSnapshotCodecException` **만** 잡았다. 그런데 `decodeAt` 은 값 객체
팩토리를 직접 부르고 `ImageInput.of`·`AudioInput.of` 는 MIME 접두어가 안 맞으면 **`IllegalArgumentException`**
을 던진다. 그 예외는 `catch` 를 그냥 통과해 `collect` 밖으로 나갔다 — 즉 §0.4 가 없앴다고 적은 배치 소실이
**한 축에서는 그대로 살아 있었다.**

| 문서·처방이 약속한 경계 | 코드가 실제로 그은 경계 |
|---|---|
| "더 새로운 문서인가 vs 손상된 문서인가" | "이 코덱의 예외 타입인가 vs 아닌가" |

2차 리뷰의 실측(Redis 컨테이너, 정상 2 + `{"type":"image","mimeType":"video/mp4"}` 1):
알 수 없는 태그는 `collected=3` 으로 **살아났고**(설계대로), 나쁜 MIME 은
`IllegalArgumentException` 이 나가고 `entries-left-in-stream=0` — **3건 전부 소실**.

**틀렸다는 증거가 같은 파일 안에 있었다.** `decodeBase64` 는 JDK 의 `IllegalArgumentException` 을 잡아
코덱 예외로 정규화하고 있었고, 그 테스트 이름이 이유까지 적고 있었다 — *"invalid base64 is refused as a
codec failure, not as an IllegalArgumentException from the JDK"*. 같은 규칙을 MIME 축에 적용하지 않았을
뿐이다.

**그리고 약속했던 경계 자체가 구현 불가능했다.** 디코더는 *더 새로운 문서*와 *손상된 문서*를 구별할 수
없다 — `{"type":"video"}` 는 양쪽으로 똑같이 읽힌다. 그래서 그을 수 있는 경계로 바꿔 적었다:
**입력 필드를 읽다 나온 것은 전부 저하하고, 봉투의 나머지는 그대로 거절한다.** 필드가 경계다.

| 자리 | 무엇을 했나 |
|---|---|
| `UserInputCodec.decodeAt` | 값 객체가 선언한 실패(`IllegalArgumentException`·`NullPointerException`)를 코덱 예외로 정규화 — `decodeBase64` 가 이미 하던 것. `decode()` 의 계약이 참이 되고, 덤으로 되감기 지점 하나가 스냅샷 **전체**를 못 읽게 만들던 선재 경로도 닫힌다 |
| `UserInputCodec.decodeOrText` | `catch` 를 `RuntimeException` 으로 넓힘. 정규화가 있으면 오늘은 도달하지 않는다 — 그 사실을 javadoc 에 적었다 |
| 세 인박스 코덱 | 저하 경고에 **세션 id** 를 함께 넘긴다. 어느 세션의 턴이 텍스트로 떨어졌는지 못 말하면 "관측 가능" 이 "어딘가 줄이 하나 있다" 가 된다 |

| 가드 | 넣어 본 결함 | 결과 |
|------|-------------|------|
| `valueObjectRefusalIsNormalized` · `valueObjectRefusalDegradesAsWell` | 정규화 제거(넓은 catch 유지) | 앞의 것만 FAILED — 넓은 catch 가 뒤를 받아 낸다 |
| 같은 둘 | catch 를 다시 좁힘(정규화 유지) | **둘 다 PASSED** — 넓은 catch 는 오늘 도달 불가 |
| 같은 둘 | 둘 다 제거 | **둘 다 FAILED** |
| `unreadableEncodingDegradesAudibly` | — | 경고에 세션 id 가 들어가는지까지 검사 |

가운데 줄을 그대로 적어 두는 이유는 [`README.md`](README.md) 규칙 다섯의 마지막 문단이다 — 오늘 도달하지
않는 방어는 **왜 거기 있는지 적혀 있을 때만** 결함이 아니라 결정이다.

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
| Redis `InboundMessageCodecTest` — 주입 mapper 가 subtree 까지 도달하는지 | §0.1 의 그 함정, 즉 무인자 오버로드로 수렴 | `encode(options, mapper)` → `encode(options)` | FAILED — 나머지 9건 전부 PASSED |

셋째 줄이 이 쌍을 만든 이유 그 자체다. 공유 코덱의 모양이 한 칸 늘었을 때 Mongo 의 라운드트립은
**초록인 채로 남는다** — 자기가 모르는 필드는 넣지도 빼지도 않으므로 픽스처와 여전히 같기 때문이다.
빨개지는 것은 자기 리터럴이 아니라 남의 상수와 대조하는 그 한 건뿐이다.

**정정** — 처방의 "순수 삭제" 부분이 틀렸다. §0.1 참조.

---

## 2. 크로스 노드 제출은 아직 텍스트다 — **닫힘** *(2026-09-05)*

**무엇이었나** — `SubmitRequest` 가 `UserInput` 을 나르게 하고, 인박스 와이어 포맷에 입력 인코딩을
얹는다.

**왜** — `LiveSession` 은 이제 이미지·문서·멀티모달 턴을 받지만, 라우터를 거쳐 다른 노드로 가는 제출은
`String` 이었다. 즉 **멀티모달은 핸들을 직접 쥔 호스트만의 것**이고, 같은 애플리케이션이 스케일아웃하면
그 기능을 포기해야 했다.

IMPORTANT: **원래 항목은 여기에 "조용히 사라진다" 고 적었고 그것은 틀렸다.** `main` 에는 `UserInput` 을
받아 납작하게 만드는 프레임워크 경로가 없다 — 손실은 런타임 침묵이 아니라 **컴파일 벽**이었다.
근거와 그 오류가 이 항목의 닫는 근거까지 끌고 들어간 경위는 §0.3.

**항목의 마지막 문장은 이 항목이 아니다** — *"라우터는 재시도를 노출하지도 않는다"* 는 여전히 참이고
(`SessionRouter` 에 `retryLastTurn` 대응물이 없다) 여기서 손대지 않았다. 두 문장이 한 항목에 있었던
것은 둘 다 "핸들을 직접 쥔 호스트만의 것" 이라는 같은 관찰에서 나왔기 때문이지, 같은 변경이어서가
아니다. 재시도는 `RewoundTurn` 을 크로스 노드로 나르는 것이 아니라 **어느 노드가 되감을 자격이 있는지**
를 정하는 문제이고(되감기는 홀더의 전사를 고쳐 쓴다), 그 판단은 리스와 얽힌다. 별개 항목으로 등록할
만하지만, 아직 등록하지 않는다 — 이 작업 중에 그 설계를 실제로 해 보지 않았으므로 규칙 다섯이 요구하는
처방을 적을 수 없고, 검증되지 않은 처방을 적어 두는 것이 그 규칙이 막으려는 것이다.

**트리거는 멀쩡했다. 이 항목은 그 앞에서 처리되었다.** 처음 닫으면서 적었던 근거 — *"손실이 조용하므로
트리거가 이 자리를 지나가지 않는다"* — 는 **철회한다**(§0.3). 컴파일 벽 위에서는 그 논증이 성립하지
않는다: 멀티모달을 요구하는 소비자는 `newRequest(id, image)` 가 컴파일되지 않는 순간 알게 되고, 그것은
3번을 열어 둔 기준(*"취소가 안 듣는 것은 곧바로 보인다"*)과 **같은 성질**이다. 즉 같은 기준을 두 항목에
다르게 적용했었다.

그러므로 이 항목이 닫힌 이유는 **트리거가 결함이어서가 아니라 일이 끝났기 때문**이다. 지시받아 착수한
작업이고, 트리거를 기다리지 않았다. 그 사실을 적어 두는 이유는 다음 사람이 "닫힘" 을 "급했다" 로 읽지
않게 하기 위해서다 — 이 자리는 급하지 않았다. 3번은 그대로 열려 있고, 그 트리거도 그대로 유효하다.

**일찍 한 대가가 하나 있고, 그것이 §0.4 를 낳았다.** 이 항목 전까지 알 수 없는 입력 타입 태그를 만날 수
있는 곳은 저장된 스냅샷 하나뿐이었고, 거기서는 `decodeRewindPoint` 가 이미 저하시키고 있었다. 인박스에
같은 인코딩을 실으면서 두 번째 자리가 생겼는데, 그쪽은 **저하가 아니라 거절**이었다 — 그리고 인박스의
거절은 배치 전체의 소실이다. 트리거를 기다렸다면 여섯 번째 `InputType` 이 무엇인지 아는 상태에서 한 번에
정할 수 있었을 문제를, 미리 만들어 놓고 뒤늦게 닫은 셈이다.

**한 것**

| 자리 | 결과 |
|------|------|
| `at.aimon.core.subagent.task.codec.UserInputCodec` | `JsonSessionSnapshotCodec` 의 private 메서드 넷을 **그대로** 꺼냈다. 필드 이름·타입 태그·32단계 상한 동일 — 저장된 스냅샷의 해석은 바뀌지 않는다. 노드 형태(`ObjectNode`)와 텍스트 형태(`String`)를 둘 다 낸다 |
| `SubmitRequest` · `InboundMessage` | `getUserInput()` 이 `UserInput` 을 돌려준다. 빌더는 `userInput(String)` 을 **유지**하므로(=`TextInput.of` 설탕) 생산자 호출부는 한 곳도 안 바뀐다 |
| `DefaultSessionRouter` | 두 이음매(`runTurnLoop` 의 self-message, `deliverToInbox`)가 입력을 그대로 넘긴다. 드레인은 `submitAsync(TurnId, UserInput, …)` 로 붙는다 |
| Redis · Postgres · Mongo 인박스 코덱 | `userInput` 은 **키도 타입도 그대로**(이제 `asText()`), 비텍스트만 `userInputEncoded` 사이드카를 더한다. 디코드는 셋 다 `UserInputCodec.decodeOrText` 하나를 지난다 — 사이드카 우선, 없거나 **읽을 수 없으면** 옆의 문자열로 저하하고 WARN 을 남긴다(§0.4) |
| `AimonSessions.newRequest(SessionId, UserInput)` | 추가. 스타터가 스케일아웃 형태이므로, 이 기능이 닿는 문이 `newRequest(id, "")` + `.userInput(image)` 여서는 안 된다 |
| `AbstractMultiNodeSessionContractTest` | 멀티모달 forward 시나리오 추가 — 이미지가 견뎌야 하는 것은 **각 백엔드의 직렬화**이므로 in-memory 테스트로는 볼 수 없다 |

**와이어는 양방향으로 읽힌다.** 인박스는 **아직 안 된 일**을 담으므로 업그레이드 시점에 스트림·테이블·
컬렉션에 상대 빌드가 쓴 항목이 남아 있고, 그것이 이 모양을 정했다.

| 읽는 쪽 | 결과 |
|---|---|
| 새 빌드가 옛 항목을 | 사이드카 없음 = 텍스트. 원래 그랬던 그대로 |
| 옛 빌드가 새 빌드의 **텍스트** 항목을 | 한 바이트도 안 바뀌었다 |
| 옛 빌드가 새 빌드의 **멀티모달** 항목을 | 옛 키에서 `asText()` 렌더를 읽는다. 이미지를 돌릴 방법은 어차피 없고, 대안은 더 나쁘다 — 키를 객체로 바꾸면 `JsonNode.asText()` 가 `""` 를 주고 **조용히 빈 턴**이 돌며(Mongo 는 `getString` 이라 던진다), 키를 빼면 항목이 디코드 불가가 되어 **아무도 안 돌린다** |
| 새 빌드가 **더 새로운** 빌드의 항목을 | 위의 거울상이고, 이 형식이 새로 만든 방향이다. 여섯 번째 `InputType` 을 만나면 같은 `asText()` 렌더로 저하하고 WARN 을 남긴다 — 거절하면 배치가 통째로 사라지기 때문이다(§0.4) |

근거는 [`frozen-names.md`](../migration/frozen-names.md) 에 적었다.

**항목이 적지 않았던 세 번째 계약** — `IdempotencyEntry.inputHash` 도 노드 간 계약이다. §0.2 참조.
텍스트 턴은 여전히 `sha256(bare text)` 로 해싱한다.

**남긴 가드, 그리고 각각이 실제로 빨개지는 것을 본 뮤테이션** — 넷 다 통과하는 것만으로는 아무것도
증명되지 않으므로(규칙 다섯), 하나씩 겨눈 결함을 넣어 보고 **그 하나만** 빨개지는 것을 확인했다.

| 가드 | 겨눈 것 | 넣어 본 결함 | 결과 |
|------|---------|-------------|------|
| `SessionRouterMultimodalSubmitTest` — 로컬·forward 두 홉 | 라우터가 입력을 평탄화한다 | `.userInput(request.getUserInput().asText())` | 둘 다 FAILED |
| 같은 클래스 — 텍스트 멱등 해시 | 해시 규칙이 버전 경계를 깬다 | 항상 인코딩으로 해싱 | FAILED |
| Redis `InboundMessageCodecTest` — 텍스트가 옛 모양으로 써지는가 | 텍스트에도 사이드카가 붙는다 | 조건 제거 | FAILED — **같은 클래스의 라운드트립은 PASSED** |
| 같은 클래스 — 옛 키가 읽히는가 | `userInput` 이 객체가 된다 | `put` → `set(…, encode(…))` | FAILED — 라운드트립은 여전히 PASSED |
| `UserInputCodecTest` — 저장 포맷 리터럴 | 필드 이름이 드리프트한다 | `FIELD_TEXT` → `"txt"` | FAILED — 라운드트립은 PASSED |
| 백엔드 3종 `unreadableSidecarDegradesToText` | 더 새로운 빌드의 항목이 배치를 통째로 잃게 한다 | 저하를 없애고 다시 거절 | 셋 다 FAILED |
| `UserInputCodecTest` — 저하가 WARN 을 남기는가 | 조용한 저하 = 이 항목이 없애려던 실패 모드 | `degrade` 에서 로그만 제거 | FAILED — 저하 자체는 통과 |

마지막 세 줄이 왜 리터럴로 적었는지 그 자체다. 인코더와 디코더가 상수를 공유하므로 **한 코덱 안의
라운드트립은 호환성 파괴를 구조적으로 볼 수 없다** — 1번이 Mongo 키 집합에서 배운 것과 같은 성질이,
이번에는 이름이 아니라 **타입**에 대해 나타난 것이다.

**검증** — `./gradlew checkAll` 초록. `integrationTest` 는 Docker 로 216건 전부 통과했고, 멀티모달
forward 시나리오가 Redis·Postgres·MongoDB **셋 다에서** 실제로 돌았다.

**정정** — 셋이다. *"인코딩 자체는 이미 있다"* 는 참이지만 재사용 가능하다는 뜻은 아니었고(§0.2),
*"조용히 사라진다"* 는 심각도는 틀렸으며 그것이 닫는 근거까지 끌고 갔고(§0.3), 이 변경이 세운 원칙을
이 변경이 만든 새 경로에 적용하지 않았다(§0.4).

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

---

## 5. 한 항목을 못 읽으면 같은 `collect` 의 정상 항목까지 사라진다 — **열림**

2번을 닫으면서 나온 것이고, **선재 동작**이다([`README.md`](README.md) — *"항목은 착수하지 않아도 모양이
바뀐다"*). 2번이 좁힌 것은 입력 필드 하나이고, 아래는 그 옆에 그대로 남아 있다.

**무엇** — 인박스의 `collect` 가 한 항목의 디코드 실패로 배치 전체를 버리지 않게 한다. 어떤 모양이어야
하는지는 **정하지 않는다** — 아래 참조.

**왜** — 세 백엔드 전부 **디코드하기 전에** 저장소에서 지운다(§0.4 의 표). 그래서 손상된 항목 하나가
그 호출이 이미 지운 **정상 항목들까지** 데려간다 — 저장소에도 없고 아무에게도 전달되지 않는다. 제출자
입장에서는 약속받은 턴이 조용히 사라진 것이고, 재시도할 근거도 남지 않는다. 2차 리뷰가 MongoDB
실컨테이너에서 3건 중 가운데의 `initiator.type` 을 깨뜨려 재현했다 — `docs-left-in-storage=1`,
생존자는 뒤쪽 하나, 앞쪽 정상 항목은 소실.

트리거는 손상된 문서이므로 흔하지 않다. 다만 **2번이 그 확률을 낮췄지 없애지는 않았다** — 입력 필드는
이제 저하하지만 `initiator` · `priority` · `deliveredAt` 은 계속 던지고, 그것이 맞다(그 값들에는 옆에
놓인 대체물이 없다).

**어디** *(2026-09-05 확인)* — `RedisSessionInbox.collectTier` (Lua 가 배치 전체를 `XDEL` 한 뒤 디코드) ·
`PostgresSessionInbox.collect` (`commit()` 뒤 디코드 루프) · `MongoSessionInbox.collect`
(`findOneAndDelete` 를 반복하며 `out` 에 축적). 세 곳 다 `catch` 는 백엔드 예외(`RedisException` /
`SQLException` / `MongoException`)뿐이고 코덱의 `RuntimeException` 은 통과한다.

**처방을 적지 않는 이유** — 후보가 최소 셋이고 어느 것도 검증하지 않았다: (a) 항목 단위로 감싸 못 읽은
것만 버리고 로그를 남긴다, (b) 지우기 전에 디코드한다(Redis 의 Lua 원자성과 Postgres 의 트랜잭션 모양이
바뀐다), (c) 못 읽은 항목을 dead-letter 로 옮긴다(새 저장 표면). 규칙 다섯이 막는 것이 정확히
**검증되지 않은 처방을 적어 두는 것**이므로, 진단만 남긴다.

**언제 다시 볼까** — 두 가지 중 먼저 오는 것. 운영에서 인박스 디코드 실패가 실제로 관측될 때, 또는
인박스 봉투에 필드를 하나 더 더할 때 — 후자는 새 디코드 실패 지점을 만드는 일이므로 이 항목을 함께 연다.
