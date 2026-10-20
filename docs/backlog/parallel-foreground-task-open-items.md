# 포그라운드 Task 병렬 실행 — 등록 항목 6건 (열림 6 · 닫힘 0)

출처는 [`../design/subagent/parallel-foreground-task.md`](../design/subagent/parallel-foreground-task.md) 의 구현이다. 그 문서
§8(Open questions)과 §9(구현이 이 설계와 갈라진 자리) 가운데 **이 변경 밖으로 결과가 나가는 것**만 여기로
올라왔다. 이 변경 안에서 닫힌 결정(블로킹 대신 inline fallback, 기본 `maxForkDepth = 1`, 허가가 팩토리
전체에 걸치는 것, 이름 등)은 설계 문서 §8 에 남고 여기 옮겨 적지 않는다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §8 · §9 는 설계·구현 시점의 기록이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 등록 시점(2026-10-20, 브랜치 `herdr/parallel-foreground-task` 의
작업 트리, 기준 `c2fa8fb8`) 기준이다. 경로는 따로 적지 않으면 `modules/aimon-core/src/main/java/at/aimon/core/` 아래다.

---

## PF-1 — `DedicatedThreadConfig` 를 `AimonStackBuilder` · Spring 설정으로 노출하지 않았다 · **열림**

**무엇을.** `aimon-bootstrap` 의 `AimonStackBuilder` 와 Spring 스타터의 `AimonProperties` 에서 포그라운드 Task 병렬 실행을 켜고
`maxThreads` · `maxForkDepth` 를 정할 수 있게 한다. 같은 자리에 기존 `ToolConcurrencyConfig` 도 함께 노출한다.

**왜.** 지금은 `OrcaAgentExecutorFactory.withDedicatedThreadConfig` 를 직접 부르는 임베더만 켤 수 있다. 스타터나 부트스트랩으로
조립한 호스트는 기능이 있다는 것을 알아도 켤 길이 없다. 기존 `ToolConcurrencyConfig` 도 같은 처지라(설계 §3.10, grep 0건) 이번에
하나만 노출하면 두 병렬 스위치의 노출 수준이 갈린다 — 그래서 둘을 함께 하는 후속으로 미뤘다(설계 §8 Q4).

**어디** *(2026-10-20 확인)* — `agent/impl/orca/OrcaAgentExecutorFactory.java` 의 `withDedicatedThreadConfig` ·
`withToolConcurrencyConfig`. 노출할 자리는 `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java` 의
팩토리 조립부와 스타터의 `AimonProperties`.

**언제 다시 볼까.** 스타터나 부트스트랩 사용자가 둘 중 하나를 요구할 때, 또는 스타터의 설정 표면을 손볼 때.

---

## PF-2 — 백그라운드 `Task` 호출도 전용 허가를 잠깐 쓴다 · **열림**

**무엇을.** `Task(run_in_background=true)` 호출은 전용 스레드 대신 호출 스레드에서 돌게 한다 — 호출별 판정(`ToolUse` 를 보는
선언)이 필요하다.

**왜.** `TaskTool` 의 `DEDICATED_THREAD` 선언은 정적이라, 즉시 반환하는 백그라운드 launch 도 허가 하나를 잠깐 쥔다. 두 가지 결과가
있다. ① 백그라운드 launch 의 `SubagentStart` 가 **입력 순서로 찍힌다는 보장**이 사라진다 — `DefaultSubagentExecutionManager.executeInBackground`
가 Start 를 launch 스레드에서 미리 쏘는 이유가 그 순서였다. ② 한 배치에 백그라운드 launch 가 몰리면 같은 배치의 포그라운드
`Task` 가 허가를 못 얻어 inline lane 으로 밀린다(교착은 없다). 설계 §8 Q2 는 비용이 작다고 보고 정적 선언을 택했고, 가이드 §9
제한사항에 적었다.

**어디** *(2026-10-20 확인)* — `tools/task/TaskTool.java` 의 `getConcurrencyBehavior()`,
`agent/tool/DedicatedThreadDispatcher.java` 의 `isDedicated`.

**언제 다시 볼까.** 백그라운드 launch 의 Start 순서에 기대는 소비자(CLI 외)가 생길 때, 또는 한 배치에 백그라운드 `Task` 를
허가 수보다 많이 띄우는 사용이 관측될 때.

---

## PF-3 — `runForeground` 가 돌아온 뒤의 늦은 interrupt 창 · **열림**

**무엇을.** 도구가 돌아온 뒤 PostTool 훅 전에 실행 스레드의 인터럽트 플래그를 소비하고 신호로 승격할지 정한다
([`../design/agent-execution/interrupt.md`](../design/agent-execution/interrupt.md) §8 의 변경).

**왜.** `DefaultTerminatorRegistrar.fireAll` 은 terminator 를 lock 밖에서 부르고 `unregister` 는 이미 발화했으면 그냥 돌아가므로,
`Task` 가 돌아온 뒤에도 interrupt 가 도착할 수 있다. 전용 스레드에서는 그 interrupt 가 스레드와 함께 사라지지만, 그 전에 같은
스레드에서 도는 것이 둘 있다 — `SingleToolInvoker` 의 PostTool 훅과 디스패처의 `onCompleted`(`emitToolResultReady`). 플래그가 그
사이에 서면 `DefaultHookExecutor` 의 fail-open 이 PostTool 을 건너뛸 수 있다. **이 창은 오늘 순차 경로의 부모 스레드에도 똑같이
있고** 전용 스레드가 넓히지 않았다(설계 §6.7 잔여 창, §8 Q9).

**어디** *(2026-10-20 확인)* — `agent/interrupt/DefaultTerminatorRegistrar.java` 의 `fireAll` · `unregister`,
`toolinvocation/SingleToolInvoker.java` 의 PostTool 발화부, `tools/task/TaskTool.java` 의 `runForeground`.

**언제 다시 볼까.** PostTool 훅이 취소된 `Task` 뒤에 건너뛰어졌다는 보고가 있을 때, 또는 `interrupt.md` §8 을 다시 열 때.

---

## PF-4 — PermissionRequest 훅의 `ASK` 는 직렬화되지만 중복 제거되지 않는다 · **열림**

**무엇을.** PermissionRequest 훅이 `ASK` 를 돌려줄 때도 같은 질문을 동시에 한 번만 묻게 할지 정한다.

**왜.** 승인 게이트(`SideEffectApprovalGate`)는 (스코프 × 도구) 단위로 진행 중인 질문을 공유해 같은 질문을 한 번만 묻는다.
PermissionRequest 훅의 `ASK` 는 그 게이트를 지나지 않고 `AskPromptHandler` 로 바로 간다. CLI 핸들러가 콘솔을 한 번에 하나씩
쓰게 되었으므로 프롬프트가 섞이지는 않지만, 병렬 포크 N 개가 같은 `ASK` 를 내면 사용자는 같은 질문에 N 번 답한다. `ASK` 의
답은 승인 게이트처럼 기억되지도 않으므로 "같은 질문" 의 단위부터 정해야 한다(설계 §9.1 의 2).

**어디** *(2026-10-20 확인)* — `hook/DefaultHookExecutionManager.java` 의 `AskPromptHandler` 사용부,
`modules/aimon-cli/src/main/java/at/aimon/cli/hook/InteractiveAskPromptHandler.java`.

**언제 다시 볼까.** `ASK` 를 내는 PermissionRequest 훅을 병렬 포그라운드 `Task` 와 함께 쓰는 배포가 생길 때.

---

## PF-5 — 부모 수준 승인 대기자는 부모 취소에 깨어나지 않는다 · **열림**

**무엇을.** 같은 승인 질문을 기다리는 호출자를 부모 취소가 깨우게 할지 정한다 — 예를 들어 대기를 턴의 취소 신호에도 묶는다.

**왜.** 병렬 `Task` 셋이 같은 세션에서 `Allow 'Task(...)'?` 를 동시에 물으면 하나가 묻고 둘이 그 답을 기다린다. 기다리는 둘의 전용
스레드에는 아직 terminator 가 없다 — 게이트는 도구 실행 **전에** 돌고 `runForeground` 는 실행 중에야 terminator 를 등록한다. 그래서
부모가 취소되어도 둘은 묻던 쪽이 답을 받을 때까지 깨지 않는다. 묻던 쪽은 오늘처럼 `console.readLine()` 에 막혀 있으므로 턴이
더 오래 걸리지는 않지만, 취소가 사람의 답을 기다린다는 사실은 그대로다. 포크 **안의** 대기자는 그 스레드에 포크의 terminator 가
걸려 있어 깨어난다(설계 §9.1 의 4).

**어디** *(2026-10-20 확인)* — `toolinvocation/approval/SideEffectApprovalGate.java` 의 `awaitShared`,
`toolinvocation/SingleToolInvoker.java` 의 게이트 호출부.

**언제 다시 볼까.** 대화형이 아닌 `AskPromptHandler`(사람 대신 원격 승인 채널)가 생겨 묻던 쪽이 취소에 반응할 수 있게 될 때 —
그때는 대기자만 매달리는 것이 실제 지연이 된다.

---

## PF-6 — `LlmCallMetadataHolder` 의 ambient 태그는 전용 스레드에서 보이지 않는다 · **열림**

**무엇을.** 턴 스레드의 `ThreadLocal` 에 밀어 넣은 LLM 호출 태그를 전용 스레드 · 풀 워커 · 백그라운드 포크로 옮길지, 아니면 그 API 의
javadoc 에 "호출 스레드에서만 보인다" 를 적을지 정한다.

**왜.** 트레이싱 부모는 값으로 전달되어 스레드와 무관하다(설계 §6.3). 코드베이스에서 유일한 metadata `ThreadLocal` 인
`LlmCallMetadataHolder` 는 두 실행기 어느 쪽도 쓰지 않지만, 임베더가 그것으로 턴 스레드에 태그를 밀어 넣었다면 전용 스레드에서 도는
포크의 LLM 호출은 그 태그를 보지 못한다. 풀 경로와 백그라운드 포크에 **이미 있는 같은 공백**이고 이번 변경이 그 범위를 넓혔다.

**어디** *(2026-10-20 확인)* — `llm/tagging/LlmCallMetadataHolder.java`.

**언제 다시 볼까.** 임베더가 `LlmCallMetadataHolder` 로 태그를 넣는 사용이 확인될 때.
