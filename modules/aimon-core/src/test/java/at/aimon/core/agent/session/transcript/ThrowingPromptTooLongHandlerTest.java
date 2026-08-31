package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.invoke.PromptTooLongEvent;

@DisplayName("ThrowingPromptTooLongHandler Tests")
class ThrowingPromptTooLongHandlerTest {

    private static LlmModel model() {
        return LlmModel.builder().name("gpt-test").build();
    }

    private static PromptTooLongEvent<TranscriptBuffer> event(LlmPromptTooLongException ex, TranscriptBuffer memory,
            int attempt) {
        return PromptTooLongEvent.<TranscriptBuffer>builder().exception(ex).model(model()).memorySnapshot(memory)
                .attempt(attempt).build();
    }

    @Test
    @DisplayName("handle() rethrows the triggering exception verbatim")
    void handle_rethrowsTriggeringException() {
        LlmPromptTooLongException trigger = new LlmPromptTooLongException("context too long");
        PromptTooLongEvent<TranscriptBuffer> evt = event(trigger, null, 1);

        LlmPromptTooLongException thrown = catchThrowableOfType(() -> ThrowingPromptTooLongHandler.INSTANCE.handle(evt),
                LlmPromptTooLongException.class);

        assertThat(thrown).isSameAs(trigger);
    }

    @Test
    @DisplayName("handle() preserves the cause chain of the rethrown exception")
    void handle_preservesCauseChain() {
        Throwable rootCause = new IllegalStateException("provider returned 400");
        LlmPromptTooLongException trigger = new LlmPromptTooLongException("context too long", rootCause);
        PromptTooLongEvent<TranscriptBuffer> evt = event(trigger, null, 1);

        assertThatThrownBy(() -> ThrowingPromptTooLongHandler.INSTANCE.handle(evt))
                .isInstanceOf(LlmPromptTooLongException.class).hasCause(rootCause);
    }

    @Test
    @DisplayName("INSTANCE is the same object across accesses")
    void instance_isSingleton() {
        assertThat(ThrowingPromptTooLongHandler.INSTANCE).isSameAs(ThrowingPromptTooLongHandler.INSTANCE);
    }

    @Test
    @DisplayName("handle() does not touch memorySnapshot — tolerates null memory")
    void handle_doesNotDereferenceMemory() {
        LlmPromptTooLongException trigger = new LlmPromptTooLongException("context too long");
        // Explicitly omit memorySnapshot — builder allows null.
        PromptTooLongEvent<TranscriptBuffer> evt = PromptTooLongEvent.<TranscriptBuffer>builder().exception(trigger)
                .model(model()).attempt(1).build();

        assertThat(evt.getMemorySnapshot()).isEmpty();

        assertThatThrownBy(() -> ThrowingPromptTooLongHandler.INSTANCE.handle(evt)).isSameAs(trigger);
    }

    @Test
    @DisplayName("handle() rejects null events with NullPointerException")
    void handle_nullEvent_throwsNpe() {
        assertThatThrownBy(() -> ThrowingPromptTooLongHandler.INSTANCE.handle(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("event");
    }
}
