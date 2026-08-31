package at.aimon.core.hook.rewake.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import at.aimon.core.hook.rewake.ExternalEvent;
import at.aimon.core.hook.rewake.ExternalEventResolver;

class McpNotificationToRewakeBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void forwardsMappedEventToResolver() throws Exception {
        final ExternalEventResolver resolver = mock(ExternalEventResolver.class);
        when(resolver.resolve(any())).thenReturn(1);

        final McpNotificationToRewakeBridge bridge = new McpNotificationToRewakeBridge(resolver);
        bridge.onNotification("notifications/resources/updated", JSON.readTree("{\"uri\":\"file:///x\"}"));

        final ArgumentCaptor<ExternalEvent> captor = ArgumentCaptor.forClass(ExternalEvent.class);
        verify(resolver).resolve(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("mcp.resource_updated");
        assertThat(captor.getValue().getEventKey()).isEqualTo("file:///x");
        assertThat(captor.getValue().getSourceTransport()).contains("mcp");
    }

    @Test
    void skipsResolverWhenMapperReturnsNull() {
        final ExternalEventResolver resolver = mock(ExternalEventResolver.class);
        final McpNotificationMapper mapper = (method, params) -> null;

        new McpNotificationToRewakeBridge(resolver, mapper).onNotification("notifications/whatever",
                MissingNode.getInstance());

        verify(resolver, never()).resolve(any());
    }

    @Test
    void usesCustomMapperWhenSupplied() {
        final ExternalEventResolver resolver = mock(ExternalEventResolver.class);
        when(resolver.resolve(any())).thenReturn(0);
        final McpNotificationMapper customMapper = (method, params) -> ExternalEvent.builder().eventType("custom.type")
                .eventKey("custom-key").sourceTransport("mcp").build();

        new McpNotificationToRewakeBridge(resolver, customMapper).onNotification("notifications/foo",
                MissingNode.getInstance());

        final ArgumentCaptor<ExternalEvent> captor = ArgumentCaptor.forClass(ExternalEvent.class);
        verify(resolver).resolve(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("custom.type");
        assertThat(captor.getValue().getEventKey()).isEqualTo("custom-key");
    }

    @Test
    void swallowsMapperExceptionsAndDoesNotPropagate() {
        final ExternalEventResolver resolver = mock(ExternalEventResolver.class);
        final McpNotificationMapper failingMapper = (method, params) -> {
            throw new IllegalStateException("boom");
        };

        new McpNotificationToRewakeBridge(resolver, failingMapper).onNotification("notifications/foo",
                MissingNode.getInstance());

        verify(resolver, never()).resolve(any());
    }

    @Test
    void swallowsResolverExceptionsAndDoesNotPropagate() throws Exception {
        final ExternalEventResolver resolver = mock(ExternalEventResolver.class);
        when(resolver.resolve(any())).thenThrow(new RuntimeException("downstream-fail"));

        new McpNotificationToRewakeBridge(resolver).onNotification("notifications/resources/updated",
                JSON.readTree("{\"uri\":\"file:///x\"}"));

        verify(resolver, times(1)).resolve(any());
    }

    @Test
    void treatsNullParamsAsMissingNode() {
        final ExternalEventResolver resolver = mock(ExternalEventResolver.class);
        // mapper observes whatever we feed in; assert no NPE
        final McpNotificationMapper mapper = (method, params) -> {
            assertThat(params).isNotNull();
            return null;
        };

        new McpNotificationToRewakeBridge(resolver, mapper).onNotification("notifications/foo", null);

        verify(resolver, never()).resolve(any());
    }

    @Test
    void rejectsNullMethod() {
        final McpNotificationToRewakeBridge bridge = new McpNotificationToRewakeBridge(
                mock(ExternalEventResolver.class));
        assertThatNullPointerException().isThrownBy(() -> bridge.onNotification(null, MissingNode.getInstance()));
    }

    @Test
    void rejectsNullResolver() {
        assertThatNullPointerException().isThrownBy(() -> new McpNotificationToRewakeBridge(null));
    }

    @Test
    void rejectsNullMapper() {
        assertThatNullPointerException()
                .isThrownBy(() -> new McpNotificationToRewakeBridge(mock(ExternalEventResolver.class), null));
    }
}
