package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.workflow.AgentTask;
import at.aimon.workflow.graaljs.exception.JsScriptException;

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

    @Test
    @DisplayName("attributes: nested and dotted spellings are the same attribute; scalars become text")
    void attributesNestedAndDottedAreEquivalent() {
        // The resolver admits only the keys the operator allowed (EE-45); what is under test is the flattening.
        final SubagentResolver allowing = SubagentResolver.inline(new InMemorySubagentRegistry(),
                List.of("sandbox.slot", "sandbox.cpus", "gpu"));
        final AgentTask nested = AgentTaskMarshaller.toTask(context.eval("js",
                "({ agentType: 'a', goal: 'g', attributes: { sandbox: { slot: 'build', cpus: 4 }, gpu: false } })"),
                allowing);
        final AgentTask dotted = AgentTaskMarshaller.toTask(context.eval("js",
                "({ agentType: 'a', goal: 'g', attributes: { 'sandbox.slot': 'build', 'sandbox.cpus': 4.0, gpu: false } })"),
                allowing);

        assertThat(nested.getSubagent().getMetadata().getAttributes()).containsExactly(
                Map.entry("sandbox.slot", "build"), Map.entry("sandbox.cpus", "4"), Map.entry("gpu", "false"));
        assertThat(dotted.getSubagent().getMetadata().getAttributes())
                .isEqualTo(nested.getSubagent().getMetadata().getAttributes());
    }

    @Test
    @DisplayName("attributes: absent or null means none")
    void attributesAbsentOrNullAreEmpty() {
        assertThat(AgentTaskMarshaller
                .toTask(context.eval("js", "({ agentType: 'a', goal: 'g' })"), SubagentResolver.inline()).getSubagent()
                .getMetadata().getAttributes()).isEmpty();
        assertThat(AgentTaskMarshaller.toTask(context.eval("js", "({ agentType: 'a', goal: 'g', attributes: null })"),
                SubagentResolver.inline()).getSubagent().getMetadata().getAttributes()).isEmpty();
    }

    @Test
    @DisplayName("attributes: a non-object is rejected loudly")
    void attributesMustBeAnObject() {
        for (String js : new String[]{"'build'", "['a']", "42", "(() => 1)"}) {
            final Value descriptor = context.eval("js", "({ agentType: 'a', goal: 'g', attributes: " + js + " })");
            assertThatThrownBy(() -> AgentTaskMarshaller.toTask(descriptor, SubagentResolver.inline())).as(js)
                    .isInstanceOf(JsScriptException.class).hasMessageContaining("'attributes' must be an object");
        }
    }

    @Test
    @DisplayName("attributes: an entry a definition file could not hold is rejected with its key")
    void attributesEntryRulesMatchDefinitionFiles() {
        assertThatThrownBy(() -> AgentTaskMarshaller.toTask(
                context.eval("js", "({ agentType: 'a', goal: 'g', attributes: { slots: ['a', 'b'] } })"),
                SubagentResolver.inline())).isInstanceOf(JsScriptException.class)
                .hasMessageContaining("'slots' is a list");
        assertThatThrownBy(() -> AgentTaskMarshaller.toTask(
                context.eval("js", "({ agentType: 'a', goal: 'g', attributes: { slot: null } })"),
                SubagentResolver.inline())).isInstanceOf(JsScriptException.class)
                .hasMessageContaining("'slot' has no value");
        assertThatThrownBy(() -> AgentTaskMarshaller.toTask(
                context.eval("js",
                        "({ agentType: 'a', goal: 'g', attributes: { sandbox: 'x', 'sandbox.slot': 'y' } })"),
                SubagentResolver.inline())).isInstanceOf(JsScriptException.class)
                .hasMessageContaining("'sandbox' is both a value and a group");
    }

    @Test
    @DisplayName("attributes: a non-finite number (NaN, ±Infinity) is rejected with its key")
    void attributesRejectNonFiniteNumbers() {
        for (String js : new String[]{"NaN", "Infinity", "-Infinity", "0/0"}) {
            final Value descriptor = context.eval("js",
                    "({ agentType: 'a', goal: 'g', attributes: { sandbox: { cpus: " + js + " } } })");
            assertThatThrownBy(() -> AgentTaskMarshaller.toTask(descriptor, SubagentResolver.inline())).as(js)
                    .isInstanceOf(JsScriptException.class)
                    .hasMessageContaining("attribute 'sandbox.cpus' must be a finite number");
        }
        // A large but finite number is still an ordinary value.
        assertThat(AgentTaskMarshaller
                .toTask(context.eval("js", "({ agentType: 'a', goal: 'g', attributes: { big: 1.5e300 } })"),
                        SubagentResolver.inline(new InMemorySubagentRegistry(), List.of("big")))
                .getSubagent().getMetadata().getAttributes()).containsKey("big");
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
