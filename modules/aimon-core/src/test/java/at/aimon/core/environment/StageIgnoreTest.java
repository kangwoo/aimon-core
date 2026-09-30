package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StageIgnore")
class StageIgnoreTest {

    @Test
    @DisplayName("none() ignores nothing")
    void none() {
        assertThat(StageIgnore.none().ignored("a/b.txt")).isFalse();
        assertThat(StageIgnore.parse("\n# only a comment\n").ignored("a")).isFalse();
    }

    @Test
    @DisplayName("an unanchored glob matches a file name at any depth")
    void unanchoredGlob() {
        final StageIgnore ignore = StageIgnore.parse("*.bin");
        assertThat(ignore.ignored("model.bin")).isTrue();
        assertThat(ignore.ignored("assets/deep/model.bin")).isTrue();
        assertThat(ignore.ignored("model.binx")).isFalse();
        assertThat(ignore.ignored("model.txt")).isFalse();
    }

    @Test
    @DisplayName("? matches exactly one character")
    void questionMark() {
        final StageIgnore ignore = StageIgnore.parse("file?.txt");
        assertThat(ignore.ignored("file1.txt")).isTrue();
        assertThat(ignore.ignored("file12.txt")).isFalse();
    }

    @Test
    @DisplayName("a directory pattern ignores everything under the directory but not a file of that name")
    void directoryPattern() {
        final StageIgnore ignore = StageIgnore.parse("assets/");
        assertThat(ignore.ignored("assets/big.png")).isTrue();
        assertThat(ignore.ignored("assets/sub/big.png")).isTrue();
        assertThat(ignore.ignored("nested/assets/x")).isTrue();
        assertThat(ignore.ignored("assets")).isFalse();
    }

    @Test
    @DisplayName("an anchored pattern matches from the resource root only")
    void anchored() {
        final StageIgnore ignore = StageIgnore.parse("/data/*.csv");
        assertThat(ignore.ignored("data/a.csv")).isTrue();
        assertThat(ignore.ignored("sub/data/a.csv")).isFalse();
    }

    @Test
    @DisplayName("** crosses directories")
    void doubleStar() {
        final StageIgnore ignore = StageIgnore.parse("docs/**/*.pdf");
        assertThat(ignore.ignored("docs/a.pdf")).isTrue();
        assertThat(ignore.ignored("docs/x/y/a.pdf")).isTrue();
        assertThat(ignore.ignored("other/a.pdf")).isFalse();
    }

    @Test
    @DisplayName("negation re-includes, and the last matching rule wins")
    void negation() {
        final StageIgnore ignore = StageIgnore.parse("*.log\n!keep.log\n# comment\n\n");
        assertThat(ignore.ignored("a.log")).isTrue();
        assertThat(ignore.ignored("keep.log")).isFalse();
        final StageIgnore reversed = StageIgnore.parse("!keep.log\n*.log");
        assertThat(reversed.ignored("keep.log")).isTrue();
    }

    @Test
    @DisplayName("comment lines are not patterns")
    void comments() {
        assertThat(StageIgnore.parse("# *.txt").ignored("a.txt")).isFalse();
    }
}
