package at.aimon.bootstrap.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.programmatic.CodeExecutionLimits;

@DisplayName("ToolSpec — programmatic tool calling switch and limits")
class ToolSpecProgrammaticTest {

    @Test
    @DisplayName("off by default, with the design's default limits")
    void defaults() {
        final ToolSpec spec = ToolSpec.defaults();
        assertThat(spec.isProgrammaticEnabled()).isFalse();
        assertThat(spec.getProgrammaticLimits()).isEqualTo(CodeExecutionLimits.defaults());
    }

    @Test
    @DisplayName("carries the switch and the limits it was given")
    void carries() {
        final CodeExecutionLimits limits = CodeExecutionLimits.builder().maxNestedCalls(10).build();
        final ToolSpec spec = ToolSpec.builder().programmaticEnabled(true).programmaticLimits(limits).build();
        assertThat(spec.isProgrammaticEnabled()).isTrue();
        assertThat(spec.getProgrammaticLimits()).isEqualTo(limits);
        assertThat(spec.toString()).contains("programmatic=true");
        assertThatNullPointerException().isThrownBy(() -> ToolSpec.builder().programmaticLimits(null));
    }
}
