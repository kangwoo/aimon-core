package at.aimon.core.llm.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmStreamingOptionsTest {

    @Test
    void defaultsHaveDocumentedFlagValues() {
        LlmStreamingOptions opts = LlmStreamingOptions.defaults();
        assertThat(opts.isBufferUntilFirstSuccess()).isFalse();
        assertThat(opts.isIncludeUsage()).isTrue();
    }

    @Test
    void defaultsAreSingletonInstance() {
        assertThat(LlmStreamingOptions.defaults()).isSameAs(LlmStreamingOptions.defaults());
    }

    @Test
    void builderOverridesAreReflectedInGetters() {
        LlmStreamingOptions opts = LlmStreamingOptions.builder().bufferUntilFirstSuccess(true).includeUsage(false)
                .build();

        assertThat(opts.isBufferUntilFirstSuccess()).isTrue();
        assertThat(opts.isIncludeUsage()).isFalse();
    }

    @Test
    void toStringContainsFlagValues() {
        LlmStreamingOptions opts = LlmStreamingOptions.builder().bufferUntilFirstSuccess(true).build();
        assertThat(opts.toString()).contains("bufferUntilFirstSuccess=true").contains("includeUsage=true");
    }
}
