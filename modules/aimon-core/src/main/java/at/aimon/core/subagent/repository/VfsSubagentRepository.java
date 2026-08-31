package at.aimon.core.subagent.repository;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.core.subagent.exception.SubagentRepositoryException;

/**
 * VirtualFileSystem-based implementation of SubagentRepository.
 *
 * <p>
 * Handles all filesystem operations for subagent persistence. Loads subagent definitions from agents/*.md files.
 *
 * <p>
 * Responsibilities (SRP):
 *
 * <ul>
 * <li>File existence checks
 * <li>File reading operations
 * <li>Directory listing
 * </ul>
 *
 * <p>
 * Does NOT handle:
 *
 * <ul>
 * <li>Parsing (delegated to Parser)
 * <li>Caching (delegated to Registry)
 * <li>Validation (delegated to Parser/Registry)
 * </ul>
 *
 * <p>
 * Thread-safe as VirtualFileSystem is thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem fileSystem = new LocalFileSystem(projectRoot);
 *     SubagentRepository repository = new VfsSubagentRepository(fileSystem, "agents");
 *
 *     Optional<String> content = repository.findByName("code-reviewer");
 *     if (content.isPresent()) {
 *         // Parse and use content
 *     }
 * }
 * </pre>
 */
public class VfsSubagentRepository implements SubagentRepository {
    private final VirtualFileSystem fileSystem;
    private final String agentsDirectory;

    /**
     * Creates a new VfsSubagentRepository.
     *
     * @param fileSystem
     *            The virtual file system to use (must not be null)
     * @param agentsDirectory
     *            The agents directory path (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public VfsSubagentRepository(VirtualFileSystem fileSystem, String agentsDirectory) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
        this.agentsDirectory = Objects.requireNonNull(agentsDirectory, "Agents directory cannot be null");
    }

    @Override
    public Optional<String> findByName(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");

        final String filePath = buildFilePath(subagentName);

        try (InputStream inputStream = fileSystem.read(filePath)) {
            final String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (FileNotFoundException e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new SubagentRepositoryException("Failed to read subagent file: " + filePath, e);
        }
    }

    @Override
    public List<String> findAllNames() {
        if (!fileSystem.exists(agentsDirectory)) {
            return List.of();
        }

        try {
            return fileSystem.list(agentsDirectory).stream().filter(file -> !fileSystem.isDirectory(file))
                    .filter(file -> file.endsWith(".md")).map(file -> {
                        final int lastSlash = file.lastIndexOf('/');
                        final String filename = lastSlash >= 0 ? file.substring(lastSlash + 1) : file;
                        return filename.substring(0, filename.length() - 3);
                    }).toList();
        } catch (Exception e) {
            throw new SubagentRepositoryException("Failed to list subagent files in directory: " + agentsDirectory, e);
        }
    }

    @Override
    public boolean exists(String subagentName) {
        Objects.requireNonNull(subagentName, "Subagent name cannot be null");
        try {
            return fileSystem.exists(buildFilePath(subagentName));
        } catch (VirtualFileSystemException e) {
            return false;
        }
    }

    private String buildFilePath(String subagentName) {
        return agentsDirectory + '/' + subagentName + ".md";
    }
}
