package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.environment.RuntimeFileSystem;

@DisplayName("LocalRuntimeFileSystem")
class LocalRuntimeFileSystemTest {

    @TempDir
    Path tempDir;

    private Path root;
    private RuntimeFileSystem files;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.toRealPath();
        files = new LocalRuntimeFileSystem(root);
    }

    @Test
    @DisplayName("writeNew creates the file with its whole content and leaves no temporary name")
    void writeNew() throws IOException {
        files.writeNew(root.resolve("a").toString(), "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(root.resolve("a")).hasContent("hello");
        try (var entries = Files.list(root)) {
            assertThat(entries).containsExactly(root.resolve("a"));
        }
    }

    @Test
    @DisplayName("writeNew refuses an existing file, directory or link and changes nothing")
    void writeNewRefusesExisting() throws IOException {
        Files.writeString(root.resolve("f"), "old");
        Files.createDirectory(root.resolve("d"));
        Files.createSymbolicLink(root.resolve("l"), root.resolve("missing"));
        for (String name : List.of("f", "d", "l")) {
            assertThatThrownBy(() -> files.writeNew(root.resolve(name).toString(), new byte[]{1}))
                    .isInstanceOf(FileAlreadyExistsException.class);
        }
        assertThat(root.resolve("f")).hasContent("old");
        assertThat(root.resolve("missing")).doesNotExist();
    }

    @Test
    @DisplayName("of concurrent writers to one path exactly one wins")
    void exactlyOneWriterWins() throws Exception {
        final String target = root.resolve("race").toString();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger won = new AtomicInteger();
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final byte[] content = ("writer-" + i).getBytes(StandardCharsets.UTF_8);
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                    files.writeNew(target, content);
                    won.incrementAndGet();
                } catch (FileAlreadyExistsException e) {
                    // lost
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(won).hasValue(1);
        assertThat(Files.readString(Path.of(target))).startsWith("writer-");
    }

    @Test
    @DisplayName("read is capped, refuses links and non-regular files, reports a missing file")
    void read() throws IOException {
        Files.writeString(root.resolve("f"), "0123456789");
        assertThat(files.read(root.resolve("f").toString(), 4)).containsExactly('0', '1', '2', '3');
        assertThat(files.read(root.resolve("f").toString(), 100)).hasSize(10);
        Files.createSymbolicLink(root.resolve("l"), root.resolve("f"));
        assertThatThrownBy(() -> files.read(root.resolve("l").toString(), 100)).isInstanceOf(IOException.class);
        Files.createDirectory(root.resolve("d"));
        assertThatThrownBy(() -> files.read(root.resolve("d").toString(), 100)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> files.read(root.resolve("none").toString(), 100))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    @DisplayName("delete removes a tree without following links; a missing path is not an error")
    void delete() throws IOException {
        final Path outside = Files.createDirectories(tempDir.resolveSibling(tempDir.getFileName() + "-outside"));
        Files.writeString(outside.resolve("keep"), "keep");
        final Path tree = Files.createDirectories(root.resolve("t/a/b"));
        Files.writeString(tree.resolve("x"), "x");
        Files.createSymbolicLink(root.resolve("t/link"), outside);
        files.delete(root.resolve("t").toString());
        assertThat(root.resolve("t")).doesNotExist();
        assertThat(outside.resolve("keep")).exists();
        files.delete(root.resolve("t").toString());
        Files.delete(outside.resolve("keep"));
        Files.delete(outside);
    }

    @Test
    @DisplayName("createDirectories creates parents; paths outside the root or relative are refused")
    void confinement() throws IOException {
        files.createDirectories(root.resolve("p/q").toString());
        assertThat(root.resolve("p/q")).isDirectory();
        assertThatThrownBy(() -> files.writeNew(root.resolve("../escape").toString(), new byte[0]))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> files.writeNew("relative", new byte[0])).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> files.delete(root.toString())).isInstanceOf(IOException.class);
        final Path outside = Files.createDirectories(tempDir.resolveSibling(tempDir.getFileName() + "-out2"));
        Files.createSymbolicLink(root.resolve("hop"), outside);
        assertThatThrownBy(() -> files.writeNew(root.resolve("hop/x").toString(), new byte[0]))
                .isInstanceOf(IOException.class);
        assertThat(outside.resolve("x")).doesNotExist();
        Files.delete(outside);
    }
}
