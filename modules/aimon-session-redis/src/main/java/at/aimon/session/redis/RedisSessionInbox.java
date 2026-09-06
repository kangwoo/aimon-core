package at.aimon.session.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionInboxException;
import at.aimon.core.agent.session.inbox.CollectedBatch;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.inbox.UnreadableEntry;
import at.aimon.session.redis.internal.InboundMessageCodec;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link SessionInbox} per design §5.6.
 *
 * <p>
 * Each session maps to one Redis Stream per priority tier:
 *
 * <pre>
 *   aimon:inbox:{convId}:NOW
 *   aimon:inbox:{convId}:NEXT
 *   aimon:inbox:{convId}:LATER
 * </pre>
 *
 * <p>
 * <b>deliver</b> performs {@code XADD} with a single field {@code "p"} carrying the JSON-encoded envelope; the returned
 * stream entry id (e.g. {@code "1700000000000-0"}) is wrapped as {@link InboundMessageId} and surfaced to the manager.
 *
 * <p>
 * <b>collect</b> walks the tiers in declared order ({@code NOW → NEXT → LATER}) up to {@code maxPriority} inclusive and
 * for each tier runs a Lua script that {@code XRANGE}s the entire stream and {@code XDEL}s every returned id atomically
 * (deliveries that arrive between the XRANGE and XDEL of a single Lua execution are not visible — they survive for the
 * next collect). Within a tier the natural stream id ordering provides the FIFO guarantee; across tiers, walking by
 * declared order provides priority ordering.
 *
 * <p>
 * Idempotency / dedup is delegated to {@code IdempotencyStore} (design §9.2). This class never deduplicates by message
 * content.
 */
public final class RedisSessionInbox implements SessionInbox {

    public static final String DEFAULT_KEY_PREFIX = "aimon:inbox";

    private static final Logger log = LoggerFactory.getLogger(RedisSessionInbox.class);
    private static final String FIELD = "p";

    private static final String COLLECT_SCRIPT = "local entries = redis.call('XRANGE', KEYS[1], '-', '+') "
            + "if #entries == 0 then return {} end " + "local result = {} " + "local ids = {} "
            + "for i, entry in ipairs(entries) do " + "  ids[#ids+1] = entry[1] " + "  result[#result+1] = entry[1] "
            + "  result[#result+1] = entry[2][2] " + "end " + "redis.call('XDEL', KEYS[1], unpack(ids)) "
            + "return result";

    private final RedisCommands<String, String> commands;
    private final InboundMessageCodec codec;
    private final String keyPrefix;

    public RedisSessionInbox(StatefulRedisConnection<String, String> connection) {
        this(connection, defaultMapper(), DEFAULT_KEY_PREFIX);
    }

    public RedisSessionInbox(StatefulRedisConnection<String, String> connection, ObjectMapper mapper,
            String keyPrefix) {
        Objects.requireNonNull(connection, "connection must not be null");
        this.commands = connection.sync();
        this.codec = new InboundMessageCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
    }

    @Override
    public InboundMessageId deliver(InboundMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        final String key = streamKey(message.getSessionId(), message.getPriority());
        final String payload = codec.encode(message);
        try {
            final String entryId = commands.xadd(key, Map.of(FIELD, payload));
            log.debug("Delivered to {} (entryId={})", key, entryId);
            return InboundMessageId.of(entryId);
        } catch (RedisException e) {
            throw new SessionInboxException("Redis error delivering to " + message.getSessionId(), e);
        }
    }

    @Override
    public CollectedBatch collect(SessionId id, QueuedInputPriority maxPriority) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(maxPriority, "maxPriority must not be null");
        try {
            final List<InboundMessage> all = new ArrayList<>();
            final List<UnreadableEntry> unreadable = new ArrayList<>();
            for (QueuedInputPriority tier : QueuedInputPriority.values()) {
                if (tier.ordinal() > maxPriority.ordinal()) {
                    break;
                }
                collectTier(id, tier, all, unreadable);
            }
            return CollectedBatch.of(all, unreadable);
        } catch (RedisException e) {
            throw new SessionInboxException("Redis error collecting from " + id, e);
        }
    }

    @Override
    public boolean isEmpty(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            for (QueuedInputPriority tier : QueuedInputPriority.values()) {
                if (commands.xlen(streamKey(id, tier)) > 0) {
                    return false;
                }
            }
            return true;
        } catch (RedisException e) {
            throw new SessionInboxException("Redis error sizing " + id, e);
        }
    }

    @Override
    public void purge(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            for (QueuedInputPriority tier : QueuedInputPriority.values()) {
                commands.del(streamKey(id, tier));
            }
        } catch (RedisException e) {
            throw new SessionInboxException("Redis error purging " + id, e);
        }
    }

    private void collectTier(SessionId id, QueuedInputPriority tier, List<InboundMessage> out,
            List<UnreadableEntry> unreadable) {
        final String key = streamKey(id, tier);
        final List<Object> raw = commands.eval(COLLECT_SCRIPT, ScriptOutputType.MULTI, key);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            final String entryId = String.valueOf(raw.get(i));
            final String payload = String.valueOf(raw.get(i + 1));
            try {
                out.add(codec.decode(payload, entryId));
            } catch (RuntimeException e) {
                // The boundary is the entry, not the exception type. The Lua above has already XDELed this batch, so
                // letting one entry's failure out of here destroys every message the call collected — and the type it
                // arrives as is not this codec's to predict: Principal.Type.valueOf throws a plain
                // IllegalArgumentException, and the next value object to grow a rule will throw something else again.
                unreadable.add(codec.recoverAddress(payload, entryId, e));
                log.warn(
                        "Dropping an inbox entry this build cannot decode [session={}, entryId={}]: {}."
                                + " It is already out of the inbox, so its turn will not run anywhere.",
                        id, entryId, e.toString());
            }
        }
    }

    private String streamKey(SessionId id, QueuedInputPriority tier) {
        return keyPrefix + ":" + id.value() + ":" + tier.name();
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
