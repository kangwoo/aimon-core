package at.aimon.core.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.exception.InsufficientStorageException;

class SizeLimitedOutputStreamTest {

    @Test
    void writesUnderTheCapReachTheDelegate() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 10)) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(sink.toByteArray()).asString(StandardCharsets.UTF_8).isEqualTo("hello");
    }

    @Test
    void writingExactlyUpToTheCapIsAllowed() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 5)) {
            out.write("abcde".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(sink.size()).isEqualTo(5);
    }

    @Test
    void bulkWriteExceedingTheCapIsRejectedAndNoBytesAreDelegated() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 4);

        assertThatThrownBy(() -> out.write("abcde".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InsufficientStorageException.class)
                .hasMessageContaining("exceeds maximum allowed size of 4 bytes");

        // The rejected write must not have leaked any bytes to the underlying stream.
        assertThat(sink.size()).isZero();
    }

    @Test
    void singleByteWriteExceedingTheCapIsRejected() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 2);

        out.write('a');
        out.write('b');
        assertThatThrownBy(() -> out.write('c')).isInstanceOf(InsufficientStorageException.class);

        assertThat(sink.size()).isEqualTo(2);
    }

    @Test
    void accumulatedWritesAreCountedAcrossCalls() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 6);

        out.write("abc".getBytes(StandardCharsets.UTF_8)); // total 3
        out.write("def".getBytes(StandardCharsets.UTF_8)); // total 6, exactly at cap
        assertThatThrownBy(() -> out.write("g".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InsufficientStorageException.class);

        assertThat(sink.toByteArray()).asString(StandardCharsets.UTF_8).isEqualTo("abcdef");
    }

    @Test
    void counterIsUnchangedAfterARejectedWriteSoASmallerWriteStillFits() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 5);

        out.write("abc".getBytes(StandardCharsets.UTF_8)); // total 3
        assertThatThrownBy(() -> out.write("xyz".getBytes(StandardCharsets.UTF_8))) // would be 6 > 5
                .isInstanceOf(InsufficientStorageException.class);
        out.write("de".getBytes(StandardCharsets.UTF_8)); // 3 + 2 = 5, still fits

        assertThat(sink.toByteArray()).asString(StandardCharsets.UTF_8).isEqualTo("abcde");
    }

    @Test
    void zeroCapRejectsAnyNonEmptyWriteButAllowsEmptyWrites() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 0);

        out.write(new byte[0]); // zero-length write is permitted
        assertThatThrownBy(() -> out.write('x')).isInstanceOf(InsufficientStorageException.class);

        assertThat(sink.size()).isZero();
    }

    @Test
    void flushAndCloseAreDelegated() throws IOException {
        AtomicBoolean flushed = new AtomicBoolean(false);
        AtomicBoolean closed = new AtomicBoolean(false);
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {
                // no-op sink
            }

            @Override
            public void flush() {
                flushed.set(true);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 10);
        out.flush();
        out.close();

        assertThat(flushed).isTrue();
        assertThat(closed).isTrue();
    }

    @Test
    void constructorRejectsNullDelegate() {
        assertThatThrownBy(() -> new SizeLimitedOutputStream(null, 10)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Delegate cannot be null");
    }

    @Test
    void constructorRejectsNegativeCapOtherThanTheUnlimitedSentinel() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        assertThatThrownBy(() -> new SizeLimitedOutputStream(sink, -2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBytes must be >= 0");
    }

    @Test
    void theUnlimitedSentinelIsAcceptedAndLetsEveryWriteThrough() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, VirtualFileSystem.NO_MAX_FILE_SIZE)) {
            out.write("abcdefghij".getBytes(StandardCharsets.UTF_8));
            out.write('k');
        }
        assertThat(sink.toByteArray()).asString(StandardCharsets.UTF_8).isEqualTo("abcdefghijk");
    }

    @Test
    void wrapSkipsTheDecoratorWhenUnlimited() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        assertThat(SizeLimitedOutputStream.wrap(sink, VirtualFileSystem.NO_MAX_FILE_SIZE)).isSameAs(sink);
    }

    @Test
    void wrapDecoratesWhenACapIsConfigured() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream wrapped = SizeLimitedOutputStream.wrap(sink, 3);

        assertThat(wrapped).isInstanceOf(SizeLimitedOutputStream.class);
        assertThatThrownBy(() -> wrapped.write("abcd".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InsufficientStorageException.class);
    }

    @Test
    void wrapRejectsNullDelegate() {
        assertThatThrownBy(() -> SizeLimitedOutputStream.wrap(null, VirtualFileSystem.NO_MAX_FILE_SIZE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Delegate cannot be null");
    }

    @Test
    void onLimitExceededRunsOnceBeforeTheRejectionAndSeesTheAcceptedByteCount() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AtomicInteger calls = new AtomicInteger();
        AtomicLong observed = new AtomicLong(-1);
        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 4) {
            @Override
            protected void onLimitExceeded() {
                calls.incrementAndGet();
                observed.set(bytesWritten());
            }
        };

        out.write("abc".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> out.write("de".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InsufficientStorageException.class);

        assertThat(calls).hasValue(1);
        assertThat(observed).hasValue(3);
    }

    @Test
    void aFailingOnLimitExceededReplacesTheStorageException() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 1) {
            @Override
            protected void onLimitExceeded() throws IOException {
                throw new IOException("abort failed");
            }
        };

        // The cleanup failure is the honest outcome: the backend could not undo what it accepted.
        assertThatThrownBy(() -> out.write("ab".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class)
                .isNotInstanceOf(InsufficientStorageException.class).hasMessage("abort failed");
    }

    @Test
    void onLimitExceededIsNotCalledWhenNoWriteExceedsTheCap() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AtomicInteger calls = new AtomicInteger();
        try (SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 5) {
            @Override
            protected void onLimitExceeded() {
                calls.incrementAndGet();
            }
        }) {
            out.write("abcde".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(calls).hasValue(0);
    }

    @Test
    void outOfBoundsWriteArgumentsAreRejected() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, 100);
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> out.write(data, -1, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> out.write(data, 0, 4)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void veryLargeCapDoesNotSpuriouslyRejectNormalWrites() throws IOException {
        // A cap near Long.MAX_VALUE must accept an ordinary write. Note: this cannot distinguish the overflow-safe
        // "additional > maxBytes - written" form from the naive "written + additional > maxBytes" form, because
        // reaching a 'written' large enough to overflow the naive form would require writing exabytes. The subtraction
        // form is a defensive-correctness choice justified by the invariant written <= maxBytes (see the class), not
        // something an achievable write can exercise; this test only guards against a large cap being rejected
        // outright.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (SizeLimitedOutputStream out = new SizeLimitedOutputStream(sink, Long.MAX_VALUE)) {
            out.write("data".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(sink.size()).isEqualTo(4);
    }
}
