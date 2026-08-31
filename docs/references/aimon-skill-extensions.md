# AIMON Skill Extensions

이 문서는 AIMON이 [Agent Skills 표준](agentskills-specification.md)에 더한 **확장 frontmatter 필드**를 정리한다. 표준 필드(`name`, `description`, `license`, `compatibility`, `metadata`, `allowed-tools`)는 본 문서에서 다루지 않는다 — 표준 명세를 따르며, 사양이 바뀌면 표준 문서 쪽이 단일 소스다.

확장 필드 파싱 규칙은 `MarkdownSkillParser`에 구현되어 있고, 시맨틱 검증은 `SkillMetadata.Builder`가 수행한다. 본 문서와 코드의 시맨틱이 어긋나면 코드가 정답이다.

---

## arguments — 위치 인자 이름

```yaml
arguments: [target, severity]
```

- 타입: `list<string>`
- 기본값: `[]`
- 검증: 비어 있지 않은 식별자, 중복 불가
- 의미: 사용자가 `/skill <name> <a> <b>`로 호출했을 때 `$1=a`, `$2=b`로 매핑된다. 본문의 `$1`..`$9`는 위치 기반 치환이며, `arguments`는 **이름을 문서화**할 뿐 본문 치환 토큰을 바꾸지 않는다.
- 관련 토큰: 본문에서 `$ARGUMENTS`(전체 raw)·`$0`(전체 raw)·`$1..$9`(위치)·`$ARG_COUNT`(인자 개수). 자세한 규칙은 `DefaultSkillContentRenderer` 참고.

---

## invoke — 호출 정책

```yaml
invoke:
  user: true
  model: false
```

- 타입: `mapping{user?: boolean, model?: boolean}`
- 기본값: `{user: true, model: true}`
- 의미:
  - `user=false` 인 스킬은 사용자 슬래시 호출(`/skill <name>`) 대상에서 제외된다.
  - `model=false` 인 스킬은 LLM에게 노출되는 `SkillTool` 설명 목록에서 숨겨진다 — 모델이 직접 부를 수 없다.
- 다른 키 사용 시 파서가 거부한다.

---

## max-iterations — 사용자 호출 시 ReAct 루프 상한

```yaml
max-iterations: 25
```

- 타입: `integer ≥ 1`
- 기본값: `100` (`SkillMetadata.DEFAULT_MAX_ITERATIONS`)
- 적용 시점: **사용자가** 스킬을 호출해 ReAct 루프가 시작될 때만 사용된다. 모델이 도구로 호출하는 경로에는 영향이 없다 — 그 경로는 부모 에이전트의 iteration 정책을 따른다.

---

## execution — 실행 모드

```yaml
execution:
  mode: fork
  agent: code-reviewer
```

- 타입: `mapping{mode: 'inline'|'fork', agent?: string}`
- 기본값: `mode: inline`
- 시맨틱:
  - `inline` (기본) — 스킬 본문이 부모 에이전트의 turn에 그대로 주입된다. `agent` 필드를 같이 두면 파서가 거부한다.
  - `fork` — `SkillTool`이 본문을 렌더링한 뒤 `execution.agent`에 지정된 SubAgent를 새 컨텍스트에서 spawn하고, 그 SubAgent의 최종 답변을 결과로 반환한다. `agent`가 비어 있으면 빌드 시점에 거부된다.
- 알 수 없는 키(`mode`/`agent` 외)는 파서가 거부한다.

### Fork-mode 동작 디테일

Fork 시맨틱은 두 호출 경로 모두에 적용된다:

- **LLM tool-call 경로** — `SkillTool`이 fork 분기를 처리한다. SubAgent의 최종 답변은 `=== Skill Forked ===` 블록으로 감싸 LLM에 돌려준다.
- **User-slash 경로** — `LlmSkillExecutor`가 동일한 `SkillForkExecutor`를 통해 fork 분기를 처리한다. SubAgent의 최종 답변은 그대로 `SkillExecutionResult.success(...)`의 응답으로 surfacing된다(슬래시 호출에서는 `=== Skill Forked ===` 래핑을 추가하지 않는다).

공통 흐름:

1. 본문이 일반 절차대로 렌더링된다. `$ARGUMENTS`/`$1..$9` 치환은 fork에서도 동일하게 일어나며, 렌더된 본문이 SubAgent의 **goal**이 된다.
2. `SubagentBackedSkillForkExecutor`가 `SubagentRegistry`에 `execution.agent`가 등록돼 있는지 먼저 확인한다 — 없으면 `Skill 'X' references unknown subagent 'Y'`로 즉시 실패하고 `SubagentExecutionManager`를 호출하지 않는다.
3. `ToolContext`에 `AGENT_RUNTIME_ID`가 없으면 fork를 진행하지 않고 명확한 에러를 반환한다. User-slash 경로에서는 `OrcaAgentExecutor`가 현재 `AgentRuntime`의 ID를 `CommandExecutionManager`에 함께 전달하므로 정상 호출 시 항상 채워진다.
4. SubAgent 실행 결과가 `success`면 LLM tool-call 경로(SkillTool)는 다음 형식으로 결과를 감싸 반환한다:

   ```
   === Skill Forked ===
   Skill: <name>
   Agent: <agent>

   Final Answer:
   <final answer>
   ```

   실패면 두 경로 모두 `Skill fork failed for '<name>': <message>` 형태로 surfacing한다(LLM 경로는 `ToolResult.error`, 슬래시 경로는 `SkillExecutionResult.failure`).

### Fork executor 와이어링

두 경로 모두 동일한 `OrcaSkillForkExecutorResolver`를 거쳐 fork executor를 결정한다. 다음 6가지(`Agent`, `SubagentRegistry`, `ToolRegistry`, `HookRegistry`, `Environment`, `SubagentExecutionManager`)가 모두 있으면 `SubagentBackedSkillForkExecutor`가 와이어링되고, 하나라도 없으면 `NoOpSkillForkExecutor`로 폴백한다. NoOp 폴백 상태에서 fork-mode 스킬을 호출하면 `Skill 'X' declares execution.mode=fork but fork execution is not configured`로 실패한다 — 인라인 전용 배포를 가능하게 하기 위한 의도된 동작이다.

- **LLM tool-call 경로** — `OrcaSkillToolProvider`가 `SkillTool` 등록 시점에 resolver를 호출해 fork executor를 `SkillTool` 생성자에 주입한다.
- **User-slash 경로** — `OrcaAgentExecutor.executeCommand`가 매 슬래시 호출마다 resolver를 호출하고, 결과를 `ToolContext`의 `ExtToolContextKeys.SKILL_FORK_EXECUTOR_KEY`에 실어서 `LlmSkillExecutor`에 전달한다. `LlmSkillExecutor`는 `ToolContext`에 키가 있으면 그 executor를 우선 사용하고, 없으면 생성자에서 받은 fallback(보통 NoOp)을 쓴다. 따라서 `OrcaAgentExecutor` 경유 호출은 LLM tool-call 경로와 동일한 SubagentBacked 와이어링을 자동으로 받는다.

---

## hooks — 스킬 단위 hook 스코프

```yaml
hooks:
  preTool:
    - matcher: "Bash"
      action: { type: deny, reason: "Bash not allowed inside this skill" }
    - matcher: "Read"
      action: { type: shell, command: "echo $AIMON_TOOL_NAME >&2", timeoutMs: 5000 }
  postTool:
    - matcher: "*"
      action: { type: shell, command: "logger result=$AIMON_TOOL_RESULT_STATUS" }
  onStart:
    - action: { type: shell, command: "echo skill=$AIMON_SKILL_NAME started" }
  onStop:
    - action: { type: shell, command: "echo skill=$AIMON_SKILL_NAME success=$AIMON_SUCCESS" }
```

- 타입: `mapping{event-name: list<hook-def>}`
- 기본값: `SkillHookSet.empty()` (즉, 키 자체가 없으면 무동작)
- 의미: 스킬이 호출되는 동안에만 `HookRegistry`에 임시로 등록되는 hook 묶음. `SkillTool.execute()` 진입 시 `SkillHookActivator`가 등록하고, 결과 반환(성공/실패 무관) 시점에 LIFO 순서로 등록 해제한다.
- 지원되는 이벤트: `preTool`, `postTool`, `onStart`, `onStop`. compaction-lifecycle hook(`PreCompactHook`/`PostCompactHook`)은 단일 스킬 호출 범위와 lifetime이 맞지 않아 의도적으로 제외한다.

### hook-def 스키마

```
hook-def := { matcher?: string, action: action-def }
action-def := { type: "deny", reason: string }
            | { type: "shell", command: string, timeoutMs?: integer }
```

- `matcher` (선택)
  - `preTool` / `postTool` 에서만 허용된다 — `onStart` / `onStop` 에 두면 파서가 거부한다.
  - 생략 또는 `"*"` 은 모든 도구에 매칭된다(`NameOnlyPredicate.ANY`).
  - `*` 가 섞여 있으면 **글롭 매처**(SK-13 Phase 2)다. `*` 는 0개 이상의 임의 문자에 대응하고, 그 밖의 모든 문자(정규식 메타문자 포함: `.`, `(`, `+` 등)는 리터럴로 취급된다. 예: `"Read*"` → `Read`/`ReadTool`/`Readme` 매칭, `"*Tool"` → `Tool`/`BashTool` 매칭, `"*Tool*"` → 부분 문자열 매칭.
  - `*` 가 없는 문자열은 **정확한 이름 매칭**이다.
  - 인자 패턴(`"Bash(git:*)"`, `"Edit(**/*.java)"`)은 SK-13 Phase 3 으로 보류 — 이름만 받던 매처 API 자체를 도구 입력까지 받도록 넓혀야 하므로 별도 WU 다. Phase 2 글롭은 매처 시그니처를 그대로 두므로 `Declarative*Hook` / `SkillHookSetParser` 변경 없이 추가됐다.
  - 다만 **문법과 매칭기는 이미 있다.** 도구 권한 계층(`at.aimon.core.agent.tool.permission`)이 같은 표기를 쓰며, 값의 종류에 따라 매처가 갈린다 — 명령은 `ToolPattern`(`git:*` 처럼 `:*` 로 끝나면 접두사, 아니면 완전 일치), 경로는 `PathPattern`(글롭. `**` 는 임의 깊이, `*` 는 `/` 를 넘지 않는다). Phase 3 는 세 번째 문법을 만들지 말고 이 둘을 재사용해야 한다. 위 예시를 `"Edit(*.java)"` 가 아니라 `"Edit(**/*.java)"` 로 적은 것도 그 규칙에 맞춘 것이다 — `*` 가 `/` 를 넘지 않으므로 `*.java` 는 디렉토리 없는 파일명에만 걸린다.
  - 이 표기는 **후크 매처**에만 해당하는 보류다. 도구 권한 쪽 인자 패턴(`AllowedTool`)은 보류가 아니라 동작하며, 경로 패턴까지 포함한다 — [도구 개발 가이드 › 권한 시스템](../features/tool/tool-development-guide.md) 참조.
- `action.type: deny` — `preTool` 에서만 허용된다. `reason` 문자열은 LLM이 보는 차단 메시지가 되며 비어 있을 수 없다. `postTool` / `onStart` / `onStop` 은 인터페이스 계약상 비차단이라 `deny` 를 두면 파서가 거부한다.
- `action.type: shell`
  - `command` 는 비어 있지 않은 단일 문자열이며, 그대로 호스트 셸에 전달된다(아래 "셸 실행 시맨틱" 참고).
  - `timeoutMs` 는 양의 정수(밀리초)다. 생략하면 실행자(`ShellActionExecutor`)의 기본값을 따른다.
  - 로딩 단계에서 `ShellActionExecutor.isShellSupported()` 가 `false` 인 환경(기본 와이어링)에서는 `shell` 액션이 선언된 SKILL 은 **parse 단계에서 실패**한다 — 런타임 첫 발화가 아니라 스킬 로드 시점에 즉시 명확한 에러로 surface 된다.

### 셸 실행 시맨틱

- 모든 셸 액션은 동일 스킬 호출 안에서 **동기·순차** 실행된다(병렬 발화 없음).
- `preTool` 발화 결과가 비-zero exit 이거나 timeout 이면 도구 실행이 차단되고 에러 메시지가 LLM 로 surface 된다(`deny` 와 같은 경로).
- `postTool` / `onStart` / `onStop` 은 비차단 — 비-zero / timeout 은 경고 로그로만 기록되고 메인 흐름에 영향이 없다.
- 환경 변수가 매 발화마다 주입된다. 아래 표는 `SkillHookEnv` 상수와 1:1 대응한다(이름을 바꾸는 것은 break change).

| 변수 | 값 | 발화 이벤트 |
|------|----|------------|
| `AIMON_HOOK_EVENT` | `preTool` / `postTool` / `onStart` / `onStop` | 모든 이벤트 |
| `AIMON_SKILL_NAME` | `SkillMetadata.name` | 모든 이벤트 |
| `AIMON_INVOKER_NAME` | 호출 에이전트 이름 | 모든 이벤트 |
| `AIMON_INVOKER_TYPE` | `MAIN_AGENT` / `SUBAGENT` | 모든 이벤트 |
| `AIMON_TOOL_NAME` | 발화 대상 도구 이름 | `preTool`, `postTool` |
| `AIMON_ITERATION` | 1-based ReAct 루프 인덱스 | `preTool`, `postTool` |
| `AIMON_TOOL_RESULT_STATUS` | `success` / `error` | `postTool` |
| `AIMON_USER_MESSAGE_LENGTH` | 원본 사용자 메시지 길이(문자수) | `onStart` |
| `AIMON_SUCCESS` | `true` / `false` | `onStop` |
| `AIMON_ITERATION_COUNT` | 종료 시점 누적 iteration | `onStop` |

### 적용 범위 / 호스트 와이어링

- **fork 모드**에서만 실질적으로 의미가 있다 — scope 가 spawn 된 SubAgent 의 lifetime 을 감싸므로 forked agent 의 tool 호출이 hook 을 관측한다.
- **inline 모드**에서는 scope 가 렌더링 단계에만 걸쳐 있어 hook 이 사실상 발화하지 않는다(의도된 동작; `SkillHookActivator` 인터페이스 Javadoc 참고).
- HookRegistry 와이어링: `OrcaSkillToolProvider` 가 컨텍스트에 `HookRegistry` 가 있으면 `RegistryBackedSkillHookActivator` 를, 없으면 `NoOpSkillHookActivator` 를 자동으로 결정한다.
- 셸 와이어링: `MarkdownSkillParser` 의 기본 생성자는 `NoOpShellActionExecutor` 를 사용해 `shell` 액션을 거부한다. `aimon-cli` 의 `AgentSetupFactory` 는 `LocalShell` 을 띄우고 `DefaultShellActionExecutor` 로 감싼 파서를 모든 스킬 로더(번들/사용자 정의)에 주입하므로, CLI 환경에서는 `shell` 액션이 그대로 동작한다. 다른 호스트는 `MarkdownSkillParser(ShellArgumentTokenizer, SkillHookSetParser(executor))` 와 `DefaultSkillRegistry(fs, dir, parser)` / `*AgentBundleLoader(..., parser)` 4-arg 오버로드를 사용해 동일한 파서를 주입한다.
- 부분 등록 안전성: 등록 도중 RuntimeException 이 발생하면 이미 성공한 등록은 LIFO 로 즉시 롤백된 뒤 예외가 그대로 전파된다 — leak 없이 호출이 실패한다.

### 프로그래매틱 hook (옵션)

YAML 로 표현하기 어려운 hook(상태 보유, 외부 의존)은 여전히 코드로 만들 수 있다.

```java
SkillHookSet hooks = SkillHookSet.builder()
        .addPreTool(myAuditHook)
        .addPostTool(myMetricsHook)
        .build();

SkillMetadata metadata = SkillMetadata.builder().name("review").description("...").hooks(hooks).build();
```

선언형(`hooks:` frontmatter)과 프로그래매틱(`SkillMetadata.Builder#hooks`)은 같은 `SkillHookSet` 추상화를 공유하므로 한 스킬에서 둘 다 사용해도 된다 — 활성화 순서는 등록 순(LIFO 해제)이다.

---

## 호환성 노트

- 표준 frontmatter만 사용하는 스킬은 모든 AIMON 버전에서 그대로 동작한다.
- 본 확장 필드는 다른 Agent Skills 런타임에서는 무시된다(또는 거부된다). AIMON 외부에서도 쓸 의도가 있는 스킬은 표준 필드만 사용해 작성한다.
- 새 확장이 추가되면 본 문서의 표 + 동작 디테일을 같은 PR에서 갱신한다. 표준 명세 문서(`agentskills-specification.md`)는 변경하지 않는다.
