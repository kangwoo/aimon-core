# 문서 사이트 — 두 언어를 한 트리에 두고 두 표면에서 읽히게 한다

> Status: **IMPLEMENTED** — `mkdocs.yml`, `.github/workflows/docs.yml`, `scripts/mkdocs_github_links.py`.
> 사이트는 <https://kangwoo.github.io/aimon-core/> 에 배포된다.
>
> 이 문서는 오픈소스 전환 계획(옛 `docs/plan/open-source-readiness.md`, 2026-10-05 에 작업이 끝나 삭제)에서
> **남길 가치가 있던 근거**만 옮긴 것이다. 번역을 어떻게 쓰고 고치는지의 규칙은 여기가 아니라
> [`../../project/documentation-guide.md`](../../project/documentation-guide.md) §5 에 있다 — 이 문서는 그 규칙이 서
> 있는 **사이트 구조**가 왜 이런 모양인지를 적는다.

---

## 1. 결정

| 항목 | 결정 | 근거 |
|------|------|------|
| 정본 언어 | **한국어** | 결정 시점에 문서 34,352줄이 이미 한국어였다. 정본을 뒤집으면 34k줄을 다시 쓴 뒤에야 첫 커밋이 나온다 |
| 번역 언어 | 영어 | |
| 번역 범위 | 디렉토리로 정한다 | 경계의 정본은 [`documentation-guide.md` §5.1](../../project/documentation-guide.md) 이다. 여기 다시 적지 않는다 |
| 배포 | **GitHub Pages + MkDocs Material** | 34k줄은 저장소 마크다운 탐색의 한계를 넘었다 |
| 레이아웃 | **접미사** (`*.en.md`), 폴더 분리 아님 | §2 |
| 사이트 루트(`/`) | **한국어** | §3 — 선호가 아니라 빌드 제약이다 |

---

## 2. 레이아웃: 폴더 분리가 아니라 접미사

`docs/ko/` + `docs/en/` 로 가르는 폴더 방식은 채택하지 않았다. 결정 시점에 저장소에 걸려 있던 것은 이랬다.

| 깨지는 것 | 개수 |
|-----------|------|
| 문서 간 상대 링크 | 946 |
| Java Javadoc 안의 `docs/...` 참조 | 105 |
| `CLAUDE.md` 의 `@docs/...` 참조 | 4 |

폴더 방식은 이 1,055개를 전부 고쳐야 하고, 상대 경로는 문자열 치환으로 고칠 수 없다. 접미사 방식은
**정본 파일이 한 칸도 움직이지 않는다.**

```
docs/features/tool/tool-development-guide.md       ← 한국어 정본 (경로 불변)
docs/features/tool/tool-development-guide.en.md    ← 영어 번역
```

`mkdocs-static-i18n` 이 이 규약을 기본 지원한다.

---

## 3. 사이트 루트의 기본 언어는 한국어

정본 언어와 사이트 기본 언어는 원래 별개 결정이고, GitHub 유입을 생각하면 루트가 영어인 쪽이 선호였다.
spike 로 확인해 보니 **그쪽은 빌드가 되지 않는다.**

`mkdocs-static-i18n` 의 접미사 모드에서 무접미사 파일은 **기본 로케일의 것**이고, 기본 로케일은 언제나
사이트 루트에 빌드된다. 즉 "무접미사 = 한국어" 와 "기본 로케일 = 영어" 는 동시에 성립할 수 없다.
`default: true` 를 `en` 에 주면 두 `index.md` 가 같은 자리를 다투고 빌드가 끊긴다.

```
Exception: Conflicting files for the default language 'en':
choose either 'index.md' or 'index.md' but not both
```

영어 루트를 살리려면 정본 전부에 `.ko` 접미사를 붙여야 하는데, 그것은 §2 가 접미사를 고른 이유(정본이
움직이지 않는다)를 스스로 무르는 일이다.

| URL | 내용 | 파일 |
|-----|------|------|
| `/` | 한국어 (정본) | 무접미사 |
| `/en/` | 영어 (번역) | `.en.md` |

번역이 없는 문서는 **404 가 아니라 `/en/` 아래에 한국어 정본이 그대로 서빙된다** — 별도 설정이 아니라
`fallback_to_default` 의 기본값이다. 번역이 일부만 있어도 사이트가 늘 온전하다는 뜻이다.

루트가 한국어인 대가(영어권 독자가 한 번 더 클릭한다)는 언어 스위처와 `README.md` 의 영어 링크로 갚는다.

---

## 4. 한국어 검색 — `separator` 는 실측으로 정했다

착수 전 가정("형태소 분석이 없으니 한국어 검색은 안 된다")은 절반만 맞았다.

1. Material 의 한국어 로케일은 lunr 파이프라인을 비워 둔다 — stemmer 도, stopword 필터도, **trimmer 도** 없다
2. Material 은 모든 질의어 뒤에 `*` 를 붙인다. 즉 **모든 검색은 접두 검색**이다
3. 한국어 조사는 접미사이므로 2 가 1 을 구제한다 — `세션` 이 `세션을`·`세션이`·`세션은` 을 모두 잡는다

문제는 다른 데 있었다. trimmer 가 없어 **구두점이 토큰에 붙어 있고**, 접두 검색은 단어가 토큰의 맨 앞에
있을 때만 잡는다. 기본 `separator` 로 잰 결과다.

| 질의 | 기본 `[\s\-]+` | 튜닝 후 |
|------|----------------|---------|
| `이터레이션` | **0** — 문서에 단독으로 없고 `Iteration(이터레이션)` 으로만 나온다 | 5 |
| `세션` | 12 | 17 |
| `수명` | 6 | 9 |
| `execution` | 3 | 6 |

괄호·쉼표·따옴표 같은 구두점을 구분자에 넣어 해소했다. **`.` 은 일부러 넣지 않는다** — 넣으면
`aimon.llm.provider` 와 `at.aimon.core.agent` 가 조각난다. 현재 정규식의 정본은 `mkdocs.yml` 의 `search`
플러그인 설정과 그 위의 주석이다.

> 순정 lunr 로 먼저 실험했을 때는 영어 Porter stemmer 가 질의어만 어간화해 `livesession` → `livesess` 로
> 깨지는 것으로 나왔다. **Material 에서는 일어나지 않는다** — Material 은 `pipeline` 에 없는 함수를 색인·검색
> 양쪽에서 제거한다. 순정 lunr 의 기본값을 Material 의 동작으로 옮겨 적으면 안 된다.

---

## 5. 내비게이션 — `nav:` 를 쓰지 않는다

`mkdocs.yml` 에 `nav:` 를 두면 `docs/README.md` 의 인덱스와 **같은 목록을 두 벌 유지**하게 된다. 새 문서를
추가한 사람이 한쪽만 고치면 사이트나 인덱스 한쪽에서 사라진다.

`nav:` 를 생략하면 MkDocs 가 디렉토리 트리에서 내비게이션을 만든다. 알파벳순이라 손으로 고른 순서보다
못하지만, **손으로 유지하는 목록이 `docs/README.md` 하나**로 남는다. `awesome-pages` 같은 플러그인은
`.pages` 파일로 순서를 되찾아 주지만 유지 대상이 하나 더 느는 일이라 택하지 않았다.

---

## 6. 앵커 — GitHub 과 사이트가 같은 슬러그를 만든다

`toc` 확장의 `slugify` 를 `pymdownx.slugs.slugify(case=lower)` 로 맞췄다. 그래서 GitHub 에서 생기는 앵커가
사이트에서도 글자 그대로 같고, GitHub 의 슬러그 규칙을 모델링한 `scripts/check-doc-links.py` 가 사이트의
앵커까지 함께 지킨다 — 게이트를 하나 더 만들지 않고 범위가 넓어졌다.

---

## 7. `docs/` 밖을 가리키는 링크

문서는 두 표면에서 읽힌다. GitHub 에서는 `../../../modules/.../ReadTool.java` 가 맞는 링크이고, 사이트에는
`modules/` 가 없으므로 죽은 링크다. 원본을 고치면 사이트를 살리는 대신 GitHub 을 깨뜨린다.

그래서 원본은 상대 경로 그대로 두고, `scripts/mkdocs_github_links.py` 훅이 **`docs_dir` 를 벗어나는 링크만**
렌더 시점에 GitHub URL 로 바꾼다. `docs/` 안에 머무는 링크는 건드리지 않으므로 MkDocs 의 링크 해석과
`.en.md` 매핑이 그대로 동작하고, 덕분에 CI 가 `mkdocs build --strict` 를 쓸 수 있다. 플러그인 없이 MkDocs
내장 `hooks:` 로 처리했다.

> 훅을 처음 쓸 때 인라인 코드를 보호하려고 줄을 코드 스팬 기준으로 잘라 붙였더니,
> ``[`ReadTool`](...)`` 처럼 **링크 텍스트가 백틱인 링크가 반토막**나서 199개 중 54개만 바뀌었다. 자르지 않고
> **위치로 건너뛰도록** 고쳤다.

---

## 8. 범위에서 뺀 것

- **`CHANGELOG.md` 번역** — 릴리스 노트는 영어 한 벌이면 충분하고, 이미 영어다
- **`design/`·`backlog/` 번역** — 결정의 정본은 [`documentation-guide.md` §5.1](../../project/documentation-guide.md)
  이다. 결정 시점의 이유는 "설계 근거는 내부 독자용이고, 외부 기여자가 여기까지 오면 이슈로 물을 수 있다" 였다
