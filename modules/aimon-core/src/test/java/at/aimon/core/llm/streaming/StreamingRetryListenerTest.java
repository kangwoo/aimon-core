package at.aimon.core.llm.streaming;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class StreamingRetryListenerTest {

    @Test
    void noopListenerSwallowsInvocations() {
        assertThatCode(() -> StreamingRetryListener.NOOP.onRetry(0, 1, "5xx_retry")).doesNotThrowAnyException();
    }
}
