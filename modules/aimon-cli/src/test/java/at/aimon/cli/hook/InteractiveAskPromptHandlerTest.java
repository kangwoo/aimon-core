package at.aimon.cli.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;

class InteractiveAskPromptHandlerTest {

    @Test
    void nullConsoleDelegatesToFallback() {
        final InteractiveAskPromptHandler handler = new InteractiveAskPromptHandler(null,
                new PrintStream(new ByteArrayOutputStream()), AskPromptHandler.allowAll());
        assertThat(handler.resolve("Confirm?")).isEqualTo(Decision.ALLOW);
    }

    @Test
    void nullConsoleDenyFallback() {
        final InteractiveAskPromptHandler handler = new InteractiveAskPromptHandler(null,
                new PrintStream(new ByteArrayOutputStream()), AskPromptHandler.denyAll());
        assertThat(handler.resolve("Confirm?")).isEqualTo(Decision.DENY);
    }
}
