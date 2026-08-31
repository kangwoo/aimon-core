/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.file.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.Workspace;

/**
 * Boundary codec that converts the memory domain types to and from
 * Jackson {@link JsonNode}s. Keeps the file module's wire format explicit and
 * isolates the core types from Jackson annotations.
 *
 * <p>
 * The shape of every field is intentional and stable: changing it requires a
 * versioned migration (the events written today must replay tomorrow).
 */
public final class MemoryJsonCodec {

    private final ObjectMapper objectMapper;

    public MemoryJsonCodec() {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public ObjectNode newObject() {
        return objectMapper.createObjectNode();
    }

    public ArrayNode newArray() {
        return objectMapper.createArrayNode();
    }

    public String writeAsString(JsonNode node) {
        Objects.requireNonNull(node, "node cannot be null");
        try {
            return objectMapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise JSON node", e);
        }
    }

    public JsonNode readTree(String json) {
        Objects.requireNonNull(json, "json cannot be null");
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed JSON line", e);
        }
    }

    // ---------------------------------------------------------------------
    // Workspace
    // ---------------------------------------------------------------------

    public ObjectNode workspaceToJson(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", workspace.getId());
        node.put("displayName", workspace.getDisplayName());
        node.put("createdAt", workspace.getCreatedAt().toString());
        node.set("metadata", stringMapToJson(workspace.getMetadata()));
        return node;
    }

    public Workspace workspaceFromJson(JsonNode node) {
        Objects.requireNonNull(node, "node cannot be null");
        return Workspace.builder().id(textRequired(node, "id")).displayName(textRequired(node, "displayName"))
                .createdAt(Instant.parse(textRequired(node, "createdAt")))
                .metadata(jsonToStringMap(node.get("metadata"))).build();
    }

    // ---------------------------------------------------------------------
    // Principal / PeerView
    // ---------------------------------------------------------------------

    public ObjectNode principalToJson(Principal principal) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", principal.getType().name());
        node.put("id", principal.getId());
        node.put("displayName", principal.getDisplayName());
        return node;
    }

    public Principal principalFromJson(JsonNode node) {
        Principal.Type type = Principal.Type.valueOf(textRequired(node, "type"));
        String id = textRequired(node, "id");
        String displayName = textRequired(node, "displayName");
        return Principal.builder().type(type).id(id).displayName(displayName).build();
    }

    public ObjectNode peerViewToJson(PeerView peer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("workspace", workspaceToJson(peer.getWorkspace()));
        node.set("principal", principalToJson(peer.getPrincipal()));
        return node;
    }

    public PeerView peerViewFromJson(JsonNode node) {
        Workspace workspace = workspaceFromJson(node.get("workspace"));
        Principal principal = principalFromJson(node.get("principal"));
        return PeerView.of(workspace, principal);
    }

    // ---------------------------------------------------------------------
    // ObservationId
    // ---------------------------------------------------------------------

    public ObjectNode observationIdToJson(ObservationId id) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workspaceId", id.getWorkspaceId());
        node.put("localId", id.getLocalId());
        return node;
    }

    public ObservationId observationIdFromJson(JsonNode node) {
        return ObservationId.of(textRequired(node, "workspaceId"), textRequired(node, "localId"));
    }

    // ---------------------------------------------------------------------
    // Observation
    // ---------------------------------------------------------------------

    public ObjectNode observationToJson(Observation obs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("id", observationIdToJson(obs.getId()));
        node.set("subject", peerViewToJson(obs.getSubject()));
        node.set("observer", peerViewToJson(obs.getObserver()));
        node.put("content", obs.getContent());
        node.put("type", obs.getType().name());
        ArrayNode sources = objectMapper.createArrayNode();
        for (String s : obs.getSourceMessageIds()) {
            sources.add(s);
        }
        node.set("sourceMessageIds", sources);
        node.put("createdAt", obs.getCreatedAt().toString());
        node.put("confidence", obs.getConfidence());
        node.set("metadata", stringMapToJson(obs.getMetadata()));
        return node;
    }

    public Observation observationFromJson(JsonNode node) {
        Observation.Builder builder = Observation.builder().id(observationIdFromJson(node.get("id")))
                .subject(peerViewFromJson(node.get("subject"))).observer(peerViewFromJson(node.get("observer")))
                .content(textRequired(node, "content")).type(ObservationType.valueOf(textRequired(node, "type")))
                .sourceMessageIds(jsonArrayToStringList(node.get("sourceMessageIds")))
                .createdAt(Instant.parse(textRequired(node, "createdAt"))).confidence(node.get("confidence").asDouble())
                .metadata(jsonToStringMap(node.get("metadata")));
        return builder.build();
    }

    // ---------------------------------------------------------------------
    // Representation
    // ---------------------------------------------------------------------

    public ObjectNode representationToJson(Representation rep) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("subject", peerViewToJson(rep.getSubject()));
        rep.getObserver().ifPresent(o -> node.set("observer", peerViewToJson(o)));
        rep.getSessionId().ifPresent(sid -> node.put("sessionId", sid));
        ArrayNode obs = objectMapper.createArrayNode();
        for (Observation o : rep.getObservations()) {
            obs.add(observationToJson(o));
        }
        node.set("observations", obs);
        node.put("summary", rep.getSummary());
        node.put("generatedAt", rep.getGeneratedAt().toString());
        node.put("tokenCount", rep.getTokenCount());
        return node;
    }

    public Representation representationFromJson(JsonNode node) {
        Representation.Builder builder = Representation.builder().subject(peerViewFromJson(node.get("subject")))
                .summary(textRequired(node, "summary")).generatedAt(Instant.parse(textRequired(node, "generatedAt")))
                .tokenCount(node.get("tokenCount").asInt());
        if (node.has("observer") && !node.get("observer").isNull()) {
            builder.observer(peerViewFromJson(node.get("observer")));
        }
        if (node.has("sessionId") && !node.get("sessionId").isNull()) {
            builder.sessionId(node.get("sessionId").asText());
        }
        List<Observation> observations = new ArrayList<>();
        JsonNode obsArray = node.get("observations");
        if (obsArray != null && obsArray.isArray()) {
            for (JsonNode item : obsArray) {
                observations.add(observationFromJson(item));
            }
        }
        builder.observations(observations);
        return builder.build();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private ObjectNode stringMapToJson(Map<String, String> map) {
        ObjectNode node = objectMapper.createObjectNode();
        for (Map.Entry<String, String> e : map.entrySet()) {
            node.put(e.getKey(), e.getValue());
        }
        return node;
    }

    private static Map<String, String> jsonToStringMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            map.put(e.getKey(), e.getValue().asText());
        }
        return map;
    }

    private static List<String> jsonArrayToStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            out.add(item.asText());
        }
        return out;
    }

    private static String textRequired(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return v.asText();
    }
}
