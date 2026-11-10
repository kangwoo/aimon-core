package at.aimon.cli.e2e;

import java.util.Objects;

import at.aimon.core.llm.LlmClient;
import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.openai.OpenAIConfig;
import at.aimon.core.llms.openai.OpenAILlmClient;

/**
 * The real model clients of the E2E. Default models follow the live-test precedent and can be overridden with
 * {@code AIMON_LLM_TEST_OPENAI_MODEL} / {@code AIMON_LLM_TEST_ANTHROPIC_MODEL}, for when a weak model will not write
 * code. The key goes straight into the client builder; no config object is ever logged.
 *
 * <p>
 * The Anthropic default is {@code claude-haiku-5-5}, which thinks by default; its {@code maxTokens} is 16000 so that
 * thinking cannot use up the reply.
 */
final class LiveModels {

    static final String OPENAI = "openai";
    static final String ANTHROPIC = "anthropic";

    private LiveModels() {
    }

    /** The model id a provider runs on in this run. */
    static String model(String provider) {
        final String override = System
                .getenv(OPENAI.equals(provider) ? "AIMON_LLM_TEST_OPENAI_MODEL" : "AIMON_LLM_TEST_ANTHROPIC_MODEL");
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        return OPENAI.equals(provider) ? "gpt-4o-mini" : "claude-haiku-5-5";
    }

    /**
     * Builds the client for {@code provider}, skipping the calling test when its key is not set.
     *
     * @return a client; close it if it is {@link AutoCloseable}
     */
    static LlmClient client(String provider) {
        final LiveKeys keys = LiveKeys.fromEnvironment();
        if (OPENAI.equals(provider)) {
            return new OpenAILlmClient(
                    OpenAIConfig.builder().apiKey(keys.openAi()).model(model(provider)).maxTokens(4000).build());
        }
        Objects.requireNonNull(provider, "provider cannot be null");
        return new AnthropicLlmClient(
                AnthropicConfig.builder().apiKey(keys.anthropic()).model(model(provider)).maxTokens(16000).build());
    }
}
