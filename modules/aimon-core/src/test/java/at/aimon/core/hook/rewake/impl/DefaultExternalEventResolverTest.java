package at.aimon.core.hook.rewake.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.rewake.ExternalEvent;
import at.aimon.core.hook.rewake.RewakeService;

class DefaultExternalEventResolverTest {

    @Test
    void delegatesToRewakeServiceUsingEventFields() {
        final RewakeService service = mock(RewakeService.class);
        when(service.resolve(eq("webhook"), eq("ticket-1"), any())).thenReturn(2);

        final DefaultExternalEventResolver resolver = new DefaultExternalEventResolver(service);
        final ExternalEvent event = ExternalEvent.builder().eventType("webhook").eventKey("ticket-1")
                .payload("status", "approved").sourceTransport("webhook").idempotencyKey("idem-42").build();

        final int matched = resolver.resolve(event);

        assertThat(matched).isEqualTo(2);
        verify(service).resolve("webhook", "ticket-1", Map.of("status", "approved"));
    }

    @Test
    void returnsZeroWhenNoEnvelopeMatches() {
        final RewakeService service = mock(RewakeService.class);
        when(service.resolve(anyString(), anyString(), any())).thenReturn(0);

        final DefaultExternalEventResolver resolver = new DefaultExternalEventResolver(service);
        final ExternalEvent event = ExternalEvent.builder().eventType("webhook").eventKey("nope").build();

        assertThat(resolver.resolve(event)).isZero();
    }

    @Test
    void rejectsNullEvent() {
        final RewakeService service = mock(RewakeService.class);
        final DefaultExternalEventResolver resolver = new DefaultExternalEventResolver(service);

        assertThatNullPointerException().isThrownBy(() -> resolver.resolve(null));
        verify(service, never()).resolve(anyString(), anyString(), any());
    }

    @Test
    void rejectsNullRewakeService() {
        assertThatNullPointerException().isThrownBy(() -> new DefaultExternalEventResolver(null));
    }
}
