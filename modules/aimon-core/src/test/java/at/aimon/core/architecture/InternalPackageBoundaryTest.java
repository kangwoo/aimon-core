package at.aimon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An {@code internal} package is used only from inside the package tree it belongs to.
 *
 * <p>
 * {@code docs/project/api-stability.md} §2 puts {@code <package>.internal} beside {@code *.impl}: not public API,
 * free to change without notice. For {@code *.impl} that boundary is enforced by
 * {@link PackageDependencyArchitectureTest}; this is the same promise for {@code internal}, which the policy also says
 * the build enforces. {@code at.aimon.session.mongodb.internal.ListenDispatcher} may be imported by anything under
 * {@code at.aimon.session.mongodb}, and by nothing else.
 *
 * <h2>Why a source scan and not ArchUnit</h2>
 *
 * <p>
 * Four of the five {@code internal} packages live outside {@code aimon-core}, and this module's test classpath does
 * not see them — the dependency points the other way. Reading {@code import} lines from every module's
 * {@code src/main/java} covers all of them from one place. A fully qualified name written in code without an import
 * would slip past; none exists today, and the javadoc mentions that do exist ({@code InboundMessageCodec} names its
 * Redis twin) are not dependencies.
 *
 * <p>
 * The sources are deliberately not declared as inputs of this module's {@code test} task, for the reason
 * {@code ReleaseGateMatchesCiGateTest} gives for its tag scan: declaring every main source tree would re-run most of
 * the build's tests after an edit in any module. CI always runs it from clean.
 *
 * <h2>Exceptions</h2>
 *
 * <p>
 * {@link #ALLOWED} lists the cross-tree imports that are accepted, each with its reason. Test sources are not scanned:
 * a module's own tests reach into its internals by design, and none of them ships.
 */
class InternalPackageBoundaryTest {

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern INTERNAL_IMPORT = Pattern
            .compile("^import\\s+(?:static\\s+)?(([\\w.]+?)\\.internal(?:\\.[\\w.*]+)?)\\s*;", Pattern.MULTILINE);

    /**
     * Accepted imports of another tree's internals, keyed by {@code <module>:<imported name>}.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "aimon-session-testkit:at.aimon.session.routing.internal.DefaultSessionRouter",
            "unpublished multi-node contract suite; switches on the real router's status broadcast to test it");

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    @DisplayName("No main source imports an internal package from outside the package tree that owns it")
    void internalPackagesStayInsideTheirTree() throws IOException {
        assumeTrue(REPOSITORY_ROOT != null, "repository root not found from the working directory — nothing to scan");

        List<String> violations = new ArrayList<>();
        List<String> usedExceptions = new ArrayList<>();
        int internalImports = 0;

        for (Path source : mainSources(REPOSITORY_ROOT.resolve("modules"))) {
            String text = Files.readString(source);
            Matcher pkg = PACKAGE.matcher(text);
            if (!pkg.find()) {
                continue;
            }
            String importer = pkg.group(1);
            String module = REPOSITORY_ROOT.resolve("modules").relativize(source).getName(0).toString();

            Matcher imp = INTERNAL_IMPORT.matcher(text);
            while (imp.find()) {
                internalImports++;
                String imported = imp.group(1);
                String owner = imp.group(2);
                if (importer.equals(owner) || importer.startsWith(owner + ".")) {
                    continue;
                }
                String key = module + ":" + imported;
                if (ALLOWED.containsKey(key)) {
                    usedExceptions.add(key);
                    continue;
                }
                violations.add(REPOSITORY_ROOT.relativize(source) + " (package " + importer + ") imports " + imported
                        + ", which is internal to " + owner);
            }
        }

        assertThat(internalImports).as("the scan saw no internal imports at all — it is not reading what it thinks")
                .isPositive();
        assertThat(violations).as("internal packages are not public API (api-stability.md §2); depend on the owning"
                + " module's public types instead, or add a reasoned entry to ALLOWED").isEmpty();
        assertThat(usedExceptions).as("every ALLOWED entry must still match an import — remove the ones that do not")
                .containsExactlyInAnyOrderElementsOf(ALLOWED.keySet());
    }

    private static List<Path> mainSources(Path modules) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> moduleDirs = Files.list(modules)) {
            for (Path module : moduleDirs.sorted().toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(result::add);
                }
            }
        }
        return result;
    }

    /**
     * Walks up from the working directory to the directory holding both {@code settings.gradle.kts} and
     * {@code modules/}. Returns {@code null} rather than throwing so the test can skip itself if it is ever run from
     * somewhere unexpected.
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
