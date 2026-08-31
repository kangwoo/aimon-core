# 번역 용어표 (Translation Glossary)

`docs/` 의 정본은 **한국어**이고, 영어 문서는 그 번역이다 (`.en.md` 접미사).
이 표는 그 번역이 **한 단어를 여러 가지로 옮기는 일**을 막기 위해 있다.

리포지토리 루트의 `README.md` · `CONTRIBUTING.md` 등은 방향이 반대(영어 정본 → `.ko.md`)지만,
아래 규칙은 **방향과 무관하게** 그대로 적용된다 — 두 언어가 같은 개념을 같은 이름으로 부르게
하는 것이 목적이지, 한쪽을 다른 쪽에 맞추는 것이 목적이 아니기 때문이다.
경계와 접미사 규약은 [`../README.md`](../README.md) 의 **번역 규칙** 절에 있다.

번역이 일관성을 잃으면 손해는 영어 독자에게만 가지 않는다. `LiveSession` 이 어느 문단에서는
"session", 어느 문단에서는 "live handle" 로 나오는 순간, 이 저장소가 **두 수명을 구별하려고
치른 모든 비용**(`overview/glossary.md` §2, ArchUnit 테스트, 두 번의 개명)이 영어 쪽에서 무효가 된다.

> 이 표는 완결된 사전이 아니다. **§7 이 규칙이고 나머지는 그 규칙의 적용 사례다.**

---

## 1. 번역하지 않는 것

아래는 영어 문서에서도 **원문 그대로** 둔다. 옮기면 가리키는 대상이 사라진다.

| 대상 | 예 |
|------|-----|
| 타입 이름 | `LiveSession`, `SessionRecord`, `AgentRuntime`, `ToolResult`, `ExecutionBudget` |
| 패키지 경로 | `at.aimon.core.agent.session.store` |
| 파일·디렉토리 경로 | `docs/overview/glossary.md`, `modules/aimon-core/` |
| 코드 블록 안의 식별자·명령어·출력 | `./gradlew checkAll`, `SubmitOutcome.QUEUED` |
| 설정 키 | `aimon.llm.provider`, `aimon.llm.api-key` |
| CLI 명령과 플래그 | `/compact`, `/budget`, `/revoke --agent` |
| 애노테이션·태그 | `@ExternallyManaged`, `@Tag("docker")`, `@ToolParam` |
| 열거 상수 | `ENFORCE`, `CONCURRENT_SAFE`, `MAIN_AGENT` |
| **동결된 와이어 이름** | `conversationId`, `conversation_locks`, `invokingConversationId` |

마지막 줄은 특히 조심한다. 이 이름들은 Java 식별자가 `Session*` 로 개명될 때 **의도적으로
동결**되었다 (`overview/scope-model.md` §7). 영어 번역에서 "일관성을 위해" `sessionId` 로
고치면 저장된 데이터와 문서가 어긋난다. **어긋나 보이는 것이 정상**이라는 사실까지가 문서다.

### 코드 블록 안의 주석은 번역한다

블록 전체를 그대로 두라는 뜻이 아니다. 경계는 **실행되는 것과 읽히는 것** 사이에 있다.

```java
// 만든 쪽이 닫는다 — 어셈블리가 셸을 줬다면 런타임은 손대지 않는다
if (ownedShell != null) {
    ownedShell.close();
}
```

위 블록에서 번역 대상은 주석 한 줄뿐이다. `ownedShell` 도 `close()` 도 건드리지 않는다.

---

## 2. 실행 단위 세 단어 — 번역이 아니라 **보존**의 문제

`turn` · `iteration` · `execution` 은 **한국어 정본에서도 이미 영어**이거나 그 음차다
(`overview/glossary.md` §4). 영어 번역에서 할 일은 옮기는 것이 아니라 **섞지 않는 것**이다.

| 한국어 정본 | English | 절대 쓰지 않을 것 |
|-------------|---------|------------------|
| 턴 | turn | run, request, session |
| 이터레이션 | iteration | loop, round, step, cycle |
| 실행 | execution | run, invocation, execution**s** 로 얼버무리기 |

세 단어는 포함 관계다 — 턴은 실행의 한 종류이고, 이터레이션은 턴 안에 있다. 두 경로(턴과 포크)가
공유하는 것을 말할 때 `turn` 이라고 쓰면 **틀린 문장이 된다**. 정본이 그 자리에서 "실행" 이라고
쓰고 있다면 그것은 문체가 아니라 정확성이므로, 영어에서도 `execution` 이어야 한다.

예외는 `assistant turn` / `user turn` 뿐이다. LLM 메시지 role 어휘이며 **반드시 한정어를 붙인다** —
맨 `turn` 은 언제나 "사용자 입력 1건의 처리" 다.

---

## 3. 수명·스코프 용어

`overview/glossary.md` 와 `overview/scope-model.md` 의 뼈대다. 이 절의 오역은 문서 하나가
아니라 모델 전체를 무너뜨린다.

| 한국어 | English | 주의 |
|--------|---------|------|
| 수명 | lifetime | "life cycle" 아님. 얼마나 사는가 |
| 스코프 | scope | 어느 단계에 속하는가. 수명과 다른 축이다 |
| 소유권 | ownership | |
| 소멸 | teardown | "destruction" / "disposal" 아님. `close()` 를 부를 **책임** |
| 애그리게이트 | aggregate | DDD 용어 그대로 |
| 영속 | persistent / persisted | "permanent" 아님 |
| 노드 로컬 | node-local | |
| 일시적 | transient | "temporary" 아님 — 없어질 것이 예정되어 있다는 뜻 |
| 리스 | lease | |
| 펜싱 | fencing | fencing token |
| 축출 | eviction | idle-TTL·`maxEntries` 로 밀려나는 것 |
| 재기동 | rehydration | 저장된 상태에서 다시 세우는 것 |
| 재개 | resume | 같은 `SessionId` 로 다시 여는 것 |
| 재발화 | re-fire | cron 이 다시 울리는 것. "reactivation" 아님 |
| 우편함 | inbox | `SessionInbox` |
| 신호 | signal | `SessionSignalBus`, `SessionSignal` |
| 전사 | transcript | `SessionTranscript` 와 같은 단어. "transcription" 아님 |
| 포크 | fork | 명사·동사 모두 fork |
| 빌려온 것 | borrowed | `@ExternallyManaged` 가 표시하는 것 |
| 오칭 | misnomer | |
| 동결 | frozen | 와이어 포맷·DDL·채널명 |

### 이 절에서 가장 자주 깨지는 것

**`LiveSession` 을 "the session" 으로 줄이지 않는다.** 한국어 정본이 "라이브 세션" 이라고 쓴
자리는 전부 노드 로컬 핸들이고, 그냥 "세션" 이라고 쓴 자리는 영속 레코드다. 영어에서 둘 다
"session" 이 되면 `1 SessionRecord : 0..N LiveSession` 이라는 문장이 읽는 사람에게
동어반복이 된다.

같은 이유로 영어 번역은 **맨 `Session` / `AgentSession` 을 타입처럼 쓰지 않는다.**
그 두 이름은 코드에서 금지되어 있고 `SessionNamingArchitectureTest` 가 빌드를 깨뜨린다 —
문서만 그 이름을 되살릴 이유가 없다.

**"conversation" 은 살아 있는 단어다.** 폐기된 것이 아니라 **자리가 바뀐 것**이다.
수명을 가리키는 자리에서는 빠졌고, **LLM 과의 메시지 교환**을 가리키는 자리에는 그대로 있다
(`getConversationHistory()`, "Conversation compacted"). 한국어 "대화 이력" 은
`conversation history` 이지 `session history` 가 아니다.

---

## 4. 반복 용어

| 한국어 | English | 주의 |
|--------|---------|------|
| 도구 | tool | |
| 훅 | hook | |
| 스킬 | skill | |
| 계약 | contract | 지켜야 하는 것 |
| 규약 | convention | 합의된 것. 계약과 달리 강제되지 않을 수 있다 |
| 게이트 | gate | 통과해야 지나가는 검사. "gateway" 아님 |
| 강제 | enforce | `SchemaValidationMode.ENFORCE` 와 같은 단어 |
| 관대 | lenient | 모르는 것은 통과시킨다 |
| 격리 | isolation | |
| 예산 | budget | `ExecutionBudget` |
| 상한 | cap / limit | 배치당 캡은 cap, max iterations 는 limit |
| 실측 | measured | "실측되지 않았다" = has not been measured (추정과 대비) |
| 병렬 | parallel | 실행 방식 |
| 동시 실행 | concurrent | 안전성의 성질 (`CONCURRENT_SAFE`) |
| 순차 | sequential | `SEQUENTIAL` |
| 중단 | interrupt | 돌고 있는 것을 끊는다 |
| 취소 | cancel | 시작 전이든 후든 그만둔다 |
| 승인 | approval | |
| 미등록 (도구) | hallucinated | 모델이 없는 도구 이름을 부른 것 |
| 진입점 | entry point | |
| 어셈블리 | assembly | 조립 담당 모듈 (`aimon-bootstrap`, `aimon-cli`) |
| 퍼사드 | facade | |
| 팬아웃 | fan-out | |
| 핫리로드 | hot reload | |
| 트리거 | trigger | |
| 개명 | rename | |
| 정본 | canonical | 문서 언어. 데이터 권위는 "the authority" |

### `backlog/` 전용 어휘

이 세 단어는 `docs/backlog/README.md` 가 **서로 다른 뜻으로 정의한 것**이라 바꿔 쓸 수 없다.

| 한국어 | English | 뜻 |
|--------|---------|-----|
| 열림 | open | 아직 할 일이 남았다 |
| 닫힘 | closed | 했다. 기록은 **지우지 않는다** |
| 해소 | dissolved | 하지 않기로 했다 — 전제가 사라져서 항목 자체가 없어졌다 |
| 접힘 | folded | 더 큰 항목 안으로 들어갔다 |

---

## 5. 문체 — 번역이 지우면 안 되는 것

정본은 **결론을 먼저 쓰고 근거를 뒤에 붙이는** 문체다. 근거 문장이 길다는 이유로 요약하면
그 문서의 값이 통째로 사라진다. 이 저장소의 문서가 답하는 질문은 대개 "무엇인가" 가 아니라
"**왜 그렇게 되어 있는가**" 이기 때문이다.

- **`IMPORTANT:` 마커를 유지한다.** 번역하지 않고 그대로 둔다
- **"하지 말 것" 목록은 명령형으로 유지한다.** "It is recommended not to…" 로 부드럽게 만들지 않는다
- **표는 표로 유지하고 행 순서도 바꾸지 않는다.** 정본과 번역을 대조할 때의 단위가 행이다
- **길이가 비슷해야 한다.** 번역본이 눈에 띄게 짧으면 근거를 버린 것이고, 눈에 띄게 길면
  정본에 없는 설명을 지어낸 것이다

---

## 6. 헤딩을 번역하면 앵커가 바뀐다

번역에서 가장 자주 나는 사고이고, 자동으로 잡힌다.

`## 3. 부트스트랩 흐름` 을 `## 3. Bootstrap flow` 로 옮기면 그 문서의 목차와 다른 문서의
`#3-부트스트랩-흐름` 링크가 전부 죽는다. **헤딩을 옮겼으면 그것을 가리키는 `#fragment` 도 같은
PR 에서 고친다.**

```bash
python3 scripts/check-doc-links.py
```

경로뿐 아니라 앵커까지 검사하며 CI(`docs-links` 잡)도 같은 것을 돌린다. 없는 앵커는 페이지가
정상적으로 열리고 맨 위에 떨어질 뿐이라, 이 검사가 없으면 **잘못 안내됐다는 사실이 독자에게
전달되지 않는다.**

---

## 7. 이 표에 없는 단어를 만났을 때

1. `overview/glossary.md` 에 그 단어가 있는지 본다. 있으면 **거기가 정의**이고 이 표는 표기법만 정한다
2. 없으면 정하되, **정한 것을 같은 PR 에서 이 표에 추가한다.** 다음 번역자가 같은 결정을 다시
   내리면 두 번역이 갈라진다 — 이 문서가 막으려는 것이 정확히 그것이다
3. 정할 수 없으면 **번역하지 말고 원문을 남긴다.** 잘못 옮긴 단어는 원문보다 나쁘다.
   원문은 읽는 사람이 검색이라도 할 수 있다

---

## 관련 문서

- [`../overview/glossary.md`](../overview/glossary.md) — 용어의 **뜻**. 이 표는 그 용어의 **표기**를 담당한다
- [`../overview/scope-model.md`](../overview/scope-model.md) — 수명·소유권·소멸 규칙
- [`../README.md`](../README.md) — 번역 대상 디렉토리(Tier) 경계
- [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) — 문서 기여 규칙과 링크 검사
