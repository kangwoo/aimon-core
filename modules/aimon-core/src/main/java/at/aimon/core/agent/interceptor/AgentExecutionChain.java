package at.aimon.core.agent.interceptor;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntime;

/**
 * 인터셉터 체인에서 다음 단계로 실행을 전달하는 인터페이스.
 *
 * <p>
 * 인터셉터 내부에서 {@code chain.proceed(context, request)}를 호출하면 다음 인터셉터가 실행되거나, 체인 끝에 도달하면 실제 AgentExecutor가 실행된다.
 *
 * <p>
 * <b>중요:</b> {@code proceed()}는 체인 단계당 최대 1회만 호출 가능하다. 2회 이상 호출하면 {@code IllegalStateException}이 발생한다. 에이전트 실행은 부수효과가
 * 크므로(LLM 호출, 토큰 소비) 이중 실행을 방지하기 위함이다.
 *
 * @param <CTX>
 *            실행 컨텍스트 타입
 * @param <REQ>
 *            실행 요청 타입
 * @param <RES>
 *            실행 결과 타입
 *
 * @see AgentExecutionInterceptor
 */
@FunctionalInterface
// @formatter:off
public interface AgentExecutionChain<
        CTX extends AgentRuntime,
        REQ extends AgentExecutionRequest,
        RES extends AgentExecutionResult> {
    // @formatter:on

    /**
     * 다음 인터셉터 또는 실제 AgentExecutor로 실행을 전달한다.
     *
     * @param context
     *            실행 컨텍스트 (null 불가)
     * @param request
     *            실행 요청 (null 불가)
     * @return 실행 결과 (null 불가)
     * @throws IllegalStateException
     *             이미 proceed()가 호출된 경우
     */
    RES proceed(CTX context, REQ request);
}
