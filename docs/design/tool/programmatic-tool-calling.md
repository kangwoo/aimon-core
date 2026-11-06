# 프로그래매틱 도구 호출 — 모델이 쓴 코드가 도구를 부르는 실행 경로

> **Status**: PARTIALLY IMPLEMENTED (B) — `aimon-core` (`at.aimon.core.agent.tool`, `at.aimon.core.programmatic`, `at.aimon.core.tools.code`),
> `aimon-graaljs` (`aimon-workflow-graaljs` 를 개명·재편), `aimon-code-engine-testkit` (새 모듈). 공개 API 호환을 깨는 항목이 있다(§5).
> 구현된 것은 서술자·제안 문맥·승인 관문·쿼터·도구 선언(조각 B)이고, `ExecuteCode` 와 엔진은 아직 없다. 구현이 본문과 다른 점은 §21.
> 셸 엔진은 [셸 엔진 문서](programmatic-tool-calling-shell.md), 코드의 도구 호출이 지나는 입구는
> [도구 호출의 단일 입구](tool-call-dispatcher.md) 가 정한다.

## 1. 목적과 범위

ReAct 루프에서는 도구 호출 하나가 LLM 왕복 한 번이고, 그 결과가 전부 컨텍스트에 쌓인다. 스무 개 호스트의 상태를 읽어 이상한
셋만 고르는 작업은 스무 번의 왕복과 스무 개의 관찰을 남긴다. 그 관찰 대부분은 다음 호출에 값을 옮겨 적는 데만 쓰인다.

**프로그래매틱 도구 호출**(programmatic tool calling, 이하 PTC)은 모델이 그 반복을 코드로 쓰게 한다. 코드가 도구를 함수로 부르고,
루프·조건·필터·집계는 코드 안에서 끝나며, **코드의 출력만** 관찰로 돌아간다. 중간 도구 결과의 본문은 트랜스크립트에 들어가지
않는다. 모델에게 가야 하는 환경 알림과 훅 피드백은 따로 모아 돌려준다(§12.1).

**코드는 AIMON 쪽에서 돈다.** 로컬 JVM 안의 GraalJS isolate 이거나, 에이전트의 `ExecutionEnvironment` 가 샌드박스일 때 그 안의
Python 이다. LLM 제공자가 호스팅하는 실행 컨테이너는 쓰지 않는다(§15).

세 문서의 나눔:

| 문서 | 정하는 것 | 이 문서 없이 성립하는가 |
|---|---|---|
| [도구 호출의 단일 입구](tool-call-dispatcher.md) | `ToolCallDispatcher`, `ToolCallOrigin`, 스킬 폴백 제거 | 성립한다 — PTC 없이도 슬래시 스킬의 관문 없는 폴백을 없앤다 |
| 이 문서 | 모델이 보는 표면, 엔진 선택, 호출 가능 집합, 코드 경로 규칙, 엔진 SPI, GraalJS 엔진, 관찰·한도·보안 모델 | 단일 입구에 기댄다 |
| [셸 엔진](programmatic-tool-calling-shell.md) | 샌드박스 안 Python 실행, 프로토콜, `RuntimeFileSystem`, 같은 uid 의 한계와 도구 범위, `aimon-sandbox` 요구 | 이 문서에 기댄다 |

| 주제 | 절 |
|---|---|
| 외부 사례와 그 수치 | §2 |
| 설계 결정 | §3 |
| 구조, 타입, 패키지 | §4 |
| 호환을 깨는 변경과 추가 | §5 |
| 모델이 보는 표면 | §6 |
| 어떤 도구가 코드에서 불릴 수 있는가 | §7 |
| 코드가 부른 도구의 경로 — 코드 경로 규칙, 승인, 쿼터 | §8 |
| 엔진 SPI | §9 |
| GraalJS 엔진 | §10 |
| 셸 엔진 요약 | §11 |
| 결과·관측 / 한도·취소 | §12, §13 |
| 보안 모델 | §14 |
| 의도적으로 제외하거나 미룬 것 | §15 |
| 설정 / 강제 / 평가 | §16 – §18 |
| 열린 질문, 참고 | §19, §20 |
| 구현 현황과 본문과 다른 점 | §21 |

정본이 따로 있는 주제:

| 주제 | 정본 |
|---|---|
| 도구 호출 관문의 순서(이하 "관문"), 스키마 검증 | [도구 계약](tool-contract.md) |
| 관문의 입구, 호출 경로 | [도구 호출의 단일 입구](tool-call-dispatcher.md) |
| 부작용 축과 실행 상한(ceiling, 이하 "실링") | [부작용 축](side-effect-axes.md) |
| 셸 SPI, 스트리밍, 취소, `RUN_DIRECTORY` | [셸 계약](shell-contract.md) |
| 환경의 수명과 소유, 샌드박스 제공자 계약 | [실행 환경](execution-environment.md) |
| 병렬 실행 게이트 | [병렬 실행](parallel-execution.md) |
| deferred 도구 | [도구 검색](tool-search.md) |
| MCP 도구의 축 매핑, `annotationTrust` | [MCP 도구 통합](../integration/mcp-tool.md) |
| GraalJS 샌드박스의 한계 | [워크플로](../workflow/workflow.md) §7 |

용어:

| 용어 | 뜻 |
|---|---|
| 데이터 도달 범위 (`dataReach`) | 도구가 닿는 데이터·네트워크가 어디까지인가 — `ENVIRONMENT` / `HOST` / `EXTERNAL` (§7.1) |
| 엔진 고유 등급 | 코드가 도구를 거치지 않고 엔진 안에서 직접 할 수 있는 일의 부작용·파괴성·도달 범위 (결정 10) |
| 셸 엔진의 용어(OpenSandbox, execd, PTC 런타임, 공존 프로세스, 전용 uid 모드) | [셸 엔진](programmatic-tool-calling-shell.md) §1 |

---

## 2. 배경 — 외부 사례

조사한 구현은 모두 같은 관찰에서 출발한다. **모델은 도구 호출 JSON 보다 코드를 훨씬 많이 보고 배웠고, 연쇄 호출에서 중간 결과를
모델에게 거쳐 보낼 이유가 없다.**

| 사례 | 코드가 도는 곳 | 도구를 부르는 방식 | 이 설계가 가져온 것 |
|---|---|---|---|
| Anthropic PTC (`code_execution_20260120`) | Anthropic 컨테이너 (Python) | 도구가 `allowed_callers` 로 opt-in. 코드가 부르면 실행이 멈추고 클라이언트에 `tool_use` 가 `caller` 와 함께 나온다. 함수는 dict 하나를 받고 **문자열**을 돌려준다. `print` 가 결과다 | opt-in(§7), 호출 경로 표시, 문자열 결과, Python 표면 |
| Anthropic "Code execution with MCP" | 에이전트 쪽 실행 환경 | 도구를 파일 트리의 코드 모듈로 노출해 필요한 정의만 읽는다 | 도구가 많을 때의 점진 노출(§18 판정 기준) |
| Cloudflare Code Mode | V8 isolate, 네트워크 차단 | MCP 스키마를 TypeScript API 로 바꾸고, 호출은 isolate 밖 감독자로 가는 RPC. `console.log` 가 결과다. 자격 증명은 감독자만 쥔다 | 인프로세스 isolate 엔진(§10), "샌드박스의 출구는 바인딩뿐", 표준 출력 = 관찰 |
| smolagents `CodeAgent` / CodeAct | 로컬 인터프리터 또는 E2B·Docker | 도구를 파이썬 함수로 주입 | 엔진을 갈아 끼우는 구조(§9) |

### 2.1 수치와 한계

| 수치 | 출처 |
|---|---|
| BrowseComp·DeepSearchQA 등 agentic search 에서 정확도 향상과 입력 토큰 감소 | PTC 문서 |
| 75개 도구 프로젝트 관리 벤치마크: 청구 입력 토큰 약 −38%, 정확도 동일 | PTC 문서 |
| 실제 트래픽, 도구 10–49개 요청: 토큰 20–40% 절감 | PTC 문서 |
| τ²-bench (턴마다 순차 호출 한두 번): 점수 동일, 비용 약 **+8%** | PTC 문서 |
| 복잡한 리서치 과제 평균 토큰 43,588 → 27,297 (−37%) | Advanced tool use (engineering 글) |

잘 맞는 경우는 많은 항목에 대한 팬아웃, 걸러 낼 수 있는 큰 결과, 반복 질의형 검색이다. 맞지 않는 경우는 순차·소량 호출, 호출
사이에 사용자 확인이 필요한 도구다. τ²-bench 의 +8% 는 스크립트 작성과 선언 노출의 **고정 비용**이다.

결론 두 가지. **PTC 는 기본값이 아니라 선택지다**(결정 1). **고정 비용을 최소로 한다** — 선언은 opt-in 한 도구에만, 언어는
하나만(결정 4, 5).

이 수치는 모두 Anthropic 컨테이너와 Claude 기준이다. 이 설계의 GraalJS·OpenAI 경로에 대한 근거는 아니며, 자체 평가(§18)로 확인한다.

### 2.2 Anthropic 문서가 꼽은 대안

| 문서의 형태 | 이 설계 |
|---|---|
| Client-side direct execution — 앱이 코드를 로컬에서 직접 실행 | GraalJS isolate (§10). 호스트 프로세스로 임의 코드를 돌리는 형태는 미룬다(§15) |
| Self-managed sandboxed execution — 샌드박스에서 실행하고, 도구 호출을 샌드박스 밖으로 돌리는 프로토콜이 필요 | [셸 엔진](programmatic-tool-calling-shell.md) |

---

## 3. 설계 결정

| # | 쟁점 | 결정 | 이유 | 기각한 대안 |
|---|---|---|---|---|
| 1 | 직접 호출과의 관계 | 직접 호출은 그대로 두고 코드 실행 도구 `ExecuteCode` 를 하나 더한다 | §2.1: 순차 작업에서는 PTC 가 더 비싸다. 모델이 경우에 따라 고른다 | 모든 호출을 코드로만 받기 (Code Mode 의 단일 도구): 순차 작업이 손해를 본다 |
| 2 | 코드가 도는 곳 | AIMON 쪽. 엔진 SPI 뒤에 GraalJS(JVM 안)와 셸 엔진(샌드박스 안 Python) | 요구가 로컬 또는 샌드박스 실행이다. 제공자 중립이라 OpenAI 경로도 같다. 데이터가 제공자 컨테이너에 남지 않는다 | Anthropic `code_execution_20260120`: §15 |
| 3 | 코드가 부른 도구의 경로 | [단일 입구](tool-call-dispatcher.md) `ToolCallDispatcher` 를 `CODE` 경로로 지난다 — 직접 호출과 **같은** 관문. 그 위에 관문 설정과 무관한 **코드 경로 규칙**(결정 12)을 더한다 | 코드 경로가 관문을 우회하면 PTC 가 권한 우회 수단이 된다. 관문은 opt-in 이라(출하되는 조립 어디에도 승인 관문이 배선되어 있지 않다) 관문에만 기대면 기본 배치에서 아무것도 막지 않는다 | 관문만으로: 승인 관문이 없는 배치에서 코드 경로 제한이 사라진다 |
| 4 | 코드에서 부를 수 있는 도구 | **명시적 opt-in 만.** 에이전트 설정 `programmatic-tools` 에 이름을 적은 도구 중 도구 쪽 상한(`codeCallable`)과 코드 경로 규칙을 통과한 것. 생략하면 `ExecuteCode` 자체가 없다 | 고정 비용(§2.1)이 opt-in 한 만큼만 생긴다. 무엇을 코드에 여는지는 조립하는 쪽이 정한다 | 기본 "`READ_ONLY` 전부": 선언이 직접 목록과 겹쳐 매 iteration 토큰이 두 번 실리고, 외부로 나가는 도구가 자동으로 딸려 온다 |
| 5 | 언어 | **실행마다 하나.** 제안 시점에 환경 종류로 고른다(§6.2). 입력에 `language` 가 없다. 고른 엔진을 쓸 수 없으면 다른 언어로 내려가지 않고 `ExecuteCode` 를 내지 않는다 | 두 언어를 함께 노출하면 선언이 두 배다. 대화 도중 언어가 바뀌면 트랜스크립트의 이전 코드 패턴을 따라 쓴 코드가 실패하고 도구 블록 캐시가 깨진다 | `language` 입력: 토큰 두 배. 실패 시 자동 강등: 샌드박스에서 GraalJS 로 내려가 호스트 JVM 메모리 위험을 다중 사용자 서버에 들인다 |
| 6 | 코드가 받는 결과 | **언제나 문자열** — PostTool 훅을 지난 텍스트 결과 | Anthropic 표면과 같아 `json.loads(await f(...))` 습관이 통한다. 기존 마스킹 훅이 코드 경로에도 그대로 효력을 갖는다 | 구조화 값 전달: 텍스트에 없는 정보(잘린 출력, MCP 의 독립된 `structuredContent`)가 훅을 비껴 코드로 간다. 미룬다(§15) |
| 7 | 관찰 | **표준 출력이 관찰이다** — Python `print`, JS `console.log`. JS 의 반환값이 `undefined` 가 아니면 출력 뒤에 붙인다. 표준 오류(`console.error`, stderr)는 로그 | 두 엔진에서 같은 모양의 프로그램이 같은 관찰을 낸다. 모델은 스크립트 끝에서 결과를 출력하는 습관이 강하다(Anthropic·Code Mode 모두 출력이 결과) | JS 는 반환값만: `console.log` 로 찍은 결과가 로그 꼬리로 밀려 잘린다 |
| 8 | 중간 결과의 기록 | 트랜스크립트에는 넣지 않는다. TOOL span, PostTool 훅, `renderPayload` 에 남긴다. 환경 알림·훅 피드백은 브리지가 `ToolDispatchResult` 에서 모아 관찰 끝에 붙인다 | 감사와 UI 는 중간 호출을 봐야 하고 모델은 보지 않아야 한다. 알림을 중첩 결과에 붙이면 코드에 흡수되어 사라진다 | 숨김 메시지로 기록: 압축·재생 경로가 모두 그것을 알아야 한다 |
| 9 | 제안 문맥 | iteration 마다 `ToolOfferContext` 를 만들어 iteration 컨텍스트에 싣는다. 제안 필터, 실링, 승인이 그 문맥의 `describe` 를 읽는다. 제안이 없는 호출자는 `ToolOfferContext.none()` | `ExecuteCode` 의 등급은 엔진과 호출 가능 집합에 달려 있다. 제안·실링·승인이 서로 다른 값을 보면 안 된다 | `Tool` 의 상수 getter 유지: `ExecuteCode` 의 등급을 정직하게 말할 수 없다 |
| 10 | `ExecuteCode` 의 등급 | `max(엔진 고유 등급, 호출 가능 집합 등급)`. GraalJS 의 고유 등급은 `READ_ONLY`·`NON_DESTRUCTIVE`·`ENVIRONMENT` 이고 `approvalDelegated=true` — 승인 관문은 `ExecuteCode` 를 묻지 않고 중첩 호출마다 판정한다. 셸 엔진의 고유 등급은 그 환경의 `Bash` 와 같고(`MUTATING`·`DESTRUCTIVE`), `ExecuteCode` 자체가 `Bash` 처럼 승인 대상이다 | 셸 엔진의 코드는 도구를 거치지 않고 작업 영역을 쓰고 지우고 프로세스를 띄운다. 그것을 호출 가능 집합 등급으로만 서술하면 `READ_ONLY` 실링 아래에서 `Bash` 와 같은 능력이 `READ_ONLY` 로 제안되고, `Bash` 에 승인을 요구하는 배치에서 무승인 임의 실행 경로가 된다 | 호출 가능 집합 등급만: 위의 실링·승인 우회. 이름 특례로 승인 생략: 선언과 실제가 다른 도구 |
| 11 | 셸 엔진 `ExecuteCode` 의 승인 키 | **`Bash` 와 같은 키를 쓴다.** `ToolDescriptor.approvalKey()` 를 두고(기본값은 도구 이름), 셸 엔진 `ExecuteCode` 는 `Bash` 를 돌려준다. 저장된 허용과 거절이 양쪽으로 공유된다. 질의 문구는 "Bash — via ExecuteCode (Python)" 처럼 어느 경로인지 밝힌다. GraalJS `ExecuteCode` 는 승인을 위임하므로(결정 10) 키가 쓰이지 않는다 | 두 도구는 같은 능력이다 — 지금 `Bash` 의 저장된 허용은 이미 "이 세션에서 임의 셸" 이다. 키를 나누면 사용자가 `Bash` 를 **거절해도** 모델이 `ExecuteCode` 로 같은 일을 다시 묻게 되고, 거기서 한 번 허용하면 `Bash` 거절이 무너진다. 같은 능력에 두 번 묻는 것은 승인 피로를 늘려 무심한 허용을 부른다 | 별도 이름 `ExecuteCode`: 거절 우회와 이중 질의. 기억하지 않음(매번 질의): 스크립트마다 묻는 피로가 같은 결과를 낳는다. 승인 관문 쪽 이름 특례: 선언 축이 아닌 이름에 정책을 묻는다 |
| 12 | 코드 경로 규칙 | 관문 설정과 무관하게 브리지가 강제한다. ① `MUTATING` 이면서 `DESTRUCTIVE` 인 도구는 호출 가능 집합에서 뺀다(`READ_ONLY` 도구의 파괴성 축은 읽지 않는다 — `Tool#getDestructiveBehavior` 의 계약). ② `MUTATING` 호출은 스크립트당 도구별 상한(기본 20)을 센다. ③ 승인 관문이 있으면 코드 경로는 **묻지 않는다** — 저장된 승인이 있으면 통과, 없으면 거절. ④ `MUTATING` 이상 도구는 `programmatic-only` 에 넣을 수 없다 | 승인 관문은 opt-in 이고 임계값도 바뀐다. 묻으면 스크립트가 사람을 기다린다. 기억된 승인을 무제한으로 타면 승인 한 번이 N 회 반복으로 증폭된다. `programmatic-only` 도구는 직접 호출이 없어 승인을 저장할 길이 없다 | 관문 위에만 짓기: 관문이 없는 배치(지금의 모든 조립)에서 공집합이 된다 |
| 13 | 호출 가능 집합의 시점 | `ExecuteCode` 가 시작할 때 동결한다. `programmatic-tools` 의 도구는 deferred 로 둘 수 없다(`EAGER` 로 다룬다). `ToolSearch` 와 `ExecuteCode` 도 deferred 불가 | 스크립트 안에서 deferred 도구를 활성화해 집합을 넓히면 결정 4·10 이 무너진다. 코드는 `ToolSearch` 를 부를 수 없고, 직접 활성화하면 직접 목록에 나타나 `programmatic-only` 와 모순된다 | 실행 중 갱신 |
| 14 | 포크의 도구 호출 상한 | 상한을 스레드 안전한 공개 `ToolCallQuota` 로 꺼내 컨텍스트에 싣고, 브리지가 중첩 호출마다 `admit(1)` 한다 | 지금은 포크 루프 스레드만 만지는 private 카운터라, `ExecuteCode` 하나가 1회로 세어져 상한을 우회한다 | `ExecuteCode` 를 N 회로 사후 정산: 상한을 넘은 뒤에야 안다 |
| 15 | 재귀와 모델 호출 | LLM 을 부르거나 포크를 일으키는 도구, 도구 집합을 바꾸는 도구, 사용자 입력을 기다리는 도구는 `codeCallable=false` 다. 지금 해당하는 것: `ExecuteCode`, `Task`, `Skill`, `WorkflowJs`, `Workflow`, `ToolSearch`, `MemoryChat`, `Wiki*`, 대화형 도구. ArchUnit 이 클래스 그래프로 검사한다(§17) | 중첩 실행·LLM 호출이 스크립트 한도와 예산 밖에서 자라고, 집합 동결(결정 13)이 깨진다. 이름 목록은 새 도구가 생길 때 빠진다 | 깊이 제한으로 허용: 예산 산정이 서브에이전트 계층과 겹친다 |
| 16 | JS 엔진의 모듈 | `aimon-workflow-graaljs` 를 `aimon-graaljs` 로 개명한다. 엔진 홀더와 설정은 공개 패키지, 컨텍스트 팩토리·워치독·마샬링은 `internal` | GraalJS 샌드박스 기반이 두 기능의 공통 부분이고, GraalJS 의존은 모듈 하나에만 둔다. 엔진 홀더는 CLI·부트스트랩이 조립하므로 공개여야 한다 | 기존 모듈에 끼워 넣기: 이름이 내용과 어긋난다. 엔진 홀더까지 `internal`: `InternalPackageBoundaryTest` 가 조립 코드를 막는다 |

---

## 4. 구조

```
 LLM ── tool_use: ExecuteCode{ code } ──▶ execution loop (main / fork / slash-skill)
                                              │ ToolCallDispatcher (path = DIRECT)
                                              ▼
                                        ExecuteCodeTool
                                              │ engine chosen at offer time
                         ┌────────────────────┴─────────────────────┐
                         ▼                                          ▼
            GraalJsCodeExecutionEngine                  ShellCodeExecutionEngine
            (in-JVM isolate, JavaScript)                (python3 in the sandbox; shell doc)
                         │ tools.X(args)                            │ authenticated lines / replies
                         └──────────────────┬───────────────────────┘
                                            ▼
                                      ToolCallBridge
                    (frozen callable set, code-path rules, quota, lanes,
                     child cancel, CLOSED state, notice/feedback collection)
                                            │ ToolCallDispatcher (path = CODE)
                                            ▼
            gates: allow-list, ceiling, PermissionRequest, approval (never asks),
                   PreTool, execute, PostTool (rewrites the text the code gets)
```

| 타입 | 패키지 | 수명 |
|---|---|---|
| `ToolDescriptor`, `ToolOfferContext`, `OfferKey`, `DataReach`, `ToolCallQuota` | `at.aimon.core.agent.tool` | 쿼터는 실행 단위, 제안 문맥은 iteration 하나 |
| `CodeExecutionEngine`, `CodeExecutionRequest`, `CodeExecutionOutcome`, `EngineAvailability`, `ToolCallBridge` | `at.aimon.core.programmatic` (중립 SPI) | 엔진은 Application, 브리지는 `ExecuteCode` 호출 하나 |
| `ExecuteCodeTool`, `ToolApiRenderer`, 제안 문맥의 환경 키 | `at.aimon.core.tools.code` | 상태 없음 |
| `ShellCodeExecutionEngine` | `at.aimon.core.tools.code.shell` ([셸 엔진](programmatic-tool-calling-shell.md)) | Application |
| `GraalJsCodeExecutionEngine` | `at.aimon.graaljs.code` | Application |
| `GraalJsEngineHolder`, `JsSandboxConfig` | `at.aimon.graaljs` (공개) | Application |
| `JsContextFactory`, 워치독, 마샬링, 이벤트 루프 | `at.aimon.graaljs.internal` | Application. `InternalPackageBoundaryTest` 로 `at.aimon.graaljs..` 안에서만 |

`ToolCallDispatcher`, `ToolDispatchResult`, `ToolCallOrigin` 은 [단일 입구](tool-call-dispatcher.md) 문서의 타입이다.

**패키지 규칙.** `PackageDependencyArchitectureTest` 는 `at.aimon.core.agent..` 가 `environment`·`tools`·`hook` 에 의존하는 것을 막는다.
그래서 (a) `ToolOfferContext` 는 환경을 직접 들지 않고 `ToolContext` 처럼 타입 키로 속성을 싣는다(`<T> Optional<T> get(OfferKey<T>)`).
환경 관련 키(서술자, 셸 기능, 런타임 FS 유무)는 `tools.code` 가 정의한다. (b) 엔진 SPI 는 `agent..` 밖의 `at.aimon.core.programmatic` 에
두고 `aimon-graaljs` 는 그 패키지에만 의존한다.

`ExecuteCode` 실행은 도구 호출 하나 안에서 끝나고 노드를 넘어 살아남을 상태가 없다. 엔진이 들고 있는 것은 환경 객체에 붙인 탐침
결과뿐이다(약한 참조). 멀티 인스턴스 원칙과 충돌하지 않는다. 엔진 홀더의 생성·종료는
[`scope-model.md`](../../overview/scope-model.md) 의 Application 행에 추가한다. 지금은 WorkflowJs 가 켜졌을 때만 만들어지므로, 두
기능 중 하나라도 켜지면 만들도록 조립을 바꾼다.

---

## 5. 호환을 깨는 변경과 추가

호환을 깨는 것은 허용이지 의무가 아니다. 아래는 PTC 의 결정 하나를 성립시키는 데 필요한 것만 남겼다. 단일 입구의 변경은
[그 문서](tool-call-dispatcher.md) §6, 셸 엔진의 변경은 [그 문서](programmatic-tool-calling-shell.md) §3 에 있다.

### 5.1 깨는 것

| 변경 | 전 | 후 | 필요한 결정 |
|---|---|---|---|
| 축을 읽는 곳의 입력 | `Tool` 의 상수 getter 와 정적 `getDefinition()` 을 직접 읽음 | 제안 문맥의 `describe` 결과를 읽음 — `OrcaAgentExecutor`(제안), `DefaultSubagentExecutor`(제안), `LlmSkillExecutor`(제안), `DefaultToolExecutionManager`(실링 거절), `SideEffectApprovalGate`, `RoutineExecutor`(재시도). 병렬 게이트와 `DedicatedThreadDispatcher` 는 동시성·인터럽트 축만 읽고 `ExecuteCode` 는 그 축을 상수로 두므로 바꾸지 않는다 | 9, 10 |
| 승인 관문 | `denialReason(tool, toolUse, context)`, 선언 축만 봄, 기억은 도구 이름 단위 | 호출 경로와 서술자를 받는다. `approvalDelegated` 도구는 묻지 않고, 코드 경로는 묻지 않는다. 기억의 키는 `approvalKey()` 다 | 10, 11, 12 |
| 모듈 | `aimon-workflow-graaljs`, `at.aimon.workflow.graaljs` | `aimon-graaljs`, `at.aimon.graaljs.{workflow,code,internal}` | 16 |

### 5.2 추가하는 것

| 추가 | 내용 |
|---|---|
| `Tool.describe(ToolOfferContext)` | default 메서드. 기존 getter 와 `getDefinition()` 을 조합해 `ToolDescriptor` 를 만들고 문맥은 무시한다. `describe` 를 재정의하는 도구는 `ExecuteCode` 하나. `getDefinition()` 은 정적 스키마를 돌려주고 동적 설명은 `describe` 만 준다 — 검색 카탈로그·권한 검사기는 정적 정의를 그대로 읽는다 |
| `Tool.isCodeCallable()`, `Tool.dataReach()` | default getter. `isCodeCallable()` 기본 `true` (`DEDICATED_THREAD` 는 `false`), `dataReach()` 기본 `EXTERNAL` — 다른 축의 보수적 기본값(`MUTATING`, `DESTRUCTIVE`)과 같은 방향이다. 내장 도구는 각자 선언한다(§7.1) |
| `ToolDescriptor` | 기존 축 + `codeCallable` + `dataReach` + `approvalDelegated` + `approvalKey` (기본 도구 이름) + 문맥이 반영된 `ToolDefinition` |
| `ToolOfferContext.none()` | 제안이 없는 호출자용. 그 문맥에서 `ExecuteCode` 는 제안 불가, 최대 등급으로 답한다 |
| `McpTool` 의 `dataReach` | `annotationTrust=IGNORE` 이면 `EXTERNAL`. `TRUST` 이면 `openWorldHint=false` 일 때 `HOST`, 힌트가 없거나 `true` 이면 `EXTERNAL`. MCP 도구는 원격 서버에서 돌므로 `ENVIRONMENT` 가 될 수 없다. 이로써 [MCP 도구 통합](../integration/mcp-tool.md) D13 이 미룬 네 번째 축(백로그 TC-15)의 소비자가 생긴다 — D13 과 [부작용 축](side-effect-axes.md) 의 `openWorldHint` 행을 함께 고친다 |
| `ToolCallQuota` | 공개, 스레드 안전. `ToolContextKeys.TOOL_CALL_QUOTA` (write-once — enricher 가 무제한 쿼터로 바꿔 끼우지 못하게) |
| `ToolContextKeys.TOOL_OFFER_CONTEXT` | write-once 키. `ToolContextKey.BUILT_IN_WRITE_ONCE_NAMES` 와 `ToolContextKeysWriteOnceTest` 에 추가한다. iteration 마다 `putAll(턴 컨텍스트).put(TOOL_OFFER_CONTEXT, …)` 로 iteration 컨텍스트를 지어 싣는다 — 턴 컨텍스트에는 그 키를 넣지 않는다. 실링 거절을 하는 `DefaultToolExecutionManager` 는 `agent.tool` 에 있어 `tools` 를 볼 수 없으므로, 키의 정의는 `ToolOfferContext.CONTEXT_KEY` 이고 `ToolContextKeys.TOOL_OFFER_CONTEXT` 는 같은 키다. 읽는 쪽은 `ToolOfferContext.of(ToolContext)` (없으면 `none()`) |
| `aimon-code-engine-testkit` | 엔진 계약 스위트. `aimon-core` 에 `java-test-fixtures` 를 쓸 수 없으므로 별도 모듈이고, `aimon-sandbox` 가 돌려야 하므로 배포 대상이다(§17) |

개명은 [`rename-maps.md`](../../migration/rename-maps.md) 에 행을 더하고, 모듈 목록과 "옛 좌표는 Central 에 남는다" 는 NOTE 를
CLAUDE.md 에 적는다(샌드박스 분리 때와 같은 형식). 개명이 닿는 곳: `settings.gradle.kts`, `gradle/coverage-baselines.properties`,
`aimon-cli` 의 `AgentSetupFactory` · `GraalJsWorkflowToolProvider` · `CliSettings` · `default-config.yaml` 과 테스트,
`TurnVocabularyArchitectureTest` 의 주석, `README.md` · `MAINTAINERS.md` · `CONTRIBUTING.md`(+`.ko.md`) · `CHANGELOG.md` ·
`docs/README.md`(+`.en.md`) · `docs/overview/architecture.md`(+`.en.md`). 정본과 번역본을 같은 커밋에서 고친다.

`aimon-bootstrap` 과 스타터는 GraalJS 를 직접 참조하지 않는다. `aimon-graaljs` 가 `ServiceLoader` 로 엔진 팩토리를 내고, 조립 쪽은
`at.aimon.core.programmatic` SPI 만 본다.

---

## 6. 모델이 보는 표면

### 6.1 도구

| 필드 | 값 |
|---|---|
| 이름 | `ExecuteCode` |
| 입력 | `code` (문자열), `timeout_seconds` (선택, 상한 이하) |
| 부작용·파괴성·데이터 도달 범위 | 결정 10 — `max(엔진 고유 등급, 호출 가능 집합 등급)` |
| 승인 | GraalJS: 위임. 셸 엔진: `Bash` 와 같은 키(결정 11) |
| 코드에서 호출 / deferred | 불가 / 불가 |
| 동시성 / 인터럽트 | `SEQUENTIAL` / `COOPERATIVE` (§13.2) |

`programmatic-tools` 에 통과한 도구가 하나도 없거나, 이번 환경에서 쓸 엔진이 없으면 `ExecuteCode` 는 제안되지 않는다. 그 이유는 운영자에게
보인다(§12.2).

### 6.2 엔진 선택

제안 시점에 `ToolOfferContext` 가 환경의 정적 정보를 알므로 거기서 고른다. **제안 시점에는 셸 명령이나 파일 쓰기를 하지 않는다**
(샌드박스가 프로비저닝되지 않게).

| `programmatic-engine` | 고르는 규칙 |
|---|---|
| `auto` (기본) | `isolation()=HOST` 이면 `javascript`, `SANDBOXED` 이면 `python`. 고른 엔진의 정적 조건을 못 맞추면 `ExecuteCode` 없음 |
| `javascript` | GraalJS 엔진만. 샌드박스 환경에서도 쓸 수 있다 — 코드는 호스트 JVM 에서 돌고 도구는 샌드박스 환경으로 간다. 다중 사용자 서버에서는 §14 의 메모리 위험을 받아들이는 선택이다 |
| `python` | 셸 엔진만. `SANDBOXED` 환경에서만 |

`auto` 의 근거는 §14 다. 샌드박스에서는 Python 이 샌드박스 자원 한도 안에서 돈다. 로컬에서는 isolate 가 호스트 프로세스보다 낫다.

정적 조건: GraalJS 는 `aimon-graaljs` 가 조립되었을 것. 셸 엔진의 정적 조건과 탐침은 [셸 엔진](programmatic-tool-calling-shell.md) §4 다
— 그중 하나가 "`Bash` 를 제약 없이 쓸 수 있다" 이다(결정 10, 11 과 같은 이유).

언어는 환경 종류(`isolation()`)가 바뀌지 않는 한 바뀌지 않는다. 같은 세션이 실행마다 다른 종류의 환경에서 돌면 언어가 바뀔 수 있고,
그때는 그 iteration 의 관찰에 "ExecuteCode now runs JavaScript (was Python)" 를 환경 알림으로 싣는다.

### 6.3 설명

`ExecuteCode` 의 설명은 `describe` 가 실행마다 계산한다(`DynamicToolDefinitionProvider` 는 인자 없는 Supplier 라 문맥을 볼 수 없다).

1. **사용 지침.**
   - 언제 쓰고 언제 쓰지 말지 (§2.1 을 줄인 것)
   - 결과는 문자열이고, 반환 형식은 각 함수 설명에 적혀 있다는 것
   - **표준 출력이 관찰이다** (`print` / `console.log`), 표준 오류는 로그
   - 관찰 상한(§12.1)과 잘림 표식
   - 팬아웃에는 `Promise.allSettled` / `asyncio.gather(..., return_exceptions=True)` 를 쓰라는 것. 서로 의존하지 않는 호출은 병렬로 —
     셸 엔진은 호출당 고정 지연이 있다
   - 쓰기 도구는 스크립트당 도구별 20회까지라는 것, 승인이 필요한 도구는 직접 호출로 쓰라는 것
   - 도구 결과를 코드로 실행하지 말라는 것
2. **함수 선언.** `ToolApiRenderer` 가 `inputSchema` 와 도구 설명에서 생성한다. 도구 이름 순으로 정렬해 결정론적으로 만든다.

```ts
// JavaScript — 생성 결과의 모양
/** Search file contents with a regular expression. Returns matching lines as "path:line:text". */
tools.Grep(args: { pattern: string; path?: string; glob?: string }): Promise<string>
```

```python
# Python — 생성 결과의 모양
async def Grep(args: dict) -> str:
    """Search file contents with a regular expression. Returns matching lines as "path:line:text".

    args:
      pattern (str): regular expression
      path (str, optional): directory to search
      glob (str, optional): file filter
    """
```

이름이 식별자가 될 수 없으면(`mcp__server__tool` 의 하이픈 등) `tools["..."]` / `call_tool("...", args)` 로 부른다.

**토큰.** `programmatic-only` 에 든 도구는 직접 목록에서 빠지므로 스키마가 한 번만 실린다. 직접 목록에도 남는 도구는 코드 쪽에
시그니처 한 줄과 "인자는 직접 도구 `Grep` 과 같다" 만 렌더한다. 설명 크기와 그에 따른 판정은 §18.

---

## 7. 어떤 도구가 코드에서 불릴 수 있는가

### 7.1 도구 쪽 선언

- `isCodeCallable()` — 결정 15 의 성질을 가진 도구는 `false` 로 재정의한다. "이 도구를 코드에서 부르면 계약이 깨진다" 는 선언이지
  정책이 아니다.
- `dataReach()` — 도구가 닿는 데이터·네트워크의 범위.

| 값 | 뜻 | 예 |
|---|---|---|
| `ENVIRONMENT` | 실행 환경 안에서 돌고, 환경이 원래 닿는 것 이상에 닿지 않는다 | `Read`, `Grep`, `Write`, `Edit`, `Bash`, `BashOutput` |
| `HOST` | 호스트 쪽 데이터 저장소나 호스트의 자격 증명으로 닿는 닫힌 시스템 | `MemorySearch`, `MemoryRecall`, `KnowledgeSearch`, `SessionHistory`, `TaskList`, 신뢰한 사내 MCP 읽기 도구 |
| `EXTERNAL` (기본) | 열린 외부 세계 | `WebFetch`, `WebSearch`, 신뢰하지 않은 MCP 도구 |

내장 도구는 모두 `dataReach()` 를 명시적으로 선언한다. §17 이 그것을 검사한다. 위 예에 없는 내장 도구의 값: `KillShell`·`MonitorStop` 과
아티팩트 래퍼 `Edit`·`Write` 는 `ENVIRONMENT`. 스케줄·태스크 조회·`TodoWrite`·`ToolSearch`·`ConsoleOutput`·`Observe`·`MemoryChat`·`Wiki*`·
메모리 deriver 도구는 `HOST`. `Monitor`(`ws`·등록 소스가 임의 엔드포인트에 닿는다)와 포크를 일으키는 `Task`·`Workflow`·`WorkflowJs`·`Skill`
(포크가 허용 목록 안의 어떤 도구든 쓸 수 있다)은 `EXTERNAL`.

### 7.2 에이전트 설정

```yaml
programmatic-tools:        # 코드에서 부를 수 있는 도구. 정확한 이름. 생략하면 PTC 없음
  - Read
  - Grep
  - mcp__prometheus__query
programmatic-only:         # 직접 제안에서 빼고 코드에서만
  - mcp__prometheus__query
programmatic-engine: auto  # §6.2
shell-engine-tool-scope: host  # 선택. 애플리케이션 값보다 좁게만 (셸 엔진 §12.2)
```

이 예시의 `mcp__prometheus__query` 는 그 서버에 `annotationTrust: TRUST` 가 설정되어 있고 도구가 `readOnlyHint: true`,
`openWorldHint: false` 를 선언한다는 전제다. 기본 신뢰(`IGNORE`)에서 MCP 도구는 `MUTATING`·`DESTRUCTIVE`·`EXTERNAL` 로 서술되므로
코드 경로 규칙에서 빠지고 `programmatic-only` 에도 넣을 수 없다.

키는 에이전트 frontmatter 의 kebab-case 관례(`allowed-tools`)를 따른다. 이름은 정확히 일치해야 한다 — `AllowedTool` 의 매칭이 정확
일치이고, 와일드카드는 MCP 서버가 도구를 더할 때 PTC 집합이 몰래 넓어지게 만든다. `programmatic-only` 는 `programmatic-tools` 의
부분집합이어야 하고 `MUTATING` 이상 도구를 담을 수 없다(결정 12 ④).

**검사 시점.** frontmatter 를 파싱할 때 알 수 있는 것(부분집합, 이름 형식)은 로딩 시점에 거절한다. 도구의 축은 레지스트리 등록 뒤에야
알 수 있다 — MCP 도구는 서버에 연결해 `tools/list` 를 받은 뒤다. 그래서 축에 기대는 검사(④, 범위 밖 도구)는 **도구 등록 시점과 제안
시점**에 하고, 어기면 그 도구를 빼고 에이전트·이유마다 한 번 경고한다.

**결과적으로 코드에서 쓸 수 있는 내장 도구.** 내장 도구 중 `NON_DESTRUCTIVE` 를 선언한 것은 지금 없다. 그래서 결정 12 ① 에 따라
코드 경로에서는 `READ_ONLY` 도구만 쓸 수 있다(파괴성 축은 `MUTATING` 일 때만 읽히므로 `Read`·`Grep` 은 남는다). 쓰기 도구를 코드에
열려면 그 도구의 파괴성을 감사해 `NON_DESTRUCTIVE` 를 선언해야 한다(백로그 TC-7 의 인트리 도구 감사와 같은 작업).

### 7.3 호출 가능 집합

다음 순서로 계산한다.

| 순서 | 거르는 것 | 출처 |
|---|---|---|
| 1 | 제안 자격 — 허용 목록, 실링. `programmatic-only` 를 직접 목록에서 빼기 **전에** 계산한다 | `ToolOfferContext` |
| 2 | `isCodeCallable()` | §7.1 |
| 3 | `programmatic-tools` | §7.2 |
| 4 | 코드 경로 규칙 ① — `MUTATING` ∧ `DESTRUCTIVE` 제외 | 결정 12 |
| 5 | `shell-engine-tool-scope` — 셸 엔진이 전용 uid 모드 없이 돌 때만 | [셸 엔진](programmatic-tool-calling-shell.md) §12.2 |

집합은 `ExecuteCode` 시작 시 동결된다(결정 13). 계산 순서는 "다른 도구의 `describe` → 호출 가능 집합 → `ExecuteCode.describe`" 이고,
`ExecuteCode` 는 자기 자신을 집합에서 뺀다. 역방향 의존은 없다.

`programmatic-only` 도구를 모델이 직접 부르면(설명에 이름이 보이므로 가능하다) 디스패처가 `DIRECT` 경로에서 거절한다. 제안 필터와
같은 출처를 읽는다 — 필터와 거절이 같은 값을 봐야 한다는 메인 루프의 원칙과 같다.

---

## 8. 코드가 부른 도구의 경로

### 8.1 입구와 결과

코드의 도구 호출은 [단일 입구](tool-call-dispatcher.md) 의 `ToolCallDispatcher.dispatch(toolUse, ToolCallOrigin.code(parentId, language), context)`
를 지난다. 브리지는 그 결과 `ToolDispatchResult` 에서 `text`·`isError` 를 엔진에 넘기고, `notices`·`hookFeedback` 은 모아 관찰 끝에
붙인다(결정 8). 직접 경로처럼 결과 텍스트에 붙이지 않는다.

### 8.2 중첩 호출의 정체성

| 항목 | 값 |
|---|---|
| tool use id | `<ExecuteCode 의 tool use id>#<순번>` |
| 컨텍스트 | 부모 컨텍스트를 그대로 물려받고 write-once 가 아닌 키(`CURRENT_TOOL_USE_ID_KEY`, `CALLER_ALLOWED_TOOLS`, 취소 신호)만 덮는다 — 직접 경로의 `SingleToolInvoker` 와 같은 방식이다. 취소 신호는 브리지의 자식 신호(§13.2) |
| 허용 목록 | `AllowedTools.intersect(부모, 동결된 집합)`. 교집합이 비면(`Optional.empty()`) 그 호출을 거절한다 — 빈 목록은 "무제한" 으로 읽히기 때문이다 |

### 8.3 코드 경로 규칙과 쿼터

브리지가 dispatch 전에 강제한다. 관문이 있든 없든 같다(결정 12).

| 규칙 | 동작 |
|---|---|
| `MUTATING` ∧ `DESTRUCTIVE` 제외 | 호출 가능 집합 계산에서 뺀다. 설명에 나오지 않고, 이름으로 불러도 거절된다. `READ_ONLY` 도구는 파괴성 축이 기본값이어도 남는다 |
| `MUTATING` 상한 | 스크립트당 도구별로 센다(기본 20). 넘으면 그 도구의 이후 호출을 "limit 20 per script for <tool>; 20 already applied" 로 거절하고, 관찰 요약에 거절 수를 싣는다 |
| 승인 관문이 있을 때 | 코드 경로는 질의하지 않는다. PermissionRequest 훅과 PreTool 훅의 `ASK` 도 묻지 않고 거절로 해석한다(관문 설정과 무관 — `DefaultHookExecutionManager`). 저장된 승인이 있으면 통과, 없으면 "직접 호출로 먼저 승인받으라" 로 거절한다. 기억은 `approvalKey()` 로 찾는다 |
| 포크 쿼터 | `ToolCallQuota.admit(1)`. 거절되면 스크립트당 호출 상한을 넘었을 때와 똑같이 **즉시** 실행을 멈추고 `LIMIT_EXCEEDED` 로 끝낸다. 쿼터에 초과를 표시하므로 포크는 다음 iteration 에서 `TOOL_CALL_BUDGET_EXCEEDED` 로 끝난다 |

---

## 9. 엔진 SPI

`at.aimon.core.programmatic` 의 중립 SPI 다.

```java
public interface CodeExecutionEngine {
    /** "javascript" or "python". */
    String language();

    /** Offer time. Static facts only: no shell command, no file write (§6.2). */
    EngineAvailability staticAvailability(OfferFacts facts);

    /** First execution per environment object. May run commands; the engine caches by object identity. */
    EngineAvailability probe(ExecutionEnvironment environment);

    /** Intrinsic grade of code run by this engine, before the callable set is folded in (decision 10). */
    ToolGrade intrinsicGrade();

    /** Never throws for script errors; those are in the outcome. Throws only on a broken host. */
    CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context);
}
```

| 타입 | 담는 것 |
|---|---|
| `OfferFacts` | 서술자의 `isolation()`·런타임 능력, 셸 기능, 런타임 FS 유무, `Bash` 를 제약 없이 쓸 수 있는가(셸 엔진 §4) |
| `EngineAvailability` | 가용 여부와 불가 이유. 정적 불가는 제안에서 빠지고 운영자에게 경고로 간다. 탐침 불가는 그 `ExecuteCode` 호출의 오류로 한 번 모델에게 간다 |
| `ToolGrade` | 부작용·파괴성·데이터 도달 범위·`approvalDelegated`·`approvalKey` |
| `CodeExecutionRequest` | 코드, 동결된 호출 가능 집합의 `ToolDefinition`, 한도(§13.1), 취소 신호 |
| `ToolCallBridge` | `callAsync(name, Map<String,Object> input) -> CompletableFuture<ToolDispatchResult>`. 스레드 안전 |
| `CodeExecutionOutcome` | `stdout`, `logsTail`, `status` (`COMPLETED` / `SCRIPT_ERROR` / `TIMED_OUT` / `CANCELLED` / `LIMIT_EXCEEDED` / `PROTOCOL_VIOLATION`), 중첩 호출 기록 |

브리지가 엔진과 무관한 공통 부분을 소유한다 — 동결된 집합 검사, 코드 경로 규칙, 쿼터, 동시성 차선(§13.1), 순번, 자식 취소 신호,
`CLOSED` 상태(§13.2), 디스패처 호출, 알림·피드백 수집, 호출 기록. 엔진은 "언어 런타임 안의 함수 호출을 `bridge.callAsync` 로 옮기는
일" 만 한다.

엔진은 `OrcaToolProvider` 로 등록되지 않는다. `ExecuteCodeTool` 이 생성자로 엔진 목록을 받고, CLI `AgentSetupFactory` 와 부트스트랩·
스타터가 `ServiceLoader` 로 찾은 엔진을 조립한다(§5.2).

---

## 10. GraalJS 엔진 — 로컬 JVM

### 10.1 샌드박스

`JsContextFactory` 의 정책을 쓴다 — `HostAccess.NONE`, 호스트 클래스 조회·로딩 없음, `IOAccess.NONE`, 스레드·프로세스·네이티브
접근 없음, `EnvironmentAccess.NONE`, `PolyglotAccess.NONE`, `statementLimit`. 공유 `Engine`, 취소와 마감은 워치독.

**내장 출력 끄기.** 지금 컨텍스트는 `out`/`err` 를 지정하지 않고 `js.print`·`js.console` 도 끄지 않으므로, 내장 `print`·`console` 이
엔진 기본 출력(JVM `System.out`)으로 간다. 컨텍스트에 상한 있는 `out`/`err` 싱크를 붙이고 `js.print=false`, `js.console=false` 로 끈 뒤,
자체 `console` 을 설치한다 — `console.log` 는 관찰 버퍼로, `console.error` 는 로그 버퍼로. 같은 문제가 `WorkflowJs` 에도 있으므로
`internal` 에서 함께 고친다.

**샌드박스의 출구는 `tools` 바인딩과 두 버퍼뿐이다.**

### 10.2 바인딩과 이벤트 루프

`tools` 는 `ProxyObject` 이고 각 멤버는 `ProxyExecutable` 이다. 부르면 JS `Promise` 를 돌려준다.

- **인자 복사는 예산을 들고 걷는다.** 노드 수와 누적 바이트 예산(1 MiB)을 들고 깊이 우선으로 복사하며, 배열은 할당 **전에**
  `getArraySize()` 를 예산과 비교한다. 넘으면 즉시 `ToolError`. 지금의 `JsMarshalling.deepDetach` 는 깊이(128)만 보고 배열을 크기대로
  선할당하므로, `new Array(2**31-1)` 하나나 공유 참조 DAG(깊이 100 에 노드 2¹⁰⁰)로 호스트 힙을 고갈시킬 수 있다 — 그 구현을 이 방식으로
  바꾼다.
- 호출은 `bridge.callAsync` 로 넘어가고, 완료는 소유 스레드의 이벤트 큐에 들어간다. **이 이벤트 루프는 새 구성요소다** — 지금의 워크플로
  바인딩은 모두 동기 호스트 조인이다. 소유 스레드는 `poll(timeout)` 으로 큐를 기다리며 매번 취소 신호와 마감을 확인한다. 워치독이
  컨텍스트를 닫는 것만으로는 큐에서 블록된 소유 스레드를 깨우지 못하기 때문이다(`GraalJsWorkflowScript` 의 기존 주석과 같은 이유).
- 결과는 JS 문자열이다. 호스트 객체가 샌드박스로 들어가지 않는다. 오류 결과는 `ToolError` 로 reject 된다.
- 반환값은 게스트 안에서 먼저 자른다(`String(v).slice(0, max + 1)`) — 거대한 문자열을 호스트로 통째로 복사한 뒤 자르지 않는다.
- 스크립트 하나가 isolate 로 들이는 도구 결과의 누적 바이트에 상한(16 MiB)을 둔다.

### 10.3 한계

CE 에서는 **게스트 힙**과 CPU 시간에 하드 한도가 없다([워크플로](../workflow/workflow.md) §7.3). PTC 의 작성자는 모델이고, 모델은
도구 결과에 섞인 인젝션의 영향을 받을 수 있다. 이 엔진은 **권한 상승과 호스트 쪽 복사로 인한 고갈은 막고, 게스트 힙 고갈로 노드를
쓰러뜨리는 것은 막지 못한다**(§14). 워크플로가 정한 규칙대로, 이 엔진이 조립되면 시작 시 "하드 메모리 한도 없음" 을 경고로 남긴다.

---

## 11. 셸 엔진 요약

[셸 엔진](programmatic-tool-calling-shell.md) 이 정본이다. 이 문서의 결정과 닿는 점만 적는다.

| 항목 | 요약 |
|---|---|
| 도는 곳 | `SANDBOXED` 환경 안의 `python3`. 로컬 셸 엔진은 미룬다(§15) |
| 등급과 승인 | `Bash` 와 같다(결정 10, 11). `Bash` 를 제약 없이 쓸 수 있는 에이전트에만 제안된다 |
| 통로 | stdout(execd 스풀)은 공개 통로로 보고 모든 줄을 HMAC·연속 seq 로 인증한다. 응답은 `RuntimeFileSystem.writeNew` 로 쓴 HMAC 파일 |
| 한계 | 같은 uid 공존 프로세스를 막지 못한다. 코드에 여는 도구의 범위를 `shell-engine-tool-scope`(기본 `environment`)로 묶는다 |
| 위반 | `PROTOCOL_VIOLATION`(`notRetryable`) |

---

## 12. 결과와 관측

### 12.1 관찰

```
<stdout, cut at max-result-chars (default 20,000) with "[truncated N chars]">
<JS only: return value, if not undefined>

[environment]               # notices collected from nested calls, deduplicated
...
[hook feedback]             # feedback collected from nested calls, deduplicated, capped
...
[logs]                      # only when there were logs; last 4,000 chars
...
[ExecuteCode: 14 tool calls (Read x12, Grep x2), 1 error, 2 refused by write cap, 3.2s]
```

상태가 `COMPLETED` 가 아니면 `ToolResult.error` 로 돌려준다. 메시지에는 상태, 스크립트 오류의 마지막 줄, 그때까지의 로그 꼬리,
호출 요약을 싣는다. `TIMED_OUT` · `LIMIT_EXCEEDED` 는 재시도할 수 있다 — 모델이 더 작게 나눠 다시 쓸 수 있다. `PROTOCOL_VIOLATION` 은
`notRetryable` 이다.

### 12.2 기록과 운영자 신호

| 대상 | 무엇이 남는가 |
|---|---|
| 트레이스 | 중첩 호출마다 TOOL span. 부모는 `ExecuteCode` span, 속성 `tool.path=code`. iteration span 에 `ExecuteCode` 를 제안하지 못한 이유 `ptc.unavailable_reason` |
| 훅 | 중첩 호출마다 PreTool/PostTool (경로 포함) |
| UI | `ExecuteCode` 결과의 `renderPayload` 에 코드와 중첩 호출 목록(이름, 입력 요약, 결과 길이, 오류 여부). LLM 으로 직렬화되지 않는다 |
| 트랜스크립트 | `ExecuteCode` 의 tool_use(코드 포함)와 §12.1 의 관찰 |
| 로그 (WARN, 한 번씩) | 런타임 등록 시 `programmatic-tools` 가 있는데 조립된 엔진이 없음 · 에이전트·환경마다 엔진 정적 불가 이유 · 범위·코드 경로 규칙으로 빠진 도구 · 에이전트가 애플리케이션 범위를 넓히려 함 |
| 로그 (WARN, 시작 시) | GraalJS 하드 메모리 한도 없음 · `shell-engine-tool-scope=unrestricted` |
| 로그 (ERROR) | `PROTOCOL_VIOLATION` |

**모델 유래 문자열을 로그에 남기기 전에 제어 문자를 이스케이프한다**(`\p{Cc}` → `\xNN`, 줄당 상한, 실행당 줄 수 상한). 스크립트 로그,
오류 메시지, 중첩 호출 입력 요약이 모두 대상이다. 로그가 터미널로 가는 CLI 에서 ANSI 이스케이프로 화면을 위조하거나 가짜 로그 줄을
만들지 못하게 한다.

압축(`TimeBasedMicrocompact`)은 `ExecuteCode` 결과를 다른 도구 결과와 똑같이 다룬다.

---

## 13. 한도·취소·예산

### 13.1 한도

| 한도 | 기본값 | 넘으면 |
|---|---|---|
| 벽시계 | 120초, 설정 상한 600초. `EXECUTION_DEADLINE` 이 더 가까우면 그것 | `TIMED_OUT`, 엔진 정지 |
| 중첩 호출 수 | 200 | **즉시** 실행 정지, `LIMIT_EXCEEDED` |
| `MUTATING` 호출 | 스크립트당 도구별 20 | 그 도구의 이후 호출 거절 (§8.3) |
| 동시 중첩 호출 | 8 | 대기 (브리지 큐) |
| JS 문장 수 | 10M | `LIMIT_EXCEEDED` |
| 인자 / 응답 크기 | GraalJS 1 MiB, 셸 엔진 64 KiB 줄 / 1 MiB | 인자는 거절, 응답은 잘라서 표시 |
| isolate 로 들어오는 누적 결과 (GraalJS) | 16 MiB | `LIMIT_EXCEEDED` |
| 스크립트 출력 (stdout + stderr) | 4 MiB | 실행 정지. 셸 엔진은 런타임 감시 스레드, GraalJS 는 상한 있는 버퍼 |
| 관찰 결과 / 로그 | 20,000 / 4,000 문자 | 자르고 표시 |

**동시성 차선**: 브리지는 도구의 `ConcurrencyBehavior` 를 따른다. `CONCURRENT_SAFE` 도구만 동시에 돌고, `SEQUENTIAL` 도구는 한
차선으로 줄을 선다. [병렬 실행](parallel-execution.md) 의 "모델 의도" 가 여기서는 `Promise.all` · `asyncio.gather` 이고, "프레임워크
안전성" 은 같다.

### 13.2 취소

브리지는 부모 취소 신호에 연결된 **자식 취소 소스**를 갖는다. 중첩 호출에는 부모 신호가 아니라 이 자식 신호가 간다.

| 일 | 결과 |
|---|---|
| 부모 취소 | 자식이 함께 걸린다. 엔진 정지, 결과 `CANCELLED` |
| 어떤 이유로든 스크립트가 끝남 (완료, 오류, 타임아웃, await 하지 않은 Promise·태스크) | 자식 신호를 걸고, **모든** 중첩 호출이 끝날 때까지 기다린다(상한 = 남은 벽시계). 직접 경로의 `SEQUENTIAL` 도구가 끝날 때까지 루프를 막는 것과 같은 의미다. 상한을 넘으면 관찰에 "still running when the script ended: <도구 이름들>" 을 적는다 |
| 브리지 `CLOSED` | 실행이 끝나면 먼저 닫는다. 그 뒤 도착한 결과는 엔진에 넘기지 않고 기록·span 에만 남긴다 — 셸 엔진에서 읽을 런타임이 없는 호스트 결과가 샌드박스에 남지 않게 |
| 엔진 정지 | GraalJS 는 워치독이 컨텍스트를 닫고 이벤트 루프의 `poll` 이 신호를 본다. 셸 엔진은 셸 취소로 프로세스 트리를 끝낸다(best-effort) |

### 13.3 예산·쿼터

- 중첩 호출은 iteration 이 아니다. `BudgetTracker.recordIteration` 을 부르지 않는다.
- 포크의 도구 호출 상한은 중첩 호출을 센다(결정 14, §8.3).
- LLM 을 부르는 도구는 `codeCallable=false` 이므로(결정 15, ArchUnit 검사) 중첩 호출이 토큰·비용 예산을 쓰는 경로는 없다.
- `StalledIterationGuard` 는 `ExecuteCode` 를 평범한 도구 하나로 본다.

---

## 14. 보안 모델

**원칙: 코드는 모델이 직접 할 수 있는 일 이상을 할 수 없어야 한다.** 도구 권한은 §8 과 단일 입구가, 엔진 자체의 능력은 결정 10 의
고유 등급이 보장한다. 남는 것은 결과가 코드를 거치며 생기는 새 경로다.

| 위협 | GraalJS (로컬 JVM) | 셸 엔진 + `aimon-sandbox` |
|---|---|---|
| 도구 관문 우회 | 막음 — 출구는 `tools` 와 두 버퍼 | 막음 — 출구는 인증된 요청 통로 |
| 엔진 자체의 능력으로 실링·승인 우회 | 해당 없음 — 고유 등급 `READ_ONLY` | 막음 — `ExecuteCode` 가 `Bash` 와 같은 등급·승인 키를 받고, `Bash` 를 제약 없이 쓸 수 있는 에이전트에만 제안된다(결정 10, 11) |
| 호스트 파일·네트워크·프로세스 | 막음 (`IOAccess.NONE`, `HostAccess.NONE`) | 샌드박스 정책을 따른다 |
| 호스트 출력(터미널·로그) 주입 | 막음 — 내장 출력 끄기(§10.1)와 로그 이스케이프(§12.2) | 막음 — 로그 이스케이프 |
| 공존 프로세스 | 해당 없음 | **한계**. 비용을 올리고 탐지하되 막지 못한다. 범위는 `shell-engine-tool-scope` 가 묶는다(셸 엔진 §12) |
| 바인딩을 통한 egress | `EXTERNAL` 도구를 opt-in 하면 코드가 루프로 증폭할 수 있다. 엔진 격리와 무관 — 그 도구는 호스트에서 돈다 | 같음 |
| 승인 증폭 | 제한함 — 관문 설정과 무관하게 `MUTATING` ∧ `DESTRUCTIVE` 제외, `MUTATING` 스크립트당 상한(결정 12) | 같음 |
| 메모리 고갈 | 호스트 쪽 복사는 막음(§10.2). **게스트 힙은 막지 못함** — 시작 시 경고 | 샌드박스 자원 한도 |
| CPU·출력 독점 | 문장 한도, 벽시계, 출력 상한 | 셸 타임아웃, 출력 상한, 감시 스레드 |

운영 권고:

- **신뢰할 수 없는 입력을 다루는 에이전트**(웹 페이지·티켓 본문·외부 로그를 읽는 운영 에이전트)는 샌드박스 환경에서 돌리고
  `shell-engine-tool-scope` 를 `environment` 로 둔다. `host` 가 필요하면 샌드박스 egress 를 기본 거부로 둔다.
- `EXTERNAL` 도구는 `programmatic-tools` 에 넣지 않는다.
- GraalJS 엔진을 샌드박스 환경에서 명시적으로 쓰는 것(`programmatic-engine: javascript`)은 게스트 힙 고갈을 감수할 수 있는 배치에서만.
- 도구 결과는 코드가 다루는 문자열이다. 코드가 그것을 `eval` 하면 인젝션이 코드가 된다. 그래도 그 코드가 할 수 있는 일은 위 표의
  범위 안이다.

---

## 15. 의도적으로 제외하거나 미룬 것

| 항목 | 상태 | 이유 |
|---|---|---|
| 제공자 호스팅 PTC (Anthropic `code_execution_20260120` + `allowed_callers`) | 제외 | 요구가 로컬·샌드박스 실행이다. ZDR 대상이 아니고 컨테이너 데이터가 30일까지 남는다. OpenAI 경로에는 같은 기능이 없다. `aimon-llm-anthropic` 은 서버 도구 블록(`server_tool_use`, `container`, `caller`)을 모델링하지 않고 모르는 블록을 버린다. [도구 검색](tool-search.md) §8 이 provider 전용 프로토콜을 provider 모듈의 일로 둔 것과 같은 판단이다 |
| 로컬 호스트 프로세스의 셸 엔진 | 미룸 | 로컬에는 GraalJS 가 있다. 단일 사용자 CLI 에서는 `Bash` 와 같은 권한이지만, 여러 세션이 같은 OS uid 로 도는 서버에서는 세션 X 의 프로세스가 세션 Y 의 런타임에 요청을 주입해 Y 의 신원·승인·메모리 범위로 호스트 도구를 부르는 confused deputy 가 된다. 단일 테넌트 배치를 판별하는 수단과 함께 다시 본다 |
| 구조화 결과 (`outputSchema`, `ToolResult.structured`, 구조화 값을 다루는 훅) | 미룸 | 엔진은 텍스트만으로 동작한다. 구조화 값은 텍스트에 없는 정보를 담을 수 있어 마스킹 훅을 비껴가므로, "텍스트와 같은 정보" 불변식을 지킬 도구 이전(`Read`·`Bash` 의 `GenericTool` 화, `Grep` 의 출력 DTO)과 MCP `structuredContent` 처리를 별도 설계로 정한다. 그 설계는 `GenericTool` 출력 DTO 의 `record` 허용(CLAUDE.md 규칙 변경)도 다룬다 |
| 큰 인자 파일 | 미룸 | 64 KiB 를 넘는 인자는 셸 엔진에서 오류로 돌려준다. IT 운영 용례(호스트 이름·질의 문자열)는 거의 다 덮인다 |
| 상태를 유지하는 REPL | 제외 | `ExecuteCode` 호출 사이에 변수를 남기면 실행 단위를 넘는 노드 로컬 엔진 상태가 생긴다. 호출 사이의 값은 작업 환경의 파일로 넘긴다 |
| 스크립트 안의 사람 승인 대기 | 제외 | 결정 12 |
| 코드에서 LLM·서브에이전트 호출 | 제외 | `WorkflowJs` 의 일이다(결정 15) |
| 다중 언어 동시 노출, 실패 시 언어 강등 | 제외 | 결정 5 |

---

## 16. 설정 표면

| 설정 | 에이전트 frontmatter | `AimonStack` / 스타터 `aimon.programmatic.*` | CLI | 기본값 |
|---|---|---|---|---|
| `programmatic-tools` | ✓ (`Agent` 빌더 포함) | — | — | 없음 (PTC 꺼짐) |
| `programmatic-only` | ✓ | — | — | 없음 |
| `programmatic-engine` | ✓ | — | — | `auto` |
| `shell-engine-tool-scope` | 좁히기만 | `shellEngineToolScope` / `shell-engine-tool-scope` | `programmatic.shellEngineToolScope` | `environment` |
| 한도 (§13.1), 관찰 상한 | — | `limits.*` | `programmatic.limits.*` | §13.1 |
| GraalJS 엔진 조립 | — | `aimon-graaljs` 의존 (ServiceLoader) | `programmatic.enableJs` | 꺼짐 |

CLI 설정은 기존 관례(`enableWorkflowJs`)대로 camelCase 다. 에이전트 단위 설정은 "무엇을 코드에 열 것인가", 애플리케이션 단위 설정은
"이 배치가 무엇을 허용하는가" 다. 에이전트 값은 애플리케이션 값을 넓힐 수 없다.

켜졌는지 확인하려면 `ExecuteCode` 가 도구 목록에 있는지 본다. 없으면 §12.2 의 WARN 로그와 iteration span 의 `ptc.unavailable_reason`
이 이유를 말한다.

---

## 17. 강제

설계의 어느 규칙을 빌드가 지키는가. 테스트 목록이 아니라 규칙과 그것을 강제하는 장치의 대응이다.

| 규칙 | 강제하는 곳 |
|---|---|
| 두 엔진이 같은 계약을 지킨다 — 같은 모양의 프로그램이 같은 관찰을 내고(결정 7), 한도·취소(§13)가 같은 의미다 | 엔진 계약 스위트 `aimon-code-engine-testkit`. 두 엔진이 같은 스위트를 돌린다 |
| LLM 을 부르거나 포크를 일으키는 도구는 코드에서 불릴 수 없다(결정 15) | ArchUnit (코어, `ProgrammaticToolDeclarationArchitectureTest`) — `at.aimon.core.tools..` 의 도구 중 클래스 그래프가 `LlmClient`·`LlmCallGateway`·`DialecticEngine`·`WikiKnowledgeBase`·`SubagentExecutionManager` 에 닿는 것은 `isCodeCallable()` 을 재정의한다. 그래프 탐색은 이유를 적은 정지 목록(`ToolContextKeys`, `StoreBackedPeerMemory`)에 들어가지 않는다(§21). `aimon-workflow-graaljs` 의 `GraalJsWorkflowTool` 은 그 모듈의 테스트가 확인한다 |
| 코드의 도구 호출은 단일 입구만 지난다(결정 3) | ArchUnit (코어) — `at.aimon.core.tools.code..` 는 `ToolCallDispatcher` 외 경로로 도구를 부르지 않는다. 입구 규칙 자체는 [단일 입구](tool-call-dispatcher.md) §3 #8. `aimon-graaljs` 는 같은 규칙을 `at.aimon.graaljs.code..` 에 대해 그 모듈의 아키텍처 테스트에 둔다 — 코어 테스트 클래스패스는 그 클래스를 보지 못한다 |
| 내장 도구는 `dataReach()` 를 명시적으로 선언한다(§7.1) | 내장 도구 전체에 대한 선언 검사 — `ProgrammaticToolDeclarationArchitectureTest` (`at.aimon.core.tools..`, 메모리 deriver 도구, `McpTool`) |
| 셸 엔진의 규칙 | [셸 엔진](programmatic-tool-calling-shell.md) §14 |

---

## 18. 평가

§2.1 의 수치는 이 설계의 근거가 아니다. 기본값(한도, 설명 문구, `auto` 규칙)은 잠정값이고, 아래 측정의 판정이 그것을 정한다.

| 측정 | 방법 | 판정 |
|---|---|---|
| 토큰·정확도 | 대표 IT 운영 과제(다중 호스트 상태 수집, 로그 필터링, 메트릭 질의 팬아웃, 순차 진단)를 직접 호출과 PTC 로 각각 실행. 제공자별(Anthropic, OpenAI), 엔진별 | PTC 가 손해인 과제 유형을 설명 지침에 "쓰지 마라" 로 넣는다 |
| 설명 크기 | opt-in 도구 수 5 / 20 / 50 에서 `ExecuteCode` 설명 토큰 | 선언이 정해 둔 토큰 예산을 넘는 도구 수부터는 선언 대신 `tools.describe(name)` 지연 노출로 바꾼다 |
| 프롬프트 캐시 | PTC 를 켠 세션과 끈 세션의 캐시 적중률 | 적중률이 떨어지면 설명이 바뀌는 원인(활성화, 환경 종류 변화)을 줄인다 |
| 실패 유형 | 스크립트 오류 중 결과 파싱, 도구 이름, 인자 형식이 차지하는 비율. Python 선언의 산문 표기와 `TypedDict` 표기 비교 | 비율이 큰 쪽의 렌더링을 바꾼다 |
| 지연 | 샌드박스에서 중첩 호출 하나와 응답 쓰기 하나의 p50/p99, 순차 N 호출의 총 시간 | 폴링 간격과 설명 지침의 병렬 권고 강도 |

---

## 19. 열린 질문

1. **게스트 힙 한도.** Oracle GraalVM `SandboxPolicy.UNTRUSTED` (힙·CPU 한도)를 선택적으로 지원할지. 런타임이 Oracle GraalVM 이어야 한다.

---

## 20. 참고

- Anthropic, [Programmatic tool calling](https://platform.claude.com/docs/en/agents-and-tools/tool-use/programmatic-tool-calling)
  — `allowed_callers`, `caller`, 메시지 형식 제약, 토큰 수치(BrowseComp·DeepSearchQA, 75개 도구, 도구 10–49개, τ²-bench),
  "Alternative implementations"
- Anthropic Engineering, [Introducing advanced tool use](https://www.anthropic.com/engineering/advanced-tool-use)
  — PTC 평균 토큰 −37%, Tool Search · Tool Use Examples 와의 조합
- Anthropic Engineering, [Code execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp)
  — 도구를 코드 모듈로 노출, 점진 노출
- Cloudflare, [Code Mode: the better way to use MCP](https://blog.cloudflare.com/code-mode/)
  — TypeScript API 생성, isolate, 바인딩 RPC, 자격 증명 분리
- [replicate/replicate-mcp-code-mode](https://github.com/replicate/replicate-mcp-code-mode) — Deno 샌드박스의 단일 코드 도구
- Wang et al., *Executable Code Actions Elicit Better LLM Agents* (CodeAct, 2024)

---

## 21. 구현 현황

| 조각 | 내용 | 상태 |
|---|---|---|
| A | [단일 입구](tool-call-dispatcher.md) | 구현됨 |
| B | `ToolDescriptor`·`ToolOfferContext`·`OfferKey`·`DataReach`, `Tool.describe`·`isCodeCallable`·`dataReach`, 세 루프의 제안 문맥, 서술자를 읽는 실링·승인 관문·루틴 재시도, `approvalKey`·`approvalDelegated`·코드 경로 무질의, 공개 `ToolCallQuota`, 내장 도구 선언과 그 검사, MCP `DataReach` | 구현됨 |
| C, D | `ExecuteCode`, 엔진 SPI, GraalJS·셸 엔진, 모듈 개명, 계약 스위트 | 미구현 |

조각 B 가 본문과 다르게 정한 점:

| 본문 | 구현 | 이유 |
|---|---|---|
| `ToolOfferContext` 는 타입 키로 속성을 싣는다(§4) | 타입 키(`OfferKey`)에 더해 레지스트리·허용 목록 필터·실링을 일급 필드로 싣고, `offer()` 가 "허용 → `describe` → 실링" 을 한 번에 적용한다 | 세 루프의 제안 필터가 같은 계산을 한 곳에서 하고, §7.3 순서 1(제안 자격)의 출처가 그 문맥이 된다 |
| `ToolContextKeys.TOOL_OFFER_CONTEXT` (§5.2) | 정의는 `ToolOfferContext.CONTEXT_KEY`, `ToolContextKeys.TOOL_OFFER_CONTEXT` 는 같은 키 | 실링 거절(`DefaultToolExecutionManager`)이 `agent.tool` 에 있어 `tools` 에 의존할 수 없다 |
| `ToolCallQuota` 키의 write-once 여부는 적지 않았다 | write-once | `QUOTA_GUARD` 와 같은 이유 — enricher 가 무제한 쿼터로 바꿔 끼우지 못하게 |
| 코드 경로에서 `ASK` 를 거절로 읽는 것은 PermissionRequest 훅(§8.3) | PreTool 훅의 `ASK` 도 | PreTool 의 `ASK` 도 프롬프트를 띄운다. "스크립트는 사람을 기다리지 않는다"(결정 12, §15)는 훅 종류를 가리지 않는다 |
| 슬래시 스킬 루프도 iteration 마다 제안 문맥을 짓는다 | 실행당 한 번 짓고 iteration 컨텍스트마다 싣는다. 호출자 컨텍스트에 이미 있는 제안 문맥은 스킬의 것으로 바꾼다 | 스킬의 도구 목록은 실행 중 바뀌지 않는다. 바깥 턴의 제안 문맥을 그대로 복사하면 write-once 키가 두 번 쓰인다 |
| 승인 질의 문구 "Bash — via ExecuteCode (Python)" (결정 11) | "'Bash' — via 'ExecuteCode(code=…)'" — 키와 호출을 밝히고 언어는 적지 않는다 | 관문은 서술자와 호출만 본다. 언어를 문구에 넣을지는 `ExecuteCode` 가 생기는 조각 C 에서 정한다 |
| 결정 15 의 클래스 그래프 검사(§17) | 정지 목록 두 개: `ToolContextKeys`(키 상수 레지스트리 — 키 하나를 읽는 것이 그 레지스트리가 이름 붙인 모든 타입에 닿는 것은 아니다), `StoreBackedPeerMemory`(대화용 `DialecticEngine` 을 들지만 메모리 읽기·쓰기 도구는 검색·회상·관찰 쪽만 쓴다) | 클래스 단위 그래프는 둘을 거쳐 거의 모든 도구가 `WikiKnowledgeBase`·`DialecticEngine` 에 닿는다고 읽는다. 목록이 없으면 규칙이 모든 도구에 `false` 를 요구해 무의미해진다 |
