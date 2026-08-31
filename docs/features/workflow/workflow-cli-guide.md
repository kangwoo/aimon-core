# Workflow CLI 가이드 (aimon-cli 관점)

> aimon-cli에서 워크플로 기능을 켜고 쓰는 방법

이 문서는 **aimon-cli를 쓰는 사용자**를 대상으로 한다.
Java 코드로 워크플로를 직접 조립하는 방법은
[Workflow 사용 가이드(라이브러리 관점)](workflow-usage-guide.md)를 참조한다.

## 목차

1. [무엇이 제공되는가](#무엇이-제공되는가)
2. [기능 켜기](#기능-켜기)
3. `Workflow` [도구 — 미리 정의된 전략](#workflow-도구--미리-정의된-전략)
4. `WorkflowJs` [도구 — JS 스크립트](#workflowjs-도구--js-스크립트)
5. [포그라운드와 백그라운드](#포그라운드와-백그라운드)
6. `/runs` [명령](#runs-명령)
7. [화면에 보이는 것](#화면에-보이는-것)
8. [트러블슈팅](#트러블슈팅)
9. [알아둘 한계](#알아둘-한계)

---

## 무엇이 제공되는가

워크플로는 **여러 서브에이전트를 정해진 구조로 돌려서** 한 문제를 처리하는 기능이다.
일반 대화에서 에이전트가 스스로 판단하며 진행하는 것과 달리, 워크플로는 팬아웃 폭·검증 라운드 수·
종합 단계가 **미리 정해져 있어 재현 가능**하다.

aimon-cli는 두 개의 도구로 이 기능을 노출한다. **둘 다 기본은 꺼져 있다.**

| 도구 | 성격 | 언제 |
|------|------|------|
| `Workflow` | 미리 정의된 전략 3종(다각도 분석 / 판정단 / 반증) | 대부분의 경우. 프롬프트만 주면 된다 |
| `WorkflowJs` | 사용자가 JavaScript로 제어 흐름을 직접 작성 | 반복 대상이 N개거나, 단계가 고유할 때 |

여기에 백그라운드 실행을 조회·중단하는 `/runs` 명령이 붙는다.

---

## 기능 켜기

설정 파일의 `cli:` 블록에서 켠다. 두 플래그는 독립적이다.

```yaml
cli:
  colorOutput: true
  showToolCalls: true
  enableWorkflow: true      # `Workflow` 도구 등록
  enableWorkflowJs: true    # `WorkflowJs` 도구 등록 (GraalJS)
```

설정 파일을 지정해 실행한다:

```bash
./gradlew :aimon-cli:run --args="--config ~/.aimon/aimon.yaml"
```

`--config`를 주지 않으면 CLI에 내장된 `default-config.yaml`이 쓰인다(두 플래그 모두 `false`).

**두 플래그 중 하나라도 켜면** 백그라운드 실행에 필요한 워크플로 러너가 함께 켜지고,
`/runs` 명령이 동작한다. 둘 다 꺼져 있으면 `/runs`는 안내 메시지만 낸다.

`WorkflowJs`에 필요한 GraalJS 엔진은 `enableWorkflowJs: true`일 때만 생성되고, CLI 종료 시 닫힌다.
쓰지 않는다면 켤 이유가 없다.

---

## `Workflow` 도구 — 미리 정의된 전략

프롬프트 하나만 주면 되는 쪽이다. LLM이 필요하다고 판단하면 알아서 호출하며,
사용자가 "여러 관점에서 검토해줘" 같이 요청해서 유도할 수도 있다.

### 파라미터

| 파라미터 | 필수 | 기본 | 설명 |
|---------|:---:|------|------|
| `prompt` | ✅ | — | 다룰 질문/작업/주장 |
| `strategy` | | `perspectives` | `perspectives` \| `judge_panel` \| `adversarial_verify` |
| `perspectives` | | `technical,risk,user_impact` | 쉼표로 구분한 관점 라벨 |
| `synthesize` | | `true` | `perspectives` 전략에서 종합할지 여부 |
| `mode` | | `foreground` | `foreground` \| `background` |

### 전략 3종

**`perspectives` (기본)** — 프롬프트를 여러 관점 서브에이전트에 동시에 던지고, 결과를 하나로 종합한다.

```
관점 3개 병렬 분석 → 종합 에이전트 → 답변 1개
```

`synthesize: false`를 주면 종합하지 않고 관점별 분석을 라벨과 함께 그대로 돌려준다.
관점별 원문을 직접 비교하고 싶을 때 쓴다.

**`judge_panel`** — 후보 답변을 여러 개 만들고, 후보마다 판정자 2명이 점수를 매긴 뒤 최고안을 종합한다.
정답 공간이 넓어 "한 번 쓰고 고치기"보다 "여러 개 만들고 고르기"가 나은 문제에 맞는다.
`perspectives` 값이 후보 생성 각도로 쓰인다.

**`adversarial_verify`** — 프롬프트를 **주장으로 보고** 회의론자 3명이 각자 반증을 시도한다.
2명 이상이 반박하면 기각, 아니면 생존 판정이 나온다. 사실 확인·리스크 검증용이다.

### 예시

```
> 이 아키텍처 변경안을 기술/비용/운영 관점에서 검토해줘

[Subagent] workflow:perspective:technical: 이 아키텍처 변경안을 ...
[Subagent] workflow:perspective:cost: 이 아키텍처 변경안을 ...
[Subagent] workflow:perspective:operations: 이 아키텍처 변경안을 ...
[Subagent] workflow:synthesizer: ...
```

```
> "이 캐시 계층을 제거하면 p99가 개선된다"는 주장을 검증해줘
  → adversarial_verify: 회의론자 3명 중 2명이 반박 → 기각
```

---

## `WorkflowJs` 도구 — JS 스크립트

제어 흐름을 JavaScript로 직접 쓴다. **스크립트가 구조를 쓰고, LLM은 각 서브에이전트 안에서만 돈다.**

### 파라미터

| 파라미터 | 필수 | 기본 | 설명 |
|---------|:---:|------|------|
| `script` | ✅ | — | JavaScript 소스 |
| `args` | | `{}` | 스크립트에 읽기 전용 `args`로 노출되는 입력 객체 |
| `max_agents` | | 없음 | 이 실행의 에이전트 개수 상한 (**포그라운드에서만 적용**) |
| `mode` | | `foreground` | `foreground` \| `background` |

### 스크립트에서 쓸 수 있는 것

전역으로 다음이 주입된다. 이것이 **전부**다 — 파일 접근, 네트워크, Java 리플렉션은 없다.

| 전역 | 시그니처 | 설명 |
|------|---------|------|
| `agent` | `agent(descriptor)` 또는 `agent(prompt, descriptor?)` | 서브에이전트 1스텝(동기) |
| `parallel` | `parallel([descriptor, ...])` | 배리어 팬아웃. 입력 순서로 결과 반환 |
| `pipeline` | `pipeline(items, stage1, stage2, ...)` | 단계별 처리 |
| `phase` | `phase(title)` | 이후 스텝을 이 이름으로 그룹핑 |
| `log` | `log(message)` | 진행 메시지 |
| `args` | (객체) | 읽기 전용 입력. 중첩까지 변경 불가 |
| `console` | `console.log/warn/error/info` | **기본 비활성**. 활성 시 `log`로 연결됨 |

최상위 `return`과 `await`이 합법이다(스크립트가 async 함수로 감싸져 실행된다).

### 에이전트 디스크립터

```js
{
  agentType: "reviewer",       // 논리적 타입 (이름과 기본 시스템 프롬프트의 근거)
  systemPrompt: "...",         // 명시적 시스템 프롬프트 (agentType 중 하나는 있어야 함)
  goal: "...",                 // 또는 prompt: "..."
  schema: { ... },             // JSON Schema — 주면 구조화 객체가 반환된다
  isolation: "worktree",       // 파일을 변조하는 병렬 스텝 격리
  label: "review:foo.java",    // 표시용 라벨
  phase: "Review",             // 이벤트 그룹
  model: "...",                // 모델 오버라이드
  tools: ["Read", "Grep"],     // 도구 허용 목록
  maxIterations: 10
}
```

`agentType` 또는 `systemPrompt` 중 최소 하나는 필요하다.
`schema`를 주면 `agent(...)`가 **구조화된 객체**를 그대로 반환한다. 주지 않으면 결과 뷰를 반환한다:

```js
{ text, structured, isSuccess, isComplete, completionReason, label }
```

실패한 팬아웃 슬롯은 `null`이 된다. **결과를 쓰기 전에 항상 걸러야 한다.**

### 예시 1 — 관점 팬아웃 후 종합

```js
phase('Analyze');
const angles = ['technical', 'risk', 'cost'];
const results = parallel(angles.map(a => ({
  agentType: a,
  systemPrompt: `You analyze strictly from the ${a} angle. Be concise.`,
  goal: args.question
})));

phase('Synthesize');
const combined = results
  .filter(Boolean)
  .map((r, i) => `## ${angles[i]}\n${r.text}`)
  .join('\n\n');

return agent({ agentType: 'synthesizer', goal: combined }).text;
```

`args`는 도구 호출 시 함께 넘긴다: `{"question": "이 변경안의 리스크는?"}`

### 예시 2 — 발견 후 반증 검증 (pipeline)

```js
const files = args.files;

const reviewed = pipeline(files,
  (prev, file) => ({
    agentType: 'reviewer',
    goal: `Find bugs in ${file}`,
    label: `review:${file}`,
    phase: 'Review'
  }),
  (prev, file) => prev && {
    agentType: 'skeptic',
    goal: `Try to refute these findings. Default to refuted=true if uncertain:\n${prev.text}`,
    schema: {
      type: 'object',
      properties: { refuted: { type: 'boolean' }, reason: { type: 'string' } },
      required: ['refuted']
    },
    label: `verify:${file}`,
    phase: 'Verify'
  }
);

log(`${reviewed.filter(Boolean).length}/${files.length} 검증 완료`);
return JSON.stringify(reviewed.filter(Boolean).map(r => r.structured));
```

> **`pipeline`의 동작을 정확히 알아두기:** CLI의 JS `pipeline`은 **스테이지마다 배리어**가 있다.
> 모든 아이템이 1단계를 끝내야 2단계가 시작된다. 스테이지 함수는 `stage(이전결과, 원본아이템, 인덱스)`로
> 호출되고, `null`을 반환하면 그 아이템은 이후 단계에서 제외된다.
> (Java API의 `pipeline`은 배리어 없는 아이템 병렬이라 의미가 다르다.)

### 샌드박스 한계

스크립트는 격리된 GraalJS 컨텍스트에서 돈다. CLI는 기본값을 쓰며 설정 파일로 바꿀 수 없다.

| 항목 | 기본값 |
|------|-------|
| 최대 실행 문(statement) 수 | 10,000,000 |
| 벽시계 타임아웃 | 30분 |
| `console` | 비활성 |
| 호스트 접근 | 없음 (`HostAccess.NONE`) — 파일/네트워크/Java 리플렉션 불가 |
| 결정성 모드 | `NONE` (설정 시 `STRICT`는 `Date`, `Math.random`, `Intl.DateTimeFormat`, `performance.now`를 봉인) |

또한 **진짜 비동기 스크립트는 지원되지 않는다.** 프라미스가 동기적으로 정착(settle)하지 않으면
"workflow promise did not settle synchronously" 오류가 난다. `agent`/`parallel`/`pipeline`은
이미 동기 호출이므로 일반적인 워크플로 작성에는 제약이 되지 않는다.

결과 반환값은 문자열이면 그대로, 객체/배열이면 JSON으로, `null`/`undefined`면 빈 문자열로 변환된다.

---

## 포그라운드와 백그라운드

**포그라운드** (`mode: foreground`, 기본)

- 끝날 때까지 기다렸다가 결과를 반환한다.
- 호출한 턴의 실행 컨텍스트·주체·취소 신호를 상속한다. 즉 **Ctrl+C로 중단할 수 있다**.
- `WorkflowJs`의 `max_agents`는 이 모드에서만 적용된다.

**백그라운드** (`mode: background`)

- 즉시 실행 ID를 돌려주고 대화를 계속할 수 있다.
- 호출 턴의 컨텍스트·주체·취소 신호를 **상속하지 않는다**. Ctrl+C로 멈추지 않는다 →
  `/runs stop <runId>`를 쓴다.
- 실행 ID는 요청 내용에서 결정론적으로 파생된다. **같은 요청이 이미 돌고 있으면 중복 실행하지 않고
  기존 런에 합류한다.**
  - `Workflow`: `run:workflow:<해시>`
  - `WorkflowJs`: `run:graaljs:<해시>`

```
Started background workflow run 'run:workflow:3f2a...' over 3 perspective(s).
Track it with the /runs command (or /runs status run:workflow:3f2a...).
```

백그라운드 런의 **결과 텍스트는 도구 반환값으로 돌아오지 않는다.** `/runs`로 상태를 확인하는 용도다.
결과가 필요하면 포그라운드를 쓴다.

---

## `/runs` 명령

```
/runs                      # = /runs list
/runs list
/runs status <runId>
/runs stop <runId>
```

출력 예:

```
Workflow runs (2):
  run:workflow:3f2a9c...         RUNNING   started 2026-07-27T04:12:33Z
  run:graaljs:81be04...          COMPLETED started 2026-07-27T04:02:11Z  ended 2026-07-27T04:07:48Z
```

상태 값: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `KILLED`.

`stop`은 **협조적 취소**이며 **이 노드에서 돌고 있는 런에만** 적용된다:

```
Requested stop for run 'run:workflow:3f2a9c...'.
No live run 'run:workflow:xxxx' to stop on this node.
```

---

## 화면에 보이는 것

서브에이전트가 뜰 때마다 한 줄씩 표시된다:

```
[Subagent] workflow:perspective:technical: 이 변경안을 기술적으로 검토하라 ...
```

이 줄은 워크플로 전용이 아니라 `Task` 도구와 스킬 포크에도 동일하게 나온다.
`Workflow`의 서브에이전트 이름은 다음 접두사를 쓴다:

| 이름 | 역할 |
|------|------|
| `workflow:perspective:<라벨>` | 관점 분석 |
| `workflow:candidate:<라벨>` | 판정단 후보 생성 |
| `workflow:judge` | 판정 |
| `workflow:skeptic` | 반증 |
| `workflow:synthesizer` | 종합 |

`WorkflowJs`의 서브에이전트는 `graaljs:<agentType>` 형태로 나타난다
(`agentType` 없이 `systemPrompt`만 준 경우 `graaljs:<해시>`).

도구 호출 자체를 보고 싶으면 `cli.showToolCalls: true`를 유지한다.

---

## 트러블슈팅

| 메시지 | 원인 / 해결 |
|--------|-----------|
| `Background workflow runs are disabled. Enable them with 'cli.enableWorkflow: true' in your config.` | `/runs`를 썼는데 두 플래그가 모두 꺼져 있다. 설정에서 켠다 |
| `Background mode is not available: no workflow runner is configured for this agent.` | 같은 원인. `mode: background`가 러너를 못 찾음 |
| `Invalid mode 'xxx'. Use 'foreground' (default) or 'background'.` | `mode` 오타 |
| `Invalid strategy 'xxx'. Use one of [perspectives, judge_panel, adversarial_verify].` | `strategy` 오타 |
| `prompt cannot be blank` / `script cannot be blank` | 필수 입력 누락 |
| `perspectives cannot be empty` | `perspectives`에 빈 문자열/쉼표만 넘김 |
| `JS workflow failed: agent(...) requires a prompt string or a descriptor object` | `agent()`에 인자를 안 줬거나 `null`을 줌 |
| `JS workflow failed: parallel(...) requires an array of descriptor objects` | `parallel`에 배열이 아닌 값을 넘김 |
| `JS workflow failed: pipeline stage N must be a function` | 스테이지 자리에 함수가 아닌 값 |
| `JS workflow failed: GraalJS statement limit exceeded ...` | 스크립트가 무한 루프거나 너무 무겁다. 반복 횟수를 줄인다 |
| `JS workflow failed: workflow promise did not settle synchronously ...` | 진짜 비동기 코드(타이머, 외부 I/O 대기)를 썼다. 지원되지 않는다 |
| `Agent runtime ID not found in tool context` | 에이전트 실행 컨텍스트 밖에서 호출됨 (정상 REPL 사용에서는 발생하지 않음) |
| 실행이 끝났는데 `/runs list`에 안 보임 | 런 목록은 CLI 프로세스 수명 동안만 메모리에 유지된다 |

---

## 알아둘 한계

- **두 도구 모두 실험적 옵트인**이다. 기본 설정에서는 등록되지 않는다.
- 백그라운드 런 목록·상태는 **CLI 프로세스 메모리**에 있다. CLI를 재시작하면 사라진다.
- `/runs stop`은 이 노드에서 살아있는 런에만 통한다.
- `WorkflowJs` 샌드박스 값(문 수 제한, 30분 타임아웃, `console` 비활성)은 설정 파일로 조정할 수 없다.
  조정이 필요하면 라이브러리로 임베딩해 `JsSandboxConfig`를 직접 준다.
- `isolation: 'worktree'` 디스크립터는 워크트리 팩토리가 있어야 동작한다. CLI는 파일 시스템이 있을 때
  이를 주입하며, 없으면 해당 스텝이 실행 실패로 처리된다.
- 워크플로는 **비대화형**이다. 스크립트 중간에 사용자에게 되묻는 것은 불가능하다.

---

## 관련 문서

- [Workflow 사용 가이드(라이브러리 관점)](workflow-usage-guide.md) — Java로 직접 조립·실행
- [빌트인 Agent/Skill 가이드](../skill/builtin-agent-skill-guide.md)
- [서브에이전트 개발 가이드](../subagent/subagent-development-guide.md) — 재사용 가능한 서브에이전트 정의
- [aimon-core 가이드](../../overview/architecture.md) — 핵심 추상화 레퍼런스
