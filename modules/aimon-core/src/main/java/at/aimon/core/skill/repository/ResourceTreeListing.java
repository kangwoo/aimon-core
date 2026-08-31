package at.aimon.core.skill.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of listing a class path resource directory: the files that were found, and whether the layout could be
 * walked at all.
 *
 * <p>
 * Those two facts are separate on purpose. {@link ClasspathResourceTreeWalker} used to answer <em>"this directory is
 * empty"</em> and <em>"this deployment layout cannot be enumerated"</em> with the same empty list, so a caller could
 * only ever report the first one. An application packaged as a WAR therefore read <em>"has no files; skipping"</em>
 * about skills that were sitting right there in the archive — the files existed, only the walk was impossible, and the
 * message said the opposite.
 *
 * <p>
 * So {@link #isEnumerated()} is the question to ask <em>before</em> reading any meaning into {@link #getFiles()} being
 * empty. There is deliberately no {@code isEmpty()} on this type: it would restore exactly the conflation this type
 * exists to break, under a new name.
 *
 * <p>
 * Note what this does <i>not</i> do — a listing that could not be enumerated is not an error, and nothing here throws.
 * Bootstrap continues with the skills it could reach; the caller decides how loudly to say so. A real I/O failure while
 * reading a <em>supported</em> layout is a different thing and still raises
 * {@link at.aimon.core.skill.exception.SkillRepositoryException}.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class ResourceTreeListing {

    private static final ResourceTreeListing EMPTY = new ResourceTreeListing(List.of(), null);

    private final List<String> files;

    /** The URL protocol that could not be walked, or {@code null} when the directory was enumerated. */
    private final String unsupportedProtocol;

    private ResourceTreeListing(List<String> files, String unsupportedProtocol) {
        this.files = files;
        this.unsupportedProtocol = unsupportedProtocol;
    }

    /**
     * Creates a listing for a directory that was walked successfully.
     *
     * @param files
     *            The relative file paths found, possibly empty (must not be null)
     * @return An enumerated listing (never null)
     * @throws NullPointerException
     *             if files is null
     */
    public static ResourceTreeListing enumerated(List<String> files) {
        Objects.requireNonNull(files, "Files cannot be null");
        return files.isEmpty() ? EMPTY : new ResourceTreeListing(List.copyOf(files), null);
    }

    /**
     * Returns the listing for a directory that was walked and found to hold nothing — including the case where it does
     * not exist at all. Both are "there is nothing to copy", which is what the caller acts on.
     *
     * @return An enumerated, empty listing (never null)
     */
    public static ResourceTreeListing empty() {
        return EMPTY;
    }

    /**
     * Creates a listing for a directory that could not be walked because its class path layout is outside the supported
     * set.
     *
     * @param protocol
     *            The URL protocol that could not be walked, e.g. {@code "vfs"} or {@code "jrt"} (must not be null or
     *            blank)
     * @return A non-enumerated listing (never null)
     * @throws NullPointerException
     *             if protocol is null
     * @throws IllegalArgumentException
     *             if protocol is blank
     */
    public static ResourceTreeListing unsupported(String protocol) {
        Objects.requireNonNull(protocol, "Protocol cannot be null");
        if (protocol.isBlank()) {
            throw new IllegalArgumentException("Protocol cannot be blank");
        }
        return new ResourceTreeListing(List.of(), protocol);
    }

    /**
     * Returns the relative file paths found under the directory, sorted for determinism.
     *
     * <p>
     * Always empty when {@link #isEnumerated()} is {@code false} — and empty means nothing at all in that case. Check
     * that first.
     *
     * @return An immutable list of slash-separated relative paths (never null)
     */
    public List<String> getFiles() {
        return files;
    }

    /**
     * Returns whether the directory was actually walked, which is what makes {@link #getFiles()} meaningful.
     *
     * @return {@code true} if the file list is the authoritative content of the directory; {@code false} if the layout
     *         could not be enumerated and nothing is known about what is in there
     */
    public boolean isEnumerated() {
        return unsupportedProtocol == null;
    }

    /**
     * Returns the URL protocol that could not be walked.
     *
     * @return The protocol, or empty when the directory was enumerated (never null)
     */
    public Optional<String> getUnsupportedProtocol() {
        return Optional.ofNullable(unsupportedProtocol);
    }

    @Override
    public String toString() {
        return isEnumerated()
                ? "ResourceTreeListing[enumerated, files=" + files.size() + "]"
                : "ResourceTreeListing[not enumerated, protocol=" + unsupportedProtocol + "]";
    }
}
