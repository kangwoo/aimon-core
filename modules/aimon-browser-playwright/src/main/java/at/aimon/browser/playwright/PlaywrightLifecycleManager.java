package at.aimon.browser.playwright;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

/**
 * 단일 Playwright Worker의 생명주기를 관리한다.
 *
 * <p>
 * Playwright Java API는 스레드 안전하지 않으므로, 모든 Playwright 호출은
 * 단일 전용 스레드({@code playwrightExecutor})에서 실행한다.
 *
 * <p>
 * 이 클래스가 Playwright/Browser/ExecutorService를 소유하며,
 * {@link #shutdown()} 호출 시 역순으로 정리한다.
 *
 * <p>
 * 단일 인스턴스로 사용하면 모든 세션의 요청이 직렬화된다.
 * {@link PlaywrightWorkerPool}이 여러 인스턴스를 관리하여
 * 세션 간 병렬 처리를 지원한다.
 *
 * <p>
 * 로컬 Chromium 실행 외에 원격 Playwright Server(WebSocket) 및
 * Chrome DevTools Protocol(CDP) 연결도 지원한다.
 *
 * @see PlaywrightConnectionConfig
 * @see PlaywrightConnectionMode
 */
public class PlaywrightLifecycleManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightLifecycleManager.class);

    private final PlaywrightConnectionConfig config;
    private final ExecutorService playwrightExecutor;
    private final Playwright playwright;
    private final Browser browser;

    /**
     * Playwright 초기화 결과를 담는 타입 안전 holder.
     */
    private static class PlaywrightInitResult {

        final Playwright playwright;
        final Browser browser;

        PlaywrightInitResult(Playwright playwright, Browser browser) {
            this.playwright = playwright;
            this.browser = browser;
        }
    }

    /**
     * 연결 설정 기반으로 Playwright 환경을 초기화한다.
     * 반드시 playwrightExecutor 스레드 내에서 Playwright.create()와
     * 브라우저 연결을 실행하여 스레드 소유권을 보장한다.
     *
     * @param config
     *            Playwright 연결 설정
     * @param workerIndex
     *            Worker 식별 인덱스 (스레드 이름에 사용)
     * @throws NullPointerException
     *             config가 null인 경우
     * @throws IllegalStateException
     *             Playwright 초기화에 실패한 경우
     */
    public PlaywrightLifecycleManager(PlaywrightConnectionConfig config, int workerIndex) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.playwrightExecutor = Executors.newSingleThreadExecutor(workerThreadFactory(workerIndex));

        try {
            // Playwright 스레드 내에서 초기화 수행
            Future<PlaywrightInitResult> future = playwrightExecutor.submit(() -> {
                Playwright pw = Playwright.create();
                try {
                    Browser br = connectBrowser(pw, config);
                    return new PlaywrightInitResult(pw, br);
                } catch (Exception e) {
                    pw.close();
                    throw e;
                }
            });

            PlaywrightInitResult result = future.get(30, TimeUnit.SECONDS);
            this.playwright = result.playwright;
            this.browser = result.browser;

            if (config.getMode().isRemote()) {
                log.info("Connected to remote browser via {} at {}", config.getMode(), config.getEndpoint());
            }

        } catch (Exception e) {
            playwrightExecutor.shutdownNow();
            throw new IllegalStateException("Failed to initialize Playwright worker " + workerIndex, e);
        }
    }

    /**
     * Worker 스레드를 만드는 팩토리. 스레드는 <b>데몬</b>이다.
     *
     * <p>
     * 데몬이 아니면 JVM 이 종료되지 않는 경로가 둘 있다 — 생성자에서
     * {@code Playwright.create()} 가 네이티브 프레임에 멈춰 인터럽트가 닿지 않는 경우(:98 의
     * 30초 타임아웃이 터진 뒤에도 스레드는 살아 있다), 그리고 소비자가 {@link #shutdown()} 을
     * 부르지 않고 이 객체를 버리는 경우다. 둘 다 유일한 정리 경로가 이미 실패한 상황이므로
     * 스레드를 붙잡아 두어도 얻는 것이 없다.
     *
     * <p>
     * 대가는 있다 — JVM 이 호출 도중 그냥 끝날 수 있으므로 {@code browser.close()} /
     * {@code playwright.close()} 가 건너뛰어지고 외부 node driver·Chromium 프로세스가 고아로
     * 남을 수 있다. 고아 프로세스는 회수 가능하지만 멈춘 JVM 은 그렇지 않으므로 이쪽을 택했다.
     * 정상 종료 경로에서는 {@link #shutdown()} 이 여전히 먼저 정리한다.
     *
     * <p>
     * 별도 메서드로 뽑아 둔 것은 실제 Chromium 없이 단위 테스트에서 검증하기 위해서다.
     *
     * @param workerIndex
     *            Worker 식별 인덱스 (스레드 이름에 사용)
     * @return 데몬 스레드를 만드는 팩토리
     */
    static ThreadFactory workerThreadFactory(int workerIndex) {
        return r -> {
            final Thread thread = new Thread(r, "browser-playwright-" + workerIndex);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Playwright 환경을 초기화한다. 반드시 playwrightExecutor 스레드 내에서
     * Playwright.create()와 browser.launch()를 실행하여 스레드 소유권을 보장한다.
     *
     * @param headless
     *            Chromium headless 모드 여부 (기본 true)
     * @param workerIndex
     *            Worker 식별 인덱스 (스레드 이름에 사용)
     */
    public PlaywrightLifecycleManager(boolean headless, int workerIndex) {
        this(PlaywrightConnectionConfig.local(headless), workerIndex);
    }

    /**
     * 연결 모드에 따라 브라우저에 연결한다.
     */
    private static Browser connectBrowser(Playwright pw, PlaywrightConnectionConfig config) {
        return switch (config.getMode()) {
            case LOCAL -> pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(config.isHeadless()));
            case REMOTE_WS -> pw.chromium().connect(config.getEndpoint());
            case REMOTE_CDP -> pw.chromium().connectOverCDP(config.getEndpoint());
        };
    }

    /**
     * Playwright 스레드에서 작업을 실행하고 결과를 반환한다.
     *
     * @param task
     *            실행할 작업
     * @param timeoutMs
     *            타임아웃 (밀리초)
     * @param <T>
     *            반환 타입
     * @return 작업 결과
     * @throws ExecutionException
     *             작업 실행 중 예외 발생 시
     * @throws TimeoutException
     *             타임아웃 초과 시
     * @throws InterruptedException
     *             스레드 인터럽트 시
     */
    public <T> T executeOnPlaywrightThread(Callable<T> task, int timeoutMs)
            throws ExecutionException, TimeoutException, InterruptedException {
        Future<T> future = playwrightExecutor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /**
     * 새 BrowserContext를 생성한다. 반드시 Playwright 스레드에서 호출해야 한다.
     *
     * @param options
     *            컨텍스트 옵션
     * @return 새 BrowserContext
     */
    public BrowserContext createContext(Browser.NewContextOptions options) {
        return browser.newContext(options);
    }

    /**
     * 세션의 리소스를 Playwright 스레드에서 해제한다.
     * 외부 스레드에서 직접 context.close()를 호출하면 안 된다.
     *
     * @param session
     *            닫을 세션
     */
    public void closeSessionAsync(BrowserSession session) {
        playwrightExecutor.submit(() -> {
            try {
                session.closeInternal();
            } catch (Exception e) {
                log.debug("Error closing session {} (may already be closed): {}", session.getId(), e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * 모든 리소스를 정리한다.
     * 호출 순서: executor에 browser/playwright 종료 제출 -> executor 종료
     */
    public void shutdown() {
        if (config.getMode().isRemote()) {
            log.debug("Disconnecting from remote browser ({})", config.getMode());
        }

        playwrightExecutor.submit(() -> {
            try {
                browser.close();
            } catch (Exception e) {
                log.debug("Error closing browser: {}", e.getMessage());
            }
            try {
                playwright.close();
            } catch (Exception e) {
                log.debug("Error closing playwright: {}", e.getMessage());
            }
        });

        playwrightExecutor.shutdown();
        try {
            if (!playwrightExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Playwright executor did not terminate in time, forcing shutdown");
                playwrightExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            playwrightExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
