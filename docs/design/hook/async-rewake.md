# 비동기 재개 (Async Rewake)

> Status: **IMPLEMENTED** — 시간 트리거와 외부 이벤트 트리거가 모두 들어가 있고, 어셈블리
> (`AimonStackBuilder`)가 서비스·리스너를 조립한다. 남은 것은 §8 — MCP 브리지의 트랜스포트 배선,
> 웹훅 서버 부팅, 그리고 멀티 인스턴스용 envelope 영속화다.
>
> 적용 대상: `aimon-core` — `at.aimon.core.hook.rewake` (SPI: `RewakeSpec`, `RewakeEnvelope`,
> `RewakeService`, `RewakeFireListener`, `RewakeCapableRuntime`, `RewakeQuotaManager`,
> `ExternalEventResolver`), `…rewake.impl` (기본 구현 4개), `…rewake.mcp` (MCP 알림 브리지),
> `at.aimon.core.config.hook.rewake` (`asyncRewake` 파서) ·
> `aimon-scheduling-quartz` — `QuartzRewakeService` (cron·클러스터) ·
> `aimon-rewake-webhook` — HMAC 검증 Javalin 엔드포인트 · `aimon-bootstrap` — 조립.

---

## 1. 문제

훅이 **결정을 미루고 나중에 다시 발화**해야 하는 경우가 있다.

1. **승인 워크플로** — `preTool` 훅이 외부 승인 서비스를 부른다. 몇 분씩 훅 실행기 스레드를 붙잡는 대신
   5분 뒤에 다시 발화해서 그때 allow/deny 를 정한다
2. **외부 이벤트 대기** — `deploy.complete` 웹훅이 올 때까지 기다렸다가 후속 조치를 한다
3. **재시도** — `postTool` 훅이 일시적 실패를 보고 15분마다 최대 3회 후속 알림을 예약한다

원시가 없으면 훅 저자의 선택지는 셋뿐이고 전부 나쁘다 — (a) 동기 블록(실행기 스레드 소진 + 타임아웃),
(b) 결정 포기(데이터 손실), (c) 훅 바깥에 자기 스케줄러를 만들기(관측 불가, 핫리로드와 무관).

**하지 않는 것**은 명확하다. rewake 는 **훅을 다시 발화**할 뿐 진행 중이던 LLM 턴이나 도구 실행을
재개하지 않는다. 원래 호출은 rewake 가 예약되기 전에 이미 결말(ALLOW/DENY/fail-open)이 나 있어야 한다.
분기도 없다 — 한 번의 rewake 는 정확히 하나의 후속 발화를 만든다.

---

## 2. 결정 축이 아니라 **직교 채널**이다

첫 설계는 `Decision.ASYNC_REWAKE` 를 추가하는 것이었고, 구현 중에 뒤집혔다.

rewake 는 **살아 있는 턴에게는 관측상 `ALLOW`** 다 — 즉시 디스패치 결과를 바꾸지 않는다. 그것을
`ALLOW/ASK/DENY` 와 같은 서수에 접으면 `getDecision()` 을 읽는 모든 소비자가 "턴이 진행되는 조건"의
뜻을 다시 배워야 하고, `Decision` 위의 전순서(`DENY > ASK > ALLOW`)가 망가진다.

그래서 `HookResult` 에 **직교 컬렉션 필드**를 얹었다.

```java
HookResult.asyncRewake(RewakeSpec spec)
HookResult.builder().rewakeSpec(a).rewakeSpec(b).build()
List<RewakeSpec> getRewakeSpecs()   // null 아님, 비어 있을 수 있음
```

`Decision` 과 `FlowControl` 은 그대로다. 훅은 세 값의 **아무 조합**이나 낼 수 있다 —
`DENY` + rewake(지금 호출은 막고 후속만 예약)도 유효하며, 흔한 경로는 `ALLOW` + rewake 다.

머지 규칙: 두 결정 축은 기존 그대로 접히고, `rewakeSpecs` 는 **인자 순서대로 연결**된다.
어느 훅이 낸 spec 이든 전부 살아남고 서비스가 전부 스케줄한다. 머지 계층에 dedup 은 없다.

---

## 3. envelope — 무엇을 얼려 두는가

살아 있는 훅 컨텍스트에는 직렬화할 수 없는 런타임 참조(`ToolRegistry`, `HookRegistry`, 진행 중인 전사)가
붙어 있다. 그래서 `RewakeEnvelope` 는 **정체성만** 싣고, 발화 시점에 나머지를 다시 해석한다.

| 필드 | 쓰임 |
|------|------|
| `envelopeId` | 취소·재스케줄·멱등의 키 |
| `agentRuntimeId` | **agent-scoped** 이고 결정론적이므로 다른 JVM 에서도 같은 값으로 resolve 된다 |
| `originalEventType` | 어느 체인으로 되돌아갈지 |
| `originatingHookId` | 그 체인의 **어느 훅**인지 (형제 훅은 이미 원발화를 봤으므로 부르지 않는다) |
| `originalToolName` / `originalToolInput` | `PRE_TOOL` 재현용 동결 스냅샷 |
| `attemptNumber` / `maxAttempts` / `timeout` / `firstScheduledAt` | 두 축의 상한을 발화 시점에 spec 없이 판정 |
| `payload` / `reason` | 임의 문자열 메타데이터, 로그·`/rewakes` 표시 |

`agentRuntimeId` 를 고른 것이 이 설계의 핵심이다. 실행마다 새로 나는 id 였다면 다운타임을 넘긴 발화가
아무것도 resolve 하지 못했을 것이다.

발화 시 `RewakeFireListener` 는 `AgentRuntimeRegistry.getAs(id, RewakeCapableRuntime.class)` 로 런타임을
되찾는다. **런타임이 없거나 그 SPI 를 구현하지 않으면 WARN 후 드롭**한다 — rewake 는 best-effort
delivery 이지 보장 배달이 아니다.

### 3.1 `RewakeCapableRuntime` — 베이스 인터페이스를 부풀리지 않기 위한 opt-in

재발화에는 `HookRegistry`(원 훅 찾기)와 `Environment`(컨텍스트 재구성)가 더 필요하다. 이 둘을 베이스
`AgentRuntime` 에 올리면 모든 구현체와 테스트 스텁이 따라 커진다. 대신 **마커 SPI** 를 두고 리스너가
`getAs(...)` 로 물어보며, 구현하지 않은 스텁의 발화는 그냥 드롭한다.

---

## 4. 어느 이벤트가 rewake 를 받을 수 있는가

세 등급이다. 판정은 두 지점에서 이뤄진다 — 스케줄 입구(`RewakeEnvelopes.from`)와 발화 지점
(`DefaultRewakeFireListener`).

| 등급 | 이벤트 | 처리 |
|------|--------|------|
| **재발화** | `PRE_TOOL`, `ON_CONFIG_RELOAD`, `PRE_COMPACT`, `ON_SESSION_START`, `ON_SESSION_END` | 컨텍스트를 재구성해 원 훅만 다시 부른다 |
| **입구에서 거부** | `PERMISSION_REQUEST`, `PERMISSION_DENIED` | `RewakeEnvelopes.from()` 이 `IllegalArgumentException`; 스케줄러가 잡아 WARN 후 spec 만 버린다 |
| **발화 시 드롭** | `POST_TOOL`, `POST_COMPACT`, `ON_STOP`, `ON_START`, `SUBAGENT_START`, `SUBAGENT_STOP` | WARN 후 드롭 |

**권한 이벤트를 입구에서 막는 이유**는 시점이다. 권한 결정이 도구가 이미 디스패치된 **뒤에** 도착하면
그 결정은 아무것도 못 막으면서 재생 소음만 남긴다. 동기적이어야 의미가 있는 결정이므로 예약 자체를
금지한다. 단 예외를 스케줄러가 삼켜 **동기 권한 판정은 방해받지 않는다**.

**발화 시 드롭되는 것들의 이유는 두 갈래다.** `POST_TOOL` / `POST_COMPACT` 는 컨텍스트가
`ToolUseResult` · 전사 · `CompactionMetadata` 같은 런타임 상태를 요구하는데 envelope 스키마가 그것을
담지 않는다 — 넓히려면 스키마 변경이 먼저다. 나머지 넷(에이전트·서브에이전트 수명 이벤트)은 out-of-band
재생에 **방어할 수 있는 의미론이 아예 없다.**

재구성된 컨텍스트는 **degraded stand-in** 이다. envelope 의 정체성 + 이벤트별 기본값(카운터 0,
`CompactionTrigger.MANUAL` 등)으로 만들며, `PRE_TOOL` 재발화에서도 **도구는 실제로 실행되지 않는다.**

---

## 5. 두 개의 트리거, 두 개의 구현

`RewakeTrigger` 는 셋이다 — `RewakeTriggerDelay`(1회), `RewakeTriggerCron`(반복),
`RewakeTriggerEvent`(외부 이벤트).

| 구현 | delay | cron | event | 쓰는 곳 |
|------|:-----:|:----:|:-----:|--------|
| `DefaultRewakeService` (`aimon-core`) | ○ | **✗** `UnsupportedOperationException` | ○ | 단일 인스턴스 |
| `QuartzRewakeService` (`aimon-scheduling-quartz`) | ○ | ○ | ○ | 클러스터 |

core 에 cron 파서를 넣지 않은 것은 의도다. cron 은 Quartz 만의 능력이고, core 가 자기 파서를 갖는 순간
두 개의 cron 방언이 생긴다.

`schedule()` 은 `envelopeId` 에 대해 **멱등**이다 — 같은 id 를 두 번 넣으면 이전 등록을 취소하고 교체한다.

### 5.1 cron 은 두 축으로 묶인다

무한 반복을 막는 방법이 둘이고, 서로를 보완한다.

- **시간축** — Quartz 트리거에 `endAt(firstScheduledAt + timeout)` 을 건다. 스케줄러가 **자율적으로**
  멈추므로 `RewakeJob.execute` 가 도는지와 무관하다
- **횟수축** — 매 성공 발화마다 `advanceCronAttempt(...)` 가 `attemptNumber` 를 올리고, 다음 시도가
  `maxAttempts` 를 넘으면 envelope 를 제거하고 Quartz 잡을 지운다

기본값은 `RewakeSpec.DEFAULT_TIMEOUT`(1시간)과 `DEFAULT_MAX_ATTEMPTS`(3)이다.

misfire 정책은 다운타임 뒤 **일단 발화**다 — 1회성은 `MISFIRE_INSTRUCTION_FIRE_NOW`, cron 은
`FIRE_ONCE_NOW`. envelope 의 `firstScheduledAt` 이 있으니 너무 늦은 발화를 버릴지는 핸들러가 정한다.
Quartz `JobDataMap` 에는 **`envelopeId` 만** 싣는다 — envelope 본문은 서비스 안에 있고, 직렬화 형식이
잡 데이터에 굳는 것을 피한다.

### 5.2 rewake 안의 rewake

재발화한 훅이 또 `asyncRewake` 를 내면 `RewakeEnvelopes.chained(previous, nextSpec)` 로 이어지고,
`previous.attemptNumber + 1 > nextSpec.maxAttempts` 면 WARN 후 드롭한다. 체인에는 서비스 바인딩
(`bindRewakeService`)이 필요하며, 바인딩이 없으면 INFO 로 드롭한다 — 바인딩 없는 리스너를 쓰는 테스트
픽스처가 원발화는 그대로 관측하게 하기 위해서다.

---

## 6. 외부 이벤트

`ExternalEventResolver` 가 `(eventType, eventKey)` 를 등록된 envelope 로 라우팅하고,
`RewakeService.resolve(eventType, key, payload)` 가 그것을 시간 발화와 **같은 핸들러 경로**로 넘긴다.
들어온 payload 는 envelope 의 payload 에 병합된다.

진입점은 둘이다.

- **웹훅** (`aimon-rewake-webhook`) — Javalin 엔드포인트. 기본 경로 `/rewake/events`, HMAC 서명은
  `X-Rewake-Signature` 헤더, 멱등 키는 `X-Rewake-Idempotency-Key` 헤더에 24시간 보관 윈도우.
  비밀키는 `CredentialStore` 프로필(`rewake-webhook` / `hmac-secret`)에서 읽는다 — 설정 파일에 두지 않는다
- **MCP 알림** (`…rewake.mcp`) — `McpNotificationToRewakeBridge` 가 `McpNotificationListener` 로서
  알림을 `resolve()` 호출로 번역한다

별도 모듈로 뽑은 이유는 의존성이다. HTTP 서버를 `aimon-core` 에 넣으면 웹훅을 쓰지 않는 모든 조립이
Javalin 을 끌고 온다.

---

## 7. 운영 표면

### 7.1 쿼터

폭주 스케줄링을 막는 캡이 `RewakeQuotaManager` 다. 기존 `TaskQuotaManager` 를 재사용하지 않은 이유는
**키가 다르기 때문**이다 — 그쪽은 `Principal` 로 키잉되는데 rewake 파이프라인은 발화 시점에 principal 을
갖고 있지 않다. 그래서 `AgentRuntimeId` 로 키잉하는 SPI 를 따로 두었다.

`DefaultRewakeQuotaManager` 는 CAS 로 지킨 `tryAcquire`/`release` 와 컨텍스트별 오버라이드를 갖고
기본 캡은 `DEFAULT_MAX_QUOTA`(64)다. `DefaultRewakeService` 는 `schedule()` 에서 물어보고(캡이면 WARN 후
드롭) 모든 제거 경로(취소·발화·resolve·close·트리거 오류 롤백)에서 슬롯을 반납한다.
**기본값은 `RewakeQuotaManager.NOOP` 이라 강제는 opt-in** 이다.

### 7.2 핫리로드와의 관계

설정 스왑으로 원 훅이 사라지면 `HookRegistryReloader` 가 `cancelByOriginatingHookId(...)` 로 그 훅의
대기 중 envelope 를 전부 무효화한다. 이것이 성립하려면 훅 id 가 **리로드를 넘어 안정적**이어야 하고,
그래서 선언적 훅은 내용에서 파생된 `DeclarativeHookId` 를 쓴다
([`hook-system.md`](hook-system.md) §5.3). 이미 리스너에 넘어간 발화는 되돌리지 않는다(best-effort).

### 7.3 가시성

`/rewakes` 시스템 명령(`RewakeListCommand`)이 `RewakeService.listPending()` 스냅샷을 envelope 당 한 블록
(id, 원 훅, agent 컨텍스트, 트리거 요약 `delay PT5M` / `event TYPE:KEY` / `cron 'EXPR' (ZONE)`, 시도 횟수,
경과, 사유)으로 렌더한다. 등록은 **조건부**다 — `OrcaSystemCommandProvider` 는 배선된 `RewakeService` 가
`null` 이거나 `NOOP` 이면 명령을 등록하지 않는다. 서비스 예외는 `"Failed to query: <message>"` 로 표시되어
`execute()` 밖으로 새지 않는다.

### 7.4 조립

`AimonStackBuilder`(`aimon-bootstrap`)가 `DefaultRewakeService` + `DefaultRewakeFireListener` 쌍을
application-scoped 로 만들어 `bindRewakeService` 로 묶고, 서비스를 두 팩토리에 흘린다 —
`OrcaAgentExecutorFactory.withRewakeService(...)`(훅이 낸 spec 이 스케줄되도록)와
`OrcaAgentRuntimeFactory.withRewakeService(...)`(명령 프로바이더가 non-NOOP 서비스를 보도록).
teardown 순서상 rewake 는 스케줄링 다음이다 — 스케줄된 루틴이 rewake 를 무장시키는 주체 중 하나이기 때문.

---

## 8. 남은 것

- **MCP 브리지가 트랜스포트에 붙어 있지 않다.** `McpNotificationToRewakeBridge` 는 있지만 실제
  `StdioMcpTransport` 읽기 루프에 등록하는 코드가 인트리에 없다 — MCP 알림 경로는 아직 손으로 배선해야 한다
- **웹훅 서버를 부팅하는 엔트리포인트가 없다.** `RewakeWebhookServer` 를 시작하는 main 소스가 없다
- **envelope 가 프로세스 메모리에만 있다.** `DefaultRewakeService` 의 pending 은 `ConcurrentHashMap` 이고
  SQL/Mongo 어댑터는 없다. 멀티 인스턴스 운영은 현재 Quartz 잡 저장소가 커버하는 범위(시간 트리거)까지이며,
  이벤트 트리거 envelope 는 재시작을 넘지 못한다. 저장소 SPI 를 먼저 뽑는 것이 순서다
- **`asyncRewake` 파싱 관대함** — 의미 없는 spec(`{"asyncRewake": true}`)은 파스 에러가 아니라
  드롭으로 처리된다. 엄격 모드는 별도 결정으로 남겨 두었다

---

## 9. 기각한 대안

| 대안 | 왜 기각했나 |
|------|------------|
| `Decision.ASYNC_REWAKE` 상수 추가 | rewake 는 살아 있는 턴에게 `ALLOW` 다. 결정 서수에 접으면 모든 소비자가 "턴 진행 조건"을 다시 배워야 하고 `Decision` 전순서가 망가진다 |
| 머지에서 rewake spec dedup | 같은 훅 id 충돌은 허용 가능하다. 필요해지면 서비스 계층이 하는 편이 맞다 |
| envelope 에 살아 있는 컨텍스트 참조 저장 | 직렬화 불가이고 JVM 을 넘지 못한다. 정체성만 싣고 재해석한다 |
| 실행별 id 로 런타임 참조 | 다운타임을 넘긴 발화가 아무것도 resolve 하지 못한다. `AgentRuntimeId` 는 결정론적이다 |
| `HookRegistry`/`Environment` 를 `AgentRuntime` 베이스에 올리기 | 모든 구현체·테스트 스텁이 따라 커진다. opt-in `RewakeCapableRuntime` 로 충분하다 |
| 권한 이벤트도 rewake 허용 | 도구가 이미 디스패치된 뒤 도착하는 권한 결정은 아무것도 막지 못한다 |
| `TaskQuotaManager` 재사용 | `Principal` 로 키잉되는데 rewake 는 발화 시점에 principal 이 없다 |
| `DefaultRewakeService` 에 cron 파서 내장 | cron 방언이 둘이 된다. cron 은 Quartz 전용 능력으로 둔다 |
| 웹훅 서버를 `aimon-core` 에 | 웹훅을 안 쓰는 조립까지 Javalin 을 끌고 온다 |
| Quartz `JobDataMap` 에 envelope 전체 직렬화 | 직렬화 형식이 잡 저장소에 굳는다. `envelopeId` 만 싣는다 |

---

## 부록: 참조 파일 지도

| 파일 | 확인할 것 |
|------|----------|
| `hook/rewake/RewakeSpec.java:32,35` | `DEFAULT_TIMEOUT` 1h, `DEFAULT_MAX_ATTEMPTS` 3 |
| `hook/rewake/RewakeEnvelope.java:61-73` | envelope 가 싣는 13개 필드 |
| `hook/rewake/RewakeEnvelopes.java:59` | 권한 이벤트 입구 거부 |
| `hook/rewake/RewakeService.java` | `schedule`(멱등) / `cancel` / `cancelByOriginatingHookId` / `resolve` / `listPending` |
| `hook/rewake/RewakeCapableRuntime.java` | 재발화용 opt-in SPI |
| `hook/rewake/impl/DefaultRewakeService.java:66,161` | in-memory pending, cron 거부 |
| `hook/rewake/impl/DefaultRewakeFireListener.java:182-201` | 재발화 5종 / 드롭 나머지 |
| `hook/rewake/impl/DefaultRewakeQuotaManager.java:31` | `DEFAULT_MAX_QUOTA` 64 |
| `hook/rewake/mcp/McpNotificationToRewakeBridge.java` | MCP 알림 → `resolve()` |
| `config/hook/rewake/RewakeSpecParser.java` | `asyncRewake` 블록 파싱 |
| `agent/impl/orca/command/OrcaSystemCommandProvider.java:87` | `/rewakes` 조건부 등록 |

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준. 그 밖에 —
`modules/aimon-scheduling-quartz/…/rewake/QuartzRewakeService.java`(`endAt` cron 상한, misfire, `JobDataMap`),
`modules/aimon-rewake-webhook/…/webhook/RewakeWebhookConfig.java`(헤더·경로·크리덴셜 기본값),
`modules/aimon-bootstrap/…/AimonStackBuilder.java:326`(조립).

---

## 관련 문서

- [`hook-system.md`](hook-system.md) — 훅 결과 모델과 선언적 훅 id (rewake 가 그 위에 선다)
- [`hook-config-guide.md`](../../features/hook/hook-config-guide.md) — `asyncRewake` 설정 문법
- [`.claude/rules/hook-development.md`](../../../.claude/rules/hook-development.md) — cron rewake 는 체이닝하지 않는다는 규칙
- [`quartz-scheduling-web-deployment-guide.md`](../../features/scheduling/quartz-scheduling-web-deployment-guide.md) — Quartz 클러스터 배포
- [`references/hooks-specification.md`](../../references/hooks-specification.md) — parity 경계 (매핑·확장·미지원)
