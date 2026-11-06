package at.aimon.graaljs.code;

import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionEngineFactory;

/**
 * The {@code ServiceLoader} entry of the GraalJS engine: assembly that only sees {@code at.aimon.core.programmatic}
 * ({@code aimon-bootstrap}, the Spring Boot starter) finds the JavaScript engine through it when {@code aimon-graaljs}
 * is on the class path ({@code docs/design/tool/programmatic-tool-calling.md} §5.2).
 */
public final class GraalJsCodeEngineFactory implements CodeExecutionEngineFactory {

    /** Creates the factory; {@code ServiceLoader} needs a public no-argument constructor. */
    public GraalJsCodeEngineFactory() {
        // stateless
    }

    @Override
    public String language() {
        return CodeExecutionEngine.JAVASCRIPT;
    }

    /** Creates an engine that owns its own GraalJS engine holder, closed with it. */
    @Override
    public CodeExecutionEngine create() {
        return GraalJsCodeExecutionEngine.create();
    }
}
