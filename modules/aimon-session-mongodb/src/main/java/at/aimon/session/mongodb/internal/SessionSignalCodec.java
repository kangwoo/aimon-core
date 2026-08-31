package at.aimon.session.mongodb.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;

/**
 * Codec between {@link SessionSignal} and the BSON {@link Document} written to the capped
 * {@code conversation_signals} collection (design §4.2).
 *
 * <p>
 * The envelope shape is intentionally narrow — {@code sessionId / kind / originNodeId / payload} — and
 * {@code payload} is a {@code Map<String, Object>} containing only JSON-friendly primitives the manager places there
 * (signal-specific fields like {@code reason}, {@code messageId}). Polymorphic round-trip of
 * {@code AgentExecutionEvent}
 * is out of scope; the manager-side relay projects events to a Map for cross-node EVENT broadcast.
 */
public final class SessionSignalCodec {

    public SessionSignalCodec() {
    }

    /**
     * Encodes the envelope to a BSON document, omitting {@code _id} and {@code createdAt} which the caller stamps at
     * insert time.
     */
    public Document encode(SessionSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        final Document doc = new Document();
        doc.append(DocumentKeys.F_CONVERSATION_ID, signal.getSessionId().value());
        doc.append(DocumentKeys.F_KIND, signal.getKind().name());
        doc.append(DocumentKeys.F_ORIGIN_NODE_ID, signal.getOriginNodeId());
        doc.append(DocumentKeys.F_PAYLOAD, mapToDocument(signal.getPayload()));
        return doc;
    }

    public SessionSignal decode(Document doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        final SessionSignal.Builder builder = SessionSignal.builder()
                .sessionId(SessionId.of(doc.getString(DocumentKeys.F_CONVERSATION_ID)))
                .kind(SignalKind.valueOf(doc.getString(DocumentKeys.F_KIND)))
                .originNodeId(doc.getString(DocumentKeys.F_ORIGIN_NODE_ID));
        final Document payload = doc.get(DocumentKeys.F_PAYLOAD, Document.class);
        if (payload != null) {
            builder.payload(documentToMap(payload));
        }
        return builder.build();
    }

    private static Document mapToDocument(Map<String, Object> map) {
        final Document out = new Document();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            out.append(e.getKey(), encodeValue(e.getValue()));
        }
        return out;
    }

    private static Object encodeValue(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Map<?, ?> m) {
            final Document inner = new Document();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                inner.append(String.valueOf(e.getKey()), encodeValue(e.getValue()));
            }
            return inner;
        }
        if (v instanceof Iterable<?> it) {
            final List<Object> list = new ArrayList<>();
            for (Object child : it) {
                list.add(encodeValue(child));
            }
            return list;
        }
        return v.toString();
    }

    private static Map<String, Object> documentToMap(Document doc) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            out.put(e.getKey(), decodeValue(e.getValue()));
        }
        return out;
    }

    private static Object decodeValue(Object v) {
        if (v instanceof Document d) {
            return documentToMap(d);
        }
        if (v instanceof List<?> list) {
            final List<Object> out = new ArrayList<>(list.size());
            for (Object child : list) {
                out.add(decodeValue(child));
            }
            return out;
        }
        return v;
    }
}
