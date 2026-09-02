# 멀티 인스턴스 준비도가 축마다 다르다 — 등록 항목 3건 (열림 1 · 닫힘 1 · 해소 1)

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

> **이 블록은 그 뒤에 바뀌었다 (2026-09-02, M-1).** 위 인용은 2026-08-31 의 기록으로 남긴다.
> 무조건 발화하던 것이 **아직 노드 로컬로 남은 저장소만** 세도록 좁혀졌고, 셋 다 건네받으면
> 아무 말도 하지 않는다. 좁힌 이유는 M-1 이 만든 것 때문이다 — 건넬 길이 없던 동안에는 위
> 문장이 항상 참이었지만, 길이 생긴 뒤로는 공유 저장소를 넣은 배포에게 거짓말이 된다.

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
| **승인 · 보류턴 · 메시지 큐** | `AgentApprovalStore` · `SessionApprovalStore` · `PendingTurnRegistry` · `MessageQueueRepository` | 없음 — **M-2** | `SkillApprovalSpec` · `AimonStackSpec` (M-1 이 뚫었다) |

마지막 줄만 두 칸이 다 비어 있었다. 그리고 **두 칸이 빈 이유가 서로 달랐다** — 구현이 없는 것은
소비자가 없어서이고(B-7 과 같은 트리거 대기), 길이 없는 것은 아무도 그 자리를 만들지 않아서다.
둘을 한 항목으로 세면 안 되는 이유가 B-34 에 이미 적혀 있다.

**그 구분이 값을 했다.** 오른쪽 칸은 M-1 로 채워졌고 가운데 칸은 M-2 로 그대로 열려 있다 — 한
항목이었다면 "구현이 없으니 아직 못 한다" 로 한꺼번에 미뤄졌을 것이다.

---

## 2. 항목

### M-1 — 승인·보류턴 저장소를 스택에 건넬 길이 없다 · ✅ **닫힘 (2026-09-02)**

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

#### 착수 (2026-09-02) — 전제는 넷 다 참이었고, **뚫는 것이 절반이었다**

**먼저, 위 문단의 권고를 따르지 않았다.** 그것은 "두 트리거 중 앞의 것(분산 저장소를 쓰려는
소비자)이 먼저 오는 쪽이 낫다" 였는데, 소비자 없이 착수했다. 이유는 아래에서 나온 것이다 —
길을 뚫는 일이 **degradation 을 고치는 일을 포함**하고 있었고, 그쪽은 소비자를 기다릴 이유가
없었다. 뚫기 전에는 그 사실이 보이지 않았으므로 권고가 틀렸다기보다 **범위가 잘못 세어져
있었다.**

규칙 둘대로 소스에서 다시 읽었다. **위 진단은 한 글자도 틀리지 않았다** — 줄 번호 네 개가
그대로 맞았고(`:275` · `:280` · `:281` · `:289`), `SkillApprovalSpec` 의 접근자 8개에 저장소는
없었고, 스타터의 `ObjectProvider<AgentApprovalStore>` 류도 0건이었으며, 이 넷은 트리 전체에서
인메모리 구현 하나씩뿐이었다. 이 문서에서 근거가 무너지지 않은 첫 항목이다.

**만든 것.**

| 자리 | 무엇 |
|------|------|
| `SkillApprovalSpec` | `withAgentApprovalStore` · `withSessionApprovalStore` · `withPendingTurnRegistry` + 대응 `Optional` 접근자 |
| `AimonStackSpec` | `Builder.messageQueueRepository(...)` + `getMessageQueueRepository()` |
| `AimonStackBuilder` | 넷 다 `orElseGet(InMemory...::new)` 로 바뀜 — 빌려온 것이므로 teardown 계획에 올리지 않는다 |
| `AimonAutoConfiguration` | 넷을 애플리케이션 빈으로 받는 입력 이음매 (B-34 가 스케줄링 축에서 한 것과 같은 모양) |

**메시지 큐가 `SkillApprovalSpec` 에 가지 않은 이유.** 항목은 넷을 한 묶음으로 적었지만 배치는
갈렸다. `QueuedInput` 은 `SessionId` 가 아니라 **`AgentRuntimeId` 로 키잉**되고 읽는 쪽이 셋이다
(ReAct 루프의 mid-turn drain, 라이브 세션의 enqueue, 서브에이전트 매니저). 승인 축도 세션 축도
아니므로 `AimonStackSpec` 최상위로 갔다 — `knowledgeStore` · `credentialStore` 와 같은 자리다.
**한 축으로 세는 것과 한 타입에 넣는 것은 다르다.**

#### 그리고 절반은 진단에 없었다 — **길을 뚫자 degradation 이 거짓말을 시작했다**

`distributed-approvals` 는 `DeploymentMode.DISTRIBUTED` 이기만 하면 *"Skill approvals and suspended
turns stay on the node that produced them"* 을 무조건 올리고 있었다. 저장소를 건넬 길이 없던
동안에는 그것이 **항상 참**이었다. 길이 생긴 순간 참이 아니게 된다 — 공유 저장소를 넣은 배포가
"당신의 승인은 이 노드에만 있다" 는 말을 계속 듣는다.

**즉 이 항목의 실제 범위는 "자리를 만든다" 가 아니라 "자리를 만들고, 그 자리가 채워졌을 때
스택이 하는 말을 고친다" 였다.** 그래서 announcement 를 남은 것만 세도록 좁혔고(3 → 2 → 0),
반쯤 설정된 모양을 항목별로 이름 지어 말한다. 공유 레지스트리 + 노드 로컬 승인 저장소는 턴을
찾아내서 **결정을 모르는 노드로 풀어 주는** 조합이라, 한 문장으로 뭉뚱그리면 읽는 사람이 자기가
어느 절반에 있는지 알 수 없다.

**규칙 하나 더 — 재수출과 입력은 같은 타입일 수 없다.** 스타터는 `PendingTurnRegistry` 를
`AimonStack` 에서 **내보내고** 있었다(`mode=suspend` 의 승인 엔드포인트용). 여기에
`ObjectProvider<PendingTurnRegistry>` 입력을 더하자 순환이 닫혔다 — spec → contributions →
재수출 → stack → spec. **애플리케이션이 무엇을 정의했든 무관하게** 모든 앱이
`BeanCurrentlyInCreationException` 으로 기동 실패한다. 추측이 아니라 실측이다: 기존 승인 슬라이스
테스트 10개가 전부 그렇게 깨졌다. 그래서 그 하나만 `ObjectProvider` 가 아니라 빈 **정의** 조회
(`allowEagerInit=false`)로 읽고 재수출을 이름으로 제외한다. 회귀 테스트가 그 자리를 지킨다.

#### 리뷰가 찾아낸 두 개 — 하나는 틀린 근거, 하나는 틀린 조언

**`getIfAvailable()` 이 파괴 순서를 등록한다는 것은 사실이 아니었다.** 위 문단의 결론(레지스트리만
edge 를 손으로 등록한다)은 맞았지만 근거가 거꾸로였다 — provider 도 edge 를 등록하지 않는다.
`ObjectProvider` 는 `autowiredBeanNames` 싱크 없이 resolve 하므로 누가 요청했는지가 기록되지 않는다.
실측이다: 스타터 컨텍스트에서 `getDependentBeans` 가 승인 저장소 · 세션 저장소 · 큐 셋 다 빈 배열을
돌려주고, 손으로 등록한 레지스트리만 `[aimonApplicationContributions]` 를 돌려줬다.

살아 있는 버그는 아니었다 — 이 빈들은 contributions 를 만드는 도중에 생기므로 스택보다 먼저 생성되고
역-생성순이 우연히 맞는 답을 준다. 문제는 **그 우연을 근거로 적어 둔 것**이다. 다음 사람이
"provider 면 순서는 공짜" 로 읽고 다섯 번째 저장소를 얹는다. 그래서 넷이 아니라 **기여 빈 전부**에
edge 를 등록하고, 그 edge 를 단언하는 테스트를 붙였다(`AimonApplicationContributionsTest`).

#### 두 번째 리뷰 — "기여 빈 전부" 가 한 메서드 안에서만 전부였다

위 문단의 "전부" 는 `aimonApplicationContributions` 안의 전부였고, 그 밖에 같은 모양으로 resolve 하는
자리가 다섯 군데 더 있었다 — 세션 슬라이스의 SPI 5개, 파일시스템 2개, 스케줄링 4개와 빌려온 Quartz
`Scheduler`, knowledge 1개, memory 3개. **커넥션을 쥐고 있을 개연성은 오히려 그쪽이 높다**
(`store=postgres` 의 `SessionRecordStore`, GridFS·S3 위의 `VirtualFileSystem`). 규칙을 적어 놓고 그
규칙이 가장 필요한 자리를 비워 둔 셈이다.

같은 오해가 독립적으로 두 번 자랐다는 증거도 나왔다 — 스케줄링 슬라이스의 주석이 이미
*"그래서 파괴 순서를 정하는 의존성 edge 가 등록된다"* 라고 적고 있었고, 그것은 승인 축에서 정정한 것과
정확히 같은 틀린 문장이다. 그래서 헬퍼를 `ApplicationBeans` 로 꺼내 여섯 슬라이스가 모두 그것을
지나가게 했다. 주석이 아니라 **호출 자리**를 보고 "이 기여에 edge 가 붙었나" 를 답할 수 있는 것이
목적이다.

**그리고 이름 기반 제외가 감수한 두 모양은 증상이 없었다.** 애플리케이션이 자기 빈에
`aimonPendingTurnRegistry` 라는 이름을 붙이면 `@ConditionalOnMissingBean` 은 타입으로 보고 재수출을
물리는데 입력 이음매는 이름으로 그 빈을 건너뛴다 — 애플리케이션은 한 레지스트리를 주입받고 스택은 다른
것에 턴을 적재하며, `/approve` 는 아무것도 못 찾는다. **이 이음매가 없애려던 바로 그 실패**가 아무 신호
없이 남는 것이다. `PendingTurnRegistryConsistencyCheck` 가 refresh 끝에서 동일성 비교 한 번으로 기동
에러로 바꾼다.

**메시지 큐를 degradation 으로 알리자는 제안은 기각했다 — 조언이 틀렸다.** 승인 저장소 셋과 같은 줄에
세우면 "공유하라" 로 읽히는데, 큐는 공유하면 안 되는 쪽이다. 배출은 `AgentRuntimeId` 하나로만 거르고
(`OrcaAgentExecutor#injectQueuedMessages`) 적재된 항목에는 `SessionId` 가 **아예 없으므로**
(`DefaultLiveSession`), 공유 저장소에서는 그 에이전트 런타임의 턴을 다음에 도는 노드가 — 다른 세션의
턴이더라도 — 그 입력을 가져간다. 한 노드 안에서는 그것이 큐의 용도지만 노드를 넘으면 남의 턴에
주입된다. degradation 대신 **이음매의 javadoc 에 제약으로** 적었고, "알리지 않는다" 를 테스트로
못박았다.

#### 세 번째 리뷰 — 규칙은 맞았고, **규칙이 닿는 범위를 실측하지 않았다**

앞의 두 라운드가 근거를 고쳤다면 이번엔 **적용 범위**가 문제였다. 셋 다 실측으로 잡혔다.

**edge 등록이 `FactoryBean` 의 제품을 못 봤다.** 싱글턴 캐시에 있는 것은 제품이 아니라 팩토리이므로,
캐시 하나만 대조하던 첫 판은 `FactoryBean` 이 만든 기여를 조용히 건너뛰었다. 그리고 그 모양의 대표가
하필 **빌려온 Quartz `Scheduler`** 다 — `spring-boot-starter-quartz` 가 `SchedulerFactoryBean` 으로
내보내기 때문이고, 그래서 *"여기가 거의 어디보다 중요하다"* 고 주석이 지목한 바로 그 항목이 edge 없이
남아 있었다. 실측: `FactoryBean<SessionRecordStore>` 를 정의하면 spec 에는 정상 반영되는데
(`getRecordStore()` 가 채워진다) `getDependentBeans` 는 빈 배열이었다. 이제 두 캐시를 다 읽고, edge 가
없는 채로 남는 것은 프로토타입과 비-싱글턴 `FactoryBean` 제품 둘뿐이다 — 컨테이너가 파괴하지 않는
것들이라 정렬할 파괴가 없다.

**감수한 두 모양 중 하나는 애초에 실패가 아니었다.** 타입을 인스턴스화해야만 알 수 있는 `FactoryBean` 이
레지스트리를 만드는 경우를 "증상 없는 어긋남" 으로 적고 검사가 잡는다고 했는데, **둘 다 틀렸다.** 그런
빈은 정의 스캔에만 안 보이는 것이 아니라 컨테이너의 모든 타입 기반 조회에 안 보이므로,
`@ConditionalOnMissingBean` 이 재수출을 그대로 두고 애플리케이션의 `@Autowired PendingTurnRegistry` 도
그 재수출로 — 즉 스택이 쓰는 그 인스턴스로 — 풀린다. **갈라지는 것이 없다.** 실측으로 확인했고
(`getBeanNamesForType` 에 그 빈이 아예 안 나온다), 검사는 원인 목록 대신 **어긋남 자체**를 겨누도록
다시 썼다 — 계약이 한 문장이 되었고(*`getBean(PendingTurnRegistry.class)` 가 돌려주는 것이 스택이 턴을
적재하는 그것이다*) 해소 규칙을 두 번 구현하지 않는다.

**그리고 정정한 그 문장이 세 번째 자리에 그대로 있었다.** `AimonSessionAutoConfiguration` 클래스
javadoc 이 여전히 *"`getIfAvailable()` registers a dependent-bean edge"* 라고 적고 있었다 — 이번 라운드가
배선을 바꾼 메서드 **바로 위**에서. "이 오해는 스스로 자란다" 가 앞 라운드의 논거였는데 그 증거가 한 건 더
있었던 셈이고, 헬퍼로 꺼낸 판단이 옳았다는 뜻이기도 하다.

자잘한 것 둘도 같이. `soleCandidate` 가 *"provider 가 던졌을 예외를 던진다"* 고 적었지만 provider 의
`@Priority` fallback 은 따라 하지 않는다 — 그것을 읽으려면 후보마다 인스턴스가 있어야 하고, 후보를
인스턴스화하지 않는 것이 이 조회의 존재 이유이므로 **일부러** 좁게 두고 그 이유를 적었다. 그리고
`AimonAgentCustomizer` 만 헬퍼를 지나가지 않아 "이 항목에 edge 가 필요한가" 라는 판단이 한 자리 남아
있었다 — `ApplicationBeans.resolveAll` 로 그 자리도 없앴다.

**남은 것.** 없다. 구현은 M-2 이고 그것은 여전히 트리거 대기다 — 이 항목이 뚫은 것은 **길**이다.

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

기록되어 있다. 두 겹으로 — `AimonStackBuilder` 의 `distributed-approvals` degradation 이 기동 시
운영자에게 말하고(2026-08-31 기준 `:295-303`; M-1 이 그 자리를 `announceNodeLocalApprovals` 로
옮기고 남은 것만 세도록 좁혔다), SPI javadoc 여섯 중 다섯이 각자 적고 있다.
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
