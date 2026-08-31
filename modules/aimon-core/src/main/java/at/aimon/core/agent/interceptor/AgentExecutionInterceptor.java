package at.aimon.core.agent.interceptor;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntime;

/**
 * AgentExecutor 실행을 가로채는 인터셉터.
 *
 * <p>
 * 인터셉터는 에이전트 실행 전/후에 로직을 삽입하거나, 요청/응답을 변환하거나, 실행을 차단할 수 있다. Middleware Chain 패턴을 따른다.
 *
 * <p>
 * 인터셉터는 반드시 {@code chain.proceed()}를 <b>최대 1회</b> 호출하여 다음 인터셉터 또는 실제 AgentExecutor로 실행을 전달해야 한다. 호출하지 않으면 실행이 차단되며, 2회
 * 이상 호출하면 {@code IllegalStateException}이 발생한다.
 *
 * <p>
 * <b>후처리 시 예외 안전성:</b> 후처리 로직에서 예외가 발생하면 바깥쪽 인터셉터의 후처리도 실행되지 않을 수 있다. 자원 정리가 필요한 경우 반드시 try-finally 블록을 사용할 것.
 *
 * <p>
 * <b>스트리밍 이벤트와의 관계:</b> 인터셉터는 실행 체인을 <em>제어</em>하는 동기 계층이고,
 * {@link at.aimon.core.agent.stream.AgentExecutionEvent}와 {@link at.aimon.core.agent.stream.StreamingAgentExecutor}
 * 는 실행 진행을 <em>관찰</em>하는 정보성 이벤트 계층이다. 두 계층은 직교한 관심사로 설계됐으며, 상세한 역할
 * 분리는 {@code docs/design/agent-execution/interceptor.md} §7 "스트리밍 이벤트와의 관계"를 참조한다.
 *
 * <p>
 * 사용 예제:
 *
 * <pre>
 * // 패스스루 인터셉터: 모든 AgentExecutor 타입에 재사용 가능
 * public class LoggingInterceptor&lt;CTX, REQ, RES&gt; implements AgentExecutionInterceptor&lt;CTX, REQ, RES&gt; {
 *
 *     &#64;Override
 *     public RES intercept(CTX context, REQ request, AgentExecutionChain&lt;CTX, REQ, RES&gt; chain) {
 *         log.info("Before: {}", request.getUserInput());
 *         RES result = chain.proceed(context, request);
 *         log.info("After: success={}", result.isSuccess());
 *         return result;
 *     }
 * }
 *
 * // 차단 인터셉터: 구체 타입으로 특정하여 올바른 결과 생성
 * public class AuthInterceptor implements
 *         AgentExecutionInterceptor&lt;OrcaAgentRuntime,
 *             OrcaAgentExecutionRequest,
 *             OrcaAgentExecutionResult&gt; {
 *
 *     &#64;Override
 *     public OrcaAgentExecutionResult intercept(
 *             OrcaAgentRuntime context,
 *             OrcaAgentExecutionRequest request,
 *             AgentExecutionChain&lt;OrcaAgentRuntime,
 *                 OrcaAgentExecutionRequest,
 *                 OrcaAgentExecutionResult&gt; chain) {
 *         if (!authorized) {
 *             return OrcaAgentExecutionResult.failure("Unauthorized", null, null);
 *         }
 *         return chain.proceed(context, request);
 *     }
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
 * @see AgentExecutionChain
 * @see InterceptingAgentExecutor
 */
// @formatter:off
public interface AgentExecutionInterceptor<
        CTX extends AgentRuntime,
        REQ extends AgentExecutionRequest,
        RES extends AgentExecutionResult> {
    // @formatter:on

    /**
     * 에이전트 실행을 가로챈다.
     *
     * <p>
     * {@code chain.proceed()}는 최대 1회만 호출해야 한다. 호출하지 않으면 실행이 차단되며, 이 경우 적절한 RES 타입의 결과를 직접 생성하여 반환해야 한다.
     *
     * @param context
     *            실행 컨텍스트 (null 불가)
     * @param request
     *            실행 요청 (null 불가)
     * @param chain
     *            다음 인터셉터 또는 실제 실행을 호출하는 체인 (null 불가)
     * @return 실행 결과 (null 불가)
     */
    RES intercept(CTX context, REQ request, AgentExecutionChain<CTX, REQ, RES> chain);

    /**
     * 인터셉터의 실행 순서를 결정하는 우선순위.
     *
     * <p>
     * 낮은 값이 먼저 실행된다 (가장 바깥쪽). 기본값은 0. 동일한 순서의 인터셉터는 등록 순서(stable sort)를 유지한다.
     *
     * @return 우선순위 값
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 디버깅 및 로깅을 위한 인터셉터 이름.
     *
     * <p>
     * 익명 클래스나 람다의 경우 클래스 단순명이 비어 있을 수 있으므로, 이 경우 정규화된 클래스명에서 추출한 이름을 반환한다.
     *
     * @return 인터셉터 이름 (기본값: 클래스 단순명, 빈 경우 정규화된 이름 fallback)
     */
    default String getName() {
        String simpleName = getClass().getSimpleName();
        if (simpleName == null || simpleName.isEmpty()) {
            String fullName = getClass().getName();
            int lastDot = fullName.lastIndexOf('.');
            return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
        }
        return simpleName;
    }
}
