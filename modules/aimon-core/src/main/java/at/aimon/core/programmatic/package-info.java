/**
 * The engine SPI of programmatic tool calling ({@code docs/design/tool/programmatic-tool-calling.md} §9): the neutral
 * contract between {@code ExecuteCode} ({@code at.aimon.core.tools.code}) and a language engine — the GraalJS engine
 * in {@code aimon-graaljs}, the shell engine in {@code at.aimon.core.tools.code.shell}.
 *
 * <p>
 * It sits outside {@code at.aimon.core.agent..} so an engine module depends on this package alone. An engine only moves
 * the function calls of its language runtime onto {@link at.aimon.core.programmatic.ToolCallBridge#callAsync}; the
 * bridge owns everything engine-independent — the frozen callable set, the code-path rules, the quota, the
 * concurrency lanes, the child cancellation signal and the dispatcher call.
 *
 * <p>
 * Engines are application-scoped: built once at assembly, closed at shutdown ({@code docs/overview/scope-model.md}).
 * Assembly finds them through {@link at.aimon.core.programmatic.CodeExecutionEngineFactory} and {@code ServiceLoader},
 * or constructs them directly.
 */
package at.aimon.core.programmatic;
