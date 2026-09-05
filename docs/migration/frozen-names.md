# Frozen Names

The names that were **deliberately not renamed**. This is a compatibility contract, not a change
record: everything listed here is expected to still be there in the next release, and changing any
of it is a data migration rather than a cleanup.

The Java identifiers around these names did move -- [`rename-maps.md`](rename-maps.md) is that
lookup. The two documents describe the same refactors from opposite sides, and the boundary between
them is exactly this: **a rename stops at the Java symbol boundary.** So a persisted name that looks
out of step with the Java name it stores is the intended state, not drift.

**No data migration and no rolling-upgrade coordination is needed.** A node running the new jars
interoperates with the stored state and the live traffic of a node running the old ones.

> **Why this is a document and not a changelog section.** A changelog entry is written once and never
> revisited; this list is consulted whenever somebody is about to "fix" an inconsistent name, and it
> grows whenever something new is frozen. It used to live under `[Unreleased]` in
> [`CHANGELOG.md`](../../CHANGELOG.md), where the first release to ship would have buried it under a
> version heading. [`../overview/scope-model.md`](../overview/scope-model.md) §7 cites this list as
> the boundary of what the renames did not touch.

---

## Names encoding "conversation"

- `aimon-session-postgres` DDL and SQL — tables `conversation_inbox`, `conversation_lock`,
  `conversation_lock_fence`, `conversation_signal`, the `conversation_id` columns, and the
  `LISTEN`/`NOTIFY` channel `conversation_signal_doorbell`.
- `aimon-session-mongodb` — collections `conversation_locks`, `conversation_inbox`,
  `conversation_signals`, and the `"conversationId"` / `"Conversation"` document keys (`DocumentKeys`).
- `aimon-session-redis` — the `"conversationId"` codec fields and the `aimon:conv`, `aimon:inbox`,
  `aimon:conv:idem` key prefixes. Their inconsistency with each other is frozen too; harmonizing it
  is a data migration, not a cleanup.
- `at.aimon.core.tools.ToolContextKeys` — the `"conversationId"` and `"invokingConversationId"` key
  strings. Out-of-tree tools reading the raw string keep working; only code referencing the Java
  constant must be updated.
- `JsonSessionSnapshotCodec` — the snapshot envelope's field names (`FORMAT_VERSION=1`,
  `FIELD_CONVERSATION_ID`).
- `StatusSnapshotPayload` — the neutral keys `totals`, `turnCount`, `iterations`.
- LLM-facing and user-facing text where "conversation" means the message exchange: `/compact`'s
  output, the summarization prompt, `/clear`'s confirmation, the prompt-too-long recovery text.

## Names encoding "context"

- `aimon-session-postgres` — the `context_id` column and `background_task_context_idx` index
  (`V1__init.sql`).
- `aimon-session-mongodb` — `DocumentKeys.F_CONTEXT_ID` (`"contextId"`).
- `aimon-session-redis` — `BackgroundTaskCodec`'s `"contextId"` field.
- `aimon-session-routing` — `AgentExecutionEventPayload.KEY_CONTEXT` (`"ctx"`).
- `aimon-knowledge-opensearch` — `OpenSearchDocumentMapper.FIELD_CONTEXT_ID` (`"context_id"`), an
  indexed field; renaming it would require a reindex.
- `VfsSessionSnapshotStore.FIELD_CONTEXT_ID` (`"contextId"`) — the subagent transcript envelope tag.

## The inbox's `userInput`, whose *type* is frozen and not only its spelling

The session inbox envelope carries a `userInput` key in all four backends. It used to hold the
submission's text because the submission *was* text; `SubmitRequest` and `InboundMessage` now carry a
`UserInput`, and the obvious tidy — making the key hold the structured value — is the one thing that
may not happen.

- **`userInput` stays a string**, now holding `UserInput.asText()`. `aimon-session-redis` and
  `aimon-session-postgres` read it with `JsonNode.asText()`, which answers `""` for an object rather
  than failing; `aimon-session-mongodb` reads it with `Document.getString`, which throws. So a node on
  an older build meeting a restructured key either runs an empty turn without saying so or cannot
  decode the entry at all.
- **`userInputEncoded` is the addition**, carrying the
  `at.aimon.core.subagent.task.codec.UserInputCodec` subtree (a JSON object in Redis and Postgres, the
  same JSON as text in MongoDB), and it is written **only when the input is not plain text**. A text
  submission is therefore byte-for-byte the document the previous build wrote, and an older reader
  meeting a multimodal one still finds a rendering it can run under the key it knows.
- **`UserInputCodec`'s own field names and type tags** — `type`, `text`, `mimeType`, `fileName`,
  `data`, `inputs`, and the tags `text` / `image` / `audio` / `file` / `multimodal` — are frozen for
  the same reason plus one more: the same encoding is what `JsonSessionSnapshotCodec` has always
  written into stored subagent transcripts, so a rename would strand those too.

Why this is a freeze and not just a compatible default: an inbox holds work that has **not been done
yet**, so at every upgrade the stream, the table and the collection still contain entries the other
build wrote, in both directions. The asymmetric shape is what lets a node on either build read a
document written by a node on the other.

The forward direction has a rule of its own, for a reason specific to an inbox. All three backends
remove an entry from storage *before* the codec sees it — Redis's collect script `XDEL`s inside Lua,
Postgres commits its `DELETE … RETURNING`, MongoDB uses `findOneAndDelete` — so refusing a document
does not reject one message, it destroys every message that call collected. A `userInputEncoded` this
build cannot read (a sixth `InputType` written by a node one release ahead) therefore **degrades to
the string beside it and logs at `WARN`, naming the session** rather than throwing.
`UserInputCodec.decodeOrText` is the one place that decides it, and its javadoc carries the
comparison with `JsonSessionSnapshotCodec`, which refuses the same failure — and refuses a narrower
set of them than the inbox catches — because a rewind point has no such string and no such cost.

**The line is the field, not the kind of failure.** A decoder cannot tell a newer document from a
damaged one — `{"type":"video"}` reads identically either way — so the rule is the one it can
enforce: anything thrown while reading `userInputEncoded` degrades, whatever its type, and the rest
of the envelope still refuses. A malformed `initiator` or an unknown `priority` throws, because those
say the document is damaged rather than newer and the string beside them stands in for nothing.
Drawing that line at the exception type instead is a mistake this format already made once: a
`mimeType` of `video/mp4` under `"type":"image"` is refused by `ImageInput` with a plain
`IllegalArgumentException`, which sailed past a `catch` written for the codec's own type and
destroyed the batch anyway.

`InboundMessageCodecTest` (Redis, MongoDB) and `InboundMessageRowCodecTest` (Postgres) pin both
halves against hard-coded literals, and `UserInputCodecTest` pins the subtree's shapes the same way.
As everywhere else on this page, the assertions are on literals rather than on the constants —
encoder and decoder share a constant, so a round-trip through them cannot see a rename, and here it
cannot see a change of type either.

## The freeze is pinned by tests

**These literals are now pinned by tests.** Each is asserted against a **hard-coded string**, never
against the constant it guards — an assertion routed through the constant follows the rename and can
never fail, which is worse than having no test, and a round-trip test structurally cannot catch it
because encoder and decoder share the constant. `MongoSchemaFreezeTest` (collection names on both the
Java constants and `db/mongodb/init.js`, plus two index declarations), `PostgresSchemaFreezeTest`
(DDL identifiers), `ListenDispatcherChannelFreezeTest` (`conversation_signal_doorbell`, the one frozen
name absent from the DDL) and `RedisKeyPrefixFreezeTest`. Verified by actually running a
`Conversation` → `Session` rename across string literals and DDL: 24 pins went red while all 31
round-trip tests stayed green.

## Also frozen

**No wire format, stored document or configuration key** is affected by the tool-contract
work. The schema gate's default (`WARN`) means an existing deployment sees logging, not new failures;
the permission fail-open closure is the one intended exception.

Frozen too on the filesystem side: **GridFS keeps storing paths as `filename` and nothing else
changes shape.** Directory markers are the one addition — zero-length documents whose `filename` ends
in `/`, tagged `metadata.type = "directory"` — and they are additive: a bucket written by an earlier
version has none, and every directory in it is still discovered from the paths of the files under it.
The exception to "no configuration key changed" is the GridFS **default** database name, which moved
from `at/aimon` to `aimon` under [Fixed](../../CHANGELOG.md#fixed); MongoDB rejects `/` in a database name, so no
deployment can hold data under the old value.

---

## Related documents

- [`rename-maps.md`](rename-maps.md) -- the old-name -> new-name lookup for the Java side
- [`../project/api-stability.md`](../project/api-stability.md) -- what `0.x` promises, and what it does not
- [`../overview/scope-model.md`](../overview/scope-model.md) -- §6 lists the misnomers these frozen names produce
- [`../design/session/backends.md`](../design/session/backends.md) -- §2 on why `conversation_*` and `session_*` coexist in one schema
