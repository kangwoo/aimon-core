package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * Deterministic-name synthesis tests for the inline resolver. Names must be stable across resolves (and
 * across JVMs) so shared/persistent resume caches replay without spurious misses. Also the EE-42 attribute rules:
 * a registered {@code agentType}'s attributes, overlaid by the descriptor's own.
 */
@DisplayName("InlineSubagentResolver — deterministic, cross-JVM-stable names")
class SubagentResolverTest {

    private final SubagentResolver resolver = SubagentResolver.inline();

    @Test
    @DisplayName("same agentType yields the same name")
    void agentTypeNameIsDeterministic() {
        final Subagent first = resolver.resolve(type("bug-reviewer").build());
        final Subagent second = resolver.resolve(type("bug-reviewer").build());
        assertThat(first.getName()).isEqualTo("graaljs:bug-reviewer").isEqualTo(second.getName());
    }

    @Test
    @DisplayName("systemPrompt-only descriptors get a stable hash-derived name")
    void promptOnlyNameIsStableHash() {
        final Subagent first = resolver.resolve(SubagentDescriptor.builder().systemPrompt("You review diffs.").build());
        final Subagent second = resolver
                .resolve(SubagentDescriptor.builder().systemPrompt("You review diffs.").build());
        assertThat(first.getName()).startsWith("graaljs:").isEqualTo(second.getName());
        final Subagent different = resolver
                .resolve(SubagentDescriptor.builder().systemPrompt("You write tests.").build());
        assertThat(different.getName()).isNotEqualTo(first.getName());
    }

    @Test
    @DisplayName("explicit fields are applied; agentType supplies a synthesized system prompt")
    void appliesExplicitFields() {
        final Subagent subagent = resolver
                .resolve(type("planner").model("gpt-4").tools(List.of("Read", "Grep")).maxIterations(5).build());
        assertThat(subagent.getName()).isEqualTo("graaljs:planner");
        assertThat(subagent.getContent().getSystemPrompt()).isEqualTo("You are the \"planner\" subagent.");
        assertThat(subagent.getMetadata().getModel()).isEqualTo("gpt-4");
        assertThat(subagent.getMetadata().getMaxIterations()).isEqualTo(5);
    }

    @Test
    @DisplayName("a descriptor with neither agentType nor systemPrompt is rejected loudly")
    void rejectsEmptyDescriptor() {
        assertThatThrownBy(() -> resolver.resolve(SubagentDescriptor.builder().agentType("  ").build()))
                .isInstanceOf(JsScriptException.class).hasMessageContaining("agentType");
    }

    @Nested
    @DisplayName("attributes (EE-42)")
    class Attributes {

        private final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        private final SubagentResolver withRegistry = SubagentResolver.inline(registry);

        Attributes() {
            final Map<String, String> builder = new LinkedHashMap<>();
            builder.put("sandbox.slot", "build");
            builder.put("sandbox.profile", "rw");
            registry.register(registered("builder", builder));
        }

        @Test
        @DisplayName("unregistered agentType, no explicit attributes: none")
        void unregisteredWithoutExplicit() {
            assertThat(withRegistry.resolve(type("reviewer").build()).getMetadata().getAttributes()).isEmpty();
        }

        @Test
        @DisplayName("unregistered agentType (or none), explicit attributes: the explicit ones")
        void unregisteredWithExplicit() {
            assertThat(withRegistry.resolve(type("reviewer").attributes(Map.of("sandbox.slot", "ro")).build())
                    .getMetadata().getAttributes()).containsExactly(Map.entry("sandbox.slot", "ro"));
            assertThat(withRegistry
                    .resolve(SubagentDescriptor.builder().systemPrompt("p").attributes(Map.of("gpu", "true")).build())
                    .getMetadata().getAttributes()).containsExactly(Map.entry("gpu", "true"));
        }

        @Test
        @DisplayName("registered agentType, no explicit attributes: the registered ones")
        void registeredWithoutExplicit() {
            final Subagent subagent = withRegistry.resolve(type("builder").build());

            assertThat(subagent.getMetadata().getAttributes()).containsExactly(Map.entry("sandbox.slot", "build"),
                    Map.entry("sandbox.profile", "rw"));
            // Only attributes are copied: name and prompt keep the inline rules.
            assertThat(subagent.getName()).isEqualTo("graaljs:builder");
            assertThat(subagent.getContent().getSystemPrompt()).isEqualTo("You are the \"builder\" subagent.");
        }

        @Test
        @DisplayName("registered agentType, explicit attributes: registered overlaid, explicit wins per key")
        void registeredOverlaidByExplicit() {
            final Subagent subagent = withRegistry
                    .resolve(type("builder").attributes(Map.of("sandbox.slot", "test", "gpu", "true")).build());

            assertThat(subagent.getMetadata().getAttributes()).containsExactly(Map.entry("sandbox.slot", "test"),
                    Map.entry("sandbox.profile", "rw"), Map.entry("gpu", "true"));
        }

        @Test
        @DisplayName("inline() without a registry never looks one up")
        void inlineIgnoresRegistry() {
            assertThat(SubagentResolver.inline().resolve(type("builder").build()).getMetadata().getAttributes())
                    .isEmpty();
        }

        @Test
        @DisplayName("an explicit key that clashes as value/group with a registered one is a JsScriptException")
        void clashAcrossIsRejected() {
            assertThatThrownBy(() -> withRegistry.resolve(type("builder").attributes(Map.of("sandbox", "x")).build()))
                    .isInstanceOf(JsScriptException.class).hasMessageContaining("agent 'builder'")
                    .hasMessageContaining("'sandbox' is both a value and a group")
                    .hasMessageContaining("between the base and override");
        }

        @Test
        @DisplayName("a registered definition that clashes on its own is reported as such")
        void clashWithinRegisteredIsReported() {
            final Map<String, String> broken = new LinkedHashMap<>();
            broken.put("sandbox", "x");
            broken.put("sandbox.slot", "y");
            registry.register(registered("broken", broken));

            assertThatThrownBy(() -> withRegistry.resolve(type("broken").build())).isInstanceOf(JsScriptException.class)
                    .hasMessageContaining("within the base attributes");
        }

        @Test
        @DisplayName("a registry that throws fails the step loudly, naming the agentType")
        void failingRegistryIsLoud() {
            final SubagentRegistry failing = mock(SubagentRegistry.class);
            when(failing.getSubagent(anyString())).thenThrow(new IllegalStateException("down"));

            assertThatThrownBy(() -> SubagentResolver.inline(failing).resolve(type("builder").build()))
                    .isInstanceOf(JsScriptException.class).hasMessageContaining("agent 'builder'")
                    .hasMessageContaining("down");
        }
    }

    private static SubagentDescriptor.Builder type(String agentType) {
        return SubagentDescriptor.builder().agentType(agentType);
    }

    private static Subagent registered(String name, Map<String, String> attributes) {
        return Subagent.of(name, SubagentMetadata.builder().description("d").attributes(attributes).build(),
                SubagentContent.of("registered prompt, not used by workflow steps"));
    }
}
