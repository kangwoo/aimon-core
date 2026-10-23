package at.aimon.core.agent.tool.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolExecutionException;
import at.aimon.core.agent.tool.execution.ToolExecutionResult;
import at.aimon.core.agent.tool.execution.ToolExecutionResultConverter;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUseResult;

/**
 * Tests for {@link GenericTool}.
 *
 * <p>
 * What is being checked here is the base class's side of the bargain: the schema comes from the record, a bad call
 * never reaches {@code doExecute}, and no exception a subclass throws — anticipated or not — escapes {@code execute}.
 */
class GenericToolTest {

    record EchoInput(@ToolParam(required = true, description = "What to say") String message,
            @ToolParam(description = "How many times") Integer times) {
    }

    /** Records what it was called with, so a test can assert that a rejected call never got here. */
    static class EchoTool extends GenericTool<EchoInput, String> {

        final AtomicInteger executions = new AtomicInteger();

        EchoTool() {
            super("Echo", "Repeats a message back", EchoInput.class);
        }

        @Override
        protected String doExecute(EchoInput input, ToolContext context) {
            executions.incrementAndGet();
            return input.message().repeat(input.times() == null ? 1 : input.times());
        }

        @Override
        protected ToolResult render(String output) {
            return ToolResult.success(output);
        }
    }

    /** Throws whatever a test hands it, to exercise both sides of the error contract. */
    static class ThrowingTool extends GenericTool<EchoInput, String> {

        private final Exception failure;

        ThrowingTool(Exception failure) {
            super("Throwing", "Always fails", EchoInput.class);
            this.failure = failure;
        }

        @Override
        protected String doExecute(EchoInput input, ToolContext context) throws Exception {
            throw failure;
        }

        @Override
        protected ToolResult render(String output) {
            return ToolResult.success(output);
        }
    }

    static class NullRenderingTool extends GenericTool<EchoInput, String> {

        NullRenderingTool() {
            super("NullRendering", "Renders nothing", EchoInput.class);
        }

        @Override
        protected String doExecute(EchoInput input, ToolContext context) {
            return "ignored";
        }

        @Override
        protected ToolResult render(String output) {
            return null;
        }
    }

    private final EchoTool tool = new EchoTool();

    private ToolResult call(Map<String, Object> input) {
        return tool.execute(ToolInput.of(input), ToolContext.empty());
    }

    @Nested
    class Definition {

        @SuppressWarnings("unchecked")
        @Test
        void schemaIsDerivedFromTheRecord() {
            ToolDefinition definition = tool.getDefinition();
            Map<String, Object> schema = definition.getInputSchema();

            assertThat(definition.getName()).isEqualTo("Echo");
            assertThat(schema).containsEntry("type", "object").containsEntry("required", List.of("message"))
                    .containsEntry("additionalProperties", false);
            assertThat(((Map<String, Object>) schema.get("properties")).keySet()).containsExactly("message", "times");
        }

        @Test
        void categoryDefaultsWhenNotGiven() {
            assertThat(tool.getDefinition().getCategory()).isEqualTo(ToolDefinition.DEFAULT_CATEGORY);
        }

        @Test
        void aDescriptionCanBeRecomputedWhileTheSchemaStaysFixed() {
            AtomicInteger reads = new AtomicInteger();
            GenericTool<EchoInput, String> dynamic = new GenericTool<>("Dynamic", ToolDefinition.DEFAULT_CATEGORY,
                    () -> "read " + reads.incrementAndGet(), EchoInput.class) {

                @Override
                protected String doExecute(EchoInput input, ToolContext context) {
                    return input.message();
                }

                @Override
                protected ToolResult render(String output) {
                    return ToolResult.success(output);
                }
            };

            assertThat(dynamic.getDefinition().getDescription()).isEqualTo("read 1");
            assertThat(dynamic.getDefinition().getDescription()).isEqualTo("read 2");
            assertThat(dynamic.getDefinition().getInputSchema()).containsEntry("required", List.of("message"));
        }

        @Test
        void aNonRecordInputTypeFailsAtConstructionRatherThanAtCallTime() {
            assertThatThrownBy(() -> new GenericTool<String, String>("Bad", "Bad", String.class) {

                @Override
                protected String doExecute(String input, ToolContext context) {
                    return input;
                }

                @Override
                protected ToolResult render(String output) {
                    return ToolResult.success(output);
                }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be a record");
        }
    }

    @Nested
    class Dispatch {

        @Test
        void aValidCallReachesDoExecuteWithItsParametersBound() {
            ToolResult result = call(Map.of("message", "hi", "times", 3));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).isEqualTo("hihihi");
            assertThat(tool.executions).hasValue(1);
        }

        @Test
        void aRejectedCallNeverReachesDoExecute() {
            ToolResult result = call(Map.of("times", 3));

            assertThat(result.isError()).isTrue();
            assertThat(tool.executions).hasValue(0);
        }

        @Test
        void aRejectedCallNamesTheToolAndListsEveryViolation() {
            ToolResult result = call(Map.of("times", "three", "extra", true));

            assertThat(result.getContent()).startsWith("Invalid input for tool 'Echo':").contains("'message'")
                    .contains("'times'").contains("'extra'");
        }

        @Test
        void nullArgumentsAreRejectedAtTheEntryPoint() {
            assertThatThrownBy(() -> tool.execute(null, ToolContext.empty())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> tool.execute(ToolInput.of(Map.of("message", "hi")), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class ErrorContract {

        private ToolResult failWith(Exception failure) {
            return new ThrowingTool(failure).execute(ToolInput.of(Map.of("message", "hi")), ToolContext.empty());
        }

        @Test
        void anAnticipatedFailureReachesTheModelInTheToolsOwnWords() {
            ToolResult result = failWith(new ToolExecutionException("Invalid regex pattern: dangling ["));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("Invalid regex pattern: dangling [");
            assertThat(result.isRetryable()).isTrue();
        }

        @Test
        void aFailureThrownAsNotRetryableIsMarkedSo() {
            ToolResult result = failWith(ToolExecutionException.notRetryable("SIZE_LIMIT: source over 10 MB"));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("SIZE_LIMIT: source over 10 MB");
            assertThat(result.isRetryable()).isFalse();
            assertThat(ToolExecutionException.notRetryable("x", new java.io.IOException("io")).getCause())
                    .isInstanceOf(java.io.IOException.class);
            assertThat(new ToolExecutionException("x").isRetryable()).isTrue();
            org.assertj.core.api.Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> ToolExecutionException.notRetryable(null));
        }

        @Test
        void anAnticipatedFailureKeepsItsExceptionAndCauseOnTheResult() {
            IOException root = new IOException("connection reset");
            ToolExecutionException thrown = new ToolExecutionException("Search interrupted", root);

            ToolResult result = failWith(thrown);

            assertThat(result.getContent()).isEqualTo("Search interrupted");
            assertThat(result.isRetryable()).isTrue();
            assertThat(result.getException()).containsSame(thrown);
            assertThat(result.getException().orElseThrow().getCause()).isSameAs(root);
        }

        @Test
        void aNotRetryableFailureKeepsItsExceptionCauseAndMark() {
            IOException root = new IOException("403 from upstream");
            ToolExecutionException thrown = ToolExecutionException.notRetryable("AUTH_DENIED: no access", root);

            ToolResult result = failWith(thrown);

            assertThat(result.getContent()).isEqualTo("AUTH_DENIED: no access");
            assertThat(result.isRetryable()).isFalse();
            assertThat(result.getException()).containsSame(thrown);
            assertThat(result.getException().orElseThrow().getCause()).isSameAs(root);
        }

        @Test
        void theAttachedExceptionNeverReachesWhatTheModelReads() {
            ToolResult result = failWith(
                    ToolExecutionException.notRetryable("AUTH_DENIED: no access", new IOException("secret detail")));

            ToolExecutionResult executed = ToolExecutionResult.of("tu-1", result);
            ToolUseResult toModel = ToolExecutionResultConverter.toToolUseResult(executed);

            assertThat(executed.getException()).containsInstanceOf(ToolExecutionException.class);
            // equals covers every field a ToolUseResult has: exactly what a plain error carries, nothing more.
            assertThat(toModel).isEqualTo(ToolUseResult.error("tu-1", "AUTH_DENIED: no access").notRetryable());
            assertThat(toModel.getContent()).doesNotContain("secret detail");
        }

        @Test
        void anUnanticipatedFailureIsMarkedAsUnexpected() {
            ToolResult result = failWith(new IllegalStateException("boom"));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("Unexpected error: boom");
            assertThat(result.getException()).containsInstanceOf(IllegalStateException.class);
        }

        @Test
        void aCheckedExceptionIsCaughtToo() {
            // doExecute is declared `throws Exception` so a tool need not wrap what it calls; execute() being final
            // is what makes that safe, since the no-throw rule is kept in one place rather than by every subclass.
            ToolResult result = failWith(new java.io.IOException("disk gone"));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("Unexpected error: disk gone");
        }

        @Test
        void aToolThatRendersNothingIsReportedRatherThanReturningNull() {
            ToolResult result = new NullRenderingTool().execute(ToolInput.of(Map.of("message", "hi")),
                    ToolContext.empty());

            assertThat(result).isNotNull();
            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("NullRendering").contains("produced no result");
        }
    }
}
