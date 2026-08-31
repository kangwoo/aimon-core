/**
 * Declares a tool's parameters once, as a record, instead of twice.
 *
 * <p>
 * A tool built on {@code AbstractTool} writes its parameters down two ways — a {@code Map} literal for the schema the
 * model reads, and a run of {@code getRequiredString} / {@code getInteger} calls for the values the code reads. Nothing
 * checks that the two agree, and in a tool with a dozen parameters they stop agreeing quietly: a parameter is declared
 * and never read, or read under a name that was never declared, or declared required and then defaulted anyway. The
 * model is told one contract and the code honours another, and the only symptom is a tool that behaves oddly.
 *
 * <p>
 * This package removes the second copy. {@link at.aimon.core.agent.tool.generic.GenericTool} takes a record type;
 * {@link at.aimon.core.agent.tool.generic.ToolSchemaGenerator} derives the schema from it and
 * {@link at.aimon.core.agent.tool.generic.ToolInputBinder} binds calls back into it. There is one declaration, so
 * there is nothing left to disagree.
 *
 * <h2>Records, in a codebase that prefers classes</h2>
 *
 * <p>
 * The project's convention is a class with a builder rather than a record. These input types are a narrow, deliberate
 * exception, granted because the reason for the convention does not apply to them:
 *
 * <ul>
 * <li>They are <b>deserialization targets</b>, never assembled field-by-field by a caller. A builder exists so that a
 * half-built object cannot escape; here the binder builds the whole thing at once from a map, and there is no partial
 * state for a builder to guard.
 * <li>They are <b>not domain types</b>. They live and die inside one {@code execute()} call and are never stored,
 * published, or evolved for compatibility.
 * <li><b>The record shape is what makes derivation possible.</b> A record's components are reflectable in declaration
 * order with their generic types intact; a builder's are not. Writing these as builder classes would mean reinstating
 * a hand-written schema, which is the thing being removed.
 * </ul>
 *
 * <p>
 * The exception stops there — at types annotated with {@link at.aimon.core.agent.tool.generic.ToolParam} and passed to
 * a {@code GenericTool}. It is not a licence to reach for a record elsewhere.
 *
 * <h2>Two gates, one vocabulary</h2>
 *
 * <p>
 * Binding is not the only place a bad call is caught. {@code DefaultToolExecutor} runs a schema check ahead of
 * dispatch that covers every tool, including MCP tools this package knows nothing about — but only on the path through
 * the executor, and several in-tree call sites reach a tool directly. Binding covers only migrated tools, but every
 * path to them. Neither subsumes the other, so both exist, and both build their sentences with
 * {@code at.aimon.core.agent.tool.schema.ViolationMessages} — a model that gets the same complaint from either one
 * reads the same words.
 *
 * @see at.aimon.core.agent.tool.AbstractTool
 * @see at.aimon.core.agent.tool.schema.ViolationMessages
 */
package at.aimon.core.agent.tool.generic;
