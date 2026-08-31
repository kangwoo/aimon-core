package at.aimon.core.subagent.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayName("ClasspathSubagentRepository Tests")
class ClasspathSubagentRepositoryTest {

    private static final String BASE_PATH = "builtin/agents";
    private static final String MERGED_BASE_PATH = "merged/agents";

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create repository with valid base path")
        void shouldCreateWithValidBasePath() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);
            assertThat(repository).isNotNull();
        }

        @Test
        @DisplayName("Should throw exception when base path is null")
        void shouldThrowWhenBasePathIsNull() {
            assertThatThrownBy(() -> new ClasspathSubagentRepository(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Base path cannot be null");
        }

        @Test
        @DisplayName("Should throw exception when class loader is null")
        void shouldThrowWhenClassLoaderIsNull() {
            assertThatThrownBy(() -> new ClasspathSubagentRepository(BASE_PATH, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ClassLoader cannot be null");
        }

        @Test
        @DisplayName("Should create repository with custom class loader")
        void shouldCreateWithCustomClassLoader() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH,
                    getClass().getClassLoader());
            assertThat(repository).isNotNull();
        }
    }

    @Nested
    @DisplayName("findByName")
    class FindByNameTests {

        @Test
        @DisplayName("Should find subagent by name when resource exists")
        void shouldFindSubagentWhenResourceExists() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            Optional<String> result = repository.findByName("explore");

            assertThat(result).isPresent();
            assertThat(result.get()).contains("name: explore");
            assertThat(result.get()).contains("Fast agent for exploring codebases");
        }

        @Test
        @DisplayName("Should return empty when resource does not exist")
        void shouldReturnEmptyWhenResourceDoesNotExist() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            Optional<String> result = repository.findByName("non-existent");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should throw exception when name is null")
        void shouldThrowWhenNameIsNull() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            assertThatThrownBy(() -> repository.findByName(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Subagent name cannot be null");
        }

        @Test
        @DisplayName("Should preserve content including frontmatter and body")
        void shouldPreserveContent() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            Optional<String> result = repository.findByName("code-reviewer");

            assertThat(result).isPresent();
            assertThat(result.get()).contains("name: code-reviewer");
            assertThat(result.get()).contains("Code review agent");
            assertThat(result.get()).contains("Security vulnerabilities");
        }

        @Test
        @DisplayName("Should return empty when base path does not exist")
        void shouldReturnEmptyWhenBasePathDoesNotExist() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository("nonexistent/path");

            Optional<String> result = repository.findByName("explore");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findAllNames")
    class FindAllNamesTests {

        @Test
        @DisplayName("Should return all names from index file")
        void shouldReturnAllNamesFromIndex() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            List<String> names = repository.findAllNames();

            assertThat(names).containsExactly("explore", "code-reviewer");
        }

        @Test
        @DisplayName("Should filter out comments and empty lines from index")
        void shouldFilterCommentsAndEmptyLines() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            List<String> names = repository.findAllNames();

            assertThat(names).doesNotContain("# Built-in agents for testing");
            assertThat(names).doesNotContain("");
        }

        @Test
        @DisplayName("Should return empty list when index file does not exist")
        void shouldReturnEmptyListWhenIndexDoesNotExist() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository("nonexistent/path");

            List<String> names = repository.findAllNames();

            assertThat(names).isEmpty();
        }
    }

    @Nested
    @DisplayName("exists")
    class ExistsTests {

        @Test
        @DisplayName("Should return true when resource exists")
        void shouldReturnTrueWhenResourceExists() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            assertThat(repository.exists("explore")).isTrue();
            assertThat(repository.exists("code-reviewer")).isTrue();
        }

        @Test
        @DisplayName("Should return false when resource does not exist")
        void shouldReturnFalseWhenResourceDoesNotExist() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            assertThat(repository.exists("non-existent")).isFalse();
        }

        @Test
        @DisplayName("Should throw exception when name is null")
        void shouldThrowWhenNameIsNull() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository(BASE_PATH);

            assertThatThrownBy(() -> repository.exists(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Subagent name cannot be null");
        }

        @Test
        @DisplayName("Should return false when base path does not exist")
        void shouldReturnFalseWhenBasePathDoesNotExist() {
            ClasspathSubagentRepository repository = new ClasspathSubagentRepository("nonexistent/path");

            assertThat(repository.exists("explore")).isFalse();
        }
    }

    /**
     * Exercises index aggregation over a real multi-root class path built from an exploded directory plus a JAR, so
     * that merging is proven against the JDK's own resource enumeration rather than against a mock.
     */
    @Nested
    @DisplayName("findAllNames - multi-source aggregation")
    class MultiSourceIndexTests {

        @TempDir
        Path tempDir;

        private Logger repositoryLogger;
        private ListAppender<ILoggingEvent> logAppender;

        @BeforeEach
        void attachLogAppender() {
            repositoryLogger = (Logger) LoggerFactory.getLogger(ClasspathSubagentRepository.class);
            logAppender = new ListAppender<>();
            logAppender.start();
            repositoryLogger.addAppender(logAppender);
        }

        @AfterEach
        void detachLogAppender() {
            repositoryLogger.detachAppender(logAppender);
            logAppender.stop();
        }

        @Test
        @DisplayName("Should merge names from an exploded root and a JAR root")
        void shouldMergeNamesAcrossRoots() throws Exception {
            final URL dirRoot = explodedRoot("root-a", Map.of(indexPath(), "# built-in agents\nexplore\n\nplan\n",
                    subagentPath("explore"), subagent("explore"), subagentPath("plan"), subagent("plan")));
            final URL jarRoot = jarRoot("root-b.jar",
                    Map.of(indexPath(), "code-reviewer\n", subagentPath("code-reviewer"), subagent("code-reviewer")));

            try (URLClassLoader loader = new URLClassLoader(new URL[]{dirRoot, jarRoot}, null)) {
                final List<String> names = new ClasspathSubagentRepository(MERGED_BASE_PATH, loader).findAllNames();

                assertThat(names).containsExactly("explore", "plan", "code-reviewer");
            }
        }

        @Test
        @DisplayName("Should preserve declaration order inside each source rather than sorting")
        void shouldPreserveDeclarationOrderWithinEachSource() throws Exception {
            final URL dirRoot = explodedRoot("root-a", Map.of(indexPath(), "plan\nexplore\n"));
            final URL jarRoot = jarRoot("root-b.jar", Map.of(indexPath(), "code-reviewer\nauditor\n"));

            try (URLClassLoader loader = new URLClassLoader(new URL[]{dirRoot, jarRoot}, null)) {
                final List<String> names = new ClasspathSubagentRepository(MERGED_BASE_PATH, loader).findAllNames();

                assertThat(names).containsExactly("plan", "explore", "code-reviewer", "auditor");
            }
        }

        @Test
        @DisplayName("Should keep the first declaration and warn naming both sources on a duplicate name")
        void shouldKeepFirstDeclarationAndWarnOnDuplicate() throws Exception {
            final URL dirRoot = explodedRoot("root-a",
                    Map.of(indexPath(), "explore\nshared\n", subagentPath("shared"), subagent("shared from root-a")));
            final URL jarRoot = jarRoot("root-b.jar", Map.of(indexPath(), "shared\ncode-reviewer\n",
                    subagentPath("shared"), subagent("shared from root-b")));

            try (URLClassLoader loader = new URLClassLoader(new URL[]{dirRoot, jarRoot}, null)) {
                final ClasspathSubagentRepository repository = new ClasspathSubagentRepository(MERGED_BASE_PATH,
                        loader);

                final List<String> names = repository.findAllNames();

                assertThat(names).containsExactly("explore", "shared", "code-reviewer");
                assertThat(logAppender.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("shared").contains(dirRoot.toExternalForm())
                            .contains(jarRoot.toExternalForm());
                });
                // First-wins also governs the body: the winning declaration and the resolved .md file agree.
                assertThat(repository.findByName("shared"))
                        .hasValueSatisfying(content -> assertThat(content).contains("shared from root-a"));
            }
        }

        @Test
        @DisplayName("Should filter comments and blank lines in every source, not only the first")
        void shouldFilterCommentsAndBlankLinesInEverySource() throws Exception {
            final URL dirRoot = explodedRoot("root-a", Map.of(indexPath(), "# first root\nexplore\n"));
            final URL jarRoot = jarRoot("root-b.jar",
                    Map.of(indexPath(), "\n# second root\n\n  code-reviewer  \n   \n"));

            try (URLClassLoader loader = new URLClassLoader(new URL[]{dirRoot, jarRoot}, null)) {
                final List<String> names = new ClasspathSubagentRepository(MERGED_BASE_PATH, loader).findAllNames();

                assertThat(names).containsExactly("explore", "code-reviewer");
            }
        }

        @Test
        @DisplayName("Should not report a conflict when parent delegation enumerates one index URL twice")
        void shouldNotWarnWhenParentDelegationEnumeratesSameIndexUrlTwice() throws Exception {
            final URL jarRoot = jarRoot("root-b.jar", Map.of(indexPath(), "explore\ncode-reviewer\n"));

            // Listing the same JAR twice in ONE loader's URL array does not reproduce this: URLClassPath.getLoader
            // skips a URL whose urlNoFragString is already in its lmap, so {jarRoot, jarRoot} yields a single hit
            // and would assert nothing. Real duplicate enumeration comes from parent delegation —
            // ClassLoader.getResources concatenates the parent's hits with the loader's own — which is what an app
            // that puts the same artifact on both a parent and a child loader actually produces.
            try (URLClassLoader parent = new URLClassLoader(new URL[]{jarRoot}, null);
                    URLClassLoader loader = new URLClassLoader(new URL[]{jarRoot}, parent)) {

                // Guard the fixture: without two byte-identical URLs the assertions below prove nothing.
                final List<URL> enumerated = Collections.list(loader.getResources(indexPath()));
                assertThat(enumerated).hasSize(2);
                assertThat(enumerated.get(0).toExternalForm()).isEqualTo(enumerated.get(1).toExternalForm());

                final List<String> names = new ClasspathSubagentRepository(MERGED_BASE_PATH, loader).findAllNames();

                // One index reached twice is one source, not a conflict: no name repeats and nothing is logged.
                // Guaranteed jointly by the URL de-dup in findIndexResources and the same-source check in
                // findAllNames, so this asserts the behaviour rather than pinning either mechanism.
                assertThat(names).containsExactly("explore", "code-reviewer");
                assertThat(logAppender.list).isEmpty();
            }
        }

        @Test
        @DisplayName("Should resolve a subagent declared in one root whose definition lives in another")
        void shouldResolveSubagentDeclaredInOneRootWithBodyInAnother() throws Exception {
            final URL dirRoot = explodedRoot("root-a",
                    Map.of(indexPath(), "remote\nexplore\n", subagentPath("explore"), subagent("explore")));
            final URL jarRoot = jarRoot("root-b.jar", Map.of(indexPath(), "code-reviewer\n", subagentPath("remote"),
                    subagent("remote"), subagentPath("code-reviewer"), subagent("code-reviewer")));

            try (URLClassLoader loader = new URLClassLoader(new URL[]{dirRoot, jarRoot}, null)) {
                final ClasspathSubagentRepository repository = new ClasspathSubagentRepository(MERGED_BASE_PATH,
                        loader);

                assertThat(repository.findAllNames()).containsExactly("remote", "explore", "code-reviewer");
                assertThat(repository.findByName("remote")).isPresent();
                assertThat(repository.exists("remote")).isTrue();
            }
        }

        @Test
        @DisplayName("Should behave exactly as before with a single root and log nothing")
        void shouldBehaveIdenticallyWithSingleRoot() throws Exception {
            final URL dirRoot = explodedRoot("root-a",
                    Map.of(indexPath(), "# built-in agents\nexplore\ncode-reviewer\n"));

            try (URLClassLoader loader = new URLClassLoader(new URL[]{dirRoot}, null)) {
                final List<String> names = new ClasspathSubagentRepository(MERGED_BASE_PATH, loader).findAllNames();

                assertThat(names).containsExactly("explore", "code-reviewer");
                assertThat(logAppender.list).isEmpty();
            }
        }

        @Test
        @DisplayName("Should return an empty list when no root carries an index")
        void shouldReturnEmptyWhenNoRootHasIndex() throws Exception {
            final URL dirRoot = explodedRoot("root-a", Map.of(subagentPath("explore"), subagent("explore")));
            final URL jarRoot = jarRoot("root-b.jar", Map.of(subagentPath("code-reviewer"), subagent("code-reviewer")));

            try (URLClassLoader loader = new URLClassLoader(new URL[]{dirRoot, jarRoot}, null)) {
                assertThat(new ClasspathSubagentRepository(MERGED_BASE_PATH, loader).findAllNames()).isEmpty();
            }
        }

        private String indexPath() {
            return MERGED_BASE_PATH + "/index";
        }

        private String subagentPath(String subagentName) {
            return MERGED_BASE_PATH + "/" + subagentName + ".md";
        }

        private String subagent(String marker) {
            return "---\nname: " + marker.split(" ")[0] + "\ndescription: \"" + marker + "\"\n---\n# " + marker + "\n";
        }

        private URL explodedRoot(String name, Map<String, String> resources) throws IOException {
            final Path root = Files.createDirectories(tempDir.resolve(name));
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                final Path file = root.resolve(resource.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, resource.getValue(), StandardCharsets.UTF_8);
            }
            return directoryUrl(root);
        }

        private URL jarRoot(String fileName, Map<String, String> resources) throws IOException {
            final Path jarPath = tempDir.resolve(fileName);
            // Directory entries are deliberately omitted, mirroring a Shadow/Spring Boot repackaged archive.
            try (OutputStream out = Files.newOutputStream(jarPath); JarOutputStream jar = new JarOutputStream(out)) {
                for (Map.Entry<String, String> resource : resources.entrySet()) {
                    jar.putNextEntry(new JarEntry(resource.getKey()));
                    jar.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                    jar.closeEntry();
                }
            }
            return jarPath.toUri().toURL();
        }
    }

    /**
     * Builds a class path root URL for a directory. URLClassLoader only treats a URL as a directory root when it ends
     * with a slash; without one the root would silently resolve nothing and the tests would pass vacuously.
     */
    private static URL directoryUrl(Path directory) throws IOException {
        final String uri = directory.toUri().toString();
        return URI.create(uri.endsWith("/") ? uri : uri + "/").toURL();
    }
}
