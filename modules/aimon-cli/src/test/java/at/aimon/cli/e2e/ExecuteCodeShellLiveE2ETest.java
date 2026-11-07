package at.aimon.cli.e2e;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.cli.e2e.ExecuteCodeLiveFixtures.RunDirectoryRecorder;
import at.aimon.cli.e2e.ExecuteCodeLiveFixtures.Variant;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;

/**
 * Real-model E2E, Python shell engine on a local environment that declares itself sandboxed: the model's Python runs
 * on this host, which is acceptable only because the test is opt-in and local. Needs {@code python3} ≥ 3.8. After the
 * stack closes, every {@code aimon_ptc.py} run directory recorded during the run must have no process left.
 * Runs only under {@code :aimon-cli:llmTest}; see {@link ExecuteCodeJsLiveE2ETest} for the gating.
 */
@EnabledIfSystemProperty(named = "aimon.llmTest", matches = "true")
@DisplayName("ExecuteCode real-model E2E — Python (shell engine)")
class ExecuteCodeShellLiveE2ETest {

    private static ShellCodeExecutionEngine engine;

    @BeforeAll
    static void createEngine() {
        engine = ShellCodeExecutionEngine.create();
    }

    @AfterAll
    static void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {LiveModels.OPENAI, LiveModels.ANTHROPIC})
    void modelWritesCodeThatCallsBothTools(String provider, @TempDir Path workspace) throws Exception {
        assumeTrue(ExecuteCodeLiveFixtures.python3Available(), "python3 >= 3.8 is not on the path");
        ExecuteCodeLiveFixtures.runLive(provider, Variant.PYTHON, engine, workspace, new RunDirectoryRecorder());
    }
}
