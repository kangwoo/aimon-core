package at.aimon.core.skill.repository;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.skill.exception.SkillRepositoryException;

/**
 * Enumerates the files contained in a classpath resource directory, transparently handling both exploded
 * ({@code file:}) and packaged ({@code jar:}) class path layouts.
 *
 * <p>
 * The JDK does not offer a portable way to list the entries of a classpath "directory": a {@code file:} URL can be
 * walked as a normal directory, but a {@code jar:} URL must be enumerated through the owning {@link JarFile}. This
 * walker hides that distinction behind {@link #list(String)} so that callers (notably {@link BundledSkillMaterializer})
 * can copy a resource tree out of the class path regardless of how the application is packaged.
 *
 * <p>
 * {@code file:} and {@code jar:} are the whole supported set, and that is a scope decision rather than an omission: the
 * supported deployment shapes are an executable jar (both Boot loader flavours) and an exploded directory class path.
 * WAR deployment into a servlet container, {@code jlink} runtime images ({@code jrt:}), and native images are outside
 * it. See {@code docs/getting-started/embedding-agent-in-application.md} §2.4.
 *
 * <p>
 * An unsupported protocol does not raise — bootstrap continues with whatever it could reach — but it is <em>not</em>
 * reported as an empty directory either. It comes back as a {@link ResourceTreeListing} that answers
 * {@link ResourceTreeListing#isEnumerated()} with {@code false}, so a caller can say "this layout cannot be walked"
 * instead of the false "there is nothing there". Callers that ignore the distinction degrade exactly as before.
 *
 * <p>
 * Thread-safe: holds only an immutable {@link ClassLoader} reference.
 */
public final class ClasspathResourceTreeWalker {

    private static final Logger log = LoggerFactory.getLogger(ClasspathResourceTreeWalker.class);

    private final ClassLoader classLoader;

    /**
     * Creates a walker backed by the given class loader.
     *
     * @param classLoader
     *            The class loader used to resolve resources (must not be null)
     * @throws NullPointerException
     *             if classLoader is null
     */
    public ClasspathResourceTreeWalker(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "ClassLoader cannot be null");
    }

    /**
     * Lists every file under the given classpath resource directory.
     *
     * @param resourceDir
     *            The resource directory path (e.g. {@code "agents/default/skills/commit"}); must not be null. A
     *            trailing slash is tolerated.
     * @return The listing — relative file paths (slash-separated, relative to {@code resourceDir}) sorted for
     *         determinism when the layout could be walked, otherwise a non-enumerated listing naming the protocol
     *         (never null)
     * @throws SkillRepositoryException
     *             if an I/O error occurs while reading a supported resource layout
     */
    public ResourceTreeListing list(String resourceDir) {
        return list(resourceDir, null);
    }

    /**
     * Lists every file under the given classpath resource directory, using an optional anchor file to locate the owning
     * archive when the directory itself is not directly resolvable.
     *
     * <p>
     * Some JAR layouts (notably fat/uber JARs repackaged by Shadow or Spring Boot) omit explicit directory entries, in
     * which case {@code classLoader.getResource(resourceDir)} returns {@code null} even though files exist underneath.
     * When that happens and {@code anchorFileResource} resolves to a {@code jar:} URL, the owning {@link JarFile} is
     * derived from the anchor and entries are enumerated by the {@code resourceDir} prefix — so enumeration no longer
     * depends on directory entries being present. The anchor only has to live in the <em>same archive</em> as
     * {@code resourceDir}; it is used solely to locate the owning {@link JarFile} and is never itself matched against
     * the prefix. Typically it is a file under the directory (for example its {@code SKILL.md}), but any sibling
     * resource from the same JAR works equally well.
     *
     * @param resourceDir
     *            The resource directory path; must not be null. A trailing slash is tolerated.
     * @param anchorFileResource
     *            A file resource known to live in the same archive as {@code resourceDir}, used as a fallback locator
     *            (may be null)
     * @return The listing — relative file paths (slash-separated, relative to {@code resourceDir}) sorted for
     *         determinism when the layout could be walked, otherwise a non-enumerated listing naming the protocol
     *         (never null)
     * @throws SkillRepositoryException
     *             if an I/O error occurs while reading a supported resource layout
     */
    public ResourceTreeListing list(String resourceDir, String anchorFileResource) {
        Objects.requireNonNull(resourceDir, "Resource directory cannot be null");

        final String normalized = stripTrailingSlash(resourceDir);
        final URL url = classLoader.getResource(normalized);
        if (url != null) {
            final String protocol = url.getProtocol();
            switch (protocol) {
                case "file" :
                    return ResourceTreeListing.enumerated(listFromFile(url, normalized));
                case "jar" :
                    return ResourceTreeListing.enumerated(listFromJar(url, normalized));
                default :
                    log.warn("Cannot enumerate classpath resource '{}': unsupported URL protocol '{}'", normalized,
                            protocol);
                    return ResourceTreeListing.unsupported(protocol);
            }
        }

        // The directory resource is not directly resolvable (e.g. a JAR packaged without directory entries). Fall back
        // to an anchor file known to live under the directory to locate the owning archive, then enumerate by prefix.
        if (anchorFileResource != null) {
            final URL anchorUrl = classLoader.getResource(anchorFileResource);
            if (anchorUrl != null) {
                final String anchorProtocol = anchorUrl.getProtocol();
                if ("jar".equals(anchorProtocol)) {
                    return ResourceTreeListing.enumerated(listFromJar(anchorUrl, normalized));
                }
                if (!"file".equals(anchorProtocol)) {
                    // The archive exists and holds the anchor, so the directory is very likely populated too — we just
                    // have no way to walk this layout. Reporting it as empty would be a lie about the content.
                    log.warn("Cannot enumerate classpath resource '{}' via anchor '{}': unsupported URL protocol '{}'",
                            normalized, anchorFileResource, anchorProtocol);
                    return ResourceTreeListing.unsupported(anchorProtocol);
                }
                // A file: anchor means an exploded class path, where a populated directory would have resolved
                // directly. Nothing was hidden from us; the directory really is absent.
            }
        }
        return ResourceTreeListing.empty();
    }

    private List<String> listFromFile(URL url, String resourceDir) {
        try {
            final Path dir = Paths.get(url.toURI());
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                return stream.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/'))
                        .sorted().toList();
            }
        } catch (URISyntaxException | IOException e) {
            throw new SkillRepositoryException("Failed to enumerate file resources under: " + resourceDir, e);
        }
    }

    private List<String> listFromJar(URL url, String resourceDir) {
        try {
            final JarURLConnection connection = (JarURLConnection) url.openConnection();
            // Own the JarFile so it is safe to close in the try-with-resources below; the default cached JarFile is
            // shared and must not be closed by us.
            connection.setUseCaches(false);
            try (JarFile jarFile = connection.getJarFile()) {
                final String prefix = resourceDir + "/";
                final List<String> files = new ArrayList<>();
                final Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    final JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    final String name = entry.getName();
                    if (name.startsWith(prefix) && name.length() > prefix.length()) {
                        files.add(name.substring(prefix.length()));
                    }
                }
                files.sort(null);
                return files;
            }
        } catch (IOException e) {
            throw new SkillRepositoryException("Failed to enumerate jar resources under: " + resourceDir, e);
        }
    }

    private static String stripTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
