# 오픈소스 전환 — 체계 정리 및 문서 이중화 계획

> Status: **Phase 0 ~ Phase 5 전부 완료.** 남은 것은 코드로 할 수 없는 저장소 설정뿐이다
> (§Phase 0 의 "저장소 설정" 목록 + Pages 소스를 GitHub Actions 로 지정 + public 전환).
> 이 문서는 `docs/README.md` 의 규칙에 따른 **진행 추적 문서**다. 작업이 끝나면 삭제하고,
> 남길 가치가 있는 근거는 `design/` 또는 `project/` 로 옮긴다.

## 0. 결정 사항 (확정)

| 항목 | 결정 | 근거 |
|------|------|------|
| 정본 언어 | **한국어** | 기존 34,352줄이 이미 한국어. 정본을 뒤집으면 34k줄 재작성 후에야 첫 커밋이 나온다 |
| 번역 언어 | 영어 | |
| 번역 범위 | **Tier 1 + `features/`** (약 14,900줄, 전체의 43%) | `design/`·`backlog/`는 내부 설계 근거 — 외부 기여자의 진입 경로가 아니다 |
| 배포 | **GitHub Pages + MkDocs Material** | 34k줄은 리포지토리 마크다운 탐색의 한계를 넘었다 |

### 0.1 레이아웃: 폴더 분리가 아니라 **접미사(suffix)** 방식

`docs/ko/` + `docs/en/` 로 가르는 폴더 방식은 **채택하지 않는다.** 현재 저장소에는 다음이 걸려 있다.

| 깨지는 것 | 개수 |
|-----------|------|
| 문서 간 상대 링크 | 946 |
| Java Javadoc 안의 `docs/...` 참조 | 105 |
| `CLAUDE.md` 의 `@docs/...` 참조 | 4 |

폴더 방식은 이 1,055개를 전부 고쳐야 하고, 상대 경로는 문자열 치환으로 고칠 수 없다
(`docs/README.md` 의 링크 규칙 참조). 접미사 방식은 **정본 파일이 한 칸도 움직이지 않는다.**

```
docs/features/tool/tool-development-guide.md       ← 한국어 정본 (경로 불변)
docs/features/tool/tool-development-guide.en.md    ← 영어 번역 (신규)
```

`mkdocs-static-i18n` 이 이 규약을 기본 지원한다.

### 0.2 해소 — 사이트 루트(`/`)의 기본 언어는 **한국어**

정본 언어와 사이트 기본 언어는 원래 별개 결정이고, GitHub 유입을 생각하면 루트 URL 이 영어인 쪽
(1안)이 선호였다. 그래서 문서만 읽고 단정하지 않고 spike 로 확인했다 — **1안은 취향 문제가 아니라
빌드가 되지 않는다.**

`mkdocs-static-i18n` 의 접미사 모드에서 **무접미사 파일은 기본 로케일의 것**이고, 기본 로케일은
언제나 사이트 루트에 빌드된다. 즉 "무접미사 = 한국어"와 "기본 로케일 = 영어"는 동시에 성립할 수
없다. 1안대로 `default: true` 를 `en` 에 주면 `index.md`(한국어 정본)와 `index.md`(영어 자리)가
같은 자리를 다투고 빌드가 이렇게 끊긴다.

```
Exception: Conflicting files for the default language 'en':
choose either 'index.md' or 'index.md' but not both
```

1안을 살리려면 정본 34k줄에 전부 `.ko` 접미사를 붙여야 하는데, 그것은 §0.1 이 접미사 방식을 고른
이유(**정본 파일이 한 칸도 움직이지 않는다**)를 스스로 무르는 일이다.

**따라서 2안으로 확정한다.**

| URL | 내용 | 파일 |
|-----|------|------|
| `/` | 한국어 (정본) | 무접미사 — 경로 불변 |
| `/en/` | 영어 (번역) | `.en.md` |

2안은 spike 에서 실제로 확인했다 — 로케일별 내용이 맞게 갈리고, `<html lang="ko">` / `<html lang="en">`
가 붙고, 언어 스위처가 렌더링된다. 번역이 없는 문서는 **404 가 아니라 `/en/` 아래에 한국어 정본이
그대로 서빙된다** — Phase 4 가 11개 배치로 나뉘어 진행되는 동안 사이트가 늘 온전하다는 뜻이고,
`mkdocs.yml` 의 "번역 누락 시 정본 fallback" 요구사항이 별도 설정 없이 충족된다는 뜻이다.

루트가 한국어인 것의 대가(영어권 유입이 한 번 더 클릭해야 함)는 언어 스위처와 `README.md` 의
영어 링크로 갚는다. 빌드가 되지 않는 선택지와 맞바꿀 만한 비용이 아니다.

---

## 1. 현황 진단

### 1.1 강점 — 이미 잘 되어 있는 것

체계 정리를 "처음부터"로 오해하면 안 된다. 다음은 이미 상당한 수준이다.

- `docs/README.md` 의 문서 분류 체계(기능 축 / `design` vs `plan` vs `backlog` 구분, 배치 규칙)는
  대부분의 OSS 프로젝트보다 명확하다. **재설계 대상이 아니라 보존 대상이다**
- 상대 링크 946개 중 실제 깨진 것 **5개** — 링크 위생이 좋다
- `overview/glossary.md`·`scope-model.md` 가 용어 수명을 강제 수준으로 정의 → 번역 용어표의 뼈대가 이미 있다
- CI(`checkAll`) 가 포맷·스타일·테스트를 한 게이트로 묶고 있다
- Apache 2.0 LICENSE, Maven Central 퍼블리싱, BOM 모듈 모두 갖춰짐

### 1.2 즉시 고쳐야 할 것 — 지키지 않은 약속

`README.md` 와 `CONTRIBUTING.md` 가 **존재하지 않는 파일을 링크**하고 있다. 오픈소스 저장소의
첫인상에서 가장 먼저 클릭되는 링크들이다.

| 위치 | 링크 | 상태 |
|------|------|------|
| `README.md` | `CODE_OF_CONDUCT.md` | **없음** |
| `README.md` | `SECURITY.md` | **없음** |
| `CONTRIBUTING.md` | `CODE_OF_CONDUCT.md` | **없음** |
| `CONTRIBUTING.md` | `SECURITY.md` | **없음** |
| `CONTRIBUTING.md` | "*Bug report* 템플릿으로 이슈 열기" | 템플릿 **없음** |
| `README.md` 배지 | `actions/workflows/ci.yml` | 실제 파일은 `build.yml` → **배지 깨짐** |
| `CONTRIBUTING.md` (2곳) | "Discussion 을 여세요" | 저장소에 Discussions **비활성** |

> 최초 스캔은 `docs/references/agentskills-specification.md:234` 도 깨진 링크로 잡았으나 **오탐**이었다 —
> 그 링크는 ```` ```markdown ```` 펜스 안의 예시 문법이라 렌더링되지 않는다. 스캐너가 코드 펜스를
> 무시한 탓이며, Phase 2 의 링크 검사 CI 는 펜스와 인라인 코드를 제외해야 한다.

### 1.3 낡았거나 중복인 것

- **`DEVELOP.md`** — "프로젝트 구조" 절이 `at.aimon.agent.core` / `at.aimon.agent.ext` 를 설명하는데,
  실제 패키지는 `at.aimon.core.*` 다(`ext.tools.*` → `core.tools.*` 개명 반영 안 됨).
  나머지 내용(빌드·테스트·포맷·SOLID)은 `CONTRIBUTING.md` 와 거의 전부 중복이다
- **`docs/backlog/spring-boot-starter-open-items.md`** — 2,774줄. 열린 항목 추적을 문서 한 개로
  하고 있다. 외부 기여자가 집어 갈 수 있으려면 GitHub Issues 여야 한다
- **`samples/`** — 샘플 프로젝트 3개에 README 가 하나도 없다

### 1.4 없는 것 — 오픈소스 운영 인프라

`CODE_OF_CONDUCT` / `SECURITY` / 이슈·PR 템플릿 / `dependabot.yml` / 릴리스 워크플로 /
API 안정성 정책 / 메인테이너·거버넌스 문서 / 로드맵 / 링크 검사 CI.

---

## 2. 단계별 계획

각 Phase 는 독립적으로 머지 가능하다. Phase 4(번역)만 분량이 크고, 나머지는 짧다.

### Phase 0 — 지키지 않은 약속 메우기 ✅ 완료

가장 먼저 한다. 지금 저장소를 방문한 사람이 만나는 깨진 링크를 없애는 것이 우선이다.

- [x] `CODE_OF_CONDUCT.md` — Contributor Covenant 2.1, 신고 연락처 `kangwoo@gmail.com`
- [x] `SECURITY.md` — 지원 버전, 비공개 신고 경로(GitHub Security Advisory + 이메일), 응답 목표,
      **범위 명시** — 에이전트 프레임워크의 "설계대로 동작" 과 "취약점" 경계를 in/out 으로 갈랐다
      (권한 우회·샌드박스 탈출·자격증명 유출·세션 격리 실패는 in / 권한 안에 머무는 프롬프트 인젝션,
      부여된 권한으로 저지른 피해는 out)
- [x] `.github/ISSUE_TEMPLATE/bug_report.yml` — 모듈·버전·Java·LLM 프로바이더 구조화 수집
- [x] `.github/ISSUE_TEMPLATE/feature_request.yml` — "해결책"보다 **"어떤 문제에 막혔는가"**를 필수로
- [x] `.github/ISSUE_TEMPLATE/config.yml` — 빈 이슈 차단, 보안·문서·기여가이드 링크
- [x] `.github/PULL_REQUEST_TEMPLATE.md` — `CONTRIBUTING.md` 의 체크리스트와 **동일 문구**로 맞춤
      (두 곳이 다른 말을 하지 않게)
- [x] `README.md` CI 배지 `ci.yml` → `build.yml`, 라벨 `CI` → `Build`
- [x] ~~`agentskills-specification.md:234` 링크~~ — 오탐이었다(위 참조). 조치 불필요

**완료 기준 달성:** 펜스 인식 스캐너 기준 상대 링크 947개 중 broken **0건**.
이슈 템플릿 3종 YAML 파싱 검증 통과.

#### Phase 0 에서 남은 것 — 저장소 설정 (메인테이너 조작 필요)

파일이 아니라 GitHub 저장소 설정이라 커밋으로 해결되지 않는다.

- [ ] **Discussions 활성화** — `CONTRIBUTING.md` 가 두 군데서 "Discussion 을 열라"고 안내하는데
      현재 비활성이다. 활성화하거나, 아니면 CONTRIBUTING 의 두 문장을 이슈 안내로 바꾼다
- [ ] **저장소 description 과 topics 입력** — 지금 비어 있다. GitHub 검색·추천의 주 입력이다
- [ ] **Private vulnerability reporting 활성화** — `SECURITY.md` 가 안내하는
      `/security/advisories/new` 경로는 이 설정이 켜져야 동작한다
- [ ] **Pages 소스를 "GitHub Actions" 로 지정** — Phase 3 에서 넣은 `.github/workflows/docs.yml`
      의 배포 잡은 이 설정 없이는 실패한다. 워크플로가 있는 것과 Pages 가 켜져 있는 것은 별개다
- [ ] (공개 전환 시점에) 저장소를 public 으로

### Phase 1 — 저장소 체계 정리 `PR 2~3개`

- [x] **`DEVELOP.md` 폐기** — 낡은 패키지 구조 절은 삭제하고, 나머지는 `CONTRIBUTING.md` 로 흡수.
      루트에 개발자 문서가 `CONTRIBUTING`·`DEVELOP`·`CLAUDE` 셋으로 갈려 있는 상태를 둘로 줄인다.
      **한국어 온보딩 문서가 이 삭제로 0개가 됐다** — `CONTRIBUTING.ko.md` 를 Phase 4 목록에 추가했다
- [x] **`docs/project/api-stability.md` 신설** — 0.x 동안의 호환성 약속, 공개 API 의 경계,
      deprecation 주기, 1.0 진입 조건. 계획이 적어 둔 "`@Experimental` 표기 규칙" 은 **쓸 수 없었다** —
      그 애노테이션은 이 저장소에 존재하지 않는다. 문서는 대신 *패키지 위치가 공개 여부를 정한다* 는
      실제 규칙을 적고, 애노테이션을 새로 만들려면 ArchUnit 규칙이 함께 와야 한다고 못박았다
- [x] **릴리스 자동화** — **계획의 전제가 틀렸다.** `scripts/release.sh` 가 이미 릴리스를 자동화하고
      있었고, 그 순서(게이트 → 배포 → 커밋 → 태그 → 푸시)는 배포가 되돌릴 수 없고 git 은 되돌릴 수
      있기 때문에 의도된 것이다. 태그 트리거로 배포하는 워크플로는 그 순서를 뒤집는다.
      실제로 빠져 있던 것은 **릴리스 노트**였다 — 태그 9개에 GitHub Release 1개.
      `.github/workflows/release.yml` 은 CHANGELOG 에서 노트를 잘라 Release 만 만든다
- [x] `.github/dependabot.yml` — gradle + github-actions. 모든 패턴이 `libs.versions.toml` 의
      실제 좌표에 걸리는지 검증했다 — 걸리지 않는 패턴은 조용히 그룹을 해제한다
- [x] `samples/*/README.md` 3개 — 각 샘플이 무엇을 **증명**하는지와 실행 방법
- [x] `MAINTAINERS.md` + `docs/project/roadmap.md` (1.0 까지의 목표)
- [x] **레이블 체계** — 11개를 만들었다. 8개는 `MAINTAINERS.md` 의 영역 표를 그대로 옮긴 `area:*`
      이고(영역 유지보수자가 자기 영역만 걸러 볼 수 있어야 그 표가 실물이 된다), 나머지는
      `needs-triage` · `blocked` · `breaking-change` 다. `blocked` 의 설명은 **트리거를 이슈 안에
      적도록 요구**한다 — 막힌 이유가 없는 `blocked` 는 영원히 붙어 있는다. 레이블이 생겼으므로
      Phase 0 에서 뺐던 `needs-triage` 를 이슈 템플릿 둘에 되돌렸다
- [x] `docs/backlog/spring-boot-starter-open-items.md` → GitHub Issues 이관 —
      **계획이 "2,774줄 전부" 라고 적은 것은 틀렸다.** 등록 34건 중 **열린 것은 4건**이고 나머지
      30건은 닫힘·해소다. 닫힌 것을 이슈 30개로 만들면 목록이 그만큼 조용해지기만 하고, 이 문서의
      실제 값어치인 §0.1~§0.7 정정 기록은 **항목 단위로 쪼개지지 않는다** — 그 절들은 한 항목이 아니라
      *여러 항목이 같은 방식으로 틀렸다* 를 말한다. 그리고 "문서는 링크 인덱스만 남긴다" 는
      `docs/backlog/README.md` 의 규칙("닫힌 항목은 지우지 않는다")과 정면으로 어긋난다.
      **열린 4건 중 3건**(B-7 · B-15 · B-25)을 영어 이슈 [#49](https://github.com/kangwoo/aimon-core/issues/49) ·
      [#50](https://github.com/kangwoo/aimon-core/issues/50) · [#51](https://github.com/kangwoo/aimon-core/issues/51)
      로 열고, 양쪽에 링크 인덱스를 넣었다. B-23 은 만들지 않았다 — 기다리는 것이 저장소 밖 앱의
      일정이라 이슈로 열어도 집어 갈 사람이 없고 트리거가 이슈 쪽에서 발화하지 않는다

### Phase 2 — 번역 전 정본 정비 `PR 1~2개`

**번역보다 먼저 한다.** 정본이 흔들리는 상태에서 번역하면 같은 문서를 두 번 옮기게 된다.

- [x] **번역 용어표 `docs/project/translation-glossary.md`** — 14,900줄을 일관되게 옮기려면 필수.
      `overview/glossary.md` 의 수명 용어가 뼈대다.
      번역 **금지** 목록을 명시한다: 타입명(`LiveSession`, `SessionRecord`), 패키지 경로, 파일 경로,
      코드 블록 내부, `turn`/`iteration`/`execution` 구분(이미 한국어 문서에서도 영어로 쓰고 있다).

      금지 목록에 **동결된 와이어 이름**(`conversationId`, `conversation_locks`)을 한 줄 더 넣었다.
      번역자가 가장 그럴듯하게 저지를 수 있는 실수가 그것들을 "일관성을 위해" `sessionId` 로
      고치는 것이기 때문이다 — 어긋나 보이는 것이 정상이라는 사실까지가 문서다.
      코드 블록은 통째로 얼리지 않고 경계를 **실행되는 것 / 읽히는 것** 사이에 그었다:
      식별자·명령어·출력은 원문, 그 안의 주석은 번역한다.
- [x] **문서 frontmatter 도입** — 번역 동기화를 자동 판정하기 위한 최소 메타데이터
      ```yaml
      ---
      translated_from: <정본 파일 경로>
      source_commit: <정본의 마지막 커밋 SHA>
      ---
      ```
      영어 파일에만 붙인다. 정본은 건드리지 않는다. 규약은 `docs/README.md` 의 **번역 규칙** 절에 적었다.
      한 가지를 명시적으로 못박았다 — **본문과 `source_commit` 은 같은 커밋에서 고친다.**
      따로 고치면 이 필드는 "정본의 어디까지 반영했는가" 가 아니라 "마지막으로 누가 신경 썼는가" 라는
      다른 뜻이 되고, Phase 5 의 staleness 판정이 그 위에 서지 못한다
- [x] **링크 검사 CI** — `scripts/check-doc-links.py` + `build.yml` 의 `docs-links` 잡.
      번역본이 늘면 링크 수가 두 배가 된다. 지금 자동화하지 않으면 Phase 4 에서 감당이 안 된다.
      현재 181개 파일 / 상대 링크 1,249개, 깨진 것 0.

      **`lychee` 를 쓰지 않았다.** 그 도구의 강점은 외부 URL 검사인데, 이 저장소가 링크 검사를
      원하는 이유는 그것이 아니다 — 번역이 늘면서 두 배가 되는 것은 **상대 링크**이고, 외부 URL 을
      게이트에 넣으면 남의 호스트가 죽은 날 우리 PR 이 빨개진다. 빨간 게이트는 읽히지 않게 된다.

      대신 **앵커 검사를 넣었다.** 없는 경로는 링크를 눌러 보면 알지만, 없는 `#앵커` 는 페이지가
      정상적으로 열리고 맨 위에 떨어질 뿐이라 잘못 안내됐다는 사실 자체가 독자에게 전달되지 않는다.
      실제로 이 작업 중에 기억으로 지어낸 앵커를 한 번 심었다. 첫 실행이 잡아낸 것도 그런 종류다 —
      `aimon-core-integration-via-cli-reference.md` 의 목차 3번이 `#3-…--aimoncli-call` 을 가리키고
      있었는데 GitHub 은 `AimonCli.call()` 의 `.` 을 하이픈 없이 지우므로 `aimonclicall` 이 맞다.
      바로 아래 4번 항목은 같은 규칙을 제대로 지키고 있었다.
- [x] Tier 경계를 `docs/README.md` 에 명시 — 어느 디렉토리가 번역 대상인지 규칙으로 못박는다.

      상태를 둘이 아니라 **셋**으로 적었다: 대상 / 아직 아님 / 대상 아님.
      `project/`·`references/`·`migration/` 은 Phase 4 배치에 없지만 `design/`·`backlog/`·`plan/` 과
      성격이 다르다 — 앞의 셋은 **미룬 것**이라 표의 상태만 바꾸면 승격되고, 뒤의 셋은 **번역하지 않기로
      결정한 것**이라 뒤집으려면 결정을 먼저 뒤집어야 한다. 둘을 한 칸에 넣으면 다음 사람이 그 차이를
      알 방법이 없다.

      루트 문서(`README.md`·`CONTRIBUTING.md`·`SECURITY.md`·`CODE_OF_CONDUCT.md`·`MAINTAINERS.md`)가
      영어 정본이라는 사실도 같은 절에 적었다 — Phase 4-11 이 방향이 반대인 이유가 여기 있고,
      그 배치에 도착해서야 알게 되면 늦다

### Phase 3 — 문서 사이트 인프라 `PR 1~2개`

- [x] **spike: `mkdocs-static-i18n` 접미사 모드 + 루트 로케일 검증** (§0.2 미결 해소).
      1안이 불가하면 2안으로 확정하고 이 문서에 기록
      → **1안은 빌드가 되지 않는다.** 2안 확정, 근거와 재현 출력은 §0.2 에 기록했다
- [x] `mkdocs.yml` — Material 테마, `static-i18n`, 언어 스위처, 번역 누락 시 정본 fallback
      → fallback 은 별도 설정이 아니라 `fallback_to_default` 의 기본값이다. 번역이 없는 문서는
      `/en/` 아래에 한국어 정본이 그대로 서빙된다(404 아님) — Phase 4 가 11개 배치로 나뉘어 도는
      동안 사이트가 늘 온전하다
- [x] **한국어 검색 설정** — 기본값으로 두면 한국어 검색이 사실상 동작하지 않으니
      **완료 기준에 한국어 검색 실사용 확인을 포함**한다
      → 실제 인덱스로 확인했다. 아래 "한국어 검색" 참조
- [x] `.github/workflows/docs.yml` — main push 시 Pages 배포
- [x] `docs/README.md` 의 인덱스와 `mkdocs.yml` nav 의 이중 관리 방지 규칙 결정
      (nav 자동 생성 플러그인 사용 여부)
      → **플러그인 없이 `nav:` 자체를 쓰지 않는다.** 아래 "nav 이중 관리" 참조

#### 한국어 검색 — 실측으로 정한 `separator`

착수 전 가정("MkDocs 검색은 형태소 분석이 없으니 한국어가 안 될 것이다")은 **절반만 맞았다.**
형태소 분석이 없는 것은 사실이지만, 그래서 안 되는 것은 아니다. 실제로 무슨 일이 일어나는지 확인했다.

1. Material 의 한국어 로케일은 lunr 파이프라인을 `" "` 로 둔다 — stemmer 도, stopword 필터도,
   **trimmer 도** 없다
2. Material 은 모든 질의어 뒤에 `*` 를 붙인다 (`search/query/transform`). 즉 **모든 검색은 접두 검색**이다
3. 한국어 조사는 접미사이므로 2번이 1번을 구제한다 — `세션` 이 `세션을`·`세션이`·`세션은` 을 모두 잡는다

문제는 다른 데 있었다. trimmer 가 없어 **구두점이 토큰에 붙어 있고**, 검색은 접두 검색이므로 단어가
토큰의 **맨 앞**에 있을 때만 잡힌다. 기본 `separator` 로는 이렇게 된다.

| 질의 | 기본 `[\s\-]+` | 튜닝 후 |
|------|----------------|---------|
| `이터레이션` | **0** — 문서에 단독으로 없고 `Iteration(이터레이션)` 으로만 나온다 | 5 |
| `세션` | 12 | 17 |
| `수명` | 6 | 9 |
| `execution` | 3 | 6 |

`(`·`)`·`,`·`"`·`[`·`]` 를 구분자에 넣어 해소했다. **`.` 은 일부러 넣지 않았다** — 넣으면
`aimon.llm.provider` 와 `at.aimon.core.agent` 가 조각난다. 실제 사이트 인덱스로 재확인한 결과
한국어 12개·식별자 10개 질의가 모두 의도한 문서를 최상위로 반환한다.

> 중간에 한 번 틀렸던 것을 남겨 둔다. 순정 lunr 로 먼저 실험했을 때는 영어 Porter stemmer 가 질의어만
> 어간화해 `livesession` → `livesess` 로 깨지는 것으로 나왔다. **Material 에서는 일어나지 않는다** —
> Material 은 `pipeline` 에 없는 함수를 색인·검색 파이프라인 양쪽에서 제거하기 때문이다.
> 순정 lunr 의 기본값을 Material 의 동작으로 옮겨 적으면 안 된다.

#### nav 이중 관리 — `nav:` 를 쓰지 않는 것으로 해소

`mkdocs.yml` 에 `nav:` 를 두면 `docs/README.md` 의 인덱스와 **같은 목록을 두 벌 유지**하게 된다.
새 문서를 추가한 사람이 한쪽만 고치면 사이트에서 사라지거나(누락) 인덱스에서 사라진다.

`nav:` 를 생략하면 MkDocs 가 디렉토리 트리에서 내비게이션을 자동 생성한다. 순서는 알파벳순이라
손으로 고른 순서보다 못하지만, **손으로 유지하는 목록이 정확히 하나(`docs/README.md`)** 로 유지된다.
`awesome-pages` 같은 플러그인은 `.pages` 파일로 순서를 되찾아 주지만 그것 역시 유지 대상이 하나 더
느는 일이라, 기여자 한 명 규모에서는 자동 생성 쪽이 낫다고 판단했다.

#### 부수 결과 — 앵커가 GitHub 과 사이트에서 같아졌다

`toc` 확장의 `slugify` 를 `pymdownx.slugs.slugify(case=lower)` 로 맞췄더니, Phase 2 에서 고친
`#3-부트스트랩-흐름--aimonclicall` 이 사이트에서도 **글자 그대로** 같은 앵커로 생성된다.
`scripts/check-doc-links.py` 는 GitHub 의 슬러그 규칙을 모델링한 것이므로, 이제 그 검사가
사이트의 앵커까지 함께 지켜 준다 — 게이트를 하나 더 만들지 않고 범위가 넓어졌다.

#### `docs/` 밖을 가리키는 링크 199개

문서는 두 표면에서 읽힌다. GitHub 에서는 `../../../modules/.../ReadTool.java` 가 맞는 링크이고,
사이트에서는 `modules/` 가 존재하지 않으므로 죽은 링크다. 원본을 고치면 사이트를 살리는 대신 GitHub 을
깨뜨린다.

그래서 원본은 상대 경로 그대로 두고, `scripts/mkdocs_github_links.py` 훅이 **`docs_dir` 를 벗어나는
링크만** 렌더 시점에 GitHub URL 로 바꾼다. `docs/` 안에 머무는 링크는 손대지 않으므로 MkDocs 의 링크
해석과 `.en.md` 번역 매핑이 그대로 동작한다. 덕분에 CI 가 `mkdocs build --strict` 를 쓸 수 있다
(경고 0). 플러그인 의존성 없이 MkDocs 내장 `hooks:` 로 처리했다.

> 훅을 쓰면서 한 번 틀렸다. 인라인 코드를 보호하려고 줄을 코드 스팬 기준으로 잘라 붙였더니
> ``[`ReadTool`](...)`` 처럼 **링크 텍스트가 백틱인 링크가 반토막**나서 199개 중 54개만 바뀌었다.
> 자르지 말고 **위치로 건너뛰도록** 고쳤다.

### Phase 4 — 번역 실행 ✅ 완료

디렉토리 단위로 PR 을 쪼갠다. 한 PR = 한 디렉토리 = 리뷰 가능한 크기.

| 배치 | 대상 | 줄 수 | 우선순위 |
|------|------|-------|---------|
| 4-1 ✅ | `docs/README.md` | 206 | 최우선 — 사이트 진입점 |
| 4-2 ✅ | `overview/` (4개) | 1,669 | 최우선 — 용어·수명이 다른 모든 번역의 기준 |
| 4-3 ✅ | `getting-started/` (2개) | 2,800 | 최우선 — 신규 사용자 경로 |
| 4-4 ✅ | `features/tool/` (3개) | 2,125 | 높음 — 확장점 1순위 |
| 4-5 ✅ | `features/session/` (3개) | 1,201 | 높음 |
| 4-6 ✅ | `features/hook/` (2개) | 1,265 | 높음 |
| 4-7 ✅ | `features/agent-execution/` (3개) | 1,020 | 중간 |
| 4-8 ✅ | `features/workflow/`·`subagent/`·`skill/` | 1,933 | 중간 |
| 4-9 ✅ | `features/llm/`·`memory/`·`knowledge/` | 1,906 | 중간 |
| 4-10 ✅ | `features/observability/`·`scheduling/`·`README.md` | 812 | 낮음 |
| 4-11 ✅ | `CONTRIBUTING.ko.md` — 루트 문서는 영어 정본이므로 **방향이 반대**인 유일한 배치 | 331 | 중간 |
| | **합계** | **약 15,200줄** | |

번역 규칙(각 PR 에 동일 적용):

- 코드 블록·타입명·패키지 경로·파일 경로는 **원문 그대로**
- 표의 구조와 행 순서를 보존한다 — 정본과 diff 가 대응돼야 동기화 판정이 가능하다
- `IMPORTANT:` 같은 강조 마커를 유지한다
- 상대 링크는 같은 언어의 파일을 가리키게 조정(`foo.md` → `foo.en.md`)
- 정본에 없는 내용을 추가하지 않는다. 정본이 부실하면 **정본을 먼저 고치고** 번역한다

계획은 배치당 PR 1개였으나 실제로는 배치당 커밋 1~2개로 진행했다(작업이 한 브랜치에서
연속으로 이뤄졌기 때문이며, 쪼개는 단위 자체는 계획대로 디렉토리였다). `docs/features/`
아래에 번역이 없는 파일은 남아 있지 않고, 번역본 30개 전부가 유효한 frontmatter 를 갖는다.

번역하면서 규칙이 셋 늘었다. 셋 다 실제로 한 번씩 틀린 뒤에 얻은 것이다.

- **ASCII 다이어그램은 고치는 것이 아니라 다시 그린다.** 한글은 두 칸 폭이라 상자 가운데의
  텍스트를 치환하면 정렬이 깨진다. 폭을 assert 하는 생성 스크립트로 만들어야 조용히 어긋나지
  않는다. 다만 한국어가 줄 **끝에만** 있으면(`←` 뒤 주석 등) ASCII 접두부가 그대로이므로 치환해도 된다
- **코드 펜스 안의 `#` 주석도 번역 대상이다.** 제목 개수 검사(`grep -c '^#'`)에 함께 잡히므로,
  지우면 구조 대응이 깨진다
- **정본의 낡음은 번역본이 고치지 않는다.** 그대로 옮기고, 정본을 별도 커밋으로 고친다

### Phase 5 — 유지 체계 ✅ 완료

번역은 만드는 것보다 **낡지 않게 두는 것**이 어렵다.

- [x] `scripts/check-translation-staleness.py` — 번역본의 `source_commit` 과 정본의 현재
      이력을 비교해 뒤처진 번역을 목록화. **계획의 `.sh` 대신 `.py`** 로 갔다 — git plumbing
      호출과 frontmatter 파싱이 필요했고, 옆에 이미 `scripts/check-doc-links.py` 가 있어서
      기여자가 익힐 도구가 늘지 않는 쪽을 택했다
- [x] CI 에 **경고로만** 연결한다(실패시키지 않는다). 번역 지연이 정본 수정 PR 을 막으면
      정본이 낡기 시작한다 — 더 나쁜 실패 모드다. `build.yml` 의 `translation-staleness` 잡이며
      스크립트 자체가 stale 을 찾아도 0 으로 끝난다(`--strict` 는 릴리스 직전용 opt-in)
- [x] `CONTRIBUTING.md` 에 절 추가: 정본을 고쳤을 때 번역을 어떻게 처리하는가
      (같이 고치거나, 못 고치면 stale 로 두고 이슈를 남긴다)
- [x] `CLAUDE.md` 에 번역 규칙 반영 — 에이전트가 문서를 고칠 때 양쪽을 인지하게 한다

#### 방향을 가정하지 않는다

체커는 "영어 파일" 을 찾지 않고 `translated_from` 이 있는 파일을 찾는다. 그 필드가 정본을
**언어와 무관하게** 지목하므로 `docs/**/*.en.md`(한국어 정본)와 `CONTRIBUTING.ko.md`(영어 정본)가
같은 코드로 처리된다. 계획서의 "영어 파일의 `source_commit`" 이라는 표현은 배치 4-11 이 생기기
전의 것이다.

#### 공동 수정 지연 (co-edit lag)

만들고 처음 돌렸을 때 `docs/README.en.md` 가 stale 로 잡혔는데, 확인해 보니 **번역본에 이미 그
내용이 들어 있었다.** 원인은 구조적이다 — `source_commit` 은 **이미 존재하는** 커밋만 적을 수 있으므로,
정본과 번역본을 같은 커밋에서 고치면 그 값은 **항상 한 커밋 뒤처진다**. 순진하게 만들면 정상적인
동시 수정이 전부 stale 로 보고되고, 그러면 아무도 보고서를 읽지 않게 된다.

그래서 체커는 **정본과 그 번역본을 함께 건드린 커밋을 건너뛴다.** 이 필터가 이 도구가 쓸모 있는지
없는지를 가르는 한 줄이다.

두 번째 함정은 CI 쪽이다. `actions/checkout` 의 기본 얕은 클론에는 `git log` 가 볼 이력이 없어서
**모든 번역이 unresolvable 로 보고된다.** 잡에 `fetch-depth: 0` 이 필요하다.

---

## 3. 순서가 이렇게 되는 이유

```
Phase 0 (약속 메우기) ─┐
                       ├→ Phase 2 (정본 정비) → Phase 4 (번역) → Phase 5 (유지)
Phase 1 (체계 정리)  ─┘                      ↗
                          Phase 3 (사이트)  ┘
```

- **Phase 0 이 먼저인 이유:** 지금 이 순간 저장소를 보는 사람이 만나는 문제다. 다른 어떤 작업보다 싸고 즉효다
- **Phase 2 가 번역 앞인 이유:** 용어표 없이 14,900줄을 옮기면 `LiveSession` 을 세 가지로 번역한
  결과물이 나온다. 되돌리는 비용이 처음 하는 비용보다 크다
- **Phase 3 과 Phase 4 가 병렬인 이유:** 사이트 인프라는 번역본이 0개여도 구축·검증할 수 있다.
  오히려 먼저 세워 두면 번역 PR 마다 렌더링 결과를 즉시 확인할 수 있다
- **Phase 5 가 마지막인 이유:** 동기화 검사는 대상이 있어야 의미가 있다

## 4. 범위에서 뺀 것

의식적으로 제외한 항목이다. 필요해지면 별도 계획으로 다룬다.

- `design/`·`backlog/` 번역 — 설계 근거는 내부 독자용이다. 외부 기여자가 여기까지 오는 시점이면
  이슈로 물어볼 수 있다. 필요해지면 문서 단위로 선별 번역한다
- `CHANGELOG.md` (1,472줄) 번역 — 릴리스 노트는 영어 한 벌이면 충분하고, 이미 영어다
- 문서 내용 자체의 재작성 — 이 계획은 **체계와 언어**를 다룬다. 개별 문서의 품질 개선은
  낡은 부분(`DEVELOP.md` 패키지 구조)을 걷어내는 선까지만 한다
