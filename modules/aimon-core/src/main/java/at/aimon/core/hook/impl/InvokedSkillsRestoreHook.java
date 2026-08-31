package at.aimon.core.hook.impl;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.compact.InvokedSkillRecord;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostCompactHook;
import at.aimon.core.hook.execution.HookResult;

/**
 * {@link PostCompactHook} that re-attaches the list of {@code Skill} invocations from the compacted segment so the
 * agent does not lose track of which skills it had already activated.
 *
 * <p>
 * After a successful compaction the {@link PostCompactContext#getInvokedSkills() invoked-skill snapshot} is inspected;
 * up to {@code maxRecords} most-recent records are formatted as a single bulleted user message. The hook is idempotent
 * — re-attaching the same record twice is harmless because the engine already deduplicates on (name, args).
 *
 * <p>
 * The hook is intentionally non-blocking: any RuntimeException thrown while formatting is logged and the post-compact
 * phase continues. Per the {@link PostCompactHook} contract, {@code execute(...)} must never throw.
 *
 * <h2>Wiring</h2>
 *
 * <pre>{@code
 * InvokedSkillsRestoreHook hook = new InvokedSkillsRestoreHook(10);
 * hookRegistry.register(HookEventType.POST_COMPACT, hook);
 * }</pre>
 *
 * <p>
 * Thread-safe — holds only its immutable configuration.
 */
public final class InvokedSkillsRestoreHook implements PostCompactHook {

    /** Default cap on how many invocation records to re-attach after a compaction. */
    public static final int DEFAULT_MAX_RECORDS = 10;

    /** Maximum length of the {@code args} preview included per record before truncation. */
    public static final int ARGS_PREVIEW_MAX_LENGTH = 80;

    private static final Logger log = LoggerFactory.getLogger(InvokedSkillsRestoreHook.class);
    private static final String HEADER = "[System note: skills invoked before conversation compaction]";

    private final int maxRecords;

    /** Creates a hook with {@link #DEFAULT_MAX_RECORDS}. */
    public InvokedSkillsRestoreHook() {
        this(DEFAULT_MAX_RECORDS);
    }

    /**
     * Creates a hook with an explicit cap on the number of records to re-attach.
     *
     * @param maxRecords
     *            maximum number of records to include in the restore message (must be &gt;= 1)
     * @throws IllegalArgumentException
     *             if {@code maxRecords < 1}
     */
    public InvokedSkillsRestoreHook(int maxRecords) {
        if (maxRecords < 1) {
            throw new IllegalArgumentException("maxRecords must be >= 1, got: " + maxRecords);
        }
        this.maxRecords = maxRecords;
    }

    @Override
    public HookResult execute(PostCompactContext context) {
        Objects.requireNonNull(context, "context must not be null");

        try {
            final List<InvokedSkillRecord> selected = selectMostRecent(context.getInvokedSkills(), maxRecords);
            if (selected.isEmpty()) {
                return HookResult.success();
            }

            final String body = formatRestoreMessage(selected);
            final TranscriptBuffer memory = context.getTranscriptBuffer();
            memory.addUserMessage(body);
            log.info("Re-attached {} invoked-skill record(s) after compaction (session={})", selected.size(),
                    memory.getSessionId());
            return HookResult.success();
        } catch (RuntimeException e) {
            log.warn("InvokedSkillsRestoreHook failed: {}", e.getMessage(), e);
            return HookResult.success();
        }
    }

    private static List<InvokedSkillRecord> selectMostRecent(List<InvokedSkillRecord> all, int limit) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        final int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size());
    }

    private static String formatRestoreMessage(List<InvokedSkillRecord> records) {
        final StringBuilder sb = new StringBuilder(HEADER);
        for (InvokedSkillRecord record : records) {
            sb.append("\n- ").append(record.getName());
            record.getArgsOptional().ifPresent(a -> sb.append(" args=\"").append(truncate(a)).append('"'));
        }
        return sb.toString();
    }

    private static String truncate(String args) {
        if (args.length() <= ARGS_PREVIEW_MAX_LENGTH) {
            return args;
        }
        return args.substring(0, ARGS_PREVIEW_MAX_LENGTH) + "…";
    }
}
