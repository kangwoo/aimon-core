package at.aimon.core.agent.interceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntime;

/**
 * 인터셉터 체인을 적용하는 AgentExecutor 데코레이터.
 *
 * <p>
 * 기존 AgentExecutor를 감싸서 실행 전후에 인터셉터를 적용한다. 인터셉터는 {@link AgentExecutionInterceptor#getOrder()} 순서로 실행되며, 동일한 순서의 인터셉터는
 * 등록 순서를 유지한다 (stable sort).
 *
 * <p>
 * 사용 예제:
 *
 * <pre>
 * {@code
 * OrcaAgentExecutor executor = new OrcaAgentExecutor(llmClient, ...);
 *
 * // builder(delegate)로 타입 파라미터 자동 추론
 * var intercepted = InterceptingAgentExecutor.builder(executor)
 *         .addInterceptor(new LoggingInterceptor<>())
 *         .addInterceptor(new AuthInterceptor(authService))
 *         .addInterceptor(new MetricsInterceptor<>())
 *         .build();
 *
 * // 호출자는 동일한 타입으로 사용
 * OrcaAgentExecutionResult result = intercepted.execute(orcaContext, orcaRequest);
 * }
 * </pre>
 *
 * @param <CTX>
 *            실행 컨텍스트 타입
 * @param <REQ>
 *            실행 요청 타입
 * @param <RES>
 *            실행 결과 타입
 *
 * @see AgentExecutionInterceptor
 * @see AgentExecutionChain
 */
// @formatter:off
public final class InterceptingAgentExecutor<
        CTX extends AgentRuntime,
        REQ extends AgentExecutionRequest,
        RES extends AgentExecutionResult>
        implements AgentExecutor<CTX, REQ, RES> {
    // @formatter:on

    private final AgentExecutor<CTX, REQ, RES> delegate;

    private final List<AgentExecutionInterceptor<CTX, REQ, RES>> interceptors;

    private InterceptingAgentExecutor(AgentExecutor<CTX, REQ, RES> delegate,
            List<AgentExecutionInterceptor<CTX, REQ, RES>> interceptors) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null");
        this.interceptors = List.copyOf(interceptors);
    }

    @Override
    public RES execute(CTX context, REQ request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        if (interceptors.isEmpty()) {
            return delegate.execute(context, request);
        }

        // 체인의 시작점 구성: 마지막 인터셉터부터 역순으로 감싸기
        AgentExecutionChain<CTX, REQ, RES> chain = oneShot(delegate::execute);

        for (int i = interceptors.size() - 1; i >= 0; i--) {
            final AgentExecutionInterceptor<CTX, REQ, RES> interceptor = interceptors.get(i);
            final AgentExecutionChain<CTX, REQ, RES> next = chain;
            chain = oneShot((ctx, req) -> interceptor.intercept(ctx, req, next));
        }

        return chain.proceed(context, request);
    }

    /**
     * 감싸고 있는 원본 AgentExecutor를 반환한다.
     *
     * @return delegate AgentExecutor (null 아님)
     */
    public AgentExecutor<CTX, REQ, RES> getDelegate() {
        return delegate;
    }

    /**
     * 적용된 인터셉터 목록을 반환한다.
     *
     * <p>
     * 반환된 목록은 {@link AgentExecutionInterceptor#getOrder()} 기준으로 정렬되어 있으며, 수정 불가하다.
     *
     * @return 정렬된 인터셉터 목록 (null 아님, 수정 불가)
     */
    public List<AgentExecutionInterceptor<CTX, REQ, RES>> getInterceptors() {
        return interceptors;
    }

    /**
     * chain.proceed()가 최대 1회만 호출되도록 보장하는 래퍼.
     */
    private AgentExecutionChain<CTX, REQ, RES> oneShot(AgentExecutionChain<CTX, REQ, RES> delegate) {
        final AtomicBoolean called = new AtomicBoolean(false);
        return (ctx, req) -> {
            if (!called.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "chain.proceed() has already been called. " + "Each chain step can only be invoked once.");
            }
            return delegate.proceed(ctx, req);
        };
    }

    /**
     * delegate로부터 타입 파라미터를 자동 추론하는 Builder를 생성한다.
     *
     * @param delegate
     *            감쌀 AgentExecutor (null 불가)
     * @return 타입이 추론된 Builder
     */
    // @formatter:off
    public static <CTX extends AgentRuntime,
            REQ extends AgentExecutionRequest,
            RES extends AgentExecutionResult>
            Builder<CTX, REQ, RES> builder(
                    AgentExecutor<CTX, REQ, RES> delegate) {
        // @formatter:on
        return new Builder<CTX, REQ, RES>().delegate(Objects.requireNonNull(delegate, "Delegate cannot be null"));
    }

    // @formatter:off
    public static final class Builder<
            CTX extends AgentRuntime,
            REQ extends AgentExecutionRequest,
            RES extends AgentExecutionResult> {
        // @formatter:on

        private AgentExecutor<CTX, REQ, RES> delegate;

        private final List<AgentExecutionInterceptor<CTX, REQ, RES>> interceptors = new ArrayList<>();

        private Builder() {
        }

        Builder<CTX, REQ, RES> delegate(AgentExecutor<CTX, REQ, RES> delegate) {
            this.delegate = delegate;
            return this;
        }

        /**
         * 인터셉터를 추가한다.
         *
         * @param interceptor
         *            추가할 인터셉터 (null 불가)
         * @return This builder
         */
        public Builder<CTX, REQ, RES> addInterceptor(AgentExecutionInterceptor<CTX, REQ, RES> interceptor) {
            this.interceptors.add(Objects.requireNonNull(interceptor, "Interceptor cannot be null"));
            return this;
        }

        /**
         * InterceptingAgentExecutor를 생성한다.
         *
         * @return 인터셉터가 적용된 AgentExecutor
         */
        public InterceptingAgentExecutor<CTX, REQ, RES> build() {
            Objects.requireNonNull(delegate, "Delegate AgentExecutor must be set");
            final List<AgentExecutionInterceptor<CTX, REQ, RES>> sorted = new ArrayList<>(interceptors);
            // stable sort: 동일 order 내에서 등록 순서 유지
            sorted.sort(Comparator.comparingInt(AgentExecutionInterceptor::getOrder));
            return new InterceptingAgentExecutor<>(delegate, sorted);
        }
    }
}
