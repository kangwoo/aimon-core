package at.aimon.core.tools.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogSource;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Lets the agent read back what the view no longer shows: a message by its seq — the number an elided tool result's
 * placeholder names — or the messages matching a search (context-engine §6).
 *
 * <p>
 * <b>Scope.</b> The current session's {@link LogOrigin#CONVERSATION} entries, sealed ranges included, read through
 * {@link SessionLogSource} from the running execution's buffer. Nothing before {@code /clear} — the reader follows the
 * manifest only — and nothing from another session; recall across sessions is peer memory's job. Everything returned
 * was sent to the LLM before, so this opens no new exposure.
 *
 * <p>
 * <b>Search</b> is a case-insensitive substring match with no index, so it is a linear scan: from the most recent
 * message back, up to {@code maxScanTokens}. When the limit stops it, the result says that older history was not
 * searched. Each match is shown with up to two conversation messages on either side, every message cut to
 * {@code maxResultChars}.
 *
 * <p>
 * Registered when the rolling context engine is wired; the default engine elides nothing, so there is no placeholder
 * to follow. Stateless and read-only.
 */
public class SessionHistoryTool extends AbstractTool {

    public static final String TOOL_NAME = "SessionHistory";

    /** Where the executor publishes the running session's log. Absent outside a session (forks, skill loops). */
    public static final ToolContextKey<SessionLogSource> LOG_SOURCE_KEY = ToolContextKey
            .of("session_history.log_source", SessionLogSource.class);

    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 20;
    public static final int DEFAULT_MAX_SCAN_TOKENS = 1_000_000;
    public static final int DEFAULT_MAX_RESULT_CHARS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryTool.class);

    private static final int CONTEXT_RADIUS = 2;
    /** How many seqs one backward read of a search covers. */
    private static final long SCAN_WINDOW = 64;
    /** How far around a looked-up seq to read, enough for its neighbours past a few synthetic entries. */
    private static final long SEQ_WINDOW = 16;
    private static final String GAP_PREFIX = "[history unavailable:";

    private final int maxScanTokens;
    private final int maxResultChars;
    private final TokenEstimator tokenEstimator;

    /** Creates the tool with the default scan limit (1M tokens) and result cut (2000 characters). */
    public SessionHistoryTool() {
        this(DEFAULT_MAX_SCAN_TOKENS, DEFAULT_MAX_RESULT_CHARS, new HeuristicTokenEstimator());
    }

    /**
     * @param maxScanTokens
     *            how many tokens of history a search reads, newest first (must be {@code >= 1})
     * @param maxResultChars
     *            the length each returned message is cut to (must be {@code >= 1})
     * @param tokenEstimator
     *            sizes scanned messages (must not be null)
     */
    public SessionHistoryTool(int maxScanTokens, int maxResultChars, TokenEstimator tokenEstimator) {
        super(TOOL_NAME, "Reads back earlier messages of this session that are no longer in your context: the original"
                + " of an elided tool result ('[tool result elided: seq=N]' — pass seq=N), or the messages matching a"
                + " case-insensitive text search (pass query). Covers this session only, since its last /clear.",
                createInputSchema());
        if (maxScanTokens < 1) {
            throw new IllegalArgumentException("maxScanTokens must be >= 1, got: " + maxScanTokens);
        }
        if (maxResultChars < 1) {
            throw new IllegalArgumentException("maxResultChars must be >= 1, got: " + maxResultChars);
        }
        this.maxScanTokens = maxScanTokens;
        this.maxResultChars = maxResultChars;
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("seq",
                        Map.of("type", "integer", "description",
                                "The seq of one message to read in full, e.g. the N of '[tool result elided: seq=N]'."
                                        + " Give either seq or query."),
                        "query",
                        Map.of("type", "string", "description",
                                "Text to search for, case-insensitive, most recent matches first. Give either seq"
                                        + " or query."),
                        "limit",
                        Map.of("type", "integer", "description", "How many matches to return for a query (default "
                                + DEFAULT_LIMIT + ", at most " + MAX_LIMIT + ").")),
                "required", List.of());
    }

    @Override
    public ConcurrencyBehavior getConcurrencyBehavior() {
        return ConcurrencyBehavior.CONCURRENT_SAFE;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        try {
            final SessionLogSource source = context.get(LOG_SOURCE_KEY).orElse(null);
            if (source == null) {
                return ToolResult.error("SessionHistory is only available inside a session; this execution has no"
                        + " session history to read.");
            }
            final Long seq = input.getLongOrNull("seq");
            final String query = input.getStringOrNull("query");
            if ((seq == null) == (query == null)) {
                return ToolResult.error("Provide exactly one of 'seq' or 'query'.");
            }
            if (seq != null) {
                return lookUp(source, seq);
            }
            if (query.isBlank()) {
                return ToolResult.error("'query' cannot be blank.");
            }
            final int limit = input.getInteger("limit", DEFAULT_LIMIT);
            if (limit < 1) {
                return ToolResult.error("'limit' must be >= 1, got: " + limit);
            }
            return search(source, query, Math.min(limit, MAX_LIMIT));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid SessionHistory input: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("SessionHistory failed: {}", e.getMessage(), e);
            return ToolResult.error("Reading the session history failed: " + e.getMessage());
        }
    }

    private ToolResult lookUp(SessionLogSource source, long seq) {
        final SessionLogState state = source.currentLog();
        if (seq < state.getFloorSeq() || seq >= state.getNextSeq()) {
            return ToolResult.error("seq " + seq + " is not in this session's history, which holds seqs "
                    + state.getFloorSeq() + " to " + (state.getNextSeq() - 1) + " (anything before /clear is gone).");
        }
        final List<SessionLogEntry> read = source.read(state, Math.max(state.getFloorSeq(), seq - SEQ_WINDOW),
                Math.min(state.getNextSeq(), seq + SEQ_WINDOW + 1));
        final List<SessionLogEntry> conversation = new ArrayList<>();
        for (SessionLogEntry entry : read) {
            if (isGap(entry) && covers(entry, seq)) {
                return ToolResult.error("seq " + seq + " is unavailable: " + entry.getMessage().getContent());
            }
            if (entry.getOrigin() == LogOrigin.CONVERSATION) {
                conversation.add(entry);
            }
        }
        for (int i = 0; i < conversation.size(); i++) {
            if (conversation.get(i).getSeq() == seq) {
                final StringBuilder out = new StringBuilder();
                appendWithContext(out, conversation, i);
                return ToolResult.success(out.toString().trim());
            }
        }
        return ToolResult.error("seq " + seq + " is not a conversation message; only what was said in this session"
                + " can be read back.");
    }

    private ToolResult search(SessionLogSource source, String query, int limit) {
        final SessionLogState state = source.currentLog();
        final String needle = query.toLowerCase(Locale.ROOT);
        final List<SessionLogEntry> newestFirst = new ArrayList<>();
        final List<String> gaps = new ArrayList<>();
        long tokens = 0;
        boolean truncated = false;
        long to = state.getNextSeq();
        while (to > state.getFloorSeq() && !truncated) {
            final long from = Math.max(state.getFloorSeq(), to - SCAN_WINDOW);
            final List<SessionLogEntry> window = source.read(state, from, to);
            for (int i = window.size() - 1; i >= 0; i--) {
                final SessionLogEntry entry = window.get(i);
                if (isGap(entry)) {
                    gaps.add(0, entry.getMessage().getContent());
                    continue;
                }
                if (entry.getOrigin() != LogOrigin.CONVERSATION || entry.getMessage() == null) {
                    continue;
                }
                tokens += tokenEstimator.estimateMessage(entry.getMessage());
                if (tokens > maxScanTokens) {
                    truncated = true;
                    break;
                }
                newestFirst.add(entry);
            }
            to = from;
        }
        final List<SessionLogEntry> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        final StringBuilder out = new StringBuilder();
        int found = 0;
        for (int i = oldestFirst.size() - 1; i >= 0 && found < limit; i--) {
            if (textOf(oldestFirst.get(i).getMessage()).toLowerCase(Locale.ROOT).contains(needle)) {
                out.append("=== match at seq ").append(oldestFirst.get(i).getSeq()).append(" ===\n");
                appendWithContext(out, oldestFirst, i);
                out.append('\n');
                found++;
            }
        }
        if (found == 0) {
            out.append("No message in this session's history matches '").append(query).append("'.\n");
        }
        if (truncated) {
            out.append("Older history was not searched: the search stops after ").append(maxScanTokens)
                    .append(" tokens.\n");
        }
        for (String gap : gaps) {
            out.append("Not searched, could not be read: ").append(gap).append('\n');
        }
        return ToolResult.success(out.toString().trim());
    }

    private void appendWithContext(StringBuilder out, List<SessionLogEntry> entries, int index) {
        final int from = Math.max(0, index - CONTEXT_RADIUS);
        final int to = Math.min(entries.size(), index + CONTEXT_RADIUS + 1);
        for (int i = from; i < to; i++) {
            final SessionLogEntry entry = entries.get(i);
            out.append(i == index ? ">> " : "   ").append("[seq ").append(entry.getSeq()).append("] ")
                    .append(entry.getMessage().getRole().name().toLowerCase(Locale.ROOT)).append(": ")
                    .append(cut(textOf(entry.getMessage()))).append('\n');
        }
    }

    private String cut(String text) {
        if (text.length() <= maxResultChars) {
            return text;
        }
        return text.substring(0, maxResultChars) + " … [" + (text.length() - maxResultChars) + " more characters]";
    }

    private static boolean isGap(SessionLogEntry entry) {
        return entry.getOrigin() == LogOrigin.SYNTHETIC && entry.getMessage() != null
                && entry.getMessage().getContent() != null && entry.getMessage().getContent().startsWith(GAP_PREFIX);
    }

    /** Whether a gap entry, which stands at its range's first seq, reports a range containing {@code seq}. */
    private static boolean covers(SessionLogEntry gap, long seq) {
        final String text = gap.getMessage().getContent();
        final int dots = text.lastIndexOf("..");
        try {
            final long last = Long.parseLong(text.substring(dots + 2, text.length() - 1).trim());
            return seq >= gap.getSeq() && seq <= last;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String textOf(Message message) {
        final StringBuilder sb = new StringBuilder();
        if (message.getContent() != null) {
            sb.append(message.getContent());
        }
        for (ToolUse toolUse : message.getToolUses()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("(tool_use ").append(toolUse.getName()).append(' ').append(toolUse.getInput()).append(')');
        }
        for (ToolUseResult result : message.getToolUseResults()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(result.isError() ? "(tool error) " : "").append(result.getContent());
        }
        return sb.toString();
    }
}
