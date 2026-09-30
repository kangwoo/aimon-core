package at.aimon.core.environment;

/**
 * Answers a {@code Grep} query inside an {@link ExecutionEnvironment} without the tool reading files one by one
 * through the filesystem — one process (or one remote call) instead of one round trip per file.
 *
 * <p>
 * Results are a normalised value ({@link ContentSearchResult}), not a tool's wire format, so {@code Grep} formats
 * both paths with one formatter and a sandbox is not tied to a particular search program's output. An implementation
 * that cannot answer a query (an unsupported option, a crashed process) throws; {@code Grep} then walks the
 * filesystem itself, with identical output.
 */
public interface ContentSearch {

    /**
     * Runs a search.
     *
     * @param query
     *            the query (must not be null)
     * @return the matches, per file
     * @throws RuntimeException
     *             if the query cannot be answered; the caller falls back to its own search
     */
    ContentSearchResult search(ContentQuery query);
}
