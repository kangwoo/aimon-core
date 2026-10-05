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

두 경로 모두 동일한 `OrcaSkillForkExecutorResolver`를 거쳐 fork executor를 결정한다. 다음 6가지(`Agent`, `SubagentRegistry`, `ToolRegistry`, `HookRegistry`, `UserLocale`, `SubagentExecutionManager`)가 모두 있으면 `SubagentBackedSkillForkExecutor`가 와이어링되고, 하나라도 없으면 `NoOpSkillForkExecutor`로 폴백한다. NoOp 폴백 상태에서 fork-mode 스킬을 호출하면 `Skill 'X' declares execution.mode=fork but fork execution is not configured`로 실패한다 — 인라인 전용 배포를 가능하게 하기 위한 의도된 동작이다.

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
  - **인자 패턴은 `도구(글롭)` 꼴로 받는다** — `"Bash(git push*)"` 는 `Bash` 의 서브커맨드 글롭, `"Edit(*.java)"` 는 경로 글롭이고, 항을 `|` 로 이으면 OR 다(`PredicateParser`). 글롭의 규칙은 이름 글롭과 같다: `*` 만 와일드카드이고 `/` 도 넘는다. 괄호를 받는 도구는 `Bash` 와 경로 도구(`Read` · `Edit` · `Write` · `MultiEdit` · `Glob` · `Grep` · `LS` · `NotebookEdit`)뿐이고, 다른 도구에 붙이면 스킬 로드가 실패한다. 문법 전체와 한계는 [hook 설정 가이드 › Matcher 문법](../features/hook/hook-config-guide.md#matcher-문법)에 있다.
  - **도구 권한의 패턴 문법과는 다른 문법이다.** `allowed-tools` 는 같은 괄호 표기를 쓰지만 명령은 `ToolPattern`(`git:*` 처럼 `:*` 로 끝나면 접두사), 경로는 `PathPattern`(`**` 는 임의 깊이, `*` 는 `/` 를 넘지 않는다)으로 읽는다. 매처에서는 `:` 와 `**` 가 리터럴이므로 `"Bash(git:*)"` 를 매처로 쓰면 오류 없이 로드되고 **아무 호출과도 맞지 않는다** — `git:` 로 시작하는 커맨드는 없다. 이 문서는 한때 인자 패턴을 "보류" 로 적고 그 예시를 권한 문법으로 들었다; 그 표기를 매처에 옮겨 적었다면 `"Bash(git *)"` 로 고친다. 정규식 · `&` · 입력 필드 지정도 매처 문법이 아니며 마찬가지로 조용히 빗나간다.
  - 도구 권한 쪽 인자 패턴(`AllowedTool`)은 [도구 개발 가이드 › 권한 시스템](../features/tool/tool-development-guide.md) 참조.
- `action.type: deny` — `preTool` 에서만 허용된다. `reason` 문자열은 LLM이 보는 차단 메시지가 되며 비어 있을 수 없다. `postTool` / `onStart` / `onStop` 은 인터페이스 계약상 비차단이라 `deny` 를 두면 파서가 거부한다.
- `action.type: shell`
  - `command` 는 비어 있지 않은 단일 문자열이며, 그대로 **실행 환경의 셸**에 전달된다(아래 "셸 실행 시맨틱" 참고).
  - `timeoutMs` 는 양의 정수(밀리초)다. 생략하면 실행자(`ShellActionExecutor`)의 기본값을 따른다.
  - 로딩 단계에서 `ShellActionExecutor.isShellSupported()` 가 `false` 인 환경(기본 와이어링)에서는 `shell` 액션이 선언된 SKILL 은 **parse 단계에서 실패**한다 — 런타임 첫 발화가 아니라 스킬 로드 시점에 즉시 명확한 에러로 surface 된다.

### 셸 실행 시맨틱

- 모든 셸 액션은 동일 스킬 호출 안에서 **동기·순차** 실행된다(병렬 발화 없음).
- **어디서 도는가.** 명령은 호스트 JVM 이 아니라 **hook 이 발화한 실행의 실행 환경**(`HookContext.getExecutionEnvironment()`)의 셸에서 돈다 — 같은 스킬의 `Bash` 호출이 도는 바로 그 셸이다. fork 모드에서는 **fork 자신의** 환경이다. 실행자(`DefaultShellActionExecutor`)는 셸을 쥐지 않고 발화할 때마다 컨텍스트에서 얻는다.
  - **작업 디렉터리는 워크스페이스다.** 로컬 환경에서도 그렇다 — 예전에는 호스트 JVM 의 작업 디렉터리였다. 상대 경로로 스크립트를 부르는 명령은 워크스페이스 기준으로 풀린다. 자기 스킬 디렉터리의 스크립트는 환경 변수 `AIMON_SKILL_DIR` 로 부른다 — `bash "$AIMON_SKILL_DIR/scripts/guard.sh"`. 사본에는 실행 비트가 없으므로 인터프리터로 실행한다.
  - **`AIMON_SKILL_DIR` 는 발화할 때마다, hook 이 도는 환경에 스테이징해서 얻는다.** 값은 그 실행의 `ExecutionEnvironment.stage(...)` 가 돌려준 경로다. 스킬을 호출한 쪽에서 본문을 렌더하며 스테이징한 경로를 물려받지 않는다 — hook 은 fork(와 fork 가 띄운 실행)에서 발화하고, fork 가 다른 환경에 놓이면 호출한 쪽의 사본은 거기에 없다. 이미 사본이 있는 환경에서는 마커 하나를 확인하는 비용이다.
  - **스테이징하지 못하면 명령은 돌지 않는다**(크기 상한 초과, 적재 뒤 디스크에서 바뀐 스킬 등). 변수 없이 돌리면 `"$AIMON_SKILL_DIR/scripts/guard.sh"` 가 `/scripts/guard.sh` 가 되어 다른 명령이 된다. 그 다음은 아래 "명령을 돌리지 못했을 때" 와 같다 — 가드 이벤트는 막고(`failOpen: true` 면 통과), 나머지는 WARN 만 남긴다.
  - **변수가 없는 경우**(빈 문자열이 아니라 unset): `hooks.json` 의 hook(스킬 디렉터리가 없다), 레지스트리를 거치지 않고 손으로 조립해 `StagedResource` 가 없는 스킬, 그리고 발화한 실행의 환경이 아닌 곳에서 명령을 돌리는 실행기(`HostShellActionExecutor`)를 스킬 파서에 물린 호스트.
  - **실행 환경이 없거나 사용 불가면 명령은 돌지 않는다.** 호스트로 되돌아가지 않는다. WARN 로그가 남고, 그 다음은 이벤트에 달렸다 — 아래 "명령을 돌리지 못했을 때" 를 볼 것.
  - 그래서 실행 밖에서 발화하는 이벤트(`onSessionStart` · `onSessionEnd` · `onConfigReload`)는 스킬 frontmatter 에 선언할 수 없다 — 실행 환경이 없어 셸 액션이 돌 곳이 없다. 파서가 스킬 로드 시점에 이유와 함께 거부한다. 그 이벤트는 `hooks.json` 에 선언한다(운영자 설정이며 호스트 셸에서 돈다).
- **종료 코드의 계약.** 스킬 fork 안에서 거부 채널이 있는 이벤트는 넷이다(`onStart` · `preTool` · `preCompact` 는 block, `permissionRequest` 는 deny). 거기서 **exit 2 는 거부**이고 stderr 가 사유로 LLM 에 surface 된다(`deny` 와 같은 경로). exit 0 은 허용이다. **그 밖의 종료 코드(1 · 3 · 130 …)는 허용**이다 — 깨진 스크립트가 조용한 게이트키퍼가 되면 안 되므로 WARN 만 남는다. 예외는 **126 · 127** 이다 — 셸이 "실행할 수 없다" · "찾지 못했다" 로 끝낸 것이라 스크립트는 돌지 않았고, 아래 "명령을 돌리지 못했을 때" 로 읽는다. 무엇이 막고 `failOpen` 이 무엇을 바꾸는지의 전체 표는 [hook 설정 가이드 › 가드가 막는 경우](../features/hook/hook-config-guide.md#가드가-막는-경우)에 있다.
  - **`onStart` 의 거부는 fork 를 시작하지 않는다.** exit 2 든 아래의 "돌리지 못함" 이든, fork 는 LLM 을 한 번도 부르지 않고 끝나고 `Skill` 도구의 결과는 `Skill fork failed for '<스킬>': Execution blocked by OnStart hook [SUBAGENT/<에이전트>]: <사유>` 다. 그 fork 의 `onStop` 은 발화하지 않는다(시작하지 않은 실행에는 멈춤도 없다 — 메인 실행과 같다). 스킬 fork 가 띄운 하위 fork 도 시작할 때 같은 hook 을 맞는다.
- **명령을 돌리지 못했을 때 — 가드는 막는다(fail-closed).** 종료 코드를 얻지 못하면 — 실행 환경 없음, 환경 사용 불가, 스킬 디렉터리 스테이징 실패, **timeout**, 셸 실패, 실행기가 던진 예외 — 또는 셸이 명령을 시작하지 못하면(**exit 126 · 127**: 스크립트가 그 환경에 없거나 실행 권한이 없다. 스크립트가 스스로 그 코드로 끝나도 구별되지 않아 똑같이 읽는다) 위 네 이벤트의 hook 은 **거부**한다. 판단하지 못한 가드는 통과시키지 않는다. 사유는 `Blocked: guard hook '<skill>' (<event>) could not run its command — <cause>: <detail>. …` 꼴이고 명령 문자열은 싣지 않는다 — 셸 실패와 예외는 `<detail>` 에 예외의 타입 이름만 싣는다(메시지는 명령을 담을 수 있어 로그에만 남는다).
  - **인터럽트된 실행의 hook 은 `failOpen` 과 무관하게 막는다.** 사용자가 실행을 중단하면 돌고 있던 hook 명령은 실행의 취소 신호로 멈추고(스레드 인터럽트에 반응하지 않는 셸에서도), 가드 이벤트의 결과는 거부다 — 끝나는 실행이 한 걸음 더 가지 않게 한다. 어떤 이벤트가 신호를 싣는지는 [hook 설정 가이드 › 가드가 막는 경우](../features/hook/hook-config-guide.md#가드가-막는-경우)에 있다.
  - 가드가 아니라 **관찰** 용도의 hook 이면 항목에 `failOpen: true` 를 선언한다(아래 예). 그러면 명령을 돌리지 못했을 때 WARN 만 남기고 통과한다. exit 2 는 `failOpen` 과 무관하게 여전히 거부다.
  - `failOpen` 은 YAML 불리언(`true` / `false`)만 받는다. `"true"` 나 `1` 은 스킬 로드 시점의 파싱 오류다 — 가드를 푸는 키라서 느슨하게 읽지 않는다. `shell` · `http` · `mcp` 액션에 똑같이 적용된다(`deny` 액션에 쓰면 WARN 후 무시된다 — 판정이 항상 있다).
  - **`preTool` 의 `http` · `mcp` 액션도 같은 규칙이다.** 정책 서버가 `decision: deny` 로 답하면 판정이고, 닿지 못했거나(연결 실패 · timeout · non-2xx · MCP 서버 미연결 · `isError`) 답을 읽을 수 없으면(`decision` 이 `allow` · `deny` · `defer` 가 아님) **판정 없음**이라 막는다. `failOpen: true` 면 WARN 후 통과한다. 어떤 응답이 판정인지는 [hook 설정 가이드 › `http`](../features/hook/hook-config-guide.md#http)에 있다.
  - 환경 제공자가 실패한 실행에서는 가드가 걸린 도구가 **환경을 쓰지 않는 것까지** 막힌다. `preCompact` 에 관찰 hook 을 걸었다면 `failOpen: true` 를 권한다 — 아니면 환경 장애 동안 자동 compaction 이 계속 건너뛰어진다.
- 그 밖의 이벤트(`postTool` · `onStop` · `subagentStart` · `subagentStop` · `permissionDenied` · `postCompact`)는 비차단 — 어떤 종료 코드도, 돌리지 못한 것도 경고 로그로만 기록되고 메인 흐름에 영향이 없다.

```yaml
hooks:
  preTool:
    - matcher: Bash
      action: { type: shell, command: "audit.sh", timeoutMs: 5000 }
      failOpen: true   # 관찰용 — audit.sh 를 돌리지 못해도 Bash 는 실행된다
```
- 환경 변수가 매 발화마다 주입된다. 아래 표는 `SkillHookEnv` 상수와 1:1 대응한다(이름을 바꾸는 것은 break change). 같은 값이 stdin 의 JSON 에도 접두어를 떼고 소문자로 실린다(`AIMON_SKILL_DIR` → `skill_dir`).

| 변수 | 값 | 발화 이벤트 |
|------|----|------------|
| `AIMON_HOOK_EVENT` | `preTool` / `postTool` / `onStart` / `onStop` | 모든 이벤트 |
| `AIMON_SKILL_NAME` | `SkillMetadata.name` | 모든 이벤트 |
| `AIMON_SKILL_DIR` | 스킬 디렉터리를 hook 이 도는 환경에 스테이징한 절대 경로. 자르지 않는다 | 모든 이벤트 (스킬이 선언한 hook 만. `hooks.json` 에서는 unset) |
| `AIMON_INVOKER_NAME` | 호출 에이전트 이름 | 모든 이벤트 |
| `AIMON_INVOKER_TYPE` | `MAIN_AGENT` / `SUBAGENT` | 모든 이벤트 |
| `AIMON_TOOL_NAME` | 발화 대상 도구 이름 | `preTool`, `postTool` |
| `AIMON_ITERATION` | 1-based ReAct 루프 인덱스 | `preTool`, `postTool` |
| `AIMON_TOOL_RESULT_STATUS` | `success` / `error` | `postTool` |
| `AIMON_USER_MESSAGE_LENGTH` | 원본 사용자 메시지 길이(문자수) | `onStart` |
| `AIMON_SUCCESS` | `true` / `false` | `onStop` |
| `AIMON_ITERATION_COUNT` | 종료 시점 누적 iteration | `onStop` |

### 적용 범위 / 호스트 와이어링

- **hook 은 그 스킬의 fork 에서만 발화한다.** fork 한 SubAgent 와, 그 SubAgent 가 다시 띄운 fork(`Task` · `Workflow` · `WorkflowJs` 의 foreground · 중첩 스킬)의 이벤트에 발화한다. **같은 에이전트의 다른 세션에는 발화하지 않고, 스킬을 호출한 실행 자신에게도 발화하지 않는다.** 스킬 hook 은 런타임의 `HookRegistry` 에 등록되지 않는다 — fork 가 받는 레지스트리 위에 얹힌다(`SkillScopedHookRegistry`).
- **inline 모드**에서는 얹을 fork 가 없어 hook 이 발화하지 않는다(의도된 동작; `SkillHookActivator` 인터페이스 Javadoc 참고). hook 을 선언한 inline 스킬은 거부되지 않지만 로드 시 WARN 이 남는다.
- **수명.** 스킬 호출(`Skill` 도구 호출 또는 슬래시 명령)이 답을 돌려줄 때까지다. 그 뒤에도 도는 것 — fork 가 `run_in_background` 로 띄운 `Task` — 은 스킬이 끝난 시점부터 스킬 hook 없이 돈다. 그래서 가드 hook 이 있는 스킬에서는 그것을 띄울 수 없다(바로 아래).
- **background `Task` 제한.** **가드 hook(`onStart` · `preTool` · `preCompact` · `permissionRequest`)이 활성인 스킬 fork 안에서는 `Task` 의 `run_in_background` 가 거절된다** — `Background mode is not available here: skill '<스킬>' has guard hooks active and a background subagent would keep running without them once the skill returns. Run the task in the foreground (omit run_in_background).` foreground `Task` 는 스킬보다 먼저 끝나므로 그대로 돌고 가드를 맞는다. 관찰 전용 hook 만 있는 스킬에서는 background `Task` 가 실행되고, 스킬이 끝난 뒤로는 그 SubAgent 에 hook 이 발화하지 않는다(WARN).
- **background 워크플로 제한.** `Workflow` · `WorkflowJs` 의 `mode: background` 는 호출한 실행에서 아무 것도 물려받지 않는 에이전트 범위 러너에서 돌므로 스킬 hook 이 따라가지 못한다. 그래서 **가드 hook(`onStart` · `preTool` · `preCompact` · `permissionRequest`)이 활성인 스킬 fork 안에서는 background 모드가 거절된다** — 도구 오류가 foreground 로 실행하라고 알린다. `onStart` 만 선언한 스킬도 여기에 든다. 관찰 전용 hook 만 있으면 실행되지만 그 워크플로의 SubAgent 에는 hook 이 발화하지 않는다(백로그 EE-63).
- **`ScheduleTask` 제한.** 같은 이유로 가드 hook 이 활성인 스킬 fork 안에서는 `ScheduleTask` 가 거절된다 — 루틴은 나중에 런타임 레지스트리 위에서 발화하므로 스킬의 가드가 따라가지 못한다. 스킬 밖에서 예약할 것.
- **호출 경로와 무관하다.** 모델이 `Skill` 도구로 부르든 사용자가 슬래시 명령(`/my-skill`)으로 부르든 fork 모드 스킬의 hook 은 같은 방식으로 그 fork 에 얹힌다 — `SkillTool` 과 `LlmSkillExecutor` 가 둘 다 `OrcaSkillHookActivatorResolver` 가 고른 활성화기로 호출한 실행의 레지스트리 위에 얹는다(백로그 EE-68, 이전에는 슬래시 경로에서 hook 이 꺼졌다). 슬래시 경로의 활성화기는 런타임에서 고르므로, 직접 등록한 `SkillTool` 에 `NoOpSkillHookActivator` 를 준 호스트도 `/skill` 에서는 hook 이 켜진다.
- HookRegistry 와이어링: `OrcaSkillHookActivatorResolver` 가 `HookRegistry` 가 있으면 `ScopedSkillHookActivator` 를, 없으면 `NoOpSkillHookActivator` 를 고른다 — `OrcaSkillToolProvider` 는 그것을 `SkillTool` 에 넘기고, `OrcaAgentExecutor` 는 슬래시 명령 컨텍스트에 `ToolContextKeys.SKILL_HOOK_ACTIVATOR_KEY` 로 싣는다. 사용자 정의 `SkillForkExecutor` 와 SubAgent 를 스폰하는 사용자 정의 도구는 fork 의 레지스트리를 `HookRegistryAccess.of(toolContext)` 에서 얻어야 한다 — 생성자에서 받은 런타임 레지스트리를 넘기면 그 fork 에서 스킬 hook 이 꺼진다.
- 셸 와이어링: `MarkdownSkillParser` 의 기본 생성자는 `NoOpShellActionExecutor` 를 사용해 `shell` 액션을 거부한다. `aimon-cli` 의 `AgentSetupFactory` 와 `aimon-bootstrap` 의 `AimonStackBuilder` 는 `DefaultShellActionExecutor`(인자 없음 — 셸을 쥐지 않는다)로 만든 파서를 모든 스킬 로더(번들/사용자 정의)에 주입하므로, 그 환경에서는 `shell` 액션이 그대로 동작한다. 스킬 hook 용 호스트 셸은 어디에도 없다. 다른 호스트는 `MarkdownSkillParser(ShellArgumentTokenizer, SkillHookSetParser(executor))` 와 `DefaultSkillRegistry(fs, dir, parser)` / `*AgentBundleLoader(..., parser)` 4-arg 오버로드를 사용해 동일한 파서를 주입한다.
- 활성화는 공유 상태를 건드리지 않는다: 레지스트리에 등록하지 않으므로 도중에 실패해도 되돌릴 것이 없다.

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
