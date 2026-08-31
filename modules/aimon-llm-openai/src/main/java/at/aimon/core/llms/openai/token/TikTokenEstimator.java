package at.aimon.core.llms.openai.token;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * {@link TokenEstimator} backed by the OpenAI <a href="https://github.com/openai/tiktoken">tiktoken</a> BPE encoder
 * (Java port: <a href="https://github.com/knuddelsgmbh/jtokkit">jtokkit</a>).
 *
 * <p>
 * Use this estimator when targeting an OpenAI chat-completion model — it returns BPE-accurate counts for text rather
 * than the byte-length heuristic used by {@link HeuristicTokenEstimator}. The structural overheads (per-message,
 * per-request, image/document fixed costs) follow the conventions documented in the
 * <a href="https://github.com/openai/openai-cookbook/blob/main/examples/How_to_count_tokens_with_tiktoken.ipynb">OpenAI
 * tokens cookbook</a>.
 *
 * <h2>Model resolution</h2>
 *
 * <ul>
 * <li>An explicit {@code modelName} (e.g. {@code "gpt-4o"}, {@code "gpt-4o-2024-08-06"}, {@code "gpt-4-turbo"}) is
 * mapped to its tiktoken encoding via the jtokkit registry. Versioned model names that the registry does not know
 * verbatim fall back to a prefix match against known {@code gpt-4o}, {@code gpt-4}, and {@code gpt-3.5} families.
 * <li>If no model is supplied, or the model name cannot be resolved, the estimator defaults to {@link
 * EncodingType#O200K_BASE} (the encoding shared by all current GPT-4o models). Over-estimation is preferred to
 * under-estimation per the {@link TokenEstimator} contract.
 * </ul>
 *
 * <p>
 * Image and document content blocks are counted with fixed safe upper bounds since their true cost depends on
 * server-side preprocessing the client cannot replicate. Tool uses count as the encoded JSON of the tool name and its
 * input map plus a small structural overhead.
 *
 * <p>
 * Stateless after construction and thread-safe — jtokkit's {@link Encoding} is documented as safe for concurrent use.
 */
public final class TikTokenEstimator implements TokenEstimator {

    /** Default encoding when no model is supplied or the supplied model is unknown. */
    public static final EncodingType DEFAULT_ENCODING = EncodingType.O200K_BASE;

    private static final int PER_MESSAGE_OVERHEAD_TOKENS = 3;
    private static final int PER_REQUEST_OVERHEAD_TOKENS = 3;
    private static final int IMAGE_BLOCK_TOKENS = 1500;
    private static final int DOCUMENT_BLOCK_TOKENS = 1000;
    private static final int TOOL_USE_OVERHEAD_TOKENS = 8;
    private static final int TOOL_RESULT_OVERHEAD_TOKENS = 6;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(TikTokenEstimator.class);

    private final Encoding encoding;
    private final String modelName;

    /** Creates an estimator using the default {@link #DEFAULT_ENCODING}. */
    public TikTokenEstimator() {
        this(null);
    }

    /**
     * Creates an estimator targeting the named OpenAI chat-completion model.
     *
     * @param modelName
     *            the OpenAI model identifier (e.g. {@code "gpt-4o"}); may be {@code null} to use the default encoding
     */
    public TikTokenEstimator(String modelName) {
        final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.modelName = modelName;
        this.encoding = resolveEncoding(registry, modelName);
    }

    private static Encoding resolveEncoding(EncodingRegistry registry, String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            final Optional<Encoding> exact = registry.getEncodingForModel(modelName);
            if (exact.isPresent()) {
                return exact.get();
            }
            final Optional<Encoding> byFamily = resolveByFamilyPrefix(registry, modelName);
            if (byFamily.isPresent()) {
                return byFamily.get();
            }
            log.debug("Unknown OpenAI model '{}', falling back to {} encoding", modelName, DEFAULT_ENCODING);
        }
        return registry.getEncoding(DEFAULT_ENCODING);
    }

    private static Optional<Encoding> resolveByFamilyPrefix(EncodingRegistry registry, String modelName) {
        if (modelName.startsWith("gpt-4o") || modelName.startsWith("o1") || modelName.startsWith("o3")) {
            return Optional.of(registry.getEncoding(EncodingType.O200K_BASE));
        }
        if (modelName.startsWith("gpt-4") || modelName.startsWith("gpt-3.5")) {
            return Optional.of(registry.getEncoding(EncodingType.CL100K_BASE));
        }
        return Optional.empty();
    }

    /**
     * @return the resolved model name (may be {@code null} when the default encoding is in use)
     */
    public String getModelName() {
        return modelName;
    }

    @Override
    public int estimate(String systemPrompt, List<Message> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");
        int total = PER_REQUEST_OVERHEAD_TOKENS;
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            total += PER_MESSAGE_OVERHEAD_TOKENS + estimateText(systemPrompt);
        }
        for (Message message : messages) {
            total += estimateMessage(message);
        }
        return total;
    }

    @Override
    public int estimateMessage(Message message) {
        Objects.requireNonNull(message, "message cannot be null");
        int total = PER_MESSAGE_OVERHEAD_TOKENS;
        for (ContentBlock block : message.getContentBlocks()) {
            total += estimateBlock(block);
        }
        for (ToolUse toolUse : message.getToolUses()) {
            total += estimateToolUse(toolUse);
        }
        for (ToolUseResult result : message.getToolUseResults()) {
            total += estimateToolUseResult(result);
        }
        return total;
    }

    @Override
    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    private int estimateBlock(ContentBlock block) {
        if (block instanceof TextContentBlock textBlock) {
            return estimateText(textBlock.asText());
        }
        if (block instanceof ImageContentBlock) {
            return IMAGE_BLOCK_TOKENS;
        }
        if (block instanceof DocumentContentBlock) {
            return DOCUMENT_BLOCK_TOKENS;
        }
        return estimateText(block.asText());
    }

    private int estimateToolUse(ToolUse toolUse) {
        int total = TOOL_USE_OVERHEAD_TOKENS;
        total += estimateText(toolUse.getName());
        total += estimateInputAsJson(toolUse.getInput());
        return total;
    }

    private int estimateToolUseResult(ToolUseResult result) {
        return TOOL_RESULT_OVERHEAD_TOKENS + estimateText(result.getContent());
    }

    private int estimateInputAsJson(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return 0;
        }
        try {
            return estimateText(JSON_MAPPER.writeValueAsString(input));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise tool input for token counting; falling back to per-entry estimate: {}",
                    e.getMessage());
            int total = 0;
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                total += estimateText(entry.getKey());
                total += estimateText(String.valueOf(entry.getValue()));
                total += 2;
            }
            return total;
        }
    }
}
