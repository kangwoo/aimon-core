package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryBrowserSessionStoreTest {

    private PlaywrightWorkerPool workerPool;
    private InMemoryBrowserSessionStore store;

    @BeforeEach
    void setUp() {
        workerPool = mock(PlaywrightWorkerPool.class);
        doNothing().when(workerPool).closeSessionAsync(any());
        store = new InMemoryBrowserSessionStore(3, Duration.ofMinutes(30), workerPool);
    }

    @Test
    void shouldStoreAndRetrieveSession() {
        BrowserSession session = createMockSession("bs_001", 0);
        store.put("bs_001", session);

        Optional<BrowserSession> retrieved = store.get("bs_001");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getId()).isEqualTo("bs_001");
    }

    @Test
    void shouldReturnEmptyForNonExistentSession() {
        assertThat(store.get("nonexistent")).isEmpty();
    }

    @Test
    void shouldRemoveSession() {
        BrowserSession session = createMockSession("bs_001", 0);
        store.put("bs_001", session);

        Optional<BrowserSession> removed = store.remove("bs_001");
        assertThat(removed).isPresent();
        assertThat(store.get("bs_001")).isEmpty();

        verify(workerPool).closeSessionAsync(session);
    }

    @Test
    void shouldReturnEmptyWhenRemovingNonExistentSession() {
        assertThat(store.remove("nonexistent")).isEmpty();
    }

    @Test
    void shouldEvictLruSessionWhenMaxExceeded() {
        BrowserSession s1 = createMockSession("bs_001", 0);
        BrowserSession s2 = createMockSession("bs_002", 0);
        BrowserSession s3 = createMockSession("bs_003", 0);
        BrowserSession s4 = createMockSession("bs_004", 0);

        store.put("bs_001", s1);
        store.put("bs_002", s2);
        store.put("bs_003", s3);

        // Access s1 to make it recently used
        store.get("bs_001");

        // Add s4 — should evict s2 (LRU)
        store.put("bs_004", s4);

        assertThat(store.size()).isEqualTo(3);
        assertThat(store.get("bs_001")).isPresent();
        assertThat(store.get("bs_003")).isPresent();
        assertThat(store.get("bs_004")).isPresent();

        verify(workerPool).closeSessionAsync(s2);
    }

    @Test
    void shouldEvictExpiredSession() {
        InMemoryBrowserSessionStore shortTtlStore = new InMemoryBrowserSessionStore(3, Duration.ofMillis(1),
                workerPool);

        BrowserSession session = createMockSession("bs_001", 0);
        shortTtlStore.put("bs_001", session);

        // Simulate expiration by changing the mock behavior
        org.mockito.Mockito.when(session.isExpired(any())).thenReturn(true);

        assertThat(shortTtlStore.get("bs_001")).isEmpty();
        verify(workerPool).closeSessionAsync(session);
    }

    @Test
    void shouldEvictAllExpiredSessions() {
        InMemoryBrowserSessionStore shortTtlStore = new InMemoryBrowserSessionStore(10, Duration.ofMillis(1),
                workerPool);

        BrowserSession s1 = createMockSession("bs_001", 0);
        BrowserSession s2 = createMockSession("bs_002", 0);
        shortTtlStore.put("bs_001", s1);
        shortTtlStore.put("bs_002", s2);

        // Simulate expiration by changing the mock behavior
        org.mockito.Mockito.when(s1.isExpired(any())).thenReturn(true);
        org.mockito.Mockito.when(s2.isExpired(any())).thenReturn(true);

        List<BrowserSession> evicted = shortTtlStore.evictExpired();
        assertThat(evicted).hasSize(2);
        assertThat(shortTtlStore.size()).isEqualTo(0);
    }

    @Test
    void shouldRemoveAllSessions() {
        store.put("bs_001", createMockSession("bs_001", 0));
        store.put("bs_002", createMockSession("bs_002", 0));

        List<BrowserSession> all = store.removeAll();
        assertThat(all).hasSize(2);
        assertThat(store.size()).isEqualTo(0);

        verify(workerPool, times(2)).closeSessionAsync(any());
    }

    @Test
    void shouldReturnMaxSessions() {
        assertThat(store.maxSessions()).isEqualTo(3);
    }

    @Test
    void shouldRejectInvalidMaxSessions() {
        assertThatThrownBy(() -> new InMemoryBrowserSessionStore(0, Duration.ofMinutes(30), workerPool))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BrowserSession createMockSession(String id, int workerIndex) {
        BrowserSession session = mock(BrowserSession.class);
        org.mockito.Mockito.when(session.getId()).thenReturn(id);
        org.mockito.Mockito.when(session.getWorkerIndex()).thenReturn(workerIndex);
        org.mockito.Mockito.when(session.isExpired(any())).thenReturn(false);
        org.mockito.Mockito.when(session.getCreatedAt()).thenReturn(java.time.Instant.now());
        org.mockito.Mockito.when(session.getLastUsedAt()).thenReturn(java.time.Instant.now());
        return session;
    }
}
