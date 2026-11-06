package at.aimon.core.tools.code.shell;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.programmatic.testkit.AbstractCodeExecutionEngineContractTest;
import at.aimon.programmatic.testkit.EnginePrograms;
import at.aimon.programmatic.testkit.PythonPrograms;

/** The engine contract suite on the shell engine, over a local shell (shell doc §14). */
@DisplayName("ShellCodeExecutionEngine — engine contract suite on LocalShell")
class ShellCodeEngineContractTest extends AbstractCodeExecutionEngineContractTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void requirePython() {
        assumeTrue(Python3.available(), "python3 3.8+ is not installed");
    }

    @Override
    protected CodeExecutionEngine engine() {
        return ShellCodeExecutionEngine.create();
    }

    @Override
    protected EnginePrograms programs() {
        return new PythonPrograms();
    }

    @Override
    protected ToolContext toolContext() {
        try {
            return ToolContext.builder()
                    .put(ToolContextKeys.EXECUTION_ENVIRONMENT, new SandboxedLocalEnvironment(tempDir.toRealPath()))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
