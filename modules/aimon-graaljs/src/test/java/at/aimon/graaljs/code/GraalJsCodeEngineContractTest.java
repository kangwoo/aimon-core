package at.aimon.graaljs.code;

import org.junit.jupiter.api.DisplayName;

import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.programmatic.testkit.AbstractCodeExecutionEngineContractTest;
import at.aimon.programmatic.testkit.EnginePrograms;
import at.aimon.programmatic.testkit.JavaScriptPrograms;

@DisplayName("GraalJsCodeExecutionEngine — engine contract suite")
class GraalJsCodeEngineContractTest extends AbstractCodeExecutionEngineContractTest {

    @Override
    protected CodeExecutionEngine engine() {
        return GraalJsCodeExecutionEngine.create();
    }

    @Override
    protected EnginePrograms programs() {
        return new JavaScriptPrograms();
    }
}
