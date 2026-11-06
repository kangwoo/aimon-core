package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShellEngineToolScope")
class ShellEngineToolScopeTest {

    @Test
    @DisplayName("bounds data reach: environment < host < unrestricted")
    void admits() {
        assertThat(ShellEngineToolScope.ENVIRONMENT.admits(DataReach.ENVIRONMENT)).isTrue();
        assertThat(ShellEngineToolScope.ENVIRONMENT.admits(DataReach.HOST)).isFalse();
        assertThat(ShellEngineToolScope.HOST.admits(DataReach.HOST)).isTrue();
        assertThat(ShellEngineToolScope.HOST.admits(DataReach.EXTERNAL)).isFalse();
        assertThat(ShellEngineToolScope.UNRESTRICTED.admits(DataReach.EXTERNAL)).isTrue();
    }

    @Test
    @DisplayName("min is the narrower; parse ignores case and refuses unknown values")
    void minAndParse() {
        assertThat(ShellEngineToolScope.HOST.min(ShellEngineToolScope.ENVIRONMENT))
                .isEqualTo(ShellEngineToolScope.ENVIRONMENT);
        assertThat(ShellEngineToolScope.HOST.min(ShellEngineToolScope.UNRESTRICTED))
                .isEqualTo(ShellEngineToolScope.HOST);
        assertThat(ShellEngineToolScope.parse(" Host ")).isEqualTo(ShellEngineToolScope.HOST);
        assertThat(ShellEngineToolScope.UNRESTRICTED.wireName()).isEqualTo("unrestricted");
        assertThatThrownBy(() -> ShellEngineToolScope.parse("all")).isInstanceOf(IllegalArgumentException.class);
    }
}
