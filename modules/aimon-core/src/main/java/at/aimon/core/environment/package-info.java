/**
 * The execution environment SPI: the filesystem, shell and self-description one execution's tools run against.
 *
 * <p>
 * An {@link at.aimon.core.environment.ExecutionEnvironmentProvider} resolves an
 * {@link at.aimon.core.environment.ExecutionEnvironment} once per execution; executors publish it in the write-once
 * {@code ToolContextKeys.EXECUTION_ENVIRONMENT} key, and file tools, {@code Bash}, {@code WikiIngest}, {@code Skill}
 * and prompt assembly read it from there. The provider owns the resources behind the environments it returns; an
 * environment is a view and is not closed. The local implementation lives in {@code at.aimon.core.environment.impl}.
 *
 * <p>
 * Design: {@code docs/design/tool/execution-environment.md}.
 */
package at.aimon.core.environment;
