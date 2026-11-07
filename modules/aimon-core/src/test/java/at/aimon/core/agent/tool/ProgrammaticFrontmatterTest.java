package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProgrammaticFrontmatter")
class ProgrammaticFrontmatterTest {

    private static Map<String, Object> fm(Object... keyValues) {
        final Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("absent keys mean none()")
    void absent() {
        assertThat(ProgrammaticFrontmatter.parse(fm("name", "x"))).isEqualTo(ProgrammaticToolSettings.none());
    }

    @Test
    @DisplayName("parses the keys from YAML lists or comma strings")
    void parsesKeys() {
        final ProgrammaticToolSettings settings = ProgrammaticFrontmatter.parse(fm("programmatic-tools",
                List.of("Read", "Grep", "mcp__prometheus__query"), "programmatic-only", "mcp__prometheus__query",
                "programmatic-engine", "JavaScript", "shell-engine-tool-scope", "host"));

        assertThat(settings.getTools()).containsExactly("Read", "Grep", "mcp__prometheus__query");
        assertThat(settings.getCodeOnly()).containsExactly("mcp__prometheus__query");
        assertThat(settings.getEngine()).isEqualTo(ProgrammaticEngineChoice.JAVASCRIPT);
        assertThat(settings.getShellEngineToolScope()).hasValue(ShellEngineToolScope.HOST);
    }

    @Test
    @DisplayName("a comma string is trimmed and blank entries dropped")
    void commaString() {
        assertThat(ProgrammaticFrontmatter.parse(fm("programmatic-tools", " Read , ,Grep ")).getTools())
                .containsExactly("Read", "Grep");
        assertThat(ProgrammaticFrontmatter.parse(fm("programmatic-tools", "Read")).getShellEngineToolScope()).isEmpty();
    }

    @Test
    @DisplayName("refuses programmatic-only outside programmatic-tools")
    void notSubset() {
        assertThatThrownBy(
                () -> ProgrammaticFrontmatter.parse(fm("programmatic-tools", "Read", "programmatic-only", "Grep")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subset")
                .hasMessageStartingWith("Invalid programmatic tool settings: ");
    }

    @Test
    @DisplayName("refuses wildcards and argument patterns")
    void notExact() {
        assertThatThrownBy(() -> ProgrammaticFrontmatter.parse(fm("programmatic-tools", "mcp__x__*")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact tool name");
        assertThatThrownBy(() -> ProgrammaticFrontmatter.parse(fm("programmatic-tools", "Bash(git:*)")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact tool name");
    }

    @Test
    @DisplayName("refuses an unknown engine or scope")
    void unknownValues() {
        assertThatThrownBy(
                () -> ProgrammaticFrontmatter.parse(fm("programmatic-tools", "Read", "programmatic-engine", "ruby")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown programmatic engine");
        assertThatThrownBy(() -> ProgrammaticFrontmatter
                .parse(fm("programmatic-tools", "Read", "shell-engine-tool-scope", "everything")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shell engine tool scope");
    }

    @Test
    @DisplayName("refuses the dependent keys without programmatic-tools")
    void needsTools() {
        for (String key : List.of("programmatic-only", "programmatic-engine", "shell-engine-tool-scope")) {
            assertThatThrownBy(() -> ProgrammaticFrontmatter.parse(fm(key,
                    key.equals("programmatic-only")
                            ? "Read"
                            : key.equals("programmatic-engine") ? "javascript" : "host")))
                    .as(key).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("need 'programmatic-tools'");
        }
    }

    @Test
    @DisplayName("refuses camelCase spellings by name")
    void camelCase() {
        for (String key : List.of("programmaticTools", "programmaticOnly", "programmaticEngine",
                "shellEngineToolScope")) {
            assertThatThrownBy(() -> ProgrammaticFrontmatter.parse(fm("programmatic-tools", "Read", key, "x"))).as(key)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kebab-case")
                    .hasMessageContaining(key);
        }
    }

    @Test
    @DisplayName("refuses values of the wrong shape")
    void wrongShape() {
        assertThatThrownBy(() -> ProgrammaticFrontmatter.parse(fm("programmatic-tools", 42)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected a YAML list or a comma-separated string");
        assertThatThrownBy(() -> ProgrammaticFrontmatter.parse(fm("programmatic-tools", List.of("Read", 7))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entry: expected a string");
        assertThatThrownBy(() -> ProgrammaticFrontmatter
                .parse(fm("programmatic-tools", "Read", "programmatic-engine", List.of("javascript"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected a string");
    }

    @Test
    @DisplayName("refuses null frontmatter")
    void nullFrontmatter() {
        assertThatThrownBy(() -> ProgrammaticFrontmatter.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
