package at.aimon.core.skill.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathResourceTreeWalkerTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------------------
    // constructor
    // ---------------------------------------------------------------------------

    @Test
    void constructor_nullClassLoader_throwsNullPointerException() {
        assertThatThrownBy(() -> new ClasspathResourceTreeWalker(null)).isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------------------
    // file: protocol — test classpath fixture
    // ---------------------------------------------------------------------------

    @Test
    void list_fileProtocol_returnsAllFilesUnderDir() {
        final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(
                Thread.currentThread().getContextClassLoader());

        final ResourceTreeListing listing = walker.list("materializer/skills/demo");

        assertThat(listing.isEnumerated()).isTrue();
        assertThat(listing.getFiles()).containsExactlyInAnyOrder("SKILL.md", "references/schema.md", "scripts/run.py",
                "templates/report.md");
    }

    @Test
    void list_fileProtocol_resultIsSorted() {
        final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(
                Thread.currentThread().getContextClassLoader());

        final ResourceTreeListing listing = walker.list("materializer/skills/demo");

        assertThat(listing.getFiles()).isSorted();
    }

    @Test
    void list_fileProtocol_trailingSlashTolerated() {
        final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(
                Thread.currentThread().getContextClassLoader());

        final List<String> withSlash = walker.list("materializer/skills/demo/").getFiles();
        final List<String> withoutSlash = walker.list("materializer/skills/demo").getFiles();

        assertThat(withSlash).isEqualTo(withoutSlash);
    }

    // ---------------------------------------------------------------------------
    // non-existent resource directory
    // ---------------------------------------------------------------------------

    @Test
    void list_nonExistentDir_isEnumeratedAndEmpty() {
        final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(
                Thread.currentThread().getContextClassLoader());

        final ResourceTreeListing listing = walker.list("this/path/does/not/exist/at/all");

        // Absent is a fact about the content, so it is enumerated — nothing about the layout was in doubt.
        assertThat(listing.isEnumerated()).isTrue();
        assertThat(listing.getFiles()).isEmpty();
        assertThat(listing.getUnsupportedProtocol()).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // jar: protocol — runtime-built jar in @TempDir
    // ---------------------------------------------------------------------------

    @Test
    void list_jarProtocol_returnsRelativePathsUnderDir() throws IOException {
        // Build a small .jar that contains a few entries under "pkg/skills/demo/".
        final Path jarFile = tempDir.resolve("test-skills.jar");
        createJarWithEntries(jarFile, new String[]{"pkg/skills/demo/SKILL.md", "pkg/skills/demo/templates/x.md",
                "pkg/skills/demo/scripts/run.sh", "other/entry.txt"});

        final URL jarUrl = new URL("file:" + jarFile.toAbsolutePath());
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, null)) {
            final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(loader);

            final ResourceTreeListing listing = walker.list("pkg/skills/demo");

            assertThat(listing.isEnumerated()).isTrue();
            assertThat(listing.getFiles()).containsExactlyInAnyOrder("SKILL.md", "templates/x.md", "scripts/run.sh");
        }
    }

    @Test
    void list_jarProtocol_resultIsSorted() throws IOException {
        final Path jarFile = tempDir.resolve("sorted-skills.jar");
        createJarWithEntries(jarFile, new String[]{"pkg/demo/zzz.md", "pkg/demo/SKILL.md", "pkg/demo/aaa/file.txt"});

        final URL jarUrl = new URL("file:" + jarFile.toAbsolutePath());
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, null)) {
            final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(loader);

            final ResourceTreeListing listing = walker.list("pkg/demo");

            assertThat(listing.getFiles()).isSorted();
        }
    }

    @Test
    void list_jarProtocol_excludesEntriesOutsidePrefix() throws IOException {
        final Path jarFile = tempDir.resolve("mixed.jar");
        createJarWithEntries(jarFile,
                new String[]{"pkg/skills/demo/SKILL.md", "pkg/skills/other/SKILL.md", "unrelated/file.txt"});

        final URL jarUrl = new URL("file:" + jarFile.toAbsolutePath());
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, null)) {
            final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(loader);

            final ResourceTreeListing listing = walker.list("pkg/skills/demo");

            assertThat(listing.getFiles()).containsExactly("SKILL.md");
        }
    }

    @Test
    void list_jarProtocol_nonExistentDirIsEnumeratedAndEmpty() throws IOException {
        final Path jarFile = tempDir.resolve("empty-dir-test.jar");
        createJarWithEntries(jarFile, new String[]{"pkg/skills/demo/SKILL.md"});

        final URL jarUrl = new URL("file:" + jarFile.toAbsolutePath());
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, null)) {
            final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(loader);

            final ResourceTreeListing listing = walker.list("pkg/skills/nonexistent");

            assertThat(listing.isEnumerated()).isTrue();
            assertThat(listing.getFiles()).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------
    // jar: protocol without directory entries (fat/uber JAR repackaging) — anchor fallback
    // ---------------------------------------------------------------------------

    @Test
    void list_jarWithoutDirectoryEntries_singleArg_isEnumeratedAndEmpty() throws IOException {
        // Shadow/Spring Boot repackaged JARs often strip directory entries, so getResource(<dir>) returns null.
        final Path jarFile = tempDir.resolve("no-dir-entries.jar");
        createJarWithoutDirectoryEntries(jarFile,
                new String[]{"pkg/skills/demo/SKILL.md", "pkg/skills/demo/templates/x.md"});

        final URL jarUrl = new URL("file:" + jarFile.toAbsolutePath());
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, null)) {
            final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(loader);

            // Without an anchor the directory is not resolvable, and the walker cannot tell that from an absent
            // directory — the layout itself is supported, so this stays an (empty) enumerated answer. Passing the
            // anchor is what makes the difference, as the next test shows.
            final ResourceTreeListing listing = walker.list("pkg/skills/demo");

            assertThat(listing.isEnumerated()).isTrue();
            assertThat(listing.getFiles()).isEmpty();
        }
    }

    @Test
    void list_jarWithoutDirectoryEntries_withAnchor_enumeratesViaAnchor() throws IOException {
        final Path jarFile = tempDir.resolve("no-dir-entries-anchor.jar");
        createJarWithoutDirectoryEntries(jarFile, new String[]{"pkg/skills/demo/SKILL.md",
                "pkg/skills/demo/templates/x.md", "pkg/skills/demo/scripts/run.sh"});

        final URL jarUrl = new URL("file:" + jarFile.toAbsolutePath());
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, null)) {
            final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(loader);

            final ResourceTreeListing listing = walker.list("pkg/skills/demo", "pkg/skills/demo/SKILL.md");

            assertThat(listing.isEnumerated()).isTrue();
            assertThat(listing.getFiles()).containsExactlyInAnyOrder("SKILL.md", "templates/x.md", "scripts/run.sh");
        }
    }

    // ---------------------------------------------------------------------------
    // unsupported layout — the case that used to arrive as an empty list
    // ---------------------------------------------------------------------------

    @Test
    void list_unsupportedProtocol_isNotEnumeratedAndNamesTheProtocol() {
        final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(
                loaderServing("pkg/skills/demo", fakeUrl("vfs", "/pkg/skills/demo")));

        final ResourceTreeListing listing = walker.list("pkg/skills/demo");

        assertThat(listing.isEnumerated()).isFalse();
        assertThat(listing.getUnsupportedProtocol()).contains("vfs");
        assertThat(listing.getFiles()).isEmpty();
    }

    @Test
    void list_unsupportedProtocolViaAnchor_isNotEnumerated() {
        // The directory itself does not resolve (no directory entries), and the anchor lands on a layout we cannot
        // walk. The archive plainly holds the anchor, so answering "empty" would misdescribe the content.
        final ClasspathResourceTreeWalker walker = new ClasspathResourceTreeWalker(
                loaderServing("pkg/skills/demo/SKILL.md", fakeUrl("vfs", "/pkg/skills/demo/SKILL.md")));

        final ResourceTreeListing listing = walker.list("pkg/skills/demo", "pkg/skills/demo/SKILL.md");

        assertThat(listing.isEnumerated()).isFalse();
        assertThat(listing.getUnsupportedProtocol()).contains("vfs");
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a URL on a protocol the JDK does not know, standing in for a container-specific class path layout such as
     * JBoss/WildFly's {@code vfs:}. The handler is never opened — the walker rejects the protocol before that.
     */
    private static URL fakeUrl(String protocol, String path) {
        try {
            return new URL(protocol, "", -1, path, new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                    throw new UnsupportedOperationException("Not expected to be opened");
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A class loader that resolves exactly one resource name and nothing else. */
    private static ClassLoader loaderServing(String resourceName, URL url) {
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return resourceName.equals(name) ? url : null;
            }
        };
    }

    private static void createJarWithEntries(Path jarPath, String[] entryNames) throws IOException {
        try (OutputStream fos = Files.newOutputStream(jarPath); JarOutputStream jos = new JarOutputStream(fos)) {
            for (final String entryName : entryNames) {
                // For nested entries, add parent directory entries first so the jar is well-formed.
                addDirectoryEntries(jos, entryName);
                final JarEntry entry = new JarEntry(entryName);
                jos.putNextEntry(entry);
                jos.write(("content of " + entryName).getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
    }

    private static void createJarWithoutDirectoryEntries(Path jarPath, String[] entryNames) throws IOException {
        // Deliberately omit directory entries to emulate a fat/uber JAR repackaged by Shadow or Spring Boot.
        try (OutputStream fos = Files.newOutputStream(jarPath); JarOutputStream jos = new JarOutputStream(fos)) {
            for (final String entryName : entryNames) {
                final JarEntry entry = new JarEntry(entryName);
                jos.putNextEntry(entry);
                jos.write(("content of " + entryName).getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
    }

    /**
     * Adds intermediate directory entries for a given file path (best-effort; duplicates are swallowed).
     */
    private static void addDirectoryEntries(JarOutputStream jos, String filePath) {
        final int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        final String parentPath = filePath.substring(0, lastSlash + 1); // ends with '/'
        try {
            jos.putNextEntry(new JarEntry(parentPath));
            jos.closeEntry();
        } catch (java.util.zip.ZipException ignored) {
            // duplicate entry — already added
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // recurse upward
        addDirectoryEntries(jos, parentPath.substring(0, parentPath.length() - 1));
    }
}
