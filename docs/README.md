# AIMON Documentation

AIMON 프로젝트 문서의 시작점. 문서는 **기능 축**으로 정리되어 있다 — 독자 역할(가이드/개발/운영)이
아니라 *무엇에 대한 문서인가*로 나눈다.

## 어떤 문서를 읽을까

| 상황 | 시작 지점 |
|------|----------|
| **AIMON이 무슨 기능을 갖고 있는지 훑고 싶다** | [`overview/features.md`](overview/features.md) ← **기능 카탈로그** |
| AIMON이 무엇이고 어떤 추상화를 가지는지 보고 싶다 | [`overview/architecture.md`](overview/architecture.md) |
| **무엇이 이 프레임워크 바깥에 있고 무엇이 필수인지** 알고 싶다 | [`overview/context.md`](overview/context.md) |
| 여러 노드로 띄우면 무엇이 어디에 뜨는지 보고 싶다 | [`overview/deployment.md`](overview/deployment.md) |
| Session / Live session / Turn 같은 용어의 수명이 헷갈린다 | [`overview/glossary.md`](overview/glossary.md) |
| 이 값을 어디 두고 언제 닫아야 하는지 모르겠다 | [`overview/scope-model.md`](overview/scope-model.md) |
| 내 애플리케이션에 AIMON을 임베딩하고 싶다 | [`getting-started/embedding-agent-in-application.md`](getting-started/embedding-agent-in-application.md) |
| 동작하는 통합 예시를 코드 단위로 따라가고 싶다 | [`getting-started/aimon-core-integration-via-cli-reference.md`](getting-started/aimon-core-integration-via-cli-reference.md) |
| 특정 기능(도구·스킬·훅·세션·워크플로 …)을 깊게 보고 싶다 | [`features/`](features/) |
| 어떤 컴포넌트가 왜 그렇게 설계되었는지 근거가 궁금하다 | [`design/README.md`](design/README.md) ← **설계 문서 색인** |
| 0.0.x → 0.1.x 업그레이드 시 깨진 것이 있다 | [`migration/`](migration/) |
| 프로젝트에 기여하거나 퍼블리싱하고 싶다 | [`project/`](project/) |
| 진행 중인 작업이 어디까지 왔는지 보고 싶다 | [`plan/`](plan/) — 진행 중인 계획이 있을 때만 존재한다 (아래 참조) |
| **끝난 작업이 무엇을 남기고 갔는지** 보고 싶다 | [`backlog/`](backlog/) ← 열린 항목의 **정본** |

## 디렉토리 구조

```
docs/
├── overview/          AIMON 전체 조망 — 기능 카탈로그, 아키텍처, 경계, 배포, 용어, 수명 규칙
├── getting-started/   처음 붙일 때 — 임베딩, 통합 레퍼런스
├── features/          기능별 상세 가이드 (사용·개발·운영을 기능 단위로 묶음)
├── design/            설계 근거와 구현 노트
├── backlog/           끝난 작업이 남긴 열린 항목 — 열림/닫힘의 정본
├── references/        외부 표준·패턴 명세
├── migration/         버전 업그레이드 절차, 개명·동결 이름 조회표
└── project/           프로젝트 운영 — 방향, 호환성 약속, 코딩 원칙, 릴리스
```

### [`overview/`](overview/) — 전체 조망

| 문서 | 목적 |
|------|------|
| [`features.md`](overview/features.md) | **기능 카탈로그** — `aimon-core`가 무엇을 할 수 있는지, 진입점은 무엇이고 코어 내장인지 별도 모듈인지 |
| [`architecture.md`](overview/architecture.md) | 핵심 추상화(Agent, AgentExecutor, Tool, LlmClient, VirtualFileSystem, …) 레퍼런스 |
| [`context.md`](overview/context.md) | **시스템 경계** — 무엇이 바깥에 있고, 어느 모듈이 붙이고, 빼면 무엇이 남는가 |
| [`deployment.md`](overview/deployment.md) | **배포 뷰** — 단일 노드 vs 멀티 노드, 노드 로컬과 공유의 경계, 배포 체크리스트 |
| [`glossary.md`](overview/glossary.md) | 용어집 — 각 용어의 수명과 `SessionRecord`:`LiveSession` = 1:0..N |
| [`scope-model.md`](overview/scope-model.md) | 수명·소유권·소멸 책임 규칙 — 새 타입을 만들거나 `close()`를 부르기 전에 본다 |

### [`getting-started/`](getting-started/) — 처음 붙일 때

| 문서 | 목적 |
|------|------|
| [`embedding-agent-in-application.md`](getting-started/embedding-agent-in-application.md) | Spring Boot / SDK에 임베딩하기 |
| [`aimon-core-integration-via-cli-reference.md`](getting-started/aimon-core-integration-via-cli-reference.md) | `aimon-cli` 부트스트랩 코드를 한 줄씩 따라가며 통합 패턴 설명 |

### [`features/`](features/) — 기능별 상세

기능 하나당 디렉토리 하나. 그 기능의 사용·개발·운영 문서가 같은 폴더에 있다.
전체 색인은 [`features/README.md`](features/README.md).

| 영역 | 대표 문서 |
|------|----------|
| [`agent-execution/`](features/agent-execution/) | 명령 큐, 중단 가능 도구, `<system-reminder>` 규약 |
| [`session/`](features/session/) | 세션 튜토리얼, `LiveSession` API, 멀티 노드 배포 |
| [`tool/`](features/tool/) | **도구 개발 가이드**, 병렬 실행, 브라우저 도구 |
| [`skill/`](features/skill/) | 빌트인 Agent/Skill 시스템 |
| [`hook/`](features/hook/) | 훅 개발, 훅 설정·핫리로드 |
| [`subagent/`](features/subagent/) | 코드로 서브에이전트 정의 |
| [`workflow/`](features/workflow/) | 워크플로 CLI, 코드로 조립·재개 |
| [`llm/`](features/llm/) | LLM Provider 개발, 사용량·비용 미터링 |
| [`memory/`](features/memory/) | 메모리 사용 |
| [`knowledge/`](features/knowledge/) | OpenSearch Knowledge Store |
| [`scheduling/`](features/scheduling/) | Quartz 클러스터 배포 |
| [`observability/`](features/observability/) | 실행 트레이싱 |

### [`design/`](design/) — 설계 문서

컴포넌트의 설계 근거와 기각한 대안. 일반 사용자는 `features/` 가이드로 충분하며, 내부 동작이나
변경 이유를 알아야 할 때 참고한다. **도메인 축**으로 나뉘며, 디렉토리 이름은 `features/` 의 것과
일치한다 — 같은 주제의 사용 가이드와 설계 근거가 마주 보게 하려는 것이다.

- [`design/README.md`](design/README.md) — **전체 색인**. 도메인별 문서 목록과 옛 경로 대응표
- [`design/<도메인>/`](design/) — `agent-execution` · `session` · `tool` · `skill` · `hook` ·
  `subagent` · `workflow` · `llm` · `filesystem` · `memory` · `knowledge` · `scheduling` ·
  `observability` · `integration`
- [`design/backlog/`](design/backlog/) — 의식적으로 보류한 항목. 진행 트래커가 아니라 "왜 지금
  하지 않는가"의 근거와 재검토 트리거를 남긴 문서. 최상위 [`backlog/`](backlog/) 와 헷갈리기 쉽다 —
  아래에서 구분한다

구현 여부는 디렉토리가 아니라 각 문서 첫머리의 `Status` 한 줄이 말한다. 예전에는 `design/implemented/`
라는 상태 축이 있었으나, 한 문서의 절반만 구현된 경우에 디렉토리가 거짓말을 해서 폐기했다 —
옛 경로 대응표는 [`design/README.md`](design/README.md) §4 에 있다.

기능별로 어떤 설계 문서가 있는지는 [`features/README.md`](features/README.md) 의 각 절
**설계 근거** 링크로도 따라갈 수 있다.

### [`backlog/`](backlog/) — 끝난 작업이 남긴 열린 항목

IMPORTANT: **무엇이 열려 있는지의 정본은 여기다.** 설계 문서의 우선순위 표(P0/P1/P2, 미해결 U)는
**설계 시점의 기록**으로 동결되어 있으므로, 거기서 취소선이 없다고 열려 있는 것이 아니다. 이 규칙이
필요하다는 증거는 첫 등록 때 바로 나왔다 — 스타터 설계 문서의 표는 이미 배선된 항목 하나를 여전히
열린 것처럼 싣고 있었다. 자세한 것은 [`backlog/README.md`](backlog/README.md).

`design/backlog/` 와 이름이 겹치지만 담는 것이 다르다.

| | [`design/backlog/`](design/backlog/) | [`backlog/`](backlog/) |
|---|---|---|
| 담는 것 | **설계 자체를 보류한 것** — 형태는 확정됐지만 소비자가 없어 만들지 않았다 | **구현하고 남은 것** — 만들었는데 어떤 항목을 뒤로 미뤘다 |
| 단위 | 문서 하나 = 보류한 설계 하나 | 문서 하나 = 끝난 작업 하나가 남긴 항목 전부 |
| 나오는 시점 | 설계 중 | 작업 종료 시 |

`plan/` 과도 다르다. 계획 문서는 **진행 중인** 작업의 다음 할 일을 담고 끝나면 지우지만, 여기 항목은
작업이 끝난 **뒤에** 생겨서 누가 집어 갈 때까지 남는다. 그래서 아래 "상태 표기는 `plan/` 에만" 규칙의
예외다 — 다만 담는 것은 진행률이 아니라 **열림/닫힘 하나**여야 한다.

### [`references/`](references/) — 외부 명세 / 패턴

AIMON이 참조·확장하는 외부 표준이나 패턴 명세.

| 문서 | 내용 |
|------|------|
| [`agentskills-specification.md`](references/agentskills-specification.md) | Agent Skills 표준 포맷 명세 |
| [`aimon-skill-extensions.md`](references/aimon-skill-extensions.md) | AIMON이 표준에 더한 확장 frontmatter 필드 |
| [`hooks-specification.md`](references/hooks-specification.md) | Claude Code 훅 스펙과의 parity 경계 — 매핑·확장·미지원 |
| [`llm-wiki.md`](references/llm-wiki.md) | LLM Wiki 패턴 — `WikiKnowledgeStore`의 컨셉 출처 |

### [`migration/`](migration/) — 버전 업그레이드 가이드

| 문서 | 대상 버전 |
|------|----------|
| [`custom-command-to-skill.md`](migration/custom-command-to-skill.md) | 0.0.37 → 0.1.0 (`CustomCommand` 제거) |
| [`rename-maps.md`](migration/rename-maps.md) | 버전 무관 — **옛 이름 ↔ 새 이름 조회표**. 개명이 생길 때마다 자란다 |
| [`frozen-names.md`](migration/frozen-names.md) | 버전 무관 — **개명하지 않기로 한 이름들**. 고치면 데이터 마이그레이션이다 |

IMPORTANT: 뒤의 둘은 업그레이드 **절차**가 아니라 **조회표**다. `CHANGELOG.md` 의 `[Unreleased]` 안에
있었는데, 릴리스가 나가는 순간 버전 헤딩 아래 묻히고 그것을 인용하던 열두 파일이 전부 "가서
0.2.0 절을 뒤져라" 가 되기 때문에 옮겼다. 변경 기록은 한 번 쓰고 다시 안 고치지만 이 표들은 계속
조회되고 계속 자란다 — `design/` 과 `plan/` 을 가르는 것과 같은 기준이다.

### [`project/`](project/) — 프로젝트 운영

| 문서 | 내용 |
|------|------|
| [`roadmap.md`](project/roadmap.md) | 어디로 가고 있는가 — 지금 하는 일, 막혀 있는 것, `1.0` 까지 |
| [`api-stability.md`](project/api-stability.md) | `0.x` 가 무엇을 약속하고 무엇을 약속하지 않는가 |
| [`solid-principles.md`](project/solid-principles.md) | 프로젝트에 적용하는 SOLID 원칙 |
| [`translation-glossary.md`](project/translation-glossary.md) | 한국어 정본 → 영어 번역 용어표 (아래 **번역 규칙**) |
| [`publishing-guide.md`](project/publishing-guide.md) | Maven Central 퍼블리싱 절차 |
| [`aimon-core-coverage-priority.md`](project/aimon-core-coverage-priority.md) | 테스트 커버리지 우선순위 |

### `plan/` — 진행 추적 문서

여러 PR에 걸쳐 진행되는 작업의 **현재 상태와 다음 할 일**. 설계 문서와 짝을 이루되 역할이 다르다 —
설계 문서는 근거를 남기고 좀처럼 바뀌지 않으며, 계획 문서는 작업이 진척될 때마다 갱신되고 **끝나면
지운다**(결과는 design 문서와 git 히스토리에 남는다).

디렉토리는 **진행 중인 계획이 있을 때만 존재한다.** 지금은
[`open-source-readiness.md`](plan/open-source-readiness.md)(오픈소스 전환) 하나가 진행 중이며,
그것이 끝나 지워지면 디렉토리도 함께 사라지는 것이 정상 상태다.

`OrcaAgentRuntime` 통합 테스트 계층(L0–L4) 구축은 이 규칙의 첫 적용 사례다 — 작업이 끝나면서 계획
문서는 지워졌고, 남길 가치가 있던 근거는
[`design/agent-execution/integration-test-layers.md`](design/agent-execution/integration-test-layers.md)
로 옮겨갔다.

`turn` 용어 정리(iteration·실행을 `turn` 이라 부르던 자리)도 같은 경로를 밟았다. 다만 옮겨간 곳이
design 문서가 아니다 — 남길 가치가 있던 것이 설계 근거가 아니라 **어휘 규칙**이었으므로
[`overview/glossary.md`](overview/glossary.md) §4 › 실행 단위에 규칙으로 들어갔고(재발 방지의 본체가
거기다), 무엇을 왜 고쳤는지는 [`CHANGELOG.md`](../CHANGELOG.md) 에 남았다.

## 문서 작성 규칙

### 어디에 둘까

새 문서를 추가할 때 막히면, **누가 읽는가가 아니라 무엇에 대한 문서인가**로 정한다.

| 문서의 내용 | 위치 |
|------------|------|
| AIMON 전체를 조망하는 것 (기능 목록, 용어, 수명 규칙) | `overview/` |
| 처음 붙이는 절차 | `getting-started/` |
| 특정 기능의 사용·개발·운영 방법 | `features/<기능>/` |
| 설계 결정의 근거 / 기각한 대안 | `design/<도메인>/` — 도메인은 `features/` 와 같은 이름 |
| 의식적으로 보류한 설계 항목 | `design/backlog/` |
| **끝난 작업이 남기고 간 열린 항목** | `backlog/` — 열림/닫힘의 정본 |
| 여러 PR에 걸친 작업의 진행 상태 / 다음 할 일 | `plan/` |
| 외부 명세나 패턴 인용 | `references/` |
| 버전 업그레이드 절차, 그리고 개명·동결 이름 조회표 | `migration/` |
| 프로젝트 자체의 운영 (원칙·릴리스·품질) | `project/` |

IMPORTANT: 같은 기능의 "개발자용"과 "운영자용" 문서를 서로 다른 디렉토리로 가르지 않는다.
둘 다 `features/<기능>/` 에 둔다 — 한 기능을 붙이는 사람은 대개 둘 다 읽는다.

기능 디렉토리를 새로 만들었다면 [`features/README.md`](features/README.md) 색인과
[`overview/features.md`](overview/features.md) 카탈로그 양쪽에 반영한다.

### `design/` 과 `plan/` 을 가르는 기준

같은 작업에 대해 두 문서가 생길 수 있다. 나누는 기준은 **무엇이 문서를 갱신시키는가**다.

| | `design/` | `plan/` |
|---|---|---|
| 담는 것 | 왜 이렇게 하기로 했는가 — 분류·구조·결정의 근거 | 어디까지 했고 다음에 무엇을 하는가 |
| 갱신 시점 | 결정이 바뀔 때만 | 작업이 진척될 때마다 |
| 수명 | 영구 (구현 여부는 첫머리 `Status` 한 줄이 말한다) | 작업 종료 시 **삭제** |

체크박스·"미착수/완료" 같은 상태 표기는 `plan/` 에만 둔다. 설계 문서가 진행률을 들고 있으면 근거를
읽으러 온 사람이 낡은 상태 표를 먼저 만나게 된다. 반대로 계획 문서는 근거를 복사하지 말고 설계 문서를
링크한다.

예외는 [`backlog/`](backlog/) 하나다. 작업이 **끝난 뒤** 남은 항목은 갱신시킬 계획 문서가 이미
없으므로 거기 모으고, 대신 담는 상태를 **열림/닫힘 하나로** 제한한다 — 진행률이 들어가는 순간
지워지지 않는 `plan/` 이 된다.

### 링크 규칙

- 문서 간 링크는 **상대 경로**로 쓴다.
- 문서를 옮길 때는 그 문서를 가리키는 모든 링크를 함께 고친다. 상대 경로는 문자열 치환으로 고칠 수
  없다 — **옛 디렉토리 기준으로 해석 → 새 위치로 매핑 → 새 디렉토리 기준으로 다시 상대화**해야 한다.
- Java Javadoc이나 `CLAUDE.md` 에서 문서를 가리킬 때는 리포지토리 루트 기준 경로
  (`docs/features/tool/tool-development-guide.md`)를 쓴다.
- 링크는 자동으로 검사된다 — `python3 scripts/check-doc-links.py` 가 경로와 `#앵커`를 둘 다 본다.
  CI 의 `docs-links` 잡이 같은 것을 돌린다.

### 번역 규칙

**정본은 한국어다.** 영어 문서는 번역이며, 접미사로 구분한다 — 정본 파일은 한 칸도 움직이지 않는다.

```
docs/features/tool/tool-development-guide.md      ← 한국어 정본 (경로 불변)
docs/features/tool/tool-development-guide.en.md   ← 영어 번역
```

이 접미사가 사이트의 URL 을 정한다.

| URL | 내용 |
|-----|------|
| `/` | 한국어 — 무접미사 파일 |
| `/en/` | 영어 — `.en.md` 파일 |

**번역이 없는 문서는 `/en/` 에서 404 가 아니라 한국어 정본이 그대로 나온다.** 그래서 번역을 한
디렉토리씩 진행해도 사이트는 늘 온전하다 — 한 번에 다 번역해야 할 이유가 없다는 뜻이다.

루트가 한국어인 것은 취향이 아니라 접미사 방식의 귀결이다. 무접미사 파일은 기본 로케일의 것이고
기본 로케일은 언제나 루트에 빌드되므로, 영어를 루트로 두려면 정본 전체에 `.ko` 를 붙여야 한다.
근거는 [`plan/open-source-readiness.md`](plan/open-source-readiness.md) §0.2 에 있다.

#### 무엇을 번역하는가 — 디렉토리로 정한다

문서마다 판단하지 않는다. **번역 대상은 디렉토리로 못박혀 있고, 이 표가 그 경계다.**

| 디렉토리 | 상태 | 이유 |
|---------|------|------|
| `docs/README.md` | **대상** | 사이트 진입점 |
| `overview/` | **대상** | 이 프로젝트가 무엇인지 묻는 사람이 처음 여는 곳. 용어·수명이 다른 번역의 기준이 된다 |
| `getting-started/` | **대상** | 붙여 보려는 사람의 경로 |
| `features/` | **대상** | 기능을 쓰려는 사람의 경로 |
| `project/` | 아직 아님 | 대상으로 **승격 가능**. 지금 미룬 것은 우선순위이지 성격이 아니다 |
| `references/` | 아직 아님 | 같음. 외부 명세와의 대조표라 수요가 생기면 앞당긴다 |
| `migration/` | 아직 아님 | 같음. 다만 새 마이그레이션 문서가 나오는 시점이 자연스러운 승격 시점이다 |
| `design/` | **대상 아님** | 설계 **근거**의 기록. 기여자의 진입 경로가 아니고, 가장 자주 바뀐다 |
| `backlog/` | **대상 아님** | 같은 이유. 밖에서 오는 문은 GitHub Issues 다 |
| `plan/` | **대상 아님** | 끝나면 지워지는 문서다 |

가운데 세 줄과 아래 세 줄은 다른 말을 한다. **"아직 아님" 은 미룬 것**이고 승격에 새 근거가 필요
없다 — 이 표의 상태만 바꾸면 된다. **"대상 아님" 은 번역하지 않기로 결정한 것**이고, 뒤집으려면
그 결정을 먼저 뒤집어야 한다.

경계를 옮기고 싶으면 **이 표를 먼저 고친다.** 표에 없는 디렉토리를 번역하면 다음 사람이
"여기는 왜 번역이 있고 저기는 없나" 를 매번 다시 판단하게 된다.

#### 리포지토리 루트는 방향이 반대다

`docs/` 아래의 정본은 한국어지만, 루트의 `README.md` · `CONTRIBUTING.md` · `SECURITY.md` ·
`CODE_OF_CONDUCT.md` · `MAINTAINERS.md` 는 **영어가 정본**이다. GitHub 이 그 파일들을 먼저 보여
주고, 그것을 여는 사람이 아직 이 프로젝트의 언어를 모르기 때문이다.

따라서 그쪽의 번역 파일은 `.ko.md` 이고, `translated_from` 이 가리키는 방향도 반대다.
접미사 규약은 양쪽에서 같다 — **접미사가 붙은 쪽이 번역이다.**

#### 번역 파일에는 frontmatter 를 붙인다

**영어 파일에만** 붙인다. 정본은 건드리지 않는다 — 정본에 메타데이터를 붙이면 번역이 없는 문서에도
번역 관리 부담이 생긴다.

```yaml
---
translated_from: docs/features/tool/tool-development-guide.md
source_commit: 4d1779d3
---
```

| 필드 | 값 |
|------|-----|
| `translated_from` | 정본 파일의 **리포지토리 루트 기준** 경로 |
| `source_commit` | 번역이 따라간 정본의 마지막 커밋 SHA (짧은 형식) |

`source_commit` 이 있어야 **정본만 바뀌고 번역이 안 따라온 상태**를 사람이 아니라 도구가 판정할 수
있다. 판정은 간단하다 — 그 SHA 이후로 `translated_from` 경로에 커밋이 있으면 번역이 낡은 것이다.
번역을 갱신할 때는 본문과 `source_commit` 을 **같은 커밋에서** 함께 고친다. 따로 고치면 이 필드는
"마지막으로 누군가 신경 쓴 시점" 이라는 다른 뜻이 되어 버린다.

#### 번역본 안의 상대 링크는 **번역이 있는 것만** 접미사로 가리킨다

번역본이 `foo.md` 를 가리킬지 `foo.en.md` 를 가리킬지는 사이트에서 **차이가 없다** — i18n 플러그인이
둘을 같은 페이지로 해석하고, 번역이 없는 문서는 404 가 아니라 한국어 정본을 내보낸다. 실측으로 확인한
사실이다.

차이는 GitHub 에서 난다. GitHub 은 플러그인 없이 파일을 그대로 열기 때문에 `.en.md` 는 그 파일이
있을 때만 맞고, 없으면 404 다. 따라서 규칙은 하나로 정리된다.

| 대상의 번역 | 링크에 쓸 것 |
|------------|-------------|
| 있다 | `foo.en.md` — 양쪽 다 맞다 |
| 아직 없다 | `foo.md` — 사이트는 한국어로 대체하고, GitHub 은 열린다 |

배치가 하나 끝나면 앞 배치들의 링크 중 이 규칙에 새로 걸리는 것이 생긴다.
`python3 scripts/upgrade-translation-links.py` 가 그것만 골라 올린다 — 손으로 찾지 않는다.

#### 용어

한 단어를 여러 가지로 옮기는 것을 막는 표가 따로 있다 —
[`project/translation-glossary.md`](project/translation-glossary.md). 번역 전에 §1(번역하지 않는 것)과
§2(`turn`/`iteration`/`execution`)는 반드시 읽는다. 표에 없는 단어를 새로 정했다면 **같은 PR 에서**
그 표에 추가한다.
