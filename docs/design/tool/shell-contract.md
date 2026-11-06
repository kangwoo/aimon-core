# 셸 계약

> **Status**: IMPLEMENTED. 적용 대상은 `aimon-core` (`at.aimon.core.shell`, `at.aimon.core.shell.impl.local`, `at.aimon.core.tools.bash`) 다. 로컬 프로세스 트리 종료의 남는 틈은 열려 있다(§5.3).

## 1. 목적과 범위

`VirtualShell` 은 명령을 돌리는 SPI 다. 로컬 프로세스, 원격 샌드박스, 그것을 감싼 래퍼가 모두 이 계약을 구현한다. 이 문서는 구현과 호출자가 함께 지키는 계약과, 그 위에서 `Bash` 가 모델에게 내는 결과를 정한다.

| 주제 | 정본 |
|---|---|
| 백그라운드 작업의 수명, 소유, `KillShell`, 상한 | [백그라운드 명령](background-commands.md) |
| 실행 디렉터리 계약, 변조 위협, 남는 위험 | [변조 경계](../hook/hook-tamper-boundary.md) |
| 훅이 어느 셸에서 도는가 | [훅 환경](../hook/hook-environment.md) |
| 줄 단위 출력의 소비자, 도구의 셸 사용 규칙 | [Monitor](monitor.md) |
| 셸을 어느 환경에서 얻는가, 셸 없는 실행의 오류 | [실행 환경](execution-environment.md) |

## 2. SPI

```java
public interface VirtualShell extends AutoCloseable {
    ShellCommandResult execute(ShellCommand command) throws ShellExecutionException;
    ShellCommandResult execute(ShellCommand command, ExecutionOptions options) throws ShellExecutionException;
    default ShellCommandResult execute(ShellCommand command, ExecutionOptions options, ShellLineListener listener)
            throws ShellExecutionException;   // 기본은 UnsupportedOperationException. §7
    String getWorkingDirectory();
    boolean supports(ShellFeature feature);
    @Override void close();
}
```

- 구현은 thread-safe 다.
- 명령의 비정상 종료는 예외가 아니라 `ShellCommandResult` 의 값이다(§8.3). 예외는 명령을 돌리지 못했거나 끝까지 지켜보지 못한 경우다.

| 예외 | 뜻 |
|---|---|
| `ShellExecutionException` | 명령을 띄우지 못했거나 기다리다 인터럽트되었다 |
| `ShellTimeoutException` | timeout 에 걸렸다. 그때까지의 출력을 싣는다 |
| `ShellCancelledException` | 취소 신호로 멈췄다. 그때까지의 출력을 싣는다 |
| `RunDirectoryIntegrityException` | 실행 디렉터리 또는 stdin 이 준 바이트와 다르다 |

## 3. 옵션과 기능

### 3.1 `ExecutionOptions`

불변 값이고 빌더로 만든다.

| 옵션 | 기본값 | 뜻 |
|---|---|---|
| `timeout` | 없음 | 셸이 강제한다. 0 이하는 무기한 대기로 읽는다 |
| `environment` | 빈 맵 | 명령의 환경 변수 |
| `workingDirectory` | 셸의 기본값 | 작업 디렉터리 |
| `charset` | UTF-8 | 입출력 인코딩 |
| `redirectErrorStream` | false | stderr 를 stdout 에 합친다 |
| `unixShell` | 셸의 기본값 | `bash` 등 |
| `maxCaptureBytes` | 구현 기본값 | 스트림마다 결과로 읽는 바이트 상한 |
| `stdin` | 없음 | 있으면 정확히 이 텍스트 뒤에 EOF. 없으면 구현 기본값이고, `stdin` 을 끝까지 읽는 명령이 어느 쪽에서든 끝나야 한다 |
| `background` | false | §3.3 |
| `hook` | false | §3.3 |
| `isolated` | false | §3.3 |
| `cancellation` | `ShellCancellation.none()` | §4 |
| `runDirectory` | 없음 | 명령 하나만을 위해 셸이 두는 파일. [변조 경계](../hook/hook-tamper-boundary.md) 의 `RunDirectory` SPI |

### 3.2 `ShellFeature`

호출자는 필요한 기능을 `supports` 로 묻는다. 선언하지 않은 셸은 해당 옵션을 무시하거나, 스트리밍처럼 거절한다.

| 기능 | 선언의 뜻 | 선언하지 않은 셸 | `LocalShell` |
|---|---|---|---|
| `INTERACTIVE` | 대화형 터미널(PTY) | — | 아니오 |
| `PIPE` | `a \| b` | — | 예 |
| `REDIRECTION` | `>`, `<`, `2>` 등 | — | 예 |
| `CANCELLATION` | 취소 신호를 지킨다(§4) | 신호를 무시한다. 명령은 끝나거나 timeout 까지 돈다 | 예 |
| `STREAMING` | 줄 단위로 넘긴다(§7) | `execute(…, listener)` 가 거절한다 | 예 |
| `ISOLATED_EXECUTION` | `isolated` 를 지킨다(§3.3) | 플래그를 무시한다. 그러면 안 되는 호출자는 돌리지 않는다 | 예. 명령 사이 상태가 없으므로 자명하다 |
| `RUN_DIRECTORY` | 실행 디렉터리를 둔다 | 옵션을 무시한다. 파일이 필요한 호출자는 돌리지 않거나 다른 길로 물러선다 | 예(§6.1) |

**선언을 묻는 쪽의 방어.** 새 상수는 `default` 없는 `switch` 를 쓴 옛 바이너리의 `supports` 에서 `IncompatibleClassChangeError` 를 던질 수 있다. 훅 실행기는 `EnvironmentShells.declares` 에서 `RuntimeException` 과 `LinkageError` 를 잡아 "선언하지 않음" 으로 읽는다.

### 3.3 명령이 누구의 것인가

세 플래그는 세션별 상태를 지닌 셸에게 이 명령을 그 세션의 평범한 명령으로 돌리면 안 된다고 알린다. 그런 셸은 `cd` · `export` 가 명령 사이에 남고 세션마다 한 번에 명령 하나만 돈다.

| 플래그 | 켜는 쪽 | 그런 셸이 할 일 | 명령마다 프로세스를 띄우는 셸(`LocalShell`) |
|---|---|---|---|
| `background` | `BashTool` 의 백그라운드 경로, Monitor | 세션을 쥐지 않는다. 어떻게 돌릴지는 셸 구현이 정한다 | 무시한다 |
| `hook` | 훅 셸 액션의 공용 사다리 `ShellActionRunner` | 세션 밖에서 돌린다. 세션 잠금을 잡지 않고 상태를 저장하지 않는다. 세션의 현재 상태에서 시작하는 것은 된다 | 무시한다. 다만 I/O 는 §6.1 |
| `isolated` | `hooks.json` 경로(`hook` 과 함께) | 새 셸처럼 돌린다. 환경의 루트와 **기본** 변수에 `environment` 를 더해 시작하고, 세션 상태를 읽지도 저장하지도 않고 잠금을 잡지 않는다. `ISOLATED_EXECUTION` 을 선언해야 지킨다 | 자명하게 지킨다. 다만 I/O 는 §6.1 |

- **`background`.** 한 번에 명령 하나만 도는 지속 셸 세션에서 백그라운드 명령이 세션을 쥐면 다음 명령이 모두 기다리므로, 셸이 구별하도록 플래그를 싣는다.
- **`hook`.** 훅의 명령은 `Bash` 와 같은 `execute` 로 들어가므로 셸은 옵션 없이 모델의 도구 호출과 구별할 수 없다. 구별하지 못하면 병렬 도구 호출의 `preTool` 셸 가드가 모델의 명령을 기다리다 "셸이 바쁘다" 로 실패해 거부로 읽히고, 훅의 `cd` · `export` 가 모델의 셸 상태에 남는다. 훅 전체의 배치는 [훅 환경](../hook/hook-environment.md) 이 소유한다.
- **`isolated`.** `hook` 과 독립이다. `hook` 은 세션의 현재 상태에서 시작할 수 있고 `isolated` 는 그것도 하지 않는다. 모델이 앞서 셸에 한 일이 이 명령에 닿지 않으므로 운영자의 정책 훅을 에이전트가 조종하지 못한다. `isolatedFrom()` 으로 계보를 밝힌 환경은 기반 환경의 뷰이고, 격리 실행은 그 기반 환경의 셸에서 돈다.

### 3.4 래퍼

셸을 감싸는 래퍼(`WorkingDirectoryShell` 등)는 옵션과 기능을 그대로 넘긴다.

- `ExecutionOptions.toBuilder()` 는 **모든** 옵션을 넘긴다. `timeout`, `environment`, `workingDirectory`, `charset`, `redirectErrorStream`, `unixShell`, `maxCaptureBytes`, `stdin`, `background`, `hook`, `isolated`, `cancellation`, `runDirectory` 다. 새 옵션을 빌더로 처음부터 만들면 이 값들이 조용히 사라진다.
- `supports` 를 위임하는 래퍼는 스트리밍 `execute` 도 위임한다. 그러지 않으면 선언한 기능을 거절한다.

## 4. 취소 계약

### 4.1 타입

```java
public interface ShellCancellation {
    static ShellCancellation none();            // 절대 걸리지 않는다
    boolean isCancelled();
    Registration onCancel(Runnable listener);   // 이미 걸렸으면 등록하는 스레드에서 바로 돈다
    interface Registration { void remove(); }   // 멱등
}
public final class ShellCancellationSource {    // 거는 쪽. 명령 하나에 하나
    public static ShellCancellationSource create();
    public ShellCancellation token();
    public boolean cancel();                    // 처음 부를 때만 true. 던지지 않는다
}
```

`ShellCancellation` 을 `CancellationSignal` 과 따로 둔다. 셸 패키지는 `agent.interrupt` 를 볼 수 없고, 그 신호는 실행 단위이며 `InterruptReason` 어휘를 싣기 때문이다.

### 4.2 계약

`ShellFeature.CANCELLATION` 을 선언한 셸은 신호가 걸리면 명령과 그 명령이 띄운 것 전부를 멈추고 `execute` 가 `ShellCancelledException` 을 던지게 한다. 시작 전에 걸려 있으면 띄우지 않고 던진다. 끝난 뒤에 걸리면 무효다. 선언하지 않은 셸은 신호를 무시하고 호출자는 `supports` 로 묻는다.

- "전부" 는 셸이 닿는 범위다. 로컬은 프로세스 트리(§5), 원격은 원격 명령이다.
- 시작 전에 걸린 신호는 명령을 띄우지 않는다. 띄운 뒤 죽이면 부작용이 있는 명령이 죽는 동안 돈다.
- `ShellCancelledException` 은 그때까지의 stdout · stderr · 잘림 여부 · notice 를 싣는다.
- 리스너는 거는 쪽 스레드에서 돌 수 있으므로 thread-safe 하고 멱등이어야 하며 던지지 않는다. 셸은 명령이 끝나면 리스너를 뗀다.
- `cancel()` 은 멈춤이 **요청될** 때까지만 걸린다. 로컬은 SIGTERM 유예를 포함한다. 원격 셸은 요청을 보내고 곧바로 돌아와도 된다.

제공자가 지킬 의무는 [실행 환경](execution-environment.md) 의 제공자 계약 표에 한 줄로 있고, 내용은 이 절이다.

### 4.3 `LocalShell` 의 판정

- `isCancelled()` 를 프로세스를 띄우기 **전에** 본다.
- 띄운 뒤 리스너를 등록한다. 확인과 등록 사이에 걸린 신호는 등록하는 자리에서 돈다.
- 리스너는 **프로세스가 아직 살아 있을 때만** "내가 죽였다" 플래그를 세운 뒤 트리를 멈춘다. 이미 끝난 명령은 정상 결과를 유지한다.
- 남는 경합은 반대쪽이다. 살아 있음을 확인한 직후 명령이 제 힘으로 끝나면 취소로 보고한다. 종료 요청이 살아 있는 명령에 닿았으므로 받아들인다.

### 4.4 포그라운드와 백그라운드의 차이

| | 포그라운드 `Bash` | 백그라운드 `Bash` |
|---|---|---|
| 신호의 주인 | 실행. 도구가 셸 호출 동안만 실행 단위 `CancellationSignal`(`InterruptAccess.signalOf`) 에 리스너를 건다 | 작업. 매니저가 셸이 선언했을 때만 작업마다 만든다 |
| 신호를 거는 것 | 실행의 인터럽트 | `KillShell`, 스택 종료 |
| 스레드 인터럽트 | `THREAD_INTERRUPT` 선언으로 함께 나간다 | 해당 없음 |
| 모델이 받는 결과 | 어느 쪽으로 끝나든 `Bash command interrupted: <reason>` | `BashOutput` 의 `Status: Killed` |

포그라운드 `Bash` 는 실행 단위 신호에 셸 호출 동안만 리스너를 걸고, `THREAD_INTERRUPT` 선언과 스레드 인터럽트도 함께 둔다. 멈춤이 두 길로 나가지만 모델이 받는 결과는 같다. 스레드 인터럽트에 반응하는 것은 셸 계약이 아니라 `LocalShell` 의 동작이므로, 원격 셸에는 신호가 필요하다. 코디네이터가 신호를 먼저 걸고 스레드를 나중에 인터럽트하므로 `LocalShell` 의 명령은 대개 취소 쪽으로 끝난다.

실행의 인터럽트는 백그라운드 명령을 멈추지 않는다. 백그라운드 명령의 신호는 작업의 것이다.

## 5. 프로세스 트리 종료와 남는 틈

`LocalShell` 의 timeout · 인터럽트 · 취소가 모두 `destroyForciblyQuietly` 를 탄다. `KillShell` 과 스택 종료는 취소를 거쳐 같은 코드에 닿는다. 자식만 남겨 두면 `sleep 30 &` 나 띄운 서버가 멈춰야 할 시점을 지나 돈다.

### 5.1 단계

1. `descendants()` 를 스냅숏한다. 플랫폼이 거부하면 빈 목록으로 물러나 부모만 정리한다.
2. 자손을 `destroy()` 한다.
3. 부모를 `destroy()` 하고 트리 전체가 유예(`PROCESS_DESTROY_TIMEOUT`, 200ms) 하나를 나눠 쓰며 기다린다.
4. 유예가 끝나면 **살아 있는 핸들** 에서 자손을 한 번 더 열거해 집합에 더한다(`sweepLateDescendants`).
5. 집합과 부모 가운데 살아 있는 것을 부모의 생사와 상관없이 하나하나 `destroyForcibly()` 한다.

### 5.2 순서의 이유

- **자손 먼저, 스냅숏 먼저.** 부모를 먼저 죽이면 자식이 재부모화되어 `descendants()` 가 열거하지 못한다. `p.destroy()` 뒤에 처음 열거해도 빈 스트림이 돌아올 수 있다.
- **살아 있는 핸들에서 걷는다.** 죽은 프로세스에는 열거할 자식이 없다. 그래서 유예 중에 태어난 프로세스는 그 부모, 또는 명령과의 사이에 있는 어느 조상이 유예를 넘겨 살아 있으면 잡힌다. 명령의 부모 셸이 이미 죽었어도 살아남은 자식 아래에서 태어났으면 잡힌다.
- **유예를 나눠 쓰고 개별로 올린다.** 부모가 SIGTERM 에 죽어도 `trap '' TERM` 으로 버티는 자식을 남기지 않는다.
- **늦게 찾은 프로세스에는 유예가 없다.** 멈춤 요청 뒤에 태어났으므로 곧바로 강제 종료한다.
- **한 번만 걷는다.** `descendants()` 는 프로세스 테이블을 한 시점에 읽으므로 살아 있는 뿌리에서 한 번 걸으면 그 시점 기준으로 완전하다. 중요한 것은 마지막 열거와 kill 사이의 시간이고, 반복은 그 자리에 열거를 한 번 더 끼워 넣는다. 조상이 이미 걸린 핸들은 건너뛴다.

`isAlive()` 확인은 재사용된 pid 를 막는 역할도 한다. 끝난 핸들은 새 프로세스를 살아 있다고 보고하지 않는다.

### 5.3 남는 틈

1. **부모가 이미 죽은 프로세스.** 이중 fork, `( cmd & )`, 데몬화, 그리고 `TERM` 핸들러가 fork 한 뒤 유예 안에 끝난 경우다. 어떤 핸들의 자손도 아니다.
2. **마지막 열거와 그 부모의 kill 사이에 태어난 프로세스.** 프로세스 테이블을 한 번 읽는 시간이다. `TERM` 에 한 번 fork 하는 명령은 여기 걸리지 않는다. `TERM` 을 무시하고 쉬지 않고 fork 하는 명령은 드물게 하나를 남긴다.

`nohup` 과 `setsid` 는 그 자체로는 트리를 벗어나지 않는다. `descendants()` 는 부모 pid 로 걷는다. 벗어나는 것은 부모가 끝난 프로세스다.

이 틈은 [EE-55](../../backlog/execution-environment-open-items.md) 에 열려 있다. `KillShell` 의 답은 이 범위만큼만 말한다([백그라운드 명령](background-commands.md)).

## 6. 훅 명령의 I/O

### 6.1 로컬 훅 I/O

`LocalShell` 은 `isHook() || isIsolated()` 인 명령의 I/O 를 이름으로 닿지 않게 한다. 그 출력이 정책 판정이기 때문이다. 위협으로서의 의미는 [변조 경계](../hook/hook-tamper-boundary.md) 의 남는 위험 절이 소유한다. 이 절은 계약만 적는다.

- **명령.** argv 로 전달한다(`bash -c`).
- **stdout · stderr.** 임시 파일로 리다이렉트하되, JVM 이 프로세스 시작 **전에** 읽기 채널을 열고 시작 직후 경로를 unlink 한다. `waitFor` 뒤 그 채널로 읽는다. 자식은 자기 fd 를 쥐고 있다.
- **stdin.** 데몬 스레드가 파이프에 쓰고 닫는다. 페이로드가 파일에 놓이지 않는다. 호출 스레드에서 쓰면 페이로드가 파이프 버퍼를 넘고 자식이 아직 읽지 않을 때 교착한다.
- **쓰기 스레드의 한계.** 페이로드를 다 쓰거나, 읽는 끝을 쥔 프로세스가 없어져 `IOException` 이 나면 끝난다. 자식이 읽지 않으면 자식이 끝나거나 죽을 때까지 막혀 있다. 물려받은 프로세스가 읽는 끝을 쥐면 그 프로세스가 끝날 때까지 기다린다. bash 는 `cmd &` 에 `/dev/null` 을 주므로 명시적 `<&3` 이나 스스로 fork 해 나간 프로그램의 경우다. 다른 스레드에서 닫을 수 없다. `write` 가 쥔 모니터를 같이 기다리기 때문이다. `execute` 는 이 스레드를 기다리지 않는다.
- **남는 창.** 임시 파일 생성부터 프로세스 시작까지다. 임의 이름의 0600 파일이고, 여기 쓴 내용은 자식의 리다이렉트가 잘라 버리지만 이 창에서 열린 fd 는 살아남는다.
- **Unix 한정.** 다른 프로세스가 연 파일을 지울 수 없는 Windows 에서는 unlink 가 실패해(DEBUG) 캡처 파일이 명령이 끝날 때까지 이름을 유지한다. stdin 파이프는 그대로 성립한다. Windows 는 범위 밖이다.

모델의 명령은 stdin 파일 경로를 유지한다. 파일 리다이렉트는 막히지 않는다. `stdin` 이 없어도 빈 파일을 리다이렉트해, stdin 을 끝까지 읽는 명령이 파이프에서 timeout 까지 막히지 않고 곧바로 EOF 를 받는다.

**실행 디렉터리의 로컬 배치.** `java.io.tmpdir` 아래 새 `aimon-run-*` 디렉터리다. POSIX 파일 시스템이면 디렉터리 0700, 파일 0600 이고, 파일은 `CREATE_NEW` 로 쓴다. 명령이 끝나면 어떤 결과든 지운다. 쓴 뒤 다시 읽어 비교하고 다르면 `RunDirectoryIntegrityException` 을 던진다. 이 비교는 쓰기의 자체 점검이지 변조 방어가 아니다. 바이트가 에이전트가 쓸 수 있는 곳을 지나지 않았고, 같은 uid 의 쓰기는 그 뒤에 일어날 수 있다.

**비밀 파일.** `RunDirectory.Builder.secretFile(path, bytes)` 로 넣은 파일은 `secret` 표지를 단다(`isSecret`, `getSecretPaths`).
`RUN_DIRECTORY` 를 선언한 셸은 표지된 파일을 아무리 작아도 명령 인자나 다른 프로세스 인자에 인라인하지 않고, 인라인하기에 너무 큰
파일과 같은 길(업로드, 호스트의 직접 쓰기)로 옮기며, 업로드 원본은 배치 직후 지운다. 인자는 프로세스가 도는 내내
`/proc/<pid>/cmdline` 으로 누구에게나 보이기 때문이다([셸 엔진](programmatic-tool-calling-shell.md) 결정 4). 파일을 모두 자기가 쓰는
`LocalShell` 은 아무것도 하지 않고 이 조항을 지킨다.

### 6.2 훅 명령이 남긴 것

셸은 명령이 끝날 때 그 명령이 남긴 것을 끝낼 수 있고, 훅은 백그라운드 작업이 자기보다 오래 산다고 기대하면 안 된다. `LocalShell` 은 정상 종료에서 끝내지 않지만 실행 디렉터리는 곧바로 지운다. `hook` 과 `isolated` 모두 같다.

- SPI 는 허용하고 로컬은 하지 않는다. 프로세스 그룹을 통째로 끝내는 샌드박스 셸이 허용의 예다.
- `LocalShell` 에서 스스로 끝난 훅 명령의 자손은 계속 돈다. timeout · 취소 · 인터럽트만 아직 닿는 자손을 끝낸다. 그 출력은 unlink 된 캡처 파일로 가므로 읽을 수 없다.
- 백그라운드 작업은 명령이 끝난 뒤 경로로 훅의 파일을 열 수 없다.
- `LocalShell` 이 끝내지 않는 이유: 훅이 할 수 있는 일(`notify-send &` 를 띄우는 `postTool` 훅)을 바꾸면서 unlink 이상의 이득이 없다. 도는 프로세스는 어차피 닿을 수 있다. 분리된 작업에는 일부만 닿는다.

이를 `ShellFeature` 로 선언할지와 가이드의 안내는 [EE-118](../../backlog/execution-environment-open-items.md) 에 열려 있다. 훅 작성자에게 주는 조언은 [훅 설정 가이드](../../features/hook/hook-config-guide.md) 가 소유한다.

## 7. 스트리밍

```java
public interface ShellLineListener {
    void onStdoutLine(String line);
    default void onStderrLine(String line) { }
}
```

`ShellFeature.STREAMING` 을 선언한 셸만 `execute(command, options, listener)` 를 구현한다. `CANCELLATION` 과 같은 방식이다. default 메서드라 스트리밍하지 않는 구현은 고칠 것이 없고, 지원 여부는 실행 시점에 판정한다.

- **그 밖의 동작.** 명령이 끝날 때까지 막히고, timeout 과 취소 신호를 지키고, 같은 예외를 던진다.
- **결과.** `stdout` 은 비어 있을 수 있다. 줄을 이미 넘겼으니 다시 들고 있을 이유가 없다.
- **stderr.** 결과에 언제나 남고, 셸이 원하면 `onStderrLine` 으로도 넘긴다. 결과의 stderr 는 캡처 상한에서 앞부분만 남으므로, 긴 실행의 마지막 줄이 필요한 호출자는 여기서 모은다.
- **순서와 스레드.** 스트림 안에서만 순서를 지킨다. 전체 순서가 필요하면 `redirectErrorStream` 을 켠다. 리스너는 한 번에 한 호출만 받으므로 자기 자신에 대한 잠금이 필요 없다.
- **느리거나 던지는 리스너.** 느린 리스너는 셸의 다음 읽기를 늦출 뿐 명령을 늦추지 않는다. 던지면 그 줄을 잃고 셸은 로그를 남기고 계속한다.
- **포그라운드.** 셸은 포그라운드의 의미로 돌릴 수 없는 포그라운드 스트리밍 호출을 거절할 수 있다. 포그라운드는 정확히
  `!background && !hook && !isolated` 다 — 세 플래그는 모두 "세션 상태를 읽지도 남기지도 않는다" 는 뜻이므로, 스트리밍하는 셸은
  셋을 다 스트리밍한다. `ISOLATED_EXECUTION` 과 `STREAMING` 을 함께 선언한 셸은 `isolated` 스트리밍 호출을 돌린다. `ExecuteCode` 의
  셸 엔진이 런타임을 그렇게 돌린다(백그라운드도 훅도 아니다 — [셸 엔진](programmatic-tool-calling-shell.md) §3).
- **줄 상한은 물질화하기 전에.** 스트리밍하는 셸은 줄을 메모리에 들기 **전에** 상한을 건다. `LocalShell` 은 개행 없는 64KiB 를
  한 줄로 넘긴다(§7.1). 이벤트로 출력을 받는 원격 셸은 더 큰 상한에서 명령을 끝내고 결과의
  `killCause()`(`ShellKillCause.LINE_LIMIT_EXCEEDED`)로 알릴 수 있다. 어느 쪽이든 상한 안에서 끝나지 않는 줄은 리스너에 통째로
  닿지 않는다.

`Monitor` 의 `shell` source 는 `STREAMING` 과 `CANCELLATION` 을 **둘 다** 선언한 셸에서만 시작한다. 멈출 수 없는 셸에서 끝나지 않는 명령을 돌리면 타임아웃까지 프로세스가 남고, 모델이 `MonitorStop` 에 기대하는 것과 다르다. `STREAMING` 을 선언하지 않은 셸에서 `command` 형태는 실행 시점에 거절된다. 어느 제공자가 선언하는지는 제공자 소관이다.

### 7.1 `LocalShell`

자식의 출력은 스트리밍에서도 임시 파일로 간다.

- 프로세스 시작 **전에** 캡처 파일에 읽기 채널을 하나 더 연다. 훅 명령의 unlink 전에 채널을 여는 것과 같은 순서다.
- `waitFor` 대신 100ms 주기로 위치 지정 읽기를 해서 완성된 줄만 넘긴다. 위치 지정 읽기라 결과를 만드는 `readCapped` 의 채널 위치와 섞이지 않는다.
- 프로세스가 끝나면 남은 바이트를 마저 읽고, 개행 없이 남은 꼬리도 한 줄로 넘긴다.
- 줄은 `\n` 바이트로 가른다. UTF-8 과 ASCII 호환 charset 에서 안전하다. 끝의 `\r` 은 뗀다. 개행 없이 64KiB 에 이른 구간은 한 줄로 넘기고, UTF-8 이면 문자 중간을 자르지 않는다.
- stderr 도 자기 캡처 파일에서 같은 방식으로 따라간다. `redirectErrorStream` 이면 stdout 의 줄이다.

파이프로 바꾸지 않는다. 백그라운드 자식이 쓰기 끝을 쥐고 있으면 EOF 가 오지 않아 읽는 스레드가 새기 때문이다. 캡처 파일을 쓰는 일반 경로의 이유와 같다. 파일에서는 부모가 끝날 때 출력이 이미 파일에 있으므로 읽는 스레드도 EOF 대기도 없다.

캡처 파일은 명령이 도는 동안 디스크에서 자란다. 이 비용을 어떻게 다루는지는 [Monitor](monitor.md) 가 소유한다.

## 8. `Bash` 가 셸을 쓰는 방식

### 8.1 별도 실행 SPI 를 두지 않는다

`BashTool` 은 `VirtualShell` 을 직접 소비한다. `BashExecutor` 같은 Bash 전용 SPI 도, `BashResult` 같은 결과 타입도 두지 않는다. `VirtualShell` · `ExecutionOptions` · `ShellCommandResult` 가 필요한 필드를 이미 갖고 있다. 두 번째 추상화는 변환 코드와 두 번째 진실을 만들고, 기능을 어느 쪽에 넣을지 매번 정하게 한다. `String` 을 돌려주는 어댑터 계약은 exit code 와 부분 출력을 버린다.

셸을 호출마다 환경에서 읽는 규칙과 셸을 쓸 수 없는 실행의 오류는 [실행 환경](execution-environment.md) 이 소유한다.

### 8.2 포그라운드 실행과 타임아웃 하한

포그라운드 실행에는 future 래퍼가 없다. 도구 스레드가 셸 안에서 블록하고, 타임아웃 감시와 프로세스 종료는 셸이 하며, 타임아웃 예외가 부분 출력을 실어 온다. 이 때문에 `THREAD_INTERRUPT` 에 뜻이 생긴다. 인터럽트가 셸의 대기를 풀고 셸이 나가는 길에 프로세스를 죽인다.

`timeout` 입력의 기본값은 120초, 최대는 600초다. `MIN_TIMEOUT_MS` 는 1초다. 셸은 0 이하의 타임아웃을 무기한 대기로 읽으므로 하한이 없으면 `timeout: 0` 이 끝나지 않는 대기가 된다. 스키마의 `minimum` 은 모델에게 주는 정보이고, 방어는 클램프다.

### 8.3 exit code 와 결과

exit code 는 값이다. `BashTool` 은 한 곳에서 실패 표현을 조립한다.

```text
본문 = stdout·stderr 병합 → 30,000자 컷 → 마커 → 캡처 잘림 안내
마커: 성공이면 없음 · 실패면 [exit code: N] · 타임아웃이면 [timed out after Nms]
```

- 마커는 **컷 다음에** 붙는다. 반대로 하면 출력이 큰 실패에서 마커가 잘려 나간다.
- 마커는 본문 **끝**에 온다. 앞에 붙이면 출력의 첫 줄이 명령 결과가 아니게 되어, 모델이 출력을 인용할 때 오염된다.
- `Command failed: ` 접두사는 명령이 아예 실행되지 못한 경우에만 붙는다. 그때는 보고할 exit status 도 출력도 없다.
- 타임아웃은 그때까지의 출력을 같은 경로로 렌더한다.
- 환경의 notice 는 본문 앞에 온다. notice 의 정본은 [실행 환경](execution-environment.md) 이다.

### 8.4 세 상한

세 상한이 서로 다른 것을 지킨다.

| 상한 | 지키는 것 | 값과 자리 |
|---|---|---|
| `maxCaptureBytes` | **메모리**. 힙에 올리는 바이트 | `BashTool` 이 1,000,000 바이트를 `ExecutionOptions` 로 넘긴다. 실패 경로에도 걸린다 |
| 30,000자 | **컨텍스트**. 모델에 실리는 문자 수 | `BashTool.MAX_OUTPUT_LENGTH` |
| `timeout` | **시간** | `ExecutionOptions`, 셸이 강제한다 |

캡처 상한을 도구가 명시적으로 넘긴다. 구현 기본값(`LocalShell` 은 16MiB)에 기대면 다른 `VirtualShell` 구현이 꽂혔을 때 조용히 달라진다. 자식은 전체 출력을 캡처 파일에 쓰고, 이 값은 결과로 읽는 양만 묶는다. 넘치면 결과의 `outputTruncated` 가 켜지고 `Bash` 가 모델에게 알린다.

### 8.5 `grep` 무매치는 실패 그대로 보고한다

`grep` · `diff` · `test` 의 exit 1 을 허용목록으로 성공 처리하지 않는다. `bash -c` 에 들어가는 문자열은 파이프 · `&&` · 환경 변수 접두사 · 서브셸을 포함할 수 있어 선두 프로그램을 신뢰성 있게 뽑을 수 없다. 첫 토큰만 보는 휴리스틱은 `FOO=1 grep x f`, `cat f | grep x`, `git diff` 에서 각각 다르게 틀리고, 틀리는 방향이 "실패를 성공으로 보고" 다. 모델은 `[exit code: 1]` 과 빈 출력을 함께 보므로 "매치 없음" 을 읽어낼 수 있다.

재검토 조건은 [TC-6](../../backlog/tool-contract-open-items.md) 에 있다.

## 9. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| 취소 신호를 `ExecutionOptions` 에 싣는다 | 옵션을 그대로 넘기는 래퍼는 고칠 것이 없고, 지원하지 않는 셸은 무시하면 된다. 지원 여부를 `ShellFeature` 로 묻는다 | 실행이 핸들을 돌려준다(`VirtualShell.start(...)` → `cancel()` · `result()`). 메서드가 하나 늘어 모든 구현과 래퍼가 고쳐져야 한다 |
| 스레드 인터럽트를 계약으로 삼지 않는다 | 인터럽트하려면 실행 스레드를 쥐어야 하는데 그것은 실행기 구현의 사정이다. 원격 셸의 블로킹 호출은 반응한다는 보장이 없고 반응해도 원격 명령은 돈다. "풀이 내려간다" 와 "이 명령을 끝내라" 를 구분하지 못하고 지원 여부도 물을 수 없다 | 인터럽트만으로 멈춘다 |
| 신호 타입을 셸 패키지에 따로 둔다 | §4.1 | `agent.interrupt.CancellationSignal` 재사용. `shell → agent` 역방향 의존이 생긴다 |
| 시작 전 신호는 명령을 띄우지 않는다 | §4.2 | 띄운 직후 리스너로 죽인다 |
| 트리를 스냅숏과 유예 뒤 재열거로 끝낸다 | §5.2 | 프로세스 그룹. `ProcessBuilder` 에 새 그룹으로 띄우는 길이 없고, 래퍼 `setsid(1)` 는 macOS 에 없으며, 끼우면 **모든** 명령의 기동 방식이 바뀐다. 스스로 `setsid` 를 부른 프로세스는 그룹도 떠난다. 자기 프로세스 모델을 가진 셸의 선택으로 남긴다 |
| 재열거는 한 번이다 | §5.2 | 새 것이 안 나올 때까지 반복 |
| 출력은 파일로 캡처한다 | §7.1 | 상속 파이프와 읽는 스레드 |
| 훅 명령의 파일은 시작 후 unlink 한다 | 이름으로 닿는 파일이 없어진다. 자식은 fd 를 쥐므로 캡처는 그대로다 | 훅 프로세스 트리를 끝내 파이프를 쓴다. §6.2 |
| `LocalShell` 은 훅이 남긴 것을 정상 종료에서 끝내지 않는다 | §6.2. 로컬 CLI 사용자의 동작하는 훅을 바꾼다 | 제공자와 맞춘다 |
| 훅이 남긴 것의 처리를 기능 비트로 만들지 않는다 | 어느 쪽이든 훅에 주는 조언이 같아 갈라질 호출자가 없다 | `ShellFeature` 로 선언한다. 재검토는 [EE-118](../../backlog/execution-environment-open-items.md) |
| 스트리밍은 기능 비트와 default 메서드로 연다 | 스트리밍하지 않는 구현은 고칠 것이 없다 | 파이프로 바꾼다(§7.1) |
| `Bash` 는 `VirtualShell` 을 직접 쓴다 | §8.1 | `BashExecutor` 에 타임아웃 · 상한 · exit code 를 더한다. 어댑터만 남긴다 |
| exit code 를 값으로 전달한다 | 호출자가 출력과 함께 본다. 실패 표현이 한 곳에서 만들어진다 | 비정상 종료를 예외로 올린다 |
| `grep` 무매치를 성공으로 보지 않는다 | §8.5 | 허용목록 |

## 10. 하지 말 것

- **옵션을 파생하는 래퍼에서 옵션을 떨구지 말 것.** `toBuilder()` 로 파생한다. 취소 신호가 사라지면 격리 브랜치에서 `KillShell` 이 조용히 듣지 않는다.
- **스트리밍을 선언하고 위임하지 않는 래퍼를 만들지 말 것.**
- **0 이하의 timeout 을 끝내는 값으로 쓰지 말 것.** 셸은 무기한 대기로 읽는다.
- **취소 리스너에서 던지지 말 것.** 거는 쪽 스레드에서 돈다.
- **부모를 먼저 죽이거나 `destroy()` 뒤에 처음 열거하지 말 것.** 자손을 잃는다.
- **`KillShell` 의 답이나 문서에서 트리 종료가 전부를 끝낸다고 약속하지 말 것.** §5.3 이 남는다.
- **`isolated` 를 선언하지 않은 셸에서 그 의미를 가정하지 말 것.** 플래그가 무시된다.
- **훅이 끝난 뒤에도 백그라운드 작업이 남거나 실행 디렉터리를 쓸 수 있다고 기대하지 말 것.**
- **출력에 컷을 적용하기 전에 마커를 붙이지 말 것.**
- **캡처 상한을 구현 기본값에 맡기지 말 것.**
- **모델의 명령 I/O 를 파이프로 바꾸지 말 것.** 백그라운드 자식이 EOF 를 막는다.

## 11. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 항목 | 파일 |
|---|---|
| SPI | `shell/VirtualShell.java`, `shell/ExecutionOptions.java`, `shell/ShellFeature.java`, `shell/ShellCommand.java`, `shell/ShellCommandResult.java`, `shell/ShellKillCause.java`, `shell/ShellLineListener.java`, `shell/RunDirectory.java` |
| 취소 | `shell/ShellCancellation.java`, `shell/ShellCancellationSource.java`, `shell/NoopShellCancellation.java` |
| 예외 | `shell/exception/ShellExecutionException.java`, `shell/exception/ShellTimeoutException.java`, `shell/exception/ShellCancelledException.java`, `shell/exception/RunDirectoryIntegrityException.java` |
| 로컬 구현 | `shell/impl/local/LocalShell.java` |
| 래퍼 | `environment/impl/WorkingDirectoryShell.java` |
| `Bash` | `tools/bash/BashTool.java` |
| 훅 쪽 호출자 | `skill/hook/declarative/ShellActionRunner.java`, `skill/hook/declarative/EnvironmentShells.java` |
