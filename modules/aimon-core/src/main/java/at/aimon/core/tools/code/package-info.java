/**
 * {@code ExecuteCode} — the tool that runs model-written code whose function calls are tool calls
 * ({@code docs/design/tool/programmatic-tool-calling.md}).
 *
 * <p>
 * The tool picks an engine at offer time ({@link at.aimon.core.programmatic.CodeExecutionEngine}), freezes the callable
 * set when a program starts, and hands every call the program makes to the iteration's
 * {@link at.aimon.core.agent.tool.ToolCallDispatcher} on the {@code CODE} path through a per-call bridge. Code in this
 * package calls tools only through that dispatcher (decision 3, enforced by {@code ProgrammaticToolArchitectureTest}).
 */
package at.aimon.core.tools.code;
