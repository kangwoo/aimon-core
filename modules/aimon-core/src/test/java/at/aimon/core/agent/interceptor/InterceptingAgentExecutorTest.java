package at.aimon.core.agent.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntime;

@DisplayName("InterceptingAgentExecutor Tests")
class InterceptingAgentExecutorTest {

    @Nested
    @DisplayName("Basic Execution")
    class BasicExecution {

        @Test
        @DisplayName("Should delegate to executor when no interceptors")
        void shouldDelegateWhenNoInterceptors() {
            StubExecutor delegate = new StubExecutor("hello");

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).build();

            StubResult result = intercepted.execute(new StubContext(), new StubRequest());

            assertThat(result.getFinalAnswer()).isEqualTo("hello");
            assertThat(delegate.executeCount).isEqualTo(1);
        }

        @Test
        @DisplayName("Should pass through single interceptor")
        void shouldPassThroughSingleInterceptor() {
            StubExecutor delegate = new StubExecutor("result");
            List<String> log = new ArrayList<>();

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).addInterceptor(new LoggingStubInterceptor(log, "A")).build();

            StubResult result = intercepted.execute(new StubContext(), new StubRequest());

            assertThat(result.getFinalAnswer()).isEqualTo("result");
            assertThat(log).containsExactly("A:before", "A:after");
        }

        @Test
        @DisplayName("Should reject null context")
        void shouldRejectNullContext() {
            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).build();

            assertThatThrownBy(() -> intercepted.execute(null, new StubRequest()))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Context cannot be null");
        }

        @Test
        @DisplayName("Should reject null request")
        void shouldRejectNullRequest() {
            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).build();

            assertThatThrownBy(() -> intercepted.execute(new StubContext(), null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Request cannot be null");
        }
    }

    @Nested
    @DisplayName("Interceptor Ordering")
    class InterceptorOrdering {

        @Test
        @DisplayName("Should execute interceptors in getOrder() order")
        void shouldExecuteInOrder() {
            List<String> log = new ArrayList<>();

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).addInterceptor(new LoggingStubInterceptor(log, "C", 10))
                    .addInterceptor(new LoggingStubInterceptor(log, "A", -100))
                    .addInterceptor(new LoggingStubInterceptor(log, "B", 0)).build();

            intercepted.execute(new StubContext(), new StubRequest());

            assertThat(log).containsExactly("A:before", "B:before", "C:before", "C:after", "B:after", "A:after");
        }

        @Test
        @DisplayName("Should preserve registration order for same getOrder() (stable sort)")
        void shouldPreserveRegistrationOrderForSameOrder() {
            List<String> log = new ArrayList<>();

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).addInterceptor(new LoggingStubInterceptor(log, "First", 0))
                    .addInterceptor(new LoggingStubInterceptor(log, "Second", 0))
                    .addInterceptor(new LoggingStubInterceptor(log, "Third", 0)).build();

            intercepted.execute(new StubContext(), new StubRequest());

            assertThat(log).containsExactly("First:before", "Second:before", "Third:before", "Third:after",
                    "Second:after", "First:after");
        }
    }

    @Nested
    @DisplayName("Blocking Interceptor")
    class BlockingInterceptor {

        @Test
        @DisplayName("Should block execution when chain.proceed() is not called")
        void shouldBlockExecution() {
            StubExecutor delegate = new StubExecutor("should-not-reach");

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).addInterceptor(new BlockingStubInterceptor("blocked")).build();

            StubResult result = intercepted.execute(new StubContext(), new StubRequest());

            assertThat(result.getFinalAnswer()).isNull();
            assertThat(result.getErrorMessage()).isEqualTo("blocked");
            assertThat(result.isSuccess()).isFalse();
            assertThat(delegate.executeCount).isEqualTo(0);
        }

        @Test
        @DisplayName("Should block at middle interceptor and skip remaining")
        void shouldBlockAtMiddleInterceptor() {
            StubExecutor delegate = new StubExecutor("should-not-reach");
            List<String> log = new ArrayList<>();

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).addInterceptor(new LoggingStubInterceptor(log, "Outer", -10))
                    .addInterceptor(new BlockingStubInterceptor("denied"))
                    .addInterceptor(new LoggingStubInterceptor(log, "Inner", 10)).build();

            StubResult result = intercepted.execute(new StubContext(), new StubRequest());

            assertThat(result.getErrorMessage()).isEqualTo("denied");
            assertThat(delegate.executeCount).isEqualTo(0);
            // Outer executes before/after, Inner never executes
            assertThat(log).containsExactly("Outer:before", "Outer:after");
        }
    }

    @Nested
    @DisplayName("Double Proceed Prevention")
    class DoubleProceedPrevention {

        @Test
        @DisplayName("Should throw IllegalStateException on double proceed")
        void shouldThrowOnDoubleProceed() {
            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).addInterceptor(new DoubleProceedInterceptor()).build();

            assertThatThrownBy(() -> intercepted.execute(new StubContext(), new StubRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("chain.proceed() has already been called");
        }
    }

    @Nested
    @DisplayName("Request/Response Transformation")
    class RequestResponseTransformation {

        @Test
        @DisplayName("Should allow interceptor to transform request")
        void shouldTransformRequest() {
            StubExecutor delegate = new StubExecutor("ok");

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).addInterceptor(new RequestTransformInterceptor("transformed")).build();

            intercepted.execute(new StubContext(), new StubRequest("original"));

            assertThat(delegate.lastRequest.tag).isEqualTo("transformed");
        }

        @Test
        @DisplayName("Should allow interceptor to transform response")
        void shouldTransformResponse() {
            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("original")).addInterceptor(new ResponseTransformInterceptor("wrapped"))
                    .build();

            StubResult result = intercepted.execute(new StubContext(), new StubRequest());

            assertThat(result.getFinalAnswer()).isEqualTo("wrapped");
        }
    }

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandling {

        @Test
        @DisplayName("Should propagate exception from interceptor")
        void shouldPropagateInterceptorException() {
            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).addInterceptor(new ExceptionThrowingInterceptor("boom")).build();

            assertThatThrownBy(() -> intercepted.execute(new StubContext(), new StubRequest()))
                    .isInstanceOf(RuntimeException.class).hasMessage("boom");
        }

        @Test
        @DisplayName("Should propagate exception from delegate")
        void shouldPropagateDelegateException() {
            AgentExecutor<StubContext, StubRequest, StubResult> delegate = (ctx, req) -> {
                throw new RuntimeException("delegate-error");
            };

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).addInterceptor(new PassthroughInterceptor()).build();

            assertThatThrownBy(() -> intercepted.execute(new StubContext(), new StubRequest()))
                    .isInstanceOf(RuntimeException.class).hasMessage("delegate-error");
        }

        @Test
        @DisplayName("Should execute outer interceptor post-processing even when inner throws (try-finally)")
        void shouldExecuteOuterPostProcessingOnInnerException() {
            List<String> log = new ArrayList<>();

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).addInterceptor(new TryFinallyInterceptor(log, "Outer", -10))
                    .addInterceptor(new ExceptionThrowingInterceptor("inner-error")).build();

            assertThatThrownBy(() -> intercepted.execute(new StubContext(), new StubRequest()))
                    .isInstanceOf(RuntimeException.class).hasMessage("inner-error");

            // Outer's finally block should still execute
            assertThat(log).containsExactly("Outer:before", "Outer:finally");
        }
    }

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("Should reject null delegate")
        void shouldRejectNullDelegate() {
            assertThatThrownBy(() -> InterceptingAgentExecutor.builder(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Delegate cannot be null");
        }

        @Test
        @DisplayName("Should reject null interceptor")
        void shouldRejectNullInterceptor() {
            assertThatThrownBy(() -> InterceptingAgentExecutor.builder(new StubExecutor("ok")).addInterceptor(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Interceptor cannot be null");
        }
    }

    @Nested
    @DisplayName("Generic Type Safety")
    class GenericTypeSafety {

        @Test
        @DisplayName("Should preserve generic type parameters through chain")
        void shouldPreserveGenericTypes() {
            StubExecutor delegate = new StubExecutor("typed-result");

            // Generic passthrough interceptor
            AgentExecutionInterceptor<StubContext, StubRequest, StubResult> interceptor = (ctx, req, chain) -> {
                // Type-safe access
                StubContext typedCtx = ctx;
                StubRequest typedReq = req;
                StubResult typedRes = chain.proceed(typedCtx, typedReq);
                return typedRes;
            };

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).addInterceptor(interceptor).build();

            StubResult result = intercepted.execute(new StubContext(), new StubRequest());

            assertThat(result.getFinalAnswer()).isEqualTo("typed-result");
        }
    }

    @Nested
    @DisplayName("Multiple Execute Calls")
    class MultipleExecuteCalls {

        @Test
        @DisplayName("Should allow multiple execute() calls on the same instance")
        void shouldAllowMultipleExecuteCalls() {
            StubExecutor delegate = new StubExecutor("ok");
            List<String> log = new ArrayList<>();

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).addInterceptor(new LoggingStubInterceptor(log, "A")).build();

            intercepted.execute(new StubContext(), new StubRequest());
            intercepted.execute(new StubContext(), new StubRequest());

            assertThat(delegate.executeCount).isEqualTo(2);
            assertThat(log).containsExactly("A:before", "A:after", "A:before", "A:after");
        }
    }

    @Nested
    @DisplayName("Context Transformation")
    class ContextTransformation {

        @Test
        @DisplayName("Should allow interceptor to transform context")
        void shouldTransformContext() {
            StubExecutor delegate = new StubExecutor("ok");

            AgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).addInterceptor(new ContextTransformInterceptor("transformed")).build();

            intercepted.execute(new StubContext("original"), new StubRequest());

            assertThat(delegate.lastContext.tag).isEqualTo("transformed");
        }
    }

    @Nested
    @DisplayName("Accessor Methods")
    class AccessorMethods {

        @Test
        @DisplayName("Should return delegate via getDelegate()")
        void shouldReturnDelegate() {
            StubExecutor delegate = new StubExecutor("ok");

            InterceptingAgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(delegate).build();

            assertThat(intercepted.getDelegate()).isSameAs(delegate);
        }

        @Test
        @DisplayName("Should return sorted interceptors via getInterceptors()")
        void shouldReturnSortedInterceptors() {
            List<String> log = new ArrayList<>();
            LoggingStubInterceptor high = new LoggingStubInterceptor(log, "High", 10);
            LoggingStubInterceptor low = new LoggingStubInterceptor(log, "Low", -10);

            InterceptingAgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).addInterceptor(high).addInterceptor(low).build();

            assertThat(intercepted.getInterceptors()).containsExactly(low, high);
        }

        @Test
        @DisplayName("Should return unmodifiable interceptor list")
        void shouldReturnUnmodifiableInterceptorList() {
            InterceptingAgentExecutor<StubContext, StubRequest, StubResult> intercepted = InterceptingAgentExecutor
                    .builder(new StubExecutor("ok")).addInterceptor(new PassthroughInterceptor()).build();

            assertThatThrownBy(() -> intercepted.getInterceptors().add(new PassthroughInterceptor()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Interceptor getName()")
    class InterceptorNaming {

        @Test
        @DisplayName("Should return class simple name by default")
        void shouldReturnClassSimpleName() {
            PassthroughInterceptor interceptor = new PassthroughInterceptor();

            assertThat(interceptor.getName()).isEqualTo("PassthroughInterceptor");
        }

        @Test
        @DisplayName("Should return non-empty name for lambda interceptor")
        void shouldReturnNonEmptyNameForLambda() {
            AgentExecutionInterceptor<StubContext, StubRequest, StubResult> lambda = (ctx, req, chain) -> chain
                    .proceed(ctx, req);

            assertThat(lambda.getName()).isNotEmpty();
        }
    }

    // --- Test stubs ---

    static class StubContext implements AgentRuntime {

        final String tag;

        StubContext() {
            this("default");
        }

        StubContext(String tag) {
            this.tag = tag;
        }

        @Override
        public at.aimon.core.agent.AgentRuntimeId getId() {
            return null;
        }

        @Override
        public at.aimon.core.agent.Agent getAgent() {
            return null;
        }

        @Override
        public List<at.aimon.core.agent.tool.Tool> getAvailableTools() {
            return List.of();
        }
    }

    static class StubRequest implements AgentExecutionRequest {

        final String tag;

        StubRequest() {
            this("default");
        }

        StubRequest(String tag) {
            this.tag = tag;
        }

        @Override
        public at.aimon.core.agent.input.UserInput getUserInput() {
            return null;
        }

        @Override
        public java.util.Optional<at.aimon.core.base.Principal> getPrincipal() {
            return java.util.Optional.empty();
        }
    }

    static class StubResult implements AgentExecutionResult {

        private final String finalAnswer;

        private final String errorMessage;

        StubResult(String finalAnswer) {
            this.finalAnswer = finalAnswer;
            this.errorMessage = null;
        }

        static StubResult failure(String errorMessage) {
            return new StubResult(null, errorMessage);
        }

        private StubResult(String finalAnswer, String errorMessage) {
            this.finalAnswer = finalAnswer;
            this.errorMessage = errorMessage;
        }

        @Override
        public boolean isSuccess() {
            return finalAnswer != null;
        }

        @Override
        public String getFinalAnswer() {
            return finalAnswer;
        }

        @Override
        public String getErrorMessage() {
            return errorMessage;
        }
    }

    static class StubExecutor implements AgentExecutor<StubContext, StubRequest, StubResult> {

        private final String answer;

        int executeCount = 0;

        StubContext lastContext;

        StubRequest lastRequest;

        StubExecutor(String answer) {
            this.answer = answer;
        }

        @Override
        public StubResult execute(StubContext context, StubRequest request) {
            executeCount++;
            lastContext = context;
            lastRequest = request;
            return new StubResult(answer);
        }
    }

    static class LoggingStubInterceptor implements AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        private final List<String> log;

        private final String name;

        private final int order;

        LoggingStubInterceptor(List<String> log, String name) {
            this(log, name, 0);
        }

        LoggingStubInterceptor(List<String> log, String name, int order) {
            this.log = log;
            this.name = name;
            this.order = order;
        }

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            log.add(name + ":before");
            StubResult result = chain.proceed(context, request);
            log.add(name + ":after");
            return result;
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    static class BlockingStubInterceptor implements AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        private final String errorMessage;

        BlockingStubInterceptor(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            // chain.proceed() 호출하지 않아 실행 차단
            return StubResult.failure(errorMessage);
        }
    }

    static class DoubleProceedInterceptor implements AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            chain.proceed(context, request);
            return chain.proceed(context, request); // 2회 호출 → IllegalStateException
        }
    }

    static class PassthroughInterceptor implements AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            return chain.proceed(context, request);
        }
    }

    static class ContextTransformInterceptor
            implements
                AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        private final String newTag;

        ContextTransformInterceptor(String newTag) {
            this.newTag = newTag;
        }

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            return chain.proceed(new StubContext(newTag), request);
        }
    }

    static class RequestTransformInterceptor
            implements
                AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        private final String newTag;

        RequestTransformInterceptor(String newTag) {
            this.newTag = newTag;
        }

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            return chain.proceed(context, new StubRequest(newTag));
        }
    }

    static class ResponseTransformInterceptor
            implements
                AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        private final String newAnswer;

        ResponseTransformInterceptor(String newAnswer) {
            this.newAnswer = newAnswer;
        }

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            chain.proceed(context, request);
            return new StubResult(newAnswer);
        }
    }

    static class ExceptionThrowingInterceptor
            implements
                AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        private final String message;

        ExceptionThrowingInterceptor(String message) {
            this.message = message;
        }

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            throw new RuntimeException(message);
        }
    }

    static class TryFinallyInterceptor implements AgentExecutionInterceptor<StubContext, StubRequest, StubResult> {

        private final List<String> log;

        private final String name;

        private final int order;

        TryFinallyInterceptor(List<String> log, String name, int order) {
            this.log = log;
            this.name = name;
            this.order = order;
        }

        @Override
        public StubResult intercept(StubContext context, StubRequest request,
                AgentExecutionChain<StubContext, StubRequest, StubResult> chain) {
            log.add(name + ":before");
            try {
                return chain.proceed(context, request);
            } finally {
                log.add(name + ":finally");
            }
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
