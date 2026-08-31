# 코드로 정의하는 서브에이전트 (Code-Defined Registration)

> Status: **IMPLEMENTED** — 코드 레지스트리, 합성 우선순위, 부트스트랩 배선이 모두 들어가 있고,
> 애초에 v1 에서 기각했던 커스텀 실행 동작(§6)도 뒤이어 구현되었다. 남은 것은 §7 — provider SPI 는
> 두 번째 빌트인이 생길 때까지 보류다.
>
> 적용 대상: `aimon-core` — `at.aimon.core.subagent`(`MutableSubagentRegistry`,
> `InMemorySubagentRegistry`, `CompositeSubagentRegistry`, `Subagent.builder()`),
> `…subagent.behavior`(커스텀 실행 동작 8종),
> `at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory`(합성) ·
> `aimon-bootstrap` — `AimonStackBuilder` 조립.

---

## 1. 문제

서브에이전트는 `.aimon/agents/*.md` 마크다운으로만 정의할 수 있었다. 프레임워크가 자기 빌트인
서브에이전트를 싣거나, 임베딩 애플리케이션이 자기 도메인 서브에이전트를 코드로 등록할 경로가 없었다.

핵심 관찰 하나가 설계를 거의 다 정한다 — **`Subagent` 는 출처와 무관한 값 객체다.** 마크다운 파서가
만들든 빌더가 만들든 실행 계층에는 같은 값이 도착한다. 따라서 실행·호출 계층은 **한 줄도 바뀌지
않는다.** 필요한 것은 값을 만들 다른 경로와, 그 경로를 기존 조회에 합성하는 일뿐이다.

---

## 2. 컴포넌트

### 2.1 `MutableSubagentRegistry` — 쓰기 표면 분리

기존 `SubagentRegistry` 는 읽기 전용이다 (`getSubagent` / `getAllSubagents` / `reloadSubagent`).
등록·해제는 별도 인터페이스로 뽑는다.

```java
public interface MutableSubagentRegistry extends SubagentRegistry {
    void register(Subagent subagent);
    Optional<Subagent> unregister(String name);
}
```

CQRS 를 새로 도입한 것이 아니라 이 코드베이스의 선례를 따른 것이다 — `MutableCommandRegistry` 가 같은
형태다. 조회만 필요한 소비자(합성 레지스트리, 실행기)가 쓰기 메서드에 의존하지 않게 한다 (ISP).

### 2.2 `InMemorySubagentRegistry` — 그리고 그 패키지 위치

구현은 `at.aimon.core.subagent` 에 둔다. **`.impl` 이 아니다.** ArchUnit 이 `*.impl` 의 외부 import 를
막는데, 이 레지스트리는 바로 그 외부(부트스트랩, 임베딩 애플리케이션)가 인스턴스를 만들어 등록해야 하는
타입이기 때문이다. 도메인 패키지에 두는 것이 규칙 위반이 아니라 규칙의 의도 그 자체다.

### 2.3 `reload*` 는 안전한 no-op 이어야 한다

이것이 이 설계에서 가장 조용하고 가장 위험한 함정이다.

`CompositeSubagentRegistry.reloadAll()` / `reloadSubagent(name)` 은 **모든 레이어에 무조건 전파**한다.
코드 레지스트리에는 다시 읽을 외부 소스가 없으므로 자연스러운 구현은 "던지거나" "비우거나" 둘 중
하나인데, 둘 다 틀렸다.

- **던지면** — 파일 hot-reload 한 번이 composite 전체를 깨뜨린다
- **비우면** — 파일 hot-reload 한 번이 **코드 정의 서브에이전트를 증발시킨다**

그래서 두 메서드는 상태를 그대로 유지하는 no-op 이다. 클래스 javadoc 과 각 메서드 javadoc 양쪽에 이유가
박혀 있다. 현재 production 에 reload 호출자는 없지만, 그것이 이 제약을 미룰 이유는 못 된다 — 함정은
호출자가 생기는 날 터진다.

### 2.4 `Subagent.builder()` — 마크다운과 동일한 기본값

코드 정의는 **동등한 마크다운과 구별할 수 없어야** 한다. 그래서 빌더의 기본값은 파서의 기본값과 같다
(`maxIterations = 1000` 등). 두 경로가 다른 기본값을 갖는 순간 "코드로 옮겼더니 동작이 달라졌다"는
디버깅 불가능한 클래스의 버그가 생긴다.

### 2.5 합성 지점

`OrcaAgentRuntimeFactory.buildCompositeSubagentRegistry` 가 세 레이어를 합친다.

```java
protected SubagentRegistry buildCompositeSubagentRegistry(Optional<SubagentRegistry> bundledRegistry,
        SubagentRegistry userRegistry, SubagentRegistry codeRegistry) {
    final List<SubagentRegistry> layers = new ArrayList<>();
    bundledRegistry.ifPresent(layers::add);
    layers.add(userRegistry);
    if (codeRegistry != null) {
        layers.add(codeRegistry);
    }
    return new CompositeSubagentRegistry(layers);
}
```

원래는 `private static` 2-arg 였다. 접근 제어자만 여는 것으로는 부족했다 — code 레이어는 인스턴스 필드에서
오므로 **`protected` non-static 으로 바꾸고 세 번째 nullable 파라미터를 추가**했다. `protected` 를 유지한
것은 서브클래스가 합성 정책을 갈아끼울 수 있게 하기 위해서다.

---

## 3. 합성 우선순위 — 코드가 권위를 갖는다

**결정: `[bundled < user < code]`.** 코드 레이어가 리스트 마지막이고, `CompositeSubagentRegistry.getSubagent`
가 **뒤에서부터** 조회하므로 코드 정의가 항상 이긴다. (`getAllSubagents` 는 반대로 앞에서부터
`LinkedHashMap` 에 병합하므로 열거 결과에서도 뒤 레이어가 덮는다.)

**근거는 보안이다.** 프레임워크·애플리케이션이 싣는 코드 빌트인은 큐레이션된 `AllowedTool` 화이트리스트를
갖는다. 사용자가 `.aimon/agents/<같은-이름>.md` 를 놓아 더 넓은 권한으로 그것을 shadow 할 수 있으면,
화이트리스트는 방어가 아니라 장식이 된다.

**트레이드오프는 명시적이다.** Skills 와 Commands 는 "사용자 우선" 관례인데 서브에이전트만 반대가 된다.
이 **의도적 비대칭**은 합성 메서드 javadoc 에 적혀 있고 충돌 테스트로 고정되어 있다 — 나중에 누가
"일관성"을 이유로 순서를 뒤집는 것을 막기 위해서다.

---

## 4. 부트스트랩 배선

코드 레지스트리는 **application-scoped 로 1회** 구성하고 `AgentRuntime` 생성 시 팩토리에 주입한다
(등록은 부트스트랩에서 1회 — CLAUDE.md 스코프 규칙).

실제 배선 지점은 `aimon-bootstrap` 의 `AimonStackBuilder` 다.

```java
final InMemorySubagentRegistry codeSubagentRegistry = new InMemorySubagentRegistry();
final InMemorySubagentBehaviorRegistry codeSubagentBehaviorRegistry = new InMemorySubagentBehaviorRegistry();
…
executorFactory.withSubagentBehaviorRegistry(codeSubagentBehaviorRegistry)
…
runtimeFactory.withCodeSubagentRegistry(codeSubagentRegistry)
```

프레임워크 중립 어셈블리에 두었으므로 CLI·Spring Boot 스타터·임베딩 애플리케이션이 모두 같은 배선을
공유한다. 각 어셈블리가 팩토리를 직접 조립하는 경우에도 enabler 는 팩토리 API
(`withCodeSubagentRegistry` / `withSubagentBehaviorRegistry`)이므로 호출 한 줄이면 된다.

> **happens-before**: 레지스트리 생성 + 모든 `register(...)` 완료 → **그다음** 팩토리에 전달한다.
> 부분적으로 채워진 레지스트리가 합성 시점에 관측되면 안 된다. 부트스트랩이 단일 스레드라 자연히
> 충족되지만 순서 자체가 계약이다.

---

## 5. 제약

| ID | 제약 | 이유 |
|---|---|---|
| **C1** | `InMemorySubagentRegistry.reload*` 는 **안전한 no-op** (throw ❌, clear ❌) | composite 가 reload 를 무조건 전파한다 — §2.3 |
| **C2** | 합성 순서는 `[bundled, user, code]` | 코드 화이트리스트의 un-shadowable 성질 — §3. 충돌 테스트로 고정 |
| **C3** | `Subagent.builder()` 기본값 ≡ 마크다운 파서 기본값 | 코드 정의와 동등 마크다운이 구별 불가해야 한다 — §2.4 |
| **C4** | (provider SPI 도입 시) provider context 에 `OrcaProviderDependencies` 금지 | 닭-달걀 — composite 는 deps 보다 **먼저** 생성된다(deps 가 composite 에 의존). context 는 `Agent` / `Environment` 만 — §7 |
| **C5** | 신규 타입 ArchUnit 배치 | `InMemorySubagentRegistry` · `MutableSubagentRegistry` 는 `at.aimon.core.subagent`(도메인). 향후 SPI 는 `at.aimon.core.agent.orca.subagent`, 빌트인 구현은 `*.impl.orca.subagent` — §2.2 |

---

## 6. 커스텀 실행 동작 — v1 에서 기각했다가 구현한 것

검토한 대안은 셋이었다.

| 대안 | 요약 | 판정 |
|---|---|---|
| **A. 코드 레지스트리 + 합성 레이어** (본 설계) | 신규 레지스트리 + 합성 한 줄 + 부트스트랩 배선. 실행·호출 무변경 | **채택** |
| **B. `AgentBundle` 경로로만 주입** | `AgentBundle.Builder.subagentRegistry`(= bundled 레이어)에 주입, 팩토리 무변경 | 기각 — bundled 는 **최저 우선순위**라 §3 과 정면 충돌. 번들에 이미 레지스트리가 있으면 병합도 필요 |
| **C. 서브에이전트별 커스텀 `SubagentExecutor`** | 코드 서브에이전트가 자체 실행 전략(ReAct 우회 등)을 갖는다 | v1 기각 → **후행 구현** (아래) |

C 의 기각 사유는 세 가지였다 — (a) `Subagent` 값 객체에 행위를 주입하면 불변성이 깨진다, (b) ReAct
실행기가 서브에이전트의 출처를 알게 된다(origin-agnostic 위반), (c) blast radius 가 넓다.

**세 우려는 분리 가능했다.** 행위를 `Subagent` 에 넣는 대신 **이름으로 키잉된 별도 레지스트리**에 두면
된다.

- `SubagentBehaviorRegistry` / `MutableSubagentBehaviorRegistry` / `InMemorySubagentBehaviorRegistry` —
  이름 → 행위. 없으면 `SubagentBehaviorRegistry.empty()`
- `SubagentBehavior` / `SubagentBehaviorRunner` / `SubagentBehaviorRegistrar` /
  `SubagentBehaviorSupport` / `DefaultSubagentBehaviorSupport` — 행위 계약과 실행 지원
- 분기는 `DefaultSubagentExecutionManager` 의 **디스패치 단일 지점** 한 곳

결과적으로 (a) `Subagent` 는 여전히 순수 값 객체이고, (b) ReAct 실행기는 데이터 서브에이전트에 대해
그대로 origin-agnostic 이며, (c) 변경 지점은 매니저 한 곳이다. 행위 레지스트리를 배선하지 않으면
`empty()` 로 해석되므로 기존 동작은 그대로다.

교훈은 기록해 둘 만하다 — 기각 사유가 셋이면 그 셋이 **정말로 한 덩어리인지** 확인할 값어치가 있다.
여기서는 아니었다.

---

## 7. 남은 것 — provider SPI

빌트인 서브에이전트를 각 모듈이 자기 `OrcaSubagentProvider` 로 기여하는 SPI 는 **보류**다. 사용처가
하나뿐인 추상화이고, 두 번째 빌트인이 생기기 전에는 형태를 정할 근거가 없다 (YAGNI).

도입하게 되면 C4 가 첫 제약이 된다 — provider context 에 `OrcaProviderDependencies` 를 실을 수 없다.
composite 레지스트리가 deps 보다 먼저 생성되고 deps 가 composite 에 의존하므로, provider 가 deps 를 들고
다니면 그래프가 닫히지 않는다. context 에 넣을 수 있는 것은 `Agent` 와 `Environment` 뿐이다.

---

## 부록 — 참조 파일 지도

| 파일 | 확인할 것 |
|---|---|
| `subagent/SubagentRegistry.java:56-76` | 읽기 표면 3개 |
| `subagent/MutableSubagentRegistry.java:47,58` | `register` / `unregister` |
| `subagent/InMemorySubagentRegistry.java:13-39,67-81` | 패키지 배치 근거, no-op reload 두 곳의 경고 |
| `subagent/CompositeSubagentRegistry.java:74-80,113-128` | 역순 조회(뒤 레이어 승) · reload 무조건 전파 |
| `subagent/SubagentMetadata.java` | `DEFAULT_MAX_ITERATIONS` 1000 — 빌더/파서 공통 기본값 |
| `agent/impl/orca/OrcaAgentRuntimeFactory.java:376,792,956-965` | `withCodeSubagentRegistry`, 레이어 순서 주석, 합성 메서드 |
| `subagent/behavior/` (8개 파일) | 대안 C 의 후행 구현 |
| `agent/impl/orca/OrcaAgentExecutorFactory.java:367,771-773` | `withSubagentBehaviorRegistry`, null → `empty()` |
| `subagent/DefaultSubagentExecutionManager.java` | 행위 디스패치 단일 지점 |

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준. 배선은
`modules/aimon-bootstrap/…/AimonStackBuilder.java:306-307,347,387`.

---

## 관련 문서

- [`execution.md`](execution.md) — 등록된 서브에이전트를 실제로 돌리는 실행·태스크 관리
- [`subagent-development-guide.md`](../../features/subagent/subagent-development-guide.md) — 마크다운·코드 양쪽 작성법과 "코드-행위(custom behavior)" 절
- [`../skill/command-unification.md`](../skill/command-unification.md) — `MutableCommandRegistry` 선례와 사용자 우선 관례
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 레지스트리는 application-scoped, 등록은 부트스트랩 1회
