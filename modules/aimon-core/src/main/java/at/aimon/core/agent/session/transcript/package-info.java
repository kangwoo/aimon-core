/**
 * The LLM-visible message history of a session: the value it is stored as, the buffer a turn appends to, and the
 * snapshot that moves between them.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * "Conversation" survives in this package as the LLM word — a transcript <em>is</em> a conversation with a model, and
 * {@code getConversationHistory()} means exactly that. What it no longer means is a lifetime. The lifetimes are named
 * elsewhere: {@link at.aimon.core.agent.session.store.SessionRecord} is the durable side,
 * {@link at.aimon.core.agent.session.LiveSession} is the node-local handle, and they stand in a 1 : 0..N relation. See
 * {@code docs/overview/glossary.md} for the full lifetime table.
 *
 * <p>
 * This package is nested under {@code session} rather than sitting beside it because it is <b>inside</b> the session
 * aggregate, not next to it: the record contains a transcript, the handle holds a buffer, the store hands out a
 * manager,
 * and in the other direction the buffer needs a {@link at.aimon.core.agent.session.SessionId} and the manager needs a
 * {@link at.aimon.core.agent.session.store.SessionRecordStore}. As siblings the two would form a package cycle, and the
 * cycle would be honest.
 *
 * <h2>Key Concepts</h2>
 *
 * <h3>SessionTranscript — the stored value</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.session.transcript.SessionTranscript} is the immutable pair of system prompt plus messages
 * that a record holds as one field. Immutability is what lets record copies share it by reference instead of
 * duplicating
 * the message list:
 *
 * <pre>
 * {
 *     &#64;code
 *     SessionTranscript transcript = SessionTranscript.of("You are a helpful assistant.",
 *             List.of(Message.user("What is the weather?"), Message.assistant("I'll check the weather for you.")));
 *
 *     SessionTranscript empty = SessionTranscript.empty();
 * }
 * </pre>
 *
 * <h3>TranscriptBuffer — the turn's append path</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.session.transcript.TranscriptBuffer} is the live, mutable history of a running turn, and
 * the <b>only</b> append path — {@code SessionRecord} deliberately has no append method, because appending to an
 * immutable transcript is O(n) per message and would race the buffer the turn is actually writing to. The buffer
 * reports
 * mutations through a monotonic version counter plus an optional dirty listener:
 *
 * <pre>
 * {
 *     &#64;code
 *     TranscriptBuffer buffer = TranscriptBuffer.fromSnapshot(snapshot);
 *
 *     // Every mutator bumps the version and notifies the listener, if one is attached
 *     buffer.setDirtyListener(b -&gt; flusher.schedule(b));
 *     final long before = buffer.getVersion();
 *     buffer.addUserMessage("New message");
 *
 *     if (buffer.getVersion() != before) {
 *         store.mergeFromSnapshot(buffer.toSnapshot());
 *     }
 * }
 * </pre>
 *
 * <h3>SessionSnapshot — the crossing point</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.session.transcript.SessionSnapshot} is an immutable capture of id, system prompt and
 * history, and the <b>sole owner of the persisted format</b>. Everything durable travels through it in both directions:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Out of the buffer, into the store
 *     store.mergeFromSnapshot(buffer.toSnapshot());
 *
 *     // Out of a loaded record, back into a buffer
 *     SessionRecordView loaded = store.load(sessionId).orElseThrow();
 *     TranscriptBuffer resumed = TranscriptBuffer.fromSnapshot(SessionSnapshot.from(loaded));
 * }
 * </pre>
 *
 * <p>
 * The snapshot carries the transcript and nothing else. A record's side fields —
 * {@link at.aimon.core.agent.session.store.SessionTotals}, {@code budgetOverride}, {@code agentRef},
 * {@code compactionFailureCount} — have separate owners and stay behind on the record, which is why the store's write
 * entry point is named {@code mergeFromSnapshot} rather than {@code save}. The reverse construction
 * ({@code SessionRecord.fromSnapshot}) lives on the store side on purpose: this package must not depend on the mutable
 * record type.
 *
 * <h3>TranscriptManager — rehydrate, then flush</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.session.transcript.TranscriptManager} pairs the buffer with the store.
 * {@code initialize} runs <b>once per turn</b>, not once per session — it loads whatever is on record and returns a
 * buffer positioned at the end of it, so appends continue where the previous turn stopped:
 *
 * <pre>
 * {
 *     &#64;code
 *     TranscriptManager manager = new DefaultTranscriptManager(store);
 *     SessionId id = SessionId.generate();
 *
 *     // First turn
 *     TranscriptBuffer first = manager.initialize(id, "You are a helpful assistant.");
 *     first.addUserMessage("What is 2+2?");
 *     first.addAssistantMessage("2+2 equals 4.");
 *     manager.save(first);
 *
 *     // Second turn — initialize() rehydrates the stored history rather than starting a new one
 *     TranscriptBuffer second = manager.initialize(id, "You are a helpful assistant.");
 *     second.addUserMessage("What is 3+3?");
 *     manager.save(second);
 *
 *     // Or flush without letting a persistence failure fail the turn
 *     manager.saveSilently(second);
 * }
 * </pre>
 *
 * <p>
 * The name is a known misnomer for the same reason: {@code initialize} reads as "once per session" but fires every
 * turn.
 * {@code beginTurn} / {@code endTurn} would be accurate; renaming it is a separate change from the vocabulary work,
 * because it moves a call boundary rather than a name.
 *
 * <h3>ThrowingPromptTooLongHandler</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.session.transcript.ThrowingPromptTooLongHandler} is the do-nothing default for callers
 * with
 * no compaction strategy wired: it rethrows the triggering exception verbatim, so behaviour matches having installed no
 * handler at all. Real compaction lives in {@link at.aimon.core.agent.compact}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * {@code SessionTranscript} and {@code SessionSnapshot} are immutable and safe to share. {@code TranscriptBuffer}
 * synchronizes every accessor and mutator on itself, which makes single operations safe but not compound ones — a
 * read-decide-write sequence across two calls still needs the caller's own lock. {@code SessionRecord}, over in the
 * store package, is not thread-safe at all.
 *
 * @see at.aimon.core.agent.session.store.SessionRecord
 * @see at.aimon.core.agent.session.store.SessionRecordStore
 * @see at.aimon.core.agent.session.LiveSession
 * @see at.aimon.core.agent.compact
 */
package at.aimon.core.agent.session.transcript;
