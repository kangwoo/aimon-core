package at.aimon.core.agent.context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * {@link ContextProvider} that reports the current git branch, read through the {@link VirtualFileSystem}.
 *
 * <p>
 * Per the design, git context is queried via the VFS rather than by shelling out to a {@code git} process: this
 * provider reads {@code .git/HEAD} and resolves the branch name (or a short commit id when the repository is in
 * detached-HEAD state). When no filesystem is bound, {@code .git/HEAD} is absent, or the read fails for any reason, the
 * provider contributes nothing — the block is simply omitted.
 *
 * <p>
 * Richer status (uncommitted changes, recent commits) requires executing git and belongs to a sandbox-backed provider;
 * this VFS-only provider intentionally stays limited to what {@code .git/HEAD} can cheaply yield.
 */
public final class GitStatusContextProvider implements ContextProvider {

    /** Stable block key. */
    public static final String BLOCK_KEY = "git-branch";

    private static final String HEAD_PATH = ".git/HEAD";
    private static final String REF_PREFIX = "ref: refs/heads/";
    private static final int MAX_HEAD_BYTES = 4096;
    private static final int SHORT_SHA_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(GitStatusContextProvider.class);

    @Override
    public List<ContextBlock> provide(ContextAssemblyRequest request) {
        final VirtualFileSystem fileSystem = request.getFileSystem().orElse(null);
        if (fileSystem == null) {
            return List.of();
        }
        final String head = readHead(fileSystem);
        if (head == null || head.isBlank()) {
            return List.of();
        }
        final String branch = resolveBranch(head.trim());
        if (branch.isEmpty()) {
            return List.of();
        }
        return List.of(ContextBlock.system(BLOCK_KEY, "Current git branch: " + branch));
    }

    /**
     * Reads {@code .git/HEAD} through the VFS, capping the read so a pathological file cannot exhaust memory. Any
     * failure (missing file, invalid path, backend error) resolves to {@code null} so the caller omits the block.
     */
    private static String readHead(VirtualFileSystem fileSystem) {
        try (InputStream in = fileSystem.read(HEAD_PATH)) {
            final byte[] buffer = new byte[MAX_HEAD_BYTES];
            int total = 0;
            int read;
            while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
                total += read;
            }
            return new String(buffer, 0, total, StandardCharsets.UTF_8);
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("git HEAD unavailable via VFS; omitting git branch block: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the branch name from the first line of {@code HEAD}. A symbolic ref ({@code ref: refs/heads/<branch>})
     * yields the branch; a raw commit id (detached HEAD) yields a short id.
     */
    private static String resolveBranch(String head) {
        final int newline = head.indexOf('\n');
        final String firstLine = (newline >= 0 ? head.substring(0, newline) : head).trim();
        if (firstLine.startsWith(REF_PREFIX)) {
            return firstLine.substring(REF_PREFIX.length()).trim();
        }
        // Detached HEAD: HEAD holds a raw commit SHA. Surface a short id.
        if (firstLine.length() >= SHORT_SHA_LENGTH) {
            return firstLine.substring(0, SHORT_SHA_LENGTH) + " (detached)";
        }
        return firstLine;
    }
}
