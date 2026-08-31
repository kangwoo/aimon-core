package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryTaskResultStoreTest {

    private InMemoryTaskResultStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTaskResultStore();
    }

    private static TaskResult resultFor(String answer) {
        return TaskResult.builder().success(true).finalAnswer(answer).build();
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new InMemoryTaskResultStore(0));
    }

    @Test
    void loadUnknownTaskReturnsEmpty() {
        assertThat(store.load("nope")).isEmpty();
    }

    @Test
    void saveThenLoadReturnsTheStoredResult() {
        final TaskResult result = resultFor("the answer");

        store.save("t", result);

        assertThat(store.load("t")).contains(result);
    }

    @Test
    void saveIsLastWriteWinsForTheSameTask() {
        store.save("t", resultFor("first"));
        store.save("t", resultFor("second"));

        assertThat(store.load("t")).get().satisfies(r -> assertThat(r.getSummary()).isEqualTo("second"));
    }

    @Test
    void evictRemovesTheResult() {
        store.save("t", resultFor("gone soon"));

        store.evict("t");

        assertThat(store.load("t")).isEmpty();
    }

    @Test
    void evictOfUnknownTaskIsSilent() {
        store.evict("never-existed");

        assertThat(store.load("never-existed")).isEmpty();
    }

    @Test
    void oldestEntryIsDroppedOnceTheCapIsExceeded() {
        final InMemoryTaskResultStore bounded = new InMemoryTaskResultStore(2);
        bounded.save("a", resultFor("a"));
        bounded.save("b", resultFor("b"));

        bounded.save("c", resultFor("c"));

        assertThat(bounded.load("a")).isEmpty();
        assertThat(bounded.load("b")).isPresent();
        assertThat(bounded.load("c")).isPresent();
    }

    @Test
    void readingAnEntryKeepsItFromBeingEvictedFirst() {
        // Access-ordered: a task whose result was just read is not the least recently used one.
        final InMemoryTaskResultStore bounded = new InMemoryTaskResultStore(2);
        bounded.save("a", resultFor("a"));
        bounded.save("b", resultFor("b"));
        bounded.load("a");

        bounded.save("c", resultFor("c"));

        assertThat(bounded.load("a")).isPresent();
        assertThat(bounded.load("b")).isEmpty();
    }
}
