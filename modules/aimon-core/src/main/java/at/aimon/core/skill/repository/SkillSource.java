package at.aimon.core.skill.repository;

import java.util.Objects;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Where a skill's files can be read from to stage them into an execution environment (execution-environment design
 * §4.4): a filesystem and the skill's directory on it. Only the read side of the filesystem is used. Immutable.
 */
public final class SkillSource {

    private final VirtualFileSystem fileSystem;
    private final String directory;

    private SkillSource(VirtualFileSystem fileSystem, String directory) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
    }

    /**
     * @param fileSystem
     *            the filesystem the skill's files are read from (must not be null)
     * @param directory
     *            the skill's directory on it (must not be null)
     * @return the source
     */
    public static SkillSource of(VirtualFileSystem fileSystem, String directory) {
        return new SkillSource(fileSystem, directory);
    }

    /** @return the filesystem the skill's files are read from */
    public VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    /** @return the skill's directory on {@link #getFileSystem()} */
    public String getDirectory() {
        return directory;
    }

    @Override
    public String toString() {
        return "SkillSource{" + fileSystem.getWorkingDirectory() + " : " + directory + '}';
    }
}
