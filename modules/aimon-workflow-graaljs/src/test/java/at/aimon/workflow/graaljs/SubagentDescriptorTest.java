package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SubagentDescriptor — normalized, validated, immutable")
class SubagentDescriptorTest {

    @Test
    @DisplayName("an empty builder gives absent fields, no tools and no attributes")
    void emptyBuilder() {
        final SubagentDescriptor descriptor = SubagentDescriptor.builder().build();

        assertThat(descriptor.agentType()).isEmpty();
        assertThat(descriptor.systemPrompt()).isEmpty();
        assertThat(descriptor.model()).isEmpty();
        assertThat(descriptor.maxIterations()).isEmpty();
        assertThat(descriptor.tools()).isEmpty();
        assertThat(descriptor.attributes()).isEmpty();
    }

    @Test
    @DisplayName("blank texts are absent, so resolvers need not re-check them")
    void blankTextIsAbsent() {
        final SubagentDescriptor descriptor = SubagentDescriptor.builder().agentType(" ").systemPrompt("").model("\t")
                .build();

        assertThat(descriptor.agentType()).isEmpty();
        assertThat(descriptor.systemPrompt()).isEmpty();
        assertThat(descriptor.model()).isEmpty();
    }

    @Test
    @DisplayName("tools are copied defensively; null means unrestricted")
    void toolsAreCopied() {
        final List<String> tools = new ArrayList<>(List.of("Read"));
        final SubagentDescriptor descriptor = SubagentDescriptor.builder().tools(tools).build();
        tools.add("Bash");

        assertThat(descriptor.tools()).containsExactly("Read");
        assertThatThrownBy(() -> descriptor.tools().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(SubagentDescriptor.builder().tools(null).build().tools()).isEmpty();
    }

    @Test
    @DisplayName("attributes are validated like any definition's")
    void attributesAreValidated() {
        assertThatThrownBy(() -> SubagentDescriptor.builder().attributes(Map.of(" ", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubagentDescriptor.builder().attributes(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("equal fields make equal descriptors")
    void equality() {
        final SubagentDescriptor a = SubagentDescriptor.builder().agentType("t").tools(List.of("Read")).maxIterations(3)
                .attributes(Map.of("sandbox.slot", "b")).build();
        final SubagentDescriptor b = SubagentDescriptor.builder().agentType("t").tools(List.of("Read")).maxIterations(3)
                .attributes(Map.of("sandbox.slot", "b")).build();
        final SubagentDescriptor c = SubagentDescriptor.builder().agentType("t").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThat(a.toString()).contains("agentType=t", "sandbox.slot=b");
    }
}
