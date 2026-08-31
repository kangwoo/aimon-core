package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.InsufficientStorageException;

/**
 * Verifies that the configured {@code maxFileSize} cap is enforced on <em>both</em> the bulk-write ({@code write})
 * and streaming ({@code openOutputStream}) paths. The streaming enforcement is the fix for issue #9.
 */
class LocalFileSystemMaxFileSizeTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem createWithCap(long maxFileSize) {
        LocalFileSystemConfig config = LocalFileSystemConfig.builder(tempDir.toString()).maxFileSize(maxFileSize)
                .build();
        LocalFileSystem fs = new LocalFileSystem(config);
        fs.initialize();
        return fs;
    }

    private LocalFileSystem createWithoutCap() {
        LocalFileSystem fs = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fs.initialize();
        return fs;
    }

    private static byte[] bytes(int count, char c) {
        byte[] data = new byte[count];
        java.util.Arrays.fill(data, (byte) c);
        return data;
    }

    // ---------- write() path ----------

    @Test
    void writeRejectsContentExceedingCapEvenWhenLengthIsUndeclared() {
        LocalFileSystem fs = createWithCap(10);

        // contentLength = -1 (unknown) forces enforcement through the running byte counter, not the fast-fail path.
        assertThatThrownBy(() -> fs.write("big.bin", new ByteArrayInputStream(bytes(11, 'a')), -1))
                .isInstanceOf(InsufficientStorageException.class)
                .hasMessageContaining("exceeds maximum allowed size of 10 bytes");

        fs.close();
    }

    @Test
    void writeRejectsWhenDeclaredLengthExceedsCap() {
        LocalFileSystem fs = createWithCap(10);

        assertThatThrownBy(() -> fs.write("big.bin", new ByteArrayInputStream(bytes(11, 'a')), 11))
                .isInstanceOf(InsufficientStorageException.class).hasMessageContaining("exceeds maximum allowed size");

        fs.close();
    }

    @Test
    void writeLeavesNoPartialFileAfterRejection() {
        LocalFileSystem fs = createWithCap(10);

        assertThatThrownBy(() -> fs.write("big.bin", new ByteArrayInputStream(bytes(11, 'a')), -1))
                .isInstanceOf(InsufficientStorageException.class);

        // write() owns the lifecycle, so a rejected oversized write must not leave a truncated artifact behind.
        assertThat(tempDir.resolve("big.bin")).doesNotExist();

        fs.close();
    }

    @Test
    void writeAcceptsContentExactlyAtCap() throws IOException {
        LocalFileSystem fs = createWithCap(10);

        fs.write("exact.bin", new ByteArrayInputStream(bytes(10, 'a')), 10);

        assertThat(Files.size(tempDir.resolve("exact.bin"))).isEqualTo(10);

        fs.close();
    }

    // ---------- openOutputStream() path (issue #9) ----------

    @Test
    void openOutputStreamRejectsWritingPastCap() throws IOException {
        LocalFileSystem fs = createWithCap(10);

        try (OutputStream out = fs.openOutputStream("stream.bin")) {
            assertThatThrownBy(() -> out.write(bytes(11, 'b'))).isInstanceOf(InsufficientStorageException.class)
                    .hasMessageContaining("exceeds maximum allowed size of 10 bytes");
        }

        // The oversized write is rejected before any byte is delegated, so the freshly created file stays empty —
        // not merely "within the cap". Asserting exactly zero pins the "no bytes leak downstream" guarantee.
        assertThat(Files.size(tempDir.resolve("stream.bin"))).isZero();

        fs.close();
    }

    @Test
    void openOutputStreamRejectsAccumulatedWritesPastCap() throws IOException {
        LocalFileSystem fs = createWithCap(10);

        try (OutputStream out = fs.openOutputStream("stream.bin")) {
            out.write(bytes(6, 'a')); // total 6, ok
            assertThatThrownBy(() -> out.write(bytes(6, 'b'))) // would be 12 > 10
                    .isInstanceOf(InsufficientStorageException.class);
        }

        // The bytes accepted before the overflow are within the cap and remain in the file (documented behavior).
        long size = Files.size(tempDir.resolve("stream.bin"));
        assertThat(size).isEqualTo(6);

        fs.close();
    }

    @Test
    void openOutputStreamAcceptsWritingExactlyUpToCap() throws IOException {
        LocalFileSystem fs = createWithCap(10);

        try (OutputStream out = fs.openOutputStream("stream.bin")) {
            out.write(bytes(10, 'a'));
        }

        assertThat(Files.readAllBytes(tempDir.resolve("stream.bin"))).hasSize(10);

        fs.close();
    }

    @Test
    void openOutputStreamIsUnboundedWhenNoCapConfigured() {
        LocalFileSystem fs = createWithoutCap();

        // Default config has no cap: a large streaming write must succeed without an InsufficientStorageException.
        assertThatCode(() -> {
            try (OutputStream out = fs.openOutputStream("unbounded.bin")) {
                out.write(bytes(64 * 1024, 'z'));
            }
        }).doesNotThrowAnyException();

        fs.close();
    }

    // ---------- maxFileSize == 0 boundary (cap enabled, zero bytes allowed) ----------

    @Test
    void zeroCapRejectsAnyContentOnBothWritePathsButAllowsAnEmptyFile() throws IOException {
        LocalFileSystem fs = createWithCap(0);

        // write(): any non-empty content is rejected and leaves no partial file...
        assertThatThrownBy(() -> fs.write("bulk.bin", new ByteArrayInputStream(bytes(1, 'a')), -1))
                .isInstanceOf(InsufficientStorageException.class);
        assertThat(tempDir.resolve("bulk.bin")).doesNotExist();

        // ...while an empty write is permitted (0 <= 0).
        fs.write("empty-bulk.bin", new ByteArrayInputStream(new byte[0]), 0);
        assertThat(Files.size(tempDir.resolve("empty-bulk.bin"))).isZero();

        // openOutputStream(): the very first non-empty byte is rejected...
        try (OutputStream out = fs.openOutputStream("stream.bin")) {
            assertThatThrownBy(() -> out.write('x')).isInstanceOf(InsufficientStorageException.class);
        }
        assertThat(Files.size(tempDir.resolve("stream.bin"))).isZero();

        // ...while opening and closing without writing yields an empty file.
        fs.openOutputStream("empty-stream.bin").close();
        assertThat(Files.size(tempDir.resolve("empty-stream.bin"))).isZero();

        fs.close();
    }

    @Test
    void openOutputStreamContentIsReadableAfterCappedWrite() throws IOException {
        LocalFileSystem fs = createWithCap(100);

        try (OutputStream out = fs.openOutputStream("hello.txt")) {
            out.write("hello world".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(Files.readString(tempDir.resolve("hello.txt"))).isEqualTo("hello world");

        fs.close();
    }
}
