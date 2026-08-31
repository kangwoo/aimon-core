package at.aimon.core.skill.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.skill.exception.SkillRepositoryException;

/**
 * NIO Path-based implementation of SkillRepository.
 *
 * <p>
 * Loads skills from a filesystem directory structure using {@link java.nio.file.Path}. Unlike
 * {@link ClasspathSkillRepository}, this implementation fully supports supplementary files (scripts, references,
 * assets) and does not require index files — it directly scans the directory.
 *
 * <p>
 * Expected directory structure:
 *
 * <pre>
 * skills/
 * ├── skill-name/
 * │   ├── SKILL.md          (required)
 * │   ├── scripts/          (optional)
 * │   ├── references/       (optional)
 * │   └── assets/           (optional)
 * </pre>
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
 *     Path skillsDir = Path.of("/path/to/skills");
 *     SkillRepository repository = new PathSkillRepository(skillsDir);
 *
 *     Optional<String> content = repository.findByName("commit");
 *     // → reads /path/to/skills/commit/SKILL.md
 * }
 * </pre>
 */
public class PathSkillRepository implements SkillRepository {

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String MARKDOWN_EXTENSION = ".md";

    private static final Logger log = LoggerFactory.getLogger(PathSkillRepository.class);

    private final Path skillsBasePath;

    /**
     * Creates a new PathSkillRepository.
     *
     * @param skillsBasePath
     *            The base directory path containing skill directories (must not be null)
     * @throws NullPointerException
     *             if skillsBasePath is null
     */
    public PathSkillRepository(Path skillsBasePath) {
        this.skillsBasePath = Objects.requireNonNull(skillsBasePath, "Skills base path cannot be null").toAbsolutePath()
                .normalize();
    }

    @Override
    public Optional<String> findByName(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final Path skillDir = resolveSafely(skillName);
        if (skillDir == null) {
            return Optional.empty();
        }

        final Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.isRegularFile(skillFile)) {
            return Optional.empty();
        }

        try {
            return Optional.of(Files.readString(skillFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SkillRepositoryException(
                    String.format("Failed to read skill '%s' from path '%s'", skillName, skillFile), e);
        }
    }

    @Override
    public List<String> findAllNames() {
        if (!Files.isDirectory(skillsBasePath)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(skillsBasePath)) {
            return stream.peek(this::warnIfStrayMarkdown).filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(SKILL_FILE_NAME)))
                    .map(dir -> dir.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new SkillRepositoryException(String.format("Failed to list skills from path '%s'", skillsBasePath),
                    e);
        }
    }

    /**
     * Warns when a markdown file is placed directly under {@code skillsBasePath}.
     *
     * <p>
     * AIMON skills must use the directory layout ({@code <name>/SKILL.md}); flat files such as
     * {@code .aimon/skills/foo.md} are ignored to prevent ambiguity with the directory format.
     */
    private void warnIfStrayMarkdown(Path entry) {
        if (!Files.isRegularFile(entry)) {
            return;
        }
        final String filename = entry.getFileName().toString();
        if (filename.toLowerCase().endsWith(MARKDOWN_EXTENSION)) {
            log.warn(
                    "Ignoring stray markdown file '{}' under skills base '{}'. "
                            + "AIMON skills must use the directory layout (<name>/SKILL.md); move it to '{}'.",
                    filename, skillsBasePath,
                    skillsBasePath.resolve(stripExtension(filename)).resolve(SKILL_FILE_NAME));
        }
    }

    private static String stripExtension(String filename) {
        final int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    @Override
    public boolean exists(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final Path skillDir = resolveSafely(skillName);
        return skillDir != null && Files.isRegularFile(skillDir.resolve(SKILL_FILE_NAME));
    }

    @Override
    public Map<String, String> findRootFiles(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final Path skillDir = resolveSafely(skillName);
        if (skillDir == null || !Files.isDirectory(skillDir)) {
            return Map.of();
        }

        try (Stream<Path> stream = Files.list(skillDir)) {
            final Map<String, String> rootFiles = new HashMap<>();

            stream.filter(Files::isRegularFile).forEach(file -> {
                final String filename = file.getFileName().toString();
                if (!SKILL_FILE_NAME.equals(filename)) {
                    rootFiles.put(filename, file.toAbsolutePath().toString());
                }
            });

            return rootFiles;
        } catch (IOException e) {
            throw new SkillRepositoryException(String.format("Failed to list root files for skill '%s'", skillName), e);
        }
    }

    @Override
    public Map<String, String> findScripts(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        return findFilesInDirectory(skillName, "scripts");
    }

    @Override
    public Map<String, String> findReferences(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        return findFilesInDirectory(skillName, "references");
    }

    @Override
    public Map<String, String> findAssets(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");
        return findFilesInDirectory(skillName, "assets");
    }

    @Override
    public Optional<String> resolveBaseDir(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final Path skillDir = resolveSafely(skillName);
        if (skillDir == null || !Files.isDirectory(skillDir)) {
            return Optional.empty();
        }
        return Optional.of(skillDir.toAbsolutePath().toString());
    }

    @Override
    public Map<String, String> findAllFiles(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final Path skillDir = resolveSafely(skillName);
        if (skillDir == null || !Files.isDirectory(skillDir)) {
            return Map.of();
        }

        try (Stream<Path> stream = Files.walk(skillDir)) {
            final Map<String, String> files = new HashMap<>();

            stream.filter(Files::isRegularFile).forEach(file -> {
                final String relativePath = skillDir.relativize(file).toString().replace('\\', '/');
                if (!SKILL_FILE_NAME.equals(relativePath)) {
                    files.put(relativePath, file.toAbsolutePath().toString());
                }
            });

            return files;
        } catch (IOException e) {
            throw new SkillRepositoryException(String.format("Failed to list all files for skill '%s'", skillName), e);
        }
    }

    private Map<String, String> findFilesInDirectory(String skillName, String directoryName) {
        final Path skillDir = resolveSafely(skillName);
        if (skillDir == null) {
            return Map.of();
        }

        final Path directoryPath = skillDir.resolve(directoryName);
        if (!Files.isDirectory(directoryPath)) {
            return Map.of();
        }

        try (Stream<Path> stream = Files.walk(directoryPath)) {
            final Map<String, String> files = new HashMap<>();

            stream.filter(Files::isRegularFile).forEach(file -> {
                final String relativePath = directoryPath.relativize(file).toString();
                files.put(relativePath, file.toAbsolutePath().toString());
            });

            return files;
        } catch (IOException e) {
            throw new SkillRepositoryException(
                    String.format("Failed to list files in '%s' directory for skill '%s'", directoryName, skillName),
                    e);
        }
    }

    /**
     * Resolves a skill name against skillsBasePath with path traversal protection.
     *
     * @return the resolved path, or null if the result escapes skillsBasePath
     */
    private Path resolveSafely(String skillName) {
        final Path resolved = skillsBasePath.resolve(skillName).normalize();
        if (!resolved.startsWith(skillsBasePath)) {
            return null;
        }
        return resolved;
    }
}
