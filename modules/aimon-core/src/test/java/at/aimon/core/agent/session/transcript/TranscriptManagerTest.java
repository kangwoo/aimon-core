package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

/**
 * Unit tests for {@link DefaultTranscriptManager}.
 */
class TranscriptManagerTest {

    private SessionRecordStore repository;
    private DefaultTranscriptManager manager;

    @BeforeEach
    void setUp() {
        repository = new InMemorySessionRecordStore();
        manager = new DefaultTranscriptManager(repository);
    }

    @Test
    @DisplayName("Constructor should throw NullPointerException when repository is null")
    void testConstructor_NullRepository_ThrowsException() {
        assertThatThrownBy(() -> new DefaultTranscriptManager(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Repository cannot be null");
    }

    @Test
    @DisplayName("Initialize should create new empty conversation when conversation ID does not exist")
    void testInitialize_NewConversation_CreatesEmptyConversation() {
        // Arrange
        SessionId sessionId = new SessionId("new-conversation");
        String systemPrompt = "You are a helpful assistant";

        // Act
        TranscriptBuffer memory = manager.initialize(sessionId, systemPrompt);

        // Assert
        assertThat(memory).isNotNull();
        assertThat(memory.getSessionId()).isEqualTo(sessionId);
        assertThat(memory.getSystemPrompt()).isEqualTo(systemPrompt);
        assertThat(memory.getMessages()).isEmpty();
    }

    @Test
    @DisplayName("Initialize should load existing conversation when conversation ID exists")
    void testInitialize_ExistingConversation_LoadsAndUpdatesSystemPrompt() {
        // Arrange
        SessionId sessionId = new SessionId("existing-conversation");
        String originalSystemPrompt = "Original system prompt";
        String newSystemPrompt = "Updated system prompt";

        // Create and save an existing session
        TranscriptBuffer existingMemory = new TranscriptBuffer(sessionId, originalSystemPrompt);
        existingMemory.addUserMessage("Previous message");
        repository.mergeFromSnapshot(existingMemory.toSnapshot());

        // Act
        TranscriptBuffer memory = manager.initialize(sessionId, newSystemPrompt);

        // Assert
        assertThat(memory).isNotNull();
        assertThat(memory.getSessionId()).isEqualTo(sessionId);
        assertThat(memory.getSystemPrompt()).isEqualTo(newSystemPrompt);
        assertThat(memory.getMessages()).hasSize(1);
        assertThat(memory.getMessages().get(0).getContent()).isEqualTo("Previous message");
    }

    @Test
    @DisplayName("Initialize should accept null systemPrompt")
    void testInitialize_NullSystemPrompt_DoesNotThrow() {
        SessionId sessionId = new SessionId("conversation-id");
        TranscriptBuffer memory = manager.initialize(sessionId, null);
        assertThat(memory).isNotNull();
        assertThat(memory.getSystemPrompt()).isNull();
    }

    @Test
    @DisplayName("Initialize should throw NullPointerException when sessionId is null")
    void testInitialize_NullConversationId_ThrowsException() {
        assertThatThrownBy(() -> manager.initialize(null, "system prompt")).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Session id cannot be null");
    }

    @Test
    @DisplayName("Save should persist conversation to repository")
    void testSave_ValidMemory_PersistsToRepository() {
        // Arrange
        SessionId sessionId = new SessionId("test-conversation");
        String systemPrompt = "Test system prompt";
        TranscriptBuffer memory = manager.initialize(sessionId, systemPrompt);
        memory.addUserMessage("Test message");

        // Act
        manager.save(memory);

        // Assert
        Optional<SessionRecordView> savedRecord = repository.load(sessionId);
        assertThat(savedRecord).isPresent();
        assertThat(savedRecord.get().getId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("Save should throw NullPointerException when memory is null")
    void testSave_NullMemory_ThrowsException() {
        assertThatThrownBy(() -> manager.save(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Transcript buffer cannot be null");
    }

    @Test
    @DisplayName("SaveSilently should persist conversation without throwing on success")
    void testSaveSilently_ValidMemory_PersistsToRepository() {
        // Arrange
        SessionId sessionId = new SessionId("test-conversation");
        String systemPrompt = "Test system prompt";
        TranscriptBuffer memory = manager.initialize(sessionId, systemPrompt);
        memory.addUserMessage("Test message");

        // Act
        manager.saveSilently(memory);

        // Assert
        Optional<SessionRecordView> savedRecord = repository.load(sessionId);
        assertThat(savedRecord).isPresent();
        assertThat(savedRecord.get().getId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("SaveSilently should log error and not throw when save fails")
    void testSaveSilently_FailingRepository_DoesNotThrow() {
        // Arrange
        SessionRecordStore failingRepository = new SessionRecordStore() {
            @Override
            public void mergeFromSnapshot(SessionSnapshot snapshot) {
                throw new RuntimeException("Simulated save failure");
            }

            @Override
            public SessionRecordView provision(SessionId sessionId, String agentRef) {
                throw new UnsupportedOperationException("not exercised by this test");
            }

            @Override
            public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals,
                    at.aimon.core.agent.budget.ExecutionBudget budgetOverride) {
                throw new UnsupportedOperationException("not exercised by this test");
            }

            @Override
            public int incrementCompactionFailureCount(SessionId sessionId) {
                throw new UnsupportedOperationException("not exercised by this test");
            }

            @Override
            public void resetCompactionFailureCount(SessionId sessionId) {
                throw new UnsupportedOperationException("not exercised by this test");
            }

            @Override
            public Optional<SessionRecordView> load(SessionId sessionId) {
                return Optional.empty();
            }

            @Override
            public void delete(SessionId sessionId) {
                // No-op
            }

            @Override
            public java.util.List<SessionId> listSessionIds() {
                return java.util.List.of();
            }

            @Override
            public boolean exists(SessionId sessionId) {
                return false;
            }

            @Override
            public void clear() {
                // No-op
            }
        };

        TranscriptManager managerWithFailingRepo = new DefaultTranscriptManager(failingRepository);
        SessionId sessionId = new SessionId("test-conversation");
        String systemPrompt = "Test system prompt";
        String userMessage = "Test message";
        TranscriptBuffer memory = new TranscriptBuffer(sessionId, systemPrompt);
        memory.addUserMessage(userMessage);

        // Act & Assert - should not throw
        managerWithFailingRepo.saveSilently(memory);
    }

    @Test
    @DisplayName("SaveSilently should throw NullPointerException when memory is null")
    void testSaveSilently_NullMemory_ThrowsException() {
        assertThatThrownBy(() -> manager.saveSilently(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Transcript buffer cannot be null");
    }

    @Test
    @DisplayName("Initialize then addMessage should attach multimodal message to a new conversation")
    void testInitialize_ThenAddMultimodalMessage_NewConversation() {
        // Arrange
        SessionId sessionId = new SessionId("multimodal-conversation");
        String systemPrompt = "You are a helpful assistant";
        Message userMessage = Message.user(java.util.List.of(TextContentBlock.of("What's in this image?"),
                ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png")));

        // Act
        TranscriptBuffer memory = manager.initialize(sessionId, systemPrompt);
        memory.addMessage(userMessage);

        // Assert
        assertThat(memory).isNotNull();
        assertThat(memory.getSessionId()).isEqualTo(sessionId);
        assertThat(memory.getSystemPrompt()).isEqualTo(systemPrompt);
        assertThat(memory.getMessages()).hasSize(1);
        assertThat(memory.getMessages().get(0).hasNonTextContentBlocks()).isTrue();
        assertThat(memory.getMessages().get(0).getContentBlocks()).hasSize(2);
        assertThat(memory.getMessages().get(0).getContent()).isEqualTo("What's in this image?");
    }

    @Test
    @DisplayName("Initialize then addMessage should append multimodal message to an existing conversation")
    void testInitialize_ThenAddMultimodalMessage_ExistingConversation() {
        // Arrange
        SessionId sessionId = new SessionId("existing-multimodal");
        String originalSystemPrompt = "Original prompt";
        String newSystemPrompt = "Updated prompt";

        TranscriptBuffer existingMemory = new TranscriptBuffer(sessionId, originalSystemPrompt);
        existingMemory.addUserMessage("Previous message");
        repository.mergeFromSnapshot(existingMemory.toSnapshot());

        Message userMessage = Message.user(java.util.List.of(TextContentBlock.of("Describe this"),
                ImageContentBlock.ofBase64(new byte[]{4, 5, 6}, "image/jpeg")));

        // Act
        TranscriptBuffer memory = manager.initialize(sessionId, newSystemPrompt);
        memory.addMessage(userMessage);

        // Assert
        assertThat(memory.getMessages()).hasSize(2);
        assertThat(memory.getMessages().get(1).hasNonTextContentBlocks()).isTrue();
        assertThat(memory.getSystemPrompt()).isEqualTo(newSystemPrompt);
    }
}
