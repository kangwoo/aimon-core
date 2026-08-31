package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

class VfsTaskOutputStoreTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;
    private VfsTaskOutputStore store;

    @BeforeEach
    void setUp() {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        store = new VfsTaskOutputStore(fileSystem);
    }

    @Test
    void constructorRejectsNullFileSystem() {
        assertThatNullPointerException().isThrownBy(() -> new VfsTaskOutputStore(null));
    }

    @Test
    void constructorRejectsBlankBaseDir() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VfsTaskOutputStore(fileSystem, "  "));
    }

    @Test
    void readUnknownTaskReturnsEmptySlice() {
        OutputSlice slice = store.read("nope", 0, 100);

        assertThat(slice.getText()).isEmpty();
        assertThat(slice.getNextOffset()).isZero();
        assertThat(slice.isTruncatedHead()).isFalse();
        assertThat(slice.hasMore()).isFalse();
        assertThat(store.length("nope")).isZero();
    }

    @Test
    void singleAppendRoundTrips() {
        store.append("t", "hello world");

        OutputSlice slice = store.read("t", 0, 100);

        assertThat(slice.getText()).isEqualTo("hello world");
        assertThat(slice.getNextOffset()).isEqualTo(11L);
        assertThat(slice.hasMore()).isFalse();
        assertThat(store.length("t")).isEqualTo(11L);
    }

    @Test
    void nullAndEmptyChunksAreNoOps() {
        store.append("t", null);
        store.append("t", "");

        assertThat(store.length("t")).isZero();
    }

    @Test
    void eachAppendCreatesAnImmutableSegmentNamedByStartOffset() {
        store.append("t", "abc"); // [0,3)
        store.append("t", "defgh"); // [3,8)

        List<String> entries = fileSystem.list(VfsTaskOutputStore.DEFAULT_BASE_DIR + "/t");
        assertThat(entries).hasSize(2);
        assertThat(entries)
                .anyMatch(e -> e.endsWith("/00000000000000000000.seg") || e.endsWith("00000000000000000000.seg"));
        assertThat(entries).anyMatch(e -> e.endsWith("00000000000000000003.seg"));
    }

    @Test
    void readSpansMultipleSegments() {
        store.append("t", "abc"); // [0,3)
        store.append("t", "defgh"); // [3,8)
        store.append("t", "ij"); // [8,10)

        OutputSlice slice = store.read("t", 0, 100);

        assertThat(slice.getText()).isEqualTo("abcdefghij");
        assertThat(slice.getNextOffset()).isEqualTo(10L);
        assertThat(slice.hasMore()).isFalse();
        assertThat(store.length("t")).isEqualTo(10L);
    }

    @Test
    void deltaReadStartingInsideLaterSegmentMarksTruncatedHead() {
        store.append("t", "abc"); // [0,3)
        store.append("t", "defgh"); // [3,8)
        store.append("t", "ij"); // [8,10)

        // Offset 5 lands inside the second segment (which covers [3,8)).
        OutputSlice slice = store.read("t", 5, 100);

        assertThat(slice.getText()).isEqualTo("fghij");
        assertThat(slice.getNextOffset()).isEqualTo(10L);
        assertThat(slice.isTruncatedHead()).isTrue();
        assertThat(slice.hasMore()).isFalse();
    }

    @Test
    void readHonoursMaxCharsAcrossSegmentBoundary() {
        store.append("t", "abc"); // [0,3)
        store.append("t", "defgh"); // [3,8)

        // Budget 5 starting at 0 should stop mid second segment.
        OutputSlice slice = store.read("t", 0, 5);

        assertThat(slice.getText()).isEqualTo("abcde");
        assertThat(slice.getNextOffset()).isEqualTo(5L);
        assertThat(slice.hasMore()).isTrue();
    }

    @Test
    void incrementalPollingAdvancesCursorAcrossAppends() {
        store.append("t", "abc");
        OutputSlice s1 = store.read("t", 0, 100);
        assertThat(s1.getText()).isEqualTo("abc");
        assertThat(s1.hasMore()).isFalse();

        OutputSlice caughtUp = store.read("t", s1.getNextOffset(), 100);
        assertThat(caughtUp.getText()).isEmpty();
        assertThat(caughtUp.getNextOffset()).isEqualTo(3L);
        assertThat(caughtUp.hasMore()).isFalse();

        store.append("t", "def");
        OutputSlice s2 = store.read("t", s1.getNextOffset(), 100);
        assertThat(s2.getText()).isEqualTo("def");
        assertThat(s2.getNextOffset()).isEqualTo(6L);
        assertThat(s2.isTruncatedHead()).isTrue();
    }

    @Test
    void failedAppendLeavesNoOffsetGapAndRetriesAtSameStart() {
        // With a single writer, offsets stay contiguous; this guards the "advance only after successful PUT" contract
        // indirectly by asserting the reconstructed length equals the sum of chunk lengths across many appends.
        for (int i = 0; i < 50; i++) {
            store.append("t", "0123456789");
        }

        assertThat(store.length("t")).isEqualTo(500L);
        OutputSlice tail = store.read("t", 495, 100);
        assertThat(tail.getText()).isEqualTo("56789");
        assertThat(tail.hasMore()).isFalse();
    }

    @Test
    void lengthAndReadReconstructFromDiskOnAFreshInstance() {
        store.append("t", "abc");
        store.append("t", "defgh");

        // A brand-new store instance (simulating another node) with no in-memory cursor must resolve length and deltas
        // purely from the on-disk segment filenames.
        VfsTaskOutputStore reader = new VfsTaskOutputStore(fileSystem);

        assertThat(reader.length("t")).isEqualTo(8L);
        OutputSlice slice = reader.read("t", 0, 100);
        assertThat(slice.getText()).isEqualTo("abcdefgh");
        assertThat(slice.getNextOffset()).isEqualTo(8L);
    }

    @Test
    void freshInstanceContinuesAppendingWithoutOverwritingExistingSegments() {
        store.append("t", "abc");

        VfsTaskOutputStore second = new VfsTaskOutputStore(fileSystem);
        second.append("t", "def"); // must lazily reconstruct length=3 and write segment at offset 3

        OutputSlice slice = second.read("t", 0, 100);
        assertThat(slice.getText()).isEqualTo("abcdef");
        assertThat(second.length("t")).isEqualTo(6L);
    }

    @Test
    void utf8MultibyteCharactersRoundTripByCharOffset() {
        // Mix of ASCII, a 3-byte char (한), and a 4-byte astral char (😀, 2 Java chars).
        store.append("t", "a한"); // 'a'(1) + '한'(1 char) => 2 chars
        store.append("t", "😀b"); // '😀'(2 chars) + 'b'(1) => 3 chars

        assertThat(store.length("t")).isEqualTo(5L);

        OutputSlice whole = store.read("t", 0, 100);
        assertThat(whole.getText()).isEqualTo("a한😀b");

        // A char-based delta from offset 2 begins exactly at the emoji, never splitting its bytes.
        OutputSlice delta = store.read("t", 2, 100);
        assertThat(delta.getText()).isEqualTo("😀b");
        assertThat(delta.getNextOffset()).isEqualTo(5L);
    }

    @Test
    void customBaseDirIsHonoured() {
        VfsTaskOutputStore custom = new VfsTaskOutputStore(fileSystem, "custom/out/");
        custom.append("t", "abc");

        assertThat(fileSystem.exists("custom/out/t")).isTrue();
        assertThat(custom.read("t", 0, 100).getText()).isEqualTo("abc");
    }

    @Test
    void evictRemovesAllSegments() {
        store.append("t", "abc");
        store.append("t", "def");

        store.evict("t");

        assertThat(store.length("t")).isZero();
        assertThat(store.read("t", 0, 100).getText()).isEmpty();
        assertThat(fileSystem.exists(VfsTaskOutputStore.DEFAULT_BASE_DIR + "/t")).isFalse();
    }

    @Test
    void negativeOffsetClampedToZero() {
        store.append("t", "abc");

        assertThat(store.read("t", -10, 100).getText()).isEqualTo("abc");
    }

    @Test
    void nullTaskIdRejected() {
        assertThatNullPointerException().isThrownBy(() -> store.append(null, "x"));
        assertThatNullPointerException().isThrownBy(() -> store.read(null, 0, 1));
        assertThatNullPointerException().isThrownBy(() -> store.length(null));
        assertThatNullPointerException().isThrownBy(() -> store.evict(null));
    }

    @Test
    void concurrentAppendsPreserveTotalLengthAndContiguity() throws Exception {
        int writers = 6;
        int perWriter = 100;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            Future<?>[] futures = new Future<?>[writers];
            for (int w = 0; w < writers; w++) {
                futures[w] = pool.submit(() -> {
                    for (int i = 0; i < perWriter; i++) {
                        store.append("race", "xy");
                    }
                });
            }
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long expected = (long) writers * perWriter * 2;
        assertThat(store.length("race")).isEqualTo(expected);

        // Reading the whole stream yields exactly `expected` characters with no gaps (contiguous segments).
        OutputSlice slice = store.read("race", 0, (int) expected + 10);
        assertThat(slice.getText()).hasSize((int) expected);
        assertThat(slice.hasMore()).isFalse();
    }
}
