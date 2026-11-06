package at.aimon.core.skill.hook.declarative.predicate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.skill.hook.declarative.ToolInputPredicate;

@DisplayName("ToolInputPredicate.mayMatchTool — the predicate's answer with the arguments unknown")
class MayMatchToolTest {

    @Test
    @DisplayName("name-only, Bash sub-command, path glob and composites answer by tool name, conservatively")
    void mayMatchTool() {
        assertThat(PredicateParser.parse("Bash").mayMatchTool("Bash")).isTrue();
        assertThat(PredicateParser.parse("Write").mayMatchTool("Bash")).isFalse();
        assertThat(PredicateParser.parse("*").mayMatchTool("Bash")).isTrue();
        assertThat(PredicateParser.parse("Bash(git *)").mayMatchTool("Bash")).isTrue();
        assertThat(PredicateParser.parse("Bash(git *)").mayMatchTool("Read")).isFalse();
        assertThat(PredicateParser.parse("Edit(*.ts)").mayMatchTool("Bash")).isFalse();
        assertThat(PredicateParser.parse("Write|Edit(*.ts)").mayMatchTool("Bash")).isFalse();
        assertThat(PredicateParser.parse("Write|Bash(rm *)").mayMatchTool("Bash")).isTrue();
        final ToolInputPredicate custom = (name, input) -> false;
        assertThat(custom.mayMatchTool("Bash")).as("an unknown predicate may match anything").isTrue();
    }
}
