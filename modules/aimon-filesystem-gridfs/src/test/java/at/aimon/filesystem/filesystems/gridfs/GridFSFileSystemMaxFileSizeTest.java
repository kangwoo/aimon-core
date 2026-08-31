package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.InsufficientStorageException;
import at.aimon.filesystem.core.gridfs.GridFSConfig;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

/**
 * What the shared contract test cannot see: after GridFS refuses an over-sized write, the chunks it had already
 * streamed into the bucket are gone as well.
 *
 * <p>
 * This is the failure mode the cap was added against. GridFS uploads incrementally — the driver flushes a chunk
 * document as soon as one fills — so by the time a write crosses the limit, part of the rejected payload is already in
 * {@code fs.chunks}. Rejecting without aborting would leave those chunks with no {@code fs.files} entry pointing at
 * them: invisible to every method on {@link GridFSFileSystem}, and never reclaimed. Every case here therefore asserts
 * against the collections directly, and uses a payload big enough that the driver really has flushed something before
 * the limit trips — {@link java.io.InputStream#transferTo} copies in 8 KiB blocks, so a cap under that would be hit on
 * the very first write, with nothing yet uploaded to strand.
 */
@Tag("docker")
class GridFSFileSystemMaxFileSizeTest {

    /** Small enough that the accepted prefix spans many chunk documents rather than sitting in one. */
    private static final int CHUNK_SIZE = 1024;

    /** Above one {@code transferTo} block (8 KiB) and below two, so the second block is the one that is refused. */
    private static final long CAP = 10_000;

    private static final int OVER_CAP = 30_000;

    private GridFSFileSystem fs;

    @BeforeEach
    void setUp() {
        GridFSTestSupport.cleanDatabase();
        fs = new GridFSFileSystem(GridFSConfig.builder().connectionString(GridFSTestSupport.MONGO.getConnectionString())
                .databaseName(GridFSTestSupport.DATABASE_NAME).chunkSizeBytes(CHUNK_SIZE).maxFileSize(CAP).build());
        fs.initialize();
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    @Test
    @DisplayName("a refused bulk write leaves neither a file entry nor the chunks it had already uploaded")
    void bulkWriteBeyondTheCapStrandsNothing() {
        assertThatThrownBy(() -> fs.write("big.bin", new ByteArrayInputStream(payload(OVER_CAP)), OVER_CAP))
                .isInstanceOf(InsufficientStorageException.class);

        assertThat(fs.exists("big.bin")).isFalse();
        assertThat(GridFSTestSupport.countDocuments("fs.files")).isZero();
        assertThat(GridFSTestSupport.countDocuments("fs.chunks")).isZero();
    }

    @Test
    @DisplayName("a refused streaming write leaves neither a file entry nor the chunks it had already uploaded")
    void streamingWriteBeyondTheCapStrandsNothing() throws IOException {
        try (OutputStream out = fs.openOutputStream("big.bin")) {
            out.write(payload(8192));

            assertThatThrownBy(() -> out.write(payload(8192))).isInstanceOf(InsufficientStorageException.class);
        }

        assertThat(fs.exists("big.bin")).isFalse();
        assertThat(GridFSTestSupport.countDocuments("fs.files")).isZero();
        assertThat(GridFSTestSupport.countDocuments("fs.chunks")).isZero();
    }

    @Test
    @DisplayName("a refused write leaves the revision that was already there whole")
    void rejectedWriteKeepsThePreviousRevision() {
        final byte[] original = payload(4096);
        fs.write("big.bin", new ByteArrayInputStream(original), original.length);

        assertThatThrownBy(() -> fs.write("big.bin", new ByteArrayInputStream(payload(OVER_CAP)), OVER_CAP))
                .isInstanceOf(InsufficientStorageException.class);

        assertThat(fs.getMetadata("big.bin").getSize()).isEqualTo(original.length);
        // Exactly one revision: the rejected upload neither added one of its own nor retired the one it failed to
        // replace. The chunk count follows from the surviving revision alone.
        assertThat(GridFSTestSupport.countDocuments("fs.files")).isEqualTo(1);
        assertThat(GridFSTestSupport.countDocuments("fs.chunks")).isEqualTo(original.length / CHUNK_SIZE);
    }

    @Test
    @DisplayName("a write of exactly the cap is stored, chunks and all")
    void writeAtExactlyTheCapIsStored() {
        final byte[] atCap = payload((int) CAP);

        fs.write("at-cap.bin", new ByteArrayInputStream(atCap), atCap.length);

        assertThat(fs.getMetadata("at-cap.bin").getSize()).isEqualTo(CAP);
        assertThat(GridFSTestSupport.countDocuments("fs.files")).isEqualTo(1);
    }

    @Test
    @DisplayName("an under-declared content length does not buy more room than the cap allows")
    void underDeclaredLengthIsStillMeasuredOnTheBytesActuallyRead() {
        // The cheapest way past a cap checked against the caller's number would be to lie about it. The limit is
        // enforced on the bytes that arrive, so the lie only changes which layer refuses the write.
        assertThatThrownBy(() -> fs.write("liar.bin", new ByteArrayInputStream(payload(OVER_CAP)), 10))
                .isInstanceOf(InsufficientStorageException.class);

        assertThat(fs.exists("liar.bin")).isFalse();
        assertThat(GridFSTestSupport.countDocuments("fs.chunks")).isZero();
    }

    private static byte[] payload(int size) {
        final byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'x');
        return bytes;
    }
}
