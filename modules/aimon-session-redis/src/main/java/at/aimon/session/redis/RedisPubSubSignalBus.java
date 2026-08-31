package at.aimon.session.redis;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.session.redis.internal.SessionSignalCodec;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

/**
 * Redis pub/sub-backed {@link SessionSignalBus} per design §5.4.
 *
 * <p>
 * Signals are split across two channel namespaces keyed by {@link SessionId}:
 *
 * <ul>
 * <li><b>control</b> ({@code aimon:conv:control:{convId}}) — {@link SignalKind#INTERRUPT INTERRUPT},
 * {@link SignalKind#EVICT EVICT}, {@link SignalKind#MESSAGE_ENQUEUED MESSAGE_ENQUEUED}.
 * <li><b>events</b> ({@code aimon:conv:events:{convId}}) — high-frequency observability traffic:
 * {@link SignalKind#EVENT EVENT} and {@link SignalKind#STATUS STATUS}.
 * </ul>
 *
 * <p>
 * A single subscribe call listens to both channels for the requested session; consumers filter on
 * {@code signal.getKind()} if they only care about a subset (the control vs. events split is a fan-out optimization,
 * not a routing constraint at the SPI level).
 *
 * <p>
 * The bus uses two Lettuce connections — one normal {@link StatefulRedisConnection} for {@code publish}, one
 * {@link StatefulRedisPubSubConnection} for the listener. Both are owned by the caller; this class registers / removes
 * its listener at construction / {@link #close()} and never closes the connections themselves.
 *
 * <p>
 * Thread-safety: subscribers are stored in a per-session {@link CopyOnWriteArrayList} so the dispatcher iterates
 * without locks. Reference counting guards the SUBSCRIBE / UNSUBSCRIBE round-trips so a many-handlers-per-session
 * setup costs at most one Redis subscription per session.
 */
public final class RedisPubSubSignalBus implements SessionSignalBus, AutoCloseable {

    public static final String DEFAULT_KEY_PREFIX = "aimon:conv";

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubSignalBus.class);

    private final RedisCommands<String, String> publishCommands;
    private final StatefulRedisPubSubConnection<String, String> subscribeConnection;
    private final RedisPubSubCommands<String, String> subscribeCommands;
    private final SessionSignalCodec codec;
    private final String keyPrefix;
    private final RedisPubSubAdapter<String, String> listener;

    // @formatter:off
    private final ConcurrentMap<SessionId, List<Consumer<SessionSignal>>> handlers
            = new ConcurrentHashMap<>();
    // @formatter:on

    public RedisPubSubSignalBus(StatefulRedisConnection<String, String> publishConnection,
            StatefulRedisPubSubConnection<String, String> subscribeConnection) {
        this(publishConnection, subscribeConnection, defaultMapper(), DEFAULT_KEY_PREFIX);
    }

    public RedisPubSubSignalBus(StatefulRedisConnection<String, String> publishConnection,
            StatefulRedisPubSubConnection<String, String> subscribeConnection, ObjectMapper mapper, String keyPrefix) {
        Objects.requireNonNull(publishConnection, "publishConnection must not be null");
        this.subscribeConnection = Objects.requireNonNull(subscribeConnection, "subscribeConnection must not be null");
        this.publishCommands = publishConnection.sync();
        this.subscribeCommands = subscribeConnection.sync();
        this.codec = new SessionSignalCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        this.listener = new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                dispatch(channel, message);
            }
        };
        this.subscribeConnection.addListener(listener);
    }

    @Override
    public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        final boolean[] firstSubscriber = {false};
        handlers.compute(id, (k, list) -> {
            if (list == null) {
                firstSubscriber[0] = true;
                final List<Consumer<SessionSignal>> created = new CopyOnWriteArrayList<>();
                created.add(handler);
                return created;
            }
            list.add(handler);
            return list;
        });
        if (firstSubscriber[0]) {
            try {
                subscribeCommands.subscribe(controlChannel(id), eventsChannel(id));
            } catch (RedisException e) {
                handlers.computeIfPresent(id, (k, list) -> {
                    list.remove(handler);
                    return list.isEmpty() ? null : list;
                });
                throw new IllegalStateException("Failed to subscribe to Redis channels for " + id, e);
            }
        }
        return () -> unsubscribeOne(id, handler);
    }

    @Override
    public void publish(SessionSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        final String channel = channelFor(signal.getKind(), signal.getSessionId());
        final String json = codec.encode(signal);
        try {
            publishCommands.publish(channel, json);
        } catch (RedisException e) {
            throw new IllegalStateException(
                    "Redis error publishing " + signal.getKind() + " for " + signal.getSessionId(), e);
        }
    }

    @Override
    public void close() {
        subscribeConnection.removeListener(listener);
        handlers.clear();
    }

    private void unsubscribeOne(SessionId id, Consumer<SessionSignal> handler) {
        final boolean[] lastSubscriber = {false};
        handlers.computeIfPresent(id, (k, list) -> {
            list.remove(handler);
            if (list.isEmpty()) {
                lastSubscriber[0] = true;
                return null;
            }
            return list;
        });
        if (lastSubscriber[0]) {
            try {
                subscribeCommands.unsubscribe(controlChannel(id), eventsChannel(id));
            } catch (RedisException e) {
                log.warn("Best-effort unsubscribe for {} failed: {}", id, e.toString());
            }
        }
    }

    private void dispatch(String channel, String message) {
        final SessionId id = parseSessionId(channel);
        if (id == null) {
            return;
        }
        final List<Consumer<SessionSignal>> list = handlers.get(id);
        if (list == null || list.isEmpty()) {
            return;
        }
        final SessionSignal signal;
        try {
            signal = codec.decode(message);
        } catch (RuntimeException e) {
            log.warn("Failed to decode signal on channel {}: {}", channel, e.toString());
            return;
        }
        for (Consumer<SessionSignal> handler : list) {
            try {
                handler.accept(signal);
            } catch (Exception e) {
                log.warn("Signal handler threw for {}: {}", id, e.toString());
            }
        }
    }

    private SessionId parseSessionId(String channel) {
        final String controlPrefix = keyPrefix + ":control:";
        final String eventsPrefix = keyPrefix + ":events:";
        if (channel.startsWith(controlPrefix)) {
            return SessionId.of(channel.substring(controlPrefix.length()));
        }
        if (channel.startsWith(eventsPrefix)) {
            return SessionId.of(channel.substring(eventsPrefix.length()));
        }
        return null;
    }

    private String channelFor(SignalKind kind, SessionId id) {
        // EVENT and STATUS are high-frequency observability traffic; keep them off the low-frequency control channel
        // that carries INTERRUPT / EVICT / MESSAGE_ENQUEUED. Subscribers listen on both channels, so receipt is
        // unaffected by the split.
        return kind == SignalKind.EVENT || kind == SignalKind.STATUS ? eventsChannel(id) : controlChannel(id);
    }

    private String controlChannel(SessionId id) {
        return keyPrefix + ":control:" + id.value();
    }

    private String eventsChannel(SessionId id) {
        return keyPrefix + ":events:" + id.value();
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
