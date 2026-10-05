# 설계 — EE-49 · EE-51 · EE-58: 샌드박스 전 격리 경계 묶음

> Status: **IMPLEMENTED** (2026-10-04). 백로그 항목 EE-49 · EE-51 · EE-58 의 설계이고, 설계 리뷰에서 승인된 그대로다(두 차례 —
> 첫 리뷰의 차단 지적 하나를 개정 2 가 반영했고, 둘째 리뷰는 차단 지적 없이 통과, 비차단 지적 일곱 건). 아래 본문(§1~§9)은
> 승인본에서 한 글자도 바꾸지 않았다 — 소스 인용(`1d60d30` 기준)과 열린 질문 번호까지 그대로다. 그래서 본문에는 **구현 뒤에
> 사실이 아니게 된 서술**이 남아 있다. 고친 것은 본문이 아니라 §10 에 적는다.
>
> 더한 것은 §10 하나다 — 구현이 이 설계에서 벗어난 곳과 그 이유, 반영한 리뷰 지적, 열린 질문이 어떻게 되었는지. 특히
> §10.1(`hooks.json` 의 잘못된 `failOpen` 은 그 핸들러만 빼지도, 파일 전체를 거절하지도 않고 **`false` 로 읽어 핸들러를
> 남긴다** — 본문 §4.2 · F15 · §7 과 다르고, 첫 구현과도 다르다)과 §10.4(사람이 뒤집을 수 있는 결정 셋), §10.7(PR #207
> 리뷰가 바꾼 것)을 먼저 볼 것. §9 의 열린 질문과 §8 의 새 항목 가운데 이 변경 밖으로 결과가 번지는 것은
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) 로
> 옮겼다(EE-63 ← Q8 · §8, EE-64 ← Q4 · F5, EE-65 ← §8, EE-66 ← Q3, EE-67 ← Q5, EE-68 ← §8, EE-69 ← 설계 리뷰 2,
> EE-70 · EE-71 ← PR #207 리뷰 — 둘은 닫혔고 그 설계는
> [`execution-environment-ee70-ee71-fail-closed.md`](execution-environment-ee70-ee71-fail-closed.md) 다). 열림/닫힘의 정본은 그 문서다. 이 설계가 확장하는 명세는 [`execution-environment.md`](execution-environment.md) §5.3 · §10 · §13 · §15 다.

- 대상 브랜치: `herdr/ee49-ee51-ee58-isolation-boundary` (BASE `main`, `1d60d30`)
- 이 문서는 설계만 담는다. 코드 조각은 모양을 보이기 위한 것이고 구현이 아니다.

---

기준 커밋 `1d60d30` (`main`, 2026-10-04). 경로는 따로 적지 않으면 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.
**개정 2 (리뷰 1 반영).** 바뀐 곳: 백그라운드 워크플로와 스킬 가드(§2 P16, §3.1, §4.1, F13 · F14, Q8), 스폰 지점을 넷으로
(GraalJS 포함), `HOOK_REGISTRY` 를 write-once 로, `failOpen` 의 엄격한 불리언 검증과 모델용 사유에서 힌트 제거, 셸 미지원
실행기의 `hooks.json` 적용, `Unrun` 대응표, 소유자 팩터리가 던지지 않음.

"확인함" 은 이 워크트리의 소스를 읽어 확인했다는 뜻이고, "추론" 은 소스로 확인하지 못한 것이다.

## 1. 문제

세션마다 실행 환경이 다른 제공자(샌드박스)를 붙이면, 지금 코어에 있는 세 군데의 "에이전트 런타임 단위" 경계가 격리
경계를 넘는다. (EE-49) 스킬 훅은 agent-scoped `HookRegistry` 에 등록되어, 세션 A 가 활성화한 스킬의 훅이 같은 에이전트의
세션 B 이벤트에 발화하고 **B 의 환경**에서 명령을 돌린다. (EE-51) 스킬의 가드 셸 훅은 명령을 돌리지 못하면
`ShellHookOutcome.notObserved()` 를 내고, 읽는 쪽은 그것을 통과로 읽는다 — 환경 제공자가 실패하면 그 실행의 모든 셸 가드가
조용히 꺼지는데 환경을 쓰지 않는 도구는 그대로 실행된다. (EE-58) 백그라운드 `Bash` 작업의 소유자는 `AGENT_RUNTIME_ID` 뿐이라
같은 런타임의 다른 세션이 task id 만 알면 출력을 읽고 `KillShell` 로 멈춘다. 셋 다 공개 SPI 를 건드리므로, 외부 저장소
(aimon-sandbox · aimon-browser)가 한 번만 따라오도록 샌드박스 연동 전에 한 묶음으로 끝낸다. EE-51 의 방향(fail-closed)은
메인테이너가 이미 정했다.

## 2. 착수 전에 소스로 확인한 전제

| # | 사실 | 근거 |
|---|------|------|
| P1 | 훅 디스패치는 `context.getHookRegistry()` 가 돌려준 레지스트리에서 훅을 읽는다 — 전역 조회가 아니다 | `hook/DefaultHookExecutionManager.java` `dispatch` |
| P2 | 포크는 `SubagentExecutionEnvironment.hookRegistry` 로 받은 레지스트리를 **자기 모든 훅 컨텍스트**(tool · compaction · subagentStart/Stop)에 싣는다 | `subagent/execution/DefaultSubagentExecutor.java` 의 `lc.hookRegistry()` 사용처 |
| P3 | 스폰 지점 넷(`TaskTool` · `WorkflowTool` 전경 경로 · `SubagentBackedSkillForkExecutor` · `aimon-workflow-graaljs` 의 `GraalJsWorkflowTool` 전경 경로)은 **생성자에서 받은** 런타임 레지스트리를 포크에 넘긴다 — 호출 컨텍스트에서 받지 않는다 | 각 파일의 `.hookRegistry(hookRegistry)` (`TaskTool.java:554`, `WorkflowTool.java:511`, `GraalJsWorkflowTool.java:251-252`) |
| P4 | `SkillHookActivator.activate` 호출처는 `SkillTool.execute` 하나다. 범위는 렌더링 + (FORK 모드면) 포크의 수명. INLINE 모드에서는 "사실상 발화하지 않는다" 가 문서화된 동작이다 | `tools/skill/SkillTool.java:350`, `skill/hook/SkillHookActivator.java` javadoc |
| P5 | 스킬 frontmatter 훅은 `asyncRewake` 를 갖지 않는다(`DeclarativeHookOptions.ofDiscriminator` 만) — rewake 리플레이가 스킬 훅을 id 로 찾을 일이 없다 | `skill/parser/SkillHookSetParser.java:215` |
| P6 | `HookContext` 에는 실행 정체성(`SessionId`/`ExecutionId`)이 공통으로 없다. 일부 컨텍스트만 따로 갖는다 | `hook/execution/HookContext.java` |
| P7 | 거부 채널이 있는 선언 셸 훅은 넷이다 — `preTool`(block), `onStart`(block), `preCompact`(block), `permissionRequest`(deny). 나머지는 `vetoResult` 가 비어 있어 exit 2 도 무시한다 | `DeclarativePreToolHook`, `AbstractDeclarativeShellHook.vetoResult` 와 세 오버라이드 |
| P8 | `notObserved()` 를 내는 곳은 여섯이다 — 환경 없음, `shell()` 의 unavailable, `shell()` 의 기타 예외(`DefaultShellActionExecutor`), timeout · unavailable · 실행 실패 · 기타 예외(`ShellActionRunner`, 하나의 꼬리 return), `NoOpShellActionExecutor.run` | 해당 파일 |
| P9 | `hooks.json` 과 스킬 frontmatter 는 **같은 훅 클래스**와 `DeclarativeHookOptions` 를 쓴다. 다른 것은 실행기뿐이다(`HostShellActionExecutor` vs `DefaultShellActionExecutor`) | `config/hook/HookRegistryApplier.java`, `DeclarativeShellHookBinding` |
| P10 | 훅 실행기 수준의 timeout 은 따로 있고(`HookExecutionPolicy.timeoutFor` = 선언 예산 + grace) `preTool` 기본 정책은 `TimeoutBehavior.FAIL_OPEN` 이다 | `hook/execution/HookExecutionPolicy.java`, `DefaultHookExecutionManager.Builder` |
| P11 | 세션 범위 인가의 선례: "자기 세션, 없으면 호출한 세션" — `SideEffectApprovalGate.scopeKeyOf` | `toolinvocation/approval/SideEffectApprovalGate.java:240` |
| P12 | 포크는 `EXECUTION_ID`(전달 안 됨) + `INVOKING_SESSION_ID`(있을 때만, 그대로 전달)를 싣고 `SESSION_ID` 는 싣지 않는다. 루틴은 `EXECUTION_ID` 만 싣는다 | `DefaultSubagentExecutor.createToolContext`, `scheduling/RoutineExecutor.buildToolContext` |
| P13 | `ScopedSubagentTaskController` 의 범위 키는 세션이 아니라 `AgentRuntimeId` 다. 따를 선례는 키가 아니라 **형태**다 — 범위 밖은 "없음" 과 같은 답, 동작 전에 인가 | `subagent/ScopedSubagentTaskController.java` |
| P14 | `BackgroundBashStore` 구현은 코어의 `InMemoryBackgroundBashStore` 하나이고, bootstrap(`ToolSpec.backgroundBashStore`)과 starter(`ObjectProvider<BackgroundBashStore>`)가 외부 구현을 받는다 | 해당 파일 |
| P15 | rewake 리플레이는 훅의 `execute` 만 부르고 도구를 실행하지 않는다 | `hook/rewake/impl/DefaultRewakeFireListener.java:237` (`hook.execute(rebuiltContext)` 뿐, 도구 호출 경로 없음 — 리뷰에서도 확인) |
| P16 | **백그라운드 워크플로는 호출 컨텍스트에서 아무 것도 물려받지 않는다.** `WorkflowTool` · `GraalJsWorkflowTool` 의 background 모드는 agent-scoped 러너의 `runInBackground(script, runId)` 를 부르고, 그 러너의 기반 환경은 런타임을 조립할 때 **런타임 레지스트리로 한 번** 만들어진다. `RunId` 는 요청 내용에서 나오므로 같은 요청이 진행 중이면 **다른 호출자의 실행에 합류**한다 | `tools/workflow/WorkflowTool.java:286-300`, `agent/impl/orca/OrcaAgentRuntimeFactory.java:1162-1168`, `GraalJsWorkflowTool.java:180-195` |
| P17 | `ToolContext.Builder` 는 write-once 키의 두 번째 쓰기를 거부한다 | `agent/tool/ToolContext.java` `Builder.put` |
| P18 | `HookRegistryApplier` 는 `isShellSupported()` 를 보지 않는다(스킬 파서만 본다). `HookHandlerSpec` 은 `ignoreUnknown` 만 설정하고 스칼라 강제 변환을 끄지 않는다 | `config/hook/HookRegistryApplier.java`, `config/hook/HookHandlerSpec.java:53`, `skill/parser/SkillHookSetParser.java:292` |

## 3. 접근

### 3.1 EE-49 — 스킬 훅을 공유 레지스트리에 넣지 않고, 포크가 들고 다니는 레지스트리에 얹는다

**선택.** 스킬 훅을 런타임의 `HookRegistry` 에 등록/해제하는 대신, **런타임 레지스트리 위에 스킬 훅 층을 얹은 읽기 뷰**
(`SkillScopedHookRegistry`, 가칭)를 만들어 그 스킬의 포크에만 넘긴다. P1 · P2 덕에 포크 안의 모든 이벤트는 그 뷰를 통해
디스패치되고, 다른 실행은 그 객체에 닿을 길이 없다. 범위는 **id 로 거르는 것이 아니라 구조로 정해진다.**

- **범위의 키.** 없다. `ExecutionId` 도 세션 id 도 비교하지 않는다. 뷰 객체를 가진 실행만 스킬 훅을 본다.
- **포크 계보.** 스킬의 포크와 **그 후손 포크**까지 발화한다(지금도 후손은 런타임 레지스트리로 그 훅을 본다 — 동작 유지).
  P3 때문에 그대로 두면 후손은 런타임 레지스트리로 되돌아가므로, 실행기가 "이 실행이 이벤트를 디스패치하는 레지스트리" 를
  `ToolContextKeys.HOOK_REGISTRY`(신규)로 싣고, 스폰 지점은 그 값을 먼저 쓰고 없을 때만 생성자 값을 쓴다
  (`EXECUTION_ENVIRONMENT_PROVIDER` · `CALLER_ALLOWED_TOOLS` 와 같은 "컨텍스트로 물려주기" 패턴). 스폰 지점은 **넷**이다
  (P3): `TaskTool`(전경 · `run_in_background` 모두 호출 컨텍스트로 환경을 만든다), `WorkflowTool` 전경,
  `GraalJsWorkflowTool` 전경, `SubagentBackedSkillForkExecutor`.
- **백그라운드 워크플로는 뷰를 물려받을 수 없다 — 그래서 가드가 켜져 있으면 거절한다.** P16 대로 background 모드는 호출
  컨텍스트와 끊긴 agent-scoped 러너에서 돈다. 지금은 스킬 훅이 런타임 레지스트리에 있어서 그 워크플로의 서브에이전트도
  (스킬이 활성인 동안만, 우연히) 가드를 맞았다. 이 설계에서 그대로 두면 **스킬이 아직 활성인데 그 하위 트리에서만 가드가
  소리 없이 꺼진다** — 이 묶음이 막으려는 바로 그 실패다. 뷰를 실어 보내는 쪽은 성립하지 않는다: (1) 러너 SPI 에 호출별
  환경을 받는 자리가 없고, (2) `RunId` 합류 때문에 한 실행이 여러 호출자에게 공유되어 "누구의 스킬 훅" 인지 정할 수 없고,
  (3) 실행이 스킬보다 오래 살아 `close()` 뒤에는 어차피 층이 꺼진다. 그래서 EE-51 과 같은 원칙을 쓴다 — **판단을 보장할 수
  없으면 막는다**: 호출 컨텍스트의 레지스트리 사슬에 **거부 채널이 있는 이벤트의 활성 스킬 훅**(P7 의 넷)이 하나라도 있으면
  `Workflow` · `WorkflowJs` 의 background 모드는 실행하지 않고 원인을 담은 도구 오류로 답한다("전경 모드로 실행하라").
  활성 스킬 훅이 관찰 전용뿐이면 실행하고, 그 하위 트리에서 스킬 훅이 발화하지 않는다는 WARN 을 남긴다(관찰의 손실은
  문서화된 한계로 받아들인다 — F13). 스킬 훅이 없는 컨텍스트(대부분의 호출)는 아무 영향이 없다. → Q8
- **활성화한 실행 자신.** 발화하지 **않는다.** 지금은 FORK 스킬이 도는 동안 부모의 병렬 형제 도구 이벤트에 우연히 발화했다.
  문서화된 계약은 "포크한 에이전트의 도구 호출을 관찰한다"(P4)이고, 부모 쪽 발화는 그 계약에 없는 부산물이다. INLINE 모드는
  얹을 포크가 없으므로 아무 것도 활성화하지 않는다 — "사실상 발화하지 않는다" 가 "발화하지 않는다" 로 바뀐다. → Q1
- **활성의 수명.** 지금과 같다: `SkillTool` 의 try-with-resources 범위(= 포크가 답을 돌려줄 때까지). `close()` 는 뷰의 스킬
  층을 끈다(원자적 플래그). 포크가 띄운 백그라운드 후손이 뷰를 계속 쥐고 있어도 닫힌 뒤에는 런타임 훅만 보인다 — "활성 해제
  후 발화하지 않는다" 를 지킨다.
- **세션 없는 실행.** 루틴 · 포크가 스킬을 부르면 그 호출의 `ToolContext` 에서 기반 레지스트리를 읽어 얹는다. 세션 유무와
  무관하게 같은 규칙이다.
- **중첩 스킬.** 포크 F1(스킬 X 층) 안에서 스킬 Y 를 포크하면 Y 의 뷰는 **F1 의 뷰 위에** 얹힌다(컨텍스트의
  `HOOK_REGISTRY` 가 기반). X 와 Y 의 훅이 모두 보인다.
- **핫리로드.** 뷰는 기반 레지스트리를 매번 읽으므로 `hooks.json` 리로드가 그대로 비친다. 쓰기(`register`/`unregister`/
  `clearAll`)는 기반으로 위임한다 — 포크 안에서 동적으로 등록하던 코드의 의미("런타임 전체")가 바뀌지 않는다.
- **멀티 인스턴스.** 활성 상태는 저장소가 아니라 **한 실행이 쥔 객체**다. 실행은 띄운 프로세스 안에서만 돌므로(스킬 포크는
  `executeInline`, 스냅샷 재개 경로가 없다 — 확인함) 다른 노드가 이 상태를 알아야 할 순간이 없다. `LiveSession` 의 메시지
  큐와 같은 부류이고, 저장소 인터페이스로 뽑을 대상이 아니다. 오히려 지금의 공유 레지스트리 등록이 "노드 로컬 상태를
  에이전트 전체에 흘리는" 쪽이었다.

**기각한 대안.**

| 대안 | 기각 이유 |
|------|-----------|
| A. 공유 레지스트리에 그대로 등록하되 훅을 "활성화한 실행 id 와 같을 때만 실행" 하는 필터로 감싼다 | `HookContext` 에 공통 정체성이 없다(P6). 열세 개 컨텍스트 타입과 외부 `HookContext` 구현에 접근자를 더해야 하고, `EXECUTION_ID` 는 전달되지 않으므로(P12) 후손을 알아보려면 계보 추적까지 새로 만들어야 한다. 훅은 여전히 공유 목록에 있어 다른 실행의 디스패치 비용 · dedup · `stopOnBlocked` 순서에 끼어든다 |
| B. 범위를 세션으로(같은 세션의 모든 실행에 발화) | 세션 없는 실행이 빠지고, 한 세션의 형제 포크가 서로의 스킬 훅을 맞는다. 스킬 호출 하나의 수명을 세션 수명으로 넓힐 근거가 없다 |
| C. 활성 상태를 저장소 인터페이스로 뽑아 노드 간 공유 | 공유할 소비자가 없다(위 "멀티 인스턴스"). 훅 인스턴스는 직렬화 대상도 아니다 |
| E. 백그라운드 워크플로에도 뷰를 실어 보낸다(`runInBackground` 에 환경 인자 추가) | P16 — `RunId` 합류로 실행이 호출자 사이에 공유되고, 실행이 스킬 수명을 넘는다. 러너 SPI 를 깨고도 "스킬이 끝난 뒤의 실행" 과 "합류한 다른 호출자" 에서 답이 없다 |
| F. 백그라운드 워크플로의 가드 손실을 문서화만 하고 받아들인다 | 가드가 신호 없이 꺼진다. 같은 PR 의 EE-51 결정("판단할 수 없으면 막는다")과 정면으로 어긋난다 |
| D. `SkillForkExecutor.fork` 에 레지스트리 인자를 더한다 | 공개 SPI 시그니처가 바뀌고, 중첩 `Task`/`Workflow` 를 위해 컨텍스트 키는 어차피 필요하다. 키 하나로 둘 다 풀린다 |

### 3.2 EE-51 — "판단하지 못한 가드는 막는다", 훅별 `failOpen` 으로만 푼다

**규칙.** 거부 채널이 있는 네 이벤트(P7)의 셸 훅이 **종료 코드를 얻지 못하면** 그 이벤트의 거부 결과를 낸다. 원인은 가리지
않는다 — 환경 없음, 환경 사용 불가, 셸 미지원, **timeout, 실행 실패, 예기치 않은 예외** 모두 같다. 결정의 취지가 "가드는
판단할 수 없을 때 막아야 가드" 이고, 가드 입장에서 timeout 과 환경 장애는 구별되지 않는 같은 사건("답을 못 들었다")이다.
timeout 만 통과시키면 느리게 만드는 것으로 가드를 끌 수 있다.

**옵션을 둔다: 훅별 `failOpen: true`, 기본 `false`.** 근거: 같은 `preTool` 이벤트가 가드와 감사(관찰) 두 용도로 쓰이고
선언만으로는 구별되지 않는다(백로그 EE-51 도 같은 관찰). 옵션이 없으면 감사용 `preTool` 훅 하나가 환경 장애 때 MCP · 웹 같은
환경 무관 도구까지 전부 막는다. 옵션은 **훅 단위**다 — 스킬 전체나 전역 스위치로 두면 가드 하나를 살리려는 사람이 다른
가드까지 끈다.

**종료 코드를 낸 명령은 바뀌지 않는다.** exit 0 = 허용, exit 2 = 거부, 그 밖 = WARN 후 허용(Claude Code 호환 계약). 이
변경은 "돌리지 못했다" 만 다룬다. → Q3 (exit 126/127 은 사실상 "돌리지 못함" 인데 이 규칙 밖에 남는다)

**관찰 전용 이벤트는 영향이 없다.** `postTool` · `onStop` · `subagentStart/Stop` · `permissionDenied` · `postCompact` ·
세션/설정 이벤트는 거부 채널이 없어(`vetoResult` 가 빈 값, `postTool` 은 별도 클래스) 지금처럼 WARN 후 성공이다. 구조로
보장된다: 변환은 `vetoResult` 가 값을 낼 때만 일어난다.

**`hooks.json` 에도 같은 규칙을 적용한다.** 근거: (1) 두 프런트엔드는 같은 훅 클래스를 쓴다(P9) — 규칙을 가르려면 "누가
만들었나" 를 클래스에 새로 실어야 하고, 같은 이벤트 · 같은 exit 계약이 출처에 따라 반대로 읽힌다. (2) 운영자가 쓴 가드는
스킬 가드보다 가드일 가능성이 높다. (3) 호스트 실행기는 환경이 필요 없으므로 새로 막히는 경우는 timeout 과 실행 실패뿐이라
범위가 좁다. 비용은 운영자의 기존 훅이 timeout 때 막기 시작한다는 것이고, 핸들러의 `failOpen: true` 와 CHANGELOG 로 받는다.
→ Q2 (범위를 스킬로만 줄이고 싶다면 뒤집을 수 있는 결정)

**거부 사유.** 모델과 사용자가 원인을 알 수 있게 싣는다. 형식(영문, 도구 결과에 그대로 들어감):

```
Blocked: guard hook '<skill>' (<event>) could not run its command — <cause>: <detail>.
A guard that cannot decide blocks (fail-closed).
```

`failOpen` 으로 푸는 방법은 **모델에게 보이는 사유에 싣지 않는다.** 그 글을 읽는 것은 가드를 받는 쪽(포크한 에이전트)이고,
`Edit`/`Write` 와 스테이징된 스킬 사본을 쥐고 있을 수 있다. 푸는 방법은 WARN 로그와 문서에만 적는다.

`<cause>` 는 `no execution environment` / `execution environment unavailable` / `timed out after 30s` / `shell execution failed`
/ `shell actions not supported`(§4.2 의 `Unrun` 과 1:1). `<detail>` 은 예외 메시지이고 기존 `MAX_DENY_REASON_LENGTH` 상한을 따른다. 명령 문자열은
싣지 않는다(비밀이 들어갈 수 있다 — 로그에는 지금처럼 남는다).

**기각한 대안.**

| 대안 | 기각 이유 |
|------|-----------|
| 실행기(`ShellActionExecutor`)가 거부를 낸다 | 실행기는 이벤트에 거부 채널이 있는지 모른다. 관찰 이벤트에서도 "거부" 를 만들어 읽는 쪽이 다시 걸러야 한다 |
| timeout 은 fail-open 유지 | 위 — 가드를 느리게 만들어 끌 수 있고, 결정의 취지와 어긋난다 |
| 옵션 없음(무조건 fail-closed) | 감사용 `preTool` 훅이 환경 장애를 전면 장애로 키운다 |
| 스킬 단위 · 전역 옵션 | 가드 하나를 위해 다른 가드까지 연다 |
| `HookExecutionPolicy` 의 `TimeoutBehavior` 를 `FAIL_CLOSED` 로 바꾼다 | 프로그램으로 등록한 모든 훅의 동작이 바뀐다. 이 묶음의 범위 밖이고, 남는 틈은 §6 F5 와 Q4 에 적는다 |

### 3.3 EE-58 — 소유자를 (런타임, 세션 또는 실행) 으로 넓히고 전부 같아야 찾는다

**소유자 키.** 작업을 띄운 호출의 `ToolContext` 에서 다음 순서로 정한다(P11 의 선례 그대로):

1. `SESSION_ID` 가 있으면 → 그 세션.
2. 없고 `INVOKING_SESSION_ID` 가 있으면 → 호출한 세션.
3. 둘 다 없고 `EXECUTION_ID` 가 있으면 → 그 실행.
4. 아무 것도 없으면 → 범위 없음(런타임만; Orca 밖 임베딩과 단위 테스트의 지금 동작).

조회 · 종료는 **호출자의 소유자를 같은 방법으로 계산해 레코드의 소유자와 전부 같을 때만** 찾는다. 다르면 `NOT_FOUND` —
없는 id 와 같은 응답(`BashOutputTool.notFound`)이다.

- **포크 ↔ 부모.** 세션 S 의 턴이 띄운 포크는 2번으로 S 가 소유자다. 부모(1번, S)와 같으므로 부모가 포크의 작업을 읽고
  멈추고, 포크도 부모의 작업을 읽고 멈춘다. 같은 세션의 형제 포크끼리도 보인다 — 모두 같은 사용자 세션을 대신해 도는
  실행이므로 인가 경계 안이다. `INVOKING_SESSION_ID` 는 "상태 분할 키로 쓰지 말라" 고 문서화되어 있지만 그 이유가 "포크의
  상태가 부모와 합쳐진다" 이고, 여기서는 그 합쳐짐이 요구 사항이다. 이 키의 본래 용도("이 실행은 어느 세션을 대신하는가" 라는
  인가 질문)에 해당한다.
- **루틴.** 3번으로 그 발화의 `ExecutionId`(`routine:<taskId>:…`)가 소유자다. 발화가 끝나면 아무도 그 작업에 닿지 못하고,
  작업은 환경이 정한 상한(`backgroundCommandTimeout`)과 `TeardownPhase.BACKGROUND_COMMANDS` 로 끝난다. 다음 발화가 이전
  발화의 작업을 멈출 수 있게 하지는 않는다 — 발화 사이에 공유되는 정체성은 런타임뿐이고, 그것이 바로 닫으려는 범위다.
- **호출자 없는 포크**(루틴의 포크, 턴보다 오래 사는 백그라운드 워크플로의 포크). 3번으로 **자기** `ExecutionId` 가 소유자다
  (`EXECUTION_ID` 는 전달되지 않는다 — P12). 그래서 루틴은 자기 포크가 띄운 작업을 읽지 못한다. 받아들인다 → Q5.
- **rewake 리플레이.** 도구를 실행하지 않으므로(P15) 작업을 띄우지 않는다. 규칙은 필요 없고, 띄우게 되는 날에는 3번이
  적용된다.
- **저장소와 다른 노드.** 레코드에 소유 세션 · 소유 실행을 싣는다(§4.3). `find` 는 지금도 저장소 레코드의 소유자를
  `ELSEWHERE` 판정 **전에** 비교하므로, 같은 비교를 넓히면 다른 노드에서도 답이 같다: 같은 세션이 다른 노드에서 다시 열리면
  `describeElsewhere` 를 듣고, 다른 세션은 어느 노드에서든 "없음" 을 듣는다. 세션 id 는 영속 정체성이므로 노드를 넘어
  성립하고, 실행 id 소유 레코드는 그 실행이 사는 노드에서만 물어볼 수 있으므로 다른 노드에서는 언제나 "없음" 이다(맞는 답).
- **옛 레코드.** 범위 필드가 없는 레코드(이 변경 전에 쓴 것, 범위를 저장하지 않는 외부 저장소)는 "범위 없음" 으로 읽히고
  세션을 가진 호출자와 일치하지 않는다 → "없음". 레코드 수명은 보존 기간(기본 24시간) 안이라 이관 절차를 두지 않는다.
  CHANGELOG 에 적는다.

**기각한 대안.**

| 대안 | 기각 이유 |
|------|-----------|
| 소유자를 `Principal` 로 | 같은 사용자의 두 세션이 서로를 멈춘다(의도일 수도 있지만 EE-58 의 "세션으로 좁힌다" 와 다르다). 루틴의 `PRINCIPAL` 은 작업 소유자라 루틴끼리 합쳐진다. 인증 없는 배포는 principal 이 없다 |
| 포크 작업을 포크의 `ExecutionId` 로만 소유 | 부모 세션이 포크의 작업을 읽지도 멈추지도 못한다 — 백로그가 짚은 바로 그 문제 |
| `ScopedSubagentTaskController` 처럼 매니저를 감싸는 데코레이터 | 비교할 값이 레코드에 없으면 감쌀 수 없다. 스키마가 먼저다. 매니저는 이미 소유자 인자를 받으므로 그 인자의 타입을 넓히는 쪽이 한 군데에서 끝난다 |
| "루트 실행 id" 를 새로 만들어 호출자 없는 계보를 잇는다 | id 가족에 네 번째를 더한다. 사용자가 없는 실행의 가장자리 하나(Q5)를 위해서는 과하다 |
| 소유자를 불투명 문자열 하나(`"session:<id>"`)로 | 저장소 구현이 타입 없는 값을 받는다. `SessionId`/`ExecutionId` 구분("실행 id 는 아무도 가리키지 않는다")이 문자열 접두사 규약으로 내려간다 |

## 4. 바뀌는 인터페이스와 데이터

### 4.1 EE-49 (`skill.hook`, `tools`, `skill.fork`)

```java
// skill/hook/SkillHookActivator.java — 시그니처 변경 (깨짐)
SkillHookScope activate(Skill skill, ToolContext context);

// skill/hook/SkillHookScope.java — 추가
/** 이 스킬의 포크가 써야 할 레지스트리. 얹은 것이 없으면 empty (호출자의 레지스트리를 그대로 쓴다). */
default Optional<HookRegistry> hookRegistry() { return Optional.empty(); }

// tools/ToolContextKeys.java — 추가
ToolContextKey<HookRegistry> HOOK_REGISTRY = ToolContextKey.writeOnce("hookRegistry", HookRegistry.class);

// tools/HookRegistryAccess.java — 신규 (InvokingSessionAccess · ExecutionEnvironmentAccess 와 같은 꼴)
static Optional<HookRegistry> of(ToolContext context);
/** 컨텍스트의 레지스트리 사슬에 거부 채널 이벤트의 활성 스킬 훅이 있는가 — 백그라운드 워크플로의 거절 판정. */
static boolean hasActiveSkillGuards(ToolContext context);
/** 포크에 넘길 사본: 다른 키는 그대로, HOOK_REGISTRY 만 주어진 값으로. 유일한 "교체" 지점. */
static ToolContext withHookRegistry(ToolContext context, HookRegistry registry);
```

- **`HOOK_REGISTRY` 는 write-once 다**(`EXECUTION_ENVIRONMENT` 와 같은 이유). 실행기가 enricher 보다 먼저 싣고, enricher 가
  바꾸려 하면 `IllegalStateException` 이다 — 포크의 스폰 도구가 쓰는 레지스트리를 enricher 가 바꿔치기해 가드를 벗길 수
  없다. `SkillTool` 이 포크용 컨텍스트를 만들 때는 **같은 컨텍스트에 덮어쓰는 것이 아니라** `withHookRegistry` 로 새
  컨텍스트를 조립한다(그 키를 걸러 내고 나머지를 복사, P17 과 충돌하지 않는다).

- `RegistryBackedSkillHookActivator` → **`ScopedSkillHookActivator`** 로 바꾼다(가칭; 더는 레지스트리에 등록하지 않으므로 옛
  이름이 거짓이 된다). 생성자의 `HookRegistry` 는 "컨텍스트에 `HOOK_REGISTRY` 가 없을 때의 기반" 으로 남는다.
- `SkillScopedHookRegistry`(신규, `skill.hook`, `HookRegistry` 구현): `getHooks(type)` = 기반의 목록 + (활성일 때) 스킬 층.
  순서는 지금과 같다(런타임 훅 먼저, 스킬 훅 뒤 — 지금도 등록 순서상 뒤에 붙는다). 쓰기는 기반으로 위임. `isEmpty()` 는 둘 다
  비었을 때. 불변 스킬 층 + `AtomicBoolean active`. `hasActiveGuards()` — 이 층이 활성이고 P7 의 네 이벤트 중 하나에 훅이
  있거나, 기반이 같은 타입이고 그쪽이 참이면 참(중첩 스킬).
- `SkillTool`: `activate(skill, context)` 를 부르고, 범위가 레지스트리를 주면 `HOOK_REGISTRY` 를 그 값으로 바꾼 `ToolContext`
  사본을 `forkExecutor.fork(...)` 에 넘긴다. `SkillForkExecutor` 의 시그니처는 그대로다.
- 스폰 지점 넷 — `SubagentBackedSkillForkExecutor` · `TaskTool` · `WorkflowTool.buildEnvironment` ·
  `GraalJsWorkflowTool` 의 환경 조립(`aimon-workflow-graaljs`): `HookRegistryAccess.of(context).orElse(this.hookRegistry)`.
- 백그라운드 워크플로 둘 — `WorkflowTool.runInBackground` · `GraalJsWorkflowTool` 의 background 분기: 러너를 부르기 전에
  `HookRegistryAccess.hasActiveSkillGuards(context)` 면 거절(도구 오류, 예외를 던지지 않는 쪽 규칙은 각 도구의 기존 방식
  그대로 — `WorkflowTool` 은 `ToolExecutionException` → 기반 클래스가 오류 결과로, `GraalJsWorkflowTool` 은
  `ToolResult.error`). 관찰 전용 스킬 훅만 활성이면 WARN 후 진행. 오류 문구:
  `Background mode is not available here: skill '<name>' has guard hooks active and a background workflow run would not be covered by them. Run the workflow in foreground mode.`
- `DefaultSubagentExecutor.createToolContext` · `OrcaAgentExecutor.createToolContext`: `HOOK_REGISTRY` 를 싣는다.
  `RoutineExecutor` 는 싣지 않는다(스폰 도구가 생성자 값으로 떨어진다 — 지금 동작).

### 4.2 EE-51 (`skill.hook.declarative`, `skill.parser`, `config.hook`)

```java
// ShellHookOutcome — notObserved() 를 없애고 원인을 강제한다 (깨짐)
public enum Unrun { NO_ENVIRONMENT, ENVIRONMENT_UNAVAILABLE, SHELL_UNSUPPORTED, TIMEOUT, EXECUTION_FAILED }
public static ShellHookOutcome notRun(Unrun cause, String detail);
public Optional<Unrun> getUnrunCause();      // observed 이면 empty
public String unrunReason();                  // 사람이 읽는 "<cause>: <detail>", 상한 적용

// DeclarativeHookOptions — 필드 추가 (기본 false)
public boolean isFailOpen();
Builder failOpen(boolean failOpen);
```

- P8 의 여섯 곳과 원인의 대응(테스트의 기대값):

  | 분기 | `Unrun` |
  |------|---------|
  | `DefaultShellActionExecutor` — 컨텍스트에 환경 없음 | `NO_ENVIRONMENT` |
  | `DefaultShellActionExecutor` — `shell()` 이 `ExecutionEnvironmentUnavailableException` | `ENVIRONMENT_UNAVAILABLE` |
  | `DefaultShellActionExecutor` — `shell()` 이 그 밖의 `RuntimeException` ("환경이 셸을 주지 않았다") | `ENVIRONMENT_UNAVAILABLE` (환경이 쓸 수 없다는 같은 사실; detail 에 예외 메시지) |
  | `ShellActionRunner` — `ShellTimeoutException` | `TIMEOUT` |
  | `ShellActionRunner` — `execute` 중 `ExecutionEnvironmentUnavailableException` | `ENVIRONMENT_UNAVAILABLE` |
  | `ShellActionRunner` — `ShellExecutionException` · 그 밖의 `RuntimeException` | `EXECUTION_FAILED` |
  | `NoOpShellActionExecutor.run` | `SHELL_UNSUPPORTED` |

- 인자 없는 `notObserved()` 를 **남기지 않는다.** 남기면 새 "못 돌림" 분기가 원인 없이 통과 쪽으로 다시 생길 수 있다.
  원인을 인자로 강제하면 컴파일러가 P8 의 여섯 곳을 모두 짚는다. 외부 `ShellActionExecutor` 구현은 고쳐야 한다(CHANGELOG).
- 판정은 한 곳에: `ShellHookVerdicts.guard(outcome, options, skillName, eventName)`(가칭, 패키지 전용) → 거부 사유 문자열
  또는 empty. `AbstractDeclarativeShellHook.interpret` 는 `vetoResult(reason)` 로, `DeclarativePreToolHook` 은
  `HookResult.block(reason)` 으로 감싼다. 두 클래스가 문구와 규칙을 따로 갖지 않게 한다.
- `ShellActionExecutor.run` 의 javadoc: "`notObserved` 는 어떤 호출자도 거부로 읽지 않는다" → "종료 코드를 얻지 못하면
  `notRun(cause, …)` 을 낸다. 거부 채널이 있는 이벤트는 `failOpen` 이 아니면 그것을 거부로 읽는다". "절대 던지지 않는다" 는
  그대로다.
- 선언 형식:

```yaml
# SKILL.md frontmatter — 항목 수준 키 (matcher · action 과 나란히)
hooks:
  preTool:
    - matcher: { tool: Bash }
      action: { type: shell, command: "audit.sh", timeoutMs: 5000 }
      failOpen: true
```

```json
// hooks.json — 핸들러 수준 키 (timeoutMs · asyncRewake 와 나란히)
{ "type": "command", "command": "audit.sh", "timeoutMs": 5000, "failOpen": true }
```

  **`failOpen` 은 JSON/YAML 불리언 `true`/`false` 만 받는다.** 가드를 푸는 키이므로 느슨한 쪽으로 읽히면 안 된다. 스킬
  파서는 `Boolean` 이 아니면 파싱 오류(기존 `requireString` 류와 같은 경로). `hooks.json` 은 Jackson 기본값이 `"true"` · `1`
  을 `true` 로 강제 변환하므로(P18) **타입 있는 필드로 바로 바인딩하지 않는다**: `fromJson` 이 `JsonNode`(또는 `Object`)로
  받아 `isBoolean()` 이 아니면 `IllegalArgumentException` → 그 핸들러는 기존 "invalid handler" 경로로 WARN 후 **등록되지
  않는다**(잘못 쓴 옵션이 가드를 열지도, 조용히 무시되지도 않는다). 테스트가 `"true"` · `1` · `null` 을 고정한다.
- **`HookRegistryApplier` 가 셸 미지원 실행기를 미리 거른다.** 지금은 `isShellSupported()` 가 false 인 실행기를 받아도
  `command` 핸들러를 등록하고, 발화 때 `notObserved` 로 아무 일도 없었다(P18). 그대로 두면 이 변경 뒤에는 "조용히 아무 것도
  안 함" 이 "걸리는 모든 `preTool`/`onStart` 를 막음" 으로 바뀐다. 적용 시점에 `!shellExecutor.isShellSupported()` 면
  `command` 핸들러를 WARN 과 함께 건너뛴다(기존 `canRunOn` 검사 옆). 스킬 파서는 이미 파싱 오류로 막는다.
  셸 액션이 아닌 훅(`deny` · `http` · `mcp`)에 쓰면 WARN 후 무시한다 — 이 묶음은 셸 액션의 "못 돌림" 만 다룬다(§8 새 항목).
  거부 채널이 없는 이벤트에 쓰면 조용히 무해하다(읽히지 않는다); 스킬 파서는 WARN 한 줄을 남긴다.

### 4.3 EE-58 (`tools.bash`)

```java
// tools/bash/BackgroundBashOwner.java — 신규, 불변 class + 정적 팩터리
public final class BackgroundBashOwner {
    public static BackgroundBashOwner of(ToolContext context);          // §3.3 의 1~4 규칙. 유일한 계산 지점
    public static BackgroundBashOwner of(AgentRuntimeId runtimeId, SessionId sessionId, ExecutionId executionId);
    public Optional<AgentRuntimeId> getRuntimeId();
    public Optional<SessionId> getSessionId();       // 자기 세션이든 호출한 세션이든 — "누구를 대신하는가"
    public Optional<ExecutionId> getExecutionId();   // 세션이 없을 때만. 둘 다 주어지면 세션이 이기고 실행 id 는 버린다(던지지 않는다)
    // equals/hashCode: 세 필드 전부
}

// BackgroundBashManager — AgentRuntimeId 인자를 BackgroundBashOwner 로 (깨짐, 옛 오버로드를 남기지 않는다)
BackgroundBashTask start(BackgroundBashOwner owner, String command, VirtualShell shell, ExecutionOptions options);
BackgroundBashLookup find(BackgroundBashOwner owner, String taskId);
BackgroundBashKill kill(BackgroundBashOwner owner, String taskId);

// BackgroundBashRecord — 필드 둘 추가 (선택값)
Optional<SessionId> getOwnerSessionId();
Optional<ExecutionId> getOwnerExecutionId();
BackgroundBashOwner owner();                         // 세 필드로 조립. find 가 비교하는 값

// BackgroundBashTask — ownerRuntimeId 대신 owner
BackgroundBashOwner getOwner();
```

- 팩터리 둘 다 **던지지 않는다.** 세션과 실행 id 가 함께 오면(잘못 조립된 컨텍스트, 둘 다 채운 외부 저장소의 레코드) F10
  과 같은 규칙으로 정규화한다 — 세션이 이긴다. `BackgroundBashRecord.owner()` 가 `find` 안에서 던지는 접근자가 되지 않게
  하기 위해서다.
- 런타임만 받는 옛 오버로드를 남기지 않는 이유: 그것이 곧 닫으려는 구멍이다. 남기면 호출처 하나가 조용히 넓은 범위로 남는다.
- `BackgroundBashStore` 인터페이스의 메서드는 바뀌지 않는다. 바뀌는 것은 **레코드의 필드**와 계약 문장 하나 — "저장소는
  레코드의 소유 필드 셋을 모두 그대로 돌려줘야 한다. 하나라도 잃으면 그 작업은 누구에게도 보이지 않는다". 직렬화하는 외부
  구현은 두 필드를 더 저장해야 한다.
- `BackgroundBashLookup` · `BackgroundBashKill` · `describeElsewhere` 는 바뀌지 않는다. `Kind.NOT_FOUND` 의 javadoc 만
  "다른 런타임" → "다른 소유자" 로.
- 소유자 없이 등록하는 옛 API(`registerTask` 와 id 만 받는 `readNewOutput` · `getTask` · …)는 손대지 않는다. 도구 셋은 이미
  쓰지 않는다(확인함: `BashOutputTool` · `KillShellTool` 은 `find`/`kill` 만 쓴다).

## 5. 파일·모듈별 변경

**aimon-core — main**

| 파일 | 변경 |
|------|------|
| `skill/hook/SkillHookActivator.java` | `activate(Skill, ToolContext)`; javadoc 의 수명 · INLINE 서술 갱신 |
| `skill/hook/SkillHookScope.java` | `hookRegistry()` 기본 메서드 |
| `skill/hook/RegistryBackedSkillHookActivator.java` → `ScopedSkillHookActivator.java` | 등록/해제 대신 뷰 생성; `close()` 가 층을 끈다 |
| `skill/hook/SkillScopedHookRegistry.java` (신규) | §4.1 |
| `skill/hook/NoOpSkillHookActivator.java`, `SkillHookSet.java` | 시그니처 · javadoc("registers … with a HookRegistry" 서술) |
| `tools/ToolContextKeys.java`, `tools/HookRegistryAccess.java` (신규) | `HOOK_REGISTRY` |
| `tools/skill/SkillTool.java` | 활성화 호출, 포크용 컨텍스트 사본 |
| `skill/fork/SubagentBackedSkillForkExecutor.java`, `tools/task/TaskTool.java`, `tools/workflow/WorkflowTool.java` | 컨텍스트의 레지스트리 우선; `WorkflowTool.runInBackground` 는 활성 스킬 가드가 있으면 거절(컨텍스트를 인자로 받게 한다) |
| `subagent/execution/DefaultSubagentExecutor.java`, `agent/impl/orca/OrcaAgentExecutor.java` | `HOOK_REGISTRY` 게시 |
| `agent/impl/orca/tool/OrcaSkillToolProvider.java` | 새 활성화기 배선 |
| `skill/hook/declarative/ShellHookOutcome.java` | `notRun` · `Unrun` · `unrunReason`; `notObserved()` 제거 |
| `skill/hook/declarative/DefaultShellActionExecutor.java`, `ShellActionRunner.java`, `NoOpShellActionExecutor.java`, `HostShellActionExecutor.java` | 분기마다 원인을 싣는다(`ShellActionRunner` 의 꼬리 return 을 catch 별로 나눈다); 클래스 javadoc 의 "fail-open" 문단 교체 |
| `skill/hook/declarative/DeclarativeHookOptions.java` | `failOpen` (+ equals/hashCode/toString) |
| `skill/hook/declarative/ShellHookVerdicts.java` (신규, 패키지 전용) | 판정과 사유 문구 |
| `skill/hook/declarative/AbstractDeclarativeShellHook.java`, `DeclarativePreToolHook.java` | 판정 호출; `options` 에서 `failOpen` 보관 |
| `skill/hook/declarative/ShellActionExecutor.java` | 계약 javadoc |
| `skill/parser/SkillHookSetParser.java` | `failOpen` 파싱 |
| `config/hook/HookHandlerSpec.java`, `HookRegistryApplier.java` | `failOpen` 필드(엄격한 불리언 검증)와 옵션 전달; 셸 미지원 실행기면 `command` 핸들러 건너뜀 |
| `tools/bash/BackgroundBashOwner.java` (신규) | §4.3 |
| `tools/bash/BackgroundBashRecord.java`, `BackgroundBashTask.java`, `BackgroundBashManager.java` | 소유자 타입 · 필드 · 비교 |
| `tools/bash/BashTool.java`, `BashOutputTool.java`, `KillShellTool.java` | `BackgroundBashOwner.of(context)`; javadoc 의 "runtime" 서술 |
| `tools/bash/BackgroundBashStore.java`, `BackgroundBashLookup.java`, `package-info.java` | 계약 문장 |

**다른 모듈.** `aimon-workflow-graaljs` 의 `GraalJsWorkflowTool` — 전경 환경 조립(`:251-252`)에서 컨텍스트의 레지스트리
우선, background 분기(`:180-195`)에서 활성 스킬 가드가 있으면 거절. `aimon-cli` ·
`aimon-bootstrap` · `aimon-spring-boot-starter` 는 `RegistryBackedSkillHookActivator` 나 `BackgroundBashManager.start/find/kill`
을 직접 부르는 곳이 있으면 컴파일 오류로 드러난다(매니저 생성만 한다 — 확인함).

**문서 (정본 + 번역본을 같은 커밋에).**

- 신규 `docs/design/tool/execution-environment-ee49-ee51-ee58-isolation-boundary.md` (ee9-ee12 · ee13-ee7 노트 형식: 상태
  머리말, §1 문제 … §9 열린 질문, 구현 뒤 §10 "벗어난 점"). `docs/design/README.md` 인덱스.
- `docs/backlog/execution-environment-open-items.md`: EE-49 · EE-51 · EE-58 닫힘(착수해 보니 달랐던 점 포함 — 특히 P13),
  머리 카운트 · 머리말; EE-1 · EE-59 에 외부 저장소 영향; §8 의 새 항목. `docs/backlog/README.md` 인덱스 행.
- `docs/design/tool/execution-environment.md` §10 · §13 · §15(훅 fail-open 서술, 백그라운드 범위), `docs/design/hook/hook-system.md`,
  `docs/references/aimon-skill-extensions.md:151`(fail-open 문장)과 `:153`("0 이 아닌 종료 코드 **또는 timeout** 이면 막는다"
  — 지금도 틀렸다: 막는 종료 코드는 2 뿐이다. 이 변경 뒤의 규칙으로 고쳐 쓴다: exit 2 는 거부, 그 밖의 종료 코드는 허용,
  종료 코드를 못 내면 `failOpen` 이 아닌 한 거부)과 `failOpen` 키 설명, 스킬 포크 안의 백그라운드 워크플로 제한, `docs/features/hook/hook-development-guide.md`,
  `docs/overview/scope-model.md`(스킬 훅 활성의 수명 · 백그라운드 작업 소유자가 표에 있다면).
- `CHANGELOG.md` `[Unreleased]`.
- 앞선 설계 노트 둘(ee9-ee12 §8/§9, ee13-ee7 §9)은 승인본을 고치지 않는 관례이므로 본문은 두고 "덧붙임" 한 줄로 이 노트를
  가리킨다.
- 검사: `check-backlog-registers.py`, `check-translation-staleness.py`, `check-translation-structure.py`, `check-doc-links.py`.

## 6. 실패 모드

| # | 상황 | 처리 |
|---|------|------|
| F1 | 사용자 정의 `SkillForkExecutor` 가 `HOOK_REGISTRY` 를 읽지 않는다 | 그 포크에서는 스킬 훅이 발화하지 않는다(가드라면 **조용히 꺼진다**). 구조상 코어가 강제할 수 없다 — CHANGELOG 의 깨지는 변경으로 적고 `SkillForkExecutor` javadoc 에 요구 사항으로 쓴다. 코어의 구현 하나는 테스트로 고정 |
| F2 | 스킬 훅 층 생성 중 예외 | 공유 상태를 건드리지 않으므로 되돌릴 것이 없다. `SkillTool` 의 기존 catch 가 `ToolResult.error` 로 바꾼다 |
| F3 | 포크가 끝난 뒤에도 뷰를 쥔 백그라운드 후손 | `close()` 가 층을 꺼서 런타임 훅만 보인다. 뷰 객체는 후손이 끝나면 수거된다 |
| F4 | 환경 장애 중 가드가 걸린 스킬 포크 | 그 포크의 **모든** 도구 호출이 사유와 함께 막힌다(의도된 동작). 모델은 사유에서 원인과 `failOpen` 을 읽는다. 루프는 예산 · 반복 상한으로 끝난다 |
| F5 | 셸이 자기 timeout 을 지키지 않아 훅 실행기 수준 timeout 이 먼저 터진다 | `HookExecutionPolicy` 의 `FAIL_OPEN` 으로 **통과한다**(P10). 이 묶음이 닫지 못하는 남은 틈이다 — 셸 timeout + grace 뒤에야 터지므로 정상 셸에서는 닿지 않는다. → Q4, §8 새 백로그 항목 |
| F6 | `onStart` 가드가 환경 장애로 턴 전체를 막는다 | 의도된 동작. 사유가 사용자에게 간다 |
| F7 | `preCompact` 가드가 못 돌아 압축이 건너뛰어진다 | 자동 압축이 계속 막히면 컨텍스트가 찬다. 기존 exit 2 거부와 같은 경로 · 같은 결과이므로 새 처리를 두지 않는다. 문서에 `preCompact` 관찰 훅은 `failOpen: true` 를 권한다고 적는다 |
| F8 | rewake 리플레이가 `hooks.json` `preTool` 훅을 환경 없는 컨텍스트로 다시 부른다 | 호스트 실행기는 환경이 필요 없다. 환경 결합 실행기에는 rewake 가 애초에 붙지 않는다(`toRewakeSpec` 가 버린다 — 확인함). 리플레이의 결과는 어떤 도구도 막지 않는다 |
| F9 | 저장소가 새 소유 필드를 잃는다 / 옛 레코드 | "범위 없음" 으로 읽혀 세션 호출자에게 "없음". 과하게 닫히는 쪽으로 실패한다. 계약 테스트가 왕복을 요구한다 |
| F10 | `ToolContext` 에 `SESSION_ID` 와 `EXECUTION_ID` 가 함께 있다(잘못 조립된 컨텍스트) | 1번 규칙이 이긴다(세션). 팩터리는 던지지 않는다 — 도구의 `execute()` 안에서 불린다 |
| F11 | 세션이 끝난 뒤 남은 작업 | 이 변경은 가시 범위만 바꾼다. 수명은 지금과 같다(상한 · 보존 기간 · teardown) |
| F13 | 가드 스킬 훅이 활성인 포크(또는 그 후손)가 `Workflow`/`WorkflowJs` 를 background 모드로 부른다 | **거절한다** — 원인을 담은 도구 오류. 러너는 불리지 않는다. 모델은 전경 모드로 다시 부를 수 있고 그 경로는 뷰를 물려받는다. 활성 스킬 훅이 관찰 전용뿐이면 실행하고 WARN — 그 하위 트리에서 스킬의 관찰 훅은 발화하지 않는다(문서화된 한계, 지금도 스킬이 끝난 뒤로는 발화하지 않았다) |
| F14 | 사용자 정의 도구가 agent-scoped 러너의 `runInBackground` 를 직접 부른다 | 코어가 강제할 수 없다. F1 과 같은 부류 — CHANGELOG 와 `WorkflowRunner.runInBackground` javadoc 에 "호출 컨텍스트의 스킬 훅은 따라가지 않는다; 가드가 활성이면 `HookRegistryAccess.hasActiveSkillGuards` 로 거절하라" 를 적는다 |
| F15 | `hooks.json` 에 `"failOpen": "true"` 같은 불리언 아닌 값 | 그 핸들러는 등록되지 않는다(WARN). 강제 변환으로 가드가 열리지 않는다 |
| F16 | 셸 미지원 실행기로 `hooks.json` 을 적용 | `command` 핸들러를 적용 시점에 건너뛴다(WARN). 발화 때 전부 막는 일이 없다 |
| F12 | id 추측 | 범위 밖 요청은 저장소 조회 뒤 같은 `notFound` 문구로 답한다. 로컬 히트/미스 사이의 응답 내용 차이는 없다(시간 차는 다루지 않는다) |

## 7. 테스트 전략

**EE-49**

- `SkillScopedHookRegistryTest`(신규): 기반 + 층의 순서; `close` 뒤 층이 사라짐; 기반의 뒤늦은 등록/`clearAll` 이 비침;
  쓰기가 기반으로 감; `isEmpty`.
- `RegistryBackedSkillHookActivatorTest` → `ScopedSkillHookActivatorTest`: **활성화 뒤에도 런타임 레지스트리의 `getHooks` 가
  바뀌지 않는다**(핵심 회귀); 훅 없는 스킬은 `EMPTY`; 컨텍스트의 `HOOK_REGISTRY` 가 기반이 됨(중첩).
- `SkillToolTest`: FORK — 포크 실행기가 받은 컨텍스트의 `HOOK_REGISTRY` 에 스킬 훅이 있고 호출자 컨텍스트에는 없다; 반환 뒤
  그 레지스트리에 스킬 훅이 없다; INLINE — 아무 것도 얹지 않는다.
- `SubagentBackedSkillForkExecutorTest` · `TaskToolTest` · `WorkflowTool` 테스트 · `GraalJsWorkflowTool` 테스트
  (`aimon-workflow-graaljs`): 컨텍스트 레지스트리가 생성자 값보다 우선 — **네 지점 모두**.
- 백그라운드 워크플로(`WorkflowTool` · `GraalJsWorkflowTool` 각각): (a) 컨텍스트에 활성 `preTool` 스킬 훅 → 오류 결과이고
  **러너의 `runInBackground` 가 불리지 않는다**(mock 검증), 문구에 스킬 이름과 "foreground"; (b) 관찰 전용(`postTool`)만 →
  실행됨; (c) 스킬 훅 없음 / `HOOK_REGISTRY` 없음 → 지금과 같음; (d) 범위를 닫은 뒤 → 실행됨; (e) 중첩 스킬의 바깥 층에만
  가드 → 거절.
- `HookRegistryAccessTest`(신규): `withHookRegistry` 가 다른 키를 보존하고 그 키만 바꾼다; 같은 빌더에 두 번 쓰면
  `IllegalStateException`(write-once); `hasActiveSkillGuards` 의 사슬 판정.
- 통합(`agent/impl/orca/it/`, `SubagentIdentityIsolationIntegrationTest` 옆): 한 런타임의 세션 A 가 FORK 스킬(`preTool` 기록
  훅)을 도는 동안 세션 B 가 도구를 부른다 → 훅은 A 의 포크 호출만 기록(수용 기준 1). 포크 안의 중첩 `Task` 에서도 발화.
  스킬 반환 뒤 A 의 다음 도구 호출에는 발화하지 않는다.

**EE-51**

- `ShellHookOutcomeTest`: `notRun` 의 원인 · 사유 · 상한; `isDenied()` 는 여전히 false.
- `DefaultShellActionExecutorTest` · `HostShellActionExecutorTest` · (신규) `ShellActionRunnerTest`: 분기별 원인 —
  환경 없음, unavailable(`shell()` 과 `execute` 양쪽), timeout, `ShellExecutionException`, 기타 예외, NoOp.
- 이벤트 × 원인 매개변수 테스트(`DeclarativePreToolHookTest`, `DeclarativeOnStartHookTest`, `AbstractDeclarativeShellHookTest`
  에 `preCompact` · `permissionRequest`): 기본 → block/deny 이고 사유에 원인 · 스킬 · 이벤트가 있다; `failOpen` → 성공;
  exit 0/2/기타는 그대로.
- 관찰 이벤트(`DeclarativePostToolHookTest`, `DeclarativeOnStopHookTest`, 그리고 `DeclarativeShellHookBinding` 의 나머지 전부를
  도는 테스트): 어떤 원인에도 성공.
- `SkillHookSetParserTest` · `HookRegistryApplierTest` · `JacksonHookConfigParser` 테스트: `failOpen` 파싱, 기본값, 잘못된 타입
  (`"true"` · `1` · `null` → 스킬은 파싱 오류, `hooks.json` 은 그 핸들러 미등록); 모델에게 가는 거부 사유에 `failOpen` 이라는
  글자가 **없다**.
- `HookRegistryApplierTest`: `isShellSupported()==false` 실행기 → `command` 핸들러가 등록되지 않는다.
- 통합: 환경 제공자가 실패하는 실행에서 `preTool` 가드가 걸린 스킬 포크의 도구 호출이 사유를 담은 도구 오류로 끝난다.

**EE-58**

- `BackgroundBashOwnerTest`(신규): §3.3 의 네 규칙; 포크 컨텍스트(실행 id + 호출 세션)가 부모와 같은 소유자; F10; 세션과
  실행 id 를 함께 준 3-인자 팩터리와 그런 레코드의 `owner()` 가 던지지 않고 세션 소유자를 낸다.
- `BackgroundBashManagerTest`: 같은 런타임 · 다른 세션 → `NOT_FOUND`(find · kill, kill 은 취소를 요청하지 않음); 같은 세션 →
  동작; 포크 ↔ 부모 양방향; 루틴 실행 id 끼리 격리; 범위 없는 호출자 ↔ 범위 없는 작업(지금 동작 유지).
- `BackgroundBashStoreContractTest`(+ `InMemoryBackgroundBashStoreTest`): 소유 필드 셋의 왕복, `settle` 이 보존.
- `BashOutputToolTest` · `KillShellToolTest`: 다른 세션의 응답이 **없는 id 의 응답과 글자까지 같다**; 다른 노드의 레코드
  (`ELSEWHERE`)도 다른 세션에는 `notFound`, 같은 세션에는 `describeElsewhere`.
- `BackgroundTaskTurnIntegrationTest`: 포크가 띄운 작업을 부모 턴이 `BashOutput`/`KillShell` 한다.

**공통.** `./gradlew checkAll`; ArchUnit(`HookRegistryAccess` 가 `*.impl` 을 끌어오지 않음, 새 타입 이름이 `Session*` 규칙에
걸리지 않음 — `BackgroundBashOwner` · `SkillScopedHookRegistry` 는 해당 없음).

## 8. 공개 표면의 변경과 저장소 밖 영향

**CHANGELOG `[Unreleased]` — 깨지는 변경.**

1. `SkillHookActivator.activate(Skill)` → `activate(Skill, ToolContext)`; `RegistryBackedSkillHookActivator` 제거/개명. 스킬
   훅은 더는 런타임 `HookRegistry` 에 나타나지 않는다(그것을 열거해 스킬 훅을 보던 코드는 못 본다).
2. 사용자 정의 `SkillForkExecutor` 와 서브에이전트를 스폰하는 사용자 정의 도구는 `HookRegistryAccess.of(toolContext)` 의
   레지스트리로 포크를 돌려야 스킬 훅이 발화한다.
2a. **동작 변경**: 가드 스킬 훅이 활성인 실행 안에서 `Workflow` · `WorkflowJs` 의 background 모드는 거절된다. 관찰 전용 스킬
   훅은 백그라운드 워크플로의 서브에이전트에 더는 발화하지 않는다. `runInBackground` 를 직접 부르는 사용자 정의 도구는
   `HookRegistryAccess.hasActiveSkillGuards` 를 확인해야 한다.
3. `ShellHookOutcome.notObserved()` 제거 → `notRun(cause, detail)`. 사용자 정의 `ShellActionExecutor` 는 원인을 실어야 한다.
4. **동작 변경**: 가드 이벤트의 셸 훅(스킬 · `hooks.json` 모두)이 명령을 돌리지 못하면 거부한다. timeout 포함. `failOpen: true`
   로 되돌린다. 셸 미지원 실행기로 적용한 `hooks.json` 의 `command` 핸들러는 등록되지 않는다.
5. `BackgroundBashManager.start/find/kill` 의 소유자 인자가 `BackgroundBashOwner`; `BackgroundBashTask.getOwnerRuntimeId()` →
   `getOwner()`; `BackgroundBashRecord` 에 필드 둘. 사용자 정의 `BackgroundBashStore` 는 새 필드를 보존해야 한다.
6. **동작 변경**: `BashOutput`/`KillShell` 은 같은 세션(과 그 세션을 대신하는 포크)의 작업만 본다.

**외부 저장소 (EE-1 · EE-59 에 더할 것 — 추론, 그쪽 체크아웃 없음).** aimon-sandbox: `ShellActionExecutor` 나
`BackgroundBashStore` 를 구현하거나 `BackgroundBashManager` 를 직접 부른다면 3 · 5 를 따라와야 한다. 환경 제공자 SPI
(`ExecutionEnvironment` · `VirtualShell`)는 이 묶음에서 바뀌지 않는다. 다만 4 때문에 샌드박스의 `shell()` 이
`ExecutionEnvironmentUnavailableException` 을 던지는 순간이 이제 "그 실행의 가드 걸린 도구가 전부 막힘" 으로 보인다 — 그쪽
문서에 적을 값이다. aimon-browser: 서브에이전트를 스폰하는 도구가 있으면 2 · 2a.

**새 백로그 항목(닫으면서 여는 것).**

- 백그라운드 워크플로가 호출 컨텍스트의 스킬 훅을 물려받지 못한다(P16) — 이 변경은 가드를 거절로, 관찰을 문서화된 손실로
  처리했다. 제대로 잇려면 러너 SPI 와 `RunId` 합류 규칙을 함께 다시 봐야 한다.
- 훅 실행기 수준 timeout 의 `FAIL_OPEN` 이 선언 가드의 fail-closed 를 우회하는 틈(F5).
- `preTool` 의 `http` · `mcp` 액션이 실행기 미배선 · 호출 실패 때 통과하는 것("degrading to success") — EE-51 과 같은 질문의
  셸 아닌 판본.
- Q3(exit 126/127), Q5(호출자 없는 포크의 작업)가 "지금은 두기" 로 정해지면 각각 한 줄.
- 슬래시 명령 경로(`LlmSkillExecutor` + `SKILL_FORK_EXECUTOR_KEY`)는 스킬 훅을 활성화하지 않는다 — 이 변경 전부터 그렇고
  (활성화 호출처는 `SkillTool` 하나, P4) 이 변경이 고치지 않는다.

## 9. 풀지 못한 것 — 열린 질문

과제 문장만으로 정할 수 없어 **기본값을 골라 설계에 넣었고**, 사람이 뒤집을 수 있게 따로 적는다. PR 설명 맨 위로 간다.

- **Q1 (EE-49) — 활성화한 실행 자신에게는 발화하지 않는다.** 과제는 "활성화한 실행(과 그 포크)" 라고 썼다. 설계는 "그 스킬의
  포크와 후손" 으로 읽었다 — 활성화한 실행에서 지금 발화하는 것은 FORK 스킬이 도는 동안의 병렬 형제 도구 이벤트뿐이고
  문서화된 계약이 아니기 때문이다. 부모에게도 발화해야 한다면 뷰를 부모의 진행 중인 턴에 끼워 넣을 이음매(실행기가 턴 중에
  레지스트리를 바꿔 끼우는 것)가 새로 필요하고, 그것은 이 설계에 없다.
- **Q2 (EE-51) — `hooks.json` 에도 fail-closed 를 적용한다.** 일관성을 골랐다. 운영자의 기존 `preTool`/`onStart` 명령 훅이
  timeout 때 막기 시작하는 동작 변경이다. 범위를 스킬로만 줄이려면 `HookRegistryApplier` 가 옵션 기본값을 `failOpen=true` 로
  주면 되고(한 줄), 대가는 같은 선언이 출처에 따라 반대로 읽히는 것이다.
- **Q3 (EE-51) — exit 126/127 은 그대로 통과다.** 샌드박스에 가드 스크립트가 없으면 셸은 127 을 "종료 코드" 로 돌려주고,
  기존 계약("0 · 2 가 아니면 오작동 → 허용")이 그것을 통과시킨다. 사실상 "돌리지 못함" 이지만 스크립트 자신이 127 을 낼 수도
  있어 구별되지 않고, 계약을 바꾸면 exit 1 을 내는 기존 훅 전부에 영향이 간다. 이 묶음에서는 건드리지 않고 백로그로 남기는
  쪽을 골랐다. EE-50(`${AIMON_SKILL_DIR}` 없음)이 이 경로를 실제로 밟게 만든다.
- **Q4 (EE-51) — 훅 실행기 수준 timeout 의 `FAIL_OPEN`(F5)을 이 묶음에서 닫을지.** 닫지 않는 쪽을 골랐다(정책 기본값 변경은
  프로그램으로 등록한 모든 훅에 번진다).
- **Q5 (EE-58) — 호출자 없는 포크가 띄운 작업은 그 포크만 본다.** 루틴이 포크를 띄우고 그 포크가 백그라운드 명령을 띄우면
  루틴은 그 작업을 읽지도 멈추지도 못한다(상한과 teardown 이 끝낸다). 잇려면 전달되는 "루트 실행" 정체성이 필요하다.
- **Q6 (EE-58) — 같은 세션의 형제 포크는 서로의 작업을 멈출 수 있다.** 같은 사용자 세션을 대신하므로 허용했다. 포크 단위로
  더 좁히면 부모가 포크의 작업에 닿는 길을 따로 만들어야 한다.
- **Q8 (EE-49) — 가드 스킬 훅이 활성일 때 백그라운드 워크플로를 거절한다.** 지금은 실행되고 (스킬이 활성인 동안만) 가드를
  맞았다. 뷰를 실어 보낼 수 없어서(P16) "소리 없이 가드가 꺼지는 것" 과 "거절" 가운데 거절을 골랐다. 훅을 선언한 FORK 스킬
  안에서 백그라운드 워크플로를 쓰던 사용자에게는 기능 축소다. 관찰 전용 훅은 거절하지 않고 손실을 받아들였는데, 감사 훅을
  가드만큼 무겁게 본다면 그쪽도 거절로 바꿀 수 있다(판정 한 줄).
- **Q7 — 이름.** `ScopedSkillHookActivator`, `SkillScopedHookRegistry`, `BackgroundBashOwner`, `ShellHookOutcome.Unrun`,
  선언 키 `failOpen` 은 가칭이다. 과제가 예로 든 `failOpen` 은 그대로 썼다.
- **확인하지 못한 것.** aimon-sandbox · aimon-browser 의 소스(§8 의 영향은 코어 SPI 에서 끌어낸 추론), `docs/overview/scope-model.md` 에 이 셋에 해당하는 행이 있는지. 구현 첫 단계에서 확인할 값이다.

## 10. 구현이 이 설계에서 벗어난 점

본문은 승인본 그대로이므로, 구현이 다르게 한 것과 본문이 정하지 않아 구현이 정한 것을 여기에 적는다. 이름(Q7)은 본문의
가칭을 그대로 썼다 — `ScopedSkillHookActivator`, `SkillScopedHookRegistry`, `BackgroundBashOwner`, `ShellHookOutcome.Unrun`,
선언 키 `failOpen`.

### 10.1 본문과 다르게 한 것

1. **`hooks.json` 의 불리언 아닌 `failOpen` 은 그 핸들러를 빼지 않고 `false` 로 읽는다** (§4.2, F15, §7 의
   "그 핸들러 미등록"). 본문이 적은 기제는 본문이 적은 결과를 내지 못했다(설계 리뷰 2) — `HookHandlerSpec.fromJson` 은
   `@JsonCreator` 라서 거기서 던진 예외는 `HookConfigParseException` 이 되고 핸들러 단위의 WARN 경로에는 닿지 않는다. 그리고
   핸들러만 빼는 쪽은 `"failOpen": "false"` 라고 잘못 쓴 **진짜 가드**를 WARN 한 줄과 함께 없앤다. 첫 구현은 그래서 **파일
   전체 거절**을 골랐으나 PR #207 리뷰(B1)가 그것도 fail-open 이라는 것을 짚었다 — 시작 시 파싱 실패는 레지스트리를 **비운 채**
   WARN 한 줄로 넘어가므로(`HookHotReloadBootstrap`), 키 하나의 오타가 파일의 **모든** 가드를 끈다. 메인테이너 결정("판단하지
   못한 가드는 막는다")에 맞춰 지금은 이렇다: `fromJson` 은 `failOpen` 을 `JsonNode` 로 받아 JSON 불리언이 아니면(명시적
   `null` 포함) **`false`** 로 읽고 원문을 `HookHandlerSpec.getRejectedFailOpen()` 에 남긴다. `HookConfigLoader` 가 파일 ·
   이벤트 · 핸들러를 밝혀 WARN 한다. 강제 변환으로 가드가 열리지 않고, 핸들러도 가드도 사라지지 않는다. 테스트는
   `"true"` · `"false"` · `1` · `0` · `null` · `[true]` 를 고정한다(`JacksonHookConfigParserTest
   .failOpenThatIsNotABooleanIsReadAsFalse`, `HookConfigLoaderTest.nonBooleanFailOpenIsReadAsFalseWithAWarning`). 스킬
   frontmatter 쪽은 본문대로 파싱 오류다(스킬 하나만 빠지고 다른 스킬의 가드는 남는다). 다른 파싱 오류가 시작 때 파일의
   훅을 전부 비우는 것은 이 변경 전부터의 동작이라 그대로 두고 EE-71 로 올렸다.
2. **`HookRegistryAccess.hasActiveSkillGuards` 만으로는 거절 문구를 만들 수 없어 이름을 돌려주는 메서드를 더했다** (§4.1).
   본문의 시그니처는 `boolean` 인데 거절 문구는 스킬 이름을 싣고 §7 이 그것을 단언한다(리뷰 2). 그래서
   `activeSkillGuards(ToolContext)` 가 `List<String>`(바깥 스킬부터)을 돌려주고 `hasActiveSkillGuards` 는 그것이 비었는지만
   본다. 관찰 전용 훅의 WARN 을 위해 `activeSkillHooks(ToolContext)` 도 있다. 뷰 쪽은 `hasActiveGuards()` 대신
   `activeGuardSkills()` · `activeSkills()` 다.
3. **거절 문구는 한 곳에 있다** — `HookRegistryAccess.backgroundRefusal(List<String>)`. `WorkflowTool` 은 그것을
   `ToolExecutionException` 으로, `GraalJsWorkflowTool` 은 `ToolResult.error` 로 감싼다(리뷰 2 의 "두 문구가 갈라지지 않게").
   중첩 스킬이면 이름이 여럿 실린다(`skill 'a', 'b' has guard hooks active …`).
4. **`timeout` 의 `<cause>` 는 `timed out after 30s` 가 아니라 `timed out: no exit status within 30000ms` 다** (§3.2).
   사유를 `<cause>: <detail>` 한 형식으로 맞추려고 원인 낱말(`ShellHookOutcome.Unrun#description()`)과 세부를 나눴다.
   나머지 넷의 원인 낱말은 본문 그대로다.
5. **EE-51 의 통합 테스트는 처음에 "환경 제공자가 실패하는 실행" 이 아니라 "명령이 timeout 안에 끝나지 않는 실행" 으로
   덮었다** (§7). 실제 런타임 · 실제 `DefaultShellActionExecutor` · 실제 로컬 셸로 `sleep 20`(timeout 300ms) 가드를 걸어
   "도구가 막히고 사유가 모델의 관찰에 실린다" 와 `failOpen` 이면 도구가 돈다는 것을 고정했다. PR #207 리뷰(S5) 뒤로는
   본문이 적은 모양도 있다: 하네스에 제공자 데코레이터와 셸 훅을 받는 스킬 파서 옵션을 더해, **스킬 frontmatter 의 셸
   `preTool` 가드가 스킬 포크 안에서 돌고 그 포크의 제공자가 실패할 때**(던짐 · null — 둘 다 `resolveOrUnavailable` 경로)
   환경이 필요 없는 도구(`TodoWrite`)가 원인을 실은 사유와 함께 막힌다
   (`IsolationBoundaryIntegrationTest.skillShellGuardBlocksWhenTheForksEnvironmentProviderFails`). 포크의 훅 컨텍스트에
   환경이 아예 없는 경우(`NO_ENVIRONMENT`)는 조립된 런타임에서 만들 길이 없어 — 포크는 늘 `resolveOrUnavailable` 을 거친다 —
   단위 테스트가 덮는다(`DefaultShellActionExecutorTest`, 이벤트 × 원인 매개변수 테스트).
6. **§7 이 `BackgroundTaskTurnIntegrationTest` 에 두라고 한 "포크가 띄운 작업을 부모 턴이 읽고 멈춘다" 는
   `IsolationBoundaryIntegrationTest` 에 있다.** 그 클래스는 백그라운드 **서브에이전트** 작업(`Task` · `AgentOutput` ·
   `TaskStop`)의 것이고 백그라운드 `Bash` 와 무관하다. 세 항목의 런타임 수준 테스트를 한 클래스에 모았다.

### 10.2 본문이 정하지 않아 구현이 정한 것

- **`BackgroundBashOwner.none()`.** 매니저의 소유자 인자를 nullable 로 두지 않았다 — `start` · `find` · `kill` 은 null 을
  거절하고, 런타임 밖의 호출자는 `none()` 을 넘긴다. 소유자 없이 등록하는 옛 API(`registerTask`)가 만든 작업의 소유자도
  `none()` 이다.
- **`BackgroundBashRecord.Builder.owner(BackgroundBashOwner)`.** 세 필드를 한 번에 싣는다. 필드별 세터도 그대로 있다(저장소
  구현이 직렬화한 값을 되돌릴 때 쓴다).
- **`SkillHookSet.guardEvents()` · `hasGuards()`.** "거부 채널이 있는 이벤트"(P7)가 세 군데(뷰의 가드 판정, 스킬 파서의
  `failOpen` 경고, 문서)에서 필요해 한 곳에 뒀다. 처음에는 넷이었고 지금은 셋이다 — `onStart` 를 뺐다(§10.7, EE-70).
- **`failOpen` 은 거부 채널이 있는 이벤트의 `exit 2` 를 약하게 하지 않는다.** 종료 코드를 낸 명령은 옵션과 무관하게 본문
  §3.2 의 계약대로다(테스트 `execute_failOpen_doesNotWeakenAnExitTwoVeto`).
- **`ShellHookOutcome.unrunReason()` 의 상한은 `denyReason()` 과 같은 `MAX_DENY_REASON_LENGTH` 이고 같은 잘림 표식을 쓴다.**
- **활성화기는 실행 모드를 보지 않는다.** 훅이 있는 스킬이면 INLINE 이어도 뷰를 만들어 범위에 싣지만, `SkillTool` 은 FORK
  분기에서만 그것을 포크에 넘긴다. 결과는 본문과 같다(INLINE 은 아무 데도 얹히지 않는다).
- **`OrcaAgentExecutor` 의 슬래시 명령 컨텍스트에는 `HOOK_REGISTRY` 를 싣지 않았다.** 그 경로의 스폰 지점은 생성자 값으로
  떨어지고, 그 값이 곧 런타임 레지스트리다 — 지금 동작 그대로다(EE-68).
  *(2026-10-05: EE-68 이 닫히며 더는 사실이 아니다 — 슬래시 명령 컨텍스트는 `HOOK_REGISTRY` 와 활성화기를 싣고 슬래시
  포크도 스킬 훅을 받는다. 백로그의 EE-68 닫힘 절 참고.)*

### 10.3 설계 리뷰 2 의 지적과 처리

| 지적 | 처리 |
|------|------|
| `hooks.json` 의 잘못된 `failOpen` — 적힌 기제로는 계획한 테스트가 통과할 수 없다 | §10.1-1. 처음에는 파일 전체 거절로 정했고, PR #207 리뷰 뒤로 `false` 로 읽어 핸들러를 남기는 쪽으로 바꿨다 |
| "잘못 쓴 옵션이 가드를 열지 않는다" 는 핸들러 단위 탈락에서는 참이 아니다 | 지금은 참이다 — 핸들러가 닫힌 채 남는다. 파일 전체 거절은 시작 시 파일의 가드를 전부 끄므로 이 지적을 풀지 못했다(§10.7) |
| F4 가 "모델은 사유에서 원인과 `failOpen` 을 읽는다" 고 적는다(개정 2 가 힌트를 뺐다) | 본문은 고치지 않는다. **F4 의 그 문장은 사실이 아니다** — 사유에 `failOpen` 이라는 글자는 없고 테스트가 그것을 단언한다. 여는 방법은 WARN 로그와 문서에만 있다 |
| 가드 스킬 포크가 `run_in_background` `Task` 를 띄우고 반환하면 그 서브에이전트는 가드 없이 계속 돈다 | 문서화된 한계로 받아들이고 백로그에 올렸다(EE-69). 백그라운드 워크플로와 다르게 다루는 이유: `Task` 는 스킬이 활성인 동안은 뷰를 물려받아 가드를 맞고, 스킬이 끝난 뒤에야 벗어난다(이 변경 전과 같다). 백그라운드 워크플로는 **처음부터** 가드를 맞지 않는다 — 그래서 그쪽만 거절한다. `SkillHookActivator` javadoc 에 둘 다 적었다 |
| `hasActiveSkillGuards` 가 이름을 돌려주지 않는다 | §10.1-2 |
| 문서 목록에 번역본 유무가 없다 | 리뷰가 확인해 준 대로였다. 번역본이 있는 것(`hook-development-guide` · `hook-config-guide` · `scope-model`)은 같이 고쳤다. `scope-model.md` 에는 `BackgroundBashManager` 행이 있어 소유자 키를 거기에 적었고, 스킬 훅 활성의 행은 없어 더하지 않았다 |
| 거절 문구를 두 도구가 따로 갖지 않게 | §10.1-3 |

### 10.4 열린 질문은 어떻게 되었나

**기본값대로 구현했고 사람이 뒤집을 수 있는 결정 — PR 설명 맨 위에 적을 것.**

- **Q1 — 활성화한 실행 자신에게는 발화하지 않는다.** 과제 문장("활성화한 실행(과 그 포크)")보다 좁다.
- **Q2 — `hooks.json` 에도 fail-closed 를 적용한다.** 운영자의 기존 `preTool` · `onStart` · `preCompact` ·
  `permissionRequest` `command` 핸들러가 timeout · 셸 실패 때 막기 시작한다.
- **Q8 — 가드 스킬 훅이 활성이면 `Workflow` · `WorkflowJs` 의 background 모드를 거절한다.** 관찰 전용 훅은 거절하지 않는다.
- Q6(같은 세션의 형제 포크는 서로의 백그라운드 작업을 읽고 멈춘다)도 기본값대로다.

**백로그로 옮긴 것.**

| 백로그 | 출처 | 내용 |
|--------|------|------|
| EE-63 | Q8 · §8 · P16 | 백그라운드 워크플로는 호출 컨텍스트의 스킬 훅을 물려받지 못한다 — 가드는 거절, 관찰은 손실 |
| EE-64 | Q4 · F5 | 훅 실행기 수준 timeout 의 `FAIL_OPEN` 이 선언 가드의 fail-closed 를 우회한다 |
| EE-65 | §8 | `preTool` 의 `http` · `mcp` 액션은 실행기 미배선 · 호출 실패 때 통과한다 |
| EE-66 | Q3 | 가드 명령이 없어서 나는 exit 126/127 은 통과다 |
| EE-67 | Q5 | 호출자 없는 포크가 띄운 백그라운드 작업은 그 포크만 본다 |
| EE-68 | §8 | 슬래시 명령으로 부른 스킬은 훅을 활성화하지 않는다 |
| EE-69 | 설계 리뷰 2 | 스킬 포크가 띄운 백그라운드 서브에이전트는 스킬이 끝난 뒤 가드 없이 돈다 |

외부 저장소 영향(§8)은 EE-1(aimon-browser · 서브에이전트를 스폰하는 사용자 정의 도구)과 EE-59(aimon-sandbox)에 더했다.
**"확인하지 못한 것"(§9 마지막) 가운데 `scope-model.md` 는 확인했다(위 표). aimon-sandbox · aimon-browser 의 소스는 이번에도
확인하지 못했다** — 그쪽 영향은 여전히 코어 SPI 에서 끌어낸 추론이다.

### 10.5 착수해 보니 본문과 달랐던 사실

- **§4.2 의 frontmatter 예시는 파싱되지 않는다.** `matcher: { tool: Bash }` 라고 적었지만 스킬 훅의 `matcher` 는 문자열이다
  (`matcher: Bash`). 문서의 예시는 문자열로 적었다.
- **셸 미지원 실행기로 `hooks.json` 을 적용하는 조립은 코어의 테스트 셋에 있었다**(`HookRegistryApplierTest` ·
  `HookRegistryReloaderTest` · `HookHotReloadBootstrapTest` · `HookConfigHotReloadE2ETest` 가 `NoOpShellActionExecutor` 로
  `command` 핸들러를 등록해 왔다). 본문 P18 이 예상한 "조용히 아무 것도 안 함 → 전부 막음" 은 가정이 아니라 그 테스트
  클래스 넷이 실제로 밟고 있던 모양이었다. 적용 시점에 건너뛰게 하고 그 테스트들은 셸을 지원하는 실행기로 옮겼다. 운영 조립
  (CLI 의 `AgentSetupFactory`)은 호스트 셸 실행기를 쓰므로 영향이 없다.
- **F4 의 "루프는 예산 · 반복 상한으로 끝난다" 보다 먼저 끝난다.** 도구 호출이 전부 실패한 iteration 이 연달아 쌓이면
  실행기의 정체 가드(`StalledIterationGuard`)가 루프를 끊는다 — 가드가 못 돌아 모든 도구 호출이 막히는 실행은 예산을 다
  쓰기 전에 그 경로로 끝난다(통합 테스트를 쓰다가 밟았다).
- **aimon-cli 의 테스트 하나가 옛 fail-open 을 "호스트로 되돌아가지 않았다" 의 증거로 쓰고 있었다**
  (`AgentSetupFactorySkillHookShellTest` — `exit 2` 훅이 SUCCESS 면 안 돌았다는 뜻). 이제 안 돌아도 막히므로 그 구별이
  사라진다. `exit 0` 훅이 **환경 없음 사유로** 막히는 것으로 바꿔 같은 것을 증명하게 했다.

### 10.6 문서

- 명세 [`execution-environment.md`](execution-environment.md) §5.3 의 소유 범위, §10 의 훅 서술, §13 의 계약 표(한 행을
  고치고 한 행을 더했다), §15 를 고쳤다.
- `docs/references/aimon-skill-extensions.md` — 셸 실행 시맨틱(본문이 짚은 `:153` 의 틀린 문장 포함), `failOpen`, 적용 범위,
  백그라운드 워크플로 제한. `docs/references/hooks-specification.md` §6 에 "종료 코드 없음" 행.
- `docs/features/hook/hook-config-guide.md` · `hook-development-guide.md` 와 두 번역본, `docs/overview/scope-model.md` 와
  번역본.
- 앞선 설계 노트 둘(ee9-ee12, ee13-ee7)에는 본문을 두고 머리말에 "덧붙임" 한 줄씩을 더했다.
- `CHANGELOG.md` `[Unreleased]`, 백로그(세 항목 닫힘 · 일곱 항목 추가 · EE-1 · EE-59 보강).

### 10.7 PR #207 리뷰의 지적과 처리

메인테이너의 기준 결정은 EE-51 그대로다 — **판단하지 못한 가드는 막는다.** 지적마다 소스로 먼저 확인했다.

| 지적 | 처리 |
|------|------|
| B1 — 불리언 아닌 `failOpen` 이 파일 전체의 파싱을 실패시키고, 시작 시에는 레지스트리가 빈 채 WARN 한 줄로 넘어간다(fail-open) | `false` 로 읽고 핸들러를 남긴다. 로더가 파일 · 이벤트 · 핸들러를 밝혀 WARN 한다(§10.1-1). 다른 파싱 오류가 시작 때 파일의 훅을 비우는 것은 이 변경 전부터의 동작이라 바꾸지 않았다(→ EE-71). 핫 리로드 실패가 이전 설정을 그대로 두는 것은 가이드에 적었다 |
| S1 — 던지는 선언 가드는 통과된다(`ShellActionRunner` 는 `RuntimeException` 만 잡고, 훅 정책의 기본 예외 매퍼는 가드 이벤트에서 성공을 낸다) | `DeclarativePreToolHook` 과 `AbstractDeclarativeShellHook` 이 셸 분기를 `RuntimeException \| LinkageError` 로 감싸 `notRun(EXECUTION_FAILED, <클래스 이름>)` 으로 읽는다. 그래서 fail-closed 판정과 `failOpen` 이 그대로 적용된다. 예외 전체는 로그에만 |
| S2 — 사유가 명령 문자열을 흘릴 수 있다(`LocalShell` 의 예외 메시지가 명령을 싣는다) | `EXECUTION_FAILED` 의 세부는 예외의 클래스 이름뿐이다. `shell()` 이 사용 불가 예외가 아닌 것을 던진 `ENVIRONMENT_UNAVAILABLE` 도 같다. `ExecutionEnvironmentUnavailableException` 의 메시지는 남겼다 — 도구가 이미 모델에게 그대로 보여 주는 값이고, 원인을 사유에 싣는다는 결정의 그 "원인" 이다. 메시지는 로그에 남는다 |
| S3 — 스킬 frontmatter 의 `onStart` 가드는 막지 못한다(`DefaultSubagentExecutor` 가 `onStart` 결과를 advisory 로만 읽는다) | 포크의 `onStart` 동작은 바꾸지 않았다(사람의 결정). 문서를 사실대로 고쳤고, `onStart` 를 `SkillHookSet.GUARD_EVENTS` 에서 빼 `onStart` 만 있는 스킬이 백그라운드 워크플로를 거절하게 만들지 않는다(→ EE-70) |
| S4 — 가드 스킬 포크가 `ScheduleTask` 로 루틴을 잡으면, 루틴은 나중에 런타임 레지스트리에서 스킬의 가드 없이 돈다 | 백그라운드 워크플로와 같은 이유로 `ScheduleTaskTool` 이 거절한다(`ToolResult.error`, 문구는 `HookRegistryAccess.scheduleRefusal`). 백그라운드 `Task` 는 그대로다(EE-69) |
| S5 — 제공자가 실패하는 포크에서 스킬의 셸 가드를 돌리는 조립 테스트가 없다 | §10.1-5 |
| `vetoResult` 를 두 번 불러 거부 채널을 탐침한다 | `canVeto()` 를 더했다 |
| 가드 판정은 컨텍스트의 레지스트리가 `SkillScopedHookRegistry` **자신**이어야 한다 | `HookRegistryAccess` javadoc 에 적었다 — 감싸는 데코레이터는 백그라운드 거절을 끈다 |
| 훅을 선언한 INLINE 스킬 | 거절하지 않고 로드 시 WARN 한다(훅이 발화하지 않는다) |

손대지 않은 것: `HOOK_REGISTRY` 가 읽기 전용 뷰라는 점, `skill.hook` ↔ `tools` 패키지 순환(활성화기가 읽는 키 `HOOK_REGISTRY` 가 `tools.ToolContextKeys` 에 있어, 키를 옮기는 API 변경 없이는 풀리지 않는다),
같은 세션의 형제 포크가 서로의 백그라운드 작업을 보는 것(Q6), 활성화한 실행 자신에게는 발화하지 않는 것(Q1).
