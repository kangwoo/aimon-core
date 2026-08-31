package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.subagent.task.codec.JsonTaskResultCodec;

class VfsTaskResultStoreTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;
    private VfsTaskResultStore store;

    @BeforeEach
    void setUp() {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        store = new VfsTaskResultStore(fileSystem);
    }

    private static TaskResult sampleResult() {
        return TaskResult.builder().success(true).finalAnswer("the answer 🚀")
                .completionReason(CompletionReason.COMPLETED).iterationCount(3).durationMillis(1_500L).totalTokens(42)
                .build();
    }

    /** Reads an object's bytes straight off the backend, bypassing the store, to assert on the persisted form. */
    private String readRaw(String path) throws IOException {
        try (InputStream in = fileSystem.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void constructorRejectsNullFileSystem() {
        assertThatNullPointerException().isThrownBy(() -> new VfsTaskResultStore(null));
    }

    @Test
    void constructorRejectsNullCodec() {
        assertThatNullPointerException().isThrownBy(() -> new VfsTaskResultStore(fileSystem, null, "dir"));
    }

    @Test
    void constructorRejectsBlankBaseDir() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VfsTaskResultStore(fileSystem, new JsonTaskResultCodec(), "  "));
    }

    @Test
    void loadUnknownTaskReturnsEmpty() {
        assertThat(store.load("nope")).isEmpty();
    }

    @Test
    void saveThenLoadRoundTripsTheResult() {
        final TaskResult result = sampleResult();

        store.save("t", result);

        assertThat(store.load("t")).contains(result);
    }

    @Test
    void saveOverwritesThePreviousResultForTheSameTask() {
        store.save("t", TaskResult.builder().success(false).errorMessage("first").build());

        store.save("t", sampleResult());

        assertThat(store.load("t")).get().satisfies(r -> assertThat(r.getSummary()).isEqualTo("the answer 🚀"));
    }

    @Test
    void anotherInstanceLoadsTheResultFromTheSameDirectory() {
        final TaskResult result = sampleResult();
        store.save("t", result);

        // A fresh store over the same backing filesystem simulates another node with no shared memory: this is the
        // whole point of persisting the result rather than holding it in a node-local future.
        final VfsTaskResultStore otherNode = new VfsTaskResultStore(fileSystem);

        assertThat(otherNode.load("t")).contains(result);
    }

    @Test
    void evictRemovesTheResult() {
        store.save("t", sampleResult());

        store.evict("t");

        assertThat(store.load("t")).isEmpty();
    }

    @Test
    void evictOfUnknownTaskIsSilent() {
        store.evict("never-existed");

        assertThat(store.load("never-existed")).isEmpty();
    }

    @Test
    void malformedStoredResultLoadsAsEmptyRatherThanThrowing() {
        // Best-effort contract: an unreadable result is reported as absent, never as an exception to the caller.
        fileSystem.write(VfsTaskResultStore.DEFAULT_BASE_DIR + "/t.json", "{not json");

        assertThat(store.load("t")).isEmpty();
    }

    @Test
    void resultFromAnUnsupportedVersionLoadsAsEmpty() {
        fileSystem.write(VfsTaskResultStore.DEFAULT_BASE_DIR + "/t.json",
                "{\"version\":99,\"success\":true,\"finalAnswer\":\"from the future\"}");

        assertThat(store.load("t")).isEmpty();
    }

    @Test
    void aDirectoryWhereTheResultObjectBelongsLoadsAsEmpty() {
        fileSystem.createDirectory(VfsTaskResultStore.DEFAULT_BASE_DIR + "/t.json");

        assertThat(store.load("t")).isEmpty();
    }

    @Test
    void resultIsWrittenAsTheCodecDocumentWithNoEnvelope() throws Exception {
        // Unlike the snapshot store there is no wrapper: nothing needs carrying beyond the codec's own document,
        // because a task result is not owner-tagged (authorization happens one layer up, at the task controller).
        store.save("t", sampleResult());

        final String raw = readRaw(VfsTaskResultStore.DEFAULT_BASE_DIR + "/t.json");

        assertThat(new ObjectMapper().readTree(raw).get("version").asInt())
                .isEqualTo(JsonTaskResultCodec.FORMAT_VERSION);
        assertThat(new JsonTaskResultCodec().decode(raw)).isEqualTo(sampleResult());
    }

    @Test
    void resultsForDifferentTasksDoNotCollide() {
        store.save("a", TaskResult.builder().success(true).finalAnswer("answer a").build());
        store.save("b", TaskResult.builder().success(true).finalAnswer("answer b").build());

        assertThat(store.load("a")).get().satisfies(r -> assertThat(r.getSummary()).isEqualTo("answer a"));
        assertThat(store.load("b")).get().satisfies(r -> assertThat(r.getSummary()).isEqualTo("answer b"));
    }

    @Test
    void saveRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> store.save(null, sampleResult()));
        assertThatNullPointerException().isThrownBy(() -> store.save("t", null));
    }
}
