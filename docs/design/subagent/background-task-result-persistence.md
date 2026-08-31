# 백그라운드 태스크 결과 영속화 (Background Task Result Persistence)

> Status: **IMPLEMENTED** — 결과 저장소·코덱·대기 의미·크기 정책 넷이 한 세트로 들어갔고,
> 노드 로컬 퓨처 홀더(`BackgroundTaskManager`)와 그것을 노출하던 SPI 는 삭제되었다.
> 남은 것은 분산 `TaskResultStore` 백엔드 — 지금은 in-memory 와 VFS 둘뿐이다 (§8).
>
> 적용 대상: `aimon-core` — `at.aimon.core.subagent.task`(`TaskResult`, `TaskResultStore`,
> `InMemoryTaskResultStore`, `VfsTaskResultStore`), `…subagent.task.codec`(`TaskResultCodec`,
> `JsonTaskResultCodec`), `at.aimon.core.subagent`(`DefaultSubagentExecutionManager` 의 저장 지점),
> `at.aimon.core.tools.task`(`AgentOutputTool` 의 읽기 경로) ·
> 자매 문서: [`execution.md`](execution.md) (태스크 수명·출력 로그·resume 전반)

---

## 1. 문제 — 표면이 두 반쪽으로 갈려 있었다

백그라운드 태스크에 대해 물을 수 있는 것은 두 종류인데, 둘이 서로 다른 곳에 살고 있었다.

| 반쪽 | 무엇을 담나 | 어디에 살았나 | 크로스 노드 |
|---|---|---|---|
| **lifecycle** | `BackgroundTask` — taskId · subagent 이름 · 상태 · 시각 · owner · `agentRuntimeId` · heartbeat · `outputOffset` | `BackgroundTaskStore` (영속) | 됐다 |
| **result** | 태스크가 실제로 만들어 낸 것 | `BackgroundTaskManager` 의 **노드 로컬 `CompletableFuture` 맵** | **안 됐다** |

증분 출력은 이미 `TaskOutputStore` 가 담고 있었고, 최종 결과만 갈 곳이 없어 프로세스 힙에 남았다.
그래서 세 가지가 따라왔다.

- 다른 노드에서 시작된 태스크는 `list` 로 보이고 `stop` 도 되는데 **결과만 못 받았다** — 관측 표면이
  노드 경계에서 갈렸다
- **재시작하면 결과가 사라졌다.** 태스크는 완료로 남는데 산출물은 없었다
- `SubagentExecutionManager.getBackgroundTaskManager()` 가 **노드 로컬 구현 세부를 인터페이스로
  노출**했다. 나머지 SPI 는 전부 저장소 뒤에 있는데 이 하나만 힙의 퓨처를 가리켰다

한 가지 더 있었다. 그 맵은 **비워지지 않았다** — 완료된 태스크의 `SubagentExecutionResult` 를
프로세스가 사는 동안 계속 붙들었고, 결과에는 전사(`SessionSnapshot`)가 통째로 매달려 있었다.

---

## 2. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|---|---|---|
| 결과를 어디에 두나 | **별도 `TaskResultStore`** — `taskId` 로 키잉되는 네 번째 저장소 | ① `BackgroundTask` 에 필드 추가: 목록 조회(`TaskList`)가 결과 본문까지 끌고 오게 되고, 레코드 비대화를 저장소 하나에 몰아넣는다. ② `TaskOutputStore` 에 합치기: 그쪽은 append-only 오프셋 로그이고 이쪽은 last-write-wins 단일 문서다 — 읽기 패턴도 수명도 다르다 |
| 무엇을 저장하나 | `SubagentExecutionResult` 가 아니라 **투영 타입 `TaskResult`** | 결과를 그대로 담으면 전사가 딸려 들어가는데, 그 전사는 이미 같은 `taskId` 로 `SessionSnapshotStore` 에 있다. 이중 저장이자 §1이 지적한 비대화의 재현이다. 선례는 세션 레코드 쪽 `StoredAgentExecutionResult` |
| 소유자 태그를 넣나 | **넣지 않는다** — 인가는 한 층 위 `ScopedSubagentTaskController` 가 한다 | 저장소마다 소유자 검사를 중복하면 검사 지점이 늘어나고, 그중 하나만 빠져도 격리가 뚫린다. 형제 저장소 `TaskOutputStore` 도 태그가 없다 |
| 결과와 터미널 상태의 순서 | **결과를 먼저 저장하고 그다음 전이**한다 (§3) | 반대 순서면 읽는 쪽이 "결과가 아직 안 왔다"와 "결과가 없다"를 구분할 수 없다 |
| `block=true` 의 의미 | 스코프된 컨트롤러에 대한 **바운드 폴링** (§4) | 시그널 버스 대기: 백그라운드 태스크는 세션이 아니라 `SessionSignalBus` 의 대상이 아니고, §3의 순서 계약이 있으면 폴링만으로 정확하다 |
| 크기 정책 | 저장 상한 `TaskResult.DEFAULT_MAX_SUMMARY_CHARS`(128,000), tail-keep, `summaryTruncated` 플래그 (§5) | 무제한 저장: 저장소가 결과 하나로 부풀 수 있다. 인라인 상한(32,000)과 동일하게: 저장이 인라인보다 좁으면 나중에 상한을 올려도 잃은 텍스트가 돌아오지 않는다 |
| 옛 SPI 처리 | `getBackgroundTaskManager()` 와 **`BackgroundTaskManager` 자체를 삭제** | 접근자만 지우기: 접근자가 없어지면 그 클래스의 완료 결과 맵은 아무도 읽지 않는 순수 누수가 된다 |

---

## 3. 순서 계약 — 결과가 먼저, 터미널 상태가 나중

이 설계에서 **깨지면 안 되는 것은 하나**다.

```
saveTaskResult(taskId, result)          ← 먼저
taskStore.transition(taskId, TERMINAL)  ← 나중
```

터미널 상태는 곧 **결과가 읽을 수 있다는 신호**다. 이 순서가 성립하면 읽는 쪽의 판단이 단순해진다 —
터미널인데 결과가 없으면 그것은 "아직"이 아니라 "없음"이다. 그래서 `AgentOutput` 의 폴링 루프는
매 바퀴 **상태를 먼저 읽고 결과를 나중에** 로드한다. 반대로 하면 1마이크로초 차이로 빗나간 load 뒤에
터미널 상태를 보게 되어, 결과를 낸 태스크가 아무것도 내지 않은 것처럼 보인다.

지키는 지점은 둘이고 **둘 다** 지켜야 한다.

| 경로 | 저장 지점 |
|---|---|
| 정상 종료 (`COMPLETED` / `FAILED` / `KILLED`) | `finalizeBackgroundTask` try 블록의 첫 문장 |
| 풀 포화 거절 (`RejectedExecutionException`) | `FAILED` 전이 직전 — 이 경로는 finalizer 를 아예 거치지 않으므로 자기가 직접 지킨다 |

`DefaultSubagentExecutionManagerBackgroundTest` 가 태스크 저장소를 감싼 프로브로 **터미널 전이가
발행되는 그 순간** 결과가 이미 로드 가능했는지를 두 경로 모두에 대해 단언한다.

이것은 [`execution.md` §3](execution.md) 의 "전이는 상태를 먼저 쓴다"와 충돌하지 않는다. 그쪽은
전이와 **부수효과**(알림·큐 적재)의 순서이고, 여기는 전이와 **결과 저장**의 순서다. 결과 저장은
부수효과가 아니라 전이가 발행하는 사실의 일부다.

---

## 4. `block=true` 의 재정의

옛 의미는 노드 로컬 퓨처에 대한 `join` 이었다. 지금은 스코프된 `SubagentTaskController.status` 에 대한
바운드 폴링이다 — `POLL_INTERVAL_MILLIS`(500ms), 데드라인은 `wait_up_to`(기본 150초), 남은 시간이
더 짧으면 그만큼만 잔다.

동작이 **조용히** 달라지지 않도록 바뀐 자리를 명시한다.

| 상황 | 옛 동작 | 지금 |
|---|---|---|
| `wait_up_to <= 0` | 무기한 `join` | 단일 폴링으로 강등 |
| 대기 중 스레드 인터럽트 | 퓨처가 삼킴 | 인터럽트 플래그를 복원하고 "아직 실행 중"으로 반환 |
| 대기 중 태스크 축출 | 퓨처는 살아 있음 | not-found — 애초에 없던 것과 구분 불가 |
| 터미널인데 결과 없음 | 있을 수 없었음 | 상태와 이유를 문장으로 반환 (아래) |

마지막 칸은 두 경우를 **일부러 갈라 놓았다**. 저장소가 배선되지 않은 것은 설정 사실이고
("result retention is not configured"), 배선된 저장소에서 결과가 없는 것은 태스크가 정말 아무것도
내지 않았거나 축출된 것이다. 출력 저장소가 있으면 진행 로그를 읽으라는 포인터를 덧붙인다.

폴링 간격 500ms 는 의도적으로 굵다. 기다리는 에이전트는 어차피 놀고 있고, 더 촘촘한 루프는 같은
태스크를 기다리는 에이전트 수만큼 공유 백엔드에 대한 읽기를 곱한다.

---

## 5. 크기 정책 — 상한이 둘이고 저장 쪽이 더 넓다

| 상한 | 값 | 어디에 적용되나 |
|---|---|---|
| **저장** `TaskResult.DEFAULT_MAX_SUMMARY_CHARS` | 128,000 | 결과가 저장소에 들어갈 때 |
| **인라인** `SubagentResultFormatter.DEFAULT_MAX_CHARS` | 32,000 | 그 결과가 부모 트랜스크립트에 실릴 때 |

**저장 상한이 인라인 상한보다 넓은 것이 핵심**이다. 좁으면 인라인 경로가 보여 줬을 텍스트를 저장
단계에서 이미 잃어버리게 되고, 나중에 인라인 상한을 올려도 돌아오지 않는다. 넷의 관계는 단방향이다 —
영속화는 절대 표시보다 먼저 버리지 않는다. `TaskResultTest` 가 두 상수의 대소를 단언한다.

절단은 [`execution.md` §6](execution.md) 과 같은 **tail-keep** 이다. ReAct 실행의 결론은 끝에 있으므로
뒤를 남기고, 잘린 자리에는 얼마나 잘렸는지를 남긴다. 절단이 일어났다는 사실 자체는
`summaryTruncated` 플래그로 결과에 함께 저장된다 — 읽는 쪽이 문자열을 되짚어 추측하지 않게 하기
위해서다.

`TaskResult` 가 **버리는 것**은 둘이다: 전사(`SessionSnapshot` — 같은 `taskId` 로 `SessionSnapshotStore`
에 이미 있다)와 비용(`Money`). 남기는 것은 성공 여부 · 최종 답 또는 에러 · `CompletionReason` ·
iteration 수 · 소요 시간 · 토큰 수 · 절단 플래그다 — `ExecutionMetadata` 에서는 이 셋만 평평하게
펴서 들고, `startTime`/`endTime` 은 `BackgroundTask` 가 이미 갖고 있으므로 다시 담지 않는다.

---

## 6. 코덱 — 평평한 버전 문서

`TaskResultCodec` / `JsonTaskResultCodec` 은 `SessionSnapshotCodec` 계열과 같은 모양이다 —
`FORMAT_VERSION = 1`, 스테이트리스, 실패 시 `TaskResultCodecException`. null 텍스트 필드는 `null` 로
쓰지 않고 **아예 빼서** 쓴다.

한 군데만 자매 코덱과 다르게 정했다. **모르는 `CompletionReason` 이름은 치명적이지 않다** — 성공이면
`COMPLETED`, 실패면 `ERROR` 로 낮춰 읽고 답 본문은 살린다. 새 노드가 이 빌드가 모르는 정지 사유를
붙여 저장했을 때, 라벨 하나 때문에 결과 전체를 버리는 것이 더 나쁘기 때문이다. 반면 **버전 불일치와
비객체 문서는 거부**한다 — 그쪽은 문서 구조 자체를 신뢰할 수 없다는 뜻이다.

`VfsTaskResultStore` 는 코덱 출력을 **봉투 없이** 그대로 쓴다 (`.aimon/task-result/<taskId>.json`).
`VfsSessionSnapshotStore` 의 envelope 은 소유자 태그(`contextId`)를 싣기 위해 있는 것인데, 이 저장소는
태그가 없으므로(§2) 실을 것이 없다.

---

## 7. 하지 말 것

- **터미널 전이를 먼저 하고 결과를 나중에 저장하지 말 것.** §3 이 무너지면 `AgentOutput` 의 폴링이
  "결과 없음"과 "아직 안 옴"을 구분하지 못한다. 새 종료 경로를 추가한다면 저장 지점을 그 경로에도
  **직접** 넣어야 한다 — 풀 거절 경로가 finalizer 를 거치지 않는 것이 그 실례다
- **`AgentOutput` 의 폴링에서 결과를 먼저 읽지 말 것.** 순서가 뒤집히면 계약이 있어도 경합한다
- **`TaskResultStore` 에 소유자 검사를 옮겨 넣지 말 것.** 인가는 컨트롤러 한 곳이다. 두 곳이 되면
  한 곳만 고치는 사고가 생긴다
- **저장 상한을 인라인 상한 이하로 내리지 말 것** (§5)
- **`TaskResult` 에 전사를 도로 넣지 말 것.** 같은 것을 두 저장소에 담는 순간 §1 의 비대화가
  새 자리에서 반복된다. resume 이 필요하면 `SessionSnapshotStore` 를 본다
- **결과 저장 실패로 태스크를 실패시키지 말 것.** best-effort 다 — 저장소가 던지면 로그만 남기고
  태스크의 터미널 상태는 그대로 발행한다

---

## 8. 남은 것

- **분산 백엔드.** 지금 `TaskResultStore` 구현은 `InMemoryTaskResultStore`(LRU 256)와
  `VfsTaskResultStore` 둘이다. VFS 가 공유 스토리지(GridFS·S3)를 가리키면 크로스 노드 조회가 이미
  성립하지만, `BackgroundTaskStore` 처럼 Redis·Mongo·Postgres 전용 구현을 두는 편이 자연스러운
  배치도 있다
- **터미널 보관 정책의 정렬.** `InMemoryBackgroundTaskStore` 는 터미널 태스크를 1000개까지, 결과
  저장소는 256개까지 든다. 둘이 어긋나므로 "태스크는 보이는데 결과는 축출됨"이 in-memory 배치에서
  나올 수 있다 — §4 의 마지막 칸이 그 경우를 문장으로 설명하지만, 두 상한을 맞추는 것은 별개 변경이다
- **`evict` 를 부르는 곳이 아직 없다.** SPI 에는 있지만 main 소스의 호출 지점이 0이다. in-memory 는
  LRU 가 대신 막아 주므로 무해하지만, `VfsTaskResultStore` 는 결과 객체가 백엔드에 무한정 쌓인다.
  형제인 `SessionSnapshotStore.evict` 도 같은 상태이므로 둘을 함께 태스크 보관 정책에 묶는 것이 맞다

---

## 부록 — 참조 파일 지도

| 항목 | 파일 |
|---|---|
| 투영 타입과 두 상한 | `subagent/task/TaskResult.java` (`DEFAULT_MAX_SUMMARY_CHARS`, `from`) |
| 저장소 계약 (순서·비태깅·best-effort) | `subagent/task/TaskResultStore.java` |
| 구현 | `subagent/task/InMemoryTaskResultStore.java` (LRU 256), `subagent/task/VfsTaskResultStore.java` (`.aimon/task-result`) |
| 코덱 | `subagent/task/codec/{TaskResultCodec,JsonTaskResultCodec,TaskResultCodecException}.java` |
| 저장 지점 (순서 계약) | `subagent/DefaultSubagentExecutionManager.java` (`saveTaskResult`, `finalizeBackgroundTask`, 풀 거절 경로) |
| 터미널 상태 = 준비 신호 | `subagent/task/BackgroundTaskState.java` (`isTerminal` javadoc) |
| 읽기 경로 (폴링·정착 렌더링) | `tools/task/AgentOutputTool.java` (`awaitSettled`, `settled`, `POLL_INTERVAL_MILLIS`) |
| 배선 | `agent/impl/orca/OrcaAgentRuntimeFactory.java` (`withTaskResultStoreFactory`, `withDistributedTaskResultStore`), `agent/impl/orca/tool/OrcaSubagentToolProvider.java`, `tools/task/TaskTool.java` |
| 순서 계약 테스트 | `subagent/DefaultSubagentExecutionManagerBackgroundTest.java` (`TerminalOrderingProbe`) |

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준 (테스트는 `src/test/java/at/aimon/core/`).

---

## 관련 문서

- [`execution.md`](execution.md) — 태스크 수명·출력 로그·resume·격리 전반
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명·소유권 규칙
- [`../session/session-model.md`](../session/session-model.md) — `StoredAgentExecutionResult` 투영의 선례
