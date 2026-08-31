package at.aimon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A published library logs through the SLF4J API and does not choose the binding for its consumers.
 *
 * <p>
 * SLF4J resolves exactly one provider per application. When a library declares a binding on a consumer-visible
 * configuration — {@code implementation}, {@code api}, {@code runtimeOnly} — that binding is published in the POM's
 * runtime scope and is dragged into every application that depends on the library. An application that already runs
 * log4j2, or any other provider, then gets a multiple-binding conflict it never asked for and cannot remove without
 * an exclusion. The choice belongs to whoever assembles the application, which is why {@code aimon-cli} — the only
 * module here that is an application rather than a library — declares logback directly and ships the single
 * {@code logback.xml} in the repo.
 *
 * <p>
 * Tests are a different matter: they run inside this build, so they are an application in their own right and need a
 * binding. Test-scoped configurations supply one and are invisible to publication — the {@code JavaLibrary} component
 * that {@code aimon.publishable} publishes is {@code apiElements} plus {@code runtimeElements}, and no test
 * configuration feeds either.
 *
 * <h2>What is enforced</h2>
 *
 * <p>
 * A binding coordinate may appear on a test configuration and nowhere else, in two places:
 *
 * <ul>
 * <li>the {@code build.gradle.kts} of every module under {@code modules/} that applies {@code aimon.publishable};
 * <li>every shared build script whose declarations those modules <em>inherit</em> — the pre-compiled script plugins
 * under {@code buildSrc/src/main/kotlin/} and the root {@code build.gradle.kts}. A {@code runtimeOnly} binding in
 * {@code aimon.java-conventions} is one line that poisons every published POM at once, and scanning module scripts
 * alone would never see it.
 * </ul>
 *
 * <p>
 * "Binding coordinate" is a set, not the single string {@code logback}: {@code slf4j-simple},
 * {@code log4j-slf4j2-impl} and friends reintroduce exactly the same conflict. See {@link #BINDING_COORDINATES}.
 * Matching is deliberately narrow — {@code slf4j-api} is the API every module is supposed to depend on and is never
 * flagged, nor are the {@code jul-to-slf4j} / {@code jcl-over-slf4j} bridges, which route <em>into</em> SLF4J and so
 * do not create a second provider.
 *
 * <h2>What this still cannot see</h2>
 *
 * <p>
 * The check reads build scripts as text, so a binding that is never named in one escapes it:
 *
 * <ul>
 * <li><b>transitive bindings</b> — a coordinate that drags a provider in behind it. {@code spring-boot-starter} pulls
 * {@code spring-boot-starter-logging}, hence logback, and the script says only "starter";
 * <li><b>version catalog bundles</b> — {@code implementation(libs.bundles.jackson)} hides its members, and the catalog
 * is not expanded here;
 * <li><b>third-party plugins</b> that add dependencies from their own code rather than from a script in this repo.
 * </ul>
 *
 * <p>
 * Closing those needs a resolved dependency graph, which needs a configured Gradle project — a different kind of check
 * than this one. {@code buildSrc/build.gradle.kts} is deliberately out of scope for the opposite reason: it configures
 * the build's own classpath, which never reaches a consumer.
 *
 * <p>
 * Build scripts rather than bytecode is also why this is plain JUnit and not ArchUnit — ArchUnit sees compiled classes
 * and a dependency declaration never becomes one. It scans every module rather than the two that were fixed, because
 * the regression it guards against is a new module or a new declaration, not the old ones coming back.
 */
@DisplayName("published module logging binding")
class PublishedModuleLoggingBindingTest {

    /** Marks a module as published to Maven Central, hence subject to the rule. */
    private static final String PUBLISHABLE_PLUGIN = "id(\"aimon.publishable\")";

    /**
     * Configurations that stay inside the build. Deliberately an allowlist rather than a {@code startsWith("test")}
     * prefix test: {@code testFixturesApi} and {@code testFixturesImplementation} also start with "test" but are
     * published when {@code java-test-fixtures} is applied.
     */
    private static final List<String> TEST_SCOPED_CONFIGURATIONS = List.of("testImplementation", "testRuntimeOnly",
            "testCompileOnly");

    /**
     * Artifact ids that make a JAR an SLF4J provider. Any one of them is the multiple-binding conflict; which one is
     * irrelevant, so the guard cannot be a single {@code logback} substring.
     *
     * <p>
     * Compared against a declaration whose {@code .} and {@code :} have been folded to {@code -}, so one entry covers
     * every spelling a build script can use: the catalog accessor {@code libs.slf4j.simple}, the lookup
     * {@code libs.findLibrary("slf4j-simple")}, and the raw GAV {@code "org.slf4j:slf4j-simple:2.0.9"}. Each entry is
     * a full artifact id — never a bare {@code slf4j} — so {@code slf4j-api} does not match.
     *
     * <p>
     * One binding is deliberately absent. {@code slf4j-jcl} is the SLF4J 1.x binding to Commons Logging; it has no
     * 2.x release, and the fold above makes it a substring of the group-plus-artifact of the
     * {@code org.slf4j:jcl-over-slf4j} <em>bridge</em>, which is not a binding. Adding it back would flag that bridge
     * as a violation — if a 1.x binding ever has to be matched, match it with a boundary-aware rule rather than by
     * dropping it into this list.
     */
    private static final List<String> BINDING_COORDINATES = List.of("logback", "slf4j-simple", "slf4j-jdk14",
            "slf4j-nop", "slf4j-log4j12", "slf4j-reload4j", "log4j-slf4j-impl", "log4j-slf4j2-impl");

    /** Sentinel for the shared-script scan: if this is ever renamed, the scan must fail rather than shrink. */
    private static final String CONVENTIONS_SCRIPT = "aimon.java-conventions.gradle.kts";

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    @DisplayName("no published module declares a binding on a consumer-visible configuration")
    void publishedModulesDeclareBindingsInTestScopeOnly() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final Path modulesDirectory = REPOSITORY_ROOT.resolve("modules");
        final List<Path> publishedBuildScripts = publishedModuleBuildScripts(modulesDirectory);

        // Without this the scan would report success just as loudly if it had walked into the wrong directory and
        // found no build scripts at all.
        assertThat(publishedBuildScripts)
                .withFailMessage("found no published module build scripts under %s — the scan is broken, not clean",
                        modulesDirectory)
                .isNotEmpty();
        assertThat(publishedBuildScripts).anyMatch(script -> "aimon-core".equals(moduleNameOf(script)));

        for (final Path buildScript : publishedBuildScripts) {
            assertBindingsAreTestScopedOnly(buildScript);
        }
    }

    @Test
    @DisplayName("no shared build script declares a binding every published module would inherit")
    void sharedBuildScriptsDeclareBindingsInTestScopeOnly() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        final List<Path> sharedBuildScripts = sharedBuildScripts();

        assertThat(sharedBuildScripts).withFailMessage(
                "found no shared build scripts under %s — the scan is broken, not clean", REPOSITORY_ROOT).isNotEmpty();
        assertThat(sharedBuildScripts)
                .withFailMessage(
                        "%s was not among the scanned shared scripts — it holds the dependency block every "
                                + "module inherits, so if it moved, this scan must be pointed at its new home",
                        CONVENTIONS_SCRIPT)
                .anyMatch(script -> CONVENTIONS_SCRIPT.equals(script.getFileName().toString()));

        for (final Path buildScript : sharedBuildScripts) {
            assertBindingsAreTestScopedOnly(buildScript);
        }
    }

    @Test
    @DisplayName("the configuration allowlist accepts test scopes and only those")
    void allowlistAcceptsTestScopesAndOnlyThose() {
        // The rules above pass as written, so a hole in the allowlist stays invisible until the day it lets a real
        // leak through. Both directions are pinned here instead. The quoted spellings are what a pre-compiled script
        // plugin has to write, since typed configuration accessors are not generated there.
        final List<String> testScoped = List.of("testImplementation(libs.logback.classic)",
                "testRuntimeOnly(libs.logback.classic)", "testCompileOnly(libs.logback.classic)",
                "\"testImplementation\"(libs.findLibrary(\"logback-classic\").get())",
                "\"testRuntimeOnly\"(libs.findLibrary(\"logback-classic\").get())");
        final List<String> consumerVisible = List.of("runtimeOnly(libs.logback.classic)",
                "implementation(libs.logback.classic)", "api(libs.logback.classic)",
                "compileOnly(libs.logback.classic)", "testFixturesApi(libs.logback.classic)",
                "testFixturesImplementation(libs.logback.classic)",
                "\"runtimeOnly\"(libs.findLibrary(\"logback-classic\").get())",
                "\"implementation\"(libs.findLibrary(\"logback-classic\").get())");

        assertThat(testScoped).allSatisfy(declaration -> assertThat(isTestScoped(declaration))
                .withFailMessage("expected %s to be accepted as test-scoped", declaration).isTrue());
        assertThat(consumerVisible).allSatisfy(declaration -> assertThat(isTestScoped(declaration))
                .withFailMessage("expected %s to be rejected as consumer-visible", declaration).isFalse());
    }

    @Test
    @DisplayName("the coordinate matcher recognises providers and leaves the API and bridges alone")
    void coordinateMatcherRecognisesProvidersOnly() {
        // Same reasoning as the allowlist test: the widening from one substring to a set is only worth anything if
        // both directions are pinned. A matcher that fired on `slf4j-api` would fail every module in the repo; one
        // that missed `slf4j-simple` would let the conflict back in silently.
        final List<String> bindings = List.of("runtimeOnly(libs.logback.classic)",
                "\"runtimeOnly\"(libs.findLibrary(\"logback-classic\").get())", "implementation(libs.slf4j.simple)",
                "implementation(\"org.slf4j:slf4j-simple:2.0.9\")", "runtimeOnly(\"org.slf4j:slf4j-nop:2.0.9\")",
                "runtimeOnly(libs.slf4j.jdk14)", "runtimeOnly(\"org.apache.logging.log4j:log4j-slf4j2-impl:2.24.1\")");
        final List<String> notBindings = List.of("implementation(libs.slf4j.api)",
                "implementation(\"org.slf4j:slf4j-api:2.0.9\")",
                "\"implementation\"(libs.findLibrary(\"slf4j-api\").get())", "implementation(libs.jul.to.slf4j)",
                "implementation(\"org.slf4j:jcl-over-slf4j:2.0.9\")", "implementation(libs.bundles.jackson)");

        assertThat(bindings).allSatisfy(declaration -> assertThat(declaresBinding(declaration))
                .withFailMessage("expected %s to be recognised as an SLF4J binding", declaration).isTrue());
        assertThat(notBindings).allSatisfy(declaration -> assertThat(declaresBinding(declaration))
                .withFailMessage("expected %s not to be recognised as an SLF4J binding", declaration).isFalse());
    }

    private static void assertBindingsAreTestScopedOnly(Path buildScript) throws IOException {
        final List<String> lines = Files.readAllLines(buildScript);
        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index).strip();
            // A comment may legitimately name a binding — the rule itself is written out in prose in several of these
            // scripts. Leading comment markers are dropped outright; a trailing `//` is cut off the code before it.
            if (isComment(line)) {
                continue;
            }
            final String declaration = stripTrailingComment(line).strip();
            if (!declaresBinding(declaration)) {
                continue;
            }
            assertThat(isTestScoped(declaration)).withFailMessage(
                    "%s:%d declares an SLF4J binding on a consumer-visible configuration:%n" + "  %s%n"
                            + "A published library logs through the SLF4J API and must not choose the binding — this "
                            + "lands in the published POM's runtime scope and collides with the host application's "
                            + "own provider; in a shared build script it does so for every published module at once. "
                            + "Move it to a test configuration (%s).",
                    describe(buildScript), index + 1, line, String.join(", ", TEST_SCOPED_CONFIGURATIONS)).isTrue();
        }
    }

    private static List<Path> publishedModuleBuildScripts(Path modulesDirectory) throws IOException {
        if (!Files.isDirectory(modulesDirectory)) {
            return List.of();
        }
        final List<Path> publishedBuildScripts = new ArrayList<>();
        try (Stream<Path> modules = Files.list(modulesDirectory)) {
            for (final Path module : modules.sorted().toList()) {
                final Path buildScript = module.resolve("build.gradle.kts");
                if (Files.isRegularFile(buildScript) && Files.readString(buildScript).contains(PUBLISHABLE_PLUGIN)) {
                    publishedBuildScripts.add(buildScript);
                }
            }
        }
        return publishedBuildScripts;
    }

    /**
     * Build scripts whose declarations every published module inherits: the pre-compiled script plugins — all of them,
     * not just {@code aimon.java-conventions}, since a new one could carry the same leak — and the root build script,
     * whose {@code allprojects} / {@code subprojects} blocks reach the same modules.
     */
    private static List<Path> sharedBuildScripts() throws IOException {
        final List<Path> sharedBuildScripts = new ArrayList<>();
        final Path pluginDirectory = REPOSITORY_ROOT.resolve("buildSrc/src/main/kotlin");
        if (Files.isDirectory(pluginDirectory)) {
            try (Stream<Path> scripts = Files.list(pluginDirectory)) {
                scripts.sorted().filter(Files::isRegularFile)
                        .filter(script -> script.getFileName().toString().endsWith(".gradle.kts"))
                        .forEach(sharedBuildScripts::add);
            }
        }
        final Path rootBuildScript = REPOSITORY_ROOT.resolve("build.gradle.kts");
        if (Files.isRegularFile(rootBuildScript)) {
            sharedBuildScripts.add(rootBuildScript);
        }
        return sharedBuildScripts;
    }

    /**
     * Whether the declaration names a coordinate that turns the classpath into an SLF4J provider. The fold of
     * {@code .} and {@code :} to {@code -} is what lets one entry per artifact cover the catalog accessor, the
     * {@code findLibrary} lookup and the raw GAV alike.
     */
    private static boolean declaresBinding(String declaration) {
        final String normalized = declaration.toLowerCase(Locale.ROOT).replace('.', '-').replace(':', '-');
        return BINDING_COORDINATES.stream().anyMatch(normalized::contains);
    }

    private static boolean isTestScoped(String declaration) {
        return TEST_SCOPED_CONFIGURATIONS.stream().anyMatch(configuration -> declaration.startsWith(configuration + "(")
                || declaration.startsWith("\"" + configuration + "\"("));
    }

    private static boolean isComment(String declaration) {
        return declaration.startsWith("//") || declaration.startsWith("/*") || declaration.startsWith("*");
    }

    /**
     * Drops a trailing {@code //} comment so prose after a declaration cannot be read as part of it. The scheme in a
     * URL is skipped — {@code https://} is not a comment marker.
     */
    private static String stripTrailingComment(String line) {
        int marker = line.indexOf("//");
        while (marker > 0 && line.charAt(marker - 1) == ':') {
            marker = line.indexOf("//", marker + 2);
        }
        return marker < 0 ? line : line.substring(0, marker);
    }

    private static String moduleNameOf(Path buildScript) {
        return buildScript.getParent().getFileName().toString();
    }

    /** Repository-relative path, so a failure message points at a file the reader can open. */
    private static String describe(Path buildScript) {
        return REPOSITORY_ROOT.relativize(buildScript).toString();
    }

    /**
     * Walks up from the test's working directory — the module directory under Gradle's defaults — until it finds the
     * directory holding both {@code settings.gradle.kts} and {@code modules/}. Returns {@code null} rather than
     * throwing so the test can skip itself if it is ever run from somewhere unexpected.
     */
    private static Path locateRepositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
                    && Files.isDirectory(candidate.resolve("modules"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
