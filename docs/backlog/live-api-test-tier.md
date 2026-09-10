# 라이브 API 테스트 계층 — 등록 항목 1건 (열림 0 · 닫힘 1)

출처는 [#81](https://github.com/kangwoo/aimon-core/issues/81) 이고, 2026-09-10 의 작업이다. 이 문서는
**결정 항목 하나**를 적는다 — 프로바이더 키에 게이트가 걸린 라이브 API 테스트 계층에 CI 신호를 줄
것인가. 결정은 **등록과 같은 날 내려졌고** 그 결정이 요구한 문서화도 같은 변경에서 끝났으므로, 항목은
열리자마자 닫힌다.

그래도 적어 두는 이유는 [`README.md`](README.md) 규칙 넷이다 — *"결정은 내려지고 나면 근거가 아니라
결론으로 인용된다."* 이 계층에 신호가 없다는 것을 다음에 알아챈 사람은 이슈가 제시한 것과 같은 처방
(예약 워크플로)에 곧바로 이를 것이고, 그것이 **이미 저울에 올라갔고 무엇을 받아들이는 대가로 채택되지
않았는지**를 읽을 자리가 없으면 같은 판단을 처음부터 다시 한다.

기여자가 실제로 만나는 사실 — 이 계층을 어떻게 돌리는가, 돈이 드는가, 신호가 없으면 무엇을 예상해야
하는가 — 은 이 문서가 아니라 [`CONTRIBUTING.md`](../../CONTRIBUTING.md#live-api-tests) 에 있다. 두 자리는
독자가 다르다: 그쪽은 **이 계층을 돌리려는 사람**을, 이쪽은 **이 배치를 바꾸려는 사람**을 위한 것이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 전부 2026-09-10 기준이다.

---

## 0. 등록하며 확인한 것

규칙 넷은 결정문을 쓰기 전에 **전제를 소스로 확인하라**고 한다. 이슈의 전제 셋을 확인했고, 하나는
적힌 것보다 **나빴다.**

### 0.1 네 클래스와 게이트 — 맞다. 다만 같은 모양의 클래스가 둘 더 있다

`grep -rln "EnabledIfEnvironmentVariable" modules/ samples/` 는 여섯 클래스를 낸다.

| 클래스 | 게이트 변수 | 이 문서의 대상인가 |
|--------|------------|-------------------|
| `AnthropicThinkingLiveTest` | `ANTHROPIC_KEY` | 그렇다 |
| `AnthropicLlmClientIntegrationTest` | `ANTHROPIC_KEY` | 그렇다 |
| `OpenAIReasoningLiveTest` | `OPENAI_KEY` | 그렇다 |
| `OpenAILlmClientIntegrationTest` | `OPENAI_KEY` | 그렇다 |
| `DockerSandboxBackendIntegrationTest` | `AIMON_DOCKER_IT=true` | 아니다 |
| `KubernetesSandboxBackendIntegrationTest` | `AIMON_KUBERNETES_IT=true` | 아니다 |

표의 마지막 두 클래스도 **CI 신호가 없는 같은 모양**이다 — 어느 워크플로도 그 변수를 켜지 않는다
(`grep -rn "AIMON_DOCKER_IT\|AIMON_KUBERNETES_IT" .github/` 0건). 그러나 그 게이트가 막는 비용은 돈이
아니라 인프라이고, 왜 CI 밖에 있는지는 **확인하지 않았다.** 근거를 확인하지 않은 채 항목으로 올리는
것은 규칙 둘이 막는 일이므로 등록하지 않고, **본 적이 있다는 사실만** 여기 남긴다. 빌드 설정의 계층
주석도 그 둘을 이름으로 적어 두었다.

### 0.2 워크플로에 키가 없다 — 맞다

`grep -rn "ANTHROPIC_KEY\|OPENAI_KEY" .github/` 는 0건이고, 워크플로의 `secrets.` 참조는
`GITHUB_TOKEN` 셋뿐이다(`release.yml:113`, `dependabot-auto-merge.yml:42,62`).

### 0.3 "수동 전용" 은 이슈가 가정한 것보다 약했다 — 손으로 치는 명령이 아무것도 돌리지 않았다

이슈의 선택지 2 는 *"사람이 검증하기로 마음먹었을 때 검증된다"* 를 받아들이자는 것이었고, 그 문장은
**손으로 치면 돈다**는 것을 전제한다. 한 번 쳐 보니 그렇지 않았다.

키 없이 네 클래스를 한 번 돌리고(전부 `SKIPPED`, 초록), 두 키를 export 한 뒤 **같은 명령**을 다시
쳤더니 `:aimon-llm-anthropic:test` 와 `:aimon-llm-openai:test` 가 둘 다 `UP-TO-DATE` 였다 —
**543ms 만에 `BUILD SUCCESSFUL`, 실행된 테스트 0건.** Gradle 은 환경 변수를 `test` 태스크의 입력으로
치지 않고, `buildSrc/` 와 두 모듈의 빌드 스크립트 어디에도 `upToDateWhen` 이나 환경 입력 선언이 없다.
그러니 이것은 이 빌드의 결함이 아니라 **Gradle 의 기본값**이고, 이 계층을 손으로 돌리는 모든 사람에게
걸린다.

각 태스크에 `--rerun` 을 붙이자 둘 다 실제로 돌았고 네 클래스 42건이 전부 통과했다(59초). 그래서
[`CONTRIBUTING.md`](../../CONTRIBUTING.md#live-api-tests) 의 명령은 `--rerun` 을 싣고, 그것이 왜
선택이 아닌지를 함께 적는다.

이것은 `README.md` 규칙 셋이 `playwrightTest` 에서 기록한 모양과 **같다** — *"안 도는 것과 아무것도
없는 것은 밖에서 같아 보인다."* 그쪽은 후보 클래스가 0개라 `NO-SOURCE` 로 초록이었고, 이쪽은 태스크가
최신이라 `UP-TO-DATE` 로 초록이다. 둘 다 **읽어서는 절대 나오지 않고 한 번 쳐 보면 첫 줄에 나온다.**
문서화만 하고 명령을 돌려 보지 않았다면, 이 결정은 *"사람이 돌리면 검증된다"* 를 약속하면서 그 사람이
실제로 칠 명령이 아무것도 검증하지 않는 상태로 닫혔을 것이다.

---

## 1. 항목

### LA-1 — 라이브 API 테스트 계층에 CI 신호가 없다 · **닫힘** *(2026-09-10, #81)*

**무엇** — 프로바이더 키 게이트가 걸린 네 클래스(§0.1)에 신호를 줄 것인가. 이슈가 낸 선택지는 둘이었다.

1. **신호를 준다.** 예약 워크플로(주간 또는 릴리스 시)를 두고 `ANTHROPIC_KEY` / `OPENAI_KEY` 를 저장소
   시크릿에 넣어 네 클래스만 돌린다
2. **신호가 없다고 적는다.** 이 계층을 수동 전용으로 문서화하고, 사람이 검증하기로 마음먹었을 때
   검증된다는 것을 받아들인다

**왜 — 관측 가능한 결과.** 2026-09-10 하루의 작업에서 이 계층의 썩음이 **세 건** 나왔고, 셋 다 CI 가
아니라 우연히 발견되었다.

| # | 무엇 | 어떻게 발견되었나 |
|---|------|------------------|
| 1 | `AnthropicThinkingLiveTest.DialectMismatchesAreRejected` 가 `main` 에서 이미 빨갰다(10건 중 1건) | #73 이 쌍둥이 테스트를 빨갛게 만들 뻔해서. #73 안에서 고쳐졌다 |
| 2 | 같은 클래스의 서명 음성 대조가 6회 중 3회 실패한다 — 서버가 400 본문 두 가지를 번갈아 주는데 테스트는 하나를 문구 그대로 대조한다 | #73 의 빌드가 실측. `L-12` 로 등록 |
| 3 | `OpenAIReasoningLiveTest` 가 OpenAI 에러 문장을 문구 그대로 대조한다 — OpenAI 가 문구를 바꾸면 빨개진다 | #71 의 빌드 리뷰가 "막지 않음" 으로 보고. 어디에도 등록되지 않았다 |

(1번의 클래스 이름은 이슈의 것과 다르다. 이슈는 `AnthropicThinkingDialectTest.DialectMismatchesAreRejected` 로
적었는데, `AnthropicThinkingDialectTest` 는 실재하지만 키 게이트가 없는 테스트이고(§0.1 의 인구조사에 없다)
그 안에 `DialectMismatchesAreRejected` 는 없다. 그 중첩 클래스는 `AnthropicThinkingLiveTest:223` 에만 선언되어
있고, 그 javadoc 이 같은 사실 — 표가 배포된 날부터 빨갰고 CI 에 키가 없어 아무도 몰랐다 — 을 적고 있다. 이슈의
이름대로 찾아가면 없는 자리에 닿으므로 여기 적는다.)

셋 중 둘(2·3)은 **같은 결함**이다 — 주장이 *상태*만 요구하는 자리에서 테스트가 *문장*을 대조한다.
셋 다 각자의 자리에서 "막지 않음" 으로 판단되어 이월되었고, 각각은 옳은 판단이었다. 모양은 셋이 다 쌓인
뒤에야 보였다.

**결정 — 선택지 2, 수동 전용. 메인테이너가 골랐다.** 어느 쪽이든 돈이 들므로 구현 세부가 아니라
메인테이너의 결정이라는 것이 이슈 자신의 서술이다.

받아들인 대가는 이슈가 적어 둔 그대로다 — **이 계층의 썩음은 뜻밖의 일이 아니라 정상 상태**이고,
다음에 누군가 이 계층을 돌릴 때 발견된다. 그 문장은 [`CONTRIBUTING.md`](../../CONTRIBUTING.md#live-api-tests)
에 기여자가 읽는 형태로 들어갔다. 이슈는 선택지 2 를 고르면 **그것을 암묵으로 두지 말고 적으라**고
요구했다.

범위 밖이었던 것도 적는다: 이 테스트들을 기본 `test` 태스크에서 **돌게** 만드는 것. 게이트는 옳다 —
키가 필요하고, 돈이 든다. 문제는 건너뛰는 것이 아니라 건너뛰는 동안 아무것도 그 침묵을 보고하지 않는
것이었다.

**`@Tag("docker")` 와의 비교 — 차이는 양이 아니라 종류다.** 이슈는 docker 계층을 *"이 계층에 없는
모양"* 의 선례로 들었다 — 전용 `integrationTest` 태스크와 그것을 도는 CI 잡. 그 모양이 왜 이 계층으로
자동으로 옮겨지지 않는지는 [`architecture-review-open-items.md`](architecture-review-open-items.md) R-1 이
두 태그 계층을 가를 때 쓴 칸 — **`필요한 것`** — 으로 보면 드러난다.

| 계층 | 모양 | 필요한 것 | CI |
|------|------|----------|-----|
| `@Tag("docker")` | 태그 + `integrationTest` | Docker 데몬 | `build` 또는 `integration` 잡의 스텝, 그리고 릴리스 게이트 |
| `@Tag("packaging")` | 태그 + `packagingTest` | 없음 | 같음 |
| `@Tag("playwright")` | 태그 + `playwrightTest` | 브라우저 바이너리 | 같음 |
| 라이브 API | `@EnabledIfEnvironmentVariable`, **태스크 없음** | **프로바이더 키, 그리고 실행마다 청구** | 없음 |

(CI 칸은 `aimon.java-conventions.gradle.kts` 의 계층 주석이 세 태스크를 **묶어서** 적은 것을 옮겼다.
어느 태스크가 어느 잡에 있는지는 이 문서가 따로 확인하지 않았다.)

위 세 줄이 필요로 하는 것은 CI 가 **공짜로** 갖출 수 있는 것이다 — 데몬, 아무것도 아닌 것, 캐시된
내려받기. 넷째 줄은 **청구되는 비밀**을 필요로 하고, 그것이 이 계층에 태스크도 잡도 없는 이유다.
그러니 docker 계층의 모양을 이 계층에 입히는 것은 선례를 따르는 일이 아니라 **선택지 1 그 자체**다.
R-1 이 두 태그 계층을 두고 적은 문장 — *"그 차이는 양이 아니라 **종류**이고"* — 이 여기서도 그대로 성립한다.

**왜 설계 문서가 아니라 여기인가.** 설계된 것이 없다. 남은 것은 검증 범위에 관한 결정 하나, 채택되지
않은 선택지 하나, 그리고 그 결정을 다시 열 조건이다 — 백로그의 결정 항목이 담는 모양 그대로다.

**어디** *(2026-09-10)*

- 게이트: `AnthropicThinkingLiveTest:76` · `AnthropicLlmClientIntegrationTest:41` ·
  `OpenAIReasoningLiveTest:69` · `OpenAILlmClientIntegrationTest:41`
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md#live-api-tests) 의 `Live-API tests` 절과 그 번역본 — 명령,
  비용, `--rerun`, 정상 상태로서의 썩음. 같은 변경에서 `Prerequisites` 가 적고 있던
  `ANTHROPIC_API_KEY` 를 `ANTHROPIC_KEY` 로 고쳤다 — 트리의 어느 게이트도 앞의 이름을 읽지 않는다
- `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` 의 계층 주석 — *"no tier in this build
  is out of both any more"* 는 **태스크**에 대한 문장이고 환경 변수 게이트에는 닿지 않는다는 것을 그
  바로 아래에 적었다. 그 문장은 이슈가 선례로 가리킨 바로 그 파일에 있어서, 그대로 두면 이 계층에도
  게이트가 있다고 읽혔을 것이다
- `.github/workflows/` — 키 없음(§0.2). **이 결정이 그 사실을 바꾸지 않는다**

**이 결정과 함께 고친 것.** 발견 2·3 — 문장을 대조하던 두 단언을 주장이 요구하는 만큼으로 좁혔다.
2 는 [`llm-config-surface-open-items.md`](llm-config-surface-open-items.md) 의 `L-12` 로 등록되어 있었으므로
그쪽에서 닫았고, 3 은 어디에도 등록되지 않았으므로 **새로 등록하지 않고** 같은 닫힘 블록에 적었다.

**언제 다시 볼까 — 이 결정을 다시 여는 조건.** 둘 다 [`README.md`](README.md) 규칙 일곱의 물음 —
*"그 사건이 이 자리를 지나가는가"* — 에 대고 골랐고, 2번은 조건부로만 통과한다. 이 계층의 문제는 사람이 돌릴 때만 발견되고,
그 사람이 여는 문서가 `CONTRIBUTING.md` 이며, 그 절이 이 문서를 가리킨다.

1. **이 계층이 잡았을 썩음이 릴리스된 뒤에 발견될 때.** "다음에 돌리는 사람이 발견한다" 가 싸게 끝나는
   것은 그 발견이 릴리스보다 먼저일 때뿐이다. 릴리스된 버전이 이 계층의 빨강을 안고 나갔다면, 받아들인
   대가가 이슈가 가정한 것보다 비싸다는 뜻이다
2. **이 계층의 한 번 실행 비용이 크게 달라질 때** — 클래스가 늘거나 줄 때, 또는 프로바이더의 청구
   방식이 바뀔 때. 클래스를 더하는 사람이 `CONTRIBUTING.md` 의 표를 고치면 이 자리를 지나간다 — **다만
   그것을 강제하는 검사는 없고**, 표에 오르지 않은 채 들어온 키 게이트 클래스는 이 트리거를 지나가지 않는다.
   참고로 이슈는 #71 의 더 큰 스윕이 청구 호출 11건이었다고 적었다. **이 문서는 네 클래스 한 번의 청구
   호출 수를 세지 않았다**

다시 열 때 이 결정의 전제를 **다시 확인한다** — 특히 §0.3. 환경 변수가 `test` 의 입력이 아니라는 사정은
신호를 주기로 해도 그대로이므로, 그 신호를 내는 명령에도 §0.3 을 먼저 대 본다 — 아무것도 돌리지 않고 초록이
되는지.

---

## 2. 관련

- [#81](https://github.com/kangwoo/aimon-core/issues/81) — 이 결정의 출처. 두 선택지와 세 발견의 원문
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md#live-api-tests) — 이 계층을 돌리는 방법
- [`llm-config-surface-open-items.md`](llm-config-surface-open-items.md) — `L-12` 가 여기서 닫혔다
- [`architecture-review-open-items.md`](architecture-review-open-items.md) — R-1 · R-7, 태그 계층을 CI 에
  넣은 결정과 그 `필요한 것` 칸
- [`README.md`](README.md) — 항목 등록 규칙
