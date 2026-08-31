package at.aimon.bootstrap.spec;

import java.util.Objects;

import at.aimon.core.llm.LlmClient;

/**
 * Declares which {@link LlmClient} the stack reasons with.
 *
 * <h2>Why this wraps an instance instead of naming a provider</h2>
 *
 * <p>
 * The obvious shape for this type would be {@code LlmSpec.anthropic(apiKey)} / {@code LlmSpec.openai(apiKey)}.
 * It is not what this module does, because {@code aimon-bootstrap} would then have to depend on
 * {@code aimon-llm-anthropic} <i>and</i> {@code aimon-llm-openai} — putting both vendor SDKs on the compile
 * classpath of every consumer, including the ones using neither. A neutral assembly layer that forces a
 * transitive dependency on two SDKs is not neutral.
 *
 * <p>
 * So provider selection stays one layer up: whoever knows the configuration (a Spring autoconfiguration slice,
 * a CLI factory, a test) builds the client and hands the instance over. That is consistent with the layering —
 * the configuration layer contributes <i>materials</i>, this layer only assembles them.
 *
 * <p>
 * The type exists rather than passing a bare {@code LlmClient} so decorators that must wrap the client before
 * it reaches the executor (tracing, cost accounting, fallback) have a declared home in a later step without
 * changing the spec's shape.
 */
public final class LlmSpec {

    private final LlmClient client;

    private LlmSpec(LlmClient client) {
        this.client = Objects.requireNonNull(client, "LLM client must not be null");
    }

    /**
     * Creates a spec around an already-constructed client.
     *
     * @param client
     *            the client the agent reasons with (must not be null)
     * @return the spec
     * @throws NullPointerException
     *             if {@code client} is null
     */
    public static LlmSpec of(LlmClient client) {
        return new LlmSpec(client);
    }

    /**
     * Returns the client to wire into the executor.
     *
     * @return the LLM client, never null
     */
    public LlmClient getClient() {
        return client;
    }

    @Override
    public String toString() {
        return "LlmSpec[" + client.getClass().getSimpleName() + "]";
    }
}
