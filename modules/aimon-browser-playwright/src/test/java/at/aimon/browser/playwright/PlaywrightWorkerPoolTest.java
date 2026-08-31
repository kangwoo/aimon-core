package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlaywrightWorkerPoolTest {

    // --- Public constructor validation ---

    @Test
    void shouldThrowNpeWhenConfigIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PlaywrightWorkerPool(1, (PlaywrightConnectionConfig) null))
                .withMessageContaining("Config");
    }

    @Test
    void shouldThrowIaeWhenWorkerCountLessThanOne() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.local(true);
        assertThatIllegalArgumentException().isThrownBy(() -> new PlaywrightWorkerPool(0, config))
                .withMessageContaining("workerCount");
    }

    // --- Package-private constructor validation ---

    @Test
    void shouldThrowNpeWhenWorkersListIsNull() {
        assertThatNullPointerException().isThrownBy(() -> new PlaywrightWorkerPool(null))
                .withMessageContaining("Workers list");
    }

    @Test
    void shouldThrowIaeWhenWorkersListIsEmpty() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PlaywrightWorkerPool(Collections.emptyList()))
                .withMessageContaining("Workers list cannot be empty");
    }

    // --- selectWorker() round-robin ---

    @Test
    void shouldSelectWorkersInRoundRobin() {
        PlaywrightLifecycleManager w0 = mock(PlaywrightLifecycleManager.class);
        PlaywrightLifecycleManager w1 = mock(PlaywrightLifecycleManager.class);
        PlaywrightLifecycleManager w2 = mock(PlaywrightLifecycleManager.class);

        PlaywrightWorkerPool pool = new PlaywrightWorkerPool(List.of(w0, w1, w2));

        assertThat(pool.selectWorker()).isEqualTo(0);
        assertThat(pool.selectWorker()).isEqualTo(1);
        assertThat(pool.selectWorker()).isEqualTo(2);
        assertThat(pool.selectWorker()).isEqualTo(0);
        assertThat(pool.selectWorker()).isEqualTo(1);
        assertThat(pool.selectWorker()).isEqualTo(2);
    }

    // --- closeSessionAsync() ---

    @Test
    void shouldDelegateCloseToCorrectWorker() {
        PlaywrightLifecycleManager w0 = mock(PlaywrightLifecycleManager.class);
        PlaywrightLifecycleManager w1 = mock(PlaywrightLifecycleManager.class);
        PlaywrightWorkerPool pool = new PlaywrightWorkerPool(List.of(w0, w1));

        BrowserSession session = mock(BrowserSession.class);
        when(session.getWorkerIndex()).thenReturn(1);

        pool.closeSessionAsync(session);

        verify(w1).closeSessionAsync(session);
    }

    @Test
    void shouldIgnoreInvalidWorkerIndexOnClose() {
        PlaywrightLifecycleManager w0 = mock(PlaywrightLifecycleManager.class);
        PlaywrightWorkerPool pool = new PlaywrightWorkerPool(List.of(w0));

        BrowserSession session = mock(BrowserSession.class);
        when(session.getWorkerIndex()).thenReturn(5);
        when(session.getId()).thenReturn("bs_invalid");

        // Should not throw
        pool.closeSessionAsync(session);
    }

    @Test
    void shouldIgnoreNegativeWorkerIndexOnClose() {
        PlaywrightLifecycleManager w0 = mock(PlaywrightLifecycleManager.class);
        PlaywrightWorkerPool pool = new PlaywrightWorkerPool(List.of(w0));

        BrowserSession session = mock(BrowserSession.class);
        when(session.getWorkerIndex()).thenReturn(-1);
        when(session.getId()).thenReturn("bs_negative");

        // Should not throw
        pool.closeSessionAsync(session);
    }

    // --- shutdown() ---

    @Test
    void shouldShutdownAllWorkers() {
        PlaywrightLifecycleManager w0 = mock(PlaywrightLifecycleManager.class);
        PlaywrightLifecycleManager w1 = mock(PlaywrightLifecycleManager.class);
        PlaywrightLifecycleManager w2 = mock(PlaywrightLifecycleManager.class);
        PlaywrightWorkerPool pool = new PlaywrightWorkerPool(List.of(w0, w1, w2));

        pool.shutdown();

        verify(w0).shutdown();
        verify(w1).shutdown();
        verify(w2).shutdown();
    }

    // --- workerCount() ---

    @Test
    void shouldReturnCorrectWorkerCount() {
        PlaywrightLifecycleManager w0 = mock(PlaywrightLifecycleManager.class);
        PlaywrightLifecycleManager w1 = mock(PlaywrightLifecycleManager.class);
        PlaywrightWorkerPool pool = new PlaywrightWorkerPool(List.of(w0, w1));

        assertThat(pool.workerCount()).isEqualTo(2);
    }
}
