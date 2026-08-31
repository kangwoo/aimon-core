package at.aimon.core.llm;

/**
 * Represents the role of a message in a conversation.
 *
 * <p>
 * Roles define who is speaking in the conversation:
 *
 * <ul>
 * <li>USER: Messages from the user/human
 * <li>ASSISTANT: Messages from the LLM/AI
 * <li>TOOL: Tool execution results (converted to provider-specific format by LlmClient)
 * </ul>
 *
 * <p>
 * Note: SYSTEM role is not included as it's handled separately as a system prompt parameter in the LLM API.
 *
 * <p>
 * The TOOL role is used internally to represent tool execution results. LlmClient implementations are responsible for
 * converting this to the appropriate format for their specific API:
 *
 * <ul>
 * <li>Anthropic API: Converts TOOL messages to USER messages with tool_result content
 * <li>OpenAI API: Uses native "tool" role
 * </ul>
 */
public enum Role {
    /** Message from the user/human. */
    USER,

    /** Message from the LLM/AI assistant. */
    ASSISTANT,

    /**
     * Tool execution results.
     *
     * <p>
     * This role is used internally to represent tool execution results. LlmClient implementations convert this to the
     * appropriate provider-specific format.
     */
    TOOL
}
