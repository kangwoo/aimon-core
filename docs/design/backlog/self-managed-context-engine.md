# Self-Managed Context Engine — 모델이 자기 뷰를 줄인다

> Status: **Backlog (의식적 보류)** — 구현 없음. 이 저장소의 과제에서 **이득을 잰 값이 없어** 착수하지 않는다.
> 재검토 트리거: [§12](#12-언제-다시-볼까).
> 이 문서는 두 부분이다. §2 ~ §7 은 **착수한다면 이렇게 한다고 정한 것**이고, [§8](#8-착수-전에-프로토타입으로-정할-것) 은
> **문서로는 정하지 못한 것**이다 — 저장 계층에 닿는 네 가지로, 종이 위에서 채울 때마다 코드와 어긋났다. 거기에는 답이
> 아니라 그 과정에서 드러난 제약을 적었다.
> 적용 대상(착수한다면): `aimon-core`, 그리고 선택 키가 지나는 `aimon-bootstrap` · `aimon-spring-boot-starter` · `aimon-cli`.
> 선행 설계: [`../agent-execution/context-engine.md`](../agent-execution/context-engine.md) (`ContextEngine` SPI 와 롤링
> engine), [`../session/session-log.md`](../session/session-log.md) (로그 · 뷰 상태 · 봉인)

---

## 1. 무엇을 푸는가

### 1.1 뷰를 줄이는 판단이 전부 하네스에 있다

`DefaultContextEngine` 과 `RollingContextEngine` 은 **언제**(임계값)와 **무엇을**(절단면)을 토큰 수로 정한다. 둘 다
대화의 내용을 모른다.

| # | 결과 | 원인 |
|---|------|------|
| M1 | **쓸모없는 것이 임계값까지 남는다** | 실패한 검색, 이미 읽고 버린 로그 덤프도 임계값에 닿기 전에는 매 호출에 실려 간다 |
| M2 | **쓸모 있는 것이 위치 때문에 사라진다** | tail 예산 밖에 있으면 결정적인 한 줄도 요약자의 말로 바뀐다. 요약자는 그 줄이 왜 중요한지 모른다 |
| M3 | **큰 결과는 통째로만 다룬다** | L0 prune 은 도구 결과를 placeholder 로 바꾸거나 그대로 둔다. "이 2만 토큰 중 세 줄만" 은 없다 |

무엇이 아직 필요한지는 그 작업을 하고 있는 모델이 가장 잘 안다. 이 문서는 그 판단을 모델에게 넘기는 engine 을 다룬다.

### 1.2 이 문서의 답

- **모델이 도구로 뷰를 줄인다** — 구간을 자기 노트로 대체하거나(`replace`), 도구 결과에서 남길 줄을 고른다(`trim`)
- **편집은 뷰 상태가 된다** — 로그는 그대로다. 감사 기록과 `SessionHistory` 되찾기가 바뀌지 않는다
- **하네스는 게이트와 안전망이다** — 편집이 유효한지 검사하고(§3.3), 모델이 관리하지 않으면 롤링 engine 이 줄인다(§4.3)
- **모델은 자기 뷰의 크기를 본다** — 크기를 모르는 모델은 줄이지 않는다(§4.4)

### 1.3 참고한 것

- **Context Language Models** (Shao et al., arXiv 2609.37725, 2026-09-29) — 컨텍스트를 파일로 미러링하고 모델이 셸로
  제한 없이 고친다. 시스템 프롬프트와 원래 과제만 고정한다. 저자 보고로 BrowseComp-Plus 에서 요약 하네스보다 정확도가
  높고(59.4% 대 48.0%) 연산이 적다(prefix-reuse FLOPs 21.5% 감소). 32K 컨텍스트 상한의 Qwen3.6-27B 기준이고 독립 재현은
  아직 없다. 작은 모델(Qwen3.5-9B)은 과제의 절반에서 한 번도 편집하지 않았다. 레퍼런스 구현은 CC BY-NC 4.0 이다
- **pi-clm** (`lolipopshock/pi-clm`, MIT) — 논문 공저자가 Pi 코딩 에이전트의 확장으로 만든 구현. 세션 기록은 고치지 않고
  **투영**만 바꾼다는 점이 이 문서와 같다. 두 정책을 싣는다 — 줄이는 편집만 받고 첫 · 최신 사용자 메시지를 지키는
  `conservative`, 크기 게이트도 보호도 없는 `clm`(기본값). 이 문서의 게이트는 앞의 것에 가깝다
- **VISTA** (Xu et al., arXiv 2606.30005) — 모델은 자기 컨텍스트 상태를 스스로 추정하지 못한다. 토큰 사용량과 남은
  예산을 보여 주고 주소 가능한 블록 단위로 보관하게 한다

이 절의 수치와 설명은 논문 초록과 두 저장소의 문서를 읽은 것이고, 이 저장소에서 확인할 수 있는 것이 아니다. **어느
구현의 코드도 옮겨 오지 않는다.**

---

## 2. 경계

```
                    +----------- SelfManagedContextEngine (agent-scoped) ------------+
ContextEdit tool    |                                                                |
  validate ---------+--> pending edits (per execution, owned by the executor)        |
                    |         |                                                      |
TranscriptBuffer    |         v                                                      |
  log --------------+--> prepare(request)                                            |
  SessionViewState <+--    1. apply pending edits     rewrite / trim                 |
                    |      2. RollingContextEngine.prepare   safety net, own rules   |
                    |      3. attach the size reminder  ---> ContextView ---> LLM    |
                    |    recover / compactNow ---> RollingContextEngine              |
                    +----------------------------------------------------------------+
```

| 무엇 | 누가 | 수명 |
|------|------|------|
| 편집 요청 | `ContextEdit` 도구가 검증해 실행 단위의 대기열에 넣는다. 대기열은 실행기의 것이다 | 다음 `prepare` 까지 |
| 편집의 적용 | engine 이 `prepare` 에서 | — |
| 편집 결과 (`rewrites`, `trims`) | `SessionViewState` — 레코드에 영속 | session |
| 임계값 압축 · 복구 · `/compact` | 안에 든 `RollingContextEngine` | agent |
| 크기 알림 | engine 이 뷰에서 계산. 로그에 없다 | 한 LLM 호출 |

**도구는 뷰 상태를 바꾸지 않는다.** 이유는 스레드가 아니다 — `ContextEdit` 은 `CONCURRENT_SAFE` 가 아니므로
`DefaultParallelToolDispatcher` 가 그 배치를 병렬로 돌리지 않고, 도구는 턴을 돌리는 스레드에서 실행된다. 이유는
[`context-engine.md` §11](../agent-execution/context-engine.md#11-하지-말-것) 의 "engine 밖에서 뷰를 줄이지 않는다" 다.
뷰를 줄이는 입구는 `prepare` · `recover` · `compactNow` 셋이고, 편집을 `prepare` 로 모으면 SPI 에 메서드가 늘지 않는다.
늘어나는 것은 값 타입의 필드다(§9).

**바깥에서 감싸는 것으로 충분하다.** 편집 적용과 롤링의 판정을 한 락으로 묶을 필요가 없다. `/compact` 는 실행기의 명령
흐름에서 그 턴 자신의 버퍼로 돌고, 세션의 턴은 직렬화되어 있고, 버퍼는 턴마다 새로 만들어진다. `prepare` 와 끼어들 다른
쓰기가 없다. 롤링은 편집이 적용된 뒤의 버퍼에서 자기 계산을 처음부터 한다.

---

## 3. 편집과 뷰 상태

### 3.1 형태

```
SessionViewState
  summarySpan   : { fromSeq, toSeq, summaryText, ... }        (0 or 1)   unchanged
  droppedRanges : [ [fromSeq, toSeq) ... ]                               unchanged
  elisions      : { seq -> placeholder }                                 unchanged (L0 prune only)
  rewrites      : [ { fromSeq, toSeq, note } ... ]                       NEW - sorted, disjoint, after the head region
  trims         : { (seq, toolUseId) -> ... }                            NEW - what is stored is open, see section 8.3
```

뷰 계산에 두 단계가 더해진다.

1. `rewrites` 의 각 구간을 **노트 마커 하나**로 대체한다
2. `trims` 의 각 도구 결과 본문을 발췌로 바꾼다 (tool_use id · error 플래그는 보존)

- **`rewrites` 는 여럿이고 span 은 하나다.** 누적 갱신이 한 요약을 전제한다는
  [`session-log.md` §4](../session/session-log.md) 의 이유는 그대로다. `rewrites` 는 서로 독립이고 갱신하지 않는다
- **`trims` 는 `elisions` 가 아니다.** 실행기는 한 iteration 의 도구 결과 전부를 `TOOL` 메시지 **하나**로 append 하고,
  `elisions` 는 seq 가 키라서 그 메시지의 모든 결과에 같은 placeholder 를 쓴다. 모델이 병렬 호출 다섯 개 중 하나만
  줄이려면 `(seq, toolUseId)` 가 키여야 한다. `elisions` 는 L0 prune 의 것으로 그대로 둔다
- **마커는 `USER` 메시지이고 접두로 구별한다** — 요약 마커와 같다. `CompactBoundary` 에 `[[CONTEXT_NOTE:` 를 둔다.
  `DefaultPromptSizeRecoveryStrategy` 는 두 자리에서 이 접두를 알아야 한다. 버릴 후보에서 빼는 자리, 그리고 **"마지막
  USER 메시지" 를 고르는 자리**다 — 그 전략은 마지막 USER 메시지를 지키는데, 현재 턴의 입력 뒤에 노트 마커가 있으면
  마커가 그 보호를 가져가고 진짜 입력이 버릴 후보가 된다
- **마커의 본문은 이스케이프한다.** 본문의 줄이 마커 접두(`[[CONTEXT_NOTE:`, `[[COMPACT_`)로 시작하면 투영할 때 역슬래시를
  앞에 붙인다. 노트가 하네스의 머리 줄을 흉내 내지 못하게 한다 — pi-clm 이 미러 파일의 블록 머리 줄에 대해 하는 것과 같다
- **겹침** — 새 `rewrite` 는 기존 `rewrites` · `droppedRanges` · `trims` · `elisions` 를 **통째로 덮거나 닿지 않는다.**
  덮으면 흡수한다. 일부만 걸치면 거절한다(G1)
- **결정성** — 노트는 만들어진 순간 뷰 상태에 저장되고, 뷰 계산은 읽기만 한다.
  [`context-engine.md` §3.3](../agent-execution/context-engine.md#33-뷰의-결정성) 이 요약문에 대해 말한 것과 같다

### 3.2 연산

| 연산 | 뜻 |
|------|----|
| `rewrite(fromSeq, toSeq, note)` | 로그의 `[fromSeq, toSeq)` 를 노트 마커로 대체한다 |
| `trim(seq, toolUseId, selectors)` | 그 도구 결과에서 고른 줄만 남긴다 |

**`trim` 은 글을 받지 않는다.** 받는 것은 선택자다 — 앞 n 줄, 뒤 n 줄, 어떤 문자열을 포함하는 줄과 그 주변, 줄 범위.
발췌는 하네스가 원문에서 자른다. 모델이 쓴 문장이 도구 결과의 자리에 들어가는 길이 없고, 발췌가 도구 호출의 입력에 한
번 더 실리지도 않는다. 선택자의 정확한 정의는 §8.3 에 있다.

마커와 발췌에는 **하네스가 만든 머리 줄**이 붙는다.

```
[[CONTEXT_NOTE: written by assistant; replaces seq 412-448; conversation originals via SessionHistory]]
<note>

[tool result trimmed by assistant: seq=431 tool_use_id=toolu_01ab; kept lines 12-40, 118-121 of 2,304; original via SessionHistory]
<lines>
```

노트가 비어 있으면 머리 줄만 남는다 — "지웠다" 는 사실과 주소는 언제나 뷰에 있다. "conversation originals" 라고 적는
것은 `SessionHistoryTool` 이 `CONVERSATION` 항목만 돌려주기 때문이다. 구간에 든 `SYNTHETIC` 항목은 되찾을 수 없다 —
롤링의 span 이 흡수한 것과 같다.

### 3.3 게이트 — 편집이 유효한 조건

| # | 조건 | 적용 | 누가 검사하나 | 이유 |
|---|------|------|---------------|------|
| G1 | 두 경계가 **합법 절단면**이고, 기존 뷰 상태 구간을 일부만 걸치지 않는다 | `replace` | 연산 | `tool_use` / `tool_result` 짝이 갈라지면 프로바이더가 요청을 거절한다. 기존 불변식이다 |
| G2 | 대상이 **head 영역 뒤**에 있다 | 둘 다 | engine | 세션의 목적이다 |
| G3 | 대상이 **요청 시점의 로그 끝**(`nextSeq`) 앞에 있다 | 둘 다 | engine | 요청 뒤에 append 되는 것(호출한 assistant 메시지, 그 배치의 결과)은 모델이 편집을 정할 때 보지 못했거나 아직 답하지 않은 것이다 |
| G4a | rewind point 이후의 **`CONVERSATION` USER 항목**을 포함하지 않는다 | `replace` | engine | 현재 턴의 입력이다. 턴 중간에 들어온 메시지도 같은 종류의 항목으로 append 된다 |
| G4b | **rewind point 를 걸치지 않는다** | `replace` | 연산 | §3.4 |
| G5 | 대상이 **span 뒤**에 있다 | 둘 다 | 연산 | span 안은 원문이 뷰에 없고 봉인되었을 수 있다 |
| G6 | 노트의 추정 토큰이 `maxNoteTokens`(기본 2,000) 이하다 | `replace` | engine | 한 번에 실어 나를 수 있는 양을 묶는다 |
| G7 | 대상이 `TOOL` 메시지의 그 `toolUseId` 결과다 | `trim` | 연산 | — |
| G8 | **순 절감**이 `minEditSavingTokens`(기본 1,000) 이상이다 | 둘 다 | engine | 아래 |

```
replace:  net saving = tokens(range as the view shows it now) - tokens(marker with note) - tokens(note)
trim:     net saving = tokens(result as the view shows it now) - tokens(new excerpt with header)
```

- **연산의 불변식과 engine 의 정책을 나눈다.** 뷰 상태 연산(`SessionLogState`)은 추정기를 모른다. 크기를 재는
  조건(G6 · G8)과 "지금 이 실행의 사정" 을 보는 조건(G2 · G3 · G4a)은 engine 이 검사한다. 크기는 런타임에 주입된
  `TokenEstimator` 로 잰다 — 도구가 요청을 받을 때와 engine 이 적용할 때 같은 인스턴스여야 같은 답이 나온다
- **head 영역** — `floorSeq` 부터 그 뒤 첫 `CONVERSATION` USER 항목까지(포함). 그 앞의 `SYNTHETIC` 항목까지 묶는 것은,
  롤링의 span 이 head 뒤에서 시작하므로 그 앞에 `rewrite` 가 생기면 "span 뒤" 가 깨지기 때문이다. **영역이 빌 수 있다** —
  레코드에 남은 첫 `CONVERSATION` USER 항목보다 앞에서 시작하는 manifest 줄이 있으면(진짜 첫 요청이 복구로 빠져 봉인되었을
  수 있다) 뒤의 메시지를 첫 요청으로 삼지 않는다. 롤링이 head 를 정할 때 쓰는 규칙과 같다
  ([`context-engine.md` §13.6](../agent-execution/context-engine.md#136-in-place-폴백과-롤링-요약-보강)). 남은
  `CONVERSATION` USER 항목이 없을 때도 빈다. 빈 영역에서는 G2 가 지키는 것이 없다
- **G8 이 "줄이는 편집만" 을 맡는다.** `replace` 에서 노트를 한 번 더 빼는 것은 노트가 `ContextEdit` 호출의 입력으로도
  뷰에 남기 때문이다. 하한이 0 이 아니라 1,000 인 것은 편집이 뷰의 가운데를 바꾸기 때문이다 — OpenAI 의 자동 prefix
  캐시는 지금도 동작하고, 편집은 그 지점부터 뒤를 다시 읽힌다. 이미 가려진(`elide` 된) 결과의 `trim` 도 G8 에서 떨어진다
- **같은 구간을 다시 `replace` 할 수 있다.** 기존 `rewrite` 를 통째로 덮는 것이므로 G1 을 지난다. 다만 G8 이 그대로
  걸리므로 **작은 노트를 고쳐 쓰는 길은 없다.** `trim` 을 한 번 한 결과를 조금 더 다듬는 것도 같은 이유로 안 된다.
  제자리에서 고쳐 가는 글이 필요한지는 §8.4
- **스냅하지 않는다.** 구간이 합법 절단면에 걸리지 않으면 넓히지 않고 거절하며, 가장 가까운 합법 구간을 답에 담는다.
  조용히 넓히면 모델이 남기려던 메시지가 함께 사라진다. 거절을 줄이는 것은 `list` 의 일이다(§5)
- **G3 이 미응답 규칙을 대신한다.** 도구가 실행될 때 호출한 assistant 메시지는 아직 로그에 없다. 그래서 요청 시점의
  `nextSeq` 를 요청에 적어 둔다. `prepare` 가 적용할 때의 미응답 부분
  ([`context-engine.md` §13.10](../agent-execution/context-engine.md#1310-모델이-아직-답하지-않은-것은-압축하지-않는다))은
  전부 그 뒤에 있다
- **`SYNTHETIC` 항목은 head 영역 밖에서는 구간에 들어갈 수 있다.** 롤링의 span 도 이미 그것을 흡수한다. 반드시 남아야 하는
  지시는 시스템 프롬프트에 둔다 — 시스템 프롬프트는 뷰 밖이고 어떤 engine 도 건드리지 않는다
- 검사는 두 번 한다. 도구가 요청을 받을 때(모델에게 거절을 알리기 위해), 그리고 `prepare` 가 적용할 때. 그 사이에 로그는
  append 만 일어나므로 통과한 요청이 둘 사이에 무효가 되지 않는다

### 3.4 로그를 자르는 경로

rewind 는 rewind point 부터 로그를 자른다. G4b 가 그 지점을 걸치는 `rewrite` 를 만들지 못하게 하므로 모든 `rewrite` 는
그 앞에서 끝나거나 그 뒤에서 시작한다. 뒤의 것은 항목과 함께 사라지고 앞의 것은 그대로다. `trims` 는 seq 로 같은 규칙을
따른다. `/clear` 는 전부 지운다.

걸치는 구간을 받으면 안 되는 이유는 봉인이다. `SessionLogSealer` 는 봉인 구간을 rewind point 에서 끊으므로 걸친
`rewrite` 의 앞쪽 절반만 봉인될 수 있다. rewind 가 그 `rewrite` 를 버리면 manifest 가 가리키는 구간이 더 이상 가려져
있지 않고, 레코드는 `SessionLogState` 의 manifest 검사에서 떨어진다. 걸치는 구간이 실제로 생기는 자리도 있다 — 턴이
시작되면 rewind point 가 찍히고, 사용자 입력보다 **먼저** 조립된 user-context 가 `SYNTHETIC` 으로 append 된다.

rewind 는 **되돌린 턴 안에서 그 앞쪽 구간에 한 편집**을 되돌리지 않는다. 뷰 상태에는 이력이 없다. 그 노트는 사라진 턴의
모델이 쓴 글이고 사라진 턴의 사정을 담고 있을 수 있다 — [`session-log.md` §6.1](../session/session-log.md) 이 span 에
대해 받아들인 것과 같은 종류의 부정확이다.

### 3.5 봉인

[`session-log.md` §5.1](../session/session-log.md) 의 봉인 가능 조건("뷰에 원문으로 나타나지 않을 때 — `summarySpan` 안
또는 `droppedRanges` 안")에 **`rewrites` 안**을 더한다. 조건의 뜻("뷰 계산이 그 항목을 읽지 않는다")은 그대로이고,
G1 · G4b 를 지난 `rewrite` 는 봉인 구간의 다른 조건(두 끝이 합법 절단면, rewind point 에서 끊김)도 만족한다.

**이것만으로는 레코드 크기가 묶이지 않는다.** 그 문제와, 그것을 풀려다 깨진 두 장치는 §8.1 에 있다.

---

## 4. `SelfManagedContextEngine`

### 4.1 `prepare`

1. **편집 적용** — 대기 중인 편집을 요청 순서대로. 검사에서 떨어진 것은 버리고 센다
2. **안전망의 판정** — 롤링의 `prepare`. 롤링이 그 모델을 감당하지 못해 기본 engine 으로 물러나는 경우도 여기다
3. **크기 알림** — 뷰에 싣는다(§4.4)

**편집은 `prepare` 의 입력이다.** 실행기가 대기열의 내용을 불변 목록으로 `ContextRequest` 에 싣고 대기열을 비운다.
실행기는 같은 `ContextRequest` 를 `recover` 에도 쓰는데, `recover` 와 `compactNow` 는 그 목록을 읽지 않는다.

**적용되지 못하는 편집이 있다.** `prepare` 는 iteration 의 머리에서만 불린다. 도구 호출 뒤에 루프가 끝나면(최대
iteration, 예산 정지, 중단) 대기열은 버려진다. 잃는 것은 없다 — 뷰가 줄지 않았을 뿐이다. 다만 로그에는 그 도구 호출의
답이 남아 다음 턴의 모델이 읽으므로, 답은 "적용했다" 가 아니라 **"대기열에 넣었다"** 고 말한다(§5).

### 4.2 결정과 기록

**편집은 압축이 아니다.** 롤링의 결정은 손대지 않고 그대로 돌려주고, 편집의 결과는 `ContextDecision` 의 **별도 필드**
(`ContextEditSummary` — 적용 · 거절의 수, 전후 추정 토큰, 구간들)에 싣는다.

| 실행기가 하는 일 | 조건 |
|------------------|------|
| 봉인을 부른다 | 액션이 `COMPACT` **이거나** 편집 요약의 적용 수가 0 보다 크다 |
| `CompactBoundary` 이벤트 · `compactionEvents` | 지금과 같다 — 롤링의 기록에만 |
| 편집 요약을 실행 결과(`OrcaAgentExecutionResult`)에 싣는다 | 편집 요약이 있다. **액션과 무관하다** — `BLOCK` 이어도 결정은 실행기에 닿는다 |

- `kind = EDIT` 이나 새 `CompactionTrigger` 를 만들지 않는다. `CompactionMetadata` 는 요약 호출의 기록이고 편집에는 요약
  호출이 없다
- 편집 요약이 액션과 무관하게 실리는 것이 중요하다. 실행기는 `WARN` 의 기록을 `FALLBACK` 일 때만 결과에 더한다. 편집을
  롤링의 기록에 얹으면 warning 밴드에서 일어난 편집 — 가장 흔한 경우다 — 이 관측에서 사라진다

### 4.3 안전망

`recover` 와 `compactNow` 는 안의 롤링 engine 에 위임한다. `recover` 가 돌려준 뷰에는 크기 알림을 다시 싣는다.
`compactNow` 는 뷰가 아니라 `CompactionResult` 를 돌려주므로 싣는 것이 없다.

- **임계값을 늦춘다 — 롤링을 `autoCompactRatio = 1.0` 으로 만든다.** 그러면 롤링의 임계값은
  `min(effective, limits.autoCompactThreshold)` 가 되고, warning · budget-forced · "이 모델이 롤링을 감당하는가" 검사는 거기서
  그대로 파생된다. 코드 변경이 없다. 롤링이 0.6 에서 일찍 · 자주 · 작게 압축하는 것은 그것이 유일한 주체일 때의 정책이다.
  여기서 안전망이 60% 에서 먼저 움직이면 모델이 관리할 틈이 없다. pi-clm 은 같은 이유로 호스트의 자동 압축을 멈춘다 —
  그것이 모델의 편집을 버리기 때문이다
- **전제는 롤링과 같다.** 뷰 상태를 가질 수 있는 쓰기 형식이 필요하고, 그렇지 않은 노드에 배선하면 기동 시 실패한다
- **작은 창에서는 판정이 기본 engine 의 것이 된다.** 롤링이 감당하지 못하는 모델 · 시스템 프롬프트 조합(논문의 32K 도
  여기다)에서 롤링은 `DefaultContextEngine` 으로 물러난다. 편집 적용과 크기 알림은 그 앞뒤의 단계이므로 그대로 동작한다
- **롤링이 편집을 만났을 때** — span 이 넓어지며 `rewrites` · `trims` 를 흡수할 때 무엇을 요약하는지, L0 prune 이 `trims`
  를 어떻게 다루는지는 §8.2. 지금의 롤링은 뷰가 만든 메시지가 span 의 마커 둘뿐이라고 전제한다

### 4.4 크기 알림

뷰가 임계값의 `adviseRatio`(기본 0.5) 이상이면, 보낼 뷰의 **마지막 메시지 뒤에** `USER` 메시지 하나를 붙인다. 런타임이
모델에게 말할 때 쓰는 `SystemReminderFormatter` 로 포장하고 key 는 `context-size` 다.

```
[context: estimated 91,400 of 167,000 tokens before auto-compaction (55%); last request measured 96,200 including tool definitions]
```

- **하네스가 쓴 글만 들어간다.** 모델이 쓴 글이 이 포장에 들어가면 다른 문제가 생긴다(§8.4)
- **로그에 append 하지 않고 뷰의 메시지 목록에도 넣지 않는다.** `ContextView` 의 메시지 목록은 `(로그, 뷰 상태)` 의
  투영이다. 알림은 `ContextView` 의 별도 필드이고 LLM 을 부르는 자리가 붙인다
- **자리는 끝이다.** 시스템 프롬프트에 넣으면 호출마다 prefix 가 바뀐다
- **프로바이더에는 새 user 메시지로 간다.** Anthropic 어댑터는 도구 결과 뒤에 user 파라미터를 하나 더 내고, OpenAI 는
  도구 결과가 제 역할이므로 역시 새 user 메시지다. 모델이 그것을 사용자의 말로 읽을 위험은 다른 `<system-reminder>` 와
  같다. 절반 아래에서 싣지 않는 것이 그 빈도를 줄인다
- **임계값은 `ModelContextLimits` 에서 읽는다.** 롤링이든 물러난 기본 engine 이든 같은 값이라서(§4.3) 안의 engine 에 물을
  필요가 없다
- **크기에 든다.** 알림을 실었으면 그 토큰은 보낸 요청의 일부다. 알림은 한 줄이라 판정에는 넣지 않는다

**두 숫자를 섞지 않는다.** `estimated` 는 engine 의 추정기로 잰 값이다(시스템 프롬프트 + 뷰. 임계값 판정이 쓰는 것과 같은
양이고 도구 정의는 들지 않는다). `last request measured` 는 프로바이더가 직전 요청에 대해 돌려준 입력 토큰 수로,
실행기가 `ContextRequest` 에 실어 준다. 도구 정의를 포함하므로 그렇게 적는다. 턴의 첫 iteration 과 편집이 적용된 직후에는
없거나 낡은 값이므로 싣지 않는다. pi-clm 은 문자 수 기반 추정이 조밀한 내용에서 실제의 절반 아래로 나온다고 보고한다.
모델이 자기 크기를 판단하는 근거가 추정 하나면 그 오차를 그대로 믿게 된다.

seq 는 알림에 싣지 않는다. 모델이 구간을 고르려면 지도가 필요하고, 그것은 도구의 `list` 가 준다.

---

## 5. `ContextEdit` 도구

`at.aimon.core.tools.session` 에 `SessionHistoryTool` 과 나란히 둔다. engine 이 self-managed 로 해석된 런타임에만
등록한다. 입력이 여럿이므로 `GenericTool` 이고 입력 DTO 는 `@ToolParam` 을 단 `record` 다.

| 액션 | 입력 | 뜻 |
|------|------|----|
| `list` | `from_seq`(기본: head 영역 바로 뒤), `limit`(기본 40, 최대 200) | 뷰의 지도 |
| `replace` | `from_seq`, `to_seq`, `note` | 그 구간을 노트로 대체. **두 seq 모두 포함**이다 — 모델에게 보이는 것은 항목이지 경계가 아니다 |
| `trim` | `seq`, `tool_use_id`, 선택자 | 그 도구 결과에서 고른 줄만 남긴다 |

- **`list` 는 합법 단위로 보여 준다.** assistant 메시지와 그 도구 결과를 한 묶음으로 들여 쓰고, 묶음의 첫 seq 와 마지막
  seq 를 적는다. 묶음의 경계를 고르면 G1 을 지난다. 항목마다 seq, 역할, 추정 토큰, 첫 80자를, 도구 결과에는
  `tool_use_id` 와 줄 수를, 편집할 수 없는 항목에는 걸리는 조건을 적는다. 한 줄이 아닌 유일한 답이고, 지도는 뷰에 남아
  낡으므로 모델은 다 쓴 지도를 다음 `replace` 구간에 넣을 수 있다
- **편집의 답은 한 줄이다.** `queued: seq 412-448, about 18,300 tokens fewer from the next model call`
- **거절은 오류 결과다.** 도구 개발 규칙이 그렇고, 예외를 둘 이유가 없다. 답은 걸린 조건과 고칠 길을 담는다 —
  `rejected (G1): 448 splits a tool call from its result; the nearest range is 412-449`. 거절이 되풀이되는 것을 묶는 것은
  최대 iteration 과 예산이다. `StalledIterationGuard` 는 모든 결과가 오류인 iteration 이 세 번 이어질 때만 멈추고, 그
  사이의 성공한 `list` 하나가 그것을 되돌린다
- **설명은 규약만 적는다.** 도구 설명과 알림의 문구는 무엇을 할 수 있는지와 제약을 말하고, **언제 무엇을 지울지의
  전략**은 말하지 않는다. 전략은 AGENT.md 본문이나 스킬에 둔다 — 에이전트마다 다르고, 고쳐 가며 재는 대상이기 때문이다.
  pi-clm 이 하네스 문구를 규약으로 한정하고 전략을 따로 싣는 것과 같다
- **대기열은 실행기가 `ToolContext` 로 넣어 준다** — `SessionHistoryTool.LOG_SOURCE_KEY` 와 같은 방식이다. 같은 배치의
  여러 편집은 요청 순서대로, 앞선 요청이 적용된 것으로 보고 검증한다. 그래서 이 도구는 `CONCURRENT_SAFE` 가 아니다
- **`SessionHistoryTool` 을 함께 등록한다.** 머리 줄이 가리키는 원문을 되찾는 길이다. 지금 그 도구는 seq 와 글자 offset
  으로 읽는다. 병렬 배치의 결과 하나에서 발췌가 뺀 줄을 되찾으려면 결과와 줄로 짚는 입력이 필요하고, 그 모양은 `trim` 의
  줄 정의를 따른다(§8.3)
- allow-list 는 여전히 이름으로 막는다. 이 도구를 뺀 self-managed 에이전트는 크기 알림이 붙고 임계값이 늦은 롤링
  에이전트다
- **포크에서는 동작하지 않는다.** 포크의 도구 컨텍스트에는 대기열이 없고, 도구는 대기열이 없으면 오류를 돌려준다 —
  `SessionHistoryTool` 이 로그 소스 없이 그러는 것과 같다. 포크의 버퍼는 뷰 상태를 가질 수 없는 형식으로 만들어지므로
  포크에 주입된 engine 은 롤링이 그 버퍼에서 하는 것처럼 물러난다
- 도구 규칙(예외 금지 · 무상태 · 불변 I/O)은
  [`tool-development-guide.md`](../../features/tool/tool-development-guide.md) 를 따른다

---

## 6. 안전

편집 가능한 뷰는 **지시가 턴을 넘어 살아남는 통로**다. 논문과 pi-clm 이 모두 직접 적은 위험이고, AIMON 의 에이전트가
읽는 로그 · 티켓 · 웹 페이지는 신뢰할 수 없는 입력이다.

| 공격 | 막는 것 | 남는 것 |
|------|---------|---------|
| 도구 출력이 모델을 시켜 **지시를 노트로 남긴다** | 노트는 `maxNoteTokens` 를 넘지 못한다. 머리 줄이 모델이 쓴 글임을 밝히고, 본문은 머리 줄을 흉내 낼 수 없다(이스케이프). 모든 편집이 관측에 남는다(§10) | 모델은 자기 노트를 믿는다. 게이트는 **내용**을 판정하지 않고, 노트는 다시 `replace` 되며 앞으로 옮겨질 수 있다 |
| 도구 출력이 모델을 시켜 **제약이나 증거를 지운다** | head 영역과 현재 턴의 입력은 고정이다(G2 · G4a). 시스템 프롬프트는 뷰 밖이다. 대화의 원문은 로그에 남고 마커가 주소를 든다 | head 영역이 빈 세션에서는 G2 가 지키는 것이 없다. 뷰에서 사라진 제약을 모델이 다시 찾아 읽을지는 모델에 달렸다 |
| 모델이 **도구 결과를 지어낸다** | `trim` 은 글을 받지 않는다 — 발췌는 원문의 줄이다. `replace` 의 노트는 도구 결과의 자리가 아니라 마커로 들어간다 | 줄을 골라 뜻을 바꿀 수는 있다(부정문에서 한 줄만 남기기). 머리 줄이 남긴 줄 범위를 적는다 |
| 노트가 **요약을 거쳐 사실이 된다** | 정해지지 않았다(§8.2) | 노트 마커는 `USER` 메시지다. 그대로 요약 입력에 들어가면 요약자는 그것을 사용자의 말로 읽는다 |

§8 의 어떤 답도 이 표에 줄을 더할 수 있다. 이미 하나가 그랬다 — 도구 결과를 `USER` 역할의 글로 옮기는 장치는 주입된
지시로 고른 줄을 사용자의 자리로 올린다(§8.1).

이 engine 은 **기본값이 아니다.** 신뢰할 수 없는 입력을 읽으면서 되돌릴 수 없는 일을 하는 에이전트에는 켜지 않는다 — 그
판단은 배포하는 쪽의 것이다.

로그는 어떤 편집으로도 바뀌지 않으므로 **감사 기록은 모델이 고칠 수 없다.** 레퍼런스 구현이 하네스 밖에 따로 두라고
요구하는 것을 저장 모델이 이미 준다.

---

## 7. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 편집의 표현 | 뷰 상태 연산 | **로그를 고쳐 쓴다**(레퍼런스 구현) — [`context-engine.md` §1.1](../agent-execution/context-engine.md#11-한-타입이-두-가지-일을-한다) 의 L3 ~ L5 가 되돌아온다. 편집 빈도가 높을수록 더 자주 |
| 편집의 입구 | 구조화된 도구 `ContextEdit` | **파일 미러링 + 파일 · 셸 도구**(논문, pi-clm) — pi-clm 은 스스로 한계로 적는다: 다른 기계나 컨테이너의 도구 백엔드는 로컬 미러를 볼 수 없다. AIMON 의 셸은 `ExecutionEnvironment` 에서 돌고 그것은 원격일 수 있다. 미러를 그리로 보내면 대화가 실행 환경에 놓인다. 파일의 임의 편집도 결국 뷰 상태 연산으로 번역되어야 하므로(로그는 append-only 다) 표현력의 상한은 같다. 논문에서 구조화된 방식이 자유 편집보다 낮았다는 것은 알고 택한다 — 되살릴 조건은 §12 |
| 게이트의 세기 | 줄이는 편집만, head 영역과 현재 입력 고정 | **게이트 없음**(pi-clm 의 `clm` 모드 — 크기 제한 없음, 보호 없음, 새 블록과 순서 바꾸기 허용) — 대화형 코딩 도구에서 사람이 편집 diff 를 보는 전제의 선택이다. AIMON 의 에이전트는 사람 없이 돌고 신뢰할 수 없는 입력을 읽는다. 게이트가 이득을 얼마나 깎는지는 재야 한다(§12) |
| 적용 시점 | 다음 `prepare` (대기열) | **도구가 즉시 적용** — 뷰를 줄이는 주체가 engine 밖에 하나 더 생긴다. **턴 끝에 한 번**(pi-clm) — 한 턴 안에서 iteration 이 수십 번 도는 실행에서는 너무 늦다. **SPI 에 `edit()` 추가** — 입구가 넷이 된다 |
| 편집과 안전망을 묶는 방식 | 바깥에서 감싼다 | **롤링의 세션 락 안에서 한 단계로** — 끼어들 쓰기가 없다(§2). 롤링은 자기 계산을 락보다 먼저 시작하고 기본 engine 으로 물러나는 길은 다른 락을 쓰므로, 한 락으로 묶으려면 롤링의 흐름을 뒤집어야 한다 |
| 대기 중 실행이 끝난 편집 | 버린다. 답은 "대기열에 넣었다" | **실행 끝에 적용** — `prepare` 밖에서 뷰 상태를 바꾸는 호출이 필요하다 |
| 도구 결과의 축소 | 선택자(`trim`), 키는 `(seq, toolUseId)` | **모델이 쓴 발췌를 `elide` 로** — 모델의 문장이 도구 결과의 자리에 들어가고, 발췌가 호출 입력으로 한 번 더 뷰에 실리며, `elisions` 는 seq 가 키라 병렬 배치의 모든 결과에 같은 글을 쓴다 |
| 대체의 개수 | `rewrites` 여럿, span 은 하나 | **span 을 여럿으로** — 누적 갱신이 한 span 을 전제한다. **`droppedRanges` 재사용** — 노트를 실을 자리가 없고, 복구가 뺀 것과 모델이 뺀 것이 구별되지 않는다 |
| 합법 절단면 밖의 구간 | 거절하고 가까운 구간을 알려 준다. 지도를 합법 단위로 보여 준다 | **자동 스냅** — 모델이 남기려던 메시지가 조용히 사라진다. **깨진 짝을 글로 낮춘다**(pi-clm) — 모델이 의도하지 않은 구조 변경이고, 도구 출력이 `USER` 역할의 글이 된다 |
| 거절의 모양 | 오류 결과 | **오류 아닌 결과** — 도구 개발 규칙을 어긴다 |
| 고정 범위 | head 영역, 현재 턴의 입력, 요청 뒤에 온 것 | **시스템 프롬프트만**(논문, pi-clm 의 `clm`) — 미응답 규칙은 이 저장소가 값을 치르고 얻은 것이다(SL-6). pi-clm 도 넘침 방지에서 같은 것을 배웠다 — 방금 요청한 결과를 가리면 가리기가 되풀이된다. **롤링의 head 를 그대로** — 크기 상한과 blocking 흡수로 비는 값이다 |
| rewind point 를 걸치는 구간 | 거절(G4b) | **rewind 때 통째로 버린다** — 앞쪽 절반이 이미 봉인되었을 수 있고, 그러면 레코드가 일관성 검사에서 떨어진다(§3.4). **rewind point 에서 잘라 낸다** — 노트가 가리키는 구간이 바뀌어 노트의 내용과 어긋난다 |
| 줄이지 않는 편집 | 거절(G8). 순 절감에 하한 | **허용**(pi-clm 의 `clm`) — 뷰를 줄이는 연산이 내용을 주입하는 연산이 된다. **하한 0** — 편집은 그 지점 뒤의 prefix 캐시를 버린다 |
| 안전망 | 롤링 engine 을 안에 든다. `autoCompactRatio = 1.0` | **없음**(pi-clm 은 호스트의 자동 압축을 멈추고 넘침 방지만 둔다) — 편집하지 않는 모델이 실재한다(§1.3). **롤링의 0.6 그대로** — 모델보다 안전망이 먼저 움직인다. **새 임계값 로직** — 같은 판정을 세 번째로 구현한다 |
| 편집의 기록 | `ContextDecision` 의 별도 필드. 액션은 롤링의 것 그대로 | **`COMPACT` 와 `kind = EDIT`** — `BLOCK` 을 삼키고, `WARN` 에서는 기록이 실행 결과에 실리지 않으며, 편집하는 iteration 마다 경계 이벤트가 나간다 |
| 크기 알림의 자리 | `ContextView` 의 별도 필드, 보낼 때 끝에 | **로그에 `SYNTHETIC` append** — iteration 마다 쌓인다. **시스템 프롬프트** — prefix 가 매번 바뀐다. **도구 결과마다 크기를 덧붙인다**(pi-clm 의 선택 스위치) — 로그에 남는 결과를 바꾸거나 뷰 계산에 뷰 밖의 값을 넣게 된다 |
| 크기 알림의 빈도 | 임계값의 절반 이상일 때 매 호출 | **단계별로 한 번씩, 내려가면 다시 장전**(pi-clm) — "이미 알렸는가" 가 실행 단위의 상태다. engine 은 agent-scoped 이고 그 상태를 실을 자리가 없다 |
| 크기의 숫자 | 추정과 프로바이더 측정을 따로 보여 준다 | **추정 하나** — 조밀한 내용에서 크게 틀린다. **추정을 측정으로 보정한다**(pi-clm) — 보정 계수는 노드 로컬 상태다. 그것으로 게이트나 임계값을 재면 노드마다 답이 달라진다 |
| 전략의 자리 | AGENT.md 나 스킬. 도구 설명은 규약만 | **도구 설명에 전략** — 에이전트마다 다르고 재는 대상인 것이 코드에 박힌다 |
| 훅 | 편집에는 PreCompact · PostCompact 를 발화하지 않는다 | **발화** — 두 훅의 계약은 요약 호출의 앞뒤다 |
| 메모리 ingest | `ContextEdit` 호출의 `note` 입력을 가린다. `IngestChunks` 옆의 공용 도우미 하나에서 | **호출과 결과를 뺀다** — 빈 assistant 메시지나 결과 없는 `TOOL` 메시지가 남고 역할의 순서가 달라진다. **그대로 둔다** — 노트 본문이 대화로 ingest 된다. L5 와 같은 누수다. **경로마다 가린다** — 세션 끝의 ingest 는 프런트엔드가 조립한다(CLI 의 `AgentSetupFactory`). 남이 만든 프런트엔드는 빠뜨린다 |
| 포크 | 지원하지 않는다 | **주입 가능**(롤링처럼) — 포크의 버퍼는 뷰 상태를 가질 수 없고 되찾기 도구도 동작하지 않는다 |
| 다른 에이전트의 컨텍스트 | 건드리지 않는다 | **컨텍스트 파일로 서브에이전트를 만들고 지운다**(논문) — 부모가 포크의 버퍼를 고치는 길은 격리를 우회한다 |
| 이름 | `self-managed` / `SelfManagedContextEngine` | **`clm`** — 논문에서 CLM 은 모델의 종류다. 선택 키의 다른 값(`default`, `rolling`)은 정책 이름이다 |
| 기본값 | 바꾸지 않는다. opt-in | **`rolling` 을 대체** — 근거가 32K 상한의 저자 보고뿐이다 |

가져오지 않은 것 — 서빙 쪽의 Suffix Cache Reuse(자체 호스팅한 추론 서버의 일이다), 컨텍스트 관리 전략의 강화학습,
스킬 문서를 진화시키는 루프(기존 스킬 체계의 일이다), pi-clm 의 `/clm-compact`(모델에게 "지금 줄여라" 를 보내는 고정
프롬프트 — 사용자 메시지이지 engine 의 일이 아니다), 도구 결과의 글자 수 상한(도구 쪽의 일이다).

---

## 8. 착수 전에 프로토타입으로 정할 것

넷 다 저장 계층이나 요약 호출에 닿는다. 이 문서를 쓰는 동안 각각을 종이 위에서 채웠고, 채운 안은 매번 코드와 어긋났다.
그래서 답을 적지 않는다. 적는 것은 **무엇이 문제인가**, **깨진 안과 깨진 이유**, **어떤 답이든 지켜야 하는 제약**이다.
§8.1 이 풀리지 않으면 이 engine 은 착수할 수 없다.

### 8.1 레코드 크기

**문제.** 지금은 뷰가 임계값에 닿으면 span 이 넓어지며 가려진 것을 삼키고, 연속한 span 은 봉인되어 레코드를 떠난다.
이 engine 에서는 모델이 뷰를 잘 관리할수록 임계값에 닿지 않는다. **뷰는 작고 hot 로그만 커지는 세션이 정상 동작이다.**
뷰에는 없는데 레코드에 남는 것이 둘 있다.

- **흩어진 작은 `rewrites`** — `SessionLogSealer` 는 가려진 연속 구간 하나가 `minSealTokens`(32K) 이상일 때만 봉인한다
- **가려진 도구 결과의 원문**(`trims`, 그리고 prune 의 `elisions`) — 그 항목이 뷰에 남아 있고(같은 메시지의 다른 결과,
  tool_use id), `TOOL` 메시지 하나는 앞쪽 경계가 합법 절단면이 아니어서 혼자서는 봉인 구간이 될 수 없다

**깨진 안.**

| 안 | 왜 깨졌나 |
|----|-----------|
| 가려진 양이 한도를 넘으면 **span 을 강제로 넓힌다** | 봉인이 일어나지 않는 구성(세그먼트 저장소 없음, 쓰기 실패, 구간이 작음, 결정이 `WARN`)에서는 넓혀도 가려진 양이 줄지 않아 `prepare` 마다 요약을 부른다. 평범한 prune 한 번이 한도를 넘겨 prune 이 피하려던 요약을 강제한다. 최근 결과의 `trim` 은 tail 에 있어 결국 모델의 발췌 위에 요약을 덮는다 |
| 봉인의 크기 규칙을 **구간 하나에서 구간들의 합으로** | 가려진 채 남는 양은 묶이지만 manifest 가 `rewrite` 마다 한 줄씩 는다. manifest 는 합쳐지지 않으므로 레코드가 다시 선형으로 자란다. manifest 검사와 합법 절단면 판정이 줄 수에 비례하고 `prepare` 마다 여러 번 돈다. reader 는 줄마다 세그먼트를 한 번씩 읽는다. 기존 engine 에도 메시지 하나짜리 세그먼트가 생긴다 |
| 가려진 도구 결과가 쌓이면 **오래된 `[assistant, TOOL]` 짝을 뷰에 보이는 글 그대로 `rewrite` 로 접는다** | prune 이 가린 구간이 곧바로 접히고 원문이 봉인되어, span 이 넓어질 때 요약자가 원문 대신 placeholder 를 받는다 — 롤링이 가려진 항목의 원문을 요약에 넣는 이유를 정면으로 깬다. 도구 출력이 `USER` 역할의 글이 된다(§6). 크기가 그대로인 채 prefix 캐시를 오래된 짝부터 버린다. assistant 메시지의 추론 블록과 artifact 가 갈 곳이 없다 |

**어떤 답이든 지켜야 하는 것.**

- 레코드 크기를 줄이려고 LLM 을 부르지 않는다
- 가려진 양의 측정은 뷰 상태와 hot 항목만 읽는다. engine 은 저장소의 설정(`minSealTokens`)을 모른다
- 발췌를 뷰 상태에 저장하면 원문과 발췌가 **둘 다** 레코드에 있다. 측정이 "원문 − 보이는 것" 이면 그 중복을 세지 않는다
- prune 의 `elisions` 는 모델의 선택이 아니다. 그 원문은 요약에 쓰여야 하므로 요약 전에 봉인되면 안 된다
- 세그먼트 저장소가 없는 조립에서는 아무것도 봉인되지 않는다([`session-log.md` §12.2](../session/session-log.md)).
  롤링에서는 degradation 이지만 여기서는 레코드가 끝없이 자라는 구성이다. 그 조립에서 이 engine 을 거절할지 정해야 한다
- [`session-log.md` §11](../session/session-log.md) 의 세그먼트 병합이 선행 조건일 수 있다

### 8.2 롤링이 편집을 만났을 때

**문제.** span 이 넓어지며 `rewrites` · `trims` 를 흡수할 때 요약자에게 무엇을 주는가. 원문을 주면 모델이 버린 것이
요약으로 되돌아오고 봉인된 구간을 읽어야 한다. 노트를 주면 모델이 쓴 글이 요약을 거쳐 출처 없는 사실이 된다.

**깨진 안.** 노트를 격리된 데이터(`<<<ASSISTANT_NOTE>>>`)로 넣고 누적되지 않는 `Assistant notes` 섹션에만 적게 한다.

- 기본 engine 이 쓰는 전체 요약 템플릿(`SummaryPromptTemplate`)에는 이전 요약의 격리가 없다. 분리는 한 세대만 간다
- 이전 요약의 격리는 요약 호출의 **시스템 프롬프트**에 있다. 노트를 거기 넣으면 대화 안에서의 위치를 잃는다
- `SummaryRequest` 는 공개 SPI 다. 사용자가 구현한 `CompactionEngine` 은 노트를 조용히 버린다

**지금의 코드가 전제하는 것.** 뷰가 만든 메시지는 span 의 마커 둘뿐이다.

- 롤링의 요약 입력은 뷰가 만든 메시지를 건너뛰고, 원문이 아닌 위치에는 로그의 원문을 넣는다
- 롤링의 절단면 계산과 tail 토큰 계산은 뷰가 만든 위치를 세지 않거나 건너뛴다
- `DefaultContextEngine` 의 뷰 모드는 절단 위치의 seq 를 뷰에서 읽는데, 그 위치가 가운데의 마커면 seq 가 없다
- L0 prune 은 원문이 아닌 항목을 전부 건너뛴다

### 8.3 `trim` 의 세부

정한 것은 "선택자를 받고 글을 받지 않는다" 와 키뿐이다(§3.2). 남은 것은 다음이다.

- **줄의 정의** — 모델은 줄 번호 없이 결과를 본다. 개행 없는 출력(minified JSON, 대부분의 MCP 결과)에는 줄이 하나뿐이다.
  긴 줄을 고정 길이 조각으로 나누어 세는 안은 조각 경계에 걸친 문자열을 `match` 가 놓치고 서로게이트 쌍을 가를 수 있다.
  `\r\n`, 끝의 개행, 대소문자 접기의 로케일도 정해야 한다
- **선택자의 상한** — `match` 문자열의 수와 주변 줄 수. 정규식은 받지 않는다 — 모델이 쓴 패턴을 큰 입력에 돌리게 된다
- **무엇을 저장하는가** — 발췌를 저장하면 §8.1 의 중복이 생긴다. 선택자를 저장하고 투영할 때 다시 자르면 중복은 없지만
  원문이 hot 이어야 한다
- **다시 `trim`** — 선택자를 원문에 거는가 발췌에 거는가. 원문이면 봉인된 뒤에는 못 한다
- **`ToolUseResult.renderPayload`** — 본문과 함께 줄이는가
- **`SessionHistoryTool` 의 주소** — `tool_use_id` 와 줄로 읽는 입력. 줄의 정의가 정해져야 한다

### 8.4 작업 노트와 저장 형식

**작업 노트.** 논문의 모델이 스스로 만들어 낸 전략 가운데 가장 눈에 띄는 것이 제자리에서 고치는 스코어보드이고, pi-clm
은 그것을 "자라는 스크래치패드" 로 허용한다. G8 아래에서 `replace` 의 노트는 그 용도로 쓸 수 없다(§3.3). 뷰 상태에
크기가 묶인 슬롯 하나를 두고 크기 알림과 같은 자리에 싣는 안을 채웠고, 이렇게 깨졌다.

- `SystemReminderFormatter` 는 본문을 XML 이스케이프하고, 본문에 닫는 태그가 있으면 **예외를 던진다.** 노트는 영속되므로
  모델이 그 글자를 한 번 쓰면 그 세션의 모든 `prepare` 가 실패한다
- 이스케이프는 "한 군데만 고치기" 를 깬다. 모델은 이스케이프된 글을 보고 복사하는데 저장된 노트는 원래 글이다
- 대기열에 넣는 방식이면 루프가 끝날 때 마지막 노트 편집이 버려진다 — 인계 노트가 필요한 바로 그때다. 노트는 로그의
  구간을 가리는 것이 아니므로 "engine 밖에서 뷰를 줄이지 않는다" 가 대기열을 요구하지도 않는다
- `TodoWriteTool` 은 대신하지 못한다. `TodoRepository` 에 적는 작업 목록이고 수명과 저장 위치가 뷰 상태와 다르다
- 이 슬롯은 지우기 전까지 매 호출에 실리는, 가장 오래 사는 주입 통로다(§6)

**저장 형식.** `rewrites` 와 `trims` 는 뷰 상태의 새 필드이고, 지금의 코덱은 아는 필드만 쓴다.

- `JsonSessionSnapshotCodec` 은 모르는 버전의 레코드에 예외를 던진다. 새 형식을 모르는 노드는 그 세션을 열지 못한다
- 메인 소스의 열네 곳쯤이 형식을 **"정확히 V2 인가"** 로 검사한다 — 기본 engine 의 뷰 모드, 봉인, manifest 검사, 롤링이
  v1 로그로 보고 물러나는 자리, 코덱의 본문 쓰기, 조립의 봉인 degradation, CLI 가 세그먼트 저장소를 짝짓는 자리. 형식을
  하나 올리면 전부 "v2 가 아니다" 쪽으로 떨어진다. 순서 있는 비교가 먼저 있어야 한다
- 세그먼트의 payload 는 버전 2 를 요구한다. 형식을 올려도 그것은 그대로여야 한다
- 세션 백엔드 모듈은 바뀌지 않는다. transcript 는 그들에게 불투명한 문자열이다
- 올리는 단위가 둘 있다. **노드 스위치**(`DefaultTranscriptManager` 가 턴을 시작할 때 버퍼를 노드의 쓰기 형식으로 올린다 —
  v1 → v2 가 이렇게 갔다)는 opt-in engine 하나 때문에 전체를 되돌릴 수 없게 올린다. **레코드 단위**(레코드가 자기 형식을
  갖고 내려가지 않는 장치는 이미 있다)는 그 engine 을 쓰는 세션만 올린다

---

## 9. 기존 코드와 설계에서 바뀌는 것

§2 ~ §7 이 정한 것만 적는다. §8 의 답이 정해지면 저장 계층(`SessionLogSealer`, `SessionLogFormat`, 코덱, 조립)과 요약
(`RollingContextEngine`, `DefaultContextEngine`, `CompactionEngine`)이 여기에 더해진다.

| 자리 | 무엇이 |
|------|--------|
| `SessionViewState` | `rewrites` · `trims` 와 그 정규화. "이 seq 는 뷰에서 가려졌는가" 를 답하는 술어들이 `rewrites` 를 안다. rewind 정리가 둘을 다룬다 |
| `SessionLogState` · `TranscriptBuffer` | 두 연산과 불변식 검사(G1 · G4b · G5 · G7). manifest 검사가 `rewrites` 를 가린 구간으로 센다 |
| `ViewProjection` | 두 투영 단계와 본문 이스케이프. 위치마다 "원문 / span 마커 / 노트 마커 / 발췌 / elide" 를 구별한다 — 지금은 "원문인가" 하나뿐이다 |
| `PassthroughContextEngine` · `DefaultContextEngine` | 새 뷰 상태를 가진 버퍼를 투영한다 |
| `CompactBoundary` · `DefaultPromptSizeRecoveryStrategy` | 노트 마커의 접두. 그 인식이 버릴 후보와 "마지막 USER" 두 자리에 |
| `ContextRequest` · `ContextView` · `ContextDecision` | 대기 편집 목록과 직전 요청의 측정 토큰 / 크기 알림 / 편집 요약 |
| `OrcaAgentExecutor` | 대기열을 갖고 도구 컨텍스트에 넣는다. `prepare` 에 싣고 비운다. 알림을 마지막에 붙인다. 편집 요약에 봉인을 부르고 실행 결과에 싣는다 |
| `OrcaAgentExecutionResult` | 편집 요약의 목록 |
| `SessionHistoryTool` · `SessionLogSource` | `ContextEdit` 이 같은 소스로 뷰 상태를 읽는다. 되찾기의 새 주소는 §8.3 |
| 메모리 ingest | `IngestChunks` 옆의 도우미가 `ContextEdit` 의 `note` 입력을 가린다. 실행 끝과 세션 끝의 두 경로가 그것을 지난다 |
| `ContextEngineKind` · `OrcaAgentRuntimeFactory` | `SELF_MANAGED`, 롤링을 `autoCompactRatio = 1.0` 으로 감싸는 배선, 두 도구의 등록 |
| 선택 키가 지나는 자리 | `ExecutorSpec`, `AimonProperties`, `MarkdownAgentDefinitionParser`, CLI 의 `AgentSetupFactory` |
| 사용 가이드 | `docs/features/agent-execution/context-engine-guide.md` 와 그 번역본, `system-reminder` 규약 문서의 key 목록 |

설계 문서에서는 [`session-log.md`](../session/session-log.md) 의 §4(뷰 상태) · §5.1(봉인 가능 조건) · §6.1(rewind)과
[`context-engine.md`](../agent-execution/context-engine.md) 의 §3.1(값 타입) · §3.2(호출 지점의 알림) · §3.4(뷰 상태
연산) · §6(`SessionHistoryTool`) · §7(ingest) · §8.2(복구 전략의 마커 인식) · §10(선택 키 · 관측)이 바뀐다.

---

## 10. 선택과 관측

- **설정 키** — 기존 키에 값 하나가 늘어난다. Spring `aimon.context.engine`, AGENT.md frontmatter `context-engine` 에
  `self-managed`
- **관측** — 이 engine 이 이득인지는 재 봐야 안다. 전부 편집 요약(§4.2)에서 나온다
  - 적용된 편집의 수와 전후 추정 토큰, 액션별 수
  - 거절된 요청 수와 걸린 조건(G1 ~ G8). 거절이 많으면 도구 설명이나 `list` 가 모자란 것이다
  - 대기열에서 버려진 편집 수(§4.1)
  - 같은 실행에서 안전망이 발동한 횟수. 줄지 않으면 모델이 관리하지 않는 것이다
  - 편집 뒤 같은 구간에 대한 `SessionHistory` 조회 수 — 지운 것을 다시 읽는 비율
- 편집 요약은 구간과 전후 크기를 담으므로, pi-clm 의 패널이 보여 주는 것(요청마다의 크기, 편집 지점, 편집 전후 diff)을
  로그와 함께 다시 만들 수 있다. 화면은 이 문서의 일이 아니다

---

## 11. 하지 말 것

- **도구에서 뷰 상태를 바꾸지 않는다.** 대기열에 넣고, 적용은 `prepare` 가 한다(§2)
- **노트를 권한의 근거로 읽지 않는다.** 승인 · 권한 · 예산을 판정하는 코드는 뷰를 읽지 않는다. "승인받음" 이라고 적힌
  노트는 모델이 쓴 문장이다
- **머리 줄 없이, 이스케이프 없이 모델의 글을 뷰에 넣지 않는다**
- **도구 결과의 자리에 모델의 글을 넣지 않는다.** `trim` 은 선택자만 받는다
- **도구 출력을 `USER` 역할의 글로 옮기지 않는다**(§8.1 의 세 번째 안)
- **줄이지 않는 편집을 받지 않는다**(G8). `rewrites` 를 갱신하는 연산을 만들지 않는다
- **rewind point 를 걸치는 구간을 받지 않는다**(G4b)
- **편집을 `COMPACT` 로 보고하지 않는다.** 롤링의 액션을 덮지 않는다(§4.2)
- **크기 알림을 로그나 뷰의 메시지 목록에 넣지 않는다.** 모델이 쓴 글을 그 포장에 넣지 않는다(§4.4)
- **보정된 크기로 게이트나 임계값을 재지 않는다.** 프로바이더의 측정값은 모델에게 보여 주는 숫자다(§4.4)
- **레코드 크기를 줄이려고 요약을 부르지 않는다**(§8.1)
- **도구 설명에 전략을 적지 않는다**(§5)
- **다른 구현의 코드를 옮겨 오지 않는다**
- [`context-engine.md` §11](../agent-execution/context-engine.md#11-하지-말-것) 의 항목은 전부 그대로다

---

## 12. 언제 다시 볼까

착수 조건은 하나다 — **이 저장소의 과제에서 이득이 재어졌을 때.**

`ContextEngineLiveRig` 에 engine 을 비교하는 과제는 생겼다 — 뷰 압력을 높인 상태에서 사실 보존(needle), 키-값 조회, 로그
triage 를 `default` 와 `rolling` 으로 잰다([`context-engine.md` §13.11](../agent-execution/context-engine.md#1311-engine-을-비교하는-과제)).
**기준선을 한 번 쟀고, 아래 조건은 충족되지 않았다**(2026-10-07, 16K 창, `claude-haiku-4-5` 와 `gpt-4o-mini`, 시드
하나 — 수치와 읽은 방법은 [`CP-1`](../../backlog/context-engine-pressure-rig-open-items.md)). 롤링이 약한 칸은 키-값 조회
하나였고, 거기서 잃은 것은 요약이 지운 사실이 아니라 모델이 되찾지 않은 사실이었다. 그 리그에서 다음이 보이면 이 문서를
다시 연다.

- 롤링이 M1 ~ M3 때문에 실패하는 과제가 실제로 있다 — 요약이 필요한 사실을 잃거나, 임계값 전의 뷰가 쓸모없는 결과로 차서
  비용이나 정확도가 나빠진다. 논문의 수치는 32K 상한의 것이고, 128K ~ 200K 창에서는 셋 다 훨씬 늦게 나타난다
- 쓰려는 모델이 크기 알림을 보고 실제로 편집한다. 이것은 engine 없이도 잴 수 있다 — 알림과 도구 정의만 주고 호출하는지
  본다

다시 열면 순서는 이렇다. **§8.1 을 먼저 프로토타입으로 닫는다** — 그것이 닫히지 않으면 나머지는 의미가 없다. 그다음
§8.2 ~ §8.4. §2 ~ §7 은 그 답에 맞추어 다시 읽는다.

리그로 재야 정해지는 것들이다.

- **게이트가 이득을 깎는가** — pi-clm 은 게이트 없는 모드가 기본이고, 논문에서 구조화된 방식은 자유 편집보다 낮았다.
  G8 의 하한과 "스냅하지 않는다" 가 거절을 얼마나 만드는지, 거절이 모델을 편집에서 멀어지게 하는지
- **파일 입구를 되살릴 조건** — 구조화된 도구가 정밀 편집(도구 결과 여러 개를 한 번에 걸러 내기)에서 뒤처지면, 같은 뷰
  상태 연산 위에 파일 입구를 더한다. 그때의 질문은 미러를 어디에 두는가다
- **기본값들** — `maxNoteTokens`, `minEditSavingTokens`, `adviseRatio`. 전부 근거 없이 고른 값이다
- **모델별 기본값** — 편집하지 않는 모델에서는 도구 정의와 알림이 비용만 된다. 모델 능력 표에 따라 engine 을 물릴지
- **캐시 경계 이후의 G8** — CTX-05 로 Anthropic 에도 캐시 경계가 생기면 편집의 비용이 구체화된다. 하한을 토큰 수가
  아니라 "줄어드는 양 대 다시 읽히는 양" 으로 바꿀지

---

## 부록: 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| SPI 와 값 타입 | [`ContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextEngine.java), [`ContextRequest.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextRequest.java), [`ContextView.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextView.java), [`ContextDecision.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextDecision.java) |
| 안전망이 될 engine — 임계값의 파생, 요약 입력, 절단면, prune, 기본 engine 으로 물러나는 자리 | [`RollingContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/RollingContextEngine.java), [`DefaultContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/DefaultContextEngine.java) |
| 뷰 계산 · 미응답 부분 · elide 가 메시지의 모든 결과에 닿는 자리 | [`ViewProjection.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ViewProjection.java), [`PassthroughContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/PassthroughContextEngine.java) |
| 복구 — 마커 인식, 마지막 USER 의 보호, 뷰가 만든 메시지의 거절 | [`DefaultPromptSizeRecoveryStrategy.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultPromptSizeRecoveryStrategy.java), [`RecoveryDiff.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/RecoveryDiff.java), [`CompactBoundary.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactBoundary.java) |
| 요약 호출 — 이전 요약의 격리, 섹션, 판정 쪽의 크기 계산 | [`DefaultCompactionEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultCompactionEngine.java), [`DefaultCompactionGuard.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultCompactionGuard.java) |
| 압축 기록 (편집은 여기에 싣지 않는다) | [`CompactionMetadata.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionMetadata.java) |
| 뷰 상태와 그 연산 · manifest 검사 · 형식 | [`SessionViewState.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionViewState.java), [`SessionLogState.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogState.java), [`TranscriptBuffer.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/TranscriptBuffer.java), [`SessionLogFormat.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogFormat.java) |
| 합법 절단면 | [`LegalCuts.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/LegalCuts.java) |
| 봉인 — 구간의 계산, rewind point 에서 끊기, 크기 규칙 | [`SessionLogSealer.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogSealer.java) |
| 직렬화 — 뷰 상태의 필드, 버전 검사 | [`JsonSessionSnapshotCodec.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/subagent/task/codec/JsonSessionSnapshotCodec.java) |
| 턴 시작 시 형식 올리기 | [`DefaultTranscriptManager.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/DefaultTranscriptManager.java) |
| 도구 결과의 모양 | [`ToolUseResult.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/ToolUseResult.java) |
| 도구 선례 · 도구 컨텍스트 키 · 되찾기 | [`SessionHistoryTool.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/session/SessionHistoryTool.java) |
| `prepare` 호출 · `COMPACT` 뒤의 봉인 · 도구 결과를 한 메시지로 append · 도구 컨텍스트 | [`OrcaAgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java) |
| 오류만 이어지는 iteration 의 정지 | [`StalledIterationGuard.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/budget/StalledIterationGuard.java) |
| 병렬 디스패치의 조건 | [`DefaultParallelToolDispatcher.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/DefaultParallelToolDispatcher.java), [`ConcurrencyBehavior.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ConcurrencyBehavior.java) |
| 런타임이 모델에게 말하는 포장 | [`SystemReminderFormatter.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/prompt/SystemReminderFormatter.java) |
| 포크의 버퍼와 도구 컨텍스트 | [`DefaultSubagentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/DefaultSubagentExecutor.java) |
| 메모리 ingest | [`IngestingExecutionMemorySink.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/memory/IngestingExecutionMemorySink.java), [`IngestChunks.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/memory/IngestChunks.java) |
| 작업 목록 도구 (작업 노트와 다른 것) | [`TodoWriteTool.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/todo/TodoWriteTool.java) |
| engine 선택 | [`ContextEngineKind.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/ContextEngineKind.java), [`OrcaAgentRuntimeFactory.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeFactory.java) |
| 비교 리그 | [`ContextEngineLiveRig.java`](../../../modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/ContextEngineLiveRig.java) |
