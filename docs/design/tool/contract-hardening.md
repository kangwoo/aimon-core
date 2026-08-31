# 도구 계약 강화 (Tool Contract Hardening)

> Status: **IMPLEMENTED** — TCH-01 ~ TCH-07 일곱 항목이 모두 반영되었다. 스키마 검증만 기본값이
> `WARN` 이며 `ENFORCE` 승격은 남아 있다 (§8).
>
> 적용 대상: `aimon-core` — `at.aimon.core.agent.tool{,.schema,.generic,.permission,.execution}`,
> `at.aimon.core.tools.bash`, `at.aimon.core.shell.impl.local`,
> `at.aimon.core.agent.impl.orca.{tool,environment}` · `aimon-browser-playwright`
>
> 관련 규칙: [`.claude/rules/tool-development.md`](../../../.claude/rules/tool-development.md),
> [`.claude/rules/code-style.md`](../../../.claude/rules/code-style.md)
> (**"Prefer `class` over `record`" 의 소재지** — §6.3 이 그 유일한 예외를 정의한다),
> [`.claude/rules/immutability-pattern.md`](../../../.claude/rules/immutability-pattern.md)
>
> 관련 문서: [`parallel-execution.md`](parallel-execution.md) (같은 패키지의 `ConcurrencyBehavior`),
> [`side-effect-axes.md`](side-effect-axes.md) (도구가 선언하는 다른 축),
> [`../../features/tool/tool-development-guide.md`](../../features/tool/tool-development-guide.md)

---

## 1. 무엇을 풀었는가

도구 계약이 **선언한 것**과 런타임이 **실제로 보장하는 것** 사이에 다섯 개의 간극이 있었다. 이 문서는
그것을 메운 일곱 개의 변경(TCH-01 ~ 07)을 기록한다.

| 선언 (LLM 또는 호출자가 믿던 것) | 그때의 실제 보장 | 항목 |
|---|---|---|
| `ToolDefinition.getInputSchema()` 를 LLM 에 보낸다 | 아무도 검증하지 않았다. 오타난 파라미터는 조용히 무시됐다 | TCH-05 |
| `Bash` 의 `timeout` (기본 120s, 최대 600s) | 자바 쪽 대기만 풀리고 프로세스는 계속 살아 있었다 | TCH-01 |
| `Bash` 설명: "output truncation at 30,000 characters" | 성공 경로만 잘랐다. 메모리에는 전량 적재됐다 | TCH-01 |
| `ToolInput` 은 입력의 불변 래퍼다 | JSON `null` 값 하나에 NPE 가 났고, `ToolInput` 보다 먼저 LLM 응답 변환에서 터져 **턴 전체가 실패**했다 | TCH-04 |
| `Read(/tmp/**)` 같은 경로 권한 | 규칙이 작업 디렉토리를 못 받아 상대 경로를 판정할 수 없었고, 패턴이 선언된 도구가 오히려 **무제한 허용**됐다 | TCH-06 |

두 트랙은 서로 의존하지 않았다 — 트랙 A(TCH-01 ~ 03)는 셸 배선, 트랙 B(TCH-04 ~ 07)는 도구 계약이다.

| ID | 트랙 | 결과 |
|----|------|------|
| **TCH-01** | A · Bash | `BashExecutor` SPI 폐기 — `BashTool` 이 `VirtualShell` 을 직접 소비한다 |
| **TCH-02** | A · Bash | `LocalShell` 이 프로세스 트리(자손 포함)를 종료한다 |
| **TCH-03** | A · Bash | exit code 가 값으로 전달된다 — 비정상 종료를 예외로 승격하지 않는다 |
| **TCH-04** | B · 계약 | JSON `null` 을 부재로 정규화 — `Map.copyOf` NPE 제거 |
| **TCH-05** | B · 계약 | 도구 입력 스키마 검증 단계 (`…tool.schema`) |
| **TCH-06** | B · 계약 | `PermissionSubject` 도입 + 권한 fail-open 폐쇄 |
| **TCH-07** | B · 계약 | `GenericTool<I, O>` — 입력 `record` 하나에서 스키마와 바인딩이 파생된다 |

**설계 도중 두 번 방향이 바뀌었고, 둘 다 "이미 있는 것을 찾았다"였다.**

1. Bash 세 항목은 처음에 "타임아웃과 출력 상한을 새로 구현한다"였으나, `at.aimon.core.shell` 이 정확히
   그것을 이미 구현하고 있고 **기본 배선만 그쪽을 쓰지 않고 있었다.** 그래서 이 절반은 새 코드가 아니라
   **삭제와 재배선**이다.
2. TCH-04 의 NPE 지점은 `ToolInput` 생성자가 아니라 그보다 상류인 **LLM 응답 변환(`ToolUse`)** 이었다.
   증상은 "모델이 나쁜 오류 메시지를 받는다"가 아니라 **"턴이 실패한다"** 였다.

---

## 2. 도구 호출 경로

```
LlmResponse (tool_use)
      │
      ▼
ToolUse                         ← JSON null 을 버린다 (TCH-04)
      │
      ▼
DefaultToolExecutionManager     ← 권한 검증 (TCH-06)
      │
      ▼
DefaultToolExecutor             ← 스키마 검증 (TCH-05) · 이 경로의 유일한 관문
      │
      ▼
Tool#execute(ToolInput, ToolContext)
      │                              GenericTool 이면 여기서 바인딩 (TCH-07)
      ▼
BashTool ──► VirtualShell#execute(ShellCommand, ExecutionOptions)   ← TCH-01
                     └─► LocalShell: waitFor(timeout) → 트리 종료   ← TCH-02
                         결과: ShellCommandResult(exitCode, …)      ← TCH-03
```

**순서가 의도적이다.** 스키마 검증은 권한 검증 **뒤**에 온다 — 순서를 바꾸면 허용되지 않은 도구의
스키마 위반 메시지를 통해 도구의 존재 여부가 새어 나간다.

---

## 3. 트랙 A — Bash 가 실제로 무엇을 죽이는가

### 3.1 `BashExecutor` 폐기 (TCH-01)

`BashExecutor` · `ProcessBashExecutor` · `VirtualShellBashExecutor` 세 파일이 **삭제**되었다.
`at.aimon.core.tools.bash` 에 남은 것은 `BashTool`, `BashOutputTool`, `BackgroundBashManager`,
`BackgroundBashTask`, `BashTaskStatus` 뿐이다.

`BashTool` 은 이제 생성자로 `VirtualShell` 을 받는다 (`BashTool.java:171`, `:189`). 셸은 조립이
공급하며 — `OrcaBashToolProvider.java:47` 이 `context.getShell()` 로 꺼낸다 — 셸이 없으면 **조립을
실패시키지 않고 Bash·BashOutput 을 등록하지 않는다**(`:48-57`). 셸 없는 에이전트는 쓸 수 있는
에이전트이지만, 명령이 사라진 채 도는 에이전트는 아니기 때문이다.

포그라운드 실행에는 **future 래퍼가 없다**. 도구 스레드가 `shell.execute(...)` 안에서 그대로 블록하고,
타임아웃 감시와 프로세스 종료는 셸이 한다 (`BashTool.java:295-297`).

| 옛 경로 | 지금 |
|---|---|
| `executorService.submit(...)` → `future.get(timeout)` → `future.cancel(true)` | 도구 스레드가 셸의 `waitFor(timeout)` 에서 블록 |
| 인터럽트가 자바 스레드만 깨우고 명령은 계속 돌았다 | `THREAD_INTERRUPT` 터미네이터가 셸의 `waitFor` 를 풀고, 셸이 나가는 길에 프로세스를 죽인다 (`BashTool.java:500-501`) |
| 타임아웃 시 부분 출력이 버려졌다 | `ShellTimeoutException` 이 싣고 온 stdout/stderr 를 그대로 렌더한다 (`BashTool.java:298-302`) |

**타임아웃 하한이 이 변경의 조용한 필수 조건이다.** 셸은 0 이하의 타임아웃을 "무기한 대기"로 읽는다.
래퍼가 있을 때는 `future.get(0)` 이 즉시 타임아웃시켜 증상이 가려져 있었지만, 래퍼를 걷어낸 뒤에는
`timeout: 0` 이 곧 영원한 대기다. 그래서 `MIN_TIMEOUT_MS = 1_000` 이 도입되어 클램프가
`Math.min(Math.max(raw, MIN), MAX)` 가 되었고(`:279`), 스키마에도 `minimum` 이 함께 선언되었다(`:225`).

### 3.2 프로세스 트리 종료 (TCH-02)

`LocalShell.destroyForciblyQuietly` 는 자손을 먼저 죽인다 (`LocalShell.java:156-174`).

1. `p.descendants()` 를 **스냅샷**한다 (`snapshotDescendants`, `:186-193` — 플랫폼이 거부하면 빈 목록으로
   degrade 해서 부모만 정리한다)
2. 자손을 `destroy()` 한다
3. 부모를 `destroy()` 하고 `PROCESS_DESTROY_TIMEOUT` 만큼 기다린다
4. 그래도 살아 있으면 스냅샷 + 부모를 `destroyForcibly()`

**순서와 스냅샷 시점이 둘 다 필수다.** 부모를 먼저 죽이면 자식이 init 으로 재부모화되어
`descendants()` 가 아예 열거하지 못하고, `p.destroy()` 이후에 열거하면 이미 빈 스트림이 돌아올 수 있다.

남는 빈틈은 문서화되어 있다 — **스냅샷 이후에 태어난 손자는 살아남는다.** 이것을 닫으려면 프로세스 그룹
(`setsid` + `kill(-pgid)`)이 필요하고, 그것은 플랫폼 의존적이며 `ProcessBuilder` 의 사정거리 밖이다(§8).

### 3.3 exit code 는 값이다 (TCH-03)

`ShellCommandResult` 가 exit code · stdout · stderr · duration · truncated 를 값으로 싣고 오므로,
`BashTool` 이 **한 곳에서** 실패 표현을 조립한다 (`:319-327`).

```
본문 = mergeStreams(stdout, stderr)  →  30,000자 컷  →  마커 append
마커: 성공이면 없음 · 실패면 "[exit code: N]" · 타임아웃이면 "[timed out after Nms]"
```

- 마커는 **컷 다음에** 붙는다. 반대로 하면 출력이 큰 실패에서 마커가 잘려 나간다.
- 마커가 본문 **끝**에 오는 이유: 앞에 붙이면 출력의 첫 줄이 명령 결과가 아니게 되어, 모델이 출력을
  그대로 인용할 때 오염된다.
- 옛 경로의 이중 접두사(`Command failed: Command failed with exit code 1: …`)가 사라졌다. 지금
  `"Command failed: "` 가 붙는 경우는 **명령이 아예 실행되지 못한 하나**뿐이며, 그때는 보고할 exit
  status 도 출력도 없다 (`:313`).

세 상한이 서로 다른 것을 지킨다.

| 상한 | 지키는 것 | 자리 |
|---|---|---|
| `maxCaptureBytes` | **메모리** — 힙에 올리는 바이트의 절대 상한 | `ExecutionOptions` (도구가 명시적으로 지정) |
| 30,000자 | **컨텍스트** — 모델에 실리는 문자 수 | `BashTool.MAX_OUTPUT_LENGTH` |
| `timeout` | **시간** | `ExecutionOptions`, 셸이 강제 |

캡처 상한을 도구가 명시적으로 넘기는 것이 중요하다. 구현 기본값에 기대면 다른 `VirtualShell` 구현이
꽂혔을 때 조용히 달라진다.

---

## 4. 트랙 B — 계약

### 4.1 JSON `null` 은 부재다 (TCH-04)

`ToolInput` 의 모든 팩토리가 `null` 값 항목을 버린다 (`NullSafeMaps.withoutNullValues`,
`ToolInput.java:114`). 상류인 `ToolUse` 도 같은 정규화를 한다 — NPE 가 실제로 나던 자리가 그쪽이기
때문이다. 원칙은 하나다: **불변식은 그 값을 만드는 타입이 지킨다.** 호출자가 미리 거르게 하면
`ToolInput.of` 를 직접 부르는 모든 자리(테스트, 스킬 실행기, `SingleToolInvoker`)가 같은 함정에
그대로 노출된다.

그 결과 접근자들이 서로 일치한다 — 모델이 `null` 로 보낸 키는 보내지 않은 키와 **정확히 같게** 행동한다.
`getXxx(key, default)` 는 기본값을, `getXxxOrNull(key)` 는 `null` 을, `getRequiredXxx(key)` 는 "누락"을
보고한다. 도구는 명시적 `null` 과 생략을 구별할 수 없고, 오늘 그것이 필요한 도구는 없다(§8).

### 4.2 스키마 검증 (TCH-05)

`at.aimon.core.agent.tool.schema` 가 **모델이 실제로 저지르는 네 가지**만 잡는다.

| 검사 | 잡는 것 | 메시지 |
|---|---|---|
| `required` | 선언된 필수 파라미터 누락 (JSON `null` 도 누락 — §4.1) | `Parameter 'x' is required (string). The tool was not executed.` |
| `type` | 타입 불일치 | `Parameter 'x' must be an integer. …` |
| `enum` | 선언된 허용값 밖의 값 | `Parameter 'x' must be one of [a, b], but was 'c'. …` |
| 미선언 이름 | 오타 — `additionalProperties: false` 일 때만 | `Unknown parameter 'file_paht'. Did you mean 'file_path'? …` |

**JSON Schema 구현이 아니고, 되려 하지도 않는다.** 경계는 세 개이며 전부 의도된 것이다.

- **모르는 것은 통과시킨다.** 여기를 지나가는 스키마의 상당수는 우리가 쓰지 않았다 — MCP 서버의 스키마는
  서버가 보낸 그대로 도착한다. 파싱하지 못하는 것을 거부하면 낯선 서버 하나를 연결했을 때 멀쩡한 호출이
  무더기로 거부되고, 원인은 증상 근처에 없다. 그래서 `$ref`/`oneOf`/`anyOf`/`allOf`/`not` 을 쓰는
  프로퍼티는 **건너뛰고 형제는 건너뛰지 않으며**, 모르는 타입 이름도 건너뛴다.
- **모양은 여기서, 범위는 도구에서.** `minimum`/`maximum`/`minLength`/`minItems`/`default` 는 선언되어
  있어도 무시된다. 도구마다 처리 방식이 다르기 때문이다 — `BashTool` 은 과대한 timeout 을 거부하지 않고
  **clamp** 하므로(§3.1), 여기서 `maximum` 을 강제하면 지금 조용히 성공하는 호출이 에러가 된다.
  강제 지점이 둘이 되면 어느 쪽이 정본인지에 대한 답도 없어진다.
- **중첩은 1단계까지.** `object`/`array` 프로퍼티는 그 타입인지만 본다. 재귀하려면 `$ref` 와 `oneOf` 를
  진짜로 다뤄야 하고, 그 시점에서 이것은 JSON Schema 구현이다. 중첩 계약이 중요한 도구는 §4.4 로 간다.

반응 방식은 `SchemaValidationMode` 가 정한다 — `OFF` / **`WARN`(기본값)** / `ENFORCE`. `WARN` 에서는
위반이 로그로만 남고 도구는 그대로 실행되므로 **모델이 보는 것은 `OFF` 와 같다**. 관측 먼저, 거부는
그다음이다. 켜는 자리는 `new DefaultToolExecutionManager(mode)` 또는
`OrcaAgentExecutorFactory.withSchemaValidationMode(...)` 다.

**관문을 도구 안이 아니라 실행기에 둔 이유**는 셋이다 — 도구마다 스스로 검증하게 하면 (a) 규칙을 어기는
도구가 생기고 (b) 오류 문장 품질이 제각각이 되며 (c) MCP 로 들어온 도구는 우리가 손댈 수 없다.

**관문 밖에 남은 호출 지점은 목록으로 고정되어 있다.** `ToolExecutionGateArchitectureTest` 가
`Tool#execute` 를 부를 수 있는 예외를 클래스 리터럴로 못박는다 — `RecentFilesRestoreHook`,
`ReActLlmDeriver`, `RoutineExecutor`, `ArtifactAwareEditTool`, `ArtifactAwareWriteTool`. 목록의 목적은
이 다섯을 금지하는 것이 아니라 **여섯 번째를 논증거리로 만드는 것**이다. 제외 기준은 입력의 출처가
아니라 폭발 반경이다. 규칙이 호출뿐 아니라 **메서드 참조**(`tool::execute`)까지 보는 것도, 패키지 문자열이
아니라 클래스 리터럴을 쓰는 것도 같은 이유다 — 아무것도 매치하지 않는 규칙은 실패하지 않고 **조용히
통과**한다.

### 4.3 권한 판정 (TCH-06)

허용 목록의 항목은 `"이름"` 또는 `"이름(패턴)"` 이다. **이름은 그 도구를 실행해도 되는지**를, **패턴은
어떤 호출을 실행해도 되는지**를 정한다. 패턴을 무엇과 비교해야 하는지는 도구만 알 수 있으므로 —
`Bash` 는 `command`, `Read` 는 `file_path` — 도구가 그 값을 `PermissionSubject` 로 내놓는다.

| Kind | 판정 대상 | 매처 | 예 |
|---|---|---|---|
| `COMMAND` | 셸 명령줄 | `ToolPattern` — 끝의 `:*` 만 와일드카드 | `Bash(git:*)` |
| `PATH` | 절대·정규화된 파일 경로 | `PathPattern` — 글로브(`**`, `*`) | `Read(/tmp/**)` |

종류를 스펙 문자열에서 되살릴 수 없다는 것이 핵심이다. `AllowedTool` 파서는 괄호만 보고 `:` 를 해석하지
않으므로 `Read(/tmp/**)` 와 `Bash(git:*)` 는 파서 눈에 구분되지 않는다. 그래서 종류를 **도구가** 함께
실어 보낸다.

매처가 둘인 이유는 하나로 겸할 수 없기 때문이다. `ToolPattern` 은 후보에 셸 메타문자
(`;` `|` `&` `` ` `` `$` `>` `<` `(` `)`)가 있으면 거부하는데, `bash -c` 로 향하는 문자열에는 맞는
방어지만 경로에 적용하면 `report(1).csv` 같은 평범한 파일이 영영 닿지 않는다.

인터페이스는 둘이고, 주체 쪽이 기본이다.

| 인터페이스 | 언제 | 구현체 |
|---|---|---|
| `ToolPermissionSubjectAware` | 판정 대상이 입력 필드 하나로 정해진다 | `BashTool`, `ReadTool`, `EditTool`, `WriteTool` (+ 아티팩트 데코레이터 2개, 공통 로직은 `FilePathSubjects`) |
| `CustomToolPermissionAware` | 여러 입력의 조합이나 외부 조회가 필요하다 | `BrowserTool` / `ArtifactAwareBrowserTool` (`action:url` 문법) |

둘 다 구현한 도구는 **주체를 먼저** 본다 (`DefaultToolPermissionValidator.java:199-207`).

**이 항목이 닫은 fail-open 이 실질적인 보안 수정이다.** 예전에는 패턴이 선언되어 있는데 그 도구에
커스텀 규칙이 없으면 `return true` 했다 — 즉 `Read(/tmp/**)` 를 설정하면 `Read` 가 **무제한 허용**됐다.
가장 엄격해 보이는 설정이 가장 약한 강제를 만들고 있었다. 지금은 해석할 수 없는 패턴이 **거부**다
(`:209-211`). 같은 이유로 **빈 주체는 기권이 아니라 판정 불가**다 — 그 도구에 패턴이 설정되어 있으면
거부된다. 추측해서 통과시키는 쪽(상대 경로를 프로세스 CWD 로 푸는 식)은 JVM 이 어디서 떴는지에 따라
결과가 달라져 패턴을 쓴 사람이 예측할 수 없다.

`PATH` 주체는 절대 경로 + **어휘적** 정규화를 거친다. 파일 도구는 상대 경로를 `Environment` 의 작업
디렉토리로 풀고 `..` 을 접은 뒤 내놓으므로 `/tmp/../etc/passwd` 는 `Read(/tmp/**)` 를 통과하지 못한다.
심볼릭 링크는 풀지 않는다(§8).

### 4.4 `GenericTool<I, O>` (TCH-07)

`AbstractTool` 을 **대체하지 않고 그 옆에 서는** 선택적 베이스 클래스다. 입력 `record` 하나가 스키마와
파라미터 추출의 단일 출처가 된다.

| 타입 | 역할 |
|---|---|
| `GenericTool<I, O>` | `execute()` 가 **`final`** (`:161`). 하위 클래스는 `doExecute(I, ToolContext)`(`:209`)와 `render(O)`(`:222`)만 구현한다 |
| `ToolParam` | `@Target(RECORD_COMPONENT)` · `name` / `description` / `required` |
| `ToolSchemaGenerator` | `record` → JSON Schema. `additionalProperties: false` 를 **항상**, 중첩 `record` 까지 |
| `ToolInputBinder` / `BindResult` | `ToolInput` → `I` 바인딩. 위반을 **한 번에 모두** 보고한다 |

세 가지가 따라온다.

- **스키마와 추출이 어긋날 수 없다.** 둘 다 같은 `record` 에서 나온다. `additionalProperties: false` 가
  선택 사항이 아닌 것이 바인딩의 요점이다 — `record` 는 정확히 그 컴포넌트만 가진다.
- **`execute()` 가 `final` 이다.** "예외를 던지지 않는다"는 도구 계약이 하위 클래스의 성실함이 아니라
  타입으로 강제된다.
- **바인딩 위반이 §4.2 의 검증기와 같은 문장을 쓴다.** 두 계층이 서로 다른 시점에 잡지만, 모델이 같은
  불평을 두 가지 말투로 배울 이유는 없다.

와이어 이름은 **선언하는 것이지 변환되는 것이 아니다** — snake_case 라면 `@ToolParam(name = "file_path")`
로 적는다. 자동 변환은 없다 (`Grep` 의 `-i`·`-A` 처럼 애초에 식별자로 쓸 수 없는 이름이 있다).

현재 사용처: `GrepTool`(파라미터 13개), `WorkflowTool`, `BrowserTool`, `CopyToSandboxTool`,
`RunSandboxTool`. **`AbstractTool` 이 여전히 기본**이며, 파라미터가 서넛 이하이거나 입력 키 집합이
런타임에 정해지는 도구(MCP 위임)는 그쪽이 맞다.

---

## 5. 어느 베이스 클래스를 고를 것인가

| 상황 | 고를 것 |
|---|---|
| 파라미터가 서넛 이하이고 대부분 필수 | `AbstractTool` — 스키마를 손으로 쓰는 비용이 타입 하나를 더 만드는 비용보다 싸다 |
| 파라미터 5개 이상이거나 선택적 파라미터가 많다 | `GenericTool<I, O>` |
| 입력 키 집합이 런타임에 정해진다 (MCP 위임 등) | `AbstractTool` — 바인딩할 컴파일 타임 타입이 없다 |

`AbstractTool` 경로를 고르면 스키마 최상위에 `additionalProperties: false` 를 **직접** 넣어야 한다.
`at.aimon.core.tools` 안이라면 `BuiltInToolSchemaArchitectureTest` 가 빌드에서 확인한다 — 그 밖에 사는
내장 도구(`at.aimon.sandbox.tool`, Playwright, GraalJS)는 규칙은 같지만 테스트가 지켜 주지 않는다.

---

## 6. 설계 결정

### 6.1 고치지 않고 삭제한다

| 대안 | 판단 |
|---|---|
| `BashExecutor` 에 타임아웃·상한·exit code 를 추가한다 | **기각.** `VirtualShell`/`ExecutionOptions`/`ShellCommandResult` 가 이미 그것이다. 두 번째 셸 추상화를 만드는 셈이고, 다음 사람은 어느 쪽에 기능을 넣을지 매번 고민한다 |
| 어댑터(`VirtualShellBashExecutor`)만 남긴다 | **기각.** 어댑터의 존재 이유가 `String` 계약을 맞추는 것이고, 그 계약이 exit code 와 부분 출력을 버리는 원인이다 |
| SPI 폐기, `BashTool` 이 `VirtualShell` 소비 | **채택.** 삭제 3파일. 샌드박스(Docker/K8s) 셸이 조립의 `withShell(...)` 한 줄로 꽂힌다 |

새 결과 타입(`BashResult`/`BashCommand`)도 만들지 않았다 — `ShellCommandResult` 와 `ExecutionOptions` 가
필드까지 같았다. 새 타입은 변환 코드와 두 번째 진실만 만든다.

### 6.2 `grep` 무매치를 성공으로 보지 않는다 — 보류

`grep`/`diff`/`test` 가 exit 1 로 "결과 없음"을 알리는 것은 사실이고, 허용목록
(`{grep, rg, diff, test, find, cmp, …}`)으로 처리하는 선례도 있다. 채택하지 않은 이유는 **명령의 선두
실행 파일을 신뢰성 있게 뽑을 수 없기 때문**이다. `bash -c` 에 들어가는 문자열은 파이프·`&&`·환경변수
접두사·서브셸을 포함할 수 있고, 첫 토큰만 보는 휴리스틱은 `FOO=1 grep x f`, `cat f | grep x`,
`git diff` 에서 각각 다르게 틀린다. 틀리는 방향이 "실패를 성공으로 보고"라서 대가가 크다.

TCH-03 이 만든 상태가 이미 개선이다 — 모델이 `[exit code: 1]` 과 빈 출력을 함께 보므로 "매치 없음"을
읽어낼 수 있다.

### 6.3 도구 입력 DTO 에 한해 `record` 를 허용한다

프로젝트 규약은 `class` 선호다(`.claude/rules/code-style.md`). **예외는 `GenericTool` 의 입력 DTO
하나뿐이며**, 도메인 타입·값 객체·설정 객체는 포함되지 않는다.

근거는 라인 수가 아니라 규칙의 취지다. `immutability-pattern.md` 에는 `record` 라는 단어가 없다 —
걸리는 것은 **빌더 요구 조항**이다. 그런데 도구 입력 DTO 는 프레임워크가 역직렬화로만 만들고 애플리케이션
코드가 `new` 하지 않는다. 조립 단계가 없으므로 빌더가 지킬 불변식도 없다. 즉 충돌은 "`record` 예외"가
아니라 **"역직렬화 대상은 빌더 면제"** 라는 별개 단서로 해소된다. in-tree 선례도 같은 방향이다 —
`Todo` 는 관례대로 불변 클래스지만 **빌더가 없고** `@JsonCreator` 생성자 하나뿐이다(`Todo.java:44-46`).

라인 수는 그 위에 얹히는 확인일 뿐이다. `GrepTool`(파라미터 13개) 기준으로 스키마 45줄 + 추출 13줄 =
58줄이 `record` DTO 로는 약 28줄이 된다. 같은 것을 불변 클래스 + 빌더로 쓰면 `Todo` 의 밀도(필드 3개에
126줄)를 13개에 적용하게 되어 대체하려는 코드보다 길어진다.

### 6.4 스키마 생성을 런타임 리플렉션으로 한다

| 대안 | 판단 |
|---|---|
| 런타임 리플렉션 + `@ToolParam` + 자체 바인더 | **채택.** 새 의존성 0개. 같은 메타데이터가 스키마 생성(out)과 바인딩(in) 양쪽에 쓰여 이중 진실이 원천 차단된다 |
| 애노테이션 프로세서로 컴파일 타임 생성 | **기각.** 런타임 비용이 0 이지만 빌드 복잡도가 오른다. 스키마 생성은 도구 생성 시 1회뿐이라 런타임 비용이 애초에 문제가 아니다 |
| 타입 세이프 스키마 빌더 DSL 만 도입 | **기각.** `Map.of` 중첩은 줄지만 **이중 진실은 그대로**다. 스키마와 `input.getXxx()` 가 여전히 별개다 |

Jackson 을 바인더로 쓰지 않은 이유는 `jackson-module-parameter-names` 가 버전 카탈로그에 없어서 컴포넌트마다
`@JsonProperty` 를 따로 붙여야 하기 때문이다 — `@ToolParam` 과 이름을 두 번 쓰는 셈이다. 다만 `List<T>` 와
중첩 객체는 손으로 풀 이유가 없으므로 그 부분만 `ObjectMapper.convertValue` 에 위임한다.

**바인딩이 대체하지 않는 것 하나.** 업무 규칙 검증은 `doExecute()` 에 남는다 — `GrepTool` 의 ReDoS 방어
(`patternString.length() > MAX_PATTERN_LENGTH`)는 타입으로 표현되지 않는다. 없앤 것은 **추출**이지
**검증**이 아니다.

---

## 7. 의도적으로 제외한 것

| 제외 항목 | 이유 |
|---|---|
| **`Tool` 인터페이스의 제네릭화** (`Tool<I, O>`) | `ToolRegistry` 가 이종 `Tool` 을 담아야 해서 와일드카드가 전면에 번지고, MCP 도구는 컴파일 타임 입력 타입이 없어 어차피 `Map` 경로가 남는다. **두 근거 모두 인터페이스를 건드릴 때만 성립한다** — `AbstractTool` 옆에 선택적 베이스를 두는 TCH-07 은 둘 다 피해 간다 |
| `Tool#validateInput` 류의 검증 훅 | `PreToolHook` 이 이미 그 자리다 — 도구 호출 1건에 걸리는 차단 지점. 도구별 검증은 스키마로, 정책적 차단은 훅으로 간다. 세 번째 자리를 만들면 "어디에 쓸 것인가"가 매번 질문이 된다 |
| `<tool_use_error>` 오류 봉투 | 오류 신호는 이미 양쪽 프로바이더로 전달된다 — 다만 같은 방식이 아니다. Anthropic 은 프로토콜 필드(`isError`)로, OpenAI 는 그런 필드가 없어 **본문에 `"Error: "` 접두어**를 붙인다. 텍스트 봉투는 이미 OpenAI 경로에 존재하므로 프레임워크가 하나 더 씌우면 이중이 된다 |
| `Tool#aliases()` | 이름은 레지스트리가 소유한다. 도구가 자기 별칭을 선언하면 두 곳이 이름을 소유한다. `DefaultToolRegistry.normalizeName` 이 하는 `"functions."` 접두어 제거는 별칭이 아니라 프로바이더 보정이다 |
| 도구가 프로토콜 블록을 직접 만드는 `mapResult(O, toolUseId)` | 그 변환은 `ToolExecutionResultConverter` 가 소유한다. 도구가 프로바이더 프로토콜을 알면 안 된다. 렌더링 부가 정보가 필요하면 `ToolResult.renderPayload` 가 있다 |
| Bash 의 작업 디렉토리 격리 | `BashToolTurnIntegrationTest.bashIsNotConfinedToTheNodeRoot` 가 **의도적으로 고정한 열린 질문**이다. 배선 교체와 정책 변경을 한 커밋에 섞지 않았다 |

---

## 8. 보안과 남은 것

| 항목 | 상태 |
|---|---|
| **자원 고갈** | 해결 — TCH-01/02 가 프로세스·스레드 누수를 막는다 |
| **메모리 고갈** | 해결 — `maxCaptureBytes` 로 힙 상한이 생겼다. 실패 경로에도 상한이 있다 |
| **권한 fail-open** | 해결 — 해석할 수 없는 패턴은 거부된다 (§4.3) |
| **권한 우회 — 상대 경로** | 해결 — 어휘적 정규화로 `../` 를 접는다 |
| **오류 메시지의 정보 노출** | 스키마 위반 메시지는 파라미터 이름과 타입만 싣는다. `enum` 위반만 실제 값을 싣는다 — 선택지가 이미 스키마에 공개되어 있어 새는 정보가 없고, 값 없이는 고칠 수 없기 때문이다 |
| **권한 우회 — 심볼릭 링크** | **미해결.** `ToolContext` 에 VFS 가 실리지 않아 링크를 풀지 못한다. `Read(/allowed/**)` 는 `/allowed/link → /secrets` 를 막지 못한다 |
| **권한 우회 — TOCTOU** | **미해결.** 판정 시점과 파일을 여는 시점 사이에 경로가 바뀔 수 있다 |
| **프로세스 그룹 종료** | **미해결.** `descendants()` 스냅샷 이후에 태어난 손자는 살아남는다 (§3.2) |

IMPORTANT: **권한 시스템을 격리 경계로 쓰면 안 된다.** 권한 패턴은 에이전트가 *무엇을 요청할 수 있는지*
를 좁히는 것이고, 격리는 샌드박스의 일이다. 위 두 미해결 항목은 그 경계의 결과이지 버그가 아니다.

### 남은 작업

| 항목 | 상태 |
|---|---|
| `SchemaValidationMode` 를 `ENFORCE` 로 승격 | 열림 — `WARN` 로그 한 사이클이 "보고되는 위반이 정말로 모델의 실수"임을 보인 뒤에 올린다 |
| `ToolContextKeys.VIRTUAL_FILE_SYSTEM` 의 producer | 열림 — 키는 있는데 채우는 곳이 없다. **소비자는 이미 소스에 있고 죽어 있다**(`WikiIngestTool.java:92`). 진짜 비용은 경로가 느는 것이 아니라, 생성자 주입(`Read`/`Edit`/`Write`)과 컨텍스트 조회 중 어느 쪽이 정본인지 정하지 않은 채 양쪽을 살려 두는 것이다 |
| 프로세스 그룹 종료 (`setsid` + `kill(-pgid)`) | 열림 — 플랫폼 의존적이라 `VirtualShell` 구현 단위의 선택 사항으로 둔다 |
| exit code 허용목록 | 보류 (§6.2) — 모델이 무매치를 실패로 오해하는 것이 관측되면 재검토 |
| 중첩 스키마 재귀 검증 | 열림 — `$ref`/`oneOf` 까지 다루려면 JSON Schema 구현체를 들여야 하고, 그때는 자체 검증기를 버리는 결정이 함께 온다 |
| `schedule_task` 의 **예약 시점** 스키마 검증 | 열림 — `ScheduleTaskTool` 이 각 스텝의 `tool_params` 를 대상 도구 스키마로 검증한다. 발화 시점(`RoutineExecutor`)은 관문 밖으로 남기기로 했고 그 시점엔 오류를 고칠 모델도 없다. 비용은 `Supplier<ToolRegistry>` 주입 하나 |
| 입력 인지 동시성 정책 | 열림 — `getConcurrencyBehavior()` 는 인자가 없어 정책이 도구 단위로만 선언된다. `ToolInput` 을 받게 하면 `Edit`/`Write` 가 서로 다른 파일을 건드리는 경우를 안전으로 선언할 수 있으나, 같은 파일을 다투는지 판정할 `parallelResourceKeys` 가 함께 필요하다 ([`parallel-execution.md`](parallel-execution.md)) |
| 부재 vs 명시적 `null` 구분 | 열림 — 필요한 도구가 생기면 원본 맵을 `ToolInput` 옆에 별도로 싣는 방식으로 확장한다 |

---

## 부록 · 참조 파일 지도

| 항목 | 파일 |
|---|---|
| Bash 도구 | `at/aimon/core/tools/bash/BashTool.java` |
| 기본 배선 | `at/aimon/core/agent/impl/orca/tool/OrcaBashToolProvider.java`, `…/environment/LocalShells.java` |
| 셸 구현 | `at/aimon/core/shell/impl/local/LocalShell.java`, `at/aimon/core/shell/{VirtualShell,ExecutionOptions,ShellCommandResult}.java` |
| 입력 정규화 | `at/aimon/core/agent/tool/ToolInput.java`, `at/aimon/core/base/NullSafeMaps.java` |
| 스키마 검증 | `at/aimon/core/agent/tool/schema/` (`ToolInputSchemaValidator`, `DefaultToolInputSchemaValidator`, `SchemaValidationMode`, `SchemaValidationResult`, `ViolationMessages`) |
| 검증 관문 | `at/aimon/core/agent/tool/execution/DefaultToolExecutor.java`, `at/aimon/core/agent/tool/DefaultToolExecutionManager.java` |
| 권한 | `at/aimon/core/agent/tool/permission/` (`PermissionSubject`, `ToolPermissionSubjectAware`, `CustomToolPermissionAware`, `DefaultToolPermissionValidator`, `ToolPattern`, `PathPattern`, `AllowedTool`) |
| 권한 주체 구현 | `at/aimon/core/tools/file/FilePathSubjects.java` (+ `ReadTool`/`EditTool`/`WriteTool`), `BrowserToolPermissionRule` (`aimon-browser-playwright`) |
| 제네릭 도구 | `at/aimon/core/agent/tool/generic/` (`GenericTool`, `ToolParam`, `ToolSchemaGenerator`, `ToolInputBinder`, `BindResult`) |
| 아키텍처 테스트 | `architecture/ToolExecutionGateArchitectureTest.java`, `architecture/BuiltInToolSchemaArchitectureTest.java` |

## 관련 문서

- [`parallel-execution.md`](parallel-execution.md) — `ConcurrencyBehavior` 와 병렬 실행 게이트
- [`side-effect-axes.md`](side-effect-axes.md) — `SideEffectLevel` / `DestructiveBehavior`
- [`tool-search.md`](tool-search.md) — 도구 지연 로딩과 검색
- [`../integration/mcp-tool.md`](../integration/mcp-tool.md) — 제3자 스키마가 들어오는 경로
- [`../../features/tool/tool-development-guide.md`](../../features/tool/tool-development-guide.md) — 개발 가이드
