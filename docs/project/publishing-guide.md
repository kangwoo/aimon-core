# Publishing Guide

AIMON 모듈을 Maven Central에 퍼블리싱하는 방법을 설명한다.

## 퍼블리싱 대상 모듈

퍼블리싱 대상은 **`aimon.publishable` 스크립트 플러그인을 적용한 모듈**이다. 목록을 문서에 박아 두면
모듈이 늘 때마다 조용히 낡으므로, 확인은 빌드에 묻는다:

```bash
grep -rl "aimon.publishable" modules/*/build.gradle.kts
```

여기에는 `aimon-bom`(java-platform)과 나머지 라이브러리 모듈들이 포함된다. 제외되는 것은 두 종류다:

- **`aimon-cli`** — 애플리케이션 모듈이다
- **`samples/` 아래 3개 모듈** — 예제이므로 `settings.gradle.kts` 에는 있지만 배포하지 않는다

`aimon-bom` 이 관리하는 좌표 집합이 실제 퍼블리싱 대상과 일치하는지는 `:aimon-bom:verifyBom` 이
검사하며, 이 태스크는 `checkAll` 에 포함되어 있다 — 즉 **모듈을 추가하고 BOM 에 넣는 것을 잊으면
빌드가 깨진다**.

## 사전 준비

### 1. Sonatype 계정 및 Namespace 등록

[central.sonatype.com](https://central.sonatype.com)에서 계정을 생성하고 namespace(`at.aimon.core`)를 등록해야 한다.

### 2. GPG 서명키 생성

Maven Central에 퍼블리싱하려면 GPG 서명이 필수이다.

```bash
# GPG 키 생성
gpg --full-generate-key

# 키 ID 확인
gpg --list-keys --keyid-format short

# 공개키를 키서버에 업로드
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

### 3. 인증 정보 설정

`~/.gradle/gradle.properties`에 다음 정보를 추가한다:

```properties
# Sonatype (Maven Central) 인증
mavenCentralUsername=<sonatype-username>
mavenCentralPassword=<sonatype-password>

# GPG 서명 (파일 기반)
signing.keyId=<GPG-key-ID-마지막-8자리>
signing.password=<GPG-key-passphrase>
signing.secretKeyRingFile=/path/to/.gnupg/secring.gpg
```

또는 in-memory key 방식을 사용할 수 있다:

```properties
# GPG 서명 (in-memory 방식)
signingInMemoryKeyId=<key-id>
signingInMemoryKey=<ASCII-armored-GPG-key>
signingInMemoryKeyPassword=<passphrase>
```

> **주의**: `~/.gradle/gradle.properties`는 버전 관리에 포함하지 않는다. 민감한 인증 정보가 포함되어 있으므로 로컬 환경에서만 관리한다.

## 퍼블리싱 절차

### 1. 로컬 테스트

Maven Central에 업로드하기 전에 로컬 Maven 저장소에 먼저 퍼블리싱하여 검증한다:

```bash
./gradlew publishToMavenLocal
```

퍼블리싱된 아티팩트는 `~/.m2/repository/at/aimon/core/` 에서 확인할 수 있다.

### 2. 버전 설정

루트 `gradle.properties`에서 버전을 릴리스 버전으로 변경한다:

```properties
# SNAPSHOT 제거
VERSION_NAME=0.0.1
```

### 3. Maven Central에 퍼블리싱

```bash
# 모든 대상 모듈을 퍼블리싱
./gradlew publishAllPublicationsToMavenCentralRepository

# 특정 모듈만 퍼블리싱
./gradlew :aimon-core:publishAllPublicationsToMavenCentralRepository
```

### 4. 릴리스 확인

[central.sonatype.com](https://central.sonatype.com)에 접속하여 업로드된 아티팩트의 상태를 확인하고 릴리스를 승인한다.

## 플러그인 구성

이 프로젝트는 [vanniktech/gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)을 사용한다.

주요 설정은 루트 `build.gradle.kts`에 정의되어 있다:

- **퍼블리싱 대상**: `SonatypeHost.CENTRAL_PORTAL` (Sonatype Central Portal)
- **서명**: 모든 퍼블리케이션에 GPG 서명 적용
- **아티팩트**: 소스 JAR + Javadoc JAR 포함

각 모듈의 POM 메타데이터는 해당 모듈의 `gradle.properties`에 정의한다:

```properties
POM_ARTIFACT_ID=aimon-core
POM_NAME=AIMON Core
POM_DESCRIPTION=Core framework for AIMON intelligent agent
```

공통 POM 메타데이터(라이선스, 개발자, SCM 등)는 루트 `gradle.properties`에 정의되어 있다.

## 정규 경로 — `scripts/release.sh`

위의 수동 절차는 **개별 태스크가 무엇을 하는지 알기 위한 설명**이다. 실제 릴리스는 손으로 하지 않고
[`scripts/release.sh`](../../scripts/release.sh) 를 쓴다.

```bash
scripts/release.sh --dry-run     # 게이트까지만 돌려 보고 멈춘다
scripts/release.sh patch         # 0.2.2 → 0.2.3
scripts/release.sh minor         # 0.2.2 → 0.3.0
```

스크립트가 강제하는 것들:

| 단계 | 하는 일 |
|------|---------|
| pre-flight | `main` 브랜치, 클린 워킹 트리, `origin/main` 과 동기화, 태그 미존재 확인 |
| 크리덴셜 | Sonatype·GPG 설정 **이름만** 확인 (값은 절대 출력하지 않는다) |
| 품질 게이트 | `checkAll` — CI 와 **같은** 태스크 |
| 확인 | 버전 문자열을 직접 타이핑해야 진행 (`--yes` 로 생략) |
| 발행 → 커밋 → 태그 → 푸시 | **이 순서** |

### 순서가 그 순서인 이유

발행은 되돌릴 수 없고 git 은 되돌릴 수 있다. 그래서 **발행이 성공한 뒤에야** 커밋·태그·푸시가
일어난다. 발행이 실패하면 남는 부작용은 커밋되지 않은 `gradle.properties` 한 줄뿐이고,
`git checkout -- gradle.properties` 로 지운다.

이 순서 때문에 **태그를 트리거로 삼아 발행하는 CI 워크플로는 만들지 않았다.** 그런 워크플로는
"태그 → 발행" 이므로 순서가 뒤집히고, 발행이 실패하면 Central 에 존재하지 않는 버전을 가리키는
태그가 남는다.

### 게이트가 CI 와 어긋나지 않는다는 보장

`ReleaseGateMatchesCiGateTest` (`aimon-core`) 가 `scripts/release.sh` 와
`.github/workflows/build.yml` 을 둘 다 읽어서, **릴리스 게이트가 CI 게이트보다 좁지 않은지** 검사한다.
CI 에 검증 스텝을 추가하고 스크립트를 안 고치면 빌드가 깨진다.

한 번 실제로 어긋난 적이 있어서 생긴 테스트다 — 릴리스는 `test spotlessCheck` 만 돌고 CI 는
`checkAll` 을 돌던 시절, checkstyle 과 `verifyBom` 이 발행을 한 번도 막지 못했다.

## GitHub Release — `.github/workflows/release.yml`

스크립트가 태그를 푸시하면 [`release.yml`](../../.github/workflows/release.yml) 이 발화해서
**GitHub Release 를 만든다**. 이 워크플로는 Maven Central 에 아무것도 올리지 않는다 — 그 일은 이미
스크립트가 끝냈다.

하는 일은 둘이다:

1. 태그와 그 태그가 가리키는 트리의 `VERSION_NAME` 이 일치하는지 대조 (손으로 만든 태그를 걸러낸다)
2. `CHANGELOG.md` 에서 `## [<version>]` 섹션을 잘라 릴리스 노트로 사용

`CHANGELOG.md` 에 해당 섹션이 없으면 릴리스 노트 대신 CHANGELOG 링크만 남는다. 그래서
`release.sh` 는 버전을 올리기 전에 섹션 유무를 미리 경고한다 — **릴리스 전에 `[Unreleased]` 를
`[X.Y.Z]` 로 확정하는 것이 절차의 일부**라는 뜻이다.

섹션이 **너무 길어도** 릴리스가 만들어지지 않는다. GitHub 은 릴리스 본문을 125,000자로 제한하고
초과분을 422 로 거부하는데, 그러면 `gh release create` 호출 전체가 실패해서 위의 포인터 대체본조차
남지 않는다 — 섹션이 없을 때보다 나쁜 결과다. `scripts/cap-release-notes.py` 가 줄 경계에서 자르고,
잘린 자리가 코드 펜스 안이면 펜스를 닫은 뒤, 전체 섹션을 가리키는 링크를 붙인다.
`absolutize-release-links.py` **뒤에** 돌린다 — 절대화는 글자 수를 늘리기만 하므로 먼저 자르면
다시 한도를 넘을 수 있다.

> 0.2.4 가 이 경우였다. `[0.1.11]` 이후를 한 섹션에 몰아넣어(0.2.0~0.2.3 은 자기 섹션 없이 나갔다)
> 156,902자가 되었고 한도를 32k 넘겼다.

> 이 워크플로가 생긴 이유: 저장소에 태그는 9개인데 GitHub Release 는 1개뿐이었다. v0.1.17 ~ v0.2.2 는
> Central 에는 올라갔지만 릴리스 페이지가 비어 있었고, "0.2.2 에서 뭐가 바뀌었나" 를 알려면 git log 를
> 읽어야 했다.
