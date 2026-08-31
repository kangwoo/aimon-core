package at.aimon.session.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Shared test infrastructure: singleton Redis container, helpers for Lettuce client / connection lifecycle, and
 * {@code FLUSHDB} between tests so each test sees a clean keyspace.
 */
public final class RedisTestSupport {

    public static final GenericContainer<?> REDIS;

    private static final RedisClient CLIENT;

    static {
        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
                .withCommand("redis-server", "--appendonly", "no");
        REDIS.start();
        CLIENT = RedisClient.create(uri());
    }

    private RedisTestSupport() {
    }

    public static RedisURI uri() {
        return RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379)).build();
    }

    public static StatefulRedisConnection<String, String> connect() {
        return CLIENT.connect();
    }

    public static StatefulRedisPubSubConnection<String, String> connectPubSub() {
        return CLIENT.connectPubSub();
    }

    public static void flushAll() {
        try (StatefulRedisConnection<String, String> conn = connect()) {
            conn.sync().flushall();
        }
    }
}
