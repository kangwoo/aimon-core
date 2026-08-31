package at.aimon.memory.postgres.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.Workspace;

/**
 * Jackson codec for the {@code mem_representation.payload_blob} JSONB column.
 *
 * <p>
 * The payload carries a self-contained snapshot of the {@link Representation}
 * so that a row can be hydrated without joining against {@code mem_observation}.
 * That immutability is deliberate: a representation is an append-only snapshot
 * of "what we knew about this subject at time T", and must survive the later
 * deletion of any underlying observation.
 *
 * <h3>JSON shape (version 1)</h3>
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "subject":  { "workspaceId": "ws", "principalType": "USER",
 *                 "principalId": "alice", "principalDisplayName": "Alice" },
 *   "observer": null | { ...same shape... },
 *   "sessionId": "s-1" | null,
 *   "summary":  "...",
 *   "tokenCount": 123,
 *   "generatedAt": "2026-04-28T10:00:00Z",
 *   "observations": [
 *     {
 *       "workspaceId": "ws",
 *       "localId":     "obs-1",
 *       "subject":  { ...peer... },
 *       "observer": { ...peer... },
 *       "content":  "...",
 *       "type":     "EXPLICIT",
 *       "sourceMessageIds": ["m1", "m2"],
 *       "createdAt": "...",
 *       "confidence": 0.7,
 *       "metadata":  { "k": "v" }
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class RepresentationRowCodec {

    /** Current payload schema version. Bump when the JSON shape changes. */
    public static final int SCHEMA_VERSION = 1;

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public RepresentationRowCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    public String encode(Representation representation) {
        Objects.requireNonNull(representation, "representation must not be null");
        try {
            final ObjectNode root = mapper.createObjectNode();
            root.put("version", SCHEMA_VERSION);
            root.set("subject", encodePeer(representation.getSubject()));
            if (representation.getObserver().isPresent()) {
                root.set("observer", encodePeer(representation.getObserver().get()));
            } else {
                root.putNull("observer");
            }
            if (representation.getSessionId().isPresent()) {
                root.put("sessionId", representation.getSessionId().get());
            } else {
                root.putNull("sessionId");
            }
            root.put("summary", representation.getSummary());
            root.put("tokenCount", representation.getTokenCount());
            root.put("generatedAt", representation.getGeneratedAt().toString());

            final ArrayNode obsArray = root.putArray("observations");
            for (Observation o : representation.getObservations()) {
                obsArray.add(encodeObservation(o));
            }
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode Representation payload", e);
        }
    }

    public Representation decode(String json) {
        Objects.requireNonNull(json, "json must not be null");
        try {
            final JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Representation payload is not a JSON object");
            }

            final PeerView subject = decodePeer(requireField(root, "subject"));
            final PeerView observer = root.hasNonNull("observer") ? decodePeer(root.get("observer")) : null;
            final String sessionId = root.hasNonNull("sessionId") ? root.get("sessionId").asText() : null;
            final String summary = requireField(root, "summary").asText();
            final int tokenCount = requireField(root, "tokenCount").asInt();
            final Instant generatedAt = Instant.parse(requireField(root, "generatedAt").asText());

            final List<Observation> observations = new ArrayList<>();
            final JsonNode obsArray = root.get("observations");
            if (obsArray != null && obsArray.isArray()) {
                final Iterator<JsonNode> it = obsArray.elements();
                while (it.hasNext()) {
                    observations.add(decodeObservation(it.next()));
                }
            }

            final Representation.Builder b = Representation.builder().subject(subject).observer(observer)
                    .sessionId(sessionId).observations(observations).summary(summary).generatedAt(generatedAt)
                    .tokenCount(tokenCount);
            return b.build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode Representation payload", e);
        }
    }

    // ---- peer / observation helpers --------------------------------------------------------

    private ObjectNode encodePeer(PeerView peer) {
        final ObjectNode out = mapper.createObjectNode();
        out.put("workspaceId", peer.getWorkspace().getId());
        out.put("workspaceDisplayName", peer.getWorkspace().getDisplayName());
        out.put("principalType", peer.getPrincipal().getType().name());
        out.put("principalId", peer.getPrincipal().getId());
        out.put("principalDisplayName", peer.getPrincipal().getDisplayName());
        return out;
    }

    private PeerView decodePeer(JsonNode node) {
        Objects.requireNonNull(node, "peer node must not be null");
        final String workspaceId = requireField(node, "workspaceId").asText();
        // Workspace inside the snapshot is rebuilt from id + best-effort display name.
        // The full Workspace row remains the source of truth — but the snapshot must
        // be self-contained, so we synthesize one here.
        final String workspaceDisplayName = node.hasNonNull("workspaceDisplayName")
                ? node.get("workspaceDisplayName").asText()
                : workspaceId;
        final Workspace ws = Workspace.builder().id(workspaceId).displayName(workspaceDisplayName)
                .createdAt(Instant.EPOCH).build();
        final Principal principal = Principal.builder()
                .type(Principal.Type.valueOf(requireField(node, "principalType").asText()))
                .id(requireField(node, "principalId").asText())
                .displayName(requireField(node, "principalDisplayName").asText()).build();
        return PeerView.of(ws, principal);
    }

    private ObjectNode encodeObservation(Observation o) {
        final ObjectNode out = mapper.createObjectNode();
        out.put("workspaceId", o.getId().getWorkspaceId());
        out.put("localId", o.getId().getLocalId());
        out.set("subject", encodePeer(o.getSubject()));
        out.set("observer", encodePeer(o.getObserver()));
        out.put("content", o.getContent());
        out.put("type", o.getType().name());
        out.set("sourceMessageIds", mapper.valueToTree(o.getSourceMessageIds()));
        out.put("createdAt", o.getCreatedAt().toString());
        out.put("confidence", o.getConfidence());
        out.set("metadata", mapper.valueToTree(o.getMetadata()));
        return out;
    }

    private Observation decodeObservation(JsonNode node) {
        final String workspaceId = requireField(node, "workspaceId").asText();
        final String localId = requireField(node, "localId").asText();
        final ObservationId id = ObservationId.of(workspaceId, localId);

        final List<String> sourceMessageIds = node.hasNonNull("sourceMessageIds")
                ? mapper.convertValue(node.get("sourceMessageIds"), STRING_LIST_TYPE)
                : List.of();

        final Map<String, String> metadata;
        if (node.hasNonNull("metadata")) {
            metadata = mapper.convertValue(node.get("metadata"), STRING_MAP_TYPE);
        } else {
            metadata = new HashMap<>();
        }

        return Observation.builder().id(id).subject(decodePeer(requireField(node, "subject")))
                .observer(decodePeer(requireField(node, "observer"))).content(requireField(node, "content").asText())
                .type(ObservationType.valueOf(requireField(node, "type").asText())).sourceMessageIds(sourceMessageIds)
                .createdAt(Instant.parse(requireField(node, "createdAt").asText()))
                .confidence(requireField(node, "confidence").asDouble()).metadata(metadata).build();
    }

    private static JsonNode requireField(JsonNode node, String name) {
        final JsonNode child = node.get(name);
        if (child == null || child.isNull()) {
            throw new IllegalStateException("Representation payload is missing required field: " + name);
        }
        return child;
    }
}
