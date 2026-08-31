package at.aimon.spring.boot.autoconfigure;

/**
 * Backing store for session records, selected by {@code aimon.session.store}.
 *
 * <p>
 * An enum rather than a {@code String} on purpose, though not quite for the reason it is usually given.
 * Spring Boot's configuration processor does <em>not</em> emit value hints for enum-typed properties — it
 * records the property's {@code type} and leaves the IDE to read the constants off the class. The effect is the
 * same completion list, but it is worth stating accurately: what an enum actually buys is the type in the
 * metadata, relaxed binding across {@code IN_MEMORY} / {@code in-memory} / {@code inMemory}, and a typo
 * rejected during binding instead of silently matching no branch. The one selector that stays a {@code String}
 * is {@code aimon.llm.provider}, because a third party is expected to add its own value there, and it pays for
 * that openness with hand-written hints in {@code additional-spring-configuration-metadata.json} and a
 * dedicated startup check.
 */
public enum SessionStoreType {

    /**
     * Sessions live in the JVM heap. No extra infrastructure, but transcripts, session totals and budget
     * overrides are lost on restart, and a second instance cannot serve a session this one started. The stack
     * registers a {@code session-durability} degradation so the trade-off is logged rather than assumed.
     */
    IN_MEMORY,

    /**
     * Sessions are stored in PostgreSQL. Requires a {@code SessionRecordStore} bean from
     * {@code aimon-session-postgres}; this starter does not construct one.
     */
    POSTGRES,

    /**
     * Sessions are stored in MongoDB. Requires a {@code SessionRecordStore} bean from
     * {@code aimon-session-mongodb}; this starter does not construct one.
     */
    MONGODB,

    /**
     * Sessions are stored in Redis. Requires a {@code SessionRecordStore} bean from
     * {@code aimon-session-redis}; this starter does not construct one.
     */
    REDIS
}
