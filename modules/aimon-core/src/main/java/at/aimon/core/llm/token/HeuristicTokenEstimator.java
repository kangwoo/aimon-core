package at.aimon.core.llm.token;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

/**
 * Heuristic {@link TokenEstimator} based on byte-length to token-count ratios.
 *
 * <p>
 * Approximations used:
 *
 * <ul>
 * <li>1 token ≈ 3.5 UTF-8 bytes (slightly conservative for English; accommodates other scripts via byte length)
 * <li>Each message adds a small role-overhead (~4 tokens)
 * <li>Image content blocks count as a fixed 1500 tokens
 * <li>Document content blocks count as a fixed 1000 tokens
 * <li>Tool uses and results count by JSON-like serialization length
 * </ul>
 *
 * <p>
 * Designed to over-estimate so that the compaction guard fires before the provider rejects the request. Stateless and
 * thread-safe.
 */
public final class HeuristicTokenEstimator implements TokenEstimator {

    private static final double BYTES_PER_TOKEN = 3.5;
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;
    private static final int IMAGE_BLOCK_TOKENS = 1500;
    private static final int DOCUMENT_BLOCK_TOKENS = 1000;
    private static final int TOOL_USE_OVERHEAD_TOKENS = 8;
    private static final int TOOL_RESULT_OVERHEAD_TOKENS = 6;

    @Override
    public int estimate(String systemPrompt, List<Message> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");
        int total = estimateText(systemPrompt);
        for (Message message : messages) {
            total += estimateMessage(message);
        }
        return total;
    }

    @Override
    public int estimateMessage(Message message) {
        Objects.requireNonNull(message, "message cannot be null");
        int total = MESSAGE_OVERHEAD_TOKENS;
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
        final int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return (int) Math.ceil(bytes / BYTES_PER_TOKEN);
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
        // Unknown block type — fall back to its text representation
        return estimateText(block.asText());
    }

    private int estimateToolUse(ToolUse toolUse) {
        int total = TOOL_USE_OVERHEAD_TOKENS;
        total += estimateText(toolUse.getName());
        total += estimateMap(toolUse.getInput());
        return total;
    }

    private int estimateToolUseResult(ToolUseResult result) {
        return TOOL_RESULT_OVERHEAD_TOKENS + estimateText(result.getContent());
    }

    private int estimateMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return 0;
        }
        // Approximate JSON serialization size: key="value", per entry overhead ~5 chars
        int total = 0;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            total += estimateText(entry.getKey());
            total += estimateText(String.valueOf(entry.getValue()));
            total += 2; // separator overhead
        }
        return total;
    }
}
