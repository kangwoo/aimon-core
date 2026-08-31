package at.aimon.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.command.CommandNameConflictDetector.Source;

class CommandNameConflictDetectorTest {

    private final CommandNameConflictDetector detector = new CommandNameConflictDetector();

    @Test
    void shouldReturnSilentlyWhenNoConflicts() {
        StubRegistry system = new StubRegistry("help", "version");
        StubRegistry skills = new StubRegistry("commit", "deploy");

        assertThatCode(
                () -> detector.verifyNoConflicts(List.of(Source.of("system", system), Source.of("skill", skills))))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldReturnSilentlyWithEmptySources() {
        assertThatCode(() -> detector.verifyNoConflicts(List.of())).doesNotThrowAnyException();
    }

    @Test
    void shouldFlagConflictBetweenTwoSources() {
        StubRegistry system = new StubRegistry("help");
        StubRegistry skills = new StubRegistry("help"); // Conflicts with system

        assertThatThrownBy(
                () -> detector.verifyNoConflicts(List.of(Source.of("system", system), Source.of("skill", skills))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'help'").hasMessageContaining("system")
                .hasMessageContaining("skill");
    }

    @Test
    void shouldListEveryConflictingNameInSortedOrder() {
        StubRegistry system = new StubRegistry("zeta", "alpha", "mu");
        StubRegistry skills = new StubRegistry("mu", "zeta", "alpha");

        assertThatThrownBy(
                () -> detector.verifyNoConflicts(List.of(Source.of("system", system), Source.of("skill", skills))))
                .isInstanceOf(IllegalStateException.class).satisfies(ex -> {
                    String msg = ex.getMessage();
                    int alpha = msg.indexOf("'alpha'");
                    int mu = msg.indexOf("'mu'");
                    int zeta = msg.indexOf("'zeta'");
                    assertThat(alpha).isPositive();
                    assertThat(alpha).isLessThan(mu);
                    assertThat(mu).isLessThan(zeta);
                });
    }

    @Test
    void shouldNotFlagDuplicateInsideSingleSource() {
        // Within-source duplicates are the source's own concern; the detector only checks across sources. We assert
        // that even a registry that exposes the same name twice in getAllCommands() (which a real implementation
        // should never do) is left alone — preventing the detector from accidentally policing intra-source state.
        ListRegistry skills = new ListRegistry(List.of(new StubCommand("commit"), new StubCommand("commit")));

        assertThatCode(() -> detector.verifyNoConflicts(List.of(Source.of("skill", skills))))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNullSourcesList() {
        assertThatThrownBy(() -> detector.verifyNoConflicts(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Sources");
    }

    @Test
    void shouldRejectNullSourceEntry() {
        assertThatThrownBy(() -> {
            List<Source> sources = new java.util.ArrayList<>();
            sources.add(null);
            detector.verifyNoConflicts(sources);
        }).isInstanceOf(NullPointerException.class).hasMessageContaining("Source");
    }

    @Test
    void shouldRejectBlankLabel() {
        StubRegistry r = new StubRegistry("commit");
        assertThatThrownBy(() -> Source.of(" ", r)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Label");
    }

    @Test
    void shouldRejectNullLabel() {
        StubRegistry r = new StubRegistry("commit");
        assertThatThrownBy(() -> Source.of(null, r)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Label");
    }

    @Test
    void shouldRejectNullRegistry() {
        assertThatThrownBy(() -> Source.of("system", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Registry");
    }

    @Test
    void shouldIncludeRecommendedActionInMessage() {
        StubRegistry a = new StubRegistry("commit");
        StubRegistry b = new StubRegistry("commit");

        assertThatThrownBy(() -> detector.verifyNoConflicts(List.of(Source.of("system", a), Source.of("skill", b))))
                .hasMessageContaining("Rename or remove");
    }

    private static final class StubRegistry implements CommandRegistry {
        private final Map<String, Command> commands = new LinkedHashMap<>();

        StubRegistry(String... names) {
            for (String n : names) {
                commands.put(n, new StubCommand(n));
            }
        }

        @Override
        public Optional<Command> getCommand(String commandName) {
            return Optional.ofNullable(commands.get(commandName));
        }

        @Override
        public List<Command> getAllCommands() {
            return List.copyOf(commands.values());
        }

        @Override
        public List<Command> getSystemCommands() {
            return List.of();
        }

        @Override
        public boolean hasCommand(String commandName) {
            return commands.containsKey(commandName);
        }

        @Override
        public boolean isSystemCommand(String commandName) {
            return false;
        }

        @Override
        public void reloadCommand(String commandName) {
        }

        @Override
        public void reloadAll() {
        }
    }

    private static final class ListRegistry implements CommandRegistry {
        private final List<Command> commands;

        ListRegistry(List<Command> commands) {
            this.commands = List.copyOf(commands);
        }

        @Override
        public Optional<Command> getCommand(String commandName) {
            return commands.stream().filter(c -> c.getName().equals(commandName)).findFirst();
        }

        @Override
        public List<Command> getAllCommands() {
            return commands;
        }

        @Override
        public List<Command> getSystemCommands() {
            return List.of();
        }

        @Override
        public boolean hasCommand(String commandName) {
            return getCommand(commandName).isPresent();
        }

        @Override
        public boolean isSystemCommand(String commandName) {
            return false;
        }

        @Override
        public void reloadCommand(String commandName) {
        }

        @Override
        public void reloadAll() {
        }
    }

    private static final class StubCommand implements Command {
        private final String name;

        StubCommand(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public CommandMetadata getMetadata() {
            return CommandMetadata.empty();
        }

        @Override
        public CommandType getType() {
            return CommandType.CUSTOM;
        }
    }
}
