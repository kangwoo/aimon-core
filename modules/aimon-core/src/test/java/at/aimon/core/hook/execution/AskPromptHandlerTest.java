package at.aimon.core.hook.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AskPromptHandlerTest {

    @Test
    void denyAllAlwaysReturnsDeny() {
        assertThat(AskPromptHandler.denyAll().resolve("anything")).isEqualTo(Decision.DENY);
    }

    @Test
    void allowAllAlwaysReturnsAllow() {
        assertThat(AskPromptHandler.allowAll().resolve("anything")).isEqualTo(Decision.ALLOW);
    }

    @Test
    void fromEnvDefaultsToDenyWhenVariableAbsent() {
        final AskPromptHandler h = AskPromptHandler.fromEnv(Map.of());
        assertThat(h.resolve("p")).isEqualTo(Decision.DENY);
    }

    @Test
    void fromEnvAllowWhenVariableSetToAllow() {
        final AskPromptHandler h = AskPromptHandler.fromEnv(Map.of(AskPromptHandler.ASK_DEFAULT_ENV, "allow"));
        assertThat(h.resolve("p")).isEqualTo(Decision.ALLOW);
    }

    @Test
    void fromEnvCaseInsensitive() {
        final AskPromptHandler h = AskPromptHandler.fromEnv(Map.of(AskPromptHandler.ASK_DEFAULT_ENV, "ALLOW"));
        assertThat(h.resolve("p")).isEqualTo(Decision.ALLOW);
    }

    @Test
    void fromEnvUnknownValueResolvesToDeny() {
        final AskPromptHandler h = AskPromptHandler.fromEnv(Map.of(AskPromptHandler.ASK_DEFAULT_ENV, "maybe"));
        assertThat(h.resolve("p")).isEqualTo(Decision.DENY);
    }

    @Test
    void fromEnvRejectsNullMap() {
        assertThatNullPointerException().isThrownBy(() -> AskPromptHandler.fromEnv(null));
    }
}
