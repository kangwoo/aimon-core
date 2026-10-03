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
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    @DisplayName("a source edited after the scan is refused: no marker, no partial copy, and a message the user can"
            + " act on")
    void editedAfterScan() {
        final StagedResource resource = scan();
        control.write("skills/demo/scripts/run.sh", "echo edited");
        final ExecutionEnvironment env = ownedEnv();

        assertThatThrownBy(() -> env.stage(resource)).isInstanceOf(StagingException.class)
                .hasMessageContaining("changed").hasMessageContaining("reload");
        assertThat(workspace.resolve(".aimon-staged/demo/" + resource.getContentKey())).doesNotExist();

        // A rescan (what a registry reload does) stages under the new key.
        final String path = env.stage(scan());
        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo edited");
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
