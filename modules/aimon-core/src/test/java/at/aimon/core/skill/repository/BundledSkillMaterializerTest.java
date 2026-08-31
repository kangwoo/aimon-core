package at.aimon.core.skill.repository;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Unit tests for BundledSkillMaterializer. */
class BundledSkillMaterializerTest {

    private static final String CLASSPATH_SKILLS_BASE = "materializer/skills";
    private static final String MULTI_ROOT_SKILLS_BASE = "multiroot/skills";
    private static final String TARGET_DIR = ".aimon/bundled-skills";

    @TempDir
    Path tempDir;

    /** Separate from {@link #tempDir}, which is the workspace root: this one holds synthetic class path roots. */
    @TempDir
    Path classpathDir;

    private LocalFileSystem fileSystem;
    private BundledSkillMaterializer materializer;

    @BeforeEach
    void setUp() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();

        materializer = new BundledSkillMaterializer(Thread.currentThread().getContextClassLoader());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    void constructor_NullClassLoader_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new BundledSkillMaterializer(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ClassLoader cannot be null");
    }

    @Test
    void materialize_ValidClasspathBase_ReturnsMaterializedSkillNames() {
        java.util.List<String> result = materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(result).containsExactly("demo");
    }

    @Test
    void materialize_ValidClasspathBase_WritesSKILLMdToDestination() {
        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(fileSystem.exists(TARGET_DIR + "/demo/SKILL.md")).isTrue();
    }

    @Test
    void materialize_ValidClasspathBase_WritesTemplatesReportMd() {
        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(fileSystem.exists(TARGET_DIR + "/demo/templates/report.md")).isTrue();
    }

    @Test
    void materialize_ValidClasspathBase_WritesScriptsRunPy() {
        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(fileSystem.exists(TARGET_DIR + "/demo/scripts/run.py")).isTrue();
    }

    @Test
    void materialize_ValidClasspathBase_WritesReferencesSchema() {
        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(fileSystem.exists(TARGET_DIR + "/demo/references/schema.md")).isTrue();
    }

    @Test
    void materialize_ValidClasspathBase_TemplateFileContentIsCorrect() throws IOException {
        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        String templatePath = TARGET_DIR + "/demo/templates/report.md";
        assertThat(fileSystem.exists(templatePath)).isTrue();

        String content = readFileContent(templatePath);
        assertThat(content).contains("# Report Template");
    }

    @Test
    void materialize_NonExistentClasspathBase_ReturnsEmptyList() {
        java.util.List<String> result = materializer.materialize("nonexistent/skills/base", fileSystem, TARGET_DIR);

        assertThat(result).isEmpty();
    }

    @Test
    void materialize_NonExistentClasspathBase_WritesNoFiles() {
        materializer.materialize("nonexistent/skills/base", fileSystem, TARGET_DIR);

        assertThat(fileSystem.exists(TARGET_DIR)).isFalse();
    }

    @Test
    void materialize_OverwriteSemantics_StaleFileIsRemoved() {
        // Pre-write a stale file that should be cleared on re-materialize
        String staleFilePath = TARGET_DIR + "/demo/STALE.txt";
        fileSystem.write(staleFilePath, "stale content".getBytes(StandardCharsets.UTF_8));
        assertThat(fileSystem.exists(staleFilePath)).isTrue();

        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(fileSystem.exists(staleFilePath)).isFalse();
    }

    @Test
    void materialize_OverwriteSemantics_RealFilesExistAfterClearing() {
        // Pre-write a stale file
        String staleFilePath = TARGET_DIR + "/demo/STALE.txt";
        fileSystem.write(staleFilePath, "stale content".getBytes(StandardCharsets.UTF_8));

        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(fileSystem.exists(TARGET_DIR + "/demo/SKILL.md")).isTrue();
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/templates/report.md")).isTrue();
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/scripts/run.py")).isTrue();
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/references/schema.md")).isTrue();
    }

    @Test
    void materialize_NullClasspathSkillsBase_ThrowsNullPointerException() {
        assertThatThrownBy(() -> materializer.materialize(null, fileSystem, TARGET_DIR))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void materialize_NullFileSystem_ThrowsNullPointerException() {
        assertThatThrownBy(() -> materializer.materialize(CLASSPATH_SKILLS_BASE, null, TARGET_DIR))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void materialize_NullTargetDir_ThrowsNullPointerException() {
        assertThatThrownBy(() -> materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void materialize_PrunesSkillsNoLongerInIndex() {
        // Simulate a skill that was materialized by a previous release but is no longer in the index.
        String ghostSkillFile = TARGET_DIR + "/ghost/SKILL.md";
        fileSystem.write(ghostSkillFile, "---\nname: ghost\ndescription: x\n---\n".getBytes(StandardCharsets.UTF_8));
        assertThat(fileSystem.exists(ghostSkillFile)).isTrue();

        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        // The stale skill directory is pruned; the indexed skill remains.
        assertThat(fileSystem.exists(TARGET_DIR + "/ghost")).isFalse();
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/SKILL.md")).isTrue();
    }

    @Test
    void materialize_CalledTwice_IsIdempotentAndReturnsMaterializedNames() {
        java.util.List<String> first = materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);
        java.util.List<String> second = materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(first).containsExactly("demo");
        assertThat(second).containsExactly("demo");
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/SKILL.md")).isTrue();
    }

    @Test
    void materialize_EmptyOrAbsentIndex_PrunesPreviouslyMaterializedSkills() {
        // A previous release materialized two skills; the current release ships none (absent index).
        fileSystem.write(TARGET_DIR + "/ghost/SKILL.md", "---\nname: ghost\n---\n".getBytes(StandardCharsets.UTF_8));
        fileSystem.write(TARGET_DIR + "/demo/SKILL.md", "---\nname: demo\n---\n".getBytes(StandardCharsets.UTF_8));

        List<String> result = materializer.materialize("nonexistent/skills/base", fileSystem, TARGET_DIR);

        assertThat(result).isEmpty();
        assertThat(fileSystem.exists(TARGET_DIR + "/ghost")).isFalse();
        assertThat(fileSystem.exists(TARGET_DIR + "/demo")).isFalse();
    }

    @Test
    void materialize_IndexReadFailure_ReturnsEmptyAndPreservesPreviousCopy() {
        // A transient index-read failure must fail SAFE: no-op, do NOT prune the existing materialized copy.
        fileSystem.write(TARGET_DIR + "/demo/SKILL.md", "---\nname: demo\n---\n".getBytes(StandardCharsets.UTF_8));
        final BundledSkillMaterializer failing = new BundledSkillMaterializer(new FailingResourceClassLoader(
                Thread.currentThread().getContextClassLoader(), CLASSPATH_SKILLS_BASE + "/index"));

        List<String> result = failing.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(result).isEmpty();
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/SKILL.md")).isTrue();
    }

    @Test
    void materialize_JarWithoutDirectoryEntries_MaterializesViaAnchor() throws IOException {
        // Emulate a fat/uber JAR that strips directory entries; the SKILL.md anchor must drive enumeration end-to-end.
        final Path jarFile = tempDir.resolve("bundle.jar");
        createJarWithoutDirectoryEntries(jarFile, new String[]{"bundle/skills/index", "bundle/skills/demo/SKILL.md",
                "bundle/skills/demo/templates/report.md"}, "demo\n");

        final URL jarUrl = new URL("file:" + jarFile.toAbsolutePath());
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, null)) {
            final BundledSkillMaterializer jarMaterializer = new BundledSkillMaterializer(loader);

            List<String> result = jarMaterializer.materialize("bundle/skills", fileSystem, TARGET_DIR);

            assertThat(result).containsExactly("demo");
            assertThat(fileSystem.exists(TARGET_DIR + "/demo/SKILL.md")).isTrue();
            assertThat(fileSystem.exists(TARGET_DIR + "/demo/templates/report.md")).isTrue();
        }
    }

    @Test
    void materialize_ResourceReadFailureMidCopy_LeavesPreviousCopyIntact() {
        // Seed a good materialized copy with the normal class loader.
        materializer.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/templates/report.md")).isTrue();

        // Re-materialize with a loader that throws while reading one resource; reads happen before the destination is
        // cleared, so the previously materialized copy must survive untouched.
        final BundledSkillMaterializer failing = new BundledSkillMaterializer(new FailingResourceClassLoader(
                Thread.currentThread().getContextClassLoader(), CLASSPATH_SKILLS_BASE + "/demo/templates/report.md"));

        List<String> result = failing.materialize(CLASSPATH_SKILLS_BASE, fileSystem, TARGET_DIR);

        assertThat(result).isEmpty();
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/SKILL.md")).isTrue();
        assertThat(fileSystem.exists(TARGET_DIR + "/demo/templates/report.md")).isTrue();
    }

    @Test
    void materialize_SkillDeclaredOnlyInSecondRoot_MaterializesItWithSupplementaryFiles() throws Exception {
        // Two artifacts contribute the same skills base, each with its own index — the Spring Boot assembly case.
        final URL firstRoot = explodedRoot("root-a", Map.of(indexPath(), "alpha\n", skillPath("alpha"),
                skillBody("alpha"), scriptPath("alpha", "alpha.sh"), "echo alpha\n"));
        final URL secondRoot = explodedRoot("root-b", Map.of(indexPath(), "beta\n", skillPath("beta"),
                skillBody("beta"), scriptPath("beta", "beta.sh"), "echo beta\n"));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{firstRoot, secondRoot}, null)) {
            final List<String> result = new BundledSkillMaterializer(loader).materialize(MULTI_ROOT_SKILLS_BASE,
                    fileSystem, TARGET_DIR);

            assertThat(result).containsExactly("alpha", "beta");
            // The second root's skill is materialized in full — SKILL.md plus its scripts/ tree — and, because the
            // keep-set is the merged index, it survives the prune that runs at the end of materialize().
            assertThat(fileSystem.exists(TARGET_DIR + "/beta/SKILL.md")).isTrue();
            assertThat(fileSystem.exists(TARGET_DIR + "/beta/scripts/beta.sh")).isTrue();
            assertThat(readFileContent(TARGET_DIR + "/beta/scripts/beta.sh")).contains("echo beta");
            assertThat(fileSystem.exists(TARGET_DIR + "/alpha/scripts/alpha.sh")).isTrue();
        }
    }

    @Test
    void materialize_SkillDeclaredOnlyInSecondJarRoot_MaterializesFromThatJar() throws Exception {
        // Same asymmetry, but the second root is a JAR without directory entries: the walker must locate the owning
        // archive through the SKILL.md anchor even though the first root cannot resolve the path at all.
        final URL firstRoot = explodedRoot("root-a",
                Map.of(indexPath(), "alpha\n", skillPath("alpha"), skillBody("alpha")));
        final URL secondRoot = jarRoot("root-b.jar", Map.of(indexPath(), "beta\n", skillPath("beta"), skillBody("beta"),
                scriptPath("beta", "beta.sh"), "echo beta\n"));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{firstRoot, secondRoot}, null)) {
            final List<String> result = new BundledSkillMaterializer(loader).materialize(MULTI_ROOT_SKILLS_BASE,
                    fileSystem, TARGET_DIR);

            assertThat(result).containsExactly("alpha", "beta");
            assertThat(fileSystem.exists(TARGET_DIR + "/beta/SKILL.md")).isTrue();
            assertThat(readFileContent(TARGET_DIR + "/beta/scripts/beta.sh")).contains("echo beta");
        }
    }

    @Test
    void materialize_DuplicateNameAcrossRoots_MaterializesTheFirstRootCopyOnce() throws Exception {
        final URL firstRoot = explodedRoot("root-a",
                Map.of(indexPath(), "shared\n", skillPath("shared"), skillBody("shared") + "from root-a\n"));
        final URL secondRoot = explodedRoot("root-b",
                Map.of(indexPath(), "shared\n", skillPath("shared"), skillBody("shared") + "from root-b\n"));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{firstRoot, secondRoot}, null)) {
            final List<String> result = new BundledSkillMaterializer(loader).materialize(MULTI_ROOT_SKILLS_BASE,
                    fileSystem, TARGET_DIR);

            // First-wins on the index, and getResource resolves the body from the same root, so the two agree.
            assertThat(result).containsExactly("shared");
            assertThat(readFileContent(TARGET_DIR + "/shared/SKILL.md")).contains("from root-a");
        }
    }

    @Test
    void materialize_UnsupportedClasspathLayout_SaysSoInsteadOfClaimingTheSkillIsEmpty() throws Exception {
        // The index advertises the skill and the archive holds it — only the layout cannot be walked. Reporting that as
        // "has no files" sent readers hunting for files that were there all along.
        final URL indexOnlyRoot = explodedRoot("index-only", Map.of(indexPath(), "ghost\n"));

        final ch.qos.logback.classic.Logger materializerLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(BundledSkillMaterializer.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        materializerLogger.addAppender(appender);
        try (URLClassLoader indexLoader = new URLClassLoader(new URL[]{indexOnlyRoot}, null)) {
            final ClassLoader loader = new UnwalkableDirectoryClassLoader(indexLoader,
                    MULTI_ROOT_SKILLS_BASE + "/ghost");

            final List<String> result = new BundledSkillMaterializer(loader).materialize(MULTI_ROOT_SKILLS_BASE,
                    fileSystem, TARGET_DIR);

            assertThat(result).isEmpty();
            final List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anySatisfy(
                    message -> assertThat(message).contains("ghost", "cannot be enumerated", "vfs", "may well be"));
            assertThat(messages).noneSatisfy(message -> assertThat(message).contains("has no files"));
        } finally {
            materializerLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private static String indexPath() {
        return MULTI_ROOT_SKILLS_BASE + "/index";
    }

    private static String skillPath(String skillName) {
        return MULTI_ROOT_SKILLS_BASE + "/" + skillName + "/SKILL.md";
    }

    private static String scriptPath(String skillName, String fileName) {
        return MULTI_ROOT_SKILLS_BASE + "/" + skillName + "/scripts/" + fileName;
    }

    private static String skillBody(String skillName) {
        return "---\nname: " + skillName + "\ndescription: \"bundled " + skillName + "\"\n---\n# " + skillName + "\n";
    }

    private URL explodedRoot(String name, Map<String, String> resources) throws IOException {
        final Path root = Files.createDirectories(classpathDir.resolve(name));
        for (Map.Entry<String, String> resource : resources.entrySet()) {
            final Path file = root.resolve(resource.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, resource.getValue(), StandardCharsets.UTF_8);
        }
        // URLClassLoader only treats a URL as a directory root when it ends with a slash; without one the root would
        // resolve nothing and the test would pass vacuously.
        final String uri = root.toUri().toString();
        return URI.create(uri.endsWith("/") ? uri : uri + "/").toURL();
    }

    private URL jarRoot(String fileName, Map<String, String> resources) throws IOException {
        final Path jarPath = classpathDir.resolve(fileName);
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

    private String readFileContent(String path) throws IOException {
        try (InputStream is = fileSystem.read(path)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void createJarWithoutDirectoryEntries(Path jarPath, String[] entryNames, String indexContent)
            throws IOException {
        // Deliberately omit directory entries to emulate a Shadow/Spring Boot repackaged fat JAR.
        try (OutputStream fos = Files.newOutputStream(jarPath); JarOutputStream jos = new JarOutputStream(fos)) {
            for (final String entryName : entryNames) {
                jos.putNextEntry(new JarEntry(entryName));
                final String content = entryName.endsWith("/index") ? indexContent : "content of " + entryName;
                jos.write(content.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
    }

    /**
     * ClassLoader that makes a single named resource unreadable, delegating everything else to its parent.
     *
     * <p>
     * The failure is injected at every seam the resource can be reached through — {@code getResourceAsStream} (used for
     * skill bodies) and {@code getResource}/{@code getResources} (used for index enumeration, which reads the returned
     * URLs directly and so never passes back through the loader). Poisoning only one seam would make an "unreadable
     * index" test pass vacuously.
     */
    /**
     * Resolves one directory on a protocol the walker cannot enumerate (standing in for a container layout such as
     * JBoss/WildFly's {@code vfs:}), while everything else — notably the index — resolves normally through the parent.
     */
    private static final class UnwalkableDirectoryClassLoader extends ClassLoader {

        private final String unwalkableDirectory;

        UnwalkableDirectoryClassLoader(ClassLoader parent, String unwalkableDirectory) {
            super(parent);
            this.unwalkableDirectory = unwalkableDirectory;
        }

        @Override
        public URL getResource(String name) {
            if (name.startsWith(unwalkableDirectory)) {
                try {
                    return new URL("vfs", "", -1, "/" + name, new URLStreamHandler() {
                        @Override
                        protected URLConnection openConnection(URL url) {
                            throw new UnsupportedOperationException("Not expected to be opened");
                        }
                    });
                } catch (MalformedURLException e) {
                    throw new IllegalStateException(e);
                }
            }
            return super.getResource(name);
        }
    }

    private static final class FailingResourceClassLoader extends ClassLoader {

        private final String failResource;

        FailingResourceClassLoader(ClassLoader parent, String failResource) {
            super(parent);
            this.failResource = failResource;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (failResource.equals(name)) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("simulated read failure for " + failResource);
                    }
                };
            }
            return super.getResourceAsStream(name);
        }

        @Override
        public URL getResource(String name) {
            return failResource.equals(name) ? failingUrl() : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return failResource.equals(name)
                    ? Collections.enumeration(List.of(failingUrl()))
                    : super.getResources(name);
        }

        private URL failingUrl() {
            try {
                return new URL("file", "", -1, "/" + failResource, new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) throws IOException {
                        throw new IOException("simulated read failure for " + failResource);
                    }
                });
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
