package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.workflow.AgentTask;

/**
 * Marshalling unit tests: a JS descriptor with a nested schema marshals to an {@link AgentTask} that
 * holds <b>zero</b> {@code org.graalvm} references (recursive deep-detach) and correct isolation semantics.
 *
 * <p>
 * Uses a raw locked-down {@code Context} directly (no runner) to construct guest {@code Value}s.
 */
@DisplayName("AgentTaskMarshaller — recursive deep-detach + isolation")
class MarshallingUnitTest {

    private GraalJsEngineHolder engines;
    private Context context;

    @BeforeEach
    void setUp() {
        engines = GraalJsEngineHolder.create();
        context = JsContextFactory.create(engines.engine(), JsSandboxConfig.defaults());
    }

    @AfterEach
    void tearDown() {
        context.close(true);
        engines.close();
    }

    @Test
    @DisplayName("nested schema is deep-detached to plain Java (no org.graalvm types)")
    void nestedSchemaDeepDetached() {
        final Value descriptor = context.eval("js",
                "({ agentType: 'a', goal: 'g',"
                        + " schema: { type: 'object', properties: { x: { type: 'string' }, n: { type: 'number' } },"
                        + " required: ['x'] } })");

        final AgentTask task = AgentTaskMarshaller.toTask(descriptor, SubagentResolver.inline());

        assertThat(task.getGoal()).isEqualTo("g");
        assertThat(task.getSubagent().getName()).isEqualTo("graaljs:a");
        assertThat(task.getResultSchema()).isPresent();
        final Map<String, Object> schema = task.getResultSchema().orElseThrow();
        assertThat(schema).containsEntry("type", "object").containsKey("properties");
        assertNoPolyglotTypes(schema);
    }

    @Test
    @DisplayName("isolation:'worktree' sets isolate (and hence nonCacheable)")
    void worktreeIsolationSetsIsolate() {
        final Value descriptor = context.eval("js", "({ agentType: 'a', goal: 'g', isolation: 'worktree' })");
        final AgentTask task = AgentTaskMarshaller.toTask(descriptor, SubagentResolver.inline());
        assertThat(task.isIsolate()).isTrue();
        assertThat(task.isNonCacheable()).isTrue();
    }

    @Test
    @DisplayName("string-form agent('goal', opts) marshals goal + opts")
    void stringFormMarshals() {
        final Value opts = context.eval("js", "({ agentType: 'w', label: 'L' })");
        final AgentTask task = AgentTaskMarshaller.toTask("do it", opts, SubagentResolver.inline());
        assertThat(task.getGoal()).isEqualTo("do it");
        assertThat(task.getLabel()).isEqualTo("L");
        assertThat(task.getSubagent().getName()).isEqualTo("graaljs:w");
    }

    private static void assertNoPolyglotTypes(Object value) {
        if (value == null) {
            return;
        }
        assertThat(value.getClass().getName()).doesNotStartWith("org.graalvm");
        if (value instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                assertThat(entry.getKey()).isInstanceOf(String.class);
                assertNoPolyglotTypes(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (final Object element : list) {
                assertNoPolyglotTypes(element);
            }
        }
    }
}
