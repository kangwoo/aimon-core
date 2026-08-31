package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class InMemoryTaskOutputStoreTest {

    private final InMemoryTaskOutputStore store = new InMemoryTaskOutputStore();

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
    void appendThenReadWholeStream() {
        store.append("t", "hello world");

        OutputSlice slice = store.read("t", 0, 100);

        assertThat(slice.getText()).isEqualTo("hello world");
        assertThat(slice.getNextOffset()).isEqualTo(11L);
        assertThat(slice.isTruncatedHead()).isFalse();
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
    void readHonoursMaxCharsAndReportsHasMore() {
        store.append("t", "0123456789");

        OutputSlice first = store.read("t", 0, 4);

        assertThat(first.getText()).isEqualTo("0123");
        assertThat(first.getNextOffset()).isEqualTo(4L);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.isTruncatedHead()).isFalse();
    }

    @Test
    void deltaReadFromMidStreamMarksTruncatedHead() {
        store.append("t", "0123456789");

        OutputSlice slice = store.read("t", 4, 100);

        assertThat(slice.getText()).isEqualTo("456789");
        assertThat(slice.getNextOffset()).isEqualTo(10L);
        assertThat(slice.isTruncatedHead()).isTrue();
        assertThat(slice.hasMore()).isFalse();
    }

    @Test
    void incrementalPollingAdvancesCursorAcrossAppends() {
        store.append("t", "abc");
        OutputSlice s1 = store.read("t", 0, 100);
        assertThat(s1.getText()).isEqualTo("abc");
        assertThat(s1.hasMore()).isFalse();

        // Nothing new yet.
        OutputSlice s2 = store.read("t", s1.getNextOffset(), 100);
        assertThat(s2.getText()).isEmpty();
        assertThat(s2.getNextOffset()).isEqualTo(3L);
        assertThat(s2.hasMore()).isFalse();

        // More appended; the same cursor now yields the delta.
        store.append("t", "def");
        OutputSlice s3 = store.read("t", s1.getNextOffset(), 100);
        assertThat(s3.getText()).isEqualTo("def");
        assertThat(s3.getNextOffset()).isEqualTo(6L);
    }

    @Test
    void readAtOrPastEndReturnsEmptyCaughtUpSlice() {
        store.append("t", "abc");

        OutputSlice atEnd = store.read("t", 3, 100);
        assertThat(atEnd.getText()).isEmpty();
        assertThat(atEnd.getNextOffset()).isEqualTo(3L);
        assertThat(atEnd.isTruncatedHead()).isTrue();
        assertThat(atEnd.hasMore()).isFalse();

        OutputSlice pastEnd = store.read("t", 99, 100);
        assertThat(pastEnd.getText()).isEmpty();
        assertThat(pastEnd.getNextOffset()).isEqualTo(99L);
    }

    @Test
    void negativeOffsetIsClampedToZero() {
        store.append("t", "abc");

        OutputSlice slice = store.read("t", -5, 100);

        assertThat(slice.getText()).isEqualTo("abc");
        assertThat(slice.isTruncatedHead()).isFalse();
    }

    @Test
    void evictDiscardsTaskOutput() {
        store.append("t", "abc");
        store.evict("t");

        assertThat(store.length("t")).isZero();
        assertThat(store.read("t", 0, 100).getText()).isEmpty();
    }

    @Test
    void tasksAreIsolatedById() {
        store.append("a", "aaa");
        store.append("b", "bbbb");

        assertThat(store.length("a")).isEqualTo(3L);
        assertThat(store.length("b")).isEqualTo(4L);
        assertThat(store.read("a", 0, 100).getText()).isEqualTo("aaa");
    }

    @Test
    void nullTaskIdRejected() {
        assertThatNullPointerException().isThrownBy(() -> store.append(null, "x"));
        assertThatNullPointerException().isThrownBy(() -> store.read(null, 0, 1));
        assertThatNullPointerException().isThrownBy(() -> store.length(null));
        assertThatNullPointerException().isThrownBy(() -> store.evict(null));
    }

    @Test
    void concurrentAppendsPreserveEveryChunk() throws Exception {
        int writers = 8;
        int perWriter = 200;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            Future<?>[] futures = new Future<?>[writers];
            for (int w = 0; w < writers; w++) {
                futures[w] = pool.submit(() -> {
                    for (int i = 0; i < perWriter; i++) {
                        store.append("race", "x");
                    }
                });
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // No lost updates: every single-char append is present.
        assertThat(store.length("race")).isEqualTo((long) writers * perWriter);
    }
}
