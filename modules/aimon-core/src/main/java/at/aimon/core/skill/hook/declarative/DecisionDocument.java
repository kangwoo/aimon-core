package at.aimon.core.skill.hook.declarative;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookResult;

/**
 * Reads the JSON decision document an {@code http} or {@code mcp} hook action answers with:
 * {@code {"decision": "allow" | "deny" | "defer", "reason": "...", "feedback": "...", "updatedInput": {...}}}.
 *
 * <p>
 * One reader for both executors, so the two cannot disagree about which documents are a verdict. A document can state
 * its verdict in three places &mdash; the native {@code decision} and the two spellings an endpoint written for Claude
 * Code uses &mdash; and each is read on its own:
 * <ul>
 * <li>{@code decision}: {@code allow} or {@code defer} &rarr; allow; {@code deny}, or Claude Code's {@code block}
 * &rarr; deny with {@code reason}; any other text, or a value that is not text &rarr; unreadable.
 * <li>{@code hookSpecificOutput.permissionDecision}: {@code allow} &rarr; allow; {@code deny} &rarr; deny with
 * {@code permissionDecisionReason}; {@code ask} &rarr; ask, with {@code permissionDecisionReason} as the prompt; any
 * other text (Claude Code's {@code defer} included, see below), or a value that is not text &rarr; unreadable.
 * <li>{@code continue}: {@code false} &rarr; deny with {@code stopReason}; {@code true} says nothing; a value that is
 * not a boolean &rarr; unreadable.
 * </ul>
 * Values are matched case-insensitively, and a field that is absent or {@code null} says nothing.
 *
 * <p>
 * <b>The strictest statement is the verdict</b>, in the order deny &gt; unreadable &gt; ask &gt; allow: a document that
 * says {@code decision: allow} next to {@code permissionDecision: deny} is a deny. A deny is a verdict and blocks
 * whatever {@code failOpen} says; an unreadable statement is <em>no verdict</em> ({@code INVALID_RESPONSE}) &mdash; the
 * endpoint tried to decide and the decision cannot be read, and guessing "allow" for it would turn a refusal into a
 * pass. A document that states nothing is a verdict, allow (the endpoint carried feedback or an input rewrite only).
 *
 * <p>
 * <b>{@code ask}</b> becomes a {@link Decision#ASK} result, the verdict this framework already has for "the user
 * decides": on {@code preTool} the hook execution manager resolves it through its {@code AskPromptHandler}, which
 * denies unless a host or {@code AIMON_HOOK_ASK_DEFAULT} says otherwise. It is a verdict, so {@code failOpen} does not
 * open it.
 *
 * <p>
 * <b>{@code permissionDecision: defer}</b> is unreadable, although the native {@code decision: defer} is an allow. The
 * native one means "no opinion, the next hook decides". Claude Code's means "hold this tool call until the calling
 * application resumes it", which nothing here can do; running the tool instead would be the opposite of what the
 * endpoint asked for.
 *
 * <p>
 * <b>The deny reason</b> is the reason field of the statement that denied (the first one, in the order above), then
 * the document's {@code reason}, then the caller's default. It is never {@code feedback}, {@code systemMessage} or
 * {@code additionalContext}: those are addressed to the model as advice, and a deny reason is shown to it as the
 * refusal.
 *
 * <p>
 * {@code updatedInput} present and not an object &rarr; <em>no verdict</em>, unless the document denies: the endpoint
 * asked for the input to be rewritten and the rewrite cannot be applied, so the original input must not go through
 * as if it had been.
 *
 * <p>
 * Stateless; thread-safe.
 */
final class DecisionDocument {

    /** The prompt of an {@code ask} that carries no {@code permissionDecisionReason}. Fixed: it names no endpoint. */
    static final String DEFAULT_ASK_PROMPT = "A policy hook asked for confirmation of this tool call";

    private static final Logger log = LoggerFactory.getLogger(DecisionDocument.class);

    private DecisionDocument() {
        throw new AssertionError("This class should not be instantiated");
    }

    /** What one place in the document says, weakest first: {@link #compareTo} is the strictness order. */
    private enum Stance {
        SILENT, ALLOW, ASK, UNREADABLE, DENY
    }

    /** One statement: its stance, and the reason (deny, ask) or the not-run detail (unreadable) that goes with it. */
    private static final class Statement {

        private static final Statement SILENT = new Statement(Stance.SILENT, null);
        private static final Statement ALLOW = new Statement(Stance.ALLOW, null);

        private final Stance stance;
        private final String text;

        private Statement(Stance stance, String text) {
            this.stance = stance;
            this.text = text;
        }

        static Statement unreadable(String detail) {
            return new Statement(Stance.UNREADABLE, detail);
        }
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
     *            the reason to block with when a deny carries none (never null)
     * @param acceptClaudeCodeFeedback
     *            whether {@code systemMessage} and {@code hookSpecificOutput.additionalContext} are read as feedback
     *            when {@code feedback} is absent; the Claude Code spellings of a <em>verdict</em> are read either way
     * @return the outcome (never null)
     */
    static ActionCallOutcome read(JsonNode root, ObjectMapper objectMapper, String source, String defaultDenyReason,
            boolean acceptClaudeCodeFeedback) {
        final List<Statement> statements = List.of(nativeDecision(root, source), permissionDecision(root, source),
                continueFlag(root, source));
        Stance strictest = Stance.SILENT;
        for (Statement statement : statements) {
            if (statement.stance.compareTo(strictest) > 0) {
                strictest = statement.stance;
            }
        }
        if (strictest == Stance.DENY) {
            return ActionCallOutcome.verdict(HookResult.block(denyReason(statements, root, defaultDenyReason)));
        }
        if (strictest == Stance.UNREADABLE) {
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.INVALID_RESPONSE,
                    firstText(statements, Stance.UNREADABLE));
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

        if (strictest == Stance.ASK) {
            // The feedback slot of an ask is its prompt, as a deny's is its reason.
            final String prompt = firstText(statements, Stance.ASK);
            return ActionCallOutcome.verdict(HookResult.builder().decision(Decision.ASK)
                    .feedback(prompt != null ? prompt : DEFAULT_ASK_PROMPT).updatedInput(updatedInput).build());
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

    /** The native {@code decision}, plus {@code block}: a server that says block means block. */
    private static Statement nativeDecision(JsonNode root, String source) {
        final JsonNode node = root.get("decision");
        if (node == null || node.isNull()) {
            return Statement.SILENT;
        }
        if (!node.isTextual()) {
            log.warn("{} returned a 'decision' that is not text ({}); no verdict", source, node.getNodeType());
            return Statement.unreadable("decision is not text");
        }
        switch (node.asText().strip().toLowerCase(Locale.ROOT)) {
            case "allow", "defer" :
                return Statement.ALLOW;
            case "deny", "block" :
                return new Statement(Stance.DENY, textOrNull(root, "reason"));
            default :
                // The value is the endpoint's text and stays in the log.
                log.warn("{} returned an unknown 'decision' ({}); allowed values are allow, deny, defer, block; no"
                        + " verdict", source, node.asText());
                return Statement.unreadable("unknown decision value");
        }
    }

    /** Claude Code's {@code hookSpecificOutput.permissionDecision}. */
    private static Statement permissionDecision(JsonNode root, String source) {
        final JsonNode hso = root.get("hookSpecificOutput");
        final JsonNode node = hso != null && hso.isObject() ? hso.get("permissionDecision") : null;
        if (node == null || node.isNull()) {
            return Statement.SILENT;
        }
        if (!node.isTextual()) {
            log.warn("{} returned a 'permissionDecision' that is not text ({}); no verdict", source,
                    node.getNodeType());
            return Statement.unreadable("permissionDecision is not text");
        }
        switch (node.asText().strip().toLowerCase(Locale.ROOT)) {
            case "allow" :
                return Statement.ALLOW;
            case "deny" :
                return new Statement(Stance.DENY, textOrNull(hso, "permissionDecisionReason"));
            case "ask" :
                return new Statement(Stance.ASK, textOrNull(hso, "permissionDecisionReason"));
            default :
                log.warn("{} returned an unknown 'permissionDecision' ({}); allowed values are allow, deny, ask; no"
                        + " verdict", source, node.asText());
                return Statement.unreadable("unknown permissionDecision value");
        }
    }

    /** Claude Code's {@code continue: false}, which stops whatever the other fields say. */
    private static Statement continueFlag(JsonNode root, String source) {
        final JsonNode node = root.get("continue");
        if (node == null || node.isNull()) {
            return Statement.SILENT;
        }
        if (!node.isBoolean()) {
            log.warn("{} returned a 'continue' that is not a boolean ({}); no verdict", source, node.getNodeType());
            return Statement.unreadable("continue is not a boolean");
        }
        return node.booleanValue() ? Statement.SILENT : new Statement(Stance.DENY, textOrNull(root, "stopReason"));
    }

    private static String denyReason(List<Statement> statements, JsonNode root, String defaultDenyReason) {
        final String own = firstText(statements, Stance.DENY);
        if (own != null) {
            return own;
        }
        final String reason = textOrNull(root, "reason");
        return reason != null ? reason : defaultDenyReason;
    }

    /** The text of the first statement with this stance that carries one, or null. */
    private static String firstText(List<Statement> statements, Stance stance) {
        for (Statement statement : statements) {
            if (statement.stance == stance && statement.text != null) {
                return statement.text;
            }
        }
        return null;
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
