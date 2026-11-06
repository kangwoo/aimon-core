package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.programmatic.testkit.ScriptedToolCallBridge;

/**
 * Q-C4: whatever ends a run — the wall clock, a cancellation, a bridge limit, the runtime's own output limit, or the
 * program simply finishing — no process of the run outlives it: not the runtime, not a child the program started, not
 * the runtime's watchdog or anchor (shell doc §6, §8). The shell here kills the way aimon-sandbox's provider does, by
 * process group only ({@link ProcessGroupKillShell}); on the old runtime, which left that group, every case but the
 * output limit leaves the program running.
 */
@DisplayName("ShellCodeExecutionEngine — no process outlives a run (Q-C4)")
class ShellEngineProcessCleanupTest {

    private static final String TOOL = "Echo";
    private static final Duration DEAD_WITHIN = Duration.ofSeconds(5);
    /** Records the runtime's pid, a child's pid and the run directory's name, then goes on with the case. */
    private static final String PROLOGUE = "import os, subprocess, sys\n"
            + "child = subprocess.Popen(['sleep', '600'])\n" + "with open('pids.tmp', 'w') as f:\n"
            + "    f.write('%d %d %s' % (os.getpid(), child.pid, os.path.basename(os.path.dirname(sys.argv[0]))))\n"
            + "os.rename('pids.tmp', 'pids')\n";

    @TempDir
    Path tempDir;

    private Path root;
    private ShellCodeExecutionEngine engine;
    private ProcessGroupKillShell shell;
    private ScriptedToolCallBridge bridge;
    private final Set<Long> recorded = new LinkedHashSet<>();
    private long runtimeGroup = -1;
    private String runMarker;

    @BeforeAll
    static void requirePython() {
        assumeTrue(Python3.available(), "python3 3.8+ is not installed");
    }

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        engine = ShellCodeExecutionEngine.create();
        shell = new ProcessGroupKillShell(new LocalShell(root));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bridge != null) {
            bridge.close();
        }
        engine.close();
        shell.close();
        // A failing run on the old runtime must not leave strays behind (TASK acceptance).
        final List<Long> left = new ArrayList<>();
        if (runMarker != null) {
            left.addAll(pgrep("-f", runMarker));
        }
        if (runtimeGroup > 0) {
            left.addAll(pgrep("-g", String.valueOf(runtimeGroup)));
        }
        for (long pid : recorded) {
            if (alive(pid)) {
                left.add(pid);
            }
        }
        for (long pid : left) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
        assertThat(left).as("processes of the run left behind").isEmpty();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("the wall clock over a bytecode loop: TIMED_OUT, every process of the run dead")
    void wallClockBytecodeLoop() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("unused");
        final CompletableFuture<CodeExecutionOutcome> running = start(PROLOGUE + "while True:\n    pass\n",
                Duration.ofSeconds(2));
        snapshot();
        assertThat(running.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(CodeExecutionStatus.TIMED_OUT);
        assertAllDead();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("the wall clock over a builtin that holds the GIL: TIMED_OUT, every process of the run dead")
    void wallClockGilHoldingBuiltin() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("unused");
        final CompletableFuture<CodeExecutionOutcome> running = start(PROLOGUE + "sum(range(10**13))\n",
                Duration.ofSeconds(2));
        snapshot();
        assertThat(running.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(CodeExecutionStatus.TIMED_OUT);
        assertAllDead();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("cancellation while waiting on a call: CANCELLED, every process of the run dead")
    void cancelWaitingOnCall() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("unused").holdCalls();
        final CompletableFuture<CodeExecutionOutcome> running = start(PROLOGUE + "await " + TOOL + "({})\n",
                Duration.ofSeconds(30));
        snapshot();
        final long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (bridge.calls().isEmpty() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        bridge.cancel();
        assertThat(running.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(CodeExecutionStatus.CANCELLED);
        assertAllDead();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("cancellation during a builtin that holds the GIL: CANCELLED, every process of the run dead")
    void cancelGilHoldingBuiltin() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("unused");
        final CompletableFuture<CodeExecutionOutcome> running = start(PROLOGUE + "sum(range(10**13))\n",
                Duration.ofSeconds(30));
        snapshot();
        bridge.cancel();
        assertThat(running.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(CodeExecutionStatus.CANCELLED);
        assertAllDead();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a bridge limit: LIMIT_EXCEEDED, every process of the run dead")
    void bridgeLimit() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("ok").stopAfter(3);
        final CompletableFuture<CodeExecutionOutcome> running = start(
                PROLOGUE + "import time\ntime.sleep(0.5)\n" + "for i in range(50):\n    try:\n        await " + TOOL
                        + "({'i': i})\n    except ToolError:\n" + "        pass\nprint('done')\n",
                Duration.ofSeconds(30));
        snapshot();
        final CodeExecutionOutcome outcome = running.get(30, TimeUnit.SECONDS);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.LIMIT_EXCEEDED);
        assertThat(bridge.calls()).hasSizeLessThanOrEqualTo(5);
        assertAllDead();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("the runtime's own output limit ends the run without the shell's kill; the child dies with it")
    void outputLimitSelfEnd() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("unused");
        final CompletableFuture<CodeExecutionOutcome> running = start(
                PROLOGUE + "import time\ntime.sleep(0.5)\nwhile True:\n    print('x' * 64)\n",
                CodeExecutionLimits.builder().maxScriptOutputBytes(10_000).build(), Duration.ofSeconds(30));
        snapshot();
        final CodeExecutionOutcome outcome = running.get(30, TimeUnit.SECONDS);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.LIMIT_EXCEEDED);
        // DONE arrived and the wrapper finished: the shell never had to kill the group (§6 intent).
        assertThat(shell.groupKills()).isZero();
        assertAllDead();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a completed program's background child does not outlive the run (§8)")
    void completedProgramLeavesNoChild() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("unused");
        final CompletableFuture<CodeExecutionOutcome> running = start(PROLOGUE + "print('bye')\n",
                Duration.ofSeconds(30));
        snapshot();
        final CodeExecutionOutcome outcome = running.get(30, TimeUnit.SECONDS);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).contains("bye");
        assertAllDead();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("the watchdog is not the program's child: waiting for any child does not wait on it")
    void watchdogIsNotTheProgramsChild() throws Exception {
        bridge = ScriptedToolCallBridge.succeedingWith("unused");
        final String code = "import os, subprocess\n" + "subprocess.Popen(['true']).wait()\n" + "try:\n"
                + "    os.wait()\n" + "    print('waited on a child it never started')\n"
                + "except ChildProcessError:\n" + "    print('no child left')\n";
        final CodeExecutionOutcome outcome = start(code, Duration.ofSeconds(10)).get(30, TimeUnit.SECONDS);
        assertThat(outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(outcome.getStdout()).contains("no child left");
    }

    private CompletableFuture<CodeExecutionOutcome> start(String code, Duration wallClock) {
        return start(code, CodeExecutionLimits.defaults(), wallClock);
    }

    private CompletableFuture<CodeExecutionOutcome> start(String code, CodeExecutionLimits limits, Duration wallClock) {
        final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("toolu_cleanup").code(code)
                .tools(List.of(ToolDefinition.of(TOOL, "Echoes.", Map.of("type", "object")))).limits(limits)
                .wallClock(wallClock).cancellation(bridge.signal()).build();
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, new SandboxedLocalEnvironment(root, shell)).build();
        return CompletableFuture.supplyAsync(() -> engine.execute(request, bridge, context));
    }

    /**
     * Waits for the program's {@code pids} file and records every process of the run before anything kills it: the
     * runtime, its parent (the anchor), its descendants and every member of its process group (the watchdog, which is
     * not a descendant once double-forked).
     */
    private void snapshot() throws Exception {
        final Path pids = root.resolve("pids");
        final long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.exists(pids)) {
            assertThat(System.nanoTime()).as("the program never wrote its pids").isLessThan(until);
            Thread.sleep(10);
        }
        final String[] fields = Files.readString(pids, StandardCharsets.US_ASCII).strip().split(" ");
        final long runtime = Long.parseLong(fields[0]);
        recorded.add(runtime);
        recorded.add(Long.parseLong(fields[1]));
        runMarker = fields[2];
        runtimeGroup = runtime;
        ProcessHandle.of(runtime).ifPresent(handle -> {
            handle.parent().ifPresent(parent -> recorded.add(parent.pid()));
            handle.descendants().forEach(descendant -> recorded.add(descendant.pid()));
        });
        recorded.addAll(pgrep("-g", String.valueOf(runtime)));
        assertThat(recorded).as("the runtime and its child").hasSizeGreaterThanOrEqualTo(2);
    }

    private void assertAllDead() throws InterruptedException {
        final long until = System.nanoTime() + DEAD_WITHIN.toNanos();
        List<Long> alive = aliveRecorded();
        while (!alive.isEmpty() && System.nanoTime() < until) {
            Thread.sleep(50);
            alive = aliveRecorded();
        }
        assertThat(alive).as("processes of the run still alive %s after the run ended", DEAD_WITHIN).isEmpty();
        assertThat(pgrep("-f", runMarker)).as("runtime processes matching the run directory").isEmpty();
    }

    private List<Long> aliveRecorded() {
        final List<Long> alive = new ArrayList<>();
        for (long pid : recorded) {
            if (alive(pid)) {
                alive.add(pid);
            }
        }
        return alive;
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static List<Long> pgrep(String flag, String pattern) {
        try {
            final Process process = new ProcessBuilder("pgrep", flag, pattern).redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            process.waitFor(10, TimeUnit.SECONDS);
            final List<Long> pids = new ArrayList<>();
            for (String line : output.split("\\s+")) {
                if (!line.isBlank()) {
                    final long pid = Long.parseLong(line.strip());
                    if (pid != ProcessHandle.current().pid() && alive(pid)) {
                        pids.add(pid);
                    }
                }
            }
            return pids;
        } catch (IOException | NumberFormatException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
