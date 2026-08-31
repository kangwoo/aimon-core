package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PlaywrightLifecycleManagerTest {

    // --- Unit test: null config validation ---

    @Test
    void shouldThrowNpeWhenConfigIsNull() {
        assertThatNullPointerException().isThrownBy(() -> new PlaywrightLifecycleManager(null, 0))
                .withMessageContaining("Config");
    }

    // --- Unit test: worker thread factory ---

    /**
     * Worker 스레드가 데몬이어야 한다. 데몬이 아니면 {@code shutdown()} 이 불리지 않은
     * (또는 네이티브 프레임에서 인터럽트가 닿지 않은) 매니저 하나가 JVM 종료를 영구히 막는다.
     *
     * <p>
     * 이 검증이 {@code @Tag("playwright")} 없이 팩토리를 직접 겨누는 이유는, 태그가 붙으면
     * 기본 {@code test} 태스크에서 제외되어 실제 Chromium 이 있는 환경에서만 돌기 때문이다 —
     * 그러면 CI 가 회귀를 잡지 못한다.
     */
    @Test
    void workerThreadsShouldBeDaemons() {
        Thread thread = PlaywrightLifecycleManager.workerThreadFactory(3).newThread(() -> {
        });

        assertThat(thread.isDaemon()).isTrue();
        assertThat(thread.getName()).isEqualTo("browser-playwright-3");
    }

    // --- Integration tests requiring Playwright runtime ---

    @Test
    @Tag("playwright")
    void shouldInitializeLocalHeadless() {
        try (PlaywrightLifecycleManager manager = new PlaywrightLifecycleManager(true, 0)) {
            assertThat(manager).isNotNull();
        }
    }

    @Test
    @Tag("playwright")
    void shouldExecuteOnPlaywrightThread() throws Exception {
        try (PlaywrightLifecycleManager manager = new PlaywrightLifecycleManager(true, 0)) {
            String threadName = manager.executeOnPlaywrightThread(() -> Thread.currentThread().getName(), 5000);
            assertThat(threadName).startsWith("browser-playwright-");
            assertThat(manager.executeOnPlaywrightThread(() -> Thread.currentThread().isDaemon(), 5000)).isTrue();
        }
    }

    @Test
    @Tag("playwright")
    void shouldCreateContext() throws Exception {
        try (PlaywrightLifecycleManager manager = new PlaywrightLifecycleManager(true, 0)) {
            var context = manager.executeOnPlaywrightThread(() -> manager.createContext(null), 5000);
            assertThat(context).isNotNull();
            manager.executeOnPlaywrightThread(() -> {
                context.close();
                return null;
            }, 5000);
        }
    }

    @Test
    @Tag("playwright")
    void shouldShutdownGracefully() {
        PlaywrightLifecycleManager manager = new PlaywrightLifecycleManager(true, 0);
        // Should not throw
        manager.shutdown();
    }
}
