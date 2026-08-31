package at.aimon.session.redis;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.subagent.task.TaskStopSignal;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

/**
 * Redis pub/sub-backed {@link TaskStopSignal}: carries a background-task stop request across instances
 * (subagent design §4).
 *
 * <p>
 * All nodes publish to and listen on a single channel ({@code <channel>}); the message body is the raw {@code taskId}.
 * {@link #broadcastStop(String)} publishes it, and every subscribed manager (including the publisher) is delivered the
 * id and trips its local execution handle if it owns that task — so a stop issued on any node reaches whichever node is
 * running the task. A node that does not own the task simply no-ops, and handlers never re-broadcast, so a stop cannot
 * loop.
 *
 * <p>
 * Modeled on {@code RedisPubSubSignalBus}: two caller-owned Lettuce connections — one {@link StatefulRedisConnection}
 * for {@code PUBLISH}, one {@link StatefulRedisPubSubConnection} for the listener. The single channel is subscribed
 * once
 * at construction and unsubscribed on {@link #close()}; individual {@link #subscribe(Consumer) subscriptions} only
 * add/remove an in-process handler and never round-trip to Redis. This class never closes the connections themselves.
 *
 * <p>
 * Thread-safety: handlers live in a {@link CopyOnWriteArrayList} so the pub/sub dispatch thread iterates without locks;
 * a
 * throwing handler is isolated (logged, not propagated).
 */
public final class RedisTaskStopSignal implements TaskStopSignal, AutoCloseable {

    /** Default pub/sub channel for background-task stop broadcasts. */
    public static final String DEFAULT_CHANNEL = "aimon:subagent:task-stop";

    private static final Logger log = LoggerFactory.getLogger(RedisTaskStopSignal.class);

    private final RedisCommands<String, String> publishCommands;
    private final StatefulRedisPubSubConnection<String, String> subscribeConnection;
    private final RedisPubSubCommands<String, String> subscribeCommands;
    private final String channel;
    private final RedisPubSubAdapter<String, String> listener;
    private final List<Consumer<String>> handlers = new CopyOnWriteArrayList<>();

    /**
     * Creates a signal on the {@link #DEFAULT_CHANNEL default channel}.
     *
     * @param publishConnection
     *            the connection used for {@code PUBLISH} (must not be null; owned by the caller)
     * @param subscribeConnection
     *            the pub/sub connection used for the listener (must not be null; owned by the caller)
     */
    public RedisTaskStopSignal(StatefulRedisConnection<String, String> publishConnection,
            StatefulRedisPubSubConnection<String, String> subscribeConnection) {
        this(publishConnection, subscribeConnection, DEFAULT_CHANNEL);
    }

    /**
     * Creates a signal on an explicit channel.
     *
     * @param publishConnection
     *            the connection used for {@code PUBLISH} (must not be null; owned by the caller)
     * @param subscribeConnection
     *            the pub/sub connection used for the listener (must not be null; owned by the caller)
     * @param channel
     *            the pub/sub channel name (must not be null)
     */
    public RedisTaskStopSignal(StatefulRedisConnection<String, String> publishConnection,
            StatefulRedisPubSubConnection<String, String> subscribeConnection, String channel) {
        Objects.requireNonNull(publishConnection, "publishConnection must not be null");
        this.subscribeConnection = Objects.requireNonNull(subscribeConnection, "subscribeConnection must not be null");
        this.publishCommands = publishConnection.sync();
        this.subscribeCommands = subscribeConnection.sync();
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.listener = new RedisPubSubAdapter<>() {
            @Override
            public void message(String ch, String message) {
                dispatch(ch, message);
            }
        };
        this.subscribeConnection.addListener(listener);
        try {
            this.subscribeCommands.subscribe(channel);
        } catch (RedisException e) {
            this.subscribeConnection.removeListener(listener);
            throw new IllegalStateException("Failed to subscribe to Redis task-stop channel " + channel, e);
        }
    }

    @Override
    public void broadcastStop(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        try {
            publishCommands.publish(channel, taskId);
        } catch (RedisException e) {
            // Best-effort: a failed broadcast must not abort the caller's stop flow.
            log.warn("Failed to broadcast stop for task {}: {}", taskId, e.toString());
        }
    }

    @Override
    public Subscription subscribe(Consumer<String> onStopRequest) {
        Objects.requireNonNull(onStopRequest, "onStopRequest cannot be null");
        handlers.add(onStopRequest);
        return () -> handlers.remove(onStopRequest);
    }

    @Override
    public void close() {
        try {
            subscribeCommands.unsubscribe(channel);
        } catch (RedisException e) {
            log.warn("Best-effort unsubscribe from task-stop channel {} failed: {}", channel, e.toString());
        }
        subscribeConnection.removeListener(listener);
        handlers.clear();
    }

    private void dispatch(String ch, String taskId) {
        if (!channel.equals(ch) || taskId == null) {
            return;
        }
        for (final Consumer<String> handler : handlers) {
            try {
                handler.accept(taskId);
            } catch (RuntimeException e) {
                log.warn("Task stop handler threw for taskId {}: {}", taskId, e.toString());
            }
        }
    }
}
