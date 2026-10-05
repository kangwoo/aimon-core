/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.EnvironmentProviding;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.FileStamp;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.tools.file.EditTool;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.file.WriteTool;

/**
 * A routine firing carries its own read-stamp map, as a turn and a fork carry theirs (EE-11).
 *
 * <p>
 * These run the real {@code Read}, {@code Edit} and {@code Write} tools through {@link RoutineExecutor}, because the
 * rule under test is what those tools do with the context the executor hands them, not the presence of a key.
 */
class RoutineExecutorReadStampTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private DefaultAgentRuntimeRegistry agentRuntimeRegistry;
    private RoutineExecutor executor;
    private AgentRuntimeId boundRuntimeId;
    private StampCapturingTool stampCapture;

    @BeforeEach
    void setUp() {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        executor = new RoutineExecutor(agentRuntimeRegistry, new SimpleScheduledTaskEventPublisher(),
                Clock.systemUTC());

        final Agent agent = DefaultAgent.builder().name("orca-stamps").systemPrompt("test").build();
        boundRuntimeId = AgentRuntimeId.from(agent);
        stampCapture = new StampCapturingTool();
        agentRuntimeRegistry.register(new FileToolRuntime(boundRuntimeId, agent,
                List.of(new ReadTool(), new EditTool(), new WriteTool(), stampCapture),
                TestExecutionEnvironments.provider(fileSystem)));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        fileSystem.close();
    }

    @Test
    void anEditStepSucceedsAfterAReadStepInTheSameFiring() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("report.txt"), "status: old\n");

        final RoutineResult result = executor.execute(task(step("Read", Map.of("file_path", file.toString())),
                step("Edit", Map.of("file_path", file.toString(), "old_string", "old", "new_string", "new"))));

        assertThat(result.getStepResults()).extracting(StepResult::getErrorMessage)
                .allSatisfy(error -> assertThat(error).isEmpty());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("status: new\n");
    }

    @Test
    void aWriteStepOverAnExistingFileIsRefusedWithoutAReadInTheSameFiring() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("report.txt"), "kept\n");

        final RoutineResult result = executor
                .execute(task(step("Write", Map.of("file_path", file.toString(), "content", "overwritten\n"))));

        assertThat(result.isSuccess()).isFalse();
        // The text a routine author reads in the step result: what to do, and for which file.
        assertThat(result.getStepResults().get(0).getErrorMessage())
                .contains("Read the file before modifying it: " + file);
        assertThat(Files.readString(file)).isEqualTo("kept\n");
    }

    @Test
    void aWriteStepOverAnExistingFileSucceedsAfterAReadStepInTheSameFiring() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("report.txt"), "old\n");

        final RoutineResult result = executor.execute(task(step("Read", Map.of("file_path", file.toString())),
                step("Write", Map.of("file_path", file.toString(), "content", "new\n"))));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("new\n");
    }

    @Test
    void aWriteStepThatCreatesAFileNeedsNoRead() throws IOException {
        final Path file = tempDir.resolve("fresh.txt");

        final RoutineResult result = executor
                .execute(task(step("Write", Map.of("file_path", file.toString(), "content", "created\n"))));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("created\n");
    }

    /**
     * The file the routine itself created in an earlier step is stamped by that write, so a later step of the same
     * firing may overwrite or edit it without a {@code Read} in between.
     */
    @Test
    void aFileWrittenEarlierInTheFiringMayBeModifiedWithoutARead() throws IOException {
        final Path file = tempDir.resolve("fresh.txt");

        final RoutineResult result = executor
                .execute(task(step("Write", Map.of("file_path", file.toString(), "content", "one\n")),
                        step("Edit", Map.of("file_path", file.toString(), "old_string", "one", "new_string", "two")),
                        step("Write", Map.of("file_path", file.toString(), "content", "three\n"))));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("three\n");
    }

    /**
     * A read in one firing does not license a write in the next: the map belongs to the firing, not to the task, the
     * runtime or the executor. Between two firings is exactly when somebody else changes the file.
     */
    @Test
    void aReadInOneFiringDoesNotCarryIntoTheNext() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("report.txt"), "kept\n");
        final ScheduledTask readOnly = task(step("Read", Map.of("file_path", file.toString())));
        final ScheduledTask writeOnly = task(step("Write", Map.of("file_path", file.toString(), "content", "x\n")));

        assertThat(executor.execute(readOnly).isSuccess()).isTrue();
        final RoutineResult second = executor.execute(writeOnly);

        assertThat(second.isSuccess()).isFalse();
        assertThat(second.getStepResults().get(0).getErrorMessage())
                .hasValueSatisfying(error -> assertThat(error).contains("Read the file before modifying it"));
        assertThat(Files.readString(file)).isEqualTo("kept\n");
    }

    /** Two firings of the same task get two maps, each empty when the firing starts. */
    @Test
    void eachFiringOfATaskGetsItsOwnEmptyStampMap() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("report.txt"), "kept\n");
        final ScheduledTask task = task(step("StampCapture", Map.of()),
                step("Read", Map.of("file_path", file.toString())));

        assertThat(executor.execute(task).isSuccess()).isTrue();
        assertThat(executor.execute(task).isSuccess()).isTrue();

        assertThat(stampCapture.maps).hasSize(2).doesNotContainNull();
        assertThat(stampCapture.maps.get(0)).isNotSameAs(stampCapture.maps.get(1));
        assertThat(stampCapture.sizesAtCapture).containsExactly(0, 0);
        // Each firing's own Read landed in that firing's map.
        assertThat(stampCapture.maps.get(0)).hasSize(1);
        assertThat(stampCapture.maps.get(1)).hasSize(1);
    }

    private ScheduledTask task(RoutineStep... steps) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("stamp-task").cronExpression("*/5 * * * *")
                .routine(List.of(steps)).owner(Principal.user("alice")).boundRuntimeId(boundRuntimeId).enabled(true)
                .build();
    }

    private static RoutineStep step(String tool, Map<String, String> params) {
        final StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue().replace("\\", "\\\\").replace("\n", "\\n")).append('"');
        }
        return RoutineStep.builder().tool(tool).toolParams(json.append('}').toString()).maxRetries(0)
                .timeout(Duration.ofSeconds(10)).build();
    }

    /** A runtime that carries an execution-environment provider, as the Orca runtime does. */
    private static final class FileToolRuntime implements AgentRuntime, EnvironmentProviding {

        private final AgentRuntimeId id;
        private final Agent agent;
        private final List<Tool> tools;
        private final ExecutionEnvironmentProvider provider;

        FileToolRuntime(AgentRuntimeId id, Agent agent, List<Tool> tools, ExecutionEnvironmentProvider provider) {
            this.id = Objects.requireNonNull(id);
            this.agent = Objects.requireNonNull(agent);
            this.tools = List.copyOf(tools);
            this.provider = Objects.requireNonNull(provider);
        }

        @Override
        public AgentRuntimeId getId() {
            return id;
        }

        @Override
        public Agent getAgent() {
            return agent;
        }

        @Override
        public List<Tool> getAvailableTools() {
            return tools;
        }

        @Override
        public ExecutionEnvironmentProvider getExecutionEnvironmentProvider() {
            return provider;
        }
    }

    /** Records the stamp map each firing hands its steps, and how many stamps it held at that moment. */
    private static final class StampCapturingTool extends AbstractTool {

        private final List<Map<String, FileStamp>> maps = new ArrayList<>();
        private final List<Integer> sizesAtCapture = new ArrayList<>();

        StampCapturingTool() {
            super("StampCapture", "test tool", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public synchronized ToolResult execute(ToolInput input, ToolContext context) {
            final Map<String, FileStamp> stamps = context.get(ReadTool.FILE_STAMPS_KEY).orElse(null);
            maps.add(stamps);
            sizesAtCapture.add(stamps == null ? -1 : stamps.size());
            return ToolResult.success("ok");
        }
    }
}
