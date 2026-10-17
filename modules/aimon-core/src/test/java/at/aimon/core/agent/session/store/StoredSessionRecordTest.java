package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

@DisplayName("StoredSessionRecord")
class StoredSessionRecordTest {

    private static final SessionId ID = SessionId.of("stored");

    @Test
    @DisplayName("the builder carries the owner")
    void builderCarriesTheOwner() {
        final StoredSessionRecord record = StoredSessionRecord.builder(ID).agentRef("agent-a")
                .owner(Principal.user("alice", "Alice")).build();

        assertThat(record.getOwner()).contains(Principal.user("alice"));
        assertThat(record.getOwner().orElseThrow().getDisplayName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("no owner is the default, and both empty() overloads agree on it")
    void ownerDefaultsToEmpty() {
        assertThat(StoredSessionRecord.builder(ID).build().getOwner()).isEmpty();
        assertThat(StoredSessionRecord.empty(ID, "agent-a").getOwner()).isEmpty();
        assertThat(StoredSessionRecord.empty(ID, "agent-a", null).getOwner()).isEmpty();
    }

    @Test
    @DisplayName("empty(id, agentRef, owner) reports both side fields and nothing else")
    void emptyWithOwner() {
        final StoredSessionRecord record = StoredSessionRecord.empty(ID, "agent-a", Principal.service("svc", "Svc"));

        assertThat(record.getAgentRef()).contains("agent-a");
        assertThat(record.getOwner()).contains(Principal.service("svc", "Svc"));
        assertThat(record.getMessages()).isEmpty();
        assertThat(record.getSessionTotals()).isEqualTo(SessionTotals.empty());
    }
}
