# 멀티 인스턴스 준비도가 축마다 다르다 — 등록 항목 3건 (열림 2 · 해소 1)

`CLAUDE.md` 의 설계 원칙 — *"상태를 가진 컴포넌트는 저장소를 인터페이스로 분리하여 멀티
인스턴스(스케일아웃) 환경에서도 동작할 수 있도록 설계한다. 기본 구현은 in-memory 로 제공하되,
저장소 교체가 리팩토링이 아니라 구현체 교체로 가능해야 한다."*

이 원칙은 **이음매**(SPI)와 **길**(스택에 꽂는 자리) 둘 다를 요구한다. 아키텍처 리뷰에서
전자는 전부 지켜져 있고 후자가 축마다 다르다는 것이 나왔다.

출처는 설계 문서가 아니라 **2026-08-31 의 아키텍처 리뷰**다. 그래서 이 문서에는 대응하는
`design/` 문서가 없고, 규칙 하나("설계 문서의 표는 정본이 아니다")가 적용될 표도 없다.

---

## 0. 착수하며 정정한 것 — 등록하려던 항목 하나가 **거짓 전제 위에 있었다**

원래 등록하려던 항목은 이랬다: *"승인·보류턴 축이 인메모리뿐인데 세션은 sticky routing 없이
노드를 옮긴다. 그런데 그 사실이 어디에도 적혀 있지 않아 침묵이 '괜찮다' 로 읽힌다."*

**뒷문장이 거짓이다.** 적혀 있다. 두 자리에, 두 독자를 향해 — 조립이 **기동 시 운영자에게
degradation** 으로 말하고, SPI javadoc 이 **그 SPI 를 구현하려는 사람에게** 말한다. 아래는 앞의
것이고, 뒤의 것은 이 절 뒤쪽 표에 있다.

`AimonStackBuilder.java:295-303` (2026-08-31 확인):

```java
if (spec.getSession().getMode() == DeploymentMode.DISTRIBUTED) {
    // Distributing sessions does not distribute everything keyed by one. Both of these fail closed —
    // an approval is re-asked, a suspended turn expires — so the consequence is friction rather than an
    // escalation, which is exactly why it would otherwise go unnoticed.
    degradations.add("distributed-approvals",
            "Skill approvals and suspended turns stay on the node that produced them. After a session moves"
                    + " to another node, a skill approved for that session asks again, and an /approve for a"
                    + " turn suspended elsewhere finds nothing to release.");
}
```

이 여덟 줄이 항목이 말하려던 것을 **전부**, 그리고 더 정확하게 말한다 — 실패 방향이
fail-closed 라는 것과, 그래서 결과가 권한 상승이 아니라 마찰이라는 것까지.

**두 번째 자리 — SPI javadoc.** 처음에는 "조립에는 있지만 SPI 에는 없다" 로 정정문을 썼는데,
그것도 틀렸다. 여섯 개를 실제로 세어 보니 다섯이 이미 적고 있다.

| SPI | 멀티 인스턴스 문장 | 문구 |
|-----|-------------------|------|
| `SessionApprovalStore` | **있음** (`:46-48`) | *"therefore node-local; … or accept that a session which moves nodes re-prompts"* |
| `AgentApprovalStore` | **있음** (`:44-46`) | *"deployments running multiple aimon instances should provide a shared backing store"* |
| `PendingTurnRegistry` | **있음** | 같은 문구 |
| `MessageQueueRepository` | **있음** (`:17-19`) | *"the swap-point between the default in-memory backend and future distributed backends"* |
| `PendingApprovalStore` | 없음 — **불필요** | javadoc 이 대신 *"Not yet wired. Nothing in production writes to or reads from this store today."* 라고 적는다 |
| `ToolApprovalStore` | **없었음** | 이번에 추가했다 (아래) |

**어떻게 틀렸나가 교훈이다.** 처음 확인은 두 번 다 도구가 만들어 낸 답이었다(규칙 여섯).
첫 번째는 `InMemory*` **구현 파일**에 grep 했다 — 문장은 인터페이스에 있었다. 두 번째는 인터페이스를
열었지만 **앞 35줄만** 읽었고, `SessionApprovalStore` 의 그 문장은 46번째 줄에 있었다. 세 번째로
`multi-node|multi-instance|node-local|shared backing store` 를 네 갈래로 훑고서야
`MessageQueueRepository` 가 **다른 어휘로** 같은 말을 하고 있다는 것이 보였다 — "swap-point" 는
네 갈래 중 어디에도 없다.

그러므로 규칙 여섯의 "N건도 도구가 만들어 낸 숫자" 에 한 줄이 붙는다: **부재(0건)를 근거로 항목을
등록할 때는 같은 뜻의 다른 어휘를 먼저 센다.** 존재는 한 번 맞히면 참이지만 부재는 검색어가 좁을수록
그럴듯해진다.

**실제로 빠져 있던 것은 하나다** — `ToolApprovalStore`. `SideEffectApprovalGate` 가 세션으로
키잉하므로 승인 스토어들과 같은 성질인데 그 문장만 없었다. 이번에 추가하면서 degradation 을
**가리키게** 썼다(다시 서술하면 두 자리가 어긋난다).

남은 항목 M-1 은 이 정정을 전부 통과하고 살아남은 것이다 — **문서가 아니라 이음매**의 문제다.

---

## 1. 축별 준비도 — 무엇이 실제로 분산 가능한가

2026-08-31 기준. "분산 구현" 은 이 저장소 안의 out-of-process 백엔드를 뜻한다.

| 축 | SPI | 분산 구현 | 스택에 꽂는 길 |
|----|-----|----------|---------------|
| 세션 레코드·리스·인박스·신호·멱등 | `SessionRecordStore` · `SessionLeaseStore` · `SessionInbox` · `SessionSignalBus` · `IdempotencyStore` | **Redis · Postgres · Mongo** | `SessionSpec` |
| 배경 작업 | `BackgroundTaskStore` | **Redis · Postgres · Mongo** | `SessionSpec` |
| 메모리 | `ObservationStore` · `RepresentationStore` · `WorkspaceStore` · `DerivationQueueManager` | **File · Postgres · Mongo** | `MemorySpec` |
| 지식 | `KnowledgeStore` | **OpenSearch** | `AimonStackSpec.getKnowledgeStore` |
| 자격증명 | `CredentialStore` | 없음 | `AimonStackSpec.getCredentialStore` |
| 스케줄 저장소·가드 | `ScheduledTaskRepository` · `ScheduledExecutionGuard` · `ScheduledTaskInterruptBus` | 없음 — **B-7** | `SchedulingSpec` (B-34 가 뚫었다) |
| 서브에이전트 산출물 | `TaskOutputStore` · `TaskResultStore` · `SessionSnapshotStore` | **VFS 경유** (GridFS · S3) | 런타임 팩토리 |
| **승인 · 보류턴 · 메시지 큐** | `AgentApprovalStore` · `SessionApprovalStore` · `PendingTurnRegistry` · `MessageQueueRepository` | 없음 | **없음 — M-1** |

마지막 줄만 두 칸이 다 비어 있다. 그리고 **두 칸이 빈 이유가 서로 다르다** — 구현이 없는 것은
소비자가 없어서이고(B-7 과 같은 트리거 대기), 길이 없는 것은 아무도 그 자리를 만들지 않아서다.
둘을 한 항목으로 세면 안 되는 이유가 B-34 에 이미 적혀 있다.

---

## 2. 열린 항목

### M-1 — 승인·보류턴 저장소를 스택에 건넬 길이 없다 · **열림**

**무엇** — `AimonStackSpec` / `SkillApprovalSpec` 에 `AgentApprovalStore` · `SessionApprovalStore` ·
`PendingTurnRegistry` · `MessageQueueRepository` 를 받는 자리를 만든다.

**왜** — 지금은 `AimonStackBuilder` 가 네 개를 **하드코딩**한다. 즉 누군가
`RedisSessionApprovalStore` 를 써도 `AimonStack` 위에서 쓸 방법이 없다. §1 의 다른 축들은
전부 `Optional<...>` 이음매를 갖고 있으므로, 이것은 설계 방침이 아니라 **빠진 자리**다.

**어디** (2026-08-31 확인)

| 하드코딩 | 줄 |
|---------|-----|
| `new InMemoryAgentApprovalStore()` | `AimonStackBuilder.java:280` |
| `new InMemorySessionApprovalStore()` | `AimonStackBuilder.java:281` |
| `new InMemoryPendingTurnRegistry()` | `AimonStackBuilder.java:289` |
| `new InMemoryMessageQueueRepository()` | `AimonStackBuilder.java:275` |

대조군이 같은 파일 옆자리에 있다. `SchedulingSpec` 의 접근자 5개는 **전부**
`Optional<TaskScheduler>` · `Optional<ScheduledTaskRepository>` · `Optional<ScheduledExecutionGuard>` …
꼴이다. `SkillApprovalSpec` 의 접근자 8개는 `getDefaultDecision` · `getChannelMode` ·
`getSuppliedChannel` · `getPendingTurnTtl` … — **채널과 정책 손잡이뿐이고 저장소는 하나도 없다.**
스타터에도 `ObjectProvider<AgentApprovalStore>` 류는 0건이다.

**이것은 B-34 와 같은 모양이다.** B-34 는 *"B-7 의 해소 조건('영속 구현이 생기는 시점')이 와도
그 구현을 스택에 꽂을 자리가 없다"* 였고 스케줄링 축에서 닫혔다. 승인 축은 같은 진단이 아직
열려 있다. **같은 모양이 두 축에서 났다는 것이 이 항목의 실제 값**이다 — 다음에 새 축을 만들 때
물어야 할 것은 "SPI 가 있는가" 가 아니라 **"그 SPI 의 다른 구현을 조립에 건넬 수 있는가"** 다.

**언제 다시 볼까** — 트리거 두 개 중 하나면 된다.
- 분산 승인 저장소를 실제로 쓰려는 소비자가 생길 때
- **또는 §1 표에 새 축이 추가될 때** — 그때 이 항목이 체크리스트가 된다

이것은 M-2 와 달리 **소비자를 기다리지 않아도 착수할 수 있다.** B-34 가 그랬듯 길을 먼저
뚫는 것은 구현을 기다릴 필요가 없다. 다만 지금 뚫으면 아무도 안 쓰는 이음매가 생기므로, 두
트리거 중 앞의 것이 먼저 오는 쪽이 낫다.

### M-2 — 승인·보류턴 축의 분산 구현이 없다 · **열림 · 트리거 대기**

**무엇** — `SessionApprovalStore` 등의 out-of-process 구현.

**왜** — §0 이 인용한 degradation 이 말하는 마찰이 실제로 아프면 필요해진다.

**왜 지금 하지 않나** — B-7 과 같은 이유이고, 이쪽이 근거가 하나 더 있다. **실패가 fail-closed
다** — 승인은 다시 물어보고 보류턴은 만료된다. 즉 지금 상태로 배포해도 권한이 넘어가지 않으며,
degradation 이 기동 시 그렇게 말한다. 소비자 없이 만들면 검증되지 않은 이음매가 하나 늘 뿐이다.

**언제 다시 볼까** — 분산 모드로 실제 운영하는 앱이 "승인을 자꾸 다시 묻는다" 를 보고할 때.
그 앱은 아직 없다(`roadmap.md` §3 — *"aimon-core 밖에서 온 백엔드 구현 0건"*).

**전제 확인** (규칙 여섯) — `InMemoryPendingApprovalStore` 는 이 항목에 **넣지 않았다.**
main 소스 생성 지점이 **0건**이기 때문이다(`new` · `::` · 팩토리 전부 확인, 2026-08-31).
그것은 근거가 아니라 관측이다. 그리고 이 건에서는 인구조사가 필요하지도 않았다 — 그 타입의
javadoc 이 스스로 *"Not yet wired. Nothing in production writes to or reads from this store today."*
라고 적고 있다. **규칙 여섯을 적용하기 전에 그 타입이 자기에 대해 뭐라고 적어 두었는지 읽으면
세는 수고가 없어질 때가 있다.**

---

## 3. 해소

### M-3 — "이 갭이 어디에도 기록되어 있지 않다" · **해소**

기록되어 있다. 두 겹으로 — `AimonStackBuilder.java:295-303` 의 `distributed-approvals`
degradation 이 기동 시 운영자에게 말하고, SPI javadoc 여섯 중 다섯이 각자 적고 있다.
빠져 있던 하나(`ToolApprovalStore`)는 이번에 채웠다. 전문과 세 번에 걸친 정정 경위는 §0.

**고친 적이 없으므로 닫힘이 아니라 해소다.** 닫힘으로 세면 다음 사람이 "기록을 추가했다" 로
읽는데, 사실은 **처음부터 있었다.** 두 문장은 다음 사람이 이 자리를 볼 때 완전히 다른 것을
하게 만든다(`README.md` 의 "해소 칸이 따로 있는 이유").

---

## 4. 관련

- [`spring-boot-starter-open-items.md`](spring-boot-starter-open-items.md) — **B-7**(스케줄 저장소
  영속 구현 없음) · **B-34**(길이 없었고 뚫었음). M-1 은 B-34 의 승인 축 판이고, M-2 는 B-7 의
  승인 축 판이다
- [`module-dependency-scope.md`](module-dependency-scope.md) — 같은 리뷰에서 나온 다른 항목
- [`../overview/scope-model.md`](../overview/scope-model.md) — 어떤 값이 어느 수명에 속하는가
- [`../design/session/routing.md`](../design/session/routing.md) — sticky routing 없이 노드를
  옮긴다는 전제의 출처
