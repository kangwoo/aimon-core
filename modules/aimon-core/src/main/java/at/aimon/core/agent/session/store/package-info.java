/**
 * One door to a session: who holds it, which agent it is bound to, and what is durably recorded.
 *
 * <p>
 * The entry point is {@link at.aimon.core.agent.session.store.SessionStore#claim}, which elects a holder,
 * provisions the record, and settles the agent binding in a single call. Everything else here supports that:
 * {@link at.aimon.core.agent.session.store.ClaimResult} is its three-way answer,
 * {@link at.aimon.core.agent.session.store.SessionLease} is the proof of holdership it hands back,
 * {@link at.aimon.core.agent.session.store.SessionLeaseStore} is the pluggable election backend, and
 * {@link at.aimon.core.agent.session.store.DefaultSessionStore} composes that backend with a
 * {@link at.aimon.core.agent.session.store.SessionRecordStore}.
 *
 * <h2>The shared wire shape</h2>
 *
 * <p>
 * {@link at.aimon.core.agent.session.store.SessionRecordCodec} is the encoding every distributed
 * {@code SessionRecordStore} backend shares, with
 * {@link at.aimon.core.agent.session.store.StoredSessionRecord} as its neutral document and
 * {@link at.aimon.core.agent.session.store.StoredAgentExecutionResult} as the wire-safe result projection. The last
 * of those serves two callers that have nothing else in common — idempotency replay and the forwarded-turn result
 * rail — which is why it sits with the codec rather than with either of them. The codec encodes the transcript half
 * by delegating to the snapshot codecs in {@code at.aimon.core.subagent.task.codec} instead of duplicating that
 * mapping; those two are pure data codecs whose package name records their first consumer, not a constraint.
 *
 * <h2>Why this lives in aimon-core</h2>
 *
 * <p>
 * Election used to be {@code ConversationLock} in {@code aimon-session-base}, one module up from the record it
 * protected.
 * That split was not sustainable once the two had to be decided together: the SPI is keyed by
 * {@link at.aimon.core.agent.session.SessionId}, which lives here, so the module dependency can only point
 * one
 * way. The four distributed election backends ({@code aimon-session-redis}, {@code -postgres}, {@code -mongodb}, and
 * the
 * in-process default) implement the SPI from here and are unaffected by the move — their wire formats, key prefixes and
 * DDL are untouched.
 *
 * <h2>Which scope is which</h2>
 *
 * <p>
 * {@code SessionLeaseStore} and {@code SessionRecordStore} are <b>application-scoped</b> and shared by every
 * node. {@code DefaultSessionStore} is <b>node-scoped</b> — one per session manager — because it remembers which
 * leases the local node holds so record writes can be fenced without threading a token through the ReAct call chain.
 * This
 * is the {@code InMemoryTodoRepository} situation from the scope model: the instance's own lifetime and the scope of
 * what
 * it is keyed by are different questions.
 *
 * <p>
 * Everything here is named {@code Session*}, and that is what the restructure bought. Under the old vocabulary these
 * types could not carry the word: the record was a {@code Conversation}, {@code Session} meant the node-local handle,
 * and a lease — which outlives the handle that won it — would have been claiming the shorter of the two lifetimes.
 * Now {@code SessionId} names the join key both lifetimes share, so a lease keyed by it is plainly on the durable side.
 * What the vocabulary can no longer distinguish, {@code ArchitectureTest} enforces directly: nothing in this package
 * may depend on {@code LiveSession} or any {@code Live*} type.
 */
package at.aimon.core.agent.session.store;
