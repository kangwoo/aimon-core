# 도구 계약 (Tool Contract)

> **Status**: IMPLEMENTED — `aimon-core` 의 `at.aimon.core.agent.tool{,.schema,.generic,.permission,.execution}`, `at.aimon.core.toolinvocation`, `at.aimon.core.llm.ToolUse`. 스키마 검증의 기본 모드는 `WARN` 이고 `ENFORCE` 승격은 [TC-1](../../backlog/tool-contract-open-items.md) 이다.

---

## 1. 목적

도구 계약은 선언과 런타임 보장이 일치해야 한다. 모델에게 보낸 스키마는 검증되고, `timeout` 은 프로세스를 끝내며, 출력 상한은 실패
경로에도 걸리고, 잘못된 입력 값 하나가 execution 을 실패시키지 않으며, 패턴이 선언된 도구는 패턴으로 판정된다.

이 문서는 도구 호출이 지나는 경로와 입력·권한 계약의 정본이다. 다음은 다른 문서가 정본이다.

| 주제 | 문서 |
|---|---|
| `timeout`, 출력 상한, 프로세스 종료, `Bash` 결과 | [셸 계약](shell-contract.md) |
| 부작용 선언과 실링·승인 게이트의 읽기 규칙 | [부작용 축](side-effect-axes.md) |
| `ConcurrencyBehavior` 와 배치 병렬 실행 | [병렬 실행](parallel-execution.md) |
| 도구를 작성하는 방법 | [도구 개발 가이드](../../features/tool/tool-development-guide.md) |

---

## 2. 호출 경로와 관문 순서

```
LlmResponse (tool_use)
      │
      ▼
ToolUse                        ← JSON null 을 버린다
      │
      ▼
SingleToolInvoker              ← PermissionRequest 훅 → 승인 게이트 → PreTool 훅 (입력을 고칠 수 있다)
      │
      ▼
DefaultToolExecutionManager    ← 허용 목록 → 부작용 실링
      │
      ▼
DefaultToolExecutor            ← 스키마 검증. 이 경로의 유일한 관문
      │
      ▼
Tool#execute(ToolInput, ToolContext)   ← GenericTool 이면 여기서 바인딩
      │
      ▼
SingleToolInvoker              ← PostTool 훅 → 환경 알림 드레인
```

순서마다 이유가 있다.

- **스키마 검증은 권한 뒤에 온다.** 순서를 바꾸면 허용되지 않은 도구의 스키마 위반 메시지로 도구의 존재 여부가 샌다.
- **승인 게이트는 PermissionRequest 훅 뒤에 온다.** 거부할 훅이 사용자에게 먼저 프롬프트 비용을 물리지 않게 한다.
- **허용 목록과 스키마는 PreTool 훅이 고친 입력을 판정한다.** 훅이 고친 뒤의 값이 실제로 실행되는 값이기 때문이다.
- **허용 목록이 비면 그 검사를 건너뛴다.** 빈 목록은 제한이 없다는 뜻이다.
- **환경 알림은 맨 마지막에 드레인한다.** 어느 훅도 알림을 보거나 고칠 수 없고, 훅의 셸 명령이 일으킨 알림도 함께 나간다.

`SingleToolInvoker` 는 메인 실행기(`OrcaAgentExecutor`)와 서브에이전트 실행기(`DefaultSubagentExecutor`)가 공유한다. 권한 훅의
순서가 두 실행기에서 어긋나면 보안 문제가 되므로 순서를 한 컴포넌트에 둔다. 환경 알림의 뜻은
[실행 환경](execution-environment.md) 이 정한다.

---

## 3. JSON `null` 은 부재다

`ToolInput` 의 모든 팩토리와 상류의 `ToolUse` 가 `null` 값 항목을 버린다(`NullSafeMaps.withoutNullValues`). `ToolUse` 까지
정규화하는 이유는 LLM 응답 변환이 `ToolInput` 보다 먼저 일어나기 때문이다. 거기서 실패하면 도구 하나가 아니라 execution 이
실패한다.

원칙은 하나다. **불변식은 그 값을 만드는 타입이 지킨다.** 호출자가 미리 거르게 하면 `ToolInput.of` 를 직접 부르는 모든
자리가 같은 함정에 노출된다.

그 결과 접근자가 서로 일치한다. 모델이 `null` 로 보낸 키는 보내지 않은 키와 정확히 같게 행동한다.

| 접근자 | 키가 없거나 `null` 일 때 |
|---|---|
| `getXxx(key, default)` | 기본값 |
| `getXxxOrNull(key)` | `null` |
| `getRequiredXxx(key)` | 누락을 보고 |

도구는 명시적 `null` 과 생략을 구별할 수 없다. 구별이 필요한 도구가 생기면 [TC-5](../../backlog/tool-contract-open-items.md) 로 다룬다.

---

## 4. 스키마 검증과 모드

### 4.1 잡는 것

`at.aimon.core.agent.tool.schema` 는 모델이 실제로 저지르는 네 가지만 잡는다.

| 검사 | 잡는 것 |
|---|---|
| `required` | 선언된 필수 파라미터 누락. JSON `null` 도 누락이다 |
| `type` | 타입 불일치 |
| `enum` | 선언된 허용값 밖의 값 |
| 미선언 이름 | 오타. `additionalProperties: false` 일 때만 검사하고, 가까운 이름을 함께 알려 준다 |

위반 문장(`ViolationMessages`)은 개발자 진단이 아니라 모델과의 계약이다. 모든 문장이 도구를 실행하지 않았다고 밝힌다. 호출이
반쯤 일어났을 수 있다고 의심하는 모델은 `Write` 나 `Edit` 의 재시도를 망설이기 때문이다.

위반 메시지는 파라미터 이름과 타입만 싣는다. `enum` 위반만 실제 값을 싣는다. 선택지가 이미 스키마에 공개되어 있어 새는 정보가
없고, 값 없이는 고칠 수 없기 때문이다.

### 4.2 경계

이것은 JSON Schema 구현이 아니고, 되려 하지도 않는다. 경계는 셋이다.

- **모르는 것은 통과시킨다.** 여기를 지나는 스키마의 상당수는 프레임워크가 쓰지 않았다. MCP 서버의 스키마는 서버가 보낸 그대로
  도착한다. 파싱하지 못하는 것을 거부하면 낯선 서버 하나를 연결했을 때 멀쩡한 호출이 무더기로 거부되고, 원인은 증상 근처에 없다.
  그래서 `$ref`·`oneOf`·`anyOf`·`allOf`·`not` 을 쓰는 프로퍼티는 건너뛰되 형제는 건너뛰지 않으며, 모르는 타입 이름도 건너뛴다.
- **모양은 여기서, 범위는 도구에서.** `minimum`·`maximum`·`minLength`·`minItems`·`default` 는 선언되어 있어도 무시한다. 도구마다
  처리 방식이 다르기 때문이다. `BashTool` 은 과대한 timeout 을 거부하지 않고 clamp 하므로, 여기서 `maximum` 을 강제하면 성공하던
  호출이 에러가 된다. 강제 지점이 둘이 되면 어느 쪽이 정본인지에 대한 답도 없어진다.
- **중첩은 1단계까지.** `object`·`array` 프로퍼티는 그 타입인지만 본다. 재귀하려면 `$ref` 와 `oneOf` 를 진짜로 다뤄야 하고, 그 시점에서
  이것은 JSON Schema 구현이다. 중첩 계약이 중요한 도구는 [`GenericTool`](#8-generictool) 로 간다. 재귀 검증은
  [TC-2](../../backlog/tool-contract-open-items.md) 다.

스키마는 진실로 취급한다. 이 층은 스키마가 파라미터를 읽는 코드와 맞는지 알 수 없다. 둘을 맞추는 일은 `GenericTool` 의 몫이고
두 층은 겹쳐서 쓴다. 구현은 이해하지 못한 스키마로 예외를 던지지 않는다.

### 4.3 모드

반응 방식은 `SchemaValidationMode` 가 정한다. 모드는 `new DefaultToolExecutionManager(mode)` 또는
`OrcaAgentExecutorFactory.withSchemaValidationMode(...)` 로 정한다.

| 모드 | 동작 |
|---|---|
| `OFF` | 검증기를 부르지 않는다. 호출 실패를 진단할 때 관문을 제외하는 스위치다 |
| `WARN` (기본값) | 위반을 로그로만 남기고 도구를 실행한다. 모델이 보는 것은 `OFF` 와 같다 |
| `ENFORCE` | 실행을 거부하고 위반을 도구 에러로 돌려준다 |

기본이 `WARN` 인 이유는 거부가 한꺼번에 시작되면 원인을 가릴 수 없기 때문이다. 거부는 모델의 실수일 수도 도구 스키마의 오류일
수도 있다. 관측이 먼저이고 거부는 그다음이다. 승격은 [TC-1](../../backlog/tool-contract-open-items.md) 이다.

---

## 5. 관문 밖 호출 지점

`ToolExecutionGateArchitectureTest` 가 `Tool#execute` 를 부를 수 있는 예외를 클래스 리터럴로 고정한다. 목록의 목적은 이 다섯을 금지하는
것이 아니라 **여섯 번째를 논증거리로 만드는 것**이다. 제외 기준은 입력의 출처가 아니라 폭발 반경이다.

| 호출 지점 | 관문 밖에 두는 근거 |
|---|---|
| `ArtifactAwareEditTool`, `ArtifactAwareWriteTool` | 바깥 도구로 이미 관문을 지난 호출을 감싼 도구에 넘긴다 |
| `RecentFilesRestoreHook` | 훅이 스스로 만든 `file_path` 입력으로 `ReadTool` 만 부른다 |
| `ReActLlmDeriver` | 도구 집합이 닫혀 있고 닿는 부작용이 `ObservationStore` 하나다. 파생은 무인으로 돌아 승인 게이트를 놓으면 언제나 실패한다 |
| `RoutineExecutor` | 발화 시점에는 오류를 고칠 모델이 없다. 루틴을 검사하는 자리는 예약 시점이다(`ScheduledTaskPolicy`). 예약 시점의 스키마 검증은 [TC-3](../../backlog/tool-contract-open-items.md) 다 |

규칙은 호출뿐 아니라 메서드 참조(`tool::execute`)까지 보고, 패키지 문자열이 아니라 클래스 리터럴을 쓴다. 아무것도 매치하지 않는
규칙은 실패하지 않고 조용히 통과하기 때문이다.

이 규칙은 코어 테스트 클래스패스 안만 본다. 다른 저장소의 데코레이터는 그쪽 빌드가 확인한다.

---

## 6. 권한 판정

### 6.1 항목과 주체

허용 목록의 항목은 `"이름"` 또는 `"이름(패턴)"` 이다. 이름은 그 도구를 실행해도 되는지를, 패턴은 어떤 호출을 실행해도 되는지를 정한다.
패턴을 무엇과 비교할지는 도구만 알 수 있다. `Bash` 는 `command`, `Read` 는 `file_path` 다. 그래서 도구가 그 값을 `PermissionSubject` 로 내놓는다.

| Kind | 판정 대상 | 매처 | 예 |
|---|---|---|---|
| `COMMAND` | 셸 명령줄 | `ToolPattern` — 끝의 `:*` 만 와일드카드 | `Bash(git:*)` |
| `PATH` | 절대·정규화된 파일 경로 | `PathPattern` — 글로브(`**`, `*`) | `Read(/tmp/**)` |

인터페이스는 셋이고 앞의 둘은 호출 자체를, 셋째는 호출이 대신하는 능력을 판정한다.

| 인터페이스 | 언제 |
|---|---|
| `ToolPermissionSubjectAware` | 판정 대상이 입력 필드 하나로 정해진다. 기본 선택이다 |
| `CustomToolPermissionAware` | 여러 입력의 조합이나 외부 조회가 필요하다 |
| `ToolPermissionDelegationAware` | 호출이 다른 도구의 능력을 대신 쓴다 |

구현체 목록은 코드가 정본이다.

### 6.2 판정 순서

`DefaultToolPermissionValidator` 는 다음 순서로 판정한다.

1. 허용 목록이 비었다 → 허용
2. 이름이 목록에 없다 → 거부
3. 그 이름의 항목에 패턴이 하나도 없다 → 허용. `"Bash"` 와 `"Bash(git:*)"` 가 함께 있으면 패턴이 맞아야 한다
4. 도구가 주체를 내놓는다 → 주체의 종류가 고른 매처로 비교
5. 도구가 커스텀 규칙을 내놓는다 → 규칙에 맡긴다
6. 그 밖 → 거부
7. 3~5 가 허용했고 도구가 위임을 선언했다 → 위임 대상 도구의 항목으로 2·3·4 를 한 번 더 적용

`SkillPermissionManager` 는 스킬 `allowed-tools` 의 인자 패턴을 `ToolPattern` 으로 직접 비교하므로 이 순서를 지나지 않는다.

### 6.3 판정할 수 없으면 거부다

해석할 수 없는 패턴은 거부한다. 패턴이 선언된 도구를 통과시키면 가장 엄격해 보이는 설정이 가장 약한 강제가 되기 때문이다. 빈 주체도 같은
이유로 기권이 아니라 판정 불가다. 그 도구에 패턴이 설정되어 있고 커스텀 규칙도 없으면 거부된다.

글로브 문법에 맞지 않는 패턴은 `PATH` 주체의 어느 경로와도 맞지 않는다.

### 6.4 `PATH` 주체

`PATH` 주체는 절대 경로이고 어휘적으로 정규화된다. `/tmp/../etc/passwd` 는 `Read(/tmp/**)` 를 통과하지 못한다.

`FilePathSubjects` 는 상대 경로를 `EXECUTION_ENVIRONMENT` 의 `descriptor().workingDirectory()` 에 대해 푼다. 셸과 파일 도구가 합의한 그 디렉터리
하나뿐이다. 컨텍스트에 환경이 없으면 답이 없고 주체는 비어 있다. 프로세스 CWD 로 풀면 판정이 JVM 을 띄운 위치에 달려 패턴을 쓴 사람이 예측할 수 없다.

### 6.5 위임

권한은 도구 이름별로 설정된다. 위임이 없으면 `Bash(git:*)` 와 패턴 없는 `Monitor` 를 함께 허용한 목록에서 `Monitor` 가 `Bash` 항목이 막은 명령을
돌릴 수 있다. 다른 도구의 능력을 대신 쓰는 도구는 `ToolPermissionDelegationAware` 로 대상 도구와 주체를 선언하고, 검증기는 도구 자신의 항목이
허락한 뒤 대상 도구의 항목으로 한 번 더 판정한다.

| 대상 도구의 항목 (허용 목록이 비어 있지 않을 때) | 결과 |
|---|---|
| 없음 | 거부. 이 에이전트는 그 능력을 쓸 수 없다 |
| 패턴 없는 항목만 | 허용 |
| 패턴이 있다 | 주체가 그 패턴과 맞아야 허용 |

자신의 항목이 먼저다. 대상 도구의 항목만 있고 자신의 항목이 없으면 그 도구는 쓸 수 없다. 적용 예는 [Monitor](monitor.md) 의 권한 절이다.

세 인터페이스 모두 도구보다 먼저 돈다. 잡아 줄 곳이 없으므로 입력을 방어적으로 읽고 예외를 던지지 않으며, 상태를 갖지 않는다.

---

## 7. 권한은 격리 경계가 아니다

`PATH` 주체는 어휘적으로만 정규화한다. 권한 패턴은 요청 범위를 좁히는 장치이고 격리는 샌드박스의 일이다. 링크를 풀어도 판정과 파일 열기 사이의
TOCTOU 가 남으므로 격리 경계가 되지 않는다.

다음은 이 경계의 결과이고 결함이 아니다.

- `Read(/allowed/**)` 는 `/allowed/link → /secrets` 를 막지 않는다.
- 판정 시점과 파일을 여는 시점 사이에 경로가 바뀔 수 있다.

격리가 필요하면 [격리 브랜치](execution-environment-isolation.md) 와 샌드박스 제공자를 본다.

---

## 8. GenericTool

### 8.1 구조

`GenericTool<I, O>` 는 `AbstractTool` 을 대체하지 않고 그 옆에 서는 선택적 베이스 클래스다. 입력 `record` 하나가 스키마와 파라미터 추출의 단일 출처다.

| 타입 | 역할 |
|---|---|
| `GenericTool<I, O>` | `execute()` 가 `final` 이다. 하위 클래스는 `doExecute(I, ToolContext)` 와 `render(O)` 만 구현한다 |
| `ToolParam` | `@Target(RECORD_COMPONENT)`. `name`, `description`, `required` |
| `ToolSchemaGenerator` | `record` → JSON Schema. 중첩 `record` 까지 `additionalProperties: false` 를 항상 넣는다 |
| `ToolInputBinder`, `BindResult` | `ToolInput` → `I` 바인딩. 위반을 한 번에 모두 보고한다 |

이 구조에서 세 가지가 따라온다.

- **스키마와 추출이 어긋날 수 없다.** 둘 다 같은 `record` 에서 나온다. `additionalProperties: false` 가 선택 사항이 아닌 것이 바인딩의 요점이다. `record` 는 정확히 그 컴포넌트만 가진다.
- **`execute()` 가 `final` 이다.** "예외를 던지지 않는다" 는 계약을 하위 클래스의 성실함이 아니라 타입으로 강제한다.
- **바인딩 위반이 스키마 검증기와 같은 문장을 쓴다.** 두 층은 다른 시점에 잡지만 모델이 같은 불평을 두 말투로 배울 이유는 없다.

와이어 이름은 선언하는 것이지 변환되는 것이 아니다. snake_case 라면 `@ToolParam(name = "file_path")` 로 적는다. `Grep` 의 `-i`·`-A` 처럼 식별자로 쓸 수 없는 이름이 있어 자동 변환을 두지 않는다.

바인딩이 대체하지 않는 것이 하나 있다. 업무 규칙 검증은 `doExecute()` 에 남는다. `GrepTool` 의 ReDoS 방어(패턴 길이 상한)는 타입으로 표현되지 않는다. 바인딩이 맡는 것은 추출이지 검증이 아니다.

### 8.2 입력 DTO 는 `record` 다

프로젝트 규약은 `class` 선호다. 예외는 `GenericTool` 의 입력 DTO 하나뿐이고, 도메인 타입·값 객체·설정 객체는 포함되지 않는다.

걸리는 것은 `record` 라는 단어가 아니라 빌더 요구 조항이다. 도구 입력 DTO 는 프레임워크가 역직렬화로만 만들고 애플리케이션 코드가 `new` 하지 않는다. 조립 단계가 없으므로 빌더가 지킬 불변식도 없다. 그래서 이 예외는 "역직렬화 대상은 빌더 면제" 로 해소된다. 선례는 `@JsonCreator` 생성자 하나뿐이고 빌더가 없는 `Todo` 다. 규칙의 정본은 [`immutability-pattern.md`](../../../.claude/rules/immutability-pattern.md) 와 [`code-style.md`](../../../.claude/rules/code-style.md) 다.

### 8.3 스키마 생성

스키마 생성은 런타임 리플렉션과 `@ToolParam`, 자체 바인더로 한다. 새 의존성이 없고, 같은 메타데이터가 스키마 생성과 바인딩 양쪽에 쓰인다. 스키마 생성은 도구 생성 시 1회뿐이므로 런타임 비용은 문제가 되지 않는다.

`List<T>` 와 중첩 객체는 손으로 풀 이유가 없어 그 부분만 `ObjectMapper.convertValue` 에 위임한다.

### 8.4 베이스 선택

선택 표는 [도구 개발 가이드](../../features/tool/tool-development-guide.md) 의 "어느 베이스 클래스를 고를 것인가" 가 정본이다. `AbstractTool` 이 여전히 기본이고, 입력 키 집합이 런타임에 정해지는 도구(MCP 위임)는 바인딩할 컴파일 타임 타입이 없어 그쪽이다.

`AbstractTool` 경로는 스키마 최상위에 `additionalProperties: false` 를 직접 넣는다. `at.aimon.core.tools` 안은 `BuiltInToolSchemaArchitectureTest` 가 확인한다. 그 밖의 도구도 규칙은 같지만 테스트가 지켜 주지 않는다.

---

## 9. 의도적으로 제외한 것

| 제외 항목 | 이유 |
|---|---|
| `Tool` 인터페이스의 제네릭화(`Tool<I, O>`) | `ToolRegistry` 가 이종 `Tool` 을 담아야 해서 와일드카드가 전면에 번지고, MCP 도구는 컴파일 타임 입력 타입이 없어 `Map` 경로가 남는다. 두 근거 모두 인터페이스를 건드릴 때만 성립하므로 `AbstractTool` 옆의 선택적 베이스는 둘 다 피한다 |
| `Tool#validateInput` 류의 검증 훅 | 도구별 검증은 스키마로, 정책적 차단은 `PreToolHook` 으로 간다. 세 번째 자리를 만들면 "어디에 쓸 것인가" 가 매번 질문이 된다 |
| `<tool_use_error>` 오류 봉투 | 오류 신호는 이미 양쪽 프로바이더로 전달된다. Anthropic 은 프로토콜 필드(`isError`)로, OpenAI 는 그런 필드가 없어 본문의 `"Error: "` 접두어로 간다. 프레임워크가 봉투를 더 씌우면 OpenAI 경로에서 이중이 된다 |
| `Tool#aliases()` | 이름은 레지스트리가 소유한다. 도구가 별칭을 선언하면 두 곳이 이름을 소유한다. `DefaultToolRegistry` 의 `"functions."` 접두어 제거는 별칭이 아니라 프로바이더 보정이다 |
| 도구가 프로토콜 블록을 직접 만드는 `mapResult(O, toolUseId)` | 그 변환은 `ToolExecutionResultConverter` 가 소유한다. 도구가 프로바이더 프로토콜을 알면 안 된다. 렌더링 부가 정보는 `ToolResult.renderPayload` 가 있다 |

---

## 10. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| 스키마 검증 관문을 실행기에 둔다 | 도구마다 검증하면 규칙을 어기는 도구가 생기고 오류 문장 품질이 제각각이 되며, MCP 로 들어온 도구는 손댈 수 없다 | 도구 안 검증 |
| 스키마 검증은 네 검사와 1단계 중첩까지다 | [§4.2](#42-경계) | JSON Schema 구현체 도입. 들이면 자체 검증기를 버리는 결정이 함께 온다 |
| 기본 모드는 `WARN` 이다 | 위반이 모델의 실수인지 도구 스키마의 오류인지 먼저 가린다 | 처음부터 `ENFORCE` |
| `null` 정규화는 값을 만드는 타입이 한다 | [§3](#3-json-null-은-부재다) | 호출자가 미리 거른다 |
| 입력 스키마 검증기를 따로 둔다 | `StructuredOutputSupport` 는 `workflow.impl` 안의 비공개 코드이고 불리언 하나만 돌려주어 위반 문장을 낼 수 없다. `null` 도 반대로 읽는다. 저쪽은 모델이 낸 출력을, 이쪽은 모델이 보낸 입력을 검증한다 | `StructuredOutputSupport` 재사용 |
| 주체의 종류를 도구가 함께 보낸다 | `AllowedTool` 파서는 괄호만 보고 `:` 를 해석하지 않으므로 `Read(/tmp/**)` 와 `Bash(git:*)` 는 파서 눈에 구분되지 않는다 | 스펙 문자열에서 종류 추론 |
| 매처를 둘 둔다 | `ToolPattern` 은 후보에 셸 메타문자가 있으면 거부한다. `bash -c` 로 향하는 문자열에는 맞는 방어지만 경로에 적용하면 `report(1).csv` 같은 파일이 영영 닿지 않는다 | 매처 하나로 겸한다 |
| 판정 불가는 거부다 | [§6.3](#63-판정할-수-없으면-거부다) | 통과시키거나 프로세스 CWD 로 추측한다 |
| 심볼릭 링크를 풀지 않는다 | [§7](#7-권한은-격리-경계가-아니다) | 환경의 파일 시스템으로 링크를 풀어 판정한다 |
| 다른 도구의 능력은 위임으로 판정한다 | 권한이 도구 이름별이라 위임 없이는 대상 도구의 패턴을 우회한다 | 대상 도구 항목을 물려받지 않는다 |
| 입력 DTO 만 `record` 로 쓴다 | [§8.2](#82-입력-dto-는-record-다) | 불변 클래스 + 빌더 |
| 스키마를 런타임 리플렉션으로 만든다 | [§8.3](#83-스키마-생성) | 애노테이션 프로세서: 빌드 복잡도가 오르는데 런타임 비용은 원래 문제가 아니다. 타입 세이프 스키마 빌더 DSL: 스키마와 `input.getXxx()` 가 여전히 별개라 이중 진실이 남는다 |
| 바인더를 직접 쓴다 | `jackson-module-parameter-names` 가 버전 카탈로그에 없어 컴포넌트마다 `@JsonProperty` 를 붙여야 한다. `@ToolParam` 과 이름을 두 번 쓰게 된다 | Jackson 바인더 |
| 와이어 이름은 선언한다 | [§8.1](#81-구조) | 자동 snake_case 변환 |

---

## 11. 하지 말 것

- **`Tool#execute` 를 새 지점에서 직접 부르지 말 것.** 호출하면 스키마 관문을 건너뛴다. 필요하면 §5 의 목록에 올리고 폭발 반경으로 논증한다.
- **예외 목록을 패키지 문자열로 쓰지 말 것.** 아무것도 매치하지 않는 규칙은 조용히 통과한다.
- **권한 패턴을 격리 경계로 쓰지 말 것.** §7 이다.
- **빈 주체를 기권으로 읽지 말 것.** 판정 불가이고 패턴이 설정된 도구에서는 거부다.
- **`PATH` 주체에 프로세스 CWD 폴백을 넣지 말 것.** 판정이 JVM 을 띄운 위치에 달린다.
- **권한 인터페이스 구현에서 입력을 믿거나 예외를 던지지 말 것.** 도구보다 먼저 돌고 스키마 검증보다도 먼저 돈다.
- **스키마 검증기에 범위 검사를 넣지 말 것.** 범위의 정본은 도구다.
- **이해하지 못한 스키마를 거부하지 말 것.** 제3자 스키마가 지나는 관문이다.
- **도구에서 명시적 `null` 과 생략의 구별에 기대지 말 것.** 둘은 같다.
- **`record` 예외를 입력 DTO 밖으로 넓히지 말 것.** 애플리케이션이 조립하는 타입은 빌더가 필요하다.

---

## 12. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다. 아키텍처 테스트만 `src/test/java` 아래다.

| 관심사 | 파일 |
|---|---|
| 호출 파이프라인 | `toolinvocation/SingleToolInvoker.java` |
| 허용 목록·실링·실행기 호출 | `agent/tool/DefaultToolExecutionManager.java` |
| 스키마 관문 | `agent/tool/execution/DefaultToolExecutor.java` |
| 입력 정규화 | `llm/ToolUse.java`, `agent/tool/ToolInput.java`, `base/NullSafeMaps.java` |
| 스키마 검증 | `agent/tool/schema/` (`ToolInputSchemaValidator`, `DefaultToolInputSchemaValidator`, `SchemaValidationMode`, `SchemaValidationResult`, `ViolationMessages`) |
| 권한 | `agent/tool/permission/` (`PermissionSubject`, `ToolPermissionSubjectAware`, `CustomToolPermissionAware`, `CustomToolPermissionRule`, `ToolPermissionDelegationAware`, `PermissionDelegation`, `DefaultToolPermissionValidator`, `ToolPattern`, `PathPattern`, `AllowedTool`) |
| 경로 주체 | `tools/file/FilePathSubjects.java` |
| 제네릭 도구 | `agent/tool/generic/` (`GenericTool`, `ToolParam`, `ToolSchemaGenerator`, `ToolInputBinder`, `BindResult`) |
| 결과 변환 | `agent/tool/execution/ToolExecutionResultConverter.java` |
| 아키텍처 테스트 | `architecture/ToolExecutionGateArchitectureTest.java`, `architecture/BuiltInToolSchemaArchitectureTest.java` |

---

## 13. 관련 문서

- [셸 계약](shell-contract.md) — `Bash` 가 실제로 무엇을 끝내는가
- [실행 환경](execution-environment.md) — 도구가 환경에서 파일 시스템과 셸을 받는 규칙
- [병렬 실행](parallel-execution.md) — `ConcurrencyBehavior` 와 병렬 게이트
- [부작용 축](side-effect-axes.md) — `SideEffectLevel`, `DestructiveBehavior`, `IdempotencyBehavior`
- [도구 검색](tool-search.md) — 도구 지연 로딩
- [Monitor](monitor.md) — 위임의 적용 예
- [MCP 도구 통합](../integration/mcp-tool.md) — 제3자 스키마가 들어오는 경로
- [도구 개발 가이드](../../features/tool/tool-development-guide.md)
- [남은 작업 등록부](../../backlog/tool-contract-open-items.md)
