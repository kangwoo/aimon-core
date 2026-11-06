# 프로그래매틱 도구 호출 — 셸 엔진 (`aimon-sandbox`)

> **Status**: PROPOSED — `aimon-core` (`at.aimon.core.tools.code.shell`, `at.aimon.core.environment`, `at.aimon.core.shell`),
> 외부 저장소 `aimon-sandbox`. 공개 API 호환을 깨는 항목이 있다(§3).

## 1. 목적과 범위

[프로그래매틱 도구 호출](programmatic-tool-calling.md)(이하 PTC 본 문서)의 두 엔진 가운데 **셸 엔진**을 정한다 — 에이전트의 실행 환경이
샌드박스일 때, 모델이 쓴 Python 코드를 그 샌드박스 안에서 돌리고 코드의 도구 호출을 호스트로 되돌리는 방식이다. 모델이 보는 표면,
호출 가능 집합, 코드 경로 규칙, 엔진 SPI, 관찰, 한도는 PTC 본 문서가 정한다. 이 문서는 그 위에서 셸 엔진에만 해당하는 것을 정한다.

| 주제 | 절 |
|---|---|
| 설계 결정 | §2 |
| 호환을 깨는 변경 | §3 |
| 실행 환경 위의 엔진, 가용성 | §4 |
| 배치와 실행, 런타임 | §5, §6 |
| 프로토콜 | §7 |
| 정리, 지연, 이미지 계약, 실행 상한 | §8 – §11 |
| 같은 uid 의 한계와 도구 범위 | §12 |
| `aimon-sandbox` 에 필요한 것 | §13 |
| 테스트, 조각 사이의 의존, 열린 질문 | §14 – §16 |

용어:

| 용어 | 뜻 |
|---|---|
| OpenSandbox / execd | `aimon-sandbox` 의 기본 제공자가 쓰는 업스트림 샌드박스 런타임과, 그 안에서 명령을 실행하는 데몬 |
| PTC 런타임 | 셸 엔진이 샌드박스 안에서 띄우는 Python 프로세스. 모델 코드를 감싸 실행하고 도구 호출을 호스트로 보낸다 |
| 공존 프로세스 | 같은 샌드박스 안에서 PTC 런타임과 같은 uid 로 도는 다른 프로세스 (모델의 백그라운드 `Bash`, 고아 프로세스) |
| 전용 uid 모드 | PTC 런타임을 공존 프로세스와 다른 uid 로 띄우는 배치. 업스트림 블로커로 지금은 없다(§12) |
| 데이터 도달 범위 (`dataReach`) | 도구가 닿는 데이터·네트워크의 범위 — `ENVIRONMENT` / `HOST` / `EXTERNAL` (PTC 본 문서 §7.1) |

---

## 2. 설계 결정

| # | 쟁점 | 결정 | 이유 | 기각한 대안 |
|---|---|---|---|---|
| 1 | 어디서 도는가 | `SANDBOXED` 환경에서만. `ExecutionEnvironment` 가 주는 셸·런타임 파일 시스템·서술자만 쓰고 특정 제공자를 모른다 | 로컬 호스트 프로세스의 셸 엔진은 여러 세션이 같은 OS uid 로 도는 서버에서 세션 간 confused deputy 가 된다(PTC 본 문서 §15). 제공자를 모르면 `aimon-sandbox` 를 위한 코드가 코어에 생기지 않는다 | 로컬 셸 엔진: 미룬다 |
| 2 | 요청 통로 | 실행의 stdout 은 **공개 통로**로 본다. execd 는 그것을 샌드박스 `/tmp` 의 스풀 파일로 받는다. 모든 줄에 하나의 단조 증가 seq 와 HMAC 을 붙인다. 모델 코드와 자식의 stdout·stderr 는 런타임 전용 캡처 파일로 돌린다 | 스풀 파일은 같은 uid 프로세스가 읽고 덧붙일 수 있고 실행 뒤에도 남는다. MAC 과 연속 seq 가 덧붙인 줄을 거절하고 탐지한다 | 비공개 fd + 첫 줄 키 교환: execd 스풀 때문에 비공개가 아니다. MAC 없는 요청: `O_APPEND` 한 줄로 호스트 도구 호출이 주입된다 |
| 3 | 응답 통로 | 환경의 **런타임 파일 시스템**(`RuntimeFileSystem`, 경로 규칙 밖)에 응답 파일을 `writeNew` 로 쓴다. 응답마다 HMAC. 대상이 이미 있으면 프로토콜 위반 | `aimon-sandbox` 에는 도는 프로세스에 쓸 stdin 도, 샌드박스→호스트 연결도 없다. `writeNew` 는 OpenSandbox 제공자에서 link(2) 로 원자적이다. 무결성은 HMAC 이 지킨다 | `RUN_DIRECTORY` 에 응답: 시작 전에만 바이트를 놓는 계약이다. 임시 이름 + `move(overwrite=false)`: execd 의 move 는 check-then-act 라 원자적이지 않다 |
| 4 | 런타임과 키의 배치 | 런타임, 모델 코드, 키, 응답 디렉터리 경로를 `RunDirectory` 로 넘긴다. 키는 **비밀 파일** 표지를 달아 셸이 명령 인자에 인라인하지 않고 업로드하게 한다. 런타임은 키와 경로 파일을 읽자마자 지운다 | 다이제스트 검증, 모델 파일 도구 밖, 실행 뒤 삭제를 셸이 보장한다(EE-109). 그런데 `aimon-sandbox` 는 합계 16 KiB 이하의 실행 파일을 래퍼 스크립트에 base64 로 인라인하고, 그 스크립트가 execd 의 `bash -c` 인자다 — 인라인된 키는 실행 내내 `/proc/<pid>/cmdline` 에 남고, 그것은 dumpable 과 무관하게 hidepid 가 없으면 다른 uid 도 읽는다 | 스테이징 영역: 셸이 쓸 수 있는 곳이라 경계가 아니다. 명령행 인자나 표지 없는 `RunDirectory`: 실행 내내 `ps` 에 보인다 |
| 5 | 코드에 여는 도구의 범위 | 애플리케이션 설정 `shell-engine-tool-scope` (`environment` 기본 / `host` / `unrestricted`)가 **데이터 도달 범위**로 상한을 정한다. 에이전트는 좁히기만 할 수 있다 | 전용 uid 모드가 없는 동안 공존 프로세스는 스크립트가 도는 동안 호출 가능 집합 안의 도구를 대신 부르고 그 결과를 읽는다(§12). `READ_ONLY`·닫힌 세계는 부작용과 상대 도메인의 축이지 기밀성의 축이 아니다 — 호스트 메모리·지식·사내 MCP 같은 닫힌 읽기 도구도 샌드박스가 원래 닿지 못하는 데이터를 들인다 | `READ_ONLY ∧ ¬openWorld`: 호스트 데이터 유출을 묶지 못한다. 앱 전체 단일 값: 신뢰 입력 에이전트 하나를 위해 넓히면 모든 에이전트가 넓어진다 |
| 6 | 같은 uid 문제 | **한계로 두고 진행한다.** 전용 uid 모드는 업스트림 블로커(EE-113 과 같음)가 풀린 뒤에 둔다. 그동안 위험의 범위는 결정 5 가 묶는다 | 지금 배치에서 모델 명령과 execd 는 같은 uid 라 샌드박스 안 악성 프로세스는 이미 샌드박스 전체에 닿는다. PTC 가 더하는 것은 그 위에서 호스트 도구에 닿는 경로이고, 그것은 범위로 묶을 수 있다. 기본 범위 `environment` 에서 코드에 열리는 내장 도구는 `Read`·`Grep` 정도라 Python 이 직접 할 수 있는 일과 겹친다 — 셸 엔진의 실질적 가치는 `host` 범위와 egress 기본 거부를 함께 둔 배치(메트릭·로그 MCP 팬아웃)에서 생긴다 | 업스트림 해결까지 셸 엔진 보류: 샌드박스에서 PTC 를 쓸 길이 GraalJS 뿐인데, 그것은 호스트 JVM 의 메모리 고갈을 막지 못해 다중 사용자 서버에서 더 나쁜 실패다 |
| 7 | 위반의 처리 | 즉시 실행을 끝내고 `PROTOCOL_VIOLATION`(`notRetryable`), ERROR 로그, span 이벤트, 그 환경 객체에서 셸 엔진 불가 | 이 실패는 버그가 아니라 공존 프로세스가 PTC 를 겨냥했다는 거의 유일한 신호다. 재시도는 공격자에게 기회를 한 번 더 준다 | 일반 `SCRIPT_ERROR`: 관측되지 않고 모델이 다시 시도한다 |

---

## 3. 호환을 깨는 변경과 추가

| 변경 | 전 | 후 |
|---|---|---|
| 셸 스트리밍 계약 | `VirtualShell` javadoc: 포그라운드 스트리밍은 거절될 수 있으니 background 로 | "포그라운드 = `!background && !hook && !isolated`". `isolated` 스트리밍을 계약으로 올린다(`aimon-sandbox` 의 `SandboxShell` 은 이미 이렇게 동작한다). 스트리밍 제공자는 줄을 물질화하기 **전에** 상한을 건다. [셸 계약](shell-contract.md) 도 고친다 |
| `RunDirectory` 비밀 파일 | 모든 실행 파일이 같다. 작으면 셸이 인라인할 수 있다 | 파일 단위 `secret` 표지. 표지된 파일은 명령 인자에 인라인하지 않고 업로드하며, 업로드 원본은 배치 직후 지운다. [셸 계약](shell-contract.md) 의 `RUN_DIRECTORY` 조항에 올린다 |
| `EnvironmentDescriptor` | 작업 디렉터리·플랫폼·OS 버전·셸 이름·노트 | `isolation()` (`HOST` / `SANDBOXED`)과 제공자가 선언한 런타임 능력(`python3`). 둘 다 프로필에서 정해지는 정적 값이다 |
| `ExecutionEnvironment` SPI (`aimon-sandbox` 포함) | 경로 규칙을 지나는 `fileSystem()` 만 | `Optional<RuntimeFileSystem> runtimeFileSystem()` — 프레임워크 전용, 경로 규칙 밖, 모델 도구에는 주지 않는다. `RuntimeFileSystem` 은 `at.aimon.core.environment` 의 새 인터페이스다: `writeNew(path, bytes)` (원자적, 대상이 있으면 실패), `read(path, maxBytes)` (상한, 링크를 따라가지 않음), `delete(path)`, `createDirectories(path)`. 코어 `VirtualFileSystem` 에는 쓰기 모드가 없어 이 연산을 표현할 수 없다. 로컬 구현(`O_EXCL` 생성)은 계약 스위트가 쓴다 |

---

## 4. 실행 환경 위의 엔진

| 쓰는 것 | 용도 |
|---|---|
| `shell().execute(command, options, ShellLineListener)` | 실행, 요청·결과 수신, `RunDirectory` 로 런타임·코드·키 배치 |
| `runtimeFileSystem()` | 응답 파일 쓰기 (`writeNew`) |
| `descriptor()` | `isolation()`, 선언된 런타임 능력 |

가용성은 두 단계다. 제안 시점에는 셸 명령이나 파일 쓰기를 하지 않는다 — 샌드박스는 첫 셸·파일 호출에서 프로비저닝되므로, 제안
시점에 탐침하면 모델이 `ExecuteCode` 를 쓰지 않는 턴에도 샌드박스가 뜬다.

| 단계 | 조건 |
|---|---|
| 정적 (제안 시점) | `isolation()=SANDBOXED`. 셸이 `STREAMING` · `RUN_DIRECTORY` · `ISOLATED_EXECUTION` 을 선언. 환경이 `runtimeFileSystem()` 을 준다. 서술자가 `python3` 능력을 선언. **`Bash` 를 제약 없이 쓸 수 있다** — 이번 제안 문맥에서 `Bash` 가 제안 자격을 가지고(레지스트리에 있고 허용 목록·실링을 통과), 허용 목록의 `Bash` 항목에 인자 패턴이 없고, `Bash` 에 매치되는 PermissionRequest·PreTool 훅이 없다. 빈 허용 목록("무제한")만으로는 통과하지 않는다 |
| 탐침 (환경 객체당 첫 `ExecuteCode`) | `python3` 3.8 이상. 런타임 FS 로 쓴 프로브 파일을 셸에서 `test -f` 로 볼 수 있다 |

`Bash` 조건이 있는 이유: 셸 엔진의 코드는 도구를 거치지 않고 작업 영역을 쓰고 지우고 프로세스를 띄운다 — `Bash` 와 같은 능력이다.
`Bash` 가 없거나 패턴·훅으로 좁혀진 에이전트에 패턴·훅 없는 임의 셸을 주면 안 된다. 셸 엔진 `ExecuteCode` 의 등급과 승인 키도 그래서
`Bash` 와 같다(PTC 본 문서 결정 10, 11).

탐침 결과는 엔진이 환경 객체 정체성에 붙여 둔다(약한 참조). 탐침에 실패하거나 실행 중 응답 파일 가시성·`python3` 부재가 드러나면 그
호출을 오류로 돌려주고 그 환경 객체에서 셸 엔진을 불가로 표시한다. 다른 언어로 내려가지 않는다.

---

## 5. 배치와 실행

| 무엇 | 어디 |
|---|---|
| `aimon_ptc.py` (런타임), `main.py` (모델 코드를 감싼 진입점), `key` (K, 32바이트), `reply_dir` (응답 디렉터리 경로) | `RunDirectory` — 다이제스트 검증, 모델 파일 도구 밖, 실행 뒤 삭제. 합계 상한(4 MiB)이 곧 코드 크기 상한. `key` 는 **비밀 파일**로 표지한다. 런타임은 `key` 와 `reply_dir` 을 읽자마자 지운다 |
| 응답 `r/<seq>` | 런타임 FS 의 `.aimon-ptc/<128비트 무작위>/` |
| 모델 코드의 stdout·stderr 캡처 | 런타임이 `mkdtemp` 로 만든 전용 임시 디렉터리 (작업 영역 밖). 호스트는 읽지 않는다 — 결과는 요청 통로로 온다 |

실행 명령은 `python3 -I -u "$AIMON_RUN_DIR/aimon_ptc.py"` 이고, 옵션은 `isolated(true)`, `runDirectory(...)`, 타임아웃(스크립트 벽시계
한도)이다. `background`·`hook` 은 쓰지 않는다 — `background` 는 "호출보다 오래 사는 명령" 이라는 다른 뜻이다. 셸의 `cd`/`export` 상태를
읽지도 남기지도 않는다.

`.aimon-ptc/` 는 경로 규칙에서 모델의 **파일 도구**에게 `DENY` 다. 모델의 `Bash` 는 경로 규칙 밖이라 막지 못한다 — 그래서 무결성은
경로를 숨기는 데가 아니라 HMAC 에 기댄다. 디렉터리 권한 비트는 같은 uid 에게 효과가 없으므로 근거로 쓰지 않는다. 겨냥하지 않은
프로세스에 대한 방어는 무작위 이름뿐이다.

---

## 6. 런타임

표면은 Anthropic PTC 와 같다. 도구는 `async` 함수이고 인자는 dict 하나, 반환은 문자열이다. 최상위 `await` 를 허용하며, 런타임이
`asyncio.run` 으로 감싼다. **stdout 이 결과다.** 모델 코드와 그 자식 프로세스가 stdout 에 쓴 것이 관찰이 되고, stderr 는 로그로 간다.

시작 단계:

1. `os.setpgid(0, 0)` — 자기 프로세스 그룹을 만든다. 출력 상한 등으로 실행을 끝낼 때 그 그룹만 끝내서, 셸 래퍼가 살아 결과를 마무리한다.
2. `prctl(PR_SET_DUMPABLE, 0)` (Linux, 실패해도 계속) — 같은 uid 의 비특권 프로세스가 `/proc/<pid>/fd`·`environ`·`mem` 을 열지 못하게
   한다. 경계가 아니라 비용 상승이다(§12).
3. `key`·`reply_dir` 을 읽고 지운다.
4. 원래 stdout(실행의 stdout = 통로)을 런타임 전용 fd 로 옮기고(CLOEXEC), fd 1·2 를 캡처 파일로 돌린다.
5. 캡처 파일 크기를 100 ms 마다 보는 감시 스레드를 띄운다(§11).

---

## 7. 프로토콜

**원칙: 런타임과 모델 코드는 같은 프로세스다.** 모델 코드는 통로 fd 에 무엇이든 쓸 수 있고, 공존 프로세스는 스풀 파일에 덧붙일 수
있다. 그래서 아래의 모든 줄 규칙은 **호스트가 다시 강제한다.** 런타임 쪽 규칙은 정상 경로를 맞추기 위한 것일 뿐이다.

**통로는 공개다.** execd 는 명령의 stdout 을 파이프가 아니라 샌드박스 안의 스풀 파일로 받아 100 ms 마다 읽어 보낸다(`aimon-sandbox`
저장소의 `spike/opensandbox/reports/A-source.md` Q5). 위치와 수명은 execd 버전마다 다르다.

| execd | 스풀 위치 | 실행 뒤 |
|---|---|---|
| `release-1.1.0` 이상 | `$TMPDIR/opensandbox-execd/` (0700 디렉터리) | execd 가 스스로 지운다 |
| `docker/execd/v1.1.0` | `$TMPDIR` 바로 아래 | 남는다 — 제공자가 지운다(§13 S1) |

어느 쪽이든 0700 디렉터리의 소유자는 execd 의 uid 이고 그것이 모델 명령의 uid 와 같으므로, **실행 중에는** 같은 uid 프로세스가 그 파일을
읽고 덧붙일 수 있다. 그래서 통로에는 비밀을 싣지 않고, 모든 줄을 인증한다.

```
 python runtime (sandbox)                              host (JVM)
 ─────────────────────────────────────────────        ──────────────────────────────────────
 K, reply_dir <- RunDirectory (then deleted)            K generated here, sent as a secret file
 await Grep({...})
 line = "CALL 1 {json}"
 write(ch, line + " " + hex(HMAC(K, line)) + "\n") ──▶  verify MAC, require seq == last + 1
                                                         enqueue (listener never blocks)
 poll reply_dir/r/1 (5ms x2 .. 100ms,                     bridge.callAsync -> gates -> result
                     until remaining deadline)           file = hex(HMAC(K, "R" 1 0x00 payload)) "\n" payload
                                         ◀─ runtimeFS ─ writeNew r/1  (exists -> violation)
 open O_NOFOLLOW, regular file only, size-capped read
 verify HMAC on raw bytes; parse; delete
 return text / raise ToolError
 ...
 "OUT 7 <base64>"  + mac  x n            ─ stream ─▶  verify, append (decoded bytes capped)
 "ERR 9 <base64>"  + mac                 ─ stream ─▶  stderr tail for logs
 "DONE 10" + mac; exit                   ─ stream ─▶  result complete; bridge -> CLOSED
```

| 규칙 | 내용 |
|---|---|
| 줄 형식 | `<TYPE> <seq> <payload> <hex(HMAC-SHA256(K, "<TYPE> <seq> <payload>"))>`. TYPE 은 `CALL` · `OUT` · `ERR` · `DONE`. 모든 줄이 **하나의** seq 를 공유하며 1 부터 1 씩 증가한다 |
| 검증 | 호스트는 MAC 이 맞고 `seq == last + 1` 인 줄만 받는다. MAC 불일치, 중복·역행·건너뛴 seq, 모르는 TYPE 은 **프로토콜 위반**이다. 런타임이 seq 를 락 아래에서 단조 증가시키며 한 줄씩 쓰므로, 이 규칙에 어긋나는 줄은 두 번째 작성자가 있다는 증거다 |
| 줄 길이 | 접두어와 MAC 을 포함한 **UTF-8 바이트**로 64 KiB 미만. 셸의 줄 전달(`StreamLines`)이 64 KiB 에서 줄을 자르므로, 그보다 긴 줄은 호스트에 두 조각으로 도착하고 어느 조각도 MAC 검증을 통과하지 못해 위반이 된다. 제공자는 256 KiB 를 넘는 한 줄에서 명령을 끝낸다(§13 S2). `CALL` 페이로드는 `json.dumps(ensure_ascii=True)`, `OUT`·`ERR` 는 base64 라 모든 페이로드가 개행 없는 ASCII 다(execd 는 `\n` 과 `\r` 을 모두 줄바꿈으로 다룬다). 인자가 이 크기를 넘으면 런타임이 "argument too large; write it to a file and pass the path" 로 오류를 낸다 |
| 응답 파일 | `hex(HMAC-SHA256(K, "R" ‖ seq ‖ 0x00 ‖ payload))` + `\n` + payload 원본 바이트. payload 는 `{"text":..., "is_error":..., "retryable":...}`. 런타임은 원본 바이트로 검증한 **뒤에** 파싱한다. 크기 상한 1 MiB (넘으면 잘라서 표시) |
| 응답 쓰기 | `RuntimeFileSystem.writeNew(path, bytes)` 한 번. OpenSandbox 제공자에서 이것은 임시 이름 업로드 + `ln`(`CREATE_NEW`)으로 원자적이다. 대상이 이미 있으면 누군가 선점한 것이므로 프로토콜 위반이다 |
| 응답 읽기 | 런타임은 `O_NOFOLLOW` 로 열고 regular file 인지, 크기가 상한 이하인지 확인한 뒤 읽는다 |
| 결과 | 런타임이 끝에 stdout 캡처를 `OUT` 줄들로, stderr 캡처의 꼬리(4,000자 분량)를 `ERR` 줄로 보내고 `DONE` 을 보낸다. 출력 상한은 **디코딩 후** 바이트로 센다 |
| 리스너 | 셸 스레드(샌드박스의 SSE 스레드) 위에서 돈다. 파싱·검증과 큐 삽입만 하고 블록하지 않는다. 큐는 상한이 있다. 위반은 리스너가 던지는 방식이 아니라 브리지 상태를 바꾸는 방식으로 처리한다 — `StreamLines` 는 리스너 예외를 삼키고 줄을 버리기 때문이다 |
| 응답 쓰기 실패 | 재시도한 뒤 실패하면 실행을 끝낸다 |
| 종료 | `DONE` 없이 0 이 아닌 종료면 `SCRIPT_ERROR` 와 받은 `ERR` 꼬리 |
| 위반 처리 | 결정 7. 위반 전에 이미 디스패치된 호출은 기록에 "unattributed" 로 표시한다 |

---

## 8. 정리

실행이 끝나면(성공·실패·취소 모두) 브리지를 `CLOSED` 로 둔 **뒤에** 엔진이 `.aimon-ptc/<run>/` 을 지운다. `CLOSED` 뒤에 도착한 결과는
응답 파일로 쓰지 않는다(PTC 본 문서 §13.2). 이 디렉터리를 치우는 기존 스윕은 없으므로, 엔진이 환경 객체당 첫 실행 때 `.aimon-ptc/`
아래에서 벽시계 상한의 두 배보다 오래된 항목을 지운다. `setsid` 로 빠져나간 자손은 셸이 끝내지 못한다(best-effort).

---

## 9. 지연 시간

호출 하나의 비용:
- 요청 줄 전달: execd 가 스풀을 100 ms 주기로 읽으므로 평균 약 50 ms, 최대 약 100 ms
- 응답 쓰기: 업로드 한 번 + `ln` 을 위한 `/command` 한 번 (HTTP 두 번과 프로세스 하나)
- 런타임의 폴링

병렬 호출은 이 비용을 나눠 갖지만, **순차 호출 20개는 수 초**가 된다. 순차 패턴에서는 PTC 가 LLM 왕복보다 크게 싸지 않을 수 있어,
설명 지침이 병렬을 권하고 PTC 본 문서 §18 에서 잰다.

---

## 10. 이미지 계약

`aimon-sandbox` 의 기본 이미지 계약에는 Python 이 없다(필수: `bash`, coreutils, `flock`, `git`, `rg`). `python3` (3.8 이상)를
**선택 항목**으로 추가하고, 프로필이 그것을 선언하면 서술자의 런타임 능력에 실린다. 선언하지 않은 샌드박스에서 `auto` 는
`ExecuteCode` 를 내지 않고, 그 이유를 운영자에게 알린다.

---

## 11. 실행 상한

런타임의 감시 스레드가 stdout·stderr 캡처 파일의 합계를 100 ms 마다 보고, 상한(4 MiB)을 넘으면 자기 프로세스 그룹을 끝낸다. 모델
코드는 fd 1·2 에 직접 쓸 수 있어 쓰기 래퍼로는 막을 수 없기 때문이다. 호스트는 받은 `OUT` 의 디코딩 바이트로 백스톱을 건다.

---

## 12. 같은 uid 의 한계와 도구 범위

### 12.1 무엇이 남는가

지금 배치에서는 모델의 명령과 execd 가 같은 uid 로 돈다(Docker 기본은 root, 하드닝한 Kubernetes 템플릿은 1000). 그래서 같은 샌드박스의
악성 프로세스는 PTC 와 무관하게 이미 샌드박스 안의 모든 것에 닿는다. **같은 uid 경계의 부재는 PTC 가 만든 것이 아니다. PTC 가 더하는
것은 그 부재 위에서 호스트 도구에 닿는 경로다.**

능동적으로 PTC 를 겨냥하는 공존 프로세스는 키가 업로드되어 런타임이 읽고 지우기까지의 창(실행 시작 직후의 짧은 구간)에 키를 읽거나,
런타임 파일을 검증과 실행 사이에 바꾸거나, dumpable 이 꺼지기 전의 짧은 창에서 `/proc` 로 닿을 수 있다. HMAC·연속 seq·
`PR_SET_DUMPABLE`·비밀 파일 표지·무작위 이름은 **비용을 올리되 막지 못한다.**

| # | 잔여 위험 | 무게 |
|---|---|---|
| R1 | 호스트 도구 대신 부르기 — 동결된 집합 안의 도구를 임의 인자로. 관문과 코드 경로 규칙은 그대로 지난다 | **실질적인 권한 확대.** PTC 가 없으면 샌드박스 프로세스는 호스트 도구에 닿지 못한다. 도구 범위가 묶는 대상 |
| R2 | 호스트 도구 결과 읽기 — 응답 파일로 샌드박스에 들어온 결과 | 허용된 egress 로 내보낼 수 있다. 도구 범위가 결과의 출처를 묶는다 |
| R3 | 가짜 결과 넣기 — 코드에 위조된 도구 결과 | 작업 파일을 조작하는 것과 같은 무게다. 모델은 이미 작업 파일을 믿지 않는다 |
| R4 | 수동 노출 — execd 스풀의 요청 인자와 스크립트 출력. 겨냥할 필요가 없다 | 실행 중에는 남는다. 실행 뒤에는 execd `release-1.1.0` 이상이 스스로 지우고, `v1.1.0` 은 제공자가 지운다(§13 S1) |
| R5 | 런타임 바꿔치기 — 검증 뒤 교체로 런타임 전체(키 포함)를 쥔다 | R1–R4 를 모두 낳는다. `RunDirectory` 자체가 "좁히기이지 권한 경계가 아니다"(`SandboxShell` 의 문서) |
| R6 | 감사 오귀속·상한 소모 — 탐지 전에 디스패치된 주입 호출이 모델의 `ExecuteCode` 호출로 기록되고 상한을 먹는다 | 연속 seq 위반으로 첫 주입에서 실행이 끝나고 "unattributed" 로 표시된다. MAC 을 맞춘 주입은 탐지되지 않는다 |
| R7 | 실행 방해 — 응답 선점, 통로 오염으로 실행을 끝내게 만들기 | DoS. 탐지되어 `PROTOCOL_VIOLATION` 으로 보인다 |

### 12.2 도구 범위 — `shell-engine-tool-scope`

셸 엔진이 전용 uid 모드 없이 돌 때, 코드에 여는 도구를 **데이터 도달 범위**로 묶는다. PTC 본 문서 §7.3 의 호출 가능 집합 계산의 마지막
단계다.

| 값 | 호출 가능 집합에 남는 도구 | R1·R2 |
|---|---|---|
| `environment` (기본) | `dataReach = ENVIRONMENT` 만 | 공존 프로세스가 대신 부를 수 있는 것이 그 프로세스가 원래 닿던 것뿐이다. **샌드박스 프로세스가 이미 닿던 것 이상은 새지 않는다** |
| `host` | `ENVIRONMENT` + `HOST` | 호스트 메모리·지식·사내 시스템의 데이터가 샌드박스로 들어온다. 이를 막는 것은 샌드박스의 egress 정책뿐이다. 이 값을 쓰면 egress 기본 거부를 함께 둔다 |
| `unrestricted` | `programmatic-tools` 그대로 | 외부 요청 도구까지 대신 불린다. 시작 시 경고하고, 실행마다 `ExecuteCode` span 에 `ptc.tool_scope=unrestricted` 를 남긴다 |

- **애플리케이션 값이 상한이고 에이전트는 좁히기만 한다.** 넓히는 값은 무시하고 경고한다.
- **적용 시점은 제안 시점이다.** 범위 밖 도구는 그 실행의 호출 가능 집합에서 빠지고, 에이전트·환경마다 한 번 경고한다.
- 운영 에이전트의 핵심 용도인 메트릭·로그 MCP 팬아웃은 `HOST` 도구이므로 `host` 가 필요하다. 그것은 운영자가 egress 정책과 함께 내리는
  판단이다.

### 12.3 해제 조건 — 전용 uid 모드

업스트림이 풀리면 이 한계를 경계로 바꾼다. 다음이 모두 성립해야 하고, 하나라도 빠지면 보장이 없다. 서술자가 이 모드를 선언하는 것은
제공자가 이 조건을 보장한다는 뜻이고, 그때 `shell-engine-tool-scope` 는 적용되지 않는다.

| 조건 | 이유 |
|---|---|
| ① 모델의 명령(`Bash` 와 그 자손)은 **비특권 작업 uid A** 로 돈다. root 가 아니고, uid B 로 전환할 setuid·sudo·capability 경로가 없으며, execd 의 API 에 닿지 못한다 | 전용 uid 모드에는 uid 를 바꿀 수 있는 root execd 가 필요한데, 지금의 OpenSandbox 에서는 그 API 가 에이전트 uid 에서 닿아 uid 0 명령까지 돌린다(`aimon-sandbox` 의 [`workspace-sandbox-hook-identity.md`](https://github.com/kangwoo/aimon-sandbox/blob/main/docs/design/workspace-sandbox-hook-identity.md) §5 Q1). 이것이 업스트림 블로커다 |
| ② PTC 런타임은 execd 가 **처음부터 uid B 로** 띄운다 | 런타임이 root 로 시작해 내려가려면 런타임을 띄우는 셸이 root 여야 하고, 그러면 같은 셸로 도는 모델 명령도 root 다 |
| ③ 응답 디렉터리는 uid B 소유 0700. 호스트는 files API 의 uid 경로(`/v1/filesystem/{uid}/{gid}/...`, execd `release-1.1.1` 이상)로 B 로서 쓴다 | A 는 디렉터리에 닿지 못하고, B 는 호스트가 쓴 파일을 읽고 지울 수 있어야 한다 |
| ④ `RunDirectory` 는 인라인 없이 B 로 업로드되어 B 소유이고, 비밀은 어떤 프로세스의 argv 로도 가지 않는다 | R5 와 키 노출을 막는다(훅 신원 설계의 "run-directory uploads as H" 와 같다). argv 는 다른 uid 도 `/proc` 로 읽는다 |
| ⑤ execd 의 출력 스풀은 A 가 읽거나 쓸 수 없다 (B 소유 0600 이거나 uid 별 `TMPDIR`) | R4 와 통로 주입을 막는다 |

그때 깨질 API: 셸 SPI 에 명령별 실행 주체(`ExecutionOptions.runAs(WORKLOAD | CODE_RUNTIME)`), 제공자 `ExecSpec` 의 uid, `aimon-sandbox`
의 "`/command` 에 uid 를 넘기지 않는다" 결정 변경. 훅 신원(EE-113, core#287)과 메커니즘이 같으므로 셸 SPI 의 실행 주체는 "훅 전용" 이
아니라 일반 실행 주체로 함께 설계한다.

---

## 13. `aimon-sandbox` 에 필요한 것

| # | 무엇 | 코어 SPI 에 기대는가 |
|---|---|---|
| S1 | isolated·hook·스트리밍 실행이 끝나면 `init` 이벤트의 command id 로 execd 스풀을 지운다 — 두 배치(`opensandbox-execd/<id>.*`, `$TMPDIR/<id>.*`) 모두. execd `release-1.1.0` 이상은 스스로 지우므로 실제로 바뀌는 것은 `v1.1.0` 이다. `$TMPDIR` 은 execd 프로세스의 값이라 files API 로는 알 수 없고 `/tmp` 로 가정한다 | 아니다 |
| S2 | SSE 리더가 이벤트 한 줄을 물질화하기 **전에** 상한(256 KiB)을 걸고, 넘으면 명령을 끝내고 두 스트림을 잘림으로 표시한다. "제공자는 명령을 끝내지 않는다" 는 `aimon-sandbox` 의 기존 결정의 예외다 | 아니다 (계약은 §3 의 셸 스트리밍 변경으로 코어에 올라간다) |
| S8 | 명령을 끝낸 원인(줄 상한 초과 등)을 결과에 싣는다. 지금은 셸에 "wrapper failed (exit 137)" 만 보여, 셸 엔진이 이것을 `PROTOCOL_VIOLATION` 으로 구분할 수 없다 | 기댄다 (`ShellCommandResult` 에 원인 필드) |
| S9 | 경로 규칙에 `.aimon-ptc/` 를 모델의 파일 도구에게 `DENY` 로 둔다(§5) | 아니다 |
| S3 | 이미지 계약에 `python3` (3.8 이상)를 선택 항목으로 추가하고, 프로필 선언과 seed 확인을 둔다 | 서술자 능력으로 싣는 부분은 기댄다 |
| S4 | `RunDirectory` 비밀 파일 표지를 지킨다 — 인라인하지 않고 업로드한다. 업로드한 blob 은 래퍼가 복사 직후 이미 지우므로, 남은 일은 인라인하지 않는 것이다 | 기댄다 (`RunDirectory` 는 코어 타입) |
| S5 | `RuntimeFileSystem` 구현 — `writeNew` 는 기존 `SandboxFiles.write(CREATE_NEW)`(임시 업로드 + `ln`)를 그대로 쓴다. execd 의 download 는 링크를 따라가므로 `read` 의 "링크를 따라가지 않음" 은 별도로 지킨다. 호스트가 런타임 FS 에서 읽는 것은 탐침 파일뿐이다 | 기댄다 |
| S6 | 서술자의 `isolation()=SANDBOXED` 와 런타임 능력 | 기댄다 |
| S7 | `isolated` 스트리밍이 포그라운드로 취급되지 않음을 계약 테스트로 고정한다 (지금 동작) | 아니다 |

---

## 14. 테스트

| 층 | 무엇 |
|---|---|
| 엔진 계약 스위트 | PTC 본 문서 §17 의 `aimon-code-engine-testkit`. `aimon-sandbox` 의 testkit (`LocalProcessSandboxProvider`)과 docker 계층에서 돌린다. `LocalShell` 위에서도 돌린다(테스트 경로) |
| 프로토콜 공격 | 64 KiB 를 넘어 두 조각으로 도착한 줄(위반), 256 KiB 를 넘는 줄로 끝난 실행이 `PROTOCOL_VIOLATION` 으로 분류됨(S8), 모델 코드가 통로 줄 모양을 `print` (무시), 모델 코드가 통로 fd 에 MAC 없는 줄을 씀(위반), 스풀 파일에 줄 덧붙이기(위반), 중복·역행·건너뛴 seq(위반), 응답 선점(`writeNew` 실패 → 위반), 응답 바꿔치기와 심볼릭 링크(HMAC·`O_NOFOLLOW` 가 거절), 개행·`\r`·비 UTF-8 바이트를 담은 출력(결과가 바이트 그대로), 64 KiB 경계의 다바이트 문자, 거대한 한 줄(SSE 리더가 끊음), 스크립트 종료 뒤 끝난 중첩 호출의 결과가 샌드박스에 남지 않음, 실행 중 어떤 `/proc/*/cmdline` 에도 K 가 없음, 위반 시 span 이벤트·ERROR 로그·엔진 불가 표시, 실행 뒤 execd 스풀이 지워짐, 출력 폭주 뒤에도 셸 래퍼가 살아 결과를 마무리함 |
| 가용성 | 제안만 하고 쓰지 않는 턴에서 샌드박스가 프로비저닝되지 않음. 다음 에이전트에 셸 엔진 `ExecuteCode` 가 제안되지 않음: 허용 목록이 비었고 `Bash` 가 등록되지 않음, `Bash(git *)` 처럼 인자 패턴으로 좁힘, `Bash` 에 PreTool·PermissionRequest 훅이 걸림. 원격 VFS 를 빌린 환경(탐침이 불가를 답함) |
| 도구 범위 | `environment` 에서 `HOST`·`EXTERNAL` 도구가 빠짐, `host` 에서 `EXTERNAL` 이 빠짐, 에이전트 좁히기가 적용되고 넓히기는 무시·경고됨 |
| 전용 uid 모드 (해제 뒤) | 모델의 `Bash` 가 요청 통로·스풀·`RunDirectory`·응답 디렉터리·execd API 에 닿지 못하고 PTC uid 로 전환할 경로가 없음, 범위가 적용되지 않음 |

---

## 15. 조각 사이의 의존

PTC 본 문서 §19 의 조각 C(GraalJS 엔진까지) 위에 얹힌다.

| 조각 | 무엇 | 기대는 것 |
|---|---|---|
| D1 | 코어의 `RuntimeFileSystem` SPI 와 로컬 구현, `RunDirectory` 비밀 파일 표지, 서술자의 `isolation`·런타임 능력, 셸 스트리밍 계약, 셸 엔진, `shell-engine-tool-scope` | PTC 본 문서의 조각 C. `LocalShell` 위의 계약 스위트로 검증된다 |
| D2 | §13 의 S3–S6, S8 | 배포된 D1 (`aimon-sandbox` 는 배포된 코어에 의존한다) |
| — | §13 의 S1·S2·S7·S9 | 없음. 지금 할 수 있고, EE-113 에도 쓸모가 있다 |
| E | 명령별 실행 주체와 전용 uid 모드 | D2, **업스트림 블로커 해소**, EE-113 과 같은 메커니즘 |

D2 가 들어가면 샌드박스 에이전트의 `auto` 설정에서 `ExecuteCode` 가 나타난다. 이 변화와 셸 엔진의 등급(`Bash` 와 같음)은 그 릴리스의
변경 노트에 적는다.

---

## 16. 열린 질문

1. **stdin 통로.** 응답 파일·폴링을 없애려면 OpenSandbox execd 가 실행 중 stdin 을 받아야 한다. 우리 API 호환을 깨도 풀리지 않는
   업스트림 제약이다.
2. **업로드된 비밀의 창.** 비밀 파일이 업로드되어 런타임이 지우기까지의 창을 더 줄일 수 있는가 — 예를 들어 파일 대신 execd 가 실행
   중 stdin 을 받게 되면 키를 그쪽으로 옮긴다(1 과 같은 블로커).
