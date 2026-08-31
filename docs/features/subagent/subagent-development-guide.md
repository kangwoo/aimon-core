# Subagent Development Guide

> 코드(Java)로 서브에이전트를 정의·등록하기 위한 가이드

이 문서는 `agents/*.md` 마크다운 파일 없이 **Java 코드로 서브에이전트를 정의하고 등록**하는 방법을 설명합니다.
설계 근거와 결정 과정은 [`design/subagent/code-defined-registration.md`](../../design/subagent/code-defined-registration.md)
를 참고하세요.

## 목차

1. [개요](#개요)
2. [언제 코드 정의를 쓰는가](#언제-코드-정의를-쓰는가)
3. [핵심 개념](#핵심-개념)
4. [Subagent.builder() 사용법](#subagentbuilder-사용법)
5. [등록: InMemorySubagentRegistry](#등록-inmemorysubagentregistry)
6. [부트스트랩 배선](#부트스트랩-배선)
7. [합성 우선순위 — 코드 정의가 권위(authoritative)](#합성-우선순위--코드-정의가-권위authoritative)
8. [동작 검증: 자동으로 노출되는 경로](#동작-검증-자동으로-노출되는-경로)
9. [전체 예제](#전체-예제)
10. [코드-행위(custom behavior) 서브에이전트](#코드-행위custom-behavior-서브에이전트)
11. [백그라운드 팬아웃(fan-out) 패턴](#백그라운드-팬아웃fan-out-패턴)
12. [마크다운 vs 코드 정의](#마크다운-vs-코드-정의)
13. [체크리스트](#체크리스트)

---

## 개요

서브에이전트는 `TaskTool`(`Task`)이 호출하는 특화 에이전트입니다. 같은 ReAct 실행 모델을 공유하지만, 자체
시스템 프롬프트와 도구 허용 목록(allow-list)을 갖습니다.

서브에이전트는 **소스(마크다운/코드)에 무관한 불변 값 객체**(`Subagent`)입니다. 따라서 마크다운(`agents/*.md`)으로
정의하든 코드(`Subagent.builder()`)로 정의하든, 실행·훅·취소 전파·도구 권한 게이트·LLM 사용량 귀속이 **완전히 동일하게**
동작합니다.

### 핵심 원칙

| 원칙 | 설명 |
|------|------|
| **Source-agnostic** | 실행 계층은 `Subagent`가 어디서 왔는지 묻지 않는다 |
| **Immutability** | `Subagent`는 불변 값 객체 — builder로 생성 후 변경 불가 |
| **데이터 정의만** | 코드 정의는 프롬프트 + 도구 + 모델/반복/권한 **설정**만 제공한다. 서브에이전트별 커스텀 실행기는 비지원 |
| **Markdown parity** | builder의 기본값은 마크다운 파서와 **동일**(`maxIterations=1000` 등) |
| **Authoritative** | 코드 정의는 동일 이름의 사용자 `.md`로 **shadow 불가** (보안: 큐레이션된 도구 허용목록 보호) |

### 패키지 구조

```
at.aimon.core.subagent/
├── Subagent.java                  # 불변 값 객체 + Subagent.builder()
├── SubagentMetadata.java          # 설명/도구/모델/반복/권한
├── SubagentContent.java           # 시스템 프롬프트
├── SubagentRegistry.java          # 읽기 전용 레지스트리 인터페이스
├── MutableSubagentRegistry.java   # 쓰기(register/unregister) — CQRS
├── InMemorySubagentRegistry.java  # 코드 정의용 in-memory 구현
├── CompositeSubagentRegistry.java # 레이어 합성 (later-wins)
└── DefaultSubagentRegistry.java   # 파일 기반(agents/*.md) 구현
```

---

## 언제 코드 정의를 쓰는가

| 상황 | 권장 |
|------|------|
| 애플리케이션에 **빌트인 제공**할 서브에이전트 (사용자가 못 지우게) | **코드 정의** |
| 큐레이션된 **좁은 도구 허용목록**을 강제해야 함 (보안) | **코드 정의** (사용자 `.md`로 shadow 불가) |
| 빌드/배포 시점에 프롬프트가 결정됨 | **코드 정의** |
| 사용자가 런타임에 자유롭게 추가·수정 | `agents/*.md` (파일 기반) |
| 운영 중 hot-reload로 갱신 | `agents/*.md` (코드 정의는 reload 대상이 아님 — 후술) |

> 코드 정의와 파일 정의는 **공존**한다. 코드 정의는 기존 파일 기반 경로를 전혀 건드리지 않는다.

---

## 핵심 개념

```
[파일 소스]  agents/*.md ─► DefaultSubagentRegistry(user)  ──┐
                                                            │
[번들]       AgentBundle.getSubagentRegistry()  ────────────┤   CompositeSubagentRegistry(
                                                            │     [bundled, user, code])
[코드]       Subagent.builder()...build()                   │          │  ← code 최우선
              └► InMemorySubagentRegistry.register(...) ────┘          ▼
                                          (기존 그대로) TaskTool · SkillFork · /agents · 실행기
```

- **`Subagent`** — 이름 + `SubagentMetadata` + `SubagentContent`로 구성된 불변 값 객체.
- **`InMemorySubagentRegistry`** — 코드로 등록한 `Subagent`를 메모리에 보관하는 `MutableSubagentRegistry` 구현.
- **`CompositeSubagentRegistry`** — 번들/사용자/코드 레지스트리를 한 뷰로 합성. **리스트에서 뒤에 있을수록 높은 우선순위**.

소비자(`TaskTool`, 실행기, `/agents`, skill fork)는 읽기 전용 `SubagentRegistry` 추상화만 보므로, 코드 정의분이
**호출 계층 코드 변경 없이** 자동으로 노출·실행·목록에 포함됩니다.

---

## Subagent.builder() 사용법

```java
import java.util.List;

import at.aimon.core.subagent.Subagent;

Subagent dbTriage = Subagent.builder()
        .name("db-triage")                                   // 필수
        .description("DB 장애 1차 분류. 메트릭/로그 조회 후 원인 후보 좁히기.")  // TaskTool이 LLM에 노출
        .whenToUse("DB 장애가 발생해 1차 분류가 필요할 때")          // 선택 — TaskTool 설명에 트리거로 노출
        .tools(List.of("Read", "Grep", "Bash(psql:*)"))      // 마크다운 allowed-tools 와 동일 파싱
        .model("sonnet")                                     // 모델 별칭
        .maxIterations(50)                                   // ReAct 루프 상한
        .systemPrompt("You are a database triage specialist...")  // 필수
        .build();
```

### Builder 메서드

| 메서드 | 필수 | 미지정 시 기본값 (마크다운 패리티) |
|--------|------|-----------------------------------|
| `name(String)` | ✅ | — (null이면 `build()`가 `NullPointerException`) |
| `systemPrompt(String)` | ✅ | — (null이면 `build()`가 `NullPointerException`) |
| `description(String)` | | `null` |
| `whenToUse(String)` | | `null` (선택 트리거 조건, TaskTool 설명에 노출) |
| `tools(List<String>)` | | 빈 목록 → `hasToolRestrictions() == false` (도구 제한 없음) |
| `model(String)` | | `null` (실행기 기본 모델) |
| `maxIterations(int)` | | `1000` |

> **도구 문자열 포맷**은 마크다운 `allowed-tools` 와 동일하다: `"Read"`, `"Bash(git:*)"`, `"Bash(npm install)"` 등.
> 내부적으로 `AllowedTool.parse(...)`를 거치므로 파싱 로직이 중복되지 않는다.

### 마크다운 패리티 (중요)

builder는 **호출자가 실제로 설정한 필드만** `SubagentMetadata.builder()`로 전달합니다. 따라서 미설정 필드는
마크다운 파서가 의존하는 동일한 기본값으로 남습니다. 아래 두 정의는 **동등한 `Subagent`** 를 생성합니다.

```java
// (1) 코드
Subagent.builder().name("plain").systemPrompt("You are a plain agent.").build();
```

```markdown
<!-- (2) agents/plain.md — 동등 -->
You are a plain agent.
```

둘 다 `maxIterations=1000`, `model=null`, `whenToUse=null`, 도구 제한 없음이 됩니다.

---

## 등록: InMemorySubagentRegistry

`InMemorySubagentRegistry`는 `MutableSubagentRegistry`(읽기/쓰기 분리, CQRS) 구현입니다.

```java
import at.aimon.core.subagent.InMemorySubagentRegistry;

InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
codeSubagents.register(dbTriage);                 // 이름 충돌 시 replace
codeSubagents.register(Subagent.builder().name("log-analyzer").systemPrompt("...").build());

// 필요 시 제거 (제거된 항목 반환, 없으면 Optional.empty())
codeSubagents.unregister("db-triage");
```

- **Thread-safe** — `ConcurrentHashMap` 백킹.
- **reload는 안전한 no-op** — 코드 정의는 "다시 읽을 외부 소스"가 없습니다. `CompositeSubagentRegistry`는 reload를
  **모든 레이어에 무조건 전파**하므로, `InMemorySubagentRegistry.reloadAll()`/`reloadSubagent()`는 상태를 그대로
  유지합니다(throw❌, clear❌). 만약 이 메서드들이 clear한다면, 파일 hot-reload 시 코드 정의가 함께 증발합니다.

> **네이밍 주의**: 서브에이전트의 `Default*`(=`DefaultSubagentRegistry`)는 이미 **파일 기반** 구현이 차지했습니다.
> 따라서 in-memory 구현은 `InMemory*`로 명명합니다 (`DefaultToolRegistry`/`DefaultAgentRegistry`가 in-memory인 것과
> 반대 — 불가피한 비대칭).

---

## 부트스트랩 배선

코드 정의 레지스트리는 **application-scoped**로 부트스트랩에서 1회 구성하고, `OrcaAgentRuntimeFactory`에
`withCodeSubagentRegistry(...)`로 주입합니다.

### CLI (`AgentSetupFactory`)

```java
final InMemorySubagentRegistry codeSubagentRegistry = new InMemorySubagentRegistry();
codeSubagentRegistry.register(Subagent.builder()...build());   // 빌트인 코드 서브에이전트(0개여도 무방)

final OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory(/* ... */)
        .withSkillRegistry(skillRegistry)
        .withCodeSubagentRegistry(codeSubagentRegistry)        // ← 배선
        .withPendingTurnRegistry(pendingTurnRegistry)
        /* ... */;
```

### Web / 임베딩 애플리케이션

aimon-core의 web/session 모듈은 팩토리를 직접 생성하지 않습니다 — 애플리케이션 부트스트랩이
`OrcaAgentRuntimeManager.Builder.agentRuntimeFactory(...)`로 **이미 빌드된** 팩토리를 주입합니다. 따라서 web
경로에서도 팩토리를 만들 때 **동일하게** `withCodeSubagentRegistry(...)`를 호출하면 됩니다.

> ⚠️ **주의 (C2)**: 코드 서브에이전트를 빌트인으로 제공한다면 **모든 부트스트랩**(CLI + web)에서 배선해야 합니다.
> 한쪽만 배선하면 그 transport에서만 코드 서브에이전트가 조용히 누락됩니다.

### happens-before

빌드 + 모든 `register(...)` 완료 → **그 다음** `withCodeSubagentRegistry(...)`로 전달하세요. 부분 채워진 레지스트리가
합성 시점에 관측되지 않도록 합니다. (단일 스레드 부트스트랩이면 자연 충족.)

---

## 합성 우선순위 — 코드 정의가 권위(authoritative)

합성 순서는 **`[bundled < user < code]`** 이며, 리스트 마지막인 **코드 레이어가 최고 우선순위**입니다.
`CompositeSubagentRegistry.getSubagent`는 뒤에서부터 조회하고, `getAllSubagents`는 뒤 레이어가 앞을 덮어쓰므로,
동일 이름이 있으면 **코드 정의가 항상 이깁니다**.

| 레이어 | 출처 | 우선순위 |
|--------|------|----------|
| bundled | `AgentBundle.getSubagentRegistry()` | 최저 |
| user | `agents/*.md` (`DefaultSubagentRegistry`) | 중간 |
| **code** | `InMemorySubagentRegistry` | **최고 (un-shadowable)** |

### 의도적 비대칭

Skills/Commands는 **사용자 우선** 관례인데, 서브에이전트만 **코드 우선**으로 반대입니다. 이유는 **보안**입니다:
큐레이션된 도구 허용목록을 가진 코드 빌트인을, 사용자가 `.aimon/agents/*.md`로 더 넓은 권한을 주며 덮어쓰는 것을
차단합니다.

---

## 동작 검증: 자동으로 노출되는 경로

코드 서브에이전트는 다음 경로에 **호출 계층 코드 변경 없이** 자동 포함됩니다.

- **`TaskTool`(`Task`)** — `subagent_name` 입력에 enum 제약이 없고, 도구 설명이 `registry.getAllSubagents()`로 매 호출
  동적 생성되므로 코드 등록분이 LLM에 노출됩니다.
- **`/agents` 명령** — 합성 레지스트리 목록을 그대로 보여줍니다.
- **Skill fork** — fork 대상 에이전트 이름으로 위임 시 합성 레지스트리에서 resolve됩니다.
- **직접 `@subagent` 호출** — 동일.

---

## 전체 예제

```java
import java.util.List;

import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

// 1) 코드 서브에이전트 정의
Subagent dbTriage = Subagent.builder()
        .name("db-triage")
        .description("DB 장애 1차 분류 전문가. 느린 쿼리/락 경합 진단 시 사용.")
        .whenToUse("느린 쿼리·락 경합 등 DB 장애 1차 분류가 필요할 때")
        .tools(List.of("Read", "Grep", "Bash(psql:*)"))   // 읽기 + psql 만 — 좁은 허용목록
        .model("sonnet")
        .maxIterations(50)
        .systemPrompt("""
                You are a database triage specialist.
                1. Inspect slow-query and lock metrics.
                2. Narrow down likely root causes.
                3. Report findings; do NOT mutate production data.
                """)
        .build();

// 2) 레지스트리 구성 + 등록 (application-scoped, 부트스트랩 1회)
InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
codeSubagents.register(dbTriage);

// 3) 팩토리에 주입 (CLI · web 동일)
OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory(/* ... */)
        .withCodeSubagentRegistry(codeSubagents);

// 이후: Task(subagent_name="db-triage", ...) 로 호출 가능, /agents 목록에도 노출
```

---

## 코드-행위(custom behavior) 서브에이전트

지금까지는 **코드 정의**(데이터: 프롬프트 + 도구 + 설정)였고, 실행은 LLM ReAct 루프였다. 한 단계 더 나아가
서브에이전트의 **행위 자체를 Java 코드로 구현**(ReAct 루프를 우회하는 결정론적/커스텀 로직)할 수도 있다.

> 순수 코드 로직이 필요하면 일반적으로 **Tool**이 1차 선택지다. 코드-행위 서브에이전트는 "서브에이전트로서
> 호출되지만(`Task`/`/agents`/`@name`에 노출), 내부 동작은 LLM 루프 대신 코드"가 필요할 때 쓴다.

### 개념: 데이터 + 행위 페어

코드-행위 서브에이전트는 **같은 이름의 페어**다.

| 조각 | 레지스트리 | 역할 |
|------|-----------|------|
| `Subagent` 데이터 엔트리 | `SubagentRegistry` (코드 레이어) | 발견(TaskTool 목록)·description·**도구 허용목록** |
| `SubagentBehavior` | `SubagentBehaviorRegistry` | 실행 — ReAct 루프를 **대체** |

실행 매니저(`DefaultSubagentExecutionManager`)는 디스패치 시 "이 이름에 등록된 행위가 있나?"를 확인해, 있으면
행위를, 없으면 기존 ReAct 루프를 실행한다. 데이터 서브에이전트 경로는 **무변경**이다(origin-agnostic 보존).

> ⚠️ 행위만 등록하고 데이터 엔트리가 없으면, 매니저가 이름을 resolve하지 못해 `SubagentNotFoundException`으로
> 실패한다(fail-fast). 항상 **페어**로 등록하라 — `SubagentBehaviorRegistrar`가 한 번에 처리한다.

### `SubagentBehavior` SPI

```java
@FunctionalInterface
public interface SubagentBehavior {
    SubagentExecutionResult execute(SubagentExecutionContext context, SubagentExecutionRequest request,
            SubagentBehaviorSupport support);
}
```

- ReAct 실행기와 **동일한** `SubagentExecutionContext`(서브에이전트·도구/훅 레지스트리·환경·취소 신호·knowledge)
  와 `SubagentExecutionRequest`(goal·principal·attributes·metadata)를 받고, **동일한** `SubagentExecutionResult`를
  반환한다 → `TaskTool`/백그라운드 소비자는 차이를 알 수 없다.
- `support`(`SubagentBehaviorSupport`)는 취소 신호와 결과 빌더를 제공한다: `cancellationSignal()`,
  `isCancelledOrInterrupted()`, `success(finalAnswer)`, `failure(errorMessage)` — conversation snapshot/metadata를
  직접 구성할 필요가 없다.
- 구현체는 `context.getToolRegistry()`/`getEnvironment()` 등으로 도구·LLM을 **선택적으로** 쓸 수 있으나, 기본
  기대값은 순수 코드다. `Tool.execute()`처럼 throw 대신 `support.failure(...)` 반환을 권장한다(러너가 throw/null도
  failure로 셰이핑하는 안전망 제공).

### LLM 호출 + ReAct 패리티 입력 (선택)

코드 behavior는 결정론적 로직이 기본이지만, 모델을 직접 호출할 수도 있다. `support`가 **ReAct 경로와 동일하게
해석된 입력**을 제공한다 — `getDefaultModel()`/`getToolRegistry()`의 raw 값이 아니라:

| `support` accessor | ReAct가 쓰는 값과 동일? | 설명 |
|--------------------|--------------------------|------|
| `resolvedModel()` | ✅ | 서브에이전트 `model` 별칭(예: `sonnet`)을 default와 병합한 **해석된 모델**. (raw `ctx.getDefaultModel()`은 별칭 미반영) |
| `scopedToolRegistry()` | ✅ | 서브에이전트 allow-list로 필터된 registry (**노출만, 강제 아님** — trusted code는 `ctx.getToolRegistry()`로 전체 접근 가능) |
| `effectiveLlmCallMetadata()` | ✅ | 서브에이전트 사용량 귀속 metadata (component=이름, feature="subagent") |
| `llmGateway()` | ✅ | ReAct와 동일 config(기본 재시도, 폴백 없음)의 게이트웨이. `LlmClient` 미배선 시 `Optional.empty()` |

```java
public SubagentExecutionResult execute(SubagentExecutionContext ctx, SubagentExecutionRequest req,
        SubagentBehaviorSupport support) {
    var gw = support.llmGateway().orElseThrow();          // 미배선 시 Optional.empty()
    var model = support.resolvedModel();                  // 서브에이전트 해석 모델
    List<ToolDefinition> tools = support.scopedToolRegistry().findAll().stream()
            .map(Tool::getDefinition).toList();           // allow-list 스코프

    // (A) 간단 — 재시도/폴백 적용, 단 사용량 귀속 없음
    LlmResponse a = gw.sendMessage("You are ...", messages, tools, model);

    // (B) 귀속까지 — parts 오버로드 + effectiveLlmCallMetadata()
    SystemPromptParts parts = SystemPromptParts.of(List.of(SystemPromptPart.builder()
            .content("You are ...").staticness(Staticness.STATIC).kind("system").build()));
    LlmResponse b = gw.sendMessage(parts, messages, tools, model, support.effectiveLlmCallMetadata());

    return support.success(b.getTextContent());
}
```

> raw `ctx.getDefaultModel()`은 서브에이전트의 `model` 별칭을 **반영하지 않으며**, `ctx.getToolRegistry()`는
> allow-list가 **적용되지 않은** 전체 registry다. ReAct와 동일하게 동작하려면 `support.resolvedModel()` /
> `support.scopedToolRegistry()`를 사용하라.

### 등록 + 배선

```java
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.behavior.SubagentBehaviorRegistrar;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;

InMemorySubagentRegistry codeData = new InMemorySubagentRegistry();
InMemorySubagentBehaviorRegistry codeBehavior = new InMemorySubagentBehaviorRegistry();

// 데이터 엔트리 + 행위를 같은 이름으로 함께 등록 (이름 드리프트 방지)
SubagentBehaviorRegistrar.register(
        Subagent.builder().name("clock").description("현재 서버 시각을 반환. 시간 조회가 필요할 때 사용.")
                .systemPrompt("(code behavior)").build(),
        (ctx, req, support) -> support.success("Current server time: " + java.time.Instant.now()),
        codeData, codeBehavior);

// 배선: 데이터는 context factory, 행위는 executor factory
factory.withCodeSubagentRegistry(codeData);                  // OrcaAgentRuntimeFactory
agentExecutorFactory.withSubagentBehaviorRegistry(codeBehavior);  // OrcaAgentExecutorFactory
```

> `systemPrompt`는 데이터 엔트리의 필수 필드라 무엇이든 채워야 하지만, 행위가 ReAct 루프를 대체하므로 코드-행위
> 서브에이전트에선 실제로 사용되지 않는다(플레이스홀더로 둔다).

### 제한: OnStart/OnStop 훅 미발화

코드 경로는 ReAct 루프를 우회하므로, 루프 내부 훅인 **OnStart/OnStop은 발화되지 않는다**(OnStart의 대화-피드백
주입은 대화 루프가 있어야 의미가 있고, OnStop 종료 신호는 SubagentStop과 중복이다). 디스패치 경계 훅인
**SubagentStart/SubagentStop은 그대로 발화**되므로 옵저버빌리티/감사에는 손실이 없다.

### 전체 예제

```java
public final class ClockSubagentBehavior implements SubagentBehavior {
    @Override
    public SubagentExecutionResult execute(SubagentExecutionContext ctx, SubagentExecutionRequest req,
            SubagentBehaviorSupport support) {
        if (support.isCancelledOrInterrupted()) {
            return support.failure("Execution interrupted");
        }
        return support.success("Current server time: " + java.time.Instant.now());
    }
}
```

---

## 백그라운드 팬아웃(fan-out) 패턴

여러 서브에이전트를 **동시에** 돌려 한 턴의 벽시계 시간을 줄이고 싶을 때가 있습니다. 이때 `Task` 툴을
`CONCURRENT_SAFE`로 만들어 foreground에서 병렬화하려는 시도는 하지 마세요 — `Task`는
`EXTERNALLY_TERMINATED` 인터럽트 동작을 가지므로 병렬 게이트(§도구 병렬 실행 가이드)에서 **자동 제외**됩니다.
팬아웃은 **백그라운드 경로**로 표현합니다.

### 메커니즘

1. **여러 개를 백그라운드로 던진다.** `Task(subagent_name=..., run_in_background=true)`는 즉시 반환하며
   **Task ID**를 돌려줍니다. 서브에이전트는 독립 스레드에서 ReAct 루프를 돕니다.
2. **진행/완료를 폴링한다.**
   - `TaskList` — 진행 중·완료된 백그라운드 태스크를 나열(상태 포함).
   - `AgentOutput(task_id=...)` — 특정 태스크의 라이브 출력과 최종 결과를 오프셋 기반으로 읽음.
   - `TaskStop(task_id=...)` — 필요 시 태스크를 중단(`KILLED` 전이 + 부분 결과 보존).
3. **결과를 취합한다.** 모든 관심 태스크가 완료되면 `AgentOutput`으로 각 결과를 모아 다음 단계로 진행합니다.

> **참조 철학과의 정합**: "coordinator는 모두 async" — 조정자(부모)는 블로킹하지 않고 여러 작업을 던진 뒤
> 폴링으로 수렴합니다.

### 완료 push 알림 (G11) — 폴링에 의존하지 않아도 됨

백그라운드 태스크가 터미널 상태(COMPLETED/FAILED/KILLED)에 도달하면 프레임워크가 **부모에게 능동적으로 알립니다.**
`TaskList`/`AgentOutput` 폴링은 여전히 유효하지만, 알림 덕분에 폴링 루프를 촘촘히 돌 필요가 없습니다. 두 채널이
동시에 발화합니다:

1. **메시지 큐 전달(모델 인지 보장).** 완료 시 부모의 메시지 큐에 `NEXT` 우선순위 알림이 push되고, 부모의 다음
   ReAct iteration에서 `<system-reminder>`로 감싸진 user 턴으로 주입됩니다. 본문은 어떤 태스크가 어떤 결과로
   끝났는지와 **전체 결과를 `AgentOutput(taskId=...)`로 회수**하라는 안내를 담은 평문입니다. 즉, 부모가 idle
   이었더라도 다음 턴에 반드시 완료 사실을 인지합니다.
2. **`agent.stream` 이벤트(라이브 표시/관측).** 같은 시점에 `SubagentTaskCompleted` 이벤트가 부모 executor의
   이벤트 스트림으로 방출됩니다. CLI는 완료 라인을 즉시 출력하고, web 임베딩은 SSE로 push할 수 있습니다. 단 이
   채널은 best-effort입니다 — 부모 턴이 idle이라 리스너가 없으면 드롭되며, 이때 모델 인지는 (1)이 보장합니다.

> 실무 팁: 여러 개를 백그라운드로 던진 뒤, **완료 알림이 주입될 때까지 다른 유용한 일을 계속**하세요. 알림이
> 도착하면 해당 `taskId`로 `AgentOutput`을 호출해 전체 결과를 취합하면 됩니다. 빡빡한 `TaskList` 폴링 루프는
> 더 이상 필수가 아닙니다.

### 유한 풀로 상한 (중요)

백그라운드 팬아웃은 무제한이 아닙니다. `SubagentBackgroundConfig`가 **동시 실행 백그라운드 서브에이전트 수를
상한**합니다. 이를 설정하지 않으면 예전처럼 무제한 캐시 스레드풀이 되어, 폭발적 팬아웃이 스레드/LLM 트래픽을
무한 생성할 수 있습니다.

```java
import at.aimon.core.subagent.SubagentBackgroundConfig;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorFactory;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptManager;

// 동시 4개까지만 실행, 나머지는 대기(큐는 사실상 무제한 — 버스트를 거부하지 않고 지연)
TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore);
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withSubagentBackgroundConfig(SubagentBackgroundConfig.of(4))
        .create(llmClient, transcriptManager);

// 포화 시 아예 거부(load shedding)하려면 큐 용량도 명시
// SubagentBackgroundConfig.of(4, 16);  // maxConcurrency=4, queueCapacity=16
```

- **기본값**(`SubagentBackgroundConfig.defaults()`): `maxConcurrency = min(4, availableProcessors)`, 큐 무제한.
  → thread/LLM fan-out은 상한하되 버스트는 거부하지 않음("결국 전부 실행").
- **큐를 명시**하면 포화 시 초과분을 거부해 load-shedding으로 동작합니다.
- 상한을 넘는 스폰은 **거부되지 않고 큐에서 대기**하다가 슬롯이 나면 실행됩니다(기본 설정 기준).

### 언제 쓰나

- **독립적인** 여러 하위 조사/수집을 동시에 진행하고 싶을 때(서로 의존 없음).
- 각 서브에이전트가 오래 걸려 순차 실행이 벽시계를 지배할 때.
- 상한 유한 풀이 자원 폭주를 막아주므로, 팬아웃 폭이 커도 안전하게 수렴합니다.

> 백그라운드 라이프사이클(조회/나열/중단)·라이브 출력·결과 경계·취소·multi-instance 공유의 전체 설계는
> `docs/design/subagent/execution.md`(§5.1~5.3)를 참조하세요.

---

## 마크다운 vs 코드 정의

| 항목 | `agents/*.md` | `Subagent.builder()` |
|------|---------------|----------------------|
| 정의 위치 | 파일 시스템 | Java 코드 |
| 우선순위 | user 레이어 (중간) | code 레이어 (**최고**) |
| 사용자가 shadow 가능? | 예 (동명 파일로) | **아니오** |
| hot-reload | 지원 (`reloadAll`) | 해당 없음 (no-op) |
| 도구 파싱 | `AllowedTool.parse` | `AllowedTool.parse` (동일) |
| 기본값 | `SubagentMetadata` 기본 | **동일** |
| 실행 모델 | ReAct | ReAct (동일) |

---

## 체크리스트

코드 서브에이전트를 추가할 때 확인하세요.

### 정의
- [ ] `name`·`systemPrompt`를 설정했는가? (둘 다 필수)
- [ ] `description`이 "언제·어떻게 사용하는지"를 LLM에 설명하는가?
- [ ] 도구 허용목록이 **필요한 최소 범위**로 좁혀졌는가? (보안)
- [ ] 마크다운 패리티가 필요하면 미지정 필드를 그대로 두었는가?

### 등록 & 배선
- [ ] `InMemorySubagentRegistry`를 **application-scoped**로 1회 구성했는가?
- [ ] 모든 `register(...)` 완료 후 `withCodeSubagentRegistry(...)`로 전달했는가?
- [ ] **CLI + web 모든 부트스트랩**에서 배선했는가? (C2 — 한쪽만 하면 누락)

### 검증
- [ ] `getAllSubagents()`(=`TaskTool`이 읽는 목록)에 포함되는가?
- [ ] 동일 이름의 사용자 `.md`보다 코드 정의가 우선하는가?

---

## 관련 문서

- [코드 기반 서브에이전트 등록 설계](../../design/subagent/code-defined-registration.md) — 설계 근거·대안 검토·제약
- [Tool 개발 가이드](../tool/tool-development-guide.md) — 서브에이전트가 사용하는 Tool
- [SOLID 원칙](../../project/solid-principles.md)
- [빌트인 Agent/Skill 가이드](../skill/builtin-agent-skill-guide.md) — 빌트인 vs 사용자 오버라이드
