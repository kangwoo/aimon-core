package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.base.Principal;

/**
 * Pins the frozen wire key {@link MongoSessionInbox#deliver} writes at the top level of every inbox document.
 *
 * <p>
 * Behavior against a real server lives in the docker-tagged {@code MongoSessionInboxIntegrationTest}; this class
 * drives the store against a mocked collection so the write shape is asserted under the daemonless {@code test} task,
 * where a wire-key regression is cheap to catch.
 */
@DisplayName("MongoSessionInbox — the frozen wire key deliver() writes")
class MongoSessionInboxTest {

    @Test
    @DisplayName("deliver() writes the conversation under the frozen wire key \"conversationId\"")
    @SuppressWarnings("unchecked")
    void conversationIdKeyIsFrozenOnDeliver() {
        // FROZEN WIRE FORMAT. This is the only place inbox documents get their sessionId — InboundMessageCodec
        // encodes the payload subtree and leaves the top-level columns to the store. Two things depend on the literal
        // and neither is visible to a codec round-trip: messages already in conversation_inbox, and the
        // `{ conversationId: 1, priority: 1, deliveredAt: 1 }` index declared in init.js, which is what makes collect()
        // a sorted index scan instead of a collection scan. A rename keeps every test green while newly written
        // documents quietly stop matching the deployed index and the existing backlog stops matching the new filter.
        // Hence the literal: a rename sweep would carry DocumentKeys.F_CONVERSATION_ID and every reference to it along
        // in lockstep, so a constant-based assertion can never fail.
        final MongoCollection<Document> collection = mock(MongoCollection.class);
        final MongoDatabase database = mock(MongoDatabase.class);
        when(database.getCollection(anyString())).thenReturn(collection);

        new MongoSessionInbox(database).deliver(message());

        final ArgumentCaptor<List<? extends Bson>> pipeline = ArgumentCaptor.forClass(List.class);
        verify(collection).findOneAndUpdate(any(Bson.class), pipeline.capture(), any(FindOneAndUpdateOptions.class));
        final Document setStage = (Document) pipeline.getValue().get(0);
        final Document fields = setStage.get("$set", Document.class);
        assertThat(fields.keySet()).contains("conversationId");
        assertThat(fields.getString("conversationId")).isEqualTo("conv-42");
    }

    private static InboundMessage message() {
        return InboundMessage.builder().sessionId(SessionId.of("conv-42")).agentRef("agent-x").userInput("hello")
                .priority(QueuedInputPriority.NEXT)
                .initiator(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .deliveredAt(Instant.parse("2026-04-27T10:00:00Z")).build();
    }
}
