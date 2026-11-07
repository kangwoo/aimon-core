package at.aimon.core.tools.code;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.EnvironmentNotices;

/**
 * The language-change notice ({@code docs/design/tool/programmatic-tool-calling.md} §6.2): when the language
 * {@code ExecuteCode} runs changes between executions of a session — its environment went from host to sandboxed — the
 * model is told, once, in an iteration's observation: {@code ExecuteCode now runs Python (was JavaScript)}.
 *
 * <p>
 * "Which language did this session use last" is not a field. It is read from the conversation the iteration sends to
 * the model, which is record-side state and so survives restarts and node moves: the summary line every
 * {@code ExecuteCode} observation ends with names its language ({@link ExecuteCodeObservation}), and a notice once
 * delivered is itself in the conversation. The condition is the right one too — the description the model reads on
 * every call is always current, and only its own past programs, still in view, can mislead it.
 *
 * <p>
 * The view is scanned backwards for the newest <em>known</em> marker: an {@code ExecuteCode} program whose result's
 * summary is readable — in the view, or in the log when the view elided the body — or a delivered notice. A program
 * whose summary cannot be read anywhere (the log copy was sealed or rewritten, the history predates the marker, a hook
 * rewrote the text) is skipped; if only such programs are in view, a conservative notice is sent instead, once — it is
 * then the newest known marker.
 *
 * <p>
 * Stateless; all methods are static.
 */
public final class ExecuteCodeLanguageNotice {

    /** The summary line of an {@code ExecuteCode} observation; group 1 is the language's display name. */
    private static final Pattern SUMMARY = Pattern
            .compile("^\\[" + ExecuteCodeTool.TOOL_NAME + " \\((JavaScript|Python)\\): .*\\]$");
    private static final String NOTICE_HEAD = ExecuteCodeTool.TOOL_NAME + " now runs ";
    /** A delivered notice, as {@link EnvironmentNotices#render} writes it; group 1 is the language's display name. */
    private static final Pattern NOTICE = Pattern
            .compile("^" + Pattern.quote(EnvironmentNotices.PREFIX + NOTICE_HEAD) + "(JavaScript|Python)\\b.*$");
    private static final String FEEDBACK_OPEN = "<system-reminder key=\"" + HookFeedback.REMINDER_KEY + "\">";
    private static final String REMINDER_CLOSE = "</system-reminder>";

    private ExecuteCodeLanguageNotice() {
    }

    /**
     * Returns the notice this iteration should deliver, or empty when there is none to deliver.
     *
     * @param view
     *            the conversation this iteration sends to the model — the context engine's view, not the log (must not
     *            be null)
     * @param loggedResult
     *            looks a tool result's content up in the log by its tool-use id, for a body the view elided (must not
     *            be null)
     * @param offered
     *            this iteration's {@code ExecuteCode} descriptor, offered (must not be null)
     * @return the notice text, without the {@code [environment] } prefix, or empty
     */
    public static Optional<String> check(List<Message> view, Function<String, Optional<String>> loggedResult,
            ToolDescriptor offered) {
        Objects.requireNonNull(view, "view cannot be null");
        Objects.requireNonNull(loggedResult, "loggedResult cannot be null");
        final Optional<String> language = Objects.requireNonNull(offered, "offered cannot be null").getCodeLanguage();
        if (language.isEmpty()) {
            return Optional.empty();
        }
        final String now = ExecuteCodeDescription.displayName(language.get());
        // Tool results come after the tool_use they answer, so a backwards scan meets them first.
        final Map<String, String> viewResults = new HashMap<>();
        boolean unreadable = false;
        for (int i = view.size() - 1; i >= 0; i--) {
            final Message message = view.get(i);
            final List<ToolUseResult> results = message.getToolUseResults();
            for (int r = results.size() - 1; r >= 0; r--) {
                final ToolUseResult result = results.get(r);
                final String content = result.getContent() == null ? "" : result.getContent();
                viewResults.put(result.getToolUseId(), content);
                final Optional<String> noticed = noticedLanguage(content)
                        .or(() -> loggedResult.apply(result.getToolUseId()).filter(logged -> !logged.equals(content))
                                .flatMap(ExecuteCodeLanguageNotice::noticedLanguage));
                if (noticed.isPresent()) {
                    return verdict(now, noticed.get());
                }
            }
            final List<ToolUse> toolUses = message.getToolUses();
            for (int u = toolUses.size() - 1; u >= 0; u--) {
                final ToolUse toolUse = toolUses.get(u);
                if (!ExecuteCodeTool.TOOL_NAME.equals(toolUse.getName())) {
                    continue;
                }
                final String inView = viewResults.get(toolUse.getId());
                final Optional<String> ran = Optional.ofNullable(inView).flatMap(ExecuteCodeLanguageNotice::languageOf)
                        .or(() -> loggedResult.apply(toolUse.getId()).flatMap(ExecuteCodeLanguageNotice::languageOf));
                if (ran.isPresent()) {
                    return verdict(now, ran.get());
                }
                unreadable = true;
            }
        }
        return unreadable
                ? Optional.of(NOTICE_HEAD + now + "; earlier " + ExecuteCodeTool.TOOL_NAME
                        + " programs in this conversation may be in another language")
                : Optional.empty();
    }

    /**
     * Returns a lookup of tool results by tool-use id over {@code log}, read once, on the first lookup — so an
     * iteration whose view resolves every marker never reads the log.
     *
     * @param log
     *            supplies the messages of the session's log, append-only (must not be null)
     * @return the lookup (never null); not thread-safe, for the one iteration that asked for it
     */
    public static Function<String, Optional<String>> loggedResults(Supplier<List<Message>> log) {
        Objects.requireNonNull(log, "log cannot be null");
        final Map<String, String> byId = new HashMap<>();
        final boolean[] read = {false};
        return toolUseId -> {
            if (!read[0]) {
                read[0] = true;
                for (Message message : log.get()) {
                    for (ToolUseResult result : message.getToolUseResults()) {
                        if (result.getContent() != null) {
                            byId.put(result.getToolUseId(), result.getContent());
                        }
                    }
                }
            }
            return Optional.ofNullable(byId.get(toolUseId));
        };
    }

    /**
     * Returns the language an {@code ExecuteCode} result says its program ran in: the last line of the tool's own text
     * — a trailing hook-feedback block stripped first — when it is the observation's summary line. A program cannot
     * forge it: what it prints always comes before the real summary.
     *
     * @param content
     *            the result's content as recorded (must not be null)
     * @return the language's display name ({@code JavaScript}, {@code Python}), or empty when the summary is not there
     */
    public static Optional<String> languageOf(String content) {
        String text = Objects.requireNonNull(content, "content cannot be null");
        if (text.endsWith(REMINDER_CLOSE)) {
            final int open = text.lastIndexOf(FEEDBACK_OPEN);
            if (open >= 0) {
                text = text.substring(0, open);
                if (text.endsWith("\n\n")) {
                    text = text.substring(0, text.length() - 2);
                }
            }
        }
        final int newline = text.lastIndexOf('\n');
        final Matcher matcher = SUMMARY.matcher(newline < 0 ? text : text.substring(newline + 1));
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The language a delivered notice names: a line among the {@code [environment] } lines at the result's head. */
    private static Optional<String> noticedLanguage(String content) {
        int start = 0;
        while (content.startsWith(EnvironmentNotices.PREFIX, start)) {
            final int end = content.indexOf('\n', start);
            final String line = end < 0 ? content.substring(start) : content.substring(start, end);
            final Matcher matcher = NOTICE.matcher(line);
            if (matcher.matches()) {
                return Optional.of(matcher.group(1));
            }
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return Optional.empty();
    }

    private static Optional<String> verdict(String now, String before) {
        return now.equals(before) ? Optional.empty() : Optional.of(NOTICE_HEAD + now + " (was " + before + ")");
    }
}
