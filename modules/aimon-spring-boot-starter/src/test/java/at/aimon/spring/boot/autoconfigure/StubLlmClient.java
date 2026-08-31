package at.aimon.spring.boot.autoconfigure;

import java.util.List;

import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

/**
 * An {@link LlmClient} that never talks to anything.
 *
 * <p>
 * The slice tests assemble a real stack but never run a turn, so the only thing this has to do is exist and be
 * distinguishable from the vendor clients the starter would otherwise build. Deliberately not
 * {@link AutoCloseable}: the destroy-method audit asserts which beans Spring would close, and a closeable stub
 * would make that assertion pass for the wrong reason.
 */
class StubLlmClient implements LlmClient {

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public String getProviderName() {
        return "stub";
    }

}
