package at.aimon.core.subagent.task.codec;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
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

/**
 * The JSON shape of a {@link UserInput}, for every wire that has to carry one.
 *
 * <p>
 * <b>Why this is a class and not four private methods.</b> It was four private methods, on
 * {@link JsonSessionSnapshotCodec}, written so a {@code SessionRewindPoint} could replay the input its turn was
 * submitted with. The second consumer is the session inbox: a submission forwarded to another node used to be a
 * {@code String}, so an image or a document survived only as long as the turn stayed on the host that received it.
 * Rather than hand-map the five shapes a second time — the situation {@link SubmitOptionsCodec} exists to prevent one
 * layer up, and the one this repository has already paid for once — the encoding moved here unchanged and the
 * snapshot codec became its first caller.
 *
 * <p>
 * It lives beside {@link JsonSessionSnapshotCodec} for the same reason {@link SubmitOptionsCodec} does: that is
 * where it came from, and the package name records a first consumer rather than a constraint on later ones.
 *
 * <p>
 * <b>The input, not the message built from it.</b> The type tags are this codec's own — {@code file} and
 * {@code multimodal} have no {@link at.aimon.core.llm.content.ContentBlock} counterpart, and {@code image} means an
 * {@link ImageInput}, which is always inline bytes and so carries no {@code source} discriminator. Encoding the
 * message instead would be one shape fewer to map and would lose the only thing that makes a replay or a routed turn
 * faithful: the message is a lossy rendering of the request, in which an image reads back as a text placeholder.
 *
 * <p>
 * <b>No mapper overload, unlike {@link SubmitOptionsCodec}.</b> That class takes one because two of its five fields
 * are {@code Map<String, Object>}, so a registered module is the difference between an {@code Instant} landing as an
 * ISO-8601 string and landing as an object. Every leaf here is a {@code String} — a type tag, a MIME type, a file
 * name, or base64 text — so no mapper configuration can reach this wire, and a parameter for one would be ceremony
 * that implies otherwise.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class UserInputCodec {

    /**
     * How deeply a {@code multimodal} input may nest before the document is treated as unreadable.
     *
     * <p>
     * The decode is recursive, and this family of codecs refuses by exception rather than by stack overflow. A real
     * request nests once or twice; anything near this bound is a corrupt or hostile document, not a user's.
     */
    public static final int MAX_NESTING = 32;

    /*
     * The field names and type tags, public for the same reason SubmitOptionsCodec's are: one representation of this
     * shape cannot be expressed in an ObjectNode. The MongoDB inbox stores BSON, and although this subtree — unlike a
     * SubmitOptions one — is strings all the way down and so would convert without loss, it stores the encoded text
     * instead (see InboundMessageCodec there). A test pinning literals against these constants can therefore say
     * whether a stored shape drifted, which a round-trip through one codec structurally cannot.
     */
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_MIME_TYPE = "mimeType";
    public static final String FIELD_FILE_NAME = "fileName";
    public static final String FIELD_DATA = "data";
    public static final String FIELD_INPUTS = "inputs";

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_AUDIO = "audio";
    public static final String TYPE_FILE = "file";
    public static final String TYPE_MULTIMODAL = "multimodal";

    /** Every {@code type} tag this codec writes — one per {@link at.aimon.core.agent.input.InputType}. */
    public static final Set<String> TYPE_TAGS = Set.of(TYPE_TEXT, TYPE_IMAGE, TYPE_AUDIO, TYPE_FILE, TYPE_MULTIMODAL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UserInputCodec() {
        // Utility class
    }

    /**
     * Encodes one input as the subtree a document embeds under a field of its own.
     *
     * @param input
     *            the input to encode (must not be null)
     * @return the encoded subtree, never null
     * @throws SessionSnapshotCodecException
     *             if {@code input} is an implementation this build cannot map
     */
    public static ObjectNode encode(UserInput input) {
        Objects.requireNonNull(input, "input cannot be null");
        final ObjectNode node = MAPPER.createObjectNode();
        if (input instanceof TextInput text) {
            node.put(FIELD_TYPE, TYPE_TEXT);
            node.put(FIELD_TEXT, text.getText());
        } else if (input instanceof ImageInput image) {
            node.put(FIELD_TYPE, TYPE_IMAGE);
            node.put(FIELD_MIME_TYPE, image.getMimeType());
            node.put(FIELD_DATA, encodeBase64(image.getData()));
        } else if (input instanceof AudioInput audio) {
            node.put(FIELD_TYPE, TYPE_AUDIO);
            node.put(FIELD_MIME_TYPE, audio.getMimeType());
            node.put(FIELD_DATA, encodeBase64(audio.getData()));
        } else if (input instanceof FileInput file) {
            node.put(FIELD_TYPE, TYPE_FILE);
            node.put(FIELD_MIME_TYPE, file.getMimeType());
            node.put(FIELD_FILE_NAME, file.getFileName());
            node.put(FIELD_DATA, encodeBase64(file.getData()));
        } else if (input instanceof MultimodalInput multimodal) {
            node.put(FIELD_TYPE, TYPE_MULTIMODAL);
            final ArrayNode inputs = node.putArray(FIELD_INPUTS);
            for (UserInput nested : multimodal.getInputs()) {
                inputs.add(encode(nested));
            }
        } else {
            throw new SessionSnapshotCodecException(
                    "Unsupported user input type for encoding: " + input.getClass().getName());
        }
        return node;
    }

    /**
     * Decodes a subtree previously written by {@link #encode(UserInput)}.
     *
     * <p>
     * <b>Malformed content is an exception, not a fallback.</b> An input this build cannot read is not a turn that
     * can be run with less of it: dropping the unreadable part and running the rest would run a different turn from
     * the one submitted, which is the failure the structured encoding exists to remove. Callers that would rather
     * lose the input than the document — a rewind point, whose whole value is one turn's retry — catch this and
     * decide that for themselves.
     *
     * @param node
     *            the subtree (must not be null and must be a JSON object)
     * @return the decoded input, never null
     * @throws SessionSnapshotCodecException
     *             if the subtree is absent, is not an object, names an unknown type, or nests past
     *             {@link #MAX_NESTING}
     */
    public static UserInput decode(JsonNode node) {
        return decodeAt(node, 0);
    }

    /**
     * Encodes one input as standalone JSON text.
     *
     * <p>
     * For a wire whose currency is not a Jackson tree. The MongoDB inbox is the one in this repository: its documents
     * are BSON, and although this all-string subtree would convert to a {@code org.bson.Document} without loss,
     * parsing it into one would buy queryability the inbox does not use — its indexed fields are top-level columns —
     * at the price of a second hand-written representation of this shape, which is what this class exists to avoid.
     *
     * @param input
     *            the input to encode (must not be null)
     * @return the encoded JSON text, never null
     * @throws SessionSnapshotCodecException
     *             if {@code input} is an implementation this build cannot map
     */
    public static String encodeToString(UserInput input) {
        try {
            return MAPPER.writeValueAsString(encode(input));
        } catch (JsonProcessingException e) {
            throw new SessionSnapshotCodecException("Cannot serialize the user input: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes JSON text previously written by {@link #encodeToString(UserInput)}.
     *
     * @param json
     *            the encoded text (must not be null)
     * @return the decoded input, never null
     * @throws SessionSnapshotCodecException
     *             if the text is not readable as an input subtree
     */
    public static UserInput decodeFromString(String json) {
        Objects.requireNonNull(json, "json cannot be null");
        try {
            return decode(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new SessionSnapshotCodecException("Cannot parse the encoded user input: " + e.getMessage(), e);
        }
    }

    private static UserInput decodeAt(JsonNode node, int depth) {
        if (node == null || !node.isObject()) {
            throw new SessionSnapshotCodecException("User input entry is not a JSON object");
        }
        if (depth > MAX_NESTING) {
            throw new SessionSnapshotCodecException(
                    "User input nests deeper than " + MAX_NESTING + " levels; refusing to decode it");
        }
        final String type = requiredText(node, FIELD_TYPE);
        return switch (type) {
            case TYPE_TEXT -> TextInput.of(requiredText(node, FIELD_TEXT));
            case TYPE_IMAGE ->
                ImageInput.of(decodeBase64(requiredText(node, FIELD_DATA)), requiredText(node, FIELD_MIME_TYPE));
            case TYPE_AUDIO ->
                AudioInput.of(decodeBase64(requiredText(node, FIELD_DATA)), requiredText(node, FIELD_MIME_TYPE));
            case TYPE_FILE -> FileInput.of(decodeBase64(requiredText(node, FIELD_DATA)),
                    requiredText(node, FIELD_MIME_TYPE), requiredText(node, FIELD_FILE_NAME));
            case TYPE_MULTIMODAL -> decodeMultimodal(node, depth);
            default -> throw new SessionSnapshotCodecException("Unknown user input type: " + type);
        };
    }

    private static UserInput decodeMultimodal(JsonNode node, int depth) {
        final JsonNode inputsNode = node.get(FIELD_INPUTS);
        if (inputsNode == null || !inputsNode.isArray() || inputsNode.isEmpty()) {
            throw new SessionSnapshotCodecException("Multimodal user input carries no inputs");
        }
        final List<UserInput> inputs = new ArrayList<>();
        for (JsonNode nested : inputsNode) {
            inputs.add(decodeAt(nested, depth + 1));
        }
        return MultimodalInput.of(inputs);
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
