package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
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
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

/**
 * Staging against another stager of the same workspace (EE-17) and against a workspace that folds file names (EE-38).
 *
 * <p>
 * "Another process" is a second provider over the same directory: it has its own {@link LocalStaging}, so the two
 * share the disk and nothing else — no lock, no memory of what is staged. The interleavings are driven from a hook on
 * the source filesystem, not by timing: the hook runs while one stager is in the middle of its copy.
 */
@DisplayName("LocalStaging across stagers and file-name folding (EE-17, EE-38)")
class LocalStagingTest {

    @TempDir
    Path workspace;

    @TempDir
    Path controlDir;

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

    // ---- EE-17 ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("EE-17: while a copy is in progress its target is not there yet — never a directory with half the"
            + " files")
    void targetIsNeverVisibleHalfCopied() {
        final HookedSource source = new HookedSource(control);
        final StagedResource resource = StagedResource.scan(source, "skills/demo", "demo");
        final Path target = target(resource);
        final List<String> seen = new ArrayList<>();
        // run.sh is the last file read: the two before it are already written when the hook runs.
        source.beforeRead("scripts/run.sh", () -> seen.add(describe(target)));

        process().stage(resource);

        assertThat(seen).containsExactly("absent");
        assertThat(describe(target)).isEqualTo("complete");
    }

    @Test
    @DisplayName("EE-17: a stager that fails does not delete the copy another process completed in the meantime")
    void failingStagerLeavesTheOtherProcessCopy() {
        final HookedSource source = new HookedSource(control);
        final StagedResource resource = StagedResource.scan(source, "skills/demo", "demo");
        final StagedResource sameResource = StagedResource.scan(control, "skills/demo", "demo");
        final ExecutionEnvironment loser = process();
        final ExecutionEnvironment winner = process();
        final AtomicReference<String> winnerPath = new AtomicReference<>();
        source.beforeRead("scripts/run.sh", () -> {
            winnerPath.set(winner.stage(sameResource));
            throw new IllegalStateException("disk hiccup");
        });

        assertThatThrownBy(() -> loser.stage(resource)).isInstanceOf(StagingException.class)
                .hasMessageContaining("scripts/run.sh");

        // The winner was handed this path, and its model is about to run a script from it.
        assertThat(describe(Path.of(winnerPath.get()))).isEqualTo("complete");
        assertThat(Path.of(winnerPath.get(), "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(siblings(resource)).as("nothing left beside the copy").containsExactly(resource.getContentKey());
    }

    @Test
    @DisplayName("EE-17: the loser of a race ends up with the winner's copy, untouched, and leaves nothing behind")
    void loserAdoptsTheWinnersCopy() throws Exception {
        final HookedSource source = new HookedSource(control);
        final StagedResource resource = StagedResource.scan(source, "skills/demo", "demo");
        final StagedResource sameResource = StagedResource.scan(control, "skills/demo", "demo");
        final ExecutionEnvironment loser = process();
        final ExecutionEnvironment winner = process();
        final AtomicReference<String> winnerPath = new AtomicReference<>();
        final Map<String, FileTime> written = new LinkedHashMap<>();
        source.beforeRead("scripts/run.sh", () -> {
            winnerPath.set(winner.stage(sameResource));
            written.putAll(modifiedTimes(Path.of(winnerPath.get())));
            pause();
        });

        final String loserPath = loser.stage(resource);

        assertThat(loserPath).isEqualTo(winnerPath.get());
        assertThat(written).hasSize(resource.getFiles().size() + 1);
        // Not one file rewritten: the winner's model may already be reading them.
        assertThat(modifiedTimes(Path.of(loserPath))).isEqualTo(written);
        assertThat(siblings(resource)).as("the loser's own copy is gone").containsExactly(resource.getContentKey());
    }

    @Test
    @DisplayName("EE-17: a half-copied directory an interrupted stager left behind is replaced whole")
    void interruptedTargetIsReplaced() throws Exception {
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        final Path target = target(resource);
        Files.createDirectories(target.resolve("scripts"));
        Files.writeString(target.resolve("SKILL.md"), "# de");
        Files.writeString(target.resolve("scripts/stale.sh"), "echo stale");

        final String path = process().stage(resource);

        assertThat(describe(Path.of(path))).isEqualTo("complete");
        assertThat(Path.of(path, "SKILL.md")).hasContent("# demo");
        assertThat(Path.of(path, "scripts/stale.sh")).doesNotExist();
        assertThat(siblings(resource)).containsExactly(resource.getContentKey());
    }

    @Test
    @DisplayName("EE-17: a complete copy that lands between 'is it staged?' and 'move it aside' is put back, not"
            + " replaced")
    void aValidCopyMovedAsideIsRestored() throws Exception {
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        final Path target = target(resource);
        // An interrupted copy is in the way, so a plain rename fails and the stager has to look at what is there.
        Files.createDirectories(target.resolve("scripts"));
        Files.writeString(target.resolve("SKILL.md"), "# de");
        final ExecutionEnvironment winner = process();
        final AtomicReference<Object> winnerDirectory = new AtomicReference<>();
        final Map<String, FileTime> written = new LinkedHashMap<>();
        final String marker = ".aimon-staged/demo/" + resource.getContentKey() + "/" + LocalStaging.MARKER;
        // The other process finishes after this stager was told "no marker" and before it acts on that answer.
        final VirtualFileSystem racing = new DelegatingFileSystem(rawWorkspace()) {
            private boolean raced;

            @Override
            public boolean exists(String path) {
                final boolean answer = super.exists(path);
                if (!raced && path.equals(marker) && ownCopyIsComplete()) {
                    raced = true;
                    winner.stage(resource);
                    winnerDirectory.set(fileKey(target));
                    written.putAll(modifiedTimes(target));
                }
                return answer;
            }

            private boolean ownCopyIsComplete() {
                return siblings(resource).stream().anyMatch(name -> LocalStaging.isTemporary(name)
                        && Files.exists(target.resolveSibling(name).resolve(LocalStaging.MARKER)));
            }
        };
        final LocalStaging loser = new LocalStaging(racing, racing, workspace, ".aimon-staged", Long.MAX_VALUE);

        final String loserPath = loser.stage(resource);

        assertThat(winnerDirectory.get()).as("the race happened").isNotNull();
        assertThat(Path.of(loserPath)).isEqualTo(target);
        // The same directory, not the same bytes under a new one: the winner's model may be running from it.
        assertThat(fileKey(target)).isEqualTo(winnerDirectory.get());
        assertThat(modifiedTimes(target)).isEqualTo(written);
        assertThat(siblings(resource)).containsExactly(resource.getContentKey());
    }

    private static Object fileKey(Path path) {
        try {
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("EE-17: a copy made beside the target is not a content key, so the sweep's newest-copy rule and a"
            + " resource's files never meet it")
    void temporaryNamesAreNotContentKeys() {
        final String temporary = "0123456789abcdef" + LocalStaging.TEMPORARY_INFIX + "0123456789abcdef0123456789abcdef";

        assertThat(LocalStaging.isTemporary(temporary)).isTrue();
        assertThat(LocalStaging.isContentKey(temporary)).isFalse();
        assertThat(LocalStaging.isTemporary("0123456789abcdef")).isFalse();
        assertThat(LocalStaging.isTemporary("main.tmp-0123456789abcdef0123456789abcdef")).isFalse();
    }

    // A filesystem that is not a host directory (S3, GridFS) has no rename: LocalStaging is told so with a null host
    // root, and moves the verified files into the target one at a time.

    @Test
    @DisplayName("EE-17, no host directory: the copy is staged file by file, marker and all, and nothing is left"
            + " beside it")
    void withoutAHostDirectoryTheCopyIsMovedFileByFile() {
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");

        final String path = remoteLike().stage(resource);

        assertThat(describe(Path.of(path))).isEqualTo("complete");
        assertThat(Path.of(path, LocalStaging.MARKER)).hasContent(resource.getContentKey());
        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(siblings(resource)).containsExactly(resource.getContentKey());
    }

    @Test
    @DisplayName("EE-17, no host directory: a stager that fails does not delete the copy another process completed")
    void withoutAHostDirectoryAFailingStagerLeavesTheOtherCopy() {
        final HookedSource source = new HookedSource(control);
        final StagedResource resource = StagedResource.scan(source, "skills/demo", "demo");
        final StagedResource sameResource = StagedResource.scan(control, "skills/demo", "demo");
        final LocalStaging loser = remoteLike();
        final LocalStaging winner = remoteLike();
        final AtomicReference<String> winnerPath = new AtomicReference<>();
        source.beforeRead("scripts/run.sh", () -> {
            winnerPath.set(winner.stage(sameResource));
            throw new IllegalStateException("disk hiccup");
        });

        assertThatThrownBy(() -> loser.stage(resource)).isInstanceOf(StagingException.class);

        assertThat(describe(Path.of(winnerPath.get()))).isEqualTo("complete");
        assertThat(siblings(resource)).containsExactly(resource.getContentKey());
    }

    @Test
    @DisplayName("EE-17, no host directory: the loser of a race rewrites nothing of the winner's copy")
    void withoutAHostDirectoryTheLoserAdoptsTheWinnersCopy() {
        final HookedSource source = new HookedSource(control);
        final StagedResource resource = StagedResource.scan(source, "skills/demo", "demo");
        final StagedResource sameResource = StagedResource.scan(control, "skills/demo", "demo");
        final LocalStaging loser = remoteLike();
        final LocalStaging winner = remoteLike();
        final Map<String, FileTime> written = new LinkedHashMap<>();
        source.beforeRead("scripts/run.sh", () -> {
            written.putAll(modifiedTimes(Path.of(winner.stage(sameResource))));
            pause();
        });

        final String loserPath = loser.stage(resource);

        assertThat(written).hasSize(resource.getFiles().size() + 1);
        assertThat(modifiedTimes(Path.of(loserPath))).isEqualTo(written);
        assertThat(siblings(resource)).containsExactly(resource.getContentKey());
    }

    @Test
    @DisplayName("EE-17, no host directory: a mislabelled copy loses its marker first, then its files are replaced and"
            + " its strays removed")
    void withoutAHostDirectoryABadCopyIsRepairedInPlace() throws Exception {
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        final Path target = target(resource);
        Files.createDirectories(target.resolve("scripts"));
        Files.writeString(target.resolve("SKILL.md"), "# demo");
        Files.writeString(target.resolve("scripts/run.sh"), "echo PLANTED");
        Files.writeString(target.resolve("scripts/stale.sh"), "echo stale");
        Files.writeString(target.resolve(LocalStaging.MARKER), resource.getContentKey());
        final List<String> order = new ArrayList<>();
        final VirtualFileSystem recording = new DelegatingFileSystem(rawWorkspace()) {
            @Override
            public void delete(String path) {
                order.add("delete " + path.substring(path.lastIndexOf('/') + 1));
                super.delete(path);
            }

            @Override
            public void move(String sourcePath, String destinationPath, boolean overwrite) {
                order.add("move " + destinationPath.substring(destinationPath.lastIndexOf('/') + 1));
                super.move(sourcePath, destinationPath, overwrite);
            }
        };

        final String path = new LocalStaging(recording, recording, null, ".aimon-staged", Long.MAX_VALUE)
                .stage(resource);

        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo staged-script");
        assertThat(Path.of(path, "scripts/stale.sh")).doesNotExist();
        assertThat(describe(Path.of(path))).isEqualTo("complete");
        assertThat(order.get(0)).as("the commit point goes before any file changes").isEqualTo("delete .staged");
        assertThat(order.get(order.size() - 1)).as("and comes back last").isEqualTo("move .staged");
        assertThat(siblings(resource)).containsExactly(resource.getContentKey());
    }

    // ---- EE-38 ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("EE-38: two files a case-insensitive workspace would merge into one are refused, naming both")
    void caseCollisionRefusedOnAFoldingWorkspace() {
        final StagedResource resource = caseSensitiveResource();
        final ExecutionEnvironment env = borrowed(new CaseFoldingFileSystem(rawWorkspace()));

        assertThatThrownBy(() -> env.stage(resource)).isInstanceOf(StagingException.class)
                .hasMessageContaining("scripts/RUN.sh").hasMessageContaining("scripts/run.sh");

        assertThat(target(resource)).as("no copy with one file standing for two").doesNotExist();
        assertThat(siblings(resource)).isEmpty();
    }

    @Test
    @DisplayName("EE-38: on whatever disk this runs, a staged copy holds each file's own bytes or is refused")
    void caseCollisionNeverStagesMergedFiles() {
        final StagedResource resource = caseSensitiveResource();
        final ExecutionEnvironment env = process();

        final String path;
        try {
            path = env.stage(resource);
        } catch (StagingException e) {
            // A case-insensitive disk (APFS, NTFS).
            assertThat(e).hasMessageContaining("scripts/RUN.sh").hasMessageContaining("scripts/run.sh");
            assertThat(target(resource)).doesNotExist();
            return;
        }
        // A case-sensitive disk keeps both, and such a skill stages as it always did.
        assertThat(Path.of(path, "scripts/RUN.sh")).hasContent("echo UPPER");
        assertThat(Path.of(path, "scripts/run.sh")).hasContent("echo lower");
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static EnvironmentRequest request() {
        return EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.of("agent:test")).build();
    }

    /** One more process over the same workspace directory: its own filesystem, provider and staging. */
    private ExecutionEnvironment process() {
        return borrowed(rawWorkspace());
    }

    private ExecutionEnvironment borrowed(VirtualFileSystem fs) {
        final LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder().fileSystem(fs)
                .contentSearch(false).build();
        closeables.add(provider);
        return provider.resolve(request());
    }

    /** A stager over the workspace that has been given no host directory, as over S3 or GridFS. */
    private LocalStaging remoteLike() {
        final LocalFileSystem fs = rawWorkspace();
        return new LocalStaging(fs, fs, null, ".aimon-staged", Long.MAX_VALUE);
    }

    private LocalFileSystem rawWorkspace() {
        final LocalFileSystem shared = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        shared.initialize();
        closeables.add(shared::close);
        return shared;
    }

    private Path target(StagedResource resource) {
        return workspace.resolve(".aimon-staged/demo/" + resource.getContentKey());
    }

    /** The entries beside (and including) the copy: anything but the content key is a leftover. */
    private List<String> siblings(StagedResource resource) {
        final Path nameDir = target(resource).getParent();
        if (!Files.isDirectory(nameDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(nameDir)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a reader that does not know about the marker finds at a staged path. */
    private static String describe(Path target) {
        if (!Files.exists(target)) {
            return "absent";
        }
        final boolean all = Files.exists(target.resolve("SKILL.md"))
                && Files.exists(target.resolve("references/notes.md")) && Files.exists(target.resolve("scripts/run.sh"))
                && Files.exists(target.resolve(LocalStaging.MARKER));
        return all ? "complete" : "partial";
    }

    private static Map<String, FileTime> modifiedTimes(Path dir) {
        final Map<String, FileTime> times = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                times.put(dir.relativize(p).toString(), Files.getLastModifiedTime(p));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return times;
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A resource as a classpath, S3 or GridFS source can hold it: two files whose names differ only by case. It is
     * built by hand because the test's own disk may not be able to hold the pair.
     */
    private StagedResource caseSensitiveResource() {
        final Map<String, String> files = new TreeMap<>();
        files.put("SKILL.md", "# demo");
        files.put("scripts/RUN.sh", "echo UPPER");
        files.put("scripts/run.sh", "echo lower");
        final VirtualFileSystem source = new DelegatingFileSystem(control) {
            @Override
            public InputStream read(String path) {
                final String content = files.get(path.substring("skills/demo/".length()));
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        long total = 0;
        for (Map.Entry<String, String> file : files.entrySet()) {
            final byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
            hasher.add(file.getKey(), bytes);
            total += bytes.length;
        }
        return StagedResource.builder().sourceFileSystem(source).sourceDir("skills/demo").name("demo")
                .contentKey(hasher.build()).totalBytes(total).files(List.copyOf(files.keySet())).build();
    }

    /** A source that runs a hook, once, just before one file is read. */
    private static final class HookedSource extends DelegatingFileSystem {
        private String suffix;
        private Runnable hook;

        HookedSource(VirtualFileSystem delegate) {
            super(delegate);
        }

        void beforeRead(String pathSuffix, Runnable action) {
            this.suffix = pathSuffix;
            this.hook = action;
        }

        @Override
        public InputStream read(String path) {
            if (hook != null && path.endsWith(suffix)) {
                final Runnable once = hook;
                hook = null;
                once.run();
            }
            return super.read(path);
        }
    }

    /**
     * A workspace that keeps one file for names differing only by case, the way APFS and NTFS do, whatever disk the
     * test runs on: every path is lower-cased on its way to the real one.
     */
    private static final class CaseFoldingFileSystem extends DelegatingFileSystem {

        CaseFoldingFileSystem(VirtualFileSystem delegate) {
            super(delegate);
        }

        private static String fold(String path) {
            return path.toLowerCase(Locale.ROOT);
        }

        @Override
        public void write(String path, InputStream content, long contentLength) {
            super.write(fold(path), content, contentLength);
        }

        @Override
        public InputStream read(String path) {
            return super.read(fold(path));
        }

        @Override
        public void delete(String path) {
            super.delete(fold(path));
        }

        @Override
        public boolean exists(String path) {
            return super.exists(fold(path));
        }

        @Override
        public boolean isDirectory(String path) {
            return super.isDirectory(fold(path));
        }

        @Override
        public FileMetadata getMetadata(String path) {
            return super.getMetadata(fold(path));
        }

        @Override
        public List<String> list(String directory) {
            return super.list(fold(directory));
        }

        @Override
        public List<String> listRecursive(String directory) {
            return super.listRecursive(fold(directory));
        }

        @Override
        public void copy(String sourcePath, String destinationPath, boolean overwrite) {
            super.copy(fold(sourcePath), fold(destinationPath), overwrite);
        }

        @Override
        public void move(String sourcePath, String destinationPath, boolean overwrite) {
            super.move(fold(sourcePath), fold(destinationPath), overwrite);
        }

        @Override
        public OutputStream openOutputStream(String path) {
            return super.openOutputStream(fold(path));
        }

        @Override
        public InputStream openInputStream(String path) {
            return super.openInputStream(fold(path));
        }

        @Override
        public void createDirectory(String path) {
            super.createDirectory(fold(path));
        }

        @Override
        public void deleteRecursive(String path) {
            super.deleteRecursive(fold(path));
        }
    }
}
