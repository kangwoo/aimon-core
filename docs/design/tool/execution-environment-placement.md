# 실행 환경 배치 입력 — 제공자가 어디서 돌지를 정하는 값

> **Status**: IMPLEMENTED — `aimon-core`, `aimon-graaljs`, `aimon-bootstrap`, `aimon-spring-boot-starter`. 열린 항목의 정본은 [등록부](../../backlog/execution-environment-open-items.md)다.

## 1. 정의 속성과 실행 속성

`ExecutionEnvironmentProvider.resolve(EnvironmentRequest)` 는 실행의 파일과 셸이 어디 있을지 정한다. 요청의 정체성과 계보
(`parent`, `branchKey`, `fork`)는 [실행 환경](execution-environment.md)에 있고, 이 문서는 나머지 입력 둘을 다룬다.

| 입력 | 어디서 오는가 | 실행마다 달라지는가 | 코어가 읽는가 |
|---|---|---|---|
| `definitionAttributes()` | 에이전트 또는 서브에이전트 정의의 `attributes` | 아니다. 정의에서 와서 실행마다 같다 | 아니다. 싣기만 한다 |
| `requestAttributes()` | 이번 실행의 호출자(호스트)가 붙인 `environment.*` | 그렇다 | 아니다. 싣기만 한다 |

후자가 없으면 제공자는 정의나 세션으로만 배치를 정할 수 있고, 실행 id 가 실행 안에서 발급되는 루틴은 표현할 길이 없다.

키 이름은 제공자가 정한다(`sandbox.slot`). 접두사를 떼므로 제공자는 두 맵에서 같은 이름을 읽고, "실행이 정의를 덮는다" 같은
층을 키를 고쳐 쓰지 않고 쌓을 수 있다. 어떻게 쌓을지는 제공자의 정책이다.

## 2. 설계 결정

| 쟁점 | 결정 | 이유 | 기각한 대안 |
|---|---|---|---|
| 실행 속성의 범위 | `executionAttributes` 중 예약 접두사 `environment.` 의 부분집합만 넘긴다 | 옵트인이 명시적이다 | 맵 전체를 `Map<String, Object>` 로 넘긴다. 제공자가 호출자 클래스에 의존하고, 기능 플래그와 A/B 키가 배치 입력이 된다 |
| 접두사를 뗄 것인가 | 뗀다 | 두 맵에서 같은 개념을 같은 이름으로 읽는다 | 접두사를 둔 채 넘긴다. 제공자가 같은 개념을 두 철자로 쓴다 |
| 통로의 모양 | 접두사 + 기존 `executionAttributes` | 새 채널과 코덱이 필요 없다 | `SubmitOptions.environmentAttributes(Map<String,String>)` 전용 필드. 타입은 낫지만 `executionAttributes` 옆의 둘째 채널이고 세 세션 인박스 코덱과 `SubmitOptionsCodec` 을 고쳐야 한다. 접두사를 `aimon.environment.` 로. 충돌은 줄지만, 드러나는 범위가 코어가 문서화하거나 쓴 적 없는 이름 공간에 그친다 |
| 값의 타입 | `String` 만. 어기면 오류 | §4 | 암묵 변환, 건너뛰기 + WARN. §4 |
| 어디서 검증하는가 | 실행기가 추출할 때 | 속성은 `SubmitOptions` 말고도 요청 빌더, 코덱 재생, 루틴 해석기로 들어온다 | `SubmitOptions.Builder.build()` 에서 검증한다. 유일한 검사가 될 수 없고, 같은 오류를 앞당길 뿐이며, `agent` 가 `environment` 를 부르면 패키지 순환 위험이 있다 |
| 포크에 물려줄 것인가 | 물려주지 않는다 | 포크의 배치는 `parent` 의 몫이다. 물려주면 포크가 부모와 다른 격리 단위를 청할 수 있다. 명시적 옵트인은 [EE-104](../../backlog/execution-environment-open-items.md)로 열려 있다 | `requestAttributes` 를 포크로 상속한다 |
| 루틴의 속성은 어디서 오는가 | 호스트 해석기 `RoutineExecutionAttributes` | 루틴은 모델이 `ScheduleTaskTool` 로 만들므로 `ScheduledTask` 필드는 모델이 쓸 수 있는 필드가 되거나 한 턴의 배치를 모든 발화에 얼린다 | `ScheduledTask` 의 영속 필드. 위 이유에 더해 영속 애그리게이트와 모든 코덱이 바뀐다. 해석기도 호스트가 가진 태스크별 데이터를 읽을 수 있다 |
| 단계의 속성을 어떻게 고르는가 | 등록 정의에서 **속성만** 복사한다. 이름·프롬프트·모델·도구는 단계의 것 | 스크립트의 `agentType` 이 등록 이름과 우연히 겹친다고 프롬프트나 도구가 바뀌면 안 된다 | `agentType` 과 같은 이름의 정의를 통째로 쓴다. 프롬프트, 도구, 이름이 바뀌고 재개 캐시가 빗나간다 |
| 호출자의 속성을 단계가 상속할 것인가 | 하지 않는다. 비면 제공자의 포크 기본을 따른다 | 포크의 `parent` 가 이미 호출자의 환경이고 제공자의 기본이 "부모와 같은 샌드박스" 다 | 새 write-once 키로 호출자의 정의 속성을 상속한다. 실행기 둘과 키 하나가 늘고, 속성 없는 등록 정의가 "상속" 인지 "빈 값" 인지 모호해진다 |
| 내장 `Workflow` 의 입력 | `WorkflowInput` 에 `attributes` 를 두지 않고 `WorkflowJs` 의 설명에도 광고하지 않는다 | 코드가 어디서 도는지는 운영자 정책이지 모델의 결정이 아니다. 매 턴 보이는 스키마도 넓어진다 | LLM 이 보는 `attributes` 매개변수 |
| 내장 `Workflow` 의 속성 출처 | 역할 이름으로 등록된 정의 | 단계의 배치가 호출 위치와 무관하게 등록 정의로 정해진다 | `OrcaSubagentToolProvider` 에서 에이전트 속성을 넘긴다. 레지스트리를 포크와 나눠 쓰므로 슬롯 X 의 포크가 부른 `Workflow` 의 단계가 메인 에이전트의 슬롯으로 간다 |
| 역할 이름의 단위 | 역할당 하나 | 역할을 배치하기에 충분하다 | 관점마다 이름(`workflow-perspective-risk`). 관점은 모델이 쓰는 자유 텍스트라 모델이 고른 이름을 끝없이 찾게 된다 |
| 조회를 어디에 두는가 | 기본 해석기 `InlineSubagentResolver` 안 | 사용자 정의 해석기가 감쌀 수 있는 구현 하나에 "서술자의 뜻" 이 모인다 | `WorkflowBindings` · `AgentTaskMarshaller` 에서 조회한다. 사용자 정의 해석기가 조용히 조회를 잃는다 |
| 해석기 SPI 의 모양 | `resolve(SubagentDescriptor)` | 필드를 더해도 외부 구현이 깨지지 않는다 | 위치 인자 `attributes` 를 하나 더한다 |
| 스크립트가 등록 키를 지울 수 있는가 | 없다. `null` 값은 정의 파일처럼 거절한다 | 규칙이 정의 파일과 같다 | 값 `null` 로 지운다 |
| 고정 규칙의 자리 | graaljs 의 `InlineSubagentResolver`. `DefinitionAttributes.overlay` 는 범용 override 승리 규칙으로 둔다 | 고정은 병합 규칙이 아니라 누가 덮는가에 대한 graaljs 정책이다 | `overlay` 에 고정을 넣는다 |

## 3. 정의 속성

`AGENT.md` 와 `agents/*.md` 의 `attributes` 블록은 `DefinitionAttributes.fromFrontmatter` 한 곳에서 읽는다. 중첩은 점 표기로
펼치고 스칼라는 글자로 바꾼다. 값이 조용히 사라지면 샌드박스가 기본으로 골라지고 아무도 이유를 모르므로, 리스트, `null`, 빈 중첩
맵, 빈 키, 값이면서 그룹인 키는 오류다. 따옴표 없는 값은 YAML 1.1 이 먼저 타입을 입히므로(`010` → `8`, `on` → `true`) 평범한
텍스트가 아닌 값은 따옴표로 감싼다.

`definitionAttributes()` 는 포크면 포크의 것, 아니면 에이전트의 것, 둘 다 없으면 빈 맵이다. 제공자는 이것을 읽는다. `agent()`
만 읽으면 모든 포크가 메인 턴의 슬롯에 들어가는데 아무것도 실패하지 않는다. 포크의 값은 메인 턴의 값으로 폴백하지 않는다.
포크의 값은 `DefaultSubagentExecutor` 가 `ForkDefinition` 에 복사하며, 프롬프트에 렌더되지 않는다.

## 4. `environment.*` 규칙

`EnvironmentRequestAttributes.extract` 한 곳에서 추출한다. 메인 턴과 루틴이 같은 맵을 두 가지로 읽을 수 없게 하기 위해서다.
`SubmitOptions.executionAttribute("environment.sandbox.workspace", "ws-7")` 은
`requestAttributes().get("sandbox.workspace")` 로 도착한다.

값은 `String` 만 받는다(`instanceof String`). `null` · 비문자열 · 빈 나머지는 오류이며, 불리언도 `"true"` 로 쓴다. 암묵 변환은 값
표기를 호출자 클래스의 `toString()` 에 묶고(`Double` → `"1.0"`), 건너뛰기는 타입 오타로 배치 의도를 조용히 잃게 한다.

**호환 대가.** 접두사를 예약하므로 이미 `environment.*` 에 비문자열을 싣던 호스트는 그 실행의 파일 · 셸 도구가 실패한다. 조용히
건너뛰면 그런 호스트는 살지만 "붙은 것이 없으면 거절" 이 타입 오타로 뚫리므로, 배치 입력에서 더 나쁜 실패인 누락을 택하지 않는다.

`EnvironmentRequest` 의 `toString()` 은 키 이름만 찍고 값은 찍지 않는다. 값은 테넌트나 워크스페이스 식별자일 수 있고, 요청은
실패할 때 WARN 으로 로그에 남는다.

## 5. 경로별 표

| 경로 | `requestAttributes` |
|---|---|
| 메인 턴(슬래시 커맨드와 리플레이 턴 포함. 같은 `execute` 를 지난다) | `extract(executionRequest.getExecutionAttributes())`. `SubmitOptions` 가 여기 도착한다 |
| 스케줄 루틴 | 호스트 해석기의 답에서(§6). 해석기가 없으면 빈 맵 |
| 서브에이전트 · 스킬 포크, 워크플로 단계 | **언제나 비어 있다.** 포크의 `executionAttributes` 는 부모의 `environment.*` 를 여전히 싣고 포크의 `ToolContext` 로 가지만 배치에는 쓰이지 않는다 |
| 워크플로 러너의 부모 없는 격리 단계 | 비어 있다. 부모 환경을 넘기면 속성은 군더더기이고 속성으로 새로 해석하면 부모와 다른 답이 나올 수 있어, [EE-30](../../backlog/execution-environment-open-items.md)을 고칠 때 함께 정한다 |
| CLI 의 작업 디렉터리 조회(`AgentSetupFactory.workingDirectoryOf`) | 비어 있다. 어떤 실행에도 속하지 않는다 |

메인 턴의 요청에는 실행 id 가 없다. 제공자가 이 값을 같은 턴의 다른 관측과 맞출 키는 세션뿐이고, 무엇을 실을지는
[EE-105](../../backlog/execution-environment-open-items.md)로 열려 있다.

## 6. 루틴 해석기

```java
@FunctionalInterface
public interface RoutineExecutionAttributes {
    RoutineExecutionAttributes NONE = (task, executionId) -> Map.of();
    Map<String, Object> resolve(ScheduledTask task, ExecutionId executionId);
}
```

루틴의 속성은 영속 필드가 아니라 호스트 해석기가 준다. 루틴은 모델이 `ScheduleTaskTool` 로 만들므로 `ScheduledTask` 필드는 모델이
쓸 수 있는 필드가 되거나 한 턴의 배치를 모든 발화에 얼린다. 해석기는 발화 노드의 실행 스레드에서 실행당 한 번 불리므로 빨라야
한다.

- **호출 위치.** `RoutineExecutor` 가 실행 id 를 발급한 뒤, 첫 단계 전에 부른다. 런타임이 없거나 제공자가 없어도 한 번은 불린다
- **답의 쓰임.** `environment.*` 부분이 요청의 `requestAttributes` 가 되고, 맵 전체가 단계의 `ToolContext` 에
  `EXECUTION_ATTRIBUTES_KEY` 로 실린다. 비어 있으면 키를 넣지 않는다. 읽는 쪽은 모두 없음과 빈 맵을 같게 읽는다
- **타입.** `Map<String, Object>` 로 `SubmitOptions` 와 같다. 호스트가 턴과 루틴에 맵 하나를 쓸 수 있고 추출 규칙이 하나다

`RoutineExecutor` 를 만드는 곳은 운영 코드에서 `SchedulingEngineBuilder.build()` 하나이고 엔진이 그것을 감춘다. 그래서 세 층으로
잇는다.

| 층 | 표면 |
|---|---|
| 코어 | `SchedulingEngineBuilder.routineExecutionAttributes(RoutineExecutionAttributes)` |
| 부트스트랩 | `SchedulingSpec.withRoutineExecutionAttributes(...)`, `AimonStackBuilder` 가 전달 |
| 스타터 | `RoutineExecutionAttributes` 빈. 백엔드가 `none` 인데 이 빈이 있으면 시작이 실패한다. 다른 스케줄링 빈과 같은 규칙이다 |

## 7. 신뢰 경계

실행 속성은 호스트가 채운다. 도구 입력이 복사되는 길은 없다. `TaskTool` 과 `WorkflowTool` 은 부모의 맵을 그대로 옮긴다.

1. `environment.*` 는 배치 입력이다. 호스트는 모델 출력(도구 인자, 모델이 쓴 루틴 매개변수, 워크플로 스크립트 값)으로 채우지 않는다
2. 포크 경로는 이것을 읽지 않는다. 모델이 띄운 포크도 이것으로 배치를 고르지 못하고 부모가 받은 것을 넓히지 못한다
3. 루틴은 `ScheduledTask` 가 아니라 호스트 해석기에서 받는다
4. 요청은 `createToolContext` 전에 `executionRequest` 에서 만든다. `EXECUTION_ATTRIBUTES_KEY` 는 write-once 가 아니어서 enricher 가
   고칠 수 있고 도구는 고친 값을 보지만, 제공자는 원래 값을 본다
5. `WorkflowInput` 에 `attributes` 를 두지 않고 `WorkflowJs` 의 설명에도 광고하지 않는다. 필드는 동작하고 운영자가 쓴 스크립트가 쓸 수
   있지만 모델에게 내주지 않는다

재생은 호스트가 원래 제출한 `SubmitOptions` 를 쓰므로 이 경계를 넘지 않는다.

## 8. 실행 속성의 실패 처리

실패는 호스트가 코어가 표현하지 못하는 배치를 요청했다는 뜻이다. 기본 배치로 돌면 "붙은 것이 없으면 거절" 을 조용히 무시하게 되고,
실행 전체를 실패시키면 `resolve` 쪽에서 유일하게 그렇게 하는 오류가 된다. 그래서 사용 불가 환경으로 간다.

| 실패 | 처리 |
|---|---|
| 접두사 아래 비문자열(`null` 포함) 또는 키가 `environment.` 뿐 | `extract` 가 던지고 환경은 `UnavailableExecutionEnvironment` 다. 제공자를 부르지 않는다. 키와 클래스만 담은 WARN 하나를 남기고 값은 찍지 않는다. 실행은 계속되고 파일 · 셸 도구가 원인과 함께 실패하며, 서술자의 `notes` 가 첫 도구 호출 전에 알린다 |
| 접두사 밖의 `null` 키나 값 | 건너뛴다. 배치 입력이 아니다 |
| 루틴에서 같은 경우 | 그 실행의 환경이 사용 불가다. 루틴은 호스트로 돌아가지 않는다 |
| 해석기가 던지거나, `null` 을 돌려주거나, `Map.copyOf` 가 거절하는 맵(`null` 키·값)을 돌려준다 | 호출을 한 `try` 로 감싸 빈 속성과 원인으로 바꾼다. 환경은 사용 불가이고 단계에는 속성 키가 없다. 실행은 중단되지 않는다 |
| 런타임 없음, 제공자 없음, 해석기 실패가 겹친다 | 런타임 없음 → 제공자 없음 → 해석기 실패 순으로 원인이 된다. 해석기 실패는 어느 쪽이든 WARN 으로 남는다 |
| 제공자가 속성을 거부한다(없는 워크스페이스, 붙은 것 없음 + 거절 요청) | 제공자가 던지고 기존 `resolveOrUnavailable` 경로로 간다 |
| 호스트가 이 기능을 모른다 | 빈 맵이다. 로컬 제공자 둘을 포함해 읽지 않는 제공자는 영향이 없다 |
| 턴의 `environment.*` 가 포크의 `ToolContext` 에 보인다 | 허용한다. 포크의 배치에 쓰이지 않는다 |
| 옛 턴의 rewake 재생 | 제출된 `SubmitOptions` 를 재생하므로 같은 배치를 청한다. `String` 은 그대로 왕복한다. 다른 타입은 `SubmitOptionsCodec` 이 최선 노력으로 왕복하므로, 규칙을 어긴 호스트에서는 거부된 턴이 재생에서 받아들여지거나 반대가 될 수 있다 |

## 9. 워크플로 단계 속성

포크의 `definitionAttributes()` 는 에이전트 값으로 폴백하지 않는다. 워크플로 단계는 등록 정의에서 만들어지지 않으므로, 따로 채우지
않으면 속성이 늘 비어 제공자가 빈 속성 기본값으로 조용히 배치한다.

두 프런트엔드 모두 등록 정의에서 **속성만** 가져온다. 속성이 비면 제공자의 포크 기본(부모와 같은 샌드박스)을 따른다.

등록 정의와 스크립트에서 온 속성은 `SubagentMetadata` 에 실려 단계의 `inputHash` 에 포함되므로, 슬롯이 바뀌면 재개 캐시가 재생하지
않는다. 재개 캐시 자체는 [`workflow.md`](../workflow/workflow.md)에 있다.

### 9.1 서술자와 병합

`SubagentResolver.resolve(SubagentDescriptor)` 는 인자 하나를 받는다. `SubagentDescriptor` 는 `agentType` · `systemPrompt` · `model` ·
`tools` · `maxIterations` · `attributes` 를 담는다. 불변 + 빌더이고, 빌더가 빈 글자를 없음으로 바꾸고 `attributes` 를
`DefinitionAttributes.copyOf` 로 검증한다. 해석기마다 같은 검사를 다시 쓰지 않게 하기 위해서다. 단계의 이름은
`graaljs:<agentType>` 그대로다.

스크립트의 `attributes` 는 정의 파일과 같은 `DefinitionAttributes.fromFrontmatter` 로 읽는다. 그래서 중첩과 점 표기가 같고
(`{sandbox: {slot: 'build'}}` ≡ `{'sandbox.slot': 'build'}`) 거절 규칙도 같다. 스크립트가 파일에 담지 못할 것을 담을 수 없다. 표면의
모양은 `agent({ agentType: 'builder', goal: '...', attributes: {...} })` 이고 사용 안내는
[워크플로 CLI 가이드](../../features/workflow/workflow-cli-guide.md)에 있다.

`DefinitionAttributes.overlay(base, override)` 는 키마다 override 가 이기는 범용 병합이다. base 순서를 유지하고 새 키를 뒤에 붙이며,
합친 맵에 값이면서 그룹인 키가 생기면 거절한다. 거절 메시지는 충돌이 base 안에 있는지 base 와 override 사이에 있는지 구분한다. 코드로
만든 정의는 `copyOf` 를 지나 이 검사를 건너뛰므로 자기 안의 충돌이 여기서 처음 드러날 수 있기 때문이다.

### 9.2 스크립트 속성: 고정과 허용 목록

스크립트는 모델이 쓰고 속성은 코드가 어디서 도는지를 정하므로, 어떤 규칙도 스크립트가 운영자가 내주지 않은 배치를 고르게 두지
않는다.

- **등록 정의가 정한 키는 고정한다.** 다른 값을 주면 스크립트가 실패하고 같은 값은 무시한다
- **그 밖의 키**, 곧 등록 정의가 정하지 않은 키와 등록되지 않았거나 없는 `agentType` 의 모든 키는 `scriptAttributeKeys` 에 있어야 하며
  이 목록은 기본이 비어 있다. 고정만 두면 단계 이름을 바꿔 슬롯을 요청할 수 있기 때문이다
- **목록에 넣어도 고정은 풀리지 않는다**

| `agentType` 등록 | 스크립트 `attributes` | 결과 |
|---|---|---|
| 아니오 · 없음 | 없음 | `{}` |
| 아니오 · 없음 | 있음, 모든 키가 허용 목록에 있음 | 그 값 |
| 아니오 · 없음 | 있음, 허용 목록에 없는 키 | 스크립트 실패 |
| 예 | 없음 | 등록 값 |
| 예 | 등록 키에 같은 값 | 등록 값 |
| 예 | 등록 키에 다른 값 | 스크립트 실패 |
| 예 | 등록되지 않은 키, 허용 목록에 있음 | 등록 값에 더함 |
| 예 | 등록되지 않은 키, 허용 목록에 없음 | 스크립트 실패 |

허용 목록은 `GraalJsWorkflowTool.Builder.scriptAttributeKeys` 또는 `SubagentResolver.inline(registry, keys)` 로 준다. `inline()` 과
`inline(registry)` 는 스크립트 속성을 모두 거절한다. `GraalJsWorkflowScript` 의 편의 생성자는 `inline()` 을 쓴다. 사용자 정의
`subagentResolver` 는 무엇을 허용할지 스스로 정하므로 `scriptAttributeKeys` 와 함께 쓰면 빌드가 실패한다. CLI 와 스타터에는 이 목록의
설정 키가 없어 그 경로의 단계는 등록 정의로만 속성을 받는다
([EE-87](../../backlog/execution-environment-open-items.md)).

### 9.3 역할 이름

내장 `Workflow` 의 단계는 역할마다 정해진 이름(`workflow-perspective` · `workflow-synthesizer` · `workflow-candidate` · `workflow-judge` ·
`workflow-skeptic`)으로 등록된 정의에서 속성만 복사한다. 단계의 이름(`workflow:judge` 등)과 프롬프트는 그대로다.

역할 속성은 내장 스크립트 시작에서 실행당 한 번, 스크립트 스레드에서 조회한다. 팬아웃 안에서 조회하면 리로드 중에 형제 단계의
슬롯이 갈린다. 리로드는 다음 실행부터 반영된다.

속성만 주려고 둔 역할 정의는 `hidden: true` 로 둔다. `Task` 의 목록에 나오지 않고 모델이 이름을 대도 거절되며, 이름으로 찾는 조회는
그대로 찾는다.

### 9.4 워크플로 단계의 실패 처리

| 경우 | 처리 |
|---|---|
| `attributes` 가 객체가 아니다(문자열, 배열, 함수) | 필드를 말하는 `JsScriptException` 이고 도구는 `ToolResult.error("JS workflow failed: …")` 를 돌려준다. 함수를 따로 거절하는 것은 소스 텍스트로 바뀌어 더 모호한 오류가 나기 때문이다 |
| 리스트, `null`, 빈 맵, 빈 키, 값이면서 그룹인 키 | `fromFrontmatter` 의 거절이 `JsScriptException` 이 된다. 키를 말한다 |
| `NaN` · `±Infinity` | 키를 말하며 거절한다. YAML 이 만들 수 없고 글자로 바꾸면 슬롯 이름이 `"NaN"` 이 된다 |
| 유한한 숫자와 불리언 | 글자가 된다 |
| 스크립트 키가 등록 키와 값/그룹으로 충돌한다(`sandbox` 와 `sandbox.slot`) | `overlay` 의 거절이 `JsScriptException` 이 된다 |
| `agentType` 이 등록되지 않았다 | 오류가 아니다. `agentType` 은 자유 라벨이기도 하다. 디버그 로그만 남긴다. 스크립트 속성은 §9.2 를 따른다 |
| 레지스트리 조회가 던진다 — graaljs | 스크립트 실패(`JsScriptException`). 스크립트 작성자가 그 타입을 요청했다 |
| 레지스트리 조회가 던진다 — `WorkflowTool` | WARN 후 그 역할의 단계를 속성 없이 돌린다. 내장 도구가 선택적 배치 힌트 때문에 사용자의 워크플로를 실패시키지 않는다 |
| 거부된 실행의 메시지 | 비동기 스크립트 본문에서 던져져도 `JsResultMarshaller` 가 호스트 예외의 메시지를 읽어 도구 결과까지 필드 이름이 간다 |
| 백그라운드 실행 | 스크립트가 도는 시점에 포그라운드와 같이 해석한다. 등록 정의에서 온 속성은 합류 키에 들지 않아, 실행 중 정의가 슬롯을 바꿔도 같은 요청은 합류한다. 스크립트에 쓴 속성은 소스의 일부이므로 합류 키에 든다 |
| 모델에게 새는가 | 아니다. 속성은 `ForkDefinition` 으로만 간다 |

## 10. 하지 말 것

- **`environment.*` 를 모델 출력으로 채우지 말 것.** 도구 인자, 모델이 쓴 루틴 매개변수, 스크립트 값 모두다(§7)
- **`requestAttributes` 를 포크에 넘기지 말 것.** 포크가 부모와 다른 격리 단위를 청할 수 있게 된다(§5)
- **`environment.*` 값을 변환하거나 건너뛰지 말 것.** 오류로 둔다(§4)
- **실행 속성의 값을 로그에 남기지 말 것.** 키 이름과 클래스만 남긴다(§4)
- **제공자에서 `agent()` 로 속성을 읽지 말 것.** 모든 포크가 메인 턴의 슬롯에 들어가는데 아무것도 실패하지 않는다(§3)
- **루틴의 배치를 `ScheduledTask` 에 저장하지 말 것**(§6)
- **역할 조회를 팬아웃 안으로 옮기지 말 것**(§9.3)
- **`WorkflowInput` 이나 도구 설명으로 `attributes` 를 모델에게 내주지 말 것**(§2)
- **고정 규칙을 `DefinitionAttributes.overlay` 에 넣지 말 것.** 범용 규칙이다(§2)

## 11. 참조 파일 지도

| 주제 | 타입 | 경로 |
|---|---|---|
| 요청과 추출 | `EnvironmentRequest`, `EnvironmentRequestAttributes`, `ExecutionEnvironments`, `ForkDefinition` | `modules/aimon-core/src/main/java/at/aimon/core/environment/` |
| 정의 속성 | `DefinitionAttributes`, `SubagentMetadata` | `.../core/base/`, `.../core/subagent/` |
| 메인 턴과 포크 | `OrcaAgentExecutor`, `DefaultSubagentExecutor`, `SubmitOptions` | `.../core/agent/impl/orca/`, `.../core/subagent/execution/`, `.../core/agent/` |
| 루틴 | `RoutineExecutionAttributes`, `RoutineExecutor`, `SchedulingEngineBuilder` | `.../core/scheduling/` |
| 루틴 조립 | `SchedulingSpec`, `AimonStackBuilder`, `AimonSchedulingAutoConfiguration` | `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/`, `modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/` |
| 내장 워크플로 | `WorkflowTool`, `DefaultWorkflowContext` | `.../core/tools/workflow/`, `.../core/workflow/impl/` |
| GraalJS 프런트엔드 | `SubagentDescriptor`, `SubagentResolver`, `InlineSubagentResolver`, `AgentTaskMarshaller`, `GraalJsWorkflowTool`, `GraalJsWorkflowScript` | `modules/aimon-graaljs/src/main/java/at/aimon/graaljs/workflow/` |
| 숨김 정의 | `TaskTool` | `.../core/tools/task/` |
