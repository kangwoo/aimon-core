# Skill-Command 통합 — 슬래시 명령의 단일 진실

> Status: **IMPLEMENTED** — 사용자 정의 슬래시 명령의 단일 진실은 Skill 이고(`SkillBackedCommandRegistry`),
> 실행은 `SkillExecutor` 로 라우팅되며(`SkillBackedCommandExecutor`), 본문 렌더링은 모델·사용자 양쪽이
> 같은 `SkillContentRenderer` 를 통과하고, 이름 충돌은 부팅에서 거부된다(`CommandNameConflictDetector`).
> `CustomCommand` / `CustomCommandRegistry` 는 제거되었고 레거시 `.aimon/commands/*.md` 가 남아 있으면
> 런타임 생성 자체가 실패한다. 남은 것은 §8 — 스킬별 `model` 오버라이드는 아직 없다.
>
> 적용 대상: `aimon-core` — `at.aimon.core.command`(`DefaultCommandRegistry`,
> `CommandNameConflictDetector`, `CommandType`), `…command.skill`(어댑터 2종),
> `…command.execution`(`CompositeCommandExecutor`, `…execution.skill.SkillBackedCommandExecutor`),
> `at.aimon.core.skill.execution`(`SkillExecutor`·`LlmSkillExecutor`·`SkillToolDispatcher`),
> `…skill.render`(`DefaultSkillContentRenderer`), `…skill.repository`(`PathSkillRepository` 로더 정책).

---

## 1. 문제 — 같은 것을 두 포맷으로 정의하고 있었다

`/<name>` 로 부르는 사용자 정의 명령과, LLM 이 도구로 부르는 스킬은 **하는 일이 같았다**: 마크다운 본문 +
frontmatter 로 선언된 도구 권한 + ReAct 루프. 그런데 정의하는 자리가 둘이었다.

```
ReplSession (/foo args)
    └─► DefaultCommandRegistry
            ├─► SystemCommandRegistry  ── help, version, /skills, …
            └─► CustomCommandRegistry  ── .aimon/commands/<name>.md
                       └─► CustomCommand ── LlmExecutable
                                  └─► LlmCommandExecutor
                                         ├─ ToolCallFormatter (!cmd, @file)
                                         ├─ ArgumentInterpolator ($1..$N)
                                         └─ ReAct loop

SkillRegistry  ── .aimon/skills/<name>/SKILL.md
    └─► Skill (모델 경로만 활성)
            └─► SkillTool
                   └─► SkillContentRenderer   ← 별도 렌더 경로
```

비용은 기능마다 두 번이었다. 새 frontmatter 키를 넣으면 파서 두 곳, 렌더 규칙을 고치면 렌더러 두 곳,
`allowed-tools` 의미를 정하면 두 번 정해야 했다. 그리고 두 렌더 경로가 **조용히 갈라졌다** — 같은 본문을
`/foo` 로 부를 때와 모델이 부를 때 결과가 달랐다.

---

## 2. 통합 결과

```
ReplSession (/foo args)
    └─► DefaultCommandRegistry
            ├─► SystemCommandRegistry          (1순위)
            └─► SkillBackedCommandRegistry     (2순위)
                       └─► SkillBackedCommand (어댑터, LlmExecutable)
                                  └─► SkillBackedCommandExecutor
                                             └─► SkillExecutor (LlmSkillExecutor)

SkillRegistry  ── .aimon/skills/<name>/SKILL.md   (디렉터리 단일 포맷)
    └─► Skill (invokePolicy = {user, model})
            ├─► (model)  SkillTool          ─┐
            └─► (user)   SkillBackedCommand ─┴─► SkillContentRenderer  (단일 렌더)
```

바뀐 것은 셋이다.

1. **저장소가 하나다.** `SkillRegistry` 가 유일한 소유자이고, `SkillBackedCommandRegistry` 는 그것을
   `invoke.user=true` 로 걸러 `Command` 로 **비추기만** 한다.
2. **렌더러가 하나다.** 인자 치환과 컨텍스트 토큰이 `SkillContentRenderer.render()` 한 번에서 끝난다.
3. **실행기가 하나다.** ReAct 루프는 `LlmSkillExecutor` 에만 있고, 슬래시 경로는 어댑터를 통해 거기로 간다.

---

## 3. 단일 진실의 원천 — Skill

### 3.1 디렉터리 단일 포맷

```
.aimon/skills/commit/
  SKILL.md          ← frontmatter + body (필수)
  reference.md      ← (선택) 본문이 참조하는 자료
  templates/feat.md ← (선택)
```

로더(`PathSkillRepository`) 정책은 두 줄이다 — `<name>/SKILL.md` 가 있는 디렉터리만 스킬이고,
`.aimon/skills/foo.md` 처럼 **평평하게 놓인 마크다운은 WARN 후 무시**하며 로그가 옮겨야 할 경로를 그대로
적어 준다. 부가 자료는 디렉터리에 있다는 것만으로 주입되지 않는다 — 본문이 명시적으로 참조할 때만 의미가
있다.

| 기각한 대안 | 이유 |
|-------------|------|
| 단일 파일 포맷(`.aimon/skills/<name>.md`) 병행 | 로더가 두 레이아웃을 다루게 되고 "한 디렉터리 = 한 스킬" 이라는 불변식이 깨진다. 한 파일의 가벼움은 정의 1회의 비용일 뿐이고, 그 대가로 부가 자료 동거를 잃는다 |
| 평평한 마크다운을 관대하게 받아 주기 | 조용히 로드되면 사용자는 자기 파일이 어느 규칙으로 읽혔는지 모른다. 무시 + WARN 이 "왜 안 뜨지" 를 가장 빨리 끝낸다 |

### 3.2 frontmatter

```markdown
---
name: commit
description: Create a git commit with proper message
allowed-tools:
  - Bash(git add:*)
  - Bash(git commit:*)
  - Read
invoke:
  user: true
  model: false
max-iterations: 50
---
```

| 키 | 뜻 | 기본값 |
|----|-----|--------|
| `description` | `/<name>` 도움말 + LLM tool description | — |
| `allowed-tools` | ReAct 동안 호출 가능한 도구 화이트리스트 | `List.of()` |
| `invoke.user` | 슬래시로 부를 수 있는가 | `false` |
| `invoke.model` | LLM 에게 도구로 노출되는가 | `true` |
| `max-iterations` | ReAct 최대 라운드 | `100` (`SkillMetadata.DEFAULT_MAX_ITERATIONS`) |

`invoke` 기본값이 `{user=false, model=true}` 인 것은 의도다 — 통합 이전 스킬의 동작이 그것이었으므로,
블록을 쓰지 않은 기존 스킬은 하나도 새로 슬래시에 노출되지 않는다. 노출은 **명시적으로 켜는 것**이다.

`allowed-tools` 는 **모델 경로와 사용자 경로에서 같은 뜻**이다("이 스킬이 ReAct 동안 호출할 수 있는 도구").
경로별로 키를 쪼개면 같은 본문이 누가 불렀느냐에 따라 다른 권한을 갖게 되는데, 그것은 통합이 없애려던
바로 그 이중성이다. `max-iterations` 는 양수 정수만 받고, 아니면 파싱이 예외로 실패한다.

---

## 4. 어댑터 계층

`SkillBackedCommand` 는 `Skill` 을 `Command` + `LlmExecutable` 로 비추는 불변 어댑터다. 매핑은 세 줄
(`description`, `maxIterations`, `allowedTools`)이며 `allowedTools` 는 이미 파싱된 `AllowedTool` 을
`toString()` 의 정규 스펙 형태로 되돌려 넘긴다 — `CommandMetadata.Builder` 가 원시 스펙 문자열만 받기
때문이고, 재파싱이 아니라 왕복이다.

`SkillBackedCommandRegistry` 는 **캐시하지 않는다.** 조회할 때마다 `SkillRegistry` 에 물으므로 스킬 쪽
reload 가 즉시 반영된다. `reloadCommand` / `reloadAll` 도 `SkillRegistry` 로 그대로 위임한다 — 스킬의
수명은 스킬 레지스트리가 단독으로 책임진다.

`CommandType.CUSTOM` 은 살아남았지만 **뜻이 바뀌었다**: 이제 "`.aimon/commands` 에서 온 것"이 아니라
"바이너리에 내장되지 않은 것", 즉 항상 skill-backed 다. 열거값을 지우지 않은 것은 SYSTEM ↔ CUSTOM 이라는
이분법 자체는 여전히 유효하기 때문이다.

| 기각한 대안 | 이유 |
|-------------|------|
| 어댑터 클래스 없이 합성 단계에서 즉석 변환 | 변환 규칙이 레지스트리 안에 숨어 단위 테스트할 표면이 없어진다. 클래스 하나의 값이 그보다 싸다 |
| `SkillBackedCommandRegistry` 가 `MutableCommandRegistry` 도 구현 | 스킬을 명령 쪽에서 등록·삭제할 수 있게 되면 소유자가 둘이 된다. 통합의 전제가 무너진다 |
| 어댑터가 `invoke.user` 를 스스로 검사 | 게이트가 두 곳이 되면 어긋난다. 필터는 레지스트리에 한 번만 두고, 어댑터는 자기가 받은 스킬을 믿는다 |

---

## 5. 실행 경로

`CompositeCommandExecutor` 가 타입으로 갈래를 정한다 — `SkillBackedCommand` 는
`SkillBackedCommandExecutor` 로, `DirectExecutable` 은 `DirectCommandExecutor` 로. 둘 다 아니면
`IllegalStateException` 이다(배선 버그이지 사용자 입력 오류가 아니다). **LLM 실행 경로는 이제 하나뿐**이며
`LlmSkillExecutor` 가 그것이다.

`SkillBackedCommandExecutor` 가 하는 일은 컨텍스트 변환이고, 그중 한 줄만 설명이 필요하다 —
`ExecutionId.generate("skill:" + name)` 으로 **호출마다 새 id** 를 발급한다. 같은 슬래시 명령을 연달아 두 번
실행할 수 있고 두 실행이 실행별 상태를 공유하면 안 되기 때문이다. 이 실행은 자기 세션이 없다 — 대신 어떤
세션을 대리하는지는 넘겨받은 `ToolContext` 가 이미 들고 있다(용어 규칙은
[`glossary.md` §4](../../overview/glossary.md)).

### 5.1 렌더링은 한 번에

`DefaultSkillContentRenderer` 가 **단일 패스**로 처리한다 — 치환된 텍스트가 다시 해석되지 않는다는 뜻이다.

| 토큰 | 처리 |
|------|------|
| `$ARGUMENTS`, `$0` | 원시 인자 문자열 전체 |
| `$1` … `$N`, `$ARG_COUNT` | 위치 인자 (`ShellArgumentTokenizer` 기준), 없는 위치는 빈 문자열 |
| `$name` | `argument-names` 에 선언된 이름만 치환. 선언 밖의 이름은 그대로 둔다 |
| `${AIMON_*}` | `RenderContext` 의 내장 변수. 없으면 빈 문자열 + WARN. **`System.getenv` 는 읽지 않는다** |
| `` !`cmd` `` | `Bash(cmd)` 로 재작성 — 권한 계층이 검증할 수 있는 형태로 의도를 선언 |
| `@file/path` | `Read(file/path)` 로 재작성 (줄 시작 또는 공백 뒤에서만 — 이메일 주소 오탐 방지) |

`${AIMON_AGENT_RUNTIME_ID}` / `${AIMON_SESSION_ID}` / `${AIMON_EXECUTION_ID}` 는 **서로 폴백하지 않는다.**
셋의 수명이 다르기 때문이며, 뒤의 둘은 배타적 쌍이다(세션 있는 실행이면 앞, 없으면 뒤).

### 5.2 도구 호출은 에이전트와 같은 문을 지난다

통합 직후에도 남아 있던 구멍이 하나 있었다. 슬래시로 부른 스킬의 ReAct 루프가
`ToolExecutionManager` 를 **직접** 쳤다 — 같은 레지스트리이지만 더 짧은 길이어서 `PreTool`/`PostTool` 훅도,
부수효과 승인 게이트도 지나지 않았다. 에이전트라면 물어봤을 도구가 사용자가 슬래시를 친 순간 묻지 않고
실행됐다. 누가 결정한 설계가 아니라 매니저를 직접 부른 것의 부작용이었다.

`SkillToolDispatcher` 가 그 자리를 메운다. 에이전트 실행기가 `SKILL_TOOL_DISPATCHER_KEY` 로 구현체를
`ToolContext` 에 실어 보내고, 스킬 루프는 그것을 통해 **에이전트와 같은 인보커**로 배치를 돌린다.
협력자를 컨텍스트로 태운 것은 `SkillForkExecutor` 와 같은 이유다 — `CommandExecutionManager` 시그니처는
공개 API 인데, 같은 말을 하려면 훅 레지스트리·환경·인터럽트 코디네이터를 전부 파라미터로 끌고 와야 했다.

디스패처가 없으면(에이전트 런타임 없이 `LlmSkillExecutor` 를 직접 구동하는 임베더) 매니저 직행으로
폴백한다. **두 경로의 관측 가능한 차이 하나**: 디스패처 경로에서 권한 위반은 그 도구 하나의 에러 결과로
돌아오고 스킬은 계속 진행하지만, 폴백 경로에서는 `executeAll` 이 던져 스킬 전체가 실패한다. 앞의 것이
에이전트가 늘 갖고 있던 동작이다.

---

## 6. 이름 충돌은 부팅에서 거부한다

`CommandNameConflictDetector` 가 라벨이 붙은 소스들(`"system"`, `"skill"`)을 훑어 같은 이름을 두 소스가
발행하면 `IllegalStateException` 으로 세운다. 메시지는 충돌한 이름과 발행 소스를 이름순으로 나열한다.

우선순위(system > skill)는 조회 순서로 존재하지만 **그것에 의존하는 사용자 경험은 만들지 않는다.**
사용자가 스킬 이름을 `skills` 로 지었을 때 조용히 가려지는 대신 시작이 멈추고, 무엇을 고쳐야 하는지
문장으로 나온다. 검사 시점은 두 곳 — `DefaultCommandRegistry.initialize()`(런타임 생성 시
`OrcaAgentRuntimeFactory` 가 호출)와 `reloadAll()` 이후다. 한 소스 안에서의 중복은 이 검출기의 일이 아니다
(그 소스 자신의 문제이므로 "skill vs skill" 이라는 혼란스러운 메시지를 내지 않도록 소스별로 먼저 dedupe
한다).

---

## 7. 레거시 제거

`CustomCommand` / `CustomCommandRegistry` / Custom 경로의 `LlmCommandExecutor` 는 모두 삭제되었다
(0.0.37 deprecated → 0.1.0 제거). 남은 것은 **거부**뿐이다 — `.aimon/commands/` 에 `*.md` 가 하나라도 있으면
`initialize()` 가 `CommandException` 으로 실패하고, 에러 메시지가 워크스페이스 루트·남은 파일 목록·마이그레이션
가이드·변환 스크립트 경로를 함께 적는다. `reloadAll()` 에서도 같은 검사를 다시 돌린다(마이그레이션 후
실수로 다시 커밋된 파일을 다음 스윕에서 잡기 위해서다).

디렉터리 이름만 적지 않고 **워크스페이스 루트를 함께 적는 것**은 멀티 테넌트 배포 때문이다 —
`.aimon/commands` 는 모든 런타임에서 동일한 상대 경로라 그것만으로는 누구의 워크스페이스를 치워야 하는지
말해 주지 않는다.

---

## 8. 남은 것

| 항목 | 현재 | 비고 |
|------|------|------|
| 스킬별 `model` 오버라이드 | 없음 — `SkillMetadata` 에 `model` 필드가 없다 | 넣는다면 사용자 경로에만 적용되고 `SkillTool` 경로는 기본 모델 그대로다 |
| `reloadCommand(name)` | 명령 쪽에서는 no-op (DEBUG 로그) | 핫 리로드 계약은 `SkillRegistry` 가 단독 소유. 명령 진입점으로 뚫으면 소유자가 둘이 된다 |
| 디스패처 없는 폴백 경로의 권한 위반 처리 | 스킬 전체 실패 | 에이전트 동작(도구 단위 에러)과 다르다. 임베더 경로에만 남은 차이 |

**하지 말 것** — `SkillBackedCommand` 에 스킬에 없는 메타데이터를 추가하지 말 것(어댑터가 소유자가 된다).
`CommandType.CUSTOM` 을 "파일 기반 명령" 으로 읽지 말 것(§4). 충돌 검출을 우선순위 폴백으로 바꾸지 말 것 —
가려진 명령은 사용자가 절대 눈치채지 못한다.

---

## 부록 · 참조 파일 지도

| 파일 | 역할 |
|------|------|
| `command/skill/SkillBackedCommand.java` | Skill → Command 어댑터 |
| `command/skill/SkillBackedCommandRegistry.java` | `invoke.user` 필터 + 무캐시 read-through |
| `command/DefaultCommandRegistry.java` | system + skill-backed 합성, 레거시 거부, 충돌 검사 |
| `command/CommandNameConflictDetector.java` | 라벨 소스 간 이름 충돌 검출 |
| `command/CommandType.java` | SYSTEM / CUSTOM (뜻이 바뀐 자리) |
| `command/execution/CompositeCommandExecutor.java` | 타입별 실행기 라우팅 |
| `command/execution/skill/SkillBackedCommandExecutor.java` | Command 컨텍스트 → Skill 컨텍스트 변환 |
| `command/system/SkillListCommand.java` | `[user, model]` / `[user-only]` / `[model-only]` / `[disabled]` 표시 |
| `skill/execution/SkillExecutor.java` | 사용자 호출 실행 SPI |
| `skill/execution/llm/LlmSkillExecutor.java` | ReAct 루프 · 권한 필터 · 토큰 누적 |
| `skill/execution/llm/SkillSystemPromptBuilder.java` | 도구 제한이 반영된 시스템 프롬프트 |
| `skill/execution/SkillToolDispatcher.java` | 훅·승인 게이트를 지나는 도구 배치 디스패치 |
| `skill/render/DefaultSkillContentRenderer.java` | 단일 패스 렌더 (인자·환경변수·툴 토큰) |
| `skill/render/ShellArgumentTokenizer.java` | 위치 인자 토큰화 |
| `skill/InvokePolicy.java` | `{user=false, model=true}` 기본값 |
| `skill/SkillMetadata.java` | `maxIterations`, `allowedTools`, `invokePolicy` |
| `skill/parser/MarkdownSkillParser.java` | frontmatter 파싱 (`max-iterations` 양수 검증) |
| `skill/repository/PathSkillRepository.java` | 디렉터리 단일 포맷 로더 + 평파일 WARN |

## 관련 문서

- [`approval-scope.md`](approval-scope.md) — 스킬 승인의 도달 범위
- [`../../features/skill/builtin-agent-skill-guide.md`](../../features/skill/builtin-agent-skill-guide.md) — 스킬 작성 가이드
- [`../../migration/custom-command-to-skill.md`](../../migration/custom-command-to-skill.md) — 사용자 마이그레이션 가이드
- [`../subagent/execution.md`](../subagent/execution.md) — 스킬 포크(`execution: fork`)가 타는 실행 경로
- [`../../overview/glossary.md`](../../overview/glossary.md) — turn · iteration · execution
