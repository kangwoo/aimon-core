package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fan-out marshalling tests over the real engine: deep-detach parallel with nested schemas, null-isolation
 * and barrier-per-stage pipeline.
 */
@DisplayName("WorkflowBindings — marshal-before-fan-out")
class WorkflowBindingsFanoutTest extends AbstractGraalJsRunTest {

    @Test
    @DisplayName("Parallel with nested per-descriptor schemas yields non-null structured results, in order")
    void parallelWithNestedSchemasDeepDetaches() {
        // Every worker reads the (deep-detached) schema via augmentGoal on its own thread. A shallow live view would
        // throw a multithread PolyglotException there and the dispatcher would silently null the slot — so non-null
        // structured results are the positive proof of correct deep-detach.
        behavior = (subagent, goal) -> "{\"x\":\"ok\"}";
        final String out = run("const found = await parallel([\n"
                + "  { agentType: 'a', goal: 'g0', schema: { type:'object', properties:{ x:{ type:'string' } },"
                + " required:['x'] } },\n"
                + "  { agentType: 'b', goal: 'g1', schema: { type:'object', properties:{ x:{ type:'string' } },"
                + " required:['x'] } },\n" + "]);\n"
                + "return found.length + '|' + found[0].structured.x + '|' + found[1].structured.x;");
        assertThat(out).isEqualTo("2|ok|ok");
    }

    @Test
    @DisplayName("A failing descriptor becomes a null slot; the batch is not aborted")
    void oneFailingDescriptorIsNullIsolated() {
        behavior = (subagent, goal) -> {
            if (goal.contains("boom")) {
                throw new RuntimeException("kaboom");
            }
            return "ans:" + goal;
        };
        final String out = run("const r = await parallel([\n" + "  { agentType: 'a', goal: 'ok0' },\n"
                + "  { agentType: 'a', goal: 'boom' },\n" + "  { agentType: 'a', goal: 'ok2' },\n" + "]);\n"
                + "return (r[0] ? r[0].text : 'null') + '|' + (r[1] === null ? 'null' : r[1].text)"
                + " + '|' + (r[2] ? r[2].text : 'null');");
        assertThat(out).isEqualTo("ans:ok0|null|ans:ok2");
    }

    @Test
    @DisplayName("Pipeline runs a barrier per stage, threading each item's prior result, in order")
    void pipelineBarrierPerStage() {
        final String out = run("const out = await pipeline([1, 2],\n"
                + "  (prev, item, i) => ({ agentType: 's1', goal: 'stage1:' + item }),\n"
                + "  (prev, item, i) => ({ agentType: 's2', goal: 'stage2:' + prev.text })\n" + ");\n"
                + "return out.map(r => r.text).join(',');");
        assertThat(out).isEqualTo("ans:stage2:ans:stage1:1,ans:stage2:ans:stage1:2");
    }

    @Test
    @DisplayName("args is a read-only snapshot the script can read")
    void argsAreReadable() {
        final String out = run("return 'hi ' + args.name;", java.util.Map.of("name", "world"),
                JsSandboxConfig.defaults());
        assertThat(out).isEqualTo("hi world");
    }

    @Test
    @DisplayName("args is deep read-only: top-level, nested-object, and array mutations are all rejected")
    void argsAreDeepReadOnly() {
        final String js = "let r = [];\n"
                + "try { args.name = 'x'; r.push('top:MUTATED'); } catch (e) { r.push('top:blocked'); }\n"
                + "try { args.nested.x = 9; r.push('nested:MUTATED'); } catch (e) { r.push('nested:'"
                + " + args.nested.x); }\n"
                + "try { args.arr[0] = 9; r.push('arr:MUTATED'); } catch (e) { r.push('arr:' + args.arr[0]); }\n"
                + "return r.join('|');";
        final String out = run(js,
                java.util.Map.of("name", "world", "nested", java.util.Map.of("x", 1), "arr", java.util.List.of(7, 8)),
                JsSandboxConfig.defaults());
        assertThat(out).isEqualTo("top:blocked|nested:1|arr:7");
    }
}
