package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.prompt.SystemPromptParts;

/**
 * CTX-05 regression test for {@link LlmClient}'s parts-aware {@code sendMessage} default method.
 *
 * <p>
 * Verifies that providers that only implement the legacy {@link String}-based {@code sendMessage} continue to work
 * unchanged: the default parts-aware overload collapses the supplied {@link SystemPromptParts} via
 * {@link SystemPromptParts#concatenated()} and routes through the existing authoritative entry point.
 */
@DisplayName("LlmClient default sendMessage(SystemPromptParts, ...) delegation")
class LlmClientDefaultMethodTest {

    @Test
    @DisplayName("default parts overload delegates to the String overload using parts.concatenated()")
    void defaultPartsOverload_delegatesToStringOverload_usingConcatenated() {
        final StringOnlyLlmClient client = new StringOnlyLlmClient();

        final SystemPromptParts parts = SystemPromptParts.of(List.of(
                SystemPromptPart.builder().content("agent-instructions").staticness(Staticness.STATIC)
                        .kind("agent-content").build(),
                SystemPromptPart.builder().content("env-block").staticness(Staticness.DYNAMIC).kind("environment")
                        .build()));

        final LlmResponse response = client.sendMessage(parts, List.of(), List.of(), LlmModel.builder().build(),
                LlmCallMetadata.empty());

        assertThat(response).isNotNull();
        assertThat(client.invocationCount.get()).isEqualTo(1);
        // The String overload must have been invoked with the concatenated form of the parts.
        assertThat(client.lastSystemPrompt.get()).isEqualTo(parts.concatenated());
        assertThat(client.lastSystemPrompt.get()).isEqualTo("agent-instructions\n\nenv-block");
    }

    @Test
    @DisplayName("default parts overload rejects null parts with NullPointerException")
    void defaultPartsOverload_rejectsNullParts() {
        final StringOnlyLlmClient client = new StringOnlyLlmClient();

        assertThatNullPointerException().isThrownBy(() -> client.sendMessage((SystemPromptParts) null, List.of(),
                List.of(), LlmModel.builder().build(), LlmCallMetadata.empty()));
    }

    @Test
    @DisplayName("default parts overload with empty parts delegates with empty concatenated string")
    void defaultPartsOverload_emptyParts_delegatesWithEmptyString() {
        final StringOnlyLlmClient client = new StringOnlyLlmClient();

        client.sendMessage(SystemPromptParts.empty(), List.of(), List.of(), LlmModel.builder().build(),
                LlmCallMetadata.empty());

        assertThat(client.lastSystemPrompt.get()).isEmpty();
    }

    /**
     * A minimal {@link LlmClient} that implements <b>only</b> the authoritative {@code String}-based
     * {@code sendMessage} overload. Inherits the default parts-aware overload from the interface to exercise the
     * CTX-05 delegation path.
     */
    private static final class StringOnlyLlmClient implements LlmClient {
        final AtomicInteger invocationCount = new AtomicInteger();
        final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            invocationCount.incrementAndGet();
            lastSystemPrompt.set(systemPrompt);
            return LlmResponse.text("ok");
        }

        @Override
        public String getProviderName() {
            return "StringOnly";
        }

    }
}
