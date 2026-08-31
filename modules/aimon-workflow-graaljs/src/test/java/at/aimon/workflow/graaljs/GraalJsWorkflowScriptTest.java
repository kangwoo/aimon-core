package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.workflow.WorkflowBudget;
import at.aimon.core.workflow.WorkflowRunnerOptions;
import at.aimon.core.workflow.exception.WorkflowBudgetExceededException;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * End-to-end tests for the {@link GraalJsWorkflowScript} seam over the real GraalJS engine.
 */
@DisplayName("GraalJsWorkflowScript — JS source runs on the core runner")
class GraalJsWorkflowScriptTest extends AbstractGraalJsRunTest {

    @Test
    @DisplayName("Thunk-wrap makes top-level return/await legal and yields a String")
    void simpleReturnYieldsString() {
        final String out = run("return agent({ agentType: 'a', goal: 'g' }).text;");
        assertThat(out).isEqualTo("ans:g");
    }

    @Test
    @DisplayName("Await resolves the synchronous agent value in one microtask")
    void awaitResolvesSynchronously() {
        final String out = run(
                "const r = await agent({ agentType: 'a', goal: 'hello' });\n" + "return r.isComplete + '|' + r.text;");
        assertThat(out).isEqualTo("true|ans:hello");
    }

    @Test
    @DisplayName("An object result is detached to JSON, valid after the Context closes")
    void objectResultDetachedToJson() {
        final String out = run("return { a: agent({ agentType: 'x', goal: 'g1' }).text, n: 2, ok: true };");
        assertThat(out).contains("\"a\":\"ans:g1\"").contains("\"n\":2").contains("\"ok\":true");
    }

    @Test
    @DisplayName("A scalar result is stringified")
    void scalarResultStringified() {
        assertThat(run("return 6 * 7;")).isEqualTo("42");
        assertThat(run("return 'plain';")).isEqualTo("plain");
    }

    @Test
    @DisplayName("empty/undefined result becomes an empty string")
    void undefinedResultBecomesEmpty() {
        assertThat(run("log('side effect only');")).isEmpty();
    }

    @Test
    @DisplayName("A run-fatal budget overrun propagates through the script, past guest try/catch")
    void budgetOverrunPropagatesPastGuestTryCatch() {
        final WorkflowRunnerOptions options = WorkflowRunnerOptions.builder().budget(WorkflowBudget.ofAgents(1))
                .build();
        // The guest swallows every error, yet the run-fatal budget exception must still abort the run.
        final String js = "try {\n" + "  agent({ agentType: 'a', goal: '1' });\n"
                + "  agent({ agentType: 'a', goal: '2' });\n" + "  return 'swallowed';\n"
                + "} catch (e) { return 'caught'; }";
        assertThatThrownBy(() -> run(js, options)).isInstanceOf(WorkflowBudgetExceededException.class);
    }

    @Test
    @DisplayName("a cyclic return value fails loudly instead of overflowing the stack")
    void cyclicReturnValueRejected() {
        assertThatThrownBy(() -> run("const o = {}; o.self = o; return o;")).isInstanceOf(JsScriptException.class)
                .hasMessageContaining("nesting depth");
    }

    @Test
    @DisplayName("a cyclic schema descriptor fails loudly instead of overflowing the stack")
    void cyclicSchemaRejected() {
        assertThatThrownBy(() -> run("const s = { type: 'object' }; s.self = s;\n"
                + "return agent({ agentType: 'a', goal: 'g', schema: s" + " }).text;"))
                .isInstanceOf(JsScriptException.class);
    }

    @Test
    @DisplayName("throw undefined / Promise.reject() fails the run instead of returning an empty success")
    void undefinedRejectionFailsRun() {
        assertThatThrownBy(() -> run("throw undefined;")).isInstanceOf(JsScriptException.class)
                .hasMessageContaining("rejected");
        assertThatThrownBy(() -> run("return Promise.reject();")).isInstanceOf(JsScriptException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    @DisplayName("STRICT determinism blocks Date, Math.random, and Intl.DateTimeFormat fail-closed")
    void strictDeterminismFailClosed() {
        final JsSandboxConfig strict = JsSandboxConfig.builder().determinismMode(JsSandboxConfig.DeterminismMode.STRICT)
                .build();
        final String js = "let r = [];\n"
                + "try { Date.now(); r.push('date:OPEN'); } catch (e) { r.push('date:blocked'); }\n"
                + "try { Math.random(); r.push('rand:OPEN'); } catch (e) { r.push('rand:blocked'); }\n"
                + "if (typeof Intl === 'undefined') { r.push('intl:absent'); }\n"
                + "else { try { new Intl.DateTimeFormat().format(); r.push('intl:OPEN'); }"
                + " catch (e) { r.push('intl:blocked'); } }\n" + "return r.join('|');";
        assertThat(run(js, Map.of(), strict)).doesNotContain("OPEN");
    }
}
