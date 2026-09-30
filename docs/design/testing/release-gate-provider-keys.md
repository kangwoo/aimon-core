# 릴리스 게이트의 프로바이더 키 거부 (Release Gate Provider-Key Refusal)

> Status: **IMPLEMENTED** — `scripts/release.sh` 는 프로바이더 API 키가 환경에 있으면 시작하지 않는다.
> `ReleaseGateMatchesCiGateTest` 가 그 스크립트를 샌드박스에서 실제로 돌려 거부와 그 순서를 붙들고, 거부 목록을
> 프로바이더 모듈의 키 게이트와 대조한다. 그 테스트가 읽는 저장소 파일은 `:aimon-core:test` 의 입력으로 선언되어
> 있다. 남은 것은 §10.
>
> 적용 대상: `scripts/release.sh` (`0. provider API keys` 절) · `aimon-core` 테스트
> `at.aimon.core.architecture.ReleaseGateMatchesCiGateTest` · `modules/aimon-core/build.gradle.kts` 의 `tasks.test`
> 입력 · `.claude/skills/release/SKILL.md`

---

## 1. 문제와 범위

### 1.1 키 하나가 평범한 빌드를 청구되는 라이브 실행으로 바꾼다

프로바이더의 실제 API 를 부르는 테스트 클래스는 넷이다 — `aimon-llm-anthropic` 의 `AnthropicThinkingLiveTest` ·
`AnthropicLlmClientIntegrationTest`, `aimon-llm-openai` 의 `OpenAIReasoningLiveTest` · `OpenAILlmClientIntegrationTest`.
넷 모두 태그가 없고, `@EnabledIfEnvironmentVariable` 의 프로바이더 키(`ANTHROPIC_KEY` · `OPENAI_KEY`) 하나로만 켜진다.
모듈 `test` 태스크의 제외는 태그로만 한다 — 컨벤션 플러그인이 모든 모듈에서 빼는 `docker` · `packaging`, 그리고
`aimon-browser-playwright` 가 더 빼는 `playwright`. 그래서 키가 export 된 셸의 `./gradlew test` 와 `checkAll` 은 그
클래스를 함께 돌린다. 그 호출은 키 주인의 계정에 청구되고, 빌드하는 변경과 무관한 이유(더는 유효하지 않은 키의
HTTP 401, 프로바이더 쪽 변화)로 빨개질 수 있다.

같은 이유로 문서의 명령 예시는 키를 `export` 하지 않고 그 키가 필요한 명령 앞에 둔다. 규칙과 그 이유는
[`CONTRIBUTING.md` › Live-API tests](../../../CONTRIBUTING.md#live-api-tests) 가 정본이다.

### 1.2 키가 있는 셸에서 자른 릴리스는 CI 가 돌지 않는 게이트를 돈다

`ReleaseGateMatchesCiGateTest` 는 **릴리스가 PR 이 이미 통과한 게이트보다 좁은 게이트를 통과하지 않는다**는 불변식을
지키려고 있다. Maven Central 발행은 되돌릴 수 없으므로 릴리스 경로가 적어도 CI 만큼 엄격해야 한다. CI 의 어느
워크플로도 프로바이더 키를 갖지 않는다. `scripts/release.sh` 가 시작된 환경을 그대로 물려받으면, 키가 있는 셸에서 자른
릴리스는 게이트 안에서 청구 호출을 하고, 프로바이더 쪽 이유로 빨개질 수 있으며, CI 가 도는 게이트와 다른 게이트를 돈다.

### 1.3 테스트가 읽는 파일이 태스크의 입력이 아니면 가드가 돌지 않는다

이 테스트는 클래스패스에 없는 저장소 파일(스크립트, 워크플로, 스킬, 다른 모듈의 테스트 소스)을 읽는다. Gradle 은 그
파일을 입력으로 추론하지 못하므로, 그것만 바꾼 로컬 빌드는 `:aimon-core:test` 를 `UP-TO-DATE` 로 건너뛰고 초록을
보고한다 — 가드가 잡으려는 바로 그 편집에서다.

### 1.4 범위 밖

- **라이브 API 계층을 게이트에 들이는 것.** 거부는 백로그
  [`LA-1`](../../backlog/live-api-test-tier.md) 의 닫힌 결정 — 라이브 API 계층은 수동으로만 돌린다 — 에 기댄다. 그
  결정을 뒤집어 라이브 계층을 게이트에 들이면 거부도 다시 판단한다
- **`AIMON_DOCKER_IT` · `AIMON_KUBERNETES_IT`.** 같은 모양으로 게이트가 걸린 샌드박스 통합 테스트 둘의 변수다.
  거부하지 않으며, 거부할지는 [`LA-2`](../../backlog/live-api-test-tier.md) 가 연다(§10)
- **구멍이 아닌 경로.** export 되지 않은 셸 변수는 스크립트에도 Gradle 에도 보이지 않는다. `~/.gradle/gradle.properties`
  나 `-P` 로 준 값은 환경만 읽는 `@EnabledIfEnvironmentVariable` 에 닿지 않는다

---

## 2. 거부 — unset 하지 않고 시작하지 않는다

### 2.1 계약

| 항목 | 결정 |
|---|---|
| 새 사전조건 | `ANTHROPIC_KEY` · `OPENAI_KEY` 가 스크립트의 환경에 **존재하지 않아야** 한다 |
| "설정됨" 의 뜻 | 변수가 존재하면 설정된 것이다. **빈 문자열로 설정된 것도 포함한다** |
| 적용 범위 | 모든 호출. `--dry-run` 도 게이트를 돌므로 거부한다 |
| 위반 시 | exit 1, 출력은 stderr. 어떤 `git` 호출보다 먼저 끝난다(§3) |
| 탈출구 | 없다. 거부를 끄는 플래그를 두지 않는다 |
| 바뀌지 않는 것 | 명령줄(잘못된 인자는 여전히 usage 줄과 exit 2), 게이트 줄과 태스크 목록, Java API, 설정 키, 와이어 이름 |

존재 여부는 `${!name+set}` 으로 묻는다. 이 형태는 변수가 있는지만 보고 값을 확장하지 않으며, 빈 문자열로 설정된
변수도 있는 것으로 본다(bash 3.2.57 · 5.1.16 에서 확인, 2026-09-11).

빈 문자열까지 거부하는 이유는 가드가 각 클래스의 `matches` 정규식에 기대지 않게 하려는 것이다. 지금 네 클래스는
`matches = ".+"` 라 빈 값으로는 켜지지 않지만, `matches = ".*"` 로 게이트를 건 클래스는 빈 값으로도 켜진다. 인구조사(§5)도
정규식이 아니라 이름을 읽는다. 처방(`unset`)은 어느 쪽이든 같다.

### 2.2 메시지

- **설정된 변수만, 전부, 한 번의 거부에** 부른다. 설정되지 않은 이름은 메시지 어디에도 나오지 않는다 — 운영자가 없는
  것을 unset 하라는 말을 듣지 않고, 거부를 두 번 만나지 않게 하려는 것이다. 값은 절대 출력하지 않는다
- 설명과 처방을 먼저 찍고, 마지막 줄은 스크립트의 `fail` 헬퍼로 찍는 판정 한 줄이다 — 거부된 변수 이름을 담는다.
  단수·복수에 따라 `is`/`are`, `it`/`them` 을 가른다
- 처방은 둘이다. 셸에서 `unset <names>` 하고 다시 돌리거나(그 셸의 뒤 빌드도 청구하지 않게 된다), 이번 한 번만
  `env -u <name>… scripts/release.sh <args>` 로 돌린다. 뒤의 것은 이미 검증된 인자를 되풀이해 복사해 쓸 수 있게 한다

### 2.3 왜 unset 이 아니라 거부인가

두 선택지 모두 공개 계약을 바꾸지 않는다 — 인자, 게이트의 태스크 목록, CI 는 어느 쪽에서도 같고, 둘 다 운영자가
관찰할 수 있는 동작 변화다. 그래서 "가장 작은 계약 변경" 으로는 고를 수 없고, 결정은 다음 넷에 선다.

1. **위험은 셸에 있고, 셸에 닿는 것은 거부뿐이다.** 스크립트가 끝나도 운영자의 셸에는 키가 export 된 채 남고, 그 셸의
   뒤 빌드는 전부 청구된다. 스크립트 안의 unset 은 그 셸의 명령 하나를 고치고 운영자에게 아무것도 알리지 않는다
2. **게이트가 무엇을 검증하는지 바꾸는 사전조건은 이 스크립트에서 `fail` 이다.** 브랜치, 클린 트리, origin 동기화,
   Docker, 크리덴셜, 기존 태그가 모두 그렇다. 스크립트가 조용히 고치는 유일한 변수 `JAVA_TOOL_OPTIONS` 는 Gradle 이
   **어떻게** 도는지를 바꿀 뿐 **어떤 테스트**가 도는지를 바꾸지 않는다. 키는 어떤 테스트가 도는지를 정한다
3. **조용한 unset 은 기록을 남기지 않는다.** 키가 있는 셸의 릴리스 로그와 깨끗한 셸의 로그가 똑같이 읽힌다. 거부는
   전사에도, 그것을 전달하는 `/release` 세션에도 보인다
4. **거부는 자기 출력으로 붙들 수 있다.** 종료 코드, 메시지, 메시지에 없어야 하는 값이 있고 테스트가 정확히 그것을
   단언한다(§4). unset 은 출력이 없어서 뒤의 자식 프로세스가 받은 환경으로 추측할 수밖에 없다

대가는 드물고 의도적인 작업에 붙는 마찰 하나다. 탈출 플래그를 두지 않는 것은 그것이 두 선택지를 플래그 뒤에 함께 두는
것이고, 라이브 계층을 릴리스 게이트 안에 되돌려 `LA-1` 과 어긋나기 때문이다.

**에이전트가 도는 `/release`.** 스킬은 거부를 다른 중단과 똑같이 그대로 전달하고 멈춘다. 메시지가 찍는 `env -u …`
처방을 실행할지, 키를 unset 할지는 **사용자가 정한다** — 스킬의 Notes 가 그렇게 적는다. 메시지가 복사해 쓸 수 있는
명령을 찍으므로, 그것이 에이전트가 스스로 집어 들 처방이 아니라는 문장이 따로 있어야 한다.

---

## 3. 검사 위치 — 인자 파싱 바로 뒤, 저장소에 닿기 전

검사는 인자 루프가 끝난 직후, `cd "$(git rev-parse --show-toplevel)"` · `trap cleanup EXIT` · pre-flight 보다 앞에서 돈다.

1. **저장소가 필요 없다.** 앞서 돌아야 할 것이 없다
2. **거부될 셸이 먼저 `git fetch origin main` 이나 `docker info` 를 부르지 않는다**
3. **거부는 실패한 릴리스가 아니다.** 한번 걸린 `EXIT` trap 은 0 이 아닌 종료마다 `git diff` 를 부르고,
   `gradle.properties` 가 HEAD 와 다르면 *"uncommitted version bump"* 노트를 찍는다. 거부 옆에 그 노트가 찍히면 거짓이다
4. **인자 파싱 뒤라** 알 수 없는 인자는 여전히 usage 줄과 exit 2 를 받는다 — 기존 계약이다

`log` · `ok` · `fail` 헬퍼는 `cd` 위에 정의되어 거부도 같은 형식으로 찍는다. 이 위치는 산문으로 적어 두는 것이 아니라
테스트가 붙든다(§4). 스크립트의 절 주석도 테스트가 잡는 네 이동 — 인자 루프 위, `cd` 아래, trap 아래,
`log "Pre-flight checks"` 아래 — 을 이름으로 부른다.

| 측정 | 결과 | 날짜 |
|---|---|---|
| 스크래치 복사본에 최소 가드를 네 위치로 넣고 호출을 기록하는 stub `git` 으로 돌림 | 인자 루프 바로 뒤에 둘 때만 모든 거부 단언이 통과한다. `cd` 뒤에 두면 `git rev-parse --show-toplevel` 이, `EXIT` trap 뒤에 두면 trap 의 `git diff --quiet -- gradle.properties` 까지 호출 로그에 남는다 | 2026-09-11 |

---

## 4. 샌드박스 하네스 — 스크립트를 읽지 않고 돌린다

### 4.1 왜 돌리는가

거부는 태스크 이름이 아니라 **동작**이다. 주석 처리되었거나 조건이 뒤집혔거나 도달할 수 없는 검사도 텍스트 스캔은
통과하고, 어떤 스캔도 값이 출력되지 않는다는 것을 보일 수 없다. 그래서 `ReleaseGateMatchesCiGateTest` 는 실제
`scripts/release.sh` 를 실행한다. 새 클래스를 만들지 않고 이 클래스에 둔 것은, 이 클래스가 거부가 지키는 불변식(릴리스
게이트 = CI 게이트, 그리고 CI 에는 키가 없다)을 말하고, 그 javadoc 의 *What is enforced* 목록이 릴리스
게이트가 붙들리는 모든 것을 찾으러 가는 자리이기 때문이다.

### 4.2 모양

각 사례는 테스트 메서드의 `@TempDir` 아래 **사례별 하위 디렉토리**에서 돈다.

| 요소 | 결정 | 이유 |
|---|---|---|
| 작업 디렉토리 | 빈 `work/`, `HOME` 도 그곳 | `gradle.properties` 가 없으므로 아무것도 거부하지 않은 실행은 *"gradle.properties not found"* 에서 멈춘다 |
| 환경 | 비운 뒤 `PATH` · `HOME` · 사례의 변수만 | 키를 export 한 개발자 머신에서도 무키 사례가 결정론적이고, 스크립트가 export 하는 `JAVA_TOOL_OPTIONS` 가 끼지 않는다 |
| `PATH` | 호출을 기록하는 stub `git` 하나만 든 `bin/` | 진짜 `git` · `docker` · `perl` · `./gradlew` 에 닿을 수 없다 |
| 인자 | `--dry-run` | 한 겹 더 |
| stub | `#!/bin/sh`. 인자를 작업 디렉토리 **밖**의 로그 파일에 덧붙이고, `rev-parse` 에 `pwd` 를 답하고, exit 0 | 로그 위치가 스크립트에 영향을 주지 않는다. exit 0 이라 trap 의 `git diff --quiet` 도 노트를 찍지 않는다. 로그 경로는 작은따옴표로 쓰므로 경로에 `'` 가 없음을 먼저 단언한다 |
| 출력 | stderr 를 합쳐 파일로 받고, 프로세스가 끝난 뒤에만 읽는다. stdin 은 시작 직후 닫는다 | 걸린 스크립트가 읽기를 막을 수 없으므로 타임아웃이 반드시 발화한다 |
| 타임아웃 | 30초, 넘으면 `destroyForcibly()` 후 실패 | 샌드박스에서 막히는 것에 닿을 수 없다(확인 `read` 는 게이트 뒤) — 느린 머신이 bash 를 띄우는 시간만 넘기면 된다 |
| 키 값 | 센티널 `dummy-98-sentinel` | 키도 키 모양도 아니다(`sk-` 접두어 없음 — 시크릿 스캐너가 걸지 않는다). 출력 어디에서든 발견되면 값이 출력된 것이다 |
| bash | 테스트 JVM 의 `PATH` 에서 처음 찾은 실행 가능한 `bash` | 스크립트의 `#!/usr/bin/env bash` 가 고르는 것과 같다 |

샌드박스는 가드가 있든 없든 무해하다. 그래서 가드를 지우면 릴리스가 시작되는 대신 이 테스트가 빨개진다.

### 4.3 stub 의 두 역할

- **순서를 붙든다.** 모든 거부 사례는 호출 로그가 비어 있음을 단언한다 — 스크립트가 어떤 이유로든 `git` 을 부르기 전에
  거부했다. 테스트는 "pre-flight 전에 거부한다" 에서 멈추지 않고 `git` 호출이 없었다는 것까지 붙든다. §3 의 이유가
  `git fetch` 와 trap 에 관한 것이라, 그것을 붙들지 않으면 이 테스트가 대체하려는 산문 주장이 그대로 남기 때문이다.
  `git fetch` 와 `docker info` 는 `Pre-flight checks` 줄 뒤에 있으므로 따로 단언하지 않는다
- **거부 사례가 공허하지 않음을 증명한다.** 무키 사례는 호출 로그에 `rev-parse --show-toplevel` 이 있음을 단언한다 —
  stub 이 `PATH` 에 있었고 실행되었다. 그렇지 않으면 "git 호출 없음" 은 stub 이 돌 수 없는 샌드박스에서 저절로 통과한다

### 4.4 사례와 그것이 잡는 붕괴

| 사례 | 단언 |
|---|---|
| 키마다 하나씩 센티널로 설정 | exit 1 · 그 이름을 부른다 · 설정되지 않은 다른 이름은 부르지 않는다 · 센티널 없음 · `Pre-flight checks` 없음 · `git` 호출 없음 |
| `OPENAI_KEY` 를 빈 문자열로 | 같은 거부 단언 — §2.1 의 "빈 문자열도 설정됨" |
| 두 키 모두 | exit 1 · 두 이름 · 센티널 없음 · `git` 호출 없음 |
| 키 없음 | `Pre-flight checks` 에 도달 · 어느 이름도 부르지 않음 · 호출 로그에 `rev-parse --show-toplevel` |
| `OPENAI_KEY` 를 설정하고 `--bogus` | exit 2 · usage 줄 · 거부 없음(이름도 센티널도 없음) · `git` 호출 없음 |

무키 사례는 위의 셋만 단언하고, 종료 코드나 pre-flight 실패 문구는 단언하지 않는다 — 그 메시지를 고쳐 써도 이 사례가
실패하지 않게 하려는 것이다.

| 붕괴 | 실패하는 단언 |
|---|---|
| 가드 삭제 | 키별·빈 문자열·두 키 사례 — 스크립트가 pre-flight 까지 가서 이름을 부르지 않는다 |
| `log "Pre-flight checks"` 아래로 이동 | 같은 셋 — `Pre-flight checks` 가 찍힌다 |
| `cd` 아래 또는 `trap cleanup EXIT` 아래로 이동 | 같은 셋 — `git` 을 부른 뒤 거부한다 |
| 인자 루프 위로 이동 | 잘못된 인자 사례 — exit 2 가 아니라 1 |
| 값 출력 | 센티널이 담긴 사례 |
| 조건 반전 | 무키 사례와 모든 거부 사례 |
| 목록에서 변수 하나 누락 | 그 변수의 키별 사례와 두 키 사례 |

### 4.5 skip 과 fail 을 가르는 기준

- **bash 가 없거나 임시 파일시스템에 POSIX 권한이 없으면**, 아무것도 만들기 전에 이유와 함께 skip 한다. 둘 다 하네스를
  그 머신에서 만들 수 없다는 뜻이다 — bash 가 없으면 릴리스 스크립트도 돌 수 없고, POSIX 권한이 없으면 stub 에 실행
  비트를 줄 수 없다
- **만들어진 stub 이 실행되지 않으면**(예: `noexec` 임시 마운트) skip 하지 않고 실패한다. 매 실행 전에 stub 을 한 번 직접
  돌려 로그가 생기는지 확인한다. 그런 머신은 릴리스를 자를 수 있으므로, skip 하면 정확히 중요한 자리에서 거부가
  붙들리지 않는다. 처방은 `java.io.tmpdir` 을 실행을 허용하는 디렉토리로 돌리는 것이다
- 인구조사(§5)는 프로세스를 띄우지 않으므로 이 조건과 무관하게 돈다

**보지 못하는 것.** 스크립트가 pre-flight 전에 `git` 말고 다른 명령을 부르는지는 아무것도 확인하지 않는다. 지금은 부르지
않고, stub 은 `git` 만 기록한다.

---

## 5. 인구조사 — 거부 목록은 키 게이트를 적어도 덮는다

### 5.1 무엇을 붙드는가

거부 목록은 청구되는 계층을 어떤 변수가 게이트하는지에 대한 수기 사본이고, 이 클래스는 그런 사본이 어긋나기 때문에
있다. 그래서 사본을 그 출처에 묶는다.

- 테스트는 동결된 목록 `PROVIDER_KEY_VARIABLES = [ANTHROPIC_KEY, OPENAI_KEY]` 를 갖고, §4 의 거부 사례는 이 목록의
  항목마다 돈다
- `refusedKeysAreTheProviderModulesKeyGates` 는 `modules/aimon-llm-*/src/test` 아래 모든 `.java` 에서
  `@EnabledIfEnvironmentVariable` 이 부르는 변수를 모아, 비어 있지 않음(*"the scan is broken, not clean"*)과 목록과의
  **같음**을 단언한다
- 둘을 합치면 붙들리는 것은 **스크립트의 거부 집합 ⊇ 프로바이더 모듈의 키 게이트**다. 인구조사가 모든 게이트를 목록에
  올리고, 거부 사례가 목록의 모든 항목으로 스크립트를 돌린다. 거부 사례는 환경을 비우고 목록의 키만 설정하므로, 그 밖의
  변수를 더 거부하는 스크립트도 통과한다

새 프로바이더 모듈이 새 변수(예: `GEMINI_KEY`)로 게이트를 걸면 목록에 그 이름이 오를 때까지 인구조사가 실패하고, 목록에
오르면 스크립트가 거부할 때까지 거부 사례가 실패한다.

### 5.2 왜 "적어도" 가 옳은 주장인가

이 클래스가 막는 드리프트는 한 방향이다 — 릴리스가 CI 보다 **좁은** 게이트를 통과하는 것. 더 많이 거부하는 것은 그렇게
할 수 없다. 릴리스가 시작하지 못하게 할 뿐이고, 변수 이름을 부르며 크게 실패한다. 이 인구조사의 목적 — 다음
프로바이더의 키가 거부에 들어오지 않고는 빌드에 들어올 수 없게 하는 것 — 에는 ⊇ 만 필요하고, ⊇ 는 완전히 붙들린다.

테스트는 같음을 붙들지 않는다. 목록 밖 변수를 거부하지 **않음**을 요구하려면 그 자리에 세울 변수가 필요한데, 그럴 수
있는 것은 인구조사 밖의 두 게이트 `AIMON_DOCKER_IT` · `AIMON_KUBERNETES_IT` 뿐이다. 같음을 붙들면 `LA-2` 의 답을 테스트가
정하게 된다.

| 측정 | 결과 | 날짜 |
|---|---|---|
| 스크립트의 거부 루프에 `AIMON_DOCKER_IT` 를 임시로 더하고 클래스를 다시 돌림 | 모든 테스트 통과 — 테스트가 붙드는 것은 포함이다 | 2026-09-11 |

### 5.3 무엇을 어떻게 읽는가

- **범위는 `modules/aimon-llm-*` 이다.** 사전 제외 집합은 없다 — 오늘 그 범위의 게이트는 전부 프로바이더 키이고, 키가
  아닌 게이트는 전부 범위 밖에 있다. 두 샌드박스 변수는 **의도적으로** 범위 밖이다
- **애노테이션 전체를 읽는다.** 줄을 여는 `@EnabledIfEnvironmentVariable(` 이나 컨테이너 `@EnabledIfEnvironmentVariables(`
  에서 시작해(정규화된 이름 포함), 괄호가 균형을 이루는 줄까지 — 문자열 리터럴 안의 괄호는 세지 않고 — 읽으며, 그 안의
  모든 `named = "…"` 을 속성 순서와 줄바꿈에 관계없이 센다. 한 가지 모양만 읽는 정규식은 이 인구조사가 있는 바로 그
  경우에 **조용히** 놓친다 — 새 프로바이더 모듈의 새 이름 하나는 읽지 못하고, 옛 이름 둘은 다른 모듈에서 찾아내 같음이
  성립한다
- **줄머리 앵커**가 javadoc 과 주석 안의 언급을 읽지 않게 한다. 태그 스캔의 `TEST_TAG_ANNOTATION` 과 같은 선례다

| 측정 | 결과 | 날짜 |
|---|---|---|
| `aimon-llm-openai` 테스트 소스에 `GEMINI_KEY` 게이트를 모양별로 넣음 | 속성 순서가 바뀐 것, 컨테이너 애노테이션, 괄호를 담은 `matches`, 정규화된 이름, 여러 줄로 감싼 인자를 모두 읽어 인구조사가 실패한다. javadoc `{@code …}` 와 `//` 주석 안의 언급은 읽지 않는다 | 2026-09-11 |

### 5.4 실패 메시지

첫 줄은 **단언이 비교하는 것만** 말한다 — 프로바이더 모듈 테스트가 게이트하는 변수와, 거부 사례가 스크립트를 돌리는 키
목록(`PROVIDER_KEY_VARIABLES`). 단언은 스크립트를 읽지 않으므로 "스크립트가 무엇을 거부한다" 고 말하지 않는다. 그 뒤로
출구를 셋 적는다.

1. 게이트된 변수가 프로바이더 키면 — 목록에 더하고, 스크립트가 거부하게 하고, `CONTRIBUTING.md` 의 라이브 API 표에 그
   클래스를 올린다
2. 프로바이더 키가 아닌 것을 게이트하면 — 스캔을 좁혀 빼고, 그것이 무엇을 게이트하며 릴리스 게이트가 왜 물려받아도
   되는지 메모를 남긴다
3. 거부하는 변수가 더는 아무것도 게이트하지 않으면 — 목록과 스크립트에서 뺀다

### 5.5 보지 못하는 것

- 애노테이션이 아니라 `System.getenv` 와 assumption 으로 읽는 키
- `modules/aimon-llm-*` 밖의 프로바이더 키 게이트
- **이미 거부되는** 변수로 게이트를 건 새 클래스 — 거부에 필요한 변화가 아니다

---

## 6. 테스트가 읽는 저장소 파일은 태스크 입력으로 선언한다

### 6.1 규칙

클래스패스에 없는 파일을 읽어 단언하는 가드는, 그 파일을 자기를 도는 `Test` 태스크의 입력으로 **선언**한다. Gradle 은
그런 파일을 추론하지 못하고, 선언이 없으면 그 파일만 바꾼 로컬 빌드가 태스크를 `UP-TO-DATE` 로 건너뛴다 — 가드가 잡으려는
바로 그 편집에서 빌드가 초록을 보고한다.

이 빌드에는 빌드 캐시가 켜져 있지 않다. CI 의 fresh checkout 은 태스크 이력이 없으므로 선언과 관계없이 가드를 돈다.
**선언이 닫는 공백은 로컬 빌드뿐이다.** 그래도 적어 두는 대신 선언하는 것은, "CI 가 잡는다" 가 이미 선언된 모든 입력에도
참이었는데 빌드가 매번 선언을 골랐기 때문이다 — 적어 둔 메모는 약속이지 불변식이 아니고, 다음에 그 파일을 고치는 사람이
읽는 자리에 있지 않다.

이 규칙은 `aimon-core` 의 `tasks.test` 블록과 `aimon-spring-boot-starter` 의 `tasks.test` 블록(`AimonDocumentedPropertiesTest`
가 읽는 `docs/` 를 `documentation` 으로 선언)이 같은 이유로 따른다. 규칙의 설계 문서는 이 문서이고, 두 빌드 스크립트의
주석이 각자 이유를 되풀이한다.

### 6.2 `:aimon-core:test` 의 입력

| 입력 속성 | 대상 | 읽는 테스트 |
|---|---|---|
| `releaseScript` | `scripts/release.sh` | `ReleaseGateMatchesCiGateTest` — 게이트 줄, 거부 하네스 |
| `ciWorkflow` | `.github/workflows/build.yml` | 같음 — CI 의 Gradle 스텝 |
| `releaseSkill` | `.claude/skills/release/SKILL.md` | 같음 — 두 스킬 가드(§7.2) |
| `providerModuleTestSources` | `modules/` 아래 `aimon-llm-*/src/test/**/*.java` | 같음 — 인구조사(§5) |
| `moduleBuildScripts` · `sharedBuildScripts` · `rootBuildScript` | 모든 모듈의 `build.gradle.kts`, `buildSrc/src/main/kotlin/*.gradle.kts`, 루트 `build.gradle.kts` | `PublishedModuleApiScopeTest` · `PublishedModuleLoggingBindingTest` |

**관용구.** 구성 시점에 `rootProject.file(…)` / `rootProject.fileTree(…)` 로 해석하고 `withPropertyName` 을 붙인다. 이
블록은 경로 민감도를 설정하지 않는다 — 빌드 캐시가 꺼져 있어 바꾸는 것이 없고, 한 블록 안의 일관성이 읽기에 더 싸다. 이
관용구는 구성 캐시에 안전하다(실행 시점에 `project` 를 쓰지 않는다). `src/test` 나 `.claude/` 아래에 출력을 쓰는 태스크가
없으므로, 이 입력은 Gradle 의 implicit dependency 검증에 걸리지 않는다.

**비용.** 선언의 대가는 그 파일만 바뀐 빌드에서 드는 `:aimon-core:test` **전체** 재실행이다 — 이 클래스만이 아니다.
그 비용과 무관하게 선언을 유지한다. 비용은 그 파일을 고친 빌드에만 붙고, 프로바이더 모듈의 자기 테스트를 도는 빌드
(`./gradlew :aimon-llm-openai:test`)는 `:aimon-core:test` 를 돌지 않는다.

### 6.3 `providerModuleTestSources` — 모듈 목록이 아니라 인구조사의 걸음에서 파생한다

glob 은 인구조사가 걷는 모양 그대로다(`PROVIDER_MODULE_PREFIX` → `src/test` → `*.java`). 그래서 프로바이더 모듈은
`settings.gradle.kts` 가 이름을 부르든 말든 디렉토리가 생긴 날부터 입력이고, 입력은 인구조사보다 넓지 않다.

"인구조사가 무엇을 읽는가" 의 두 사본 — Java 상수와 Gradle glob — 은 **서로를 가리키는 주석**으로 잇는다. 빌드 스크립트
주석이 glob 을 인구조사의 접두어로 부르고, `PROVIDER_MODULE_PREFIX` 의 javadoc 이 `providerModuleTestSources` 를 부르며
인구조사를 넓히면 선언도 넓혀야 한다고 말한다. 셀프체크는 두지 않는다(§8). 인구조사를 넓히면서 glob 을 그대로 두면 새로
읽게 된 소스에서만 로컬 공백이 돌아온다 — CI 는 여전히 잡는다.

### 6.4 `releaseSkill` — 파일 하나를, 단일 파일 입력 옆에

스킬은 `releaseScript` · `ciWorkflow` 와 같은 모양(`inputs.file`)으로 그 둘 옆에 선언하고, 블록 주석이 스킬을 부른다.

1. **공백이 가드가 지키려는 바로 그 편집 위에 있다.** 두 스킬 가드는 스킬의 글을 단언하고, 다른 어떤 파일의 편집도 둘을
   실패시킬 수 없다. 선언이 없으면 둘이 로컬에서 실패할 수 있는 빌드는 무관한 이유로 `:aimon-core:test` 를 다시 도는
   빌드뿐이다
2. **공백을 적어 둘 자리가 없다.** 공백에 걸리는 사람은 스킬을 고치는 사람이다. 스킬은 프롬프트라, 거기 둔 메모는
   `/release` 마다 모델이 읽는 글이 된다. 테스트 javadoc 과 빌드 스크립트는 테스트나 빌드를 고치는 사람이 읽는다
3. 스킬만 바꾸는 편집은 드물어 비용이 작다

선언은 **파일 하나**다. 테스트는 한 파일을 읽고, `.claude/skills/` 에는 어떤 테스트도 읽지 않는 다른 스킬이 있다. 읽는
것보다 넓은 입력은 아무 이유 없이 스위트를 다시 돌린다.

스킬이 **옮겨지면** 양쪽에서 크게 실패한다 — 선언은 없는 입력 파일을, 테스트는 없는 경로를 만난다. 스킬이 새 경로로
**복사**되고 테스트 상수가 따라가고 옛 파일이 남으면, 선언이 옛 파일을 계속 지문으로 삼아 새 파일에 대한 공백이 조용히
돌아온다. 빌드 주석이 스킬을 이름으로 부르는 것이 그 완화다.

### 6.5 태그 스캔의 소스는 선언하지 않는다

같은 클래스의 태그 스캔(`everyTestTagIsGated`)은 `modules/` 와 `samples/` 아래 모든 테스트 소스를 읽는다. 그것을 선언하면
**어느 모듈의 테스트를 고쳐도** `aimon-core` 의 스위트 — 빌드 테스트의 대부분 — 가 다시 돈다. 인구조사 소스를 선언한
비용과 달리 릴리스를 건드리지 않는 기여자 모두에게 붙으므로 선언하지 않는다.

공백은 좁다. 입력이 아닌 테스트 소스를 가진 모듈 — `modules/aimon-llm-*` 밖의 모듈, 또는 샘플 — 의 `@Tag` 만 바꾼 로컬
빌드가 이 테스트를 `UP-TO-DATE` 로 보고할 수 있다. `aimon-core` 자신의 `@Tag` 편집은 컴파일된 테스트 클래스가 입력이라
공백에 들지 않고, 프로바이더 모듈은 `providerModuleTestSources` 가 덮는다. 테스트 javadoc 의 *What this cannot see* 가 이
범위를 적는다.

### 6.6 측정 결론

| 측정 | 결과 | 날짜 |
|---|---|---|
| 인구조사 소스 선언 전후, `aimon-llm-openai` 테스트 소스에 키 게이트를 더함 | 전: `:aimon-core:test` `UP-TO-DATE`. 후: 태스크가 실행되어 인구조사가 실패한다. 설정 파일에 없는 `aimon-llm-*` 디렉토리의 소스도 입력이고, 같은 프로브를 `aimon-sandbox-docker` 에 두면 `UP-TO-DATE` 다 — 입력은 인구조사보다 넓지 않다 | 2026-09-11 |
| 스킬 선언 전후, 두 스킬 가드가 실패하는 이전 판본의 스킬로 교체 | 전: `UP-TO-DATE`. 후: `releaseSkill` 변경으로 실행되어 두 스킬 가드가 실패한다. 선언되지 않은 `aimon-sandbox-docker` 테스트 소스의 태그 편집은 여전히 `UP-TO-DATE` 다 | 2026-09-11 |
| 선언의 비용 — 무필터 `:aimon-core:test --rerun` 한 번 | 8168 테스트 40초(인구조사 소스 선언 시점), 8201 테스트 36초(스킬 선언 시점) | 2026-09-11 |
| 구성 캐시와 implicit dependency | `--configuration-cache` 에서 문제 보고 없음. 한 호출에서 `:aimon-llm-openai:spotlessApply` 와 `:aimon-core:test` 를 함께 돌려 검증 문제 없음 | 2026-09-11 |

---

## 7. 게이트 사본 — 셋은 대조하고, 퍼블리싱 가이드는 대조하지 않는다

### 7.1 게이트는 한 호출의 다섯 태스크다

릴리스 게이트는 `scripts/release.sh` 의 Gradle 호출 하나 —
`checkAll integrationTest packagingTest playwrightTest jacocoTestCoverageVerification` — 다. CI 는 같은 태스크를 `build` ·
`integration` · `coverage` 잡의 스텝으로 나눠 돌고, 빌드를 실패시키지 않는 보고서 태스크 `jacocoTestReport` 를 하나 더
돈다. pre-flight 가 Docker 데몬을 확인하는 것은 `integrationTest` 때문이다. 세 계층 태스크와 `test` 사이에는
`shouldRunAfter` 관계만 있으므로 `build` · `check` 는 계층을 더하지 않는다.

그래서 게이트를 `checkAll` 하나로 적는 사본은 거짓이고, `jacocoTestReport` 를 빼고 "CI 와 같은 태스크" 라고 적는 사본은
반대 방향으로 거짓이다.

### 7.2 테스트가 대조하는 사본 셋

CI 워크플로 · 릴리스 스크립트 · `/release` 스킬은 한 태스크 목록의 수기 사본 셋이고, `ReleaseGateMatchesCiGateTest` 가
셋을 대조한다.

| 단언 | 무엇을 막는가 |
|---|---|
| 게이트가 `checkAll` 을 부른다 | CI 가 도는 집계 태스크를 빠뜨린 게이트 |
| CI `run: ./gradlew …` 스텝의 모든 태스크가 게이트에도 있다 — `REPORTING_ONLY_CI_TASKS`(`jacocoTestReport`) 제외 | 스크립트를 고치지 않고 CI 에 검증 스텝을 더하는 것 |
| 게이트에 `-x` 제외가 없다 | 모듈을 게이트에서 빼는 것 |
| `test` 가 제외하는 모든 태그가 두 게이트가 도는 태스크에 대응한다 | 어느 게이트에도 들지 않는 계층 |
| 스킬의 ``Quality gate = `…` `` 줄이 스크립트의 게이트 태스크를 부른다 | 발행하려는 사람이 읽는 틀린 게이트 서술 |
| 스킬의 어떤 줄도 `opt-in` · `outside both` · `outside the gate` 를 게이트 태스크 이름과 함께 두지 않는다 | 게이트된 계층을 게이트 밖이라고 부르는 산문 |

스킬 가드는 고정 구문을 매칭한다. 문장을 고쳐 쓰면 크게 실패해야 하기 때문이다 — 이 문장이 조용히 참이 아니게 되는 것을
아무도 알아채지 못하는 것이 가드가 있는 이유다.

이 대조는 태스크 **이름**만 본다. `checkAll` 자신이 무엇에 의존하는지는 루트 빌드 스크립트가 집계 하나를 소유하는 것으로
닫는다.

### 7.3 퍼블리싱 가이드는 네 번째 사본이고, 읽는 검사가 없다

[`docs/project/publishing-guide.md`](../../project/publishing-guide.md) 의 스크립트 단계 표도 게이트를 서술한다 — 품질 게이트
행은 다섯 태스크와 CI 잡 이름을 부르고 `jacocoTestReport` 를 한 번 부른다(§7.1). 이 사본은 어떤 검사도 읽지 않는다.

가드를 두지 않는다. 가드는 유지해야 할 한국어 고정 구문, 이 클래스가 읽는 네 번째 저장소 파일, 선언할 입력 하나를 더
만든다(§6.1 이 그대로 적용된다). 가이드 행의 드리프트는 받아들이고, 열린 항목으로 둔다(§10).

---

## 8. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 스크립트 안에서 키를 unset (`unset …` 또는 `env -u … $GRADLE …`) | 위험이 사는 셸에 닿지 않고 기록도 남기지 않으며, 출력이 없어 붙들 수 없다(§2.3) |
| 비어 있지 않은 값만 거부 (`matches = ".+"` 미러) | 가드가 클래스마다의 정규식에 기대게 된다. `matches = ".*"` 게이트는 빈 값으로 켜지고, 처방은 같다(§2.1) |
| 탈출 플래그 (`--allow-provider-keys` 등) | 두 선택지를 플래그 뒤에 함께 두는 것이고, 라이브 계층을 릴리스 게이트에 되돌려 `LA-1` 과 어긋난다 |
| 이 클래스의 다른 테스트처럼 스크립트 텍스트를 스캔 | 가드는 동작이다. 주석 처리·반전·도달 불가 검사가 통과하고, 값이 출력되지 않음을 보일 수 없다 |
| 검사를 `scripts/lib/…sh` 로 뽑아 스크립트와 테스트가 함께 source | 테스트가 헬퍼를 붙들 뿐 `release.sh` 가 게이트 전에 그것을 부르는지는 붙들지 못한다. 선언할 입력 파일도 하나 는다 |
| 실제 저장소에서 `--dry-run` 으로 실행 | `main` · 클린 트리 · origin 동기화 · 네트워크 · Docker · 발행 크리덴셜이 필요하고, 가드가 사라지면 단위 테스트 안에서 게이트 전체가 재귀로 돈다 |
| 스크립트에 자기 점검 플래그 (`--check-env`) | 테스트만을 위해 스크립트의 명령줄 표면을 늘린다 |
| 거부 사례가 "pre-flight 전에 거부" 까지만 단언 | 검사 위치의 이유(`git fetch`, trap 노트)가 붙들리지 않은 산문으로 남는다. 막는 데 드는 것은 stub 한 줄과 사례당 단언 하나다 |
| 거부 테스트를 형제 클래스로 분리 | 동작은 같다. 이 클래스가 불변식을 말하고 *What is enforced* 가 릴리스 게이트의 모든 조건을 찾는 자리이며, 이미 `release.sh` 를 입력으로 읽는다 |
| 두 이름만 하드코딩 | 오늘은 맞고, 다음 프로바이더에서 조용히 틀린다 |
| 모든 모듈을 스캔하고 `AIMON_DOCKER_IT` · `AIMON_KUBERNETES_IT` 를 허용 목록에 | 허용 목록에는 그 둘이 거부 밖에 있어도 되는 적힌 이유가 필요한데, 그 이유는 확인되지 않았다(`LA-2`) |
| 스크립트가 테스트 소스를 grep 해 거부 목록을 만든다 | 릴리스 스크립트가 Java 를 파싱하게 된다 |
| 같음을 붙든다 — 인구조사 밖 게이트 변수를 설정하고 pre-flight 도달을 요구 | 세울 수 있는 변수가 `LA-2` 의 둘뿐이라 `LA-2` 의 "아니오" 를 테스트에 새기고, `LA-2` 가 "예" 로 답하는 날 실패한다 |
| 같음을 붙든다 — 스크립트의 `for name in …` 루프 단어를 목록과 비교 | 동작을 읽지 않고 돌린다는 이 클래스의 규칙에 어긋난다. 철자만 붙들고, 다른 자리의 두 번째 거부는 보지 못한다 |
| 같음을 붙든다 — 임의의 센티널 변수 하나가 거부되지 않음을 요구 | 이름 하나만 붙든다. 다른 이름을 거부하는 스크립트는 여전히 통과하므로 "같다" 는 여전히 과장이다 |
| 입력 공백을 선언하지 않고 적어 둔다 (테스트 javadoc, 빌드 주석, `CONTRIBUTING.md`, 스킬) | 공백에 걸리는 사람이 읽는 자리가 아니고, 스킬에 두면 모델이 매번 읽는 프롬프트가 된다(§6.1 · §6.4) |
| 태그 스캔의 소스도 선언 | 어느 모듈의 테스트를 고쳐도 `aimon-core` 스위트가 다시 돈다 — 릴리스를 건드리지 않는 기여자에게 붙는 비용이다(§6.5) |
| `:aimon-core:test` 에 `outputs.upToDateWhen { false }` | 모든 빌드에서 수천 개 테스트를 다시 돈다 |
| 스캔을 자기 입력을 가진 별도 `Test` 태스크로 분리 | 새 태스크는 `build.yml` · `release.sh` · 루트 `checkAll` · 스킬의 `Quality gate =` 줄을 고치게 한다. 로컬 전용 공백을 닫으려고 이 클래스가 지키는 게이트 태스크 목록 계약을 바꾼다 |
| glob 을 시스템 프로퍼티로 테스트에 넘겨 출처를 하나로 | IDE 에서 돌리면 범위를 잃거나 폴백이 필요하고, 폴백은 결국 두 번째 사본이다. 기존 입력 중 그렇게 도는 것이 없다 |
| 인구조사 결과를 파일로 뽑는 작은 태스크를 두고 그 파일을 입력으로 (답이 바뀔 때만 재실행) | Kotlin 으로 쓴 두 번째 파서가 Java 파서의 실행 여부를 정한다. 그 누락은 조용한 skip 이다 — §5.3 이 막는 실패 모양이다. 태그 스캔 선언의 비용이 치를 만해지면 그때 고를 모양이다 |
| 스킬을 테스트 클래스패스에 올려 거기서 읽음 | 발행되는 모듈의 테스트 소스 세트를 바꾸고, 이 클래스가 읽는 세 루트 파일 중 하나만 다르게 읽힌다 |
| `.claude/skills/release/` 나 `.claude/skills/` 디렉토리를 선언 | 테스트는 파일 하나를 읽는다. 읽는 것보다 넓은 입력은 이유 없이 스위트를 다시 돌린다 |
| 테스트가 읽는 모든 루트 파일이 선언되었는지 확인하는 셀프체크 | Java 테스트가 Kotlin DSL 텍스트를 파싱한다. 같은 쌍의 사본에 대해 서로 가리키는 주석을 골랐다(§6.3) |
| 퍼블리싱 가이드의 게이트 행을 읽는 가드 | 유지할 한국어 고정 구문, 네 번째 읽기 파일, 선언할 입력이 하나씩 는다(§7.3) |

---

## 9. 하지 말 것

- **키의 값을 확장하지 말 것.** `${!name+set}` 으로 존재만 묻는다. `${!name}` · `printenv NAME` · `$OPENAI_KEY` 를 쓰지
  않는다(`printenv` 는 샌드박스 `PATH` 에도 없다)
- **`set -u` 아래에서 빈 배열을 확장하지 말 것.** macOS `/bin/bash` 3.2 는 빈 배열의 `"${arr[@]}"` 에서 멈춘다. 이름은
  문자열에 누적한다
- **거부 절에서 builtin 이 아닌 명령을 부르지 말 것.** `[` · `printf` · `exit` 과 스크립트 헬퍼만 쓰고, 출력은 stderr, 종료는
  1 이다
- **검사를 옮기지 말 것** — 인자 루프 위로, `cd` 아래로, `trap cleanup EXIT` 아래로, `log "Pre-flight checks"` 아래로.
  넷 다 테스트가 잡는다
- **게이트 줄 위에 `$GRADLE ` 로 시작하는 줄이나 `quality gate` 가 든 주석을 더하지 말 것.** 게이트 로케이터는
  `quality gate` 가 든 첫 `#` 줄에서 섹션을 시작하고 그 뒤 첫 `$GRADLE ` 줄을 게이트로 읽는데, 스크립트 헤더 주석이 이미 그
  말을 담고 있다
- **거부를 unset 으로 바꾸거나 탈출 플래그를 더하지 말 것.** `LA-1` 을 뒤집는 결정과 함께만 다시 본다
- **거부를 skip 으로 약하게 하지 말 것.** 만들어진 stub 이 실행되지 않으면 실패한다 — skip 은 bash 와 POSIX 권한이 없을
  때뿐이다
- **센티널을 키 모양으로 만들지 말 것.** `sk-` 로 시작하는 값은 시크릿 스캐너가 건다
- **인구조사를 한 가지 애노테이션 모양만 읽는 정규식으로 좁히지 말 것.** 새 이름을 조용히 놓친다
- **테스트가 거부 목록과 게이트의 같음을 붙들게 하지 말 것.** `LA-2` 를 테스트가 정한다. 문장도 "적어도" 로 쓴다
- **`refusedKeysAreTheProviderModulesKeyGates` 의 메서드 이름을 바꾸지 말 것.** `LA-2` 가 그 이름으로 가리킨다
- **인구조사 범위와 `providerModuleTestSources` 를 따로 바꾸지 말 것.** 넓히면 둘 다 넓힌다
- **테스트가 새 저장소 파일을 읽게 하면서 입력 선언을 빠뜨리지 말 것.** 선언은 읽는 파일만큼만 — 디렉토리로 넓히지 않는다
- **스킬을 새 경로로 복사하고 옛 파일을 남기지 말 것.** 옮긴다
- **스킬의 ``Quality gate = `…` `` 줄 모양을 바꾸지 말 것.** 그리고 어떤 줄에도 `opt-in` · `outside both` ·
  `outside the gate` 를 게이트 태스크 이름과 함께 두지 않는다
- **게이트를 `checkAll` 하나로 적거나, `jacocoTestReport` 를 빼고 "CI 와 같은 태스크" 라고 적지 말 것**
- **`/release` 를 도는 에이전트가 거부 메시지의 처방을 스스로 실행하지 말 것.** unset 할지는 사용자가 정한다

---

## 10. 남은 것

- **샌드박스 통합 테스트의 두 변수를 거부할지** — `AIMON_DOCKER_IT` · `AIMON_KUBERNETES_IT` 를 export 한 셸의 릴리스는 CI 가
  돌지 않는 클래스를 게이트 안에서 돈다. [`../../backlog/live-api-test-tier.md`](../../backlog/live-api-test-tier.md) `LA-2`
- **태그 스캔의 입력 공백** — `modules/aimon-llm-*` 밖의 모듈이나 샘플의 `@Tag` 만 바꾼 로컬 빌드는 이 테스트를
  `UP-TO-DATE` 로 건너뛸 수 있다. 닫는 비용(모든 테스트 편집이 `aimon-core` 스위트를 다시 돌림)이 공백보다 커서 선언하지
  않았고, 백로그에 등록되지 않았다
- **퍼블리싱 가이드의 게이트 서술을 읽는 검사가 없다** — 네 번째 사본은 다시 어긋날 수 있다. 가드의 비용(고정 구문, 읽기
  파일, 입력) 때문에 두지 않았고, 백로그에 등록되지 않았다
- **미측정: 키를 export 한 셸에서의 실제 릴리스** — 거부는 테스트의 샌드박스에서만 돌았다
- **미측정: `unset` 뒤 같은 셸에서, 키가 있을 때 먼저 뜬 Gradle 데몬** — 데몬이 빌드마다 클라이언트의 환경을 적용해 키가
  `Test` 워커에 닿지 않는다는 것에 `CONTRIBUTING.md` 의 "`unset` it before you build" 가 기댄다. `Exec` 자식으로만
  대리 측정되었고 `Test` 워커로는 재지 않았다
- **미측정: 스킬이 경로에 없을 때** — Gradle 이 입력 파일 부재로 테스트 전에 태스크를 거부하리라 예상한다
  (`releaseScript` · `ciWorkflow` 와 같은 모양). `.claude/` 아래 파일을 지워야 재기 때문에 재지 않았다
- **미측정: 입력 파일만 바꾼 뒤의 로컬 `checkAll` 전 과정** — 태스크가 실행된다는 것과 추가되는 스위트의 비용은 따로 쟀지만
  한 빌드로 합쳐 재지는 않았다

---

## 부록 — 참조 파일 지도

| 파일 | 무엇을 확인하나 |
|---|---|
| `scripts/release.sh` | `0. provider API keys` 절 — 거부 루프, 메시지, 주석이 부르는 네 이동. 헤더의 "Order of operations", `1. pre-flight` 의 Docker 검사, `4. quality gate` 의 게이트 줄 |
| `modules/aimon-core/src/test/java/at/aimon/core/architecture/ReleaseGateMatchesCiGateTest.java` | 클래스 javadoc 의 *What is enforced* · *What this cannot see*. 상수 `PROVIDER_KEY_VARIABLES` · `KEY_SENTINEL` · `KEY_GATE_ANNOTATION` · `KEY_GATE_NAME` · `PROVIDER_MODULE_PREFIX` · `REPORTING_ONLY_CI_TASKS` · `UNGATED_CLAIM_MARKERS` · `GATE_SECTION_MARKER`. 메서드 `runReleaseScript` · `requireRunnableStub` · `assertRefused` · `refusedKeysAreTheProviderModulesKeyGates` |
| `modules/aimon-core/build.gradle.kts` | `tasks.test` 의 입력 일곱과 그 위 주석 |
| `modules/aimon-spring-boot-starter/build.gradle.kts` | 같은 규칙의 다른 사례 — `documentation` 입력 |
| `.claude/skills/release/SKILL.md` | 안전 게이트 목록, Notes 의 키 거부 bullet, `Quality gate =` 줄 |
| `.github/workflows/build.yml` | `build` · `integration` · `coverage` 잡의 Gradle 스텝 |
| `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` · `modules/aimon-browser-playwright/build.gradle.kts` | `test` 의 태그 제외, 계층 태스크의 `shouldRunAfter(test)` |
| `modules/aimon-llm-anthropic/src/test/…` · `modules/aimon-llm-openai/src/test/…` | 라이브 API 클래스 넷의 `@EnabledIfEnvironmentVariable` |
| `docs/project/publishing-guide.md` | 스크립트 단계 표 — 대조되지 않는 게이트 사본 |
| `docs/backlog/live-api-test-tier.md` | `LA-1`(닫힘 — 수동 전용), `LA-2`(열림) |

---

## 관련 문서

- [`../../backlog/live-api-test-tier.md`](../../backlog/live-api-test-tier.md) — 라이브 API 계층의 결정(`LA-1`)과 거부가 멈춘 자리(`LA-2`)
- [`../../project/publishing-guide.md`](../../project/publishing-guide.md) — 릴리스 스크립트의 단계와 운영 절차
- [`../../../CONTRIBUTING.md`](../../../CONTRIBUTING.md#live-api-tests) — 라이브 API 테스트를 돌리는 법, 키를 명령 앞에 두는 규칙
- [`test-classpath-shipped-versions.md`](test-classpath-shipped-versions.md) — 같은 축의 형제 문서: 모듈의 테스트가 발행 버전 위에서 도는가
