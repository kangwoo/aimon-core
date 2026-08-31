package at.aimon.core.agent.context;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * {@link ContextProvider} that summarises the top level of the working directory, read through the
 * {@link VirtualFileSystem}.
 *
 * <p>
 * Lists the immediate entries of the VFS root and emits a capped, sorted overview (directories flagged with a trailing
 * {@code /}). When no filesystem is bound, the listing fails, or the directory is empty, the provider contributes
 * nothing. The entry cap keeps the block bounded on large directories; when entries are dropped the block says so.
 *
 * <p>
 * This block is optional context — a lightweight orientation aid, not an authoritative file index.
 */
public final class DirectorySummaryContextProvider implements ContextProvider {

    /** Stable block key. */
    public static final String BLOCK_KEY = "directory-summary";

    /** Default cap on the number of listed entries. */
    public static final int DEFAULT_MAX_ENTRIES = 50;

    private static final String LIST_ROOT = ".";

    private static final Logger log = LoggerFactory.getLogger(DirectorySummaryContextProvider.class);

    private final int maxEntries;

    /**
     * Creates a provider with the {@link #DEFAULT_MAX_ENTRIES default entry cap}.
     */
    public DirectorySummaryContextProvider() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Creates a provider with a custom entry cap.
     *
     * @param maxEntries
     *            the maximum number of entries to list (must be &gt;= 1)
     * @throws IllegalArgumentException
     *             if {@code maxEntries < 1}
     */
    public DirectorySummaryContextProvider(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got: " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public List<ContextBlock> provide(ContextAssemblyRequest request) {
        final VirtualFileSystem fileSystem = request.getFileSystem().orElse(null);
        if (fileSystem == null) {
            return List.of();
        }

        final List<String> entries;
        try {
            entries = fileSystem.list(LIST_ROOT);
        } catch (RuntimeException e) {
            log.debug("Working directory listing failed; omitting directory summary block: {}", e.getMessage());
            return List.of();
        }
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        final List<String> sorted = new ArrayList<>(entries);
        sorted.sort(String::compareTo);
        final int total = sorted.size();
        final int shown = Math.min(total, maxEntries);

        final StringBuilder body = new StringBuilder();
        body.append("Working directory contents (").append(total).append(total == 1 ? " entry" : " entries");
        if (shown < total) {
            body.append(", showing first ").append(shown);
        }
        body.append("):");
        for (int i = 0; i < shown; i++) {
            final String entry = sorted.get(i);
            body.append("\n- ").append(entry).append(isDirectory(fileSystem, entry) ? "/" : "");
        }
        return List.of(ContextBlock.system(BLOCK_KEY, body.toString()));
    }

    /**
     * Best-effort directory check; any failure is treated as "not a directory" so a single bad entry cannot break the
     * summary.
     */
    private static boolean isDirectory(VirtualFileSystem fileSystem, String entry) {
        try {
            return fileSystem.isDirectory(entry);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
