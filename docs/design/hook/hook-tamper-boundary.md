# 정책 훅 변조의 경계 (Hook Tamper Boundary)

> **Status**: IMPLEMENTED — `aimon-core` 의 `at.aimon.core.shell` 과 `at.aimon.core.skill.hook.declarative`. 권한 경계는 구현되지 않았고 [EE-113](../../backlog/execution-environment-open-items.md)이 추적한다. 그 밖의 열린 항목은 등록부의 EE-114, EE-115, EE-116, EE-117, EE-118 이다.

에이전트가 실행 환경 안의 정책 훅을 얼마나 건드릴 수 있는가, 코어가 무엇을 막고 무엇을 막지 못하는가의 정본이다. 사본의 매 호출 검증은 [스테이징](../tool/execution-environment-staging.md), 셸 옵션과 로컬 훅 I/O 의 계약은 [셸 계약](../tool/shell-contract.md), `hooks.json` 배치는 [훅 환경](hook-environment.md), `failOpen` 판정은 [fail-closed](hook-fail-closed.md)가 소유한다. 사용자 경고는 [hook-config-guide](../../features/hook/hook-config-guide.md) 에 있다.

## 1. 목표 수준

**좁히기이고 권한 경계가 아니다.** 환경 안의 모든 명령은 훅을 포함해 에이전트의 셸과 같은 uid 로 돈다. 코어는 실행 전의 파일과 지속되는 창을 닫는다. 같은 uid 의 에이전트가 **실행 중인** 훅 프로세스에 끼어드는 것은 막지 않는다. 경계에 무엇이 필요한지는 [§10](#10-권한-경계-조건)에 적는다.

운영자가 쓴 것과 코어가 읽는 판정 사이에 에이전트가 쓸 수 있는 파일이 두 종류 있고, 창도 두 종류다.

| 파일 | 지속되는 창 | 다시 여는 창 |
|---|---|---|
| 스크립트 사본 (`.aimon-staged/`) | 한 번 확인한 사본을 계속 믿으면 `Bash` 로 고친 `guard.sh` 가 계속 쓰인다 | 호출마다 확인해도 인터프리터가 경로로 다시 연다 |
| 명령 · 입력 · 출력 파일 | 샌드박스 래퍼의 입출력 파일(`run-*`)로 명령, 훅의 JSON, 판정을 위조할 수 있다. 로컬은 이름으로 닿는 임시 파일이다 | 파일이 있는 동안 |

정책 훅은 스테이징 사본에서 돌지 않는다. 매 호출 검증만으로는 인터프리터가 경로로 다시 여는 사이의 창이 남고, 그 창을 닫으려면 확인을 실행 안으로 옮겨야 한다. 그래서 셸이 확인된 바이트를 exec 요청으로 받아 그 실행만의 디렉터리에 둔다. 코어가 "스크립트" 를 인터프리터에 직접 줄 수 없는 이유는 명령이 임의의 운영자 텍스트이기 때문이다(`bash "$AIMON_HOOK_DIR/guard.sh"`, `source "$AIMON_HOOK_DIR/lib.sh"; ...`).

## 2. 실행 디렉터리

실행 디렉터리(`RunDirectory`)는 코어가 확인한 바이트이고, `ShellFeature.RUN_DIRECTORY` 를 선언한 셸이 명령 하나를 위해 새 디렉터리에 놓는다. 불변 값이고 변수 이름, 상대 경로, 내용을 든다. `toString()` 은 내용을 말하지 않는다. `ExecutionOptions.getRunDirectory()` 로 실린다.

선언한 셸의 **계약 6조**다.

1. 명령 시작 전에, 없던 디렉터리를 만든다(있으면 실패). 환경이 허용하는 만큼 좁은 권한(디렉터리 0700, 파일 0600)을 쓰고, 그런 자리가 있으면 모델의 파일 도구가 받는 경로 밖에 둔다.
2. `getPaths()` 를 그 바이트로 쓴다. 빌더가 제어 문자와 파일·디렉터리 겹침이 없음을 보장하므로 셸은 그 둘을 검사하지 않아도 된다. 파일 시스템이 합치는 두 경로(대소문자, 유니코드 형식)는 셸의 오류이고 명령을 돌리지 않는다.
3. 바이트가 에이전트가 쓸 수 있는 자리를 지났으면(업로드), 그 길을 거치지 않은 다이제스트로 놓은 뒤 명령 전에 대조한다. 다르면 명령을 돌리지 않고 `RunDirectoryIntegrityException` 을 던진다. 업로드한 stdin 에도 같은 예외를 쓴다(`ExecutionOptions.getStdin()`).
4. 디렉터리의 절대 경로를 `getVariable()` 로 내보낸다. `getEnvironment()` 의 같은 이름보다 이긴다.
5. 명령이 끝나면 결과와 무관하게 지운다. 최선 노력이고 지우지 못한 것은 WARN 이지 명령의 실패가 아니다.
6. `isIsolated()` · `isHook()` 과 직교한다. 스킬 훅의 명령도 실을 수 있다.

선언하지 않은 셸은 옵션을 무시하므로 호출자가 먼저 `supports(RUN_DIRECTORY)` 를 본다. 던지는 `supports` 는 미선언으로 읽는다([셸 계약](../tool/shell-contract.md)의 `ShellFeature` 절).

실행 디렉터리는 훅이 돌아오면 사라진다. 백그라운드 작업은 훅이 끝난 뒤 훅의 파일을 읽을 수 없다. 훅 명령이 남긴 것의 계약은 [셸 계약](../tool/shell-contract.md)의 "훅 명령이 남긴 것" 절이고, 셸이 그것을 끝내는지 밝히는 일은 [EE-118](../../backlog/execution-environment-open-items.md)이 추적한다.

## 3. 빌더 거부

`RunDirectory.Builder.file` 은 어떤 셸도 그대로 놓을 수 없는 것을 그 호출에서 `IllegalArgumentException` 으로 거부한다. `build()` 까지 미루지 않는 이유는 충돌을 일으킨 호출을 탓할 수 있어야 하기 때문이다.

| 거부 | 범위 | 이유 |
|---|---|---|
| 제어 문자 | `Character.isISOControl` 전체. 코드 포인트 U+0000–U+001F, U+007F, U+0080–U+009F | `\n` · `\r` 은 `sha256sum -c` 가 나르지 못한다. 나머지는 WARN 로그, CLI 사용자가 읽는 거부 사유(ESC · C1 CSI 로 터미널 이스케이프 주입), tar 나 셸 스크립트 인라인을 깨뜨린다. 정당한 파일 이름에 없다. JDK 가 정의한 집합이라 셸이 목록을 베끼지 않고 이름으로 말할 수 있다 |
| 파일이자 디렉터리 | `a` 와 `a/b`, 추가 순서 무관 | 어떤 파일 시스템도 놓지 못한다 |
| 가두어지지 않은 경로, 중복 | 빈 세그먼트, `.`, `..`, 맨 앞 `/`, `\` | 디렉터리 밖에 쓰지 못하게 한다 |
| 합계 상한 | [§4](#4-공개-상한) | 계약으로 만든다 |

제어 문자 검사를 가장 먼저 하므로 다른 분기의 메시지가 제어 문자를 그대로 찍지 못한다. 메시지는 제어 문자를 `\uXXXX` 로 이스케이프하고 스킬이나 `hooks/` 안의 상대 경로만 말하므로 거부 사유가 되어도 된다.

```text
Not placeable in a run directory: 'scripts/a\u000ab.sh' contains a control character (U+000A)
Not placeable in a run directory: 'lib' is a file and also the parent directory of 'lib/x.py'
```

유니코드 서식 문자(bidi, ZWJ)와 U+2028 · U+2029 는 거부하지 않는다. `sha256sum -c` 도 바이트 단위 배치도 깨뜨리지 않으며, 거부는 놓을 수 있는가가 아니라 위장 이름에 대한 정책이다. [EE-117](../../backlog/execution-environment-open-items.md)이 추적한다.

**로컬 소스에서 빌더까지 오는 것.** `LocalFileSystem` 의 `PathValidator` 가 C0 제어 문자를 거부하므로, 로컬의 그런 이름은 스캔에서 먼저 실패한다. `hooks.json` 은 `HOOK_DIR_STAGING_FAILED` 이고 스킬은 적재되지 않는다. 로컬 소스가 빌더에 넘기는 것은 DEL 과 C1 이다. 비로컬 소스(S3 · GridFS 키, 인메모리 VFS, 손으로 조립한 자원)는 무엇이든 넘길 수 있다. 빌더는 셸과 비로컬 소스가 기대는 SPI 계약이다.

**호출자가 받는 결과.** 제품 코드의 호출자는 `HookRunFiles` 하나다. `hooks.json` 은 `HOOK_DIR_STAGING_FAILED`, 스킬은 `STAGING_FAILED` 이고 메시지가 고치는 법(이름 변경, `.stageignore`)을 말한다. 아무것도 캐시하지 않으므로 파일 이름을 고칠 때까지 발화마다 같게 거부한다. 셸이 `RUN_DIRECTORY` 를 선언하지 않으면 빌더에 닿지 않는다.

둘 수 없는 이름은 could-not-run 부류다. 후퇴하지 않는 이유는 크기 후퇴와 달리 그 스킬이 정당하지 않고 스테이징도 둘 수 없기 때문이며, 변조로 다루지 않는 이유는 워크스페이스 스킬에 파일 하나를 떨어뜨려 `failOpen` 훅을 전부 막는 지렛대가 되기 때문이다. 새 우회도 아니다. 훅 디렉터리에 파일을 만들 수 있는 쪽은 이미 훅 스크립트를 고칠 수 있다.

## 4. 공개 상한

`RunDirectory.MAX_TOTAL_BYTES` 는 4 MiB 다. 값의 성질이므로 값의 타입에 두고 빌더가 강제한다. 그래서 "더 큰 `RunDirectory` 는 없다" 가 계약이 되고 셸이 전송 크기를 그것에 맞출 수 있다. 발화마다 쓰고, 원격 셸에서는 올린다.

`HookRunFiles` 는 빌더 앞에서 먼저 잰다. 그래서 스킬은 스테이징으로 후퇴하고 `hooks/` 는 `.stageignore` 를 말하는 거부가 된다. 실제 크기와 스캔 값이 다르면 읽는 동안의 누계가 잡는다.

상한을 올리는 일은 SPI 변경이다. 상수는 컴파일 때 인라인되므로 상한을 확인하는 모든 셸을 다시 빌드해 배포한 뒤에만 올린다. 설정([EE-116](../../backlog/execution-environment-open-items.md))은 낮추는 쪽만 가능하다. 스킬 캐시 상한 `HookRunFiles.MAX_CACHED_BYTES`(32 MiB)는 SPI 가 아니라 패키지 전용이다.

## 5. 호출자별 경로

`stage()` 는 기존 사본을 호출마다 내용 키와 대조한다. 그래도 확인과 인터프리터가 경로로 다시 여는 사이의 창은 남으므로 정책 훅은 사본이 아니라 실행 디렉터리에서 돈다. 사본 쪽 규칙은 [스테이징](../tool/execution-environment-staging.md)이 소유한다.

| | `hooks.json` (`AIMON_HOOK_DIR`) | 스킬 훅 (`AIMON_SKILL_DIR`) |
|---|---|---|
| 실행기 | `IsolatedShellActionExecutor`, `ConfigHookDirectory` | `DefaultShellActionExecutor`, `SkillHookDirectory` |
| 언제 싣나 | 명령 텍스트가 변수를 언급할 때 | 스킬에 자원이 있으면 모든 명령. 스크립트가 다른 길로 환경에서 읽을 수 있어 텍스트 검사를 하지 않는다 |
| 읽는 법 | 발화마다 스캔하고 읽는다 | 소스를 읽어 내용 키와 대조하고 메모리에 캐시한다 |
| 셸이 미선언 | 돌리지 않는다(`RUN_DIRECTORY_UNSUPPORTED`) | [§6](#6-스킬-후퇴) |
| 상한 초과 | 거부(`HOOK_DIR_STAGING_FAILED`, 사유에 크기와 `.stageignore`) | [§6](#6-스킬-후퇴) |
| 둘 수 없는 이름 | `HOOK_DIR_STAGING_FAILED` | `STAGING_FAILED`. 후퇴하지 않는다 |
| 호스트 배치 | 변수는 호스트 경로 자체다 | 없다 |

**`hooks.json`.** 발화마다 층의 `hooks/` 를 스캔해 모든 파일을 읽고, 읽은 바이트로 내용 키를 다시 만든다. 스캔의 키와 다르면 읽는 중 바뀐 것이므로 한 번 다시 읽고 거부한다. `stage()` 를 부르지 않고 `.aimon-staged/hooks-*` 사본을 만들지 않는다. 운영자의 편집은 다음 발화에 반영된다. 비용은 변수를 쓰는 핸들러의 발화마다 파일을 읽고 해시하는 것이고 상한으로 묶인다. 변수를 쓰지 않는 핸들러는 치르지 않는다.

**스킬 훅.** 다음이면 변수가 없다(빈 값이 아니라 unset). `hooks.json` 훅, `StagedResource` 가 없는 손조립 스킬, 컨텍스트의 환경에서 돌리지 않는 실행기, 환경이 없는 컨텍스트(실행기가 어차피 거절한다)다. 훅이 자기 자원을 찾는 법은 [훅 환경](hook-environment.md)의 스킬 훅 범위 절이다. 경로는 자르지 않는다. 자르면 다른 경로가 된다.

스킬 소스의 키가 적재 때와 다르면 스캔한 자원은 다시 스캔해 현재 내용을 쓰고 WARN 을 한 번 남긴다. 손으로 조립한 자원은 거부한다(`STAGING_FAILED`). 다시 스캔하면 일부러 뺀 파일이 에이전트의 uid 가 읽을 수 있는 디렉터리로 복사되기 때문이다.

**스킬 캐시.** 발화마다 소스를 읽지 않도록 확인한 바이트를 메모리에 둔다(`HookRunFiles.SkillCache`, 32 MiB, LRU).

- 적재한 버전은 레지스트리가 리로드하거나 그 항목이 축출될 때까지 쓰인다. 축출된 뒤 소스가 편집되어 있으면 편집본이 돈다. 스테이징 사본이 있는 동안만 쓰이는 것과 같은 모양이고 R4 안에 있다.
- 스킬 캐시와 `LocalStaging` 은 답을 그 바이트가 해시되는 키로 기억한다. 소스가 적재한 버전으로 돌아오면 기억한 답을 버린다. 비용은 소스가 다른 동안 호출당 소스 읽기다.
- 실행기 하나가 캐시 하나를 소유한다. 실행기는 스킬 파서와 함께 만들어져 같이 살고, 캐시는 메모리만 쥐므로 닫을 것이 없다. 전역에 두면 어떤 런타임이나 리로드보다 오래 산다.

**값의 뜻.** 두 변수의 값은 환경 안에서 발화마다 새로 생기는 디렉터리다. 로컬에서는 `${java.io.tmpdir}/aimon-run-*` 이고 발화 사이에 남지 않으며 워크스페이스 아래가 아니다. 스크립트가 잃는 것은 옆에 쓴 파일, 훅 디렉터리에서 계산한 워크스페이스 상대 경로, 나열되지 않은 파일이다. 파일이 모두 0600 이므로 스크립트를 직접 실행하면 exit 126 이다(`bash "$AIMON_SKILL_DIR/x.sh"` 로 부른다).

stdin 문서는 실행기가 돌기 전에 훅이 렌더하고 값은 실행기가 정하므로 `skill_dir` 과 `hook_dir` 을 싣지 않는다. 스킬 **본문**의 `${AIMON_SKILL_DIR}` 는 이것과 다른 값이고 [스테이징](../tool/execution-environment-staging.md)이 소유한다.

**다른 쪽 한도.** 실행 디렉터리를 받는 스킬 훅에는 스테이징 상한(`maxStagedBytes`)이 적용되지 않고 `MAX_TOTAL_BYTES` 가 적용된다. 그것을 넘어 후퇴한 스킬에는 스테이징 상한이 다시 적용된다.

## 6. 스킬 후퇴

스킬 훅은 실행 디렉터리를 못 쓰면 매 호출 검증 스테이징으로 후퇴한다. 스킬 훅은 에이전트 셸 상태에서 시작하므로 정책급이 아니고, 닫으면 그런 셸의 스킬 훅이 전부 멈춘다. 서드파티 실행기의 기본 메서드도 같은 후퇴를 쓰며, 변수를 비운 채로는 돌리지 않는다.

| 항목 | 규칙 |
|---|---|
| 조건 | 셸이 `RUN_DIRECTORY` 를 선언하지 않았거나 자원이 `MAX_TOTAL_BYTES` 를 넘는다 |
| 값 | `AIMON_SKILL_DIR` 은 `stage()` 가 돌려준 경로다 |
| 남는 것 | 다시 여는 창(R4) |
| 로그 | 셸 클래스와 스킬 이름마다 WARN 한 번. 집합은 정적이고 상한이 있으며, 넘으면 DEBUG 다. WARN 과 DEBUG 만 가르므로 상태라 부를 것이 없다 |
| 실패 | 스테이징 실패는 `STAGING_FAILED`, 환경 사용 불가는 `ENVIRONMENT_UNAVAILABLE`. 명령은 돌지 않는다 |
| 공유 코드 | `SkillHookDirectory.stageInto` 를 `ShellActionExecutor` 의 기본 오버로드와 `DefaultShellActionExecutor` 의 후퇴가 함께 쓴다 |
| 호출 | 세 선언 훅은 `SkillHookDirectory.resourceFor` 가 자원을 찾을 때만 오버로드를 부른다. `hooks.json` 훅과 자원 없는 스킬, 오버로드를 모르는 실행기는 4인자 `run` 을 받는다 |

호스트 실행기, `NoOpShellActionExecutor`, 라우터는 기본을 쓰거나 고른 실행기로 넘긴다. 스킬 훅은 호스트 실행기를 쓰지 않는다.

## 7. 변조 표지

`RunDirectoryIntegrityException` 은 셸이 받은 바이트로 실행을 위해 놓은 것, 곧 실행 디렉터리의 파일이나 에이전트가 쓸 수 있는 길로 옮긴 stdin 이 그 바이트가 아니라는 뜻이다. 실행 디렉터리가 없는 명령에서도 날 수 있다(stdin 을 업로드한 훅). `ShellActionRunner` 가 `ShellExecutionException` 보다 먼저 잡아 `Unrun.HOOK_FILES_TAMPERED` 로 읽는다.

- `HOOK_FILES_TAMPERED` 는 `failOpen` 과 무관하게 가드를 막는다. 인프라 실패가 아니라 공격 증거이고, 열어 두면 파일을 건드리는 것으로 모든 `failOpen` 가드를 끌 수 있다. advisory 이벤트에서는 WARN 만 남는다. 규칙은 [fail-closed](hook-fail-closed.md)가 소유한다.
- 사유는 고정 문구("the hook's files or input changed before the run")다. 파일을 말하는 셸의 메시지는 WARN 에만 간다.
- 이름은 파일을 말하지만 뜻은 "파일 또는 입력" 이다. 주된 경우가 파일이고 공개 이름이다.

오래된 사본을 고치거나 실행 디렉터리를 만들지 못한 것은 변조가 아니다. 사본 불일치는 다시 복사하고 WARN 한다. 실행 디렉터리를 만들거나 쓰지 못한 경우(디스크 가득, `java.io.tmpdir` 없음, 대소문자로 합쳐진 이름)는 `EXECUTION_FAILED` 다. 로컬의 재읽기는 쓰기 자체 점검이지 변조 방어가 아니다. 바이트가 여행하지 않았고 같은 uid 의 작성자는 그 뒤에 바꿀 수 있다.

## 8. 로컬 훅 I/O 의 위협 관점

훅과 격리 명령의 I/O 를 이름으로 닿는 파일에서 빼는 계약은 [셸 계약](../tool/shell-contract.md)의 로컬 훅 I/O 절이다. 위협 쪽에서 볼 것은 두 가지다. 이름으로 닿는 파일을 없애는 것은 Unix 에서만 성립한다. 그리고 `createTempFile` 과 `start()` 사이의 창(R2)이 남는다.

원격 셸의 입력과 출력도 같은 목적이다. 샌드박스 래퍼의 입출력 파일(`run-*`)은 훅 판정이 위조되는 길이므로 훅과 격리 모드에서 쓰지 않아야 한다. 모델 자신의 명령이 자기 출력을 위조하는 것은 경계를 넘는 일이 아니다. 샌드박스의 지원 상태는 [EE-114](../../backlog/execution-environment-open-items.md)가 소유한다.

## 9. 남는 위험

- **R1. 실행 중인 훅 프로세스.** 같은 uid 이므로 에이전트는 다음을 할 수 있다. 어느 것도 훅보다 오래 살거나 훅보다 먼저 있는 파일이 필요 없다. 받아들인 잔여 위험이다.
  - 훅이 도는 동안 실행 디렉터리를 쓴다. 경로는 훅의 환경(`/proc/<pid>/environ`)에 보인다. 셸의 확인과 인터프리터가 파일을 여는 사이의 창, 그 뒤의 `source` 도 여기 든다. 창은 지속되지 않고 실행마다 있다.
  - `/proc/<pid>/fd/{0,1,2}` 를 열어 stdin · stdout · stderr 파이프나 출력 파일에 끼어든다.
  - `/proc/<pid>/cmdline` 의 트레일러 nonce 를 읽어 트레일러를 위조한다.
  - 훅을 `ptrace` 하거나 신호를 보낸다. `kill` 은 `TIMEOUT` 이나 `EXECUTION_FAILED` 가 되고 `failOpen` 가드는 통과한다.

  결과는 위조된 판정, `TIMEOUT`, `EXECUTION_FAILED` 다. [§10](#10-권한-경계-조건)만이 고친다.
- **R2. 로컬의 시작 전 창.** 0600 의 임의 이름 파일에 대해 `createTempFile` 과 `start()` 사이의 한 JVM 호출 안이다. 거기 쓴 것은 자식의 리다이렉트가 잘라 내지만 그 창에 연 fd 는 살아남는다.
- **R3. 로컬 제공자에는 경계가 전혀 없다.** 에이전트의 `Bash` 가 호스트에서 JVM 의 uid 로 돈다. `~/.aimon/hooks/guard.sh`, 핫리로드되는 `<projectRoot>/.aimon/hooks.json`, JVM 자체를 고칠 수 있다. `"shell": "host"` 는 에이전트의 셸이 다른 곳(샌드박스)에서 돌 때만 닿지 않는다. 로컬의 실행 디렉터리와 훅 I/O 는 위생이다. 이름 있는 창을 없애고 계약을 맞춘다.
- **R4. 스킬 훅.** 에이전트의 셸 상태에서 시작한다(의도한 비격리). 파일을 변조 탐지로 만들어도 정책급이 되지 않는다. 후퇴 경로에서는 다시 여는 창이 남는다.
- **R5. 훅이 들여다보는 것.** 워크스페이스는 본래 에이전트가 쓸 수 있다. 정책 훅은 판단에 워크스페이스 파일을 믿으면 안 된다.
- **R6.** `RUN_DIRECTORY` 를 선언하지 않은 셸에서 `AIMON_HOOK_DIR` 을 쓰는 `hooks.json` 핸들러는 돌지 않는다(`RUN_DIRECTORY_UNSUPPORTED`, 가드는 `failOpen` 이 아니면 막는다). 변수를 쓰지 않는 핸들러는 영향이 없고 스킬 훅은 매 호출 검증 스테이징으로 후퇴한다. `aimon-sandbox` 의 지원 상태는 백로그 [EE-114](../../backlog/execution-environment-open-items.md)가 소유한다.

## 10. 권한 경계 조건

에이전트(uid A)와 정책 훅(uid H) 사이의 경계는 **아래가 모두 성립할 때만** 선다. 진행 상황과 코어에 필요한 표면의 제안은 백로그 [EE-113](../../backlog/execution-environment-open-items.md)이 소유한다.

1. **A 가 root 가 아니고 H 나 root 가 될 수 없다.** `runAsNonRoot`, no-new-privileges, `CAP_SETUID` · `CAP_DAC_OVERRIDE` · `CAP_FOWNER` · `CAP_SYS_PTRACE` · `CAP_KILL` 없음, H 에 닿는 setuid 바이너리 없음.
2. **훅 exec 가 H ≠ A 로 돈다.** exec 데몬이 uid 를 바꿀 수 있어야 하고, 그 권한이 A 의 프로세스에서 닿지 않아야 한다.
3. **훅 파일, 실행 디렉터리, 샌드박스 래퍼의 입출력 파일(`run-*`), 그리고 그 부모 디렉터리가 H 소유이고 A 가 쓸 수 없다.** `rename` 은 부모의 쓰기 권한으로 된다. A 가 `/workspace` 를 가지므로 그 아래의 자리는 자기 모드와 무관하게 이름을 바꿔 치울 수 있다. H 의 자리는 A 가 쓸 수 있는 트리 밖(`/opt/aimon/hooks`, `/run/aimon`)이거나 매번 소유자와 모드를 확인해야 한다.
4. **파일 도구의 소유자가 맞아야 한다.** 파일 도구는 A 로, 실행 디렉터리 업로드는 H 로 써야 한다.
5. **H 의 프로세스가 A 에서 닿지 않는다.** uid 가 다르면 `ptrace`, 신호, `/proc/<pid>/fd`, `/proc/<pid>/environ` 이 막힌다(`ptrace_scope` ≥ 1 또는 다른 uid). 훅의 출력 파이프는 파일이 아니라 exec 데몬으로 간다.
6. **이미지가 준다.** 사용자 H, H 소유 디렉터리, `sha256sum` · `base64`. 시드가 H 와 그 디렉터리의 소유를 확인한다.
7. **로컬 제공자.** 에이전트의 `Bash` 가 JVM 과 다른 OS 사용자(또는 컨테이너)로 돌아야 한다. `LocalShell` 안에서는 할 수 없고 범위 밖이다. `"shell": "host"` 는 에이전트의 셸이 JVM 의 uid 가 아닐 때만 닿지 않는다.
8. **훅의 입력.** 훅이 들여다보는 워크스페이스는 어떤 uid 배치에서도 A 가 쓸 수 있다(R5).

## 11. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| 확인을 실행 안으로 옮긴다 | 옵션으로 바이트를 넘겨야 확인과 실행 사이에 지속되는 것이 없다 | 매 호출 검증만 하고 사본을 쓴다: 인터프리터가 경로로 다시 열어 고쳐 쓰는 루프가 이긴다. 훅 exec 의 첫 단계에서 `sha256sum -c` 로 확인한다: 같은 창이 짧아졌을 뿐이고 확인 대상이 에이전트가 아는 지속 경로다. `stageForRun(...)` 이 경로를 돌려준다: 호출 둘이 창을 다시 만든다. 스크립트를 인터프리터에 직접 준다: 임의의 명령에서 스크립트를 찾을 수 없고 stdin 은 이미 JSON 을 나르며 `source` 는 여전히 경로로 연다. 한 파일용 `"script"` 필드는 [EE-115](../../backlog/execution-environment-open-items.md)가 추적한다 |
| 내용으로 대조한다 | 메타데이터는 위조된다 | mtime · 크기로 확인한다: `touch -d` 로 위조된다 |
| 같은 uid 안에서 좁힌다 | 다른 uid 는 보류한다. 조건은 [§10](#10-권한-경계-조건)이다 | 에이전트가 쓸 수 없는 디렉터리에 스테이징한다: 같은 uid 에서는 그런 디렉터리가 없다. 지금 별도 uid 를 쓴다: 보류한다([EE-113](../../backlog/execution-environment-open-items.md)) |
| 로컬 셸은 훅의 프로세스 트리를 정상 종료에서 끝내지 않는다 | 훅이 할 수 있는 일이 바뀌는데 얻는 것이 없다. 도는 프로세스에는 어차피 닿는다(R1) | 끝내고 stdout 을 파이프로 쓴다 |
| 스킬 훅은 후퇴한다 | [§6](#6-스킬-후퇴) | 닫는다: 그런 셸의 스킬 훅이 전부 멈춘다. 기본 오버로드가 거부한다: 서드파티 실행기의 스킬 훅이 전부 멈추고 얻는 것이 없다 |
| 적용 시점에 오류를 내지 않는다 | 적용기는 실행 전에 훅을 만들므로 어느 셸을 만날지 모른다 | R6 을 적용 시점 ERROR 로 만든다. 발화마다의 WARN 을 쓴다 |
| 스킬 훅은 변수를 언급하지 않아도 싣는다 | 스크립트가 다른 길로 환경에서 읽을 수 있다. 비용은 캐시와 상한으로 묶인다 | 텍스트 검사로 좁힌다: 그런 훅의 동작이 바뀐다 |
| 빌더가 놓을 수 없는 것을 거부한다 | 셸마다 다르게 실패하는 것을 없앤다 | 각 셸에 맡긴다: 두 셸이 이미 다르게 답한다. `build()` 에서 확인한다: 어느 호출이 일으켰는지 잃는다. `\n` · `\r` 만 거부한다: 터미널 주입이 남고 다음 셸이 자기 문자를 찾는다. 대소문자 · 유니코드 충돌도 거부한다: 파일 시스템에 달려 있어 셸의 오류로 둔다. 전용 예외 타입: 얻는 것이 없다 |
| 상한을 값의 타입에 두고 강제한다 | [§4](#4-공개-상한) | 셸 쪽 힌트 메서드: 의존이 뒤집히고 모든 셸에 메서드가 는다. `ShellFeature` 의 상수: 값의 성질이다. 공개만 하고 강제하지 않는다: 셸이 여전히 확인해야 한다 |
| 변조 표지에 종류를 두지 않는다 | 파일이든 입력이든 코어의 판단과 대처가 같고, 셸이 값싸게 가를 수도 없다 | 종류 enum: 소비자 없이 모든 셸이 채운다. 예외를 개명한다: 공개 이름을 깨고 얻는 것은 표현뿐이다 |
| 셸이 훅의 남은 작업을 끝낼 수 있음을 계약으로 말하고 기능 비트로 두지 않는다 | 어느 쪽이든 조언이 같아 분기할 호출자가 없다 | 로컬 셸도 같게 끝낸다: 분리된 프로세스에 닿지 않고 동작하던 훅을 바꾼다. `ShellFeature` 로 선언한다: [EE-118](../../backlog/execution-environment-open-items.md) |

## 12. 하지 말 것

- **`hooks.json` 스크립트를 스테이징 사본에서 돌리지 말 것.** 변수 없이 돌리는 것은 더 나쁘다. 다른 명령이 된다.
- **정책 훅에 변수를 비운 채 명령을 돌리지 말 것.** 어느 경로에서도 돌리지 않는 것이 답이다.
- **둘 수 없는 이름을 변조나 후퇴로 다루지 말 것.** could-not-run 부류다.
- **`HOOK_FILES_TAMPERED` 를 `failOpen` 으로 풀지 말 것.**
- **`RunDirectory` 의 내용을 로그에 남기지 말 것.** 사유와 로그에는 상대 경로와 크기만 간다.
- **메타데이터로 바이트를 확인하지 말 것.**
- **실행 디렉터리를 "경계" 라고 부르지 말 것.** 좁히기다. `"shell": "host"` 도 로컬 제공자에서는 경계가 아니다.
- **에이전트가 닿을 수 있는 자리의 파일로 판정을 나르지 말 것.** 판정의 길은 exec 자신의 출력이어야 한다.

## 13. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 내용 | 타입 | 경로 |
|---|---|---|
| SPI | `RunDirectory`, `ExecutionOptions`, `ShellFeature`, `RunDirectoryIntegrityException` | `shell/`, `shell/exception/` |
| 로컬 구현 | `LocalShell` | `shell/impl/local/` |
| 읽기와 캐시 | `HookRunFiles`, `ConfigHookDirectory`, `SkillHookDirectory` | `skill/hook/declarative/` |
| 실행기 | `IsolatedShellActionExecutor`, `DefaultShellActionExecutor`, `ShellActionExecutor`, `ShellActionRunner`, `EnvironmentShells` | `skill/hook/declarative/` |
| 판정 | `ShellHookOutcome`, `ShellHookVerdicts` | `skill/hook/declarative/` |
| 스테이징 | `ExecutionEnvironment`, `LocalStaging` | `environment/`, `environment/impl/` |
| 변수 | `SkillHookEnv` | `skill/hook/declarative/` |
