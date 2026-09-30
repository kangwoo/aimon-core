package at.aimon.core.skill.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.BackendConnectionException;
import at.aimon.core.filesystem.exception.FileNotFoundException;

/**
 * A read-only {@link VirtualFileSystem} view of classpath skills, rooted at a repository's {@code basePath} — the
 * staging source of {@link ClasspathSkillRepository} (execution-environment design §4.4).
 *
 * <p>
 * Paths are relative to {@code basePath} ({@code {skill}/scripts/x.sh}). A skill directory is listed with the same
 * {@link ClasspathResourceTreeWalker} and {@code SKILL.md} anchor the {@link BundledSkillMaterializer} uses, so it sees
 * exactly the files a materialized copy would hold. When the class path layout cannot be enumerated, a listing holds
 * only the skill's {@code SKILL.md} and one WARN names the protocol: the skill still loads and renders, and its other
 * files stay unreachable, as they always were on such a layout.
 *
 * <p>
 * Every mutating method throws {@link UnsupportedOperationException}; there is nothing to initialise or close.
 */
final class ClasspathSkillSourceFileSystem implements VirtualFileSystem {

    private static final Logger log = LoggerFactory.getLogger(ClasspathSkillSourceFileSystem.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String READ_ONLY = "read-only file system";

    private final ClassLoader classLoader;
    private final String basePath;
    private final ClasspathResourceTreeWalker walker;
    private final Set<String> warnedDirectories = ConcurrentHashMap.newKeySet();

    ClasspathSkillSourceFileSystem(ClassLoader classLoader, String basePath) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
        this.basePath = trimSlashes(Objects.requireNonNull(basePath, "basePath must not be null"));
        this.walker = new ClasspathResourceTreeWalker(classLoader);
    }

    private String resource(String path) {
        final String relative = trimSlashes(path);
        return relative.isEmpty() || ".".equals(relative) ? basePath : basePath + "/" + relative;
    }

    @Override
    public List<String> listRecursive(String directory) {
        final String dir = trimSlashes(directory);
        final String resourceDir = resource(dir);
        final ResourceTreeListing listing = walker.list(resourceDir, resourceDir + "/" + SKILL_FILE_NAME);
        final List<String> files = new ArrayList<>();
        if (listing.isEnumerated()) {
            for (String file : listing.getFiles()) {
                files.add(dir.isEmpty() ? file : dir + "/" + file);
            }
            return files;
        }
        if (warnedDirectories.add(resourceDir)) {
            log.warn("Classpath skill directory '{}' cannot be listed: class path layout '{}' cannot be enumerated"
                    + " (its files may well be present); only {} is staged. See the supported deployment shapes in"
                    + " docs/getting-started/embedding-agent-in-application.md §2.4", resourceDir,
                    listing.getUnsupportedProtocol().orElse("unknown"), SKILL_FILE_NAME);
        }
        if (classLoader.getResource(resourceDir + "/" + SKILL_FILE_NAME) != null) {
            files.add(dir.isEmpty() ? SKILL_FILE_NAME : dir + "/" + SKILL_FILE_NAME);
        }
        return files;
    }

    @Override
    public List<String> list(String directory) {
        final String dir = trimSlashes(directory);
        final String prefix = dir.isEmpty() ? "" : dir + "/";
        final List<String> children = new ArrayList<>();
        for (String file : listRecursive(dir)) {
            final String rest = file.substring(prefix.length());
            final int slash = rest.indexOf('/');
            final String child = prefix + (slash < 0 ? rest : rest.substring(0, slash));
            if (!children.contains(child)) {
                children.add(child);
            }
        }
        return children;
    }

    @Override
    public InputStream read(String path) {
        final InputStream in = classLoader.getResourceAsStream(resource(path));
        if (in == null) {
            throw new FileNotFoundException(path);
        }
        return in;
    }

    @Override
    public InputStream openInputStream(String path) {
        return read(path);
    }

    @Override
    public boolean exists(String path) {
        return classLoader.getResource(resource(path)) != null || isDirectory(path);
    }

    @Override
    public boolean isDirectory(String path) {
        final String dir = trimSlashes(path);
        if (dir.isEmpty()) {
            return true;
        }
        // A skill directory is anchored by its SKILL.md; deeper directories exist when they hold a listed file.
        if (classLoader.getResource(resource(dir) + "/" + SKILL_FILE_NAME) != null) {
            return true;
        }
        final int slash = dir.indexOf('/');
        if (slash < 0) {
            return false;
        }
        final String skillDir = dir.substring(0, slash);
        return listRecursive(skillDir).stream().anyMatch(file -> file.startsWith(dir + "/"));
    }

    @Override
    public FileMetadata getMetadata(String path) {
        final long size;
        try (InputStream in = read(path)) {
            size = in.readAllBytes().length;
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.of("CLASSPATH"), "Failed to read " + path, e);
        }
        final Instant epoch = Instant.EPOCH;
        return FileMetadata.builder().path(trimSlashes(path)).size(size).createdAt(epoch).modifiedAt(epoch).build();
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void delete(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public OutputStream openOutputStream(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void createDirectory(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void deleteRecursive(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public String getWorkingDirectory() {
        return "classpath:/" + basePath;
    }

    @Override
    public void initialize() {
        // Nothing to prepare.
    }

    @Override
    public BackendStatus getStatus() {
        return BackendStatus.connected(BackendType.of("CLASSPATH"));
    }

    @Override
    public void close() {
        // Holds no resources.
    }

    private static String trimSlashes(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
