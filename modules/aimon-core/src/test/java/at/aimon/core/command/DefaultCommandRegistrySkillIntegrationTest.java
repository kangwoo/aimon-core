package at.aimon.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.command.exception.CommandException;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.command.skill.SkillBackedCommand;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;

@DisplayName("DefaultCommandRegistry skill-backed integration (SK-08-F)")
class DefaultCommandRegistrySkillIntegrationTest {

    private static final String LEGACY_DIR = ".aimon/commands";

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private Path legacyCommandsDir;

    @BeforeEach
    void setUp() {
        legacyCommandsDir = tempDir.resolve(LEGACY_DIR);
        final LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    @DisplayName("System and skill sources resolve through their own paths")
    void shouldRouteLookupThroughEachSource() {
        final StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(userInvocable("commit", "skill commit"));
        final List<SystemCommand> systemCommands = List.of(new FakeSystemCommand("help", "built-in help"));

        final DefaultCommandRegistry registry = new DefaultCommandRegistry(systemCommands, skills, fileSystem,
                LEGACY_DIR);
        registry.initialize();

        final Optional<Command> help = registry.getCommand("help");
        assertThat(help).isPresent();
        assertThat(help.get().getType()).isEqualTo(CommandType.SYSTEM);

        final Optional<Command> commit = registry.getCommand("commit");
        assertThat(commit).isPresent().get().isInstanceOf(SkillBackedCommand.class);
    }

    @Test
    @DisplayName("getAllCommands merges system + skill")
    void shouldMergeBothSources() {
        final StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(userInvocable("commit", "skill commit"));
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(
                List.of(new FakeSystemCommand("help", "help")), skills, fileSystem, LEGACY_DIR);
        registry.initialize();

        assertThat(registry.getAllCommands()).extracting(Command::getName).containsExactlyInAnyOrder("help", "commit");
    }

    @Test
    @DisplayName("getSkillBackedCommands returns only user-invocable skills")
    void shouldExposeOnlyUserInvocableSkills() {
        final StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(userInvocable("commit", "skill"));
        skills.put(modelOnly("internal"));
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(List.<SystemCommand>of(), skills, fileSystem,
                LEGACY_DIR);
        registry.initialize();

        assertThat(registry.getSkillBackedCommands()).extracting(Command::getName).containsExactly("commit");
    }

    @Test
    @DisplayName("initialize() rejects conflict between system and skill")
    void shouldFailInitializeOnSystemSkillConflict() {
        final StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(userInvocable("help", "skill help"));
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(
                List.of(new FakeSystemCommand("help", "built-in")), skills, fileSystem, LEGACY_DIR);

        assertThatThrownBy(registry::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'help'").hasMessageContaining("system").hasMessageContaining("skill");
    }

    @Test
    @DisplayName("initialize() rejects when legacy .aimon/commands/*.md files are still present")
    void shouldRejectLegacyCustomCommandFiles() throws Exception {
        Files.createDirectories(legacyCommandsDir);
        Files.writeString(legacyCommandsDir.resolve("legacy.md"), "---\ndescription: legacy\n---\nbody\n");

        final DefaultCommandRegistry registry = new DefaultCommandRegistry(List.<SystemCommand>of(),
                new StubSkillRegistry(), fileSystem, LEGACY_DIR);

        // The workspace root has to be in the message: LEGACY_DIR is filesystem-relative and identical for every
        // agent runtime, so on a multi-tenant deployment it alone does not identify whose workspace to clean up.
        assertThatThrownBy(registry::initialize).isInstanceOf(CommandException.class)
                .hasMessageContaining("Legacy CustomCommand").hasMessageContaining("legacy.md")
                .hasMessageContaining(".aimon/skills").hasMessageContaining(fileSystem.getWorkingDirectory());
    }

    @Test
    @DisplayName("initialize() succeeds when legacy directory is absent")
    void shouldSucceedWithoutLegacyDirectory() {
        // Note: legacyCommandsDir is not created in setUp().
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(
                List.of(new FakeSystemCommand("help", "help")), new StubSkillRegistry(), fileSystem, LEGACY_DIR);

        assertThatCode(registry::initialize).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("initialize() succeeds when legacy directory exists but is empty")
    void shouldSucceedWithEmptyLegacyDirectory() throws Exception {
        Files.createDirectories(legacyCommandsDir);
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(
                List.of(new FakeSystemCommand("help", "help")), new StubSkillRegistry(), fileSystem, LEGACY_DIR);

        assertThatCode(registry::initialize).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reloadAll re-runs the legacy rejection so a freshly added legacy file fails fast")
    void shouldFailReloadAllWhenLegacyFileAddedAfterInit() throws Exception {
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(List.<SystemCommand>of(),
                new StubSkillRegistry(), fileSystem, LEGACY_DIR);
        registry.initialize();

        Files.createDirectories(legacyCommandsDir);
        Files.writeString(legacyCommandsDir.resolve("commit.md"), "---\ndescription: legacy\n---\nbody\n");

        assertThatThrownBy(registry::reloadAll).isInstanceOf(CommandException.class).hasMessageContaining("commit.md");
    }

    @Test
    @DisplayName("Two-arg test convenience constructor (no system, no skill registry)")
    void shouldSupportTestConvenienceConstructor() {
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(fileSystem, LEGACY_DIR);
        assertThatCode(registry::initialize).doesNotThrowAnyException();

        assertThat(registry.getSystemCommands()).isEmpty();
        assertThat(registry.getSkillBackedCommands()).isEmpty();
        assertThat(registry.getAllCommands()).isEmpty();
    }

    @Test
    @DisplayName("hasCommand consults skill-backed source")
    void shouldReportSkillBackedNameAsPresent() {
        final StubSkillRegistry skills = new StubSkillRegistry();
        skills.put(userInvocable("commit", "skill commit"));
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(List.<SystemCommand>of(), skills, fileSystem,
                LEGACY_DIR);
        registry.initialize();

        assertThat(registry.hasCommand("commit")).isTrue();
        assertThat(registry.isSystemCommand("commit")).isFalse();
    }

    @Test
    @DisplayName("registerSystemCommand and unregisterSystemCommand mutate the system source")
    void shouldRegisterAndUnregisterSystemCommands() {
        final DefaultCommandRegistry registry = new DefaultCommandRegistry(List.<SystemCommand>of(),
                new StubSkillRegistry(), fileSystem, LEGACY_DIR);
        registry.initialize();

        assertThat(registry.getSystemCommands()).isEmpty();

        registry.registerSystemCommand(new FakeSystemCommand("ping", "ping"));
        assertThat(registry.hasCommand("ping")).isTrue();
        assertThat(registry.isSystemCommand("ping")).isTrue();

        Optional<SystemCommand> removed = registry.unregisterSystemCommand("ping");
        assertThat(removed).isPresent();
        assertThat(registry.hasCommand("ping")).isFalse();
    }

    @Test
    @DisplayName("Constructor rejects null system commands, null file system, null legacy directory")
    void shouldRejectNullArgs() {
        assertThatThrownBy(() -> new DefaultCommandRegistry(null, new StubSkillRegistry(), fileSystem, LEGACY_DIR))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("System commands");
        assertThatThrownBy(
                () -> new DefaultCommandRegistry(List.<SystemCommand>of(), new StubSkillRegistry(), null, LEGACY_DIR))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("File system");
        assertThatThrownBy(
                () -> new DefaultCommandRegistry(List.<SystemCommand>of(), new StubSkillRegistry(), fileSystem, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Legacy commands directory");
    }

    private static Skill userInvocable(String name, String description) {
        final SkillMetadata m = SkillMetadata.builder().name(name).description(description)
                .invokePolicy(InvokePolicy.of(true, true)).build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body")).build();
    }

    private static Skill modelOnly(String name) {
        final SkillMetadata m = SkillMetadata.builder().name(name).description("model-only")
                .invokePolicy(InvokePolicy.of(false, true)).build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body")).build();
    }

    private static final class FakeSystemCommand extends SystemCommand implements DirectExecutable {
        FakeSystemCommand(String name, String description) {
            super(name, description);
        }

        @Override
        public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
            return CommandExecutionResult.success("ok");
        }
    }

    private static final class StubSkillRegistry implements SkillRegistry {
        private final Map<String, Skill> skills = new LinkedHashMap<>();
        private final List<String> reloaded = new ArrayList<>();
        private int reloadAllCount;

        void put(Skill skill) {
            skills.put(skill.getName(), skill);
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return Optional.ofNullable(skills.get(skillName));
        }

        @Override
        public List<Skill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public void reloadSkill(String skillName) {
            reloaded.add(skillName);
        }

        @Override
        public void reloadAll() {
            reloadAllCount++;
        }
    }
}
