package at.aimon.core.skill.hook.declarative;

import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.execution.HookResult;

/**
 * Reads the JSON decision document an {@code http} or {@code mcp} hook action answers with:
 * {@code {"decision": "allow" | "deny" | "defer", "reason": "...", "feedback": "...", "updatedInput": {...}}}.
 *
 * <p>
 * One reader for both executors, so the two cannot disagree about which documents are a verdict:
 * <ul>
 * <li>{@code decision} absent or {@code null} &rarr; a verdict, allow (the endpoint carried feedback or an input
 * rewrite only, or nothing).
 * <li>{@code decision} is {@code allow} or {@code defer} (case-insensitive) &rarr; a verdict, allow.
 * <li>{@code decision} is {@code deny} &rarr; a verdict, block with {@code reason}.
 * <li>{@code decision} is anything else &mdash; not text, or text that is none of the three &rarr; <em>no verdict</em>
 * ({@code INVALID_RESPONSE}). The endpoint tried to decide and the decision cannot be read; guessing "allow" for a
 * value such as {@code block} would turn a refusal into a pass.
 * <li>{@code updatedInput} present and not an object &rarr; <em>no verdict</em>: the endpoint asked for the input to
 * be rewritten and the rewrite cannot be applied, so the original input must not go through as if it had been.
 * </ul>
 *
 * <p>
 * Stateless; thread-safe.
 */
final class DecisionDocument {

    private static final Logger log = LoggerFactory.getLogger(DecisionDocument.class);

    private DecisionDocument() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Maps a decision document to a verdict, or to the reason it carries none.
     *
     * @param root
     *            the parsed document; must be a JSON object
     * @param objectMapper
     *            mapper used to convert {@code updatedInput} (never null)
     * @param source
     *            what answered, for the log (never null)
     * @param defaultDenyReason
     *            the reason to block with when a {@code deny} carries none (never null)
     * @param acceptClaudeCodeFeedback
     *            whether {@code systemMessage} and {@code hookSpecificOutput.additionalContext} are read as feedback
     *            when {@code feedback} is absent
     * @return the outcome (never null)
     */
    static ActionCallOutcome read(JsonNode root, ObjectMapper objectMapper, String source, String defaultDenyReason,
            boolean acceptClaudeCodeFeedback) {
        final JsonNode decisionNode = root.get("decision");
        final boolean decided = decisionNode != null && !decisionNode.isNull();
        if (decided && !decisionNode.isTextual()) {
            log.warn("{} returned a 'decision' that is not text ({}); no verdict", source, decisionNode.getNodeType());
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.INVALID_RESPONSE, "decision is not text");
        }
        final String decision = decided ? decisionNode.asText().strip().toLowerCase(Locale.ROOT) : "";
        if ("deny".equals(decision)) {
            final String reason = textOrNull(root, "reason");
            return ActionCallOutcome.verdict(HookResult.block(reason != null ? reason : defaultDenyReason));
        }
        if (decided && !"allow".equals(decision) && !"defer".equals(decision)) {
            // The value is the endpoint's text and stays in the log.
            log.warn("{} returned an unknown 'decision' ({}); allowed values are allow, deny, defer; no verdict",
                    source, decisionNode.asText());
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.INVALID_RESPONSE, "unknown decision value");
        }

        final JsonNode updatedInputNode = root.get("updatedInput");
        ToolInput updatedInput = null;
        if (updatedInputNode != null && !updatedInputNode.isNull()) {
            if (!updatedInputNode.isObject()) {
                log.warn("{} returned an 'updatedInput' that is not an object ({}); no verdict", source,
                        updatedInputNode.getNodeType());
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.INVALID_RESPONSE,
                        "updatedInput is not an object");
            }
            try {
                @SuppressWarnings("unchecked")
                final Map<String, Object> map = objectMapper.convertValue(updatedInputNode, Map.class);
                updatedInput = ToolInput.of(map);
            } catch (IllegalArgumentException e) {
                log.warn("{} returned an 'updatedInput' that could not be converted; no verdict: {}", source,
                        e.getMessage());
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.INVALID_RESPONSE,
                        "updatedInput could not be converted");
            }
        }

        final String feedback = pickFeedback(root, acceptClaudeCodeFeedback);
        if (feedback == null && updatedInput == null) {
            return ActionCallOutcome.verdict(HookResult.success());
        }
        final HookResult.Builder b = HookResult.builder();
        if (feedback != null) {
            b.feedback(feedback);
        }
        if (updatedInput != null) {
            b.updatedInput(updatedInput);
        }
        return ActionCallOutcome.verdict(b.build());
    }

    private static String pickFeedback(JsonNode root, boolean acceptClaudeCodeFeedback) {
        final String f = textOrNull(root, "feedback");
        if (f != null || !acceptClaudeCodeFeedback) {
            return f;
        }
        // Claude Code-compatible aliases: systemMessage and hookSpecificOutput.additionalContext
        final String sysMsg = textOrNull(root, "systemMessage");
        if (sysMsg != null) {
            return sysMsg;
        }
        final JsonNode hso = root.get("hookSpecificOutput");
        if (hso != null && hso.isObject()) {
            return textOrNull(hso, "additionalContext");
        }
        return null;
    }

    private static String textOrNull(JsonNode root, String field) {
        final JsonNode n = root.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }
}
