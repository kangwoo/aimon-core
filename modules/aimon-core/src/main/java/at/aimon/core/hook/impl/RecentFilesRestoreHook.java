package at.aimon.core.hook.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostCompactHook;
import at.aimon.core.hook.execution.HookResult;

/**
 * {@link PostCompactHook} that re-attaches the most recently {@code Read}-accessed files after a compaction so the
 * agent does not lose track of in-flight file context.
 *
 * <p>
 * After a successful compaction the {@link PostCompactContext#getRecentReadFilePaths() recent file path snapshot} is
 * inspected; up to {@code maxFiles} most-recent paths are re-read through the supplied {@code Read} tool and appended
 * as a single user message containing labelled blocks for each file. The hook is idempotent — re-attaching the same
 * file twice is harmless.
 *
 * <p>
 * The hook is intentionally non-blocking and tolerant: any per-file failure is logged at WARN and skipped rather than
 * aborting the post-compact phase. Per the framework hook contract for {@link PostCompactHook}, the
 * {@code execute(...)} method must never throw.
 *
 * <h2>Wiring</h2>
 *
 * <pre>{@code
 * Tool readTool = new ReadTool(virtualFileSystem);
 * RecentFilesRestoreHook hook = new RecentFilesRestoreHook(readTool, 5);
 * hookRegistry.register(PostCompactHook.class, hook);
 * }</pre>
 *
 * <p>
 * Thread-safe — holds only its immutable dependencies.
 */
public final class RecentFilesRestoreHook implements PostCompactHook {

    /** Default cap on how many recently-read files to re-attach after a compaction. */
    public static final int DEFAULT_MAX_FILES = 5;

    private static final Logger log = LoggerFactory.getLogger(RecentFilesRestoreHook.class);
    // @formatter:off
    private static final String HEADER
            = "[System note: re-attaching recently-read files after conversation compaction]";
    // @formatter:on

    private final Tool readTool;
    private final int maxFiles;

    /**
     * Creates a hook that re-attaches up to {@link #DEFAULT_MAX_FILES} files using the supplied {@code Read} tool.
     *
     * @param readTool
     *            the {@code Read} tool used to fetch file contents (must not be null)
     */
    public RecentFilesRestoreHook(Tool readTool) {
        this(readTool, DEFAULT_MAX_FILES);
    }

    /**
     * Creates a hook with an explicit cap on the number of files to re-attach.
     *
     * @param readTool
     *            the {@code Read} tool used to fetch file contents (must not be null)
     * @param maxFiles
     *            maximum number of recent files to re-attach (must be &gt;= 1)
     * @throws IllegalArgumentException
     *             if {@code maxFiles < 1}
     */
    public RecentFilesRestoreHook(Tool readTool, int maxFiles) {
        this.readTool = Objects.requireNonNull(readTool, "readTool must not be null");
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles must be >= 1, got: " + maxFiles);
        }
        this.maxFiles = maxFiles;
    }

    @Override
    public HookResult execute(PostCompactContext context) {
        Objects.requireNonNull(context, "context must not be null");

        final List<String> selected = selectMostRecent(context.getRecentReadFilePaths(), maxFiles);
        if (selected.isEmpty()) {
            return HookResult.success();
        }

        final List<ReadOutcome> outcomes = readEach(selected);
        if (outcomes.isEmpty()) {
            return HookResult.success();
        }

        final String body = formatRestoreMessage(outcomes);
        final TranscriptBuffer memory = context.getTranscriptBuffer();
        memory.addUserMessage(body);
        log.info("Re-attached {} recently-read file(s) after compaction (session={})", outcomes.size(),
                memory.getSessionId());
        return HookResult.success();
    }

    private static List<String> selectMostRecent(List<String> all, int limit) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        final int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size());
    }

    private List<ReadOutcome> readEach(List<String> paths) {
        final List<ReadOutcome> outcomes = new ArrayList<>(paths.size());
        for (String path : paths) {
            try {
                final ToolResult result = readTool.execute(ToolInput.of("file_path", path), ToolContext.empty());
                if (result.isError()) {
                    log.warn("Skipping re-attach for {} — Read returned error: {}", path, result.getContent());
                    continue;
                }
                outcomes.add(new ReadOutcome(path, result.getContent()));
            } catch (RuntimeException e) {
                log.warn("Skipping re-attach for {} — Read threw {}: {}", path, e.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        return outcomes;
    }

    private static String formatRestoreMessage(List<ReadOutcome> outcomes) {
        final StringBuilder sb = new StringBuilder(HEADER);
        for (ReadOutcome outcome : outcomes) {
            sb.append("\n\n=== ").append(outcome.path).append(" ===\n").append(outcome.content);
        }
        return sb.toString();
    }

    private static final class ReadOutcome {
        private final String path;
        private final String content;

        private ReadOutcome(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }
}
