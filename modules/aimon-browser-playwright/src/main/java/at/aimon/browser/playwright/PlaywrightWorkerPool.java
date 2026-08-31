package at.aimon.browser.playwright;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;

/**
 * Playwright Worker Pool.
 *
 * <p>
 * N개의 {@link PlaywrightLifecycleManager}를 관리하여
 * 세션 간 병렬 처리를 지원한다. 각 Worker는 독립된 Playwright 인스턴스와
 * 전용 스레드를 소유하므로 Playwright의 스레드 안전성 제약을 준수한다.
 *
 * <p>
 * 세션은 생성 시 특정 Worker에 고정(affinity)된다.
 * 동일 세션의 모든 요청은 항상 같은 Worker 스레드에서 실행되어
 * 액션 순서가 보장된다.
 *
 * <p>
 * 서로 다른 세션의 요청은 서로 다른 Worker에서 병렬로 실행될 수 있으므로,
 * 세션 A의 느린 페이지 로딩이 다른 Worker에 할당된 세션 B를 차단하지 않는다.
 *
 * @see PlaywrightConnectionConfig
 */
public class PlaywrightWorkerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightWorkerPool.class);

    private final List<PlaywrightLifecycleManager> workers;
    private final AtomicInteger nextWorkerIndex = new AtomicInteger(0);

    /**
     * 연결 설정 기반으로 Worker Pool을 초기화한다.
     *
     * @param workerCount
     *            Worker 수 (1 이상). 로컬 모드에서는 각 Worker가 독립된
     *            Chromium 프로세스를 실행하므로 Worker당 약 200-500MB 메모리를 사용한다.
     * @param config
     *            Playwright 연결 설정
     * @throws IllegalArgumentException
     *             workerCount가 1 미만인 경우
     * @throws NullPointerException
     *             config가 null인 경우
     */
    public PlaywrightWorkerPool(int workerCount, PlaywrightConnectionConfig config) {
        Objects.requireNonNull(config, "Config cannot be null");
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be >= 1");
        }

        this.workers = new ArrayList<>(workerCount);
        try {
            for (int i = 0; i < workerCount; i++) {
                workers.add(new PlaywrightLifecycleManager(config, i));
            }
        } catch (Exception e) {
            // 부분 초기화된 Worker들을 정리한다
            workers.forEach(PlaywrightLifecycleManager::shutdown);
            throw e;
        }

        log.info("Initialized PlaywrightWorkerPool with {} workers (mode={})", workerCount, config.getMode());
    }

    /**
     * 사전 생성된 Worker 리스트로 Worker Pool을 초기화한다.
     * 테스트에서 mock Worker를 주입하기 위한 패키지 프라이빗 생성자이다.
     *
     * @param workers
     *            Worker 리스트 (비어 있지 않아야 함)
     * @throws NullPointerException
     *             workers가 null인 경우
     * @throws IllegalArgumentException
     *             workers가 비어 있는 경우
     */
    PlaywrightWorkerPool(List<PlaywrightLifecycleManager> workers) {
        Objects.requireNonNull(workers, "Workers list cannot be null");
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("Workers list cannot be empty");
        }
        this.workers = new ArrayList<>(workers);
    }

    /**
     * Worker Pool을 초기화한다.
     *
     * @param workerCount
     *            Worker 수 (1 이상). 각 Worker가 독립된 Chromium 프로세스를
     *            실행하므로 Worker당 약 200-500MB 메모리를 사용한다.
     * @param headless
     *            Chromium headless 모드 여부
     * @throws IllegalArgumentException
     *             workerCount가 1 미만인 경우
     */
    public PlaywrightWorkerPool(int workerCount, boolean headless) {
        this(workerCount, PlaywrightConnectionConfig.local(headless));
    }

    /**
     * 다음 세션을 할당할 Worker 인덱스를 선택한다.
     * Round-robin 방식으로 Worker 간 부하를 균등 분배한다.
     *
     * @return Worker 인덱스
     */
    public int selectWorker() {
        return nextWorkerIndex.getAndUpdate(i -> (i + 1) % workers.size());
    }

    /**
     * 특정 Worker에서 작업을 실행한다.
     *
     * @param workerIndex
     *            Worker 인덱스 (BrowserSession.getWorkerIndex()로 조회)
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
    public <T> T executeOnWorker(int workerIndex, Callable<T> task, int timeoutMs)
            throws ExecutionException, TimeoutException, InterruptedException {
        return workers.get(workerIndex).executeOnPlaywrightThread(task, timeoutMs);
    }

    /**
     * 특정 Worker에서 BrowserContext를 생성한다.
     * 반드시 해당 Worker의 Playwright 스레드에서 호출해야 한다.
     *
     * @param workerIndex
     *            Worker 인덱스
     * @param options
     *            컨텍스트 옵션
     * @return 새 BrowserContext
     */
    public BrowserContext createContext(int workerIndex, Browser.NewContextOptions options) {
        return workers.get(workerIndex).createContext(options);
    }

    /**
     * 세션의 리소스를 해당 Worker의 스레드에서 비동기로 해제한다.
     *
     * <p>
     * Worker의 executor가 이미 종료된 경우(shutdown 중)
     * RejectedExecutionException이 발생할 수 있으므로 호출자가 예외를 처리해야 한다.
     *
     * @param session
     *            닫을 세션
     */
    public void closeSessionAsync(BrowserSession session) {
        int idx = session.getWorkerIndex();
        if (idx >= 0 && idx < workers.size()) {
            workers.get(idx).closeSessionAsync(session);
        } else {
            log.warn("Invalid worker index {} for session {}, skipping close", idx, session.getId());
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * 모든 Worker를 종료한다.
     */
    public void shutdown() {
        for (int i = 0; i < workers.size(); i++) {
            log.debug("Shutting down worker {}", i);
            workers.get(i).shutdown();
        }
    }

    /**
     * Worker 수를 반환한다.
     *
     * @return Worker 수
     */
    public int workerCount() {
        return workers.size();
    }
}
