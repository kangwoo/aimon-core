package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.StepKey;
import at.aimon.core.workflow.StepOutcome;

@DisplayName("VfsStepResultCache — persistent, cross-node step cache")
class VfsStepResultCacheTest {

    private static final String BASE_DIR = ".aimon/step-cache";
    private static final RunId RUN = RunId.from("audit");
    private static final AgentRuntimeId CTX = AgentRuntimeId.fromName("agent-a");
    private static final StepKey KEY = StepKey.of(RUN, CTX, "p0/0/a0");

    private Path root;
    private VirtualFileSystem fileSystem;
    private VfsStepResultCache cache;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        root = tempDir;
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        cache = new VfsStepResultCache(fileSystem);
    }

    private static StepOutcome outcome() {
        return StepOutcome.builder().text("answer").totalTokens(42).completionReason(CompletionReason.COMPLETED)
                .inputHash("h1").structureFingerprint("fp1").build();
    }

    @Test
    @DisplayName("constructor rejects a null file system or a blank base dir")
    void constructorValidation() {
        assertThatThrownBy(() -> new VfsStepResultCache(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VfsStepResultCache(fileSystem, "  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("save then load round-trips the outcome; evict removes it")
    void saveLoadEvict() {
        cache.save(KEY, outcome());
        assertThat(cache.load(KEY)).contains(outcome());

        cache.evict(KEY);
        assertThat(cache.load(KEY)).isEmpty();
    }

    @Test
    @DisplayName("an outcome saved on one instance loads on another over the same backend (cross-node)")
    void crossNodeReload() {
        cache.save(KEY, outcome());

        final VfsStepResultCache otherNode = new VfsStepResultCache(fileSystem);
        assertThat(otherNode.load(KEY)).contains(outcome());
    }

    @Test
    @DisplayName("an unknown key loads as empty")
    void unknownKey() {
        assertThat(cache.load(StepKey.of(RUN, CTX, "a9"))).isEmpty();
    }

    @Test
    @DisplayName("a corrupt on-disk object loads as empty (best-effort), never throws")
    void corruptObjectMisses() {
        cache.save(KEY, outcome());
        // Overwrite the exact cache object with garbage.
        fileSystem.write(pathFor(KEY), "}{ not json");

        assertThat(cache.load(KEY)).isEmpty();
    }

    @Test
    @DisplayName("a key mismatch in the envelope (hash-collision guard) loads as empty")
    void keyMismatchMisses() {
        cache.save(KEY, outcome());
        // A well-formed envelope but recording a different key must not be served for KEY.
        fileSystem.write(pathFor(KEY), "{\"v\":1,\"key\":\"run:audit/agent:agent-a/DIFFERENT\","
                + "\"outcome\":{\"fv\":1,\"text\":\"x\",\"completionReason\":\"COMPLETED\",\"inputHash\":\"h\"}}");

        assertThat(cache.load(KEY)).isEmpty();
    }

    @Test
    @DisplayName("save() honours the best-effort contract: a failing backend write never throws")
    void saveNeverThrows() {
        final VirtualFileSystem failing = mock(VirtualFileSystem.class);
        when(failing.exists(anyString())).thenReturn(false);
        doThrow(new RuntimeException("backend down")).when(failing).write(anyString(), anyString());

        final VfsStepResultCache failingCache = new VfsStepResultCache(failing);
        assertThatCode(() -> failingCache.save(KEY, outcome())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unsupported envelope version loads as empty")
    void envelopeVersionMismatchMisses() {
        fileSystem.write(pathFor(KEY), "{\"v\":99,\"key\":\"" + KEY.value() + "\","
                + "\"outcome\":{\"fv\":1,\"text\":\"x\",\"completionReason\":\"COMPLETED\",\"inputHash\":\"h\"}}");

        assertThat(cache.load(KEY)).isEmpty();
    }

    @Test
    @DisplayName("a well-formed envelope missing its outcome node loads as empty")
    void missingOutcomeNodeMisses() {
        fileSystem.write(pathFor(KEY), "{\"v\":1,\"key\":\"" + KEY.value() + "\"}");

        assertThat(cache.load(KEY)).isEmpty();
    }

    @Test
    @DisplayName("a directory squatting on the cache path loads as empty")
    void directoryAtCachePathMisses() throws Exception {
        Files.createDirectories(root.resolve(pathFor(KEY)));

        assertThat(cache.load(KEY)).isEmpty();
    }

    private static String pathFor(StepKey key) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final String hex = HexFormat.of().formatHex(digest.digest(key.value().getBytes(StandardCharsets.UTF_8)));
            return BASE_DIR + "/" + hex + ".json";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
