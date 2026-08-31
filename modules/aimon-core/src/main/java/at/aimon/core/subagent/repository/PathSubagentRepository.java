package at.aimon.core.subagent.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import at.aimon.core.subagent.exception.SubagentRepositoryException;

/**
 * NIO Path-based implementation of SubagentRepository.
 *
 * <p>
 * Loads subagent definitions from a filesystem directory using {@link java.nio.file.Path}. Unlike
 * {@link ClasspathSubagentRepository}, this implementation does not require index files — it directly scans the
 * directory for {@code .md} files.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     Path agentsDir = Path.of("/path/to/agents");
 *     SubagentRepository repository = new PathSubagentRepository(agentsDir);
 *
 *     Optional<String> content = repository.findByName("explore");
 *     // → reads /path/to/agents/explore.md
 * }
 * </pre>
 */
public class PathSubagentRepository implements SubagentRepository {

    private final Path basePath;

    /**
     * Creates a new PathSubagentRepository.
     *
     * @param basePath
     *            The base directory path containing subagent {@code .md} files (must not be null)
     * @throws NullPointerException
     *             if basePath is null
     */
    public PathSubagentRepository(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath, "Base path cannot be null").toAbsolutePath().normalize();
    }

    @Override
    public Optional<String> findByName(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");

        final Path filePath = resolveSafely(subagentName + ".md");
        if (filePath == null || !Files.isRegularFile(filePath)) {
            return Optional.empty();
        }

        try {
            return Optional.of(Files.readString(filePath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SubagentRepositoryException("Failed to read subagent file: " + filePath, e);
        }
    }

    @Override
    public List<String> findAllNames() {
        if (!Files.isDirectory(basePath)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(basePath)) {
            return stream.filter(Files::isRegularFile).map(Path::getFileName).map(Path::toString)
                    .filter(name -> name.endsWith(".md")).map(name -> name.substring(0, name.length() - 3)).toList();
        } catch (IOException e) {
            throw new SubagentRepositoryException("Failed to list subagent files in directory: " + basePath, e);
        }
    }

    @Override
    public boolean exists(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");

        final Path filePath = resolveSafely(subagentName + ".md");
        return filePath != null && Files.isRegularFile(filePath);
    }

    /**
     * Resolves a relative path against basePath with path traversal protection.
     *
     * @return the resolved path, or null if the result escapes basePath
     */
    private Path resolveSafely(String relativePath) {
        final Path resolved = basePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(basePath)) {
            return null;
        }
        return resolved;
    }
}
