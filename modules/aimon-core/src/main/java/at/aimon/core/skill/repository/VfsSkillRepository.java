package at.aimon.core.skill.repository;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.core.skill.exception.SkillRepositoryException;

/**
 * VirtualFileSystem-based implementation of SkillRepository.
 *
 * <p>
 * Reads skills from a filesystem directory structure:
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
 * Thread-safe if the underlying VirtualFileSystem is thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem fileSystem = new LocalFileSystem(basePath);
 *     SkillRepository repository = new VfsSkillRepository(fileSystem, "skills");
 *
 *     // Find skill
 *     Optional<String> content = repository.findByName("alert-analysis");
 *     // Returns content of skills/alert-analysis/SKILL.md
 * }
 * </pre>
 */
public class VfsSkillRepository implements SkillRepository {

    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final VirtualFileSystem fileSystem;
    private final String skillsBasePath;

    /**
     * Creates a new VfsSkillRepository.
     *
     * @param fileSystem
     *            The virtual file system (must not be null)
     * @param skillsBasePath
     *            The base path for skills directory (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public VfsSkillRepository(VirtualFileSystem fileSystem, String skillsBasePath) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
        this.skillsBasePath = Objects.requireNonNull(skillsBasePath, "Skills base path cannot be null");
    }

    @Override
    public Optional<String> findByName(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final String skillFilePath = buildSkillFilePath(skillName);

        try (InputStream inputStream = fileSystem.read(skillFilePath)) {
            final String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (FileNotFoundException e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new SkillRepositoryException(
                    String.format("Failed to read skill '%s' from path '%s'", skillName, skillFilePath), e);
        }
    }

    @Override
    public List<String> findAllNames() {
        try {
            if (!fileSystem.exists(skillsBasePath)) {
                return new ArrayList<>();
            }

            final List<String> paths = fileSystem.list(skillsBasePath);
            final List<String> skillNames = new ArrayList<>();

            for (String path : paths) {
                if (fileSystem.isDirectory(path)) {
                    final String skillName = extractSkillName(path);
                    final String skillFilePath = buildSkillFilePath(skillName);
                    if (fileSystem.exists(skillFilePath)) {
                        skillNames.add(skillName);
                    }
                }
            }

            return skillNames;
        } catch (Exception e) {
            throw new SkillRepositoryException(String.format("Failed to list skills from path '%s'", skillsBasePath),
                    e);
        }
    }

    @Override
    public boolean exists(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final String skillFilePath = buildSkillFilePath(skillName);
        try {
            return fileSystem.exists(skillFilePath);
        } catch (VirtualFileSystemException e) {
            return false;
        }
    }

    @Override
    public Map<String, String> findRootFiles(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final String skillDir = skillsBasePath + '/' + skillName;

        try {
            if (!fileSystem.exists(skillDir) || !fileSystem.isDirectory(skillDir)) {
                return Map.of();
            }

            final List<String> paths = fileSystem.list(skillDir);
            final Map<String, String> rootFiles = new HashMap<>();

            for (String filePath : paths) {
                if (fileSystem.isDirectory(filePath)) {
                    continue;
                }

                final String filename = extractFileName(filePath);

                if (SKILL_FILE_NAME.equals(filename)) {
                    continue;
                }

                final String fullPath = skillsBasePath + '/' + skillName + '/' + filename;
                rootFiles.put(filename, fullPath);
            }

            return rootFiles;
        } catch (Exception e) {
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

        final String skillDir = skillsBasePath + '/' + skillName;
        try {
            if (fileSystem.exists(skillDir) && fileSystem.isDirectory(skillDir)) {
                return Optional.of(skillDir);
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new SkillRepositoryException(
                    String.format("Failed to resolve base directory for skill '%s'", skillName), e);
        }
    }

    @Override
    public Map<String, String> findAllFiles(String skillName) {
        Objects.requireNonNull(skillName, "Skill name cannot be null");

        final String skillDir = skillsBasePath + '/' + skillName;
        try {
            if (!fileSystem.exists(skillDir) || !fileSystem.isDirectory(skillDir)) {
                return Map.of();
            }

            final List<String> filePaths = fileSystem.listRecursive(skillDir);
            final Map<String, String> files = new HashMap<>();

            for (String filePath : filePaths) {
                if (fileSystem.isDirectory(filePath)) {
                    continue;
                }

                final String relativePath = extractRelativePath(filePath, skillDir);
                if (SKILL_FILE_NAME.equals(relativePath)) {
                    continue;
                }
                files.put(relativePath, filePath);
            }

            return files;
        } catch (Exception e) {
            throw new SkillRepositoryException(String.format("Failed to list all files for skill '%s'", skillName), e);
        }
    }

    private Map<String, String> findFilesInDirectory(String skillName, String directoryName) {
        final String directoryPath = skillsBasePath + '/' + skillName + '/' + directoryName;

        try {
            if (!fileSystem.exists(directoryPath) || !fileSystem.isDirectory(directoryPath)) {
                return Map.of();
            }

            final List<String> filePaths = fileSystem.listRecursive(directoryPath);
            final Map<String, String> files = new HashMap<>();

            for (String filePath : filePaths) {
                if (fileSystem.isDirectory(filePath)) {
                    continue;
                }

                // Use relative path from directory as key to avoid filename collisions
                final String relativePath = extractRelativePath(filePath, directoryPath);
                files.put(relativePath, filePath);
            }

            return files;
        } catch (Exception e) {
            throw new SkillRepositoryException(
                    String.format("Failed to list files in '%s' directory for skill '%s'", directoryName, skillName),
                    e);
        }
    }

    private String buildSkillFilePath(String skillName) {
        return skillsBasePath + '/' + skillName + '/' + SKILL_FILE_NAME;
    }

    private String extractSkillName(String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String extractFileName(String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String extractRelativePath(String filePath, String basePath) {
        if (filePath.startsWith(basePath + '/')) {
            return filePath.substring(basePath.length() + 1);
        }
        return extractFileName(filePath);
    }
}
