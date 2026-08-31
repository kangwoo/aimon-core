package at.aimon.filesystem.filesystems.s3;

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
import at.aimon.filesystem.core.s3.S3FileSystem;

/**
 * The per-file cap against a real bucket. The shared contract test covers what a caller sees; this covers how S3 gets
 * there, which is not how the other backends do it.
 *
 * <p>
 * Nothing here has to be reclaimed after a refusal, and that is the point worth pinning: a single-shot put needs a
 * known content length up front, so an over-sized bulk write is refused before a request is ever sent, and the
 * streaming path stages the payload in memory and simply never sends one. There is no partially uploaded object for a
 * later write to trip over — but only as long as both checks stay in front of the SDK call, which is what these cases
 * hold in place.
 */
@Tag("docker")
class S3FileSystemMaxFileSizeTest {

    private static final long CAP = 32;

    private S3FileSystem fs;

    @BeforeEach
    void setUp() {
        S3TestSupport.cleanBucket();
        fs = S3TestSupport.createAndInitialize(CAP);
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    @Test
    @DisplayName("a bulk write past the cap is refused and no object is created")
    void bulkWriteBeyondTheCapIsRefused() {
        final byte[] tooBig = payload((int) CAP + 1);

        assertThatThrownBy(() -> fs.write("big.bin", new ByteArrayInputStream(tooBig), tooBig.length))
                .isInstanceOf(InsufficientStorageException.class);

        assertThat(fs.exists("big.bin")).isFalse();
    }

    @Test
    @DisplayName("a write of exactly the cap is stored")
    void writeAtExactlyTheCapIsStored() {
        final byte[] atCap = payload((int) CAP);

        fs.write("at-cap.bin", new ByteArrayInputStream(atCap), atCap.length);

        assertThat(fs.getMetadata("at-cap.bin").getSize()).isEqualTo(CAP);
    }

    @Test
    @DisplayName("a refused write leaves the object that was already there untouched")
    void rejectedWriteKeepsThePreviousObject() {
        final byte[] original = payload(8);
        fs.write("obj.bin", new ByteArrayInputStream(original), original.length);

        final byte[] tooBig = payload((int) CAP + 1);
        assertThatThrownBy(() -> fs.write("obj.bin", new ByteArrayInputStream(tooBig), tooBig.length))
                .isInstanceOf(InsufficientStorageException.class);

        assertThat(fs.getMetadata("obj.bin").getSize()).isEqualTo(original.length);
    }

    @Test
    @DisplayName("the streaming path refuses at the write that crosses the cap and uploads nothing at all")
    void streamingWriteBeyondTheCapUploadsNothing() throws IOException {
        try (OutputStream out = fs.openOutputStream("streamed.bin")) {
            out.write(payload(16));

            assertThatThrownBy(() -> out.write(payload(20))).isInstanceOf(InsufficientStorageException.class);
        }

        // Stronger than the contract's "no more than the cap": this backend buffers until close, so the accepted
        // prefix goes nowhere either. Closing the stream after a refusal must not turn that prefix into an object.
        assertThat(fs.exists("streamed.bin")).isFalse();
    }

    @Test
    @DisplayName("a streaming write that stays within the cap is stored on close")
    void streamingWriteWithinTheCapIsStored() throws IOException {
        try (OutputStream out = fs.openOutputStream("streamed.bin")) {
            out.write(payload(16));
            out.write(payload(16));
        }

        assertThat(fs.getMetadata("streamed.bin").getSize()).isEqualTo(CAP);
    }

    @Test
    @DisplayName("an under-declared content length stores only what was declared, never more than the cap")
    void underDeclaredLengthCannotSmuggleBytesPastTheCap() {
        // S3 reaches the same guarantee from the other side: the SDK reads exactly the declared number of bytes, so a
        // stream with more to give does not get to hand them over. The cap holds either way.
        fs.write("liar.bin", new ByteArrayInputStream(payload(1024)), 8);

        assertThat(fs.getMetadata("liar.bin").getSize()).isEqualTo(8);
    }

    private static byte[] payload(int size) {
        final byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'x');
        return bytes;
    }
}
