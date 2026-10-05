# 마이그레이션 문서 (Migration)

버전을 올리다가 무언가 깨졌을 때 들어오는 곳이다. 업그레이드 **절차**와, 이름이 바뀐 것 · 일부러 바꾸지 않은
것의 **조회표**가 있다.

- 무엇이 바뀌었는지 버전별로 보려면 → [`../../CHANGELOG.md`](../../CHANGELOG.md)
- `0.x` 가 무엇을 약속하는지 → [`../project/api-stability.md`](../project/api-stability.md)

---

| 문서 | 내용 |
|------|------|
| [`custom-command-to-skill.md`](custom-command-to-skill.md) | `.aimon/commands/<name>.md` 를 `.aimon/skills/<name>/SKILL.md` 로 옮기는 절차 — `CustomCommand` 는 0.1.0 에서 제거되었다 |
| [`rename-maps.md`](rename-maps.md) | 옛 이름 → 새 이름 조회표. 더 이상 풀리지 않는 자바 심볼을 여기서 찾는다 |
| [`frozen-names.md`](frozen-names.md) | **일부러 개명하지 않은** 이름 — 와이어 키, 컬렉션 · 테이블 · 채널 이름. 변경 기록이 아니라 호환성 계약이다 |

`rename-maps.md` 와 `frozen-names.md` 는 같은 리팩토링을 반대편에서 적는다. 개명은 자바 심볼 경계에서 멈췄으므로,
영속된 이름이 자바 이름과 어긋나 보이는 것은 의도된 상태다.

이 디렉토리의 문서는 아직 번역 대상이 아니다([`../project/documentation-guide.md`](../project/documentation-guide.md) §5.1).
