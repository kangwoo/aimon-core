package at.aimon.core.subagent.task.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.AudioInput;
import at.aimon.core.agent.input.FileInput;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.MessageArtifact;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

class JsonSessionSnapshotCodecTest {

    private final JsonSessionSnapshotCodec codec = new JsonSessionSnapshotCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private static byte[] allByteValues() {
        final byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    @Test
    void roundTripsAFullMultiShapeSnapshot() {
        final Message userText = Message.user("What is in this image? 안녕하세요 🚀");
        final Message userMultimodal = Message.user(
                List.of(TextContentBlock.of("Describe:"), ImageContentBlock.ofBase64(allByteValues(), "image/png"),
                        DocumentContentBlock.of("report body".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                "application/pdf", "report.pdf")));
        final Message userImageUrl = Message
                .user(List.of(ImageContentBlock.ofUrl("https://example.com/pic.jpeg", "image/jpeg")));
        final Message assistantWithTools = Message.assistant("I'll inspect it.",
                List.of(ToolUse.of("call_1", "Bash",
                        Map.of("command", "ls -la", "flag", true, "count", 3, "ratio", 2.5, "nested", Map.of("k", "v"),
                                "items", List.of("a", "b")))),
                List.of(MessageArtifact.builder().path("/out/report.csv").fileName("report.csv").size(1024)
                        .mimeType("text/csv").toolUseId("call_1").downloadToken("tok-abc").build(),
                        MessageArtifact.builder().path("/out/plain.bin").fileName("plain.bin").size(0).build()));
        // A shape only Message.restore can build: an ASSISTANT turn with non-text content blocks plus tool uses.
        final Message assistantMultiBlock = Message.restore(Role.ASSISTANT,
                List.of(TextContentBlock.of("Here is a chart:"),
                        ImageContentBlock.ofBase64("gif-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                "image/gif")),
                List.of(ToolUse.of("call_2", "Read", Map.of("path", "/etc/hosts"))), List.of(), List.of());
        final Message toolResults = Message.toolUseResults(
                List.of(ToolUseResult.success("call_1", "done"), ToolUseResult.error("call_2", "permission denied")));

        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("conv-42"), "You are a helper.",
                List.of(userText, userMultimodal, userImageUrl, assistantWithTools, assistantMultiBlock, toolResults));

        final SessionSnapshot decoded = codec.decode(codec.encode(snapshot));

        assertThat(decoded).isEqualTo(snapshot);
        assertThat(decoded.getSystemPrompt()).isEqualTo("You are a helper.");
        assertThat(decoded.getConversationHistory()).hasSize(6);
    }

    @Test
    void roundTripsNullSystemPrompt() {
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("c"), null, List.of(Message.user("hi")));

        final SessionSnapshot decoded = codec.decode(codec.encode(snapshot));

        assertThat(decoded).isEqualTo(snapshot);
        assertThat(decoded.getSystemPrompt()).isNull();
    }

    @Test
    void roundTripsEmptyHistory() {
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("empty"));

        final SessionSnapshot decoded = codec.decode(codec.encode(snapshot));

        assertThat(decoded).isEqualTo(snapshot);
        assertThat(decoded.getConversationHistory()).isEmpty();
    }

    @Test
    void binaryPayloadsRoundTripByteForByte() {
        final byte[] imageBytes = allByteValues();
        final byte[] docBytes = new byte[]{0, -1, 42, 127, -128};
        final Message message = Message.user(List.of(ImageContentBlock.ofBase64(imageBytes, "image/webp"),
                DocumentContentBlock.of(docBytes, "text/plain")));
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("bin"), null, List.of(message));

        final SessionSnapshot decoded = codec.decode(codec.encode(snapshot));

        final ImageContentBlock image = (ImageContentBlock) decoded.getConversationHistory().get(0).getContentBlocks()
                .get(0);
        final DocumentContentBlock document = (DocumentContentBlock) decoded.getConversationHistory().get(0)
                .getContentBlocks().get(1);
        assertThat(image.getData()).containsExactly(imageBytes);
        assertThat(document.getData()).containsExactly(docBytes);
        assertThat(document.getFileName()).isNull();
    }

    @Test
    void renderPayloadIsNotPersisted() {
        final ToolUseResult withPayload = ToolUseResult.success("call_1", "ok")
                .withRenderPayload(Map.of("chart", "base64...", "kind", "bar"));
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("rp"), null,
                List.of(Message.toolUseResults(List.of(withPayload))));

        final SessionSnapshot decoded = codec.decode(codec.encode(snapshot));

        final ToolUseResult decodedResult = decoded.getConversationHistory().get(0).getToolUseResults().get(0);
        assertThat(decodedResult.getRenderPayload()).isNull();
        // The persisted result equals the same result without a payload, i.e. the sidecar was dropped by design.
        assertThat(decodedResult).isEqualTo(ToolUseResult.success("call_1", "ok"));
        assertThat(decoded).isNotEqualTo(snapshot);
    }

    @Test
    void unknownTopLevelFieldsAreIgnored() throws Exception {
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("fwd"), "sys", List.of(Message.user("hi")));
        final ObjectNode tree = (ObjectNode) mapper.readTree(codec.encode(snapshot));
        tree.put("futureField", "ignore-me");
        tree.putObject("anotherFuture").put("x", 1);

        final SessionSnapshot decoded = codec.decode(mapper.writeValueAsString(tree));

        assertThat(decoded).isEqualTo(snapshot);
    }

    /**
     * The retired {@code compactionFailureCount} field is why {@link JsonSessionSnapshotCodec#FORMAT_VERSION}
     * stayed at 1: a document written before the field left {@link SessionSnapshot} must still decode, at the same
     * version, to the snapshot it always meant. Bumping the version instead would have rejected every stored file.
     */
    @Test
    void aDocumentStillCarryingTheRetiredCompactionCounterDecodesAtVersionOne() throws Exception {
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("legacy"), "sys", List.of(Message.user("hi")));
        final ObjectNode asStoredBefore = (ObjectNode) mapper.readTree(codec.encode(snapshot));
        assertThat(fieldNames(asStoredBefore)).doesNotContain("compactionFailureCount");
        asStoredBefore.put("compactionFailureCount", 3);

        final SessionSnapshot decoded = codec.decode(mapper.writeValueAsString(asStoredBefore));

        assertThat(decoded).isEqualTo(snapshot);
    }

    private static List<String> fieldNames(ObjectNode node) {
        final List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void decodeRejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode("{ this is not json")).isInstanceOf(SessionSnapshotCodecException.class);
    }

    @Test
    void decodeRejectsMissingOrUnsupportedVersion() {
        assertThatThrownBy(() -> codec.decode("{}")).isInstanceOf(SessionSnapshotCodecException.class);
        assertThatThrownBy(() -> codec.decode("{\"version\":999,\"conversationId\":\"c\",\"messages\":[]}"))
                .isInstanceOf(SessionSnapshotCodecException.class);
    }

    @Test
    void decodeRejectsUnknownContentBlockType() {
        final String json = "{\"version\":1,\"conversationId\":\"c\",\"systemPrompt\":null,"
                + "\"compactionFailureCount\":0,\"messages\":[{\"role\":\"USER\","
                + "\"content\":[{\"type\":\"hologram\"}]}]}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(SessionSnapshotCodecException.class);
    }

    @Test
    void decodeRejectsUnknownRole() {
        final String json = "{\"version\":1,\"conversationId\":\"c\",\"compactionFailureCount\":0,"
                + "\"messages\":[{\"role\":\"WIZARD\",\"content\":[]}]}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(SessionSnapshotCodecException.class);
    }

    @Test
    void nullArgumentsRejected() {
        assertThatNullPointerException().isThrownBy(() -> codec.encode(null));
        assertThatNullPointerException().isThrownBy(() -> codec.decode(null));
    }

    // ---- rewind point -----------------------------------------------------------------------

    @Test
    void roundTripsARewindPoint() {
        final UserInput ask = TextInput.of("summarise the incident");
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("c"), "prompt",
                List.of(Message.user("earlier"), Message.user(ask.asText()), Message.assistant("half an ans")),
                SessionRewindPoint.of(1, ask));

        final SessionSnapshot decoded = codec.decode(codec.encode(snapshot));

        assertThat(decoded.getRewindPoint()).isPresent();
        assertThat(decoded.getRewindPoint().orElseThrow().getMessageCount()).isEqualTo(1);
        assertThat(decoded.getRewindPoint().orElseThrow().getUserInput()).isEqualTo(ask);
    }

    /**
     * The options are half of what a turn was. A retry submitted without them runs the same words as a different
     * caller — the principal reaches tool context and the memory request — so they round-trip with the input.
     */
    @Test
    void roundTripsTheOptionsARewindPointsTurnWasSubmittedUnder() {
        final SubmitOptions options = SubmitOptions.builder().principal(Principal.user("operator-7"))
                .systemPromptVariables(Map.of("tenant", "acme")).userContextInjection(false).build();
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("c"), "prompt", List.of(),
                SessionRewindPoint.of(0, TextInput.of("summarise the incident"), options));

        final SessionRewindPoint decoded = codec.decode(codec.encode(snapshot)).getRewindPoint().orElseThrow();

        assertThat(decoded.getSubmitOptions()).isEqualTo(options);
    }

    /**
     * A turn submitted with no options at all — every turn the CLI submits — must encode as it did before options
     * were remembered, so the common document does not grow a key that says nothing.
     */
    @Test
    void aRewindPointWithNoOptionsWritesNoOptionsKey() throws Exception {
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("c"), "prompt", List.of(),
                SessionRewindPoint.of(0, TextInput.of("hi")));

        final ObjectNode point = (ObjectNode) mapper.readTree(codec.encode(snapshot)).get("rewindPoint");

        assertThat(point.has("submitOptions")).isFalse();
        assertThat(codec.decode(codec.encode(snapshot)).getRewindPoint().orElseThrow().getSubmitOptions())
                .isEqualTo(SubmitOptions.empty());
    }

    /**
     * The reason the point keeps the input rather than the message built from it: an image has no text, so a message
     * carrying it can only be read back as a placeholder, while the input round-trips as the bytes that were sent.
     */
    @Test
    void roundTripsARewindPointStartedByAMultimodalRequest() {
        final byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};
        final UserInput ask = MultimodalInput.of(TextInput.of("what is in this screenshot?"),
                ImageInput.of(png, "image/png"));
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("c"), "prompt",
                List.of(Message.user("earlier")), SessionRewindPoint.of(1, ask));

        final SessionSnapshot decoded = codec.decode(codec.encode(snapshot));

        final UserInput replayed = decoded.getRewindPoint().orElseThrow().getUserInput();
        assertThat(replayed).isInstanceOf(MultimodalInput.class);
        assertThat(replayed).isEqualTo(ask);
        assertThat(((ImageInput) ((MultimodalInput) replayed).getInputs().get(1)).getData()).isEqualTo(png);
    }

    /**
     * Every leaf input type goes through the same pair of methods, so all four are pinned here; the fifth,
     * {@code MULTIMODAL}, is the recursive case and has its own test above.
     */
    @Test
    void roundTripsEveryUserInputType() {
        final byte[] bytes = {1, 2, 3, 4};
        final List<UserInput> inputs = List.of(TextInput.of("text"), ImageInput.of(bytes, "image/png"),
                AudioInput.of(bytes, "audio/wav"), FileInput.of(bytes, "text/csv", "incident.csv"));

        for (UserInput input : inputs) {
            final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("c"), "prompt", List.of(),
                    SessionRewindPoint.of(0, input));

            assertThat(codec.decode(codec.encode(snapshot)).getRewindPoint().orElseThrow().getUserInput())
                    .as("%s must survive the round trip", input.getType()).isEqualTo(input);
        }
    }

    /**
     * A session with nothing to retry has to encode exactly as it did before the field existed, or every stored
     * document would change shape on the first write after the upgrade.
     */
    @Test
    void aSnapshotWithNothingToRetryWritesNoRewindPointAtAll() throws Exception {
        final SessionSnapshot snapshot = SessionSnapshot.of(SessionId.of("c"), "prompt",
                List.of(Message.user("earlier")));

        final ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(snapshot));

        assertThat(root.has("rewindPoint")).isFalse();
        assertThat(codec.decode(codec.encode(snapshot)).getRewindPoint()).isEmpty();
    }

    /** A document written before the field existed decodes to "nothing to retry", which is the truth about it. */
    @Test
    void aDocumentWithoutTheFieldDecodesAsNotRetryable() {
        final String json = "{\"version\":1,\"conversationId\":\"c\",\"systemPrompt\":null,"
                + "\"messages\":[{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}";

        assertThat(codec.decode(json).getRewindPoint()).isEmpty();
    }

    /**
     * The count and the messages are written by one writer into one document, so a count that does not fit them means
     * the document is corrupt. Decoding it into a snapshot whose retry would misbehave later is worse than refusing
     * it here.
     */
    @Test
    void decodeRejectsARewindPointThatDoesNotFitTheTranscript() {
        final String json = "{\"version\":1,\"conversationId\":\"c\","
                + "\"messages\":[{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}],"
                + "\"rewindPoint\":{\"messageCount\":9," + "\"userInput\":{\"type\":\"text\",\"text\":\"hi\"}}}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(SessionSnapshotCodecException.class)
                .hasMessageContaining("not a position in this transcript");
    }

    @Test
    void aRewindPointWithNoInputAtAllDecodesAsAbsent() {
        final String json = "{\"version\":1,\"conversationId\":\"c\",\"messages\":[],"
                + "\"rewindPoint\":{\"messageCount\":0}}";

        assertThat(codec.decode(json).getRewindPoint()).isEmpty();
    }

    /**
     * An input this build cannot replay costs one turn's retry; refusing the document would cost the whole session,
     * because every backend turns a decode failure into a store exception and the record then cannot be opened at
     * all. So the point decodes as absent — unlike the count, which describes the transcript itself.
     *
     * <p>
     * The three shapes covered here are the ones that actually occur: a tag a later build introduced, the field name
     * used before the point carried an input at all, and options this build cannot rebuild.
     */
    @Test
    void aRewindPointThisBuildCannotReplayDecodesAsAbsentRatherThanFailingTheRecord() {
        final String unknownTag = "{\"version\":1,\"conversationId\":\"c\",\"messages\":[],"
                + "\"rewindPoint\":{\"messageCount\":0,\"userInput\":{\"type\":\"hologram\"}}}";
        final String olderFieldName = "{\"version\":1,\"conversationId\":\"c\",\"messages\":[],"
                + "\"rewindPoint\":{\"messageCount\":0,\"userMessage\":{\"role\":\"USER\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}}";

        final String unreadableOptions = "{\"version\":1,\"conversationId\":\"c\",\"messages\":[],"
                + "\"rewindPoint\":{\"messageCount\":0,\"userInput\":{\"type\":\"text\",\"text\":\"hi\"},"
                + "\"submitOptions\":{\"principal\":{\"type\":\"ROBOT\",\"id\":\"u\"}}}}";

        assertThat(codec.decode(unknownTag).getRewindPoint()).isEmpty();
        assertThat(codec.decode(olderFieldName).getRewindPoint()).isEmpty();
        assertThat(codec.decode(unreadableOptions).getRewindPoint())
                .as("options that cannot be rebuilt cost the retry, not the record").isEmpty();
        assertThat(codec.decode(unreadableOptions).getConversationHistory()).isEmpty();
        assertThat(codec.decode(olderFieldName).getConversationHistory()).as("the record itself still loads").isEmpty();
    }

    /** The decode is recursive, so a pathological document must be refused rather than overflow the stack. */
    @Test
    void aRewindPointNestedBeyondTheBoundDecodesAsAbsent() {
        final StringBuilder input = new StringBuilder("{\"type\":\"text\",\"text\":\"deep\"}");
        for (int i = 0; i < 64; i++) {
            input.insert(0, "{\"type\":\"multimodal\",\"inputs\":[").append("]}");
        }
        final String json = "{\"version\":1,\"conversationId\":\"c\",\"messages\":[],"
                + "\"rewindPoint\":{\"messageCount\":0,\"userInput\":" + input + "}}";

        assertThat(codec.decode(json).getRewindPoint()).isEmpty();
    }
}
