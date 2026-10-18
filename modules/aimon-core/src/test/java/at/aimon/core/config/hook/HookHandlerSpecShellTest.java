package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;

/** The {@code "shell"} key of a {@code hooks.json} handler (aimon-core #273). */
@DisplayName("HookHandlerSpec: \"shell\"")
class HookHandlerSpecShellTest {

    private final JacksonHookConfigParser parser = new JacksonHookConfigParser();

    private HookHandlerSpec handler(String handlerJson) {
        return parser.parse("{\"hooks\":{\"preTool\":[{\"hooks\":[" + handlerJson + "]}]}}").getHooks().get("preTool")
                .get(0).getHandlers().get(0);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource({"host,HOST", "HOST,HOST", "Host,HOST", "environment,ENVIRONMENT", "Environment,ENVIRONMENT"})
    @DisplayName("accepted values, matched ignoring case")
    void acceptedValues(String raw, HookHandlerSpec.Shell expected) {
        assertThat(handler("{\"type\":\"command\",\"command\":\"x\",\"shell\":\"" + raw + "\"}").getShell())
                .contains(expected);
    }

    @Test
    @DisplayName("absent means the event decides")
    void absentIsEmpty() {
        assertThat(handler("{\"type\":\"command\",\"command\":\"x\"}").getShell()).isEmpty();
    }

    @Test
    @DisplayName("an unknown value fails the parse rather than moving the handler somewhere else")
    void unknownValueFails() {
        assertThatThrownBy(() -> handler("{\"type\":\"command\",\"command\":\"x\",\"shell\":\"hots\"}"))
                .isInstanceOf(HookConfigParseException.class).hasMessageContaining("hots");
    }

    @Test
    @DisplayName("\"shell\" on a handler that is not a command fails the parse")
    void shellOnHttpFails() {
        assertThatThrownBy(() -> handler("{\"type\":\"http\",\"url\":\"http://x\",\"shell\":\"host\"}"))
                .isInstanceOf(HookConfigParseException.class)
                .hasMessageContaining("'shell' applies only to command handlers");
    }

    @Test
    @DisplayName("serialises as the lower-case token, and not at all when absent")
    void serialises() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();

        assertThat(mapper.writeValueAsString(handler("{\"type\":\"command\",\"command\":\"x\",\"shell\":\"HOST\"}")))
                .contains("\"shell\":\"host\"");
        assertThat(mapper.writeValueAsString(handler("{\"type\":\"command\",\"command\":\"x\"}")))
                .doesNotContain("shell");
    }
}
