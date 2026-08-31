# MCP Tool — 외부 서버의 도구를 로컬 도구와 구분되지 않게 만든다

> Status: **IMPLEMENTED (전송은 STDIO 하나)** — `at.aimon.core.mcp`(main 20파일 / test 15) +
> `at.aimon.core.hook.rewake.mcp`(main 3 / test 3). `McpServerConfig.McpTransportType` 은 값 셋을
> 선언하지만 `SSE` / `STREAMABLE_HTTP` 는 팩토리에서 `UnsupportedOperationException` 이다.
> 인바운드 알림 경로는 소비자(`McpNotificationToRewakeBridge`)만 있고 **생산자가 없다**. 남은 것은 §9.

MCP(Model Context Protocol) 서버가 제공하는 도구를 AIMON 에이전트가 쓸 수 있게 하는 통합 계층이다.
설계의 전부가 문장 하나에서 나온다 — **`McpTool` 이 `Tool` 인터페이스를 구현한다.** 그래서
`OrcaAgentExecutor` · `ToolRegistry` · 승인 게이트 · 부작용 상한 어느 것도 원격 도구와 로컬 도구를
구별하지 못하고, 구별할 필요도 없다. 이 문서는 그 등가성을 지키기 위해 어디에 무엇을 두었는지를 적는다.

---

## 1. 개요

### 1.1 MCP Tool vs 로컬 Tool

| 구분 | 로컬 Tool | MCP Tool |
|------|----------|----------|
| 정의 | 코드에 하드코딩 | 서버에서 `tools/list` 로 동적 조회 |
| 실행 | 로컬 메서드 호출 | JSON-RPC `tools/call` 원격 요청 |
| 수명 | 애플리케이션과 동일 | **agent-scoped** — `AgentRuntime` 과 함께 생성/소멸 |
| 이름 | `Read`, `Bash` | `mcp__<server>__<tool>` |
| 부작용 선언 | 클래스가 직접 override | 서버의 신고 + 그 서버에 대한 신뢰로 **해소**(§4) |

수명 칸이 핵심이다. MCP 연결은 프로세스(STDIO 의 경우 자식 프로세스)를 붙잡고 있으므로
[`scope-model.md`](../../overview/scope-model.md) 의 소유권 규칙이 그대로 적용된다 — 만든 쪽이 닫고,
`AgentRuntime.close()` 의 하드코딩된 목록에 들어가야 닫힌다(§5).

### 1.2 설계 목표

1. **투명한 통합** — 실행기 코드를 한 줄도 바꾸지 않고 MCP 도구를 쓴다.
2. **전송 계층 분리** — `McpTransport` 인터페이스 뒤에 stdio / HTTP 계열을 둔다.
3. **Fail-Safe** — `execute()` 는 예외를 던지지 않고 `ToolResult.error()` 를 돌려준다.
4. **수명 일치** — MCP 연결의 생성/종료가 `AgentRuntime` 과 정확히 같다.
5. **Thread-Safety** — 여러 도구가 공유하는 `McpClient` / `McpTransport` 는 thread-safe.
6. **신뢰를 한 곳에서 해소** — 서버가 자기 도구에 대해 하는 주장은 §4 한 지점에서만 믿거나 무시된다.

---

## 2. 아키텍처

### 2.1 4계층

```
┌─────────────────────────────────────────────────────┐
│                  OrcaAgentExecutor                  │
│              (MCP 를 전혀 알지 못한다)                │
└────────────────────┬────────────────────────────────┘
                     │
              ┌──────▼───────┐
              │ ToolRegistry │
              └──┬────────┬──┘
                 │        │
        ┌────────▼─┐  ┌───▼────────┐
        │ ReadTool │  │  McpTool   │  ← Tool 구현. 이름만 mcp__ 접두
        │ BashTool │  │  (원격)     │
        │ ...      │  └───┬────────┘
        └──────────┘      │
                     ┌────▼────────┐
                     │  McpClient  │  ← 프로토콜: initialize / listTools / callTool
                     └────┬────────┘
                          │
                     ┌────▼────────┐
                     │ McpTransport│  ← 전송: JSON-RPC 프레임 송수신
                     └────┬────────┘
                          │
                  ┌───────▼────────┐
                  │ StdioMcpTransport │  ← 유일한 구현 (ProcessBuilder)
                  └───────┬────────┘
                          │
                  ┌───────▼────────┐
                  │  MCP 서버 프로세스 │
                  └────────────────┘
```

계층을 넷으로 자른 기준은 **바뀌는 축이 서로 다르다**는 것이다. 전송은 배포 형태(로컬 프로세스 / 원격
HTTP)에 따라 바뀌고, 프로토콜은 MCP 스펙 버전에 따라 바뀌며, 어댑터는 AIMON 의 `Tool` 계약에 따라
바뀐다. 한 축이 움직여도 나머지가 재컴파일되지 않는다.

### 2.2 왜 별도 `aimon-mcp` 모듈이 아닌가

`aimon-llm-openai` 나 `aimon-filesystem-s3` 는 **벤더 SDK 의존성**을 격리하려고 분리된 모듈이다. MCP 에는
격리할 벤더가 없다.

- **추가 의존성이 없다.** `StdioMcpTransport` 는 `ProcessBuilder` + Jackson 뿐이고 둘 다 이미 코어에 있다.
  HTTP 전송이 생기면 그때 판단이 달라질 수 있지만, 지금은 늘어나는 의존성이 0이다.
- **벤더 중립 표준이다.** 대체 구현이 필요한 종류의 추상화가 아니다 — `LlmClient` 와 다른 점이 이것이다.
- **도구 시스템의 자연스러운 확장이다.** `McpTool` 이 `AbstractTool` 을 상속하므로 코어 밖으로 나갈 수 없다.

### 2.3 패키지 배치

```
at.aimon.core.mcp/
├── McpClient.java                 # 프로토콜 인터페이스
├── DefaultMcpClient.java          # 기본 구현
├── McpClientFactory.java          # 생성 인터페이스 — ApplicationScoped
├── DefaultMcpClientFactory.java   # 전송 타입 → transport 분기 + initialize
├── McpClientManager.java          # 여러 클라이언트의 수명 관리 — AgentScoped
├── McpTool.java                   # Tool 어댑터
├── McpToolSchema.java             # tools/list 응답 1건 (불변)
├── McpToolAnnotations.java        # 서버가 신고한 4개 힌트 (불변)
├── McpToolTraits.java             # 신고 + 신뢰 → AIMON 선언 (불변, §4)
├── McpCallResult.java             # tools/call 결과 (불변)
├── McpServerCapabilities.java     # initialize 응답 (불변)
├── McpServerConfig.java           # 서버 설정 (불변) + McpTransportType · AnnotationTrust
├── McpServerConfigProvider.java   # 설정 소스 추상화 — ApplicationScoped
├── McpNotificationListener.java   # 인바운드 알림 SPI (§7.2)
├── exception/{McpException, McpTransportException, McpInitializeException}.java
├── transport/{McpTransport, StdioMcpTransport}.java
└── orca/OrcaMcpToolProvider.java  # OrcaToolProvider 구현
```

`ext` 네임스페이스는 폐기되었다 — 도구는 `at.aimon.core.tools.*`, MCP 는 `at.aimon.core.mcp.*` 다
(ArchUnit `extPackageIsDecommissioned` 가 재도입을 막는다). `orca/` 만 따로 있는 이유는 그것이
**Orca 실행기 전용 등록 어댑터**이기 때문이다 — 나머지는 실행기와 무관하다.

---

## 3. 어댑터 — `McpTool`

`AbstractTool` 을 상속하고 세 가지만 한다.

1. **이름을 짓는다** — `mcp__<serverName>__<toolName>`. 서버 이름이 도구 이름의 일부이므로 서버 이름
   유일성이 도구 이름 충돌 방지와 같은 문제가 된다(§5.4).
2. **선언을 대신한다** — `getSideEffectLevel()` / `getDestructiveBehavior()` 를 `McpToolTraits` 에 위임한다.
3. **호출을 위임한다** — `mcpClient.callTool(name, input.toMap())`.

`execute()` 의 실패 경로는 셋이고 **전부 `ToolResult.error()`** 로 나간다: 연결이 끊긴 경우
(`isConnected()` 가 false), 서버가 `isError` 결과를 준 경우, 그리고 그 밖의 모든 예외. 마지막 catch 가
`Exception` 인 것은 게으름이 아니라 계약이다 — 도구는 예외를 던지지 않는다
([tool-development-guide](../../features/tool/tool-development-guide.md)).

입력 스키마는 서버가 준 것을 **그대로** 광고한다. 그래서 `BuiltInToolSchemaArchitectureTest` 의
`additionalProperties: false` 강제 대상에서 MCP 도구는 빠져 있다 — 그 스키마는 우리 것이 아니다.
스키마 검증기도 같은 원칙으로 동작한다: **키가 있으면 강제하고 없으면 관대**하다.

---

## 4. 신뢰 경계 — 서버의 자기 신고를 어디까지 믿는가

MCP 스펙의 tool annotation(`readOnlyHint` · `destructiveHint` · `idempotentHint` · `openWorldHint`)은
**도구가 자기 자신에 대해 하는 주장**이고, 그 주장을 중계하는 것은 AIMON 이 통제하지 않는 서버다.
그런데 AIMON 에서 `SideEffectLevel.READ_ONLY` 는 장식이 아니다 — 부작용 상한과 승인 게이트가 그것을
읽는다. `readOnlyHint: true` 를 거짓으로 신고한 도구는 **묻지 않고 실행된다**.

그래서 신뢰는 값 하나로 해소되고, 그 값은 **서버 단위 설정**이다.

```
McpToolAnnotations (서버가 신고한 것)  +  AnnotationTrust (그 서버를 얼마나 믿는가)
                          │
                          ▼  McpToolTraits.resolve(...)
        SideEffectLevel + DestructiveBehavior  ← 여기부터는 로컬 도구와 구분 불가
```

| `AnnotationTrust` | 결과 |
|-------------------|------|
| **`IGNORE`** (기본값) | 애노테이션을 파싱해서 들고만 있고 쓰지 않는다. 모든 도구가 `MUTATING` + `DESTRUCTIVE` — 애노테이션을 읽기 전과 정확히 같다 |
| `TRUST` | `readOnlyHint` 가 레벨이 되고 `destructiveHint` 가 파괴성이 된다 |

세 가지가 이 설계의 요점이다.

- **기본값이 `IGNORE` 인 것은 무동작을 보장하기 위해서다.** 기능이 추가되었지만 기존 배포의 동작은
  한 비트도 바뀌지 않는다. 신뢰는 켜는 것이지 꺼야 하는 것이 아니다.
- **신뢰는 서버 단위이지 정책 인터페이스가 아니다.** 결정할 것이 하나뿐이고 그 하나가 서버마다 다르므로,
  `McpServerConfig` 안에 사는 것이 맞다. 신뢰는 서버를 가로질러 전이되지 않는다.
- **모순은 관대한 쪽으로 해소한다.** 서버가 `readOnlyHint: true` 와 `destructiveHint: true` 를 함께 보내면
  `READ_ONLY` + `NON_DESTRUCTIVE` 가 된다. 소비자는 `MUTATING` 아래에서 두 번째 축을 읽지 않으므로
  모순을 저장해 봐야 `toString()` 과 실제 동작이 어긋날 뿐이고, `TRUST` 를 켠 시점에 이미 둘 중 더 무거운
  주장을 믿기로 한 것이기 때문이다.

해소는 `McpClientManager.registerAllTools()` 에서 **도구당 한 번** 일어난다. 그 뒤로는 아무도 이것이
원격 도구인지 묻지 않는다 — 그것이 §1 의 등가성이 실제로 성립하는 지점이다.

---

## 5. 라이프사이클 — 누가 만들고 누가 닫는가

### 5.1 스코프 분류

| 컴포넌트 | 스코프 | 마커 | 이유 |
|----------|--------|------|------|
| `McpClientFactory` | Application | `extends ApplicationScoped` | stateless, 모든 런타임이 재사용 |
| `McpServerConfigProvider` | Application | `extends ApplicationScoped` | 설정 소스, 모든 런타임이 재사용 |
| `McpClientManager` | Agent | `implements AgentScoped` | 서버 연결이 `AgentRuntime` 과 함께 나고 죽는다 |
| `McpClient` / `McpTransport` | Agent | — | 매니저가 소유. 개별 마커 없음 |

IMPORTANT: **마커는 문서일 뿐 자동 소멸이 아니다.** `OrcaAgentRuntime.close()` 는 `AgentScoped` 구현체를
스캔하지 않고 **하드코딩된 목록**만 닫는다. `mcpClientManager` 가 그 목록의 첫 항목이고, 그래서 닫힌다 —
마커를 달았기 때문이 아니다. 전체 규칙은 [`scope-model.md` §2](../../overview/scope-model.md).

같은 이유로 **`ToolRegistry` / `HookRegistry` 는 `AgentScoped` 를 구현하지 않는다.** 둘 다 agent-scoped
이지만 닫을 자원이 없다. 마커는 "수명이 무엇인가"가 아니라 "닫아야 할 것이 있는가"를 말한다.

### 5.2 `McpClientManager` 를 만드는 곳

`OrcaProviderDependencies` 는 불변이다(final 클래스, setter 없음, 빌더로만 생성). 따라서 provider 안에서
매니저를 만들어 dependencies 에 밀어 넣는 것은 불가능하다.

해결은 **`OrcaAgentRuntimeFactory` 가 만들어 양쪽에 주입**하는 것이다.

```java
// OrcaAgentRuntimeFactory.create(..., mcpClientFactory, mcpServerConfigProvider)
final McpClientManager mcpClientManager = new McpClientManager(mcpClientFactory);   // 1. 만들고
allProviders.add(new OrcaMcpToolProvider(mcpServerConfigProvider, mcpClientManager)); // 2. provider 에
// 3. 런타임에도 주입 → OrcaAgentRuntime.close() 가 닫는다
```

MCP 오버로드는 기존 `create(...)` 를 바꾸지 않고 **추가**된 것이고, 런타임 생성이 실패하면 팩토리가
매니저를 직접 닫는다 — 아직 아무도 소유하지 않은 상태에서 새는 것을 막기 위해서다.

`McpClientManager` 는 nullable 필드다. MCP 를 설정하지 않은 런타임에는 아예 없고,
`getMcpClientManager()` 는 `Optional` 을 돌려준다.

### 5.3 병렬 초기화와 그 바운드

`createClients(List<McpServerConfig>)` 는 서버들을 **동시에** 붙인다. 서버 3개가 각 2초면 순차는 6초,
병렬은 약 2초다. 풀은 `Executors.newCachedThreadPool()` — 서버 수만큼만 늘고 유휴 스레드는 60초 뒤
반납된다.

기다림에는 **하나의 데드라인**이 걸린다. 그 값은

```
max(설정된 requestTimeout 들)  +  STARTUP_SPAWN_ALLOWANCE(30초)
```

이며 **합이 아니다** — 서버를 추가해도 길어지지 않는다. 30초는 아무것도 바운드하지 않는 유일한 구간,
즉 서버 프로세스를 띄우는 시간을 덮는다(그 뒤의 `initialize` 요청은 전송이 스스로 바운드한다).

이 값은 **운영자가 튜닝하는 시작 예산이 아니다.** 건강한 서버라면 절대 걸리지 않을 만큼 느슨하게
잡혀 있고, 스스로를 바운드하지 못하는 전송이 에이전트 기동을 무한정 매달지 못하게 하는 것만이 목적이다.
진짜 예산(정책으로 강제되는 더 낮은 상한)을 둘 것인가는 **결정되지 않았다**(§9.2).

데드라인에서 포기한 서버는 취소가 실제로 먹기 전까지 살아 있을 수 있다. 그 창에서 늦게 성공한
클라이언트는 `discardRacedClients` 가 제거하고 닫는다 — 호출자가 "실패했다"고 통보받은 서버의 도구가
등록되어 있으면 안 되기 때문이다. **현재 트리의 어떤 전송도 여기에 도달하지 않는다**(STDIO 는 요청별
데드라인을 스스로 지킨다). 방어이지 동작 중인 경로가 아니다.

### 5.4 부분 실패와 이름 유일성

- **부분 실패는 전체를 중단시키지 않는다.** 실패한 서버 이름 목록이 반환되고, 성공한 서버의 도구만
  등록된다. 에이전트는 남은 도구로 계속 동작한다.
- **서버 이름은 세 지점에서 검사된다** — 형식은 `McpServerConfig` 생성자에서
  (`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`), 설정 목록 안의 중복은 `OrcaMcpToolProvider` 가 fail-fast 로,
  등록 시점의 중복은 `McpClientManager` 가 최종 방어로. 셋이 중복인 것은 의도적이다 — 앞의 둘은 더 이른
  실패를, 마지막은 어떤 경로로 들어와도 뚫리지 않음을 보장한다.
- `createClients` 안에서 중복 이름은 **덮어쓰지 않고 실패로 보고**된다. 이미 그 이름을 차지한 클라이언트가
  이긴다.

---

## 6. 등록 흐름

```
AgentRuntime 생성
  └─ OrcaAgentRuntimeFactory.create(..., mcpClientFactory, mcpServerConfigProvider)
       ├─ new McpClientManager(mcpClientFactory)
       └─ OrcaMcpToolProvider.registerTools(registry, ctx)
            ├─ configProvider.getConfigs()            ← 비어 있으면 조용히 반환
            ├─ validateUniqueServerNames(configs)     ← fail-fast
            ├─ manager.createClients(configs)         ← 병렬. 실패 목록 반환
            │    └─ 서버별: transport 생성 → initialize 핸드셰이크 → capability 교환
            └─ manager.registerAllTools(registry)
                 └─ 서버별: listTools() → 도구별 McpToolTraits.resolve(...) → McpTool 등록

...에이전트 실행 (여러 세션에 걸쳐 같은 런타임을 공유)...

AgentRuntime.close()
  └─ mcpClientManager.close() → closeAll() → 서버별 McpClient.close() → 프로세스 종료
```

종료 시 개별 `close()` 실패는 로그만 남기고 나머지 서버 종료를 계속한다.

---

## 7. MCP 를 소비하는 다른 자리

도구 등록만이 MCP 의 소비처가 아니다.

### 7.1 선언적 훅 — `McpToolAction`

스킬의 선언적 훅이 `McpToolAction` 으로 MCP 도구를 직접 호출할 수 있다. `McpActionExecutor` 가 호출
시점에 `McpClientManager` 로 서버를 찾고, args 템플릿을 렌더링한 뒤 `callTool` 한다. 결과는 HTTP 액션과
**같은 JSON 계약**으로 `HookResult` 에 매핑된다 — `decision` 필드가 있으면 `allow`/`deny`/`defer` 를
존중하고, 없으면 부작용 전용 호출로 보고 `success()` 를 돌려준다.

알 수 없는 서버·전송 실패·`isError` 결과는 **전부 WARN + `success()`** 로 degrade 한다. 선언적 훅은
fail-soft 다.

### 7.2 rewake 브리지 — 소비자만 있고 생산자가 없다

`McpNotificationListener` 는 서버가 밀어 보내는 JSON-RPC 알림(`id` 없는 프레임)을 받기 위한 중립 SPI 다.
`at.aimon.core.hook.rewake.mcp` 의 `McpNotificationToRewakeBridge` 가 그것을 구현해서
`McpNotificationMapper` 로 번역한 뒤 `ExternalEventResolver` 에 넘긴다.

rewake 로 **직결하지 않고 중립 SPI 를 둔 이유**는 알림 표면이 rewake 보다 넓기 때문이다 — 로그 전달,
capability 변경 공지, 도구 목록 무효화가 전부 같은 프레임 모양을 공유하면서 서로 다른 소비자로 흐른다.
전송을 그중 하나에 결합시키지 않으려는 것이다.

IMPORTANT: **그런데 이 리스너를 호출하는 코드가 없다.** `StdioMcpTransport` 의 읽기 루프는 인바운드
알림을 **건너뛴다**(응답 상관에만 관심이 있다). `McpTransport` 에는 리스너를 등록하는 메서드가 없고,
`McpClientManager` 에도 `setNotificationListener(...)` 가 없다 — 브리지 javadoc 이 그 이름을 언급하지만
그런 메서드는 존재하지 않는다. 브리지와 매퍼는 구현되고 테스트되어 있으나 **연결되어 있지 않다**(§9.1).

---

## 8. 설계 결정 사항

### D1. MCP 는 `aimon-core` 안에 둔다

별도 모듈로 뺄 근거인 "벤더 SDK 격리"가 성립하지 않는다(§2.2). 원격 전송이 실제로 구현되어 HTTP 클라이언트
의존성이 붙는 시점에 재검토할 여지는 있지만, STDIO 만 있는 지금은 추가 의존성이 0이다.

### D2. `McpTool` 은 `AbstractTool` 을 상속한다 — 별도 계층을 만들지 않는다

`Tool` 을 직접 구현하고 `execute()` 를 감싸는 자체 안전망을 두는 선택지가 있었으나, 그러면
"예외를 던지지 않는다"는 계약이 이 클래스에서만 다른 방식으로 지켜진다. `AbstractTool` 을 쓰면 등가성이
상속 관계로 드러난다.

### D3. 서버 애노테이션 신뢰는 **서버 단위 설정**이지 정책 인터페이스가 아니다

결정할 것이 하나뿐이고 그 하나가 서버마다 다르다. 정책 인터페이스로 만들면 구현체가 결국
"서버 이름 → 불리언" 맵이 되는데, 그것이 `McpServerConfig` 의 필드다.

### D4. 기본값은 `IGNORE` — 기능 추가가 기존 동작을 바꾸지 않는다

`TRUST` 를 기본으로 두면 업그레이드만으로 승인 게이트가 조용히 느슨해진다. 신뢰는 명시적으로 켜는 것이다.

### D5. `READ_ONLY` 와 `destructiveHint: true` 의 모순은 관대한 쪽으로 해소한다

§4 참조. 소비자가 읽지 않는 축에 모순을 저장해 두면 표시와 동작이 어긋날 뿐이다.

### D6. `McpClientManager` 는 팩토리가 만들고 런타임이 닫는다

`OrcaProviderDependencies` 가 불변이라 provider 가 만들 수 없다(§5.2). 소유권이 갈리지 않도록
**만드는 곳과 닫는 곳을 각각 하나로** 고정했다.

### D7. 병렬 초기화의 바운드는 합이 아니라 최댓값 + 상수다

서버 수에 비례해 늘어나는 바운드는 서버를 늘릴수록 무의미해진다. 그리고 이 값은 **정책이 아니라
안전망**이다 — 운영자용 시작 예산은 별도 결정 사항으로 남겼다(§9.2).

### D8. 부분 실패는 기동을 막지 않는다

MCP 서버 하나가 죽었다고 에이전트 전체가 뜨지 못하면, 선택적 통합이 필수 의존성이 된다.

### D9. 인바운드 알림은 rewake 에 직결하지 않고 중립 리스너를 거친다

§7.2 참조. 알림 표면이 rewake 보다 넓다.

### D10. 도구 목록은 런타임 생성 시 한 번만 조회한다

`notifications/tools/list_changed` 구독도, 주기적 재조회도 하지 않는다. 도구 목록이 실행 중에 바뀌면
`ToolRegistry` 스냅샷과 LLM 이 이미 본 도구 정의가 어긋나는데, 그 어긋남을 턴 중간에 처리하는 방법이
정해져 있지 않다. 재조회는 인바운드 알림 경로가 실제로 연결된 뒤의 문제다(§9.1).

---

## 9. 남은 것 · 하지 말 것

### 9.1 아직 코드가 없는 것

| 항목 | 현재 | 필요한 것 |
|------|------|----------|
| **원격 전송** | `McpTransportType` 에 `SSE` / `STREAMABLE_HTTP` 가 선언되어 있지만 `DefaultMcpClientFactory.createTransport` 가 `UnsupportedOperationException` 을 던진다 | `StreamableHttpMcpTransport` 를 먼저 구현한다. MCP 2025-03-26 에서 SSE 는 deprecated 이므로 SSE 를 새로 만들 이유는 하위 호환뿐이다 |
| **인바운드 알림 배선** | 리스너 SPI 와 rewake 브리지는 있으나 **호출하는 코드가 없다**. `StdioMcpTransport` 는 인바운드 알림을 건너뛴다 | `McpTransport` 에 리스너 등록 메서드, 읽기 루프에서 `id` 없는 프레임을 리스너로 분기, `McpClientManager` 에 전달 경로 |
| **재연결** | 연결이 끊기면 `isConnected()` 가 false 가 되고 이후 모든 호출이 `ToolResult.error()` 다. 복구 없음 | 재연결 정책을 `DefaultMcpClient` 에 둘지 매니저에 둘지가 먼저 결정되어야 한다 |
| **도구 목록 갱신** | 런타임 생성 시 1회 조회 (D10) | 인바운드 알림이 먼저다 |
| **Resources / Prompts** | `McpServerCapabilities` 가 `supportsResources` / `supportsPrompts` 를 파싱해 두지만 아무도 읽지 않는다 | `McpResourceClient` / `McpPromptClient` 를 별도 인터페이스로 — `McpClient` 를 키우지 말 것 |

### 9.2 결정이 필요한 것

- **시작 예산.** §5.3 의 바운드는 안전망이지 정책이 아니다. 운영자가 "MCP 초기화에 N초 이상 쓰지 말 것"을
  강제할 수 있어야 하는가는 결정되지 않았다. 결정한다면 그것은 새 설정 값이지 `STARTUP_SPAWN_ALLOWANCE`
  의 재해석이 아니다.
- **`AnnotationTrust` 의 중간 단계.** 지금은 `IGNORE` / `TRUST` 둘뿐이다. "읽기 전용 주장만 믿는다" 같은
  중간값이 필요한지는 실사용 사례가 나오기 전까지 미룬다.

### 9.3 하지 말 것

- **`McpClient` 에 Resources / Prompts 메서드를 추가하지 말 것.** ISP 위반이고, `McpTool` 은 그중 무엇도
  쓰지 않는다. 별도 인터페이스로 나눈다.
- **`McpClientManager` 를 application-scoped 로 올리지 말 것.** MCP 연결은 자식 프로세스를 붙잡고 있고,
  런타임마다 다른 서버 목록을 가질 수 있다.
- **`AgentScoped` 마커를 달았다고 자동으로 닫힌다고 가정하지 말 것.** `OrcaAgentRuntime.close()` 의
  하드코딩된 목록에 직접 넣어야 한다(§5.1).
- **`McpTool` 의 입력 스키마에 `additionalProperties: false` 를 주입하지 말 것.** 그 스키마는 서버가
  소유한다. 엄격함을 켜는 것은 스키마 소유자의 선언이다.
- **애노테이션 해소를 `registerAllTools` 밖으로 옮기지 말 것.** 해소 지점이 하나이기 때문에 그 뒤로는
  아무도 원격/로컬을 구분하지 않는다는 §1 의 성질이 유지된다.

---

## 부록. 참조 파일 지도

| 파일 | 무엇을 보나 |
|------|------------|
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/McpTool.java` | 어댑터. 이름 포맷, 3중 실패 경로 |
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/McpToolTraits.java` | 신뢰 해소. `resolve()` 가 §4의 전부 |
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/McpToolAnnotations.java` | 서버가 신고하는 4개 힌트와 그 MCP 기본값 |
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/McpServerConfig.java` | 이름 패턴, 전송 타입 enum, `AnnotationTrust` enum |
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/McpClientManager.java` | 병렬 초기화, 데드라인 계산, 레이스 정리, 도구 등록 |
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/DefaultMcpClientFactory.java` | 전송 분기. 미구현 전송이 던지는 지점 |
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/DefaultMcpClient.java` | initialize 핸드셰이크, `tools/list`, `tools/call` |
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/transport/StdioMcpTransport.java` | JSON-RPC 프레이밍, 요청 상관, 인바운드 알림을 건너뛰는 곳 |
| `modules/aimon-core/src/main/java/at/aimon/core/mcp/orca/OrcaMcpToolProvider.java` | 등록 흐름 전체 |
| `modules/aimon-core/src/main/java/at/aimon/core/hook/rewake/mcp/McpNotificationToRewakeBridge.java` | 생산자가 없는 소비자 |
| `modules/aimon-core/src/main/java/at/aimon/core/skill/hook/declarative/McpActionExecutor.java` | 선언적 훅에서의 MCP 호출 |
| `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeFactory.java` | 매니저 생성과 provider 주입 |
| `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntime.java` | `close()` 의 하드코딩 목록 |
| `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/spec/ToolSpec.java` | MCP 를 도구 스펙의 일부로 두는 이유 |
| `modules/aimon-cli/src/main/java/at/aimon/cli/config/McpConfig.java` | YAML `mcp.servers` 바인딩 |

## 관련 문서

- [Tool 개발 가이드](../../features/tool/tool-development-guide.md)
- [스코프 모델](../../overview/scope-model.md)
- [SOLID 원칙](../../project/solid-principles.md)
- [Spring Boot Starter](spring-boot-starter.md)
