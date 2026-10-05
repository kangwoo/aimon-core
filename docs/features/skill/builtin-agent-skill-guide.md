# Built-in Agent/Skill Guide

## 개요

AIMON 프레임워크는 즉시 사용 가능한 **빌트인(Built-in) Agent와 Skill**을 제공한다. 사용자가 `.aimon/agents/`나 `.aimon/skills/` 디렉터리에 파일을 작성하지 않아도 기본 Agent와 Skill을 바로 사용할 수 있다.

빌트인 Agent와 Skill은 **AgentBundle** 단위로 패키징된다. 각 Agent는 자신만의 번들(subagent, skill)을 가질 수 있으며, 빌트인과 사용자 정의는 **Composite Registry 패턴**으로 합성된다. 동일 이름의 경우 사용자 정의가 빌트인을 override한다.

## 핵심 개념

### AgentBundle

`AgentBundle`은 Agent와 그에 연관된 SubagentRegistry, SkillRegistry를 하나로 묶는 불변 값 객체다.

```java
AgentBundle bundle = AgentBundle.builder()
    .agent(myAgent)
    .subagentRegistry(subagentRegistry)  // optional
    .skillRegistry(skillRegistry)        // optional
    .build();
```

- `agent` (필수): Agent 정의
- `subagentRegistry` (선택): 이 Agent에 번들된 subagent 레지스트리
- `skillRegistry` (선택): 이 Agent에 번들된 skill 레지스트리

### AgentBundleRegistry

`AgentBundleRegistry`는 AgentBundle 인스턴스를 Agent 이름으로 관리하는 중앙 레지스트리다. `DefaultAgentBundleRegistry`는 `ConcurrentHashMap` 기반의 thread-safe 구현체를 제공한다.

```java
AgentBundleRegistry registry = new DefaultAgentBundleRegistry();
registry.register(bundle);

Optional<AgentBundle> found = registry.findByName("default");
List<AgentBundle> all = registry.findAll();
```

### AgentBundleLoader

`AgentBundleLoader`는 다양한 소스에서 AgentBundle을 로드하는 인터페이스다. 세 가지 구현체를 제공한다:

| Loader | 소스 | index 파일 | 부가 파일 지원 |
|--------|------|-----------|--------------|
| `ClasspathAgentBundleLoader` | 클래스패스 (JAR) | 필요 | 미지원 |
| `FileSystemAgentBundleLoader` | 파일시스템 (NIO Path) | 불필요 | 전체 지원 |
| `AdaptiveAgentBundleLoader` | 자동 감지 | 자동 | 자동 |

`AdaptiveAgentBundleLoader`는 리소스 URL의 프로토콜을 감지하여 `file://`이면 `FileSystemAgentBundleLoader`를, 그 외(JAR 등)에는 `ClasspathAgentBundleLoader`를 사용한다.

## 사용 방법

빌트인 Agent와 Skill은 별도 설정 없이 자동으로 로드된다.

**Agent 사용 (TaskTool):**

```
/task explore "프로젝트의 주요 엔트리포인트를 찾아줘"
```

**Skill 사용 (SkillTool):**

```
/skill commit
```

## Override 방법

사용자 정의 Agent/Skill이 빌트인과 **동일한 이름**을 사용하면 자동으로 override된다.

**Agent override 예시:**

`.aimon/agents/explore.md` 파일을 생성하면 빌트인 `explore` 대신 사용자 정의가 사용된다:

```markdown
---
name: explore
description: "커스텀 탐색 에이전트"
allowed-tools: Read, Grep, Glob, Bash
# model 없음: 메인 에이전트의 모델로 돈다. 다른 모델로 돌려야 할 때만 설정된 provider 가 서비스하는 id 를 적는다 — 쓰인 그대로 간다(별칭을 풀지 않는다).
---
당신은 심층적인 코드베이스 분석 에이전트입니다.
...
```

**Skill override 예시:**

`.aimon/skills/commit/SKILL.md` 파일을 생성하면 빌트인 `commit` 대신 사용자 정의가 사용된다:

```markdown
---
name: commit
description: "팀 커밋 컨벤션 가이드"
---
# 팀 커밋 메시지 규칙
...
```

## 빌트인 비활성화

특정 빌트인을 비활성화하려면 동일 이름으로 빈 내용의 파일을 생성한다:

```markdown
---
name: explore
description: "비활성화됨"
---
```

## 커스텀 Agent/Skill 추가

빌트인과 별개로 새로운 Agent/Skill을 추가할 수 있다. 빌트인과 사용자 정의는 합산되어 제공된다.

**커스텀 Agent 추가:**

`.aimon/agents/my-analyzer.md`:

```markdown
---
name: my-analyzer
description: "성능 분석 에이전트"
allowed-tools: Read, Grep, Bash
# model 없음: 메인 에이전트의 모델로 돈다. 다른 모델로 돌려야 할 때만 설정된 provider 가 서비스하는 id 를 적는다 — 쓰인 그대로 간다(별칭을 풀지 않는다).
---
당신은 성능 분석 전문 에이전트입니다.
...
```

**커스텀 Skill 추가:**

`.aimon/skills/review/SKILL.md`:

```markdown
---
name: review
description: "코드 리뷰 가이드"
---
# 코드 리뷰 체크리스트
...
```

## 스킬 Frontmatter 필드

스킬의 `SKILL.md` 상단 YAML frontmatter는 [Agent Skills 표준](../../references/agentskills-specification.md) 필드와 AIMON 확장 필드를 함께 받는다. 자세한 시맨틱은 [AIMON Skill Extensions](../../references/aimon-skill-extensions.md)를 참고한다.

| 필드 | 출처 | 타입 | 필수 | 요약 |
|------|------|------|:---:|------|
| `name` | 표준 | string | ✓ | 스킬 식별자 (소문자/숫자/하이픈, 1–64자) |
| `description` | 표준 | string | ✓ | 무엇을·언제 쓰는지 (1–1024자) |
| `license` | 표준 | string |  | SPDX 라이선스 식별자 |
| `compatibility` | 표준 | string |  | 호환 환경 메모 |
| `metadata` | 표준 | mapping |  | 자유 형식 키-값 |
| `allowed-tools` | 표준 | string |  | 공백 구분 `AllowedTool` 목록 (예: `Read Bash(git:*)`) |
| `arguments` | AIMON | list\<string\> |  | 위치 인자 이름 (`$1..$N`과 매핑) |
| `invoke.user` / `invoke.model` | AIMON | boolean |  | 사용자(`/skill`)·모델 호출 허용 여부 |
| `max-iterations` | AIMON | integer |  | 사용자 호출 시 ReAct 루프 상한 (기본 100) |
| `execution.mode` | AIMON | `inline`·`fork` |  | 부모 에이전트 인라인 실행 vs 서브에이전트 fork (기본 `inline`) |
| `execution.agent` | AIMON | string | (fork시) | `mode: fork`일 때 위임 대상 SubAgent 이름 |
| `hooks` | AIMON | mapping |  | `preTool`/`postTool`/`onStart`/`onStop` 이벤트별 `deny`·`shell` 액션 (fork-mode 한정 발화) |

예시 (fork-mode):

```yaml
---
name: review
description: "Run code review via the code-reviewer subagent."
arguments: [target]
invoke:
  user: true
  model: false
execution:
  mode: fork
  agent: code-reviewer
---
Review the following: $1
```

예시 (hooks — fork-mode 와 함께 사용):

```yaml
---
name: review
description: "Run code review via the code-reviewer subagent."
execution:
  mode: fork
  agent: code-reviewer
hooks:
  preTool:
    - matcher: "Bash"
      action: { type: deny, reason: "Bash is not allowed inside review skill" }
  onStop:
    - action: { type: shell, command: "echo review done success=$AIMON_SUCCESS" }
---
Review the following: $1
```

> `shell` 액션은 호스트가 `DefaultShellActionExecutor` 로 와이어된 환경(예: aimon-cli)에서만 동작한다. 명령은 호스트가 아니라 **훅이 발화한 실행의 실행 환경 셸**에서 돈다 — 같은 스킬의 `Bash` 호출이 도는 곳이고, 작업 디렉터리는 워크스페이스다. 훅은 **이 스킬이 fork 한 에이전트(와 그 에이전트가 띄운 fork)에서만** 발화한다 — 같은 에이전트의 다른 세션이나 스킬을 호출한 쪽에는 발화하지 않는다. `preTool` 같은 가드 이벤트의 `shell` 훅은 명령을 **돌리지 못하면**(실행 환경 없음 · timeout · 셸 실패, 또는 exit 126 · 127 — 훅이 도는 곳에 스크립트가 없거나 실행 권한이 없다) 막는다. 관찰용이면 항목에 `failOpen: true` 를 둔다. 무엇이 막고 `failOpen` 이 무엇을 바꾸는지는 표 하나에 있다: [hook 설정 가이드 › 가드가 막는 경우](../hook/hook-config-guide.md#가드가-막는-경우). 가드 훅(`onStart` · `preTool` · `preCompact` · `permissionRequest`)이 있는 스킬의 fork 안에서는 스킬보다 오래 살거나 스킬 밖에서 도는 일을 시작할 수 없다 — `Task` 의 `run_in_background`, background 워크플로, `ScheduleTask` 는 도구 오류로 거절된다(가드가 따라가지 못한다). 훅 명령은 자기 스킬 디렉터리의 스크립트를 환경 변수 `$AIMON_SKILL_DIR` 로 부른다(`bash "$AIMON_SKILL_DIR/scripts/guard.sh"`) — 훅이 도는 환경에 스테이징한 경로이고, 스테이징하지 못하면 명령은 돌지 않는다. 사용 가능한 환경 변수와 액션 시맨틱은 [AIMON Skill Extensions / hooks](../../references/aimon-skill-extensions.md#hooks--스킬-단위-hook-스코프) 를 참고한다.

## Fork-mode 스킬 호출하기

위의 `review` 예시(`execution.mode: fork`, `agent: code-reviewer`)를 두 경로로 호출했을 때 무슨 일이 일어나는지.

### 사전 조건

1. `code-reviewer` 라는 SubAgent가 등록돼 있어야 한다 (`.aimon/agents/code-reviewer.md` 또는 빌트인 번들). 미등록이면 fork는 LLM/SubAgent 호출 없이 즉시 실패한다.
2. 호스트가 SubAgent 인프라(6요소: `Agent`, `SubagentRegistry`, `ToolRegistry`, `HookRegistry`, `UserLocale`, `SubagentExecutionManager`)를 모두 와이어링해야 한다. `aimon-cli`는 기본으로 만족한다. 하나라도 빠지면 fork-mode 스킬 호출은 `fork execution is not configured` 로 실패한다 — 인라인 전용 배포를 가능하게 하려는 의도된 동작이다.

### fork 는 두 허용목록을 함께 적용한다

스킬의 `allowed-tools` 는 fork 경계에서 멈추지 않는다 — 대상 SubAgent 의 목록과 **함께** 걸린다. 어느 한쪽이
거부하는 호출은 허용되지 않는다.

| 스킬 | SubAgent | fork 가 받는 것 |
|------|----------|-----------------|
| (없음) | `Read, Grep` | `Read, Grep` — 스킬이 제한하지 않으므로 SubAgent 의 목록이 그대로 |
| `Read` | (없음) | `Read` — SubAgent 가 제한하지 않으므로 스킬의 목록이 그대로 |
| `Read, Write` | `Read, Grep` | `Read` — 양쪽에 있는 것만 |
| `Bash` | `Bash(git:*)` | `Bash(git:*)` — 스킬이 인자를 제한하지 않으므로 SubAgent 의 패턴이 이긴다 |
| `Read` | `Write` | **fork 실패** — 겹치는 것이 없다 |

마지막 줄이 실패인 이유는 안전 때문이다. 빈 허용목록은 이 코드베이스에서 **"제한 없음"** 을 뜻하므로, 겹치지
않는 두 목록의 교집합을 빈 목록으로 넘기면 **가장 엄격한 설정이 가장 느슨한 설정으로 뒤집힌다.** 그래서 넘기지
않고 거부하며, 오류 메시지가 양쪽 목록을 함께 보여 준다.

서로 다른 패턴 둘(`Bash(git:*)` 와 `Bash(npm:*)`)은 **버린다.** 두 glob 의 교집합은 일반적으로 계산할 수 없고,
넓게 어림잡으면 한쪽이 거부한 것을 허용하게 된다. 같은 패턴이면 그대로 살아남는다.

### 슬래시로 호출 (`invoke.user: true` 일 때)

REPL에서:

```
> /review src/main/java/Foo.java
```

흐름:

1. `OrcaAgentExecutor` 가 `/`로 시작하는 입력을 명령으로 인식한다.
2. `SkillBackedCommandRegistry` 가 `review` 스킬을 찾아 `SkillBackedCommandExecutor` → `LlmSkillExecutor` 로 라우팅한다.
3. 본문이 렌더링된다 — `$1` 위치에 `src/main/java/Foo.java` 가 치환되어 `Review the following: src/main/java/Foo.java` 가 된다.
4. `OrcaAgentExecutor` 가 매 슬래시 호출마다 fork executor 를 resolve해서 `ToolContext` 로 전달하므로, `LlmSkillExecutor` 가 그 executor 로 fork 를 위임한다.
5. `code-reviewer` SubAgent 가 새 컨텍스트에서 spawn 되고, 렌더된 본문이 그 SubAgent 의 goal(첫 user 메시지)이 된다.
6. SubAgent 의 최종 답변이 그대로 `/review` 의 응답으로 화면에 출력된다 (`=== Skill Forked ===` 래핑 없음).

### LLM 호출 (`invoke.model: true` 일 때)

LLM이 자체 판단으로 `Skill` 도구를 통해 호출한다 — 사용자가 직접 `/review` 를 입력하지 않아도 된다:

```
LLM: 코드 리뷰가 필요해 보입니다 — Skill(skill="review", args="src/main/java/Foo.java") 를 호출하겠습니다.
```

흐름은 슬래시 경로와 동일하지만, 결과가 LLM 에게 돌아갈 때 `SkillTool` 이 다음 형식으로 감싼다:

```
=== Skill Forked ===
Skill: review
Agent: code-reviewer

Final Answer:
<SubAgent 의 최종 답변>
```

LLM 은 이 블록을 보고 fork 가 끝났음을 인식한 뒤 사용자에게 정리된 답변을 다시 만들어 준다.

### 자주 만나는 실패 메시지

| 메시지 | 원인 |
|--------|------|
| `Skill 'review' references unknown subagent 'code-reviewer'` | `execution.agent` 가 SubagentRegistry 에 없음. SubAgent 파일 누락 또는 이름 오타. |
| `Skill 'review' declares execution.mode=fork but fork execution is not configured` | 호스트가 SubAgent 인프라를 와이어링하지 않은 환경(NoOp 폴백). |
| `Skill 'review' is declared as fork mode but has no execution.agent set` | YAML 에 `execution.mode: fork` 만 있고 `agent` 가 빠짐. |
| `Cannot fork skill 'review': agent runtime ID not available in tool context` | `OrcaAgentExecutor` 외부에서 `LlmSkillExecutor` 를 직접 부르는 비표준 경로. 정상 호출에서는 발생하지 않음. |

내부 와이어링 디테일(어떤 컴포넌트가 어디서 resolve 되는지)은 [AIMON Skill Extensions / Fork executor 와이어링](../../references/aimon-skill-extensions.md#fork-executor-와이어링) 참고.

## 아키텍처

### AgentBundle 로딩

```
AdaptiveAgentBundleLoader
├── file:// protocol → FileSystemAgentBundleLoader
│   ├── Agent ← {basePath}/{name}/agent.md
│   ├── SubagentRegistry ← PathSubagentRepository ({basePath}/{name}/agents/)
│   └── SkillRegistry ← PathSkillRepository ({basePath}/{name}/skills/)
│       └── 부가 파일 지원 (scripts/, references/, assets/)
│
└── JAR protocol → ClasspathAgentBundleLoader
    ├── Agent ← {basePath}/{name}/agent.md
    ├── SubagentRegistry ← ClasspathSubagentRepository (index 파일 필요)
    └── SkillRegistry ← ClasspathSkillRepository (index 파일 필요)
```

> 부트스트랩은 번들 스킬 트리를 제어 저장소로 한 번 복사한 뒤 그 위에서 VFS 기반 레지스트리를 다시 빌드한다.
> 로더가 만든 번들 레지스트리(`ClasspathSkillRepository` / `PathSkillRepository`)는 복사가 건너뛴 스킬의 폴백으로
> 남으며, 어느 쪽이든 부가 파일은 `${AIMON_SKILL_DIR}` 스테이징으로 모델에게 닿는다 — 아래
> [번들 스킬 리소스 materialization](#번들-스킬-리소스-materialization) 참고.

### AgentBundle 레지스트리

```
AgentBundleRegistry (중앙 레지스트리)
└── DefaultAgentBundleRegistry (ConcurrentHashMap 기반)
    ├── register(AgentBundle)
    ├── findByName(agentName) → Optional<AgentBundle>
    ├── findAll() → List<AgentBundle>
    └── unregister(agentName)
```

### Composite Registry (빌트인 + 사용자 정의 합성)

```
CompositeSubagentRegistry / CompositeSkillRegistry
├── DefaultSubagentRegistry (builtin)     ← AgentBundle의 번들된 레지스트리
│   └── ClasspathSubagentRepository / PathSubagentRepository
└── DefaultSubagentRegistry (user)        ← .aimon/agents/
    └── VfsSubagentRepository

조회 우선순위: user > builtin (리스트 뒤쪽이 높은 우선순위)
목록: 양쪽 합산 (user가 동일 이름 override)
```

## 번들 디렉터리 구조

각 Agent 번들은 다음 디렉터리 구조를 따른다:

```
{basePath}/{agent-name}/
├── agent.md              ← Agent 정의 파일 (필수)
├── agents/               ← 번들된 subagent 정의 (선택)
│   ├── index             ← subagent 목록 (클래스패스 로딩 시 필요)
│   └── explore.md
└── skills/               ← 번들된 skill 정의 (선택)
    ├── index             ← skill 목록 (클래스패스 로딩 시 필요)
    └── commit/
        ├── SKILL.md
        ├── scripts/      ← 부가 스크립트
        ├── references/   ← 부가 참조 파일
        ├── assets/       ← 부가 자산 파일
        └── templates/    ← 임의 디렉터리도 그대로 보존됨
```

부가 파일(`scripts/`, `references/`, `assets/`, 그리고 `templates/` 같은 임의 디렉터리)은 부트스트랩 시
제어 저장소(`.aimon/bundled-skills/<name>/`)로 **materialize(복사)** 되고, 스킬을 쓸 때 작업 공간의
`.aimon-staged/` 로 스테이징되므로, FileSystem/JAR 로더 어느 쪽으로 번들을 로드하든 Agent 의 `Read`/`Bash`
도구로 접근할 수 있다. 자세한 동작은 아래
[번들 스킬 리소스 materialization](#번들-스킬-리소스-materialization) 참고.

### 클래스패스 리소스 위치

빌트인 번들은 `aimon-core` 모듈의 클래스패스 리소스에 포함된다:

```
modules/aimon-core/src/main/resources/
└── agents/
    └── {agent-name}/
        ├── agent.md
        ├── agents/
        │   ├── index
        │   └── *.md
        └── skills/
            ├── index
            └── {skill-name}/
                └── SKILL.md
```

빌트인 Agent/Skill을 추가하려면 위 구조에 맞게 리소스 파일을 추가하고, 클래스패스 로딩 시 `index` 파일을 업데이트해야 한다.

## 번들 스킬 리소스 materialization

번들 스킬의 부가 파일(`scripts/`, `references/`, `assets/`, `templates/` 등 임의 디렉터리)은 클래스패스
(JAR 내부 또는 `build/resources` 트리)에 존재한다. 이 위치는 Agent 의 `Read`/`Bash` 도구가 보는 workspace
`VirtualFileSystem` 으로는 접근할 수 없다 — JAR 엔트리는 파일이 아니고, 펼쳐진 리소스는 워크스페이스 샌드박스
밖의 OS 절대경로로 resolve 되기 때문이다.

부트스트랩은 `BundledSkillMaterializer` 로 번들 스킬 트리를 **제어 저장소**의 `bundled-skills/<skill-name>/`
(로컬에서는 `{workspace}/.aimon/bundled-skills/`)로 복사한다. 제어 저장소는 모델의 파일 도구에게 보이지 않으므로,
이 사본은 에이전트가 직접 읽는 파일이 아니라 다음 절의 스테이징이 읽는 원본이다.

- **로딩 무관**: `ClasspathResourceTreeWalker` 가 `file:`/`jar:` URL 을 모두 처리하므로 IDE/`gradle run`/패키징된
  JAR 어디서 실행하든 동일하게 동작한다.
- **부팅 시 덮어쓰기**: 매 부팅마다 대상 디렉터리를 비우고 다시 복사하므로 제어 저장소 사본은 항상 배포된
  클래스패스 내용과 일치한다.
- **레지스트리 우선순위**: 최종 `CompositeSkillRegistry` 는 `[클래스패스 번들(폴백) < materialize 된 VFS 번들 <
  사용자 .aimon/skills]` 순으로 합성된다. materialize 된 VFS 레이어가 같은 이름의 클래스패스 레이어를 가리고,
  사용자 스킬이 그 위를 가린다. materialize 가 실패한 스킬은 클래스패스 폴백이 본문만이라도 계속 제공한다.

### `${AIMON_SKILL_DIR}` 로 자기 파일 참조

스킬 본문에서 자기 디렉터리 기준 파일을 참조할 때는 `${AIMON_SKILL_DIR}` 변수를 쓰는 것을 권장한다:

```markdown
이 스킬의 템플릿을 로드한다: @${AIMON_SKILL_DIR}/templates/report.md
헬퍼 실행: !`python ${AIMON_SKILL_DIR}/scripts/run.py`
```

`${AIMON_SKILL_DIR}` 는 언제나 **실행 환경이 스킬을 스테이징하고 돌려준 경로**다(`ExecutionEnvironment.stage`,
설계 [`design/tool/execution-environment.md`](../../design/tool/execution-environment.md) §4.4). 스킬을 처음
쓸 때 스킬 디렉터리가 작업 공간의 `{workspace}/.aimon-staged/<skill-name>/<contentKey>/` 로 복사되고, 렌더러
(`DefaultSkillContentRenderer`)가 그 경로로 치환한다. 스킬이 어느 저장소에서 왔든 — 사용자 스킬(VFS), 호스트 경로
(`PathSkillRepository`, 읽기 전용 로컬 VFS 로 읽는다), 클래스패스 — 같다. 그래서 모델의 셸과 파일 도구가 같은
경로를 본다. 스킬을 활성화하면 ToolResult 의 `Available Files` 섹션에 부가 파일이 `이름 → 스테이징 경로` 형태로
함께 제공되므로(임의 디렉터리는 `Other Files` 로 노출), 모델이 그 경로로 직접 `Read` 할 수도 있다.

- **읽기 전용 사본이다.** 파일 도구는 `.aimon-staged/` 에 쓸 수 없다. 사본에는 실행 비트가 없으므로 스크립트는
  `bash x.sh` · `python3 x.py` 처럼 인터프리터로 실행한다(`./x.sh` 는 안 된다).
- **내용 주소다.** `contentKey` 는 스킬 디렉터리 전체의 해시라, 내용이 같으면 경로가 같고 바뀌면 새 경로가 된다.
  해시는 레지스트리가 스킬을 읽을 때 한 번 계산한다 — 그래서 시작 시 모든 스킬 파일을 한 번씩 읽는다.
- **디스크에서 스킬을 고친 뒤**에도 스킬은 계속 쓸 수 있다. 다만 무엇이 복사되는지는 고친 시점에 달렸다. 그 스킬을
  이번 프로세스에서 **아직 쓰지 않았고** 적재된 판의 사본도 없으면, 지금 디스크에 있는 파일이 그 내용의 `contentKey`
  경로로 복사되고 경고 로그가 한 번 남는다. **이미 쓴 뒤**에 고쳤으면 적재된 판의 사본이 그대로 쓰인다. 어느 쪽이든
  본문 · 도구 제한 · 훅처럼 `SKILL.md` 에서 읽은 것은 레지스트리를 다시 읽거나(reload) 앱을 재시작해야 바뀐다.
- **`.stageignore`** (gitignore 문법의 부분집합: glob, `dir/`, `!`, `#`)를 스킬 디렉터리에 두면 큰 에셋을 복사
  대상에서 뺄 수 있다. 스킬 디렉터리 하나의 스테이징 총량은 기본 50 MB 로 제한된다(스타터 속성
  `aimon.environment.staging.max-bytes`).
- **링크로 설치한 스킬.** 디스크에서 읽는 번들의 `skills/` 에서는 스킬 디렉터리나 그 안의 파일·디렉터리가 심볼릭
  링크여도 된다. 링크는 그 실제 경로가 `skills/` 안이거나 **허용 루트** 안일 때만 따라가고, 그 밖을 가리키는 링크가 있는
  스킬은 적재되지 않는다(그 스킬만 빠지고 경고가 링크를 이름으로 든다). 허용 루트는 기본이 비어 있고, 공유 헬퍼를
  `skills/foo -> /opt/shared-skills/foo` 로 링크했다면 그 디렉터리를 적는다 — 스타터 `aimon.skill.allowed-link-roots`,
  CLI `agent.allowedSkillLinkRoots`, 직접 조립할 때는 `AimonStackSpec.builder().allowedSkillLinkRoots(...)`. 절대 경로만
  받는다. 상대 경로, 빈 항목, `/` 는 기동이 실패하고, 없는 디렉터리는 받아들이되 아무것도 허용하지 않는다. jar 안의
  번들에는 링크가 없으므로 이 설정이 닿지 않고, `.aimon/skills` 의 사용자 스킬은 워크스페이스 파일 시스템으로 읽으므로
  링크를 아예 따라가지 않는다.
- `.aimon-staged/` 는 사본일 뿐이다. 로컬 제공자가 첫 복사 때 `.aimon-staged/.gitignore`(`*`)를 써 두므로
  프로젝트의 `.gitignore` 에 따로 넣지 않아도 된다(이미 있는 파일은 건드리지 않는다).

### 스킬 본문 렌더 변수 (`${AIMON_*}`)

`DefaultSkillContentRenderer` 가 스킬 본문에서 치환하는 내장 변수는 아래 5개가 전부다. 이들은 **본문 텍스트
치환용**이며, 선언적 hook 에 주입되는 셸 프로세스 환경변수(`SkillHookEnv` 의 `AIMON_*`)와는 완전히 별개의
채널이다 — 렌더러는 `System.getenv` 를 읽지 않는다. 이름이 양쪽에 다 있는 것은 `AIMON_SKILL_DIR` 하나다: 본문에서는
`${AIMON_SKILL_DIR}` 가 스킬을 호출한 실행의 환경에 스테이징한 경로로 치환되고, 훅 명령에서는 환경 변수
`$AIMON_SKILL_DIR` 가 **훅이 발화한 실행**(스킬의 fork)의 환경에 스테이징한 경로다. fork 가 다른 환경에 놓이면 두 값은
다르다.

| 변수 | 값 | 범위 |
|------|----|------|
| `${AIMON_SKILL_DIR}` | 스킬 디렉터리를 이 실행의 환경에 스테이징한 경로(`ExecutionEnvironment.stage`) | 스킬 단위 |
| `${AIMON_AGENT_RUNTIME_ID}` | `AgentRuntimeId` 값 — `agent:<name>` 또는 `agent:<name>:<discriminator>` | **에이전트 단위** |
| `${AIMON_SESSION_ID}` | `SessionId` 값. 렌더하는 실행이 **세션의 턴일 때만** 채워진다 | **세션 단위** |
| `${AIMON_EXECUTION_ID}` | `ExecutionId` 값 — 자기 세션이 없는 실행(서브에이전트 포크, 스킬 포크, 스케줄 루틴)의 신원. 노드 로컬이고, **어떤 영속 저장소도 이 id 로 키잉되지 않는다** — 포크의 transcript 라벨로 적혀 재시작을 넘어가긴 하지만 그 스냅샷은 task id 로 찾으므로, 남는 것은 키가 아니라 이름이다. 실행이 세션의 턴이면 **비어 있다** | **실행 단위** |
| `${AIMON_USER}` | 호출자 `Principal#getDisplayName()` | 호출자 단위 |

값을 찾을 수 없으면 빈 문자열로 치환되고 WARN 이 남는다. 위 목록에 없는 `${VAR}` 는
`RenderContext#getAdditionalVariables()` 에서 찾고, 거기에도 없으면 본문에 그대로 남는다.

IMPORTANT: 세 id 변수는 **수명이 다르므로 서로 대체되지 않는다.** `${AIMON_AGENT_RUNTIME_ID}` 는 **에이전트
단위**로 결정론적이다 — 같은 에이전트의 모든 세션과 모든 cron 재발화가 동일한 문자열을 보므로, 실행마다 유일해야
하는 용도(예: `/tmp/work/${AIMON_AGENT_RUNTIME_ID}` 같은 작업 디렉터리)로 쓰면 동시에 도는 세션들이 같은 경로를
공유한다.

`${AIMON_SESSION_ID}` 와 `${AIMON_EXECUTION_ID}` 는 **배타적인 한 쌍**이다 — 렌더러는 두 리터럴을 항상 치환하지만
값이 들어가는 것은 그중 정확히 하나다. 렌더하는 실행이 세션의 턴이면 session id 쪽이 채워지고 execution id 쪽이
`""` 가 되며, 자기 세션이 없는 실행(서브에이전트 포크, 스킬 포크, 스케줄 루틴)이면 그 반대다. 빈 쪽에 남는 WARN 은
**반대쪽 변수 이름을 함께 알려준다**(`resolveSessionId` / `resolveExecutionId`). 한쪽이 없을 때 다른 쪽으로
폴백하지 않는 것은 의도적이다: 예전에는 포크가 새로 발급한 session id 를 이 자리에 렌더했고, 본문은 그 값을
사용자 세션의 id 와 구별할 수 없었다. 지금은 session id 를 물으면 세션 id 를 받거나 아무것도 받지 않는다.

따라서 **실행마다 유일한 작업 디렉터리**에는 `${AIMON_AGENT_RUNTIME_ID}` 가 아니라 이 쌍을 쓴다. 세션 턴에서만
활성화되는 스킬이면 `${AIMON_SESSION_ID}` 하나로 충분하지만, 포크나 루틴에서도 활성화될 수 있는 스킬은 두 변수를
**모두** 적는다 — 어느 상황에서도 정확히 하나만 확장되므로 경로는 여전히 유일하다:

```markdown
작업 디렉터리: /tmp/work/${AIMON_SESSION_ID}${AIMON_EXECUTION_ID}
```

> `${AIMON_SESSION_ID}` 는 한 릴리스 동안 `${AIMON_AGENT_RUNTIME_ID}` 의 deprecated 별칭이었다. 세션 우선 개편이
> 그 deprecation 을 **완료**했다 — 별칭 분기는 삭제되었고 리터럴은 이름이 처음부터 약속한 세션 id 에 묶였다.
> WARN 을 무시하고 별칭을 계속 쓴 본문은 이제 *다른 값*을 받는다.

NOTE: `RenderContext` 는 스킬을 호출한 실행의 툴 컨텍스트에서 채워진다(`SkillRenderContextAccess`). 모델이
도구로 부르는 `SkillTool` 과 `/skill-name` 슬래시 호출(`SkillBackedCommandExecutor`)이 같은 헬퍼를 거치므로 두
경로의 치환 결과가 같다. 슬래시 호출에는 세션 id 가 실리고, `${AIMON_USER}` 는 명령을 친 호출자의 `Principal` 이다.
루틴 스텝에서 `Skill` 도구를 부르면 스텝의 툴 컨텍스트(`AGENT_RUNTIME_ID` · `PRINCIPAL` · `EXECUTION_ID`)로 렌더된다.
