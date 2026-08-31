package at.aimon.browser.playwright;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory 브라우저 세션 저장소 (LRU + TTL).
 *
 * <p>
 * {@link InMemoryBrowserSessionStore}는 세션을 Map에서 제거만 하고,
 * 실제 Playwright 리소스 해제는 {@link PlaywrightWorkerPool}에 위임한다.
 *
 * <p>
 * {@link LinkedHashMap}의 access-order 모드로 LRU 동작을 구현한다.
 * 모든 연산은 {@code synchronized}로 스레드 안전성을 보장한다.
 */
public class InMemoryBrowserSessionStore implements BrowserSessionStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBrowserSessionStore.class);

    private final Map<String, BrowserSession> sessions;
    private final int maxSessions;
    private final Duration sessionTtl;
    private final PlaywrightWorkerPool workerPool;

    /**
     * InMemoryBrowserSessionStore를 생성한다.
     *
     * @param maxSessions
     *            최대 세션 수 (1 이상)
     * @param sessionTtl
     *            세션 TTL (비활동 만료 시간)
     * @param workerPool
     *            Playwright Worker Pool (세션 종료 시 리소스 해제용)
     * @throws IllegalArgumentException
     *             maxSessions가 1 미만인 경우
     */
    public InMemoryBrowserSessionStore(int maxSessions, Duration sessionTtl, PlaywrightWorkerPool workerPool) {
        if (maxSessions < 1) {
            throw new IllegalArgumentException("maxSessions must be > 0");
        }
        this.maxSessions = maxSessions;
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl cannot be null");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool cannot be null");

        // LRU 순서 유지. removeEldestEntry에서는 Map 제거만 수행하고
        // Playwright 리소스 해제는 workerPool에 위임한다.
        this.sessions = new LinkedHashMap<>(maxSessions, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BrowserSession> eldest) {
                if (size() > InMemoryBrowserSessionStore.this.maxSessions) {
                    log.info("Evicting LRU session: {}", eldest.getKey());
                    safeCloseSession(eldest.getValue());
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public synchronized void put(String sessionId, BrowserSession session) {
        sessions.put(sessionId, session);
    }

    @Override
    public synchronized boolean isFull() {
        evictExpired();
        return sessions.size() >= maxSessions;
    }

    @Override
    public synchronized Optional<BrowserSession> get(String sessionId) {
        BrowserSession session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.isExpired(sessionTtl)) {
            log.debug("Session expired: {}", sessionId);
            sessions.remove(sessionId);
            safeCloseSession(session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    @Override
    public synchronized Optional<BrowserSession> remove(String sessionId) {
        BrowserSession session = sessions.remove(sessionId);
        if (session != null) {
            safeCloseSession(session);
            return Optional.of(session);
        }
        return Optional.empty();
    }

    @Override
    public synchronized int size() {
        return sessions.size();
    }

    @Override
    public int maxSessions() {
        return maxSessions;
    }

    @Override
    public synchronized List<BrowserSession> evictExpired() {
        List<BrowserSession> evicted = new ArrayList<>();
        Iterator<Map.Entry<String, BrowserSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BrowserSession> entry = it.next();
            if (entry.getValue().isExpired(sessionTtl)) {
                evicted.add(entry.getValue());
                it.remove();
            }
        }
        if (!evicted.isEmpty()) {
            log.info("Evicted {} expired sessions", evicted.size());
            evicted.forEach(this::safeCloseSession);
        }
        return evicted;
    }

    @Override
    public synchronized List<BrowserSession> removeAll() {
        List<BrowserSession> all = new ArrayList<>(sessions.values());
        sessions.clear();
        all.forEach(this::safeCloseSession);
        return all;
    }

    @Override
    public void close() {
        removeAll();
    }

    /**
     * 세션의 Playwright 리소스를 안전하게 해제한다.
     * Worker가 shutdown 중일 때 RejectedExecutionException이 발생할 수 있으므로
     * 예외를 흡수한다.
     */
    private void safeCloseSession(BrowserSession session) {
        try {
            workerPool.closeSessionAsync(session);
        } catch (Exception e) {
            log.warn("Failed to close session {} (worker may be shutting down): {}", session.getId(), e.getMessage());
        }
    }
}
