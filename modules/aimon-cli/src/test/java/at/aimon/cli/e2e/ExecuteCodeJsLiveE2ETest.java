package at.aimon.cli.e2e;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.cli.e2e.ExecuteCodeLiveFixtures.Variant;
import at.aimon.graaljs.GraalJsEngineHolder;
import at.aimon.graaljs.code.GraalJsCodeExecutionEngine;

/**
 * Real-model E2E, JavaScript engine: a real model must write a GraalJS program that calls both host tools, and only
 * what the program printed may come back. Billed and nondeterministic, so it runs only under {@code :aimon-cli:llmTest}
 * (which sets {@code aimon.llmTest}) and only for a provider whose key is set — never in {@code test}, {@code checkAll}
 * or CI. See CONTRIBUTING.md, "Real-model ExecuteCode E2E".
 */
@EnabledIfSystemProperty(named = "aimon.llmTest", matches = "true")
@DisplayName("ExecuteCode real-model E2E — JavaScript")
class ExecuteCodeJsLiveE2ETest {

    private static GraalJsEngineHolder holder;

    @BeforeAll
    static void createEngine() {
        holder = GraalJsEngineHolder.create();
    }

    @AfterAll
    static void closeEngine() {
        // After every stack: the caller owns engines it hands the stack.
        if (holder != null) {
            holder.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {LiveModels.OPENAI, LiveModels.ANTHROPIC})
    void modelWritesCodeThatCallsBothTools(String provider, @TempDir Path workspace) throws Exception {
        ExecuteCodeLiveFixtures.runLive(provider, Variant.JAVASCRIPT, new GraalJsCodeExecutionEngine(holder), workspace,
                null);
    }
}
