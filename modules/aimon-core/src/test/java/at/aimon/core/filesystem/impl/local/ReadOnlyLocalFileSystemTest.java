package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.core.filesystem.exception.BackendConnectionException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

@DisplayName("ReadOnlyLocalFileSystem")
class ReadOnlyLocalFileSystemTest {

    @TempDir
    Path tempDir;

    private Path root;
    private VirtualFileSystem fs;

    @BeforeEach
    void setUp() throws Exception {
        root = tempDir.resolve("skills");
        Files.createDirectories(root.resolve("demo/scripts"));
        Files.writeString(root.resolve("demo/SKILL.md"), "# demo");
        Files.writeString(root.resolve("demo/scripts/run.sh"), "echo hi");
        Files.writeString(tempDir.resolve("outside.txt"), "nope");
        fs = VirtualFileSystems.readOnlyLocal(root);
    }

    @Test
    @DisplayName("reads files, lists them root-relative and reports metadata")
    void reads() throws Exception {
        assertThat(new String(fs.read("demo/SKILL.md").readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("# demo");
        assertThat(new String(fs.openInputStream("demo/scripts/run.sh").readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("echo hi");
        assertThat(fs.listRecursive("demo")).containsExactly("demo/SKILL.md", "demo/scripts/run.sh");
        assertThat(fs.list("demo")).containsExactly("demo/SKILL.md", "demo/scripts");
        assertThat(fs.exists("demo/scripts/run.sh")).isTrue();
        assertThat(fs.isDirectory("demo/scripts")).isTrue();
        assertThat(fs.getMetadata("demo/scripts/run.sh").getSize()).isEqualTo(7);
        assertThat(fs.getMetadata("demo/scripts/run.sh").getEtag()).isEmpty();
        assertThat(fs.getWorkingDirectory()).isEqualTo(root.toAbsolutePath().normalize().toString());
        assertThat(fs.read(root.resolve("demo/SKILL.md").toString())).isNotNull();
        assertThatThrownBy(() -> fs.read("demo/missing")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("every mutating method throws")
    void mutatorsThrow() {
        assertThatThrownBy(() -> fs.write("demo/x", new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.write("demo/x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.delete("demo/SKILL.md")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.copy("demo/SKILL.md", "demo/c", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.move("demo/SKILL.md", "demo/c", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.openOutputStream("demo/x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.createDirectory("demo/d")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.deleteRecursive("demo")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(root.resolve("demo/SKILL.md")).exists();
    }

    @Test
    @DisplayName("paths that escape the root are rejected")
    void escapesRejected() {
        assertThatThrownBy(() -> fs.read("../outside.txt")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.read(tempDir.resolve("outside.txt").toString()))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.exists("demo/../../outside.txt")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    @DisplayName("a missing root has no files and is not created")
    void missingRoot() {
        final Path missing = tempDir.resolve("no-such-dir");
        final VirtualFileSystem empty = VirtualFileSystems.readOnlyLocal(missing);
        empty.initialize();
        assertThat(empty.exists("x")).isFalse();
        assertThat(empty.listRecursive(".")).isEmpty();
        assertThat(empty.list(".")).isEmpty();
        assertThat(missing).doesNotExist();
    }

    @Test
    @DisplayName("a root that is not writable works")
    void readOnlyRoot() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assumeTrue(!"root".equals(System.getProperty("user.name")));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            final VirtualFileSystem readOnly = VirtualFileSystems.readOnlyLocal(root);
            readOnly.initialize();
            assertThat(readOnly.listRecursive(".")).contains("demo/SKILL.md");
            assertThat(readOnly.getStatus()).isNotNull();
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    @DisplayName("a linked file is read only when it resolves inside the root or an allowed link root")
    void linkedFileConfined() throws Exception {
        try {
            Files.createSymbolicLink(root.resolve("demo/outside.txt"), tempDir.resolve("outside.txt"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "symbolic links are not supported here: " + e.getMessage());
        }
        assertThatThrownBy(() -> fs.read("demo/outside.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("outside the root");
        assertThatThrownBy(() -> fs.listRecursive("demo")).isInstanceOf(InvalidPathException.class);

        final VirtualFileSystem allowed = VirtualFileSystems.readOnlyLocal(root, List.of(tempDir));
        assertThat(new String(allowed.read("demo/outside.txt").readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("nope");
        assertThat(allowed.listRecursive("demo")).contains("demo/outside.txt");
    }

    @Test
    @DisplayName("exists refuses a link that resolves outside the root instead of answering for its target (EE-36)")
    void existsConfined() throws Exception {
        link(root.resolve("demo/outside.txt"), tempDir.resolve("outside.txt"));

        assertThatThrownBy(() -> fs.exists("demo/outside.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("outside the root");
        assertThat(VirtualFileSystems.readOnlyLocal(root, List.of(tempDir)).exists("demo/outside.txt")).isTrue();
    }

    @Test
    @DisplayName("exists answers the same for an outside link whether or not its target is there (EE-36)")
    void existsIsNoOracleForAMissingOutsideTarget() throws Exception {
        link(root.resolve("demo/gone.txt"), tempDir.resolve("no-such-file.txt"));
        link(root.resolve("demo/elsewhere"), tempDir);

        assertThatThrownBy(() -> fs.exists("demo/gone.txt")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.exists("demo/elsewhere/no-such-file.txt")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.read("demo/gone.txt")).isInstanceOf(InvalidPathException.class);
        // Inside the root, a missing path and a dangling link are simply absent.
        link(root.resolve("demo/dangling"), root.resolve("demo/no-such-file"));
        assertThat(fs.exists("demo/dangling")).isFalse();
        assertThat(fs.exists("demo/no-such-file")).isFalse();
        assertThatThrownBy(() -> fs.read("demo/dangling")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("isDirectory refuses a linked directory that resolves outside the root (EE-36)")
    void isDirectoryConfined() throws Exception {
        link(root.resolve("demo/elsewhere"), tempDir);

        assertThatThrownBy(() -> fs.isDirectory("demo/elsewhere")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("outside the root");
        assertThat(VirtualFileSystems.readOnlyLocal(root, List.of(tempDir)).isDirectory("demo/elsewhere")).isTrue();
    }

    @Test
    @DisplayName("getMetadata does not report the size of a file outside the root (EE-36)")
    void getMetadataConfined() throws Exception {
        link(root.resolve("demo/outside.txt"), tempDir.resolve("outside.txt"));

        assertThatThrownBy(() -> fs.getMetadata("demo/outside.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("outside the root");
        final var metadata = VirtualFileSystems.readOnlyLocal(root, List.of(tempDir)).getMetadata("demo/outside.txt");
        assertThat(metadata.getSize()).isEqualTo(4);
        assertThat(metadata.getPath()).isEqualTo("demo/outside.txt");
        assertThatThrownBy(() -> fs.getMetadata("demo/missing")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("list does not list a directory outside the root through a link (EE-36)")
    void listConfined() throws Exception {
        link(root.resolve("demo/elsewhere"), tempDir);

        assertThatThrownBy(() -> fs.list("demo/elsewhere")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("outside the root");
        assertThat(VirtualFileSystems.readOnlyLocal(root, List.of(tempDir)).list("demo/elsewhere"))
                .contains("demo/elsewhere/outside.txt");
        // The link is an entry of a directory inside the root: it is named, not followed.
        assertThat(fs.list("demo")).containsExactly("demo/SKILL.md", "demo/elsewhere", "demo/scripts");
    }

    @Test
    @DisplayName("read opens the path it checked, so swapping the link after the check serves nothing else (EE-36)")
    void readOpensTheCheckedPath() throws Exception {
        final Path link = root.resolve("demo/swapped.txt");
        final Path spare = root.resolve("demo/.swap");
        final Path inside = root.resolve("demo/SKILL.md");
        final Path outside = tempDir.resolve("outside.txt");
        link(link, inside);

        final AtomicBoolean stop = new AtomicBoolean();
        final Thread swapper = new Thread(() -> {
            boolean toOutside = true;
            while (!stop.get()) {
                try {
                    Files.deleteIfExists(spare);
                    Files.createSymbolicLink(spare, toOutside ? outside : inside);
                    Files.move(spare, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    toOutside = !toOutside;
                } catch (java.io.IOException e) {
                    // Try again: the reader is what the test is about.
                }
            }
        }, "link-swapper");
        swapper.setDaemon(true);
        swapper.start();
        int served = 0;
        try {
            for (int i = 0; i < 20_000; i++) {
                final String content;
                try (InputStream in = fs.read("demo/swapped.txt")) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (InvalidPathException e) {
                    continue; // checked while the link pointed outside: refused, which is the rule
                } catch (FileNotFoundException | BackendConnectionException e) {
                    continue; // resolved in the middle of a swap (a rename is not atomic to a reader everywhere)
                }
                assertThat(content).as("read #%d", i).isEqualTo("# demo");
                served++;
            }
        } finally {
            stop.set(true);
            swapper.join(5_000);
        }
        assertThat(served).as("reads that were served at all").isPositive();
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "symbolic links are not supported here: " + e.getMessage());
        }
    }
}
