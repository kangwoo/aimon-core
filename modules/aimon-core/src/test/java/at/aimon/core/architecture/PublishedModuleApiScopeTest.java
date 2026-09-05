package at.aimon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code api(project(":aimon-…"))} is for facades. Every other published module declares its siblings on
 * {@code implementation}.
 *
 * <p>
 * The rule is stated in {@code .claude/rules/code-style.md} and this test is that statement, enforced:
 *
 * <blockquote>implementation modules must use {@code implementation(project(":aimon-core"))}, not {@code api()} —
 * prevent leaking core types to transitive consumers. Exception — <b>facade/aggregator modules</b>, whose whole
 * purpose is to re-export a public API surface [...] <b>Not a general escape hatch — if a module only consumes core
 * types, it is an implementation module and keeps {@code implementation()}.</b></blockquote>
 *
 * <h2>Why a test and not just the rule</h2>
 *
 * <p>
 * Because the wrong answer is the one that looks right on inspection. Gradle's {@code java-library} maps
 * {@code implementation} to the published POM's <b>runtime</b> scope and {@code api} to <b>compile</b>, and every
 * backend module here does put sibling types in its own signatures — {@code RedisSessionRecordStore implements
 * SessionRecordStore}, {@code S3FileSystem implements VirtualFileSystem}, {@code OpenAILlmClient implements
 * LlmClient}. Read the POM alone and each of those looks mis-scoped, and "fixing" all seventeen is a one-word edit
 * per module that no compiler and no existing test would have objected to.
 *
 * <p>
 * The project's answer is that the POM is not the whole story: consumers are told to declare {@code aimon-core}
 * themselves ({@code README.md} installation section), the BOM aligns the version, and the transitive surface stays
 * deliberately narrow. Whether to revisit that is a decision, not a cleanup — which is exactly why it needs a guard
 * rather than a convention. See {@code docs/backlog/module-dependency-scope.md} for the open question and what would
 * have to be true to reopen it.
 *
 * <h2>What is enforced</h2>
 *
 * <p>
 * In the {@code build.gradle.kts} of every module applying {@code aimon.publishable}, a {@code project(":aimon-…")}
 * dependency may be declared on:
 *
 * <ul>
 * <li>{@code api} — only by a module in {@link #FACADE_MODULES}, whose deliverable <em>is</em> the re-exported
 * surface;
 * <li>{@code implementation} — by anything else;
 * <li>{@code compileOnly} or a test configuration — by anything, since neither reaches the published POM. The
 * starter's {@code compileOnly(project(":aimon-llm-openai"))} is the deliberate case: the vendor module is the
 * application's choice and {@code @ConditionalOnClass} wires whichever one is present.
 * </ul>
 *
 * <p>
 * {@code runtimeOnly} is rejected for a sibling as well. It publishes at runtime scope like {@code implementation}
 * but additionally drops the module off this module's own compile classpath, which no module here wants and which
 * would fail at compile time rather than reaching a consumer — its presence would mean something else went wrong.
 *
 * <h2>What is out of scope</h2>
 *
 * <p>
 * {@code aimon-bom} declares {@code api(project(...))} inside a {@code constraints} block; that publishes into
 * {@code <dependencyManagement>} and never lands on any consumer's compile classpath, so a {@code java-platform} is
 * outside both the rule and its exception. The declaration there is written {@code publishedProjects().forEach
 * { api(it) }} and carries no literal {@code project(":aimon-…")}, so it does not match and needs no special case —
 * if that ever changes, add one rather than widening {@link #FACADE_MODULES}.
 *
 * <p>
 * Two of the three testkits — {@code aimon-filesystem-testkit} and {@code aimon-session-testkit} — use {@code api}
 * and are absent from the facade list for a different reason again: neither is published, so this scan never reaches
 * them. Their build scripts say so themselves. {@code aimon-memory-testkit} is published and therefore <em>is</em>
 * scanned, and it is on the facade list: its deliverable is a base class whose extension point is
 * {@code PeerMemory newBackend()}, so a subclass in another repository cannot write the override without aimon-core
 * on its compile classpath.
 *
 * <p>
 * Build scripts rather than bytecode is why this is plain JUnit and not ArchUnit — a dependency declaration never
 * becomes a compiled class. It is modelled on {@link PublishedModuleLoggingBindingTest}, which guards a neighbouring
 * property of the same POMs, and scans every module rather than the ones that were once wrong: the regression it
 * guards against is a new module, or an old one being "tidied".
 */
@DisplayName("published module dependency scope")
class PublishedModuleApiScopeTest {

    /** Marks a module as published to Maven Central, hence subject to the rule. */
    private static final String PUBLISHABLE_PLUGIN = "id(\"aimon.publishable\")";

    /**
     * Modules whose deliverable is a re-exported API surface, and which therefore declare siblings on {@code api}.
     *
     * <p>
     * {@code aimon-bootstrap} needs two such lines — {@code :aimon-core} and {@code :aimon-session-routing} —
     * because {@code AimonStack} returns types from both, and {@code aimon-session-routing} keeps core on
     * {@code implementation} so it does not re-export core on its own. A facade re-exports every module whose types
     * appear in its own signatures, not just core. The sandbox pair re-export their SPI the same way while keeping
     * {@code implementation} for their own libraries.
     *
     * <p>
     * {@code aimon-memory-testkit} is the one entry here that is not an assembly. It is a contract suite, and what it
     * publishes is a base class a backend author extends: the abstract {@code PeerMemory newBackend()} an
     * out-of-repository backend has to override is an aimon-core type, so core belongs on that author's compile
     * classpath by the same argument the facades use. The other two testkits are absent because they are not
     * published at all, not because they scope differently.
     */
    private static final List<String> FACADE_MODULES = List.of("aimon-bootstrap", "aimon-spring-boot-starter",
            "aimon-sandbox-docker", "aimon-sandbox-kubernetes", "aimon-memory-testkit");

    /** Never reaches the published POM, so it cannot describe it wrongly. Open to every module. */
    private static final List<String> BUILD_INTERNAL_CONFIGURATIONS = List.of("compileOnly", "testImplementation",
            "testRuntimeOnly", "testCompileOnly", "testFixturesApi", "testFixturesImplementation");

    /** The configuration a non-facade published module declares a sibling on. */
    private static final String IMPLEMENTATION = "implementation";

    /** The configuration only a facade may declare a sibling on. */
    private static final String API = "api";

    /** Any {@code someConfiguration(project(":aimon-x"))} declaration, capturing the configuration and the target. */
    private static final Pattern PROJECT_DEPENDENCY = Pattern
            .compile("(?:\"?)([A-Za-z]+)(?:\"?)\\(\\s*project\\(\\s*\"(:[A-Za-z0-9-]+)\"\\s*\\)");

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    @DisplayName("only facade modules declare a sibling on api")
    void onlyFacadesDeclareSiblingsOnApi() throws IOException {
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

        // And without this it would pass on a repository where no module declares a sibling at all, which is not the
        // repository this rule is about.
        int declarationsSeen = 0;
        for (final Path buildScript : publishedBuildScripts) {
            declarationsSeen += assertSiblingScopesFollowTheRule(buildScript);
        }
        assertThat(declarationsSeen).withFailMessage(
                "found no `project(\":aimon-…\")` declarations in any published module — the pattern stopped "
                        + "matching, so this test is passing without checking anything")
                .isGreaterThan(10);
    }

    @Test
    @DisplayName("every named facade exists and actually declares a sibling on api")
    void facadeListIsAccurate() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        // An allowlist entry that no longer matches a real module is worse than no entry: it silently exempts a name
        // nobody will notice is gone, and the next module to take that name inherits the exemption.
        for (final String facade : FACADE_MODULES) {
            final Path buildScript = REPOSITORY_ROOT.resolve("modules").resolve(facade).resolve("build.gradle.kts");
            assertThat(buildScript)
                    .withFailMessage("FACADE_MODULES names %s, which has no build script — remove it or fix the name",
                            facade)
                    .isRegularFile();
            assertThat(Files.readString(buildScript)).withFailMessage(
                    "FACADE_MODULES names %s, but it declares no sibling on `api`. If it stopped being a facade, "
                            + "remove it from the list — an exemption nothing uses is an exemption nobody audits.",
                    facade).contains(API + "(project(\"");
        }
    }

    @Test
    @DisplayName("the classifier accepts each configuration for the right kind of module and no other")
    void classifierAcceptsTheRightScopePerModuleKind() {
        // The rule above passes as written, so a hole in the classifier stays invisible until the day it lets a real
        // mis-scoped dependency through. Both directions are pinned here, for both kinds of module.
        final String facade = "aimon-bootstrap";
        final String plain = "aimon-session-redis";

        assertThat(violationIn(facade, "api(project(\":aimon-core\"))")).isNull();
        assertThat(violationIn(facade, "implementation(project(\":aimon-core\"))")).isNull();
        assertThat(violationIn(plain, "implementation(project(\":aimon-core\"))")).isNull();
        assertThat(violationIn(plain, "compileOnly(project(\":aimon-llm-openai\"))")).isNull();
        assertThat(violationIn(plain, "testImplementation(project(\":aimon-session-routing\"))")).isNull();
        assertThat(violationIn(plain, "api(project( \":aimon-core\" ))")).isNotNull();

        assertThat(violationIn(plain, "api(project(\":aimon-core\"))"))
                .withFailMessage("a non-facade declaring a sibling on api must be reported").isNotNull();
        assertThat(violationIn(plain, "runtimeOnly(project(\":aimon-core\"))"))
                .withFailMessage("runtimeOnly on a sibling must be reported").isNotNull();
        assertThat(violationIn(facade, "runtimeOnly(project(\":aimon-core\"))"))
                .withFailMessage("runtimeOnly is wrong for a facade too").isNotNull();
    }

    /**
     * Checks one build script and returns how many sibling declarations it contained, so the caller can tell "no
     * violations" from "nothing was looked at".
     */
    private static int assertSiblingScopesFollowTheRule(Path buildScript) throws IOException {
        final List<String> lines = Files.readAllLines(buildScript);
        final String module = moduleNameOf(buildScript);
        int seen = 0;
        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index).strip();
            // A comment may legitimately name a declaration — this rule is quoted in prose in several of these
            // scripts, including the testkit's, which is where it was first written down.
            if (isComment(line)) {
                continue;
            }
            final String declaration = stripTrailingComment(line).strip();
            final Matcher matcher = PROJECT_DEPENDENCY.matcher(declaration);
            if (!matcher.find()) {
                continue;
            }
            seen++;
            assertThat(violationIn(module, declaration)).withFailMessage(
                    "%s:%d declares sibling %s on `%s`:%n  %s%n"
                            + "Only a facade — a module whose deliverable is the re-exported surface — declares a "
                            + "sibling on `api` (%s). Everything else uses `implementation`, which keeps core types "
                            + "off transitive consumers; see .claude/rules/code-style.md. If this module really has "
                            + "become a facade, add it to FACADE_MODULES here and say in its build script what it "
                            + "re-exports and why.",
                    describe(buildScript), index + 1, matcher.group(2), matcher.group(1), line,
                    String.join(", ", FACADE_MODULES)).isNull();
        }
        return seen;
    }

    /**
     * The offending configuration name, or {@code null} when the declaration is right for this kind of module.
     * Returning the name rather than a boolean is what lets the failure message say which configuration was used.
     */
    private static String violationIn(String module, String declaration) {
        final Matcher matcher = PROJECT_DEPENDENCY.matcher(declaration);
        if (!matcher.find()) {
            return null;
        }
        final String configuration = matcher.group(1);
        if (BUILD_INTERNAL_CONFIGURATIONS.contains(configuration) || IMPLEMENTATION.equals(configuration)) {
            return null;
        }
        if (API.equals(configuration) && FACADE_MODULES.contains(module)) {
            return null;
        }
        return configuration;
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

    private static boolean isComment(String declaration) {
        return declaration.startsWith("//") || declaration.startsWith("/*") || declaration.startsWith("*");
    }

    private static String stripTrailingComment(String line) {
        final int marker = line.indexOf("//");
        return marker < 0 ? line : line.substring(0, marker);
    }

    private static String moduleNameOf(Path buildScript) {
        return buildScript.getParent().getFileName().toString();
    }

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
