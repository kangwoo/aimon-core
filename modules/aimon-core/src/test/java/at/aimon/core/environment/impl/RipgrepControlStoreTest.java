package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.ContentQuery;
import at.aimon.core.environment.ContentSearchResult;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.tools.file.GrepTool;

/**
 * The control store stays out of {@code Grep} when {@code rg} answers it (execution-environment design §9.2, §15).
 * rg's exclusion globs alone do not do it: rg never applies them to a path named on its command line, and a later user
 * glob overrides them. These cases pin both holes shut — once against the real {@code rg} (skipped when it is not on
 * the {@code PATH}), and once against a stand-in {@code rg} that reports a control-store match whatever it is asked,
 * so the filtering is proven on every machine.
 */
@DisplayName("RipgrepContentSearch — the control store never reaches Grep")
class RipgrepControlStoreTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(workspace.resolve(".aimon/s"));
        Files.writeString(workspace.resolve(".aimon/s/x.json"), "{\"note\": \"CONTROL-ONLY token\"}\n");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/a.txt"), "a token here\n");
    }

    private static EnvironmentRequest request() {
        return EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.of("agent:test")).build();
    }

    private static String grep(ExecutionEnvironment env, Map<String, Object> input) {
        final ToolResult result = new GrepTool().execute(ToolInput.of(input),
                TestExecutionEnvironments.contextBuilder(env).build());
        return (result.isSuccess() ? "OK:" : "ERR:") + result.getContent();
    }

    /** Every way the model can point Grep at the control store, by path or by glob. */
    private List<Map<String, Object>> probes() {
        final List<Map<String, Object>> probes = new ArrayList<>();
        for (String path : controlStorePaths()) {
            probes.add(Map.of("pattern", "token", "path", path));
            probes.add(Map.of("pattern", "token", "path", path, "output_mode", "content"));
        }
        for (String glob : List.of("*", "*.json", "**", ".aimon/**", "x.json")) {
            probes.add(Map.of("pattern", "token", "glob", glob));
            probes.add(Map.of("pattern", "token", "glob", glob, "output_mode", "content"));
        }
        probes.add(Map.of("pattern", "token", "type", "json"));
        return probes;
    }

    /** Every spelling of the control store as a Grep target, including ones that leave the workspace and return. */
    private List<String> controlStorePaths() {
        final Path abs = workspace.toAbsolutePath().normalize();
        final String name = abs.getFileName().toString();
        return List.of(".aimon", ".aimon/s", "./.aimon", "src/../.aimon", abs.resolve(".aimon").toString(),
                "../" + name + "/.aimon", abs + "/../" + name + "/.aimon", ".AIMON", ".Aimon/s");
    }

    private void assertNoControlStore(LocalExecutionEnvironmentProvider provider) {
        final ExecutionEnvironment env = provider.resolve(request());
        assertThat(env.contentSearch()).as("rg answers these queries").isPresent();
        for (Map<String, Object> probe : probes()) {
            assertThat(grep(env, probe)).as(probe.toString()).doesNotContain(".aimon").doesNotContain("x.json")
                    .doesNotContain("CONTROL-ONLY");
        }
        // The control store is hidden, not the workspace: an ordinary search still finds the ordinary file.
        assertThat(grep(env, Map.of("pattern", "token", "glob", "*"))).contains("src/a.txt");
    }

    @Test
    @DisplayName("an isolated branch whose directory is a link into the control store or out of the workspace is refused")
    void linkedBranchRootRefused() throws IOException {
        // The check runs before rg starts, so no rg is needed: a refusal is IllegalArgumentException, and a target that
        // passes it fails later, starting the missing program.
        final Path noRg = workspace.resolve("no-such-rg");
        final RipgrepContentSearch parent = new RipgrepContentSearch(noRg, workspace, List.of(".aimon"));
        Files.createDirectories(workspace.resolve(".worktrees"));
        Files.createSymbolicLink(workspace.resolve(".worktrees/step_1"), workspace.resolve(".aimon"));
        final Path outside = Files.createTempDirectory("rg-outside");
        try {
            Files.createSymbolicLink(workspace.resolve(".worktrees/step_2"), outside);
            Files.createDirectories(workspace.resolve(".worktrees/step_3"));
            final ContentQuery query = ContentQuery.builder().pattern("token").path(".").build();

            assertThatThrownBy(() -> parent.rootedAt(workspace.resolve(".worktrees/step_1")).search(query))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hidden prefix");
            assertThatThrownBy(() -> parent.rootedAt(workspace.resolve(".worktrees/step_2")).search(query))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the workspace");
            // A real branch directory passes the check (and then fails to start the missing rg).
            assertThatThrownBy(() -> parent.rootedAt(workspace.resolve(".worktrees/step_3")).search(query))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not start rg");
            // The branch keeps the parent's hidden prefixes.
            assertThatThrownBy(() -> parent.rootedAt(workspace.resolve(".worktrees/step_3"))
                    .search(ContentQuery.builder().pattern("token").path(".aimon").build()))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            Files.deleteIfExists(workspace.resolve(".worktrees/step_2"));
            Files.deleteIfExists(outside);
        }
    }

    @Nested
    @DisplayName("with the real rg")
    class RealRipgrep {

        @Test
        @DisplayName("Grep by path or glob never shows the control store")
        void grepHidesControlStore() {
            final Optional<Path> rg = RipgrepContentSearch.probe();
            assumeTrue(rg.isPresent(), "rg is not on the PATH");
            try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                    .workspaceRoot(workspace).ripgrepExecutable(rg.get()).build()) {
                assertNoControlStore(provider);
            }
        }

        @Test
        @DisplayName("a target at or under a hidden prefix is refused before rg runs")
        void hiddenTargetRefused() {
            final Optional<Path> rg = RipgrepContentSearch.probe();
            assumeTrue(rg.isPresent(), "rg is not on the PATH");
            final RipgrepContentSearch search = new RipgrepContentSearch(rg.get(), workspace, List.of(".aimon"));
            for (String path : controlStorePaths()) {
                assertThatThrownBy(() -> search.search(ContentQuery.builder().pattern("token").path(path).build()))
                        .as(path).isInstanceOf(IllegalArgumentException.class);
            }
            // A sibling that only shares the prefix's spelling is not hidden.
            assertThat(search.search(ContentQuery.builder().pattern("token").path("src").build()).getFiles())
                    .extracting(ContentSearchResult.FileMatches::getPath).containsExactly("src/a.txt");
        }
    }

    /**
     * The stand-ins are written once for the class and each executed once before any test uses it. On macOS 13+ the
     * first execution of a newly written script waits for syspolicyd's Gatekeeper scan, which is serialised
     * machine-wide; paid inside a timed region, it made the timeout case kill an rg that had not started and the
     * cancellation case overrun its limit. The verdict is cached per file, so the warm-up pays it up front. A stand-in
     * answers {@value #WARM_UP} — which rg is never passed first — by exiting at once.
     *
     * <p>
     * The stand-ins share one directory and its {@code args} / {@code pid} files, so these tests must not run
     * concurrently with each other; the build runs tests sequentially.
     */
    @Nested
    @DisplayName("with a stand-in rg that always reports a control-store match")
    class StandInRipgrep {

        /** The first argument on which a stand-in exits at once; {@link RipgrepContentSearch} always starts --json. */
        private static final String WARM_UP = "--warm-up";

        /** How long both warm-ups together may queue for syspolicyd: minutes on a busy Mac, no time at all on Linux. */
        private static final long WARM_UP_TIMEOUT_MINUTES = 5;

        @TempDir
        static Path bin;

        private static Path fakeRg;
        private static Path argsFile;
        private static Path slowRg;
        private static Path pidFile;

        @BeforeAll
        static void writeStandIns() throws Exception {
            assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win"),
                    "the stand-in rg is a POSIX shell script");
            final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(WARM_UP_TIMEOUT_MINUTES);
            argsFile = bin.resolve("args");
            pidFile = bin.resolve("pid");
            final Path output = bin.resolve("output.jsonl");
            Files.writeString(output,
                    "{\"type\":\"begin\",\"data\":{\"path\":{\"text\":\"./.aimon/s/x.json\"}}}\n"
                            + match("./.aimon/s/x.json", "CONTROL-ONLY token")
                            + match(".aimon/s/x.json", "CONTROL-ONLY token") + match("./src/a.txt", "a token here"));
            fakeRg = standIn("rg", "printf '%s\\n' \"$@\" > '" + argsFile + "'\ncat '" + output + "'\nexit 0\n",
                    deadline);
            // Records its pid and then sleeps far past any test's patience, holding stdout open.
            slowRg = standIn("slow-rg", "echo $$ > '" + pidFile + "'\nexec sleep 60\n", deadline);
        }

        @BeforeEach
        void forgetLastRun() throws IOException {
            Files.deleteIfExists(argsFile);
            Files.deleteIfExists(pidFile);
        }

        /**
         * Writes an executable stand-in and runs it once with {@value #WARM_UP}, so its first execution is behind it.
         * {@code deadline} ({@link System#nanoTime()}) is shared by every warm-up of the class.
         */
        private static Path standIn(String name, String body, long deadline) throws IOException, InterruptedException {
            final Path script = bin.resolve(name);
            Files.writeString(script, "#!/bin/sh\nif [ \"$1\" = " + WARM_UP + " ]; then exit 0; fi\n" + body);
            assertThat(script.toFile().setExecutable(true)).isTrue();
            final Process process = new ProcessBuilder(script.toString(), WARM_UP).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError("the stand-in " + script + " did not finish its warm-up within "
                        + WARM_UP_TIMEOUT_MINUTES
                        + " minutes of the class's warm-ups. On macOS its first execution waits for syspolicyd's "
                        + "Gatekeeper scan; `log show --predicate 'process == \"syspolicyd\"' --last 10m` shows the queue");
            }
            assertThat(process.exitValue()).as("warm-up exit status of %s", script).isZero();
            return script;
        }

        private static String match(String path, String line) {
            return "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"" + path + "\"},\"lines\":{\"text\":\"" + line
                    + "\\n\"},\"line_number\":1}}\n";
        }

        @Test
        @DisplayName("control-store paths rg reports are dropped")
        void reportedControlStoreDropped() {
            final RipgrepContentSearch search = new RipgrepContentSearch(fakeRg, workspace, List.of(".aimon"));
            final ContentSearchResult result = search
                    .search(ContentQuery.builder().pattern("token").path(".").glob("*").build());
            assertThat(result.getFiles()).extracting(ContentSearchResult.FileMatches::getPath)
                    .containsExactly("src/a.txt");
        }

        @Test
        @DisplayName("the exclusion glob comes after the user's glob, so it wins")
        void exclusionAfterUserGlob() throws IOException {
            final RipgrepContentSearch search = new RipgrepContentSearch(fakeRg, workspace, List.of(".aimon"));
            search.search(ContentQuery.builder().pattern("token").path(".").glob("*").build());
            final List<String> args = Files.readAllLines(argsFile);
            assertThat(args.lastIndexOf("!/.aimon")).isGreaterThan(args.indexOf("*"));
        }

        @Test
        @DisplayName("a hidden target never starts rg")
        void hiddenTargetNeverRuns() {
            final RipgrepContentSearch search = new RipgrepContentSearch(fakeRg, workspace, List.of(".aimon"));
            assertThatThrownBy(() -> search.search(ContentQuery.builder().pattern("token").path(".aimon").build()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(argsFile).doesNotExist();
        }

        @Test
        @DisplayName("a symbolic link named as the target is refused when it leads into the control store or out of"
                + " the workspace, and rg never starts")
        void symlinkTargetRefused() throws IOException {
            Files.createSymbolicLink(workspace.resolve("lnk"), workspace.resolve(".aimon"));
            Files.createSymbolicLink(workspace.resolve("out"), bin);
            Files.createSymbolicLink(workspace.resolve("src/up"), workspace.resolve(".aimon/s"));
            final RipgrepContentSearch search = new RipgrepContentSearch(fakeRg, workspace, List.of(".aimon"));
            for (String path : List.of("lnk", "lnk/s", "out", "src/up", workspace.resolve("lnk").toString())) {
                assertThatThrownBy(() -> search.search(ContentQuery.builder().pattern("token").path(path).build()))
                        .as(path).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(argsFile).doesNotExist();

            // Through Grep the walk answers instead, and it does not follow the link either.
            try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                    .workspaceRoot(workspace).ripgrepExecutable(fakeRg).build()) {
                final ExecutionEnvironment env = provider.resolve(request());
                for (String path : List.of("lnk", "out")) {
                    assertThat(grep(env, Map.of("pattern", "token", "path", path, "output_mode", "content"))).as(path)
                            .doesNotContain("CONTROL-ONLY").doesNotContain("x.json");
                }
            }
        }

        @Test
        @DisplayName("a stuck rg is killed at the timeout instead of blocking the call")
        void stuckRgTimesOut() throws Exception {
            final RipgrepContentSearch search = new RipgrepContentSearch(slowRg, workspace, List.of(".aimon"),
                    Duration.ofSeconds(2));

            final long started = System.nanoTime();
            assertThatThrownBy(() -> search.search(ContentQuery.builder().pattern("token").path("src").build()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            assertProcessGone();
        }

        @Test
        @DisplayName("a cancelled query kills rg while it is still running")
        void cancelledQueryKillsRg() throws Exception {
            final RipgrepContentSearch search = new RipgrepContentSearch(slowRg, workspace, List.of(".aimon"));
            final AtomicBoolean cancelled = new AtomicBoolean();
            final ContentQuery query = ContentQuery.builder().pattern("token").path("src").cancellation(() -> {
                // Cancel once rg is up, so the kill lands on a running process.
                if (Files.exists(pidFile)) {
                    cancelled.set(true);
                }
                return cancelled.get();
            }).build();

            final long started = System.nanoTime();
            assertThatThrownBy(() -> search.search(query)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cancelled");

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            assertProcessGone();
        }

        private void assertProcessGone() throws Exception {
            final long pid = Long.parseLong(Files.readString(pidFile).trim());
            final Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                handle.get().onExit().get(10, TimeUnit.SECONDS);
            }
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).isNotEqualTo(Optional.of(true));
        }

        @Test
        @DisplayName("Grep through the local provider never shows the control store")
        void grepHidesControlStore() {
            try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                    .workspaceRoot(workspace).ripgrepExecutable(fakeRg).build()) {
                assertNoControlStore(provider);
            }
        }
    }
}
