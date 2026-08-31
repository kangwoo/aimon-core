package at.aimon.rewake.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RewakeWebhookConfigTest {

    @Test
    void defaultsMatchPhase4BConventions() {
        final RewakeWebhookConfig config = RewakeWebhookConfig.builder().build();

        assertThat(config.getPort()).isZero();
        assertThat(config.getPath()).isEqualTo("/rewake/events");
        assertThat(config.getCredentialProfile()).isEqualTo("rewake-webhook");
        assertThat(config.getCredentialField()).isEqualTo("hmac-secret");
        assertThat(config.getSignatureHeader()).isEqualTo("X-Rewake-Signature");
        assertThat(config.getIdempotencyHeader()).isEqualTo("X-Rewake-Idempotency-Key");
        assertThat(config.getIdempotencyWindow()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void rejectsNegativePort() {
        assertThatIllegalArgumentException().isThrownBy(() -> RewakeWebhookConfig.builder().port(-1).build());
    }

    @Test
    void rejectsPathWithoutLeadingSlash() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeWebhookConfig.builder().path("rewake/events").build());
    }

    @Test
    void rejectsBlankCredentialProfile() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeWebhookConfig.builder().credentialProfile(" ").build());
    }

    @Test
    void rejectsZeroIdempotencyWindow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeWebhookConfig.builder().idempotencyWindow(Duration.ZERO).build());
    }
}
