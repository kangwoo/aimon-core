package at.aimon.core.tools.code.shell;

import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionEngineFactory;

/**
 * The {@code ServiceLoader} entry of the shell engine ({@code docs/design/tool/programmatic-tool-calling.md} §5.2):
 * assembly that discovers engines ({@code aimon-bootstrap} with {@code programmaticEnabled}, the Spring Boot starter)
 * finds the Python engine here, next to the JavaScript one {@code aimon-graaljs} registers. Being in {@code aimon-core}
 * it is always on the class path; it is statically unavailable outside a sandboxed environment, so on a host-isolated
 * one it changes nothing.
 */
public final class ShellCodeEngineFactory implements CodeExecutionEngineFactory {

    @Override
    public String language() {
        return CodeExecutionEngine.PYTHON;
    }

    @Override
    public CodeExecutionEngine create() {
        return ShellCodeExecutionEngine.create();
    }
}
