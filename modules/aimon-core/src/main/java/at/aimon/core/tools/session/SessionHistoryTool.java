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
import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogPage;
import at.aimon.core.agent.session.transcript.SessionLogReadCache;
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
 * {@code maxResultChars}. A range that cannot be read is reported once, however many windows it spans.
 *
 * <p>
 * <b>Long messages</b> come back in parts: a message longer than {@code maxResultChars} is cut with a note naming the
 * {@code offset} to pass with its {@code seq} for the next part, so an elided original — elided because it was large —
 * can always be read in full.
 *
 * <p>
 * One call loads each sealed segment at most once, through a {@link SessionLogReadCache} that lives only as long as
 * the call; the tool keeps nothing between calls.
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
                + " case-insensitive text search (pass query). A long message comes back in parts; pass the offset"
                + " the result names to read the next part. Covers this session only, since its last /clear.",
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
                                "The seq of one message to read, e.g. the N of '[tool result elided: seq=N]'. Give"
                                        + " either seq or query."),
                        "offset",
                        Map.of("type", "integer", "description",
                                "With seq: the character to start reading the message at (default 0). A message"
                                        + " longer than one result is cut with a note giving the offset of the next"
                                        + " part."),
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
            final Integer offset = input.getIntegerOrNull("offset");
            if (seq != null) {
                if (offset != null && offset < 0) {
                    return ToolResult.error("'offset' must be >= 0, got: " + offset);
                }
                return lookUp(source, seq, offset == null ? 0 : offset);
            }
            if (offset != null) {
                return ToolResult.error("'offset' applies to seq only.");
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

    private ToolResult lookUp(SessionLogSource source, long seq, int offset) {
        final SessionLogState state = source.currentLog();
        if (seq < state.getFloorSeq() || seq >= state.getNextSeq()) {
            return ToolResult.error("seq " + seq + " is not in this session's history, which holds seqs "
                    + state.getFloorSeq() + " to " + (state.getNextSeq() - 1) + " (anything before /clear is gone).");
        }
        final SessionLogPage read = source.readRange(state, Math.max(state.getFloorSeq(), seq - SEQ_WINDOW),
                Math.min(state.getNextSeq(), seq + SEQ_WINDOW + 1), SessionLogReadCache.create());
        for (SeqRange gap : read.getGaps()) {
            if (gap.contains(seq)) {
                return ToolResult.error("seq " + seq + " is unavailable: " + SessionLogPage.gapText(gap));
            }
        }
        final List<SessionLogEntry> conversation = new ArrayList<>();
        for (SessionLogEntry entry : read.getEntries()) {
            if (entry.getOrigin() == LogOrigin.CONVERSATION && entry.getMessage() != null) {
                conversation.add(entry);
            }
        }
        for (int i = 0; i < conversation.size(); i++) {
            final SessionLogEntry entry = conversation.get(i);
            if (entry.getSeq() != seq) {
                continue;
            }
            final int length = textOf(entry.getMessage()).length();
            if (offset > 0 && offset >= length) {
                return ToolResult.error("offset " + offset + " is past the end of seq " + seq + ", which has " + length
                        + " characters.");
            }
            final StringBuilder out = new StringBuilder();
            if (offset > 0) {
                // A later part stands alone: its neighbours were shown with the first one.
                appendLine(out, entry, true, part(entry, offset));
            } else {
                appendWithContext(out, conversation, i);
            }
            return ToolResult.success(out.toString().trim());
        }
        return ToolResult.error("seq " + seq + " is not a conversation message; only what was said in this session"
                + " can be read back.");
    }

    private ToolResult search(SessionLogSource source, String query, int limit) {
        final SessionLogState state = source.currentLog();
        final String needle = query.toLowerCase(Locale.ROOT);
        final SessionLogReadCache cache = SessionLogReadCache.create();
        final List<SessionLogEntry> newestFirst = new ArrayList<>();
        // Oldest first, merged: a range that spans many windows arrives once per window and is reported once.
        final List<SeqRange> gaps = new ArrayList<>();
        long tokens = 0;
        boolean truncated = false;
        long to = state.getNextSeq();
        while (to > state.getFloorSeq() && !truncated) {
            final long from = Math.max(state.getFloorSeq(), to - SCAN_WINDOW);
            final SessionLogPage window = source.readRange(state, from, to, cache);
            final List<SeqRange> windowGaps = window.getGaps();
            for (int g = windowGaps.size() - 1; g >= 0; g--) {
                prependMerged(gaps, windowGaps.get(g));
            }
            final List<SessionLogEntry> entries = window.getEntries();
            for (int i = entries.size() - 1; i >= 0; i--) {
                final SessionLogEntry entry = entries.get(i);
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
        for (SeqRange gap : gaps) {
            out.append("Not searched, could not be read: ").append(SessionLogPage.gapText(gap)).append('\n');
        }
        return ToolResult.success(out.toString().trim());
    }

    /**
     * Adds {@code gap} — no newer than any range in {@code gaps}, since the scan runs backwards — to the front of the
     * oldest-first list, merged with the first range when the two touch or overlap.
     */
    private static void prependMerged(List<SeqRange> gaps, SeqRange gap) {
        if (!gaps.isEmpty() && gap.getToSeq() >= gaps.get(0).getFromSeq()) {
            final SeqRange first = gaps.get(0);
            gaps.set(0, SeqRange.of(Math.min(gap.getFromSeq(), first.getFromSeq()),
                    Math.max(gap.getToSeq(), first.getToSeq())));
            return;
        }
        gaps.add(0, gap);
    }

    private void appendWithContext(StringBuilder out, List<SessionLogEntry> entries, int index) {
        final int from = Math.max(0, index - CONTEXT_RADIUS);
        final int to = Math.min(entries.size(), index + CONTEXT_RADIUS + 1);
        for (int i = from; i < to; i++) {
            appendLine(out, entries.get(i), i == index, part(entries.get(i), 0));
        }
    }

    private static void appendLine(StringBuilder out, SessionLogEntry entry, boolean marked, String text) {
        out.append(marked ? ">> " : "   ").append("[seq ").append(entry.getSeq()).append("] ")
                .append(entry.getMessage().getRole().name().toLowerCase(Locale.ROOT)).append(": ").append(text)
                .append('\n');
    }

    /**
     * The part of the entry's text starting at {@code offset}, at most {@code maxResultChars} long. A cut part says how
     * much is left and the offset that reads it; a part after the first says where it starts.
     */
    private String part(SessionLogEntry entry, int offset) {
        final String text = textOf(entry.getMessage());
        int end = (int) Math.min(text.length(), (long) offset + maxResultChars);
        if (end < text.length() && end - offset > 1 && Character.isHighSurrogate(text.charAt(end - 1))) {
            // Never split a surrogate pair: the next part starts with the whole character instead.
            end--;
        }
        final StringBuilder part = new StringBuilder();
        if (offset > 0) {
            part.append("[characters ").append(offset).append('-').append(end).append(" of ").append(text.length())
                    .append("] ");
        }
        part.append(text, offset, end);
        if (end < text.length()) {
            part.append(" … [").append(text.length() - end).append(" more characters; read on with seq=")
                    .append(entry.getSeq()).append(", offset=").append(end).append(']');
        }
        return part.toString();
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
