# AgentRuntime 과 Agent Scope

> Status: **IMPLEMENTED** — 남은 것은 [§9](#9-남은-것)가 가리킨다.
> 적용 대상: `aimon-core` (`AgentRuntime` · `AgentRuntimeId` · `OrcaAgentRuntime` · `OrcaAgentRuntimeManager`),
> `aimon-bootstrap` (`AgentRuntimeResolver`), `aimon-session-routing` (`LiveSessionOpener` · `DefaultSessionRouter`)
> 관련 규칙: [`.claude/rules/scheduling.md`](../../../.claude/rules/scheduling.md),
> [`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md)
> 관련 문서: [`scope-model.md`](../../overview/scope-model.md) (수명 계층과 등록·해제 규칙의 정본),
> [`tool/execution-environment.md`](../tool/execution-environment.md) (실행 환경 제공자의 소유),
> [`session/routing.md`](../session/routing.md),
> [`integration/spring-boot-starter.md`](../integration/spring-boot-starter.md)
> 사용 가이드: [`embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md)

이 문서는 `AgentRuntime` 이 왜 agent-scoped 인가와, 그 수명이 강제하는 규칙 — 결정론적 id, 닫는 목록, 닫으면 안 되는
주체, 생성 경로 — 을 정한다. 수명 4계층 전체와 "무엇을 언제 닫는가" 의 규칙 표는
[`scope-model.md`](../../overview/scope-model.md) 가 정본이다.

`AgentRuntime` 은 실행마다 새로 생기는 값이 아니라 agent 당 하나 살아 있는 장수명 런타임이다. 옛 이름과의 대응은
[`rename-maps.md`](../../migration/rename-maps.md) 에 있다.

---

## 1. 왜 agent scope 인가

`AgentRuntime` 은 세션이 아니라 agent 수명에 속한다. 런타임이 담는 협력자(레지스트리, MCP 클라이언트, context engine,
제어 저장소) 가운데 세션에 종속된 값은 없고, 세션 상태는 `SessionRecord` 와 `LiveSession` 에 있기 때문이다. 런타임을
세션마다 만들고 무작위 id 를 주면, 세션보다 오래 사는 `ScheduledTask` 가 cron 재발화 때 자기 런타임을 찾지 못한다 —
`RoutineExecutor` 는 태스크가 기록해 둔 `boundRuntimeId` 로 레지스트리를 조회하는데, 그 id 를 발급한 세션은 이미 끝났다.

그래서 이것은 lifecycle 규칙의 문제가 아니라 scope 정의의 문제다. 어떤 해제 규칙을 붙여도 id 가 무작위인 한 재발화는
런타임을 찾지 못한다.

## 2. Agent scope 정의

Application 과 Session 사이에 **Agent** 를 둔다. 한 `Agent` 정의와 선택적 discriminator 의 조합 `(Agent, discriminator)`
하나가 수명 단위 하나다. 계층별 구성원과 다이어그램은 [`scope-model.md` §1](../../overview/scope-model.md#1-스코프-계층)
에 있다.

### 2.1 `AgentRuntimeId` — 결정론적 발급

id 는 (agent 이름, discriminator) 에서 결정론적으로 나온다. 무작위로 발급하는 팩토리(`generate()`)는 없다.

| 팩토리 | 결과 |
|--------|------|
| `from(Agent)` | `agent:<name>` |
| `from(Agent, String discriminator)` | `agent:<name>:<discriminator>` |
| `fromName(String)` / `fromName(String, String)` | 위 둘의 문자열 입력판 — 세션 라우터가 `agentRef` 에서 도출할 때 쓴다 |
| `of(String)` | 역직렬화 — 진입점에서 `agent:<name>[:<disc>]` 레이아웃을 완전히 검증한다 |

id 는 `agentName` 과 `discriminator` 를 구조적으로 저장하고 `value()` 를 두 필드에서 파생한다. 검증을 `of()` 한 곳에
모아, 도달 가능한 모든 인스턴스가 레이아웃 불변식을 만족하고 접근자가 실패하지 않게 한다.

discriminator 는 non-blank 이고 콜론을 포함할 수 없다(`IllegalArgumentException`). 콜론이 구분자이므로 그것을 허용하면
`value()` 의 파싱이 모호해진다.

### 2.2 전제

1. **런타임 : (Agent, discriminator) = 1 : 1.** discriminator 를 쓰지 않는 사용처에서는 agent 당 1개다.
2. **agent 이름의 유일성은 호출자 책임이다.** `AgentRegistry.register(Agent)` 는 같은 이름이 들어오면 기존 항목을 교체한다.
3. **discriminator 의 의미는 호출자가 정한다.** 프레임워크는 단순 식별자로만 취급한다 — 테넌트·환경·사용자 중 무엇으로
   쓸지, 요청에서 어떻게 고를지는 부트스트랩 코드의 몫이다.
4. **agent 정의의 hot-reload 는 범위 밖이다.** agent 정의가 런타임에 바뀌어도 (name, discriminator) 가 같으면 같은 id 다.
   훅 설정(`hooks.json`)의 hot-reload 는 이와 별개로 지원된다 — `HookHotReloadBootstrap` 이 런타임을 다시 만들지 않고
   살아 있는 훅 레지스트리에 변경을 반영한다.

## 3. agent-scoped 자원

### 3.1 `McpClientManager`

`AgentScoped` 마커는 agent 수명을 뜻하고, MCP 클라이언트 매니저는 거기 속한다.

- AIMON 의 주 사용처(IT 운영 자동화)에서 MCP 는 agent 정의에 종속된 인프라 도구다 — Kubernetes·클라우드 API 같은
  것으로, 사용자별 격리가 일반적인 요구가 아니다.
- 사용자별 자격증명이 필요하면 `CredentialStore`(application-scoped)를 도구 호출 시점에 조회한다.
- stdio MCP 는 프로세스 spawn 이다. 세션마다 spawn 하면 사용자가 지연을 체감한다.

세션 단위 MCP 가 필요해지면 라이브 세션 쪽에 별도 슬롯을 더한다. agent-scoped MCP 와 병존하므로 breaking change 없이
얹을 수 있다.

### 3.2 닫지 않는 것

- **런타임은 application-scoped 협력자를 닫지 않는다.** `KnowledgeStore`, `CredentialStore` 등은 `ApplicationScoped`
  마커가 그 계약이다.
- **런타임 소멸은 스케줄링 컴포넌트를 닫지 않는다.** `SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor` 는
  application-scoped 다.
- **`LiveSession.close()` 는 런타임을 닫지 않는다.** 같은 agent 의 다른 세션이 그 런타임을 쓰고 있을 수 있기 때문이다.
  핸들은 자기 자원만 정리한다.

등록·해제의 전체 규칙은 [`scope-model.md` §2](../../overview/scope-model.md#2-소유권과-소멸--누가-만들고-누가-닫는가)를
따른다.

## 4. 소멸 — `close()` 목록

`OrcaAgentRuntime.close()` 는 멱등이고, MCP 클라이언트 매니저, `workflowRunner`, 그리고 런타임이 **소유한** 실행 환경
제공자를 이 순서로 닫는다. 빌린 제공자는 닫지 않는다. 런타임이 제공자를 소유하는 경우는 런타임별 제공자 함수
(`OrcaAgentRuntimeFactory.withExecutionEnvironmentProviderFactory`)가 돌려준 제공자 하나뿐이고, 그 규칙은
[`tool/execution-environment.md` §4](../tool/execution-environment.md#4-수명과-소유권)가 정본이다. 제공자가 마지막인 것은
`workflowRunner` 가 그 제공자에서 환경을 해석하기 때문이다.

각 항목은 예외를 삼키고 다음으로 넘어간다. 하나가 실패해도 나머지 정리를 막지 않는다.

`close()` 는 `AgentScoped` 구현체를 스캔하지 않고 하드코딩된 목록만 닫는다. 마커는 문서일 뿐이다 — 네이티브 자원을 쥔
agent-scoped 컴포넌트를 새로 더한다면 이 목록에 직접 한 줄을 넣어야 하고, 마커를 붙이는 것만으로는 아무 일도 일어나지
않는다. 자동 fan-out 을 쓰지 않는 이유는 순서다. 닫는 순서가 자원마다 다르고, 스캔은 순서를 표현하지 못한다.

## 5. 생성과 등록

### 5.1 멱등 생성 — `OrcaAgentRuntimeManager`

`OrcaAgentRuntimeManager.getOrCreateRuntime` 은 (agent, discriminator) 당 런타임을 한 번만 만들고 `hookRegistrars` 도 그
안에서 한 번만 등록한다. 여러 번 불러도 같은 인스턴스를 돌려주므로 훅이 중복 등록되지 않는다. 만든 런타임을 등록하는
도중 실패하면 매니저가 그 런타임을 닫는다 — 아직 아무도 그것에 닿을 수 없으므로 달리 닫을 주체가 없다.

해제는 `OrcaAgentRuntimeManager.destroyRuntime` 이 레지스트리에서 빼고 닫는다. 이 호출은 앱 shutdown 이나 명시적 agent
제거 때만 한다. 그 런타임을 쓰는 라이브 세션이 없다는 것은 호출자가 알아야 한다.

이 매니저는 직접 배선하는 호스트를 위한 API 다. bootstrap 조립(`AimonStack`, CLI 의 `AgentSetupFactory` 가 조립하는 스택)은
이것을 쓰지 않고, 생성과 해제를 아래 5.2 의 두 자리가 맡는다.

### 5.2 선언된 런타임과 테넌트 런타임

런타임이 만들어지는 자리는 둘이고, 둘은 같은 `AgentRuntimeProvisioner` 로 조립한다.

| 종류 | 언제 만드는가 | 언제 해제하는가 |
|------|--------------|----------------|
| **선언된 런타임** — 설정이 열거한 agent (discriminator 없는 id, 또는 설정이 discriminator 를 준 id) | 부트스트랩에서 1회. `AimonStackBuilder` 가 선언된 agent 를 돌며 만들고, 호스트가 스택을 시작할 때 등록한다 | 앱 shutdown (스택의 teardown) |
| **테넌트 런타임** — 선언되지 않은 discriminator 를 가진 id | `AgentRuntimeResolver` 가 첫 사용 때 만들고 등록한다 | 리스 0 이고 유휴 TTL 이 지나면 리졸버가 회수한다(레지스트리에서 빼고 닫는다) |

- **agent 는 열거할 수 있고 테넌트는 열거할 수 없다.** agent 는 첫 요청 전에 알려져 있으므로 시작 때 세운다 — 그래야
  MCP 서버 누락이나 해석할 수 없는 도구가 사용자에게 드러나는 오류가 아니라 부트 실패가 된다. 테넌트 집합은 프로세스가
  도는 동안 자라므로 미리 세울 것이 없고, 가능한 시점은 첫 사용뿐이다.
- **discriminator 없는 미등록 id 는 거부한다**(`UnknownAgentRuntimeException`). 즉석에서 만들면 오타가 성공하는 것과
  구분할 수 없고, agent 를 시작 때 세운 이유인 fail-fast 보장을 잃는다. 스택이 선언했지만 아직 등록하지 않은 id 도 리졸버가
  만들지 않는다 — 같은 id 뒤에 런타임이 둘 생기기 때문이다.
- **회수는 시간이 아니라 리스로 판정한다.** 테넌트 런타임을 재시작까지 붙들면 고객 수만큼 파일 시스템·도구 레지스트리·MCP
  연결이 쌓인다. 그러나 실행(턴이든 스케줄 루틴 같은 세션 없는 실행이든)이 쓰는 중에 `close()` 가 돌면 안 되고, 경과 시간만으로는 "한 시간 전에 마지막으로 썼다" 와
  "한 시간째 도는 중이다" 를 구분하지 못한다. 그래서 호출자는 작업 동안 `AgentRuntimeLease` 를 쥐고, 쥔 주체가 없고
  유휴 TTL 이 지난 런타임만 닫힌다. 회수 방식(`AgentRuntimeEviction`)의 기본은 유휴 회수이고, 테넌트가 작고 알려진
  배포는 프로세스 수명 동안 붙들 수 있다.
- **상한에 닿으면 생성을 거부한다.** `max-entries` 에 닿았는데 유휴인 것이 없으면 `AgentRuntimeExhaustedException` 이다.
  상한 초과를 허용하면 마지막에 온 테넌트 하나가 아니라 모든 테넌트가 실패한다.
- **선언된 런타임은 리졸버가 축출하거나 교체하지 않는다.** 그것을 갈아끼우는 것은 재시작이다. 리졸버가 조용히 등록을 빼면
  스케줄링 엔진이 cron 태스크의 런타임을 아무 설명 없이 찾지 못하게 된다.

등록을 `AgentRegistry.register` 콜백이나 agent 정의의 hot-reload 트리거에 결합하지 않는다. 결합하면 런타임이 언제
만들어지는가가 등록 순서에 따라 달라진다.

테넌트 런타임의 프로퍼티와 다중 에이전트 배치는
[`integration/spring-boot-starter.md` §4.11](../integration/spring-boot-starter.md#411-다중-에이전트--n개-에이전트--m개-테넌트)에
있다.

## 6. opener 는 id 형식을 모른다

opener 는 id 형식을 모른다. 세션 라우터(`DefaultSessionRouter`)가 `{agentRef, contextDiscriminator}` 에서 id 를 도출해
넘기고, opener 는 그 id 의 런타임을 받기만 한다. `LiveSessionOpener` 구현은 `open` 마다 런타임을 새로 만들면 안 된다 —
한 세션 레코드는 시간이 지나며 여러 라이브 핸들로 서비스되므로(유휴 축출, 재시작, 노드 이동), open 마다 만든 런타임은 축출마다
하나씩 생기고 아무도 닫지 않는다.

- 직접 배선하는 호스트에서 opener 는 부트스트랩이 등록한 런타임을 레지스트리에서 찾는다.
- 부트스트랩 스택의 opener(`StackLiveSessionOpener`)는 런타임을 만들지 않고 `AgentRuntimeResolver` 에 묻는다. 테넌트
  런타임을 만들 수 있는 것은 리졸버뿐이다. opener 는 받은 리스를 핸들에 묶어, 핸들을 닫을 때 리스를 반납한다 — 턴 사이의
  캐시된 핸들은 타이머에게 유휴로 보이므로, 묶지 않으면 따뜻한 세션이 곧 쓸 런타임을 리졸버가 회수할 수 있다.

opener 가 agent 참조에서 id 를 직접 도출하는 안은 기각한다. opener 구현체마다 prefix · discriminator 결합 규칙을 알아야
하고, discriminator 가 `OpenAttributes` 의 키 관례에 묶여 1급 필드가 되지 못하기 때문이다. `SubmitRequest.contextDiscriminator`
의 검증 규칙은 [`session/routing.md` §13.1](../session/routing.md#131-submitrequest) 에 있다.

런타임이 없는 상황에서도 emit 되는 `RejectedAt` / `InterruptedAt` 이벤트는 sentinel id 상수 `agent:web-rejected` ·
`agent:web-evicted` 를 싣는다.

## 7. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 런타임의 수명 | agent-scoped — `(Agent, discriminator)` 당 하나 | 세션마다 생성 — 런타임에 세션 종속 값이 없고, 세션보다 오래 사는 스케줄이 런타임을 잃는다 |
| id 발급 | 이름과 discriminator 에서 결정론적으로 | 무작위 UUID(`generate()`) — 어떤 해제 규칙을 붙여도 cron 재발화가 런타임을 찾지 못한다 |
| id 의 내부 표현 | 두 필드를 구조적으로 저장, `value()` 는 파생, 검증은 `of()` 한 곳 | 와이어 문자열을 접근자마다 재파싱 — 파싱 실패가 접근자에서 터질 수 있다 |
| discriminator 의 문자 | non-blank, 콜론 금지 | 자유 문자열 — 콜론이 구분자라 `value()` 파싱이 모호해진다 |
| MCP 클라이언트의 수명 | agent-scoped | 세션 단위 — stdio spawn 비용을 세션마다 치르고, 주 사용처에 사용자별 격리 요구가 없다. 필요해지면 라이브 세션 슬롯으로 병존시킨다 |
| `close()` 가 닫을 것을 고르는 방식 | 하드코딩된 목록, 소유한 제공자는 마지막 | `AgentScoped` 구현체 스캔 — 닫는 순서를 표현하지 못한다 |
| 실행 환경 제공자 | 소유한 것만 닫는다 | 늘 닫기 — 여러 런타임이 나눠 쓰는 제공자와 그것이 쥔 백그라운드 셸이 한 런타임의 소멸로 끊긴다 |
| 등록 시점 | 부트스트랩(선언된 런타임) 또는 첫 사용(테넌트 런타임) | `AgentRegistry.register` 콜백·agent 정의 hot-reload 에 결합 — 생성 시점이 등록 순서에 좌우된다 |
| 테넌트 런타임 생성 | `AgentRuntimeResolver` 가 첫 사용 때, 선언된 런타임과 같은 프로비저너로 | 별도 지연 조립 경로 — 기능이 설정에 적힌 agent 에서만 동작하고 테넌트에서는 조용히 빠진다 |
| discriminator 없는 미등록 id | 거부(`UnknownAgentRuntimeException`) | 즉석 생성 — 오타가 성공하고 fail-fast 보장을 잃는다 |
| 테넌트 런타임 회수 | 리스 0 + 유휴 TTL | 경과 시간만으로 축출 — 오래 걸리는 실행을 유휴로 오인한다 / 재시작까지 보유 — 테넌트 수만큼 자원이 샌다 |
| 테넌트 상한 | 닿으면 생성 거부(`AgentRuntimeExhaustedException`) | 상한 초과 허용 — 마지막 테넌트가 아니라 모두가 실패한다 |
| id 도출의 주체 | 세션 라우터, opener 는 받은 id 로 런타임만 얻는다 | opener 가 agent 참조에서 도출 — 구현체마다 결합 규칙을 알아야 하고 discriminator 가 키 관례에 묶인다 |

## 8. 하지 말 것

- `LiveSession.close()` 에서 `AgentRuntime.close()` 나 `destroyRuntime` 을 부르지 않는다. 런타임은 세션이 소유하지 않는다.
- 런타임 소멸에서 `SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor` 나 `ApplicationScoped` 협력자를 닫지 않는다.
- `LiveSessionOpener.open` 마다 런타임을 만들지 않는다.
- 네이티브 자원을 쥔 agent-scoped 컴포넌트를 더하면서 `AgentScoped` 마커만 붙이고 `close()` 목록에 넣지 않는 일을 하지
  않는다. 마커는 아무것도 닫지 않는다.
- 빌린 실행 환경 제공자를 런타임에서 닫지 않는다.
- `AgentRuntimeId` 를 문자열로 조립하지 않는다. `from` / `fromName` / `of` 를 쓴다.
- 테넌트 런타임을 리스 없이 쓰지 않는다. 쥔 주체가 없는 런타임은 유휴 TTL 뒤에 닫힐 수 있다.
- 선언된 런타임을 리졸버로 무효화하거나 교체하려 하지 않는다. 교체는 재시작이다.

## 9. 남은 것

남은 일은 [`agent-execution-open-items.md`](../../backlog/agent-execution-open-items.md) 가 정본이다.

---

## 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 런타임 계약 | [`AgentRuntime.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/AgentRuntime.java) |
| 결정론적 id | [`AgentRuntimeId.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/AgentRuntimeId.java) |
| 런타임 레지스트리 | [`AgentRuntimeRegistry.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/AgentRuntimeRegistry.java) |
| 수명 마커 | [`AgentScoped.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/base/AgentScoped.java), [`ApplicationScoped.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/base/ApplicationScoped.java) |
| `close()` 목록 | [`OrcaAgentRuntime.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntime.java) |
| 제공자 소유 플래그 | [`OrcaAgentRuntimeFactory.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeFactory.java) |
| 멱등 생성 · 해제 | [`OrcaAgentRuntimeManager.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeManager.java) |
| agent 이름 교체 정책 | [`AgentRegistry.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/AgentRegistry.java) |
| 훅 설정 hot-reload | [`HookHotReloadBootstrap.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/config/hook/HookHotReloadBootstrap.java) |
| 선언된 런타임 생성 | [`AimonStackBuilder.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java) |
| 테넌트 런타임 · 리스 · 회수 | [`AgentRuntimeResolver.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/runtime/AgentRuntimeResolver.java), [`AgentRuntimeLease.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/runtime/AgentRuntimeLease.java), [`AgentRuntimeEviction.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/runtime/AgentRuntimeEviction.java) |
| 두 생성 경로의 공통 조립 | [`AgentRuntimeProvisioner.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/runtime/AgentRuntimeProvisioner.java) |
| 스택의 opener | [`StackLiveSessionOpener.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/assemble/StackLiveSessionOpener.java) |
| opener 계약 | [`LiveSessionOpener.java`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/LiveSessionOpener.java) |
| id 도출 · sentinel id | [`DefaultSessionRouter.java`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/DefaultSessionRouter.java) |
| `contextDiscriminator` | [`SubmitRequest.java`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/SubmitRequest.java) |

## 관련 문서

- [`scope-model.md`](../../overview/scope-model.md) — 수명 4계층, 등록·해제 규칙, 닫는 순서
- [`tool/execution-environment.md`](../tool/execution-environment.md) — 실행 환경 제공자의 소유
- [`session/routing.md`](../session/routing.md) — 세션 라우터와 `SubmitRequest`
- [`session/session-model.md`](../session/session-model.md) — 세션과 라이브 세션의 분할
- [`integration/spring-boot-starter.md`](../integration/spring-boot-starter.md) — 테넌트 런타임의 프로퍼티와 다중 에이전트
- [`embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md) — 호스트 배선과 프로퍼티
