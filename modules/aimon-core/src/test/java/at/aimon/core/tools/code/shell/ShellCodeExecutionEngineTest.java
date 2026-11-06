package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.shell.ShellFeature;

@DisplayName("ShellCodeExecutionEngine — grade, static availability, probe")
class ShellCodeExecutionEngineTest {

    @TempDir
    Path tempDir;

    private final ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create();

    @AfterEach
    void close() {
        engine.close();
    }

    @Test
    @DisplayName("runs Python, graded as Bash and approved under Bash's key, bounded by the tool scope")
    void grade() {
        assertThat(engine.language()).isEqualTo(CodeExecutionEngine.PYTHON);
        assertThat(engine.intrinsicGrade().getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(engine.intrinsicGrade().getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        assertThat(engine.intrinsicGrade().getDataReach()).isEqualTo(DataReach.ENVIRONMENT);
        assertThat(engine.intrinsicGrade().isApprovalDelegated()).isFalse();
        assertThat(engine.intrinsicGrade().getApprovalKey()).hasValue("Bash");
        assertThat(engine.isToolScopeBound()).isTrue();
    }

    @Test
    @DisplayName("statically available only with every fact of shell doc §4")
    void staticAvailability() {
        assertThat(engine.staticAvailability(complete().build()).isAvailable()).isTrue();
        assertUnavailable(OfferFacts.none(), "not sandboxed");
        assertUnavailable(complete().descriptor(descriptor(EnvironmentIsolation.HOST, true)).build(), "not sandboxed");
        assertUnavailable(complete().descriptor(descriptor(null, true)).build(), "undeclared");
        assertUnavailable(
                complete().shellFeatures(EnumSet.of(ShellFeature.STREAMING, ShellFeature.RUN_DIRECTORY)).build(),
                "ISOLATED_EXECUTION");
        assertUnavailable(complete().runtimeFileSystem(false).build(), "runtime file system");
        assertUnavailable(complete().descriptor(descriptor(EnvironmentIsolation.SANDBOXED, false)).build(), "python3");
        assertUnavailable(complete().bashUnconstrained(false).build(), "Bash");
    }

    @Test
    @DisplayName("the probe finds python3 and a runtime-file-system file from the shell, is cached, and sweeps nothing")
    void probe() throws Exception {
        assumeTrue(Python3.available(), "python3 3.8+ is not installed");
        final Path root = tempDir.toRealPath();
        final Path stale = Files.createDirectories(root.resolve(ShellCodeExecutionEngine.PTC_DIRECTORY).resolve("old"));
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(3600)));
        final SandboxedLocalEnvironment environment = new SandboxedLocalEnvironment(root);
        final EngineAvailability first = engine.probe(environment);
        assertThat(first.isAvailable()).as(first.toString()).isTrue();
        assertThat(engine.probe(environment)).isSameAs(first);
        // Runs sweep, the probe does not (§8; ShellRuntimeSweepTest).
        assertThat(stale).exists();
        Files.delete(stale);
        try (var left = Files.list(root.resolve(ShellCodeExecutionEngine.PTC_DIRECTORY))) {
            assertThat(left).as("the probe file is removed").isEmpty();
        }
    }

    @Test
    @DisplayName("no environment, or one without a runtime file system, probes unavailable")
    void probeWithoutEnvironment() {
        assertThat(engine.probe(null).isAvailable()).isFalse();
    }

    private void assertUnavailable(OfferFacts facts, String reason) {
        final EngineAvailability availability = engine.staticAvailability(facts);
        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.getReason()).hasValueSatisfying(text -> assertThat(text).contains(reason));
    }

    private static OfferFacts.Builder complete() {
        return OfferFacts.builder().descriptor(descriptor(EnvironmentIsolation.SANDBOXED, true))
                .shellFeatures(
                        Set.of(ShellFeature.STREAMING, ShellFeature.RUN_DIRECTORY, ShellFeature.ISOLATED_EXECUTION))
                .runtimeFileSystem(true).bashUnconstrained(true);
    }

    private static EnvironmentDescriptor descriptor(EnvironmentIsolation isolation, boolean python) {
        final EnvironmentDescriptor.Builder builder = EnvironmentDescriptor.builder().workingDirectory("/workspace")
                .isolation(isolation);
        if (python) {
            builder.runtime(EnvironmentDescriptor.RUNTIME_PYTHON3);
        }
        return builder.build();
    }
}
