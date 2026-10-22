# 세션 저장소 백엔드 — PostgreSQL · MongoDB · Redis

**Status**: IMPLEMENTED — `aimon-session-postgres`, `aimon-session-mongodb`, `aimon-session-redis`. 남은 것은 [마지막 절](#남은-것).

세 백엔드 모듈은 같은 SPI 를 구현하며 서로를 대체할 수 있다. 이 문서는 각 백엔드가 그 SPI 를 무엇에 기대어 구현하고 무엇을 다르게
보장하는지를 정의한다. SPI 의 계약은 [`session-store.md`](session-store.md) 가, 무엇을 고르느냐는 이미 운영 중인 인프라와 §8 이 정한다.
DDL 적용 명령과 서버 설정 체크리스트는 각 모듈의 `db/postgres/README.md` · `db/mongodb/README.md` 와
[`../../features/session/web-session-deployment-guide.md`](../../features/session/web-session-deployment-guide.md) 에 있다.

---

## 1. 구현 매트릭스

세 모듈의 main 은 `aimon-core` 의 SPI 만 본다([`session-store.md`](session-store.md) §1.3).

| SPI | PostgreSQL | MongoDB | Redis |
|-----|-----------|---------|-------|
| `SessionLeaseStore` | `PostgresSessionLeaseStore` | `MongoSessionLeaseStore` | `RedisSessionLeaseStore` |
| `SessionRecordStore` | `PostgresSessionRecordStore` | `MongoSessionRecordStore` | `RedisSessionRecordStore` |
| `SessionLogSegmentStore` | `PostgresSessionLogSegmentStore` | `MongoSessionLogSegmentStore` | `RedisSessionLogSegmentStore` |
| `SessionInbox` | `PostgresSessionInbox` | `MongoSessionInbox` | `RedisSessionInbox` |
| `SessionSignalBus` | `PostgresSessionSignalBus` | `MongoSessionSignalBus` | `RedisPubSubSignalBus` |
| `IdempotencyStore` | `PostgresIdempotencyStore` | `MongoIdempotencyStore` | `RedisIdempotencyStore` |
| `BackgroundTaskStore` | `PostgresBackgroundTaskStore` | `MongoBackgroundTaskStore` | `RedisBackgroundTaskStore` |

---

## 2. 공통 규칙

### 2.1 전사는 문자열, side field 는 네이티브

세 백엔드는 전사를 `SessionRecordCodec` 이 만드는 **문자열 하나**로 저장한다([`session-store.md`](session-store.md) §2.5). 세그먼트 payload 도
같다. 문자열인 것은 강제다 — 도구 호출의 입력은 모델이 만든 임의의 맵이라

- BSON 은 키에 `.` 이 있거나 `$` 로 시작하는 것을 거부하고,
- `jsonb` 는 문자열 안의 NUL(`U+0000`)을 거부한다(`text` 는 이스케이프된 형태로 담는다).

side field 는 **부분 원자 쓰기**가 가능해야 하므로 네이티브 타입으로 남는다 — 카운터는 `$inc` / `UPDATE … + 1`, totals · override 는 쌍으로
덮어쓴다. 필드가 없는 것은 기본값으로 디코드한다([`session-store.md`](session-store.md) §2.4).

### 2.2 이름 동결

**모든 테이블 · 컬럼 · 인덱스 · 컬렉션 · 키 모양 · 채널 이름은 얼어 있다.** 자바 타입은 자유롭게 개명해도 되지만 이 이름들은 데이터를 옮기는
마이그레이션 없이는 바꾸지 않는다.

이름을 SQL 상수와 DDL 파일에서 **동시에** 바꾸면 컴파일되고 테스트도 통과한다 — 테스트 지원 클래스가 같은 DDL 을 새 컨테이너에 다시 적용하므로
모든 계층이 자기들끼리는 일치한다. 그리고 프로덕션에서 행을 하나도 찾지 못한다. 그래서 `PostgresSchemaFreezeTest` / `MongoSchemaFreezeTest` 와
Redis 키 모양 동결 테스트가 핀으로 박아 둔다. 거기서 실패하면 그것은 버그가 아니라 데이터 마이그레이션 결정이다. 목록은
[`../../migration/frozen-names.md`](../../migration/frozen-names.md) 가 정본이다.

**`conversation_*` 와 `session_*` 이 섞여 있다.** `conversation_*` 는 개명 이전에 배포되어 이미 디스크에 행이 있는 이름이다. 레코드와 세그먼트
저장소만 `session_*` 인 것은 개명 이후에 추가되어 뒤에 데이터가 없었기 때문이다. 첫 배포 이후로는 똑같이 얼어 있다. 개명은 자바 식별자만
바꿨다([`../../overview/scope-model.md`](../../overview/scope-model.md) §6).

### 2.3 시간은 서버가 정한다

리스의 steal · extend · release 와 멱등 mark-done 의 시간 비교는 전부 DB 서버 시각으로 한다 — Postgres 는 `now()`, Mongo 는 서버 사이드 `$$NOW`
집계 변수다. 두 노드가 리스 만료 시각에 대해 의견이 갈릴 수 없게 하기 위해서다. **애플리케이션 서버들의 시계 편차는 무해하고, DB 의 시계만
중요하다.**

Mongo 에서 애플리케이션 `Clock` 이 쓰이는 자리는 넷뿐이고 전부 안전하다 — `acquiredAt`(정보성), 콜드 스타트의 첫 `insertOne`(Mongo 가
`insertOne` 에서 `$$NOW` 를 거부한다. 비교할 이전 리스가 없고 이후의 모든 펜싱 판단은 서버 사이드다), resume token 기록, 스위퍼의 `cutoff`
인자(비교 자체는 서버가 쓴 `lastTouchedAt` 에 대해 서버에서 한다). 예외는 세그먼트의 `createdAt` 이다 — 봉인한 노드의 시계이고 GC 유예와
비교된다([`session-log.md`](session-log.md) §7.1).

### 2.4 런타임은 DDL 을 실행하지 않는다

스키마(Postgres)와 컬렉션·인덱스(Mongo)는 **운영자가 환경당 한 번** 적용한다. Flyway · Liquibase 를 써도 같은 파일을 먹이면 되고, 모듈은
무엇이 적용했는지 가정하지 않는다. DDL 을 실행하는 유일한 자리는 테스트 지원 클래스다. 그래서 새 SPI 표면이 스키마를 요구하면 그 효과는
배포가 아니라 운영자가 마이그레이션을 돌린 시점에 시작된다([`inbox.md`](inbox.md) §6 이 이것을 근거로 dead-letter 를 보류했다).

---

## 3. PostgreSQL

### 3.1 스키마

`V1__init.sql` · `V2__session_log_segment.sql` · `V3__session_record_owner.sql` 를 이 순서로 적용하고, 쉬는 세션
깨우기의 상한을 노드 사이에서 공유하면 `V4__wake_ledger.sql` 도 적용한다. V3 은 `session_record` 에 `owner jsonb`
를 더한다. 이 컬럼을 읽는 빌드는 V3 을 적용하기 전에는 모든 provision · load 가 `column "owner" does not exist` 로 실패한다 — 호스트가 소유자를
쓰든 안 쓰든. 미래의 인덱스 파일 이름은 `V5__indexes.sql` 로 예약되어 있다(§3.4) — V3 과 V4 가 차례로 그 자리를 가져갔다.

| 테이블 | 역할 | 핵심 |
|--------|------|------|
| `conversation_lock` | 리스의 권위 행 | 세션당 1행. `lease_expires_at < now()` 면 stale |
| `conversation_lock_fence` | 펜싱 토큰 발급 | 락 행이 사라져도 카운터는 유지 — 토큰이 단조 증가한다 |
| `conversation_signal` | 신호 페이로드 | `bigserial id` 가 순서. NOTIFY 는 이 id 만 나른다 |
| `conversation_inbox` | 우선순위 mailbox | `(conversation_id, priority, id)` 인덱스로 드레인. 드레인 경쟁은 `FOR UPDATE SKIP LOCKED` |
| `idempotency_entry` | 멱등 원장 | 24h primary TTL — 자동 만료가 없어 스위퍼가 지운다 |
| `background_task` | 태스크 메타데이터 | 태스크 소유자 `Principal` 을 세 컬럼으로 펼침 |
| `session_record` | 전사 + side field | `transcript` 는 `text`, side field 는 네이티브, `owner` 는 `jsonb` |
| `session_log_segment` | 봉인된 로그 구간 | `(session_id, segment_id)` 기본키, `payload` 는 `text` |
| `wake_ledger` | 깨우기 상한 (V4, 선택) | 세션당 1행. `update` 는 READ COMMITTED 의 `SELECT … FOR UPDATE`. 깨우기를 시도할 때마다 `updated_at` 을 쓰므로, 하루 지난 행을 지우면 연속 횟수만 다시 센다 |

**펜싱 카운터를 락 행과 따로 둔다.** 한 테이블이면 락 행이 만료·삭제될 때 카운터도 사라지고, 다음 획득자가 이전 홀더와 같은 토큰을 받아
펜싱이 무의미해진다.

런타임은 시작 시 **읽기 전용 단언**만 수행해 DDL 미적용을 빨리 실패시킨다. 락과 멱등 불변식은 durable commit 을 전제한다
(`synchronous_commit = on` — 임시로도 끄지 않는다).

### 3.2 NOTIFY 는 초인종이고 페이로드는 테이블에 있다

`NOTIFY` 의 페이로드 한도는 8000 바이트이고 신호의 `EVENT` 프레임은 그것을 쉽게 넘는다.

```
publish:   INSERT INTO conversation_signal (...) RETURNING id
           → SELECT pg_notify('conversation_signal_doorbell', '<id>')
subscribe: LISTEN conversation_signal_doorbell
           → 깨어나면 SELECT * FROM conversation_signal WHERE id > $lastSeen ORDER BY id
```

**`publishAll` 은 한 문장 · 한 트랜잭션이다** — 여러 행 `INSERT` 하나와 `pg_notify` 한 번(목록이 256행을 넘으면 그만큼씩 나눈다). 행은 배열 넷
(`unnest`)으로 보내므로 문장 텍스트가 행 수와 무관하게 하나다 — 행 수마다 텍스트가 다르면 커넥션마다 준비된 문장과 플랜이 수백 개 쌓인다.
초인종이 한 번이면 되는 것은 구독 쪽이 NOTIFY 가 나르는 id 를 읽지 않고 `id > $lastSeen` 전체를 가져오기 때문이다. 서버가 한 행을 거부하면
문장 전체가 롤백되고 그 배치는 한 건씩 다시 나간다 — 문제의 한 건만 실패하고 두 번 발행되는 것은 없다.

여기서 네 가지가 따라온다.

- **채널은 하나다.** LISTEN 은 커넥션 스코프이므로 노드가 구독한 세션이 몇 개든 커넥션은 하나면 된다. 어느 세션의 신호인지는 in-memory 로
  거른다. 규모에서 결정적인 예산 속성이다
- **재연결이 손실을 만들지 않는다.** 재연결 → `LISTEN` 재발행 → `id > lastSeen` 백로그. 행은 NOTIFY 전달과 독립적으로 테이블에 있다
- **늦게 커밋된 행을 다시 찾는다.** id 는 INSERT 때 발급되고 행은 커밋 때 보이므로, 발행자가 둘이면 더 큰 id 가 먼저 커밋되어 fetch 된 뒤에 작은
  id 의 행이 나타날 수 있다. high-water mark 만으로는 그 행을 영영 보지 못한다. 그래서 `ListenDispatcher` 는 fetch 가 건너뛴 id 를
  `SignalGapTracker` 에 유예 시간(60초) 동안 적어 두고, 매 fetch 에서 새 행과 같은 문장으로(`id > $lastSeen OR id = ANY(...)`) 함께 묻는다.
  문장이 하나라 스냅샷도 하나이므로 되찾은 행과 그 발행자가 뒤이어 보낸 행이 같이 보이고 id 순으로 배달된다. 유예가 끝나면 롤백된 것으로
  보고 잊는다. 되찾은 행은 앞선 fetch 가 이미 배달한 더 큰 id 의 행보다 뒤에 배달된다 — 순서는 한 발행자의 순차 발행 안에서만 지켜진다(세션의
  이벤트 스트림이 그렇다). 유예보다 오래 열려 있던 트랜잭션의 행은 잃는다. 상한은 둘이다 — 4,096개를 넘는 구멍은 가장 큰 4,096개 id 만
  추적하고, 동시에 최대 16,384개까지만 든다. 자리가 모자라면 큰 id 부터 남기고 나머지는 WARN 과 함께 포기한다
- **NOTIFY 유실에 대한 백스톱이 있다.** 백엔드 크래시로 NOTIFY 가 통째로 사라져도 주기적 self-poll(기본 5초)이 같은 질의를 다시 돌린다

채널 이름은 얼어 있다. 롤링 배포에서 구버전이 옛 채널을 듣고 신버전이 새 채널로 쏘면 두 집단이 서로의 신호를 못 본다 — 다운타임 없이 바꿀
수 없는 종류의 이름이다.

### 3.3 커넥션 예산

커넥션 사용자는 셋이고, 성질이 달라 풀을 나눈다.

| 사용자 | 수명 | 배치 | 크기 |
|--------|------|------|------|
| 락 · 인박스 · 멱등 · 레코드 · 세그먼트 연산 | 짧은 트랜잭션(< 50 ms) | Hikari 메인 풀 | `min=4, max=20` |
| 리스 갱신 + 스위퍼 | 짧은 트랜잭션, 스케줄 | 메인 풀 공유 | — |
| `ListenDispatcher` 의 LISTEN 커넥션 | 장수명, 노드당 1개 | **Hikari 밖** — 직접 `DriverManager` | 정확히 1 |

LISTEN 커넥션이 풀 밖인 것은 LISTEN 상태가 커넥션에 묶여 있어 풀에 반납될 수 없기 때문이다. 행 fetch 용으로 작은 두 번째 풀(`min=1, max=2`)을
두면 턴 쪽 INSERT 가 커넥션을 기다리는 동안 신호 fetch 가 멈추지 않는다. 노드 하나가 200 동시 턴 + 50 구독 세션을 서빙할 때 ≤ 23 커넥션이고,
4노드 클러스터면 프라이머리에 `max_connections ≥ 100` 이 필요하다.

### 3.4 나중에 추가하는 인덱스

`V1` 은 정확성에 필요한 최소 인덱스만 담는다. 아래 중 하나가 걸리기 전에는 추가하지 않는다.

| 트리거 | 임계 |
|--------|------|
| 인박스 드레인 퇴화 | `collect` 의 `EXPLAIN` 이 인덱스 스캔에서 시퀀셜 스캔으로 뒤집힘, 또는 드레인 p95 > 25 ms (10분 지속) |
| 멱등 테이블이 DONE 행으로 포화 | DONE 행이 전체의 50% 초과 (1시간 지속) |
| 멱등 스위퍼 지연 | `findStaleInFlight` p95 > 100 ms (30분 지속) |
| 인박스 테이블 크기 | `conversation_inbox` 가 1000만 행 초과 |

추가할 내용은 부분 인덱스 둘이다 — `priority <= 1` 인 인박스 행, `status = 'IN_FLIGHT'` 인 멱등 행. `CREATE INDEX CONCURRENTLY` 는 트랜잭션
안에서 돌 수 없으므로 이것도 운영자가 적용한다.

---

## 4. MongoDB

### 4.1 컬렉션

`init.js` 를 **클러스터당 한 번** 적용한다. 멱등하다. 런타임은 이 스크립트를 실행하지 않고, 저장소 생성자는 인덱스 존재를 확인조차 하지
않는다. 잊었을 때의 피드백은 성능 저하와, 신호 버스의 경우 `watch()` 에서의 예외뿐이다.

| 컬렉션 | 특이사항 |
|--------|---------|
| `conversation_locks` | 세션당 1문서 |
| `conversation_signals` | **capped, 64 MiB** — 자라지 않는다 |
| `conversation_inbox` | `by_conv_priority_fifo` = `(conversationId, priority, deliveredAt)` |
| `idempotency_entries` | `ttl_expires_at`(`expireAfterSeconds: 0`) + `by_status_touch` |
| `background_task` | `by_state`, `by_context` |
| `session_records` | 전사(문자열) + side field. `owner` 는 하위 문서 |
| `session_log_segments` | `_id` = `{sessionId, segmentId}`, 최상위 `sessionId` 와 `by_session` 인덱스 |
| `wake_ledger` | 깨우기 상한(선택). `version` CAS, `ttl_expires_at`(`expireAfterSeconds: 0`) — 마지막 쓰기 1일 뒤 만료 |
| `scheduled_tasks` · `scheduled_task_interrupts` | 스케줄 태스크 레코드와 노드 간 중지 요청(capped 1 MiB). 세션 컬렉션이 아니다 — 키가 `ScheduledTaskId` |

### 4.2 복제 세트와 일관성

**복제 세트는 필수다.** Change Streams 는 MongoDB 에서 유일하게 실용적인 push 메커니즘이고, 단일 노드 배포에서도 복제 세트를 요구한다.
`init.js` 는 `replSetGetStatus` 가 실패하면 경고를 찍지만 스스로 `rs.initiate()` 하지 않는다 — 네트워크 토폴로지와 영속 정책에 묶인 운영
결정이다. 복제 세트를 돌릴 수 없는 배포라면 이 백엔드가 답이 아니다. Redis 나 Postgres 는 단일 프라이머리로 동작한다.

capped collection 의 tailable cursor 로 standalone 을 지원하는 것은 기계적으로 가능하지만 구현하지 않는다. resume token 이 없고, capped 의미론에
강하게 결합되며, Mongo 자신의 문서가 신규 코드에는 Change Streams 를 권한다.

| 항목 | 요구 | 이유 |
|------|------|------|
| 멤버 수 | 프로덕션 3(PSS). 개발은 단일 노드 복제 세트 | |
| Oplog 크기 | 피크 쓰기량 기준 24시간 이상 | resume token 으로 복구 가능한 구간을 정한다 |
| Write concern | 전부 `majority` — 락 · 인박스 · 멱등 · 레코드 쓰기 | 프라이머리에 썼는데 복제 전에 죽는 lost-acquire 를 막는다 |
| Read concern | `majority` | 펜싱 판단이 durable 해야 한다 |
| Read preference | `primary` | 신호 버스와 락이 강한 일관성을 요구한다 |
| Change stream | 노브 없음 — majority-committed oplog 를 읽는다 | |

**읽기 복제본으로 보내지 않는다** — 미구현이 아니라 안전하지 않다. 복제 지연이 진행 중인 멱등 항목을 가려 dedup 에 거짓 음성을 만든다.
bounded-staleness 읽기 없이는 열 수 없다. 세 백엔드 모두 같다.

멀티 도큐먼트 트랜잭션은 쓰지 않는다.

### 4.3 배선

`MongoClient` 는 thread-safe 하고 수명이 싱글턴이다. `MongoSessionBackends` 가 한 `MongoDatabase` 위에 저장소들을 조립해 접근자로 노출한다 —
조립하는 쪽이 객체 하나만 들고 다니면 된다. 컬렉션 이름 · 멱등 TTL · `Clock` 은 빌더로 덮어쓸 수 있다(덮어쓰면 `init.js` 도 맞춰야 한다).

종료는 순서가 있다 — 신호 버스를 먼저 닫아 change stream watcher 를 멈추고, 그다음 클라이언트를 닫아 풀을 놓는다. 드라이버 기본
`maxPoolSize=100` 에서 change stream 의 장수명 커서가 한 슬롯을 수명 내내 점유하므로 `동시_턴_수 + 5` 가 안전한 어림이다.

---

## 5. Redis

가장 단순하고 가장 빠르며, 전달 보장이 가장 약하다.

- **레코드** — `{keyPrefix}:{sessionId}` HASH 하나. `sessionId` 필드가 레코드의 존재 자체를 뜻하고 나머지는 없으면 기본값이다. 소유자는
  `owner` 필드(코덱 JSON 문자열)다
- **빈 문자열 sentinel** — provision 스크립트는 `agentRef` 와 소유자 인자를 위치로 **언제나** 넘기고 빈 문자열을 "없음" 표지로 쓴다. 선택 인자가
  둘이면 위치로 부재를 나타낼 수 없기 때문이다. 그래서 이 저장소는 빈 문자열 `agentRef` 를 거부한다(`null` 이 "바인딩 없음" 이다). 라우터의
  `SubmitRequest` 가 이미 그런 이름을 거부하므로 이것은 라우터 없이 저장소를 쓰는 호출자를 위한 두 번째 방어다. 스크립트는 `""` 를 해시에 쓰지
  않고, 해시에 `owner = ""` 가 있더라도 소유자 없음으로 디코드한다
- **세그먼트** — 세션당 해시 둘, `<prefix>:{s:<sid>}:data`(id → payload 를 든 JSON)와 `<prefix>:{s:<sid>}:created`(id → createdAt). `list` 가
  payload 를 끌어오지 않고, Lua 스크립트가 둘을 맞춘다. 중괄호는 hash tag 라서 두 키가 Redis Cluster 의 한 슬롯에 들고, 두 키 스크립트와 `DEL`
  이 `CROSSSLOT` 을 내지 않는다. tag 는 고정 표식 `s:` 로 시작한다 — Redis 는 첫 `{` 와 그 뒤 첫 `}` 사이가 비면 키 전체를 해시하므로 `{<sid>}`
  만으로는 `}` 로 시작하는 id 의 두 키가 다른 슬롯에 떨어진다. 접미사를 세션 id **뒤**에 고정하므로 한 세션의 키가 다른 세션의 키가 될 수
  없다 — 접두 모양(`<prefix>:<sid>` / `<prefix>:<sid>:created`)이면 세션 `X:created` 의 data 키가 세션 `X` 의 created 키와 같아진다. 기본
  prefix 는 `aimon:session:segment` 이고, `{` 가 든 prefix 는 모든 세션을 한 슬롯에 몰아넣으므로 거절한다
- **세그먼트 스캔** — `scanSessions` 는 `SCAN` 으로 `:created` 키를 훑는다. 세션별 키와 같은 슬롯에 둘 수 없는 색인 키를 따로 두지 않으려는
  선택이다. 그래서 나이로 거르지 않고, 중복을 허용하며, `COUNT` 는 힌트다 — `scanSessions` 의 느슨한 계약이 이것을 허용한다
  ([`session-log.md`](session-log.md) §6). standalone 연결에서는 그 노드의 키 공간이 전체다. **Redis Cluster** 에서는
  `StatefulRedisClusterConnection` 을 받는 생성자를 쓴다 — 세션 단위 명령은 hash tag 가 가리키는 슬롯으로 가고, `scanSessions` 는 마스터를
  노드 id 순으로 하나씩 `SCAN` 한다. 커서는 `<nodeId>:<nodeCursor>` 다 — Lettuce 의 클러스터 스캔은 위치를 커서 **객체**에 두므로 두 호출 사이에
  문자열로 넘길 수 없다. 스캔 중에 노드가 빠지면 다음 마스터로 넘어가며, 그 회차는 옮겨 간 슬롯의 키를 놓칠 수 있다(`SCAN` 자체의 주의사항이고
  다음 회차가 본다). 레코드 저장소에는 Cluster 생성자가 없다
- **신호** — `RedisPubSubSignalBus`. pub/sub 은 **연결이 끊긴 동안 at-most-once** 다. 끊긴 사이의 신호는 복구되지 않는다. 이벤트가 best-effort 라는
  계약이 여기서 나온다
- **멱등 TTL** — Redis 가 자체 만료를 하므로 스위퍼가 primary TTL 을 대신 돌 필요가 없다
- **복제 토폴로지 요구 없음** — 단일 프라이머리로 충분하다

---

## 6. 소유자와 세그먼트의 저장 모양

소유자는 `SessionRecordCodec.encodeOwner` 의 JSON(`{"version":1,"type":…,"id":…,"displayName":…}`)이다. 쓰는 곳은 `provision` 뿐이고, 세 백엔드
모두 바인딩과 **같은 원자 연산 안에서** 비어 있을 때만 채운다. 머지 · totals · 카운터 쓰기는 소유자를 건드리지 않는다.

| 백엔드 | 소유자 | 쓰기 규칙 | 세그먼트 |
|---|---|---|---|
| In-memory | `SessionRecord.owner` | `compute` 안에서 null 이면 설정 | 세션별 맵, `put` 은 바깥 맵의 `compute` 안 |
| Postgres | `session_record.owner jsonb` (V3) | upsert 의 `COALESCE(session_record.owner, EXCLUDED.owner)` | `session_log_segment` 행 |
| Mongo | `owner` 하위 문서 | 파이프라인의 `$ifNull` + `$literal` — id 나 표시 이름이 `$` 로 시작해도 연산자로 읽히지 않게 | `session_log_segments`, `_id` = `{sessionId, segmentId}` — 세그먼트 id 는 세션 범위라서 id 하나만 `_id` 로 두면 다른 세션의 같은 id 를 E11000 으로 거절한다 |
| Redis | 해시 필드 `owner` | provision 스크립트의 `HSETNX` | 해시 둘(§5) |
| 인박스 봉투 | 선택 필드 `owner` | `initiator` 와 같은 `type/id/displayName` 모양, 없으면 쓰지 않는다 | — |

소유자만 읽는 `loadOwner` 는 백엔드마다 소유자 하나만 읽는다 — Postgres 단일 컬럼, Mongo projection, Redis `HGET`. 전사가 깨진 레코드에서도
답하고, 소유자 자체가 깨졌으면 예외다([`session-store.md`](session-store.md) §2.3).

---

## 7. 무엇이 어떻게 깨지는가

### 7.1 세 백엔드 공통

| 실패 | 탐지 | 동작 |
|------|------|------|
| 저장소 도달 불가 | SPI 메서드에서 예외 | 세션 예외로 전파, 제출은 503. 리스 갱신기는 tick 마다 WARN |
| 긴 턴이 리스를 초과 | `extend()` 가 false (펜싱 토큰 불일치) | 갱신기가 `LEASE_LOST` 인터럽트, 부분 상태는 커밋되지 않는다 |
| 두 노드가 동시에 홀더 손실을 탐지 | 둘 다 `compareAndReset` | 저장소가 직렬화 — 하나만 이기고 복구를 돌린다 |
| 스키마/스크립트 미적용 | Postgres: 시작 단언 실패 / Mongo: `watch()` 실패 또는 느린 스캔 | 운영자가 적용. 런타임은 자동 생성하지 않는다 |

### 7.2 PostgreSQL

| 실패 | 동작 |
|------|------|
| LISTEN 커넥션 끊김 | 재연결 → `LISTEN` 재발행 → `id > lastSeen` 백로그. 신호 손실 없음 |
| 백엔드 크래시로 NOTIFY 유실 | 5초 self-poll 이 잡는다. 늦은 배달(≤ 5s) |
| 스위퍼끼리의 경쟁 | `DELETE … WHERE holder_id = ?` 를 Postgres 가 직렬화 — 하나가 1행, 다른 하나가 0행 |
| 인박스 드레인 경쟁 | `FOR UPDATE SKIP LOCKED` 로 서로 다른 행을 진행. 홀더만 collect 한다는 불변식의 이중 방어 |
| `idempotency_entry` 무한 증가 | 스위퍼가 조용히 실패한 것. ERROR 로그. 제출 경로는 막지 않는다 |

### 7.3 MongoDB

| 실패 | 동작 |
|------|------|
| resume token 만료(oplog 가 지나감) | WARN 후 토큰 없이 스트림 재개(live tail). 그 사이 이벤트는 유실 — 이벤트가 best-effort 인 계약과 맞다. 운영 조치는 oplog 확대 |
| 프라이머리 step-down | 드라이버가 새 프라이머리로 자동 재시도. 모든 SPI 가 재시도에 멱등하다 — `tryAcquire` 는 재발행되어 성공(만료 리스 탈취)하거나 실패, `extend` 는 같은 펜싱 삼중항이라 리스가 유효할 때만 성공, `markDone` 은 멱등 |
| TTL 모니터 지연(최대 60초) | `expiresAt` 이 지난 항목이 최대 60초 더 읽힌다. 무해하다 — stale-`IN_FLIGHT` 탐지는 TTL 이 아니라 `lastTouchedAt` 스캔으로 하고, 살아남은 DONE 항목의 캐시된 결과는 여전히 유효하다 |
| 문서 크기 한도 16 MiB | `markDone` 이 실패하고 결과가 재생 불가로 취급된다. `StoredAgentExecutionResult` 투영은 통상 1 MiB 훨씬 아래다. 거대한 산출물을 내는 도구는 GridFS/S3 에 쓰고 참조만 담는다. 레코드가 이 한도에 닿지 않는 것은 봉인 덕분이다([`session-log.md`](session-log.md) §5.3) |
| 커넥션 풀 고갈 | change stream 의 장수명 커서 + 버스트 턴 부하. 제출이 빨리 실패한다. `maxPoolSize` 를 올린다 |

---

## 8. 어느 것을 고를 것인가

먼저 **이미 운영 중인 것**을 쓴다. 그것으로 갈리지 않을 때의 기준이다.

| 상황 | 답 |
|------|-----|
| 복제 세트를 돌릴 수 없다 | **Mongo 아님.** Redis 또는 Postgres |
| 신호 팬아웃의 지연이 임계적이다 | **Redis.** pub/sub p50 ≈ 1 ms. Mongo Change Streams 는 p50 ≈ 10–50 ms |
| 끊김을 넘는 이벤트 재개가 필요하다 | **Mongo.** resume token 이 oplog 구간 안에서 at-least-once 를 준다. Redis pub/sub 은 끊기면 at-most-once |
| 신호 손실 없는 재연결 + 관계형 운영 도구 | **Postgres.** 신호가 행으로 남으므로 재연결이 백로그를 되읽는다 |
| 인박스·멱등 상태를 애드혹으로 들여다봐야 한다 | Postgres(SQL) 또는 Mongo(`mongosh`). Redis Streams 는 다루기 번거롭다 |

---

## 9. 메트릭

지금 구현된 세션 메트릭은 라우터의 `SessionMetrics` 훅이다([`routing.md`](routing.md) §14). 백엔드 모듈은 그 타입을 볼 수 없다 — 그것이 라우팅에
있고 백엔드 main 은 core 만 본다. DB 자신의 서버 사이드 메트릭(oplog 창, 복제 지연, `pg_stat_*`)은 이 설계의 범위 밖이고 표준 도구로 수집한다.

**메트릭을 더할 때의 기준.** 다른 백엔드에 의미 있는 대응물이 있으면 라우터가 소유하고 `backend` 로 구분한다 — 여러 백엔드를 함께 돌리는 운영자가
대시보드를 분리하거나 태그를 가로질러 집계할 수 있다. 그 백엔드에만 있는 기능(Change Streams, LISTEN, TTL 모니터, 드라이버 풀)을 서술하면 그 백엔드
모듈이 소유한다. 이 규칙은 계획이고, 백엔드 전용 메트릭은 아직 없다.

---

## 10. 용량

| | 증가 요인 | 규모 |
|---|---|---|
| 인박스 | 턴마다 드레인 | 정상 상태에서 작다(진행 중 세션 × 대기 메시지). 세션당 백로그 알람을 예: 1000 에 건다 |
| 멱등 | 24h primary TTL × 피크 제출률 | 100 RPS ≈ 860만 항목 × ~500 B ≈ 4 GiB |
| 신호 | — | Mongo 는 64 MiB capped. Postgres 는 기본 5분 보존 |
| 레코드 | 세션 수 × 창 크기 근처(봉인) | 명시적 삭제까지 자란다 |
| 세그먼트 | 세션 수 × 봉인된 로그 | 레코드와 같은 수명. 고아는 GC · 스윕이 지운다 |

---

## 11. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 전사 저장 형식 | 코덱 문자열 | **네이티브 문서·jsonb** — BSON 키 제약과 jsonb 의 NUL 거부 |
| 펜싱 카운터 | 락 행과 별도 테이블 | **한 테이블** — 락 행이 사라질 때 카운터도 사라져 토큰이 재사용된다 |
| Postgres 신호 | NOTIFY 는 id, 페이로드는 테이블, 채널 하나 | **NOTIFY 페이로드** — 8000 바이트 한도. **세션별 채널** — 구독마다 커넥션 |
| Mongo push | Change Streams, 복제 세트 필수 | **tailable cursor 로 standalone** — resume token 이 없고 capped 의미론에 결합된다 |
| 시간 | DB 서버 시각 | **애플리케이션 `Clock`** — 노드 간 시계 편차가 리스 판단을 가른다 |
| DDL | 운영자 적용, 런타임은 단언만 | **런타임 자동 생성** — 운영 정책과 권한에 묶인 결정을 라이브러리가 대신한다 |
| 이름 | 동결, freeze 테스트 | **자유 개명** — 컴파일·테스트는 통과하고 프로덕션에서 행을 못 찾는다 |
| 읽기 복제본 | 쓰지 않는다 | **복제본 읽기** — 진행 중인 멱등 항목을 가려 dedup 거짓 음성 |
| Redis 세그먼트 키 | `{s:<sid>}` hash tag, 세션 id 뒤 고정 접미사 | **`{<sid>}`** — `}` 로 시작하는 id 가 슬롯을 가른다. **접두 모양** — 다른 세션의 키와 충돌한다 |
| Redis 스캔 | `SCAN` + 느슨한 계약 | **색인 키** — 세션별 키와 같은 슬롯에 둘 수 없다 |

---

## 하지 말 것

- **테이블 · 컬럼 · 컬렉션 · 키 모양 · 채널 이름을 바꾸지 않는다.** 데이터 마이그레이션 결정 없이는
- **런타임에 DDL 을 실행하지 않는다**
- **`synchronous_commit` 을 끄지 않는다.** 락과 멱등 불변식이 durable commit 을 전제한다
- **Mongo 를 복제 세트 없이 쓰지 않는다**
- **읽기를 복제본이나 secondary 로 보내지 않는다**
- **Postgres LISTEN 커넥션을 풀에 넣지 않는다**
- **멀티 노드 하네스에서 두 노드가 in-memory 저장소를 공유하게 하지 않는다.** 두 애플리케이션 프로세스를 흉내 내는 것이므로 한 컨테이너에 대해 두
  개의 독립 풀/클라이언트를 만든다([`session-store.md`](session-store.md) §4.1)
- **Redis 세그먼트 prefix 에 `{` 를 넣지 않는다**

---

## 남은 것

| 항목 | 지금 |
|------|------|
| 쓰기 펜스의 남는 창 | 펜싱 compare-and-set 레코드 백엔드가 필요하다([`session-store.md`](session-store.md) §5.3) |
| `conversation_signal` 파티셔닝 | 기본 5분 보존은 중간 볼륨까지 충분하다. 고볼륨이면 시간 파티셔닝으로 `DETACH` 가 `DELETE` 를 대체한다 |
| 핫 세션에 `pg_advisory_xact_lock` | 한 세션이 초당 100 획득을 넘으면 UPSERT 를 직렬화. 기능 플래그 뒤 |
| 16 MiB 초과 결과의 GridFS 탈출구 | 지금은 상위에서 크기를 제한한다. 실제로 한도를 치면 재검토 |
| 백엔드 전용 메트릭 | 없다. §9 의 기준만 있다 |
| Redis 레코드 저장소의 Cluster 지원 | 세그먼트 저장소만 Cluster 생성자를 갖는다 |
| 의도적으로 검증하지 않는 것 | 백엔드 간 마이그레이션(운영자 주도 — 드레인 후 전환), 샤드 클러스터, Postgres 프라이머리 페일오버 |

---

## 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| Postgres 스키마 | [`db/postgres/`](../../../modules/aimon-session-postgres/src/main/resources/db/postgres/README.md) |
| Postgres 신호 | [`PostgresSessionSignalBus.java`](../../../modules/aimon-session-postgres/src/main/java/at/aimon/session/postgres/PostgresSessionSignalBus.java) |
| Mongo 컬렉션 | [`db/mongodb/`](../../../modules/aimon-session-mongodb/src/main/resources/db/mongodb/README.md), [`MongoSessionBackends.java`](../../../modules/aimon-session-mongodb/src/main/java/at/aimon/session/mongodb/MongoSessionBackends.java) |
| Redis | [`RedisSessionRecordStore.java`](../../../modules/aimon-session-redis/src/main/java/at/aimon/session/redis/RedisSessionRecordStore.java), [`RedisSessionLogSegmentStore.java`](../../../modules/aimon-session-redis/src/main/java/at/aimon/session/redis/RedisSessionLogSegmentStore.java) |
| 레코드 저장소 | [`PostgresSessionRecordStore.java`](../../../modules/aimon-session-postgres/src/main/java/at/aimon/session/postgres/PostgresSessionRecordStore.java), [`MongoSessionRecordStore.java`](../../../modules/aimon-session-mongodb/src/main/java/at/aimon/session/mongodb/MongoSessionRecordStore.java) |

---

## 관련 문서

- [`session-store.md`](session-store.md) — 이 모듈들이 구현하는 SPI 의 계약
- [`session-log.md`](session-log.md) — 세그먼트 저장소의 의미
- [`routing.md`](routing.md) — 이 백엔드들 위의 라우터
- [`inbox.md`](inbox.md) · [`idempotency.md`](idempotency.md) — 인박스와 멱등 원장의 의미
- [`../../migration/frozen-names.md`](../../migration/frozen-names.md) — 동결 이름 목록
- [`../../features/session/web-session-deployment-guide.md`](../../features/session/web-session-deployment-guide.md) — 운영자용 배포 가이드
