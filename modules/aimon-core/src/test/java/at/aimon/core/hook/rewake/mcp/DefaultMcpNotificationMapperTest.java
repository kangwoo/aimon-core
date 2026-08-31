package at.aimon.core.hook.rewake.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import at.aimon.core.hook.rewake.ExternalEvent;

class DefaultMcpNotificationMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DefaultMcpNotificationMapper mapper = new DefaultMcpNotificationMapper();

    @Test
    void mapsResourceUpdatedToMcpResourceUpdatedEventTypeWithUriKey() throws Exception {
        final ExternalEvent event = mapper.map("notifications/resources/updated",
                JSON.readTree("{\"uri\":\"file:///foo.txt\",\"title\":\"foo\"}"));

        assertThat(event).isNotNull();
        assertThat(event.getEventType()).isEqualTo("mcp.resource_updated");
        assertThat(event.getEventKey()).isEqualTo("file:///foo.txt");
        assertThat(event.getSourceTransport()).contains("mcp");
        assertThat(event.getPayload()).containsEntry("uri", "file:///foo.txt").containsEntry("title", "foo");
    }

    @Test
    void mapsListChangedToWildcardKey() throws Exception {
        final ExternalEvent event = mapper.map("notifications/resources/list_changed", JSON.readTree("{}"));

        assertThat(event).isNotNull();
        assertThat(event.getEventType()).isEqualTo("mcp.resource_list_changed");
        assertThat(event.getEventKey()).isEqualTo("*");
    }

    @Test
    void mapsToolListChangedToWildcardKey() throws Exception {
        final ExternalEvent event = mapper.map("notifications/tools/list_changed", JSON.readTree("{}"));

        assertThat(event.getEventType()).isEqualTo("mcp.tool_list_changed");
        assertThat(event.getEventKey()).isEqualTo("*");
    }

    @Test
    void skipsLogNotifications() throws Exception {
        assertThat(mapper.map("notifications/log", JSON.readTree("{\"level\":\"info\"}"))).isNull();
    }

    @Test
    void mapsUnknownNotificationToGenericMcpEventType() throws Exception {
        final ExternalEvent event = mapper.map("notifications/custom/workflow_step",
                JSON.readTree("{\"id\":\"step-7\"}"));

        assertThat(event).isNotNull();
        assertThat(event.getEventType()).isEqualTo("mcp.custom.workflow_step");
        assertThat(event.getEventKey()).isEqualTo("step-7");
        assertThat(event.getPayload()).containsEntry("id", "step-7");
    }

    @Test
    void unknownNotificationWithoutKeyFieldIsSkipped() throws Exception {
        assertThat(mapper.map("notifications/custom/keyless", JSON.readTree("{\"foo\":\"bar\"}"))).isNull();
    }

    @Test
    void unknownNotificationFallsBackToFirstAvailableKeyField() throws Exception {
        final ExternalEvent event = mapper.map("notifications/custom/something", JSON.readTree("{\"name\":\"alice\"}"));

        assertThat(event).isNotNull();
        assertThat(event.getEventKey()).isEqualTo("alice");
    }

    @Test
    void resourceUpdatedWithoutUriIsSkipped() throws Exception {
        assertThat(mapper.map("notifications/resources/updated", JSON.readTree("{}"))).isNull();
    }

    @Test
    void rejectsNullMethod() {
        assertThatNullPointerException().isThrownBy(() -> mapper.map(null, MissingNode.getInstance()));
    }

    @Test
    void rejectsNullParams() {
        assertThatNullPointerException().isThrownBy(() -> mapper.map("notifications/log", null));
    }

    @Test
    void nonNotificationMethodIsSkipped() throws Exception {
        assertThat(mapper.map("tools/list", JSON.readTree("{}"))).isNull();
    }
}
