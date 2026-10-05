package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.DelegatingFileSystem;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.impl.local.LocalShell;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayName("LocalExecutionEnvironmentProvider staging (execution-environment §4.4)")
class LocalExecutionEnvironmentProviderStagingTest {

    @TempDir
    Path workspace;

    @TempDir
    Path controlDir;

    @TempDir
    Path elsewhere;

    private LocalFileSystem control;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        control = new LocalFileSystem(new LocalFileSystemConfig(controlDir.toString()));
        control.initialize();
        control.write("skills/demo/SKILL.md", "# demo");
        control.write("skills/demo/scripts/run.sh", "echo staged-script");
        control.write("skills/demo/references/notes.md", "notes");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
        control.close();
    }

    private static EnvironmentRequest request() {
        return EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.of("agent:test")).build();
    }

    private ExecutionEnvironment ownedEnv(LocalExecutionEnvironmentProvider.Builder builder) {
        final LocalExecutionEnvironmentProvider provider = builder.workspaceRoot(workspace).contentSearch(false)
                .build();
        closeables.add(provider);
        return provider.resolve(request());
    }

    private ExecutionEnvironment ownedEnv() {
        return ownedEnv(LocalExecutionEnvironmentProvider.builder());
    }

    private ExecutionEnvironment borrowedEnv(VirtualFileSystem fs) {
        final LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder().fileSystem(fs)
                .contentSearch(false).build();
        closeables.add(provider);
        return provider.resolve(request());
    }

    private StagedResource scan() {
        return StagedResource.scan(control, "skills/demo", "demo");
    }

    private String root() {
        return workspace.toAbsolutePath().normalize().toString();
    }

    @Test
    @DisplayName("the first stage copies the files and returns the absolute path the file tools and a shell elsewhere"
            + " can both read")
    void firstStage() throws Exception {
        final ExecutionEnvironment env = ownedEnv();
        final StagedResource resource = scan();

        final String path = env.stage(resource);

        assertThat(path).isEqualTo(root() + "/.aimon-staged/demo/" + resource.getContentKey());
        assertThat(Path.of(path, LocalStaging.MARKER)).exists();
        try (InputStream in = env.fileSystem().read(path + "/scripts/run.sh")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("echo staged-script");
        }
        final ShellCommandResult cat = new LocalShell(elsewhere).execute(() -> "cat " + path + "/references/notes.md");
        assertThat(cat.exitCode()).isZero();
        assertThat(cat.stdout()).isEqualTo("notes");
    }

    @Test
    @DisplayName("a name that is not one path segment is refused before anything is written or deleted")
    void unsafeNameRefused() {
        final ExecutionEnvironment env = ownedEnv();
        for (String name : List.of("..", ".", "a/b", "..\\x")) {
            final StagedResource resource = StagedResource.scan(control, "skills/demo", name);
            assertThatThrownBy(() -> env.stage(resource)).as(name).isInstanceOf(StagingException.class)
                    .hasMessageContaining("single path segment");
        }
        assertThat(workspace.resolve(".aimon-staged")).doesNotExist();
    }

    @Test
    @DisplayName("the marker is written last, and a second stage with the marker present writes nothing")
    void markerLastAndSkip() {
        final LocalFileSystem shared = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        shared.initialize();
        closeables.add(shared::close);
        final List<String> writes = new ArrayList<>();
        final VirtualFileSystem counting = new DelegatingFileSystem(shared) {
            @Override
            public void write(String path, InputStream content, long contentLength) {
                writes.add(path);
                super.write(path, content, contentLength);
            }
        };
        final ExecutionEnvironment env = borrowedEnv(counting);
        final StagedResource resource = scan();

        final String first = env.stage(resource);
        writes.removeIf(w -> w.endsWith("/" + LocalStaging.GITIGNORE));
        assertThat(writes).hasSize(resource.getFiles().size() + 1);
        assertThat(writes.get(writes.size() - 1)).endsWith("/" + LocalStaging.MARKER);

        final int before = writes.size();
        assertThat(env.stage(resource)).isEqualTo(first);
        assertThat(writes).hasSize(before);
    }

    @Test
    @DisplayName("a target deleted from disk is copied again — the marker on the target decides, not memory")
    void deletedTargetRecopied() throws Exception {
        final ExecutionEnvironment env = ownedEnv();
        final StagedResource resource = scan();
        final String path = env.stage(resource);
        deleteTree(Path.of(path));

        assertThat(env.stage(resource)).isEqualTo(path);
        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo staged-script");
    }

    @Test
    @DisplayName("changed content stages under a new key and the old copy stays")
    void newKeyNewPath() {
        final ExecutionEnvironment env = ownedEnv();
        final String first = env.stage(scan());
        control.write("skills/demo/scripts/run.sh", "echo v2");
        final String second = env.stage(scan());

        assertThat(second).isNotEqualTo(first);
        assertThat(Path.of(first, "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(Path.of(second, "scripts/run.sh")).hasContent("echo v2");
    }

    @Test
    @DisplayName("a resource over the size limit is refused")
    void overLimit() {
        final ExecutionEnvironment env = ownedEnv(LocalExecutionEnvironmentProvider.builder().maxStagedBytes(5));
        assertThatThrownBy(() -> env.stage(scan())).isInstanceOf(StagingException.class)
                .hasMessageContaining("staging limit");
    }

    @Test
    @DisplayName(".stageignore'd files are not copied, and the copy verifies against the same hash")
    void stageIgnore() {
        control.write("skills/demo/assets/big.bin", "0123456789");
        control.write("skills/demo/.stageignore", "assets/\n");
        final ExecutionEnvironment env = ownedEnv();
        final String path = env.stage(scan());

        assertThat(Path.of(path, "assets/big.bin")).doesNotExist();
        assertThat(Path.of(path, ".stageignore")).exists();
        assertThat(Path.of(path, LocalStaging.MARKER)).exists();
    }

    @Test
    @DisplayName("a workspace resource under the hidden control store is copied, not passed through")
    void hiddenWorkspaceResourceIsCopied() throws Exception {
        final LocalFileSystem shared = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        shared.initialize();
        closeables.add(shared::close);
        shared.write(".aimon/skills/local/SKILL.md", "# local");
        shared.write(".aimon/skills/local/scripts/x.sh", "echo hi");
        final ExecutionEnvironment env = borrowedEnv(shared);
        final StagedResource resource = StagedResource.scan(shared, ".aimon/skills/local", "local");

        final String path = env.stage(resource);

        // Passing the .aimon/ path through would hand the model a directory its file tools cannot read.
        assertThat(path).isEqualTo(root() + "/.aimon-staged/local/" + resource.getContentKey());
        try (InputStream in = env.fileSystem().read(path + "/scripts/x.sh")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("echo hi");
        }
    }

    @Test
    @DisplayName("a staged path is returned unchanged when the resource already lives in the workspace")
    void sameFileSystemPassthrough() {
        final LocalFileSystem shared = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        shared.initialize();
        closeables.add(shared::close);
        shared.write("skills/local/SKILL.md", "# local");
        final ExecutionEnvironment env = borrowedEnv(shared);

        final String path = env.stage(StagedResource.scan(shared, "skills/local", "local"));

        assertThat(path).isEqualTo(root() + "/skills/local");
        assertThat(workspace.resolve(".aimon-staged")).doesNotExist();
    }

    @Test
    @DisplayName("the owned-mode sweep keeps the newest and recent copies and deletes old superseded and partial ones")
    void sweep() throws Exception {
        final Instant now = Instant.parse("2026-09-28T12:00:00Z");
        final Path report = workspace.resolve(".aimon-staged/report");
        final Path newest = stagedCopy(report, "00000000000e0e0e", now.minus(Duration.ofHours(1)));
        final Path young = stagedCopy(report, "00000000000a0a0a", now.minus(Duration.ofHours(2)));
        final Path old = stagedCopy(report, "00000000000d0d0d", now.minus(Duration.ofHours(48)));
        final Path partial = report.resolve("00000000000b0b0b");
        Files.createDirectories(partial);
        Files.writeString(partial.resolve("half.txt"), "x");
        Files.setLastModifiedTime(partial, FileTime.from(now.minus(Duration.ofHours(48))));
        final Path freshPartial = report.resolve("00000000000f0f0f");
        Files.createDirectories(freshPartial);
        Files.setLastModifiedTime(freshPartial, FileTime.from(now.minus(Duration.ofMinutes(5))));

        ownedEnv(LocalExecutionEnvironmentProvider.builder().clock(Clock.fixed(now, ZoneOffset.UTC))
                .stagingSweepGrace(Duration.ofHours(24)));

        assertThat(newest).exists();
        assertThat(young).exists();
        assertThat(old).doesNotExist();
        assertThat(partial).doesNotExist();
        assertThat(freshPartial).exists();
    }

    @Test
    @DisplayName("EE-17: the sweep deletes a temporary staging directory a dead stager left, and keeps a recent one")
    void sweepRemovesAbandonedTemporaryDirectories() throws Exception {
        final Instant now = Instant.parse("2026-09-28T12:00:00Z");
        final Path report = workspace.resolve(".aimon-staged/report");
        final Path newest = stagedCopy(report, "00000000000e0e0e", now.minus(Duration.ofHours(1)));
        final String suffix = LocalStaging.TEMPORARY_INFIX + "0123456789abcdef0123456789abcdef";
        // Complete, marker and all, and never renamed: its stager died between the copy and the rename.
        final Path abandoned = stagedCopy(report, "00000000000e0e0e" + suffix, now.minus(Duration.ofHours(48)));
        Files.setLastModifiedTime(abandoned, FileTime.from(now.minus(Duration.ofHours(48))));
        final Path inProgress = report.resolve("00000000000a0a0a" + suffix);
        Files.createDirectories(inProgress);
        Files.writeString(inProgress.resolve("SKILL.md"), "x");
        Files.setLastModifiedTime(inProgress, FileTime.from(now.minus(Duration.ofMinutes(5))));

        ownedEnv(LocalExecutionEnvironmentProvider.builder().clock(Clock.fixed(now, ZoneOffset.UTC))
                .stagingSweepGrace(Duration.ofHours(24)));

        assertThat(abandoned).doesNotExist();
        assertThat(inProgress).exists();
        assertThat(newest).as("a temporary directory's marker never makes it the newest copy").exists();
    }

    @Test
    @DisplayName("a background ceiling longer than the sweep grace keeps copies a running command may still read")
    void sweepGraceFollowsALongerBackgroundCeiling() throws Exception {
        final Instant now = Instant.parse("2026-09-28T12:00:00Z");
        final Path report = workspace.resolve(".aimon-staged/report");
        stagedCopy(report, "00000000000e0e0e", now.minus(Duration.ofHours(1)));
        final Path withinCeiling = stagedCopy(report, "00000000000d0d0d", now.minus(Duration.ofHours(48)));
        final Path beyondCeiling = stagedCopy(report, "00000000000c0c0c", now.minus(Duration.ofHours(80)));

        // A command started 48 hours ago may still be running under a 72-hour ceiling, from the copy it was staged
        // with. The default 24-hour grace would delete that copy from under it.
        ownedEnv(LocalExecutionEnvironmentProvider.builder().clock(Clock.fixed(now, ZoneOffset.UTC))
                .stagingSweepGrace(Duration.ofHours(24)).backgroundCommandTimeout(Duration.ofHours(72)));

        assertThat(withinCeiling).exists();
        assertThat(beyondCeiling).doesNotExist();
    }

    @Test
    @DisplayName("the sweep does nothing when the staging directory is a symbolic link, even into the workspace")
    void sweepSkipsSymlinkedStagingRoot() throws Exception {
        final Instant now = Instant.parse("2026-09-28T12:00:00Z");
        // A link out of the workspace: what a cloned repository could commit as .aimon-staged -> $HOME.
        final Path victim = elsewhere.resolve("Documents/old-project");
        Files.createDirectories(victim.resolve("src"));
        Files.writeString(victim.resolve("src/Main.java"), "class Main {}");
        Files.setLastModifiedTime(victim, FileTime.from(now.minus(Duration.ofDays(400))));
        Files.createSymbolicLink(workspace.resolve(".aimon-staged"), elsewhere);

        ownedEnv(LocalExecutionEnvironmentProvider.builder().clock(Clock.fixed(now, ZoneOffset.UTC))
                .stagingSweepGrace(Duration.ofHours(24)));

        assertThat(victim.resolve("src/Main.java")).hasContent("class Main {}");

        // A link that stays inside the workspace is skipped too: the staging directory must be a real directory.
        final Path inside = workspace.resolve("keep/report");
        final Path old = stagedCopy(inside, "00000000000d0d0d", now.minus(Duration.ofHours(48)));
        stagedCopy(inside, "00000000000e0e0e", now.minus(Duration.ofHours(1)));
        Files.createSymbolicLink(workspace.resolve("staged-link"), workspace.resolve("keep"));
        LocalExecutionEnvironmentProvider.sweepStaging(workspace, workspace.resolve("staged-link"),
                Duration.ofHours(24), Clock.fixed(now, ZoneOffset.UTC));
        assertThat(old).exists();
    }

    @Test
    @DisplayName("the sweep never descends into a symbolic link planted as a name or copy directory")
    void sweepSkipsSymlinkedNameAndCopyDirectories() throws Exception {
        final Instant now = Instant.parse("2026-09-28T12:00:00Z");
        final Path victim = elsewhere.resolve("work/old-project");
        Files.createDirectories(victim);
        Files.writeString(victim.resolve("notes.md"), "mine");
        Files.setLastModifiedTime(victim, FileTime.from(now.minus(Duration.ofDays(400))));
        final Path staging = workspace.resolve(".aimon-staged");
        Files.createDirectories(staging.resolve("report"));
        // A name directory that is a link, and a copy directory that is a link, both older than the grace.
        Files.createSymbolicLink(staging.resolve("work"), elsewhere.resolve("work"));
        Files.createSymbolicLink(staging.resolve("report/00000000000c0c0c"), victim);
        final Path real = stagedCopy(staging.resolve("report"), "00000000000e0e0e", now.minus(Duration.ofHours(1)));

        ownedEnv(LocalExecutionEnvironmentProvider.builder().clock(Clock.fixed(now, ZoneOffset.UTC))
                .stagingSweepGrace(Duration.ofHours(24)));

        assertThat(victim.resolve("notes.md")).hasContent("mine");
        assertThat(real).exists();
    }

    @Test
    @DisplayName("the sweep leaves a directory whose name is not a content key, however old")
    void sweepKeepsNonKeyDirectories() throws Exception {
        final Instant now = Instant.parse("2026-09-28T12:00:00Z");
        final Path report = workspace.resolve(".aimon-staged/report");
        final Path old = stagedCopy(report, "00000000000d0d0d", now.minus(Duration.ofHours(48)));
        stagedCopy(report, "00000000000e0e0e", now.minus(Duration.ofHours(1)));
        final Path notAKey = report.resolve("main");
        Files.createDirectories(notAKey);
        Files.writeString(notAKey.resolve("keep.txt"), "mine");
        Files.setLastModifiedTime(notAKey, FileTime.from(now.minus(Duration.ofDays(400))));

        ownedEnv(LocalExecutionEnvironmentProvider.builder().clock(Clock.fixed(now, ZoneOffset.UTC))
                .stagingSweepGrace(Duration.ofHours(24)));

        assertThat(old).doesNotExist();
        assertThat(notAKey.resolve("keep.txt")).hasContent("mine");
    }

    @Test
    @DisplayName("a staging root over user directories never sweeps one whose name is short hex, like logs/2024/01")
    void sweepKeepsShortHexDirectories() throws Exception {
        final Instant now = Instant.parse("2026-09-28T12:00:00Z");
        final List<Path> logs = new ArrayList<>();
        for (String month : List.of("01", "12")) {
            final Path dir = Files.createDirectories(workspace.resolve("logs/2024/" + month));
            Files.writeString(dir.resolve("app.log"), "log " + month);
            Files.setLastModifiedTime(dir, FileTime.from(now.minus(Duration.ofDays(400))));
            logs.add(dir.resolve("app.log"));
        }
        final Path cafe = Files.createDirectories(workspace.resolve("logs/beef/cafe"));
        Files.setLastModifiedTime(cafe, FileTime.from(now.minus(Duration.ofDays(400))));

        ownedEnv(LocalExecutionEnvironmentProvider.builder().stagingRoot("logs").clock(Clock.fixed(now, ZoneOffset.UTC))
                .stagingSweepGrace(Duration.ofHours(24)));

        assertThat(logs.get(0)).hasContent("log 01");
        assertThat(logs.get(1)).hasContent("log 12");
        assertThat(cafe).exists();
    }

    @Test
    @DisplayName("the sweep never runs over the workspace root itself")
    void sweepSkipsWorkspaceRoot() throws Exception {
        final Instant now = Instant.parse("2026-09-28T12:00:00Z");
        final Path old = stagedCopy(workspace.resolve("src"), "00000000000d0d0d", now.minus(Duration.ofHours(48)));
        stagedCopy(workspace.resolve("src"), "00000000000e0e0e", now.minus(Duration.ofHours(1)));

        LocalExecutionEnvironmentProvider.sweepStaging(workspace, workspace.resolve("."), Duration.ofHours(24),
                Clock.fixed(now, ZoneOffset.UTC));
        LocalExecutionEnvironmentProvider.sweepStaging(workspace, workspace, Duration.ofHours(24),
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(old).exists();
    }

    @Test
    @DisplayName("a staging root that is not one directory name, or is the control store, is refused at build()")
    void invalidStagingRootRefused() {
        for (String root : List.of("", ".", "..", "src/main", "/tmp/x", "a\\b", "C:", ".aimon", ".AIMON")) {
            assertThatThrownBy(() -> LocalExecutionEnvironmentProvider.builder().workspaceRoot(workspace)
                    .stagingRoot(root).pathRules(List.of()).contentSearch(false).build()).as(root)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stagingRoot");
        }
        // One ordinary name is fine, with the default rules or explicit ones.
        final LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).stagingRoot("staged").pathRules(List.of()).contentSearch(false).build();
        closeables.add(provider);
    }

    @Test
    @DisplayName("a build that fails after creating its filesystem and shell closes both")
    void failedBuildClosesOwnedResources() throws Exception {
        final List<String> closed = new ArrayList<>();
        final Clock broken = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new IllegalStateException("clock broken");
            }
        };
        final int[] counter = {0};
        final LocalExecutionEnvironmentProvider.Builder builder = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).clock(broken).contentSearch(false).ownedResourceDecorator(closer -> {
                    final String label = "resource-" + counter[0]++;
                    return () -> {
                        closed.add(label);
                        closer.close();
                    };
                });
        // The sweep reads the clock only when there is a staging directory to sweep.
        Files.createDirectories(workspace.resolve(".aimon-staged"));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class).hasMessage("clock broken");
        assertThat(closed).containsExactly("resource-0", "resource-1");
    }

    @Test
    @DisplayName("a recorded file path that could leave the copy is refused before anything is written")
    void unconfinedRelPathRefused() {
        final ExecutionEnvironment env = ownedEnv();
        for (String relPath : List.of("../../.aimon/x", "/etc/passwd", "a/../../b", "./a", "a//b", "a\\b", "")) {
            final StagedResource resource = StagedResource.builder().sourceFileSystem(control).sourceDir("skills/demo")
                    .name("demo").contentKey("0123456789abcdef").files(List.of("SKILL.md", relPath)).build();
            assertThatThrownBy(() -> env.stage(resource)).as(relPath).isInstanceOf(StagingException.class)
                    .hasMessageContaining("not a relative path inside the resource");
        }
        assertThat(workspace.resolve(".aimon-staged")).doesNotExist();
    }

    @Test
    @DisplayName("a content key that is not lowercase hex is refused before anything is written or deleted")
    void nonHexContentKeyRefused() throws Exception {
        final Path control = workspace.resolve(".aimon/state.json");
        Files.createDirectories(control.getParent());
        Files.writeString(control, "{}");
        final ExecutionEnvironment env = ownedEnv();
        for (String key : List.of("../../.aimon", "..", "", "ABCDEF", "k1/x", "abcdef", "0123456789abcdef0")) {
            final StagedResource resource = StagedResource.builder().sourceFileSystem(this.control)
                    .sourceDir("skills/demo").name("demo").contentKey(key).files(List.of("SKILL.md")).build();
            assertThatThrownBy(() -> env.stage(resource)).as(key).isInstanceOf(StagingException.class)
                    .hasMessageContaining("content key");
        }
        assertThat(control).hasContent("{}");
        assertThat(workspace.resolve(".aimon-staged")).doesNotExist();
    }

    @Test
    @DisplayName("providers over a shared, borrowed filesystem never sweep a copy another runtime may use")
    void sharedFileSystemNotSwept() throws Exception {
        final LocalFileSystem shared = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        shared.initialize();
        closeables.add(shared::close);
        final String first = borrowedEnv(shared).stage(scan());
        control.write("skills/demo/scripts/run.sh", "echo v2");
        final String second = borrowedEnv(shared).stage(scan());
        Files.setLastModifiedTime(Path.of(first, LocalStaging.MARKER), FileTime.from(Instant.EPOCH));

        final ExecutionEnvironment third = borrowedEnv(shared);

        assertThat(Path.of(first, "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(second).isNotEqualTo(first);
        try (InputStream in = third.fileSystem().read(first + "/scripts/run.sh")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("echo staged-script");
        }
    }

    @Test
    @DisplayName("an interrupted copy leaves no marker, and the next call copies again")
    void interruptedCopy() {
        final AtomicBoolean failing = new AtomicBoolean(false);
        final VirtualFileSystem flaky = new DelegatingFileSystem(control) {
            @Override
            public InputStream read(String path) {
                if (failing.get() && path.endsWith("scripts/run.sh")) {
                    throw new IllegalStateException("disk hiccup");
                }
                return super.read(path);
            }
        };
        final StagedResource resource = StagedResource.scan(flaky, "skills/demo", "demo");
        final ExecutionEnvironment env = ownedEnv();
        final Path target = workspace.resolve(".aimon-staged/demo/" + resource.getContentKey());

        failing.set(true);
        assertThatThrownBy(() -> env.stage(resource)).isInstanceOf(StagingException.class)
                .hasMessageContaining("scripts/run.sh");
        assertThat(target.resolve(LocalStaging.MARKER)).doesNotExist();

        failing.set(false);
        env.stage(resource);
        assertThat(target.resolve(LocalStaging.MARKER)).exists();
        assertThat(target.resolve("scripts/run.sh")).hasContent("echo staged-script");
    }

    @Test
    @DisplayName("exactly the scanned files are copied; a file added after the scan is not")
    void copiesExactlyScannedFiles() {
        final StagedResource resource = scan();
        control.write("skills/demo/late.txt", "late");
        final String path = ownedEnv().stage(resource);

        assertThat(Path.of(path, "late.txt")).doesNotExist();
        assertThat(Path.of(path, LocalStaging.MARKER)).exists();
    }

    // ---- EE-3: a source that changed between the scan (the registry's load) and the first copy --------------------

    @Test
    @DisplayName("EE-3: a source edited after the scan is staged under the key its bytes have now, never under the"
            + " loaded one")
    void editedAfterScanIsStagedUnderItsOwnKey() {
        final StagedResource loaded = scan();
        control.write("skills/demo/scripts/run.sh", "echo edited");
        final ExecutionEnvironment env = ownedEnv();

        final String path = env.stage(loaded);

        // The key a registry reload would compute: a restart finds this copy and reuses it.
        final String current = scan().getContentKey();
        assertThat(current).isNotEqualTo(loaded.getContentKey());
        assertThat(path).isEqualTo(root() + "/.aimon-staged/demo/" + current);
        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo edited");
        assertThat(Path.of(path, LocalStaging.MARKER)).hasContent(current);
        // New bytes are never stored under the old key, and nothing is left beside the copy.
        assertThat(copies("demo")).containsExactly(current);
        assertThat(env.stage(scan())).as("the reloaded resource names the same copy").isEqualTo(path);
    }

    @Test
    @DisplayName("EE-3: the change is reported once, by name, at WARN — and later calls copy and read nothing")
    void editedAfterScanWarnsOnceAndIsRemembered() {
        final List<String> sourceReads = new ArrayList<>();
        final VirtualFileSystem countingSource = new DelegatingFileSystem(control) {
            @Override
            public InputStream read(String path) {
                sourceReads.add(path);
                return super.read(path);
            }
        };
        final StagedResource loaded = StagedResource.scan(countingSource, "skills/demo", "demo");
        control.write("skills/demo/scripts/run.sh", "echo edited");
        final ExecutionEnvironment env = ownedEnv();
        final Logger logger = (Logger) LoggerFactory.getLogger(LocalStaging.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            final String first = env.stage(loaded);
            sourceReads.clear();
            assertThat(env.stage(loaded)).isEqualTo(first);
            assertThat(env.stage(loaded)).isEqualTo(first);

            assertThat(sourceReads).as("a remembered re-keyed copy costs what any staged copy costs").isEmpty();
            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage).singleElement().asString().contains("'demo'")
                    .contains("changed on disk");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("EE-3: files added and removed since the scan follow the edit — the copy is the directory as it is"
            + " now, not the loaded file list with new bytes")
    void editedAfterScanTakesTheCurrentFileSet() {
        final StagedResource loaded = scan();
        control.write("skills/demo/scripts/run.sh", "echo edited; . helper.sh");
        control.write("skills/demo/scripts/helper.sh", "echo helper");
        control.delete("skills/demo/references/notes.md");

        final String path = ownedEnv().stage(loaded);

        assertThat(path).endsWith("/" + scan().getContentKey());
        assertThat(Path.of(path, "scripts/helper.sh")).hasContent("echo helper");
        assertThat(Path.of(path, "references/notes.md")).doesNotExist();
    }

    @Test
    @DisplayName("EE-3: a skill whose recorded files are unchanged still stages to its loaded key, also when a file"
            + " was added beside them")
    void unchangedSkillStillStagesToItsLoadedKey() {
        final StagedResource loaded = scan();
        control.write("skills/demo/late.txt", "late");

        final String path = ownedEnv().stage(loaded);

        assertThat(path).isEqualTo(root() + "/.aimon-staged/demo/" + loaded.getContentKey());
        assertThat(Path.of(path, LocalStaging.MARKER)).hasContent(loaded.getContentKey());
        assertThat(copies("demo")).containsExactly(loaded.getContentKey());
    }

    @Test
    @DisplayName("EE-3: an edit after the first copy is not seen — the copy under the loaded key is served, in this"
            + " process and in the next one that loaded the same version")
    void editedAfterTheFirstCopyKeepsTheLoadedCopy() {
        final StagedResource loaded = scan();
        final String first = ownedEnv().stage(loaded);
        control.write("skills/demo/scripts/run.sh", "echo edited");

        assertThat(ownedEnv().stage(loaded)).isEqualTo(first);
        assertThat(borrowedEnv(rawWorkspace()).stage(loaded)).isEqualTo(first);
        assertThat(Path.of(first, "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(copies("demo")).containsExactly(loaded.getContentKey());
    }

    @Test
    @DisplayName("EE-3: reverting the edit does not resurrect the edited bytes under the loaded key")
    void revertedSourceStagesTheLoadedBytesUnderTheLoadedKey() {
        final StagedResource loaded = scan();
        control.write("skills/demo/scripts/run.sh", "echo edited");
        final String edited = ownedEnv().stage(loaded);
        control.write("skills/demo/scripts/run.sh", "echo staged-script");

        // A process that never saw the edit.
        final String reverted = borrowedEnv(rawWorkspace()).stage(loaded);

        assertThat(reverted).isEqualTo(root() + "/.aimon-staged/demo/" + loaded.getContentKey());
        assertThat(Path.of(reverted, "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(Path.of(edited, "scripts/run.sh")).hasContent("echo edited");
    }

    @Test
    @DisplayName("EE-3: the size limit applies to what is on disk now, and a refused copy leaves nothing behind")
    void editedAfterScanOverTheLimitIsRefused() {
        final StagedResource loaded = scan();
        final ExecutionEnvironment env = ownedEnv(
                LocalExecutionEnvironmentProvider.builder().maxStagedBytes(loaded.getTotalBytes() + 4));
        control.write("skills/demo/scripts/run.sh", "echo " + "x".repeat(200));

        assertThatThrownBy(() -> env.stage(loaded)).isInstanceOf(StagingException.class)
                .hasMessageContaining("staging limit");

        assertThat(copies("demo")).isEmpty();
        // Back under the limit: the next call stages.
        control.write("skills/demo/scripts/run.sh", "echo ok-script-now!!");
        assertThat(Path.of(env.stage(loaded), "scripts/run.sh")).hasContent("echo ok-script-now!!");
    }

    @Test
    @DisplayName("EE-3: a source still changing while it is copied is refused, with nothing left, and stages once it"
            + " holds still")
    void sourceChangingDuringTheCopyIsRefusedThenStaged() {
        final AtomicInteger edits = new AtomicInteger();
        final AtomicBoolean moving = new AtomicBoolean(false);
        // Every read of the script finds another version: no two passes over the directory agree.
        final VirtualFileSystem restless = new DelegatingFileSystem(control) {
            @Override
            public InputStream read(String path) {
                if (moving.get() && path.endsWith("scripts/run.sh")) {
                    control.write("skills/demo/scripts/run.sh", "echo v" + edits.incrementAndGet());
                }
                return super.read(path);
            }
        };
        final StagedResource loaded = StagedResource.scan(restless, "skills/demo", "demo");
        final ExecutionEnvironment env = ownedEnv();

        moving.set(true);
        assertThatThrownBy(() -> env.stage(loaded)).isInstanceOf(StagingException.class).hasMessageContaining("demo")
                .hasMessageContaining("changing");
        assertThat(copies("demo")).isEmpty();

        moving.set(false);
        final String path = env.stage(loaded);
        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo v" + edits.get());
        assertThat(copies("demo")).containsExactly(scan().getContentKey());
    }

    @Test
    @DisplayName("EE-3: a recorded file that is gone is a change too, and the copy is made without it")
    void removedFileIsAChange() {
        final StagedResource loaded = scan();
        control.delete("skills/demo/references/notes.md");

        final String path = ownedEnv().stage(loaded);

        assertThat(path).endsWith("/" + scan().getContentKey());
        assertThat(Path.of(path, "references/notes.md")).doesNotExist();
        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(copies("demo")).containsExactly(scan().getContentKey());
    }

    // ---- a resource that was not scanned: its file list is the caller's, and no rescan may widen it ---------------

    @Test
    @DisplayName("a hand-built resource whose listed file changed is refused, and a file it never listed is not"
            + " written anywhere in the workspace — not even into a temporary directory")
    void handBuiltSubsetIsRefusedNotWidened() throws Exception {
        final StagedResource subset = handBuiltSubset();
        control.write("repo/tool/run.sh", "echo edited");
        final List<String> written = new ArrayList<>();
        final ExecutionEnvironment env = borrowedEnv(recordingWrites(written));

        assertThatThrownBy(() -> env.stage(subset)).isInstanceOf(StagingException.class)
                .hasMessageContaining("'tool' changed on disk after it was loaded")
                .hasMessageContaining("Restart the application");

        assertThat(written).as("every write the stager made").noneMatch(p -> p.endsWith("credentials.env"));
        assertThat(filesUnderWorkspace()).noneMatch(p -> p.endsWith("credentials.env") || p.endsWith("run.sh"));
        assertThat(copies("tool")).isEmpty();
    }

    @Test
    @DisplayName("a hand-built resource is refused the same way for an isolated branch, which stages through the"
            + " same code")
    void handBuiltSubsetIsRefusedForABranchToo() throws Exception {
        final StagedResource subset = handBuiltSubset();
        control.write("repo/tool/run.sh", "echo edited");
        final List<String> written = new ArrayList<>();
        final VirtualFileSystem fs = recordingWrites(written);
        final LocalStaging staging = new LocalStaging(fs, fs, workspace, ".aimon-staged", Long.MAX_VALUE);

        assertThatThrownBy(() -> staging.stageCopy(subset)).isInstanceOf(StagingException.class)
                .hasMessageContaining("changed on disk after it was loaded");

        assertThat(written).noneMatch(p -> p.endsWith("credentials.env"));
        assertThat(filesUnderWorkspace()).noneMatch(p -> p.endsWith("credentials.env"));
    }

    @Test
    @DisplayName("a hand-built resource that is unchanged stages exactly its list")
    void handBuiltSubsetUnchangedStagesItsList() {
        final StagedResource subset = handBuiltSubset();

        final String path = ownedEnv().stage(subset);

        assertThat(Path.of(path, "run.sh")).hasContent("echo v1");
        assertThat(Path.of(path, "credentials.env")).doesNotExist();
    }

    @Test
    @DisplayName("a hand-built resource whose listed file is gone reports the read that failed; the rest of the"
            + " directory is not staged in its place")
    void handBuiltSubsetWithAMissingFileReportsTheRead() throws Exception {
        final StagedResource subset = handBuiltSubset();
        control.delete("repo/tool/run.sh");

        assertThatThrownBy(() -> ownedEnv().stage(subset)).isInstanceOf(StagingException.class)
                .hasMessageContaining("run.sh could not be read");

        assertThat(filesUnderWorkspace()).noneMatch(p -> p.endsWith("credentials.env"));
        assertThat(copies("tool")).isEmpty();
    }

    /** {@code repo/tool} holds a script and a secret; the resource lists the script only, as SPI code may. */
    private StagedResource handBuiltSubset() {
        control.write("repo/tool/run.sh", "echo v1");
        control.write("repo/tool/credentials.env", "TOKEN=secret");
        final byte[] script = "echo v1".getBytes(StandardCharsets.UTF_8);
        return StagedResource.builder().sourceFileSystem(control).sourceDir("repo/tool").name("tool")
                .contentKey(new StagedResource.ContentKeyBuilder().add("run.sh", script).build())
                .totalBytes(script.length).files(List.of("run.sh")).build();
    }

    /** The workspace, with the path of every write made through it recorded. */
    private VirtualFileSystem recordingWrites(List<String> written) {
        return new DelegatingFileSystem(rawWorkspace()) {
            @Override
            public void write(String path, InputStream content, long contentLength) {
                written.add(path);
                super.write(path, content, contentLength);
            }
        };
    }

    private List<String> filesUnderWorkspace() throws Exception {
        try (Stream<Path> walk = Files.walk(workspace)) {
            return walk.filter(Files::isRegularFile).map(p -> workspace.relativize(p).toString()).sorted().toList();
        }
    }

    /** The directories beside (and including) the copies of a resource name. */
    private List<String> copies(String name) {
        final Path nameDir = workspace.resolve(".aimon-staged").resolve(name);
        if (!Files.isDirectory(nameDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(nameDir)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("EE-4: the first copy writes .aimon-staged/.gitignore ignoring everything, so copies stay out of git")
    void stagingAreaIgnoresItself() throws Exception {
        final ExecutionEnvironment env = ownedEnv();
        env.stage(scan());

        final Path gitignore = workspace.resolve(".aimon-staged/" + LocalStaging.GITIGNORE);
        assertThat(gitignore).hasContent("*");
        assertThat(env.fileSystem().exists(".aimon-staged/.gitignore")).as("visible, read-only").isTrue();
    }

    @Test
    @DisplayName("EE-4: a .gitignore the user already keeps in the staging area is left as it is")
    void existingGitignoreKept() throws Exception {
        final Path gitignore = workspace.resolve(".aimon-staged/" + LocalStaging.GITIGNORE);
        Files.createDirectories(gitignore.getParent());
        Files.writeString(gitignore, "# mine\n*\n");
        ownedEnv().stage(scan());

        assertThat(gitignore).hasContent("# mine\n*\n");
    }

    @Test
    @DisplayName("EE-4: a resource named .gitignore cannot take the staging area's ignore file")
    void gitignoreNameRefused() {
        final StagedResource resource = StagedResource.scan(control, "skills/demo", ".gitignore");
        assertThatThrownBy(() -> ownedEnv().stage(resource)).isInstanceOf(StagingException.class);
    }

    @Test
    @DisplayName("EE-37: a planted copy whose marker names the key but whose files do not hash to it is staged again")
    void plantedCopyWithMatchingMarkerIsReplaced() throws Exception {
        final StagedResource resource = scan();
        final Path target = workspace.resolve(".aimon-staged/demo/" + resource.getContentKey());
        // what a cloned repository could carry: the right path, the right marker, other bytes
        Files.createDirectories(target.resolve("scripts"));
        Files.writeString(target.resolve("SKILL.md"), "# demo");
        Files.writeString(target.resolve("scripts/run.sh"), "echo PLANTED");
        Files.writeString(target.resolve("extra.sh"), "echo extra");
        Files.writeString(target.resolve(LocalStaging.MARKER), resource.getContentKey());

        final String path = ownedEnv().stage(resource);

        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(Path.of(path, "references/notes.md")).hasContent("notes");
        assertThat(Path.of(path, "extra.sh")).as("a file the resource does not have").doesNotExist();
        assertThat(Path.of(path, LocalStaging.MARKER)).hasContent(resource.getContentKey());
    }

    @Test
    @DisplayName("EE-37: a file planted beside an intact copy is removed, and the copy itself is kept, not re-made")
    void intactCopyWithAnExtraFileLosesTheExtraOnly() throws Exception {
        final StagedResource resource = scan();
        final String first = ownedEnv().stage(resource);
        Files.createDirectories(Path.of(first, "scripts/__pycache__"));
        Files.writeString(Path.of(first, "scripts/__pycache__/run.pyc"), "bytecode");
        final Path kept = Path.of(first, "scripts/run.sh");
        final FileTime before = Files.getLastModifiedTime(kept);
        Thread.sleep(20);

        final ExecutionEnvironment restarted = borrowedEnv(rawWorkspace());
        assertThat(restarted.stage(resource)).isEqualTo(first);
        assertThat(Path.of(first, "scripts/__pycache__/run.pyc")).doesNotExist();
        // Not re-copied: a re-copy would delete the tree under whatever is still running from it.
        assertThat(Files.getLastModifiedTime(kept)).isEqualTo(before);
    }

    @Test
    @DisplayName("EE-4: a copy staged before the ignore file existed gets it on the reuse path too")
    void reusedCopyGetsTheGitignore() throws Exception {
        final StagedResource resource = scan();
        ownedEnv().stage(resource);
        Files.delete(workspace.resolve(".aimon-staged/" + LocalStaging.GITIGNORE));

        borrowedEnv(rawWorkspace()).stage(resource);

        assertThat(workspace.resolve(".aimon-staged/" + LocalStaging.GITIGNORE)).hasContent("*");
    }

    @Test
    @DisplayName("EE-37: a marker that does not hold the key is not taken as \"already staged\"")
    void markerWithoutTheKeyIsNotTrusted() throws Exception {
        final StagedResource resource = scan();
        final Path target = workspace.resolve(".aimon-staged/demo/" + resource.getContentKey());
        Files.createDirectories(target.resolve("scripts"));
        Files.writeString(target.resolve("scripts/run.sh"), "echo PLANTED");
        Files.writeString(target.resolve(LocalStaging.MARKER), "");

        final String path = ownedEnv().stage(resource);

        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo staged-script");
    }

    @Test
    @DisplayName("EE-37: an intact copy left by an earlier process is reused without copying, and verified only once")
    void intactCopyReusedAndVerifiedOnce() throws Exception {
        final StagedResource resource = scan();
        final String first = ownedEnv().stage(resource);

        final List<String> writes = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
        final VirtualFileSystem counting = new DelegatingFileSystem(rawWorkspace()) {
            @Override
            public void write(String path, InputStream content, long contentLength) {
                writes.add(path);
                super.write(path, content, contentLength);
            }

            @Override
            public InputStream read(String path) {
                reads.add(path);
                return super.read(path);
            }
        };
        final ExecutionEnvironment restarted = borrowedEnv(counting);

        assertThat(restarted.stage(resource)).isEqualTo(first);
        assertThat(writes).isEmpty();
        assertThat(reads).as("the copy is checked against its key").isNotEmpty();

        reads.clear();
        assertThat(restarted.stage(resource)).isEqualTo(first);
        assertThat(reads).as("once per environment").isEmpty();
        assertThat(writes).isEmpty();
    }

    private LocalFileSystem rawWorkspace() {
        final LocalFileSystem shared = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        shared.initialize();
        closeables.add(shared::close);
        return shared;
    }

    private static Path stagedCopy(Path nameDir, String key, Instant markerTime) throws Exception {
        final Path copy = nameDir.resolve(key);
        Files.createDirectories(copy);
        Files.writeString(copy.resolve("SKILL.md"), key);
        final Path marker = copy.resolve(LocalStaging.MARKER);
        Files.writeString(marker, key);
        Files.setLastModifiedTime(marker, FileTime.from(markerTime));
        return copy;
    }

    private static void deleteTree(Path dir) throws Exception {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
