# 프로젝트 문서 (Project)

AIMON 을 **쓰는 법**이 아니라 이 프로젝트가 **어떻게 운영되는가**를 담는다 — 무엇을 약속하는지, 다음에 무엇이
오는지, 코드와 문서를 어떤 규칙으로 쓰는지, 어떻게 릴리스하는지.

- 기여 절차 전체는 → [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md)
- 버전을 올리다 깨진 것은 → [`../migration/README.md`](../migration/README.md)

---

## 약속과 방향

| 문서 | 내용 |
|------|------|
| [`api-stability.md`](api-stability.md) | `0.x` 가 무엇을 약속하고 무엇을 약속하지 않는지 — Maven Central 모듈을 의존성으로 쓰는 사람이 대상이다 |
| [`roadmap.md`](roadmap.md) | 지금 무엇을 하고 있고 다음에 무엇이 오는지. 날짜가 아니라 착수 순서를 적는다 |

## 코드와 문서를 쓰는 규칙

| 문서 | 내용 |
|------|------|
| [`solid-principles.md`](solid-principles.md) | 이 프로젝트가 따르는 객체지향 설계 원칙 다섯 |
| [`documentation-guide.md`](documentation-guide.md) | 문서를 새로 쓰거나 · 옮기거나 · 번역할 때의 규칙 — 어디에 둘까, 링크, 번역 frontmatter |
| [`translation-glossary.md`](translation-glossary.md) | 번역 용어표 — 번역하지 않는 것, 수명 · 스코프 용어, 반복 용어의 표기 |

## 릴리스와 품질

| 문서 | 내용 |
|------|------|
| [`publishing-guide.md`](publishing-guide.md) | Maven Central 퍼블리싱 — 대상 모듈, 사전 준비, `scripts/release.sh`, GitHub Release |
| [`aimon-core-coverage-priority.md`](aimon-core-coverage-priority.md) | `aimon-core` 테스트 커버리지 수치와, 어디부터 메울지의 우선순위 |

이 디렉토리의 문서는 아직 번역 대상이 아니다([`documentation-guide.md`](documentation-guide.md) §5.1).
