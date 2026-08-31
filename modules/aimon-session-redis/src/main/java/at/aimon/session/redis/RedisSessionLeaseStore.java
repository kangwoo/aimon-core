package at.aimon.session.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLeaseException;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link SessionLeaseStore} per design §5.3.
 *
 * <p>
 * Acquire = one Lua script that bumps {@code fenceKey} and stamps {@code lockKey} with
 * {@code "{holderId}:{token}"} in the same atomic step. Extend / release run as Lua scripts that compare the stored
 * value against the caller's stamp before mutating, so a stale lease whose term already expired and was re-acquired
 * elsewhere can never extend or release the new holder's lock.
 *
 * <h2>Key layout</h2>
 *
 * <pre>
 *   {keyPrefix}:lock:{convId}        SET NX PX  — the actual lock; value is "{holderId}:{token}"
 *   {keyPrefix}:lock:fence:{convId}  INCR       — monotonic fencing counter (gaps allowed)
 * </pre>
 *
 * <p>
 * Backend failures ({@link RedisException}) are wrapped as {@link SessionLeaseException}; held-by-another-holder
 * is
 * signaled by an empty {@link Optional} from {@link #tryAcquire}.
 *
 * <p>
 * The class name predates the {@code ConversationLock} &rarr; {@code SessionLeaseStore} rename and is kept on
 * purpose: {@code RedisKeyPrefixFreezeTest} pins {@link #DEFAULT_KEY_PREFIX} to this type, and renaming the class
 * without
 * a key migration would silently strand every lock key already in Redis.
 */
public final class RedisSessionLeaseStore implements SessionLeaseStore {

    public static final String DEFAULT_KEY_PREFIX = "aimon:conv";

    private static final Logger log = LoggerFactory.getLogger(RedisSessionLeaseStore.class);

    // KEYS = [lockKey, fenceKey], ARGV = [holderId, leaseMillis]. Returns the fencing token on success, 0 when the
    // lock is already held — INCR starts at 1 and only grows, so 0 can never be a real token.
    //
    // This has to be one script rather than INCR-then-SET-NX from the client. SessionLeaseStore requires the fencing
    // token to be strictly monotonic per session, and that is a statement about the tokens that get *applied*, not the
    // ones that get issued: with two round trips, an acquirer can be descheduled between its INCR and its SET, so a low
    // token can be stamped after a higher one has already been stamped, held and expired. Bumping the counter only
    // after EXISTS says the lock is free additionally keeps a losing acquirer from burning a token it never applies,
    // which is what makes the counter itself observable as "the token of the live lease" (see the acquire race test).
    private static final String ACQUIRE_SCRIPT = "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
            + "local token = redis.call('INCR', KEYS[2]) "
            + "if redis.call('SET', KEYS[1], ARGV[1] .. ':' .. token, 'NX', 'PX', ARGV[2]) then return token end "
            + "return 0";

    private static final String EXTEND_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then "
            + "return redis.call('PEXPIRE', KEYS[1], ARGV[2]) " + "else return 0 end";

    private static final String RELEASE_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then "
            + "return redis.call('DEL', KEYS[1]) " + "else return 0 end";

    // GET plus PTTL in one round trip, so the reported holder and its remaining term cannot disagree. A key without a
    // TTL (-1) or an absent key (-2) both answer "unheld": SET always carries PX here, so a TTL-less lock key is
    // corruption rather than an eternal holder, and reporting it as a holder would let it block the session
    // forever.
    private static final String FIND_HOLDER_SCRIPT = "local v = redis.call('GET', KEYS[1]) "
            + "if not v then return nil end " + "local ttl = redis.call('PTTL', KEYS[1]) "
            + "if ttl < 0 then return nil end " + "return v .. '|' .. ttl";

    private final RedisCommands<String, String> commands;
    private final String keyPrefix;
    private final Clock clock;

    public RedisSessionLeaseStore(StatefulRedisConnection<String, String> connection) {
        this(connection, DEFAULT_KEY_PREFIX, Clock.systemUTC());
    }

    public RedisSessionLeaseStore(StatefulRedisConnection<String, String> connection, String keyPrefix, Clock clock) {
        Objects.requireNonNull(connection, "connection must not be null");
        this.commands = connection.sync();
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<SessionLease> tryAcquire(SessionId id, String holderId, Duration lease) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        if (holderId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("holderId must not contain ':' separator: " + holderId);
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }

        try {
            // The script stamps ARGV[1] .. ':' .. token, so its value format must stay in step with stamp() — extend
            // and release rebuild the same string from the returned lease and compare it byte for byte.
            final Long token = commands.eval(ACQUIRE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{lockKey(id), fenceKey(id)}, holderId, String.valueOf(lease.toMillis()));
            if (token == null || token == 0L) {
                return Optional.empty();
            }
            return Optional.of(SessionLease.builder().sessionId(id).holderId(holderId).fencingToken(token)
                    .acquiredAt(clock.instant()).lease(lease).build());
        } catch (RedisException e) {
            throw new SessionLeaseException("Redis error during tryAcquire for " + id, e);
        }
    }

    @Override
    public Optional<LeaseHolder> findHolder(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        try {
            final String result = commands.eval(FIND_HOLDER_SCRIPT, ScriptOutputType.VALUE, new String[]{lockKey(id)});
            if (result == null) {
                return Optional.empty();
            }
            return Optional.of(parseHolder(result));
        } catch (RedisException e) {
            throw new SessionLeaseException("Redis error during findHolder for " + id, e);
        }
    }

    /**
     * Parses {@code "{holderId}:{token}|{ttlMillis}"}. Splitting from the right is what makes this safe: the token and
     * the TTL are numeric, and {@code tryAcquire} rejects a holder id containing {@code ':'}, so the last two
     * separators
     * always delimit exactly those two fields no matter what the holder id looks like.
     */
    private LeaseHolder parseHolder(String raw) {
        final int ttlSep = raw.lastIndexOf('|');
        final int tokenSep = raw.lastIndexOf(':', ttlSep);
        if (ttlSep < 0 || tokenSep < 0) {
            throw new SessionLeaseException("Malformed lock value from Redis: " + raw);
        }
        final long ttlMillis = Long.parseLong(raw.substring(ttlSep + 1));
        return LeaseHolder.builder().holderId(raw.substring(0, tokenSep))
                .fencingToken(Long.parseLong(raw.substring(tokenSep + 1, ttlSep)))
                .expiresAt(clock.instant().plusMillis(ttlMillis)).build();
    }

    @Override
    public boolean extend(SessionLease lease, Duration duration) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive: " + duration);
        }
        final String key = lockKey(lease.getSessionId());
        final String stamp = stamp(lease.getHolderId(), lease.getFencingToken());
        try {
            final Long result = commands.eval(EXTEND_SCRIPT, ScriptOutputType.INTEGER, new String[]{key}, stamp,
                    String.valueOf(duration.toMillis()));
            return result != null && result == 1L;
        } catch (RedisException e) {
            throw new SessionLeaseException("Redis error during extend for " + lease.getSessionId(), e);
        }
    }

    @Override
    public void release(SessionLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        final String key = lockKey(lease.getSessionId());
        final String stamp = stamp(lease.getHolderId(), lease.getFencingToken());
        try {
            commands.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{key}, stamp);
        } catch (RedisException e) {
            log.warn("Best-effort release for {} failed: {}", lease.getSessionId(), e.toString());
        }
    }

    private String lockKey(SessionId id) {
        return keyPrefix + ":lock:" + id.value();
    }

    private String fenceKey(SessionId id) {
        return keyPrefix + ":lock:fence:" + id.value();
    }

    private static String stamp(String holderId, long token) {
        return holderId + ":" + token;
    }
}
