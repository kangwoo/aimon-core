package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.programmatic.testkit.ScriptedToolCallBridge;

/**
 * Each run's runtime sweeps stale entries of {@code .aimon-ptc/} (shell doc §8): at {@code max(2 × maxWallClock, 20
 * min)}, never a {@code probe-*} entry, never its own, and only under the reply directory's parent — guarded by its
 * name, by refusing a symlink and by finding its own entry there. Before this change the sweep ran in the probe at a
 * fixed 20 minutes, so of the engine cases only "an entry past the age is removed" fails on the old code (it never
 * probes); the others guard the new rule.
 */
@DisplayName("aimon_ptc.py — the sweep of stale run directories")
class ShellRuntimeSweepTest {

    @TempDir
    Path tempDir;

    private Path root;
    private Path ptc;
    private ShellCodeExecutionEngine engine;
    private ScriptedToolCallBridge bridge;
    private Path tmpSibling;

    @BeforeAll
    static void requirePython() {
        assumeTrue(Python3.available(), "python3 3.8+ is not installed");
    }

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        ptc = Files.createDirectories(root.resolve(ShellCodeExecutionEngine.PTC_DIRECTORY));
        engine = ShellCodeExecutionEngine.create();
        bridge = ScriptedToolCallBridge.succeedingWith("unused");
    }

    @AfterEach
    void tearDown() throws IOException {
        bridge.close();
        engine.close();
        if (tmpSibling != null) {
            Files.deleteIfExists(tmpSibling);
        }
    }

    @Test
    @DisplayName("the sweep age is twice maxWallClock, at least 20 minutes")
    void sweepAge() {
        assertThat(ShellCodeExecutionEngine.sweepAge(CodeExecutionLimits.defaults())).isEqualTo(Duration.ofMinutes(20));
        assertThat(ShellCodeExecutionEngine
                .sweepAge(CodeExecutionLimits.builder().maxWallClock(Duration.ofMinutes(30)).build()))
                .isEqualTo(Duration.ofMinutes(60));
        assertThat(ShellCodeExecutionEngine.sweepAge(CodeExecutionLimits.builder().wallClock(Duration.ofSeconds(5))
                .maxWallClock(Duration.ofSeconds(5)).build())).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    @DisplayName("default limits: an entry 25 minutes old is removed; a fresh one and an old probe file stay")
    void removesPastTheAge() throws Exception {
        final Path old = backdated(Files.createDirectories(ptc.resolve("old")), Duration.ofMinutes(25));
        Files.writeString(old.resolve("leftover"), "x");
        backdated(old, Duration.ofMinutes(25));
        final Path fresh = Files.createDirectories(ptc.resolve("fresh"));
        final Path probe = backdated(Files.writeString(ptc.resolve("probe-" + "ab".repeat(16)), "1"),
                Duration.ofMinutes(25));
        assertThat(run(CodeExecutionLimits.defaults()).getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(probe).exists();
    }

    @Test
    @DisplayName("maxWallClock 30 minutes: an entry 25 minutes old may be a live run and stays")
    void ageFollowsMaxWallClock() throws Exception {
        final Path live = backdated(Files.createDirectories(ptc.resolve("live")), Duration.ofMinutes(25));
        run(CodeExecutionLimits.builder().maxWallClock(Duration.ofMinutes(30)).build());
        assertThat(live).exists();
    }

    @Test
    @DisplayName("maxWallClock 5 seconds: the 20-minute floor keeps an entry one minute old")
    void floor() throws Exception {
        final Path recent = backdated(Files.createDirectories(ptc.resolve("recent")), Duration.ofMinutes(1));
        run(CodeExecutionLimits.builder().wallClock(Duration.ofSeconds(5)).maxWallClock(Duration.ofSeconds(5)).build());
        assertThat(recent).exists();
    }

    @Test
    @DisplayName("never next to the shell-placed run directory: an old sibling there survives")
    void neverBesideTheRunDirectory() throws Exception {
        // LocalShell places run directories under java.io.tmpdir and has no option to move them.
        final Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        tmpSibling = backdated(Files.writeString(tmp.resolve("aimon-sweep-test-" + UUID.randomUUID()), "keep"),
                Duration.ofMinutes(25));
        final String code = "import os, sys\nopen('run_parent', 'w').write("
                + "os.path.realpath(os.path.dirname(os.path.dirname(os.path.abspath(sys.argv[0])))))\n"
                + "import time\ntime.sleep(0.5)\n";
        assertThat(run(code, CodeExecutionLimits.defaults()).getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(Path.of(Files.readString(root.resolve("run_parent"))).toRealPath()).isEqualTo(tmp);
        assertThat(tmpSibling).exists();
    }

    @Test
    @DisplayName("a reply directory whose parent is not .aimon-ptc sweeps nothing")
    void wrongBasename() throws Exception {
        final Path parent = Files.createDirectories(root.resolve("elsewhere"));
        Files.createDirectories(parent.resolve("own"));
        final Path old = backdated(Files.createDirectories(parent.resolve("old")), Duration.ofMinutes(25));
        runRuntimeDirectly(parent.resolve("own").toString());
        assertThat(old).exists();
    }

    @Test
    @DisplayName("a .aimon-ptc that is a symlink sweeps nothing")
    void symlinkedRoot() throws Exception {
        final Path real = Files.createDirectories(root.resolve("real"));
        Files.createDirectories(real.resolve("own"));
        final Path old = backdated(Files.createDirectories(real.resolve("old")), Duration.ofMinutes(25));
        final Path linked = Files.createDirectories(root.resolve("work")).resolve(".aimon-ptc");
        Files.createSymbolicLink(linked, real);
        runRuntimeDirectly(linked.resolve("own").toString());
        assertThat(old).exists();
    }

    @Test
    @DisplayName("a reply directory without its own entry in .aimon-ptc sweeps nothing")
    void ownEntryMissing() throws Exception {
        final Path old = backdated(Files.createDirectories(ptc.resolve("old")), Duration.ofMinutes(25));
        runRuntimeDirectly(ptc.resolve("missing").toString());
        assertThat(old).exists();
    }

    @Test
    @DisplayName("an old symlink entry is unlinked; its target outside is not touched")
    void symlinkEntryUnlinked() throws Exception {
        Files.createDirectories(ptc.resolve("own"));
        final Path target = Files.createDirectories(root.resolve("outside"));
        final Path precious = Files.writeString(target.resolve("precious"), "keep");
        backdated(target, Duration.ofMinutes(25));
        final Path link = Files.createSymbolicLink(ptc.resolve("link"), target);
        backdateLink(link, Duration.ofMinutes(25));
        runRuntimeDirectly(ptc.resolve("own").toString());
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(precious).exists();
    }

    private CodeExecutionOutcome run(CodeExecutionLimits limits) {
        return run("import time\ntime.sleep(0.5)\n", limits);
    }

    private CodeExecutionOutcome run(String code, CodeExecutionLimits limits) {
        final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("toolu_sweep").code(code)
                .tools(List.of(ToolDefinition.of("Echo", "Echoes.", Map.of("type", "object")))).limits(limits)
                .wallClock(limits.getWallClock()).cancellation(bridge.signal()).build();
        return engine.execute(request, bridge, ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, new SandboxedLocalEnvironment(root)).build());
    }

    /** Runs the runtime with a hand-written run directory, so a guard is tested without an engine hook. */
    private void runRuntimeDirectly(String replyDirectory) throws Exception {
        final Path run = Files.createDirectories(root.resolve("run-" + UUID.randomUUID()));
        try (InputStream in = ShellCodeExecutionEngine.class.getResourceAsStream("aimon_ptc.py")) {
            Files.write(run.resolve("aimon_ptc.py"), in.readAllBytes());
        }
        Files.writeString(run.resolve("main.py"), "import time\ntime.sleep(0.5)\n");
        Files.writeString(run.resolve("config.json"), "{\"tools\":[],\"max_output_bytes\":1000,"
                + "\"max_reply_bytes\":1000,\"max_log_chars\":100,\"wall_clock_seconds\":10,\"sweep_age_seconds\":1200}");
        Files.writeString(run.resolve("key"), HexFormat.of().formatHex(new byte[16]));
        Files.writeString(run.resolve("reply_dir"), replyDirectory);
        final Process process = new ProcessBuilder("python3", "-I", "-u", run.resolve("aimon_ptc.py").toString())
                .directory(root.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static Path backdated(Path path, Duration age) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
        return path;
    }

    /** {@code Files.setLastModifiedTime} follows links; {@code touch -h} dates the link itself. */
    private static void backdateLink(Path link, Duration age) throws Exception {
        final String stamp = LocalDateTime.ofInstant(Instant.now().minus(age), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm.ss"));
        final Process touch = new ProcessBuilder("touch", "-h", "-t", stamp, link.toString()).redirectErrorStream(true)
                .start();
        final String output = new String(touch.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(touch.waitFor(10, TimeUnit.SECONDS) && touch.exitValue() == 0).as(output).isTrue();
    }
}
