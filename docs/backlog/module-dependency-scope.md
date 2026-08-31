# 백엔드 모듈의 POM 스코프 — 등록 항목 1건 (열림 1 · 결정 대기)

`.claude/rules/code-style.md` 와 `.claude/rules/architecture.md` 가 같은 규칙을 두 번 적고 있다 —
**implementation 모듈은 `implementation(project(":aimon-core"))` 를 쓰고, 파사드만 `api()` 를 쓴다.**
이 문서는 그 규칙을 뒤집자는 것이 아니라, 규칙이 감수하기로 한 **비용이 어디에 남아 있는지**를
기록한다.

출처는 2026-08-31 의 아키텍처 리뷰다.

---

## 0. 착수하며 정정한 것 — 리뷰가 이 규칙을 모르고 17개 모듈을 바꿨다

리뷰는 이 자리를 **결함**으로 진단하고 발행 모듈 17개를 `api` 로 바꿨다. 그 다음에
`.claude/rules/code-style.md` 를 읽었고, 되돌렸다.

**규칙을 몰랐던 것이 아니라 찾지 않았다.** 그리고 규칙은 리뷰가 발견했다고 여긴 것을 이미
알고 있었다 — 규칙 본문이 스스로 *"Gradle 의 `implementation` 은 POM 에 `<scope>runtime</scope>`
으로 나가므로 소비자 앱은 jar 는 받지만 `Agent` / `LiveSession` / `Tool` 에 **컴파일할 수 없다**"*
라고 적고, 그럼에도 파사드가 아닌 모듈은 `implementation` 을 유지한다고 정한다.

즉 리뷰가 "발견" 한 메커니즘은 **결정문 안에 이미 인용되어 있었다.** 이것이 백로그
`README.md` 규칙 다섯의 마지막 문단이 말하는 것과 같은 모양이다 — *"처방이 '이 값을 바꿔라'
형태일 때는, 고치기 전에 그 값이 지금 왜 그 값인지 설명이 있는지 먼저 찾는다. 설명이 있으면
그것은 결함이 아니라 결정이고, 항목이 반박해야 할 대상은 코드가 아니라 그 설명이다."*

그 문장이 겨눈 곳은 코드 주석이었는데, 이 건에서 설명이 있던 곳은 **`.claude/rules/`** 였다.
그래서 규칙 다섯에 자리가 하나 더 붙는다: 값이 왜 그 값인지 찾을 때 보는 곳은 코드 주석과
`docs/` 만이 아니다.

**남긴 것** — 그 실수를 잡는 가드를 대신 넣었다
(`PublishedModuleApiScopeTest`, `modules/aimon-core/src/test/java/at/aimon/core/architecture/`).
비-파사드가 `api` 를 쓰면 실패하고, `FACADE_MODULES` 에 이름이 있는데 실제로는 파사드가 아니면
그것도 실패한다. **틀린 처방을 되돌리는 것으로 끝내지 않고 다음 사람이 같은 자리에서 같은
결론에 이르는 것을 막는 장치를 남긴다**(규칙 둘).

---

## 1. 열린 항목

### D-1 — 백엔드 모듈의 POM 이 말하는 것과 그 모듈의 공개 API 가 다르다 · **열림 · 결정 대기**

**무엇** — 발행 모듈 17개가 core 타입을 공개 시그니처에 노출하면서 core 를 runtime 스코프로
발행한다. 이대로 둘지, 축을 바꿀지 결정한다.

**왜 — 관측 가능한 결과**

측정(2026-08-31): `implementation(project(":aimon-core"))` 를 선언한 발행 모듈 **17개 전부**가
core 타입을 공개 상속/구현 또는 공개 메서드 시그니처에 노출한다.

| 모듈 | 예 |
|------|-----|
| `aimon-session-redis` | `RedisSessionRecordStore implements SessionRecordStore`, `provision(SessionId): SessionRecordView` |
| `aimon-filesystem-s3` | `S3FileSystem implements VirtualFileSystem` |
| `aimon-llm-openai` | `OpenAILlmClient implements LlmClient` |
| `aimon-rewake-webhook` | 공개 API 의 core 타입 2종 (가장 적은 모듈) |

`implementation` 은 POM 에 `<scope>runtime</scope>` 로 나가므로, `aimon-session-redis` 만
선언한 소비자는 자기가 방금 만든 객체의 타입 이름을 **쓸 수 없다.**

**그런데 이것이 오늘 누군가를 물고 있지는 않다.** `README.md:114` 이 *"Add `aimon-core` and at
least one LLM provider"* 라고 안내하고 예제가 둘 다 선언하며, BOM 이 버전을 맞춘다. 문서화된
경로는 동작한다. 남는 것은 **POM 이 사실이 아닌 말을 한다**는 것뿐이고, 규칙은 그 대가로
전이 표면을 좁게 유지하는 쪽을 골랐다.

**결정에 필요한 것** (규칙 넷 — 결정문 전에 전제를 소스로 확인한다)

| 물어야 할 것 | 지금 아는 것 |
|-------------|-------------|
| "전이 누출을 막는다" 가 실제로 무엇을 막고 있나 | **미확인.** 소비자가 core 를 어차피 선언하므로, 좁아지는 것이 컴파일 클래스패스인지 아무것도 아닌지 실측되지 않았다 |
| 저장소 밖 소비자가 실제로 어떻게 선언하나 | **표본 0.** `roadmap.md` §3 — 밖에서 온 백엔드 구현 0건 |
| 규칙을 뒤집으면 무엇이 깨지나 | 깨지지 않는다. `runtime` → `compile` 은 **넓히는** 방향이라 기존 소비자에게 호환된다 |

가운데 줄이 이 항목이 **결정 대기**인 이유다. 세 번째 줄이 "비용이 낮다" 를 말하지만, 낮은
비용은 착수 근거가 아니다 — 첫 줄의 답을 모르는 채 바꾸면 **규칙이 지키려던 것이 무엇이었는지
모르는 채로** 지우게 된다.

**언제 다시 볼까** — 셋 중 하나.
- **저장소 밖에서 온 첫 백엔드 구현**이 생길 때 (`api-stability.md` §6 의 `1.0` 진입 조건이기도
  하다). 그 사람이 어떻게 선언하는지가 첫 줄의 실측이다
- 소비자가 "`aimon-session-redis` 만 넣었더니 컴파일이 안 된다" 를 보고할 때 — 그때는 결정이
  아니라 결함이다
- `aimon-bootstrap` · `aimon-spring-boot-starter` 말고 **세 번째 파사드**가 생길 때. 파사드가
  늘어난다는 것은 "재수출이 정상 경로" 라는 뜻이고 규칙의 전제가 흔들린다

**여기서 하지 않기로 한 것** — 리뷰가 한 번 한 것(17개를 `api` 로)을 다시 하지 않는다.
되돌린 이유가 §0 이고, 다시 하려면 이 항목의 첫 줄에 답이 있어야 한다.

---

## 2. 관련

- `.claude/rules/code-style.md` · `.claude/rules/architecture.md` — 규칙 원문
- `modules/aimon-filesystem-testkit/build.gradle.kts` — 규칙이 **왜** 그런지 산문으로 적힌 유일한
  빌드 파일 (*"to keep a published POM honest"*)
- [`multi-instance-readiness.md`](multi-instance-readiness.md) — 같은 리뷰에서 나온 다른 항목
- [`../project/api-stability.md`](../project/api-stability.md) — `0.x` 가 약속하는 것
