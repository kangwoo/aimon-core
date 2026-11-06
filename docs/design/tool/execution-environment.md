# 실행 환경 — 도구가 쓰는 파일 시스템과 셸을 실행마다 고른다

> **Status**: IMPLEMENTED — `aimon-core`(`at.aimon.core.environment`), `aimon-bootstrap`, `aimon-spring-boot-starter`, `aimon-cli`. 열린 항목의 정본은 [등록부](../../backlog/execution-environment-open-items.md)다.

## 1. 목표와 비목표

이 SPI 는 aimon-sandbox 가 구현하는 전제이지만, 샌드박스가 없어도 실행마다 환경을 고르고 제어 저장소를 가르는 문제를
함께 푼다.

**목표**

- 도구가 보는 파일 시스템과 셸을 **실행(execution)마다** 정할 수 있다
- 파일 도구와 셸은 한 환경에서 나오며, **같은 파일 시스템을 본다**
- 모델이 만지는 **작업 환경**과 프레임워크가 쓰는 **제어 저장소**를 타입으로 분리한다
- 프롬프트가 묘사하는 환경은 명령이 실제로 도는 환경이다
- 워크플로 격리가 레지스트리 복제 없이 환경 파생으로 된다

**비목표**

- 샌드박스 자체의 구현. 코어는 SPI 와 로컬 구현만 갖는다
- 셸 경로를 막는 파일 권한 모델. 모델이 셸을 가지면 파일 도구의 경로 제한은 우회된다. 경계는 환경 자체다. 로컬이면
  운영자 책임이고 샌드박스면 컨테이너다. 에이전트와 환경 안 정책 훅 사이의 권한 경계가 없다는 사실은
  [EE-113](../../backlog/execution-environment-open-items.md)으로 열려 있다

**이 문서 밖의 정본**

| 주제 | 문서 |
|---|---|
| 배치 입력(정의 속성, 실행 속성, 워크플로 단계 속성) | [배치 입력](execution-environment-placement.md) |
| 스테이징, 스킬 소스 | [스테이징](execution-environment-staging.md) |
| 제어 저장소, `PathRule`, `FileStamp`, `ContentSearch`, artifact 복사 | [파일 규칙](execution-environment-files.md) |
| 격리 브랜치, `isolatedFrom`, `WorktreeMerge` | [격리 브랜치](execution-environment-isolation.md) |
| 셸 SPI, 취소, 프로세스 트리 종료 | [셸 계약](shell-contract.md) |
| 백그라운드 명령 | [백그라운드 명령](background-commands.md) |
| 훅이 도는 자리, 판단 못 한 가드, 변조 | [훅 환경](../hook/hook-environment.md), [fail-closed](../hook/hook-fail-closed.md), [변조 경계](../hook/hook-tamper-boundary.md) |
| 컴포넌트 수명 일반 규칙 | [`scope-model.md`](../../overview/scope-model.md) |

## 2. 설계 결정

| 쟁점 | 결정 | 이유 | 기각한 대안 |
|---|---|---|---|
| 도구가 파일 시스템과 셸을 어디서 얻는가 | `execute()` 마다 `ToolContextKeys.EXECUTION_ENVIRONMENT` 에서 꺼낸다 | 도구가 생성자로 쥐면 agent-scoped 레지스트리에 한 번 등록되므로 같은 에이전트의 두 세션이 서로 다른 작업 디렉터리나 격리 환경을 쓸 수 없다 | 생성자 주입 + 실행마다 레지스트리 복제. 도구 N개를 실행마다 만들고 도구 변형마다 분기를 다시 적는다. 브랜치마다 복제해도 `Bash` 는 베이스 셸에 묶여 격리가 파일 도구에만 걸린다 |
| 환경을 누가 정하는가 | 실행기가 실행마다 `ExecutionEnvironmentProvider` 에게 묻고 결과를 write-once 키에 넣는다 | 명시적 제공자 하나가 답하면 실패의 모양이 하나다 | `ToolContextEnricher` 가 키를 덮어쓴다. enricher 순서에 정확성이 걸리고, 덮어쓰는 enricher 가 없거나 실패하면 호스트 환경이 남는다(열린 쪽 실패) |
| 환경을 어떻게 나르는가 | `ToolContext` 키 | 값의 출처가 호출 그래프에 보인다 | `ThreadLocal`. 병렬 도구 디스패치와 백그라운드 명령이 스레드를 바꾼다. 경로 접두어로 VFS 를 고르는 합성 라우터. 파일 도구만 라우팅되고 셸은 따라오지 않아 "같은 파일 시스템을 본다" 를 깨는 쪽으로 쓰기 쉽다 |
| 포크가 제공자를 얻는 길 | 실행기가 `EXECUTION_ENVIRONMENT_PROVIDER` 를 함께 싣고, `SubagentLaunchContext` 를 만드는 쪽이 두 키를 호출 컨텍스트에서 복사한다 | 주체, 신호, 호출 세션이 이미 같은 길로 간다 | `OrcaToolProviderContext` 에 실어 도구 생성자로 전달. 등록 시점에 환경의 출처를 쥐는 통로가 다시 생긴다. `DefaultSubagentExecutor` 에 보관. 런타임마다 다른 제공자를 담지 못한다 |
| write-once 를 어떻게 강제하는가 | 이름을 `ToolContextKey` 의 정적 집합에 두고 내장 이름은 상수로 먼저 넣는다. `put` · `putAll` 이 한 검사를 지난다 | 컨텍스트를 `builder().putAll(ctx.getContext())` 로 복사하는 곳이 있다. 이름 기준이면 복사가 첫 쓰기이고 그 뒤 쓰기는 던진다 | 빌더마다 쓴 키를 기억한다. 복사할 때마다 잃는다 |
| 환경의 수명 | `ExecutionEnvironment` 는 `Closeable` 이 아니다. 뒤의 자원은 제공자가 소유한다 | 환경은 실행마다 만들어지는 뷰다. 실행이 끝났다고 셸을 닫으면 같은 셸을 쓰는 다른 실행이 끊긴다 | `Closeable` 로 둔다. 공유 자원을 실행마다 만들어야 한다 |
| 제공자의 수명 | 부트스트랩은 스택당 제공자 하나, 런타임별 정리는 `RuntimeBinding` | 제공자를 런타임마다 두면 축출 때 셸이 닫혀 띄워 둔 명령이 끊기거나 작업 목록이 사라진다. 소유가 갈리는 기준은 "어느 API 로 넘겼는가" 하나다 | `factory(Function)` + 범용 라우터. 축출 때 자식을 닫으면 닫힘이 실제 동작인 셸에서 명령이 끊기고, 닫지 않으면 테넌트마다 샌다. `LocalExecutionEnvironmentProvider` 안에 슬롯을 둔다. 합성이 주지 못하는 동작을 하나도 주지 않으면서 소유·빌림·스윕·되감기 갈래를 다시 쓴다 |
| 축출 통지의 모양 | id 콜백이 아니라 핸들 | 같은 `AgentRuntimeId` 의 런타임 둘이 잠시 함께 살 수 있어, id 로 통지하면 옛 런타임의 늦은 close 가 새 런타임의 몫을 정리한다 | `onRuntimeEvicted(AgentRuntimeId)` |
| 누가 바인딩하는가 | 런타임을 만드는 어셈블리 | 부트스트랩은 제어 저장소 때문에 런타임을 만들기 전에 슬롯이 필요하다 | 런타임이 스스로 바인딩한다. 훅이 두 군데가 되고 런타임의 닫기 목록이 는다. 바인딩 대신 도는 명령이 없을 때까지 정리를 미룬다. 로컬은 미룰 필요가 없고 원격 제공자는 자기 셸이 도는 명령을 안다 |
| 런타임별 제공자 함수가 돌려준 제공자 | 런타임이 소유한다. 부트스트랩은 이 함수를 쓰지 않는다 | 함수를 넘긴 호출자는 런타임이 언제 사라지는지도 생성이 실패했는지도 알 수 없어 닫을 시점이 없다. 부트스트랩에 있으면 축출이 제공자를 닫는다 | 원칙대로 호출자가 닫는다. 테넌트마다 제공자와 셸 프로세스가 샌다 |
| 제공자가 없는 조립 | `OrcaAgentRuntimeFactory.create(...)` 와 `OrcaAgentRuntimeManager.Builder.build()` 가 실패한다 | 기본값을 고르면 아무도 고르지 않은 워크스페이스에서 돈다 | 호스트 환경을 기본으로 둔다. `create(...)` 오버로드마다 제공자 인자를 둔다. 다른 협력자는 이미 `with*` 다 |
| 해석이 실패하면 | 실행을 실패시키지 않고 사용 불가 환경을 싣는다. 호스트로 돌아가지 않는다 | 파일과 셸이 필요 없는 실행은 영향을 받지 않고, 필요한 실행은 이유를 알고 실패한다 | 호스트 폴백. 열린 쪽 실패다 |
| `EnvironmentRequest.agent` | 선택이고 `agentRuntimeId` 만 필수다 | 포크와 워크플로는 `Agent` 를 들지 않는다. 제공자는 `agentRuntimeId` 와 `parent` 로 구한다 | 필수로 둔다. 포크를 띄우는 도구 넷의 생성자와 워크플로 의존 규칙이 함께 넓어진다 |
| SPI 의 위치 | `at.aimon.core.environment`. `agent <-> environment` 순환 하나를 받아들이고 가드 둘로 묶는다(§13) | 외부 모듈이 `agent` 안으로 들어가지 않고 구현한다. 요청은 에이전트 정체성을 이름으로 가리키고 프롬프트 조립은 서술자를 렌더하므로 순환은 설계에서 온다 | `at.aimon.core.agent.environment` 로 옮긴다. 외부 구현이 `agent` 안으로 들어간다. 서술자를 `agent` 로 옮기거나 문자열로 나른다. SPI 가 두 패키지로 갈리거나 타입을 잃는다 |
| 프롬프트의 환경 블록 | `EnvironmentDescriptor` 를 렌더한다. 호스트 JVM 값이 아니다 | 프롬프트가 JVM 호스트를 묘사하면 셸이 다른 곳에서 돌 때 모델이 호스트 방언(`sed -i ''`)을 쓴다 | — |
| 날짜와 시간대 | 프레임워크가 프롬프트에 넣지 않는다(§11) | 날짜가 필요한 배포가 자기 시간대로 계산해 건넨다 | 프레임워크가 시간대 값을 들고 호환 다리를 둔다. 그 값을 읽는 코드가 없고 저장되거나 노드 사이를 오가는 형식에도 없어 이행할 데이터가 없다 |
| 알림 채널 | 명령 채널 `notices()` 와 환경 채널 `drainNotices()` 를 함께 둔다(§10) | 명령 하나의 사실은 `BashOutput` 까지 가야 하고, 환경의 사실은 어느 명령의 것도 아니다 | 전부 `drainNotices()` 로 모으고 `notices()` 를 폐기한다. 나중 턴에 읽히는 백그라운드 결과의 사실이 갈 곳을 잃는다 |
| 환경 알림을 어디서 비우는가 | `SingleToolInvoker.invoke` 가 PostTool 훅 뒤에 | 모델에게 가는 결과만 지난다. 훅은 알림을 보지 못하고 훅의 셸 명령이 일으킨 사실도 그 결과에 실린다 | `DefaultToolExecutor` 에서 비운다. 스크립트, 루틴 단계, 훅에게도 닿아 알림을 잃고, PostTool 훅이 알림을 고쳐 쓸 수 있다 |
| 언제 비우는가 | 도구 호출이 끝날 때마다 | 게으른 제공자는 재생성을 첫 파일·셸 호출에서야 안다 | 턴 시작에 비운다. 가장 필요한 경우를 놓치고, 실행 도중의 사실은 어차피 도구별 경로가 필요하다 |
| 어느 도구 결과에 싣는가 | 컨텍스트에 환경이 있는 모든 도구 호출 | 알림은 도구가 아니라 환경의 사실이다. `TodoWrite` 결과에 실려도 `Read` 결과만큼 쓸모 있고, 호출 중에 쌓인 알림은 그 호출에서 나간다 | 도구가 환경을 건드렸는지 추적하는 프록시. 접근자마다 감싸야 하고, 프록시가 write-once 키에 실린 인스턴스여야 하며, 얻는 것이 없다 |
| 알림의 표현 | 결과 머리의 `[environment]` 줄 | 모델이 `Bash` · `BashOutput` 에서 이미 보는 모양이고 긴 결과에 묻히지 않는다 | `ToolResult` 에 별도 필드. 모든 LLM 클라이언트와 전사 코덱이 알아야 한다. 꼬리의 `<system-reminder>`. 한 결과에 같은 종류의 사실이 두 모양으로 나온다 |
| 알림의 전달 보장 | 돌려준 것이 전달한 것이다(최대 한 번) | 결과가 버려지면 알림도 사라지지만 창이 작다. 다음 재생성이 새 알림을 만든다 | 두 단계 확인(엿보기 + 수신 확인). SPI 를 두 배로 늘리고 모든 경로에 기록 지점을 요구한다 |

## 3. 타입

### 3.1 `ExecutionEnvironment`

```java
package at.aimon.core.environment;

/** The filesystem, shell and self-description one execution's tools run against. Not Closeable: a view. */
public interface ExecutionEnvironment {
    VirtualFileSystem fileSystem();
    VirtualShell shell();
    EnvironmentDescriptor descriptor();

    /** Files written here outlive the execution? false makes artifact tools copy to the control store. */
    default boolean durable() { return true; }

    /** Makes control-plane files readable from this environment's shell and file tools; returns the path. */
    String stage(StagedResource resource);

    /** A derived environment whose writes are isolated under branchKey; empty = no isolation, throw = refused. */
    default Optional<ExecutionEnvironment> isolate(String branchKey) { return Optional.empty(); }

    /** The environment this branch was isolated from, if it declares its lineage. */
    default Optional<ExecutionEnvironment> isolatedFrom() { return Optional.empty(); }

    default Optional<ContentSearch> contentSearch() { return Optional.empty(); }

    /** The longest a background command may run here; empty = the caller's default of 24 hours. */
    default Optional<Duration> backgroundCommandTimeout() { return Optional.empty(); }

    /** Removes and returns the facts queued for the model; called by the core's tool dispatch only (§10). */
    default List<String> drainNotices() { return List.of(); }
}
```

`stage` 의 규칙은 [스테이징](execution-environment-staging.md), `isolate` 의 두 답과 `isolatedFrom` 은
[격리 브랜치](execution-environment-isolation.md), `contentSearch` 와 `durable` 의 쓰임은
[파일 규칙](execution-environment-files.md), 상한은 [백그라운드 명령](background-commands.md)에 있다.

### 3.2 `ExecutionEnvironmentProvider`

```java
public interface ExecutionEnvironmentProvider {
    ExecutionEnvironment resolve(EnvironmentRequest request);   // once per execution

    /** Called by the assembly per runtime it builds; closing the handle says that runtime is gone. */
    default RuntimeBinding bindRuntime(AgentRuntimeId agentRuntimeId) { return RuntimeBinding.NONE; }
}
```

제공자는 답할 수 없으면 던진다. 호스트 환경으로 대신 답하지 않는다. `resolve` 는 아무도 바인딩하지 않은 런타임 id 에도
답한다. 모든 어셈블리가 바인딩하지는 않기 때문이다.

### 3.3 값 타입

| 타입 | 내용 |
|---|---|
| `EnvironmentDescriptor` | `workingDirectory` · `platform` · `osVersion` · `shellName` · `notes`(자유 텍스트 한두 줄). 불변 + 빌더 |
| `EnvironmentRequest` | 필수: `agentRuntimeId`. 선택: `agent` · `sessionId` · `executionId` · `invokingSessionId` · `principal` · `parent`(포크의 부모 환경) · `branchKey` · `fork`(`ForkDefinition`). `requestAttributes` 는 언제나 있고 포크에는 언제나 빈 맵이다. 메인 턴의 요청에는 `executionId` 가 없다([EE-105](../../backlog/execution-environment-open-items.md)) |
| `ForkDefinition` | 포크가 도는 서브에이전트의 이름과 `attributes`. 서브에이전트 타입을 싣지 않는 것은 `subagent` 가 이 패키지에 이미 의존해 순환이 되기 때문이다 |
| `ContentSearch` | `search(ContentQuery) → ContentSearchResult`. 질의와 결과의 필드는 [파일 규칙](execution-environment-files.md)에 있다 |
| `StagedResource` | 제어 저장소 쪽 파일 묶음. `sourceFileSystem` · `sourceDir` · `contentKey` · `name` · `totalBytes` · `files` · `isScanned()` |
| `FileStamp` | `size` · `modifiedAt` · `etag`? |
| `RuntimeBinding` | `close()` 하나짜리 핸들(`NONE` 포함). 멱등이고 던지지 않으며 도는 명령을 멈추지 않는다(§4.3) |

`EnvironmentDescriptor` 와 `EnvironmentRequest` 는 `workingDirectory()` · `agentRuntimeId()` 모양의 접근자를 쓴다.
SPI 를 부르는 쪽이 `env.descriptor().workingDirectory()` 로 읽기 때문이다.

`EnvironmentProviding` 은 제공자를 든 런타임이 구현한다. `AgentRuntime` 만 쥔 코드(스케줄 루틴)가 이 인터페이스로
제공자를 찾는다. 접근자를 `AgentRuntime` 에 두지 않는 것은 에이전트 코어가 이 패키지에 의존하지 않게 하기 위해서다.

## 4. 수명과 소유권

무엇을 누가 닫는가의 일반 규칙은 [`scope-model.md`](../../overview/scope-model.md)에 있다. 이 절은 왜 그렇게 나눴는가를 말한다.

### 4.1 표

| 대상 | 수명 | 소유·종료 |
|---|---|---|
| `ExecutionEnvironmentProvider` | Application. 런타임별 제공자 함수가 만든 것은 Agent | 어셈블리가 만들고 앱 shutdown 에 닫는다(`AutoCloseable` 구현은 선택). 런타임별 함수가 돌려준 것은 런타임이 소유한다(§4.2) |
| `RuntimeBinding` | Agent. 런타임 인스턴스 하나 | 런타임을 만든 어셈블리가 `bindRuntime` 으로 얻어 그 런타임과 함께 닫는다. 런타임 자신은 닫지 않는다 |
| 제공자가 쥔 셸·파일 시스템·원격 연결 | Application(또는 제공자가 정한 수명) | 제공자. 런타임 하나의 몫은 그 바인딩이 닫힐 때 놓는다 |
| `BackgroundBashManager` · `BackgroundBashStore` | [백그라운드 명령](background-commands.md) | 같은 문서 |
| `ExecutionEnvironment` | Execution | `ToolContext` 와 함께 버려진다. 닫을 것이 없다 |
| `controlFileSystem` | Application | 어셈블리 |

런타임은 셸과 파일 시스템을 소유하지 않는다. 백그라운드 명령은 시작한 실행과 런타임보다 오래 살 수 있으므로, 명령이
붙잡은 셸은 실행이 아니라 제공자의 수명에 기댄다.

### 4.2 예외 하나 — 런타임별 제공자 함수

`OrcaAgentRuntimeFactory.withExecutionEnvironmentProviderFactory(id -> ...)` 가 돌려준 제공자는 그 런타임이 소유한다.
`OrcaAgentRuntime.close()` 가 다른 자원을 다 닫은 뒤 마지막으로 닫고, `create(...)` 가 함수의 답을 받은 뒤 실패하면
`create(...)` 가 닫는다. 함수는 그 런타임 전용 제공자를 돌려줘야 한다. 여러 런타임이 나눠 쓰는 제공자는
`withExecutionEnvironmentProvider(p)` 로 주고, 런타임은 그것을 빌린다.

### 4.3 스택당 제공자와 `RuntimeBinding`

제공자는 `request.agentRuntimeId()` 로 워크스페이스를 고른다. 바인딩이 슬롯을 명령 끝까지 붙들지 않는 이유는 축출이 셸이 아니라
작업 목록을 끊기 때문이고, 그 논증은 [백그라운드 명령](background-commands.md)의 매니저 수명 절에 있다.

- 어셈블리는 런타임을 만들 때 `bindRuntime(id)` 를 **가장 먼저** 부르고, 돌려받은 핸들을 그 런타임의 자원으로 올린다.
  그래서 런타임 → 제어 저장소 → 바인딩 순으로 닫힌다. 만들다 실패하면 같은 되감기가 바인딩도 닫는다
- 바인딩을 닫으면 **그 바인딩만을 위해 쥔 것**을 놓는다. 같은 id 의 다른 바인딩이 쓰는 것은 놓지 않는다
- 바인딩을 닫아도 **도는 명령은 멈추지 않는다.** 놓을 수 없는 것은 명령이 끝날 때나 제공자의 `close()` 때 놓는다
- 런타임은 바인딩하지 않는다. 그래서 부트스트랩을 거치지 않는 조립은 `bindRuntime` 을 스스로 불러야 한다
  ([EE-56](../../backlog/execution-environment-open-items.md))

### 4.4 부트스트랩 배선

`ExecutionEnvironmentSpec` 이 스택의 제공자 하나를 정한다.

| 스펙 | 제공자 | 소유 |
|---|---|---|
| `defaults()` | `PerRuntimeLocalEnvironmentProvider` | 스택 |
| `provider(Supplier)` | 스택이 한 번 불러 얻은 것 | 스택. `AutoCloseable` 이면 스택이 닫을 때 닫는다 |
| `shared(provider)` | 호출자가 준 것 | 호출자. `bindRuntime` 통지는 받는다 |

- 제공자는 `StackAgentRuntimeProvisioner.Builder.build()` 에서 생기고, `AimonStackBuilder` 가 곧바로 `AGENT_RESOURCES` 에
  올린다. 런타임 루프보다 먼저 등록하므로 그 단계에서 마지막에 닫힌다
- 바인딩 핸들은 프로비저닝 때 얻는다. 테넌트는 `ProvisionedAgentRuntime` 이 런타임과 제어 저장소 다음으로 닫고, 시작
  런타임은 teardown 계획이 닫는다. 런타임 팩토리에는 제공자를 한 번만 준다
- `bindRuntime` 이 던지면 프로비저닝이 실패하고 되감긴다. `null` 을 돌려주면 `NONE` 으로 다루고 WARN 한다
- 로컬 슬롯의 자식 제공자는 `FileSystemSpec` 의 세 배치를 옮긴다. 로컬 루트는 `workspaceRoot`, 호출자 공급 파일 시스템은
  `fileSystem`(빌림), 파일 시스템 팩토리는 `ownedFileSystem` 이다. 팩토리의 파일 시스템을 슬롯이 소유하는 것은 같은 id 의
  런타임 둘이 겹칠 때 옛 런타임의 close 가 새 런타임이 쓰는 파일 시스템을 닫지 않게 하기 위해서다
- `shared` · `provider` 와 `FileSystemSpec.factory` 를 함께 쓰면 맡길 슬롯이 없으므로 런타임의 자원 목록이 파일 시스템을
  소유한다. 파일 시스템이 런타임을 만들 때마다 생기므로 겹침 문제는 없다
- 스타터는 `ExecutionEnvironmentProvider` 빈을 `shared` 로 넘긴다. 그래서 Spring 이 닫고 스택은 닫지 않는다

## 5. 로컬 제공자

`LocalExecutionEnvironmentProvider`(`at.aimon.core.environment.impl`)는 한 워크스페이스의 파일 시스템과 셸을 들고,
부모가 없는 모든 요청에 같은 `LocalExecutionEnvironment` 를 돌려준다. 포크의 요청에는 부모 환경을 그대로 돌려준다
(`request.parent().orElse(environment)`). 부모가 격리 브랜치면 그 브랜치 환경이지 베이스가 아니다.

| 빌더 | 파일 시스템 | 셸 |
|---|---|---|
| `workspaceRoot(Path)` | 제공자가 소유하는 `LocalFileSystem` | 제공자가 소유하는 `LocalShell`, 워크스페이스 루트 |
| `fileSystem(VirtualFileSystem)` | 빌림. 원격일 수 있다 | 제공자가 소유하는 `LocalShell`. 파일 시스템의 작업 디렉터리가 로컬 경로일 때만 거기를 루트로 삼는다 |
| `ownedFileSystem(VirtualFileSystem)` | 넘겨받아 제공자가 닫는다 | 위와 같다 |

- 빌드가 도중에 던지면 그때까지 만든 것을 스스로 닫는다. 달리 쥔 사람이 없기 때문이다
- `Bash` 는 JVM 의 `user.dir` 이 아니라 워크스페이스 루트에서 돈다
- `descriptor()` 는 호스트를 읽어 만든다. 로컬에서는 호스트가 곧 실행 환경이므로 참이다
- 원격 파일 시스템을 빌린 배치에서는 호스트 셸이 파일을 보지 못한다. 이 배치의 한계이고 스테이징에 미치는 영향은
  [EE-2](../../backlog/execution-environment-open-items.md)로 열려 있다
- 경로 규칙과 스윕, 내용 검색, 격리는 각 정본 문서에 있다

`PerRuntimeLocalEnvironmentProvider` 는 런타임 id 별 슬롯에 로컬 제공자를 하나씩 두는 Application 수명 제공자다.

- `bindRuntime` 이 바인딩 수를 올리고 핸들을 닫으면 내린다. 0 이 되면 슬롯을 지우고 자식을 닫는다
- 바인딩 없는 `resolve` 는 슬롯을 만들어 `close()` 까지 둔다. 제공자가 닫힌 뒤의 `resolve` 는 던지고 실행기가 사용 불가 환경으로 바꾼다
- 슬롯 함수의 반환 타입은 인터페이스가 아니라 `LocalExecutionEnvironmentProvider` 다. 자식을 닫아도 명령이 멈추지 않는다는
  것은 `LocalShell` 이 명령에 대해 아무것도 쥐지 않을 때만 참이기 때문이다
- 슬롯 함수는 제공자 전체의 락 밖에서 돈다. 한 테넌트의 디렉터리 생성이나 원격 연결이 다른 테넌트의 `resolve` 를 막지 않아야
  하기 때문이다. 같은 id 의 동시 첫 요청은 빌드 하나를 나눠 쓰고, 실패한 빌드는 지워져 다음 요청이 다시 짓는다. 짓는 사이에 `close()` 가 돌면 지은 것을 닫고 던진다

**CLI 의 워크스페이스 루트.** CLI 는 `provider(() -> ...workspaceRoot(...))` 로 단일 워크스페이스 제공자를 쓴다. 루트는 jar 가
있는 디렉터리이고, jar 로 돌지 않을 때만 현재 디렉터리(`user.dir`)다(`AgentSetupFactory.getJarDirectory`). jar 를 프로젝트
밖에 두고 프로젝트에서 실행하면 현재 디렉터리와 다른 곳을 가리킨다. 이 값은 로컬의 **워크스페이스 루트**이고, 환경의
루트(SPI 어휘)나 `hooks.json` 층을 찾는 프로젝트 루트와 다른 층의 이름이다.

## 6. 해석

### 6.1 순서

`resolve()` 는 **실행 시작에 한 번**, 프롬프트 조립과 반복 루프보다 먼저 부른다. 도구 컨텍스트를 만드는 곳에서 부르면
늦다. 프롬프트 조립이 그보다 앞서 환경을 읽기 때문이다. 한 실행 안에서 프롬프트가 묘사하는 환경과 도구가 쓰는 환경이
다를 수 없게 하는 것이 목적이다.

```text
1. env = resolveOrUnavailable(provider, request)   once, at execution start
2. prompt assembly reads env (descriptor; opt-in context providers read fileSystem())
3. tool context:
     framework keys
     EXECUTION_ENVIRONMENT = env, EXECUTION_ENVIRONMENT_PROVIDER = provider   (write-once)
     HOOK_REGISTRY                                                            (write-once)
     enrichers    may read the keys above, may not replace them
```

`ExecutionEnvironments.resolveOrUnavailable` 이 예외, `null` 답, 없는 제공자를 모두 사용 불가 환경으로 바꾼다. 메인 턴과
루틴은 `environment.*` 추출까지 하는 오버로드를 쓴다([배치 입력](execution-environment-placement.md)).

### 6.2 `resolve()` 를 부르는 곳

불변식은 **한 실행에 한 번**이다. 한 실행에서 두 번 부르면 한 실행에 환경이 둘이 된다.

| 자리 | 언제 | 요청 |
|---|---|---|
| `OrcaAgentExecutor.execute` | 실행마다. 메인 턴, 슬래시 커맨드 흐름, 리플레이 턴이 같은 값을 쓴다. 커맨드 흐름은 ReAct 루프 대신 도는 같은 실행의 분기다 | 에이전트, 세션, 주체, 실행 속성 |
| `DefaultSubagentExecutor` | 포크마다. 워크플로 단계도 포크다 | 실행 id, 호출 세션, 주체, `parent`, `fork` |
| `RoutineExecutor` | 루틴 실행마다 | 에이전트, 실행 id, 태스크 소유자, 해석기가 준 속성 |
| `AgentSetupFactory.workingDirectoryOf` | CLI 가 작업 디렉터리를 알아낼 때. 어떤 실행에도 속하지 않는다 | 에이전트 |
| `DefaultWorkflowContext.resolveLaunchContext` | **틈.** 격리 단계인데 기본 launch context 에 부모 환경이 없을 때마다 | `agentRuntimeId` 와 `principal` 뿐 |

마지막 행은 에이전트 범위 러너(백그라운드 워크플로)에서 생긴다. 그 러너는 제공자만 싣고 부모 환경을 싣지 않으므로, 격리
단계마다 부모 없이 해석한다. 로컬 제공자는 부모가 없으면 같은 환경을 돌려주므로 드러나지 않는다. 그런 요청으로
워크스페이스를 정할 수 없는 제공자는 거부한다. 이 틈은 [EE-30](../../backlog/execution-environment-open-items.md)으로 열려 있다.

이 밖에 `ToolContext` 를 다시 조립하는 경로는 `resolve()` 를 부르지 않고 이미 해석된 실행의 환경을 싣는다.

루틴은 `EnvironmentProviding` 으로 제공자를 찾는다. 런타임이 등록되어 있지 않거나 제공자를 내주지 않으면 그 사실을 원인으로
담은 사용 불가 환경을 싣는다. 포크에 제공자가 전달되지 않았을 때도 원인이 그렇게 실린다
(`no ExecutionEnvironmentProvider forwarded to this fork`). 배선 결함이 호스트 실행으로 숨지 않고 모델에게 보인다.

### 6.3 write-once 키

`EXECUTION_ENVIRONMENT` 와 `EXECUTION_ENVIRONMENT_PROVIDER` 는 write-once 키다. 이름은 `ToolContextKey` 의 정적 집합에 있고
내장 이름은 상수로 먼저 넣는다. `put` · `putAll` 이 한 검사를 지난다. 내장 이름은 여섯이다. 환경, 제공자, `hookRegistry`,
`quotaGuard`, `executionDeadline`, `contextEngine`. `ToolContextKeysWriteOnceTest` 가 상수 집합과 키 선언의 어긋남을 잡는다.

enricher 가 두 키를 쓰면 `IllegalStateException` 이 나고, 메인 턴과 포크의 enricher 경로가 enricher 별 `try/catch` 로 잡아
WARN 한다. 환경은 제공자가 답한 그대로 남는다. `RoutineExecutor` 에는 enricher 가 없다.

### 6.4 프롬프트 조립이 환경을 읽는 비용

서술자는 `resolve()` 가 돌려준 값이라 괜찮다. `GitStatusContextProvider` · `DirectorySummaryContextProvider` 는 매 턴
`fileSystem()` 을 읽는다. 첫 파일 접근에서 프로비저닝하는 제공자와 함께 쓰면 명령을 한 번도 실행하지 않는 턴도 샌드박스를
띄우고, 일시 정지된 샌드박스를 매 턴 깨운다. 두 제공자는 옵트인이라 기본 조립에는 없고, 그 조합을 쓸지는 어셈블리가
정한다. 코어는 두 제공자에게 환경의 준비 상태를 알리는 수단을 두지 않는다.

## 7. 사용 불가 환경

호스트 환경으로 되돌아가는 경로는 없다. `UnavailableExecutionEnvironment` 는 환경을 얻지 못한 실행에 싣는 값이다.

- 파일 시스템과 셸의 모든 호출, `stage`, `isolate` 가 원인을 담은 `ExecutionEnvironmentUnavailableException` 을 던진다.
  `isolate` 가 빈 값이 아니라 던지는 것은 "격리 없음" 이 호출자에게 필요한 이유를 가리기 때문이다
- 도구는 그 예외를 `ToolResult.error` 로 바꾼다. 거부는 평범한 오류 결과다
- `descriptor()` 는 던지지 않는다. `notes` 에 `execution environment unavailable: {원인}` 을 싣고 나머지를 비워, 모델이 첫 도구
  호출 전에 사정을 안다
- 도구는 사용 불가 환경을 "결과 없음" 으로 삼키지 않는다. `Grep` 은 전파하고 `WikiIngest` 는 먼저 디렉터리를 확인한다
- 백그라운드 명령은 시작 전에 확인한다([백그라운드 명령](background-commands.md))
- 가드 이벤트의 셸 훅은 이 환경에서 명령을 돌리지 못하면 막는다([fail-closed](../hook/hook-fail-closed.md))

## 8. 포크 계약

`EnvironmentRequest.parent` 에 부모 환경을 싣는다. 코어가 약속하는 것은 **포크는 부모의 격리 단위(작업 공간) 밖으로 나가지
않는다** 이다. "부모와 같은 파일 시스템" 은 로컬 제공자의 성질이지 계약이 아니다.

- 로컬 제공자는 `parent` 를 그대로 돌려준다
- 샌드박스 제공자는 부모와 같은 워크스페이스 안의 환경을 돌려주되 셸은 새로 잡고, 어느 슬롯에서 돌지는 자기 바인딩 정책으로
  정한다. 기본은 부모와 같은 샌드박스다
- 부모가 사용 불가 환경이면 제공자는 포크도 사용 불가로 돌려준다. 부모를 만들지 못한 이유(주체 거부 등)를 포크가 새 해석으로
  우회하면 안 된다
- 포크의 요청에는 포크 자신의 정의가 실린다(`fork()`). 제공자는 `agent()` 가 아니라 `definitionAttributes()` 를 읽는다
- 워크플로 단계도 포크이므로 정의 속성을 싣는다. 속성을 어떻게 채우는지는 [배치 입력](execution-environment-placement.md)에
  있다. 속성이 비면 제공자는 부모와 같은 샌드박스를 기본으로 한다
- 포크의 요청에 `requestAttributes` 는 언제나 비어 있다. 부모 턴의 `environment.*` 속성은 포크의 도구에 보이지만 포크의 배치를
  정하지 않는다. 명시적으로 받는 길은 [EE-104](../../backlog/execution-environment-open-items.md)로 열려 있다
- `SubagentLaunchContext.toolRegistry` 는 부모 레지스트리 그대로다. 브랜치별 레지스트리는 없다
- 격리 단계는 러너가 `isolate()` 로 파생한다. 규칙은 [격리 브랜치](execution-environment-isolation.md), 러너의 사용은
  [`workflow.md`](../workflow/workflow.md)에 있다

**에이전트 범위 러너.** 백그라운드 워크플로의 러너는 호출 컨텍스트에서 아무것도 물려받지 않는다. 부모 환경도 스킬 훅도 없다.
부모 환경의 틈은 §6.2 다. 스킬 훅 쪽의 거절은 [훅 환경](../hook/hook-environment.md)의 끊기는 경로 거절 절에 있고, 물려받는 길을
낼지는 [EE-63](../../backlog/execution-environment-open-items.md)으로 열려 있다.

## 9. 도구가 환경을 받는 규칙

파일 도구, `Bash`, `WikiIngest`, `Skill` 은 생성자에서 환경을 받지 않는다. `execute()` 안에서
`ExecutionEnvironmentAccess.require(context)` 로 꺼내고, 없거나 사용 불가면 `ToolResult.error` 를 돌려준다. 던지지 않는다.

- `OrcaBashToolProvider` 는 `Bash` 와 `BashOutput` 을 언제나 등록한다. 셸은 등록 시점에 알 수 없으므로, 셸을 쓸 수 없는 실행은
  호출이 오류를 낸다
- 스킬 기반 커맨드도 실행 안에서 돌므로 그 실행의 환경을 쓴다
- `OrcaToolProviderContext` 에는 `getFileSystem()` 과 `getShell()` 이 없고 `getControlFileSystem()` 이 있다. 도구 등록 시점에 작업
  환경을 붙잡을 통로를 남기면 외부 프로바이더가 그것을 생성자에 넣어 §2 의 첫 행을 다시 만든다
- `toolsHoldNoFileSystemOrShellFields` 가 이 규칙을 강제한다. 예외는 제어 저장소를 쥐는 `ArtifactArchive` 하나다

도구별 규칙은 소유 문서에 있다. `Read` · `Write` · `Edit` 의 stamp, `Grep` 의 내용 검색, artifact 복사는
[파일 규칙](execution-environment-files.md), `Skill` 의 파일 목록과 `${AIMON_SKILL_DIR}` 는
[스테이징](execution-environment-staging.md), `BashOutput` · `KillShell` 은 [백그라운드 명령](background-commands.md)이다.

## 10. 알림 두 채널

환경이 모델에게 알려야 하는 사실의 통로는 둘이다. 같은 사실을 둘 다에 싣지 않는다.

| 채널 | 무엇 | 어디까지 간다 |
|---|---|---|
| `ShellCommandResult.notices()` | 명령 하나의 실행에 관한 사실. "셸 세션이 새로 열려 cwd·환경 변수가 초기화되었다" | `Bash` 결과. 백그라운드 명령이면 `BashOutput` |
| `ExecutionEnvironment.drainNotices()` | 환경 자체의 사실. "샌드박스가 재생성되어 `/workspace` 가 비었다", "이 세션은 이제 다른 워크스페이스에서 돈다" | 모델에게 가는 모든 도구 결과 |

"샌드박스가 재생성되어 `/workspace` 가 비었다" 같은 환경 자체의 사실은 어느 셸 명령의 것도 아니다. 명령별 채널만 있으면 파일
도구만 쓰는 실행은 그것을 듣지 못하고 빈 트리를 고친다.

### 10.1 명령 채널

`ShellExecutionException` · `ShellTimeoutException` · `ShellCancelledException` 도 같은 `notices()` 를 싣는다. 명령이 timeout 되거나
실패해도 환경은 바뀌었을 수 있기 때문이다. `BashTool` 은 출력이나 오류 앞에 `[environment]` 줄로 붙이고, 이 줄은 `filter` 정규식과
출력 잘림의 대상이 아니다. stderr 에 섞지 않는다. stderr 는 명령이 낸 것이고 모델은 그것을 명령의 오류로 해석한다. 로컬 셸은
notice 를 만들지 않는다.

### 10.2 환경 채널의 전달

코어는 **모델에게 가는 도구 호출 하나가 끝날 때마다**, 어떤 도구였든, 그 실행의 환경을 한 번 비우고 나온 notice 를 결과 앞에
붙인다. 항목당 한 줄이다.

```text
[environment] The sandbox was re-created; /workspace is empty.
<Read's normal output>
```

`Bash` 결과에서는 환경의 줄이 먼저, 명령의 줄이 다음, 출력이 마지막이다.

비우는 자리는 [도구 호출 디스패처](tool-call-dispatcher.md) 둘이고, 붙이는 것은 결과를 모델에게 넘기는 루프다.

- `SingleToolInvoker.invoke` (관문을 지나는 `DefaultToolCallDispatcher` 뒤)는 디스패치 전체를 한 번 감싸 비우므로 모든 종료 경로가
  구조적으로 덮이고, 드레인은 도구별 terminator 가 닫힌 뒤에 돌아 인터럽트가 닿지 않는다. 메인 에이전트, 서브에이전트, 슬래시
  스킬의 루프가 이것을 지난다
- 이름으로 고르는 `ToolCallDispatchers.ungated` 도 호출마다 비운다
- 비운 notice 는 `ToolDispatchResult` 의 `notices` 목록으로 결과 옆에 나오고, 결과를 전사에 넣는 루프가
  `ToolDispatchResults.toToolUseResult` 에서 한 번 앞에 붙인다

모델에게 가지 않는 경로인 워크플로 스크립트의 도구 호출, 스케줄 루틴 단계, 훅은 비우지 않는다. 그 notice 는 다음 모델 쪽
호출까지 남는다.

### 10.3 규칙

- **훅은 보지 못한다.** PostTool 훅이 받는 결과에 notice 는 없고, 훅이 결과를 고쳐 써도 notice 는 그 뒤에 붙는다. 훅의 셸 명령이
  일으킨 재생성도 그 도구의 결과에 실린다. 훅의 셸 결과의 `notices()` 는 버린다
- **한 번만, 받은 순간 확정.** 돌려준 notice 는 다시 돌려주지 않는다. 병렬 배치에서는 여러 스레드가 비우므로 원자적이어야 하고,
  어느 형제 결과에 실릴지는 정해져 있지 않다. 돌려준 것이 곧 전달한 것이다
- **빠르고 조용하게.** 긴 원격 입출력을 하지 않고 던지지 않는다
- **항목당 한 줄.** 줄바꿈이 있는 항목은 이어지는 줄이 도구의 출력처럼 읽힌다. 긴 사실은 여러 항목으로 나눈다
- **상한은 없다.** `Bash` 도 두지 않는다
- **도구와 훅은 부르지 않는다.** 비운 것은 모델에게 가지 못한다. 도구 작성자용 지시는
  [도구 개발 가이드](../../features/tool/tool-development-guide.md)에 있다. 훅을 막는 것이 문서뿐이라는 점은
  [EE-108](../../backlog/execution-environment-open-items.md)으로 열려 있다

**큐의 범위.** 코어는 디스패치하는 컨텍스트의 인스턴스를 비운다. 큐는 인스턴스마다 하나를 권고한다. 포크에 부모 인스턴스를 그대로
주는 제공자는 notice 를 만들지 않을 때만 안전하다. 로컬 제공자가 그 경우다. notice 를 만드는 제공자가 큐를 공유하면 포크의 첫 호출이
가져가 포크의 모델만 듣고, 부모는 비워진 트리를 모른 채 고친다. 포크의 큐는 공유하지 말고 포크도 들어야 할 것으로 채운다.

**듣지 못하는 자리.** 도구를 부르지 않는 실행은 듣지 못한다. 실행 시작에 비우는 경로가 없기 때문이다
([EE-106](../../backlog/execution-environment-open-items.md)). 사용자가 친 `/skill` 의 루프가 비운 notice 는 그 스킬의 LLM 만 듣고
메인 에이전트의 다음 턴은 듣지 못한다. 그 루프의 전사는 끝나면 버려진다
([EE-107](../../backlog/execution-environment-open-items.md)).

### 10.4 실패 처리

`EnvironmentNotices.drain` 은 던지지 않고 비울 것이 없으면 빈 목록을 돌려준다. `ToolDispatchResults.toToolUseResult` 가 붙인
결과는 `isError`, 렌더 payload, `isRetryable` 을 유지한다.

| 경우 | 처리 |
|---|---|
| `drainNotices()` 가 던진다 | 도구 이름과 toolUseId 로 WARN 하고 빈 목록을 돌려준다. notice 는 제공자가 지우기 전에 던졌는지에 따라 남거나 사라진다 |
| `null` 반환, `null`·빈 항목 | 없는 것으로 친다 |
| 컨텍스트에 환경이 없다 | 비울 것이 없다 |
| `UnavailableExecutionEnvironment` | 기본값(빈 목록). 원인은 도구의 오류로 이미 간다 |
| 거부·차단·모르는 도구·예외로 끝난 호출 | 결과가 모델에게 가므로 싣는다 |
| 도구가 돌아온 뒤 실행이 중단됐다 | 경로가 결과를 버리면 notice 도 사라진다 |

`BashTool` 은 자기 렌더러를 유지하므로 명령 채널의 빈 항목을 건너뛰지 않는다. 두 채널은 접두어 상수 하나
(`EnvironmentNotices.PREFIX`)를 쓴다.

## 11. 프롬프트 서술

시스템 프롬프트, 포크 프롬프트, `EnvironmentContextProvider` 는 **그 실행의** `EnvironmentDescriptor` 를
`EnvironmentBlocks.render` 하나로 렌더한다. 작업 디렉터리, platform, OS 버전, `notes` 를 싣고 서술자에 없는 필드는 뺀다.
`shellName` 은 서술자에 있지만 이 블록에는 실리지 않는다.

**날짜를 넣지 않는다.** 프레임워크는 날짜와 시간대를 프롬프트에 넣지 않는다. 날짜가 필요한 배포는 자기 시간대로 계산한 값을
시스템 프롬프트 변수로 건넨다. 프레임워크가 시간대 값을 들지 않으므로 호환 다리도 없다. 옛 이름의 대응은
[`rename-maps.md`](../../migration/rename-maps.md)에 있다.

**캐시.** 서술자를 실행 단위로 조립하는 것이 이 구조의 비용이다. 서술자는 환경마다 한 번 정해지고 한 세션 안에서는 바뀌지
않으므로 세션 단위 캐시 접두부는 안정적이다. 샌드박스가 재생성되어도 이미지가 같으면 같다.

**훅.** 실행 안에서 발화하는 이벤트의 훅 컨텍스트도 실행의 환경을 싣고, `getEnvironmentDescriptor()` 는 거기서 파생된다.
`subagentStop` 은 포크의 환경을 싣는다. 규칙은 [훅 환경](../hook/hook-environment.md)에 있다.

## 12. `environment` 동음이의 구분

`environment` 라는 낱말은 이 저장소에서 서로 다른 개념을 가리킨다. 일괄 치환과 검색이 이 경계를 넘으면 안 된다.

| 이름 | 무엇 |
|---|---|
| `ExecutionEnvironment` · `EnvironmentDescriptor` · `ExecutionEnvironmentProvider` · `EnvironmentRequest` · `ToolContextKeys.EXECUTION_ENVIRONMENT*` · `HookContext.getExecutionEnvironment()` · `getEnvironmentDescriptor()` | 이 문서의 실행 환경 |
| `ExecutionOptions.getEnvironment()` · `ExecutionOptions.Builder.environment(Map)` · `ProcessBuilder.environment()` | 셸의 환경 변수 맵 |
| `environment.*` 실행 속성 · `EnvironmentRequestAttributes` | 제공자에게 가는 배치 입력 |
| `AimonProperties.getEnvironment()` · `EnvironmentProperties` · `aimon.environment.*` | 스타터의 실행 환경 설정 |
| `EnvironmentBlocks` · `EnvironmentContextProvider`(`BLOCK_KEY = "environment"`) · `SystemPromptPart` 의 `kind("environment")` | 프롬프트의 환경 블록 |
| `org.springframework.core.env.Environment` | Spring 의 설정 환경 |

**`SubagentLaunchContext` 는 실행 환경이 아니다.** 호출자가 `SubagentExecutionManager` 에 건네는 협력자 묶음이다. 실행 환경이
아니라 실행 환경을 나른다. 값 객체라 자기 수명이 없고 들고 있는 쪽을 따른다. `SubagentExecutionContext` 는 매니저가 실행기에 주는
것으로, 풀린 `Subagent` 와 그 포크의 협력자를 담는다. 둘은 띄우기의 양 끝이다. `getExecutionEnvironment()` 는 포크가 아니라 스폰한
실행의 환경을 돌려주며, 이것은 이름이 아니라 javadoc 에만 있다. 옛 이름의 대응은
[`rename-maps.md`](../../migration/rename-maps.md)에 있다.

## 13. 패키지 경계와 순환 가드

`at.aimon.core.environment` 가 중립 SPI 이고 `at.aimon.core.environment.impl` 이 로컬 구현이다. `PackageDependencyArchitectureTest` 가
다음을 강제한다.

| 규칙 | 지키는 것 |
|---|---|
| `environmentImplMustNotLeakOutsideEnvironmentTree` | 실행기, 도구, 어셈블리는 중립 SPI 에만 의존한다. 로컬 제공자는 `filesystem.impl` · `shell.impl` 을 직접 만드는 코어 안의 유일한 조립 지점이다 |
| `environmentSpiDependenciesAreCurated` | SPI 는 코어, 파일 시스템, 셸 SPI 와 네 에이전트 정체성 타입(`Agent` · `AgentRuntimeId` · `SessionId` · `ExecutionId`)에만 의존한다. 순환의 `environment → agent` 쪽을 묶는다 |
| `onlyAgentContextAndCompactMayReachEnvironmentFromAgentTree` | 에이전트 코어에서는 프롬프트 조립과 압축만 이 패키지에 닿는다. 순환의 `agent → environment` 쪽을 묶는다 |
| `noNewTopLevelCorePackageCycles` · `BASELINE_TOP_LEVEL_CYCLES` | `agent <-> environment` 는 baseline 에 있고 새 순환은 막는다 |
| `toolsHoldNoFileSystemOrShellFields` | §9 |

위치 결정 셋이 이 경계를 지탱한다.

- `UnavailableExecutionEnvironment` 와 `ExecutionEnvironments` 는 `impl` 이 아니라 중립 패키지에 있다. 실행기가 읽기 때문이다
- `ToolContext` 에서 환경을 꺼내는 `ExecutionEnvironmentAccess` 는 `at.aimon.core.tools` 에 있다. 그래서 `environment` 는 `tools` 나
  `agent.tool` 에 의존하지 않는다
- `EnvironmentNotices` 도 `at.aimon.core.tools` 에 있다. `SingleToolInvoker`, `ToolCallDispatchers`, `ToolDispatchResults` 가 이미 이 패키지를 보고, 헬퍼는
  `environment` 와 `llm.ToolUseResult` 에만 의존한다

## 14. 샌드박스 제공자와의 계약

aimon-sandbox 는 `ExecutionEnvironmentProvider` 를 구현한다. 이 표가 코어가 그쪽에 약속하고 요구하는 것의 정본이다. 규칙의 근거는
각 소유 문서에 있다. [워크스페이스 샌드박스 설계](https://github.com/kangwoo/aimon-sandbox/blob/main/docs/design/workspace-sandbox.md)의
§7 이 이 표를 샌드박스 쪽 구현으로 풀어 적는다. 실행 디렉터리와 격리 실행의 외부 후속은
[EE-114](../../backlog/execution-environment-open-items.md)가 소유한다.

| 코어가 보장 | 샌드박스 제공자가 보장 |
|---|---|
| 실행마다 `resolve()` 를 한 번, 도구 호출 전에 부른다 | `resolve()` 는 원격 자원을 만들지 않는다(게으른 프로비저닝). 파일·셸을 처음 쓸 때 만든다 |
| 포크에 `parent` 를 싣는다 | `parent` 와 같은 워크스페이스, 새 셸을 돌려준다. 슬롯은 바인딩 정책이 정하고 기본은 부모와 같은 샌드박스다. 부모가 사용 불가면 포크도 사용 불가다(§8) |
| 워크플로 브랜치는 `isolate()` 로만 만든다. 병합은 하지 않는다. 조립 코드가 고른다 | 브랜치를 제공한다면 파일 복사 병합(`WorktreeMerge.promote`)이 그 환경에서도 동작해야 한다. git worktree 로 격리하는 제공자는 git 병합을 자기 모듈 API 로 따로 줄 수 있다 |
| `durable() == false` 면 artifact 를 복사한다 | 비영속 작업 공간에 대해 `durable() == false` 를 돌려준다 |
| `notices()` 를 모델에게 보인다 | 셸 세션·샌드박스 재생성 시 notice 를 싣는다 |
| 모델에게 가는 도구 호출이 끝날 때마다, 어떤 도구였든, `drainNotices()` 를 한 번 불러 그 결과 앞에 보인다. 훅에는 보이지 않는다(§10) | 환경 자체의 사실은 `ShellCommandResult` 가 아니라 `drainNotices()` 에 싣는다. 원자적이고 한 번만 돌려주며, 돌려준 것은 전달된 것으로 본다. 큐는 환경 인스턴스마다 두고 포크와 공유하지 않는다 |
| 백그라운드 명령에 `ExecutionOptions.background` 를 켠다 | 켜진 명령은 지속 셸 세션을 쥐지 않는다 |
| 훅의 셸 액션(스킬 선언 훅과 `hooks.json` 훅)에 `ExecutionOptions.hook` 을 켠다 | 켜진 명령은 지속 셸 세션 밖에서 돈다. 세션 잠금을 잡지 않고 `cd` · `export` 를 세션에 저장하지 않는다. 세션의 현재 상태에서 시작해도 된다. 옵션을 파생하는 래퍼는 플래그를 넘긴다 |
| 환경에 놓인 `hooks.json` 명령에 `ExecutionOptions.isolated` 도 켜고, 셸이 `ShellFeature.ISOLATED_EXECUTION` 을 선언했을 때만 돌린다. 계보를 밝힌 브랜치의 명령은 그 기반 환경의 셸에서 돌린다 | 선언했다면 켜진 명령은 세션의 상태(`cd` · `export`)를 읽지도 저장하지도 않고, 세션 잠금 없이, 환경의 루트(샌드박스 루트)에서 프로파일의 기본 환경 위에 `environment` 만 얹어 시작한다. 옵션을 파생하는 래퍼는 플래그를 넘긴다. 선언하지 않으면 그 명령은 돌지 않는다(가드 훅은 막는다) |
| `AIMON_HOOK_DIR` 을 쓰는 `hooks.json` 명령과 스킬 훅에 `ExecutionOptions.runDirectory` 를 싣는다. 셸이 `ShellFeature.RUN_DIRECTORY` 를 선언하지 않으면 전자는 돌리지 않고 후자는 스테이징으로 물러선다([변조 경계](../hook/hook-tamper-boundary.md)) | 선언했다면 계약 6조를 지킨다. 바이트가 에이전트가 쓸 수 있는 자리를 지나면 그 길을 거치지 않은 다이제스트로 대조하고, 다르면 `RunDirectoryIntegrityException` |
| 셸이 `ShellFeature.CANCELLATION` 을 선언하면 백그라운드 명령마다 `ExecutionOptions.getCancellation()` 에 신호를 싣고, `KillShell` 과 스택 종료가 그 신호를 건다. 포그라운드 명령에는 선언과 무관하게 언제나 신호를 싣고, 실행의 인터럽트가 그 신호를 건다 | 선언했다면 신호가 걸릴 때 원격 명령과 그 명령이 띄운 것을 멈추고 그 `execute` 가 `ShellCancelledException` 을 던진다(멈춤을 요청만 하고 돌아와도 된다). 이미 걸린 신호면 명령을 띄우지 않는다. 옵션을 파생하는 래퍼는 신호를 넘긴다. 선언하지 않으면 `KillShell` 은 오류로 답한다 |
| 백그라운드 명령의 timeout 으로 `backgroundCommandTimeout()` 을 쓴다. 비어 있으면 24시간이다 | 도는 명령이 슬롯을 깨워 두는 배치라면 감당할 수 있는 상한을 돌려준다. 0 이하는 무시된다 |
| 런타임을 만들 때마다 `bindRuntime(id)` 를 부르고, 그 런타임이 사라질 때 핸들을 닫는다. 제공자 자체는 스택이 끝날 때 닫는다(§4.3) | 런타임별 자원을 쥔다면 `bindRuntime` 으로 통지를 받는다. 핸들이 닫혀도 **도는 명령은 멈추지 않고**, 같은 id 의 다른 바인딩이 쓰는 것은 놓지 않는다. 바인딩되지 않은 id 의 `resolve` 도 답한다 |
| artifact 복사 경로와 상한을 정한다 | 복사 대상 경로를 따로 정하지 않는다. 코어의 artifact 도구가 복사한다 |
| 스킬을 렌더하는 모든 경로에서 `stage()` 를 거친다 | `stage()` 는 기존 사본을 호출마다 내용 키와 대조하고 재생성 뒤에는 다시 복사한다. 스테이징 루트는 브랜치 바깥에 두고 `isolate()` 파생 환경과 공유한다 |
| 서술자를 프롬프트에 렌더한다. `resolve()` 직후, 첫 도구 호출 전에 | 서술자는 이미지의 실제 platform·OS 다. 원격 자원 없이 알 수 있어야 하므로 설정(프로파일)에 선언한 값이고, 프로비저닝 때 실제 이미지와 대조한다. 같은 세션에서는 재생성을 넘어 같은 값을 돌려준다(§11) |
| 스테이징 영역을 파일 도구에 읽기 전용으로 둔다. 경로 규칙으로. 로컬 제공자와 외부 제공자가 같은 공개 팩토리 `VirtualFileSystems.withPathRules` 를 쓴다 | 샌드박스의 스테이징 영역도 파일 도구에 읽기 전용이다. 셸은 쓸 수 있다 |
| `WorktreeMerge.promote` 는 부모 자신·중복·부모와 파일 시스템을 공유하는 브랜치를 거부하고, `isolatedFrom()` 이 밝힌 계보가 다르면 거부한다 | (권장) `isolate()` 가 만든 브랜치는 `isolatedFrom()` 으로 부모를 밝히고, 부모의 경로 규칙을 브랜치 루트 기준으로 `VirtualFileSystems.withPathRules` 로 건다. 사용 불가이거나 이미 브랜치여서 격리를 거절할 때는 빈 값이 아니라 이유를 담은 예외를 던진다 |
| `resolve()` 실패 시 호스트로 되돌아가지 않는다 | 실패를 예외로 알린다 |
| 메인 턴과 루틴의 요청에 그 실행의 `environment.*` 실행 속성을 접두사를 떼고 `requestAttributes()` 로 싣는다. 값은 `String` 뿐이고(아니면 제공자를 부르지 않고 사용 불가), 모델 출력에서 오지 않으며(호스트가 채운다), 포크·워크플로 단계에는 싣지 않는다. `toString()` 은 키 이름만 찍는다 | 배치 입력으로 다루고 `definitionAttributes()` 위에 자기 정책대로 얹는다. 따를 수 없는 요청(없는 워크스페이스, "붙은 것이 없으면 거절")은 예외로 알린다. 값을 로그에 남기지 않는다 |
| 스킬 선언 훅의 셸 액션을 그 실행의 `shell().execute` 로 돌린다. `ExecutionOptions` 에 `timeout` · `environment`(`AIMON_*` 변수) · `stdin`(JSON 페이로드)을 싣는다 | 셸이 `environment` 와 `stdin` 옵션을 받는다. 받지 못하면 그 훅은 돌지 않은 것으로 처리된다. 가드 이벤트(`preTool` · `onStart` · `preCompact` · `permissionRequest`)에서는 `failOpen` 이 아닌 한 **거부**이고, 나머지는 WARN 후 진행이다. `shell()` · `execute` 가 사용 불가 예외를 던질 때도 같다 |
| 백그라운드 작업을 그것을 띄운 세션(또는 세션 없는 실행)에만 보인다. 레코드에 소유 필드 셋을 싣는다 | `BackgroundBashStore` 를 구현한다면 소유 필드 셋을 그대로 저장하고 돌려준다 |
| 프롬프트 조립이 `fileSystem()` 을 읽을 수 있다(옵트인 컨텍스트 제공자, §6.4) | 그 읽기가 프로비저닝을 일으킨다는 것을 문서에 밝힌다 |

## 15. 하지 말 것

- **도구 생성자에 파일 시스템이나 셸을 넣지 말 것.** 실행마다 `EXECUTION_ENVIRONMENT` 에서 꺼낸다(§9)
- **환경을 얻지 못했을 때 기본 환경으로 되돌아가지 말 것.** `UnavailableExecutionEnvironment` 가 오류를 낸다(§7)
- **enricher 에서 `EXECUTION_ENVIRONMENT` 를 덮어쓰지 말 것.** write-once 이며 제공자만 쓴다(§6.3)
- **한 실행에서 `resolve()` 를 두 번 부르지 말 것.** 컨텍스트를 다시 조립할 때는 해석된 환경을 싣는다(§6.2)
- **실행이 끝날 때 환경의 셸·파일 시스템을 닫지 말 것.** 제공자 소유다(§4)
- **런타임이 사라질 때 제공자를 닫지 말 것.** 닫는 것은 그 런타임의 `RuntimeBinding` 이다. 제공자는 다른 런타임과, 사라진 런타임이
  띄워 둔 백그라운드 명령이 계속 쓴다(§4.3)
- **`RuntimeBinding.close()` 에서 도는 명령을 멈추지 말 것.** 축출은 종료 요청이 아니다(§4.3)
- **포크가 부모의 사용 불가를 새 해석으로 우회하게 두지 말 것**(§8)
- **도구나 훅에서 `drainNotices()` 를 부르지 말 것.** 비운 것은 모델에게 가지 못한다(§10)
- **같은 사실을 `notices()` 와 `drainNotices()` 양쪽에 싣지 말 것**(§10)

훅, 백그라운드 명령, 스테이징, 격리, artifact, 제어 저장소의 금지는 각 정본 문서의 하지 말 것에 있다.

## 16. 참조 파일 지도

| 주제 | 타입 | 경로 |
|---|---|---|
| SPI | `ExecutionEnvironment`, `ExecutionEnvironmentProvider`, `EnvironmentRequest`, `EnvironmentDescriptor`, `ForkDefinition`, `RuntimeBinding`, `EnvironmentProviding` | `modules/aimon-core/src/main/java/at/aimon/core/environment/` |
| 해석 헬퍼, 사용 불가 환경 | `ExecutionEnvironments`, `UnavailableExecutionEnvironment` | 같은 디렉터리 |
| 로컬 구현 | `LocalExecutionEnvironmentProvider`, `LocalExecutionEnvironment`, `PerRuntimeLocalEnvironmentProvider` | `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/` |
| 컨텍스트 키 | `ToolContextKey`, `ToolContextKeys`, `ExecutionEnvironmentAccess` | `.../core/agent/tool/`, `.../core/tools/` |
| 해석 자리 | `OrcaAgentExecutor`, `DefaultSubagentExecutor`, `RoutineExecutor`, `DefaultWorkflowContext` | `.../core/agent/impl/orca/`, `.../core/subagent/execution/`, `.../core/scheduling/`, `.../core/workflow/impl/` |
| 런타임 소유 | `OrcaAgentRuntimeFactory`, `OrcaAgentRuntime`, `OrcaAgentRuntimeManager` | `.../core/agent/impl/orca/` |
| 도구 등록 | `OrcaToolProviderContext`, `OrcaBashToolProvider`, `OrcaFileToolProvider` | `.../core/agent/orca/tool/`, `.../core/agent/impl/orca/tool/` |
| 포크 협력자 | `SubagentLaunchContext`, `SubagentExecutionContext` | `.../core/subagent/`, `.../core/subagent/execution/` |
| 알림 | `EnvironmentNotices`, `SingleToolInvoker`, `ToolCallDispatchers`, `ToolDispatchResults`, `ShellCommandResult`, `BashTool` | `.../core/tools/`, `.../core/toolinvocation/`, `.../core/shell/`, `.../core/tools/bash/` |
| 프롬프트 | `EnvironmentBlocks`, `EnvironmentContextProvider`, `SystemPromptRenderer` | `.../core/agent/context/`, `.../core/agent/impl/orca/` |
| 부트스트랩 | `ExecutionEnvironmentSpec`, `StackAgentRuntimeProvisioner`, `AimonStackBuilder` | `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/` |
| CLI | `AgentSetupFactory` | `modules/aimon-cli/src/main/java/at/aimon/cli/factory/` |
| 스타터 | `AimonAutoConfiguration`, `AimonProperties` | `modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/` |
| 경계 강제 | `PackageDependencyArchitectureTest`, `ToolContextKeysWriteOnceTest` | `modules/aimon-core/src/test/java/at/aimon/core/` |
