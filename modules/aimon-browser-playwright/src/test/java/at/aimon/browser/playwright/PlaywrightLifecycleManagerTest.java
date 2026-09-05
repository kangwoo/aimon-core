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
     * 이 검증이 {@code @Tag("playwright")} 없이 팩토리를 직접 겨누는 이유는, 태그가 붙으면 기본
     * {@code test} 태스크에서 제외되기 때문이다. 그 제외가 뜻하는 것은 2026-09-05 에 바뀌었다 —
     * {@code playwrightTest} 가 CI 스텝이자 릴리스 게이트 태스크가 되었으므로 태그를 붙여도 CI 는
     * 이제 이것을 돈다. 그래도 태그를 붙이지 않는 이유는 남는다: 이 단언은 Chromium 없이 성립하고,
     * 브라우저 캐시가 없는 기계에서도 {@code ./gradlew test} 하나로 돈다. 아래 태그된 넷은 그렇지 않다.
     *
     * <p>
     * 그리고 이 문단이 원래 적고 있던 이유("태그를 붙이면 CI 가 회귀를 잡지 못한다")는 당시에도
     * 실제보다 약했다. 그때 {@code playwrightTest} 는 어느 게이트에도 없었을 뿐 아니라 <b>아무것도
     * 실행하지 않았다</b> — 태스크에 {@code testClassesDirs} 와 {@code classpath} 가 없어
     * {@code NO-SOURCE} 로 통과했다. 즉 태그를 붙였다면 CI 가 못 잡는 정도가 아니라 아무 데서도
     * 돌지 않았을 것이다.
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
