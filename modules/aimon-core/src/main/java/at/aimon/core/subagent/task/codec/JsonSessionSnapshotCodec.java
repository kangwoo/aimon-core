package at.aimon.core.subagent.task.codec;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.input.AudioInput;
import at.aimon.core.agent.input.FileInput;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.MessageArtifact;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ContentBlock;
import at.aimon.core.llm.content.DocumentContentBlock;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;

/**
 * Default {@link SessionSnapshotCodec} that maps a snapshot to a versioned JSON document by hand.
 *
 * <p>
 * Every field of the snapshot type graph is written explicitly so that no reflection-based assumption about the core
 * message types is required and every value round-trips: content blocks are discriminated by a {@code type} tag, binary
 * image/document payloads are carried as standard base64 text (unambiguous and human-inspectable), a tool use's
 * arbitrary {@code Map<String, Object>} input is embedded as a nested JSON value, and each message is rebuilt through
 * {@link Message#restore(Role, java.util.List, java.util.List, java.util.List, java.util.List)}. The top-level document
 * carries a format {@code version} so a future schema change can be detected rather than silently mis-parsed.
 *
 * <p>
 * <p>
 * A {@link SessionRewindPoint} is written with the {@link at.aimon.core.agent.input.UserInput} the turn was submitted
 * with — plus, when it had any, the {@link at.aimon.core.agent.SubmitOptions} it was submitted under, delegated to
 * {@link SubmitOptionsCodec} rather than hand-mapped a fourth time. The input uses its own set of type tags:
 * {@code file} and {@code multimodal} have no content-block counterpart, and
 * {@code image} means an inline-bytes {@link at.aimon.core.agent.input.ImageInput} rather than the two-source content
 * block. That is one more shape to hand-map than encoding the message would be, and it buys the only thing that makes
 * a retry faithful — the message is a lossy rendering of the request (an image reads back as a text placeholder), so
 * a point carrying one could only ever replay a description of what was asked.
 *
 * <p>
 * Per the {@link SessionSnapshotCodec} contract, {@link ToolUseResult#getRenderPayload()} is intentionally not
 * emitted. Unknown top-level fields are ignored on decode (forward tolerance), but an unknown content block type or an
 * unsupported format version is a hard {@link SessionSnapshotCodecException} because it cannot be reconstructed
 * faithfully.
 *
 * <p>
 * {@code compactionFailureCount} was dropped from the document when it left {@link SessionSnapshot}, and
 * {@link #FORMAT_VERSION} deliberately stayed at 1. Forward tolerance already covers the old direction — a stored
 * document still carrying the field decodes with it ignored — and the field only ever held the value a snapshot could
 * produce for it, which was {@code 0}: no snapshot reaching this codec came from a persisted record. Bumping the
 * version would have made every existing snapshot file undecodable to buy nothing.
 *
 * <p>
 * Stateless and thread-safe: the shared {@link ObjectMapper} is used only for tree building and text I/O.
 */
public final class JsonSessionSnapshotCodec implements SessionSnapshotCodec {

    /** Current serialization format version. */
    public static final int FORMAT_VERSION = 1;

    /**
     * How deeply a {@code multimodal} input may nest before the document is treated as unreadable.
     *
     * <p>
     * The decode is recursive, and this codec's other refusals are exceptions rather than stack overflows. A real
     * request nests once or twice; anything near this bound is a corrupt or hostile document, not a user's.
     */
    private static final int MAX_INPUT_NESTING = 32;

    private static final Logger log = LoggerFactory.getLogger(JsonSessionSnapshotCodec.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String FIELD_VERSION = "version";
    private static final String FIELD_CONVERSATION_ID = "conversationId";
    private static final String FIELD_SYSTEM_PROMPT = "systemPrompt";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_REWIND_POINT = "rewindPoint";
    private static final String FIELD_MESSAGE_COUNT = "messageCount";
    private static final String FIELD_USER_INPUT = "userInput";
    private static final String FIELD_INPUTS = "inputs";
    private static final String FIELD_SUBMIT_OPTIONS = "submitOptions";

    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_TOOL_USES = "toolUses";
    private static final String FIELD_TOOL_RESULTS = "toolResults";
    private static final String FIELD_ARTIFACTS = "artifacts";

    private static final String FIELD_TYPE = "type";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_SOURCE = "source";
    private static final String FIELD_MIME_TYPE = "mimeType";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_URL = "url";
    private static final String FIELD_FILE_NAME = "fileName";

    private static final String FIELD_ID = "id";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_INPUT = "input";

    private static final String FIELD_TOOL_USE_ID = "toolUseId";
    private static final String FIELD_IS_ERROR = "isError";

    private static final String FIELD_PATH = "path";
    private static final String FIELD_SIZE = "size";
    private static final String FIELD_DOWNLOAD_TOKEN = "downloadToken";

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_DOCUMENT = "document";

    private static final String INPUT_TYPE_TEXT = "text";
    private static final String INPUT_TYPE_IMAGE = "image";
    private static final String INPUT_TYPE_AUDIO = "audio";
    private static final String INPUT_TYPE_FILE = "file";
    private static final String INPUT_TYPE_MULTIMODAL = "multimodal";
    private static final String SOURCE_BASE64 = "base64";
    private static final String SOURCE_URL = "url";

    @Override
    public String encode(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        try {
            final ObjectNode root = MAPPER.createObjectNode();
            root.put(FIELD_VERSION, FORMAT_VERSION);
            root.put(FIELD_CONVERSATION_ID, snapshot.getSessionId().value());
            if (snapshot.getSystemPrompt() != null) {
                root.put(FIELD_SYSTEM_PROMPT, snapshot.getSystemPrompt());
            } else {
                root.putNull(FIELD_SYSTEM_PROMPT);
            }
            final ArrayNode messages = root.putArray(FIELD_MESSAGES);
            for (Message message : snapshot.getConversationHistory()) {
                messages.add(encodeMessage(message));
            }
            // Written only when there is one, so a snapshot with nothing to retry encodes exactly as it did before
            // this field existed. A reader that predates it ignores it; this reader defaults it to absent, which is
            // the truthful answer for a document written when sessions could not be rewound at all.
            if (snapshot.getRewindPoint().isPresent()) {
                final SessionRewindPoint point = snapshot.getRewindPoint().get();
                final ObjectNode node = root.putObject(FIELD_REWIND_POINT);
                node.put(FIELD_MESSAGE_COUNT, point.getMessageCount());
                node.set(FIELD_USER_INPUT, encodeUserInput(point.getUserInput()));
                // Written only when the turn carried options, so a turn submitted without any — every turn the CLI
                // submits — encodes exactly as it did before they were remembered.
                final ObjectNode options = SubmitOptionsCodec.encode(point.getSubmitOptions());
                if (options != null) {
                    node.set(FIELD_SUBMIT_OPTIONS, options);
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new SessionSnapshotCodecException("Failed to encode session snapshot: " + e.getMessage(), e);
        }
    }

    @Override
    public SessionSnapshot decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded cannot be null");
        try {
            final JsonNode root = MAPPER.readTree(encoded);
            if (root == null || !root.isObject()) {
                throw new SessionSnapshotCodecException("Encoded snapshot is not a JSON object");
            }
            final int version = root.path(FIELD_VERSION).asInt(-1);
            if (version != FORMAT_VERSION) {
                throw new SessionSnapshotCodecException("Unsupported session snapshot format version: " + version
                        + " (expected " + FORMAT_VERSION + ")");
            }
            final SessionId sessionId = SessionId.of(requiredText(root, FIELD_CONVERSATION_ID));
            final String systemPrompt = root.hasNonNull(FIELD_SYSTEM_PROMPT)
                    ? root.get(FIELD_SYSTEM_PROMPT).asText()
                    : null;
            final List<Message> messages = new ArrayList<>();
            final JsonNode messagesNode = root.get(FIELD_MESSAGES);
            if (messagesNode != null && messagesNode.isArray()) {
                for (JsonNode messageNode : messagesNode) {
                    messages.add(decodeMessage(messageNode));
                }
            }
            return SessionSnapshot.of(sessionId, systemPrompt, messages, decodeRewindPoint(root, messages.size()));
        } catch (SessionSnapshotCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new SessionSnapshotCodecException("Failed to decode session snapshot: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the rewind point, and refuses one that does not fit the history it arrived with.
     *
     * <p>
     * A count past the end of the messages is not a document this codec can honour: rewinding to it would either
     * throw or silently keep the whole turn. The pair is written in one document by one writer, so a mismatch means
     * the document is corrupt — saying so beats materialising a snapshot whose retry is a trap.
     */
    private SessionRewindPoint decodeRewindPoint(JsonNode root, int messageCount) {
        final JsonNode node = root.get(FIELD_REWIND_POINT);
        if (node == null || !node.isObject()) {
            return null;
        }
        final int keep = node.path(FIELD_MESSAGE_COUNT).asInt(-1);
        if (keep < 0 || keep > messageCount) {
            throw new SessionSnapshotCodecException("Rewind point keeps " + keep + " of " + messageCount
                    + " messages, which is not a position in this transcript");
        }
        // Unlike the count, the input describes something optional. The count indexes the messages that arrived with
        // it, so a mismatch means the document is inconsistent and refusing is the only honest answer. An input this
        // reader cannot decode — written under an older field name, or tagged with a type a later build added — costs
        // exactly one turn's retry, while throwing would cost the whole record: every backend turns a decode failure
        // into a SessionRecordStoreException, so the session could not be opened at all. "Nothing to retry" is both
        // the smaller answer and the true one.
        final JsonNode userInput = node.get(FIELD_USER_INPUT);
        if (userInput == null || !userInput.isObject()) {
            log.debug("Rewind point carries no readable user input; treating the turn as not retryable");
            return null;
        }
        try {
            return SessionRewindPoint.of(keep, decodeUserInput(userInput, 0),
                    SubmitOptionsCodec.decode(node.get(FIELD_SUBMIT_OPTIONS)));
        } catch (SessionSnapshotCodecException e) {
            log.debug("Rewind point holds a user input this build cannot replay ({}); treating the turn as not"
                    + " retryable", e.getMessage());
            return null;
        }
    }

    /**
     * Encodes the input a turn was submitted with, for a rewind point to replay.
     *
     * <p>
     * Written as the input rather than as the message the executor built from it, because the two are not the same
     * for anything but plain text and only the input can be submitted again. The type tags are this method's own —
     * {@code file} and {@code multimodal} have no content-block counterpart, and {@code image} means an
     * {@link ImageInput}, which is always inline bytes and so carries no {@code source} discriminator.
     */
    private ObjectNode encodeUserInput(UserInput userInput) {
        final ObjectNode node = MAPPER.createObjectNode();
        if (userInput instanceof TextInput text) {
            node.put(FIELD_TYPE, INPUT_TYPE_TEXT);
            node.put(FIELD_TEXT, text.getText());
        } else if (userInput instanceof ImageInput image) {
            node.put(FIELD_TYPE, INPUT_TYPE_IMAGE);
            node.put(FIELD_MIME_TYPE, image.getMimeType());
            node.put(FIELD_DATA, encodeBase64(image.getData()));
        } else if (userInput instanceof AudioInput audio) {
            node.put(FIELD_TYPE, INPUT_TYPE_AUDIO);
            node.put(FIELD_MIME_TYPE, audio.getMimeType());
            node.put(FIELD_DATA, encodeBase64(audio.getData()));
        } else if (userInput instanceof FileInput file) {
            node.put(FIELD_TYPE, INPUT_TYPE_FILE);
            node.put(FIELD_MIME_TYPE, file.getMimeType());
            node.put(FIELD_FILE_NAME, file.getFileName());
            node.put(FIELD_DATA, encodeBase64(file.getData()));
        } else if (userInput instanceof MultimodalInput multimodal) {
            node.put(FIELD_TYPE, INPUT_TYPE_MULTIMODAL);
            final ArrayNode inputs = node.putArray(FIELD_INPUTS);
            for (UserInput nested : multimodal.getInputs()) {
                inputs.add(encodeUserInput(nested));
            }
        } else {
            throw new SessionSnapshotCodecException(
                    "Unsupported user input type for encoding: " + userInput.getClass().getName());
        }
        return node;
    }

    private UserInput decodeUserInput(JsonNode node, int depth) {
        if (node == null || !node.isObject()) {
            throw new SessionSnapshotCodecException("User input entry is not a JSON object");
        }
        if (depth > MAX_INPUT_NESTING) {
            throw new SessionSnapshotCodecException(
                    "User input nests deeper than " + MAX_INPUT_NESTING + " levels; refusing to decode it");
        }
        final String type = requiredText(node, FIELD_TYPE);
        return switch (type) {
            case INPUT_TYPE_TEXT -> TextInput.of(requiredText(node, FIELD_TEXT));
            case INPUT_TYPE_IMAGE ->
                ImageInput.of(decodeBase64(requiredText(node, FIELD_DATA)), requiredText(node, FIELD_MIME_TYPE));
            case INPUT_TYPE_AUDIO ->
                AudioInput.of(decodeBase64(requiredText(node, FIELD_DATA)), requiredText(node, FIELD_MIME_TYPE));
            case INPUT_TYPE_FILE -> FileInput.of(decodeBase64(requiredText(node, FIELD_DATA)),
                    requiredText(node, FIELD_MIME_TYPE), requiredText(node, FIELD_FILE_NAME));
            case INPUT_TYPE_MULTIMODAL -> decodeMultimodalInput(node, depth);
            default -> throw new SessionSnapshotCodecException("Unknown user input type: " + type);
        };
    }

    private UserInput decodeMultimodalInput(JsonNode node, int depth) {
        final JsonNode inputsNode = node.get(FIELD_INPUTS);
        if (inputsNode == null || !inputsNode.isArray() || inputsNode.isEmpty()) {
            throw new SessionSnapshotCodecException("Multimodal user input carries no inputs");
        }
        final List<UserInput> inputs = new ArrayList<>();
        for (JsonNode nested : inputsNode) {
            inputs.add(decodeUserInput(nested, depth + 1));
        }
        return MultimodalInput.of(inputs);
    }

    private ObjectNode encodeMessage(Message message) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put(FIELD_ROLE, message.getRole().name());
        final ArrayNode content = node.putArray(FIELD_CONTENT);
        for (ContentBlock block : message.getContentBlocks()) {
            content.add(encodeContentBlock(block));
        }
        if (message.hasToolUses()) {
            final ArrayNode toolUses = node.putArray(FIELD_TOOL_USES);
            for (ToolUse toolUse : message.getToolUses()) {
                toolUses.add(encodeToolUse(toolUse));
            }
        }
        if (message.hasToolResults()) {
            final ArrayNode results = node.putArray(FIELD_TOOL_RESULTS);
            for (ToolUseResult result : message.getToolUseResults()) {
                results.add(encodeToolResult(result));
            }
        }
        if (message.hasArtifacts()) {
            final ArrayNode artifacts = node.putArray(FIELD_ARTIFACTS);
            for (MessageArtifact artifact : message.getArtifacts()) {
                artifacts.add(encodeArtifact(artifact));
            }
        }
        return node;
    }

    private ObjectNode encodeContentBlock(ContentBlock block) {
        final ObjectNode node = MAPPER.createObjectNode();
        if (block instanceof TextContentBlock text) {
            node.put(FIELD_TYPE, TYPE_TEXT);
            node.put(FIELD_TEXT, text.getText());
        } else if (block instanceof ImageContentBlock image) {
            node.put(FIELD_TYPE, TYPE_IMAGE);
            node.put(FIELD_MIME_TYPE, image.getMimeType());
            if (image.getSource() == ImageContentBlock.Source.BASE64) {
                node.put(FIELD_SOURCE, SOURCE_BASE64);
                node.put(FIELD_DATA, encodeBase64(image.getData()));
            } else {
                node.put(FIELD_SOURCE, SOURCE_URL);
                node.put(FIELD_URL, image.getUrl());
            }
        } else if (block instanceof DocumentContentBlock document) {
            node.put(FIELD_TYPE, TYPE_DOCUMENT);
            node.put(FIELD_MIME_TYPE, document.getMimeType());
            node.put(FIELD_DATA, encodeBase64(document.getData()));
            if (document.getFileName() != null) {
                node.put(FIELD_FILE_NAME, document.getFileName());
            }
        } else {
            throw new SessionSnapshotCodecException(
                    "Unsupported content block type for encoding: " + block.getClass().getName());
        }
        return node;
    }

    private ObjectNode encodeToolUse(ToolUse toolUse) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put(FIELD_ID, toolUse.getId());
        node.put(FIELD_NAME, toolUse.getName());
        node.set(FIELD_INPUT, MAPPER.valueToTree(toolUse.getInput()));
        return node;
    }

    private ObjectNode encodeToolResult(ToolUseResult result) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put(FIELD_TOOL_USE_ID, result.getToolUseId());
        node.put(FIELD_CONTENT, result.getContent());
        node.put(FIELD_IS_ERROR, result.isError());
        return node;
    }

    private ObjectNode encodeArtifact(MessageArtifact artifact) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put(FIELD_PATH, artifact.getPath());
        node.put(FIELD_FILE_NAME, artifact.getFileName());
        node.put(FIELD_SIZE, artifact.getSize());
        artifact.getMimeType().ifPresent(value -> node.put(FIELD_MIME_TYPE, value));
        artifact.getToolUseId().ifPresent(value -> node.put(FIELD_TOOL_USE_ID, value));
        artifact.getDownloadToken().ifPresent(value -> node.put(FIELD_DOWNLOAD_TOKEN, value));
        return node;
    }

    private Message decodeMessage(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new SessionSnapshotCodecException("Message entry is not a JSON object");
        }
        final Role role = decodeRole(requiredText(node, FIELD_ROLE));

        final List<ContentBlock> content = new ArrayList<>();
        final JsonNode contentNode = node.get(FIELD_CONTENT);
        if (contentNode != null && contentNode.isArray()) {
            for (JsonNode blockNode : contentNode) {
                content.add(decodeContentBlock(blockNode));
            }
        }
        final List<ToolUse> toolUses = new ArrayList<>();
        final JsonNode toolUsesNode = node.get(FIELD_TOOL_USES);
        if (toolUsesNode != null && toolUsesNode.isArray()) {
            for (JsonNode toolUseNode : toolUsesNode) {
                toolUses.add(decodeToolUse(toolUseNode));
            }
        }
        final List<ToolUseResult> results = new ArrayList<>();
        final JsonNode resultsNode = node.get(FIELD_TOOL_RESULTS);
        if (resultsNode != null && resultsNode.isArray()) {
            for (JsonNode resultNode : resultsNode) {
                results.add(decodeToolResult(resultNode));
            }
        }
        final List<MessageArtifact> artifacts = new ArrayList<>();
        final JsonNode artifactsNode = node.get(FIELD_ARTIFACTS);
        if (artifactsNode != null && artifactsNode.isArray()) {
            for (JsonNode artifactNode : artifactsNode) {
                artifacts.add(decodeArtifact(artifactNode));
            }
        }
        return Message.restore(role, content, toolUses, results, artifacts);
    }

    private ContentBlock decodeContentBlock(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new SessionSnapshotCodecException("Content block entry is not a JSON object");
        }
        final String type = requiredText(node, FIELD_TYPE);
        return switch (type) {
            case TYPE_TEXT -> TextContentBlock.of(requiredText(node, FIELD_TEXT));
            case TYPE_IMAGE -> decodeImage(node);
            case TYPE_DOCUMENT -> decodeDocument(node);
            default -> throw new SessionSnapshotCodecException("Unknown content block type: " + type);
        };
    }

    private ContentBlock decodeImage(JsonNode node) {
        final String mimeType = requiredText(node, FIELD_MIME_TYPE);
        final String source = requiredText(node, FIELD_SOURCE);
        if (SOURCE_BASE64.equals(source)) {
            return ImageContentBlock.ofBase64(decodeBase64(requiredText(node, FIELD_DATA)), mimeType);
        }
        if (SOURCE_URL.equals(source)) {
            return ImageContentBlock.ofUrl(requiredText(node, FIELD_URL), mimeType);
        }
        throw new SessionSnapshotCodecException("Unknown image source: " + source);
    }

    private ContentBlock decodeDocument(JsonNode node) {
        final String mimeType = requiredText(node, FIELD_MIME_TYPE);
        final byte[] data = decodeBase64(requiredText(node, FIELD_DATA));
        final String fileName = node.hasNonNull(FIELD_FILE_NAME) ? node.get(FIELD_FILE_NAME).asText() : null;
        return DocumentContentBlock.of(data, mimeType, fileName);
    }

    private ToolUse decodeToolUse(JsonNode node) {
        final String id = requiredText(node, FIELD_ID);
        final String name = requiredText(node, FIELD_NAME);
        final JsonNode inputNode = node.get(FIELD_INPUT);
        final Map<String, Object> input = inputNode == null || inputNode.isNull()
                ? Map.of()
                : MAPPER.convertValue(inputNode, MAP_TYPE);
        return ToolUse.of(id, name, input);
    }

    private ToolUseResult decodeToolResult(JsonNode node) {
        final String toolUseId = requiredText(node, FIELD_TOOL_USE_ID);
        final String content = requiredText(node, FIELD_CONTENT);
        final boolean isError = node.path(FIELD_IS_ERROR).asBoolean(false);
        return isError ? ToolUseResult.error(toolUseId, content) : ToolUseResult.success(toolUseId, content);
    }

    private MessageArtifact decodeArtifact(JsonNode node) {
        final MessageArtifact.Builder builder = MessageArtifact.builder().path(requiredText(node, FIELD_PATH))
                .fileName(requiredText(node, FIELD_FILE_NAME)).size(node.path(FIELD_SIZE).asLong(0L));
        if (node.hasNonNull(FIELD_MIME_TYPE)) {
            builder.mimeType(node.get(FIELD_MIME_TYPE).asText());
        }
        if (node.hasNonNull(FIELD_TOOL_USE_ID)) {
            builder.toolUseId(node.get(FIELD_TOOL_USE_ID).asText());
        }
        if (node.hasNonNull(FIELD_DOWNLOAD_TOKEN)) {
            builder.downloadToken(node.get(FIELD_DOWNLOAD_TOKEN).asText());
        }
        return builder.build();
    }

    private static Role decodeRole(String role) {
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new SessionSnapshotCodecException("Unknown message role: " + role, e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        final JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw new SessionSnapshotCodecException("Missing or non-textual field '" + field + "'");
        }
        return value.asText();
    }

    private static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static byte[] decodeBase64(String text) {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new SessionSnapshotCodecException("Invalid base64 payload: " + e.getMessage(), e);
        }
    }
}
