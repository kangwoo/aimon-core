package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 3 WI-3.4.a — verifies {@link HookConfigWatcher} debounce, polling, and lifecycle behaviour.
 */
class HookConfigWatcherTest {

    @TempDir
    Path tempDir;

    private HookConfigWatcher watcher;

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void rejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new HookConfigWatcher(null, (counter, path) -> {
        }));
        assertThatNullPointerException()
                .isThrownBy(() -> new HookConfigWatcher(List.of(tempDir.resolve("a.json")), null));
    }

    @Test
    void rejectsEmptyWatchedFiles() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HookConfigWatcher(List.of(), (counter, path) -> {
        }));
    }

    @Test
    void rejectsNonPositiveDebounce() {
        final List<Path> files = List.of(tempDir.resolve("a.json"));
        assertThatIllegalArgumentException().isThrownBy(() -> new HookConfigWatcher(files, (c, p) -> {
        }, Duration.ZERO, Duration.ofSeconds(1), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new HookConfigWatcher(files, (c, p) -> {
        }, Duration.ofSeconds(-1), Duration.ofSeconds(1), null));
    }

    @Test
    void triggerReloadFiresListenerAfterDebounce() throws Exception {
        final Path file = Files.writeString(tempDir.resolve("hooks.json"), "{}");
        final ConcurrentLinkedQueue<Long> reloads = new ConcurrentLinkedQueue<>();
        final AtomicReference<Path> capturedPath = new AtomicReference<>();

        watcher = new HookConfigWatcher(List.of(file), (counter, path) -> {
            reloads.add(counter);
            capturedPath.set(path);
        }, Duration.ofMillis(150), Duration.ofMillis(100), null);
        watcher.start();

        watcher.triggerReload(file);

        await().atMost(Duration.ofSeconds(2)).until(() -> reloads.size() == 1);
        assertThat(reloads).containsExactly(1L);
        assertThat(capturedPath.get()).isEqualTo(file);
        assertThat(watcher.getReloadCounter()).isEqualTo(1L);
    }

    @Test
    void rapidEventsCollapseIntoSingleReload() throws Exception {
        final Path file = Files.writeString(tempDir.resolve("hooks.json"), "{}");
        final AtomicInteger reloads = new AtomicInteger();

        watcher = new HookConfigWatcher(List.of(file), (counter, path) -> reloads.incrementAndGet(),
                Duration.ofMillis(300), Duration.ofMillis(50), null);
        watcher.start();

        for (int i = 0; i < 5; i++) {
            watcher.triggerReload(file);
            Thread.sleep(50);
        }

        await().atMost(Duration.ofSeconds(2)).until(() -> reloads.get() >= 1);
        Thread.sleep(400);
        assertThat(reloads.get()).isEqualTo(1);
    }

    @Test
    void fileModificationTriggersReload() throws Exception {
        final Path file = Files.writeString(tempDir.resolve("hooks.json"), "{\"version\":1}");
        // Set mtime to a known past value so the modification we make later shows a clear delta.
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(10)));

        final ConcurrentLinkedQueue<Long> reloads = new ConcurrentLinkedQueue<>();
        watcher = new HookConfigWatcher(List.of(file), (counter, path) -> reloads.add(counter), Duration.ofMillis(150),
                Duration.ofMillis(50), null);
        watcher.start();

        // Modify file: rewrite contents and bump mtime forward.
        Files.writeString(file, "{\"version\":2}");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()));

        await().atMost(Duration.ofSeconds(2)).until(() -> !reloads.isEmpty());
        assertThat(reloads).containsExactly(1L);
    }

    @Test
    void fileCreationAfterStartTriggersReload() throws Exception {
        final Path missing = tempDir.resolve("hooks.json");
        final ConcurrentLinkedQueue<Long> reloads = new ConcurrentLinkedQueue<>();

        watcher = new HookConfigWatcher(List.of(missing), (counter, path) -> reloads.add(counter),
                Duration.ofMillis(150), Duration.ofMillis(50), null);
        watcher.start();

        Files.writeString(missing, "{}");

        await().atMost(Duration.ofSeconds(2)).until(() -> !reloads.isEmpty());
        assertThat(reloads).containsExactly(1L);
    }

    @Test
    void closeIsIdempotentAndStopsListener() throws Exception {
        final Path file = Files.writeString(tempDir.resolve("hooks.json"), "{}");
        final AtomicInteger reloads = new AtomicInteger();

        watcher = new HookConfigWatcher(List.of(file), (counter, path) -> reloads.incrementAndGet(),
                Duration.ofMillis(100), Duration.ofMillis(50), null);
        watcher.start();
        watcher.close();
        watcher.close();

        watcher.triggerReload(file);
        Thread.sleep(250);
        assertThat(reloads.get()).isZero();
    }

    @Test
    void listenerExceptionDoesNotPoisonWatcher() throws Exception {
        final Path file = Files.writeString(tempDir.resolve("hooks.json"), "{}");
        final AtomicInteger reloads = new AtomicInteger();

        watcher = new HookConfigWatcher(List.of(file), (counter, path) -> {
            reloads.incrementAndGet();
            if (counter == 1L) {
                throw new RuntimeException("boom");
            }
        }, Duration.ofMillis(100), Duration.ofMillis(50), null);
        watcher.start();

        watcher.triggerReload(file);
        await().atMost(Duration.ofSeconds(2)).until(() -> reloads.get() == 1);

        watcher.triggerReload(file);
        await().atMost(Duration.ofSeconds(2)).until(() -> reloads.get() == 2);
        assertThat(watcher.getReloadCounter()).isEqualTo(2L);
    }

    @Test
    void triggerReloadRejectsUnwatchedPath() throws Exception {
        final Path file = Files.writeString(tempDir.resolve("hooks.json"), "{}");
        final Path other = tempDir.resolve("other.json");
        watcher = new HookConfigWatcher(List.of(file), (counter, path) -> {
        }, Duration.ofMillis(100), Duration.ofMillis(50), null);
        watcher.start();

        assertThatIllegalArgumentException().isThrownBy(() -> watcher.triggerReload(other));
    }
}
