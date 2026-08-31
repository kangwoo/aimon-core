package at.aimon.core.hook.execution;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 훅 실행 정책 (per-hook). per-hook timeout과 timeout 동작 모드를 포함한다.
 *
 * <p>
 * <b>Timeout</b>: 각 훅 호출의 wall-clock 상한. 기본값 {@link #DEFAULT_TIMEOUT}.
 *
 * <p>
 * <b>TimeoutBehavior</b>: 타임아웃 발생 시 정책. 기본값 {@link TimeoutBehavior#FAIL_OPEN} — 운영 안전성을 위해 타임아웃을 SUCCESS 로 처리해 다음 훅으로
 * 진행한다. 보안 민감 훅은 {@link TimeoutBehavior#FAIL_CLOSED} 로 BLOCKED 처리할 수 있다.
 */
public final class HookExecutionPolicy {

    private static final Logger log = LoggerFactory.getLogger(HookExecutionPolicy.class);

    /** 기본 per-hook timeout (30 초). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Slack added on top of a hook-declared budget when computing {@link #timeoutFor(ExecutionHook)}, covering the
     * teardown and result mapping that follow the hook's own deadline.
     */
    public static final Duration DECLARED_BUDGET_GRACE = Duration.ofSeconds(5);

    /**
     * Hard ceiling on a hook-declared budget (10 minutes).
     *
     * <p>
     * A declared budget arrives from configuration ({@code timeoutMs} in {@code hooks.json} / skill frontmatter) and is
     * otherwise unvalidated, so an absurd value would let a single hook stall an agent turn indefinitely — and a value
     * near {@link Long#MAX_VALUE} would additionally overflow {@link Duration#toNanos()} in the executor. Clamping here
     * keeps the outer net finite no matter what the config says.
     */
    public static final Duration MAX_DECLARED_BUDGET = Duration.ofMinutes(10);

    /** 예외 시 계속 진행하고 차단하지 않는 정책을 생성한다. */
    public static HookExecutionPolicy continueOnExceptionAndNeverStop() {
        return new HookExecutionPolicy(false, e -> HookResult.success(), DEFAULT_TIMEOUT, TimeoutBehavior.FAIL_OPEN,
                ExecutionMode.SEQUENTIAL);
    }

    /** 예외 시 계속 진행하되 차단 시 중단하는 정책을 생성한다. */
    public static HookExecutionPolicy continueOnExceptionButStopOnBlocked() {
        return new HookExecutionPolicy(true, e -> HookResult.success(), DEFAULT_TIMEOUT, TimeoutBehavior.FAIL_OPEN,
                ExecutionMode.SEQUENTIAL);
    }

    /** 예외 시 차단 처리하고 실행을 중단하는 보수적 정책을 생성한다. */
    public static HookExecutionPolicy failClosedStopOnBlocked() {
        // 보수적으로: 예외면 block 처리하고 실행 중단
        return new HookExecutionPolicy(true, e -> HookResult.block("Hook execution failed: " + e.getMessage()),
                DEFAULT_TIMEOUT, TimeoutBehavior.FAIL_CLOSED, ExecutionMode.SEQUENTIAL);
    }

    private final boolean stopOnBlocked;
    private final ExceptionMapper exceptionMapper;
    private final Duration timeout;
    private final TimeoutBehavior timeoutBehavior;
    private final ExecutionMode executionMode;
    private final DedupKeyExtractor dedupKeyExtractor;

    /** HookExecutionPolicy를 생성한다 (기본 timeout/behavior/mode 사용). */
    public HookExecutionPolicy(boolean stopOnBlocked, ExceptionMapper exceptionMapper) {
        this(stopOnBlocked, exceptionMapper, DEFAULT_TIMEOUT, TimeoutBehavior.FAIL_OPEN, ExecutionMode.SEQUENTIAL);
    }

    /** HookExecutionPolicy를 생성한다 (timeout/behavior 명시, mode 기본 SEQUENTIAL). */
    public HookExecutionPolicy(boolean stopOnBlocked, ExceptionMapper exceptionMapper, Duration timeout,
            TimeoutBehavior timeoutBehavior) {
        this(stopOnBlocked, exceptionMapper, timeout, timeoutBehavior, ExecutionMode.SEQUENTIAL);
    }

    /** HookExecutionPolicy를 생성한다 (dedup 없는 변형). */
    public HookExecutionPolicy(boolean stopOnBlocked, ExceptionMapper exceptionMapper, Duration timeout,
            TimeoutBehavior timeoutBehavior, ExecutionMode executionMode) {
        this(stopOnBlocked, exceptionMapper, timeout, timeoutBehavior, executionMode, NO_DEDUP);
    }

    /** HookExecutionPolicy를 생성한다 (전 필드 명시). */
    public HookExecutionPolicy(boolean stopOnBlocked, ExceptionMapper exceptionMapper, Duration timeout,
            TimeoutBehavior timeoutBehavior, ExecutionMode executionMode, DedupKeyExtractor dedupKeyExtractor) {
        this.stopOnBlocked = stopOnBlocked;
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper cannot be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout cannot be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
        }
        this.timeoutBehavior = Objects.requireNonNull(timeoutBehavior, "timeoutBehavior cannot be null");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode cannot be null");
        this.dedupKeyExtractor = Objects.requireNonNull(dedupKeyExtractor, "dedupKeyExtractor cannot be null");
    }

    /** Default no-op dedup extractor (returns null for every hook → no dedup applied). */
    private static final DedupKeyExtractor NO_DEDUP = h -> null;

    /**
     * 차단 시 중단 여부를 반환한다.
     *
     * <p>
     * <b>Note.</b> This flag only takes effect under {@link ExecutionMode#SEQUENTIAL}. Under
     * {@link ExecutionMode#PARALLEL} the executor cannot cancel hooks that have already been launched, so the value is
     * a no-op &mdash; every parallel hook is awaited regardless. See {@code DefaultHookExecutor} for the contract.
     */
    public boolean stopOnBlocked() {
        return stopOnBlocked;
    }

    /** 예외 발생 시 처리 결과를 반환한다. */
    public HookResult onException(Exception e) {
        return exceptionMapper.map(e);
    }

    /** Per-hook 실행 timeout. */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the timeout the executor must enforce for one specific hook.
     *
     * <p>
     * This is {@link #timeout()} widened to accommodate a hook that declares a longer budget of its own via
     * {@link ExecutionHook#getExecutionBudget()} — a {@code timeoutMs} from {@code hooks.json} / skill frontmatter, for
     * instance. Such a hook enforces its own deadline internally and maps it to a proper {@link HookResult}; the policy
     * timeout is only the outer net that catches a hook which fails to. The net therefore gets
     * {@link #DECLARED_BUDGET_GRACE} of slack on top of the declared budget, so the hook's graceful path always wins
     * the race.
     *
     * <p>
     * The grace applies as soon as the declared budget <i>reaches</i> the policy timeout, not only when it exceeds it.
     * The equality case is the common one, not a corner case: {@code ShellAction.DEFAULT_TIMEOUT} and
     * {@link #DEFAULT_TIMEOUT} are both 30s, so every declarative shell hook that omits {@code timeoutMs} declares
     * exactly the policy timeout. Denying it the grace would leave the net racing the hook's own deadline in precisely
     * the default configuration.
     *
     * <p>
     * A declared budget <i>shorter</i> than the policy timeout is ignored — narrowing the net would only race the
     * hook's own deadline without ever finishing sooner. A null, zero or negative budget counts as "nothing declared":
     * a zero budget is not a meaningful floor.
     *
     * <p>
     * The declared budget is clamped to {@link #MAX_DECLARED_BUDGET} before the grace is added, so misconfiguration
     * cannot stall a turn forever (and cannot overflow the executor's nanosecond conversion).
     *
     * @param hook
     *            the hook about to be invoked (must not be null)
     * @return the effective per-hook timeout (never null, always positive)
     * @throws NullPointerException
     *             if hook is null
     */
    public Duration timeoutFor(ExecutionHook<?> hook) {
        Objects.requireNonNull(hook, "hook cannot be null");
        final Duration declared = hook.getExecutionBudget().orElse(null);
        if (declared == null || declared.isNegative() || declared.isZero()) {
            return timeout;
        }
        Duration effective = declared;
        if (effective.compareTo(MAX_DECLARED_BUDGET) > 0) {
            log.warn("Hook declared an execution budget of {} exceeding the maximum {}; clamping. hookId={}", declared,
                    MAX_DECLARED_BUDGET, hook.getHookId());
            effective = MAX_DECLARED_BUDGET;
        }
        if (effective.compareTo(timeout) < 0) {
            return timeout;
        }
        return effective.plus(DECLARED_BUDGET_GRACE);
    }

    /** Timeout 발생 시 동작 모드. */
    public TimeoutBehavior timeoutBehavior() {
        return timeoutBehavior;
    }

    /** 실행 모드. */
    public ExecutionMode executionMode() {
        return executionMode;
    }

    /**
     * Timeout 만 변경한 새 정책을 반환한다 (기존 정책의 다른 속성은 보존).
     *
     * @param newTimeout
     *            새 timeout (must not be null, must be positive)
     * @return 새 정책 (never null)
     */
    public HookExecutionPolicy withTimeout(Duration newTimeout) {
        return new HookExecutionPolicy(stopOnBlocked, exceptionMapper, newTimeout, timeoutBehavior, executionMode,
                dedupKeyExtractor);
    }

    /**
     * TimeoutBehavior 만 변경한 새 정책을 반환한다 (기존 정책의 다른 속성은 보존).
     *
     * @param newBehavior
     *            새 timeout 동작 (must not be null)
     * @return 새 정책 (never null)
     */
    public HookExecutionPolicy withTimeoutBehavior(TimeoutBehavior newBehavior) {
        return new HookExecutionPolicy(stopOnBlocked, exceptionMapper, timeout, newBehavior, executionMode,
                dedupKeyExtractor);
    }

    /**
     * ExecutionMode 만 변경한 새 정책을 반환한다.
     *
     * @param newMode
     *            새 실행 모드 (must not be null)
     * @return 새 정책 (never null)
     */
    public HookExecutionPolicy withExecutionMode(ExecutionMode newMode) {
        return new HookExecutionPolicy(stopOnBlocked, exceptionMapper, timeout, timeoutBehavior, newMode,
                dedupKeyExtractor);
    }

    /** Dedup-key extractor 를 반환한다. */
    public DedupKeyExtractor dedupKeyExtractor() {
        return dedupKeyExtractor;
    }

    /**
     * DedupKeyExtractor 만 변경한 새 정책을 반환한다.
     *
     * @param newExtractor
     *            새 dedup-key 추출기 (must not be null; null 키를 반환하면 해당 hook 은 dedup 대상에서 제외)
     * @return 새 정책 (never null)
     */
    public HookExecutionPolicy withDedupKeyExtractor(DedupKeyExtractor newExtractor) {
        return new HookExecutionPolicy(stopOnBlocked, exceptionMapper, timeout, timeoutBehavior, executionMode,
                newExtractor);
    }

    @FunctionalInterface
    public interface ExceptionMapper {
        /** 예외를 HookResult로 매핑한다. */
        HookResult map(Exception e);
    }

    /**
     * 훅 dedup-key 추출기.
     *
     * <p>
     * 동일 키를 가진 hook 이 여러 번 등록되어 있으면 처음 발견된 hook 만 실행되고 이후의 동일 키 hook 은 스킵된다 (LinkedHashSet 동작).
     * {@code null} 또는 빈 문자열을 반환하면 해당 hook 은 dedup 대상에서 제외 — 항상 그대로 실행된다. 기본 정책은
     * {@code h -> null} 으로 dedup 미적용.
     */
    @FunctionalInterface
    public interface DedupKeyExtractor {
        /**
         * 주어진 hook 에 대한 dedup 키를 반환한다.
         *
         * @param hook
         *            대상 hook (never null)
         * @return dedup 키 (null 또는 빈 문자열이면 dedup 미적용)
         */
        String extract(ExecutionHook<?> hook);
    }

    /**
     * 타임아웃 발생 시 동작 모드.
     *
     * <ul>
     * <li>{@link #FAIL_OPEN} — 타임아웃을 SUCCESS 로 처리. 운영 가용성 우선.</li>
     * <li>{@link #FAIL_CLOSED} — 타임아웃을 BLOCKED 로 처리. 보안 민감 훅의 fail-safe.</li>
     * </ul>
     */
    public enum TimeoutBehavior {
        FAIL_OPEN, FAIL_CLOSED
    }

    /**
     * 훅 실행 모드.
     *
     * <ul>
     * <li>{@link #SEQUENTIAL} — 훅을 등록 순서대로 차례로 실행. 이전 훅이 반환한 {@code updatedInput}/{@code updatedOutput} 가
     * 다음 훅에 자동으로 전달된다 (thread-through 의미). {@code stopOnBlocked} 가 true 면 BLOCKED 발생 시 즉시
     * 중단. 의존성 있는 훅에 적합한 기본 모드.</li>
     * <li>{@link #PARALLEL} — 훅을 동시 실행. 모든 훅이 동일한 시작 컨텍스트를 본다 ({@code updatedInput} threading 없음).
     * {@code stopOnBlocked} 는 무의미하며 (이미 발사된 작업은 멈출 수 없음), 결과는 {@link HookResult#merge} 가 호출되는 호출
     * 측에서 통합된다. 부수효과 없는 read-only/관측 훅에 권장.</li>
     * </ul>
     */
    public enum ExecutionMode {
        /** 순차 실행 (기본값). */
        SEQUENTIAL,

        /** 병렬 실행 (read-only 훅 권장). */
        PARALLEL
    }
}
