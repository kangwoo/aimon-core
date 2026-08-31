package at.aimon.core.skill.hook.declarative.predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;

class PathGlobPredicateTest {

    @Test
    void of_nullOrBlankArgs_throw() {
        assertThatThrownBy(() -> PathGlobPredicate.of(null, "*.ts")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PathGlobPredicate.of("Edit", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PathGlobPredicate.of("", "*.ts")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathGlobPredicate.of("Edit", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void test_nonMatchingTool_returnsFalse() {
        PathGlobPredicate p = PathGlobPredicate.of("Edit", "*.ts");

        assertThat(p.test("Read", ToolInput.of(Map.of("file_path", "x.ts")))).isFalse();
        assertThat(p.test("Bash", ToolInput.of(Map.of("file_path", "x.ts")))).isFalse();
    }

    @Test
    void test_editFilePathGlobMatches() {
        PathGlobPredicate p = PathGlobPredicate.of("Edit", "*.ts");

        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", "src/foo.ts")))).isTrue();
        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", "foo.tsx")))).isFalse();
        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", "foo.js")))).isFalse();
    }

    @Test
    void test_writeExactDotEnv() {
        PathGlobPredicate p = PathGlobPredicate.of("Write", ".env");

        assertThat(p.test("Write", ToolInput.of(Map.of("file_path", ".env")))).isTrue();
        assertThat(p.test("Write", ToolInput.of(Map.of("file_path", "config/.env")))).isFalse();
        assertThat(p.test("Write", ToolInput.of(Map.of("file_path", ".env.local")))).isFalse();
    }

    @Test
    void test_readSecretsPrefix() {
        PathGlobPredicate p = PathGlobPredicate.of("Read", "secrets.*");

        assertThat(p.test("Read", ToolInput.of(Map.of("file_path", "secrets.json")))).isTrue();
        assertThat(p.test("Read", ToolInput.of(Map.of("file_path", "secrets.yaml")))).isTrue();
        assertThat(p.test("Read", ToolInput.of(Map.of("file_path", "config.json")))).isFalse();
    }

    @Test
    void test_globToolPattern_field() {
        PathGlobPredicate p = PathGlobPredicate.of("Glob", "*.ts");

        // Glob tool stores the user-supplied glob in `pattern`.
        assertThat(p.test("Glob", ToolInput.of(Map.of("pattern", "src/*.ts")))).isTrue();
        // It also accepts `path` as a secondary fallback.
        assertThat(p.test("Glob", ToolInput.of(Map.of("path", "x.ts")))).isTrue();
    }

    @Test
    void test_grepPath_field() {
        PathGlobPredicate p = PathGlobPredicate.of("Grep", "*.java");

        assertThat(p.test("Grep", ToolInput.of(Map.of("path", "Main.java")))).isTrue();
        assertThat(p.test("Grep", ToolInput.of(Map.of("path", "Main.kt")))).isFalse();
    }

    @Test
    void test_lsPath_field() {
        PathGlobPredicate p = PathGlobPredicate.of("LS", "/etc/*");

        assertThat(p.test("LS", ToolInput.of(Map.of("path", "/etc/passwd")))).isTrue();
        assertThat(p.test("LS", ToolInput.of(Map.of("path", "/var/log")))).isFalse();
    }

    @Test
    void test_notebookEditPath_field() {
        PathGlobPredicate p = PathGlobPredicate.of("NotebookEdit", "*.ipynb");

        assertThat(p.test("NotebookEdit", ToolInput.of(Map.of("notebook_path", "demo.ipynb")))).isTrue();
        assertThat(p.test("NotebookEdit", ToolInput.of(Map.of("notebook_path", "demo.py")))).isFalse();
    }

    @Test
    void test_unknownTool_fallsBackToConventionalFields() {
        PathGlobPredicate p = PathGlobPredicate.of("MyCustomTool", "*.txt");

        assertThat(p.test("MyCustomTool", ToolInput.of(Map.of("file_path", "a.txt")))).isTrue();
        assertThat(p.test("MyCustomTool", ToolInput.of(Map.of("path", "a.txt")))).isTrue();
        assertThat(p.test("MyCustomTool", ToolInput.of(Map.of("pattern", "a.txt")))).isTrue();
        assertThat(p.test("MyCustomTool", ToolInput.of(Map.of("other", "a.txt")))).isFalse();
    }

    @Test
    void test_missingPathField_returnsFalse() {
        PathGlobPredicate p = PathGlobPredicate.of("Edit", "*.ts");

        assertThat(p.test("Edit", ToolInput.of())).isFalse();
        assertThat(p.test("Edit", ToolInput.of(Map.of("other", "x.ts")))).isFalse();
    }

    @Test
    void test_emptyPath_returnsFalse() {
        PathGlobPredicate p = PathGlobPredicate.of("Edit", "*.ts");

        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", "")))).isFalse();
    }

    @Test
    void test_nonStringPathValue_returnsFalse() {
        PathGlobPredicate p = PathGlobPredicate.of("Edit", "*.ts");

        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", 42)))).isFalse();
    }

    @Test
    void test_nullInputs_throw() {
        PathGlobPredicate p = PathGlobPredicate.of("Edit", "*.ts");

        assertThatThrownBy(() -> p.test(null, ToolInput.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> p.test("Edit", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void test_globRegexMetacharsLiteral() {
        PathGlobPredicate p = PathGlobPredicate.of("Edit", "secrets.*");

        // '.' in the pattern is a literal dot, not regex any-char.
        assertThat(p.test("Edit", ToolInput.of(Map.of("file_path", "secretsXjson")))).isFalse();
    }

    @Test
    void test_getters() {
        PathGlobPredicate p = PathGlobPredicate.of("Edit", "*.ts");

        assertThat(p.getBoundToolName()).isEqualTo("Edit");
        assertThat(p.getPattern()).isEqualTo("*.ts");
    }
}
