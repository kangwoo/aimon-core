# 세션 저장소 백엔드 — PostgreSQL · MongoDB · Redis

**Status**: IMPLEMENTED

세션의 분산 상태를 담는 세 백엔드 모듈의 스키마, 구현 전략, 운영 요구사항을 정의한다.
세 모듈은 **같은 여섯 SPI 를 구현**하며 서로를 대체할 수 있다 — 무엇을 고르느냐는 이미 운영 중인
인프라와 아래 §7 의 제약이 정한다.

- SPI 의 계약: [`spi-extraction.md`](spi-extraction.md)
- 이들을 조립해 쓰는 라우팅 계층: [`routing.md`](routing.md)
- 무엇이 왜 영속되는가: [`session-model.md`](session-model.md)

---

## 1. 여섯 개의 SPI

셋 다 `aimon-core` 의 SPI 만 보고 구현한다 — 라우팅 모듈에 대한 의존은 없다.

| SPI | 패키지 | 하는 일 |
|-----|--------|---------|
| `SessionLeaseStore` | `agent.session.store` | 어느 노드가 어느 세션을 쥐고 있는지 — 홀더 선출 + 펜싱 토큰 |
| `SessionRecordStore` | `agent.session.store` | 전사 + side field 넷 |
| `SessionInbox` | `agent.session.inbox` | 세션의 크로스 노드 우편함 (우선순위 → FIFO) |
| `SessionSignalBus` | `agent.session.signal` | `SessionSignal` 팬아웃 (INTERRUPT · EVICT · MESSAGE_ENQUEUED · EVENT · STATUS) |
| `IdempotencyStore` | `agent.session.idempotency` | 클라이언트 키 기반 at-most-once 제출 |
| `BackgroundTaskStore` | `subagent.task` | 백그라운드 서브에이전트 태스크 메타데이터 |

| SPI | PostgreSQL | MongoDB | Redis |
|-----|-----------|---------|-------|
| `SessionLeaseStore` | `PostgresSessionLeaseStore` | `MongoSessionLeaseStore` | `RedisSessionLeaseStore` |
| `SessionRecordStore` | `PostgresSessionRecordStore` | `MongoSessionRecordStore` | `RedisSessionRecordStore` |
| `SessionInbox` | `PostgresSessionInbox` | `MongoSessionInbox` | `RedisSessionInbox` |
| `SessionSignalBus` | `PostgresSessionSignalBus` | `MongoSessionSignalBus` | `RedisPubSubSignalBus` |
| `IdempotencyStore` | `PostgresIdempotencyStore` | `MongoIdempotencyStore` | `RedisIdempotencyStore` |
| `BackgroundTaskStore` | `PostgresBackgroundTaskStore` | `MongoBackgroundTaskStore` | `RedisBackgroundTaskStore` |

레코드 저장소가 마지막에 들어왔다. 그 전까지 세션의 **나머지 전부**(리스·신호·인박스·멱등 원장)는
이미 분산이었는데 전사만 in-memory 였다 — 즉 함대가 세션을 노드 간에 넘길 수 있었지만, 넘기면서
**대화 내용은 잃고 그에 대한 장부만 지키는** 상태였다. 이 저장소가 그 구멍을 닫는다.

### 1.1 인코딩은 `SessionRecordCodec` 이 소유한다

세 백엔드는 전사를 **문자열 하나**로 저장한다. 각자 JSON 을 만들지 않고 코덱을 공유하며, 중립 문서는
`StoredSessionRecord`, 결과 투영은 `StoredAgentExecutionResult` 다.

문자열인 것은 **선택이 아니라 강제**다. 도구 호출의 입력은 모델이 만든 임의의 맵이라

- BSON 은 키에 `.` 이 있거나 `$` 로 시작하는 것을 거부하고,
- `jsonb` 는 문자열 안의 NUL(`U+0000`)을 거부한다 (`text` 는 이스케이프된 형태로 담는다).

반면 side field 넷은 **부분 원자 쓰기**가 가능해야 하므로 네이티브 타입으로 남는다 — 카운터는
`$inc`/`UPDATE … + 1`, totals·override 는 쌍으로 덮어쓴다.

### 1.2 "없음" 은 별도 상태가 아니다

셋 다 같은 규칙을 지킨다.

> 문서/행/해시에 **필드가 없는 것**은, in-memory 저장소가 그 필드를 가진 레코드에 대해 보고하는
> **기본값과 똑같이** 디코드된다 — 전사 없음, totals 없음, override 없음, 카운트 0.

그래서 claim 경로가 프로비저닝만 해 두고 아무도 쓰지 않은 레코드 위에서 도는 **세션의 첫 턴**이 네
백엔드(셋 + in-memory)에서 동일하게 동작한다. "아직 안 씀" 을 별도 상태로 두면 그 첫 턴에서만 갈린다.

---

## 2. 이름 동결

**모든 테이블·컬럼·인덱스·컬렉션 이름은 얼어 있다.** 자바 타입은 자유롭게 개명해도 되지만 이 이름들은
안 된다 — 데이터를 옮기는 마이그레이션 없이는.

이 규칙이 필요한 이유가 구체적이다. 이름을 SQL 상수와 DDL 파일에서 **동시에** 바꾸면 컴파일이 되고,
테스트도 통과한다(테스트 지원 클래스가 같은 DDL 파일을 새 컨테이너에 다시 적용하므로 모든 계층이
자기들끼리는 일치한다). 그리고 프로덕션에서 **행을 하나도 찾지 못한다.**

`PostgresSchemaFreezeTest` / `MongoSchemaFreezeTest` 가 핀으로 박아 둔다. 거기서 실패하면 그것은
버그가 아니라 **데이터 마이그레이션 결정**이다.

### 2.1 `conversation_*` 와 `session_*` 이 섞여 있는 이유

| 이름 | 백엔드 |
|------|--------|
| `conversation_lock` / `conversation_lock_fence` / `conversation_signal` / `conversation_inbox` / `idempotency_entry` / `background_task` | Postgres |
| `conversation_locks` / `conversation_signals` / `conversation_inbox` / `idempotency_entries` / `background_task` | Mongo |
| **`session_record`** (Postgres) / **`session_records`** (Mongo) | 레코드 저장소만 |

`conversation_*` 는 개명 이전에 배포되어 **이미 디스크에 행이 있는** 이름이다. 레코드 저장소만
`session_*` 인 것은 그것이 개명 이후에 추가되어 **뒤에 데이터가 없었기** 때문이다 — 그래서 코드가 쓰는
철자를 그대로 받았다. 다만 첫 배포 이후로는 똑같이 얼어붙는다.

어긋나 보이는 것이 정상이다. 개명은 **자바 식별자만** 바꿨다
([`../../overview/scope-model.md`](../../overview/scope-model.md) §6).

---

## 3. PostgreSQL

### 3.1 스키마

`V1__init.sql` 하나. 운영자가 **환경당 한 번** 적용하고, **런타임은 DDL 을 실행하지 않는다.**

| 테이블 | 역할 | 핵심 |
|--------|------|------|
| `conversation_lock` | 분산 락의 권위 행 | 세션당 1행. `lease_expires_at < now()` 면 stale |
| `conversation_lock_fence` | 펜싱 토큰 발급 | 락 행이 사라져도 카운터는 유지 — 토큰이 monotonic |
| `conversation_signal` | 신호 페이로드 | `bigserial id` 가 순서. NOTIFY 는 이 id 만 나른다 |
| `conversation_inbox` | 우선순위 mailbox | `(conversation_id, priority, id)` 인덱스로 드레인 |
| `idempotency_entry` | 멱등 원장 | 24h primary TTL — Postgres 는 자동 만료가 없어 sweeper 가 지운다 |
| `background_task` | 태스크 메타데이터 | owner `Principal` 을 세 컬럼으로 펼침 |
| `session_record` | 전사 + side field | `transcript` 는 `text`(§1.1), side field 는 네이티브 |

펜싱 카운터를 락 행과 **따로** 두는 것이 핵심이다. 한 테이블이면 락 행이 만료·삭제될 때 카운터도 함께
사라지고, 다음 획득자가 이전 홀더와 같은 토큰을 받아 펜싱이 무의미해진다.

### 3.2 doorbell — LISTEN/NOTIFY 는 페이로드를 나르지 않는다

`NOTIFY` 의 페이로드 한도는 8000 바이트다. 신호의 `EVENT` 프레임은 그것을 쉽게 넘는다. 그래서

```
publish:   INSERT INTO conversation_signal (...) RETURNING id
           → SELECT pg_notify('conversation_signal_doorbell', '<id>')
subscribe: LISTEN conversation_signal_doorbell
           → 깨어나면 SELECT * FROM conversation_signal WHERE id > $lastSeen ORDER BY id
```

NOTIFY 는 **초인종**이고 페이로드는 테이블에 있다. 여기서 세 가지가 따라온다.

- **채널은 하나다.** LISTEN 은 세션(커넥션) 스코프이지 채널 스코프가 아니므로, 노드가 구독한 세션이
  몇 개든 커넥션은 **하나면 된다.** 어느 세션의 신호인지는 in-memory 로 거른다. 규모에서 결정적인
  예산 속성이다.
- **재연결이 손실을 만들지 않는다.** 커넥션이 끊기면 재연결 → `LISTEN` 재발행 → `id > lastSeen`
  백로그 질의. 행은 NOTIFY 전달과 **독립적으로** 테이블에 있으므로 그 사이 신호가 복구된다.
- **NOTIFY 유실에 대한 백스톱이 있다.** 백엔드 크래시로 NOTIFY 가 통째로 사라져도 주기적 self-poll
  (기본 5초)이 같은 질의를 다시 돌려 늦게라도 배달한다.

채널 이름은 얼어 있다. 롤링 배포에서 구버전 노드가 옛 채널을 듣고 신버전이 새 채널로 쏘면, 두 집단이
서로의 신호를 못 본다 — 다운타임 없이 바꿀 수 없는 종류의 이름이다.

### 3.3 커넥션 예산

커넥션 사용자가 셋이고, **성질이 달라 풀을 나눈다.**

| 사용자 | 수명 | 배치 | 크기 |
|--------|------|------|------|
| 락 / 인박스 / 멱등 / 레코드 연산 | 짧은 트랜잭션 (< 50 ms) | Hikari 메인 풀 | `min=4, max=20` |
| 리스 갱신 + sweeper | 짧은 트랜잭션, 스케줄 | 메인 풀 공유 | — |
| `ListenDispatcher` 의 LISTEN 커넥션 | **장수명, 노드당 1개** | **Hikari 밖** — 직접 `DriverManager` | 정확히 1 |

LISTEN 커넥션이 풀 밖인 것은 **LISTEN 상태가 커넥션에 묶여 있어** 풀에 반납될 수 없기 때문이다.
행 fetch 용으로 작은 두 번째 풀(`min=1, max=2`)을 두면 턴 쪽 INSERT 가 커넥션을 기다리는 동안
신호 fetch 왕복이 멈추지 않는다.

노드 하나가 200 동시 턴 + 50 구독 세션을 서빙할 때 `≤ 23 커넥션`. 4노드 클러스터면 프라이머리에
`max_connections ≥ 100` 이 필요하다.

### 3.4 운영

```bash
psql "$DATABASE_URL" -c 'CREATE SCHEMA IF NOT EXISTS aimon_session;'
psql "$DATABASE_URL" --set=search_path=aimon_session,public \
     -v ON_ERROR_STOP=1 -f V1__init.sql
```

Flyway / Liquibase 를 쓰면 같은 파일을 그 도구에 먹이면 된다 — 모듈은 무엇이 적용했는지 가정하지 않는다.
런타임은 시작 시 **읽기 전용 단언**만 수행해 DDL 미적용을 빨리 실패시킨다.

`postgresql.conf` 에서 확인할 것:

- `synchronous_commit = on` (기본값). 락과 멱등 불변식이 durable commit 을 전제한다 — 임시로도 끄지 말 것
- `idle_in_transaction_session_timeout = 30s`. 리스 갱신기가 실수로 트랜잭션을 열어 둔 버그를 잡는다
- `max_connections ≥ 100` (§3.3)

### 3.5 나중에 필요해지면 추가하는 인덱스

`V1` 은 **정확성에 필요한 최소 인덱스만** 담는다. 아래 중 하나라도 걸리기 전에는 추가하지 않는다.

| 트리거 | 임계 |
|--------|------|
| 인박스 드레인 퇴화 | `collect` 의 `EXPLAIN` 이 인덱스 스캔에서 시퀀셜 스캔으로 뒤집힘, **또는** 드레인 p95 > 25 ms (10분 지속) |
| 멱등 테이블이 DONE 행으로 포화 | DONE 행이 전체의 **50% 초과** (1시간 지속) |
| 멱등 sweeper 지연 | `findStaleInFlight` p95 > **100 ms** (30분 지속) |
| 인박스 테이블 크기 | `conversation_inbox` 가 **1000만 행** 초과 |

추가할 때의 내용은 부분 인덱스 둘이다 — `priority <= 1` 인 인박스 행, `status = 'IN_FLIGHT'` 인 멱등 행.
`CREATE INDEX CONCURRENTLY` 는 트랜잭션 안에서 돌 수 없으므로 이것도 운영자가 수동으로 적용한다.

---

## 4. MongoDB

### 4.1 컬렉션

`init.js` 를 **클러스터당 한 번** 적용한다. 멱등하다.

```bash
mongosh "mongodb://localhost:27017/aimon_session?replicaSet=rs0" \
        modules/aimon-session-mongodb/src/main/resources/db/mongodb/init.js
```

| 컬렉션 | 특이사항 |
|--------|---------|
| `conversation_locks` | 세션당 1문서 |
| `conversation_signals` | **capped, 64 MiB** — 절대 자라지 않는다 |
| `conversation_inbox` | `by_conv_priority_fifo` = `(conversationId, priority, deliveredAt)` |
| `idempotency_entries` | `ttl_expires_at` (`expireAfterSeconds: 0`) + `by_status_touch` |
| `background_task` | `by_state`, `by_context` |
| `session_records` | 전사(문자열) + side field |

**런타임은 이 스크립트를 실행하지 않는다.** 저장소 생성자는 인덱스 존재 여부를 *확인조차 하지 않는다* —
운영자가 적용했다고 가정한다. 잊었을 때의 피드백은 성능 저하와, 신호 버스의 경우
`watch()` 에서의 예외뿐이다.

### 4.2 복제 세트는 필수다

Change Streams 는 MongoDB 에서 유일하게 실용적인 push 메커니즘이고, **단일 노드 배포에서도 복제
세트를 요구한다.** `init.js` 는 `replSetGetStatus` 가 실패하면 경고를 찍지만 스스로 `rs.initiate()`
하지는 않는다 — 그것은 네트워크 토폴로지와 영속 정책에 묶인 운영 결정이다.

```bash
mongod --replSet rs0 --dbpath /tmp/mongo
mongosh --eval 'rs.initiate()'
```

복제 세트를 돌릴 수 없는 배포라면 **이 백엔드가 답이 아니다.** Redis 나 Postgres 를 쓴다 — 둘 다
단일 프라이머리에서 동작하며 복제 토폴로지를 요구하지 않는다.

capped collection 의 tailable cursor 로 standalone 을 지원하는 것은 기계적으로 가능하지만 구현하지
않는다. resume token 이 없고, capped 의미론에 강하게 결합되며, Mongo 자신의 문서가 신규 코드에는
Change Streams 를 권한다.

프로비저닝 체크리스트:

| 항목 | 권장 |
|------|------|
| 멤버 수 | 프로덕션 3 (PSS). 개발은 단일 노드 `rs.initiate()` |
| Oplog 크기 | 피크 쓰기량 기준 **24시간 이상** — resume token 복구 가능 구간을 결정한다 |
| Write concern | 전부 `majority`. URI 에 `?w=majority&readConcernLevel=majority` |
| Read preference | `primary`. 신호 버스와 락이 강한 일관성을 요구하므로 secondary 읽기는 안전하지 않다 |

### 4.3 시간은 서버가 정한다 — `$$NOW`

리스 steal/extend/release 와 멱등 mark-done 은 전부 서버 사이드 `$$NOW` 집계 변수를 쓴다. 두 매니저
노드가 리스 만료 시각에 대해 **의견이 갈릴 수 없게** 하기 위함이다. 애플리케이션 `Clock` 이 쓰이는
자리는 넷뿐이고 전부 안전하다.

- `acquiredAt` — 정보성
- 콜드 스타트의 첫 `insertOne` — MongoDB 가 `insertOne` 에서 `$$NOW` 를 거부한다. 비교할 이전 리스가
  없고 이후의 모든 펜싱 판단은 서버 사이드이므로 안전하다
- resume token 기록
- sweeper 의 `cutoff` 인자 — 비교 자체는 서버가 쓴 `lastTouchedAt` 에 대해 서버에서 수행된다

Postgres 도 같은 원칙이다 — 모든 시간 비교가 `now()` 이지 `Instant.now()` 가 아니다. **애플리케이션
서버들의 시계 편차는 무해하고, DB 의 시계만 중요하다.**

### 4.4 read / write concern

- **락 · 인박스 · 멱등 · 레코드 쓰기**: `w: "majority"`. "프라이머리에 썼는데 복제 전에 프라이머리가
  죽는" lost-acquire 를 막기 위해 필수다
- **읽기**: `readConcern: "majority"`. 펜싱 판단이 durable 하려면 필수
- **Change stream**: 노브가 없다 — 기본적으로 majority-committed oplog 를 읽는다

### 4.5 배선

`MongoClient` 는 thread-safe 하고 수명이 싱글턴이다. `MongoSessionBackends` 가 한 `MongoDatabase`
위에 여섯을 조립하고 접근자로 노출한다 — 조립하는 쪽이 객체 하나만 들고 다니면 된다. 컬렉션 이름과
멱등 TTL, `Clock` 은 빌더로 덮어쓸 수 있다(덮어쓰면 `init.js` 도 맞춰 고쳐야 한다).

종료는 순서가 있다 — 신호 버스를 먼저 닫아 change stream watcher 를 멈추고, 그다음 클라이언트를 닫아
풀을 놓는다.

**풀 크기**: 드라이버 기본값은 `maxPoolSize=100`. change stream 의 장수명 커서가 **한 슬롯을 수명 내내
점유**하므로 `동시_턴_수 + 5` 가 안전한 어림이다.

---

## 5. Redis

가장 단순하고 가장 빠르며, 전달 보장이 가장 약하다.

- **키 레이아웃**: 레코드는 `{keyPrefix}:{sessionId}` HASH 하나. `sessionId` 필드가 **레코드의 존재
  자체**를 뜻하고 나머지는 §1.2 대로 없으면 기본값이다
- **신호**: `RedisPubSubSignalBus` — pub/sub 은 **연결이 끊긴 동안 at-most-once** 다. 끊긴 사이의
  신호는 복구되지 않는다. 이벤트가 best-effort 라는 계약이 여기서 나온다
- **멱등 TTL**: Redis 가 자체 만료를 하므로 sweeper 가 primary TTL 을 대신 돌 필요가 없다
- **복제 토폴로지 요구 없음** — 단일 프라이머리로 충분하다

---

## 6. 무엇이 어떻게 깨지는가

### 6.1 세 백엔드 공통

| 실패 | 탐지 | 동작 |
|------|------|------|
| 저장소 도달 불가 | SPI 메서드에서 예외 | 세션 예외로 전파, 제출은 503. 리스 갱신기는 tick 마다 WARN |
| 긴 턴이 리스를 초과 | `extend()` 가 false (펜싱 토큰 불일치) | 갱신기가 `LEASE_LOST` 인터럽트를 발화, 부분 상태는 커밋되지 않음 |
| 두 노드가 동시에 홀더 손실을 탐지 | 둘 다 `compareAndReset` | 저장소가 직렬화 — 하나만 이기고 복구를 돌린다. 진 쪽은 skip |
| 스키마/스크립트 미적용 | Postgres: 시작 단언 실패 / Mongo: `watch()` 실패 또는 느린 스캔 | 운영자가 적용. **런타임이 자동 생성하지 않는다** |

### 6.2 PostgreSQL

| 실패 | 동작 |
|------|------|
| LISTEN 커넥션 끊김 | 재연결 → `LISTEN` 재발행 → `id > lastSeen` 백로그. **신호 손실 없음** |
| 백엔드 크래시로 NOTIFY 유실 | 5초 self-poll 이 잡는다. 늦은 배달(≤ 5s) |
| sweeper 끼리의 경쟁 | `DELETE … WHERE holder_id = ?` 를 Postgres 가 직렬화 — 하나가 1행, 다른 하나가 0행 |
| 인박스 드레인 경쟁 | `FOR UPDATE SKIP LOCKED` 로 서로 다른 행을 진행. 홀더만 collect 한다는 불변식의 이중 방어 |
| `idempotency_entry` 무한 증가 | sweeper 가 조용히 실패한 것. ERROR 로그 + 메트릭 상승. 제출 경로는 막지 않는다 |

### 6.3 MongoDB

| 실패 | 동작 |
|------|------|
| resume token 만료 (oplog 가 지나감) | WARN 후 **토큰 없이** 스트림 재개(live tail). 그 사이 이벤트는 유실 — 이벤트가 best-effort 인 계약과 일치. 운영 조치는 oplog 확대 |
| 프라이머리 step-down | 드라이버가 새 프라이머리로 자동 재시도. 모든 SPI 가 재시도에 멱등하다 — `tryAcquire` 는 재발행되어 성공(만료 리스 탈취)하거나 실패, `extend` 는 같은 펜싱 삼중항이라 리스가 유효할 때만 성공, `markDone` 은 멱등 |
| TTL 모니터 지연 (최대 60초) | `expiresAt` 이 지난 항목이 최대 60초 더 읽힌다. 무해하다 — stale-IN_FLIGHT 탐지는 TTL 이 아니라 `lastTouchedAt` 스캔으로 하고, 살아남은 DONE 항목은 캐시된 결과가 여전히 유효하다 |
| 문서 크기 한도 16 MiB | `markDone` 이 실패하고 결과가 재생 불가로 취급된다. `StoredAgentExecutionResult` 투영은 최종 답변 + 에러 메시지 + 작은 enum 뿐이라 통상 1 MiB 훨씬 아래다. 거대한 산출물을 내는 도구는 GridFS/S3 에 쓰고 **참조만** 담아야 한다 |
| 커넥션 풀 고갈 | change stream 의 장수명 커서 + 버스트 턴 부하. 제출이 빨리 실패한다. `maxPoolSize` 를 올린다(§4.5) |

---

## 7. 어느 것을 고를 것인가

먼저 **이미 운영 중인 것**을 쓴다. 그것으로 갈리지 않을 때의 기준이다.

| 상황 | 답 |
|------|-----|
| 복제 세트를 돌릴 수 없다 | **Mongo 아님.** Redis 또는 Postgres |
| 신호 팬아웃의 지연이 임계적이다 | **Redis.** pub/sub p50 ≈ 1 ms. Mongo Change Streams 는 p50 ≈ 10–50 ms |
| 끊김을 넘는 이벤트 재개가 필요하다 | **Mongo.** resume token 이 oplog 구간 안에서 at-least-once 를 준다. Redis pub/sub 은 끊기면 at-most-once |
| 신호 손실 없는 재연결 + 관계형 운영 도구 | **Postgres.** 신호가 행으로 남으므로 재연결이 백로그를 되읽는다 |
| 인박스·멱등 상태를 애드혹으로 들여다봐야 한다 | Postgres(SQL) 또는 Mongo(`mongosh`). Redis Streams 는 다루기 번거롭다 |

---

## 8. 메트릭 — 두 개의 버킷

세 백엔드가 같은 규칙을 따르므로 한 백엔드용으로 만든 대시보드가 다른 백엔드로 옮겨간다.

**버킷 A — SPI 레벨.** 매니저(`SessionMetrics`)가 낸다. 백엔드가 *원인*일 수는 있어도 *발행*은 매니저
쪽이다. 형식은 `aimon.session.<spi>.<op>{outcome=…,backend=…}` 이고 **모든 SPI 메트릭이
`backend=` 태그를 단다** — 여러 백엔드를 함께 돌리는 운영자가 대시보드를 분리하거나 태그를 가로질러
집계할 수 있다.

| 메트릭 | 무엇을 말하는가 |
|--------|----------------|
| `aimon.session.lock.acquire{outcome=success\|rejected\|error}` | 락 경합과 획득 지연, 거절률 |
| `aimon.session.lock.extend{outcome=success\|fenced\|error}` | 리스 갱신 건강도. `fenced` = 토큰 불일치(리스를 뺏김) |
| `aimon.session.holder_loss.recovered{outcome=success\|noop\|error}` | 정상 상태에서 ~0 이어야 한다 |
| `aimon.session.inbox.deliver` / `.collect{outcome=success\|empty\|error}` | 적재 처리량 / 드레인 지연 |
| `aimon.session.idempotency.put{outcome=inserted\|existing\|error}` | 재생(dedup) 적중률 |
| `aimon.session.idempotency.compare_and_reset{outcome=reset\|noop\|error}` | sweeper 주도 복구율 |
| `aimon.session.signal.publish{outcome=…,kind=INTERRUPT\|EVICT\|MESSAGE_ENQUEUED\|EVENT}` | 종류별 발행 지연 |

**버킷 B — 백엔드 전용.** 해당 모듈이 직접 낸다. 형식은 `aimon.session.<backend>.<feature>.<measurement>`.
단조 카운터는 `_total`, 지속시간은 단위 접미사(`_ms`, `_seconds`), 바이트는 `bytes`.

| Postgres | Mongo |
|----------|-------|
| `postgres.signal.doorbell.lag_ms` | `mongo.signal.changestream.lag_ms` |
| `postgres.signal.listen.reconnects_total` | `mongo.signal.changestream.resumes_total` |
| `postgres.signal.notify.dropped_estimate_total` | `mongo.signal.changestream.history_lost_total` |
| `postgres.idempotency.sweeper.cleared_total` / `.scan_ms` / `.errors_total` | `mongo.ttl.lag_seconds` |
| `postgres.inbox.deliver.bytes` / `.collect.batch_size` | `mongo.idempotency.result.bytes` |
| `postgres.pool.connections.active` | `mongo.pool.connections.active` / `mongo.failover_total` |

**나중에 메트릭을 추가할 때의 기준:** 다른 백엔드에 의미 있는 대응물이 있으면 버킷 A(매니저 소유),
그 백엔드에만 있는 기능(Change Streams, LISTEN, TTL 모니터, 드라이버 풀)을 서술하면 버킷 B 다.

DB 자신의 서버 사이드 메트릭(oplog 창, 복제 지연, `pg_stat_*`)은 이 설계의 범위 밖이다 — 표준 도구로
수집한다.

---

## 9. 용량

| | 증가 요인 | 규모 |
|---|---|---|
| 인박스 | 턴마다 드레인 | 정상 상태에서 작다(진행 중 세션 × 대기 메시지). 세션당 백로그 알람을 예: 1000 에 건다 |
| 멱등 | 24h primary TTL × 피크 제출률 | 100 RPS ≈ 860만 항목 × ~500 B ≈ 4 GiB |
| 신호 | — | Mongo 는 64 MiB capped 로 절대 안 자란다. Postgres 는 기본 5분 보존 |
| 레코드 | 세션 수 × 전사 길이 | 유일하게 **명시적 삭제까지** 자라는 것 |

---

## 10. 테스트

Testcontainers 기반이며 `@Tag("docker")` 로 `integrationTest` 태스크에서만 돈다.

- **SPI 계약 테스트**는 백엔드마다 반복하지 않는다 — 하나의 계약 스위트를 각 모듈이 어댑터로 돌린다
- **DDL 을 실행하는 유일한 자리는 테스트 지원 클래스**다. 프로덕션 코드는 절대 부르지 않는다.
  테스트가 프로덕션과 같은 DDL 파일을 새 컨테이너에 적용하므로 §2 의 동결 위험이 여기서는 감지되지
  않는다는 점을 기억할 것 — 그래서 freeze 테스트가 따로 있다
- **멀티 노드 하네스**는 한 컨테이너에 대해 **두 개의 독립 풀/클라이언트**를 만든다. 두 애플리케이션
  프로세스를 흉내 내는 것이므로 in-memory 저장소를 공유하면 안 된다
  ([`session-model.md`](session-model.md) §5.2 — `SessionStore` 는 노드 스코프다)

멀티 노드 시나리오는 넷이다 — 크로스 노드 인터럽트, 크로스 노드 핸드오프(A 가 락을 쥔 채 B 로 들어온
메시지가 인박스를 거쳐 A 에게), 홀더 손실 복구, 두 노드 동시 제출(정확히 하나가 즉시 실행되고 다른
하나는 큐).

**의도적으로 테스트하지 않는 것:** 백엔드 간 마이그레이션(운영자 주도 — 드레인 후 전환),
멀티 도큐먼트 트랜잭션(쓰지 않는다), 샤드 클러스터, Postgres 프라이머리 페일오버(멀티 컨테이너 필요),
읽기 복제본 라우팅.

읽기 복제본은 단순히 미구현이 아니라 **안전하지 않다** — 복제 지연이 진행 중인 멱등 항목을 가려
dedup 매트릭스에 거짓 음성을 만든다. bounded-staleness 읽기 없이는 열 수 없다.

---

## 11. 열어 둔 것

| 항목 | 상태 |
|------|------|
| 쓰기 시점 펜싱을 레코드 저장소까지 확장 | 지금은 `SessionStore.records()` 가 위임 직전에 `findHolder` 로 재증명한다. sub-millisecond 창이 남는다 ([`session-model.md`](session-model.md) §5.3) |
| 인박스 배치 `deliverAll` | 세션 핫스팟 부하에서 왕복을 분할상환한다. SPI 변경이므로 세 구현을 한 번에 바꿔야 싸다 |
| `conversation_signal` 파티셔닝 | 기본 5분 보존은 중간 볼륨까지 충분하다. 고볼륨이면 시간 파티셔닝으로 `DETACH` 가 `DELETE` 를 대체 |
| 핫 세션에 `pg_advisory_xact_lock` | 한 세션이 초당 100 획득을 넘으면 UPSERT 를 직렬화. 기능 플래그 뒤 |
| 16 MiB 초과 결과의 GridFS 탈출구 | 지금은 상위에서 크기를 제한한다. 실제로 한도를 치면 재검토 |

---

## 관련 문서

- [`session-model.md`](session-model.md) — 무엇이 왜 영속되는가
- [`routing.md`](routing.md) — 이 백엔드들 위의 라우팅·인박스·신호 계층
- [`spi-extraction.md`](spi-extraction.md) — SPI 가 `aimon-core` 로 내려온 경위
- [`../../features/session/web-session-deployment-guide.md`](../../features/session/web-session-deployment-guide.md) — 운영자용 배포 가이드
