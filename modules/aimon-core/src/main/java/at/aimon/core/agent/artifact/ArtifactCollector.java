package at.aimon.core.agent.artifact;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects file artifacts generated during a single agent execution request.
 *
 * <p>
 * Created per-request (not per-context) so that artifacts from different requests do not mix. Injected into
 * {@link at.aimon.core.agent.tool.ToolContext} under
 * {@code at.aimon.core.tools.ToolContextKeys#ARTIFACT_COLLECTOR} so that tools can register artifacts during
 * execution.
 *
 * <p>
 * Thread-safe. Uses {@link CopyOnWriteArrayList} internally, so tools dispatched in parallel
 * ({@code ConcurrencyBehavior.CONCURRENT_SAFE}) may {@link #add} concurrently. No artifact-producing tool declares
 * itself concurrent-safe today, but the collector does not depend on that staying true.
 *
 * <p>
 * Reads are snapshots, never live views: {@link #getArtifacts()} copies. That matters because callers combine a count
 * taken before tool execution with a read taken after it, and because the lists handed to an
 * {@code AgentExecutionResult} must not keep changing once the result is built.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // In OrcaAgentExecutor.execute()
 *     ArtifactCollector collector = new ArtifactCollector();
 *
 *     // Inject into ToolContext
 *     ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
 *
 *     // In a tool implementation
 *     context.get(ToolContextKeys.ARTIFACT_COLLECTOR).ifPresent(c -> {
 *         c.add(FileArtifact.builder().path("/reports/output.csv").size(1024).fileName("output.csv").build());
 *     });
 *
 *     // After execution
 *     List<FileArtifact> artifacts = collector.getArtifacts();
 * }
 * </pre>
 *
 * @see FileArtifact
 */
public class ArtifactCollector {

    private final List<FileArtifact> artifacts = new CopyOnWriteArrayList<>();

    /**
     * Adds a file artifact to this collector.
     *
     * @param artifact
     *            The artifact to add (must not be null)
     * @throws NullPointerException
     *             if artifact is null
     */
    public void add(FileArtifact artifact) {
        Objects.requireNonNull(artifact, "Artifact cannot be null");
        artifacts.add(artifact);
    }

    /**
     * Returns an immutable snapshot of the collected artifacts.
     *
     * <p>
     * The returned list reflects the order in which artifacts were added, and does not change afterwards even if more
     * artifacts are added to this collector. Callers that need two consistent reads (a size and the elements) must take
     * one snapshot and query it twice, not call this method twice.
     *
     * @return An immutable list of artifacts (never null, may be empty)
     */
    public List<FileArtifact> getArtifacts() {
        return List.copyOf(artifacts);
    }

    /**
     * Returns an immutable snapshot of the artifacts added at or after {@code fromIndex}, which is how a caller reads
     * "what this iteration produced" given a count taken before it started.
     *
     * <p>
     * The whole slice comes out of a single snapshot, so a concurrent {@link #add} can neither shift the window nor
     * invalidate it mid-read. An out-of-range {@code fromIndex} yields an empty list rather than throwing: it only
     * means
     * nothing was added since the mark, and one iteration's artifact bookkeeping is not worth failing a turn over.
     *
     * @param fromIndex
     *            The number of artifacts already collected when the window opened; negative values are treated as 0
     * @return An immutable list of the artifacts added since then (never null, may be empty)
     */
    public List<FileArtifact> sliceFrom(int fromIndex) {
        final List<FileArtifact> snapshot = List.copyOf(artifacts);
        final int from = Math.max(fromIndex, 0);
        if (from >= snapshot.size()) {
            return List.of();
        }
        return List.copyOf(snapshot.subList(from, snapshot.size()));
    }

    /**
     * Returns the number of artifacts collected so far.
     *
     * <p>
     * Intended for marking the start of a window that {@link #sliceFrom(int)} later closes; it avoids copying the list
     * just to count it.
     *
     * @return The current artifact count (never negative)
     */
    public int size() {
        return artifacts.size();
    }
}
