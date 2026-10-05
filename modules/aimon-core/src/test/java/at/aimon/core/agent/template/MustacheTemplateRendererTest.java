package at.aimon.core.agent.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a system prompt template does with a variable nobody supplied.
 *
 * <p>
 * The embedding guide tells an application that wants the model to know the date to put {@code {{currentDate}}} in the
 * agent's system prompt and fill it on each turn. These pin the two things that advice rests on: a turn that does not
 * fill the variable sends the sentence with a hole in it rather than failing, and wrapping the sentence in a section
 * drops it instead.
 */
@DisplayName("MustacheTemplateRenderer and a variable that was not supplied")
class MustacheTemplateRendererTest {

    private final MustacheTemplateRenderer renderer = new MustacheTemplateRenderer();

    @Test
    @DisplayName("a supplied variable is rendered in place")
    void suppliedVariableIsRendered() {
        assertThat(renderer.render("Today is {{currentDate}}.", Map.of("currentDate", "2026-10-05")))
                .isEqualTo("Today is 2026-10-05.");
    }

    @Test
    @DisplayName("a missing variable renders as nothing, without an error")
    void missingVariableRendersEmpty() {
        assertThat(renderer.render("Today is {{currentDate}}.", Map.of())).isEqualTo("Today is .");
    }

    @Test
    @DisplayName("a sentence wrapped in a section is dropped when its variable is missing and kept when it is not")
    void sectionDropsTheSentenceWithoutItsVariable() {
        final String template = "Be brief.{{#currentDate}} Today is {{currentDate}}.{{/currentDate}}";

        assertThat(renderer.render(template, Map.of())).isEqualTo("Be brief.");
        assertThat(renderer.render(template, Map.of("currentDate", "2026-10-05")))
                .isEqualTo("Be brief. Today is 2026-10-05.");
    }
}
