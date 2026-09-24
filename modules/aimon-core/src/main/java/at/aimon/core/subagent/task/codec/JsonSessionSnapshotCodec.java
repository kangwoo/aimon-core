package at.aimon.core.subagent.task.codec;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogManifestEntry;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SessionViewState;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.MessageArtifact;
import at.aimon.core.llm.ReasoningTrace;
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
 * with — plus, when it had any, the {@link at.aimon.core.agent.SubmitOptions} it was submitted under. Neither is
 * hand-mapped here: the options go to {@link SubmitOptionsCodec} and the input to {@link UserInputCodec}, which is
 * where the reasoning behind the input's own set of type tags now lives. Both used to be private methods of this
 * class, and both moved out when a second wire needed the same shape — the inbox, in the input's case.
 *
 * <p>
 * A message's {@link at.aimon.core.llm.ReasoningTrace}s are written as a {@code reasoning} array, and only when the
 * message has any — so a document produced before that field existed is byte-identical to one produced now. The
 * reverse direction is covered by the same forward tolerance the class already relies on: an old build reads five
 * field names and ignores everything else, so a {@code reasoning} array it has never heard of is skipped. That is why
 * {@link #FORMAT_VERSION} stays at 1; the argument is the {@code compactionFailureCount} one, below.
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
 * <h2>Two versions</h2>
 *
 * <p>
 * Version 1 is the document described above: a {@code messages} array and a rewind point stored as a message count.
 * Version 2 ({@link #FORMAT_VERSION_V2}) carries the whole {@link SessionLogState}: {@code nextSeq}, {@code floorSeq},
 * an {@code entries} array of {@code {seq, origin, message}}, a rewind point stored as a seq, and — when it leaves
 * anything out — a {@code viewState} object: {@code summarySpan} (the range, the summary text and the boundary's
 * metadata), {@code droppedRanges} ({@code {fromSeq, toSeq}} each) and {@code elisions} ({@code {seq, placeholder}}
 * each). Both versions are read; a version-2 document without {@code viewState} has an empty one.
 *
 * <p>
 * What is written is the later of the codec's write format ({@link SessionLogFormat#V1} unless constructed otherwise)
 * and the snapshot's own {@link SessionLogState#getFormat()}. The second half is the sticky upgrade: a state decoded
 * from a version-2 document says {@link SessionLogFormat#V2}, so it cannot be written back as version 1 — which would
 * drop its seqs and origins, and restart the seqs at 0 on the next read. Writing version 1 converts at the boundary:
 * the rewind point's seq becomes the number of carried entries before it, and seqs and origins are not written.
 *
 * <p>
 * The write gate being here, in the codec, is what makes it apply to every writer — the session record backends and
 * the subagent resume snapshots ({@code VfsSessionSnapshotStore}) alike.
 *
 * <p>
 * Stateless and thread-safe: the shared {@link ObjectMapper} is used only for tree building and text I/O.
 */
public final class JsonSessionSnapshotCodec implements SessionSnapshotCodec {

    /** The version-1 format: a plain message list. Written unless the write format or the snapshot asks for more. */
    public static final int FORMAT_VERSION = 1;

    /** The version-2 format: the whole session log state. */
    public static final int FORMAT_VERSION_V2 = 2;

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
    private static final String FIELD_SUBMIT_OPTIONS = "submitOptions";
    private static final String FIELD_SEQ = "seq";
    private static final String FIELD_NEXT_SEQ = "nextSeq";
    private static final String FIELD_FLOOR_SEQ = "floorSeq";
    private static final String FIELD_ENTRIES = "entries";
    private static final String FIELD_ORIGIN = "origin";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_VIEW_STATE = "viewState";
    private static final String FIELD_SUMMARY_SPAN = "summarySpan";
    private static final String FIELD_DROPPED_RANGES = "droppedRanges";
    private static final String FIELD_ELISIONS = "elisions";
    private static final String FIELD_FROM_SEQ = "fromSeq";
    private static final String FIELD_TO_SEQ = "toSeq";
    private static final String FIELD_SUMMARY_TEXT = "summaryText";
    private static final String FIELD_BOUNDARY_ID = "boundaryId";
    private static final String FIELD_TRIGGER = "trigger";
    private static final String FIELD_PRE_TOKEN_COUNT = "preTokenCount";
    private static final String FIELD_MESSAGES_SUMMARIZED = "messagesSummarized";
    private static final String FIELD_DISCOVERED_TOOL_NAMES = "discoveredToolNames";
    private static final String FIELD_PLACEHOLDER = "placeholder";
    private static final String FIELD_MANIFEST = "manifest";
    private static final String FIELD_SEGMENT_ID = "segmentId";
    private static final String FIELD_CONTENT_HASH = "contentHash";
    private static final String FIELD_ENTRY_COUNT = "entryCount";

    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_TOOL_USES = "toolUses";
    private static final String FIELD_TOOL_RESULTS = "toolResults";
    private static final String FIELD_ARTIFACTS = "artifacts";
    private static final String FIELD_REASONING = "reasoning";
    private static final String FIELD_PROVIDER = "provider";
    private static final String FIELD_PAYLOAD = "payload";

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

    private static final String SOURCE_BASE64 = "base64";
    private static final String SOURCE_URL = "url";

    private final SessionLogFormat writeFormat;

    /**
     * Creates a codec that writes version 1 unless a snapshot requires version 2.
     */
    public JsonSessionSnapshotCodec() {
        this(SessionLogFormat.V1);
    }

    /**
     * Creates a codec that writes at least {@code writeFormat}.
     *
     * @param writeFormat
     *            the format to write at least (must not be null)
     */
    public JsonSessionSnapshotCodec(SessionLogFormat writeFormat) {
        this.writeFormat = Objects.requireNonNull(writeFormat, "writeFormat cannot be null");
    }

    @Override
    public String encode(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        try {
            final SessionLogState state = snapshot.getLogState();
            final SessionLogFormat format = writeFormat.atLeast(state.getFormat());
            final ObjectNode root = MAPPER.createObjectNode();
            root.put(FIELD_VERSION, format.getWireVersion());
            root.put(FIELD_CONVERSATION_ID, snapshot.getSessionId().value());
            if (snapshot.getSystemPrompt() != null) {
                root.put(FIELD_SYSTEM_PROMPT, snapshot.getSystemPrompt());
            } else {
                root.putNull(FIELD_SYSTEM_PROMPT);
            }
            final long rewindAt;
            if (format == SessionLogFormat.V2) {
                root.put(FIELD_NEXT_SEQ, state.getNextSeq());
                root.put(FIELD_FLOOR_SEQ, state.getFloorSeq());
                final ArrayNode entries = root.putArray(FIELD_ENTRIES);
                for (SessionLogEntry entry : state.getEntries()) {
                    final ObjectNode node = entries.addObject();
                    node.put(FIELD_SEQ, entry.getSeq());
                    node.put(FIELD_ORIGIN, entry.getOrigin().name());
                    node.set(FIELD_MESSAGE, encodeMessage(entry.getMessage()));
                }
                rewindAt = state.getRewindPoint().map(SessionRewindPoint::getSeq).orElse(-1L);
                if (!state.getViewState().isEmpty()) {
                    root.set(FIELD_VIEW_STATE, encodeViewState(state.getViewState()));
                }
                // Written only when something was sealed, like the view state: a log that never sealed encodes as it
                // did before the manifest existed.
                if (!state.getManifest().isEmpty()) {
                    final ArrayNode lines = root.putArray(FIELD_MANIFEST);
                    for (SessionLogManifestEntry line : state.getManifest()) {
                        final ObjectNode node = lines.addObject();
                        node.put(FIELD_FROM_SEQ, line.getFromSeq());
                        node.put(FIELD_TO_SEQ, line.getToSeq());
                        node.put(FIELD_SEGMENT_ID, line.getSegmentId().value());
                        node.put(FIELD_CONTENT_HASH, line.getContentHash());
                        node.put(FIELD_ENTRY_COUNT, line.getEntryCount());
                    }
                }
            } else {
                final ArrayNode messages = root.putArray(FIELD_MESSAGES);
                for (Message message : state.getMessages()) {
                    messages.add(encodeMessage(message));
                }
                // Version 1 stores a position, and version 1 reads positions back as seqs 0..n-1 — so the position is
                // the number of carried entries before the point.
                rewindAt = state.getRewindPoint().map(point -> (long) state.countBefore(point.getSeq())).orElse(-1L);
            }
            // Written only when there is one, so a snapshot with nothing to retry encodes exactly as it did before
            // this field existed. A reader that predates it ignores it; this reader defaults it to absent, which is
            // the truthful answer for a document written when sessions could not be rewound at all.
            if (state.getRewindPoint().isPresent()) {
                final SessionRewindPoint point = state.getRewindPoint().get();
                final ObjectNode node = root.putObject(FIELD_REWIND_POINT);
                node.put(format == SessionLogFormat.V2 ? FIELD_SEQ : FIELD_MESSAGE_COUNT, rewindAt);
                node.set(FIELD_USER_INPUT, UserInputCodec.encode(point.getUserInput()));
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
            if (version != FORMAT_VERSION && version != FORMAT_VERSION_V2) {
                throw new SessionSnapshotCodecException("Unsupported session snapshot format version: " + version
                        + " (expected " + FORMAT_VERSION + " or " + FORMAT_VERSION_V2 + ")");
            }
            final SessionId sessionId = SessionId.of(requiredText(root, FIELD_CONVERSATION_ID));
            final String systemPrompt = root.hasNonNull(FIELD_SYSTEM_PROMPT)
                    ? root.get(FIELD_SYSTEM_PROMPT).asText()
                    : null;
            final SessionLogState state = version == FORMAT_VERSION_V2 ? decodeV2(root) : decodeV1(root);
            return SessionSnapshot.fromLog(sessionId, systemPrompt, state);
        } catch (SessionSnapshotCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new SessionSnapshotCodecException("Failed to decode session snapshot: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a version-1 document: the messages migrate to seqs {@code 0..n-1}, all {@link LogOrigin#CONVERSATION}.
     */
    private SessionLogState decodeV1(JsonNode root) {
        final List<Message> messages = new ArrayList<>();
        final JsonNode messagesNode = root.get(FIELD_MESSAGES);
        if (messagesNode != null && messagesNode.isArray()) {
            for (JsonNode messageNode : messagesNode) {
                messages.add(decodeMessage(messageNode));
            }
        }
        final JsonNode pointNode = root.get(FIELD_REWIND_POINT);
        final long keep = pointNode == null ? -1 : pointNode.path(FIELD_MESSAGE_COUNT).asLong(-1);
        if (pointNode != null && pointNode.isObject() && (keep < 0 || keep > messages.size())) {
            throw new SessionSnapshotCodecException("Rewind point keeps " + keep + " of " + messages.size()
                    + " messages, which is not a position in this transcript");
        }
        return SessionLogState.ofMessages(messages, decodeRewindPoint(pointNode, keep));
    }

    /**
     * Reads a version-2 document into the state it was written from. The state's own invariants — ascending seqs
     * within {@code [floorSeq, nextSeq)}, a rewind point within {@code [floorSeq, nextSeq]} — are the document's
     * validation: a document that breaks one was not written by this codec.
     */
    private SessionLogState decodeV2(JsonNode root) {
        final long nextSeq = requiredLong(root, FIELD_NEXT_SEQ);
        final long floorSeq = requiredLong(root, FIELD_FLOOR_SEQ);
        final List<SessionLogEntry> entries = new ArrayList<>();
        final JsonNode entriesNode = root.get(FIELD_ENTRIES);
        if (entriesNode != null && entriesNode.isArray()) {
            for (JsonNode entryNode : entriesNode) {
                if (entryNode == null || !entryNode.isObject()) {
                    throw new SessionSnapshotCodecException("Log entry is not a JSON object");
                }
                entries.add(SessionLogEntry.of(requiredLong(entryNode, FIELD_SEQ),
                        decodeMessage(entryNode.get(FIELD_MESSAGE)),
                        decodeOrigin(requiredText(entryNode, FIELD_ORIGIN))));
            }
        }
        final JsonNode pointNode = root.get(FIELD_REWIND_POINT);
        final long at = pointNode == null ? -1 : pointNode.path(FIELD_SEQ).asLong(-1);
        if (pointNode != null && pointNode.isObject() && (at < floorSeq || at > nextSeq)) {
            throw new SessionSnapshotCodecException(
                    "Rewind point seq " + at + " lies outside [floorSeq=" + floorSeq + ", nextSeq=" + nextSeq + "]");
        }
        try {
            return SessionLogState.builder().entries(entries).nextSeq(nextSeq).floorSeq(floorSeq)
                    .rewindPoint(decodeRewindPoint(pointNode, at)).format(SessionLogFormat.V2)
                    .viewState(decodeViewState(root.get(FIELD_VIEW_STATE)))
                    .manifest(decodeManifest(root.get(FIELD_MANIFEST))).build();
        } catch (IllegalArgumentException e) {
            throw new SessionSnapshotCodecException("Inconsistent session log: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a version-2 manifest. Absent means nothing was sealed. Bounds, overlap and "hidden by the view" are
     * checked by {@link SessionLogState}'s own invariants.
     */
    private static List<SessionLogManifestEntry> decodeManifest(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new SessionSnapshotCodecException("manifest is not a JSON array");
        }
        final List<SessionLogManifestEntry> lines = new ArrayList<>();
        for (JsonNode line : node) {
            if (line == null || !line.isObject()) {
                throw new SessionSnapshotCodecException("Manifest line is not a JSON object");
            }
            lines.add(SessionLogManifestEntry.builder().fromSeq(requiredLong(line, FIELD_FROM_SEQ))
                    .toSeq(requiredLong(line, FIELD_TO_SEQ))
                    .segmentId(SegmentId.of(requiredText(line, FIELD_SEGMENT_ID)))
                    .contentHash(requiredText(line, FIELD_CONTENT_HASH))
                    .entryCount((int) requiredLong(line, FIELD_ENTRY_COUNT)).build());
        }
        return lines;
    }

    /**
     * Encodes log entries — seq, origin and message — with the message encoding the transcript uses. This is the
     * payload of a sealed segment (session-log §5.6); backends store it as an opaque string.
     *
     * @param entries
     *            the entries (must not be null)
     * @return the encoded entries (never null)
     * @throws SessionSnapshotCodecException
     *             if encoding fails
     */
    public String encodeEntries(List<SessionLogEntry> entries) {
        Objects.requireNonNull(entries, "entries cannot be null");
        try {
            final ObjectNode root = MAPPER.createObjectNode();
            root.put(FIELD_VERSION, FORMAT_VERSION_V2);
            final ArrayNode array = root.putArray(FIELD_ENTRIES);
            for (SessionLogEntry entry : entries) {
                final ObjectNode node = array.addObject();
                node.put(FIELD_SEQ, entry.getSeq());
                node.put(FIELD_ORIGIN, entry.getOrigin().name());
                node.set(FIELD_MESSAGE, encodeMessage(entry.getMessage()));
            }
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new SessionSnapshotCodecException("Failed to encode log entries: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes what {@link #encodeEntries(List)} wrote.
     *
     * @param encoded
     *            the encoded entries (must not be null)
     * @return the entries in stored order (never null)
     * @throws SessionSnapshotCodecException
     *             if the payload is not a readable entry list
     */
    public List<SessionLogEntry> decodeEntries(String encoded) {
        Objects.requireNonNull(encoded, "encoded cannot be null");
        try {
            final JsonNode root = MAPPER.readTree(encoded);
            if (root == null || !root.isObject() || root.path(FIELD_VERSION).asInt(-1) != FORMAT_VERSION_V2) {
                throw new SessionSnapshotCodecException("Encoded log entries are not a version-2 entry list");
            }
            final List<SessionLogEntry> entries = new ArrayList<>();
            final JsonNode array = root.get(FIELD_ENTRIES);
            if (array == null || !array.isArray()) {
                throw new SessionSnapshotCodecException("Encoded log entries carry no entry array");
            }
            for (JsonNode entryNode : array) {
                if (entryNode == null || !entryNode.isObject()) {
                    throw new SessionSnapshotCodecException("Log entry is not a JSON object");
                }
                entries.add(SessionLogEntry.of(requiredLong(entryNode, FIELD_SEQ),
                        decodeMessage(entryNode.get(FIELD_MESSAGE)),
                        decodeOrigin(requiredText(entryNode, FIELD_ORIGIN))));
            }
            return entries;
        } catch (SessionSnapshotCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new SessionSnapshotCodecException("Failed to decode log entries: " + e.getMessage(), e);
        }
    }

    private static ObjectNode encodeViewState(SessionViewState viewState) {
        final ObjectNode node = MAPPER.createObjectNode();
        viewState.getSummarySpan().ifPresent(span -> {
            final ObjectNode spanNode = node.putObject(FIELD_SUMMARY_SPAN);
            spanNode.put(FIELD_FROM_SEQ, span.getFromSeq());
            spanNode.put(FIELD_TO_SEQ, span.getToSeq());
            spanNode.put(FIELD_SUMMARY_TEXT, span.getSummaryText());
            spanNode.put(FIELD_BOUNDARY_ID, span.getBoundaryId());
            spanNode.put(FIELD_TRIGGER, span.getTrigger());
            spanNode.put(FIELD_PRE_TOKEN_COUNT, span.getPreTokenCount());
            spanNode.put(FIELD_MESSAGES_SUMMARIZED, span.getMessagesSummarized());
            final ArrayNode tools = spanNode.putArray(FIELD_DISCOVERED_TOOL_NAMES);
            span.getDiscoveredToolNames().forEach(tools::add);
        });
        final ArrayNode ranges = node.putArray(FIELD_DROPPED_RANGES);
        for (SeqRange range : viewState.getDroppedRanges()) {
            final ObjectNode rangeNode = ranges.addObject();
            rangeNode.put(FIELD_FROM_SEQ, range.getFromSeq());
            rangeNode.put(FIELD_TO_SEQ, range.getToSeq());
        }
        final ArrayNode elisions = node.putArray(FIELD_ELISIONS);
        viewState.getElisions().forEach((seq, placeholder) -> {
            final ObjectNode elisionNode = elisions.addObject();
            elisionNode.put(FIELD_SEQ, seq);
            elisionNode.put(FIELD_PLACEHOLDER, placeholder);
        });
        return node;
    }

    /**
     * Reads a version-2 view state. Absent means empty — the log leaves nothing out of its view. The seqs are checked
     * against the log by {@link SessionLogState}'s own invariants.
     */
    private static SessionViewState decodeViewState(JsonNode node) {
        if (node == null || node.isNull()) {
            return SessionViewState.empty();
        }
        if (!node.isObject()) {
            throw new SessionSnapshotCodecException("viewState is not a JSON object");
        }
        SummarySpan span = null;
        final JsonNode spanNode = node.get(FIELD_SUMMARY_SPAN);
        if (spanNode != null && !spanNode.isNull()) {
            final List<String> tools = new ArrayList<>();
            final JsonNode toolsNode = spanNode.get(FIELD_DISCOVERED_TOOL_NAMES);
            if (toolsNode != null && toolsNode.isArray()) {
                toolsNode.forEach(tool -> tools.add(tool.asText()));
            }
            span = SummarySpan.builder().fromSeq(requiredLong(spanNode, FIELD_FROM_SEQ))
                    .toSeq(requiredLong(spanNode, FIELD_TO_SEQ)).summaryText(requiredText(spanNode, FIELD_SUMMARY_TEXT))
                    .boundaryId(requiredText(spanNode, FIELD_BOUNDARY_ID))
                    .trigger(requiredText(spanNode, FIELD_TRIGGER))
                    .preTokenCount(spanNode.path(FIELD_PRE_TOKEN_COUNT).asInt(0))
                    .messagesSummarized(spanNode.path(FIELD_MESSAGES_SUMMARIZED).asInt(0)).discoveredToolNames(tools)
                    .build();
        }
        final List<SeqRange> ranges = new ArrayList<>();
        final JsonNode rangesNode = node.get(FIELD_DROPPED_RANGES);
        if (rangesNode != null && rangesNode.isArray()) {
            for (JsonNode rangeNode : rangesNode) {
                ranges.add(SeqRange.of(requiredLong(rangeNode, FIELD_FROM_SEQ), requiredLong(rangeNode, FIELD_TO_SEQ)));
            }
        }
        final SortedMap<Long, String> elisions = new TreeMap<>();
        final JsonNode elisionsNode = node.get(FIELD_ELISIONS);
        if (elisionsNode != null && elisionsNode.isArray()) {
            for (JsonNode elisionNode : elisionsNode) {
                elisions.put(requiredLong(elisionNode, FIELD_SEQ), requiredText(elisionNode, FIELD_PLACEHOLDER));
            }
        }
        return SessionViewState.of(span, ranges, elisions);
    }

    /**
     * Reads the rewind point's input and options, once its position has been checked against the log it arrived
     * with.
     *
     * <p>
     * A position that is not in the log is not a document this codec can honour: rewinding to it would either throw
     * or silently keep the whole turn. The pair is written in one document by one writer, so a mismatch means the
     * document is corrupt — the callers refuse it before this is reached.
     */
    private SessionRewindPoint decodeRewindPoint(JsonNode node, long seq) {
        if (node == null || !node.isObject()) {
            return null;
        }
        // Unlike the position, the input describes something optional. The position addresses the log that arrived
        // with it, so a mismatch means the document is inconsistent and refusing is the only honest answer. An input
        // this
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
            return SessionRewindPoint.of(seq, UserInputCodec.decode(userInput),
                    SubmitOptionsCodec.decode(node.get(FIELD_SUBMIT_OPTIONS)));
        } catch (SessionSnapshotCodecException e) {
            // Refusing rather than degrading is the right trade here and the opposite of what the session inbox
            // does with the same failure — and the inbox catches a wider set than this, because there the cost of
            // being surprised is a destroyed batch. Here a point has no text rendering to fall back to, and a lost
            // retry is one the user can re-issue. UserInputCodec.decodeOrText carries the full comparison.
            log.debug("Rewind point holds a user input this build cannot replay ({}); treating the turn as not"
                    + " retryable", e.getMessage());
            return null;
        }
    }

    private static LogOrigin decodeOrigin(String origin) {
        try {
            return LogOrigin.valueOf(origin);
        } catch (IllegalArgumentException e) {
            throw new SessionSnapshotCodecException("Unknown log entry origin: " + origin, e);
        }
    }

    private static long requiredLong(JsonNode node, String field) {
        final JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || !value.isIntegralNumber()) {
            throw new SessionSnapshotCodecException("Missing or non-integral field '" + field + "'");
        }
        return value.asLong();
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
        if (message.hasReasoningTraces()) {
            final ArrayNode traces = node.putArray(FIELD_REASONING);
            for (ReasoningTrace trace : message.getReasoningTraces()) {
                traces.add(encodeReasoningTrace(trace));
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

    private ObjectNode encodeReasoningTrace(ReasoningTrace trace) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put(FIELD_PROVIDER, trace.getProviderName());
        node.put(FIELD_PAYLOAD, trace.getPayload());
        trace.getToolUseId().ifPresent(value -> node.put(FIELD_TOOL_USE_ID, value));
        return node;
    }

    private ReasoningTrace decodeReasoningTrace(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new SessionSnapshotCodecException("Reasoning trace entry is not a JSON object");
        }
        final ReasoningTrace.Builder builder = ReasoningTrace.builder().providerName(requiredText(node, FIELD_PROVIDER))
                .payload(requiredText(node, FIELD_PAYLOAD));
        if (node.hasNonNull(FIELD_TOOL_USE_ID)) {
            builder.toolUseId(node.get(FIELD_TOOL_USE_ID).asText());
        }
        return builder.build();
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
        // Absent for every document written before reasoning traces existed, which is exactly the five-argument
        // restore's behaviour: an empty list.
        final List<ReasoningTrace> reasoningTraces = new ArrayList<>();
        final JsonNode reasoningNode = node.get(FIELD_REASONING);
        if (reasoningNode != null && reasoningNode.isArray()) {
            for (JsonNode traceNode : reasoningNode) {
                reasoningTraces.add(decodeReasoningTrace(traceNode));
            }
        }
        return Message.restore(role, content, toolUses, results, artifacts, reasoningTraces);
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
